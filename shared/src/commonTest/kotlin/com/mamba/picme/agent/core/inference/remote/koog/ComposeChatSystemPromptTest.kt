package com.mamba.picme.agent.core.inference.remote.koog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * chat system prompt 三段组装单测（US-2.4 注入语义）。
 *
 * 摘要段必须：拼在记忆快照后、空则跳过、带【会话摘要】标记——使模型可区分长期事实（快照）
 * 与会话级近期情节（摘要）。
 */
class ComposeChatSystemPromptTest {

    private val base = "你是相册助手。"

    @Test
    fun `全空只返回 base`() {
        assertEquals(base, composeChatSystemPrompt(base, null, null))
        assertEquals(base, composeChatSystemPrompt(base, "", ""))
        assertEquals(base, composeChatSystemPrompt(base, "  ", "  "))
    }

    @Test
    fun `只有快照拼快照`() {
        assertEquals("$base\n\n快照内容", composeChatSystemPrompt(base, "快照内容", null))
    }

    @Test
    fun `只有摘要拼摘要`() {
        val result = composeChatSystemPrompt(base, null, "摘要内容")
        assertEquals("$base\n\n【会话摘要】\n摘要内容", result)
    }

    @Test
    fun `快照与摘要都在时摘要恒在快照后`() {
        val result = composeChatSystemPrompt(base, "快照", "摘要")
        val snapshotIdx = result.indexOf("快照")
        val summaryIdx = result.indexOf("【会话摘要】")
        assertTrue(snapshotIdx >= 0 && summaryIdx > snapshotIdx, "摘要必须在快照后")
    }

    @Test
    fun `摘要含多行槽位内容原样保留`() {
        val summary = "会话意图：x\n已做决定：\n- A\n- B"
        val result = composeChatSystemPrompt(base, null, summary)
        assertTrue(result.contains("- A"))
        assertTrue(result.contains("- B"))
    }
}
