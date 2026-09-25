package com.mamba.picme.features.chat

import com.mamba.picme.R
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.remote.picme.ClaudeChatClient
import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 工程师任务卡接线回归（Task 4 + 审查加固）。
 *
 * 覆盖：
 * - SSE 中途失败 → 任务卡 FAILED terminal 化并落库（markActiveEngineerTaskFailed）
 * - Done(turns, 非截断) → COMPLETED + resultSummary=末段文本首个非空行
 * - 流式进行中 Room 表级 invalidation 重发旧快照 → 合并语义保住 live entry（不被冲回 RUNNING）
 *
 * 基建复用 [ChatViewModelTestBase]；claudeChatClient fake SSE 对齐 [ChatViewModelClaudeSidTest] 先例。
 * 内部态经公开只读流 [ChatViewModel.engineerTasks] 观测。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelEngineerTaskTest : ChatViewModelTestBase() {

    override val initialToken = "pl-test-token"

    private val claudeChatClient: ClaudeChatClient = mockk()
    private val insertedMessages = mutableListOf<ChatMessageEntity>()
    private val roomMessagesFlow = MutableStateFlow<List<ChatMessageEntity>>(emptyList())

    @Before
    override fun setUp() {
        super.setUp()
        coEvery { claudeChatClient.engineerAvailability(any()) } returns Result.success(false)
        coEvery { chatMessageDao.getMessageCount(any()) } returns 0
        coEvery { chatMessageDao.insertMessage(capture(insertedMessages)) } answers { }
        every { chatMessageDao.getMessagesBySession(any()) } returns roomMessagesFlow
    }

    override fun newViewModel(): ChatViewModel = ChatViewModel(
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = chatMessageDao,
            chatSessionDao = chatSessionDao,
            userSettingsRepository = userSettingsRepository,
            mediaSearchEngine = mediaSearchEngine,
            mediaFeedbackRepository = mediaFeedbackRepository,
            mediaRepository = mockk(relaxed = true),
            picMeAuthClient = picMeAuthClient,
            claudeChatClient = claudeChatClient,
            getGallerySummaryUseCase = getGallerySummaryUseCase,
            queryGalleryMediaUseCase = mockk(relaxed = true),
            startTagScanUseCase = StartTagScanUseCase(context),
            personDao = mockk(relaxed = true),
            controlledVocab = ControlledVocab(),
            chatEditStateHolder = ChatEditStateHolder(),
            chatEditProcessor = mockk(relaxed = true),
            chatImageStore = mockk(relaxed = true),
            saveChatEditResultUseCase = mockk(relaxed = true),
        )
    )

    private fun taskCards(): List<ChatMessageEntity> =
        insertedMessages.filter { entity -> entity.type == EngineerTaskState.ROOM_TYPE }

    private fun engineerTasks(vm: ChatViewModel): Map<String, EngineerTaskState> = vm.engineerTasks.value

    @Test
    fun `sse failure marks active task FAILED and persists terminal state`() = runTest {
        coEvery { claudeChatClient.chat(any(), any(), any(), any()) } coAnswers {
            val onEvent = arg<(ClaudeEvent) -> Unit>(3)
            onEvent(ClaudeEvent.Session("aaaa1111bbbb"))
            Result.failure(RuntimeException("boom"))
        }

        val vm = newViewModel()
        vm.sendClaudeMessage("修一下崩溃")
        advanceUntilIdle()

        val cards = taskCards()
        // 提交插卡 + Session 事件 + FAILED terminal，至少 3 次 upsert
        assert(cards.size >= 3) { "expected >= 3 task_card upserts, got ${cards.size}" }
        val terminal = parseEngineerTaskState(cards.last().metadata)
        assertNotNull(terminal)
        assertEquals(EngineerTaskStatus.FAILED, terminal!!.status)
        assertEquals("boom", terminal.errorSummary)
    }

    @Test
    fun `done event completes task with result summary from final text first line`() = runTest {
        coEvery { claudeChatClient.chat(any(), any(), any(), any()) } coAnswers {
            val onEvent = arg<(ClaudeEvent) -> Unit>(3)
            onEvent(ClaudeEvent.Session("aaaa1111bbbb"))
            onEvent(ClaudeEvent.ToolUse("Bash", JSONObject()))
            onEvent(ClaudeEvent.AssistantText("\n修复完成\n详情略"))
            onEvent(ClaudeEvent.Done(turns = 2, truncated = false))
            Result.success("ok")
        }

        val vm = newViewModel()
        vm.sendClaudeMessage("修一下崩溃")
        advanceUntilIdle()

        val terminal = parseEngineerTaskState(taskCards().last().metadata)
        assertNotNull(terminal)
        assertEquals(EngineerTaskStatus.COMPLETED, terminal!!.status)
        assertEquals(2, terminal.turns)
        assertEquals("修复完成", terminal.resultSummary)
    }

    @Test
    fun `room invalidation re-emitting stale snapshot does not clobber live task entry`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { claudeChatClient.chat(any(), any(), any(), any()) } coAnswers {
            val onEvent = arg<(ClaudeEvent) -> Unit>(3)
            onEvent(ClaudeEvent.Session("aaaa1111bbbb"))
            onEvent(ClaudeEvent.ToolUse("Bash", JSONObject()))
            // 挂住模拟流式进行中（内存态已推进到 stage=Bash，Room 快照仍是旧的）
            gate.await()
            onEvent(ClaudeEvent.Done(turns = 1))
            Result.success("ok")
        }

        val vm = newViewModel()
        vm.sendClaudeMessage("修一下崩溃")

        // Room 表级 invalidation 重发旧快照：首行 RUNNING 卡（提交时落库，stage=null）
        val staleRow = taskCards().first()
        roomMessagesFlow.value = listOf(staleRow)

        // 合并语义：live entry（stage=Bash）存活，不被旧快照冲回 RUNNING
        val live = engineerTasks(vm).values.single()
        assertEquals("Bash", live.stage)
        assertEquals(EngineerTaskStatus.RUNNING, live.status)

        gate.complete(Unit)
        advanceUntilIdle()

        // live entry 存活 ⇒ Done 正常推进终态落库
        val terminal = parseEngineerTaskState(taskCards().last().metadata)
        assertEquals(EngineerTaskStatus.COMPLETED, terminal?.status)
    }

    /** 白名单账号进工程师模式：canDeliver=true ⇒ Done 且有 file_change → AWAITING_DELIVER。 */
    private fun TestScope.runRoundToAwaitingDeliver(vm: ChatViewModel) {
        vm.enterClaudeMode()
        vm.sendClaudeMessage("改一下脚本")
        advanceUntilIdle()
    }

    @Test
    fun `deliver success resolves task DELIVERED with branch persisted`() = runTest {
        coEvery { claudeChatClient.engineerAvailability(any()) } returns Result.success(true)
        coEvery { claudeChatClient.chat(any(), any(), any(), any()) } coAnswers {
            val onEvent = arg<(ClaudeEvent) -> Unit>(3)
            onEvent(ClaudeEvent.Session("aaaa1111bbbb"))
            onEvent(ClaudeEvent.FileChange("fix.kt", "edit"))
            onEvent(ClaudeEvent.Done(turns = 1, truncated = false))
            Result.success("ok")
        }
        coEvery { claudeChatClient.deliver(any(), any(), any()) } returns
            Result.success(JSONObject().put("ok", true).put("branch", "fix/x"))

        val vm = newViewModel()
        runRoundToAwaitingDeliver(vm)
        val taskId = vm.engineerTasks.value.keys.single()
        assertEquals(EngineerTaskStatus.AWAITING_DELIVER, vm.engineerTasks.value[taskId]?.status)

        vm.deliverEngineerTask(taskId)
        advanceUntilIdle()

        val terminal = parseEngineerTaskState(taskCards().last().metadata)
        assertEquals(EngineerTaskResolution.DELIVERED, terminal?.resolution)
        assertEquals("fix/x", terminal?.deliverBranch)
        assertEquals(EngineerTaskStatus.COMPLETED, terminal?.status)
    }

    @Test
    fun `deliver failure keeps AWAITING_DELIVER with error on card and is retryable`() = runTest {
        coEvery { claudeChatClient.engineerAvailability(any()) } returns Result.success(true)
        coEvery { claudeChatClient.chat(any(), any(), any(), any()) } coAnswers {
            val onEvent = arg<(ClaudeEvent) -> Unit>(3)
            onEvent(ClaudeEvent.Session("aaaa1111bbbb"))
            onEvent(ClaudeEvent.FileChange("fix.kt", "edit"))
            onEvent(ClaudeEvent.Done(turns = 1, truncated = false))
            Result.success("ok")
        }
        coEvery { claudeChatClient.deliver(any(), any(), any()) } returns
            Result.success(JSONObject().put("ok", false).put("error", "conflict"))
        // relaxed context 的 getString 返回 ""，serde ifBlank 会解析回 null；钉死文案使 errorSummary 可断言
        every { context.getString(R.string.claude_deliver_failed, *anyVararg()) } returns "deliver failed: conflict"

        val vm = newViewModel()
        runRoundToAwaitingDeliver(vm)
        val taskId = vm.engineerTasks.value.keys.single()

        vm.deliverEngineerTask(taskId)
        advanceUntilIdle()

        val failed = parseEngineerTaskState(taskCards().last().metadata)
        // 失败保持待审批态（可重试），错误摘要上卡；不裁决
        assertEquals(EngineerTaskStatus.AWAITING_DELIVER, failed?.status)
        assertEquals("deliver failed: conflict", failed?.errorSummary)
        assertNull(failed?.resolution)

        // 再次交付成功 → DELIVERED
        coEvery { claudeChatClient.deliver(any(), any(), any()) } returns
            Result.success(JSONObject().put("ok", true).put("branch", "fix/y"))
        vm.deliverEngineerTask(taskId)
        advanceUntilIdle()

        val retried = parseEngineerTaskState(taskCards().last().metadata)
        assertEquals(EngineerTaskResolution.DELIVERED, retried?.resolution)
        assertEquals("fix/y", retried?.deliverBranch)
    }

    @Test
    fun `deliver uses gateway 12-hex sid even when claude init UUID session event overwrote task sid`() = runTest {
        coEvery { claudeChatClient.chat(any(), any(), any(), any()) } coAnswers {
            val onEvent = arg<(ClaudeEvent) -> Unit>(3)
            onEvent(ClaudeEvent.Session("aaaa1111bbbb"))
            // claude stream-json 的 system/init（带连字符 UUID）每回合转发，reducer last-wins 覆盖 task.sid
            onEvent(ClaudeEvent.Session("550e8400-e29b-41d4-a716-446655440000"))
            onEvent(ClaudeEvent.Done(turns = 1, truncated = false))
            Result.success("ok")
        }
        coEvery { claudeChatClient.deliver(any(), any(), any()) } returns
            Result.success(JSONObject().put("ok", true).put("branch", "fix/x"))

        val vm = newViewModel()
        vm.sendClaudeMessage("改一下脚本")
        advanceUntilIdle()
        val taskId = vm.engineerTasks.value.keys.single()
        // 证据固定：reducer 确实把 UUID 存进了卡（消费端校验的成因）
        assertEquals("550e8400-e29b-41d4-a716-446655440000", vm.engineerTasks.value[taskId]?.sid)

        vm.deliverEngineerTask(taskId)
        advanceUntilIdle()

        // 网关 /deliver 只认 12-hex：UUID 被 pattern 拦下，回落 VM 级 claudeSid
        coVerify { claudeChatClient.deliver(any(), "aaaa1111bbbb", "push") }
    }

    @Test
    fun `abandon and skip are sticky - repeated approval actions are no-ops`() = runTest {
        coEvery { claudeChatClient.chat(any(), any(), any(), any()) } coAnswers {
            val onEvent = arg<(ClaudeEvent) -> Unit>(3)
            onEvent(ClaudeEvent.Session("aaaa1111bbbb"))
            onEvent(ClaudeEvent.Done(turns = 1, truncated = false))
            Result.success("ok")
        }

        val vm = newViewModel()
        vm.sendClaudeMessage("改一下脚本")
        advanceUntilIdle()
        val taskId = vm.engineerTasks.value.keys.single()

        vm.abandonEngineerTask(taskId)
        advanceUntilIdle()
        val afterFirst = taskCards().size
        assertEquals(EngineerTaskResolution.ABANDONED, vm.engineerTasks.value[taskId]?.resolution)

        // 二次 abandon / skip：resolved 幂等粘滞，不再产生新 upsert、不翻盘
        vm.abandonEngineerTask(taskId)
        vm.skipEngineerDeliver(taskId)
        advanceUntilIdle()

        assertEquals(afterFirst, taskCards().size)
        assertEquals(EngineerTaskResolution.ABANDONED, vm.engineerTasks.value[taskId]?.resolution)
        assertEquals(EngineerTaskStatus.COMPLETED, vm.engineerTasks.value[taskId]?.status)
    }
}
