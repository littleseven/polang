package com.mamba.picme.features.chat

import com.mamba.picme.R
import com.mamba.picme.agent.core.inference.remote.ChatStreamEvent
import com.mamba.picme.agent.core.inference.remote.StreamChatResult
import com.mamba.picme.data.local.ChatSessionEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ChatViewModel 流式接线回归：TextSnapshot 经节奏器、streamChat 返回后 finish，
 * 最终 streamingMessage 被清除、不崩。节奏细节由 StreamingPacingControllerTest 覆盖。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelStreamingWiringTest : ChatViewModelTestBase() {

    @Before
    override fun setUp() {
        super.setUp()
        every { context.getString(R.string.new_chat) } returns "New Chat"
        every { context.getString(R.string.chat_title_image_first) } returns "Image Chat"
        every { context.getString(R.string.chat_calling_tool) } returns "正在调用工具"
    }

    @Test
    fun `text snapshot is paced and streaming message clears on finish`() = runTest {
        coEvery { chatSessionDao.getSession("default") } returns
            ChatSessionEntity(sessionId = "default", title = "New Chat")
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        val onEventSlot = slot<(ChatStreamEvent) -> Unit>()
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), capture(onEventSlot)) } answers {
            onEventSlot.captured(ChatStreamEvent.TextSnapshot("你好世界"))
            Result.success(StreamChatResult(fullResponse = "你好世界"))
        }

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("测试")
        advanceUntilIdle()

        assertNull(vm.streamingMessage.value)
    }

    @Test
    fun `throttled engineer tasks emits current value without waiting for sample tick`() = runTest {
        // M2 审查回归钉桩：displayMessages 的第三路源是 sample(500) 节流流，裸 sample 首帧要等
        // 首个周期 tick，combine 三源不齐导致首进 chat 页列表空白 ~500ms；onStart 首值直通后
        // 不推进虚拟时间即可取到首值（若被 sample 拖住，100ms 虚拟超时返回 null）。
        val vm = newViewModel()
        val first = withTimeoutOrNull(100) { vm.throttledEngineerTasks.first() }
        assertNotNull(first)
        assertTrue(first!!.isEmpty())
    }
}
