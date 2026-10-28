package com.maafw.naruto.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.maafw.naruto.data.schedule.ScheduleRepository
import com.maafw.naruto.schedule.data.SchedulePolicyRepository
import com.maafw.naruto.schedule.model.ExecutionResult
import com.maafw.naruto.schedule.model.ScheduleStrategy
import com.maafw.naruto.service.MaaEngineService
import com.maafw.naruto.shizuku.ShizukuManager
import rikka.shizuku.Shizuku

/**
 * 定时任务闹钟接收器
 *  ScheduleReceiver.kt + 保留旧 ScheduleItem 兼容：
 * - EXTRA_STRATEGY_ID 存在 -> 策略触发（ 流程）
 * - 否则走旧 ScheduleItem 流程
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduleAlarmReceiver"
        const val ACTION_TRIGGER = "com.maafw.naruto.SCHEDULE_TRIGGER"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_TASK_ENTRY = "task_entry"
        const val EXTRA_STRATEGY_ID = "strategy_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return

        val strategyId = intent.getStringExtra(EXTRA_STRATEGY_ID)
        if (strategyId != null) {
            handleStrategy(context, strategyId)
            return
        }

        // 旧 ScheduleItem 流程（兼容）
        val scheduleId = intent.getIntExtra(EXTRA_SCHEDULE_ID, -1)
        Log.i(TAG, "定时任务触发：id=$scheduleId ")

        val item = ScheduleRepository.load(context).find { it.id == scheduleId }
        if (item != null && item.enabled) {
            ScheduleHelper.schedule(context, item)
        }

        startEngine(context, profileName = "default", forceStart = false, autoSleep = false, closeGame = false)
    }

    /** 策略触发流程（ ScheduleReceiver） */
    private fun handleStrategy(context: Context, strategyId: String) {
        Log.i(TAG, "定时策略触发：strategyId=$strategyId ")

        val repository = SchedulePolicyRepository(context)
        val strategy = repository.getById(strategyId)
        if (strategy == null) {
            Log.w(TAG, "策略不存在：$strategyId ")
            return
        }

        // 重新注册下一次闹钟（：仅启用时）
        if (strategy.enabled) {
            ScheduleHelper.scheduleStrategy(context, strategy)
        }

        if (!strategy.enabled) return

        // 唤醒方式：Root 优先（更可靠），其次 Shizuku
        val rootWake = strategy.rootWakeApp
        if (rootWake) {
            // 通过 Root 把 App 强拉前台：解除后台启动前台服务限制 + 拉起被杀进程
            RootWakeHelper.wakeApp(context)
        } else if (strategy.shizukuWakeApp) {
            // 使用 Shizuku 在后台唤醒应用（可选）：当策略开启时，先确认 Shizuku 已就绪
            if (!ShizukuManager.isReady()) {
                Log.w(TAG, "Shizuku 未就绪，无法唤醒应用执行策略：${strategy.name} ")
            } else {
                // 冗余唤醒：通过显式广播再次触发自己，确保应用进程被拉起
                try {
                    val wakeIntent = Intent(context.applicationContext, ScheduleAlarmReceiver::class.java).apply {
                        action = ACTION_TRIGGER
                        putExtra(EXTRA_STRATEGY_ID, strategy.id)
                    }
                    context.applicationContext.sendBroadcast(wakeIntent)
                    Log.i(TAG, "已发送 Shizuku 冗余唤醒广播：${strategy.name} ")
                } catch (e: Exception) {
                    Log.w(TAG, "冗余唤醒广播失败：${e.message} ")
                }
            }
        }

        startEngine(
            context,
            profileName = strategy.profileId,
            forceStart = strategy.forceStart,
            autoSleep = strategy.autoSleepAfterTask,
            closeGame = strategy.closeGameAfterTask,
            // Root 唤醒开启 -> 引擎也走 Root 进程（不依赖 Shizuku）
            useRootEngine = rootWake,
        )

        // 记录执行结果
        repository.recordExecutionResult(strategyId, ExecutionResult.STARTED, "已触发")
    }

    // Shizuku 唤醒辅助可在此扩展

    private fun startEngine(
        context: Context,
        profileName: String,
        forceStart: Boolean,
        autoSleep: Boolean,
        closeGame: Boolean,
        useRootEngine: Boolean = false,
    ) {
        val serviceIntent = Intent(context.applicationContext, MaaEngineService::class.java).apply {
            putExtra("action", "run_profile")
            putExtra("profile_name", profileName)
            putExtra("force_start", forceStart)
            putExtra("auto_sleep", autoSleep)
            putExtra("close_game", closeGame)
            // Root 唤醒 -> 引擎走 root 进程（不依赖 Shizuku）
            putExtra("use_root", useRootEngine)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.applicationContext.startForegroundService(serviceIntent)
        } else {
            context.applicationContext.startService(serviceIntent)
        }
    }
}