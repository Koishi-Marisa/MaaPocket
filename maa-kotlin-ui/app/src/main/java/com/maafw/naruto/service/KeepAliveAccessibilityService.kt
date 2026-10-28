package com.maafw.naruto.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍防杀服务（非 root 保活）。
 *
 * 原理：启用无障碍服务的 App 会被系统视为"用户重要"（oom_adj 自动降低），
 * ColorOS/各家 ROM 后台清理时也不会杀无障碍进程——从而保住 App 进程，
 * 让引擎被回收后能立即重连并自动恢复任务。
 *
 * 本服务不做任何无障碍逻辑（纯空实现），仅用于标记进程重要性。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeepAliveAccessibility"
        /** 服务完整 ID（引擎 shell 代授无障碍时写入 secure settings 用） */
        const val SERVICE_ID = "com.maafw.naruto/.service.KeepAliveAccessibilityService"

        private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
        /** 无障碍服务是否已连接（UI 实时观察开启状态） */
        val isConnected: kotlinx.coroutines.flow.StateFlow<Boolean> =
            _isConnected.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true
        Log.i(TAG, "无障碍防杀服务已连接（App 进程已受系统保护）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 空实现：不需要任何无障碍能力
    }

    override fun onInterrupt() {
        // 空实现
    }
}