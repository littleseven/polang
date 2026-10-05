package com.mamba.picme.domain.tagscan

/**
 * TAG 扫描总进度计算核心（双端 SSOT，2026-10-05 口径立法）。
 *
 * 总进度 = 已完成工作量 / 总工作量，工作量单位 = 各 Pass 预估耗时（毫秒），
 * 不按张数——Pass3（默认 ~7s/张，约占全程 87%）与 Pass1（~0.8s/张）同权
 * 会导致人脸阶段秒到 50% 再打标阶段半天不动的失真。
 *
 * 两个作用域共用同一权重模型：
 * - 任务域（一次 sweep / 一批重生成）：分母 = 启动时快照的剩余工作量，
 *   扫描中途新增照片不进入本轮分母（当前剩余按基线钳制），进度单调收敛 100%。
 * - 库域（全库完成度）：分母 = 全部照片两阶段满配工作量，
 *   新增照片拉低完成度是诚实口径，不钳制。
 *
 * 进度与 ETA 同源：etaMs = 当前剩余工作量，杜绝两套时间基准打架。
 */
data class ScanPassWeights(
    val faceDetectionMs: Long = DEFAULT_FACE_DETECTION_MS,
    val imageTaggingMs: Long = DEFAULT_IMAGE_TAGGING_MS,
) {
    companion object {
        const val DEFAULT_FACE_DETECTION_MS = 800L
        const val DEFAULT_IMAGE_TAGGING_MS = 7_000L
    }
}

/** 任务域进度快照。[fraction] 为 null 表示基线无可量化工作（渲染不定态）。 */
data class ScanTaskProgress(
    val fraction: Float?,
    val etaMs: Long?,
)

/**
 * 任务域加权进度：
 *   fraction = 1 − ( w₁·r₁ + w₃·r₃ ) / ( w₁·r₁₀ + w₃·r₃₀ )
 * 当前剩余 r 按 [0, 基线] 钳制：新增照片只会推高 r，钳回基线即「不进入本轮分母」。
 */
fun computeScanTaskProgress(
    baselineRemainingPass1: Long,
    baselineRemainingPass3: Long,
    remainingPass1: Long,
    remainingPass3: Long,
    weights: ScanPassWeights = ScanPassWeights(),
): ScanTaskProgress {
    val baselineWork = weights.faceDetectionMs * baselineRemainingPass1.coerceAtLeast(0) +
        weights.imageTaggingMs * baselineRemainingPass3.coerceAtLeast(0)
    if (baselineWork <= 0) return ScanTaskProgress(fraction = null, etaMs = null)
    val clampedPass1 = remainingPass1.coerceIn(0, baselineRemainingPass1.coerceAtLeast(0))
    val clampedPass3 = remainingPass3.coerceIn(0, baselineRemainingPass3.coerceAtLeast(0))
    val remainingWork = weights.faceDetectionMs * clampedPass1 +
        weights.imageTaggingMs * clampedPass3
    val fraction = 1f - remainingWork.toFloat() / baselineWork
    return ScanTaskProgress(
        fraction = fraction.coerceIn(0f, 1f),
        etaMs = remainingWork,
    )
}

/**
 * 库域加权完成度：
 *   fraction = ( w₁·(N−r₁) + w₃·(N−r₃) ) / ( N·(w₁+w₃) )
 * [totalPhotos] 为 0 时返回 0。r 按 [0, N] 钳制。
 */
fun computeLibraryCompletionFraction(
    totalPhotos: Long,
    remainingPass1: Long,
    remainingPass3: Long,
    weights: ScanPassWeights = ScanPassWeights(),
): Float {
    val total = totalPhotos.coerceAtLeast(0)
    if (total == 0L) return 0f
    val donePass1 = total - remainingPass1.coerceIn(0, total)
    val donePass3 = total - remainingPass3.coerceIn(0, total)
    val doneWork = weights.faceDetectionMs * donePass1 + weights.imageTaggingMs * donePass3
    val totalWork = (weights.faceDetectionMs + weights.imageTaggingMs) * total
    if (totalWork <= 0) return 0f
    return (doneWork.toFloat() / totalWork).coerceIn(0f, 1f)
}
