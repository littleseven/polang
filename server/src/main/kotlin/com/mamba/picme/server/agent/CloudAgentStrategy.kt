package com.mamba.picme.server.agent

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams
import com.mamba.picme.agent.core.inference.remote.koog.PoLangReActStrategyConfig
import com.mamba.picme.agent.core.inference.remote.koog.poLangSingleRunStrategy
import org.slf4j.LoggerFactory

private val strategyLogger = LoggerFactory.getLogger("picme-agent")

/**
 * 云端单跑 ReAct 策略：shared `poLangSingleRunStrategy` 的云端配置（分层 runtime M1
 * 收口——图结构/边序/流式节点/纠正节点全部同源，本函数只剩配置组装）。
 *
 * 云端配置 = 流式节点（openui 预览旁路数据源，2026-10-26 起：Koog OpenAI 客户端把
 * 工具 args 增量以 ToolCallDelta 逐帧透传，帧流驱动 AgentRunner 的 openui 预览旁路；
 * 流式失败回退一次性请求，重复计费可接受、轮次可用性优先）+ ProgramGuard 终结纠正
 * （turn-native，2026-11-06 GenUI 唯一化后无条件启用；上限 [ProgramGuard.MAX_CORRECTIONS]
 * 次，用尽放行落流，由 AgentRunner 终结落流的 [OpenUiTurnNative.ensureValidProgram]
 * 兜底包装——「端上永远收到合法树」双保险）。
 */
internal fun cloudAgentStrategy(
    programGuard: ProgramGuard,
): AIAgentGraphStrategy<String, String> =
    poLangSingleRunStrategy(
        PoLangReActStrategyConfig(
            name = "polang_cloud_single_run",
            streaming = true,
            finalOutputCorrector = programGuard.asCorrector(),
            onStreamingFallback = { e ->
                strategyLogger.warn("LLM 流式请求失败，回退一次性请求", e)
            },
        ),
    )

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
