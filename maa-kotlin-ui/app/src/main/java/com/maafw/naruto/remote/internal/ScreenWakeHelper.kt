package com.maafw.naruto.remote.internal

import android.os.SystemClock
import android.view.KeyEvent
import com.maafw.naruto.bridge.NativeBridge
import com.maafw.naruto.third.Ln
import com.maafw.naruto.third.wrappers.ServiceManager

/**
 * 屏幕唤醒与锁屏处理助手。
 *
 * 场景：
 * - 硬件熄屏后恢复亮屏（PowerManager.wakeUp 直达，不依赖按键注入策略）；
 * - 定时任务触发时若设备锁屏，自动亮屏并解除锁屏；
 * - 安全锁屏（纯数字 PIN）可自动输入解锁。
 *
 * 全部基于系统公开 AIDL（IPowerManager / IWindowManager）反射实现，
 * 在 Shizuku shell / Root 提权进程内执行。
 */
object ScreenWakeHelper {

    private const val TAG = "ScreenWakeHelper"
    private const val SCREEN_ON_TIMEOUT_MS = 5_000L
    private const val KEYGUARD_GONE_TIMEOUT_MS = 5_000L
    private const val BOUNCER_SETTLE_MS = 1_200L
    private const val DIGIT_GAP_MS = 50L
    private const val POLL_INTERVAL_MS = 100L

    /** 唤醒结果码 */
    const val RESULT_OK = 0
    const val RESULT_WAKE_FAILED = 1
    const val RESULT_UNSUPPORTED = 2
    const val RESULT_CREDENTIAL_REQUIRED = 3
    const val RESULT_CREDENTIAL_REJECTED = 4
    const val RESULT_LOCK_FAILED = 5

    /**
     * 亮屏（物理屏）。
     * @return true=屏幕已亮
     */
    fun wakeUp(): Boolean {
        val pm = ServiceManager.getPowerManager()
        if (pm.isScreenOn(0)) {
            return true
        }
        if (!pm.wakeUp()) {
            Ln.w("$TAG wakeUp() 不可用")
            return false
        }
        val ok = pollUntil(SCREEN_ON_TIMEOUT_MS) { pm.isScreenOn(0) }
        Ln.i("$TAG 亮屏 ${if (ok) "成功" else "超时"}")
        return ok
    }

    /**
     * 亮屏并尝试解锁（自动处理：无凭据锁屏直接解除；纯数字 PIN 安全锁屏自动输入）。
     * @param pin 安全锁屏的纯数字 PIN；无安全锁屏或未配置可传 null/空串
     * @return [RESULT_OK] 或对应失败码
     */
    fun wakeAndUnlock(pin: String? = null): Int {
        if (!wakeUp()) return RESULT_WAKE_FAILED

        val wm = ServiceManager.getWindowManager()
        val locked = wm.isKeyguardLocked() ?: return RESULT_OK // API 不支持视为可继续
        if (!locked) return RESULT_OK

        val secure = wm.isKeyguardSecure(0) ?: false

        // 无凭据锁屏：直接 dismissKeyguard
        if (!secure) {
            return if (wm.dismissKeyguard() && pollUntil(KEYGUARD_GONE_TIMEOUT_MS) {
                    wm.isKeyguardLocked() == false
                }
            ) {
                Ln.i("$TAG 无凭据锁屏已解除")
                RESULT_OK
            } else {
                Ln.w("$TAG 无凭据锁屏解除失败")
                RESULT_LOCK_FAILED
            }
        }

        // 安全锁屏：需要 PIN
        val credential = pin?.trim() ?: ""
        if (credential.isEmpty() || credential.any { !it.isDigit() }) {
            Ln.w("$TAG 安全锁屏需纯数字 PIN（当前未提供）")
            return RESULT_CREDENTIAL_REQUIRED
        }

        if (!wm.dismissKeyguard()) {
            Ln.w("$TAG dismissKeyguard 不可用")
            return RESULT_UNSUPPORTED
        }

        // bouncer 弹出期间 isKeyguardLocked 仍为 true，等待稳定再注入
        Thread.sleep(BOUNCER_SETTLE_MS)

        val displayId = MaaFwVirtualDisplay.getDisplayId().takeIf { it >= 0 } ?: 0
        for (c in credential) {
            val keyCode = KeyEvent.KEYCODE_0 + (c - '0')
            NativeBridge.keyDown(keyCode, displayId)
            NativeBridge.keyUp(keyCode, displayId)
            Thread.sleep(DIGIT_GAP_MS)
        }
        // 部分 ROM 会自动提交；补 ENTER 兼容需确认的 PIN
        NativeBridge.keyDown(KeyEvent.KEYCODE_ENTER, displayId)
        NativeBridge.keyUp(KeyEvent.KEYCODE_ENTER, displayId)

        return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked() == false }) {
            Ln.i("$TAG PIN 解锁成功")
            RESULT_OK
        } else {
            // 不重试，避免连续输错触发系统锁定
            Ln.w("$TAG PIN 解锁失败（PIN 错误或注入被忽略）")
            RESULT_CREDENTIAL_REJECTED
        }
    }

    /** 亮屏（仅亮屏不解锁，供「硬件熄屏恢复」等场景） */
    fun wakeOnly(): Boolean = wakeUp()

    private inline fun pollUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cond()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return cond()
    }
}