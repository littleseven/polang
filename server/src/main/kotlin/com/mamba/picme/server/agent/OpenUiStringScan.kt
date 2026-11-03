package com.mamba.picme.server.agent

/**
 * OpenUI 程序文本的字符串字面量字符级扫描器（2026-11-04 StackOverflowError 修复）。
 *
 * 背景：java.util.regex 对「分组 + 量词」（GroupCurly）的匹配是**每重复单元一层
 * 递归栈帧**——`"([^"\\]|\\.)*"` 这类交替-星号字符串字面量正则在 512KB 栈下
 * 2288 字符即爆 StackOverflowError（实测）；拆环写法 `"[^"\\]*(?:\\.[^"\\]*)*"`
 * 只把递归深度换成转义数，HTML 转义密集（\" 遍地）照样炸。turn-native 线
 * `Html("...")` 内嵌整页 HTML 动辄数万字符，终结校验（ProgramGuard /
 * ensureValidProgram）对全程序跑该正则必崩——2026-11-03 勾股定理卡事故根因。
 *
 * 结论：字符串字面量处理一律改字符级扫描（[OpenUiCallTree] 同宗先例），
 * O(n) 零递归。语义与旧正则逐字对齐（回归测试钉住）：只配对**完整**字面量，
 * 未闭合尾段原样保留不剥离。
 */
internal object OpenUiStringScan {

    /** 一个字面量的扫描结果：content = 去引号原文（不反转义）；endExclusive = 闭合引号后一位。 */
    data class Literal(val content: String, val endExclusive: Int)

    /**
     * 从 [openQuote]（必须是 `"`）扫一个完整字符串字面量（`\` 转义感知）。
     * 未闭合（流式截断/残缺程序）→ null（对齐正则「未闭合不匹配」语义）。
     */
    fun literalAt(text: String, openQuote: Int): Literal? {
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

    /**
     * 全部完整字符串字面量替换为 [placeholder]（默认 `""`），未闭合尾段原样保留——
     * 与旧 `STRING_LITERAL` 正则 `replace(program, "\"\"")` 语义对齐（回归测试钉住）。
     */
    fun stripLiterals(text: String, placeholder: String = "\"\""): String {
        if (text.indexOf('"') < 0) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '"') {
                val literal = literalAt(text, index)
                if (literal == null) {
                    // 未闭合：余下原样保留并结束（正则 findAll 在更内层也找不到配对）
                    out.append(text, index, text.length)
                    return out.toString()
                }
                out.append(placeholder)
                index = literal.endExclusive
            } else {
                out.append(text[index])
                index++
            }
        }
        return out.toString()
    }

    /** 按序枚举全部完整字面量（替代旧 `STRING_LITERAL.findAll(...)`）。 */
    fun literals(text: String): List<Literal> {
        val out = mutableListOf<Literal>()
        var index = 0
        while (index < text.length) {
            if (text[index] == '"') {
                val literal = literalAt(text, index) ?: break
                out += literal
                index = literal.endExclusive
            } else {
                index++
            }
        }
        return out
    }
}
