package com.maafw.naruto.data.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务会话日志（B5）：一次任务从开始到结束的完整链路。
 * 记录任务配置、启动时间、运行过程中的关键日志、结束状态，独立文件按时间戳命名。
 */
object MaaFwSessionLog {

    @Volatile
    private var sessionFile: File? = null

    @Volatile
    private var active = false

    /** 开始一次任务会话 */
    fun startSession(context: Context, profileName: String, taskCount: Int) {
        endSession(context, "INTERRUPTED") // 上一会话未正常结束则先收尾
        val dir = File(context.getExternalFilesDir(null), "maa_logs").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "session_$ts.log")
        sessionFile = file
        active = true
        append(context, "==== 会话开始：${profileName}（${taskCount} 个任务）====")
    }

    /** 追加一行日志 */
    fun append(context: Context, line: String) {
        val f = sessionFile ?: return
        if (!active) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runCatching { f.appendText("[$time] $line\n") }
    }

    /** 结束会话（记录结束状态） */
    fun endSession(context: Context, status: String) {
        val f = sessionFile ?: return
        if (!active) return
        append(context, "==== 会话结束：$status ====")
        active = false
        sessionFile = null
    }

    fun isActive(): Boolean = active
}