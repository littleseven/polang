package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecord
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 流式轮 llm_call_log 补录（Task 13）：Koog 1.3.0 流式路径（executeStreaming）只发
 * onLLMStreaming* 事件、不触发 onLLMCallCompleted（ContextualPromptExecutor 字节码实证），
 * 原录制点在流式回合整段缺失——[recordStreamingLlmCall] 以 End 帧 metaInfo 补录，
 * 字段口径与非流式录制（onLLMCallCompleted handler）对齐。
 */
class KoogChatAgentStreamingRecordTest {

    private val recorded = mutableListOf<LlmCallRecord>()

    @AfterTest
    fun tearDown() {
        RemoteModelFactory.recorder = null
        RemoteModelFactory.wireTap = null
        RemoteModelFactory.captureContent = true
    }

    @Test
    fun `streaming round records call aligned with non-streaming field set`() {
        RemoteModelFactory.recorder = { record -> recorded.add(record) }
        val meta = ResponseMetaInfo.Empty.copy(
            inputTokensCount = 1200,
            outputTokensCount = 340,
            totalTokensCount = 1540,
        )
        val roundText = "// summary: 已为你整理好照片\nroot = Markdown(\"这是回复正文\")"

        recordStreamingLlmCall(
            model = LLModel(provider = LLMProvider.OpenAI, id = "deepseek-v4-flash"),
            meta = meta,
            roundText = roundText,
            traceId = "trace-42",
        )

        val record = recorded.single()
        assertEquals("chat-koog", record.source)
        assertEquals("deepseek-v4-flash", record.model)
        assertEquals(true, record.success)
        assertEquals(null, record.latencyMs)
        assertEquals(1200, record.promptTokens)
        assertEquals(340, record.completionTokens)
        assertEquals(1540, record.totalTokens)
        assertEquals(null, record.errorMessage)
        assertEquals("trace-42", record.traceId)
        assertEquals(null, record.cachedTokens)
        assertEquals("", record.requestJson)
        assertEquals(roundText, record.responseJson)
        assertTrue(record.createdAt > 0)
    }

    @Test
    fun `release mode omits response text but keeps metrics`() {
        RemoteModelFactory.recorder = { record -> recorded.add(record) }
        RemoteModelFactory.captureContent = false
        val meta = ResponseMetaInfo.Empty.copy(inputTokensCount = 90, outputTokensCount = 12)

        recordStreamingLlmCall(
            model = LLModel(provider = LLMProvider.OpenAI, id = "m1"),
            meta = meta,
            roundText = "root = Markdown(\"内容\")",
            traceId = null,
        )

        val record = recorded.single()
        assertNull(record.responseJson)
        assertEquals(90, record.promptTokens)
        assertEquals(12, record.completionTokens)
        assertNull(record.traceId)
    }

    @Test
    fun `blank round text records null response summary`() {
        RemoteModelFactory.recorder = { record -> recorded.add(record) }

        recordStreamingLlmCall(
            model = null,
            meta = null,
            roundText = "",
            traceId = null,
        )

        val record = recorded.single()
        assertNull(record.responseJson)
        assertNull(record.model)
        assertNull(record.promptTokens)
        assertNull(record.completionTokens)
        assertNull(record.totalTokens)
    }

    @Test
    fun `no recorder injected is a no-op`() {
        RemoteModelFactory.recorder = null

        recordStreamingLlmCall(
            model = LLModel(provider = LLMProvider.OpenAI, id = "m1"),
            meta = ResponseMetaInfo.Empty,
            roundText = "root = Markdown(\"x\")",
            traceId = null,
        )

        assertTrue(recorded.isEmpty())
    }
}
