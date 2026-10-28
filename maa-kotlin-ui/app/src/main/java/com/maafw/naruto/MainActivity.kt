package com.maafw.naruto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.maa.adaptation.PipelineAdaptation
import com.maafw.naruto.maa.adaptation.ScreenProfile
import com.maafw.naruto.model.AssetLoader
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.OptionOverrideBuilder
import com.maafw.naruto.remote.RemoteEngineServiceImpl
import com.maafw.naruto.root.RootManager
import com.maafw.naruto.root.RootRemoteServiceConnector
import com.maafw.naruto.schedule.ScheduleHelper
import com.maafw.naruto.service.KeepAliveService
import com.maafw.naruto.schedule.data.SchedulePolicyRepository
import com.maafw.naruto.schedule.ui.ScheduleEditView
import com.maafw.naruto.schedule.ui.ScheduleListView
import com.maafw.naruto.shizuku.ShizukuManager
import com.maafw.naruto.ui.components.MaaBottomBar
import com.maafw.naruto.ui.components.MaaScreen
import com.maafw.naruto.ui.home.HomeScreen
import com.maafw.naruto.ui.onboarding.OnboardingScreen
import com.maafw.naruto.ui.script.ScriptsScreen
import com.maafw.naruto.ui.settings.SettingsScreen
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面
 * 现在引擎和虚拟屏都搬到 Shizuku UserService（shell 进程）里，
 * 应用侧只负责 UI、绑定远端服务、收发日志。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REMOTE_LOG_ACTION = "com.maafw.naruto.REMOTE_LOG"
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS 申请结果: $granted")
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 首次引导「一键申请必要权限」：通知 + 精确闹钟 + 电池优化 */
    fun requestNecessaryPermissions() {
        requestNotificationPermission()
        // 精确闹钟（Android 12+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            }
        }
        // 电池优化（后台保活）
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        "package:$packageName".toUri()
                    )
                )
            }
        }
    }

    // 使用 Compose State 才能触发界面重绘，否则远端引擎连上了 UI 还显示“未连接”
    private val remoteEngineState = mutableStateOf<IRemoteEngineService?>(null)
    private val remoteBoundState = mutableStateOf(false)
    private val remoteEngine: IRemoteEngineService? get() = remoteEngineState.value
    private val remoteBound: Boolean get() = remoteBoundState.value

    /**
     * 把应用私有外部目录传给远端 shell 进程，避免 shell 里 ShellContext 调 getExternalFilesDir 触发 UID 校验
     */
    private fun getUserDir(): String? = runCatching { getExternalFilesDir(null)?.absolutePath }.getOrNull()

    // 防止 bindUserService 调用期间重复进入（Compose 可观察，驱动 UI 禁用「开始任务」）
    private val remoteBindingState = mutableStateOf(false)
    private val remoteBindingLock = Any()
    private var remoteBinding: Boolean
        get() = remoteBindingState.value
        set(value) { remoteBindingState.value = value }

    /** 引擎绑定成功后的公共初始化（版本/分辨率/监听器） */
    private fun onEngineConnected() {
        // P0-A 双引擎收敛：把引擎连接共享给定时任务（MaaEngineService）复用，避免起两个引擎进程
        com.maafw.naruto.service.EngineConnectionShared.service = remoteEngine
        com.maafw.naruto.service.EngineConnectionShared.bound = true
        com.maafw.naruto.service.EngineConnectionShared.owner = "main"
        com.maafw.naruto.service.EngineConnectionShared.engineMode =
            if (SettingsRepository.isRootMode(applicationContext)) "root" else "shizuku"
        // 悬浮球控制：引擎连接后更新引用；开关开启且未显示则自动显示
        com.maafw.naruto.overlay.MaaFwFloatingControl.updateEngine(remoteEngine)
        if (SettingsRepository.isFloatingControlEnabled(applicationContext)) {
            runCatching { com.maafw.naruto.overlay.MaaFwFloatingControl.show(this, remoteEngine) }
        }
        // P1-2：绑定独立 logcat 服务（任务期间按 pid 抓取 App+引擎日志落盘）
        runCatching { com.maafw.naruto.service.LogcatServiceManager.bind(this) }
        // 注册状态监听器（binder 回调：日志/运行状态/当前任务）
        runCatching { remoteEngine?.registerStatusListener(engineStatusListener) }
        // P0-1 心跳看门狗：喂 App pid，App 死则引擎 5s 内自杀（防孤儿引擎占虚拟屏/唤醒锁）
        runCatching { remoteEngine?.heartbeat(android.os.Process.myPid()) }
        // 引擎异常被杀后自动恢复：重连成功 → 自动从断点续跑（不重跑已完成的）
        autoResumeProfile?.let { profile ->
            val resumeEntry = autoResumeEntry
            autoResumeProfile = null
            autoResumeEntry = null
            // 防冲突：若用户已手动开始任务（engineRunningNow 已 true），取消自动恢复
            if (engineRunningNow) {
                addLog("检测到任务已在运行，取消自动恢复")
                return@let
            }
            addLog("引擎已重连，自动恢复执行配置 [$profile]${resumeEntry?.let { "（从 $it 继续）" } ?: ""}…")
            lifecycleScope.launch {
                delay(2_000)
                // 2s 后再次确认未运行，避免与用户手动开始竞争
                if (engineRunningNow) {
                    addLog("自动恢复取消（任务已由手动开始）")
                } else {
                    startEnabledTasks(
                        com.maafw.naruto.model.AssetLoader.loadInterface(this@MainActivity),
                        profile,
                        resumeFromEntry = resumeEntry
                    )
                }
            }
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { remoteEngine?.setup(getUserDir()) }
                val version = withContext(Dispatchers.IO) { remoteEngine?.version() }
                version?.let { addLog("远端引擎: $it") }
                // 查询虚拟屏分辨率（预览 Surface 用）
                withContext(Dispatchers.IO) {
                    remoteEngine?.getDisplayResolution()?.takeIf { it.size >= 2 }?.let {
                        displayResolutionState.value = Pair(it[0], it[1])
                    }
                }
                // 引擎（重）连接后：自动重设预览 Surface（修复「预览 Surface 设置失败」）
                withContext(Dispatchers.IO) {
                    currentPreviewSurface?.let { s ->
                        runCatching { remoteEngine?.setMonitorSurface(s) }
                    }
                }
                // 部署 libbridge.so 到 /data/local/tmp（data 分区可执行）：
                // App 进程读取安装包内 so 字节，由引擎写入 tmp，绕开 FUSE noexec 与 /data/app 权限问题
                withContext(Dispatchers.IO) {
                    runCatching {
                        val src = java.io.File(applicationContext.applicationInfo.nativeLibraryDir, "libbridge.so")
                        if (src.exists() && src.length() > 0) {
                            val ok = remoteEngine?.deployBridge(src.readBytes()) == true
                            if (ok) addLog("libbridge.so 已部署到引擎可执行目录") else addLog("libbridge.so 部署失败")
                        }
                    }.onFailure { addLog("libbridge.so 读取失败: ${it.message}") }
                }
                // P0：引擎（重）连接后，若存在"未连接时点击的开始任务"请求，自动执行（避免任务请求丢失）
                pendingStartRequest?.let { req ->
                    pendingStartRequest = null
                    startEnabledTasks(req.interfaceData, req.profileName, req.resumeFromEntry, req.isResume, req.profileTasks)
                }
                // 暂停恢复：引擎初始化完成后，自动从暂停的任务继续
                pendingResumeEntry?.let { entry ->
                    pendingResumeEntry = null
                    val iface = com.maafw.naruto.model.AssetLoader.loadInterface(this@MainActivity)
                    startEnabledTasks(iface, currentRunningProfile, entry, isResume = false)
                }
            }.onFailure { addLog("远端初始化失败: ${it.message}\n${it.stackTraceToString()}") }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder 已连接，尝试绑定远端引擎")
        bindRemoteEngine()
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 只接受 shell/root 进程（引擎）发送的广播，避免被伪造
            val sendingUid = android.os.Binder.getCallingUid()
            // 所有广播都打 logcat，便于排查 root 引擎 binder 广播是否到达（Android 16 上 ServiceManager 不可见）
            Log.d(TAG, "onReceive action=${intent?.action} sendingUid=$sendingUid")
            if (sendingUid != android.os.Process.SHELL_UID && sendingUid != 0) return
            when (intent?.action) {
                REMOTE_LOG_ACTION -> {
                    val log = intent.getStringExtra("log")
                    val running = intent.getBooleanExtra("running", false)
                    if (log != null) addLog(log)
                    updateRunningState(running)
                    // 事件驱动：引擎广播当前任务入口名
                    if (intent.hasExtra("current")) {
                        val cur = intent.getStringExtra("current").orEmpty()
                        if (cur.isNotBlank()) {
                            currentTaskState.value = cur
                        }
                    }
                }
                "com.maafw.naruto.TOUCH_EVENT" -> {
                val action = intent.getIntExtra("action", -1)
                val x = intent.getIntExtra("x", 0)
                val y = intent.getIntExtra("y", 0)
                if (action >= 0) {
                    // 广播来的脚本触摸 -> 手势分析（点击/长按/滑动）-> 触摸预览
                    handleScriptTouch(action, x, y)
                }
            }
                RemoteEngineServiceImpl.ROOT_ENGINE_BINDER_ACTION -> {
                    // Root 模式：引擎进程直接把 binder 广播过来，绕开 ServiceManager.getService 的 hidden API 限制
                    // Intent.getIBinderExtra 在 SDK stub 里是 @hide，用反射读取（运行时真实类存在该方法）
                    val binder = runCatching {
                        Intent::class.java.getMethod("getIBinderExtra", String::class.java)
                            .invoke(intent, "binder") as? IBinder
                    }.getOrNull()
                    if (binder != null && binder.pingBinder()) {
                        addLog("收到 Root 引擎 binder 广播，连接中…")
                        connectRootEngine(binder)
                    } else {
                        addLog("Root 引擎 binder 广播无效，回退 ServiceManager 轮询")
                    }
                }
            }
        }
    }

    private val logBuffer = mutableStateListOf("等待运行日志…")
// focus 日志轮询（App 侧 tail 解析 maafw.log 提取 Node.Action.Starting 的 focus，安全：不碰引擎 MaaTaskerAddContextSink）
private var focusPollJob: Job? = null
private var focusLastOffset = 0L
    @Volatile private var engineRunningNow = false
    private var runningCallback: ((Boolean) -> Unit)? = null
    private val virtualDisplayIdState = mutableIntStateOf(-1)
    // 虚拟屏分辨率（用于预览 Surface setFixedSize，修复黑屏）
    private val displayResolutionState = mutableStateOf(Pair(1280, 720))
    // 当前预览 Surface，虚拟屏重建或继续任务时需要重新设置
    private var currentPreviewSurface: android.view.Surface? = null
    // 脚本触摸事件（供触摸预览显示脚本触摸位置，广播自远端引擎）
    private val touchEventState = mutableStateOf<IntArray?>(null)
    // 脚本操作标记（手势分析结果：点击/长按/滑动），类成员供 handleScriptTouch 写入
    private val scriptTouchMarkersState = mutableStateOf(listOf<IntArray>())
    var scriptTouchMarkers by scriptTouchMarkersState
    // 当前任务名（事件驱动，广播自引擎事件回调）
    private val currentTaskState = mutableStateOf("")
    private val isPausedState = mutableStateOf(false)
    private val agentConnectedState = mutableStateOf(false)
    private var currentRunningProfile: String = com.maafw.naruto.data.profile.ProfileManager.DEFAULT_PROFILE_NAME
    private var pausedFromEntry: String? = null
    /** P0-5 运行期守护：后台任务期间每 5s 检查游戏存活/漂移，游戏死则提示，漂移自动拉回 */
    private var appWatchdog: com.maafw.naruto.service.GameWatchdog? = null
    /** 任务链分段进度（驱动通知进度条） */
    private val taskProgress = com.maafw.naruto.data.task.MaaFwTaskProgress()
    /** 引擎异常被杀后自动恢复：记录任务运行中的配置，重连成功后自动重跑 */
    @Volatile private var autoResumeProfile: String? = null
    /** 断点续跑：记录被杀时正在执行的任务 entry，恢复时从断点继续（不重跑已完成的） */
    @Volatile private var autoResumeEntry: String? = null
    // 暂停恢复待启动任务（重建引擎绑定成功后自动从该任务继续）
    private var pendingResumeEntry: String? = null
    /** P0：引擎未连接时点击"开始任务"，绑定成功后自动执行（避免任务请求丢失、要再点一次） */
    private var pendingStartRequest: PendingStartRequest? = null
    data class PendingStartRequest(
        val interfaceData: com.maafw.naruto.model.MaaInterface?,
        val profileName: String,
        val resumeFromEntry: String?,
        val isResume: Boolean,
        val profileTasks: List<com.maafw.naruto.data.profile.ProfileManager.ProfileTask>?
    )
    // 是否处于关屏（停止投屏）状态，Surface 重建后保持
    private val isScreenOffState = mutableStateOf(false)

    // 引擎状态监听器（binder 回调，可靠事件驱动，不依赖广播）
    private val engineStatusListener = object : IEngineStatusListener.Stub() {
        override fun onStatusChanged(running: Boolean, currentEntry: String?) {
            // 屏保遮罩：任务运行且开关开启 → 遮罩；结束/停止 → 移出
            if (running) {
                if (SettingsRepository.isScreenSaverEnabled(this@MainActivity) && !isScreenOffState.value) {
                    com.maafw.naruto.overlay.MaaFwScreenSaver.show(this@MainActivity)
                }
            } else {
                com.maafw.naruto.overlay.MaaFwScreenSaver.hide()
            }
            // B5：任务结束/停止 → 会话收尾
            if (!running) {
                runCatching { com.maafw.naruto.data.log.MaaFwSessionLog.endSession(this@MainActivity, "COMPLETED") }
            }
            // U-4：任务结束/停止时取消"运行中"通知
            if (!running) {
                runCatching {
                    com.maafw.naruto.service.TaskNotificationCoordinator(this@MainActivity).cancelTaskRunning()
                }
                // 注意：logcat 持续抓取不随任务结束停止（绑定后一直抓，unbind 时统一停）
            }
            updateRunningState(running)
            if (!currentEntry.isNullOrBlank()) {
                currentTaskState.value = currentEntry
            }
            refreshAgentStatus()
        }

        override fun onLog(message: String?) {
    if (!message.isNullOrBlank()) addLog(message)
}

override fun onTouch(action: Int, x: Int, y: Int) {
    Log.i(TAG, "onTouch action=$action x=$x y=$y")
    handleScriptTouch(action, x, y)
}

override fun onTaskEvent(entry: String?, event: String?) {
    val e = entry ?: return
    val ev = event ?: return
    runCatching {
        taskProgress.onTaskEvent(e, ev)
        val p = taskProgress.progress.value
        // 分段进度通知（运行中且开关开启时更新）
        if (p.total > 0) {
            com.maafw.naruto.service.TaskNotificationCoordinator(this@MainActivity)
                .notifyTaskProgress("任务运行中", p.completed, p.total, p.errorCount)
        }
    }.onFailure { Log.d(TAG, "onTaskEvent 处理失败: ${it.message}") }
}
}

    // ===== 脚本手势分析：区分 点击/长按/滑动（onTouch 驱动） =====
    private var gStartX = -1; private var gStartY = -1; private var gStartTime = 0L
    private var gMaxDist = 0; private var gLastX = -1; private var gLastY = -1

    private fun handleScriptTouch(action: Int, x: Int, y: Int) {
        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                gStartX = x; gStartY = y; gStartTime = android.os.SystemClock.elapsedRealtime()
                gMaxDist = 0; gLastX = x; gLastY = y
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val d = kotlin.math.hypot((x - gStartX).toDouble(), (y - gStartY).toDouble()).toInt()
                if (d > gMaxDist) gMaxDist = d
                gLastX = x; gLastY = y
            }
            android.view.MotionEvent.ACTION_UP -> {
                val duration = android.os.SystemClock.elapsedRealtime() - gStartTime
                val marker: IntArray = when {
                    // 位移大 -> 滑动 [2, sx,sy, ex,ey]
                    gMaxDist > 24 -> intArrayOf(2, gStartX, gStartY, x, y)
                    // 按得久 -> 长按 [1, x, y]
                    duration >= 500 -> intArrayOf(1, gStartX, gStartY)
                    // 快速点击 -> 点击 [0, x, y]
                    else -> intArrayOf(0, gStartX, gStartY)
                }
                scriptTouchMarkers = (scriptTouchMarkers + marker).takeLast(30)
            }
        }
    }

private fun addLog(message: String) {
        // U-3：日志统一带时间戳（级别颜色渲染由日志面板按前缀处理）
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = if (message.startsWith("[")) "[$ts] $message" else "[$ts] $message"
        if (logBuffer.size >= 500) logBuffer.removeAt(0)
        logBuffer.add(line)
        // B5：任务会话进行中日志同步写入会话文件
        runCatching { com.maafw.naruto.data.log.MaaFwSessionLog.append(this, message) }
    }

/**
 * 启动 focus 日志轮询：App 侧 tail 解析 maafw.log，提取 Node.Action.Starting 的 focus 文案加入运行日志。
 * 安全方案：不调用 MaaTaskerAddContextSink（v5.12/5.13 引擎崩溃元凶），仅读引擎已写好的日志文件。
 */
private fun startFocusLogPolling() {
    if (focusPollJob?.isActive == true) return
    focusPollJob = lifecycleScope.launch {
        val lastSent = java.util.concurrent.ConcurrentHashMap<String, Long>()
        while (isActive) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = getExternalFilesDir(null)?.let { java.io.File(it, "maa_logs/maafw.log") }
                    if (f != null && f.exists()) {
                        val len = f.length()
                        // 引擎轮转/重建日志（文件变小）时重新定位
                        if (len < focusLastOffset) focusLastOffset = 0
                        if (len > focusLastOffset) {
                            java.io.RandomAccessFile(f, "r").use { raf ->
                                raf.seek(focusLastOffset)
                                val bytes = ByteArray((len - focusLastOffset).toInt())
                                raf.readFully(bytes)
                                focusLastOffset = len
                                val text = String(bytes, Charsets.UTF_8)
                                for (line in text.lineSequence()) {
                                    if (line.contains("Node.Action.Starting") && line.contains("\"focus\":\"")) {
                                        val focus = extractFocusFromLine(line)
                                        if (focus != null && focus.isNotBlank()) {
                                            val now = SystemClock.elapsedRealtime()
                                            val last = lastSent[focus]
                                            if (last != null && now - last < 1000) continue // 1s 去重，避免高频动作刷屏
                                            lastSent[focus] = now
                                            withContext(Dispatchers.Main) { addLog(focus) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.onFailure { /* 文件读取失败忽略，下一轮重试 */ }
            }
            delay(400)
        }
    }
}

private fun stopFocusLogPolling() {
    focusPollJob?.cancel()
    focusPollJob = null
    focusLastOffset = 0
}

/** 从 maafw.log 的 Node.Action.Starting 行提取 focus 文案（details JSON 中的 focus 字符串） */
private fun extractFocusFromLine(line: String): String? {
    val m = Regex("\"focus\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(line) ?: return null
    return m.groupValues[1]
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
}

    private fun updateRunningState(running: Boolean) {
        engineRunningNow = running
        runningCallback?.invoke(running)
    }

    /** 刷新 Agent 独立进程连接状态（agent 异步连接，延迟稍候再查） */
    private fun refreshAgentStatus() {
        Thread {
            Thread.sleep(1500)
            agentConnectedState.value = runCatching { remoteEngine?.isAgentConnected() == true }.getOrDefault(false)
        }.start()
    }

    // ---- Root 模式辅助（设置里选 Root 时，系统操作直接用 su 执行，不依赖 Shizuku） ----
    private fun runRootCommand(cmd: String) {
        Thread {
            runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(this)
        RootRemoteServiceConnector.initialize(this)
        // 方案4：连接状态机（统一绑定/自动重连/共享引擎）
        com.maafw.naruto.service.MaaFwConnectionManager.initialize(this)
        com.maafw.naruto.service.MaaFwConnectionManager.setOnConnected { onEngineConnected() }
        com.maafw.naruto.service.MaaFwConnectionManager.setOnStateChanged { onConnStateChanged(it) }
        // 方案5：连接状态统一收尾（引擎 Died/Error → 停守护/置ERROR/日志/通知）
        com.maafw.naruto.service.MaaFwStateDispatcher.setOnDied {
            runCatching { appWatchdog?.stop(); appWatchdog = null }
            runCatching { com.maafw.naruto.data.log.MaaFwSessionLog.endSession(this, "SERVICE_DIED") }
            runCatching { com.maafw.naruto.service.TaskNotificationCoordinator(this).notifyServiceDied("引擎服务异常，已自动重连") }
            // 自动恢复：引擎异常被杀时若任务在跑，重连成功后自动从断点续跑
            if (engineRunningNow) {
                autoResumeProfile = currentRunningProfile
                autoResumeEntry = currentTaskState.value.takeIf { it.isNotBlank() }
                addLog("注意：引擎异常，已记录断点（${autoResumeEntry ?: "从头"}），重连后自动恢复")
            }
            updateRunningState(false)
        }
        com.maafw.naruto.service.MaaFwStateDispatcher.start(lifecycleScope)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        scheduleExistingTasks()
        // D1：外部 Intent 联动（Tasker/MacroDroid 发 com.maafw.naruto.LAUNCH_PROFILE 触发任务）
        dispatchExternalLaunchIntent(intent)
    // focus 日志轮询（App 侧解析 maafw.log，安全显示脚本节点 focus 提示）
    startFocusLogPolling()
        // Root 守护进程：开关开启且已 root -> 拉起常驻调度（App 被杀后定时任务仍可执行）
        if (SettingsRepository.isRootDaemonEnabled(applicationContext) &&
            com.maafw.naruto.root.RootManager.isRootGranted()
        ) {
            Thread {
                runCatching { com.maafw.naruto.root.RootDaemonController.start(applicationContext) }
            }.start()
        }
        // 后台保活：开关开启则启动前台保活服务（防杀后台）
        if (SettingsRepository.isKeepAliveEnabled(applicationContext)) {
            KeepAliveService.start(this)
        }

        val interfaceData: MaaInterface? = AssetLoader.loadInterface(this)

        setContent {
            val baseDensity = LocalDensity.current
            val configuration = LocalConfiguration.current
            var currentScreen by remember { mutableStateOf(MaaScreen.HOME) }
            var running by remember { mutableStateOf(false) }
        val currentTask by currentTaskState
        val isPaused by isPausedState
            // 主题（ themeMode 生效逻辑 + 莫奈动态取色）
            var theme by remember { mutableStateOf(SettingsRepository.getTheme(applicationContext)) }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (theme) {
                SettingsRepository.THEME_DARK -> true
                SettingsRepository.THEME_LIGHT -> false
                else -> systemDark
            }
            val colorScheme = when (theme) {
                SettingsRepository.THEME_MONET -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (darkTheme) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                    } else if (darkTheme) darkColorScheme() else lightColorScheme()
                }
                SettingsRepository.THEME_DARK -> darkColorScheme()
                SettingsRepository.THEME_LIGHT -> lightColorScheme()
                else -> if (systemDark) darkColorScheme() else lightColorScheme()
            }
            // 页面缩放（ 字号缩放，用 Density 整体缩放含间距）
            var uiScale by remember { mutableStateOf(SettingsRepository.getUiScale(applicationContext)) }
            // 全屏预览（隐藏底部栏 + 系统栏）
            var isFullscreen by remember { mutableStateOf(false) }
            // 首次启动引导
            var showWelcome by remember { mutableStateOf(!SettingsRepository.isOnboarded(applicationContext)) }
            // 全局操作引导（聚光灯，Scaffold 之外渲染避免底栏遮挡/坐标错位）
            val guideController = remember { com.maafw.naruto.ui.components.GuideController() }
            // 定时任务编辑状态（ 式导航：列表 <-> 编辑）
            var editingStrategyId by remember { mutableStateOf<String?>(null) }
    var inScheduleEdit by remember { mutableStateOf(false) }
    // 定时任务编辑页：按返回先退回定时任务列表，不直接退出 App
    BackHandler(enabled = inScheduleEdit) {
        inScheduleEdit = false
        editingStrategyId = null
    }
            // 首次启动引导
            var showOnboarding by remember { mutableStateOf(!SettingsRepository.isOnboardingDone(applicationContext)) }

            val remoteEngine by remoteEngineState
            val remoteBound by remoteBoundState
            val remoteConnected = remoteEngine != null && remoteBound
            val virtualDisplayId by virtualDisplayIdState
            val displayResolution by displayResolutionState

            // 帧率显示（Debug）：轮询远端引擎获取虚拟屏游戏帧率 / 脚本识别频率
            var gameFps by remember { mutableStateOf(0.0) }
            var scriptFps by remember { mutableStateOf(0.0) }
            LaunchedEffect(remoteEngine, remoteBound) {
                while (true) {
                    if (SettingsRepository.isFpsDebugEnabled(applicationContext) && remoteEngine != null && remoteBound) {
                        gameFps = runCatching { remoteEngine?.getFps() ?: 0.0 }.getOrDefault(0.0)
                        scriptFps = runCatching { remoteEngine?.getScriptFps() ?: 0.0 }.getOrDefault(0.0)
                    }
                    delay(1000)
                }
            }

            // 脚本触摸事件
    val touchEvent by touchEventState
    LaunchedEffect(touchEvent) {
        val e = touchEvent ?: return@LaunchedEffect
        // 兼容：App 内手动注入路径（保留旧逻辑，实际由 onTouch 手势分析驱动）
        touchEventState.value = null
    }

// 引擎运行状态与当前任务：事件驱动（引擎事件回调 -> 广播 -> 更新），无需轮询
            LaunchedEffect(Unit) {
                runningCallback = { running = it }
            }

            // 预览刷新：任务开始后等游戏画面流动（4s），重新绑定预览 Surface，
            // 解决「开始任务后小预览不更新、切主页回来才显示」的问题
            LaunchedEffect(running) {
                if (running) {
                    delay(4000)
                    currentPreviewSurface?.let { surface ->
                        runCatching { remoteEngine?.setMonitorSurface(surface) }
                            .onFailure { Log.d(TAG, "预览 Surface 刷新失败: ${it.message}") }
                    }
                }
            }

            // 全屏预览时隐藏系统状态栏/导航栏（修复全屏仍有状态栏和底栏）
            LaunchedEffect(isFullscreen) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (isFullscreen) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            val keepScreenOn = remember { SettingsRepository.isKeepScreenOn(applicationContext) }

            LaunchedEffect(running, keepScreenOn) {
                if (running && keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density * uiScale,
                    fontScale = baseDensity.fontScale * uiScale,
                )
            ) {
            MaterialTheme(colorScheme = colorScheme) {
            // 首次启动引导（覆盖主界面）
            if (showOnboarding) {
                OnboardingScreen(
                    onFinish = {
                        SettingsRepository.setOnboardingDone(applicationContext, true)
                        showOnboarding = false
                    },
                    onRequestPermissions = { requestNecessaryPermissions() }
                )
            } else {
            Scaffold(
                bottomBar = {
                    if (!isFullscreen) {
                        MaaBottomBar(current = currentScreen) { currentScreen = it }
                    }
                }
            ) { padding ->
            // 底部导航切换动画：淡入 + 轻微水平滑动
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally { it / 20 }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally { -it / 20 })
                }
            ) { screen ->
                when (screen) {
                    MaaScreen.HOME -> HomeScreen(
                        running = running,
                        currentTask = currentTask,
                        remoteConnected = remoteConnected,
                        engineBinding = remoteBinding,
                        agentConnected = agentConnectedState.value,
                        displayId = virtualDisplayId,
                        displayResolution = displayResolution,
                        runMode = SettingsRepository.getRunMode(applicationContext),
                        onRequestShizuku = {
                            ShizukuManager.requestPermission(this@MainActivity) { granted ->
                                if (granted) bindRemoteEngine()
                            }
                        },
                        // U-5：打开 Shizuku App（未运行引导）
                        onOpenShizuku = {
                            runCatching {
                                val pkg = "moe.shizuku.privileged.api"
                                val launch = packageManager.getLaunchIntentForPackage(pkg)
                                if (launch != null) {
                                    startActivity(launch)
                                    addLog("已打开 Shizuku，请在其中启动服务后返回")
                                } else {
                                    addLog("Shizuku App 未安装，无法打开")
                                }
                            }.onFailure { addLog("打开 Shizuku 失败: ${it.message}") }
                        },
                        // U-5：安装引导（打开 Shizuku 官网 / 应用商店）
                        onInstallShizuku = {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://shizuku.rikka.app/zh-hans/download.html")
                                )
                                startActivity(intent)
                            }.onFailure { addLog("打开下载页失败: ${it.message}") }
                        },
                        onOpenScripts = { currentScreen = MaaScreen.SCRIPT },
                        onUpdateResource = {
                            addLog("开始强制更新资源（释放安装包最新版本）...")
                            Thread {
                                runCatching {
                                    val base = com.maafw.naruto.maa.AssetResourceDeployer.deploy(
                                        applicationContext, getUserDir(), force = true
                                    )
                                    // 提升部署版本标记：确保后续 App/引擎端所有 deploy 都会检测到版本不匹配而强制重部署最新资源
                                    runCatching {
                                        java.io.File(base, ".maafw_version")
                                            .writeText((com.maafw.naruto.BuildConfig.VERSION_CODE + 1).toString())
                                    }
                                    addLog("资源更新完成：$base（部署标记已提升，下次启动将强制重部署最新资源）")
                                }.onFailure { addLog("资源更新失败: ${it.message}") }
                            }.start()
                        },
                        onStartTask = {
                            startEnabledTasks(interfaceData, com.maafw.naruto.data.profile.ProfileManager.DEFAULT_PROFILE_NAME)
                        },
                        modifier = Modifier.padding(padding)
                    )
                    MaaScreen.SCRIPT -> ScriptsScreen(
                        running = running,
                        isPaused = isPaused,
                        isScreenOff = isScreenOffState.value,
                        currentTask = currentTask,
                        remoteConnected = remoteConnected,
                        engineBinding = remoteBinding,
                        interfaceData = interfaceData,
                        logs = logBuffer.toList(),
                        displayResolution = displayResolution,
                        isFullscreen = isFullscreen,
                        onFullscreenChange = { isFullscreen = it },
                        scriptTouchMarkers = scriptTouchMarkers,
                        gameFps = gameFps,
                        scriptFps = scriptFps,
                        onPreviewSurfaceAvailable = { surface ->
                            currentPreviewSurface = surface
                            // 引擎未连接时静默（连接后 onEngineConnected 会自动重设）；连接失败仅 Log.d 不刷屏
                            if (remoteEngine != null) {
                                runCatching { remoteEngine?.setMonitorSurface(surface) }
                                    .onFailure { Log.d(TAG, "预览 Surface 设置失败: ${it.message}") }
                            }
                            // 若当前处于关屏状态，Surface 重建后仍保持不投屏
                            if (isScreenOffState.value) {
                                runCatching { remoteEngine?.setPreviewEnabled(false) }
                            }
                        },
                        onStartProfile = { profileName, tasks ->
                            startEnabledTasks(interfaceData, profileName, profileTasks = tasks)
                        },
                        onPauseResume = {
                            if (isPaused) resumeRemoteEngine(interfaceData) else pauseRemoteEngine()
                        },
                        onStop = {
                            // 停止任务 = 完整释放后台资源（与「释放后台资源」一致）：
                            // stopTask + closeGame（引擎内一次 + 引擎空闲后兜底一次）+ stopVirtualDisplay
                            stopRemoteEngine()
                            closeGame()
                            addLog("已停止任务，并释放后台资源（游戏/虚拟屏）")
                        },
                        onClearLogs = {
                            logBuffer.clear()
                            // 脚本页「清空」同样删除磁盘引擎日志（含备份）
                            com.maafw.naruto.data.log.LogExporter.clearLogFiles(this@MainActivity)
                        },
                        onScreenOff = {
                            // useHardwareScreenOff 开启 -> 硬件熄屏；否则关虚拟屏（）
                            if (SettingsRepository.isUseHardwareScreenOff(applicationContext)) {
                                if (SettingsRepository.isRootMode(applicationContext)) {
                                    runRootCommand("input keyevent 26")
                                } else {
                                    runCatching { remoteEngine?.hardwareScreenOff() }
                                }
                                addLog("已执行硬件熄屏")
                            } else {
                                setDisplayPower(false)
                            }
                        },
                        onScreenOn = { setDisplayPower(true) },
                        onScreenshot = { captureScreenshot() },
                        onCloseGame = { closeGame() },
                        onToggleGameSound = { muted ->
                            if (SettingsRepository.isRootMode(applicationContext)) {
                                runRootCommand("media volume --stream 3 --set ${if (muted) 0 else 100}")
                                addLog(if (muted) "游戏已静音(root)" else "游戏已恢复声音(root)")
                            } else {
                                runCatching { remoteEngine?.setAudioMuted(muted) }
                                    .onSuccess { addLog(if (muted) "游戏已静音" else "游戏已恢复声音") }
                                    .onFailure { addLog("静音切换失败: ${it.message}") }
                            }
                        },
                        onInjectTouch = { action, x, y ->
                            // 引擎已连接（Shizuku 或 Root 引擎进程）时统一走引擎注入：
                            // 注入到虚拟屏 displayId，坐标正确，且支持 DOWN/MOVE/UP 完整手势（滑动/长按/拖动）
                            // 触摸日志只进引擎日志（导出时可见），不显示在 App 日志页
                            if (remoteEngine != null) {
                                runCatching { remoteEngine?.injectTouch(action, x, y) }
                                    .onFailure { addLog("触摸注入失败: ${it.message}") }
                            } else if (SettingsRepository.isRootMode(applicationContext)) {
                                // Root 引擎尚未连接时的兜底：su input tap（仅点击，主屏坐标，无法滑动）
                                if (action == android.view.MotionEvent.ACTION_DOWN) {
                                    runRootCommand("input tap $x $y")
                                }
                            }
                        },
                        onInjectMultiTouch = { action, points, actionIndex ->
                            // 多点触控注入（双指缩放等）：points 展平 [x1,y1,x2,y2,...]
                            if (remoteEngine != null) {
                                runCatching { remoteEngine?.injectMultiTouch(action, points, actionIndex) }
                                    .onFailure { addLog("多点触摸注入失败: ${it.message}") }
                            }
                        },
                        onInjectKey = { keyCode ->
                            if (SettingsRepository.isRootMode(applicationContext)) {
                                runRootCommand("input keyevent $keyCode")
                            } else {
                                runCatching { remoteEngine?.injectKey(keyCode) }
                                    .onFailure { addLog("按键注入失败: ${it.message}") }
                            }
                        },
                        onReleaseBackground = {
                            stopRemoteEngine()
                            closeGame()
                            addLog("已强制释放后台资源（虚拟屏、游戏、远端引擎）")
                        },
                        guideController = guideController,
                        modifier = Modifier.padding(padding)
                    )
                    MaaScreen.SCHEDULE -> Box(modifier = Modifier.padding(padding)) {
                        if (inScheduleEdit) {
                            ScheduleEditView(
                                strategyId = editingStrategyId,
                                onBack = {
                                    inScheduleEdit = false
                                    editingStrategyId = null
                                }
                            )
                        } else {
                            ScheduleListView(
                                onEditStrategy = { id ->
                                    editingStrategyId = id
                                    inScheduleEdit = true
                                },
                                guideController = guideController
                            )
                        }
                    }
                    MaaScreen.SETTINGS -> Box(modifier = Modifier.padding(padding)) {
                        SettingsScreen(
                            logBuffer = logBuffer,
                            theme = theme,
                            onThemeChange = { theme = it },
                            uiScale = uiScale,
                            onUiScaleChange = { uiScale = it },
                            onResolutionChange = { res -> SettingsRepository.setResolution(applicationContext, res) },
                            onRunModeChange = { mode ->
                val prev = SettingsRepository.getRunMode(applicationContext)
                SettingsRepository.setRunMode(applicationContext, mode)
                if (prev != mode) {
                    // U-6：切换运行模式后提示 + 自动重绑引擎（任务运行中则下次生效）
                    if (engineRunningNow) {
                        addLog("注意：任务运行中，运行模式已保存，将在任务结束后重启引擎连接时生效")
                    } else {
                        addLog("已切换运行模式：${if (mode == SettingsRepository.RUN_MODE_ROOT) "Root" else "Shizuku"}，正在重启引擎连接…")
                        stopRemoteEngine()
                        bindRemoteEngine()
                    }
                }
            },
                            onCaptureLogcat = {
                runCatching { remoteEngine?.captureLogcat(20000) }.getOrNull()
            }
                        )
                    }
                }
            }
            }

            // 全局操作引导（覆盖全屏含底部导航栏，坐标对齐不偏移）
            if (guideController.isActive) {
                com.maafw.naruto.ui.components.SpotlightGuide(
                    steps = guideController.steps,
                    stepIndex = guideController.stepIndex,
                    targets = guideController.targets,
                    onNext = { guideController.next() },
                    onPrev = { guideController.prev() },
                    onSkip = { guideController.dismiss() }
                )
            }

            // 首次启动引导（权限说明 + 使用指引）
            if (showWelcome) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("欢迎使用 MAAFW 火影忍者") },
                    text = {
                        Column {
                            Text("本应用通过 Shizuku/Root 创建虚拟屏，自动运行火影忍者日常任务。", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("使用前请完成：", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("1. 安装并启动 Shizuku（或已 Root）", style = MaterialTheme.typography.bodySmall)
                            Text("2. 在设置页授权并选择运行模式", style = MaterialTheme.typography.bodySmall)
                            Text("3. 在脚本页配置任务后点击「开始任务」", style = MaterialTheme.typography.bodySmall)
                            Text("4. 在定时任务页可设置自动执行与后台唤醒", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Text("详情可随时在主页「权限检查」查看权限状态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            SettingsRepository.setOnboarded(applicationContext)
                            showWelcome = false
                        }) { Text("开始使用") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            SettingsRepository.setOnboarded(applicationContext)
                            showWelcome = false
                        }) { Text("跳过") }
                    }
                )
            }
            }
        }
    }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(REMOTE_LOG_ACTION)
        filter.addAction("com.maafw.naruto.TOUCH_EVENT")
        filter.addAction(RemoteEngineServiceImpl.ROOT_ENGINE_BINDER_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 引擎在 shell/root 进程（不同 UID），必须 RECEIVER_EXPORTED 才能收到
                Context.RECEIVER_EXPORTED
            } else {
                0x00000002
            }
            registerReceiver(logReceiver, filter, null, null, flags)
        } else {
            registerReceiver(logReceiver, filter)
        }
        // 修复：无条件触发引擎绑定（bind() 内部已按 Root/Shizuku 模式分流，且 Connecting/Connected 幂等）。
        // 原 `if (Shizuku.pingBinder())` 在 Root 模式 / Shizuku 未运行时永不触发 → 打开 App 即"引擎未连接"且无新引擎日志
        bindRemoteEngine()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(logReceiver) } catch (_: IllegalArgumentException) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFocusLogPolling()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        // 悬浮球控制：退出时清理悬浮窗
        com.maafw.naruto.overlay.MaaFwFloatingControl.dismiss()
        com.maafw.naruto.overlay.MaaFwScreenSaver.hide()
        // P1-2：解绑独立 logcat 服务
        runCatching { com.maafw.naruto.service.LogcatServiceManager.unbind() }
        // 方案4：退出统一解绑（标记手动，不触发自动重连）+ 清空共享引擎
        com.maafw.naruto.service.MaaFwConnectionManager.unbind()
        if (com.maafw.naruto.service.EngineConnectionShared.owner == "main") {
            com.maafw.naruto.service.EngineConnectionShared.clear()
        }
    }

    private fun bindRemoteEngine() {
        // 方案4：统一走连接状态机（Shizuku 三方案 + Root + 自动重连 + 引擎驻留复用）
        com.maafw.naruto.service.MaaFwConnectionManager.bind()
    }

    /** 方案4：连接状态机 → Compose 状态映射（驱动 UI 按钮可用性/状态卡） */
    private fun onConnStateChanged(state: com.maafw.naruto.service.MaaFwConnectionManager.State) {
        when (state) {
            is com.maafw.naruto.service.MaaFwConnectionManager.State.Connected -> {
                remoteEngineState.value = state.svc
                remoteBoundState.value = true
                remoteBindingState.value = false
            }
            is com.maafw.naruto.service.MaaFwConnectionManager.State.Connecting -> {
                remoteBindingState.value = true
            }
            else -> {
                remoteEngineState.value = null
                remoteBoundState.value = false
                remoteBindingState.value = false
            }
        }
    }

    /**
     * Root 模式：引擎进程广播过来的 binder 直接连接（绕开 ServiceManager.getService 的 hidden API 限制）
     */
    private fun connectRootEngine(binder: IBinder) {
        synchronized(remoteBindingLock) { remoteBinding = false }
        if (remoteBound) return
        remoteEngineState.value = IRemoteEngineService.Stub.asInterface(binder)
        remoteBoundState.value = true
        addLog("Root 远端引擎已连接")
        onEngineConnected()
    }

    private fun scheduleExistingTasks() {
        val items = com.maafw.naruto.data.schedule.ScheduleRepository.load(applicationContext)
        if (items.isNotEmpty()) {
            ScheduleHelper.rescheduleAll(applicationContext, items)
        }
        // 策略（：启动时重注册所有启用的定时策略）
        val strategies = SchedulePolicyRepository(applicationContext).load()
        ScheduleHelper.rescheduleStrategies(applicationContext, strategies)
    }

    /**
     * D1：外部 Intent 联动（Tasker/MacroDroid 等发 com.maafw.naruto.LAUNCH_PROFILE 触发任务）。
     * 复用 MaaEngineService（前台服务）执行链路，与定时任务同逻辑（含引擎复用/后台保护）。
     */
    private fun dispatchExternalLaunchIntent(intent: Intent?) {
        val request = com.maafw.naruto.schedule.ExternalLaunchMapper.fromExternalIntent(intent) ?: return
        addLog("收到外部启动请求：配置 [${request.profileName}]（forceStart=${request.forceStart}）")
        val svc = Intent(this, com.maafw.naruto.service.MaaEngineService::class.java).apply {
            putExtra("action", "run_profile")
            putExtra("profile_name", request.profileName)
            putExtra("force_start", request.forceStart)
            putExtra("auto_sleep", request.autoSleep)
            putExtra("close_game", request.closeGame)
            putExtra("use_root", SettingsRepository.isRootMode(applicationContext))
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            addLog("已启动前台服务执行外部任务")
        } catch (e: Exception) {
            addLog("外部任务启动失败: ${e.message}")
        }
    }

    /**
     * 开始任务前释放后台内存（仅用户手动点「开始任务」时触发）：
     * - 只杀后台/缓存进程（importance >= BACKGROUND），不碰前台/可见/服务进程；
     * - **系统进程保护（双保险）**：① uid < 10000（系统/共享 uid，含 system_server、系统 UI、厂商系统应用）一律跳过；
     *   ② 包名白名单：自身所有进程（App/引擎/agent）、游戏、Shizuku、厂商关键包；
     * - 后台保活、自启动、定时任务走 MaaEngineService，不经过此方法，不会误清前台。
     */
    private fun releaseMemoryBeforeTask() {
        if (!SettingsRepository.isMemoryCleanBeforeTask(applicationContext)) return
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val keepPrefix = listOf(
                "com.maafw.naruto",              // 自身所有进程（App/引擎/agent）
                "com.tencent.KiHan",             // 游戏（马上启动，不能杀）
                "moe.shizuku.privileged.api",    // Shizuku
                "com.miui", "com.huawei", "com.vivo", "com.oppo", "com.coloros",
                "com.xiaomi", "com.oneplus", "com.samsung", "com.google.android.gms"
            )
            var killed = 0
            am.runningAppProcesses?.forEach { p ->
                // 系统进程保护：uid < 10000（系统/共享 uid 进程）一律不杀
                if (p.uid < 10000) return@forEach
                if (p.importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                    val pkg = p.pkgList.firstOrNull { k -> keepPrefix.none { k.startsWith(it) } }
                    if (pkg != null) {
                        am.killBackgroundProcesses(pkg)
                        killed++
                    }
                }
            }
            if (killed > 0) addLog("已释放 $killed 个后台进程内存，为任务腾出空间")
        }.onFailure { /* 无权限或失败忽略，不影响任务启动 */ }
    }

    private fun startEnabledTasks(
        interfaceData: MaaInterface?,
        profileName: String = ProfileManager.DEFAULT_PROFILE_NAME,
        resumeFromEntry: String? = null,
        isResume: Boolean = false,
        // 传入脚本页当前内存的任务列表（所见即所得，避免磁盘不同步导致"不勾选也运行"）
        profileTasks: List<ProfileManager.ProfileTask>? = null
    ) {
        // 开始任务前释放后台内存（仅手动点开始；白名单保护自身引擎/游戏/Shizuku，不误杀）
        releaseMemoryBeforeTask()
        // 点击开始任务前：检查资源是否最新（assets vs 部署目录全文件对比），非最新则覆盖更新
        runCatching {
            val deployStart = System.currentTimeMillis()
            val base = com.maafw.naruto.maa.AssetResourceDeployer.deploy(applicationContext, getUserDir())
            val cost = System.currentTimeMillis() - deployStart
            if (cost > 500) addLog("资源已更新到最新（${cost}ms）：$base")
        }.onFailure { addLog("资源检查/更新失败: ${it.message}") }
        // 复制 libbridge.so 到 App 外部目录（userDir，shell/root 可读）：
        // 新安装后 /data/app 的 so 引擎进程（shell/root）可能被 SELinux 拒绝读取（Bad file descriptor），
        // App 进程能读自己的 so 且能写 userDir，复制后引擎从 userDir 加载绕开权限问题。
        runCatching {
            val src = java.io.File(applicationContext.applicationInfo.nativeLibraryDir, "libbridge.so")
            val dst = java.io.File(getUserDir(), "libbridge.so")
            if (src.exists() && (!dst.exists() || dst.length() != src.length())) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                addLog("libbridge.so 已部署到 $dst")
            }
        }.onFailure { addLog("libbridge.so 复制失败: ${it.message}") }
        currentRunningProfile = profileName
        isPausedState.value = false
        pausedFromEntry = null
        val remote = remoteEngine
        // binder 可能因引擎进程异常退出而失效（root 模式停止任务后尤甚），先探测活性，失效则复位并重新绑定
        val binderAlive = remote != null && runCatching { remote!!.asBinder()?.pingBinder() == true }.getOrDefault(false)
        if (remote == null || !binderAlive) {
            if (remote != null) {
                synchronized(remoteBindingLock) { remoteBinding = false }
                runCatching { remoteEngineState.value?.unregisterStatusListener(engineStatusListener) }
                remoteEngineState.value = null
                remoteBoundState.value = false
            }
            addLog("远端引擎未连接，正在重新绑定…")
            // P0：记录待启动任务，绑定成功后自动执行（避免任务请求丢失）
            pendingStartRequest = PendingStartRequest(interfaceData, profileName, resumeFromEntry, isResume, profileTasks)
            bindRemoteEngine()
            return
        }
        // 按当前选中的任务配置运行（不总是默认配置）；优先用传入的当前内存任务列表（所见即所得）
        val profile = if (profileTasks != null) {
            ProfileManager.Profile(profileName, profileTasks.toMutableList())
        } else if (profileName == ProfileManager.DEFAULT_PROFILE_NAME) {
            ProfileManager.loadDefault(applicationContext, interfaceData)
        } else {
            ProfileManager.load(applicationContext, profileName)
                ?: run {
                    addLog("配置 [$profileName] 不存在，回退到默认配置")
                    ProfileManager.loadDefault(applicationContext, interfaceData)
                }
        }
        addLog("正在运行配置：$profileName ")
        val enabled = profile.tasks.filter { it.enabled }
        if (enabled.isEmpty()) {
            addLog("默认配置里没有启用任何任务")
            return
        }
        val tasks = interfaceData?.task ?: emptyList()
        // 适配层：资源包的 roi/模板都按 720p 基线采集，虚拟屏可能被用户切到 1080p。
        // 这里读出「本次将要应用」的分辨率（与下方 setResolution 用的是同一份设置），
        // 任务启动前把每个 pipeline 节点的 roi/template 改写进去。
        val (screenW, screenH, screenDpi) = SettingsRepository.getResolutionFull(
            SettingsRepository.getResolution(applicationContext), applicationContext
        )
        val screenProfile = ScreenProfile(screenW, screenH, screenDpi)
        val items = JSONArray()
        var found = resumeFromEntry.isNullOrBlank()
        for (entry in enabled.map { it.entry }) {
            if (!found) {
                if (entry == resumeFromEntry) found = true else continue
            }
            val task = tasks.find { it.entry == entry } ?: continue
                val config = SettingsRepository.getTaskConfig(applicationContext, entry, profileName)
                val override = OptionOverrideBuilder.build(task, config.options, interfaceData)
                items.put(JSONObject().apply {
                    put("entry", entry)
                    put("options", JSONObject().apply {
                        config.options.forEach { (k, v) -> put(k, v) }
                    })
                    // 积分赛战力对比：point_race_challenge 用 agent 注册的原版 FindToChallenge（Custom识别+Click，独立进程执行）
                    var finalOverride = override
                    if (entry == "point_race") {
                        val legacyNode = JSONObject()
                            .put("point_race_challenge", JSONObject()
                                .put("recognition", JSONObject()
                                    .put("type", "Custom")
                                    .put("param", JSONObject()
                                        .put("custom_recognition", "FindToChallenge")
                                        .put("custom_recognition_param", JSONObject().put("fource_battle", false))))
                                .put("action", "Click"))
                        val base = runCatching { JSONObject(finalOverride ?: "{}") }.getOrDefault(JSONObject())
                        val merged = JSONObject()
                        val keys = base.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            merged.put(k, base.get(k))
                        }
                        merged.put("point_race_challenge", legacyNode.getJSONObject("point_race_challenge"))
                    finalOverride = merged.toString()
                }
                // 情报社：村口点击（默认走原版 OCR 识别；仅当「村口自定义点击」开关=Yes 时用自定义坐标点击）
                if (entry == "naruto_club") {
                    val clubEnabled = config.options["村口自定义点击"] == "Yes"
                    if (clubEnabled) {
                        val ptRaw = config.options["村口点击位置"]?.ifBlank { null }
                            ?: SettingsRepository.getClubVillagePoint(applicationContext).ifBlank { null } // 兼容旧配置
                            ?: "1181,464"
                        val pt = SettingsRepository.parseClickPoint(ptRaw) ?: intArrayOf(1181, 464)
                        val node = JSONObject()
                            .put("village_entry", JSONObject()
                                .put("recognition", "DirectHit")
                                .put("action", "Click")
                                // 注意：不用 Custom action：MaaFramework v5.12.3 的 custom_action_param 经 JNA 回调会丢失
                                //（ClickStoredPoint 收到空 actionParam -> 失败）。改用原生 Click + target_offset：
                                // DirectHit 全屏 box [0,0,1280,720]，点击子矩形中心 = 村口固定坐标 (pt)
                                .put("target_offset", JSONArray()
                                    .put(pt[0]).put(pt[1]).put(pt[0]).put(pt[1])))
                        val base = runCatching { JSONObject(finalOverride ?: "{}") }.getOrDefault(JSONObject())
                        val merged2 = JSONObject()
                        val keys2 = base.keys()
                        while (keys2.hasNext()) {
                            val k = keys2.next()
                            merged2.put(k, base.get(k))
                        }
                        merged2.put("village_entry", node.getJSONObject("village_entry"))
                        finalOverride = merged2.toString()
                    }
                }
                // 手机端适配：分辨率缩放 roi + 选择当前分辨率的模板集。
                // 结果与选项 override 浅合并，只在节点级覆盖 roi/template。
                val adapted = PipelineAdaptation.apply(applicationContext, screenProfile, finalOverride)
                if (adapted.override.length() > 0) put("pipeline_override", adapted.override.toString())
                if (adapted.details.isNotEmpty()) {
                    addLog(adapted.summary)
                    adapted.details.forEach { addLog("  适配 $it") }
                }
                })
        }
        if (items.length() == 0) {
            addLog("没有可继续的任务")
            return
        }
        addLog("开始按顺序运行 ${items.length()} 个任务")

        // 引擎侧（shell 进程）无权读 App 私有 SharedPreferences，把引擎所需运行设置写入 userDir 共享配置
        runCatching {
            com.maafw.naruto.data.settings.EngineSharedConfig.write(
                getUserDir(),
                com.maafw.naruto.data.settings.EngineSharedConfig.Config(
                    engineReuse = SettingsRepository.isEngineReuseEnabled(applicationContext),
                    closeGameAfterTask = SettingsRepository.isCloseGameAfterTask(applicationContext),
                    verboseLogging = SettingsRepository.isVerboseLogging(applicationContext),
                    forceStop = SettingsRepository.isForceStopEnabled(applicationContext),
                    taskOptions = com.maafw.naruto.data.settings.EngineSharedConfig.taskOptionsFrom(items)
                )
            )
        }.onFailure { addLog("引擎共享配置写入失败: ${it.message}") }

        // P0-4/E5：任务启动前给游戏授予"省电豁免+后台不受限"（vivo/澎湃后台杀游戏防护）
        runCatching {
            val granted = remote.grantPermissions("com.tencent.KiHan", 1 or 2)
            if (granted != 0) addLog("已为游戏授予后台保护（省电豁免+后台不受限）")
            else addLog("游戏后台保护授予未生效（部分 ROM 需在系统设置手动允许后台运行）")
        }.onFailure { addLog("游戏后台保护授予失败: ${it.message}") }

        runCatching {
            if (isResume) {
                // 继续任务：复用已有虚拟屏，只重新运行任务
                addLog("继续任务，复用虚拟屏")
                currentPreviewSurface?.let { surface ->
                    runCatching { remote.setMonitorSurface(surface) }
                    if (isScreenOffState.value) runCatching { remote.setPreviewEnabled(false) }
                }
            } else {
                // 应用虚拟屏分辨率设置（720p/1080p）
                runCatching {
                    val (w, h, dpi) = SettingsRepository.getResolutionFull(
                        SettingsRepository.getResolution(applicationContext), applicationContext
                    )
                    remote.setResolution(w, h, dpi)
                    displayResolutionState.value = Pair(w, h)
                    addLog("虚拟屏分辨率：${w}x${h}@${dpi}dpi")
                }
                // 启动任务时自动静音（ muteOnGameLaunch）
                if (SettingsRepository.isMuteOnGameLaunch(applicationContext)) {
                    remote.setAudioMuted(true)
                    addLog("已按设置静音游戏")
                }
                val displayId = remote.startVirtualDisplay()
                virtualDisplayIdState.value = displayId
                if (displayId < 0) {
                    addLog("虚拟屏启动失败")
                    // 适配提示（提供模式化适配提示）
                    if (SettingsRepository.isRootMode(applicationContext)) {
                        addLog("当前为 Root 模式：请确认已授予 root 权限，或改用 Shizuku 模式")
                    } else if (com.maafw.naruto.shizuku.ShizukuManager.isRunningAsRoot()) {
                        addLog("Shizuku 以 root 身份运行，虚拟屏创建可能受限；可在设置页切换 Root 模式重试")
                    } else if (com.maafw.naruto.root.RootManager.isRootAvailable()) {
                        addLog("若 Shizuku 无法创建虚拟屏，可在设置页切换 Root 模式重试")
                    } else {
                        addLog("请确认 Shizuku 为 adb/shell 模式并已授权")
                    }
                    return
                }
                addLog("虚拟屏已创建 displayId=$displayId")
        currentPreviewSurface?.let { surface ->
            runCatching { remote.setMonitorSurface(surface) }
            if (isScreenOffState.value) runCatching { remote.setPreviewEnabled(false) }
        }
            if (!remote.startTasksJson(items.toString())) {
                // P1-3 StartResult 分级：按引擎日志关键词给出针对性失败提示（不再笼统报错）
                val recent = logBuffer.joinToString("\n")
                val hint = when {
                    "资源加载失败" in recent ->
                        "任务启动失败：资源加载异常——请在设置页重新部署资源（或检查 assets 完整性）"
                    "控制器创建失败" in recent || "控制器连接失败" in recent ->
                        "任务启动失败：控制器创建/连接异常——请确认 Shizuku 为 adb/shell 模式、libbridge.so 可用"
                    "控制器连接超时" in recent ->
                        "任务启动失败：控制器连接超时（15s）——请确认 Shizuku 权限与虚拟屏状态后重试"
                    "任务器创建失败" in recent ->
                        "任务启动失败：任务器初始化异常——建议重启引擎（设置->引擎）再试"
                    "虚拟屏创建失败" in recent || "虚拟屏启动异常" in recent ->
                        "任务启动失败：虚拟屏异常——请确认 Shizuku 权限；Shizuku root 模式可切 Root/Shell 模式重试"
                    else -> "任务启动失败：查看上方引擎日志，或导出日志 ZIP 分析"
                }
                addLog("注意：$hint")
            } else {
                // 手动启动任务也发送"任务开始"通知（受设置开关控制）
                runCatching {
                    com.maafw.naruto.service.TaskNotificationCoordinator(this)
                        .notifyTaskStarted(profileName, "开始运行配置")
                }
                // U-4：任务进行中通知（进度条，结束自动取消）
                runCatching {
                    com.maafw.naruto.service.TaskNotificationCoordinator(this)
                        .notifyTaskRunning(profileName, "正在执行 ${items.length()} 个任务")
                }
                // B5：任务会话日志（开始）
                runCatching { com.maafw.naruto.data.log.MaaFwSessionLog.startSession(this, profileName, items.length()) }
                // 任务链分段进度：注册全部任务（PENDING），引擎事件驱动更新
                runCatching {
                    taskProgress.reset((0 until items.length()).map { items.optJSONObject(it)?.optString("entry", "") ?: "" })
                }
                // P1-2：开始抓取 logcat（App + 引擎进程，落盘 userDir/debug/logcat/）
                runCatching {
                    com.maafw.naruto.service.LogcatServiceManager.startCapture(android.os.Process.myPid(), getUserDir())
                }
                // 预览黑屏修复：任务启动成功后立即重投预览 Surface（不等 4s 延迟，
                // 引擎侧任务初始化完成后也会重投，双保险）
                currentPreviewSurface?.let { s ->
                    runCatching { remote.setMonitorSurface(s) }
                        .onFailure { Log.d(TAG, "任务启动后预览重投失败: ${it.message}") }
                }
                // P0-5 GameWatchdog：后台任务运行期守护（游戏进程存活 + 显示漂移拉回）
                startGameWatchdog(remote)
            }
            } // else 分支闭合
        }.onFailure {
            addLog("启动远端引擎失败: ${it.message}")
            it.printStackTrace()
        }
    }

    /**
     * P0-5：启动后台任务运行期守护（游戏进程存活 + 显示漂移拉回）。
     * 每 5s 检查一次；游戏死 -> 提示；游戏漂移出虚拟屏 -> 5s 宽限期后拉回（上限 3 次）。
     */
    private fun startGameWatchdog(remote: IRemoteEngineService) {
        appWatchdog?.stop()
        appWatchdog = com.maafw.naruto.service.GameWatchdog(
            remote = remote,
            packageName = "com.tencent.KiHan",
            onGameDied = {
                addLog("注意：游戏进程已死亡，任务可能失效（可停止后重新开始）")
            },
            onDrift = { msg -> addLog("注意：$msg") }
        ).also { it.start(lifecycleScope) }
    }

    private fun stopRemoteEngine() {
        // P0-5 停止运行期守护（游戏存活/漂移检测）
        appWatchdog?.stop()
        appWatchdog = null
        // 每个清理步骤独立 try-catch：任一失败不影响后续，确保游戏与虚拟屏都被释放
        runCatching { remoteEngine?.stopTask() }
            .onFailure { addLog("停止任务失败: ${it.message}") }
        // 停止任务时同时结束游戏进程（避免游戏残留在后台）
        runCatching { closeGame() }
            .onFailure { addLog("关闭游戏失败: ${it.message}") }
        // 结束虚拟屏（释放捕获器与预览投屏）
        runCatching { remoteEngine?.stopVirtualDisplay() }
            .onFailure { addLog("释放虚拟屏失败: ${it.message}") }
        isPausedState.value = false
        virtualDisplayIdState.value = -1
        // 方案4：Root 模式统一解绑（标记手动，不触发自动重连）+ 杀 root 引擎进程；
        // Shizuku 模式引擎驻留复用（连接状态机保持 Connected，下次任务直接复用）
        if (SettingsRepository.isRootMode(applicationContext)) {
            com.maafw.naruto.service.MaaFwConnectionManager.unbind()
            RootRemoteServiceConnector.killExistingRootService()
        }
        RootRemoteServiceConnector.pendingBinder = null
        addLog("已停止远端引擎")
        updateRunningState(false)
    }

    private fun pauseRemoteEngine() {
        if (isPausedState.value) {
            addLog("当前已处于暂停状态")
            return
        }
        // P0-5 暂停时停止运行期守护（恢复时 startEnabledTasks 会重新启动）
        appWatchdog?.stop()
        appWatchdog = null
        val entry = currentTaskState.value.takeIf { it.isNotBlank() }
        pausedFromEntry = entry
        runCatching {
            val remote = remoteEngine
            val alive = remote != null && runCatching { remote.asBinder().pingBinder() == true }.getOrDefault(false)
            if (alive) {
                // 暂停：只取消任务循环，不销毁引擎/虚拟屏/投屏
                remoteEngine?.pauseTask()
            }
        }.onFailure { addLog("暂停引擎调用失败: ${it.message}（已标记为暂停）") }
        isPausedState.value = true
        addLog("任务已暂停${entry?.let { "（将从 $it 继续）" } ?: ""}，可修改右侧设置后点击继续")
    }

    private fun resumeRemoteEngine(interfaceData: MaaInterface?) {
        if (!isPausedState.value) {
            addLog("当前未处于暂停状态")
            return
        }
        val iface = interfaceData ?: com.maafw.naruto.model.AssetLoader.loadInterface(this)
        // 断点 entry：优先暂停时记录的，其次当前任务，最后从进度表推断 IN_PROGRESS
        val entry = pausedFromEntry
            ?: currentTaskState.value.takeIf { it.isNotBlank() }
            ?: taskProgress.tasks.value.firstOrNull { it.status == com.maafw.naruto.data.task.MaaFwTaskProgress.Status.IN_PROGRESS }?.entry
        pausedFromEntry = null

        // 优化：引擎还活着 → 同引擎直接继续（引擎/资源/虚拟屏都在，不重建、不重载，快且无感）
        val remote = remoteEngine
        val alive = remote != null && runCatching { remote!!.asBinder().pingBinder() == true }.getOrDefault(false)
        if (alive) {
            // 从断点 entry 开始构建剩余任务（跳过已完成的）
            val profile = currentRunningProfile
            val profileObj = com.maafw.naruto.data.profile.ProfileManager.load(this, profile)
            val enabled = profileObj?.tasks?.filter { it.enabled }?.map { it.entry } ?: emptyList()
            val idx = enabled.indexOf(entry)
            val rest = if (idx >= 0) enabled.subList(idx, enabled.size) else enabled
            val tasks = iface?.task ?: emptyList()
            val (screenW, screenH, screenDpi) = SettingsRepository.getResolutionFull(
                SettingsRepository.getResolution(applicationContext), applicationContext
            )
            val screenProfile = ScreenProfile(screenW, screenH, screenDpi)
            val items = org.json.JSONArray()
            for (e in rest) {
                val task = tasks.find { it.entry == e } ?: continue
                val config = SettingsRepository.getTaskConfig(applicationContext, e, profile)
                items.put(org.json.JSONObject().apply {
                    put("entry", e)
                    put("enabled", true)
                    put("options", org.json.JSONObject(config.options))
                    // 断点续跑同样要走适配层，否则 1080p 下 roi/模板仍然是 720p 的值
                    val adapted = PipelineAdaptation.apply(applicationContext, screenProfile, null)
                    if (adapted.override.length() > 0) put("pipeline_override", adapted.override.toString())
                })
            }
            if (items.length() > 0) {
                val ok = runCatching { remote!!.startTasksJson(items.toString()) }.getOrDefault(false)
                if (ok) {
                    isPausedState.value = false
                    runCatching { taskProgress.reset(rest) }
                    runCatching { com.maafw.naruto.data.log.MaaFwSessionLog.startSession(this, profile, items.length()) }
                    // 重启运行期守护 + 进度通知
                    startGameWatchdog(remote!!)
                    runCatching {
                        com.maafw.naruto.service.TaskNotificationCoordinator(this)
                            .notifyTaskRunning(profile, "正在执行 ${items.length()} 个任务")
                    }
                    addLog("继续任务（同引擎，不重建）：从 ${entry ?: "头"}继续 ${items.length()} 个任务")
                    return
                }
            }
        }
        // 引擎不可用/无断点：重建引擎链路（绑定成功后由 onEngineConnected 自动从断点继续）
        addLog("正在重启引擎以继续任务${entry?.let { "（从 $it）" } ?: ""} ...")
        pendingResumeEntry = entry
        stopRemoteEngine()
        bindRemoteEngine()
    }

    private fun setDisplayPower(on: Boolean) {
        isScreenOffState.value = !on
        runCatching {
            // 关屏：停止向预览 Surface 投递虚拟屏画面（不再投屏）
            // 亮屏：恢复投递画面
            remoteEngine?.setPreviewEnabled(on)
            remoteEngine?.setDisplayPower(on)
            addLog(if (on) "已点亮虚拟屏，恢复预览" else "已关闭虚拟屏，停止投屏")
        }.onFailure { addLog("关屏/亮屏失败: ${it.message}") }
    }

    private fun captureScreenshot() {
        runCatching {
            val dir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
            val path = remoteEngine?.captureFramePng("$dir/screenshots")
            addLog(path?.let { "截图已保存: $it" } ?: "截图失败，无可用帧")
        }.onFailure { addLog("截图失败: ${it.message}") }
    }

    private fun closeGame() {
        runCatching {
            val ok = remoteEngine?.stopPackage("com.tencent.KiHan") ?: false
            if (ok) {
                addLog("已关闭游戏")
            } else {
                // binder 失效或引擎关闭失败时，直接以 shell 权限 force-stop 兜底
                addLog("引擎关闭游戏失败，尝试直接强制停止")
                forceStopGameDirect()
            }
        }.onFailure {
            addLog("关闭游戏异常: ${it.message}")
            forceStopGameDirect()
        }
    }

    /** 直接以 shell 权限强制停止游戏进程（不依赖远端引擎 binder） */
    private fun forceStopGameDirect() {
        val killed = if (SettingsRepository.isRootMode(applicationContext)) {
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.tencent.KiHan"))
                p.waitFor() == 0
            }.getOrDefault(false)
        } else {
            // Shizuku 模式：引擎 binder 正常时由 stopPackage（shell 进程 am force-stop）处理；
            // 此处兜底尝试本地 am（无权限通常失败，仅作最后尝试）
            runCatching {
                val p = Runtime.getRuntime().exec("am force-stop com.tencent.KiHan")
                p.waitFor() == 0
            }.getOrDefault(false)
        }
        addLog(if (killed) "已直接强制停止游戏" else "直接强制停止游戏失败")
    }
}