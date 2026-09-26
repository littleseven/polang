package com.mamba.picme.features.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mamba.picme.R
import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import com.mamba.picme.domain.chat.HtmlCardMeta
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 聊天里的 HTML 组件卡片：WebView 渲染自包含 HTML（LLM `render_html` 产物，
 * 落库前已过 [HtmlCardSanitizer]）。
 *
 * 沙箱策略（渲染对象为不可信的 LLM 产物）：
 * - **零 JS 桥接**（不 addJavascriptInterface）——JS 拿不到任何原生对象，最高防线不动；
 * - 禁文件/内容访问、禁 DOM Storage、无缓存；
 * - 远程资源（img/CSS 等）放行加载（2026-09-25 起暂时放开安全限制，表现力优先）；
 * - `<a>` 外链点击**不在卡片内导航**：回调 `onOpenLink` 由宿主打开全屏落地页
 *   （[HtmlLinkPreviewOverlay]）查看；卡片内其余元素（按钮/Tab/手风琴等 JS 交互）
 *   直接在卡片内生效。
 *
 * **双形态**（spec《HTML 卡双形态》2026-09-26，分流判定见 [HtmlCardDisplay]）：
 * - **Inline 卡**（短卡，displayMode=INLINE）：动态测高 + 完全撑开——卡片高度 = 内容全高
 *   （≥ [CARD_MIN_HEIGHT]），自身永不竖滚，滚动全交外层 LazyColumn；加载后注入
 *   ResizeObserver 监听 body，交互撑高（手风琴/Tab）经 console 约定通道持续跟随。
 *   **判定 INLINE 后不再重新分流**（交互撑高保持 inline，避免交互中跳变）。
 * - **Fullpage 预览卡**（长卡，displayMode=FULLPAGE）：固定高 ≈0.5 × 可用屏高，
 *   WebView 真实渲染上半部，底部 120dp 渐隐遮罩 + 「点击查看完整内容」提示条；
 *   透明触控层消费点击（→ [onOpenFullpage] 全屏查看器）、不消费竖拖（LazyColumn 正常接管）；
 *   WebView 不可聚焦不可点击（JS 仍运行，仅触摸不到达）；不参与 ResizeObserver 高度跟随。
 *   渲染失败（onRenderProcessGone/加载错误）→ 原生封面兜底（summary + 提示条），点击仍进全屏。
 *
 * 分流判定结果（displayMode + 测高）随消息 metadata 持久化，会话重开/列表回收后形态不跳变。
 *
 * **滑动防抖**（inline 卡）：测高结果按 [cacheKey]（消息 id）进程级缓存（重回视口零高度动画）+
 * 列表滑动途中冻结高度更新（滚停后一次性应用）+ 小于 [HEIGHT_UPDATE_THRESHOLD_PX]px 的抖动忽略；
 * console 上报的高度在视图未完成首次布局（width=0，CSS 视口宽度为 0 文本极限换行测出虚高）时丢弃——
 * 三者共同消除「滑动中卡片先显示大片空白再收起」的抖动。滚动条一律隐藏。
 */

/** 卡片态最小高度。 */
internal val CARD_MIN_HEIGHT = 120.dp

/** 测高完成前的占位高度（接近常见卡片高度，减少跳动）。 */
private val CARD_PLACEHOLDER_HEIGHT = 280.dp

/** 建议单卡内容高度占屏高比例（inline 卡可读性建议值，组合根注入 prompt 用，非硬上限）。 */
const val CARD_MAX_HEIGHT_FRACTION = 0.66f

/**
 * 卡片内容区水平 chrome（列表边距 + 卡片内边距 + 气泡余量，dp 近似值）；
 * 组合根注入渲染环境时用 `屏宽 dp - 该值` 估算卡片内容区最大宽度。
 */
const val CARD_HORIZONTAL_CHROME_DP = 48

/**
 * 测高结果进程级缓存（key = 消息 id）：LazyColumn 划出视口的列表项会被回收，
 * 滑回来时重组复位成占位高 → 重测高 → 高度动画，多张卡片在滑动途中边滚边变高即「上下抖动」。
 * 缓存越出单个列表项的 remember 生命周期，卡片重回视口直接以最终高度出现，零高度动画。
 * （曾以 html.hashCode() 为 key，32 位哈希存在碰撞面——不同卡片串高度互相污染，
 * 2026-09-26 改为消息 id 字符串 key。）
 */
private val measuredHeightCache = LruCache<String, Int>(64)

/** 高度更新最小幅度（CSS px ≈ dp）：低于此值的测高抖动（小数取整等）直接忽略，不触发动画。 */
private const val HEIGHT_UPDATE_THRESHOLD_PX = 2

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // 双形态状态机单函数收口；待重构：拆 Inline/Fullpage 子 composable
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HtmlCard(
    html: String,
    modifier: Modifier = Modifier,
    /** 双形态元数据（display 声明 / 持久化终判 / 测高 / summary）；null = 旧消息，按未声明 inline 待测高处理。 */
    meta: HtmlCardMeta? = null,
    /** 测高缓存 key（消息 id，1:1 绑定 html 内容；勿用 html hashCode——32 位哈希有碰撞面）。 */
    cacheKey: String = html,
    /** 分流阈值基准视口高（dp，须对 IME 不变——宿主上报时加回 IME inset；键盘收缩视口会把 inline 卡误判 FULLPAGE 并持久化）；≤0 时退回整屏高（偏大保守，不误伤长卡判定）。 */
    viewportHeightDp: Int = 0,
    /** 预览卡限高基准视口高（dp，即时视口，随键盘收缩合理）；≤0 时退回 [viewportHeightDp]。 */
    previewViewportHeightDp: Int = 0,
    isListScrolling: Boolean = false,
    onOpenLink: ((String) -> Unit)? = null,
    /** 预览卡点击 / 兜底封面点击 → 宿主打开全屏查看器（[HtmlFullpageViewer]）。 */
    onOpenFullpage: (() -> Unit)? = null,
    /** 端侧终判完成回调（宿主持久化 displayMode + 测高到消息 metadata）；仅在首次判定时触发。 */
    onDisplayModeResolved: (HtmlCardDisplayMode, Int?) -> Unit = { _, _ -> }
) {
    val configuration = LocalConfiguration.current
    val effectiveViewportHeightDp =
        if (viewportHeightDp > 0) viewportHeightDp else configuration.screenHeightDp
    val thresholdPx = (effectiveViewportHeightDp * HtmlCardDisplay.FULLPAGE_THRESHOLD_FRACTION).roundToInt()
    val effectivePreviewViewportHeightDp =
        if (previewViewportHeightDp > 0) previewViewportHeightDp else effectiveViewportHeightDp
    val previewHeight = (effectivePreviewViewportHeightDp * HtmlCardDisplay.PREVIEW_HEIGHT_FRACTION)
        .roundToInt().dp.coerceAtLeast(CARD_MIN_HEIGHT)

    // 形态终判：持久化值（会话重开/回收后形态不跳变）> display=fullpage 免测高直判 > null（待测高）
    var displayMode by remember(html) {
        mutableStateOf(
            meta?.displayMode
                ?: if (HtmlCardDisplay.isDeclaredFullpage(meta?.display)) {
                    HtmlCardDisplayMode.FULLPAGE
                } else {
                    null
                }
            )
    }
    // display=fullpage 直判的回写终判（免测高；宿主按已有值幂等跳过重复落库）
    LaunchedEffect(html) {
        if (meta?.displayMode == null && displayMode == HtmlCardDisplayMode.FULLPAGE) {
            onDisplayModeResolved(HtmlCardDisplayMode.FULLPAGE, null)
        }
    }

    // 内容测高（CSS px ≈ dp，模板已注入 viewport meta width=device-width）；html 变化时复位，
    // 初值取进程级缓存 / 持久化测高——重回视口（含进程重建）的卡片直接以最终高度出现，不走占位高度动画
    var measuredHeightDp by remember(html) {
        mutableIntStateOf(
            measuredHeightCache.get(cacheKey)
                ?: meta?.measuredHeightPx?.takeIf { px -> px > 0 }
                ?: -1
        )
    }
    // 滑动途中冻结高度更新（列表项边滚边变高是滑动抖动主因），最新值挂起，滚动停止后一次性应用
    var pendingHeightDp by remember(html) { mutableIntStateOf(-1) }
    val currentIsScrolling by rememberUpdatedState(isListScrolling)
    // 预览态渲染失败（onRenderProcessGone/主帧加载错误）→ 原生封面兜底
    var renderFailed by remember(html) { mutableStateOf(false) }

    // inline 卡完全撑开：高度 = 内容全高（≥ [CARD_MIN_HEIGHT]）、不内滚，
    // 竖直滚动全交外层列表——WebView 永不竖滚，与 LazyColumn 的手势冲突从根上消失
    val targetHeight = if (measuredHeightDp > 0) {
        measuredHeightDp.dp.coerceAtLeast(CARD_MIN_HEIGHT)
    } else {
        CARD_PLACEHOLDER_HEIGHT
    }
    val animatedHeight by animateDpAsState(targetValue = targetHeight, label = "htmlCardHeight")

    LaunchedEffect(isListScrolling) {
        if (!isListScrolling && pendingHeightDp > 0) {
            measuredHeightDp = pendingHeightDp
            measuredHeightCache.put(cacheKey, pendingHeightDp)
            pendingHeightDp = -1
        }
    }

    /** inline 卡测高应用（含滑动冻结/抖动阈值/缓存）；FULLPAGE 判定后不再调用。 */
    fun applyMeasuredHeight(px: Int) {
        val last = measuredHeightCache.get(cacheKey) ?: -1
        if (abs(px - last) >= HEIGHT_UPDATE_THRESHOLD_PX) {
            if (currentIsScrolling) {
                pendingHeightDp = px
            } else {
                measuredHeightDp = px
                measuredHeightCache.put(cacheKey, px)
            }
        }
    }

    val isFullpage = displayMode == HtmlCardDisplayMode.FULLPAGE
    val cardColor = MaterialTheme.colorScheme.surface
    val viewFullLabel = stringResource(R.string.html_card_view_full)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardColor,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (!(isFullpage && renderFailed)) {
                HtmlWebView(
                    html = html,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isFullpage) previewHeight else animatedHeight)
                        // 预览卡高度恒定、触控层吃掉手势，无需嵌套滚动让渡
                        .then(
                            if (isFullpage) {
                                Modifier
                            } else {
                                Modifier.nestedScroll(rememberNestedScrollInteropConnection())
                            }
                        )
                        .padding(8.dp),
                    measureEnabled = !isFullpage,
                    previewMode = isFullpage,
                    onContentHeightCssPx = { px ->
                        when (displayMode) {
                            // 首次测高 → 混合分流判定（display 声明 × 测高 × 阈值），终判回写持久化
                            null -> {
                                val decided = HtmlCardDisplay.resolveDisplayMode(
                                    declaredDisplay = meta?.display,
                                    measuredHeightPx = px,
                                    fullpageThresholdPx = thresholdPx
                                )
                                displayMode = decided
                                onDisplayModeResolved(decided, px)
                                if (decided == HtmlCardDisplayMode.INLINE) applyMeasuredHeight(px)
                            }
                            // INLINE 判定后高度跟随（手风琴/Tab 撑高保持 inline，不再重新分流）
                            HtmlCardDisplayMode.INLINE -> applyMeasuredHeight(px)
                            // FULLPAGE 不参与高度跟随（高度恒定）
                            HtmlCardDisplayMode.FULLPAGE -> Unit
                        }
                    },
                    onOpenLink = onOpenLink,
                    onRenderFailed = { renderFailed = true }
                )
            }
            if (isFullpage) {
                if (renderFailed) {
                    // 渲染失败兜底：原生封面（summary + 提示条），点击仍进全屏（降级链 §9.2）
                    HtmlPreviewFallbackCover(
                        summary = meta?.summary,
                        onClick = { onOpenFullpage?.invoke() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight)
                    )
                } else {
                    // 底部渐隐遮罩（向卡底色渐隐，运行时取色，天然适配 Light/Dark）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(HtmlCardDisplay.PREVIEW_FADE_HEIGHT_DP.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to cardColor
                                )
                            )
                    )
                    HtmlPreviewHintBar(modifier = Modifier.align(Alignment.BottomCenter))
                    // 触控层：消费点击（→ 全屏查看器），不消费竖拖（LazyColumn 越过 touch slop 后正常接管）；
                    // clickable(indication = null) 自带 Role.Button 语义（TalkBack 可激活）且无水波纹
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = viewFullLabel
                            ) { onOpenFullpage?.invoke() }
                    )
                }
            }
        }
    }
}

/** 预览卡底部居中提示条「点击查看完整内容」（primary 色）。 */
@Composable
private fun HtmlPreviewHintBar(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(bottom = 10.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.UnfoldMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.html_card_view_full),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 预览卡渲染失败的原生兜底封面：失败说明 + summary（如有）+ 提示条，点击仍进全屏查看器。 */
@Composable
private fun HtmlPreviewFallbackCover(
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = stringResource(R.string.html_card_view_full)
        ) { onClick() }
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.html_card_preview_failed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
            if (!summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HtmlPreviewHintBar(modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(modifier = Modifier.height(10.dp))
    }
}

/**
 * 卡片沙箱 WebView。内容变化（[html] 变化）才重载，避免重组时闪烁重渲染。
 *
 * [measureEnabled]：false（预览卡）时不注入测高 JS 与 ResizeObserver（高度恒定，无跟随需求）。
 * [previewMode]：预览态——WebView 不可聚焦/不可点击（JS 仍运行，仅触摸不到达）。
 * [onContentHeightCssPx]：页面加载完成后回测内容高度（CSS px，≈ dp）。
 * 经 `evaluateJavascript` 出站求值实现——不构成 JS 桥接（JS 无法反向调用原生）。
 * [onOpenLink]：`<a>` http(s) 外链点击回调（卡片自身永不导航），由宿主打开落地页。
 * [onRenderFailed]：渲染进程消亡 / 主帧加载错误回调（预览卡据此外显原生兜底封面）。
 */
@SuppressLint("SetJavaScriptEnabled") // 表现力来源；零桥接，JS 拿不到原生对象
@Composable
private fun HtmlWebView(
    html: String,
    modifier: Modifier = Modifier,
    measureEnabled: Boolean = true,
    previewMode: Boolean = false,
    onContentHeightCssPx: ((Int) -> Unit)? = null,
    onOpenLink: ((String) -> Unit)? = null,
    onRenderFailed: (() -> Unit)? = null
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ChatHtmlWebView(context).apply {
                applyCardSandbox(measureEnabled, onContentHeightCssPx, onOpenLink, onRenderFailed)
                loadHtmlOnce(html)
            }
        },
        update = { webView ->
            // 预览态禁触摸/聚焦（JS 仍运行）；inline 态恢复默认可交互
            webView.isFocusable = !previewMode
            webView.isClickable = !previewMode
            if (webView.tag != html) {
                webView.loadHtmlOnce(html)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        }
    )
}

private fun WebView.loadHtmlOnce(html: String) {
    // tag 直接持有 html 全文做重载判等（曾用 hashCode，32 位哈希碰撞会漏重载/串卡）
    tag = html
    loadDataWithBaseURL(null, wrapHtmlDocument(html), "text/html", "utf-8", null)
}

/**
 * 聊天卡片专用 WebView 的嵌套滚动策略。inline 卡已完全撑开（无高度上限、自身不滚动），
 * 竖直拖动手势应**全部让渡给外层聊天列表**，WebView 只保留点击/横滑等卡内交互：
 * - 内容不可竖滚（常态）：MOVE 直接返回 false，且不调用 requestDisallowInterceptTouchEvent，
 *   Compose 父链（LazyColumn）越过 touch slop 后经 onInterceptTouchEvent 接管手势流，
 *   WebView 收 ACTION_CANCEL、列表顺畅滚动——这是 WebView 嵌进 LazyColumn 的标准做法，
 *   替代手动 dispatchNestedPreScroll（实测在 Compose interop 链路上不可靠，几乎滑不动）；
 * - 内容可竖滚（异常兜底）：保持 isNestedScrollingEnabled=false，
 *   WebView 自滚、父链无感知（防 LazyColumn pre-scroll 抢占 delta 导致卡死）。
 */
private class ChatHtmlWebView(context: Context) : WebView(context) {
    init {
        isNestedScrollingEnabled = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            !canScrollVertically(-1) && !canScrollVertically(1)
        ) {
            return false
        }
        return super.onTouchEvent(event)
    }
}

/**
 * 把 HTML 片段包进完整文档：注入 viewport meta（width=device-width → 1 CSS px ≈ 1 dp，
 * 测高与排版都有确定基准）与响应式 reset（防 LLM 产物固定宽度撑爆卡片）。
 * 已是完整文档（含 `<html`）时原样加载，不重复包裹。
 */
internal fun wrapHtmlDocument(html: String): String {
    if (html.contains("<html", ignoreCase = true)) return html
    return """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">""" +
        """<style>html,body{margin:0;padding:0}body{display:flow-root;overflow-wrap:break-word}""" +
        """img,video,canvas{max-width:100%;height:auto}svg,table,pre{max-width:100%}</style>""" +
        """</head><body>$html</body></html>"""
}

/** 内容测高 JS：body 内容盒高度为准（getBoundingClientRect，不受 viewport 高度污染）；为 0 时回退 scrollHeight。 */
private const val MEASURE_HEIGHT_JS =
    "(function(){var b=document.body;if(!b)return 0;" +
        "var h=Math.ceil(b.getBoundingClientRect().height);" +
        "if(h>0)return h;" +
        "var d=document.documentElement;" +
        "return Math.max(b.scrollHeight,d?d.scrollHeight:0);})()"

/** 高度变化上报的 console 约定前缀（见 [HEIGHT_WATCHER_JS]）。 */
private const val HEIGHT_CONSOLE_PREFIX = "__polang_card_height:"

/**
 * 高度监听 JS：ResizeObserver 观察 body，内容高度变化（手风琴展开/Tab 切换/JS 动态排版）时
 * 经 console.log 上报——`onConsoleMessage` 是 JS→原生的单向被动通道（JS 拿不到任何原生对象，
 * 不破坏零桥接红线）。经 `evaluateJavascript` 注入，片段/完整文档均生效；老内核无 ResizeObserver
 * 时静默跳过，由首测 + 延迟复测兜底。`window.__polangHeightWatcher` 防重复注入。
 */
private const val HEIGHT_WATCHER_JS =
    "(function(){if(window.__polangHeightWatcher)return;window.__polangHeightWatcher=true;" +
        "var last=0;" +
        "function report(){var b=document.body;if(!b)return;" +
        "var h=Math.ceil(b.getBoundingClientRect().height);" +
        "if(h>0&&h!==last){last=h;console.log('$HEIGHT_CONSOLE_PREFIX'+h);}}" +
        "if(typeof ResizeObserver!=='undefined'){" +
        "try{new ResizeObserver(report).observe(document.body);}catch(e){}}" +
        "report();})()"

/** 上报内容高度；非法值（非数/≤0）静默忽略，保留占位高度。 */
private fun WebView.reportContentHeight(onContentHeightCssPx: (Int) -> Unit) {
    evaluateJavascript(MEASURE_HEIGHT_JS) { value ->
        value?.trim()?.toDoubleOrNull()?.toInt()?.let { px ->
            if (px > 0) onContentHeightCssPx(px)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.applyCardSandbox(
    measureEnabled: Boolean,
    onContentHeightCssPx: ((Int) -> Unit)?,
    onOpenLink: ((String) -> Unit)?,
    onRenderFailed: (() -> Unit)?
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.mediaPlaybackRequiresUserGesture = true
    settings.setSupportZoom(false)
    // 滚动条永不显示（列表滚动时右侧滚动条闪现属视觉噪音）；超高内容仍可滚，只是无指示条
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    setBackgroundColor(AndroidColor.TRANSPARENT)
    // console 约定通道：接收 [HEIGHT_WATCHER_JS] 的高度上报（其他 console 消息放行默认 logcat）。
    // 视图未完成首次布局（width=0）时丢弃上报——此时 CSS 视口宽度为 0，文本极限换行会测出虚高，
    // 采纳后卡片先撑成大片空白、布局完成后再收起到正确高度（滑动途中回收重组时的抖动根因）。
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val text = consoleMessage.message()
            if (!text.startsWith(HEIGHT_CONSOLE_PREFIX)) return false
            if (this@applyCardSandbox.width <= 0) return true
            text.removePrefix(HEIGHT_CONSOLE_PREFIX).toIntOrNull()?.let { px ->
                if (px > 0) onContentHeightCssPx?.invoke(px)
            }
            return true
        }
    }
    webViewClient = object : WebViewClient() {
        /**
         * 卡片自身永不导航（一律拦截）：http(s) 外链回调 [onOpenLink] 由宿主打开落地页；
         * 其他协议（tel:/mailto:/intent: 等）静默吞掉。
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString() ?: return true
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") onOpenLink?.invoke(url)
            return true
        }

        // 远程子资源（img/CSS 等）放行加载：2026-09-25 起暂时放开安全限制，表现力优先；
        // 零 JS 桥接 + 禁文件访问防线不变。

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onRenderFailed?.invoke()
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onRenderFailed?.invoke()
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (request.isForMainFrame) onRenderFailed?.invoke()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!measureEnabled) return
            // 高度 watcher 与布局无关（ResizeObserver 注册即生效），先行注入；
            // 上报侧已对「布局完成前虚高」做丢弃（见 onConsoleMessage）
            view.evaluateJavascript(HEIGHT_WATCHER_JS, null)
            val callback = onContentHeightCssPx ?: return
            view.measureContentHeightWhenLaidOut(callback)
        }
    }
}

/**
 * 视图有尺寸后才测高：onPageFinished 可能先于首次布局（viewW=0 时 CSS 视口宽度为 0，
 * 文本极限换行导致测出虚高）；未布局则挂一次性 LayoutChangeListener 等首个有效尺寸。
 * 首测 + [MEASURE_RETRY_DELAY_MS] 延迟复测：覆盖内联 JS 布局 settle（动画/计数器初始渲染）后的高度变化。
 */
private fun WebView.measureContentHeightWhenLaidOut(onContentHeightCssPx: (Int) -> Unit) {
    if (width > 0 && height > 0) {
        reportContentHeight(onContentHeightCssPx)
        postDelayed({ if (width > 0) reportContentHeight(onContentHeightCssPx) }, MEASURE_RETRY_DELAY_MS)
    } else {
        addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                if (v.width > 0 && v.height > 0) {
                    v.removeOnLayoutChangeListener(this)
                    reportContentHeight(onContentHeightCssPx)
                    v.postDelayed(
                        { if (v.width > 0) reportContentHeight(onContentHeightCssPx) },
                        MEASURE_RETRY_DELAY_MS
                    )
                }
            }
        })
    }
}

private const val MEASURE_RETRY_DELAY_MS = 400L

/**
 * 外链落地页全屏浮层：卡片内 `<a>` 链接点击后以完整浏览器形态加载远程页面。
 *
 * 与卡片沙箱的差异：这里是通用 web 内容，启用 DOM Storage 保证兼容性；
 * **零 JS 桥接不变**（不 addJavascriptInterface）；允许浮层内页面跳转（用户已显式点链接进入）。
 * 返回键/关闭键由宿主（ChatScreen 的预览 BackHandler）收口，浮层自身不拦截。
 */
@SuppressLint("SetJavaScriptEnabled") // 通用 web 内容需要 JS；零桥接防线不变
@Composable
fun HtmlLinkPreviewOverlay(url: String?, onDismiss: () -> Unit) {
    if (url == null) return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 显式挂 client：页面内跳转留在浮层内，不甩给外部浏览器
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            },
            update = { /* 单次加载，url 变化时浮层整体重组（remember key 在调用侧） */ },
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}
