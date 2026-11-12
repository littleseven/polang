package com.mamba.picme.data.local.llmlog

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 远程 LLM 调用日志实体（独立数据库 polang_llm_log，与主库 AppDatabase 零耦合）。
 *
 * 全构建写入；release 构建仅落纯指标（requestJson/responseJson 不含消息内容），
 * DEBUG 构建额外记录消息全文。保留最近 200 条。
 * 所有列均为普通列，无外键、无与其它表的关联。
 */
@Entity(tableName = "llm_call_log")
data class LlmCallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val source: String,
    val model: String?,
    val success: Boolean,
    val latencyMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val requestJson: String,
    val responseJson: String?,
    val errorMessage: String?,
    /** 关联 ID：一条用户消息一个 traceId；非 chat 来源/老数据为 null（详情页按无关联处理）。 */
    val traceId: String? = null,
    /**
     * 上游 prompt 前缀缓存命中 token 数（SSE usage 帧 `cached_tokens`，LlmWireTap 采集；
     * 纯指标，release 也落）。供应商/配置未回传 usage 帧时 null（v6 迁移前老数据同 null）。
     */
    val cachedTokens: Int? = null
)
