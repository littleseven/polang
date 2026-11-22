package com.mamba.picme.service.organize

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mamba.picme.PoLangApplication

/**
 * 后台预分析共用入口（Agent 清理 M1 Task 6）：
 * - `ACTION_POWER_CONNECTED`：插电即评估（充电是硬前置，插电时刻最接近全条件满足）；
 * - [ACTION_PRE_ANALYSIS_ALARM]：每日 03:00 闲时闹钟 fire。
 *
 * 两者同走 [PreAnalysisScheduler.runNow]，条件不满足时内部静默跳过（不弹不扰）。
 * goAsync 持住广播至链条收尾（元数据回填通常已被启动回填排空，秒级完成）；
 * 闹钟触发后重排次日闹钟维持日节奏（进程存活期不依赖 onCreate 重排）。
 */
class PreAnalysisReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PoLangApplication ?: return
        val trigger = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> PreAnalysisScheduler.TRIGGER_POWER_CONNECTED
            ACTION_PRE_ANALYSIS_ALARM -> PreAnalysisScheduler.TRIGGER_DAILY_ALARM
            else -> return
        }
        val scheduler = app.preAnalysisScheduler
        val pendingResult = goAsync()
        scheduler.runNowWithCompletion(trigger, onFinished = { pendingResult.finish() })
        if (trigger == PreAnalysisScheduler.TRIGGER_DAILY_ALARM) {
            scheduler.scheduleDailyAlarm()
        }
    }

    companion object {
        /** 每日闲时闹钟 action（App 私有，PendingIntent 显式指向本 Receiver）。 */
        const val ACTION_PRE_ANALYSIS_ALARM = "com.mamba.picme.organize.PRE_ANALYSIS_ALARM"
    }
}
