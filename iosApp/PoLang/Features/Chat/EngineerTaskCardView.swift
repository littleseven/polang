import SwiftUI
import SharedKit

// MARK: - 工程师任务卡聊天气泡（chat.yaml §13 task_card iOS 跟随）

/// 工程师任务卡：`EngineerTaskHtml`（shared L1 模板）组装 → `HtmlCardView` 双形态渲染
/// （INLINE 动态测高 / FULLPAGE 预览 + 查看器）。点击整卡折叠/展开（INLINE 触控层）；
/// displayMode 粘滞不落库（终判回调 no-op——会话内 @State 粘滞、视图重建复位）。
///
/// iOS 无 claude-tunnel 数据链（task-center.yaml §范围裁定）：卡片只读——不渲染原生
/// 动作条（anti_patterns #3），数据源仅持久化历史。组装失败 → 原生兜底卡。
struct EngineerTaskCardView: View {
    let task: EngineerTaskState
    var onOpenFullpage: (String, String?) -> Void
    var onOpenLink: (URL) -> Void

    @State private var expanded = false
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    var body: some View {
        Group {
            if let html = assembledHtml {
                HtmlCardView(
                    messageId: "engineer:\(task.taskId)",
                    part: MessagePartHtmlCard(
                        partId: task.taskId,
                        html: html,
                        meta: emptyMeta,
                        state: .outputAvailable),
                    meta: emptyMeta,
                    onOpenFullpage: onOpenFullpage,
                    onOpenLink: onOpenLink,
                    onDisplayFinalized: { _, _ in },   // displayMode 粘滞不落库（chat.yaml §13）
                    onTap: { withAnimation(.easeInOut(duration: AppMotion.fastMs / 1000)) { expanded.toggle() } })
            } else {
                nativeFallbackCard
            }
        }
        .accessibilityIdentifier("chat_engineer_task_card")
        .accessibilityLabel(L("cd_engineer_task_card"))
    }

    /// 组装失败判据：产物空文档 → 原生兜底卡。
    private var assembledHtml: String? {
        // Kotlin object 经 K/N 导出为 .shared 单例
        let html = EngineerTaskHtml.shared.assemble(
            task: task,
            expanded: expanded,
            palette: palette,
            texts: texts)
        return html.isEmpty ? nil : html
    }

    private var emptyMeta: HtmlCardMeta {
        HtmlCardMeta(display: nil, displayMode: nil, measuredHeightPx: nil, summary: nil)
    }

    // MARK: - 调色板（DesignTokens 当前 scheme 取色 → hex；字段口径 = EngineerTaskPalette 注释）

    private var palette: EngineerTaskPalette {
        EngineerTaskPalette(
            cardBg: s.surfaceContainer.hexRgb,          // 卡底（scheme/surfaceContainer）
            iconBlockBg: s.surfaceContainerHigh.hexRgb, // ⚙ 图标块底（surfaceContainerHigh）
            primary: s.primary.hexRgb,
            onPrimary: s.onPrimary.hexRgb,
            error: s.error.hexRgb,
            onError: s.onError.hexRgb,
            onSurface: s.onSurface.hexRgb,
            onSurfaceVariant: s.onSurfaceVariant.hexRgb,
            surfaceVariant: s.surfaceVariant.hexRgb,    // 进度条轨道底
            outlineVariant: s.outlineVariant.hexRgb,    // 分隔线
            neutralChipBg: s.surfaceContainerHigh.hexRgb,   // 中性 chip（COMPLETED/resolved）
            neutralChipFg: s.onSurfaceVariant.hexRgb)
    }

    // MARK: - 模板文案（Localizable 预解析 + 数值格式化；null = 数据缺省整行隐藏）

    private var texts: EngineerTaskTexts {
        let turns = Int(task.turns)
        let costCents = task.costCents.map { Int(truncating: $0) }
        let turnsText = turns > 0 ? EngineerTaskFormat.turnsText(turns) : nil
        let costText = costCents.map { EngineerTaskFormat.costText(cents: $0) }
        return EngineerTaskTexts(
            titlePrefix: L("chat_task_title_prefix"),
            badgeLabel: L("chat_task_badge"),
            chipRunning: L("chat_task_status_running"),
            chipAwaiting: L("chat_task_status_awaiting"),
            chipCompleted: L("chat_task_status_completed"),
            chipFailed: L("chat_task_status_failed"),
            chipResolvedContinued: L("chat_task_resolved_continued"),
            chipResolvedAbandoned: L("chat_task_resolved_abandoned"),
            chipResolvedDelivered: L("chat_task_resolved_delivered"),
            chipResolvedSkipped: L("chat_task_resolved_skipped"),
            expandHint: L("chat_task_expand"),
            collapseHint: L("chat_task_collapse"),
            recentEventsCaption: L("chat_task_recent_events"),
            metaTurns: turnsText,
            metaElapsed: EngineerTaskFormat.elapsed(from: task.startedAtMs, to: task.updatedAtMs),
            metaCost: costText,
            reasonPrefix: L("chat_task_reason_prefix"),
            usedPrefix: L("chat_task_used_prefix"),
            summaryPrefix: L("chat_task_summary_prefix"),
            usedTurns: turnsText,
            usedCost: costText,
            filesChanged: task.fileChangeCount > 0
                ? EngineerTaskFormat.filesChangedText(Int(task.fileChangeCount))
                : nil,
            deliverSummary: task.status == .awaitingDeliver
                ? String(format: L("chat_task_deliver_summary"), Int64(task.fileChangeCount))
                : nil)
    }

    // MARK: - 原生兜底卡（组装失败：状态 chip + 标题 + 阶段行）

    private var nativeFallbackCard: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            HStack(spacing: Spacing.sm) {
                EngineerTaskStatusChip(status: task.status, resolution: task.resolution)
                Text(L("chat_task_title_prefix") + task.sourceText)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(s.onSurface)
                    .lineLimit(2)
            }
            if let stage = task.stage?
                .trimmingCharacters(in: .whitespacesAndNewlines), !stage.isEmpty {
                Text(String(format: L("chat_task_stage"), stage))
                    .font(.system(size: 12))
                    .foregroundColor(s.primary)
            }
        }
        .padding(Spacing.md)
        .frame(maxWidth: ChatBubbleTokens.bubbleMaxWidth, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.card))
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Color → hex（EngineerTaskPalette hex 字段形态 "#RRGGBB"）

extension Color {
    /// sRGB 直读转 6 位 hex（palette 消费侧专用；token 色（SchemeColors）均为 sRGB 实色）。
    var hexRgb: String {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        UIColor(self).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return String(
            format: "#%02X%02X%02X",
            Int(round(red * 255)), Int(round(green * 255)), Int(round(blue * 255)))
    }
}

#if DEBUG
#Preview("RUNNING / 待交付 / 失败") {
    VStack(spacing: Spacing.md) {
        EngineerTaskCardView(
            task: TaskCenterStore.previewEngineerTask(id: "t1", status: .running),
            onOpenFullpage: { _, _ in }, onOpenLink: { _ in })
        EngineerTaskCardView(
            task: TaskCenterStore.previewEngineerTask(id: "t2", status: .awaitingDeliver),
            onOpenFullpage: { _, _ in }, onOpenLink: { _ in })
        EngineerTaskCardView(
            task: TaskCenterStore.previewEngineerTask(id: "t3", status: .failed),
            onOpenFullpage: { _, _ in }, onOpenLink: { _ in })
    }
    .padding()
    .background(Color(.systemBackground))
}
#endif
