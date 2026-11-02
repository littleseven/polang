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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mamba.picme.R
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import com.mamba.picme.domain.chat.HtmlCardMeta
import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiToolNotFoundException
import com.mamba.picme.domain.chat.openui.OpenUiToolResolver
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.features.chat.HtmlCard
import com.mamba.picme.core.designsystem.cell
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskHtml
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskPalette
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskProgram
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskTexts
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskThrottle
import com.mamba.picme.features.chat.openui.OpenUiNodeRenderer
import com.mamba.picme.features.chat.openui.OpenUiPhotoResolver
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 工程师任务卡（2026-09-27 H2 HTML 化 → **2026-11-02 H3 OpenUI 化**，spec《2026-11-02
 * engineer-taskcard-openui》）：信息区默认走 [EngineerTaskProgram] 端侧程序合成 +
 * [EngineerTaskOpenUiInfoZone] 原生渲染（[USE_OPENUI_TASKCARD_RENDER] 回滚闸可回退
 * H2 WebView 路径——L1 模板 HTML 经 [EngineerTaskHtml] 组装、[HtmlCard] 双形态渲染，
 * iOS 双轨期保留）；双形态语义两路径一致（折叠/展开 INLINE + 展开超 1.0 屏升格
 * FULLPAGE → 全屏查看器）。
 *
 * 卡片为**上下两分区单卡容器**（2026-09-27 Muse 修订，外置动作条方案作废；design taskcard 四帧）：
 * 上半 = HTML 信息区（r16 卡容器内），下半 = **原生 Compose 动作区**（与卡面统一 cell 底 + hairline
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
/**
 * 任务卡信息区渲染引擎开关（spec《2026-11-02-engineer-taskcard-openui》§3.2 回滚闸）：
 * true = OpenUI 程序端侧合成 + 原生渲染（H3，本轮交付）；false = 回退 H2 WebView 路径。
 * iOS 双轨期 [EngineerTaskHtml] 保留编译（WKWebView 消费），两路径并存成本≈0；
 * iOS OpenUI 渲染器落地统一后一并删除（届时连同本开关与 legacy 分支）。
 */
internal const val USE_OPENUI_TASKCARD_RENDER = true

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
    // 单卡容器（两分区改版）：上 = HTML 信息区，下 = 原生动作区，外层统一 r16 裁剪；
    // 整卡统一 cell 底（Light 纯白 / Dark #1A1A1A，token v3.0「灰底白组」槽）——上下两区同色，
    // 分区仅靠动作区顶 hairline；白卡从微信灰列表底（#EDEDED）自然浮起，无需描边（design taskcard/light-*）
    Surface(
        color = MaterialTheme.colorScheme.cell,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = cd },
    ) {
        Column {
            if (USE_OPENUI_TASKCARD_RENDER) {
                EngineerTaskOpenUiInfoZone(
                    task = throttled,
                    expanded = expanded,
                    viewportHeightDp = viewportHeightDp,
                    texts = texts,
                    fullpageSticky = resolvedMode == HtmlCardDisplayMode.FULLPAGE,
                    onDisplayModeResolved = { mode -> resolvedMode = mode },
                    onToggleExpand = onToggleExpand,
                    onOpenFullpage = onOpenFullpage,
                )
            } else if (html == null) {
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
                    cardColor = MaterialTheme.colorScheme.cell,
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
        cardBg = scheme.cell.toCssHex(),
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
    // 阶段时间线名词标签（design taskcard/expanded：读取文件/编辑代码…）与当前阶段动词（正在修改…）；
    // 键集合由 stageMaps 工厂统一定义（iOS 同工厂免桥接 Kotlin Map）
    val (stageLabels, stageVerbs) = EngineerTaskHtml.stageMaps(
        labelRead = stringResource(R.string.chat_task_stage_read),
        labelEdit = stringResource(R.string.chat_task_stage_edit),
        labelBash = stringResource(R.string.chat_task_stage_bash),
        labelSearch = stringResource(R.string.chat_task_stage_search),
        labelFileChange = stringResource(R.string.chat_task_stage_filechange),
        labelTool = stringResource(R.string.chat_task_stage_tool),
        verbRead = stringResource(R.string.chat_task_stage_verb_read),
        verbEdit = stringResource(R.string.chat_task_stage_verb_edit),
        verbBash = stringResource(R.string.chat_task_stage_verb_bash),
        verbSearch = stringResource(R.string.chat_task_stage_verb_search),
        verbFileChange = stringResource(R.string.chat_task_stage_verb_filechange),
        verbTool = stringResource(R.string.chat_task_stage_verb_tool),
    )
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
        stageLabels = stageLabels,
        stageVerbs = stageVerbs,
        reasonMaxTurns = stringResource(R.string.chat_task_reason_max_turns),
        reasonPhaseTimeout = stringResource(R.string.chat_task_reason_phase_timeout),
    )
}

/**
 * 卡内原生动作区（spec §7 2026-09-27 Muse 修订；design taskcard/collapsed、approval、done 帧）：
 * 与卡面统一 cell 底（Light 纯白 / Dark #1A1A1A，2026-10-11 Light 连体定稿）+ 顶 hairline
 * （outlineVariant）作唯一分区线，由外层卡容器统一裁剪底圆角。
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
            .background(scheme.cell),
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
            .fillMaxWidth()
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

/**
 * OpenUI 信息区宿主（spec《2026-11-02-engineer-taskcard-openui》§3.2，H3）：
 * 节流态经 [EngineerTaskProgram] 合成程序文本 → [OpenUiSession] 全管线（无
 * local_handle/无 viewState）→ **解包根 Card 渲染 children**——卡壳由外层两分区
 * 原生 Surface 提供（对齐 H2 期 HtmlCard cardColor/contentPadding=0 中和手法）。
 *
 * - 500ms 合帧语义不变（跃迁即时/微更新合帧在 [rememberThrottledTask]）；
 * - 重合成零闪烁：保留旧根直至新根 prepare 就绪（程序小、无工具调用，毫秒级）；
 * - 组装/prepare 失败 → [EngineerTaskFallbackCard]（spec §9.3 口径，动作区照常）；
 * - 双形态复刻（spec D5）：折叠/展开 = 程序带 showExpanded 重合成；展开态
 *   `onSizeChanged` 原生量高超 [viewportHeightDp] 升格 FULLPAGE（方向粘滞仅内存，
 *   spec §10 口径）——升格后内联回落折叠形态，点击整卡开 [EngineerTaskFullpageDialog]；
 *   WebView 测高缓存/防抖机制此路径天然不需要（原生布局同步完成）。
 */
@Suppress("LongParameterList")
@Composable
internal fun EngineerTaskOpenUiInfoZone(
    task: EngineerTaskState,
    expanded: Boolean,
    viewportHeightDp: Int,
    texts: EngineerTaskTexts,
    fullpageSticky: Boolean,
    onDisplayModeResolved: (HtmlCardDisplayMode) -> Unit,
    onToggleExpand: () -> Unit,
    onOpenFullpage: (program: String, title: String) -> Unit,
) {
    val showExpanded = expanded && !fullpageSticky
    val program = remember(task, showExpanded, texts) {
        runCatching { EngineerTaskProgram.assemble(task, showExpanded, texts) }.getOrNull()
    }
    if (program == null) {
        EngineerTaskFallbackCard(task)
        return
    }
    // 保留旧根直至新根就绪（同 taskId 跨 500ms 合帧不闪兜底卡；无 Query → prepare 毫秒级）
    var root by remember(task.taskId) { mutableStateOf<OpenUiValue.ComponentVal?>(null) }
    var prepareFailed by remember(task.taskId) { mutableStateOf(false) }
    LaunchedEffect(program) {
        val session = OpenUiSession(program, noOpTaskTools())
        val rendered = session.prepare() as? OpenUiResult.Rendered
        root = if (rendered != null) session.rootOrNull() else null
        prepareFailed = rendered == null
    }
    if (prepareFailed) {
        EngineerTaskFallbackCard(task)
        return
    }
    val resolvedRoot = root
    if (resolvedRoot == null) {
        // 首帧占位（prepare 极快，肉眼不可见的一帧空盒）
        Box(modifier = Modifier.fillMaxWidth())
        return
    }
    val density = LocalDensity.current
    val clickLabel = stringResource(
        if (expanded) R.string.chat_card_collapse_hint else R.string.chat_card_expand_hint,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                val heightDp = with(density) { size.height.toDp().value }
                if (!fullpageSticky && showExpanded && viewportHeightDp > 0 && heightDp > viewportHeightDp) {
                    onDisplayModeResolved(HtmlCardDisplayMode.FULLPAGE)
                }
            }
            .clickable(onClickLabel = clickLabel) {
                if (fullpageSticky) {
                    val expandedProgram = runCatching {
                        EngineerTaskProgram.assemble(task, true, texts)
                    }.getOrNull() ?: program
                    onOpenFullpage(expandedProgram, task.sourceText)
                } else {
                    onToggleExpand()
                }
            },
    ) {
        OpenUiUnwrappedCardChildren(root = resolvedRoot)
    }
}

/** 根 Card 解包：children 直渲（卡壳/内边距 12/16/14 由宿主提供，对齐 H2 HTML body 口径）。 */
@Composable
private fun OpenUiUnwrappedCardChildren(root: OpenUiValue.ComponentVal) {
    val children = (root.props["children"] as? OpenUiValue.Arr)?.v
        ?.filterIsInstance<OpenUiValue.ComponentVal>()
        .orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        children.forEach { child ->
            OpenUiNodeRenderer(
                node = child,
                state = emptyMap(),
                photoResolver = noOpPhotoResolver(),
                onAction = { },
            )
        }
    }
}

/** 任务卡程序无 Query/local_handle——工具解析恒 not-found（防御性，不触达）。 */
private fun noOpTaskTools(): OpenUiToolResolver = OpenUiToolResolver { name, _ ->
    throw OpenUiToolNotFoundException(name)
}

/** 任务卡程序无 Image(photo)——解析器恒 null（防御性，不触达）。 */
private fun noOpPhotoResolver(): OpenUiPhotoResolver = OpenUiPhotoResolver { null }

/**
 * 任务卡全屏查看器（复刻 H2 全屏 WebView 查看器交互）：标题 + 同一展开程序可滚动
 * 原生渲染；由 ChatScreen 浮层槽驱动（对齐 HtmlFullpageViewer 挂载位）。
 */
@Composable
internal fun EngineerTaskFullpageDialog(
    program: String,
    title: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.cell,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                )
                var root by remember(program) { mutableStateOf<OpenUiValue.ComponentVal?>(null) }
                LaunchedEffect(program) {
                    val session = OpenUiSession(program, noOpTaskTools())
                    root = if (session.prepare() is OpenUiResult.Rendered) session.rootOrNull() else null
                }
                root?.let { resolved ->
                    val children = (resolved.props["children"] as? OpenUiValue.Arr)?.v
                        ?.filterIsInstance<OpenUiValue.ComponentVal>()
                        .orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        children.forEach { child ->
                            OpenUiNodeRenderer(
                                node = child,
                                state = emptyMap(),
                                photoResolver = noOpPhotoResolver(),
                                onAction = { },
                            )
                        }
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
