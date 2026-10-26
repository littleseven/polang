package com.mamba.picme.server.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI SSE chunk 流构造器（openui P2 起策略层 LLM 节点全走 requestLLMStreaming）。
 *
 * 把一次性 chat.completion 响应转成 SSE chunk 流：文本分两片、工具 args 按 [ARGS_CHUNK]
 * 字符切片逐帧下发，真实演练 Koog ToolCallDelta 逐帧累积 → OpenUiToolCallStreamTracker 链路。
 *
 * ⚠️ MockEngine 不支持 SSECapability——流式请求在客户端侧即抛（不消耗响应队列），
 * 走策略层一次性回退；故 SSE 链路测试必须用真 CIO 服务器（见 AgentRunnerTest
 * `openui tool call streams program prefixes via delta callback`）。
 */
object MockLlmSse {

    private const val ARGS_CHUNK = 24

    private val json = Json { ignoreUnknownKeys = true }

    internal fun toSseBody(oneShot: String): String {
        val root = json.parseToJsonElement(oneShot).jsonObject
        val choice = root["choices"]!!.jsonArray[0].jsonObject
        val message = choice["message"]!!.jsonObject
        val finishReason = choice["finish_reason"]!!.jsonPrimitive.content
        val sb = StringBuilder()

        fun emitChunk(delta: String, finish: String? = null) {
            val finishPart = if (finish == null) "null" else "\"$finish\""
            sb.append("data: {\"id\":\"chatcmpl-s\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,")
                .append("\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":")
                .append(delta)
                .append(",\"finish_reason\":")
                .append(finishPart)
                .append("}]}\n\n")
        }

        message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text ->
            val mid = text.length / 2
            emitChunk("{\"role\":\"assistant\",\"content\":${json.encodeToString(text.substring(0, mid))}}")
            emitChunk("{\"content\":${json.encodeToString(text.substring(mid))}}")
        }

        message["tool_calls"]?.jsonArray?.forEachIndexed { index, callEl ->
            val call = callEl.jsonObject
            val callId = call["id"]!!.jsonPrimitive.content
            val fn = call["function"]!!.jsonObject
            val name = fn["name"]!!.jsonPrimitive.content
            val args = fn["arguments"]!!.jsonPrimitive.content
            // 模拟真实提供商形态（2026-10-26 真机日志实证：DeepSeek 仅首 chunk 带 id/name，
            // 后续 chunk 只有 index + arguments 增量）——回归 OpenUiToolCallStreamTracker
            // 对 id 缺失帧的 index 别名归组；旧版 mock 每帧全量带 id/name 掩盖了该 bug
            var pos = 0
            var first = true
            while (first || pos < args.length) {
                val slice = if (first) "" else args.substring(pos, minOf(pos + ARGS_CHUNK, args.length))
                if (!first) pos += ARGS_CHUNK
                first = false
                val toolCall = if (slice.isEmpty() && pos == 0) {
                    "{\"index\":$index,\"id\":${json.encodeToString(callId)}," +
                        "\"type\":\"function\",\"function\":{\"name\":${json.encodeToString(name)}," +
                        "\"arguments\":\"\"}}"
                } else {
                    "{\"index\":$index,\"function\":{\"arguments\":${json.encodeToString(slice)}}}"
                }
                emitChunk("{\"tool_calls\":[$toolCall]}")
            }
        }

        emitChunk("{}", finishReason)
        sb.append("data: [DONE]\n\n")
        return sb.toString()
    }
}
