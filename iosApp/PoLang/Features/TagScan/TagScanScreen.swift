import SwiftUI

/// TAG 扫描控制页 v4.1（spec: docs/08-UI-SPECS/screens/tag-control.yaml，2026-10-02 正典）。
///
/// 四段骨架：progBar（本轮 4dp 流光）→ top_bar（标题 + 会话 x/y + ETA 副行）→
/// content（ringHero 全库仪表环 + 六格成果面板 + 互斥卡 + 阶段行）→ bottom_bar（52dp 主/次双钮）。
/// guard_banner 为 Android 平台概念（保活引导），iOS 无后台扫描 → 不渲染（台账 N/A）。
/// 嵌入形态：OrganizeHome 胶囊双 tab 宿主（embedded=true，胶囊即唯一页头，无返回箭头）；
/// 独立形态：相册/设置 fullScreenCover 入口（embedded=false，top_bar 前置返回钮）。
struct TagScanScreen: View {
    @StateObject private var vm = TagScanViewModel()
    @Environment(\.colorScheme) private var cs

    /// 由父视图注入的关闭回调（embedded=宿主切回整理 tab；cover=关闭浮层）。
    var onDismiss: (() -> Void)? = nil
    /// true = OrganizeHome 胶囊双 tab 宿主内嵌（胶囊即页头，不渲染返回钮）。
    var embedded: Bool = false

    @State private var showComingSoon = false
    @State private var showPeople = false
    @State private var showCitySheet = false
    @State private var activeStageSheet: TagStage?
    @State private var toast: String? = nil

    // MARK: - 状态机（yaml state_machine → bottom_bar 四态 + 过渡/终态分支）

    /// bottom_bar 驱动相位：idle（无会话/终态零失败回落）/ running / 过渡 / paused / 终态有失败。
    private enum BarPhase { case idle, running, pausing, cancelling, paused, failures }

    private var phase: BarPhase {
        guard let p = vm.progress else { return .idle }
        switch p.state {
        case .idle: return .idle
        case .running: return .running
        case .pausing: return .pausing
        case .cancelling: return .cancelling
        case .paused: return .paused
        case .completed, .cancelled: return p.failed > 0 ? .failures : .idle
        }
    }

    /// 会话可见（计数/进度条持有分数）：活跃/暂停/终态有失败；终态零失败回落 idle 布局。
    private var sessionVisible: Bool {
        switch phase {
        case .idle: return false
        case .running, .pausing, .cancelling, .paused, .failures: return true
        }
    }

    /// 本轮会话分数（progBar 填充；idle 0 宽仅存轨道）。
    private var sessionFraction: CGFloat {
        guard let p = vm.progress, sessionVisible, p.total > 0 else { return 0 }
        return CGFloat(max(0, min(p.processed, p.total))) / CGFloat(p.total)
    }

    /// 上次会话进程死亡对账 → interrupted_card 互斥槽位（无活会话时才提示续扫）。
    private var showInterruptedCard: Bool {
        vm.hasUnfinishedSession && phase == .idle
    }

    var body: some View {
        let s = appScheme(cs)
        VStack(spacing: 0) {
            TagScanProgBar(
                fraction: sessionFraction,
                color: phase == .paused ? StatusColor.warningAmber : s.primary,
                running: phase == .running)
            TagScanTopBar(
                counter: sessionVisible
                    ? "\(vm.progress?.processed ?? 0) / \(vm.progress?.total ?? 0)" : nil,
                subline: topSubline,
                showsBack: !embedded,
                onBack: onDismiss)
            ScrollView {
                VStack(spacing: Spacing.sm) {
                    TagScanRingHeroCard(
                        completion: vm.libraryCompletion,
                        statusKey: ringStatusKey,
                        active: phase == .running || phase == .pausing || phase == .cancelling,
                        running: phase == .running,
                        paused: phase == .paused,
                        stats: vm.stats,
                        onOpenPeople: { showPeople = true },
                        onOpenCitySheet: { showCitySheet = true },
                        onSelfGuide: { showToast(L("tag_result_self_unset")) })
                    if showInterruptedCard {
                        TagScanInterruptedCard(onResume: { vm.resumeUnfinished() })
                    }
                    // mutex_slots：aesthetic_card iOS 无美学评分链 → 恒不渲染（session_control_gap 台账）。
                    TagStageSection(stats: vm.stats, onStageTap: handleStageTap)
                    // guard_banner：BackgroundScanGuard 保活引导为 Android 平台概念（iOS 退后台即
                    // pauseForBackground，无后台扫描）→ 不渲染（platform_differences.guard_banner_na）。
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.top, Spacing.md)
                .padding(.bottom, Spacing.md)
            }
            TagScanBottomBar(
                primaryTitle: L(primaryKey),
                primaryDisabled: primaryDisabled,
                secondaryTitle: L(secondaryKey),
                onPrimary: primaryAction,
                onSecondary: secondaryAction)
            .padding(.horizontal, Spacing.lg)
            .padding(.top, Spacing.md)
            .padding(.bottom, embedded ? 84 : Spacing.md)
        }
        .background(s.background.ignoresSafeArea())
        .onAppear { vm.refreshStats() }
        // 对齐 Android TagGenerationControlScreen 的 1s 轮询（LaunchedEffect while(true) refreshStats）：
        // 扫描中成果格/仪表环/阶段行实时更新；页面离开组合（切 tab/关浮层）自动取消。
        .task {
            while !Task.isCancelled {
                vm.refreshStats()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
        .sheet(item: $activeStageSheet) { stage in
            StageActionSheet(
                stage: stage,
                onRunNew: { runStage(stage, full: false) },
                onRunFull: { runStage(stage, full: true) })
        }
        .sheet(isPresented: $showCitySheet) {
            TagCitySheet(cities: vm.cityGroups()) { _ in
                // TODO(platform gap): 点选城市 → 相册 CITY 视图；iOS 无 GalleryViewFilter 通道
                // （台账登记）。城市回填落地前列表恒空，本出口留待通道落地后接通。
                showCitySheet = false
            }
        }
        .fullScreenCover(isPresented: $showPeople) {
            PersonView(onBack: { showPeople = false })
                .environmentObject(AppContainer.shared)
        }
        .alert(Text("scan_coming_soon_toast"), isPresented: $showComingSoon) {
            Button(L("OK"), role: .cancel) {}
        }
        .alert(Text("scan_models_needed_title"), isPresented: $vm.showModelsNeeded) {
            Button(L("OK"), role: .cancel) {}
        } message: {
            Text("scan_models_needed_msg")
        }
        .overlay(alignment: .bottom) {
            if let toast {
                toastView(toast)
            }
        }
        .task(id: toast) {
            guard toast != nil else { return }
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            self.toast = nil
        }
    }

    // MARK: - top_bar 副行（RUNNING=ETA/进行中；PAUSED=已暂停）

    private var topSubline: String? {
        guard let p = vm.progress else { return nil }
        switch phase {
        case .running:
            return p.estimatedRemainingMs > 0
                ? String(format: L("tag_scan_top_eta"), formatDuration(p.estimatedRemainingMs))
                : L("tag_scan_top_running")
        case .pausing, .cancelling:
            return L("tag_scan_top_running")
        case .paused:
            return L("tag_scan_top_paused")
        case .failures, .idle:
            return nil
        }
    }

    /// 环 hint 尾词：completed=已完成；cancelled/idle=准备开始。
    private var ringStatusKey: String {
        switch vm.progress?.state {
        case .running, .pausing, .cancelling: return "tag_scan_status_running"
        case .paused: return "tag_scan_status_paused"
        case .completed: return "tag_scan_status_done"
        case .cancelled, .idle, nil: return "tag_scan_status_ready"
        }
    }

    // MARK: - bottom_bar 四态（yaml bottom_bar.states）

    private var primaryKey: String {
        switch phase {
        case .idle: return "tag_scan_btn_start"
        case .running: return "tag_scan_btn_pause_scan"
        case .pausing: return "tag_scan_state_pausing"
        case .cancelling: return "tag_scan_state_cancelling"
        case .paused: return "tag_scan_btn_resume_scan"
        case .failures: return "tag_scan_retry_failed_items"
        }
    }

    private var primaryDisabled: Bool {
        phase == .pausing || phase == .cancelling
    }

    private var secondaryKey: String {
        switch phase {
        case .idle: return "tag_scan_btn_later"
        case .paused: return "tag_scan_btn_restart"
        case .running, .pausing, .cancelling, .failures: return "tag_scan_btn_stop"
        }
    }

    private var primaryAction: () -> Void {
        switch phase {
        case .idle: return { vm.startIncremental() }
        case .running: return { vm.pause() }
        case .pausing, .cancelling: return {}
        case .paused: return { vm.resume() }
        case .failures: return { vm.retryFailed() }
        }
    }

    private var secondaryAction: () -> Void {
        switch phase {
        case .idle: return { onDismiss?() }
        case .paused: return { vm.restart() }
        case .running, .pausing, .cancelling, .failures: return { vm.cancel() }
        }
    }

    // MARK: - 阶段分派（点按 = StageActionSheet；QUALITY 行弹「后续版本」）

    private func handleStageTap(_ stage: TagStage) {
        switch stage {
        case .faces, .people, .content:
            activeStageSheet = stage
        case .aesthetic:
            // iOS 美学评分（QUALITY 阶段）恒缺（session_control_gap 台账）→ 「后续版本」提示
            showComingSoon = true
        }
    }

    /// StageActionSheet intents → 既有执行链：
    /// FACE = 增量/全量主链（Pass1→2→3）；PEOPLE = 重聚类（iOS 无 full 变体，两档同源——台账）；
    /// CONTENT = Pass3 增量/全量；QUALITY 不入 Sheet。
    private func runStage(_ stage: TagStage, full: Bool) {
        activeStageSheet = nil
        switch stage {
        case .faces:
            if full { vm.startFull() } else { vm.startIncremental() }
        case .people:
            vm.runPass2()
        case .content:
            if full { vm.startPass3Full() } else { vm.startPass3Incremental() }
        case .aesthetic:
            break
        }
    }

    // MARK: - 轻提示（「我」未标记引导；等价 snackbar）

    private func showToast(_ message: String) {
        withAnimation(.easeInOut(duration: AppMotion.fastMs / 1000)) {
            toast = message
        }
    }

    private func toastView(_ message: String) -> some View {
        let s = appScheme(cs)
        return VStack {
            Spacer()
            Text(message)
                .font(.system(size: 13))
                .foregroundColor(s.onBackground)
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, 10)
                .background(Capsule().fill(s.surfaceContainerHigh.opacity(AppAlpha.surfaceTranslucent)))
                .padding(.bottom, 100)
        }
        .transition(.opacity)
        .allowsHitTesting(false)
    }
}
