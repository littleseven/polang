package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 分层记忆 M4 投影测试：长期轨 [ModelInputItem] 序列 → 短期轨 Koog [Message] 序列。
 *
 * 验证 [toKoogMemoryMessages] 的映射语义：
 * - 文本按消息级 role 落 User/Assistant；
 * - ToolCall/ToolResult 落 Assistant(Call)+User(Result)，配对 id 与相邻性保持
 *  （store 三不变式的前提：投影产物天然配对）；
 * - 空 argsSummary 落 `"{}"`（线格式要求 arguments 恒为 JSON 串）；
 * - isError 追加 ` (failed)` 文本标记；
 * - 输入顺序即输出顺序；
 * - 产物经持久化组装全管线（assembleForPersistence + sanitizeToolPairing）后配对不变。
 */
class KoogMemoryProjectionTest {

    private fun textParts(message: Message): List<MessagePart.Text> =
        message.parts.filterIsInstance<MessagePart.Text>()

    private fun callParts(message: Message): List<MessagePart.Tool.Call> =
        message.parts.filterIsInstance<MessagePart.Tool.Call>()

    private fun resultParts(message: Message): List<MessagePart.Tool.Result> =
        message.parts.filterIsInstance<MessagePart.Tool.Result>()

    @Test
    fun `用户文本投影为 User 消息`() {
        val messages = listOf(ModelInputItem.TextMessage(ModelInputRole.USER, "你好")).toKoogMemoryMessages()
        val message = messages.single()
        assertIs<Message.User>(message)
        assertEquals("你好", textParts(message).single().text)
    }

    @Test
    fun `助手文本投影为 Assistant 消息`() {
        val messages = listOf(ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "已找到 3 张")).toKoogMemoryMessages()
        val message = messages.single()
        assertIs<Message.Assistant>(message)
        assertEquals("已找到 3 张", textParts(message).single().text)
    }

    @Test
    fun `工具调用与结果投影为相邻配对且 id 一致`() {
        val messages = listOf(
            ModelInputItem.ToolCall("m1:p1", "draw_chart", """{"kind":"bar"}"""),
            ModelInputItem.ToolResult("m1:p1", "draw_chart", "Chart card (SVG, 512 chars)"),
        ).toKoogMemoryMessages()

        assertEquals(2, messages.size)
        val call = callParts(messages[0]).single()
        val result = resultParts(messages[1]).single()
        assertIs<Message.Assistant>(messages[0])
        assertIs<Message.User>(messages[1])
        assertEquals("m1:p1", call.id)
        assertEquals("m1:p1", result.id)
        assertEquals("draw_chart", call.tool)
        assertEquals("draw_chart", result.tool)
    }

    @Test
    fun `空 argsSummary 落空 JSON 对象串`() {
        val messages = listOf(
            ModelInputItem.ToolCall("m1:p1", "render_html", ""),
        ).toKoogMemoryMessages()
        assertEquals("{}", callParts(messages.single()).single().args)
    }

    @Test
    fun `失败工具结果追加 failed 文本标记`() {
        val messages = listOf(
            ModelInputItem.ToolResult("m1:p1", "engineer_task", "编译失败", isError = true),
        ).toKoogMemoryMessages()
        assertEquals("编译失败 (failed)", resultParts(messages.single()).single().output)
    }

    @Test
    fun `输入顺序即输出顺序`() {
        val items = listOf(
            ModelInputItem.TextMessage(ModelInputRole.USER, "u1"),
            ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "a1"),
            ModelInputItem.ToolCall("m:p", "t", "{}"),
            ModelInputItem.ToolResult("m:p", "t", "ok"),
            ModelInputItem.TextMessage(ModelInputRole.USER, "u2"),
        )
        val messages = items.toKoogMemoryMessages()
        assertEquals(5, messages.size)
        assertIs<Message.User>(messages[0])
        assertIs<Message.Assistant>(messages[1])
        assertEquals("u1", textParts(messages[0]).single().text)
        assertEquals("u2", textParts(messages[4]).single().text)
    }

    @Test
    fun `投影产物经持久化组装后配对保持完整`() {
        val items = listOf(
            ModelInputItem.TextMessage(ModelInputRole.USER, "给我画个图"),
            ModelInputItem.ToolCall("m1:p1", "draw_chart", ""),
            ModelInputItem.ToolResult("m1:p1", "draw_chart", "Chart card (SVG, 100 chars)"),
            ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "图已生成"),
        )
        val persisted = KoogMessageMemory.sanitizeToolPairing(
            KoogMessageMemory.assembleForPersistence(items.toKoogMemoryMessages()),
        )
        val callIds = persisted.flatMap { message -> callParts(message) }.map { call -> call.id }
        val resultIds = persisted.flatMap { message -> resultParts(message) }.map { result -> result.id }
        assertEquals(callIds, resultIds)
        assertTrue(persisted.size >= 4)
    }
}
