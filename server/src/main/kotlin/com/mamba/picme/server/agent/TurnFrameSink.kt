package com.mamba.picme.server.agent

import com.mamba.picme.domain.chat.openui.OpenUiContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 回合 SSE 出站帧汇（AG-UI 迁移 M1，spec《2026-11-28-ag-ui-migration-design》§3）：
 * 路由层 runTurn 的三条信号通道（事件流 / program 前缀快照 / 设备工具桥）+ 终结双信号
 * 的抽象出口。v1（[LegacyKindFrameSink]，自定义 kind 帧）与 v2（[AgUiFrameSink]，
 * AG-UI 事件流）共用同一 runTurn 调用路径，仅 sink 不同——AG-UI 是事件溯源的新出站
 * 投影，AgentRunner / DeviceToolService / agent_event 三表零改动。
 */
internal interface TurnFrameSink {
    /** 回合开始（v2 发 RUN_STARTED；v1 无对应帧，缺省空实现）。 */
    suspend fun onTurnStart() {}

    /** 事件流落流事件（序一致）。 */
    suspend fun onAgentEvent(event: AgentEvent)

    /** 响应 content 流式增量的 program 前缀快照（瞬态旁路，不落事件流）。 */
    suspend fun onProgramDelta(prefix: String)

    /** 设备工具桥下行帧（传输层信号，不落事件流）。 */
    suspend fun onToolRequest(frame: ToolRequestFrame)

    /** 回合成功收尾（v1 done 帧 / v2 RUN_FINISHED；性能数据进帧）。 */
    suspend fun onDone(turnUsage: TurnUsage)

    /** 回合失败收尾（超时/异常）。[code] 为 v2 RUN_ERROR 的错误码，v1 帧无此字段忽略。 */
    suspend fun onError(message: String, code: String? = null)
}

internal fun java.io.Writer.writeSseFrame(obj: JsonObject) {
    write("data: ")
    write(obj.toString())
    write("\n\n")
    flush()
}

internal fun eventJson(e: AgentEvent) = buildJsonObject {
    put("id", e.id)
    put("type", e.type.wire)
    put("source", e.source.wire)
    put("role", e.role.wire)
    e.parentId?.let { put("parent_id", it) }
    put("payload", AgentEventPayloadCodec.encodeToElement(e.payload))
    put("created_at", e.createdAt)
}

/** v1 自定义 kind 帧（event/tool_request/openui_delta/done/error）——逐字节现行行为。 */
internal class LegacyKindFrameSink(
    private val writer: java.io.Writer,
) : TurnFrameSink {
    override suspend fun onAgentEvent(event: AgentEvent) {
        writer.writeSseFrame(buildJsonObject {
            put("kind", "event")
            put("event", eventJson(event))
        })
    }

    override suspend fun onProgramDelta(prefix: String) {
        writer.writeSseFrame(buildJsonObject {
            put("kind", "openui_delta")
            put("program", prefix)
        })
    }

    override suspend fun onToolRequest(frame: ToolRequestFrame) {
        writer.writeSseFrame(frame.toJson())
    }

    override suspend fun onDone(turnUsage: TurnUsage) {
        writer.writeSseFrame(buildJsonObject {
            put("kind", "done")
            put("session_status", AgentSessionStatus.RUNNING.wire)
            put("elapsed_ms", turnUsage.elapsedMs)
            put("usage", buildJsonObject {
                put("prompt_tokens", turnUsage.promptTokens)
                put("completion_tokens", turnUsage.completionTokens)
            })
            put("lang_version", OpenUiContract.LANG_VERSION)
        })
    }

    override suspend fun onError(message: String, code: String?) {
        writer.writeSseFrame(buildJsonObject {
            put("kind", "error")
            put("message", message)
        })
    }
}

/**
 * v2 AG-UI 事件流（spec §4 映射表 SSOT）。约定：
 * - `threadId` = sessionId；`runId` 由路由层每回合生成；
 * - 流式预览快照（[onProgramDelta]，累计前缀语义）差分为 TEXT_MESSAGE_* 真 delta——
 *   复刻 shared `ChatStreamTurnAdapter` 启发式：前缀扩展 = 同轮增量、非扩展 = 轮边界
 *   （OpenUiContentStreamTracker 轮次重置从空重新累计是地面真值）；
 * - 终结 assistant Message 与流式块**去重**：累计 == content → 仅 END 开放块；
 *   不等（ensureValidProgram normalize/兜底改写过）→ END 旧块 + `evt-{id}` 完整三段式；
 * - ACTION/OBSERVATION 以 `act-{eventId}` 为 toolCallId 配对锚（Koog 调用开始即给
 *   全量 args → ARGS 单 chunk，合法）；
 * - 设备工具桥下行 → CUSTOM(device_tool_request)；CONDENSATION/HTML_CARD/FINISH 不下行。
 *
 * 有状态（开放文本块/差分基准），每回合新实例，单 writer 顺序写出与事件序一致。
 */
internal class AgUiFrameSink(
    private val writer: java.io.Writer,
    private val threadId: String,
    private val runId: String,
) : TurnFrameSink {

    private var streamSeq = 0
    private var openTextMessageId: String? = null
    private var prevPrefix = ""
    private var openAccumulated = ""

    override suspend fun onTurnStart() {
        emit(buildJsonObject {
            put("type", "RUN_STARTED")
            put("threadId", threadId)
            put("runId", runId)
        })
    }

    override suspend fun onProgramDelta(prefix: String) {
        val open = openTextMessageId
        if (open != null && prefix.length >= prevPrefix.length && prefix.startsWith(prevPrefix)) {
            val delta = prefix.substring(prevPrefix.length)
            prevPrefix = prefix
            openAccumulated = prefix
            if (delta.isNotEmpty()) emitTextContent(open, delta)
        } else {
            // 非扩展 = 轮边界（tracker 轮次重置）或首块：闭合旧块、开新块
            closeOpenText()
            val id = "stream-${streamSeq++}"
            openTextMessageId = id
            prevPrefix = prefix
            openAccumulated = prefix
            emitTextStart(id, "assistant")
            if (prefix.isNotEmpty()) emitTextContent(id, prefix)
        }
    }

    override suspend fun onAgentEvent(event: AgentEvent) {
        when (val payload = event.payload) {
            is AgentEventPayload.Message -> when (event.role) {
                AgentEventRole.ASSISTANT -> {
                    val open = openTextMessageId
                    if (open != null && openAccumulated == payload.content) {
                        // 流式块即终结消息（ensureValidProgram 未改写）：闭合即完整
                        closeOpenText()
                    } else {
                        closeOpenText()
                        emitTextMessageTriad("evt-${event.id}", "assistant", payload.content)
                    }
                }
                AgentEventRole.USER -> {
                    closeOpenText()
                    emitTextMessageTriad("evt-${event.id}", "user", payload.content)
                }
                else -> Unit // SYSTEM/TOOL 角色的 Message 不出现在实时流
            }
            is AgentEventPayload.Action -> {
                // 工具调用 = 轮边界：闭合开放文本块（与端侧 adapter 同口径）
                closeOpenText()
                val toolCallId = "act-${event.id}"
                emit(buildJsonObject {
                    put("type", "TOOL_CALL_START")
                    put("toolCallId", toolCallId)
                    put("toolCallName", payload.tool)
                })
                emit(buildJsonObject {
                    put("type", "TOOL_CALL_ARGS")
                    put("toolCallId", toolCallId)
                    put("delta", payload.argsJson)
                })
                emit(buildJsonObject {
                    put("type", "TOOL_CALL_END")
                    put("toolCallId", toolCallId)
                })
            }
            is AgentEventPayload.Observation -> {
                emit(buildJsonObject {
                    put("type", "TOOL_CALL_RESULT")
                    put("messageId", "act-${payload.actionEventId}-result")
                    put("toolCallId", "act-${payload.actionEventId}")
                    put("content", payload.content)
                    put("role", "tool")
                })
            }
            // CONDENSATION（内部压缩）/ HtmlCard（已退役）/ Finish（由 onDone 统一表达）不下行
            else -> Unit
        }
    }

    override suspend fun onToolRequest(frame: ToolRequestFrame) {
        emit(buildJsonObject {
            put("type", "CUSTOM")
            put("name", "device_tool_request")
            put("value", buildJsonObject {
                put("request_id", frame.requestId)
                put("tool", frame.tool)
                put("args", frame.args)
                put("timeout_ms", frame.timeoutMs)
            })
        })
    }

    override suspend fun onDone(turnUsage: TurnUsage) {
        closeOpenText()
        emit(buildJsonObject {
            put("type", "RUN_FINISHED")
            put("threadId", threadId)
            put("runId", runId)
            put("metadata", buildJsonObject {
                put("session_status", AgentSessionStatus.RUNNING.wire)
                put("elapsed_ms", turnUsage.elapsedMs)
                put("prompt_tokens", turnUsage.promptTokens)
                put("completion_tokens", turnUsage.completionTokens)
                put("openui_lang_version", OpenUiContract.LANG_VERSION)
            })
        })
    }

    override suspend fun onError(message: String, code: String?) {
        closeOpenText()
        emit(buildJsonObject {
            put("type", "RUN_ERROR")
            put("message", message)
            code?.let { put("code", it) }
        })
    }

    private fun emitTextStart(messageId: String, role: String) {
        emit(buildJsonObject {
            put("type", "TEXT_MESSAGE_START")
            put("messageId", messageId)
            put("role", role)
        })
    }

    private fun emitTextContent(messageId: String, delta: String) {
        emit(buildJsonObject {
            put("type", "TEXT_MESSAGE_CONTENT")
            put("messageId", messageId)
            put("delta", delta)
        })
    }

    private fun emitTextMessageTriad(messageId: String, role: String, content: String) {
        emitTextStart(messageId, role)
        if (content.isNotEmpty()) emitTextContent(messageId, content)
        emit(buildJsonObject {
            put("type", "TEXT_MESSAGE_END")
            put("messageId", messageId)
        })
    }

    private fun closeOpenText() {
        openTextMessageId?.let { open ->
            emit(buildJsonObject {
                put("type", "TEXT_MESSAGE_END")
                put("messageId", open)
            })
            openTextMessageId = null
        }
        prevPrefix = ""
        openAccumulated = ""
    }

    private fun emit(obj: JsonObject) = writer.writeSseFrame(obj)
}
