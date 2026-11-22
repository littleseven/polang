package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后台预分析条件守卫（Agent 清理 M1 Task 6）纯函数单测。
 *
 * 决策序：非充电∨亮屏∨TAG 扫描活跃 → SKIP；去重会话活跃 → RUN_METADATA_ONLY；
 * 去重结果缺失或过期（≥24h）→ RUN_METADATA_AND_DEDUP；否则 RUN_METADATA_ONLY。
 */
class PreAnalysisConditionsTest {

    private companion object {
        const val NOW = 1_760_000_000_000L
    }

    private fun inputs(
        isCharging: Boolean = true,
        isScreenOn: Boolean = false,
        tagActive: Boolean = false,
        dedupActive: Boolean = false,
        lastDedup: Long? = null,
        now: Long = NOW,
    ) = PreAnalysisInputs(
        isCharging = isCharging,
        isScreenOn = isScreenOn,
        tagScanActive = tagActive,
        dedupScanActive = dedupActive,
        lastDedupScanCompletedAt = lastDedup,
        now = now,
    )

    @Test
    fun `charging and idle with stale dedup runs everything`() {
        val d = PreAnalysisConditions.decide(inputs(isCharging = true, isScreenOn = false, lastDedup = null))
        assertEquals(PreAnalysisDecision.RUN_METADATA_AND_DEDUP, d)
    }

    @Test
    fun `screen on skips even when charging`() {
        assertEquals(PreAnalysisDecision.SKIP, PreAnalysisConditions.decide(inputs(isCharging = true, isScreenOn = true)))
    }

    @Test
    fun `not charging skips`() {
        assertEquals(PreAnalysisDecision.SKIP, PreAnalysisConditions.decide(inputs(isCharging = false, isScreenOn = false)))
    }

    @Test
    fun `fresh dedup within 24h runs metadata only`() {
        val d = PreAnalysisConditions.decide(inputs(isCharging = true, isScreenOn = false, lastDedup = NOW - 3600_000))
        assertEquals(PreAnalysisDecision.RUN_METADATA_ONLY, d)
    }

    @Test
    fun `tag scan active defers everything`() {
        // Review Focus #4
        assertEquals(PreAnalysisDecision.SKIP, PreAnalysisConditions.decide(inputs(tagActive = true)))
    }

    @Test
    fun `dedup already running skips retrigger but allows metadata`() {
        val d = PreAnalysisConditions.decide(inputs(dedupActive = true))
        assertEquals(PreAnalysisDecision.RUN_METADATA_ONLY, d)
    }

    @Test
    fun `dedup at exactly 24h boundary counts as stale`() {
        val d = PreAnalysisConditions.decide(
            inputs(isCharging = true, isScreenOn = false, lastDedup = NOW - PreAnalysisConditions.DEDUP_STALE_MS),
        )
        assertEquals(PreAnalysisDecision.RUN_METADATA_AND_DEDUP, d)
    }

    @Test
    fun `tag scan active wins over stale dedup`() {
        val d = PreAnalysisConditions.decide(inputs(isCharging = true, isScreenOn = false, tagActive = true))
        assertEquals(PreAnalysisDecision.SKIP, d)
    }
}
