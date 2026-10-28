package com.maafw.naruto.schedule.model

import java.util.UUID

/**
 * 定时策略类型
 *  ScheduleType。
 */
enum class ScheduleType {
    /** 固定星期 + 时刻 */
    FIXED_TIME,
    /** 指定开始时间 + 间隔周期 */
    INTERVAL,
}

/**
 * 每日触发时刻esugar）。
 */
data class TimeOfDay(
    val hour: Int,
    val minute: Int
) : Comparable<TimeOfDay> {
    init {
        require(hour in 0..23) { "hour must be 0..23" }
        require(minute in 0..59) { "minute must be 0..59" }
    }

    override fun compareTo(other: TimeOfDay): Int {
        val h = hour.compareTo(other.hour)
        return if (h != 0) h else minute.compareTo(other.minute)
    }

    fun toMinutes(): Int = hour * 60 + minute

    override fun toString(): String = String.format("%02d:%02d", hour, minute)

    companion object {
        fun fromMinutes(total: Int): TimeOfDay = TimeOfDay(total / 60, total % 60)
        fun parse(s: String): TimeOfDay? {
            val parts = s.split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            return runCatching { TimeOfDay(h, m) }.getOrNull()
        }
    }
}

/**
 * 定时任务策略
 *  ScheduleStrategy.kt：
 * - daysOfWeek 用 Int 集合，1=周一 … 7=周日（ISO 值），
 * - executionTimes 用 [TimeOfDay] 列表
 */
data class ScheduleStrategy(
    val id: String = UUID.randomUUID().toString(),
    /** 策略名称 */
    val name: String,
    val enabled: Boolean = true,
    /** 调度类型，默认固定时间 */
    val scheduleType: ScheduleType = ScheduleType.FIXED_TIME,
    /** [FIXED_TIME] 启用的星期，1=周一…7=周日 */
    val daysOfWeek: Set<Int> = emptySet(),
    /** [FIXED_TIME] 每日触发时刻列表，已排序 */
    val executionTimes: List<TimeOfDay> = emptyList(),
    /** [INTERVAL] 首次执行的绝对时间（epoch ms） */
    val startTimeMs: Long? = null,
    /** [INTERVAL] 执行间隔（分钟） */
    val intervalMinutes: Int? = null,
    /** 关联的任务配置 Profile 名 */
    val profileId: String,
    /** 触发时若有任务运行，强制停止后再启动 */
    val forceStart: Boolean = false,
    /** 任务结束后自动熄屏 */
    val autoSleepAfterTask: Boolean = false,
    /** 任务结束后关闭游戏 */
    val closeGameAfterTask: Boolean = false,
    /** 使用 Shizuku 在后台无应用进程时主动唤醒并启动服务 */
    val shizukuWakeApp: Boolean = false,
    /**
     * 使用 Root 在后台无应用进程时主动唤醒并启动服务。
     * 比 Shizuku 唤醒更可靠：闹钟触发后以 root 执行 am start 把 App 强拉前台
     * （解除 Android12+ 后台启动前台服务限制），引擎也走 root 进程，完全不依赖 Shizuku。
     */
    val rootWakeApp: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastExecutedAt: Long? = null,
    val lastResult: ExecutionResult? = null,
    val lastResultMessage: String? = null,
)