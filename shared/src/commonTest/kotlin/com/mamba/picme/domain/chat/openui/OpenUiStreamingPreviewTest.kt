package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * OpenUI 流式预览的引擎前提测试（openui P2，spec《2026-10-26-openui-compose-alignment-design》
 * §4 P2）：任意截断前缀 parse+prepare 不崩溃（§8.1 + parser autoClose §4 流末规则），
 * 未到位引用经 dropped 哨兵剔除产出渐进生长的部分树；空 resolver 下 Query 不取数。
 */
class OpenUiStreamingPreviewTest {

    /** 代表性程序：root 在前引用后到 + state 声明 + Query + 嵌套 children + 动作。 */
    private val program = """
        ${'$'}checked = false
        buckets = Query("local_handle", {handle: "local://scan/1"}, [])
        root = Card([header, body, footer])
        header = Text("相册总览", role = "title")
        body = Column([BarChart(buckets), CheckBox(${'$'}checked, "只看收藏")])
        footer = Button("刷新", @Set(${'$'}checked, false))
    """.trimIndent()

    private val emptyResolver = OpenUiToolResolver { _, _ -> OpenUiValue.Null }

    @Test
    fun `every prefix slice prepares without throwing`() = runTest {
        // 逐 17 字符切片覆盖各种截断位（字符串中途/括号中途/转义中途），§8.1 不得抛
        var cut = 1
        while (cut <= program.length) {
            val session = OpenUiSession(program.substring(0, cut), emptyResolver)
            session.prepare() // Failed 或 Rendered 均可，断言就是不抛异常
            cut += 17
        }
    }

    @Test
    fun `root referencing not yet arrived statements renders partial tree`() = runTest {
        // 流式中途典型态：root 已完整、子件定义未到达 → children 剔除，root 可渲染
        val session = OpenUiSession("root = Card([header, body])", emptyResolver)
        val result = session.prepare()
        assertIs<OpenUiResult.Rendered>(result)
        assertEquals("Card", result.root.type)
        val children = (result.root.props["children"] as OpenUiValue.Arr).v
        assertTrue(children.isEmpty())
    }

    @Test
    fun `partial arrival renders arrived children only`() = runTest {
        val prefix = "root = Card([header, body])\nheader = Text(\"相册总览\")"
        val session = OpenUiSession(prefix, emptyResolver)
        val result = session.prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val children = (result.root.props["children"] as OpenUiValue.Arr).v
        assertEquals(1, children.size)
        assertEquals("Text", (children.single() as OpenUiValue.ComponentVal).type)
    }

    @Test
    fun `full program renders complete tree with empty resolver defaults`() = runTest {
        val session = OpenUiSession(program, emptyResolver)
        val result = session.prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val children = (result.root.props["children"] as OpenUiValue.Arr).v
        assertEquals(3, children.size)
        // state 声明默认值就位（绑定按默认值渲染）
        assertEquals(OpenUiValue.Bool(false), session.state["${'$'}checked"])
    }
}
