package com.mamba.picme.domain.chat.openui

/**
 * OpenUI 文本语义层级——LLM 只声明意图层级，端侧映射 Design Token（PARITY）。
 * （自 domain.chat.a2ui.A2uiTextRole 迁入改名，2026-11-05 A2UI 退役。）
 */
enum class OpenUiTextRole {
    /** 卡片主标题（最大字号） */
    HEADLINE,

    /** 区块副标题 */
    SUBHEAD,

    /** 正文内容 */
    BODY,

    /** 辅助说明/脚注 */
    CAPTION,
}
