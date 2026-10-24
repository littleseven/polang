package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * OpenUI 组件库 M2（2026-10-24，spec《openui-component-library-m2-design》）：
 * 七组件 → 十四组件 schema 钉住（§2 枚举 7 新组件；spec 标题「十三」/+6 系计数笔误）
 * + BINDABLE_BOOLEAN/BINDABLE_NUMBER 校验
 * （字面量/Binding/类型错各一例）+ Tabs.tabs 嵌套组件递归校验 + @Set 写 Num/Bool state。
 */
class OpenUiLibraryM2Test {

    private val noTools = OpenUiToolResolver { name, _ ->
        throw OpenUiToolNotFoundException(name)
    }

    private fun rendered(result: OpenUiResult): OpenUiResult.Rendered {
        assertIs<OpenUiResult.Rendered>(result)
        return result
    }

    private fun childrenOf(component: OpenUiValue.ComponentVal): List<OpenUiValue> =
        (component.props["children"] as OpenUiValue.Arr).v

    private fun childAt(root: OpenUiValue.ComponentVal, index: Int): OpenUiValue.ComponentVal {
        val child = childrenOf(root)[index]
        assertIs<OpenUiValue.ComponentVal>(child)
        return child
    }

    // ── 组件清单与 Icon 枚举同源钉住 ─────────────────────────────────

    @Test
    fun `library registers exactly fourteen components`() {
        // M2 实装 14 组件：既有 7 + §2 枚举 7 新组件（Icon/Divider/List/Tabs/CheckBox/
        // ChoicePicker/Slider）——spec 标题「十三」与 §5「+6→13」为计数笔误，以 §2 枚举为准
        assertEquals(
            setOf(
                "Text", "Row", "Column", "Card", "Button", "BarChart", "Image",
                "Icon", "Divider", "List", "Tabs", "CheckBox", "ChoicePicker", "Slider",
            ).also { expected -> assertEquals(14, expected.size) },
            OpenUiLibrary.components.keys,
        )
    }

    @Test
    fun `icon name enum domain is the 59 predefined a2ui catalog names`() {
        // 字面量副本三方同步（spec §5）：server RenderUiValidator.ICON_NAMES（副本源）↔
        // server RenderOpenUiValidator.ICON_NAMES ↔ 本 ENUM 域 ↔ 工具描述清单
        val expected = listOf(
            "accountCircle", "add", "arrowBack", "arrowForward", "attachFile", "calendarToday",
            "call", "camera", "check", "close", "delete", "download", "edit", "error", "event",
            "fastForward", "favorite", "favoriteOff", "folder", "help", "home", "info",
            "locationOn", "lock", "lockOpen", "mail", "menu", "moreHoriz", "moreVert",
            "notifications", "notificationsOff", "pause", "payment", "person", "phone", "photo",
            "play", "print", "refresh", "rewind", "search", "send", "settings", "share",
            "shoppingCart", "skipNext", "skipPrevious", "star", "starHalf", "starOff", "stop",
            "upload", "visibility", "visibilityOff", "volumeDown", "volumeMute", "volumeOff",
            "volumeUp", "warning",
        )
        assertEquals(59, expected.size)
        assertEquals(expected, OpenUiLibrary.iconNames)
        val nameProp = OpenUiLibrary.components.getValue("Icon").props.single { prop -> prop.name == "name" }
        assertEquals(OpenUiPropType.ENUM, nameProp.type)
        assertEquals(expected, nameProp.enumValues)
    }

    @Test
    fun `text prop upgraded to bindable string`() {
        val textProp = OpenUiLibrary.components.getValue("Text").props.single { prop -> prop.name == "text" }
        assertEquals(OpenUiPropType.BINDABLE_STRING, textProp.type)
    }

    // ── BINDABLE_BOOLEAN（CheckBox.checked） ─────────────────────────

    @Test
    fun `bindable boolean accepts literal and binding`() = runTest {
        val program = """
            ${'$'}flag = false
            root = Card([CheckBox("精选", ${'$'}flag), CheckBox("回收站", true)])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        assertEquals(OpenUiValue.Binding("\$flag"), childAt(result.root, 0).props["checked"])
        assertEquals(OpenUiValue.Bool(true), childAt(result.root, 1).props["checked"])
    }

    @Test
    fun `bindable boolean rejects wrong type and drops the component`() = runTest {
        val result = rendered(
            OpenUiSession("""root = Card([CheckBox("精选", "yes"), Text("ok")])""", noTools).prepare(),
        )
        assertEquals(1, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── BINDABLE_NUMBER（Slider.value） ──────────────────────────────

    @Test
    fun `bindable number accepts literal and binding with min max defaults`() = runTest {
        val program = """
            ${'$'}level = 50
            root = Card([Slider(value = ${'$'}level), Slider(value = 30, min = 10, max = 90)])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        val bound = childAt(result.root, 0)
        assertEquals(OpenUiValue.Binding("\$level"), bound.props["value"])
        assertEquals(OpenUiValue.Num(0.0), bound.props["min"])
        assertEquals(OpenUiValue.Num(100.0), bound.props["max"])
        val literal = childAt(result.root, 1)
        assertEquals(OpenUiValue.Num(30.0), literal.props["value"])
        assertEquals(OpenUiValue.Num(10.0), literal.props["min"])
        assertEquals(OpenUiValue.Num(90.0), literal.props["max"])
    }

    @Test
    fun `bindable number rejects wrong type and drops the component`() = runTest {
        val result = rendered(
            OpenUiSession("""root = Card([Slider(value = "high"), Text("ok")])""", noTools).prepare(),
        )
        assertEquals(1, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── Text BINDABLE_STRING ─────────────────────────────────────────

    @Test
    fun `text accepts literal and binding`() = runTest {
        val program = """
            ${'$'}selected = ""
            root = Card([Text("静态文案"), Text(${'$'}selected, "CAPTION")])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        assertEquals(OpenUiValue.Str("静态文案"), childAt(result.root, 0).props["text"])
        assertEquals(OpenUiValue.Binding("\$selected"), childAt(result.root, 1).props["text"])
    }

    // ── Tabs.tabs 嵌套组件递归校验 ───────────────────────────────────

    @Test
    fun `tabs validate nested component arrays in object fields`() = runTest {
        val program = """
            root = Card([Tabs([{title: "照片", children: [Text("一页"), Icon("photo")]}, {title: "统计", children: []}])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        val tabs = childAt(result.root, 0)
        assertEquals("Tabs", tabs.type)
        val tabList = tabs.props["tabs"]
        assertIs<OpenUiValue.Arr>(tabList)
        assertEquals(2, tabList.v.size)
        val first = tabList.v[0]
        assertIs<OpenUiValue.Obj>(first)
        assertEquals(OpenUiValue.Str("照片"), first.v["title"])
        val children = first.v["children"]
        assertIs<OpenUiValue.Arr>(children)
        assertEquals(2, children.v.size)
        assertIs<OpenUiValue.ComponentVal>(children.v[0])
    }

    @Test
    fun `tabs elements with non component children are left out`() = runTest {
        // children 字段不是组件数组 → 该 tab 元素形态不符剔除（OBJECT_ARRAY 口径），其余保留
        val program = """
            root = Card([Tabs([{title: "坏", children: 5}, {title: "好", children: [Text("x")]}])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        val tabs = childAt(result.root, 0)
        val tabList = tabs.props["tabs"]
        assertIs<OpenUiValue.Arr>(tabList)
        assertEquals(1, tabList.v.size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── @Set 泛化：写 Num/Bool state ─────────────────────────────────

    @Test
    fun `set action writes bool and num state and re-evaluates bindings`() = runTest {
        val program = """
            ${'$'}flag = false
            ${'$'}level = 50
            root = Card([CheckBox("精选", ${'$'}flag), Slider(value = ${'$'}level), Text(${'$'}flag ? "开" : "关")])
        """.trimIndent()
        val session = OpenUiSession(program, noTools)
        rendered(session.prepare())

        session.applyAction(OpenUiAction("Set", listOf(OpenUiValue.Bool(true)), target = "\$flag"))
        session.applyAction(OpenUiAction("Set", listOf(OpenUiValue.Num(80.0)), target = "\$level"))

        assertEquals(OpenUiValue.Bool(true), session.state["\$flag"])
        assertEquals(OpenUiValue.Num(80.0), session.state["\$level"])
        // 重求值后绑定属性仍是 Binding（渲染期解析），派生表达式已按新 state 求值
        val root = session.rootOrNull()!!
        assertEquals(OpenUiValue.Binding("\$flag"), childAt(root, 0).props["checked"])
        assertEquals(OpenUiValue.Binding("\$level"), childAt(root, 1).props["value"])
        assertEquals(OpenUiValue.Str("开"), childAt(root, 2).props["text"])
    }
}
