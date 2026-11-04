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

    @Test
    fun toPhotoRefs_formatsDateInNonUtcTimezone() {
        // 跨日偏移边界：epoch 0 在纽约（UTC-5/-4）是 1969-12-31 傍晚——
        // 手写格式化 + 时区组合最易错处钉住（review 🔵6）
        val newYork = TimeZone.of("America/New_York")
        val refs = ScanPhotoProjection.toPhotoRefs(
            listOf(ScanPhotoEntry(id = 1, captureDate = 0L)),
            timeZone = newYork,
        )
        assertEquals("1969-12-31", refs.single().date)
    }

    // ── 2026-11-04 Video 组件批次：mediaType 类型分流 ──

    @Test
    fun toPhotoRefs_excludesVideoEntries() {
        // media_type=all 混合命中集：#photos 投影只取照片；mediaType=null 旧 payload 视为照片
        val entries = listOf(
            ScanPhotoEntry(id = 1, captureDate = 0L),
            ScanPhotoEntry(id = 2, captureDate = 0L, mediaType = "VIDEO"),
            ScanPhotoEntry(id = 3, captureDate = 0L, mediaType = "PHOTO"),
        )
        val refs = ScanPhotoProjection.toPhotoRefs(entries, timeZone = utc)
        assertEquals(listOf(1L, 3L), refs.map { ref -> ref.id })
    }

    @Test
    fun toVideoRefs_keepsOnlyVideoEntries() {
        val entries = listOf(
            ScanPhotoEntry(id = 1, captureDate = 0L, mediaType = "PHOTO"),
            ScanPhotoEntry(id = 2, captureDate = 0L, mediaType = "VIDEO"),
            ScanPhotoEntry(id = 3, captureDate = 0L),
            ScanPhotoEntry(id = 4, captureDate = 0L, mediaType = "VIDEO"),
        )
        val refs = ScanPhotoProjection.toVideoRefs(entries, timeZone = utc)
        assertEquals(listOf(2L, 4L), refs.map { ref -> ref.id })
        assertEquals("1970-01-01", refs.first().date)
    }

    @Test
    fun toVideoRefs_truncatesAtCap() {
        val entries = (1L..(ScanPhotoProjection.PHOTOS_CAP + 5)).map { id ->
            ScanPhotoEntry(id = id, captureDate = 0L, mediaType = "VIDEO")
        }
        assertEquals(ScanPhotoProjection.PHOTOS_CAP, ScanPhotoProjection.toVideoRefs(entries, timeZone = utc).size)
    }
}
