package com.maafw.naruto.remote

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.maafw.naruto.IEngineStatusListener
import com.maafw.naruto.IRemoteEngineService
import com.maafw.naruto.bridge.NativeBridge
import com.maafw.naruto.bridge.BridgeNativeLib
import com.maafw.naruto.data.settings.EngineSharedConfig
import com.maafw.naruto.maa.AgentManager
import com.maafw.naruto.maa.AssetResourceDeployer
import com.maafw.naruto.maa.CustomActions
import com.maafw.naruto.maa.CustomRecognitions
import com.maafw.naruto.maa.MaaFrameworkEngine
import com.maafw.naruto.remote.internal.MaaFwActivityHelper
import com.maafw.naruto.remote.internal.MaaFwVirtualDisplay
import com.maafw.naruto.third.Ln
import com.maafw.naruto.third.MaaFwCompat
import com.maafw.naruto.third.wrappers.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Shizuku UserService 实现。
 * 运行在 shell 进程，负责创建虚拟屏、驱动 MaaFramework。
 */
class RemoteEngineServiceImpl(private val context: Context) : IRemoteEngineService.Stub() {

    companion object {
        private const val TAG = "RemoteEngineService"

        /** Root 模式：引擎进程把 binder 通过广播直传给 App 的 action  */
        const val ROOT_ENGINE_BINDER_ACTION = "com.maafw.naruto.ROOT_ENGINE_BINDER"
    }

    init {
        EngineBootTrace.mark("CTOR_START", "pid=${Process.myPid()}")
        Ln.i("$TAG init, pid=${Process.myPid()}, uid=${Process.myUid()}")
        val engineUid = Process.myUid()
        Ln.i("$TAG 引擎 uid=$engineUid，" +
                if (engineUid == 0) "root（非 shell，能力可能受限：虚拟屏/启动游戏/触摸注入）"
                else if (engineUid == android.os.Process.SHELL_UID) "shell（推荐）"
                else "uid=$engineUid")
        if (engineUid != android.os.Process.SHELL_UID && engineUid != 0) {
            Ln.e("$TAG 当前 UID=$engineUid 不是 shell(2000)/root(0)，" +
                    "请确认 Shizuku 使用 adb/shell 模式启动")
        }
        // 任何退出路径（destroy/看门狗/shutdown hook）统一紧急清理：
        // 恢复被静音的游戏音量、释放虚拟屏/唤醒锁、销毁 MaaFramework（防残留/永久静音）
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { performEmergencyCleanup() }
        }.apply { name = "remote-shutdown-hook" })
        // App 心跳看门狗：App 绑定后喂 pid，App 死则引擎 5s 内自杀（防孤儿引擎占虚拟屏/唤醒锁）
        startHeartbeatWatchdog()
        // 防系统回收（ColorOS/OOM 强杀防护）：降低引擎进程 oom_score_adj（root 可写；shell 尝试无害）。
        // 引擎被强杀会导致任务中断+静音/虚拟屏残留，这是最有效的保活手段。
        runCatching {
            java.io.File("/proc/self/oom_score_adj").writeText("-800")
            Ln.i("$TAG 已调整引擎进程 oom_score_adj=-800（防系统回收）")
        }.onFailure { Ln.w("$TAG 调整 oom_score_adj 失败（shell 无权限；Root/Shizuku root 模式可生效）: ${it.message}") }
        // CPU 优先级提升（任务识别不被抢占）
        runCatching {
            android.os.Process.setThreadPriority(
                android.os.Process.myTid(), android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
            )
        }.onFailure { }
        // 初始化失败绝不能阻断 onBind（否则 Shizuku UserService 启动失败，App 永远收不到连接）
        try {
            MaaFwCompat.apply()
            // 确保 JNA 在加载 MaaFramework 前知道 so 目录
            System.setProperty("jna.library.path", context.applicationInfo.nativeLibraryDir)
            // JNA 解包临时 so 时不能走 app 私有目录，否则 shell UID 会触发 UID/PKG 校验
            System.setProperty("jna.tmpdir", "/data/local/tmp")
            // 触发 BridgeNativeLib 加载 libbridge.so
            val ping = BridgeNativeLib.ping()
            Ln.i("$TAG BridgeNativeLib ping=$ping")
        } catch (e: Throwable) {
            Ln.e("$TAG 引擎初始化失败（onBind 仍继续返回）: ${e.message}", e)
        }

        // Root 模式：把自身 binder 通过显式广播直达 manifest 静态 receiver（RootBinderReceiver）。
        // Android 16 限制：uid0 进程发的广播会被系统丢弃、app 进程 getService 也拿不到服务。
        // 解法：广播瞬间临时降权到 App 的 uid（sendingUid 与 App 一致即可收到），发完立即提权回 root 。
        // 注意：仅在 root 进程执行（shell 模式 uid=2000 无需广播且 setresuid 必然失败，产生误导性噪音日志）
        if (engineUid == 0) {
            try {
                val intent = Intent(ROOT_ENGINE_BINDER_ACTION).apply {
                    component = android.content.ComponentName(
                        context.packageName,
                        "com.maafw.naruto.root.RootBinderReceiver"
                    )
                }
                // Intent.putExtra(String, IBinder) 在 SDK stub 里是 @hide，用反射写入（运行时真实类存在该方法）
                runCatching {
                    Intent::class.java.getMethod("putExtra", String::class.java, IBinder::class.java)
                        .invoke(intent, "binder", this@RemoteEngineServiceImpl)
                }.onFailure { Ln.w("$TAG 反射 putExtra binder 失败: ${it.message}") }

                val appUid = context.applicationInfo.uid
                // 降权到 App uid（real+effective=appUid，saved 保持 0），广播身份与 App 一致才能被收到
                val drop = setProcessUid(appUid)
                if (!drop) Ln.w("$TAG 降权到 uid=$appUid 失败，仍以 root 身份发广播（可能被系统丢弃）")
                try {
                    context.sendBroadcast(intent)
                } finally {
                    val raise = setProcessUid(0) // 提权回 root
                    if (!raise) Ln.e("$TAG 提权回 root 失败（后续引擎功能可能异常）")
                }
                Ln.i("$TAG 已降权广播 root 引擎 binder 给 App（uid=$appUid）")
            } catch (e: Exception) {
                Ln.e("$TAG 广播 root 引擎 binder 失败: ${e.message}")
            }
        } else {
            Ln.i("$TAG shell 模式（uid=$engineUid）：跳过 root binder 降权广播")
        }
        EngineBootTrace.mark("CTOR_DONE", "uid=$engineUid")
    }

    /**
     * 切换进程 uid（保留 saved uid=0 以便提权）。
     * 用公开 API android.system.Os.setresuid(uid, uid, 0)：real+effective=uid、saved 恒为 0，之后可 setresuid(0,0,0) 提权回 root。
     * 注意不能用 setreuid——POSIX 下 setreuid(10750,10750) 会把 saved uid 也置为 10750，导致无法提权回 root 。
     */
    private fun setProcessUid(uid: Int): Boolean {
        return runCatching {
            // android.system.Os.setresuid 在 SDK stub 里是 @hide，用反射调用（运行时真实类存在；引擎进程有全豁免）
            val m = android.system.Os::class.java.getMethod(
                "setresuid",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            m.invoke(null, uid, uid, 0)
            true
        }.getOrDefault(false)
    }

    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: MaaFrameworkEngine? = null
    private var engineJob: kotlinx.coroutines.Job? = null
    private var running = false
    @Volatile
    private var destroyed = false
    /** App 进程 pid（heartbeat 喂入；看门狗查 /proc/<pid> 决定引擎是否自杀，防孤儿引擎占虚拟屏） */
    private val appPid = java.util.concurrent.atomic.AtomicInteger(0)
    /** 引擎侧静音标记：setAudioMuted(true) 置位；紧急清理/正常恢复时复位（防游戏永久静音） */
    @Volatile
    private var audioMutedByEngine = false
    private var userDir: String? = null
    /**
     * 引擎侧运行配置：App 侧启动任务前写入 userDir/engine_config.json，引擎侧读取。
     * 不能读 App 私有 SharedPreferences（shell uid 无权限 + UserService context.applicationContext==null）。
     */
    @Volatile
    private var engineConfig = EngineSharedConfig.Config()
    /** 最近一次绑定的预览 Surface（关屏后用于恢复投屏） */
    private var monitorSurface: android.view.Surface? = null
    /** 预览投屏是否启用（setPreviewEnabled 维护，keepalive 自愈预览用） */
    @Volatile
    private var previewEnabled = true
    /** 是否处于用户暂停状态（暂停结束时不得自动关闭游戏/虚拟屏） */
    @Volatile
    private var isPausedByUser = false
    /** 当前手势 DOWN 的 downTime（DOWN/MOVE/UP 共享，保证滑动/长按正常） */
    private var currentDownTime = 0L
    /** 任务期间引擎进程自持唤醒锁（不依赖 App 进程存活，锁屏/深度休眠时 CPU 不睡） */
    private var engineWakeLock: android.os.PowerManager.WakeLock? = null
    /** 任务期间周期保持虚拟屏点亮的协程（防 Doze 灭虚拟屏导致截图/识别中断） */
    private var vdKeepAliveJob: kotlinx.coroutines.Job? = null
    // ==================== 帧率统计（纯 Kotlin，不依赖 libbridge 新增 JNI，保持原版 ABI） ====================
    // 游戏 FPS：轮询原版 libbridge 自带的 getFrameCount() 增量计算（原版 JNI，未改动）；
    // 脚本识别频率：ContextSink 统计 Node.Recognition.* 事件数（MaaFramework Kotlin 事件，不涉 native）。
    private val gameFpsState = java.util.concurrent.atomic.AtomicReference(0.0)
    private val scriptFpsState = java.util.concurrent.atomic.AtomicReference(0.0)
    private val fpsSamplerLock = Any()
    private var fpsLastCount = -1L
    private var fpsLastTime = 0L
    private var recogCount = 0L
    private var recogCountLast = 0L
    private var recogTimeLast = 0L
    /** 帧率采样协程（引擎进程常驻，任务运行时才有有效数据） */
    private var fpsSamplerJob: kotlinx.coroutines.Job? = null
    /** 识别事件计数（ContextSink 内原子累加，采样协程读取后重置） */
    private val recogEventCounter = java.util.concurrent.atomic.AtomicLong(0L)

    // ==================== 后台运行保障 ====================

    /** 任务开始时获取引擎唤醒锁（App 被杀/锁屏也保证 CPU 不休眠） */
    private fun acquireEngineWakeLock() {
        if (engineWakeLock?.isHeld == true) return
        runCatching {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            engineWakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "MaaFW:engine_task"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L) // 最长 2 小时自动释放
            }
            Ln.i("$TAG 引擎进程已持后台唤醒锁")
        }.onFailure { Ln.w("$TAG 获取唤醒锁失败（不影响任务执行）: ${it.message}") }
    }

    private fun releaseEngineWakeLock() {
        runCatching {
            engineWakeLock?.takeIf { it.isHeld }?.release()
            engineWakeLock = null
        }
    }

    /**
     * 任务期间周期保持虚拟屏活跃（userActivity 防系统休眠 + requestDisplayPower 保亮双保险）。
     * userActivity 每 4s 上报「用户活跃」，防止系统因无活动进入休眠/熄屏；
     * requestDisplayPower 每 20s 强制点亮虚拟屏，防 Doze 深度休眠熄灭。
     * 自愈：帧停滞 ≥15s（虚拟屏黑屏/捕获中断）-> 重亮 + 重建预览渲染线程；
     *       每 60s 主动重建一次预览渲染线程（防 libbridge EGL 渲染卡死导致预览黑屏）。
     */
    private fun startVdKeepAlive() {
        vdKeepAliveJob?.cancel()
        vdKeepAliveJob = remoteScope.launch {
            var lastPowerCheck = 0L
            var lastPreviewRefresh = 0L
            var lastFrameCount = -1L
            var staleSince = 0L
            while (true) {
                delay(4_000)
                val id = MaaFwVirtualDisplay.getDisplayId()
                if (id < 0) continue
                // 1) 上报用户活动（每 4s）——防系统「无活动」休眠/熄屏
                runCatching { ServiceManager.getPowerManager().userActivity(id) }
                    .onFailure { Ln.w("$TAG userActivity 保活失败: ${it.message}") }
                val now = SystemClock.elapsedRealtime()
                // 2) 周期强制点亮虚拟屏（每 20s）——防 Doze 深度休眠熄灭
                if (now - lastPowerCheck >= 20_000) {
                    lastPowerCheck = now
                    runCatching { ServiceManager.getDisplayManager().requestDisplayPower(id, true) }
                        .onFailure { Ln.w("$TAG 虚拟屏保亮失败: ${it.message}") }
                }
                // 3) 帧停滞自愈：getFrameCount 连续 15s 无增长 -> 虚拟屏黑屏/捕获中断
                val fc = runCatching { BridgeNativeLib.getFrameCount() }.getOrDefault(lastFrameCount)
                if (fc == lastFrameCount && lastFrameCount >= 0) {
                    if (staleSince == 0L) staleSince = now
                    if (now - staleSince >= 15_000) {
                        Ln.w("$TAG 虚拟屏帧停滞≥15s(疑似黑屏)，触发自愈：重亮+重建预览")
                        staleSince = 0L
                        // 先灭再亮，强制 SurfaceFlinger 重新渲染该 display
                        runCatching { ServiceManager.getDisplayManager().requestDisplayPower(id, false) }
                            .onFailure { Ln.w("$TAG 自愈:灭屏失败: ${it.message}") }
                        runCatching { ServiceManager.getDisplayManager().requestDisplayPower(id, true) }
                            .onFailure { Ln.w("$TAG 自愈:重亮失败: ${it.message}") }
                        refreshPreviewSurface()
                    }
                } else {
                    lastFrameCount = fc
                    staleSince = 0L
                }
                // 4) 周期性重建预览渲染线程（每 60s）——防 libbridge EGL 渲染线程卡死导致预览黑屏
                if (now - lastPreviewRefresh >= 60_000) {
                    lastPreviewRefresh = now
                    refreshPreviewSurface()
                }
            }
        }
    }

    private fun stopVdKeepAlive() {
        vdKeepAliveJob?.cancel()
        vdKeepAliveJob = null
    }

    /**
     * 预启动游戏到虚拟屏并等待首帧（供 startTasksJson 并行调用）。
     * 虚拟屏刚创建是空屏，MaaFramework screencap 拿不到图会卡死在 start_up，
     * 必须等游戏画面流动起来。等首帧上限 8s，100ms 轮询。
     * @return true=画面已流动（或游戏未安装但流程可继续）
     */
    private fun prelaunchGame(displayId: Int, resourceBase: String): Boolean {
        return runCatching {
            val pkg = runCatching {
                val f = File(resourceBase, "pipeline/Startup.json")
                if (f.exists()) {
                    JSONObject(f.readText())
                        .optJSONObject("start_up")
                        ?.optString("package")
                        .orEmpty()
                } else ""
            }.getOrDefault("").ifBlank { "com.tencent.KiHan" }
            sendLog("预启动游戏 $pkg 到虚拟屏 displayId=$displayId ")
            var ok = MaaFwActivityHelper.startApp(pkg, displayId, forceStop = false)
            if (!ok) {
                // AM API 启动失败时用 am start --display 兜底（真机验证可用）
                Ln.w("$TAG preLaunch: startApp failed, fallback to am start --display")
                ok = MaaFwActivityHelper.startAppViaAmCommand(pkg, displayId)
            }
            if (ok) {
                MaaFwActivityHelper.ensureAppOnDisplay(pkg, displayId)
                val baseline = BridgeNativeLib.getFrameCount()
                val deadline = SystemClock.uptimeMillis() + 8_000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (BridgeNativeLib.getFrameCount() > baseline) {
                        sendLog("游戏画面已开始流动，继续启动任务")
                        return@runCatching true
                    }
                    SystemClock.sleep(100)
                }
            } else {
                sendLog("预启动失败（游戏可能未安装），继续尝试运行任务")
            }
            true
        }.onFailure { Ln.e("$TAG preLaunch failed: ${it.message}") }.getOrDefault(true)
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        Ln.i("$TAG destroy()")
        stopAll()
        remoteScope.cancel()
        // 统一紧急清理：恢复被静音的游戏 + 释放虚拟屏（幂等，与 shutdown hook/看门狗共用）
        performEmergencyCleanup()
        exitProcess(0)
    }

    override fun exit() = destroy()

    // ==================== P0 守护：心跳看门狗 + 紧急清理 ====================

    /** App 绑定成功后喂入 App pid；引擎看门狗每 5s 查 /proc/<pid>，App 死则引擎自杀 */
    override fun heartbeat(pid: Int) {
        appPid.set(pid)
        Ln.i("$TAG heartbeat appPid=$pid")
    }

    /** 心跳看门狗线程：App 进程消失 5s 内引擎自杀（杜绝孤儿引擎占用虚拟屏/唤醒锁/权限） */
    private fun startHeartbeatWatchdog() {
        Thread {
            while (!destroyed) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
                val pid = appPid.get()
                if (pid <= 0) continue
                if (!java.io.File("/proc/$pid").exists()) {
                    Ln.w("$TAG App(pid=$pid) 已死亡，引擎自杀（heartbeat watchdog）")
                    destroy()
                    return@Thread
                }
            }
        }.apply { name = "engine-heartbeat-watchdog"; isDaemon = true }.start()
    }

    /**
     * 任何退出路径（destroy / 看门狗 / shutdown hook）统一清理：
     * 恢复被静音的游戏音量 + 释放虚拟屏/唤醒锁 + 销毁 MaaFramework。
     * 幂等：各步骤 runCatching 兜底，重复调用无副作用。
     */
    private fun performEmergencyCleanup() {
        runCatching { restoreAudioIfMuted() }.onFailure { Ln.w("$TAG 清理:恢复音频失败 ${it.message}") }
        runCatching { MaaFwVirtualDisplay.stop() }.onFailure { Ln.w("$TAG 清理:释放虚拟屏失败 ${it.message}") }
        runCatching { releaseEngineWakeLock() }.onFailure { Ln.w("$TAG 清理:释放唤醒锁失败 ${it.message}") }
        runCatching { stopVdKeepAlive() }.onFailure { }
        runCatching { stopLogcatCapture() }.onFailure { }
        runCatching { AgentManager.stop() }.onFailure { }
        runCatching {
            engine?.let { kotlinx.coroutines.runBlocking { it.destroy() } }
            engine = null
        }.onFailure { Ln.w("$TAG 清理:销毁 MaaFramework 失败 ${it.message}") }
        Ln.i("$TAG performEmergencyCleanup done")
    }

    /** 恢复被本引擎静音的音频（引擎异常退出/正常退出时防游戏永久静音） */
    private fun restoreAudioIfMuted() {
        if (!audioMutedByEngine) return
        runCatching { setAudioMutedInternal(false) }
        audioMutedByEngine = false
    }

    override fun version(): String {
        return runCatching {
            "NativeBridge ping=${BridgeNativeLib.ping()}, MaaFW=${engine?.version ?: "待机（任务启动后加载）"}"
        }.getOrDefault("unknown")
    }

    override fun setup(userDir: String?): Boolean {
        Ln.i("$TAG setup($userDir)")
        this.userDir = userDir
        // 读取 App 侧写入的共享配置（shell 进程无权读 App 私有 SharedPreferences，必须走 userDir 文件）
        engineConfig = EngineSharedConfig.read(userDir)
        Ln.i("$TAG 引擎共享配置已加载: reuse=${engineConfig.engineReuse}, closeGame=${engineConfig.closeGameAfterTask}")
        // 会话级日志隔离：引擎进程每次启动，把上次 maafw.log 滚动为备份（最多3个），本次写新日志
        runCatching { com.maafw.naruto.data.log.LogExporter.rotateEngineLog(context) }
            .onFailure { Ln.w("$TAG 引擎日志滚动失败: ${it.message}") }
        sendLog("引擎 setup：userDir=$userDir")
        // 触摸预览：脚本触摸（libbridge->InputInjector）同进程回调 -> binder 直达 App
        // 必须异步 + 限流：同步/高频 binder 会阻塞触摸注入线程（Swipe 卡死根因）
        com.maafw.naruto.shizuku.InputInjector.onInjectedTouch = { action, x, y ->
            val now = android.os.SystemClock.uptimeMillis()
            val isEdge = action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_UP
            val last = lastTouchNotifyTime.get()
            // DOWN/UP 必发；MOVE 高频时合并（最低间隔 ms）
            if (isEdge || (now - last >= TOUCH_NOTIFY_MIN_INTERVAL && lastTouchNotifyTime.compareAndSet(last, now))) {
                if (isEdge) lastTouchNotifyTime.set(now)
                touchNotifyExecutor.execute { notifyTouch(action, x, y) }
            }
        }
        return try {
            // 资源部署到 App 外部目录（userDir），shell uid 可写（社区通用方案）
            val base = AssetResourceDeployer.deploy(context, userDir)
            sendLog("资源部署完成：$base")
            true
        } catch (e: Exception) {
            Ln.e("$TAG setup failed: ${e.message}")
            e.printStackTrace()
            sendLog("资源部署失败：${e.message}\n${e.stackTraceToString()}")
            false
        }
    }

    override fun startVirtualDisplay(): Int {
        Ln.i("$TAG startVirtualDisplay()")
        return try {
            val id = MaaFwVirtualDisplay.start()
            if (id >= 0) {
                NativeBridge.setDisplayId(id)
                sendLog("虚拟屏 displayId=$id，已告知 NativeBridge")
            } else {
                sendLog("虚拟屏创建失败：返回 displayId=$id")
            }
            id
        } catch (e: Throwable) {
            val root = unwrapThrowable(e)
            Ln.e("$TAG startVirtualDisplay failed: ${root.javaClass.simpleName}: ${root.message}")
            root.printStackTrace()
            val reason = when (root) {
                is SecurityException -> "权限被拒绝，请确认 Shizuku 为 adb/shell 模式"
                is IllegalArgumentException -> "参数非法：${root.message}"
                is IllegalStateException -> "状态异常：${root.message}"
                else -> "${root.javaClass.simpleName}: ${root.message}"
            }
            sendLog("虚拟屏启动异常：$reason")
            -1
        }
    }

    private fun unwrapThrowable(t: Throwable): Throwable {
        var cause = t
        while ((cause is java.lang.reflect.InvocationTargetException || cause is RuntimeException) && cause.cause != null) {
            cause = cause.cause!!
        }
        return cause
    }

    override fun stopVirtualDisplay() {
        Ln.i("$TAG stopVirtualDisplay()")
        MaaFwVirtualDisplay.stop()
    }

    override fun setMonitorSurface(surface: Surface?) {
        Ln.i("$TAG setMonitorSurface(${surface != null})")
        monitorSurface = surface
        // 加固：任一步失败都不抛异常（避免 binder 返回 RemoteException -> App 报"预览 Surface 设置失败"）
        runCatching { MaaFwVirtualDisplay.setMonitorSurface(surface) }
            .onFailure { Ln.w("$TAG setMonitorSurface: setMonitorSurface failed: ${it.message}") }
        runCatching { BridgeNativeLib.setPreviewSurface(surface) }
            .onFailure { Ln.w("$TAG setMonitorSurface: setPreviewSurface failed: ${it.message}") }
        // 绑定预览 Surface 时再次确保虚拟屏处于点亮状态，否则截图/预览黑屏
        val displayId = MaaFwVirtualDisplay.getDisplayId()
        if (displayId >= 0) {
            runCatching {
                ServiceManager.getDisplayManager().requestDisplayPower(displayId, true)
            }.onFailure { Ln.w("$TAG setMonitorSurface: requestDisplayPower failed: ${it.message}") }
        }
    }

    override fun startTask(entry: String?, pipelineOverride: String?): Boolean {
        if (entry == null) return false
        // 选项来自 App 侧写入的共享配置（不读 App 私有 SharedPreferences，shell 无权访问）
        val options = engineConfig.optionsOf(entry)
        val obj = JSONObject().apply {
            put("entry", entry)
            put("options", JSONObject(options))
            pipelineOverride?.let { put("pipeline_override", it) }
        }
        return startTasksJson(JSONArray().apply { put(obj) }.toString())
    }

    override fun startTasksJson(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val tasks = runCatching { JSONArray(json) }.getOrNull() ?: return false
        if (tasks.length() == 0) return false
        if (running) {
            Ln.w("$TAG engine already running")
            return false
        }
        // 记录是否由暂停恢复：暂停后控制器/虚拟屏仍存活，复用分支跳过重连（避免 MaaControllerWait 卡死）
        val wasPaused = isPausedByUser
        isPausedByUser = false
        // ——性能优化：引擎实例复用——
        // 资源/pipeline/OCR模型只在首次加载；任务正常结束后复用 tasker（MaaTaskerPostTask 可重复调用），
        // 跳过每次任务的资源/模型重载（省 2~5s/任务）。仅在上次任务异常时销毁重建，保证稳定性。
        // 识别实时性保障：清识别缓存 + 控制器重连 + 最新帧缓冲（GetLockedPixels 永远拿最新帧）。
        // 设置里可关闭「引擎复用」回退到每任务完整重建（最保守）。
        // 每次任务启动前刷新共享配置（用户改设置后立即生效；不读 App 私有 SharedPreferences）
        engineConfig = EngineSharedConfig.read(userDir)
        val reuseEnabled = engineConfig.engineReuse
        engine?.let { old ->
            if (wasPaused) {
                // 暂停恢复：引擎已通过 PostStop 停止且保留，直接复用。
                // 跳过所有 native 探测（isRunning/stopTask/clearCache/reConnect 在暂停后可能阻塞卡死）
                Ln.i("$TAG 暂停恢复，直接复用引擎（跳过探测/重连）")
            } else {
                if (old.isRunning()) {
                    runCatching { old.stopTask() }.onFailure { Ln.w("$TAG stop old engine failed: ${it.message}") }
                }
                if (!reuseEnabled || old.needRebuild()) {
                    Ln.i("$TAG ${if (reuseEnabled) "上次任务异常，重建引擎（重新加载资源/模型）" else "引擎复用已关闭，重建引擎（每次任务全新状态）"}")
                    runCatching { kotlinx.coroutines.runBlocking { old.destroy() } }
                        .onFailure { Ln.e("$TAG destroy old engine failed: ${it.message}") }
                    engine = null
                } else {
                    runCatching { old.clearCache() }
                    // 实时性保障：清识别缓存 + 强制控制器重连（毫秒级，重置截屏状态）
                    val reconnected = old.reConnectController()
                    Ln.i("$TAG 复用引擎实例（跳过资源/模型重载）：控制器重连=${if (reconnected) "ok" else "失败"}")
                }
            }
        }
        // 注意：libbridge.so 统一从 /data/local/tmp 副本加载（双副本问题）：
        // Java 侧 System.loadLibrary("bridge") 加载 APK 内副本，而 MaaFramework dlopen 的 library_path
        // 是另一个路径 -> 同一进程加载两份独立 ELF，capturer 状态不共享 -> GetLockedPixels 永远空帧。
        // 必须在创建虚拟屏（setupNativeCapturer）之前 System.load(tmp) 把 JNI 注册切到 tmp 副本。
        val tmpBridge = java.io.File("/data/local/tmp", "maafw_lib_${context.packageName}/libbridge.so")
        val userBridge = userDir?.let { java.io.File(java.io.File(it), "libbridge.so") }

        /** 实时查询当前安装的 nativeLibraryDir（不信任引擎进程缓存的 ApplicationInfo；覆盖安装后旧路径已删除） */
        fun currentLibDir(): String? = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0).nativeLibraryDir
        }.getOrNull() ?: context.applicationInfo.nativeLibraryDir

        /** 确保 tmp 副本存在：源依次为 当前安装目录 -> userDir 副本 -> 引擎缓存目录（root 可读 data/app；shell 读不了则靠 userDir） */
        fun ensureTmpBridge(): File? {
            if (tmpBridge.exists() && tmpBridge.length() > 0) return tmpBridge
            val candidates = mutableListOf<File>().apply {
                currentLibDir()?.let { add(java.io.File(it, "libbridge.so")) }
                userBridge?.let { add(it) }
                add(java.io.File(context.applicationInfo.nativeLibraryDir, "libbridge.so"))
            }.distinct()
            for (src in candidates) {
                if (!src.exists() || src.length() == 0L) continue
                val ok = runCatching {
                    tmpBridge.parentFile?.mkdirs()
                    src.copyTo(tmpBridge, overwrite = true)
                    tmpBridge.setReadable(true, false)
                    tmpBridge.setExecutable(true, false)
                }.onFailure { Ln.w("$TAG 复制 libbridge.so(${src.absolutePath}) 失败: ${it.message}") }.isSuccess
                if (ok && tmpBridge.exists() && tmpBridge.length() > 0) return tmpBridge
            }
            return null
        }
        ensureTmpBridge()
        // JNI 切换到 tmp 副本（同一文件 dlopen 只加载一次，之后 MaaFramework 的 GetLockedPixels
        // 与 setupNativeCapturer 共享同一 capturer 状态）。必须在虚拟屏创建之前执行！
        runCatching { System.load(tmpBridge.absolutePath) }
            .onFailure { Ln.w("$TAG System.load(tmp libbridge) 失败: ${it.message}") }
        Ln.i("$TAG libbridge tmp 已就绪: ${tmpBridge.absolutePath} (tmp=${tmpBridge.exists()}, user=${userBridge?.exists()}, cur=${currentLibDir()})")

        val displayId = MaaFwVirtualDisplay.getDisplayId()
        if (displayId < 0) {
            Ln.e("$TAG virtual display not ready")
            return false
        }

        val cfg = MaaFwVirtualDisplay.getConfig()
        // libbridge.so 加载路径：统一用已就绪的 tmp 副本（固定路径，不随 data/app 随机目录变化）
        val bridgePath = if (tmpBridge.exists() && tmpBridge.length() > 0) tmpBridge.absolutePath
        else userBridge?.takeIf { it.exists() && it.length() > 0 }?.absolutePath
            ?: currentLibDir()?.let { java.io.File(it, "libbridge.so") }?.takeIf { it.exists() }?.absolutePath
            ?: java.io.File(context.applicationInfo.nativeLibraryDir, "libbridge.so").absolutePath
        // JNA 也刷新为实时路径（本次任务若需重建引擎，MaaFramework 等 so 从当前安装路径加载）
        System.setProperty("jna.library.path", currentLibDir() ?: context.applicationInfo.nativeLibraryDir)
        Ln.i("$TAG bridgePath=$bridgePath")
        val resourceBase = runCatching {
            AssetResourceDeployer.deploy(context, userDir)
        }.getOrElse { null } ?: run {
            Ln.e("$TAG resource deploy failed")
            return false
        }

        running = true
        // 后台保障：引擎进程自持唤醒锁（CPU 不休眠）+ 周期保持虚拟屏点亮（防 Doze 灭屏）
        acquireEngineWakeLock()
        startVdKeepAlive()
        // 帧率统计：纯 Kotlin 采样器（游戏 getFrameCount 增量 + 识别事件频率）
        startFpsSampler()
        // P1-2：任务期间持续抓取 logcat（按 App/引擎 pid 落盘 debug/logcat/）
        startLogcatCapture()
        engineJob = remoteScope.launch {
            try {
                // F5：游戏预启动（等首帧）与引擎资源初始化并行——两者互不依赖（虚拟屏已在任务前创建），
                // 重叠执行节省启动耗时（预启动 2~4s 与资源/OCR 模型加载 2~5s 并行）。
                // 控制器创建仍在两者完成后串行（依赖画面流动 + 资源就绪），不干扰虚拟屏/滑动时序。
                val prelaunchJob = remoteScope.async { prelaunchGame(displayId, resourceBase) }

                sendLog("正在初始化 MaaFramework 引擎（与游戏预启动并行）")
                // 日志目录必须由 App 进程传过来，shell 进程里 ShellContext.getExternalFilesDir 会触发 UID/PKG 校验；
                // 目录已存在时 mkdirs() 返回 false，必须用 exists()||mkdirs() 判断，否则会误回退 /data/local/tmp 
                val logDir = userDir?.let { File(it, "maa_logs") }
                    ?.takeIf { (it.exists() || runCatching { it.mkdirs() }.getOrDefault(false)) && it.canWrite() }
                    ?: File("/data/local/tmp", "maa_logs_${context.packageName}").also { it.mkdirs() }
                // custom action 的清理类动作需要文件目录
        CustomActions.setFilesDir(userDir?.let { File(it, "maa_files") })
        CustomActions.clearCounters()
        // 复刻 py 的 custom 日志文件（导出时打包，对应原版 debug/custom/*.log）
        CustomRecognitions.setLogFile(userDir?.let { File(File(it, "maa_logs"), "custom_kt.log") })
                val maa = MaaFrameworkEngine(context).apply { init(logDir) }
                engine = maa
                Ln.i("$TAG MaaFramework version ${maa.version}")
                sendLog("MaaFramework 版本 ${maa.version}")
                if (!maa.loadResource(resourceBase)) {
                    Ln.e("$TAG loadResource failed")
                    sendLog("资源加载失败：$resourceBase（pipeline/image/model 缺失或 MaaFramework 不支持）")
                    running = false
                    return@launch
                }
                sendLog("资源加载完成")
                // F5：控制器创建前等待游戏预启动完成（画面流动）——资源加载已与预启动并行
                prelaunchJob.await()
                if (!maa.createController(bridgePath, cfg.width, cfg.height, displayId, engineConfig.forceStop)) {
                    Ln.e("$TAG createController failed")
                    sendLog("控制器创建失败：libbridge=$bridgePath 分辨率=${cfg.width}x${cfg.height} displayId=$displayId（请确认 Shizuku/Root 权限）")
                    running = false
                    return@launch
                }
                sendLog("控制器创建成功")
                // 引擎复用时 tasker 已存在（复用分支保留 engine），跳过重建——
                // 重建会导致旧 tasker 泄漏且与同一 controller 竞争（滑动/多指操作卡死根因）
                if (!maa.hasTasker() && !maa.createTasker()) {
                    Ln.e("$TAG createTasker failed")
                    sendLog("任务器创建失败（引擎初始化异常）")
                    running = false
                    return@launch
                }
                sendLog(if (maa.hasTasker()) "任务器就绪（复用）" else "任务器创建失败")
                sendLog("MaaFramework 版本：${maa.version}")
                // 预览投递恢复：任务初始化完成（控制器/任务器就绪）后，重新投递预览 Surface——
                // MaaFramework 接管 libbridge capturer 后预览投递可能失效（现象：有声音但预览黑屏，
                // 切页回来才恢复），这里先 null 再重投，强制 libbridge 重建预览渲染线程
                runCatching {
                    monitorSurface?.let { s ->
                        BridgeNativeLib.setPreviewSurface(null)
                        BridgeNativeLib.setPreviewSurface(s)
                        Ln.i("$TAG 任务初始化完成，已重投预览 Surface")
                    }
                }.onFailure { Ln.w("$TAG 任务初始化后预览重投失败: ${it.message}") }
                // 方案A：拉起 Agent 独立进程（agent server）——FindToChallenge 等 Custom 节点在独立进程执行（走 ZMQ 转发，不死锁）
                runCatching {
                    remoteScope.launch {
                        runCatching {
                            if (AgentManager.start(context, maa.resourceHandle, userDir ?: "")) {
                                Ln.i("$TAG AgentManager 已连接（agent 独立进程）")
                                sendLog("Agent 独立进程已连接")
                            } else {
                                Ln.w("$TAG AgentManager 连接失败（Custom 节点用引擎内回调）")
                                sendLog("Agent 独立进程连接失败")
                            }
                        }.onFailure { Ln.w("$TAG AgentManager 启动异常: ${it.message}") }
                    }
                }.onFailure { Ln.w("$TAG AgentManager 异步启动调度失败: ${it.message}") }
                // 注册任务事件回调（事件驱动：任务开始/完成/失败时广播，无需轮询）
                // + 节点事件回调（显示节点 focus 文案）
                registerEventSink(maa)
                registerFocusSink(maa, resourceBase)

                sendLog("引擎就绪，开始执行任务")
                // 注意：focus 监听（MaaTaskerAddContextSink）确认是 v5.13.0-beta.2 引擎崩溃元凶：
                // 注册后节点事件回调会导致引擎随机崩溃/卡死（启动即死或滑动卡死）。
                // 暂时禁用；focus 显示改用 App 侧解析 maafw.log 的安全方案（不碰引擎/maafw）。
                // registerFocusSink(maa, resourceBase)

                for (i in 0 until tasks.length()) {
                    val task = tasks.getJSONObject(i)
                    val entry = task.optString("entry", "")
                    val options = task.optJSONObject("options")?.let { obj ->
                        val map = mutableMapOf<String, String>()
                        obj.keys().forEach { key -> map[key] = obj.optString(key, "") }
                        map
                    } ?: emptyMap()
                    val override = task.optString("pipeline_override", "").takeIf { it.isNotBlank() }
                    Ln.i("$TAG start task $entry options=$options")
                    sendLog("开始任务 $entry")
                    if (engineConfig.verboseLogging) {
                        sendLog("详细：任务 $entry 选项=$options")
                    }
                    if (!maa.startTask(entry, override)) {
                        Ln.e("$TAG startTask $entry failed")
                        sendLog("任务 $entry 启动失败")
                        maa.markTaskStatus(-1) // 启动失败 -> 标记异常，下次任务重建
                        break
                    }
                    val status = maa.waitTask()
                    Ln.i("$TAG task $entry finished status=$status")
                    // 暂停中断：显示"已暂停"，不再误报"结束状态0"
                    if (isPausedByUser) {
                        sendLog("任务 $entry 已暂停")
                        break
                    }
                    sendLog("任务 $entry 结束，状态 $status")
                    if (status != 3000) break
                }
                // 暂停中断时不再显示误导性的"全部任务执行完毕"
                sendLog(if (isPausedByUser) "任务已暂停" else "全部任务执行完毕")
            } catch (e: Exception) {
                Ln.e("$TAG engine error: ${e.message}")
                e.printStackTrace()
                // 输出完整堆栈，便于定位问题
                sendLog("引擎异常：${e.message}\n${e.stackTraceToString()}")
                engine?.markTaskStatus(-1)
            } finally {
                // ——性能优化：任务正常结束保留引擎复用（跳过下次资源/模型重载）；异常则销毁重建——
                engine?.let { maa ->
                    runCatching { maa.stopTask() }
                    // 用户暂停中断：保留引擎（继续时复用，避免引擎被销毁后继续失败）
                    if (!isPausedByUser && maa.needRebuild()) {
                        Ln.w("$TAG 任务异常结束，释放引擎（下次任务重建）")
                        runCatching { kotlinx.coroutines.runBlocking { maa.destroy() } }
                        engine = null
                    } else {
                        runCatching { maa.clearCache() }
                        Ln.i("$TAG 任务结束，引擎保留复用（暂停中断也保留）")
                    }
                }
                running = false
                // 任务链全部结束 -> 统一上报 running=false（避免子任务切换时 App 按钮闪烁）
                sendRunningState(false, null)
                // 后台保障：释放唤醒锁 + 停止虚拟屏保亮（任务结束）
                releaseEngineWakeLock()
                stopVdKeepAlive()
                stopFpsSampler()
                // P1-2：任务结束停止 logcat 抓取
                stopLogcatCapture()
                // P0-7 完整闭环：任务结束（非暂停）恢复被静音的游戏音量——
                // 引擎驻留复用场景引擎不退出，performEmergencyCleanup 不会触发，必须在这里主动恢复
                if (!isPausedByUser && audioMutedByEngine) {
                    runCatching { setAudioMutedInternal(false) }
                    audioMutedByEngine = false
                    sendLog("任务结束，已恢复游戏音量")
                }
                // 任务结束（非用户暂停）且开启「任务结束后关闭游戏」时：关闭游戏进程 + 释放虚拟屏
                if (!isPausedByUser && engineConfig.closeGameAfterTask) {
                    sendLog("任务结束，按设置关闭游戏并释放虚拟屏")
                    runCatching { stopPackage("com.tencent.KiHan") }
                    runCatching { MaaFwVirtualDisplay.stop() }
                }
            }
        }
        return true
    }

    override fun stopTask() {
        Ln.i("$TAG stopTask()")
        runCatching { stopAll() }
    }

    /**
     * 暂停任务：只取消任务执行循环，不销毁 MaaController/capturer/虚拟屏，
     * 保证暂停后游戏画面与投屏继续（UI 层叠加"已暂停"遮罩）。
     */
    override fun pauseTask() {
        Ln.i("$TAG pauseTask()")
        isPausedByUser = true
        // 关键：先 PostStop 当前任务，让 waitTask() 立即返回（native 阻塞协程无法被 cancel 中断），
        // 否则暂停后脚本仍会继续执行。不销毁 controller/capturer/虚拟屏，投屏继续。
        runCatching { engine?.stopTask() }
        engineJob?.cancel()
        engineJob = null
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun currentTask(): String = engine?.let { "" } ?: ""

    override fun startActivity(intent: Intent?): Boolean {
        if (intent == null) return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    override fun startActivityOnDisplay(intent: Intent?, displayId: Int): Boolean {
        if (intent == null) return false
        return MaaFwActivityHelper.startActivity(intent, displayId)
    }

    override fun isPackageInstalled(packageName: String?): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName ?: return false, 0)
            true
        }.getOrDefault(false)
    }

    override fun moveAppToVirtualDisplay(packageName: String?): Boolean {
        val displayId = MaaFwVirtualDisplay.getDisplayId()
        if (displayId < 0 || packageName.isNullOrBlank()) return false
        return MaaFwActivityHelper.moveAppTaskToDisplay(packageName, displayId)
    }

    override fun setDisplayPower(on: Boolean) {
        val displayId = MaaFwVirtualDisplay.getDisplayId()
        if (displayId < 0) return
        runCatching {
            ServiceManager.getDisplayManager().requestDisplayPower(displayId, on)
            Ln.i("$TAG setDisplayPower($on) displayId=$displayId")
        }.onFailure {
            Ln.e("$TAG setDisplayPower failed: ${it.message}")
        }
    }

    override fun setPreviewEnabled(enabled: Boolean) {
        Ln.i("$TAG setPreviewEnabled($enabled)")
        previewEnabled = enabled
        runCatching {
            // 关屏：停止把虚拟屏帧投递到预览 Surface（不再投屏）
            // 亮屏：恢复投递之前保存的预览 Surface
            BridgeNativeLib.setPreviewSurface(if (enabled) monitorSurface else null)
            // 同时控制虚拟屏电源状态，保证画面真正熄灭/点亮
            val displayId = MaaFwVirtualDisplay.getDisplayId()
            if (displayId >= 0) {
                runCatching { ServiceManager.getDisplayManager().requestDisplayPower(displayId, enabled) }
            }
        }.onFailure {
            Ln.e("$TAG setPreviewEnabled failed: ${it.message}")
        }
    }

    /** 强制重建预览渲染线程（libbridge SetPreviewSurface 对相同 Surface 直接 return，须先 null 再同 Surface 重启） */
    private fun refreshPreviewSurface() {
        if (!previewEnabled) return
        val s = monitorSurface ?: return
        runCatching { BridgeNativeLib.setPreviewSurface(null) }
            .onFailure { Ln.w("$TAG refreshPreviewSurface: 停旧预览失败: ${it.message}") }
        runCatching { BridgeNativeLib.setPreviewSurface(s) }
            .onFailure { Ln.w("$TAG refreshPreviewSurface: 重建预览失败: ${it.message}") }
        Ln.i("$TAG refreshPreviewSurface: 预览渲染线程已重建")
    }

    override fun captureFramePng(dirPath: String?): String? {
        if (dirPath.isNullOrBlank()) return null
        val bitmap = BridgeNativeLib.getFrameBufferBitmap() ?: run {
            Ln.w("$TAG captureFramePng: no frame available")
            return null
        }
        return try {
            val dir = java.io.File(dirPath).apply { mkdirs() }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(dir, "screenshot_$timestamp.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            Ln.i("$TAG captureFramePng saved ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Ln.e("$TAG captureFramePng error: ${e.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    override fun stopPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName))
            p.waitFor() == 0
        }.getOrDefault(false)
    }

    override fun setAudioMuted(muted: Boolean): Boolean {
        val ok = setAudioMutedInternal(muted)
        if (ok) {
            // 记录静音状态：紧急清理（performEmergencyCleanup）时恢复，防游戏永久静音
            audioMutedByEngine = muted
        }
        return ok
    }

    /** 静音/恢复游戏音量（shell 三通道：AudioManager -> cmd audio -> su） */
    private fun setAudioMutedInternal(muted: Boolean): Boolean {
        return runCatching {
            // 1) 优先 AudioManager.setStreamMute（shell 有 MODIFY_AUDIO_SETTINGS）
            var ok = runCatching {
                val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        if (muted) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE, 0)
                } else {
                    @Suppress("DEPRECATION")
                    am.setStreamMute(android.media.AudioManager.STREAM_MUSIC, muted)
                }
                true
            }.getOrDefault(false)
            // 2) fallback：cmd audio（API 30+）
            if (!ok) {
                ok = runCatching {
                    val cmd = "cmd audio set-stream-mute ${android.media.AudioManager.STREAM_MUSIC} ${if (muted) 1 else 0}"
                    Runtime.getRuntime().exec(cmd).waitFor() == 0
                }.getOrDefault(false)
            }
            // 3) fallback：su
            if (!ok) {
                ok = runCatching {
                    val cmd = "media volume --stream ${android.media.AudioManager.STREAM_MUSIC} --set ${if (muted) 0 else 100}"
                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
                }.getOrDefault(false)
            }
            Ln.i("$TAG setAudioMuted($muted) ok=$ok")
            ok
        }.getOrDefault(false)
    }

    // ==================== P0 权限：省电豁免 + 后台不受限（shell 身份代授） ====================

    /**
     * 为指定包授予权限位：1=省电豁免 2=后台不受限 4=通知 8=悬浮窗 16=存储 32=无障碍。
     * 引擎以 shell 身份代授（shell 可改 PowerManager 白名单 + AppOps），
     * 用于任务启动前给游戏授予"忽略电池优化 + 后台不受限"（vivo/澎湃后台杀游戏防护）。
     * @return 实际授予成功的权限位掩码
     */
    override fun grantPermissions(packageName: String?, permissions: Int): Int {
        if (packageName.isNullOrBlank()) return 0
        var granted = 0
        // 1) 忽略电池优化（省电豁免）——shell 可直接调 PowerManager 隐藏 API
        if (permissions and 1 != 0) {
            runCatching {
                val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                val m = pm.javaClass.getMethod(
                    "setPowerSaveWhitelistApp", String::class.java, Boolean::class.java
                ) // @hide
                m.isAccessible = true
                m.invoke(pm, packageName, true)
                granted = granted or 1
            }.onFailure { Ln.w("$TAG grantPermissions: 省电豁免失败 ${it.message}") }
        }
        // 2) 后台不受限（AppOps 允许后台运行）——shell 可改 AppOps
        if (permissions and 2 != 0) {
            runCatching {
                val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
                // OP_RUN_IN_BACKGROUND = 63, OP_RUN_ANY_IN_BACKGROUND = 64
                val mode = android.app.AppOpsManager.MODE_ALLOWED
                val setMode = appOps.javaClass.getMethod(
                    "setMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    String::class.java, Int::class.javaPrimitiveType
                )
                setMode.isAccessible = true
                setMode.invoke(appOps, 63, uid, packageName, mode)
                setMode.invoke(appOps, 64, uid, packageName, mode)
                granted = granted or 2
            }.onFailure { Ln.w("$TAG grantPermissions: 后台不受限失败 ${it.message}") }
        }
        // 32) 无障碍防杀服务（写 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES，
        //     root / Shizuku root 可写；Shizuku shell 部分可写，失败无害）
        if (permissions and 32 != 0) {
            runCatching {
                val svcId = "com.maafw.naruto/.service.KeepAliveAccessibilityService"
                // 读取现有服务列表（多服务冒号分隔），追加而非覆盖
                val cur = runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "settings get secure enabled_accessibility_services"))
                    p.inputStream.bufferedReader().readText().trim()
                }.getOrDefault("").removePrefix("null").trim()
                val merged = when {
                    cur.isBlank() -> svcId
                    cur.contains(svcId) -> cur
                    else -> "$cur:$svcId"
                }
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "settings put secure enabled_accessibility_services '$merged'"))
                    .waitFor()
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "settings put secure accessibility_enabled 1"))
                    .waitFor()
                granted = granted or 32
                Ln.i("$TAG 无障碍防杀服务已启用：$svcId（merged=$merged）")
            }.onFailure { Ln.w("$TAG grantPermissions: 无障碍启用失败（需 root/Shizuku 权限） ${it.message}") }
        }
        Ln.i("$TAG grantPermissions($packageName, $permissions) granted=$granted")
        return granted
    }

    /** 游戏进程存活探测：0=存活 1=死亡 2=未知（GameWatchdog 运行期守护用，pidof 判断） */
    override fun isAppAlive(packageName: String?): Int {
        if (packageName.isNullOrBlank()) return 2
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("pidof", packageName))
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.isNotEmpty()) 0 else 1
        }.getOrDefault(2)
    }

    /** 游戏是否仍在虚拟屏上（GameWatchdog 漂移检测用；无法判断/未创建虚拟屏时宽松返回 true） */
    override fun isAppOnVirtualDisplay(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        val displayId = MaaFwVirtualDisplay.getDisplayId()
        if (displayId < 0) return true
        return MaaFwActivityHelper.isAppOnDisplay(packageName, displayId)
    }

    override fun injectTouch(action: Int, x: Int, y: Int): Boolean {
        val displayId = MaaFwVirtualDisplay.getDisplayId()
        if (displayId < 0) {
            Ln.w("$TAG injectTouch: display not ready")
            return false
        }
        val ok = runCatching {
            // 同一手势的 DOWN/MOVE/UP 必须共享同一 downTime，否则滑动/长按会被系统识别为多次点击
            when (action) {
                android.view.MotionEvent.ACTION_DOWN -> currentDownTime = SystemClock.uptimeMillis()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> currentDownTime = 0L
            }
            val eventTime = SystemClock.uptimeMillis()
            val down = if (currentDownTime != 0L) currentDownTime else eventTime
            val px = x.toFloat()
            val py = y.toFloat()
            // scrcpy 风格：显式 source=SOURCE_TOUCHSCREEN + PointerProperties/Coords。
            // 关键：source=0 的 MotionEvent 会被 InputDispatcher 判定为无效事件并丢弃，
            // 即使 injectInputEvent 返回 true 游戏也不会响应。
            val props = arrayOf(android.view.MotionEvent.PointerProperties().apply {
                id = 0
                toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply {
                this.x = px
                this.y = py
                pressure = 1f
                size = 1f
            })
            val event = android.view.MotionEvent.obtain(
                down, eventTime, action, 1, props, coords,
                0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try {
                com.maafw.naruto.third.wrappers.InputManager.setDisplayId(event, displayId)
                // 原版策略：先 WAIT 保证事件送达（多指/滑动时序严格），失败回退 ASYNC
                val waitOk = ServiceManager.getInputManager().injectInputEvent(
                    event,
                    com.maafw.naruto.third.wrappers.InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
                )
                if (!waitOk) {
                    ServiceManager.getInputManager().injectInputEvent(
                        event,
                        com.maafw.naruto.third.wrappers.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
                    )
                }
                true
            } finally {
                event.recycle()
            }
        }.getOrDefault(false)
        if (ok) {
            // 广播触摸事件，供触摸预览显示脚本触摸位置
            sendTouchEvent(action, x, y)
        } else {
            Ln.w("$TAG injectTouch failed action=$action x=$x y=$y displayId=$displayId")
        }
        // 触摸日志只进引擎日志（maafw.log，导出时可见），不显示在 App 日志页
        Ln.i("$TAG injectTouch action=$action x=$x y=$y displayId=$displayId ok=$ok")
        return ok
    }

    override fun injectMultiTouch(action: Int, points: IntArray?, actionIndex: Int): Boolean {
        val displayId = MaaFwVirtualDisplay.getDisplayId()
        if (displayId < 0 || points == null || points.size % 2 != 0 || points.isEmpty()) {
            Ln.w("$TAG injectMultiTouch: invalid params displayId=$displayId points=${points?.size}")
            return false
        }
        val n = points.size / 2
        val ok = runCatching {
            // 同一手势共享 downTime
            when (action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_POINTER_DOWN -> currentDownTime = SystemClock.uptimeMillis()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_POINTER_UP,
                android.view.MotionEvent.ACTION_CANCEL -> currentDownTime = 0L
            }
            val downTime = if (currentDownTime != 0L) currentDownTime else SystemClock.uptimeMillis()
            val props = Array(n) { i ->
                android.view.MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(n) { i ->
                android.view.MotionEvent.PointerCoords().apply {
                    x = points[i * 2].toFloat()
                    y = points[i * 2 + 1].toFloat()
                    pressure = 1f
                    size = 1f
                }
            }
            var act = action
            // POINTER_DOWN/UP 需要把手指序号编码进 action
            if ((action == android.view.MotionEvent.ACTION_POINTER_DOWN || action == android.view.MotionEvent.ACTION_POINTER_UP)
                && actionIndex >= 0 && actionIndex < n
            ) {
                act = action or (actionIndex shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            val event = android.view.MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), act, n, props, coords,
                0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try {
                com.maafw.naruto.third.wrappers.InputManager.setDisplayId(event, displayId)
                // 原版策略：先 WAIT 保证事件送达（多指/滑动时序严格），失败回退 ASYNC
                val waitOk = ServiceManager.getInputManager().injectInputEvent(
                    event,
                    com.maafw.naruto.third.wrappers.InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
                )
                if (!waitOk) {
                    ServiceManager.getInputManager().injectInputEvent(
                        event,
                        com.maafw.naruto.third.wrappers.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
                    )
                }
                true
            } finally {
                event.recycle()
            }
        }.getOrDefault(false)
        if (ok) {
            // 广播触摸预览（取第一个手指位置）
            sendTouchEvent(action, points[0], points[1])
        }
        Ln.i("$TAG injectMultiTouch action=$action n=$n idx=$actionIndex ok=$ok")
        return ok
    }

    override fun injectKey(keyCode: Int): Boolean {
        val displayId = MaaFwVirtualDisplay.getDisplayId()
        if (displayId < 0) return false
        return runCatching {
            val downTime = SystemClock.uptimeMillis()
            val downEvent = android.view.KeyEvent(
                downTime, downTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0,
                0, -1, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD
            )
            val upEvent = android.view.KeyEvent(
                downTime, downTime, android.view.KeyEvent.ACTION_UP, keyCode, 0,
                0, -1, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD
            )
            try {
                com.maafw.naruto.third.wrappers.InputManager.setDisplayId(downEvent, displayId)
                com.maafw.naruto.third.wrappers.InputManager.setDisplayId(upEvent, displayId)
                val inputManager = ServiceManager.getInputManager()
                inputManager.injectInputEvent(downEvent, com.maafw.naruto.third.wrappers.InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT)
                inputManager.injectInputEvent(upEvent, com.maafw.naruto.third.wrappers.InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT)
            } catch (e: Exception) {
                Ln.e("$TAG injectKey failed: ${e.message}")
                false
            }
            true
        }.getOrDefault(false)
    }

        override fun getDisplayResolution(): IntArray {
        val cfg = MaaFwVirtualDisplay.getConfig()
        return intArrayOf(cfg.width, cfg.height)
    }
// ==================== P1-2：任务期间持续抓取 logcat（按 pid 落盘） ====================

    private var logcatProc: java.lang.Process? = null

    /** 任务开始时启动：logcat -T10 按 App/引擎 pid 过滤，持续写入 userDir/debug/logcat/（任务结束自动停止） */
    private fun startLogcatCapture() {
        if (logcatProc != null) return
        try {
            val baseDir = userDir?.let { java.io.File(it, "debug/logcat") }
                ?: java.io.File("/data/local/tmp", "maafw_logcat_${context.packageName}")
            baseDir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val out = java.io.File(baseDir, "logcat_$ts.log")
            val enginePid = Process.myPid()
            val appPid = appPid.get()
            val cmd = if (appPid > 0) {
                arrayOf("logcat", "-T", "10", "-v", "time", "--pid=$appPid", "--pid=$enginePid")
            } else {
                arrayOf("logcat", "-T", "10", "-v", "time", "--pid=$enginePid")
            }
            val p = Runtime.getRuntime().exec(cmd)
            logcatProc = p
            Thread {
                runCatching { p.inputStream.use { it.copyTo(out.outputStream()) } }
            }.apply { name = "logcat-capture"; isDaemon = true }.start()
            Ln.i("$TAG 任务期间 logcat 抓取已启动 -> ${out.absolutePath}")
        } catch (e: Throwable) {
            Ln.w("$TAG startLogcatCapture failed: ${e.message}")
        }
    }

    /** 任务结束/引擎清理时停止 logcat 抓取 */
    private fun stopLogcatCapture() {
        runCatching { logcatProc?.destroy() }
        logcatProc = null
    }

/** 抓取 logcat（shell 进程有 READ_LOGS；按 App/引擎 pid 过滤，解决"一次性 dump 返回空/43字节"问题） */
    override fun captureLogcat(lines: Int): String? {
        return runCatching {
            val n = if (lines > 0) lines else 20000
            // P1-2：按 pid 过滤——App pid（heartbeat 喂入）+ 引擎自身 pid，只抓本应用相关日志
            val enginePid = Process.myPid()
            val appPid = appPid.get()
            val cmd = if (appPid > 0) {
                arrayOf("logcat", "-d", "-t", n.toString(), "-v", "time",
                    "--pid=$appPid", "--pid=$enginePid")
            } else {
                arrayOf("logcat", "-d", "-t", n.toString(), "-v", "time", "--pid=$enginePid")
            }
            val proc = Runtime.getRuntime().exec(cmd)
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            text
        }.getOrNull()
    }

    /** 虚拟屏游戏真实渲染帧率（纯 Kotlin：轮询原版 getFrameCount 增量计算，不改 libbridge） */
    override fun getFps(): Double {
        return gameFpsState.get()
    }

    /** 脚本识别频率（每秒 Node.Recognition 事件数，MaaFramework Kotlin 事件统计；脚本卡住时归零） */
    override fun getScriptFps(): Double {
        return scriptFpsState.get()
    }

    /** Agent 独立进程是否已连接（FindToChallenge 等 Custom 节点走 agent 执行） */
    override fun isAgentConnected(): Boolean {
        return runCatching { AgentManager.isConnected }.getOrDefault(false)
    }

    /** 部署 libbridge.so 到 /data/local/tmp（data 分区可执行）：App 进程读取 so 字节传入，引擎写入 */
    override fun deployBridge(data: ByteArray?): Boolean {
        if (data == null || data.isEmpty()) return false
        return runCatching {
            val f = java.io.File("/data/local/tmp", "maafw_lib_${context.packageName}/libbridge.so")
            f.parentFile?.mkdirs()
            f.writeBytes(data)
            f.setReadable(true, false)
            f.setExecutable(true, false)
            // 注意：立即把 JNI 注册切到 tmp 副本：init 时 System.loadLibrary("bridge") 加载的是 APK 内副本，
            // 与 MaaFramework dlopen(tmp) 是两份独立 ELF，capturer 状态不共享 -> GetLockedPixels 空帧。
            runCatching { System.load(f.absolutePath) }
                .onFailure { Ln.w("$TAG deployBridge: System.load(tmp libbridge) 失败: ${it.message}") }
            Ln.i("$TAG libbridge.so 已通过 binder 部署到 ${f.absolutePath}（${data.size} 字节）")
            true
        }.getOrDefault(false)
    }

    /** 启动帧率采样协程（500ms 采样一次：游戏帧数增量 + 识别事件数 -> 平滑 FPS） */
    private fun startFpsSampler() {
        if (fpsSamplerJob != null) return
        synchronized(fpsSamplerLock) {
            fpsLastCount = -1L
            fpsLastTime = 0L
            recogCount = 0L
            recogCountLast = 0L
            recogTimeLast = 0L
        }
        fpsSamplerJob = remoteScope.launch {
            while (true) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                // 游戏 FPS：getFrameCount 增量
                runCatching {
                    val cnt = BridgeNativeLib.getFrameCount()
                    synchronized(fpsSamplerLock) {
                        if (fpsLastCount >= 0 && fpsLastTime > 0 && cnt > fpsLastCount) {
                            val dtMs = (now - fpsLastTime).coerceAtLeast(1L)
                            val fps = (cnt - fpsLastCount) * 1000.0 / dtMs
                            // 平滑：与上次值 70/30 加权
                            gameFpsState.set(gameFpsState.get() * 0.3 + fps * 0.7)
                        }
                        fpsLastCount = cnt
                        fpsLastTime = now
                    }
                }.onFailure { /* libbridge 未加载或接口缺失时保持 0 */ }
                // 脚本识别频率：ContextSink 计数
                val events = recogEventCounter.getAndSet(0L)
                synchronized(fpsSamplerLock) {
                    if (recogTimeLast > 0) {
                        val dtMs = (now - recogTimeLast).coerceAtLeast(1L)
                        val fps = events * 1000.0 / dtMs
                        scriptFpsState.set(scriptFpsState.get() * 0.3 + fps * 0.7)
                    }
                    recogTimeLast = now
                }
            }
        }
    }

    private fun stopFpsSampler() {
        fpsSamplerJob?.cancel()
        fpsSamplerJob = null
    }

    override fun hardwareScreenOff() {
        var ok = runCatching {
            // 1) 反射 PowerManager.goToSleep（shell 有 DEVICE_POWER）
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val m = pm.javaClass.getMethod("goToSleep", Long::class.java, Int::class.java, Int::class.java)
            m.invoke(pm, SystemClock.uptimeMillis() + 1, 0, 0)
            true
        }.getOrDefault(false)
        if (!ok) {
            // 2) input keyevent 26
            ok = runCatching { Runtime.getRuntime().exec("input keyevent 26").waitFor() == 0 }.getOrDefault(false)
        }
        if (!ok) {
            // 3) su
            ok = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26")).waitFor() == 0 }.getOrDefault(false)
        }
        Ln.i("$TAG hardwareScreenOff ok=$ok")
    }

    override fun setResolution(width: Int, height: Int, dpi: Int): Boolean {
        return runCatching {
            MaaFwVirtualDisplay.setResolution(width, height, dpi)
            Ln.i("$TAG setResolution($width x $height @ $dpi)")
            true
        }.getOrDefault(false)
    }

    private fun stopAll() {
    engineJob?.cancel()
    engineJob = null
    stopVdKeepAlive()
    releaseEngineWakeLock()
    stopFpsSampler()
    runBlocking { kotlin.runCatching { engine?.destroy() } }
    engine = null
    running = false
}

    private fun sendLog(message: String) {
        // 1) binder 回调（可靠，同通道）
        notifyLog(message)
        // 2) 广播兜底（兼容旧逻辑）
        try {
            val intent = Intent("com.maafw.naruto.REMOTE_LOG").apply {
                setPackage(com.maafw.naruto.BuildConfig.APPLICATION_ID)
                putExtra("log", message)
                putExtra("running", running)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Ln.e("sendLog failed: ${e.message}")
        }
    }

    /** 广播脚本触摸事件（供触摸预览显示脚本触摸位置） */
    private fun sendTouchEvent(action: Int, x: Int, y: Int) {
        try {
            val intent = Intent("com.maafw.naruto.TOUCH_EVENT").apply {
                setPackage(com.maafw.naruto.BuildConfig.APPLICATION_ID)
                putExtra("action", action)
                putExtra("x", x)
                putExtra("y", y)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Ln.e("sendTouchEvent failed: ${e.message}")
        }
    }

    /** 回调触摸事件给已注册的 listener（binder 直达，不依赖广播） */
    private fun notifyTouch(action: Int, x: Int, y: Int) {
        val n = statusListeners.beginBroadcast()
        try {
            for (i in 0 until n) {
                runCatching { statusListeners.getBroadcastItem(i).onTouch(action, x, y) }
            }
        } finally {
            statusListeners.finishBroadcast()
        }
    }

    /** 任务事件回调（事件驱动，任务开始/完成/失败时广播状态，供 UI 同步） */
    private fun registerEventSink(maa: MaaFrameworkEngine) {
        val sink = object : com.maafw.naruto.maa.MaaEventCallback {
            override fun invoke(handle: com.sun.jna.Pointer?, message: String?, detailsJson: String?, transArg: com.sun.jna.Pointer?) {
                try {
                    when (message) {
                "Tasker.Task.Starting" -> {
                    val entry = runCatching { org.json.JSONObject(detailsJson ?: "{}").optString("entry", "") }
                        .getOrDefault("")
                    // 过滤内部 PostStop 任务（暂停/停止时触发，避免"任务开始：MaaTaskerPostStop"刷屏）
                    if (entry == "MaaTaskerPostStop") return@invoke
                    sendLog(if (entry.isNotBlank()) "任务开始：$entry" else "任务开始")
                    sendRunningState(true, entry)
                    // 分段进度：任务链开始
                    notifyTaskEvent(entry, "started")
                }
                // ⚠️ 子任务（如 share→group 切换）完成时不再发 running=false：
                // 否则 App 的开始按钮会在任务链切换瞬间闪一下。真正的结束由任务链 finally 统一上报。
                "Tasker.Task.Succeeded" -> {
                    val entry = runCatching { org.json.JSONObject(detailsJson ?: "{}").optString("entry", "") }
                        .getOrDefault("")
                    if (entry == "MaaTaskerPostStop") return@invoke
                    sendLog("任务完成")
                    notifyTaskEvent(entry, "succeeded")
                }
                "Tasker.Task.Failed" -> {
                    val entry = runCatching { org.json.JSONObject(detailsJson ?: "{}").optString("entry", "") }
                        .getOrDefault("")
                    sendLog("任务失败")
                    notifyTaskEvent(entry, "failed")
                }
            }
                } catch (e: Exception) {
                    Ln.w("eventSink error: ${e.message}")
                }
            }
        }
        maa.addSink(sink)
    }

    /**
     * 节点事件回调：显示 pipeline 节点「当前在做什么」。
     * MaaFramework 的 focus 字段携带在 **Node.Action.Starting**（旧事件名）中
     * （见日志：Action.Starting 的 details 有 focus，ActionNode.Starting 为 null）。
     *
     * 注意：关键设计（防卡顿）：
     * 1. 监听动作类事件（Node.Action.Starting / Node.ActionNode.Starting），
     *    识别循环的 Recognition/PipelineNode 高频事件全部忽略；
     * 2. **异步发送**：回调在 MaaFramework 执行线程内，sendLog 是同步 binder 广播，
     *    直接发会阻塞引擎。改为投递到独立单线程队列异步发送；
     * 3. **去重**：同一 focus 文案 1s 内只发一次，避免连续节点重复刷屏。
     */
    private fun registerFocusSink(maa: MaaFrameworkEngine, resourceBase: String) {
        // focus 异步发送队列（单线程，避免阻塞 MaaFramework 执行线程）
        val focusSender = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "focus-log-sender").apply { isDaemon = true }
        }
        val lastSent = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val sink = object : com.maafw.naruto.maa.MaaEventCallback {
            override fun invoke(handle: com.sun.jna.Pointer?, message: String?, detailsJson: String?, transArg: com.sun.jna.Pointer?) {
                try {
                    // 脚本识别频率统计：识别事件（含高频的 Recognition.*）计数，供 fps 采样器计算
                    // （不 sendLog，避免刷屏；仅原子计数）
                    if (message != null && message.startsWith("Node.Recognition.")) {
                        recogEventCounter.incrementAndGet()
                        return
                    }
                    // 只监听动作类事件（focus 携带在 Node.Action.Starting）
                    if (message != "Node.Action.Starting" && message != "Node.ActionNode.Starting") return
                    val json = org.json.JSONObject(detailsJson ?: "{}")
                    val focus = json.opt("focus") // focus: any（字符串或数组）
                    val focusText = when (focus) {
                        is String -> focus
                        is org.json.JSONArray -> (0 until focus.length())
                            .joinToString(" ") { focus.optString(it, "") }
                        else -> ""
                    }
                    if (focusText.isBlank()) return

                    // 去重：同一文案 1s 内只发一次
                    val now = SystemClock.elapsedRealtime()
                    val last = lastSent[focusText]
                    if (last != null && now - last < 1_000) return
                    lastSent[focusText] = now

                    // 异步发送，不阻塞 MaaFramework 线程
                    focusSender.execute { sendLog(focusText) }
                } catch (e: Exception) {
                    Ln.w("focusSink error: ${e.message}")
                }
            }
        }
        maa.addContextSink(sink)
        Ln.i("$TAG focus 节点事件监听已注册（动作事件，异步防卡顿）")
    }

    /** 广播运行状态 + 当前任务（binder 回调 + 广播兜底，事件驱动不轮询） */
    private fun sendRunningState(running: Boolean, currentEntry: String?) {
        notifyStatus(running, currentEntry)
        try {
            val intent = Intent("com.maafw.naruto.REMOTE_LOG").apply {
                setPackage(com.maafw.naruto.BuildConfig.APPLICATION_ID)
                putExtra("running", running)
                putExtra("current", currentEntry ?: "")
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Ln.e("sendRunningState failed: ${e.message}")
        }
    }

    // ==================== binder 状态回调（可靠事件驱动，不依赖广播） ====================

    private val statusListeners = android.os.RemoteCallbackList<IEngineStatusListener>()

    // 触摸通知异步发送线程：避免同步 binder 阻塞触摸注入（Swipe 卡死根因）
    private val touchNotifyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // 触摸通知限流：DOWN/UP 必发，MOVE 高频时合并（最低间隔 ms）
    private val lastTouchNotifyTime = java.util.concurrent.atomic.AtomicLong(0L)
    private val TOUCH_NOTIFY_MIN_INTERVAL = 50L

    override fun registerStatusListener(listener: IEngineStatusListener?) {
        if (listener != null) statusListeners.register(listener)
    }

    override fun unregisterStatusListener(listener: IEngineStatusListener?) {
        if (listener != null) statusListeners.unregister(listener)
    }

    /** 日志/状态广播互斥锁：RemoteCallbackList.beginBroadcast() 不可并发（并发会导致 beginBroadcast 崩溃） */
    private val notifyLock = Any()

    /** 回调日志给已注册的 listener  */
    private fun notifyLog(message: String) {
        synchronized(notifyLock) {
            val n = statusListeners.beginBroadcast()
            try {
                for (i in 0 until n) {
                    runCatching { statusListeners.getBroadcastItem(i).onLog(message) }
                }
            } finally {
                statusListeners.finishBroadcast()
            }
        }
    }
/** 回调运行状态 + 当前任务给 listener  */
    private fun notifyStatus(running: Boolean, currentEntry: String?) {
        synchronized(notifyLock) {
            val n = statusListeners.beginBroadcast()
            try {
                for (i in 0 until n) {
                    runCatching { statusListeners.getBroadcastItem(i).onStatusChanged(running, currentEntry) }
                }
            } finally {
                statusListeners.finishBroadcast()
            }
        }
    }

    /** 回调任务链事件给 listener（分段进度用：started/succeeded/failed） */
    private fun notifyTaskEvent(entry: String, event: String) {
        synchronized(notifyLock) {
            val n = statusListeners.beginBroadcast()
            try {
                for (i in 0 until n) {
                    runCatching { statusListeners.getBroadcastItem(i).onTaskEvent(entry, event) }
                }
            } finally {
                statusListeners.finishBroadcast()
            }
        }
    }
}