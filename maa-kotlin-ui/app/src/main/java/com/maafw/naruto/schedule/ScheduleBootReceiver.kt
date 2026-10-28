package com.maafw.naruto.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.maafw.naruto.data.schedule.ScheduleRepository
import com.maafw.naruto.schedule.data.SchedulePolicyRepository

/**
 * 开机后重新注册所有定时任务
 */
class ScheduleBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduleBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "开机完成，重新注册定时任务")
        // 旧 ScheduleItem（兼容）
        val items = ScheduleRepository.load(context)
        ScheduleHelper.rescheduleAll(context, items)
        // 策略（）
        val strategies = SchedulePolicyRepository(context).load()
        ScheduleHelper.rescheduleStrategies(context, strategies)
    }
}