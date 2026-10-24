package com.mamba.picme.features.chat.openui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.domain.chat.a2ui.A2uiSpacingRole
import com.mamba.picme.domain.chat.a2ui.A2uiTextRole

/**
 * OpenUI 卡内视觉 token（M4，spec《2026-10-25-openui-visual-m4-design》§1.1/§1.2）。
 *
 * A2UI 线是对比实验对照组、样式冻结——本组 token 是 OpenUI 渲染器专属样式面，
 * 与 A2uiStyleMapper/A2uiCardTokens 零样式共享（后者仅只读复用几何尺寸与枚举解析）。
 */
object OpenUiCardTokens {

    // ── 字阶（spec §1.1：HEADLINE 20sp/Bold、SUBHEAD 15sp/SemiBold、BODY 14sp、CAPTION 12sp） ──

    @Composable
    fun textStyle(role: A2uiTextRole?): TextStyle = when (role) {
        A2uiTextRole.HEADLINE -> MaterialTheme.typography.titleLarge.copy(
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 26.sp,
        )
        A2uiTextRole.SUBHEAD -> MaterialTheme.typography.titleSmall.copy(
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 21.sp,
        )
        A2uiTextRole.BODY,
        null -> MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
        A2uiTextRole.CAPTION -> MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp)
    }

    // ── 间距阶梯（spec §1.1：组件间默认 sm(8)→md(12)；Section 前 md→lg(16)） ──

    fun spacing(role: A2uiSpacingRole?): Dp = when (role) {
        A2uiSpacingRole.COMPACT -> Spacing.xs
        A2uiSpacingRole.STANDARD,
        null -> Spacing.md
        A2uiSpacingRole.COMFORTABLE -> Spacing.lg
    }

    /** Section（SUBHEAD 标题/图表/表格）起头前间距。 */
    val sectionGap: Dp = Spacing.lg

    // ── Divider（spec §1.1：厚度 0.5dp、onSurface alpha 0.08） ──

    val dividerThickness: Dp = 0.5.dp
    const val dividerAlpha: Float = 0.08f

    // ── Image（spec §1.1：直角 → 12dp 圆角） ──

    val imageCornerRadius: Dp = 12.dp

    /** HORIZONTAL List 内 Image 条目宽（无界宽上下文兜底尺寸，MediaResultsCarousel 横滑条目先例）。 */
    val horizontalImageWidth: Dp = 200.dp

    // ── Tabs 胶囊分段控件（spec §1.3） ──

    val tabsContainerPadding: Dp = Spacing.xs
    val tabsItemPaddingH: Dp = Spacing.md
    val tabsItemPaddingV: Dp = 6.dp

    /**
     * 图表 6 色相离散色板（spec §1.2：蓝/橙/绿/紫/红/青，Material 300 系 tone——
     * 深底可读、luminance 适中）。数据可视化分类色是静态数据色而非主题语义色，收口于此。
     * BarChart 单系列用板首色（蓝，[chartSingleSeries]）；PieChart/LineChart 用全板轮转。
     */
    val chartPalette: List<Color> = listOf(
        Color(0xFF64B5F6), // 蓝 Blue 300
        Color(0xFFFFB74D), // 橙 Orange 300
        Color(0xFF81C784), // 绿 Green 300
        Color(0xFFBA68C8), // 紫 Purple 300
        Color(0xFFE57373), // 红 Red 300
        Color(0xFF4DD0E1), // 青 Cyan 300
    )
    val chartSingleSeries: Color get() = chartPalette.first()
}
