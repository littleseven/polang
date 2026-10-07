package com.mamba.picme.domain.chat

import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MessagePartsCodec] round-trip 与容错（spec §12 JVM 单测：序列化 round-trip）。
 *
 * 同时充当 parts 模型不可变性 smoke：全部子类型为 data class（structural equality），
 * round-trip 后等值即证明深度值语义成立。
 */
class MessagePartsCodecTest {

    @Test
    fun `empty list round-trips`() {
        assertEquals(emptyList(), MessagePartsCodec.decode(MessagePartsCodec.encode(emptyList())))
    }

    @Test
    fun `text part round-trips`() {
        val parts = listOf(MessagePart.Text("p0", "你好 **世界**", PartState.DONE))
        assertEquals(parts, decode(encode(parts)))
    }

    @Test
    fun `text part streaming state round-trips`() {
        val parts = listOf(MessagePart.Text("p0", "流式中", PartState.STREAMING))
        assertEquals(parts, decode(encode(parts)))
    }

    @Test
    fun `chart part round-trips`() {
        val parts = listOf(MessagePart.Chart("p0", "<svg><rect/></svg>"))
        assertEquals(parts, decode(encode(parts)))
    }

    @Test
    fun `html card part carries display metadata`() {
        val parts = listOf(
            MessagePart.HtmlCard(
                partId = "p0",
                html = "<html><body>hi</body></html>",
                meta = HtmlCardMeta(
                    display = "fullpage",
                    displayMode = HtmlCardDisplayMode.FULLPAGE,
                    measuredHeightPx = 640,
                    summary = "周报卡片",
                ),
            ),
        )
        assertEquals(parts, decode(encode(parts)))
    }

    @Test
    fun `task card part round-trips with engineer state`() {
        val task = EngineerTaskState(
            taskId = "t-1",
            sourceText = "修复编译错误",
            sid = "abc123def456",
            status = EngineerTaskStatus.AWAITING_DELIVER,
            stage = "交付审批",
            recentStages = listOf("编辑", "编译"),
            turns = 3,
            costCents = 42,
            startedAtMs = 1000L,
            updatedAtMs = 2000L,
            fileChangeCount = 2,
            truncatedReason = null,
            errorSummary = null,
            resultSummary = "全部通过",
            resolution = EngineerTaskResolution.DELIVERED,
            deliverBranch = "feat/x",
        )
        val parts = listOf(
            MessagePart.TaskCard(
                partId = "p0",
                toolCallId = "t-1",
                state = ToolPartState.APPROVAL_REQUESTED,
                task = task,
            ),
        )
        assertEquals(parts, decode(encode(parts)))
    }

    @Test
    fun `media results part round-trips with assets and feedback`() {
        val results = MediaResultsUi(
            query = "海边",
            assets = listOf(
                MediaAsset(
                    id = 7L,
                    uri = "content://m/7",
                    type = MediaType.PHOTO,
                    captureDate = 123L,
                    fileName = "a.jpg",
                    faceFocusY = 0.5f,
                    aestheticScore = 8.1f,
                ),
            ),
            totalCount = 99,
            isRefinement = true,
            feedbackState = mapOf("7" to FeedbackAction.LIKE),
        )
        val parts = listOf(MessagePart.MediaResults("p0", results))
        assertEquals(parts, decode(encode(parts)))
    }

    @Test
    fun `image and edit result parts round-trip`() {
        val parts = listOf(
            MessagePart.Image("p0", ref = "/data/img/1.jpg", saved = true),
            MessagePart.EditResult(
                partId = "p1",
                ref = "content://m/9",
                description = "已提亮",
                suggestions = listOf("再亮一点", "微调"),
                saved = false,
            ),
        )
        assertEquals(parts, decode(encode(parts)))
    }

    @Test
    fun `optimize candidates part round-trips`() {
        val group = OptimizeCandidateGroup(
            sourceImageUri = "content://m/1",
            scene = "人像",
            recommendedIndex = 1,
            candidates = listOf(
                OptimizeCandidateGroup.Candidate("清新", "/tmp/c0.jpg", 7.5f, rejected = false),
                OptimizeCandidateGroup.Candidate("胶片", "/tmp/c1.jpg", null, rejected = true),
            ),
            usedFingerprints = listOf("fp1"),
            drawIndex = 2,
        )
        val parts = listOf(MessagePart.OptimizeCandidates("p0", group))
        assertEquals(parts, decode(encode(parts)))
    }

    @Test
    fun `browser live part round-trips`() {
        val parts = listOf(
            MessagePart.BrowserLive(
                partId = "call-1",
                sessionId = "s-1",
                state = ToolPartState.OUTPUT_AVAILABLE,
                currentUrl = "https://example.com",
                pageTitle = "Example",
                frameJpegBase64 = "anFk",
                actions = listOf(BrowserActionEntry("打开 example.com"), BrowserActionEntry("点击 价格")),
                actionCount = 2,
                resultSummary = "已查到价格",
            ),
        )
        assertEquals(parts, decode(encode(parts)))
    }

    @Test
    fun `decode returns null for blank or malformed json`() {
        assertNull(MessagePartsCodec.decode(null))
        assertNull(MessagePartsCodec.decode(""))
        assertNull(MessagePartsCodec.decode("   "))
        assertNull(MessagePartsCodec.decode("not-json"))
        assertNull(MessagePartsCodec.decode("""{"unexpected":"object"}"""))
    }

    @Test
    fun `decode returns null for unknown part discriminator`() {
        // 未来新版本 part 类型：旧版本整条解码失败 → 调用方走 legacy 列兜底（不丢消息）
        assertNull(MessagePartsCodec.decode("""[{"type":"future_part","partId":"p0"}]"""))
    }

    @Test
    fun `decode tolerates unknown fields on known part`() {
        // 前向兼容：新版本字段被忽略，其余字段正常还原
        val decoded = MessagePartsCodec.decode(
            """[{"type":"text","partId":"p0","markdown":"hi","futureField":123}]""",
        )
        assertNotNull(decoded)
        assertEquals(listOf(MessagePart.Text("p0", "hi", PartState.DONE)), decoded)
    }

    @Test
    fun `encode uses category taxonomy type discriminator`() {
        val encoded = encode(listOf(MessagePart.Chart("p0", "<svg/>")))
        assertTrue(""""type":"tool_chart"""" in encoded, encoded)
        assertTrue(""""partId":"p0"""" in encoded, encoded)
    }

    @Test
    fun `sealed descriptor locks the 9-value taxonomy`() {
        // kotlinx.serialization 1.10.0 起 sealed 描述符为 {type, value} 两元素包装，
        // 子类描述符收纳在 "value"（CONTEXTUAL kotlinx.serialization.Sealed<...>）内层——
        // 外层 elementsCount 恒为 2，须下沉一层枚举子类 @SerialName。
        val sealed = MessagePart.serializer().descriptor.getElementDescriptor(1)
        assertEquals(9, sealed.elementsCount)
        val serialNames = (0 until sealed.elementsCount)
            .map { sealed.getElementDescriptor(it).serialName }
            .toSet()
        assertEquals(
            setOf(
                "text", "image",
                "tool_chart", "tool_html", "tool_task", "tool_image_edit", "tool_browser",
                "data_media_results", "data_optimize_candidates",
            ),
            serialNames,
        )
    }

    @Test
    fun `serial name prefix matches declared category`() {
        // wireName 钉桩：category → 线格式命名空间（content/tool/data），与 type 前缀约定对应
        assertEquals(listOf("content", "tool", "data"), PartCategory.entries.map { it.wireName })
        val parts: List<MessagePart> = listOf(
            MessagePart.Text("p0", "hi"),
            MessagePart.Image("p0", ref = "u"),
            MessagePart.Chart("p0", svg = "<svg/>"),
            MessagePart.HtmlCard("p0", html = "<html/>"),
            MessagePart.TaskCard(
                "p0",
                toolCallId = "t",
                state = ToolPartState.OUTPUT_AVAILABLE,
                task = EngineerTaskState(taskId = "t", sourceText = "做", startedAtMs = 0L, updatedAtMs = 0L),
            ),
            MessagePart.EditResult("p0", description = "已提亮"),
            MessagePart.BrowserLive("p0", sessionId = "s"),
            MessagePart.MediaResults(
                "p0",
                MediaResultsUi(query = "q", assets = emptyList(), totalCount = 0, isRefinement = false),
            ),
            MessagePart.OptimizeCandidates(
                "p0",
                OptimizeCandidateGroup(
                    sourceImageUri = "u",
                    scene = "s",
                    recommendedIndex = 0,
                    drawIndex = 1,
                    candidates = emptyList(),
                    usedFingerprints = emptyList(),
                ),
            ),
        )
        parts.forEach { part ->
            val json = MessagePartsCodec.encode(listOf(part))
            val expectedPrefix = when (part.category) {
                PartCategory.CONTENT -> null
                PartCategory.TOOL -> "\"type\":\"tool_"
                PartCategory.DATA -> "\"type\":\"data_"
            }
            if (expectedPrefix != null) assertTrue(json.contains(expectedPrefix), "$json should carry prefix for $part")
            // content 类反锁：不得带保留前缀
            if (part.category == PartCategory.CONTENT) {
                assertTrue(!json.contains("\"type\":\"tool_") && !json.contains("\"type\":\"data_"))
            }
            // category 是纯代码面属性：getter-only 无后备字段，不得泄漏进线格式
            assertFalse(json.contains("category"), "$json must not carry category field")
        }
    }

    @Test
    fun `part lists are deeply immutable value snapshots`() {
        val original = listOf(MessagePart.Text("p0", "a", PartState.DONE))
        val copy = original.map { part -> (part as MessagePart.Text).copy(markdown = "b") }
        // data class 值语义：copy 不污染原列表元素
        assertEquals("a", (original[0] as MessagePart.Text).markdown)
        assertEquals("b", (copy[0] as MessagePart.Text).markdown)
    }

    private fun encode(parts: List<MessagePart>): String = MessagePartsCodec.encode(parts)

    private fun decode(json: String): List<MessagePart>? = MessagePartsCodec.decode(json)
}
