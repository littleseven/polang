package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OpenUiStringScan 字符级字符串字面量扫描测试（2026-11-04 StackOverflowError 修复）。
 *
 * 事故原型：勾股定理演示卡——`Html("…")` 内嵌整页 HTML（转义密集大字面量），
 * 终结校验（ProgramGuard → validate）用 `"([^"\\]|\\.)*"` 交替-星号正则剥离字符串，
 * java.util.regex GroupCurly 对每个字符递归一层栈帧，512KB 栈实测 2288 字符即
 * StackOverflowError → Finish(reason="error: StackOverflowError")，无 assistant 落库。
 * 拆环写法 `"[^"\\]*(?:\\.[^"\\]*)*"` 把递归深度换成转义数，转义密集照样炸（均已实测）。
 *
 * 本测试 = ① 与旧正则逐字对齐的语义语料 ② 256KB 小栈线程 + 10 万字符
 * 转义密集字面量的存活回归（旧实现必崩，新实现 O(n) 零递归）。
 */
class OpenUiStringScanTest {

    // ── 语义语料（与旧正则 replace/findAll 口径逐字对齐）──

    @Test
    fun `stripLiterals replaces complete literals and keeps unterminated tail`() {
        val cases = listOf(
            "\"abc\"" to "\"\"",
            "x\"a\"y\"b\"z" to "x\"\"y\"\"z",
            "\"a\\\"b\"c" to "\"\"c",
            "\"a" to "\"a", // 未闭合：原样保留
            "a\"" to "a\"",
            "\"\\\\\"" to "\"\"", // 转义反斜杠后闭合
            "\"a\\nb\"" to "\"\"",
            "\"\"\"\"" to "\"\"\"\"", // 两个空字面量
            "no quotes" to "no quotes",
            "\"a\\\"b" to "\"a\\\"b", // 转义吞掉引号 → 整体未闭合保留
        )
        cases.forEach { (input, expected) ->
            assertEquals("input=$input", expected, OpenUiStringScan.stripLiterals(input))
        }
    }

    @Test
    fun `stripLiterals supports custom placeholder`() {
        assertEquals("Image(<>, <>)", OpenUiStringScan.stripLiterals("Image(\"photo_1\", \"\")", "<>"))
    }

    @Test
    fun `literalAt scans escaped chars and reports close position`() {
        val text = "pre\"a\\\"b\"post"
        val literal = OpenUiStringScan.literalAt(text, 3)!!
        assertEquals("a\\\"b", literal.content)
        assertEquals(9, literal.endExclusive)
        assertNull(OpenUiStringScan.literalAt("\"unterminated", 0))
        assertNull(OpenUiStringScan.literalAt("x", 0))
    }

    @Test
    fun `literals enumerates complete literals in order`() {
        val literals = OpenUiStringScan.literals("a\"one\"b\"two\"c")
        assertEquals(listOf("one", "two"), literals.map { it.content })
    }

    // ── SOE 回归（256KB 小栈线程，旧交替-星号正则在此规模必崩）──

    @Test
    fun `huge escape-dense literal survives on small stack`() {
        val literal = buildString {
            append('"')
            var i = 0
            while (length < 100_000) {
                if (i % 3 == 0) append("\\\"") else append('x')
                i++
            }
            append('"')
        }
        val program = "root = Html($literal)"
        runOnSmallStack {
            assertEquals("root = Html(\"\")", OpenUiStringScan.stripLiterals(program))
        }
    }

    /** 未闭合字面量（流式截断形态）：扫描到尾不崩、原样保留。 */
    @Test
    fun `huge unterminated literal survives on small stack`() {
        val program = "root = Html(\"" + "x".repeat(100_000)
        runOnSmallStack {
            assertEquals(program, OpenUiStringScan.stripLiterals(program))
        }
    }

    private fun runOnSmallStack(block: () -> Unit) {
        var failure: Throwable? = null
        val thread = Thread(
            null,
            {
                try {
                    block()
                } catch (e: Throwable) {
                    failure = e
                }
            },
            "openui-scan-soe-test",
            256L * 1024,
        )
        thread.start()
        thread.join()
        failure?.let { throw it }
    }
}
