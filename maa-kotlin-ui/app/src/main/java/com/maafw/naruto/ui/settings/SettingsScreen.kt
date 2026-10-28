package com.maafw.naruto.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.maafw.naruto.data.log.LogExporter
import com.maafw.naruto.data.profile.ProfileExporter
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.data.settings.ConfigExporter
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.ui.components.CollapsibleSection
import com.maafw.naruto.ui.components.ListItemDivider
import com.maafw.naruto.ui.components.MaaTopAppBar
import com.maafw.naruto.ui.components.SectionHeader
import com.maafw.naruto.ui.components.SettingRow
import com.maafw.naruto.ui.components.SettingsGroupCard
import com.maafw.naruto.ui.theme.MaaDesignTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页
 * 分区结构：
 * 显示设置 / 运行设置 / 通知设置 / 日志 / 数据管理 / 关于，
 * 每个分区用 CollapsibleSection + SettingsGroupCard。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    logBuffer: MutableList<String>,
    theme: String,
    onThemeChange: (String) -> Unit,
    uiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    onResolutionChange: (String) -> Unit,
    onRunModeChange: (String) -> Unit,
    onCaptureLogcat: (() -> String?)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logUpdateTime = remember { LogExporter.sourceLastModifiedText(context) }

    // 分辨率 / 运行模式（设置页内部 state，保存时回调）
    var resolution by remember { mutableStateOf(SettingsRepository.getResolution(context)) }
    var runMode by remember { mutableStateOf(SettingsRepository.getRunMode(context)) }
    var scale by remember { mutableStateOf(uiScale) }
    var customW by remember { mutableStateOf(SettingsRepository.getCustomWidth(context)) }
    var customH by remember { mutableStateOf(SettingsRepository.getCustomHeight(context)) }
    var customDpi by remember { mutableStateOf(SettingsRepository.getCustomDpi(context)) }
    var scheduleWakeOn by remember { mutableStateOf(SettingsRepository.isScheduleWakeOn(context)) }
    var scriptLogVisible by remember { mutableStateOf(SettingsRepository.isScriptLogVisible(context)) }
    var scriptLogCopyVisible by remember { mutableStateOf(SettingsRepository.isScriptLogCopyVisible(context)) }
    var scriptDebugTouch by remember { mutableStateOf(SettingsRepository.isScriptDebugTouch(context)) }
    var showTouchPreview by remember { mutableStateOf(SettingsRepository.isShowTouchPreview(context)) }
    var touchPreviewCount by remember { mutableStateOf(SettingsRepository.getTouchPreviewCount(context).toString()) }

    // 通知推送配置 state 
    var pushChannel by remember { mutableStateOf(SettingsRepository.getPushChannel(context)) }
    var pushMiaoToken by remember { mutableStateOf(SettingsRepository.getPushMiaotixingToken(context)) }
    var pushServerKey by remember { mutableStateOf(SettingsRepository.getPushServerChanKey(context)) }
    var pushDingToken by remember { mutableStateOf(SettingsRepository.getPushDingTalkToken(context)) }
    var pushSmtpHost by remember { mutableStateOf(SettingsRepository.getPushSmtpHost(context)) }
    var pushSmtpPort by remember { mutableStateOf(SettingsRepository.getPushSmtpPort(context).toString()) }
    var pushSmtpUser by remember { mutableStateOf(SettingsRepository.getPushSmtpUser(context)) }
    var pushSmtpPass by remember { mutableStateOf(SettingsRepository.getPushSmtpPass(context)) }
    var pushSmtpTo by remember { mutableStateOf(SettingsRepository.getPushSmtpTo(context)) }
    var pushWebhookUrl by remember { mutableStateOf(SettingsRepository.getPushWebhookUrl(context)) }
    var pushWebhookBody by remember { mutableStateOf(SettingsRepository.getPushWebhookBody(context)) }

    // 第三方通知设置页（按钮入口 -> 独立页面）
    var showNotificationSettings by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val content = context.applicationContext.contentResolver.openInputStream(uri)?.use {
                    it.reader(Charsets.UTF_8).readText()
                } ?: return@withContext Result.failure<String>(IllegalStateException("无法打开文件"))
                runCatching {
                    val json = org.json.JSONObject(content)
                    when (json.optString("type", "settings")) {
                        "profile" -> ProfileExporter.importProfile(context, content).getOrThrow()
                        else -> ConfigExporter.importFromJson(context, content).getOrThrow()
                    }
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { msg ->
                    Toast.makeText(context, "$msg\n请重启应用使全部设置生效", Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { ConfigExporter.export(context) }
                withContext(Dispatchers.Main) {
                    result.onSuccess { path ->
                        Toast.makeText(context, "配置已导出到:\n$path", Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Toast.makeText(context, "未授予存储权限，无法导出配置", Toast.LENGTH_LONG).show()
        }
    }

    fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** 导出配置到 /storage/emulated/0/Maafw配置 */
    fun exportConfig() {
        if (!hasStorageAccess()) {
            Toast.makeText(context, "需要所有文件访问权限才能导出配置，正在跳转设置", Toast.LENGTH_LONG).show()
            requestStorageAccess()
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { ConfigExporter.export(context) }
            withContext(Dispatchers.Main) {
                result.onSuccess { path ->
                    Toast.makeText(context, "配置已导出到:\n$path", Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 从 JSON 文件导入配置 */
    fun importConfig() {
        importLauncher.launch(arrayOf("application/json"))
    }

    /** 导出指定任务配置 */
    fun exportProfileConfig(profileName: String) {
        if (!hasStorageAccess()) {
            Toast.makeText(context, "需要所有文件访问权限才能导出配置，正在跳转设置", Toast.LENGTH_LONG).show()
            requestStorageAccess()
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { ProfileExporter.exportProfile(context, profileName) }
            withContext(Dispatchers.Main) {
                result.onSuccess { path ->
                    Toast.makeText(context, "配置已导出到:\n$path", Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 导出最新日志到 /storage/emulated/0/maa日志（带时间戳文件名） */
    fun exportLog() {
        if (!LogExporter.hasStoragePermission()) {
            // P0-D：无"所有文件访问"权限 -> 引导跳转授权页（授予后导出到公共目录）；
            // 同时不阻断导出（fallback 到应用私有目录），确保能拿到日志
            Toast.makeText(context, "未授予存储权限，已引导授权；本次日志导出到应用私有目录", Toast.LENGTH_LONG).show()
            runCatching {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }.onFailure {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
            }
        }
        scope.launch {
            val maafwResult = withContext(Dispatchers.IO) { LogExporter.exportLatest(context) }
            val appResult = withContext(Dispatchers.IO) { LogExporter.exportAppLog(context, logBuffer.toList()) }
            val logcatResult = withContext(Dispatchers.IO) {
                // 优先用引擎（shell 进程）抓取全量 logcat（含引擎自身日志），否则用本地 logcat
                val engineText = onCaptureLogcat?.invoke()
                if (engineText != null) {
                    LogExporter.exportLogcatText(context, engineText)
                } else {
                    LogExporter.exportLogcat(context)
                }
            }
            // 每种日志（maafw_/app_/logcat_）只保留最近 3 份，删除更旧的，避免导出目录无限膨胀
            withContext(Dispatchers.IO) { LogExporter.cleanupOldExports(context) }
            val parts = mutableListOf<String>()
            maafwResult.onSuccess { parts += "引擎日志: $it" }
                .onFailure { e ->
                    // maafw.log 不存在属于"引擎未运行"，不算导出失败
                    parts += if (e.message?.contains("不存在") == true) "引擎日志: 无（引擎未运行，无 maafw.log）" else "引擎日志导出失败: ${e.message}"
                }
            appResult.onSuccess { parts += "应用日志: $it" }.onFailure { parts += "应用日志导出失败: ${it.message}" }
            logcatResult.onSuccess { parts += "系统日志: $it" }
                .onFailure { parts += "系统日志导出失败: ${it.message}（可连接引擎/授予root后重试）" }
            // 复刻 py 的 custom 日志（FindToChallenge 等 Kotlin 复刻版执行日志）
            withContext(Dispatchers.IO) { LogExporter.exportCustomLog(context) }
                ?.onSuccess { parts += "Custom日志: $it" }
                ?.onFailure { parts += "Custom日志导出失败: ${it.message}" }
            // agent 独立进程日志（FindToChallenge 在 agent 进程的执行输出）
            withContext(Dispatchers.IO) { LogExporter.exportAgentLog(context) }
                ?.onSuccess { parts += "Agent日志: $it" }
                ?.onFailure { parts += "Agent日志导出失败: ${it.message}" }
            // P1/L-4：打包 ZIP（三层诊断 + 会话日志 + crash + 设备信息），一次性带走
            var zipPath: String? = null
            withContext(Dispatchers.IO) {
                // 先把 App 侧运行日志写入 debug 目录，ZIP 打包时一并带走（补全"只导引擎日志"的缺口）
                runCatching {
                    val debugDir = java.io.File(context.getExternalFilesDir(null), "debug").apply { mkdirs() }
                    java.io.File(debugDir, "app_runtime.log").writeText(logBuffer.joinToString("\n"))
                }
                LogExporter.exportAllToZip(context)
            }.onSuccess { zipPath = it; parts += "ZIP打包: $it" }
                .onFailure { parts += "ZIP打包失败: ${it.message}" }
            // 汇总提示：吐司只显示一句话（成功/无可导出/失败），明细不进吐司
            val successCount = parts.count { !it.contains("失败") && !it.contains("无（") }
            val failCount = parts.count { it.contains("失败") }
            val msg = when {
                successCount > 0 -> "日志已成功导出"
                failCount == 0 -> "当前暂无可导出日志"
                else -> "导出日志失败"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            // ZIP 导出成功 -> 弹出系统分享（可发送到微信/QQ/钉钉等，方便反馈日志）
            if (zipPath != null) {
                runCatching {
                    val zipFile = java.io.File(zipPath!!)
                    val authority = "${context.packageName}.fileprovider"
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, zipFile)
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "MAAFW 火影忍者 日志导出")
                        putExtra(android.content.Intent.EXTRA_TEXT, "MAAFW 诊断日志 ZIP（含绑定链路/引擎启动/会话/crash/设备信息），请查收")
                        flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(android.content.Intent.createChooser(share, "分享日志 ZIP 到…").apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure { android.util.Log.w("SettingsScreen", "分享ZIP失败: ${it.message}") }
            }
        }
    }

    var keepScreenOn by remember { mutableStateOf(SettingsRepository.isKeepScreenOn(context)) }
    var showFloatingLog by remember { mutableStateOf(SettingsRepository.isShowFloatingLog(context)) }
    var fullscreenExtraInfo by remember { mutableStateOf(SettingsRepository.isFullscreenExtraInfo(context)) }
    var keepAliveEnabled by remember { mutableStateOf(SettingsRepository.isKeepAliveEnabled(context)) }
    var accessibilityKeepAlive by remember { mutableStateOf(SettingsRepository.isAccessibilityKeepAliveEnabled(context)) }
    var floatingControlEnabled by remember { mutableStateOf(SettingsRepository.isFloatingControlEnabled(context)) }
    var screenSaverEnabled by remember { mutableStateOf(SettingsRepository.isScreenSaverEnabled(context)) }
    var verboseLogging by remember { mutableStateOf(SettingsRepository.isVerboseLogging(context)) }
    var forceStopEnabled by remember { mutableStateOf(SettingsRepository.isForceStopEnabled(context)) }
    var memoryCleanBeforeTask by remember { mutableStateOf(SettingsRepository.isMemoryCleanBeforeTask(context)) }
    var autoStartShizuku by remember { mutableStateOf(SettingsRepository.isAutoStartShizuku(context)) }
    var closeGameAfterTask by remember { mutableStateOf(SettingsRepository.isCloseGameAfterTask(context)) }
    var themeExpanded by remember { mutableStateOf(false) }

    // Root 守护进程 / 帧率调试（仅 root 授权时显示守护开关；后台线程检查避免 su 阻塞 UI）
    var rootDaemonEnabled by remember { mutableStateOf(SettingsRepository.isRootDaemonEnabled(context)) }
    var fpsDebugEnabled by remember { mutableStateOf(SettingsRepository.isFpsDebugEnabled(context)) }
    var engineReuseEnabled by remember { mutableStateOf(SettingsRepository.isEngineReuseEnabled(context)) }
    var rootGranted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        rootGranted = withContext(Dispatchers.IO) { com.maafw.naruto.root.RootManager.isRootGranted() }
    }

    var notificationEnabled by remember { mutableStateOf(SettingsRepository.isNotificationEnabled(context)) }
    var notificationSound by remember { mutableStateOf(SettingsRepository.isNotificationSound(context)) }
    var notificationVibrate by remember { mutableStateOf(SettingsRepository.isNotificationVibrate(context)) }
    var notificationTaskStart by remember { mutableStateOf(SettingsRepository.isNotifyTaskStart(context)) }
    var notificationTaskComplete by remember { mutableStateOf(SettingsRepository.isNotifyTaskComplete(context)) }
    var notificationTaskError by remember { mutableStateOf(SettingsRepository.isNotifyTaskError(context)) }
    var notificationServiceEvent by remember { mutableStateOf(SettingsRepository.isNotifyServiceEvent(context)) }

    var logs by remember { mutableStateOf(logBuffer.toList()) }
    LaunchedEffect(logBuffer) {
        logs = logBuffer.toList()
    }

    var showExportDialog by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }
    var showUpdateInfo by remember { mutableStateOf<com.maafw.naruto.data.update.UpdateChecker.UpdateInfo?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(ProfileManager.listProfiles(context)) }
    LaunchedEffect(showExportDialog) {
        if (showExportDialog) profiles = ProfileManager.listProfiles(context)
    }

    if (showNotificationSettings) {
        NotificationSettingsScreen(onBack = { showNotificationSettings = false })
    } else {
    Scaffold(
        topBar = {
            MaaTopAppBar(title = "设置")
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = MaaDesignTokens.Spacing.listHorizontal,
                vertical = MaaDesignTokens.Spacing.sm
            ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sectionGap)
        ) {
// 显示设置（ settings_section_display）
                item {
                    CollapsibleSection(
                        title = "显示设置",
                        sectionKey = "settings_section_display",
                        initiallyExpanded = true,
                    ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "主题模式",
                            description = "跟随系统 / 浅色 / 深色",
                            trailing = {
                                Box {
                                    Button(
                                        onClick = { themeExpanded = true },
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    ) {
                                        Text(themeName(theme), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    DropdownMenu(
                                        expanded = themeExpanded,
                                        onDismissRequest = { themeExpanded = false }
                                    ) {
                                        listOf(
                                            SettingsRepository.THEME_SYSTEM to "跟随系统",
                                            SettingsRepository.THEME_LIGHT to "浅色",
                                            SettingsRepository.THEME_DARK to "深色",
                                            SettingsRepository.THEME_MONET to "莫奈（壁纸取色）"
                                        ).forEach { (value, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    SettingsRepository.setTheme(context, value)
                                                    onThemeChange(value)
                                                    themeExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "屏幕常亮",
                            description = "运行任务时保持屏幕点亮",
                            trailing = {
                                Switch(
                                    checked = keepScreenOn,
                                    onCheckedChange = {
                                        keepScreenOn = it
                                        SettingsRepository.setKeepScreenOn(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "页面缩放",
                            description = "调整界面整体大小（含间距）${(scale * 100).toInt()}%（松手生效）",
                            trailing = {
                                Slider(
                                    value = scale,
                                    onValueChange = { scale = it },
                                    onValueChangeFinished = {
                                        // 松手后才应用全局缩放，避免拖动时界面变化导致滑条脱手
                                        onUiScaleChange(scale)
                                        SettingsRepository.setUiScale(context, scale)
                                    },
                                    valueRange = 0.7f..1.3f,
                                    modifier = Modifier.width(120.dp)
                                )
                            }
                        )
                    }
                }
            }

            // 运行设置
            item {
                CollapsibleSection(
                    title = "运行设置",
                    sectionKey = "settings_section_run",
                ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "自动启动 Shizuku",
                            description = "应用启动时尝试拉起 Shizuku 服务",
                            trailing = {
                                Switch(
                                    checked = autoStartShizuku,
                                    onCheckedChange = {
                                        autoStartShizuku = it
                                        SettingsRepository.setAutoStartShizuku(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "后台保活",
                            description = "常驻前台服务，防止脚本后台运行被系统杀掉（默认关闭）",
                            trailing = {
                                Switch(
                                    checked = keepAliveEnabled,
                                    onCheckedChange = {
                                        keepAliveEnabled = it
                                        SettingsRepository.setKeepAliveEnabled(context, it)
                                        if (it) {
                                            com.maafw.naruto.service.KeepAliveService.start(context)
                                        } else {
                                            com.maafw.naruto.service.KeepAliveService.stop(context)
                                        }
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "无障碍防杀（推荐）",
                            description = if (accessibilityKeepAlive)
                                "已启用：App 进程受系统保护，后台挂机引擎更稳"
                            else "一键开启后 App 受系统保护（非 root 保活核心），引擎代授免手动进设置",
                            onClick = {
                                // 一键开启：引擎 shell 代授无障碍（root/Shizuku root 直接启用，免手动进系统设置）
                                val remote = com.maafw.naruto.service.EngineConnectionShared.aliveService()
                                val granted = if (remote != null) {
                                    runCatching { remote.grantPermissions(context.packageName, 32) }.getOrDefault(0)
                                } else 0
                                if (granted and 32 != 0) {
                                    Toast.makeText(context, "无障碍防杀已启用（引擎代授，无需手动设置）", Toast.LENGTH_LONG).show()
                                } else {
                                    // 引擎不可用或代授失败 → 引导手动开启
                                    Toast.makeText(context, "已引导前往无障碍设置开启", Toast.LENGTH_SHORT).show()
                                    runCatching {
                                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }
                                }
                                // 稍后刷新状态（服务连接后）
                                kotlinx.coroutines.GlobalScope.launch {
                                    kotlinx.coroutines.delay(2000)
                                    accessibilityKeepAlive = SettingsRepository.isAccessibilityKeepAliveEnabled(context)
                                }
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "悬浮球控制",
                            description = "后台挂机时悬浮球快捷操作（开始/停止/暂停/关屏），需悬浮窗权限",
                            trailing = {
                                Switch(
                                    checked = floatingControlEnabled,
                                    onCheckedChange = {
                                        floatingControlEnabled = it
                                        SettingsRepository.setFloatingControlEnabled(context, it)
                                        if (it) {
                                            // 无悬浮窗权限 → 引导授权
                                            if (!android.provider.Settings.canDrawOverlays(context)) {
                                                Toast.makeText(context, "需要悬浮窗权限，正在跳转授权…", Toast.LENGTH_SHORT).show()
                                                runCatching {
                                                    context.startActivity(
                                                        android.content.Intent(
                                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                            android.net.Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                }
                                            } else {
                                                com.maafw.naruto.overlay.MaaFwFloatingControl.show(
                                                    context,
                                                    com.maafw.naruto.service.EngineConnectionShared.aliveService()
                                                )
                                            }
                                        } else {
                                            com.maafw.naruto.overlay.MaaFwFloatingControl.dismiss()
                                        }
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "开始任务前清理内存",
                            description = "点击开始任务时释放后台进程内存（不杀自身引擎/游戏/Shizuku）",
                            trailing = {
                                Switch(
                                    checked = memoryCleanBeforeTask,
                                    onCheckedChange = {
                                        memoryCleanBeforeTask = it
                                        SettingsRepository.setMemoryCleanBeforeTask(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "详细日志",
                            description = "开启后引擎输出更详细的识别/动作日志（排障用，默认关闭）",
                            trailing = {
                                Switch(
                                    checked = verboseLogging,
                                    onCheckedChange = {
                                        verboseLogging = it
                                        SettingsRepository.setVerboseLogging(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "强制重启游戏",
                            description = "任务启动时先 force-stop 游戏再启动（游戏状态不干净时更稳；默认关闭）",
                            trailing = {
                                Switch(
                                    checked = forceStopEnabled,
                                    onCheckedChange = {
                                        forceStopEnabled = it
                                        SettingsRepository.setForceStopEnabled(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "一键授予后台权限",
                            description = "通过引擎代授：省电豁免/后台不受限/悬浮窗/存储（引擎连接后可用）",
                            onClick = {
                                val remote = com.maafw.naruto.service.EngineConnectionShared.aliveService()
                                if (remote == null) {
                                    Toast.makeText(context, "引擎未连接，请先连接引擎", Toast.LENGTH_SHORT).show()
                                } else {
                                    val granted = runCatching {
                                        remote.grantPermissions(context.packageName, 1 or 2 or 8 or 16 or 32)
                                    }.getOrDefault(0)
                                    Toast.makeText(
                                        context,
                                        if (granted != 0) "已授予后台权限（$granted）" else "授予失败，请手动到系统设置开启",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "屏保遮罩",
                            description = "后台挂机时遮住游戏画面（OLED 防烧屏、防偷看），点按遮罩可移出",
                            trailing = {
                                Switch(
                                    checked = screenSaverEnabled,
                                    onCheckedChange = {
                                        screenSaverEnabled = it
                                        SettingsRepository.setScreenSaverEnabled(context, it)
                                        if (!it) com.maafw.naruto.overlay.MaaFwScreenSaver.hide()
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "任务结束后关闭游戏",
                            description = "任务自然结束后关闭火影忍者",
                            trailing = {
                                Switch(
                                    checked = closeGameAfterTask,
                                    onCheckedChange = {
                                        closeGameAfterTask = it
                                        SettingsRepository.setCloseGameAfterTask(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "悬浮日志",
                            description = "在屏幕上显示运行日志（后续实现）",
                            trailing = {
                                Switch(
                                    checked = showFloatingLog,
                                    onCheckedChange = {
                                        showFloatingLog = it
                                        SettingsRepository.setShowFloatingLog(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "全屏额外信息",
                            description = "全屏虚拟屏幕上方显示运行状态/任务/分辨率（默认关闭）",
                            trailing = {
                                Switch(
                                    checked = fullscreenExtraInfo,
                                    onCheckedChange = {
                                        fullscreenExtraInfo = it
                                        SettingsRepository.setFullscreenExtraInfo(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        // 分辨率：上下布局，按钮放下方（不再挤在右侧）
                        SettingRow(
                            title = "虚拟屏分辨率",
                            description = "720p / 1080p / 自定义宽高与 DPI"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                SettingsRepository.RES_720P to "720p",
                                SettingsRepository.RES_1080P to "1080p",
                                SettingsRepository.RES_CUSTOM to "自定义"
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = resolution == value,
                                    onClick = {
                                        resolution = value
                                        SettingsRepository.setResolution(context, value)
                                        onResolutionChange(value)
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                        if (resolution == SettingsRepository.RES_CUSTOM) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PushTextField(
                                    label = "宽度",
                                    value = customW.toString(),
                                    onChange = { customW = it.toIntOrNull() ?: 1280; SettingsRepository.setCustomResolution(context, customW, customH, customDpi) },
                                    modifier = Modifier.weight(1f)
                                )
                                PushTextField(
                                    label = "高度",
                                    value = customH.toString(),
                                    onChange = { customH = it.toIntOrNull() ?: 720; SettingsRepository.setCustomResolution(context, customW, customH, customDpi) },
                                    modifier = Modifier.weight(1f)
                                )
                                PushTextField(
                                    label = "DPI",
                                    value = customDpi.toString(),
                                    onChange = { customDpi = it.toIntOrNull() ?: 160; SettingsRepository.setCustomResolution(context, customW, customH, customDpi) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        ListItemDivider()
                        SettingRow(
                            title = "运行模式",
                            description = "Shizuku（推荐）或 Root（需已 root）",
                            trailing = {
                                Row {
                                    listOf(
                                        SettingsRepository.RUN_MODE_SHIZUKU to "Shizuku",
                                        SettingsRepository.RUN_MODE_ROOT to "Root"
                                    ).forEach { (value, label) ->
                                        FilterChip(
                                            selected = runMode == value,
                                            onClick = {
                                                runMode = value
                                                SettingsRepository.setRunMode(context, value)
                                                onRunModeChange(value)
                                            },
                                            label = { Text(label) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                }
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "定时任务后台唤醒",
                            description = "锁屏/应用未启动时由系统精确唤醒并执行定时任务",
                            trailing = {
                                Switch(
                                    checked = scheduleWakeOn,
                                    onCheckedChange = {
                                        scheduleWakeOn = it
                                        SettingsRepository.setScheduleWakeOn(context, it)
                                        // 立即重注册所有策略，使开关生效
                                        val strategies = com.maafw.naruto.schedule.data.SchedulePolicyRepository(context).load()
                                        com.maafw.naruto.schedule.ScheduleHelper.rescheduleStrategies(context, strategies)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "引擎复用",
                            description = "开启可优化运行速度（跳过资源重载，任务启动更快）",
                            trailing = {
                                Switch(
                                    checked = engineReuseEnabled,
                                    onCheckedChange = {
                                        engineReuseEnabled = it
                                        SettingsRepository.setEngineReuseEnabled(context, it)
                                    }
                                )
                            }
                        )
                        // Root 守护进程：仅已授予 root 权限时显示（无论运行模式是 Shizuku 还是 Root）
                        if (rootGranted) {
                            ListItemDivider()
                            SettingRow(
                                title = "Root 守护进程",
                                description = "常驻 root 进程调度定时任务，App 被清理/强停后也能准时执行（比后台唤醒更彻底）",
                                trailing = {
                                    Switch(
                                        checked = rootDaemonEnabled,
                                        onCheckedChange = { on ->
                                            rootDaemonEnabled = on
                                            SettingsRepository.setRootDaemonEnabled(context, on)
                                            if (on) {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        val ok = com.maafw.naruto.root.RootDaemonController.start(context)
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(
                                                                context,
                                                                if (ok) "Root 守护进程已启动" else "Root 守护进程启动失败，请查看日志",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                }
                                            } else {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        com.maafw.naruto.root.RootDaemonController.stop()
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 通知设置（ settings_section_notification）
            item {
                CollapsibleSection(
                    title = "通知设置",
                    sectionKey = "settings_section_notification",
                ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "启用通知",
                            description = "任务完成或失败时发送通知",
                            trailing = {
                                Switch(
                                    checked = notificationEnabled,
                                    onCheckedChange = {
                                        notificationEnabled = it
                                        SettingsRepository.setNotificationEnabled(context, it)
                                        if (it) {
                                            val activity = context as? com.maafw.naruto.MainActivity
                                            activity?.requestNotificationPermission()
                                        }
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "任务开始通知",
                            description = "开始启动任务时发送通知（默认关闭）",
                            trailing = {
                                Switch(
                                    checked = notificationTaskStart,
                                    onCheckedChange = {
                                        notificationTaskStart = it
                                        SettingsRepository.setNotifyTaskStart(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "任务完成通知",
                            description = "任务完成时发送通知（含耗时，默认开启）",
                            trailing = {
                                Switch(
                                    checked = notificationTaskComplete,
                                    onCheckedChange = {
                                        notificationTaskComplete = it
                                        SettingsRepository.setNotifyTaskComplete(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "任务出错通知",
                            description = "任务执行出错时发送通知（默认开启）",
                            trailing = {
                                Switch(
                                    checked = notificationTaskError,
                                    onCheckedChange = {
                                        notificationTaskError = it
                                        SettingsRepository.setNotifyTaskError(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "服务异常通知",
                            description = "引擎/服务异常时发送通知（默认开启）",
                            trailing = {
                                Switch(
                                    checked = notificationServiceEvent,
                                    onCheckedChange = {
                                        notificationServiceEvent = it
                                        SettingsRepository.setNotifyServiceEvent(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "通知声音",
                            description = "通知时播放提示音",
                            trailing = {
                                Switch(
                                    checked = notificationSound,
                                    onCheckedChange = {
                                        notificationSound = it
                                        SettingsRepository.setNotificationSound(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "通知振动",
                            description = "通知时振动",
                            trailing = {
                                Switch(
                                    checked = notificationVibrate,
                                    onCheckedChange = {
                                        notificationVibrate = it
                                        SettingsRepository.setNotificationVibrate(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "第三方通知",
                            description = "任务完成/出错时推送（喵提醒/Server酱/钉钉/SMTP/Webhook）",
                            trailing = {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = { showNotificationSettings = true }
                        )
                    }
                }
            }

            // 日志（ settings_section_log）
            item {
                CollapsibleSection(
                    title = "运行日志",
                    sectionKey = "settings_section_log",
                ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "导出日志",
                            description = buildString {
                        append("复制最新 maafw.log 到 /storage/emulated/0/MaaFw日志（带时间戳）")
                        logUpdateTime?.let { append("\n日志更新于 $it") }
                    },
                            trailing = {
                                Icon(
                                    imageVector = Icons.Filled.FileDownload,
                                    contentDescription = "导出日志",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = { exportLog() }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "脚本页面日志",
                            description = "显示 / 隐藏脚本页的「日志」分页",
                            trailing = {
                                Switch(
                                    checked = scriptLogVisible,
                                    onCheckedChange = {
                                        scriptLogVisible = it
                                        SettingsRepository.setScriptLogVisible(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "日志复制按钮",
                            description = "显示 / 隐藏脚本页日志里的「复制日志」按钮（默认隐藏）",
                            trailing = {
                                Switch(
                                    checked = scriptLogCopyVisible,
                                    onCheckedChange = {
                                        scriptLogCopyVisible = it
                                        SettingsRepository.setScriptLogCopyVisible(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "脚本调试：触摸坐标采集",
                            description = "全屏预览触摸时采集坐标并导出 Maa 点击位置（target 格式，默认关闭）",
                            trailing = {
                                Switch(
                                    checked = scriptDebugTouch,
                                    onCheckedChange = {
                                        scriptDebugTouch = it
                                        SettingsRepository.setScriptDebugTouch(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "帧率显示（Debug）",
                            description = "虚拟屏预览左上角显示游戏真实帧率（FPS）与脚本识别频率（识别到卡顿立即归零）",
                            trailing = {
                                Switch(
                                    checked = fpsDebugEnabled,
                                    onCheckedChange = {
                                        fpsDebugEnabled = it
                                        SettingsRepository.setFpsDebugEnabled(context, it)
                                    }
                                )
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "显示触摸预览",
                            description = "在虚拟屏预览上显示脚本点击/长按/滑动轨迹标记",
                            trailing = {
                                Switch(
                                    checked = showTouchPreview,
                                    onCheckedChange = {
                                        showTouchPreview = it
                                        SettingsRepository.setShowTouchPreview(context, it)
                                    }
                                )
                            }
                        )
                        if (showTouchPreview) {
                            ListItemDivider()
                            SettingRow(
                                title = "触摸预览数量",
                                description = "最多同时显示的最近操作数（1-30）",
                                trailing = {
                                    OutlinedTextField(
                                        value = touchPreviewCount,
                                        onValueChange = { input ->
                                            touchPreviewCount = input.filter { it.isDigit() }
                                            SettingsRepository.setTouchPreviewCount(
                                                context, touchPreviewCount.toIntOrNull() ?: 1
                                            )
                                        },
                                        modifier = Modifier.width(90.dp),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true
                                    )
                                }
                            )
                        }
                        ListItemDivider()
                        SettingRow(
                            title = "清空日志",
                            description = "删除所有运行日志（含 maafw.log 引擎日志）",
                            onClick = {
                                synchronized(logBuffer) {
                                    logBuffer.clear()
                                    logs = emptyList()
                                }
                                // 同时删除磁盘上的引擎日志文件（含备份）
                                com.maafw.naruto.data.log.LogExporter.clearLogFiles(context)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(MaaDesignTokens.Spacing.sm))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 320.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            if (logs.isEmpty()) {
                                item {
                                    Text(
                                        "暂无日志",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                items(logs) { log ->
                                    Text(
                                        text = log,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

// 数据管理
                item {
                    CollapsibleSection(
                        title = "数据管理",
                        sectionKey = "settings_section_data",
                        initiallyExpanded = true,
                    ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "导出配置",
                            description = "导出全部设置或单个任务配置到 /storage/emulated/0/Maafw配置",
                            trailing = {
                                Icon(
                                    imageVector = Icons.Filled.FileDownload,
                                    contentDescription = "导出配置",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = { showExportDialog = true }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "导入配置",
                            description = "从 JSON 文件恢复全部设置",
                            trailing = {
                                Icon(
                                    imageVector = Icons.Filled.FileUpload,
                                    contentDescription = "导入配置",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = { importConfig() }
                        )
                    }
                }
            }

// 关于（ settings_section_about）
                item {
                    CollapsibleSection(
                        title = "关于",
                        sectionKey = "settings_section_about",
                        initiallyExpanded = true,
                    ) {
                    SettingsGroupCard {
                        SettingRow(
                            title = "应用版本",
                            description = com.maafw.naruto.BuildConfig.VERSION_NAME
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "MaaFramework",
                            description = "MAAFW 火影忍者手游 Android 适配版"
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "交流QQ群",
                            description = "801637524（点击复制群号）",
                            onClick = {
                                runCatching {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("QQ群", "801637524"))
                                    Toast.makeText(context, "QQ群号已复制：801637524", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "项目GitHub",
                            description = "github.com/ShrugYu（点击打开）",
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, "https://github.com/ShrugYu".toUri())
                                    )
                                }.onFailure {
                                    Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "更新日志",
                            description = "查看本版本改进内容",
                            onClick = { showChangelog = true }
                        )
                        ListItemDivider()
                        SettingRow(
                            title = "检查更新",
                            description = if (updateChecking) "正在检查…" else "检查 GitHub 最新版本并下载安装",
                            onClick = {
                                if (updateChecking) return@SettingRow
                                updateChecking = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        com.maafw.naruto.data.update.UpdateChecker.checkForUpdate()
                                    }
                                    updateChecking = false
                                    result.onSuccess { info ->
                                        val current = com.maafw.naruto.BuildConfig.VERSION_NAME
                                        if (info.version.trimStart('v', 'V') != current) {
                                            showUpdateInfo = info
                                        } else {
                                            Toast.makeText(context, "当前已是最新版本（$current）", Toast.LENGTH_SHORT).show()
                                        }
                                    }.onFailure {
                                        Toast.makeText(context, "检查更新失败：${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            item {
                Text(
                    "By.白川～",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showChangelog) {
            AlertDialog(
                onDismissRequest = { showChangelog = false },
                title = { Text("更新日志") },
                text = {
                    val scrollState = rememberScrollState()
                    Text(
                        "v${com.maafw.naruto.BuildConfig.VERSION_NAME} 稳定版\n\n" +
                            "守护与自愈\n" +
                            "· 引擎心跳看门狗：App 退出后引擎自动清理\n" +
                            "· 紧急清理：异常退出恢复游戏音量与虚拟屏\n" +
                            "· 运行期守护：游戏进程/显示漂移自动拉回\n" +
                            "· 引擎崩溃自动重连、双引擎进程收敛\n\n" +
                            "权限与兼容\n" +
                            "· 任务前自动授予游戏省电豁免与后台不受限\n" +
                            "· Shizuku 误切 Root 修复、连接状态机\n" +
                            "· 虚拟屏旋转适配（横屏设备）、控制器连接超时保护\n\n" +
                            "诊断与日志\n" +
                            "· 三层诊断日志（绑定链路/引擎启动/crash）\n" +
                            "· 日志 ZIP 打包并可一键分享到其他应用\n" +
                            "· 失败原因分级提示、日志颜色分级\n\n" +
                            "体验与生态\n" +
                            "· 任务进行中通知、开始请求不丢失\n" +
                            "· 外部 Intent 联动（Tasker/MacroDroid）\n" +
                            "· Shizuku 分级就绪引导、定时任务同步分辨率",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(scrollState)
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showChangelog = false }) { Text("知道了") }
                }
            )
        }

        // D2：检查更新结果对话框
        showUpdateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { showUpdateInfo = null },
                title = { Text("发现新版本 ${info.version}") },
                text = {
                    Text(
                        info.notes.ifBlank { "新版本已发布，点击下载并安装。" },
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val url = info.apkUrl
                        showUpdateInfo = null
                        if (url.isNullOrBlank()) {
                            Toast.makeText(context, "发布页未附带 APK，请到 GitHub 手动下载", Toast.LENGTH_LONG).show()
                            return@TextButton
                        }
                        Toast.makeText(context, "开始下载新版本…", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                com.maafw.naruto.data.update.UpdateChecker.downloadApk(context, url, "maafw_${info.version}.apk")
                            }
                            file.onSuccess { f ->
                                com.maafw.naruto.data.update.UpdateChecker.installApk(context, f)
                                Toast.makeText(context, "下载完成，请确认安装", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("下载并安装") }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateInfo = null }) { Text("取消") }
                }
            )
        }

        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("导出配置") },
                text = {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            "任务配置（含任务勾选和右侧设置）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        if (profiles.isEmpty()) {
                            Text("暂无自定义任务配置", modifier = Modifier.padding(8.dp))
                        } else {
                            profiles.forEach { name ->
                                TextButton(
                                    onClick = {
                                        showExportDialog = false
                                        exportProfileConfig(name)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("导出 [$name]")
                                }
                            }
                        }
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "全部设置",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        TextButton(
                            onClick = {
                                showExportDialog = false
                                exportConfig()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("导出全部设置（含通知、运行参数等）")
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
    }
}

private fun themeName(theme: String): String = when (theme) {
    SettingsRepository.THEME_LIGHT -> "浅色"
    SettingsRepository.THEME_DARK -> "深色"
    SettingsRepository.THEME_MONET -> "莫奈"
    else -> "跟随系统"
}

@Composable
private fun PushTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}