package com.mamba.picme.features.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mamba.picme.R

/**
 * HTML 卡全屏查看器（spec《HTML 卡双形态》§6）：预览卡点击后进入，全量渲染同一条消息的
 * HTML payload。与 [HtmlLinkPreviewOverlay] 同级 overlay，宿主（ChatScreen）统一 BackHandler 收口。
 *
 * 与卡片沙箱完全一致（渲染对象是同一清洗产物，非通用 web 内容）：零 JS 桥接 / 禁文件 /
 * 禁 DOM Storage / 远程 script 已剔除 / img·CSS 放行；差异仅在交互面——**允许竖滚 +
 * 显示滚动条**（长内容需位置指示），不注入测高 JS 与 ResizeObserver。
 * `<a>` 点击仍回调 [onOpenLink] → [HtmlLinkPreviewOverlay] 叠在查看器之上；查看器自身永不导航。
 *
 * 加载失败（onRenderProcessGone/主帧错误）→ 错误占位 + 关闭（降级链 §9.2 末端）。
 */
@Suppress("LongMethod") // 顶栏 + WebView 单屏收口；待重构：抽顶栏子 composable
@SuppressLint("SetJavaScriptEnabled") // 表现力来源；零桥接，JS 拿不到原生对象
@Composable
fun HtmlFullpageViewer(
    content: HtmlFullpageContent?,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (content == null) return
    var loadFailed by remember(content) { mutableStateOf(false) }
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
                text = content.title?.takeIf { title -> title.isNotBlank() }
                    ?: stringResource(R.string.html_fullpage_title_default),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        if (loadFailed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.html_fullpage_load_failed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                    Text(text = stringResource(R.string.close))
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            applyFullpageSandbox(onOpenLink) { loadFailed = true }
                            tag = content.html.hashCode()
                            loadDataWithBaseURL(
                                null,
                                wrapHtmlDocument(content.html),
                                "text/html",
                                "utf-8",
                                null
                            )
                        }
                    },
                    // content 变更（查看器未关直接换卡）时按 html 哈希重载同一 WebView；
                    // tag 记录已加载内容，避免重组触发重复 loadDataWithBaseURL
                    update = { webView ->
                        if (webView.tag != content.html.hashCode()) {
                            webView.tag = content.html.hashCode()
                            webView.loadDataWithBaseURL(
                                null,
                                wrapHtmlDocument(content.html),
                                "text/html",
                                "utf-8",
                                null
                            )
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.destroy()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * 全屏查看器沙箱：与卡片沙箱逐项对齐（零桥/禁文件/禁 DOM Storage/无缓存），
 * 差异仅竖滚放行 + 滚动条显示；不注入测高 JS/ResizeObserver；永不导航（`<a>` 回传 [onOpenLink]）。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.applyFullpageSandbox(
    onOpenLink: (String) -> Unit,
    onRenderFailed: () -> Unit
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.mediaPlaybackRequiresUserGesture = true
    settings.setSupportZoom(false)
    // 背景透明对齐卡片侧：暗色主题下进查看器不跳白（html 自身背景色决定观感）
    setBackgroundColor(AndroidColor.TRANSPARENT)
    // 与卡片相反：全屏查看长内容允许竖滚并显示滚动条（位置指示）
    isVerticalScrollBarEnabled = true
    isHorizontalScrollBarEnabled = false
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString() ?: return true
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") onOpenLink(url)
            return true
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onRenderFailed()
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onRenderFailed()
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (request.isForMainFrame) onRenderFailed()
        }
    }
}
