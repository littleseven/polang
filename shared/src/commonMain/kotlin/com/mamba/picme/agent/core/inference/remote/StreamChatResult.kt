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
)

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
