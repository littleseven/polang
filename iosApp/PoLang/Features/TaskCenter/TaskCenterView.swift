import SwiftUI
import SharedKit

// MARK: - 任务中心主屏（task-center.yaml，spec v1 2026-10-03）

/// 任务中心：双 Tab（工程师任务 | 后台任务）+ 进行中/历史双分区 + 空态三态。
/// iOS 路由形态 = fullScreenCover（MainNavigationRouter.showTaskCenter，spec route 注记）。
struct TaskCenterView: View {
    var onClose: () -> Void
    /// 工程师列表项整卡点击：回 chat 对应会话（US-15 锚定降级——切会话不逐卡滚动）。
    var onOpenChatSession: (String) -> Void
    /// 后台任务卡整卡点击：destination 导航（TAG_SCAN_CONTROL / MODEL_CENTER）。
    var onNavigateDestination: (UserTaskDestination) -> Void
    #if DEBUG
    /// Preview 样本种子（nil = 生产路径：store 走持久层 + 注册表）。
    var previewSeed: TaskCenterPreviewSeed? = nil
    #endif

    @StateObject private var store = TaskCenterStore()
    /// 手选固定（spec tabs.selection_state.user_pick：手选后脱钩默认落位；nil = 跟随默认）。
    /// UI 自动化可用 launch arg `-taskCenterTab <0|1>` 预选 Tab（对齐 -startPage 先例）。
    @State private var userPickedTab: Int? = {
        let args = ProcessInfo.processInfo.arguments
        guard let idx = args.firstIndex(of: "-taskCenterTab"),
              args.count > idx + 1,
              let tab = Int(args[idx + 1]),
              (0...1).contains(tab) else { return nil }
        return tab
    }()
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    /// 默认落位（spec default_landing：工程师有活跃 → 0；否则后台有活跃 → 1；均无 → 0；
    /// 首查未回（engineerList nil）按 0 活跃计，回流后可纠正——userPickedTab 仍 nil）。
    private var defaultLandingTab: Int {
        if (store.engineerList?.active.count ?? 0) > 0 { return 0 }
        if store.activeUserCount > 0 { return 1 }
        return 0
    }

    private var selectedTab: Int { userPickedTab ?? defaultLandingTab }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(
                title: L("task_center_title"),
                showsBackButton: true,
                onBack: onClose,
                backLabel: L("task_center_title")) { }
            tabBar
            content
        }
        .background(s.surface)
        .onAppear {
            // previewSeed/loadPreview 均为 DEBUG-only（Preview 直灌）；Release 直落生产路径 store.start()
            #if DEBUG
            if let previewSeed {
                store.loadPreview(
                    engineer: previewSeed.engineer,
                    userTasks: previewSeed.userTasks,
                    activeCount: previewSeed.activeUserCount)
                return
            }
            #endif
            store.start()
        }
        .onDisappear { store.stop() }
    }

    // MARK: - 双 Tab（spec tabs：48 高 + 活跃计数徽标；下划线指示器，形制平台差异允许）

    private var tabBar: some View {
        HStack(spacing: 0) {
            tabButton(index: 0, titleKey: "task_center_tab_engineer", badge: engineerBadgeCount)
            tabButton(index: 1, titleKey: "task_center_tab_background", badge: store.activeUserCount)
        }
        .frame(height: 48)
        .overlay(alignment: .bottom) { Divider().overlay(s.outlineVariant) }
    }

    /// 徽标计数口径 = 该 Tab「进行中」分区条数（工程师首查未回不计，回流纠正）。
    private var engineerBadgeCount: Int { store.engineerList?.active.count ?? 0 }

    private func tabButton(index: Int, titleKey: String, badge: Int) -> some View {
        let selected = selectedTab == index
        return Button {
            userPickedTab = index
        } label: {
            VStack(spacing: Spacing.xs) {
                HStack(spacing: Spacing.sm - 2) {   // 标题与徽标间距 6（spec badge.offset）
                    Text(L(titleKey))
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(selected ? s.primary : s.onSurfaceVariant)
                    if badge > 0 {
                        TaskCountBadge(count: badge)
                    }
                }
                Capsule()
                    .fill(selected ? s.primary : .clear)
                    .frame(height: 3)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("task_center_tab_\(index)")
        .accessibilityLabel(L(titleKey))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    // MARK: - 内容区（两 Tab 同构分区）

    @ViewBuilder private var content: some View {
        if selectedTab == 0 {
            engineerTab
        } else {
            userTaskTab
        }
    }

    /// 工程师 Tab（iOS 范围裁定：仅持久化历史 + 空态；无审批动作）。
    @ViewBuilder private var engineerTab: some View {
        if let list = store.engineerList {
            if list.active.isEmpty && list.history.isEmpty {
                // empty_states.engineer_empty：14sp onSurfaceVariant padding 32 居中
                centerMessage(
                    L("task_center_empty"),
                    fontSize: 14,
                    padding: Spacing.xxl)
            } else {
                taskListScroll(
                    active: list.active.map { item in
                        EngineerTaskListRow(item: item) { onOpenChatSession(item.sessionId) }
                            .padding(.horizontal, Spacing.md)
                            .padding(.vertical, Spacing.xs)
                    },
                    history: list.history.map { item in
                        EngineerTaskListRow(item: item) { onOpenChatSession(item.sessionId) }
                            .padding(.horizontal, Spacing.md)
                            .padding(.vertical, Spacing.xs)
                    })
            }
        } else {
            // empty_states.engineer_first_query_pending：首查未回全空白（防空态闪现，anti_patterns #5）
            Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    /// 后台任务 Tab（UserTaskCard 列表：8 间距 / 16 边距）。
    @ViewBuilder private var userTaskTab: some View {
        let sections = store.userTaskSections
        if sections.active.isEmpty && sections.history.isEmpty {
            // empty_states.user_task_empty：bodyMedium onSurfaceVariant 居中
            centerMessage(
                L("task_center_empty_user_tasks"),
                style: AppTypography.bodyMedium,
                padding: Spacing.xxl)
        } else {
            taskListScroll(
                active: sections.active.map { userTaskCard($0) },
                history: sections.history.map { userTaskCard($0) })
        }
    }

    private func userTaskCard(_ task: UserTask) -> some View {
        UserTaskCardView(
            task: task,
            onPerform: { action in store.perform(taskId: task.id, action: action) },
            onTap: { onNavigateDestination(task.destination) })
            .padding(.horizontal, Spacing.lg)
            .padding(.vertical, Spacing.sm / 2)   // spacedBy 8 = 行 4 + 行 4
    }

    /// 双分区列表（sections：active → history；header 13sp SemiBold onSurfaceVariant 16/8 padding）。
    private func taskListScroll<Row: View>(active: [Row], history: [Row]) -> some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if !active.isEmpty {
                    sectionHeader(L("task_center_section_active"))
                    ForEach(Array(active.enumerated()), id: \.offset) { _, row in row }
                }
                if !history.isEmpty {
                    sectionHeader(L("task_center_section_history"))
                    ForEach(Array(history.enumerated()), id: \.offset) { _, row in row }
                }
            }
            .padding(.vertical, Spacing.xs)
        }
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(s.onSurfaceVariant)
            .padding(.horizontal, Spacing.lg)
            .padding(.vertical, Spacing.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("task_center_section")
    }

    private func centerMessage(
        _ text: String, fontSize: CGFloat, padding: CGFloat
    ) -> some View {
        centerMessage(text, style: TypeStyle(size: fontSize, lineHeight: fontSize + 6, weight: .regular, letterSpacing: 0), padding: padding)
    }

    private func centerMessage(_ text: String, style: TypeStyle, padding: CGFloat) -> some View {
        Text(text)
            .font(style.font)
            .foregroundColor(s.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .padding(padding)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("task_center_empty")
    }
}

// MARK: - 计数徽标（Tab 徽标 + Chat 顶栏角标共用）

/// 活跃计数徽标（spec badge / chat_topbar_task_entry.badge：M3 Badge 等价——
/// >0 显示、>99 显示 99+（task_center_badge_overflow）；error 底白字胶囊）。
struct TaskCountBadge: View {
    let count: Int

    @Environment(\.colorScheme) private var cs

    /// 组件级度量（M3 Badge 形制：高 16、最小宽 16、10pt SemiBold）。
    private enum Metrics {
        static let fontSize: CGFloat = 10
        static let minHeight: CGFloat = 16
        static let minWidth: CGFloat = 16
        static let paddingH: CGFloat = 4
    }

    var body: some View {
        Text(count > 99 ? L("task_center_badge_overflow") : "\(count)")
            .font(.system(size: Metrics.fontSize, weight: .semibold))
            .foregroundColor(appScheme(cs).onError)
            .padding(.horizontal, Metrics.paddingH)
            .frame(minWidth: Metrics.minWidth, minHeight: Metrics.minHeight)
            .background(Capsule().fill(appScheme(cs).error))
            .fixedSize()
            .accessibilityHidden(true)   // 计数并入宿主钮 label（cd_task_center_active）
    }
}

// MARK: - 任务中心 → chat 会话跳转（NotificationCenter 解耦）

/// TaskCenterView（fullScreenCover 内）→ ChatView（Pager 兄弟页）会话切换通知：
/// MainTabView 收到后 dismiss cover + 切 Pager 页 2，ChatView 收到后 switchSession
/// （ChatViewModel 为 ChatView 私有 @StateObject，经通知跨层——settingsRequestMainPage 同模式）。
extension Notification.Name {
    static let taskCenterOpenChatSession = Notification.Name("taskCenterOpenChatSession")
}

// MARK: - Preview（swiftui-expert：空态 / 各态样本）

#if DEBUG
/// Preview 样本种子（onAppear 经 store.loadPreview 直灌——store 为视图私有 StateObject）。
struct TaskCenterPreviewSeed {
    let engineer: TaskCenterList?
    let userTasks: [UserTask]
    let activeUserCount: Int

    static var populated: TaskCenterPreviewSeed {
        let items = engineerSamples
        return TaskCenterPreviewSeed(
            engineer: TaskCenterPartition.shared.partition(items: items),
            userTasks: userTaskSamples,
            activeUserCount: 2)
    }

    static var emptySeed: TaskCenterPreviewSeed {
        TaskCenterPreviewSeed(
            engineer: TaskCenterList(active: [], history: []),
            userTasks: [], activeUserCount: 0)
    }

    private static var engineerSamples: [TaskCenterItem] {
        let states: [(EngineerTaskStatus, EngineerTaskResolution?)] = [
            (.running, nil), (.awaitingDeliver, nil), (.completed, nil), (.failed, nil),
        ]
        return states.enumerated().map { index, state in
            TaskCenterItem(
                sessionId: "s\(index)",
                title: "Fix login crash on cold start",
                sessionTitle: "Bug fixes",
                task: TaskCenterStore.previewEngineerTask(
                    id: "t\(index)", status: state.0, resolution: state.1))
        }
    }

    private static var userTaskSamples: [UserTask] {
        [
            TaskCenterStore.previewUserTask(
                id: "tagscan:main", status: .running,
                progress: 0.42, progressText: "128/500", etaMs: 930_000,
                supportedActions: [.pause, .cancel]),
            TaskCenterStore.previewUserTask(
                id: "download:qwen", kind: .modelDownload, displayName: "Qwen3-VL-2B",
                status: .running, progressText: "1.2 GB / 2.0 GB",
                supportedActions: [.pause, .cancel],
                destination: .modelCenter),
            TaskCenterStore.previewUserTask(
                id: "tagscan:old", status: .completed),
        ]
    }
}

#Preview("样本数据") {
    TaskCenterView(
        onClose: {},
        onOpenChatSession: { _ in },
        onNavigateDestination: { _ in },
        previewSeed: TaskCenterPreviewSeed.populated)
        .environment(\.locale, .init(identifier: "zh-Hans"))
}

#Preview("空态") {
    TaskCenterView(
        onClose: {},
        onOpenChatSession: { _ in },
        onNavigateDestination: { _ in },
        previewSeed: TaskCenterPreviewSeed.emptySeed)
        .environment(\.locale, .init(identifier: "en"))
}
#endif
