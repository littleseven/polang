import SwiftUI
import SharedKit

// MARK: - 工程师任务列表项（task-center.yaml §engineer_list_item，US-14）

/// 工程师 Tab 列表项：Surface 卡（r16/tonal 2）+ 四行内容（meta 行 / 标题 / 状态摘要 / 任务元数据）。
/// 🔴 审批动作按钮一律不渲染（spec anti_patterns #3：iOS 无工程师数据链，误触即空转）。
/// 整卡点击 → 回 chat 对应会话（US-15 锚定降级：无逐卡锚定滚动，切会话即可）。
struct EngineerTaskListRow: View {
    let item: TaskCenterItem
    var onTap: () -> Void

    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    private var task: EngineerTaskState { item.task }

    /// 组件级度量（spec engineer_list_item.container / row 规格直录；
    /// 11sp 小字 / 12sp 摘要 / 14sp 标题 Medium 为 spec 值，token 阶梯无对应档不归一化）。
    private enum Metrics {
        static let cornerRadius: CGFloat = AppRadius.lg        // 16
        static let elevationTonal: Color = .clear              // tonal_elevation 2 由底色承担（见 cardBackground）
        static let metaFontSize: CGFloat = 11
        static let titleFontSize: CGFloat = 14
        static let summaryFontSize: CGFloat = 12
        static let innerPadding: CGFloat = Spacing.md           // 12
        static let approvalBorderWidth: CGFloat = 1
    }

    /// M3 Surface(tonalElevation = 2) → surfaceContainerHigh（色阶映射）。
    private var cardBackground: Color { s.surfaceContainerHigh }

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                metaLine
                titleLine
                summarySection
                if !taskMetaText.isEmpty {
                    Text(taskMetaText)
                        .font(.system(size: Metrics.metaFontSize))
                        .foregroundColor(s.onSurfaceVariant)
                        .padding(.top, Spacing.xs)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(Metrics.innerPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: Metrics.cornerRadius, style: .continuous))
            .overlay(approvalBorder)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("task_center_engineer_card_\(task.taskId)")
        .accessibilityLabel(L("cd_engineer_task_card"))
    }

    /// row1：状态 chip + 会话标题（blank 不渲染）+ spacer + 耗时，垂直居中。
    private var metaLine: some View {
        HStack(spacing: Spacing.sm) {
            EngineerTaskStatusChip(status: task.status, resolution: task.resolution)
            if let sessionTitle = item.sessionTitle?
                .trimmingCharacters(in: .whitespacesAndNewlines), !sessionTitle.isEmpty {
                Text(sessionTitle)
                    .font(.system(size: Metrics.metaFontSize))
                    .foregroundColor(s.onSurfaceVariant)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            Spacer(minLength: Spacing.sm)
            Text(EngineerTaskFormat.elapsed(from: task.startedAtMs, to: task.updatedAtMs))
                .font(.system(size: Metrics.metaFontSize))
                .foregroundColor(s.onSurfaceVariant)
        }
    }

    /// row2：标题（消息 content 或 sourceText），14 Medium 单行省略。
    private var titleLine: some View {
        Text(item.title)
            .font(.system(size: Metrics.titleFontSize, weight: .medium))
            .foregroundColor(s.onSurface)
            .lineLimit(1)
            .truncationMode(.tail)
            .padding(.top, Spacing.sm - 2)   // spacing_top 6
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// row3：按状态机分支的进度摘要（对齐聊天气泡卡各态文案）。
    @ViewBuilder private var summarySection: some View {
        switch task.status {
        case .running:
            if let stage = task.stage?.trimmingCharacters(in: .whitespacesAndNewlines), !stage.isEmpty {
                summaryText(String(format: L("chat_task_stage"), stage), color: s.primary, top: Spacing.xs)
            }
        case .awaitingContinue:
            summaryText(L("claude_truncated"), color: s.onSurfaceVariant, top: Spacing.sm - 2)
            if let reason = task.truncatedReason?
                .trimmingCharacters(in: .whitespacesAndNewlines), !reason.isEmpty {
                summaryText(reason, fontSize: Metrics.metaFontSize, color: s.onSurfaceVariant, top: Spacing.xs)
            }
        case .awaitingDeliver:
            summaryText(
                String(format: L("chat_task_deliver_summary"), Int64(task.fileChangeCount)),
                color: s.onSurfaceVariant, top: Spacing.sm - 2)
            errorBlock
        case .completed:
            if let summary = task.resultSummary?
                .trimmingCharacters(in: .whitespacesAndNewlines), !summary.isEmpty {
                summaryText(summary, color: s.onSurfaceVariant, top: Spacing.xs)
                    .lineLimit(2)
            }
            // 已裁决（失败后被裁决）的卡：出错原因仍是卡的一部分
            if task.resolution != nil { errorBlock }
        case .failed:
            errorBlock
        }
    }

    private func summaryText(
        _ text: String, fontSize: CGFloat = Metrics.summaryFontSize, color: Color, top: CGFloat
    ) -> some View {
        Text(text)
            .font(.system(size: fontSize))
            .foregroundColor(color)
            .padding(.top, top)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 错误块（EngineerTaskErrorBlock 等价）：error 色 8% 透明底圆角 8 + 错误图标 + 文本 max3 行。
    @ViewBuilder private var errorBlock: some View {
        if let error = task.errorSummary?
            .trimmingCharacters(in: .whitespacesAndNewlines), !error.isEmpty {
            HStack(alignment: .top, spacing: Spacing.sm - 2) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: Metrics.summaryFontSize))
                    .foregroundColor(s.error)
                Text(error)
                    .font(.system(size: Metrics.summaryFontSize))
                    .foregroundColor(s.error)
                    .lineLimit(3)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, Spacing.sm + 2)   // 10
            .padding(.vertical, Spacing.sm)
            .background(s.error.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.small, style: .continuous))
            .padding(.top, Spacing.sm - 2)
        }
    }

    /// row4：任务元数据（轮次 · 成本 · files · ⎇ branch；空串不渲染）。
    private var taskMetaText: String {
        var parts: [String] = []
        if task.turns > 0 {
            parts.append(EngineerTaskFormat.turnsText(Int(task.turns)))
        }
        if let cents = task.costCents {
            parts.append(EngineerTaskFormat.costText(cents: Int(truncating: cents)))
        }
        if task.fileChangeCount > 0 {
            parts.append(EngineerTaskFormat.filesChangedText(Int(task.fileChangeCount)))
        }
        if let branch = task.deliverBranch?
            .trimmingCharacters(in: .whitespacesAndNewlines), !branch.isEmpty {
            parts.append("⎇ \(branch)")
        }
        return parts.joined(separator: " · ")
    }

    /// 待审批项描边 1dp primary（BorderStroke）。
    @ViewBuilder private var approvalBorder: some View {
        if EngineerTaskFormat.isAwaitingApproval(task) {
            RoundedRectangle(cornerRadius: Metrics.cornerRadius, style: .continuous)
                .strokeBorder(s.primary, lineWidth: Metrics.approvalBorderWidth)
        }
    }
}

#if DEBUG
#Preview("五态样本") {
    ScrollView {
        VStack(spacing: 8) {
            ForEach(0..<5, id: \.self) { i in
                let states: [(EngineerTaskStatus, EngineerTaskResolution?)] = [
                    (.running, nil), (.awaitingContinue, nil), (.awaitingDeliver, nil),
                    (.completed, nil), (.failed, .continued),
                ]
                EngineerTaskListRow(
                    item: TaskCenterItem(
                        sessionId: "s\(i)",
                        title: "Fix login crash on cold start",
                        sessionTitle: "Bug fixes",
                        task: TaskCenterStore.previewEngineerTask(
                            id: "t\(i)", status: states[i].0, resolution: states[i].1)),
                    onTap: {})
                    .padding(.horizontal, Spacing.md)
                    .padding(.vertical, Spacing.xs)
            }
        }
        .padding(.vertical)
    }
    .background(Color(.systemBackground))
}
#endif
