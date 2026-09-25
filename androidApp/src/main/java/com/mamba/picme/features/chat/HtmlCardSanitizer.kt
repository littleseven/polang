package com.mamba.picme.features.chat

/**
 * render_html 卡片 HTML 清洗器（纯函数，便于单测）。
 *
 * 渲染对象是远程 LLM 生成的**不可信内容**。端侧 HtmlCard 的 WebView 保持
 * 零 JS 桥接 / 禁文件访问防线；2026-09-25 起远程资源（img/CSS/`<a>` 外链）放行
 * （表现力优先，暂时放开），本清洗器保留的剔除面收窄为：
 * 远程 script（远程 JS 执行是底线）、iframe/object/embed/form 等高危嵌入、
 * meta refresh 自动跳转。`<a href="https://…">` 保留——点击由卡片回调宿主打开落地页。
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

    /** 带 src 的 <script> 整段剔除（远程 JS 执行是底线）；内联 <script> 保留（表现力来源）。 */
    private val EXTERNAL_SCRIPT =
        Regex("<script[^>]*\\bsrc\\s*=[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** 高危嵌入/导航标签整段剔除（iframe 可嵌任意远程页面，form 可提交数据，仍不放行）。 */
    private val DANGEROUS_BLOCK =
        Regex("<(iframe|object|embed|form|frame|frameset)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DANGEROUS_SELF_CLOSING = Regex("<(iframe|object|embed|frame)[^>]*>", RegexOption.IGNORE_CASE)

    /** <meta http-equiv=refresh> 跳转剔除（卡片不允许自动跳走）。 */
    private val META_REFRESH = Regex("<meta[^>]*http-equiv\\s*=\\s*['\"]?refresh[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * 清洗 [html]：超限 → [Result.Rejected]；否则剔除远程 script/高危标签后返回 [Result.Ok]。
     * 远程 img/CSS/`<a>` 外链保留（2026-09-25 起放开，表现力优先）。
     */
    fun sanitize(html: String): Result {
        if (html.isBlank()) return Result.Rejected("html is blank; nothing to render")
        if (html.toByteArray(Charsets.UTF_8).size > MAX_HTML_BYTES) {
            return Result.Rejected("html exceeds ${MAX_HTML_BYTES / 1024}KB limit; shrink it and retry")
        }
        var out = html
        out = EXTERNAL_SCRIPT.replace(out, "")
        out = DANGEROUS_BLOCK.replace(out, "")
        out = DANGEROUS_SELF_CLOSING.replace(out, "")
        out = META_REFRESH.replace(out, "")
        return Result.Ok(out)
    }
}
