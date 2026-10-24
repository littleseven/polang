package com.mamba.picme.server.agent

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
    fun `gate keeps render_ui on a2ui validator and passes other tools through`() {
        // render_ui 仍走 A2UI 信封校验（openui program 不是合法 A2UI 负载）
        assertNotNull(RenderUiValidator.gate("render_ui", argsJson(validMinimal)))
        assertNull(RenderUiValidator.gate("search_media", " whatever "))
    }
}
