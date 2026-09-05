package com.mamba.picme.domain.swipe

import com.mamba.picme.domain.organize.OrganizeCategorizer
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.organize.OrganizeItem

/**
 * 手势快速整理（F2）队列构建纯函数：废片优先分桶，每媒体只进一个桶
 * （按桶优先级取首个命中），桶内 captureDate 倒序。视频永不入队
 * （手势整理只处理照片；LARGE_VIDEOS 走整理中心类目页）。
 */
object SwipeQueueBuilder {

    /** 桶优先级声明顺序即出队顺序。 */
    private data class Bucket(val category: OrganizeCategory?, val reason: SwipeReason)

    private val BUCKETS = listOf(
        Bucket(OrganizeCategory.SCREENSHOTS, SwipeReason.SCREENSHOT),
        Bucket(OrganizeCategory.BLURRY, SwipeReason.BLURRY),
        Bucket(OrganizeCategory.LOW_QUALITY_PORTRAITS, SwipeReason.LOW_QUALITY_PORTRAIT),
        Bucket(null, SwipeReason.RECENT), // 兜底桶：其余照片
    )

    fun build(items: List<OrganizeItem>): List<SwipeCandidate> {
        val grouped = LinkedHashMap<SwipeReason, MutableList<SwipeCandidate>>()
        for (bucket in BUCKETS) grouped[bucket.reason] = mutableListOf()
        for (item in items) {
            if (item.isVideo) continue
            val categories = OrganizeCategorizer.categoriesOf(item)
            val reason = BUCKETS.first { bucket ->
                bucket.category == null || bucket.category in categories
            }.reason
            grouped.getValue(reason) += SwipeCandidate(
                uri = item.uri,
                sizeBytes = item.sizeBytes,
                captureDate = item.captureDate,
                reason = reason,
            )
        }
        return grouped.values.flatMap { bucket ->
            bucket.sortedByDescending { candidate -> candidate.captureDate }
        }
    }
}
