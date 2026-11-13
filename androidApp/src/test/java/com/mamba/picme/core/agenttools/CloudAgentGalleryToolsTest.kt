package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.FakeLocalHandleDao
import com.mamba.picme.data.local.LocalHandleDao
import com.mamba.picme.data.local.LocalHandleEntity
import com.mamba.picme.data.local.LocalHandleStore
import com.mamba.picme.domain.chat.openui.ScanPhotoEntry
import com.mamba.picme.domain.model.StructuredFilter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAgentGalleryToolsTest {

    private fun asset(id: Long, type: MediaType = MediaType.PHOTO, labels: String? = null) =
        MediaAsset(id = id, uri = "content://m/$id", type = type, captureDate = id, fileName = "f$id.jpg", labels = labels)

    private fun tools(
        byQuery: suspend (String, Set<Long>?) -> List<MediaAsset> = { _, _ -> emptyList() },
        byFilter: suspend (StructuredFilter, Set<Long>?) -> List<MediaAsset> = { _, _ -> emptyList() },
        summary: suspend (Boolean) -> GallerySummary? = { null },
        handleStore: LocalHandleStore = LocalHandleStore(FakeLocalHandleDao()),
        allPhotos: suspend () -> List<MediaAsset> = { emptyList() },
    ) = CloudAgentGalleryTools(
        searchByQuery = byQuery,
        searchByFilter = byFilter,
        gallerySummary = summary,
        localHandleStore = handleStore,
        allPhotos = allPhotos,
    )

    /** 命中回文本新形态（D2 投影）：前缀 + opaque token 尾缀。 */
    private fun handleTextOf(prefix: String): Regex =
        Regex(Regex.escape(prefix) + "（数据句柄 handle: scan/[0-9a-f]{12}）")

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
    fun `summary reply carries all-media scan handle when library non-empty`() = runTest {
        // 2026-11-13 placeholder 假句柄事故修复：摘要回执尾部注册全库句柄，
        // 月度分布/清单绑定有合法 handle，模型不再臆造 local://scan/placeholder
        val s = GallerySummary(
            totalPhotos = 10, totalVideos = 2, totalMedia = 12, hasFaceCount = 3,
            personClusterCount = 1, namedPersonCount = 1, labeledCount = 8, unlabeledCount = 4,
            semanticEncodedCount = 5, remainingPass1 = 0, remainingPass3 = 4,
            isScanning = false, recommendation = GallerySummary.ScanRecommendation.INCREMENTAL,
        )
        val store = LocalHandleStore(FakeLocalHandleDao())
        val out = tools(
            summary = { s },
            handleStore = store,
            allPhotos = { listOf(asset(1), asset(2, MediaType.VIDEO), asset(3)) },
        ).getGallerySummary()
        assertTrue(out.contains("12 个媒体"))
        assertTrue("摘要回执应携带全库数据句柄：$out", out.contains("（数据句柄 handle: scan/"))
        // 句柄真实可查（端侧 payloadOf 语义：domain 匹配 + payload 为全量条目）
        val handleId = Regex("handle: scan/([0-9a-f]{12})").find(out)!!.groupValues[1]
        val payload = store.payloadOf("scan", handleId)
        assertTrue("句柄 payload 应含全部 3 条条目：$payload", payload!!.split("\"id\"").size - 1 == 3)
    }

    @Test
    fun `summary handle registration failure degrades to plain text`() = runTest {
        val s = GallerySummary(
            totalPhotos = 10, totalVideos = 2, totalMedia = 12, hasFaceCount = 3,
            personClusterCount = 1, namedPersonCount = 1, labeledCount = 8, unlabeledCount = 4,
            semanticEncodedCount = 5, remainingPass1 = 0, remainingPass3 = 4,
            isScanning = false, recommendation = GallerySummary.ScanRecommendation.INCREMENTAL,
        )
        val out = tools(
            summary = { s },
            allPhotos = { error("allMedia unavailable") },
        ).getGallerySummary()
        assertTrue(out.contains("12 个媒体"))
        assertTrue(!out.contains("数据句柄"))
        assertTrue(!out.startsWith("error:"))
    }

    @Test
    fun `plain query goes to query path and reports photo count only`() = runTest {
        val t = tools(byQuery = { q, ids ->
            assertEquals("猫", q); assertEquals(null, ids)
            listOf(asset(1), asset(2, MediaType.VIDEO), asset(3))
        })
        assertTrue(handleTextOf("找到 2 张「猫」的照片").matches(t.searchMedia("猫", "", "", "", "")))
    }

    @Test
    fun `structured params go to filter path`() = runTest {
        var capturedFilter: StructuredFilter? = null
        val t = tools(byFilter = { f, _ -> capturedFilter = f; listOf(asset(1)) })
        val out = t.searchMedia("去年夏天海边", "", "", "", "true")
        assertEquals(true, capturedFilter?.hasFaces)
        assertTrue(handleTextOf("找到 1 张「去年夏天海边」的照片").matches(out))
    }

    @Test
    fun `blank query without filters returns all photos via dedicated channel`() = runTest {
        var queryPathUsed = false
        val t = tools(
            byQuery = { _, _ -> queryPathUsed = true; emptyList() },
            allPhotos = { listOf(asset(1), asset(2, MediaType.VIDEO), asset(3)) },
        )
        val out = t.searchMedia("", "", "", "", "")
        assertFalse("空 query 不得走语义搜索路径（blank 短路返回空）", queryPathUsed)
        // VIDEO 仍被过滤，展示名兜底为「全部」
        assertTrue(handleTextOf("找到 2 张「全部」的照片").matches(out))
    }

    @Test
    fun `blank query with time filter still goes to filter path`() = runTest {
        var capturedFilter: StructuredFilter? = null
        val t = tools(
            byFilter = { f, _ -> capturedFilter = f; listOf(asset(1)) },
            allPhotos = { error("有结构化约束时不得走全量通道") },
        )
        t.searchMedia("", "", "100", "200", "")
        assertEquals(100L, capturedFilter?.timeRange?.startMs)
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
        assertTrue(handleTextOf("找到 2 张「猫」的照片").matches(t.searchMedia("猫", "", "", "", "")))
        assertTrue(handleTextOf("在上一轮结果内找到 1 张「夜景」的照片").matches(t.refineMediaSearch("夜景", "", "")))
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
        assertTrue(handleTextOf("在上一轮结果内找到 1 张「4月的」的照片").matches(out))
    }


    // ── opaque handle 注册（D2：命中集引用式不上云）────────────────

    @Test
    fun `search hit registers scan handle and appends opaque token`() = runTest {
        val dao = FakeLocalHandleDao()
        val t = tools(
            byQuery = { _, _ -> listOf(asset(1), asset(2)) },
            handleStore = LocalHandleStore(dao),
        )
        val out = t.searchMedia("猫", "", "", "", "")
        val match = Regex(".*（数据句柄 handle: scan/([0-9a-f]{12})）$").find(out)
        assertTrue("返回文本须以 opaque token 收尾：$out", match != null)
        val handleId = match!!.groupValues[1]
        val payloadJson = dao.rows.getValue(handleId).payloadJson
        // D2：payload 只序列化 ScanPhotoEntry(id/captureDate)，结构保证无 uri
        assertFalse(payloadJson.contains("\"uri\""))
        val entries = Json.decodeFromString<List<ScanPhotoEntry>>(payloadJson)
        assertEquals(2, entries.size)
        assertEquals(listOf(1L, 2L), entries.map { entry -> entry.id })
    }

    @Test
    fun `search empty hit returns plain text and registers nothing`() = runTest {
        val dao = FakeLocalHandleDao()
        val t = tools(
            byQuery = { _, _ -> emptyList() },
            handleStore = LocalHandleStore(dao),
        )
        val out = t.searchMedia("没有的东西", "", "", "", "")
        // 空命中不注册句柄、不带 token（与 refine 空分支同口径）
        assertFalse(out.contains("数据句柄"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `handle registration failure degrades to plain text without error prefix`() = runTest {
        val failingDao = object : LocalHandleDao {
            override suspend fun upsert(handle: LocalHandleEntity) {
                throw IllegalStateException("disk full")
            }
            override suspend fun byId(handleId: String): LocalHandleEntity? = null
            override suspend fun deleteOlderThan(beforeMs: Long): Int = 0
        }
        val t = tools(
            byQuery = { _, _ -> listOf(asset(1), asset(2)) },
            handleStore = LocalHandleStore(failingDao),
        )
        // 搜索成功但 Room 写失败 → 降级为无 token 前缀，不走 error: 协议
        assertEquals("找到 2 张「猫」的照片", t.searchMedia("猫", "", "", "", ""))
    }

    // ── mediaType 媒体类型过滤（2026-11-04 Video 组件批次）────────────────

    @Test
    fun `mediaType video keeps only videos and reports video wording`() = runTest {
        val t = tools(byQuery = { _, _ -> listOf(asset(1), asset(2, MediaType.VIDEO), asset(3, MediaType.VIDEO)) })
        assertTrue(
            handleTextOf("找到 2 段「旅行」的视频").matches(t.searchMedia("旅行", "", "", "", "", "video")),
        )
    }

    @Test
    fun `mediaType all keeps photos and videos with media wording`() = runTest {
        val t = tools(byQuery = { _, _ -> listOf(asset(1), asset(2, MediaType.VIDEO)) })
        assertTrue(
            handleTextOf("找到 2 个「旅行」的媒体").matches(t.searchMedia("旅行", "", "", "", "", "all")),
        )
    }

    @Test
    fun `mediaType unknown falls back to photo only`() = runTest {
        val t = tools(byQuery = { _, _ -> listOf(asset(1), asset(2, MediaType.VIDEO)) })
        assertTrue(
            handleTextOf("找到 1 张「猫」的照片").matches(t.searchMedia("猫", "", "", "", "", "bogus")),
        )
    }

    @Test
    fun `handle payload carries mediaType for projection split`() = runTest {
        val dao = FakeLocalHandleDao()
        val t = tools(
            byQuery = { _, _ -> listOf(asset(1), asset(2, MediaType.VIDEO)) },
            handleStore = LocalHandleStore(dao),
        )
        val out = t.searchMedia("旅行", "", "", "", "", "all")
        val handleId = Regex("handle: scan/([0-9a-f]{12})").find(out)!!.groupValues[1]
        val entries = Json.decodeFromString<List<ScanPhotoEntry>>(dao.rows.getValue(handleId).payloadJson)
        assertEquals(listOf("PHOTO", "VIDEO"), entries.map { entry -> entry.mediaType })
    }

    @Test
    fun `refine follows base search mediaType`() = runTest {
        val t = tools(
            byQuery = { _, _ ->
                listOf(asset(1, MediaType.VIDEO), asset(2, MediaType.VIDEO), asset(3))
            },
        )
        t.searchMedia("旅行", "", "", "", "", "video")
        // 视频基数上的细化：照片 asset(3) 不得混回结果集
        assertTrue(
            handleTextOf("在上一轮结果内找到 2 段「夜景」的视频")
                .matches(t.refineMediaSearch("夜景", "", "")),
        )
    }

    @Test
    fun `video search empty hit reports zero videos`() = runTest {
        val t = tools(byQuery = { _, _ -> listOf(asset(1)) })
        assertEquals("找到 0 段「旅行」的视频", t.searchMedia("旅行", "", "", "", "", "video"))
    }
}
