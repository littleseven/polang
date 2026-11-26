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
 * @param systemPrompt 当次 buildAgent 组装的成品 system prompt（不可变构造参数，接线由类型系统
 *   强制）。存在理由（Koog 1.3.0 坑位，2026-11-26 真机实锤）：ChatMemory feature 的合并逻辑是
 *   `historyMessages.ifEmpty { initialMessages + historyMessages }`——历史**非空时整表替换**
 *   prompt 消息（agents-features-memory `ChatMemory.installInternal`），initialMessages 里的
 *   system prompt 被丢弃；而 store 不变式①（withoutSystem）恰在持久化/加载两侧剥离 system，
 *   两边叠加 = 会话一旦有历史，系统提示词（身份/契约/技能清单）整段丢失——新会话第一轮
 *   正常、第二轮起静默丢失。故 [load] 把本参数重新前置；store 保存时 withoutSystem
 *   自动剥离（不变式①），往返一致不重复。宿主每次 buildAgent 新建本实例（轻量无状态），
 *   在途 run 持有自己 agent 安装的那份，不受其他 run 重建影响。
 *
 * - [load]：store 已做 decode + withoutSystem（不变式①）+ sanitizeToolPairing（不变式③）；
 *   返回前剥除历史残留 System 并把 [systemPrompt] 前置为唯一 `Message.System`
 *   （store 未守不变式①的防御：当次成品 prompt 恒胜，绝不双 System——多 System
 *   会被部分 OpenAI 兼容网关 400）。
 * - [store]：store 内部做 [KoogMessageMemory.assembleForPersistence]（M1：withoutSystem① + 老化旧 tool result
 *   + token 预算裁剪 + trimToMaxMessages 条数上限兜底②）+ 落盘。
 *
 * **窗口裁剪完全由 store 承担**：这里**不**给 ChatMemory feature 设 windowSize——避免 feature 做朴素
 * 计数裁剪拆散 tool_call 块（Call/Result 分属 Assistant/User 两条消息，朴素按条数裁会产生悬空 Call →
 * 远端 OpenAI 400 "tool_calls without tool results"）。🔴 windowSize 同样会把前置的 System 裁掉
 * （WindowSizePreProcessor.takeLast 朴素取尾），system prompt 前置方案与之不兼容，勿设。
 * store 的 [KoogMessageMemory.trimToMaxMessages]
 * 按原子块裁剪 + [KoogMessageMemory.sanitizeToolPairing] 双向配对兜底，三不变式逐条对齐旧 langchain4j 链路。
 */
public class KoogSessionHistoryProvider(
    private val store: ChatMemoryStore,
    private val systemPrompt: String,
) : ChatHistoryProvider {

    override suspend fun load(id: String): List<Message> {
        val history = store.load(id)
        val prompt = systemPrompt.trim()
        if (prompt.isEmpty()) return history
        // 防御：store 若未守不变式①漏出持久化的旧 System，剥掉再前置——保证有且仅有
        // 一条 System 且恒为当次成品 prompt（旧 prompt 缺契约/覆盖段，让它获胜等于
        // 静默回退到本修复要消灭的故障形态；双 System 则被部分 OpenAI 兼容网关 400）。
        val clean = history.filterNot { it is Message.System }
        return listOf(Message.System(prompt, RequestMetaInfo.Empty)) + clean
    }

    override suspend fun store(id: String, messages: List<Message>) {
        store.save(id, messages)
    }
}
