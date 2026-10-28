package com.maafw.naruto.schedule.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.maafw.naruto.schedule.ScheduleHelper
import com.maafw.naruto.schedule.data.ScheduleConfigManager
import com.maafw.naruto.schedule.data.SchedulePolicyRepository
import com.maafw.naruto.schedule.model.ExecutionResult
import com.maafw.naruto.schedule.model.ScheduleStrategy
import com.maafw.naruto.ui.components.GuideStep
import com.maafw.naruto.ui.components.MaaTopAppBar
import com.maafw.naruto.ui.components.SpotlightGuide
import com.maafw.naruto.ui.components.rememberGuideTarget
import com.maafw.naruto.ui.theme.MaaDesignTokens
/**
 * 定时任务列表页
 *  ScheduleListView.kt：
 * - NavController -> 回调 onBack/onEdit/onShowTriggerLog
 * - koinViewModel -> 直接持有 SchedulePolicyRepository
 * - 保留：策略卡片（下次触发/上次结果）、删除确认、自启动引导、空状态
 */
@Composable
fun ScheduleListView(
    onBack: () -> Unit = {},
    onEditStrategy: (String?) -> Unit,
    guideController: com.maafw.naruto.ui.components.GuideController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { SchedulePolicyRepository(context) }
    var strategies by remember { mutableStateOf(repository.load()) }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    var showAutoStartGuide by remember { mutableStateOf(false) }

    // 导入/导出 / 定位 状态
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var highlightId by remember { mutableStateOf<String?>(null) }

    // 导出：写入系统文件选择器
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(ScheduleConfigManager.export(context).toByteArray())
                    }
                    true
                }.getOrDefault(false)
                Toast.makeText(context, if (ok) "定时任务配置已导出" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 导入：读取系统文件选择器选中的 JSON
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                val result = if (json != null) ScheduleConfigManager.import(context, json)
                else Result.failure(IllegalStateException("无法读取文件"))
                result.onSuccess { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
                // 导入后刷新列表 + 重注册所有策略闹钟
                strategies = repository.load()
                ScheduleHelper.rescheduleStrategies(context, repository.load())
            }
        }
    }

    /** 定位到下次触发最近的策略（滚动 + 高亮） */
    fun locateNextStrategy() {
        val next = strategies
            .filter { it.enabled }
            .mapNotNull { s -> ScheduleHelper.computeNextTriggerMs(s, 0L)?.let { s to it } }
            .minByOrNull { it.second }
        val targetId = next?.first?.id
        val idx = strategies.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            scope.launch {
                listState.animateScrollToItem(idx)
                highlightId = targetId
                delay(2000)
                highlightId = null
            }
        } else {
            Toast.makeText(context, "没有即将触发的定时任务", Toast.LENGTH_SHORT).show()
        }
    }

    fun refresh() {
        strategies = repository.load()
    }

    // ---- 首次操作引导（聚光灯，全局渲染避免底栏遮挡/偏移） ----
    val guideTargets = guideController.targets
    val guideSteps = remember {
        listOf(
            GuideStep("list", "定时策略列表", "这里展示所有定时任务：点击卡片可编辑详情，右侧开关可启用/停用，垃圾桶图标删除。"),
            GuideStep("fab", "新建定时策略", "点击右下角 + 新建一个定时任务：可设置固定时间（星期+时刻）或间隔周期，并关联任务配置。"),
            GuideStep("card", "下次执行与结果", "启用的策略会显示「下次执行」时间；执行后显示「上次结果」，一目了然。")
        )
    }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("maa_guide", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("schedule_guided", false)) {
            guideController.onFinished = {
                context.getSharedPreferences("maa_guide", Context.MODE_PRIVATE)
                    .edit().putBoolean("schedule_guided", true).apply()
            }
            delay(400)
            guideController.start(guideSteps)
        }
    }

    // 首次有策略时检查是否需要自启动引导（）
    androidx.compose.runtime.LaunchedEffect(strategies.isNotEmpty()) {
        if (strategies.isNotEmpty() && AutoStartHelper.isKnownRestrictiveManufacturer()) {
            val prefs = context.getSharedPreferences("schedule_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("autostart_guided", false)) {
                val intent = AutoStartHelper.getAutoStartIntent(context)
                if (intent != null) {
                    showAutoStartGuide = true
                    prefs.edit { putBoolean("autostart_guided", true) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            MaaTopAppBar(
                title = "定时任务",
                navigationIcon = Icons.Filled.ArrowBack,
                onNavigationClick = onBack
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(end = 16.dp, bottom = 16.dp)
            ) {
                // 导入定时任务配置（与新建同色系，统一小圆钮风格）
                FloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = "导入配置", modifier = Modifier.size(20.dp))
                }
                // 导出定时任务配置
                FloatingActionButton(
                    onClick = {
                        exportLauncher.launch("maa_schedule_${System.currentTimeMillis()}.json")
                    },
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = "导出配置", modifier = Modifier.size(20.dp))
                }
                // 定位下次触发的任务
                FloatingActionButton(
                    onClick = { locateNextStrategy() },
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "定位任务", modifier = Modifier.size(20.dp))
                }
                // 新建定时任务（主操作，默认 FAB 尺寸，与其余按钮同色系）
                FloatingActionButton(
                    onClick = { onEditStrategy(null) },
                    modifier = Modifier.then(rememberGuideTarget("fab", guideTargets)),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新建策略")
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (strategies.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "还没有定时策略",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "点击右下角 + 新建定时任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .then(rememberGuideTarget("list", guideTargets)),
                state = listState,
                contentPadding = PaddingValues(horizontal = MaaDesignTokens.Spacing.listHorizontal, vertical = MaaDesignTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(strategies, key = { it.id }) { strategy ->
                    StrategyCard(
                        strategy = strategy,
                        nextTrigger = ScheduleHelper.formatNextTriggerForDisplay(strategy),
                        highlighted = strategy.id == highlightId,
                        onToggleEnabled = { enabled ->
                            repository.setEnabled(strategy.id, enabled)
                            if (enabled) {
                                ScheduleHelper.scheduleStrategy(context, repository.getById(strategy.id) ?: strategy)
                            } else {
                                ScheduleHelper.cancelStrategy(context, strategy.id)
                            }
                            refresh()
                        },
                        onClick = { onEditStrategy(strategy.id) },
                        onDelete = { deleteConfirmId = strategy.id }
                    )
                }
            }
        }

        if (deleteConfirmId != null) {
            AlertDialog(
                onDismissRequest = { deleteConfirmId = null },
                title = { Text("删除定时策略") },
                text = { Text("确定要删除这个定时策略吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        val id = deleteConfirmId
                        if (id != null) {
                            ScheduleHelper.cancelStrategy(context, id)
                            repository.remove(id)
                        }
                        deleteConfirmId = null
                        refresh()
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirmId = null }) { Text("取消") }
                }
            )
        }

        if (showAutoStartGuide) {
            AlertDialog(
                onDismissRequest = { showAutoStartGuide = false },
                title = { Text("开启自启动权限") },
                text = { Text("为了定时任务能在后台可靠触发，建议允许本应用自启动。") },
                confirmButton = {
                    TextButton(onClick = {
                        AutoStartHelper.getAutoStartIntent(context)?.let {
                            runCatching { context.startActivity(it) }
                        }
                        showAutoStartGuide = false
                    }) { Text("去设置") }
                },
                dismissButton = {
                    TextButton(onClick = { showAutoStartGuide = false }) { Text("以后再说") }
                }
            )
        }
    }

    // 首次操作引导（聚光灯）已提升到 MainActivity 全局渲染，避免底栏遮挡与坐标偏移
}

@Composable
private fun StrategyCard(
    strategy: ScheduleStrategy,
    nextTrigger: String?,
    highlighted: Boolean = false,
    onToggleEnabled: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strategy.name, style = MaterialTheme.typography.titleMedium)

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = localizedScheduleStrategySummary(strategy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "配置：${strategy.profileId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (strategy.enabled && nextTrigger != null) {
                    Text(
                        text = "下次执行：$nextTrigger",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                val lastResultText = strategy.lastResult?.let {
                    formatExecutionResult(it, strategy.lastResultMessage)
                }
                if (lastResultText != null) {
                    Text(
                        text = lastResultText,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = executionResultColor(
                            strategy.lastResult,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.error,
                            MaterialTheme.colorScheme.tertiary,
                        ),
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = strategy.enabled,
                onCheckedChange = onToggleEnabled
            )
        }
    }
}

@Composable
private fun formatExecutionResult(result: ExecutionResult, message: String?): String {
    val label = when (result) {
        ExecutionResult.STARTED,
        ExecutionResult.FAILED_VALIDATION,
        ExecutionResult.FAILED_START,
        ExecutionResult.FAILED_UI_LAUNCH,
        ExecutionResult.SKIPPED_BUSY,
        ExecutionResult.CANCELLED -> {
            "上次结果：" + scheduleExecutionResultLabel(result)
        }

        else -> return ""
    }
    return if (message.isNullOrBlank()) label else "$label · $message"
}

private fun executionResultColor(
    result: ExecutionResult?,
    successColor: Color,
    errorColor: Color,
    warningColor: Color,
): Color {
    return when (result) {
        ExecutionResult.STARTED -> successColor
        ExecutionResult.SKIPPED_BUSY,
        ExecutionResult.CANCELLED -> warningColor

        ExecutionResult.FAILED_VALIDATION,
        ExecutionResult.FAILED_START,
        ExecutionResult.FAILED_UI_LAUNCH -> errorColor

        else -> Color.Unspecified
    }
}