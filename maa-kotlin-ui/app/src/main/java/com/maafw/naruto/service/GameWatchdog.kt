package com.maafw.naruto.service

import android.os.SystemClock
import android.util.Log
import com.maafw.naruto.IRemoteEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 运行期守护（P0-5）：后台任务运行期间，每 5s 检查游戏进程存活 + 是否还在虚拟屏上。
 * - 游戏进程死亡 -> 上报（由调用方决定停止/提示）；
 * - 游戏漂移出虚拟屏 -> 5s 宽限期后拉回（moveAppToVirtualDisplay），上限 3 次，回屏后计数清零。
 * 设计要点（参考 06 号报告）：
 * - REPIN_GRACE_MS=5s 宽限期：游戏启动后的 SDK 登录/鉴权弹窗可能导致瞬时"离开虚拟屏"，避免误拉回打断登录；
 * - MAX_REPIN=3 上限：连续拉回失败即停（拉回会重投放启动 intent，无限重试等于每 5s 投放一次）。
 */
class GameWatchdog(
    private val remote: IRemoteEngineService,
    private val packageName: String,
    private val onGameDied: () -> Unit = {},
    private val onDrift: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "GameWatchdog"
        private const val POLL_MS = 5_000L
        private const val REPIN_GRACE_MS = 5_000L
        private const val MAX_REPIN = 3
    }

    private var job: Job? = null
    private var driftFirstSeen = 0L
    private var repinAttempts = 0
    private var driftNotified = false
    /** 游戏死亡连续确认计数（连续 2 次=10s 才判定死亡，防启动中/瞬间消失误报） */
    private var deadCount = 0

    fun start(scope: CoroutineScope) {
        if (job != null) return
        driftFirstSeen = 0L
        repinAttempts = 0
        driftNotified = false
        deadCount = 0
        job = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val alive = runCatching { remote.isAppAlive(packageName) }.getOrDefault(2)
                if (alive == 1) {
                    // 死亡确认：连续 2 次（10s）才判定，避免游戏启动中进程短暂消失误报
                    deadCount++
                    if (deadCount >= 2) {
                        Log.w(TAG, "游戏进程已死亡（连续确认 $deadCount 次，$packageName）")
                        onGameDied()
                        break
                    }
                } else {
                    deadCount = 0
                }
                if (alive != 0) continue
                // 漂移检测：游戏是否还在虚拟屏
                val onDisplay = runCatching { remote.isAppOnVirtualDisplay(packageName) }.getOrDefault(true)
                if (!onDisplay) {
                    tryRepin()
                } else {
                    driftFirstSeen = 0L
                    repinAttempts = 0
                    driftNotified = false
                }
            }
        }
    }

    private suspend fun tryRepin() {
        if (repinAttempts >= MAX_REPIN) {
            if (!driftNotified) {
                driftNotified = true
                onDrift("游戏已离开虚拟显示器，连续拉回失败（已达上限），请检查")
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (driftFirstSeen == 0L) {
            driftFirstSeen = now
            return
        }
        if (now - driftFirstSeen < REPIN_GRACE_MS) return
        val ok = runCatching { remote.moveAppToVirtualDisplay(packageName) }.getOrDefault(false)
        if (ok) {
            Log.i(TAG, "游戏已拉回虚拟屏")
            driftFirstSeen = 0L
            repinAttempts = 0
        } else {
            repinAttempts++
            Log.w(TAG, "拉回游戏失败（${repinAttempts}/$MAX_REPIN）")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}