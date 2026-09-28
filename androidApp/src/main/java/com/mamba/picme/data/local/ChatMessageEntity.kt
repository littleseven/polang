package com.mamba.picme.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 数据库实体：聊天消息
 *
 * 对应表：chat_messages
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,

    /**
     * 会话 ID，当前仅支持单会话（default），后续可扩展多会话
     */
    val sessionId: String = "default",

    /**
     * 新分类法 8 值（spec §1）：text, image, tool_chart, tool_html, tool_task,
     * tool_image_edit, data_media_results, data_optimize_candidates。
     * 降级定位：索引冗余 + 迁移源；读面权威是 partsJson。
     */
    val type: String,

    /**
     * 消息角色："user" / "agent"（spec §2 升格列；默认值 'agent' 仅为迁移安全网，
     * 所有写路径必须显式赋值）。
     */
    val role: String = "agent",

    /**
     * 文本内容或图片路径（图片消息存储本地文件路径）
     */
    val content: String,

    /**
     * 消息时间戳
     */
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * 生成该消息的模型标识：local_qwen3.5_2b / remote_deepseek 等
     */
    val modelUsed: String? = null,

    /**
     * 扩展 JSON 字段，用于存储额外元数据
     */
    val metadata: String? = null,

    /**
     * 消息内容块数组 JSON（ADR-016 M1 parts 模型；[com.mamba.picme.domain.chat.MessagePartsCodec] 线格式）。
     *
     * M1 双写过渡：写入侧由 `ChatMessageDao.insertMessageWithParts` 从 (type, content, metadata)
     * 现算填充；读侧优先本列、缺失时回 legacy 列现算（`decodePartsOrLegacy`），旧列保留不删，
     * 回退安全。v24→v25 迁移对存量行全量回填（失败行降级 Text part 原文兜底，不丢消息）。
     */
    val partsJson: String? = null
)
