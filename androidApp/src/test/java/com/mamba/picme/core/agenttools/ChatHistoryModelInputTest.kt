package com.mamba.picme.core.agenttools

import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.toModelInputItems
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GET_CHAT_HISTORY 回灌链路的 (type, content) 对形状测试（spec §6 + M1 验收：
 * 与现状回灌输出等价——文本消息逐条等价；卡片/数据块的语义差为 spec 显式决策）。
 */
class ChatHistoryModelInputTest {

    /** 现行实现（main 之前的直拼）：(entity.type, entity.content)。 */
    private fun legacyPairs(entities: List<ChatMessageEntity>): List<Pair<String, String>> =
        entities.map { entity -> entity.type to entity.content }

    private fun newPairs(entities: List<ChatMessageEntity>): List<Pair<String, String>> =
        entities.flatMap { entity -> entity.toModelInputItems() }
            .map { item -> item.toHistoryPair() }

    private fun entity(
        type: String,
        content: String,
        metadata: String? = null,
        id: String = "m-$type",
    ) = ChatMessageEntity(
        id = id,
        sessionId = "s",
        type = type,
        content = content,
        timestamp = 1L,
        metadata = metadata,
    )

    @Test
    fun `text-only history is byte-equivalent to legacy join`() {
        val entities = listOf(
            entity("user_text", "帮我找海边的照片", id = "m1"),
            entity("agent_text", "找到了 3 张", id = "m2"),
            entity("user_text", "只要有人脸的", id = "m3"),
            entity("agent_text", "还剩 1 张", """{"prompt_len":10}""", id = "m4"),
        )
        assertEquals(legacyPairs(entities), newPairs(entities))
    }

    @Test
    fun `card messages surface as tool call and tool result pairs`() {
        val pairs = newPairs(
            listOf(
                entity("chart", "<svg/>", id = "c1"),
                entity("html_card", "<html/>", """{"html_card":{"summary":"周报"}}""", id = "c2"),
                entity(
                    "task_card",
                    "修复编译",
                    """{"engineer_task":{"taskId":"t1","sourceText":"修复编译","status":"FAILED","errorSummary":"编译失败","startedAtMs":1,"updatedAtMs":2}}""",
                    id = "c3",
                ),
            ),
        )
        assertEquals(
            listOf(
                "tool_call" to "draw_chart",
                "tool_result" to "draw_chart → 图表卡片（SVG，6 字符）",
                "tool_call" to "render_html",
                "tool_result" to "render_html → 周报",
                "tool_call" to "engineer_task(修复编译)",
                "tool_result" to "engineer_task → 编译失败（失败）",
            ),
            pairs,
        )
    }

    @Test
    fun `data parts and media blocks are absent from history payload`() {
        val pairs = newPairs(
            listOf(
                entity("user_text", "看图", id = "d1"),
                entity("user_image", "/data/img.jpg", id = "d2"),
                entity(
                    "media_results",
                    """[{"id":1,"uri":"u","type":"PHOTO","captureDate":1,"fileName":"f"}]""",
                    id = "d3",
                ),
                entity("agent_edit_result", "已提亮", """{"imageUri":"/i.jpg"}""", id = "d4"),
            ),
        )
        // 媒体块/数据块不进上下文；用户文本保留
        assertEquals(listOf("user_text" to "看图"), pairs)
    }

    @Test
    fun `toHistoryPair renders blank args tool call without parentheses`() {
        val call = ModelInputItem.ToolCall("p0", "draw_chart", "")
        assertEquals("tool_call" to "draw_chart", call.toHistoryPair())
        val result = ModelInputItem.ToolResult("p0", "draw_chart", "done", isError = false)
        assertEquals("tool_result" to "draw_chart → done", result.toHistoryPair())
        val text = ModelInputItem.TextMessage(ModelInputRole.USER, "hi")
        assertEquals("user_text" to "hi", text.toHistoryPair())
    }
}
