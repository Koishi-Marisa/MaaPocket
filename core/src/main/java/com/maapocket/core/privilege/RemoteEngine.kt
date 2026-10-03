package com.maapocket.core.privilege

import android.graphics.Bitmap
import android.os.SystemClock
import com.maapocket.core.bridge.NativeBridgeLib
import com.maapocket.core.constant.DefaultDisplayConfig
import com.maapocket.core.maa.DriverClass
import com.maapocket.core.maafw.MaaAgentRuntime
import com.maapocket.core.maafw.MaaController
import com.maapocket.core.maafw.MaaDef
import com.maapocket.core.maafw.MaaEventCallback
import com.maapocket.core.maafw.MaaFrameworkApi
import com.maapocket.core.maafw.MaaFw
import com.maapocket.core.maafw.MaaResource
import com.maapocket.core.maafw.MaaSinks
import com.maapocket.core.maafw.MaaTasker
import com.maapocket.core.pi.AgentHandle
import com.maapocket.core.pi.AgentHandshakeException
import com.maapocket.core.pi.AgentLauncher
import com.maapocket.core.pi.AgentSpawnException
import com.maapocket.core.pi.PiAgentEnv
import com.maapocket.core.privilege.RemoteProtocol.Cmd
import com.maapocket.core.privilege.RemoteProtocol.ErrorCode
import com.maapocket.core.privilege.RemoteProtocol.Event
import com.maapocket.core.third.Ln
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Field
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 特权进程里的**实际业务**：屏幕 + 截图 + 输入注入 + 拉起游戏 + 跑 MaaFramework。
 *
 * 对应 MAA-Meow `remote/RemoteServiceImpl.kt`（558 行）里除 AIDL/binder 样板之外的部分。
 * 这里**没有** Arknights 业务逻辑（没有作业/关卡/干员那些），只有把命令翻译成
 * `third/wrappers/\*`、`maa/DriverClass` 和 `maafw/\*` 的调用。
 *
 * ## 与 `RemoteMain` 的分工
 * - `RemoteMain`：进程引导（Context、Looper、异常处理器）、socket 服务、app 看门狗；
 * - `RemoteEngine`：装命令处理器（[install]）+ 做活。它是**无 Context** 的——
 *   显示/输入全走反射出来的系统服务（见 `RemoteMain` 头部注释）。
 *
 * ## 为什么 MaaFramework 必须住在这个进程里（而不是 app 进程）
 * MaaFramework 通过外部库 `bridge` 取帧/注输入，而 `bridge` 与 MaaFramework 之间靠
 * **进程内符号**通信（`GetLockedPixels` / `UnlockPixels` / `DispatchInputMessage`），
 * 所以两者必须同进程；同时只有特权进程能建虚拟屏、能把输入注到别的 display 上。
 * 因此 controller / resource / tasker 全部由本类持有，app 只发命令。
 *
 * ## 长任务与多路复用（需求 7）
 * 每个 `cmd` 由 `RemoteServer` 丢到独立 worker 线程执行，所以
 * `display.start`（要建屏 + 等 Surface）或 `task.run`（可能跑几十分钟）在跑的时候
 * `ping`/`maa.state`/`task.stop` 依然立即回。这里唯一需要自己管线程的是
 * `capture.preview`：它是一个持续循环，由 [Cmd.CAPTURE_PREVIEW] 的 `enable` 开关控制，
 * **不占用 worker**。
 *
 * ## 帧的传输（为什么默认 base64 而不是文件）
 * 一行最多 [RemoteProtocol.MAX_LINE_BYTES]（1 MiB）。720p 的 PNG 动辄 1~2 MB，
 * base64 后必然越界；JPEG(q=80) 约 100~200 KB，base64 约 140~270 KB，稳。
 * 所以默认 `format=jpeg` + 内联 base64，并用 `maxBytes` 卡住（超过就报错，
 * 让调用方降质量或改用 `path` 落盘）。`path` 模式让特权进程直接写文件——
 * 它通常是 root/shell，写哪里都行，适合需要无损 PNG 的场景。
 */
class RemoteEngine(
    private val eventSink: EventSink,
) {

    /**
     * 事件出口。用 `fun interface` 是为了 `RemoteMain.java` 能直接传 Java lambda
     * （Kotlin 的 `(String, JsonObject?) -> Boolean` 从 Java 传要写 `Function2`，太丑）。
     *
     * **返回 false = 事件被丢弃**（服务端队列满，app 跟不上）。调用方要据此背压。
     */
    fun interface EventSink {
        fun emit(event: String, data: JsonObject?): Boolean
    }

    /**
     * 工作目录（MaaFramework 的资源/日志/帧都落在这里）。由 `engine.setup` 由 app 告知——
     * 特权进程不去猜 app 的私有目录（MAA-Meow 是靠 `RemoteServiceStarter` 里那套
     * package Context 拿到的，我们把它挪进了协议参数）。
     *
     * app 传进来的会是 `/sdcard/Android/data/<pkg>/files/Maa`（外部私有目录）：
     * helper 以 shell(2000) 身份跑，读不到 `/data/data/<pkg>/files`。
     */
    private var userDir: File? = null

    /** app 的 `applicationInfo.nativeLibraryDir`。helper 是独立进程，dlopen 需要真实路径。 */
    private var nativeLibraryDir: File? = null

    private fun requireUserDir(): File = userDir ?: throw RemoteProtocolException(
        ErrorCode.NOT_READY,
        "userDir is not set yet; send engine.setup with a userDir first",
    )

    private fun requireNativeLibraryDir(): File = nativeLibraryDir ?: throw RemoteProtocolException(
        ErrorCode.NOT_READY,
        "nativeLibraryDir is not set yet; pass it in engine.setup",
        "the helper is a separate process, so dlopen needs the real <nativeLibraryDir>/libbridge.so path",
    )

    private var server: RemoteServer? = null

    private var display: DisplaySession? = null

    private var displayMode: DisplaySession.Mode = DisplaySession.Mode.VIRTUAL

    private val previewRunning = AtomicBoolean(false)

    private var previewThread: Thread? = null

    @Volatile
    private var previewStop = false

    private var previewIntervalMs = DEFAULT_PREVIEW_INTERVAL_MS

    private var previewInline = false

    private var previewMaxBytes = DEFAULT_INLINE_MAX_BYTES

    /** 最近一次 `engine.setup` 的结果，供 `engine.info` 复用。 */
    private var setupOk: Boolean? = null

    private var setupDetail: String? = null

    // ------------------------------------------------------------------ MaaFramework 状态

    /**
     * 全部 Maa 句柄的归属。所有写操作都在 [maaLock] 里做，读操作尽量读 volatile 字段，
     * 因为命令是在不同 worker 线程上跑的（`maa.state`、`task.stop` 会与 `task.run` 并发）。
     */
    private val maaLock = Any()

    @Volatile
    private var controller: MaaController? = null

    @Volatile
    private var resource: MaaResource? = null

    @Volatile
    private var tasker: MaaTasker? = null

    /** 正在跑的 task id；`null` = 没在跑（但 [tasker] 可能还在，供 `task.stop` 复用）。 */
    private val taskId = AtomicLong(-1L)

    private val taskRunning = AtomicBoolean(false)

    /** tasker 的事件 sink 与其 id（`MaaCallbackRegistry` 要求成对 release，否则每次会话泄漏一个）。 */
    private var taskSink: MaaEventCallback? = null

    private var taskSinkId = 0L

    /**
     * 已经握手成功的 agent 子进程（PI 的 `child_exec`，如 `agent/go-service`），key = 标签。
     *
     * 与其它 Maa 句柄一样由 [maaLock] 保护，但**关闭动作一律在锁外**：`AgentHandle.close()`
     * 要 `disconnect` + `destroy` + 最多 2s 宽限 + 强杀，拿着 [maaLock] 做这件事会把
     * `maa.state` / `task.stop` 一起堵住。所以取快照 → 出锁 → 逐个关。
     */
    private val agents = LinkedHashMap<String, AgentHandle>()

    /** 见 [pidOf]：按 `Process` 的具体类缓存反射到的 `pid` 字段；查不到时存 [MISSING] 哨兵。 */
    private val pidFieldCache = ConcurrentHashMap<Class<*>, Any>()

    /**
     * MaaFramework 是否真的加载好了。**运行时真实判定**，不再是常量——
     * `MaaFw.ensureLoaded` 成功后才为 true。
     */
    val maaWired: Boolean get() = MaaFw.isLoaded

    // ------------------------------------------------------------------ 注册

    /** 把全部命令注册到服务端。`hello`/`ping`/`shutdown` 由 `RemoteServer` 自己处理。 */
    fun install(server: RemoteServer) {
        this.server = server
        server.on(Cmd.ENGINE_SETUP) { params -> engineSetup(params) }
        server.on(Cmd.ENGINE_INFO) { _ -> engineInfo() }
        server.on(Cmd.DISPLAY_START) { params -> displayStart(params) }
        server.on(Cmd.DISPLAY_STOP) { _ -> displayStop() }
        server.on(Cmd.DISPLAY_RESTART) { params -> displayRestart(params) }
        server.on(Cmd.DISPLAY_RESIZE) { params -> displayResize(params) }
        server.on(Cmd.DISPLAY_STATE) { _ -> displayState() }
        server.on(Cmd.CAPTURE_FRAME) { params -> captureFrame(params) }
        server.on(Cmd.CAPTURE_PREVIEW) { params -> capturePreview(params) }
        server.on(Cmd.INPUT_TOUCH) { params -> inputTouch(params) }
        server.on(Cmd.INPUT_KEY) { params -> inputKey(params) }
        server.on(Cmd.APP_START) { params -> appStart(params) }
        // MaaFramework：controller / resource / tasker 三段式。
        server.on(Cmd.MAA_CONTROLLER_START) { params -> maaControllerStart(params) }
        server.on(Cmd.MAA_RESOURCE_LOAD) { params -> maaResourceLoad(params) }
        server.on(Cmd.MAA_TASK_RUN) { params -> maaTaskRun(params) }
        server.on(Cmd.MAA_TASK_STOP) { _ -> maaTaskStop() }
        server.on(Cmd.MAA_STATE) { _ -> maaState() }
        // agent：外挂扩展进程，必须由本进程拉起（见 agentStart 的注释）。
        server.on(Cmd.AGENT_START) { params -> agentStart(params) }
        server.on(Cmd.AGENT_STOP) { params -> agentStop(params) }
    }

    // ------------------------------------------------------------------ engine.*

    /**
     * 初始化。抄了 MAA-Meow `RemoteServiceImpl.setup` 的两个关键决定：
     * 1. **幂等**：成功后再调直接返回 OK，失败则下次重试；
     * 2. **先探路径可读性再交给 native**：MAA-Meow 的 `UserDirProbe` 注释说
     *    对不可读目录调 `AsstSetUserDir` 会**直接把进程 abort**（issue #227），
     *    所以宁可在这里返回错误，也不能让 native 拿到一个坏路径。
     *
     * 参数：`{userDir?, nativeLibraryDir?, requireMaa?}`。
     *
     * 关于权限：**这里只做 `mkdirs()`，绝不 chmod、不修权限**。app 给的路径本来就是
     * 外部私有目录（`/sdcard/Android/data/<pkg>/files/...`），helper 以 shell(2000)
     * 身份能读；试图“修好”权限只会掩盖 app 侧的配置错误，还可能把别人的目录改成 0777。
     */
    private fun engineSetup(params: JsonObject?): JsonObject {
        val requested = params.str("userDir")?.let { File(it) } ?: userDir
        if (requested == null) {
            throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "userDir is required on the first engine.setup call",
                "the privileged process does not guess the app's private directories",
            )
        }
        params.str("nativeLibraryDir")?.let { nativeLibraryDir = File(it) }

        if (setupOk == true && requested == userDir) {
            return buildJsonObject {
                put("userDir", requested.absolutePath)
                put("cached", true)
                put("maaWired", maaWired)
                put("maaVersion", MaaFw.MaaVersion())
            }
        }

        if (!requested.exists() && !requested.mkdirs()) {
            setupOk = false
            setupDetail = "user dir does not exist and could not be created: ${requested.absolutePath}"
            throw RemoteProtocolException(ErrorCode.BAD_PARAMS, setupDetail!!)
        }
        if (!requested.canRead()) {
            // 关键：绝不把这个路径交给 native（会 abort 整个进程）。
            setupOk = false
            setupDetail = "user dir is not readable: ${requested.absolutePath} (uid=${android.os.Process.myUid()})"
            throw RemoteProtocolException(ErrorCode.BAD_PARAMS, setupDetail!!)
        }

        val frameDir = File(requested, FRAME_DIR_NAME)
        if (!frameDir.exists()) frameDir.mkdirs()

        // 真正的 MaaFramework 加载。放在 userDir 校验**之后**：加载成功后要立刻 setLogDir，
        // 而 setLogDir 需要一个已验证可读的目录。
        val maaRequired = params.bool("requireMaa") == true
        val maaResult = ensureMaaLoaded(requested)
        if (maaResult != null && maaRequired) {
            // 如实把失败原因（类名 + message）传出去，别让调用方猜。
            setupOk = false
            setupDetail = "MaaFramework load failed: ${maaResult.javaClass.name}: ${maaResult.message}"
            throw RemoteProtocolException(
                ErrorCode.MAAFRAMEWORK_NOT_WIRED,
                "requireMaa=true but MaaFramework/libbridge could not be loaded in this process",
                setupDetail,
            )
        }

        setupOk = true
        setupDetail = "ok"
        userDir = requested
        Ln.i(
            "RemoteEngine: setup ok userDir=${requested.absolutePath} nativeLoaded=${NativeBridgeLib.LOADED} " +
                "maaWired=$maaWired maaVersion=${MaaFw.MaaVersion()}",
        )
        return buildJsonObject {
            put("userDir", requested.absolutePath)
            put("frameDir", frameDir.absolutePath)
            put("nativeLoaded", NativeBridgeLib.LOADED)
            put("maaWired", maaWired)
            put("maaVersion", MaaFw.MaaVersion())
            put("maaFailure", MaaFw.lastFailure?.let { "${it.javaClass.name}: ${it.message}" })
            put("cached", false)
        }
    }

    /**
     * 加载 MaaFramework 与外部库 `bridge`，并把 native 的日志目录指向 userDir。
     *
     * `tmpDir` 固定给 `/data/local/tmp`：JNA 解包 `.so` 需要可写目录，而
     * `MaaFw.ensureLoaded` 的候选顺序是 (tmpDir) → `/data/local/tmp` → nativeLibraryDir，
     * 取第一个可写的；helper 是 shell/root，通常两个都能写。
     *
     * @return `null` 表示成功或**调用方没要求**；非 null 是失败原因（供 `requireMaa` 报错）。
     */
    private fun ensureMaaLoaded(workDir: File): Throwable? {
        val libDir = nativeLibraryDir
        // 没给 nativeLibraryDir 也试一次：MaaFw 会退回 /data/local/tmp。
        val result = MaaFw.ensureLoaded(libDir, tmpDir = File("/data/local/tmp"))
        if (!MaaFw.isLoaded) {
            val failure = MaaFw.lastFailure ?: result.exceptionOrNull()
            Ln.w(
                "RemoteEngine: MaaFramework not loaded (nativeLibraryDir=${libDir?.absolutePath}): " +
                    "${failure?.javaClass?.name}: ${failure?.message}",
            )
            return failure ?: IllegalStateException("MaaFw.ensureLoaded returned failure without a Throwable")
        }
        Ln.i("RemoteEngine: MaaFramework loaded version=${MaaFw.MaaVersion()} from ${libDir?.absolutePath}")
        // 日志目录：native 会在里面写 maa.log / 各任务的 save_draw。
        runCatching { MaaFw.setLogDir(workDir) }
            .onFailure { Ln.w("RemoteEngine: MaaFw.setLogDir failed: ${it.message}") }
        return null
    }

    private fun engineInfo(): JsonObject = buildJsonObject {
        put("pid", android.os.Process.myPid())
        put("uid", android.os.Process.myUid())
        put("sdk", android.os.Build.VERSION.SDK_INT)
        put("nativeLoaded", NativeBridgeLib.LOADED)
        put("maaWired", maaWired)
        put("maaVersion", MaaFw.MaaVersion())
        put("maaFailure", MaaFw.lastFailure?.let { "${it.javaClass.name}: ${it.message}" })
        put("setupOk", setupOk)
        put("setupDetail", setupDetail)
        put("nativeLibraryDir", nativeLibraryDir?.absolutePath)
        put("droppedEvents", server?.droppedEventCount ?: 0)
        put("display", displayState())
        put("maa", maaState())
        put("frames", runCatching { NativeBridgeLib.getFrameCount() }.getOrDefault(-1L))
        put("inputInjectionNeedsRoot", ProcessSpawner.keepRootForInputInjection)
    }

    // ------------------------------------------------------------------ display.*

    private fun displayStart(params: JsonObject?): JsonObject {
        val mode = when (params.str("mode")?.lowercase()) {
            null, "virtual" -> DisplaySession.Mode.VIRTUAL
            "mirror", "primary" -> DisplaySession.Mode.PRIMARY_MIRROR
            else -> throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "mode must be 'virtual' or 'mirror'",
                "got=${params.str("mode")}",
            )
        }
        displayMode = mode

        val width = params.int("width") ?: DefaultDisplayConfig.WIDTH
        val height = params.int("height") ?: DefaultDisplayConfig.HEIGHT
        val dpi = params.int("dpi") ?: DefaultDisplayConfig.DPI

        // 换模式必须重建：VirtualDisplay 是绑定在具体 display 上的，不能复用。
        var session = display
        if (session == null || displayMode != mode) {
            if (session != null) runCatching { session.stop() }
            session = DisplaySession(mode) { changedId -> onDisplayChanged(changedId) }
            display = session
            displayMode = mode
        }
        if (session.isCapturing) session.setResolution(width, height, dpi)

        val id = session.start()
        if (id == DefaultDisplayConfig.DISPLAY_NONE) {
            throw RemoteProtocolException(
                ErrorCode.INTERNAL,
                "display start failed (mode=$mode, ${width}x$height@$dpi); see launcher log + logcat tag ${Ln.TAG}",
            )
        }
        // 建屏后给 native 一点时间把第一帧拿出来，否则 app 立刻截图会拿到空帧。
        // MAA-Meow 把这一步放在 DriverClass.awaitFirstFrame() 里，这里只做一次轻量确认。
        val frameCount = runCatching { NativeBridgeLib.getFrameCount() }.getOrDefault(0L)
        return displayState() + buildJsonObject {
            put("firstFrameCount", frameCount)
        }
    }

    private fun displayStop(): JsonObject {
        stopPreviewInternal()
        display?.stop()
        return buildJsonObject { put("stopped", true) }
    }

    private fun displayRestart(params: JsonObject?): JsonObject {
        display?.stop()
        return displayStart(params)
    }

    private fun displayResize(params: JsonObject?): JsonObject {
        val session = display ?: throw RemoteProtocolException(ErrorCode.NOT_READY, "display has not been started")
        val width = params.int("width")
        val height = params.int("height")
        if (width == null || height == null) {
            throw RemoteProtocolException(ErrorCode.BAD_PARAMS, "width and height are required", "params=$params")
        }
        val dpi = params.int("dpi") ?: DefaultDisplayConfig.DPI
        val recreated = session.setResolution(width, height, dpi)
        return displayState() + buildJsonObject { put("recreated", recreated) }
    }

    private fun displayState(): JsonObject {
        val session = display
        return buildJsonObject {
            put("mode", if (displayMode == DisplaySession.Mode.VIRTUAL) "virtual" else "mirror")
            put("displayId", session?.displayId ?: DefaultDisplayConfig.DISPLAY_NONE)
            put("capturing", session?.isCapturing ?: false)
            put("preview", previewRunning.get())
            put("frames", runCatching { NativeBridgeLib.getFrameCount() }.getOrDefault(-1L))
        }
    }

    private fun onDisplayChanged(displayId: Int) {
        eventSink.emit(Event.DISPLAY_CHANGED, buildJsonObject { put("displayId", displayId) })
    }

    // ------------------------------------------------------------------ capture.*

    /**
     * 取一帧。`inline=true`（jpeg 默认）时把 base64 直接放进 `result.dataBase64`；
     * 给 `path` 时落盘并只回路径。
     */
    private fun captureFrame(params: JsonObject?): JsonObject {
        requireNative("capture.frame")
        val format = (params.str("format") ?: "jpeg").lowercase()
        if (format != "jpeg" && format != "png") {
            throw RemoteProtocolException(ErrorCode.BAD_PARAMS, "format must be 'jpeg' or 'png'", "got=$format")
        }
        val quality = (params.int("quality") ?: DEFAULT_JPEG_QUALITY).coerceIn(1, 100)
        val maxBytes = params.int("maxBytes") ?: DEFAULT_INLINE_MAX_BYTES
        val path = params.str("path")

        val bitmap = NativeBridgeLib.getFrameBufferBitmap()
            ?: throw RemoteProtocolException(ErrorCode.INTERNAL, "native capturer returned no bitmap (no frame yet?)")
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            throw RemoteProtocolException(ErrorCode.INTERNAL, "bitmap is empty: ${bitmap.width}x${bitmap.height}")
        }

        val bytes = encode(bitmap, format, quality)
        val width = bitmap.width
        val height = bitmap.height

        if (path != null) {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            Ln.i("RemoteEngine: frame written to $path (${bytes.size} bytes, ${width}x$height)")
            return buildJsonObject {
                put("path", file.absolutePath)
                put("width", width)
                put("height", height)
                put("bytes", bytes.size)
                put("format", format)
            }
        }

        if (bytes.size > maxBytes) {
            // 不要吞掉：告诉调用方到底多大、怎么缩。
            throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "encoded frame is ${bytes.size} bytes, over maxBytes=$maxBytes; " +
                    "use format=jpeg/lower quality, raise maxBytes, or pass an explicit path",
                "width=$width height=$height format=$format",
            )
        }
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return buildJsonObject {
            put("width", width)
            put("height", height)
            put("bytes", bytes.size)
            put("format", format)
            put("dataBase64", base64)
        }
    }

    /**
     * 预览开关。`{enable:true, intervalMs, inline, maxBytes}` / `{enable:false}`。
     *
     * 循环跑在自己的 daemon 线程上，靠 [eventSink] 的返回值做背压：
     * 服务端事件队列满（app 跟不上）时**直接丢帧**，绝不把特权进程堵住。
     */
    private fun capturePreview(params: JsonObject?): JsonObject {
        val enable = params.bool("enable") ?: throw RemoteProtocolException(
            ErrorCode.BAD_PARAMS,
            "enable (boolean) is required",
        )
        if (!enable) {
            stopPreviewInternal()
            return buildJsonObject { put("preview", false) }
        }
        requireNative("capture.preview")
        val interval = (params.int("intervalMs") ?: DEFAULT_PREVIEW_INTERVAL_MS)
            .coerceIn(MIN_PREVIEW_INTERVAL_MS, MAX_PREVIEW_INTERVAL_MS)
        val inline = params.bool("inline") ?: false
        val maxBytes = params.int("maxBytes") ?: DEFAULT_INLINE_MAX_BYTES

        stopPreviewInternal()
        previewIntervalMs = interval
        previewInline = inline
        previewMaxBytes = maxBytes
        previewStop = false
        previewRunning.set(true)
        previewThread = Thread({ previewLoop() }, "maapocket-preview").apply {
            isDaemon = true
            start()
        }
        Ln.i("RemoteEngine: preview started interval=${interval}ms inline=$inline maxBytes=$maxBytes")
        return buildJsonObject {
            put("preview", true)
            put("intervalMs", interval)
            put("inline", inline)
        }
    }

    private fun previewLoop() {
        val frameDir = File(requireUserDir(), FRAME_DIR_NAME)
        if (!frameDir.exists()) frameDir.mkdirs()
        while (!previewStop) {
            val started = SystemClock.elapsedRealtime()
            try {
                val bitmap = NativeBridgeLib.getFrameBufferBitmap()
                if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                    val bytes = encode(bitmap, "jpeg", DEFAULT_JPEG_QUALITY)
                    val meta = buildJsonObject {
                        put("width", bitmap.width)
                        put("height", bitmap.height)
                        put("bytes", bytes.size)
                        put("format", "jpeg")
                    }
                    val data = if (previewInline && bytes.size <= previewMaxBytes) {
                        meta + buildJsonObject { put("dataBase64", Base64.getEncoder().encodeToString(bytes)) }
                    } else {
                        // 默认只给落盘路径：JSON 行有 1 MiB 上限，持续内联大帧会打爆协议。
                        val file = File(frameDir, "preview-${System.currentTimeMillis()}.jpg")
                        runCatching { file.writeBytes(bytes) }
                        pruneFrameDir(frameDir)
                        meta + buildJsonObject { put("path", file.absolutePath) }
                    }
                    val accepted = eventSink.emit(Event.FRAME, data)
                    if (!accepted) previewDropped++
                }
            } catch (e: Throwable) {
                Ln.w("RemoteEngine: preview tick failed: ${e.message}")
                // `Ln.*` 在 logcat 里看不到（实测），而「预览为什么是黑的」只能靠现场证据回答。
                // 落一个小文件，adb 一 pull 就知道是取帧抛异常还是取到的是黑图。
                runCatching {
                    File(frameDir, PREVIEW_ERROR_FILE).writeText(
                        "${e.javaClass.name}: ${e.message}\n",
                    )
                }
            }
            val cost = SystemClock.elapsedRealtime() - started
            val sleep = previewIntervalMs - cost
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private var previewDropped = 0L

    /**
     * 只保留最近 [PREVIEW_KEEP_FILES] 张落盘帧。
     *
     * 落盘帧是排查「识别为什么对不上」的唯一证据（虚拟屏没有可用的 `screencap -d`，
     * 见 `_research` 里的结论），所以要一直在，但绝不能无限长：5fps 跑一小时就是 1.8 万张。
     * 每 [PREVIEW_PRUNE_EVERY] 帧扫一次目录，按 `lastModified()` 删最旧的。
     */
    private fun pruneFrameDir(dir: File) {
        if (previewPruneTick++ % PREVIEW_PRUNE_EVERY != 0) return
        val frames = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("preview-") && it.name.endsWith(".jpg") }
            ?: return
        if (frames.size <= PREVIEW_KEEP_FILES) return
        frames.sortedBy { it.lastModified() }
            .take(frames.size - PREVIEW_KEEP_FILES)
            .forEach { runCatching { it.delete() } }
    }

    private var previewPruneTick = 0

    private fun stopPreviewInternal() {
        previewStop = true
        previewThread?.let {
            it.interrupt()
            runCatching { it.join(PREVIEW_JOIN_TIMEOUT_MS) }
                .onFailure { e -> Ln.w("RemoteEngine: preview thread did not stop: ${e.message}") }
        }
        previewThread = null
        if (previewRunning.getAndSet(false)) {
            Ln.i("RemoteEngine: preview stopped (dropped=$previewDropped)")
        }
    }

    // ------------------------------------------------------------------ input.*

    /**
     * 触控注入。`{action: down|move|up|cancel, x, y, contact?, displayId?}`。
     *
     * 走 `DriverClass`（`InputControlUtils` 是包私有，跨包不可见），
     * 它会顺带处理「等第一帧」——没有帧就注入会点到黑屏上。
     */
    private fun inputTouch(params: JsonObject?): JsonObject {
        requireBridgeAndDriver()
        val action = params.str("action")?.lowercase()
            ?: throw RemoteProtocolException(ErrorCode.BAD_PARAMS, "action is required")
        val displayId = params.int("displayId") ?: currentDisplayId()
        val contact = params.int("contact") ?: 0

        if (action == "cancel") {
            // DriverClass 没有 cancel；InputControlUtils.cancel(int) 是包私有的，跨包调不到。
            // 与其瞎猜一个等价实现，不如如实报不支持。
            throw RemoteProtocolException(
                ErrorCode.UNSUPPORTED,
                "touch cancel is not exposed by the input driver yet",
                "DriverClass has touchDown/touchMove/touchUp/keyDown/keyUp only; " +
                    "InputControlUtils.cancel(int) is package-private",
            )
        }

        val x = params.int("x") ?: throw RemoteProtocolException(ErrorCode.BAD_PARAMS, "x is required", "action=$action")
        val y = params.int("y") ?: throw RemoteProtocolException(ErrorCode.BAD_PARAMS, "y is required", "action=$action")
        val ok = when (action) {
            "down" -> DriverClass.touchDown(x, y, contact, displayId)
            "move" -> DriverClass.touchMove(x, y, contact, displayId)
            "up" -> DriverClass.touchUp(x, y, contact, displayId)
            else -> throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "unknown touch action '$action'",
                "expected one of down/move/up/cancel",
            )
        }
        return buildJsonObject {
            put("injected", ok)
            put("displayId", displayId)
        }
    }

    /** 按键注入。`{action: down|up, keyCode, displayId?}`。 */
    private fun inputKey(params: JsonObject?): JsonObject {
        requireBridgeAndDriver()
        val action = params.str("action")?.lowercase()
            ?: throw RemoteProtocolException(ErrorCode.BAD_PARAMS, "action is required")
        val keyCode = params.int("keyCode")
            ?: throw RemoteProtocolException(ErrorCode.BAD_PARAMS, "keyCode is required")
        val displayId = params.int("displayId") ?: currentDisplayId()
        val ok = when (action) {
            "down" -> DriverClass.keyDown(keyCode, displayId)
            "up" -> DriverClass.keyUp(keyCode, displayId)
            else -> throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "unknown key action '$action'",
                "expected down/up",
            )
        }
        return buildJsonObject {
            put("injected", ok)
            put("displayId", displayId)
        }
    }

    /** 拉起游戏并等首帧。`{packageName, displayId?, forceStop?}`。 */
    private fun appStart(params: JsonObject?): JsonObject {
        requireBridgeAndDriver()
        val pkg = params.str("packageName")
            ?: throw RemoteProtocolException(ErrorCode.BAD_PARAMS, "packageName is required")
        val displayId = params.int("displayId") ?: currentDisplayId()
        val forceStop = params.bool("forceStop") ?: false
        val started = DriverClass.startApp(pkg, displayId, forceStop)
        return buildJsonObject {
            put("started", started)
            put("packageName", pkg)
            put("displayId", displayId)
        }
    }

    // ------------------------------------------------------------------ maa.controller.start

    /**
     * 建 AndroidNative 控制器并连上已经存在的虚拟屏。
     *
     * 参数 `{displayId?, width?, height?, forceStop?}`；缺省从 [display] 的 displayId 与
     * [DefaultDisplayConfig] 的 WIDTH/HEIGHT 取。
     *
     * **`library_path` 必须是磁盘绝对路径** `<nativeLibraryDir>/libbridge.so`：
     * helper 是独立进程，MaaFramework 的 dlopen 需要真实文件路径，不能用 `System.loadLibrary`
     * 的名字。
     */
    private fun maaControllerStart(params: JsonObject?): JsonObject {
        val api = requireMaa()
        val width = params.int("width") ?: DefaultDisplayConfig.WIDTH
        val height = params.int("height") ?: DefaultDisplayConfig.HEIGHT
        val displayId = params.int("displayId") ?: currentDisplayId()
        val forceStop = params.bool("forceStop") ?: false

        val bridgeLib = File(requireNativeLibraryDir(), BRIDGE_LIB_NAME)
        if (!bridgeLib.exists()) {
            throw RemoteProtocolException(
                ErrorCode.NOT_READY,
                "external bridge library not found: ${bridgeLib.absolutePath}",
                "nativeLibraryDir=${nativeLibraryDir?.absolutePath}",
            )
        }

        val config = MaaFw.androidNativeControllerConfig(
            bridgeLibraryPath = bridgeLib.absolutePath,
            width = width,
            height = height,
            displayId = displayId,
            forceStop = forceStop,
        )
        Ln.i("RemoteEngine: maa.controller.start config=$config")

        // 同一时刻只允许一个 controller：旧的先关，否则 native 侧会留着上一个显示的连接。
        releaseController()

        val created = try {
            MaaController.createAndroidNative(api, config)
        } catch (e: Throwable) {
            throw RemoteProtocolException(
                ErrorCode.INTERNAL,
                "MaaAndroidNativeControllerCreate failed: ${e.message}",
                "config=$config",
            )
        }
        controller = created

        val connectId = created.postConnection()
        if (!created.awaitSucceeded(connectId, CONTROLLER_CONNECT_TIMEOUT_MS)) {
            releaseController()
            throw RemoteProtocolException(
                ErrorCode.INTERNAL,
                "controller did not connect within ${CONTROLLER_CONNECT_TIMEOUT_MS}ms",
                "displayId=$displayId ${width}x$height; check that display.start ran first and that " +
                    "screen_resolution matches the frame size bridge produces",
            )
        }

        return buildJsonObject {
            put("displayId", displayId)
            put("width", width)
            put("height", height)
            put("uuid", runCatching { created.uuid() }.getOrNull())
        }
    }

    // ------------------------------------------------------------------ resource.load

    /**
     * 按顺序叠加加载资源目录（后面的优先级高）。参数 `{paths: [String]}`。
     *
     * 全部加载成功返回 `failedPath=null`；部分失败也**不抛异常**，把第一个失败的目录
     * 原样回给 app 让它决定（MaaFramework 允许多 bundle 叠加，失败的那个只是不生效）。
     */
    private fun maaResourceLoad(params: JsonObject?): JsonObject {
        val api = requireMaa()
        val paths = params.stringList("paths")
        if (paths.isEmpty()) {
            throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "paths (non-empty array of strings) is required",
                "params=$params",
            )
        }
        val roots = paths.map { File(it) }
        roots.firstOrNull { !it.isDirectory }?.let {
            // 目录不存在时 MaaResourcePostBundle 只会异步失败，早报早好。
            throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "resource path is not a directory: ${it.absolutePath}",
                "paths=$paths",
            )
        }

        releaseResource()

        val created = try {
            MaaResource.create(api)
        } catch (e: Throwable) {
            throw RemoteProtocolException(ErrorCode.INTERNAL, "MaaResourceCreate failed: ${e.message}")
        }
        resource = created

        val failed = try {
            created.loadAll(roots, timeoutMs = RESOURCE_LOAD_TIMEOUT_MS)
        } catch (e: Throwable) {
            releaseResource()
            throw RemoteProtocolException(
                ErrorCode.INTERNAL,
                "resource load crashed: ${e.message}",
                "paths=$paths",
            )
        }

        val nodes = runCatching { created.nodeList().size }.getOrDefault(0)
        if (failed != null) {
            Ln.w("RemoteEngine: resource load failed at ${failed.absolutePath} (loaded=${created.loaded})")
        } else {
            Ln.i("RemoteEngine: resource loaded from $paths nodes=$nodes")
        }
        return buildJsonObject {
            put("loaded", created.loaded)
            put("failedPath", failed?.absolutePath)
            put("nodes", nodes)
        }
    }

    // ------------------------------------------------------------------ task.run / task.stop / maa.state

    /**
     * 跑一条 task。参数 `{entry: String, pipelineOverride?: JsonObject, timeoutMs?: Long}`。
     *
     * 空闲态才允许（否则 [ErrorCode.BUSY]）——两条 task 抢同一个 controller 会互相踩输入。
     * 进度通过 [Event.JOB_PROGRESS] 主动推给 app（`{taskId, message, details}`）。
     */
    private fun maaTaskRun(params: JsonObject?): JsonObject {
        val api = requireMaa()
        val entry = params.str("entry")
            ?: throw RemoteProtocolException(ErrorCode.BAD_PARAMS, "entry is required")
        val pipelineOverride = params?.get("pipelineOverride")?.let {
            if (it is JsonObject) it.toString() else null
        } ?: "{}"
        // 绝不能把 null 传下去：JNA 会把 Kotlin 的 null String 变成 NULL char*，
        // MaaTaskerPostTask 内部直接 strlen 它 → 特权进程 SIGSEGV（真机 tombstone 见 MaaTasker.postTask）。
        val timeoutMs = params.long("timeoutMs") ?: DEFAULT_TASK_TIMEOUT_MS

        val currentResource = resource
            ?: throw RemoteProtocolException(ErrorCode.NOT_READY, "no resource loaded; send resource.load first")
        val currentController = controller
            ?: throw RemoteProtocolException(ErrorCode.NOT_READY, "no controller; send maa.controller.start first")
        if (!currentController.connected) {
            throw RemoteProtocolException(
                ErrorCode.NOT_READY,
                "controller is not connected any more; re-run maa.controller.start",
            )
        }
        if (!taskRunning.compareAndSet(false, true)) {
            throw RemoteProtocolException(
                ErrorCode.BUSY,
                "a task is already running (taskId=${taskId.get()}); call task.stop first",
            )
        }

        try {
            // 每次 run 都新建 tasker：MAA-Meow 的语义是「一个 tasker = 一次游戏会话」，
            // 复用会让 internal state 串味（上一个任务的 cache/context 还在）。
            releaseTasker()
            val created = try {
                MaaTasker.create(api)
            } catch (e: Throwable) {
                throw RemoteProtocolException(ErrorCode.INTERNAL, "MaaTaskerCreate failed: ${e.message}")
            }
            if (!created.bindResource(currentResource)) {
                throw RemoteProtocolException(ErrorCode.INTERNAL, "MaaTaskerBindResource failed")
            }
            if (!created.bindController(currentController)) {
                throw RemoteProtocolException(ErrorCode.INTERNAL, "MaaTaskerBindController failed")
            }
            taskId.set(-1L)
            val sink = MaaSinks.create { notification ->
                // 服务端事件队列满时 tryEmit 返回 false —— 进度丢一帧无所谓，绝不能阻塞 Maa 回调线程。
                eventSink.emit(
                    Event.JOB_PROGRESS,
                    buildJsonObject {
                        put("taskId", taskId.get())
                        put("message", notification.message)
                        put("details", notification.details)
                    },
                )
            }
            taskSink = sink
            taskSinkId = created.addSink(sink)
            synchronized(maaLock) { tasker = created }

            val status = created.runTask(
                entry = entry,
                pipelineOverrideJson = pipelineOverride,
                timeoutMs = timeoutMs,
                onStart = { id ->
                    taskId.set(id)
                    Ln.i("RemoteEngine: task started entry=$entry id=$id timeout=${timeoutMs}ms")
                },
            )
            val succeeded = status == MaaDef.MaaStatus_Succeeded
            if (status == MaaDef.MaaStatus_Invalid) {
                Ln.w("RemoteEngine: task $entry timed out after ${timeoutMs}ms")
            } else {
                Ln.i("RemoteEngine: task $entry finished status=$status")
            }
            return buildJsonObject {
                put("status", status)
                put("succeeded", succeeded)
                put("taskId", taskId.get())
            }
        } finally {
            taskRunning.set(false)
        }
    }

    /** 请求停止正在跑的 task。`postStop` 只投递请求，不等待（要看是否停干净就轮询 [Cmd.MAA_STATE]）。 */
    private fun maaTaskStop(): JsonObject {
        val current = tasker
        if (current == null) {
            return buildJsonObject {
                put("stopped", false)
                put("reason", "no tasker")
            }
        }
        val id = runCatching { current.postStop() }.getOrElse {
            throw RemoteProtocolException(ErrorCode.INTERNAL, "MaaTaskerPostStop failed: ${it.message}")
        }
        Ln.i("RemoteEngine: task stop requested id=$id running=${taskRunning.get()}")
        return buildJsonObject {
            put("stopped", true)
            put("taskId", taskId.get())
        }
    }

    /** MaaFramework 汇总状态。app 用它做「要不要重新 setup/连控制器」的判断。 */
    private fun maaState(): JsonObject {
        val currentController = controller
        val currentResource = resource
        return buildJsonObject {
            put("maaWired", maaWired)
            put("version", MaaFw.MaaVersion())
            put("controllerReady", currentController?.connected ?: false)
            put("resourceLoaded", currentResource?.loaded ?: false)
            put("taskRunning", taskRunning.get())
            put("taskId", taskId.get().takeIf { it >= 0 })
            put("displayId", display?.displayId ?: DefaultDisplayConfig.DISPLAY_NONE)
            put("nodes", runCatching { currentResource?.nodeList()?.size }.getOrNull())
            put("agentCount", agentCount())
            put("agents", agentsState())
        }
    }

    // ------------------------------------------------------------------ agent.start / agent.stop

    /**
     * 起一个 agent 子进程（PI 的 `child_exec`，如 `agent/go-service`）并完成握手。
     *
     * 参数：
     * ```
     * {
     *   executable: String,       // 必填。绝对路径，且能从本进程 execve（nativeLibraryDir 里的 lib*.so）
     *   args: [String] = [],      // 声明里的 child_args；identifier 由本方法追加到末尾，不要自己带
     *   workingDir: String?,      // CWD，AgentWorkspace 铺好的目录（其中 maafw 必须齐 4 个 .so）
     *   nativeLibDir: String?,    // 填 LD_LIBRARY_PATH / MAAFW_BINARY_PATH（防御性，Go agent 只认 CWD/maafw）
     *   label: String?,           // 日志前缀兼幂等键；缺省用可执行文件名
     *   identifier: String?,      // null（缺省）= 让 MaaFramework 自己生成 socket 标识
     *   timeoutMs: Long?,         // 握手上限，缺省 AgentLauncher.DEFAULT_CONNECT_TIMEOUT_MS（20s）
     *   env: {PI_*: String}       // 见 PiAgentEnv；未知的键被忽略，并在返回值 ignoredEnv 里如实列出
     * }
     * ```
     *
     * 返回 `{label, identifier, pid?, ready, alive, connected, customActions, customRecognitions,
     * envKeys, ignoredEnv, reused}`。
     *
     * **幂等**：同一个 [label] 上已经有一个活着的 agent 时不再起第二个，直接把它的状态回给调用方
     * （`reused=true`）。app 的 `prepare()` 可以被反复调用，少了这条约束就会按一次多一个子进程。
     *
     * 资源必须是**本进程里已经 `resource.load` 出来的那一个**：`MaaAgentClient.bindResource`
     * 绑的必须是真正在跑 pipeline 的资源，自己另建一个等于把 agent 的自定义动作注册到一个
     * 没人在用的资源上（pipeline 依旧卡在 Custom 节点）。所以这里只读 [resource]，不新建。
     *
     * 线程：`launch()` 阻塞到握手成功或超时，本方法在 `RemoteServer` 的 worker 线程上跑，
     * 调用方（app）要给它一个大于 [timeoutMs] 的请求超时。
     */
    private fun agentStart(params: JsonObject?): JsonObject {
        val api = requireMaa()

        val executablePath = params.str("executable")
            ?: throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "executable is required",
                "params=$params",
            )
        val executable = File(executablePath)
        if (!executable.isFile) {
            throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "agent executable does not exist: ${executable.absolutePath}",
                "AgentWorkspace 只铺工作目录，可执行文件本身必须留在 nativeLibraryDir（见 docs/agents.md §3）",
            )
        }
        val label = params.str("label")?.takeIf { it.isNotBlank() } ?: executable.name

        // 已经有活着的同名 agent：直接复用，不要再 execve 一个。
        val existing = synchronized(maaLock) { agents[label] }
        if (existing != null && existing.alive) {
            val pid = pidOf(existing.process)
            Ln.i("RemoteEngine: agent '$label' already running (pid=$pid), reusing")
            return describeAgent(label, existing) + buildJsonObject { put("reused", true) }
        }

        val currentResource = resource
            ?: throw RemoteProtocolException(
                ErrorCode.NOT_READY,
                "no resource loaded; send resource.load first",
                "agent.start 必须绑到正在跑 pipeline 的那个 MaaResource 上",
            )
        if (!currentResource.loaded) {
            throw RemoteProtocolException(
                ErrorCode.NOT_READY,
                "resource is not loaded (MaaResourceLoaded() == false)",
                "在没加载成功的资源上绑 agent 只会让它的自定义动作静默失效；先重跑 resource.load 并检查 failedPath",
            )
        }

        val workingDir = params.str("workingDir")?.let { File(it) }
        if (workingDir != null && !workingDir.isDirectory) {
            throw RemoteProtocolException(
                ErrorCode.BAD_PARAMS,
                "workingDir is not a directory: ${workingDir.absolutePath}",
                "AgentWorkspace.prepare() 失败了？Go agent 的 logger 写不了 <CWD>/debug 会直接 log.Fatal",
            )
        }
        val nativeLibDir = params.str("nativeLibDir")?.let { File(it) }
        val timeoutMs = (params.long("timeoutMs") ?: AgentLauncher.DEFAULT_CONNECT_TIMEOUT_MS)
            .coerceIn(MIN_AGENT_CONNECT_TIMEOUT_MS, MAX_AGENT_CONNECT_TIMEOUT_MS)
        val identifier = params.str("identifier")?.takeIf { it.isNotBlank() }

        val env = params.envMap()
        val piEnv = PiAgentEnv(
            interfaceVersion = env["PI_INTERFACE_VERSION"],
            clientName = env["PI_CLIENT_NAME"],
            clientVersion = env["PI_CLIENT_VERSION"],
            clientLanguage = env["PI_CLIENT_LANGUAGE"],
            clientMaafwVersion = env["PI_CLIENT_MAAFW_VERSION"],
            version = env["PI_VERSION"],
            controllerJson = env["PI_CONTROLLER"],
            resourceJson = env["PI_RESOURCE"],
        )
        // PiAgentEnv.toMap() 会丢掉空值，所以这里既能报"实际写进子进程的键"，也能报"没认识的键"。
        val applied = piEnv.toMap().keys
        val ignored = env.keys - applied
        if (ignored.isNotEmpty()) {
            Ln.w("RemoteEngine: agent '$label' env 里有本版本不认的键，已忽略：${ignored.sorted()}")
        }

        val runtime = MaaAgentRuntime(
            executable = executable,
            args = params.stringList("args"),
            nativeLibDir = nativeLibDir,
            workingDir = workingDir,
        )
        val launcher = AgentLauncher(
            api = api,
            resource = currentResource,
            log = { line -> Ln.i("RemoteEngine: $line") },
            connectTimeoutMs = timeoutMs,
        )

        val handle = try {
            launcher.launch(label, runtime, piEnv, identifier)
        } catch (e: AgentSpawnException) {
            // execve 都没成功：路径 / x 位 / CWD 的问题，如实把原因转给 app（它要显示给用户）。
            throw RemoteProtocolException(
                ErrorCode.INTERNAL,
                "agent spawn failed: ${e.message}",
                "label=$label exe=${executable.absolutePath} cwd=${workingDir?.absolutePath ?: "继承父进程"}",
            )
        } catch (e: AgentHandshakeException) {
            // 进程起来了但没握上手：九成是 <CWD>/maafw 的 4 个 .so 不全或 CWD 不可写。
            throw RemoteProtocolException(
                ErrorCode.NOT_READY,
                "agent handshake failed: ${e.message}",
                "label=$label cwd=${workingDir?.absolutePath ?: "继承父进程"}",
            )
        }

        // 同一个 label 上可能还挂着一个已经死掉的旧句柄，先摘掉并关掉它，别让 agents 只增不减。
        val replaced = synchronized(maaLock) { agents.put(label, handle) }
        if (replaced != null && replaced !== handle) {
            runCatching { replaced.close() }.onFailure { Ln.w("RemoteEngine: stale agent '$label' close failed: ${it.message}") }
        }

        Ln.i(
            "RemoteEngine: agent '$label' ready pid=${pidOf(handle.process)} " +
                "identifier=${runCatching { handle.client.identifier() }.getOrDefault("")} env=${applied.sorted()}",
        )
        return describeAgent(label, handle) + buildJsonObject {
            put("reused", false)
            put("envKeys", stringArray(applied.sorted()))
            put("ignoredEnv", stringArray(ignored.sorted()))
        }
    }

    /**
     * 停 agent 子进程。`{label: String}` 停一个；`{all: true}` 或省略 label 停全部。
     *
     * 返回 `{stopped: Int, labels: [String], remaining: Int}`。
     */
    private fun agentStop(params: JsonObject?): JsonObject {
        val label = params.str("label")?.takeIf { it.isNotBlank() }
        val all = params.bool("all") ?: false
        val stopped = if (label != null && !all) stopAgents(listOf(label)) else stopAgents(null)
        return buildJsonObject {
            put("stopped", stopped.size)
            put("labels", stringArray(stopped))
            put("remaining", agentCount())
        }
    }

    /**
     * 摘掉并关闭 agent。`labels == null` = 全部。
     *
     * 快照在 [maaLock] 里取、关闭在锁外做：`close()` 最多会等 2s 宽限再强杀，
     * 不能在锁里做（否则 `maa.state` / `task.stop` 会被一起堵住）。
     */
    private fun stopAgents(labels: List<String>?): List<String> {
        val snapshot: List<Pair<String, AgentHandle>> = synchronized(maaLock) {
            val picked = if (labels == null) {
                agents.map { it.key to it.value }
            } else {
                labels.mapNotNull { name -> agents[name]?.let { name to it } }
            }
            picked.forEach { (name, _) -> agents.remove(name) }
            picked
        }
        snapshot.forEach { (name, handle) ->
            val pid = pidOf(handle.process)
            runCatching { handle.close() }
                .onFailure { Ln.w("RemoteEngine: agent '$name' close failed: ${it.message}") }
            Ln.i("RemoteEngine: agent '$name' stopped (pid=$pid)")
        }
        return snapshot.map { it.first }
    }

    private fun releaseAgents(): List<String> = stopAgents(null)

    /**
     * `java.lang.Process` 在 Android 上**没有** `pid()` —— 那是 JDK 9+ 才加的 API，
     * libcore 从未跟进。写 `process.pid()` 会直接编译失败：
     * `Unresolved reference 'pid' on receiver of type 'Process'`（CI run 37126269339）。
     *
     * 这里改用反射读具体实现类（libcore 的 `java.lang.ProcessImpl`）里的 `pid` 字段；
     * 反射失败就返回 null。`pid` 只用于日志与 `maa.state` 展示，没有任何控制流依赖它，
     * 所以「拿不到」是可接受的降级，不是错误。
     *
     * 缓存按 `Class` 存：字段查找失败也缓存（`MISSING` 哨兵），避免每条 agent 都重试反射。
     */
    private fun pidOf(process: Process): Long? {
        val cls = process.javaClass
        val cached = pidFieldCache[cls]
        val field: Field? = when {
            cached === MISSING -> null
            cached is Field -> cached
            else -> {
                var f: Field? = null
                try {
                    f = cls.getDeclaredField("pid")
                } catch (_: Throwable) {
                    // 不是 libcore 的实现类（或字段改了名）——再试一次标准实现类。
                }
                if (f == null) {
                    try {
                        f = Class.forName("java.lang.ProcessImpl").getDeclaredField("pid")
                    } catch (_: Throwable) {
                        // 连 ProcessImpl 都没有：放弃，返回值一律为 null。
                    }
                }
                if (f != null) {
                    try {
                        f.isAccessible = true
                    } catch (_: Throwable) {
                        // 某些 ROM 上 setAccessible 会被拒；下面 field.get 仍可能抛，也会被吞。
                    }
                }
                pidFieldCache[cls] = f ?: MISSING
                f
            }
        }
        if (field == null) return null
        return try {
            (field.get(process) as? Number)?.toLong()
        } catch (_: Throwable) {
            null
        }
    }

    private fun agentCount(): Int = synchronized(maaLock) { agents.size }

    /** 给 `maa.state` / `agent.start` 用的单条 agent 快照。可以在锁外调用（内部只读句柄）。 */
    private fun describeAgent(label: String, handle: AgentHandle): JsonObject = buildJsonObject {
        put("label", label)
        put("identifier", runCatching { handle.client.identifier() }.getOrDefault(""))
        put("pid", pidOf(handle.process))
        put("ready", handle.alive)
        put("alive", handle.alive)
        put("connected", runCatching { handle.client.connected }.getOrDefault(false))
        put("customActions", stringArray(runCatching { handle.client.customActionList() }.getOrDefault(emptyList())))
        put(
            "customRecognitions",
            stringArray(runCatching { handle.client.customRecognitionList() }.getOrDefault(emptyList())),
        )
    }

    /**
     * 全部 agent 的快照（先取快照再逐个读句柄，避免拿着 [maaLock] 调 native 查询）。
     */
    private fun agentsState(): JsonArray {
        val snapshot = synchronized(maaLock) { agents.map { it.key to it.value } }
        return JsonArray(snapshot.map { (label, handle) -> describeAgent(label, handle) })
    }

    /** `JsonArray` 构造，省得每次写 `buildJsonArray { … }`。 */
    private fun stringArray(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    /**
     * `agent.start` 的 `env` 对象。只接受字符串值：元素不是字符串就丢掉——
     * 宁可少一个环境变量，也不要让一条 `agent.start` 因为一个坏值整条失败。
     */
    private fun JsonObject?.envMap(): Map<String, String> {
        val element = this?.get("env") as? JsonObject ?: return emptyMap()
        return element.mapNotNull { (key, value) ->
            runCatching { key to value.jsonPrimitive.content }.getOrNull()
        }.toMap()
    }

    // ------------------------------------------------------------------ 收尾

    /**
     * 进程退出前的清理。由 `RemoteMain` 的 shutdown hook / 异常处理器调用。
     *
     * 顺序：预览 → tasker（先 stop 再关）→ agent → resource → controller → display → native preview。
     * 先停 tasker 是有讲究的：它还可能在回调里用 controller 取帧，反过来先关 controller
     * 会让 native 侧在正在跑的推理里拿到空指针。
     *
     * agent 排在 tasker **之后**、resource **之前**：
     * - 之后的理由与 tasker 同源——正在跑的 pipeline 可能正把 Custom 节点派给 agent 子进程，
     *   先把 tasker 停干净（`stopAndWait`）再拆 agent，才不会让 native 侧拿着半截调用；
     * - 之前的理由是硬约束——`MaaAgentClient` 绑的就是 [resource]，资源先 `close()`
     *   会让 agent 的 client 挂在一个已销毁的句柄上（socket + 子进程双双泄漏）。
     */
    fun shutdown() {
        stopPreviewInternal()
        runCatching { releaseTasker() }.onFailure { Ln.w("RemoteEngine: tasker shutdown failed: ${it.message}") }
        runCatching { releaseAgents() }.onFailure { Ln.w("RemoteEngine: agent shutdown failed: ${it.message}") }
        runCatching { releaseResource() }.onFailure { Ln.w("RemoteEngine: resource shutdown failed: ${it.message}") }
        runCatching { releaseController() }.onFailure { Ln.w("RemoteEngine: controller shutdown failed: ${it.message}") }
        runCatching { display?.stop() }.onFailure { Ln.w("RemoteEngine: display stop failed: ${it.message}") }
        display = null
        // MAA-Meow `RemoteServiceImpl.shutdownPreview` 的教训：SurfaceView 侧的 buffer queue
        // 不会自己发现生产者死了，Surface 不关就会一直记在这个进程头上，
        // 下一个特权进程 eglCreateWindowSurface 会直接报 "already connected"。
        if (NativeBridgeLib.LOADED) {
            val done = AtomicBoolean(false)
            val t = Thread({
                runCatching { NativeBridgeLib.shutdownPreview() }
                done.set(true)
            }, "maapocket-shutdown-preview").apply { isDaemon = true }
            t.start()
            runCatching { t.join(SHUTDOWN_PREVIEW_JOIN_MS) }
            if (!done.get()) Ln.w("RemoteEngine: shutdownPreview still running after ${SHUTDOWN_PREVIEW_JOIN_MS}ms")
        }
    }

    /**
     * 优雅停 tasker 再销毁。`stopAndWait` 有超时，超时就硬关：
     * 退出路径上不能无限等一个可能已经卡住的 native 任务。
     */
    private fun releaseTasker() {
        synchronized(maaLock) {
            val current = tasker ?: return
            tasker = null
            runCatching { current.stopAndWait(TASKER_STOP_TIMEOUT_MS) }
                .onFailure { Ln.w("RemoteEngine: stopAndWait failed: ${it.message}") }
            val sink = taskSink
            val sinkId = taskSinkId
            if (sink != null && sinkId > 0) {
                runCatching { current.removeSink(sinkId, sink) }
                    .onFailure { Ln.w("RemoteEngine: removeSink failed: ${it.message}") }
            }
            taskSink = null
            taskSinkId = 0L
            taskId.set(-1L)
            runCatching { current.close() }.onFailure { Ln.w("RemoteEngine: tasker close failed: ${it.message}") }
        }
    }

    private fun releaseResource() {
        // agent 绑在这个 resource 上，必须先走：`resource.load` 会先 releaseResource() 再建新资源，
        // 漏了这一步，重载资源就会把旧 agent 留在一个已经 close 掉的资源上。
        // 注意 stopAgents 自己取快照后在锁外关闭，所以这里不会嵌套拿 maaLock。
        runCatching { releaseAgents() }.onFailure { Ln.w("RemoteEngine: agent release failed: ${it.message}") }
        synchronized(maaLock) {
            val current = resource ?: return
            resource = null
            runCatching { current.close() }.onFailure { Ln.w("RemoteEngine: resource close failed: ${it.message}") }
        }
    }

    private fun releaseController() {
        synchronized(maaLock) {
            val current = controller ?: return
            controller = null
            runCatching { current.close() }.onFailure { Ln.w("RemoteEngine: controller close failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 取 MaaFramework API；没加载好就抛 [ErrorCode.MAAFRAMEWORK_NOT_WIRED]，
     * 并把类名+message 一起带上（`MaaFw.lastFailure`）。
     */
    private fun requireMaa(): MaaFrameworkApi {
        if (!MaaFw.isLoaded) {
            throw RemoteProtocolException(
                ErrorCode.MAAFRAMEWORK_NOT_WIRED,
                "MaaFramework is not loaded in this process",
                MaaFw.lastFailure?.let { "${it.javaClass.name}: ${it.message}" }
                    ?: "MaaFw.ensureLoaded() was never successful; check engine.setup(nativeLibraryDir=...)",
            )
        }
        return MaaFw.api
    }

    private fun currentDisplayId(): Int =
        display?.displayId?.takeIf { it != DefaultDisplayConfig.DISPLAY_NONE } ?: 0

    private fun requireNative(what: String) {
        if (!NativeBridgeLib.LOADED) {
            throw RemoteProtocolException(
                ErrorCode.UNSUPPORTED,
                "$what requires the native bridge, but libbridge.so failed to load",
                "see logcat tag ${Ln.TAG}",
            )
        }
    }

    /**
     * 输入路径必须先有 native，再有 `DriverClass`：`JNI_OnLoad` 会 `FindClass`
     * `com/maapocket/core/maa/DriverClass`，顺序反了或类不存在都会让注入静默失败。
     */
    private fun requireBridgeAndDriver() {
        requireNative("input injection")
        if (!driverReady) {
            driverReady = runCatching {
                Class.forName(DRIVER_CLASS_NAME, true, javaClass.classLoader).let { true }
            }.getOrElse {
                Ln.e("RemoteEngine: cannot load $DRIVER_CLASS_NAME", it)
                false
            }
        }
        if (!driverReady) {
            throw RemoteProtocolException(
                ErrorCode.UNSUPPORTED,
                "input driver class $DRIVER_CLASS_NAME is not available in this process",
                "check that the module provides com.maapocket.core.maa.DriverClass",
            )
        }
    }

    private var driverReady = false

    private fun encode(bitmap: Bitmap, format: String, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        val compressFormat = if (format == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        // PNG 忽略 quality 参数；JPEG 用它。
        if (!bitmap.compress(compressFormat, quality, out)) {
            throw RemoteProtocolException(ErrorCode.INTERNAL, "Bitmap.compress failed (format=$format)")
        }
        return out.toByteArray()
    }

    /** `params` 取值一律走字符串再解析，避免依赖 `JsonPrimitive.intOrNull` 之类的扩展。 */
    private fun JsonObject?.str(key: String): String? =
        this?.get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject?.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun JsonObject?.long(key: String): Long? = str(key)?.toLongOrNull()

    private fun JsonObject?.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()

    /** 只接受字符串数组；元素不是字符串就跳过（宁可少加载一个目录也不炸掉整条命令）。 */
    private fun JsonObject?.stringList(key: String): List<String> {
        val element = this?.get(key) ?: return emptyList()
        val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }

    /** `JsonObject` 不可变，`+` 返回合并后的新对象。 */
    private operator fun JsonObject.plus(other: JsonObject): JsonObject =
        JsonObject(this.toMutableMap().apply { putAll(other) })

    companion object {
        /**
         * [pidFieldCache] 的「该 Class 上没有可用的 pid 字段」哨兵。用 `Any()` 而不是 null，
         * 因为 `ConcurrentHashMap` 不允许 null 值，而「查过但没有」与「还没查过」必须区分开。
         */
        private val MISSING = Any()

        private const val FRAME_DIR_NAME = "frames"

        /** 取帧抛异常时把原因写在这里；`Ln.*` 在 logcat 里看不到，只能靠文件传话。 */
        private const val PREVIEW_ERROR_FILE = "preview-error.txt"

        /** 外部库名：MaaFramework 靠它拿帧/注输入（与 `bridge` 同进程，见类注释）。 */
        private const val BRIDGE_LIB_NAME = "libbridge.so"

        private const val DRIVER_CLASS_NAME = "com.maapocket.core.maa.DriverClass"

        private const val DEFAULT_JPEG_QUALITY = 80

        /** 内联 base64 的默认上限：200 KiB 原始 → base64 约 267 KiB，离 1 MiB 行上限很远。 */
        private const val DEFAULT_INLINE_MAX_BYTES = 200 * 1024

        private const val DEFAULT_PREVIEW_INTERVAL_MS = 200

        private const val MIN_PREVIEW_INTERVAL_MS = 50

        private const val MAX_PREVIEW_INTERVAL_MS = 5_000

        private const val PREVIEW_JOIN_TIMEOUT_MS = 1_000L

        /** 落盘帧的保留张数：5fps × 60 = 12 秒的胶片，足够看清「失败那一刻屏上是什么」。 */
        private const val PREVIEW_KEEP_FILES = 60

        /** 每这么多帧扫一次 `frames/`，别每帧都 listFiles。 */
        private const val PREVIEW_PRUNE_EVERY = 10

        private const val SHUTDOWN_PREVIEW_JOIN_MS = 1_000L

        /** controller 连虚拟屏要等 bridge 起来 + 第一次 screencap，给足 60s。 */
        private const val CONTROLLER_CONNECT_TIMEOUT_MS = 60_000L

        /** 资源加载：MaaResource.postBundle 要解 pipeline + 读图，大资源包给它 10 分钟。 */
        private const val RESOURCE_LOAD_TIMEOUT_MS = 600_000L

        /** task 默认超时 30 分钟（MaaTasker.runTask 的默认值也是 30 分钟）。 */
        private const val DEFAULT_TASK_TIMEOUT_MS = 30 * 60_000L

        private const val TASKER_STOP_TIMEOUT_MS = 60_000L

        /**
         * `agent.start` 握手上限的下界。
         *
         * 子进程要 dlopen 四个 .so 再跑 Go runtime 的 init，冷启动在低端机上就要几百毫秒；
         * 给个 1s 的下界防止调用方传个荒唐的小值，把一次正常的慢启动误判成握手失败
         * （那样会杀掉一个其实马上就能起来的 agent）。
         */
        private const val MIN_AGENT_CONNECT_TIMEOUT_MS = 1_000L

        /**
         * `agent.start` 握手上限的上界，5 分钟。
         *
         * 握手本身不该慢（慢就是坏了），但它是同步阻塞在 worker 线程上的：
         * 封顶是为了让一条坏的 `agent.start` 不至于把 helper 的整个命令通道占死太久。
         */
        private const val MAX_AGENT_CONNECT_TIMEOUT_MS = 5 * 60_000L
    }
}
