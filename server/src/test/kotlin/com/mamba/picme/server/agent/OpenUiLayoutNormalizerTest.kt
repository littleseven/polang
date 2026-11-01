package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenUiLayoutNormalizerTest {

    // N1：图文 Row 缺 weights → 按图位补 [1,2] / [2,1]
    @Test
    fun `n1 adds weights to bare image-text row`() {
        val program = "root = Card([Row([Image(photos[0].id), Text(\"说明\")])])"
        val normalized = OpenUiLayoutNormalizer.normalize(program)
        assertEquals(
            "root = Card([Row([Image(photos[0].id), Text(\"说明\")], weights = [1, 2])])",
            normalized,
        )
    }

    @Test
    fun `n1 reversed order gets flipped weights`() {
        val program = "root = Card([Row([Text(\"说明\"), Image(photos[0].id)])])"
        assertEquals(
            "root = Card([Row([Text(\"说明\"), Image(photos[0].id)], weights = [2, 1])])",
            OpenUiLayoutNormalizer.normalize(program),
        )
    }

    @Test
    fun `n1 skips row with weights or pure image row`() {
        val hasWeights = "root = Card([Row([Image(photos[0].id), Text(\"a\")], weights = [1, 3])])"
        assertEquals(hasWeights, OpenUiLayoutNormalizer.normalize(hasWeights))
        val pureImages = "root = Card([Row([Image(photos[0].id), Image(photos[1].id)])])"
        assertEquals(pureImages, OpenUiLayoutNormalizer.normalize(pureImages))
    }

    // N3：单子裸容器解包（有命名实参不解包）
    @Test
    fun `n3 unwraps single-child bare container`() {
        val program = "root = Card([Column([Text(\"hi\")])])"
        assertEquals("root = Card([Text(\"hi\")])", OpenUiLayoutNormalizer.normalize(program))
    }

    @Test
    fun `n3 keeps container with named args`() {
        val program = "root = Card([Column([Text(\"hi\")], align = \"center\")])"
        assertEquals(program, OpenUiLayoutNormalizer.normalize(program))
    }

    // N4：Row 直接子级 Column 含 DISPLAY 未居中 → 补 align
    @Test
    fun `n4 centers stat column`() {
        val program = "root = Card([Row([Column([Text(\"7,120\", \"DISPLAY\")]), Column([Text(\"771\", \"DISPLAY\")], align = \"center\")])])"
        assertEquals(
            "root = Card([Row([Column([Text(\"7,120\", \"DISPLAY\")], align = \"center\"), " +
                "Column([Text(\"771\", \"DISPLAY\")], align = \"center\")])])",
            OpenUiLayoutNormalizer.normalize(program),
        )
    }

    // 评审 I-1：args 尾随逗号形态下追加命名实参不得产出双逗号——客户端 parseCall
    // 本可容忍尾随逗号，双逗号会让 parsePrefix 抛异常 → 整卡致命降级 fallback
    @Test
    fun `n1 tolerates trailing comma after children array`() {
        val trailing = "root = Card([Row([Image(photos[0].id), Text(\"说明\")],)])"
        assertEquals(
            "root = Card([Row([Image(photos[0].id), Text(\"说明\")], weights = [1, 2])])",
            OpenUiLayoutNormalizer.normalize(trailing),
        )
        // 数组内尾随逗号（客户端本就容忍的形态）同样不得被改写出双逗号
        val insideArray = "root = Card([Row([Image(photos[0].id), Text(\"说明\"),])])"
        assertEquals(
            "root = Card([Row([Image(photos[0].id), Text(\"说明\"),], weights = [1, 2])])",
            OpenUiLayoutNormalizer.normalize(insideArray),
        )
    }

    @Test
    fun `n4 tolerates trailing comma in column args`() {
        val program = "root = Card([Row([Column([Text(\"7,120\", \"DISPLAY\")],), Text(\"x\")])])"
        assertEquals(
            "root = Card([Row([Column([Text(\"7,120\", \"DISPLAY\")], align = \"center\"), Text(\"x\")])])",
            OpenUiLayoutNormalizer.normalize(program),
        )
    }

    // N2：连续 Spacer 合并取最大档；纵向流 xs/xl 降档
    @Test
    fun `n2 merges consecutive spacers keeping max size`() {
        val program = "root = Card([Text(\"a\"), Spacer(\"s\"), Spacer(\"l\"), Text(\"b\")])"
        assertEquals(
            "root = Card([Text(\"a\"), Spacer(\"l\"), Text(\"b\")])",
            OpenUiLayoutNormalizer.normalize(program),
        )
    }

    @Test
    fun `n2 downgrades xs and xl in vertical flow`() {
        val program = "root = Card([Text(\"a\"), Spacer(\"xs\"), Text(\"b\")])"
        assertEquals(
            "root = Card([Text(\"a\"), Spacer(\"s\"), Text(\"b\")])",
            OpenUiLayoutNormalizer.normalize(program),
        )
        val xl = "root = Card([Text(\"a\"), Spacer(\"xl\"), Text(\"b\")])"
        assertEquals(
            "root = Card([Text(\"a\"), Spacer(\"l\"), Text(\"b\")])",
            OpenUiLayoutNormalizer.normalize(xl),
        )
    }

    // 2026-11-01 间距词汇统一批次：Spacer 扩 2xl 档——降档映射改显式表（旧
    // replace("xl","l") 会把 "2xl" 腐蚀成非法 "2l"），纵向流 2xl→l、横向 Row 不降
    @Test
    fun `n2 downgrades 2xl in vertical flow but keeps it in row`() {
        val vertical = "root = Card([Text(\"a\"), Spacer(\"2xl\"), Text(\"b\")])"
        assertEquals(
            "root = Card([Text(\"a\"), Spacer(\"l\"), Text(\"b\")])",
            OpenUiLayoutNormalizer.normalize(vertical),
        )
        val merged = "root = Card([Column([Spacer(\"m\"), Spacer(\"2xl\")])])"
        val once = OpenUiLayoutNormalizer.normalize(merged)
        assertEquals("root = Card([Column([Spacer(\"l\")])])", once)
        assertEquals(once, OpenUiLayoutNormalizer.normalize(once))
        val row = "root = Card([Row([Spacer(\"m\"), Spacer(\"2xl\")])])"
        assertEquals(
            "root = Card([Row([Spacer(\"2xl\")])])",
            OpenUiLayoutNormalizer.normalize(row),
        )
    }

    // 评审修 1：N3 裸判定不得被子项改写注入的命名实参遮蔽——唯一子 Row 被 N1 补
    // weights 后，自身全裸的父容器仍须解包
    @Test
    fun `n3 unwraps bare container even when only child gained weights`() {
        val program = "root = Card([Column([Row([Image(photos[0].id), Text(\"a\")])])])"
        val once = OpenUiLayoutNormalizer.normalize(program)
        assertEquals(
            "root = Card([Row([Image(photos[0].id), Text(\"a\")], weights = [1, 2])])",
            once,
        )
        assertEquals(once, OpenUiLayoutNormalizer.normalize(once))
    }

    // 评审修 2 反例 A：N2 合并腾空后的单 Spacer 容器不再二跳解包（幂等）
    @Test
    fun `container holding single merged spacer stays put`() {
        val program = "root = Card([Row([Spacer(\"s\"), Spacer(\"l\")])])"
        val once = OpenUiLayoutNormalizer.normalize(program)
        assertEquals("root = Card([Row([Spacer(\"l\")])])", once)
        assertEquals(once, OpenUiLayoutNormalizer.normalize(once))
    }

    // 评审修 2 反例 B：merge 取最大档产出的字面量在纵向流同样降档（xl 不残留）
    @Test
    fun `merged spacer downgrades max size in vertical flow`() {
        val program = "root = Card([Column([Spacer(\"s\"), Spacer(\"xl\")])])"
        val once = OpenUiLayoutNormalizer.normalize(program)
        assertEquals("root = Card([Column([Spacer(\"l\")])])", once)
        assertEquals(once, OpenUiLayoutNormalizer.normalize(once))
    }

    // 评审修 3：唯一子为非组件调用（@动作）不解包——回退拼接会剥掉 @ 前缀，
    // 产出白名单外裸调用
    @Test
    fun `n3 keeps container whose only child is a non-component call`() {
        val program = "root = Card([Column([@ToAssistant(\"hi\")])])"
        assertEquals(program, OpenUiLayoutNormalizer.normalize(program))
    }

    // 不动点 + 幂等 + 安全
    @Test
    fun `golden programs pass through unchanged`() {
        listOf(
            RenderOpenUiToolService.GOLDEN_STATS_PROGRAM,
            // 三图 Row = N1 多图跳过的教学示例
            RenderOpenUiToolService.GOLDEN_MIXED_PROGRAM,
            RenderOpenUiToolService.GOLDEN_LAYOUT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_STAT_TILE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_CLASSIC_PROGRAM,
            // 布局可控性批次两黄金（Task 14 I-1 补钉）
            RenderOpenUiToolService.GOLDEN_COMPARE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_TIMELINE_PROGRAM,
        ).forEach { golden ->
            assertEquals(golden, golden, OpenUiLayoutNormalizer.normalize(golden))
        }
    }

    @Test
    fun `normalize is idempotent and safe on garbage`() {
        val program = "root = Card([Row([Image(photos[0].id), Text(\"x\")]), Column([Text(\"y\")])])"
        val once = OpenUiLayoutNormalizer.normalize(program)
        assertEquals(once, OpenUiLayoutNormalizer.normalize(once))
        assertEquals("截断", OpenUiLayoutNormalizer.normalize("截断"))
    }
}
