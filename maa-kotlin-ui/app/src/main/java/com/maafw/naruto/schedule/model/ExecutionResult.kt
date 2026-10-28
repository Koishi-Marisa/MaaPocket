package com.maafw.naruto.schedule.model

/**
 * 定时任务执行结果
 *  ExecutionResult.kt。
 */
enum class ExecutionResult {
    /** 已启动（成功） */
    STARTED,
    /** 校验失败 */
    FAILED_VALIDATION,
    /** 启动失败 */
    FAILED_START,
    /** UI 拉起失败 */
    FAILED_UI_LAUNCH,
    /** 已有任务运行，跳过 */
    SKIPPED_BUSY,
    /** 已取消 */
    CANCELLED,
}

/** 序列化友好名称（存到 JSON 用） */
fun ExecutionResult.toName(): String = when (this) {
    ExecutionResult.STARTED -> "STARTED"
    ExecutionResult.FAILED_VALIDATION -> "FAILED_VALIDATION"
    ExecutionResult.FAILED_START -> "FAILED_START"
    ExecutionResult.FAILED_UI_LAUNCH -> "FAILED_UI_LAUNCH"
    ExecutionResult.SKIPPED_BUSY -> "SKIPPED_BUSY"
    ExecutionResult.CANCELLED -> "CANCELLED"
}

fun executionResultFromName(name: String?): ExecutionResult? = when (name) {
    "STARTED" -> ExecutionResult.STARTED
    "FAILED_VALIDATION" -> ExecutionResult.FAILED_VALIDATION
    "FAILED_START" -> ExecutionResult.FAILED_START
    "FAILED_UI_LAUNCH" -> ExecutionResult.FAILED_UI_LAUNCH
    "SKIPPED_BUSY" -> ExecutionResult.SKIPPED_BUSY
    "CANCELLED" -> ExecutionResult.CANCELLED
    else -> null
}