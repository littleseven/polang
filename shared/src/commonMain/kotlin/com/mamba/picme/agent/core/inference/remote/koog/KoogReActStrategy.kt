package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls

/**
 * PoLang 单跑 ReAct 策略：与 Koog 1.1.1 内建 `singleRunStrategy` 同构，**唯一差异**是
 * `nodeSendToolResult` 出边把 `onToolCalls` 声明在 `onTextMessage` **之前**。
 *
 * 背景（真机 2026-08-07 实测复现，飞书 RPA 多轮工具场景）：
 * - Koog 的边匹配是「声明序优先」，`onTextMessage` 只要求响应**含** Text part，
 *   `onToolCalls` 要求含 Tool.Call part，两者对「叙述文本 + tool_calls 同帧」的响应**同时命中**。
 * - 内建策略在 `nodeSendToolResult` 出边先声明 `onTextMessage → nodeFinish`——DeepSeek 惯用
 *   「先叙述计划再发 tool_calls」的同帧响应，于是第二轮工具调用被静默丢弃，agent 把叙述文本
 *   当最终答复返回（表现为「只说不做」：回了『让我重试切换』却不执行）。
 * - `nodeCallLLM` 出边内建版本来就是 `onToolCalls` 在前（首轮工具循环正常），本策略保持。
 *
 * 注：叙述文本不会丢——onToolCalls 边只消费 Tool.Call part，Text part 仍由
 * EventHandler 的流式事件旁路到 onPartialText（用户可见）。
 *
 * 时效（2026-09-25 核实）：Koog 1.3.0 内建策略**仍未修**——nodeSendToolResult 出边
 * onTextMessage→nodeFinish 仍声明在 onToolCalls 之前（AIAgentSimpleStrategies.kt），
 * 谓词仍是「含 Text part 即命中」。本策略在 1.3.0 下继续必要，勿因升级误删。
 */
internal fun poLangSingleRunStrategy(): AIAgentGraphStrategy<String, String> =
    strategy("polang_single_run") {
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
