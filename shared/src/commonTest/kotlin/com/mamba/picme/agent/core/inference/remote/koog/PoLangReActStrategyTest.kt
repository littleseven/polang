package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.entity.AIAgentNodeBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 分层 runtime M1：端云共用策略工厂——默认路径与云端配置两态。 */
class PoLangReActStrategyTest {

    private fun stubCorrector() = object : FinalOutputCorrector {
        override fun correctionGateError(assistantText: String): String? = "err"
        override fun rawValidationError(assistantText: String): String? = "err"
        override fun markCorrected() {}
        override fun correctionPrompt(error: String): String = "fix: $error"
    }

    @Test
    fun `default config keeps legacy strategy name`() {
        assertEquals("polang_single_run", poLangSingleRunStrategy().name)
    }

    @Test
    fun `named one-shot config builds`() {
        assertEquals("custom", poLangSingleRunStrategy(PoLangReActStrategyConfig(name = "custom")).name)
    }

    @Test
    fun `streaming config with corrector builds`() {
        val strategy = poLangSingleRunStrategy(
            PoLangReActStrategyConfig(
                name = "polang_cloud_single_run",
                streaming = true,
                finalOutputCorrector = stubCorrector(),
            )
        )
        assertEquals("polang_cloud_single_run", strategy.name)
    }

    @Test
    fun `one-shot config with corrector builds`() {
        val strategy = poLangSingleRunStrategy(
            PoLangReActStrategyConfig(name = "guarded", finalOutputCorrector = stubCorrector())
        )
        assertEquals("guarded", strategy.name)
    }

    // ── U2-1：loopGuard 熔断边——三态图同构（builtin 既有 + guarded/plain 补齐） ──
    // 图结构断言：Koog 边按声明序求值（先匹配者胜），熔断边 = executeTool 出边中直达
    // nodeFinish 者，必须声明在回落边（→sendToolResult）之前。

    /** nodeStart → callLLM 首跳；callLLM 首条声明边恒为 onToolCalls → executeTool。 */
    private fun executeToolNode(strategy: AIAgentGraphStrategy<String, String>): AIAgentNodeBase<*, *> {
        val callLLM = strategy.nodeStart.edges.single().toNode
        return callLLM.edges.first().toNode
    }

    private fun executeToolTargets(strategy: AIAgentGraphStrategy<String, String>): List<AIAgentNodeBase<*, *>> =
        executeToolNode(strategy).edges.map { it.toNode }

    @Test
    fun `streaming guarded config with loop guard routes executeTool to finish before fallback`() {
        val strategy = poLangSingleRunStrategy(
            PoLangReActStrategyConfig(
                name = "polang_cloud_single_run",
                streaming = true,
                finalOutputCorrector = stubCorrector(),
                toolLoopGuard = ToolLoopGuard(),
            )
        )
        val targets = executeToolTargets(strategy)
        assertEquals(2, targets.size, "executeTool 出边 = 熔断边 + 回落边，实际: ${targets.map { it.name }}")
        assertTrue(targets[0] === strategy.nodeFinish, "熔断边须声明在回落边之前（先匹配者胜）")
        assertTrue(targets[1] !== strategy.nodeFinish)
    }

    @Test
    fun `streaming plain config with loop guard routes executeTool to finish before fallback`() {
        val strategy = poLangSingleRunStrategy(
            PoLangReActStrategyConfig(name = "plain_stream", streaming = true, toolLoopGuard = ToolLoopGuard())
        )
        val targets = executeToolTargets(strategy)
        assertEquals(2, targets.size, "实际: ${targets.map { it.name }}")
        assertTrue(targets[0] === strategy.nodeFinish)
        assertTrue(targets[1] !== strategy.nodeFinish)
    }

    @Test
    fun `builtin one-shot with loop guard keeps trip edge before fallback`() {
        val strategy = poLangSingleRunStrategy(PoLangReActStrategyConfig(toolLoopGuard = ToolLoopGuard()))
        val targets = executeToolTargets(strategy)
        assertEquals(2, targets.size)
        assertTrue(targets[0] === strategy.nodeFinish)
        assertTrue(targets[1] !== strategy.nodeFinish)
    }

    @Test
    fun `null loop guard keeps configurable graphs without trip edge`() {
        val guarded = poLangSingleRunStrategy(
            PoLangReActStrategyConfig(streaming = true, finalOutputCorrector = stubCorrector())
        )
        val guardedTargets = executeToolTargets(guarded)
        assertEquals(1, guardedTargets.size)
        assertTrue(guardedTargets[0] !== guarded.nodeFinish)

        val plain = poLangSingleRunStrategy(PoLangReActStrategyConfig(streaming = true))
        val plainTargets = executeToolTargets(plain)
        assertEquals(1, plainTargets.size)
        assertTrue(plainTargets[0] !== plain.nodeFinish)
    }
}
