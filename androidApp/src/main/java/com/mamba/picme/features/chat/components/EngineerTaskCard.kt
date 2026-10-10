package com.mamba.picme.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
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
import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import com.mamba.picme.domain.chat.HtmlCardMeta
import com.mamba.picme.features.chat.HtmlCard
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskHtml
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskPalette
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskTexts
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskThrottle
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 工程师任务卡（2026-09-27 H2 HTML 化，spec《HTML 卡双形态》§7 对 9-25 spec D2 的渲染层修订）：
 * **L1 模板 HTML**（[EngineerTaskHtml] 组装，端侧模板 + 状态 JSON，样式权威在 App）经 [HtmlCard]
 * 双形态渲染——折叠/展开为 INLINE（点击整卡切换），展开超 1.0 屏自动升格 FULLPAGE 预览 → 全屏查看器。
 *
 * 卡片为**上下两分区单卡容器**（2026-09-27 Muse 修订，外置动作条方案作废；design taskcard 四帧）：
 * 上半 = HTML 信息区（r16 卡容器内），下半 = **原生 Compose 动作区**（surfaceContainerHigh 底 + hairline
 * 分隔，零 JS 桥接红线不动，动作状态与卡片同源）——running=整宽胶囊[停止]、approval=radio 选项列表
 * （继续执行/到此为止）、deliver=[暂不|推送交付]双胶囊、failed=[重试]主胶囊、completed 无动作区。
 *
 * - 500ms 合帧：SSE 状态微更新经 [rememberThrottledTask] 节流（状态/裁决跃迁即时），
 *   WebView 按全文判等重载——无变化帧零开销；
 * - displayMode **粘滞但不落库**（spec §10：任务卡 HTML 是渲染期产物）——仅 FULLPAGE 方向粘滞
 *   （跨合帧不回缩），INLINE 每帧重判保持可升格；
 * - 测高缓存 key 形态域（`taskcard:<id>:exp|col`）——状态重生成不失效缓存，避免占位→测高跳变；
 * - 组装失败（模板异常）→ [EngineerTaskFallbackCard] 原生兜底（spec §9.3），动作区不受影响。
 *
 * 纯渲染无状态；动作回调由 ChatScreen 接到 ChatViewModel（[actionsEnabled] 门控对齐 VM 侧守卫）。
 * 任务中心列表项继续用原生紧凑形态（同页多卡并存，WebView 实例成本不划算，spec §7）。
 */
@Suppress("LongParameterList")
@Composable
fun EngineerTaskCard(
    task: EngineerTaskState,
    expanded: Boolean,
    viewportHeightDp: Int,
    previewViewportHeightDp: Int,
    isListScrolling: Boolean,
    actionsEnabled: Boolean = true,
    onToggleExpand: () -> Unit,
    onOpenFullpage: (html: String, title: String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onAbandon: () -> Unit,
    onDeliver: () -> Unit,
    onSkipDeliver: () -> Unit,
    onRetry: () -> Unit,
) {
    val throttled = rememberThrottledTask(task)
    val palette = rememberEngineerTaskPalette()
    val texts = engineerTaskTexts(throttled)
    // 组装失败兜底（spec §9.3）：模板/插值异常 → 原生摘要卡，动作区照常
    val html = remember(throttled, expanded, palette, texts) {
        runCatching { EngineerTaskHtml.assemble(throttled, expanded, palette, texts) }.getOrNull()
    }
    val hasBar = throttled.status == EngineerTaskStatus.RUNNING ||
        throttled.status == EngineerTaskStatus.AWAITING_CONTINUE ||
        throttled.status == EngineerTaskStatus.AWAITING_DELIVER ||
        throttled.status == EngineerTaskStatus.FAILED
    // 形态终判粘滞（仅内存，不落库——spec §10）：FULLPAGE 方向跨合帧保持，INLINE 每帧重判可升格
    var resolvedMode by remember(throttled.taskId, expanded) { mutableStateOf<HtmlCardDisplayMode?>(null) }
    val cd = stringResource(R.string.cd_engineer_task_card)
    // 单卡容器（两分区改版）：上 = HTML 信息区，下 = 原生动作区，外层统一 r16 裁剪
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = cd },
    ) {
        Column {
            if (html == null) {
                EngineerTaskFallbackCard(throttled)
            } else {
                HtmlCard(
                    html = html,
                    // FULLPAGE 粘滞：html 每次重生成都以 meta 复位形态；INLINE 传 null 走重测
                    meta = if (resolvedMode == HtmlCardDisplayMode.FULLPAGE) {
                        HtmlCardMeta(displayMode = HtmlCardDisplayMode.FULLPAGE)
                    } else {
                        null
                    },
                    cacheKey = "taskcard:${throttled.taskId}:${if (expanded) "exp" else "col"}",
                    viewportHeightDp = viewportHeightDp,
                    previewViewportHeightDp = previewViewportHeightDp,
                    isListScrolling = isListScrolling,
                    onOpenFullpage = { onOpenFullpage(html, throttled.sourceText) },
                    onDisplayModeResolved = { mode, _ -> resolvedMode = mode },
                    cardColor = MaterialTheme.colorScheme.surfaceContainer,
                    cardShape = if (hasBar) {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                    } else {
                        RoundedCornerShape(16.dp)
                    },
                    cardTonalElevation = 0.dp,
                    contentPadding = 0.dp,
                    onInlineCardTap = onToggleExpand,
                    inlineTapLabel = stringResource(
                        if (expanded) R.string.chat_card_collapse_hint else R.string.chat_card_expand_hint
                    ),
                )
            }
            if (hasBar) {
                EngineerTaskActionZone(
                    task = throttled,
                    actionsEnabled = actionsEnabled,
                    onStop = onStop,
                    onContinue = onContinue,
                    onAbandon = onAbandon,
                    onDeliver = onDeliver,
                    onSkipDeliver = onSkipDeliver,
                    onRetry = onRetry,
                )
            }
        }
    }
}

/**
 * 500ms 合帧（spec §7）：SSE 状态变化 → 节流重渲染。状态/裁决跃迁（[EngineerTaskThrottle.isImmediate]）
 * 即时渲染；RUNNING 字段微更新（stage/轮次/成本/阶段列表）延迟合帧——LaunchedEffect 以 task 为 key，
 * 新帧到达取消旧延迟（last-wins debounce）；首次出现经 remember 初始化即时呈现。
 */
@Composable
private fun rememberThrottledTask(task: EngineerTaskState): EngineerTaskState {
    val throttled = remember(task.taskId) { mutableStateOf(task) }
    LaunchedEffect(task) {
        if (EngineerTaskThrottle.isImmediate(throttled.value, task)) {
            throttled.value = task
        } else {
            delay(EngineerTaskHtml.FRAME_MS)
            throttled.value = task
        }
    }
    return throttled.value
}

/** 调色板：MaterialTheme 运行时取色（≡ design-tokens，dynamic color 关闭）→ CSS hex。 */
@Composable
private fun rememberEngineerTaskPalette(): EngineerTaskPalette {
    val scheme = MaterialTheme.colorScheme
    return EngineerTaskPalette(
        cardBg = scheme.surfaceContainer.toCssHex(),
        iconBlockBg = scheme.surfaceContainerHigh.toCssHex(),
        primary = scheme.primary.toCssHex(),
        onPrimary = scheme.onPrimary.toCssHex(),
        error = scheme.error.toCssHex(),
        onError = scheme.onError.toCssHex(),
        onSurface = scheme.onSurface.toCssHex(),
        onSurfaceVariant = scheme.onSurfaceVariant.toCssHex(),
        surfaceVariant = scheme.surfaceVariant.toCssHex(),
        outlineVariant = scheme.outlineVariant.toCssHex(),
        neutralChipBg = scheme.surfaceContainerHigh.toCssHex(),
        neutralChipFg = scheme.onSurfaceVariant.toCssHex(),
    )
}

private const val CSS_RGB_MASK = 0xFFFFFF

private fun androidx.compose.ui.graphics.Color.toCssHex(): String =
    String.format(Locale.ROOT, "#%06X", CSS_RGB_MASK and toArgb())

/** 模板文案：stringResource 预解析（含数值格式化），组装器只做拼接与转义；null = 数据缺省隐藏。 */
@Composable
private fun engineerTaskTexts(task: EngineerTaskState): EngineerTaskTexts {
    val turnsText = if (task.turns > 0) stringResource(R.string.chat_task_meta_turns, task.turns) else null
    val costText = task.costCents?.let { cents -> stringResource(R.string.chat_task_meta_cost, cents / 100.0) }
    return EngineerTaskTexts(
        titlePrefix = stringResource(R.string.chat_task_title_prefix),
        badgeLabel = stringResource(R.string.chat_task_badge),
        chipRunning = stringResource(R.string.chat_task_status_running),
        chipAwaiting = stringResource(R.string.chat_task_status_awaiting),
        chipCompleted = stringResource(R.string.chat_task_status_completed),
        chipFailed = stringResource(R.string.chat_task_status_failed),
        chipResolvedContinued = stringResource(R.string.chat_task_resolved_continued),
        chipResolvedAbandoned = stringResource(R.string.chat_task_resolved_abandoned),
        chipResolvedDelivered = stringResource(R.string.chat_task_resolved_delivered),
        chipResolvedSkipped = stringResource(R.string.chat_task_resolved_skipped),
        expandHint = stringResource(R.string.chat_card_expand_hint),
        collapseHint = stringResource(R.string.chat_card_collapse_hint),
        recentEventsCaption = stringResource(R.string.chat_task_recent_events),
        metaTurns = turnsText,
        metaElapsed = if (task.updatedAtMs > task.startedAtMs) {
            stringResource(R.string.chat_task_meta_elapsed, formatElapsed(task.updatedAtMs - task.startedAtMs))
        } else {
            null
        },
        metaCost = costText,
        reasonPrefix = stringResource(R.string.chat_task_reason_prefix),
        usedPrefix = stringResource(R.string.chat_task_used_prefix),
        summaryPrefix = stringResource(R.string.chat_task_summary_prefix),
        usedTurns = turnsText,
        usedCost = costText,
        filesChanged = if (task.fileChangeCount > 0) {
            stringResource(R.string.chat_task_meta_files, task.fileChangeCount)
        } else {
            null
        },
        deliverSummary = if (task.status == EngineerTaskStatus.AWAITING_DELIVER) {
            stringResource(R.string.chat_task_deliver_summary, task.fileChangeCount)
        } else {
            null
        },
    )
}

/**
 * 卡内原生动作区（spec §7 2026-09-27 Muse 修订；design taskcard/collapsed、approval、done 帧）：
 * surfaceContainerHigh 底 + 顶 hairline（outlineVariant），由外层卡容器统一裁剪底圆角。
 * 四态映射：running=整宽胶囊[停止]、approval=radio 选项列表（继续执行/到此为止）、
 * deliver=[暂不|推送交付]双胶囊、failed=[重试]主胶囊。
 */
@Suppress("LongParameterList")
@Composable
private fun EngineerTaskActionZone(
    task: EngineerTaskState,
    actionsEnabled: Boolean,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onAbandon: () -> Unit,
    onDeliver: () -> Unit,
    onSkipDeliver: () -> Unit,
    onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(
                if (task.status == EngineerTaskStatus.AWAITING_CONTINUE) 6.dp else 8.dp
            ),
            modifier = Modifier.padding(
                start = 16.dp,
                end = 16.dp,
                top = 10.dp,
                bottom = if (task.status == EngineerTaskStatus.AWAITING_CONTINUE) 12.dp else 14.dp,
            ),
        ) {
            when (task.status) {
                EngineerTaskStatus.RUNNING -> TaskCapsuleButton(
                    label = stringResource(R.string.chat_task_stop),
                    primary = false,
                    enabled = actionsEnabled,
                    onClick = onStop,
                )
                EngineerTaskStatus.AWAITING_CONTINUE -> {
                    Text(
                        text = stringResource(R.string.chat_task_choose_next),
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    RadioOptionRow(
                        label = stringResource(R.string.chat_task_option_continue),
                        desc = stringResource(R.string.chat_task_option_continue_desc),
                        enabled = actionsEnabled,
                        onClick = onContinue,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(scheme.outlineVariant),
                    )
                    RadioOptionRow(
                        label = stringResource(R.string.chat_task_abandon),
                        desc = stringResource(R.string.chat_task_option_abandon_desc),
                        enabled = actionsEnabled,
                        onClick = onAbandon,
                    )
                }
                EngineerTaskStatus.AWAITING_DELIVER -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TaskCapsuleButton(
                        label = stringResource(R.string.chat_task_skip_deliver),
                        primary = false,
                        enabled = actionsEnabled,
                        onClick = onSkipDeliver,
                        modifier = Modifier.weight(1f),
                    )
                    TaskCapsuleButton(
                        label = stringResource(R.string.chat_task_deliver_push),
                        primary = true,
                        enabled = actionsEnabled,
                        onClick = onDeliver,
                        modifier = Modifier.weight(1f),
                    )
                }
                EngineerTaskStatus.FAILED -> TaskCapsuleButton(
                    label = stringResource(R.string.chat_task_retry),
                    primary = true,
                    enabled = actionsEnabled,
                    onClick = onRetry,
                )
                EngineerTaskStatus.COMPLETED -> Unit
            }
        }
    }
}

/** 整宽胶囊按钮（design BtnStop/BtnLater/BtnDeliver：h36 r18，12sp SemiBold 居中）。 */
@Composable
private fun TaskCapsuleButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.5f
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background((if (primary) scheme.primary else scheme.surfaceVariant).copy(alpha = alpha))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = (if (primary) scheme.onPrimary else scheme.onSurface).copy(alpha = alpha),
        )
    }
}

/** 审批选项行（design OptionRow：18dp 描边圆 + 12sp SemiBold 标题 + 10sp 描述，整行可点）。 */
@Composable
private fun RadioOptionRow(
    label: String,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.5f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(scheme.surfaceContainer)
                .border(1.dp, scheme.onSurfaceVariant.copy(alpha = alpha), CircleShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface.copy(alpha = alpha),
            )
            Text(
                text = desc,
                fontSize = 10.sp,
                color = scheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

/** 模板组装失败的纯文本兜底卡（spec §9.3：状态 + 标题；动作区由父层照常渲染）。 */
@Composable
private fun EngineerTaskFallbackCard(task: EngineerTaskState) {
    // 直角矩形：由外层卡容器统一裁剪圆角（两分区改版后与动作区无缝衔接）
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            EngineerTaskStatusChip(task)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = task.sourceText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            task.stage?.takeIf { stage -> stage.isNotBlank() }?.let { stage ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    // 实心 chip（对齐 design taskcard 四帧与 HTML 模板）：resolved=中性底，failed=error 底，其余=primary 底
    val (bg, fg) = when {
        task.resolution != null ->
            MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        task.status == EngineerTaskStatus.FAILED ->
            MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        else ->
            MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

@Composable
internal fun taskMetaText(task: EngineerTaskState): String {
    val parts = mutableListOf<String>()
    if (task.turns > 0) parts += stringResource(R.string.chat_task_meta_turns, task.turns)
    task.costCents?.let { cents -> parts += stringResource(R.string.chat_task_meta_cost, cents / 100.0) }
    if (task.fileChangeCount > 0) parts += stringResource(R.string.chat_task_meta_files, task.fileChangeCount)
    // 交付目标分支（DELIVERED 裁决时回填，此前采集未展示）
    task.deliverBranch?.takeIf { branch -> branch.isNotBlank() }?.let { branch -> parts += "⎇ $branch" }
    return parts.joinToString(" · ")
}

/**
 * 错误区块：出错信息是任务卡的一等组成部分——错误色容器 + 图标 + 完整摘要
 * （最多 6 行，取代原单行/两行红字）。Chat 卡（原生兜底）与任务中心列表项共用。
 */
@Composable
internal fun EngineerTaskErrorBlock(
    errorText: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 6,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(14.dp)
                    .padding(top = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = errorText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.ROOT, totalSec / 60, totalSec % 60)
}
