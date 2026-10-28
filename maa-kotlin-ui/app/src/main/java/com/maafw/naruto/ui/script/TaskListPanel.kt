@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.maafw.naruto.ui.script

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import android.os.SystemClock
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.model.MaaTask
import com.maafw.naruto.ui.components.AnimatedCheckbox
import org.burnoutcrew.reorderable.*

/**
 * 左侧任务列表
 *  TaskListPanel.kt：
 * - 编辑模式下长按任务即可拖拽排序
 * - TaskChainNode 替换为我们的 ProfileTask + MaaTask
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskListPanel(
    nodes: List<ProfileManager.ProfileTask>,
    allTasks: List<MaaTask>,
    selectedNodeId: String?,
    isEditMode: Boolean,
    isAddingTask: Boolean,
    isProfileMode: Boolean,
    onNodeEnabledChange: (String, Boolean) -> Unit,
    onNodeSelected: (String) -> Unit,
    onNodeMove: (Int, Int) -> Unit,
    onNodeRemove: (Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleAddingTask: () -> Unit,
    onToggleProfileMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // 任务配置按钮 - 在编辑任务按钮上方（ 配置切换）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleProfileMode() },
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isProfileMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                1.dp,
                if (isProfileMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isProfileMode) 2.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isProfileMode) Icons.Default.Check else Icons.Filled.List,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isProfileMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isProfileMode) "完成" else "任务配置",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isProfileMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (isProfileMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 编辑任务按钮 - 具备高亮状态（）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleEditMode() },
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                1.dp,
                if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isEditMode) 2.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isEditMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isEditMode) "完成" else "编辑任务",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isEditMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (isEditMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 新增任务按钮 - 仅在编辑模式下显示（）
        AnimatedVisibility(
            visible = isEditMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleAddingTask() },
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAddingTask) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    border = if (isAddingTask) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    } else {
                        null
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isAddingTask) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "添加任务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isAddingTask) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (isAddingTask) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isEditMode) {
            ReorderableTaskList(
                nodes = nodes,
                allTasks = allTasks,
                selectedNodeId = selectedNodeId,
                onNodeEnabledChange = onNodeEnabledChange,
                onNodeSelected = onNodeSelected,
                onNodeMove = onNodeMove,
                onNodeRemove = onNodeRemove,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(nodes, key = { _, node -> node.entry + "@" + nodes.indexOf(node) }) { index, node ->
                    key(node.entry + "@" + index) {
                        TaskNodeRow(
                            node = node,
                            taskName = allTasks.find { it.entry == node.entry }?.name ?: node.entry,
                            isSelected = selectedNodeId == node.entry,
                            isEditMode = false,
                            onEnabledChange = { enabled -> onNodeEnabledChange(node.entry, enabled) },
                            onSelected = { onNodeSelected(node.entry) },
                            onRemove = { onNodeRemove(index) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 力度感应边缘滚动（velocity/proximity based edge scroll）：
 * 长按拖拽 item 到列表上/下边缘时，滚动速度 = 离边缘距离因子 + 手指拖拽速度因子。
 * 像手机桌面图标拖拽：慢慢靠边慢滚、快速甩向边缘快滚。
 */
internal fun Modifier.dragForceAutoScroll(
    listState: LazyListState,
    containerHeight: () -> Int,
    isDragging: () -> Boolean
): Modifier = this.pointerInput(listState) {
    val thresholdPx = 120.dp.toPx()      // 触发边缘滚动的距离阈值
    val maxSpeedPx = 1400.dp.toPx()      // 最大滚动速度(px/s)
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var lastY = 0f
        var lastTime = 0L
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            val y = change.position.y
            val now = SystemClock.uptimeMillis()
            if (lastTime != 0L && containerHeight() > 0 && isDragging()) {
                val dtMs = (now - lastTime).coerceIn(16L, 100L)
                val vel = (y - lastY) / dtMs * 1000f   // 手指速度 px/s，向下为正
                var speed = 0f
                if (y < thresholdPx) {
                    // 顶部边缘：距离越近越用力，叠加向上甩的速度
                    val proximity = 1f - (y / thresholdPx).coerceIn(0f, 1f)
                    val velBoost = if (vel < 0f) -vel * 0.25f else 0f
                    speed = -(proximity * maxSpeedPx + velBoost).coerceAtMost(maxSpeedPx)
                } else if (containerHeight() - y < thresholdPx) {
                    // 底部边缘
                    val proximity = 1f - ((containerHeight() - y) / thresholdPx).coerceIn(0f, 1f)
                    val velBoost = if (vel > 0f) vel * 0.25f else 0f
                    speed = (proximity * maxSpeedPx + velBoost).coerceAtMost(maxSpeedPx)
                }
                if (speed != 0f) {
                    // dispatchRawDelta 是 LazyListState 成员方法（非 suspend），可在受限指针作用域直接调用
                    listState.dispatchRawDelta(speed * dtMs / 1000f)
                }
            }
            lastY = y
            lastTime = now
        }
    }
}

@Composable
private fun ReorderableTaskList(
    nodes: List<ProfileManager.ProfileTask>,
    allTasks: List<MaaTask>,
    selectedNodeId: String?,
    onNodeEnabledChange: (String, Boolean) -> Unit,
    onNodeSelected: (String) -> Unit,
    onNodeMove: (Int, Int) -> Unit,
    onNodeRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var containerHeight by remember { mutableStateOf(0) }
    // 稳定且唯一的 key：允许重复添加同一任务（entry 相同），用「entry + 同名前缀序号」区分实例，
    // 拖拽移动后相对序号不变，key 保持稳定（修复重复任务时 LazyColumn key 冲突导致拖拽错乱/崩溃）
    val keys = remember(nodes) {
        nodes.mapIndexed { i, n -> n.entry + "#" + nodes.take(i + 1).count { it.entry == n.entry } }
    }
    val state = rememberReorderableLazyListState(
        // 拖拽过程中实时重排数据源 -> 其他 item 让位，用户能感知目标位置
        onMove = { from, to ->
            val list = nodes.toMutableList()
            if (from.index in list.indices && to.index in list.indices && from.index != to.index) {
                onNodeMove(from.index, to.index)
            }
        },
        // 重排已在 onMove 中实时完成，松手无需额外处理
        onDragEnd = { _, _ -> },
        // 关闭库的固定速度自动滚动，改用力度感应滚动（dragForceAutoScroll）
        maxScrollPerFrame = 0.dp
    )
    Column(modifier = modifier) {
        // 交互引导：少量任务时提示（避免用户困惑"拖不动"）
        if (nodes.size <= 1) {
            Text(
                if (nodes.isEmpty()) "暂无任务，点上方「添加任务」开始" else "已添加 1 个任务，至少 2 个任务才能拖拽排序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            Text(
                "共 ${nodes.size} 个任务：长按任务行可拖拽排序（或使用右侧箭头）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        LazyColumn(
            state = state.listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .reorderable(state)
                .onSizeChanged { containerHeight = it.height }
                .dragForceAutoScroll(
                    listState = state.listState,
                    containerHeight = { containerHeight },
                    isDragging = { state.draggingItemIndex != null }
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(nodes, key = { index, _ -> keys[index] }) { index, node ->
                ReorderableItem(state, key = keys[index]) { isDragging ->
                    TaskNodeRow(
                        node = node,
                        taskName = allTasks.find { it.entry == node.entry }?.name ?: node.entry,
                        isSelected = selectedNodeId == node.entry,
                        isEditMode = true,
                        canMoveUp = index > 0,
                        canMoveDown = index < nodes.size - 1,
                        onEnabledChange = { enabled -> onNodeEnabledChange(node.entry, enabled) },
                        onSelected = { onNodeSelected(node.entry) },
                        onMoveUp = { onNodeMove(index, index - 1) },
                        onMoveDown = { onNodeMove(index, index + 1) },
                        onRemove = { onNodeRemove(index) },
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .detectReorderAfterLongPress(state)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskNodeRow(
    node: ProfileManager.ProfileTask,
    taskName: String,
    isSelected: Boolean,
    isEditMode: Boolean,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onEnabledChange: (Boolean) -> Unit,
    onSelected: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        ) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelected() }
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditMode) {
                    // 拖拽手柄：视觉提示"可长按拖动排序"
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "拖拽排序",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                AnimatedCheckbox(
                    checked = node.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.size(20.dp),
                    size = 20.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = taskName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (isEditMode) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(16.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}