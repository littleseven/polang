package com.mamba.picme.features.chat

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.mamba.picme.core.common.Logger

/**
 * HTML 卡本地媒体注入层（ADR-016 D3 预留方案落地，2026-09-27）。
 *
 * 引用契约：LLM 在 render_html 的 html 里以 `media://{id}` 引用相册图片/视频
 * （id 只能来自 gallery.query / media.meta / search_media 等取数结果——uri 白名单红线不动，
 * LLM 永远拿不到 content:// 路径）。两条生效路径：
 * 1. 静态字面量：渲染前 [rewriteMediaRefs] 把 html 字符串里的 `media://{id}` 重写为
 *    白名单域 URL，WebView 子资源请求经 [intercept]（WebViewAssetLoader）命中开流；
 * 2. 运行时拼接：JS 动态赋值（`el.src='media://'+id`）不产生静态重写，[intercept]
 *    对 `media://` scheme 请求直接拦截开流（与路径 handler 共用 [openMediaStream]）。
 * 落库 HTML 保持 `media://` 契约形态，URL 机制演进不影响已存消息。
 *
 * id 命名空间（与 chat 取数结果同源，`MediaRepositoryImpl.getMediaById` 双空间解析）：
 * - 正数：Room `media_assets` 行 id（已索引媒体）；
 * - 负数：未落库系统媒体的合成 id（`-(mediaStoreId*10+typeSalt)`，salt 1=图片 2=视频）。
 *
 * 安全/隐私设计：
 * - 白名单域 `polang-media.invalid`（`.invalid` 为 RFC 2606 保留 TLD，永不真实解析）；
 *   页面 origin 为不透明 origin（loadDataWithBaseURL baseUrl=null），与媒体域天然跨源——
 *   `<img>`/`<video>` 可正常显示，但卡内 JS 拿不到媒体字节：fetch/XHR 跨源且无 CORS 头被拒，
 *   canvas 绘制即污染（toDataURL 抛异常），**字节级**无远程外泄通道
 *   （允许面：JS 可经 img onload/onerror + naturalWidth/Height 对任意 id 做存在性/尺寸探测——
 *   属元数据级信息且仅本机显示，不触碰 [PRIVACY] 红线）；
 * - allowFileAccess/allowContentAccess 保持 false，本地媒体只走这一条白名单通道；
 * - 字节全程不出设备（本进程 ContentResolver 流）。
 */
object LocalMediaWebViewAssets {

    /** LLM 侧引用契约前缀：`media://{id}`（id 为 chat 媒体 id，可为负数合成 id）。 */
    const val MEDIA_REF_SCHEME = "media://"

    /** 白名单拦截域（拦截失效时 DNS 必失败，不会误触真实站点）。 */
    const val MEDIA_HOST = "polang-media.invalid"

    /** 重写目标 URL 前缀（`<img src="$MEDIA_URL_PREFIX/media/{id}">`）。 */
    const val MEDIA_URL_PREFIX = "https://$MEDIA_HOST"

    private const val MEDIA_PATH_PREFIX = "/media/"

    /** 运行时直通 scheme（`media://{id}` 的 scheme 部分）。 */
    private const val MEDIA_SCHEME = "media"

    /** `media://123` / `media://-10000211391`（负数 = 未落库系统媒体合成 id）；`\b` 防半截误配。 */
    private val MEDIA_REF_REGEX = Regex("""media://(-?\d+)\b""")

    /**
     * chat 媒体 id → content:// Uri 解析器，由组合根（PoLangApplication）注入
     * （实现 = `MediaRepositoryImpl.getMediaById(id)?.uri`，双 id 命名空间解析的唯一真源）。
     * 未注入时本地媒体引用不生效（拦截返回 null → 破图，其余行为不变）。
     * 在 WebView 子资源加载线程上同步调用（实现内部允许短时阻塞：DB 主键查询/内存快照过滤）。
     */
    @Volatile
    var mediaUriResolver: ((Long) -> Uri?)? = null

    /** 渲染前重写：html 中的 `media://{id}` 引用 → 白名单域 URL（幂等，无引用时原样返回）。 */
    fun rewriteMediaRefs(html: String): String {
        if (!html.contains(MEDIA_REF_SCHEME)) return html
        return MEDIA_REF_REGEX.replace(html) { match -> "$MEDIA_URL_PREFIX/media/${match.groupValues[1]}" }
    }

    /**
     * `/media/` 前缀后的路径尾段 → chat 媒体 id（纯函数，便于 JVM 单测）。
     * 容忍尾部多斜杠；非数字 / Long 溢出 / 多余前导路径段（如 `/123`）一律 null
     * （调用侧破图降级，契约外输入安全）。前导零按数值归一（`007` → 7）。
     */
    internal fun parseMediaId(path: String): Long? = path.trimEnd('/').toLongOrNull()

    /**
     * 完整 `media://{id}` URL → chat 媒体 id（纯函数，JS 运行时拼接场景：
     * 入参形如 `media://123` / `media://-10000211391`）。契约外输入一律 null。
     */
    internal fun parseMediaRefId(url: String): Long? =
        MEDIA_REF_REGEX.find(url)?.groupValues?.get(1)?.toLongOrNull()

    @Volatile
    private var assetLoader: WebViewAssetLoader? = null

    /**
     * WebViewClient.shouldInterceptRequest 钩子：
     * - `media://` scheme（JS 运行时拼接，未经静态重写）→ 解析 id 直接开流；
     * - 白名单域（静态重写产物）→ WebViewAssetLoader 路径 handler 开流；
     * - 其余请求返回 null 放行（远程资源加载行为不变）。
     */
    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.scheme == MEDIA_SCHEME) {
            val id = parseMediaRefId(request.url.toString()) ?: return null
            return openMediaStream(context.applicationContext, id)
        }
        return assetLoader(context).shouldInterceptRequest(request.url)
    }

    /**
     * id → content:// 解析 → 原图/原视频流（路径 handler 与 `media://` 直通共用）。
     * 已删除/无权限/resolver 未注入 → null（WebView 显示破图，HTML 侧可 onerror 兜底）。
     */
    internal fun openMediaStream(appContext: Context, id: Long): WebResourceResponse? {
        val resolver = mediaUriResolver
        if (resolver == null) {
            Logger.w(TAG, "mediaUriResolver not wired; local media unavailable: id=$id")
            return null
        }
        val uri = runCatching { resolver.invoke(id) }.getOrNull()
        if (uri == null) {
            Logger.w(TAG, "local media id not resolvable: id=$id")
            return null
        }
        // 日志只记 id（uri 不外显口径，与 GalleryJs 白名单一致）
        return openStream(appContext, uri).also { response ->
            if (response == null) Logger.w(TAG, "local media stream open failed: id=$id")
        }
    }

    /** 开流 + 探测 MIME。 */
    private fun openStream(appContext: Context, uri: Uri): WebResourceResponse? {
        val contentResolver = appContext.contentResolver
        val stream = runCatching { contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "image/jpeg"
        return WebResourceResponse(mime, null, stream)
    }

    private fun assetLoader(context: Context): WebViewAssetLoader =
        assetLoader ?: synchronized(this) {
            assetLoader ?: WebViewAssetLoader.Builder()
                .setDomain(MEDIA_HOST)
                .addPathHandler(MEDIA_PATH_PREFIX, LocalMediaPathHandler(context.applicationContext))
                .build()
                .also { assetLoader = it }
        }

    private const val TAG = "PoLang:HtmlCard"
}

/** `/media/{id}` → 解析 chat 媒体 id → 开流（实现收口在 [LocalMediaWebViewAssets]）。 */
private class LocalMediaPathHandler(
    private val appContext: Context
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val id = LocalMediaWebViewAssets.parseMediaId(path) ?: return null
        return LocalMediaWebViewAssets.openMediaStream(appContext, id)
    }
}
