package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.search.QueryParser
import com.mamba.picme.domain.search.SearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] SearchIntent 时间词清洗回归测试。
 *
 * 覆盖：当 LLM 已把"去年夏天"等时间词转成 [SearchIntent.timeRange] 后，
 * 仍误把"夏天"等时间词塞进 keywords 时，VM 层应在转成 [StructuredFilter] 前将其剔除，
 * 避免 MediaSearchEngine 把时间候选集与空标签候选集取交集后返回 0 张。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelSearchIntentSanitizerTest : ChatViewModelTestBase() {

    @Before
    override fun setUp() {
        super.setUp()

        coEvery { mediaSearchEngine.search(filter = any(), limitToIds = any(), enableSemanticSearch = any()) } returns
            SearchResult(emptyList(), "")
        coEvery { mediaSearchEngine.search(query = any(), llmSearch = any(), enableSemanticSearch = any(), limitToIds = any()) } returns
            SearchResult(emptyList(), "")
    }

    @Test
    fun `onSearchMedia strips time-only keyword when timeRange present`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "去年夏天的照片",
            timeRange = TimeRange(startMs = 1_718_198_400_000, endMs = 1_725_145_599_999),
            keywords = listOf("夏天")
        )

        viewModel.onSearchMedia("去年夏天的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertTrue("时间词 '夏天' 应从 keywords 中剔除", filterSlot.captured.keywords.isEmpty())
        assertEquals("time_range 应保留", intent.timeRange?.startMs, filterSlot.captured.timeRange?.startMs)
    }

    @Test
    fun `onSearchMedia keeps non-time keywords and strips time-only ones`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "去年夏天小孩的照片",
            timeRange = TimeRange(startMs = 1_718_198_400_000, endMs = 1_725_145_599_999),
            keywords = listOf("夏天", "小孩")
        )

        viewModel.onSearchMedia("去年夏天小孩的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertEquals(listOf("小孩"), filterSlot.captured.keywords)
    }

    @Test
    fun `onSearchMedia strips Chinese and Arabic month keywords when timeRange present`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "去年3月的照片",
            timeRange = TimeRange(startMs = 1_709_251_200_000, endMs = 1_717_106_399_999),
            keywords = listOf("3月", "三月", "照片")
        )

        viewModel.onSearchMedia("去年3月的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertEquals(listOf("照片"), filterSlot.captured.keywords)
    }

    @Test
    fun `onSearchMedia backfills timeRange from bare season word and strips it from keywords`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "夏天的回忆",
            timeRange = null,
            keywords = listOf("夏天")
        )

        viewModel.onSearchMedia("夏天的回忆", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        // 与字符串搜索路径同口径：裸季节词也会被 QueryParser 解析出 timeRange，
        // 随后时间专属词从 keywords 剔除，避免时间约束与空内容候选集取交集
        assertEquals(QueryParser.parseTimeRange("夏天的回忆")?.startMs, filterSlot.captured.timeRange?.startMs)
        assertTrue(filterSlot.captured.keywords.isEmpty())
    }

    @Test
    fun `onRefineMediaSearch also sanitizes time-only keywords`() = runTest {
        val viewModel = newViewModel()
        // 先给当前 session 注入一轮结果，使 refine 走 in-set 分支
        viewModel.onSearchMedia(
            "去年的照片",
            SearchIntent(
                query = "去年的照片",
                timeRange = TimeRange(startMs = 1_704_067_200_000, endMs = 1_735_603_199_999),
                keywords = emptyList()
            )
        )
        advanceUntilIdle()

        val refineIntent = SearchIntent(
            query = "只要夏天的",
            timeRange = TimeRange(startMs = 1_718_198_400_000, endMs = 1_725_145_599_999),
            keywords = listOf("夏天")
        )
        viewModel.onRefineMediaSearch("只要夏天的", refineIntent)
        advanceUntilIdle()

        val filters = mutableListOf<StructuredFilter>()
        coVerify(atLeast = 1) { mediaSearchEngine.search(filter = capture(filters), limitToIds = any(), enableSemanticSearch = any()) }
        val refineFilter = filters.last()
        assertTrue("细化时也应剔除时间词 '夏天'", refineFilter.keywords.isEmpty())
    }

    // ── 人脸意图兜底（2026-09-29：结构化入口丢 hasFaces 的回归防护）────────────

    @Test
    fun `onSearchMedia backfills hasFaces when query asks for faces but intent lacks it`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "去年夏天有人脸的照片",
            timeRange = TimeRange(startMs = 1_718_198_400_000, endMs = 1_725_145_599_999),
        )

        viewModel.onSearchMedia("去年夏天有人脸的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertEquals("query 含'人脸'时应补回 hasFaces=true", true, filterSlot.captured.hasFaces)
    }

    @Test
    fun `onSearchMedia keeps hasFaces null when query has no people words`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "去年夏天海边的照片",
            timeRange = TimeRange(startMs = 1_718_198_400_000, endMs = 1_725_145_599_999),
        )

        viewModel.onSearchMedia("去年夏天海边的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertNull(filterSlot.captured.hasFaces)
    }

    @Test
    fun `onRefineMediaSearch backfills hasFaces from constraint text`() = runTest {
        val photo = MediaAsset(
            id = 1L, uri = "content://media/1", type = MediaType.PHOTO,
            captureDate = 0L, fileName = "a.jpg", hasFace = true,
        )
        coEvery {
            mediaSearchEngine.search(filter = any(), limitToIds = any(), enableSemanticSearch = any())
        } returns SearchResult(listOf(photo), "")
        val viewModel = newViewModel()
        viewModel.onSearchMedia(
            "去年的照片",
            SearchIntent(
                query = "去年的照片",
                timeRange = TimeRange(startMs = 1_704_067_200_000, endMs = 1_735_603_199_999),
            )
        )
        advanceUntilIdle()

        viewModel.onRefineMediaSearch("只要有人脸的", SearchIntent(query = ""))
        advanceUntilIdle()

        val filters = mutableListOf<StructuredFilter>()
        coVerify(atLeast = 1) { mediaSearchEngine.search(filter = capture(filters), limitToIds = any(), enableSemanticSearch = any()) }
        assertEquals("constraint 含'人脸'时应补回 hasFaces=true", true, filters.last().hasFaces)
    }

    // ── 时间兜底（2026-09-30 真机实证：LLM 只传 hasFace 不传 fromMs/toMs，时间约束丢失）──

    @Test
    fun `onSearchMedia backfills timeRange when intent lacks it but query has time words`() = runTest {
        val viewModel = newViewModel()
        // 真机回归场景：search_media 只给了 hasFace=true，fromMs/toMs 空串
        val intent = SearchIntent(query = "去年夏天有人脸的照片", hasFaces = true)

        viewModel.onSearchMedia("去年夏天有人脸的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        val expected = QueryParser.parseTimeRange("去年夏天有人脸的照片")
        assertNotNull("query 含'去年夏天'时应补回 timeRange", filterSlot.captured.timeRange)
        assertEquals(expected?.startMs, filterSlot.captured.timeRange?.startMs)
        assertEquals(expected?.endMs, filterSlot.captured.timeRange?.endMs)
        assertEquals(true, filterSlot.captured.hasFaces)
    }

    @Test
    fun `onSearchMedia keeps explicit timeRange over query-parsed one`() = runTest {
        val viewModel = newViewModel()
        val explicit = TimeRange(startMs = 1_718_198_400_000, endMs = 1_725_145_599_999)
        val intent = SearchIntent(query = "去年夏天有人脸的照片", timeRange = explicit, hasFaces = true)

        viewModel.onSearchMedia("去年夏天有人脸的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertEquals("上游已显式给出 timeRange 时不得覆盖", explicit.startMs, filterSlot.captured.timeRange?.startMs)
        assertEquals(explicit.endMs, filterSlot.captured.timeRange?.endMs)
    }

    @Test
    fun `onSearchMedia keeps timeRange null when query has no time words`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(query = "有人脸的照片", hasFaces = true)

        viewModel.onSearchMedia("有人脸的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertNull(filterSlot.captured.timeRange)
    }
}
