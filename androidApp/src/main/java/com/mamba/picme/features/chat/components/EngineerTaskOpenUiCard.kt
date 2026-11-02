package com.mamba.picme.features.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.cell
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiToolNotFoundException
import com.mamba.picme.domain.chat.openui.OpenUiToolResolver
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskProgram
import com.mamba.picme.domain.chat.taskcenter.EngineerTaskTexts
import com.mamba.picme.features.chat.openui.OpenUiNodeRenderer
import com.mamba.picme.features.chat.openui.OpenUiPhotoResolver

/**
 * 任务卡信息区 OpenUI 路径（H3，spec《2026-11-02-engineer-taskcard-openui》§3.2；
 * 从 [EngineerTaskCard.kt] 拆出——OpenUI 路径独立成件，legacy WebView 路径留在原文件）：
 * 节流态经 [EngineerTaskProgram] 合成程序文本 → [OpenUiSession] 全管线（无
 * local_handle/无 viewState）→ **解包根 Card 渲染 children**——卡壳由外层两分区
 * 原生 Surface 提供（对齐 H2 期 HtmlCard cardColor/contentPadding=0 中和手法）。
 *
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
                if (shouldUpgradeToFullpage(
                        fullpageSticky = fullpageSticky,
                        showExpanded = showExpanded,
                        viewportHeightDp = viewportHeightDp,
                        measuredHeightDp = heightDp,
                    )
                ) {
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

/** FULLPAGE 升格判定（纯函数，spec §D5）：展开态 + 有视口基准 + 实测高超一屏。 */
private fun shouldUpgradeToFullpage(
    fullpageSticky: Boolean,
    showExpanded: Boolean,
    viewportHeightDp: Int,
    measuredHeightDp: Float,
): Boolean = !fullpageSticky && showExpanded && viewportHeightDp > 0 && measuredHeightDp > viewportHeightDp

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
