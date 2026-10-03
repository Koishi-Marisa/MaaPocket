package com.maapocket.core.maafw

import android.graphics.Bitmap
import com.sun.jna.Pointer

/**
 * Kotlin wrappers over MaaFramework's opaque buffer handles.
 *
 * Each class follows the same shape: `create()` allocates the native object, `close()`
 * (`Maa*Destroy`) frees it. All of them are [AutoCloseable] so call sites read as
 * `MaaStringBuffer.create(api).use { ... }` and a thrown framework call cannot leak.
 *
 * The `Get*` accessors return **borrowed** pointers into the framework's own storage — never
 * destroy a handle obtained from `MaaStringListBufferAt` / `MaaImageBufferGetRawData` /
 * `MaaImageBufferGetEncoded`.
 */
class MaaStringBuffer private constructor(
    private val api: MaaFrameworkApi,
    val handle: Pointer,
) : AutoCloseable {

    val isEmpty: Boolean get() = api.MaaStringBufferIsEmpty(handle).toBool()
    val size: Long get() = api.MaaStringBufferSize(handle)

    fun get(): String = api.MaaStringBufferGet(handle) ?: ""

    fun set(value: String): Boolean = api.MaaStringBufferSet(handle, value).toBool()

    override fun close() = api.MaaStringBufferDestroy(handle)

    companion object {
        fun create(api: MaaFrameworkApi): MaaStringBuffer {
            val handle = api.MaaStringBufferCreate()
                ?: throw IllegalStateException("MaaStringBufferCreate() returned null")
            return MaaStringBuffer(api, handle)
        }

        /**
         * Allocates a buffer, hands it to [block] and destroys it afterwards.
         *
         * This exists because most call sites are "fill a buffer, hand its pointer to a `Get*`
         * function, read it back" and spelling that out three times invites a forgotten destroy.
         */
        inline fun <T> scoped(api: MaaFrameworkApi, block: (MaaStringBuffer) -> T): T =
            create(api).use(block)
    }
}

/** A framework-owned list of strings, used for node lists and custom-action name lists. */
class MaaStringListBuffer private constructor(
    private val api: MaaFrameworkApi,
    val handle: Pointer,
) : AutoCloseable {

    val isEmpty: Boolean get() = api.MaaStringListBufferIsEmpty(handle).toBool()
    val size: Long get() = api.MaaStringListBufferSize(handle)

    /** Borrowed child buffer — do not close it. */
    fun handleAt(index: Long): Pointer? = api.MaaStringListBufferAt(handle, index)

    fun at(index: Long): String? = handleAt(index)?.let { api.MaaStringBufferGet(it) }

    fun toList(): List<String> = (0 until size).mapNotNull { at(it) }

    fun append(value: MaaStringBuffer): Boolean = api.MaaStringListBufferAppend(handle, value.handle).toBool()
    fun remove(index: Long): Boolean = api.MaaStringListBufferRemove(handle, index).toBool()
    fun clear(): Boolean = api.MaaStringListBufferClear(handle).toBool()

    override fun close() = api.MaaStringListBufferDestroy(handle)

    companion object {
        fun create(api: MaaFrameworkApi): MaaStringListBuffer {
            val handle = api.MaaStringListBufferCreate()
                ?: throw IllegalStateException("MaaStringListBufferCreate() returned null")
            return MaaStringListBuffer(api, handle)
        }

        /** Builds a list buffer from plain Kotlin strings, for [MaaResource.overrideNext]. */
        fun of(api: MaaFrameworkApi, values: List<String>): MaaStringListBuffer {
            val buffer = create(api)
            try {
                values.forEach { value ->
                    MaaStringBuffer.create(api).use { item ->
                        item.set(value)
                        buffer.append(item)
                    }
                }
            } catch (t: Throwable) {
                buffer.close()
                throw t
            }
            return buffer
        }
    }
}

/**
 * A framework image buffer (an OpenCV `cv::Mat` behind the scenes).
 *
 * MaaFramework writes screenshots as **packed BGR** (`CV_8UC3`) or BGRA (`CV_8UC4`), matching
 * OpenCV's channel order rather than Android's. [toBitmap] does the swap; everything else is a
 * thin passthrough.
 */
class MaaImageBuffer private constructor(
    private val api: MaaFrameworkApi,
    val handle: Pointer,
) : AutoCloseable {

    val isEmpty: Boolean get() = api.MaaImageBufferIsEmpty(handle).toBool()
    val width: Int get() = api.MaaImageBufferWidth(handle)
    val height: Int get() = api.MaaImageBufferHeight(handle)
    val channels: Int get() = api.MaaImageBufferChannels(handle)

    /** OpenCV `Mat::type()`, e.g. `16` (`CV_8UC3`) or `24` (`CV_8UC4`). */
    val type: Int get() = api.MaaImageBufferType(handle)

    fun clear(): Boolean = api.MaaImageBufferClear(handle).toBool()

    /** Borrowed pointer to the `cv::Mat`'s pixel storage. */
    fun rawData(): Pointer? = api.MaaImageBufferGetRawData(handle)

    /** PNG/JPEG bytes as produced by `cv::imencode`; borrowed. Empty when the framework has none. */
    fun encoded(): ByteArray {
        val data = api.MaaImageBufferGetEncoded(handle) ?: return ByteArray(0)
        val size = api.MaaImageBufferGetEncodedSize(handle)
        if (size <= 0L || size > Int.MAX_VALUE) return ByteArray(0)
        return data.getByteArray(0, size.toInt())
    }

    /**
     * Copies the pixels out and converts BGR(A) → Android's ARGB.
     *
     * Only intended for the in-app screen preview and bug reports — it allocates a pixel array
     * the size of the frame, so it must not be called per recognition cycle.
     */
    fun toBitmap(): Bitmap? {
        if (isEmpty) return null
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val raw = rawData() ?: return null
        val c = channels
        if (c < 3) return null

        val bytes = raw.getByteArray(0, w * h * c)
        val pixels = IntArray(w * h)
        var s = 0
        for (i in pixels.indices) {
            val b = bytes[s].toInt() and 0xFF
            val g = bytes[s + 1].toInt() and 0xFF
            val r = bytes[s + 2].toInt() and 0xFF
            val a = if (c >= 4) bytes[s + 3].toInt() and 0xFF else 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            s += c
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() = api.MaaImageBufferDestroy(handle)

    companion object {
        fun create(api: MaaFrameworkApi): MaaImageBuffer {
            val handle = api.MaaImageBufferCreate()
                ?: throw IllegalStateException("MaaImageBufferCreate() returned null")
            return MaaImageBuffer(api, handle)
        }
    }
}

/** A `MaaRect` — recognition results carry one rectangle each. */
class MaaRect internal constructor(
    private val api: MaaFrameworkApi,
    val handle: Pointer,
) : AutoCloseable {

    val x: Int get() = api.MaaRectGetX(handle)
    val y: Int get() = api.MaaRectGetY(handle)
    val w: Int get() = api.MaaRectGetW(handle)
    val h: Int get() = api.MaaRectGetH(handle)

    fun set(x: Int, y: Int, w: Int, h: Int): Boolean = api.MaaRectSet(handle, x, y, w, h).toBool()

    override fun close() = api.MaaRectDestroy(handle)

    override fun toString(): String = "MaaRect($x, $y, $w x $h)"

    companion object {
        fun create(api: MaaFrameworkApi): MaaRect {
            val handle = api.MaaRectCreate()
                ?: throw IllegalStateException("MaaRectCreate() returned null")
            return MaaRect(api, handle)
        }
    }
}

/** A borrowed `MaaRect` owned by the framework — never destroyed by the caller. */
class MaaRectView internal constructor(
    private val api: MaaFrameworkApi,
    val handle: Pointer,
) {
    val x: Int get() = api.MaaRectGetX(handle)
    val y: Int get() = api.MaaRectGetY(handle)
    val w: Int get() = api.MaaRectGetW(handle)
    val h: Int get() = api.MaaRectGetH(handle)

    override fun toString(): String = "MaaRect($x, $y, $w x $h)"
}
