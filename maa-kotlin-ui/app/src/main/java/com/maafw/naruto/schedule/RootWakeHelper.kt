package com.maafw.naruto.schedule

import android.content.Context
import android.util.Log
import com.maafw.naruto.root.RootManager

/**
 * Root 唤醒助手
 * 定时任务触发时若开启「Root 唤醒应用」，以 su 执行系统命令把 App 强拉前台：
 * 1. am start 拉起 MainActivity -> App 进入前台（解除 Android 12+ 后台启动前台服务的限制）；
 * 2. 若 App 进程已被杀，am start 也会由系统重新拉起进程（普通后台清理场景有效）。
 *
 * 注意：被「强制停止」的 App 其闹钟/广播会被系统整体清除，此类极端场景需配合
 * root 常驻守护进程（app_process daemon）才能彻底解决，见项目内方案说明。
 */
object RootWakeHelper {

    private const val TAG = "RootWakeHelper"

    /**
     * 通过 Root 唤醒 App（异步执行，不阻塞调用线程）。
     * su 未授权时可能弹出授权窗口，故整体放入子线程。
     */
    fun wakeApp(context: Context) {
        val pkg = context.packageName
        Thread {
            if (!RootManager.isRootGranted()) {
                Log.w(TAG, "root 未授权，无法通过 Root 唤醒应用")
                return@Thread
            }
            // 主 Activity 类名固定为 .MainActivity（见 AndroidManifest）
            val launcher = "$pkg/.MainActivity"
            val ok = execRoot("am start -n $launcher")
            Log.i(TAG, "通过 Root 拉起应用 ${if (ok) "成功" else "失败"}: $launcher")
        }.start()
    }

    /** 执行一条 su 命令，返回是否成功（exitCode == 0） */
    private fun execRoot(cmd: String): Boolean {
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor() == 0
        }.getOrDefault(false)
    }
}