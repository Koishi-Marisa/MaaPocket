package com.maafw.naruto.shizuku

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.maafw.naruto.third.ShellContext
import com.maafw.naruto.third.Ln
import com.maafw.naruto.third.wrappers.InputManager
import com.maafw.naruto.third.wrappers.ServiceManager

/**
 * 输入注入器
 * 在 shell 进程通过 InputManager.injectInputEvent 向指定 displayId 注入触摸/按键事件。
 *
 * 修复「DispatchInputMessage failed ret=-1」卡死：
 * - 触摸用 ASYNC 模式（WAIT_FOR_FINISH 在部分 ROM 会失败/卡住）
 * - 注入失败自动重试 3 次
 * - 坐标 clamp 到 display 范围内
 * - 注入成功广播 TOUCH_EVENT（供预览显示脚本触摸位置；用户手动触摸不经此路径）
 */
object InputInjector {

    private const val DEFAULT_DEVICE_ID = 0
    private const val DEFAULT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN
    private const val TOUCH_EVENT_ACTION = "com.maafw.naruto.TOUCH_EVENT"

    private val pointerProps = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }
    private val pointerCoords = MotionEvent.PointerCoords()
    private val pointerPropsArray = arrayOf(pointerProps)
    private val pointerCoordsArray = arrayOf(pointerCoords)

    private var currentDownTime = 0L

    private val inputManager by lazy { ServiceManager.getInputManager() }

    /**
     * 同进程触摸回调（RemoteEngineServiceImpl 设置，binder 直达 App 供触摸预览）。
     * 调用方需保证异步 + 限流，避免阻塞触摸注入（Swipe 卡死根因）。
     */
    @Volatile
    var onInjectedTouch: ((action: Int, x: Int, y: Int) -> Unit)? = null

    private fun setPointerCoords(x: Float, y: Float, pressure: Float) {
        pointerCoords.x = kotlin.math.max(0f, x)
        pointerCoords.y = kotlin.math.max(0f, y)
        pointerCoords.pressure = pressure
        pointerCoords.size = 1.0f
    }

    private fun obtainEvent(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, pressure: Float): MotionEvent {
        setPointerCoords(x, y, pressure)
        return MotionEvent.obtain(
            downTime, eventTime, action,
            1, pointerPropsArray, pointerCoordsArray,
            0, 0, 1.0f, 1.0f,
            DEFAULT_DEVICE_ID, 0, DEFAULT_SOURCE, 0
        )
    }

    private fun inject(event: InputEvent, displayId: Int, mode: Int): Boolean {
        InputManager.setDisplayId(event, displayId)
        return inputManager.injectInputEvent(event, mode)
    }

    /** 注入 + 失败重试（最多 3 次） */
    private fun injectWithRetry(event: InputEvent, displayId: Int, mode: Int): Boolean {
        var ok = inject(event, displayId, mode)
        var attempt = 0
        while (!ok && attempt < 3) {
            Thread.sleep(30)
            ok = inject(event, displayId, mode)
            attempt++
        }
        if (!ok) Ln.e("InputInjector: injectInputEvent failed displayId=$displayId")
        return ok
    }

    private fun broadcastTouch(action: Int, x: Int, y: Int) {
        try {
            val intent = Intent(TOUCH_EVENT_ACTION).apply {
                putExtra("action", action)
                putExtra("x", x)
                putExtra("y", y)
            }
            ShellContext.get().sendBroadcast(intent)
        } catch (e: Exception) {
            Ln.w("broadcastTouch failed: ${e.message}")
        }
    }

    @JvmStatic
    @Synchronized
    fun touchDown(x: Int, y: Int, displayId: Int): Boolean {
        if (currentDownTime != 0L) {
            val cancel = obtainEvent(currentDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x.toFloat(), y.toFloat(), 0f)
            inject(cancel, displayId, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        }
        currentDownTime = SystemClock.uptimeMillis()
        val event = obtainEvent(currentDownTime, currentDownTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 1f)
        val ok = injectWithRetry(event, displayId, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        event.recycle()
        if (ok) {
            broadcastTouch(MotionEvent.ACTION_DOWN, x, y)
            onInjectedTouch?.invoke(MotionEvent.ACTION_DOWN, x, y)
        }
        return ok
    }

    @JvmStatic
    @Synchronized
    fun touchMove(x: Int, y: Int, displayId: Int): Boolean {
        if (currentDownTime == 0L) return false
        val event = obtainEvent(currentDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat(), 1f)
        val ok = inject(event, displayId, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        event.recycle()
        if (ok) {
            broadcastTouch(MotionEvent.ACTION_MOVE, x, y)
            onInjectedTouch?.invoke(MotionEvent.ACTION_MOVE, x, y)
        }
        return ok
    }

    @JvmStatic
    @Synchronized
    fun touchUp(x: Int, y: Int, displayId: Int): Boolean {
        if (currentDownTime == 0L) return false
        val event = obtainEvent(currentDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0f)
        val ok = injectWithRetry(event, displayId, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        event.recycle()
        currentDownTime = 0L
        if (ok) {
            broadcastTouch(MotionEvent.ACTION_UP, x, y)
            onInjectedTouch?.invoke(MotionEvent.ACTION_UP, x, y)
        }
        return ok
    }

    @JvmStatic
    fun keyDown(keyCode: Int, displayId: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val event = android.view.KeyEvent(downTime, downTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
        InputManager.setDisplayId(event, displayId)
        return inputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
    }

    @JvmStatic
    fun keyUp(keyCode: Int, displayId: Int): Boolean {
        val upTime = SystemClock.uptimeMillis()
        val event = android.view.KeyEvent(upTime, upTime, android.view.KeyEvent.ACTION_UP, keyCode, 0)
        InputManager.setDisplayId(event, displayId)
        return inputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
    }
}