package com.maafw.naruto.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maafw.naruto.data.schedule.ScheduleItem
import com.maafw.naruto.data.schedule.ScheduleRepository
import com.maafw.naruto.model.AssetLoader
import com.maafw.naruto.model.MaaTask
import com.maafw.naruto.schedule.ScheduleHelper

/**
 * 定时任务列表页喵～
 *  的 ScheduleListView喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen() {
    val context = LocalContext.current
    val allTasks = remember {
        AssetLoader.loadInterface(context)?.task?.takeIf { it.isNotEmpty() }
            ?: listOf(MaaTask(name = "启动游戏", entry = "start_up"))
    }

    var items by remember { mutableStateOf(ScheduleRepository.load(context)) }
    var editingItem by remember { mutableStateOf<ScheduleItem?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(items) {
        ScheduleHelper.rescheduleAll(context, items)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("定时任务") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingItem = null
                showDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无定时任务，点击右下角添加喵～", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        ScheduleItemCard(
                            item = item,
                            onToggle = {
                                items = ScheduleRepository.toggle(context, item.id)
                            },
                            onEdit = {
                                editingItem = item
                                showDialog = true
                            },
                            onDelete = {
                                items = ScheduleRepository.delete(context, item.id)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDialog) {
        ScheduleEditDialog(
            item = editingItem,
            allTasks = allTasks,
            onDismiss = { showDialog = false },
            onSave = { newItem ->
                items = if (editingItem == null) {
                    ScheduleRepository.add(context, newItem.copy(id = ScheduleRepository.nextId(context)))
                } else {
                    ScheduleRepository.update(context, newItem)
                }
                showDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleItemCard(
    item: ScheduleItem,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = String.format("%02d:%02d", item.hour, item.minute),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "${item.taskName} ${formatDays(item.repeatDays)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = item.enabled,
                onCheckedChange = { onToggle() }
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

private fun formatDays(days: Set<Int>): String {
    if (days.isEmpty()) return "仅一次"
    val names = listOf("日", "一", "二", "三", "四", "五", "六")
    return "周" + days.sorted().map { names[it] }.joinToString("")
}