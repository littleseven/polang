package com.mamba.picme.features.chat.taskcenter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamba.picme.R
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.features.chat.components.EngineerTaskStatusChip
import com.mamba.picme.features.chat.components.formatElapsed
import com.mamba.picme.features.chat.components.taskMetaText
import com.mamba.picme.features.chat.engineer.TaskCenterItem
import com.mamba.picme.features.chat.engineer.TaskCenterPartition
import com.mamba.picme.features.common.topbar.AppTopBar

/**
 * 任务中心页（spec US-12~16 + D7）：跨会话聚合工程师任务，分区「进行中/历史」。
 * 只读管理 + 审批动作（继续/交付/重试/暂不/到此为止），不做任务编辑/删除。
 * 审批动作经 Activity 级共享 [ChatViewModel] 的 FromCenter 入口（prime 补种 + sessionId 显式落库）。
 *
 * @param onOpenTaskInChat 回 chat 锚定对应任务卡（US-15）：(sessionId, taskId) → 由 Activity 层驱动切页+滚动
 */
@Composable
fun TaskCenterScreen(
    taskCenterViewModel: TaskCenterViewModel,
    chatViewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onOpenTaskInChat: (sessionId: String, taskId: String) -> Unit,
) {
    val taskList by taskCenterViewModel.taskList.collectAsStateWithLifecycle()
    val isProcessing by chatViewModel.isProcessing.collectAsStateWithLifecycle()
    val actionInFlight by chatViewModel.engineerActionInFlight.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.task_center_title),
                onBack = onNavigateBack,
            )
        },
    ) { innerPadding ->
        val list = taskList
        when {
            // 首查未回：仅顶栏（防空态文案闪现一帧）
            list == null -> Unit
            list.active.isEmpty() && list.history.isEmpty() -> TaskCenterEmpty(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (list.active.isNotEmpty()) {
                    item(key = "header_active") {
                        TaskCenterSectionHeader(stringResource(R.string.task_center_section_active))
                    }
                    items(list.active, key = { item -> "active_${item.task.taskId}" }) { item ->
                        TaskCenterListItem(
                            item = item,
                            actionsEnabled = !isProcessing && item.task.taskId !in actionInFlight,
                            onClick = { onOpenTaskInChat(item.sessionId, item.task.taskId) },
                            onContinue = {
                                chatViewModel.continueEngineerTaskFromCenter(item.sessionId, item.task.taskId)
                                onOpenTaskInChat(item.sessionId, item.task.taskId)
                            },
                            onAbandon = {
                                chatViewModel.abandonEngineerTaskFromCenter(item.sessionId, item.task.taskId)
                            },
                            onDeliver = {
                                chatViewModel.deliverEngineerTaskFromCenter(item.sessionId, item.task.taskId)
                            },
                            onSkipDeliver = {
                                chatViewModel.skipEngineerDeliverFromCenter(item.sessionId, item.task.taskId)
                            },
                            onRetry = {
                                chatViewModel.retryEngineerTaskFromCenter(item.sessionId, item.task.taskId)
                                onOpenTaskInChat(item.sessionId, item.task.taskId)
                            },
                        )
                    }
                }
                if (list.history.isNotEmpty()) {
                    item(key = "header_history") {
                        TaskCenterSectionHeader(stringResource(R.string.task_center_section_history))
                    }
                    items(list.history, key = { item -> "history_${item.task.taskId}" }) { item ->
                        // 历史区只读（D7）：仅 FAILED 保留「重试」动作，审批回调缺省为 null（不渲染）
                        TaskCenterListItem(
                            item = item,
                            actionsEnabled = !isProcessing && item.task.taskId !in actionInFlight,
                            onClick = { onOpenTaskInChat(item.sessionId, item.task.taskId) },
                            onRetry = {
                                chatViewModel.retryEngineerTaskFromCenter(item.sessionId, item.task.taskId)
                                onOpenTaskInChat(item.sessionId, item.task.taskId)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCenterSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TaskCenterEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.task_center_empty),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 任务中心列表项（US-14）：任务卡同构紧凑形态——标题 + 状态 chip + meta 行 + 进度/结果摘要。
 * 审批中项高亮（描边）并带动作按钮；整卡点击回 chat 锚定（US-15）。
 * 动作回调可空：null = 该动作不可用（历史区只读），对应按钮不渲染。
 */
@Composable
private fun TaskCenterListItem(
    item: TaskCenterItem,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onContinue: (() -> Unit)? = null,
    onAbandon: (() -> Unit)? = null,
    onDeliver: (() -> Unit)? = null,
    onSkipDeliver: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val task = item.task
    val awaiting = TaskCenterPartition.isAwaitingApproval(task)
    val cd = stringResource(R.string.cd_engineer_task_card)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = cd }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        border = if (awaiting) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineerTaskStatusChip(task)
                item.sessionTitle?.takeIf { title -> title.isNotBlank() }?.let { title ->
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatElapsed(task.updatedAtMs - task.startedAtMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TaskProgressSummary(task)
            val meta = taskMetaText(task)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(text = meta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TaskActionsRow(
                task = task,
                actionsEnabled = actionsEnabled,
                onContinue = onContinue,
                onAbandon = onAbandon,
                onDeliver = onDeliver,
                onSkipDeliver = onSkipDeliver,
                onRetry = onRetry,
            )
        }
    }
}

/** 进度/结果摘要行：RUNNING 显示当前阶段，其余各态显示对应摘要（对齐 EngineerTaskCard 各态文案）。 */
@Composable
private fun TaskProgressSummary(task: EngineerTaskState) {
    when (task.status) {
        EngineerTaskStatus.RUNNING -> task.stage?.let { stage ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chat_task_stage, stage),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        EngineerTaskStatus.AWAITING_CONTINUE -> {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.claude_truncated),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        }
        EngineerTaskStatus.COMPLETED -> {
            task.resultSummary?.takeIf { summary -> summary.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        EngineerTaskStatus.FAILED -> {
            task.errorSummary?.let { errorText ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = errorText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 状态动作区（D7：仅审批动作）：待审批给决策按钮，失败给重试，其余只读；回调为 null 的动作不渲染。 */
@Composable
private fun TaskActionsRow(
    task: EngineerTaskState,
    actionsEnabled: Boolean,
    onContinue: (() -> Unit)?,
    onAbandon: (() -> Unit)?,
    onDeliver: (() -> Unit)?,
    onSkipDeliver: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    when {
        task.status == EngineerTaskStatus.AWAITING_CONTINUE && task.resolution == null &&
            onContinue != null && onAbandon != null -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onContinue, enabled = actionsEnabled) {
                    Text(stringResource(R.string.claude_continue), fontSize = 13.sp)
                }
                TextButton(onClick = onAbandon, enabled = actionsEnabled) {
                    Text(stringResource(R.string.chat_task_abandon), fontSize = 13.sp)
                }
            }
        }
        task.status == EngineerTaskStatus.AWAITING_DELIVER && task.resolution == null &&
            onDeliver != null && onSkipDeliver != null -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDeliver, enabled = actionsEnabled) {
                    Text(stringResource(R.string.claude_deliver_mode_push), fontSize = 13.sp)
                }
                TextButton(onClick = onSkipDeliver, enabled = actionsEnabled) {
                    Text(stringResource(R.string.chat_task_skip_deliver), fontSize = 13.sp)
                }
            }
        }
        task.status == EngineerTaskStatus.FAILED && onRetry != null -> {
            TextButton(onClick = onRetry, enabled = actionsEnabled) {
                Text(stringResource(R.string.chat_task_retry), fontSize = 13.sp)
            }
        }
    }
}
