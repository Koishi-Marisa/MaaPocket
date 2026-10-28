package com.maafw.naruto.root

import android.os.Binder
import android.os.IBinder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Root 引擎 binder 回传登记表（P1-5：ContentProvider 握手）。
 * App 侧按 token 登记待接收的 binder；root 进程经 ContentProvider.call 回传，
 * 通过 [attach] 完成 deferred，供 waitForBinder 取用（绕开 Android16 的 ServiceManager 限制）。
 */
object RootServiceBootstrapRegistry {

    const val AUTHORITY_SUFFIX = ".root.bootstrap"
    const val METHOD_ATTACH_REMOTE_SERVICE = "attachRemoteService"
    const val KEY_TOKEN = "token"
    const val KEY_SERVICE_BINDER = "service_binder"
    const val KEY_APP_BINDER = "app_binder"
    const val KEY_APP_PID = "app_pid"

    private val pendingBinders = ConcurrentHashMap<String, CompletableFuture<IBinder>>()

    /** App 侧常驻生命周期 binder：root 进程 linkToDeath 它，App 死则 root 服务自杀 */
    private val appLifecycleBinder = Binder()

    /** 登记 token，返回等待 binder 的 future */
    fun register(token: String): CompletableFuture<IBinder> =
        pendingBinders.getOrPut(token) { CompletableFuture() }

    fun unregister(token: String) {
        pendingBinders.remove(token)
    }

    /** root 进程回传 binder；token 不存在返回 null（防串线） */
    fun attach(token: String, binder: IBinder): IBinder? {
        val future = pendingBinders.remove(token) ?: return null
        future.complete(binder)
        return appLifecycleBinder
    }

    fun getAppLifecycleBinder(): IBinder = appLifecycleBinder
}