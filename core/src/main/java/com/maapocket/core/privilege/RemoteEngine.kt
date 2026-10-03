package com.maapocket.core.privilege

import android.graphics.Bitmap
import android.os.SystemClock
import com.maapocket.core.bridge.NativeBridgeLib
import com.maapocket.core.constant.DefaultDisplayConfig
import com.maapocket.core.maa.DriverClass
import com.maapocket.core.privilege.RemoteProtocol.Cmd
import com.maapocket.core.privilege.RemoteProtocol.ErrorCode
import com.maapocket.core.privilege.RemoteProtocol.Event
import com.maapocket.core.third.Ln
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 特权进程里的**实际业务**：屏幕 + 截图 + 输入注入 + 拉起游戏。
 *
 * 对应 MAA-Meow `remote/RemoteServiceImpl.kt`（558 行）里除 AIDL/binder 样板之外的部分。
 * 这里**没有** Arknights 业务逻辑，只有把命令翻译成 `third/wrappers/*` 和
 * `maa/DriverClass` 的调用。
 *
 * ## 与 `RemoteMain` 的分工
 * - `RemoteMain`：进程引导（Context、Looper、异常处理器）、socket 服务、app 看门狗；
 * - `RemoteEngine`：装命令处理器（[install]）+ 做活。它是**无 Context** 的——
 *   显示/输入全走反射出来的系统服务（见 `RemoteMain` 头部注释）。
 *
 * ## 长任务与多路复用（需求 7）
 * 每个 `cmd` 由 `RemoteServer` 丢到独立 worker 线程执行，所以
 * `display.start`（要建屏 + 等 Surface）跑着的时候 `ping`/`display.state` 依然立即回。
 * 这里唯一需要自己管线程的是 `capture.preview`：它是一个持续循环，
 * 由 [Cmd.CAPTURE_PREVIEW] 的 `enable` 开关控制，**不占用 worker**。
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
     */
    private var userDir: File? = null

    private fun requireUserDir(): File = userDir ?: throw RemoteProtocolException(
        ErrorCode.NOT_READY,
        "userDir is not set yet; send engine.setup with a userDir first",
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
    }

    // ------------------------------------------------------------------ engine.*

    /**
     * 初始化。抄了 MAA-Meow `RemoteServiceImpl.setup` 的两个关键决定：
     * 1. **幂等**：成功后再调直接返回 OK，失败则下次重试；
     * 2. **先探路径可读性再交给 native**：MAA-Meow 的 `UserDirProbe` 注释说
     *    对不可读目录调 `AsstSetUserDir` 会**直接把进程 abort**（issue #227），
     *    所以宁可在这里返回错误，也不能让 native 拿到一个坏路径。
     *
     * 本仓目前没有 MaaFramework 的 JNI 绑定类（`maafw/MaaDef.kt` 只有常量，
     * 仓内不存在 `MaaFw`/`Asst*`），所以 `maa` 部分如实报 `wired=false`；
     * 调用方传 `requireMaa=true` 时会得到 [ErrorCode.MAAFRAMEWORK_NOT_WIRED]。
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
        if (setupOk == true && requested == userDir) {
            return buildJsonObject {
                put("userDir", requested.absolutePath)
                put("cached", true)
                put("maaWired", MAA_WIRED)
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

        if (params.bool("requireMaa") == true && !MAA_WIRED) {
            throw RemoteProtocolException(
                ErrorCode.MAAFRAMEWORK_NOT_WIRED,
                "MaaFramework JNI bindings are not part of this module yet; only display/input/capture commands are served",
                "see maafw/MaaDef.kt — it mirrors constants but no native binding class exists",
            )
        }

        setupOk = true
        setupDetail = "ok"
        userDir = requested
        Ln.i("RemoteEngine: setup ok userDir=${requested.absolutePath} nativeLoaded=${NativeBridgeLib.LOADED}")
        return buildJsonObject {
            put("userDir", requested.absolutePath)
            put("frameDir", frameDir.absolutePath)
            put("nativeLoaded", NativeBridgeLib.LOADED)
            put("maaWired", MAA_WIRED)
            put("cached", false)
        }
    }

    private fun engineInfo(): JsonObject = buildJsonObject {
        put("pid", android.os.Process.myPid())
        put("uid", android.os.Process.myUid())
        put("sdk", android.os.Build.VERSION.SDK_INT)
        put("nativeLoaded", NativeBridgeLib.LOADED)
        put("maaWired", MAA_WIRED)
        put("setupOk", setupOk)
        put("setupDetail", setupDetail)
        put("droppedEvents", server?.droppedEventCount ?: 0)
        put("display", displayState())
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
        val interval = (params.int("intervalMs") ?: DEFAULT_PREVIEW_INTERVAL_MS).coerceIn(MIN_PREVIEW_INTERVAL_MS, MAX_PREVIEW_INTERVAL_MS)
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
                        meta + buildJsonObject { put("path", file.absolutePath) }
                    }
                    val accepted = eventSink.emit(Event.FRAME, data)
                    if (!accepted) previewDropped++
                }
            } catch (e: Throwable) {
                Ln.w("RemoteEngine: preview tick failed: ${e.message}")
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

    // ------------------------------------------------------------------ 收尾

    /** 进程退出前的清理。由 `RemoteMain` 的 shutdown hook / 异常处理器调用。 */
    fun shutdown() {
        stopPreviewInternal()
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

    // ------------------------------------------------------------------ 小工具

    private fun currentDisplayId(): Int = display?.displayId?.takeIf { it != DefaultDisplayConfig.DISPLAY_NONE } ?: 0

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

    private fun JsonObject?.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()

    /** `JsonObject` 不可变，`+` 返回合并后的新对象。 */
    private operator fun JsonObject.plus(other: JsonObject): JsonObject =
        JsonObject(this.toMutableMap().apply { putAll(other) })

    companion object {
        private const val FRAME_DIR_NAME = "frames"

        /**
         * MaaFramework 的 JNI 绑定类目前不在本模块里（`maafw/MaaDef.kt` 只有常量镜像）。
         * 等绑定接进来后把它改成 true，`engine.setup(requireMaa=true)` 就会通过。
         */
        private const val MAA_WIRED = false

        private const val DRIVER_CLASS_NAME = "com.maapocket.core.maa.DriverClass"

        private const val DEFAULT_JPEG_QUALITY = 80

        /** 内联 base64 的默认上限：200 KiB 原始 → base64 约 267 KiB，离 1 MiB 行上限很远。 */
        private const val DEFAULT_INLINE_MAX_BYTES = 200 * 1024

        private const val DEFAULT_PREVIEW_INTERVAL_MS = 200

        private const val MIN_PREVIEW_INTERVAL_MS = 50

        private const val MAX_PREVIEW_INTERVAL_MS = 5_000

        private const val PREVIEW_JOIN_TIMEOUT_MS = 1_000L

        private const val SHUTDOWN_PREVIEW_JOIN_MS = 1_000L
    }
}
