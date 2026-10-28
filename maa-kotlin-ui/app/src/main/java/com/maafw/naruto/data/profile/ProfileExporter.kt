/*
 * 火影MAA - 安卓脚本辅助框架
 * Copyright (C) 2024  火影MAA贡献者
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.maafw.naruto.data.profile

import android.content.Context
import android.net.Uri
import com.maafw.naruto.data.settings.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务配置文件导出/导入工具。
 * 导出单个 profile 及其任务右侧设置（options），便于备份/分享/迁移。
 */
object ProfileExporter {

    private const val EXPORT_DIR_NAME = "Maafw配置"

    private val exportDir: File
        get() = File(android.os.Environment.getExternalStorageDirectory(), EXPORT_DIR_NAME)

    /**
     * 导出指定 profile 为 JSON 文件。
     * 返回导出文件的绝对路径。
     */
    fun exportProfile(context: Context, profileName: String): Result<String> {
        return runCatching {
            ensureDir()
            val profile = ProfileManager.load(context, profileName)
                ?: ProfileManager.loadDefault(context, null)
            val json = JSONObject().apply {
                put("version", 1)
                put("type", "profile")
                put("exportedAt", formatTime(Date()))
                put("name", profile.name)
                val arr = JSONArray()
                profile.tasks.forEach { task ->
                    val config = SettingsRepository.getTaskConfig(context, task.entry)
                    arr.put(JSONObject().apply {
                        put("entry", task.entry)
                        put("enabled", task.enabled)
                        val opts = JSONObject()
                        config.options.forEach { (k, v) -> opts.put(k, v) }
                        put("options", opts)
                    })
                }
                put("tasks", arr)
            }

            val safeName = profileName.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]"), "_")
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(exportDir, "maafw_profile_${safeName}_$timestamp.json")
            FileWriter(file, Charsets.UTF_8).use { it.write(json.toString(2)) }
            file.absolutePath
        }
    }

    /**
     * 从 JSON 字符串导入 profile。
     *
     * @param targetName 若不为空则覆盖此名称，否则使用 JSON 中自带的 name。
     */
    fun importProfile(context: Context, json: String, targetName: String? = null): Result<String> {
        return runCatching {
            val obj = JSONObject(json)
            val name = targetName ?: obj.optString("name", "imported")
            val arr = obj.optJSONArray("tasks") ?: JSONArray()
            val tasks = mutableListOf<ProfileManager.ProfileTask>()
            for (i in 0 until arr.length()) {
                val taskObj = arr.getJSONObject(i)
                val entry = taskObj.optString("entry", "")
                if (entry.isBlank()) continue
                val enabled = taskObj.optBoolean("enabled", true)
                tasks.add(ProfileManager.ProfileTask(entry, enabled))

                // 恢复任务右侧设置
                val opts = mutableMapOf<String, String>()
                taskObj.optJSONObject("options")?.let { optObj ->
                    val keys = optObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        opts[key] = optObj.optString(key, "")
                    }
                }
                val oldConfig = SettingsRepository.getTaskConfig(context, entry)
                SettingsRepository.setTaskConfig(
                    context,
                    oldConfig.copy(options = opts, enabled = enabled)
                )
            }
            ProfileManager.save(context, ProfileManager.Profile(name, tasks))
            "配置 [$name] 已导入，共 ${tasks.size} 个任务"
        }
    }

    /**
     * 从 URI 读取并导入 profile。
     */
    fun importProfile(context: Context, uri: Uri, targetName: String? = null): Result<String> {
        return runCatching {
            val content = context.applicationContext.contentResolver.openInputStream(uri)?.use {
                it.reader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("无法打开文件")
            importProfile(context, content, targetName).getOrThrow()
        }
    }

    private fun ensureDir() {
        if (!exportDir.exists()) exportDir.mkdirs()
    }

    private fun formatTime(date: Date): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
    }
}