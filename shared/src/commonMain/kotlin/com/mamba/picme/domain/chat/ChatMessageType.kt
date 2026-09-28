package com.mamba.picme.domain.chat

/**
 * Chat 消息 UI 类型（双端 SSOT，对齐 Android `ChatMessageType`）。
 *
 * 2026-08-13 由 `androidApp/.../features/chat/ChatScreen.kt` 下沉至 commonMain，
 * Android 原枚举改 typealias 引用本类。
 *
 * 消息语义枚举（渲染分支用）；8 值线分类法（content/tool/data 前缀）住在 parts 层
 * （[MessagePart] serialName / Room type 列）。role 不在本枚举——已升格为独立消息级字段
 * （[ChatMessage.role]，spec §2）。`COMMAND`/`PLAN_PREVIEW` 已删除：并入 text
 * （`LegacyChatTypeMigration` 存量行转正）。
 */
enum class ChatMessageType {
    USER_TEXT,
    AGENT_TEXT,
    USER_IMAGE,
    USER_IMAGE_TEXT,
    AGENT_IMAGE,
    AGENT_EDIT_RESULT,
    MEDIA_RESULTS,
    CHART,
    HTML_CARD,
    TASK_CARD,
    OPTIMIZE_CANDIDATES,
}
