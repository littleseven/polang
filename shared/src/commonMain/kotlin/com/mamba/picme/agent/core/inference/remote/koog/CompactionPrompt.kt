package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message

/**
 * M2 摘要请求 prompt 构造（US-2.1/2.5/2.6，纯函数）。
 *
 * 输入 = 上一版摘要（可空，首次压缩）+ 待压缩对话轮，输出 = 发给摘要模型的单 user 消息文本。
 * 经 server 网关（S4）纯文本远程推理（[PRIVACY] 合规——只发文本，媒体一律 `media://` id 占位）。
 *
 * 语义保护（US-2.6）写死在指令里：
 * - media id（`media://...`）原样保留（相册实体指代的锚）；
 * - 日期 / 数字原样保留（时间推理与计数推理的锚）；
 * - 否定语义（"不要时间线"/"别按日期"）必须带否定词保留——丢掉否定 = 摘要反向污染对话。
 */
public object CompactionPrompt {

    /**
     * 构造摘要请求文本。
     *
     * @param previous 上一版 [SessionCompaction]（首次压缩传 null，不含历史段）；
     * @param turnsToCompact 待压缩对话轮（[KoogMessageMemory.selectCompactionCandidates] 选出，最旧在前）。
     */
    public fun build(previous: SessionCompaction?, turnsToCompact: List<Message>): String = buildString {
        appendLine("你是会话记忆压缩器。把下列对话轮压缩成结构化摘要，供后续轮次恢复语境。")
        appendLine()
        appendLine("输出必须是 JSON，恰好四个键：")
        appendLine("{\"intent\": string, \"decisions\": [string], \"todos\": [string], \"entities\": [string]}")
        appendLine()
        appendLine("语义保护规则（必须遵守）：")
        appendLine("- media id（media:// 开头）原样保留，不得改写或省略")
        appendLine("- 日期与数字原样保留（如 2025-07、3 张）")
        appendLine("- 否定语义必须带否定词保留（如「不要时间线」「别按日期」）")
        appendLine("- intent 一句话概括用户目标；decisions 已达成共识；todos 尚未完成；entities 关键实体/时间/媒体")
        appendLine("- 无内容的槽位给空串或空数组，不要编造")
        if (previous != null) {
            val rendered = previous.slots.render()
            if (rendered.isNotBlank()) {
                appendLine()
                appendLine("上一版摘要（在此基础上增量更新，不要丢弃仍有效的信息）：")
                appendLine(rendered)
            }
        }
        appendLine()
        appendLine("待压缩对话轮：")
        turnsToCompact.forEach { message -> appendLine("- ${message.textContent()}") }
    }.trimEnd()
}
