package com.mamba.picme.domain.chat.a2ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

class ScanPhotoProjectionTest {

    private val utc = TimeZone.UTC

    @Test
    fun toPhotoRefs_formatsDateInUtc() {
        // epoch 0 → 1970-01-01；月/日补零对齐 GalleryHistogramBuilder 同风格
        val refs = ScanPhotoProjection.toPhotoRefs(
            listOf(ScanPhotoEntry(id = 7, captureDate = 0L)),
            timeZone = utc,
        )
        assertEquals(listOf(PhotoRef(id = 7, date = "1970-01-01")), refs)
    }

    @Test
    fun toPhotoRefs_keepsOriginalOrder() {
        // 原序 = 搜索相关度序，投影不重排（id 故意乱序）
        val entries = listOf(
            ScanPhotoEntry(id = 9, captureDate = LocalDate(2026, 10, 1).atStartOfDayIn(utc).toEpochMilliseconds()),
            ScanPhotoEntry(id = 3, captureDate = LocalDate(2026, 8, 15).atStartOfDayIn(utc).toEpochMilliseconds()),
        )
        val refs = ScanPhotoProjection.toPhotoRefs(entries, timeZone = utc)
        assertEquals(listOf(9L, 3L), refs.map { ref -> ref.id })
        assertEquals(listOf("2026-10-01", "2026-08-15"), refs.map { ref -> ref.date })
    }

    @Test
    fun toPhotoRefs_truncatesAtCap() {
        val entries = (1L..(ScanPhotoProjection.PHOTOS_CAP + 5)).map { id ->
            ScanPhotoEntry(id = id, captureDate = 0L)
        }
        val refs = ScanPhotoProjection.toPhotoRefs(entries, timeZone = utc)
        assertEquals(ScanPhotoProjection.PHOTOS_CAP, refs.size)
        // 截断保留头部（相关度最高的前 cap 张）
        assertEquals(1L, refs.first().id)
        assertEquals(ScanPhotoProjection.PHOTOS_CAP.toLong(), refs.last().id)
    }

    @Test
    fun toPhotoRefs_emptyEntries_returnsEmpty() {
        assertEquals(emptyList(), ScanPhotoProjection.toPhotoRefs(emptyList(), timeZone = utc))
    }
}
