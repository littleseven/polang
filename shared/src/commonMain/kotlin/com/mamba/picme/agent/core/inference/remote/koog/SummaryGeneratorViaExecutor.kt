package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecord
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import kotlin.time.Clock

/**
 * 经 Koog executor 单发摘要请求的 [SummaryGenerator] 生产实现（S4 网关，纯文本 [PRIVACY] 合规）。
 *
 * 与 [com.mamba.picme.agent.core.intent.IntentRouter] 的单发模式同源：`executor.execute(prompt,
 * model, emptyList())` 返回 `Message.Assistant`，文本在 Text parts。temperature=0（摘要确定性）、
 * maxTokens 限 512（四槽位 JSON 足够）。调用经 `llm_call_log` 录制（source 区分，traceId 串联）。
 */
class SummaryGeneratorViaExecutor(
    private val bundle: RemoteModelFactory.KoogExecutorBundle,
) : SummaryGenerator {

    private val tag = "SummaryGenerator"

    override suspend fun generate(promptText: String): String {
        val params = bundle.baseParams.copy(temperature = 0.0, maxTokens = 512)
        val summaryPrompt = prompt(id = "polang-session-compaction", params = params) {
            user(promptText)
        }
        val started = Clock.System.now().toEpochMilliseconds()
        return try {
            val response = bundle.executor.execute(summaryPrompt, bundle.model, emptyList())
            val text = response.parts
                .filterIsInstance<MessagePart.Text>()
                .joinToString("") { part -> part.text }
            record(started, success = true, text = text, error = null)
            text
        } catch (exception: Exception) {
            record(started, success = false, text = null, error = exception.message)
            Logger.w(tag, "summary LLM call failed: ${exception.message}")
            throw exception
        }
    }

    private fun record(started: Long, success: Boolean, text: String?, error: String?) {
        runCatching {
            RemoteModelFactory.recorder?.record(
                LlmCallRecord(
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                    source = RECORD_SOURCE,
                    model = bundle.model.id,
                    success = success,
                    latencyMs = Clock.System.now().toEpochMilliseconds() - started,
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    requestJson = "",
                    responseJson = if (RemoteModelFactory.captureContent) text?.take(512) else null,
                    errorMessage = error?.take(200),
                    traceId = null,
                )
            )
        }
    }

    private companion object {
        /** llm_call_log 里摘要调用的 source 标识（与 chat 主链路 / intent-router 区分）。 */
        const val RECORD_SOURCE = "chat-session-compaction"
    }
}
