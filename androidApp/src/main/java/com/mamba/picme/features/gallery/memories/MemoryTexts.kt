package com.mamba.picme.features.gallery.memories

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.domain.memories.Memory
import com.mamba.picme.domain.memories.MemoryType
import java.time.Instant
import java.time.MonthDay
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 回忆文案还原（I18N 红线）：[Memory] 只携带结构化字段，title/subtitle 由本层按
 * [MemoryType] + 参数经 stringResource 拼装；月日按当前 Locale 取最佳 pattern 本地化。
 */
@Composable
fun memoryTitle(memory: Memory): String = when (memory.type) {
    MemoryType.ON_THIS_DAY -> stringResource(R.string.memory_on_this_day)
    MemoryType.RECENT_HIGHLIGHTS -> stringResource(R.string.memory_recent_highlights)
    MemoryType.PERSON -> stringResource(R.string.memory_person_title, memory.label.orEmpty())
    MemoryType.CITY -> memory.label.orEmpty()
}

@Composable
fun memorySubtitle(memory: Memory): String = when (memory.type) {
    MemoryType.ON_THIS_DAY -> stringResource(
        R.string.memory_on_this_day_subtitle,
        localizedMonthDay(memory.monthDay),
        memory.latestYear ?: 0,
    )
    MemoryType.RECENT_HIGHLIGHTS -> stringResource(R.string.memory_recent_highlights_subtitle)
    MemoryType.PERSON -> stringResource(R.string.memory_photo_count, memory.hitCount)
    MemoryType.CITY -> cityDateRange(memory)
}

/**
 * 旅程卡日期范围（CITY 副行，对齐小米）：同年月 → 本地化 yMMMM（skeleton "yMMMM" 经系统取当前
 * Locale 最佳 pattern，如 en "May 2025"、zh "2025年5月"）；跨月/跨年 → "startYear · endYear"
 * （如「2025 · 2026」）。字段缺失（非 CITY）容错返回空串。
 */
@Composable
fun cityDateRange(memory: Memory): String {
    val earliest = memory.earliestCaptureDate
    val latest = memory.latestCaptureDate
    if (earliest == null || latest == null) return ""
    val locale = LocalConfiguration.current.locales[0]
    return remember(earliest, latest, locale) {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(earliest).atZone(zone)
        val end = Instant.ofEpochMilli(latest).atZone(zone)
        val startMonth = YearMonth.from(start)
        if (startMonth == YearMonth.from(end)) {
            val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMM")
            startMonth.format(DateTimeFormatter.ofPattern(pattern, locale))
        } else {
            "${start.year} · ${end.year}"
        }
    }
}

/** 月日本地化：skeleton "MMMd" 经系统取当前 Locale 最佳 pattern（en "MMM d"、zh "M月d日"、es "d MMM"）；null 容错返回空串。 */
@Composable
private fun localizedMonthDay(monthDay: MonthDay?): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(monthDay, locale) {
        if (monthDay == null) {
            ""
        } else {
            val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
            monthDay.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
    }
}
