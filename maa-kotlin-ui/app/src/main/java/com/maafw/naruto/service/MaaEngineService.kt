package com.maafw.naruto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.maafw.naruto.IRemoteEngineService
import com.maafw.naruto.MainActivity
import com.maafw.naruto.R
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.model.AssetLoader
import com.maafw.naruto.model.OptionOverrideBuilder
import com.maafw.naruto.remote.RemoteEngineServiceImpl
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * 前台服务
 * 用于定时任务等场景：绑定远端 Shizuku UserService 并执行默认 profile。
 */
class MaaEngineService : Service() {

    companion object {
        private const val TAG = "MaaEngineService"
        private const val NOTIFICATION_ID = 0x4D4141
        private const val CHANNEL_ID_FOREGROUND = "maa_engine_foreground_v2"
        private const val CHANNEL_ID_EVENT = "maa_engine_event"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var remoteService: IRemoteEngineService? = null
    @Volatile
    private var remoteBound = false
    // Root 唤醒开启时，引擎走 root 进程（su + app_process），不依赖 Shizuku
    private var useRootEngineMode = false
    // 任务期间临时保活标记（用户未开启保活时，任务期间自动保活、结束自动停）
    private var tempKeepAlive = false
    // 后台唤醒：任务执行期间持有部分唤醒锁，锁屏/熄屏也不让 CPU 休眠
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val notificationCoordinator by lazy { TaskNotificationCoordinator(this) }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "MaaFW:schedule"
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) // 最多 30 分钟自动释放
            }
            Log.i(TAG, "已持有后台唤醒锁")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    /**
     * 把应用私有外部目录传给远端 shell 进程，避免 shell 里 ShellContext 调 getExternalFilesDir 触发 UID 校验
     */
    private fun getUserDir(): String? = runCatching { getExternalFilesDir(null)?.absolutePath }.getOrNull()

    private val remoteConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteService = IRemoteEngineService.Stub.asInterface(service)
            remoteBound = true
            Log.i(TAG, "远端引擎已绑定")
            // P0-A 双引擎收敛：自己绑定的引擎也写入共享（供后续任务复用）
            com.maafw.naruto.service.EngineConnectionShared.service = remoteService
            com.maafw.naruto.service.EngineConnectionShared.bound = true
            com.maafw.naruto.service.EngineConnectionShared.owner = "schedule"
            // P0-1 心跳看门狗：喂 App pid，App 死则引擎 5s 内自杀（防孤儿引擎占虚拟屏/唤醒锁）
            runCatching { remoteService?.heartbeat(android.os.Process.myPid()) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            remoteBound = false
            Log.i(TAG, "远端引擎已断开")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!areNotificationsEnabled()) {
            // P1-X3：通知权限未授予时不再直接 stopSelf（否则定时任务静默失效）；
            // Android 13+ 无通知权限 FGS 仍可启动（只是通知不可见），任务照常执行
            Log.w(TAG, "通知权限未授予：任务照常执行，但前台通知不可见（可在系统设置开启通知）")
        }
        startForeground(NOTIFICATION_ID, buildNotification("MAAFW 定时任务待命中"))
        // 后台唤醒开启时持有唤醒锁（锁屏/熄屏也执行任务）
        if (com.maafw.naruto.data.settings.SettingsRepository.isScheduleWakeOn(applicationContext)) {
            acquireWakeLock()
        }
        if (intent?.getStringExtra("action") == "run_profile") {
            val profileName = intent.getStringExtra("profile_name") ?: "default"
            val forceStart = intent.getBooleanExtra("force_start", false)
            val autoSleep = intent.getBooleanExtra("auto_sleep", false)
            val closeGame = intent.getBooleanExtra("close_game", false)
            // Root 唤醒开启或全局为 Root 模式 -> 引擎走 root 进程
            useRootEngineMode = intent.getBooleanExtra("use_root", false) ||
                    com.maafw.naruto.data.settings.SettingsRepository.isRootMode(applicationContext)
            // 后台保障：任务期间自动临时保活（用户未开启「后台保活」时也保证进程不被杀）
            if (!com.maafw.naruto.data.settings.SettingsRepository.isKeepAliveEnabled(applicationContext)
                && !KeepAliveService.isRunning(this)
            ) {
                tempKeepAlive = true
                KeepAliveService.start(this)
                Log.i(TAG, "任务期间临时保活已启动")
            }
            serviceScope.launch { runProfile(profileName, forceStart, autoSleep, closeGame) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        // 任务结束：若为任务期间临时保活且用户未手动开启，则停止
        if (tempKeepAlive && !com.maafw.naruto.data.settings.SettingsRepository.isKeepAliveEnabled(applicationContext)) {
            KeepAliveService.stop(this)
            tempKeepAlive = false
        }
        if (useRootEngineMode) {
            // Root 模式：杀掉 root 引擎进程（binder 随进程销毁），不残留
            runCatching { com.maafw.naruto.root.RootRemoteServiceConnector.killExistingRootService() }
        } else {
            val args = remoteServiceArgs
            if (remoteBound && args != null) {
                runCatching { Shizuku.unbindUserService(args, remoteConnection, true) }
            }
        }
        // P0-A 双引擎收敛：仅当共享连接是本服务绑定的（owner="schedule"）才清空；
        // 复用的 MainActivity 连接（owner="main"）不动，避免破坏手动任务的绑定
        if (com.maafw.naruto.service.EngineConnectionShared.owner == "schedule") {
            com.maafw.naruto.service.EngineConnectionShared.clear()
        }
        serviceScope.cancel()
    }

    private var remoteServiceArgs: Shizuku.UserServiceArgs? = null
    /** Shizuku UserService version 递增（配合随机 tag 根治服务端 record 卡死） */
    private val shizukuServiceVersion = java.util.concurrent.atomic.AtomicInteger(100)
    /**
     * P1：本次定时任务是否复用了共享引擎（MainActivity 绑定的引擎）。
     * 复用时虚拟屏可能属于原持有者（如暂停中的手动任务），结束时不强制 stopVirtualDisplay，避免误停。
     */
    @Volatile
    private var sharedEngineReused = false

    private suspend fun ensureRemoteConnected(): Boolean {
        if (remoteBound && remoteService != null) return true

        // 方案4：优先复用连接状态机的引擎（MainActivity 已连接，避免双进程）
        com.maafw.naruto.service.MaaFwConnectionManager.initialize(this)
        val cmSvc = com.maafw.naruto.service.MaaFwConnectionManager.currentService
        if (cmSvc != null && !useRootEngineMode) {
            remoteService = cmSvc
            remoteBound = true
            sharedEngineReused = true
            Log.i(TAG, "复用连接状态机引擎")
            return true
        }

        // P0-A 双引擎收敛：优先复用已绑定的共享引擎（MainActivity 手动任务绑定），避免起两个引擎进程
        // 复用前校验运行模式一致（定时任务要求 Root 引擎时不复用 Shizuku 引擎，反之亦然）
        val shared = com.maafw.naruto.service.EngineConnectionShared.aliveService()
        val sharedMode = com.maafw.naruto.service.EngineConnectionShared.engineMode
        val needRoot = useRootEngineMode || com.maafw.naruto.data.settings.SettingsRepository.isRootMode(applicationContext)
        val expectMode = if (needRoot) "root" else "shizuku"
        if (shared != null && sharedMode == expectMode) {
            remoteService = shared
            remoteBound = true
            sharedEngineReused = true
            // 复用不改变 owner（connection 仍归 MainActivity，本服务不 unbind）
            Log.i(TAG, "复用共享引擎连接（owner=${com.maafw.naruto.service.EngineConnectionShared.owner}, mode=$sharedMode）")
            return true
        }
        sharedEngineReused = false
        com.maafw.naruto.service.EngineConnectionShared.clear()

        // Root 模式：su + app_process 启动 root 引擎（与 Shizuku 同 AIDL 接口，无需 Shizuku）
        if (useRootEngineMode) {
            if (!com.maafw.naruto.root.RootManager.isRootGranted()) {
                Log.w(TAG, "Root 未授权，无法启动 Root 引擎执行定时任务")
                return false
            }
            com.maafw.naruto.root.RootRemoteServiceConnector.initialize(this)
            return bindRootEngine()
        }

        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku 未运行，无法执行定时任务")
            return false
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku 未授权，无法执行定时任务")
            return false
        }
val args = Shizuku.UserServiceArgs(ComponentName(this, RemoteEngineServiceImpl::class.java))
                    .daemon(false) // 原版配置：临时模式（多数设备含部分澎湃可用）
                    .processNameSuffix("remote_engine")
                    .debuggable(true) // 原版配置
                    // 随机 tag + 递增 version：服务端按 package:tag 管理 record，
                    // 避免与 MainActivity 的手动绑定共用固定 key 导致 record 卡死（P0-A 双引擎收敛的缓解措施）
                    .tag(java.util.UUID.randomUUID().toString())
                    .version(shizukuServiceVersion.incrementAndGet())
        remoteServiceArgs = args
        Shizuku.bindUserService(args, remoteConnection)
        // 等待绑定完成，最多 10 秒
        var waited = 0
        while (!remoteBound && waited < 100) {
            delay(100)
            waited++
        }
        return remoteBound
    }

    /**
     * 绑定 Root 引擎（su + app_process 启动，binder 经 ServiceManager/显式广播获取）。
     * 与 Shizuku 绑定返回同一 IRemoteEngineService 接口，调用方无感知。
     */
    private suspend fun bindRootEngine(): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            com.maafw.naruto.root.RootRemoteServiceConnector.bind(
                onConnected = { binder ->
                    remoteService = IRemoteEngineService.Stub.asInterface(binder)
                    remoteBound = true
                    Log.i(TAG, "Root 引擎已连接（定时任务）")
                    // P0-A 双引擎收敛：Root 引擎也写入共享
                    com.maafw.naruto.service.EngineConnectionShared.service = remoteService
                    com.maafw.naruto.service.EngineConnectionShared.bound = true
                    com.maafw.naruto.service.EngineConnectionShared.owner = "schedule"
                    // P0-1 心跳看门狗：喂 App pid（Root 模式 primeHeartbeat 兜底）
                    runCatching { remoteService?.heartbeat(android.os.Process.myPid()) }
                    cont.resume(true) { }
                },
                onError = { e ->
                    remoteBound = false
                    Log.w(TAG, "Root 引擎绑定失败: ${e.message}")
                    cont.resume(false) { }
                }
            )
        }
    }

    private suspend fun runProfile(profileName: String, forceStart: Boolean, autoSleep: Boolean, closeGame: Boolean) {
    try {
        notificationCoordinator.notifyTaskStarted(profileName, "定时任务开始执行")
        updateNotification("正在启动配置 [$profileName]")
        val taskStartElapsed = android.os.SystemClock.elapsedRealtime()
        if (!ensureRemoteConnected()) {
                updateNotification(if (useRootEngineMode) "定时任务失败：Root 引擎未就绪" else "定时任务失败：Shizuku 未就绪")
                stopSelfDelayed()
                return
            }
            val remote = remoteService ?: run {
                updateNotification("定时任务失败：远端引擎未连接")
                stopSelfDelayed()
                return
            }

            // P0-A 收敛互斥：引擎正在运行其他任务（如手动任务）时，跳过本次定时触发——
            // 否则 startTasksJson 失败后 stopVirtualDisplay 会把手动任务正在使用的虚拟屏停掉（黑屏）
            if (remote.isRunning && !forceStart) {
                Log.i(TAG, "定时任务跳过：引擎正在运行其他任务（[$profileName] 本次不执行）")
                updateNotification("定时任务跳过：已有任务运行中")
                stopSelfDelayed()
                return
            }

            remote.setup(getUserDir())

            // libbridge.so 部署到 userDir（shell/root 读 /data/app 可能被 SELinux 拒绝 Bad file descriptor，
            // 必须由 App 进程复制到可读目录；与手动任务 startEnabledTasks 保持一致）
            runCatching {
                val src = java.io.File(applicationInfo.nativeLibraryDir, "libbridge.so")
                val dst = java.io.File(getUserDir(), "libbridge.so")
                if (src.exists() && (!dst.exists() || dst.length() != src.length())) {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                }
            }.onFailure { Log.w(TAG, "libbridge.so 复制失败: ${it.message}") }

            // P0-4/E5：任务启动前给游戏授予"省电豁免+后台不受限"（vivo/澎湃后台杀游戏防护）
            runCatching { remote.grantPermissions("com.tencent.KiHan", 1 or 2) }
                .onFailure { Log.w(TAG, "游戏后台保护授予失败: ${it.message}") }

            // 启动任务时自动静音（ muteOnGameLaunch）
            if (SettingsRepository.isMuteOnGameLaunch(applicationContext)) {
                runCatching { remote.setAudioMuted(true) }
            }

            // forceStart：先停止已有任务（ 强制开始语义）
            if (forceStart && remote.isRunning) {
                remote.stopTask()
            }

            // 应用虚拟屏分辨率设置（与手动任务 startEnabledTasks 一致，防定时任务与识别模板分辨率不匹配）
            runCatching {
                val (w, h, dpi) = com.maafw.naruto.data.settings.SettingsRepository.getResolutionFull(
                    com.maafw.naruto.data.settings.SettingsRepository.getResolution(applicationContext),
                    applicationContext
                )
                remote.setResolution(w, h, dpi)
                Log.i(TAG, "定时任务虚拟屏分辨率：${w}x${h}@${dpi}dpi")
            }.onFailure { Log.w(TAG, "定时任务设置分辨率失败: ${it.message}") }

            val displayId = remote.startVirtualDisplay()
            if (displayId < 0) {
                updateNotification("定时任务失败：虚拟屏创建失败")
                stopSelfDelayed()
                return
            }

            val interfaceData = AssetLoader.loadInterface(applicationContext)
            val profile = if (profileName == "default") {
                ProfileManager.loadDefault(applicationContext, interfaceData)
            } else {
                ProfileManager.load(applicationContext, profileName) ?: run {
                    updateNotification("定时任务：配置 [$profileName] 不存在")
                    safeStopVirtualDisplay(remote)
                    stopSelfDelayed()
                    return
                }
            }
            val enabled = profile.tasks.filter { it.enabled }
if (enabled.isEmpty()) {
                    updateNotification("定时任务：配置 [$profileName] 无启用任务")
                    safeStopVirtualDisplay(remote)
                    stopSelfDelayed()
                    return
                }

            val tasks = interfaceData?.task ?: emptyList()
            val items = JSONArray()
            for (entry in enabled.map { it.entry }) {
                val task = tasks.find { it.entry == entry } ?: continue
                val config = SettingsRepository.getTaskConfig(applicationContext, entry)
                val override = OptionOverrideBuilder.build(task, config.options, interfaceData)
                items.put(JSONObject().apply {
                    put("entry", entry)
                    put("options", JSONObject().apply {
                        config.options.forEach { (k, v) -> put(k, v) }
                    })
                    override?.let { put("pipeline_override", it) }
                })
            }
if (items.length() == 0) {
                    updateNotification("定时任务：无有效任务")
                    safeStopVirtualDisplay(remote)
                    stopSelfDelayed()
                    return
                }

            updateNotification("正在执行配置 [$profileName]（${items.length()} 个任务）")
            // 引擎侧（shell 进程）无权读 App 私有 SharedPreferences，把引擎所需运行设置写入 userDir 共享配置
            runCatching {
                com.maafw.naruto.data.settings.EngineSharedConfig.write(
                    getUserDir(),
                    com.maafw.naruto.data.settings.EngineSharedConfig.Config(
                        engineReuse = com.maafw.naruto.data.settings.SettingsRepository.isEngineReuseEnabled(applicationContext),
                        closeGameAfterTask = com.maafw.naruto.data.settings.SettingsRepository.isCloseGameAfterTask(applicationContext),
                        verboseLogging = com.maafw.naruto.data.settings.SettingsRepository.isVerboseLogging(applicationContext),
                        forceStop = com.maafw.naruto.data.settings.SettingsRepository.isForceStopEnabled(applicationContext),
                        taskOptions = com.maafw.naruto.data.settings.EngineSharedConfig.taskOptionsFrom(items)
                    )
                )
            }.onFailure { Log.w(TAG, "引擎共享配置写入失败: ${it.message}") }
if (!remote.startTasksJson(items.toString())) {
                    updateNotification("定时任务：启动失败")
                    safeStopVirtualDisplay(remote)
                    stopSelfDelayed()
                    return
                }

            // 等待任务结束（500ms 轮询，更快感知结束，减少任务收尾延迟）
            while (remote.isRunning) {
                delay(500)
            }

            // autoSleep：任务结束后自动熄屏（）
            if (autoSleep) {
                remote.setDisplayPower(false)
            }
            // P1：复用共享引擎时不强制释放虚拟屏（归属原持有者），否则正常释放
            safeStopVirtualDisplay(remote)

            // closeGame：任务结束后关闭游戏（）
            if (closeGame || SettingsRepository.isCloseGameAfterTask(applicationContext)) {
                runCatching { remote.stopPackage("com.tencent.KiHan") }
            }

            updateNotification("定时任务执行完毕")
        val durationSec = (android.os.SystemClock.elapsedRealtime() - taskStartElapsed) / 1000
        val durationText = formatDuration(durationSec)
        notificationCoordinator.notifyTaskCompleted("任务执行完毕", durationText)
    } catch (e: Exception) {
            Log.e(TAG, "runProfile error: ${e.message}", e)
            updateNotification("定时任务异常：${e.message}")
            notificationCoordinator.notifyTaskError("定时任务", e.message ?: "未知异常")
        } finally {
            stopSelfDelayed()
        }
    }

    private fun stopSelfDelayed() {
        serviceScope.launch {
            delay(3000)
            stopSelf()
        }
    }

    /**
     * P1：释放虚拟屏——复用共享引擎时不强制释放（虚拟屏可能属于原持有者，如暂停中的手动任务），
     * 避免定时任务结束后误停他人虚拟屏（黑屏）。
     */
    private fun safeStopVirtualDisplay(remote: IRemoteEngineService) {
        if (sharedEngineReused) {
            Log.i(TAG, "复用共享引擎，跳过 stopVirtualDisplay（虚拟屏归属原持有者）")
            return
        }
        runCatching { remote.stopVirtualDisplay() }
    }

    /** 秒 -> 「x分x秒」展示 */
    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0秒"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val foregroundChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "MAAFW 运行状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "定时任务前台服务通知" }
            manager.createNotificationChannel(foregroundChannel)
        }
    }

    private fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle("MAAFW 火影忍者")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}