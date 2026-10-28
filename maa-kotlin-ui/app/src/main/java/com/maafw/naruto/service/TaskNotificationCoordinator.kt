package com.maafw.naruto.service

import android.content.Context
import com.maafw.naruto.data.notify.PushDispatcher
import com.maafw.naruto.data.settings.SettingsRepository

/**
 * 任务通知协调器。
 * 统一分发系统通知和外部推送，避免 Service 里直接调用具体实现。
 * 按事件类型受独立开关控制（开始/完成/出错/服务异常），第三方推送同步分发。
 */
class TaskNotificationCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val appNotification = AppNotificationManager(appContext)

    /** 任务开始 */
    fun notifyTaskStarted(taskName: String, message: String = "任务开始执行") {
        // 本地通知开关（默认关闭）
        if (SettingsRepository.isNotifyTaskStart(appContext)) {
            appNotification.notifyTaskStarted(taskName, message)
        }
        // 第三方推送开关（默认关闭）
        if (SettingsRepository.isPushNotifyStart(appContext)) {
            PushDispatcher.dispatchStart(appContext, "任务开始", "[$taskName] $message")
        }
    }

    /** U-4：任务进行中通知（不确定进度条，任务运行期间展示，结束自动取消） */
    fun notifyTaskRunning(taskName: String, detail: String = "") {
        if (SettingsRepository.isNotificationEnabled(appContext)) {
            appNotification.notifyTaskRunning(taskName, detail)
        }
    }

    /** 任务链分段进度通知（completed/total，错误标红） */
    fun notifyTaskProgress(taskName: String, completed: Int, total: Int, errorCount: Int) {
        if (SettingsRepository.isNotificationEnabled(appContext)) {
            appNotification.notifyTaskProgress(taskName, completed, total, errorCount)
        }
    }

    /** U-4：任务结束/停止时取消运行中通知 */
    fun cancelTaskRunning() {
        appNotification.cancelTaskRunning()
    }

    /** 任务完成（支持耗时） */
    fun notifyTaskCompleted(summary: String, durationText: String? = null) {
        // 本地通知开关（默认开启，受总开关控制）
        if (SettingsRepository.isNotifyTaskComplete(appContext)) {
            appNotification.notifyAllTasksCompleted(summary, durationText)
        }
        // 第三方推送同步（开关默认开启）
        if (SettingsRepository.isPushNotifySuccess(appContext)) {
            val body = if (durationText != null) "$summary（耗时 $durationText）" else summary
            PushDispatcher.dispatch(appContext, "任务完成", body, isSuccess = true)
        }
    }

    /** 任务出错 */
    fun notifyTaskError(taskName: String, message: String) {
        // 本地通知开关（默认开启，受总开关控制）
        if (SettingsRepository.isNotifyTaskError(appContext)) {
            appNotification.notifyTaskError(taskName, message)
        }
        // 第三方推送同步（开关默认开启）
        if (SettingsRepository.isPushNotifyError(appContext)) {
            PushDispatcher.dispatch(appContext, "任务出错", "[$taskName] $message", isSuccess = false)
        }
    }

    /** 服务异常 */
    fun notifyServiceDied(message: String) {
        // 本地通知开关（默认开启，受总开关控制）
        if (SettingsRepository.isNotifyServiceEvent(appContext)) {
            appNotification.notifyServiceDied(message)
        }
    }
}