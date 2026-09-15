import SwiftUI

// MARK: - 整理中心类目详情屏（organize.yaml §3 category_grid + §4 cleaned）
//
// 契约 SSOT：docs/08-UI-SPECS/screens/organize.yaml §3（category_grid）/ §4（cleaned）/
// §1（领域口径）/ §8（平台差异与裁剪项）。🔴 不读 Android 源码翻译 UI。
// 对外契约（T4 引用）：OrganizeCategoryScreen(category:) 自包含——自建 ViewModel，
// 内部经 OrganizeRepository.loadItems() 全量快照 + OrganizeCategorizer.classifyAll 管线。
//
// 平台差异（spec §8 platform_differences，勿「修复」对齐）：
// - 删除通路 = PHAssetChangeRequest.deleteAssets（最近删除 30 天；系统强制确认框，
//   无 Android createTrashRequest/MANAGE_MEDIA 静默等价）；
// - iOS 无 Undo：无 API 从「最近删除」程序化恢复，cleaned 屏 Undo 整段裁剪；
// - Coil size 360 / crossfade false 为 Android 工程约束，iOS 用等效缩略图加载。

// MARK: - 本屏量化常量（spec §3/§4 给定值；通用值走 DesignTokens）

private enum OrganizeCategoryTokens {
    static let gridColumns = 3
    /// spec §3.grid.thumb.coil size_px = 360（iOS 等效缩略图请求档）
    static let thumbRequestPx: CGFloat = 360
    // selection_badge（spec §3.grid.selection_badge）
    static let selectionBadgeSize: CGFloat = 22
    static let selectionBadgeCheckSize: CGFloat = 14
    static let selectionBadgeMargin: CGFloat = 6
    static let selectionBadgeBorderWidth: CGFloat = 1.5
    // protected_badge（spec §3.grid.protected_badge）
    static let protectedBadgeIconSize: CGFloat = 16
    static let protectedBadgeInnerPadding: CGFloat = 2
    // cleaned（spec §4）
    static let cleanedIconSize: CGFloat = 64
    static let freedNumberFontSize: CGFloat = 34
    // delete bar（spec §3.bottom_bar）
    static let deleteButtonHeight: CGFloat = 48
    static let snackbarDurationMs: UInt64 = 3_000
}

// MARK: - ViewModel

/// 类目详情状态机（spec §3.states）+ 选择语义（spec §3.selection_semantics）。
@MainActor
final class OrganizeCategoryViewModel: ObservableObject {

    enum Phase: Equatable {
        case loading            // 居中 spinner
        case ready              // 三段分组网格
        case empty              // 居中 org_empty_category
        case cleaned            // 清空完成态（spec §4，category_grid 的 trashed 分支）
    }

    enum SectionId: String, Identifiable {
        case suggested          // 建议删除 = HIGH 置信且非 protected
        case review             // 请确认 = MEDIUM+LOW 且非 protected
        case protected          // 可能是珍贵照片 = protected（无论置信度）
        var id: String { rawValue }
    }

    struct GridSection: Identifiable {
        let id: SectionId
        let items: [ClassifiedItem]
    }

    @Published private(set) var phase: Phase = .loading
    /// 三段分组（互斥且完备；空段不占位，spec §3.grid.sections）
    @Published private(set) var sections: [GridSection] = []
    @Published private(set) var selection: Set<String> = []
    /// AI 预选开关（默认开：进入即勾选 suggested 段，spec §3.sections.suggested.preselected）
    @Published private(set) var isAiPreselectOn = true
    /// cleaned 态累计口径（跨批累加：org_cleaned_meta 计数 / org_cleaned_freed 字节）
    @Published private(set) var trashedCount = 0
    @Published private(set) var freedBytes: Int64 = 0
    /// org_partial_trash 一次性提示（部分失败残留，spec §3.trash_flow.partial_notice）
    @Published private(set) var partialTrashNotice: String?
    @Published private(set) var isTrashing = false

    let category: OrganizeCategory

    private let repository: OrganizeRepository
    private let trashService: IosTrashService

    init(
        category: OrganizeCategory,
        repository: OrganizeRepository = .shared,
        trashService: IosTrashService = IosTrashService()
    ) {
        self.category = category
        self.repository = repository
        self.trashService = trashService
    }

    // MARK: 派生统计

    /// 该类目全量（含 protected），subtitle 左侧 org_cat_meta 口径
    var totalCount: Int { sections.reduce(0) { $0 + $1.items.count } }
    var totalBytes: Int64 {
        sections.reduce(Int64(0)) { acc, section in
            acc + section.items.reduce(Int64(0)) { $0 + $1.item.sizeBytes }
        }
    }
    var selectedCount: Int { selection.count }
    /// 选中项字节和（delete bar CTA 文案 org_move_to_trash 第二参数）
    var selectedBytes: Int64 {
        sections.reduce(Int64(0)) { acc, section in
            acc + section.items
                .filter { selection.contains($0.item.uri) }
                .reduce(Int64(0)) { $0 + $1.item.sizeBytes }
        }
    }

    // MARK: 加载

    func load() async {
        guard phase == .loading else { return }
        let items = await repository.loadItems()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // 全库裁定（CPU 密集）离主线程（对齐 loadItems 的 Task.detached 口径）
        let targetCategory = category
        let entries = await Task.detached(priority: .utility) { () -> [ClassifiedItem] in
            // duplicates_guard（spec §3.selection_semantics）：按 uri 去重防同 key 重复行硬崩网格；
            // 保留最后一条快照（与 SwipeQueueBuilder.dedup_by_uri 同防线，spec §5）。
            var byUri: [String: ClassifiedItem] = [:]
            for entry in OrganizeCategorizer.classifyAll(items, now: now)
            where entry.category == targetCategory {
                byUri[entry.item.uri] = entry
            }
            return Array(byUri.values)
        }.value
        apply(entries)
        phase = sections.isEmpty ? .empty : .ready
        if phase == .ready {
            // AI 预选默认开 → 进入即勾选建议段（扣 keeper）
            selection = preselectTargets()
        }
    }

    /// 三段分组重组（captureDate 降序 + uri 升序兜底，保证删除部分项后序稳定不闪换）
    private func apply(_ entries: [ClassifiedItem]) {
        let sorted = entries.sorted { lhs, rhs in
            if lhs.item.captureDate != rhs.item.captureDate {
                return lhs.item.captureDate > rhs.item.captureDate
            }
            return lhs.item.uri < rhs.item.uri
        }
        let suggested = sorted.filter { $0.confidence == .high && !$0.isProtected }
        let review = sorted.filter { $0.confidence != .high && !$0.isProtected }
        let protected = sorted.filter { $0.isProtected }
        var next: [GridSection] = []
        if !suggested.isEmpty { next.append(GridSection(id: .suggested, items: suggested)) }
        if !review.isEmpty { next.append(GridSection(id: .review, items: review)) }
        if !protected.isEmpty { next.append(GridSection(id: .protected, items: protected)) }
        sections = next
    }

    // MARK: 选择语义（spec §3.selection_semantics）

    /// DUPLICATES keeper 排除钩子（spec §3.keeper_rule）：按 exactDupGroupKey 分组，
    /// 每组排除 1 张 keeper = 组内 captureDate 最新者（同值按 uri 字典序取大求稳定）；
    /// 无组标识成员（similar-only）不参与排除。
    /// 本批 exactDupGroupKey 恒 nil（OrganizeRepository T8 裁剪注记，DUPLICATES 不命中），
    /// 恒返回空集——管线钩子就位，dedup 扫描器落地后自动生效，无需改本屏。
    private static func keeperUris(_ entries: [ClassifiedItem]) -> Set<String> {
        let grouped = Dictionary(
            grouping: entries.filter { $0.item.exactDupGroupKey != nil },
            by: { $0.item.exactDupGroupKey! }
        )
        var keepers = Set<String>()
        for group in grouped.values where group.count >= 2 {
            if let keeper = group.max(by: { lhs, rhs in
                if lhs.item.captureDate != rhs.item.captureDate {
                    return lhs.item.captureDate < rhs.item.captureDate
                }
                return lhs.item.uri < rhs.item.uri
            }) {
                keepers.insert(keeper.item.uri)
            }
        }
        return keepers
    }

    /// AI 预选目标（spec §3.top_bar ai_preselect_toggle.behavior）：
    /// 开 = suggested 段（HIGH 非保护）再按组留 keeper。
    private func preselectTargets() -> Set<String> {
        guard let suggested = sections.first(where: { $0.id == .suggested }) else { return [] }
        return Set(suggested.items.map(\.item.uri))
            .subtracting(Self.keeperUris(suggested.items))
    }

    /// 顶栏「AI 预选」开关：开 = 立即勾选建议段；关 = 立即清空（语义对称，手选项需重新点选）
    func setAiPreselect(_ on: Bool) {
        guard phase == .ready, isAiPreselectOn != on else { return }
        isAiPreselectOn = on
        selection = on ? preselectTargets() : []
    }

    /// 段级全选语义（spec §3.selection_semantics.select_all）：仅圈非 protected 项，
    /// DUPLICATES 同按组留 keeper（与 hub 可释放口径对偶，防全选删光整组）。
    /// ⚠️ 本批无 UI 调用方（spec §8 spec_6_3_section_select_all 明示裁剪），VM 就绪待接线。
    func selectAll() {
        guard phase == .ready else { return }
        let candidates = sections.flatMap(\.items).filter { !$0.isProtected }
        selection = Set(candidates.map(\.item.uri))
            .subtracting(Self.keeperUris(candidates))
    }

    func deselectAll() {
        guard phase == .ready else { return }
        selection = []
    }

    /// 格点点击 = toggle_selection（protected 项可手选，仅预选/全选排除，spec §3 语义）
    func toggleSelection(uri: String) {
        guard phase == .ready else { return }
        if selection.contains(uri) {
            selection.remove(uri)
        } else {
            selection.insert(uri)
        }
    }

    // MARK: trash_flow（spec §3.bottom_bar.trash_flow）

    func trashSelected() {
        guard phase == .ready, !selection.isEmpty, !isTrashing else { return }
        let uris = Array(selection)
        isTrashing = true
        trashService.moveToTrash(uris) { result in
            // IosTrashService 保证 completion 恒在主线程；此处再显式跳 MainActor 域
            Task { @MainActor [weak self] in
                self?.handleTrashResult(result)
            }
        }
    }

    private func handleTrashResult(_ result: TrashBatchResult) {
        isTrashing = false
        // 全量失败 = 用户在系统确认框点取消（iOS performChanges 原子提交，整批不发生，
        // 见 IosTrashService 注记）→ 留原地可重试（spec §3.trash_flow.outcome「Cancelled」）。
        // org_trash_unsupported（Android API<30 分支）iOS 不适用。
        let deleted = Set(result.deleted)
        guard !deleted.isEmpty else { return }

        let allItems = sections.flatMap(\.items)
        let removed = allItems.filter { deleted.contains($0.item.uri) }
        let remaining = allItems.filter { !deleted.contains($0.item.uri) }
        trashedCount += removed.count
        freedBytes += removed.reduce(Int64(0)) { $0 + $1.item.sizeBytes }
        apply(remaining)
        selection.subtract(deleted)

        if sections.isEmpty {
            // 剔除已回收项后清空 → 进 cleaned 态（spec §3.trash_flow.outcome + §4）
            phase = .cleaned
            selection = []
        } else if !result.failed.isEmpty {
            // 部分失败：snackbar org_partial_trash（一次性事件）。iOS 原子通路理论不可达，
            // TrashBatchResult 预留部分失败语义，防御性承接。
            partialTrashNotice = String(localized: "org_partial_trash")
        }
    }

    func clearPartialTrashNotice() {
        partialTrashNotice = nil
    }
}

// MARK: - 类目名（spec §1 categories.label_key；与 hub 卡同源）

extension OrganizeCategory {
    /// 类目文案键（organizeCategoryLabelRes 等价；⚠️ 枚举名 ≠ 键名，见 LOW_QUALITY_* 两例）
    var organizeLabelKey: String {
        switch self {
        case .duplicates: return "org_cat_duplicates"
        case .screenContent: return "org_cat_screen_content"
        case .documents: return "org_cat_documents"
        case .lowQualityPortraits: return "org_cat_portraits"
        case .lowQualityPhotos: return "org_cat_blurry"
        case .largeFiles: return "org_cat_large_files"
        }
    }
}

// MARK: - 字节格式化

/// 对齐 Android formatBytes 口径：自适应单位；0 字节兜底 "0 B"（ByteCountFormatter 0 值文案不可控）。
private enum OrganizeByteFormatter {
    private static let formatter: ByteCountFormatter = {
        let f = ByteCountFormatter()
        f.countStyle = .file
        f.isAdaptive = true
        return f
    }()

    static func string(_ bytes: Int64) -> String {
        bytes > 0 ? formatter.string(fromByteCount: bytes) : "0 B"
    }
}

// MARK: - Screen（对外契约：T4 以 OrganizeCategoryScreen(category:) 引用）

struct OrganizeCategoryScreen: View {
    let category: OrganizeCategory

    /// 父视图注入的关闭回调（fullScreenCover/sheet 注入）；缺省走环境 dismiss
    /// （NavigationStack push 场景自动 pop），自包含两用。
    var onDismiss: (() -> Void)? = nil

    @StateObject private var vm: OrganizeCategoryViewModel
    @Environment(\.colorScheme) private var cs
    @Environment(\.dismiss) private var dismiss

    init(category: OrganizeCategory, onDismiss: (() -> Void)? = nil) {
        self.category = category
        self.onDismiss = onDismiss
        _vm = StateObject(wrappedValue: OrganizeCategoryViewModel(category: category))
    }

    var body: some View {
        let s = appScheme(cs)
        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                topBar
                content
            }
        }
        .background(s.background.ignoresSafeArea())
        .task { await vm.load() }
    }

    // MARK: 顶栏（spec §3.top_bar：标题 = 类目名 + 返回 + AI 预选文字开关）

    private var topBar: some View {
        let s = appScheme(cs)
        return AppTopBar(
            title: String(localized: String.LocalizationValue(category.organizeLabelKey)),
            showsBackButton: true,
            onBack: {
                if let onDismiss {
                    onDismiss()
                } else {
                    dismiss()
                }
            }
        ) {
            // show_when: ready && !trashed（cleaned/empty/loading 态隐藏）
            if vm.phase == .ready {
                Button {
                    vm.setAiPreselect(!vm.isAiPreselectOn)
                } label: {
                    Text(String(localized: String.LocalizationValue(
                        vm.isAiPreselectOn ? "org_ai_preselect_on" : "org_ai_preselect_off")))
                        .font(AppTypography.labelMedium.font)
                        .foregroundColor(s.primary)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("org_ai_preselect_toggle")
            }
        }
    }

    // MARK: 内容状态机

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty:
            emptyView
        case .ready:
            readyGrid
        case .cleaned:
            cleanedContent
        }
    }

    /// empty：居中 org_empty_category（spec §3.states）
    private var emptyView: some View {
        let s = appScheme(cs)
        return Text(String(localized: "org_empty_category"))
            .font(AppTypography.bodyLarge.font)
            .foregroundColor(s.onSurfaceVariant)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: ready：subtitle 行 + 三段分组网格 + delete bar

    private var readyGrid: some View {
        let s = appScheme(cs)
        return VStack(spacing: 0) {
            subtitleRow
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(vm.sections) { section in
                        sectionHeader(section)
                        sectionGrid(section)
                    }
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, Spacing.sm)
            }
            .overlay(alignment: .bottom) {
                // org_partial_trash snackbar（一次性，悬浮于网格底缘、delete bar 上方）
                if let notice = vm.partialTrashNotice {
                    Text(notice)
                        .font(AppTypography.bodyMedium.font)
                        .foregroundColor(s.onSurface)
                        .padding(.horizontal, Spacing.md)
                        .padding(.vertical, Spacing.sm)
                        .background(s.surfaceContainerHighest, in: AppShapes.small)
                        .padding(.bottom, Spacing.sm)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .task(id: notice) {
                            try? await Task.sleep(
                                nanoseconds: OrganizeCategoryTokens.snackbarDurationMs * 1_000_000)
                            vm.clearPartialTrashNotice()
                        }
                        .accessibilityIdentifier("org_partial_trash_snackbar")
                }
            }
            deleteBar
        }
    }

    /// subtitle 行（spec §3.subtitle_row）：左 = 类目全量 meta，右 = 当前选中数
    private var subtitleRow: some View {
        let s = appScheme(cs)
        return HStack(spacing: Spacing.md) {
            Text(String(
                format: String(localized: "org_cat_meta"),
                vm.totalCount, OrganizeByteFormatter.string(vm.totalBytes)))
                .font(AppTypography.bodySmall.font)
                .foregroundColor(s.onSurfaceVariant)
                .lineLimit(1)
            Spacer(minLength: 0)
            Text(String(
                format: String(localized: "org_ai_preselected"), vm.selectedCount))
                .font(AppTypography.bodySmall.font)
                .foregroundColor(s.primary)
        }
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.xs)
    }

    /// 段头（spec §3.grid.sections：titleSmall；protected 段 tertiary 强调价值保护）
    private func sectionHeader(_ section: OrganizeCategoryViewModel.GridSection) -> some View {
        let s = appScheme(cs)
        let key: String
        switch section.id {
        case .suggested: key = "org_section_suggested"
        case .review: key = "org_section_review"
        case .protected: key = "org_section_protected"
        }
        return Text(String(format: String(localized: String.LocalizationValue(key)), section.items.count))
            .font(AppTypography.titleSmall.font)
            .foregroundColor(section.id == .protected ? s.tertiary : s.onSurface)
            .padding(.vertical, Spacing.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("org_section_\(section.id.rawValue)")
    }

    /// 段网格（spec §3.grid：3 列、横纵 spacing 2、content padding h16 v8 由外层承担）
    private func sectionGrid(_ section: OrganizeCategoryViewModel.GridSection) -> some View {
        let columns = Array(
            repeating: GridItem(.flexible(), spacing: GridTokens.spacing),
            count: OrganizeCategoryTokens.gridColumns)
        return LazyVGrid(columns: columns, spacing: GridTokens.spacing) {
            ForEach(section.items, id: \.item.uri) { entry in
                OrganizeThumbCell(
                    entry: entry,
                    isSelected: vm.selection.contains(entry.item.uri)
                ) {
                    vm.toggleSelection(uri: entry.item.uri)
                }
            }
        }
    }

    // MARK: delete bar（spec §3.bottom_bar.delete_bar）

    private var deleteBar: some View {
        let s = appScheme(cs)
        let enabled = vm.selectedCount > 0
        return VStack(spacing: Spacing.xs) {
            Button {
                vm.trashSelected()
            } label: {
                Text(String(
                    format: String(localized: "org_move_to_trash"),
                    vm.selectedCount, OrganizeByteFormatter.string(vm.selectedBytes)))
                    .font(AppTypography.titleMedium.font.weight(.semibold))
                    .foregroundColor(s.onError)
                    .frame(maxWidth: .infinity)
                    .frame(height: OrganizeCategoryTokens.deleteButtonHeight)
                    .background(s.error)
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(!enabled || vm.isTrashing)
            .opacity(enabled && !vm.isTrashing ? 1 : 0.5)
            .accessibilityIdentifier("org_move_to_trash_btn")

            Text(String(localized: "org_trash_note"))
                .font(AppTypography.bodySmall.font)
                .foregroundColor(s.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .center)
        }
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.md)
    }

    // MARK: cleaned（spec §4，category_grid 清空分支态）

    private var cleanedContent: some View {
        let s = appScheme(cs)
        return ScrollView {
            VStack(spacing: Spacing.lg) {
                Color.clear.frame(height: Spacing.xl)   // top_spacer 24
                MatIcon(name: "checkmark.circle", size: OrganizeCategoryTokens.cleanedIconSize)
                    .foregroundStyle(StatusColor.success)   // clean_green #4CAF50（spec §7，双端对齐硬编码值）
                Text(String(localized: "org_cleaned_title"))
                    .font(AppTypography.headlineSmall.font.weight(.bold))
                Text(String(
                    format: String(localized: "org_cleaned_meta"), vm.trashedCount))
                    .font(AppTypography.bodyMedium.font)
                    .foregroundColor(s.onSurfaceVariant)
                Text(String(
                    format: String(localized: "org_cleaned_freed"),
                    OrganizeByteFormatter.string(vm.freedBytes)))
                    .font(.system(size: OrganizeCategoryTokens.freedNumberFontSize, weight: .bold))
                    .foregroundStyle(brandGradient)   // freed 数字品牌渐变着色（与 Hero 大数字同源，spec §4/§7）
            }
            .padding(Spacing.lg)
            .frame(maxWidth: .infinity)
        }
        // 🔴 Undo 按钮（spec §4.undo_bar）整段裁剪不渲染：iOS 无公开 API 从「最近删除」
        // 程序化恢复（PHAssetChangeRequest.deleteAssets 后资产对三方 App 不可见），
        // 台账已登记（spec §8 platform_differences「删除通路」行）；恢复入口走系统相册（30 天保留）。
        // 回收站预览卡（spec §4.recycle_preview_card）同因裁剪：其前提「已回收 uri 仍可直读」
        // 是 Android MediaStore IS_TRASHED 语义，iOS 已删资产不可 fetch。
    }

    /// 品牌渐变（spec §7 brand_gradient：Hero 大数字 / Undo 钮 / freed 数字同源；
    /// token 见 ChatBubbleTokens.brandGradient*）
    private var brandGradient: LinearGradient {
        LinearGradient(
            colors: [ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd],
            startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

// MARK: - 格点单元（spec §3.grid.thumb + selection_badge + protected_badge）

private struct OrganizeThumbCell: View {
    let entry: ClassifiedItem
    let isSelected: Bool
    let onToggle: () -> Void

    @Environment(\.colorScheme) private var cs
    @State private var image: UIImage?

    var body: some View {
        let s = appScheme(cs)
        return ZStack {
            s.surfaceVariant   // placeholder
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()   // scale: crop
            }
        }
        .aspectRatio(1, contentMode: .fit)   // 1:1
        .clipped()
        .clipShape(AppShapes.thumbnail)   // corner_radius 2
        // 右上角选中勾（spec §3.grid.selection_badge）
        .overlay(alignment: .topTrailing) {
            ZStack {
                Circle().fill(isSelected ? s.primary : Color.black.opacity(AppAlpha.ghost))
                if isSelected {
                    MatIcon(name: "check", size: OrganizeCategoryTokens.selectionBadgeCheckSize)
                        .foregroundStyle(AppColors.white)
                } else {
                    Circle().strokeBorder(
                        AppColors.white.opacity(AppAlpha.emphasis),
                        lineWidth: OrganizeCategoryTokens.selectionBadgeBorderWidth)
                }
            }
            .frame(width: OrganizeCategoryTokens.selectionBadgeSize,
                   height: OrganizeCategoryTokens.selectionBadgeSize)
            .padding(OrganizeCategoryTokens.selectionBadgeMargin)
        }
        // 左下角盾牌角标（protected 项；spec §3.grid.protected_badge）
        .overlay(alignment: .bottomLeading) {
            if entry.isProtected {
                Image(systemName: "shield.fill")   // 无 mat_shield 资产；图标形状为 §8 允许的平台差异
                    .font(.system(size: OrganizeCategoryTokens.protectedBadgeIconSize))
                    .foregroundColor(AppColors.white)
                    .padding(OrganizeCategoryTokens.protectedBadgeInnerPadding)
                    .background(Circle().fill(Color.black.opacity(0.4)))   // badge_scrim（spec §7）
                    .padding(Spacing.xs)   // margin 4
                    .accessibilityLabel(Text(String(localized: "org_protected_badge")))
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onToggle)   // tap_action: toggle_selection
        .task(id: entry.item.uri) {
            image = await ThumbnailLoader.shared.thumbnail(
                for: entry.item.uri,
                size: CGSize(width: OrganizeCategoryTokens.thumbRequestPx,
                            height: OrganizeCategoryTokens.thumbRequestPx))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(String(localized: String.LocalizationValue(
            entry.item.isVideo ? "media_type_video" : "media_type_photo"))))
        .accessibilityValue(Text(String(localized: String.LocalizationValue(
            isSelected ? "media_state_selected" : "media_state_unselected"))))
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
        .accessibilityIdentifier("org_cell_\(entry.item.uri)")
    }
}
