package com.mamba.picme.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 数据库实体：聊天消息
 *
 * 对应表：chat_messages（v28 起 parts 单一权威：ADR-016 持久化接缝反转，
 * 对齐 Vercel「UIMessage JSON 整包落库」——content 列已退役）。
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
     * 投影列（写时自 parts 经 [com.mamba.picme.domain.chat.MessagePartsProjection.roomTypeOf]
     * 派生，构造/更新接缝同点完成）：服务 DAO 查询/索引面（任务中心 type='tool_task'、
     * 回灌窗口 role 子查询）。读面权威是 partsJson，本列永不作为内容源。
     * 新分类法（spec §1）：text, image, tool_chart, tool_html, tool_task,
     * tool_image_edit, data_optimize_candidates 等（data_media_results 已随横滑卡
     * 退役，存量行为历史残留，回放路径整行跳过，见 LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES）。
     */
    val type: String,

    /**
     * 消息角色："user" / "agent"
     */
    val role: String = "agent",

    /**
     * 消息时间戳
     */
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * 生成该消息的模型标识：remote_deepseek / cloud / chart 等
     */
    val modelUsed: String? = null,

    /**
     * 消息级元数据 JSON（白名单住户：claude_agent_state / LLM 性能指标 / savedAt）。
     * part 级键（html_card/engineer_task/imageUri/suggestions 等）已迁入 parts，禁止回写。
     */
    val metadata: String? = null,

    /**
     * 消息内容**唯一权威**：有序 parts 数组 JSON（ADR-016 D1 parts 模型；
     * [com.mamba.picme.domain.chat.MessagePartsCodec] 线格式）。v28 起 NOT NULL——
     * MIGRATION_27_28 对存量行一次性转正（损坏行降级 Text 原文兜底，不丢消息）。
     */
    val partsJson: String,
)
