package com.maafw.naruto.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * MAAFW 设置存储层（DataStore 风格重构）。
 *
 * 说明：
 * - 提供 DataStore 风格的统一存储接口（类型安全 getXxx / 异步落盘 put / Flow 响应式 observe），
 *   底层基于 SharedPreferences 实现（离线环境无需额外依赖；联网环境可无缝替换为 Preferences DataStore，
 *   只需改本文件内部实现，调用方不变）；
 * - 内存缓存 + 启动预读：同步读不阻塞 UI，写入异步落盘 + 同步更新缓存；
 * - 旧数据天然兼容：使用原 `maa_settings` 存储名，用户设置不丢失。
 */
object MaaFwSettingsStore {

    private const val PREF_NAME = "maa_settings"

    /** 内存缓存（key -> 值），启动预读填充，写操作同步更新 */
    private val cache = ConcurrentHashMap<String, Any>()

    /** 已写入的 key 集合（供 SettingsRepository 遍历使用） */
    private val allKeys = ConcurrentHashMap.newKeySet<String>()

    /** 状态流（响应式 UI 订阅用） */
    private val _snapshot = MutableStateFlow<Map<String, Any>>(emptyMap())
    val snapshot: Flow<Map<String, Any>> = _snapshot

    @Volatile
    private var preloaded = false

    /** 启动预读：把全部设置读入内存缓存（同步，主进程启动时调用一次） */
    fun preload(context: Context) {
        if (preloaded) return
        runCatching {
            val prefs = prefs(context)
            prefs.all.forEach { (k, v) ->
                if (v != null) cache[k] = v
                allKeys.add(k)
            }
            refreshSnapshot()
            preloaded = true
        }
    }

    private fun refreshSnapshot() {
        _snapshot.value = HashMap(cache)
    }

    // ───────────── 同步读（内存缓存） ─────────────

    fun getBoolean(key: String, default: Boolean): Boolean = (cache[key] as? Boolean) ?: default

    fun getString(key: String, default: String): String = (cache[key] as? String) ?: default

    fun getInt(key: String, default: Int): Int = (cache[key] as? Int) ?: default

    fun getLong(key: String, default: Long): Long = (cache[key] as? Long) ?: default

    fun getFloat(key: String, default: Float): Float = (cache[key] as? Float) ?: default

    @Suppress("UNCHECKED_CAST")
    fun getStringSet(key: String, default: Set<String>): Set<String> =
        (cache[key] as? Set<String>) ?: default

    /** 全部 key（同步，读缓存） */
    fun keys(): Set<String> = allKeys.toSet()

    // ───────────── 异步写（落盘 + 缓存同步） ─────────────

    fun put(context: Context, key: String, value: Any) {
        cache[key] = value
        allKeys.add(key)
        refreshSnapshot()
        runCatching {
            prefs(context).edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.map { it.toString() }.toSet())
                    else -> Unit
                }
            }.apply()
        }
    }

    /** 监听单个 Boolean 设置变化（响应式 UI 用） */
    fun observeBoolean(key: String, default: Boolean): Flow<Boolean> =
        _snapshot.map { it[key] as? Boolean ?: default }

    /** 监听全部设置变化（响应式 UI 用） */
    fun observeAll(): Flow<Map<String, Any>> = _snapshot

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        return (app ?: context).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}