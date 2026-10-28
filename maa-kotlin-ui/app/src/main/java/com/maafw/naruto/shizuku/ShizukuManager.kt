package com.maafw.naruto.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 小管家
 * 负责 Shizuku 检查/授权/状态监听。
 * 
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 42

    private lateinit var appContext: Context
    private var initialized = false
    private val listeners = CopyOnWriteArraySet<ShizukuStateListener>()
    private val observingState = AtomicBoolean(false)

    interface ShizukuStateListener {
        fun onRequestPermissionResult(granted: Boolean)
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Log.i(TAG, "ShizukuManager 初始化完成，当前 binder 存活=${Shizuku.pingBinder()} ")
    }

    fun applicationContext(): Context = appContext

    /** Shizuku 是否可用（binder 存活） */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e(TAG, "pingBinder 异常：${e.message}")
            false
        }
    }

    /** 是否已授权 */
    fun isReady(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /** 老版本 Shizuku（v11 前）无需授权，直接可用 */
    fun isPreV11(): Boolean {
        return try {
            Shizuku.isPreV11()
        } catch (_: Exception) {
            false
        }
    }
/** Shizuku 是否以 root 身份运行（uid 0，如 Root 授权启动的 Shizuku） */
    fun isRunningAsRoot(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.getUid() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Shizuku 应用是否已安装 */
    fun isAppInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * D6：检测 Sui（Magisk 的 Shizuku 模块）是否已安装——root 用户可免装 Shizuku App。
     * 轻量检测（模块目录存在即可，无需引入 rikka.sui 依赖）。
     */
    fun isSuiInstalled(): Boolean {
        return runCatching {
            val d1 = java.io.File("/data/adb/modules/sui")
            val d2 = java.io.File("/data/adb/modules/sui/main")
            (d1.exists() && d1.isDirectory) || (d2.exists() && d2.isDirectory)
        }.getOrDefault(false)
    }

    /**
     * 请求 Shizuku 权限
     * @param callback 是否成功授权（true=可用，false=不可用）
     */
    fun requestPermission(context: Context, callback: ((Boolean) -> Unit)? = null) {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku 没有运行，无法请求权限")
            callback?.invoke(false)
            return
        }
        if (isPreV11()) {
            Log.i(TAG, "Shizuku 为老版本（v11 前），无需授权")
            callback?.invoke(true)
            return
        }
        if (isReady()) {
            Log.i(TAG, "Shizuku 权限已经授予")
            callback?.invoke(true)
            return
        }
        // 临时注册一个一次性回调
        addListener(object : ShizukuStateListener {
            override fun onRequestPermissionResult(granted: Boolean) {
                callback?.invoke(granted)
                removeListener(this)
            }
        })
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    /**
     * S-5：suspend 版授权请求（callbackFlow 封装，15s 超时兜底）。
     * 业务侧直接 `val granted = ShizukuManager.requestPermissionSuspend(context)`，避免回调嵌套。
     */
    suspend fun requestPermissionSuspend(context: Context, timeoutMs: Long = 15_000L): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            callbackFlow {
                requestPermission(context) { granted ->
                    trySend(granted)
                    close()
                }
                awaitClose { }
            }.first()
        } ?: false
    }
    fun addListener(listener: ShizukuStateListener) {
        ensureStateObservation()
        listeners.add(listener)
    }

    fun removeListener(listener: ShizukuStateListener) {
        listeners.remove(listener)
    }

    private fun ensureStateObservation() {
        if (!observingState.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky {
            Log.i(TAG, "Shizuku Binder 已连接")
            notifyStateChanged()
        }
        Shizuku.addBinderDeadListener {
            Log.w(TAG, "Shizuku Binder 已断开")
            notifyStateChanged()
        }
    }

    private fun notifyStateChanged() {
        // CopyOnWriteArraySet 天然并发安全，遍历中 removeListener 不会崩溃
        listeners.forEach { listener ->
            runCatching { listener.onRequestPermissionResult(isReady()) }
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku 权限请求结果 granted=$granted ")
            notifyStateChanged()
        }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku Binder 已连接")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku Binder 已断开")
    }
}