package com.mamba.picme.server.agent

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.toMessageResponse
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

private val strategyLogger = LoggerFactory.getLogger("picme-agent")

/**
 * 流式请求并组装完整响应（openui P2）。逐帧收集触发 EventHandler 流式钩子（openui 预览旁路的
 * 数据源）；收齐后经 `toMessageResponse()` 组装 Message.Assistant 并写回 prompt，与一次性
 * requestLLM 语义等价。流式失败回退一次性请求（重复计费可接受，轮次可用性优先）。
 */
private suspend fun AIAgentLLMWriteSession.requestLLMStreamingOrOnce(): Message.Assistant =
    try {
        requestLLMStreaming().toList().toMessageResponse().also { response ->
            appendPrompt { message(response) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        strategyLogger.warn("LLM 流式请求失败，回退一次性请求", e)
        requestLLM()
    }

/**
 * 云端单跑 ReAct 策略：自 :shared `poLangSingleRunStrategy`（KoogReActStrategy.kt）移植。
 *
 * 与 Koog 1.3.0 内建 singleRunStrategy 的差异：
 * 1. nodeSendToolResult 出边把 onToolCalls 声明在 onTextMessage **之前**——内建版先匹配文本，
 *    DeepSeek 惯用的「叙述文本 + tool_calls 同帧」响应会被静默丢弃工具调用（表现为「只说不做」）。
 *    1.3.0 未修，本策略继续必要。细节见 :shared 同名策略 KDoc。
 * 2. LLM 节点全部走流式（openui P2，2026-10-26）：`nodeLLMRequest`/`nodeLLMSendToolResults`
 *    内建版是一次性 `requestLLM()`，不产任何 StreamFrame——EventHandler 流式钩子永不触发。
 *    自定义节点改用 [requestLLMStreamingOrOnce]（Koog OpenAI 客户端把工具 args 增量以
 *    ToolCallDelta 逐帧透传），帧流驱动 AgentRunner 的 openui 预览旁路。
 * 3. correctInvalidProgram 节点（2026-11-01 回合原生 ProgramGuard；2026-11-06 GenUI 唯一化后
 *    无条件启用）：终结文本非合法 OpenUI 程序 → 本节点携带逐条校验错误清单回环重发
 *    （上限 [ProgramGuard.MAX_CORRECTIONS] 次）；上限用尽放行落流，由 AgentRunner 终结
 *    落流的 [OpenUiTurnNative.ensureValidProgram] 兜底包装。假发卡纠正节点
 *    correctFakeCardClaim 已随发卡工具全退役一并摘除（该线无发卡工具，
 *    纠正文案指向不存在的工具，触发即误导）。
 *
 * ⚠️ Koog 1.3.0 `onTextMessage { block }` 的 block 是**分片过滤器**（transform 语义：
 * 过滤后 join 成 String，空串也算命中），不是门控条件——条件必须写在后续级联的
 * `onCondition` 里，否则同一节点两条文本边中先声明者无条件捕获全部文本消息（事故记录）。
 */
internal fun cloudAgentStrategy(
    programGuard: ProgramGuard,
): AIAgentGraphStrategy<String, String> =
    strategy("polang_cloud_single_run") {
        val nodeCallLLM by node<String, Message.Assistant> { message ->
            llm.writeSession {
                appendPrompt {
                    user(message)
                }
                requestLLMStreamingOrOnce()
            }
        }
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by node<ReceivedToolResults, Message.Assistant> { input ->
            llm.writeSession {
                appendPrompt {
                    user {
                        input.toolResults.forEach { toolResult -> toolResult(toolResult.toMessagePart()) }
                    }
                }
                requestLLMStreamingOrOnce()
            }
        }
        // turn-native：终结程序非法 → 带错误清单回环纠正（上限 MAX_CORRECTIONS 防死循环）
        val nodeCorrectInvalidProgram by node<String, Message.Assistant>("correctInvalidProgram") { text ->
            programGuard.markCorrected()
            val error = RenderOpenUiValidator.validateProgramToError(text)
                ?: "未知校验错误（请检查 root 语句、组件白名单与 Query 形态）"
            llm.writeSession {
                appendPrompt {
                    user(ProgramGuard.correctionPrompt(error))
                }
                requestLLMStreamingOrOnce()
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            nodeCallLLM forwardTo nodeCorrectInvalidProgram
                onTextMessage { true }
                onCondition { text -> programGuard.invalidProgramError(text) != null },
        )
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 与内建版相反：先匹配 tool_calls 再兜底文本
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        // 程序校验边在 finish 边之前声明（先匹配者胜）
        edge(
            nodeSendToolResult forwardTo nodeCorrectInvalidProgram
                onTextMessage { true }
                onCondition { text -> programGuard.invalidProgramError(text) != null },
        )
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
        edge(nodeCorrectInvalidProgram forwardTo nodeExecuteTool onToolCalls { true })
        // 纠正后再输出仍非法：放行落流（ensureValidProgram 兜底包装）
        edge(nodeCorrectInvalidProgram forwardTo nodeFinish onTextMessage { true })
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
