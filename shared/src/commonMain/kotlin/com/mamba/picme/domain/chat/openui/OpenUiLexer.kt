package com.mamba.picme.domain.chat.openui

/** OpenUI Lang 1.0 词法 token（§1.3）。 */
internal enum class OpenUiTokenKind {
    NEWLINE,
    LPAREN, RPAREN, LBRACK, RBRACK, LBRACE, RBRACE,
    COMMA, COLON,
    EQUALS, EQEQ, NOTEQ, NOT,
    GREATER, GREATEREQ, LESS, LESSEQ,
    AND, OR,
    DOT, QUESTION,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    STR, NUM,
    IDENT, TYPE, STATEVAR, BUILTINCALL,
    TRUE, FALSE, NULL,
    EOF,
}

/**
 * @param text STR=反转义后的值；IDENT/TYPE/STATEVAR/BUILTINCALL=名字（STATEVAR 含 `$` 前缀）；
 *   NUM=原始文本（对象 key 化时用 [num] 经 openUiNumToString 格式化，对齐 JS String(num)）。
 */
internal data class OpenUiToken(
    val kind: OpenUiTokenKind,
    val text: String = "",
    val num: Double = 0.0,
)

/**
 * OpenUI Lang 1.0 词法器（§1.3）。词法永不失败：无法匹配的字符跳过（`a;b` 词法为 `a` 与 `b`）。
 *
 * 关键规则：
 * - 单 `&`/`|` 即 `&&`/`||`；
 * - `.` 仅在后跟数字时属于数字（`1.` = `1` + 成员访问）；
 * - `e`/`E` 在数字后恒为指数标记，即使后无数字——该 token 不可用（值取 NaN；
 *   spec §1.6 声明悬空指数取值 implementation-defined，本实现对齐参考实现 `+"1e"` = NaN）；
 * - `-` 仅在前一 token 不是值（字面量/引用/`)`/`]`，但不含 `}`）且后跟数字时
 *   开启负数数字面量（故 `[1 -2]` 是一个元素 `1 - 2`）；
 * - 双引号字符串用 JSON 转义（含 \r \b \f \/ 与 \uXXXX）；单引号仅支持 \' \\ \n \t，
 *   其他转义字符去反斜杠保留原字符（§1.3 "kept as is"）；
 * - 未闭合字符串隐式闭合（§4 流式规则，批量解析 = 流结束）。
 *
 * 注：源码中以 \uXXXX 形式书写退格/换页/回车字面量，规避工具链对控制字符的转义损耗。
 */
internal object OpenUiLexer {

    private const val BACKSPACE = '\u0008'
    private const val FORM_FEED = '\u000C'
    private const val CARRIAGE_RETURN = '\u000D'

    fun tokenize(src: String): List<OpenUiToken> {
        val tokens = mutableListOf<OpenUiToken>()
        var i = 0
        val n = src.length
        while (i < n) {
            while (i < n && (src[i] == ' ' || src[i] == '\t' || src[i] == '\r')) i++
            if (i >= n) break
            val c = src[i]
            when {
                c == '\n' -> {
                    tokens += OpenUiToken(OpenUiTokenKind.NEWLINE)
                    i++
                }
                c == '(' -> { tokens += OpenUiToken(OpenUiTokenKind.LPAREN); i++ }
                c == ')' -> { tokens += OpenUiToken(OpenUiTokenKind.RPAREN); i++ }
                c == '[' -> { tokens += OpenUiToken(OpenUiTokenKind.LBRACK); i++ }
                c == ']' -> { tokens += OpenUiToken(OpenUiTokenKind.RBRACK); i++ }
                c == '{' -> { tokens += OpenUiToken(OpenUiTokenKind.LBRACE); i++ }
                c == '}' -> { tokens += OpenUiToken(OpenUiTokenKind.RBRACE); i++ }
                c == ',' -> { tokens += OpenUiToken(OpenUiTokenKind.COMMA); i++ }
                c == ':' -> { tokens += OpenUiToken(OpenUiTokenKind.COLON); i++ }
                c == '.' -> { tokens += OpenUiToken(OpenUiTokenKind.DOT); i++ }
                c == '?' -> { tokens += OpenUiToken(OpenUiTokenKind.QUESTION); i++ }
                c == '+' -> { tokens += OpenUiToken(OpenUiTokenKind.PLUS); i++ }
                c == '*' -> { tokens += OpenUiToken(OpenUiTokenKind.STAR); i++ }
                c == '/' -> { tokens += OpenUiToken(OpenUiTokenKind.SLASH); i++ }
                c == '%' -> { tokens += OpenUiToken(OpenUiTokenKind.PERCENT); i++ }
                c == '=' -> {
                    if (i + 1 < n && src[i + 1] == '=') {
                        tokens += OpenUiToken(OpenUiTokenKind.EQEQ); i += 2
                    } else {
                        tokens += OpenUiToken(OpenUiTokenKind.EQUALS); i++
                    }
                }
                c == '!' -> {
                    if (i + 1 < n && src[i + 1] == '=') {
                        tokens += OpenUiToken(OpenUiTokenKind.NOTEQ); i += 2
                    } else {
                        tokens += OpenUiToken(OpenUiTokenKind.NOT); i++
                    }
                }
                c == '>' -> {
                    if (i + 1 < n && src[i + 1] == '=') {
                        tokens += OpenUiToken(OpenUiTokenKind.GREATEREQ); i += 2
                    } else {
                        tokens += OpenUiToken(OpenUiTokenKind.GREATER); i++
                    }
                }
                c == '<' -> {
                    if (i + 1 < n && src[i + 1] == '=') {
                        tokens += OpenUiToken(OpenUiTokenKind.LESSEQ); i += 2
                    } else {
                        tokens += OpenUiToken(OpenUiTokenKind.LESS); i++
                    }
                }
                c == '&' -> {
                    if (i + 1 < n && src[i + 1] == '&') i += 2 else i++
                    tokens += OpenUiToken(OpenUiTokenKind.AND)
                }
                c == '|' -> {
                    if (i + 1 < n && src[i + 1] == '|') i += 2 else i++
                    tokens += OpenUiToken(OpenUiTokenKind.OR)
                }
                c == '"' -> {
                    val result = readString(src, i, '"')
                    tokens += OpenUiToken(OpenUiTokenKind.STR, text = result.first)
                    i = result.second
                }
                c == '\'' -> {
                    val result = readString(src, i, '\'')
                    tokens += OpenUiToken(OpenUiTokenKind.STR, text = result.first)
                    i = result.second
                }
                c == '-' && !isAfterValue(tokens) && i + 1 < n && src[i + 1].isDigit() -> {
                    val result = readNumber(src, i)
                    tokens += result.first
                    i = result.second
                }
                c == '-' -> {
                    tokens += OpenUiToken(OpenUiTokenKind.MINUS); i++
                }
                c.isDigit() -> {
                    val result = readNumber(src, i)
                    tokens += result.first
                    i = result.second
                }
                c == '$' && i + 1 < n && isIdentStart(src[i + 1]) -> {
                    val start = i
                    i++
                    while (i < n && isIdentPart(src[i])) i++
                    tokens += OpenUiToken(OpenUiTokenKind.STATEVAR, text = src.substring(start, i))
                }
                c == '@' && i + 1 < n && isIdentStart(src[i + 1]) -> {
                    i++
                    val start = i
                    while (i < n && isIdentPart(src[i])) i++
                    tokens += OpenUiToken(OpenUiTokenKind.BUILTINCALL, text = src.substring(start, i))
                }
                isIdentStart(c) -> {
                    val start = i
                    while (i < n && isIdentPart(src[i])) i++
                    val word = src.substring(start, i)
                    tokens += when (word) {
                        "true" -> OpenUiToken(OpenUiTokenKind.TRUE)
                        "false" -> OpenUiToken(OpenUiTokenKind.FALSE)
                        "null" -> OpenUiToken(OpenUiTokenKind.NULL)
                        else -> OpenUiToken(
                            if (c in 'A'..'Z') OpenUiTokenKind.TYPE else OpenUiTokenKind.IDENT,
                            text = word,
                        )
                    }
                }
                else -> i++ // 无法匹配的字符跳过（§1.3：lexing never fails）
            }
        }
        tokens += OpenUiToken(OpenUiTokenKind.EOF)
        return tokens
    }

    /** `-` 的判定：前一 token 是值（字面量/引用/`)`/`]`，但不含 `}`）则 `-` 是减号。 */
    private fun isAfterValue(tokens: List<OpenUiToken>): Boolean =
        when (tokens.lastOrNull()?.kind) {
            OpenUiTokenKind.NUM, OpenUiTokenKind.STR,
            OpenUiTokenKind.IDENT, OpenUiTokenKind.TYPE,
            OpenUiTokenKind.RPAREN, OpenUiTokenKind.RBRACK,
            OpenUiTokenKind.TRUE, OpenUiTokenKind.FALSE, OpenUiTokenKind.NULL,
            OpenUiTokenKind.STATEVAR, OpenUiTokenKind.BUILTINCALL,
            -> true
            else -> false
        }

    /** @return 解析出的字符串值 to 下一字符下标（未闭合时隐式闭合到文本尾）。 */
    private fun readString(src: String, start: Int, quote: Char): Pair<String, Int> {
        val out = StringBuilder()
        var i = start + 1
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '\\' && i + 1 < n) {
                val esc = src[i + 1]
                when (esc) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    '\\' -> out.append('\\')
                    '\'' -> out.append('\'')
                    '"' -> out.append('"')
                    'r' -> out.append(if (quote == '"') CARRIAGE_RETURN else 'r')
                    'b' -> out.append(if (quote == '"') BACKSPACE else 'b')
                    'f' -> out.append(if (quote == '"') FORM_FEED else 'f')
                    '/' -> out.append('/')
                    'u' -> {
                        // \uXXXX 仅双引号（JSON 转义）；截断/畸形的转义按原字符保留
                        val hex = if (quote == '"' && i + 6 <= n) src.substring(i + 2, i + 6) else null
                        val code = hex?.toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 6
                            continue
                        }
                        out.append('u')
                    }
                    // 其他转义字符：去反斜杠保留原字符（§1.3 "kept as is"）
                    else -> out.append(esc)
                }
                i += 2
                continue
            }
            if (c == quote) return out.toString() to i + 1
            out.append(c)
            i++
        }
        return out.toString() to n
    }

    /** @return NUM token to 下一字符下标。`1e` 这类悬空指数值取 NaN（spec §1.6 未定义取值）。 */
    private fun readNumber(src: String, start: Int): Pair<OpenUiToken, Int> {
        var i = start
        val n = src.length
        if (src[i] == '-') i++
        while (i < n && src[i].isDigit()) i++
        if (i < n && src[i] == '.' && i + 1 < n && src[i + 1].isDigit()) {
            i++
            while (i < n && src[i].isDigit()) i++
        }
        if (i < n && (src[i] == 'e' || src[i] == 'E')) {
            i++
            if (i < n && (src[i] == '+' || src[i] == '-')) i++
            while (i < n && src[i].isDigit()) i++
        }
        val raw = src.substring(start, i)
        return OpenUiToken(OpenUiTokenKind.NUM, text = raw, num = raw.toDoubleOrNull() ?: Double.NaN) to i
    }

    private fun isIdentStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_'

    private fun isIdentPart(c: Char): Boolean = isIdentStart(c) || c.isDigit()
}
