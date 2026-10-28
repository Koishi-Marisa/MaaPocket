package com.maafw.naruto.data.profile

import android.content.Context
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.MaaTask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 任务配置文件管理
 * 一个 profile 就是一份有序任务列表。
 */
object ProfileManager {

    private const val PROFILES_DIR = "profiles"
    private const val PREF_NAME = "profile_manager"
    const val DEFAULT_PROFILE_NAME = "default"

    data class ProfileTask(
        val entry: String,
        var enabled: Boolean = true
    )

    data class Profile(
        val name: String,
        val tasks: MutableList<ProfileTask> = mutableListOf()
    )

    private fun profilesDir(context: Context): File {
        return File(context.filesDir, PROFILES_DIR).apply { mkdirs() }
    }

    private const val PREF_PROFILE_ORDER = "profile_order"

    private fun getProfileOrder(context: Context): List<String> {
        val raw = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_PROFILE_ORDER, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun saveProfileOrder(context: Context, order: List<String>) {
        val arr = JSONArray(order)
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_PROFILE_ORDER, arr.toString()).apply()
    }

    fun moveProfile(context: Context, fromIndex: Int, toIndex: Int) {
        val order = getProfileOrder(context).toMutableList()
        if (fromIndex in order.indices) {
            val item = order.removeAt(fromIndex)
            val target = toIndex.coerceIn(0, order.size)
            order.add(target, item)
            saveProfileOrder(context, order)
        }
    }

    /** 直接用完整顺序覆盖保存（配置列表拖拽时调用） */
    fun reorderProfiles(context: Context, newOrder: List<String>) {
        saveProfileOrder(context, newOrder)
    }

    fun addProfileToOrder(context: Context, name: String) {
        val order = getProfileOrder(context).toMutableList()
        if (!order.contains(name)) {
            order.add(name)
            saveProfileOrder(context, order)
        }
    }

    fun renameProfileInOrder(context: Context, oldName: String, newName: String) {
        val order = getProfileOrder(context).toMutableList()
        val idx = order.indexOf(oldName)
        if (idx >= 0) {
            order[idx] = newName
            saveProfileOrder(context, order)
        }
    }

    fun removeProfileFromOrder(context: Context, name: String) {
        val order = getProfileOrder(context).toMutableList()
        if (order.remove(name)) {
            saveProfileOrder(context, order)
        }
    }

    private fun profileFile(context: Context, name: String): File {
        return File(profilesDir(context), "$name.json")
    }

    /**
     * 加载默认配置文件。
     * 如果不存在，根据 interface.json 的 default_check 生成一份。
     */
    fun loadDefault(context: Context, interfaceData: MaaInterface?): Profile {
        val file = profileFile(context, DEFAULT_PROFILE_NAME)
        if (file.exists()) {
            return runCatching { parseProfile(file.readText()) }.getOrNull()
                ?: createDefaultProfile(context, interfaceData)
        }
        return createDefaultProfile(context, interfaceData).also { save(context, it) }
    }

    /**
     * 保存配置文件。
     */
    fun save(context: Context, profile: Profile): Boolean {
        return try {
            val dir = profilesDir(context)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${profile.name}.json")
            file.writeText(serializeProfile(profile))
            // 写盘后立即回读校验，确保真的落盘成功
            val loaded = runCatching { parseProfile(file.readText()) }.getOrNull()
            loaded != null && loaded.tasks.size == profile.tasks.size
        } catch (e: Exception) {
            android.util.Log.e("ProfileManager", "保存配置失败: ${profile.name}", e)
            false
        }
    }

    /**
 * 根据 interface.json 创建默认配置：所有 default_check=true 的任务按原顺序加入。
 */
fun createDefaultProfile(context: Context, interfaceData: MaaInterface?): Profile {
    val tasks = interfaceData?.task
        ?.filter { it.defaultCheck }
        ?.map { ProfileTask(it.entry, true) }
        ?.toMutableList()
        ?: mutableListOf()
    if (tasks.isEmpty()) {
        // fallback
        tasks.add(ProfileTask("start_up", true))
    }
    return Profile(DEFAULT_PROFILE_NAME, tasks)
}

/**
 * 重置默认配置为最初的默认状态（按 default_check 重新生成任务列表）。
 * @return 重置后的默认配置
 */
fun resetDefaultProfile(context: Context, allTasks: List<MaaTask>): Profile {
    val tasks = allTasks.filter { it.defaultCheck }
        .map { ProfileTask(it.entry, true) }
        .toMutableList()
    if (tasks.isEmpty()) {
        tasks.add(ProfileTask("start_up", true))
    }
    val fresh = Profile(DEFAULT_PROFILE_NAME, tasks)
    save(context, fresh)
    return fresh
}

    /**
     * 获取所有可用的 profile 名。
     */
    fun listProfiles(context: Context): List<String> {
        val all = profilesDir(context).listFiles { _, name -> name.endsWith(".json") }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
        val order = getProfileOrder(context).toMutableList()
        order.retainAll(all)
        val remaining = (all - order.toSet()).sorted()
        return order + remaining
    }

    /**
     * 读取指定 profile 。
     */
    fun load(context: Context, name: String): Profile? {
        val file = profileFile(context, name)
        if (!file.exists()) return null
        return runCatching { parseProfile(file.readText()) }.getOrNull()
    }

    /**
     * 删除 profile 。
     */
    fun delete(context: Context, name: String): Boolean {
        if (name == DEFAULT_PROFILE_NAME) return false
        removeProfileFromOrder(context, name)
        return profileFile(context, name).delete()
    }

    /** 重命名 profile  */
    fun rename(context: Context, oldName: String, newName: String): Boolean {
        if (oldName == DEFAULT_PROFILE_NAME) return false
        val file = profileFile(context, oldName)
        if (!file.exists()) return false
        renameProfileInOrder(context, oldName, newName)
        return file.renameTo(profileFile(context, newName))
    }

    private fun serializeProfile(profile: Profile): String {
        val arr = JSONArray()
        profile.tasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("entry", t.entry)
                put("enabled", t.enabled)
            })
        }
        return JSONObject().apply {
            put("name", profile.name)
            put("tasks", arr)
        }.toString(2)
    }

    private fun parseProfile(json: String): Profile {
        val obj = JSONObject(json)
        val name = obj.optString("name", DEFAULT_PROFILE_NAME)
        val arr = obj.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<ProfileTask>()
        for (i in 0 until arr.length()) {
            val task = arr.getJSONObject(i)
            tasks.add(ProfileTask(
                entry = task.optString("entry", ""),
                enabled = task.optBoolean("enabled", true)
            ))
        }
        return Profile(name, tasks)
    }
}