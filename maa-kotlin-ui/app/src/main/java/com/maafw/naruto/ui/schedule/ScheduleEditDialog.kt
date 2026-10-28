package com.maafw.naruto.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.maafw.naruto.data.schedule.ScheduleItem
import com.maafw.naruto.model.MaaTask

/**
 * 定时任务编辑弹窗喵～
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditDialog(
    item: ScheduleItem?,
    allTasks: List<MaaTask>,
    onDismiss: () -> Unit,
    onSave: (ScheduleItem) -> Unit
) {
    var taskEntry by remember { mutableStateOf(item?.taskEntry ?: allTasks.firstOrNull()?.entry ?: "") }
    var taskName by remember { mutableStateOf(item?.taskName ?: allTasks.firstOrNull()?.name ?: "") }
    var hourText by remember { mutableStateOf((item?.hour ?: 0).toString().padStart(2, '0')) }
    var minuteText by remember { mutableStateOf((item?.minute ?: 0).toString().padStart(2, '0')) }
    var repeatDays by remember { mutableStateOf(item?.repeatDays?.toSet() ?: emptySet()) }

    var taskExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = if (item == null) "添加定时任务" else "编辑定时任务",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 任务选择喵
                Text("选择任务", style = MaterialTheme.typography.bodyMedium)
                Box {
                    Button(onClick = { taskExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(taskName)
                    }
                    DropdownMenu(expanded = taskExpanded, onDismissRequest = { taskExpanded = false }) {
                        allTasks.forEach { task ->
                            DropdownMenuItem(
                                text = { Text(task.name) },
                                onClick = {
                                    taskEntry = task.entry
                                    taskName = task.name
                                    taskExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 时间喵
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("时") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("分") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 重复日期喵
                Text("重复", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val days = listOf("日", "一", "二", "三", "四", "五", "六")
                    days.forEachIndexed { index, name ->
                        val checked = repeatDays.contains(index)
                        FilterChip(
                            selected = checked,
                            onClick = {
                                repeatDays = if (checked) repeatDays - index else repeatDays + index
                            },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            val hour = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                            val minute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                            onSave(
                                ScheduleItem(
                                    id = item?.id ?: 0,
                                    taskEntry = taskEntry,
                                    taskName = taskName,
                                    hour = hour,
                                    minute = minute,
                                    repeatDays = repeatDays.toSet(),
                                    enabled = item?.enabled ?: true
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}