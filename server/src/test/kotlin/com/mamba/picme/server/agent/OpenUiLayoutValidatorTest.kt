package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenUI 布局结构门禁测试（2026-10-31 布局可控性批次，spec §2.2）：
 * R1~R4 每规则一例 + 黄金程序零误伤钉住 + 节点数护栏。
 */
class OpenUiLayoutValidatorTest {

    @Test
    fun `all golden programs pass layout gate`() {
        listOf(
            RenderOpenUiToolService.GOLDEN_STATS_PROGRAM,
            RenderOpenUiToolService.GOLDEN_MIXED_PROGRAM,
            RenderOpenUiToolService.GOLDEN_INTERACTIVE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_REPORT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_STAT_TILE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_LAYOUT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_PHOTO_TEXT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_CLASSIC_PROGRAM,
            RenderOpenUiToolService.GOLDEN_PHOTO_PROGRAM,
        ).forEach { golden ->
            assertEquals(golden, emptyList<String>(), OpenUiLayoutValidator.validate(golden))
        }
    }

    @Test
    fun `r1 rejects triple nested card`() {
        val program = "root = Card([Text(\"a\"), Card([Text(\"b\"), Card([Text(\"c\")])])])"
        val violations = OpenUiLayoutValidator.validate(program)
        assertTrue(violations.toString(), violations.any { v -> v.contains("Card 嵌套") })
    }

    @Test
    fun `r1 allows two-level card but rejects deep container chain`() {
        val twoLevel = "root = Card([Card([Text(\"ok\")])])"
        assertEquals(emptyList<String>(), OpenUiLayoutValidator.validate(twoLevel))

        // 4 层容器链合法（≤MAX_CONTAINER_CHAIN），5 层违规
        val fourChain = "root = Card([Row([Column([Row([Column([Text(\"合法\")])])])])])"
        assertEquals(emptyList<String>(), OpenUiLayoutValidator.validate(fourChain))

        val fiveChain = "root = Card([Row([Column([Row([Column([Row([Text(\"深\")])])])])])])"
        assertTrue(
            OpenUiLayoutValidator.validate(fiveChain).any { v -> v.contains("容器嵌套链") },
        )
    }

    @Test
    fun `r2 rejects duplicated signature pieces in one card`() {
        val program = "root = Card([Banner(\"a\"), Banner(\"b\")])"
        assertTrue(OpenUiLayoutValidator.validate(program).any { v -> v.contains("Banner") })
    }

    @Test
    fun `r3 rejects chart squeezed beside sibling`() {
        val program = "root = Card([Row([BarChart(\"t\", []), Text(\"侧栏\")])])"
        assertTrue(
            OpenUiLayoutValidator.validate(program).any { v -> v.contains("图表不可横向分栏") },
        )
    }

    @Test
    fun `r4 rejects grid of images`() {
        val program = "root = Card([Grid([Image(photos[0].id), Image(photos[1].id)], columns = 2)])"
        assertTrue(
            OpenUiLayoutValidator.validate(program).any { v -> v.contains("PhotoGrid") },
        )
    }

    @Test
    fun `chartgrid accepts charts only`() {
        val ok = "root = Card([ChartGrid([BarChart(\"a\", []), PieChart(\"b\", [])])])"
        assertEquals(emptyList<String>(), OpenUiLayoutValidator.validate(ok))
        val bad = "root = Card([ChartGrid([BarChart(\"a\", []), Text(\"混入\")])])"
        assertTrue(OpenUiLayoutValidator.validate(bad).any { v -> v.contains("ChartGrid 只装图表") })
    }

    @Test
    fun `node count guard`() {
        val parts = (1..61).joinToString(", ") { i -> "Text(\"$i\")" }
        val program = "root = Card([$parts])"
        assertTrue(
            OpenUiLayoutValidator.validate(program).any { v -> v.contains("组件节点") },
        )
    }

    @Test
    fun `node count boundary is exact at sixty`() {
        // 59 Text + Card = 恰 60 节点（上限），放行；61 节点用例已有（node count guard）
        val parts = (1..59).joinToString(", ") { i -> "Text(\"$i\")" }
        val program = "root = Card([$parts])"
        assertEquals(emptyList<String>(), OpenUiLayoutValidator.validate(program))
    }
}
