package com.mamba.picme.features.chat

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.mamba.picme.core.common.Logger

/**
 * HTML 卡本地媒体注入层（ADR-014 D3 预留方案落地，2026-09-27）。
 *
 * 引用契约：LLM 在 render_html 的 html 里以 `media://{id}` 引用相册图片/视频
 * （id 只能来自 gallery.query / media.meta / search_media 等取数结果——uri 白名单红线不动，
 * LLM 永远拿不到 content:// 路径）。渲染前 [rewriteMediaRefs] 把 `media://{id}` 重写为
 * 白名单域 URL，WebView 子资源请求经 [intercept]（WebViewAssetLoader）命中后由
 * [LocalMediaPathHandler] 用 ContentResolver 打开 MediaStore 原图/原视频流直通。
 * 落库 HTML 保持 `media://` 契约形态，URL 机制演进不影响已存消息。
 *
 * 安全/隐私设计：
 * - 白名单域 `polang-media.invalid`（`.invalid` 为 RFC 2606 保留 TLD，永不真实解析）；
 *   页面 origin 为不透明 origin（loadDataWithBaseURL baseUrl=null），与媒体域天然跨源——
 *   `<img>`/`<video>` 可正常显示，但卡内 JS 拿不到媒体字节：fetch/XHR 跨源且无 CORS 头被拒，
 *   canvas 绘制即污染（toDataURL 抛异常），LLM 产物无法把本地图片字节外传到远程；
 * - allowFileAccess/allowContentAccess 保持 false，本地媒体只走这一条白名单通道；
 * - 字节全程不出设备（本进程 ContentResolver 流），不触碰 [PRIVACY] 媒体红线。
 */
object LocalMediaWebViewAssets {

    /** LLM 侧引用契约前缀：`media://{id}`（id = MediaStore 媒体 id）。 */
    const val MEDIA_REF_SCHEME = "media://"

    /** 白名单拦截域（拦截失效时 DNS 必失败，不会误触真实站点）。 */
    const val MEDIA_HOST = "polang-media.invalid"

    /** 重写目标 URL 前缀（`<img src="$MEDIA_URL_PREFIX/media/{id}">`）。 */
    const val MEDIA_URL_PREFIX = "https://$MEDIA_HOST"

    private const val MEDIA_PATH_PREFIX = "/media/"

    /** `media://123` 纯数字 id 引用（`\b` 防止 `media://123abc` 这类半截误配）。 */
    private val MEDIA_REF_REGEX = Regex("""media://(\d+)\b""")

    /** 渲染前重写：html 中的 `media://{id}` 引用 → 白名单域 URL（幂等，无引用时原样返回）。 */
    fun rewriteMediaRefs(html: String): String {
        if (!html.contains(MEDIA_REF_SCHEME)) return html
        return MEDIA_REF_REGEX.replace(html) { match -> "$MEDIA_URL_PREFIX/media/${match.groupValues[1]}" }
    }

    @Volatile
    private var assetLoader: WebViewAssetLoader? = null

    /**
     * WebViewClient.shouldInterceptRequest 钩子：命中白名单域 → MediaStore 开流；
     * 其余请求返回 null 放行（远程资源加载行为不变）。
     */
    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? =
        assetLoader(context).shouldInterceptRequest(request.url)

    private fun assetLoader(context: Context): WebViewAssetLoader =
        assetLoader ?: synchronized(this) {
            assetLoader ?: WebViewAssetLoader.Builder()
                .setDomain(MEDIA_HOST)
                .addPathHandler(MEDIA_PATH_PREFIX, LocalMediaPathHandler(context.applicationContext))
                .build()
                .also { assetLoader = it }
        }
}

/** `/media/{id}` → MediaStore 原图/原视频流（先图片库后视频库，两库 id 空间独立）。 */
private class LocalMediaPathHandler(
    private val appContext: Context
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val id = path.trimEnd('/').toLongOrNull() ?: return null
        val candidates = listOf(
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        )
        for (uri in candidates) {
            openStream(uri)?.let { return it }
        }
        Logger.w(TAG, "local media not found or unreadable: id=$id")
        return null
    }

    /** 存在且可读 → (mime, 流) 组装响应；已删除/无权限 → null，继续尝试下一个集合。 */
    private fun openStream(uri: Uri): WebResourceResponse? {
        val resolver = appContext.contentResolver
        val mime = runCatching {
            resolver.query(uri, MIME_PROJECTION, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: return null
        val stream = runCatching { resolver.openInputStream(uri) }.getOrNull() ?: return null
        return WebResourceResponse(mime, null, stream)
    }

    private companion object {
        const val TAG = "PoLang:HtmlCard"
        val MIME_PROJECTION = arrayOf(MediaStore.MediaColumns.MIME_TYPE)
    }
}
