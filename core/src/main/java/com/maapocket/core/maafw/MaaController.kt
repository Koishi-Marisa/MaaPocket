package com.maapocket.core.maafw

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * A MaaFramework controller.
 *
 * MaaPocket only ever creates the **Android Native** controller, and it is created inside the
 * privileged helper process because the external lib behind it (`libbridge.so`) needs the
 * virtual-display and input-injection hidden APIs. Everything in this class therefore runs in
 * that process; the app process reaches it through the remote session.
 *
 * `screen_resolution` handed to the framework must equal the frame size the bridge actually
 * produces. The framework does not rescale on this path — a mismatch fails screencap immediately.
 */
class MaaController internal constructor(
    private val api: MaaFrameworkApi,
    val handle: Pointer,
) : AutoCloseable {

    private val sinkIds = ArrayList<Long>(2)

    /** Whether `MaaControllerPostConnection` has completed successfully. */
    val connected: Boolean get() = api.MaaControllerConnected(handle).toBool()

    // ------------------------------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------------------------------

    /** `MaaCtrlOption_ScreenshotTargetShortSide` — the framework rescales captures to this. */
    fun setScreenshotTargetShortSide(pixels: Int): Boolean =
        setIntOption(MaaDef.MaaCtrlOption_ScreenshotTargetShortSide, pixels)

    fun setScreenshotTargetLongSide(pixels: Int): Boolean =
        setIntOption(MaaDef.MaaCtrlOption_ScreenshotTargetLongSide, pixels)

    /**
     * `MaaCtrlOption_ScreenshotUseRawSize` — hand the frame through at its native size.
     *
     * MaaPocket sets this: the Android Native controller builds its virtual display at exactly the
     * resolution the resource pack was authored for, so letting the framework rescale would only
     * add resampling noise.
     */
    fun setScreenshotUseRawSize(enabled: Boolean): Boolean =
        setBoolOption(MaaDef.MaaCtrlOption_ScreenshotUseRawSize, enabled)

    fun setMouseLockFollow(enabled: Boolean): Boolean =
        setBoolOption(MaaDef.MaaCtrlOption_MouseLockFollow, enabled)

    fun setScreenshotTargetExpand(enabled: Boolean): Boolean =
        setBoolOption(MaaDef.MaaCtrlOption_ScreenshotTargetExpand, enabled)

    private fun setIntOption(key: Int, value: Int): Boolean {
        val mem = Memory(4)
        mem.setInt(0, value)
        return api.MaaControllerSetOption(handle, key, mem, 4).toBool()
    }

    private fun setBoolOption(key: Int, value: Boolean): Boolean {
        val mem = Memory(1)
        mem.setByte(0, if (value) 1 else 0)
        return api.MaaControllerSetOption(handle, key, mem, 1).toBool()
    }

    // ------------------------------------------------------------------------------------------
    // Sinks
    // ------------------------------------------------------------------------------------------

    /** Registers a callback. Keep the returned callback reachable — see [MaaCallbackRegistry]. */
    fun addSink(callback: MaaEventCallback): Long {
        val id = api.MaaControllerAddSink(handle, callback, null)
        sinkIds.add(id)
        return id
    }

    fun removeSink(sinkId: Long, callback: MaaEventCallback) {
        api.MaaControllerRemoveSink(handle, sinkId)
        sinkIds.remove(sinkId)
        MaaCallbackRegistry.release(callback)
    }

    // ------------------------------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------------------------------

    fun postConnection(): Long = api.MaaControllerPostConnection(handle)

    fun postClick(x: Int, y: Int, contact: Int = 0, pressure: Int = 1): Long =
        api.MaaControllerPostClickV2(handle, x, y, contact, pressure)

    fun postLongPress(x: Int, y: Int, durationMs: Int, contact: Int = 0): Long = postSwipe(
        x, y, x, y, durationMs, contact,
    )

    fun postSwipe(
        x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int,
        contact: Int = 0, pressure: Int = 1,
    ): Long = api.MaaControllerPostSwipeV2(handle, x1, y1, x2, y2, durationMs, contact, pressure)

    fun postScroll(dx: Int, dy: Int): Long = api.MaaControllerPostScroll(handle, dx, dy)

    fun postKeyDown(keycode: Int): Long = api.MaaControllerPostKeyDown(handle, keycode)
    fun postKeyUp(keycode: Int): Long = api.MaaControllerPostKeyUp(handle, keycode)

    /** Convenience pair; the framework has no combined "press key" post. */
    fun postClickKey(keycode: Int): Long = api.MaaControllerPostClickKey(handle, keycode)

    fun postInputText(text: String): Long = api.MaaControllerPostInputText(handle, text)

    /** `intent` is a package name or `package/activity`; see the framework's `StartApp` docs. */
    fun postStartApp(intent: String): Long = api.MaaControllerPostStartApp(handle, intent)
    fun postStopApp(intent: String): Long = api.MaaControllerPostStopApp(handle, intent)

    fun postInactive(): Long = api.MaaControllerPostInactive(handle)
    fun postScreencap(): Long = api.MaaControllerPostScreencap(handle)

    fun status(id: Long): Int = api.MaaControllerStatus(handle, id)

    // ------------------------------------------------------------------------------------------
    // Waiting & capture
    // ------------------------------------------------------------------------------------------

    /**
     * Polls until [id] settles, returning the final `MaaStatus`.
     *
     * Deliberately not `MaaControllerWait`, which blocks with no timeout: if the helper process
     * dies mid-action the caller would hang forever. Returns [MaaDef.MaaStatus_Invalid] on timeout.
     */
    fun await(id: Long, timeoutMs: Long = 60_000L): Int {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val status = api.MaaControllerStatus(handle, id)
            if (MaaDef.isTerminal(status)) return status
            if (System.nanoTime() >= deadline) return MaaDef.MaaStatus_Invalid
            Thread.sleep(10L)
        }
    }

    /** `true` when the action identified by [id] reached [MaaDef.MaaStatus_Succeeded]. */
    fun awaitSucceeded(id: Long, timeoutMs: Long = 60_000L): Boolean =
        await(id, timeoutMs) == MaaDef.MaaStatus_Succeeded

    /** Copies the most recent captured frame. `null` when nothing has been captured yet. */
    fun cachedImage(): MaaImageBuffer? {
        val buffer = MaaImageBuffer.create(api)
        val ok = api.MaaControllerCachedImage(handle, buffer.handle).toBool()
        if (!ok) {
            buffer.close()
            return null
        }
        return buffer
    }

    /** Captures a fresh frame and returns it. `null` on timeout or failure. */
    fun screencap(timeoutMs: Long = 30_000L): MaaImageBuffer? {
        val id = postScreencap()
        if (!awaitSucceeded(id, timeoutMs)) return null
        return cachedImage()
    }

    /** Raw `MaaControllerGetInfo` JSON, as reported by the control unit. */
    fun info(): String = MaaStringBuffer.scoped(api) { buffer ->
        if (api.MaaControllerGetInfo(handle, buffer.handle).toBool()) buffer.get() else ""
    }

    /** The display's current resolution, or `null` when the control unit cannot report one. */
    fun resolution(): Pair<Int, Int>? {
        val w = IntByReference()
        val h = IntByReference()
        if (!api.MaaControllerGetResolution(handle, w, h).toBool()) return null
        return w.value to h.value
    }

    fun uuid(): String = MaaStringBuffer.scoped(api) { buffer ->
        if (api.MaaControllerGetUuid(handle, buffer.handle).toBool()) buffer.get() else ""
    }

    override fun close() {
        api.MaaControllerClearSinks(handle)
        sinkIds.clear()
        api.MaaControllerDestroy(handle)
    }

    companion object {
        /**
         * Creates the Android Native controller.
         *
         * [bridgeLibraryPath] is the absolute path of our external lib inside the helper's native
         * library directory. Build the JSON with [MaaFw.androidNativeControllerConfig] so the
         * resolution bookkeeping stays in one place.
         */
        fun createAndroidNative(api: MaaFrameworkApi, configJson: String): MaaController {
            val handle = api.MaaAndroidNativeControllerCreate(configJson)
                ?: throw IllegalStateException(
                    "MaaAndroidNativeControllerCreate() returned null. Check that " +
                        "libMaaAndroidNativeControlUnit.so is present and that screen_resolution " +
                        "matches the frame size the bridge produces.",
                )
            return MaaController(api, handle)
        }
    }
}
