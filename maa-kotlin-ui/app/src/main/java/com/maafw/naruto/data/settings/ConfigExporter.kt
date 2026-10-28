package com.maafw.naruto.data.settings

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用配置导出/导入工具
 * 把 SharedPreferences 序列化为 JSON，导出到 /storage/emulated/0/Maafw配置。
 */
object ConfigExporter {

    private const val PREF_NAME = "maa_settings"
    private const val EXPORT_DIR_NAME = "Maafw配置"

    private val exportDir: File
        get() = File(Environment.getExternalStorageDirectory(), EXPORT_DIR_NAME)

    /** 导出全部配置 */
    fun export(context: Context): Result<String> {
        return runCatching {
            ensureDir()
            val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val json = JSONObject().apply {
                put("type", "settings")
                put("version", 1)
                put("exportedAt", formatTime(Date()))
            }
            for ((key, value) in prefs.all) {
                if (key == "type" || key == "version" || key == "exportedAt") continue
                when (value) {
                    null -> json.put(key, JSONObject.NULL)
                    is Boolean -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is Float -> json.put(key, value.toDouble())
                    is String -> json.put(key, value)
                    is Set<*> -> json.put(key, JSONObject.wrap(value.toList()))
                    else -> json.put(key, value.toString())
                }
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(exportDir, "maafw_config_$timestamp.json")
            FileWriter(file, Charsets.UTF_8).use { it.write(json.toString(2)) }
            file.absolutePath
        }
    }

    /** 从 JSON 字符串导入全部设置 */
    fun importFromJson(context: Context, content: String): Result<String> {
        return runCatching {
            val json = JSONObject(content)
            val type = json.optString("type", "settings")
            if (type != "settings") {
                throw IllegalArgumentException("该文件是 [$type] 类型，请在脚本页导入任务配置")
            }
            doImport(context, json)
        }
    }

    /** 从 URI 导入配置 */
    fun import(context: Context, uri: Uri): Result<String> {
        return runCatching {
            val content = context.applicationContext.contentResolver.openInputStream(uri)?.use {
                it.reader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("无法打开文件")
            importFromJson(context, content).getOrThrow()
        }
    }

    private fun doImport(context: Context, json: JSONObject): String {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "type" || key == "version" || key == "exportedAt") continue
            when (val value = json.get(key)) {
                JSONObject.NULL -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> {
                    val floatValue = value.toFloat()
                    if (floatValue == value) {
                        editor.putFloat(key, floatValue)
                    } else {
                        editor.putString(key, value.toString())
                    }
                }
                is String -> editor.putString(key, value)
                else -> editor.putString(key, value.toString())
            }
        }
        editor.apply()
        return "设置已导入，共 ${json.length() - 3} 项"
    }

    private fun formatTime(date: Date): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
    }

    /** 分享最近一次导出的配置文件 */
    fun shareLatest(context: Context): Uri? {
        val dir = exportDir
        if (!dir.exists()) return null
        val latest = dir.listFiles { file -> file.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return FileProvider.getUriForFile(
            context.applicationContext,
            "${context.applicationContext.packageName}.fileprovider",
            latest
        )
    }

    private fun ensureDir() {
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }
    }
}