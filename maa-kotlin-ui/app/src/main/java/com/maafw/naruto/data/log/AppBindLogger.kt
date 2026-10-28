package com.maafw.naruto.data.log

import android.content.Context
import java.io.File

/**
 * P1-1/L-2：App 侧绑定链路诊断日志（三层诊断之一：service_bind_debug.log）。
 * 记录 Shizuku/Root 引擎绑定的各阶段事件，绑定失败时定位"卡在哪一步"。
 * 埋点：SESSION -> BIND -> CONNECTING -> CB_ON_CONNECTED -> BINDER_CONNECTED -> CB_ON_ERROR -> CONNECT_TIMEOUT -> RETRY。
 */
object AppBindLogger {

    private const val MAX_SIZE = 512 * 1024L

    @Volatile
    private var debugDir: File? = null

    fun init(context: Context) {
        debugDir = File(context.getExternalFilesDir(null), "debug").apply { mkdirs() }
        event("SESSION", "app start pid=${android.os.Process.myPid()}")
    }

    fun event(stage: String, msg: String = "") {
        val dir = debugDir ?: return
        runCatching {
            val f = File(dir, "service_bind_debug.log")
            if (f.exists() && f.length() > MAX_SIZE) {
                File(dir, "service_bind_debug.log.1").also { f.renameTo(it) }
            }
            f.appendText("${System.currentTimeMillis()} [$stage] $msg\n")
        }
    }
}