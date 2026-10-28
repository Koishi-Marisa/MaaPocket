package com.maafw.naruto.capture

import android.graphics.Bitmap

/**
 * 截图提供者接口喵～
 * 引擎只依赖这个接口，底层可以是 VirtualDisplay、SurfaceControl.screenshot 或者 mock 喵。
 */
interface ScreenshotProvider {
    /** 启动截图源，返回是否成功喵 */
    fun start(): Boolean

    /** 停止截图源喵 */
    fun stop()

    /** 获取当前帧 Bitmap，没有则返回 null 喵 */
    fun capture(): Bitmap?

    /** 返回目标 Display ID，输入注入要用它喵 */
    fun getDisplayId(): Int

    /** 是否正在运行喵 */
    fun isRunning(): Boolean
}
