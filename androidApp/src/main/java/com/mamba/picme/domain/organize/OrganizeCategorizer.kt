package com.mamba.picme.domain.organize

/** hub 卡片预览缩略图张数。 */
private const val PREVIEW_LIMIT = 4

/**
 * 整理中心 v2 管线编排 Facade（纯函数，对外唯一入口）：
 * CategoryArbiter（互斥裁定）→ ValueGuard（价值保护）→ ConfidenceGrader（置信分级）。
 * 旧集合语义 categoriesOf/stats/CategoryStat 已删除——类目页与 SwipeReview 同源消费本管线。
 */
object OrganizeCategorizer {

    /** 逐媒体裁定（类目详情页输入）。未命中任何类目的媒体不出现在结果中。 */
    fun classifyAll(items: List<OrganizeItem>, now: Long): List<ClassifiedItem> =
        items.mapNotNull { item ->
            val category = CategoryArbiter.classify(item) ?: return@mapNotNull null
            val verdict = ValueGuard.assess(item, category, now)
            ClassifiedItem(
                item = item,
                category = category,
                confidence = ConfidenceGrader.grade(item, category),
                protectReasons = verdict.reasons,
            )
        }

    /**
     * hub 聚合：类目卡（按建议优先级 highBytes 降序）+ Hero 口径（HIGH 非 protected 去重并集）。
     * 六类目全量产卡（含零命中类目：计数/字节全零、coverage 正常计算），
     * NEEDS_SCAN 引导卡与空卡是否渲染由 UI 层决定（spec P3/AC-R2-4：类目不再静默消失）。
     */
    fun board(items: List<OrganizeItem>, now: Long): OrganizeBoard {
        val classified = classifyAll(items, now)
        val entriesByCategory = classified.groupBy { entry -> entry.category }
        val cards = OrganizeCategory.entries
            .map { category ->
                val entries = entriesByCategory[category].orEmpty()
                val high = entries.filter { entry ->
                    entry.confidence == OrganizeConfidence.HIGH && !entry.isProtected
                }
                val review = entries.filter { entry ->
                    entry.confidence != OrganizeConfidence.HIGH && !entry.isProtected
                }
                CategoryBoard(
                    category = category,
                    totalCount = entries.size,
                    totalBytes = entries.sumOf { entry -> entry.item.sizeBytes },
                    highCount = high.size,
                    highBytes = high.sumOf { entry -> entry.item.sizeBytes },
                    reviewCount = review.size,
                    protectedCount = entries.count { entry -> entry.isProtected },
                    previewUris = entries.take(PREVIEW_LIMIT).map { entry -> entry.item.uri },
                    coverage = coverageOf(category, items),
                )
            }
            .sortedByDescending { card -> card.highBytes }
        return OrganizeBoard(
            categories = cards,
            // 互斥裁定保证一媒体一卡，high 求和即并集（AC-F1-1 回归防线）
            heroReclaimBytes = cards.sumOf { card -> card.highBytes },
            heroReviewCount = cards.sumOf { card -> card.reviewCount },
        )
    }

    /**
     * 类目信号覆盖度：全库任一媒体持有该类目关键信号 → READY，否则 NEEDS_SCAN
     * （驱动 hub「需先扫描」引导态，修复 v1 类目静默消失）。
     * 路径/大小类信号（SCREEN_CONTENT/LARGE_FILES/DUPLICATES）MediaStore 常备，恒 READY。
     */
    fun coverageOf(category: OrganizeCategory, items: List<OrganizeItem>): SignalCoverage =
        when (category) {
            OrganizeCategory.DUPLICATES,
            OrganizeCategory.SCREEN_CONTENT,
            OrganizeCategory.LARGE_FILES,
            -> SignalCoverage.READY
            OrganizeCategory.DOCUMENTS ->
                if (items.any { item -> item.ocrText != null || item.labels != null }) {
                    SignalCoverage.READY
                } else {
                    SignalCoverage.NEEDS_SCAN
                }
            OrganizeCategory.LOW_QUALITY_PORTRAITS ->
                if (items.any { item -> item.faceQualityScore != null }) {
                    SignalCoverage.READY
                } else {
                    SignalCoverage.NEEDS_SCAN
                }
            OrganizeCategory.LOW_QUALITY_PHOTOS ->
                // 信号集与 CategoryArbiter 准入一致（blur 或 exposure 任一持有即已覆盖）
                if (items.any { item -> item.blurScore != null || item.exposureScore != null }) {
                    SignalCoverage.READY
                } else {
                    SignalCoverage.NEEDS_SCAN
                }
        }
}
