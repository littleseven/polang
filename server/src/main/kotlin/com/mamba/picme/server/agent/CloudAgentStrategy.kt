package com.mamba.picme.server.agent

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams

/**
 * 云端单跑 ReAct 策略：自 :shared `poLangSingleRunStrategy`（KoogReActStrategy.kt）移植。
 *
 * 与 Koog 1.3.0 内建 singleRunStrategy 的差异：
 * 1. nodeSendToolResult 出边把 onToolCalls 声明在 onTextMessage **之前**——内建版先匹配文本，
 *    DeepSeek 惯用的「叙述文本 + tool_calls 同帧」响应会被静默丢弃工具调用（表现为「只说不做」）。
 *    1.3.0 未修，本策略继续必要。细节见 :shared 同名策略 KDoc。
 * 2. correctFakeCardClaim 节点（2026-10-24 假发卡修复）：LLM 以文本收尾时先过
 *    [CardClaimGuard]——声称发卡但当轮无卡工具 Action 过门控 → 不 Finish，由本节点追加
 *    user 纠正消息并就地重发 LLM 请求（上限 [CardClaimGuard.MAX_CORRECTIONS] 次，防死循环）。
 *    纠正后 LLM 可能真实发卡（tool_calls 回主循环）或给出改写文本（直接 Finish）。
 *
 * ⚠️ Koog 1.3.0 `onTextMessage { block }` 的 block 是**分片过滤器**（transform 语义：
 * 过滤后 join 成 String，空串也算命中），不是门控条件——条件必须写在后续级联的
 * `onCondition` 里，否则同一节点两条文本边中先声明者无条件捕获全部文本消息（事故记录）。
 */
internal fun cloudAgentStrategy(cardGuard: CardClaimGuard): AIAgentGraphStrategy<String, String> =
    strategy("polang_cloud_single_run") {
        val nodeCallLLM by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResults()
        val nodeCorrectFakeCard by node<String, Message.Assistant>("correctFakeCardClaim") {
            cardGuard.markCorrected()
            llm.writeSession {
                appendPrompt {
                    user(CardClaimGuard.CORRECTION_PROMPT)
                }
                requestLLM()
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            nodeCallLLM forwardTo nodeCorrectFakeCard
                onTextMessage { true }
                onCondition { cardGuard.needsCorrection(it) },
        )
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 与内建版相反：先匹配 tool_calls 再兜底文本
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            nodeSendToolResult forwardTo nodeCorrectFakeCard
                onTextMessage { true }
                onCondition { cardGuard.needsCorrection(it) },
        )
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
        edge(nodeCorrectFakeCard forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCorrectFakeCard forwardTo nodeFinish onTextMessage { true })
    }

/**
 * 组装云端 Agent 的初始 Prompt：system prompt + 事件流投影历史 + 推理参数。
 *
 * ⚠️ params 必须经 prompt DSL 的 `params` 形参烘焙（不能用 AIAgent.builder().systemPrompt()——
 * 它从空 Prompt 扩展，丢弃全部 params；:shared KoogPromptFactory 踩坑记录）。
 * 新一轮的用户输入由 agent.run(input) 追加，不在此重复（故投影先于此轮用户消息落流计算）。
 */
internal fun cloudAgentPrompt(
    id: String,
    systemPrompt: String,
    history: List<ProjectedMessage>,
    params: LLMParams,
): Prompt = prompt(id = id, params = params) {
    system(systemPrompt)
    history.forEach { m ->
        when (m) {
            is ProjectedMessage.System -> system(m.content)
            is ProjectedMessage.User -> user(m.content)
            is ProjectedMessage.Assistant -> assistant(m.content)
        }
    }
}
