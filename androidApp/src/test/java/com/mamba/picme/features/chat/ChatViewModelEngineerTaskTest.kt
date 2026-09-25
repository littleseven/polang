package com.mamba.picme.features.chat

import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.remote.picme.ClaudeChatClient
import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * 内部态观测：`_engineerTasks` 无私有访问器，按任务计划经反射读私有流（displayMessages 为
 * WhileSubscribed，无订阅者不发射，不适合断言）。
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

    @Suppress("UNCHECKED_CAST")
    private fun engineerTasks(vm: ChatViewModel): Map<String, EngineerTaskState> {
        val field = ChatViewModel::class.java.getDeclaredField("_engineerTasks")
        field.isAccessible = true
        return (field.get(vm) as StateFlow<Map<String, EngineerTaskState>>).value
    }

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
}
