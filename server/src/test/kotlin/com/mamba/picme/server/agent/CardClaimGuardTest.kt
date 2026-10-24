package com.mamba.picme.server.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假发卡守卫测试（2026-10-24 真机穷举事故类：LLM 不调 render_ui 就用文字声称「卡片已下发 ✅」）。
 * 检测模式样例全部来自当日生产消息（/tmp/a2ui_matrix/result.jsonl 实录）。
 */
class CardClaimGuardTest {

    // ── 检测器：命中实测假发卡表述 ──────────────────────────────────────

    @Test
    fun `detects observed fake card claims from production`() {
        listOf(
            "三种按钮样式卡片已下发 ✅",
            "「快速整理」卡片已下发 ✅",
            "左右分栏卡片已下发 ✅",
            "错误提示卡片已下发 ✅",
            "柱状图卡片已下发 ✅\n• 统计范围：2026-01-01 至今天",
            "已生成一张文字层级卡片，h1 到 h5 各一行",
        ).forEach { text ->
            assertTrue("应识别发卡声明：$text", CardClaimGuard.isCardClaim(text))
        }
    }

    @Test
    fun `plain answers and real card flows do not trigger`() {
        listOf(
            "统计范围：2026-01-01 至今天（2026-10-24）\n• 总数：147 张",
            "需要改按钮文字或事件名，告诉我即可。",
            "找到 7895 张「照片」的照片",
            "卡片",
            "已下载完成", // 「已下发」不含「卡」不误伤？——含「已下载」非「已下发」，不应命中
            "discarded and delivered", // 英文分支须词边界：discard 内含 card 子串不误伤
        ).forEach { text ->
            assertFalse("不应误伤：$text", CardClaimGuard.isCardClaim(text))
        }
    }

    // ── 守卫状态机 ─────────────────────────────────────────────────────

    @Test
    fun `claim without card action needs correction`() {
        val guard = CardClaimGuard()
        assertTrue(guard.needsCorrection("卡片已下发 ✅"))
    }

    @Test
    fun `claim after real card action passes through`() {
        val guard = CardClaimGuard()
        guard.markCardSent()
        assertFalse(guard.needsCorrection("卡片已下发 ✅"))
    }

    @Test
    fun `correction runs at most once per turn`() {
        val guard = CardClaimGuard()
        assertTrue(guard.needsCorrection("卡片已下发 ✅"))
        guard.markCorrected()
        // 第二次假声称放行（防 LLM 坚持己见死循环）
        assertFalse(guard.needsCorrection("卡片已下发 ✅"))
    }

    @Test
    fun `non claim text never needs correction`() {
        val guard = CardClaimGuard()
        assertFalse(guard.needsCorrection("今年共 147 张照片。"))
    }
}
