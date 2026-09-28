package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LegacyChatTypeMigration] legacy 13 值全枚举 → 新 8 值分类法映射 + 未知值兜底
 * （type taxonomy spec §4.2；MIGRATION_25_26 与备份恢复的唯一 legacy 知识源）。
 */
class LegacyChatTypeMigrationTest {

    @Test
    fun `all 13 legacy types map into the new taxonomy with role`() {
        val cases = mapOf(
            "user_text" to ("text" to "user"),
            "agent_text" to ("text" to "agent"),
            "command" to ("text" to "agent"),
            "plan_preview" to ("text" to "agent"),
            "user_image" to ("image" to "user"),
            "user_image_text" to ("image" to "user"),
            "agent_image" to ("image" to "agent"),
            "chart" to ("tool_chart" to "agent"),
            "html_card" to ("tool_html" to "agent"),
            "task_card" to ("tool_task" to "agent"),
            "agent_edit_result" to ("tool_image_edit" to "agent"),
            "media_results" to ("data_media_results" to "agent"),
            "optimize_candidates" to ("data_optimize_candidates" to "agent"),
        )
        cases.forEach { (legacy, expected) ->
            val row = LegacyChatTypeMigration.map(legacy, "c", null)
            assertEquals(expected.first, row.type, "type for $legacy")
            assertEquals(expected.second, row.role, "role for $legacy")
            assertTrue(row.parts.isNotEmpty(), "parts for $legacy")
        }
    }

    @Test
    fun `unknown legacy type falls back to text agent`() {
        val row = LegacyChatTypeMigration.map("mystery", "原文", null)
        assertEquals("text", row.type)
        assertEquals("agent", row.role)
        assertEquals(listOf(MessagePart.Text("p0", "原文", PartState.DONE)), row.parts)
    }

    @Test
    fun `legacy user_image_text keeps image plus text parts`() {
        val row = LegacyChatTypeMigration.map("user_image_text", "配文", """{"imageUri":"/i.jpg"}""")
        assertEquals("image", row.type)
        assertEquals("user", row.role)
        assertEquals(
            listOf(MessagePart.Image("p0", ref = "/i.jpg"), MessagePart.Text("p1", "配文", PartState.DONE)),
            row.parts,
        )
    }

    @Test
    fun `new types set covers exactly the eight taxonomy values`() {
        assertEquals(
            setOf(
                "text", "image",
                "tool_chart", "tool_html", "tool_task", "tool_image_edit",
                "data_media_results", "data_optimize_candidates",
            ),
            LegacyChatTypeMigration.NEW_TYPES,
        )
    }
}
