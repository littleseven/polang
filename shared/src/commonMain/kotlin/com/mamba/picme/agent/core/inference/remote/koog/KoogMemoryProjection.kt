package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole

/**
 * 长期记忆（Room partsJson 归档）→ 短期记忆（Koog ChatMemory 热窗口）的重建投影
 *（分层记忆 spec M4：Room = SSOT 长期轨，Koog 记忆 = 可丢弃可重建的派生缓存）。
 *
 * 输入 [ModelInputItem] 序列来自 commonMain `toModelInput` 显式转换（UIMessage→ModelMessage，
 * ADR-016 D1 双层分离），本函数只负责最后一步引擎组装（`ChatModelInput.kt` 的引擎无关约定：
 * Koog Message 组装是 inference/remote 层关注点）。
 *
 * 投影语义：
 * - 文本按消息级 role 落 User/Assistant；
 * - 卡片类 part 已是 Call/Result 语义对（转换器产出即配对、id 一致），映射为
 *   Assistant(Call) + User(Result) 相邻两条，天然满足 store 三不变式的配对要求；
 * - `argsSummary` 为空时落 `"{}"`（Koog/OpenAI 线格式要求 arguments 恒为 JSON 串）；
 * - 失败的工具结果追加 ` (failed)` 文本标记（Koog 1.3.0 Tool.Result 无 isError 字段，
 *   与工程师隧道 `toHistoryPair` 的 `(failed)` 口径一致）；
 * - metaInfo 一律 Empty：重建消息只供上下文，不参与精确时间断言。
 *
 * 产物的预算/老化/条数裁剪由 `ChatMemoryStore.save` 内部的
 * [KoogMessageMemory.assembleForPersistence] 统一承担，本函数不做裁剪。
 */
fun List<ModelInputItem>.toKoogMemoryMessages(): List<Message> = map { item ->
    when (item) {
        is ModelInputItem.TextMessage -> when (item.role) {
            ModelInputRole.USER -> Message.User(item.text, RequestMetaInfo.Empty)
            ModelInputRole.ASSISTANT -> Message.Assistant(item.text, ResponseMetaInfo.Empty)
        }

        is ModelInputItem.ToolCall -> Message.Assistant(
            listOf(MessagePart.Tool.Call(item.toolCallId, item.toolName, item.argsSummary.ifBlank { "{}" })),
            ResponseMetaInfo.Empty,
        )

        is ModelInputItem.ToolResult -> Message.User(
            listOf(
                MessagePart.Tool.Result(
                    item.toolCallId,
                    item.toolName,
                    item.resultSummary + if (item.isError) " (failed)" else "",
                ),
            ),
            RequestMetaInfo.Empty,
        )
    }
}
