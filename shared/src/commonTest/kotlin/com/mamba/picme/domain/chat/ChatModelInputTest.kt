package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
            parts = LegacyMessagePartsConverter.toParts(type, content, metadata),
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
    fun `data parts and media blocks never enter the model context`() {
        val dropped = listOf(
            Triple("media_results", """[{"id":1,"uri":"u","type":"PHOTO","captureDate":1,"fileName":"f"}]""", null),
            Triple("user_image", "/data/img.jpg", null),
            Triple("user_image_text", "文字", """{"imageUri":"/i.jpg"}"""),
            Triple("agent_image", "说明", """{"imageUri":"/i.jpg"}"""),
            Triple("agent_edit_result", "已提亮", """{"imageUri":"/i.jpg"}"""),
            Triple(
                "optimize_candidates",
                "挑一张",
                """{"sourceImageUri":"u","scene":"s","recommendedIndex":0,"drawIndex":1,"candidates":[],"usedFingerprints":[]}""",
            ),
        )
        dropped.forEach { (type, content, metadata) ->
            val items = legacyMessage(type, content, metadata).toModelInput()
            if (type == "user_image_text") {
                // 图文混排：图片块丢弃，文字段保留（用户意图文本对上下文有价值）
                assertEquals(
                    listOf(ModelInputItem.TextMessage(ModelInputRole.USER, "文字")),
                    items,
                )
            } else {
                assertEquals(emptyList(), items, "type=$type should not enter model context")
            }
        }
    }

    @Test
    fun `blank text parts are dropped from context`() {
        val message = ChatMessage(
            id = "m",
            type = ChatMessageType.AGENT_TEXT,
            content = "",
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
    fun `role derives from legacy type`() {
        assertEquals(ModelInputRole.USER, legacyMessage("user_text", "x").modelRole)
        assertEquals(ModelInputRole.USER, legacyMessage("user_image", "x").modelRole)
        assertEquals(ModelInputRole.ASSISTANT, legacyMessage("agent_text", "x").modelRole)
        assertEquals(ModelInputRole.ASSISTANT, legacyMessage("chart", "x").modelRole)
        assertTrue(legacyMessage("task_card", "x").modelRole == ModelInputRole.ASSISTANT)
    }
}
