package com.maafw.naruto.data.log

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出工具
 * 把引擎实时写入的 maafw.log 复制到公共目录 /storage/emulated/0/maa日志，
 * 文件名带时间戳，确保每次导出都是最新的一份、不会被上次的覆盖。
 */
object LogExporter {

    const val LOG_SUB_DIR = "maa_logs"
    const val LOG_FILE_NAME = "maafw.log"
    const val EXPORT_DIR_NAME = "MaaFw日志"

    /** 源日志文件（优先应用外部私有目录；旧版 mkdirs bug 曾导致日志落到 /data/local/tmp，兜底兼容） */
    fun sourceLogFile(context: Context): File? {
        val base = context.getExternalFilesDir(null)
        val ext = base?.let { File(it, "$LOG_SUB_DIR/$LOG_FILE_NAME") }
        if (ext != null && runCatching { ext.exists() && ext.length() > 0 }.getOrDefault(false)) return ext
        // 旧版 root 模式日志可能回退到 /data/local/tmp/maa_logs_<pkg>/maafw.log
        // 注意：root 进程写入的文件 App 可能无读权限（EACCES），必须验证可读，否则导出时直接崩溃
        val tmp = File("/data/local/tmp", "maa_logs_${context.packageName}/$LOG_FILE_NAME")
        if (runCatching { tmp.exists() && tmp.length() > 0 && tmp.canRead() }.getOrDefault(false)) return tmp
        return ext
    }

    /** 删除所有引擎日志文件（含备份）+ 导出目录日志（MaaFw日志），供「清空日志」使用 */
    fun clearLogFiles(context: Context) {
        // 清理范围必须覆盖 ZIP 导出会打包的所有来源，否则旧日志会被再次导出：
        //  1) ext/maa_logs/          → engine/ + session/（maafw.log、session_*、custom_kt、agent、bak）
        //  2) ext/debug/             → app/（service_bind_debug、crash、app_runtime、conn_env、conn_debug）+ logcat/
        //  3) /data/local/tmp/maa_logs_<pkg>/ → root/maafw.log（Root 引擎会话日志）
        //  4) /data/local/tmp/maafw_root_engine.log → root/root_launch_debug.log（Root launcher stderr）
        //  5) ext/engine_config.json  → config/engine_config.json
        //  6) 导出目录 /storage/emulated/0/MaaFw日志（maafw_/app_/logcat_/custom_/agent_ 等）
        val ext = context.getExternalFilesDir(null)
        val dirs = mutableListOf<File>()
        ext?.let { dirs += File(it, LOG_SUB_DIR) }
        ext?.let { dirs += File(it, "debug") }
        dirs += File("/data/local/tmp", "maa_logs_${context.packageName}")
        dirs += File("/data/local/tmp/maafw_root_engine.log")
        val exportDir = File(android.os.Environment.getExternalStorageDirectory(), EXPORT_DIR_NAME)
        if (exportDir.exists()) dirs += exportDir
        for (dir in dirs) {
            runCatching {
                if (dir.isFile) {
                    dir.delete()
                } else if (dir.isDirectory) {
                    // debug/ 与导出目录整体清空（含子目录 logcat/）；maa_logs 目录只删 .log/.txt（保留目录结构）
                    val isDebug = dir.name == "debug"
                    dir.listFiles()?.forEach { f ->
                        if (f.isDirectory) {
                            if (isDebug) {
                                f.deleteRecursively()
                            }
                        } else {
                            if (isDebug || f.name.endsWith(".log") || f.name.endsWith(".txt")) {
                                f.delete()
                            }
                        }
                    }
                }
                Unit // if-else if 作为语句而非表达式（避免 Kotlin 要求最终 else）
            }
        }
        // engine_config.json（单文件）
        runCatching { ext?.let { File(it, "engine_config.json") }?.delete() }
    }

    /**
     * 引擎会话滚动：每次引擎进程启动时调用，把上次的 maafw.log 滚动为备份（最多 3 个），
     * 引擎随后写新的 maafw.log = 本次会话日志 -> 导出不被上次运行污染。
     */
    fun rotateEngineLog(context: Context) {
        val base = context.getExternalFilesDir(null) ?: return
        val dir = File(base, LOG_SUB_DIR)
        if (!dir.exists() || !dir.isDirectory) return
        val log = File(dir, LOG_FILE_NAME)
        if (!log.exists() || log.length() == 0L) return
        // 滚动：bak_2->bak_3, bak_1->bak_2, log->bak_1（最多保留 3 个备份）
        runCatching {
            File(dir, "maafw_bak_3.log").delete()
            File(dir, "maafw_bak_2.log").renameTo(File(dir, "maafw_bak_3.log"))
            File(dir, "maafw_bak_1.log").renameTo(File(dir, "maafw_bak_2.log"))
            log.renameTo(File(dir, "maafw_bak_1.log"))
        }
    }

    /** 导出目录下每种日志（maafw_/app_/logcat_）只保留最近 3 个，删除更旧的 */
    fun cleanupOldExports(context: Context) {
        val targetDir = targetDir(context)
        listOf("maafw_", "app_", "logcat_").forEach { prefix ->
            runCatching {
                targetDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(3)
                    ?.forEach { it.delete() }
            }
        }
    }

    /** 是否有写入公共存储的权限（Android 11+ 需要"所有文件访问"） */
    fun hasStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    /** 导出目录：优先公共目录 /storage/emulated/0/maa日志，无权限时 fallback 应用私有目录 */
    private fun targetDir(context: Context): File {
        val publicDir = File(Environment.getExternalStorageDirectory(), EXPORT_DIR_NAME)
        return if (publicDir.exists() || publicDir.mkdirs()) {
            publicDir
        } else {
            File(context.getExternalFilesDir(null), "maa_logs_export").apply { mkdirs() }
        }
    }

    /**
     * 导出最新日志到 /storage/emulated/0/maa日志/maafw_yyyyMMdd_HHmmss.log
     * @return 导出的文件绝对路径
     */
    fun exportLatest(context: Context): Result<String> {
        val src = sourceLogFile(context)
            ?: return Result.failure(IllegalStateException("无法获取应用外部存储目录"))
        if (!src.exists() || !src.isFile) {
            return Result.failure(IllegalStateException("日志文件不存在：${src.absolutePath}"))
        }

        // 引擎日志是异步写入的，稍等片刻让队列 flush，确保复制到最新内容
        Thread.sleep(400)

        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "maafw_$ts.log")
        // 读取/复制源文件可能因权限（root 写入的 /data/local/tmp）抛 EACCES，必须捕获，避免 App 崩溃
        return runCatching {
            src.copyTo(dest, overwrite = false)
            dest.absolutePath
        }
    }

    /** 导出应用侧日志（MainActivity 运行日志），即使 MaaFW 没跑也能导出 */
    fun exportAppLog(context: Context, appLog: List<String>): Result<String> {
        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "app_$ts.log")
        runCatching { dest.writeText(appLog.joinToString("\n")) }
            .getOrElse { return Result.failure(it) }
        return Result.success(dest.absolutePath)
    }

    /** 导出复刻 py 的 custom 日志（引擎写的 files/maa_logs/custom_kt.log -> custom_*.log），无则返回 null */
    fun exportCustomLog(context: Context): Result<String>? {
        val src = context.getExternalFilesDir(null)?.let { File(File(it, LOG_SUB_DIR), "custom_kt.log") } ?: return null
        if (!src.exists() || src.length() == 0L) return null
        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "custom_$ts.log")
        return runCatching { src.copyTo(dest, overwrite = false); dest.absolutePath }
    }

    /** 导出 agent 独立进程日志（files/maa_logs/agent.log -> agent_*.log），无则返回 null */
    fun exportAgentLog(context: Context): Result<String>? {
        val src = context.getExternalFilesDir(null)?.let { File(File(it, LOG_SUB_DIR), "agent.log") } ?: return null
        if (!src.exists() || src.length() == 0L) return null
        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "agent_$ts.log")
        return runCatching { src.copyTo(dest, overwrite = false); dest.absolutePath }
    }

    /** 把给定的 logcat 文本写入导出文件（引擎侧抓取的全量日志） */
    fun exportLogcatText(context: Context, logcatText: String): Result<String> {
        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "logcat_$ts.log")
        runCatching { dest.writeText(logcatText) }
            .getOrElse { return Result.failure(it) }
        return Result.success(dest.absolutePath)
    }

    /** 导出 logcat 系统日志（含引擎 Native 日志/异常堆栈） */
    fun exportLogcat(context: Context): Result<String> {
        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(targetDir, "logcat_$ts.log")
        return runCatching {
            // App 进程 logcat 受 Android 10+ uid 隔离，读不到 shell/root 引擎进程日志；
            // 先普通抓取，若不含引擎日志（MaaFWRemote/CustomRecognitions 等），root 设备用 su 抓全量
            val t1 = runCatching { execLogcat("") }.getOrDefault("")
            val text = if (t1.contains("MaaFWRemote") || t1.contains("CustomRecognitions") || t1.contains("MaaFramework")) {
                t1
            } else {
                runCatching { execLogcat("su -c ") }.getOrDefault(t1)
            }
            dest.writeText(text)
            dest.absolutePath
        }
    }

    private fun execLogcat(prefix: String): String {
        val cmd = arrayOf("sh", "-c", "$prefix logcat -d -t 20000 -v time")
        val proc = Runtime.getRuntime().exec(cmd)
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // 保留关键行：本应用相关 + 崩溃 + 引擎 native 日志
        val keywords = listOf(
            "maafw", "maafw", "MaaFW", "MaaFWRemote", "RemoteEngine",
            "MainActivity", "AndroidRuntime", "FATAL", "InputInjector",
            "CustomActions", "CustomRecognitions", "libbridge", "MaaFramework",
            "ScheduleAlarm", "MaaEngineService", "NativeBridge"
        )
        val filtered = text.lineSequence()
            .filter { line -> keywords.any { line.contains(it, ignoreCase = true) } }
            .joinToString("\n")
        return if (filtered.isBlank()) "（logcat 中暂无本应用相关输出）" else filtered
    }

    /** 源日志的最后修改时间（用于确认导出的是最新日志） */
    fun sourceLastModifiedText(context: Context): String? {
        val src = sourceLogFile(context) ?: return null
        if (!src.exists()) return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(src.lastModified()))
    }

    /** 源日志文件大小（KB） */
    fun sourceSizeKb(context: Context): Long? {
        val src = sourceLogFile(context) ?: return null
        if (!src.exists()) return null
        return src.length() / 1024
    }

    // ───────────── P1/L-4：ZIP 打包导出（全量日志 + 分类） ─────────────

    /**
     * 把项目**全部日志**分类打包成 ZIP：
     * - app/        App 侧诊断（service_bind_debug.log、crash.log、app_runtime.log）
     * - engine/     引擎会话日志（maafw.log、custom_kt.log、agent.log、maafw_bak_*.log、service_boot_debug.log）
     * - session/    任务会话日志（session_*.log）
     * - logcat/     独立 logcat 服务抓取（app|core 子目录）
     * - root/       Root 模式启动日志（root_launch_debug.log、root 会话 maafw.log）
     * - config/     当前引擎配置（engine_config.json）
     * - device_properties.txt（getprop 全量，根目录）
     * @return ZIP 文件绝对路径
     */
    fun exportAllToZip(context: Context): Result<String> {
        val targetDir = targetDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipFile = File(targetDir, "maa_logs_$ts.zip")
        return runCatching {
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
                val ext = context.getExternalFilesDir(null)
                val pkg = context.packageName

                // 1) app/：App 侧诊断（排除 logcat 子目录，单独分类）
                File(ext, "debug").listFiles()?.filter { it.isFile }?.forEach { f ->
                    zipEntry(zos, "app/${f.name}", f)
                }
                // 1b) P-conn：动态生成连接环境快照（无论引擎是否连接，导出时总是最新）
                //     含 Shizuku/Root 状态、引擎进程、日志痕迹 → 定位"引擎未连接"的核心证据
                runCatching {
                    val connEnv = ConnectionDiagnostics.snapshotText(context)
                    zos.putNextEntry(java.util.zip.ZipEntry("app/conn_env.txt"))
                    zos.write(connEnv.toByteArray())
                    zos.closeEntry()
                }
                // 2) engine/：引擎会话日志 + 启动 trace
                File(ext, "maa_logs").listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".log") }
                    ?.forEach { f ->
                        zipEntry(zos, "engine/${f.name}", f)  // maafw/custom_kt/agent/maafw_bak_*
                    }
                val boot = File(
                    Environment.getExternalStorageDirectory(),
                    "Android/data/$pkg/files/debug/service_boot_debug.log"
                )
                if (boot.exists()) zipEntry(zos, "engine/service_boot_debug.log", boot)
                // 3) session/：任务会话日志
                File(ext, "maa_logs").listFiles()
                    ?.filter { it.isFile && it.name.startsWith("session_") }
                    ?.forEach { f -> zipEntry(zos, "session/${f.name}", f) }
                // 4) logcat/：独立 logcat 服务抓取（app|core 子目录）
                File(ext, "debug/logcat").walkTopDown()
                    ?.filter { it.isFile }
                    ?.forEach { f ->
                        val rel = f.relativeTo(File(ext, "debug/logcat")).path
                        zipEntry(zos, "logcat/$rel", f)
                    }
                // 5) root/：Root 模式启动日志 + root 会话 maafw（尽力读取）
                val rootLaunch = File("/data/local/tmp/maafw_root_engine.log")
                if (rootLaunch.exists() && rootLaunch.canRead()) {
                    zipEntry(zos, "root/root_launch_debug.log", rootLaunch)
                }
                val rootMaa = File("/data/local/tmp/maa_logs_$pkg/maafw.log")
                if (rootMaa.exists() && rootMaa.canRead()) {
                    zipEntry(zos, "root/maafw.log", rootMaa)
                }
                // 6) config/：当前引擎配置（帮助还原复现环境）
                val cfg = File(ext, "engine_config.json")
                if (cfg.exists()) zipEntry(zos, "config/engine_config.json", cfg)
                // 7) 设备信息（getprop 全量；App 进程受限时写入占位说明，确保 entry 始终存在）
                val props = runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("getprop"))
                    p.inputStream.bufferedReader().readText()
                }.getOrDefault("").ifBlank { "(getprop 不可用：App 进程无权限，请用 adb shell getprop 或连接引擎后导出)" }
                zos.putNextEntry(java.util.zip.ZipEntry("device_properties.txt"))
                zos.write(props.toByteArray())
                zos.closeEntry()
            }
            zipFile.absolutePath
        }
    }

    private fun zipEntry(zos: java.util.zip.ZipOutputStream, name: String, f: File) {
        zos.putNextEntry(java.util.zip.ZipEntry(name))
        f.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }
}