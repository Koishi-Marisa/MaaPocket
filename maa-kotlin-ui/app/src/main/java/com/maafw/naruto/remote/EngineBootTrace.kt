package com.maafw.naruto.remote

import com.maafw.naruto.third.Ln
import java.io.File

/**
 * P1-1/L-2：引擎侧启动 trace（三层诊断之二：service_boot_debug.log）。
 * 路径自行推导（引擎构造阶段进程无可用 Context、也拿不到 App 传的 userDir）：
 * /storage/emulated/0/Android/data/{pkg}/files/debug/service_boot_debug.log（shell uid 可写）
 * 埋点：CTOR_START -> WORKAROUNDS -> MAA_LOAD_BEGIN -> MAA_LOAD_OK/FAIL -> CTOR_DONE
 */
object EngineBootTrace {

    private val f: File by lazy {
        val base = File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/data/${com.maafw.naruto.BuildConfig.APPLICATION_ID}/files/debug"
        )
        File(base, "service_boot_debug.log")
    }

    fun mark(stage: String, msg: String = "") {
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText("${System.currentTimeMillis()} [$stage] $msg\n")
        }
        Ln.i("[BOOT] $stage $msg")
    }
}