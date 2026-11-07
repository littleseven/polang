package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.toMessageResponse
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.toList

/**
 * 终结输出纠正钩子（分层 runtime M1；每轮新实例，实现内部自管纠正计数）。
 * server `ProgramGuard`（OpenUI 回合原生）经 `asCorrector()` 适配接入；端侧 chat 传 null。
 */
interface FinalOutputCorrector {
    /** 边门控：纠正次数未达上限且输出非法 → 错误清单；null = 放行落流。 */
    fun correctionGateError(assistantText: String): String?

    /** 纠正节点内取原始校验错误（不做计数门控——节点只经门控边进入，先 markCorrected 后取）。 */
    fun rawValidationError(assistantText: String): String?

    /** 纠正轮次标记（实现内部计数防死循环）。 */
    fun markCorrected()

    /** 回灌给 LLM 的纠正 prompt（携带逐条错误清单）。 */
    fun correctionPrompt(error: String): String
}

/**
 * ReAct 策略配置（分层 runtime M1：端云共用同一策略工厂，差异仅配置）。
 *
 * @param name 策略名（Koog 图标识；端侧默认 "polang_single_run"，云端 "polang_cloud_single_run"）
 * @param streaming true = LLM 节点走流式（逐帧触发 EventHandler 流式钩子，openui 预览旁路的
 *   数据源），失败回退一次性请求；false = 一次性 `requestLLM()`
 * @param finalOutputCorrector 非 null 时挂 correctInvalidProgram 纠正节点
 *   （先匹配者胜，纠正边声明在 finish 边之前）
 * @param onStreamingFallback 流式失败回退一次性请求时的告警出口（server=slf4j；null=静默）
 */
data class PoLangReActStrategyConfig(
    val name: String = "polang_single_run",
    val streaming: Boolean = false,
    val finalOutputCorrector: FinalOutputCorrector? = null,
    val onStreamingFallback: ((Throwable) -> Unit)? = null,
)

/**
 * PoLang 单跑 ReAct 策略（分层 runtime M1：端云共用工厂，server `cloudAgentStrategy`
 * 经 [PoLangReActStrategyConfig] 同源消费）。
 *
 * 与 Koog 内建 singleRunStrategy 的差异：`nodeSendToolResult` 出边把 `onToolCalls`
 * 声明在 `onTextMessage` **之前**——内建版先匹配文本，DeepSeek 惯用的「叙述文本 +
 * tool_calls 同帧」响应会被静默丢弃工具调用，agent 把叙述文本当最终答复返回
 * （表现为「只说不做」；真机 2026-08-07 实测复现，飞书 RPA 多轮工具场景）。
 *
 * 背景细节：Koog 的边匹配是「声明序优先」，`onTextMessage` 只要求响应**含** Text part，
 * `onToolCalls` 要求含 Tool.Call part，两者对「叙述文本 + tool_calls 同帧」的响应
 * **同时命中**。叙述文本不会丢——onToolCalls 边只消费 Tool.Call part，Text part 仍由
 * EventHandler 的流式事件旁路到 onPartialText（用户可见）。
 *
 * 时效（2026-09-25 核实）：Koog 1.3.0 内建策略**仍未修**——nodeSendToolResult 出边
 * onTextMessage→nodeFinish 仍声明在 onToolCalls 之前（AIAgentSimpleStrategies.kt），
 * 谓词仍是「含 Text part 即命中」。本策略在 1.3.0 下继续必要，勿因升级误删。
 *
 * 默认配置（非流式 + 无纠正）= 端侧 chat 现行路径，保留内建节点实现逐字节一致；
 * 非默认配置 = 云端路径（server `cloudAgentStrategy` 原实现收口）：流式节点 + 可选
 * correctInvalidProgram 纠正节点（OpenUI 回合原生 ProgramGuard，2026-11-06 起云端
 * 无条件启用）。
 *
 * ⚠️ Koog 1.3.0 `onTextMessage { block }` 的 block 是**分片过滤器**（transform 语义：
 * 过滤后 join 成 String，空串也算命中），不是门控条件——条件必须写在后续级联的
 * `onCondition` 里，否则同一节点两条文本边中先声明者无条件捕获全部文本消息（事故记录）。
 */
fun poLangSingleRunStrategy(
    config: PoLangReActStrategyConfig = PoLangReActStrategyConfig(),
): AIAgentGraphStrategy<String, String> {
    if (!config.streaming && config.finalOutputCorrector == null) {
        return builtinOneShotStrategy(config.name)
    }
    return configurableStrategy(config)
}

/** 端侧现行路径：内建节点 + 修复后的边序（与 M1 前实现逐字节一致）。 */
private fun builtinOneShotStrategy(name: String): AIAgentGraphStrategy<String, String> =
    strategy(name) {
        val nodeCallLLM by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResults()

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 🔧 修复点：与内建版相反，先匹配 tool_calls 再兜底文本（见上方 KDoc）。
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
    }

/**
 * 云端路径（server `cloudAgentStrategy` 原实现，分层 runtime M1 收口）：自定义节点支持
 * 流式请求 + 可选终结纠正节点。纠正边声明序先于 finish 边（先匹配者胜）。
 */
private fun configurableStrategy(config: PoLangReActStrategyConfig): AIAgentGraphStrategy<String, String> {
    val corrector = config.finalOutputCorrector
    return if (corrector != null) guardedStrategy(config, corrector) else plainConfigurableStrategy(config)
}

/** 自定义节点无纠正版（streaming=true + corrector=null；当前无消费方，为配置闭集完整性保留）。 */
private fun plainConfigurableStrategy(config: PoLangReActStrategyConfig): AIAgentGraphStrategy<String, String> =
    strategy(config.name) {
        val nodeCallLLM by node<String, Message.Assistant>("callLLM") { message ->
            llm.writeSession {
                appendPrompt {
                    user(message)
                }
                requestLLMMaybeStreaming(config)
            }
        }
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by node<ReceivedToolResults, Message.Assistant>("sendToolResult") { input ->
            llm.writeSession {
                appendPrompt {
                    user {
                        input.toolResults.forEach { toolResult -> toolResult(toolResult.toMessagePart()) }
                    }
                }
                requestLLMMaybeStreaming(config)
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 与内建版相反：先匹配 tool_calls 再兜底文本
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
    }

/** 自定义节点 + correctInvalidProgram 纠正节点（server `cloudAgentStrategy` 原图逐点对应）。 */
private fun guardedStrategy(
    config: PoLangReActStrategyConfig,
    corrector: FinalOutputCorrector,
): AIAgentGraphStrategy<String, String> =
    strategy(config.name) {
        val nodeCallLLM by node<String, Message.Assistant>("callLLM") { message ->
            llm.writeSession {
                appendPrompt {
                    user(message)
                }
                requestLLMMaybeStreaming(config)
            }
        }
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by node<ReceivedToolResults, Message.Assistant>("sendToolResult") { input ->
            llm.writeSession {
                appendPrompt {
                    user {
                        input.toolResults.forEach { toolResult -> toolResult(toolResult.toMessagePart()) }
                    }
                }
                requestLLMMaybeStreaming(config)
            }
        }
        // turn-native：终结输出非法 → 带错误清单回环纠正（实现内计数上限防死循环）
        val nodeCorrectInvalidProgram by node<String, Message.Assistant>("correctInvalidProgram") { text ->
            corrector.markCorrected()
            val error = corrector.rawValidationError(text) ?: "未知校验错误（请检查输出契约）"
            llm.writeSession {
                appendPrompt {
                    user(corrector.correctionPrompt(error))
                }
                requestLLMMaybeStreaming(config)
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            nodeCallLLM forwardTo nodeCorrectInvalidProgram
                onTextMessage { true }
                onCondition { text -> corrector.correctionGateError(text) != null },
        )
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 与内建版相反：先匹配 tool_calls 再兜底文本
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        // 程序校验边在 finish 边之前声明（先匹配者胜）
        edge(
            nodeSendToolResult forwardTo nodeCorrectInvalidProgram
                onTextMessage { true }
                onCondition { text -> corrector.correctionGateError(text) != null },
        )
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
        edge(nodeCorrectInvalidProgram forwardTo nodeExecuteTool onToolCalls { true })
        // 纠正后再输出仍非法：放行落流（终结兜底变换包装）
        edge(nodeCorrectInvalidProgram forwardTo nodeFinish onTextMessage { true })
    }

/**
 * 流式请求并组装完整响应（云端路径）。逐帧收集触发 EventHandler 流式钩子；收齐后经
 * `toMessageResponse()` 组装 Message.Assistant 并写回 prompt，与一次性 requestLLM
 * 语义等价。流式失败回退一次性请求（重复计费可接受，轮次可用性优先）。
 */
private suspend fun AIAgentLLMWriteSession.requestLLMMaybeStreaming(
    config: PoLangReActStrategyConfig,
): Message.Assistant {
    if (!config.streaming) return requestLLM()
    return try {
        requestLLMStreaming().toList().toMessageResponse().also { response ->
            appendPrompt { message(response) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        config.onStreamingFallback?.invoke(e)
        requestLLM()
    }
}
