package com.mamba.picme.domain.model

import com.mamba.picme.data.model.MediaEntity

/**
 * JS `gallery.query` 的过滤参数。全字段可选，多维 **AND** 组合。
 *
 * @param label    labels 子串匹配（大小写不敏感）。
 * @param ocr      ocrText 子串匹配。
 * @param location locationName 子串匹配。
 * @param fromMs   captureDate >= fromMs（毫秒）。
 * @param toMs     captureDate <= toMs（毫秒）。
 * @param hasFace  是否含人脸。
 * @param person   人物名（相册人物分组里已命名的，如"大宝"）。按 face_embeddings 归属做 AND 交集，
 *                 使"人物 ∩ 时间/标签"精确命中（解决 search_media 自然语言人物+时间丢维的问题）。
 * @param limit    返回 id 截断上限（防止爆量）；[GalleryQueryResult.total] 仍为未截断真实命中数。
 */
data class QueryFilter(
    val label: String? = null,
    val ocr: String? = null,
    val location: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val hasFace: Boolean? = null,
    val person: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 200
    }
}

/** `gallery.query` 结果：命中 id（已截断到 [QueryFilter.limit]）+ 未截断的真实总数。 */
data class GalleryQueryResult(
    val ids: List<Long>,
    val total: Int,
)

/**
 * 在内存按 [filter] 过滤候选媒体（纯逻辑，多维 AND）。便于纯 JVM 单测，不触碰 Room/Android。
 *
 * 注：时间范围在 QueryGalleryMediaUseCase 已尽量由 DAO 预筛
 * （走 searchByTimeRange 分支）；此处仍兜底二次过滤——候选来自 searchByHasFace（已 deprecated）
 * 分支时需在此补齐时间条件。
 */
fun List<MediaEntity>.applyFilter(filter: QueryFilter): List<MediaEntity> =
    filter { m ->
        (filter.label == null || m.labels?.contains(filter.label, ignoreCase = true) == true) &&
            (filter.ocr == null || m.ocrText?.contains(filter.ocr, ignoreCase = true) == true) &&
            (filter.location == null || m.locationName?.contains(filter.location, ignoreCase = true) == true) &&
            (filter.fromMs == null || m.captureDate >= filter.fromMs) &&
            (filter.toMs == null || m.captureDate <= filter.toMs) &&
            (filter.hasFace == null || m.hasFace == filter.hasFace)
    }
