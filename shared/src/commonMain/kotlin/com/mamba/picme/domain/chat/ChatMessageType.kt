package com.mamba.picme.domain.chat

/**
 * Chat 消息类型（双端 SSOT，对齐 Android `ChatMessageType`）。
 *
 * 2026-08-13 由 `androidApp/.../features/chat/ChatScreen.kt` 下沉至 commonMain，
 * Android 原枚举改 typealias 引用本类。
 */
enum class ChatMessageType {
    USER_TEXT,
    AGENT_TEXT,
    USER_IMAGE,
    USER_IMAGE_TEXT,
    AGENT_IMAGE,
    AGENT_EDIT_RESULT,
    COMMAND,
    PLAN_PREVIEW,
    MEDIA_RESULTS,
    CHART,
    HTML_CARD,
    OPTIMIZE_CANDIDATES,
}
