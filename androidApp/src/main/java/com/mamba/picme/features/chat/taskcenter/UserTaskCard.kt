package com.mamba.picme.features.chat.taskcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.usertask.UserTask
import com.mamba.picme.domain.usertask.UserTaskAction
import com.mamba.picme.domain.usertask.UserTaskErrorCode
import com.mamba.picme.domain.usertask.UserTaskKind
import com.mamba.picme.domain.usertask.UserTaskMapping
import com.mamba.picme.domain.usertask.UserTaskStatus

/**
 * 用户任务卡片（spec §7）：原生 Compose 列表项（照引 HTML 卡 spec §7/§15——
 * 任务中心多卡并存，WebView 实例成本不划算）。
 * 动作按钮按 task.supportedActions 声明式渲染，无体系 if-else。
 */
@Composable
fun UserTaskCard(
    task: UserTask,
    onAction: (UserTaskAction) -> Unit,
    onOpenDestination: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = task.displayName ?: stringResource(
        when (task.kind) {
            UserTaskKind.TAG_SCAN -> R.string.user_task_title_tag_scan
            // 不会走到：下载必有 displayName（modelId）；兜底语义独立成串，不复用扫描标题
            UserTaskKind.MODEL_DOWNLOAD -> R.string.user_task_title_model_download
        }
    )
    val cardCd = stringResource(R.string.cd_user_task_card, title)
    Card(
        onClick = onOpenDestination,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = cardCd },
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(statusTextRes(task.status)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (UserTaskMapping.isActive(task.status)) {
                Spacer(modifier = Modifier.height(8.dp))
                val progress = task.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                task.progressText?.let { text ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = text, style = MaterialTheme.typography.bodySmall)
                }
            }
            // errorDetail 仅 PARTIAL_FAILURES 用于插值失败数；MODEL_UNAVAILABLE 不拼
            // （displayName 已是 modelId，再拼重复）
            val errorText = when (task.errorCode) {
                UserTaskErrorCode.PROCESS_TERMINATED ->
                    stringResource(R.string.user_task_error_process_terminated)
                UserTaskErrorCode.PARTIAL_FAILURES ->
                    stringResource(R.string.user_task_error_partial_failures, task.errorDetail ?: "")
                UserTaskErrorCode.MODEL_UNAVAILABLE ->
                    stringResource(R.string.user_task_error_model_unavailable)
                null -> null
            }
            errorText?.let { text ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (task.supportedActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    for (action in task.supportedActions.sortedBy { action -> action.ordinal }) {
                        TextButton(onClick = { onAction(action) }) {
                            Text(stringResource(actionTextRes(action)))
                        }
                    }
                }
            }
        }
    }
}

private fun statusTextRes(status: UserTaskStatus): Int = when (status) {
    UserTaskStatus.PENDING -> R.string.user_task_status_pending
    UserTaskStatus.RUNNING -> R.string.user_task_status_running
    UserTaskStatus.PAUSED -> R.string.user_task_status_paused
    UserTaskStatus.COMPLETED -> R.string.user_task_status_completed
    UserTaskStatus.FAILED -> R.string.user_task_status_failed
    UserTaskStatus.CANCELLED -> R.string.user_task_status_cancelled
}

private fun actionTextRes(action: UserTaskAction): Int = when (action) {
    UserTaskAction.PAUSE -> R.string.user_task_action_pause
    UserTaskAction.RESUME -> R.string.user_task_action_resume
    UserTaskAction.CANCEL -> R.string.user_task_action_cancel
    UserTaskAction.RETRY -> R.string.user_task_action_retry
}
