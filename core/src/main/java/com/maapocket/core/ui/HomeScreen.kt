package com.maapocket.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.maapocket.core.pi.PiController
import com.maapocket.core.pi.PiOption
import com.maapocket.core.pi.PiRepository
import com.maapocket.core.pi.PiSelection
import com.maapocket.core.pi.PiTask
import com.maapocket.core.privilege.PrivilegeAvailability
import com.maapocket.core.privilege.PrivilegeKind
import com.maapocket.core.privilege.PrivilegeState
import com.maapocket.core.run.MaaRunController

/** Shizuku 官方 App 的包名；只在用户点了「打开 Shizuku」时才用到。 */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/**
 * 整个 App 的根。做成独立函数而不是直接写在 setContent 里，
 * 是为了以后加导航时不用动 MainActivity。
 */
@Composable
fun AppRoot(viewModel: MaaPocketViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    // 特权进程是进程级资源。Activity 真正销毁时（不是配置变更 —— manifest 已经用
    // configChanges 拦住了）必须收掉，否则下一次启动会撞上残留的 launcher 进程。
    DisposableEffect(Unit) {
        onDispose { viewModel.shutdown() }
    }
    HomeScreen(viewModel = viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MaaPocketViewModel) {
    val state by viewModel.state.collectAsState()
    val repository by viewModel.repository.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val previewEnabled by viewModel.previewEnabled.collectAsState()
    val selectedTasks by viewModel.selectedTasks.collectAsState()
    val optionValues by viewModel.optionValues.collectAsState()
    val controllerName by viewModel.controllerName.collectAsState()
    val resourceName by viewModel.resourceName.collectAsState()
    val kind by viewModel.privilegeKind.collectAsState()
    val displayWidth by viewModel.displayWidth.collectAsState()
    val displayHeight by viewModel.displayHeight.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val uiError by viewModel.uiError.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("MaaPocket") }) },
    ) { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrivilegeCard(
                state = state,
                kind = kind,
                onSelectKind = viewModel::selectPrivilegeKind,
                onRefresh = viewModel::refreshPrivilegeOptions,
            )

            PackCard(
                state = state,
                repository = repository,
                plan = plan,
                controllerName = controllerName,
                resourceName = resourceName,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                onExtract = viewModel::extractPack,
                onSelectController = viewModel::selectController,
                onSelectResource = viewModel::selectResource,
                onDisplayWidth = viewModel::setDisplayWidth,
                onDisplayHeight = viewModel::setDisplayHeight,
            )

            TaskCard(
                repository = repository,
                selectedTasks = selectedTasks,
                optionValues = optionValues,
                onCheck = viewModel::setTaskChecked,
                onSelectAll = viewModel::selectAllTasks,
                onClearAll = viewModel::clearAllTasks,
                onOptionValue = viewModel::setOptionValue,
                optionDisplayValue = viewModel::optionDisplayValue,
                optionTrueCase = viewModel::optionTrueCase,
            )

            ActionCard(
                state = state,
                plan = plan,
                uiError = uiError,
                onPrepare = viewModel::prepare,
                onRun = viewModel::run,
                onStop = viewModel::requestStop,
            )

            PreviewCard(
                enabled = previewEnabled,
                onToggle = viewModel::setPreviewEnabled,
                preview = preview,
            )

            LogCard(
                logs = logs,
                onClear = viewModel::clearLogs,
                logsText = viewModel::logsText,
            )
        }
    }
}

// ===================================================================== 权限

@Composable
private fun PrivilegeCard(
    state: MaaRunController.State,
    kind: PrivilegeKind,
    onSelectKind: (PrivilegeKind) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val privilege = state.privilege

    SectionCard(title = "权限") {
        Text(
            text = "当前：${privilege.kind?.label ?: "未启动"} · " +
                "${privilege.state.name} · uid=${privilege.uid} · " +
                "availability=${describeAvailability(privilege.availability)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        privilege.detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (state.privilegeOptions.isEmpty()) {
            Text("尚未探测到任何提权后端", style = MaterialTheme.typography.bodySmall)
        }
        state.privilegeOptions.forEach { (optionKind, availability) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = optionKind == kind,
                    onClick = { onSelectKind(optionKind) },
                )
                Column(Modifier.weight(1f)) {
                    Text(optionKind.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = describeAvailability(availability),
                        style = MaterialTheme.typography.bodySmall,
                        color = availabilityColor(availability),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) { Text("刷新") }
            if (privilege.kind == PrivilegeKind.SHIZUKU && privilege.state == PrivilegeState.PERMISSION_REQUIRED) {
                OutlinedButton(onClick = { openShizuku(context) }) { Text("打开 Shizuku") }
            }
        }
    }
}

@Composable
private fun availabilityColor(availability: PrivilegeAvailability): Color = when (availability) {
    is PrivilegeAvailability.Ready -> MaterialTheme.colorScheme.primary
    is PrivilegeAvailability.PermissionRequired -> MaterialTheme.colorScheme.tertiary
    is PrivilegeAvailability.Denied -> MaterialTheme.colorScheme.error
    is PrivilegeAvailability.Unsupported -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun describeAvailability(availability: PrivilegeAvailability): String = when (availability) {
    is PrivilegeAvailability.Ready -> "可用"
    is PrivilegeAvailability.PermissionRequired -> "需要授权（在对应的管理器里允许本应用）"
    is PrivilegeAvailability.Unsupported -> "设备不支持 / 未安装"
    is PrivilegeAvailability.Denied -> "被拒绝：${availability.reason}"
    else -> "未知"
}

private fun openShizuku(context: Context) {
    val intent = runCatching { context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) }.getOrNull()
    if (intent == null) {
        toast(context, "未安装 Shizuku")
        return
    }
    // 从非 Activity 上下文启动需要 NEW_TASK；LocalContext 通常就是 Activity，但这里不假设。
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { toast(context, "无法打开 Shizuku：${it.javaClass.simpleName}") }
}

// ==================================================================== 资源包

@Composable
private fun PackCard(
    state: MaaRunController.State,
    repository: PiRepository?,
    plan: PiSelection.PiRunPlan?,
    controllerName: String,
    resourceName: String,
    displayWidth: String,
    displayHeight: String,
    onExtract: (Boolean) -> Unit,
    onSelectController: (String) -> Unit,
    onSelectResource: (String) -> Unit,
    onDisplayWidth: (String) -> Unit,
    onDisplayHeight: (String) -> Unit,
) {
    val extracting = state.phase == MaaRunController.Phase.EXTRACTING

    SectionCard(title = "资源包") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.packLabel ?: "未解包", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = state.packStamp ?: "-",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (extracting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        Text(
            text = state.piRoot?.absolutePath ?: "（等待解包）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onExtract(false) }, enabled = !state.busy) { Text("解包") }
            OutlinedButton(onClick = { onExtract(true) }, enabled = !state.busy) { Text("重新解包（force）") }
        }

        if (repository == null) {
            Text("解包后这里会出现 controller / resource 选择。", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }

        HorizontalDivider()

        // controller：设备上只认 Adb 类型，但包里的原值都列出来，用户能看到差异。
        DropdownField(
            label = "Controller",
            value = controllerName,
            options = repository.controllers().map { it.name to describeController(repository, it) },
            enabled = !state.busy,
            onSelect = onSelectController,
        )

        DropdownField(
            label = "Resource",
            value = resourceName,
            options = repository.resources().map { it.name to repositoryLabel(repository, it.name, it.label) },
            enabled = !state.busy,
            onSelect = onSelectResource,
        )

        HorizontalDivider()

        // 显示尺寸：优先展示 PiRunPlan 从 PI 里读出来的建议值，用户可空着走设备默认。
        // 先取出来避免依赖 `plan?.x != null` 的 smart cast —— Kotlin 不认这种安全调用推断。
        val shortSide = plan?.displayShortSide
        val longSide = plan?.displayLongSide
        Text(
            text = "资源包建议尺寸：" + if (shortSide != null || longSide != null) {
                "${shortSide ?: "?"} x ${longSide ?: "?"}"
            } else {
                "未知（按设备默认）"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = displayWidth,
                onValueChange = onDisplayWidth,
                modifier = Modifier.weight(1f),
                label = { Text("宽（可空）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = displayHeight,
                onValueChange = onDisplayHeight,
                modifier = Modifier.weight(1f),
                label = { Text("高（可空）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Text(
            text = "留空表示用特权进程探测到的设备分辨率。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun describeController(repo: PiRepository, controller: PiController): String {
    // 设备上只有 Adb 类型能跑；别的类型照原样列出来并标上 type，免得用户选了之后才发现不支持。
    val label = repositoryLabel(repo, controller.name, controller.label)
    return if (controller.type == PiRepository.ON_DEVICE_PI_TYPE) label else "$label [${controller.type}]"
}

private fun repositoryLabel(repo: PiRepository, name: String, label: String?): String {
    // PI 的 label 是 i18n key（形如 $xxx），resolve 会翻成当前语言；翻不出就退回 key 本身。
    val resolved = repo.resolve(label)
    return when {
        resolved.isNotEmpty() -> resolved
        label != null -> label
        else -> name
    }
}

// ====================================================================== 任务

@Composable
private fun TaskCard(
    repository: PiRepository?,
    selectedTasks: Set<String>,
    optionValues: Map<String, String>,
    onCheck: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onOptionValue: (String, String) -> Unit,
    optionDisplayValue: (String, PiOption) -> String,
    optionTrueCase: (PiOption) -> String?,
) {
    SectionCard(title = "任务") {
        if (repository == null) {
            Text("先解包资源包。", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }

        val defs = repository.optionDefs()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${selectedTasks.size} 项", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onSelectAll) { Text("全选") }
            TextButton(onClick = onClearAll) { Text("全不选") }
        }
        HorizontalDivider()

        repository.tasksByGroup().forEach { (groupName, tasks) ->
            Text(
                text = groupLabel(repository, groupName),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            tasks.forEach { task ->
                TaskRow(
                    repository = repository,
                    task = task,
                    checked = task.name in selectedTasks,
                    defs = defs,
                    optionValues = optionValues,
                    onCheck = onCheck,
                    onOptionValue = onOptionValue,
                    optionDisplayValue = optionDisplayValue,
                    optionTrueCase = optionTrueCase,
                )
            }
        }
    }
}

private fun groupLabel(repo: PiRepository, groupName: String?): String {
    if (groupName == null) return "（未分组）"
    val group = repo.groups().firstOrNull { it.name == groupName }
    return repositoryLabel(repo, groupName, group?.label)
}

@Composable
private fun TaskRow(
    repository: PiRepository,
    task: PiTask,
    checked: Boolean,
    defs: Map<String, PiOption>,
    optionValues: Map<String, String>,
    onCheck: (String, Boolean) -> Unit,
    onOptionValue: (String, String) -> Unit,
    optionDisplayValue: (String, PiOption) -> String,
    optionTrueCase: (PiOption) -> String?,
) {
    // 只渲染 task 自己声明的 option；case 里的子选项交给 core 的 applyOption 递归处理，
    // UI 不展开是为了避免同一个 override 被渲染两次、用户看到两份互相打架的取值。
    val optionNames = task.option.filter { defs.containsKey(it) }
    var expanded by remember(task.name) { mutableStateOf(false) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onCheck(task.name, it) })
            Column(Modifier.weight(1f)) {
                Text(
                    text = repositoryLabel(repository, task.name, task.label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val description = task.description ?: task.desc
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = repository.resolve(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (optionNames.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起选项" else "选项 ${optionNames.size}")
                }
            }
        }

        if (expanded && optionNames.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                optionNames.forEach { name ->
                    val def = defs.getValue(name)
                    OptionEditor(
                        repository = repository,
                        name = name,
                        def = def,
                        value = optionDisplayValue(name, def),
                        onValue = { onOptionValue(name, it) },
                        notEdited = name !in optionValues,
                        optionTrueCase = optionTrueCase,
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionEditor(
    repository: PiRepository,
    name: String,
    def: PiOption,
    value: String,
    onValue: (String) -> Unit,
    notEdited: Boolean,
    optionTrueCase: (PiOption) -> String?,
) {
    val title = repositoryLabel(repository, name, def.label)
    val trueCase = optionTrueCase(def)

    Column {
        when {
            trueCase != null -> {
                // 两 case 的 switch：按「哪个 case 是真」渲染，而不是按 case 顺序。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = value.equals(trueCase, ignoreCase = true),
                        onCheckedChange = { checked ->
                            val other = def.cases.firstOrNull { it.name != trueCase }?.name ?: trueCase
                            onValue(if (checked) trueCase else other)
                        },
                    )
                }
            }

            def.cases.isNotEmpty() -> {
                DropdownField(
                    label = title,
                    value = value,
                    options = def.cases.map {
                        it.name to repositoryLabel(repository, it.name, it.label)
                    },
                    onSelect = onValue,
                )
            }

            else -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValue,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(title) },
                    singleLine = true,
                    // PI 的 input 可以标 password，别把用户填的密钥明文显示在屏幕上。
                    visualTransformation = if (def.password) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                )
            }
        }

        def.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = repository.resolve(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (notEdited) {
            Text(
                text = "使用资源包默认值：${def.defaultCase ?: def.cases.firstOrNull()?.name ?: "（空）"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ==================================================================== 操作栏

@Composable
private fun ActionCard(
    state: MaaRunController.State,
    plan: PiSelection.PiRunPlan?,
    uiError: String?,
    onPrepare: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard(title = "运行") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPrepare, enabled = !state.busy) { Text("准备") }
            Button(
                onClick = onRun,
                // core 自己会检验，但按钮先在 UI 上拦住，避免用户点了没反应还不知道为什么。
                enabled = !state.busy && state.phase == MaaRunController.Phase.IDLE &&
                    state.controllerReady && state.resourceLoaded,
            ) { Text("开始") }
            OutlinedButton(
                onClick = onStop,
                enabled = state.phase == MaaRunController.Phase.RUNNING,
            ) { Text("停止") }
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        Text(describePhase(state.phase), style = MaterialTheme.typography.bodyMedium)

        if (state.phase == MaaRunController.Phase.RUNNING) {
            val fraction = if (state.taskCount > 0) {
                (state.taskIndex.toFloat() / state.taskCount.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                text = "${state.taskIndex}/${state.taskCount}  ${state.currentTask ?: ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        plan?.let {
            Text(PiSelection.summarize(it), style = MaterialTheme.typography.bodySmall)
            if (it.warnings.isNotEmpty()) {
                it.warnings.forEach { warning ->
                    Text(
                        text = "⚠ $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        val error = state.lastError ?: uiError
        if (error != null) {
            ErrorCard(error)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun describePhase(phase: MaaRunController.Phase): String = when (phase) {
    MaaRunController.Phase.IDLE -> "空闲"
    MaaRunController.Phase.EXTRACTING -> "正在解包资源包…"
    MaaRunController.Phase.PREPARING -> "正在拉起特权进程并加载引擎…"
    MaaRunController.Phase.RUNNING -> "正在运行任务"
    MaaRunController.Phase.STOPPING -> "正在停止…"
    MaaRunController.Phase.DONE -> "已完成"
    MaaRunController.Phase.FAILED -> "失败"
    else -> phase.name
}

// ====================================================================== 预览

@Composable
private fun PreviewCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    preview: android.graphics.Bitmap?,
) {
    SectionCard(title = "预览") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开启预览", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !enabled -> Text("预览已关闭", color = Color.White, style = MaterialTheme.typography.bodySmall)
                // 分支写成 `preview != null`（而不是把 Image 放 else）：这样 non-null 是分支条件本身
                // 带来的 smart cast，不依赖编译器对 else 的负向推断。
                preview != null -> Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "MaaFramework 预览帧",
                    modifier = Modifier.fillMaxSize(),
                    // Fit 而不是 FillBounds：截图画面对不上显示尺寸时，宁可留黑边也不要拉伸。
                    contentScale = ContentScale.Fit,
                )

                else -> Text("等待画面…", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            text = "关闭只停止本地渲染 —— core 没有暴露远端截图开关。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ====================================================================== 日志

@Composable
private fun LogCard(
    logs: List<String>,
    onClear: () -> Unit,
    logsText: () -> String,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(true) }

    SectionCard(
        title = "日志（${logs.size}）",
        trailing = {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
        },
    ) {
        if (!expanded) return@SectionCard

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyLogs(context, logsText()) }) { Text("复制日志") }
            OutlinedButton(onClick = onClear) { Text("清空") }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            // reverseLayout + asReversed()：最新一条永远贴在底部，不需要手动 scrollToItem，
            // 也就不会和外面那层 verticalScroll 抢滚动。
            reverseLayout = true,
        ) {
            items(logs.asReversed()) { line ->
                Text(
                    text = line,
                    color = logLineColor(line),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        if (logs.isEmpty()) {
            Text("暂无日志", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun logLineColor(line: String): Color = when {
    // controller 给每行加了 "时间戳 " 前缀，所以标记字符不会在行首，只能按包含判断。
    line.contains("✗") -> MaterialTheme.colorScheme.error
    line.contains("▶") || line.contains("◀") -> Color(0xFF64B5F6)
    line.contains("[maa]") -> Color(0xFF81C784)
    line.contains("[helper]") -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurface
}

private fun copyLogs(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (clipboard == null) {
        toast(context, "剪贴板不可用")
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("MaaPocket 日志", text))
    toast(context, "日志已复制")
}

// ================================================================== 复用组件

/** 统一的卡片外壳：标题行 + 右侧可选的 trailing 控件 + 内容区。 */
@Composable
private fun SectionCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/**
 * 下拉选择框。
 *
 * 用 [DropdownMenu] + [DropdownMenuItem] 而不是 `ExposedDropdownMenuBox`：
 * 后者需要 `menuAnchor(...)`，而 anchor type 枚举在 material3 里改过名
 * （`MenuAnchorType` -> `ExposedDropdownMenuAnchorType`），本机没有 SDK / Gradle 可以核对版本，
 * 猜错就是编译失败。这两个 API 的签名多年未变，能保证编过，交互上等价。
 */
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "（未选择）" }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = display, style = MaterialTheme.typography.bodyMedium)
            }
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
