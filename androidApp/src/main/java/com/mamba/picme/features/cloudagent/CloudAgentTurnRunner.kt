package com.mamba.picme.features.cloudagent

import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import com.mamba.picme.core.agenttools.CloudAgentScriptTools
import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.data.remote.picme.CloudFrame
import com.mamba.picme.domain.chat.openui.OpenUiProgramSummary
import com.mamba.picme.domain.chat.streaming.AgUiFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.json.JSONObject

/** 云端回合上屏/收尾信号（Chat 主页与调试页共用）。 */
sealed interface CloudTurnEvent {
    /** 助手答复整条文本（SSE 事件粒度为整条消息，非 token 增量）。 */
    data class AssistantText(val text: String) : CloudTurnEvent

    /** 服务端下发 tool_request，端侧已受理执行。 */
    data class ToolCall(val tool: String) : CloudTurnEvent

    /** 一步开始（action 事件；stepId = 服务端事件 id）。 */
    data class StepStarted(val stepId: Long, val tool: String) : CloudTurnEvent

    /** 一步结束（observation 事件；stepId = payload.actionEventId；ok = content 非 "error" 前缀）。 */
    data class StepFinished(val stepId: Long, val ok: Boolean) : CloudTurnEvent

    /** 云端 render_html 产物（H5 卡）：完整自包含 HTML + 一句话摘要。 */
    data class HtmlCard(val html: String, val summary: String) : CloudTurnEvent

    data class Error(val message: String) : CloudTurnEvent

    /** render_ui_openui 云端发卡（OpenUI Lang 1.0 程序；数据端上 local_handle 补全）。 */
    data class OpenUiCard(
        val program: String,
        val fallbackSummary: String,
    ) : CloudTurnEvent

    /** render_ui_openui 流式预览（openui P2）：已到达的 program 前缀，正式卡帧前渐进上屏。 */
    data class OpenUiCardDelta(val program: String) : CloudTurnEvent

    /** 本轮 SSE 正常收尾（done 帧；携带回合性能数据，旧服务端为 null）。 */
    data class Done(
        val promptTokens: Long? = null,
        val completionTokens: Long? = null,
        val elapsedMs: Long? = null,
        /** OpenUI 语言协议版本（版本协商）：落 OpenUiCard part，旧服务端为 null。 */
        val langVersion: Int? = null,
    ) : CloudTurnEvent
}

/**
 * 云端 Agent 单回合执行器（spec §5 状态机的共享内核，M2.5 自 CloudAgentChatViewModel 提取）：
 * postMessage SSE 收帧 → Event 解析上屏 / ToolRequest 端执行 + postToolResult 上行 / Done / Error。
 *
 * 工具执行 launch 到 [toolScope]（不阻塞收帧循环）；回合取消（停止按钮）穿透 postMessage
 * 断流，已受理的工具上行不随回合取消（对齐调试页既有语义）。
 * 纯 JVM 可测：[CloudAgentApi] 是接口，单测 fake 注入。
 */
class CloudAgentTurnRunner(
    private val api: CloudAgentApi,
    private val deviceToolExecutor: DeviceToolExecutor,
    private val toolScope: CoroutineScope,
) {

    suspend fun runTurn(
        appToken: String,
        sessionId: String,
        text: String,
        byokApiKey: String?,
        /** App 界面语言（shared `ReplyLanguage.name`；null 走服务端简中缺省）。 */
        replyLanguage: String? = null,
        /**
         * AG-UI 回滚闸（spec《AG-UI 迁移》M2，DataStore `cloud_ag_ui_enabled` 默认 true）：
         * true = 优先 v2 端点，路由缺失（老服务端 404/406 无 error 体）自动回落 v1 同会话重发
         * （v2 路由未命中不产生副作用，重发安全）；false = 强制 v1 kind 帧链路。
         */
        agUiEnabled: Boolean = true,
        onEvent: (CloudTurnEvent) -> Unit,
    ): Result<Unit> {
        val key = byokApiKey?.takeIf { k -> k.isNotBlank() }
        if (agUiEnabled && api.agUiEndpointSupported) {
            val translator = AgUiCloudTurnTranslator()
            val v2 = api.postMessageV2(appToken, sessionId, text, key, replyLanguage) { frame ->
                translator.onFrame(frame, onEvent) { requestId, tool, args ->
                    handleDeviceToolRequest(appToken, sessionId, requestId, tool, args, onEvent)
                }
            }
            if (v2.isSuccess || !isAgUiRouteMissing(v2.exceptionOrNull())) return v2
            api.markAgUiEndpointUnsupported()
            Logger.w(TAG, "AG-UI v2 endpoint missing, fall back to v1 (session=${sessionId.take(8)})")
        }
        return api.postMessage(appToken, sessionId, text, key, replyLanguage) { frame ->
            when (frame) {
                is CloudFrame.Event -> handleEvent(frame.event, onEvent)
                is CloudFrame.OpenUiDelta -> onEvent(CloudTurnEvent.OpenUiCardDelta(frame.program))
                is CloudFrame.ToolRequest -> {
                    onEvent(CloudTurnEvent.ToolCall(frame.tool))
                    handleDeviceToolRequest(appToken, sessionId, frame.requestId, frame.tool, frame.args, onEvent)
                }
                is CloudFrame.Done -> onEvent(
                    CloudTurnEvent.Done(
                        promptTokens = frame.promptTokens,
                        completionTokens = frame.completionTokens,
                        elapsedMs = frame.elapsedMs,
                        langVersion = frame.langVersion,
                    ),
                )
                is CloudFrame.Error -> onEvent(CloudTurnEvent.Error(frame.message))
            }
        }
    }

    /**
     * v2 路由缺失判定：HTTP 404/406 且响应体不含服务端业务 error 体——
     * 新服务端的业务 404（session_not_found 等）带 `{"error":...}` JSON，不属路由缺失，
     * 照常上抛（ChatViewModel 会话失效重建重试语义不变）。
     */
    private fun isAgUiRouteMissing(e: Throwable?): Boolean {
        val msg = e?.message ?: return false
        if (!msg.startsWith("HTTP 404") && !msg.startsWith("HTTP 406")) return false
        return !msg.contains("\"error\"")
    }

    /** 设备工具桥：端执行 + postToolResult 上行（v1 tool_request 帧 / v2 CUSTOM 帧共用）。 */
    private fun handleDeviceToolRequest(
        appToken: String,
        sessionId: String,
        requestId: String,
        tool: String,
        args: JSONObject,
        onEvent: (CloudTurnEvent) -> Unit,
    ) {
        toolScope.launch {
            val result = deviceToolExecutor.execute(tool, args)
            // 产物拦截（注册制）：run_gallery_script 的图卡信封 → 本地 HTML 卡逐张
            // 直接上屏（复用 ChatViewModel 的 html_card 落库管线），上行只带 summary+stats——
            // HTML 不出端、不进 LLM 上下文（对齐 chat 链路产物拦截的省 token 语义）。
            val artifact = CloudAgentScriptTools.parseHtmlArtifact(result)
            if (artifact != null) {
                artifact.cards.forEach { (html, summary) ->
                    onEvent(CloudTurnEvent.HtmlCard(html, summary))
                }
            }
            api.postToolResult(
                appToken,
                sessionId,
                requestId,
                ok = !CloudAgentToolContract.isError(result),
                result = artifact?.let { parsed ->
                    JSONObject()
                        .put("html_card", "emitted")
                        .put("summary", parsed.cards.joinToString("；") { it.second })
                        .apply {
                            parsed.stats?.let { s ->
                                put("stats", runCatching { JSONObject(s) }.getOrElse { s })
                            }
                        }
                        .toString()
                } ?: result,
            ).onFailure { e -> Logger.w(TAG, "postToolResult failed: ${e.message}") }
        }
    }

    private fun handleEvent(event: JSONObject, onEvent: (CloudTurnEvent) -> Unit) {
        val payload = event.optJSONObject("payload") ?: return
        when (event.optString("type")) {
            // 助手答复（user 回声跳过——本地已先上屏）；
            // turn-native（openui）：assistant content = OpenUI 程序 → OpenUiCard 事件
            // （spec§3.7）；非程序形态（兜底包装理论上已被 server 兜住）按文本降级。
            // 门判纯按内容嗅探（2026-11-02 跨线污染修复；A2UI 线 2026-11-05 退役后仍保留）：
            // LLM 可能模仿会话历史里的程序格式把 OpenUI 程序当纯文本输出——若放行文本路径，
            // 程序原文会经 AgentMarkdown 直接糊上屏（真机事故：调试页白气泡显示整颗程序源码）
            "message" -> if (event.optString("role") == "assistant") {
                val content = payload.optString("content")
                if (OpenUiProgramSummary.looksLikeProgram(content)) {
                    onEvent(
                        CloudTurnEvent.OpenUiCard(
                            program = content,
                            fallbackSummary = OpenUiProgramSummary.extractFallbackSummary(content),
                        ),
                    )
                } else {
                    onEvent(CloudTurnEvent.AssistantText(content))
                }
            }
            // render_html 产物卡（html 为空不上屏空卡）
            "html_card" -> {
                val html = payload.optString("html")
                if (html.isNotBlank()) {
                    onEvent(CloudTurnEvent.HtmlCard(html, payload.optString("summary")))
                }
            }
            "action" -> {
                // 步骤时间线：所有 action 帧先发 StepStarted（含发卡工具），再做卡解析
                onEvent(CloudTurnEvent.StepStarted(event.optLong("id"), payload.optString("tool")))
                if (payload.optString("tool") == CloudAgentToolContract.TOOL_RENDER_UI_OPENUI) {
                    // render_ui_openui 参数解析容错：坏帧记日志忽略，不拖垮整回合
                    runCatching {
                        val args = JSONObject(payload.optString("argsJson"))
                        CloudTurnEvent.OpenUiCard(
                            program = args.getString("program"),
                            fallbackSummary = args.optString("fallbackSummary"),
                        )
                    }.onSuccess { card -> onEvent(card) }
                        .onFailure { e -> Logger.w(TAG, "render_ui_openui action frame ignored: ${e.message}") }
                }
                // 其它工具的 action 帧不出卡（步骤事件已在上方发出；ToolCall 由 tool_request 帧驱动）
            }
            // observation → 步骤收尾；actionEventId=0 为渲染门控例行产生的孤儿帧，跳过
            "observation" -> {
                val actionEventId = payload.optLong("actionEventId")
                if (actionEventId != 0L) {
                    onEvent(
                        CloudTurnEvent.StepFinished(
                            actionEventId,
                            !payload.optString("content").startsWith("error"),
                        ),
                    )
                }
            }
            else -> Unit // finish/condensation 不上屏（ToolCall 由 tool_request 帧驱动）
        }
    }

    companion object {
        private const val TAG = "CloudAgent"
    }
}

/**
 * v2（AG-UI）帧 → [CloudTurnEvent] 翻译器（spec《AG-UI 迁移》§4 映射表的端侧倒影）。
 * 单回合状态（文本块聚合 / 工具参数累计），每回合由 [CloudAgentTurnRunner.runTurn] 新建。
 *
 * 与 v1 [CloudAgentTurnRunner.handleEvent] 对齐的口径：
 * - TEXT_MESSAGE_* 聚合成整条文本后走同一 `looksLikeProgram` 嗅探（程序 → OpenUiCard，
 *   否则 AssistantText）；多块流式增量经 `looksLikeProgramPrefix` 门判出 OpenUiCardDelta
 *   （单 chunk 块 = 非流式整条消息，不出预览帧，对齐 v1 openui_delta 仅程序流产出的语义）；
 *   role=user 块整段跳过（用户消息本地已先上屏）；
 * - **stream/evt 双块去重**（服务端 TurnFrameSink 契约）：流式预览块（`stream-*`）END 后事件
 *   暂存 [pendingFinal]，终结块（`evt-*` assistant）START 到达则丢弃候选（改写场景 evt 携带
 *   权威全文），任何其它帧到达先 flush 候选（等值场景无 evt 续帧）——两形态恒单卡；
 * - TOOL_CALL_START → StepStarted（toolCallId `act-{eventId}` 解析回服务端事件 id）；
 *   ARGS 累计到 END 全量解析（render_ui_openui → OpenUiCard，坏帧记日志容错同 v1）；
 * - TOOL_CALL_RESULT → StepFinished（`act-0` 孤儿帧跳过，同 v1 actionEventId=0 口径）；
 * - CUSTOM(device_tool_request) → 设备工具桥（ToolCall 上屏由调用方回调触发）；
 * - RUN_FINISHED metadata → Done；RUN_ERROR → Error；RUN_STARTED 仅回合边界信号。
 */
private class AgUiCloudTurnTranslator {

    /** 进行中文本块：messageId → (role, 累计文本, content 帧计数)。 */
    private data class TextBlock(val role: String, val buf: StringBuilder, var chunks: Int)

    private val textBlocks = mutableMapOf<String, TextBlock>()
    private val toolNames = mutableMapOf<String, String>()
    private val toolArgs = mutableMapOf<String, StringBuilder>()

    /**
     * 终结候选（服务端 stream/evt 去重契约，TurnFrameSink §双形态）：
     * 流式预览块（stream-*）END 后暂存事件不上屏——随后 evt-*（assistant）START 到达
     * = ensureValidProgram 改写场景，evt 携带权威全文取代候选（防双卡）；
     * 任何其它帧到达 = 等值场景（无 evt 续帧），候选 flush 转正上屏。
     */
    private var pendingFinal: CloudTurnEvent? = null

    fun onFrame(
        frame: AgUiFrame,
        onEvent: (CloudTurnEvent) -> Unit,
        onToolRequest: (requestId: String, tool: String, args: JSONObject) -> Unit,
    ) {
        if (frame is AgUiFrame.TextMessageStart && frame.isFinalAssistantBlock) {
            // 改写场景：evt 块到达，丢弃 stream 候选（权威全文在 evt 块内）
            pendingFinal = null
        } else {
            // 等值场景/工具交错：候选转正（evt assistant START 之外的一切帧都先 flush）
            flushPendingFinal(onEvent)
        }
        when (frame) {
            is AgUiFrame.RunStarted -> Unit // 回合边界信号（v1 无对应物），暂不上屏
            is AgUiFrame.TextMessageStart -> textBlocks[frame.messageId] =
                TextBlock(frame.role, StringBuilder(), 0)
            is AgUiFrame.TextMessageContent -> textBlocks[frame.messageId]?.let { block ->
                block.buf.append(frame.delta)
                block.chunks++
                // 流式程序预览：第二 chunk 起（单 chunk = 非流式整条消息）且前缀像程序
                if (block.role == "assistant" && block.chunks >= 2 &&
                    OpenUiProgramSummary.looksLikeProgramPrefix(block.buf.toString())
                ) {
                    onEvent(CloudTurnEvent.OpenUiCardDelta(block.buf.toString()))
                }
            }
            is AgUiFrame.TextMessageEnd -> textBlocks.remove(frame.messageId)?.let { block ->
                if (block.role != "assistant") return@let
                val content = block.buf.toString()
                val event = if (OpenUiProgramSummary.looksLikeProgram(content)) {
                    CloudTurnEvent.OpenUiCard(
                        program = content,
                        fallbackSummary = OpenUiProgramSummary.extractFallbackSummary(content),
                    )
                } else {
                    CloudTurnEvent.AssistantText(content)
                }
                // stream-* = 流式预览块：暂存候选等 evt 续帧裁决；evt-*（及旧服务端整条块）直上屏
                if (frame.messageId.startsWith(STREAM_MESSAGE_PREFIX)) {
                    pendingFinal = event
                } else {
                    onEvent(event)
                }
            }
            is AgUiFrame.ToolCallStart -> {
                toolNames[frame.toolCallId] = frame.toolCallName
                toolArgs[frame.toolCallId] = StringBuilder()
                parseActEventId(frame.toolCallId)?.let { stepId ->
                    onEvent(CloudTurnEvent.StepStarted(stepId, frame.toolCallName))
                }
            }
            is AgUiFrame.ToolCallArgs -> toolArgs[frame.toolCallId]?.append(frame.delta)
            is AgUiFrame.ToolCallEnd -> {
                val args = toolArgs.remove(frame.toolCallId)?.toString() ?: ""
                if (toolNames.remove(frame.toolCallId) == CloudAgentToolContract.TOOL_RENDER_UI_OPENUI) {
                    // 与 v1 action 帧同口径：坏 args 记日志忽略，不拖垮整回合
                    runCatching {
                        val argsJson = JSONObject(args)
                        CloudTurnEvent.OpenUiCard(
                            program = argsJson.getString("program"),
                            fallbackSummary = argsJson.optString("fallbackSummary"),
                        )
                    }.onSuccess { card -> onEvent(card) }
                        .onFailure { e ->
                            Logger.w(TAG, "render_ui_openui v2 args ignored: ${e.message}")
                        }
                }
            }
            is AgUiFrame.ToolCallResult -> {
                val stepId = parseActEventId(frame.toolCallId)
                // act-0 = 渲染门控例行孤儿帧（v1 actionEventId=0 同口径跳过）
                if (stepId != null && stepId != 0L) {
                    onEvent(CloudTurnEvent.StepFinished(stepId, !frame.content.startsWith("error")))
                }
            }
            is AgUiFrame.Custom -> if (frame.name == CUSTOM_DEVICE_TOOL_REQUEST) {
                val value = frame.value
                val requestId = value?.get("request_id")?.jsonPrimitive?.content.orEmpty()
                val tool = value?.get("tool")?.jsonPrimitive?.content.orEmpty()
                if (requestId.isNotBlank() && tool.isNotBlank()) {
                    onEvent(CloudTurnEvent.ToolCall(tool))
                    val args = value?.get("args")?.toString()?.let { runCatching { JSONObject(it) }.getOrNull() }
                        ?: JSONObject()
                    onToolRequest(requestId, tool, args)
                }
            }
            is AgUiFrame.RunFinished -> onEvent(
                CloudTurnEvent.Done(
                    promptTokens = frame.metadata?.get("prompt_tokens")?.jsonPrimitive?.longOrNull,
                    completionTokens = frame.metadata?.get("completion_tokens")?.jsonPrimitive?.longOrNull,
                    elapsedMs = frame.metadata?.get("elapsed_ms")?.jsonPrimitive?.longOrNull,
                    langVersion = frame.metadata?.get("openui_lang_version")?.jsonPrimitive?.intOrNull,
                ),
            )
            is AgUiFrame.RunError -> onEvent(CloudTurnEvent.Error(frame.message))
        }
    }

    /** evt-*（assistant）START = 服务端终结块（权威全文）到达信号。 */
    private val AgUiFrame.TextMessageStart.isFinalAssistantBlock: Boolean
        get() = messageId.startsWith(FINAL_MESSAGE_PREFIX) && role == "assistant"

    /** 暂存的终结候选转正上屏（等值场景/回合收尾/工具交错前调用）。 */
    private fun flushPendingFinal(onEvent: (CloudTurnEvent) -> Unit) {
        pendingFinal?.let { onEvent(it) }
        pendingFinal = null
    }

    private fun parseActEventId(toolCallId: String): Long? =
        toolCallId.removePrefix("act-").toLongOrNull()

    companion object {
        private const val TAG = "CloudAgent"
        private const val CUSTOM_DEVICE_TOOL_REQUEST = "device_tool_request"

        /** 服务端流式预览块 messageId 前缀（TurnFrameSink `stream-{N}`）。 */
        private const val STREAM_MESSAGE_PREFIX = "stream-"

        /** 服务端终结块 messageId 前缀（TurnFrameSink `evt-{eventId}`）。 */
        private const val FINAL_MESSAGE_PREFIX = "evt-"
    }
}
