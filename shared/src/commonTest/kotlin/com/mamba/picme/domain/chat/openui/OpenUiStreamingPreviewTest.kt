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

    // ── 前向引用骨架流式（2026-11-12 组合方案吸收：top-down reveal）───────────────

    /** 深搜组件树收集全部节点（含 props 与数组内），断言 Skeleton 不出现用。 */
    private fun collectSkeletons(value: OpenUiValue, out: MutableList<OpenUiValue.Skeleton>) {
        when (value) {
            is OpenUiValue.Skeleton -> out += value
            is OpenUiValue.Arr -> value.v.forEach { collectSkeletons(it, out) }
            is OpenUiValue.Obj -> value.v.values.forEach { collectSkeletons(it, out) }
            is OpenUiValue.ComponentVal -> value.props.values.forEach { collectSkeletons(it, out) }
            else -> Unit
        }
    }

    @Test
    fun `forwardRefSkeletons mode keeps not yet arrived refs as skeleton slots`() = runTest {
        // partial 前缀：root 先流出、header/body 未到 → children 两枚 Skeleton 占位
        //（dropped 语义的流式版：槽位先立、top-down reveal，渲染层骨架块）
        val session = OpenUiSession("root = Card([header, body])", emptyResolver, forwardRefSkeletons = true)
        val result = session.prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val children = (result.root.props["children"] as OpenUiValue.Arr).v
        assertEquals(2, children.size)
        val skeletons = children.filterIsInstance<OpenUiValue.Skeleton>()
        assertEquals(listOf("header", "body"), skeletons.map { it.refName })
        // unresolved 照记（与默认模式同口径——终态完整程序校验语义不动）
        assertEquals(setOf("header", "body"), session.unresolved)
    }

    @Test
    fun `skeleton slot is replaced in place once definition arrives`() = runTest {
        // 下一帧（定义流入后）：header 已到 → Text 原位替换，body 仍为 Skeleton
        val prefix = "root = Card([header, body])\nheader = Text(\"相册总览\")"
        val session = OpenUiSession(prefix, emptyResolver, forwardRefSkeletons = true)
        val result = session.prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val children = (result.root.props["children"] as OpenUiValue.Arr).v
        assertEquals(2, children.size)
        assertIs<OpenUiValue.ComponentVal>(children[0])
        assertEquals("Text", (children[0] as OpenUiValue.ComponentVal).type)
        assertIs<OpenUiValue.Skeleton>(children[1])
        assertEquals("body", (children[1] as OpenUiValue.Skeleton).refName)
        // 全程序流入后：Skeleton 全消
        val full = OpenUiSession(program, emptyResolver, forwardRefSkeletons = true)
        val fullResult = full.prepare()
        assertIs<OpenUiResult.Rendered>(fullResult)
        val skeletons = mutableListOf<OpenUiValue.Skeleton>()
        collectSkeletons(fullResult.root, skeletons)
        assertTrue(skeletons.isEmpty(), "完整程序不应残留 Skeleton：$skeletons")
    }

    @Test
    fun `skeleton never appears in default full-program mode`() = runTest {
        // 默认模式（终态/正式卡路径）语义零变化：未解析引用照旧 dropped，
        // 产物树中永不出现 Skeleton（持久化零污染的结构性保证）
        val partial = OpenUiSession("root = Card([header, body])", emptyResolver)
        val partialResult = partial.prepare()
        assertIs<OpenUiResult.Rendered>(partialResult)
        assertTrue((partialResult.root.props["children"] as OpenUiValue.Arr).v.isEmpty())
        assertEquals(setOf("header", "body"), partial.unresolved)
        val skeletons = mutableListOf<OpenUiValue.Skeleton>()
        collectSkeletons(partialResult.root, skeletons)
        assertTrue(skeletons.isEmpty())
    }

    @Test
    fun `skeleton mode collapses placeholders at scalar consumption points`() = runTest {
        // 标量消费点收口：字符串拼接/标量 prop/state 默认值里的前向引用按 Null 消费，
        // Skeleton 只在数组/children 槽存活（瞬态节点不泄漏进运算与 state store）
        val prefix = "${'$'}sel = missing\nroot = Card([Text(\"已到:\" + tail), body])"
        val session = OpenUiSession(prefix, emptyResolver, forwardRefSkeletons = true)
        val result = session.prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val children = (result.root.props["children"] as OpenUiValue.Arr).v
        assertEquals("Text", (children[0] as OpenUiValue.ComponentVal).type)
        assertEquals("已到:", ((children[0] as OpenUiValue.ComponentVal).props["text"] as OpenUiValue.Str).v)
        assertIs<OpenUiValue.Skeleton>(children[1])
        assertEquals(OpenUiValue.Null, session.state["${'$'}sel"])
    }
}
