package com.mamba.picme.features.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppColors
import com.mamba.picme.data.remote.picme.IssueReportClient
import kotlinx.coroutines.launch

/**
 * 设置页「其他」分组的「上报问题」条目：行 + 对话框 + 提交反馈全自包含
 * （数据获取模式对齐 [ServerAuthSection]：经 Application 容器取仓储，不经 ViewModel）。
 */
@Composable
internal fun ReportIssueEntry() {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val repo = app.container.userPreferencesRepository
    val scope = rememberCoroutineScope()
    val client = remember { IssueReportClient() }

    val serverToken by repo.serverAuthTokenFlow.collectAsState(initial = "")

    var showDialog by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<IssueReportState>(IssueReportState.Idle) }

    // 提交结果反馈：成功/失败 Toast 后复位状态机
    LaunchedEffect(state) {
        when (val s = state) {
            is IssueReportState.Success -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.report_issue_success, s.issueId),
                    Toast.LENGTH_SHORT,
                ).show()
                showDialog = false
                state = IssueReportState.Idle
            }
            is IssueReportState.Error -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.report_issue_error, s.message),
                    Toast.LENGTH_LONG,
                ).show()
                state = IssueReportState.Idle
            }
            else -> {}
        }
    }

    SettingsListRow(
        title = stringResource(R.string.report_issue_title),
        icon = Icons.Outlined.BugReport,
        iconBlockColor = AppColors.vibrantPink,
        onClick = { showDialog = true },
    )

    if (showDialog) {
        ReportIssueDialog(
            state = state,
            isGuest = serverToken.isBlank(),
            onDismiss = { showDialog = false },
            onSubmit = { category, title, description ->
                val token = serverToken
                if (token.isBlank()) {
                    state = IssueReportState.Error(context.getString(R.string.report_issue_guest_not_allowed))
                    return@ReportIssueDialog
                }
                if (title.isBlank()) {
                    state = IssueReportState.Error(context.getString(R.string.chat_report_title_required))
                    return@ReportIssueDialog
                }
                state = IssueReportState.Submitting
                scope.launch {
                    val result = client.submit(token, category, title, description)
                    state = result.fold(
                        onSuccess = { IssueReportState.Success(it) },
                        onFailure = {
                            IssueReportState.Error(
                                it.message ?: context.getString(R.string.chat_report_failed),
                            )
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun ReportIssueDialog(
    state: IssueReportState,
    isGuest: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (category: String, title: String, description: String) -> Unit,
) {
    var category by remember { mutableStateOf("other") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val submitting = state is IssueReportState.Submitting
    val categories = listOf("crash", "bug", "ai", "other")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_issue_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isGuest) {
                    Text(
                        text = stringResource(R.string.report_issue_guest_not_allowed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.report_issue_category_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(stringResource(categoryLabelRes(c))) },
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.report_issue_title_label)) },
                    placeholder = { Text(stringResource(R.string.report_issue_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGuest,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.report_issue_description_label)) },
                    placeholder = { Text(stringResource(R.string.report_issue_description_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGuest,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(category, title, description) },
                enabled = !submitting && !isGuest && title.isNotBlank(),
            ) {
                if (submitting) {
                    // 保持按钮宽度稳定，仅显示 "提交中..."
                    Text(stringResource(R.string.report_issue_submit) + "…")
                } else {
                    Text(stringResource(R.string.report_issue_submit))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.report_issue_cancel))
            }
        },
    )
}

@StringRes
private fun categoryLabelRes(category: String): Int = when (category) {
    "crash" -> R.string.report_issue_category_crash
    "bug" -> R.string.report_issue_category_bug
    "ai" -> R.string.report_issue_category_ai
    else -> R.string.report_issue_category_other
}
