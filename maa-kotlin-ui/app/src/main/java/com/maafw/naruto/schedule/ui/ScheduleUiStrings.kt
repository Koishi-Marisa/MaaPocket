package com.maafw.naruto.schedule.ui

import com.maafw.naruto.schedule.model.ExecutionResult
import com.maafw.naruto.schedule.model.ScheduleStrategy
import com.maafw.naruto.schedule.model.ScheduleType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务 UI 文案
 *  ScheduleUiStrings.kt，字符串内联为中文（不依赖 i18n 资源）。
 */

/** 星期完整名（1=周一…7=周日） */
fun scheduleDayChipLabel(day: Int): String = when (day) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "周$day"
}

/** 星期简称（1=周一…7=周日） */
fun scheduleDaySummaryLabel(day: Int): String = when (day) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    7 -> "日"
    else -> "$day"
}

/** 执行结果中文名 */
fun scheduleExecutionResultLabel(result: ExecutionResult): String = when (result) {
    ExecutionResult.STARTED -> "已执行"
    ExecutionResult.FAILED_VALIDATION -> "校验失败"
    ExecutionResult.FAILED_START -> "启动失败"
    ExecutionResult.FAILED_UI_LAUNCH -> "UI 拉起失败"
    ExecutionResult.SKIPPED_BUSY -> "任务繁忙已跳过"
    ExecutionResult.CANCELLED -> "已取消"
}

/** 策略摘要（ localizedScheduleStrategySummary） */
fun localizedScheduleStrategySummary(strategy: ScheduleStrategy): String {
    val schedule = when (strategy.scheduleType) {
        ScheduleType.FIXED_TIME -> {
            val days = strategy.daysOfWeek.sorted()
                .map { scheduleDaySummaryLabel(it) }
                .joinToString(" ")
            val times = strategy.executionTimes.joinToString(" ") { it.toString() }
            listOf(days, times).filter { it.isNotBlank() }.joinToString(" ")
        }

        ScheduleType.INTERVAL -> {
            val totalMinutes = strategy.intervalMinutes ?: 0
            val days = totalMinutes / (24 * 60)
            val hours = (totalMinutes % (24 * 60)) / 60
            val intervalText = when {
                days > 0 && hours > 0 -> "每 ${days}天${hours}小时"
                days > 0 -> "每 $days 天"
                else -> "每 $hours 小时"
            }
            val startText = strategy.startTimeMs?.let { ms ->
                val formatted = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
                "从 $formatted 开始"
            }.orEmpty()
            listOf(intervalText, startText).filter { it.isNotBlank() }.joinToString(" ")
        }
    }
    // 唤醒方式标记：Root 唤醒优先显示，其次 Shizuku
    val wakeMark = when {
        strategy.rootWakeApp -> "· Root唤醒"
        strategy.shizukuWakeApp -> "· Shizuku唤醒"
        else -> ""
    }
    return listOf(schedule, wakeMark).filter { it.isNotBlank() }.joinToString(" ")
}