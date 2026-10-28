package com.maafw.naruto.root

import android.util.Log
import java.io.File

/**
 * Root 权限管理器
 *  RootManager.kt，用 Runtime su 替代 libsu（不引入新依赖）。
 */
object RootManager {

    private const val TAG = "RootManager"

    /** 设备上是否存在 su */
    fun isRootAvailable(): Boolean {
        val path = System.getenv("PATH")?.split(":") ?: return false
        for (p in path) {
            val su = File(p, "su")
            if (su.exists()) return true
        }
        return false
    }

    /** 是否已获得 root 授权（su -c id 输出包含 uid=0） */
    fun isRootGranted(): Boolean {
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.contains("uid=0")
        }.getOrDefault(false)
    }

    /** 请求 root 授权（触发 su 弹窗） */
    fun requestRoot(): Boolean {
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            Log.i(TAG, "requestRoot: $out")
            out.contains("uid=0")
        }.getOrDefault(false)
    }
}