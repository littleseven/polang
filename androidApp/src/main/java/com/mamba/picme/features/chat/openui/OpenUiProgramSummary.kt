package com.mamba.picme.features.chat.openui

/**
 * 回合原生（turn-native，spec§3.4/§3.7）：响应程序的程序嗅探与 fallbackSummary 提取。
 *
 * 规则与 server `RenderOpenUiValidator` 结构校验是**字面量副本关系**（root 行首声明 /
 * Card 兜底 entry），改动须双向同步核对；本文件只做嗅探与提取，不做完整校验
 * （端上永远收到合法树由 server 双保险保证，这里是消息分派启发式）。
 */
object OpenUiProgramSummary {

    private val SUMMARY_COMMENT = Regex("""(?m)^\s*//\s*summary\s*[:：]\s*(.+)$""")
    private val STRING_LITERAL = Regex(""""([^"\\]|\\.)*"""")
    private val ROOT_STATEMENT = Regex("""(?m)^\s*root\s*=""")
    private val CARD_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Card\s*\(""")
    private val TEXT_CALL =
        Regex("""(?<![@${'$'}A-Za-z0-9_])(?:Markdown|Text)\s*\(\s*"((?:[^"\\]|\\.)*)"""")

    private const val MAX_SUMMARY_LEN = 80

    /** assistant content 是否按程序解析（false → 按纯文本 AssistantText 降级上屏）。 */
    fun looksLikeProgram(content: String): Boolean {
        val stripped = STRING_LITERAL.replace(content, "\"\"")
        return ROOT_STATEMENT.containsMatchIn(stripped) || CARD_CALL.containsMatchIn(stripped)
    }

    /** 降级摘要：// summary 首行注释 > 首个 Markdown/Text 字符串字面量（截 80 字）> 空串。 */
    fun extractFallbackSummary(program: String): String {
        SUMMARY_COMMENT.find(program)?.let { match ->
            return match.groupValues[1].trim().take(MAX_SUMMARY_LEN)
        }
        TEXT_CALL.find(program)?.let { match ->
            return match.groupValues[1]
                .replace("\\n", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim()
                .take(MAX_SUMMARY_LEN)
        }
        return ""
    }
}
