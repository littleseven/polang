@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.data.local.llmlog

import android.content.Context
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecord
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecorder
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Room 实现的 [LlmCallRecorder]：把每次远程 LLM 调用落库到独立库 llm_call_log。
 *
 * - 在后台 IO 协程写入，**绝不阻塞 LLM 主调用**；
 * - 写入后做日级 guard 清理（仅保留最近 [KEEP] 条）——"每天检查一次即可"；
 * - 任何异常吞掉只打日志，绝不冒泡到 LLM 调用链。
 *
 * 由 :androidApp 在 Application 启动时（全构建）注入到
 * [com.mamba.picme.agent.core.remote.config.RemoteModelFactory.recorder]；
 * release 构建 captureContent=false，仅落纯指标，不落消息内容。
 */
class RoomLlmCallRecorder(
    context: Context
) : LlmCallRecorder {

    private val dao = LlmLogDatabase.getDatabase(context).llmCallLogDao()
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun record(record: LlmCallRecord) {
        scope.launch {
            try {
                dao.insert(
                    LlmCallLogEntity(
                        createdAt = record.createdAt,
                        source = record.source,
                        model = record.model,
                        success = record.success,
                        latencyMs = record.latencyMs,
                        promptTokens = record.promptTokens,
                        completionTokens = record.completionTokens,
                        totalTokens = record.totalTokens,
                        requestJson = LlmCallRecord.cap(record.requestJson) ?: "{}",
                        responseJson = LlmCallRecord.cap(record.responseJson),
                        errorMessage = record.errorMessage,
                        traceId = record.traceId,
                        cachedTokens = record.cachedTokens
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
        private const val TAG = "PoLang:LlmCallLog"
        private const val PREFS_NAME = "polang_llm_log_prefs"
        private const val KEY_LAST_PRUNE_DAY = "last_prune_day"
        private const val KEEP = 200
    }
}
