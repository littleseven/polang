package com.mamba.picme.domain.organize

/** 单来源账目：count/bytes 聚合到来源 App 粒度。 */
data class SourceLedger(val source: AppSource, val count: Int, val bytes: Long)

/**
 * 存储空间叙事聚合（Agent 清理 M1）：媒体清单 + 整理看板 → hub「还剩多少空间」叙事的纯函数。
 *
 * - 字节口径全部来自 [OrganizeItem.sizeBytes]（未知 = 0，天然容错）；
 * - [reclaimBytes] 是 Agent 面口径：hero 建议字节剔除两类低质量（由 Agent 陪审另行处理，hub 不重复计入）。
 */
data class StorageStory(
    val totalBytes: Long,
    val photoBytes: Long,
    val videoBytes: Long,
    /** 全部截图（含录屏）。 */
    val screenshotBytes: Long,
    /** ≥ 365 天的截图张数（仅照片，录屏不算）。 */
    val staleScreenshotCount: Int,
    val staleScreenshotBytes: Long,
    /** 来源账目，bytes 降序（不过滤 CAMERA——「相机占多少」也是叙事；未识别来源并卷 OTHER）。 */
    val sourceLedgers: List<SourceLedger>,
    /** Agent 面口径：hero 建议字节 − 两类低质量 highBytes，下限 0。 */
    val reclaimBytes: Long,
) {
    companion object {
        /** 截图陈旧阈值：拍摄（或落盘）时间距今 ≥ 365 天视为过期。 */
        const val STALE_SCREENSHOT_AGE_MS: Long = 365L * 24 * 3600 * 1000

        fun of(items: List<OrganizeItem>, board: OrganizeBoard, now: Long): StorageStory {
            val bySource = items.groupBy { item -> appSourceOf(item.relativePath) }
            val screenshots = bySource[AppSource.SCREENSHOTS].orEmpty()
            val staleScreenshots = screenshots.filter { item ->
                !item.isVideo && item.captureDate > 0 && now - item.captureDate >= STALE_SCREENSHOT_AGE_MS
            }
            val lowQualityBytes = board.categories
                .filter { card ->
                    card.category == OrganizeCategory.LOW_QUALITY_PHOTOS ||
                        card.category == OrganizeCategory.LOW_QUALITY_PORTRAITS
                }
                .sumOf { card -> card.highBytes }
            return StorageStory(
                totalBytes = items.sumOf { item -> item.sizeBytes },
                photoBytes = items.filterNot { item -> item.isVideo }.sumOf { item -> item.sizeBytes },
                videoBytes = items.filter { item -> item.isVideo }.sumOf { item -> item.sizeBytes },
                screenshotBytes = screenshots.sumOf { item -> item.sizeBytes },
                staleScreenshotCount = staleScreenshots.size,
                staleScreenshotBytes = staleScreenshots.sumOf { item -> item.sizeBytes },
                sourceLedgers = bySource.map { (source, group) ->
                    SourceLedger(
                        source = source,
                        count = group.size,
                        bytes = group.sumOf { item -> item.sizeBytes },
                    )
                }
                    .sortedByDescending { ledger -> ledger.bytes },
                reclaimBytes = (board.heroReclaimBytes - lowQualityBytes).coerceAtLeast(0L),
            )
        }
    }
}
