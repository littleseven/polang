package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolCatalog
import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import com.mamba.picme.agent.core.inference.remote.tool.CloudToolSpec
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
 * 目录驱动的端侧桥工具（分层 runtime M2）：shared `CloudAgentToolCatalog` 的一个
 * LOCAL 条目 = 一件编程式 Koog 工具。LLM 可见 schema（名称/描述/参数集）自
 * [CloudAgentToolCatalog.descriptorFor] 派生，桥层零工具知识——execute 把 LLM 给的
 * args JsonObject 原样入帧下行（参数缺省补齐在端侧 executor，目录 defaultFor 同源）。
 */
class CatalogBridgeTool(
    private val spec: CloudToolSpec,
    private val executor: suspend (tool: String, args: JsonObject) -> String,
) : Tool<JsonObject, String>(
    argsType = typeToken<JsonObject>(),
    resultType = typeToken<String>(),
    descriptor = CloudAgentToolCatalog.descriptorFor(spec.name),
) {
    override suspend fun execute(args: JsonObject): String = executor(spec.name, args)
}

/**
 * 端侧设备工具的云端桥（ADR-018 决策 2 工具桥，spec §3.3）：
 * execute = 注册挂起 → 经 [onToolRequest] 向 SSE 写 tool_request 帧 → withTimeout 等上行结果。
 * 写帧失败 = 端已断线，立即 `error: device_offline` 不挂起；超时 = `error: device_tool_timeout`。
 * 两者都以文本返回（Observation 照常落流，pending 不变式不被桥破坏）。
 *
 * 每轮由路由层以当轮 SSE writer 构造新实例（onToolRequest 捕获 writer），不进单例。
 * M2 只读三件套；写类工具等决策 1a 逆操作执行链（M3）。
 *
 * 分层 runtime M2 起工具集目录驱动：[tools] 按 shared `CloudAgentToolCatalog.localTools()`
 * 循环生成 [CatalogBridgeTool]（原 @Tool 反射方法已退役）；工具名/LLM 可见描述/
 * 参数声明/`error:` 前缀全部真引用 shared `CloudAgentToolContract`/目录（M1 起
 * composite build）；投影承诺面与目录 projectionSchema 同源。
 */
class DeviceToolService(
    private val sessionId: String,
    private val bridge: AgentBridgeRegistry,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onToolRequest: suspend (ToolRequestFrame) -> Unit,
) {

    /** 目录声明的全部 LOCAL 工具（每件 = 一条桥通路，descriptor 自目录派生）。 */
    fun tools(): List<CatalogBridgeTool> =
        CloudAgentToolCatalog.localTools().map { spec ->
            CatalogBridgeTool(spec, ::executeOnDevice)
        }

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
