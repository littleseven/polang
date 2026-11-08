package com.mamba.picme.features.chat

import com.mamba.picme.R
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 访客模式 / 注册引导 单测（design §4.3 / client plan §5）。
 *
 * 覆盖：
 * - isGuestMode 按 server token 有无派生（chat 仅远程，token 为空即访客）
 * - guest 配额耗尽 403 → 弹注册 sheet + 插入「试用额度已用完」气泡（软引导）
 * - 注册用户额度耗尽 403 → 不触发 guest sheet（走通用错误气泡）
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelGuestModeTest : ChatViewModelTestBase() {

    @Before
    override fun setUp() {
        super.setUp()
        every { context.getString(R.string.chat_guest_quota_used_up) } returns "QUOTA_USED_UP"
    }

    // ── isGuestMode 派生（按 token 有无） ─────────────────────────

    @Test
    fun `isGuestMode derived from token presence`() = runTest {
        val cases = listOf(
            "" to true,
            "pl-abc123token" to false,
        )
        cases.forEach { (token, expectedGuest) ->
            tokenFlow.value = token
            val vm = newViewModel()
            advanceUntilIdle()
            assertEquals("token='$token'", expectedGuest, vm.isGuestMode.value)
        }
    }

    // ── 403 配额耗尽分流 ──────────────────────────────────────────

    @Test
    fun `guest 403 opens registration sheet and inserts exhaustion bubble`() = runTest {
        tokenFlow.value = ""
        // 模拟 server 真实 403 body（LlmRoute：guest 配额耗尽）—— 异常 message 即此 body
        coEvery {
            orchestrator.remoteChatEngine.streamChat(any(), any(), any())
        } returns Result.failure(
            RuntimeException("""{"error":"quota_exceeded","tier":"guest","message":"guest quota used up"}""")
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("帮我找张照片")
        advanceUntilIdle()

        assertTrue("访客配额耗尽应弹注册 sheet", vm.showRegistrationSheet.value)
        coVerify {
            chatMessageDao.insertMessage(match { entity -> entity.content == "QUOTA_USED_UP" })
        }
    }

    @Test
    fun `registered user 403 does not open guest sheet`() = runTest {
        tokenFlow.value = "pl-abc123token"
        // account 配额耗尽：body 同样含 quota_exceeded，但 isGuestMode=false → 不应弹 guest sheet
        coEvery {
            orchestrator.remoteChatEngine.streamChat(any(), any(), any())
        } returns Result.failure(
            RuntimeException("""{"error":"quota_exceeded","tier":"account","message":"free quota used up"}""")
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("帮我找张照片")
        advanceUntilIdle()

        assertFalse("注册用户额度耗尽不应触发 guest sheet", vm.showRegistrationSheet.value)
    }

    @Test
    fun `blank message is a no-op`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("   ")
        advanceUntilIdle()

        assertFalse(vm.isProcessing.value)
        coVerify(exactly = 0) { chatMessageDao.insertMessage(any()) }
    }
}
