package com.mamba.picme.features.gallery.components

import kotlin.math.roundToInt

/**
 * 单个 Pass 阶段的进度快照。
 *
 * 语义：[processed] = 本阶段「已处理」数（做过检测/生成），不是「有结果数」。
 * 取代旧的 `withFace / totalMedia` 分数式——后者把「有该结果的子集（如 withFace）」
 * 误当成「已完成」，导致进度误报。真实口径：processed = total − remaining。
 */
internal data class TagPassProgress(
    val total: Int,
    val remaining: Int,
    val processed: Int,
    /** 0f..1f；total = 0 时为 0f */
    val fraction: Float,
    val isComplete: Boolean,
    val isEmpty: Boolean
)

/**
 * 由「总数」与「待处理数」派生阶段进度。所有入参会被 clamp 到安全范围。
 */
internal fun tagPassProgress(total: Int, remaining: Int): TagPassProgress {
    val safeTotal = total.coerceAtLeast(0)
    val safeRemaining = remaining.coerceIn(0, safeTotal)
    val processed = (safeTotal - safeRemaining).coerceAtLeast(0)
    val fraction = if (safeTotal > 0) processed.toFloat() / safeTotal else 0f
    return TagPassProgress(
        total = safeTotal,
        remaining = safeRemaining,
        processed = processed,
        fraction = fraction.coerceIn(0f, 1f),
        isComplete = safeTotal > 0 && safeRemaining == 0,
        isEmpty = safeTotal == 0
    )
}

/**
 * 阶段完成率整数百分比（0..100，四舍五入）。阶段行 trailing 与图库统计圆环
 * 共用本函数（2026-09-30），杜绝同页两个口径/两种舍入的漂移。
 */
internal fun TagPassProgress.percentRounded(): Int = (fraction * 100).roundToInt()
