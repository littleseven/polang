package com.mamba.picme.domain.chat

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [projectChatRow] 全值域投影规则测试（D1a parts-first 终态立法，spec
 * 《chat-value-domain-ideal》§4.1 裁决 (a)：partsJson 权威，标量列为派生投影）。
 *
 * 两组核心不变式：
 * 1. **type 与线格式鉴别值零漂移**——对全部 13 值 part，投影 type 必须等于 codec
 *    编码出的 `type` 鉴别字段（钉住 when 字面量与 @SerialName 的同步）；
 * 2. **投影是 [MessagePartsConverter] 的逆**——单 part 规范形态下，
 *    `toParts(投影.type, 投影.content, 投影.metadata, role)` 还原原 parts
 *    （converter 是现存读侧真相，备份恢复/存量行兜底依赖此等价性）。
 */
class ChatMessageProjectionTest {

    private fun wireTypeOf(part: MessagePart): String {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(MessagePartsCodec.encodePart(part))
        return element.jsonObject["type"]!!.jsonPrimitive.content
    }

    private fun assertRoundTripsThroughConverter(parts: List<MessagePart>, role: ModelInputRole) {
        val projection = projectChatRow(parts, role)
        assertEquals(
            parts,
            MessagePartsConverter.toParts(projection.type, projection.content, projection.metadata, role),
            "投影应是 converter 的逆：$parts / $role",
        )
    }

    // ── type 投影源规则 ─────────────────────────────────────────

    @Test
    fun `projection type equals wire discriminator for every part kind`() {
        val samples: List<Pair<MessagePart, ModelInputRole>> = listOf(
            MessagePart.Text("p0", "hi") to ModelInputRole.USER,
            MessagePart.Reasoning("p0", "思考") to ModelInputRole.ASSISTANT,
            MessagePart.Chart("p0", "<svg/>") to ModelInputRole.ASSISTANT,
            MessagePart.HtmlCard("p0", "<html/>") to ModelInputRole.ASSISTANT,
            MessagePart.TaskCard("p0", "t1", ToolPartState.OUTPUT_AVAILABLE, engineerTask()) to ModelInputRole.ASSISTANT,
            MessagePart.Image("p0", "file://img") to ModelInputRole.USER,
            MessagePart.EditResult("p0", "file://e", "已提亮") to ModelInputRole.ASSISTANT,
            MessagePart.BrowserLive("p0", "s1") to ModelInputRole.ASSISTANT,
            MessagePart.OptimizeCandidates("p0", candidateGroup()) to ModelInputRole.ASSISTANT,
            MessagePart.OpenUiCard("p0", "program", "summary") to ModelInputRole.ASSISTANT,
            MessagePart.SourceUrl("p0", "s1", "https://a.com") to ModelInputRole.ASSISTANT,
            MessagePart.SourceDocument("p0", "s1", "text/html", "文档") to ModelInputRole.ASSISTANT,
            MessagePart.Tombstone("p0", "data_future_x") to ModelInputRole.ASSISTANT,
        )
        samples.forEach { (part, role) ->
            val projection = projectChatRow(listOf(part), role)
            if (part.category != PartCategory.REASONING &&
                part.category != PartCategory.SOURCE &&
                part !is MessagePart.Tombstone // 读侧合成物：防御性投影空 text 行，不主导判型
            ) {
                assertEquals(wireTypeOf(part), projection.type, "part=${part::class.simpleName}")
            }
        }
    }

    @Test
    fun `reasoning never serves as projection source`() {
        // 纯 reasoning 消息：type 兜底 text、content 空、无 metadata
        val pure = projectChatRow(listOf(MessagePart.Reasoning("p0", "思考链")), ModelInputRole.ASSISTANT)
        assertEquals(ChatRowProjection("text", "", null), pure)

        // reasoning 在 text 前：type/content 仍取 text（reasoning 不主导消息 kind）
        val leading = projectChatRow(
            listOf(MessagePart.Reasoning("p0", "先想"), MessagePart.Text("p1", "后答")),
            ModelInputRole.ASSISTANT,
        )
        assertEquals("text", leading.type)
        assertEquals("后答", leading.content)
        assertNull(leading.metadata)
    }

    @Test
    fun `source and tombstone parts never serve as projection source`() {
        val pureSource = projectChatRow(
            listOf(MessagePart.SourceUrl("p0", "s1", "https://a.com", "A")),
            ModelInputRole.ASSISTANT,
        )
        assertEquals(ChatRowProjection("text", "", null), pureSource)

        val textWithSource = projectChatRow(
            listOf(MessagePart.Text("p0", "正文"), MessagePart.SourceUrl("p1", "s1", "https://a.com")),
            ModelInputRole.ASSISTANT,
        )
        assertEquals("text", textWithSource.type)
        assertEquals("正文", textWithSource.content)

        val tombstone = projectChatRow(listOf(MessagePart.Tombstone("p0", "data_future_x")), ModelInputRole.ASSISTANT)
        assertEquals(ChatRowProjection("text", "", null), tombstone)
    }

    @Test
    fun `first content or tool part wins regardless of position`() {
        val chartThenText = projectChatRow(
            listOf(MessagePart.Chart("p0", "<svg/>"), MessagePart.Text("p1", "说明")),
            ModelInputRole.ASSISTANT,
        )
        assertEquals("tool_chart", chartThenText.type)
        assertEquals("<svg/>", chartThenText.content)
    }

    @Test
    fun `empty parts project to empty text row`() {
        assertEquals(ChatRowProjection("text", "", null), projectChatRow(emptyList(), ModelInputRole.ASSISTANT))
    }

    // ── content / metadata 各卡类形态 ───────────────────────────

    @Test
    fun `text part projects content only`() {
        val projection = projectChatRow(listOf(MessagePart.Text("p0", "你好", PartState.DONE)), ModelInputRole.USER)
        assertEquals(ChatRowProjection("text", "你好", null), projection)
        assertRoundTripsThroughConverter(listOf(MessagePart.Text("p0", "你好")), ModelInputRole.USER)
    }

    @Test
    fun `user pure image projects ref as content without metadata`() {
        val parts = listOf(MessagePart.Image("p0", "file://img"))
        assertEquals(
            ChatRowProjection("image", "file://img", null),
            projectChatRow(parts, ModelInputRole.USER),
        )
        assertRoundTripsThroughConverter(parts, ModelInputRole.USER)
    }

    @Test
    fun `user image-text projects caption as content and ref into metadata`() {
        val parts = listOf(
            MessagePart.Image("p0", "file://img"),
            MessagePart.Text("p1", "看这张", PartState.DONE),
        )
        val projection = projectChatRow(parts, ModelInputRole.USER)
        assertEquals("image", projection.type)
        assertEquals("看这张", projection.content)
        assertTrue(projection.metadata!!.contains("\"imageUri\":\"file://img\""))
        assertRoundTripsThroughConverter(parts, ModelInputRole.USER)
    }

    @Test
    fun `agent image projects ref as content with imageUri and saved keys`() {
        val parts = listOf(MessagePart.Image("p0", "file://img", saved = true))
        val projection = projectChatRow(parts, ModelInputRole.ASSISTANT)
        assertEquals("image", projection.type)
        assertEquals("file://img", projection.content)
        assertTrue(projection.metadata!!.contains("\"imageUri\":\"file://img\""))
        assertTrue(projection.metadata.contains("\"saved\":true"))
        assertRoundTripsThroughConverter(parts, ModelInputRole.ASSISTANT)
    }

    @Test
    fun `chart projects svg as content`() {
        val parts = listOf(MessagePart.Chart("p0", "<svg/>"))
        assertEquals(
            ChatRowProjection("tool_chart", "<svg/>", null),
            projectChatRow(parts, ModelInputRole.ASSISTANT),
        )
        assertRoundTripsThroughConverter(parts, ModelInputRole.ASSISTANT)
    }

    @Test
    fun `html card projects html as content and meta under html_card key`() {
        val meta = HtmlCardMeta(
            display = "inline",
            displayMode = HtmlCardDisplayMode.INLINE,
            measuredHeightPx = 320,
            summary = "周报",
        )
        val parts = listOf(MessagePart.HtmlCard("p0", "<html/>", meta))
        val projection = projectChatRow(parts, ModelInputRole.ASSISTANT)
        assertEquals("tool_html", projection.type)
        assertEquals("<html/>", projection.content)
        // 键与 converter.parseHtmlCardMeta 对齐：display/displayMode(name)/measuredHeightPx/summary
        assertTrue(projection.metadata!!.contains("\"html_card\""))
        assertTrue(projection.metadata.contains("\"displayMode\":\"INLINE\""))
        assertTrue(projection.metadata.contains("\"measuredHeightPx\":320"))
        assertTrue(projection.metadata.contains("\"summary\":\"周报\""))
        assertRoundTripsThroughConverter(parts, ModelInputRole.ASSISTANT)
    }

    @Test
    fun `task card projects empty content and task under engineer_task key`() {
        val task = engineerTask()
        val parts = listOf(
            MessagePart.TaskCard("p0", task.taskId, task.status.toToolPartState(), task),
        )
        val projection = projectChatRow(parts, ModelInputRole.ASSISTANT)
        assertEquals("tool_task", projection.type)
        assertEquals("", projection.content)
        assertTrue(projection.metadata!!.contains("\"engineer_task\""))
        // RUNNING 是非空默认枚举：缺失会被读侧还原成 FAILED，投影必须恒写出 status
        assertTrue(projection.metadata.contains("\"status\":\"RUNNING\""))
        assertTrue(projection.metadata.contains("\"taskId\":\"t1\""))
        assertRoundTripsThroughConverter(parts, ModelInputRole.ASSISTANT)
    }

    @Test
    fun `edit result projects description as content and payload keys as metadata`() {
        val parts = listOf(
            MessagePart.EditResult(
                "p0",
                ref = "file://out",
                description = "已提亮",
                suggestions = listOf("再亮一点", "微调"),
                saved = true,
            ),
        )
        val projection = projectChatRow(parts, ModelInputRole.ASSISTANT)
        assertEquals("tool_image_edit", projection.type)
        assertEquals("已提亮", projection.content)
        val metadata = projection.metadata!!
        assertTrue(metadata.contains("\"imageUri\":\"file://out\""))
        assertTrue(metadata.contains("\"saved\":true"))
        assertTrue(metadata.contains("\"suggestions\""))
        assertRoundTripsThroughConverter(parts, ModelInputRole.ASSISTANT)
    }

    @Test
    fun `edit result with empty payload projects null metadata`() {
        val parts = listOf(MessagePart.EditResult("p0", ref = null, description = "说明"))
        assertEquals(null, projectChatRow(parts, ModelInputRole.ASSISTANT).metadata)
        assertRoundTripsThroughConverter(parts, ModelInputRole.ASSISTANT)
    }

    @Test
    fun `browser live projects whole part json as content`() {
        val part = MessagePart.BrowserLive(
            "p0", sessionId = "s1",
            state = ToolPartState.OUTPUT_AVAILABLE,
            currentUrl = "https://a.com",
            pageTitle = "A",
            resultSummary = "完成",
        )
        val parts = listOf(part)
        val projection = projectChatRow(parts, ModelInputRole.ASSISTANT)
        assertEquals("tool_browser", projection.type)
        assertEquals(MessagePartsCodec.encodePart(part), projection.content)
        assertNull(projection.metadata)
        assertRoundTripsThroughConverter(parts, ModelInputRole.ASSISTANT)
    }

    @Test
    fun `openui card projects whole part json as content`() {
        val part = MessagePart.OpenUiCard("p0", program = "Page{}", fallbackSummary = "摘要", viewState = "selected=a")
        val parts = listOf(part)
        val projection = projectChatRow(parts, ModelInputRole.ASSISTANT)
        assertEquals("data_genui_program", projection.type)
        assertEquals(MessagePartsCodec.encodePart(part), projection.content)
        assertNull(projection.metadata)
        assertRoundTripsThroughConverter(parts, ModelInputRole.ASSISTANT)
    }

    @Test
    fun `optimize candidates projects empty content and group json as metadata`() {
        val group = candidateGroup()
        val parts = listOf(MessagePart.OptimizeCandidates("p0", group))
        val projection = projectChatRow(parts, ModelInputRole.ASSISTANT)
        assertEquals("data_optimize_candidates", projection.type)
        assertEquals("", projection.content)
        // metadata 顶层即 group JSON（键与 OptimizeCandidateGroup 线格式一致）
        assertTrue(projection.metadata!!.contains("\"sourceImageUri\":\"content://media/1\""))
        assertTrue(projection.metadata.contains("\"recommendedIndex\":1"))
        assertRoundTripsThroughConverter(parts, ModelInputRole.ASSISTANT)
    }

    // ── fixtures ────────────────────────────────────────────────

    private fun engineerTask(): EngineerTaskState = EngineerTaskState(
        taskId = "t1",
        sourceText = "修一下崩溃",
        status = EngineerTaskStatus.RUNNING,
        startedAtMs = 1L,
        updatedAtMs = 2L,
    )

    private fun candidateGroup(): OptimizeCandidateGroup = OptimizeCandidateGroup(
        sourceImageUri = "content://media/1",
        scene = "GENERAL",
        recommendedIndex = 1,
        candidates = listOf(
            OptimizeCandidateGroup.Candidate("base", "file:///a.jpg", 6.0f, false),
            OptimizeCandidateGroup.Candidate("warm", "file:///b.jpg", 6.5f, false),
        ),
        usedFingerprints = listOf("fp1"),
        drawIndex = 1,
    )
}
