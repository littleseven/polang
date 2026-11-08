package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessagePartsProjectionTest {

    private fun textPart(id: String = "p0", md: String = "hello") =
        MessagePart.Text(id, md, PartState.DONE)

    @Test
    fun `roomTypeOf maps every part subtype to its wire type`() {
        val task = EngineerTaskState(
            taskId = "t1", sourceText = "s", status = EngineerTaskStatus.RUNNING,
            startedAtMs = 0L, updatedAtMs = 0L,
        )
        val group = OptimizeCandidateGroup("u", "scene", 0, emptyList(), emptyList(), 0)
        val cases: List<Pair<MessagePart, String>> = listOf(
            textPart() to "text",
            MessagePart.Image("p0", "file://a") to "image",
            MessagePart.Chart("p0", "<svg/>") to "tool_chart",
            MessagePart.HtmlCard("p0", "<p/>") to "tool_html",
            MessagePart.TaskCard("p0", "t1", ToolPartState.INPUT_AVAILABLE, task) to "tool_task",
            MessagePart.EditResult("p0", "file://a", "d") to "tool_image_edit",
            MessagePart.BrowserLive("p0", "s1") to "tool_browser",
            MessagePart.OptimizeCandidates("p0", group) to "data_optimize_candidates",
            MessagePart.OpenUiCard("p0", "prog", "sum") to "data_ui_openui",
            MessagePart.Unsupported("p0", "tool_future") to "text",
            // 退役横滑卡行（parts 兜底为 Unsupported）：投影仍归一退役正型供回放整行跳过
            MessagePart.Unsupported("p0", "data_media_results") to "data_media_results",
        )
        cases.forEach { (part, expected) ->
            assertEquals(expected, MessagePartsProjection.roomTypeOf(listOf(part)), "type of ${part::class.simpleName}")
        }
    }

    @Test
    fun `roomTypeOf empty parts falls back to text`() {
        assertEquals("text", MessagePartsProjection.roomTypeOf(emptyList()))
    }

    @Test
    fun `contentPreviewOf joins text parts and skips non-text`() {
        val parts = listOf(
            textPart("p0", "第一段"),
            MessagePart.Chart("p1", "<svg/>"),
            textPart("p2", "第二段"),
        )
        assertEquals("第一段\n\n第二段", MessagePartsProjection.contentPreviewOf(parts))
        assertEquals("", MessagePartsProjection.contentPreviewOf(listOf(MessagePart.Chart("p0", "<svg/>"))))
    }

    @Test
    fun `slimMetadataJson keeps message-level keys and drops part-level keys`() {
        val raw = """
            {"claude_agent_state":{"text":"x"},"prompt_len":10,"decode_len":20,"used_sandbox":false,
             "html_card":{"display":"inline"},"engineer_task":{"taskId":"t"},"imageUri":"file://a",
             "saved":true,"suggestions":["a"],"explanation":"e","query":"q","totalCount":3,
             "isRefinement":false,"sourceImageUri":"u","candidates":[]}
        """.trimIndent()
        val slim = MessagePartsProjection.slimMetadataJson(raw)!!
        listOf("claude_agent_state", "prompt_len", "decode_len", "used_sandbox").forEach {
            assert(it in slim) { "expected key $it in $slim" }
        }
        listOf("html_card", "engineer_task", "imageUri", "saved", "suggestions", "explanation",
            "query", "totalCount", "isRefinement", "sourceImageUri", "candidates").forEach {
            assert(it !in slim) { "unexpected key $it in $slim" }
        }
    }

    @Test
    fun `slimMetadataJson returns null for blank corrupt or fully-stripped input`() {
        assertNull(MessagePartsProjection.slimMetadataJson(null))
        assertNull(MessagePartsProjection.slimMetadataJson(""))
        assertNull(MessagePartsProjection.slimMetadataJson("not json"))
        assertNull(MessagePartsProjection.slimMetadataJson("""{"html_card":{}}"""))
        assertEquals("""{"savedAt":123}""", MessagePartsProjection.slimMetadataJson("""{"savedAt":123,"saved":true}"""))
    }
}
