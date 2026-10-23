package com.mamba.picme.features.chat.a2ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mamba.picme.domain.chat.a2ui.A2uiColorRole
import com.mamba.picme.domain.chat.a2ui.A2uiSpacingRole
import com.mamba.picme.domain.chat.a2ui.A2uiTextRole
import androidx.compose.ui.graphics.Color

/**
 * A2UI 语义样式令牌 → Compose Design Token 映射表。
 *
 * 对应 shared `A2uiStyleTokens` 的端侧落地：LLM 只声明意图层级，
 * 此处映射到 MaterialTheme 的具体样式/颜色/间距，保主题适配与双端一致。
 *
 * 非法/缺失令牌一律 fallback 到默认值（BODY / ON_SURFACE / STANDARD），
 * 样式问题不阻断数据渲染（对齐 HandleResolver 降级哲学）。
 */
object A2uiStyleMapper {

    @Composable
    fun textStyle(role: A2uiTextRole?): TextStyle = when (role) {
        A2uiTextRole.HEADLINE -> MaterialTheme.typography.titleMedium
        A2uiTextRole.SUBHEAD -> MaterialTheme.typography.titleSmall
        A2uiTextRole.BODY,
        null -> MaterialTheme.typography.bodyMedium
        A2uiTextRole.CAPTION -> MaterialTheme.typography.labelSmall
    }

    @Composable
    fun color(role: A2uiColorRole?): Color = when (role) {
        A2uiColorRole.PRIMARY -> MaterialTheme.colorScheme.primary
        A2uiColorRole.SURFACE -> MaterialTheme.colorScheme.surfaceVariant
        A2uiColorRole.ERROR -> MaterialTheme.colorScheme.error
        A2uiColorRole.ON_SURFACE,
        null -> MaterialTheme.colorScheme.onSurface
    }

    fun spacing(role: A2uiSpacingRole?): Dp = when (role) {
        A2uiSpacingRole.COMPACT -> 4.dp
        A2uiSpacingRole.STANDARD,
        null -> 8.dp
        A2uiSpacingRole.COMFORTABLE -> 16.dp
    }

    /** 解析线格式字符串为语义令牌；非法值返回 null（调用方 fallback 到默认）。 */
    fun parseTextRole(raw: String?): A2uiTextRole? =
        raw?.let { value -> runCatching { A2uiTextRole.valueOf(value) }.getOrNull() }

    fun parseColorRole(raw: String?): A2uiColorRole? =
        raw?.let { value -> runCatching { A2uiColorRole.valueOf(value) }.getOrNull() }

    fun parseSpacingRole(raw: String?): A2uiSpacingRole? =
        raw?.let { value -> runCatching { A2uiSpacingRole.valueOf(value) }.getOrNull() }
}
