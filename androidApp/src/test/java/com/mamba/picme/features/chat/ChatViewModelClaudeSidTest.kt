package com.mamba.picme.features.chat

import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.data.remote.picme.ClaudeChatClient
import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] claudeSid 持久化接线的单测（Task 8 + 两轮审查修复，单槽语义）。
 *
 * 覆盖：
 * - 真实入口恢复（集成向）：store 预置 (S1, sid) → enterClaudeMode 切回 S1、恢复 sid、不新建会话
 *   → 发消息 chat 带旧 sid（--resume）
 * - store 为空 → enterClaudeMode 走 newSession 原行为；记录所属会话已删 → 清残留并新建
 * - 兜底恢复：直接对历史会话发消息时 load 命中（takeIf first == sessionId）
 * - Session 事件格式判断：12 位 hex 网关 sid 采纳并 save（含轮换覆盖自愈）；带连字符 UUID 忽略
 *
 * 基建复用 [ChatViewModelTestBase]；claudeChatClient / claudeSidStore 为本文件专属注入。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelClaudeSidTest : ChatViewModelTestBase() {

    override val initialToken = "pl-test-token"

    private val claudeChatClient: ClaudeChatClient = mockk()
    private val sidStore = InMemoryClaudeSidStore()

    @Before
    override fun setUp() {
        super.setUp()

        coEvery { claudeChatClient.engineerAvailability(any()) } returns Result.success(false)
        coEvery { chatMessageDao.getMessageCount(any()) } returns 0
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
            messageDecodeDispatcher = Dispatchers.Main,
        ).also { it.claudeSidStore = sidStore }
    )

    /** fake chat：先发 [events]，再成功返回。 */
    private fun stubChat(vararg events: ClaudeEvent) {
        coEvery { claudeChatClient.chat(any(), any(), any(), any()) } coAnswers {
            val onEvent = arg<(ClaudeEvent) -> Unit>(3)
            events.forEach(onEvent)
            Result.success("ok")
        }
    }

    @Test
    fun `enterClaudeMode restores saved session and sid, no new session created`() = runTest {
        // 预置持久化上下文（模拟上次工程师会话 S1，进程被杀后重进）
        sidStore.save("S1", "aaaa1111bbbb")
        coEvery { chatSessionDao.getSession("S1") } returns ChatSessionEntity(sessionId = "S1", title = "t")
        stubChat()

        val vm = newViewModel()
        assertEquals("default", vm.currentSessionId.value)
        vm.enterClaudeMode()

        // 切回原会话（transcript 连续），不新建
        assertEquals("S1", vm.currentSessionId.value)

        // agent 上下文连续：发消息带恢复的旧 sid（--resume）
        vm.sendClaudeMessage("hello")
        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat("pl-test-token", "hello", "aaaa1111bbbb", any())
        }
    }

    @Test
    fun `enterClaudeMode with empty store creates new session as before`() = runTest {
        stubChat()

        val vm = newViewModel()
        vm.enterClaudeMode()

        // 新建 UUID 会话，且不带 sid（全新上下文）
        assertNotEquals("default", vm.currentSessionId.value)
        vm.sendClaudeMessage("hello")
        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat(any(), "hello", isNull(), any())
        }
    }

    @Test
    fun `enterClaudeMode clears stale record when saved chat session is gone`() = runTest {
        sidStore.save("gone-session", "aaaa1111bbbb")
        // getSession("gone-session") → null（setUp 的 any() stub）：会话已被删除
        stubChat()

        val vm = newViewModel()
        vm.enterClaudeMode()

        assertNull(sidStore.load())
        assertNotEquals("default", vm.currentSessionId.value) // 走了 newSession
    }

    @Test
    fun `persisted sid is restored and sent as resume sid on direct message`() = runTest {
        // 兜底路径：不经 enterClaudeMode，直接对当前（历史）会话发消息
        sidStore.save("default", "aaaa1111bbbb")
        stubChat()

        val vm = newViewModel()
        vm.sendClaudeMessage("hello")

        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat("pl-test-token", "hello", "aaaa1111bbbb", any())
        }
    }

    @Test
    fun `gateway sid session event is adopted and persisted, overriding rotated old sid`() = runTest {
        // 预置旧 sid（模拟恢复后网关 workdir 已清 → 轮换签发新 sid）
        sidStore.save("default", "aaaa1111bbbb")
        stubChat(ClaudeEvent.Session("deadbeef0012"))

        val vm = newViewModel()
        vm.sendClaudeMessage("first")

        // 首轮带旧 sid；新网关 sid 覆盖旧记录并持久化（自愈）
        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat(any(), "first", "aaaa1111bbbb", any())
        }
        assertEquals("default" to "deadbeef0012", sidStore.load())

        // 下一轮带新 sid
        vm.sendClaudeMessage("second")
        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat(any(), "second", "deadbeef0012", any())
        }
    }

    @Test
    fun `claude init session id with hyphens is ignored`() = runTest {
        stubChat(ClaudeEvent.Session("550e8400-e29b-41d4-a716-446655440000"))

        val vm = newViewModel()
        vm.sendClaudeMessage("hello")

        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat(any(), "hello", isNull(), any())
        }
        assertNull(sidStore.load())
    }
}
