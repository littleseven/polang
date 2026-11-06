package com.mamba.picme.features.chat.openui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mamba.picme.domain.chat.openui.OpenUiTextRole

/** OpenUI 文本/图标配色语义层级（线格式词表 SSOT 在 shared `OpenUiLibrary.colorRoles`）。 */
enum class OpenUiColorRole {
    PRIMARY,
    SURFACE,
    ERROR,
    ON_SURFACE,
}

/**
 * OpenUI 语义样式令牌 → Compose 映射（自 features.chat.a2ui.A2uiStyleMapper 迁入改名，
 * 2026-11-05 A2UI 退役；OpenUI 渲染层只消费 color/role 解析，字阶走 [OpenUiCardTokens]）。
 *
 * 非法/缺失令牌一律 fallback 到默认值（BODY / ON_SURFACE），样式问题不阻断数据渲染。
 */
object OpenUiStyleMapper {

    @Composable
    fun color(role: OpenUiColorRole?): Color = when (role) {
        OpenUiColorRole.PRIMARY -> MaterialTheme.colorScheme.primary
        // SURFACE 仅被文字色消费：surfaceVariant 是填充色，在卡面上隐形
        // （2026-10-24 真机仪表盘小字全隐形事故）——弱化文字正解是 onSurfaceVariant
        OpenUiColorRole.SURFACE -> MaterialTheme.colorScheme.onSurfaceVariant
        OpenUiColorRole.ERROR -> MaterialTheme.colorScheme.error
        OpenUiColorRole.ON_SURFACE,
        null -> MaterialTheme.colorScheme.onSurface
    }

    /** 解析线格式字符串为语义令牌；非法值返回 null（调用方 fallback 到默认）。 */
    fun parseTextRole(raw: String?): OpenUiTextRole? =
        raw?.let { value -> runCatching { OpenUiTextRole.valueOf(value) }.getOrNull() }

    fun parseColorRole(raw: String?): OpenUiColorRole? =
        raw?.let { value -> runCatching { OpenUiColorRole.valueOf(value) }.getOrNull() }
}
