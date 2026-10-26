package com.mamba.picme.server.agent

/**
 * render_ui_openui 工具流式增量的 program 字段前缀提取器（openui P2 流式预览，spec
 * 《2026-10-26-openui-compose-alignment-design》§4 P2）。
 *
 * LLM 流式产出工具 args JSON（`{"program": "...", "fallbackSummary": "..."}`），Koog 以
 * ToolCallDelta.content 逐片下发。本类累积原文、JSON 感知地定位顶层 `program` 键
 * （深度 1 且不在字符串内的键名匹配，其它字段值里出现 "program" 字样不误判），
 * 逐字符解 JSON 字符串转义输出已到达的 program 前缀——未闭合/转义中途断帧时
 * 输出截至最后完整字符的前缀。
 *
 * 每次 feed 对累积原文从头重提取（KB 级文本，亚毫秒），实现无内部游标状态。
 */
internal class OpenUiProgramStreamExtractor {

    private val raw = StringBuilder()

    /** 喂入 args JSON 增量；返回当前已到达的 program 前缀（未出现 program 键 → null）。 */
    fun feed(delta: String): String? {
        raw.append(delta)
        return extractProgramPrefix(raw)
    }

    companion object {
        private const val KEY = "program"

        /** 从（可能截断的）args JSON 原文提取 program 字符串前缀。纯函数，供单测直调。 */
        fun extractProgramPrefix(text: CharSequence): String? {
            val n = text.length
            var i = 0
            var depth = 0
            while (i < n) {
                when (val c = text[i]) {
                    '{' -> {
                        depth++
                        i++
                    }
                    '}' -> {
                        depth--
                        i++
                    }
                    '[' -> i++ // 数组在 program 之前的字段值里理论上不存在，深度不跟踪也不影响键匹配
                    ']' -> i++
                    '"' -> {
                        val end = skipString(text, i) ?: return null // 未闭合字符串即流尾，后面的内容还没来
                        if (depth == 1) {
                            val token = text.substring(i + 1, end - 1)
                            var j = skipWhitespace(text, end)
                            if (token == KEY && j < n && text[j] == ':') {
                                j = skipWhitespace(text, j + 1)
                                if (j >= n) return null
                                if (text[j] != '"') return null // 非字符串值（不应发生），放弃本帧提取
                                return decodeStringPrefix(text, j)
                            }
                        }
                        i = end
                    }
                    else -> i++
                }
            }
            return null
        }

        /** 跳过从 [start]（开引号）起的字符串，返回闭引号后一位；未闭合 → null。 */
        private fun skipString(text: CharSequence, start: Int): Int? {
            var i = start + 1
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i += 2
                    '"' -> return i + 1
                    else -> i++
                }
            }
            return null
        }

        private fun skipWhitespace(text: CharSequence, start: Int): Int {
            var i = start
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }

        /** 解码从 [start]（开引号）起的字符串值前缀；遇闭引号或流尾结束，不完整转义丢弃。 */
        private fun decodeStringPrefix(text: CharSequence, start: Int): String {
            val out = StringBuilder()
            var i = start + 1
            val n = text.length
            while (i < n) {
                when (val c = text[i]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i + 1 >= n) return out.toString() // 转义中途断帧
                        when (val esc = text[i + 1]) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 5 >= n) return out.toString() // \uXXXX 不完整
                                val hex = text.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16) ?: return out.toString()
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> out.append(esc) // 未知转义按字符原样（容错）
                        }
                        i += 2
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }
    }
}
