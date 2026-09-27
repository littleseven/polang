package com.mamba.picme.agent.core.platform.storage

import com.mamba.picme.agent.core.inference.remote.koog.CompactionSlots
import com.mamba.picme.agent.core.inference.remote.koog.SessionCompaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * M2 摘要存取契约测试（US-2.3，commonTest——双端 actual 共用契约）。
 *
 * 用内存假 store 验证 [ChatMemoryStore] 摘要协议的语义（接口演进后所有 actual 必须满足的契约）：
 * - saveSummary → loadSummary 往返保持全字段；
 * - version 单调语义由调用方（KoogChatAgent）保证，store 只忠实地存取；
 * - 无摘要时 loadSummary 返回 null（不是空 CompactionSlots——区分「从未压过」与「压过但全空」）；
 * - clearSummary 幂等（无摘要时调用不炸）；
 * - clear 同时清摘要与会话历史（会话销毁 = 全清）。
 */
class ChatMemoryStoreSummaryTest {

    /** 内存假 store：实现摘要协议的参考语义（双端 actual 的行为镜像）。 */
    private class InMemoryStore : ChatMemoryStore {
        private val history = mutableMapOf<String, List<ai.koog.prompt.message.Message>>()
        private val summaries = mutableMapOf<String, SessionCompaction>()

        override suspend fun load(sessionId: String) = history[sessionId] ?: emptyList()
        override suspend fun save(sessionId: String, messages: List<ai.koog.prompt.message.Message>) {
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

    private fun summary(version: Int, turnId: Int) = SessionCompaction(
        version = version,
        slots = CompactionSlots(
            intent = "intent-$version",
            decisions = listOf("d$version"),
            todos = listOf("t$version"),
            entities = listOf("e$version"),
        ),
        compactedUpToTurnId = turnId,
        createdAt = version * 1000L,
    )

    @Test
    fun `saveSummary 后 loadSummary 往返保持全字段`() = runTest {
        val store = InMemoryStore()
        val original = summary(version = 3, turnId = 7)
        store.saveSummary("s1", original)
        assertEquals(original, store.loadSummary("s1"))
    }

    @Test
    fun `无摘要时 loadSummary 返回 null`() = runTest {
        val store = InMemoryStore()
        assertNull(store.loadSummary("never-compacted"))
    }

    @Test
    fun `saveSummary 覆盖同 session 旧版本`() = runTest {
        val store = InMemoryStore()
        store.saveSummary("s1", summary(version = 1, turnId = 2))
        store.saveSummary("s1", summary(version = 2, turnId = 5))
        assertEquals(2, store.loadSummary("s1")?.version)
        assertEquals(5, store.loadSummary("s1")?.compactedUpToTurnId)
    }

    @Test
    fun `不同 session 摘要互不影响`() = runTest {
        val store = InMemoryStore()
        store.saveSummary("s1", summary(version = 1, turnId = 1))
        store.saveSummary("s2", summary(version = 9, turnId = 9))
        assertEquals(1, store.loadSummary("s1")?.version)
        assertEquals(9, store.loadSummary("s2")?.version)
    }

    @Test
    fun `clearSummary 幂等`() = runTest {
        val store = InMemoryStore()
        store.clearSummary("nothing") // 不炸
        store.saveSummary("s1", summary(version = 1, turnId = 1))
        store.clearSummary("s1")
        assertNull(store.loadSummary("s1"))
        store.clearSummary("s1") // 再清不炸
    }

    @Test
    fun `clear 同时清历史与摘要`() = runTest {
        val store = InMemoryStore()
        store.saveSummary("s1", summary(version = 1, turnId = 1))
        store.clear("s1")
        assertNull(store.loadSummary("s1"))
    }
}
