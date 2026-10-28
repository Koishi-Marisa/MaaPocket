package com.maafw.naruto.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.maafw.naruto.MainActivity
import com.maafw.naruto.R
import com.maafw.naruto.data.settings.SettingsRepository

/**
 * 系统通知管理器。
 * 负责应用内部事件通知（任务完成/出错/服务异常），按类型独立 ID/渠道/颜色，互不覆盖。
 */
class AppNotificationManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "AppNotificationManager"
        private const val CHANNEL_DEFAULT = "app_events_default_v2"
        private const val CHANNEL_HIGH = "app_events_important_v2"

        // 按类型独立 ID，避免互相覆盖
        private const val ID_TASK_START = 9001
        private const val ID_TASK_COMPLETE = 9002
        private const val ID_TASK_ERROR = 9003
        private const val ID_SERVICE_DIED = 9004
        private const val ID_TASK_RUNNING = 9005
    }

    init {
        ensureChannels()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channels = listOf(
            NotificationChannel(
                CHANNEL_DEFAULT,
                "普通事件通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "任务开始/完成等普通事件通知"
            },
            NotificationChannel(
                CHANNEL_HIGH,
                "重要事件通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "任务出错/服务异常等重要事件通知"
            }
        )
        manager.createNotificationChannels(channels)
    }

    /** 定时任务开始 */
    fun notifyTaskStarted(taskName: String, message: String) {
        if (!canNotify()) return
        send("任务开始", "[$taskName] $message", ID_TASK_START, isHigh = false, accentGreen = false)
    }

    /** 全部任务完成（支持耗时信息） */
    fun notifyAllTasksCompleted(summary: String, durationText: String? = null) {
        if (!canNotify()) return
        val text = if (durationText != null) "$summary · 耗时 $durationText" else summary
        send("任务完成", text, ID_TASK_COMPLETE, isHigh = false, accentGreen = true)
    }

    /** 任务出错 */
    fun notifyTaskError(taskName: String, message: String) {
        if (!canNotify()) return
        send("任务出错", "[$taskName] $message", ID_TASK_ERROR, isHigh = true, accentGreen = false)
    }

    /** 服务异常 */
    fun notifyServiceDied(message: String) {
        if (!canNotify()) return
        send("服务异常 注意", message, ID_SERVICE_DIED, isHigh = true, accentGreen = false)
    }

    /** U-4：任务进行中（不确定进度条，无分段数据时用） */
    fun notifyTaskRunning(taskName: String, detail: String = "") {
        if (!canNotify()) return
        val text = if (detail.isBlank()) "任务运行中…" else detail
        val builder = NotificationCompat.Builder(appContext, CHANNEL_DEFAULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("[$taskName] 任务运行中…")
            .setContentText(text)
            .setContentIntent(runningPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)          // 不可滑动清除（任务结束自动取消）
            .setOnlyAlertOnce(true)    // 高频更新不打扰
            .setProgress(0, 0, true)   // 不确定进度条
            .setColor(0xFF2196F3.toInt())
        try {
            manager.notify(ID_TASK_RUNNING, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "发送运行中通知失败: ${e.message}")
        }
    }

    /** 任务链分段进度通知（completed/total，有错误时标题/进度条标红） */
    fun notifyTaskProgress(taskName: String, completed: Int, total: Int, errorCount: Int) {
        if (!canNotify()) return
        val text = if (errorCount > 0) "完成 $completed/$total（错误 $errorCount）" else "完成 $completed/$total"
        val builder = NotificationCompat.Builder(appContext, CHANNEL_DEFAULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("[$taskName] 任务运行中…")
            .setContentText(text)
            .setContentIntent(runningPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(completed, total, false)  // 分段进度
            .setColor(if (errorCount > 0) 0xFFE57373.toInt() else 0xFF2196F3.toInt())
        try {
            manager.notify(ID_TASK_RUNNING, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "发送进度通知失败: ${e.message}")
        }
    }

    private fun runningPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext, ID_TASK_RUNNING, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** U-4：任务结束/停止时取消运行中通知 */
    fun cancelTaskRunning() {
        runCatching { manager.cancel(ID_TASK_RUNNING) }
    }

    fun canNotify(): Boolean {
        if (!SettingsRepository.isNotificationEnabled(appContext)) {
            Log.d(TAG, "通知总开关未开启")
            return false
        }
        val enabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        if (!enabled) {
            Log.w(TAG, "系统通知权限被拒绝，状态栏不会显示通知")
        }
        return enabled
    }

    private fun send(title: String, text: String, notifyId: Int, isHigh: Boolean, accentGreen: Boolean) {
        val channelId = if (isHigh) CHANNEL_HIGH else CHANNEL_DEFAULT
        val priority = if (isHigh) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notifyId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setPriority(priority)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 完成=绿色强调（成功语义），其余默认
            .setColor(if (accentGreen) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())

        var defaults = 0
        if (SettingsRepository.isNotificationSound(appContext)) {
            defaults = defaults or NotificationCompat.DEFAULT_SOUND
        }
        if (SettingsRepository.isNotificationVibrate(appContext)) {
            defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
        }
        if (defaults != 0) {
            builder.setDefaults(defaults)
        }

        try {
            manager.notify(notifyId, builder.build())
            Log.i(TAG, "已发送通知: $title")
        } catch (e: SecurityException) {
            Log.e(TAG, "发送通知失败: ${e.message}")
        }
    }
}