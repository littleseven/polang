package com.mamba.picme.server.agent

import com.mamba.picme.domain.chat.openui.OpenUiLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenUI 程序负载校验器测试：每个违规类一例 + 合法夹具放行 + 布局门禁。
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
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_STATS_PROGRAM))
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
    fun `paginated album program with state computed index and ternary passes`() {
        // album-maker 技能卡内分页骨架（2026-11-07）：$page state + 计算下标 photos[$page*9+i].id
        // + 名字/标签平行字面量数组 + 越界格三元置 null + @Set 翻页——9 个 Image 调用位 = 9 位图
        // （计算下标不改变静态计数），全程序应合法放行，防校验器误杀卡内分页形态。
        val program = """
            ${'$'}page = 0
            photos = Query("local_handle", { handle: "local://scan/7f3a9b1c2d3e#photos" }, [])
            names = ["西湖晨光", "断桥残影", "柳浪闻莺", "灵隐午后", "九溪烟树", "龙井茶山", "钱塘暮色", "河坊夜市", "满陇桂雨", "北高峰远眺", "吴山天风"]
            tags = ["湖景", "湖景", "公园", "古刹", "溪谷", "山野", "江景", "夜景", "花事", "山景", "城景"]
            root = Card([
              CardHeader("杭州精选", subtitle = "11 张 · 第 " + (${'$'}page + 1) + " / 2 页"),
              Row([
                Column([Box([Image(photos[${'$'}page * 9 + 0].id, aspect = 1), Tag(tags[${'$'}page * 9 + 0])], align = "bottomStart"), Text(names[${'$'}page * 9 + 0], maxLines = 1)], spacing = "xs"),
                Column([Box([Image(photos[${'$'}page * 9 + 1].id, aspect = 1), Tag(tags[${'$'}page * 9 + 1])], align = "bottomStart"), Text(names[${'$'}page * 9 + 1], maxLines = 1)], spacing = "xs"),
                (${'$'}page * 9 + 2 < photos.length) ? Column([Box([Image(photos[${'$'}page * 9 + 2].id, aspect = 1), Tag(tags[${'$'}page * 9 + 2])], align = "bottomStart"), Text(names[${'$'}page * 9 + 2], maxLines = 1)], spacing = "xs") : null
              ], align = "start"),
              Row([
                ${'$'}page > 0 ? Button("← 上一页", @Set(${'$'}page, ${'$'}page - 1), "secondary") : null,
                ${'$'}page < 1 ? Button("下一页 →", @Set(${'$'}page, ${'$'}page + 1), "secondary") : null
              ], align = "center")
            ])
            fallbackSummary = "11 张精选，数据在原设备"
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
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_MIXED_PROGRAM))
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
    fun `tool description icon list matches validator whitelist`() {
        // 分层 runtime M1.5：三处字面量副本已收口——校验器白名单与描述内嵌清单
        // 均真引用 shared OpenUiLibrary.iconNames，本测试钉派生关系而非副本相等
        assertEquals(
            OpenUiLibrary.iconNames.joinToString(", "),
            OpenUiPromptAssets.ICON_NAMES_TEXT,
        )
        assertEquals(OpenUiLibrary.iconNames.toSet(), RenderOpenUiValidator.ICON_NAMES)
    }

    @Test
    fun `tool description interactive golden example passes validation`() {
        // 交互黄金示例同源引用同一常量（GOLDEN_STATS_PROGRAM 钉法先例）
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_INTERACTIVE_PROGRAM))
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
    fun `whitelist derives from shared openui library`() {
        // 分层 runtime M1.5：103 项字面量副本已收口——白名单真引用 shared
        // OpenUiLibrary.componentNames（composite build），跨模块漂移风险自此消除；
        // 逐项内容与治理史由 shared 侧 OpenUiLibraryM2Test 钉住
        assertEquals(OpenUiLibrary.componentNames, RenderOpenUiValidator.ALLOWED_COMPONENTS)
        assertEquals(103, RenderOpenUiValidator.ALLOWED_COMPONENTS.size)
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
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_REPORT_PROGRAM))
    }

    @Test
    fun `tool description stat tile golden example passes validation`() {
        // stat tile 黄金示例（DISPLAY 大数字 + CAPTION 标签，设计规范 §4.1）同源钉住
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_STAT_TILE_PROGRAM))
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
    fun `lint clean at display threshold`() {
        val tiles = (1..4).joinToString(", ") { i -> "Text(\"$i\", \"DISPLAY\")" }
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition("root = Card([$tiles])"))
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

    // ── 图文组合固化（2026-10-30）：photo 键计数 ─────────────────

    @Test
    fun `tool description photo text golden example passes validation and lint`() {
        // 图文排版黄金示例（ImageTextLarge/ImageText 组合 + photo 投影键）同源钉住
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_PHOTO_TEXT_PROGRAM))
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(OpenUiPromptAssets.GOLDEN_PHOTO_TEXT_PROGRAM),
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

    // ── 通用布局原语批次（2026-10-30）：Grid/Box/Spacer + Row.weights ──────

    @Test
    fun `tool description layout golden example passes validation and lint`() {
        // 通用布局黄金示例（PhotoGrid 照片墙 + 1:2 左图右文 Row weights）同源钉住
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_LAYOUT_PROGRAM))
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(OpenUiPromptAssets.GOLDEN_LAYOUT_PROGRAM),
        )
    }

    // ── 经典组合版式批次（2026-10-31）：StatRow/StatItem/Banner + bgPhoto 计数 ──────

    @Test
    fun `tool description classic golden example passes validation and lint`() {
        // 经典组合版式黄金示例（Banner + StatRow + ListItem）同源钉住（GOLDEN_* 钉法先例）
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_CLASSIC_PROGRAM))
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(OpenUiPromptAssets.GOLDEN_CLASSIC_PROGRAM),
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
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_PHOTO_PROGRAM))
        assertEquals(
            emptyList<String>(),
            RenderOpenUiValidator.lintComposition(OpenUiPromptAssets.GOLDEN_PHOTO_PROGRAM),
        )
    }
    @Test
    fun `tool description layout batch golden examples pass validation and lint`() {
        // Task 14 I-1 补钉：companion KDoc「与 RenderOpenUiValidatorTest 同源引用」SSOT 位
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_COMPARE_PROGRAM))
        assertTrue(
            RenderOpenUiValidator.lintComposition(OpenUiPromptAssets.GOLDEN_COMPARE_PROGRAM).isEmpty(),
        )
        assertNull(RenderOpenUiValidator.validateToError(OpenUiPromptAssets.GOLDEN_TIMELINE_PROGRAM))
        assertTrue(
            RenderOpenUiValidator.lintComposition(OpenUiPromptAssets.GOLDEN_TIMELINE_PROGRAM).isEmpty(),
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

    // ── 查看全部缺省动作兜底批次（2026-10-31）：lint 第三条（收编后末条） ──────────────

    @Test
    fun `header actionLabel without action triggers lint`() {
        // 真机回归根因：黄金示例只传 actionLabel 不传 action，按钮可点态无动作——lint 引导
        // 显式配 @ToAssistant（端侧有同文案兜底，但显式文案语境更准）
        val bare =
            "root = Card([CardHeader(\"最近月份\", actionLabel = \"查看全部\")])"
        assertEquals(1, RenderOpenUiValidator.lintComposition(bare).size)
        val wired =
            "root = Card([CardHeader(\"最近月份\", actionLabel = \"查看全部\", " +
                "action = @ToAssistant(\"查看全部 最近月份\"))])"
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition(wired))
    }

    // ── 动作契约批次（2026-11-01 review Y2/Y5/Y6） ─────────────────────

    @Test
    fun `quoted run and wrapper action strings are rejected`() {
        // QUOTED_ACTION 词表补 Run|Action（review Y2，词表 = shared OpenUiContract.Actions
        // 字面量副本）："@Run(…)" 与 "@Action([…])" 同为字符串包裹动作表达式，端侧
        // ACTION 槽位 type-mismatch 静默惰性，服务端同轮纠错
        listOf(
            """root = Card([Button("b", "@Run(mutation)")])""",
            """root = Card([Button("b", "@Action([@Set(${'$'}a, 1)])")])""",
        ).forEach { program ->
            val error = RenderOpenUiValidator.validateToError(program)
            assertNotNull("应拒绝: $program", error)
            assertTrue(error!!.contains("动作表达式被包成了字符串"))
        }
        // 裸 @Run（合法语法形态）不误伤
        assertNull(
            RenderOpenUiValidator.validateToError(
                """root = Card([Button("b", @Run(mut))])""",
            ),
        )
    }

    @Test
    fun `quoted navigate action string is rejected`() {
        // 自定义动作词表（2026-11-01 官方对齐）：@Navigate 是 library 注册自定义动作，
        // 包成字符串端侧 ACTION 槽位 type-mismatch 静默惰性，服务端同轮纠错
        val error = RenderOpenUiValidator.validateToError(
            """root = Card([Button("b", "@Navigate(\"task_center\")")])""",
        )
        assertNotNull("应拒绝", error)
        assertTrue(error!!.contains("动作表达式被包成了字符串"))
        // 裸形态不误伤
        assertNull(
            RenderOpenUiValidator.validateToError(
                """root = Card([Button("b", @Navigate("task_center"))])""",
            ),
        )
    }

    @Test
    fun `mutation plus run chain passes structural validation`() {
        // 访问 API 官方形态（2026-11-01）：Mutation 语句 + @Run(ref)，组合 Action([...])
        assertNull(
            RenderOpenUiValidator.validateToError(
                """
                photos = Query("local_handle", { handle: "local://scan/7f3a#photos" }, [])
                fav = Mutation("favorite_media", { id: photos[0].id })
                root = Card([Button("收藏并逛相册", Action([@Run(fav), @Navigate("gallery")]))])
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `open url literal gate rejects non https and passes https`() {
        // review Y5：@OpenUrl 字面量首参限 https（与图片 src https 硬校验同族，外链零闸补齐）
        assertNull(
            RenderOpenUiValidator.validateToError(
                """root = Card([Button("b", @OpenUrl("https://polang.net/x"))])""",
            ),
        )
        listOf("file:///etc/passwd", "market://details?id=x", "javascript:alert(1)").forEach { url ->
            val error = RenderOpenUiValidator.validateToError(
                """root = Card([Button("b", @OpenUrl("$url"))])""",
            )
            assertNotNull("应拒绝: $url", error)
            assertTrue(error!!.contains("@OpenUrl 只接受 https://"))
        }
        // 表达式形态（@OpenUrl($u)）不可静态判定，放行由端侧 dispatch scheme 闸兜底
        val binding =
            "${'$'}u = \"https://polang.net\"\nroot = Card([Button(\"b\", @OpenUrl(${'$'}u))])"
        assertNull(RenderOpenUiValidator.validateToError(binding))
    }

    @Test
    fun `list item and list block actionLabel without action trigger lint`() {
        // review Y6：死按钮兜底从 CardHeader 扩展到 ListItem（命名键 = 形态）与
        // ListBlock（items 对象字段冒号形态）
        val bareItem = "root = Card([ListItem(\"行\", icon = \"photo\", actionLabel = \"查看\")])"
        assertEquals(1, RenderOpenUiValidator.lintComposition(bareItem).size)
        val bareBlock = "root = Card([ListBlock([{title: \"行\", actionLabel: \"查看\"}])])"
        assertEquals(1, RenderOpenUiValidator.lintComposition(bareBlock).size)
        // 显式 action 配对后不警告
        val wiredItem =
            "root = Card([ListItem(\"行\", actionLabel = \"查看\", " +
                "action = @ToAssistant(\"查看 行\"))])"
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition(wiredItem))
        val wiredBlock =
            "root = Card([ListBlock([{title: \"行\", actionLabel: \"查看\", " +
                "action: @ToAssistant(\"查看 行\")}])])"
        assertEquals(emptyList<String>(), RenderOpenUiValidator.lintComposition(wiredBlock))
    }

    // ── 回合原生批次（2026-11-01，Task 2）：Html 白名单 + validateProgramToError ──

    @Test
    fun `Html component allowed in whitelist`() {
        val program = "root = Card([Html(\"<p>hi</p>\")])"
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    // ── 任务卡 OpenUI 化批次（2026-11-02）：Progress 白名单 ──

    @Test
    fun `Progress component allowed in whitelist`() {
        val program = "root = Card([Progress(40)])"
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `validateProgramToError returns violations for invalid program`() {
        val error = RenderOpenUiValidator.validateProgramToError("这是一段没有 root 的纯文本")
        assertNotNull(error)
        assertTrue(error!!.contains("缺 root"))
        // 回合原生文案：不再指引 LLM 调用工具
        assertTrue("错误文案仍是工具口径: $error", error!!.contains("重新输出完整程序"))
    }

    @Test
    fun `validateProgramToError passes valid program and applies layout gate`() {
        val valid = "root = Card([Text(\"标题\", \"HEADLINE\")])"
        assertNull(RenderOpenUiValidator.validateProgramToError(valid))
        // 布局门禁违规（Grid 内 ≥2 Image，R4）同样被回合原生入口拦截
        val layoutBad = """
            root = Card([Grid([Image(photos[0].id), Image(photos[1].id)], columns = 2)])
        """.trimIndent()
        val layoutError = RenderOpenUiValidator.validateProgramToError(layoutBad)
        assertNotNull(layoutError)
        // 布局门禁分支同样走回合原生文案（T2 审查修复）：指引重输出完整程序，不得指引工具
        assertTrue("实际：$layoutError", layoutError!!.contains("重新输出完整程序"))
        assertTrue("实际：$layoutError", !layoutError.contains("render_ui_openui"))
    }

    @Test
    fun `validateProgramToError validates normalize-first so raw-only oversize program passes`() {
        // 终审修复波（normalize-first 对齐 ensureValidProgram）：raw 超 MAX_PROGRAM_LENGTH
        // 的程序经 N2 连续 Spacer 合并收回限内后合法——不再白烧 ProgramGuard 唯一纠正机会
        // （MAX_CORRECTIONS=1）。normalize 不补 root、不改组件名与字符串字面量（「raw 非法
        // 但 normalize 后合法」的唯一结构分歧面是长度：N2 合并 / N3 解包缩文本），故本用例
        // 以长度分歧钉住该口径；bare Card(...) 无 root 行本就过结构校验（Card 兜底 entry），
        // 不构成分歧。
        val spacers = (1..9_500).joinToString(", ") { "Spacer(\"xs\")" }
        val raw = "root = Column([$spacers])"
        assertTrue(raw.length > RenderOpenUiValidator.MAX_PROGRAM_LENGTH)
        // raw 口径确实结构非法（超长）——钉住分歧真实存在
        val rawError = RenderOpenUiValidator.validateToError(raw, turnNative = true)
        assertNotNull(rawError)
        assertTrue("实际：$rawError", rawError!!.contains("超长"))
        // normalize 后收回限内 → 回合原生入口放行（结构与布局门禁双过）
        assertTrue(
            OpenUiLayoutNormalizer.normalize(raw).length <= RenderOpenUiValidator.MAX_PROGRAM_LENGTH,
        )
        assertNull(RenderOpenUiValidator.validateProgramToError(raw))
    }

    // ── 2026-11-04 SOE 修复：Html 内嵌整页 HTML（转义密集大字面量）校验不爆栈 ──
    // 事故原型：勾股定理演示卡——终结 ProgramGuard → validate 全程序正则剥离字符串，
    // 交替-星号字面量正则 GroupCurly 逐字符递归 StackOverflowError，回合中途崩掉
    // （Finish error，无 assistant 落库）。修复 = OpenUiStringScan 字符级扫描。

    /** 模拟勾股定理演示卡：Html 字面量内含密集 \" 转义属性。 */
    private fun hugeHtmlProgram(htmlChars: Int): String {
        val body = buildString {
            append("<html><body>")
            var i = 0
            while (length < htmlChars) {
                append("<div class=\\\"row\\\" data-i=\\\"").append(i % 10).append("\\\">x</div>")
                i++
            }
            append("</body></html>")
        }
        return "// summary: 演示\nroot = Html(\"$body\")"
    }

    @Test
    fun `huge Html literal program passes validation without stack overflow`() {
        val program = hugeHtmlProgram(60_000)
        assertNull(RenderOpenUiValidator.validateToError(program))
        assertNull(RenderOpenUiValidator.validateProgramToError(program))
    }

    @Test
    fun `violations are still detected alongside huge Html literal`() {
        val program = hugeHtmlProgram(40_000) + "\nicon = Icon(\"notAnIcon\")"
        val error = RenderOpenUiValidator.validateToError(program)
        assertNotNull(error)
        assertTrue("实际：$error", error!!.contains("notAnIcon"))
    }

    @Test
    fun `lintComposition survives huge Text literals and still counts DISPLAY`() {
        // TEXT_DISPLAY_CALL 旧正则同样交替-星号逐字符递归；修复后改组件树递归计数
        val bigText = "长".repeat(20_000)
        val texts = (1..5).joinToString(", ") { "Text(\"$bigText\", \"DISPLAY\")" }
        val warnings = RenderOpenUiValidator.lintComposition("root = Card([$texts])")
        assertTrue(warnings.any { warning -> warning.contains("DISPLAY") })
    }

    // ── 视频组件批次（2026-11-04：Video + #videos fragment） ──

    @Test
    fun `video component with photo projection passes`() {
        val program = """
            videos = Query("local_handle", { handle: "local://scan/f9d33db29ed6#videos" }, [])
            root = Card([Video(photo = videos[0].id)])
        """.trimIndent()
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `video component with https src passes`() {
        val program = """root = Card([Video(src = "https://cos.polang.net/demo.mp4")])"""
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `video without photo or src is rejected`() {
        val error = RenderOpenUiValidator.validateToError("""root = Card([Video(aspect = 1.5)])""")
        assertNotNull(error)
        assertTrue("实际：$error", error!!.contains("Video 缺视频来源"))
    }

    @Test
    fun `video non-https named src is rejected with video wording`() {
        val error = RenderOpenUiValidator.validateToError("""root = Card([Video(src = "http://x.com/a.mp4")])""")
        assertNotNull(error)
        assertTrue("实际：$error", error!!.contains("公网视频地址"))
    }

    @Test
    fun `video positional non-https src is rejected`() {
        // 位置序 photo, aspect, src——第三位字符串字面量同样限 https
        val error = RenderOpenUiValidator.validateToError(
            "videos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#videos\" }, [])\n" +
                "root = Card([Video(videos[0].id, 1.5, \"ftp://x.com/a.mp4\")])",
        )
        assertNotNull(error)
        assertTrue("实际：$error", error!!.contains("Video.src 只接受 https://"))
    }

    @Test
    fun `video photo does not count into image bitmap cap`() {
        // 12 张图 + 4 段视频：旧口径会把 Video(photo=…) 误计为位图超限
        val images = (1..12).joinToString(", ") { "Image(src = \"https://cos.polang.net/$it.png\")" }
        val videos = (1..4).joinToString(", ") { "Video(src = \"https://cos.polang.net/$it.mp4\")" }
        assertNull(RenderOpenUiValidator.validateToError("root = Card([$images, $videos])"))
    }

    @Test
    fun `video count over cap is rejected`() {
        val videos = (1..5).joinToString(", ") { "Video(src = \"https://cos.polang.net/$it.mp4\")" }
        val error = RenderOpenUiValidator.validateToError("root = Card([$videos])")
        assertNotNull(error)
        assertTrue("实际：$error", error!!.contains("视频 5 个超上限"))
    }

    @Test
    fun `videos fragment handle passes and typo fragment still rejected`() {
        assertNull(
            RenderOpenUiValidator.validateToError(
                "videos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#videos\" }, [])\n" +
                    "root = Card([Text(\"x\")])",
            ),
        )
        val error = RenderOpenUiValidator.validateToError(
            "videos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#video\" }, [])\n" +
                "root = Card([Text(\"x\")])",
        )
        assertNotNull(error)
        assertTrue("实际：$error", error!!.contains("#videos"))
    }
}
