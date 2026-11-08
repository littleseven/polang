package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * 工具循环熔断器（端侧 chat / camera / feishu ReAct 循环共用，2026-11-08）。
 *
 * 回归背景：deepseek-v4-flash 在工具结果持续为空/报错时会原地打转（真机事故：「看看我
 * 最近拍的照片」连续 15 轮工具调用、每轮结果仅 14~20 token 的短文本，直到撞满 Koog
 * maxIterations=30 步硬顶，裸英文异常文案上屏）。模型打转时 maxIterations 只是兜底，
 * 需要在策略层提前识别「无进展循环」主动熔断。
 *
 * 判定双通道：
 * 1. **周期循环**：轮签名（工具名 + 输出采样）出现周期 ≤ [maxPeriod] 的序列且连续重复
 *    ≥ [minRepeats] 次——覆盖「同参重试」与「A→B→C 循环」两类打转；输出入签名，
 *    翻页/迭代式推进（同名工具结果递增）不会误判。
 * 2. **连续全错轮**：连续 [maxConsecutiveErrorRounds] 轮所有工具结果均失败——覆盖错误
 *    信息逐轮微变、签名校验抓不到的空转。
 *
 * 接线：策略工厂（[PoLangReActStrategyConfig.toolLoopGuard]）在 nodeExecuteTool 出边
 * 声明熔断边（先匹配者胜），命中即携 [TRIPPED_SENTINEL] 直达 nodeFinish；agent 包装层
 * （KoogChatAgent/KoogReActAgent）识别哨兵抛 [AgentLoopStuckException]，UI 层按类型映射
 * 本地化文案。哨兵不进会话记忆（finish 输出不回写 prompt）。
 *
 * 生命周期：agent 实例跨回合缓存复用，故 guard 为 per-agent 字段、每回合 [reset]；
 * runs 串行（调用方互斥守卫），内部状态无需并发保护。
 */
class ToolLoopGuard(
    private val maxPeriod: Int = 3,
    private val minRepeats: Int = 3,
    private val maxConsecutiveErrorRounds: Int = 6,
) {
    /** 一轮工具执行的可判定信号（与 Koog ReceivedToolResult 解耦，纯逻辑可测）。 */
    data class RoundSignal(val tool: String, val output: String, val isError: Boolean)

    private val roundSignatures = mutableListOf<String>()
    private var consecutiveErrorRounds = 0

    var tripped: Boolean = false
        private set

    /** 每轮工具结果回录 + 熔断判定。返回 true = 熔断（策略边据此导向终点哨兵）。 */
    fun checkAndRecord(signals: List<RoundSignal>): Boolean {
        if (tripped) return true
        consecutiveErrorRounds =
            if (signals.isNotEmpty() && signals.all { it.isError }) consecutiveErrorRounds + 1 else 0
        roundSignatures += signals.joinToString("|") {
            it.tool + ":" + it.output.take(MAX_OUTPUT_SIGNATURE_CHARS)
        }
        if (consecutiveErrorRounds >= maxConsecutiveErrorRounds || hasRepeatingCycle()) {
            tripped = true
        }
        return tripped
    }

    /** 新回合开始前复位（agent 跨回合复用，状态不得残留）。 */
    fun reset() {
        roundSignatures.clear()
        consecutiveErrorRounds = 0
        tripped = false
    }

    /** 尾部窗口是否由同一 period 块连续重复 [minRepeats] 次构成。 */
    private fun hasRepeatingCycle(): Boolean {
        for (period in 1..maxPeriod) {
            val window = period * minRepeats
            if (roundSignatures.size < window) continue
            val tail = roundSignatures.takeLast(window)
            val block = tail.subList(0, period)
            var repeats = true
            for (r in 1 until minRepeats) {
                if (tail.subList(r * period, (r + 1) * period) != block) {
                    repeats = false
                    break
                }
            }
            if (repeats) return true
        }
        return false
    }

    companion object {
        /** 轮签名中单个工具输出的最大采样字符（防超长 observation 拖慢比对）。 */
        const val MAX_OUTPUT_SIGNATURE_CHARS = 120

        /** 熔断命中时策略终点输出的哨兵：包装层识别后抛 [AgentLoopStuckException]，绝不直接上屏。 */
        const val TRIPPED_SENTINEL = "__polang_tool_loop_guard_tripped__"
    }
}

/**
 * Agent 工具循环熔断 / Koog 迭代上限的统一异常。message 仅供日志与未接线平台兜底展示
 * （iOS chat 错误文案未本地化的现状下兜底可读）；Android chat 按类型映射五语资源。
 */
class AgentLoopStuckException(message: String) : Exception(message)

/**
 * LLM 响应的日志摘要：正文 + 工具调用清单（原实现只取 textContent，纯工具调用轮全部记成
 * null——2026-11-08 循环事故复盘发现的观测盲区，事后无法还原模型在调什么）。
 * 文本优先，工具调用以 `toolCalls: name(args)` 尾随行追加；均空返回 null。
 */
internal fun summarizeResponseForLog(response: Message?): String? {
    response ?: return null
    val text = response.textContent().takeIf { it.isNotBlank() }
    val calls = (response as? Message.Assistant)
        ?.parts
        ?.filterIsInstance<MessagePart.Tool.Call>()
        ?.joinToString { "${it.tool}(${it.args.take(MAX_TOOL_ARGS_LOG_CHARS)})" }
        ?.takeIf { it.isNotBlank() }
    return listOfNotNull(text, calls?.let { "toolCalls: $it" })
        .joinToString("\n")
        .ifBlank { null }
}

/** 单个工具调用参数在日志中的最大字符（防 run_gallery_script 大脚本撑爆单条记录；外层另有 32KB cap）。 */
private const val MAX_TOOL_ARGS_LOG_CHARS = 300
