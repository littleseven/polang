package com.mamba.picme.features.chat.openui

import androidx.compose.ui.unit.dp
import com.mamba.picme.core.designsystem.Spacing
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 容器间距统一映射钉住（2026-11-01 间距词汇统一批次）：
 * OpenUiCardTokens.spacingDpOf 是 Row/Column/Card/Grid/List spacing、Stack.gap、
 * Spacer.size 三处的单一映射——官方七档 t-shirt scale → Spacing 阶梯（8pt 网格），
 * legacy 别名（A2UI 词汇）保持存量卡视觉不变，未知值回退 m（半信任输入防御）。
 */
class OpenUiSpacingTokensTest {

    @Test
    fun `official seven tiers map to spacing ladder`() {
        assertEquals(0.dp, OpenUiCardTokens.spacingDpOf("none"))
        assertEquals(Spacing.xs, OpenUiCardTokens.spacingDpOf("xs"))
        assertEquals(Spacing.sm, OpenUiCardTokens.spacingDpOf("s"))
        assertEquals(Spacing.md, OpenUiCardTokens.spacingDpOf("m"))
        assertEquals(Spacing.lg, OpenUiCardTokens.spacingDpOf("l"))
        assertEquals(Spacing.xl, OpenUiCardTokens.spacingDpOf("xl"))
        assertEquals(Spacing.xxl, OpenUiCardTokens.spacingDpOf("2xl"))
    }

    @Test
    fun `legacy aliases keep persisted cards visually unchanged`() {
        assertEquals(Spacing.xs, OpenUiCardTokens.spacingDpOf("COMPACT"))
        assertEquals(Spacing.md, OpenUiCardTokens.spacingDpOf("STANDARD"))
        assertEquals(Spacing.lg, OpenUiCardTokens.spacingDpOf("COMFORTABLE"))
    }

    @Test
    fun `missing and unknown values fall back to m`() {
        assertEquals(Spacing.md, OpenUiCardTokens.spacingDpOf(null))
        assertEquals(Spacing.md, OpenUiCardTokens.spacingDpOf("huge"))
    }
}
