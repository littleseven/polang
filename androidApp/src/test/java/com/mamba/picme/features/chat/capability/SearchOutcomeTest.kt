package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.model.context.AgentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * search/refine 回执形态（2026-11-14 id 下发）：计数 + 前 24 个媒体 id，
 * 兑现工具描述「返回命中总数与前 24 个媒体 id」契约（此前纯计数 → LLM 拿不到
 * id 只能出占位框架的事故）。
 */
class SearchOutcomeTest {

    @Test
    fun `search receipt appends all ids when within cap`() {
        val outcome = SearchOutcome("海边", listOf(11L, 22L, 33L), 3, isRefinement = false)
        val reply = outcome.toTextReply(1).getOrThrow() as AgentAction.TextReply
        assertEquals("找到 3 张「海边」的照片，媒体 id：11,22,33", reply.message)
    }

    @Test
    fun `refine receipt uses in-set prefix with ids`() {
        val outcome = SearchOutcome("夜景", listOf(44L), 1, isRefinement = true)
        val reply = outcome.toTextReply(1).getOrThrow() as AgentAction.TextReply
        assertEquals("在上一轮结果内找到 1 张「夜景」的照片，媒体 id：44", reply.message)
    }

    @Test
    fun `receipt truncates ids at cap and points to gallery_query`() {
        val ids = (1L..30L).toList()
        val outcome = SearchOutcome("全部", ids, 30, isRefinement = false)
        val reply = outcome.toTextReply(1).getOrThrow() as AgentAction.TextReply
        val expectedIds = (1L..24L).joinToString(",")
        assertEquals(
            "找到 30 张「全部」的照片，前 24 个媒体 id：$expectedIds" +
                "（共 30 个，更多 id 用 run_gallery_script 的 gallery.query 获取）",
            reply.message
        )
    }

    @Test
    fun `zero hits keeps plain count without id suffix`() {
        val outcome = SearchOutcome("zzz", emptyList(), 0, isRefinement = false)
        val reply = outcome.toTextReply(1).getOrThrow() as AgentAction.TextReply
        assertEquals("找到 0 张「zzz」的照片", reply.message)
    }

    @Test
    fun `error message maps to error action without ids`() {
        val outcome = SearchOutcome("x", emptyList(), 0, isRefinement = true, errorMessage = "没有上一轮搜索结果可细化")
        val action = outcome.toTextReply(1).getOrThrow()
        assertTrue(action is AgentAction.Error)
        assertEquals("没有上一轮搜索结果可细化", (action as AgentAction.Error).message)
    }
}
