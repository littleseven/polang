import SwiftUI
import UIKit

// MARK: - 文案还原（spec memories.yaml §4 texts，[I18N] 红线：UI 层按类型本地化）

/// Memory 只带结构化字段；title/subtitle/日期范围在此按当前语言还原。
/// 日期本地化走 DateFormatter.dateFormat(fromTemplate:)（MMMd / yMMMM skeleton，
/// 语义等价 Android getBestDateTimePattern）；locale 跟随 LanguageManager 当前语言。
enum MemoryTexts {

    /// LanguageManager 当前语言 → Locale（"system" 跟随系统，与 L() 取值口径一致）。
    static func locale() -> Locale {
        switch LanguageManager.shared.currentLanguage {
        case "english": return Locale(identifier: "en")
        case "chinese_simplified": return Locale(identifier: "zh-Hans")
        case "chinese_traditional": return Locale(identifier: "zh-Hant")
        case "spanish": return Locale(identifier: "es")
        case "french": return Locale(identifier: "fr")
        default: return Locale.current
        }
    }

    /// 标题（title_by_type）：CITY 城市名直出，PERSON 带 label，其余固定键。
    static func title(for memory: Memory) -> String {
        switch memory.type {
        case .onThisDay: return L("memory_on_this_day")
        case .recentHighlights: return L("memory_recent_highlights")
        case .person: return String(format: L("memory_person_title"), memory.label ?? "")
        case .city: return memory.label ?? ""
        }
    }

    /// 副行（subtitle_by_type）。
    static func subtitle(for memory: Memory) -> String {
        switch memory.type {
        case .onThisDay:
            return String(format: L("memory_on_this_day_subtitle"),
                          localizedMonthDay(memory.monthDay), memory.latestYear ?? 0)
        case .recentHighlights:
            return L("memory_recent_highlights_subtitle")
        case .person:
            return String(format: L("memory_photo_count"), memory.hitCount)
        case .city:
            return cityDateRange(memory)
        }
    }

    /// 月日本地化（localizedMonthDay）：MMMd skeleton（en "Sep 15"、zh "9月15日"）；
    /// nil 容错返回空串。仅取月日模板，年用占位年合成。
    static func localizedMonthDay(_ monthDay: MemoryMonthDay?) -> String {
        guard let md = monthDay else { return "" }
        var comps = DateComponents()
        comps.year = 2001
        comps.month = md.month
        comps.day = md.day
        guard let date = Calendar.current.date(from: comps) else { return "" }
        let locale = self.locale()
        let fmt = DateFormatter()
        fmt.locale = locale
        fmt.dateFormat = DateFormatter.dateFormat(fromTemplate: "MMMd", options: 0, locale: locale)
        return fmt.string(from: date)
    }

    /// 旅程日期范围（cityDateRange）：同年月 → 本地化年月（yMMMM：en "May 2025"、zh "2025年5月"）；
    /// 跨月/跨年 → "{startYear} · {endYear}"；字段缺失（非 CITY）容错返回空串。
    static func cityDateRange(_ memory: Memory) -> String {
        guard let earliest = memory.earliestCaptureDate,
              let latest = memory.latestCaptureDate else { return "" }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let start = cal.dateComponents([.year, .month], from: date(ms: earliest))
        let end = cal.dateComponents([.year, .month], from: date(ms: latest))
        guard let startYear = start.year, let endYear = end.year else { return "" }
        if startYear == endYear, start.month == end.month {
            let locale = self.locale()
            let fmt = DateFormatter()
            fmt.locale = locale
            fmt.dateFormat = DateFormatter.dateFormat(fromTemplate: "yMMMM", options: 0, locale: locale)
            return fmt.string(from: date(ms: latest))
        }
        return "\(startYear) · \(endYear)"
    }

    private static func date(ms: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    }
}

// MARK: - 回忆根页（spec memories.yaml §2 root_page，主 Pager 页 4）

/// 回忆根页：顶栏（大标题 + 端侧私密副标）→ 三分区（时光/旅程/人物，空分区剔除）
/// → 整页空态。底 bar 由宿主 MainTabView 渲染，本 View 不渲染 bar（底部 96 预留遮挡）。
/// 详情页经 fullScreenCover 二级弹出（路由 memory_detail/{memoryId}）。
struct MemoriesView: View {

    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    @StateObject private var vm = MemoriesViewModel()
    @State private var detailRoute: MemoryRoute?
    @State private var hideTarget: Memory?
    @State private var hideConfirmShown = false

    var body: some View {
        ZStack {
            s.surface.ignoresSafeArea()
            if vm.memories.isEmpty {
                emptyState
            } else {
                VStack(spacing: 0) {
                    topBar
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(sections) { section in
                                sectionView(section)
                            }
                        }
                        .padding(.bottom, 96)  // feed_bottom_padding：预留悬浮底 bar 遮挡
                    }
                }
            }
        }
        .accessibilityIdentifier("memories_root")
        .onAppear { vm.reload() }
        .fullScreenCover(item: $detailRoute) { route in
            MemoryDetailView(memoryId: route.id, viewModel: vm)
        }
        // 长按大卡 → 隐藏确认（v1 无恢复入口，文案不承诺恢复）
        .confirmationDialog(L("memory_hide"), isPresented: $hideConfirmShown, titleVisibility: .visible) {
            Button(L("memory_hide"), role: .destructive) {
                if let target = hideTarget { vm.hideMemory(id: target.id) }
                hideTarget = nil
            }
            Button(L("memory_hide_cancel"), role: .cancel) { hideTarget = nil }
        } message: {
            Text(L("memory_hide_confirm"))
        }
    }

    // MARK: 顶栏（大标题 + 端侧私密副标；状态栏避让由 safe area 承担）

    private var topBar: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("memory_title"))
                .font(.system(size: AppTypography.headlineMedium.size,
                              weight: AppTypography.WeightOverride.semibold))
                .foregroundColor(s.onSurface)
            Text(L("memory_privacy_note"))
                .font(.system(size: AppTypography.labelSmall.size, weight: .medium))
                .foregroundColor(s.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.md)
    }

    // MARK: 分区（time=ON_THIS_DAY+RECENT_HIGHLIGHTS / journey=CITY / people=PERSON；空分区剔除）

    private struct MemorySection: Identifiable {
        let id: String
        let titleKey: String
        let memories: [Memory]
    }

    private var sections: [MemorySection] {
        let time = vm.memories.filter { $0.type == .onThisDay || $0.type == .recentHighlights }
        let journey = vm.memories.filter { $0.type == .city }
        let people = vm.memories.filter { $0.type == .person }
        var out: [MemorySection] = []
        if !time.isEmpty { out.append(MemorySection(id: "time", titleKey: "memory_section_time", memories: time)) }
        if !journey.isEmpty { out.append(MemorySection(id: "journey", titleKey: "memory_section_journey", memories: journey)) }
        if !people.isEmpty { out.append(MemorySection(id: "people", titleKey: "memory_section_people", memories: people)) }
        return out
    }

    private func sectionView(_ section: MemorySection) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L(section.titleKey))
                .font(.system(size: AppTypography.titleMedium.size,
                              weight: AppTypography.WeightOverride.semibold))
                .foregroundColor(s.onSurface)
                .padding(.horizontal, MemoryPageTokens.sectionHorizontalPadding)
                .padding(.top, MemoryPageTokens.sectionTitleSpacing)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: MemoryPageTokens.cardSpacing) {
                    ForEach(section.memories) { memory in
                        MemoryBigCard(memory: memory) {
                            detailRoute = MemoryRoute(id: memory.id)
                        } onLongPress: {
                            hideTarget = memory
                            hideConfirmShown = true
                        }
                    }
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, Spacing.sm)
            }
        }
    }

    // MARK: 整页空态（三分区全空）

    private var emptyState: some View {
        Text(L("memory_empty_page"))
            .font(.system(size: AppTypography.bodyMedium.size))
            .foregroundColor(s.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .padding(.horizontal, Spacing.xl)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// 详情路由包装（fullScreenCover item 绑定需 Identifiable；id 经 URI 编解码一次的内存态）。
struct MemoryRoute: Identifiable {
    let id: String
}

// MARK: - 分区大卡（spec §2 big_card：168×224 竖版卡）

private struct MemoryBigCard: View {

    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    let memory: Memory
    let onTap: () -> Void
    let onLongPress: () -> Void

    @State private var image: UIImage?

    private var title: String { MemoryTexts.title(for: memory) }
    private var subtitle: String { MemoryTexts.subtitle(for: memory) }

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            // 加载/失败占位 = surface 色块；禁交叉淡入（对齐 Android recycled bitmap 红线）
            s.surfaceContainer
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            }
            // 底部 50% 高黑渐变蒙层（透明 → 黑 0.6；spec §2 big_card.scrim 固定黑底白字）
            LinearGradient(colors: [.clear, Color.black.opacity(0.6)],
                           startPoint: .top, endPoint: .bottom)
                .frame(height: MemoryPageTokens.cardHeight / 2)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .allowsHitTesting(false)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundColor(.white.opacity(AppAlpha.emphasis))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(Spacing.md)
        }
        .frame(width: MemoryPageTokens.cardWidth, height: MemoryPageTokens.cardHeight)
        .clipShape(RoundedRectangle(cornerRadius: MemoryPageTokens.cardCornerRadius, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: MemoryPageTokens.cardCornerRadius, style: .continuous))
        .onTapGesture(perform: onTap)
        .onLongPressGesture(perform: onLongPress)
        .task(id: memory.coverUri) {
            // 缩略图 ≥512px（spec §2 big_card.cover；336pt 卡宽 @1.5x 留余量）
            image = await ThumbnailLoader.shared.thumbnail(
                for: memory.coverUri,
                size: CGSize(width: 512, height: 512 * MemoryPageTokens.cardHeight / MemoryPageTokens.cardWidth))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(title) · \(subtitle)"))
        .accessibilityAddTraits(.isButton)
    }
}
