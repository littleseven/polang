package com.mamba.picme.features.chat

import com.mamba.picme.R
import com.mamba.picme.agent.core.inference.remote.ChatStreamEvent
import com.mamba.picme.agent.core.inference.remote.StreamChatResult
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.openui.OpenUiContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 本地回合 OpenUI 统一嗅探落卡（Task 7 / spec《本地回合 OpenUI 统一》U2-4）：
 * 开关 on 时本地回合终稿为 OpenUI 程序 → 统一落 `data_genui_program` part
 * （reasoning/source parts 随行在卡前，顺序立法同云端路径）；非程序文本与
 * 开关 off 走原文本 part 路径（回滚位行为逐字节不变）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelLocalTurnOpenUiTest : ChatViewModelTestBase() {

    private val program = """
        // summary: 已为你整理好照片
        root = Markdown("这是回复正文")
    """.trimIndent()

    @Before
    override fun setUp() {
        super.setUp()
        every { context.getString(R.string.new_chat) } returns "New Chat"
        every { context.getString(R.string.chat_title_image_first) } returns "Image Chat"
        every { context.getString(R.string.chat_calling_tool) } returns "正在调用工具"
    }

    private fun stubToggle(enabled: Boolean) {
        every { userSettingsRepository.localTurnOpenUiEnabledFlow } returns flowOf(enabled)
    }

    private fun stubStreamRound(fullResponse: String, reasoning: String? = null) {
        coEvery { chatSessionDao.getSession("default") } returns
            ChatSessionEntity(sessionId = "default", title = "New Chat")
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        val onEventSlot = slot<(ChatStreamEvent) -> Unit>()
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), capture(onEventSlot)) } coAnswers {
            if (reasoning != null) {
                onEventSlot.captured(ChatStreamEvent.ReasoningSnapshot(reasoning))
            }
            onEventSlot.captured(ChatStreamEvent.TextSnapshot(fullResponse))
            // yield 让 feedTurn 的 Main.immediate 漏斗先出队（对齐生产主线程 FIFO 时序：
            // 事件回调先入队、终稿完成恢复后入队——fold 读取时 reasoning parts 已装配；
            // 同步 answers 不让位时事件被尾调用排队到落库之后，turnReasoning 读空）
            yield()
            Result.success(StreamChatResult(fullResponse = fullResponse))
        }
    }

    private fun persistedAssistant(inserted: MutableList<ChatMessageEntity>): ChatMessageEntity =
        inserted.single { it.role == "agent" }

    @Test
    fun `program reply persists OpenUiCard part with reasoning parts first`() = runTest {
        stubToggle(true)
        stubStreamRound(program, reasoning = "先思考一下")
        val inserted = mutableListOf<ChatMessageEntity>()
        coEvery { chatMessageDao.insertMessage(capture(inserted)) } answers { }

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("帮我看看照片")
        advanceUntilIdle()

        val assistant = persistedAssistant(inserted)
        assertEquals("remote_deepseek", assistant.modelUsed)
        val parts = assistant.decodeParts()
        val card = parts.filterIsInstance<MessagePart.OpenUiCard>().single()
        assertEquals("p0", card.partId)
        assertEquals(program, card.program)
        assertEquals("已为你整理好照片", card.fallbackSummary)
        assertEquals(OpenUiContract.LANG_VERSION, card.langVersion)
        // 排序铁律：reasoning/source 在 OpenUiCard 之前
        assertTrue(parts.filterIsInstance<MessagePart.Reasoning>().isNotEmpty())
        assertTrue(
            parts.indexOfFirst { it is MessagePart.Reasoning } <
                parts.indexOfFirst { it is MessagePart.OpenUiCard }
        )
        coVerify { chatSessionDao.touchSession("default", any()) }
    }

    @Test
    fun `non-program reply keeps text part when toggle on`() = runTest {
        stubToggle(true)
        stubStreamRound("你好世界")
        val inserted = mutableListOf<ChatMessageEntity>()
        coEvery { chatMessageDao.insertMessage(capture(inserted)) } answers { }

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("打个招呼")
        advanceUntilIdle()

        val parts = persistedAssistant(inserted).decodeParts()
        assertTrue(parts.none { it is MessagePart.OpenUiCard })
        val text = parts.filterIsInstance<MessagePart.Text>().single()
        assertEquals("p0", text.partId)
        assertEquals("你好世界", text.markdown)
    }

    @Test
    fun `program reply falls back to text part when toggle off`() = runTest {
        stubToggle(false)
        stubStreamRound(program)
        val inserted = mutableListOf<ChatMessageEntity>()
        coEvery { chatMessageDao.insertMessage(capture(inserted)) } answers { }

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("帮我看看照片")
        advanceUntilIdle()

        val parts = persistedAssistant(inserted).decodeParts()
        assertTrue(parts.none { it is MessagePart.OpenUiCard })
        val text = parts.filterIsInstance<MessagePart.Text>().single()
        assertEquals("p0", text.partId)
        assertEquals(program, text.markdown)
    }
}
