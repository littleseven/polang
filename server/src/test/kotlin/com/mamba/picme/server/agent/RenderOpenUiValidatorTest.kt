package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * render_ui_openui 负载校验器测试（review 🟡4，照 RenderUiValidatorTest 先例）：
 * 每个违规类一例 + 合法夹具放行 + gate 按 toolName 分发。
 */
class RenderOpenUiValidatorTest {

    /** 最小合法程序：root + Text，无数据绑定。 */
    private val validMinimal = """
        root = Card([Text("你好")])
    """.trimIndent()

    /** 合法数据卡：Query local_handle + $selected 约定。 */
    private val validWithQuery = """
        ${'$'}selected = ""
        root = Card([Text("月度照片"), chart])
        chart = BarChart("月桶", buckets, ${'$'}selected)
        buckets = Query("local_handle", { handle: "local://scan/7f3a9b1c2d3e#histogram" }, [])
    """.trimIndent()

    private fun argsJson(program: String): String {
        val escaped = program
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"program":"$escaped","fallbackSummary":"摘要"}"""
    }

    // ── 放行形态 ────────────────────────────────────────────────────

    @Test
    fun `valid minimal program passes`() {
        assertNull(RenderOpenUiValidator.validateToError(validMinimal))
    }

    @Test
    fun `valid program with local_handle query passes`() {
        assertNull(RenderOpenUiValidator.validateToError(validWithQuery))
    }

    @Test
    fun `query line before root statement passes`() {
        // 2026-10-24 真机事故回归：剥离顺序先注释后字符串时，handle 里 local:// 的
        // // 被当注释截断留下奇数引号，字符串全局配对跨行吞掉后续 root 语句，
        // 合法程序被误报「缺 root」死循环烧光 20 迭代（validWithQuery 的 Query 在末行
        // 恰好掩盖此 bug）。Query 行在前、root 行在后的真实 LLM 输出钉住。
        val program = """
            ${'$'}selected = ""
            buckets = Query("local_handle", { handle: "local://scan/f9d33db29ed6#histogram" }, [])
            root = Card([Text("2026 年照片月度分布", "HEADLINE"), Text("共 148 张，按拍摄月份统计", "CAPTION"), BarChart("每月照片数", buckets, ${'$'}selected)])
        """.trimIndent()
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `tool description golden example passes validation`() {
        // 黄金示例与 @LLMDescription 同源引用同一常量（RENDER_UI_EXAMPLE 先例）：
        // 改示例或收紧校验器都会红此测试——防描述与校验器脱节
        assertNull(RenderOpenUiValidator.validateToError(RenderOpenUiToolService.GOLDEN_STATS_PROGRAM))
    }

    @Test
    fun `image component program passes`() {
        // 图文混排：Image(photo*, aspect?) 引用 #photos 投影元素 id（spec 设计 D3/D5）
        val program = """
            photos = Query("local_handle", { handle: "local://scan/7f3a9b1c2d3e#photos" }, [])
            root = Card([Text("海边之旅", "HEADLINE"), Row([Image(photos[0].id), Image(photos[1].id), Image(photos[2].id)]), Text(photos[0].date, "CAPTION")])
        """.trimIndent()
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `image with literal aspect number passes`() {
        assertNull(
            RenderOpenUiValidator.validateToError(
                """photos = Query("local_handle", { handle: "local://scan/7f3a9b1c2d3e#photos" }, [])
root = Card([Image(photos[0].id, 1.5)])""",
            ),
        )
    }

    @Test
    fun `photos fragment handle form passes`() {
        // #photos 在 fragment 白名单内（histogram|photos，2026-10-24 收敛）
        val program = """
            photos = Query("local_handle", { handle: "local://scan/7f3a9b1c2d3e#photos" }, [])
            root = Card([Image(photos[0].id)])
        """.trimIndent()
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `image with media path literal is still rejected`() {
        // [PRIVACY] 不变式：photo 只许句柄投影 id，MEDIA_PATH 禁令对 Image 同样生效
        val error = RenderOpenUiValidator.validateToError(
            """root = Card([Image("content://media/external/images/media/101")])""",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("媒体路径"))
    }

    @Test
    fun `tool description image golden example passes validation`() {
        // 图文混排黄金示例同图源引用（同 GOLDEN_STATS_PROGRAM 钉法）
        assertNull(RenderOpenUiValidator.validateToError(RenderOpenUiToolService.GOLDEN_MIXED_PROGRAM))
    }

    @Test
    fun `unknown handle fragment is rejected`() {
        // fragment 闭集白名单（2026-10-24 review 🟡4）：#photo 这类 typo 在服务端
        // 同轮纠错，不放行到端侧整卡静默降级
        val error = RenderOpenUiValidator.validateToError(
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photo\" }, [])\n" +
                "root = Card([Image(photos[0].id)])",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("#histogram") && error.contains("#photos"))
    }

    @Test
    fun `image count over limit is rejected`() {
        // OOM 护栏（2026-10-24 review 🟡3）：全宽图每张约 3.5MB 位图，整卡同屏存活
        val images = (1..(RenderOpenUiValidator.MAX_IMAGE_COMPONENTS + 1))
            .joinToString(", ") { index -> "Image(photos[$index].id)" }
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([$images])"
        val error = RenderOpenUiValidator.validateToError(program)
        assertNotNull(error)
        assertTrue(error!!.contains("Image") && error.contains("上限"))
    }

    @Test
    fun `image count at limit passes`() {
        val images = (1..RenderOpenUiValidator.MAX_IMAGE_COMPONENTS)
            .joinToString(", ") { index -> "Image(photos[$index].id)" }
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([$images])"
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `quoted action string is rejected`() {
        // 2026-10-24 真机事故：LLM 把 action 包成字符串（"@OpenUrl(...)"），
        // 端侧 ACTION 校验 type-mismatch 按钮静默惰性——服务端同轮纠错拦截
        val error = RenderOpenUiValidator.validateToError(
            """root = Card([Button("查看全部", "@OpenUrl(\"https://example.com/album\")")])""",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("裸动作表达式"))
    }

    @Test
    fun `bare action expression passes`() {
        assertNull(
            RenderOpenUiValidator.validateToError(
                """root = Card([Button("查看全部", @OpenUrl("https://example.com/album"))])""",
            ),
        )
    }

    @Test
    fun `no root statement falls back to first Card entry`() {
        assertNull(RenderOpenUiValidator.validateToError("""page = Card([Text("x")])"""))
    }

    @Test
    fun `string literals and comments do not trip component whitelist`() {
        // 剥离字符串/注释后再扫：文案里的「Video(」与注释里的组件名不误伤
        val program = """
            root = Card([Text("看 Video(1) 学构图")]) # Video( 注释
        """.trimIndent()
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    // ── 违规形态 ────────────────────────────────────────────────────

    @Test
    fun `missing root and missing Card entry is rejected`() {
        val error = RenderOpenUiValidator.validateToError("""title = Text("x")""")
        assertNotNull(error)
        assertTrue(error!!.contains("root"))
    }

    @Test
    fun `blank program is rejected`() {
        assertNotNull(RenderOpenUiValidator.validateToError("   "))
    }

    @Test
    fun `unknown component is rejected`() {
        val error = RenderOpenUiValidator.validateToError("""root = Card([Video("x")])""")
        assertNotNull(error)
        assertTrue(error!!.contains("Video"))
    }

    @Test
    fun `query with non local_handle tool is rejected`() {
        val error = RenderOpenUiValidator.validateToError(
            "root = Card([Text(\"x\")])\ndata = Query(\"media_list\", {}, [])",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("local_handle"))
    }

    @Test
    fun `local_handle query without handle arg is rejected`() {
        val error = RenderOpenUiValidator.validateToError(
            "root = Card([BarChart(\"t\", buckets)])\nbuckets = Query(\"local_handle\", {}, [])",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("local://scan/"))
    }

    @Test
    fun `malformed local handle is rejected`() {
        val error = RenderOpenUiValidator.validateToError(
            "root = Card([BarChart(\"t\", buckets)])\nbuckets = Query(\"local_handle\", { handle: \"local://foo/bar\" }, [])",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("local://foo/bar"))
    }

    @Test
    fun `query as standalone statement rhs passes`() {
        assertNull(RenderOpenUiValidator.validateToError(validWithQuery))
    }

    @Test
    fun `inline query inside component args passes`() {
        // 2026-10-24 合并决议：端侧引擎已支持内联 Query 提升（fix/a2ui 0e5856b10），
        // 服务端不再禁内联（原 inline-reserved 根因已根治），钉住防禁令回潮。
        val program = """
            ${'$'}selected = ""
            root = Card([Text("月度分布"), BarChart("每月照片数", Query("local_handle", { handle: "local://scan/7f3a9b1c2d3e#histogram" }, []), ${'$'}selected)])
        """.trimIndent()
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `media path in program is rejected`() {
        val error = RenderOpenUiValidator.validateToError(
            "root = Card([Text(\"content://media/1\")])",
        )
        // 路径在字符串字面量内也禁（隐私投影契约先于剥离检查）
        assertNotNull(error)
        assertTrue(error!!.contains("媒体路径"))
    }

    @Test
    fun `oversized program is rejected`() {
        val program = "root = Card([Text(\"x\")])\n" + "a = 1\n".repeat(30_000)
        assertTrue(program.length > RenderOpenUiValidator.MAX_PROGRAM_LENGTH)
        val error = RenderOpenUiValidator.validateToError(program)
        assertNotNull(error)
        assertTrue(error!!.contains("超长"))
    }

    // ── M2 七新组件（2026-10-24，spec《openui-component-library-m2-design》） ──

    /** M2 交互复合卡：七新组件 + $绑定 全放行。 */
    private val validM2Interactive = """
        ${'$'}level = 50
        ${'$'}flag = false
        ${'$'}choice = "a"
        root = Card([Icon("photo"), Divider(), List([Text("x")], "HORIZONTAL"), Tabs([{title: "t", children: [Text("y")]}]), CheckBox("精选", ${'$'}flag), ChoicePicker("排序", [{label: "时间", value: "a"}], ${'$'}choice), Slider(value = ${'$'}level)])
    """.trimIndent()

    @Test
    fun `m2 six new components pass whitelist`() {
        assertNull(RenderOpenUiValidator.validateToError(validM2Interactive))
    }

    @Test
    fun `icon with predefined name passes`() {
        assertNull(RenderOpenUiValidator.validateToError("""root = Card([Icon("warning")])"""))
    }

    @Test
    fun `icon with unknown name is rejected`() {
        // 禁臆造图标名（svgPath 同口径）：服务端同轮纠错，不放行到端侧静默丢组件
        val error = RenderOpenUiValidator.validateToError("""root = Card([Icon("explode")])""")
        assertNotNull(error)
        assertTrue(error!!.contains("explode") && error.contains("预定义图标集"))
    }

    @Test
    fun `icon names match a2ui validator whitelist`() {
        // 字面量副本同源钉住：RenderUiValidator.ICON_NAMES 是副本源（spec §5）
        assertEquals(59, RenderOpenUiValidator.ICON_NAMES.size)
        assertEquals(
            RenderUiValidator.ICON_NAMES,
            RenderOpenUiValidator.ICON_NAMES,
        )
    }

    @Test
    fun `tool description icon list matches validator whitelist`() {
        // 工具描述内嵌清单 ↔ 校验器白名单同源钉住（spec §5 三处字面量副本）
        assertEquals(
            RenderOpenUiValidator.ICON_NAMES.sorted().joinToString(", "),
            RenderOpenUiToolService.ICON_NAMES_TEXT,
        )
    }

    @Test
    fun `tool description interactive golden example passes validation`() {
        // 交互黄金示例同源引用同一常量（GOLDEN_STATS_PROGRAM 钉法先例）
        assertNull(RenderOpenUiValidator.validateToError(RenderOpenUiToolService.GOLDEN_INTERACTIVE_PROGRAM))
    }

    // ── M3 七新组件（2026-10-24，spec《openui-component-library-m3-design》） ──

    @Test
    fun `m3 seven new components pass whitelist`() {
        // 七组件逐个独立成卡（白名单只认组件名，形态细节由端侧 schema 兜底）
        val cases = listOf(
            "Table([{label: \"月份\"}], [[\"2026-01\", 12]])",
            "PieChart(\"占比\", [{label: \"人像\", value: 42}])",
            "LineChart(\"趋势\", [\"01\", \"02\"], [{name: \"全部\", values: [1, 2]}])",
            "Callout(\"结论\", \"提示\", \"WARNING\")",
            "Markdown(\"# 标题\\n- 甲\")",
            "Accordion([{title: \"明细\", children: [Text(\"x\")]}])",
            "Switch(\"通知\", ${'$'}flag)",
        )
        cases.forEach { component ->
            val program = "${'$'}flag = true\nroot = Card([$component])"
            assertNull("应放行: $component", RenderOpenUiValidator.validateToError(program))
        }
    }

    @Test
    fun `m3 lookalike component name is rejected`() {
        // 反面：不在白名单内的近名组件仍被拒（如 DataTable/Grid）
        val error = RenderOpenUiValidator.validateToError("""root = Card([DataTable("x")])""")
        assertNotNull(error)
        assertTrue(error!!.contains("DataTable"))
    }

    // ── 官方全量补全（2026-10-25，spec《openui-full-library-design》21→89） ──

    @Test
    fun `whitelist pins all 97 openui library components`() {
        // 字面量副本同源钉住：server 不依赖 :shared，本清单与 shared
        // OpenUiLibrary.components 的 97 个 key 逐字一致，改动须双向同步核对
        // （跨模块漂移风险与 iconNames 同口径——两边测试各自全绿不代表同源，
        //  改组件清单时人工双端核对）；2026-10-30 通用布局原语批次 89→92（+Grid/Box/Spacer）；
        //  2026-10-31 经典组合版式批次 92→95（+StatRow/StatItem/Banner）；
        //  2026-10-31 相册 APP 组合方式批次 95→97（+PhotoGrid/AvatarRow）
        val expected = setOf(
            // 本仓既有 + M2/M3 扩（原二十一组件）
            "Text", "Row", "Column", "Card", "Button", "BarChart", "Image",
            "Icon", "Divider", "List", "Tabs", "CheckBox", "ChoicePicker", "Slider",
            "Table", "PieChart", "LineChart", "Callout", "Markdown", "Accordion", "Switch",
            // 通用布局原语批次（2026-10-30 本仓扩展）
            "Grid", "Box", "Spacer",
            // 经典组合版式批次（2026-10-31 本仓扩展）
            "StatRow", "StatItem", "Banner",
            // 相册 APP 组合方式批次（2026-10-31 本仓扩展）
            "PhotoGrid", "AvatarRow",
            // 官方别名
            "Stack", "Separator", "MarkDownRenderer",
            // 官方内容/布局
            "CardHeader", "InlineHeader", "TextContent", "TextCallout", "CodeBlock",
            "ImageBlock", "ImageGallery", "Tag", "TagBlock", "Label",
            "BoldText", "IconText", "ImageText", "ImageTextLarge", "EntityList",
            "ListBlock", "Steps", "Carousel", "Buttons", "IconButton", "Modal",
            // 官方表单
            "Form", "FormControl", "Input", "TextArea", "Select", "DatePicker",
            "CheckBoxGroup", "RadioGroup", "SwitchGroup", "Chips", "OptionCards", "EditableTable",
            // 官方图表
            "AreaChart", "HorizontalBarChart", "RadarChart", "RadialChart",
            "SingleStackedBarChart", "ScatterChart",
            // 官方卡块
            "MetricIndicatorInline", "MetricIndicatorWithStrikethrough",
            "SnippetCardBlock", "OverviewCardBlock", "ContextCardBlock",
            "CompositeCardBlock", "VisualCardBlock",
            // 元素形状（ListItem 2026-10-31 起升格为可独立渲染的经典列表行，仍在白名单）
            "TabItem", "AccordionItem", "ListItem", "StepsItem", "SelectItem",
            "CheckBoxItem", "RadioItem", "SwitchItem", "ChipItem", "OptionCard",
            "Series", "Slice", "ScatterSeries", "Point",
            "SnippetCardItem", "OverviewCardItem", "ContextCardItem",
            "CompositeCardItem", "VisualCardItem",
        )
        assertEquals(97, expected.size)
        assertEquals(expected, RenderOpenUiValidator.ALLOWED_COMPONENTS)
    }

    @Test
    fun `official expansion components pass whitelist`() {
        // 各分组代表组件 + 元素形状组件（白名单只认组件名，形态细节由端侧 schema 兜底）
        val cases = listOf(
            "Stack([Text(\"x\")], \"row\", \"m\")",
            "Separator(\"vertical\")",
            "MarkDownRenderer(\"# 标题\")",
            "CardHeader(\"标题\", \"副题\")",
            "TextCallout(\"提示\", \"说明\", \"warning\")",
            "ImageBlock(\"https://cos.polang.net/a.png\")",
            "Tag(\"精选\", \"star\")",
            "ListBlock([{title: \"一\"}], \"number\")",
            "Steps([{title: \"第一步\"}])",
            "Modal(\"弹窗\", ${'$'}open, [Text(\"x\")])",
            "Form(\"f\", [Input(\"city\")])",
            "Select(\"s\", [{value: \"a\", label: \"甲\"}])",
            "Chips(\"c\", [{value: \"a\", label: \"甲\"}], \"single\")",
            "EditableTable(\"t\", [{type: \"text\", key: \"k\", header: \"列\"}], [{id: \"1\", values: [\"v\"]}])",
            "AreaChart(\"趋势\", [\"01\"], [{name: \"全部\", values: [1]}])",
            "RadarChart(\"能力\", [\"a\"], [{name: \"我\", values: [1]}])",
            "RadialChart(\"环\", [{category: \"甲\", value: 1}])",
            "ScatterChart(\"散点\", [{name: \"s\", points: [[1, 2]]}])",
            "MetricIndicatorInline(\"+12%\")",
            "SnippetCardBlock([{lhs: [Text(\"k\")], rhs: [Text(\"v\")]}])",
            "VisualCardBlock([{body: \"封面\"}])",
            // 元素形状（仅作父组件数组元素的对象形状，白名单放行，位置约束端侧兜底）
            "TabItem(\"v\", \"触发\", [Text(\"x\")])",
            "Series(\"系列\", [1, 2])",
            "Slice(\"甲\", 1)",
            "Point(1, 2)",
        )
        cases.forEach { component ->
            val program = "${'$'}open = false\nroot = Card([$component])"
            assertNull("应放行: $component", RenderOpenUiValidator.validateToError(program))
        }
    }

    @Test
    fun `official col component is deliberately rejected`() {
        // 官方 Col 列式 Table 刻意不收（spec §3.1：行式 Table 为唯一形态，防双形态精神分裂）
        val error = RenderOpenUiValidator.validateToError("""root = Card([Col("x")])""")
        assertNotNull(error)
        assertTrue(error!!.contains("Col"))
    }

    // ── Image src 校验（2026-10-25 官方对齐：photo 降可选 + src https 限） ──

    @Test
    fun `image with only photo passes`() {
        assertNull(
            RenderOpenUiValidator.validateToError(
                """photos = Query("local_handle", { handle: "local://scan/7f3a9b1c2d3e#photos" }, [])
root = Card([Image(photos[0].id)])""",
            ),
        )
    }

    @Test
    fun `image with only https src passes`() {
        assertNull(
            RenderOpenUiValidator.validateToError(
                """root = Card([Image(src = "https://cos.polang.net/banner.png", alt = "封面")])""",
            ),
        )
    }

    @Test
    fun `image with positional https src passes`() {
        // 位置参数形态（photo, aspect, src, alt）：第三个位置参数的 https 字符串同样放行
        assertNull(
            RenderOpenUiValidator.validateToError(
                """photos = Query("local_handle", { handle: "local://scan/7f3a9b1c2d3e#photos" }, [])
root = Card([Image(photos[0].id, 1.5, "https://cos.polang.net/banner.png")])""",
            ),
        )
    }

    @Test
    fun `image with non-https named src is rejected`() {
        // [PRIVACY] 同 A2UI Image.url 口径：http/本地路径一律拒
        val error = RenderOpenUiValidator.validateToError(
            """root = Card([Image(src = "http://insecure.example.com/a.png")])""",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("https://"))
    }

    @Test
    fun `image with non-https positional src is rejected`() {
        val error = RenderOpenUiValidator.validateToError(
            """photos = Query("local_handle", { handle: "local://scan/7f3a9b1c2d3e#photos" }, [])
root = Card([Image(photos[0].id, 1.5, "http://insecure.example.com/a.png")])""",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("Image.src") && error.contains("https://"))
    }

    @Test
    fun `image without photo and src is rejected`() {
        // photo/src 二缺一：渲染层虽兜底丢占位，prompt 层引导 LLM 必给其一（服务端同轮纠错）
        val error = RenderOpenUiValidator.validateToError("""root = Card([Image()])""")
        assertNotNull(error)
        assertTrue(error!!.contains("photo") && error.contains("src"))
    }

    @Test
    fun `image gallery with non-https src is rejected`() {
        // src https 限全组件生效：ImageGallery 的 {src: "…"} 对象字段形态同样硬校验
        val gallery = RenderOpenUiValidator.validateToError(
            """root = Card([ImageGallery([{src: "http://insecure.example.com/a.png"}])])""",
        )
        assertNotNull(gallery)
        assertTrue(gallery!!.contains("https://"))
    }

    // ── 图片地址键名闭集（src/bgImageSrc/image，review 🟡7） ─────────────

    @Test
    fun `visual card bgImageSrc non-https is rejected`() {
        val error = RenderOpenUiValidator.validateToError(
            """root = Card([VisualCardBlock([{body: "封面", bgImageSrc: "http://insecure.example.com/bg.png"}])])""",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("bgImageSrc") && error.contains("https://"))
    }

    @Test
    fun `visual card bgImageSrc https passes`() {
        assertNull(
            RenderOpenUiValidator.validateToError(
                """root = Card([VisualCardBlock([{body: "封面", bgImageSrc: "https://cos.polang.net/bg.png"}])])""",
            ),
        )
    }

    @Test
    fun `listblock image non-https is rejected`() {
        val error = RenderOpenUiValidator.validateToError(
            """root = Card([ListBlock([{title: "一", image: "http://insecure.example.com/t.png"}], "image")])""",
        )
        assertNotNull(error)
        assertTrue(error!!.contains("image") && error.contains("https://"))
    }

    @Test
    fun `listblock image https passes`() {
        assertNull(
            RenderOpenUiValidator.validateToError(
                """root = Card([ListBlock([{title: "一", image: "https://cos.polang.net/t.png"}], "image")])""",
            ),
        )
    }

    // ── 整卡位图总数护栏（review 🟡8：Image 调用 + src 类图片合计 ≤12） ──

    @Test
    fun `image gallery with 13 https images is rejected`() {
        val items = (1..13).joinToString(", ") { i -> "{src: \"https://cos.polang.net/$i.png\"}" }
        val error = RenderOpenUiValidator.validateToError("root = Card([ImageGallery([$items])])")
        assertNotNull(error)
        assertTrue(error!!.contains("上限"))
    }

    @Test
    fun `image gallery with 12 https images passes`() {
        val items = (1..12).joinToString(", ") { i -> "{src: \"https://cos.polang.net/$i.png\"}" }
        assertNull(RenderOpenUiValidator.validateToError("root = Card([ImageGallery([$items])])"))
    }

    @Test
    fun `image photo plus gallery 12 images exceeds combined limit`() {
        // 合计口径：1 个 Image(photo) + 12 张 gallery = 13 张位图 → 超上限
        val items = (1..12).joinToString(", ") { i -> "{src: \"https://cos.polang.net/$i.png\"}" }
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([Image(photos[0].id), ImageGallery([$items])])"
        val error = RenderOpenUiValidator.validateToError(program)
        assertNotNull(error)
        assertTrue(error!!.contains("上限"))
    }

    @Test
    fun `image src inside image call is not double counted`() {
        // Image(src=…) 的 src 落在 Image 调用区间内只计一次：1 + 11 = 12 张 → 放行
        val items = (1..11).joinToString(", ") { i -> "{src: \"https://cos.polang.net/$i.png\"}" }
        val program =
            "root = Card([Image(src = \"https://cos.polang.net/cover.png\"), ImageGallery([$items])])"
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `tool description report golden example passes validation`() {
        // 图文报表黄金示例（Table + PieChart + Callout）同源引用同一常量（GOLDEN_* 钉法先例）
        assertNull(RenderOpenUiValidator.validateToError(RenderOpenUiToolService.GOLDEN_REPORT_PROGRAM))
    }

    @Test
    fun `tool description stat tile golden example passes validation`() {
        // stat tile 黄金示例（DISPLAY 大数字 + CAPTION 标签，设计规范 §4.1）同源钉住
        assertNull(RenderOpenUiValidator.validateToError(RenderOpenUiToolService.GOLDEN_STAT_TILE_PROGRAM))
    }

    // ── 构图软警告（2026-10-25，设计规范第一刀配套） ──────────────────

    @Test
    fun `lint warns when display texts exceed four but does not reject the card`() {
        val tiles = (1..5).joinToString(", ") { i -> "Text(\"$i\", \"DISPLAY\")" }
        val program = "root = Card([$tiles])"
        val warnings = RenderOpenUiValidator.lintComposition(program)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("DISPLAY"))
        // 软警告不拒卡：硬校验仍放行
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `lint clean at display threshold and stat tile golden stays clean`() {
        val tiles = (1..4).joinToString(", ") { i -> "Text(\"$i\", \"DISPLAY\")" }
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition("root = Card([$tiles])"))
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(RenderOpenUiToolService.GOLDEN_STAT_TILE_PROGRAM),
        )
    }

    @Test
    fun `lint warns when buttons exceed three`() {
        val buttons = (1..4).joinToString(", ") { i -> "Button(\"b$i\")" }
        val warnings = RenderOpenUiValidator.lintComposition("root = Card([$buttons])")
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("Button"))
    }

    @Test
    fun `lint ignores component names inside text literals`() {
        // 字符串剥离后计数：文案里出现 Button/DISPLAY 字样不计入
        val program = """root = Card([Text("Button 是按钮组件，DISPLAY 是大数字"), Button("确定")])"""
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition(program))
    }

    @Test
    fun `lint clean at button threshold and dual violations produce two warnings`() {
        val threeButtons = (1..3).joinToString(", ") { i -> "Button(\"b$i\")" }
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition("root = Card([$threeButtons])"))
        val tiles = (1..5).joinToString(", ") { i -> "Text(\"$i\", \"DISPLAY\")" }
        val buttons = (1..4).joinToString(", ") { i -> "Button(\"b$i\")" }
        assertEquals(
            2,
            RenderOpenUiValidator.lintComposition("root = Card([$tiles, $buttons])").size,
        )
    }

    @Test
    fun `lint warns when stat tile row lacks center align`() {
        val program = "root = Card([Text(\"统计\", \"HEADLINE\"), " +
            "Row([Column([Text(\"7,120\", \"DISPLAY\"), Text(\"照片\", \"CAPTION\")])])])"
        val warnings = RenderOpenUiValidator.lintComposition(program)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("align"))
        // 软警告不拒卡：硬校验仍放行（align 属端侧 schema 面，server 半信任不拦）
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `lint clean when stat tile columns carry center align or no row present`() {
        val centered =
            "root = Card([Row([Column([Text(\"7,120\", \"DISPLAY\")], align = \"center\")])])"
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition(centered))
        // DISPLAY 直排无 Row 分栏（非 stat tile 形态）不触发
        val flat = "root = Card([Text(\"7,120\", \"DISPLAY\"), Text(\"照片\", \"CAPTION\")])"
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition(flat))
    }

    // ── 图文组合固化（2026-10-30）：photo 键计数 + Row 裸拼图文 lint ──────

    @Test
    fun `tool description photo text golden example passes validation and lint`() {
        // 图文排版黄金示例（ImageTextLarge/ImageText 组合 + photo 投影键）同源钉住
        assertNull(RenderOpenUiValidator.validateToError(RenderOpenUiToolService.GOLDEN_PHOTO_TEXT_PROGRAM))
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(RenderOpenUiToolService.GOLDEN_PHOTO_TEXT_PROGRAM),
        )
    }

    @Test
    fun `image text photo keys count toward image limit`() {
        // 13 个 ImageText(photo=…) 各产一张位图 → 超上限；photo 键在 Image 调用区间外计入
        val items = (1..13).joinToString(", ") { i -> "ImageText(\"t$i\", photo = photos[0].id)" }
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([$items])"
        val error = RenderOpenUiValidator.validateToError(program)
        assertNotNull(error)
        assertTrue(error!!.contains("上限"))
    }

    @Test
    fun `named photo inside image call is not double counted`() {
        // Image(photo=…) 的 photo 键落在 Image 调用区间内只计一次：1 + 11 = 12 张 → 放行
        val items = (1..11).joinToString(", ") { i -> "{src: \"https://cos.polang.net/$i.png\"}" }
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([Image(photo = photos[0].id), ImageGallery([$items])])"
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `lint warns when row mixes bare image with text but does not reject the card`() {
        // 图文项反模式：Row 等宽分栏 + Image + Text 裸拼
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([Row([Image(photos[0].id), Column([Text(\"标题\"), Text(\"副题\", \"CAPTION\")])])])"
        val warnings = RenderOpenUiValidator.lintComposition(program)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("ImageText"))
        // 软警告不拒卡：硬校验仍放行
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `lint clean for pure multi image row and text after row does not false trip`() {
        // 纯多图并排是食谱合法形态；Row 之后的卡级 Text 不被窗口误算进 Row 实参区间
        // （GOLDEN_MIXED 黄金示例正是此形态——括号配对截取防回归）
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(RenderOpenUiToolService.GOLDEN_MIXED_PROGRAM),
        )
    }

    // ── 通用布局原语批次（2026-10-30）：Grid/Box/Spacer + Row.weights ──────

    @Test
    fun `tool description layout golden example passes validation and lint`() {
        // 通用布局黄金示例（PhotoGrid 照片墙 + 1:2 左图右文 Row weights）同源钉住
        assertNull(RenderOpenUiValidator.validateToError(RenderOpenUiToolService.GOLDEN_LAYOUT_PROGRAM))
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(RenderOpenUiToolService.GOLDEN_LAYOUT_PROGRAM),
        )
    }

    @Test
    fun `lint exempts row image text composition carrying weights`() {
        // weights 比例分栏是认可的经典版式写法（2026-10-30 lint 修订）：同样 Image+Text
        // 同 Row，带 weights 不警告、不带才警告（无 weights 用例见上文既有测试）
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([Row([Image(photos[0].id), Column([Text(\"标题\"), Text(\"副题\", \"CAPTION\")])], weights = [1, 2], align = \"center\")])"
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition(program))
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    // ── 经典组合版式批次（2026-10-31）：StatRow/StatItem/Banner + bgPhoto 计数 ──────

    @Test
    fun `tool description classic golden example passes validation and lint`() {
        // 经典组合版式黄金示例（Banner + StatRow + ListItem）同源钉住（GOLDEN_* 钉法先例）
        assertNull(RenderOpenUiValidator.validateToError(RenderOpenUiToolService.GOLDEN_CLASSIC_PROGRAM))
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(RenderOpenUiToolService.GOLDEN_CLASSIC_PROGRAM),
        )
    }

    @Test
    fun `classic layout components pass whitelist`() {
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([Banner(\"精选\", photo = photos[0].id), " +
                "StatRow([{value: \"1\", label: \"a\"}]), " +
                "ListItem(\"行\", icon = \"photo\", trailing = \"x\"), " +
                "StatItem(\"2\", \"b\")])"
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `bgPhoto keys count toward image limit`() {
        // bgPhoto（VisualCardBlock/Banner 题图投影）同 photo 口径计入单卡位图上限（OOM 护栏）
        val items = (1..13).joinToString(", ") { i -> "{body: \"b$i\", bgPhoto: photos[0].id}" }
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([VisualCardBlock([$items])])"
        assertNotNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `banner photo and bgPhoto within limit pass`() {
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([Banner(\"a\", photo = photos[0].id), Banner(\"b\", photo = photos[1].id), " +
                "VisualCardBlock([{body: \"c\", bgPhoto: photos[2].id}])])"
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    // ── 相册 APP 组合方式批次（2026-10-31）：PhotoGrid/AvatarRow + photos 数组计数 ──

    @Test
    fun `tool description photo pattern golden example passes validation and lint`() {
        // 相册 APP 构图黄金示例（CardHeader + PhotoGrid + AvatarRow）同源钉住（GOLDEN_* 钉法先例）
        assertNull(RenderOpenUiValidator.validateToError(RenderOpenUiToolService.GOLDEN_PHOTO_PROGRAM))
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(RenderOpenUiToolService.GOLDEN_PHOTO_PROGRAM),
        )
    }

    @Test
    fun `photo pattern components pass whitelist`() {
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([CardHeader(\"2026年7月\", actionLabel = \"查看全部\"), " +
                "PhotoGrid(photos = [photos[0].id, photos[1].id], columns = 2), " +
                "AvatarRow([{photo: photos[0].id, label: \"妈妈\"}])])"
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `photo grid array elements count toward image limit`() {
        // photos 数组每个元素 = 一张位图（元素含嵌套下标括号 photos[0].id，计数走括号
        // 配对扫描而非正则截断）；13 张单网格即超单卡上限
        val ids = (0..12).joinToString(", ") { i -> "photos[$i].id" }
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([PhotoGrid(photos = [$ids])])"
        assertNotNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `photo grid array within limit passes and query statement not counted`() {
        // 取数语句 photos = Query(...) 不产生位图；12 张网格 + 2 头像 = 14>12 拒、12 张网格独行放行
        val ids = (0..11).joinToString(", ") { i -> "photos[$i].id" }
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([PhotoGrid(photos = [$ids], columns = 4)])"
        assertNull(RenderOpenUiValidator.validateToError(program))
        val over =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([PhotoGrid(photos = [$ids], columns = 4), AvatarRow([{photo: photos[0].id}])])"
        assertNotNull(RenderOpenUiValidator.validateToError(over))
    }

    @Test
    fun `grid with two images triggers photo grid lint`() {
        val program =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([Grid([Image(photos[0].id), Image(photos[1].id)], columns = 2)])"
        assertEquals(1, RenderOpenUiValidator.lintComposition(program).size)
        // 换 PhotoGrid 固化件后无警告
        val fixed =
            "photos = Query(\"local_handle\", { handle: \"local://scan/7f3a9b1c2d3e#photos\" }, [])\n" +
                "root = Card([PhotoGrid(photos = [photos[0].id, photos[1].id], columns = 2)])"
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition(fixed))
    }

    // ── argsJson 与 gate 分发 ───────────────────────────────────────

    @Test
    fun `argsJson not an object is rejected`() {
        assertNotNull(RenderOpenUiValidator.validateArgsJson("not json"))
    }

    @Test
    fun `argsJson missing program is rejected`() {
        assertNotNull(RenderOpenUiValidator.validateArgsJson("""{"fallbackSummary":"s"}"""))
    }

    @Test
    fun `gate dispatches render_ui_openui to openui validator`() {
        assertNotNull(
            RenderUiValidator.gate("render_ui_openui", argsJson("""x = Video(1)""")),
        )
        assertNull(
            RenderUiValidator.gate("render_ui_openui", argsJson(validWithQuery)),
        )
    }

    @Test
    fun `gate never blocks lint-overflow card - soft warnings are not hard rejects`() {
        // lint 超限（10 个 DISPLAY）只是构图建议，发射点门控必须放行（防 lint 误接进 gate 的回归）
        val tiles = (1..10).joinToString(", ") { i -> "Text(\"$i\", \"DISPLAY\")" }
        assertNull(RenderUiValidator.gate("render_ui_openui", argsJson("root = Card([$tiles])")))
    }

    @Test
    fun `gate keeps render_ui on a2ui validator and passes other tools through`() {
        // render_ui 仍走 A2UI 信封校验（openui program 不是合法 A2UI 负载）
        assertNotNull(RenderUiValidator.gate("render_ui", argsJson(validMinimal)))
        assertNull(RenderUiValidator.gate("search_media", " whatever "))
    }
}
