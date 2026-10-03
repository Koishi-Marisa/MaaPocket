package com.maapocket.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.maapocket.core.constant.DefaultDisplayConfig
import com.maapocket.core.pi.PiController
import com.maapocket.core.pi.PiOption
import com.maapocket.core.pi.PiRepository
import com.maapocket.core.pi.PiSelection
import com.maapocket.core.pi.PiTask
import com.maapocket.core.privilege.PrivilegeAvailability
import com.maapocket.core.privilege.PrivilegeKind
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

    // 任务筛选词。**刻意放在 HomeScreen**：TaskCard 位于整页 verticalScroll 里，
    // 状态放上层才能在滚动/重组期间保持住。它只做 UI 过滤，不影响"哪些任务被勾选"。
    var taskQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MaaPocket") }) },
        // 承重：任务卡里有 30 个任务、每个带 4-8 行描述，把「准备 / 开始 / 停止」推到 20+ 屏之后。
        // 真机实测（HONOR AGI-AN00 / Android 15 / 1200x2664）12 次 fling 都滚不到 —— 而整页只有
        // 这一个入口。所以把它移出滚动流，常驻在 Scaffold 的 bottomBar 上。
        bottomBar = {
            RunBar(
                state = state,
                uiError = uiError,
                onPrepare = viewModel::prepare,
                onRun = viewModel::run,
                onStop = viewModel::requestStop,
                onDismissError = viewModel::dismissUiError,
            )
        },
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
                onRequestPrivilege = viewModel::requestPrivilege,
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
                query = taskQuery,
                onQueryChange = { taskQuery = it },
                selectedTasks = selectedTasks,
                optionValues = optionValues,
                onCheck = viewModel::setTaskChecked,
                onSelectAll = viewModel::selectAllTasks,
                onClearAll = viewModel::clearAllTasks,
                onSelectThese = viewModel::selectTasks,
                onUnselectThese = viewModel::unselectTasks,
                onOptionValue = viewModel::setOptionValue,
                optionDisplayValue = viewModel::optionDisplayValue,
                optionTrueCase = viewModel::optionTrueCase,
            )

            // 「运行」卡片的按钮已经搬到 bottomBar；这里只保留计划摘要，它是**配置结果**
            // （controller / resource / 显示尺寸 / 勾了哪些任务），跟按钮放一起会让人以为
            // 它随滚动位置失效。
            RunSummaryCard(plan = plan)

            PreviewCard(
                enabled = previewEnabled,
                onToggle = viewModel::setPreviewEnabled,
                // 与虚拟屏同尺寸：原生渲染线程按虚拟屏分辨率配置 EGL，SurfaceView 的
                // buffer 尺寸对不上就会被拉伸（MAA-Meow 也是先 setFixedSize 再交 Surface）。
                previewWidth = displayWidth.toIntOrNull() ?: DefaultDisplayConfig.WIDTH,
                previewHeight = displayHeight.toIntOrNull() ?: DefaultDisplayConfig.HEIGHT,
                onSurface = viewModel::onPreviewSurface,
            )

            LogCard(
                logs = logs,
                onClear = viewModel::clearLogs,
                logsText = viewModel::logsText,
            )

            // Scaffold 给内容区的 insets 底部已经算进了 bottomBar 的高度，这里再补一点余量，
            // 免得最后一张卡紧贴运行栏。
            Spacer(Modifier.height(8.dp))
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
    onRequestPrivilege: () -> Unit,
) {
    val context = LocalContext.current
    val privilege = state.privilege

    // 会话状态里的 availability 默认值是 Unsupported（PrivilegeStatus 的字段默认值），
    // 只有真的起过会话才有意义。直接显示它会出现自相矛盾的一屏：状态行写「设备不支持」，
    // 紧挨着的 shizuku 那行却写「可用」。未启动时改用**所选后端的探测结果**。
    val sessionKind = privilege.kind
    val effectiveAvailability = if (sessionKind != null) {
        privilege.availability
    } else {
        state.privilegeOptions.firstOrNull { it.first == kind }?.second ?: privilege.availability
    }

    SectionCard(title = "权限") {
        Text(
            text = if (sessionKind == null) {
                // uid 此时恒为 -1，写成 "-" 而不是 "-1"，免得看起来像探测失败。
                "未启动 · ${privilege.state.name} · uid=- · " +
                    "${kind.label}：${describeAvailability(effectiveAvailability)}"
            } else {
                "当前：${sessionKind.label} · ${privilege.state.name} · uid=${privilege.uid} · " +
                    describeAvailability(effectiveAvailability)
            },
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRefresh, enabled = !state.busy) { Text("刷新") }
            // 判据用**所选后端的探测结果**（effectiveAvailability）而不是会话状态：
            // 会话还没起来时 privilege.kind == null、privilege.state == IDLE，
            // 老代码的 `privilege.state == PERMISSION_REQUIRED` 永远为假 —— 真机上
            // Shizuku 明明「需要授权」，这个按钮却从来没出现过，用户只能看到
            // 「特权进程未连上」而无处可点。
            if (effectiveAvailability is PrivilegeAvailability.PermissionRequired) {
                Button(onClick = onRequestPrivilege, enabled = !state.busy) { Text("授权") }
            }
            if (kind == PrivilegeKind.SHIZUKU && effectiveAvailability !is PrivilegeAvailability.Ready) {
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

/** 一个分组 + 它下面（可能被搜索过滤过的）任务。 */
private data class TaskGroupView(val label: String, val tasks: List<PiTask>)

@Composable
private fun TaskCard(
    repository: PiRepository?,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedTasks: Set<String>,
    optionValues: Map<String, String>,
    onCheck: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onSelectThese: (Collection<String>) -> Unit,
    onUnselectThese: (Collection<String>) -> Unit,
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

        // 展开的「选项」编辑器：一次只开一个。真机可用宽度只有 369dp，同时展开多个会把
        // 列表切成碎片，用户滚下去就再也找不到刚改的那一项。状态提到这里（而不是 TaskRow
        // 内部的 remember）是为了扛住 LazyColumn 的回收 —— 行滚出屏幕再滚回来不会塌掉。
        var expandedTask by remember { mutableStateOf<String?>(null) }

        val groups = repository.tasksByGroup()
        val filtered = filterTaskGroups(repository, groups, query)
        val total = groups.values.fold(0) { acc, list -> acc + list.size }
        val shown = filtered.fold(0) { acc, group -> acc + group.tasks.size }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索任务（名称 / 说明 / 标签）") },
            singleLine = true,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (query.isBlank()) {
                    "已选 ${selectedTasks.size} 项"
                } else {
                    "已选 ${selectedTasks.size} 项 · 筛出 $shown / $total"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            // 有筛选词时「全选 / 全不选」只作用于**筛出来的那些**。否则筛出 3 个按「全选」，
            // 会把另外 27 个看不见的任务一起勾上（或一起清掉），用户完全无从察觉。
            val shownNames = filtered.flatMap { it.tasks }.map { it.name }
            TextButton(
                onClick = { if (query.isBlank()) onSelectAll() else onSelectThese(shownNames) },
            ) { Text("全选") }
            TextButton(
                onClick = { if (query.isBlank()) onClearAll() else onUnselectThese(shownNames) },
            ) { Text("全不选") }
        }
        HorizontalDivider()

        if (shown == 0) {
            Text("没有匹配的任务，换个关键词试试。", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }

        // 独立的滚动区域。**必须有确定高度**：外层是 Column(verticalScroll)，它给子项的
        // maxHeight 是 Infinity，而 Compose 会直接抛
        //   IllegalStateException: Vertically scrollable component was measured with an
        //   infinity maximum height constraints
        // 日志卡早就是这个写法（LazyColumn + 固定 height），真机上已验证可用，
        // 所以这里照抄而不用 weight/fillMaxHeight。
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
        ) {
            filtered.forEach { group ->
                item {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(group.tasks) { task ->
                    TaskRow(
                        repository = repository,
                        task = task,
                        checked = task.name in selectedTasks,
                        expanded = expandedTask == task.name,
                        onToggleExpand = {
                            expandedTask = if (expandedTask == task.name) null else task.name
                        },
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
}

/**
 * 按关键词过滤任务，空组直接丢掉。
 *
 * 匹配范围覆盖任务的 name / 已解析的 label / description / desc / entry / 它声明的 option 名，
 * 因为真机上的描述全是「tasks/daily/fight.py:102 显式 screen.change_to('main')」这种
 * 带路径的文本 —— 只按显示名搜等于搜不到。
 */
private fun filterTaskGroups(
    repo: PiRepository,
    groups: Map<String?, List<PiTask>>,
    query: String,
): List<TaskGroupView> {
    val needle = query.trim()
    return groups.mapNotNull { (name, tasks) ->
        val matched = if (needle.isEmpty()) tasks else tasks.filter { matchesTask(repo, it, needle) }
        if (matched.isEmpty()) null else TaskGroupView(groupLabel(repo, name), matched)
    }
}

private fun matchesTask(repo: PiRepository, task: PiTask, needle: String): Boolean {
    fun hit(text: String?): Boolean = text != null && text.contains(needle, ignoreCase = true)
    return hit(task.name) ||
        hit(repo.resolve(task.label)) ||
        hit(task.label) ||
        hit(task.description) ||
        hit(task.desc) ||
        hit(task.entry) ||
        task.option.any { hit(it) }
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
    expanded: Boolean,
    onToggleExpand: () -> Unit,
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
    // 描述的展开状态留在行内即可（不需要跨回收保持），但「一行」必须是默认值：
    // 30 个任务的描述都是 4-8 行，全展开就是 20+ 屏。
    var descExpanded by remember(task.name) { mutableStateOf(false) }

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
                    // 点一下切换 2 行 / 全文。真机上的描述形如
                    // 「tasks/daily/fight.py:102 显式 screen.change_to('main')」——
                    // 折叠后仍能看到开头，展开才看得到文件名和行号。
                    Text(
                        text = repository.resolve(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { descExpanded = !descExpanded },
                    )
                }
            }
            if (optionNames.isNotEmpty()) {
                TextButton(onClick = onToggleExpand) {
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

/**
 * 常驻运行栏（`Scaffold` 的 bottomBar）。
 *
 * 三个按钮的 `enabled` 条件是从原来那张页面里的 ActionCard **逐字照抄**的：
 * 那是 [MaaRunController] 对外的状态约定，不该在「换个地方放按钮」的重构里顺手改掉。
 *
 * 之所以要常驻：真机（HONOR AGI-AN00 / 1200x2664）上任务卡有 30 个带长描述的任务，
 * 把这三个按钮推到 20+ 屏之后，实测 12 次 fling 都到不了，而整页只有这一个入口。
 */
@Composable
private fun RunBar(
    state: MaaRunController.State,
    uiError: String?,
    onPrepare: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
) {
    val error = state.lastError ?: uiError
    // 错误换一条就重新折成一行，免得上一条的展开状态串到新错误上。
    var errorExpanded by remember(error) { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Scaffold **不会**给 bottomBar 自动加导航条内边距。不写这句，
                // 手势导航条会正好盖住「停止」。运行栏是唯一的入口，不能被盖。
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onPrepare, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("准备")
                }
                Button(
                    onClick = onRun,
                    // core 自己会检验，但按钮先在 UI 上拦住，避免用户点了没反应还不知道为什么。
                    enabled = !state.busy && state.phase == MaaRunController.Phase.IDLE &&
                        state.controllerReady && state.resourceLoaded,
                    modifier = Modifier.weight(1f),
                ) { Text("开始") }
                OutlinedButton(
                    onClick = onStop,
                    enabled = state.phase == MaaRunController.Phase.RUNNING,
                    modifier = Modifier.weight(1f),
                ) { Text("停止") }
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = describePhase(state.phase),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.phase == MaaRunController.Phase.RUNNING) {
                    Text(
                        text = "${state.taskIndex}/${state.taskCount}  ${state.currentTask ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (state.phase == MaaRunController.Phase.RUNNING) {
                val fraction = if (state.taskCount > 0) {
                    (state.taskIndex.toFloat() / state.taskCount.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }

            if (error != null) {
                // 运行栏只有这么点高度，长错误先压成一行；点一下展开全文，旁边可以关掉
                // （`dismissUiError()` 之前没有任何 UI 调用过，所以 uiError 一旦出现就再也消不掉）。
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = if (errorExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { errorExpanded = !errorExpanded },
                )
                TextButton(onClick = onDismissError) { Text("关闭提示") }
            }
        }
    }
}

/** 运行摘要。按钮搬去 bottomBar 之后，这里只留「这次要跑什么」的配置结果。 */
@Composable
private fun RunSummaryCard(plan: PiSelection.PiRunPlan?) {
    SectionCard(title = "运行摘要") {
        if (plan == null) {
            Text(
                text = "解包资源包并勾选任务后，这里会显示这次要跑什么。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Text(PiSelection.summarize(plan), style = MaterialTheme.typography.bodySmall)
        plan.warnings.forEach { warning ->
            Text(
                text = "⚠ $warning",
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
    previewWidth: Int,
    previewHeight: Int,
    onSurface: (android.view.Surface?) -> Unit,
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
            if (!enabled) {
                Text("预览已关闭", color = Color.White, style = MaterialTheme.typography.bodySmall)
            } else {
                // 照抄 MAA-Meow 的 `BackgroundTaskView`：预览**不是**一帧帧推过来的位图，
                // 而是一块 SurfaceView，把它的 Surface 交给特权进程，由原生 EGL 直接把抓到的
                // 帧画进这块窗口的 BufferQueue。像素不经过 JSON / base64 / 文件，所以既不卡也不掉帧。
                AndroidView(
                    factory = { ctx ->
                        // SurfaceView **不缩放**缓冲区内容，只是把缓冲区按自己的位置摆上去。
                        // 所以缓冲区绝不能设成虚拟屏分辨率（1920x1080）——在 ~340px 宽的卡片里
                        // 就只会露出左上角那一小块（实测如此）。缓冲区必须是**视图自己的像素尺寸**，
                        // 原生侧 `AttachWindow` 会按 surface 尺寸 `glViewport(0,0,w,h)`，
                        // 全屏四边形把整帧缩放着画满；这里再按画面宽高比内接，避免拉伸。
                        var desiredWidth = 0
                        var desiredHeight = 0

                        SurfaceView(ctx).apply {
                            // RGBA_8888 与原生侧 `RenderLoop` 的 EGL 配置一致。
                            holder.setFormat(PixelFormat.RGBA_8888)

                            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                                val viewWidth = right - left
                                val viewHeight = bottom - top
                                if (viewWidth <= 0 || viewHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) {
                                    return@addOnLayoutChangeListener
                                }
                                val scale = minOf(
                                    viewWidth.toFloat() / previewWidth,
                                    viewHeight.toFloat() / previewHeight,
                                )
                                desiredWidth = (previewWidth * scale).toInt().coerceAtLeast(1)
                                desiredHeight = (previewHeight * scale).toInt().coerceAtLeast(1)
                                runCatching { holder.setFixedSize(desiredWidth, desiredHeight) }
                            }

                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    // 布局监听器还没跑时，SurfaceView 会先用默认尺寸回调一次；
                                    // 那一版交出去只会让原生侧画进一块尺寸不对的窗口，所以先摘掉。
                                    if (desiredWidth > 0 && width == desiredWidth && height == desiredHeight) {
                                        onSurface(holder.surface)
                                    } else {
                                        onSurface(null)
                                    }
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    // 滚动出屏 / 关闭预览 / 换会话都会走到这里。必须摘掉，
                                    // 否则原生侧会继续往一块正在销毁的窗口上画。
                                    onSurface(null)
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = "预览是 app 的 SurfaceView 直接承接特权进程抓到的帧（与 MAA-Meow 同款），" +
                "不经过 IPC 传像素。",
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
