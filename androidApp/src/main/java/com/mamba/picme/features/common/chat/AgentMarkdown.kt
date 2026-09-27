package com.mamba.picme.features.common.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.appColors
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.delay
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

/**
 * immediate（同步解析）内容长度门控上限（字符数）。
 *
 * 定案实测（2026-09-27，Redmi 24129PN74C 真机 instrument 打点，org.jetbrains.markdown GFM 全量 parse，
 * 临时用例取数后已删）：进程稳态（ART 预热后）700 字 2.1ms / 1500 字 4.1ms / 3000 字 7.8ms /
 * 5000 字 12.3ms（线性 ~2.4μs/字）；冷进程首次 parse ~20ms（一次性，可接受）。
 * 结论：≤1500 字同步解析稳态 ≤~4ms 远低于 16.6ms 帧预算，换首帧无空窗；
 * >1500 字走异步（retainState 保旧帧 + 库内 conflate 防抖），规避流式期每 pacing tick
 * 一次主线程 parse 的累积卡顿（3000+ 字时单次即逼近帧预算一半）。
 */
internal const val IMMEDIATE_PARSE_MAX_CHARS = 1500

/**
 * Agent 正文 Markdown 渲染（ADR-016 M3，mikepenz multiplatform-markdown-renderer 0.41.0）。
 * 取代 jeziellago compose-markdown（Markwon 位图渲染，流式抖动根源），AST → 原生 Compose 组件。
 *
 * 与 MarkdownSegmenter 的边界：Chat 二级页经 segmentMarkdown 把 TABLE/CODE 段切给自研
 * AgentTable/CodeBlock（CJK 计宽/全屏预览/折叠属产品功能，库对应能力弱），本组件只承接
 * MARKDOWN 正文段；AiChatScreen/MediaPager/悬浮气泡等不分段的调用点，围栏代码块则由
 * 本组件的 [CollapsibleHighlightedCodeFence] 承接（库高亮 + 自研折叠/复制）。
 *
 * 流式参数：retainState=true 防重解析期 loading 态闪烁。immediate（同步解析）按内容长度门控——
 * 官方明示 immediate=true 阻塞 composition 不宜全量上生产；短内容走同步保首帧无空窗，
 * 长内容走异步（库内置 conflate，流式 tick 间自然防抖）。
 * 阈值定案依据（真机实测数据见 [IMMEDIATE_PARSE_MAX_CHARS] KDoc）。
 */
@Composable
fun AgentMarkdown(
    content: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
) {
    val markdownState = rememberMarkdownState(
        content = content,
        retainState = true,
        immediate = content.length <= IMMEDIATE_PARSE_MAX_CHARS,
    )
    Markdown(
        markdownState = markdownState,
        colors = agentMarkdownColors(textColor = color),
        typography = agentMarkdownTypography(fontSize = fontSize, lineHeight = lineHeight),
        components = markdownComponents(
            codeFence = CollapsibleHighlightedCodeFence,
            codeBlock = CollapsibleHighlightedCodeBlock,
        ),
        annotator = rememberInlineHtmlAnnotator(),
        modifier = modifier,
    )
}

/** 颜色全部派生自调用方正文色，保证深色气泡（白字）与常规表面（onSurface）两种宿主下对比度一致。 */
@Composable
private fun agentMarkdownColors(textColor: Color): MarkdownColors = markdownColor(
    text = textColor,
    codeBackground = textColor.copy(alpha = 0.08f),
    inlineCodeBackground = textColor.copy(alpha = 0.12f),
    dividerColor = textColor.copy(alpha = 0.2f),
    tableBackground = textColor.copy(alpha = 0.02f),
)

/** 正文 [fontSize]/[lineHeight] 由调用方定；标题降档（m3 默认 display 级标题在气泡内过大），按正文比例缩放。 */
@Composable
private fun agentMarkdownTypography(fontSize: TextUnit, lineHeight: TextUnit): MarkdownTypography {
    val body = TextStyle(fontSize = fontSize, lineHeight = lineHeight)
    val codeSize = fontSize * 0.8f
    val code = TextStyle(
        fontSize = codeSize,
        lineHeight = codeSize * 1.35f,
        fontFamily = FontFamily.Monospace,
    )
    val headingLineHeight = lineHeight * 1.2f
    return markdownTypography(
        h1 = body.copy(fontSize = fontSize * 1.3f, lineHeight = headingLineHeight, fontWeight = FontWeight.Bold),
        h2 = body.copy(fontSize = fontSize * 1.22f, lineHeight = headingLineHeight, fontWeight = FontWeight.Bold),
        h3 = body.copy(fontSize = fontSize * 1.15f, lineHeight = headingLineHeight, fontWeight = FontWeight.Bold),
        h4 = body.copy(fontSize = fontSize * 1.1f, lineHeight = headingLineHeight, fontWeight = FontWeight.Bold),
        h5 = body.copy(fontSize = fontSize * 1.05f, lineHeight = headingLineHeight, fontWeight = FontWeight.Bold),
        h6 = body.copy(lineHeight = headingLineHeight, fontWeight = FontWeight.Bold),
        text = body,
        code = code,
        inlineCode = body.copy(fontFamily = FontFamily.Monospace),
        quote = body.copy(fontStyle = FontStyle.Italic),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            ),
        ),
        table = body,
    )
}

/**
 * 白名单内联 HTML annotator：拦截 HTML_TAG 节点，`<u>`/`<mark>`/`<sup>`/`<sub>` 开标签推
 * SpanStyle、闭标签弹栈；非白名单标签同样消费丢弃（正文禁嵌渲染级 HTML，与 two-tier spec §8 一致）。
 * mark 底色走 token（appColors.amber 降透明度，深浅主题下正文均可读）；sup/sub 字号用 em 相对值。
 *
 * 行为契约（已由 InlineHtmlAnnotatorTest 钉住）：仅匹配标签原文 trim 后的精确形式
 * （`<u>` 匹配、`<u >`/`<u class=...>` 不匹配——按非白名单消费丢弃）；
 * 未闭合开标签样式泄漏到段尾不崩，深度计数按 AnnotatedString.Builder 实例复位不污染下一次渲染。
 *
 * 🔴 流式竞态红线（2026-09-27 spike 真机闪退实证）：lambda 首个参数 content 才是 AST 所属文档，
 * 绝不能用闭包捕获的外部 content——retainState 下旧树配新 annotator，节点区间超出捕获文本直接
 * StringIndexOutOfBounds。
 */
@Composable
private fun rememberInlineHtmlAnnotator(): MarkdownAnnotator {
    val markColor = MaterialTheme.appColors.amber.copy(alpha = 0.35f)
    return remember(markColor) { createInlineHtmlAnnotator(markColor) }
}

/** 非 Composable 工厂（JVM 单测可直接构造）：每次调用返回独立状态（开标签深度按 Builder 实例复位）。 */
internal fun createInlineHtmlAnnotator(markColor: Color): MarkdownAnnotator {
    var openStyles = 0
    var lastBuilder: AnnotatedString.Builder? = null
    return markdownAnnotator { content, child ->
        if (lastBuilder !== this) {
            openStyles = 0
            lastBuilder = this
        }
        if (child.type != MarkdownTokenTypes.HTML_TAG) return@markdownAnnotator false
        when (child.getTextInNode(content).toString().lowercase().trim()) {
            "<u>" -> { pushStyle(SpanStyle(textDecoration = TextDecoration.Underline)); openStyles++ }
            "<mark>" -> { pushStyle(SpanStyle(background = markColor)); openStyles++ }
            "<sup>" -> { pushStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)); openStyles++ }
            "<sub>" -> { pushStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)); openStyles++ }
            "</u>", "</mark>", "</sup>", "</sub>" -> if (openStyles > 0) { pop(); openStyles-- }
        }
        true
    }
}

/** 折叠阈值：超过此行数的围栏代码块默认折叠。 */
private const val CODE_FENCE_COLLAPSE_LINES = 12

/** 围栏代码块（```）→ 高亮 + 折叠/复制。 */
private val CollapsibleHighlightedCodeFence: MarkdownComponent = { model ->
    MarkdownCodeFence(model.content, model.node, style = model.typography.code) { code, language, codeStyle ->
        CollapsibleHighlightedCode(code = code, language = language, style = codeStyle)
    }
}

/** 缩进代码块（4 空格，无围栏无语言）→ 同围栏块待遇。 */
private val CollapsibleHighlightedCodeBlock: MarkdownComponent = { model ->
    MarkdownCodeBlock(model.content, model.node, style = model.typography.code) { code, language, codeStyle ->
        CollapsibleHighlightedCode(code = code, language = language, style = codeStyle)
    }
}

/**
 * 库高亮（SnipMe Highlights，produceState 异步）+ 自研折叠/复制包装：
 * 超过 [CODE_FENCE_COLLAPSE_LINES] 行时按行高*行数 + 库内边距（vertical 8 + codeBlock 8）截断，
 * 「展开/收起」与复制按钮行复用自研 CodeBlock 的既有文案资源。
 */
@Composable
private fun CollapsibleHighlightedCode(code: String, language: String?, style: TextStyle) {
    val totalLines = remember(code) { code.trimEnd('\n').lines().size }
    val expandable = totalLines > CODE_FENCE_COLLAPSE_LINES
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val collapsedMaxHeight = remember(style, density) {
        with(density) { (style.lineHeight * CODE_FENCE_COLLAPSE_LINES).toDp() + 16.dp }
    }
    val contentColor = LocalMarkdownColors.current.text
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LocalMarkdownColors.current.codeBackground),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expandable && !expanded) {
                        Modifier.heightIn(max = collapsedMaxHeight).clipToBounds()
                    } else {
                        Modifier
                    },
                ),
        ) {
            MarkdownHighlightedCode(code = code, language = language, style = style)
        }
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (expandable) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.claude_code_collapse)
                        } else {
                            stringResource(R.string.claude_code_expand_n, totalLines)
                        },
                        fontSize = 12.sp,
                    )
                }
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.claude_code_copy),
                    modifier = Modifier.size(16.dp),
                    tint = contentColor.copy(alpha = 0.7f),
                )
            }
            if (copied) {
                Text(
                    text = stringResource(R.string.claude_code_copied),
                    fontSize = 11.sp,
                    color = contentColor.copy(alpha = 0.7f),
                )
                LaunchedEffect(copied) {
                    delay(1500)
                    copied = false
                }
            }
        }
    }
}
