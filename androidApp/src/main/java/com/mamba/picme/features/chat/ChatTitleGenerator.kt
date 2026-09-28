package com.mamba.picme.features.chat

/**
 * 根据用户的第一条消息自动生成 Chat 会话标题。
 *
 * 设计为纯函数对象，便于单元测试和后续替换为更智能的 LLM 摘要策略。
 */
internal object ChatTitleGenerator {

    const val MAX_AUTO_TITLE_LENGTH = 20

    /**
     * 自动标题生成时需要从首尾剥离的标点符号。
     */
    private val TITLE_TRIM_CHARS = setOf(
        '.', ',', '!', '?', ';', ':',
        '。', '，', '！', '？', '；', '：',
        '"', '\'', '「', '」', '『', '』',
        '(', ')', '（', '）', '[', ']', '【', '】', '{', '}'
    )

    /**
     * 根据第一条用户消息生成会话标题。
     *
     * @param firstUserMessageType 消息类型，如 [USER_TEXT] 或 [USER_IMAGE]
     * @param textContent 文本消息内容；图片消息时该值可为空
     * @param imageTitle 图片消息对应的固定标题
     * @param fallbackTitle 无法生成有意义标题时的兜底文案
     */
    fun generateTitle(
        firstUserMessageType: String,
        textContent: String,
        imageTitle: String,
        fallbackTitle: String
    ): String {
        return when (firstUserMessageType) {
            "image" -> imageTitle
            "text" -> sanitizeTitle(textContent, fallbackTitle)
            else -> fallbackTitle
        }
    }

    /**
     * 清理用户输入，使其适合作为会话标题。
     *
     * - 去除首尾空白与首尾标点
     * - 将换行与连续空白折叠为单个空格
     * - 超过 [MAX_AUTO_TITLE_LENGTH] 时截断并追加省略号
     */
    fun sanitizeTitle(content: String, fallbackTitle: String): String {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return fallbackTitle

        val singleLine = trimmed
            .replace(Regex("[\r\n]+"), " ")
            .replace(Regex("\\s+"), " ")
        val withoutEdgePunctuation = singleLine.trim { it in TITLE_TRIM_CHARS }
        val collapsed = withoutEdgePunctuation.replace(Regex("\\s+"), " ")

        return if (collapsed.length > MAX_AUTO_TITLE_LENGTH) {
            collapsed.take(MAX_AUTO_TITLE_LENGTH).trimEnd { it in TITLE_TRIM_CHARS } + "…"
        } else {
            collapsed
        }.ifBlank { fallbackTitle }
    }
}
