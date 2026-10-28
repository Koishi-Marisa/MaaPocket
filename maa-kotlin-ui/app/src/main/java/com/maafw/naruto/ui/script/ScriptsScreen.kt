@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.maafw.naruto.ui.script

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.MaaTask
import com.maafw.naruto.ui.components.GuideStep
import com.maafw.naruto.ui.components.SpotlightGuide
import com.maafw.naruto.ui.components.rememberGuideTarget
import com.maafw.naruto.ui.theme.MaaDesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 脚本页
 * 完整 BackgroundTaskView.kt：
 * - VirtualDisplayPreview：16:9 预览卡片 + 未运行/等待Surface 覆盖层 + 状态指示点
 * - 预览 Surface：RGBA_8888 + setFixedSize(虚拟屏分辨率) + 尺寸匹配后才发送（修复黑屏 bug）
 * - 全屏预览：点击预览进入全屏，支持触摸注入（复制  坐标换算）
 * - BackgroundMoreActionsOverlay：关屏 / 关游戏 / 静音切换 / 截图 + 自动设置（启动静音等）
 * - 任务勾选改为不可变替换，立即刷新（修复勾选不刷新 bug）
 */
@Composable
fun ScriptsScreen(
    running: Boolean,
    isPaused: Boolean = false,
    isScreenOff: Boolean = false,
    currentTask: String,
    remoteConnected: Boolean = true,
    engineBinding: Boolean = false,
    interfaceData: MaaInterface?,
    logs: List<String>,
    displayResolution: Pair<Int, Int>,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    scriptTouchMarkers: List<IntArray>,
    gameFps: Double = 0.0,
    scriptFps: Double = 0.0,
    onPreviewSurfaceAvailable: (Surface?) -> Unit,
    onStartProfile: (String, List<ProfileManager.ProfileTask>) -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onClearLogs: () -> Unit,
    onScreenOff: () -> Unit,
    onScreenOn: () -> Unit,
    onScreenshot: () -> Unit,
    onCloseGame: () -> Unit,
    onToggleGameSound: (Boolean) -> Unit,
    onInjectTouch: (action: Int, x: Int, y: Int) -> Unit,
    onInjectMultiTouch: (action: Int, points: IntArray, actionIndex: Int) -> Unit = { _, _, _ -> },
    onInjectKey: (keyCode: Int) -> Unit = {},
    onReleaseBackground: () -> Unit = {},
    guideController: com.maafw.naruto.ui.components.GuideController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val allTasks = remember {
        interfaceData?.task?.takeIf { it.isNotEmpty() }
            ?: listOf(MaaTask(name = "进入火影", entry = "start_up"))
    }
    var profile by remember {
        mutableStateOf(
            run {
                val saved = SettingsRepository.getCurrentProfile(context)
                if (saved != ProfileManager.DEFAULT_PROFILE_NAME) {
                    ProfileManager.load(context, saved)
                        ?: ProfileManager.Profile(saved, mutableListOf())
                } else {
                    ProfileManager.loadDefault(context, interfaceData)
                }
            }
        )
    }
    // 记住上次选择的配置（持久化），切换后下次打开沿用
    var currentProfileName by remember {
        mutableStateOf(SettingsRepository.getCurrentProfile(context))
    }
    var profiles by remember { mutableStateOf(ProfileManager.listProfiles(context).ifEmpty { listOf(ProfileManager.DEFAULT_PROFILE_NAME) }) }
    var isProfileMode by remember { mutableStateOf(false) }
    var selectedTaskEntry by remember { mutableStateOf<String?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    var isAddingTask by remember { mutableStateOf(false) }

    // 加载当前配置的任务列表（非默认配置）
    LaunchedEffect(currentProfileName) {
        if (currentProfileName != ProfileManager.DEFAULT_PROFILE_NAME) {
            profile = ProfileManager.load(context, currentProfileName)
                ?: ProfileManager.Profile(currentProfileName, mutableListOf()).also { ProfileManager.save(context, it) }
        } else {
            profile = ProfileManager.loadDefault(context, interfaceData)
        }
    }

    // 离开脚本页（切到其他底部导航）时强制保存当前配置，避免勾选状态/改动丢失
    DisposableEffect(Unit) {
        onDispose { ProfileManager.save(context, profile) }
    }

    // 配置列表实时刷新（新建/删除/重命名后调用）
    fun refreshProfiles() {
        profiles = ProfileManager.listProfiles(context).ifEmpty { listOf(ProfileManager.DEFAULT_PROFILE_NAME) }
    }

    // ---- 首次操作引导（聚光灯，全局渲染避免底栏遮挡/偏移） ----
    val guideTargets = guideController.targets
    val guideSteps = remember {
        listOf(
            GuideStep("preview", "虚拟屏预览", "这里是虚拟屏实时画面。点击可进入全屏，全屏后可点击操作游戏。"),
            GuideStep("panel", "任务列表与配置", "左侧是任务列表：顶部按钮可切换「任务配置」与「编辑任务」；右侧是任务参数设置。"),
            GuideStep("bottom", "开始 / 停止", "配置好任务后，点击「开始任务」运行；运行中可随时「停止」。右上角 ⋮ 有更多操作（静音/截图/关屏等）。")
        )
    }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("maa_guide", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("scripts_guided", false)) {
            guideController.onFinished = {
                context.getSharedPreferences("maa_guide", Context.MODE_PRIVATE)
                    .edit().putBoolean("scripts_guided", true).apply()
            }
            delay(400)
            guideController.start(guideSteps)
        }
    }

    // ---- 预览状态（） ----
    var isSurfaceAvailable by remember { mutableStateOf(false) }
    var isGameMuted by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }

    // ---- 自动设置（SettingsRepository 直读， 开关） ----
    var muteOnLaunch by remember { mutableStateOf(SettingsRepository.isMuteOnGameLaunch(context)) }
    var closeGameOnEnd by remember { mutableStateOf(SettingsRepository.isCloseGameAfterTask(context)) }
    var hwScreenOff by remember { mutableStateOf(SettingsRepository.isUseHardwareScreenOff(context)) }
    var showTouchPreview by remember { mutableStateOf(SettingsRepository.isShowTouchPreview(context)) }
    var scriptLogVisible by remember { mutableStateOf(SettingsRepository.isScriptLogVisible(context)) }

    val tabs = remember(scriptLogVisible) {
        if (scriptLogVisible) listOf(NarutoPanelTab.TASKS, NarutoPanelTab.LOG) else listOf(NarutoPanelTab.TASKS)
    }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabs.size })
    var selectedTab by remember { mutableStateOf(NarutoPanelTab.TASKS) }

    // 日志分页被隐藏时强制回到任务分页，避免选中不存在的页
    LaunchedEffect(scriptLogVisible) {
        if (!scriptLogVisible) {
            selectedTab = NarutoPanelTab.TASKS
            if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
        }
    }

    LaunchedEffect(selectedTab) {
        val idx = tabs.indexOf(selectedTab)
        if (pagerState.currentPage != idx) pagerState.animateScrollToPage(idx)
    }
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = tabs[pagerState.currentPage]
    }

    fun saveProfile() {
        val ok = ProfileManager.save(context, profile)
        if (ok) {
            // 保存后从文件回读并覆盖内存，确保内存与磁盘完全一致（杜绝任何不一致）
            profile = ProfileManager.load(context, profile.name) ?: profile
        } else {
            android.widget.Toast.makeText(
                context,
                "保存失败！请检查存储权限",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 8.dp)
        ) {
            // --- 预览图区域（） ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f)
                    .then(rememberGuideTarget("preview", guideTargets))
            ) {
                if (!isFullscreen) {
                    VirtualDisplayPreview(
                    modifier = Modifier.fillMaxSize(),
                    isRunning = running,
                    isPaused = isPaused,
                    isScreenOff = isScreenOff,
                    currentTask = currentTask,
                    isSurfaceAvailable = isSurfaceAvailable,
                    scriptTouchMarkers = scriptTouchMarkers,
                    displayResolution = displayResolution,
                    showTouchPreview = showTouchPreview,
                    gameFps = gameFps,
                    scriptFps = scriptFps,
                    onClick = { onFullscreenChange(true) }
                ) {
                        PreviewSurface(
                            displayResolution = displayResolution,
                            onSurfaceAvailable = onPreviewSurfaceAvailable,
                            onSurfaceStateChange = { isSurfaceAvailable = it }
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.fillMaxSize())
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- 业务内容区域 ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(7f)
            ) {
                PanelHeader(selectedTab = selectedTab, onTabSelected = { selectedTab = it })

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(rememberGuideTarget("panel", guideTargets))
                ) { page ->
                    when (tabs[page]) {
                        NarutoPanelTab.TASKS -> TaskListDetailLayout(
                            nodes = profile.tasks,
                            allTasks = allTasks,
                            selectedNodeId = selectedTaskEntry,
                            isEditMode = isEditMode,
                            isAddingTask = isAddingTask,
                            isProfileMode = isProfileMode,
                            profiles = profiles,
                            activeProfileId = currentProfileName,
                            onNodeEnabledChange = { entry, enabled ->
                                // 不可变替换，触发重组（修复勾选不刷新 bug）
                                profile = profile.copy(
                                    tasks = profile.tasks.map {
                                        if (it.entry == entry) it.copy(enabled = enabled) else it
                                    }.toMutableList()
                                )
                                saveProfile()
                            },
                            onNodeSelected = { entry ->
                                // 选中左侧任务时自动退出配置模式，切换到对应任务页面
                                selectedTaskEntry = entry
                                isProfileMode = false
                            },
                            onNodeMove = { from, to ->
                                val list = profile.tasks.toMutableList()
                                if (from < 0 || to < 0 || from >= list.size || to >= list.size) return@TaskListDetailLayout
                                val item = list.removeAt(from)
                                list.add(to, item)
                                profile = profile.copy(tasks = list)
                                saveProfile()
                            },
                            onNodeRemove = { index ->
                                val list = profile.tasks.toMutableList()
                                if (index < 0 || index >= list.size) return@TaskListDetailLayout
                                list.removeAt(index)
                                profile = profile.copy(tasks = list)
                                saveProfile()
                            },
                            onToggleEditMode = {
                                isEditMode = !isEditMode
                                if (!isEditMode) saveProfile() // 退出编辑时强制保存
                            },
                            onToggleAddingTask = { isAddingTask = !isAddingTask },
                            onToggleProfileMode = {
                                // 点「任务配置」时先取消任务选中，再切换配置模式（互斥）
                                selectedTaskEntry = null
                                isProfileMode = !isProfileMode
                                if (isProfileMode) {
                                    isEditMode = false
                                    isAddingTask = false
                                    saveProfile()
                                }
                            },
                            onSwitchProfile = { name ->
                                currentProfileName = name
                                selectedTaskEntry = null
                                SettingsRepository.setCurrentProfile(context, name)
                                profile = if (name == ProfileManager.DEFAULT_PROFILE_NAME) {
                                    ProfileManager.loadDefault(context, interfaceData)
                                } else {
                                    ProfileManager.load(context, name)
                                        ?: ProfileManager.Profile(name, mutableListOf()).also { ProfileManager.save(context, it) }
                                }
                                isProfileMode = false
                            },
                            onCreateProfile = { name ->
                                val p = ProfileManager.Profile(name, mutableListOf())
                                ProfileManager.save(context, p)
                                ProfileManager.addProfileToOrder(context, name)
                                currentProfileName = name
                                profile = p
                                SettingsRepository.setCurrentProfile(context, name)
                                refreshProfiles()
                                isProfileMode = false
                            },
                            onDeleteProfile = { name ->
                                ProfileManager.delete(context, name)
                                refreshProfiles()
                                if (currentProfileName == name) {
                                    currentProfileName = ProfileManager.DEFAULT_PROFILE_NAME
                                    SettingsRepository.setCurrentProfile(context, ProfileManager.DEFAULT_PROFILE_NAME)
                                    profile = ProfileManager.loadDefault(context, interfaceData)
                                }
                            },
                            onRenameProfile = { old, new ->
                                if (ProfileManager.rename(context, old, new)) {
                                    if (currentProfileName == old) {
                                        currentProfileName = new
                                        SettingsRepository.setCurrentProfile(context, new)
                                    }
                                    refreshProfiles()
                                }
                            },
                            onMoveProfile = { from, to ->
                                val list = profiles.toMutableList()
                                if (from in list.indices && to in list.indices && from != to) {
                                    val item = list.removeAt(from)
                                    list.add(to, item)
                                    ProfileManager.reorderProfiles(context, list)
                                    refreshProfiles()
                                }
                            },
                            onAddNode = { task ->
                                profile = profile.copy(
                                    tasks = (profile.tasks + ProfileManager.ProfileTask(task.entry, true)).toMutableList()
                                )
                                saveProfile()
                                isAddingTask = false
                            },
                            interfaceData = interfaceData,
                            modifier = Modifier.fillMaxSize()
                        )
                        NarutoPanelTab.LOG -> LogPanel(
                            logs = logs,
                            onClearLogs = onClearLogs,
                            showCopy = SettingsRepository.isScriptLogCopyVisible(context),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // --- 底部操作栏（ 结构） ---
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(rememberGuideTarget("bottom", guideTargets)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPauseResume,
                        enabled = running || isPaused,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (isPaused || !running) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (isPaused) "继续" else if (running) "暂停" else "未运行",
                            tint = if (running || isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                    Button(
                        onClick = { onStartProfile(currentProfileName, profile.tasks) },
                        enabled = !running && !isPaused && remoteConnected,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text(
                            when {
                                !remoteConnected && engineBinding -> "引擎连接中…"
                                !remoteConnected -> "引擎未连接"
                                else -> "开始任务"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp
                        )
                    }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = running || isPaused,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            "停止任务",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp
                        )
                    }
                    IconButton(
                        onClick = { showMoreActions = !showMoreActions },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多操作"
                        )
                    }
                }
            }
        }

        BackHandler(enabled = showMoreActions) {
            showMoreActions = false
        }
        // 编辑/配置模式：按返回先退出并保存当前配置，不直接退出 App
        BackHandler(enabled = isEditMode || isProfileMode || isAddingTask) {
            if (isEditMode) {
                isEditMode = false
                saveProfile() // 退出编辑时强制保存
            }
            if (isProfileMode) isProfileMode = false
            isAddingTask = false
        }
        BackHandler(enabled = isFullscreen) {
            onFullscreenChange(false)
        }
        if (showMoreActions) {
            BackgroundMoreActionsOverlay(
                onDismissRequest = { showMoreActions = false },
                isGameMuted = isGameMuted,
                onToggleGameSound = {
                    val newMuted = !isGameMuted
                    isGameMuted = newMuted
                    onToggleGameSound(newMuted)
                },
                onScreenOff = onScreenOff,
                onScreenOn = onScreenOn,
                onCaptureScreenshot = onScreenshot,
                onCloseApp = onCloseGame,
                onReleaseBackground = onReleaseBackground,
                muteOnGameLaunch = muteOnLaunch,
                onSetMuteOnLaunch = {
                    muteOnLaunch = it
                    SettingsRepository.setMuteOnGameLaunch(context, it)
                },
                closeGameOnTaskEnd = closeGameOnEnd,
                onSetCloseGameOnTaskEnd = {
                    closeGameOnEnd = it
                    SettingsRepository.setCloseGameAfterTask(context, it)
                },
                useHardwareScreenOff = hwScreenOff,
                onSetUseHardwareScreenOff = {
                    hwScreenOff = it
                    SettingsRepository.setUseHardwareScreenOff(context, it)
                },
                showTouchPreview = showTouchPreview,
                onSetShowTouchPreview = {
                    showTouchPreview = it
                    SettingsRepository.setShowTouchPreview(context, it)
                }
            )
        }

        // --- 全屏预览（，含触摸注入；画面按 16:9 等比缩小并居中，保留安全边距） ---
        if (isFullscreen) {
            // 全屏预览强制横屏（）
            DisposableEffect(Unit) {
                val originalOrientation = activity?.requestedOrientation
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                onDispose {
                    if (originalOrientation != null) {
                        activity?.requestedOrientation = originalOrientation
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                // 计算 16:9 等比画面区域，四周留安全边距，避免裁切
                val safeW = maxWidth - 24.dp
                val safeH = maxHeight - 24.dp
                val aspect = 16f / 9f
                val wFromH = safeH * aspect
                val hFromW = safeW / aspect
                val (pvW, pvH) = if (wFromH <= safeW) {
                    wFromH to safeH
                } else {
                    safeW to hFromW
                }

                // 脚本调试：触摸坐标采集（设置页开关）
                val debugTouch = SettingsRepository.isScriptDebugTouch(context)
                var capturedTouches by remember { mutableStateOf(listOf<Pair<Int, Int>>()) }
                // 坐标采集面板拖动偏移（提升到顶层，避免 if 块重组时 remember 丢失导致回弹）
                var debugPanelOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset(0f, 0f)) }

                // 画面区域 Box：触摸注入在 Compose Initial pass（父->子，比 SurfaceView 更早收到），
                // 注入后 consume，SurfaceView 不再处理；SurfaceView listener 返回 false 兜底。
                Box(
                    modifier = Modifier
                        .width(pvW)
                        .height(pvH)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                // 活动指针：Compose pointerId -> 虚拟屏坐标（LinkedHashMap 保持按下顺序）
                                val active = linkedMapOf<Long, Pair<Int, Int>>()
                                while (true) {
                                    // Initial pass：父节点最先收到触摸，抢先注入并消费
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.any { it.isConsumed }) continue

                                    fun toVirtual(cx: Float, cy: Float): Pair<Int, Int>? {
                                        var result: Pair<Int, Int>? = null
                                        viewToVirtualDisplay(
                                            viewX = cx, viewY = cy,
                                            viewWidth = size.width, viewHeight = size.height,
                                            bufferWidth = displayResolution.first, bufferHeight = displayResolution.second
                                        ) { vx, vy -> result = vx to vy }
                                        return result
                                    }

                                    when (event.type) {
                                        PointerEventType.Press -> {
                                            for (change in event.changes) {
                                                if (!change.pressed) continue
                                                val v = toVirtual(change.position.x, change.position.y) ?: continue
                                                active[change.id.value] = v
                                            }
                                            if (active.isEmpty()) continue
                                            // 首指 DOWN，后续指 POINTER_DOWN（新手指在最后）
                                            val pts = active.values.flatMap { listOf(it.first, it.second) }.toIntArray()
                                            if (active.size == 1) {
                                                onInjectMultiTouch(MotionEvent.ACTION_DOWN, pts, -1)
                                            } else {
                                                onInjectMultiTouch(MotionEvent.ACTION_POINTER_DOWN, pts, active.size - 1)
                                            }
                                            if (debugTouch) {
                                                capturedTouches = (capturedTouches + active.values.toList()).takeLast(100)
                                            }
                                        }
                                        PointerEventType.Move -> {
                                            for (change in event.changes) {
                                                if (change.pressed && active.containsKey(change.id.value)) {
                                                    val v = toVirtual(change.position.x, change.position.y) ?: continue
                                                    active[change.id.value] = v
                                                }
                                            }
                                            if (active.isNotEmpty()) {
                                                val pts = active.values.flatMap { listOf(it.first, it.second) }.toIntArray()
                                                onInjectMultiTouch(MotionEvent.ACTION_MOVE, pts, -1)
                                            }
                                        }
                                        PointerEventType.Release -> {
                                            val released = event.changes.filter { !it.pressed && active.containsKey(it.id.value) }
                                            for (change in released) {
                                                val id = change.id.value
                                                val index = active.keys.toList().indexOf(id)
                                                val fullPts = active.values.toList() // 抬起前完整手指
                                                active.remove(id)
                                                if (active.isEmpty()) {
                                                    val last = fullPts.getOrNull(index) ?: continue
                                                    onInjectMultiTouch(
                                                        MotionEvent.ACTION_UP,
                                                        intArrayOf(last.first, last.second),
                                                        -1
                                                    )
                                                } else {
                                                    val pts = fullPts.flatMap { listOf(it.first, it.second) }.toIntArray()
                                                    onInjectMultiTouch(MotionEvent.ACTION_POINTER_UP, pts, index.coerceAtLeast(0))
                                                }
                                            }
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                ) {
                    PreviewSurface(
                        displayResolution = displayResolution,
                        onSurfaceAvailable = onPreviewSurfaceAvailable,
                        onSurfaceStateChange = { isSurfaceAvailable = it },
                        modifier = Modifier.fillMaxSize()
                    )
                    // 关屏遮罩：投屏已关闭
                    if (isScreenOff) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "预览投屏已关闭",
                                color = Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
if (showTouchPreview) {
                            TouchPreviewOverlay(
                                markers = scriptTouchMarkers.takeLast(SettingsRepository.getTouchPreviewCount(context)),
                                displayResolution = displayResolution,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // 帧率显示（Debug）：全屏左上角
                        if (SettingsRepository.isFpsDebugEnabled(context)) {
                            FpsOverlay(
                                gameFps = gameFps,
                                scriptFps = scriptFps,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            )
                        }
                    }
                    // 触摸坐标采集面板：置于画面 Box 外（避免被触摸采集层拦截复制/拖动），可拖动移动位置
                    if (debugTouch) {
                        DebugTouchPanel(
                            touches = capturedTouches,
                            displayResolution = displayResolution,
                            onClear = { capturedTouches = emptyList() },
                            offset = debugPanelOffset,
                            onOffsetChange = { debugPanelOffset = it },
                            modifier = Modifier
                                .offset { IntOffset(debugPanelOffset.x.roundToInt(), debugPanelOffset.y.roundToInt()) }
                                .padding(12.dp)
                                .width(300.dp)
                        )
                    }
                    // 顶部信息条：进入全屏即弹出；开关开启->常驻；开关关闭->3 秒后自动隐藏
                val fullscreenExtraInfo = remember { mutableStateOf(SettingsRepository.isFullscreenExtraInfo(context)) }
                var showFullscreenInfo by remember { mutableStateOf(true) }
                LaunchedEffect(isFullscreen, fullscreenExtraInfo.value) {
                    if (isFullscreen) {
                        showFullscreenInfo = true
                        if (!fullscreenExtraInfo.value) {
                            delay(3000)
                            showFullscreenInfo = false
                        }
                    }
                }
                if (isFullscreen && showFullscreenInfo) {
                    Text(
                        text = buildString {
                            append(when {
                                isPaused -> "已暂停"
                                running -> "运行中"
                                else -> "未运行"
                            })
                            if (currentTask.isNotBlank()) append(" · $currentTask")
                            append("  ${displayResolution.first}x${displayResolution.second}")
                            append("\n点击画面可操作游戏，触摸标记仅显示脚本操作")
                        },
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                // 右上角安全边距外的退出全屏按钮
                IconButton(
                    onClick = { onFullscreenChange(false) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "退出全屏",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }

                // 悬浮导航（竖向，默认在左侧安全边距内）
                // 位置与收起状态持久化到 SharedPreferences，下次全屏恢复
                val floatPrefs = context.getSharedPreferences("fullscreen_float", Context.MODE_PRIVATE)
                var floatOffset by remember {
                    mutableStateOf(
                        Offset(
                            floatPrefs.getFloat("x", 16f),
                            floatPrefs.getFloat("y", 0f)
                        )
                    )
                }
                var floatCollapsed by remember {
                    mutableStateOf(floatPrefs.getBoolean("collapsed", false))
                }
                fun saveFloatState(offset: Offset, collapsed: Boolean) {
                    floatPrefs.edit()
                        .putFloat("x", offset.x)
                        .putFloat("y", offset.y)
                        .putBoolean("collapsed", collapsed)
                        .apply()
                }

                // 通用手势：长按（或移动）进入拖动模式；快速松开=点击切换收起/展开
                fun Modifier.floatDragToggle(offset: Offset, collapsed: Boolean) = this.pointerInput(offset, collapsed) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var dragging = false
                        var lastPos = down.position
                        val startTime = System.currentTimeMillis()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!dragging) {
                                    // 点击：切换收起/展开
                                    change.consume()
                                    floatCollapsed = !collapsed
                                    saveFloatState(offset, floatCollapsed)
                                }
                                break
                            }
                            val elapsed = System.currentTimeMillis() - startTime
                            val dist = (change.position - down.position).getDistance()
                            if (!dragging && (elapsed > 350 || dist > 24f)) {
                                dragging = true
                            }
                            if (dragging) {
                                change.consume()
                                val delta = change.position - lastPos
                                lastPos = change.position
                                // 限制拖动范围：只在屏幕内、且水平限制在左右安全边距区，避免悬浮窗挡住游戏画面
                                val newOffset = Offset(
                                    (offset.x + delta.x).coerceIn(-8f, 900f),
                                    (offset.y + delta.y).coerceIn(-400f, 400f)
                                )
                                floatOffset = newOffset
                                saveFloatState(newOffset, collapsed)
                            }
                        }
                    }
                }

                if (floatCollapsed) {
                    // 收起态：悬浮球。长按/拖动移动，点击展开
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(floatOffset.x.toInt(), floatOffset.y.toInt()) }
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .floatDragToggle(floatOffset, true),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "展开悬浮窗",
                            tint = Color.White
                        )
                    }
                } else {
                    // 展开态：竖向悬浮导航栏（左侧安全边距）
                    Card(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(floatOffset.x.toInt(), floatOffset.y.toInt()) }
                            .zIndex(10f),
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            // 移动手柄：长按拖动位置；点击切换收起/展开
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "拖动移动 / 点击收起",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(6.dp)
                                    .floatDragToggle(floatOffset, false)
                            )
                            Divider(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(1.dp),
                                color = Color.White.copy(alpha = 0.3f)
                            )
                            // 缩小全屏化（退出全屏，原右上角 X 的功能）
                            IconButton(onClick = { onFullscreenChange(false) }) {
                                Icon(
                                    imageVector = Icons.Default.FullscreenExit,
                                    contentDescription = "缩小全屏",
                                    tint = Color.White
                                )
                            }
                            Divider(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(1.dp),
                                color = Color.White.copy(alpha = 0.3f)
                            )
                            // Home
                            IconButton(onClick = { onInjectKey(KeyEvent.KEYCODE_HOME) }) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Home",
                                    tint = Color.White
                                )
                            }
                            Divider(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(1.dp),
                                color = Color.White.copy(alpha = 0.3f)
                            )
                            // 手机真正返回键
                            IconButton(onClick = { onInjectKey(KeyEvent.KEYCODE_BACK) }) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "返回",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (isAddingTask) {
        AddTaskDialog(
            allTasks = allTasks,
            profile = profile,
            onDismiss = { isAddingTask = false },
            onAdd = { task ->
                profile = profile.copy(
                    tasks = (profile.tasks + ProfileManager.ProfileTask(task.entry, true)).toMutableList()
                )
                saveProfile()
                isAddingTask = false
            }
        )
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("关闭游戏") },
            text = { Text("确定要关闭火影忍者游戏吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showCloseConfirm = false
                    onCloseGame()
                }) { Text("关闭", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) { Text("取消") }
            }
        )
    }

    // 首次操作引导（聚光灯）已提升到 MainActivity 全局渲染，避免底栏遮挡与坐标偏移
}

/**
 * 预览 Surface（ previewContent 核心逻辑）。
 * RGBA_8888 + setFixedSize(虚拟屏分辨率) + surfaceChanged 尺寸匹配后才发送——
 * 修复「必须切换分页才显示、否则黑屏」的问题。
 */
@Composable
private fun PreviewSurface(
    displayResolution: Pair<Int, Int>,
    onSurfaceAvailable: (Surface?) -> Unit,
    onSurfaceStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val currentResolution by rememberUpdatedState(displayResolution)

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setFormat(PixelFormat.RGBA_8888)
                // 触摸注入由 Compose 父级 Initial pass 处理（比 SurfaceView 更早收到事件）。
                // SurfaceView 必须不消费、不拦截，让事件进入 Compose 分发链。
                isClickable = false
                isFocusable = false
                setOnTouchListener { _, _ -> false }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceStateChange(true)
                        scope.launch {
                            delay(50)
                            val res = currentResolution
                            holder.setFixedSize(res.first, res.second)
                        }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    // 全屏/旋转/缩放都会触发 Surface 重建（尺寸变化，不再精确等于虚拟屏分辨率）。
                    // 总是重新绑定投屏：SurfaceView 会自动拉伸显示内容，不需要尺寸精确匹配，
                    // 否则重建后不发送 -> 投屏断开不恢复 -> 黑屏。
                    scope.launch {
                        delay(50)
                        onSurfaceAvailable(holder.surface)
                    }
                }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceStateChange(false)
                        onSurfaceAvailable(null)
                    }
                })
            }
        },
        modifier = modifier
    )
}

/**
 * 虚拟屏预览卡片（ VirtualDisplayPreview.kt，去掉 GameWatchdog/koin）。
 */
@Composable
private fun VirtualDisplayPreview(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    isPaused: Boolean = false,
    isScreenOff: Boolean = false,
    currentTask: String,
    isSurfaceAvailable: Boolean,
    onClick: () -> Unit,
    scriptTouchMarkers: List<IntArray> = emptyList(),
    displayResolution: Pair<Int, Int> = Pair(1280, 720),
    showTouchPreview: Boolean = false,
    gameFps: Double = 0.0,
    scriptFps: Double = 0.0,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        val aspectRatio = 16f / 9f
        val widthFromHeight = maxHeight * aspectRatio
        val heightFromWidth = maxWidth / aspectRatio
        val (cardWidth, cardHeight) = if (widthFromHeight <= maxWidth) {
            widthFromHeight to maxHeight
        } else {
            maxWidth to heightFromWidth
        }

        Card(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clickable(onClick = onClick),
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
Box(modifier = Modifier.fillMaxSize()) {
                        content()

                        // 小预览卡片也叠加脚本触摸标记
                        if (showTouchPreview) {
                            val ctx = LocalContext.current
                            TouchPreviewOverlay(
                                markers = scriptTouchMarkers.takeLast(SettingsRepository.getTouchPreviewCount(ctx)),
                                displayResolution = displayResolution,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // 帧率显示（Debug）：左上角
                        if (SettingsRepository.isFpsDebugEnabled(LocalContext.current)) {
                            FpsOverlay(
                                gameFps = gameFps,
                                scriptFps = scriptFps,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                            )
                        }

                when {
                    isScreenOff -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "预览投屏已关闭",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    !isRunning && !isPaused -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "未运行，点击预览全屏",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    isPaused -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "已暂停",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    !isSurfaceAvailable -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "等待画面…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 状态指示点（ 看门狗指示器，显示当前任务）
                val (dotColor, label) = when {
                    isScreenOff -> Color(0xFF9E9E9E) to "投屏已关闭"
                    isPaused -> Color(0xFFFFA000) to ("已暂停" + if (currentTask.isNotBlank()) " · $currentTask" else "")
                    isRunning -> Color(0xFF4CAF50) to ("运行中" + if (currentTask.isNotBlank()) " · $currentTask" else "")
                    else -> Color(0xFF9E9E9E) to "空闲"
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            Color.Black.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/** 视图坐标 -> 虚拟屏坐标（ viewToVirtualDisplay） */
private inline fun viewToVirtualDisplay(
    viewX: Float,
    viewY: Float,
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    block: (vx: Int, vy: Int) -> Unit,
) {
    val bufferW = bufferWidth.toFloat()
    val bufferH = bufferHeight.toFloat()
    val scale = minOf(viewWidth / bufferW, viewHeight / bufferH)
    val offsetX = (viewWidth - bufferW * scale) / 2f
    val offsetY = (viewHeight - bufferH * scale) / 2f
    val vx = ((viewX - offsetX) / scale).toInt()
    val vy = ((viewY - offsetY) / scale).toInt()
    if (vx < 0 || vx >= bufferW.toInt() || vy < 0 || vy >= bufferH.toInt()) return
    block(vx, vy)
}

/**
 * 更多操作浮层（ BackgroundMoreActionsOverlay.kt）。
 */
@Composable
private fun BackgroundMoreActionsOverlay(
    onDismissRequest: () -> Unit,
    isGameMuted: Boolean,
    onToggleGameSound: () -> Unit,
    onScreenOff: () -> Unit,
    onScreenOn: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onCloseApp: () -> Unit,
    onReleaseBackground: () -> Unit,
    muteOnGameLaunch: Boolean,
    onSetMuteOnLaunch: (Boolean) -> Unit,
    closeGameOnTaskEnd: Boolean,
    onSetCloseGameOnTaskEnd: (Boolean) -> Unit,
    useHardwareScreenOff: Boolean,
    onSetUseHardwareScreenOff: (Boolean) -> Unit,
    showTouchPreview: Boolean,
    onSetShowTouchPreview: (Boolean) -> Unit,
) {
    val overlayInteractionSource = remember { MutableInteractionSource() }
    val cardInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = overlayInteractionSource,
                indication = null,
                onClick = onDismissRequest
            )
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 64.dp)
                .clickable(
                    interactionSource = cardInteractionSource,
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "快捷操作",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionTile(
                        icon = Icons.Filled.PowerSettingsNew,
                        label = "关屏",
                        onClick = onScreenOff,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    ActionTile(
                        icon = Icons.Filled.Lightbulb,
                        label = "亮屏",
                        onClick = onScreenOn,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    ActionTile(
                        icon = Icons.Filled.ExitToApp,
                        label = "关闭游戏",
                        onClick = onCloseApp,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionTile(
                        icon = Icons.Filled.Cancel,
                        label = "释放后台资源",
                        onClick = onReleaseBackground,
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionTile(
                        icon = if (isGameMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        label = if (isGameMuted) "已静音" else "静音游戏",
                        onClick = onToggleGameSound,
                        modifier = Modifier.weight(1f),
                        containerColor = if (isGameMuted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                        contentColor = if (isGameMuted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                    )
                    ActionTile(
                        icon = Icons.Filled.Screenshot,
                        label = "截图",
                        onClick = onCaptureScreenshot,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "自动设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                SettingSwitchRow(
                    icon = Icons.Filled.NotificationsPaused,
                    label = "启动任务时静音",
                    checked = muteOnGameLaunch,
                    onCheckedChange = onSetMuteOnLaunch
                )
                SettingSwitchRow(
                    icon = Icons.Filled.Cancel,
                    label = "任务结束后关闭游戏",
                    checked = closeGameOnTaskEnd,
                    onCheckedChange = onSetCloseGameOnTaskEnd
                )
                SettingSwitchRow(
                    icon = Icons.Filled.StayCurrentPortrait,
                    label = "硬件熄屏",
                    checked = useHardwareScreenOff,
                    onCheckedChange = onSetUseHardwareScreenOff
                )
                SettingSwitchRow(
                    icon = Icons.Filled.TouchApp,
                    label = "显示触摸预览",
                    checked = showTouchPreview,
                    onCheckedChange = onSetShowTouchPreview
                )
            }
        }
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(4.dp),
        color = containerColor.copy(alpha = 0.08f),
        contentColor = contentColor,
        border = BorderStroke(0.5.dp, containerColor.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = containerColor.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AddTaskDialog(
    allTasks: List<MaaTask>,
    profile: ProfileManager.Profile,
    onDismiss: () -> Unit,
    onAdd: (MaaTask) -> Unit
) {
    val added = profile.tasks.mapNotNull { pt -> allTasks.find { it.entry == pt.entry } }
    val notAdded = allTasks.filter { t -> profile.tasks.none { it.entry == t.entry } }
    var showAdded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加任务") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 已添加任务（可展开，展开后可以重复添加）
                if (added.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAdded = !showAdded },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "已添加（${added.size}）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (showAdded) "收起" else "展开（可重复添加）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                if (showAdded) {
                    items(added) { task ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(task) },
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    task.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "已添加",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                // 可添加任务
                if (notAdded.isNotEmpty()) {
                    item {
                        Text(
                            "可添加",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(notAdded) { task ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(task) },
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                task.name,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } }
    )
}

// ---- 触摸预览标记（ PreviewTouchMarker.kt） ----

/** 触摸标记：虚拟屏坐标 + 动作 + 创建时间，用于在预览上显示触摸痕迹 */
data class PreviewTouchMarker(
    val x: Int,
    val y: Int,
    val action: Int,
    val createdAtMs: Long = SystemClock.elapsedRealtime()
) {
    companion object {
        const val TTL_MS = 600L
    }
}

/**
 * 调试触摸采集面板：全屏预览触摸时实时显示 Maa 点击位置（target 格式），支持复制。
 * 输出格式：
 *   "target": [x-30, y-30, 60, 60],   // 以触摸点为中心 60x60 点击区域
 */
@Composable
private fun DebugTouchPanel(
    touches: List<Pair<Int, Int>>,
    displayResolution: Pair<Int, Int>,
    onClear: () -> Unit,
    offset: androidx.compose.ui.geometry.Offset,
    onOffsetChange: (androidx.compose.ui.geometry.Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val text = buildString {
        touches.forEach { (x, y) ->
            val tx = (x - 30).coerceAtLeast(0)
            val ty = (y - 30).coerceAtLeast(0)
            append("\"target\": [$tx, $ty, 60, 60],   // 点击点 ($x, $y)\n")
        }
    }
    // 拖动闭包需读取最新 offset（pointerInput(Unit) 不随 offset 重启，用 rememberUpdatedState 保持最新）
    val currentOffset by rememberUpdatedState(offset)
    Card(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onOffsetChange(currentOffset + dragAmount)
            }
        },
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.78f))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "触摸坐标 (${displayResolution.first}x${displayResolution.second})",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
                TextButton(onClick = onClear) {
                    Text("清空", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                text = text.ifEmpty { "触摸虚拟屏画面采集坐标…" },
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
            // 拖动提示 + 复制按钮（面板已移到触摸采集层外，点击正常）
            Text(
                text = "拖动标题栏可移动面板",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall
            )
            TextButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("maa_touch", text))
                    Toast.makeText(context, "坐标已复制（${touches.size} 条）", Toast.LENGTH_SHORT).show()
                },
                enabled = text.isNotBlank()
            ) {
                Text("复制全部坐标", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * 帧率显示浮层（Debug）：虚拟屏游戏真实帧率 + 脚本识别频率。
 * 显示在预览/全屏画面左上角；脚本识别频率为 0 表示脚本卡住。
 */
@Composable
private fun FpsOverlay(
    gameFps: Double,
    scriptFps: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = "游戏 ${gameFps.toInt()} FPS",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 13.sp
        )
        Text(
            text = "脚本 ${scriptFps.toInt()} 次/s",
            color = Color(0xFFFFCC66),
            fontSize = 10.sp,
            lineHeight = 12.sp
        )
    }
}

/**
 * MD3 简洁触摸预览：点击=实心圆、长按=圆环、滑动=轨迹线。
 * markers 格式：[type, x1, y1, (x2, y2)]，type: 0=tap 1=longPress 2=swipe
 */
@Composable
private fun TouchPreviewOverlay(
    markers: List<IntArray>,
    displayResolution: Pair<Int, Int>,
    modifier: Modifier = Modifier,
) {
    val width = displayResolution.first
    val height = displayResolution.second
    Canvas(modifier = modifier) {
        val maxX = (width - 1).coerceAtLeast(1)
        val maxY = (height - 1).coerceAtLeast(1)
        fun off(x: Int, y: Int): Offset = Offset(
            x = size.width * x.coerceIn(0, maxX) / maxX.toFloat(),
            y = size.height * y.coerceIn(0, maxY) / maxY.toFloat()
        )
        val tapColor = Color(0xFF6750A4) // MD3 primary
        val longColor = Color(0xFF625B71) // MD3 secondary
        val swipeColor = Color(0xFF7D5260) // MD3 tertiary
        markers.forEach { m ->
            if (m.size < 3) return@forEach
            when (m[0]) {
                0 -> { // 点击：实心小圆
                    drawCircle(color = tapColor, radius = 5.dp.toPx(), center = off(m[1], m[2]))
                }
                1 -> { // 长按：圆环 + 中心点
                    val c = off(m[1], m[2])
                    drawCircle(
                        color = longColor, radius = 13.dp.toPx(), center = c,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                    drawCircle(color = longColor, radius = 4.dp.toPx(), center = c)
                }
                2 -> { // 滑动：起点->终点轨迹线
                    val s = off(m[1], m[2])
                    val e = off(m[3], m[4])
                    drawLine(color = swipeColor, start = s, end = e, strokeWidth = 2.5.dp.toPx())
                    drawCircle(color = swipeColor, radius = 3.5.dp.toPx(), center = s)
                    drawCircle(
                        color = swipeColor, radius = 6.dp.toPx(), center = e,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
    }
}