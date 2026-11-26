package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [KoogSessionHistoryProvider] system prompt 前置补偿单测（2026-11-26 真机根因修复）。
 *
 * Koog 1.3.0 ChatMemory feature 合并语义：历史非空即整表替换 prompt 消息
 *（`historyMessages.ifEmpty { initialMessages + historyMessages }`），initialMessages 里的
 * system prompt 被丢弃；store 不变式①（withoutSystem）又在持久化/加载两侧剥离 system——
 * 两边叠加 = 会话一旦有历史，系统提示词整段丢失（新会话第一轮正常、第二轮起静默丢失）。
 * provider 携带宿主 buildAgent 当次组装的成品 system prompt（不可变构造参数，接线由类型
 * 系统强制），load 时重新前置；store 保存时 withoutSystem 自动剥离，往返一致。
 */
class KoogSessionHistoryProviderTest {

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)
    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)
    private fun system(text: String) = Message.System(text, RequestMetaInfo.Empty)

    /** 模拟 store 不变式①：历史绝不携带 system（加载侧已剥离）。 */
    private class FakeStore(
        private val history: List<Message>,
    ) : ChatMemoryStore {
        override suspend fun load(sessionId: String): List<Message> = history
        override suspend fun save(sessionId: String, messages: List<Message>) = Unit
        override suspend fun clear(sessionId: String) = Unit
        override suspend fun loadSummary(sessionId: String): SessionCompaction? = null
        override suspend fun saveSummary(sessionId: String, summary: SessionCompaction) = Unit
        override suspend fun clearSummary(sessionId: String) = Unit
    }

    @Test
    fun `load prepends system prompt ahead of stored history`() = runTest {
        val provider = KoogSessionHistoryProvider(
            FakeStore(listOf(user("u1"), assistant("a1"))),
            "你是 PoLang 相册 AI 助手。",
        )

        val loaded = provider.load("s")

        assertEquals(3, loaded.size)
        val first = loaded.first()
        assertIs<Message.System>(first)
        assertEquals("你是 PoLang 相册 AI 助手。", first.textContent())
        assertEquals(listOf("u1", "a1"), loaded.drop(1).map { it.textContent() })
    }

    @Test
    fun `load passes history through when system prompt is blank`() = runTest {
        val history = listOf(user("u1"))
        val provider = KoogSessionHistoryProvider(FakeStore(history), "   ")
        assertEquals(history, provider.load("s"))
    }

    @Test
    fun `load prepends system even for empty history so fresh sessions stay identical`() = runTest {
        val provider = KoogSessionHistoryProvider(FakeStore(emptyList()), "sys")

        val loaded = provider.load("s")

        // Koog 合并语义下空历史本会保留 initialMessages（含同一份 system）——provider 前置后
        // 触发替换分支产物同为单条 System，两分支形态统一。
        assertEquals(1, loaded.size)
        assertTrue(loaded.first() is Message.System)
    }

    @Test
    fun `load never produces double system even if store breaks invariant 1`() = runTest {
        // 防御钉住：store 未守不变式①（或 Koog 合并语义未来变更）时不得产生双 System
        // ——部分 OpenAI 兼容网关对多 System 直接 400。
        val provider = KoogSessionHistoryProvider(
            FakeStore(listOf(system("stale"), user("u1"))),
            "current",
        )

        val loaded = provider.load("s")

        assertEquals(1, loaded.count { it is Message.System })
        assertEquals("stale", loaded.first().textContent())
    }

    @Test
    fun `each build carries its own prompt so rebuilds cannot cross-contaminate`() = runTest {
        val store = FakeStore(listOf(user("u1")))
        val v1 = KoogSessionHistoryProvider(store, "v1")
        val v2 = KoogSessionHistoryProvider(store, "v2")

        assertEquals("v1", v1.load("s").first().textContent())
        assertEquals("v2", v2.load("s").first().textContent())
    }
}
