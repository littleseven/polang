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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.mamba.picme.core.designsystem.A2uiCardTokens
import com.mamba.picme.core.designsystem.Spacing

/**
 * OpenUI 蓝图调试器（debug 包专用，BuildConfig.DEBUG 门控，与 A2uiBlueprintDebugger 对偶）。
 *
 * 徽标内容全为协议标识符（OpenUI v1.0 · partId），无自然语言，[I18N] 豁免。
 * 查看器直接展示 program 原文（OpenUI Lang 程序本身即可读文本，无需 JSON 美化）。
 */

/** Debug 徽标 overlay：右上角胶囊 `OpenUI v1.0 · <partId>`，点击开程序原文查看器。 */
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

/** 程序原文查看器：partId 标题 + program 全文（可滚动）+ 复制全部。 */
@Composable
private fun OpenUiProgramViewerDialog(
    partId: String,
    program: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
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
                    text = program,
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
