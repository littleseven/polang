package com.mamba.picme.domain.swipe

import com.mamba.picme.domain.organize.OrganizeCategorizer
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.organize.OrganizeConfidence
import com.mamba.picme.domain.organize.OrganizeItem

/**
 * 手势快速整理（F2）队列构建纯函数：与整理中心类目页同一管线产出（口径同源，
 * 修复 v1 桶规则与类目页口径漂移）。废片桶只收 HIGH/MEDIUM 且非 protected 项
 * （LOW 弱信号与受保护项落 RECENT 兜底，由用户全手动决策）。
 * 桶内 captureDate 倒序；视频永不入队（LARGE_FILES 走类目页）。
 */
object SwipeQueueBuilder {

    /** 桶优先级声明顺序即出队顺序。 */
    private data class Bucket(val category: OrganizeCategory?, val reason: SwipeReason)

    private val BUCKETS = listOf(
        Bucket(OrganizeCategory.SCREEN_CONTENT, SwipeReason.SCREENSHOT),
        Bucket(OrganizeCategory.LOW_QUALITY_PHOTOS, SwipeReason.BLURRY),
        Bucket(OrganizeCategory.LOW_QUALITY_PORTRAITS, SwipeReason.LOW_QUALITY_PORTRAIT),
        Bucket(null, SwipeReason.RECENT), // 兜底桶：其余照片
    )

    fun build(items: List<OrganizeItem>, now: Long = System.currentTimeMillis()): List<SwipeCandidate> {
        val classifiedByUri = OrganizeCategorizer.classifyAll(items, now)
            .associateBy { entry -> entry.item.uri }
        val grouped = LinkedHashMap<SwipeReason, MutableList<SwipeCandidate>>()
        for (bucket in BUCKETS) grouped[bucket.reason] = mutableListOf()
        for (item in items) {
            if (item.isVideo) continue
            val entry = classifiedByUri[item.uri]
            val wasteHit = entry != null && !entry.isProtected &&
                entry.confidence != OrganizeConfidence.LOW
            val reason = BUCKETS.first { bucket ->
                bucket.category == null || (wasteHit && entry?.category == bucket.category)
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
