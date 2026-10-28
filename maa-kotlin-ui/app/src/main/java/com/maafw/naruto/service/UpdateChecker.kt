package com.maafw.naruto.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 更新检查（D2）：检查 GitHub Releases 最新版本，下载 APK 并引导安装。
 * 仅更新 App 本体，不涉及脚本资源（资源随 App 内置）。
 */
object UpdateChecker {

    /** 发布仓库（按实际发布地址配置） */
    private const val REPO = "ShrugYu/MAAFW-Android"
    private const val GITHUB_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_TIMEOUT = 20_000

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val notes: String
    )

    /** 检查最新版本（IO 线程调用） */
    suspend fun checkLatest(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = DOWNLOAD_TIMEOUT
                conn.readTimeout = DOWNLOAD_TIMEOUT
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) throw RuntimeException("检查更新失败（HTTP ${conn.responseCode}）")
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val version = json.optString("tag_name", "").removePrefix("v")
                val notes = json.optString("body", "").take(500)
                // 从 assets 找 apk 下载地址
                val assets = json.optJSONArray("assets")
                var downloadUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i)
                        val name = a?.optString("name", "") ?: ""
                        if (name.endsWith(".apk")) {
                            downloadUrl = a.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                if (downloadUrl.isBlank()) throw RuntimeException("未找到可下载的 APK 安装包")
                UpdateInfo(version, downloadUrl, notes)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 下载 APK 到缓存目录（IO 线程调用），返回文件 */
    suspend fun downloadApk(context: Context, url: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val target = File(dir, "maafw_update.apk")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = DOWNLOAD_TIMEOUT
                conn.readTimeout = DOWNLOAD_TIMEOUT
                conn.setRequestProperty("Accept", "application/octet-stream")
                if (conn.responseCode != 200) throw RuntimeException("下载失败（HTTP ${conn.responseCode}）")
                conn.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 安装 APK（FileProvider + ACTION_VIEW） */
    fun installApk(context: Context, apk: File): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}