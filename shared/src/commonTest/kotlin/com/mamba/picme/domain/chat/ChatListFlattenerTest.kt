package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [flattenChatItems]（ADR-016 M4，spec §7.2/§7.3）钉桩：
 * 拍平粒度 / key 与 contentType / turn 边界 / 流式卡 part 三分流 / paced 文本覆盖。
 */
class ChatListFlattenerTest {

    private fun textPart(id: String, text: String, state: PartState = PartState.DONE) =
        MessagePart.Text(partId = id, markdown = text, state = state)

    private fun agentText(id: String, text: String, parts: List<MessagePart>? = null) = ChatMessage(
        id = id,
        type = ChatMessageType.AGENT_TEXT,
        content = text,
        parts = parts ?: listOf(textPart("p0", text)),
    )

    @Test
    fun `user message flattens to a single whole item and opens a turn`() {
        val items = flattenChatItems(
            listOf(
                ChatMessage(id = "u1", type = ChatMessageType.USER_TEXT, content = "你好"),
                agentText("a1", "你好呀"),
            ),
        )
        assertEquals(2, items.size)
        assertEquals(ChatListItem.TYPE_USER_MESSAGE, items[0].contentType)
        assertEquals("u1:whole", items[0].key)
        assertEquals(0, items[0].turnIndex)
        assertTrue(items[0].isTurnStart)
        assertEquals(ChatListItem.TYPE_AGENT_TEXT, items[1].contentType)
        assertEquals("a1:p0", items[1].key)
        assertEquals(0, items[1].turnIndex)
        assertFalse(items[1].isTurnStart)
        // 用户消息与后续 agent 文本不合并（merge 仅限同 turn 相邻 agent 文本）
        assertFalse(items[1].mergeWithPrevious)
    }

    @Test
    fun `second user message starts a new turn`() {
        val items = flattenChatItems(
            listOf(
                ChatMessage(id = "u1", type = ChatMessageType.USER_TEXT, content = "一"),
                agentText("a1", "答一"),
                ChatMessage(id = "u2", type = ChatMessageType.USER_TEXT, content = "二"),
                agentText("a2", "答二"),
            ),
        )
        assertEquals(listOf(0, 0, 1, 1), items.map { it.turnIndex })
        assertEquals(listOf(true, false, true, false), items.map { it.isTurnStart })
    }

    @Test
    fun `leading agent message forms its own turn`() {
        val items = flattenChatItems(
            listOf(
                agentText("a0", "欢迎"),
                ChatMessage(id = "u1", type = ChatMessageType.USER_TEXT, content = "问"),
            ),
        )
        assertEquals(listOf(0, 1), items.map { it.turnIndex })
        assertTrue(items[0].isTurnStart)
        assertTrue(items[1].isTurnStart)
    }

    @Test
    fun `agent message with multiple parts flattens to per part items`() {
        val message = ChatMessage(
            id = "a1",
            type = ChatMessageType.AGENT_TEXT,
            content = "",
            parts = listOf(
                textPart("p0", "统计如下"),
                MessagePart.Chart(partId = "p1", svg = "<svg/>"),
                textPart("p2", "以上"),
            ),
        )
        val items = flattenChatItems(listOf(message))
        assertEquals(listOf("a1:p0", "a1:p1", "a1:p2"), items.map { it.key })
        assertEquals(
            listOf(ChatListItem.TYPE_AGENT_TEXT, ChatListItem.TYPE_CHART, ChatListItem.TYPE_AGENT_TEXT),
            items.map { it.contentType },
        )
        assertEquals(listOf(false, false, true), items.map { it.isLastPartOfMessage })
        // 卡片隔开的两个文本不合并
        assertFalse(items[2].mergeWithPrevious)
    }

    @Test
    fun `adjacent agent text items in one turn merge`() {
        val streaming = ChatMessage(
            id = "s1",
            type = ChatMessageType.AGENT_TEXT,
            content = "paced",
            isStreaming = true,
            parts = listOf(
                textPart("txt-0", "第一轮"),
                textPart("txt-1", "第二轮", PartState.STREAMING),
            ),
        )
        val items = flattenChatItems(listOf(streaming))
        assertEquals(2, items.size)
        assertFalse(items[0].mergeWithPrevious)
        assertTrue(items[1].mergeWithPrevious)
    }

    @Test
    fun `streaming open text part renders paced content with cursor`() {
        val streaming = ChatMessage(
            id = "s1",
            type = ChatMessageType.AGENT_TEXT,
            content = "打字中",
            isStreaming = true,
            showCursor = true,
            parts = listOf(textPart("txt-0", "全量快照", PartState.STREAMING)),
        )
        val item = flattenChatItems(listOf(streaming)).single()
        assertEquals("打字中", item.textOverride)
        assertTrue(item.showCursor)
    }

    @Test
    fun `done text part has no override and no cursor`() {
        val streaming = ChatMessage(
            id = "s1",
            type = ChatMessageType.AGENT_TEXT,
            content = "正在调用工具",
            isStreaming = true,
            showCursor = false,
            parts = listOf(textPart("txt-0", "已完成")),
        )
        val item = flattenChatItems(listOf(streaming)).single()
        assertNull(item.textOverride)
        assertFalse(item.showCursor)
    }

    @Test
    fun `filled streaming card parts are skipped but placeholders and errors stay`() {
        val streaming = ChatMessage(
            id = "s1",
            type = ChatMessageType.AGENT_TEXT,
            content = "",
            isStreaming = true,
            parts = listOf(
                MessagePart.Chart(partId = "call-0", svg = "<svg/>", state = ToolPartState.OUTPUT_AVAILABLE),
                MessagePart.HtmlCard(partId = "call-1", html = "", state = ToolPartState.INPUT_STREAMING),
                MessagePart.Chart(partId = "call-2", svg = "", state = ToolPartState.OUTPUT_ERROR),
            ),
        )
        val items = flattenChatItems(listOf(streaming))
        // OUTPUT_AVAILABLE 跳过（产物已落库为独立消息行渲染）；占位与失败保留
        assertEquals(listOf("s1:call-1", "s1:call-2"), items.map { it.key })
        assertEquals(
            listOf(ChatListItem.TYPE_TOOL_PLACEHOLDER, ChatListItem.TYPE_TOOL_ERROR),
            items.map { it.contentType },
        )
    }

    @Test
    fun `persisted chart part always renders`() {
        val persisted = ChatMessage(
            id = "c1",
            type = ChatMessageType.CHART,
            content = "<svg/>",
            parts = listOf(MessagePart.Chart(partId = "p0", svg = "<svg/>")),
        )
        val items = flattenChatItems(listOf(persisted))
        assertEquals(1, items.size)
        assertEquals(ChatListItem.TYPE_CHART, items[0].contentType)
    }

    @Test
    fun `claude agent and command messages render as whole legacy items`() {
        val claude = ChatMessage(
            id = "cl1",
            type = ChatMessageType.AGENT_TEXT,
            content = "",
            claudeAgent = ClaudeAgentState(text = "工作中"),
            parts = listOf(textPart("p0", "")),
        )
        val command = ChatMessage(
            id = "cmd1",
            type = ChatMessageType.COMMAND,
            content = "/task x",
            parts = listOf(textPart("p0", "/task x")),
        )
        val items = flattenChatItems(listOf(claude, command))
        assertEquals(listOf(ChatListItem.TYPE_LEGACY_MESSAGE, ChatListItem.TYPE_LEGACY_MESSAGE), items.map { it.contentType })
        assertEquals(listOf("cl1:p0", "cmd1:p0"), items.map { it.key })
        assertTrue(items.all { it.part == null })
    }

    @Test
    fun `message without parts falls back to whole legacy item`() {
        val thinking = ChatMessage(
            id = "s1",
            type = ChatMessageType.AGENT_TEXT,
            content = "思考中",
            isStreaming = true,
            isThinking = true,
        )
        val item = flattenChatItems(listOf(thinking)).single()
        assertEquals(ChatListItem.TYPE_LEGACY_MESSAGE, item.contentType)
        assertEquals("s1:whole", item.key)
    }

    @Test
    fun `pending non card tool appends status item after streaming message`() {
        val streaming = ChatMessage(
            id = "s1",
            type = ChatMessageType.AGENT_TEXT,
            content = "正在调用工具",
            isStreaming = true,
            parts = listOf(textPart("txt-0", "前文")),
        )
        val items = flattenChatItems(listOf(streaming), pendingToolName = "search_media")
        assertEquals(2, items.size)
        val status = items.last()
        assertEquals(ChatListItem.TYPE_TOOL_STATUS, status.contentType)
        assertEquals("s1:tool_status", status.key)
        assertEquals("search_media", status.pendingToolName)
        assertEquals(streaming.id, status.message.id)
    }

    @Test
    fun `pending tool without streaming message emits no status item`() {
        val items = flattenChatItems(listOf(agentText("a1", "完")), pendingToolName = "search_media")
        assertEquals(1, items.size)
    }
}
