package com.mamba.picme.agent.core.inference.remote.koog

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
