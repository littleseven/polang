package com.mamba.picme.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QueryParserTimeTest {

    @Test
    fun `parse last year march`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("去年3月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH)) // 0-based

        cal.timeInMillis = range.endMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH))
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `parse this year may`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("今年5月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse specific year and month`() {
        val range = QueryParser.parseTimeRange("2024年3月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse full year`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("去年")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(0, cal.get(Calendar.MONTH))

        cal.timeInMillis = range.endMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(11, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse last month`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("上个月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse this week`() {
        val range = QueryParser.parseTimeRange("本周")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `parse chinese month may`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("五月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))

        cal.timeInMillis = range.endMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `parse last year chinese month may`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("去年五月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse past half year`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("近半年")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(11, cal.get(Calendar.MONTH))

        cal.timeInMillis = range.endMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(5, cal.get(Calendar.MONTH))
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `parse recent half year variants`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        assertNotNull(QueryParser.parseTimeRange("最近半年"))
        assertNotNull(QueryParser.parseTimeRange("半年内"))
    }

    @Test
    fun `parse past year`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("近一年")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(5, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse past n months`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("近3个月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse past n months with chinese digits`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("近三个月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse last year summer`() {
        QueryParser.currentYear = 2026
        QueryParser.currentMonth = 7

        val range = QueryParser.parseTimeRange("去年夏天")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(5, cal.get(Calendar.MONTH)) // 0-based: June
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))

        cal.timeInMillis = range.endMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(7, cal.get(Calendar.MONTH)) // 0-based: August
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `parse this year summer`() {
        QueryParser.currentYear = 2026
        QueryParser.currentMonth = 7

        val range = QueryParser.parseTimeRange("今年夏天")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(5, cal.get(Calendar.MONTH))

        cal.timeInMillis = range.endMs
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(7, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse year before last summer`() {
        QueryParser.currentYear = 2026
        QueryParser.currentMonth = 7

        val range = QueryParser.parseTimeRange("前年夏天")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(5, cal.get(Calendar.MONTH))

        cal.timeInMillis = range.endMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(7, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse last year winter`() {
        QueryParser.currentYear = 2026
        QueryParser.currentMonth = 7

        val range = QueryParser.parseTimeRange("去年冬天")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(11, cal.get(Calendar.MONTH)) // 0-based: December

        cal.timeInMillis = range.endMs
        assertEquals(2026, cal.get(Calendar.YEAR)) // Winter spans into next year
        assertEquals(1, cal.get(Calendar.MONTH)) // 0-based: February
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `kinship terms are recognized as people search`() {
        // 亲属称谓词（儿子/女儿/妈妈…）应视为人物搜索，
        // 否则 QueryParser 会把 "我儿子" 当作普通关键词驱动标签搜索（回归表现：470 张并集污染）
        assertTrue(QueryParser.isPeopleSearch("我儿子的照片"))
        assertTrue(QueryParser.isPeopleSearch("女儿和妈妈的合照"))
        assertFalse(QueryParser.isPeopleSearch("海边的日落"))
    }

    @Test
    fun `colloquial softener verb-xia is not extracted as content keyword`() {
        // 口语缓和词「找下/找一下/看下」中的「下」不是实体词，
        // 残留会以 LIKE '%下%' 命中「下午」等标签，把人物 27 张错误收窄到 3 张（真机回归 2026-09-30）
        assertTrue(QueryParser.extractKeywords("找下我儿子的照片").none { it == "下" })
        assertTrue(QueryParser.extractKeywords("找一下我儿子的照片").none { it == "下" })
        assertTrue(QueryParser.extractKeywords("看下女儿的照片").none { it == "下" })
    }

    @Test
    fun `softener removal keeps afternoon time word intact`() {
        // 「下午」是时间语义，不能被缓和建议词清理误伤成「午」
        val keywords = QueryParser.extractKeywords("下午的照片")
        assertTrue(keywords.none { it == "午" })
    }
}
