package com.mamba.picme.features.chat

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.mamba.picme.core.common.Logger

/** 预制库白名单域（RFC 2606 保留 TLD，永不真实解析；拦截失效即 DNS 失败，不会误触真实站点）。 */
private const val LIB_HOST = "polang-lib.invalid"

/**
 * HTML 卡预制 JS 库声明式注入层（2026-11-08，ECharts 低成本落地）。
 *
 * 契约：LLM 在 render_html 的 html 里写 `<meta name="polang-lib" content="echarts">`
 * 声明依赖（meta 标签过 [HtmlCardSanitizer] 不被剔除——只剔 meta refresh）；渲染期
 * [injectDeclaredLibs] 把声明展开为白名单域 `<script src>`，请求经 [intercept] 从 app
 * assets 开流（版本随 APK 钉死，离线可用）。三道闸保证 LLM 无法借此引入任意脚本：
 * 1. 声明名 → 资产是**闭集映射**（[LIBS]），未知库名静默忽略；
 * 2. 直接手写 `<script src=...>` 会被 sanitizer 整段剔除，声明式是唯一通路；
 * 3. 注入只发生在渲染期，落库 HTML 保持 meta 契约形态，URL 机制演进不影响已存消息。
 *
 * 与 [LocalMediaWebViewAssets] 同源设计：页面为不透明 origin（baseUrl=null），
 * 经典 `<script src>` 跨源加载不需要 CORS 即可执行，零 JS 桥接防线不变。
 */
object LocalWebViewLibs {

    /** 预制库定义：[urlPath] 白名单域路径（注入用），[assetPath] assets 内路径。 */
    private data class LibDef(val urlPath: String, val assetPath: String, val mime: String) {
        val url: String get() = "https://$LIB_HOST$urlPath"
    }

    /** 支持的预制库闭集：声明名 → 定义。echarts = 5.6.0 全量 min 构建（约 1MB）。 */
    private val LIBS: Map<String, LibDef> = mapOf(
        "echarts" to LibDef(
            urlPath = "/echarts/echarts.min.js",
            assetPath = "polang-lib/echarts.min.js",
            mime = "application/javascript"
        )
    )

    private const val META_NAME = "polang-lib"

    private val META_TAG_REGEX = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val LIB_NAME_ATTR_REGEX =
        Regex("""name\s*=\s*["']polang-lib["']""", RegexOption.IGNORE_CASE)
    private val CONTENT_ATTR_REGEX =
        Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN_REGEX = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
    private val BODY_OPEN_REGEX = Regex("<body[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * 渲染期展开声明：`<head>` 开标签后注入（同步加载，卡内后续 inline script 可直接用
     * echarts 全局）；无 `<head>` 退 `<body>` 前，再退文档最前。幂等——URL 已在文档中时
     * 不重复注入（同一 html 重复处理/多 meta 声明同一库均不叠加）。
     */
    fun injectDeclaredLibs(html: String): String {
        val libs = declaredLibs(html).mapNotNull { LIBS[it] }.filterNot { html.contains(it.url) }
        if (libs.isEmpty()) return html
        val tags = libs.joinToString("") { "<script src=\"${it.url}\"></script>" }
        HEAD_OPEN_REGEX.find(html)
            ?.let { return html.replaceRange(it.range.last + 1, it.range.last + 1, tags) }
        BODY_OPEN_REGEX.find(html)
            ?.let { return html.replaceRange(it.range.first, it.range.first, tags) }
        return tags + html
    }

    /**
     * 解析全部 `<meta name="polang-lib" content="...">` 声明（content 逗号/空格分隔多库），
     * 闭集过滤 + 去重保序；无声明或全部未知 → 空表。属性顺序（name/content 先后）不敏感。
     */
    internal fun declaredLibs(html: String): List<String> {
        if (!html.contains(META_NAME)) return emptyList()
        val result = LinkedHashSet<String>()
        META_TAG_REGEX.findAll(html)
            .map { it.value }
            .filter { LIB_NAME_ATTR_REGEX.containsMatchIn(it) }
            .mapNotNull { CONTENT_ATTR_REGEX.find(it)?.groupValues?.get(1) }
            .flatMap { it.split(',', ' ') }
            .map { it.trim().lowercase() }
            .filterTo(result) { LIBS.containsKey(it) }
        return result.toList()
    }

    /** 预制库注入 URL（测试断言与 skill 契约共用口径）。 */
    internal fun libUrl(name: String): String? = LIBS[name]?.url

    /**
     * WebViewClient.shouldInterceptRequest 钩子：命中预制库 URL → assets 开流；
     * 其余请求返回 null 放行（本地媒体/远程资源行为不变）。
     */
    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        if (!uri.host.equals(LIB_HOST, ignoreCase = true)) return null
        val path = uri.path ?: return null
        val lib = LIBS.values.firstOrNull { it.urlPath == path } ?: return null
        val stream = runCatching { context.applicationContext.assets.open(lib.assetPath) }
            .getOrNull()
        if (stream == null) {
            Logger.w(TAG, "bundled lib asset missing: ${lib.assetPath}")
            return null
        }
        return WebResourceResponse(lib.mime, "utf-8", stream)
    }

    private const val TAG = "PoLang:WebViewLibs"
}
