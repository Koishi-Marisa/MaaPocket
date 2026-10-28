package com.maafw.naruto.data.log

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1/L-1：App 崩溃兜底记录。
 * 未捕获异常写入 {ext}/files/debug/crash.log（含线程/堆栈/版本/设备），导出日志时带上。
 * 崩溃原因不可见是历史痛点（引擎绑定/任务执行时主进程崩溃无从排查），本组件补齐。
 */
object MaaFwCrashHandler {

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(context, thread, throwable) }
            // 保留原默认 handler（系统行为不变：日志 + 杀进程）
            prev?.uncaughtException(thread, throwable)
                ?: run { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }

    private fun record(context: Context, thread: Thread, t: Throwable) {
        val dir = File(context.getExternalFilesDir(null), "debug").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("=== CRASH $ts ===\n")
        sb.append("Thread: ${thread.name}\n")
        sb.append("PID: ${android.os.Process.myPid()}\n")
        sb.append("Version: ${com.maafw.naruto.BuildConfig.VERSION_NAME} (${com.maafw.naruto.BuildConfig.VERSION_CODE})\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Message: ${t.message}\n")
        sb.append("Stack:\n")
        t.stackTrace?.forEach { sb.append("  at $it\n") }
        t.cause?.let { c ->
            sb.append("Caused by: $c\n")
            c.stackTrace?.forEach { sb.append("  at $it\n") }
        }
        sb.append("--- end ---\n\n")
        runCatching { File(dir, "crash.log").appendText(sb.toString()) }
    }
}