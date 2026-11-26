package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.FakeLocalHandleDao
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
 * [ChatGallerySummaryCapability] 句柄回执单测（U2-7，对齐
 * CloudAgentGalleryTools.getGallerySummary 全库句柄先例）：摘要回执尾部注册
 * 全库媒体 local://scan 句柄，查询/注册失败静默降级纯文本。
 */
class ChatGallerySummaryCapabilityTest {

    private val context = AgentContext(scene = AgentScene.CHAT)
    private val capability = ChatGallerySummaryCapability.getInstance()
    private val dao = FakeLocalHandleDao()
    private val store = LocalHandleStore(dao)
    private val delegate = StubDelegate()

    private val summary = GallerySummary(
        totalPhotos = 10, totalVideos = 2, totalMedia = 12, hasFaceCount = 3,
        personClusterCount = 1, namedPersonCount = 1, labeledCount = 8, unlabeledCount = 4,
        semanticEncodedCount = 5, remainingPass1 = 0, remainingPass3 = 4,
        isScanning = false, recommendation = GallerySummary.ScanRecommendation.INCREMENTAL,
    )

    private val library = listOf(
        MediaAsset(id = 11L, uri = "content://m/11", type = MediaType.PHOTO, captureDate = 100L, fileName = "a.jpg"),
        MediaAsset(id = 22L, uri = "content://m/22", type = MediaType.VIDEO, captureDate = 200L, fileName = "b.mp4"),
    )

    @Before
    fun setUp() {
        capability.resetScanHandleSupport()
        delegate.summary = summary
        capability.bindDelegate(delegate)
        capability.attachScanHandles(store) { library }
    }

    @After
    fun tearDown() {
        capability.unbindDelegate()
        capability.resetScanHandleSupport()
    }

    private suspend fun execute(): AgentAction =
        capability.execute(AgentCommand.GetGallerySummary(includeDetails = false), context, null).getOrThrow()

    private suspend fun entriesOf(reply: AgentAction.TextReply): List<ScanPhotoEntry> {
        val handleId = Regex("handle: scan/([0-9a-f]{12})").find(reply.message)!!.groupValues[1]
        return Json.decodeFromString(store.payloadOf(OpenUiContract.DOMAIN_SCAN, handleId)!!)
    }

    private fun expectedPlainText(): String = formatGallerySummaryForReply(summary)

    @Test
    fun `summary receipt appends full-library scan handle`() = runTest {
        val reply = execute() as AgentAction.TextReply
        assertTrue("摘要回执应携带全库数据句柄：${reply.message}", reply.message.contains("（数据句柄 handle: scan/"))
        val entries = entriesOf(reply)
        assertEquals(listOf(11L, 22L), entries.map { entry -> entry.id })
        assertEquals(listOf("PHOTO", "VIDEO"), entries.map { entry -> entry.mediaType })
    }

    @Test
    fun `summary text prefix stays intact before suffix`() = runTest {
        val reply = execute() as AgentAction.TextReply
        assertTrue(reply.message.startsWith(expectedPlainText() + "（数据句柄 handle: scan/"))
    }

    @Test
    fun `all media lookup failure degrades to plain summary text`() = runTest {
        capability.attachScanHandles(store) { error("allMedia unavailable") }
        val reply = execute() as AgentAction.TextReply
        assertEquals(expectedPlainText(), reply.message)
    }

    @Test
    fun `empty library keeps plain text without handle`() = runTest {
        capability.attachScanHandles(store) { emptyList() }
        val reply = execute() as AgentAction.TextReply
        assertEquals(expectedPlainText(), reply.message)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `handle registration failure degrades to plain summary text`() = runTest {
        val throwingStore = LocalHandleStore(object : com.mamba.picme.data.local.LocalHandleDao {
            override suspend fun upsert(handle: com.mamba.picme.data.local.LocalHandleEntity) {
                error("disk full")
            }
            override suspend fun byId(handleId: String): com.mamba.picme.data.local.LocalHandleEntity? = null
            override suspend fun deleteOlderThan(beforeMs: Long): Int = 0
        })
        capability.attachScanHandles(throwingStore) { library }
        val reply = execute() as AgentAction.TextReply
        assertEquals(expectedPlainText(), reply.message)
    }

    @Test
    fun `unavailable hint stays plain when library empty`() = runTest {
        delegate.summary = null
        capability.attachScanHandles(store) { emptyList() }
        val reply = execute() as AgentAction.TextReply
        assertFalse(reply.message.contains("数据句柄"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `unavailable hint carries handle when library non-empty (cloud parity)`() = runTest {
        delegate.summary = null
        val reply = execute() as AgentAction.TextReply
        // 云端同口径（CloudAgentGalleryTools.getGallerySummary）：summary null 落引导文案，
        // 全库媒体非空时仍附句柄——OpenUI 卡有合法绑定源优先于文案语义
        assertTrue(reply.message.contains(GALLERY_SUMMARY_UNAVAILABLE_HINT))
        assertTrue(reply.message.contains("（数据句柄 handle: scan/"))
    }

    @Test
    fun `without attached store stays plain text`() = runTest {
        capability.resetScanHandleSupport()
        val reply = execute() as AgentAction.TextReply
        assertEquals(expectedPlainText(), reply.message)
    }

    private class StubDelegate : ChatGallerySummaryCapability.Delegate {
        var summary: GallerySummary? = null

        override suspend fun onGetGallerySummary(includeDetails: Boolean): GallerySummary? = summary
    }
}
