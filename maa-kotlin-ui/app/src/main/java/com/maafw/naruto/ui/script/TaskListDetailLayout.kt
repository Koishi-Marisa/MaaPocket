package com.maafw.naruto.ui.script

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.model.MaaInterface
import com.maafw.naruto.model.MaaTask

/**
 * 任务列表 + 右侧详情布局
 *  TaskListDetailLayout.kt。
 */
@Composable
fun TaskListDetailLayout(
    nodes: List<ProfileManager.ProfileTask>,
    allTasks: List<MaaTask>,
    selectedNodeId: String?,
    isEditMode: Boolean,
    isAddingTask: Boolean,
    isProfileMode: Boolean,
    profiles: List<String>,
    activeProfileId: String,
    onNodeEnabledChange: (String, Boolean) -> Unit,
    onNodeSelected: (String) -> Unit,
    onNodeMove: (Int, Int) -> Unit,
    onNodeRemove: (Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleAddingTask: () -> Unit,
    onToggleProfileMode: () -> Unit,
    onSwitchProfile: (String) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onMoveProfile: (Int, Int) -> Unit,
    onAddNode: (MaaTask) -> Unit,
    interfaceData: MaaInterface?,
    modifier: Modifier = Modifier,
    /** 右侧配置区包一层 Card（ wrapDetailInCard） */
    wrapDetailInCard: Boolean = true,
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    // 编辑任务时的长按拖拽提示：点 X 后持久化关闭，未点过则每次进入编辑模式都显示
    var editDragTipDismissed by remember {
        mutableStateOf(SettingsRepository.isEditDragTipDismissed(context))
    }
    // 一致：浮窗约 0.85 屏宽
    val floatMaxWidth = (configuration.screenWidthDp * 0.85f).dp

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentModifier = if (constraints.hasBoundedWidth) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .widthIn(max = floatMaxWidth)
                .fillMaxHeight()
                .fillMaxWidth()
        }

        Row(modifier = contentModifier) {
            TaskListPanel(
                nodes = nodes,
                allTasks = allTasks,
                selectedNodeId = selectedNodeId,
                isEditMode = isEditMode,
                isAddingTask = isAddingTask,
                isProfileMode = isProfileMode,
                onNodeEnabledChange = onNodeEnabledChange,
                onNodeSelected = onNodeSelected,
                onNodeMove = onNodeMove,
                onNodeRemove = onNodeRemove,
                onToggleEditMode = onToggleEditMode,
                onToggleAddingTask = onToggleAddingTask,
                onToggleProfileMode = onToggleProfileMode,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 80.dp, max = 104.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            DetailHost(
                wrapInCard = wrapDetailInCard,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (isProfileMode) {
                        // 任务配置管理界面（ 配置切换语义）
                        ProfileManagementPanel(
                            profiles = profiles,
                            activeProfileId = activeProfileId,
                            onSwitchProfile = onSwitchProfile,
                            onCreateProfile = onCreateProfile,
                            onDeleteProfile = onDeleteProfile,
                            onRenameProfile = onRenameProfile,
                            onMoveProfile = onMoveProfile,
                            allTasks = allTasks,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        TaskConfigPanel(
                        selectedTask = allTasks.find { it.entry == selectedNodeId },
                        interfaceData = interfaceData,
                        profileName = activeProfileId,
                        modifier = Modifier.fillMaxSize(),
                    )
                    }
                    // 编辑任务模式：右上角置顶拖拽提示（点 X 后不再显示，不超出右侧设置区）
                    if (isEditMode && !editDragTipDismissed) {
                        EditDragTipCard(
                            onDismiss = {
                                editDragTipDismissed = true
                                SettingsRepository.setEditDragTipDismissed(context, true)
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(8.dp)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/** 编辑任务时的长按拖拽提示卡片：置顶浮层，有 X 按钮，点 X 后持久化关闭 */
@Composable
private fun EditDragTipCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "拖拽排序提示",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "在编辑模式下，长按任务即可拖拽调整顺序；\n拖到列表上/下边缘会自动滚动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭提示",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailHost(
    wrapInCard: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (wrapInCard) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp),
            ) {
                content()
            }
        }
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}