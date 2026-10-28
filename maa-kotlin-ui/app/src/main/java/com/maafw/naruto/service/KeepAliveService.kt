package com.maafw.naruto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.maafw.naruto.MainActivity
import com.maafw.naruto.R

/**
 * 后台保活前台服务。
 * 开启后以常驻前台服务运行，显著降低进程被系统/厂商后台清理杀掉的概率，
 * 保证脚本在后台持续运行（定时任务、手动挂机等场景）。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "后台保活", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持应用进程存活，防止后台被杀"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MAAFW 运行中")
            .setContentText(
                if (com.maafw.naruto.data.settings.SettingsRepository.isAccessibilityKeepAliveEnabled(this))
                    "无障碍保活已启用，后台挂机任务持续稳定运行"
                else "后台保活已开启（建议开启无障碍保活，后台更稳）"
            )
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 10001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("KeepAliveService", "启动保活服务失败: ${e.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }

        fun isRunning(context: Context): Boolean {
            return runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.getRunningServices(Int.MAX_VALUE).any {
                    it.service.className == KeepAliveService::class.java.name
                }
            }.getOrDefault(false)
        }
    }
}