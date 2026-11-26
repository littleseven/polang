package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore

/**
 * Koog [ChatHistoryProvider] 适配器：桥接 Koog ChatMemory feature ↔ [ChatMemoryStore]
 * （Android actual = DataStore 的 KoogMessageMemoryStore，见 shared androidMain）。
 *
 * Koog 在 `agent.run(input, sessionId)` 时按 sessionId 作为 runId（见 `AIAgentBase.run$suspendImpl`：
 * sessionId 非 null 即直接用作 run id；ChatMemory feature 经 `AIAgentContext.getRunId()` 取该 id
 * 调本接口的 load/store）→ 因此这里把 `id` 原样作为 DataStore 的 session key。
 *
 * - [load]：store 已做 decode + withoutSystem（不变式①）+ sanitizeToolPairing（不变式③）；
 *   返回前把 [currentSystemPrompt] 前置为 `Message.System`（Koog 1.3.0 ChatMemory
 *   历史非空即整表替换 prompt 消息的补偿，详见该属性 KDoc）。
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

    /**
     * 当前生效的 system prompt（宿主 agent 每次 buildAgent 时刷新为当次组装的成品）。
     *
     * 存在理由（Koog 1.3.0 坑位，2026-11-26 真机实锤）：ChatMemory feature 的合并逻辑是
     * `historyMessages.ifEmpty { initialMessages + historyMessages }`——历史**非空时整表替换**
     * prompt 消息（agents-features-memory `ChatMemory.installInternal`），initialMessages 里的
     * system prompt 被丢弃；而 store 不变式①（withoutSystem）恰在持久化/加载两侧剥离 system，
     * 两边叠加 = 会话一旦有历史，系统提示词（身份/契约/技能清单）整段丢失——新会话第一轮
     * 正常、第二轮起静默丢失。故 [load] 把当前 system prompt 重新前置；store 保存时
     * withoutSystem 自动剥离（不变式①），往返一致不重复。
     */
    @Volatile
    public var currentSystemPrompt: String? = null

    override suspend fun load(id: String): List<Message> {
        val history = store.load(id)
        val systemPrompt = currentSystemPrompt?.trim()?.ifEmpty { null } ?: return history
        return listOf(Message.System(systemPrompt, RequestMetaInfo.Empty)) + history
    }

    override suspend fun store(id: String, messages: List<Message>) {
        store.save(id, messages)
    }
}
