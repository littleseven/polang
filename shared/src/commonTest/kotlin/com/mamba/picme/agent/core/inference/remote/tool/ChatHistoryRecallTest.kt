package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * recall_chat_history 工具链单测（2026-11-12）：纯函数（钳制 / observation 组装）+
 * @Tool 分发链（ChatToolService → CapabilityRegistry(scene=CHAT) → Capability）。
 *
 * 分发链经 [CapabilityRegistry.getInstance()] 真单例（与生产同一条路）——fake capability
 * 用唯一名注册、@AfterTest 注销，不污染同 JVM 的其它测试。
 */
class ChatHistoryRecallTest {

    // ── 纯函数：limit 钳制 ──────────────────────────────────────────

    @Test
    fun `clamp limit to range 1 to 50`() {
        assertEquals(1, ChatHistoryRecall.clampLimit(0))
        assertEquals(1, ChatHistoryRecall.clampLimit(-5))
        assertEquals(1, ChatHistoryRecall.clampLimit(1))
        assertEquals(20, ChatHistoryRecall.clampLimit(20))
        assertEquals(50, ChatHistoryRecall.clampLimit(50))
        assertEquals(50, ChatHistoryRecall.clampLimit(999))
    }

    // ── 纯函数：observation 组装 ────────────────────────────────────

    @Test
    fun `empty result renders zero-count guidance`() {
        assertEquals(
            "[历史召回] 共 0 条——当前会话没有匹配的文本历史",
            ChatHistoryRecall.formatObservation(emptyList()),
        )
    }

    @Test
    fun `lines render ascending with role prefix`() {
        val out = ChatHistoryRecall.formatObservation(
            listOf(
                ChatHistoryRecall.HistoryLine("user", "找一下去年海边的照片", 100),
                ChatHistoryRecall.HistoryLine("agent", "找到了 12 张", 200),
            )
        )
        assertEquals(
            "[历史召回] 共 2 条（按时间正序）：\n" +
                "user: 找一下去年海边的照片\n" +
                "agent: 找到了 12 张",
            out,
        )
    }

    @Test
    fun `per-line content truncates at 200 chars with ellipsis and flattens newlines`() {
        val long = "a".repeat(300)
        val out = ChatHistoryRecall.formatObservation(
            listOf(ChatHistoryRecall.HistoryLine("agent", "line1\nline2\n$long", 1))
        )
        assertTrue(out.contains("line1 line2 "), "换行应被拍平为空格")
        // 拍平后总长 312 > 200：截断作用在整行（含 "line1 line2 " 12 字符前缀）
        assertTrue(out.contains("a".repeat(188) + "…"), "应截断到 200 字符加省略号")
        assertTrue(!out.contains("a".repeat(189)), "截断后不应残留 189 连续 a")
    }

    @Test
    fun `total budget drops oldest whole lines and keeps recent segment`() {
        // 45 条 × ~190 字符 ≈ 8.5KB > TOTAL_MAX_CHARS(4000)：应从最旧一侧整行丢弃
        val lines = (1..45).map { idx ->
            ChatHistoryRecall.HistoryLine("user", "msg-$idx-" + "x".repeat(180), idx.toLong())
        }
        val out = ChatHistoryRecall.formatObservation(lines)
        assertTrue(out.startsWith("[历史召回] 共 45 条（按时间正序）：\n"))
        assertTrue(out.contains("…<较早 "), "总量超预算应带截断标记")
        assertTrue(out.contains("msg-45-"), "最近一条必须保留")
        assertTrue(!out.contains("msg-1-"), "最旧段应被丢弃")
        assertTrue(out.length < 4_500, "总量应收敛到预算附近，实际 ${out.length}")
    }

    // ── 分发链：@Tool → CapabilityRegistry(CHAT) → Capability ────────

    private class CapturedCommand(var command: AgentCommand? = null, var context: AgentContext? = null)

    private val captured = CapturedCommand()

    private val fakeCapability = object : BaseCapability() {
        override val name = "chat-history-fake-under-test"
        override val description = "fake"
        override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)
        override fun supportedCommands(): List<String> = listOf("recall_chat_history")
        override suspend fun execute(
            command: AgentCommand,
            context: AgentContext,
            pageContext: PageContext?
        ): Result<AgentAction> {
            captured.command = command
            captured.context = context
            return Result.success(AgentAction.TextReply(command.commandId, "OBSERVATION-FROM-FAKE"))
        }
    }

    @AfterTest
    fun tearDown() {
        CapabilityRegistry.getInstance().unregister(fakeCapability)
        ChatToolService.getInstance().chatSessionId = "default"
        captured.command = null
        captured.context = null
    }

    @Test
    fun `tool dispatch reaches capability with clamped limit and current session`() = runTest {
        SceneManager.getInstance().transitionTo(SceneManager.Scene.CHAT)
        CapabilityRegistry.getInstance().register(fakeCapability)
        ChatToolService.getInstance().chatSessionId = "sess-42"

        val observation = ChatToolService.getInstance().recallChatHistory(query = "过敏", limit = 999)

        assertEquals("OBSERVATION-FROM-FAKE", observation)
        val cmd = assertIs<AgentCommand.RecallChatHistory>(captured.command)
        assertEquals("过敏", cmd.query)
        assertEquals(50, cmd.limit, "LLM 越界 limit 应在 @Tool 层钳到 MAX_LIMIT")
        val ctx = captured.context
        assertEquals(AgentScene.CHAT, ctx?.scene)
        assertEquals("sess-42", ctx?.memorySessionId, "会话范围应经 chatSessionId holder 下传")
    }

    @Test
    fun `tool dispatch keeps blank query as no-filter passthrough`() = runTest {
        SceneManager.getInstance().transitionTo(SceneManager.Scene.CHAT)
        CapabilityRegistry.getInstance().register(fakeCapability)
        ChatToolService.getInstance().chatSessionId = "s"

        ChatToolService.getInstance().recallChatHistory(query = "", limit = 20)

        val cmd = assertIs<AgentCommand.RecallChatHistory>(captured.command)
        assertEquals("", cmd.query)
        assertEquals(20, cmd.limit)
        assertNull(captured.context?.traceId, "未接线 traceIdHolder 时应为 null")
    }
}
