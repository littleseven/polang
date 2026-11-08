package com.mamba.picme.agent.core

import com.mamba.picme.agent.IosAgentComposition
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.ChatAgentBridge
import com.mamba.picme.agent.core.inference.remote.ChatUiActionDto
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
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

/**
 * T5 组合根/桥测试。注意 [IosAgentComposition] 为进程级幂等单例（AtomicBoolean 守卫），
 * 同一测试进程内只有首个 initialize 真正接线，后续调用跳过——各用例共享首个接线结果。
 */
class IosAgentCompositionTest {

    private fun ensureInitialized() {
        IosAgentComposition.initialize(FakeBridge(), deviceId = "test-device-id")
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
    fun watchUiActionsMapsTextReply() = runBlocking {
        ensureInitialized()
        val bridge = ChatAgentBridge(AgentOrchestrator.getInstance())
        val received = mutableListOf<ChatUiActionDto>()
        val watcher = bridge.watchUiActions { received += it }
        try {
            // MutableSharedFlow replay=0：订阅者未就绪前的 emit 会丢，先等订阅建立再发
            val uiActions = ChatToolService.getInstance().uiActions
            withTimeout(3000) { while (uiActions.subscriptionCount.value == 0) delay(50) }
            uiActions.emit(AgentAction.TextReply(1, "你好"))
            withTimeout(3000) { while (received.isEmpty()) delay(50) }

            val textReply = received[0]
            assertEquals("text_reply", textReply.kind)
            assertEquals("你好", textReply.message)
        } finally {
            watcher.cancel()
        }
    }
}
