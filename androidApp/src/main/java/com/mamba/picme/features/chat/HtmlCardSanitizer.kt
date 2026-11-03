package com.mamba.picme.features.chat

/**
 * render_html 卡片 HTML 清洗器（纯函数，便于单测）。
 *
 * 渲染对象是远程 LLM 生成的**不可信内容**。端侧 HtmlCard 的 WebView 保持
 * 零 JS 桥接 / 禁文件访问防线；2026-09-25 起远程资源（img/CSS/`<a>` 外链）放行
 * （表现力优先，暂时放开），本清洗器保留的剔除面收窄为：
 * 远程 script（远程 JS 执行是底线）、iframe/object/embed/form 等高危嵌入、
 * meta refresh 自动跳转。`<a href="https://…">` 保留——点击由卡片回调宿主打开落地页。
 *
 * 2026-11-04 SOE 修复：带 src 的 script 与高危嵌入的整段剔除由懒惰点星正则
 * （`.*?</script>`，GroupCurly 逐字符递归，tag 体超长/缺闭合扫到全文尾爆
 * StackOverflowError）改为单次手动扫描，语义对齐旧正则（无闭合标签的残缺段保留）。
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

    /** 标签内 src 属性检测（作用于单个标签文本，规模有界，正则安全）。
     *  词边界语义对齐旧正则：`<script data-src=…>` 同样命中（`\b` 在 `-` 与 `s` 间成立）。 */
    private val SCRIPT_SRC = Regex("\\bsrc\\s*=")

    /** 高危嵌入/导航标签（长名在前，防 frame 截胡 frameset；iframe 可嵌任意远程页面，
     *  form 可提交数据，仍不放行）。 */
    private val DANGEROUS_TAGS = listOf("frameset", "iframe", "object", "embed", "frame", "form")

    /** 自闭合形态高危标签（无配对闭合标签，单剔除开标签）。`[^>]*` 字符组量词无递归，安全。 */
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
        out = removeTagBlocks(out, listOf("script"), onlyWithSrc = true)
        out = removeTagBlocks(out, DANGEROUS_TAGS, onlyWithSrc = false)
        out = DANGEROUS_SELF_CLOSING.replace(out, "")
        out = META_REFRESH.replace(out, "")
        return Result.Ok(out)
    }

    /**
     * 整段剔除 `<tag …>…</tag>`（大小写不敏感，单次原文扫描，O(n) 零递归）：
     * [onlyWithSrc] = true 时仅剔除带 src 属性的标签段（远程 script 是底线，内联 script 保留）；
     * 无闭合标签的残缺段保留（对齐旧懒惰点星正则「无闭合不匹配」语义）。
     */
    private fun removeTagBlocks(html: String, tags: List<String>, onlyWithSrc: Boolean): String {
        val out = StringBuilder(html.length)
        var cursor = 0 // 已确认保留区间的末端（原文下标）
        var index = 0
        while (index < html.length) {
            if (html[index] == '<') {
                val tag = tags.firstOrNull { name ->
                    html.regionMatches(index + 1, name, 0, name.length, ignoreCase = true)
                }
                if (tag != null) {
                    val tagEnd = html.indexOf('>', index + 1)
                    if (tagEnd >= 0) {
                        val tagText = html.substring(index, tagEnd + 1)
                        if (!onlyWithSrc || SCRIPT_SRC.containsMatchIn(tagText)) {
                            val close = indexOfIgnoreCase(html, "</$tag>", tagEnd + 1)
                            if (close >= 0) {
                                out.append(html, cursor, index)
                                index = close + tag.length + 3
                                cursor = index
                                continue
                            }
                        }
                    }
                }
            }
            index++
        }
        if (cursor == 0) return html // 无剔除，原样返回
        out.append(html, cursor, html.length)
        return out.toString()
    }

    /** 大小写不敏感子串查找，返回起始下标或 -1。 */
    private fun indexOfIgnoreCase(haystack: String, needle: String, fromIndex: Int): Int {
        val last = haystack.length - needle.length
        var i = fromIndex.coerceAtLeast(0)
        while (i <= last) {
            if (haystack.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }
}
