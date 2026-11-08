package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.data.IosChatSearchBridge
import com.mamba.picme.data.IosMediaItem
import com.mamba.picme.data.IosMediaRepository
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.data.IosSearchResultItem
import com.mamba.picme.domain.repository.AccessState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeBridge(
    private val items: List<IosMediaItem>
) : IosMediaRepositoryBridge {
    val favoriteCalls = mutableListOf<Pair<String, Boolean>>()
    var deleted: List<String> = emptyList()
        private set
    var favoriteResult: Boolean = true
    var deleteResult: Boolean = true

    override fun currentAccessState(): AccessState = AccessState.Full
    override fun fetchAllMedia(): List<IosMediaItem> = items
    override fun requestReadWriteAuthorization() = Unit
    override fun addChangeListener(listener: () -> Unit) = Unit
    override fun removeChangeListener() = Unit
    override fun deleteMedia(localIdentifiers: List<String>): Boolean {
        deleted = localIdentifiers
        return deleteResult
    }
    override fun setFavorite(localIdentifier: String, favorite: Boolean): Boolean {
        favoriteCalls += localIdentifier to favorite
        return favoriteResult
    }
}

private val ITEMS = listOf(
    IosMediaItem("L-1", "PHOTO", 1000L, fileName = "cat.jpg"),
    IosMediaItem("L-2", "PHOTO", 2000L, fileName = "dog.png"),
    IosMediaItem("L-3", "VIDEO", 3000L, 8000L, fileName = "IMG_0001.HEIC")
)

private fun idOf(localIdentifier: String): Long = localIdentifier.hashCode().toLong()

/** 搜索引擎桥 fake：同步回调 completion，记录调用。 */
private class FakeSearchBridge(
    var results: List<IosSearchResultItem> = emptyList()
) : IosChatSearchBridge {
    data class SearchCall(val query: String, val intent: SearchIntent?, val limitToDbIds: List<Long>?)

    val searchCalls = mutableListOf<SearchCall>()

    override fun search(
        query: String,
        intent: SearchIntent?,
        limitToDbIds: List<Long>?,
        completion: (List<IosSearchResultItem>) -> Unit
    ) {
        searchCalls += SearchCall(query, intent, limitToDbIds)
        completion(results)
    }
}

private fun searchItem(
    dbId: Long,
    localIdentifier: String,
    mediaType: String = "PHOTO",
    fileName: String = "IMG_$dbId.jpg",
    hasFace: Boolean = false,
    labels: String? = null,
    ocrText: String? = null,
    locationName: String? = null
) = IosSearchResultItem(
    dbId = dbId,
    localIdentifier = localIdentifier,
    mediaType = mediaType,
    captureDateMs = dbId * 1000,
    durationMs = null,
    fileName = fileName,
    hasFace = hasFace,
    labels = labels,
    ocrText = ocrText,
    locationName = locationName,
    city = null
)

class IosChatGalleryCapabilityTest {

    private val context = AgentContext(scene = AgentScene.CHAT)

    private fun newCapability(bridge: FakeBridge = FakeBridge(ITEMS)) =
        IosChatGalleryCapability(IosMediaRepository(bridge), bridge) to bridge

    // ── 盘点 ──────────────────────────────────────────────────────────────

    @Test
    fun summaryCountsPhotosAndVideos() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.GetGallerySummary(), context, null).getOrThrow()
        val reply = assertIs<AgentAction.TextReply>(action)
        assertTrue(reply.message.contains("3 个媒体"), "应含总数：${reply.message}")
        assertTrue(reply.message.contains("2 张照片"), "应含照片数：${reply.message}")
        assertTrue(reply.message.contains("1 个视频"), "应含视频数：${reply.message}")
    }

    // ── 搜索 / 细化（降级路径；横滑卡退役后回执 = TextReply 计数文案）────────────

    @Test
    fun searchMatchesFileNameIgnoreCase() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.SearchMedia(query = "CAT"), context, null).getOrThrow()
        assertEquals("找到 1 张「CAT」的照片", assertIs<AgentAction.TextReply>(action).message)
    }

    @Test
    fun searchNoMatchReturnsZeroResults() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.SearchMedia(query = "zzz"), context, null).getOrThrow()
        assertEquals("找到 0 张「zzz」的照片", assertIs<AgentAction.TextReply>(action).message)
    }

    @Test
    fun searchWithTimeRangeFiltersCaptureDate() = runTest {
        val (cap, _) = newCapability()
        val command = AgentCommand.SearchMedia(
            query = "",
            intent = SearchIntent(query = "", timeRange = TimeRange(1500L, 2500L))
        )
        val action = cap.execute(command, context, null).getOrThrow()
        assertEquals("找到 1 张「」的照片", assertIs<AgentAction.TextReply>(action).message)
    }

    @Test
    fun refineNarrowsWithinPreviousResults() = runTest {
        val (cap, _) = newCapability()
        // 空关键词搜索 = 「看看最近的」，种子为全部 3 条
        cap.execute(AgentCommand.SearchMedia(query = ""), context, null).getOrThrow()
        val action = cap.execute(AgentCommand.RefineMediaSearch(constraint = "cat"), context, null).getOrThrow()
        assertEquals("在上一轮结果内找到 1 张「cat」的照片", assertIs<AgentAction.TextReply>(action).message)
    }

    @Test
    fun refineWithoutPreviousSearchFallsBackToGlobal() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.RefineMediaSearch(constraint = "dog"), context, null).getOrThrow()
        assertEquals("找到 1 张「dog」的照片", assertIs<AgentAction.TextReply>(action).message)
    }

    @Test
    fun refineEmptyKeepsPriorRoundUnchanged() = runTest {
        val (cap, _) = newCapability()
        cap.execute(AgentCommand.SearchMedia(query = ""), context, null).getOrThrow()
        // 细化无命中 → 计数 0，上一轮集合保留（再细化仍基于原集合）
        val empty = cap.execute(AgentCommand.RefineMediaSearch(constraint = "不存在"), context, null).getOrThrow()
        assertEquals("在上一轮结果内找到 0 张「不存在」的照片", assertIs<AgentAction.TextReply>(empty).message)
        val again = cap.execute(AgentCommand.RefineMediaSearch(constraint = "dog"), context, null).getOrThrow()
        assertEquals("在上一轮结果内找到 1 张「dog」的照片", assertIs<AgentAction.TextReply>(again).message)
    }

    // ── 写命令 ──────────────────────────────────────────────────────────────

    @Test
    fun favoriteCallsBridgeWithResolvedIdentifier() = runTest {
        val (cap, bridge) = newCapability()
        val command = AgentCommand.FavoriteMedia(mediaId = idOf("L-1").toString(), favorite = true)
        val action = cap.execute(command, context, null).getOrThrow()
        val reply = assertIs<AgentAction.TextReply>(action)
        assertEquals("已收藏", reply.message)
        assertEquals(listOf("L-1" to true), bridge.favoriteCalls)
    }

    @Test
    fun favoriteUnknownMediaReturnsNotFoundReply() = runTest {
        val (cap, bridge) = newCapability()
        val command = AgentCommand.FavoriteMedia(mediaId = "999999", favorite = true)
        val action = cap.execute(command, context, null).getOrThrow()
        assertIs<AgentAction.TextReply>(action)
        assertTrue(bridge.favoriteCalls.isEmpty(), "未命中媒体不应调用桥")
    }

    @Test
    fun deleteResolvesIdentifiersAndClearsLastSearch() = runTest {
        val (cap, bridge) = newCapability()
        // 先搜索种子，删除后 refine 应退化为全局（lastSearchAssets 已清）
        cap.execute(AgentCommand.SearchMedia(query = ""), context, null).getOrThrow()
        val command = AgentCommand.DeleteMedia(mediaIds = listOf(idOf("L-1").toString()))
        val action = cap.execute(command, context, null).getOrThrow()
        val reply = assertIs<AgentAction.TextReply>(action)
        assertTrue(reply.message.contains("1 个媒体"), "应含删除数：${reply.message}")
        assertEquals(listOf("L-1"), bridge.deleted)

        val refine = cap.execute(AgentCommand.RefineMediaSearch(constraint = "dog"), context, null).getOrThrow()
        assertEquals("找到 1 张「dog」的照片", assertIs<AgentAction.TextReply>(refine).message)
    }

    @Test
    fun deleteEmptyIdsReturnsInvalidParams() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.DeleteMedia(), context, null).getOrThrow()
        val error = assertIs<AgentAction.Error>(action)
        assertEquals(AgentErrorCode.INVALID_PARAMS, error.errorCode)
    }

    // ── UI 直通命令 ─────────────────────────────────────────────────────────

    @Test
    fun viewSelectSharePassThroughAsSuccess() = runTest {
        val (cap, _) = newCapability()
        val view = AgentCommand.ViewMedia(mediaId = idOf("L-1").toString())
        val viewAction = cap.execute(view, context, null).getOrThrow()
        assertEquals(view, assertIs<AgentAction.Success>(viewAction).command)

        val select = AgentCommand.SelectMedia(mediaId = idOf("L-2").toString(), selected = true)
        val selectAction = cap.execute(select, context, null).getOrThrow()
        assertEquals(select, assertIs<AgentAction.Success>(selectAction).command)

        val share = AgentCommand.ShareMedia(mediaIds = listOf(idOf("L-3").toString()))
        val shareAction = cap.execute(share, context, null).getOrThrow()
        assertEquals(share, assertIs<AgentAction.Success>(shareAction).command)
    }

    @Test
    fun viewWithoutMediaIdReturnsInvalidParams() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.ViewMedia(), context, null).getOrThrow()
        val error = assertIs<AgentAction.Error>(action)
        assertEquals(AgentErrorCode.INVALID_PARAMS, error.errorCode)
    }

    @Test
    fun unsupportedCommandReturnsMethodNotFound() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.StartTagScan(action = "query"), context, null).getOrThrow()
        val error = assertIs<AgentAction.Error>(action)
        assertEquals(AgentErrorCode.METHOD_NOT_FOUND, error.errorCode)
    }

    @Test
    fun supportedCommandsCoverChatGalleryTools() {
        val (cap, _) = newCapability()
        assertEquals(
            listOf(
                "get_gallery_summary", "search_media", "refine_media_search",
                "view_media", "select_media", "favorite_media", "delete_media", "share_media"
            ),
            cap.supportedCommands()
        )
    }
}

/**
 * 搜索引擎路径测试（[IosChatSearchBridge] 已注入，契约 §9）。
 * 降级路径（无搜索引擎桥）行为由 [IosChatGalleryCapabilityTest] 覆盖。
 */
class IosChatGalleryCapabilityEngineTest {

    private val context = AgentContext(scene = AgentScene.CHAT, memorySessionId = "s1")

    private fun newCapability(search: FakeSearchBridge): IosChatGalleryCapability {
        val mediaBridge = FakeBridge(emptyList())
        return IosChatGalleryCapability(IosMediaRepository(mediaBridge), mediaBridge, search)
    }

    private val twoPhotos = listOf(
        searchItem(101L, "S-1", fileName = "beach.jpg", labels = """{"tags":["海边","沙滩"]}"""),
        searchItem(102L, "S-2", fileName = "night.jpg", hasFace = true, labels = """{"tags":["夜景"]}""")
    )

    // ── search_media（契约 §9.2）──────────────────────────────────────────────

    @Test
    fun searchUsesEngineAndKeepsOnlyPhotos() = runTest {
        val search = FakeSearchBridge(twoPhotos + searchItem(103L, "S-3", mediaType = "VIDEO"))
        val cap = newCapability(search)
        val action = cap.execute(AgentCommand.SearchMedia(query = "海边"), context, null).getOrThrow()
        // 回执 = TextReply 计数文案（横滑卡退役，逐字对齐 Android toTextReply）；VIDEO 已过滤
        assertEquals("找到 2 张「海边」的照片", assertIs<AgentAction.TextReply>(action).message)
        assertEquals(FakeSearchBridge.SearchCall("海边", null, null), search.searchCalls.single())
    }

    @Test
    fun searchReportsFullHitCountInReply() = runTest {
        val many = (1L..30L).map { searchItem(it, "S-$it") }
        val cap = newCapability(FakeSearchBridge(many))
        val action = cap.execute(AgentCommand.SearchMedia(query = "全部"), context, null).getOrThrow()
        // 无卡片截断概念：回执文案承载全量命中数
        assertEquals("找到 30 张「全部」的照片", assertIs<AgentAction.TextReply>(action).message)
    }

    // ── refine_media_search（契约 §9.2/§9.4）──────────────────────────────────

    @Test
    fun refineWithIntentSearchesEngineInPriorSet() = runTest {
        val search = FakeSearchBridge(twoPhotos)
        val cap = newCapability(search)
        cap.execute(AgentCommand.SearchMedia(query = "照片"), context, null).getOrThrow()

        val intent = SearchIntent(query = "", timeRange = TimeRange(0L, 9999L))
        search.results = listOf(twoPhotos[1])
        val action = cap.execute(AgentCommand.RefineMediaSearch(constraint = "4月", intent = intent), context, null).getOrThrow()

        // 结构化交集：limitToDbIds = 上一轮 dbId 集合
        assertEquals(
            FakeSearchBridge.SearchCall("4月", intent, listOf(101L, 102L)),
            search.searchCalls.last()
        )
        assertEquals("在上一轮结果内找到 1 张「4月」的照片", assertIs<AgentAction.TextReply>(action).message)
    }

    @Test
    fun refineStringPathPrefersFilterInSet() = runTest {
        val search = FakeSearchBridge(twoPhotos)
        val cap = newCapability(search)
        cap.execute(AgentCommand.SearchMedia(query = "照片"), context, null).getOrThrow()

        // 「只要夜景」→ 清洗为「夜景」；filterInSet 按 labels 命中 S-2（不走引擎语义并集）
        val action = cap.execute(AgentCommand.RefineMediaSearch(constraint = "只要夜景"), context, null).getOrThrow()
        assertEquals("在上一轮结果内找到 1 张「只要夜景」的照片", assertIs<AgentAction.TextReply>(action).message)
        assertEquals("夜景", search.searchCalls.last().query, "constraint 应被清洗")
        assertEquals(listOf(101L, 102L), search.searchCalls.last().limitToDbIds)
    }

    @Test
    fun refineEmptyKeepsPriorRoundUnchanged() = runTest {
        val search = FakeSearchBridge(twoPhotos)
        val cap = newCapability(search)
        cap.execute(AgentCommand.SearchMedia(query = "照片"), context, null).getOrThrow()

        // 细化无命中（filterInSet 空 + 引擎 hits 空）→ 计数 0，上一轮集合不变
        search.results = emptyList()
        val empty = cap.execute(AgentCommand.RefineMediaSearch(constraint = "不存在"), context, null).getOrThrow()
        assertEquals("在上一轮结果内找到 0 张「不存在」的照片", assertIs<AgentAction.TextReply>(empty).message)

        // 再细化仍基于上一轮 2 条（状态未被空结果覆盖）
        search.results = twoPhotos
        val again = cap.execute(AgentCommand.RefineMediaSearch(constraint = "夜景"), context, null).getOrThrow()
        assertEquals("在上一轮结果内找到 1 张「夜景」的照片", assertIs<AgentAction.TextReply>(again).message)
    }

    @Test
    fun refineWithoutPriorReturnsGuardError() = runTest {
        val search = FakeSearchBridge(twoPhotos)
        val cap = newCapability(search)
        // 回合护栏 c（对齐 Android）：无搜索基数 → 明确错误引导改用 search_media，
        // 不静默全局重搜、不调引擎、不动状态
        val action = cap.execute(AgentCommand.RefineMediaSearch(constraint = "海边"), context, null).getOrThrow()
        val error = assertIs<AgentAction.Error>(action)
        assertEquals(AgentErrorCode.INVALID_PARAMS, error.errorCode)
        assertTrue(error.message.contains("没有上一轮搜索结果可细化"), "应引导改用 search_media：${error.message}")
        assertTrue(search.searchCalls.isEmpty(), "无基数时不得调引擎")
    }
}
