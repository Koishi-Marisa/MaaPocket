package com.maapocket.core.privilege

import android.content.Context
import com.maapocket.core.third.Ln

/**
 * 特权后端的种类。
 *
 * [processSuffix] 拼成特权进程的 `--process-name`（`"<app 包名>:<suffix>"`），
 * [logFileName] 是 `--log-file` 的文件名。
 *
 * **两个后端的日志文件名必须不同**：launcher.c 打开日志用的是
 * `O_WRONLY|O_CREAT|O_TRUNC`，同名会让后启动的那个把前一个的日志清空；而排查
 * “root 能用 / Shizuku 不能用”这类问题时，恰恰两份日志都要留。
 */
enum class PrivilegeKind(
    val label: String,
    val processSuffix: String,
    val logFileName: String,
) {
    SHIZUKU("shizuku", "shizuku_service", "maapocket-shizuku-launcher.log"),
    ROOT("root", "root_service", "maapocket-root-launcher.log"),
    ;

    companion object {
        fun fromLabel(label: String?): PrivilegeKind? =
            values().firstOrNull { it.label.equals(label, ignoreCase = true) }
    }
}

/**
 * 后端“能不能用”。**只描述事实，不做任何副作用**——[PrivilegeBackends.availability] 可以
 * 随便调，不会弹窗、不会 spawn。
 *
 * - [Ready]：binder 活着 + 权限已授予（Shizuku），或者 libsu 拿到 root shell（root）。
 * - [PermissionRequired]：后端存在但用户还没授权（Shizuku 未在 Shizuku app 里勾选；
 *   root 未在 Magisk/KernelSU 里授权）。这一档才允许调 `requestPermission()`。
 * - [Unsupported]：设备上根本没有这个后端（Shizuku binder 从未出现；`su` 不存在）。
 * - [Denied]：存在但明确被拒绝 / 初始化失败，`reason` 只用于日志。
 */
sealed interface PrivilegeAvailability {
    object Ready : PrivilegeAvailability
    object PermissionRequired : PrivilegeAvailability
    object Unsupported : PrivilegeAvailability
    data class Denied(val reason: String) : PrivilegeAvailability

    val isReady: Boolean get() = this is Ready
}

/**
 * 会话生命周期。与 [PrivilegeAvailability] 正交：availability 说的是“后端能不能用”，
 * state 说的是“我们这一轮跑到哪了”。
 */
enum class PrivilegeState {
    IDLE,
    CHECKING,
    PERMISSION_REQUIRED,
    SPAWNING,
    CONNECTING,
    READY,
    DEAD,
}

/** 一次面向 UI 的状态快照。`runsAsRoot` 决定 Android 14+ 的 input 注入能不能成。 */
data class PrivilegeStatus(
    val kind: PrivilegeKind? = null,
    val state: PrivilegeState = PrivilegeState.IDLE,
    val availability: PrivilegeAvailability = PrivilegeAvailability.Unsupported,
    val runsAsRoot: Boolean = false,
    val uid: Int = -1,
    val detail: String? = null,
)

/**
 * 一个特权后端：负责“把 liblauncher.so 拉起来”和“兜底清理残留进程”。
 *
 * 后端的职责边界刻意画得很窄：**只管 spawn，不管 socket**。socket 的连接/重连/协议
 * 全部在 [RemoteConnector] 里，这样后端只要换掉 [spawn] 就能替换，协议层完全不用动。
 * （MAA-Meow 的 `ProcessServiceConnectorBackend` 把 spawn + binder 等待 + 保活揉在
 * 一个 279 行的类里，这里是刻意的拆分。）
 */
interface PrivilegeBackend {

    val kind: PrivilegeKind

    /** 无副作用地探测可用性。 */
    fun availability(): PrivilegeAvailability

    /**
     * 请求授权。Shizuku 会弹系统弹窗，root 会弹 `su` 授权。
     * [onResult] 一定会被回调一次（超时回调 [PrivilegeAvailability.Denied]）。
     */
    fun requestPermission(timeoutMs: Long = 15_000L, onResult: (PrivilegeAvailability) -> Unit)

    /**
     * 发射 launcher。
     *
     * 返回 null 表示“命令已提交，但拿不到句柄”（root 分支：libsu 必须后台执行，
     * 见 [RootBackend.spawn]）。此时只能用 [RemoteConnector] 的连接超时来判断失败。
     */
    fun spawn(invocation: LauncherInvocation): SpawnHandle?

    /** 杀残留：`kill $(pidof <processName>)`。用于上一次会话崩溃后没来得及退出的服务进程。 */
    fun killResidual(processName: String)

    /** 释放监听器（Shizuku 的 binder 监听）。进程生命周期内一般只调一次。 */
    fun close() {}
}

/**
 * 后端注册表。后端对象会持有监听器，因此**按 kind 缓存**，别每次 new。
 *
 * 优先级：root 优于 Shizuku。理由——Android 14+ input 注入要求 root uid，
 * Shizuku 走 adb(shell) 时即便 `newProcess` 也要降权，注入会失败（需求 5）。
 */
object PrivilegeBackends {

    private val cache = HashMap<PrivilegeKind, PrivilegeBackend>()

    @Synchronized
    fun of(context: Context, kind: PrivilegeKind): PrivilegeBackend {
        val app = context.applicationContext
        return cache.getOrPut(kind) {
            when (kind) {
                PrivilegeKind.SHIZUKU -> ShizukuBackend(app)
                PrivilegeKind.ROOT -> RootBackend(app)
            }
        }
    }

    /** 无副作用地探测所有后端，按优先级排序。 */
    fun availability(context: Context): List<Pair<PrivilegeKind, PrivilegeAvailability>> {
        val order = listOf(PrivilegeKind.ROOT, PrivilegeKind.SHIZUKU)
        return order.map { kind ->
            val availability = runCatching { of(context, kind).availability() }
                .getOrElse { e ->
                    Ln.w("PrivilegeBackends: availability($kind) threw", e)
                    PrivilegeAvailability.Denied(e.message ?: e.javaClass.simpleName)
                }
            kind to availability
        }
    }

    /** 第一个可用的后端；优先 [PrivilegeKind.ROOT]。全都不可用返回 null。 */
    fun preferred(context: Context): PrivilegeKind? =
        availability(context).firstOrNull { it.second.isReady }?.first

    @Synchronized
    fun closeAll() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }
}
