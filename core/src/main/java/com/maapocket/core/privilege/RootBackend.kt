package com.maapocket.core.privilege

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.maapocket.core.third.Ln
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * root 后端（`com.github.topjohnwu.libsu:core` 6.0.0）。
 *
 * ## 发射命令必须后台执行
 *
 * ```
 * <raw> >/dev/null 2>&1 &
 * ```
 *
 * `&` 不是可选优化。libsu 的 `Shell.cmd(...).exec()` 是**同步**的：前台跑一条不会自己退出的
 * 命令会把整条 libsu shell 卡死，后续所有 root 操作（包括兜底 kill）都排不上队。
 * 代价是拿不到 launcher 的退出码 —— launcher 其实会 fork 并 waitpid，父进程会一直挂着，
 * 所以前台等本来也等不到。早期失败只能靠 [RemoteConnector] 的连接超时发现。
 * 这与 MAA-Meow `SuSpawner.kt` 的做法一致。
 *
 * ## 权限模型
 *
 * 与 MAA-Meow `RootManager.kt` 一致：
 * - `Shell.isAppGrantedRoot() == true`（结果可能为 null，表示“还没问过”）；
 * - 或者已经有缓存的 root shell：`Shell.getCachedShell()?.isRoot == true`；
 * - 两者都不成立时再看 PATH 里有没有可执行的 `su`：有 → 还没授权；没有 → 设备没 root。
 *
 * 注意 manifest 里必须有 `com.topjohnwu.superuser.permission.REQUEST`，否则授权请求直接失败，
 * 这里看不到任何异常。那份 manifest 不归本文件管（见需求 9）。
 */
class RootBackend(private val context: Context) : PrivilegeBackend {

    override val kind: PrivilegeKind = PrivilegeKind.ROOT

    private val appContext = context.applicationContext

    init {
        // 只配置一次。libsu 的默认 builder 是全局静态状态，重复设会丢掉已缓存的 shell。
        if (!configured) {
            synchronized(RootBackend::class.java) {
                if (!configured) {
                    Shell.enableVerboseLogging = appContext.isDebuggable()
                    Shell.setDefaultBuilder(
                        Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR)
                    )
                    configured = true
                }
            }
        }
    }

    // ------------------------------------------------------------------ 探测

    override fun availability(): PrivilegeAvailability {
        val granted = runCatching {
            Shell.isAppGrantedRoot() == true || Shell.getCachedShell()?.isRoot == true
        }.getOrElse { e ->
            Ln.w("RootBackend: isAppGrantedRoot failed", e)
            false
        }
        if (granted) return PrivilegeAvailability.Ready

        return if (suInPath() != null) PrivilegeAvailability.PermissionRequired
        else PrivilegeAvailability.Unsupported
    }

    // ------------------------------------------------------------------ 授权

    override fun requestPermission(
        timeoutMs: Long,
        onResult: (PrivilegeAvailability) -> Unit,
    ) {
        if (availability().isReady) {
            onResult(PrivilegeAvailability.Ready)
            return
        }
        // `Shell.getShell()` 会阻塞直到拿到 shell（可能弹 Magisk 授权框），所以扔到后台线程，
        // 由调用线程带超时等结果 —— 与 MAA-Meow `RootManager.request()` 用协程包裹的语义相同。
        val result = AtomicReference<PrivilegeAvailability>(PrivilegeAvailability.PermissionRequired)
        val latch = CountDownLatch(1)
        Thread({
            try {
                val shell = Shell.getShell()
                result.set(
                    if (shell != null && shell.isRoot) PrivilegeAvailability.Ready
                    else PrivilegeAvailability.Denied("su returned a non-root shell")
                )
            } catch (e: Throwable) {
                result.set(PrivilegeAvailability.Denied(e.message ?: e.javaClass.simpleName))
            } finally {
                latch.countDown()
            }
        }, "maapocket-root-request").apply { isDaemon = true }.start()

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            onResult(PrivilegeAvailability.Denied("root request timed out after ${timeoutMs}ms"))
            return
        }
        onResult(result.get())
    }

    // ------------------------------------------------------------------ 发射

    /** 返回 null：见类 KDoc，后台执行后 libsu 无法再观察这个进程（需求：句柄可以为空）。 */
    override fun spawn(invocation: LauncherInvocation): SpawnHandle? {
        val command = wrapCommand(invocation)
        val result = Shell.cmd(command).exec()
        check(result.code == 0) {
            "libsu rejected spawn (code=${result.code}): ${result.err.joinToString("\n")}"
        }
        Ln.i("RootBackend: spawned ${invocation.processName} via libsu (background)")
        return null
    }

    fun wrapCommand(invocation: LauncherInvocation): String =
        "mkdir -p ${ProcessSpawner.shellQuote(invocation.logDir)} 2>/dev/null; " +
            "${invocation.rawCommand} >/dev/null 2>&1 &"

    override fun killResidual(processName: String) {
        val result = Shell.cmd(ProcessSpawner.killByNameCommand(processName)).exec()
        Ln.i("RootBackend: killResidual($processName) code=${result.code}")
    }

    // ------------------------------------------------------------------ 工具

    /** PATH 里第一个可执行的 `su`；没有就返回 null。 */
    private fun suInPath(): File? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val candidate = File(dir, "su")
            if (candidate.isFile && candidate.canExecute()) return candidate
        }
        return null
    }

    private fun Context.isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    companion object {
        /** libsu 全局 builder 只配一次；见 init。 */
        @Volatile
        private var configured = false

        /** Android 14+ 注入必须 root；仅用于日志/诊断。 */
        val needsRootForInput: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
