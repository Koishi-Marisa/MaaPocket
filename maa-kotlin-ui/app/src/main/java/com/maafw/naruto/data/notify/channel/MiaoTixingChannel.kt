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
 * 喵提醒推送通道。
 * 官方触发接口：http://miaotixing.com/trigger?id=<喵码>&text=<内容>&type=json
 */
class MiaoTixingChannel(private val context: Context) : PushChannel {

    override val channelId = "喵提醒"

    override suspend fun deliver(title: String, body: String): PushResult {
        val code = SettingsRepository.getPushMiaotixingToken(context)
        if (code.isBlank()) {
            return PushResult.Rejected("喵码未配置")
        }

        val text = if (body.isBlank()) title else "$title\n$body"
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val urlString = "http://miaotixing.com/trigger?id=${url(code)}&text=$encodedText&type=json"

        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "MAAFW-Android")
            conn.instanceFollowRedirects = true

            val httpCode = conn.responseCode
            val reader = if (httpCode in 200..299) conn.inputStream else conn.errorStream
            val responseBody = reader?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            conn.disconnect()

            if (httpCode !in 200..299) {
                return PushResult.Retryable("HTTP $httpCode: ${responseBody.take(200)}")
            }

            // 喵提醒返回示例：{"code":0,"msg":"成功","data":{}}
            val json = JSONObject(responseBody)
            when (json.optInt("code", -1)) {
                0 -> PushResult.Delivered
                else -> PushResult.Rejected(json.optString("msg", "未知错误"))
            }
        } catch (e: Exception) {
            PushResult.Retryable("网络异常: ${e.message}")
        }
    }

    private fun url(s: String) = URLEncoder.encode(s, "UTF-8")
}