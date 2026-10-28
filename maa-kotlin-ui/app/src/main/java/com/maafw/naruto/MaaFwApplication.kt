package com.maafw.naruto

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.maafw.naruto.bridge.BridgeNativeLib
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 应用全局入口
 * 负责初始化 Shizuku 监听和全局 Context 缓存，
 * 这样后台服务也能随时拿到应用上下文。
 */
class MaaFwApplication : Application() {

    companion object {
        private const val TAG = "MaaFwApplication"
        lateinit var context: Context
            private set
    }
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = this
    }


    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MAAFW 火影忍者手游 Android 容器已启动")

        // P1/L-1 MaaFwCrashHandler + P1-1 AppBindLogger（仅主进程；引擎进程不注册，避免干扰）
        if (!isRemoteEngineProcess()) {
            com.maafw.naruto.data.log.MaaFwCrashHandler.install(this)
            com.maafw.naruto.data.log.AppBindLogger.init(this)
            // DataStore 风格设置预读（同步读入缓存，主进程启动时一次）
            runCatching { com.maafw.naruto.data.settings.MaaFwSettingsStore.preload(this@MaaFwApplication) }
            // O-1 资源预部署：进程启动后台预解压（幂等，已就绪跳过），点开始任务零等待
            Thread {
                runCatching {
                    val t0 = System.currentTimeMillis()
                    val base = com.maafw.naruto.maa.AssetResourceDeployer.deploy(this, getExternalFilesDir(null)?.absolutePath)
                    val cost = System.currentTimeMillis() - t0
                    if (cost > 300) Log.i(TAG, "资源预部署完成（${cost}ms）：$base")
                }.onFailure { Log.w(TAG, "资源预部署失败（点开始任务时会重试）: ${it.message}") }
            }.apply { name = "resource-pre-deploy"; isDaemon = true }.start()
        }

        // JNA 需要可写目录解压其原生库，远端 shell 进程优先用 /data/local/tmp
        val jnaTmpDir = if (isRemoteEngineProcess()) {
            "/data/local/tmp"
        } else {
            cacheDir.absolutePath
        }
        System.setProperty("jna.tmpdir", jnaTmpDir)
        System.setProperty("jna.library.path", applicationInfo.nativeLibraryDir)
        Log.i(TAG, "JNA tmpdir=$jnaTmpDir, library.path=${applicationInfo.nativeLibraryDir}")

        // 绕过 Android 隐藏 API 限制，否则反射 DisplayManager.createVirtualDisplay 会失败
        try {
            val success = HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/hardware/display/DisplayManager;",
                "Landroid/hardware/display/DisplayManagerGlobal;",
                "Landroid/view/SurfaceControl;",
                "Landroid/view/IWindowManager;",
                "Landroid/app/IActivityManager;",
                "Landroid/hardware/input/InputManager;",
                "Landroid/view/InputEvent;",
                "Landroid/view/MotionEvent;",
                "Landroid/view/KeyEvent;",
                // Root 模式反射 ServiceManager.getService 获取引擎 binder 需要豁免
                "Landroid/os/ServiceManager;",
                "Landroid/os/ServiceManagerNative;",
                // Root 模式反射 Intent 读写 IBinder extra 需要豁免
                "Landroid/content/Intent;",
                "Landroid/os/Bundle;",
                // Root 模式反射 libcore.io.Os.setreuid 降权发广播需要豁免
                "Llibcore/io/Libcore;",
                "Llibcore/io/Os;"
            )
            Log.i(TAG, "HiddenApiBypass 已启用：$success ")
        } catch (e: Throwable) {
            Log.w(TAG, "HiddenApiBypass 启用失败：${e.message}")
        }
        try {
            val pong = BridgeNativeLib.ping()
            Log.i(TAG, "libbridge.so 加载成功：$pong")
        } catch (e: Throwable) {
            Log.e(TAG, "libbridge.so 加载失败：${e.message}", e)
        }
        // 主进程不要同步加载巨大的 MaaFramework so，避免 ANR；
        // 引擎会在远端 UserService 启动任务时按需加载
        try {
            val items = com.maafw.naruto.data.schedule.ScheduleRepository.load(this)
            if (items.isNotEmpty()) {
                com.maafw.naruto.schedule.ScheduleHelper.rescheduleAll(this, items)
                Log.i(TAG, "应用启动时重新注册了 ${items.size} 个定时任务")
            }
        } catch (e: Exception) {
            Log.w(TAG, "重新注册定时任务失败：${e.message}")
        }
        // 后台保障：Root 守护进程开关开启且已 root -> 拉起常驻调度
        // （App 进程被杀/强停后定时任务仍由 root 守护进程执行，不依赖 MainActivity 启动）
        try {
            if (!isRemoteEngineProcess()
                && com.maafw.naruto.data.settings.SettingsRepository.isRootDaemonEnabled(this)
                && com.maafw.naruto.root.RootManager.isRootGranted()
            ) {
                Thread {
                    runCatching { com.maafw.naruto.root.RootDaemonController.start(this) }
                }.start()
                Log.i(TAG, "已拉起 Root 守护进程（后台保障）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "拉起 Root 守护进程失败：${e.message}")
        }
    }

    private fun isRemoteEngineProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.find { it.pid == Process.myPid() }?.processName ?: ""
        }
        return processName.endsWith(":remote_engine")
    }
}