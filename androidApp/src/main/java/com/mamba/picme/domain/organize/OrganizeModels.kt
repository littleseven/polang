package com.mamba.picme.domain.organize

/**
 * 整理中心 v2 可清理类目（互斥：一张媒体只进一个桶，CategoryArbiter 按声明顺序裁定）。
 * 枚举名是 `organize_category/{category}` 路由段与 strings 资源映射键，改名需全量收口。
 */
enum class OrganizeCategory {
    DUPLICATES,             // 重复与相似（dedup_hash MD5/pHash 组）
    SCREEN_CONTENT,         // 截图 + 录屏
    DOCUMENTS,              // 文档与票据（OCR 密度 / VLM 标签）
    LOW_QUALITY_PORTRAITS,  // 低质人像（eDifFIQA 低分）
    LOW_QUALITY_PHOTOS,     // 低质量照片（真模糊 / 曝光异常；NIMA 仅作置信辅助）
    LARGE_FILES,            // 大视频 + 超分辨率照片
}

/**
 * 类目判定输入：Room 列 + MediaStore meta + dedup_hash 组信息 + 人物计数合并后的扁平快照。
 * null = 信号未覆盖（绝不参与判定，由引导态承接）。
 */
data class OrganizeItem(
    val uri: String,
    val isVideo: Boolean,
    val captureDate: Long,                // epoch 毫秒
    val sizeBytes: Long,                // 未知 = 0
    val relativePath: String?,
    val ocrText: String?,
    val pixelArea: Long?,
    val labels: String?,
    val hasFace: Boolean,
    val aestheticScore: Float?,         // null = 未评分
    val faceQualityScore: Float?,       // null = 未评分
    // ── v2 新增信号 ──────────────────────────────────────────
    val blurScore: Float? = null,       // Laplacian 方差，越大越清晰；null = 未计算
    val exposureScore: Float? = null,   // 平均亮度 0~1；null = 未计算
    val lastViewedAt: Long? = null,     // epoch 毫秒；null = 从未在查看器打开
    val isFavorite: Boolean = false,    // MediaStore IS_FAVORITE
    /**
     * 所属人物聚类的照片总数；null = 无 faceId（无人脸或聚类未跑），此信号不参与保护判定
     * ——与类级「null=未覆盖不判定」契约一致；「无人脸」已由 hasFace=false 表达。
     */
    val personPhotoCount: Int? = null,
    /** 精确重复组大小（同 MD5 组成员数）；< 2 = 不在重复组。 */
    val exactDupGroupSize: Int = 0,
    /** 视觉相似组大小（pHash 簇成员数）；< 2 = 不在相似组。 */
    val similarDupGroupSize: Int = 0,
    /** 精确组标识（组内共享 MD5，仅 exactDupGroupSize ≥ 2 时非空）；hub 聚合按组扣 keeper 用。 */
    val exactDupGroupKey: String? = null,
)

/** 置信度分级（详情页三段分组 + 预选口径）。 */
enum class OrganizeConfidence { HIGH, MEDIUM, LOW }

/** 价值保护原因（详情页角标文案键）。 */
enum class ProtectReason { OLD_PHOTO, SCARCE_PERSON, USER_ENGAGED }

/** 管线产出：单媒体裁定结果。 */
data class ClassifiedItem(
    val item: OrganizeItem,
    val category: OrganizeCategory,
    val confidence: OrganizeConfidence,
    val protectReasons: Set<ProtectReason> = emptySet(),
) {
    /** 是否受价值保护（由保护原因集派生，单一事实来源）。 */
    val isProtected: Boolean get() = protectReasons.isNotEmpty()
}

/** 类目信号覆盖度：驱动 hub 类目卡「需先扫描」引导态（修复 v1 类目静默消失）。 */
enum class SignalCoverage { READY, NEEDS_SCAN }

/** 类目卡聚合（hub 渲染输入）。 */
data class CategoryBoard(
    val category: OrganizeCategory,
    val totalCount: Int,
    val totalBytes: Long,
    /** HIGH 置信且非 protected：建议删除数。 */
    val highCount: Int,
    /** 建议删除字节；DUPLICATES 额外按精确组扣 1 张 keeper（全员进建议集时保留一张不可删）。 */
    val highBytes: Long,
    /** MEDIUM+LOW 且非 protected：待确认数。 */
    val reviewCount: Int,
    val protectedCount: Int,
    val previewUris: List<String>,      // 前 4 张
    val coverage: SignalCoverage,
)

/** hub 整体产出。 */
data class OrganizeBoard(
    /** 按 highBytes 降序（建议优先级）。 */
    val categories: List<CategoryBoard>,
    /** Hero 主数字：全类目 HIGH 且非 protected 去重并集字节（互斥裁定保证天然去重；DUPLICATES 已扣 keeper）。 */
    val heroReclaimBytes: Long,
    /** Hero 副行：MEDIUM+LOW 且非 protected 总数。 */
    val heroReviewCount: Int,
)
