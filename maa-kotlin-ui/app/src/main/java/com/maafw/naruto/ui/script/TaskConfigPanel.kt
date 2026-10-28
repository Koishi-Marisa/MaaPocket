package com.maafw.naruto.ui.script

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.MaaOption
import com.maafw.naruto.model.MaaOptionCase
import com.maafw.naruto.model.MaaTask
import com.maafw.naruto.ui.components.AnimatedCheckbox

/**
 * 右侧任务配置面板
 *  的 ConfigurationPanel：显示任务说明 + 选项编辑器。
 */
@Composable
fun TaskConfigPanel(
    selectedTask: MaaTask?,
    interfaceData: MaaInterface?,
    profileName: String = "default",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val taskOptions = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(selectedTask, profileName) {
        taskOptions.clear()
        selectedTask?.let { t ->
            SettingsRepository.getTaskConfig(context, t.entry, profileName).options.forEach { (k, v) -> taskOptions[k] = v }
            t.option?.forEach { name ->
                if (!taskOptions.contains(name)) {
                    val opt = interfaceData?.option?.get(name)
                    when (opt?.type) {
                        "input" -> opt.inputs?.firstOrNull()?.let { taskOptions[name] = it.default }
                        // checkbox 默认空 = 都不选
                        "checkbox" -> opt?.let { taskOptions[name] = it.defaultCase }
                        // default_case 为空时按 Maa 语义取第一个 case（如「打排位」[No,Yes] -> 默认 No=匹配）；兼容顶层 default 写法
                        else -> opt?.let { taskOptions[name] = it.defaultCase.ifBlank { opt.default ?: opt.cases.firstOrNull()?.name ?: "" } }
                    }
                }
            }
        }
    }
    DisposableEffect(selectedTask?.entry, profileName) {
        onDispose { selectedTask?.let { SettingsRepository.setTaskOptions(context, it.entry, taskOptions.toMap(), profileName) } }
    }

    if (selectedTask == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "点击左侧任务查看和编辑设置",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(selectedTask.name, style = MaterialTheme.typography.titleMedium)
        if (!selectedTask.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                selectedTask.description.replace(Regex("""\[.*?\]"""), ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!selectedTask.option.isNullOrEmpty()) {
            Spacer(Modifier.height(12.dp))
            selectedTask.option.forEach { name ->
                val opt = interfaceData?.option?.get(name) ?: return@forEach
                OptionEditor(name, opt, interfaceData, taskOptions) { k, v ->
                    taskOptions[k] = v
                    SettingsRepository.setTaskOptions(context, selectedTask.entry, taskOptions.toMap(), profileName)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 选项标题行：显示选项名，右侧 info 图标点击后在上方弹出漫画式气泡显示 description */
@Composable
private fun OptionTitle(title: String, description: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (description.isNotBlank()) {
            Spacer(Modifier.width(2.dp))
            var showTip by remember { mutableStateOf(false) }
            var tipHeight by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            // 首帧用估算高度定位，避免 tipHeight=0 时 Popup 先画在错误位置导致"闪一下"
            val estimatedHeight = with(density) { 120.dp.roundToPx() }
            val tipOffset = remember(tipHeight, density) {
                with(density) { -((if (tipHeight > 0) tipHeight else estimatedHeight) + 8.dp.roundToPx()) }
            }
            Box {
                IconButton(onClick = { showTip = true }, modifier = Modifier.size(22.dp)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "说明",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showTip) {
                    Popup(
                        alignment = Alignment.TopCenter,
                        offset = IntOffset(0, tipOffset),
                        onDismissRequest = { showTip = false },
                        properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
                    ) {
                        SpeechBubble(description) { tipHeight = it }
                    }
                }
            }
        }
    }
}

/** 漫画式说话气泡：圆角矩形主体 + 底部朝下小三角（指向图标） */

/** 分段选择器（select ≤4 项时替代下拉，Uiverse segmented control 风格）：
 * 灰色胶囊容器 + 白色高亮块滑动动画，紧凑布局 */
@Composable
private fun SegmentedOptions(
    name: String,
    option: MaaOption,
    selected: String,
    onValueChange: (String, String) -> Unit
) {
    val cases = option.cases
    val selectedIndex = cases.indexOfFirst { it.name == selected }.coerceAtLeast(0)
    val itemHeight = 30.dp
    // 配色参考 Switch 关闭状态（中性、深浅/莫奈主题均稳定对比）：
    // 容器 = surfaceVariant（switch 关闭轨道色），选中胶囊 = outline（switch 关闭滑块色，更深更明显）
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val pillColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedTextColor = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(4.dp)
    ) {
        if (cases.size <= 1) {
            // 单选项：整块作为选中胶囊，文字绝对居中（避免胶囊/文字偏左）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(pillColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    cases.firstOrNull()?.name ?: "",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = selectedTextColor,
                    maxLines = 1
                )
            }
        } else {
            val itemWidth = (maxWidth - 8.dp) / cases.size
            // 容器有 4dp 内边距：高亮块起点要加上左 padding，否则整体偏左（最后一项右侧露底最明显）
            val targetOffset = 4.dp + itemWidth * selectedIndex
            val animatedOffset by animateDpAsState(
                targetOffset,
                tween(300, easing = FastOutSlowInEasing),
                label = "pillOffset"
            )
            // 高亮块（下层，无描边/阴影，位置滑动动画）
            Box(
                Modifier
                    .offset(x = animatedOffset)
                    .width(itemWidth)
                    .height(itemHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(pillColor)
            )
            // 选项文字（上层，可点击；indication=null 去掉白色点击反馈遮罩）
            Row(Modifier.fillMaxWidth()) {
                cases.forEachIndexed { index, case ->
                    val interaction = remember { MutableInteractionSource() }
                    val isSelected = index == selectedIndex
                    // 文字颜色平滑过渡，切换选择动画更明显
                    val animatedTextColor by animateColorAsState(
                        targetValue = if (isSelected) selectedTextColor else textColor,
                        animationSpec = tween(250),
                        label = "segTextColor"
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(itemHeight)
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = { onValueChange(name, case.name) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            case.name,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = animatedTextColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** 美化下拉框：灰底圆角胶囊触发按钮（与分段选择器风格统一）+ 圆角菜单 + 选中项高亮 */
@Composable
private fun StyledDropdown(
    current: String,
    cases: List<MaaOptionCase>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val btnColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(btnColor)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current,
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    color = textColor,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = textColor
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            cases.forEach { case ->
                DropdownMenuItem(
                    text = {
                        Text(
                            case.name,
                            fontSize = 14.sp,
                            fontWeight = if (case.name == current) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    trailingIcon = if (case.name == current) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    onClick = { onSelect(case.name); expanded = false }
                )
            }
        }
    }
}

/** 漫画式说话气泡：圆角矩形主体 + 底部朝下小三角（指向图标） */
@Composable
private fun SpeechBubble(text: String, onHeight: (Int) -> Unit) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.onSizeChanged { onHeight(it.height) }
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = bg,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = fg
            )
        }
        Box(
            Modifier
                .offset(y = (-6).dp)
                .size(12.dp)
                .rotate(45f)
                .background(bg)
        )
    }
}

@Composable
private fun OptionEditor(
    name: String,
    option: MaaOption,
    interfaceData: MaaInterface?,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit
) {
    val current = values[name] ?: option.defaultCase
    val selectedCase = option.cases.firstOrNull { it.name == current }
    when (option.type) {
        "switch" -> {
            // cases 顺序不统一（有的 [No,Yes] 有的 [Yes,No]），按名称语义匹配「开」和「关」
            val yesCase = option.cases.firstOrNull { it.name == "Yes" || it.name == "是" || it.name == "开" }?.name
                ?: option.cases.firstOrNull { it.name != "No" && it.name != "否" }?.name
                ?: "Yes"
            val noCase = option.cases.firstOrNull { it.name == "No" || it.name == "否" || it.name == "关" }?.name
                ?: option.cases.firstOrNull { it.name != yesCase }?.name
                ?: "No"
            val checked = current == yesCase
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OptionTitle(name, option.description, Modifier.weight(1f))
                // 缩小开关，给文本多留空间
                Switch(
                    checked = checked,
                    onCheckedChange = { onValueChange(name, if (it) yesCase else noCase) },
                    modifier = Modifier.scale(0.8f)
                )
            }
            // switch 选中 case 也可能带嵌套子选项（如「玉石商店」Yes -> 子选项）
            selectedCase?.option?.let { NestedOptions(it, interfaceData, values, onValueChange) }
        }
        "input" -> {
            val input = option.inputs?.firstOrNull() ?: return
            var text by remember(name, current) { mutableStateOf(current) }
            // verify 正则校验（空文本不报错，避免打断输入；".*" 通配不校验）
            val verifyRegex = remember(input.verify) {
                runCatching { input.verify?.takeIf { it.isNotBlank() && it != ".*" }?.let { Regex(it) } }.getOrNull()
            }
            val verifyError = text.isNotEmpty() && verifyRegex != null && !verifyRegex.matches(text)
            Column(Modifier.fillMaxWidth()) {
                // 标题=label + info 气泡显示 input.description（与其他控件统一，不直接铺小字）
                OptionTitle(input.label.ifEmpty { name }, input.description)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; onValueChange(name, it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                    isError = verifyError,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    // 输入框内部灰色占位提示（类似网页搜索框），内容取 input.description
                    placeholder = {
                        Text(
                            input.description.ifBlank { input.label },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (input.pipelineType == "int" || input.pipelineType == "number") KeyboardType.Number else KeyboardType.Text
                    ),
                    singleLine = true
                )
                if (verifyError) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "格式不正确，参考: ${input.verify}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        "checkbox" -> {
            // 多选，值用逗号分隔存储（兼容 Map<String,String>）
            val selectedSet = remember(current) {
                current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            Column(Modifier.fillMaxWidth()) {
                OptionTitle(name, option.description)
                option.cases.forEach { case ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedCheckbox(
                        checked = case.name in selectedSet,
                        onCheckedChange = { checked ->
                            val newSet = if (checked) selectedSet + case.name else selectedSet - case.name
                            onValueChange(name, newSet.sorted().joinToString(","))
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                        Column {
                            Text(case.name, style = MaterialTheme.typography.bodyMedium)
                            case.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        else -> {
            // select：≤4 项且文字较短用分段选择器（胶囊），否则用下拉框（长文字在胶囊里会挤）
            if (option.cases.size <= 4 && option.cases.isNotEmpty() &&
                option.cases.all { it.name.length <= 4 }
            ) {
                Column(Modifier.fillMaxWidth()) {
                    OptionTitle(name, option.description)
                    Spacer(Modifier.height(4.dp))
                    SegmentedOptions(name, option, current, onValueChange)
                    // select 选中 case 带嵌套子选项时联动展开
                    selectedCase?.option?.let { NestedOptions(it, interfaceData, values, onValueChange) }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    OptionTitle(name, option.description)
                    Spacer(Modifier.height(4.dp))
                    StyledDropdown(current, option.cases, onSelect = { onValueChange(name, it) })
                    // select 选中 case 带嵌套子选项时联动展开
                    selectedCase?.option?.let { NestedOptions(it, interfaceData, values, onValueChange) }
                }
            }
        }
    }
}

/** 嵌套子选项渲染：分隔线 + 缩进展示 case.option 里的子选项（子标题层级），递归复用 OptionEditor */
@Composable
private fun NestedOptions(
    subNames: List<String>,
    interfaceData: MaaInterface?,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit
) {
    Spacer(Modifier.height(6.dp))
    Divider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().padding(start = 16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            subNames.forEach { subName ->
                val subOpt = interfaceData?.option?.get(subName) ?: return@forEach
                OptionEditor(subName, subOpt, interfaceData, values, onValueChange)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}