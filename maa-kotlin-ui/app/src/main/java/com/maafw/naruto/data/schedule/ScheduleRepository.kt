package com.maafw.naruto.data.schedule

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 定时任务仓库
 * 用 SharedPreferences + Gson 简单持久化。
 */
object ScheduleRepository {

    private const val PREF_NAME = "maa_schedule"
    private const val KEY_ITEMS = "schedule_items"
    private const val KEY_NEXT_ID = "schedule_next_id"

    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun load(context: Context): List<ScheduleItem> {
        val json = prefs(context).getString(KEY_ITEMS, "[]") ?: "[]"
        val type = object : TypeToken<List<ScheduleItem>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun save(context: Context, items: List<ScheduleItem>) {
        prefs(context).edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }

    fun add(context: Context, item: ScheduleItem): List<ScheduleItem> {
        val items = load(context).toMutableList()
        items.add(item)
        save(context, items)
        return items
    }

    fun update(context: Context, item: ScheduleItem): List<ScheduleItem> {
        val items = load(context).map { if (it.id == item.id) item else it }
        save(context, items)
        return items
    }

    fun delete(context: Context, id: Int): List<ScheduleItem> {
        val items = load(context).filter { it.id != id }
        save(context, items)
        return items
    }

    fun toggle(context: Context, id: Int): List<ScheduleItem> {
        val items = load(context).map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
        save(context, items)
        return items
    }

    fun nextId(context: Context): Int {
        val p = prefs(context)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }
}