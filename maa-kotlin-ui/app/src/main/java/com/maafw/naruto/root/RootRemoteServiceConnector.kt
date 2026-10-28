package com.maafw.naruto.root

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.maafw.naruto.remote.RemoteEngineServiceImpl

/**
 * Root 模式连接器
 * 用 su + CLASSPATH + app_process 启动 root 进程运行 RemoteEngineServiceImpl，
 * root 进程把引擎 binder 注册到 ServiceManager，app 侧轮询 getService 获取——
 * 与 Shizuku 模式完全相同的接口、完整功能（虚拟屏/触摸/引擎全在 root 进程）。
 */
object RootRemoteServiceConnector {

    private const val TAG = "RootRemoteServiceConnector"
    private const val SERVICE_NAME = RootServiceStarter.SERVICE_NAME
    private const val ROOT_BIND_TIMEOUT_MS = 15_000L
    private const val POLL_INTERVAL_MS = 200L
    private lateinit var appContext: Context

    /**
     * 通道3.：manifest 静态 receiver（RootBinderReceiver）收到 root 引擎显式广播后写入的 binder，
     * waitForBinder 轮询时优先取它（绕开 Android 16 的 ServiceManager 限制与 uid0 隐式广播过滤）
     */
    @JvmField
    @Volatile
    var pendingBinder: IBinder? = null


    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    /**
     * 启动 root 引擎服务，轮询 ServiceManager 获取 binder。
     */
    fun bind(onConnected: (IBinder) -> Unit, onError: (Throwable) -> Unit) {
        Thread {
            try {
                // 先清理可能残留的旧 root 引擎进程，避免重复启动互相覆盖服务号
                killExistingRootService()
                // P1-5：登记 token，等待 root 进程经 ContentProvider 回传 binder
                val token = "root" // 与启动命令 --token=root 一致
                val future = RootServiceBootstrapRegistry.register(token)
                startRemoteService()
                // 优先等 ContentProvider 回传（3s）；超时则兜底轮询（广播/ServiceManager）
                val binder = try {
                    future.get(3, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    null
                } ?: waitForBinder()
                RootServiceBootstrapRegistry.unregister(token)
                if (binder == null) {
                    onError(IllegalStateException("获取 Root 引擎 binder 超时"))
                    return@Thread
                }
                onConnected(binder)
            } catch (e: Exception) {
                onError(e)
            }
        }.start()
    }

    fun disconnect(binder: IBinder?) {
        binder?.let { b ->
            runCatching {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    val descriptor = b.interfaceDescriptor
                    if (descriptor != null) data.writeInterfaceToken(descriptor)
                    b.transact(16777114, data, reply, android.os.Binder.FLAG_ONEWAY)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
    }

    private fun startRemoteService() {
        val apkPath = appContext.applicationInfo.sourceDir
        val pkg = appContext.packageName
        val uid = Process.myUid()
        val serviceClass = RemoteEngineServiceImpl::class.java.name
        val starterClass = RootServiceStarter::class.java.name
        val processName = "$pkg:root_service"

        // root 进程日志落盘，便于排查启动失败（不再丢到 /dev/null）
        val logFile = "/data/local/tmp/maafw_root_engine.log"
        // liblauncher.so（原生启动器，P1-6）：在原生进程中 setenv CLASSPATH 并 execv app_process，
        // 绕开 su shell 的 CLASSPATH 解析与 SELinux 对 /data/app 的限制（root 可读 app 的 so）
        val launcher = "${appContext.applicationInfo.nativeLibraryDir}/liblauncher.so"
        val cmd = buildString {
            append("su -c '").append(launcher)
            append(" --apk=").append(apkPath)
            append(" --process-name=").append(processName)
            append(" --starter-class=").append(starterClass)
            append(" --token=root")
            append(" --package=").append(pkg)
            append(" --class=").append(serviceClass)
            append(" --uid=").append(uid)
            append(" --debug-name=").append(processName)
            append(" --log-file=").append(logFile)
            // 关键：保持 root 身份运行（uid=0），否则引擎以 shell(2000) 运行无法 addService 到
            // ServiceManager，binder 回传失败 → App 拿不到引擎 binder（超时）。
            append(" --keep-root")
            append("' &")
        }
        Log.i(TAG, "启动 root 引擎进程(liblauncher): $cmd")
        val exit = runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        }.getOrDefault(-1)
        if (exit != 0) {
            Log.w(TAG, "su 启动 root 引擎进程失败，exitCode=$exit（su 未授权或命令执行失败）")
        }
    }

    /**
     * 清理可能残留的旧 Root 引擎进程。
     * 用 ^app_process 锚定命令行开头，避免误杀 su 的 shell 自身。
     */
    @JvmStatic
    fun killExistingRootService() {
        runCatching {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "pkill -9 -f '^app_process /system/bin com\\.maafw\\.naruto\\.root\\.RootServiceStarter'")
            ).waitFor()
            Log.i(TAG, "已清理残留 root 引擎进程")
        }.onFailure { Log.w(TAG, "清理残留 root 引擎进程失败: ${it.message}") }
    }

    /** 轮询获取 binder：优先 pendingBinder（显式广播直达），再轮询 ServiceManager/Binder.getService 直到超时 */
    private fun waitForBinder(): IBinder? {
        val deadline = System.currentTimeMillis() + ROOT_BIND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // 通道3.：root 引擎显式广播直达的 binder（最快最可靠，绕开 Android16 限制）
            pendingBinder?.let { binder ->
                pendingBinder = null
                if (binder.pingBinder()) {
                    Log.i(TAG, "waitForBinder: 通过显式广播拿到 binder")
                    return binder
                }
            }
            // 通道1.2.：ServiceManager / Binder.getService 轮询
            val binder = getServiceBinder()
            if (binder != null && binder.pingBinder()) {
                return binder
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun getServiceBinder(): IBinder? {
        return try {
            // 通道1.：Android 15+ 公开 API Binder.getService（反射调用，不受 hidden API 限制）
            val binder = runCatching {
                val m = android.os.Binder::class.java.getMethod("getService", String::class.java)
                m.invoke(null, SERVICE_NAME) as? IBinder
            }.getOrNull()
            if (binder != null) {
                Log.i(TAG, "getServiceBinder: Binder.getService 获取成功")
                return binder
            }
            // 通道2.：兼容旧系统，反射 ServiceManager.getService（Android16 上返回 null，仅兜底）
            runCatching {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/os/ServiceManager;",
                    "Landroid/os/ServiceManagerNative;"
                )
            }
            val clazz = Class.forName("android.os.ServiceManager")
            val m = clazz.getMethod("getService", String::class.java)
            val smBinder = m.invoke(null, SERVICE_NAME) as? IBinder
            if (smBinder == null) {
                Log.w(TAG, "getServiceBinder: ServiceManager 返回 null（Android16 app 进程限制）")
            } else {
                Log.i(TAG, "getServiceBinder: ServiceManager 获取成功")
            }
            smBinder
        } catch (e: Throwable) {
            Log.e(TAG, "getServiceBinder 失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}