package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * SSE 下行工具请求帧（spec §3.1）：传输层信号，**不落事件流**。
 * Action 事件已由 EventHandler.onToolCallStarting 落流，二者无需关联（request_id 只活在桥层）。
 */
data class ToolRequestFrame(
    val requestId: String,
    val tool: String,
    val args: JsonObject,
    val timeoutMs: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", "tool_request")
        put("request_id", requestId)
        put("tool", tool)
        put("args", args)
        put("timeout_ms", timeoutMs)
    }
}

/**
 * 端侧设备工具的云端 Koog 适配器（ADR-018 决策 2 工具桥，spec §3.3）：
 * execute = 注册挂起 → 经 [onToolRequest] 向 SSE 写 tool_request 帧 → withTimeout 等上行结果。
 * 写帧失败 = 端已断线，立即 `error: device_offline` 不挂起；超时 = `error: device_tool_timeout`。
 * 两者都以文本返回（Observation 照常落流，pending 不变式不被桥破坏）。
 *
 * 每轮由路由层以当轮 SSE writer 构造新实例（onToolRequest 捕获 writer），不进单例。
 * @Tool 参数全部必填无默认值（Koog 反射派发约束，对齐端侧 ChatToolService 先例）。
 * M2 只读三件套；写类工具等决策 1a 逆操作执行链（M3）。
 *
 * 工具名/LLM 可见描述/`error:` 前缀全部真引用 shared `CloudAgentToolContract`
 *（分层 runtime M1，composite build）；投影承诺面与 shared
 * `CloudAgentToolCatalog.projectionSchema` 同源。
 */
class DeviceToolService(
    private val sessionId: String,
    private val bridge: AgentBridgeRegistry,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onToolRequest: suspend (ToolRequestFrame) -> Unit,
) {

    @Tool(customName = CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY)
    @LLMDescription(CloudAgentToolContract.DESC_GET_GALLERY_SUMMARY)
    suspend fun getGallerySummary(): String =
        executeOnDevice(CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY, buildJsonObject {})

    @Tool(customName = CloudAgentToolContract.TOOL_SEARCH_MEDIA)
    @LLMDescription(CloudAgentToolContract.DESC_SEARCH_MEDIA)
    suspend fun searchMedia(
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_QUERY) query: String,
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_PERSON) person: String,
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_FROM_MS) fromMs: String,
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_TO_MS) toMs: String,
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_HAS_FACE) hasFace: String,
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_DISPLAY) display: String,
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_MEDIA_TYPE) mediaType: String,
    ): String = executeOnDevice(
        CloudAgentToolContract.TOOL_SEARCH_MEDIA,
        buildJsonObject {
            put("query", query)
            put("person", person)
            put("fromMs", fromMs)
            put("toMs", toMs)
            put("hasFace", hasFace)
            put("display", display)
            put("mediaType", mediaType)
        },
    )

    @Tool(customName = CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH)
    @LLMDescription(CloudAgentToolContract.DESC_REFINE_MEDIA_SEARCH)
    suspend fun refineMediaSearch(
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_CONSTRAINT) constraint: String,
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_FROM_MS) fromMs: String,
        @LLMDescription(CloudAgentToolContract.DESC_PARAM_TO_MS) toMs: String,
    ): String = executeOnDevice(
        CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        buildJsonObject {
            put("constraint", constraint)
            put("fromMs", fromMs)
            put("toMs", toMs)
        },
    )

    private suspend fun executeOnDevice(tool: String, args: JsonObject): String {
        val requestId = "tr_" + UUID.randomUUID().toString().replace("-", "").take(8)
        val deferred = bridge.register(sessionId, requestId)
        try {
            onToolRequest(ToolRequestFrame(requestId, tool, args, timeoutMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 写帧失败 = 端已断线：不挂起，立即错误返回
            deferred.complete(ERROR_DEVICE_OFFLINE)
            return ERROR_DEVICE_OFFLINE
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // 超时也须补完 deferred 摘除 pending 条目（迟到上行将 409），防泄漏
            deferred.complete(ERROR_DEVICE_TOOL_TIMEOUT)
            ERROR_DEVICE_TOOL_TIMEOUT
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** 桥层错误信号（契约前缀组合，端侧 `isError` 同口径判定）。 */
        const val ERROR_DEVICE_OFFLINE = CloudAgentToolContract.ERROR_PREFIX + " device_offline"
        const val ERROR_DEVICE_TOOL_TIMEOUT = CloudAgentToolContract.ERROR_PREFIX + " device_tool_timeout"
    }
}
