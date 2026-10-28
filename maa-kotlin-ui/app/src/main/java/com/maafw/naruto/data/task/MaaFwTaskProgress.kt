package com.maafw.naruto.data.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedHashMap

/**
 * 任务链运行进度（MaaFW 专属；对应"任务分段进度"能力）。
 *
 * 记录一次任务中各任务链（entry）的运行状态，驱动前台通知的分段进度条：
 * - 任务启动前 clear() + 按 items 注册全部 entry（PENDING）；
 * - 引擎事件（started/succeeded/failed）经 onTaskEvent 更新状态；
 * - [progress] 暴露给通知/UI：completed/total（含错误计数）。
 */
class MaaFwTaskProgress {

    enum class Status { PENDING, IN_PROGRESS, COMPLETED, ERROR }

    data class TaskState(val entry: String, val status: Status) {
        val isDone: Boolean get() = status == Status.COMPLETED || status == Status.ERROR
    }

    private val registry = LinkedHashMap<String, TaskState>()
    private val _tasks = MutableStateFlow<List<TaskState>>(emptyList())
    val tasks: StateFlow<List<TaskState>> = _tasks.asStateFlow()

    /** 当前进度（分段进度条用） */
    data class Progress(val completed: Int, val total: Int, val errorCount: Int) {
        val fraction: Float get() = if (total <= 0) 0f else completed.toFloat() / total
    }

    private val _progress = MutableStateFlow(Progress(0, 0, 0))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** 新任务前清空并注册全部任务（按执行顺序） */
    fun reset(entries: List<String>) {
        registry.clear()
        entries.forEach { registry[it] = TaskState(it, Status.PENDING) }
        push()
    }

    /** 更新单个任务链状态（由引擎事件驱动） */
    fun onTaskEvent(entry: String, event: String) {
        val cur = registry[entry] ?: return
        val next = when (event) {
            "started" -> Status.IN_PROGRESS
            "succeeded" -> Status.COMPLETED
            "failed" -> Status.ERROR
            else -> return
        }
        registry[entry] = cur.copy(status = next)
        push()
    }

    /** 全部任务是否都结束（完成或失败） */
    fun isFinished(): Boolean {
        val list = _tasks.value
        return list.isNotEmpty() && list.all { it.isDone }
    }

    fun clear() {
        registry.clear()
        push()
    }

    private fun push() {
        val list = registry.values.toList()
        _tasks.value = list
        val done = list.count { it.isDone }
        val err = list.count { it.status == Status.ERROR }
        _progress.value = Progress(done, list.size, err)
    }
}