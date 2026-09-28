package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [MessagePartsConverter] 新 8 值分类法映射 + `image` 角色双分支 + 未知/异常兜底
 * （type taxonomy spec §4.2；legacy 13 值 → 新 8 值的收编映射见 [LegacyChatTypeMigrationTest]）。
 */
class MessagePartsConverterTest {

    @Test
    fun `text converts to single text part`() {
        assertEquals(
            listOf(MessagePart.Text("p0", "你好", PartState.DONE)),
            MessagePartsConverter.toParts("text", "你好", null, ModelInputRole.USER),
        )
    }

    @Test
    fun `image with user role and imageUri metadata splits into image plus text`() {
        val metadata = """{"imageUri":"/i.jpg"}"""
        assertEquals(
            listOf(
                MessagePart.Image("p0", ref = "/i.jpg"),
                MessagePart.Text("p1", "配文", PartState.DONE),
            ),
            MessagePartsConverter.toParts("image", "配文", metadata, ModelInputRole.USER),
        )
    }

    @Test
    fun `image with user role without metadata uses content as ref`() {
        assertEquals(
            listOf(MessagePart.Image("p0", ref = "/data/img.jpg")),
            MessagePartsConverter.toParts("image", "/data/img.jpg", null, ModelInputRole.USER),
        )
    }

    @Test
    fun `image with agent role reads ref and saved from metadata`() {
        val metadata = """{"imageUri":"/gen.jpg","saved":true}"""
        assertEquals(
            listOf(MessagePart.Image("p0", ref = "/gen.jpg", saved = true)),
            MessagePartsConverter.toParts("image", "说明", metadata, ModelInputRole.ASSISTANT),
        )
    }

    @Test
    fun `tool and data types convert to their parts`() {
        assertIs<MessagePart.Chart>(MessagePartsConverter.toParts("tool_chart", "<svg/>", null, ModelInputRole.ASSISTANT).single())
        assertIs<MessagePart.HtmlCard>(MessagePartsConverter.toParts("tool_html", "<html/>", null, ModelInputRole.ASSISTANT).single())
        val taskMeta = """{"engineer_task":{"taskId":"t-1","sourceText":"做","status":"COMPLETED","startedAtMs":1,"updatedAtMs":2}}"""
        assertIs<MessagePart.TaskCard>(MessagePartsConverter.toParts("tool_task", "做", taskMeta, ModelInputRole.ASSISTANT).single())
        val editMeta = """{"imageUri":"/i.jpg"}"""
        assertIs<MessagePart.EditResult>(MessagePartsConverter.toParts("tool_image_edit", "已提亮", editMeta, ModelInputRole.ASSISTANT).single())
        assertIs<MessagePart.MediaResults>(MessagePartsConverter.toParts("data_media_results", """[{"id":1,"uri":"u","type":"PHOTO","captureDate":1,"fileName":"f"}]""", null, ModelInputRole.ASSISTANT).single())
        val optMeta = """{"sourceImageUri":"u","scene":"s","recommendedIndex":0,"drawIndex":1,"candidates":[],"usedFingerprints":[]}"""
        assertIs<MessagePart.OptimizeCandidates>(MessagePartsConverter.toParts("data_optimize_candidates", "挑一张", optMeta, ModelInputRole.ASSISTANT).single())
    }

    @Test
    fun `unknown type falls back to plain text keeping content`() {
        assertEquals(
            listOf(MessagePart.Text("p0", "原文", PartState.DONE)),
            MessagePartsConverter.toParts("mystery", "原文", null, ModelInputRole.ASSISTANT),
        )
    }

    @Test
    fun `malformed payloads fall back to plain text keeping content`() {
        // 行级兜底（spec §4.2）：任何解析异常 → 原文 Text part，不允许丢消息
        assertEquals(
            listOf(MessagePart.Text("p0", "not-a-json-array", PartState.DONE)),
            MessagePartsConverter.toParts("data_media_results", "not-a-json-array", null, ModelInputRole.ASSISTANT),
        )
        assertEquals(
            listOf(MessagePart.Text("p0", "任务摘要", PartState.DONE)),
            MessagePartsConverter.toParts("tool_task", "任务摘要", null, ModelInputRole.ASSISTANT),
        )
        assertEquals(
            listOf(MessagePart.Text("p0", "挑一张", PartState.DONE)),
            MessagePartsConverter.toParts("data_optimize_candidates", "挑一张", """{"broken":true}""", ModelInputRole.ASSISTANT),
        )
    }

    @Test
    fun `agent image with malformed metadata ignores metadata`() {
        assertEquals(
            listOf(MessagePart.Image("p0", ref = "/p.jpg")),
            MessagePartsConverter.toParts("image", "/p.jpg", "{oops", ModelInputRole.ASSISTANT),
        )
    }

    @Test
    fun `html card meta parses display declaration`() {
        val metadata = """{"html_card":{"display":"inline","displayMode":"INLINE","measuredHeightPx":120}}"""
        val card = assertIs<MessagePart.HtmlCard>(
            MessagePartsConverter.toParts("tool_html", "<html/>", metadata, ModelInputRole.ASSISTANT).single(),
        )
        assertEquals("inline", card.meta.display)
        assertEquals(HtmlCardDisplayMode.INLINE, card.meta.displayMode)
        assertEquals(120, card.meta.measuredHeightPx)
    }

    // ── 状态机映射表（spec §5.2） ────────────────────────────────

    @Test
    fun `engineer task status to tool part state mapping is exhaustive`() {
        assertEquals(ToolPartState.INPUT_AVAILABLE, EngineerTaskStatus.RUNNING.toToolPartState())
        assertEquals(ToolPartState.APPROVAL_REQUESTED, EngineerTaskStatus.AWAITING_CONTINUE.toToolPartState())
        assertEquals(ToolPartState.APPROVAL_REQUESTED, EngineerTaskStatus.AWAITING_DELIVER.toToolPartState())
        assertEquals(ToolPartState.OUTPUT_AVAILABLE, EngineerTaskStatus.COMPLETED.toToolPartState())
        assertEquals(ToolPartState.OUTPUT_ERROR, EngineerTaskStatus.FAILED.toToolPartState())
        // 编译期穷尽性：新增 EngineerTaskStatus 枚举值会让 when 编译失败，此断言同步兜底
        assertEquals(5, EngineerTaskStatus.entries.size)
    }
}
