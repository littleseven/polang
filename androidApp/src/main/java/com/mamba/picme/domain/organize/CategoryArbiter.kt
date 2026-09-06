package com.mamba.picme.domain.organize

import com.mamba.picme.domain.dedup.DedupContentType
import com.mamba.picme.domain.dedup.detectContentType

/** MediaStore 截图目录约定（路径 contains，大小写不敏感；与 dedup 侧同一规则）。 */
private const val SCREENSHOT_DIR_KEYWORD = "screenshots"

/** 录屏目录/文件名关键词（各 ROM 录屏应用目录约定，大小写不敏感）。 */
private val SCREEN_RECORDING_KEYWORDS = listOf("screenrecord", "screen_record", "录屏")

/**
 * 整理中心 v2 互斥类目裁定（纯函数，零 Android 依赖，JVM 可测）：
 * 按 [OrganizeCategory] 声明顺序（=优先级）先命中先得，一张媒体只进一个桶。
 * 裁定域约束：视频仅参与 DUPLICATES / SCREEN_CONTENT / LARGE_FILES。
 */
object CategoryArbiter {

    /** 返回唯一命中类目；无任何命中返回 null（不属任何清理类目）。 */
    fun classify(item: OrganizeItem): OrganizeCategory? {
        // 1. 重复与相似
        if (item.exactDupGroupSize >= 2 || item.similarDupGroupSize >= 2) {
            return OrganizeCategory.DUPLICATES
        }
        // 2. 屏幕内容（截图 + 录屏）
        if (isScreenContent(item)) return OrganizeCategory.SCREEN_CONTENT
        if (!item.isVideo) {
            // 3. 文档与票据（沿用 dedup 内容类型判定，同引避免分层倒置）
            val contentType = detectContentType(
                path = item.relativePath,
                ocrText = item.ocrText,
                pixelArea = item.pixelArea,
                labels = item.labels,
                hasFace = item.hasFace,
                faceQualityScore = item.faceQualityScore,
            )
            if (contentType == DedupContentType.DOCUMENT) return OrganizeCategory.DOCUMENTS
            // 4. 低质人像
            val faceQuality = item.faceQualityScore
            if (item.hasFace && faceQuality != null &&
                faceQuality < OrganizeThresholds.FACE_QUALITY_LOW
            ) {
                return OrganizeCategory.LOW_QUALITY_PORTRAITS
            }
            // 5. 低质量照片（真模糊 / 曝光异常；NIMA 仅作置信辅助，不定类）
            if (isLowQualityPhoto(item)) return OrganizeCategory.LOW_QUALITY_PHOTOS
        }
        // 6. 大文件
        if (isLargeFile(item)) return OrganizeCategory.LARGE_FILES
        return null
    }

    private fun isScreenContent(item: OrganizeItem): Boolean {
        val path = item.relativePath ?: return false
        if (path.contains(SCREENSHOT_DIR_KEYWORD, ignoreCase = true)) return true
        return item.isVideo && SCREEN_RECORDING_KEYWORDS.any { keyword ->
            path.contains(keyword, ignoreCase = true)
        }
    }

    private fun isLowQualityPhoto(item: OrganizeItem): Boolean {
        val blur = item.blurScore
        if (blur != null && blur < OrganizeThresholds.BLUR_VARIANCE_LOW) return true
        val exposure = item.exposureScore
        return exposure != null &&
            (exposure < OrganizeThresholds.EXPOSURE_UNDER || exposure > OrganizeThresholds.EXPOSURE_OVER)
    }

    private fun isLargeFile(item: OrganizeItem): Boolean =
        if (item.isVideo) {
            item.sizeBytes >= OrganizeThresholds.LARGE_VIDEO_BYTES
        } else {
            val area = item.pixelArea ?: 0L
            area >= OrganizeThresholds.LARGE_PHOTO_PIXEL_AREA &&
                item.sizeBytes >= OrganizeThresholds.LARGE_PHOTO_BYTES
        }
}
