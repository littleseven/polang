package com.mamba.picme.features.chat

/**
 * render_html 卡片 HTML 清洗器（纯函数，便于单测）。
 *
 * 渲染对象是远程 LLM 生成的**不可信内容**。端侧 HtmlCard 的 WebView 已做
 * 断网（shouldInterceptRequest 拦截 http(s)）/ 零 JS 桥接 / 禁文件访问三层防线，
 * 本清洗器是落库前的第一道：剔除远程引用与高危标签，让卡片完全自包含、
 * 持久化到 Room 的也是清洗后的安全形态。
 */
object HtmlCardSanitizer {

    /** HTML 大小上限（字节，UTF-8）。超限拒绝，由调用方把原因回传 LLM 引导重新生成。 */
    const val MAX_HTML_BYTES = 128 * 1024

    sealed interface Result {
        data class Ok(val html: String) : Result

        /** [reason] 为回传 LLM 的英文原因（作为 tool observation，不是用户文案）。 */
        data class Rejected(val reason: String) : Result
    }

    // ── 剔除规则（按序应用）─────────────────────────────────────────────

    /** 带 src 的 <script> 整段剔除（外链 JS）；内联 <script> 保留（表现力来源）。 */
    private val EXTERNAL_SCRIPT =
        Regex("<script[^>]*\\bsrc\\s*=[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** <link>（外链 CSS/预加载等）整标签剔除。 */
    private val LINK_TAG = Regex("<link[^>]*>", RegexOption.IGNORE_CASE)

    /** 高危嵌入/导航标签整段剔除。 */
    private val DANGEROUS_BLOCK =
        Regex("<(iframe|object|embed|form|frame|frameset)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DANGEROUS_SELF_CLOSING = Regex("<(iframe|object|embed|frame)[^>]*>", RegexOption.IGNORE_CASE)

    /** <meta http-equiv=refresh> 跳转剔除。 */
    private val META_REFRESH = Regex("<meta[^>]*http-equiv\\s*=\\s*['\"]?refresh[^>]*>", RegexOption.IGNORE_CASE)

    /** CSS @import 剔除。 */
    private val CSS_IMPORT = Regex("@import[^;]*;", RegexOption.IGNORE_CASE)

    /** 属性中的远程 URL（src=/href=/action=/xlink:href="http(s)://…"）→ 置空。 */
    private val REMOTE_ATTR =
        Regex("\\b(src|href|action|xlink:href)\\s*=\\s*([\"'])https?://.*?\\2", RegexOption.IGNORE_CASE)

    /** CSS url(http…) → url(about:blank)。 */
    private val CSS_REMOTE_URL = Regex("url\\(\\s*['\"]?https?://[^)]*\\)", RegexOption.IGNORE_CASE)

    /**
     * 清洗 [html]：超限 → [Result.Rejected]；否则剔除远程引用/高危标签后返回 [Result.Ok]。
     */
    fun sanitize(html: String): Result {
        if (html.isBlank()) return Result.Rejected("html is blank; nothing to render")
        if (html.toByteArray(Charsets.UTF_8).size > MAX_HTML_BYTES) {
            return Result.Rejected("html exceeds ${MAX_HTML_BYTES / 1024}KB limit; shrink it and retry")
        }
        var out = html
        out = EXTERNAL_SCRIPT.replace(out, "")
        out = LINK_TAG.replace(out, "")
        out = DANGEROUS_BLOCK.replace(out, "")
        out = DANGEROUS_SELF_CLOSING.replace(out, "")
        out = META_REFRESH.replace(out, "")
        out = CSS_IMPORT.replace(out, "")
        out = REMOTE_ATTR.replace(out, "$1=$2$2")
        out = CSS_REMOTE_URL.replace(out, "url(about:blank)")
        return Result.Ok(out)
    }
}
