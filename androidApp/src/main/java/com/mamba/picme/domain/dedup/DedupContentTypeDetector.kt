package com.mamba.picme.domain.dedup

/** 尺寸未知（WIDTH/HEIGHT 列缺失或脏值 ≤0）时 OCR 判文档的绝对字符数兜底阈值。 */
internal const val DOCUMENT_OCR_CHAR_THRESHOLD = 200

/**
 * DOCUMENT 文字密度阈值（字符数 / 百万像素，spec §10.2 面积归一）。
 * 依据：旧绝对阈值 200 字符在典型 12MP 照片上等价约 17 字符/MP，取整 20；
 * 归一后大图低文字密度（海报/路牌/漫画）不再误判文档，小图密集文字仍可命中。
 */
internal const val DOCUMENT_OCR_DENSITY_PER_MEGAPIXEL = 20

/** 百万像素归一因子（pixelArea → MP）。 */
private const val PIXELS_PER_MEGAPIXEL = 1_000_000L

/** MediaStore 截图目录约定（路径 contains，大小写不敏感）。 */
internal const val SCREENSHOT_DIR_KEYWORD = "screenshots"

/**
 * DOCUMENT 标签关键词启发式：labels 为 TAG Pass 3 产出的自由文本（中英混合）。
 * 英文按整词（token）匹配并容忍可选复数后缀（documents/receipts/texts），
 * 避免 `context`/`texture`/`textile` 被子串 "text" 误伤；
 * 中文无词边界，按子串匹配（"截图文字" 等复合词由 "文字" 覆盖，不单独列死条目）。
 * 误伤代价仅是 VISUAL 组不预选；漏检更危险（DOCUMENT 误归 GENERAL 会被自动预选）。
 */
private val DOCUMENT_LABEL_KEYWORDS_EN = listOf("document", "receipt", "text", "screenshot_text")
private val DOCUMENT_LABEL_KEYWORDS_ZH = listOf("文档", "证件", "票据", "文字")

private val LABEL_TOKEN_REGEX = Regex("[a-z0-9_]+")

/** labels 命中文档关键词：中文子串 + 英文整词双通道。 */
private fun labelsIndicateDocument(labels: String): Boolean {
    val lower = labels.lowercase()
    if (DOCUMENT_LABEL_KEYWORDS_ZH.any { keyword -> lower.contains(keyword) }) return true
    val tokens = LABEL_TOKEN_REGEX.findAll(lower).map { match -> match.value }.toHashSet()
    return DOCUMENT_LABEL_KEYWORDS_EN.any { keyword ->
        tokens.any { token -> token == keyword || token == keyword + "s" }
    }
}

/**
 * OCR 文字密度判定（spec §10.2 面积归一）：[pixelArea] 可用时按字符数/图面积，
 * 否则（尺寸列缺失或脏值）退回绝对字符数兜底。
 */
private fun isDocumentText(ocrText: String?, pixelArea: Long?): Boolean {
    val chars = ocrText?.length ?: 0
    if (chars == 0) return false
    return if (pixelArea != null && pixelArea > 0) {
        chars.toLong() * PIXELS_PER_MEGAPIXEL > pixelArea * DOCUMENT_OCR_DENSITY_PER_MEGAPIXEL
    } else {
        chars > DOCUMENT_OCR_CHAR_THRESHOLD
    }
}

/**
 * 内容类型识别纯函数（spec §10.2，零额外推理，可 JVM 单测）。
 * 优先级 SCREENSHOT > DOCUMENT > PORTRAIT > GENERAL；TAG 未覆盖（信号全空）一律 GENERAL。
 *
 * 位于 domain 层：dedup 取数（features/gallery/dedup）与整理中心 DOCUMENT 类目判定
 * （domain/organize/OrganizeCategorizer）同引本实现，避免 domain → features 分层倒置。
 *
 * @param path RELATIVE_PATH（API 29+）或 DATA 列兜底路径，用于截图目录判定。
 * @param pixelArea 图片像素面积（WIDTH×HEIGHT），未知时传 null。
 */
fun detectContentType(
    path: String?,
    ocrText: String?,
    pixelArea: Long?,
    labels: String?,
    hasFace: Boolean,
    faceQualityScore: Float?,
): DedupContentType = when {
    path?.contains(SCREENSHOT_DIR_KEYWORD, ignoreCase = true) == true ->
        DedupContentType.SCREENSHOT
    isDocumentText(ocrText, pixelArea) || labels?.let(::labelsIndicateDocument) == true ->
        DedupContentType.DOCUMENT
    hasFace || faceQualityScore != null -> DedupContentType.PORTRAIT
    else -> DedupContentType.GENERAL
}
