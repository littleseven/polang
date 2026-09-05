package com.mamba.picme.domain.organize

/**
 * 整理中心可清理类目。DUPLICATES 不在 OrganizeCategorizer 判定（hub 卡只展示 dedup 摘要），
 * 声明在枚举中保证类目全集单一事实来源。
 */
enum class OrganizeCategory { DUPLICATES, SCREENSHOTS, BLURRY, LOW_QUALITY_PORTRAITS, LARGE_VIDEOS, DOCUMENTS }

/** 类目判定输入：Room 列 + MediaStore 批量 meta（path/sizeBytes）合并后的扁平快照。 */
data class OrganizeItem(
    val uri: String,
    val isVideo: Boolean,
    val captureDate: Long,
    val sizeBytes: Long,            // 未知 = 0
    val relativePath: String?,
    val ocrText: String?,
    val pixelArea: Long?,
    val labels: String?,
    val hasFace: Boolean,
    val aestheticScore: Float?,     // null = 未评分（必须排除）
    val faceQualityScore: Float?,
)

data class CategoryStat(
    val category: OrganizeCategory,
    val count: Int,
    val totalBytes: Long,
    val previewUris: List<String>,  // 前 4 张
)
