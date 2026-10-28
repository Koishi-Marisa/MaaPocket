package com.maafw.naruto.schedule.data

import android.content.Context
import android.content.SharedPreferences
import com.maafw.naruto.schedule.model.ExecutionResult
import com.maafw.naruto.schedule.model.ScheduleStrategy
import com.maafw.naruto.schedule.model.ScheduleType
import com.maafw.naruto.schedule.model.TimeOfDay
import com.maafw.naruto.schedule.model.executionResultFromName
import com.maafw.naruto.schedule.model.toName
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时策略仓库
 *  SchedulePolicyRepository.kt：
 * DataStore 换成 SharedPreferences + org.json（不引入新依赖）。
 */
class SchedulePolicyRepository(private val context: Context) {

    companion object {
        private const val PREF_NAME = "maa_schedule_strategies"
        private const val KEY_STRATEGIES = "strategies"
    }

    private fun prefs(): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** 读取策略列表 */
    fun load(): List<ScheduleStrategy> {
        val raw = prefs().getString(KEY_STRATEGIES, null) ?: return emptyList()
        return runCatching { decodeStrategies(raw) }.getOrElse { emptyList() }
    }

    fun getById(strategyId: String): ScheduleStrategy? = load().find { it.id == strategyId }

    fun add(strategy: ScheduleStrategy) {
        val current = load().toMutableList()
        current.add(strategy)
        saveAll(current)
    }

    fun update(strategy: ScheduleStrategy) {
        val current = load().toMutableList()
        val idx = current.indexOfFirst { it.id == strategy.id }
        if (idx >= 0) {
            current[idx] = strategy
            saveAll(current)
        }
    }

    fun remove(strategyId: String) {
        val current = load().toMutableList()
        if (current.removeAll { it.id == strategyId }) {
            saveAll(current)
        }
    }

    fun setEnabled(strategyId: String, enabled: Boolean) {
        val current = load().toMutableList()
        val idx = current.indexOfFirst { it.id == strategyId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(enabled = enabled)
            saveAll(current)
        }
    }

    fun recordExecutionResult(
        strategyId: String,
        result: ExecutionResult,
        message: String? = null,
        executedAt: Long = System.currentTimeMillis(),
    ) {
        val current = load().toMutableList()
        val idx = current.indexOfFirst { it.id == strategyId }
        if (idx < 0) return
        current[idx] = current[idx].copy(
            lastExecutedAt = executedAt,
            lastResult = result,
            lastResultMessage = message,
        )
        saveAll(current)
    }

    fun importStrategies(strategies: List<ScheduleStrategy>) {
        saveAll(strategies)
    }

    private fun saveAll(strategies: List<ScheduleStrategy>) {
        prefs().edit().putString(KEY_STRATEGIES, encodeStrategies(strategies)).apply()
    }

    // ---- 序列化（org.json） ----

    private fun encodeStrategies(list: List<ScheduleStrategy>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("enabled", s.enabled)
                put("scheduleType", s.scheduleType.name)
                put("daysOfWeek", JSONArray(s.daysOfWeek.toList()))
                put("executionTimes", JSONArray(s.executionTimes.map { it.toString() }))
                s.startTimeMs?.let { put("startTimeMs", it) }
                s.intervalMinutes?.let { put("intervalMinutes", it) }
                put("profileId", s.profileId)
                put("forceStart", s.forceStart)
                put("autoSleepAfterTask", s.autoSleepAfterTask)
                put("closeGameAfterTask", s.closeGameAfterTask)
                put("shizukuWakeApp", s.shizukuWakeApp)
                put("rootWakeApp", s.rootWakeApp)
                put("createdAt", s.createdAt)
                s.lastExecutedAt?.let { put("lastExecutedAt", it) }
                s.lastResult?.let { put("lastResult", it.toName()) }
                s.lastResultMessage?.let { put("lastResultMessage", it) }
            })
        }
        return arr.toString()
    }

    private fun decodeStrategies(raw: String): List<ScheduleStrategy> {
        val arr = JSONArray(raw)
        val result = mutableListOf<ScheduleStrategy>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val days = mutableSetOf<Int>()
            o.optJSONArray("daysOfWeek")?.let { daysArr ->
                for (j in 0 until daysArr.length()) days.add(daysArr.getInt(j))
            }
            val times = mutableListOf<TimeOfDay>()
            o.optJSONArray("executionTimes")?.let { timesArr ->
                for (j in 0 until timesArr.length()) {
                    TimeOfDay.parse(timesArr.getString(j))?.let { times.add(it) }
                }
            }
            result.add(
                ScheduleStrategy(
                    id = o.optString("id", java.util.UUID.randomUUID().toString()),
                    name = o.optString("name", "未命名"),
                    enabled = o.optBoolean("enabled", true),
                    scheduleType = runCatching { ScheduleType.valueOf(o.optString("scheduleType", "FIXED_TIME")) }
                        .getOrDefault(ScheduleType.FIXED_TIME),
                    daysOfWeek = days,
                    executionTimes = times.sorted(),
                    startTimeMs = if (o.has("startTimeMs")) o.optLong("startTimeMs") else null,
                    intervalMinutes = if (o.has("intervalMinutes")) o.optInt("intervalMinutes") else null,
                    profileId = o.optString("profileId", "default"),
                    forceStart = o.optBoolean("forceStart", false),
                    autoSleepAfterTask = o.optBoolean("autoSleepAfterTask", false),
                    closeGameAfterTask = o.optBoolean("closeGameAfterTask", false),
                    shizukuWakeApp = o.optBoolean("shizukuWakeApp", false),
                    rootWakeApp = o.optBoolean("rootWakeApp", false),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    lastExecutedAt = if (o.has("lastExecutedAt")) o.optLong("lastExecutedAt") else null,
                    lastResult = executionResultFromName(if (o.has("lastResult")) o.optString("lastResult") else null),
                    lastResultMessage = if (o.has("lastResultMessage")) o.optString("lastResultMessage") else null,
                )
            )
        }
        return result
    }
}