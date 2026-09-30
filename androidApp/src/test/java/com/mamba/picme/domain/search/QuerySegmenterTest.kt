package com.mamba.picme.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuerySegmenterTest {

    @Test
    fun `segment splits last year march indoor child photo`() {
        val result = QuerySegmenter.segment("去年3月在室内小孩的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.TIME, "去年3月"),
                Segment(SegmentType.LOCATION, "室内"),
                Segment(SegmentType.PERSON, "小孩"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits chinese month may photo`() {
        val result = QuerySegmenter.segment("五月的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.TIME, "五月"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits last year chinese month may`() {
        val result = QuerySegmenter.segment("去年五月")

        assertEquals(
            listOf(Segment(SegmentType.TIME, "去年五月")),
            result.segments
        )
    }

    @Test
    fun `segment splits beijing park child`() {
        val result = QuerySegmenter.segment("北京公园里的小孩")

        assertEquals(
            listOf(
                Segment(SegmentType.LOCATION, "北京"),
                Segment(SegmentType.LOCATION, "公园"),
                Segment(SegmentType.PERSON, "小孩")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits cat photo`() {
        val result = QuerySegmenter.segment("猫的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.OBJECT, "猫"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
    }

    @Test
    fun `segment returns empty for stop words only`() {
        val result = QuerySegmenter.segment("的照片")
        assertTrue(result.isEmpty)
    }

    @Test
    fun `toFilters converts explicit and content segments`() {
        val segmented = SegmentedQuery(
            original = "去年3月在室内小孩",
            segments = listOf(
                Segment(SegmentType.TIME, "去年3月"),
                Segment(SegmentType.LOCATION, "室内"),
                Segment(SegmentType.PERSON, "小孩")
            )
        )

        val (explicit, content) = QuerySegmenter.toFilters(segmented)

        assertTrue(explicit.timeRange != null)
        assertEquals(listOf("室内"), explicit.locationKeywords)
        assertEquals(true, explicit.hasFaces)
        assertEquals(listOf("小孩"), explicit.personKeywords)
        assertEquals(listOf("小孩"), content.keywords)
    }

    @Test
    fun `segment returns empty for empty or whitespace input`() {
        val emptyResult = QuerySegmenter.segment("")
        assertTrue(emptyResult.isEmpty)
        assertEquals(emptyList<Segment>(), emptyResult.segments)

        val whitespaceResult = QuerySegmenter.segment("   ")
        assertTrue(whitespaceResult.isEmpty)
        assertEquals(emptyList<Segment>(), whitespaceResult.segments)
    }

    @Test
    fun `segment splits sea sunset into scene segments`() {
        val result = QuerySegmenter.segment("海边日落")

        assertEquals(
            listOf(
                Segment(SegmentType.SCENE, "海边"),
                Segment(SegmentType.SCENE, "日落")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits last week invoice screenshot`() {
        val result = QuerySegmenter.segment("上周发票截图")

        assertEquals(
            listOf(
                Segment(SegmentType.TIME, "上周"),
                Segment(SegmentType.OCR, "发票"),
                Segment(SegmentType.OCR, "截图")
            ),
            result.segments
        )
    }

    @Test
    fun `segment splits dinner food with unknown filler`() {
        val result = QuerySegmenter.segment("聚餐时拍的食物")

        assertEquals(
            listOf(
                Segment(SegmentType.ACTIVITY, "聚餐"),
                Segment(SegmentType.UNKNOWN, "时"),
                Segment(SegmentType.OBJECT, "食物")
            ),
            result.segments
        )
    }

    @Test
    fun `segment uses default search vocabulary for scene object activity`() {
        val result = QuerySegmenter.segment("自定义地点")

        // "自定义地点" not in default location vocab → falls through to UNKNOWN
        assertEquals(
            listOf(Segment(SegmentType.UNKNOWN, "自定义地点")),
            result.segments
        )
    }

    @Test
    fun `toFilters hasFaces is null when no person segment`() {
        val segmented = QuerySegmenter.segment("猫的照片")

        val (explicit, _) = QuerySegmenter.toFilters(segmented)

        assertNull(explicit.hasFaces)
    }

    @Test
    fun `hasNarrowingExplicit is false for pure person query`() {
        // "小孩" 属 PERSON（explicit 段）→ hasExplicit=true，但无时间/地点收窄约束。
        // 此类纯人物/概念查询不应触发 Layer 0.5 短路，需回落到 SQL + MobileCLIP 语义融合，
        // 否则只返回"有人脸 ∩ 显式 child 标签"的少量结果（回归表现：搜"小孩"只剩 1 张）。
        val result = QuerySegmenter.segment("小孩")
        assertTrue("人物词应被识别为 explicit 段", result.hasExplicit)
        assertFalse("纯人物查询不应有收窄型 explicit 约束", result.hasNarrowingExplicit)
    }

    @Test
    fun `hasNarrowingExplicit is true for time plus location plus person compound query`() {
        val result = QuerySegmenter.segment("去年3月在室内小孩的照片")
        assertTrue(result.hasNarrowingExplicit)
    }

    @Test
    fun `hasNarrowingExplicit is true for location plus person compound query`() {
        val result = QuerySegmenter.segment("北京公园里的小孩")
        assertTrue(result.hasNarrowingExplicit)
    }

    @Test
    fun `segment splits past half year child photo`() {
        val result = QuerySegmenter.segment("近半年小孩的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.TIME, "近半年"),
                Segment(SegmentType.PERSON, "小孩"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
        assertTrue(result.hasNarrowingExplicit)
    }

    @Test
    fun `segment splits recent half year variants`() {
        assertEquals(
            listOf(Segment(SegmentType.TIME, "最近半年")),
            QuerySegmenter.segment("最近半年").segments
        )
        assertEquals(
            listOf(Segment(SegmentType.TIME, "半年内")),
            QuerySegmenter.segment("半年内").segments
        )
    }

    @Test
    fun `toFilters converts past half year child query`() {
        val segmented = QuerySegmenter.segment("近半年小孩的照片")
        val (explicit, content) = QuerySegmenter.toFilters(segmented)

        assertNotNull(explicit.timeRange)
        assertEquals(true, explicit.hasFaces)
        assertEquals(listOf("小孩"), content.keywords)
    }

    @Test
    fun `segment splits last year summer photo`() {
        val result = QuerySegmenter.segment("去年夏天的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.TIME, "去年夏天"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
        assertTrue(result.hasNarrowingExplicit)
    }

    @Test
    fun `toFilters converts last year summer query`() {
        QueryParser.currentYear = 2026
        QueryParser.currentMonth = 7

        val segmented = QuerySegmenter.segment("去年夏天的照片")
        val (explicit, content) = QuerySegmenter.toFilters(segmented)

        assertNotNull(explicit.timeRange)
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = explicit.timeRange!!.startMs
        assertEquals(2025, cal.get(java.util.Calendar.YEAR))
        assertEquals(5, cal.get(java.util.Calendar.MONTH)) // June
        assertEquals(emptyList<String>(), content.keywords)
    }

    @Test
    fun `segment classifies kinship term as person`() {
        // 亲属称谓（儿子/女儿/爸爸…）应与 "小孩" 一样归入 PERSON 段，
        // 供 toFilters 产出 explicit.personKeywords 供人物解析器消费
        val result = QuerySegmenter.segment("去年夏天儿子的照片")

        assertEquals(
            listOf(
                Segment(SegmentType.TIME, "去年夏天"),
                Segment(SegmentType.PERSON, "儿子"),
                Segment(SegmentType.UNKNOWN, "照片")
            ),
            result.segments
        )
    }
}
