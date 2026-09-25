package com.mamba.picme.agent.core.inference.remote.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ChatPromptRules] 分节结构单测：锁死规则节集合与拼装顺序。
 *
 * 新增/删除/重排规则节会在这里立刻变红（节内容本身的逐字节稳定性
 * 由 jvmTest `ChatSystemPromptGoldenTest` 兜底）。
 */
class ChatPromptRulesTest {

    @Test
    fun `sections are exactly the expected ids in order`() {
        assertEquals(
            listOf(
                "chart_rules",
                "gallery_script_overview",
                "memory_tools",
                "capability_dispatch",
                "chart_tool_note",
                "html_card_rules",
                "script_vs_tool",
                "image_edit",
                "refinement_rules",
                "convergence_rules",
            ),
            ChatPromptRules.sections.map { section -> section.id }
        )
    }

    @Test
    fun `section ids are unique and bodies non blank`() {
        val ids = ChatPromptRules.sections.map { section -> section.id }
        assertEquals(ids.size, ids.toSet().size)
        ChatPromptRules.sections.forEach { section ->
            assertTrue(section.body.isNotBlank(), "section ${section.id} body must not be blank")
        }
    }

    @Test
    fun `render joins all section bodies in order with blank line separator`() {
        val rendered = ChatPromptRules.render()
        var cursor = 0
        ChatPromptRules.sections.forEach { section ->
            val index = rendered.indexOf(section.body, startIndex = cursor)
            assertTrue(index >= cursor, "section ${section.id} must appear in order")
            cursor = index + section.body.length
        }
        assertEquals(
            ChatPromptRules.sections.joinToString("\n\n") { section -> section.body },
            rendered
        )
        assertFalse(rendered.endsWith("\n"), "render() must not end with a newline")
    }

    @Test
    fun `section bodies must not start or end with newline`() {
        // 守住「节间统一空行分隔」不变量：节体自带首尾空行会破坏分隔一致性
        ChatPromptRules.sections.forEach { section ->
            assertFalse(section.body.startsWith("\n"), "section ${section.id} must not start with newline")
            assertFalse(section.body.endsWith("\n"), "section ${section.id} must not end with newline")
        }
    }
}
