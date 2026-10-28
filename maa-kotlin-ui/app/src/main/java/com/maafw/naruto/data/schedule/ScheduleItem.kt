package com.maafw.naruto.data.schedule

/**
 * 定时任务项
 * @param id 唯一标识
 * @param taskEntry 任务入口名
 * @param taskName 任务显示名
 * @param hour 小时 0-23
 * @param minute 分钟 0-59
 * @param repeatDays 重复日期，0=周日，1=周一…6=周六
 * @param enabled 是否启用
 */
data class ScheduleItem(
    val id: Int,
    val taskEntry: String,
    val taskName: String,
    val hour: Int,
    val minute: Int,
    val repeatDays: Set<Int> = emptySet(),
    val enabled: Boolean = true
) {
    fun toLogString(): String = String.format("%02d:%02d %s%s", hour, minute, taskName,
        if (enabled) "" else "[已禁用]")
}