import SwiftUI
import SharedKit

// MARK: - 工程师任务状态 chip（任务中心列表项 + 聊天气泡原生兜底卡共用）

/// 工程师任务卡状态 chip（task-center.yaml §engineer_list_item.row1_meta_line.status_chip；
/// chip 规格指针 = shared `EngineerTaskHtml` 模板 .chip 段——双端同源视觉）。
///
/// 色规则（与 HTML 卡 chip 逐值对齐）：
/// - 未裁决：RUNNING → primary 底/onPrimary 字；COMPLETED → 中性底/onSurfaceVariant 字；
///   AWAITING_CONTINUE / AWAITING_DELIVER / FAILED → error 底/onError 字；
/// - 已裁决（resolution ≠ null）任意终态 → 中性底/onSurfaceVariant 字。
struct EngineerTaskStatusChip: View {
    let status: EngineerTaskStatus
    let resolution: EngineerTaskResolution?

    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    /// shared 模板 chip 度量（EngineerTaskHtml.document `.chip` 段）：
    /// 10.5px 字号 / padding 3×8 / 圆角 6（1 CSS px ≈ 1 dp ≈ 1 pt）。
    private enum Metrics {
        static let fontSize: CGFloat = 10.5
        static let paddingH: CGFloat = 8
        static let paddingV: CGFloat = 3
        static let cornerRadius: CGFloat = 6
    }

    private var style: Style {
        if let resolution {
            let text: String
            switch resolution {
            case .continued: text = L("chat_task_resolved_continued")
            case .abandoned: text = L("chat_task_resolved_abandoned")
            case .delivered: text = L("chat_task_resolved_delivered")
            case .deliverSkipped: text = L("chat_task_resolved_skipped")
            }
            return Style(text: text, background: s.surfaceContainerHigh, foreground: s.onSurfaceVariant)
        }
        switch status {
        case .running:
            return Style(text: L("chat_task_status_running"), background: s.primary, foreground: s.onPrimary)
        case .completed:
            return Style(text: L("chat_task_status_completed"), background: s.surfaceContainerHigh, foreground: s.onSurfaceVariant)
        case .awaitingContinue, .awaitingDeliver:
            return Style(text: L("chat_task_status_awaiting"), background: s.error, foreground: s.onError)
        case .failed:
            return Style(text: L("chat_task_status_failed"), background: s.error, foreground: s.onError)
        }
    }

    private struct Style {
        let text: String
        let background: Color
        let foreground: Color
    }

    var body: some View {
        Text(style.text)
            .font(.system(size: Metrics.fontSize))
            .foregroundStyle(style.foreground)
            .padding(.horizontal, Metrics.paddingH)
            .padding(.vertical, Metrics.paddingV)
            .background(style.background)
            .clipShape(RoundedRectangle(cornerRadius: Metrics.cornerRadius, style: .continuous))
            .fixedSize()
    }
}

// MARK: - 工程师任务格式化（任务中心列表项 + HTML 卡 texts 共用）

enum EngineerTaskFormat {

    /// 耗时 M:SS（task-center.yaml §row1_meta_line.elapsed：formatElapsed(updatedAtMs-startedAtMs)）。
    static func elapsed(from startedAtMs: Int64, to updatedAtMs: Int64) -> String {
        let seconds = max(Int(updatedAtMs - startedAtMs) / 1000, 0)
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    /// 成本 $X.XX（costCents → 元；chat_task_meta_cost）。
    static func costText(cents: Int) -> String {
        String(format: L("chat_task_meta_cost"), Double(cents) / 100)
    }

    /// 轮次 N（chat_task_meta_turns；0 轮不渲染由调用方判空）。
    static func turnsText(_ turns: Int) -> String {
        String(format: L("chat_task_meta_turns"), Int64(turns))
    }

    /// N files changed（chat_task_meta_files；0 不渲染由调用方判空）。
    static func filesChangedText(_ count: Int) -> String {
        String(format: L("chat_task_meta_files"), Int64(count))
    }

    /// 待审批判据（shared TaskCenterPartition.isAwaitingApproval 同口径：
    /// 未裁决 + AWAITING_CONTINUE/AWAITING_DELIVER → 列表项描边高亮）。
    static func isAwaitingApproval(_ task: EngineerTaskState) -> Bool {
        guard task.resolution == nil else { return false }
        return task.status == .awaitingContinue || task.status == .awaitingDeliver
    }
}

#Preview("五态") {
    VStack(spacing: 12) {
        EngineerTaskStatusChip(status: .running, resolution: nil)
        EngineerTaskStatusChip(status: .awaitingContinue, resolution: nil)
        EngineerTaskStatusChip(status: .awaitingDeliver, resolution: nil)
        EngineerTaskStatusChip(status: .completed, resolution: nil)
        EngineerTaskStatusChip(status: .failed, resolution: nil)
        EngineerTaskStatusChip(status: .completed, resolution: .delivered)
    }
    .padding(24)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color(.systemBackground))
}
