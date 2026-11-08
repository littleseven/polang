package com.mamba.picme.agent.core

import com.mamba.picme.agent.IosAgentComposition
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.ChatAgentBridge
import com.mamba.picme.agent.core.inference.remote.ChatUiActionDto
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.skill.IosSkillCacheBridge
import com.mamba.picme.agent.core.skill.IosSkillTransportBridge
import com.mamba.picme.data.IosMediaItem
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.domain.repository.AccessState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeBridge : IosMediaRepositoryBridge {
    override fun currentAccessState(): AccessState = AccessState.Full
    override fun fetchAllMedia(): List<IosMediaItem> = listOf(
        IosMediaItem("C-1", "PHOTO", 1000L, fileName = "cat.jpg")
    )
    override fun requestReadWriteAuthorization() = Unit
    override fun addChangeListener(listener: () -> Unit) = Unit
    override fun removeChangeListener() = Unit
    override fun deleteMedia(localIdentifiers: List<String>): Boolean = true
    override fun setFavorite(localIdentifier: String, favorite: Boolean): Boolean = true
}

/** skill 传输 fake：同步回调（Swift 桥的异步语义在适配器视角等价——resume 恰一次即可）。 */
private class FakeSkillTransportBridge : IosSkillTransportBridge {
    override fun fetch(path: String, callback: (String?) -> Unit) {
        callback("""{"skills":[{"name":"html-card","description":"HTML 卡","version":"abc123"}]}""")
    }
}

/** skill 缓存 fake：内存读写，语义同 Documents JSON 文件桥。 */
private class FakeSkillCacheBridge : IosSkillCacheBridge {
    var stored: String? = null
    override fun read(): String? = stored
    override fun write(snapshotJson: String): Boolean {
        stored = snapshotJson
        return true
    }
}

/**
 * T5 组合根/桥测试。注意 [IosAgentComposition] 为进程级幂等单例（AtomicBoolean 守卫），
 * 同一测试进程内只有首个 initialize 真正接线，后续调用跳过——各用例共享首个接线结果。
 */
class IosAgentCompositionTest {

    private fun ensureInitialized() {
        // 双桥恒传（2026-11-08 端侧 load_skill 接线）：首个 initialize 即接线路径——
        // skillRepository 装配 + LoadSkillCapability 注册；进程级单例，后续用例共享
        IosAgentComposition.initialize(
            FakeBridge(),
            deviceId = "test-device-id",
            skillTransportBridge = FakeSkillTransportBridge(),
            skillCacheBridge = FakeSkillCacheBridge(),
        )
    }

    @Test
    fun initializeWiresOrchestratorBridgeAndCapability() {
        ensureInitialized()
        assertNotNull(AgentOrchestrator.getInstance().remoteChatEngine, "orchestrator 应可达且 remoteChatEngine 已装配")
        assertNotNull(IosAgentComposition.chatBridge, "initialize 后 chatBridge 应就绪")

        val capability = CapabilityRegistry.getInstance().get("chat_gallery")
        assertNotNull(capability, "chat_gallery capability 应已注册")
        assertTrue(
            capability.supportedCommands().containsAll(
                listOf("get_gallery_summary", "search_media", "refine_media_search", "delete_media")
            ),
            "应覆盖 chat 相册命令"
        )
        assertTrue(capability.supportsCommand(AgentCommand.GetGallerySummary()))
    }

    @Test
    fun initializeWiresLoadSkillCapability() {
        ensureInitialized()
        // 双桥接线路径（2026-11-08）：skillRepository 装配 → LoadSkillCapability 注册，
        // load_skill dispatch 可达（未接线时 get 恒 null——降级路径由 initializeIsIdempotent
        // 之前的默认参数语义保证）
        val capability = CapabilityRegistry.getInstance().get("load_skill")
        assertNotNull(capability, "双桥注入后 load_skill capability 应已注册")
        assertTrue(
            capability.supportedCommands().contains("load_skill"),
            "load_skill capability 应声明支持 load_skill 命令",
        )
    }

    @Test
    fun initializeIsIdempotent() {
        ensureInitialized()
        val bridgeBefore = IosAgentComposition.chatBridge
        IosAgentComposition.initialize(FakeBridge(), deviceId = "another-device")
        assertTrue(bridgeBefore === IosAgentComposition.chatBridge, "重复 initialize 应跳过，chatBridge 不变")
    }

    @Test
    fun clearChatMemoryWorksWithNoopCleaner() = runTest {
        ensureInitialized()
        AgentOrchestrator.getInstance().clearChatMemory(ChatAgentBridge.DEFAULT_SESSION_ID) // 不抛即过
    }

    @Test
    fun bridgeIsNotRunningWhenIdle() {
        ensureInitialized()
        assertFalse(ChatAgentBridge(AgentOrchestrator.getInstance()).isRunning())
    }

    @Test
    fun watchUiActionsMapsTextReplyAndMediaResults() = runBlocking {
        ensureInitialized()
        val bridge = ChatAgentBridge(AgentOrchestrator.getInstance())
        val received = mutableListOf<ChatUiActionDto>()
        val watcher = bridge.watchUiActions { received += it }
        try {
            // MutableSharedFlow replay=0：订阅者未就绪前的 emit 会丢，先等订阅建立再发
            val uiActions = ChatToolService.getInstance().uiActions
            withTimeout(3000) { while (uiActions.subscriptionCount.value == 0) delay(50) }
            uiActions.emit(AgentAction.TextReply(1, "你好"))
            uiActions.emit(
                AgentAction.MediaResults(2, query = "cat", mediaIds = listOf(11L, 22L), totalCount = 2, isRefinement = false)
            )
            withTimeout(3000) { while (received.size < 2) delay(50) }

            val textReply = received[0]
            assertEquals("text_reply", textReply.kind)
            assertEquals("你好", textReply.message)

            val mediaResults = received[1]
            assertEquals("media_results", mediaResults.kind)
            assertEquals("cat", mediaResults.query)
            assertEquals(2L, mediaResults.totalCount)
            assertEquals(listOf(11L, 22L), mediaResults.mediaIds)
        } finally {
            watcher.cancel()
        }
    }
}
