package com.maapocket.core.run

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.maapocket.core.constant.DefaultDisplayConfig
import com.maapocket.core.pi.AgentRuntimeCatalog
import com.maapocket.core.pi.AgentWorkspace
import com.maapocket.core.pi.PiInstalledPackages
import com.maapocket.core.pi.PiInstaller
import com.maapocket.core.pi.PiRepository
import com.maapocket.core.pi.PiSelection
import com.maapocket.core.privilege.PrivilegeAvailability
import com.maapocket.core.privilege.PrivilegeKind
import com.maapocket.core.privilege.PrivilegeStatus
import com.maapocket.core.privilege.PrivilegedSession
import com.maapocket.core.privilege.ProcessSpawner
import com.maapocket.core.privilege.RemoteFrame
import com.maapocket.core.privilege.RemoteProtocol
import com.maapocket.core.privilege.RemoteRequestException
import kotlinx.coroutines.CancellationException
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
 *   display.start    { mode: "virtual", width, height }  // 先建虚拟屏，拿到 displayId
 *   maa.controller.start { displayId, width, height }   // 内部 dlopen <nativeLibraryDir>/libbridge.so
 *   resource.load    { paths: [...低优先级 → 高优先级...] }
 *   agent.start      { executable, args, workingDir, env: PI_* }   // 每个 PI 声明的 agent 一条
 *   task.run         { entry, pipelineOverride }        // 每个选中的 task 一条，串行
 *   task.stop                                            // 用户按停止时
 * ```
 *
 * `display.start` 必须在 `maa.controller.start` 之前，而且它的 `displayId` 要显式传给
 * controller：没有虚拟屏时 helper 侧会退化成主屏（displayId=0），游戏就会被拉到前台，
 * 「后台运行」这件事根本不成立。
 *
 * ## 线程模型
 * 所有 `exec` 都是挂起调用，由 [RemoteConnector] 的 reader 线程按 frame id 唤醒，不阻塞 UI。
 * 事件（`log` / `job.progress` / `frame` / `display.changed`）从 `session.events` 汇聚到这里。
 */
class MaaRunController(private val context: Context) {

    /**
     * 「这个客户端包名装在本机吗」探针，交给 [PiSelection.resolve] 自动挑国服 / B服。
     * 见 `PiInstalledPackages`。
     */
    private val installedPackages = PiInstalledPackages.of(context)

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

        /**
         * 已经在特权 helper 进程里**真正跑起来**的 agent（`agent.start` 的返回值）。
         *
         * 与 [agentsReady] 的分工：`agentsReady` 只说「工作目录铺好了」，这个列表才是
         * 「进程起来了、自定义识别/动作注册上了」。UI 回答「Custom 节点到底能不能跑」
         * 应该看这个列表，而不是 `agentsReady`。
         */
        val agentRuntimes: List<AgentRuntimeState> = emptyList(),

        /**
         * helper 进程不认识 `agent.start`（版本不匹配：app 升级后旧的 helper 进程还在跑）。
         *
         * 置 true 时 [agentRuntimes] 必然为空。**刻意不把 [agentsReady] 打成 false**：
         * 旧 helper = 旧行为（agent 起不来、任务照跑、Custom 节点由 MaaFramework 自己报错），
         * 不能因为一条命令不支持就让整个「准备」变成不可用。
         */
        val agentStartUnsupported: Boolean = false,
    ) {
        val busy: Boolean get() = phase == Phase.EXTRACTING || phase == Phase.PREPARING ||
            phase == Phase.RUNNING || phase == Phase.STOPPING
    }

    /** 一个已在 helper 进程里跑起来的 agent 子进程（`agent.start` 的返回）。 */
    data class AgentRuntimeState(
        /** 幂等键：`agent.start` 的 `label`,取自 [AgentWorkspace.Prepared.agentName]。 */
        val label: String,
        /** MaaFramework 生成的连接标识（socket 名），agent 侧取 `argv` 最后一项。 */
        val identifier: String,
        val pid: Long?,
        val ready: Boolean,
        /** agent 注册的自定义识别名（pipeline 里 `custom_recognition` 用得到）。 */
        val customRecognitions: List<String>,
        /** agent 注册的自定义动作名（pipeline 里 `custom_action` 用得到）。 */
        val customActions: List<String>,
    )

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
     * 已备好工作目录的 agent（见 [prepareAgentWorkspaces]），随后由 [startAgents] 交给 helper 启动。
     *
     * 真正握手（`MaaAgentClient.create / bindResource / connect`）必须发生在持有 `MaaResource`
     * 的特权 helper 进程里 —— 见 `com.maapocket.core.pi.AgentLauncher` 的类注释。App 进程在这里
     * 只负责把工作目录（可执行文件 + `maafw/` + `debug/` + `locales/`）铺好，因为那只需要普通
     * 文件权限，不需要 MaaFramework；启动则通过 `agent.start` 委托给 helper。
     *
     * 公开只读：启动方（[startAgents]）需要拿到这份「可执行文件 + args + 工作目录」清单。
     */
    var preparedAgents: List<AgentWorkspace.Prepared> = emptyList()
        private set

    /**
     * 由**本进程**持有的 agent 句柄，[shutdown] 时统一 `close()`。
     *
     * 恒为空，而且**应该**一直为空：MaaEnd 的 agent 由 helper 进程启动，`MaaAgentClient`
     * 也活在 helper 里，App 进程根本拿不到可关闭的句柄。保留这个列表是为了让「谁启动谁负责关」
     * 的收尾语义在代码里显式存在（App 侧的收尾动作是发 `shutdown`，由 helper 关掉 agent）。
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

    /**
     * 重新探测可用的提权后端（root / Shizuku）。
     *
     * **必须切 IO**：`PrivilegeBackends.availability()` 会走 libsu（fork `su` 探根）与
     * Shizuku 的 `pingBinder()` / `checkSelfPermission()`（跨进程 binder）。真机 logcat 实测
     * Shizuku 服务进程会被系统冻结（`EventType:FREEZE para:moe.shizuku.privileged.api`），
     * 此时 binder 调用会一直阻塞到超时（约 5s）：
     * `ANR in com.maapocket.hsr ... Reason:Input dispatching timed out (... Waited 5001ms for MotionEvent ...)`。
     * 之前这里是个普通函数、由 ViewModel 的 `init` 直接同步调，直接把主线程拖进了 ANR。
     *
     * 日志只在「探测结果相对上一次有变化」时打一行，避免每刷新一次就刷出一串看着像报错的
     * `privilege root: unsupported` / `privilege shizuku: permission required`。
     */
    suspend fun refreshPrivilegeOptions() = withContext(Dispatchers.IO) {
        val options = com.maapocket.core.privilege.PrivilegeBackends.availability(context)
        _state.update { it.copy(privilegeOptions = options) }
        if (options.isEmpty()) return@withContext
        val line = options.joinToString(" · ") { (kind, availability) ->
            "${kind.label} ${describe(availability)}"
        }
        if (line != lastPrivilegeLine) {
            lastPrivilegeLine = line
            log("提权后端：$line")
        }
    }

    /** 上一次打过的提权探测结果，用来去重日志。见 [refreshPrivilegeOptions]。 */
    @Volatile
    private var lastPrivilegeLine: String? = null

    /**
     * 按可用性给后端排序后取最优的一个：**Ready > PermissionRequired > Denied > Unsupported**。
     *
     * 「未授权」排在「不支持」前面是刻意的：Shizuku 未授权只差一次系统弹窗，而 `su` 不存在
     * 是死路。未 root 但有 Shizuku 的机器上，这一条决定了「准备」会不会一上来就报
     * 「root 不可用」。同档位保留 `privilegeOptions` 的原始顺序（`minByOrNull` 取首个最小）。
     *
     * 尚未探测过（列表为空）时返回 null，调用方应自行先调 [refreshPrivilegeOptions]。
     */
    fun preferredKind(): PrivilegeKind? =
        _state.value.privilegeOptions.minByOrNull { (_, availability) ->
            when (availability) {
                is PrivilegeAvailability.Ready -> 0
                is PrivilegeAvailability.PermissionRequired -> 1
                is PrivilegeAvailability.Denied -> 2
                is PrivilegeAvailability.Unsupported -> 3
            }
        }?.first

    /**
     * 只申请授权、**不跑任务** —— 给 UI 上的「授权」按钮用。
     *
     * 存在的理由：Shizuku 未授权时 [PrivilegedSession.start] 会**直接返回**（连系统弹窗都不弹），
     * 界面上只会看到一句「特权进程未连上」。真机实测设备侧 Shizuku 明明在跑、权限卡也写着
     * 「可用」，但 App 从来没被授权过 —— 而 `requestPermission` 在整个 UI 里没有任何入口。
     *
     * [PrivilegedSession.requestPermission] 内部用 latch 等异步回调，**必须**切 IO。
     */
    suspend fun requestPrivilege(
        kind: PrivilegeKind,
        timeoutMs: Long = 30_000L,
    ): PrivilegeAvailability = withContext(Dispatchers.IO) {
        val s = session?.takeIf { it.kind == kind }
            ?: PrivilegedSession(context, kind).also { session = it }
        var result = s.availability()
        if (result is PrivilegeAvailability.PermissionRequired) {
            log("${kind.label} 发起授权请求（请留意系统弹窗）…")
            s.requestPermission(timeoutMs) { result = it }
        }
        _state.update { it.copy(privilege = s.status.value) }
        log("${kind.label} 授权结果：${describe(result)}")
        result
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
            //
            // 还必须带上 APK 的安装时间：只按 `<version>-vc<versionCode>` 判身份时，**改了资源包
            // 但没升 versionCode** 的构建会命中旧目录，用户看到的仍是上一版资源。真机上就踩过
            // 这个坑 —— 崩铁包补了 B 服 overlay 和 5 个 option，装上新 APK 后界面上一个都没变。
            // 换版本号能救一次，但治不了根：只要有构建忘了升号就会再次发生。
            // `lastUpdateTime` 每次安装都会变，用它当身份戳的语义正好是「这次安装的 assets 可能
            // 和上次不同」；重解代价只在安装后第一次点「解包」时付一次。
            val stamp = runCatching { readAssetVersion() }.getOrNull() ?: "dev"
            val fullStamp = "$stamp-vc${appVersionCode()}-u${appLastUpdateTime()}"
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

    /** APK 的安装时间（毫秒）。见 [extractPack]：用它让「换了资源包但没升 versionCode」也能重解。 */
    private fun appLastUpdateTime(): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    /** 给 `PI_CLIENT_VERSION` 用；拿不到就不注入（规范允许省略）。 */
    @Suppress("DEPRECATION")
    private fun appVersionName(): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.takeIf { it.isNotBlank() }
    }.getOrNull()

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
    ) = withContext(Dispatchers.IO) {
        prepareInner(plan, kind, displayWidth, displayHeight)
    }

    /**
     * [prepare] 的正体。**只能**从 [prepare] 进入 —— 里面几乎全是阻塞调用：
     * `PrivilegeBackends.availability()`（libsu 会 fork `su` 探根）、`backend.spawn()`（Shizuku 的
     * `IRemoteProcess` binder）、`RemoteConnector.connect()`（最长 18s 轮询等待 socket）。
     *
     * 之前没有 [withContext]，而 `MaaPocketViewModel.prepare()` 由 `viewModelScope.launch` 在
     * **Main** 上发起，于是点一下「准备」就把主线程按住不动 —— 真机 logcat 实证：
     * `ANR in com.maapocket.hsr (...) Reason:Input dispatching timed out (... Waited 5001ms for MotionEvent ...)`。
     * 那一下正好撞上系统冻结 Shizuku（`EventType:FREEZE para:moe.shizuku.privileged.api`），
     * binder 要等到超时才返回。
     *
     * 失败**不抛**：全部经由 [fail] 写进 [State.lastError] 与日志。
     */
    private suspend fun prepareInner(
        plan: PiSelection.PiRunPlan,
        kind: PrivilegeKind,
        displayWidth: Int?,
        displayHeight: Int?,
    ) {
        if (_state.value.busy) {
            log("已有任务在跑，忽略 prepare()")
            return
        }
        _state.update { it.copy(phase = Phase.PREPARING, lastError = null) }

        // 设备上真正的后端可能和用户选的不一样 —— 「未 root 但有 Shizuku」是绝大多数机器。
        // 先确保探测结果是最新的；若选中的后端不是 Ready，就自动改用优先级最高的那个并写清
        // 楚为什么。否则用户只会看到一句「root 不可用」，而 shizuku 明明只差一次授权。
        // 用户在权限卡里的显式选择会被记进 kindPickedByUser，这里只影响「准备」这一轮。
        var effectiveKind = kind
        if (_state.value.privilegeOptions.isEmpty()) refreshPrivilegeOptions()
        val chosen = _state.value.privilegeOptions.firstOrNull { it.first == effectiveKind }
        if (chosen == null || chosen.second !is PrivilegeAvailability.Ready) {
            val better = preferredKind()
            if (better != null && better != effectiveKind) {
                val why = chosen?.second?.let { describe(it) } ?: "未探测到"
                log("${effectiveKind.label} 当前不可用（$why），本轮自动改用 ${better.label}")
                effectiveKind = better
            }
        }

        try {
            // 1. 特权进程
            val s = session?.takeIf { it.kind == effectiveKind && it.isConnected } ?: run {
                session?.stop()
                PrivilegedSession(context, effectiveKind).also { session = it }
            }
            hookEvents(s)

            var status = s.start()
            _state.update { it.copy(privilege = status) }

            // Shizuku 没授权时 start() 会**直接返回**（根本不会去 spawn），此时如果只报一句
            // 「特权进程未连上」，用户拿到的是一句和真实原因无关的话。这里主动申请一次授权再重试。
            // requestPermission 内部用 CountDownLatch 等异步回调，**不能**在主线程调 ——
            // prepareInner 已经整体跑在 Dispatchers.IO 上，所以这里是安全的。
            if (!s.isConnected && status.availability is PrivilegeAvailability.PermissionRequired) {
                log("${effectiveKind.label} 尚未授权，发起授权请求（请留意系统弹窗）…")
                var granted: PrivilegeAvailability? = null
                s.requestPermission(timeoutMs = 30_000L) { granted = it }
                status = s.status.value
                _state.update { it.copy(privilege = status) }
                if (granted?.isReady == true) {
                    log("${effectiveKind.label} 已授权，重新拉起特权进程")
                    status = s.start()
                    _state.update { it.copy(privilege = status) }
                }
            }

            if (!s.isConnected) {
                // 把 backend 给出的**真实原因**（availability / spawn / connect 的 detail）带出来，
                // 并指明 launcher 日志的确切位置。之前这里只有一句笼统的「特权进程未连上」，
                // 用户按它去排查什么都找不到 —— 这正是「先处理日志报错」要修的东西。
                val why = status.detail?.takeIf { it.isNotBlank() } ?: status.state.name
                fail(
                    "特权进程未连上（${effectiveKind.label} uid=${status.uid}）：$why" +
                        "；launcher 日志 ${ProcessSpawner.launcherLogFile(context, effectiveKind).absolutePath}",
                    null,
                )
                return
            }
            log("privileged session up: kind=${effectiveKind.label} token=${s.token}")

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

            // 3. display（必须在 controller 之前：controller 要连的就是这块屏）
            //
            // 这一步以前漏了，后果正是 m03108 报的「启动后游戏没有后台运行」：
            // 没人调 display.start 时，`RemoteEngine.currentDisplayId()` 会退化成 0
            // （主屏），于是 StartApp 把游戏拉在主屏前台，MaaFramework 拿到的也是主屏画面。
            // 建了虚拟屏之后 displayId != 0，StartApp 才会 launchDisplayId 到那块屏上，
            // 游戏就在后台跑，用户自己的手机前台不受影响。
            val disp = s.exec(
                RemoteProtocol.Cmd.DISPLAY_START,
                buildJsonObject {
                    put("mode", "virtual")
                    displayWidth?.let { put("width", it) }
                    displayHeight?.let { put("height", it) }
                },
                timeoutMs = 60_000L,
            )
            log("display.start → $disp")
            val virtualDisplayId = disp["displayId"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: DefaultDisplayConfig.DISPLAY_NONE
            if (virtualDisplayId == DefaultDisplayConfig.DISPLAY_NONE) {
                fail("虚拟屏没建起来（display.start 返回 displayId=$virtualDisplayId）", null)
                return
            }

            // 4. controller（内部 dlopen <nativeLibraryDir>/libbridge.so）
            val ctrl = s.exec(
                "maa.controller.start",
                buildJsonObject {
                    put("displayId", virtualDisplayId)
                    displayWidth?.let { put("width", it) }
                    displayHeight?.let { put("height", it) }
                },
                timeoutMs = 120_000L,
            )
            log("maa.controller.start → $ctrl")
            _state.update {
                it.copy(
                    controllerReady = true,
                    displayId = ctrl["displayId"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        ?: virtualDisplayId,
                )
            }

            // 5. resource.load（顺序 = 优先级，低到高）
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
            _state.update { it.copy(resourceLoaded = true) }

            plan.warnings.forEach { log("警告: $it") }

            // 6. agent：App 进程只铺工作目录（普通文件权限就够），握手（`create / identifier /
            //    起子进程 / bindResource / connect`）必须由持有 MaaResource 的 helper 进程做，
            //    所以这里铺完目录紧接着发 `agent.start`，由 helper 侧的 AgentLauncher 完成握手。
            prepareAgentWorkspaces(plan)
            startAgents(s)
            // phase 到这里才回 IDLE：`busy` 含着 PREPARING，提前放行会让 UI 在 agent 还没起来时
            // 就允许「开始任务」（agent 没起来 = Custom 节点必失败）。
            _state.update { it.copy(phase = Phase.IDLE) }
        } catch (t: Throwable) {
            fail("准备失败", t)
        }
    }

    /**
     * 为 [plan] 里声明的每个 agent **铺工作目录**，并更新 [State.agentsReady] / [State.agentFailures]。
     *
     * ## 为什么只做「铺目录」这一半
     * 上游契约要求 `create → identifier → 起子进程 → bindResource → connect` 这一串必须作用在
     * **同一个** `MaaResource` 上（见 `MaaAgentClient.kt` 的文档）。本模块所在的 App 进程刻意不加载
     * `libMaaFramework.so`（资源和控制器都活在特权 helper 进程里，见本文件类注释），因此 App 进程
     * 根本没有可用的 `MaaApi` / `MaaResource` 可以传进去。在 App 进程里另建一个 MaaResource 不只是
     * 浪费，还会让 agent 把自定义动作注册到一个**没人在跑 pipeline 的**资源上，静默失效。
     *
     * 所以这里只做「铺目录」（纯文件操作，App 权限就够），另外一半由 [startAgents] 发 `agent.start`
     * 让 helper 侧的 `AgentLauncher` 做。工作目录是幂等的，重复准备不会重复解包。
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
            // 这里只到「目录铺好」为止；接下来由 prepare() 调 startAgents() 发 agent.start 让 helper 拉起进程。
            log("agent 工作目录已就绪（${batch.prepared.size} 个），下一步由 helper 执行 agent.start")
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

    /**
     * 让特权 helper 进程把 [preparedAgents] 里的每一个 agent 子进程拉起来（`agent.start`）。
     *
     * 为什么命令要发给 helper 而不是在这里做：握手的第一步 `MaaAgentClient.create` 与第四步
     * `bindResource(resource)` 必须作用在**同一个** `MaaResource` 上，而这个资源活在 helper 进程里
     * （App 进程刻意不加载 `libMaaFramework.so`）。在 App 进程另建一个资源只会让 agent 把自定义
     * 动作注册到一个没人在跑 pipeline 的资源上，静默失效——所以这里只发命令 + 记结果。
     *
     * ## 降级（旧 helper）
     * helper 不认识 `agent.start` 时，`RemoteServer` 会回 `E_NO_SUCH_CMD`，
     * [PrivilegedSession.exec] 把它转成 `RemoteRequestException`。这种情况**只告警、不失败**：
     * 置 [State.agentStartUnsupported]，保持 [State.agentsReady] 不变（= 旧行为，任务照跑，
     * Custom 节点由 MaaFramework 自己报错）。把 prepare 打成 FAILED 才是真的倒退。
     *
     * 其余错误（握手超时、`execve` 失败）才记进 [State.agentFailures] 并置 `agentsReady = false`，
     * 让 [runTasks] 在启动任务前拦住——MaaEnd 约 40% 的 pipeline 节点是 Custom 类型，
     * 跑到一半才失败比直接拦住更糟。
     */
    private suspend fun startAgents(session: PrivilegedSession) = withContext(Dispatchers.IO) {
        val pending = preparedAgents
        if (pending.isEmpty()) return@withContext

        val env = piAgentEnv()
        val started = mutableListOf<AgentRuntimeState>()
        val failed = mutableListOf<String>()
        var unsupported = false

        for (p in pending) {
            val label = p.agentName
            val params = buildJsonObject {
                put("executable", p.runtime.executable.absolutePath)
                put("args", buildJsonArray { p.runtime.args.forEach { add(JsonPrimitive(it)) } })
                p.runtime.workingDir?.let { put("workingDir", it.absolutePath) }
                p.runtime.nativeLibDir?.let { put("nativeLibDir", it.absolutePath) }
                put("label", label)
                put("timeoutMs", AGENT_CONNECT_TIMEOUT_MS)
                put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
            }

            val result = try {
                session.exec(RemoteProtocol.Cmd.AGENT_START, params, timeoutMs = AGENT_START_TIMEOUT_MS)
            } catch (e: RemoteRequestException) {
                when (e.code) {
                    RemoteProtocol.ErrorCode.NO_SUCH_CMD, RemoteProtocol.ErrorCode.UNSUPPORTED -> {
                        unsupported = true
                        log(
                            "警告: helper 进程不支持 agent.start（${e.code}）——agent 未启动，" +
                                "MaaEnd 的 Custom 节点会失败。app 升级后旧 helper 进程可能仍在运行，" +
                                "彻底关掉 app 再重开可让它用上新代码。",
                        )
                        break
                    }
                    else -> {
                        failed += p.declared
                        log("✗ agent「${p.declared}」启动失败（${e.code}）：${e.message}")
                        null
                    }
                }
            } catch (c: CancellationException) {
                // 取消不是「某个 agent 启动失败」：整段 prepare 正在被取消，继续遍历没有意义。
                throw c
            } catch (t: Throwable) {
                failed += p.declared
                log("✗ agent「${p.declared}」启动失败：${t.message}")
                null
            }

            if (result != null) {
                val state = parseAgentRuntime(label, result)
                started += state
                log(
                    "agent「${p.declared}」已启动 → label=${state.label} identifier=${state.identifier} " +
                        "pid=${state.pid} ready=${state.ready} " +
                        "recognitions=${state.customRecognitions.size} actions=${state.customActions.size}",
                )
                if (state.customRecognitions.isNotEmpty()) {
                    log("  custom_recognition: ${state.customRecognitions.joinToString()}")
                }
                if (state.customActions.isNotEmpty()) {
                    log("  custom_action: ${state.customActions.joinToString()}")
                }
            }
        }

        _state.update {
            it.copy(
                agentRuntimes = started,
                agentStartUnsupported = unsupported,
                agentsReady = if (unsupported) it.agentsReady else failed.isEmpty(),
                agentFailures = if (unsupported) it.agentFailures else failed,
                lastError = when {
                    unsupported -> it.lastError
                    failed.isEmpty() -> it.lastError
                    else -> "agent 启动失败：${failed.joinToString()}（Custom 节点会失败，详见日志）"
                },
            )
        }
    }

    /**
     * `agent.start` 的返回值 → [AgentRuntimeState]。
     *
     * 全部字段按「缺了也不炸」处理：helper 那边 `pid` 是 `Long?`（拿不到就不写），
     * 自定义列表在句柄失效时可能为空数组。
     */
    private fun parseAgentRuntime(label: String, result: JsonObject): AgentRuntimeState = AgentRuntimeState(
        label = result["label"]?.jsonPrimitive?.contentOrNull ?: label,
        identifier = result["identifier"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        pid = result["pid"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        ready = result["ready"]?.jsonPrimitive?.contentOrNull == "true",
        customRecognitions = result.stringArray("customRecognitions"),
        customActions = result.stringArray("customActions"),
    )

    private fun JsonObject.stringArray(key: String): List<String> {
        val array = this[key] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    /**
     * 8 个 `PI_*` 环境变量（PI V2 规范自 v2.5.0 起要求 Client 注入，见 `docs/agents.md` §2）。
     *
     * Go agent 的 `pkg/pienv` 逐个 `os.Getenv`，缺值可容忍；所以这里**只放有真实取值的键**，
     * 拿不到的键直接不写（规范明确允许省略），而不是塞空串或编一个值。
     *
     * - `PI_INTERFACE_VERSION`：Client 实现的 PI 扩展面版本。取 [PI_EXTENSION_VERSION]，
     *   即本实现遵循的规范级别（v2.5.0 = 注入 `PI_*` + agent 契约），**不是** interface.json
     *   里那个数值型 `interface_version`；
     * - `PI_VERSION`：资源包自身版本（assets/pi/interface.json 的 `version`）；
     * - `PI_CLIENT_MAAFW_VERSION`：来自 [State.maaVersion]，目前恒为 null（没人写这个字段），
     *   拿不到就不注入——填一个猜的版本比不填更糟；
     * - `PI_CONTROLLER` / `PI_RESOURCE`：单行压缩 JSON。**刻意只给 name/type**：
     *   Go agent 真正用到的是 `pienv.ControllerType()`（判断是否 `Win32`），
     *   `name` + `type` 就足以给出正确答案；把 i18n 未解析的整份 PI 模型吐给它反而可能
     *   把 `$xxx` 引用键带进去（规范要求 i18n 已解析、不含 `$` 前缀键）。
     */
    private fun piAgentEnv(): Map<String, String> {
        val current = _state.value
        val controllerType = _repository?.controllers()?.firstOrNull { it.name == _controllerName }?.type
            ?.takeIf { it.isNotBlank() }
            ?: PiRepository.ON_DEVICE_PI_TYPE
        val env = LinkedHashMap<String, String>(8)
        env["PI_INTERFACE_VERSION"] = PI_EXTENSION_VERSION
        env["PI_CLIENT_NAME"] = PI_CLIENT_NAME
        appVersionName()?.let { env["PI_CLIENT_VERSION"] = it }
        env["PI_CLIENT_LANGUAGE"] = java.util.Locale.getDefault().toString()
        current.maaVersion?.let { env["PI_CLIENT_MAAFW_VERSION"] = it }
        readAssetVersion()?.let { env["PI_VERSION"] = it }
        env["PI_CONTROLLER"] = if (_controllerName.isEmpty()) {
            """{"type":"$controllerType"}"""
        } else {
            """{"name":"$_controllerName","type":"$controllerType"}"""
        }
        if (_resourceName.isNotEmpty()) {
            env["PI_RESOURCE"] = """{"name":"$_resourceName"}"""
        }
        return env
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
                installedPackages = installedPackages,
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
        // App 进程自己从不持有 agent 句柄（列表恒为空），agent 由 helper 关：
        // 下面那条 `shutdown` 命令会让 RemoteEngine 先断 agent、再断 tasker/resource/controller。
        // 这段清理保留着，是为了「谁启动谁负责关」这条语义在代码里始终显式存在。
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
                agentRuntimes = emptyList(),
                agentStartUnsupported = false,
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

        /**
         * `agent.start` 里交给 helper 的**握手上限**（helper 侧还会 clamp 到 1s..5min）。
         *
         * 20s 与 [com.maapocket.core.pi.AgentLauncher.DEFAULT_CONNECT_TIMEOUT_MS] 一致：
         * 子进程要 dlopen 四个 .so 再跑 Go runtime 的 init，冷启动在低端机上就要几百毫秒。
         */
        const val AGENT_CONNECT_TIMEOUT_MS = 20_000L

        /**
         * `agent.start` 这一条远程请求自己的超时，比 [AGENT_CONNECT_TIMEOUT_MS] 宽 10s。
         *
         * 必须**大于**握手上限：helper 是同步阻塞着握手的，若请求超时先到，app 会认定失败
         * 并把 prepare 打成 FAILED，而 helper 那边其实刚刚成功——留下一个没人管的 agent 子进程。
         */
        const val AGENT_START_TIMEOUT_MS = 30_000L

        /**
         * 本实现遵循的 PI 扩展面版本（= `PI_INTERFACE_VERSION`）。
         *
         * v2.5.0 是规范里开始要求 Client 注入 8 个 `PI_*` 变量的版本，也正是本实现覆盖的范围：
         * 注入 `PI_*` + 实现 agent 握手（`create / identifier / 子进程 / bindResource / connect`）。
         * **不是** interface.json 里那个数值型 `interface_version`（那也是 2，但含义完全不同）。
         */
        const val PI_EXTENSION_VERSION = "2.5.0"

        /** `PI_CLIENT_NAME`：规范里这个变量指的是 Client 实现的名字。 */
        const val PI_CLIENT_NAME = "MaaPocket"

        /** 缺省虚拟屏尺寸：兜底用，正常应由 PI 的 display_short_side 或用户设置决定。 */
        val JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
