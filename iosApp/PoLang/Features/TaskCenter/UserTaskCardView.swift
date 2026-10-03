import SwiftUI
import SharedKit

// MARK: - 后台任务卡（task-center.yaml §user_task_card，用户任务协议 §7）

/// UserTaskCard：类型图标 tint=状态色 + 标题 + 状态字 + 进度区（仅活跃态）+ 错误行 +
/// 声明式动作行（supportedActions 按 PAUSE/RESUME/CANCEL/RETRY ordinal 排序）。
/// 整卡点击 → destination 导航（TAG_SCAN_CONTROL→扫描控制 / MODEL_DOWNLOAD→模型中心）。
/// 🔴 原生组件渲染（spec anti_patterns #4：多卡并存禁 WebView）。
struct UserTaskCardView: View {
    let task: UserTask
    var onPerform: (UserTaskAction) -> Void
    var onTap: () -> Void

    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    /// 状态色规则（spec status_color_rule，与顶栏角标同口径的活跃标记）。
    private var statusColor: Color {
        switch task.status {
        case .failed: return s.error
        case .pending, .running, .paused: return s.primary
        case .completed, .cancelled: return s.onSurfaceVariant
        }
    }

    private var statusText: String {
        switch task.status {
        case .pending: return L("user_task_status_pending")
        case .running: return L("user_task_status_running")
        case .paused: return L("user_task_status_paused")
        case .completed: return L("user_task_status_completed")
        case .failed: return L("user_task_status_failed")
        case .cancelled: return L("user_task_status_cancelled")
        }
    }

    /// 标题：displayName（体系自带名，原样展示不参与 i18n）?: kind 默认标题。
    private var title: String {
        if let name = task.displayName, !name.isEmpty { return name }
        return task.kind == .tagScan
            ? L("user_task_title_tag_scan")
            : L("user_task_title_model_download")
    }

    /// 类型图标（spec row1.kind_icon.mapping：TAG_SCAN→ic/sync / MODEL_DOWNLOAD→ic/download；
    /// 资产以 mat_o_autorenew/mat_download 就位——sync 字形以 autorenew 代位，台账登记）。
    private var kindIconName: String {
        task.kind == .tagScan ? "mat_o_autorenew" : "mat_download"
    }

    /// 声明式动作（ordinal 排序 = PAUSE/RESUME/CANCEL/RETRY；空集合不渲染）。
    private var orderedActions: [UserTaskAction] {
        let ordinal: [UserTaskAction] = [.pause, .resume, .cancel, .retry]
        return ordinal.filter { task.supportedActions.contains($0) }
    }

    /// ETA 文案（user_task_eta_remaining；格式 ~Xh Ym / ~Xm / <1m；etaMs ≤0 或 null 不渲染）。
    private var etaText: String? {
        guard let etaMs = task.etaMs?.int64Value, etaMs > 0 else { return nil }
        let minutes = Int(etaMs / 60_000)
        if minutes >= 60 { return "\(minutes / 60)h \(minutes % 60)m" }
        if minutes >= 1 { return "\(minutes)m" }
        return "<1m"
    }

    /// 错误行文案（errorCode 非空时渲染）。
    private var errorText: String? {
        switch task.errorCode {
        case .processTerminated:
            return L("user_task_error_process_terminated")
        case .partialFailures:
            return String(format: L("user_task_error_partial_failures"), task.errorDetail ?? "")
        case .modelUnavailable:
            // 不拼 errorDetail——displayName 已是 modelId（spec error_row.keys）
            return L("user_task_error_model_unavailable")
        case nil:
            return nil
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // row1：图标 + 标题（weight 1f）+ 状态字
            HStack(spacing: Spacing.sm - 2) {
                MatIcon(name: kindIconName, size: 16)
                    .foregroundColor(statusColor)
                    .accessibilityHidden(true)   // decorative
                Text(title)
                    .font(AppTypography.titleSmall.font)
                    .foregroundColor(s.onSurface)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: Spacing.sm)
                Text(statusText)
                    .font(AppTypography.labelSmall.font)
                    .foregroundColor(statusColor)
            }

            // 进度区（仅活跃态渲染）
            if TaskCenterStore.isActive(task) {
                progressSection
            }

            // 错误行
            if let errorText {
                Text(errorText)
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.error)
                    .padding(.top, Spacing.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            // 动作行（TextButton 右对齐）
            if !orderedActions.isEmpty {
                HStack(spacing: Spacing.sm) {
                    Spacer()
                    ForEach(orderedActions, id: \.self) { action in
                        Button {
                            onPerform(action)
                        } label: {
                            Text(actionLabel(action))
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(s.primary)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("task_center_user_action_\(task.id)_\(actionLabelKey(action))")
                    }
                }
                .padding(.top, Spacing.xs)
            }
        }
        .padding(.horizontal, Spacing.lg)     // 16
        .padding(.vertical, Spacing.md)       // 12
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHighest)   // M3 filled Card 容器色
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.panel, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: AppRadius.panel, style: .continuous))
        .onTapGesture { onTap() }   // Button 子视图手势优先，不与动作行冲突
        .accessibilityIdentifier("task_center_user_card_\(task.id)")
        .accessibilityLabel(String(format: L("cd_user_task_card"), title))
    }

    /// 进度区（spacing_top 8）：全宽进度条（progress null → 不定进度）+ 文本行（spacing_top 4）。
    private var progressSection: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            if let progress = task.progress?.floatValue {
                ProgressView(value: Double(progress))
                    .progressViewStyle(.linear)
                    .tint(s.primary)
            } else {
                ProgressView()
                    .progressViewStyle(.linear)
                    .tint(s.primary)
            }
            HStack(spacing: Spacing.sm) {
                if let progressText = task.progressText {
                    Text(progressText)
                        .font(AppTypography.bodySmall.font)
                        .foregroundColor(s.onSurface)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Spacer(minLength: Spacing.xs)
                if let etaText {
                    Text(String(format: L("user_task_eta_remaining"), etaText))
                        .font(AppTypography.bodySmall.font)
                        .foregroundColor(s.onSurfaceVariant)
                }
            }
        }
        .padding(.top, Spacing.sm)
    }

    private func actionLabel(_ action: UserTaskAction) -> String {
        switch action {
        case .pause: return L("user_task_action_pause")
        case .resume: return L("user_task_action_resume")
        case .cancel: return L("user_task_action_cancel")
        case .retry: return L("user_task_action_retry")
        }
    }

    private func actionLabelKey(_ action: UserTaskAction) -> String {
        switch action {
        case .pause: return "pause"
        case .resume: return "resume"
        case .cancel: return "cancel"
        case .retry: return "retry"
        }
    }
}

// MARK: - Preview 样本

#if DEBUG
extension TaskCenterStore {

    /// 样本工厂（nonisolated：纯构造，供 Preview 非隔离上下文调用）。
    nonisolated static func previewUserTask(
        id: String,
        kind: UserTaskKind = .tagScan,
        displayName: String? = nil,
        status: UserTaskStatus,
        progress: Float? = nil,
        progressText: String? = nil,
        etaMs: Int64? = nil,
        errorCode: UserTaskErrorCode? = nil,
        errorDetail: String? = nil,
        supportedActions: Set<UserTaskAction> = [],
        destination: UserTaskDestination = .tagScanControl
    ) -> UserTask {
        UserTask(
            id: id,
            kind: kind,
            displayName: displayName,
            status: status,
            progress: progress.map { KotlinFloat(float: $0) },
            progressText: progressText,
            etaMs: etaMs.map { KotlinLong(longLong: $0) },
            errorCode: errorCode,
            errorDetail: errorDetail,
            supportedActions: supportedActions,
            destination: destination,
            updatedAt: 1_760_000_000_000)
    }
}
#endif

#if DEBUG
#Preview("后台任务卡各态") {
    ScrollView {
        VStack(spacing: Spacing.sm) {
            UserTaskCardView(
                task: TaskCenterStore.previewUserTask(
                    id: "tagscan:main", status: .running,
                    progress: 0.42, progressText: "128/500", etaMs: 930_000,
                    supportedActions: [.pause, .cancel]),
                onPerform: { _ in }, onTap: {})
            UserTaskCardView(
                task: TaskCenterStore.previewUserTask(
                    id: "download:qwen", kind: .modelDownload, displayName: "Qwen3-VL-2B",
                    status: .paused, progressText: "1.2 GB / 2.0 GB",
                    supportedActions: [.resume, .cancel]),
                onPerform: { _ in }, onTap: {})
            UserTaskCardView(
                task: TaskCenterStore.previewUserTask(
                    id: "tagscan:fail", status: .failed,
                    errorCode: .processTerminated,
                    supportedActions: [.retry]),
                onPerform: { _ in }, onTap: {})
            UserTaskCardView(
                task: TaskCenterStore.previewUserTask(
                    id: "download:x", status: .completed),
                onPerform: { _ in }, onTap: {})
        }
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical)
    }
    .background(Color(.systemBackground))
}
#endif
