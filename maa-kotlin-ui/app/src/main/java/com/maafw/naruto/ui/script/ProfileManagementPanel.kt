package com.maafw.naruto.ui.script

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.model.MaaTask
import com.maafw.naruto.ui.theme.MaaDesignTokens
import androidx.compose.ui.zIndex
import org.burnoutcrew.reorderable.*

/**
 * 任务配置管理面板
 * 列出所有任务配置（profile），可切换当前配置、新建空配置、删除/重命名。
 * 定时任务读取的 profileId 就是这里的配置名，可被正常调用。
 */
@Composable
fun ProfileManagementPanel(
    profiles: List<String>,
    activeProfileId: String,
    onSwitchProfile: (String) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onMoveProfile: (Int, Int) -> Unit,
    allTasks: List<MaaTask>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var renamingProfile by remember { mutableStateOf<String?>(null) }
    var resetConfirm by remember { mutableStateOf(false) }
    var containerHeight by remember { mutableStateOf(0) }
    val state = rememberReorderableLazyListState(
        // 拖拽过程中实时重排数据源 -> 其他 item 让位，用户能感知目标位置
        onMove = { from, to ->
            if (from.index != to.index) {
                onMoveProfile(from.index, to.index)
            }
        },
        // 重排已在 onMove 中实时完成，松手无需额外处理
        onDragEnd = { _, _ -> },
        // 关闭库的固定速度自动滚动，改用力度感应滚动（dragForceAutoScroll）
        maxScrollPerFrame = 0.dp
    )

    Column(
        modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text("任务配置", style = MaterialTheme.typography.titleMedium)
        Text(
            "切换不同任务列表；定时任务按配置名执行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = state.listState,
            modifier = Modifier
                .weight(1f)
                .reorderable(state)
                .onSizeChanged { containerHeight = it.height }
                .dragForceAutoScroll(
                    listState = state.listState,
                    containerHeight = { containerHeight },
                    isDragging = { state.draggingItemIndex != null }
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(profiles, key = { _, name -> name }) { _, name ->
                ReorderableItem(state, key = name) { isDragging ->
                val isActive = name == activeProfileId
                val tasks = ProfileManager.load(context, name)?.tasks.orEmpty()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .detectReorderAfterLongPress(state)
                        .clickable { onSwitchProfile(name) },
                    shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    ),
                    border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${tasks.count { it.enabled }} 个启用 / ${tasks.size} 个任务",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isActive) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (name == ProfileManager.DEFAULT_PROFILE_NAME) {
                            // 默认配置：提供"重置"按钮，恢复最初默认任务列表
                            IconButton(onClick = { resetConfirm = true }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Default.Refresh, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(onClick = { renamingProfile = name }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onDeleteProfile(name) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Default.Delete, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建任务配置")
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建任务配置") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("配置名称") },
                    placeholder = { Text("例如：日常、周胜、决斗场") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty() && trimmed !in profiles) {
                        onCreateProfile(trimmed)
                    }
                    showCreateDialog = false
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            }
        )
    }

    renamingProfile?.let { oldName ->
        var newName by remember { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { renamingProfile = null },
            title = { Text("重命名配置") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty() && trimmed != oldName) {
                        onRenameProfile(oldName, trimmed)
                    }
                    renamingProfile = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingProfile = null }) { Text("取消") }
            }
        )
    }

    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text("重置默认配置") },
            text = { Text("将默认配置恢复为最初的默认任务列表（按 default_check 勾选的任务重新生成），当前改动会丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    ProfileManager.resetDefaultProfile(context, allTasks)
                    resetConfirm = false
                }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text("取消") }
            }
        )
    }
}