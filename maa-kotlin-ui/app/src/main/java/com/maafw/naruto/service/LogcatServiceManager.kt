package com.maafw.naruto.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.maafw.naruto.ILogcatService
import com.maafw.naruto.remote.LogcatCaptureServiceImpl
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.Shizuku

/**
 * 独立 logcat 服务绑定管理（P1-2：完整版）。
 * 用随机 tag + 递增 version + 独立 connection 绑定 LogcatCaptureServiceImpl（processNameSuffix("logcat")），
 * 任务期间抓取 App + 引擎进程 logcat 落盘。
 */
object LogcatServiceManager {

    private const val TAG = "LogcatServiceManager"
    private val serviceVersion = AtomicInteger(100)

    @Volatile private var service: ILogcatService? = null
    @Volatile private var bound = false
    private var args: Shizuku.UserServiceArgs? = null
    private var connection: ServiceConnection? = null

    /** 绑定 logcat 服务（Shizuku 已运行且已授权时）；绑定成功后持续抓取 App 日志（不依赖任务） */
    fun bind(context: Context) {
        if (bound) return
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val appCtx = context.applicationContext
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = ILogcatService.Stub.asInterface(binder)
                bound = true
                Log.i(TAG, "logcat 服务已绑定")
                // 绑定成功即持续抓取 App 日志（引擎未连接也有 logcat；引擎进程由服务内 pgrep 自动带上）
                startCapture(android.os.Process.myPid(), appCtx.getExternalFilesDir(null)?.absolutePath)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                bound = false
                Log.i(TAG, "logcat 服务已断开")
            }
        }
        val a = Shizuku.UserServiceArgs(ComponentName(context, LogcatCaptureServiceImpl::class.java))
            .daemon(false)
            .processNameSuffix("logcat")
            .tag(UUID.randomUUID().toString())
            .version(serviceVersion.incrementAndGet())
        args = a
        connection = conn
        runCatching { Shizuku.bindUserService(a, conn) }
            .onFailure { Log.w(TAG, "绑定 logcat 服务失败: ${it.message}") }
    }

    /** 开始抓取（appPid=App 进程；引擎进程由 logcat 服务内 pgrep 定位） */
    fun startCapture(appPid: Int, userDir: String?) {
        val s = service ?: return
        runCatching { s.startCapture(appPid, userDir) }
    }

    /** 停止抓取 */
    fun stopCapture() {
        val s = service ?: return
        runCatching { s.stopCapture() }
    }

    /** 解绑（App 退出时调用） */
    fun unbind() {
        stopCapture()
        if (!bound) return
        val a = args
        val c = connection
        if (a != null && c != null) {
            runCatching { Shizuku.unbindUserService(a, c, true) }
        }
        bound = false
        service = null
        args = null
        connection = null
    }
}