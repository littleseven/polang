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
 * provider 在 load 时把 [KoogSessionHistoryProvider.currentSystemPrompt] 重新前置；
 * store 保存时 withoutSystem 自动剥离，往返一致。
 */
class KoogSessionHistoryProviderTest {

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)
    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)

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
    fun `load prepends current system prompt ahead of stored history`() = runTest {
        val provider = KoogSessionHistoryProvider(FakeStore(listOf(user("u1"), assistant("a1"))))
        provider.currentSystemPrompt = "你是 PoLang 相册 AI 助手。"

        val loaded = provider.load("s")

        assertEquals(3, loaded.size)
        val first = loaded.first()
        assertIs<Message.System>(first)
        assertEquals("你是 PoLang 相册 AI 助手。", first.textContent())
        assertEquals(listOf("u1", "a1"), loaded.drop(1).map { it.textContent() })
    }

    @Test
    fun `load passes history through when system prompt slot unset or blank`() = runTest {
        val history = listOf(user("u1"))
        val unset = KoogSessionHistoryProvider(FakeStore(history))
        assertEquals(history, unset.load("s"))

        val blank = KoogSessionHistoryProvider(FakeStore(history))
        blank.currentSystemPrompt = "   "
        assertEquals(history, blank.load("s"))
    }

    @Test
    fun `load prepends system even for empty history so fresh sessions stay identical`() = runTest {
        val provider = KoogSessionHistoryProvider(FakeStore(emptyList()))
        provider.currentSystemPrompt = "sys"

        val loaded = provider.load("s")

        // Koog 合并语义下空历史本会保留 initialMessages（含 system）——provider 前置后
        // 变成 system×1（store 侧）+ initialMessages 不再被采用，形态与非空历史分支统一。
        assertEquals(1, loaded.size)
        assertTrue(loaded.first() is Message.System)
    }

    @Test
    fun `slot refresh between agent rebuilds swaps the prepended prompt`() = runTest {
        val provider = KoogSessionHistoryProvider(FakeStore(listOf(user("u1"))))
        provider.currentSystemPrompt = "v1"
        provider.currentSystemPrompt = "v2"

        val loaded = provider.load("s")

        assertEquals("v2", loaded.first().textContent())
    }
}
