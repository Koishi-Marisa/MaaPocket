package com.maapocket.core.run

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.maapocket.core.pi.PiInstaller
import com.maapocket.core.pi.PiRepository
import com.maapocket.core.pi.PiSelection
import com.maapocket.core.privilege.PrivilegeAvailability
import com.maapocket.core.privilege.PrivilegeKind
import com.maapocket.core.privilege.PrivilegeStatus
import com.maapocket.core.privilege.PrivilegedSession
import com.maapocket.core.privilege.RemoteFrame
import com.maapocket.core.privilege.RemoteProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File
import java.util.ArrayDeque

/**
 * 运行编排：把「用户在 UI 上的选择」变成特权进程里的一串命令。
 *
 * ## 为什么要跨进程
 * MaaFramework 的 Android Native controller 需要宿主提供一个 external lib
 * （`GetLockedPixels` / `UnlockPixels` / `DispatchInputMessage`），而这三个符号是
 * **进程内**的；同时建虚拟屏（`DisplayManager.createVirtualDisplay`）和注入输入
 * （`InputManager.injectInputEvent`）都只有 shell/root 身份才做得到。所以整套
 * MaaFramework 只能活在特权 helper 进程里，App 进程只发命令、收事件。
 *
 * ## 一次运行的命令序列
 * ```
 * PrivilegedSession.start()                     // su/Shizuku 拉起 liblauncher.so → app_process
 *   engine.setup     { userDir, nativeLibraryDir, requireMaa: true }
 *   maa.controller.start { displayId, width, height }   // 内部 dlopen <nativeLibraryDir>/libbridge.so
 *   resource.load    { paths: [...低优先级 → 高优先级...] }
 *   task.run         { entry, pipelineOverride }        // 每个选中的 task 一条，串行
 *   task.stop                                            // 用户按停止时
 * ```
 *
 * ## 线程模型
 * 所有 `exec` 都是挂起调用，由 [RemoteConnector] 的 reader 线程按 frame id 唤醒，不阻塞 UI。
 * 事件（`log` / `job.progress` / `frame` / `display.changed`）从 `session.events` 汇聚到这里。
 */
class MaaRunController(private val context: Context) {

    // ------------------------------------------------------------------ 对外状态

    enum class Phase {
        /** 还没做任何事。 */
        IDLE,

        /** 正在把 assets/pi 解包到外部私有目录。 */
        EXTRACTING,

        /** 正在拉起特权进程 / 加载引擎。 */
        PREPARING,

        /** 正在跑任务。 */
        RUNNING,

        /** 收到停止请求，正在收尾。 */
        STOPPING,

        /** 本轮全部 task 跑完。 */
        DONE,

        /** 出错停下。 */
        FAILED,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val privilege: PrivilegeStatus = PrivilegeStatus(),
        val privilegeOptions: List<Pair<PrivilegeKind, PrivilegeAvailability>> = emptyList(),
        val piRoot: File? = null,
        val packLabel: String? = null,
        val packStamp: String? = null,
        val engineReady: Boolean = false,
        val maaVersion: String? = null,
        val controllerReady: Boolean = false,
        val resourceLoaded: Boolean = false,
        val displayId: Int? = null,
        val taskRunning: Boolean = false,
        val currentTask: String? = null,
        val taskIndex: Int = 0,
        val taskCount: Int = 0,
        val lastError: String? = null,
    ) {
        val busy: Boolean get() = phase == Phase.EXTRACTING || phase == Phase.PREPARING ||
            phase == Phase.RUNNING || phase == Phase.STOPPING
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 最近 [LOG_CAPACITY] 条日志（最新在最后），供 UI 直接渲染。 */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** 截图预览流（`capture.preview` 推上来的 JPEG 解码结果）。 */
    private val _preview = MutableSharedFlow<Bitmap>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val preview: SharedFlow<Bitmap> = _preview.asSharedFlow()

    // ------------------------------------------------------------------ 内部

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var session: PrivilegedSession? = null

    private val ring = ArrayDeque<String>(LOG_CAPACITY)

    private var eventJob: Job? = null

    private var runJob: Job? = null

    @Volatile
    private var stopRequested = false

    // ------------------------------------------------------------------ 日志

    private fun log(line: String) {
        val stamped = "${System.currentTimeMillis() % 100_000} $line"
        Timber.i("%s", line)
        synchronized(ring) {
            while (ring.size >= LOG_CAPACITY) ring.removeFirst()
            ring.addLast(stamped)
            _logs.value = ring.toList()
        }
    }

    // ------------------------------------------------------------------ 权限

    /** 重新探测可用的提权后端（root / Shizuku）。 */
    fun refreshPrivilegeOptions() {
        val options = com.maapocket.core.privilege.PrivilegeBackends.availability(context)
        _state.update { it.copy(privilegeOptions = options) }
        options.forEach { (kind, availability) ->
            log("privilege ${kind.label}: ${describe(availability)}")
        }
    }

    private fun describe(a: PrivilegeAvailability): String = when (a) {
        is PrivilegeAvailability.Ready -> "ready"
        is PrivilegeAvailability.PermissionRequired -> "permission required"
        is PrivilegeAvailability.Unsupported -> "unsupported"
        is PrivilegeAvailability.Denied -> "denied: ${a.reason}"
    }

    // ------------------------------------------------------------------ 解包 + 准备

    /**
     * 解包 pi pack 并读出 `interface.json`。
     *
     * 返回 null 表示失败（原因已写进 [state] 与日志）。
     */
    suspend fun extractPack(force: Boolean = false): PiRepository? = withContext(Dispatchers.IO) {
        _state.update { it.copy(phase = Phase.EXTRACTING, lastError = null) }
        try {
            val installer = PiInstaller(context)
            // 资源包身份戳先读一次 assets 里的 interface.json 版本号，读不到就退化成 "dev"。
            val stamp = runCatching { readAssetVersion() }.getOrNull() ?: "dev"
            val fullStamp = "$stamp-vc${appVersionCode()}"
            val result = installer.install(fullStamp, force)
            log(
                "pi pack → ${result.root.absolutePath} " +
                    "(${result.fileCount} 文件 / ${result.bytes} 字节, extracted=${result.extracted})",
            )
            val repo = PiRepository.load(result.root)
            _state.update {
                it.copy(
                    phase = Phase.IDLE,
                    piRoot = result.root,
                    packLabel = repo.displayName(),
                    packStamp = fullStamp,
                )
            }
            if (repo.unsupportedControllerTypes.isNotEmpty()) {
                log("资源包声明了设备上不支持的类型（按 Adb 处理）：${repo.unsupportedControllerTypes.joinToString()}")
            }
            repo
        } catch (t: Throwable) {
            fail("解包 pi pack 失败", t)
            null
        }
    }

    private fun readAssetVersion(): String? = runCatching {
        context.assets.open("pi/interface.json").use { input ->
            val text = input.readBytes().toString(Charsets.UTF_8)
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun appVersionCode(): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        }
    }.getOrDefault(0L)

    // ------------------------------------------------------------------ 启动

    /**
     * 完整启动：拉起特权进程 → 加载 MaaFramework → 建屏 + controller → 载资源。
     *
     * 成功后 [state].phase 回到 [Phase.IDLE] 并且 `engineReady && controllerReady && resourceLoaded`
     * 全为 true，此时才能 [runTasks]。
     */
    suspend fun prepare(
        plan: PiSelection.PiRunPlan,
        kind: PrivilegeKind,
        displayWidth: Int? = null,
        displayHeight: Int? = null,
    ) {
        if (_state.value.busy) {
            log("已有任务在跑，忽略 prepare()")
            return
        }
        _state.update { it.copy(phase = Phase.PREPARING, lastError = null) }

        try {
            // 1. 特权进程
            val s = session?.takeIf { it.kind == kind && it.isConnected } ?: run {
                session?.stop()
                PrivilegedSession(context, kind).also { session = it }
            }
            hookEvents(s)

            val status = s.start()
            _state.update { it.copy(privilege = status) }
            if (!s.isConnected) {
                fail("特权进程未连上（kind=${kind.label}, uid=${status.uid}, state=${status.state}）", null)
                return
            }
            log("privileged session up: kind=${kind.label} token=${s.token}")

            // 2. engine.setup
            val userDir = File(context.getExternalFilesDir(null), "Maa")
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val setup = s.exec(
                RemoteProtocol.Cmd.ENGINE_SETUP,
                buildJsonObject {
                    put("userDir", userDir.absolutePath)
                    put("nativeLibraryDir", nativeLibDir)
                    put("requireMaa", true)
                },
                timeoutMs = 60_000L,
            )
            log("engine.setup → $setup")

            val maaWired = setup["maaWired"]?.jsonPrimitive?.contentOrNull
            if (maaWired != "true") {
                fail("MaaFramework 未在特权进程里加载成功（maaWired=$maaWired）", null)
                return
            }
            _state.update { it.copy(engineReady = true, maaVersion = null) }

            // 3. controller（内部 dlopen <nativeLibraryDir>/libbridge.so）
            val ctrl = s.exec(
                "maa.controller.start",
                buildJsonObject {
                    displayWidth?.let { put("width", it) }
                    displayHeight?.let { put("height", it) }
                },
                timeoutMs = 120_000L,
            )
            log("maa.controller.start → $ctrl")
            _state.update {
                it.copy(
                    controllerReady = true,
                    displayId = ctrl["displayId"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                )
            }

            // 4. resource.load（顺序 = 优先级，低到高）
            val paths = buildJsonArray { plan.bundlePaths.forEach { add(JsonPrimitive(it)) } }
            val loaded = s.exec(
                "resource.load",
                buildJsonObject { put("paths", paths) },
                timeoutMs = 600_000L,
            )
            log("resource.load → $loaded")
            if (loaded["loaded"]?.jsonPrimitive?.contentOrNull != "true") {
                fail("资源加载失败：${loaded["failedPath"]}", null)
                return
            }
            _state.update { it.copy(resourceLoaded = true, phase = Phase.IDLE) }

            plan.warnings.forEach { log("警告: $it") }
            // 不致命的提醒：agent 不在包内
            plan.agents.filterNot { it.runnableOnDevice }.forEach {
                log("提示: agent「${it.declared}」未随包提供，依赖它的任务会失败")
            }
        } catch (t: Throwable) {
            fail("准备失败", t)
        }
    }

    // ------------------------------------------------------------------ 跑任务

    /**
     * 串行跑 [tasks]。每个 task 单独一条 `task.run`；任一失败就停下（与 PI 的语义一致，
     * 上游 OneDragon / March7th 也都是「失败即停」而不是继续）。
     */
    fun runTasks(plan: PiSelection.PiRunPlan, taskNames: Collection<String>, optionValues: Map<String, String>) {
        val s = session ?: run { log("session 未启动"); return }
        val s0 = _state.value
        if (!s0.controllerReady || !s0.resourceLoaded) {
            log("controller/resource 未就绪，先执行「准备」")
            return
        }
        if (runJob?.isActive == true) {
            log("已有任务在跑")
            return
        }

        val repo = _repository ?: run { log("资源包未加载"); return }
        val chosen = plan.tasks.filter { it.name in taskNames }.ifEmpty { plan.tasks }
        if (chosen.isEmpty()) {
            log("没有选中的任务")
            return
        }

        stopRequested = false
        runJob = scope.launch {
            _state.update {
                it.copy(phase = Phase.RUNNING, taskCount = chosen.size, taskIndex = 0, lastError = null)
            }
            try {
                chosen.forEachIndexed { index, task ->
                    if (stopRequested) return@launch
                    _state.update { it.copy(taskIndex = index + 1, currentTask = task.name) }
                    log("▶ [${index + 1}/${chosen.size}] ${task.name} → entry=${task.entry}")

                    val override = mergeOverrides(plan, repo, task, optionValues)
                    val result = s.exec(
                        "task.run",
                        buildJsonObject {
                            put("entry", task.entry)
                            override?.let { put("pipelineOverride", it) }
                        },
                        timeoutMs = TASK_TIMEOUT_MS,
                    )
                    val ok = result["succeeded"]?.jsonPrimitive?.contentOrNull == "true"
                    log("◀ ${task.name} succeeded=$ok status=${result["status"]}")
                    if (!ok) {
                        fail("任务「${task.name}」失败", null)
                        return@launch
                    }
                }
                _state.update { it.copy(phase = Phase.DONE, currentTask = null, taskRunning = false) }
                log("全部任务完成")
            } catch (t: Throwable) {
                if (stopRequested) {
                    _state.update { it.copy(phase = Phase.IDLE, currentTask = null, taskRunning = false) }
                    log("已停止")
                } else {
                    fail("任务执行失败", t)
                }
            }
        }
    }

    /**
     * 把全局选项 + 该 task 自己声明的选项 + 调用方传入的取值合并成一个 override 对象。
     *
     * MaaFramework 的 `MaaTaskerPostTask(entry, pipeline_override)` 只吃一个 JSON，
     * 所以这里把 [PiSelection.PiRunPlan.overrides] 里所有分片按顺序浅合并（后者覆盖前者）。
     */
    private fun mergeOverrides(
        plan: PiSelection.PiRunPlan,
        repo: PiRepository,
        task: com.maapocket.core.pi.PiTask,
        optionValues: Map<String, String>,
    ): JsonObject? {
        val parts = plan.overrides + listOfNotNull(
            PiSelection.resolve(
                repo = repo,
                root = _state.value.piRoot!!,
                controllerName = _controllerName,
                resourceName = _resourceName,
                taskNames = listOf(task.name),
                optionValues = optionValues,
            ).overrides,
        ).flatten()

        if (parts.isEmpty()) return null
        val merged = LinkedHashMap<String, JsonElement>()
        parts.forEach { obj -> obj.forEach { (k, v) -> merged[k] = v } }
        return JsonObject(merged)
    }

    /** 用户按「停止」。 */
    fun requestStop() {
        if (runJob?.isActive != true) return
        stopRequested = true
        _state.update { it.copy(phase = Phase.STOPPING) }
        val s = session ?: return
        scope.launch {
            runCatching { s.exec("task.stop", null, timeoutMs = 30_000L) }
                .onFailure { log("task.stop 失败: ${it.message}") }
        }
    }

    /** 彻底收尾：停预览、断会话。 */
    fun shutdown() {
        runCatching { session?.notify("capture.preview", buildJsonObject { put("enable", false) }) }
        runCatching { session?.notify(RemoteProtocol.Cmd.SHUTDOWN) }
        runCatching { session?.stop() }
        session = null
        eventJob?.cancel()
        eventJob = null
        _state.update { it.copy(phase = Phase.IDLE, controllerReady = false, resourceLoaded = false, engineReady = false) }
    }

    // ------------------------------------------------------------------ 事件汇聚

    private fun hookEvents(s: PrivilegedSession) {
        if (eventJob?.isActive == true) return
        eventJob = s.events
            .onEach { frame -> onFrame(frame) }
            .launchIn(scope)
    }

    private fun onFrame(frame: RemoteFrame) {
        when (frame.event) {
            RemoteProtocol.Event.LOG -> {
                val line = frame.data?.get("line")?.jsonPrimitive?.contentOrNull
                    ?: frame.data?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: frame.data.toString()
                log("[helper] $line")
            }

            RemoteProtocol.Event.JOB_PROGRESS -> {
                val message = frame.data?.get("message")?.jsonPrimitive?.contentOrNull
                val details = frame.data?.get("details")?.jsonPrimitive?.contentOrNull
                if (message != null) {
                    val brief = details?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                    log("[maa] $message$brief")
                }
            }

            RemoteProtocol.Event.FATAL -> {
                val message = frame.data?.get("message")?.jsonPrimitive?.contentOrNull ?: "fatal"
                fail("特权进程致命错误：$message", null)
            }

            RemoteProtocol.Event.DISPLAY_CHANGED -> {
                val id = frame.data?.get("displayId")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                log("display.changed → $id")
                _state.update { it.copy(displayId = id) }
            }

            RemoteProtocol.Event.FRAME -> decodePreview(frame)
            else -> Unit
        }
    }

    private fun decodePreview(frame: RemoteFrame) {
        val inline = frame.data?.get("inline")?.jsonPrimitive?.contentOrNull ?: return
        runCatching {
            val bytes = Base64.decode(inline, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.let { _preview.tryEmit(it) }
    }

    // ------------------------------------------------------------------ 选择态

    private var _repository: PiRepository? = null
    private var _controllerName: String = ""
    private var _resourceName: String = ""

    fun setRepository(repo: PiRepository?) {
        _repository = repo
    }

    fun setSelection(controllerName: String, resourceName: String) {
        _controllerName = controllerName
        _resourceName = resourceName
    }

    // ------------------------------------------------------------------ 工具

    private fun fail(what: String, t: Throwable?) {
        val detail = t?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        log("✗ $what$detail")
        if (t != null) Timber.e(t, what)
        _state.update { it.copy(phase = Phase.FAILED, lastError = "$what$detail", taskRunning = false, currentTask = null) }
    }

    companion object {
        const val LOG_CAPACITY = 500

        /** 单个 task 的上限：MaaEnd 的长任务（导航、模拟作战）动辄十几分钟。 */
        const val TASK_TIMEOUT_MS = 60L * 60_000L

        /** 缺省虚拟屏尺寸：兜底用，正常应由 PI 的 display_short_side 或用户设置决定。 */
        val JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
