package com.mamba.picme.domain.chat.a2ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

class GalleryHistogramBuilderTest {

    private val utc = TimeZone.UTC

    @Test
    fun buildMonthly_groupsByMonthAscending() {
        // 2026-08 两张、2026-10 一张（epoch ms 现算，不写魔术数）
        val entries = listOf(
            ScanPhotoEntry(
                id = 1,
                captureDate = LocalDate(2026, 8, 1).atStartOfDayIn(utc).toEpochMilliseconds(),
            ),
            ScanPhotoEntry(
                id = 2,
                captureDate = LocalDate(2026, 8, 15).atStartOfDayIn(utc).toEpochMilliseconds(),
            ),
            ScanPhotoEntry(
                id = 3,
                captureDate = LocalDate(2026, 10, 1).atStartOfDayIn(utc).toEpochMilliseconds(),
            ),
        )
        val buckets = GalleryHistogramBuilder.buildMonthly(entries, utc)
        assertEquals(2, buckets.size)
        assertEquals(buckets.map { bucket -> bucket.label }, buckets.map { bucket -> bucket.label }.sorted())
        assertEquals(2, buckets[0].count)
    }

    @Test
    fun buildMonthly_emptyEntries_returnsEmpty() {
        assertEquals(emptyList(), GalleryHistogramBuilder.buildMonthly(emptyList(), utc))
    }
}
