package com.mamba.picme.domain.organize

/**
 * 置信度分级（纯函数）：决定详情页三段分组与默认勾选口径。
 * HIGH = 强信号命中（默认勾选）；MEDIUM = 单信号接近阈值（仅列出）；
 * LOW = 弱信号（美学分等辅助信号单独命中）。
 * 前置：item 已经 CategoryArbiter 裁定入 [category]，本层只评估信号强度。
 */
object ConfidenceGrader {

    fun grade(item: OrganizeItem, category: OrganizeCategory): OrganizeConfidence =
        when (category) {
            OrganizeCategory.DUPLICATES ->
                if (item.exactDupGroupSize >= 2) OrganizeConfidence.HIGH else OrganizeConfidence.MEDIUM

            // 路径判定为确定性强信号
            OrganizeCategory.SCREEN_CONTENT -> OrganizeConfidence.HIGH

            OrganizeCategory.DOCUMENTS -> gradeDocument(item)

            OrganizeCategory.LOW_QUALITY_PORTRAITS -> {
                val faceQuality = item.faceQualityScore ?: 1.0f
                if (faceQuality < OrganizeThresholds.FACE_QUALITY_LOW * OrganizeThresholds.STRONG_SIGNAL_FACTOR) {
                    OrganizeConfidence.HIGH
                } else {
                    OrganizeConfidence.MEDIUM
                }
            }

            OrganizeCategory.LOW_QUALITY_PHOTOS -> gradeLowQualityPhoto(item)

            OrganizeCategory.LARGE_FILES ->
                if (item.sizeBytes >= OrganizeThresholds.LARGE_FILE_STRONG_FACTOR * largeFileThreshold(item)) {
                    OrganizeConfidence.HIGH
                } else {
                    OrganizeConfidence.MEDIUM
                }
        }

    /** OCR 强命中（≥2×密度阈值）HIGH；OCR 命中或仅标签命中 MEDIUM。 */
    private fun gradeDocument(item: OrganizeItem): OrganizeConfidence {
        val chars = item.ocrText?.length ?: 0
        if (chars == 0) return OrganizeConfidence.MEDIUM // labels 关键词命中（无 OCR 佐证）
        val area = item.pixelArea
        return if (area != null && area > 0) {
            // 与 DedupContentTypeDetector 同一面积归一口径的 2×（先乘后除，避免 sub-MP 整数截断塌缩为 0）
            if (chars.toLong() * 1_000_000L >= area * OrganizeThresholds.OCR_DENSITY_PER_MEGAPIXEL * OrganizeThresholds.OCR_STRONG_FACTOR) {
                OrganizeConfidence.HIGH
            } else {
                OrganizeConfidence.MEDIUM
            }
        } else {
            if (chars.toLong() >= OrganizeThresholds.OCR_DENSITY_FALLBACK_CHARS.toLong() * OrganizeThresholds.OCR_STRONG_FACTOR) {
                OrganizeConfidence.HIGH
            } else {
                OrganizeConfidence.MEDIUM
            }
        }
    }

    /** 模糊强命中 HIGH；模糊/曝光任一边界命中 MEDIUM；其余（含 null 信号）LOW。 */
    private fun gradeLowQualityPhoto(item: OrganizeItem): OrganizeConfidence {
        val blur = item.blurScore
        if (blur != null && blur < OrganizeThresholds.BLUR_VARIANCE_LOW * OrganizeThresholds.STRONG_SIGNAL_FACTOR) {
            return OrganizeConfidence.HIGH
        }
        val blurred = blur != null && blur < OrganizeThresholds.BLUR_VARIANCE_LOW
        val exposure = item.exposureScore
        val badExposure = exposure != null &&
            (exposure < OrganizeThresholds.EXPOSURE_UNDER || exposure > OrganizeThresholds.EXPOSURE_OVER)
        if (blurred || badExposure) return OrganizeConfidence.MEDIUM
        // 防御性兜底：arbiter 不定类 NIMA-only 项，正常管线不可达；null 信号落最低档方向安全
        return OrganizeConfidence.LOW
    }

    /** 大文件主阈值：视频按字节，照片按字节（像素面积门槛已在 CategoryArbiter 裁定）。 */
    private fun largeFileThreshold(item: OrganizeItem): Long =
        if (item.isVideo) {
            OrganizeThresholds.LARGE_VIDEO_BYTES
        } else {
            OrganizeThresholds.LARGE_PHOTO_BYTES
        }
}
