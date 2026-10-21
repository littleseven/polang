package com.mamba.picme.domain.chat.a2ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

class GalleryHistogramBuilderTest {

    private val utc = TimeZone.UTC

    @Test
    fun buildMonthly_groupsByMonthAscending() {
        // 2026-08 两张、2026-10 一张（epoch ms 现算，不写魔术数）；
        // 输入故意乱序（10 月在前），升序必须由实现排序保证而非输入巧合
        val entries = listOf(
            ScanPhotoEntry(
                id = 3,
                captureDate = LocalDate(2026, 10, 1).atStartOfDayIn(utc).toEpochMilliseconds(),
            ),
            ScanPhotoEntry(
                id = 1,
                captureDate = LocalDate(2026, 8, 1).atStartOfDayIn(utc).toEpochMilliseconds(),
            ),
            ScanPhotoEntry(
                id = 2,
                captureDate = LocalDate(2026, 8, 15).atStartOfDayIn(utc).toEpochMilliseconds(),
            ),
        )
        val buckets = GalleryHistogramBuilder.buildMonthly(entries, utc)
        assertEquals(2, buckets.size)
        assertEquals(listOf("2026-08", "2026-10"), buckets.map { bucket -> bucket.label })
        assertEquals(2, buckets[0].count)
    }

    @Test
    fun buildMonthly_emptyEntries_returnsEmpty() {
        assertEquals(emptyList(), GalleryHistogramBuilder.buildMonthly(emptyList(), utc))
    }

    @Test
    fun buildMonthly_timezoneCrossover_usesLocalMonth() {
        // 2026-08-31T23:59Z 在 UTC+8 落入 2026-09：月桶归属按传入时区的本地日期
        val shanghai = TimeZone.of("Asia/Shanghai")
        val entries = listOf(
            ScanPhotoEntry(
                id = 1,
                captureDate = Instant.parse("2026-08-31T23:59:00Z").toEpochMilliseconds(),
            ),
        )
        val buckets = GalleryHistogramBuilder.buildMonthly(entries, shanghai)
        assertEquals(listOf(HistogramBucket(label = "2026-09", count = 1)), buckets)
    }
}
