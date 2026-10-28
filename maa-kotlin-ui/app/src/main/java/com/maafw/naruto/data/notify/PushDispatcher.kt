/*
 * 火影MAA - 安卓脚本辅助框架
 * Copyright (C) 2024  火影MAA贡献者
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.maafw.naruto.data.notify

import android.content.Context
import com.maafw.naruto.data.notify.channel.*
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.third.Ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 外部推送分发器。
 * 负责根据用户配置把消息路由到对应的 [PushChannel]。
 */
object PushDispatcher {

    private const val TAG = "PushDispatcher"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 根据配置 key 构造对应通道实例。 */
    private fun channelFor(context: Context, channel: String): PushChannel? {
        return when (channel) {
            "miaotixing" -> MiaoTixingChannel(context)
            "serverchan" -> ServerChanChannel(context)
            "dingtalk" -> DingTalkChannel(context)
            "smtp" -> SmtpChannel(context)
            "webhook" -> WebhookChannel(context)
            else -> null
        }
    }

    /**
     * 按配置分发一条任务状态消息。
     *
     * @param isSuccess true 表示任务成功，false 表示任务失败
     */
    fun dispatch(context: Context, title: String, body: String, isSuccess: Boolean) {
        if (!SettingsRepository.isNotificationEnabled(context)) return

        val channel = SettingsRepository.getPushChannel(context)
        if (channel == "none") return

        if (isSuccess && !SettingsRepository.isPushNotifySuccess(context)) return
        if (!isSuccess && !SettingsRepository.isPushNotifyError(context)) return

        val prefixedTitle = "[MAAFW] $title"
        submit(context, channel, prefixedTitle, body)
    }

    /**
     * 任务开始推送：只受「通知总开关 + 已选渠道」控制，
     * 由调用方（TaskNotificationCoordinator）根据"任务开始推送"开关决定是否调用。
     */
    fun dispatchStart(context: Context, title: String, body: String) {
        if (!SettingsRepository.isNotificationEnabled(context)) return
        val channel = SettingsRepository.getPushChannel(context)
        if (channel == "none") return
        submit(context, channel, "[MAAFW] $title", body)
    }

    /**
     * 测试单个通道。结果通过回调给出。
     */
    fun test(context: Context, channel: String, onResult: (String) -> Unit) {
        val title = "MAAFW 测试通知"
        val body = "这是一条测试推送"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                deliverSync(context, channel, title, body)
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    private fun submit(context: Context, channel: String, title: String, body: String) {
        val pushChannel = channelFor(context, channel) ?: return
        scope.launch {
            val result = pushChannel.deliver(title, body)
            val message = formatResult(channel, result)
            Ln.i("$TAG: $message")
        }
    }

    private suspend fun deliverSync(
        context: Context,
        channel: String,
        title: String,
        body: String
    ): String {
        val pushChannel = channelFor(context, channel)
            ?: return "未找到通道: $channel"
        return try {
            formatResult(channel, pushChannel.deliver(title, body))
        } catch (e: Exception) {
            "$channel 发送异常: ${e.message}"
        }
    }

    private fun formatResult(channel: String, result: PushResult): String = when (result) {
        is PushResult.Delivered -> "$channel 发送成功"
        is PushResult.Rejected -> "$channel 发送失败: ${result.reason}"
        is PushResult.Retryable -> "$channel 发送异常: ${result.reason}"
    }
}