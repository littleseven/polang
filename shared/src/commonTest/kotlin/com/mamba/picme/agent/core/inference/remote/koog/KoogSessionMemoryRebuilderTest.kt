package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 分层记忆 M4 冷启动重建测试（[KoogSessionMemoryRebuilder]）。
 *
 * 语义契约：
 * - 短期轨（Koog 记忆）非空 → 幂等不动，projector 不被调用（正常路径零投影开销）；
 * - 短期轨空 + 长期轨有历史 → 投影重建写入，返回 true；
 * - 短期轨空 + 长期轨为空（全新会话）→ 不写，返回 false；
 * - 不同 session 互不影响。
 */
class KoogSessionMemoryRebuilderTest {

    /** 内存假 store（同 ChatMemoryStoreSummaryTest 的参考语义）。 */
    private class InMemoryStore : ChatMemoryStore {
        private val history = mutableMapOf<String, List<Message>>()
        private val summaries = mutableMapOf<String, SessionCompaction>()

        override suspend fun load(sessionId: String) = history[sessionId] ?: emptyList()
        override suspend fun save(sessionId: String, messages: List<Message>) {
            history[sessionId] = messages
        }

        override suspend fun clear(sessionId: String) {
            history.remove(sessionId)
            summaries.remove(sessionId)
        }

        override suspend fun loadSummary(sessionId: String): SessionCompaction? = summaries[sessionId]
        override suspend fun saveSummary(sessionId: String, summary: SessionCompaction) {
            summaries[sessionId] = summary
        }

        override suspend fun clearSummary(sessionId: String) {
            summaries.remove(sessionId)
        }
    }

    private fun archivedItems() = listOf(
        ModelInputItem.TextMessage(ModelInputRole.USER, "帮我把昨天的照片归档"),
        ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "已归档 12 张"),
        ModelInputItem.ToolCall("m1:p1", "draw_chart", ""),
        ModelInputItem.ToolResult("m1:p1", "draw_chart", "Chart card (SVG, 100 chars)"),
    )

    @Test
    fun `短期轨非空时不重建且不调用 projector`() = runTest {
        val store = InMemoryStore()
        store.save("s1", listOf(Message.User("已有记忆", RequestMetaInfo.Empty)))
        var projectorCalled = false

        val rebuilt = KoogSessionMemoryRebuilder(store).rebuildIfEmpty("s1") { _ ->
            projectorCalled = true
            archivedItems()
        }

        assertFalse(rebuilt)
        assertFalse(projectorCalled)
        assertEquals(1, store.load("s1").size)
    }

    @Test
    fun `短期轨为空且长期轨有历史时投影重建`() = runTest {
        val store = InMemoryStore()

        val rebuilt = KoogSessionMemoryRebuilder(store).rebuildIfEmpty("s1") { _ -> archivedItems() }

        assertTrue(rebuilt)
        val messages = store.load("s1")
        assertEquals(4, messages.size)
        assertIs<Message.User>(messages[0])
        assertEquals(
            "帮我把昨天的照片归档",
            messages[0].parts.filterIsInstance<MessagePart.Text>().single().text,
        )
    }

    @Test
    fun `短期轨为空且长期轨为空时不写入`() = runTest {
        val store = InMemoryStore()

        val rebuilt = KoogSessionMemoryRebuilder(store).rebuildIfEmpty("s1") { _ -> emptyList() }

        assertFalse(rebuilt)
        assertTrue(store.load("s1").isEmpty())
    }

    @Test
    fun `不同 session 的重建互不影响`() = runTest {
        val store = InMemoryStore()
        store.save("s2", listOf(Message.User("别的会话", RequestMetaInfo.Empty)))
        val rebuilder = KoogSessionMemoryRebuilder(store)

        assertTrue(rebuilder.rebuildIfEmpty("s1") { _ -> archivedItems() })
        assertFalse(rebuilder.rebuildIfEmpty("s2") { _ -> archivedItems() })
        assertEquals(4, store.load("s1").size)
        assertEquals(1, store.load("s2").size)
    }
}
