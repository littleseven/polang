package com.mamba.picme.core.agenttools

import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole

/**
 * [ModelInputItem] → GET_CHAT_HISTORY 载荷的 (type, content) 对（spec §6 回灌落地）。
 *
 * 契约归属：[AppToolExecutor.collectChatHistory] 的 payload 形状（messages 数组元素
 * `{type, content}`）不变；M1 起内容由 parts 经 `toModelInput` 显式转换生成——
 * 文本消息保持 `user_text`/`agent_text` 原样（与旧直拼输出等价），卡片类消息呈现为
 * `tool_call`/`tool_result` 语义对，data part / 媒体块不进上下文（spec 决策）。
 */
fun ModelInputItem.toHistoryPair(): Pair<String, String> = when (this) {
    is ModelInputItem.TextMessage -> when (role) {
        ModelInputRole.USER -> "user_text"
        ModelInputRole.ASSISTANT -> "agent_text"
    } to text

    is ModelInputItem.ToolCall ->
        "tool_call" to if (argsSummary.isBlank()) toolName else "$toolName($argsSummary)"

    is ModelInputItem.ToolResult ->
        "tool_result" to buildString {
            append(toolName).append(" → ").append(resultSummary)
            if (isError) append("（失败）")
        }
}
