package com.mamba.picme.agent.core.inference.remote

/**
 * 流式聊天结果（远程链路）
 *
 * 原 `local.llm` 包类型；本地文本 LLM 链路移除后由远程链路（RemoteChatEngine）独占使用，
 * 迁至本包。
 *
 * @property fullResponse 完整的响应文本
 * @property metrics 性能指标
 * @property commands 从响应中解析出的命令列表
 */
data class StreamChatResult(
    val fullResponse: String,
    val metrics: StreamMetrics? = null,
    val commands: List<com.mamba.picme.agent.core.model.command.AgentCommand> = emptyList(),
    /**
     * 意图路由直执回执（非 null 时 [fullResponse]/[commands] 为空）：
     * 直执路径跳过 LLM 总结，用户气泡由平台层据此用本地化文案（五语）渲染——
     * 模型侧 observation（硬编码中文模板）只进记忆与审计，绝不可直达用户（I18N 红线）。
     */
    val directReply: DirectRouteReply? = null
)

/**
 * 意图路由直执回执（spec《意图路由契约与意图路由器》M2 直执路径）。
 *
 * @property kind 直执命令类型（SEARCH=search_media / REFINE=refine_media_search）
 * @property query 用户原文（卡片/气泡上下文）
 * @property totalCount 结果总数（0 = 无结果，平台层据此选「未找到」文案）
 */
data class DirectRouteReply(
    val kind: Kind,
    val query: String,
    val totalCount: Int,
) {
    enum class Kind { SEARCH, REFINE }
}

/**
 * 流式性能指标
 *
 * @property latencyMs 端到端延迟（毫秒）
 * @property promptTokens 输入 Token 数
 * @property completionTokens 输出 Token 数
 */
data class StreamMetrics(
    val latencyMs: Long,
    val promptTokens: Long?,
    val completionTokens: Long?
)
