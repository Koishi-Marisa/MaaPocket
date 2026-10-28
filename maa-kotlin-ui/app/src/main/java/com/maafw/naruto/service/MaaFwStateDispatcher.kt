package com.maafw.naruto.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * MAAFW 连接状态统一收尾（方案 5：UnifiedStateDispatcher 等价，MaaFW 命名）。
 *
 * 观察 [MaaFwConnectionManager.state]，引擎 Died/Error 时统一收尾：
 * 停 GameWatchdog、任务置 ERROR、会话日志收尾、发"服务异常"通知。
 * MainActivity 注入 [setOnDied] 完成具体收尾动作。
 */
object MaaFwStateDispatcher {

    @Volatile private var onDied: (() -> Unit)? = null
    @Volatile private var started = false

    /** 注入引擎死亡统一收尾回调（停守护/置ERROR/日志/通知） */
    fun setOnDied(cb: (() -> Unit)?) {
        onDied = cb
    }

    /** 启动观察（App 初始化时调用一次） */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            MaaFwConnectionManager.state.drop(1).collect { state ->
                when (state) {
                    is MaaFwConnectionManager.State.Died,
                    is MaaFwConnectionManager.State.Error -> {
                        // 统一收尾：停守护、任务置 ERROR、会话日志、通知（由 MainActivity 注入）
                        onDied?.invoke()
                    }
                    else -> Unit
                }
            }
        }
    }

    /** 重置（可选，测试用） */
    fun resetForTest() {
        started = false
        onDied = null
    }
}