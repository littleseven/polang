package com.mamba.picme.agent.core.intent

import com.mamba.picme.agent.core.model.command.AgentCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [IntentGuard] 单测：锁死导航守卫规则的判定边界。
 *
 * 规则自 ChatViewModel 私有逻辑收口而来，行为须与迁移前逐处等价。
 *（isRefusedSearchRequest 测试组已随该函数 2026-11-08 退役删除一并移除。）
 */
class IntentGuardTest {

    // ── isExplicitNavigationRequest ─────────────────────────────

    @Test
    fun `explicit navigation - go to camera is explicit`() {
        assertTrue(IntentGuard.isExplicitNavigationRequest("去相机"))
    }

    @Test
    fun `explicit navigation - open settings is explicit`() {
        assertTrue(IntentGuard.isExplicitNavigationRequest("打开设置"))
    }

    @Test
    fun `explicit navigation - go back phrases are explicit`() {
        assertTrue(IntentGuard.isExplicitNavigationRequest("返回"))
        assertTrue(IntentGuard.isExplicitNavigationRequest("上一页"))
    }

    @Test
    fun `explicit navigation - fuzzy phrases are not explicit`() {
        assertFalse(IntentGuard.isExplicitNavigationRequest("我想看看相册"))
        assertFalse(IntentGuard.isExplicitNavigationRequest("想去拍照"))
        assertFalse(IntentGuard.isExplicitNavigationRequest(""))
        assertFalse(IntentGuard.isExplicitNavigationRequest("   "))
    }

    @Test
    fun `explicit navigation - embedded open camera phrase matches regex behavior`() {
        // 锁死实际正则行为："打开相机"子串命中即放行（旧 KDoc 曾误写为应拦截）
        assertTrue(IntentGuard.isExplicitNavigationRequest("帮我打开相机"))
        // 口令与目标间允许空白（\s* 分支）
        assertTrue(IntentGuard.isExplicitNavigationRequest("去 相机"))
    }

    // ── sanitizeNavigationCommands ──────────────────────────────

    @Test
    fun `sanitize - explicit navigation request passes through`() {
        val commands = listOf(AgentCommand.NavigateTo(destination = "camera"))
        val result = IntentGuard.sanitizeNavigationCommands(commands, "去相机", BLOCKED)
        assertEquals(commands, result)
    }

    @Test
    fun `sanitize - fuzzy navigation is replaced by blocked text reply`() {
        val commands = listOf(
            AgentCommand.NavigateTo(destination = "gallery"),
            AgentCommand.GoBack()
        )
        val result = IntentGuard.sanitizeNavigationCommands(commands, "我想看看相册", BLOCKED)
        assertEquals(2, result.size)
        result.forEach { command ->
            val reply = assertIs<AgentCommand.TextReply>(command)
            assertEquals(BLOCKED, reply.message)
        }
    }

    @Test
    fun `sanitize - non navigation commands are untouched`() {
        val commands = listOf(AgentCommand.TextReply(message = "你好"))
        val result = IntentGuard.sanitizeNavigationCommands(commands, "我想看看相册", BLOCKED)
        assertEquals(commands, result)
    }

    @Test
    fun `sanitize - empty commands pass through`() {
        assertEquals(
            emptyList(),
            IntentGuard.sanitizeNavigationCommands(emptyList(), "去相机", BLOCKED)
        )
    }

    private companion object {
        const val BLOCKED = "已在聊天页，未执行跳转"
    }
}
