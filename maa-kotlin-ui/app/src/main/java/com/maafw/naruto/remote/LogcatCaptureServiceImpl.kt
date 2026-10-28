package com.maafw.naruto.remote

import android.content.Context
import android.util.Log
import com.maafw.naruto.ILogcatService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 独立 logcat 服务进程（P1-2：完整版）。
 *
 * 作为 Shizuku UserService 独立进程运行（processNameSuffix("logcat")），
 * 按 pid 过滤持续抓取「App 进程 + 引擎进程」的 logcat，落盘到 userDir/debug/logcat/。
 * 引擎未连接/绑定失败时也能抓 App 侧日志（解决"一次性 dump 返回空/43 字节"与"绑定失败无日志"）。
 */
class LogcatCaptureServiceImpl(private val context: Context) : ILogcatService.Stub() {

    companion object {
        private const val TAG = "LogcatCaptureService"
        private const val ENGINE_PROCESS_SUFFIX = ":remote_engine"
    }

    private val targets = ConcurrentHashMap<Int, Process>()

    override fun startCapture(appPid: Int, userDir: String?) {
        stopCapture()
        if (appPid <= 0) return
        val baseDir = if (!userDir.isNullOrBlank()) File(userDir, "debug/logcat") else null
        // 引擎进程 pid：pgrep 按进程名后缀定位（remote_engine）
        val enginePid = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", ENGINE_PROCESS_SUFFIX))
            p.inputStream.bufferedReader().readText().trim().lines()
                .firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull() ?: -1
        }.getOrDefault(-1)

        startOne(appPid, "app", baseDir)
        if (enginePid > 0) startOne(enginePid, "core", baseDir)
        Log.i(TAG, "logcat 抓取已启动：app=$appPid engine=$enginePid")
    }

    private fun startOne(pid: Int, tag: String, baseDir: File?) {
        if (targets.containsKey(pid)) return
        val dir = baseDir ?: File("/data/local/tmp", "maafw_logcat").apply { mkdirs() }
        File(dir, tag).mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val out = File(File(dir, tag), "logcat_$ts.log")
        val proc = runCatching {
            ProcessBuilder("logcat", "-T", "10", "-v", "time", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return
        targets[pid] = proc
        Thread {
            runCatching { proc.inputStream.use { it.copyTo(out.outputStream()) } }
            targets.remove(pid)
        }.apply { name = "logcat-$tag"; isDaemon = true }.start()
    }

    override fun stopCapture() {
        targets.values.forEach { runCatching { it.destroy() } }
        targets.clear()
        Log.i(TAG, "logcat 抓取已停止")
    }
}