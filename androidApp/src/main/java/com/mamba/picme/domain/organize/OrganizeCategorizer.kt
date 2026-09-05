package com.mamba.picme.domain.organize

import com.mamba.picme.domain.dedup.DedupContentType
import com.mamba.picme.domain.dedup.detectContentType

/** MediaStore 截图目录约定（路径 contains，大小写不敏感；与 dedup 侧同一规则）。 */
private const val SCREENSHOT_DIR_KEYWORD = "screenshots"

/** hub 卡片预览缩略图张数。 */
private const val PREVIEW_LIMIT = 4

/**
 * 整理中心类目判定纯函数（零额外推理，可 JVM 单测）。
 * DUPLICATES 不在此判定——去重分组由 DedupScanner 产出，hub 卡只展示其摘要。
 * 一条媒体可同时属于多个类目（集合语义），阈值一律常量化。
 */
object OrganizeCategorizer {

    /** NIMA 美学分（1~10）低于该值判模糊低质；null = 未评分，绝不参与判定。 */
    const val AESTHETIC_LOW_THRESHOLD = 3.5f

    /** eDifFIQA 人脸质量分（0~1）低于该值判低质量人像；null = 未评分，绝不参与判定。 */
    const val FACE_QUALITY_LOW_THRESHOLD = 0.35f

    /** 大视频阈值（100 MiB）。 */
    const val LARGE_VIDEO_BYTES = 100L * 1024 * 1024

    fun categoriesOf(item: OrganizeItem): Set<OrganizeCategory> {
        val categories = mutableSetOf<OrganizeCategory>()
        if (item.relativePath?.contains(SCREENSHOT_DIR_KEYWORD, ignoreCase = true) == true) {
            categories += OrganizeCategory.SCREENSHOTS
        }
        if (item.isVideo) {
            if (item.sizeBytes >= LARGE_VIDEO_BYTES) {
                categories += OrganizeCategory.LARGE_VIDEOS
            }
        } else {
            val aesthetic = item.aestheticScore
            if (aesthetic != null && aesthetic < AESTHETIC_LOW_THRESHOLD) {
                categories += OrganizeCategory.BLURRY
            }
            val faceQuality = item.faceQualityScore
            if (item.hasFace && faceQuality != null && faceQuality < FACE_QUALITY_LOW_THRESHOLD) {
                categories += OrganizeCategory.LOW_QUALITY_PORTRAITS
            }
            val contentType = detectContentType(
                path = item.relativePath,
                ocrText = item.ocrText,
                pixelArea = item.pixelArea,
                labels = item.labels,
                hasFace = item.hasFace,
                faceQualityScore = item.faceQualityScore,
            )
            if (contentType == DedupContentType.DOCUMENT) {
                categories += OrganizeCategory.DOCUMENTS
            }
        }
        return categories
    }

    /** 按枚举声明顺序聚合，过滤空类目；previewUris 取该类目前 [PREVIEW_LIMIT] 个。 */
    fun stats(items: List<OrganizeItem>): List<CategoryStat> =
        OrganizeCategory.entries.mapNotNull { category ->
            if (category == OrganizeCategory.DUPLICATES) return@mapNotNull null
            val matched = items.filter { item -> category in categoriesOf(item) }
            if (matched.isEmpty()) return@mapNotNull null
            CategoryStat(
                category = category,
                count = matched.size,
                totalBytes = matched.sumOf { item -> item.sizeBytes },
                previewUris = matched.take(PREVIEW_LIMIT).map { item -> item.uri },
            )
        }
}
