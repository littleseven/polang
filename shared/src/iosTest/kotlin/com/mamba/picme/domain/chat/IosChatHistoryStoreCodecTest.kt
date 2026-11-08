package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ChatHistoryStoreCodec 契约测试（ADR-016 M5 B1）：
 * envelope v6 版本门禁 / round-trip 等值 / 9 值 kind↔wire 映射 / parts-first 投影 /
 * 结构化字段重建 / 损坏兜底不丢消息 / 瞬态与计算值不持久化。
 */
class IosChatHistoryStoreCodecTest {

    @Test
    fun encodeWrapsMessagesInVersionEnvelope() {
        // 写侧恒写当前版本信封（2026-11-08 值域终态）：顶层 {"version":6,"messages":[...]}
        val encoded = ChatHistoryStoreCodec.encode(listOf(testTextMessage()))
        assertTrue("\"version\":${ChatHistoryStoreCodec.FILE_VERSION}" in encoded, "应带版本信封: $encoded")
        assertTrue("\"messages\":[" in encoded, "信封内应为消息数组: $encoded")
    }

    @Test
    fun roundTripTextMessagePreservesDomain() {
        val original = testTextMessage()
        val decoded = ChatHistoryStoreCodec.decode(ChatHistoryStoreCodec.encode(listOf(original)))
        assertEquals(listOf(original), decoded)
    }

    @Test
    fun roundTripUserImageTextFlattensWireType() {
        val original = ChatMessage(
            id = "m2",
            type = ChatMessageType.IMAGE_TEXT,
            content = "看这张",
            role = ModelInputRole.USER,
            imageUri = "file:///img.jpg",
            imageSaved = false,
            parts = listOf(
                MessagePart.Image("p0", ref = "file:///img.jpg"),
                MessagePart.Text("p1", "看这张", PartState.DONE),
            ),
        )
        val encoded = ChatHistoryStoreCodec.encode(listOf(original))
        // 9 值 kind 枚举归并 wire 分类法 + role 独立列
        assertTrue("\"type\":\"image\"" in encoded, "IMAGE_TEXT 应归并 wire type=image: $encoded")
        assertTrue("\"role\":\"user\"" in encoded)
        val decoded = ChatHistoryStoreCodec.decode(encoded)
        assertEquals(listOf(original), decoded)
    }

    @Test
    fun roundTripChartAndHtmlCards() {
        // 平铺字段与 parts 同值（对齐 androidApp toUiModel 投影：自 primary part 提取）
        val messages = listOf(
            ChatMessage(
                id = "c1",
                type = ChatMessageType.CHART,
                content = "<svg/>",
                role = ModelInputRole.ASSISTANT,
                chartSvg = "<svg/>",
                parts = listOf(MessagePart.Chart("p0", svg = "<svg/>")),
            ),
            ChatMessage(
                id = "h1",
                type = ChatMessageType.HTML_CARD,
                content = "<div>card</div>",
                role = ModelInputRole.ASSISTANT,
                htmlContent = "<div>card</div>",
                htmlCardMeta = HtmlCardMeta(display = "inline", summary = "s"),
                parts = listOf(
                    MessagePart.HtmlCard("p0", html = "<div>card</div>", meta = HtmlCardMeta(display = "inline", summary = "s")),
                ),
            ),
        )
        assertEquals(messages, ChatHistoryStoreCodec.decode(ChatHistoryStoreCodec.encode(messages)))
    }

    @Test
    fun roundTripTaskAndDataPayloads() {
        val task = EngineerTaskState(
            taskId = "t1",
            sourceText = "任务",
            startedAtMs = 100L,
            updatedAtMs = 200L,
        )
        val gacha = testGachaGroup()
        val messages = listOf(
            ChatMessage(
                id = "t1",
                type = ChatMessageType.TASK_CARD,
                content = "",
                role = ModelInputRole.ASSISTANT,
                engineerTask = task,
                parts = listOf(
                    MessagePart.TaskCard("p0", toolCallId = "t1", state = ToolPartState.INPUT_AVAILABLE, task = task),
                ),
            ),
            ChatMessage(
                id = "g1",
                type = ChatMessageType.OPTIMIZE_CANDIDATES,
                content = "",
                role = ModelInputRole.ASSISTANT,
                optimizeCandidates = gacha,
                parts = listOf(
                    MessagePart.OptimizeCandidates("p0", group = gacha),
                ),
            ),
        )
        assertEquals(messages, ChatHistoryStoreCodec.decode(ChatHistoryStoreCodec.encode(messages)))
    }

    @Test
    fun decodeBackfillsFlatFieldsFromPartsWhenFlatMissing() {
        // 写路径只构 parts、平铺字段空：decode 自 parts 回填（parts-first 投影规范化）
        val message = ChatMessage(
            id = "c4",
            type = ChatMessageType.CHART,
            content = "",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(MessagePart.Chart("p0", svg = "<svg z/>")),
        )
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(ChatHistoryStoreCodec.encode(listOf(message))))
        assertEquals("<svg z/>", decoded.single().chartSvg)
    }

    @Test
    fun decodeTypingAnchorsOnFirstContentPartPastReasoning() {
        // 判型锚单点立法（值域终态 🟡2，与写侧 projectChatRow 同源 primaryPartOf）：
        // [Reasoning, Chart] 形态行判型落 CHART 而非 TEXT——uiTypeOf 排除 reasoning 后
        // 取首个 content part；平铺字段同样自 Chart 回填
        val message = ChatMessage(
            id = "rc1",
            type = ChatMessageType.CHART,
            content = "",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(
                MessagePart.Reasoning("p0", "先想"),
                MessagePart.Chart("p1", svg = "<svg rc/>"),
            ),
        )
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(ChatHistoryStoreCodec.encode(listOf(message))))
        val restored = decoded.single()
        assertEquals(ChatMessageType.CHART, restored.type, "reasoning 不主导消息 kind")
        assertEquals("<svg rc/>", restored.chartSvg)
        assertEquals(2, restored.parts.size, "reasoning part 原位保留（折叠块渲染）")
    }

    @Test
    fun transientAndComputedFieldsNotPersisted() {
        val original = ChatMessage(
            id = "m3",
            type = ChatMessageType.TEXT,
            content = "streaming",
            role = ModelInputRole.ASSISTANT,
            isStreaming = true,
            showCursor = true,
            isThinking = true,
            gachaInteractive = true,
            parts = listOf(MessagePart.Text("p0", "streaming", PartState.DONE)),
        )
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(ChatHistoryStoreCodec.encode(listOf(original))))
        val restored = decoded.single()
        assertEquals(false, restored.isStreaming, "瞬态字段不应持久化")
        assertEquals(false, restored.showCursor)
        assertEquals(false, restored.isThinking)
        assertEquals(false, restored.gachaInteractive, "gachaInteractive 计算值语义：decode 回默认 false")
    }

    @Test
    fun encodeBackfillsFlatFieldsFromParts() {
        // 写路径直构 parts、平铺字段漏填：encode 自 parts 补齐，文件内双 payload 一致
        val message = ChatMessage(
            id = "c2",
            type = ChatMessageType.CHART,
            content = "",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(MessagePart.Chart("p0", svg = "<svg x/>")),
        )
        val encoded = ChatHistoryStoreCodec.encode(listOf(message))
        assertTrue("\"chartSvg\":\"<svg x/>\"" in encoded, "chartSvg 应自 parts 补齐: $encoded")
    }

    @Test
    fun decodeRebuildsPartsFromFlatFieldsWhenPartsMissing() {
        // belt-and-braces：parts 数组整体缺失（手改文件/半损坏），自结构化字段重建
        val json = """
            {"version":6,"messages":[{"id":"m4","type":"image","role":"user","content":"说明文字",
              "timestamp":1,"imageUri":"file:///a.jpg"}]}
        """.trimIndent()
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(json))
        val message = decoded.single()
        assertEquals(ChatMessageType.IMAGE_TEXT, message.type, "重建后 parts-first 判型")
        assertEquals(2, message.parts.size)
        assertIs<MessagePart.Image>(message.parts[0])
        assertIs<MessagePart.Text>(message.parts[1])
    }

    @Test
    fun decodeRebuildsChartFromFlatSvg() {
        val json = """
            {"version":6,"messages":[{"id":"c3","type":"tool_chart","role":"agent","content":"","timestamp":1,
              "chartSvg":"<svg y/>"}]}
        """.trimIndent()
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(json))
        val message = decoded.single()
        assertEquals(ChatMessageType.CHART, message.type)
        assertEquals("<svg y/>", message.chartSvg)
        assertEquals(ToolPartState.OUTPUT_AVAILABLE, (message.parts.single() as MessagePart.Chart).state)
    }

    @Test
    fun decodeRebuildsBrowserLiveAndOpenUiFromWholePartJsonContent() {
        // 口径对齐 Android 备份粗映射物化器（BackupChatRestoreMapper）：tool_browser /
        // data_genui_program 的 content 承载整颗 part JSON，重建路径直通解码
        //（不再落 unknownTypeFallback 的 Tombstone 降级）
        val json = """
            {"version":6,"messages":[
              {"id":"b1","type":"tool_browser","role":"agent",
               "content":"{\"type\":\"tool_browser\",\"partId\":\"p0\",\"sessionId\":\"s1\",\"currentUrl\":\"https://example.com\",\"pageTitle\":\"Example\"}",
               "timestamp":1},
              {"id":"o1","type":"data_genui_program","role":"agent",
               "content":"{\"type\":\"data_genui_program\",\"partId\":\"p0\",\"program\":\"Page(){}\",\"fallbackSummary\":\"摘要\"}",
               "timestamp":2}
            ]}
        """.trimIndent()
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(json))
        val browser = assertIs<MessagePart.BrowserLive>(decoded[0].parts.single())
        assertEquals("s1", browser.sessionId)
        val openui = assertIs<MessagePart.OpenUiCard>(decoded[1].parts.single())
        assertEquals("Page(){}", openui.program)
    }

    @Test
    fun decodeFallsBackToTextOnUnknownWireType() {
        val json = """
            {"version":6,"messages":[{"id":"m5","type":"future_type","role":"agent","content":"原文","timestamp":1}]}
        """.trimIndent()
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(json))
        val message = decoded.single()
        assertEquals(ChatMessageType.TEXT, message.type)
        assertEquals("原文", (message.parts.single() as MessagePart.Text).markdown)
    }

    @Test
    fun decodeToleratesUnknownPartInsidePartsArray() {
        // 前向兼容兜底（2026-11-08）：parts 数组含新版本未知 part → 原位 Tombstone
        // 占位（可读摘要提升），整包 decode 不再失败丢历史
        val json = """
            {"version":6,"messages":[{"id":"m6","type":"text","role":"agent","content":"hi","timestamp":1,
              "parts":[{"type":"text","partId":"p0","markdown":"hi","state":"DONE"},
                       {"type":"tool_video_edit","partId":"p1","fallbackSummary":"视频结果"}]}]}
        """.trimIndent()
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(json))
        val message = decoded.single()
        assertEquals(2, message.parts.size)
        assertIs<MessagePart.Text>(message.parts[0])
        assertEquals(
            MessagePart.Tombstone("p1", originalType = "tool_video_edit", summary = "视频结果"),
            message.parts[1],
        )
    }

    @Test
    fun decodeUnknownWireTypeWithJsonBlobFallsBackToTombstone() {
        // content 为 JSON blob 的未知 wire type（对齐 Android converter 口径）→ Tombstone 占位
        val json = """
            {"version":6,"messages":[{"id":"m7","type":"tool_future","role":"agent",
              "content":"{\"type\":\"tool_future\",\"partId\":\"p0\",\"fallbackSummary\":\"摘要\"}","timestamp":1}]}
        """.trimIndent()
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(json))
        assertEquals(
            MessagePart.Tombstone("p0", originalType = "tool_future", summary = "摘要"),
            decoded.single().parts.single(),
        )
    }

    @Test
    fun decodeReturnsNullOnBlankOrCorrupted() {
        assertNull(ChatHistoryStoreCodec.decode(null))
        assertNull(ChatHistoryStoreCodec.decode(""))
        assertNull(ChatHistoryStoreCodec.decode("not json"))
        assertNull(ChatHistoryStoreCodec.decode("{\"partial\":"))
    }

    @Test
    fun decodeIgnoresLegacyFileWithoutVersionEnvelope() {
        // envelope 前的裸数组格式（旧 codec 写出）→ 整体忽略（Android 聊天表重置对齐，不迁移）
        val bareArray = """
            [{"id":"m1","type":"text","role":"agent","content":"hi","timestamp":1,
              "parts":[{"type":"text","partId":"p0","markdown":"hi","state":"DONE"}]}]
        """.trimIndent()
        assertNull(ChatHistoryStoreCodec.decode(bareArray))

        // legacy Swift Codable 格式（role/text 字段族）→ 同样解析不出信封，整体忽略
        val legacySwift = """
            [{"id":"123","role":"user","text":"老格式","timestamp":"2026-01-01T00:00:00Z",
              "isStreaming":false,"isThinking":false,"isToolCalling":false,"mediaIds":[]}]
        """.trimIndent()
        assertNull(ChatHistoryStoreCodec.decode(legacySwift))
    }

    @Test
    fun decodeIgnoresEnvelopeBelowCurrentVersion() {
        // 未来信封结构假设回溯兼容：低版本文件整体忽略（与 Android 聊天表重置语义对齐）
        val json = """
            {"version":5,"messages":[{"id":"m1","type":"text","role":"agent","content":"hi","timestamp":1}]}
        """.trimIndent()
        assertNull(ChatHistoryStoreCodec.decode(json))
    }

    @Test
    fun decodeDegradesRetiredDiscriminatorRowToTombstoneWithoutCollapsingFile() {
        // 文件内偶现的退役/未知鉴别值由 parts 惰性承载（逐元素 Tombstone 兜底）保证整包
        // 强类型解码不塌成 null（空历史 → persist 覆写丢数据）。行级过滤已随 2026-11-08
        // 值域终态出清（Room v28 重置后存量行不复存在）：退役鉴别值行降级为 Tombstone
        // 占位行保留，正常行不受影响。
        val json = """
            {"version":6,"messages":[
              {"id":"a","type":"text","role":"user","content":"找猫的照片","timestamp":1,
               "parts":[{"type":"text","partId":"p0","markdown":"找猫的照片","state":"DONE"}]},
              {"id":"b","type":"data_media_results","role":"agent","content":"找到 3 张","timestamp":2,
               "parts":[{"type":"data_media_results","partId":"p0","mediaIds":[1,2,3],"total":3}]},
              {"id":"c","type":"text","role":"agent","content":"找到 3 张「猫」的照片","timestamp":3,
               "parts":[{"type":"text","partId":"p0","markdown":"找到 3 张「猫」的照片","state":"DONE"}]}
            ]}
        """.trimIndent()
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(json), "退役鉴别值不得塌掉整包解码")
        assertEquals(listOf("a", "b", "c"), decoded.map { it.id }, "三行全保留（b 降级 Tombstone 占位）")
        assertEquals(ChatMessageType.TEXT, decoded[0].type)
        assertEquals(
            MessagePart.Tombstone("p0", originalType = "data_media_results"),
            decoded[1].parts.single(),
        )
        assertEquals(ChatMessageType.TEXT, decoded[1].type, "Tombstone 行判型防御性落文本")
        assertEquals(ChatMessageType.TEXT, decoded[2].type)
    }

    @Test
    fun decodeRowLevelFallbackOnPartiallyCorruptedRow() {
        // 行级容错：单行字段损坏只影响该行（content 打捞为 text），其余行不丢
        val json = """
            {"version":6,"messages":[
              {"id":"a","type":"text","role":"agent","content":"正常行","timestamp":1},
              {"id":"b","type":"tool_task","role":"agent","content":"任务卡缺 engineerTask","timestamp":2},
              {"id":"c","type":"text","role":"user","content":"尾行","timestamp":3}
            ]}
        """.trimIndent()
        val decoded = assertNotNull(ChatHistoryStoreCodec.decode(json))
        assertEquals(listOf("a", "b", "c"), decoded.map { it.id })
        assertEquals(ChatMessageType.TEXT, decoded[1].type, "损坏行降级纯文本")
        assertEquals("任务卡缺 engineerTask", decoded[1].content)
    }

    private fun testTextMessage(): ChatMessage = ChatMessage(
        id = "m1",
        type = ChatMessageType.TEXT,
        content = "hello",
        role = ModelInputRole.ASSISTANT,
        modelUsed = "test-model",
        parts = listOf(MessagePart.Text("p0", "hello", PartState.DONE)),
    )

    private fun testGachaGroup(): OptimizeCandidateGroup = OptimizeCandidateGroup(
        sourceImageUri = "file:///src.jpg",
        scene = "portrait",
        recommendedIndex = 0,
        candidates = listOf(
            OptimizeCandidateGroup.Candidate(direction = "自然", thumbPath = "file:///t0.jpg"),
            OptimizeCandidateGroup.Candidate(direction = "冷白", thumbPath = "file:///t1.jpg", nimaScore = 4.2f),
        ),
        usedFingerprints = emptyList(),
        drawIndex = 0,
    )
}
