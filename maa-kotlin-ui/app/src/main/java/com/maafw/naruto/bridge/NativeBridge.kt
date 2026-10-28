package com.maafw.naruto.bridge

import android.content.Context
import android.os.SystemClock
import com.maafw.naruto.remote.internal.MaaFwActivityHelper
import com.maafw.naruto.shizuku.InputInjector
import com.maafw.naruto.third.Ln

/**
 * 原生桥的输入驱动回调类
 * libbridge.so 在 JNI_OnLoad 里会反射调用这里的静态方法。
 * 运行环境是 Shizuku UserService 进程（shell UID），所以 am 命令和 InputManager 注入都有权限。
 */
object NativeBridge {

    private var displayId: Int = 0

    @JvmStatic
    fun touchDown(x: Int, y: Int, displayId: Int): Boolean {
        return runCatching { InputInjector.touchDown(x, y, displayId) }.getOrElse { false }
    }

    @JvmStatic
    fun touchMove(x: Int, y: Int, displayId: Int): Boolean {
        return runCatching { InputInjector.touchMove(x, y, displayId) }.getOrElse { false }
    }

    @JvmStatic
    fun touchUp(x: Int, y: Int, displayId: Int): Boolean {
        return runCatching { InputInjector.touchUp(x, y, displayId) }.getOrElse { false }
    }

    @JvmStatic
    fun keyDown(keyCode: Int, displayId: Int): Boolean {
        return runCatching { InputInjector.keyDown(keyCode, displayId) }.getOrElse { false }
    }

    @JvmStatic
    fun keyUp(keyCode: Int, displayId: Int): Boolean {
        return runCatching { InputInjector.keyUp(keyCode, displayId) }.getOrElse { false }
    }
    @JvmStatic
    fun startApp(packageName: String, displayId: Int, forceStop: Boolean): Boolean {
        return runCatching {
            val launched = MaaFwActivityHelper.startApp(packageName, displayId, forceStop, true)
            if (!launched) {
                Ln.e("NativeBridge.startApp: failed to start $packageName on display $displayId")
                return@runCatching false
            }
            Ln.i("NativeBridge.startApp: started $packageName on display $displayId, ensuring it stays there")
            MaaFwActivityHelper.ensureAppOnDisplay(packageName, displayId)
            awaitFirstFrame()
            true
        }.getOrElse { false }
    }

    @JvmStatic
    private fun awaitFirstFrame(timeoutMs: Long = 5000L, pollMs: Long = 50L): Boolean {
        val baseline = BridgeNativeLib.getFrameCount()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (BridgeNativeLib.getFrameCount() > baseline) {
                return true
            }
            SystemClock.sleep(pollMs)
        }
        Ln.w("NativeBridge.awaitFirstFrame: no new frame within ${timeoutMs}ms")
        return false
    }


    @JvmStatic
    fun setDisplayId(id: Int) {
        displayId = id
    }

    @JvmStatic
    fun getDisplayId(): Int = displayId
}