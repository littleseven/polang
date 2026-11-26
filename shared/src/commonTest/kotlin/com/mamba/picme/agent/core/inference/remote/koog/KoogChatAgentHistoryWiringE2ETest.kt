package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KoogChatAgent → KoogSessionHistoryProvider 接线的端到端钉住（评审 🟡B，2026-11-27）。
 *
 * 假 [PromptExecutor] 录制 Koog 策略真正送出的 `prompt.messages`，跑完整 runChat 断言：
 *
 * 1. **传对了 prompt**：出站首条（且唯一一条）System 的文本 = [KoogChatAgent.composeSystemPrompt]
 *    组装成品（含记忆快照/技能清单段）——把 `KoogChatAgent.buildAgent` 里
 *    `KoogSessionHistoryProvider(memoryStoreRef, systemPrompt)` 变异成传 `config.systemPrompt`
 *    基座时本测试必红（类型系统只保证「传了 prompt」，本测试保证「传对了 prompt」）。
 * 2. **跨会话不串摘要**：AIAgent 缓存键含摘要文本本体——两会话同 version 不同摘要内容时
 *    切换会话必须重建（旧实现仅比 version，会复用上一会话 agent 把别人的摘要烘焙进 prompt）。
 *
 * flag off（非流式）路径：executor 注入缝与 historyProvider 接线不区分 flag，
 * 用最简单的 execute 路径即可达到断言面。
 */
class KoogChatAgentHistoryWiringE2ETest {

    private class RecordingPromptExecutor : PromptExecutor() {
        val recorded = mutableListOf<List<Message>>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            recorded += prompt.messages
            return Message.Assistant("好的。", ResponseMetaInfo.Empty)
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = throw UnsupportedOperationException("flag-off 测试不走流式")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw UnsupportedOperationException()

        override fun close() = Unit
    }

    private class FakeStore(
        private val history: List<Message> = emptyList(),
        private val summaries: Map<String, SessionCompaction> = emptyMap(),
    ) : ChatMemoryStore {
        val saved = mutableListOf<List<Message>>()

        override suspend fun load(sessionId: String): List<Message> = history
        override suspend fun save(sessionId: String, messages: List<Message>) {
            saved += messages
        }
        override suspend fun clear(sessionId: String) = Unit
        override suspend fun loadSummary(sessionId: String): SessionCompaction? = summaries[sessionId]
        override suspend fun saveSummary(sessionId: String, summary: SessionCompaction) = Unit
        override suspend fun clearSummary(sessionId: String) = Unit
    }

    private fun agentWith(
        store: ChatMemoryStore,
        executor: RecordingPromptExecutor,
        snapshot: String? = null,
        skillSection: String? = null,
    ): KoogChatAgent {
        val config = RemoteReActAgentConfig.Builder()
            .apiKey("test-key")
            .baseUrl("https://example.com/")
            .modelName("test-model")
            .systemPrompt("BASE")
            .apply {
                snapshot?.let { text ->
                    memoryContextProvider(object : MemoryContextProvider {
                        override fun snapshot(): String = text
                    })
                }
            }
            .skillPromptProvider(skillSection?.let { section -> { section } })
            .build()
        // model/baseParams 取真实工厂产物（构造无网络），仅 executor 换录制假件；
        // 被丢弃的真实 executor 显式 close（持 Ktor client，测试结束即释放）
        val realBundle = RemoteModelFactory.createKoogExecutor(
            config = RemoteModelConfig(
                modelId = config.modelName,
                providerId = config.providerId,
                protocol = config.protocol,
                apiKey = config.apiKey,
                baseUrl = config.baseUrl,
                gatewayToken = "",
                deviceId = "",
            ),
            extraHeaders = emptyMap(),
        )
        realBundle.executor.close()
        return KoogChatAgent(
            config = config,
            toolRegistry = ToolRegistry {},
            memoryStore = store,
            executorBundleOverride = realBundle.copy(executor = executor),
        )
    }

    @Test
    fun `runChat wires composed system prompt as sole System message over stored history`() = runTest {
        val store = FakeStore(history = listOf(Message.User("旧消息", RequestMetaInfo.Empty)))
        val executor = RecordingPromptExecutor()
        val agent = agentWith(store, executor, snapshot = "快照X", skillSection = "SKILLS段")

        agent.setSessionId("s1")
        agent.runChat("你好", null, {}, { _, _ -> })

        assertEquals(1, executor.recorded.size)
        val messages = executor.recorded.single()
        val systems = messages.filterIsInstance<Message.System>()
        assertEquals(1, systems.size, "出站消息必须有且仅有一条 System")
        assertEquals(messages.first(), systems.single(), "System 必须在首位")
        assertEquals(
            agent.composeSystemPrompt("快照X", null, null, "SKILLS段").trim(),
            systems.single().textContent(),
            "传给 historyProvider 的必须是组装成品 prompt（变异成 config.systemPrompt 基座时此断言变红）",
        )
        assertEquals(listOf("旧消息", "你好"), messages.drop(1).map { it.textContent() })
        // 回写 store 的消息同样只带一条 System（剥离不落盘是 store 不变式①的职责，另有其测试钉住）
        assertEquals(1, store.saved.last().filterIsInstance<Message.System>().size)
    }

    @Test
    fun `agent rebuilds when switching to session with same summary version but different text`() = runTest {
        val store = FakeStore(
            summaries = mapOf(
                "s1" to SessionCompaction(1, CompactionSlots(intent = "摘要A意图"), 0, 0L),
                "s2" to SessionCompaction(1, CompactionSlots(intent = "摘要B意图"), 0, 0L),
            ),
        )
        val executor = RecordingPromptExecutor()
        val agent = agentWith(store, executor)

        agent.setSessionId("s1")
        agent.runChat("你好", null, {}, { _, _ -> })
        agent.setSessionId("s2")
        agent.runChat("在吗", null, {}, { _, _ -> })

        assertEquals(2, executor.recorded.size)
        val first = executor.recorded[0].filterIsInstance<Message.System>().single().textContent()
        val second = executor.recorded[1].filterIsInstance<Message.System>().single().textContent()
        assertTrue(first.contains("摘要A意图"), "s1 的 prompt 应烘焙 s1 摘要")
        assertTrue(
            second.contains("摘要B意图"),
            "同 version 不同摘要文本必须触发重建（旧实现仅比 version 会复用 s1 的 agent 串摘要）",
        )
        assertFalse(second.contains("摘要A意图"), "s2 的 prompt 不得残留 s1 摘要")
    }
}
