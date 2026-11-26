package com.mamba.picme.features.chat

import com.mamba.picme.R
import com.mamba.picme.agent.core.inference.remote.ChatStreamEvent
import com.mamba.picme.agent.core.inference.remote.StreamChatResult
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.domain.chat.MessagePart
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 本地回合 OpenUI 流式预览接线（Task 8 / spec《本地回合 OpenUI 统一》U2-5）：
 * 开关 on 且 TextSnapshot 命中程序前缀（[com.mamba.picme.domain.chat.openui.OpenUiProgramSummary]
 * 的 `looksLikeProgramPrefix`）→ 快照改道 `_openUiStreamProgram` 预览轨（渲染层既有
 * OPENUI_STREAM_PREVIEW_ID 合成管线零改动），不喂 pacingController/turnAdapter
 * （本轮无文本气泡）；轮内 latch 防抖动；RoundStarted / ToolCallStarted 复位清空；
 * 回合收尾（onSuccess / onFailure / CancellationException）清预览；开关 off 快照
 * 走原路径逐字节不变。
 *
 * 观测手法：ChatStreamEvent 的 StateFlow 副作用同步置值——事件回放桩在每个事件
 * 分发返回后即刻采样（[stubStreamRound] 的 afterEvent），不依赖 pacing 虚拟时间。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelOpenUiStreamPreviewTest : ChatViewModelTestBase() {

    private val program = """
        // summary: 已为你整理好照片
        root = Markdown("这是回复正文")
    """.trimIndent()

    private val registry: CapabilityRegistry = mockk(relaxed = true)

    private companion object {
        /** 取消口径测试的虚拟时间推进量：足以让节奏器追平短文本缓冲（BASE_CHAR_MS=50/字 + 标点停顿）。 */
        const val PACING_ADVANCE_MS = 10_000L
    }

    @Before
    override fun setUp() {
        super.setUp()
        every { context.getString(R.string.new_chat) } returns "New Chat"
        every { context.getString(R.string.chat_title_image_first) } returns "Image Chat"
        every { context.getString(R.string.chat_calling_tool) } returns "正在调用工具"
        every { context.getString(R.string.chat_thinking) } returns "思考中"
        every { orchestrator.getCapabilityRegistry() } returns registry
    }

    private fun stubToggle(enabled: Boolean) {
        every { userSettingsRepository.localTurnOpenUiEnabledFlow } returns flowOf(enabled)
    }

    /**
     * 事件回放桩：按序发射事件后返回指定终稿；afterEvent 在每个事件同步分发完成后
     * 即刻回调采样中流态（预览轨 StateFlow 同步置值）。yield 对齐生产主线程 FIFO
     * 时序（T7 先例：feedTurn 漏斗先出队，fold 读取时 parts 已装配）。
     */
    private fun stubStreamRound(
        events: List<ChatStreamEvent>,
        result: Result<StreamChatResult> = Result.success(StreamChatResult(fullResponse = program)),
        afterEvent: (ChatStreamEvent) -> Unit = {},
    ) {
        coEvery { chatSessionDao.getSession("default") } returns
            ChatSessionEntity(sessionId = "default", title = "New Chat")
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        val onEventSlot = slot<(ChatStreamEvent) -> Unit>()
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), capture(onEventSlot)) } coAnswers {
            events.forEach { event ->
                onEventSlot.captured(event)
                afterEvent(event)
            }
            yield()
            result
        }
    }

    @Test
    fun `program prefix snapshot routes to OpenUI preview and skips text bubble`() = runTest {
        stubToggle(true)
        var previewAtSnapshot: String? = null
        var thinkingAtSnapshot: Boolean? = null
        var contentAtSnapshot: String? = null
        val vm = newViewModel()
        advanceUntilIdle()
        stubStreamRound(listOf(ChatStreamEvent.TextSnapshot(program))) {
            previewAtSnapshot = vm.openUiStreamProgram.value
            thinkingAtSnapshot = vm.streamingMessage.value?.isThinking
            contentAtSnapshot = vm.streamingMessage.value?.content
        }
        vm.sendMessage("帮我看看照片")
        advanceUntilIdle()

        // 快照命中程序前缀 → 预览轨置值；文本气泡轨不动（仍思考态）
        assertEquals(program, previewAtSnapshot)
        assertEquals(true, thinkingAtSnapshot)
        assertEquals("思考中", contentAtSnapshot)
        // 回合收尾（onSuccess 点位）清预览 + 清流式占位
        assertNull(vm.openUiStreamProgram.value)
        assertNull(vm.streamingMessage.value)
    }

    @Test
    fun `in-round latch keeps subsequent snapshots on preview path`() = runTest {
        stubToggle(true)
        val followUp = "后续普通文本（无程序前缀）"
        var previewAfterFollowUp: String? = null
        var thinkingAfterFollowUp: Boolean? = null
        val vm = newViewModel()
        advanceUntilIdle()
        var sawPrefixSnapshot = false
        stubStreamRound(
            events = listOf(
                ChatStreamEvent.TextSnapshot(program),
                ChatStreamEvent.TextSnapshot(followUp),
            ),
            result = Result.success(StreamChatResult(fullResponse = followUp)),
        ) { event ->
            if (event is ChatStreamEvent.TextSnapshot && !sawPrefixSnapshot) {
                sawPrefixSnapshot = true
            } else if (event is ChatStreamEvent.TextSnapshot) {
                previewAfterFollowUp = vm.openUiStreamProgram.value
                thinkingAfterFollowUp = vm.streamingMessage.value?.isThinking
            }
        }
        vm.sendMessage("继续说")
        advanceUntilIdle()

        // 命中后本轮后续快照同路（latch 防抖动来回切）：非前缀快照仍走预览轨
        assertEquals(followUp, previewAfterFollowUp)
        assertEquals(true, thinkingAfterFollowUp)
        assertNull(vm.openUiStreamProgram.value)
    }

    @Test
    fun `RoundStarted clears preview and resets latch for new round`() = runTest {
        stubToggle(true)
        val plainFollowUp = "你好，这是新一轮普通文本"
        var previewAfterRoundStart: String? = "unset"
        var previewAfterFollowUp: String? = "unset"
        var thinkingAfterFollowUp: Boolean? = null
        val vm = newViewModel()
        advanceUntilIdle()
        stubStreamRound(
            events = listOf(
                ChatStreamEvent.TextSnapshot(program),
                ChatStreamEvent.RoundStarted,
                ChatStreamEvent.TextSnapshot(plainFollowUp),
            ),
            result = Result.success(StreamChatResult(fullResponse = plainFollowUp)),
        ) { event ->
            if (event is ChatStreamEvent.RoundStarted) {
                previewAfterRoundStart = vm.openUiStreamProgram.value
            }
            if (event is ChatStreamEvent.TextSnapshot && event.text == plainFollowUp) {
                previewAfterFollowUp = vm.openUiStreamProgram.value
                thinkingAfterFollowUp = vm.streamingMessage.value?.isThinking
            }
        }
        vm.sendMessage("再来一轮")
        advanceUntilIdle()

        // 轮边界即清预览（对齐云端 onRoundStart）+ latch 复位：非前缀快照回原路径
        assertNull(previewAfterRoundStart)
        assertNull(previewAfterFollowUp)
        assertEquals(false, thinkingAfterFollowUp)
        assertNull(vm.openUiStreamProgram.value)
    }

    @Test
    fun `ToolCallStarted interrupts program round and clears preview`() = runTest {
        stubToggle(true)
        val plainFollowUp = "工具结果后的普通回复"
        var previewAfterTool: String? = "unset"
        var contentAfterTool: String? = null
        var previewAfterFollowUp: String? = "unset"
        val vm = newViewModel()
        advanceUntilIdle()
        stubStreamRound(
            events = listOf(
                ChatStreamEvent.TextSnapshot(program),
                ChatStreamEvent.ToolCallStarted(toolName = "search_media"),
                ChatStreamEvent.TextSnapshot(plainFollowUp),
            ),
            result = Result.success(StreamChatResult(fullResponse = plainFollowUp)),
        ) { event ->
            when (event) {
                is ChatStreamEvent.ToolCallStarted -> {
                    previewAfterTool = vm.openUiStreamProgram.value
                    contentAfterTool = vm.streamingMessage.value?.content
                }
                is ChatStreamEvent.TextSnapshot -> if (event.text == plainFollowUp) {
                    previewAfterFollowUp = vm.openUiStreamProgram.value
                }
                else -> Unit
            }
        }
        vm.sendMessage("查一下照片")
        advanceUntilIdle()

        // 工具轮打断程序轮：预览清空 + 气泡切工具状态文案；latch 复位
        assertNull(previewAfterTool)
        assertEquals("正在调用工具", contentAfterTool)
        assertNull(previewAfterFollowUp)
        assertNull(vm.openUiStreamProgram.value)
    }

    /**
     * T8 评审 Important（U2-6 顺车修）：工具轮后的程序快照命中预览轨时，非卡片工具
     * chip（`_pendingNonCardTool`）须让位——原路径文本快照有该语义（新一轮文本到达
     * 覆盖工具状态文案），预览轨漏了会让 search_media 的工具 chip 与占位气泡文案
     * 悬挂至回合收尾。
     */
    @Test
    fun `program snapshot after tool round yields pending non-card tool`() = runTest {
        stubToggle(true)
        var pendingAfterProgramSnapshot: String? = "unset"
        val vm = newViewModel()
        advanceUntilIdle()
        stubStreamRound(
            events = listOf(
                ChatStreamEvent.ToolCallStarted(toolName = "search_media"),
                ChatStreamEvent.TextSnapshot(program),
            ),
        ) { event ->
            if (event is ChatStreamEvent.TextSnapshot) {
                pendingAfterProgramSnapshot = vm.pendingNonCardTool.value
            }
        }
        vm.sendMessage("查一下照片")
        advanceUntilIdle()

        // 程序快照命中即让位：chip 不悬挂至回合收尾（finally 才清）
        assertNull(pendingAfterProgramSnapshot)
        assertNull(vm.pendingNonCardTool.value)
    }

    @Test
    fun `non-prefix snapshot keeps original path when toggle on`() = runTest {
        stubToggle(true)
        val plain = "你好，这是一段普通回复"
        var previewAtSnapshot: String? = "unset"
        var thinkingAtSnapshot: Boolean? = null
        val vm = newViewModel()
        advanceUntilIdle()
        stubStreamRound(
            events = listOf(ChatStreamEvent.TextSnapshot(plain)),
            result = Result.success(StreamChatResult(fullResponse = plain)),
        ) {
            previewAtSnapshot = vm.openUiStreamProgram.value
            thinkingAtSnapshot = vm.streamingMessage.value?.isThinking
        }
        vm.sendMessage("打个招呼")
        advanceUntilIdle()

        assertNull(previewAtSnapshot)
        assertEquals(false, thinkingAtSnapshot)
        assertNull(vm.openUiStreamProgram.value)
    }

    @Test
    fun `program snapshot keeps original path when toggle off`() = runTest {
        stubToggle(false)
        var previewAtSnapshot: String? = "unset"
        var thinkingAtSnapshot: Boolean? = null
        val vm = newViewModel()
        advanceUntilIdle()
        val inserted = mutableListOf<ChatMessageEntity>()
        coEvery { chatMessageDao.insertMessage(capture(inserted)) } answers { }
        stubStreamRound(listOf(ChatStreamEvent.TextSnapshot(program))) {
            previewAtSnapshot = vm.openUiStreamProgram.value
            thinkingAtSnapshot = vm.streamingMessage.value?.isThinking
        }
        vm.sendMessage("帮我看看照片")
        advanceUntilIdle()

        // 回滚位：快照走原路径（预览轨不启用、思考态按原逻辑解除）
        assertNull(previewAtSnapshot)
        assertEquals(false, thinkingAtSnapshot)
        assertNull(vm.openUiStreamProgram.value)
        val parts = inserted.single { it.role == "agent" }.decodeParts()
        assertTrue(parts.none { it is MessagePart.OpenUiCard })
        assertEquals(program, parts.filterIsInstance<MessagePart.Text>().single().markdown)
    }

    @Test
    fun `turn failure clears preview`() = runTest {
        stubToggle(true)
        val vm = newViewModel()
        advanceUntilIdle()
        stubStreamRound(
            events = listOf(ChatStreamEvent.TextSnapshot(program)),
            result = Result.failure(IllegalStateException("boom")),
        )
        vm.sendMessage("帮我看看照片")
        advanceUntilIdle()

        assertNull(vm.openUiStreamProgram.value)
    }

    @Test
    fun `cancellation clears preview without persisting assistant message`() = runTest {
        stubToggle(true)
        var previewAtCancel: String? = null
        val vm = newViewModel()
        advanceUntilIdle()
        val inserted = mutableListOf<ChatMessageEntity>()
        coEvery { chatMessageDao.insertMessage(capture(inserted)) } answers { }
        coEvery { chatSessionDao.getSession("default") } returns
            ChatSessionEntity(sessionId = "default", title = "New Chat")
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        val onEventSlot = slot<(ChatStreamEvent) -> Unit>()
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), capture(onEventSlot)) } coAnswers {
            onEventSlot.captured(ChatStreamEvent.TextSnapshot(program))
            previewAtCancel = vm.openUiStreamProgram.value
            throw CancellationException("user stop")
        }
        vm.sendMessage("帮我看看照片")
        advanceUntilIdle()

        // 流中预览在场（前缀命中）；取消分支清预览
        assertEquals(program, previewAtCancel)
        assertNull(vm.openUiStreamProgram.value)
        assertNull(vm.streamingMessage.value)
        // 预览轨无气泡正文（纯程序轮占位停在思考态）→ keepPartial 不成立，半截不落库
        assertTrue(inserted.none { it.role == "agent" })
    }

    /**
     * T9（U2-6）：用户停止半截口径——停止时程序预览在场（本轮已改道预览轨）→
     * 半截程序不是合法产出，整体丢弃不落库；预览为空且文本半截非空 → 保留现行落库。
     *
     * 前提态构造：文本轨须先吐出真实内容（showCursor=true）现行 keepPartial 才成立——
     * 节奏器吐字依赖 delay 虚拟时间，Main 须重挂到 runTest 的 testScheduler（TestBase
     * 独立 UnconfinedTestDispatcher 的私有调度器不随 advanceTimeBy 推进，光标态进不了
     * 测试视野）。轮内普通文本先到、程序中段起笔后快照改道预览轨——文本轨与预览轨
     * 同时在场的混合轮，即现行实现唯一会落库半截的形态。
     *
     * 门闩手法：streamChat 回放桩吐出首个文本快照后挂起在 gate 上让位测试体——测试体
     * 在非重入位置推进虚拟时间令节奏器吐字，再 complete 放行 tail 与用户停止（调度器
     * 任务运行中重入 advanceTimeBy 推不动已挂起的节奏循环）。
     */
    private fun TestScope.stubPacedStopRound(
        inserted: MutableList<ChatMessageEntity>,
        pacedText: String,
        tail: (dispatch: (ChatStreamEvent) -> Unit) -> Unit = {},
    ): CompletableDeferred<Unit> {
        coEvery { chatSessionDao.getSession("default") } returns
            ChatSessionEntity(sessionId = "default", title = "New Chat")
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        coEvery { chatMessageDao.insertMessage(capture(inserted)) } answers { }
        val onEventSlot = slot<(ChatStreamEvent) -> Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), capture(onEventSlot)) } coAnswers {
            onEventSlot.captured(ChatStreamEvent.TextSnapshot(pacedText))
            gate.await()
            tail(onEventSlot.captured)
            throw CancellationException("user stop")
        }
        return gate
    }

    @Test
    fun `user stop discards partial program when preview is present`() = runTest {
        // 虚拟时间推进前提：Main 重挂 runTest 调度器（见 stubPacedStopRound KDoc）
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        stubToggle(true)
        val prose = "你好，先说结论"
        val proseThenProgram = prose + "\n// summary: 半截程序\nroot = Markdown(\"回复还没写完"
        val vm = newViewModel()
        advanceUntilIdle()
        val inserted = mutableListOf<ChatMessageEntity>()
        val gate = stubPacedStopRound(inserted, pacedText = prose) { dispatch ->
            dispatch(ChatStreamEvent.TextSnapshot(proseThenProgram))
        }
        vm.sendMessage("帮我整理照片")
        // 推进虚拟时间：节奏器吐出已缓冲文本（showCursor=true，keepPartial 成立前提）
        testScheduler.advanceTimeBy(PACING_ADVANCE_MS)
        val cursorAfterPacing = vm.streamingMessage.value?.showCursor
        // 放行：程序快照改道预览轨（预览在场）→ 抛用户停止
        gate.complete(Unit)
        advanceUntilIdle()

        // 前置自证：文本轨确已吐字（光标可见）——排除「预览在场但 keepPartial 本就不成立」的空真
        assertEquals(true, cursorAfterPacing)
        // 预览在场：半截非合法产出 → 不落库任何 agent 消息；预览与占位气泡均清空
        assertNull(vm.openUiStreamProgram.value)
        assertNull(vm.streamingMessage.value)
        assertTrue(inserted.none { it.role == "agent" })
    }

    @Test
    fun `user stop keeps partial text persisting when preview is empty`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        // 开关 on 但全程普通文本（预览从未置值）：现行半截落库口径不受丢弃分支影响
        stubToggle(true)
        val prose = "你好，这是半截文本"
        val vm = newViewModel()
        advanceUntilIdle()
        val inserted = mutableListOf<ChatMessageEntity>()
        val gate = stubPacedStopRound(inserted, pacedText = prose)
        vm.sendMessage("帮我整理照片")
        testScheduler.advanceTimeBy(PACING_ADVANCE_MS)
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(vm.openUiStreamProgram.value)
        assertEquals(prose, inserted.single { it.role == "agent" }.content)
    }

    /**
     * Task 13 节流接线：程序快照连续增长帧的预览写入被压缩——镜像 server
     * OpenUiContentStreamTracker 48 字符/80ms 粒度（首帧直通保改道可观测性）。
     * 增量条件（≥48 字符/发射档）是确定性上界：100 帧 × 每帧 +2 字符下，
     * 即使间隔条件全放行，发射档数 ≤ 首帧 1 + floor(200/48) = 5。
     */
    @Test
    fun `rapid program frames are throttled into few preview writes`() = runTest {
        stubToggle(true)
        val longProgram = program + "\n" + "// " + "x".repeat(200)
        val frames = (1..100).map { index ->
            // 从完整前缀形态起（首帧即命中改道判定），每帧 +2 字符单调增长
            longProgram.substring(0, program.length + index * 2)
        }
        val distinctPreviews = mutableSetOf<String>()
        val vm = newViewModel()
        advanceUntilIdle()
        stubStreamRound(events = frames.map { text -> ChatStreamEvent.TextSnapshot(text) }) {
            vm.openUiStreamProgram.value?.let { preview -> distinctPreviews.add(preview) }
        }
        vm.sendMessage("帮我整理照片")
        advanceUntilIdle()

        // 写入被压缩：远小于帧数（增量档确定性上界 5）；每个观测值都是某一帧的文本
        assertTrue("expected at least first-frame emission", distinctPreviews.isNotEmpty())
        assertTrue(
            "expected <= 5 distinct preview values, got ${distinctPreviews.size}",
            distinctPreviews.size <= 5,
        )
        assertTrue(distinctPreviews.all { preview -> frames.contains(preview) })
        // 回合收尾清预览 + 落卡照常（节流不破 T8 收尾语义）
        assertNull(vm.openUiStreamProgram.value)
    }

    /**
     * Task 13 节流 + 保尾帧：节流使流中预览滞后于真实流进度（采样点预览 ≠ 最新帧），
     * 用户停止时收尾 flushTail 保证判定读到「预览在场」的忠实信号 → 半截丢弃不落库
     * （T9 keepPartial 语义不被节流破坏）；预览与占位气泡照常清空。
     */
    @Test
    fun `user stop during throttled program stream discards partial`() = runTest {
        stubToggle(true)
        val longProgram = program + "\n" + "// " + "x".repeat(200)
        val frames = (1..100).map { index ->
            longProgram.substring(0, program.length + index * 2)
        }
        // 采样点取非发射档帧（program.length + 120 ≈ 第 60 帧，非 48 字符档位）：
        // 预览值若等于该帧文本说明节流失效（未接线或直通）
        val sampleIndex = 59
        val sampleFrame = frames[sampleIndex]
        var previewAtSample: String? = null
        var sawProgramPreview = false
        val vm = newViewModel()
        advanceUntilIdle()
        val inserted = mutableListOf<ChatMessageEntity>()
        coEvery { chatMessageDao.insertMessage(capture(inserted)) } answers { }
        coEvery { chatSessionDao.getSession("default") } returns
            ChatSessionEntity(sessionId = "default", title = "New Chat")
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        val onEventSlot = slot<(ChatStreamEvent) -> Unit>()
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), capture(onEventSlot)) } coAnswers {
            frames.forEachIndexed { index, event ->
                onEventSlot.captured(ChatStreamEvent.TextSnapshot(event))
                if (vm.openUiStreamProgram.value != null) sawProgramPreview = true
                if (index == sampleIndex) previewAtSample = vm.openUiStreamProgram.value
            }
            throw CancellationException("user stop")
        }
        vm.sendMessage("帮我整理照片")
        advanceUntilIdle()

        // 节流在场：程序预览出现过，且采样点预览滞后于最新帧文本（节流压制了中间帧）
        assertTrue("expected program preview to appear during stream", sawProgramPreview)
        assertTrue("expected throttled (lagging) preview at sample frame", previewAtSample != sampleFrame)
        // 取消收尾：预览在场 → 半截丢弃；预览清空；T9 语义保持
        assertTrue(inserted.none { it.role == "agent" })
        assertNull(vm.openUiStreamProgram.value)
        assertNull(vm.streamingMessage.value)
    }

    /**
     * Task 13 收尾补丁（评审 Important #2）：generic `catch (Exception)` 收尾路径——
     * 异常自 streamChat 直接冒出（未经 Result 包装的未预期路径）时预览在场，
     * 第四收尾点同样清预览 + 复位节流器（闭合四收尾点对称性，收掉 T8 遗留的
     * 预览残留窗口）。流中程序预览在场 → 异常收尾后预览与占位气泡均清空，
     * error 文案照常落库。
     */
    @Test
    fun `generic exception path clears preview when engine throws`() = runTest {
        stubToggle(true)
        val vm = newViewModel()
        advanceUntilIdle()
        val inserted = mutableListOf<ChatMessageEntity>()
        coEvery { chatMessageDao.insertMessage(capture(inserted)) } answers { }
        var previewDuringStream: String? = null
        val onEventSlot = slot<(ChatStreamEvent) -> Unit>()
        coEvery { chatSessionDao.getSession("default") } returns
            ChatSessionEntity(sessionId = "default", title = "New Chat")
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), capture(onEventSlot)) } coAnswers {
            onEventSlot.captured(ChatStreamEvent.TextSnapshot(program))
            previewDuringStream = vm.openUiStreamProgram.value
            error("engine boom")
        }
        vm.sendMessage("帮我看看照片")
        advanceUntilIdle()

        // 前置自证：流中程序预览在场（首帧直通）——排除「预览从未置值」的空真
        assertEquals(program, previewDuringStream)
        // generic catch 收尾：预览与占位气泡清空（第四收尾点闭合）
        assertNull(vm.openUiStreamProgram.value)
        assertNull(vm.streamingMessage.value)
        // error 文案照常落库（catch 分支的兜底气泡，role=agent + modelUsed=error）
        assertEquals(1, inserted.count { it.role == "agent" && it.modelUsed == "error" })
    }

    /**
     * 顺车钉 T7 落卡主产线（调用点①）：带 commands 的回合经 capability dispatch 返回
     * TextReply 程序文本 → `handleAgentAction` TextReply 分支统一嗅探落 OpenUiCard。
     */
    @Test
    fun `commands turn lands OpenUiCard via TextReply branch`() = runTest {
        stubToggle(true)
        coEvery { registry.dispatch(any(), any()) } returns Result.success(
            AgentAction.TextReply(commandId = 1, message = program)
        )
        val vm = newViewModel()
        advanceUntilIdle()
        val inserted = mutableListOf<ChatMessageEntity>()
        coEvery { chatMessageDao.insertMessage(capture(inserted)) } answers { }
        stubStreamRound(
            events = emptyList(),
            result = Result.success(
                StreamChatResult(
                    fullResponse = program,
                    commands = listOf(AgentCommand.SearchMedia(query = "猫")),
                )
            ),
        )
        vm.sendMessage("看看猫的照片")
        advanceUntilIdle()

        coVerify { registry.dispatch(any(), any()) }
        val parts = inserted.single { it.role == "agent" }.decodeParts()
        val card = parts.filterIsInstance<MessagePart.OpenUiCard>().single()
        assertEquals(program, card.program)
        assertEquals("p0", card.partId)
    }
}
