package com.mamba.picme.domain.chat

import com.mamba.picme.agent.core.model.context.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LegacyMessagePartsConverter] 全枚举覆盖 + 行级兜底（spec §10 M1 验收：13 种
 * [ChatMessageType] 的 Room 列值全部映射；单行失败降级 Text 原文兜底，不丢消息）。
 */
class LegacyMessagePartsConverterTest {

    // ── 文本类 ─────────────────────────────────────────────────

    @Test
    fun `user_text converts to single text part`() {
        val parts = LegacyMessagePartsConverter.toParts("user_text", "帮我找海边的照片", null)
        assertEquals(
            listOf(MessagePart.Text("p0", "帮我找海边的照片", PartState.DONE)),
            parts,
        )
    }

    @Test
    fun `agent_text converts to text part and ignores performance metadata`() {
        val metadata = """{"prompt_len":10,"decode_len":20,"prefill_time_ms":1,"decode_time_ms":2}"""
        val parts = LegacyMessagePartsConverter.toParts("agent_text", "找到了 3 张", metadata)
        assertEquals(listOf(MessagePart.Text("p0", "找到了 3 张", PartState.DONE)), parts)
    }

    @Test
    fun `agent_text with claude agent state still converts to text part`() {
        val metadata = """{"claude_agent_state":{"text":"进行中","steps":[],"hasFileChange":false}}"""
        val parts = LegacyMessagePartsConverter.toParts("agent_text", "进行中", metadata)
        assertEquals(listOf(MessagePart.Text("p0", "进行中", PartState.DONE)), parts)
    }

    @Test
    fun `command and plan_preview convert to text parts`() {
        assertEquals(
            listOf(MessagePart.Text("p0", "search:猫", PartState.DONE)),
            LegacyMessagePartsConverter.toParts("command", "search:猫", null),
        )
        assertEquals(
            listOf(MessagePart.Text("p0", "计划预览", PartState.DONE)),
            LegacyMessagePartsConverter.toParts("plan_preview", "计划预览", null),
        )
    }

    // ── 图片类 ─────────────────────────────────────────────────

    @Test
    fun `user_image converts to image part with content as ref`() {
        val parts = LegacyMessagePartsConverter.toParts("user_image", "/data/picme_images/img_1.jpg", null)
        assertEquals(listOf(MessagePart.Image("p0", ref = "/data/picme_images/img_1.jpg")), parts)
    }

    @Test
    fun `user_image_text converts to image plus text in display order`() {
        val parts = LegacyMessagePartsConverter.toParts(
            "user_image_text",
            "把这张图调亮一点",
            """{"imageUri":"file:///x.jpg"}""",
        )
        assertEquals(
            listOf(
                MessagePart.Image("p0", ref = "file:///x.jpg"),
                MessagePart.Text("p1", "把这张图调亮一点", PartState.DONE),
            ),
            parts,
        )
    }

    @Test
    fun `user_image_text without imageUri falls back to text only`() {
        val parts = LegacyMessagePartsConverter.toParts("user_image_text", "只有文字", null)
        assertEquals(listOf(MessagePart.Text("p0", "只有文字", PartState.DONE)), parts)
    }

    @Test
    fun `agent_image reads ref and saved from metadata`() {
        val parts = LegacyMessagePartsConverter.toParts(
            "agent_image",
            "已生成",
            """{"imageUri":"content://m/9","saved":true}""",
        )
        assertEquals(listOf(MessagePart.Image("p0", ref = "content://m/9", saved = true)), parts)
    }

    @Test
    fun `agent_image without metadata falls back to content as ref`() {
        val parts = LegacyMessagePartsConverter.toParts("agent_image", "/legacy/path.jpg", null)
        assertEquals(listOf(MessagePart.Image("p0", ref = "/legacy/path.jpg", saved = false)), parts)
    }

    @Test
    fun `agent_edit_result carries description suggestions and saved flag`() {
        val parts = LegacyMessagePartsConverter.toParts(
            "agent_edit_result",
            "已提亮 10%",
            """{"imageUri":"content://m/10","saved":false,"explanation":"已提亮 10%","suggestions":["再亮一点","微调"]}""",
        )
        assertEquals(
            listOf(
                MessagePart.EditResult(
                    partId = "p0",
                    ref = "content://m/10",
                    description = "已提亮 10%",
                    suggestions = listOf("再亮一点", "微调"),
                    saved = false,
                ),
            ),
            parts,
        )
    }

    // ── 卡片类 ─────────────────────────────────────────────────

    @Test
    fun `media_results rebuilds results ui from content array and metadata`() {
        val content = """[{"id":7,"uri":"content://m/7","type":"PHOTO","captureDate":123,"fileName":"a.jpg","faceFocusY":0.5}]"""
        val metadata = """{"query":"海边","totalCount":99,"isRefinement":true}"""
        val parts = LegacyMessagePartsConverter.toParts("media_results", content, metadata)
        val part = parts.single()
        assertIs<MessagePart.MediaResults>(part)
        assertEquals("海边", part.results.query)
        assertEquals(99, part.results.totalCount)
        assertEquals(true, part.results.isRefinement)
        val asset = part.results.assets.single()
        assertEquals(7L, asset.id)
        assertEquals("content://m/7", asset.uri)
        assertEquals(MediaType.PHOTO, asset.type)
        assertEquals(0.5f, asset.faceFocusY)
    }

    @Test
    fun `media_results without metadata uses defaults`() {
        val parts = LegacyMessagePartsConverter.toParts("media_results", "[]", null)
        val part = parts.single()
        assertIs<MessagePart.MediaResults>(part)
        assertEquals("", part.results.query)
        assertEquals(0, part.results.totalCount)
        assertEquals(false, part.results.isRefinement)
    }

    @Test
    fun `chart converts content svg verbatim`() {
        val svg = "<svg viewBox=\"0 0 1 1\"></svg>"
        assertEquals(
            listOf(MessagePart.Chart("p0", svg)),
            LegacyMessagePartsConverter.toParts("chart", svg, null),
        )
    }

    @Test
    fun `html_card carries two-tier display metadata`() {
        val metadata = """{"html_card":{"display":"fullpage","displayMode":"FULLPAGE","measuredHeightPx":640,"summary":"周报"}}"""
        val parts = LegacyMessagePartsConverter.toParts("html_card", "<html></html>", metadata)
        assertEquals(
            listOf(
                MessagePart.HtmlCard(
                    partId = "p0",
                    html = "<html></html>",
                    meta = HtmlCardMeta(
                        display = "fullpage",
                        displayMode = HtmlCardDisplayMode.FULLPAGE,
                        measuredHeightPx = 640,
                        summary = "周报",
                    ),
                ),
            ),
            parts,
        )
    }

    @Test
    fun `html_card without metadata defaults to undeclared inline pending measure`() {
        val parts = LegacyMessagePartsConverter.toParts("html_card", "<html></html>", null)
        assertEquals(
            listOf(MessagePart.HtmlCard("p0", "<html></html>", HtmlCardMeta())),
            parts,
        )
    }

    @Test
    fun `html_card unknown displayMode degrades to null instead of failing the row`() {
        val metadata = """{"html_card":{"displayMode":"FUTURE_MODE","summary":"s"}}"""
        val part = LegacyMessagePartsConverter.toParts("html_card", "<html/>", metadata).single()
        assertIs<MessagePart.HtmlCard>(part)
        assertEquals(null, part.meta.displayMode)
        assertEquals("s", part.meta.summary)
    }

    @Test
    fun `task_card converts with tool state projection`() {
        val metadata = """
            {"engineer_task":{
              "taskId":"t-1","sourceText":"修复编译错误","sid":"abc123def456",
              "status":"AWAITING_DELIVER","stage":"交付审批","recentStages":["编辑","编译"],
              "turns":3,"costCents":42,"startedAtMs":1000,"updatedAtMs":2000,
              "fileChangeCount":2,"truncatedReason":null,"errorSummary":null,
              "resultSummary":"全部通过","resolution":"DELIVERED","deliverBranch":"feat/x"
            }}
        """.trimIndent()
        val parts = LegacyMessagePartsConverter.toParts("task_card", "修复编译错误", metadata)
        val part = parts.single()
        assertIs<MessagePart.TaskCard>(part)
        assertEquals("t-1", part.toolCallId)
        assertEquals(ToolPartState.APPROVAL_REQUESTED, part.state)
        assertEquals(EngineerTaskStatus.AWAITING_DELIVER, part.task.status)
        assertEquals(EngineerTaskResolution.DELIVERED, part.task.resolution)
        assertEquals(listOf("编辑", "编译"), part.task.recentStages)
    }

    @Test
    fun `task_card unknown status falls back to FAILED safe terminal`() {
        val metadata = """{"engineer_task":{"taskId":"t-2","sourceText":"s","status":"FUTURE","startedAtMs":1,"updatedAtMs":2}}"""
        val part = LegacyMessagePartsConverter.toParts("task_card", "s", metadata).single()
        assertIs<MessagePart.TaskCard>(part)
        assertEquals(EngineerTaskStatus.FAILED, part.task.status)
        assertEquals(ToolPartState.OUTPUT_ERROR, part.state)
    }

    @Test
    fun `optimize_candidates parses group from whole metadata json`() {
        val metadata = """
            {"sourceImageUri":"content://m/1","scene":"人像","recommendedIndex":1,"drawIndex":2,
             "candidates":[
               {"direction":"清新","thumbPath":"/tmp/c0.jpg","nimaScore":7.5,"rejected":false},
               {"direction":"胶片","thumbPath":"/tmp/c1.jpg","rejected":true}
             ],
             "usedFingerprints":["fp1"]}
        """.trimIndent()
        val parts = LegacyMessagePartsConverter.toParts("optimize_candidates", "挑一张", metadata)
        val part = parts.single()
        assertIs<MessagePart.OptimizeCandidates>(part)
        assertEquals("人像", part.group.scene)
        assertEquals(2, part.group.candidates.size)
        assertEquals(7.5f, part.group.candidates[0].nimaScore)
        assertEquals(null, part.group.candidates[1].nimaScore)
        assertEquals(true, part.group.candidates[1].rejected)
    }

    // ── 行级兜底（不丢消息） ─────────────────────────────────────

    @Test
    fun `unknown type falls back to text part with original content`() {
        val parts = LegacyMessagePartsConverter.toParts("future_type", "原文保留", null)
        assertEquals(listOf(MessagePart.Text("p0", "原文保留", PartState.DONE)), parts)
    }

    @Test
    fun `media_results with malformed content falls back to text`() {
        val parts = LegacyMessagePartsConverter.toParts("media_results", "not-a-json-array", null)
        assertEquals(listOf(MessagePart.Text("p0", "not-a-json-array", PartState.DONE)), parts)
    }

    @Test
    fun `task_card without engineer_task metadata falls back to text`() {
        val parts = LegacyMessagePartsConverter.toParts("task_card", "任务摘要", null)
        assertEquals(listOf(MessagePart.Text("p0", "任务摘要", PartState.DONE)), parts)
    }

    @Test
    fun `task_card without taskId falls back to text`() {
        val metadata = """{"engineer_task":{"sourceText":"x"}}"""
        val parts = LegacyMessagePartsConverter.toParts("task_card", "任务摘要", metadata)
        assertEquals(listOf(MessagePart.Text("p0", "任务摘要", PartState.DONE)), parts)
    }

    @Test
    fun `optimize_candidates with malformed metadata falls back to text`() {
        val parts = LegacyMessagePartsConverter.toParts("optimize_candidates", "挑一张", """{"broken":true}""")
        assertEquals(listOf(MessagePart.Text("p0", "挑一张", PartState.DONE)), parts)
    }

    @Test
    fun `malformed metadata json degrades metadata-dependent fields but keeps the part`() {
        // metadata 整体非法 JSON：parseMetadata 返回 null，agent_image 退化为 content 作 ref
        val parts = LegacyMessagePartsConverter.toParts("agent_image", "/p.jpg", "{oops")
        assertEquals(listOf(MessagePart.Image("p0", ref = "/p.jpg", saved = false)), parts)
    }

    // ── 状态机映射表（spec §5.2） ────────────────────────────────

    @Test
    fun `engineer task status to tool part state mapping is exhaustive`() {
        assertEquals(ToolPartState.INPUT_AVAILABLE, EngineerTaskStatus.RUNNING.toToolPartState())
        assertEquals(ToolPartState.APPROVAL_REQUESTED, EngineerTaskStatus.AWAITING_CONTINUE.toToolPartState())
        assertEquals(ToolPartState.APPROVAL_REQUESTED, EngineerTaskStatus.AWAITING_DELIVER.toToolPartState())
        assertEquals(ToolPartState.OUTPUT_AVAILABLE, EngineerTaskStatus.COMPLETED.toToolPartState())
        assertEquals(ToolPartState.OUTPUT_ERROR, EngineerTaskStatus.FAILED.toToolPartState())
        // 编译期穷尽性：新增 EngineerTaskStatus 枚举值会让 when 编译失败
        assertEquals(5, EngineerTaskStatus.entries.size)
    }

    @Test
    fun `converter output is encodable for every legacy type`() {
        // 迁移器「转换 → encode 落列」链路冒烟：13 类全部可编码且可解码回原 parts
        val fixtures = listOf(
            Triple("user_text", "t", null),
            Triple("agent_text", "t", null),
            Triple("user_image", "/p.jpg", null),
            Triple("user_image_text", "t", """{"imageUri":"/p.jpg"}"""),
            Triple("agent_image", "t", """{"imageUri":"/p.jpg","saved":true}"""),
            Triple("agent_edit_result", "t", """{"imageUri":"/p.jpg"}"""),
            Triple("command", "t", null),
            Triple("plan_preview", "t", null),
            Triple("media_results", "[]", """{"query":"q","totalCount":0,"isRefinement":false}"""),
            Triple("chart", "<svg/>", null),
            Triple("html_card", "<html/>", null),
            Triple(
                "task_card",
                "t",
                """{"engineer_task":{"taskId":"t","sourceText":"s","status":"RUNNING","startedAtMs":1,"updatedAtMs":1}}""",
            ),
            Triple(
                "optimize_candidates",
                "t",
                """{"sourceImageUri":"u","scene":"s","recommendedIndex":0,"drawIndex":1,"candidates":[],"usedFingerprints":[]}""",
            ),
        )
        fixtures.forEach { (type, content, metadata) ->
            val parts = LegacyMessagePartsConverter.toParts(type, content, metadata)
            assertTrue(parts.isNotEmpty(), "type=$type produced no parts")
            val decoded = MessagePartsCodec.decode(MessagePartsCodec.encode(parts))
            assertEquals(parts, decoded, "type=$type round-trip mismatch")
        }
    }
}
