package com.mamba.picme.agent.core.inference.remote

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.Message
import com.mamba.picme.agent.core.facade.AgentConfigurator
import com.mamba.picme.agent.core.inference.local.ImageInferenceEngine
import com.mamba.picme.agent.core.inference.remote.koog.SessionCompaction
import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.agent.core.platform.thread.DispatcherProvider
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * U2-3：RemoteChatEngine OpenUI turn-native 开关契约——engine 层终结输出兜底
 * （[RemoteChatEngine.wrapFinalOutput] 纯函数直测）+ getChatAgent 缓存失效
 *（开关现值入 configChanged 比对，翻转即重建 agent）。
 */
class RemoteChatEngineTest {

    private val fakeMemoryStore = object : ChatMemoryStore {
        override suspend fun load(sessionId: String): List<Message> = emptyList()
        override suspend fun save(sessionId: String, messages: List<Message>) {}
        override suspend fun clear(sessionId: String) {}
        override suspend fun loadSummary(sessionId: String): SessionCompaction? = null
        override suspend fun saveSummary(sessionId: String, summary: SessionCompaction) {}
        override suspend fun clearSummary(sessionId: String) {}
    }

    private val fakeImageEngine = object : ImageInferenceEngine {
        override val isLoaded: Boolean = false
        override fun isModelAvailable(modelId: String): Boolean = false
        override suspend fun loadModel(modelId: String, useOpencl: Boolean): Result<Unit> =
            Result.failure(UnsupportedOperationException("no local model in test"))
        override fun unload() {}
        override suspend fun imageInference(
            imageBytes: ByteArray,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
        ): String = ""
        override suspend fun imageInferenceWithTimeout(
            imageBytes: ByteArray,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
            timeoutMs: Int,
        ): String = ""
    }

    private fun newEngine(provider: (() -> Boolean)? = null): RemoteChatEngine {
        val configurator = AgentConfigurator(
            dispatcherProvider = DispatcherProvider(),
            chatMemoryStore = fakeMemoryStore,
            imageEngineProvider = { fakeImageEngine },
            remoteImToolRegistryProvider = { ToolRegistry {} },
        ).apply {
            provider?.let { setOpenUiTurnNativeProvider(it) }
            // getChatAgent 兜底走 PICME_SERVER_DEFAULT（空 key 过不了 Builder 校验），
            // 测试显式配一份可用远程配置，走真实重建路径。
            updateRemoteRuntimeConfig(
                RemoteModelConfig(modelId = "test-model", baseUrl = "https://example.com/", apiKey = "test-key")
            )
        }
        return RemoteChatEngine(
            configurator = configurator,
            chatToolDescriptors = emptyList(),
            chatToolRegistry = ToolRegistry {},
        )
    }

    // ── wrapFinalOutput：off 原样（逐字节不变红线）──

    @Test
    fun `wrapFinalOutput off returns summary byte-identically`() {
        val summary = "你好呀，我是小浪\n\n含引号\"与反斜杠\\的纯文本"
        assertEquals(
            summary,
            RemoteChatEngine.wrapFinalOutput(summary, enabled = false, language = ReplyLanguage.SIMPLIFIED_CHINESE),
        )
    }

    // ── wrapFinalOutput：on 时非程序 summary 兜底包装 ──

    @Test
    fun `wrapFinalOutput on wraps plain text as Markdown program`() {
        val wrapped = RemoteChatEngine.wrapFinalOutput(
            "你好呀，我是小浪",
            enabled = true,
            language = ReplyLanguage.SIMPLIFIED_CHINESE,
        )
        assertTrue(wrapped.startsWith("// summary: 文本回复\n"), "应带简中包装标签首行，实际：$wrapped")
        assertTrue(wrapped.contains("root = Markdown(\"你好呀，我是小浪\")"), "正文应收进 root Markdown 程序")
    }

    @Test
    fun `wrapFinalOutput on escapes quotes backslash and newline in wrapped text`() {
        val wrapped = RemoteChatEngine.wrapFinalOutput(
            "第一行\"引号\"\n第二行\\反斜杠",
            enabled = true,
            language = ReplyLanguage.SIMPLIFIED_CHINESE,
        )
        assertTrue(
            wrapped.contains("root = Markdown(\"第一行\\\"引号\\\"\\n第二行\\\\反斜杠\")"),
            "包装应对正文做 OpenUI 字符串转义，实际：$wrapped",
        )
    }

    @Test
    fun `wrapFinalOutput on passes already-valid program through`() {
        val program = "// summary: 测试\nroot = Markdown(\"你好\")"
        assertEquals(
            program,
            RemoteChatEngine.wrapFinalOutput(program, enabled = true, language = ReplyLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `wrapFinalOutput on respects reply language for wrapped label`() {
        val wrapped = RemoteChatEngine.wrapFinalOutput("hello", enabled = true, language = ReplyLanguage.ENGLISH)
        assertTrue(wrapped.startsWith("// summary: Text reply\n"), "包装标签应随回复语言五语化，实际：$wrapped")
    }

    // ── getChatAgent 缓存失效：开关现值入 configChanged 比对 ──

    @Test
    fun `switch false to true rebuilds chat agent`() {
        var enabled = false
        val engine = newEngine(provider = { enabled })
        val before = engine.getChatAgent(AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE)
        assertNotNull(before)
        assertSame(
            before,
            engine.getChatAgent(AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE),
            "开关现值不变时应复用缓存 agent",
        )

        enabled = true
        val after = engine.getChatAgent(AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE)
        assertNotNull(after)
        assertNotSame(before, after, "开关 false→true 应触发 agent 重建")
    }

    @Test
    fun `switch true to false rebuilds chat agent`() {
        var enabled = true
        val engine = newEngine(provider = { enabled })
        val onAgent = engine.getChatAgent(AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE)
        assertNotNull(onAgent)

        enabled = false
        val offAgent = engine.getChatAgent(AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE)
        assertNotNull(offAgent)
        assertNotSame(onAgent, offAgent, "开关 true→false 同属配置变更，应触发重建回退")
    }

    @Test
    fun `provider not injected keeps single cached agent`() {
        // 默认 null provider = off：非 Android 平台零行为变化——重复调用恒复用
        val engine = newEngine(provider = null)
        val a = engine.getChatAgent(AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE)
        val b = engine.getChatAgent(AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE)
        assertNotNull(a)
        assertSame(a, b)
    }
}
