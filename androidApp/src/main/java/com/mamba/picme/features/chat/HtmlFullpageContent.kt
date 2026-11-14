package com.mamba.picme.features.chat

/** 全屏查看器内容载荷：html 为消息 payload（已清洗，同一 HTML 直取，不重新走 LLM）。 */
data class HtmlFullpageContent(
    val html: String,
    /** 顶栏标题（render_html 的 summary，截断显示）；null 时用默认标题。 */
    val title: String?,
    /** 来源消息 id（缩略图预览翻页集的 messageId，2026-11-15）；查看器本身不消费。 */
    val messageId: String = ""
)
