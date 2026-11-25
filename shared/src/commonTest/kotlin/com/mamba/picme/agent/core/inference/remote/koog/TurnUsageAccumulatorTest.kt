package com.mamba.picme.agent.core.inference.remote.koog

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * U2-1：回合 token 用量累加器——流式 End / 非流式 CallCompleted 两路入口、同一计数面
 * （互斥由 Koog 事件语义保证：流式轮只出 StreamFrame.End、非流式轮只出
 * onLLMCallCompleted，server 0.9.17 实证；本类纯累加不做去重）。
 */
class TurnUsageAccumulatorTest {

    @Test
    fun `streaming and completed paths accumulate into same counters`() {
        val acc = TurnUsageAccumulator()
        acc.onStreamingEnd(inputTokens = 10, outputTokens = 20)
        acc.onCallCompleted(inputTokens = 1, outputTokens = 2)
        assertEquals(11, acc.promptTokens)
        assertEquals(22, acc.completionTokens)
    }

    @Test
    fun `partial usage feeds only its own counter`() {
        val acc = TurnUsageAccumulator()
        acc.onStreamingEnd(inputTokens = 5, outputTokens = null)
        acc.onCallCompleted(inputTokens = null, outputTokens = 7)
        assertEquals(5, acc.promptTokens)
        assertEquals(7, acc.completionTokens)
    }

    @Test
    fun `null usage on both sides is a no-op`() {
        val acc = TurnUsageAccumulator()
        acc.onStreamingEnd(inputTokens = null, outputTokens = null)
        acc.onCallCompleted(inputTokens = null, outputTokens = null)
        assertEquals(0, acc.promptTokens)
        assertEquals(0, acc.completionTokens)
    }

    @Test
    fun `reset clears both counters for next run`() {
        val acc = TurnUsageAccumulator()
        acc.onStreamingEnd(inputTokens = 100, outputTokens = 200)
        acc.onCallCompleted(inputTokens = 3, outputTokens = 4)
        acc.reset()
        assertEquals(0, acc.promptTokens)
        assertEquals(0, acc.completionTokens)
        acc.onCallCompleted(inputTokens = 9, outputTokens = 9)
        assertEquals(9, acc.promptTokens)
        assertEquals(9, acc.completionTokens)
    }
}
