package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.FakeLocalHandleDao
import com.mamba.picme.data.local.LocalHandleDao
import com.mamba.picme.data.local.LocalHandleEntity
import com.mamba.picme.data.local.LocalHandleStore
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.ScanPhotoEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatSearchCapability] 句柄回执单测（U2-7，参照 [CloudAgentGalleryToolsTest] 先例）：
 * search/refine 回执尾部附 `（数据句柄 handle: scan/<12hex>）` 并注册 LocalHandleStore，
 * mediaType 过滤命中集、refine 跟随产生基数的 search、注册失败静默降级纯前缀。
 */
class ChatSearchCapabilityTest {

    private val context = AgentContext(scene = AgentScene.CHAT)
    private val capability = ChatSearchCapability.getInstance()
    private val dao = FakeLocalHandleDao()
    private val store = LocalHandleStore(dao)
    private val delegate = StubDelegate()

    /** id→MediaAsset 解析夹具（生产接线 = repository.allMedia 快照，AppContainer 注入）。 */
    private val library = listOf(
        asset(11),
        asset(22, MediaType.VIDEO),
        asset(33, MediaType.VIDEO),
        asset(44),
    )

    @Before
    fun setUp() {
        capability.resetScanHandleSupport()
        capability.bindDelegate(delegate)
        capability.attachScanHandles(store) { ids ->
            val byId = library.associateBy { asset -> asset.id }
            ids.mapNotNull { id -> byId[id] }
        }
    }

    @After
    fun tearDown() {
        capability.unbindDelegate()
        capability.resetScanHandleSupport()
    }

    private fun asset(id: Long, type: MediaType = MediaType.PHOTO) =
        MediaAsset(id = id, uri = "content://m/$id", type = type, captureDate = id * 100, fileName = "f$id.jpg")

    private suspend fun search(outcome: SearchOutcome, mediaType: String? = null): AgentAction =
        capability.execute(
            AgentCommand.SearchMedia(
                query = outcome.query,
                intent = null,
                mediaType = mediaType ?: "photo",
            ),
            context,
            null,
        ).getOrThrow()

    private suspend fun refine(outcome: SearchOutcome): AgentAction =
        capability.execute(
            AgentCommand.RefineMediaSearch(constraint = outcome.query, intent = null),
            context,
            null,
        ).getOrThrow()

    private fun handleIdOf(message: String): String =
        Regex("handle: scan/([0-9a-f]{12})").find(message)!!.groupValues[1]

    private suspend fun entriesOf(message: String): List<ScanPhotoEntry> =
        Json.decodeFromString(store.payloadOf(OpenUiContract.DOMAIN_SCAN, handleIdOf(message))!!)

    // ── Step 1-a：search 命中后回执含句柄尾缀且 store 可按 id 取回 entries ─────

    @Test
    fun `search hit appends scan handle suffix and store round-trips entries`() = runTest {
        delegate.searchOutcome = SearchOutcome("海边", listOf(11L, 44L), 2, isRefinement = false)
        val reply = search(delegate.searchOutcome) as AgentAction.TextReply
        assertEquals("找到 2 张「海边」的照片，媒体 id：11,44（数据句柄 handle: scan/${handleIdOf(reply.message)}）", reply.message)
        val entries = entriesOf(reply.message)
        assertEquals(listOf(11L, 44L), entries.map { entry -> entry.id })
        assertEquals(listOf(1100L, 4400L), entries.map { entry -> entry.captureDate })
        assertEquals(listOf("PHOTO", "PHOTO"), entries.map { entry -> entry.mediaType })
    }

    @Test
    fun `payload structure keeps no uri`() = runTest {
        delegate.searchOutcome = SearchOutcome("海边", listOf(11L), 1, isRefinement = false)
        val reply = search(delegate.searchOutcome) as AgentAction.TextReply
        val payloadJson = dao.rows.getValue(handleIdOf(reply.message)).payloadJson
        assertFalse("D2 结构保证：payload 不得含 uri：$payloadJson", payloadJson.contains("\"uri\""))
    }

    // ── Step 1-b：mediaType=video 只注册 VIDEO entries ──────────────────────────

    @Test
    fun `video mediaType registers only video entries`() = runTest {
        delegate.searchOutcome = SearchOutcome("旅行", listOf(11L, 22L, 33L), 3, isRefinement = false)
        val reply = search(delegate.searchOutcome, mediaType = "video") as AgentAction.TextReply
        val entries = entriesOf(reply.message)
        assertEquals(listOf(22L, 33L), entries.map { entry -> entry.id })
        assertEquals(listOf("VIDEO", "VIDEO"), entries.map { entry -> entry.mediaType })
    }

    @Test
    fun `photo mediaType filters video entries out`() = runTest {
        delegate.searchOutcome = SearchOutcome("旅行", listOf(11L, 22L), 2, isRefinement = false)
        val reply = search(delegate.searchOutcome, mediaType = "photo") as AgentAction.TextReply
        assertEquals(listOf(11L), entriesOf(reply.message).map { entry -> entry.id })
    }

    @Test
    fun `all mediaType registers photos and videos`() = runTest {
        delegate.searchOutcome = SearchOutcome("全部", listOf(11L, 22L), 2, isRefinement = false)
        val reply = search(delegate.searchOutcome, mediaType = "all") as AgentAction.TextReply
        assertEquals(listOf("PHOTO", "VIDEO"), entriesOf(reply.message).map { entry -> entry.mediaType })
    }

    // ── Step 1-c：store 抛异常时回执为纯前缀（不走 error: 协议）────────────────

    @Test
    fun `handle registration failure degrades to plain prefix`() = runTest {
        val failingDao = object : LocalHandleDao {
            override suspend fun upsert(handle: LocalHandleEntity) {
                error("disk full")
            }
            override suspend fun byId(handleId: String): LocalHandleEntity? = null
            override suspend fun deleteOlderThan(beforeMs: Long): Int = 0
        }
        capability.attachScanHandles(LocalHandleStore(failingDao)) { ids ->
            library.filter { asset -> asset.id in ids.toSet() }
        }
        delegate.searchOutcome = SearchOutcome("海边", listOf(11L, 44L), 2, isRefinement = false)
        val reply = search(delegate.searchOutcome) as AgentAction.TextReply
        assertEquals("找到 2 张「海边」的照片，媒体 id：11,44", reply.message)
    }

    // ── 空命中 / 错误结果不注册句柄（与云端空分支同口径）──────────────────────

    @Test
    fun `zero hit keeps plain count and registers nothing`() = runTest {
        delegate.searchOutcome = SearchOutcome("zzz", emptyList(), 0, isRefinement = false)
        val reply = search(delegate.searchOutcome) as AgentAction.TextReply
        assertEquals("找到 0 张「zzz」的照片", reply.message)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `error outcome maps to error action without handle`() = runTest {
        delegate.refineOutcome = SearchOutcome(
            "夜景", emptyList(), 0, isRefinement = true,
            errorMessage = "没有上一轮搜索结果可细化：请改用 search_media 重新全局搜索",
        )
        val action = refine(delegate.refineOutcome)
        assertTrue(action is AgentAction.Error)
        assertTrue(dao.rows.isEmpty())
    }

    // ── refine 的 mediaType 跟随产生基数的 search（云端 refineMediaType 先例）──

    @Test
    fun `refine mediaType follows producing search video`() = runTest {
        delegate.searchOutcome = SearchOutcome("旅行", listOf(22L, 33L), 2, isRefinement = false)
        search(delegate.searchOutcome, mediaType = "video")
        delegate.refineOutcome = SearchOutcome("夜景", listOf(11L, 22L, 33L), 3, isRefinement = true)
        val reply = refine(delegate.refineOutcome) as AgentAction.TextReply
        assertEquals(listOf(22L, 33L), entriesOf(reply.message).map { entry -> entry.id })
    }

    @Test
    fun `refine mediaType follows producing search photo default`() = runTest {
        delegate.searchOutcome = SearchOutcome("海边", listOf(11L), 1, isRefinement = false)
        search(delegate.searchOutcome)
        delegate.refineOutcome = SearchOutcome("夜景", listOf(11L, 22L), 2, isRefinement = true)
        val reply = refine(delegate.refineOutcome) as AgentAction.TextReply
        assertEquals(listOf(11L), entriesOf(reply.message).map { entry -> entry.id })
    }

    @Test
    fun `refine hit appends handle suffix`() = runTest {
        delegate.searchOutcome = SearchOutcome("海边", listOf(11L), 1, isRefinement = false)
        search(delegate.searchOutcome)
        delegate.refineOutcome = SearchOutcome("夜景", listOf(11L, 44L), 2, isRefinement = true)
        val reply = refine(delegate.refineOutcome) as AgentAction.TextReply
        assertTrue(reply.message.startsWith("在上一轮结果内找到 2 张「夜景」的照片，媒体 id：11,44（数据句柄 handle: scan/"))
    }

    // ── 未注入句柄接缝时兼容旧形态（纯前缀）────────────────────────────────────

    @Test
    fun `without attached store receipt stays plain`() = runTest {
        capability.resetScanHandleSupport()
        delegate.searchOutcome = SearchOutcome("海边", listOf(11L, 44L), 2, isRefinement = false)
        val reply = search(delegate.searchOutcome) as AgentAction.TextReply
        assertEquals("找到 2 张「海边」的照片，媒体 id：11,44", reply.message)
    }

    private class StubDelegate : ChatSearchCapability.Delegate {
        var searchOutcome = SearchOutcome("海边", emptyList(), 0, isRefinement = false)
        var refineOutcome = SearchOutcome("夜景", emptyList(), 0, isRefinement = true)

        override suspend fun onSearchMedia(query: String, intent: com.mamba.picme.agent.core.model.context.SearchIntent?): SearchOutcome =
            searchOutcome

        override suspend fun onRefineMediaSearch(constraint: String, intent: com.mamba.picme.agent.core.model.context.SearchIntent?): SearchOutcome =
            refineOutcome
    }
}
