package com.maapocket.core.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.maapocket.core.third.Ln
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku 后端（`dev.rikka.shizuku:api` 13.1.5）。
 *
 * ## 为什么 spawn 不能直接用 `Shizuku.newProcess`
 *
 * `Shizuku.newProcess(String[], String[], String)` 从 Shizuku API 14 起被标记移除、现在已是
 * private，所以这里直接走它背后的 AIDL：`Shizuku.getBinder()` → `IShizukuService.Stub.asInterface`
 * → `newProcess(...)`，拿到 `moe.shizuku.server.IRemoteProcess` 自己包一层句柄。
 * 这段与 MAA-Meow `ShizukuSpawner.kt:15` 的注释和做法完全一致。
 *
 * ## 命令包装
 *
 * ```
 * mkdir -p '<logDir>' 2>/dev/null; test -x '<liblauncher.so>' || exit 126; exec <raw> </dev/null >/dev/null 2>&1
 * ```
 *
 * - `mkdir` 必须在 `exec` **之前**单独成句。`exec` 会把 shell 替换成紧随其后的那一条命令，
 *   所以任何前置命令一旦被写进 `<raw>` 就会顶替掉 launcher —— 见
 *   [LauncherInvocation.rawCommand] 里那段真机踩坑记录。
 * - `test -x` 是必须的：如果 `.so` 没有可执行位，`exec` 会失败但 `sh` 会退化成
 *   “继续执行后面的东西”或者直接卡住，而 `exit 126` 让失败立刻可见。
 * - `</dev/null >/dev/null 2>&1` 把 stdio 摘掉：服务进程的 stdout 一旦没人读，
 *   管道写满就会把服务进程卡死在日志上。日志只能走 `--log-file`（launcher 已经把
 *   stderr dup2 到该文件）。
 *
 * ## 权限模型
 *
 * 与 MAA-Meow `ShizukuManager.kt` 一致：
 * - `Shizuku.pingBinder()` 判断 binder 存活；
 * - `Shizuku.checkSelfPermission() == PERMISSION_GRANTED` 判断已授权；
 * - `Shizuku.requestPermission(code)` + `OnRequestPermissionResultListener` 请求；
 * - `Shizuku.getUid() == 0` 说明 Shizuku 本身以 root 运行（此时 `newProcess` 出来就是 root，
 *   Android 14+ 的 input 注入也能成）；
 * - `rikka.sui` 的 `Sui.init(packageName)` 由上层在 Application 里调，不在这里做（需要在
 *   主进程早期执行）。
 */
class ShizukuBackend(private val context: Context) : PrivilegeBackend {

    override val kind: PrivilegeKind = PrivilegeKind.SHIZUKU

    private val appContext = context.applicationContext

    // ------------------------------------------------------------------ 探测

    override fun availability(): PrivilegeAvailability {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            // binder 不在：要么没装/没启动 Shizuku，要么还没给本 app 授权。
            // 用包名区分这两种情况，避免把“没授权”误判成“不支持”从而藏起授权入口。
            return if (isShizukuInstalled()) PrivilegeAvailability.PermissionRequired
            else PrivilegeAvailability.Unsupported
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            return PrivilegeAvailability.Denied("Shizuku pre-v11 is not supported")
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrElse { e ->
            Ln.w("ShizukuBackend: checkSelfPermission failed", e)
            false
        }
        return if (granted) PrivilegeAvailability.Ready else PrivilegeAvailability.PermissionRequired
    }

    /** MAA-Meow `ShizukuDefaults.OFFICIAL_SHIZUKU_PACKAGE`。Sui / 其它实现也够用：只要包名对不上就当没装。 */
    private fun isShizukuInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(OFFICIAL_SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shizuku 以 root 运行时 `newProcess` 出来的进程是 root；否则是 shell(2000)。 */
    val runsAsRoot: Boolean
        get() = runCatching { Shizuku.getUid() == 0 }.getOrDefault(false)

    val version: Int
        get() = runCatching { Shizuku.getVersion() }.getOrDefault(-1)

    // ------------------------------------------------------------------ 授权

    override fun requestPermission(
        timeoutMs: Long,
        onResult: (PrivilegeAvailability) -> Unit,
    ) {
        if (availability().isReady) {
            onResult(PrivilegeAvailability.Ready)
            return
        }
        val requestCode = REQUEST_CODE
        val settled = CountDownLatch(1)
        val outcome = AtomicReference<PrivilegeAvailability>(PrivilegeAvailability.PermissionRequired)

        val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == requestCode) {
                outcome.set(
                    if (result == PackageManager.PERMISSION_GRANTED) PrivilegeAvailability.Ready
                    else PrivilegeAvailability.Denied("request denied by user")
                )
                settled.countDown()
            }
        }

        // 请求弹窗是异步的（Shizuku 会把用户的操作回传到监听器），所以这里用 latch 等。
        // 但是**不能在主线程上等**，否则弹窗自己的 UI 事件排不进来 —— 上层必须从后台线程调。
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(requestCode)
            if (!settled.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                outcome.set(PrivilegeAvailability.Denied("request permission timed out after ${timeoutMs}ms"))
            }
        } catch (e: Throwable) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            outcome.set(PrivilegeAvailability.Denied(e.message ?: e.javaClass.simpleName))
        } finally {
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
        }
        onResult(outcome.get())
    }

    // ------------------------------------------------------------------ 发射

    override fun spawn(invocation: LauncherInvocation): SpawnHandle {
        val command = wrapCommand(invocation)

        val server = requireServer()
        val process = server.newProcess(arrayOf("sh", "-c", command), null, null)
            ?: error("IShizukuService.newProcess returned null")
        Ln.i("ShizukuBackend: spawned ${invocation.processName} via shizuku")
        return RemoteProcessHandle(process)
    }

    /** 见类 KDoc。public 是为了让单元测试/日志能直接看到最终命令行。 */
    fun wrapCommand(invocation: LauncherInvocation): String =
        // `mkdir` 必须在 `exec` **之前**、作为独立的一条命令。写成 `exec mkdir ...; launcher ...`
        // 会把 shell 替换成 mkdir —— 它建完目录就退出，launcher 根本不会被执行，
        // 上层只会看到 "launcher already exited code=0"。这个坑真机上踩过一次，见 LauncherInvocation.rawCommand。
        "mkdir -p ${ProcessSpawner.shellQuote(invocation.logDir)} 2>/dev/null; " +
            "test -x ${ProcessSpawner.shellQuote(invocation.launcherPath)} || exit 126; " +
            "exec ${invocation.rawCommand} </dev/null >/dev/null 2>&1"

    private fun requireServer(): IShizukuService {
        val binder = Shizuku.getBinder() ?: error("Shizuku binder unavailable")
        return IShizukuService.Stub.asInterface(binder)
            ?: error("IShizukuService.asInterface returned null")
    }

    override fun killResidual(processName: String) {
        // 残留进程一般是上一次会话被 SIGKILL 后剩下的。走 Shizuku 再起一个 sh 更稳，
        // 因为 app 自己 uid 不够（服务进程可能是 shell 或 root）。
        runCatching {
            requireServer().newProcess(
                arrayOf("sh", "-c", ProcessSpawner.killByNameCommand(processName) + " ; true"),
                null,
                null,
            )
            Ln.i("ShizukuBackend: killResidual($processName) submitted")
        }.onFailure { Ln.w("ShizukuBackend: killResidual($processName) failed", it) }
    }

    // ------------------------------------------------------------------ 监听

    /**
     * 注册 binder 到达/死亡监听。需要一份“随时知道 Shizuku 是否可用”的 UI 时用。
     * 返回的对象 close 掉即注销。
     */
    fun observe(onChange: (PrivilegeAvailability) -> Unit): AutoCloseable {
        val received = Shizuku.OnBinderReceivedListener {
            runCatching { onChange(availability()) }
        }
        val dead = Shizuku.OnBinderDeadListener {
            onChange(PrivilegeAvailability.Unsupported)
        }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        return AutoCloseable {
            runCatching { Shizuku.removeBinderReceivedListener(received) }
            runCatching { Shizuku.removeBinderDeadListener(dead) }
        }
    }

    override fun close() = Unit

    /**
     * `IRemoteProcess` 的薄包装。
     *
     * `isAlive()` 失败时**保守地当作还活着**：binder 抖动不代表进程死了，误判“已死”
     * 会让上层去杀一个正常进程。退出码拿不到就返回 null。
     */
    class RemoteProcessHandle(private val process: IRemoteProcess) : SpawnHandle {
        override fun isAlive(): Boolean =
            runCatching { process.alive() }.getOrDefault(true)

        override fun exitCode(): Int? =
            runCatching { process.exitValue() }.getOrNull()
    }

    companion object {
        const val OFFICIAL_SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /**
         * 请求码。MAA-Meow 用固定值 42（ShizukuManager.kt 里的 `requestCode`），同一个
         * 进程里同时只允许一个待决请求，固定值没问题。
         */
        const val REQUEST_CODE = 42

        /** 判断当前 API 级别下 Shizuku 是否是唯一可行后端（仅日志/诊断用）。 */
        val needsRootForInput: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
