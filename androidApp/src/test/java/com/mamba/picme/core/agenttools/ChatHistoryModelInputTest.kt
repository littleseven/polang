package com.mamba.picme.core.agenttools

import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.toModelInputItems
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GET_CHAT_HISTORY 回灌链路的 (type, content) 对形状测试（spec §6 + M1 验收：
 * 文本消息按消息级 role 标注 user/agent；卡片/数据块按 §6 显式转换——语义差为
 * spec 显式决策，逐条钉在本测试里）。
 *
 * Room v26 起 type 为新 8 值、role 独立列；此处直接构造新分类法行。
 */
class ChatHistoryModelInputTest {

    private fun newPairs(entities: List<ChatMessageEntity>): List<Pair<String, String>> =
        entities.flatMap { entity -> entity.toModelInputItems() }
            .map { item -> item.toHistoryPair() }

    private fun entity(
        type: String,
        content: String,
        metadata: String? = null,
        role: String = "agent",
        id: String = "m-$type",
    ) = ChatMessageEntity(
        id = id,
        sessionId = "s",
        type = type,
        role = role,
        content = content,
        timestamp = 1L,
        metadata = metadata,
    )

    @Test
    fun `text rows replay as user or agent by role column`() {
        val pairs = newPairs(
            listOf(
                entity("text", "帮我找海边的照片", role = "user", id = "m1"),
                entity("text", "找到了 3 张", id = "m2"),
                entity("text", "只要有人脸的", role = "user", id = "m3"),
                entity("text", "还剩 1 张", """{"prompt_len":10}""", id = "m4"),
            ),
        )
        // 标签为消息级 role（"user"/"agent"），原文逐字保留
        assertEquals(
            listOf(
                "user" to "帮我找海边的照片",
                "agent" to "找到了 3 张",
                "user" to "只要有人脸的",
                "agent" to "还剩 1 张",
            ),
            pairs,
        )
    }

    @Test
    fun `card messages surface as tool call and tool result pairs`() {
        val pairs = newPairs(
            listOf(
                entity("tool_chart", "<svg/>", id = "c1"),
                entity("tool_html", "<html/>", """{"html_card":{"summary":"周报"}}""", id = "c2"),
                entity(
                    EngineerTaskRoomType,
                    "修复编译",
                    """{"engineer_task":{"taskId":"t1","sourceText":"修复编译","status":"FAILED","errorSummary":"编译失败","startedAtMs":1,"updatedAtMs":2}}""",
                    id = "c3",
                ),
            ),
        )
        assertEquals(
            listOf(
                "tool_call" to "draw_chart",
                "tool_result" to "draw_chart → Chart card (SVG, 6 chars)",
                "tool_call" to "render_html",
                "tool_result" to "render_html → 周报",
                "tool_call" to "engineer_task(修复编译)",
                "tool_result" to "engineer_task → 编译失败 (failed)",
            ),
            pairs,
        )
    }

    @Test
    fun `legacy stray types fall back to text part and label by role column`() {
        // 防御路径：未经迁移的 legacy 残留 type（如旧 command）行级兜底为原文 Text part，
        // 回灌标签取 role 列（旧 13 值里 command/plan_preview 均为 agent 侧）——
        // 正常链路下 Room v26 迁移已把它们改写为 text+agent
        val pairs = newPairs(
            listOf(
                entity("command", "search:猫", id = "r1"),
                entity("plan_preview", "计划预览", id = "r2"),
            ),
        )
        assertEquals(
            listOf("agent" to "search:猫", "agent" to "计划预览"),
            pairs,
        )
    }

    @Test
    fun `data parts are absent while images and edit results replay as text`() {
        val pairs = newPairs(
            listOf(
                entity("text", "看图", role = "user", id = "d1"),
                entity("image", "/data/img.jpg", role = "user", id = "d2"),
                entity(
                    "data_media_results",
                    """[{"id":1,"uri":"u","type":"PHOTO","captureDate":1,"fileName":"f"}]""",
                    id = "d3",
                ),
                entity("tool_image_edit", "已提亮", """{"imageUri":"/i.jpg"}""", id = "d4"),
            ),
        )
        // data part 不进上下文；用户图片回灌占位文本、编辑结果回灌文字说明
        assertEquals(
            listOf(
                "user" to "看图",
                "user" to "[user sent an image]",
                "agent" to "已提亮",
            ),
            pairs,
        )
    }

    @Test
    fun `toHistoryPair renders blank args tool call without parentheses`() {
        val call = ModelInputItem.ToolCall("p0", "draw_chart", "")
        assertEquals("tool_call" to "draw_chart", call.toHistoryPair())
        val result = ModelInputItem.ToolResult("p0", "draw_chart", "done", isError = false)
        assertEquals("tool_result" to "draw_chart → done", result.toHistoryPair())
        val text = ModelInputItem.TextMessage(ModelInputRole.USER, "hi")
        assertEquals("user" to "hi", text.toHistoryPair())
    }

    private companion object {
        // task 卡 Room type 常量（EngineerTaskState.ROOM_TYPE = "tool_task"）
        const val EngineerTaskRoomType = "tool_task"
    }
}
