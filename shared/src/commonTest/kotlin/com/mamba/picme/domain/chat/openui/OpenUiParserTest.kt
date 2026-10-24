package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [OpenUiParser] 词法/文法测试（OpenUI Lang 1.0 §1）：
 * 语句边界（§1.4）、fence 抽取与注释剥离（§1.2）、数字边界（§1.3）、
 * 可选逗号与对象 key 形态（§1.5）。
 */
class OpenUiParserTest {

    private fun statementNames(program: String): List<String> =
        OpenUiParser.parse(program).statements.map { stmt -> stmt.name }

    private fun evaluate(program: String, name: String): OpenUiValue? {
        val parsed = OpenUiParser.parse(program)
        val runtime = OpenUiRuntime(
            statements = linkedMapOf<String, OpenUiStatement>()
                .also { map -> parsed.statements.forEach { stmt -> map[stmt.name] = stmt } },
            store = mutableMapOf(),
            queryResults = mutableMapOf(),
        )
        return runtime.evalStatement(name)
    }

    // ── 语句边界（§1.4） ────────────────────────────────────────────

    @Test
    fun `bracket continuation keeps statement together`() {
        val program = """
            root = Card([
              Text("a"),
            ])
        """.trimIndent()
        assertEquals(listOf("root"), statementNames(program))
    }

    @Test
    fun `raw newline inside string does not split statement`() {
        val program = "t = Text(\"line1\nline2\")"
        assertEquals(listOf("t"), statementNames(program))
        val text = evaluate(program, "t")
        assertIs<OpenUiValue.ComponentVal>(text)
        assertEquals(OpenUiValue.Str("line1\nline2"), text.props["text"])
    }

    @Test
    fun `multiline ternary continues across newlines`() {
        val program = """
            status = ${'$'}count > 0
              ? "Has items"
              : "Empty"
            next = 1
        """.trimIndent()
        assertEquals(listOf("status", "next"), statementNames(program))
    }

    // ── fence 抽取与注释剥离（§1.2） ────────────────────────────────

    @Test
    fun `fence extraction ignores prose outside fences`() {
        val program = """
            outside = 9
            ```openui
            a = 1
            ```
            中间散文
            ```
            b = 2
            ```
        """.trimIndent()
        assertEquals(listOf("a", "b"), statementNames(program))
    }

    @Test
    fun `comment stripping keeps markers inside strings`() {
        val program = """
            // 整行注释
            a = 1 # 行尾注释
            b = "http://x#y" // 注释
        """.trimIndent()
        assertEquals(listOf("a", "b"), statementNames(program))
        assertEquals(OpenUiValue.Str("http://x#y"), evaluate(program, "b"))
    }

    // ── 数字边界（§1.3） ────────────────────────────────────────────

    @Test
    fun `trailing dot is member access not part of number`() {
        val program = "a = 1.\nb = 2"
        assertEquals(listOf("a", "b"), statementNames(program))
        // 1. → Num(1) + 成员访问（字段 "?"），数字上成员访问 = null
        assertEquals(OpenUiValue.Null, evaluate(program, "a"))
    }

    @Test
    fun `dangling exponent lexes as one unusable number token`() {
        val program = "a = 1e\nb = 2"
        assertEquals(listOf("a", "b"), statementNames(program))
        val value = evaluate(program, "a")
        assertIs<OpenUiValue.Num>(value)
        assertTrue(value.v.isNaN(), "悬空指数的数Token不可用（§1.3），值为 NaN")
    }

    @Test
    fun `minus starts number only when previous token is not a value`() {
        val program = "a = [-2]\nb = [1 -2]"
        assertEquals(OpenUiValue.Arr(listOf(OpenUiValue.Num(-2.0))), evaluate(program, "a"))
        // §1.3：[1 -2] 是一个元素 1 - 2
        assertEquals(OpenUiValue.Arr(listOf(OpenUiValue.Num(-1.0))), evaluate(program, "b"))
    }

    // ── 可选逗号 / 对象 key 形态（§1.5） ─────────────────────────────

    @Test
    fun `commas between elements are optional and trailing comma ignored`() {
        assertEquals(
            OpenUiValue.Arr(listOf(OpenUiValue.Num(1.0), OpenUiValue.Num(2.0), OpenUiValue.Num(3.0))),
            evaluate("a = [1 2 3]", "a"),
        )
        assertEquals(
            OpenUiValue.Obj(mapOf("x" to OpenUiValue.Num(1.0), "y" to OpenUiValue.Num(2.0))),
            evaluate("o = {x: 1, y: 2,}", "o"),
        )
        val component = evaluate("c = Text(\"a\", \"BODY\",)", "c")
        assertIs<OpenUiValue.ComponentVal>(component)
        assertEquals(OpenUiValue.Str("BODY"), component.props["role"])
    }

    @Test
    fun `object keys accept names strings numbers state names and component names`() {
        val value = evaluate("o = {name: 1, \"str\": 2, 3: 3, ${'$'}sel: 4, Comp: 5}", "o")
        assertIs<OpenUiValue.Obj>(value)
        assertEquals(listOf("name", "str", "3", "sel", "Comp"), value.v.keys.toList())
    }

    @Test
    fun `single ampersand and pipe mean logical and or`() {
        assertEquals(OpenUiValue.Bool(false), evaluate("a = true & false", "a"))
        assertEquals(OpenUiValue.Bool(true), evaluate("b = true | false", "b"))
    }

    // ── 杂项 ────────────────────────────────────────────────────────

    @Test
    fun `shapeless lines are skipped without error`() {
        assertEquals(listOf("a"), statementNames("just some prose\na = 1"))
    }

    @Test
    fun `builtin name without at-sign parses as reference and args are lost`() {
        // §1.5：Count(x) 解析为引用 Count，参数丢失（内置函数必须带 @）
        val parsed = OpenUiParser.parse("a = Count([1,2])")
        val runtime = OpenUiRuntime(
            statements = parsed.statements.associateBy { stmt -> stmt.name },
            store = mutableMapOf(),
            queryResults = mutableMapOf(),
        )
        assertNull(runtime.evalStatement("a"))
        assertTrue("Count" in runtime.unresolved)
    }
}
