package com.mamba.picme.domain.chat.a2ui

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * #photos 投影元素（OpenUI Image 组件的 photo 引用源；不含 uri/路径——[PRIVACY] D1）。
 * date 为 "yyyy-MM-dd" 拍摄日期，可直接喂 Text 组件。
 */
@Serializable
data class PhotoRef(
    val id: Long,
    val date: String,
)

/**
 * 命中集 → 照片 id 清单投影（`local://scan/<id>#photos` 句柄的端侧投影，
 * spec《openui-image-component-design》D2；与 [GalleryHistogramBuilder] 同包同理）。
 *
 * 保持 entries 原序（搜索相关度序），截断到 [cap] 张封顶（token 保护）。
 */
object ScanPhotoProjection {

    /** 单卡照片引用上限。 */
    const val PHOTOS_CAP = 60

    /** ScanPhotoEntry.mediaType 的视频值（MediaType.VIDEO.name 字面量副本——
     *  本包不依赖 agent.core.model，写侧 CloudAgentGalleryTools 持同源核对责任）。 */
    private const val MEDIA_TYPE_VIDEO = "VIDEO"

    /** 照片 id 清单投影（#photos）：media_type=all 混合命中集只取照片
     *  （mediaType = null 的旧版 payload 视为照片，行为与旧口径一致）。 */
    fun toPhotoRefs(
        entries: List<ScanPhotoEntry>,
        cap: Int = PHOTOS_CAP,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<PhotoRef> = entries.filter { entry -> entry.mediaType != MEDIA_TYPE_VIDEO }
        .toRefs(cap, timeZone)

    /** 视频 id 清单投影（#videos，2026-11-04 Video 组件批次）：只取视频元素，
     *  元素形态与照片投影一致（{id, date}，渲染期端侧 id→uri）。 */
    fun toVideoRefs(
        entries: List<ScanPhotoEntry>,
        cap: Int = PHOTOS_CAP,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<PhotoRef> = entries.filter { entry -> entry.mediaType == MEDIA_TYPE_VIDEO }
        .toRefs(cap, timeZone)

    private fun List<ScanPhotoEntry>.toRefs(
        cap: Int,
        timeZone: TimeZone,
    ): List<PhotoRef> = take(cap).map { entry ->
        val date = Instant.fromEpochMilliseconds(entry.captureDate)
            .toLocalDateTime(timeZone).date
        PhotoRef(
            id = entry.id,
            date = buildString {
                append(date.year.toString().padStart(4, '0'))
                append('-')
                append(date.monthNumber.toString().padStart(2, '0'))
                append('-')
                append(date.dayOfMonth.toString().padStart(2, '0'))
            },
        )
    }
}
