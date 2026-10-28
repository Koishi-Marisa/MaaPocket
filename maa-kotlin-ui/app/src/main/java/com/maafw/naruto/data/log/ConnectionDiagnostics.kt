package com.maafw.naruto.data.log

import android.content.Context
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连接环境诊断快照（P-conn）：
 * 解决"引擎未连接时无日志可查"的问题——把 Shizuku/Root 环境状态、引擎进程存在性、
 * 运行模式等一次性落盘/导出，与 AppBindLogger（绑定过程事件）互补：
 * - AppBindLogger：绑定过程（BIND/CONNECTING/ERROR）→ service_bind_debug.log
 * - ConnectionDiagnostics：绑定失败时的环境快照 → conn_env.txt（ZIP 导出时动态生成）
 */
object ConnectionDiagnostics {

    private const val TAG = "ConnDiag"

    /**
     * 生成连接环境快照文本（导出时或绑定失败时调用，无副作用）。
     * 无论引擎是否连接都能生成——这正是"引擎未连接"场景最需要的信息。
     */
    fun snapshotText(context: Context): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        sb.appendLine("===== 连接环境快照 $ts =====")

        // 运行模式
        sb.appendLine("[模式] 当前运行模式: ${com.maafw.naruto.data.settings.SettingsRepository.getRunMode(context)}")

        // ── Shizuku 状态（权限 vs 服务运行的差异是"未连接"头号原因） ──
        val ping = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
        val ver = runCatching { rikka.shizuku.Shizuku.getVersion() }.getOrDefault(-1)
        val uid = runCatching { rikka.shizuku.Shizuku.getUid() }.getOrDefault(-1)
        val perm = runCatching { rikka.shizuku.Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        val permOk = perm == PackageManager.PERMISSION_GRANTED
        sb.appendLine("[Shizuku] pingBinder(服务运行)=$ping | version=$ver | uid=$uid | 已授权=$permOk")
        if (!ping) {
            sb.appendLine("[Shizuku] ⚠ 已授权但服务未运行（checkSelfPermission=$permOk, pingBinder=$ping）→ 需启动 Shizuku 服务或切 Root 模式")
        } else if (!permOk) {
            sb.appendLine("[Shizuku] ⚠ 服务运行但未授权本应用 → 需在 Shizuku 里授权")
        }

        // ── Root 状态 ──
        val rootAvail = runCatching { com.maafw.naruto.root.RootManager.isRootAvailable() }.getOrDefault(false)
        val rootGranted = runCatching { com.maafw.naruto.root.RootManager.isRootGranted() }.getOrDefault(false)
        sb.appendLine("[Root] 可用=$rootAvail | 已授权=$rootGranted")
        if (rootAvail && !rootGranted) {
            sb.appendLine("[Root] ⚠ su 存在但未授权 → 打开 App 触发授权弹窗，或手动允许 root")
        }

        // ── 引擎相关进程（Shizuku UserService / Root app_process） ──
        sb.appendLine("[进程] App pid=${android.os.Process.myPid()}")
        sb.appendLine("[进程] remote_engine: ${procInfo("com.maafw.naruto:remote_engine")}")
        sb.appendLine("[进程] engine(方案C): ${procInfo("com.maafw.naruto:engine")}")
        sb.appendLine("[进程] root引擎(app_process RootServiceStarter): ${procInfo("RootServiceStarter")}")
        sb.appendLine("[进程] logcat服务: ${procInfo("com.maafw.naruto:logcat")}")

        // ── 引擎日志痕迹（判断引擎是否曾启动） ──
        val ext = context.getExternalFilesDir(null)
        val maaLog = ext?.let { java.io.File(it, "maa_logs/maafw.log") }
        sb.appendLine("[日志] 引擎 maafw.log: ${fileInfo(maaLog)}")
        val rootLaunch = java.io.File("/data/local/tmp/maafw_root_engine.log")
        sb.appendLine("[日志] root_launch_debug.log: ${fileInfo(rootLaunch)}")
        val boot = ext?.let { java.io.File(it, "debug/service_boot_debug.log") }
        sb.appendLine("[日志] service_boot_debug.log: ${fileInfo(boot)}")

        sb.appendLine("=================================")
        return sb.toString()
    }

    private fun procInfo(keyword: String): String {
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep '$keyword' | grep -v grep"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (out.isBlank()) "未运行" else out.lineSequence().firstOrNull()?.trim() ?: "未运行"
        }.getOrDefault("未知")
    }

    private fun fileInfo(f: java.io.File?): String {
        if (f == null) return "N/A"
        return if (f.exists()) {
            val size = f.length() / 1024
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified()))
            "存在(${size}KB, 修改于 $ts)"
        } else {
            "不存在"
        }
    }
}
