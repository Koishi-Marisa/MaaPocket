package com.maafw.naruto.schedule.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 国产 ROM 自启动设置页面引导
 *  AutoStartHelper.kt。
 */
object AutoStartHelper {

    private val AUTOSTART_INTENTS = listOf(
        // Xiaomi MIUI
        Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        ),
        // OPPO ColorOS
        Intent().setComponent(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
        ),
        // OPPO
        Intent().setComponent(
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        ),
        // Vivo OriginOS / FuntouchOS
        Intent().setComponent(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        ),
        // Huawei EMUI / HarmonyOS
        Intent().setComponent(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        ),
        // Honor (独立后)
        Intent().setComponent(
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        ),
        // Samsung
        Intent().setComponent(
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.lool.activity.applist.AppListActivity"
            )
        ),
    )

    /**
     * 获取当前设备厂商的自启动设置 Intent。
     * 如果当前设备不是已知的国产 ROM 或 Intent 无法解析，返回 null。
     */
    fun getAutoStartIntent(context: Context): Intent? {
        return AUTOSTART_INTENTS.firstOrNull { intent ->
            context.packageManager.resolveActivity(intent, 0) != null
        }
    }

    /**
     * 是否为已知需要自启动引导的厂商
     */
    fun isKnownRestrictiveManufacturer(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer in setOf(
            "xiaomi", "redmi", "oppo", "realme", "oneplus",
            "vivo", "iqoo", "huawei", "honor", "samsung",
            "meizu", "smartisan", "letv", "zte", "nubia"
        )
    }
}