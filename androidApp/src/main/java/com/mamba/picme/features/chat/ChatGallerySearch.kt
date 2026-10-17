package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.MediaResultsUi

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.search.QueryParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * 相册搜索的纯逻辑支持：in-set 过滤 + Room 持久化编解码。
 *
 * 抽离自 ChatViewModel 以便 JVM 单测（避免加载 Android 依赖）。
 * 全部基于 :shared 的 [MediaAsset]（与 MediaSearchEngine / MediaPager 一致）。
 */
object ChatGallerySearch {

    /** in-set 过滤：对已持有的结果集按 constraint 命中 labels/ocr/location/fileName。 */
    fun filterInSet(assets: List<MediaAsset>, constraint: String): List<MediaAsset> {
        val kw = constraint.trim()
        if (kw.isEmpty()) return assets
        val wantsFace = isFaceIntent(kw)
        return assets.filter { a ->
            val textMatch = listOfNotNull(a.labels, a.ocrText, a.locationName, a.fileName)
                .any { text -> text.contains(kw, ignoreCase = true) }
            // 人脸是结构化条件：标签体系不会有"人脸/有人脸"字面词，必须用 hasFace 字段。
            textMatch || (wantsFace && a.hasFace)
        }
    }

    /**
     * 判断 constraint 是否表达"含人脸"意图（如"有人脸""人脸""脸""face"）。
     * 命中时 [filterInSet] 改用 [MediaAsset.hasFace] 结构化字段，而非标签子串。
     */
    private fun isFaceIntent(constraint: String): Boolean {
        val lower = constraint.lowercase()
        return lower.contains("脸") || lower.contains("face")
    }

    /**
     * 多轮 refine 的结果解析：取 searchEngine 全库命中与上一轮结果集 [prior] 的交集；
     * 交集为空时回退到 [filterInSet]（标签子串 + hasFace）兜底。
     *
     * 让 refine 享受 searchEngine 的语义/结构化能力，又不丢 in-set 收敛语义。
     */
    fun resolveRefine(
        prior: List<MediaAsset>,
        searchHits: List<MediaAsset>,
        constraint: String
    ): List<MediaAsset> {
        // 优先 filterInSet（标签子串 + hasFace 等结构化字段，精准）；
        // filterInSet 空（如「女性」标签字面不匹配）时，回退到 searchEngine 语义命中 ∩ prior。
        val inSet = filterInSet(prior, constraint)
        if (inSet.isNotEmpty()) return inSet
        val priorIds = prior.map { asset -> asset.id }.toSet()
        return searchHits.filter { hit -> hit.id in priorIds }
    }

    /**
     * 清理 refine constraint：去掉「只保留/只要/其中的/的照片」等口语前缀后缀，
     * 提取核心词，避免整句（如「只保留女性」）导致下游 searchEngine 翻译/语义失配。
     */
    fun cleanConstraint(constraint: String): String {
        var cleaned = constraint.trim()
        val prefixes = listOf("只保留", "只要", "保留", "其中的", "其中", "去掉", "不要", "排除")
        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix)) {
                cleaned = cleaned.removePrefix(prefix).trim()
                break
            }
        }
        val suffixes = listOf("的照片", "的图片", "照片", "图片", "的")
        for (suffix in suffixes) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.removeSuffix(suffix).trim()
                break
            }
        }
        // 性别归一：标签体系用单字「女/男」（labels/summary 里是「一位女士」「女」等），
        // 把「女性/女人/女孩」等归一到单字，使 filterInSet 标签子串命中（否则「女性」≠「女」漏报）。
        val genderMap = mapOf(
            "女性" to "女", "女人" to "女", "女孩" to "女", "少女" to "女", "女生" to "女",
            "男性" to "男", "男人" to "男", "男孩" to "男", "少年" to "男", "男生" to "男"
        )
        genderMap[cleaned]?.let { normalized -> cleaned = normalized }
        return cleaned
    }

    // ── SearchIntent → StructuredFilter 转换（自 ChatViewModel 收口，chat 链路与云端 Agent 工具桥共用）──

    /**
     * 时间专属词集合。当 [SearchIntent.timeRange] 已经表达了时间范围时，
     * 这些词不应再作为内容关键词去匹配标签/OCR/文件名，否则会导致时间候选集与空标签候选集交集为空。
     */
    private val timeOnlyKeywords = setOf(
        "去年", "今年", "明年", "前年", "后年",
        "春天", "夏天", "秋天", "冬天", "春季", "夏季", "秋季", "冬季",
        "上半年", "下半年", "近半年", "最近半年", "半年",
        "近一年", "最近一年", "一年", "近几年", "最近几年",
        "最近", "近三个月", "近3个月",
        "今天", "昨天", "前天", "明天", "后天",
        "上周", "本周", "下周",
        "上星期", "这星期", "下星期", "上个星期", "这个星期", "下个星期",
        "上个月", "这个月", "下个月", "上月", "今月", "下月"
    )

    /** 匹配“3月”“12月”“五月”等月份表达。 */
    private val monthKeywordRegex = Regex("""^(\d{1,2}月|[一二三四五六七八九十]{1,3}月)$""")

    /**
     * [SearchIntent] → [StructuredFilter] 一站式转换（字符串解析兜底 + 时间词清洗 + 结构转换）。
     * chat 链路（ChatViewModel）与云端 Agent 工具桥（CloudAgentGalleryTools）共用同一入口。
     */
    fun structuredFilterOf(intent: SearchIntent, rawText: String): StructuredFilter =
        searchIntentToStructuredFilter(withStringParseBackstop(intent, rawText))

    /**
     * 字符串解析兜底（2026-09-29 hasFaces / 2026-09-30 timeRange）：结构化入口（路由器
     * 直执 / search_media 工具）一旦填了任一结构化参数，就跳过字符串解析路径；上游漏填的
     * 维度会被整句丢弃（实证：LLM 只传 hasFace 不传 fromMs/toMs 时，"去年夏天"约束丢失，
     * 返回其他年份的人脸照片）。这里对原始文本再跑一次规则判定（与字符串搜索路径
     * [QueryParser] 同口径），把丢失的人脸/时间约束补回；上游已显式给出的维度尊重上游，
     * 不覆盖。
     */
    fun withStringParseBackstop(intent: SearchIntent, rawText: String): SearchIntent {
        var result = intent
        if (result.hasFaces == null && QueryParser.isPeopleSearch(rawText)) {
            result = result.copy(hasFaces = true)
        }
        if (result.timeRange == null) {
            QueryParser.parseTimeRange(rawText)?.let { parsed ->
                result = result.copy(
                    timeRange = TimeRange(startMs = parsed.startMs, endMs = parsed.endMs)
                )
            }
        }
        return result
    }

    /**
     * 将 :shared 的 [SearchIntent] 转换为 app 层的 [StructuredFilter]。
     *
     * 转换前先做时间词清洗：只要 [SearchIntent.timeRange] 已给出，就把“夏天”“去年”等
     * 时间专属词从 keywords / ocrKeywords / locationKeywords 中剔除，避免引擎把
     * 时间约束与空内容候选集取交集导致 0 结果。
     */
    fun searchIntentToStructuredFilter(intent: SearchIntent): StructuredFilter {
        val sanitized = sanitizeTimeKeywords(intent)
        return StructuredFilter(
            timeRange = sanitized.timeRange?.let {
                com.mamba.picme.domain.model.TimeRange(startMs = it.startMs, endMs = it.endMs)
            },
            keywords = sanitized.keywords,
            ocrKeywords = sanitized.ocrKeywords,
            locationKeywords = sanitized.locationKeywords,
            personName = sanitized.personName,
            hasFaces = sanitized.hasFaces,
            needsLlm = false
        )
    }

    /**
     * 当意图中同时存在 [SearchIntent.timeRange] 和时间专属词时，剔除这些时间专属词。
     * 这是 Prompt 约束之外的第二层保险，防止小模型/远程模型仍把“夏天”当成内容关键词。
     */
    private fun sanitizeTimeKeywords(intent: SearchIntent): SearchIntent {
        if (intent.timeRange == null) return intent
        fun isTimeOnly(word: String): Boolean = word in timeOnlyKeywords || monthKeywordRegex.matches(word)
        return intent.copy(
            keywords = intent.keywords.filterNot(::isTimeOnly),
            ocrKeywords = intent.ocrKeywords.filterNot(::isTimeOnly),
            locationKeywords = intent.locationKeywords.filterNot(::isTimeOnly)
        )
    }

    /** 把展示用 assets 序列化为 Room content JSON（id/uri/type/captureDate/fileName）。 */
    fun serializeContent(assets: List<MediaAsset>): String {
        val arr = JSONArray()
        for (a in assets) {
            val o = JSONObject()
            o.put("id", a.id)
            o.put("uri", a.uri)
            o.put("type", a.type.name)
            o.put("captureDate", a.captureDate)
            o.put("fileName", a.fileName)
            a.faceFocusY?.let { o.put("faceFocusY", it.toDouble()) }
            arr.put(o)
        }
        return arr.toString()
    }

    /** query/totalCount/isRefinement 存入 metadata JSON。 */
    fun serializeMetadata(query: String, totalCount: Int, isRefinement: Boolean): String =
        JSONObject()
            .put("query", query)
            .put("totalCount", totalCount)
            .put("isRefinement", isRefinement)
            .toString()

    /** 从 Room content + metadata 反序列化为 MediaResultsUi（重建最小 MediaAsset）。 */
    fun deserialize(content: String, metadata: String?): MediaResultsUi {
        val arr = JSONArray(content)
        val assets = ArrayList<MediaAsset>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assets.add(
                MediaAsset(
                    id = o.getLong("id"),
                    uri = o.getString("uri"),
                    type = runCatching { MediaType.valueOf(o.optString("type", "PHOTO")) }
                        .getOrDefault(MediaType.PHOTO),
                    captureDate = o.getLong("captureDate"),
                    fileName = o.optString("fileName", ""),
                    faceFocusY = if (o.has("faceFocusY")) o.getDouble("faceFocusY").toFloat() else null
                )
            )
        }
        val meta = metadata?.let { runCatching { JSONObject(it) }.getOrNull() }
        return MediaResultsUi(
            query = meta?.optString("query", "") ?: "",
            assets = assets,
            totalCount = meta?.optInt("totalCount", assets.size) ?: assets.size,
            isRefinement = meta?.optBoolean("isRefinement", false) ?: false
        )
    }
}
