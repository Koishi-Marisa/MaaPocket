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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
 * 「起进程 → 连 LocalSocket → `hello` 鉴权」。
 *
 * ## 一次完整启动
 * 1. [PrivilegeBackend.availability] 判定后端是否就绪（Shizuku 权限 / root）；
 * 2. 生成一次性 [token]，[ProcessSpawner.build] 拼出 `liblauncher.so` 命令行（socket 名 = `maapocket.<token>`）；
 * 3. 后端 `spawn`：Shizuku 走 `IShizukuService.newProcess`，root 走 `libsu Shell`；
 * 4. [RemoteConnector.connect] 在 [RemoteProtocol.CONNECT_TIMEOUT_MS] 内重试连接——
 *    特权进程从 fork 到 ART 起 main 通常要几百毫秒，第一次必然失败；
 * 5. 发 `hello`（连同本进程 pid），成功后进 [PrivilegeState.READY]，并每
 *    [HEARTBEAT_INTERVAL_MS] 发一次 `heartbeat`。
 *
 * ## 为什么 socket 名带 token
 * Abstract namespace 是全局的：如果名字固定，任何 app 都能猜到并抢注，
 * 或者 app 自己重启后把旧特权进程的 socket 当成自己的。token 每会话随机，
 * 旧进程因为 `hello` 永远等不到匹配的 token（它的 socket 名不同，也没人来连）
 * 会由自己的看门狗收尸。
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

    /** 特权进程推来的事件；未连接时是空流。 */
    val events: Flow<RemoteFrame>
        get() = connector?.events ?: emptyFlow()

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
            // 需求 5：Android 14+ 起注入输入要求 uid 0。Shizuku 以 shell(2000) 跑时，
            // 连上去也点不动游戏。这里不阻断启动（截图/其它命令仍然可用），但明确告警。
            Ln.w(
                "PrivilegedSession: SDK ${android.os.Build.VERSION.SDK_INT} 需要 root uid 才能注入输入，" +
                    "但 $kind 后端不以 root 运行（uid=${backendUid()}）；input.* 会失败",
            )
        }

        val inv = ProcessSpawner.build(
            context = appContext,
            suffix = kind.processSuffix,
            token = token,
            logFileName = kind.logFileName,
            debug = debug,
        )
        invocation = inv

        update {
            it.copy(
                state = PrivilegeState.SPAWNING,
                availability = availability,
                runsAsRoot = runsAsRoot,
                uid = backendUid(),
                detail = "launcher=${inv.launcherPath} socket=${inv.socketName}",
            )
        }

        handle = try {
            backend.spawn(inv)
        } catch (e: Throwable) {
            Ln.e("PrivilegedSession: spawn failed for $kind", e)
            return update { it.copy(state = PrivilegeState.DEAD, detail = "spawn failed: ${e.message}") }
        }

        update { it.copy(state = PrivilegeState.CONNECTING) }
        val conn = RemoteConnector(inv.socketName, token)
        connector = conn
        conn.onClosed = { cause ->
            // 连上之后断的（app 主动 close 不会走到这里）。
            update {
                it.copy(
                    state = PrivilegeState.DEAD,
                    detail = cause?.let { c -> "socket closed: ${c.javaClass.simpleName}: ${c.message}" } ?: "socket closed",
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
                append(java.io.File(appContext.applicationInfo.dataDir, "debug/${kind.logFileName}"))
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
        connector?.close()
        connector = null
        handle = null
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
