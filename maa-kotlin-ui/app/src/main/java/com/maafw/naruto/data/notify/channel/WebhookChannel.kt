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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自定义 Webhook 推送通道。
 */
class WebhookChannel(private val context: Context) : PushChannel {

    override val channelId = "Webhook"

    override suspend fun deliver(title: String, body: String): PushResult {
        val url = SettingsRepository.getPushWebhookUrl(context)
        if (url.isBlank()) {
            return PushResult.Rejected("Webhook URL 未配置")
        }

        return try {
            var payload = SettingsRepository.getPushWebhookBody(context)
            if (payload.isBlank()) {
                payload = "{\"title\":\"{title}\",\"content\":\"{content}\"}"
            }
            payload = payload
                .replace("{title}", escape(title))
                .replace("{content}", escape(body))

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val reader = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = reader?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            conn.disconnect()

            if (code in 200..299) {
                PushResult.Delivered
            } else {
                PushResult.Retryable("HTTP $code: ${resp.take(200)}")
            }
        } catch (e: Exception) {
            PushResult.Retryable("网络异常: ${e.message}")
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}