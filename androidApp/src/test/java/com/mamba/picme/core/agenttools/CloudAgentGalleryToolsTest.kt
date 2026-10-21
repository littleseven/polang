package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.model.StructuredFilter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAgentGalleryToolsTest {

    private fun asset(id: Long, type: MediaType = MediaType.PHOTO, labels: String? = null) =
        MediaAsset(id = id, uri = "content://m/$id", type = type, captureDate = id, fileName = "f$id.jpg", labels = labels)

    private fun tools(
        byQuery: suspend (String, Set<Long>?) -> List<MediaAsset> = { _, _ -> emptyList() },
        byFilter: suspend (StructuredFilter, Set<Long>?) -> List<MediaAsset> = { _, _ -> emptyList() },
        summary: suspend (Boolean) -> GallerySummary? = { null },
    ) = CloudAgentGalleryTools(searchByQuery = byQuery, searchByFilter = byFilter, gallerySummary = summary)

    @Test
    fun `summary null falls back to guidance text`() = runTest {
        val out = tools().getGallerySummary()
        assertTrue(out.contains("还没拿到你的相册数据"))
        assertTrue(!out.startsWith("error:"))
    }

    @Test
    fun `summary formatted with counts`() = runTest {
        val s = GallerySummary(
            totalPhotos = 10, totalVideos = 2, totalMedia = 12, hasFaceCount = 3,
            personClusterCount = 1, namedPersonCount = 1, labeledCount = 8, unlabeledCount = 4,
            semanticEncodedCount = 5, remainingPass1 = 0, remainingPass3 = 4,
            isScanning = false, recommendation = GallerySummary.ScanRecommendation.INCREMENTAL,
        )
        val out = tools(summary = { s }).getGallerySummary()
        assertTrue(out.contains("12 个媒体"))
        assertTrue(out.contains("10 张照片"))
        assertTrue(out.contains("增量扫描"))
    }

    @Test
    fun `plain query goes to query path and reports photo count only`() = runTest {
        val t = tools(byQuery = { q, ids ->
            assertEquals("猫", q); assertEquals(null, ids)
            listOf(asset(1), asset(2, MediaType.VIDEO), asset(3))
        })
        assertEquals("找到 2 张「猫」的照片", t.searchMedia("猫", "", "", "", ""))
    }

    @Test
    fun `structured params go to filter path`() = runTest {
        var capturedFilter: StructuredFilter? = null
        val t = tools(byFilter = { f, _ -> capturedFilter = f; listOf(asset(1)) })
        val out = t.searchMedia("去年夏天海边", "", "", "", "true")
        assertEquals(true, capturedFilter?.hasFaces)
        assertEquals("找到 1 张「去年夏天海边」的照片", out)
    }

    @Test
    fun `search failure returns error prefix`() = runTest {
        val t = tools(byQuery = { _, _ -> throw IllegalStateException("db locked") })
        assertEquals("error: db locked", t.searchMedia("猫", "", "", "", ""))
    }

    @Test
    fun `refine without base returns guidance error`() = runTest {
        val out = tools().refineMediaSearch("夜景", "", "")
        assertTrue(out.startsWith("error:"))
        assertTrue(out.contains("search_media"))
    }

    @Test
    fun `refine in-set narrows previous base`() = runTest {
        val base = listOf(asset(1, labels = "夜景"), asset(2, labels = "白天"))
        val t = tools(byQuery = { _, ids ->
            // ids == null：全局搜索（search_media）；非 null：refine 的 in-set 召回
            if (ids == null) base else base.filter { it.id in ids }
        })
        assertEquals("找到 2 张「猫」的照片", t.searchMedia("猫", "", "", "", ""))
        assertEquals("在上一轮结果内找到 1 张「夜景」的照片", t.refineMediaSearch("夜景", "", ""))
    }

    @Test
    fun `refine empty result keeps base and reports zero`() = runTest {
        var queryCount = 0
        val t = tools(byQuery = { _, ids ->
            queryCount++
            if (ids == null) listOf(asset(1, labels = "夜景"), asset(2, labels = "白天")) else emptyList()
        })
        t.searchMedia("猫", "", "", "", "")
        val out = t.refineMediaSearch("不存在的东西", "", "")
        assertTrue(out.contains("没有找到"))
        // 基数保留：再次细化仍走 in-set（limitToIds 非空）
        val again = t.refineMediaSearch("夜景", "", "")
        assertTrue(again.startsWith("在上一轮结果内找到"))
    }

    @Test
    fun `refine with time range goes to filter path scoped to base`() = runTest {
        var capturedIds: Set<Long>? = null
        val t = tools(
            byQuery = { _, _ -> listOf(asset(7), asset(8)) },
            byFilter = { _, ids -> capturedIds = ids; listOf(asset(7)) },
        )
        t.searchMedia("猫", "", "", "", "")
        val out = t.refineMediaSearch("4月的", "100", "200")
        assertEquals(setOf(7L, 8L), capturedIds)
        assertEquals("在上一轮结果内找到 1 张「4月的」的照片", out)
    }

    // ── mediaResultsListener 透出（云端图集卡恢复）────────────────

    @Test
    fun `search hit emits listener with photo assets`() = runTest {
        val hits = mutableListOf<Triple<String, List<MediaAsset>, Boolean>>()
        val t = tools(byQuery = { _, _ -> listOf(asset(1), asset(2, MediaType.VIDEO), asset(3)) })
        t.mediaResultsListener = { query, assets, _, isRefinement ->
            hits.add(Triple(query, assets, isRefinement))
        }
        t.searchMedia("猫", "", "", "", "")
        assertEquals(1, hits.size)
        assertEquals("猫", hits[0].first)
        assertEquals(false, hits[0].third)
        // 只透出 PHOTO（与回传文本的计数口径一致），VIDEO 被过滤
        assertEquals(listOf(1L, 3L), hits[0].second.map { it.id })
    }

    @Test
    fun `refine hit emits listener with refinement flag`() = runTest {
        val hits = mutableListOf<Pair<String, Boolean>>()
        val base = listOf(asset(1, labels = "夜景"), asset(2, labels = "白天"))
        val t = tools(byQuery = { _, ids ->
            if (ids == null) base else base.filter { it.id in ids && it.labels == "夜景" }
        })
        t.mediaResultsListener = { query, _, _, isRefinement -> hits.add(query to isRefinement) }
        t.searchMedia("猫", "", "", "", "")
        t.refineMediaSearch("夜景", "", "")
        assertEquals(listOf("猫" to false, "夜景" to true), hits)
    }

    @Test
    fun `empty result does not emit listener`() = runTest {
        var calls = 0
        val t = tools(byQuery = { _, _ -> emptyList() })
        t.mediaResultsListener = { _, _, _, _ -> calls++ }
        t.searchMedia("没有的东西", "", "", "", "")
        assertEquals(0, calls)
    }

    @Test
    fun `refine empty in-set does not emit listener and keeps base`() = runTest {
        var calls = 0
        val t = tools(byQuery = { _, ids ->
            if (ids == null) listOf(asset(1)) else emptyList()
        })
        t.mediaResultsListener = { _, _, _, _ -> calls++ }
        t.searchMedia("猫", "", "", "", "")
        t.refineMediaSearch("不存在", "", "")
        assertEquals(1, calls) // 仅 search 那次
    }
}
