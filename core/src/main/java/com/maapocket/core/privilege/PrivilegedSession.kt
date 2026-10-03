package com.maapocket.core.privilege

import android.content.Context
import android.os.Process
import com.maapocket.core.privilege.RemoteProtocol.Cmd
import com.maapocket.core.privilege.RemoteProtocol.Event
import com.maapocket.core.third.Ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/** 特权进程回了一条 `ok=false`。`code` 是 `RemoteProtocol.ErrorCode.*`。 */
class RemoteRequestException(
    val code: String,
    message: String,
    val detail: String? = null,
) : IOException(message)

/**
 * **app 侧**的顶层门面：`start()` → 可用 → `exec()`/`notify()` → `stop()`。
 *
 * 对应 MAA-Meow 的 `ProcessServiceConnectorBackend` + `ShizukuManager`/`RootManager`，
 * 但把「起进程 → 连 binder → 等 attach」那套换成了
 * 「起进程 → 等特权进程把管道送回来 → `hello` 鉴权」。
 *
 * ## 一次完整启动
 * 1. [PrivilegeBackend.availability] 判定后端是否就绪（Shizuku 权限 / root）；
 * 2. 生成一次性 [token]，`BootstrapRegistry.register(token)` **先挂号**（必须早于 spawn，
 *    否则特权进程调回 `BootstrapProvider` 时找不到 token 槽位）；
 * 3. [ProcessSpawner.build] 拼出 `liblauncher.so` 命令行，后端 `spawn`：
 *    Shizuku 走 `IShizukuService.newProcess`，root 走 `libsu Shell`；
 * 4. [RemoteConnector.connect] 等那个槽位被 `attach` 完成，最多
 *    [RemoteProtocol.CONNECT_TIMEOUT_MS]——特权进程从 fork 到 ART 起 main 通常几百毫秒；
 * 5. 发 `hello`（连同本进程 pid），成功后进 [PrivilegeState.READY]，并每
 *    [HEARTBEAT_INTERVAL_MS] 发一次 `heartbeat`。
 *
 * ## 为什么 token 必须是一次性的
 *
 * 引导阶段特权进程要靠 token 在 app 的 `BootstrapRegistry` 里认领槽位，
 * 而 `BootstrapProvider` 是 `exported` 的（不然 `getContentProviderExternal` 找不到它）。
 * token 每会话随机 + 只在内存里，别的 app 猜不出来就等于拿不到管道；
 * 同时它也让「app 重启后留下的旧特权进程」自然出局：旧进程手上的 token
 * 在新进程的注册表里不存在，`BootstrapClient` 会直接失败退出，由它自己的看门狗收尸。
 *
 * ## 状态
 * [status] 是唯一的对外状态源；[events] 透传特权进程的主动事件（帧、日志、致命错误）。
 */
class PrivilegedSession(
    context: Context,
    val kind: PrivilegeKind,
    /** 每次会话都应是新值；两个后端不共享。 */
    val token: String = newToken(),
    private val debug: Boolean = false,
) : Closeable {

    private val appContext: Context = context.applicationContext ?: context

    private val backend: PrivilegeBackend = PrivilegeBackends.of(appContext, kind)

    private val _status = MutableStateFlow(PrivilegeStatus(kind = kind))

    val status: StateFlow<PrivilegeStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var connector: RemoteConnector? = null

    private var handle: SpawnHandle? = null

    private var invocation: LauncherInvocation? = null

    private var heartbeatJob: Job? = null

    /**
     * 特权进程推来的事件。
     *
     * **必须是稳定的流，不能在 getter 里读 `connector`。** [MaaRunController] 在
     * `start()` 之前就订阅了它，那时 `connector` 还是 null；旧实现写的是
     * `connector?.events ?: emptyFlow()`，于是订阅者拿到一个「立刻完成」的空流，
     * 整个会话再也收不到任何事件（表现为预览永远「等待画面…」、应用日志里没有
     * `[helper]` / `[maa]` 行）。现在改成先把 [RemoteConnector.events] 桥接进一个
     * 进程级 [MutableSharedFlow]，订阅者什么时候来都能收到后续事件。
     */
    val events: Flow<RemoteFrame> = _events.asSharedFlow()

    private val _events = MutableSharedFlow<RemoteFrame>(
        replay = 0,
        extraBufferCapacity = RemoteProtocol.EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var eventForwardJob: Job? = null

    /** 把 [RemoteConnector.events] 桥接进 [events]。换 connector 时先取消旧桥。 */
    private fun bridgeEvents(conn: RemoteConnector) {
        eventForwardJob?.cancel()
        eventForwardJob = conn.events
            .onEach { frame -> _events.emit(frame) }
            .launchIn(scope)
    }

    /** 与 `ProcessServiceConnectorBackend.keepRootForInputInjection` 同义（需求 5）。 */
    val inputInjectionNeedsRoot: Boolean get() = ProcessSpawner.keepRootForInputInjection

    // ------------------------------------------------------------------ 权限

    fun availability(): PrivilegeAvailability = runCatching { backend.availability() }
        .getOrElse { PrivilegeAvailability.Denied(it.message ?: it.javaClass.simpleName) }

    /**
     * 请求权限（Shizuku 弹授权框 / root 请求 `su`）。
     * **不要在主线程调用**：内部要用 [java.util.concurrent.CountDownLatch] 等回调。
     */
    fun requestPermission(timeoutMs: Long = 15_000L, onResult: (PrivilegeAvailability) -> Unit) {
        update { it.copy(state = PrivilegeState.CHECKING) }
        backend.requestPermission(timeoutMs) { availability ->
            update {
                it.copy(
                    state = if (availability.isReady) PrivilegeState.IDLE else PrivilegeState.PERMISSION_REQUIRED,
                    availability = availability,
                )
            }
            runCatching { onResult(availability) }
        }
    }

    // ------------------------------------------------------------------ 启动

    /** 完整启动一次会话。失败**不抛**（除取消），把原因写进返回的 [PrivilegeStatus]。 */
    suspend fun start(timeoutMs: Long = RemoteProtocol.CONNECT_TIMEOUT_MS): PrivilegeStatus {
        if (connector?.isConnected == true) return _status.value

        update { it.copy(state = PrivilegeState.CHECKING, availability = availability()) }
        val availability = _status.value.availability
        if (!availability.isReady) {
            return update {
                it.copy(
                    state = if (availability is PrivilegeAvailability.PermissionRequired) {
                        PrivilegeState.PERMISSION_REQUIRED
                    } else {
                        PrivilegeState.DEAD
                    },
                    detail = describe(availability),
                )
            }
        }

        val runsAsRoot = backendRunsAsRoot()
        if (inputInjectionNeedsRoot && !runsAsRoot) {
            // 需求 5：Android 14+ 的注入要求 uid 0 **或者**该 uid 已被授予 INJECT_EVENTS。
            // 实测（HONOR AGI-AN00 / Android 15，`dumpsys package com.android.shell`）uid 2000
            // 已经 granted INJECT_EVENTS —— 所以「非 root 就一定点不动」是错的，这里不能断言失败。
            // 真正失败时由 InputManager.java:105-117 打准确原因 + 「USB 调试（安全设置）」提示。
            Ln.i(
                "PrivilegedSession: SDK ${android.os.Build.VERSION.SDK_INT}，" +
                    "$kind 后端以 uid=${backendUid()} 运行（非 root）：注入输入只有在 uid 0 或" +
                    "该 uid 已被授予 INJECT_EVENTS 时才可用；若 input.* 报 INJECT_EVENTS permission，" +
                    "请开启「开发者选项 → USB 调试（安全设置）」后重启设备",
            )
        }

        val launcherLog = ProcessSpawner.launcherLogFile(appContext, kind)
        val inv = ProcessSpawner.build(
            context = appContext,
            suffix = kind.processSuffix,
            token = token,
            logFile = launcherLog,
            debug = debug,
        )
        invocation = inv

        update {
            it.copy(
                state = PrivilegeState.SPAWNING,
                availability = availability,
                runsAsRoot = runsAsRoot,
                uid = backendUid(),
                detail = "launcher=${inv.launcherPath} token=${BootstrapRegistry.short(token)}",
            )
        }

        // 关键顺序：必须先挂号再 spawn。特权进程一起来就会调 BootstrapProvider，
        // 槽位不存在它会立刻判失败并退出进程，这一轮会话就白跑了。
        val slot = BootstrapRegistry.register(token)

        handle = try {
            backend.spawn(inv)
        } catch (e: Throwable) {
            Ln.e("PrivilegedSession: spawn failed for $kind", e)
            BootstrapRegistry.unregister(token)
            return update { it.copy(state = PrivilegeState.DEAD, detail = "spawn failed: ${e.message}") }
        }

        update { it.copy(state = PrivilegeState.CONNECTING) }
        val conn = RemoteConnector(token, slot)
        connector = conn
        bridgeEvents(conn)
        conn.onClosed = { cause ->
            // 连上之后断的（app 主动 close 不会走到这里）。
            update {
                it.copy(
                    state = PrivilegeState.DEAD,
                    detail = cause?.let { c -> "pipe closed: ${c.javaClass.simpleName}: ${c.message}" } ?: "pipe closed",
                )
            }
        }

        try {
            conn.connect(timeoutMs)
        } catch (e: Throwable) {
            val exit = handle?.exitCode()
            val detail = buildString {
                append("connect failed: ")
                append(e.message)
                if (exit != null) append(" (launcher already exited code=$exit)")
                append("; see ")
                append(launcherLog.absolutePath)
            }
            Ln.e("PrivilegedSession: $detail", e)
            teardownConnection()
            return update { it.copy(state = PrivilegeState.DEAD, detail = detail) }
        }

        // hello：协议要求第一帧。携带本进程 pid 供特权进程起看门狗。
        val hello = try {
            conn.request(
                cmd = Cmd.HELLO,
                params = RemoteConnector.helloParams(token, Process.myPid(), RemoteProtocol.VERSION),
                timeoutMs = timeoutMs,
            )
        } catch (e: Throwable) {
            Ln.e("PrivilegedSession: hello failed", e)
            teardownConnection()
            return update { it.copy(state = PrivilegeState.DEAD, detail = "hello failed: ${e.message}") }
        }
        if (hello.ok != true) {
            val err = hello.error
            Ln.e("PrivilegedSession: hello rejected: ${err?.code} ${err?.message}")
            teardownConnection()
            return update {
                it.copy(
                    state = PrivilegeState.DEAD,
                    detail = "hello rejected: ${err?.code ?: "?"} ${err?.message ?: ""}",
                )
            }
        }

        Ln.i("PrivilegedSession: $kind ready, remote uid=${hello.result?.get("uid")} pid=${hello.result?.get("pid")}")
        startHeartbeat()
        return update {
            it.copy(
                state = PrivilegeState.READY,
                availability = PrivilegeAvailability.Ready,
                detail = "remote pid=${hello.result?.get("pid")} uid=${hello.result?.get("uid")}",
            )
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val params = buildJsonObject { put("appPid", Process.myPid()) }
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                val conn = connector ?: return@launch
                try {
                    conn.notify(Cmd.HEARTBEAT, params)
                } catch (e: IOException) {
                    Ln.w("PrivilegedSession: heartbeat failed: ${e.message}")
                    return@launch
                }
            }
        }
    }

    // ------------------------------------------------------------------ 请求

    /**
     * 发请求并返回 `result`。`ok=false` 抛 [RemoteRequestException]。
     *
     * **可并发**：多路复用由 [RemoteConnector] 的 id 派发保证，
     * 长任务在跑的时候调用 [exec] 不会被排队（见 `RemoteServer` 的 worker pool）。
     */
    suspend fun exec(cmd: String, params: JsonObject? = null, timeoutMs: Long = RemoteProtocol.REQUEST_TIMEOUT_MS): JsonObject {
        val frame = execFrame(cmd, params, timeoutMs)
        return frame.result ?: JsonObject(emptyMap())
    }

    /** 同 [exec]，但把整帧给你（需要读 `error`/`event` 时用）。 */
    suspend fun execFrame(cmd: String, params: JsonObject? = null, timeoutMs: Long = RemoteProtocol.REQUEST_TIMEOUT_MS): RemoteFrame {
        val conn = connector ?: throw RemoteRequestException(RemoteProtocol.ErrorCode.NOT_READY, "session not started")
        val frame = conn.request(cmd, params, timeoutMs)
        if (frame.ok != true) {
            val err = frame.error
            throw RemoteRequestException(
                err?.code ?: RemoteProtocol.ErrorCode.INTERNAL,
                err?.message ?: "command '$cmd' failed",
                err?.detail,
            )
        }
        return frame
    }

    /** 单向通知，不等响应（`heartbeat` / `capture.preview`）。 */
    fun notify(cmd: String, params: JsonObject? = null) {
        connector?.notify(cmd, params)
    }

    /** 收事件并只挑某一类（例如 `Event.FRAME`）。返回的 Job 取消即停止订阅。 */
    fun collectEvents(
        scope: CoroutineScope,
        filter: String? = null,
        onEvent: (RemoteFrame) -> Unit,
    ): Job = events
        .onEach { if (filter == null || it.event == filter) onEvent(it) }
        .launchIn(scope)

    /** 当前连接是否活着。root 后端拿不到句柄时这里只反映 socket。 */
    val isConnected: Boolean get() = connector?.isConnected == true

    /** 后端进程是否还在（Shizuku 能查，root 恒为 true）。 */
    val isProcessAlive: Boolean get() = handle?.isAlive() ?: true

    // ------------------------------------------------------------------ 停止

    /** 优雅停机：发 `shutdown` → 关 socket → 兜底 `kill pidof`。幂等。 */
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val conn = connector
        if (conn != null && conn.isConnected) {
            // 只发不等：远端收到后会回 bye 事件并自杀；本方法的调用方不该被停机阻塞。
            runCatching { conn.notify(Cmd.SHUTDOWN) }
        }
        teardownConnection()
        invocation?.let { inv -> runCatching { backend.killResidual(inv.processName) } }
        invocation = null
        update { it.copy(state = PrivilegeState.IDLE, detail = "stopped") }
    }

    override fun close() {
        stop()
        scope.cancel()
        backend.close()
    }

    private fun teardownConnection() {
        eventForwardJob?.cancel()
        eventForwardJob = null
        connector?.close()
        connector = null
        handle = null
        // 槽位必须摘掉：不然「等待管道」的 deferred 会一直挂在注册表里，
        // 之后某次迟到的 attach 会被它接住、写出两个没人读的 FD。
        // 已被 connector 接管的槽位在这里只是被移除，不会重复关闭 FD。
        BootstrapRegistry.unregister(token)
    }

    /** 后端的真实 uid：root 后端恒 0，Shizuku 后端是 Shizuku 服务自己的 uid（0=root，2000=shell）。 */
    private fun backendUid(): Int = when (kind) {
        PrivilegeKind.ROOT -> 0
        PrivilegeKind.SHIZUKU -> runCatching { rikka.shizuku.Shizuku.getUid() }.getOrDefault(-1)
    }

    private fun backendRunsAsRoot(): Boolean = backendUid() == 0

    private fun describe(a: PrivilegeAvailability): String = when (a) {
        is PrivilegeAvailability.Ready -> "ready"
        is PrivilegeAvailability.PermissionRequired -> "permission required"
        is PrivilegeAvailability.Unsupported -> "${kind.label} 不可用"
        is PrivilegeAvailability.Denied -> "denied: ${a.reason}"
    }

    private fun update(transform: (PrivilegeStatus) -> PrivilegeStatus): PrivilegeStatus {
        val next = transform(_status.value)
        _status.value = next
        return next
    }

    companion object {
        /** 与 `RemoteServiceImpl` 的 5s 心跳对齐；只用于让对端判断本进程是否还活着。 */
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        private const val SHUTDOWN_TIMEOUT_MS = 1_500L

        /** 每次会话一个一次性 token：socket 名不可预测，旧会话的 socket 也不会被误连。 */
        fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

        /**
         * 便捷入口：挑一个当前就绪的后端建会话。
         * 顺序 root → Shizuku（`PrivilegeBackends.preferred`）。
         */
        fun preferred(context: Context, debug: Boolean = false): PrivilegedSession? =
            PrivilegeBackends.preferred(context)?.let { PrivilegedSession(context, it, debug = debug) }
    }
}
