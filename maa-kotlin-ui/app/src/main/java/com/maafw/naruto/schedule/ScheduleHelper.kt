package com.maafw.naruto.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.maafw.naruto.MainActivity
import com.maafw.naruto.data.schedule.ScheduleItem
import com.maafw.naruto.schedule.model.ScheduleStrategy
import com.maafw.naruto.schedule.model.ScheduleType
import com.maafw.naruto.schedule.model.TimeOfDay
import java.util.Calendar

/**
 * 定时任务调度助手
 * 用 AlarmManager 设置 / 取消定时唤醒。
 */
object ScheduleHelper {

    private const val TAG = "ScheduleHelper"
    private const val REQUEST_BASE = 0x4D414100.toInt()

    fun schedule(context: Context, item: ScheduleItem) {
        cancel(context, item.id)
        if (!item.enabled || item.repeatDays.isEmpty()) return

        val alarmManager = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context.applicationContext, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_TRIGGER
            putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, item.id)
            putExtra(ScheduleAlarmReceiver.EXTRA_TASK_ENTRY, item.taskEntry)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_BASE + item.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextTime = calculateNextTime(item.hour, item.minute, item.repeatDays)
        if (nextTime < 0) {
            Log.w(TAG, "任务 ${item.id} 没有可用的下次执行时间")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "没有精确闹钟权限")
                return
            }
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, pendingIntent)
        Log.i(TAG, "定时任务已设置：${item.toLogString()} 下次=${formatTime(nextTime)} ")
    }

    fun cancel(context: Context, id: Int) {
        val alarmManager = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context.applicationContext, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_TRIGGER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_BASE + id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        Log.i(TAG, "取消定时任务：$id ")
    }

    fun rescheduleAll(context: Context, items: List<ScheduleItem>) {
        // 先全部取消
        items.forEach { cancel(context, it.id) }
        // 再设置启用的
        items.filter { it.enabled && it.repeatDays.isNotEmpty() }.forEach { schedule(context, it) }
    }

    /**
     * 计算下一次触发时间。
     */
    fun calculateNextTime(hour: Int, minute: Int, repeatDays: Set<Int>): Long {
        val now = Calendar.getInstance()
        val todayDay = now.get(Calendar.DAY_OF_WEEK) // 周日=1，周六=7
        val todayIndex = todayDay - 1 // 转成 0=周日…6=周六

        for (offset in 0..7) {
            val candidateIndex = (todayIndex + offset) % 7
            if (!repeatDays.contains(candidateIndex)) continue
            val candidate = now.clone() as Calendar
            candidate.add(Calendar.DAY_OF_YEAR, offset)
            candidate.set(Calendar.HOUR_OF_DAY, hour)
            candidate.set(Calendar.MINUTE, minute)
            candidate.set(Calendar.SECOND, 0)
            candidate.set(Calendar.MILLISECOND, 0)
            if (candidate.timeInMillis > now.timeInMillis + 1000L) {
                return candidate.timeInMillis
            }
        }
        return -1
    }

    private fun formatTime(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    // ================ 策略调度（ ScheduleAlarmManager） ================

    private const val STRATEGY_REQUEST_BASE = 0x534D4100.toInt()

    /**
     * 为策略注册下一个闹钟（ ScheduleAlarmManager.scheduleNext）。
     * 「后台唤醒」开启时用 setAlarmClock（强制精确唤醒 + 状态栏闹钟图标），
     * 即使手机锁屏/应用未启动也能可靠拉起任务。
     */
    fun scheduleStrategy(context: Context, strategy: ScheduleStrategy) {
        cancelStrategy(context, strategy.id)
        if (!strategy.enabled) return

        val nextTrigger = computeNextTriggerMs(strategy, 0L) ?: return
        val alarmManager = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildStrategyPendingIntent(context, strategy.id)
        val wakeOn = com.maafw.naruto.data.settings.SettingsRepository.isScheduleWakeOn(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                if (wakeOn) {
                    // 后台唤醒：闹钟式，强制脱离 Doze 精确触发，锁屏也能拉起任务
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(nextTrigger, buildShowIntent(context)),
                        pendingIntent,
                    )
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pendingIntent)
                }
            } else {
                // 无精确闹钟权限时降级 setAlarmClock（）
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(nextTrigger, buildShowIntent(context)),
                    pendingIntent,
                )
            }
        } else {
            if (wakeOn) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(nextTrigger, buildShowIntent(context)),
                    pendingIntent,
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pendingIntent)
            }
        }
        Log.i(TAG, "定时策略已注册：[${strategy.name}] 下次=${formatTime(nextTrigger)} 后台唤醒=$wakeOn ")
    }

    /** 取消策略的闹钟 */
    fun cancelStrategy(context: Context, strategyId: String) {
        val alarmManager = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildStrategyPendingIntent(context, strategyId)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /** 重新调度所有启用的策略 */
    fun rescheduleStrategies(context: Context, strategies: List<ScheduleStrategy>) {
        strategies.filter { it.enabled }.forEach { scheduleStrategy(context, it) }
    }

    /** 计算策略的下次触发时间（epoch ms） */
    fun computeNextTriggerMs(strategy: ScheduleStrategy, afterEpochMs: Long): Long? {
        return when (strategy.scheduleType) {
            ScheduleType.FIXED_TIME -> computeNextFixedTimeMs(strategy, afterEpochMs)
            ScheduleType.INTERVAL -> computeNextIntervalMs(strategy, afterEpochMs)
        }
    }

    /** [FIXED_TIME] 扫描未来 7 天，匹配 dayOfWeek + executionTimes（） */
    private fun computeNextFixedTimeMs(strategy: ScheduleStrategy, afterEpochMs: Long): Long? {
        if (strategy.daysOfWeek.isEmpty() || strategy.executionTimes.isEmpty()) return null

        val now = Calendar.getInstance()
        val baseline = Calendar.getInstance().apply {
            timeInMillis = maxOf(System.currentTimeMillis(), afterEpochMs)
        }

        // daysOfWeek: 1=周一…7=周日（ISO）；Calendar.DAY_OF_WEEK: 1=周日…7=周六
        fun isoDay(cal: Calendar): Int {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            return if (dow == Calendar.SUNDAY) 7 else dow - 1
        }

        for (dayOffset in 0..7) {
            val candidate = baseline.clone() as Calendar
            candidate.add(Calendar.DAY_OF_YEAR, dayOffset)
            if (isoDay(candidate) !in strategy.daysOfWeek) continue

            for (time in strategy.executionTimes) {
                val trigger = candidate.clone() as Calendar
                trigger.set(Calendar.HOUR_OF_DAY, time.hour)
                trigger.set(Calendar.MINUTE, time.minute)
                trigger.set(Calendar.SECOND, 0)
                trigger.set(Calendar.MILLISECOND, 0)
                if (trigger.timeInMillis > baseline.timeInMillis + 1000L) {
                    return trigger.timeInMillis
                }
            }
        }
        return null
    }

    /** [INTERVAL] 从 startTimeMs 起每隔 intervalMinutes 触发一次（） */
    private fun computeNextIntervalMs(strategy: ScheduleStrategy, afterEpochMs: Long): Long? {
        val startMs = strategy.startTimeMs ?: return null
        val intervalMs = (strategy.intervalMinutes ?: return null) * 60_000L
        if (intervalMs <= 0) return null

        val now = System.currentTimeMillis()
        val baseline = maxOf(now, afterEpochMs)

        return if (startMs > baseline) {
            startMs
        } else {
            val elapsed = baseline - startMs
            val n = elapsed / intervalMs + 1
            startMs + n * intervalMs
        }
    }

    /** 计算下次触发时间用于 UI 显示（MM-dd HH:mm） */
    fun formatNextTriggerForDisplay(strategy: ScheduleStrategy): String? {
        val next = computeNextTriggerMs(strategy, 0L) ?: return null
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        return String.format("%02d-%02d %02d:%02d",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    private fun buildStrategyPendingIntent(context: Context, strategyId: String): PendingIntent {
        val intent = Intent(context.applicationContext, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_TRIGGER
            putExtra(ScheduleAlarmReceiver.EXTRA_STRATEGY_ID, strategyId)
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            STRATEGY_REQUEST_BASE + strategyId.hashCode().and(0x7FFFFFFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** setAlarmClock 要求的状态栏闹钟点击 Intent（） */
    private fun buildShowIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context.applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}