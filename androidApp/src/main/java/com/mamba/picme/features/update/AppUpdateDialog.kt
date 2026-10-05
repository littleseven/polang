package com.mamba.picme.features.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

/**
 * OTA 更新对话框：穷举渲染 AppUpdateState 各态。
 * Available → 立即更新/下次再说；ReadyToInstall → 安装；Failed → 重试/取消；Idle → 不渲染。
 * 下载无应用内进度态——后台下载由系统 DownloadManager 通知承担。
 */
@Composable
fun AppUpdateDialog(controller: AppUpdateController) {
    val state by controller.state.collectAsState()
    when (val current = state) {
        is AppUpdateState.Idle -> Unit
        is AppUpdateState.Available -> AvailableDialog(
            info = current,
            onUpdate = { controller.startDownload() },
            onLater = { controller.dismiss() },
        )
        is AppUpdateState.ReadyToInstall -> ReadyToInstallDialog(
            state = current,
            onInstall = { controller.installNow() },
            onLater = { controller.dismiss() },
        )
        is AppUpdateState.Failed -> FailedDialog(
            reason = current.reason,
            onRetry = { controller.startDownload() },
            onCancel = { controller.dismiss() },
        )
    }
}

@Composable
private fun AvailableDialog(
    info: AppUpdateState.Available,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.ota_update_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.ota_update_message,
                        info.info.versionName,
                        formatSize(info.info.sizeBytes),
                    ),
                )
                if (info.info.changelog.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.ota_changelog_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(info.info.changelog, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text(stringResource(R.string.ota_update_now)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.ota_update_later)) }
        },
    )
}

@Composable
private fun ReadyToInstallDialog(
    state: AppUpdateState.ReadyToInstall,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.ota_update_title)) },
        text = { Text(stringResource(R.string.ota_ready_to_install)) },
        confirmButton = {
            TextButton(onClick = onInstall) { Text(stringResource(R.string.ota_install)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.ota_update_later)) }
        },
    )
}

@Composable
private fun FailedDialog(
    reason: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.ota_update_title)) },
        text = { Text(stringResource(R.string.ota_download_failed, reason)) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.ota_retry)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.ota_cancel)) }
        },
    )
}

private fun formatSize(sizeBytes: Long): String =
    if (sizeBytes <= 0) "—" else "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
