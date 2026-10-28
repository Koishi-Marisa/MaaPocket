package com.maafw.naruto.schedule.data

import android.content.Context
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.schedule.model.ExecutionResult
import com.maafw.naruto.schedule.model.ScheduleStrategy
import com.maafw.naruto.schedule.model.ScheduleType
import com.maafw.naruto.schedule.model.TimeOfDay
import com.maafw.naruto.schedule.model.toName
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时任务配置导入 / 导出。
 * 导出内容包含：任务配置文件（profiles，即脚本页的配置）+ 定时任务设置（时间/名字/唤醒等策略）。
 * 格式：{"type":"maa_schedule_config","version":1,"profiles":[...],"profileOrder":[...],"strategies":[...]}
 */
object ScheduleConfigManager {

    private const val TYPE = "maa_schedule_config"
    private const val VERSION = 1

    /** 导出完整配置为 JSON 字符串（profiles + strategies） */
    fun export(context: Context): String {
        // 任务配置文件（profiles）
        val profiles = JSONArray()
        ProfileManager.listProfiles(context).forEach { name ->
            ProfileManager.load(context, name)?.let { p ->
                val tasks = JSONArray()
                p.tasks.forEach { t ->
                    tasks.put(JSONObject().apply {
                        put("entry", t.entry)
                        put("enabled", t.enabled)
                    })
                }
                profiles.put(JSONObject().apply {
                    put("name", p.name)
                    put("tasks", tasks)
                })
            }
        }
        // 配置文件顺序
        val profileOrder = JSONArray(ProfileManager.listProfiles(context))

        // 定时策略
        val strategies = JSONArray()
        SchedulePolicyRepository(context).load().forEach { s ->
            strategies.put(strategyToJson(s))
        }

        return JSONObject().apply {
            put("type", TYPE)
            put("version", VERSION)
            put("profiles", profiles)
            put("profileOrder", profileOrder)
            put("strategies", strategies)
        }.toString(2)
    }

    /** 导入配置，返回导入结果描述（含恢复的 profiles/strategies 数量） */
    fun import(context: Context, json: String): Result<String> {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("type") != TYPE) {
                return Result.failure(IllegalArgumentException("不是有效的定时任务配置文件"))
            }

            // 1) 恢复任务配置
            var profileCount = 0
            val profilesArr = obj.optJSONArray("profiles")
            if (profilesArr != null) {
                for (i in 0 until profilesArr.length()) {
                    val po = profilesArr.getJSONObject(i)
                    val name = po.optString("name", "")
                    if (name.isBlank()) continue
                    val tasks = mutableListOf<ProfileManager.ProfileTask>()
                    po.optJSONArray("tasks")?.let { ta ->
                        for (j in 0 until ta.length()) {
                            val t = ta.getJSONObject(j)
                            tasks.add(ProfileManager.ProfileTask(t.optString("entry", ""), t.optBoolean("enabled", true)))
                        }
                    }
                    // default 配置保留原有任务合并
                    if (name == ProfileManager.DEFAULT_PROFILE_NAME) {
                        val existing = ProfileManager.load(context, name) ?: ProfileManager.Profile(name, mutableListOf())
                        existing.tasks.clear()
                        existing.tasks.addAll(tasks)
                        ProfileManager.save(context, existing)
                    } else {
                        ProfileManager.save(context, ProfileManager.Profile(name, tasks))
                    }
                    profileCount++
                }
            }
            // 恢复配置文件顺序
            obj.optJSONArray("profileOrder")?.let { arr ->
                val order = (0 until arr.length()).map { arr.getString(it) }
                ProfileManager.reorderProfiles(context, order)
            }

            // 2) 恢复定时策略
            val strategies = mutableListOf<ScheduleStrategy>()
            val strategiesArr = obj.optJSONArray("strategies")
            if (strategiesArr != null) {
                for (i in 0 until strategiesArr.length()) {
                    jsonToStrategy(strategiesArr.getJSONObject(i))?.let { strategies.add(it) }
                }
            }
            SchedulePolicyRepository(context).importStrategies(strategies)

            Result.success("导入完成：恢复 $profileCount 个任务配置、${strategies.size} 个定时任务")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun strategyToJson(s: ScheduleStrategy): JSONObject {
        return JSONObject().apply {
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
        }
    }

    private fun jsonToStrategy(o: JSONObject): ScheduleStrategy? {
        val days = mutableSetOf<Int>()
        o.optJSONArray("daysOfWeek")?.let { arr -> for (i in 0 until arr.length()) days.add(arr.getInt(i)) }
        val times = mutableListOf<TimeOfDay>()
        o.optJSONArray("executionTimes")?.let { arr ->
            for (i in 0 until arr.length()) TimeOfDay.parse(arr.getString(i))?.let { times.add(it) }
        }
        return ScheduleStrategy(
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
        )
    }
}