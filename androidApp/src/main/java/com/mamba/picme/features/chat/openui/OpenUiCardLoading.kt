package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 卡解析/装载中的占位指示（替代已退役 androidx material3-a2ui 的
 * A2uiSurfaceDefaults.LoadingIndicator，2026-11-05；指标对齐原件：
 * 16dp 边距居中 + 库默认尺寸 CircularProgressIndicator(primary)）。
 */
@Composable
internal fun OpenUiCardLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
