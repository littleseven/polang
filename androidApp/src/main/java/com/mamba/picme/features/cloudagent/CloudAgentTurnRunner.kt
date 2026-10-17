package com.mamba.picme.features.cloudagent

import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.data.remote.picme.CloudFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 云端回合上屏/收尾信号（Chat 主页与调试页共用）。 */
sealed interface CloudTurnEvent {
    /** 助手答复整条文本（SSE 事件粒度为整条消息，非 token 增量）。 */
    data class AssistantText(val text: String) : CloudTurnEvent

    /** 服务端下发 tool_request，端侧已受理执行。 */
    data class ToolCall(val tool: String) : CloudTurnEvent

    data class Error(val message: String) : CloudTurnEvent

    /** 本轮 SSE 正常收尾（done 帧）。 */
    data object Done : CloudTurnEvent
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
        onEvent: (CloudTurnEvent) -> Unit,
    ): Result<Unit> = api.postMessage(
        appToken,
        sessionId,
        text,
        byokApiKey?.takeIf { key -> key.isNotBlank() },
    ) { frame ->
        when (frame) {
            is CloudFrame.Event -> handleEvent(frame.event, onEvent)
            is CloudFrame.ToolRequest -> {
                onEvent(CloudTurnEvent.ToolCall(frame.tool))
                toolScope.launch {
                    val result = deviceToolExecutor.execute(frame.tool, frame.args)
                    api.postToolResult(
                        appToken,
                        sessionId,
                        frame.requestId,
                        ok = !result.startsWith("error:"),
                        result = result,
                    ).onFailure { e -> Logger.w(TAG, "postToolResult failed: ${e.message}") }
                }
            }
            is CloudFrame.Done -> onEvent(CloudTurnEvent.Done)
            is CloudFrame.Error -> onEvent(CloudTurnEvent.Error(frame.message))
        }
    }

    private fun handleEvent(event: JSONObject, onEvent: (CloudTurnEvent) -> Unit) {
        val payload = event.optJSONObject("payload") ?: return
        when (event.optString("type")) {
            // 助手答复（user 回声跳过——本地已先上屏）
            "message" -> if (event.optString("role") == "assistant") {
                onEvent(CloudTurnEvent.AssistantText(payload.optString("content")))
            }
            else -> Unit // action/observation/finish 不上屏（ToolCall 由 tool_request 帧驱动）
        }
    }

    companion object {
        private const val TAG = "CloudAgent"
    }
}
