package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [OpenUiRuntime] 求值语义测试（OpenUI Lang 1.0 §3）：
 * toNumber 强转 / JS 真值 / 宽松相等 / pluck 与 .length / 内置函数全项 /
 * 引用提升 / 环检测 / 未解析引用。
 */
class OpenUiEvalTest {

    private class EvalOutcome(
        val value: OpenUiValue?,
        val errors: List<OpenUiError>,
        val unresolved: Set<String>,
    )

    private fun evaluate(
        program: String,
        name: String = "x",
        store: Map<String, OpenUiValue> = emptyMap(),
    ): EvalOutcome {
        val parsed = OpenUiParser.parse(program)
        val runtime = OpenUiRuntime(
            statements = linkedMapOf<String, OpenUiStatement>()
                .also { map -> parsed.statements.forEach { stmt -> map[stmt.name] = stmt } },
            store = store.toMutableMap(),
            queryResults = mutableMapOf(),
        )
        val value = runtime.evalStatement(name)
        return EvalOutcome(value, runtime.errors.toList(), runtime.unresolved.toSet())
    }

    private fun eval(program: String, name: String = "x"): OpenUiValue? = evaluate(program, name).value

    // ── toNumber 强转与算术（§3.1/§3.2） ────────────────────────────

    @Test
    fun `toNumber coerces strings booleans and junk`() {
        assertEquals(OpenUiValue.Num(10.0), eval("""x = "5" * 2"""))
        assertEquals(OpenUiValue.Num(-1.0), eval("""x = "abc" - 1""")) // 解析失败 = 0
        assertEquals(OpenUiValue.Num(2.0), eval("x = true + 1"))
        assertEquals(OpenUiValue.Num(5.0), eval("x = null + 5"))
    }

    @Test
    fun `plus concatenates when either side is string and null becomes empty`() {
        assertEquals(OpenUiValue.Str("101"), eval("""x = "10" + 1"""))
        assertEquals(OpenUiValue.Str("a"), eval("""x = "a" + null"""))
        assertEquals(OpenUiValue.Num(3.0), eval("x = 1 + 2"))
    }

    @Test
    fun `division and modulo by zero give zero`() {
        assertEquals(OpenUiValue.Num(0.0), eval("x = 1 / 0"))
        assertEquals(OpenUiValue.Num(0.0), eval("x = 1 % 0"))
        assertEquals(OpenUiValue.Num(2.5), eval("x = 5 / 2"))
    }

    // ── 宽松相等（§3.2 IsLooselyEqual） ──────────────────────────────

    @Test
    fun `loose equality follows ECMA semantics`() {
        assertEquals(OpenUiValue.Bool(true), eval("""x = 5 == "5" """))
        assertEquals(OpenUiValue.Bool(true), eval("x = 0 == false"))
        assertEquals(OpenUiValue.Bool(false), eval("x = null == 0"))
        assertEquals(OpenUiValue.Bool(true), eval("x = null == null"))
        assertEquals(OpenUiValue.Bool(true), eval("""x = 1 != "2" """))
        assertEquals(OpenUiValue.Bool(false), eval("""x = "a" == "b" """))
    }

    // ── 真值与逻辑/三元（§3.1/§3.2） ─────────────────────────────────

    @Test
    fun `truthiness follows JavaScript ToBoolean`() {
        assertEquals(OpenUiValue.Num(1.0), eval("x = [] ? 1 : 2")) // 空数组为真
        assertEquals(OpenUiValue.Num(1.0), eval("x = {} ? 1 : 2")) // 空对象为真
        assertEquals(OpenUiValue.Num(2.0), eval("""x = "" ? 1 : 2"""))
        assertEquals(OpenUiValue.Num(2.0), eval("x = 0 ? 1 : 2"))
        assertEquals(OpenUiValue.Num(2.0), eval("x = false ? 1 : 2"))
        assertEquals(OpenUiValue.Num(2.0), eval("x = null ? 1 : 2"))
    }

    @Test
    fun `logical operators short circuit and return the deciding operand`() {
        assertEquals(OpenUiValue.Str("d"), eval("""x = 0 || "d" """))
        assertEquals(OpenUiValue.Num(2.0), eval("x = 1 && 2"))
        assertEquals(OpenUiValue.Str(""), eval("""x = "" && 2"""))
        assertEquals(OpenUiValue.Bool(false), eval("x = !1"))
        assertEquals(OpenUiValue.Bool(false), eval("x = !!0 || false"))
    }

    // ── 成员访问与 pluck（§3.3） ─────────────────────────────────────

    @Test
    fun `member access on array plucks field from every element`() {
        val program = """
            users = [{name: "a", age: 1}, {name: "b"}]
            names = users.name
            ages = users.age
        """.trimIndent()
        assertEquals(
            OpenUiValue.Arr(listOf(OpenUiValue.Str("a"), OpenUiValue.Str("b"))),
            eval(program, "names"),
        )
        // 缺失字段处为 null
        assertEquals(
            OpenUiValue.Arr(listOf(OpenUiValue.Num(1.0), OpenUiValue.Null)),
            eval(program, "ages"),
        )
    }

    @Test
    fun `length gives array element count and numeric field plucks inner arrays`() {
        val program = """
            users = [{name: "a"}, {name: "b"}]
            n = users.length
            m = [[1, 2], [3, 4]]
            firsts = m.0
        """.trimIndent()
        assertEquals(OpenUiValue.Num(2.0), eval(program, "n"))
        assertEquals(
            OpenUiValue.Arr(listOf(OpenUiValue.Num(1.0), OpenUiValue.Num(3.0))),
            eval(program, "firsts"),
        )
    }

    @Test
    fun `member access on null or scalar gives null`() {
        assertEquals(OpenUiValue.Null, eval("x = nothing.field"))
        assertEquals(OpenUiValue.Null, eval("x = 5.field"))
    }

    @Test
    fun `index reads arrays by number and objects by string key`() {
        val program = """
            users = [{name: "a"}, {name: "b"}]
            first = users[0].name
            oob = users[9]
            o = {k: 7}
            byKey = o["k"]
            byNull = o[null]
        """.trimIndent()
        assertEquals(OpenUiValue.Str("a"), eval(program, "first"))
        assertEquals(OpenUiValue.Null, eval(program, "oob"))
        assertEquals(OpenUiValue.Num(7.0), eval(program, "byKey"))
        assertEquals(OpenUiValue.Null, eval(program, "byNull"))
    }

    // ── 内置函数全项（§3.4） ─────────────────────────────────────────

    @Test
    fun `count first last handle arrays and non arrays`() {
        assertEquals(OpenUiValue.Num(3.0), eval("x = @Count([1, 2, 3])"))
        assertEquals(OpenUiValue.Num(0.0), eval("""x = @Count("nope")"""))
        assertEquals(OpenUiValue.Num(1.0), eval("x = @First([1, 2])"))
        assertEquals(OpenUiValue.Num(2.0), eval("x = @Last([1, 2])"))
        assertEquals(OpenUiValue.Null, eval("x = @First([])"))
        assertEquals(OpenUiValue.Null, eval("""x = @Last("nope")"""))
    }

    @Test
    fun `sum avg min max use toNumber`() {
        assertEquals(OpenUiValue.Num(4.0), eval("""x = @Sum([1, "2", true])"""))
        assertEquals(OpenUiValue.Num(3.0), eval("x = @Avg([2, 4])"))
        assertEquals(OpenUiValue.Num(0.0), eval("x = @Avg([])"))
        assertEquals(OpenUiValue.Num(1.0), eval("x = @Min([3, 1, 2])"))
        assertEquals(OpenUiValue.Num(3.0), eval("x = @Max([3, 1, 2])"))
        assertEquals(OpenUiValue.Num(0.0), eval("x = @Min([])"))
    }

    @Test
    fun `filter supports all ops dot paths and empty field`() {
        val program = """
            items = [{n: "a", v: 1}, {n: "b", v: 2}]
            big = @Filter(items, "v", ">", 1)
            eqN = @Filter(items, "n", "==", "a")
            neq = @Filter([1, 2], "", "!=", 1)
            words = @Filter(["apple", "banana"], "", "contains", "app")
            unknownOp = @Filter([1, 2], "", "~~", 1)
            notArray = @Filter("x", "", "==", 1)
        """.trimIndent()
        assertEquals(
            OpenUiValue.Arr(
                listOf(OpenUiValue.Obj(mapOf("n" to OpenUiValue.Str("b"), "v" to OpenUiValue.Num(2.0)))),
            ),
            eval(program, "big"),
        )
        assertEquals(
            OpenUiValue.Arr(
                listOf(OpenUiValue.Obj(mapOf("n" to OpenUiValue.Str("a"), "v" to OpenUiValue.Num(1.0)))),
            ),
            eval(program, "eqN"),
        )
        assertEquals(OpenUiValue.Arr(listOf(OpenUiValue.Num(2.0))), eval(program, "neq"))
        assertEquals(OpenUiValue.Arr(listOf(OpenUiValue.Str("apple"))), eval(program, "words"))
        assertEquals(OpenUiValue.Arr(emptyList()), eval(program, "unknownOp"))
        assertEquals(OpenUiValue.Arr(emptyList()), eval(program, "notArray"))
    }

    @Test
    fun `filter defaults missing op to loose equality`() {
        // op 缺省 = "=="、value 缺省 = null：仅 null 元素命中（looseEquals(x, null)）
        assertEquals(
            OpenUiValue.Arr(listOf(OpenUiValue.Null)),
            eval("""x = @Filter([1, null, 2], "")"""),
        )
    }

    @Test
    fun `sort is stable numeric when parseable and descending only on desc`() {
        assertEquals(
            OpenUiValue.Arr(listOf(OpenUiValue.Str("1"), OpenUiValue.Str("9"), OpenUiValue.Str("10"))),
            eval("""x = @Sort(["10", "9", "1"], "")"""),
        )
        assertEquals(
            OpenUiValue.Arr(listOf(OpenUiValue.Num(3.0), OpenUiValue.Num(2.0), OpenUiValue.Num(1.0))),
            eval("""x = @Sort([1, 3, 2], "", "desc")"""),
        )
        assertEquals(
            OpenUiValue.Arr(listOf(OpenUiValue.Str("apple"), OpenUiValue.Str("banana"))),
            eval("""x = @Sort(["banana", "apple"], "")"""),
        )
        // 非数组原样返回
        assertEquals(OpenUiValue.Str("x"), eval("""x = @Sort("x", "")"""))
    }

    @Test
    fun `round abs floor ceil follow JavaScript math`() {
        assertEquals(OpenUiValue.Num(3.0), eval("x = @Round(2.5)")) // 半数进位（向 +∞）
        assertEquals(OpenUiValue.Num(-2.0), eval("x = @Round(-2.5)"))
        assertEquals(OpenUiValue.Num(3.14), eval("x = @Round(3.14159, 2)"))
        assertEquals(OpenUiValue.Num(2.0), eval("x = @Abs(-2)"))
        assertEquals(OpenUiValue.Num(2.0), eval("x = @Floor(2.9)"))
        assertEquals(OpenUiValue.Num(3.0), eval("x = @Ceil(2.1)"))
    }

    @Test
    fun `unknown at-call evaluates to null and reports unknown-function`() {
        val outcome = evaluate("x = @Foo(1)")
        assertEquals(OpenUiValue.Null, outcome.value)
        assertTrue(outcome.errors.any { error -> error.code == "unknown-function" })
    }

    // ── 引用提升 / 环 / 未解析（§2.3） ───────────────────────────────

    @Test
    fun `references hoist regardless of statement order`() {
        assertEquals(OpenUiValue.Num(2.0), eval("a = b\nb = 2", "a"))
    }

    @Test
    fun `cycle closes as unresolved at the cycle point`() {
        val outcome = evaluate("a = b\nb = a", "a")
        assertNull(outcome.value)
        assertTrue(outcome.unresolved.isNotEmpty())
    }

    @Test
    fun `unresolved reference is null in expression and dropped from arrays`() {
        val outcome = evaluate("x = missing")
        assertNull(outcome.value)
        assertTrue("missing" in outcome.unresolved)

        assertEquals(
            OpenUiValue.Arr(listOf(OpenUiValue.Num(1.0), OpenUiValue.Num(3.0))),
            eval("x = [1, missing, 3]"),
        )
    }

    @Test
    fun `duplicate names let the later statement win`() {
        assertEquals(OpenUiValue.Num(2.0), eval("a = 1\na = 2", "a"))
    }

    @Test
    fun `state variables read the store`() {
        val outcome = evaluate(
            "x = ${'$'}count + 1",
            store = mapOf("\$count" to OpenUiValue.Num(4.0)),
        )
        assertEquals(OpenUiValue.Num(5.0), outcome.value)
    }
}
