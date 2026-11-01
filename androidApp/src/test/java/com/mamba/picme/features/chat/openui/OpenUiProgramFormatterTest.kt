package com.mamba.picme.features.chat.openui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiProgramFormatterTest {

    @Test
    fun `nested card expands one child per line with indent`() {
        val src = "root = Card([Text(\"相册总览\", \"HEADLINE\"), StatRow([{value: \"7,886\", label: \"照片\"}]), BarChart(\"每月照片数\", buckets, \$selected)])"
        val out = OpenUiProgramFormatter.format(src)
        val expected = """
root = Card([
    Text("相册总览", "HEADLINE"),
    StatRow([{value: "7,886", label: "照片"}]),
    BarChart("每月照片数", buckets, ${'$'}selected)
])
        """.trimIndent()
        assertEquals(expected, out)
    }

    @Test
    fun `short calls and objects stay inline`() {
        val src = "a = Text(\"标题\", \"HEADLINE\")\nb = Callout(\"说明\", \"标题\", \"INFO\")"
        assertEquals(src, OpenUiProgramFormatter.format(src))
    }

    @Test
    fun `strings with brackets commas and fake comments are never broken`() {
        val src = "a = Markdown(\"列表 [1,2] (x) // 不是注释，包含 \\\"引号\\\"\")"
        assertEquals(src, OpenUiProgramFormatter.format(src))
    }

    @Test
    fun `line comments and blank lines preserved verbatim`() {
        val src = "// summary: 月度分布卡\n\$selected = \"\"\n\nbuckets = Query(\"local_handle\", { handle: \"local://scan/abc#histogram\" }, [])"
        val out = OpenUiProgramFormatter.format(src)
        assertTrue(out.startsWith("// summary: 月度分布卡\n\$selected = \"\"\n\n"))
        // Query 跨度内含直接子层 [] → 多行展开；对象 { handle: … } 恒内联
        assertTrue(out.contains("Query(\n"))
        assertTrue(out.contains("{ handle: \"local://scan/abc#histogram\" }"))
    }

    @Test
    fun `long array of photo ids expands even without nested calls`() {
        val inner = (0..5).joinToString(", ") { "photos[$it].id" }
        val src = "root = PhotoGrid(photos = [$inner], columns = 3)"
        val out = OpenUiProgramFormatter.format(src)
        assertTrue(out.contains("[\n"))
        assertTrue(out.contains("photos[0].id,\n"))
        assertTrue(out.contains("],\n"))
        assertTrue(out.trimEnd().endsWith(")"))
    }

    @Test
    fun `malformed input passes through without crash`() {
        val src = "root = Card([Text(\"未闭合"
        val out = OpenUiProgramFormatter.format(src)
        assertTrue(out.startsWith("root = Card(["))
        assertTrue(out.contains("\"未闭合"))
    }
}
