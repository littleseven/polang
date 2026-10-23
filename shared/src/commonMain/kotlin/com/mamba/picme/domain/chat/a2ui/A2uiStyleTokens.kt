package com.mamba.picme.domain.chat.a2ui

/**
 * A2UI 语义样式令牌——LLM 可见的样式闭集，端侧映射到 Design Token。
 *
 * 目的：LLM 不直接指定具体 dp/sp/色值，只声明"意图层级"，
 * 端侧各自映射到 MaterialTheme / DesignTokens，保双端一致（PARITY）。
 *
 * 线格式（A2UI updateDataModel 的 value 字符串值）：
 * - Text 增可选 "role" 属性：HEADLINE|SUBHEAD|BODY|CAPTION（默认 BODY）
 * - 颜色增可选 "colorRole"：PRIMARY|SURFACE|ERROR|ON_SURFACE（默认 ON_SURFACE）
 * - 间距增可选 "spacing"：COMPACT|STANDARD|COMFORTABLE（默认 STANDARD）
 *
 * 与 server `RenderUiToolService` @LLMDescription 第 7 条约束是字面量副本关系，
 * 改动须双向同步核对（同 A2uiCardContract 文件头约定）。
 */
enum class A2uiTextRole {
    /** 卡片主标题（最大字号） */
    HEADLINE,

    /** 区块副标题 */
    SUBHEAD,

    /** 正文内容 */
    BODY,

    /** 辅助说明/脚注 */
    CAPTION,
}

enum class A2uiColorRole {
    /** 主强调色（主题 primary） */
    PRIMARY,

    /** 表面背景色 */
    SURFACE,

    /** 错误/警示色 */
    ERROR,

    /** 中性正文色 */
    ON_SURFACE,
}

enum class A2uiSpacingRole {
    /** 紧凑内边距（4dp） */
    COMPACT,

    /** 标准内边距（8dp） */
    STANDARD,

    /** 宽松内边距（16dp） */
    COMFORTABLE,
}
