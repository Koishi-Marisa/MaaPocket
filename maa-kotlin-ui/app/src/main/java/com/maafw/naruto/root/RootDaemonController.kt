package com.maafw.naruto.root

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Root 守护进程控制器（App 侧）～
 * 负责启动 / 停止 / 检查常驻 root 守护进程（RootDaemon）。
 * 启动命令与 RootRemoteServiceConnector 一致：su + CLASSPATH + app_process。
 */
object RootDaemonController {

    private const val TAG = "RootDaemonController"
    /** pkill 锚定命令行开头，避免误杀 su shell 自身（与 RootServiceStarter 清理同一套路） */
    private const val DAEMON_PATTERN = "^app_process /system/bin com\\.maafw\\.naruto\\.root\\.RootDaemon"

    /**
     * 启动 Root 守护进程（su 拉起，常驻 root）。
     * @return true=命令已下发成功（进程是否存活以 [isRunning] 为准）
     */
    fun start(context: Context): Boolean {
        if (!RootManager.isRootGranted()) {
            Log.w(TAG, "无 root 权限，无法启动守护进程")
            return false
        }
        if (isRunning()) {
            Log.i(TAG, "守护进程已在运行")
            return true
        }
        val apkPath = context.applicationInfo.sourceDir
        val pkg = context.packageName
        val uid = Process.myUid()
        val logFile = "/data/local/tmp/maafw_root_daemon.log"
        val cmd = buildString {
            append("CLASSPATH='").append(apkPath).append("' ")
            append("app_process /system/bin ")
            append(RootDaemon.PROCESS_TAG)
            append(" --package=").append(pkg)
            append(" --uid=").append(uid)
            append(" --debug-name=").append("$pkg:root_daemon")
            append(" >$logFile 2>&1 &")
        }
        Log.i(TAG, "启动 Root 守护进程: $cmd")
        val exit = runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        }.getOrDefault(-1)
        if (exit != 0) {
            Log.w(TAG, "启动 Root 守护进程失败，exitCode=$exit")
            return false
        }
        // 等待进程起来（最多 5s）
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                Log.i(TAG, "Root 守护进程已就绪")
                return true
            }
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                break
            }
        }
        Log.w(TAG, "等待 Root 守护进程启动超时（可查看 $logFile）")
        return false
    }

    /** 停止 Root 守护进程 */
    fun stop() {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -9 -f '$DAEMON_PATTERN'")).waitFor()
        }.onFailure { Log.w(TAG, "停止 Root 守护进程失败: ${it.message}") }
    }

    /** 守护进程是否存活（pgrep -f 匹配完整命令行，ps -A 只显示进程名 app_process 会漏判） */
    fun isRunning(): Boolean {
        return runCatching {
            // 锚定 app_process 开头，避免匹配到 su shell 自身
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "pgrep -f '$DAEMON_PATTERN'"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.isNotEmpty()
        }.getOrDefault(false)
    }

    /** 日志文件路径（排查启动失败用） */
    fun logFile(): String = "/data/local/tmp/maafw_root_daemon.log"
}