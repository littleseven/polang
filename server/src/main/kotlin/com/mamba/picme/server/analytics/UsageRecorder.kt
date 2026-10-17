package com.mamba.picme.server.analytics

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

/**
 * 写一条 llm_call_log。llmRoute 的四条出口路径（ok / blocked_quota /
 * blocked_rate / upstream_error）都调用它。
 */
object UsageRecorder {
    suspend fun log(
        accountId: Int,
        model: String,
        provider: String,
        usage: TokenUsage?,
        respBytes: Int,
        status: String,
        latencyMs: Int?,
        prices: Map<String, Price>,
        deviceId: String? = null,
        platform: String? = null,
        now: Long = Instant.now().toEpochMilli(),
    ) {
        // insert{} 的接收者是 LlmCallLogs 表，bare costCny 会指向列；这里先算好成本。
        val cost = costCny(usage, model, prices)
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = accountId
                it[LlmCallLogs.model] = model
                it[LlmCallLogs.provider] = provider
                it[LlmCallLogs.promptTokens] = usage?.prompt
                it[LlmCallLogs.completionTokens] = usage?.completion
                it[LlmCallLogs.totalTokens] = usage?.total
                it[LlmCallLogs.cachedTokens] = usage?.cached
                it[LlmCallLogs.costCny] = cost
                it[LlmCallLogs.respBytes] = respBytes
                it[LlmCallLogs.status] = status
                it[LlmCallLogs.latencyMs] = latencyMs
                it[LlmCallLogs.deviceId] = deviceId
                it[LlmCallLogs.platform] = platform
                it[LlmCallLogs.createdAt] = now
            }
        }
    }
}
