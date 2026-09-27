package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore

/**
 * Koog [ChatHistoryProvider] 适配器：桥接 Koog ChatMemory feature ↔ [ChatMemoryStore]
 * （Android actual = DataStore 的 KoogMessageMemoryStore，见 shared androidMain）。
 *
 * Koog 在 `agent.run(input, sessionId)` 时按 sessionId 作为 runId（见 `AIAgentBase.run$suspendImpl`：
 * sessionId 非 null 即直接用作 run id；ChatMemory feature 经 `AIAgentContext.getRunId()` 取该 id
 * 调本接口的 load/store）→ 因此这里把 `id` 原样作为 DataStore 的 session key。
 *
 * - [load]：store 已做 decode + withoutSystem（不变式①）+ sanitizeToolPairing（不变式③）。
 * - [store]：store 内部做 [KoogMessageMemory.assembleForPersistence]（M1：withoutSystem① + 老化旧 tool result
 *   + token 预算裁剪 + trimToMaxMessages 条数上限兜底②）+ 落盘。
 *
 * **窗口裁剪完全由 store 承担**：这里**不**给 ChatMemory feature 设 windowSize——避免 feature 做朴素
 * 计数裁剪拆散 tool_call 块（Call/Result 分属 Assistant/User 两条消息，朴素按条数裁会产生悬空 Call →
 * 远端 OpenAI 400 "tool_calls without tool results"）。store 的 [KoogMessageMemory.trimToMaxMessages]
 * 按原子块裁剪 + [KoogMessageMemory.sanitizeToolPairing] 双向配对兜底，三不变式逐条对齐旧 langchain4j 链路。
 */
public class KoogSessionHistoryProvider(
    private val store: ChatMemoryStore,
) : ChatHistoryProvider {

    override suspend fun load(id: String): List<Message> = store.load(id)

    override suspend fun store(id: String, messages: List<Message>) {
        store.save(id, messages)
    }
}
