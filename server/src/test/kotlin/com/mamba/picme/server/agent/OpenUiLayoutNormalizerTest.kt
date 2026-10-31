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
            RenderOpenUiToolService.GOLDEN_LAYOUT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_STAT_TILE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_CLASSIC_PROGRAM,
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
