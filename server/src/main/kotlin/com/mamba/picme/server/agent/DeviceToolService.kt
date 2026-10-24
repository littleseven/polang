package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
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
 * 工具名/`error:` 前缀/32_000 字符截断的 SSOT = shared commonMain `CloudAgentToolContract`
 *（M3 收口；server 不依赖 :shared，本类持字面量副本——改契约常量须双向同步核对）。
 * @LLMDescription 的投影承诺面与 shared CloudAgentToolCatalog.projectionSchema 逐字对齐（混合架构 M1），改动须双向同步核对。
 */
class DeviceToolService(
    private val sessionId: String,
    private val bridge: AgentBridgeRegistry,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onToolRequest: suspend (ToolRequestFrame) -> Unit,
) {

    @Tool(customName = "get_gallery_summary")
    @LLMDescription("获取用户手机相册摘要（照片/视频总数、人物聚类数、已打标数等）。工具在用户设备上执行，结果以文本摘要回传（投影：仅计数与文本元数据，不含文件路径与媒体清单）。")
    suspend fun getGallerySummary(): String =
        executeOnDevice("get_gallery_summary", buildJsonObject {})

    @Tool(customName = "search_media")
    @LLMDescription("在用户手机相册搜索照片，结果以文本摘要回传（投影：仅数量+命中说明+尾部 opaque 数据句柄「handle: scan/<id>」（供 render_ui 数据绑定，禁止臆造 handleId），不含文件路径与媒体清单）。query 自然语言搜索词（留空=全相册所有照片，统计全库时直接留空即可）；person 人物分组名或称谓（无则空串）；fromMs/toMs 毫秒时间串（query 含时间词时必须换算填入，空串=不限）；hasFace 传 'true'=只要含人脸照片（空串=不限）。【display 判定——先想用户要的是「照片本身」还是「数字」：要照片本身（「给我看看」「我最近拍的照片」「找些…的照片」「浏览相册」等一切浏览/欣赏/挑选语义）必须传 'true'，命中照片会以横滑图集卡直接展示（含最近照片，按时间倒序）；只要数字/图表（统计、计数、为 render_ui/render_html 发卡取数）才传 'false'。禁止向用户声称本工具无法返回或展示照片清单——display='true' 即展示。】")
    suspend fun searchMedia(
        @LLMDescription("自然语言搜索词") query: String,
        @LLMDescription("人物分组名或称谓，无则空串") person: String,
        @LLMDescription("时间起点毫秒串，空串=不限") fromMs: String,
        @LLMDescription("时间终点毫秒串，空串=不限") toMs: String,
        @LLMDescription("'true'=只要含人脸；空串=不限") hasFace: String,
        @LLMDescription("用户要看照片本身='true'（出横滑图集卡）；只要数字/图表='false'。浏览语义必须 'true'") display: String,
    ): String = executeOnDevice(
        "search_media",
        buildJsonObject {
            put("query", query)
            put("person", person)
            put("fromMs", fromMs)
            put("toMs", toMs)
            put("hasFace", hasFace)
            put("display", display)
        },
    )

    @Tool(customName = "refine_media_search")
    @LLMDescription("在上一轮 search_media 结果内细化过滤（如'只要夜景'），结果以文本摘要回传（投影：仅数量+尾部 opaque 数据句柄「handle: scan/<id>」（供 render_ui 数据绑定，禁止臆造 handleId），不含文件路径与媒体清单）。constraint 细化条件；fromMs/toMs 毫秒串（空串=不限）。上一轮无搜索基数时设备会返回错误，此时改用 search_media。")
    suspend fun refineMediaSearch(
        @LLMDescription("细化条件") constraint: String,
        @LLMDescription("时间起点毫秒串，空串=不限") fromMs: String,
        @LLMDescription("时间终点毫秒串，空串=不限") toMs: String,
    ): String = executeOnDevice(
        "refine_media_search",
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
            deferred.complete("error: device_offline")
            return "error: device_offline"
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // 超时也须补完 deferred 摘除 pending 条目（迟到上行将 409），防泄漏
            deferred.complete("error: device_tool_timeout")
            "error: device_tool_timeout"
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
