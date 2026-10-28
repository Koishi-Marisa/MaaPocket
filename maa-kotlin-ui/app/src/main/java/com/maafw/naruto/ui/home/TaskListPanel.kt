package com.maafw.naruto.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maafw.naruto.model.MaaTask
import com.maafw.naruto.ui.components.TaskCategory
import com.maafw.naruto.ui.components.groupByCategory

/**
 * 任务列表面板喵～
 * 按分类展示 MAAFW 任务， 的任务选择体验喵。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListPanel(
    tasks: List<MaaTask>,
    selectedTask: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val grouped = remember(tasks) { tasks.groupByCategory() }
    var currentCategory by remember { mutableStateOf<TaskCategory?>(null) }

    val categories = remember(grouped) { grouped.keys.sortedBy { it.ordinal } }
    val displayedTasks = if (currentCategory == null) {
        grouped
    } else {
        grouped.filter { it.key == currentCategory }
    }

    val selectedTabIndex = if (currentCategory == null) 0 else categories.indexOf(currentCategory) + 1

    Column(modifier = modifier) {
        Text("选择任务", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // 分类筛选喵
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 0.dp
        ) {
            Tab(
                selected = currentCategory == null,
                onClick = { currentCategory = null },
                text = { Text("全部") }
            )
            categories.forEach { category ->
                Tab(
                    selected = currentCategory == category,
                    onClick = { currentCategory = category },
                    text = { Text(category.title) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            displayedTasks.forEach { (category, items) ->
                item {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(items, key = { it.entry }) { task ->
                    TaskCard(
                        task = task,
                        selected = task.entry == selectedTask,
                        onClick = { onSelect(task.entry) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskCard(
    task: MaaTask,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                task.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = desc.replace(Regex("\\[.*?\\]"), "").take(60),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}