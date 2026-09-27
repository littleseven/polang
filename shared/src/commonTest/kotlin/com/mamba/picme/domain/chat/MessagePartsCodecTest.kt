package com.mamba.picme.domain.chat

import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `encode uses type discriminator aligned with legacy room types`() {
        val encoded = encode(listOf(MessagePart.Chart("p0", "<svg/>")))
        assertTrue(""""type":"chart"""" in encoded, encoded)
        assertTrue(""""partId":"p0"""" in encoded, encoded)
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
