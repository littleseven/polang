package com.mamba.picme.features.gallery.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mamba.picme.R
import com.mamba.picme.util.permission.BackgroundScanGuard

/**
 * 后台扫描保活相关 UI(常驻提示条 + 启动引导弹窗)。
 *
 * 设计要点:HyperOS 等 ROM 退后台会冻结进程,扫描暂停。引导用户开启
 * 「电池白名单 + 通知 + MIUI 自启动」是根治手段,详见 [BackgroundScanGuard]。
 */

/**
 * 常驻提示条:扫描控制页顶部,检测到保活缺失项时柔和提示。
 * 点击跳转第一项缺失的修复页。
 */
@Composable
fun BackgroundScanGuardBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val issues by produceState<List<BackgroundScanGuard.Issue>>(initialValue = emptyList(), context) {
        value = runCatching { BackgroundScanGuard.unacknowledgedIssues(context.applicationContext) }
            .getOrDefault(emptyList())
            // 先检查再提醒（2026-10-01）：MIUI 自启动无读取 API（恒报）不入常驻 Banner；
            // 电池/通知按系统事实 + 用户按项确认（确认过即隐，回归缺失自动复现）
            .filter { it.type != BackgroundScanGuard.IssueType.MIUI_AUTOSTART }
    }
    if (issues.isEmpty()) return
    val bannerText = stringResource(R.string.bg_scan_guard_banner_text)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { issues.first().openFix(context) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))
    ) {
        Text(
            text = bannerText,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF856404)
        )
    }
}

/**
 * 启动扫描前的保活引导弹窗（checklist 范式）。
 *
 * 每个缺失项一行：状态勾 + 标题 + 目标设置页具体操作指引，点击行直达该项修复页；
 * 从设置页返回时 ON_RESUME 复查（电池/通知按系统事实打勾，MIUI 自启动按会话级
 * 「已去确认」），全部处理后自动收弹窗并继续被暂挂的扫描。
 *
 * @param issues 缺失项(非空时调用方才渲染本弹窗)
 * @param onContinue 继续扫描（用户点「仍然扫描」或全项处理完自动触发，调用方执行被暂挂的启动动作）
 * @param onDontRemind 用户点「不再提醒」(调用方持久化后不再弹)
 */
@Composable
fun BackgroundScanGuardDialog(
    issues: List<BackgroundScanGuard.Issue>,
    onContinue: () -> Unit,
    onDontRemind: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 最新 diagnose 仍缺失的项（ON_RESUME 复查刷新；限定在弹窗打开时的项集内）
    var stillMissing by remember(issues) {
        mutableStateOf(issues.map { issue -> issue.type }.toSet())
    }
    // MIUI 自启动无读取 API：用户点击进入过确认页即视为已处理（会话级）
    var autostartVisited by remember { mutableStateOf(false) }

    fun recheck() {
        stillMissing = runCatching { BackgroundScanGuard.diagnose(context.applicationContext) }
            .getOrDefault(emptyList())
            .map { issue -> issue.type }
            .filterTo(HashSet()) { type -> issues.any { issue -> issue.type == type } }
    }

    // 从系统设置页返回时复查打勾
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allResolved = issues.all { issue ->
        BackgroundScanGuard.isRowResolved(issue.type, stillMissing.contains(issue.type), autostartVisited)
    }
    // 全项处理完自动继续扫描（兑现用户点「开始扫描」的原始意图）；
    // 同时按项落确认——MIUI 自启动（按「访问过确认页」）从此不再每次弹；
    // 电池/通知此时已恢复 OK，其确认会被惰性清理自动撤销，未来回归缺失仍会提醒
    LaunchedEffect(allResolved) {
        if (allResolved) {
            BackgroundScanGuard.acknowledgeIssues(
                context.applicationContext,
                issues.map { issue -> issue.type }
            )
            onContinue()
        }
    }

    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text(stringResource(R.string.bg_scan_guard_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.bg_scan_guard_dialog_message))
                Spacer(Modifier.height(12.dp))
                for (issue in issues) {
                    val resolved = BackgroundScanGuard.isRowResolved(
                        issue.type, stillMissing.contains(issue.type), autostartVisited
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !resolved) {
                                if (issue.type == BackgroundScanGuard.IssueType.MIUI_AUTOSTART) {
                                    autostartVisited = true
                                }
                                issue.openFix(context)
                            }
                            .padding(vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = if (resolved) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (resolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(issue.titleRes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(issue.hintRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.bg_scan_guard_action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDontRemind) {
                Text(stringResource(R.string.bg_scan_guard_action_dont_remind))
            }
        }
    )
}
