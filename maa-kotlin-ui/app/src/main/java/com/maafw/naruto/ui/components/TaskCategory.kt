package com.maafw.naruto.ui.components

import com.maafw.naruto.model.MaaTask

/**
 * 任务分类
 * 根据 MAAFW-Narutomobile-main 的 interface.json 任务名做的手机端分类。
 */
enum class TaskCategory(val title: String, val icon: String) {
    START("启动退出", "power_settings_new"),
    DAILY("日常任务", "today"),
    PVP("决斗PVP", "sports_mma"),
    DUNGEON("副本挑战", "dungeon"),
    RESOURCE("资源商店", "store"),
    ACCOUNT("账号工具", "manage_accounts"),
    SYSTEM("系统调试", "settings_suggest")
}

/**
 * 根据任务 entry 判断分类。
 */
fun MaaTask.category(): TaskCategory {
    return when (this.entry) {
        "start_up", "exit_naruto" -> TaskCategory.START
        "weekly_win", "more_gameplay", "stronghold", "sky_ground", "rebel_ninja" -> TaskCategory.PVP
        "shugyou_no_michi", "survival_challenge", "team_dash", "use_energy",
        "secret_realm", "advanture", "elite_instance", "asura_instance", "mini_game" -> TaskCategory.DUNGEON
        "mission_office", "rich_room", "ninja_book", "shop", "buy_energy",
        "black_market_merchant", "get_copper" -> TaskCategory.RESOURCE
        "switch_account", "secondary_password_open" -> TaskCategory.ACCOUNT
        "debug", "cleanup_maafw_bak_logs" -> TaskCategory.SYSTEM
        else -> TaskCategory.DAILY
    }
}

fun List<MaaTask>.groupByCategory(): Map<TaskCategory, List<MaaTask>> {
    return this.groupBy { it.category() }
}
