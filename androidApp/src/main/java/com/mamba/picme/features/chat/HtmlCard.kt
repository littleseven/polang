package com.mamba.picme.features.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 聊天里的 HTML 组件卡片：离线 WebView 渲染自包含 HTML（LLM `render_html` 产物，
 * 落库前已过 [HtmlCardSanitizer]）。
 *
 * 锁死策略（渲染对象为不可信内容，[PRIVACY] 不触网）：
 * - `shouldInterceptRequest` 全量拦截 http(s) 子资源（断网，仅放行 data: 等内联协议）；
 * - `shouldOverrideUrlLoading` 一律拦截（禁导航/外链跳转）；
 * - 禁文件/内容访问、禁 DOM Storage、无缓存；
 * - **零 JS 桥接**（不 addJavascriptInterface）；JS 仅在沙盒内启用（动画/交互表现力）。
 *
 * 卡片态高度动态适配内容：[HtmlWebView] 加载完成后经 `evaluateJavascript` 读内容高度
 * （出站求值，非 JS 桥接），clamp 到 [[CARD_MIN_HEIGHT], 屏高×[CARD_MAX_HEIGHT_FRACTION]]；
 * 加载后注入 ResizeObserver 监听 body——内容高度随交互变化（手风琴/Tab/动态排版）时经
 * console 约定通道（`onConsoleMessage`，JS→原生单向被动上报，非桥接）持续跟随，容器动画伸缩。
 * 卡片内直接交互（JS 点击/输入即时生效，无全屏落地页）；内容超高时卡片内滚动，
 * 滚动条一律隐藏，嵌套滚动经 `rememberNestedScrollInteropConnection` 滚到边界后让渡外层列表。
 */

/** 卡片态最小高度。 */
internal val CARD_MIN_HEIGHT = 120.dp

/** 测高完成前的占位高度（接近常见卡片高度，减少跳动）。 */
private val CARD_PLACEHOLDER_HEIGHT = 280.dp

/** 卡片态最大高度占屏高比例（超出部分卡片内滚动查看）；组合根注入渲染环境时复用。 */
const val CARD_MAX_HEIGHT_FRACTION = 0.66f

/**
 * 卡片内容区水平 chrome（列表边距 + 卡片内边距 + 气泡余量，dp 近似值）；
 * 组合根注入渲染环境时用 `屏宽 dp - 该值` 估算卡片内容区最大宽度。
 */
const val CARD_HORIZONTAL_CHROME_DP = 48

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HtmlCard(html: String, modifier: Modifier = Modifier) {
    // 内容测高（CSS px ≈ dp，模板已注入 viewport meta width=device-width）；html 变化时复位
    var measuredHeightDp by remember(html) { mutableIntStateOf(-1) }
    val maxHeightDp = (LocalConfiguration.current.screenHeightDp * CARD_MAX_HEIGHT_FRACTION).toInt()
    val maxHeight = maxOf(CARD_MIN_HEIGHT, maxHeightDp.dp)
    val targetHeight = if (measuredHeightDp > 0) {
        measuredHeightDp.dp.coerceIn(CARD_MIN_HEIGHT, maxHeight)
    } else {
        CARD_PLACEHOLDER_HEIGHT
    }
    val animatedHeight by animateDpAsState(targetValue = targetHeight, label = "htmlCardHeight")

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
            onContentHeightCssPx = { measuredHeightDp = it }
        )
    }
}

/**
 * 离线锁死 WebView。内容变化（[html] hash 变化）才重载，避免重组时闪烁重渲染。
 *
 * [onContentHeightCssPx]：页面加载完成后回测内容高度（CSS px，≈ dp）。
 * 经 `evaluateJavascript` 出站求值实现——不构成 JS 桥接（JS 无法反向调用原生）。
 */
@SuppressLint("SetJavaScriptEnabled") // 表现力来源；已断网 + 零桥接，JS 跑不出沙盒
@Composable
private fun HtmlWebView(
    html: String,
    modifier: Modifier = Modifier,
    onContentHeightCssPx: ((Int) -> Unit)? = null
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ChatHtmlWebView(context).apply {
                applyOfflineLockdown(onContentHeightCssPx)
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
 * 聊天卡片专用 WebView：补足「内容不可竖直滚动时 drag delta 不进嵌套滚动」的缺口——
 * WebView 内建嵌套滚动只在自身可滚时 dispatch；卡片高度适配内容后通常不可滚，
 * 此时竖直拖动应让渡外层列表，这里手动把 delta 经 NestedScrollingChild 通道上报 Compose 父链
 * （配合 modifier 上的 `rememberNestedScrollInteropConnection` 到达 LazyColumn）。
 * 内容超高可滚时走 WebView 内建链（先自滚、边界让渡），本分支不触发。
 */
private class ChatHtmlWebView(context: Context) : WebView(context) {
    private var lastY = 0f
    private val nestedConsumed = IntArray(2)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                startNestedScroll(View.SCROLL_AXIS_VERTICAL)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (lastY - event.y).toInt()
                lastY = event.y
                if (dy != 0 && !canScrollVertically(-1) && !canScrollVertically(1)) {
                    nestedConsumed[0] = 0
                    nestedConsumed[1] = 0
                    dispatchNestedPreScroll(0, dy, nestedConsumed, null)
                    val unconsumedDy = dy - nestedConsumed[1]
                    if (unconsumedDy != 0) {
                        dispatchNestedScroll(0, 0, 0, unconsumedDy, null)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopNestedScroll()
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
private fun WebView.applyOfflineLockdown(onContentHeightCssPx: ((Int) -> Unit)?) {
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
        /** 禁一切导航（外链跳转、location 跳转）。 */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        /** 断网双保险：http(s) 子资源一律返回空响应；data:/about: 等内联协议放行。 */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val scheme = request.url.scheme?.lowercase()
            return if (scheme == "http" || scheme == "https") {
                WebResourceResponse("text/plain", "utf-8", null)
            } else {
                null
            }
        }

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
