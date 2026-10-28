package com.maafw.naruto.bridge

import android.graphics.Bitmap
import android.view.Surface

/**
 * 原生桥业务封装（MaaFW 命名）。
 *
 * 注意：libbridge.so 的 JNI 符号基于 NativeBridgeLib 类名（原生库不可重编译约束），
 * 因此真正的 native 方法在 [NativeBridgeLib]（JNI 桥）声明，本类仅做委托封装，
 * 业务代码统一调用本类，避免直接依赖 JNI 桥。
 */
object BridgeNativeLib {

    @JvmField
    var LOADED: Boolean = false

    init {
        LOADED = try {
            System.loadLibrary("bridge")
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JvmStatic
    fun ping(): String = NativeBridgeLib.ping()

    @JvmStatic
    fun setupNativeCapturer(width: Int, height: Int): Surface? = NativeBridgeLib.setupNativeCapturer(width, height)

    @JvmStatic
    fun releaseNativeCapturer() = NativeBridgeLib.releaseNativeCapturer()

    @JvmStatic
    fun setPreviewSurface(surface: Any?) = NativeBridgeLib.setPreviewSurface(surface)

    @JvmStatic
    fun getFrameBufferBitmap(): Bitmap = NativeBridgeLib.getFrameBufferBitmap()

    @JvmStatic
    fun getFrameCount(): Long = NativeBridgeLib.getFrameCount()

    @JvmStatic
    fun getFps(): Double = NativeBridgeLib.getFps()

    @JvmStatic
    fun getScriptFps(): Double = NativeBridgeLib.getScriptFps()

    /**
     * 切换进程 UID（root 引擎发广播前临时降权到 App uid，发完提权回 root）。
     * @return setresuid 返回值（0=成功）
     */
    @JvmStatic
    fun setResUid(ruid: Int, euid: Int, suid: Int): Int = NativeBridgeLib.setResUid(ruid, euid, suid)
}