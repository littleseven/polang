package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mamba.picme.core.designsystem.A2uiCardTokens
import com.mamba.picme.core.designsystem.Spacing

/**
 * OpenUI 蓝图调试器（debug 包专用，BuildConfig.DEBUG 门控，与 A2uiBlueprintDebugger 对偶）。
 *
 * 徽标内容全为协议标识符（OpenUI v1.0 · partId），无自然语言，[I18N] 豁免。
 * 查看器经 [OpenUiProgramFormatter] 缩进重排 + 语法高亮展示；复制按钮复制 program 原文。
 */

/** Debug 徽标 overlay：右上角胶囊 `OpenUI v1.0 · <partId>`，点击开程序查看器。 */
@Composable
fun OpenUiDebugBadge(
    partId: String,
    program: String,
    modifier: Modifier = Modifier,
) {
    var showViewer by remember(partId) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f),
                shape = RoundedCornerShape(
                    topStart = A2uiCardTokens.debugBadgeRadius,
                    bottomStart = A2uiCardTokens.debugBadgeRadius,
                ),
            )
            .clickable { showViewer = true }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Text(
            text = "OpenUI v1.0 · $partId",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontFamily = FontFamily.Monospace,
        )
    }

    if (showViewer) {
        OpenUiProgramViewerDialog(
            partId = partId,
            program = program,
            onDismiss = { showViewer = false },
        )
    }
}

/** 程序查看器：partId 标题 + 格式化高亮全文（可滚动）+ 复制原文。 */
@Composable
private fun OpenUiProgramViewerDialog(
    partId: String,
    program: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val formatted = remember(program) { OpenUiProgramFormatter.format(program) }
    val componentColor = MaterialTheme.colorScheme.primary
    val stringColor = MaterialTheme.colorScheme.secondary
    val metaColor = MaterialTheme.colorScheme.tertiary
    val commentColor = MaterialTheme.colorScheme.outline
    val highlighted = remember(formatted, componentColor, stringColor, metaColor, commentColor) {
        highlightOpenUiProgram(
            src = formatted,
            componentColor = componentColor,
            stringColor = stringColor,
            metaColor = metaColor,
            commentColor = commentColor,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = partId,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = A2uiCardTokens.debugMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(program)) }) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * OpenUI Lang 语法高亮（debug 查看器专用，词法容错与 [OpenUiProgramFormatter] 同哲学）：
 * - 组件/类型调用（大写开头标识符紧跟 `(`）与 Query/Mutation → componentColor 加粗
 * - 字符串字面量 → stringColor；行注释 → commentColor
 * - `@Action` 动作与 `$state` 绑定 → metaColor
 */
private fun highlightOpenUiProgram(
    src: String,
    componentColor: Color,
    stringColor: Color,
    metaColor: Color,
    commentColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(src)
    fun span(start: Int, end: Int, color: Color, bold: Boolean = false) {
        if (end > start) {
            addStyle(
                SpanStyle(color = color, fontWeight = if (bold) FontWeight.SemiBold else null),
                start,
                end,
            )
        }
    }
    var i = 0
    val n = src.length
    while (i < n) {
        val c = src[i]
        when {
            c == '"' || c == '\'' -> {
                var end = i + 1
                while (end < n) {
                    if (src[end] == '\\') {
                        end += 2
                        continue
                    }
                    if (src[end] == c) {
                        end++
                        break
                    }
                    end++
                }
                span(i, minOf(end, n), stringColor)
                i = end
            }
            c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                var end = i
                while (end < n && src[end] != '\n') end++
                span(i, end, commentColor)
                i = end
            }
            c == '@' || c == '$' -> {
                var end = i + 1
                while (end < n && (src[end].isLetterOrDigit() || src[end] == '_')) end++
                span(i, end, metaColor)
                i = end
            }
            c.isLetter() || c == '_' -> {
                var end = i + 1
                while (end < n && (src[end].isLetterOrDigit() || src[end] == '_')) end++
                val word = src.substring(i, end)
                var look = end
                while (look < n && src[look] == ' ') look++
                val isCall = look < n && src[look] == '('
                if ((c.isUpperCase() && isCall) || word == "Query" || word == "Mutation") {
                    span(i, end, componentColor, bold = true)
                }
                i = end
            }
            else -> i++
        }
    }
}
