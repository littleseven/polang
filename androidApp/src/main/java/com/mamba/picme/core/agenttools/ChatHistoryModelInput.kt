package com.mamba.picme.core.agenttools

import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole

/**
 * [ModelInputItem] → GET_CHAT_HISTORY 载荷的 (type, content) 对（spec §6 回灌落地）。
 *
 * 契约归属：[AppToolExecutor.collectChatHistory] 的 payload 形状（messages 数组元素
 * `{type, content}`）不变；M1 起内容由 parts 经 `toModelInput` 显式转换生成——
 * 文本消息按消息级 role 标注 `user`/`agent`（role 升格后角色不再自 type 前缀派生；
 * 旧 command/plan_preview 行经迁移归一为 text+agent 后同样呈现为 `agent`）；
 * 卡片类消息呈现为 `tool_call`/`tool_result` 语义对，图片/编辑结果回灌英文中性
 * 占位/说明文本，data part 不进上下文（spec §6 决策）。
 */
fun ModelInputItem.toHistoryPair(): Pair<String, String> = when (this) {
    is ModelInputItem.TextMessage -> when (role) {
        ModelInputRole.USER -> "user"
        ModelInputRole.ASSISTANT -> "agent"
    } to text

    is ModelInputItem.ToolCall ->
        "tool_call" to if (argsSummary.isBlank()) toolName else "$toolName($argsSummary)"

    is ModelInputItem.ToolResult ->
        "tool_result" to buildString {
            append(toolName).append(" → ").append(resultSummary)
            if (isError) append(" (failed)")
        }
}
