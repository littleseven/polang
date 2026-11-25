package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.Message
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.domain.chat.openui.OpenUiProgramGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * U2-2：KoogChatAgent OpenUI turn-native 开关契约——config 两字段、策略配置选择、
 * 契约段 prompt 组装（flag off 逐字节不变，flag on 追加契约段于技能清单段后）。
 */
class KoogChatAgentOpenUiTurnNativeTest {

    private val fakeMemoryStore = object : ChatMemoryStore {
        override suspend fun load(sessionId: String): List<Message> = emptyList()
        override suspend fun save(sessionId: String, messages: List<Message>) {}
        override suspend fun clear(sessionId: String) {}
        override suspend fun loadSummary(sessionId: String): SessionCompaction? = null
        override suspend fun saveSummary(sessionId: String, summary: SessionCompaction) {}
        override suspend fun clearSummary(sessionId: String) {}
    }

    private val skillSection = "可用技能（SKILL）清单标记段"

    private fun agent(
        openUiTurnNative: Boolean,
        replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE,
        skillSection: String? = null,
    ): KoogChatAgent = KoogChatAgent(
        config = RemoteReActAgentConfig.Builder()
            .apiKey("test-key")
            .baseUrl("https://example.com/")
            .modelName("test-model")
            .systemPrompt("BASE")
            .openUiTurnNative(openUiTurnNative)
            .replyLanguage(replyLanguage)
            .skillPromptProvider(skillSection?.let { section -> { section } })
            .build(),
        toolRegistry = ToolRegistry {},
        memoryStore = fakeMemoryStore,
    )

    // ── config 两字段（默认关 + Builder 透传）──

    @Test
    fun `config defaults keep turn-native off and simplified chinese`() {
        val config = RemoteReActAgentConfig.Builder()
            .apiKey("test-key")
            .baseUrl("https://example.com/")
            .build()
        assertFalse(config.openUiTurnNative)
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, config.replyLanguage)
    }

    @Test
    fun `builder setters carry both fields`() {
        val config = RemoteReActAgentConfig.Builder()
            .apiKey("test-key")
            .baseUrl("https://example.com/")
            .openUiTurnNative(true)
            .replyLanguage(ReplyLanguage.FRENCH)
            .build()
        assertTrue(config.openUiTurnNative)
        assertEquals(ReplyLanguage.FRENCH, config.replyLanguage)
    }

    // ── flag off：prompt 组装逐字节不变 ──

    @Test
    fun `flag off composes legacy prompt byte-identically`() {
        val agent = agent(openUiTurnNative = false, skillSection = skillSection)
        assertEquals(
            composeChatSystemPrompt("BASE", "快照", "摘要", skillSection),
            agent.composeSystemPrompt("快照", "摘要", null, skillSection),
        )
        assertEquals(
            composeChatSystemPrompt("BASE", null, null, null),
            agent.composeSystemPrompt(null, null, null, null),
        )
    }

    // ── flag on：契约段追加在技能清单段之后 ──

    @Test
    fun `flag on appends contract section after skill section with local identity line`() {
        val agent = agent(openUiTurnNative = true, skillSection = skillSection)
        val prompt = agent.composeSystemPrompt("快照", "摘要", null, skillSection)
        val skillIdx = prompt.indexOf(skillSection)
        val contractIdx = prompt.indexOf("OpenUI Lang 1.0 程序")
        val languageIdx = prompt.indexOf("App 界面语言为简体中文。")
        assertTrue(contractIdx > 0, "契约段特征串（OpenUI Lang 1.0 程序）必须存在")
        assertTrue(skillIdx in 0 until contractIdx, "契约段必须位于技能清单段之后")
        assertTrue(languageIdx > contractIdx, "语言规则段必须位于契约段之后")
        assertTrue(prompt.contains("你是 PoLang 相册 AI 助手。"), "端侧身份行应为端侧措辞")
        assertFalse(prompt.contains("server_time"), "端侧无 server_time 工具，工具句应剔除")
    }

    @Test
    fun `flag on language rule segment follows configured reply language`() {
        val agent = agent(openUiTurnNative = true, replyLanguage = ReplyLanguage.ENGLISH)
        val prompt = agent.composeSystemPrompt(null, null, null, null)
        assertTrue(prompt.contains("The app's UI language is English"))
        assertFalse(prompt.contains("App 界面语言为简体中文"))
    }

    // ── 策略配置选择（纯函数）──

    @Test
    fun `flag off strategy config equals legacy config exactly`() {
        val loopGuard = ToolLoopGuard()
        assertEquals(
            PoLangReActStrategyConfig(toolLoopGuard = loopGuard),
            chatReActStrategyConfig(openUiTurnNative = false, openUiGuard = OpenUiProgramGuard(), toolLoopGuard = loopGuard),
        )
    }

    @Test
    fun `flag on strategy config enables streaming corrector and fallback`() {
        val guard = OpenUiProgramGuard()
        val loopGuard = ToolLoopGuard()
        val config = chatReActStrategyConfig(openUiTurnNative = true, openUiGuard = guard, toolLoopGuard = loopGuard)
        assertTrue(config.streaming)
        assertNotNull(config.finalOutputCorrector)
        assertNotNull(config.onStreamingFallback)
        assertSame(loopGuard, config.toolLoopGuard)
    }

    @Test
    fun `flag on corrector is bound to the per-agent guard instance`() {
        val guard = OpenUiProgramGuard()
        val corrector = chatReActStrategyConfig(true, guard, ToolLoopGuard()).finalOutputCorrector!!
        assertNotNull(corrector.correctionGateError("裸文本不是程序"), "非法程序应触发纠正门控")
        corrector.markCorrected()
        assertNull(corrector.correctionGateError("仍是裸文本"), "MAX_CORRECTIONS 用尽后放行")
        guard.reset()
        assertNotNull(corrector.correctionGateError("复位后应恢复门控"), "guard.reset 应经同一 corrector 生效")
    }
}
