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
        // 与 RenderOpenUiToolService @LLMDescription 规则 7 的黄金示例逐字同源
        // （占位 <id> 换成合法 handleId 形态）；改示例必须同步改这里——
        // A2UI 线 RENDER_UI_EXAMPLE 同款「示例自身过校验」防漂移钉住
        val golden = """
            ${'$'}selected = ""
            buckets = Query("local_handle", { handle: "local://scan/f9d33db29ed6#histogram" }, [])
            root = Card([Text("相册按月分布", "HEADLINE"), Text("按月统计", "CAPTION"), BarChart("每月照片数", buckets, ${'$'}selected)])
        """.trimIndent()
        assertNull(RenderOpenUiValidator.validateToError(golden))
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
