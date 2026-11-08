package com.mamba.picme.features.chat

import android.content.Context
import com.mamba.picme.R
import com.mamba.picme.domain.model.AppLanguage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * ChatViewModel.stringContext() 非 SYSTEM 分支覆盖。
 *
 * 基座把 `getAppLanguageBlocking()` 钉死为 SYSTEM（全走 applicationContext 透传），
 * 本类显式 stub ENGLISH，钉住「when 映射 + createConfigurationContext 取词 + 按语言缓存」：
 * - 用户可见文案必须来自 localized context（而非跟随系统语言的 applicationContext）
 * - 同一次会话多次取词只创建一次 configuration context（缓存生效）
 *
 * 触发路径复用 guest 403 配额耗尽（ChatViewModelGuestModeTest 同款 fixture）：
 * 该路径的配额气泡文案经 stringContext().getString(R.string.chat_guest_quota_used_up) 解析。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelStringContextTest : ChatViewModelTestBase() {

    private lateinit var localizedContext: Context

    @Before
    override fun setUp() {
        super.setUp()
        every { userSettingsRepository.getAppLanguageBlocking() } returns AppLanguage.ENGLISH
        localizedContext = mockk(relaxed = true)
        every { localizedContext.getString(R.string.chat_guest_quota_used_up) } returns "LOCALIZED"
        every { context.createConfigurationContext(any()) } returns localizedContext
    }

    @Test
    fun `non-SYSTEM language resolves user-facing strings via localized context`() = runTest {
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

        // 取词来源钉住：配额气泡内容必须来自 localizedContext（可辨识值 "LOCALIZED"）
        coVerify {
            chatMessageDao.insertMessage(match { entity -> entity.content == "LOCALIZED" })
        }
        verify { localizedContext.getString(R.string.chat_guest_quota_used_up) }
        // 缓存断言：sendMessage 路径多次取词（thinking hint / 配额气泡 / 自动标题等），
        // localized context 只创建一次
        verify(exactly = 1) { context.createConfigurationContext(any()) }
    }

    @Test
    fun `SYSTEM language resolves strings via applicationContext without configuration override`() = runTest {
        // 显式回到 SYSTEM（本类 setUp 覆盖为 ENGLISH）：钉住 stringContext 的早返回分支——
        // 若早返回被误删，会走 createConfigurationContext（本用例未 stub 其返回值的反向验证）
        every { userSettingsRepository.getAppLanguageBlocking() } returns AppLanguage.SYSTEM
        every { context.getString(R.string.chat_guest_quota_used_up) } returns "SYSTEM_RES"
        tokenFlow.value = ""
        coEvery {
            orchestrator.remoteChatEngine.streamChat(any(), any(), any())
        } returns Result.failure(
            RuntimeException("""{"error":"quota_exceeded","tier":"guest","message":"guest quota used up"}""")
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("帮我找张照片")
        advanceUntilIdle()

        // 取词落在 applicationContext（可辨识值 "SYSTEM_RES"）
        coVerify {
            chatMessageDao.insertMessage(match { entity -> entity.content == "SYSTEM_RES" })
        }
        // SYSTEM 档绝不创建 configuration override context
        verify(exactly = 0) { context.createConfigurationContext(any()) }
    }
}
