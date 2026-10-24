package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/** 命名实参 + 内联 Query 回归（2026-10-24 all_years 真机降级事故：事件 843 逐字 program 钉住）。 */
class OpenUiNamedArgsInlineQueryTest {

    @Test
    fun `exact event-843 program renders with resolved buckets`() = runTest {
        val buckets = listOf(
            OpenUiValue.Obj(mapOf("label" to OpenUiValue.Str("2026-10"), "count" to OpenUiValue.Num(86.0))),
            OpenUiValue.Obj(mapOf("label" to OpenUiValue.Str("2026-09"), "count" to OpenUiValue.Num(63.0))),
        )
        var calledWith: Map<String, OpenUiValue>? = null
        val tools = OpenUiToolResolver { name, args ->
            assertEquals("local_handle", name)
            calledWith = args
            OpenUiValue.Arr(buckets)
        }
        val program = "\$selected = \"\"\n" +
            "root = BarChart(title=\"相册各月照片数量\", " +
            "buckets=Query(\"local_handle\", { handle: \"local://scan/79581a2d11e2#histogram\" }, []), " +
            "selected=\$selected)"
        val result = OpenUiSession(program, tools).prepare()
        println("RESULT: $result")
        assertIs<OpenUiResult.Rendered>(result)
        val chart = result.root
        assertEquals("BarChart", chart.type)
        val bound = chart.props["buckets"]
        assertIs<OpenUiValue.Arr>(bound)
        assertEquals(2, bound.v.size)
        assertEquals(
            mapOf("handle" to OpenUiValue.Str("local://scan/79581a2d11e2#histogram")),
            calledWith,
        )
    }

    @Test
    fun `named and positional args mix by spec order`() = runTest {
        val session = OpenUiSession(
            "root = Card([Text(role=\"HEADLINE\", \"hi\")])",
            OpenUiToolResolver { name, _ -> throw OpenUiToolNotFoundException(name) },
        )
        val result = session.prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val card = result.root
        val text = (card.props["children"] as OpenUiValue.Arr).v.single() as OpenUiValue.ComponentVal
        assertEquals(OpenUiValue.Str("hi"), text.props["text"])
        assertEquals(OpenUiValue.Str("HEADLINE"), text.props["role"])
    }

    @Test
    fun `unknown named arg reports unknown-prop and binds rest`() = runTest {
        val session = OpenUiSession(
            "root = Text(text=\"hi\", bogus=1)",
            OpenUiToolResolver { name, _ -> throw OpenUiToolNotFoundException(name) },
        )
        val result = session.prepare()
        assertIs<OpenUiResult.Rendered>(result)
        assertEquals(OpenUiValue.Str("hi"), result.root.props["text"])
        kotlin.test.assertTrue(result.errors.any { error -> error.code == "unknown-prop" })
    }
}
