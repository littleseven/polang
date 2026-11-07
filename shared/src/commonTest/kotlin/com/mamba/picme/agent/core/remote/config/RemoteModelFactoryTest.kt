package com.mamba.picme.agent.core.remote.config

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 远程模型工厂协议分流测试（OpenAI 兼容 / Anthropic 原生 Messages）。
 *
 * 关键约束：
 * - CLAUDE 协议必须产出 Anthropic provider 的 LLModel 且不注入 DeepSeek 系
 *   `thinking.type=disabled`（Anthropic 无此参数语义）。
 * - OpenAI 官方收到未知参数会 400，同样不注入 thinking 禁用。
 * - tokenhub/kimi/deepseek 与无 providerId 的旧配置保持历史行为（注入）。
 */
class RemoteModelFactoryTest {

    private fun config(
        protocol: RemoteProtocol,
        providerId: String,
        modelId: String = "test-model",
    ) = RemoteModelConfig(
        modelId = modelId,
        providerId = providerId,
        protocol = protocol,
        apiKey = "test-key",
        baseUrl = "https://example.com/",
    )

    @Test
    fun `claude protocol produces anthropic model without thinking injection`() {
        val bundle = RemoteModelFactory.createKoogExecutor(
            config(RemoteProtocol.CLAUDE, "anthropic-official", "claude-sonnet-4-5")
        )
        assertEquals(LLMProvider.Anthropic, bundle.model.provider)
        assertEquals("claude-sonnet-4-5", bundle.model.id)
        assertTrue(bundle.model.supports(LLMCapability.Completion))
        assertTrue(bundle.model.supports(LLMCapability.Tools))
        assertNull(bundle.baseParams.additionalProperties)
    }

    @Test
    fun `openai official does not inject thinking disabled`() {
        val bundle = RemoteModelFactory.createKoogExecutor(
            config(RemoteProtocol.OPENAI, "openai-official", "gpt-5")
        )
        assertEquals(LLMProvider.OpenAI, bundle.model.provider)
        assertTrue(bundle.model.supports(LLMCapability.OpenAIEndpoint.Completions))
        assertNull(bundle.baseParams.additionalProperties)
    }

    @Test
    fun `deepseek compatible providers keep thinking disabled injection`() {
        for (providerId in listOf("tencent-tokenhub", "kimi-official", "deepseek-official")) {
            val bundle = RemoteModelFactory.createKoogExecutor(
                config(RemoteProtocol.OPENAI, providerId)
            )
            val props = bundle.baseParams.additionalProperties
            assertNotNull(props, "thinking injection missing for $providerId")
            assertTrue(props.containsKey("thinking"), "thinking key missing for $providerId")
        }
    }

    @Test
    fun `legacy config without providerId keeps historical thinking injection`() {
        val bundle = RemoteModelFactory.createKoogExecutor(
            config(RemoteProtocol.OPENAI, "")
        )
        assertNotNull(bundle.baseParams.additionalProperties)
    }

    @Test
    fun `predefined providers include openai and anthropic`() {
        val openai = RemoteModelConfig.getProvider("openai-official")
        assertNotNull(openai)
        assertEquals(RemoteProtocol.OPENAI, openai.protocol)
        assertEquals("https://api.openai.com/v1/", openai.baseUrl)
        assertTrue(openai.models.isNotEmpty())

        val anthropic = RemoteModelConfig.getProvider("anthropic-official")
        assertNotNull(anthropic)
        assertEquals(RemoteProtocol.CLAUDE, anthropic.protocol)
        // Anthropic Messages 路径由 client 侧拼接 v1/messages，baseUrl 不带 /v1
        assertEquals("https://api.anthropic.com", anthropic.baseUrl)
        assertTrue(anthropic.models.isNotEmpty())
    }

    @Test
    fun `host marker rule injects thinking disabled for deepseek family regardless of providerId`() {
        for (baseUrl in listOf(
            "https://tokenhub.tencentmaas.com",
            "https://api.moonshot.cn",
            "https://api.deepseek.com",
        )) {
            val bundle = RemoteModelFactory.createKoogExecutor(
                RemoteModelConfig(
                    modelId = "test-model",
                    providerId = "server-cloud-agent", // 非空：不走旧配置空串兜底
                    protocol = RemoteProtocol.OPENAI,
                    apiKey = "test-key",
                    baseUrl = baseUrl,
                )
            )
            assertNotNull(bundle.baseParams.additionalProperties, "thinking injection missing for $baseUrl")
        }
    }

    @Test
    fun `host marker rule does not inject for openai official host`() {
        val bundle = RemoteModelFactory.createKoogExecutor(
            RemoteModelConfig(
                modelId = "gpt-5",
                providerId = "server-cloud-agent",
                protocol = RemoteProtocol.OPENAI,
                apiKey = "test-key",
                baseUrl = "https://api.openai.com",
            )
        )
        assertNull(bundle.baseParams.additionalProperties)
    }

    @Test
    fun `custom chatCompletionsPath builds executor bundle`() {
        val bundle = RemoteModelFactory.createKoogExecutor(
            RemoteModelConfig(
                modelId = "test-model",
                providerId = "server-cloud-agent",
                protocol = RemoteProtocol.OPENAI,
                apiKey = "test-key",
                baseUrl = "https://gateway.ai.cloudflare.com",
                chatCompletionsPath = "v1/acc/picme/compat/chat/completions",
            )
        )
        assertEquals("test-model", bundle.model.id)
        assertEquals(LLMProvider.OpenAI, bundle.model.provider)
    }
}
