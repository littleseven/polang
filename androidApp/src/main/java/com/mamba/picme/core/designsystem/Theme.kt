package com.mamba.picme.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mamba.picme.domain.model.ThemeMode

// ── MaterialTheme 扩展：兑现 designsystem/AGENTS.md 的 [TOKENS] 规范 ────────────────
// 新增 UI 引用 MaterialTheme.spacing / MaterialTheme.appShapes，禁止硬编码 dp/shape。

/** 间距令牌，引用方式：`MaterialTheme.spacing.sm`。 */
val MaterialTheme.spacing: Spacing
    @Composable @ReadOnlyComposable get() = Spacing

/** 圆角令牌，引用方式：`MaterialTheme.appShapes.panel`。 */
val MaterialTheme.appShapes: AppShapes
    @Composable @ReadOnlyComposable get() = AppShapes

/** 功能色令牌，引用方式：`MaterialTheme.appColors.focusRing`。 */
val MaterialTheme.appColors: AppColors
    @Composable @ReadOnlyComposable get() = AppColors

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
)

/**
 * 微信列表组 cell 底色（token v3.0 新增槽位，非 M3 标准槽）：
 * Light 纯白 / Dark #1A1A1A，供设置列表组等「灰底白组」容器消费。
 * 动态取色（dynamicColor=true）时回退 surfaceContainerLowest 保持近似。
 */
val LocalCellColor = staticCompositionLocalOf { CellLight }

/** 便捷访问：`MaterialTheme.colorScheme.cell`（实为 [LocalCellColor]，非 M3 槽）。 */
val ColorScheme.cell: Color
    @Composable @ReadOnlyComposable get() = LocalCellColor.current

/**
 * 全局主题入口。
 *
 * 动态取色决策（2026-08-19 签核）：全局关闭壁纸动态取色，钉青玉品牌色 scheme。
 * 原因：双端一致（iOS 无 Material You 动态色机制）；Ardot 设计稿恒为青玉。
 * 口子保留——未来如需恢复，调用方显式传 [dynamicColor] = true 即可。
 */
@Composable
fun PoLangTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        CompositionLocalProvider(
            LocalCellColor provides when {
                dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    colorScheme.surfaceContainerLowest
                else -> if (darkTheme) CellDark else CellLight
            },
            content = content
        )
    }
}

/**
 * 强制深色 scheme 的内容区主题。
 *
 * 用于相机、证件照等「强制深色背景」的页面：这些页面无论用户主题如何都以深色
 * 为背景呈现，但全局 scheme 跟随用户主题，浅色 scheme 下 onSurface 派生色
 * （如 AppSlider 未激活轨道 onSurface 12%）在深色背景上不可见。用本包装让
 * scheme 与视觉场景对齐。动态取色策略与 [PoLangTheme] 一致（默认关闭，钉青玉，
 * 2026-08-19 签核；恢复动态取色需显式传 dynamicColor = true）。
 */
@Composable
fun PoLangForcedDarkTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        DarkColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        CompositionLocalProvider(
            LocalCellColor provides CellDark,
            content = content
        )
    }
}
