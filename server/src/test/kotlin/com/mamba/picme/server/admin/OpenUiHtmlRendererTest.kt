package com.mamba.picme.server.admin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiHtmlRendererTest {

    private fun preview(program: String): OpenUiTreePreview = runBlocking {
        OpenUiHtmlRenderer.preview(program)
    }

    @Test
    fun `card tree renders title text button and layout containers`() {
        val result = preview(
            """
            root = Card(title = "相册概览", [Column([
                Text("7,120", "DISPLAY"),
                Text("照片总数", "CAPTION"),
                Row([Button("查看全部"), Button("分享", variant = "secondary")], spacing = "s"),
                Grid([Text("人像"), Text("风景"), Text("美食"), Text("宠物")], columns = 2),
            ])])
            """.trimIndent(),
        )
        val rendered = result as OpenUiTreePreview.Rendered
        val html = rendered.bodyHtml
        assertTrue(html.contains("相册概览"))
        assertTrue(html.contains("7,120"))
        assertTrue(html.contains("font-size:34px")) // DISPLAY 字阶
        assertTrue(html.contains("查看全部"))
        assertTrue(html.contains("background:#0a84ff")) // 主按钮
        assertTrue(html.contains("background:#eef2f7")) // secondary 按钮
        assertTrue(html.contains("display:flex;flex-direction:row"))
        assertTrue(html.contains("grid-template-columns:repeat(2,1fr)"))
        assertTrue(html.contains("border-radius:16px")) // Card 壳
    }

    @Test
    fun `markdown root renders via commonmark`() {
        val result = preview("""root = Markdown("# 标题\n\n正文 **加粗**")""")
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("<h1>标题</h1>"))
        assertTrue(html.contains("<strong>加粗</strong>"))
    }

    @Test
    fun `html component renders sandboxed iframe with escaped srcdoc`() {
        val result = preview("""root = Card([Html("<!DOCTYPE html><html><body><h1>投票</h1></body></html>")])""")
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("sandbox=\"allow-scripts\""))
        assertTrue(html.contains("srcdoc=\"&lt;!DOCTYPE html&gt;"))
    }

    @Test
    fun `query resolves to null on server and does not crash`() {
        val result = preview(
            """
            cnt = Query("photo_count", {})
            root = Card([Text("骨架"), BarChart(buckets = cnt)])
            """.trimIndent(),
        )
        // Query 恒 Null：BarChart buckets 空 → 组件占位框，整树仍渲染
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("骨架"))
        assertTrue(html.contains("BarChart"))
    }

    @Test
    fun `bar chart buckets render bars and labels`() {
        val result = preview(
            """root = Card([BarChart(title = "月度", buckets = [{label: "一月", count: 12}, {label: "二月", count: 30}])])""",
        )
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("月度"))
        assertTrue(html.contains("一月"))
        assertTrue(html.contains("background:#5b8def"))
    }

    @Test
    fun `pie chart renders conic gradient donut and legend`() {
        val result = preview(
            """root = Card([PieChart("占比", [{label: "人像", value: 42}, {label: "风景", value: 18}])])""",
        )
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("conic-gradient"))
        assertTrue(html.contains("人像"))
        assertTrue(html.contains("42"))
    }

    @Test
    fun `table renders html table with header and cells`() {
        val result = preview(
            """root = Card([Table([{label: "月份"}, {label: "数量"}], [["2026-01", 12], ["2026-02", 7]])])""",
        )
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("<table"))
        assertTrue(html.contains("月份"))
        assertTrue(html.contains("2026-01"))
        assertTrue(html.contains("12"))
    }

    @Test
    fun `tabs render capsule row with first tab content`() {
        val result = preview(
            """root = Card([Tabs([{title: "照片", children: [Text("一页")]}, {title: "统计", children: [Text("二页")]}])])""",
        )
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("照片"))
        assertTrue(html.contains("统计"))
        assertTrue(html.contains("一页"))
        assertTrue(!html.contains("二页")) // 静态预览只渲染首个页签
    }

    @Test
    fun `accordion renders all items expanded`() {
        val result = preview(
            """root = Card([Accordion([{title: "区一", children: [Text("内容一")]}, {title: "区二", children: [Text("内容二")]}])])""",
        )
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("区一"))
        assertTrue(html.contains("区二"))
        assertTrue(html.contains("内容一"))
        assertTrue(html.contains("内容二"))
    }

    @Test
    fun `photo image renders placeholder with photo id`() {
        val result = preview("""root = Card([Image(photo = 42, aspect = 1.5)])""")
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("照片 42"))
        assertTrue(html.contains("aspect-ratio:1.5"))
    }

    @Test
    fun `https image renders img tag, non-https downgrades to placeholder`() {
        val ok = preview("""root = Card([Image(src = "https://example.com/a.jpg")])""")
        assertTrue(((ok as OpenUiTreePreview.Rendered).bodyHtml).contains("<img src=\"https://example.com/a.jpg\""))
        val bad = preview("""root = Card([Image(src = "http://example.com/a.jpg")])""")
        assertTrue(!(bad as OpenUiTreePreview.Rendered).bodyHtml.contains("<img"))
    }

    @Test
    fun `callout renders tone colored box`() {
        val result = preview("""root = Card([Callout("磁盘将满", "告警", "WARNING")])""")
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("磁盘将满"))
        assertTrue(html.contains("#d97706"))
    }

    @Test
    fun `form controls render static approximations`() {
        val result = preview(
            """
            ${'$'}level = 30
            ${'$'}flag = true
            root = Card([
                CheckBox("精选", ${'$'}flag),
                Switch("通知", false),
                Slider(value = ${'$'}level, min = 10, max = 90),
                ChoicePicker("主题", [{label: "浅色", value: "light"}, {label: "深色", value: "dark"}], ${'$'}flag ? "light" : "dark"),
            ])
            """.trimIndent(),
        )
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("精选"))
        assertTrue(html.contains("☑"))
        assertTrue(html.contains("通知"))
        assertTrue(html.contains("background:#0a84ff;border-radius:2px")) // 滑杆已填段
        assertTrue(html.contains("浅色"))
    }

    @Test
    fun `unknown component is dropped by validation, siblings still render, errors recorded`() {
        // §8.4 组件校验恢复：未知组件在求值期被剔除（不是渲染期占位），错误入 errors 不炸树
        val result = preview("""root = Card([FancyWidget(foo = 1), Text("ok")])""")
        val rendered = result as OpenUiTreePreview.Rendered
        assertTrue(rendered.bodyHtml.contains("ok"))
        assertTrue(!rendered.bodyHtml.contains("FancyWidget"))
        assertTrue(rendered.errors.isNotEmpty())
    }

    @Test
    fun `uncovered known component renders dashed placeholder with type name`() {
        // Video 在浏览器预览不播放：虚线占位框带组件名（「未显示」痛点的兜底口径）
        val result = preview("""root = Card([Video(src = "https://example.com/v.mp4")])""")
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(html.contains("border:1px dashed"))
        assertTrue(html.contains("Video"))
    }

    @Test
    fun `parse failure returns Failed with errors`() {
        val result = preview("这不是一个 OpenUI 程序")
        val failed = result as OpenUiTreePreview.Failed
        assertTrue(failed.errors.isNotEmpty())
    }

    @Test
    fun `text escaping prevents markup injection`() {
        val result = preview("""root = Card([Text("<script>alert(1)</script>")])""")
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertTrue(!html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `binding resolves against state snapshot after prepare`() {
        // BINDABLE 属性按 state 快照物化（端侧渲染期 state[binding.name] 口径）
        val result = preview(
            """
            ${'$'}name = "海边之旅"
            root = Card([Text(${'$'}name), Text(${'$'}name, "CAPTION")])
            """.trimIndent(),
        )
        val html = (result as OpenUiTreePreview.Rendered).bodyHtml
        assertEquals(2, Regex("海边之旅").findAll(html).count())
    }
}
