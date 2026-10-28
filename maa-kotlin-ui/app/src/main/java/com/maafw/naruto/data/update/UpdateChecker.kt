package com.maafw.naruto.data.update

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查（D2）：从 GitHub Releases 检查最新版本并下载 APK 安装。
 * 仅检查/下载应用包，不含资源在线更新（资源随 APK 打包）。
 */
object UpdateChecker {

    private const val RELEASE_API = "https://api.github.com/repos/ShrugYu/MAAFW-Android/releases/latest"
    private const val TAG = "UpdateChecker"

    data class UpdateInfo(
        val version: String,        // 如 v1.0.1
        val name: String,           // 发布标题
        val apkUrl: String?,        // APK 资产下载地址（可能为空）
        val notes: String           // 更新说明
    )

    /** 检查是否有新版本（网络请求，需在后台线程调用） */
    fun checkForUpdate(): Result<UpdateInfo> = runCatching {
        val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) error("请求失败（HTTP ${conn.responseCode}）")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val assets = json.optJSONArray("assets") ?: org.json.JSONArray()
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i)
                val name = a?.optString("name", "") ?: ""
                if (name.endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            UpdateInfo(
                version = json.optString("tag_name", "unknown"),
                name = json.optString("name", ""),
                apkUrl = apkUrl,
                notes = json.optString("body", "").take(500)
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 下载 APK 到缓存目录（网络请求，需在后台线程调用） */
    fun downloadApk(context: Context, url: String, fileName: String): Result<File> = runCatching {
        val dir = File(context.cacheDir, "apk_cache").apply { mkdirs() }
        val dest = File(dir, fileName)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/octet-stream")
            if (conn.responseCode != 200) error("下载失败（HTTP ${conn.responseCode}）")
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (!dest.exists() || dest.length() == 0L) error("下载文件为空")
            dest
        } finally {
            conn.disconnect()
        }
    }

    /** 通过 FileProvider 触发安装 */
    fun installApk(context: Context, apkFile: File): Result<Unit> = runCatching {
        val authority = "${context.packageName}.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, apkFile)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}