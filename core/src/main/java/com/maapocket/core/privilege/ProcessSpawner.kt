package com.maapocket.core.privilege

import android.content.Context
import android.os.Build
import com.maapocket.core.third.Ln
import java.io.File

/**
 * 一次 spawn 的句柄。
 *
 * 注意：**root（libsu）后端永远返回 null**，因为 libsu 的发射命令必须在后台跑
 * （`... &`），前台跑会把整条 libsu shell 卡死。代价是拿不到 launcher 的退出码，
 * 早期失败只能靠“连不上 socket”的超时来发现。见 [RootBackend.spawn]。
 */
interface SpawnHandle {
    fun isAlive(): Boolean

    /** 进程已退出时返回退出码；还在跑或拿不到时返回 null（Shizuku 的 IRemoteProcess 可能抛异常）。 */
    fun exitCode(): Int?
}

/** launcher 在服务进程真正起来之前就退出了（例如 native 库不存在、workdir 不可写、uid 非法）。 */
class ProcessExitedException(val exitCode: Int?) :
    IllegalStateException("launcher exited early code=${exitCode ?: "?"}")

/**
 * 一次完整的 launcher 调用描述。[rawCommand] 是**未经后端包装**的命令行；
 * Shizuku 后端要加 `test -x ... || exit 126; exec ...`，root 后端要加 `... &`。
 */
data class LauncherInvocation(
    val launcherPath: String,
    /** `进程名 = "<app 包名>:<suffix>"`，用于 app_process 的 `--nice-name=`，也用于兜底 kill。 */
    val processName: String,
    val token: String,
    /** 特权进程据此创建/连接 LocalSocket；由 token 推导，双方无需额外握手。 */
    val socketName: String,
    /**
     * 未包装的**纯 launcher 命令行**，第一个词就是 `liblauncher.so` 的路径。
     *
     * ⚠️ 这里**绝对不能**再拼任何前置命令（尤其 `mkdir -p ... ;`）。Shizuku 后端的包装是
     * `... ; exec <rawCommand> </dev/null >/dev/null 2>&1`，`exec` 会把 shell **整体替换成它后面
     * 那一条命令**：一旦 rawCommand 以 `mkdir` 开头，被替换掉的就是 `mkdir`，它建完目录就以 0
     * 退出，launcher 一个字节都没跑。真机上的表现极具误导性 ——
     * `connect failed: ... (launcher already exited code=0)`，且 `--log-file` 指向的目录存在但为空。
     * 需要的前置准备工作请放进 [logDir]（由后端包在 `exec` 之前）。
     */
    val rawCommand: String,
    /** 需要 `mkdir -p` 出来的目录（launcher 日志的父目录）；由**后端**在 `exec` 之前建好。 */
    val logDir: String,
    /** 是否要求特权进程保持 root（Android 14+ 注入需要，见 [keepRootForInputInjection]）。 */
    val keepRoot: Boolean,
)

/**
 * 生成 `liblauncher.so` 的命令行。
 *
 * ## liblauncher.so 的完整命令行契约（逐字抄自 `core/src/main/cpp/launcher.c`）
 *
 * `parse_args()`（launcher.c:101-129）解析下面这些参数，**除 `--debug-name` / `--log-file` /
 * `--keep-root` 外全部必填**，任何一个缺失或 `--uid` 解析失败都会直接 `return false` → launcher 退出：
 *
 * ```
 * --apk=<path>            必填。APK（base.apk）路径；launcher 用它 setenv("CLASSPATH", apk, 1)
 * --process-name=<name>   必填。app_process 的 --nice-name=<name>；也用于兜底 kill 残留进程
 * --starter-class=<fqcn>  必填。app_process 要跑的 Java 入口类的全限定名
 * --token=<str>           必填。透传给入口类的 argv，用于“同一 token 只有一个服务进程”
 * --package=<pkg>         必填。宿主 app 包名，透传给入口类
 * --class=<fqcn>          必填。服务类全限定名，透传给入口类
 * --uid=<int>             必填，必须 >= 0。透传给入口类
 * --debug-name=<name>     可选。有值时作为 `--debug-name=` 透传
 * --log-file=<path>       可选。launcher 以 root 身份 open(O_WRONLY|O_CREAT|O_TRUNC, 0644)
 *                         并 fchmod 0644，随后把 stderr dup2 到该 fd；日志同时走 logcat
 *                         （TAG "RootLauncher"）。因为用了 O_TRUNC，**两个后端必须用不同的
 *                         文件名**，否则后启动的会把前一个的日志清空
 * --keep-root             可选开关（无 `=`）。不设时：若 getuid()==2000 则跳过降权，
 *                         否则 setgroups(24 个 shell gid) + setresgid(2000) + setresuid(2000)；
 *                         设了则完全跳过降权，保持 root
 * ```
 *
 * 最终 `execv` 的 argv（launcher.c:177-186）：
 *
 * ```
 * /system/bin/app_process /system/bin --nice-name=<process-name> <starter-class> \
 *     --token=<token> --package=<package> --class=<class> --uid=<uid> [--debug-name=<name>]
 * ```
 *
 * 因此 [RemoteMain] 的 `main(String[] args)` 收到的是**类名之后的**那几个
 * `--token= / --package= / --class= / --uid= / [--debug-name=]`。
 *
 * ## `--class=` 在本移植里是“必填但不用”的占位
 *
 * MAA-Meow 用 `--class=` 让 `RootUserService` 反射实例化一个绑定式 AIDL Service。
 * 我们改成了 LocalSocket，`RemoteMain` 不需要实例化任何 Service，但 launcher.c 的
 * `parse_args()` 强制要求该参数存在，所以这里塞 [SERVICE_CLASS_PLACEHOLDER]，
 * 由 `RemoteMain` 解析后忽略。
 *
 * ## 与 MAA-Meow 的差异
 *
 * MAA-Meow 的命令拼装在 `ProcessServiceConnectorBackend.buildStartCommand()`
 * （ProcessServiceConnectorBackend.kt:136-157），本对象是它的等价物，但没有 SDK 探测、
 * 没有 binder 回调，多了 [socketName]（协议从 binder 换成 LocalSocket）。
 */
object ProcessSpawner {

    const val LAUNCHER_SO_NAME = "liblauncher.so"

    /** 特权进程的 Java 入口（app_process 的 `<starter-class>`）。 */
    const val STARTER_CLASS = "com.maapocket.core.privilege.RemoteMain"

    /** 见类 KDoc「`--class=` 在本移植里是必填但不用」：占位，`RemoteMain` 会忽略。 */
    const val SERVICE_CLASS_PLACEHOLDER = "com.maapocket.core.privilege.RemoteEngine"

    /** `shellQuote()` 的实现同 MAA-Meow `ProcessSpawner.kt`。 */
    internal fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    /** 残留进程兜底清理；`2>/dev/null` 让“本来就没有”不算失败。 */
    internal fun killByNameCommand(processName: String): String =
        "kill $(pidof ${shellQuote(processName)}) 2>/dev/null"

    /**
     * Android 14（API 34）起 input 注入必须由 root uid 执行，因此 launcher 不能降权到 shell(2000)，
     * 要带 `--keep-root`。
     *
     * 抄自 MAA-Meow `ProcessServiceConnectorBackend.kt:30-32`：
     * `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`。
     */
    val keepRootForInputInjection: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * launcher 必须放在 `nativeLibraryDir` 里（APK 解压出来的 `lib/<abi>/liblauncher.so`）；
     * `exec` 要求文件有可执行位，而 `/data/app/.../lib/<abi>/` 下的 .so 天然带 x 位。
     */
    fun launcherFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, LAUNCHER_SO_NAME)

    /** Shizuku 后端下 launcher 日志的落盘目录：shell(2000) 唯一稳定可写、且 adb 能读的地方。 */
    const val SHIZUKU_LOG_DIR = "/data/local/tmp/maapocket"

    /**
     * launcher **自身**日志（`--log-file`）的落盘位置。
     *
     * 这里有个真机上踩过的坑：launcher 是在 `execv` **之前**、以**宿主身份** `open()` 这个日志文件的。
     * root 后端是 uid 0，写哪儿都行；但 Shizuku 后端是 shell(2000)，而它**写不了** app 私有目录 ——
     * 真机实测 `ls: /data/data/com.maapocket.hsr: Permission denied`，命令里那句
     * `mkdir -p <dataDir>/debug` 同样失败。结果是 launcher 早期失败的**全部原因都看不到**，
     * 界面上只剩一句干巴巴的「特权进程未连上」。
     *
     * 所以按后端分目录：
     * - [PrivilegeKind.ROOT]   → `/data/data/<pkg>/debug/`（app 自己读得到，可以直接显示到界面）
     * - [PrivilegeKind.SHIZUKU] → [SHIZUKU_LOG_DIR]（shell 可写；诊断时 `adb shell cat` 直接看）
     *
     * 注意 [PrivilegeKind.logFileName] 两个后端本来就不同，这也是必需的 —— launcher 用
     * `O_TRUNC` 打开日志，同名会互相清空。
     */
    fun launcherLogFile(context: Context, kind: PrivilegeKind): File {
        val dir = if (kind == PrivilegeKind.ROOT) {
            File(context.applicationInfo.dataDir, "debug")
        } else {
            File(SHIZUKU_LOG_DIR)
        }
        return File(dir, kind.logFileName)
    }

    /**
     * 生成一次 [LauncherInvocation]。
     *
     * @param suffix        进程名后缀（`root_service` / `shizuku_service`），最终进程名为
     *                      `"<app 包名>:<suffix>"`，与 MAA-Meow 的命名保持一致。
     * @param logFile       launcher 自身的日志文件。由 [launcherLogFile] 决定，调用方不要自己拼。
     */
    fun build(
        context: Context,
        suffix: String,
        token: String,
        logFile: File,
        debug: Boolean = false,
    ): LauncherInvocation {
        val launcher = launcherFile(context)
        check(launcher.exists()) {
            "liblauncher.so not found at ${launcher.absolutePath}; " +
                "nativeLibraryDir=${context.applicationInfo.nativeLibraryDir}"
        }

        val processName = "${context.packageName}:$suffix"
        val apkPath = context.applicationInfo.sourceDir

        val keepRoot = keepRootForInputInjection

        val sb = StringBuilder()
        sb.append(shellQuote(launcher.absolutePath))
        sb.append(" --apk=").append(shellQuote(apkPath))
        sb.append(" --process-name=").append(shellQuote(processName))
        sb.append(" --starter-class=").append(shellQuote(STARTER_CLASS))
        sb.append(" --token=").append(shellQuote(token))
        sb.append(" --package=").append(shellQuote(context.packageName))
        sb.append(" --class=").append(shellQuote(SERVICE_CLASS_PLACEHOLDER))
        sb.append(" --uid=").append(shellQuote(context.applicationInfo.uid.toString()))
        if (keepRoot) sb.append(" --keep-root")
        sb.append(" --log-file=").append(shellQuote(logFile.absolutePath))
        if (debug) sb.append(" --debug-name=").append(shellQuote(processName))

        val raw = sb.toString()
        Ln.d(
            "launcher invocation: processName=$processName keepRoot=$keepRoot " +
                "logFile=${logFile.absolutePath} cmd=$raw"
        )

        return LauncherInvocation(
            launcherPath = launcher.absolutePath,
            processName = processName,
            token = token,
            socketName = RemoteProtocol.socketNameFor(token),
            rawCommand = raw,
            logDir = logFile.parentFile?.absolutePath ?: ".",
            keepRoot = keepRoot,
        )
    }
}
