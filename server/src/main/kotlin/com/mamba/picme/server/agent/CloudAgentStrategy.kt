package com.mamba.picme.server.agent

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams

/**
 * 云端单跑 ReAct 策略：自 :shared `poLangSingleRunStrategy`（KoogReActStrategy.kt）移植。
 *
 * 与 Koog 1.3.0 内建 singleRunStrategy 的唯一差异：nodeSendToolResult 出边把 onToolCalls
 * 声明在 onTextMessage **之前**——内建版先匹配文本，DeepSeek 惯用的「叙述文本 + tool_calls
 * 同帧」响应会被静默丢弃工具调用（表现为「只说不做」）。1.3.0 未修，本策略继续必要。
 * 细节见 :shared 同名策略 KDoc。
 */
internal fun cloudAgentStrategy(): AIAgentGraphStrategy<String, String> =
    strategy("polang_cloud_single_run") {
        val nodeCallLLM by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResults()

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 与内建版相反：先匹配 tool_calls 再兜底文本
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
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
