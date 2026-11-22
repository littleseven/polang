package com.mamba.picme.service.organize

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.mamba.picme.PoLangApplication
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.repository.MediaMetadataBackfiller
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanState
import com.mamba.picme.domain.organize.PreAnalysisConditions
import com.mamba.picme.domain.organize.PreAnalysisDecision
import com.mamba.picme.domain.organize.PreAnalysisInputs
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.usertask.DedupScanTaskAdapter
import com.mamba.picme.domain.usertask.UserTaskStatus
import com.mamba.picme.service.dedup.DedupScanService
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 后台预分析调度器（Agent 清理 M1 Task 6）：充电 + 闲置窗口内推进
 * 「media_assets 元数据回填 → 去重扫描触发」链，为 Agent 相册清理备数据。
 *
 * ## 触发链
 * - [PreAnalysisReceiver]（插电 `ACTION_POWER_CONNECTED` / 每日 03:00 闲时闹钟）→ [runNow]；
 * - 手动入口接缝 = [TRIGGER_MANUAL]（hub「立即分析」，Task 7 接线）。
 *
 * ## 条件守卫
 * 全部采集进 [PreAnalysisInputs] 交 [PreAnalysisConditions.decide] 纯函数裁决：
 * 充电 = sticky `ACTION_BATTERY_CHANGED`；亮屏 = `PowerManager.isInteractive`；
 * TAG 活跃 = [TagGenerationService.isScanning]；去重活跃 = [DedupScanService.sessionProgress]；
 * 上次去重完成 = DEDUP_SCAN 用户任务行（COMPLETED 终态的 completedAt，Room 持久化）。
 *
 * ## 结束记录
 * 实际执行（非 SKIP）后经 [UserSettingsRepository.updateOrganizePreAnalysisRanAt] 落
 * `organize_pre_analysis_ran_at_ms`（0 = 从未），供 hub/诊断读。
 */
class PreAnalysisScheduler(
    private val context: Context,
    private val backfiller: MediaMetadataBackfiller,
    private val settingsRepository: UserSettingsRepository,
    private val scope: CoroutineScope,
) {

    /**
     * 评估并按需执行一次预分析（挂起直到链条完成；全程不抛——异常降级为日志，
     * [scope] 常为无 CEH 的 applicationScope，抛出即崩溃）。
     */
    @Suppress("TooGenericExceptionCaught") // 广播/手动入口兜底语义：任何失败降级为日志，不穿透 applicationScope
    suspend fun runNow(trigger: String) {
        try {
            runNowInternal(trigger)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.e(TAG, "pre-analysis runNow failed, trigger=$trigger", error)
        }
    }

    /**
     * goAsync 桥（[PreAnalysisReceiver] 专用）：在调度器自身 [scope] 上执行 [runNow]，
     * 结束（含异常/取消）后回调 [onFinished]（消费 pendingResult.finish()）。
     */
    fun runNowWithCompletion(trigger: String, onFinished: () -> Unit) {
        scope.launch {
            try {
                runNow(trigger)
            } finally {
                onFinished()
            }
        }
    }

    private suspend fun runNowInternal(trigger: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastPreAnalysisRanAt = readLastPreAnalysisRanAt()
        val inputs = collectInputs(now)
        val decision = PreAnalysisConditions.decide(inputs)
        Logger.i(
            TAG,
            "pre-analysis [$trigger]: charging=${inputs.isCharging} screenOn=${inputs.isScreenOn} " +
                "tagActive=${inputs.tagScanActive} dedupActive=${inputs.dedupScanActive} " +
                "lastDedup=${inputs.lastDedupScanCompletedAt} lastRanAt=$lastPreAnalysisRanAt -> $decision",
        )
        when (decision) {
            PreAnalysisDecision.SKIP -> return@withContext
            PreAnalysisDecision.RUN_METADATA_ONLY -> runMetadataBackfill(trigger)
            PreAnalysisDecision.RUN_METADATA_AND_DEDUP -> {
                runMetadataBackfill(trigger)
                triggerDedupScan()
            }
        }
        settingsRepository.updateOrganizePreAnalysisRanAt(now)
    }

    /**
     * 排每日闲时闹钟（本地 03:00；已过则顺延明日）。onCreate 每次启动重排 +
     * Receiver 消费后重排维持日节奏。精确闹钟按 [AlarmManager.canScheduleExactAlarms]
     * 守卫（判法同 TagScanRescheduleReceiver），未授权降级 inexact 不抛 SecurityException。
     */
    fun scheduleDailyAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Logger.w(TAG, "AlarmManager unavailable, cannot schedule daily pre-analysis")
            return
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, PreAnalysisReceiver::class.java)
                .setAction(PreAnalysisReceiver.ACTION_PRE_ANALYSIS_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAtMs = nextDailyWindowAtMs(System.currentTimeMillis())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
            Logger.i(TAG, "daily pre-analysis alarm scheduled at $triggerAtMs")
        } catch (error: SecurityException) {
            Logger.w(TAG, "schedule daily pre-analysis alarm rejected", error)
        }
    }

    // ── 条件采集 ──────────────────────────────────────────────

    private suspend fun collectInputs(now: Long): PreAnalysisInputs {
        val appContext = context.applicationContext
        return PreAnalysisInputs(
            isCharging = readChargingState(appContext),
            isScreenOn = readInteractiveState(appContext),
            tagScanActive = TagGenerationService.isScanning.value,
            dedupScanActive = isDedupSessionActive(),
            lastDedupScanCompletedAt = readLastDedupScanCompletedAt(appContext),
            now = now,
        )
    }

    /** sticky `ACTION_BATTERY_CHANGED` 直读（registerReceiver(null, …) 只查询不注册）。 */
    private fun readChargingState(appContext: Context): Boolean {
        val batteryStatus = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun readInteractiveState(appContext: Context): Boolean {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isInteractive
    }

    /** 去重会话活性：RUNNING/PAUSED 均视为进行中（重入触发会被 Service 忽略，主动不触发）。 */
    private fun isDedupSessionActive(): Boolean {
        val state = DedupScanService.sessionProgress.value?.state
        return state == DedupScanState.RUNNING || state == DedupScanState.PAUSED
    }

    /**
     * 上次去重扫描完成时间：读 DEDUP_SCAN 用户任务行（Task 5 落库）的 completedAt。
     * 只认 COMPLETED 终态（FAILED/CANCELLED 视为无有效结果 → 过期 → 重扫）；
     * 非本 App 进程上下文（单测/预览）读不到 container，返回 null 视为过期，安全偏保守。
     */
    @Suppress("TooGenericExceptionCaught") // Room 读失败按「无完成记录 → 过期 → 重扫」降级，保守安全
    private suspend fun readLastDedupScanCompletedAt(appContext: Context): Long? {
        val container = (appContext as? PoLangApplication)?.container ?: return null
        return try {
            container.database.userTaskDao()
                .getById(DedupScanTaskAdapter.TASK_ID)
                ?.takeIf { row -> row.status == UserTaskStatus.COMPLETED.name }
                ?.completedAt
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.w(TAG, "read dedup scan completedAt failed, treat as stale", error)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught") // DataStore 读失败仅损失日志上下文（lastRanAt=0），不阻断决策
    private suspend fun readLastPreAnalysisRanAt(): Long = try {
        settingsRepository.organizePreAnalysisRanAtMsFlow.first()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Logger.w(TAG, "read lastPreAnalysisRanAt failed", error)
        0L
    }

    // ── 执行链 ────────────────────────────────────────────────

    /** 元数据回填：失败降级为日志（不阻断后续去重触发与 ranAt 记录）。 */
    @Suppress("TooGenericExceptionCaught") // 回填失败仅损失本轮增量，链条其余环节照常推进
    private suspend fun runMetadataBackfill(trigger: String) {
        val written = try {
            backfiller.runUntilDone()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.w(TAG, "metadata backfill failed, trigger=$trigger", error)
            0
        }
        if (written > 0) Logger.i(TAG, "metadata backfill done: $written rows, trigger=$trigger")
    }

    /**
     * 触发去重扫描（默认档位 EXACT+VISUAL）。A12+ 后台 FGS 启动限制：闲时闹钟触发时
     * app 多在后台，startForegroundService 可能抛 ForegroundServiceStartNotAllowedException
     * ——本轮放弃去重（dedup_hash 缓存保证下次增量续扫），不 crash。
     */
    private fun triggerDedupScan() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                DedupScanService.intentStart(context, DedupScanConfig()),
            )
        }.onFailure { error ->
            Logger.w(TAG, "trigger dedup scan dropped (fgs start not allowed?)", error)
        }
    }

    /** 下一个本地 03:00 闲时窗口起点（已过则顺延明日）。 */
    private fun nextDailyWindowAtMs(nowMs: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, DAILY_ALARM_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    companion object {
        private const val TAG = "PoLang:PreAnalysis"

        /** AlarmManager 请求码（10043 = TAG 续跑闹钟、10044 = 去重通知 id，均已占用）。 */
        private const val ALARM_REQUEST_CODE = 10045

        /** 每日闲时窗口：本地 03:00（用户大概率息屏且夜间充电）。 */
        private const val DAILY_ALARM_HOUR = 3

        /** 触发源标识（runNow 日志与诊断口径）。 */
        const val TRIGGER_POWER_CONNECTED = "power_connected"
        const val TRIGGER_DAILY_ALARM = "daily_alarm"
        const val TRIGGER_MANUAL = "manual"
    }
}
