package com.maapocket.core.run

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.maapocket.core.pi.AgentRuntimeCatalog
import com.maapocket.core.pi.AgentWorkspace
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

        /**
         * agent 的工作目录是否**全部**准备就绪。
         *
         * 语义刻意收窄：只有「我们自己准备失败」（复制失败、缺 .so、目录不可写）才置 false 并
         * 阻止 [runTasks]；「资源包声明了 agent 但设备上没有对应产物」不算失败，只告警
         * （docs/agents.md §7.3 的降级策略）——否则 MaaEnd 会因为暂时没有安卓产物的
         * `agent/cpp-algo` 被整体卡死，比让它跑到第一个 Custom 节点再失败更糟。
         */
        val agentsReady: Boolean = false,

        /** 准备失败的 agent 声明（`child_exec`），供 UI 醒目提示。 */
        val agentFailures: List<String> = emptyList(),
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

    /**
     * 已备好工作目录、但**尚未启动**的 agent（见 [prepareAgentWorkspaces]）。
     *
     * 真正握手（`MaaAgentClient.create / bindResource / connect`）必须发生在持有 `MaaResource`
     * 的特权 helper 进程里 —— 见 `com.maapocket.core.pi.AgentLauncher` 的类注释。App 进程在这里
     * 只负责把工作目录（可执行文件 + `maafw/` + `debug/` + `locales/`）铺好，因为那只需要普通
     * 文件权限，不需要 MaaFramework。
     *
     * 公开只读：helper 侧的命令一旦落地，启动方需要拿到这份「可执行文件 + args + 工作目录」清单。
     */
    var preparedAgents: List<AgentWorkspace.Prepared> = emptyList()
        private set

    /**
     * 由**本进程**持有的 agent 句柄，[shutdown] 时统一 `close()`。
     *
     * 目前恒为空：MaaEnd 的 agent 必须由 helper 进程启动，App 进程拿不到可用的 `MaaAgentClient`。
     * 保留这个列表是为了让「谁启动谁负责关」的收尾语义现在就位，而不是等 helper 侧的
     * `agent.start` 落地后再回来补清理逻辑（那时很容易漏）。
     */
    private val agentHandles = mutableListOf<AutoCloseable>()

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

            // 5. agent：只准备运行环境，不启动。
            //    启动（握手）必须由持有 MaaResource 的特权 helper 进程做，而 RemoteProtocol
            //    目前还没有 agent.start 命令；详见 prepareAgentWorkspaces() 的注释。
            prepareAgentWorkspaces(plan)
        } catch (t: Throwable) {
            fail("准备失败", t)
        }
    }

    /**
     * 为 [plan] 里声明的每个 agent 准备运行环境，并更新 [State.agentsReady] / [State.agentFailures]。
     *
     * ## 为什么「只准备、不启动」
     * 上游契约要求 `create → identifier → 起子进程 → bindResource → connect` 这一串必须作用在
     * **同一个** `MaaResource` 上（见 `MaaAgentClient.kt` 的文档）。本模块所在的 App 进程刻意不加载
     * `libMaaFramework.so`（资源和控制器都活在特权 helper 进程里，见本文件类注释），因此 App 进程
     * 根本没有可用的 `MaaApi` / `MaaResource` 可以传进去。在 App 进程里另建一个 MaaResource 不只是
     * 浪费，还会让 agent 把自定义动作注册到一个**没人在跑 pipeline 的**资源上，静默失效。
     *
     * 所以这里只做「铺目录」这一半：[AgentLauncher] 已经把另外一半（`launch()`）实现好了，
     * helper 侧实现 `agent.start` 时直接调它即可。工作目录是幂等的，重复准备不会重复解包。
     *
     * ## 失败语义（对应 docs/agents.md §7.3）
     * - 设备上没有产物（如 `agent/cpp-algo`）→ 只告警、跳过，**不算失败**；
     * - 我们自己准备失败（复制失败、缺 `maafw/` 里的 .so、目录不可写）→ 置 `agentsReady = false`
     *   并写入 `lastError`。**不**在这里 `fail()`：`prepare()` 的其余部分（controller / resource）
     *   仍然可用，用户还能手工重试，把 phase 打成 FAILED 只会让 UI 显得整个准备都挂了。
     *   真正的阻断放在 [runTasks] 的入口检查上。
     */
    private suspend fun prepareAgentWorkspaces(plan: PiSelection.PiRunPlan) = withContext(Dispatchers.IO) {
        val agents = plan.agents
        preparedAgents = emptyList()
        if (agents.isEmpty()) {
            _state.update { it.copy(agentsReady = true, agentFailures = emptyList()) }
            return@withContext
        }

        // 资源包自己带的 agent（runnableOnDevice）由 PiSelection 标好；其余的走 APK 里的产物映射。
        agents.filterNot { it.runnableOnDevice }.forEach {
            log("提示: agent「${it.declared}」未随资源包提供，将按 APK 内的产物查找")
        }

        val batch = try {
            AgentWorkspace(context).prepareAll(agents, AgentRuntimeCatalog(context))
        } catch (t: Throwable) {
            val names = agents.joinToString { it.declared }
            log("✗ agent 工作目录准备失败（$names）：${t.message}")
            _state.update {
                it.copy(
                    agentsReady = false,
                    agentFailures = agents.map { a -> a.declared },
                    lastError = "agent 工作目录准备失败：${t.message}",
                )
            }
            return@withContext
        }

        batch.prepared.forEach { p ->
            // bundleFileCount == 0 表示幂等命中、本次没重新解包（见 AgentWorkspace.Prepared）。
            val bundleNote = when {
                !p.bundleInstalled ->
                    "（**没有** assets bundle：agent 很可能因缺 locales/go-service/zh_cn.json 而退出）"
                p.bundleFileCount > 0 -> "（bundle ${p.bundleFileCount} 个文件 -> locales/）"
                else -> "（bundle 复用上次解包结果）"
            }
            log("agent「${p.declared}」环境就绪 → ${p.workspace.absolutePath}$bundleNote")
        }
        batch.skipped.forEach { r -> log("警告: 跳过 agent「${r.declared}」：${r.detail}") }
        batch.failed.forEach { f -> log("✗ agent「${f.declared}」环境准备失败：${f.reason}") }

        preparedAgents = batch.prepared
        if (batch.prepared.isNotEmpty()) {
            // 说清楚当前的真实边界：目录铺好了，但进程还没起。
            // agent 的握手（bindResource/connect）必须在持有 MaaResource 的 helper 进程里做，
            // 而 RemoteProtocol 目前没有对应的命令，所以这里只能到此为止。
            log("注意: agent 环境已就绪但**尚未启动** —— 握手需要 helper 进程支持 agent.start，MaaEnd 的 Custom 节点在该命令落地前仍会失败")
        }
        val failures = batch.failed.map { it.declared }
        _state.update {
            it.copy(
                agentsReady = failures.isEmpty(),
                agentFailures = failures,
                lastError = if (failures.isEmpty()) {
                    it.lastError
                } else {
                    "agent 准备失败：${failures.joinToString()}（Custom 节点会失败，详见日志）"
                },
            )
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
        // agent 环境没准备好就拒绝启动：MaaEnd 约 40% 的 pipeline 节点是 Custom 类型，
        // 缺了 agent 跑到一半才失败的体验（截图停在某个界面、报一堆看不懂的字）比直接拦住更糟。
        // 注意这里**只**拦「我们准备失败」；「设备上没有该 agent 的产物」在 prepare 阶段只告警。
        if (!s0.agentsReady) {
            fail("agent 未就绪，已拒绝启动任务：${s0.agentFailures.joinToString()}（详见日志）", null)
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
        // agent 子进程必须先于 helper 会话退掉：它们是通过 helper 的 socket 跟 MaaAgentClient
        // 配对的，helper 先死会让 agent 卡在 recv 上不退（`AgentHandle.close()` 内部有 2s 宽限 + 强杀）。
        agentHandles.forEach { handle -> runCatching { handle.close() } }
        agentHandles.clear()
        preparedAgents = emptyList()
        runCatching { session?.notify("capture.preview", buildJsonObject { put("enable", false) }) }
        runCatching { session?.notify(RemoteProtocol.Cmd.SHUTDOWN) }
        runCatching { session?.stop() }
        session = null
        eventJob?.cancel()
        eventJob = null
        _state.update {
            it.copy(
                phase = Phase.IDLE,
                controllerReady = false,
                resourceLoaded = false,
                engineReady = false,
                agentsReady = false,
                agentFailures = emptyList(),
            )
        }
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
