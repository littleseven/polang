package com.mamba.picme.features.chat.openui

/**
 * 回合原生（turn-native，spec§3.4/§3.7）：响应程序的程序嗅探与 fallbackSummary 提取。
 *
 * 规则与 server `RenderOpenUiValidator` 结构校验是**字面量副本关系**（root 行首声明 /
 * Card 兜底 entry），改动须双向同步核对；本文件只做嗅探与提取，不做完整校验
 * （端上永远收到合法树由 server 双保险保证，这里是消息分派启发式）。
 *
 * 2026-11-04 SOE 修复：字符串字面量一律字符级扫描（与 server `OpenUiStringScan`
 * 同宗字面量副本）——旧 `"([^"\\]|\\.)*"` 交替-星号正则的 GroupCurly 对每个字符
 * 递归一层栈帧，512KB 栈实测 2288 字符字面量即 StackOverflowError；turn-native 线
 * `Html("…")` 内嵌整页 HTML（数千字符转义密集字面量）到达本嗅探必崩。
 */
object OpenUiProgramSummary {

    private val SUMMARY_COMMENT = Regex("""(?m)^\s*//\s*summary\s*[:：]\s*(.+)$""")
    private val ROOT_STATEMENT = Regex("""(?m)^\s*root\s*=""")
    private val CARD_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Card\s*\(""")
    // 前缀正则只定位到开引号，字面量本体由 literalAt 字符级扫描（见文件头 SOE 注）
    private val TEXT_CALL =
        Regex("""(?<![@${'$'}A-Za-z0-9_])(?:Markdown|Text)\s*\(\s*"""")

    private const val MAX_SUMMARY_LEN = 80

    /** assistant content 是否按程序解析（false → 按纯文本 AssistantText 降级上屏）。 */
    fun looksLikeProgram(content: String): Boolean {
        val stripped = stripLiterals(content)
        return ROOT_STATEMENT.containsMatchIn(stripped) || CARD_CALL.containsMatchIn(stripped)
    }

    /** 降级摘要：// summary 首行注释 > 首个 Markdown/Text 字符串字面量（截 80 字）> 空串。 */
    fun extractFallbackSummary(program: String): String {
        SUMMARY_COMMENT.find(program)?.let { match ->
            return match.groupValues[1].trim().take(MAX_SUMMARY_LEN)
        }
        TEXT_CALL.findAll(program).forEach { match ->
            // 未闭合字面量跳过继续找下一个（与旧正则「未闭合不匹配」同口径）
            val literal = literalAt(program, match.range.last) ?: return@forEach
            return literal.content
                .replace("\\n", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim()
                .take(MAX_SUMMARY_LEN)
        }
        return ""
    }

    /** 字面量扫描结果：content = 去引号原文（不反转义）；endExclusive = 闭合引号后一位。 */
    private data class Literal(val content: String, val endExclusive: Int)

    /**
     * 从 [openQuote]（必须是 `"`）扫一个完整字符串字面量（`\` 转义感知）；
     * 未闭合 → null。O(n) 零递归。
     */
    private fun literalAt(text: String, openQuote: Int): Literal? {
        if (openQuote < 0 || openQuote >= text.length || text[openQuote] != '"') return null
        var index = openQuote + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index++
                '"' -> return Literal(text.substring(openQuote + 1, index), index + 1)
            }
            index++
        }
        return null
    }

    /** 全部完整字符串字面量替换为 `""`，未闭合尾段原样保留（与旧正则替换语义对齐）。 */
    private fun stripLiterals(text: String): String {
        if (text.indexOf('"') < 0) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '"') {
                val literal = literalAt(text, index)
                if (literal == null) {
                    out.append(text, index, text.length)
                    return out.toString()
                }
                out.append("\"\"")
                index = literal.endExclusive
            } else {
                out.append(text[index])
                index++
            }
        }
        return out.toString()
    }
}
