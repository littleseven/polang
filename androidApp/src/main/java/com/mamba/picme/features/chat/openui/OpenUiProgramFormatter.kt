package com.mamba.picme.features.chat.openui

/**
 * OpenUI Lang 程序美化器（debug 蓝图查看器专用）。
 *
 * 输入 LLM 生成的单行长嵌套程序，输出带缩进的多行文本，规则：
 * - 字符串（"…"/'…'，含转义）与行注释（// …）原样保留，绝不在内部断行；
 * - `[`/`(` 展开为多行当且仅当：跨度内含直接子层 `[`/`(`（嵌套组件/数组），
 *   或跨度字符数超过阈值（数组 48 / 调用 72）；`{` 对象恒内联；
 * - 多行容器内顶层 `,` 后断行，缩进一级（4 空格）；内联容器保留原始空格；
 * - 顶层语句换行与空行原样保留。
 * 纯函数无依赖，便于单测；词法永不失败（未知字符原样透传），与 OpenUiLexer 同哲学。
 */
internal object OpenUiProgramFormatter {

    private const val INDENT = "    "
    private const val ARRAY_INLINE_MAX = 48
    private const val CALL_INLINE_MAX = 72

    fun format(src: String): String {
        val spans = scan(src)
        val out = StringBuilder(src.length + src.length / 3)
        val stack = ArrayDeque<Frame>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                // 字符串整体透传
                c == '"' || c == '\'' -> {
                    val end = stringEnd(src, i)
                    out.append(src, i, end)
                    i = end
                }
                // 行注释整体透传
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    var end = i
                    while (end < n && src[end] != '\n') end++
                    out.append(src, i, end)
                    i = end
                }
                c == '[' || c == '(' -> {
                    val span = spans[i]
                    val multiline = span != null && span.multiline
                    stack.addLast(Frame(c, multiline))
                    out.append(c)
                    if (multiline) {
                        out.append('\n')
                        appendIndent(out, multilineDepth(stack))
                        i++
                        while (i < n && (src[i] == ' ' || src[i] == '\t')) i++
                    } else {
                        i++
                    }
                }
                c == ']' || c == ')' -> {
                    val frame = if (stack.isEmpty()) null else stack.removeLast()
                    if (frame?.multiline == true) {
                        trimTrailingSpaces(out)
                        out.append('\n')
                        appendIndent(out, multilineDepth(stack))
                    }
                    out.append(c)
                    i++
                }
                c == '{' || c == '}' -> {
                    if (c == '{') stack.addLast(Frame(c, multiline = false)) else if (stack.isNotEmpty()) stack.removeLast()
                    out.append(c)
                    i++
                }
                c == ',' -> {
                    out.append(c)
                    val frame = stack.lastOrNull()
                    if (frame?.multiline == true) {
                        out.append('\n')
                        appendIndent(out, multilineDepth(stack))
                        i++
                        while (i < n && (src[i] == ' ' || src[i] == '\t')) i++
                    } else {
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private data class Frame(val open: Char, val multiline: Boolean)

    private class Span(val close: Int, val multiline: Boolean)

    /** 预扫描：匹配括号并按内容决定每个 `[`/`(` 是否多行展开。字符串/注释不参与配对。 */
    private fun scan(src: String): Map<Int, Span> {
        val spans = HashMap<Int, Span>()
        val opens = ArrayList<Int>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '"' || c == '\'' -> i = stringEnd(src, i)
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    while (i < n && src[i] != '\n') i++
                }
                c == '[' || c == '(' || c == '{' -> {
                    opens.add(i)
                    i++
                }
                c == ']' || c == ')' || c == '}' -> {
                    if (opens.isNotEmpty()) {
                        val openIdx = opens.removeAt(opens.size - 1)
                        val openChar = src[openIdx]
                        if (openChar == '[' || openChar == '(') {
                            spans[openIdx] = Span(
                                close = i,
                                multiline = decideMultiline(src, openIdx, i, openChar, spans),
                            )
                        }
                    }
                    i++
                }
                else -> i++
            }
        }
        return spans
    }

    /**
     * 多行判定：
     * - 调用 `(` 的整个内容恰为一个数组（`Comp([...])` 形态）→ 恒内联，展开交给该数组；
     * - 跨度内相对深度 1 处（直接子元素/参数）出现调用 `(` 或数组字面量 `[` 即复杂结构
     *   （`photos[0]` 这类下标访问的 `[` 不算——前导字符是标识符/`]`/`)`）；
     * - 否则按跨度长度兜底（数组 > 48、调用 > 72 字符）。
     */
    private fun decideMultiline(
        src: String,
        open: Int,
        close: Int,
        openChar: Char,
        spans: Map<Int, Span>,
    ): Boolean {
        if (openChar == '(') {
            var s = open + 1
            while (s < close && (src[s] == ' ' || src[s] == '\t' || src[s] == '\n')) s++
            var e = close - 1
            while (e > s && (src[e] == ' ' || src[e] == '\t' || src[e] == '\n')) e--
            if (s < close && src[s] == '[' && spans[s]?.close == e) return false
        }
        var depth = 0
        var i = open + 1
        var prev: Char? = null
        while (i < close) {
            val c = src[i]
            when {
                c == '"' || c == '\'' -> {
                    i = stringEnd(src, i)
                    prev = '"'
                }
                c == '/' && i + 1 < close && src[i + 1] == '/' -> {
                    while (i < close && src[i] != '\n') i++
                }
                c == '(' -> {
                    if (depth == 0) return true
                    depth++
                    prev = c
                    i++
                }
                c == '[' -> {
                    val structural = prev == null || prev == ',' || prev == '[' || prev == '(' ||
                        prev == '=' || prev == ':' || prev == '{'
                    if (depth == 0 && structural) return true
                    depth++
                    prev = c
                    i++
                }
                c == '{' -> {
                    depth++
                    prev = c
                    i++
                }
                c == ']' || c == ')' || c == '}' -> {
                    depth--
                    prev = c
                    i++
                }
                else -> {
                    if (c != ' ' && c != '\t' && c != '\n') prev = c
                    i++
                }
            }
        }
        val limit = if (openChar == '[') ARRAY_INLINE_MAX else CALL_INLINE_MAX
        return close - open > limit
    }

    /** 返回从 [start]（引号位）起整个字符串字面量之后的下标；未闭合则返回 src.length。 */
    private fun stringEnd(src: String, start: Int): Int {
        val quote = src[start]
        var i = start + 1
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            i++
        }
        return n
    }

    private fun appendIndent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append(INDENT) }
    }

    /** 缩进深度只计多行帧——内联帧（如 `Comp([` 的 `(`）不产生新行，不占缩进层级。 */
    private fun multilineDepth(stack: ArrayDeque<Frame>): Int = stack.count { it.multiline }

    private fun trimTrailingSpaces(out: StringBuilder) {
        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
            out.deleteCharAt(out.length - 1)
        }
    }
}
