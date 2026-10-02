import Foundation

/// 单个 Pass 阶段的进度快照（直译 Android domain/tag/scan/LibraryCompletion.kt）。
///
/// 语义：[processed] = 本阶段「已处理」数（做过检测/生成），不是「有结果数」。
/// 真实口径：processed = total − remaining。
struct TagPassProgress: Sendable, Equatable {
    let total: Int
    let remaining: Int
    let processed: Int
    /// 0..1；total = 0 时为 0
    let fraction: Float
    let isComplete: Bool
    let isEmpty: Bool
}

/// 由「总数」与「待处理数」派生阶段进度。所有入参会被 clamp 到安全范围。
func tagPassProgress(total: Int, remaining: Int) -> TagPassProgress {
    let safeTotal = max(0, total)
    let safeRemaining = min(max(0, remaining), safeTotal)
    let processed = max(0, safeTotal - safeRemaining)
    let fraction: Float = safeTotal > 0 ? Float(processed) / Float(safeTotal) : 0
    return TagPassProgress(
        total: safeTotal,
        remaining: safeRemaining,
        processed: processed,
        fraction: min(max(fraction, 0), 1),
        isComplete: safeTotal > 0 && safeRemaining == 0,
        isEmpty: safeTotal == 0
    )
}

extension TagPassProgress {
    /// 阶段完成率整数百分比（0..100，四舍五入）。Double 精确路径（2026-10-01 口径立法），
    /// 杜绝多处口径/舍入漂移——全 app 唯一对外百分比算法。
    func percentRounded() -> Int {
        total > 0 ? Int((Double(processed) / Double(total) * 100).rounded()) : 0
    }
}

/// 库级 AI 打标完成率——全 app 唯一对外百分比口径（spec 2026-10-01 §4 口径立法）。
///
/// 口径 = 内容标签 Pass3 库级完成率：round((totalMedia − remainingPass3) / totalMedia × 100)。
/// 任务级会话进度（TagScanSessionProgress，一媒体多任务）只允许以「第 x/y 张」
/// 计数形态出现，禁止渲染为百分比/进度占比，避免与本口径并存打架。
struct LibraryCompletion: Sendable, Equatable {
    let totalMedia: Int
    let remainingPass3: Int

    var progress: TagPassProgress { tagPassProgress(total: totalMedia, remaining: remainingPass3) }

    /// 0..1，供进度条/圆环弧直接使用
    var fraction: Float { progress.fraction }

    func percentRounded() -> Int { progress.percentRounded() }
}
