package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * [toModelInput] 回灌转换（spec §6 双层分离）与现状回灌输出的等价性 fixture。
 *
 * 等价口径（M1）：纯文本消息序列的 TextMessage 序列与现行 `getRecentMessages`
 * 直拼（type, content）逐条对应——同序、同文、同角色；卡片类消息的语义差（tool 对 /
 * data part 丢弃）是 spec §6 的显式决策，逐条钉在本测试里。
 */
class ChatModelInputTest {

    /** 现行回灌（`buildAppToolExecutor.chatHistoryLoader`）的直拼输出：(type, content)。 */
    private fun legacyReplay(rows: List<Triple<String, String, String?>>): List<Pair<String, String>> =
        rows.map { (type, content, _) -> type to content }

    private fun legacyMessage(type: String, content: String, metadata: String? = null): ChatMessage {
        // 经迁移单点构造：role + parts 一次到位（与 Room v26 迁移同源，防测试私搭派生逻辑）
        val migrated = LegacyChatTypeMigration.map(type, content, metadata)
        val uiType = when (type) {
            "user_text" -> ChatMessageType.USER_TEXT
            "user_image" -> ChatMessageType.USER_IMAGE
            "user_image_text" -> ChatMessageType.USER_IMAGE_TEXT
            else -> ChatMessageType.AGENT_TEXT
        }
        return ChatMessage(
            id = "m-$type",
            type = uiType,
            content = content,
            role = roleOf(migrated.role),
            parts = migrated.parts,
        )
    }

    @Test
    fun `text-only conversation replays identically to legacy join`() {
        val rows = listOf(
            Triple("user_text", "帮我找海边的照片", null),
            Triple("agent_text", "找到了 3 张", null),
            Triple("user_text", "只要有人脸的", null),
            Triple("agent_text", "还剩 1 张", null),
        )
        val legacy = legacyReplay(rows)
        val items = rows.flatMap { (type, content, metadata) ->
            legacyMessage(type, content, metadata).toModelInput()
        }
        // 等价：同长度、同序；每条 TextMessage 的 (role, text) 对应 legacy 的 (type, content)
        assertEquals(legacy.size, items.size)
        legacy.zip(items).forEach { (pair, item) ->
            assertIs<ModelInputItem.TextMessage>(item)
            val expectedRole = if (pair.first == "user_text") ModelInputRole.USER else ModelInputRole.ASSISTANT
            assertEquals(expectedRole, item.role)
            assertEquals(pair.second, item.text)
        }
    }

    @Test
    fun `chart converts to tool call plus tool result pair`() {
        val items = legacyMessage("chart", "<svg>big</svg>").toModelInput()
        assertEquals(2, items.size)
        val call = items[0]
        val result = items[1]
        assertIs<ModelInputItem.ToolCall>(call)
        assertIs<ModelInputItem.ToolResult>(result)
        assertEquals("draw_chart", call.toolName)
        assertEquals(call.toolCallId, result.toolCallId)
        assertEquals("draw_chart", result.toolName)
        assertEquals(false, result.isError)
    }

    @Test
    fun `chart and html tool call ids are namespaced by message id`() {
        // M2 命名空间锚：toolCallId = "${messageId}:${partId}"，跨消息同 partId（p0）不碰撞
        val chart = legacyMessage("chart", "<svg/>").toModelInput()
        assertEquals("m-chart:p0", (chart[0] as ModelInputItem.ToolCall).toolCallId)
        assertEquals("m-chart:p0", (chart[1] as ModelInputItem.ToolResult).toolCallId)
        val html = legacyMessage("html_card", "<html/>").toModelInput()
        assertEquals("m-html_card:p0", (html[0] as ModelInputItem.ToolCall).toolCallId)
        assertEquals("m-html_card:p0", (html[1] as ModelInputItem.ToolResult).toolCallId)
        // 任务卡保持真 taskId（不经命名空间——其 toolCallId 本来就是全局唯一锚）
        val metadata = """{"engineer_task":{"taskId":"t-7","sourceText":"做","status":"COMPLETED","startedAtMs":1,"updatedAtMs":2}}"""
        val task = legacyMessage("task_card", "做", metadata).toModelInput()
        assertEquals("t-7", (task[0] as ModelInputItem.ToolCall).toolCallId)
    }

    @Test
    fun `chart in output error state replays tool result as error`() {
        // M2 占位契约的持久化投影：OUTPUT_ERROR 的卡片以 isError 落上下文，模型可自我修正（spec §5.3）
        val message = ChatMessage(
            id = "m-err",
            type = ChatMessageType.CHART,
            content = "",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(MessagePart.Chart("p0", "", state = ToolPartState.OUTPUT_ERROR)),
        )
        val items = message.toModelInput()
        val result = items[1]
        assertIs<ModelInputItem.ToolResult>(result)
        assertEquals(true, result.isError)
        assertEquals("m-err:p0", result.toolCallId)
    }

    @Test
    fun `html card in output error state replays tool result as error`() {
        val message = ChatMessage(
            id = "m-herr",
            type = ChatMessageType.HTML_CARD,
            content = "",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(MessagePart.HtmlCard("p0", "", state = ToolPartState.OUTPUT_ERROR)),
        )
        val result = message.toModelInput()[1]
        assertIs<ModelInputItem.ToolResult>(result)
        assertEquals(true, result.isError)
    }

    @Test
    fun `html card converts to tool pair carrying summary when present`() {
        val metadata = """{"html_card":{"display":"inline","summary":"相册周报"}}"""
        val items = legacyMessage("html_card", "<html></html>", metadata).toModelInput()
        val call = items[0]
        val result = items[1]
        assertIs<ModelInputItem.ToolCall>(call)
        assertEquals("render_html", call.toolName)
        assertEquals("display=inline", call.argsSummary)
        assertIs<ModelInputItem.ToolResult>(result)
        assertEquals("相册周报", result.resultSummary)
    }

    @Test
    fun `failed task card replays error as tool result so the model can self-correct`() {
        val metadata = """{"engineer_task":{"taskId":"t-9","sourceText":"修复编译","status":"FAILED","errorSummary":"编译失败：缺符号","startedAtMs":1,"updatedAtMs":2}}"""
        val items = legacyMessage("task_card", "修复编译", metadata).toModelInput()
        val call = items[0]
        val result = items[1]
        assertIs<ModelInputItem.ToolCall>(call)
        assertEquals("engineer_task", call.toolName)
        assertEquals("修复编译", call.argsSummary)
        assertIs<ModelInputItem.ToolResult>(result)
        assertEquals("编译失败：缺符号", result.resultSummary)
        assertEquals(true, result.isError)
    }

    @Test
    fun `completed task card replays result summary without error flag`() {
        val metadata = """{"engineer_task":{"taskId":"t-1","sourceText":"做功能","status":"COMPLETED","resultSummary":"已交付","startedAtMs":1,"updatedAtMs":2}}"""
        val items = legacyMessage("task_card", "做功能", metadata).toModelInput()
        val result = items[1]
        assertIs<ModelInputItem.ToolResult>(result)
        assertEquals("已交付", result.resultSummary)
        assertEquals(false, result.isError)
    }

    @Test
    fun `data parts never enter the model context`() {
        val dropped = listOf(
            Triple(
                "media_results",
                """[{"id":1,"uri":"u","type":"PHOTO","captureDate":1,"fileName":"f"}]""",
                null,
            ),
            Triple(
                "optimize_candidates",
                "挑一张",
                """{"sourceImageUri":"u","scene":"s","recommendedIndex":0,"drawIndex":1,"candidates":[],"usedFingerprints":[]}""",
            ),
        )
        dropped.forEach { (type, content, metadata) ->
            val items = legacyMessage(type, content, metadata).toModelInput()
            assertEquals(emptyList(), items, "type=$type should not enter model context")
        }
    }

    @Test
    fun `images replay as neutral placeholders keeping turn structure`() {
        // 图片本体不进上下文（[PRIVACY]）；英文中性占位保住多轮会话的回合结构（spec §6）
        assertEquals(
            listOf(ModelInputItem.TextMessage(ModelInputRole.USER, "[user sent an image]")),
            legacyMessage("user_image", "/data/img.jpg").toModelInput(),
        )
        assertEquals(
            listOf(ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "[assistant generated an image]")),
            legacyMessage("agent_image", "说明", """{"imageUri":"/i.jpg"}""").toModelInput(),
        )
        // 图文混排：占位 + 文字段都保留，块内顺序即展示顺序（图先文后）
        assertEquals(
            listOf(
                ModelInputItem.TextMessage(ModelInputRole.USER, "[user sent an image]"),
                ModelInputItem.TextMessage(ModelInputRole.USER, "文字"),
            ),
            legacyMessage("user_image_text", "文字", """{"imageUri":"/i.jpg"}""").toModelInput(),
        )
    }

    @Test
    fun `edit result replays its description so multi-turn edits keep context`() {
        // 沿旧路径 (agent_edit_result, content) 语义：编辑说明回灌，防多轮编辑上下文断裂（spec §6）
        assertEquals(
            listOf(ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "已提亮")),
            legacyMessage("agent_edit_result", "已提亮", """{"imageUri":"/i.jpg"}""").toModelInput(),
        )
    }

    @Test
    fun `blank text parts are dropped from context`() {
        val message = ChatMessage(
            id = "m",
            type = ChatMessageType.AGENT_TEXT,
            content = "",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(MessagePart.Text("p0", "  ", PartState.DONE)),
        )
        assertEquals(emptyList(), message.toModelInput())
    }

    @Test
    fun `mixed turn keeps part order in model input`() {
        // 一回合多 part：文本-卡片-文本的交错顺序在回灌中保持（数组顺序即锚点）
        val message = ChatMessage(
            id = "m",
            type = ChatMessageType.AGENT_TEXT,
            content = "前",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(
                MessagePart.Text("p0", "前段", PartState.DONE),
                MessagePart.Chart("p1", "<svg/>"),
                MessagePart.Text("p2", "后段", PartState.DONE),
            ),
        )
        val items = message.toModelInput()
        assertEquals(4, items.size)
        assertEquals(ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "前段"), items[0])
        assertIs<ModelInputItem.ToolCall>(items[1])
        assertIs<ModelInputItem.ToolResult>(items[2])
        assertEquals(ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "后段"), items[3])
        // 卡片的 call/result 相邻且 id 配对
        assertEquals(
            (items[1] as ModelInputItem.ToolCall).toolCallId,
            (items[2] as ModelInputItem.ToolResult).toolCallId,
        )
    }

    @Test
    fun `browser live card replays as tool pair with text summary only`() {
        // browser-vnc 直播卡 Task 13：tool-call/tool-result 配对，结果侧只含文本摘要
        val message = ChatMessage(
            id = "m-b",
            type = ChatMessageType.AGENT_TEXT,
            content = "",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(
                MessagePart.BrowserLive(
                    partId = "p0",
                    sessionId = "s-1",
                    state = ToolPartState.OUTPUT_AVAILABLE,
                    currentUrl = "https://a.com",
                    pageTitle = "A",
                    frameJpegBase64 = "QUJDREVGRw==",
                    actionCount = 5,
                    resultSummary = "已订好机票",
                ),
            ),
        )
        val items = message.toModelInput()
        assertEquals(2, items.size)
        val call = items[0]
        val result = items[1]
        assertIs<ModelInputItem.ToolCall>(call)
        assertIs<ModelInputItem.ToolResult>(result)
        assertEquals("browser_session", call.toolName)
        assertEquals(call.toolCallId, result.toolCallId)
        assertEquals("已订好机票", result.resultSummary)
        assertEquals(false, result.isError)
        // 帧永不回灌：任何投影文本不得含 frameJpegBase64 的键名或内容片段
        items.forEach { item ->
            val text = when (item) {
                is ModelInputItem.TextMessage -> item.text
                is ModelInputItem.ToolCall -> item.argsSummary
                is ModelInputItem.ToolResult -> item.resultSummary
            }
            assertFalse(text.contains("QUJDREVGRw=="))
            assertFalse(text.contains("frameJpegBase64"))
        }
    }

    @Test
    fun `failed browser session replays error reason as tool result`() {
        val message = ChatMessage(
            id = "m-berr",
            type = ChatMessageType.AGENT_TEXT,
            content = "",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(
                MessagePart.BrowserLive(
                    partId = "p0",
                    sessionId = "s-2",
                    state = ToolPartState.OUTPUT_ERROR,
                    errorReason = "会话超时",
                ),
            ),
        )
        val result = message.toModelInput()[1]
        assertIs<ModelInputItem.ToolResult>(result)
        assertEquals("浏览器会话失败：会话超时", result.resultSummary)
        assertEquals(true, result.isError)
    }

    @Test
    fun `roleOf maps room role column to model role`() {
        // spec §2：role 升格为独立列后的唯一派生点——"user"→USER，其余（含 "agent"）→ASSISTANT
        assertEquals(ModelInputRole.USER, roleOf("user"))
        assertEquals(ModelInputRole.ASSISTANT, roleOf("agent"))
    }
}
