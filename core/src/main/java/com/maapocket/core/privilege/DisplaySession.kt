package com.maapocket.core.privilege

import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import com.maapocket.core.bridge.NativeBridgeLib
import com.maapocket.core.constant.AndroidVersions
import com.maapocket.core.constant.DefaultDisplayConfig
import com.maapocket.core.third.Ln
import com.maapocket.core.third.wrappers.DisplayManager
import com.maapocket.core.third.wrappers.ServiceManager
import com.maapocket.core.third.wrappers.SurfaceControl
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 特权进程里的**屏幕会话**：建虚拟屏 / 镜像主屏，并把 Surface 交给 native capturer。
 *
 * 这是 MAA-Meow `remote/internal/VirtualDisplayManager.kt`（199 行）与
 * `remote/internal/PrimaryDisplayManager.kt`（161 行）的合并简化版：
 *
 * - 两个 object 合并成一个类，用 [Mode] 选择；
 * - 去掉 `monitorSurface`（那是预览用的，MaaPocket 里由
 *   `NativeBridgeLib.setPreviewSurface` 直接接管，见 `RemoteEngine`）；
 * - 去掉 `FrameCaptureHelper.createCaptureHandler`，DisplayListener 直接挂主 Looper
 *   （`RemoteMain` 已经 `Looper.loop()`，主 Looper 一定在跑）。
 *
 * **保留下来的、经过实机验证的低层技巧**（不要随手改）：
 * 1. `createNewVirtualDisplay` 走反射构造的 `DisplayManager(Context)`，Context 来自
 *    scrcpy 的 `FakeContext`（不是 package Context）——见 `RemoteMain` 头部注释。
 * 2. 虚拟屏建好后若 `rotation != ROTATION_0` 就 `freezeRotation(vdId, ROTATION_0)`；
 *    横屏原生设备（AYN Odin2 之类定制 ROM）对二级屏的 freezeRotation 无效，所以
 *    **物理屏旋转就是 0** 时再补一刀 `setForcedDisplaySize`。
 * 3. 主屏镜像优先用 `DisplayManager.createVirtualDisplay(name,w,h,DISPLAY_ID,surface)`，
 *    失败才退回 `SurfaceControl.createDisplay` + 手工把 surface/projection/layerStack 接上。
 */
class DisplaySession(
    private val mode: Mode = Mode.VIRTUAL,
    /** 尺寸变化时的回调（`RemoteEngine` 用它发 `display.changed` 事件）。 */
    private val onDisplayChanged: ((displayId: Int) -> Unit)? = null,
) {

    enum class Mode {
        /** 独立的虚拟屏（游戏跑在新 display 上，可固定 16:9）。 */
        VIRTUAL,

        /** 镜像主屏 display 0（MAA-Meow 的 `PrimaryDisplayManager` 路径）。 */
        PRIMARY_MIRROR,
    }

    data class Config(
        val width: Int = DefaultDisplayConfig.WIDTH,
        val height: Int = DefaultDisplayConfig.HEIGHT,
        val dpi: Int = DefaultDisplayConfig.DPI,
    )

    private val config = AtomicReference(Config())

    private val displayIdRef = AtomicInteger(DefaultDisplayConfig.DISPLAY_NONE)

    private val virtualDisplay = AtomicReference<VirtualDisplay?>(null)

    /** 只给 [Mode.PRIMARY_MIRROR] 的 `SurfaceControl` 回退路径用。 */
    private val surfaceControlDisplay = AtomicReference<android.os.IBinder?>(null)

    private val state = AtomicInteger(STATE_IDLE)

    private val listenerRegistered = AtomicBoolean(false)

    private var listenerHandle: DisplayManager.DisplayListenerHandle? = null

    /** MAA-Meow `PrimaryDisplayManager` 只在尺寸变化时重建，所以记一下上次看到的尺寸。 */
    private val lastMirrorSize = AtomicReference<Pair<Int, Int>?>(null)

    val displayId: Int get() = displayIdRef.get()

    val isCapturing: Boolean get() = state.get() == STATE_CAPTURING

    // ------------------------------------------------------------------ 起停

    /** 起屏。返回 displayId，失败返回 [DefaultDisplayConfig.DISPLAY_NONE]。 */
    fun start(): Int {
        if (!state.compareAndSet(STATE_IDLE, STATE_CAPTURING)) {
            // 已经在跑（或正在跑）：幂等返回现有 id，避免重复建屏把上一个泄漏掉。
            Ln.d("DisplaySession: start ignored, state=${state.get()}")
            return displayIdRef.get()
        }
        return try {
            if (mode == Mode.PRIMARY_MIRROR) startMirrorInternal() else startVirtualInternal()
        } catch (e: Throwable) {
            Ln.e("DisplaySession: start failed (mode=$mode)", e)
            state.set(STATE_IDLE)
            releaseResources()
            DefaultDisplayConfig.DISPLAY_NONE
        }
    }

    fun stop() {
        if (state.getAndSet(STATE_IDLE) == STATE_IDLE) return
        Ln.i("DisplaySession: stopping (display=${displayIdRef.get()})")
        unregisterDisplayListener()
        releaseResources()
    }

    /** 重建：先释放再建。旋转/尺寸变化后由 [setResolution] 或 DisplayListener 触发。 */
    fun restart(): Int {
        stop()
        return start()
    }

    /**
     * 改分辨率。返回是否真的重建了。
     * 与 MAA-Meow 一致：**只有正在 capturing 时才重建**，否则只记下配置。
     */
    fun setResolution(width: Int, height: Int, dpi: Int = config.get().dpi): Boolean {
        val next = Config(width.coerceAtLeast(1), height.coerceAtLeast(1), dpi.coerceAtLeast(1))
        val old = config.getAndSet(next)
        if (old == next) return false
        Ln.i("DisplaySession: resolution ${old.width}x${old.height}@${old.dpi} -> ${next.width}x${next.height}@${next.dpi}")
        if (state.get() == STATE_CAPTURING) {
            restart()
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ 虚拟屏（Mode.VIRTUAL）

    private fun startVirtualInternal(): Int {
        val cfg = config.get()
        val surface = setupCapturer(cfg) ?: return DefaultDisplayConfig.DISPLAY_NONE
        val flags = buildDisplayFlags()

        val vd = ServiceManager.getDisplayManager()
            .createNewVirtualDisplay(DefaultDisplayConfig.VD_NAME, cfg.width, cfg.height, cfg.dpi, surface, flags)
            ?: throw IllegalStateException("createNewVirtualDisplay returned null")

        virtualDisplay.set(vd)
        val id = vd.display.displayId
        displayIdRef.set(id)

        val actual = runCatching { ServiceManager.getDisplayManager().getDisplayInfo(id) }.getOrNull()
        Ln.i(
            "DisplaySession: virtual display id=$id configured=${cfg.width}x${cfg.height}@${cfg.dpi} " +
                "actual=${actual?.size()?.width()}x${actual?.size()?.height()} rotation=${actual?.rotation()} " +
                "flags=0x${Integer.toHexString(flags)}"
        )

        freezeRotationIfNeeded(id, actual?.rotation() ?: Surface.ROTATION_0, cfg)
        return id
    }

    /**
     * 建屏后强制摆正。抄自 `VirtualDisplayManager.createVirtualDisplay`（MAA-Meow 199 行版）：
     * 若虚拟屏没以 `ROTATION_0` 起来就先 freeze；若物理屏本身就是 0（横屏原生设备），
     * 定制 ROM 常常无视二级屏的 freezeRotation，于是再把显示尺寸按配置钉死。
     *
     * 两步都**只警告不抛**：摆不正也能跑，只是截图会转 90°。
     */
    private fun freezeRotationIfNeeded(displayId: Int, rotation: Int, cfg: Config) {
        if (rotation == Surface.ROTATION_0) return
        val wm = runCatching { ServiceManager.getWindowManager() }.getOrNull()
        if (wm == null) {
            Ln.w("DisplaySession: window manager unavailable, cannot freeze rotation of display $displayId")
            return
        }
        val physicalRotation = runCatching { wm.rotation }.getOrDefault(-1)
        try {
            wm.freezeRotation(displayId, Surface.ROTATION_0)
            Ln.i("DisplaySession: froze rotation of display $displayId to ROTATION_0 (was $rotation)")
        } catch (e: Throwable) {
            Ln.w("DisplaySession: freezeRotation($displayId, 0) failed: ${e.message}", e)
        }
        if (physicalRotation == Surface.ROTATION_0) {
            // 横屏原生设备（AYN Odin2 等）：freezeRotation 对二级屏无效，只能钉尺寸。
            try {
                wm.setForcedDisplaySize(displayId, cfg.width, cfg.height)
                Ln.i("DisplaySession: forced size of display $displayId to ${cfg.width}x${cfg.height}")
            } catch (e: Throwable) {
                Ln.w("DisplaySession: setForcedDisplaySize($displayId) failed: ${e.message}", e)
            }
        }
    }

    /**
     * 逐位抄自 `VirtualDisplayManager.buildDisplayFlags()`，**顺序和条件都不要动**：
     *
     * - 基：`PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH`；
     * - `DESTROY_CONTENT_ON_REMOVAL`：对应 `VD_DESTROY_CONTENT=true`；
     * - 不加 `SHOULD_SHOW_SYSTEM_DECORATIONS`：对应 `VD_SYSTEM_DECORATIONS=false`；
     * - API 33+：`TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED`
     *   （ALWAYS_UNLOCKED 让虚拟屏不跟随锁屏，否则锁屏后截图全黑）；
     * - API 34+：`OWN_FOCUS | DEVICE_DISPLAY_GROUP | STEAL_TOP_FOCUS_DISABLED`。
     *
     * 用 `AndroidVersions.API_*` 而不是裸 `Build.VERSION_CODES`，和仓内其它代码一致。
     */
    private fun buildDisplayFlags(): Int {
        var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH
        flags = flags or FLAG_DESTROY_CONTENT_ON_REMOVAL

        val sdk = android.os.Build.VERSION.SDK_INT
        if (sdk >= AndroidVersions.API_33_ANDROID_13) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
                FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (sdk >= AndroidVersions.API_34_ANDROID_14) {
            flags = flags or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP or FLAG_STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }

    // ------------------------------------------------------------------ 主屏镜像（Mode.PRIMARY_MIRROR）

    private fun startMirrorInternal(): Int {
        ensureDisplayListener()
        return createMirrorDisplay()
    }

    private fun createMirrorDisplay(): Int {
        val info = ServiceManager.getDisplayManager().getDisplayInfo(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("cannot read DisplayInfo of default display")
        val w = info.size().width()
        val h = info.size().height()
        lastMirrorSize.set(w to h)

        val cfg = Config(w, h, info.dpi())
        config.set(cfg)

        val surface = setupCapturer(cfg) ?: return DefaultDisplayConfig.DISPLAY_NONE
        val name = DefaultDisplayConfig.VD_NAME

        // 首选：让系统直接建一个镜像 display 0 的虚拟屏。
        val direct = runCatching {
            ServiceManager.getDisplayManager().createVirtualDisplay(name, w, h, Display.DEFAULT_DISPLAY, surface)
        }.getOrElse {
            Ln.w("DisplaySession: mirror via DisplayManager failed, falling back to SurfaceControl: ${it.message}", it)
            null
        }
        if (direct != null) {
            virtualDisplay.set(direct)
            val id = direct.display.displayId
            displayIdRef.set(id)
            Ln.i("DisplaySession: mirror display id=$id ${w}x$h (layerStack=${info.layerStack()})")
            return id
        }

        // 回退：手工造 display，再把 surface/projection/layerStack 接上去。
        // 这一整套必须包在 openTransaction/closeTransaction 里，否则 SurfaceFlinger 侧不可见。
        val token = SurfaceControl.createDisplay(name, false)
            ?: throw IllegalStateException("SurfaceControl.createDisplay returned null")
        surfaceControlDisplay.set(token)
        val rect = info.size().toRect()
        try {
            SurfaceControl.openTransaction()
            SurfaceControl.setDisplaySurface(token, surface)
            SurfaceControl.setDisplayProjection(token, 0, rect, rect)
            SurfaceControl.setDisplayLayerStack(token, info.layerStack())
        } finally {
            SurfaceControl.closeTransaction()
        }
        // SurfaceControl 造出来的 display 没有 Java 侧 VirtualDisplay 对象，
        // 它的 id 只能从 DisplayManager 按名字反查（MAA-Meow 同样如此）。
        val id = findDisplayIdByName(name)
        displayIdRef.set(id)
        Ln.i("DisplaySession: mirror display (surface-control path) id=$id ${w}x$h")
        return id
    }

    private fun findDisplayIdByName(name: String): Int {
        val ids = runCatching { ServiceManager.getDisplayManager().getDisplayIds() }.getOrNull()
            ?: return DefaultDisplayConfig.DISPLAY_NONE
        for (id in ids) {
            if (id == Display.DEFAULT_DISPLAY) continue
            val info = runCatching { ServiceManager.getDisplayManager().getDisplayInfo(id) }.getOrNull() ?: continue
            if (info.uniqueId().contains(name, ignoreCase = true)) return id
        }
        Ln.w("DisplaySession: could not resolve display id for '$name'")
        return DefaultDisplayConfig.DISPLAY_NONE
    }

    /**
     * 只注册一次 DisplayListener。MAA-Meow 的 `PrimaryDisplayManager` 只用它处理
     * **尺寸变化**（旋转变化不重建，因为镜像屏跟着物理屏走）。
     */
    private fun ensureDisplayListener() {
        if (!listenerRegistered.compareAndSet(false, true)) return
        try {
            val handler = Handler(Looper.getMainLooper())
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) = onDisplayChange(displayId)
            }
            listenerHandle = ServiceManager.getDisplayManager().registerDisplayListener(listener, handler)
            Ln.i("DisplaySession: display listener registered")
        } catch (e: Throwable) {
            listenerRegistered.set(false)
            // 监听失败不影响镜像本身，只是尺寸变化后不会自动重建。
            Ln.w("DisplaySession: registerDisplayListener failed: ${e.message}", e)
        }
    }

    private fun unregisterDisplayListener() {
        if (!listenerRegistered.compareAndSet(true, false)) return
        val handle = listenerHandle ?: return
        listenerHandle = null
        runCatching { ServiceManager.getDisplayManager().unregisterDisplayListener(handle) }
            .onFailure { Ln.w("DisplaySession: unregisterDisplayListener failed: ${it.message}") }
    }

    private fun onDisplayChange(changedId: Int) {
        if (changedId != Display.DEFAULT_DISPLAY) return
        val info = runCatching { ServiceManager.getDisplayManager().getDisplayInfo(Display.DEFAULT_DISPLAY) }
            .getOrNull() ?: return
        val size = info.size().width() to info.size().height()
        val old = lastMirrorSize.get()
        if (old != null && old != size) {
            Ln.i("DisplaySession: default display resized $old -> $size, restarting mirror")
            runCatching { restart() }.onFailure { Ln.e("DisplaySession: mirror restart failed", it) }
        }
        runCatching { onDisplayChanged?.invoke(displayIdRef.get()) }
    }

    // ------------------------------------------------------------------ 公共资源

    private fun setupCapturer(cfg: Config): Surface? {
        if (!NativeBridgeLib.LOADED) {
            // 没有 native capturer 就没有可用的 Surface，建屏没有意义（会建出一个黑屏）。
            Ln.e("DisplaySession: NativeBridgeLib not loaded, cannot set up capturer")
            return null
        }
        return NativeBridgeLib.setupNativeCapturer(cfg.width, cfg.height)
    }

    private fun releaseResources() {
        virtualDisplay.getAndSet(null)?.let {
            runCatching { it.release() }.onFailure { e -> Ln.w("DisplaySession: VirtualDisplay.release failed: ${e.message}") }
        }
        // 只有 SurfaceControl 回退路径会填这个 token（那条路径上根本没有 VirtualDisplay 对象），
        // 所以两条路径互斥，各自释放自己造出来的 display，不会重复 destroy。
        surfaceControlDisplay.getAndSet(null)?.let {
            runCatching { SurfaceControl.destroyDisplay(it) }
                .onFailure { e -> Ln.w("DisplaySession: destroyDisplay failed: ${e.message}") }
        }
        if (NativeBridgeLib.LOADED) {
            runCatching { NativeBridgeLib.releaseNativeCapturer() }
                .onFailure { e -> Ln.w("DisplaySession: releaseNativeCapturer failed: ${e.message}") }
        }
        displayIdRef.set(DefaultDisplayConfig.DISPLAY_NONE)
        lastMirrorSize.set(null)
    }

    companion object {
        private const val STATE_IDLE = 0
        private const val STATE_CAPTURING = 1

        // android.hardware.display.DisplayManager 的常量在不同 API 上并非全部公开，
        // 这里按 MAA-Meow 的做法写死数值，注释给出对应的官方常量名。
        private const val FLAG_PUBLIC = 1 shl 0 // VIRTUAL_DISPLAY_FLAG_PUBLIC
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3 // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6 // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8 // VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
        private const val FLAG_TRUSTED = 1 shl 10 // VIRTUAL_DISPLAY_FLAG_TRUSTED (API 33)
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11 // VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP (API 33)
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12 // VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED (API 33)
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13 // VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED (API 33)
        private const val FLAG_OWN_FOCUS = 1 shl 14 // VIRTUAL_DISPLAY_FLAG_OWN_FOCUS (API 34)
        private const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15 // VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP (API 34)
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16 // VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED (API 34)
    }
}
