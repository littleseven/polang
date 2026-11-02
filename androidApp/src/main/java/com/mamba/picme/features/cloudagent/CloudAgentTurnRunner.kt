package com.mamba.picme.features.cloudagent

import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.data.remote.picme.CloudFrame
import com.mamba.picme.features.chat.openui.OpenUiProgramSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

    /** render_ui 云端发卡（A2UI 骨架+绑定槽位；数据端上补全）。 */
    data class A2uiCard(
        val surfaceId: String,
        val messagesJson: String,
        val fallbackSummary: String,
    ) : CloudTurnEvent

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
        genUiFormat: String = "openui",
        onEvent: (CloudTurnEvent) -> Unit,
    ): Result<Unit> = api.postMessage(
        appToken,
        sessionId,
        text,
        byokApiKey?.takeIf { key -> key.isNotBlank() },
        genUiFormat,
    ) { frame ->
        when (frame) {
            is CloudFrame.Event -> handleEvent(frame.event, onEvent)
            is CloudFrame.OpenUiDelta -> onEvent(CloudTurnEvent.OpenUiCardDelta(frame.program))
            is CloudFrame.ToolRequest -> {
                onEvent(CloudTurnEvent.ToolCall(frame.tool))
                toolScope.launch {
                    val result = deviceToolExecutor.execute(frame.tool, frame.args)
                    api.postToolResult(
                        appToken,
                        sessionId,
                        frame.requestId,
                        ok = !CloudAgentToolContract.isError(result),
                        result = result,
                    ).onFailure { e -> Logger.w(TAG, "postToolResult failed: ${e.message}") }
                }
            }
            is CloudFrame.Done -> onEvent(
                CloudTurnEvent.Done(
                    promptTokens = frame.promptTokens,
                    completionTokens = frame.completionTokens,
                    elapsedMs = frame.elapsedMs,
                ),
            )
            is CloudFrame.Error -> onEvent(CloudTurnEvent.Error(frame.message))
        }
    }

    private fun handleEvent(event: JSONObject, onEvent: (CloudTurnEvent) -> Unit) {
        val payload = event.optJSONObject("payload") ?: return
        when (event.optString("type")) {
            // 助手答复（user 回声跳过——本地已先上屏）；
            // turn-native（openui）：assistant content = OpenUI 程序 → OpenUiCard 事件
            // （spec§3.7）；非程序形态（兜底包装理论上已被 server 兜住）按文本降级。
            // 门判纯按内容嗅探、不以 genUiFormat 为前提（2026-11-02 跨线污染修复）：
            // A2UI 线无 ProgramGuard/兜底包装，LLM 可能模仿会话历史里的程序格式把
            // OpenUI 程序当纯文本输出——若还按 genUiFormat 放行文本路径，程序原文会
            // 经 AgentMarkdown 直接糊上屏（真机事故：调试页白气泡显示整颗程序源码）
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
                if (payload.optString("tool") == CloudAgentToolContract.TOOL_RENDER_UI) {
                    // 终审 m5：render_ui 参数解析容错——坏帧记日志忽略，不拖垮整回合
                    runCatching {
                        val args = JSONObject(payload.optString("argsJson"))
                        CloudTurnEvent.A2uiCard(
                            surfaceId = args.getString("surfaceId"),
                            messagesJson = args.getString("messagesJson"),
                            fallbackSummary = args.optString("fallbackSummary"),
                        )
                    }.onSuccess { card -> onEvent(card) }
                        .onFailure { e -> Logger.w(TAG, "render_ui action frame ignored: ${e.message}") }
                } else if (payload.optString("tool") == CloudAgentToolContract.TOOL_RENDER_UI_OPENUI) {
                    // render_ui_openui 同款容错（openui P2）：坏帧记日志忽略
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
