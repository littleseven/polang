package com.mamba.picme.domain.swipe

import com.mamba.picme.domain.organize.OrganizeItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class SwipeMonthsTest {

    private fun msOf(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(year, month - 1, day)
        }.timeInMillis

    private fun item(uri: String, captureDate: Long, isVideo: Boolean = false) = OrganizeItem(
        uri = uri,
        isVideo = isVideo,
        captureDate = captureDate,
        sizeBytes = 1_000_000L,
        relativePath = null,
        ocrText = null,
        pixelArea = null,
        labels = null,
        hasFace = false,
        aestheticScore = null,
        faceQualityScore = null,
    )

    @Test
    fun `availableMonths groups by local month, photos only, newest first`() {
        val items = listOf(
            item("a", msOf(2026, 11, 3)),
            item("b", msOf(2026, 11, 18)),
            item("c", msOf(2026, 9, 1)),
            item("d", msOf(2025, 12, 31)),
            item("v", msOf(2026, 11, 5), isVideo = true), // 视频不计
        )
        val months = SwipeMonths.availableMonths(items)
        assertEquals(
            listOf(
                SwipeMonth(2026, 11, 2),
                SwipeMonth(2026, 9, 1),
                SwipeMonth(2025, 12, 1),
            ),
            months,
        )
    }

    @Test
    fun `rangeOf december rolls into january of next year`() {
        val (start, end) = SwipeMonths.rangeOf(2025, 12)
        assertEquals(msOf(2025, 12, 1), start)
        assertEquals(msOf(2026, 1, 1), end)
    }

    @Test
    fun `rangeContaining returns the month of the given instant`() {
        val (start, end) = SwipeMonths.rangeContaining(msOf(2026, 11, 19))
        assertEquals(msOf(2026, 11, 1), start)
        assertEquals(msOf(2026, 12, 1), end)
    }
}
