import SwiftUI
import UIKit

// MARK: - 整理中心 hub 屏（spec organize.yaml §2 DedupHubContent，iOS 实现）
// 布局：column_scrollable + padding 16 + item_spacing 12；
// Hero 卡（品牌渐变大数字）/「滑动整理」主按钮 / 6 类目卡（v3 竖向分行）/ 隐私脚注。
// [PRIVACY] 全部数值来自端侧管线（OrganizeRepository → OrganizeCategorizer），媒体零上传。

// MARK: - 本屏 spec 定值（organize.yaml §2 数值即本屏 token；DesignTokens 未覆盖的以常量收口）

private enum OrganizeHubMetrics {
    static let heroPadding: CGFloat = 20                       // hero_card.padding
    static let heroNumberSize: CGFloat = 34                    // big_number font size
    static let heroNumberWeight: Font.Weight = .bold           // big_number weight
    static let tidyButtonHeight: CGFloat = 48                  // quick_tidy_button.height
    static let tidyButtonRadius: CGFloat = 24                  // quick_tidy_button.corner_radius
    static let iconBlockSize: CGFloat = 40                     // row1 icon_block.size
    static let iconBlockRadius: CGFloat = 10                   // row1 icon_block.corner_radius
    static let thumbSize: CGFloat = 44                         // row4_thumbs.size
    static let thumbSpacing: CGFloat = 6                       // row4_thumbs.spacing
    static let badgeHSpacing: CGFloat = 12                     // row3_badges flow horizontal_spacing
    static let badgeVSpacing: CGFloat = 4                      // row3_badges flow vertical_spacing
    static let previewLimit = 4                                // row4_thumbs.count（PREVIEW_LIMIT）
}

/// 品牌渐变（青玉，与 tagControlBrandGradient 同源 = ChatBubbleTokens.brandGradient*）。
/// Hero 大数字 / 主按钮共用（spec §7 brand_gradient）。
private let organizeBrandGradient = LinearGradient(
    colors: [ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd],
    startPoint: .topLeading,
    endPoint: .bottomTrailing
)

// MARK: - hub 屏

struct OrganizeHubView: View {
    /// VM 由容器（OrganizeHomeView）持有，hub 视图重建不重启回填链。
    @ObservedObject var vm: OrganizeHubViewModel
    @Environment(\.colorScheme) private var cs

    /// 类目卡点击（READY 态）→ OrganizeCategoryScreen（§3，T5）。
    let onOpenCategory: (OrganizeCategory) -> Void
    /// 主按钮「滑动整理」→ SwipeReviewScreen（§5，T6）。
    let onOpenSwipeReview: () -> Void
    /// NEEDS_SCAN 引导卡点击 → 切「扫描」Tab（onOpenScan；不预选扫描项，spec §8 裁剪项）。
    let onOpenScan: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.md) {
                if let board = vm.board {
                    heroCard(board)
                } else {
                    loadingPlaceholder
                }
                tidyButton
                if let board = vm.board {
                    Text(String(localized: "org_categories"))
                        .font(AppTypography.titleSmall.font)
                        .foregroundColor(appScheme(cs).onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    ForEach(displayCards(board), id: \.category) { card in
                        categoryCard(card)
                    }
                    privacyCaption
                }
            }
            .padding(Spacing.lg)
        }
        .background(appScheme(cs).background.ignoresSafeArea())
        .task { await vm.appear() }
    }

    // MARK: 未加载态（board nil：Hero 与类目卡不渲染，防 0B 假数据）

    private var loadingPlaceholder: some View {
        VStack(spacing: Spacing.md) {
            Spacer(minLength: Spacing.xxl)
            if vm.isLoading || vm.board == nil {
                ProgressView()
            }
            Spacer(minLength: Spacing.xxl)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: Hero 卡（可释放口径已扣 keeper 语义由 board 给出，UI 零再计算）

    private func heroCard(_ board: OrganizeBoard) -> some View {
        let s = appScheme(cs)
        let across = board.categories.filter { $0.totalCount > 0 }.count
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(localized: "org_hero_label"))
                .font(AppTypography.bodySmall.font)
                .foregroundColor(s.onSurfaceVariant)
            Text(OrganizeByteFormat.string(board.heroReclaimBytes))
                .font(.system(size: OrganizeHubMetrics.heroNumberSize,
                              weight: OrganizeHubMetrics.heroNumberWeight))
                .foregroundStyle(organizeBrandGradient)
            if across > 0 {
                Text(String.localizedStringWithFormat(String(localized: "org_hero_across"), across))
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.onSurfaceVariant)
            }
            if board.heroReviewCount > 0 {
                Text(String(format: String(localized: "org_hero_review"), board.heroReviewCount))
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.onSurfaceVariant)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(OrganizeHubMetrics.heroPadding)
        .background(s.surfaceContainer)
        .clipShape(AppShapes.lg)
        .accessibilityIdentifier("organize_hub_hero")
    }

    // MARK: 主按钮「滑动整理」

    private var tidyButton: some View {
        Button(action: onOpenSwipeReview) {
            Text(String(localized: "org_quick_tidy"))
                .font(AppTypography.titleMedium.font)
                .fontWeight(AppTypography.WeightOverride.semibold)
                .foregroundColor(AppColors.white)
                .frame(maxWidth: .infinity)
                .frame(height: OrganizeHubMetrics.tidyButtonHeight)
                .background(organizeBrandGradient)
                .clipShape(Capsule())
        }
        .accessibilityIdentifier("organize_hub_tidy_button")
    }

    private var privacyCaption: some View {
        Text(String(localized: "org_privacy_note"))
            .font(AppTypography.bodySmall.font)
            .foregroundColor(appScheme(cs).onSurfaceVariant)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }

    // MARK: 类目卡列表组装

    /// DUPLICATES 钉首位（§2 dedup_category_card position: always_first）；其余按 board
    /// 给出的 highBytes 降序原样保持。零命中 READY 卡不渲染（已扫描无内容）。
    private func displayCards(_ board: OrganizeBoard) -> [CategoryBoard] {
        var rest = board.categories
            .filter { $0.category != .duplicates }
            .filter { !($0.totalCount == 0 && $0.coverage == .ready) }
        guard let duplicates = board.categories.first(where: { $0.category == .duplicates }) else {
            return rest
        }
        rest.insert(duplicates, at: 0)
        return rest
    }

    // MARK: 类目卡 v3（竖向分行）

    private func categoryCard(_ card: CategoryBoard) -> some View {
        // 🔴 DUPLICATES 本批裁剪（spec §8 决策）：hub 上按 NEEDS_SCAN 降档口径渲染——
        // 引导卡（surfaceContainerLow 底 + 引导文案），不产 keeper/徽标计数；
        // T8 dedup 扫描器落地后移除此 override，恢复 board 原生 coverage/计数渲染。
        let needsScan = card.category == .duplicates || card.coverage == .needsScan
        return Button {
            if needsScan {
                onOpenScan()
            } else {
                onOpenCategory(card.category)
            }
        } label: {
            cardContent(card, needsScan: needsScan)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(
            needsScan
                ? "organize_needs_scan_card_\(card.category.rawValue)"
                : "organize_category_card_\(card.category.rawValue)")
    }

    @ViewBuilder
    private func cardContent(_ card: CategoryBoard, needsScan: Bool) -> some View {
        let s = appScheme(cs)
        VStack(alignment: .leading, spacing: Spacing.sm) {
            // row1：图标块 + 标题 + chevron（文字不与缩略图条竞争横向宽度）
            HStack(spacing: Spacing.md) {
                CategoryIconView(category: card.category)
                // labelKey 为运行时字符串（按类目枚举映射）：String(localized:) 只接受
                // 字面量 LocalizationValue，动态 key 走 NSLocalizedString（xcstrings 同源解析）
                Text(NSLocalizedString(organizeCategoryLabelKey(card.category), comment: ""))
                    .font(AppTypography.bodyLarge.font)
                    .fontWeight(.medium)
                    .foregroundColor(s.onSurface)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.system(size: Spacing.md, weight: .medium))
                    .foregroundColor(s.onSurfaceVariant)
            }
            if needsScan {
                // 引导态副行（替代 row2~row4：无 meta/徽标/缩略图），点击整卡切「扫描」Tab
                Text(String(localized: "org_needs_scan"))
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.primary)
                    .lineLimit(1)
            } else {
                row2Meta(card)
                row3Badges(card)
                row4Thumbs(card)
            }
        }
        .padding(.horizontal, Spacing.md)
        .padding(.vertical, Spacing.md)
        // 引导态降档底色（不压整卡 alpha，标题/引导文案保持全不透明，WCAG AA）
        .background(needsScan ? s.surfaceContainerLow : s.surfaceContainer)
        .clipShape(AppShapes.card)
    }

    /// row2：meta（全量口径，含 protected）+ 右侧可释放（HIGH 非保护字节；DUPLICATES 已扣 keeper）。
    @ViewBuilder
    private func row2Meta(_ card: CategoryBoard) -> some View {
        let s = appScheme(cs)
        HStack(spacing: Spacing.sm) {
            Text(String(format: String(localized: "org_cat_meta"),
                        card.totalCount,
                        OrganizeByteFormat.string(card.totalBytes)))
                .font(AppTypography.bodySmall.font)
                .foregroundColor(s.onSurfaceVariant)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
            if card.highBytes > 0 {
                Text(String(format: String(localized: "org_cat_reclaim"),
                            OrganizeByteFormat.string(card.highBytes)))
                    .font(AppTypography.labelSmall.font)
                    .foregroundColor(s.primary)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false) // soft_wrap: false，绝不折行
            }
        }
    }

    /// row3：置信徽标（FlowRow 按徽标粒度整体折行；dot + 文案为不可拆整体单元）。
    /// dot 色 = scheme primary（● 实心 / ○ 描边共用主色口径，spec §2 row3_badges）。
    @ViewBuilder
    private func row3Badges(_ card: CategoryBoard) -> some View {
        let s = appScheme(cs)
        let hasBadge = card.highCount > 0 || card.reviewCount > 0 || card.protectedCount > 0
        if hasBadge {
            BadgeFlowLayout(
                hSpacing: OrganizeHubMetrics.badgeHSpacing,
                vSpacing: OrganizeHubMetrics.badgeVSpacing
            ) {
                if card.highCount > 0 {
                    badgeUnit(dot: .filled(s.primary)) {
                        Text(String(format: String(localized: "org_confidence_high"), card.highCount))
                            .foregroundColor(s.primary)
                    }
                }
                if card.reviewCount > 0 {
                    badgeUnit(dot: .outlined(s.primary)) {
                        Text(String(format: String(localized: "org_confidence_review"), card.reviewCount))
                            .foregroundColor(s.onSurfaceVariant)
                    }
                }
                if card.protectedCount > 0 {
                    // 纯文案无圆点
                    Text(String(format: String(localized: "org_protected_count"), card.protectedCount))
                        .font(AppTypography.labelSmall.font)
                        .foregroundColor(s.onSurfaceVariant)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                }
            }
        }
    }

    /// 徽标单元：dot + 文案横向密排（gap = BadgeTokens.tagDotLabelGap），整体不拆行。
    private enum BadgeDotStyle {
        case filled(Color)
        case outlined(Color)

        @ViewBuilder
        var view: some View {
            switch self {
            case .filled(let color):
                Circle().fill(color)
            case .outlined(let color):
                Circle().strokeBorder(color, lineWidth: 1)
            }
        }
    }

    private func badgeUnit<Label: View>(
        dot: BadgeDotStyle,
        @ViewBuilder label: () -> Label
    ) -> some View {
        HStack(spacing: BadgeTokens.tagDotLabelGap) {
            dot.view
                .frame(width: BadgeTokens.tagDotSize, height: BadgeTokens.tagDotSize)
            label()
                .font(AppTypography.labelSmall.font)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
    }

    /// row4：缩略图条独占一行（前 4 张，crop 裁切填充）。
    @ViewBuilder
    private func row4Thumbs(_ card: CategoryBoard) -> some View {
        let uris = Array(card.previewUris.prefix(OrganizeHubMetrics.previewLimit))
        if !uris.isEmpty {
            HStack(spacing: OrganizeHubMetrics.thumbSpacing) {
                ForEach(uris, id: \.self) { uri in
                    CategoryThumbView(uri: uri, size: OrganizeHubMetrics.thumbSize)
                }
            }
        }
    }
}

// MARK: - 徽标 FlowRow（iOS 16 Layout；对齐 Compose FlowRow 按整体单元折行）

private struct BadgeFlowLayout: Layout {
    let hSpacing: CGFloat
    let vSpacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + vSpacing
                rowHeight = 0
            }
            x += size.width + hSpacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: proposal.width ?? (x > 0 ? x - hSpacing : 0), height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + vSpacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + hSpacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

// MARK: - 类目缩略图（44pt crop；Coil 360px 的 iOS 等效 = 3x targetSize 132px，无 crossfade）

private struct CategoryThumbView: View {
    let uri: String
    let size: CGFloat
    @State private var image: UIImage?
    @Environment(\.colorScheme) private var cs

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Rectangle().fill(appScheme(cs).surfaceVariant) // placeholder
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.small, style: .continuous))
        .task(id: uri) {
            // [PRIVACY] ThumbnailLoader 内 isNetworkAccessAllowed=false，端侧直读
            let px = CGSize(width: size * 3, height: size * 3)
            image = await ThumbnailLoader.shared.thumbnail(for: uri, size: px)
        }
    }
}

// MARK: - 类目图标块（spec §1 icon 的 iOS 映射；资产缺口登记见各 case 注释）

private struct CategoryIconView: View {
    let category: OrganizeCategory
    @Environment(\.colorScheme) private var cs

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: OrganizeHubMetrics.iconBlockRadius, style: .continuous)
                .fill(appScheme(cs).surfaceVariant)
            icon
                .foregroundColor(appScheme(cs).onSurfaceVariant)
        }
        .frame(width: OrganizeHubMetrics.iconBlockSize, height: OrganizeHubMetrics.iconBlockSize)
    }

    /// spec §1 icon：burst_mode / screenshot_monitor / description / face / blur_on / videocam。
    /// 平台差异 §8 allowed（图标形状可换 SF Symbols / mat_*）；无资产的用语义最近 SF Symbol。
    @ViewBuilder
    private var icon: some View {
        switch category {
        case .duplicates:
            // burst_mode（分层照片）→ SF rectangle.stack（照片堆叠）
            Image(systemName: "rectangle.stack")
                .font(.system(size: IconSize.md))
        case .screenContent:
            // screenshot_monitor → SF macwindow.on.rectangle（iOS 16）
            Image(systemName: "macwindow.on.rectangle")
                .font(.system(size: IconSize.md))
        case .documents:
            // description → mat_text_snippet（既有资产，同为文档字形）
            MatIcon(name: "mat_text_snippet", size: IconSize.md)
        case .lowQualityPortraits:
            // face → mat_face（既有资产）
            MatIcon(name: "mat_face", size: IconSize.md)
        case .lowQualityPhotos:
            // blur_on（点阵模糊）→ SF circle.dotted（iOS 14）
            Image(systemName: "circle.dotted")
                .font(.system(size: IconSize.md))
        case .largeFiles:
            // videocam → SF video.fill（iOS 13）
            Image(systemName: "video.fill")
                .font(.system(size: IconSize.md))
        }
    }
}

// MARK: - 类目 i18n 键映射（与 Android strings.xml org_cat_* 对齐）
// 文件内私有函数而非 OrganizeCategory 扩展：领域类型不改（依赖勿动），且避免与
// 并行任务（T5 类目详情页同需类目名）的扩展撞名。

private func organizeCategoryLabelKey(_ category: OrganizeCategory) -> String {
    switch category {
    case .duplicates: return "org_cat_duplicates"
    case .screenContent: return "org_cat_screen_content"
    case .documents: return "org_cat_documents"
    case .lowQualityPortraits: return "org_cat_portraits"
    case .lowQualityPhotos: return "org_cat_blurry"
    case .largeFiles: return "org_cat_large_files"
    }
}
