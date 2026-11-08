package com.mamba.picme.core.agenttools

import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.chatMessageEntity
import com.mamba.picme.data.local.toModelInputItems
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.HtmlCardMeta
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.OptimizeCandidateGroup
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.ToolPartState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GET_CHAT_HISTORY 回灌链路的 (type, content) 对形状测试（spec §6 + M1 验收：
 * 文本消息按消息级 role 标注 user/agent；卡片/数据块按 §6 显式转换——语义差为
 * spec 显式决策，逐条钉在本测试里）。
 *
 * Room v28 起 partsJson 为唯一内容权威；此处直接构造 parts（type 为投影派生）。
 */
class ChatHistoryModelInputTest {

    private fun newPairs(entities: List<ChatMessageEntity>): List<Pair<String, String>> =
        entities.flatMap { entity -> entity.toModelInputItems() }
            .map { item -> item.toHistoryPair() }

    private fun entity(
        id: String,
        role: String = "agent",
        parts: List<MessagePart>,
        metadata: String? = null,
    ) = chatMessageEntity(
        id = id,
        sessionId = "s",
        role = role,
        parts = parts,
        timestamp = 1L,
        metadata = metadata,
    )

    @Test
    fun `text rows replay as user or agent by role column`() {
        val pairs = newPairs(
            listOf(
                entity("m1", role = "user", parts = listOf(MessagePart.Text("p0", "帮我找海边的照片", PartState.DONE))),
                entity("m2", parts = listOf(MessagePart.Text("p0", "找到了 3 张", PartState.DONE))),
                entity("m3", role = "user", parts = listOf(MessagePart.Text("p0", "只要有人脸的", PartState.DONE))),
                entity(
                    "m4",
                    parts = listOf(MessagePart.Text("p0", "还剩 1 张", PartState.DONE)),
                    metadata = """{"prompt_len":10}""",
                ),
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
                entity("c1", parts = listOf(MessagePart.Chart("p0", svg = "<svg/>"))),
                entity(
                    "c2",
                    parts = listOf(MessagePart.HtmlCard("p0", html = "<html/>", meta = HtmlCardMeta(summary = "周报"))),
                ),
                entity(
                    "c3",
                    parts = listOf(
                        MessagePart.TaskCard(
                            partId = "p0",
                            toolCallId = "t1",
                            state = ToolPartState.OUTPUT_ERROR,
                            task = EngineerTaskState(
                                taskId = "t1",
                                sourceText = "修复编译",
                                status = EngineerTaskStatus.FAILED,
                                errorSummary = "编译失败",
                                startedAtMs = 1,
                                updatedAtMs = 2,
                            ),
                        ),
                    ),
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
    fun `unsupported parts stay out of model input`() {
        // 防御路径（spec §6）：Unsupported（前向兼容占位，含 legacy 残留语义）不进上下文——
        // 模型从未见过的占位类型进上下文只会引入噪声
        val pairs = newPairs(
            listOf(
                entity(
                    "r1",
                    parts = listOf(MessagePart.Unsupported("p0", originalType = "command", summary = "search:猫")),
                ),
            ),
        )
        assertEquals(emptyList<Pair<String, String>>(), pairs)
    }

    @Test
    fun `data parts are absent while images and edit results replay as text`() {
        val pairs = newPairs(
            listOf(
                entity("d1", role = "user", parts = listOf(MessagePart.Text("p0", "看图", PartState.DONE))),
                entity("d2", role = "user", parts = listOf(MessagePart.Image("p0", ref = "/data/img.jpg"))),
                entity(
                    "d3",
                    parts = listOf(
                        MessagePart.OptimizeCandidates(
                            "p0",
                            OptimizeCandidateGroup("u", "scene", 0, emptyList(), emptyList(), 0),
                        ),
                    ),
                ),
                entity("d4", parts = listOf(MessagePart.EditResult("p0", ref = "/i.jpg", description = "已提亮"))),
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
}
