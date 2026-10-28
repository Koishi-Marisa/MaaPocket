/*
 * 火影MAA - 安卓脚本辅助框架
 * Copyright (C) 2024  火影MAA贡献者
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.maafw.naruto.data.notify.channel

import android.content.Context
import com.maafw.naruto.data.notify.PushChannel
import com.maafw.naruto.data.notify.PushResult
import com.maafw.naruto.data.settings.SettingsRepository
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Server酱（SCT）推送通道。
 */
class ServerChanChannel(private val context: Context) : PushChannel {

    override val channelId = "Server酱"

    override suspend fun deliver(title: String, body: String): PushResult {
        val key = SettingsRepository.getPushServerChanKey(context)
        if (key.isBlank()) {
            return PushResult.Rejected("SendKey 未配置")
        }

        return try {
            val formBody = "text=${url(title)}&desp=${url(body)}"
            val resp = httpPost("https://sctapi.ftqq.com/$key.send", formBody)
            val json = JSONObject(resp.substringAfter("resp="))
            when {
                json.optInt("code", -1) == 0 -> PushResult.Delivered
                else -> PushResult.Rejected(
                    json.optString("message", json.optString("msg", "发送失败"))
                )
            }
        } catch (e: Exception) {
            PushResult.Retryable("网络异常: ${e.message}")
        }
    }

    private fun httpPost(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val reader = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = reader?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        } ?: ""
        conn.disconnect()
        return "code=$code resp=${resp.take(200)}"
    }

    private fun url(s: String) = URLEncoder.encode(s, "UTF-8")
}