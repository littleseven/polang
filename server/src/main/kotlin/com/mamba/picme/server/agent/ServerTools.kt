package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.asToolsByClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * 云端 Agent 的服务端工具（ADR-018 决策 2：服务端工具本地执行域）。
 *
 * M1 只有只读的 server_time（验证工具闭环链路）；云端浏览器等接入为后续里程碑。
 * 端侧工具（相册/媒体）走工具桥，不在此注册。
 *
 * render_html（云端 H5 卡）：模型产 HTML+summary，**回给 LLM 的只有 summary 回执**
 * （HTML 不进模型上下文，省 token，对齐主聊天 render_html 语义）；HTML 本体经
 * [onHtmlCard] sink 落 HtmlCard 事件随 SSE 下行端上渲染。超 [MAX_HTML_CHARS] 拒绝
 * （与端侧 HtmlCardSanitizer 128KB 口径对齐），且 sink 不触发。
 *
 * 工具名/`error:` 前缀的 SSOT = shared commonMain `CloudAgentToolContract`
 *（server 不依赖 :shared，本类持字面量副本——改契约常量须双向同步核对）。
 *
 * 决策 1a 可逆性元数据：[REVERSIBLE_TOOLS]——server_time 只读、render_html 只产
 * 下行卡片（无副作用可逆语义管辖），故集合为空。
 */
class ServerToolService(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onHtmlCard: suspend (html: String, summary: String) -> Unit = { _, _ -> },
) {

    @Tool(customName = "server_time")
    @LLMDescription("获取服务器当前时间，返回毫秒时间戳与 ISO-8601 字符串")
    suspend fun serverTime(): String {
        val ms = clock()
        return "server_time: epochMs=$ms iso=${Instant.ofEpochMilli(ms)}"
    }

    @Tool(customName = "render_html")
    @LLMDescription("产出一张移动端自包含单文件 HTML 卡片直接给用户看（数据汇总/报告/可视化等）。本工具只回执你写的 summary，HTML 本体不会回到你的上下文——不要在回复中重复粘贴 HTML。")
    suspend fun renderHtml(
        @LLMDescription("自包含单文件 HTML（样式与脚本全部内联，不依赖外部资源），上限 128000 字符") html: String,
        @LLMDescription("一句话说明卡片内容，将作为回执返回给你") summary: String,
    ): String {
        if (html.length > MAX_HTML_CHARS) return "error: html_too_large"
        onHtmlCard(html, summary)
        return "已渲染 HTML 卡片：$summary"
    }

    companion object {
        /** html 长度上限（字符），与端侧 HtmlCardSanitizer 128KB 口径对齐。 */
        const val MAX_HTML_CHARS = 128_000

        /** 有副作用且声明了逆操作的工具名集合（决策 1a）。M1 无为空集。 */
        val REVERSIBLE_TOOLS: Set<String> = emptySet()

        /** M1 默认 Koog ToolRegistry（reflect 展开，JVM-only API——server 即 JVM）。 */
        fun defaultRegistry(
            clock: () -> Long = { System.currentTimeMillis() },
            onHtmlCard: suspend (html: String, summary: String) -> Unit = { _, _ -> },
        ): ToolRegistry =
            ToolRegistry { tools(ServerToolService(clock, onHtmlCard).asToolsByClass()) }
    }
}

/** render_html 直派参数（pending 重放时 argsJson 解码）。 */
@Serializable
private data class RenderHtmlArgs(val html: String, val summary: String)

private val renderHtmlArgsJson = Json { ignoreUnknownKeys = true }

private suspend fun dispatchRenderHtml(service: ServerToolService, argsJson: String): String {
    val args = renderHtmlArgsJson.decodeFromString(RenderHtmlArgs.serializer(), argsJson)
    return service.renderHtml(args.html, args.summary)
}

/**
 * 服务端工具直派器：pending action 重放（崩溃恢复）时按工具名执行。
 * 与 Koog ToolRegistry 同源注册，避免双份实现漂移。
 */
class ServerToolDispatcher {
    private val handlers = linkedMapOf<String, suspend (String) -> String>()

    fun register(name: String, handler: suspend (String) -> String): ServerToolDispatcher {
        handlers[name] = handler
        return this
    }

    fun contains(name: String): Boolean = handlers.containsKey(name)

    /** @throws IllegalArgumentException 未知工具 */
    suspend fun dispatch(name: String, argsJson: String): String =
        handlers[name]?.invoke(argsJson)
            ?: throw IllegalArgumentException("unknown server tool: $name")

    /**
     * 派生绑定 run 级 [onHtmlCard] 的副本（pending 重放路径：崩溃恢复重放 render_html
     * 时卡片要重新出现在事件流，sink 由当轮调用方供给）；其余 handler 原样共享，
     * 本实例不受影响。
     */
    fun withHtmlCardSink(
        onHtmlCard: suspend (html: String, summary: String) -> Unit,
    ): ServerToolDispatcher {
        val copy = ServerToolDispatcher()
        handlers.forEach { (name, handler) ->
            if (name != "render_html") copy.register(name, handler)
        }
        val service = ServerToolService(onHtmlCard = onHtmlCard)
        copy.register("render_html") { argsJson -> dispatchRenderHtml(service, argsJson) }
        return copy
    }

    companion object {
        /** 与 [ServerToolService.defaultRegistry] 同源：同一 clock、同一实现。 */
        fun default(
            clock: () -> Long = { System.currentTimeMillis() },
            onHtmlCard: suspend (html: String, summary: String) -> Unit = { _, _ -> },
        ): ServerToolDispatcher {
            val service = ServerToolService(clock, onHtmlCard)
            return ServerToolDispatcher()
                .register("server_time") { service.serverTime() }
                .register("render_html") { argsJson -> dispatchRenderHtml(service, argsJson) }
        }
    }
}
