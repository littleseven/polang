package com.mamba.picme.domain.chat.a2ui

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/** 扫描命中集的最小投影单元（handle payload 元素；不含 uri/路径——D2）。 */
@Serializable
data class ScanPhotoEntry(
    val id: Long,
    /** 拍摄时刻，epoch milliseconds。 */
    val captureDate: Long,
)

/** 月桶（A2UI BarChart buckets 槽位的数据形态）。 */
@Serializable
data class HistogramBucket(
    /** yyyy-MM */
    val label: String,
    val count: Int,
)

/**
 * 命中集 → 月度直方图（HandleResolver 填槽用纯函数，spec §7 接缝 3）。
 * GenUI 只生成骨架不生成数据：桶计算永远在端上发生。
 */
object GalleryHistogramBuilder {

    fun buildMonthly(
        entries: List<ScanPhotoEntry>,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<HistogramBucket> {
        if (entries.isEmpty()) return emptyList()
        val counts = mutableMapOf<String, Int>()
        for (entry in entries) {
            val date = Instant.fromEpochMilliseconds(entry.captureDate)
                .toLocalDateTime(timeZone).date
            val month = buildString {
                append(date.year.toString().padStart(4, '0'))
                append('-')
                append(date.monthNumber.toString().padStart(2, '0'))
            }
            counts[month] = (counts[month] ?: 0) + 1
        }
        return counts.entries
            .sortedBy { entry -> entry.key }
            .map { (label, count) -> HistogramBucket(label = label, count = count) }
    }
}
