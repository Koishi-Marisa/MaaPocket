package com.maafw.naruto.schedule.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.schedule.ScheduleHelper
import com.maafw.naruto.schedule.data.SchedulePolicyRepository
import com.maafw.naruto.schedule.model.ScheduleStrategy
import com.maafw.naruto.schedule.model.ScheduleType
import com.maafw.naruto.schedule.model.TimeOfDay
import com.maafw.naruto.ui.components.MaaTopAppBar
import com.maafw.naruto.ui.components.SectionHeader
import com.maafw.naruto.ui.theme.MaaDesignTokens
import java.util.Calendar
import java.util.UUID

/**
 * 定时策略编辑页
 *  ScheduleEditView.kt + ScheduleEditViewModel.kt 合并：
 * - NavController -> onBack 回调
 * - java.time -> TimeOfDay / Calendar
 * - SegmentedButton -> 自定义分段选择（兼容 material3 1.1.x）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditView(
    strategyId: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { SchedulePolicyRepository(context) }

    // ---- 编辑状态 ----
    var name by remember { mutableStateOf("") }
    var scheduleType by remember { mutableStateOf(ScheduleType.FIXED_TIME) }
    var daysOfWeek by remember { mutableStateOf(setOf<Int>()) }
    var executionTimes by remember { mutableStateOf(listOf<TimeOfDay>()) }
    var startTimeMs by remember { mutableStateOf<Long?>(null) }
    var intervalDays by remember { mutableStateOf(0) }
    var intervalHours by remember { mutableStateOf(0) }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var forceStart by remember { mutableStateOf(false) }
    var autoSleepAfterTask by remember { mutableStateOf(false) }
    var closeGameAfterTask by remember { mutableStateOf(false) }
    var shizukuWakeApp by remember { mutableStateOf(false) }
    var rootWakeApp by remember { mutableStateOf(false) }
    var existingStrategy by remember { mutableStateOf<ScheduleStrategy?>(null) }
    var profiles by remember { mutableStateOf(ProfileManager.listProfiles(context).ifEmpty { listOf("default") }) }

    // 每次进入编辑页刷新配置列表（脚本页可能新建了配置）
    LaunchedEffect(Unit) {
        profiles = ProfileManager.listProfiles(context).ifEmpty { listOf("default") }
    }

    LaunchedEffect(strategyId) {
        if (strategyId != null) {
            val s = repository.getById(strategyId)
            if (s != null) {
                existingStrategy = s
                name = s.name
                scheduleType = s.scheduleType
                daysOfWeek = s.daysOfWeek
                executionTimes = s.executionTimes
                startTimeMs = s.startTimeMs
                intervalDays = (s.intervalMinutes ?: 0) / (24 * 60)
                intervalHours = ((s.intervalMinutes ?: 0) % (24 * 60)) / 60
                selectedProfileId = s.profileId
                forceStart = s.forceStart
                autoSleepAfterTask = s.autoSleepAfterTask
                closeGameAfterTask = s.closeGameAfterTask
                shizukuWakeApp = s.shizukuWakeApp
                rootWakeApp = s.rootWakeApp
                return@LaunchedEffect
            }
        }
        // 新建
        name = "定时任务 ${repository.load().size + 1}"
        selectedProfileId = profiles.firstOrNull()
    }

    var showTimePicker by remember { mutableStateOf(false) }
    var editingTime by remember { mutableStateOf<TimeOfDay?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
var needBatteryOptimization by remember { mutableStateOf(false) }
var needExactAlarm by remember { mutableStateOf(false) }
var needRootPermission by remember { mutableStateOf(false) }
var errorMessage by remember { mutableStateOf<String?>(null) }

    fun validateAndSave() {
        if (name.isBlank()) {
            errorMessage = "请输入策略名称"
            return
        }
        if (selectedProfileId == null) {
            errorMessage = "请选择任务配置"
            return
        }
        when (scheduleType) {
            ScheduleType.FIXED_TIME -> {
                if (daysOfWeek.isEmpty()) {
                    errorMessage = "请至少选择一天"
                    return
                }
                if (executionTimes.isEmpty()) {
                    errorMessage = "请至少添加一个执行时间"
                    return
                }
            }

            ScheduleType.INTERVAL -> {
                if (startTimeMs == null) {
                    errorMessage = "请选择首次执行时间"
                    return
                }
                val totalMinutes = intervalDays * 24 * 60 + intervalHours * 60
                if (totalMinutes < 60) {
                    errorMessage = "执行间隔不能小于 1 小时"
                    return
                }
            }
        }

        val intervalMinutes = if (scheduleType == ScheduleType.INTERVAL) {
            intervalDays * 24 * 60 + intervalHours * 60
        } else null

        val strategy = existingStrategy?.copy(
            name = name.trim(),
            scheduleType = scheduleType,
            daysOfWeek = daysOfWeek,
            executionTimes = executionTimes.sorted(),
            startTimeMs = startTimeMs,
            intervalMinutes = intervalMinutes,
            profileId = selectedProfileId ?: "default",
            forceStart = forceStart,
            autoSleepAfterTask = autoSleepAfterTask,
            closeGameAfterTask = closeGameAfterTask,
            shizukuWakeApp = shizukuWakeApp,
            rootWakeApp = rootWakeApp,
        ) ?: ScheduleStrategy(
            id = strategyId ?: UUID.randomUUID().toString(),
            name = name.trim(),
            enabled = true,
            scheduleType = scheduleType,
            daysOfWeek = daysOfWeek,
            executionTimes = executionTimes.sorted(),
            startTimeMs = startTimeMs,
            intervalMinutes = intervalMinutes,
            profileId = selectedProfileId ?: "default",
            forceStart = forceStart,
            autoSleepAfterTask = autoSleepAfterTask,
            closeGameAfterTask = closeGameAfterTask,
            shizukuWakeApp = shizukuWakeApp,
            rootWakeApp = rootWakeApp,
        )

        if (existingStrategy == null) {
            repository.add(strategy)
        } else {
            repository.update(strategy)
        }

        ScheduleHelper.cancelStrategy(context, strategy.id)
        ScheduleHelper.scheduleStrategy(context, strategy)

        // Root 唤醒开启但未授权 -> 并入权限对话框提示（不阻止保存，策略已注册）
        needRootPermission = rootWakeApp && !com.maafw.naruto.root.RootManager.isRootGranted()

        // 检查关键权限（）
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        needBatteryOptimization = !pm.isIgnoringBatteryOptimizations(context.packageName)
        needExactAlarm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !(context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()

        if (needBatteryOptimization || needExactAlarm || needRootPermission) {
            showPermissionDialog = true
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            MaaTopAppBar(
                title = if (strategyId == null) "新建定时策略" else "编辑定时策略",
                navigationIcon = Icons.Filled.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    TextButton(onClick = { validateAndSave() }) {
                        Text("保存")
                    }
                }
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = MaaDesignTokens.Spacing.listHorizontal,
                vertical = MaaDesignTokens.Spacing.sm
            )
        ) {
            item { SectionHeader("基本信息") }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("策略名称") },
                    placeholder = { Text("例如：每日日常") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Spacer(Modifier.height(MaaDesignTokens.Spacing.sectionGap))
                SectionHeader("调度类型")
            }
            item {
                // SegmentedButton 在 material3 1.1.x 不可用，用自定义分段（复制  视觉）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        ScheduleType.FIXED_TIME to "固定时间",
                        ScheduleType.INTERVAL to "间隔周期"
                    ).forEach { (type, label) ->
                        val selected = scheduleType == type
                        Surface(
                            onClick = { scheduleType = type },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(4.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            when (scheduleType) {
                ScheduleType.FIXED_TIME -> {
                    item {
                        Spacer(Modifier.height(MaaDesignTokens.Spacing.sectionGap))
                        SectionHeader("重复星期")
                    }
                    item {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val chipColors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                            val allSelected = (1..7).all { it in daysOfWeek }
                            FilterChip(
                                selected = allSelected,
                                onClick = {
                                    daysOfWeek = if (allSelected) emptySet() else (1..7).toSet()
                                },
                                label = { Text("每天") },
                                colors = chipColors
                            )
                            (1..7).forEach { day ->
                                FilterChip(
                                    selected = day in daysOfWeek,
                                    onClick = {
                                        daysOfWeek = if (day in daysOfWeek) daysOfWeek - day else daysOfWeek + day
                                    },
                                    label = { Text(scheduleDayChipLabel(day)) },
                                    colors = chipColors
                                )
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(MaaDesignTokens.Spacing.sectionGap))
                        SectionHeader("执行时间")
                    }
                    item {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            executionTimes.forEach { time ->
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        editingTime = time
                                        showTimePicker = true
                                    },
                                    label = { Text(time.toString()) },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { executionTimes = executionTimes - time },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "删除",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                )
                            }
                            AssistChip(
                                onClick = {
                                    editingTime = null
                                    showTimePicker = true
                                },
                                label = { Text("添加时间") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                ScheduleType.INTERVAL -> {
                    item {
                        Spacer(Modifier.height(MaaDesignTokens.Spacing.sectionGap))
                        SectionHeader("首次执行时间")
                    }
                    item {
                        var showDatePicker by remember { mutableStateOf(false) }
                        var showStartTimePicker by remember { mutableStateOf(false) }
                        var pendingDateMs by remember { mutableStateOf<Long?>(null) }

                        val displayText = startTimeMs?.let { ms ->
                            val cal = Calendar.getInstance().apply { timeInMillis = ms }
                            String.format("%04d-%02d-%02d %02d:%02d",
                                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                        } ?: "点击选择"

                        OutlinedTextField(
                            value = displayText,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("首次执行时间") },
                            modifier = Modifier.fillMaxWidth(),
                            interactionSource = remember { MutableInteractionSource() }.also { source ->
                                LaunchedEffect(source) {
                                    source.interactions.collect { interaction ->
                                        if (interaction is PressInteraction.Release) {
                                            showDatePicker = true
                                        }
                                    }
                                }
                            }
                        )

                        if (showDatePicker) {
                            val datePickerState = rememberDatePickerState(
                                initialSelectedDateMillis = startTimeMs ?: System.currentTimeMillis()
                            )
                            DatePickerDialog(
                                onDismissRequest = { showDatePicker = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        pendingDateMs = datePickerState.selectedDateMillis
                                        showDatePicker = false
                                        showStartTimePicker = true
                                    }) { Text("下一步") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                                }
                            ) {
                                DatePicker(state = datePickerState)
                            }
                        }

                        if (showStartTimePicker) {
                            val existing = startTimeMs?.let { ms ->
                                val cal = Calendar.getInstance().apply { timeInMillis = ms }
                                TimeOfDay(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                            }
                            TimePickerDialog(
                                initialTime = existing,
                                onDismiss = { showStartTimePicker = false },
                                onConfirm = { time ->
                                    val dateMs = pendingDateMs ?: return@TimePickerDialog
                                    val cal = Calendar.getInstance().apply {
                                        timeInMillis = dateMs
                                        set(Calendar.HOUR_OF_DAY, time.hour)
                                        set(Calendar.MINUTE, time.minute)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    startTimeMs = cal.timeInMillis
                                    showStartTimePicker = false
                                }
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(MaaDesignTokens.Spacing.sectionGap))
                        SectionHeader("执行间隔")
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = if (intervalDays > 0) intervalDays.toString() else "",
                                onValueChange = { intervalDays = (it.toIntOrNull() ?: 0).coerceAtLeast(0) },
                                label = { Text("天") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(80.dp)
                            )
                            OutlinedTextField(
                                value = if (intervalHours > 0) intervalHours.toString() else "",
                                onValueChange = { intervalHours = (it.toIntOrNull() ?: 0).coerceIn(0, 23) },
                                label = { Text("小时") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(80.dp)
                            )
                            val totalMinutes = intervalDays * 24 * 60 + intervalHours * 60
                            if (totalMinutes > 0) {
                                Text(
                                    text = "共 ${totalMinutes / 60} 小时",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(MaaDesignTokens.Spacing.sectionGap))
                SectionHeader("任务配置")
            }
            item {
                if (profiles.isEmpty()) {
                    Text(
                        "还没有任务配置，请先在脚本页创建",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        profiles.forEach { profileName ->
                            FilterChip(
                                selected = profileName == selectedProfileId,
                                onClick = { selectedProfileId = profileName },
                                label = { Text(profileName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                    // 显示选中配置的已启用任务摘要（）
                    val enabledTasks = selectedProfileId?.let { pid ->
                        ProfileManager.load(context, pid)?.tasks?.filter { it.enabled }
                            ?.mapNotNull { pt -> pt.entry }
                            ?.joinToString("、")
                    }
                    if (!enabledTasks.isNullOrEmpty()) {
                        Text(
                            text = "已启用任务：$enabledTasks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MaaDesignTokens.Spacing.sm)
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(MaaDesignTokens.Spacing.sectionGap))
                SectionHeader("高级设置")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("强制开始", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = forceStart,
                        onCheckedChange = { forceStart = it }
                    )
                }
                Text(
                    "触发时若已有任务运行，先停止再启动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = MaaDesignTokens.Spacing.sm)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("任务结束后自动熄屏", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoSleepAfterTask,
                        onCheckedChange = { autoSleepAfterTask = it }
                    )
                }
                Text(
                    "任务自然结束后关闭虚拟屏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = MaaDesignTokens.Spacing.sm)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("任务结束后关闭游戏", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = closeGameAfterTask,
                        onCheckedChange = { closeGameAfterTask = it }
                    )
                }
                Text(
                    "任务自然结束后关闭火影忍者游戏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = MaaDesignTokens.Spacing.sm)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Shizuku 唤醒应用", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = shizukuWakeApp,
                        onCheckedChange = { on ->
                            shizukuWakeApp = on
                            if (on) rootWakeApp = false // 与 Root 唤醒互斥
                        }
                    )
                }
                Text(
                    "到时间后即使应用未在后台，也尝试通过 Shizuku 唤醒并运行任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = MaaDesignTokens.Spacing.sm)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Root 唤醒应用", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = rootWakeApp,
                        onCheckedChange = { on ->
                            rootWakeApp = on
                            if (on) shizukuWakeApp = false // 与 Shizuku 唤醒互斥
                        }
                    )
                }
                Text(
                    "到时间后通过 Root 权限把应用强拉前台并运行任务（引擎也走 Root，不依赖 Shizuku；需已 Root）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = MaaDesignTokens.Spacing.sm)
                )
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("确定") }
            }
        )
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialTime = editingTime,
            onDismiss = { showTimePicker = false },
            onConfirm = { time ->
                val old = editingTime
                if (old != null) {
                    executionTimes = executionTimes.map { if (it == old) time else it }.distinct().sorted()
                } else {
                    if (time !in executionTimes) executionTimes = (executionTimes + time).sorted()
                }
                showTimePicker = false
            }
        )
    }

    if (showPermissionDialog) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = {
                showPermissionDialog = false
                onBack()
            },
            title = { Text("需要权限设置") },
            text = {
                Text(
                    buildString {
                        if (needBatteryOptimization) append("• 请允许忽略电池优化，否则定时任务可能被系统杀死\n")
                        if (needExactAlarm) append("• 请授予精确闹钟权限，否则定时任务无法准时触发\n")
                        if (needRootPermission) append("• 已开启 Root 唤醒但未授予 Root 权限，请在主页「权限检查」或设置页授权，否则任务触发时无法通过 Root 拉起应用")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (needBatteryOptimization) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    "package:${context.packageName}".toUri()
                                )
                            )
                        }
                    } else if (needExactAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        }
                    }
                    showPermissionDialog = false
                    onBack()
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    onBack()
                }) { Text("以后再说") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialTime: TimeOfDay? = null,
    onDismiss: () -> Unit,
    onConfirm: (TimeOfDay) -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime?.hour ?: 0,
        initialMinute = initialTime?.minute ?: 0
    )
    val configuration = LocalConfiguration.current
    var showDial by remember { mutableStateOf(configuration.screenHeightDp >= 400) }

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "选择时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                )
                if (showDial) {
                    TimePicker(state = timePickerState)
                } else {
                    TimeInput(state = timePickerState)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showDial = !showDial }) {
                        Text(if (showDial) "键盘输入" else "表盘选择")
                    }
                    Row {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(onClick = {
                            onConfirm(TimeOfDay(timePickerState.hour, timePickerState.minute))
                        }) { Text("确定") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}