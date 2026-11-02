package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import kotlinx.coroutines.CancellationException

/**
 * 网页搜索传输层（commonMain 无 HTTP 手段，组合根注入平台实现；测试注入 fake）。
 * 实现：androidApp `WebSearchClient`（OkHttp → picme-server `/v1/web-search`，
 * 服务端持 DeepSeek key 调 Search API 并做配图 URL 提取/瘦身）。
 */
interface WebSearchTransport {
    /**
     * 搜索并返回服务端紧凑 JSON 原文（`{"status":"ok","results":[{title,url,snippet,images,...}]}`）。
     * @throws WebSearchUnavailableException 网关不可达/未配置/上游不可用
     */
    suspend fun search(query: String): String
}

class WebSearchUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 网页搜索能力（主聊天 `web_search` 工具的执行体）。
 *
 * 降级不变式与 BrowserSessionCapability 同构：一切失败映射为结构化文本交 LLM 降级处理，
 * 不抛异常穿透 ReAct 链（CancellationException 例外，原样 rethrow 保护结构化并发）。
 *
 * [PRIVACY]：搜索词为文本（用户提问或其改写），无任何媒体/相册数据出端——搜索请求本身
 * 即聊天远程推理的既有暴露面，不引入新数据类别。
 */
class WebSearchCapability(
    private val transport: WebSearchTransport,
) : BaseCapability() {

    override val name: String = "web_search"
    override val description: String = "网页搜索：获取实时信息、新闻与网页配图 URL"

    override fun supportedCommands(): List<String> = listOf("web_search")

    override suspend fun execute(command: AgentCommand, context: AgentContext, pageContext: PageContext?): Result<AgentAction> {
        if (command !is AgentCommand.WebSearch) {
            return Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.METHOD_NOT_FOUND, "WebSearchCapability 不支持此命令"),
            )
        }
        val reply = try {
            transport.search(command.query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebSearchUnavailableException) {
            "[WEB_SEARCH_UNAVAILABLE] 网页搜索暂不可用：${e.message}。请改用纯文本回答（不要编造实时信息或图片链接）。"
        } catch (e: Exception) {
            "[WEB_SEARCH_FAILED] 网页搜索失败：${e.message ?: "unknown"}。可重试一次或改用纯文本回答。"
        }
        return Result.success(AgentAction.TextReply(commandId = command.commandId, message = reply))
    }
}
