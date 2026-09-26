package com.mamba.picme.features.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import java.util.Locale

/**
 * 工程师任务卡（spec US-1~3/7~11）：状态 chip + 标题 + 阶段 + meta 行 + 状态动作区 + 展开明细。
 * 纯渲染无状态；审批动作回调由 ChatScreen 接到 ChatViewModel。
 * 审批按钮经 [actionsEnabled] 门控（回合进行中禁用，对齐 VM 侧 _isProcessing 守卫）。
 * [onViewAll] 非空时头部显示「查看全部」次入口（US-12 → 任务中心）。
 */
@Composable
fun EngineerTaskCard(
    task: EngineerTaskState,
    expanded: Boolean,
    actionsEnabled: Boolean = true,
    onViewAll: (() -> Unit)? = null,
    onToggleExpand: () -> Unit,
    onContinue: () -> Unit,
    onAbandon: () -> Unit,
    onDeliver: () -> Unit,
    onSkipDeliver: () -> Unit,
    onRetry: () -> Unit,
) {
    val cd = stringResource(R.string.cd_engineer_task_card)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = cd },
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineerTaskStatusChip(task)
                Spacer(Modifier.weight(1f))
                if (onViewAll != null) {
                    Text(
                        text = stringResource(R.string.task_center_view_all),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onViewAll)
                            .padding(end = 8.dp),
                    )
                }
                Text(
                    text = formatElapsed(task.updatedAtMs - task.startedAtMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(text = task.sourceText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val stage = task.stage
            if (task.status == EngineerTaskStatus.RUNNING && stage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chat_task_stage, stage),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val meta = taskMetaText(task)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(text = meta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (task.status) {
                EngineerTaskStatus.AWAITING_CONTINUE -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.claude_truncated),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onContinue, enabled = actionsEnabled) { Text(stringResource(R.string.claude_continue), fontSize = 13.sp) }
                        TextButton(onClick = onAbandon, enabled = actionsEnabled) { Text(stringResource(R.string.chat_task_abandon), fontSize = 13.sp) }
                    }
                }
                EngineerTaskStatus.AWAITING_DELIVER -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.chat_task_deliver_summary, task.fileChangeCount),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    task.errorSummary?.let { deliverError ->
                        Text(text = deliverError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDeliver, enabled = actionsEnabled) { Text(stringResource(R.string.claude_deliver_mode_push), fontSize = 13.sp) }
                        TextButton(onClick = onSkipDeliver, enabled = actionsEnabled) { Text(stringResource(R.string.chat_task_skip_deliver), fontSize = 13.sp) }
                    }
                }
                EngineerTaskStatus.COMPLETED -> {
                    task.resultSummary?.takeIf { summary -> summary.isNotBlank() }?.let { summary ->
                        Spacer(Modifier.height(4.dp))
                        Text(text = summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                EngineerTaskStatus.FAILED -> {
                    Spacer(Modifier.height(6.dp))
                    task.errorSummary?.let { errorText ->
                        Text(text = errorText, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onRetry, enabled = actionsEnabled) { Text(stringResource(R.string.chat_task_retry), fontSize = 13.sp) }
                }
                EngineerTaskStatus.RUNNING -> Unit
            }
            if (task.recentStages.isNotEmpty()) {
                TextButton(onClick = onToggleExpand) {
                    Text(
                        text = stringResource(if (expanded) R.string.chat_task_collapse else R.string.chat_task_expand),
                        fontSize = 12.sp,
                    )
                }
                if (expanded) {
                    task.recentStages.asReversed().forEach { stageLabel ->
                        Text(
                            text = "· $stageLabel",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EngineerTaskStatusChip(task: EngineerTaskState) {
    val labelRes = when (task.resolution) {
        EngineerTaskResolution.CONTINUED -> R.string.chat_task_resolved_continued
        EngineerTaskResolution.ABANDONED -> R.string.chat_task_resolved_abandoned
        EngineerTaskResolution.DELIVERED -> R.string.chat_task_resolved_delivered
        EngineerTaskResolution.DELIVER_SKIPPED -> R.string.chat_task_resolved_skipped
        null -> when (task.status) {
            EngineerTaskStatus.RUNNING -> R.string.chat_task_status_running
            EngineerTaskStatus.AWAITING_CONTINUE, EngineerTaskStatus.AWAITING_DELIVER -> R.string.chat_task_status_awaiting
            EngineerTaskStatus.COMPLETED -> R.string.chat_task_status_completed
            EngineerTaskStatus.FAILED -> R.string.chat_task_status_failed
        }
    }
    val color = when {
        task.resolution != null || task.status == EngineerTaskStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
        task.status == EngineerTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = color,
        )
    }
}

@Composable
internal fun taskMetaText(task: EngineerTaskState): String {
    val parts = mutableListOf<String>()
    if (task.turns > 0) parts += stringResource(R.string.chat_task_meta_turns, task.turns)
    task.costCents?.let { cents -> parts += stringResource(R.string.chat_task_meta_cost, cents / 100.0) }
    if (task.fileChangeCount > 0) parts += stringResource(R.string.chat_task_meta_files, task.fileChangeCount)
    return parts.joinToString(" · ")
}

internal fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.ROOT, totalSec / 60, totalSec % 60)
}
