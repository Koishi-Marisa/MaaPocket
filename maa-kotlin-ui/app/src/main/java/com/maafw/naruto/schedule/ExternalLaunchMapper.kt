package com.maafw.naruto.schedule

import android.content.Intent

/**
 * D1：外部 Intent 联动（Tasker / MacroDroid 等自动化 App 触发任务）。
 *
 * 用法（Tasker -> 发送 Intent / 启动应用）：
 * - action: com.maafw.naruto.LAUNCH_PROFILE
 * - extra: profile_name（配置名，默认 "default"）
 * - extra: force_start（true=先停已有任务再强制开始）
 * - extra: auto_sleep（true=任务结束自动熄屏）
 * - extra: close_game（true=任务结束关闭游戏）
 */
object ExternalLaunchMapper {

    const val ACTION_LAUNCH_PROFILE = "com.maafw.naruto.LAUNCH_PROFILE"
    const val EXTRA_PROFILE_NAME = "profile_name"
    const val EXTRA_FORCE_START = "force_start"
    const val EXTRA_AUTO_SLEEP = "auto_sleep"
    const val EXTRA_CLOSE_GAME = "close_game"

    data class LaunchRequest(
        val profileName: String,
        val forceStart: Boolean,
        val autoSleep: Boolean,
        val closeGame: Boolean
    )

    /** 是否为外部启动 Intent（action 匹配） */
    fun isLaunchProfileIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_LAUNCH_PROFILE

    /** 从外部 Intent 提取启动请求；非外部启动返回 null */
    fun fromExternalIntent(intent: Intent?): LaunchRequest? {
        if (intent?.action != ACTION_LAUNCH_PROFILE) return null
        return LaunchRequest(
            profileName = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "default",
            forceStart = intent.getBooleanExtra(EXTRA_FORCE_START, false),
            autoSleep = intent.getBooleanExtra(EXTRA_AUTO_SLEEP, false),
            closeGame = intent.getBooleanExtra(EXTRA_CLOSE_GAME, false)
        )
    }
}