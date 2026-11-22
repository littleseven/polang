package com.mamba.picme.domain.organize

/**
 * 后台预分析触发条件纯函数（Agent 清理 M1 Task 6）。
 *
 * 决策序（短路，前序优先）：
 * 1. 非充电 ∨ 亮屏 ∨ TAG 扫描活跃 → [PreAnalysisDecision.SKIP]
 *    （避开用户在用相册/打标资源冲突的时段）；
 * 2. 去重扫描会话活跃（RUNNING/PAUSED）→ [PreAnalysisDecision.RUN_METADATA_ONLY]
 *    （进行中不重触发去重；元数据回填与之无资源冲突，照常补）；
 * 3. 去重无完成记录或结果已过期（距今 ≥ [PreAnalysisConditions.DEDUP_STALE_MS]）
 *    → [PreAnalysisDecision.RUN_METADATA_AND_DEDUP]；
 * 4. 否则 → [PreAnalysisDecision.RUN_METADATA_ONLY]（24h 内扫过，只补元数据）。
 */
data class PreAnalysisInputs(
    val isCharging: Boolean,
    val isScreenOn: Boolean,
    val tagScanActive: Boolean,
    val dedupScanActive: Boolean,
    val lastDedupScanCompletedAt: Long?,
    val now: Long,
)

enum class PreAnalysisDecision { RUN_METADATA_AND_DEDUP, RUN_METADATA_ONLY, SKIP }

object PreAnalysisConditions {

    /** 去重扫描结果视为过期的窗口（24h，与每日闲时闹钟节奏对齐）。 */
    const val DEDUP_STALE_MS: Long = 24L * 3600 * 1000

    fun decide(inputs: PreAnalysisInputs): PreAnalysisDecision {
        if (!inputs.isCharging || inputs.isScreenOn || inputs.tagScanActive) {
            return PreAnalysisDecision.SKIP
        }
        if (inputs.dedupScanActive) {
            return PreAnalysisDecision.RUN_METADATA_ONLY
        }
        val lastCompletedAt = inputs.lastDedupScanCompletedAt
        val dedupStale = lastCompletedAt == null || inputs.now - lastCompletedAt >= DEDUP_STALE_MS
        return if (dedupStale) {
            PreAnalysisDecision.RUN_METADATA_AND_DEDUP
        } else {
            PreAnalysisDecision.RUN_METADATA_ONLY
        }
    }
}
