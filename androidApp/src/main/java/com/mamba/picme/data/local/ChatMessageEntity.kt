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
     * 消息 kind 投影列（派生，权威源 = partsJson）：首个 content/tool 类 part 的线格式
     * 鉴别值——text/image + tool_chart/tool_html/tool_task/tool_image_edit/tool_browser +
     * data_optimize_candidates/data_genui_program（投影规则见
     * `domain/chat/ChatMessageProjection.kt`；reasoning/source/tombstone 永不作投影源）。
     * 消费方：列表气泡判型、跨会话任务卡查询（`type='tool_task'`）、备份导出可读性。
     */
    val type: String,

    /**
     * 消息角色："user" / "agent"（spec §2 升格列；默认值 'agent' 仅为迁移安全网，
     * 所有写路径必须显式赋值）。
     */
    val role: String = "agent",

    /**
     * 消息正文/图片 ref 投影列（派生，权威源 = partsJson；投影规则见
     * `domain/chat/ChatMessageProjection.kt`，列表气泡可读性优先）。
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
     * 附加负载投影列（派生 + 附加，权威源 = partsJson）：parts 派生的结构化负载
     * （html_card / engineer_task / optimize group / imageUri 等，见
     * `domain/chat/ChatMessageProjection.kt`）合并非 parts 派生的随行键
     * （performance / claude_agent_state）。
     */
    val metadata: String? = null,

    /**
     * 消息内容块数组 JSON（ADR-016 parts 模型；[com.mamba.picme.domain.chat.MessagePartsCodec]
     * 线格式）——**权威列（D1a，2026-11-08 parts-first 接缝反转）**：写入侧由
     * `ChatMessageParts.insertPartsMessage` 等接缝直建 parts 落库，type/content/metadata
     * 反向由 [com.mamba.picme.domain.chat.projectChatRow] 投影派生；读侧
     * [com.mamba.picme.data.local.decodeParts] 直读本列，损坏行降级原文 Text part（不丢消息）。
     */
    val partsJson: String
)
