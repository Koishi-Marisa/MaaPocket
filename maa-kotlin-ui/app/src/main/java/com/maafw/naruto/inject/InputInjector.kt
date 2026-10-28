package com.maafw.naruto.inject

import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 输入注入器喵～负责把点击、滑动、按键打到目标 Display 上喵。
 * 核心就是反射调用 InputManager.injectInputEvent()，并设置 displayId 喵。
 */
class InputInjector(private val inputManager: InputManager) {

    companion object {
        private const val TAG = "InputInjector"
    }

    var targetDisplayId: Int = 0

    fun tap(x: Int, y: Int): Boolean {
        return injectTouch(MotionEvent.ACTION_DOWN, x, y) &&
                sleep(50) &&
                injectTouch(MotionEvent.ACTION_UP, x, y)
    }

    fun longPress(x: Int, y: Int, durationMs: Int): Boolean {
        return injectTouch(MotionEvent.ACTION_DOWN, x, y) &&
                sleep(durationMs) &&
                injectTouch(MotionEvent.ACTION_UP, x, y)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val steps = maxOf(5, durationMs / 10)
        for (i in 0..steps) {
            val progress = i.toFloat() / steps
            val cx = (x1 + (x2 - x1) * progress).toInt()
            val cy = (y1 + (y2 - y1) * progress).toInt()
            when (i) {
                0 -> if (!injectTouch(MotionEvent.ACTION_DOWN, cx, cy)) return false
                steps -> if (!injectTouch(MotionEvent.ACTION_UP, cx, cy)) return false
                else -> if (!injectTouch(MotionEvent.ACTION_MOVE, cx, cy)) return false
            }
            sleep(durationMs / steps)
        }
        return true
    }

    fun sendKey(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        return injectEvent(down) && injectEvent(up)
    }

    private fun injectTouch(action: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x.toFloat()
            this.y = y.toFloat()
            pressure = 1.0f
            size = 1.0f
        }
        val event = MotionEvent.obtain(
            now, now, action, 1,
            arrayOf(pointer), arrayOf(coords),
            0, 0, 1.0f, 1.0f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        setDisplayId(event, targetDisplayId)
        return injectEvent(event)
    }

    private fun setDisplayId(event: InputEvent, displayId: Int) {
        if (displayId <= 0) return
        try {
            val method = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            method.invoke(event, displayId)
        } catch (e: Exception) {
            Log.w(TAG, "设置 displayId 失败喵：${e.message}")
        }
    }

    private fun injectEvent(event: InputEvent): Boolean {
        return try {
            val method = InputManager::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
            val result = method.invoke(inputManager, event, 0) as Boolean
            if (!result) Log.w(TAG, "injectInputEvent 返回 false")
            result
        } catch (e: Exception) {
            Log.e(TAG, "注入事件失败喵：${e.message}")
            false
        }
    }

    private fun sleep(ms: Int): Boolean {
        return try {
            Thread.sleep(ms.toLong())
            true
        } catch (_: InterruptedException) {
            false
        }
    }
}