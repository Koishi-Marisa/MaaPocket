package com.maapocket.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import timber.log.Timber

/**
 * MaaPocket 的 Application。
 *
 * 这里只做两件必须在主进程最早时机完成的事：
 *  1. 建立通知渠道 —— 自动化跑起来后是常驻前台服务，没有渠道会在 Android 8+ 上直接崩；
 *  2. 装 Timber —— 之后所有模块统一走 `Timber.tag(...)`，方便 `adb logcat` 过滤。
 *
 * **刻意不放**任何 MaaFramework / 特权进程相关的东西：
 *  - `libMaaFramework.so` 只在特权 helper 进程里 `Native.load`（见 [com.maapocket.core.maafw.MaaFw.ensureLoaded]），
 *    主进程加载它没有意义，反而会白白多映射 50MB 的 `.so` 并有加载失败的崩溃面；
 *  · 特权进程的拉起是显式动作（用户点按钮），不做进程级自动重连，所以也不需要在 Application 里持有连接。
 */
class MaaPocketApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        // minSdk 28 => 运行期一定有 NotificationManager，不需要版本判断外的兜底。
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // 自动化运行期间的前台服务通知（引擎已在跑 / 正在等游戏画面）。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNNING,
                getString(R.string.channel_running),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_running_desc)
                setShowBadge(false)
                enableVibration(false)
            }
        )

        // 失败/异常，需要用户回来看一眼。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.channel_alerts_desc)
            }
        )
    }

    companion object {
        const val CHANNEL_RUNNING = "maapocket.running"
        const val CHANNEL_ALERTS = "maapocket.alerts"
    }
}
