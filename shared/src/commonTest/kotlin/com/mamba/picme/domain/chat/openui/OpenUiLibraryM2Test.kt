package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * OpenUI 组件库 M2（2026-10-24，spec《openui-component-library-m2-design》）：
 * 七组件 → 十四组件 schema 钉住（§2 枚举 7 新组件；spec 标题「十三」/+6 系计数笔误）
 * + BINDABLE_BOOLEAN/BINDABLE_NUMBER 校验
 * （字面量/Binding/类型错各一例）+ Tabs.tabs 嵌套组件递归校验 + @Set 写 Num/Bool state。
 *
 * M3（2026-10-24，spec《openui-component-library-m3-design》）：十四 → 二十一组件
 * （+Table/PieChart/LineChart/Callout/Markdown/Accordion/Switch）+ PRIMITIVE_ARRAY 校验
 * （Str/Num/Bool/一层嵌套 Arr；两层嵌套拒、元素类型混杂 → 必填 type-mismatch 丢组件）+
 * LineChart.series[].values OBJECT_ARRAY 嵌套 PRIMITIVE_ARRAY 递归。
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
    fun `library registers the full official component set`() {
        // 2026-10-25 全量补全（spec《openui-full-library-design》）：官方 openuiLibrary
        // 82 组件覆盖 81（Col 列式 Table 刻意不收）+ 本仓既有 8 项（Row/Column/Divider/
        // List/CheckBox/ChoicePicker/Switch/Markdown）= 89 注册项
        assertEquals(
            setOf(
                // 既有 21（M1~M3）
                "Text", "Row", "Column", "Card", "Button", "BarChart", "Image",
                "Icon", "Divider", "List", "Tabs", "CheckBox", "ChoicePicker", "Slider",
                "Table", "PieChart", "LineChart", "Callout", "Markdown", "Accordion", "Switch",
                // 别名 5
                "Stack", "Separator", "MarkDownRenderer", "TabItem", "AccordionItem",
                // 内容/布局 23
                "CardHeader", "InlineHeader", "TextContent", "TextCallout", "CodeBlock",
                "ImageBlock", "ImageGallery", "Tag", "TagBlock", "Label", "BoldText",
                "IconText", "ImageText", "ImageTextLarge", "EntityList", "ListBlock",
                "ListItem", "Steps", "StepsItem", "Carousel", "Buttons", "IconButton", "Modal",
                // 表单 18
                "Form", "FormControl", "Input", "TextArea", "Select", "SelectItem",
                "DatePicker", "CheckBoxGroup", "CheckBoxItem", "RadioGroup", "RadioItem",
                "SwitchGroup", "SwitchItem", "Chips", "ChipItem", "OptionCards", "OptionCard",
                "EditableTable",
                // 图表 10
                "AreaChart", "HorizontalBarChart", "RadarChart", "RadialChart",
                "SingleStackedBarChart", "ScatterChart", "Series", "Slice", "ScatterSeries",
                "Point",
                // 卡块 12
                "MetricIndicatorInline", "MetricIndicatorWithStrikethrough",
                "SnippetCardBlock", "SnippetCardItem", "OverviewCardBlock", "OverviewCardItem",
                "ContextCardBlock", "ContextCardItem", "CompositeCardBlock", "CompositeCardItem",
                "VisualCardBlock", "VisualCardItem",
            ).also { expected -> assertEquals(89, expected.size) },
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

    // ── Text DISPLAY role（2026-10-25，设计规范 §2.1 统计大数字） ──────

    @Test
    fun `text role enum includes display and program validates`() = runTest {
        val roleProp = OpenUiLibrary.components.getValue("Text").props.single { prop -> prop.name == "role" }
        assertEquals(listOf("HEADLINE", "SUBHEAD", "BODY", "CAPTION", "DISPLAY"), roleProp.enumValues)
        val program = """
            root = Card([Text("7,120", "DISPLAY"), Text("照片总数", "CAPTION")])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        assertEquals(OpenUiValue.Str("DISPLAY"), childAt(result.root, 0).props["role"])
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

    // ── M3 七组件（2026-10-24，spec《openui-component-library-m3-design》） ──

    @Test
    fun `m3 component signatures are pinned verbatim`() {
        // 位置参数序 = 声明序（spec §3 逐字为契）：逐组件钉住 (prop 名, 类型, 必填)
        fun signature(component: String): List<Triple<String, OpenUiPropType, Boolean>> =
            OpenUiLibrary.components.getValue(component).props.map { prop ->
                Triple(prop.name, prop.type, prop.required)
            }
        assertEquals(
            listOf(
                Triple("columns", OpenUiPropType.OBJECT_ARRAY, true),
                Triple("rows", OpenUiPropType.PRIMITIVE_ARRAY, true),
            ),
            signature("Table"),
        )
        assertEquals(
            listOf(
                Triple("title", OpenUiPropType.STRING, false),
                Triple("slices", OpenUiPropType.OBJECT_ARRAY, true),
                Triple("variant", OpenUiPropType.ENUM, false),
            ),
            signature("PieChart"),
        )
        assertEquals(
            listOf(
                Triple("title", OpenUiPropType.STRING, false),
                Triple("labels", OpenUiPropType.PRIMITIVE_ARRAY, true),
                Triple("series", OpenUiPropType.OBJECT_ARRAY, true),
                Triple("variant", OpenUiPropType.ENUM, false),
            ),
            signature("LineChart"),
        )
        assertEquals(
            listOf(
                Triple("text", OpenUiPropType.STRING, true),
                Triple("title", OpenUiPropType.STRING, false),
                Triple("tone", OpenUiPropType.ENUM, false),
            ),
            signature("Callout"),
        )
        assertEquals(
            listOf(Triple("text", OpenUiPropType.BINDABLE_STRING, true)),
            signature("Markdown"),
        )
        assertEquals(
            listOf(Triple("items", OpenUiPropType.OBJECT_ARRAY, true)),
            signature("Accordion"),
        )
        assertEquals(
            listOf(
                Triple("label", OpenUiPropType.STRING, true),
                Triple("checked", OpenUiPropType.BINDABLE_BOOLEAN, true),
            ),
            signature("Switch"),
        )
        // Callout tone 枚举域与默认值（2026-10-25 全量补全：+NEUTRAL 对齐官方五档）
        val tone = OpenUiLibrary.components.getValue("Callout").props.single { prop -> prop.name == "tone" }
        assertEquals(listOf("INFO", "SUCCESS", "WARNING", "ERROR", "NEUTRAL"), tone.enumValues)
        assertEquals(OpenUiValue.Str("INFO"), tone.default)
        // LineChart.series[].values = OBJECT_ARRAY 嵌套 PRIMITIVE_ARRAY（spec §3）
        val seriesValues = OpenUiLibrary.components.getValue("LineChart").props
            .single { prop -> prop.name == "series" }.objectFields
            .single { field -> field.name == "values" }
        assertEquals(OpenUiPropType.PRIMITIVE_ARRAY, seriesValues.type)
        assertTrue(seriesValues.required)
        // Accordion.items[].children = OBJECT_ARRAY 嵌套 COMPONENT_LIST（Tabs 同构）
        val itemChildren = OpenUiLibrary.components.getValue("Accordion").props
            .single { prop -> prop.name == "items" }.objectFields
            .single { field -> field.name == "children" }
        assertEquals(OpenUiPropType.COMPONENT_LIST, itemChildren.type)
    }

    // ── Table（PRIMITIVE_ARRAY 一层嵌套行） ─────────────────────────────

    @Test
    fun `table accepts column objects and nested primitive rows`() = runTest {
        val program = """
            root = Card([Table([{label: "月份"}, {label: "数量"}], [["2026-01", 12], ["2026-02", true]])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        val table = childAt(result.root, 0)
        assertEquals("Table", table.type)
        val rows = table.props["rows"]
        assertIs<OpenUiValue.Arr>(rows)
        assertEquals(2, rows.v.size)
        val firstRow = rows.v[0]
        assertIs<OpenUiValue.Arr>(firstRow)
        assertEquals(OpenUiValue.Str("2026-01"), firstRow.v[0])
        assertEquals(OpenUiValue.Num(12.0), firstRow.v[1])
    }

    @Test
    fun `table rows with two level nesting drop the component`() = runTest {
        // PRIMITIVE_ARRAY 只许一层嵌套：[[["a"]]] 两层 → type-mismatch 丢组件不炸卡（spec §2）
        val result = rendered(
            OpenUiSession(
                """root = Card([Table([{label: "m"}], [[["a"]]]), Text("ok")])""",
                noTools,
            ).prepare(),
        )
        assertEquals(1, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    @Test
    fun `table rows with mixed element types drop the component`() = runTest {
        // 元素类型混杂（行内混入对象）按既有口径：必填 prop type-mismatch → 丢组件
        val result = rendered(
            OpenUiSession(
                """root = Card([Table([{label: "m"}], [[{label: "x"}]]), Text("ok")])""",
                noTools,
            ).prepare(),
        )
        assertEquals(1, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    @Test
    fun `table rows non array drops the component`() = runTest {
        val result = rendered(
            OpenUiSession("""root = Card([Table([{label: "m"}], "nope"), Text("ok")])""", noTools).prepare(),
        )
        assertEquals(1, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── PieChart ─────────────────────────────────────────────────────

    @Test
    fun `pie chart accepts label value slices and optional title`() = runTest {
        val program = """
            root = Card([PieChart("类目占比", [{label: "人像", value: 42}, {label: "风景", value: 18}])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        val pie = childAt(result.root, 0)
        assertEquals("PieChart", pie.type)
        val slices = pie.props["slices"]
        assertIs<OpenUiValue.Arr>(slices)
        val first = slices.v[0]
        assertIs<OpenUiValue.Obj>(first)
        assertEquals(OpenUiValue.Str("人像"), first.v["label"])
        assertEquals(OpenUiValue.Num(42.0), first.v["value"])
    }

    @Test
    fun `pie chart slice missing value is left out`() = runTest {
        // OBJECT_ARRAY 既有口径：形态不符元素剔除，其余保留
        val program = """
            root = Card([PieChart("占比", [{label: "人像", value: 42}, {label: "风景"}])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        val slices = childAt(result.root, 0).props["slices"]
        assertIs<OpenUiValue.Arr>(slices)
        assertEquals(1, slices.v.size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── LineChart（OBJECT_ARRAY 嵌套 PRIMITIVE_ARRAY 递归） ─────────────

    @Test
    fun `line chart accepts labels and series with nested value arrays`() = runTest {
        val program = """
            root = Card([LineChart("拍摄趋势", ["2026-01", "2026-02"], [{name: "全部", values: [12, 20]}, {name: "人像", values: [5, 9]}])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        val chart = childAt(result.root, 0)
        assertEquals("LineChart", chart.type)
        val labels = chart.props["labels"]
        assertIs<OpenUiValue.Arr>(labels)
        assertEquals(OpenUiValue.Str("2026-01"), labels.v[0])
        val series = chart.props["series"]
        assertIs<OpenUiValue.Arr>(series)
        val first = series.v[0]
        assertIs<OpenUiValue.Obj>(first)
        val values = first.v["values"]
        assertIs<OpenUiValue.Arr>(values)
        assertEquals(OpenUiValue.Num(12.0), values.v[0])
    }

    @Test
    fun `line chart series with non primitive values element is left out`() = runTest {
        // 嵌套 PRIMITIVE_ARRAY 元素形态不符 → 该 series 元素剔除（OBJECT_ARRAY 口径），其余保留
        val program = """
            root = Card([LineChart("趋势", ["a", "b"], [{name: "好", values: [1, 2]}, {name: "坏", values: [{label: "x"}]}])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        val series = childAt(result.root, 0).props["series"]
        assertIs<OpenUiValue.Arr>(series)
        assertEquals(1, series.v.size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── Callout ──────────────────────────────────────────────────────

    @Test
    fun `callout defaults tone to info and accepts explicit tone`() = runTest {
        val program = """
            root = Card([Callout("默认提示"), Callout("告警", "注意", "WARNING")])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        assertEquals(OpenUiValue.Str("INFO"), childAt(result.root, 0).props["tone"])
        val explicit = childAt(result.root, 1)
        assertEquals(OpenUiValue.Str("注意"), explicit.props["title"])
        assertEquals(OpenUiValue.Str("WARNING"), explicit.props["tone"])
    }

    @Test
    fun `callout invalid tone falls back to default`() = runTest {
        // ENUM 带默认值的既有口径：非法值 type-mismatch → 落 schema 默认（§8.2）
        val result = rendered(
            OpenUiSession("""root = Card([Callout("提示", "t", "FATAL")])""", noTools).prepare(),
        )
        assertEquals(OpenUiValue.Str("INFO"), childAt(result.root, 0).props["tone"])
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── Markdown（BINDABLE_STRING，同 Text 口径） ─────────────────────

    @Test
    fun `markdown accepts literal and binding`() = runTest {
        val program = """
            ${'$'}body = "**结论**"
            root = Card([Markdown("# 标题\n- 甲"), Markdown(${'$'}body)])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        assertEquals(OpenUiValue.Str("# 标题\n- 甲"), childAt(result.root, 0).props["text"])
        assertEquals(OpenUiValue.Binding("\$body"), childAt(result.root, 1).props["text"])
    }

    // ── Accordion（OBJECT_ARRAY 嵌套 COMPONENT_LIST，Tabs 同构） ─────────

    @Test
    fun `accordion validates nested component arrays in item fields`() = runTest {
        val program = """
            root = Card([Accordion([{title: "明细", children: [Text("一月 12 张"), Icon("photo")]}, {title: "空区", children: []}])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        val accordion = childAt(result.root, 0)
        assertEquals("Accordion", accordion.type)
        val items = accordion.props["items"]
        assertIs<OpenUiValue.Arr>(items)
        assertEquals(2, items.v.size)
        val first = items.v[0]
        assertIs<OpenUiValue.Obj>(first)
        assertEquals(OpenUiValue.Str("明细"), first.v["title"])
        val children = first.v["children"]
        assertIs<OpenUiValue.Arr>(children)
        assertIs<OpenUiValue.ComponentVal>(children.v[0])
    }

    @Test
    fun `accordion items with non component children are left out`() = runTest {
        // children 字段不是组件数组 → 该 item 元素形态不符剔除（Tabs M2 同口径），其余保留
        val program = """
            root = Card([Accordion([{title: "坏", children: 5}, {title: "好", children: [Text("x")]}])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        val items = childAt(result.root, 0).props["items"]
        assertIs<OpenUiValue.Arr>(items)
        assertEquals(1, items.v.size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── Switch（BINDABLE_BOOLEAN，CheckBox 同构） ──────────────────────

    @Test
    fun `switch accepts binding and literal`() = runTest {
        val program = """
            ${'$'}enabled = false
            root = Card([Switch("通知", ${'$'}enabled), Switch("锁定", true)])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        assertEquals(OpenUiValue.Binding("\$enabled"), childAt(result.root, 0).props["checked"])
        assertEquals(OpenUiValue.Bool(true), childAt(result.root, 1).props["checked"])
    }

    @Test
    fun `switch rejects wrong checked type and drops the component`() = runTest {
        val result = rendered(
            OpenUiSession("""root = Card([Switch("通知", "yes"), Text("ok")])""", noTools).prepare(),
        )
        assertEquals(1, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── Row/Column/Card align prop（2026-10-30 align 结构治理） ──────

    @Test
    fun `container align prop registered with official stack vocabulary and stretch default`() {
        val aligns = listOf("start", "center", "end", "stretch", "baseline")
        val rowAlign = OpenUiLibrary.components.getValue("Row").props.single { prop -> prop.name == "align" }
        assertEquals(aligns, rowAlign.enumValues)
        // 缺省 = 官方 CSS align-items 缺省 stretch（thesysdev/openui Stack schema 口径）
        assertEquals(OpenUiValue.Str("stretch"), rowAlign.default)
        val columnAlign = OpenUiLibrary.components.getValue("Column").props.single { prop -> prop.name == "align" }
        assertEquals(aligns, columnAlign.enumValues)
        assertEquals(OpenUiValue.Str("stretch"), columnAlign.default)
        val cardAlign = OpenUiLibrary.components.getValue("Card").props.single { prop -> prop.name == "align" }
        assertEquals(aligns, cardAlign.enumValues)
        assertEquals(OpenUiValue.Str("stretch"), cardAlign.default)
        // 官方 Stack 本体同口径（2026-10-30 由 start 修正）
        val stackAlign = OpenUiLibrary.components.getValue("Stack").props.single { prop -> prop.name == "align" }
        assertEquals(aligns, stackAlign.enumValues)
        assertEquals(OpenUiValue.Str("stretch"), stackAlign.default)
    }

    @Test
    fun `stat tile program with center aligned columns validates`() = runTest {
        val program = """
            root = Card([Text("相册年度统计", "HEADLINE"), Row([Column([Text("7,120", "DISPLAY"), Text("照片总数", "CAPTION")], align = "center"), Column([Text("771", "DISPLAY"), Text("视频总数", "CAPTION")], align = "center")])])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(emptyList(), result.errors)
        val row = childAt(result.root, 1)
        val firstLane = row.props["children"]
        assertIs<OpenUiValue.Arr>(firstLane)
        val lane = firstLane.v[0]
        assertIs<OpenUiValue.ComponentVal>(lane)
        assertEquals(OpenUiValue.Str("center"), lane.props["align"])
    }

    @Test
    fun `unknown align value falls back to default with error recorded`() = runTest {
        // typeMismatch 三分支（OpenUiEval）：有 default → 存默认值（不丢组件、不炸卡）
        val result = rendered(
            OpenUiSession(
                """root = Card([Column([Text("x")], align = "middle"), Text("ok")])""",
                noTools,
            ).prepare(),
        )
        assertEquals(2, childrenOf(result.root).size)
        assertEquals(OpenUiValue.Str("stretch"), childAt(result.root, 0).props["align"])
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── ImageText/ImageTextLarge photo prop（2026-10-30 图文组合固化） ──────

    @Test
    fun `image text composites register optional photo number prop`() {
        val photoProp = OpenUiLibrary.components.getValue("ImageText").props
            .single { prop -> prop.name == "photo" }
        assertEquals(OpenUiPropType.NUMBER, photoProp.type)
        assertFalse(photoProp.required)
        val largePhotoProp = OpenUiLibrary.components.getValue("ImageTextLarge").props
            .single { prop -> prop.name == "photo" }
        assertEquals(OpenUiPropType.NUMBER, largePhotoProp.type)
        assertFalse(largePhotoProp.required)
    }

    @Test
    fun `image text composites evaluate photo id from projection element`() = runTest {
        // mock local_handle 返回 #photos 投影形态 [{id, date}]（同 Image 投影用例口径）
        val photos = OpenUiValue.Arr(
            listOf(
                OpenUiValue.Obj(
                    mapOf(
                        "id" to OpenUiValue.Num(101.0),
                        "date" to OpenUiValue.Str("2026-07-18"),
                    ),
                ),
                OpenUiValue.Obj(
                    mapOf(
                        "id" to OpenUiValue.Num(102.0),
                        "date" to OpenUiValue.Str("2026-07-19"),
                    ),
                ),
            ),
        )
        val tools = OpenUiToolResolver { name, _ ->
            if (name == "local_handle") photos else throw OpenUiToolNotFoundException(name)
        }
        val program = """
            photos = Query("local_handle", { handle: "local://scan/7f3a#photos" }, [])
            root = Card([ImageTextLarge("海边日落", photo = photos[0].id, subtitle = "2026-07-18"), ImageText("山顶云海", photo = photos[1].id)])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, tools).prepare())
        assertEquals(emptyList(), result.errors)
        val large = childrenOf(result.root)[0]
        assertIs<OpenUiValue.ComponentVal>(large)
        assertEquals("ImageTextLarge", large.type)
        assertEquals(OpenUiValue.Num(101.0), large.props["photo"])
        val small = childrenOf(result.root)[1]
        assertIs<OpenUiValue.ComponentVal>(small)
        assertEquals("ImageText", small.type)
        assertEquals(OpenUiValue.Num(102.0), small.props["photo"])
    }
}
