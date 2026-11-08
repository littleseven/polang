package com.mamba.picme.domain.chat

/**
 * Chat 消息渲染 kind 枚举（双端 SSOT，对齐 Android `ChatMessageType`）。
 *
 * 2026-08-13 由 `androidApp/.../features/chat/ChatScreen.kt` 下沉至 commonMain，
 * Android 原枚举改 typealias 引用本类。
 *
 * 纯 kind 枚举（2026-11-08 值域终态 D2a：11 值收敛 9 值）——role 由消息级
 * [ChatMessage.role] 独立表达，枚举不再带 USER_/AGENT_ 前缀变体（原
 * USER_TEXT/AGENT_TEXT → [TEXT]，USER_IMAGE/AGENT_IMAGE → [IMAGE]；
 * [IMAGE_TEXT] 保留独立 kind：图文混排气泡布局需要，role 恒 user）。
 *
 * 消息语义枚举（渲染分支用）；part 级 4+1 分类 13 值线分类法住在 parts 层
 * （[MessagePart] serialName）。Room `type` 列写的是 wire 字符串（part 鉴别值
 * 口径），不经本枚举——枚举仅在 UI 投影（toUiModel / iOS `uiTypeOf`）与渲染
 * 分支消费。
 */
enum class ChatMessageType {
    TEXT,
    IMAGE,
    IMAGE_TEXT,
    EDIT_RESULT,
    CHART,
    HTML_CARD,
    TASK_CARD,
    OPTIMIZE_CANDIDATES,
    OPENUI_CARD,
}
