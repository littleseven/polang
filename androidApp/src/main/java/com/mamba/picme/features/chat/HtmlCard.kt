package com.mamba.picme.features.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mamba.picme.R

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
 * 卡片态高度动态适配内容并**完全撑开**：[HtmlWebView] 加载完成后经 `evaluateJavascript` 读内容
 * 高度（出站求值，非 JS 桥接），卡片高度 = 内容全高（≥ [CARD_MIN_HEIGHT]，无上限）——
 * 卡片自身永不竖滚，滚动全交外层 LazyColumn，从根上消除「长列表 vs WebView 手势冲突」；
 * 加载后注入 ResizeObserver 监听 body——内容高度随交互变化（手风琴/Tab/动态排版）时经
 * console 约定通道（`onConsoleMessage`，JS→原生单向被动上报，非桥接）持续跟随，容器动画伸缩。
 * **滑动防抖**：测高结果按 html hash 进程级缓存（重回视口零高度动画）+ 列表滑动途中冻结
 * 高度更新（滚停后一次性应用）+ 小于 [HEIGHT_UPDATE_THRESHOLD_PX]px 的抖动忽略——三者共同
 * 消除「滑动中列表项边滚边变高」的上下抖动。滚动条一律隐藏。
 */

/** 卡片态最小高度。 */
internal val CARD_MIN_HEIGHT = 120.dp

/** 测高完成前的占位高度（接近常见卡片高度，减少跳动）。 */
private val CARD_PLACEHOLDER_HEIGHT = 280.dp

/** 建议单卡内容高度占屏高比例（卡片已完全撑开，此为组合根注入 prompt 的可读性建议值，非硬上限）。 */
const val CARD_MAX_HEIGHT_FRACTION = 0.66f

/**
 * 卡片内容区水平 chrome（列表边距 + 卡片内边距 + 气泡余量，dp 近似值）；
 * 组合根注入渲染环境时用 `屏宽 dp - 该值` 估算卡片内容区最大宽度。
 */
const val CARD_HORIZONTAL_CHROME_DP = 48

/**
 * 测高结果进程级缓存（key = html hashCode）：LazyColumn 划出视口的列表项会被回收，
 * 滑回来时重组复位成占位高 → 重测高 → 高度动画，多张卡片在滑动途中边滚边变高即「上下抖动」。
 * 缓存越出单个列表项的 remember 生命周期，卡片重回视口直接以最终高度出现，零高度动画。
 */
private val measuredHeightCache = LruCache<Int, Int>(64)

/** 高度更新最小幅度（CSS px ≈ dp）：低于此值的测高抖动（小数取整等）直接忽略，不触发动画。 */
private const val HEIGHT_UPDATE_THRESHOLD_PX = 2

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HtmlCard(
    html: String,
    modifier: Modifier = Modifier,
    isListScrolling: Boolean = false,
    onOpenLink: ((String) -> Unit)? = null
) {
    // 内容测高（CSS px ≈ dp，模板已注入 viewport meta width=device-width）；html 变化时复位，
    // 初值取进程级缓存——重回视口的卡片直接以最终高度出现，不走占位高度动画
    val htmlKey = html.hashCode()
    var measuredHeightDp by remember(html) { mutableIntStateOf(measuredHeightCache.get(htmlKey) ?: -1) }
    // 滑动途中冻结高度更新（列表项边滚边变高是滑动抖动主因），最新值挂起，滚动停止后一次性应用
    var pendingHeightDp by remember(html) { mutableIntStateOf(-1) }
    val currentIsScrolling by rememberUpdatedState(isListScrolling)
    // 卡片完全撑开：高度 = 内容全高（≥ [CARD_MIN_HEIGHT]），无上限、不内滚，
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
            measuredHeightCache.put(htmlKey, pendingHeightDp)
            pendingHeightDp = -1
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        HtmlWebView(
            html = html,
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedHeight)
                .nestedScroll(rememberNestedScrollInteropConnection())
                .padding(8.dp),
            onContentHeightCssPx = { px ->
                val last = measuredHeightCache.get(htmlKey) ?: -1
                if (kotlin.math.abs(px - last) >= HEIGHT_UPDATE_THRESHOLD_PX) {
                    if (currentIsScrolling) {
                        pendingHeightDp = px
                    } else {
                        measuredHeightDp = px
                        measuredHeightCache.put(htmlKey, px)
                    }
                }
            },
            onOpenLink = onOpenLink
        )
    }
}

/**
 * 卡片沙箱 WebView。内容变化（[html] hash 变化）才重载，避免重组时闪烁重渲染。
 *
 * [onContentHeightCssPx]：页面加载完成后回测内容高度（CSS px，≈ dp）。
 * 经 `evaluateJavascript` 出站求值实现——不构成 JS 桥接（JS 无法反向调用原生）。
 * [onOpenLink]：`<a>` http(s) 外链点击回调（卡片自身永不导航），由宿主打开落地页。
 */
@SuppressLint("SetJavaScriptEnabled") // 表现力来源；零桥接，JS 拿不到原生对象
@Composable
private fun HtmlWebView(
    html: String,
    modifier: Modifier = Modifier,
    onContentHeightCssPx: ((Int) -> Unit)? = null,
    onOpenLink: ((String) -> Unit)? = null
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ChatHtmlWebView(context).apply {
                applyCardSandbox(onContentHeightCssPx, onOpenLink)
                loadHtmlOnce(html)
            }
        },
        update = { webView ->
            if (webView.tag != html.hashCode()) {
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
    tag = html.hashCode()
    loadDataWithBaseURL(null, wrapHtmlDocument(html), "text/html", "utf-8", null)
}

/**
 * 聊天卡片专用 WebView 的嵌套滚动策略。卡片已完全撑开（无高度上限、自身不滚动），
 * 竖直拖动手势应**全部让渡给外层聊天列表**，WebView 只保留点击/横滑等卡内交互：
 * - 内容不可竖滚（常态）：MOVE 直接返回 false，且不调用 requestDisallowInterceptTouchEvent，
 *   Compose 父链（LazyColumn）越过 touch slop 后经 onInterceptTouchEvent 接管手势流，
 *   WebView 收 ACTION_CANCEL、列表顺畅滚动——这是 WebView 嵌进 LazyColumn 的标准做法，
 *   替代手动 dispatchNestedPreScroll（实测在 Compose interop 链路上不可靠，几乎滑不动）；
 * - 内容可竖滚（异常兜底，如测高滞后瞬间）：保持 isNestedScrollingEnabled=false，
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
    onContentHeightCssPx: ((Int) -> Unit)?,
    onOpenLink: ((String) -> Unit)?
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
    // console 约定通道：接收 [HEIGHT_WATCHER_JS] 的高度上报（其他 console 消息放行默认 logcat）
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val text = consoleMessage.message()
            if (!text.startsWith(HEIGHT_CONSOLE_PREFIX)) return false
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

        override fun onPageFinished(view: WebView, url: String?) {
            // 高度 watcher 与布局无关（ResizeObserver 注册即生效），先行注入
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
