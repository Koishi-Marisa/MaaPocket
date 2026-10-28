package com.maafw.naruto.remote.internal

import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Surface
import com.maafw.naruto.third.Ln
import com.maafw.naruto.third.wrappers.ServiceManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 虚拟显示器管理喵。
 * 在 Shizuku UserService（shell 进程）里创建 VD。
 *
 * ：默认 1280x720@160，所有隐藏 flag 用本地常量避免反射失败。
 */
object VirtualDisplayManager {

    private const val STATE_IDLE = 0
    private const val STATE_CAPTURING = 1

    // Android SDK 公开 flag
    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

    // 隐藏 flag，值和 AOSP 保持一致（）
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH: Int = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT: Int = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL: Int = 1 shl 8
    private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS: Int = 1 shl 9
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED: Int = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP: Int = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED: Int = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED: Int = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS: Int = 1 shl 14
    private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP: Int = 1 shl 15
    private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED: Int = 1 shl 16

    private const val VD_DESTROY_CONTENT = true
    private const val VD_SYSTEM_DECORATIONS = false
    private const val VD_NAME = "MaaFW_VD"

    data class DisplayConfig(
        val width: Int = 1280,
        val height: Int = 720,
        val dpi: Int = 160
    )

    private val state = AtomicInteger(STATE_IDLE)
    private val config = AtomicReference(DisplayConfig())
    private val displayId = AtomicInteger(-1)
    private val virtualDisplay = AtomicReference<android.hardware.display.VirtualDisplay?>(null)
    private val monitorSurface = AtomicReference<Surface?>(null)

    fun setMonitorSurface(surface: Surface?) {
        monitorSurface.set(surface)
        Ln.i("setMonitorSurface: ${surface != null}")
    }

    fun setResolution(width: Int, height: Int, dpi: Int = config.get().dpi) {
        val newConfig = DisplayConfig(width, height, dpi)
        val oldConfig = config.getAndSet(newConfig)
        if (state.get() == STATE_CAPTURING && oldConfig != newConfig) {
            Ln.i("Resolution changed: ${oldConfig.width}x${oldConfig.height} -> ${width}x${height}, restart")
            stop()
            start()
        }
    }

    fun start(): Int {
        if (!state.compareAndSet(STATE_IDLE, STATE_CAPTURING)) {
            Ln.w("start: already capturing")
            return displayId.get()
        }
        return startInternal()
    }

    fun stop() {
        if (!state.compareAndSet(STATE_CAPTURING, STATE_IDLE)) {
            return
        }
        releaseResources()
    }

    fun getDisplayId(): Int = displayId.get()

    fun getConfig(): DisplayConfig = config.get()

    private fun startInternal(): Int {
        val cfg = config.get()
        Ln.i("VirtualDisplayManager startInternal ${cfg.width}x${cfg.height}@${cfg.dpi}")
        return try {
            Ln.i("[VD-STEP] setupNativeCapturer(${cfg.width}, ${cfg.height})...")
            val surface = com.maafw.naruto.bridge.NativeBridgeLib.setupNativeCapturer(cfg.width, cfg.height)
                ?: throw RuntimeException("setupNativeCapturer returned null")
            Ln.i("[VD-STEP] setupNativeCapturer succeeded, valid=${surface.isValid}")
            Ln.i("[VD-STEP] createVirtualDisplay...")
            createVirtualDisplay(surface, cfg, fullFlags = true)
            val id = displayId.get()
            Ln.i("VirtualDisplayManager started, displayId=$id")
            id
        } catch (e: Throwable) {
            Ln.e("VirtualDisplayManager start failed", e)
            state.set(STATE_IDLE)
            releaseResources()
            // 抛出详细信息，远端服务会把它通过广播发回 UI
            throw RuntimeException("[${e.javaClass.simpleName}] ${e.message}", e)
        }
    }

    private fun releaseResources() {
        try {
            virtualDisplay.getAndSet(null)?.release()
        } catch (e: Exception) {
            Ln.e("release virtualDisplay failed: ${e.message}")
        }
        com.maafw.naruto.bridge.NativeBridgeLib.releaseNativeCapturer()
        displayId.set(-1)
    }

    private fun createVirtualDisplay(surface: Surface, cfg: DisplayConfig, fullFlags: Boolean = true) {
        val flags = if (fullFlags) buildFlags() else buildSafeFlags()
        val wm = ServiceManager.getWindowManager()
        val physicalRotation = runCatching { wm.getRotation() }.getOrDefault(-1)
        Ln.i("Physical display rotation: $physicalRotation, flags=0x${Integer.toHexString(flags)}")

        val dm = ServiceManager.getDisplayManager()
        try {
            val vd = dm.createNewVirtualDisplay(VD_NAME, cfg.width, cfg.height, cfg.dpi, surface, flags)
            virtualDisplay.set(vd)
            val vdId = vd.display.displayId
            displayId.set(vdId)
            Ln.i("VirtualDisplay created: displayId=$vdId")
            // 新虚拟屏默认处于熄灭状态（Android 14+ 必须显式 requestDisplayPower 点亮）。
            // 不点亮的话 SurfaceFlinger 不渲染该 display：截图/预览黑屏，
            // MaaFramework screencap 拿不到帧，管线会卡死在 start_up，StartApp 永不执行喵。
            runCatching {
                dm.requestDisplayPower(vdId, true)
                Ln.i("VirtualDisplay powered on: displayId=$vdId")
            }.onFailure { Ln.w("requestDisplayPower failed: ${it.message}") }
        } catch (e: Exception) {
            if (fullFlags) {
                Ln.w("createVirtualDisplay with full flags failed: ${e.message}, retry with safe flags")
                createVirtualDisplay(surface, cfg, fullFlags = false)
            } else {
                throw e
            }
        }
    }

    private fun buildSafeFlags(): Int {
        var flags = 0
        flags = flags or VIRTUAL_DISPLAY_FLAG_PUBLIC
        flags = flags or VIRTUAL_DISPLAY_FLAG_PRESENTATION
        flags = flags or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        flags = flags or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
        flags = flags or VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
        }
        Ln.i("VD safe flags=0x${Integer.toHexString(flags)}")
        return flags
    }

    private fun buildFlags(): Int {
        var flags = 0
        flags = flags or VIRTUAL_DISPLAY_FLAG_PUBLIC
        flags = flags or VIRTUAL_DISPLAY_FLAG_PRESENTATION
        flags = flags or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        flags = flags or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
        flags = flags or VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (VD_DESTROY_CONTENT) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_TRUSTED
            flags = flags or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
            flags = flags or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
            flags = flags or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
            if (VD_SYSTEM_DECORATIONS) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
            flags = flags or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
            flags = flags or VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
        }
        Ln.i("VD flags=0x${Integer.toHexString(flags)}")
        return flags
    }
}