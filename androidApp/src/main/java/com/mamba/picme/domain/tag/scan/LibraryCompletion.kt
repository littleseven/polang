package com.mamba.picme.domain.tag.scan

import kotlin.math.roundToInt

/**
 * 单个 Pass 阶段的进度快照。
 *
 * 语义：[processed] = 本阶段「已处理」数（做过检测/生成），不是「有结果数」。
 * 取代旧的 `withFace / totalMedia` 分数式——后者把「有该结果的子集（如 withFace）」
 * 误当成「已完成」，导致进度误报。真实口径：processed = total − remaining。
 */
data class TagPassProgress(
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
fun tagPassProgress(total: Int, remaining: Int): TagPassProgress {
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
 * 阶段完成率整数百分比（0..100，四舍五入）。阶段行 trailing、图库统计圆环、
 * 前台通知与任务中心共用本函数（2026-10-01 口径立法），杜绝多处口径/舍入漂移。
 */
fun TagPassProgress.percentRounded(): Int =
    if (total > 0) (processed.toDouble() / total * 100).roundToInt() else 0

/**
 * 库级 AI 打标完成率——全 app 唯一对外百分比口径（spec 2026-10-01 §4 口径立法）。
 *
 * 口径 = 内容标签 Pass3 库级完成率：round((totalMedia − remainingPass3) / totalMedia × 100)。
 * 任务级会话进度（[TagScanSessionProgress]，一媒体多任务）只允许以「第 x/y 张」
 * 叙述形态出现，禁止渲染为百分比/进度占比，避免与本口径并存打架。
 */
data class LibraryCompletion(
    val totalMedia: Int,
    val remainingPass3: Int,
) {
    val progress: TagPassProgress get() = tagPassProgress(totalMedia, remainingPass3)

    /** 0f..1f，供进度条/圆环弧直接使用 */
    val fraction: Float get() = progress.fraction

    fun percentRounded(): Int = progress.percentRounded()
}
