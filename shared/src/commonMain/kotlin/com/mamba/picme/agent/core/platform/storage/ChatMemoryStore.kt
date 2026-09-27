package com.mamba.picme.agent.core.platform.storage

import ai.koog.prompt.message.Message
import com.mamba.picme.agent.core.inference.remote.koog.SessionCompaction

/**
 * 对话记忆持久化抽象。Android actual = DataStore；iOS actual = NSUserDefaults。
 *
 * M2 扩展摘要存取（US-2.3）：[SessionCompaction] 是 L2 情节记忆的持久化形态，
 * 随会话落盘、会话恢复时重放（spec §4 US-2.3）。摘要键独立于历史键（`koog_summary_` 前缀），
 * 与历史互不清除——[clear] 会话销毁时才一并清（双端 actual 语义对齐）。
 */
interface ChatMemoryStore {
    suspend fun load(sessionId: String): List<Message>
    suspend fun save(sessionId: String, messages: List<Message>)
    suspend fun clear(sessionId: String)

    /** 加载会话摘要；无摘要（从未压缩过）返回 null，绝不因解析失败阻断对话（降级 null）。 */
    suspend fun loadSummary(sessionId: String): SessionCompaction?

    /** 保存会话摘要（覆盖同 session 旧版本；version 单调由调用方保证）。 */
    suspend fun saveSummary(sessionId: String, summary: SessionCompaction)

    /** 只清摘要（保留会话历史）；无摘要时幂等。 */
    suspend fun clearSummary(sessionId: String)
}
