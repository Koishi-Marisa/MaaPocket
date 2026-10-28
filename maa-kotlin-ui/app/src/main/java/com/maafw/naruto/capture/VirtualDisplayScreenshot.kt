package com.maafw.naruto.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import com.maafw.naruto.bridge.NativeBridgeLib

/**
 * 基于 VirtualDisplay + libbridge.so 的截图提供者喵～
 * 这是  路线：用 Shizuku 创建虚拟屏，把画面直接投给 bridge 的 Surface，
 * MaaFramework 通过 AndroidNativeControlUnit 从 bridge 里取帧喵。
 */
class VirtualDisplayScreenshot(context: Context) : ScreenshotProvider {

    companion object {
        private const val TAG = "VirtualDisplayScreenshot"
        // MAAFW-Narutomobile-main 推荐分辨率：平板 1920x1080 横屏喵
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val DEFAULT_DPI = 320
    }

    private val context = context.applicationContext
    private val helper = VirtualDisplayHelper(context)

    override fun start(): Boolean {
        stop()

        // 严格
        var cfg = VirtualDisplayHelper.Config(width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT, dpi = DEFAULT_DPI, name = "MaaFW_VD")
        helper.setConfig(cfg)

        val surface = NativeBridgeLib.setupNativeCapturer(cfg.width, cfg.height)
        if (surface == null) {
            Log.e(TAG, "Bridge 截图 Surface 创建失败喵，libbridge.so 可能未正确加载")
            return false
        }
        Log.i(TAG, "Bridge 截图 Surface 已创建：${cfg.width}x${cfg.height}")

        var displayId = helper.create(surface)
        if (displayId < 0) {
            // fallback：按真实屏幕分辨率喵，同时重新创建同分辨率的 Surface
            Log.w(TAG, "1920x1080 VD 创建失败，回退到设备真实分辨率喵")
            NativeBridgeLib.releaseNativeCapturer()
            val dm = context.applicationContext.resources.displayMetrics
            val realSize = android.graphics.Point()
            val displayManager = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(realSize)
            val w = realSize.x.takeIf { it > 0 } ?: DEFAULT_WIDTH
            val h = realSize.y.takeIf { it > 0 } ?: DEFAULT_HEIGHT
            cfg = VirtualDisplayHelper.Config(width = w, height = h, dpi = dm.densityDpi.takeIf { it > 0 } ?: DEFAULT_DPI, name = "MaaFW_VD_FB")
            helper.setConfig(cfg)
            val fallbackSurface = NativeBridgeLib.setupNativeCapturer(cfg.width, cfg.height)
            if (fallbackSurface == null) {
                Log.e(TAG, "回退分辨率 Surface 创建失败喵")
                return false
            }
            Log.i(TAG, "回退 Surface 已创建：${cfg.width}x${cfg.height}")
            displayId = helper.create(fallbackSurface)
        }

        if (displayId < 0) {
            Log.e(TAG, "VirtualDisplay 创建失败喵，请确认 Shizuku 为 adb/shell 模式并已授权")
            NativeBridgeLib.releaseNativeCapturer()
            return false
        }

        // 给一点初始化时间喵
        Thread.sleep(300)
        Log.i(TAG, "截图提供者已启动：displayId=$displayId ${cfg.width}x${cfg.height}")
        return true
    }

    override fun stop() {
        helper.release()
        NativeBridgeLib.releaseNativeCapturer()
    }

    override fun capture(): Bitmap? = NativeBridgeLib.getFrameBufferBitmap()

    override fun getDisplayId(): Int = helper.getDisplayId()

    override fun isRunning(): Boolean = helper.getDisplayId() >= 0
}