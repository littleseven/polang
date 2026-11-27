package com.mamba.picme.domain.chat.streaming

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AG-UI 线协议帧（spec《2026-11-28-ag-ui-migration-design》§4/§6）：
 * picme-server `/agent/v2/sessions/{id}/messages` SSE 下行事件的端侧模型。
 * 只收 M1 产出的事件族；RUN_STARTED 目前仅作回合边界信号（threadId/runId 透传审计）。
 */
sealed interface AgUiFrame {

    data class RunStarted(val threadId: String, val runId: String) : AgUiFrame

    /** 文本消息块开始。role=user 的帧端侧跳过（用户消息本地已落库）。 */
    data class TextMessageStart(val messageId: String, val role: String) : AgUiFrame

    data class TextMessageContent(val messageId: String, val delta: String) : AgUiFrame

    data class TextMessageEnd(val messageId: String) : AgUiFrame

    data class ToolCallStart(val toolCallId: String, val toolCallName: String) : AgUiFrame

    /** 参数增量（服务端 Koog 调用开始即给全量 args → 恒单 chunk）。 */
    data class ToolCallArgs(val toolCallId: String, val delta: String) : AgUiFrame

    data class ToolCallEnd(val toolCallId: String) : AgUiFrame

    /** 工具结果（云端工具 observation；卡片产物由端侧执行通道自负，不进 TurnStreamEvent）。 */
    data class ToolCallResult(val messageId: String, val toolCallId: String, val content: String) : AgUiFrame

    /** 私有语义扩展帧：M1 用于 device_tool_request（端侧工具桥下行）。 */
    data class Custom(val name: String, val value: JsonObject?) : AgUiFrame

    /** 回合成功收尾；metadata 携 done 帧全量数据（elapsed_ms/prompt_tokens/completion_tokens/openui_lang_version/session_status）。 */
    data class RunFinished(val threadId: String, val runId: String, val metadata: JsonObject?) : AgUiFrame

    data class RunError(val message: String, val code: String?) : AgUiFrame
}

/**
 * SSE block → [AgUiFrame] 解析。纯逻辑：未知 type / 非法 JSON / 缺关键字段一律 null
 * 容错跳过（`CloudSseParser` 先例——服务端后续新增事件族时旧端零感知）。
 */
object AgUiSseParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseBlock(block: String): AgUiFrame? {
        val data = block.lines().firstOrNull { it.startsWith("data:") }
            ?.removePrefix("data:")?.trim() ?: return null
        val obj = try {
            json.parseToJsonElement(data).jsonObject
        } catch (_: Throwable) {
            return null
        }
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "RUN_STARTED" -> AgUiFrame.RunStarted(
                threadId = obj["threadId"]?.jsonPrimitive?.contentOrNull ?: return null,
                runId = obj["runId"]?.jsonPrimitive?.contentOrNull ?: return null,
            )
            "TEXT_MESSAGE_START" -> AgUiFrame.TextMessageStart(
                messageId = obj["messageId"]?.jsonPrimitive?.contentOrNull ?: return null,
                role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "assistant",
            )
            "TEXT_MESSAGE_CONTENT" -> AgUiFrame.TextMessageContent(
                messageId = obj["messageId"]?.jsonPrimitive?.contentOrNull ?: return null,
                delta = obj["delta"]?.jsonPrimitive?.contentOrNull ?: return null,
            )
            "TEXT_MESSAGE_END" -> obj["messageId"]?.jsonPrimitive?.contentOrNull
                ?.let(AgUiFrame::TextMessageEnd)
            "TOOL_CALL_START" -> AgUiFrame.ToolCallStart(
                toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null,
                toolCallName = obj["toolCallName"]?.jsonPrimitive?.contentOrNull ?: return null,
            )
            "TOOL_CALL_ARGS" -> AgUiFrame.ToolCallArgs(
                toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null,
                delta = obj["delta"]?.jsonPrimitive?.contentOrNull ?: return null,
            )
            "TOOL_CALL_END" -> obj["toolCallId"]?.jsonPrimitive?.contentOrNull
                ?.let(AgUiFrame::ToolCallEnd)
            "TOOL_CALL_RESULT" -> AgUiFrame.ToolCallResult(
                messageId = obj["messageId"]?.jsonPrimitive?.contentOrNull ?: return null,
                toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null,
                content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "CUSTOM" -> AgUiFrame.Custom(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null,
                value = obj["value"] as? JsonObject,
            )
            "RUN_FINISHED" -> AgUiFrame.RunFinished(
                threadId = obj["threadId"]?.jsonPrimitive?.contentOrNull ?: return null,
                runId = obj["runId"]?.jsonPrimitive?.contentOrNull ?: return null,
                metadata = obj["metadata"] as? JsonObject,
            )
            "RUN_ERROR" -> AgUiFrame.RunError(
                message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return null,
                code = obj["code"]?.jsonPrimitive?.contentOrNull,
            )
            else -> null
        }
    }
}
