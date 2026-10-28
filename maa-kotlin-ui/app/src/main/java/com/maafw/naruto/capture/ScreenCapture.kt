package com.maafw.naruto.capture

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

/**
 * 屏幕截图喵～基于 ImageReader，把 VirtualDisplay 的画面抓成 Bitmap 喵。
 */
class ScreenCapture(width: Int, height: Int) : ImageReader.OnImageAvailableListener {

    companion object {
        private const val TAG = "ScreenCapture"
    }

    private var width = width
    private var height = height
    private var format = PixelFormat.RGBA_8888
    private val maxImages = 5

    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureSurface: Surface? = null

    private val latestBitmap = AtomicReference<Bitmap>()
    private var running = false

    val surface: Surface? get() = captureSurface

    fun setResolution(w: Int, h: Int) {
        width = w
        height = h
        if (running) {
            stop()
            start()
        }
    }

    fun start(): Surface? {
        if (running) return captureSurface
        running = true

        captureThread = HandlerThread("ScreenCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = try {
            ImageReader.newInstance(width, height, format, maxImages)
        } catch (e: Exception) {
            Log.w(TAG, "RGBA_8888 失败，回退 YUV_420_888 喵：${e.message}")
            format = ImageFormat.YUV_420_888
            ImageReader.newInstance(width, height, format, maxImages)
        }
        imageReader?.setOnImageAvailableListener(this, captureHandler)
        captureSurface = imageReader?.surface
        Log.i(TAG, "截图启动：${width}x${height} format=$format")
        return captureSurface
    }

    fun stop() {
        running = false
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        captureSurface = null
        latestBitmap.getAndSet(null)?.recycle()
        Log.i(TAG, "截图停止喵")
    }

    fun capture(): Bitmap? {
        val bmp = latestBitmap.get()
        return bmp?.let {
            if (it.isRecycled) null else it.copy(it.config ?: Bitmap.Config.ARGB_8888, false)
        }
    }

    fun isRunning() = running

    override fun onImageAvailable(reader: ImageReader?) {
        var image: Image? = null
        try {
            image = reader?.acquireLatestImage() ?: return
            val bitmap = imageToBitmap(image)
            latestBitmap.getAndSet(bitmap)?.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "取帧失败喵：${e.message}")
        } finally {
            image?.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
}