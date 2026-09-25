@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.data.local.llmlog

import android.content.Context
import com.mamba.picme.agent.core.intent.RoutingAuditRecord
import com.mamba.picme.agent.core.intent.RoutingAuditRecorder
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Room 实现的 [RoutingAuditRecorder]：把每回合意图路由判定落库到独立库
 * polang_llm_log 的 routing_audit_log 表（spec §3.7 前半，路由可观测性）。
 *
 * - 在后台 IO 协程写入，**绝不阻塞路由判定主链路**（路由器在 chat 关键路径上）；
 * - 写入后做日级 guard 清理（仅保留最近 [KEEP] 条）；
 * - 任何异常吞掉只打日志，绝不冒泡到 chat 链路。
 *
 * 由 :androidApp 在 Application 启动时（全构建）注入到
 * [com.mamba.picme.agent.core.intent.IntentRouter.recorder]。
 */
class RoomRoutingAuditRecorder(
    context: Context
) : RoutingAuditRecorder {

    private val dao = LlmLogDatabase.getDatabase(context).routingAuditLogDao()
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun record(record: RoutingAuditRecord) {
        scope.launch {
            try {
                dao.insert(
                    RoutingAuditLogEntity(
                        createdAt = record.createdAt,
                        traceId = record.traceId,
                        path = record.path,
                        deliverable = record.deliverable,
                        confidence = record.confidence,
                        isRefinement = record.isRefinement,
                        latencyMs = record.latencyMs,
                        degradeReason = record.degradeReason?.take(DEGRADE_REASON_MAX_CHARS)
                    )
                )
                pruneIfNeeded()
            } catch (e: Exception) {
                Logger.w(TAG, "record failed", e)
            }
        }
    }

    /** 当天首次写入时清理一次，仅保留最近 [KEEP] 条。 */
    private suspend fun pruneIfNeeded() {
        val today = dayFormat.format(Date())
        if (prefs.getString(KEY_LAST_PRUNE_DAY, null) == today) return
        try {
            dao.prune(KEEP)
            prefs.edit().putString(KEY_LAST_PRUNE_DAY, today).apply()
        } catch (e: Exception) {
            Logger.w(TAG, "prune failed", e)
        }
    }

    companion object {
        private const val TAG = "PoLang:RoutingAudit"
        private const val PREFS_NAME = "polang_llm_log_prefs"

        /** 与 RoomLlmCallRecorder / RoomToolCallRecorder 区分，避免共享同一按天 prune 标记互相跳过。 */
        private const val KEY_LAST_PRUNE_DAY = "last_prune_day_routing"
        private const val KEEP = 200
        private const val DEGRADE_REASON_MAX_CHARS = 200
    }
}
