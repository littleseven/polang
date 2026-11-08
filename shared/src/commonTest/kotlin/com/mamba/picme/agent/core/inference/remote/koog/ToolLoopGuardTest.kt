package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ToolLoopGuard] 熔断判定单测（纯逻辑，与 Koog 类型解耦）。
 *
 * 回归背景（2026-11-08 真机事故）：「看看我最近拍的照片」端侧 agent loop 连续 15 轮
 * 工具调用、每轮结果 14~20 token 短文本打转，直到撞 Koog maxIterations×3=30 步硬顶、
 * 英文异常原文上屏。熔断器须在步数硬顶之前识别无进展循环。
 */
class ToolLoopGuardTest {

    private fun sig(tool: String, output: String, isError: Boolean = false) =
        ToolLoopGuard.RoundSignal(tool, output, isError)

    // ── 判据 1：周期循环 ────────────────────────────────────────

    @Test
    fun `same tool same output three rounds trips period 1`() {
        val guard = ToolLoopGuard()
        assertFalse(guard.checkAndRecord(listOf(sig("search_media", "[]"))))
        assertFalse(guard.checkAndRecord(listOf(sig("search_media", "[]"))))
        assertTrue(guard.checkAndRecord(listOf(sig("search_media", "[]"))))
        assertTrue(guard.tripped)
    }

    @Test
    fun `alternating two-round pattern trips period 2`() {
        val guard = ToolLoopGuard()
        val a = listOf(sig("search_media", "[]"))
        val b = listOf(sig("gallery_summary", "{}"))
        assertFalse(guard.checkAndRecord(a))
        assertFalse(guard.checkAndRecord(b))
        assertFalse(guard.checkAndRecord(a))
        assertFalse(guard.checkAndRecord(b))
        assertFalse(guard.checkAndRecord(a))
        // 第 6 轮完成 A-B 第 3 次重复 → 熔断
        assertTrue(guard.checkAndRecord(b))
    }

    @Test
    fun `three-round pattern trips period 3`() {
        val guard = ToolLoopGuard()
        val cycle = listOf(
            listOf(sig("t1", "o1")),
            listOf(sig("t2", "o2")),
            listOf(sig("t3", "o3")),
        )
        var tripped = false
        repeat(3) { round ->
            cycle.forEach { signals -> tripped = guard.checkAndRecord(signals) }
            if (round < 2) assertFalse(tripped)
        }
        assertTrue(tripped)
    }

    @Test
    fun `only two repetitions do not trip`() {
        val guard = ToolLoopGuard()
        assertFalse(guard.checkAndRecord(listOf(sig("search_media", "[]"))))
        assertFalse(guard.checkAndRecord(listOf(sig("search_media", "[]"))))
        assertFalse(guard.tripped)
    }

    @Test
    fun `same tool with progressing output does not trip for pagination`() {
        val guard = ToolLoopGuard()
        // 翻页式推进：同名工具但输出递增，入签名后不构成循环
        repeat(10) { i ->
            assertFalse(guard.checkAndRecord(listOf(sig("search_media", "page=$i"))))
        }
    }

    // ── 判据 2：连续全错轮 ─────────────────────────────────────

    @Test
    fun `six consecutive all-error rounds trip`() {
        val guard = ToolLoopGuard()
        repeat(5) { i ->
            // 错误文本逐轮微变（绕开周期签名），仅靠全错判据
            assertFalse(guard.checkAndRecord(listOf(sig("search_media", "error $i", isError = true))))
        }
        assertTrue(guard.checkAndRecord(listOf(sig("search_media", "error 5", isError = true))))
    }

    @Test
    fun `success round resets consecutive error streak`() {
        val guard = ToolLoopGuard()
        repeat(5) { i ->
            guard.checkAndRecord(listOf(sig("t", "err $i", isError = true)))
        }
        assertFalse(guard.checkAndRecord(listOf(sig("t", "ok"))))
        repeat(5) { i ->
            assertFalse(guard.checkAndRecord(listOf(sig("t", "err again $i", isError = true))))
        }
    }

    @Test
    fun `mixed success and error in one round is not an error round`() {
        val guard = ToolLoopGuard()
        repeat(8) { i ->
            assertFalse(
                guard.checkAndRecord(
                    listOf(
                        sig("t1", "boom $i", isError = true),
                        sig("t2", "fine $i"),
                    ),
                ),
            )
        }
    }

    // ── 生命周期 ────────────────────────────────────────────────

    @Test
    fun `tripped guard stays tripped until reset`() {
        val guard = ToolLoopGuard()
        repeat(3) { guard.checkAndRecord(listOf(sig("t", "same"))) }
        assertTrue(guard.tripped)
        // 熔断后状态粘滞（策略边据此直达终点）
        assertTrue(guard.checkAndRecord(listOf(sig("t", "different"))))
        guard.reset()
        assertFalse(guard.tripped)
        assertFalse(guard.checkAndRecord(listOf(sig("t", "same"))))
        assertFalse(guard.checkAndRecord(listOf(sig("t", "same"))))
    }

    // ── summarizeResponseForLog（llm_call_log.responseJson 补盲回归）────────────

    @Test
    fun `summarizeResponseForLog records tool calls of pure tool-call round`() {
        val response = Message.Assistant(
            listOf(MessagePart.Tool.Call("c1", "search_media", "{\"query\":\"最近\"}")),
            ResponseMetaInfo.Empty,
        )
        assertEquals(
            "toolCalls: search_media({\"query\":\"最近\"})",
            summarizeResponseForLog(response),
        )
    }

    @Test
    fun `summarizeResponseForLog prefers text and appends tool calls`() {
        val response = Message.Assistant(
            listOf(
                MessagePart.Text("先查一下"),
                MessagePart.Tool.Call("c1", "gallery_summary", "{}"),
            ),
            ResponseMetaInfo.Empty,
        )
        assertEquals(
            "先查一下\ntoolCalls: gallery_summary({})",
            summarizeResponseForLog(response),
        )
    }

    @Test
    fun `summarizeResponseForLog returns null for empty or null response`() {
        assertNull(summarizeResponseForLog(null))
        assertNull(summarizeResponseForLog(Message.Assistant("", ResponseMetaInfo.Empty)))
    }
}
