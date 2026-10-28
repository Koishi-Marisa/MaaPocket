package com.maafw.naruto.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.lang.reflect.Method

/**
 * 虚拟显示器创建助手喵～
 *  的 VirtualDisplayCore，用反射 DisplayManager.createVirtualDisplay
 * 或 SurfaceControl 底层 fallback 创建虚拟屏喵。
 *
 * 注意：Shizuku 必须以 adb/shell 身份运行，不能用 root，否则 UID 不匹配会抛 SecurityException 喵。
 */
class VirtualDisplayHelper(context: Context) {

    companion object {
        private const val TAG = "VirtualDisplayHelper"
    }

    data class Config(
        var width: Int = 1080,
        var height: Int = 2400,
        var dpi: Int = 320,
        var name: String = "MaaFW_VD"
    )

    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var config = Config()
    private var virtualDisplay: VirtualDisplay? = null
    private var displayId: Int = -1
    private val tokenMap = mutableMapOf<Int, IBinder>()

    private val vdCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() { Log.w(TAG, "VD paused") }
        override fun onResumed() { Log.i(TAG, "VD resumed") }
        override fun onStopped() {
            Log.e(TAG, "VD stopped unexpectedly!")
            displayId = -1
        }
    }

    fun setConfig(cfg: Config) { config = cfg }
    fun getConfig(): Config = config
    fun getDisplayId(): Int = displayId

    /**
     * 创建 VirtualDisplay，surface 
     */
    fun create(surface: Surface): Int {
        if (!surface.isValid) {
            Log.e(TAG, "Surface 无效喵")
            return -1
        }

        // 策略1：反射 DisplayManager.createVirtualDisplay
        var id = createViaReflection(surface)
        if (id >= 0) {
            Log.i(TAG, "VD 通过反射创建成功：displayId=$id")
            return id
        }

        // 策略2：SurfaceControl 底层 fallback
        Log.w(TAG, "策略1失败，尝试 SurfaceControl fallback 喵")
        id = createViaSurfaceControl(surface)
        if (id >= 0) {
            Log.i(TAG, "VD 通过 SurfaceControl 创建成功：displayId=$id")
            return id
        }

        Log.e(TAG, "所有 VD 创建策略都失败喵")
        return -1
    }

    fun release() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放 VD 异常喵：${e.message}")
        }
        virtualDisplay = null
        displayId = -1
        tokenMap.clear()
        Log.i(TAG, "VD 已释放喵")
    }

    // ========== 策略1：反射 DisplayManager ==========

    private fun createViaReflection(surface: Surface): Int {
        return try {
            val method = findCreateVirtualDisplayMethod() ?: return -1
            val result = invokeMethod(method, surface)
            when (result) {
                is VirtualDisplay -> {
                    virtualDisplay = result
                    val display = result.display
                    displayId = display?.displayId ?: -1
                    displayId
                }
                is Int -> {
                    displayId = result
                    displayId
                }
                else -> -1
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SECURITY：${e.message}")
            Log.e(TAG, "请确认 Shizuku 是 adb/shell 模式，不是 root 喵！")
            -1
        } catch (e: Exception) {
            Log.e(TAG, "策略1异常喵：${e.message}")
            -1
        }
    }

    private fun findCreateVirtualDisplayMethod(): Method? {
        val candidates = listOf(
            // 10 参数 + displayIdToMirror + uniqueId (Android 10+)
            arrayOf(
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Surface::class.java, Int::class.javaPrimitiveType,
                VirtualDisplay.Callback::class.java, Handler::class.java, String::class.java
            ),
            // 9 参数 + uniqueId (Android 8+)
            arrayOf(
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Surface::class.java, Int::class.javaPrimitiveType,
                VirtualDisplay.Callback::class.java, Handler::class.java, String::class.java
            ),
            // 8 参数
            arrayOf(
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Surface::class.java, Int::class.javaPrimitiveType,
                VirtualDisplay.Callback::class.java, Handler::class.java
            ),
            // 6 参数
            arrayOf(
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Surface::class.java, Int::class.javaPrimitiveType
            )
        )
        for (types in candidates) {
            try {
                return DisplayManager::class.java.getMethod("createVirtualDisplay", *types)
            } catch (_: NoSuchMethodException) {
            }
        }
        return null
    }

    private fun invokeMethod(method: Method, surface: Surface): Any? {
        val types = method.parameterTypes
        val handler = Handler(Looper.getMainLooper())
        return when (types.size) {
            10 -> method.invoke(
                displayManager,
                config.name, config.width, config.height, config.dpi, 0, surface,
                buildFlags(), vdCallback, handler,
                config.name + "_" + System.currentTimeMillis()
            )
            9 -> method.invoke(
                displayManager,
                config.name, config.width, config.height, config.dpi, surface,
                buildFlags(), vdCallback, handler,
                config.name + "_" + System.currentTimeMillis()
            )
            8 -> method.invoke(
                displayManager,
                config.name, config.width, config.height, config.dpi, surface,
                buildFlags(), vdCallback, handler
            )
            6 -> method.invoke(
                displayManager,
                config.name, config.width, config.height, config.dpi, surface,
                buildFlags()
            )
            else -> null
        }
    }

    // ========== 策略2：SurfaceControl ==========

    private fun createViaSurfaceControl(surface: Surface): Int {
        return try {
            val sc = Class.forName("android.view.SurfaceControl")
            val createDisplay = sc.getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
            val token = createDisplay.invoke(null, config.name, false) as? IBinder
                ?: return -1

            sc.getMethod("openTransaction").invoke(null)
            try {
                sc.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java)
                    .invoke(null, token, surface)
                sc.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, token, 0)
                sc.getMethod(
                    "setDisplayProjection", IBinder::class.java, Int::class.javaPrimitiveType,
                    android.graphics.Rect::class.java, android.graphics.Rect::class.java
                ).invoke(null, token, 0,
                    android.graphics.Rect(0, 0, config.width, config.height),
                    android.graphics.Rect(0, 0, config.width, config.height)
                )
            } finally {
                sc.getMethod("closeTransaction").invoke(null)
            }

            val ids = DisplayManager::class.java.getMethod("getDisplayIds")
                .invoke(displayManager) as? IntArray ?: return -1
            displayId = ids.lastOrNull() ?: -1
            if (displayId >= 0) tokenMap[displayId] = token
            displayId
        } catch (e: Exception) {
            Log.e(TAG, "SurfaceControl fallback 异常喵：${e.message}")
            -1
        }
    }

    // ========== Flags ==========

    private fun buildFlags(): Int {
        var flags = 0
        flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        //  使用的内部 flag，值 = 1 << 6
        flags = flags or (1 shl 6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                flags = flags or DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL").getInt(null)
            } catch (e: Exception) {
                Log.w(TAG, "获取 DESTROY_CONTENT flag 失败喵：${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                flags = flags or DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_TRUSTED").getInt(null)
                flags = flags or DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP").getInt(null)
                flags = flags or DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED").getInt(null)
                //  内部 flag TOUCH_FEEDBACK_DISABLED = 1 << 13
                flags = flags or (1 shl 13)
            } catch (e: Exception) {
                Log.w(TAG, "获取 API33+ flags 失败喵：${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                flags = flags or DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS").getInt(null)
                flags = flags or DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP").getInt(null)
                flags = flags or DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED").getInt(null)
            } catch (e: Exception) {
                Log.w(TAG, "获取 API34+ flags 失败喵：${e.message}")
            }
        }
        Log.i(TAG, "VD flags=0x${Integer.toHexString(flags)}")
        return flags
    }
}