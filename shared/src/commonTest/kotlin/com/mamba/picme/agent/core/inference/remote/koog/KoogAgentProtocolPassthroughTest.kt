package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.agent.core.remote.config.RemoteProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * protocol/providerId 从 [RemoteReActAgentConfig] 到 [KoogChatAgent] 工厂产物的透传契约测试。
 *
 * 历史断裂：KoogChatAgent 重建 RemoteModelConfig 时丢 protocol/providerId（落默认 OPENAI/""），
 * 导致 Anthropic 配置实际走 OpenAI 协议、thinking 禁用注入误伤 OpenAI 官方。
 * 本测试钉住「agent 配置携带 protocol → 工厂分流生效」的端到端语义（不发网络请求）。
 */
class KoogAgentProtocolPassthroughTest {

    private val fakeMemoryStore = object : ChatMemoryStore {
        override suspend fun load(sessionId: String): List<Message> = emptyList()
        override suspend fun save(sessionId: String, messages: List<Message>) {}
        override suspend fun clear(sessionId: String) {}
        override suspend fun loadSummary(sessionId: String): SessionCompaction? = null
        override suspend fun saveSummary(sessionId: String, summary: SessionCompaction) {}
        override suspend fun clearSummary(sessionId: String) {}
    }

    private fun buildAgent(protocol: RemoteProtocol, providerId: String): KoogChatAgent =
        KoogChatAgent(
            config = RemoteReActAgentConfig.Builder()
                .apiKey("test-key")
                .baseUrl("https://example.com/")
                .modelName("test-model")
                .protocol(protocol)
                .providerId(providerId)
                .build(),
            toolRegistry = ToolRegistry {},
            memoryStore = fakeMemoryStore,
        )

    @Test
    fun `claude protocol reaches executor bundle as anthropic model`() {
        val agent = buildAgent(RemoteProtocol.CLAUDE, "anthropic-official")
        assertEquals(LLMProvider.Anthropic, agent.executorBundle.model.provider)
        assertNull(agent.executorBundle.baseParams.additionalProperties)
    }

    @Test
    fun `openai protocol with openai-official skips thinking injection`() {
        val agent = buildAgent(RemoteProtocol.OPENAI, "openai-official")
        assertEquals(LLMProvider.OpenAI, agent.executorBundle.model.provider)
        assertNull(agent.executorBundle.baseParams.additionalProperties)
    }

    @Test
    fun `openai protocol with deepseek keeps thinking injection`() {
        val agent = buildAgent(RemoteProtocol.OPENAI, "deepseek-official")
        val props = agent.executorBundle.baseParams.additionalProperties
        assertNotNull(props)
        assertTrue(props.containsKey("thinking"))
    }
}
