import SwiftUI

// MARK: - 整理+扫描合并页（spec organize.yaml §0 container: organize_home_route）
// 主页面 Pager 页：顶部胶囊分段开关「整理 / 扫描」双 Tab；
// 整理 Tab = OrganizeHubView（本文件下方路由），扫描 Tab = 内嵌既有 TagScanScreen。

struct OrganizeHomeView: View {
    enum Segment: String, Hashable {
        case organize
        case scan
    }

    /// 页内 tab 记忆（对齐 Android MainPagerHost organizeTab rememberSaveable）：
    /// 记住上次选择，进入整理页不强制重置；扫描中落 SCAN 由 scanLandingRequested 一次性驱动。
    @AppStorage("organize_home_segment") private var segment: Segment = .organize
    /// 底 bar 点整理项且扫描中 → MainTabView 置 true → 本页消费（落 SCAN tab 后清零）。
    /// 一次性请求语义对齐 Android organizeTabRequest（main-nav.yaml §1 special_routing；
    /// 非底 bar 入口不触发）。
    @Binding var scanLandingRequested: Bool
    /// 类目详情二级页路由（→ T5 OrganizeCategoryScreen，spec §3）。
    /// 不对 OrganizeCategory 加 Identifiable 扩展（避免与并行任务撞名）：bool + 存储值驱动。
    @State private var categoryRoute: OrganizeCategory?
    @State private var showCategory = false
    /// 「滑动整理」二级页（→ T6 SwipeReviewScreen，spec §5）。
    @State private var showSwipeReview = false
    /// 容器级持有：hub 视图随分段切换销毁重建，回填链不重启（防并发双循环）。
    @StateObject private var hubVm = OrganizeHubViewModel()
    @Environment(\.colorScheme) private var cs

    init(scanLandingRequested: Binding<Bool> = .constant(false)) {
        _scanLandingRequested = scanLandingRequested
    }

    var body: some View {
        VStack(spacing: 0) {
            segmentControl
            switch segment {
            case .organize:
                OrganizeHubView(
                    vm: hubVm,
                    onOpenCategory: { category in
                        categoryRoute = category
                        showCategory = true
                    },
                    onOpenSwipeReview: { showSwipeReview = true },
                    // 引导卡点击仅切 Tab，不预选扫描项（spec §8 裁剪项 spec_6_1_preselect_scan_item）
                    onOpenScan: { switchSegment(.scan) }
                )
            case .scan:
                // TagScanScreen v4.1 内嵌（embedded：胶囊即唯一页头，不渲染返回箭头；
                // 「稍后」= 切回整理 Tab）
                TagScanScreen(onDismiss: { switchSegment(.organize) }, embedded: true)
            }
        }
        .background(appScheme(cs).background.ignoresSafeArea())
        // 底 bar 智能落点：一次性请求消费（onAppear 兜底视图懒组合场景——请求仅在底 bar
        // 点整理项时置位，onAppear 消费不破坏「滑动进页不落 SCAN」的对齐语义）
        .onChange(of: scanLandingRequested) { _ in consumeScanLanding() }
        .onAppear { consumeScanLanding() }
        // 二级页返回后重建看板（类目页删除/滑动整理提交都会改数据）
        .fullScreenCover(isPresented: $showCategory, onDismiss: refreshBoard) {
            if let category = categoryRoute {
                OrganizeCategoryScreen(category: category)
            }
        }
        .fullScreenCover(isPresented: $showSwipeReview, onDismiss: refreshBoard) {
            SwipeReviewScreen()
        }
    }

    private func refreshBoard() {
        Task { await hubVm.reload() }
    }

    /// 消费一次性落 SCAN 请求（请求为 true 才动作；MainTabView 已在底 bar 点击时判定 isScanning）。
    private func consumeScanLanding() {
        guard scanLandingRequested else { return }
        scanLandingRequested = false
        switchSegment(.scan)
    }

    private func switchSegment(_ target: Segment) {
        withAnimation(.easeInOut(duration: AppMotion.fastMs / 1000)) {
            segment = target
        }
    }

    // MARK: 顶部胶囊分段开关「整理 / 扫描」

    private var segmentControl: some View {
        let s = appScheme(cs)
        return HStack(spacing: Spacing.xs) {
            segmentButton(
                title: String(localized: "org_title"),
                id: "organize",
                isSelected: segment == .organize
            ) { switchSegment(.organize) }
            segmentButton(
                title: String(localized: "tag_section_scan"),
                id: "scan",
                isSelected: segment == .scan
            ) { switchSegment(.scan) }
        }
        .padding(Spacing.xs)
        .background(Capsule().fill(s.surfaceContainerHigh))
        .padding(.horizontal, Spacing.lg)
        .padding(.top, Spacing.sm)
        .padding(.bottom, Spacing.xs)
    }

    private func segmentButton(
        title: String,
        id: String,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        let s = appScheme(cs)
        return Button(action: action) {
            Text(title)
                .font(AppTypography.labelLarge.font)
                .fontWeight(isSelected ? .semibold : .regular)
                .foregroundColor(isSelected ? s.onSurface : s.onSurfaceVariant)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.sm)
                .background(
                    Group {
                        if isSelected {
                            Capsule().fill(s.surface)
                        } else {
                            Color.clear
                        }
                    }
                )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("organize_segment_\(id)")
    }
}
