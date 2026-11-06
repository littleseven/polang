package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.Spacing

/**
 * 降级占位卡（US-6 对偶）：handle 缺失/prepare 失败时顶替整张卡。
 * 两条静态 label 走五语资源；fallbackSummary 是云端动态内容（LLM 写的句子），[I18N] 豁免。
 * （自 features.chat.a2ui.A2uiCardDegraded 迁入改名，2026-11-05 A2UI 退役；
 * 字符串资源 2026-11-06 更名 chat_openui_card_*。）
 */
@Composable
fun OpenUiCardDegraded(summary: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), shape = AppShapes.card) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.chat_openui_card_unavailable),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.chat_openui_card_data_elsewhere, summary),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
