package com.mamba.picme.domain.swipe

import com.mamba.picme.domain.organize.OrganizeItem
import java.util.Calendar

/** 月份桶（captureDate 本地时区聚合；month 取 1..12），供 hub「按月回顾」入口与建队过滤。 */
data class SwipeMonth(val year: Int, val month: Int, val count: Int)

object SwipeMonths {

    /** 可用月份（与建队同口径只统计非视频媒体），新→旧。 */
    fun availableMonths(items: List<OrganizeItem>): List<SwipeMonth> {
        val cal = Calendar.getInstance()
        return items
            .filter { item -> !item.isVideo && item.captureDate > 0L }
            .groupingBy { item ->
                cal.timeInMillis = item.captureDate
                cal.get(Calendar.YEAR) * 100 + (cal.get(Calendar.MONTH) + 1)
            }
            .eachCount()
            .map { (yearMonth, count) ->
                SwipeMonth(year = yearMonth / 100, month = yearMonth % 100, count = count)
            }
            .sortedWith(compareByDescending<SwipeMonth> { month -> month.year }
                .thenByDescending { month -> month.month })
    }

    /** 月份起点毫秒（本地时区，含）。 */
    fun startMsOf(year: Int, month: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(year, month - 1, 1)
        }.timeInMillis

    /** 月份区间 [start, end)（本地时区，12 月跨年 rollover）。 */
    fun rangeOf(year: Int, month: Int): Pair<Long, Long> {
        val start = startMsOf(year, month)
        val end = if (month == 12) startMsOf(year + 1, 1) else startMsOf(year, month + 1)
        return start to end
    }

    /** 包含指定时刻的月份区间。 */
    fun rangeContaining(ms: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return rangeOf(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }
}
