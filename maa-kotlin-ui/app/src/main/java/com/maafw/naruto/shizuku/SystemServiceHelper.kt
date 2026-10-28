package com.maafw.naruto.shizuku

import android.content.Context
import android.hardware.input.InputManager
import android.os.IBinder
import android.util.Log
import android.view.WindowManager

/**
 * 通过 Shizuku 环境反射拿到系统服务的辅助类
 * 因为我们是 shell/adb 身份，可以直接访问 ServiceManager 里的隐藏服务。
 */
object SystemServiceHelper {

    private const val TAG = "SystemServiceHelper"

    /**
     * 反射获取 InputManager 实例。
     * 官方 Context.INPUT_SERVICE 在普通应用里拿不到注入权限，
     * 这里直接从 ServiceManager.getService("input") 构造。
     */
    fun getInputManager(): InputManager? {
        return try {
            val binder = getServiceBinder(Context.INPUT_SERVICE) ?: return null
            val cls = Class.forName("android.hardware.input.InputManager")
            val ctor = cls.getDeclaredConstructor(IBinder::class.java)
            ctor.isAccessible = true
            ctor.newInstance(binder) as InputManager
        } catch (e: Exception) {
            Log.e(TAG, "获取 InputManager 失败：${e.message}")
            null
        }
    }

    /**
     * 反射获取 WindowManager 服务。
     */
    fun getWindowManager(): WindowManager? {
        return try {
            val binder = getServiceBinder(Context.WINDOW_SERVICE) ?: return null
            val cls = Class.forName("android.view.WindowManagerImpl")
            val ctor = cls.getDeclaredConstructor(IBinder::class.java)
            ctor.isAccessible = true
            ctor.newInstance(binder) as WindowManager
        } catch (e: Exception) {
            Log.e(TAG, "获取 WindowManager 失败：${e.message}")
            null
        }
    }

    /**
     * 通用获取 IBinder 。
     */
    fun getServiceBinder(name: String): IBinder? {
        return try {
            val cls = Class.forName("android.os.ServiceManager")
            val method = cls.getMethod("getService", String::class.java)
            method.invoke(null, name) as? IBinder
        } catch (e: Exception) {
            Log.e(TAG, "获取 $name 失败：${e.message}")
            null
        }
    }
}