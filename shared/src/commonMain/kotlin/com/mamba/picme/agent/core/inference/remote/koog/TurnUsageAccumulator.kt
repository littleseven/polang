package com.mamba.picme.agent.core.inference.remote.koog

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 回合 token 用量累加器（U2-1：本地回合切 turn-native 后，流式/非流式两路 metrics 的
 * 统一落点，替代 agent 层直读 AtomicInt）。
 *
 * 两路入口对应 Koog 1.3.0 事件语义：流式轮只发 `StreamFrame.End`（不触发
 * `onLLMCallCompleted`）、非流式轮只发 `onLLMCallCompleted`——互斥由事件语义保证
 * （server 0.9.17 实证），本类是纯累加器，不做跨轮去重。null usage 不计。
 *
 * 生命周期：per-agent 持有、每回合 [reset]（runChat 开头，与 `toolLoopGuard.reset()`
 * 同点位——调用方接线属后续任务，本类只交付语义）。计数用 AtomicInt：流式事件来自
 * 网络线程、终值读取在调用线程（KoogChatAgent / OpenUiProgramGuard 同款先例）。
 */
@OptIn(ExperimentalAtomicApi::class)
class TurnUsageAccumulator {

    private val prompt = AtomicInt(0)
    private val completion = AtomicInt(0)

    /** 累计输入 token（两路合计；0 = 本回合未收到任何 usage 信息）。 */
    val promptTokens: Int get() = prompt.load()

    /** 累计输出 token（两路合计）。 */
    val completionTokens: Int get() = completion.load()

    /** 流式轮终结入口（`StreamFrame.End` 的 usage）。 */
    fun onStreamingEnd(inputTokens: Int?, outputTokens: Int?) {
        inputTokens?.let { prompt.addAndFetch(it) }
        outputTokens?.let { completion.addAndFetch(it) }
    }

    /** 非流式轮完成入口（`onLLMCallCompleted` 的 usage）。 */
    fun onCallCompleted(inputTokens: Int?, outputTokens: Int?) {
        inputTokens?.let { prompt.addAndFetch(it) }
        outputTokens?.let { completion.addAndFetch(it) }
    }

    /** 新回合开始前复位（per-run 状态不得跨回合残留）。 */
    fun reset() {
        prompt.store(0)
        completion.store(0)
    }
}
