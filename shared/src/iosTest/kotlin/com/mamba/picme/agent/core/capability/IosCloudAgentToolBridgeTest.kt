package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import com.mamba.picme.data.IosChatSearchBridge
import com.mamba.picme.data.IosMediaItem
import com.mamba.picme.data.IosMediaRepository
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.data.IosSearchResultItem
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.domain.repository.AccessState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [IosCloudAgentToolBridge] 契约测试（ADR-018 M2 iOS 对等端）：
 * unknown_tool / error 前缀 / 32k 字符截断三用例起步 + 成功路径。
 */
class IosCloudAgentToolBridgeTest {

    private class FakeBridge(
        private val items: List<IosMediaItem>
    ) : IosMediaRepositoryBridge {
        override fun currentAccessState(): AccessState = AccessState.Full
        override fun fetchAllMedia(): List<IosMediaItem> = items
        override fun requestReadWriteAuthorization() = Unit
        override fun addChangeListener(listener: () -> Unit) = Unit
        override fun removeChangeListener() = Unit
        override fun deleteMedia(localIdentifiers: List<String>): Boolean = true
        override fun setFavorite(localIdentifier: String, favorite: Boolean): Boolean = true
    }

    /** fetchAllMedia 抛异常的桥 → capability INTERNAL_ERROR → error 前缀路径。 */
    private class ThrowingBridge : IosMediaRepositoryBridge {
        override fun currentAccessState(): AccessState = AccessState.Full
        override fun fetchAllMedia(): List<IosMediaItem> = throw IllegalStateException("photos unavailable")
        override fun requestReadWriteAuthorization() = Unit
        override fun addChangeListener(listener: () -> Unit) = Unit
        override fun removeChangeListener() = Unit
        override fun deleteMedia(localIdentifiers: List<String>): Boolean = true
        override fun setFavorite(localIdentifier: String, favorite: Boolean): Boolean = true
    }

    private class FakeSearchBridge(
        var results: List<IosSearchResultItem> = emptyList()
    ) : IosChatSearchBridge {
        override fun search(
            query: String,
            intent: SearchIntent?,
            limitToDbIds: List<Long>?,
            completion: (List<IosSearchResultItem>) -> Unit
        ) = completion(results)

        override fun recordFeedback(mediaId: String, feedbackType: String, query: String, sessionId: String) = Unit
    }

    private fun newBridge(
        items: List<IosMediaItem> = listOf(
            IosMediaItem("L-1", "PHOTO", 1000L, fileName = "cat.jpg"),
            IosMediaItem("L-2", "VIDEO", 2000L, fileName = "dog.mp4"),
        ),
        mediaBridge: IosMediaRepositoryBridge = FakeBridge(items),
        searchBridge: IosChatSearchBridge? = FakeSearchBridge(),
    ): IosCloudAgentToolBridge =
        IosCloudAgentToolBridge(IosMediaRepository(mediaBridge), mediaBridge, searchBridge)

    @Test
    fun unknownToolReturnsErrorPrefix() = runTest {
        val result = newBridge().execute("delete_media", emptyMap())
        assertEquals("error: unknown_tool:delete_media", result)
    }

    @Test
    fun errorActionMapsToErrorPrefix() = runTest {
        // fetchAllMedia 抛异常 → capability 兜底 AgentAction.Error → "error: " 前缀（契约 ok=false 判定）
        val bridge = newBridge(mediaBridge = ThrowingBridge())
        val result = bridge.execute(CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY, emptyMap())
        assertTrue(result.startsWith("error:"), "expected error prefix, got: $result")
    }

    @Test
    fun truncationIsCharBasedWithSuffix() {
        val long = "x".repeat(CloudAgentToolContract.MAX_RESULT_CHARS + 100)
        val truncated = CloudAgentToolContract.truncate(long)
        assertTrue(truncated.endsWith(CloudAgentToolContract.TRUNCATED_SUFFIX))
        assertEquals(
            CloudAgentToolContract.MAX_RESULT_CHARS + CloudAgentToolContract.TRUNCATED_SUFFIX.length,
            truncated.length
        )
        // 未超限原样返回
        val exact = "y".repeat(CloudAgentToolContract.MAX_RESULT_CHARS)
        assertEquals(exact, CloudAgentToolContract.truncate(exact))
    }

    @Test
    fun gallerySummaryReturnsText() = runTest {
        val result = newBridge().execute(CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY, emptyMap())
        assertTrue(!result.startsWith("error:"), "unexpected error: $result")
        assertTrue(result.contains("2 个媒体"), "expected media count, got: $result")
    }

    @Test
    fun searchMediaArgsPassThroughAndReturnCountSummary() = runTest {
        val searchBridge = FakeSearchBridge(
            results = listOf(
                IosSearchResultItem(
                    localIdentifier = "L-1", mediaType = "PHOTO", captureDateMs = 1000L,
                    fileName = "cat.jpg", dbId = 11L,
                )
            )
        )
        val result = newBridge(searchBridge = searchBridge).execute(
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            mapOf("query" to "猫", "person" to "", "fromMs" to "", "toMs" to "", "hasFace" to ""),
        )
        assertEquals("找到 1 张「猫」的照片", result)
    }

    @Test
    fun refineWithoutBaseFallsBackToFreshSearch() = runTest {
        val result = newBridge().execute(
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
            mapOf("constraint" to "夜景", "fromMs" to "", "toMs" to ""),
        )
        assertTrue(!result.startsWith("error:"), "unexpected error: $result")
    }
}
