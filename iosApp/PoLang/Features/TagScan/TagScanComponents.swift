import SwiftUI

// MARK: - TAG 扫描控制页 v4.1 组件（spec: docs/08-UI-SPECS/screens/tag-control.yaml，2026-10-02 正典）
//
// 四段骨架的构件：progBar / top_bar / ringHero(环+六格成果面板) / 阶段行 / StageActionSheet /
// bottom_bar 双钮。v2 的 TagStatsCard/ScanActionCard/ScanProgressCard/ScanControlRow/
// ScanStatusChip/TagRegenerateCard 已随 v4 删除清单退役。
// 执行链消费既有 TagScanOrchestrator（经 TagScanViewModel，调用面不变）。

/// 品牌渐变（ChatBubbleTokens，#07C160→#06AD56）：本页外的既有消费方（SwipeReviewScreen 等）。
let tagControlBrandGradient = LinearGradient(
    colors: [ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd],
    startPoint: .topLeading,
    endPoint: .bottomTrailing
)

// MARK: - 公共 helper

/// 对齐 Android formatDuration：d>0→Xd Yh；h>0→Xh Ym；m>0→Xm Ys；else→Xs（top_bar ETA 用）。
func formatDuration(_ ms: Int) -> String {
    let t = max(0, ms / 1000)
    let d = t / 86400, h = (t % 86400) / 3600, m = (t % 3600) / 60, s = t % 60
    if d > 0 { return "\(d)d \(h)h" }
    if h > 0 { return "\(h)h \(m)m" }
    if m > 0 { return "\(m)m \(s)s" }
    return "\(s)s"
}

// MARK: - 段 1：progBar（胶囊正下方 4dp 全宽本轮进度条）

/// 轨道 surfaceVariant；填充 = primary 按 sessionFraction（暂停态琥珀）；
/// RUNNING 时 56dp 白 0.75 高亮带 1.1s 循环扫过（流光，TimelineView 对齐节拍）。
struct TagScanProgBar: View {
    let fraction: CGFloat
    let color: Color
    let running: Bool
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        GeometryReader { geo in
            let width = max(0, min(1, fraction)) * geo.size.width
            ZStack(alignment: .leading) {
                Capsule().fill(s.surfaceVariant)
                if width > 0 {
                    Capsule()
                        .fill(color)
                        .frame(width: width)
                        .overlay(alignment: .leading) {
                            if running {
                                TimelineView(.animation) { ctx in
                                    let cycle = ctx.date.timeIntervalSinceReferenceDate
                                        .truncatingRemainder(dividingBy: 1.1) / 1.1
                                    let bandWidth = min(56, width)
                                    Rectangle()
                                        .fill(Color.white.opacity(0.75))
                                        .frame(width: bandWidth)
                                        .offset(x: -bandWidth + cycle * (width + bandWidth))
                                }
                            }
                        }
                        .clipShape(Capsule())
                }
            }
        }
        .frame(height: 4)
        .accessibilityIdentifier("tag_scan_prog_bar")
    }
}

// MARK: - 段 2：top_bar（48dp surface 标题栏：标题 + 会话 x/y 计数 + ETA/状态副行）

struct TagScanTopBar: View {
    /// 本轮会话计数「x / y」；空闲只有标题（nil = 隐藏计数）。
    let counter: String?
    /// 副行：running_eta / running_no_eta / paused（11sp onSurfaceVariant）。
    let subline: String?
    /// cover 态（相册/设置入口）显示返回钮；embedded 态胶囊即页头，不显示。
    var showsBack: Bool = false
    var onBack: (() -> Void)? = nil
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        HStack(spacing: Spacing.sm) {
            if showsBack {
                AppTopBarAction(systemName: "mat_o_arrow_back", accessibilityID: "topbar_back") { onBack?() }
            }
            Text(L("tag_scan_page_title"))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(s.onSurface)
            Spacer(minLength: Spacing.md)
            if let counter {
                VStack(alignment: .trailing, spacing: 1) {
                    Text(counter)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(s.onSurface)
                    if let subline {
                        Text(subline)
                            .font(.system(size: 11))
                            .foregroundStyle(s.onSurfaceVariant)
                    }
                }
                .accessibilityIdentifier("tag_scan_top_counter")
            }
        }
        .padding(.horizontal, Spacing.md)
        .frame(height: TopBarTokens.height)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surface)
    }
}

// MARK: - 段 3a：ringHero（全库口径仪表环 + 六格成果面板）

/// 环 = 库级 AI 打标完成率（LibraryCompletion 口径立法，恒定不随会话动）；
/// 本轮会话进度只活在 progBar + top_bar。状态色只活在弧/大数字（绿=跑，琥珀=停），
/// 容器恒 surfaceContainer 不换色。
struct TagScanRingHeroCard: View {
    let completion: LibraryCompletion
    /// 环 hint 尾词键：tag_scan_status_{ready,running,paused,done}。
    let statusKey: String
    /// 会话活跃（RUNNING/PAUSING/CANCELLING）→ 弧/大数字 primary。
    let active: Bool
    /// 仅 RUNNING → 彗星弧动画（动画规格：RUNNING 才动，过渡/暂停/终态静止）。
    let running: Bool
    let paused: Bool
    let stats: ScanDbStats
    var onOpenPeople: () -> Void
    var onOpenCitySheet: () -> Void
    var onSelfGuide: () -> Void
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        let stateColor = paused ? StatusColor.warningAmber : (active ? s.primary : s.onSurfaceVariant)
        VStack(spacing: Spacing.lg) {
            dial(stateColor, s)
            Text(String(format: L("tag_scan_ring_hint"),
                        "\(completion.progress.processed)",
                        "\(stats.totalMedia)",
                        L(statusKey)))
                .font(.system(size: 13))
                .foregroundStyle(s.onSurface)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
            Divider().overlay(s.outlineVariant)
            resultsPanel(s)
        }
        .padding(.top, 20)
        .padding(.horizontal, 14)
        .padding(.bottom, Spacing.md)
        .background(s.surfaceContainer)
        .clipShape(AppShapes.lg)
        .accessibilityElement(children: .contain)   // 保子元素（dial/六格）可测，容器 id 仍可查
        .accessibilityIdentifier("tag_scan_ring_hero")
    }

    // MARK: 仪表环（200dp 容器 / 176dp 环 stroke12；12 点起点顺时针，圆头端点）

    private func dial(_ stateColor: Color, _ s: SchemeColors) -> some View {
        ZStack {
            Circle().stroke(s.surfaceVariant, lineWidth: 12)
            Circle()
                .trim(from: 0, to: CGFloat(max(0, min(1, completion.fraction))))
                .stroke(stateColor, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                .rotationEffect(.degrees(-90))
            if running {
                // 彗星弧：40° 白 0.85 亮弧 1.5s 匀速绕环（叠加非替代）
                TimelineView(.animation) { ctx in
                    let cycle = ctx.date.timeIntervalSinceReferenceDate
                        .truncatingRemainder(dividingBy: 1.5) / 1.5
                    Circle()
                        .trim(from: 0, to: 40.0 / 360.0)
                        .stroke(Color.white.opacity(0.85),
                                style: StrokeStyle(lineWidth: 12, lineCap: .round))
                        .rotationEffect(.degrees(-90 + cycle * 360))
                }
            }
            Text("\(completion.percentRounded())%")
                .font(.system(size: 48, weight: .semibold))
                .foregroundStyle(stateColor)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(width: 176, height: 176)
        .frame(width: 200, height: 200)
        .accessibilityIdentifier("tag_scan_dial")
    }

    // MARK: 六格成果面板（v4.1：成果格=查看内容；阶段行=扫描控制）

    private func resultsPanel(_ s: SchemeColors) -> some View {
        let columns = [
            GridItem(.flexible(), spacing: Spacing.sm),
            GridItem(.flexible(), spacing: Spacing.sm),
            GridItem(.flexible(), spacing: Spacing.sm),
        ]
        return LazyVGrid(columns: columns, spacing: Spacing.md) {
            // 含人脸 → FACES 视图；iOS 无 GalleryViewFilter 通道 → 等价落地人物页（yaml allowed_differences）
            TagResultCellView(
                count: "\(stats.withFace)", label: L("tag_result_faces_label"),
                dot: AppColors.tagPink, onTap: onOpenPeople)
                .accessibilityIdentifier("tag_result_cell_faces")
            // 识别人物 → 人物页（同名出口）
            TagResultCellView(
                count: "\(stats.personCount)",
                label: String(format: L("tag_result_people_label"), stats.namedPersonCount),
                dot: AppColors.tagPurple, onTap: onOpenPeople)
                .accessibilityIdentifier("tag_result_cell_people")
            // 足迹城市 → 城市弹层 → CITY 视图；⚠️ iOS 无 city 回填链路恒 0（平台缺口台账），格仍渲染
            TagResultCellView(
                count: "\(stats.cityCount)", label: L("tag_result_cities_label"),
                dot: AppColors.tagCyan, onTap: onOpenCitySheet)
                .accessibilityIdentifier("tag_result_cell_cities")
            // TODO(platform gap): 合照 → GROUP 视图（face_embeddings ≥2 人聚合）；iOS 无过滤通道，
            // 首版置灰禁用（台账登记，GalleryViewFilter 通道落地后开出口）
            TagResultCellView(
                count: "\(stats.groupPhotoCount)", label: L("tag_result_group_label"),
                dot: AppColors.tagGreen, onTap: nil)
                .accessibilityIdentifier("tag_result_cell_group")
            // 「我」：已标记 → SELF 视图（iOS 无通道 → 等价人物页，TODO 台账）；
            // 未标记 → 引导去人物页标记（yaml: snackbar 引导）
            TagResultCellView(
                count: stats.selfPhotoCount > 0 ? "\(stats.selfPhotoCount)" : "—",
                label: L("tag_result_self_label"),
                dot: StatusColor.warningAmber,
                onTap: stats.selfPhotoCount > 0 ? onOpenPeople : onSelfGuide)
                .accessibilityIdentifier("tag_result_cell_self")
            // TODO(platform gap): 已打内容标签 → TAGGED 视图；iOS 无 TagViewer/过滤通道，
            // 首版置灰禁用（台账登记）
            TagResultCellView(
                count: "\(stats.withLabels)", label: L("tag_result_tags_label"),
                dot: s.primary, onTap: nil)
                .accessibilityIdentifier("tag_result_cell_tags")
        }
    }
}

/// 成果格：数字 20sp SemiBold + 6dp 彩点 + 11sp 标签 + 14dp chevron；
/// onTap == nil → 无出口（平台缺口）置灰禁用。
private struct TagResultCellView: View {
    let count: String
    let label: String
    let dot: Color
    var onTap: (() -> Void)?
    @Environment(\.colorScheme) private var cs

    private var enabled: Bool { onTap != nil }

    var body: some View {
        let s = appScheme(cs)
        Button(action: { onTap?() }) {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                HStack(spacing: 6) {
                    Text(count)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(s.onSurface)
                    Circle().fill(dot).frame(width: BadgeTokens.tagDotSize, height: BadgeTokens.tagDotSize)
                    Spacer(minLength: 0)
                    if enabled {
                        MatIcon(name: "arrow_forward", size: 14)
                            .foregroundStyle(s.onSurfaceVariant.opacity(AppAlpha.hint))
                    }
                }
                Text(label)
                    .font(.system(size: 11))
                    .foregroundStyle(s.onSurfaceVariant)
                    .lineLimit(2, reservesSpace: true)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, Spacing.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : AppAlpha.secondary)
    }
}

// MARK: - 城市弹层（ModalBottomSheet 等价物：城市名+张数 → 点选 CITY 请求）

struct TagCitySheet: View {
    let cities: [(city: String, count: Int)]
    var onSelect: (String) -> Void
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        VStack(alignment: .leading, spacing: Spacing.md) {
            Text(L("tag_city_sheet_title"))
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(s.onSurface)
            // ⚠️ iOS 无写 media_assets.city 链路 → 列表恒空（平台缺口台账，不加临时文案）
            ForEach(cities, id: \.city) { item in
                Button {
                    onSelect(item.city)
                } label: {
                    HStack {
                        Text(item.city)
                            .font(AppTypography.bodyMedium.font)
                            .foregroundStyle(s.onSurface)
                        Spacer(minLength: 0)
                        Text(String(format: L("tag_city_sheet_count"), item.count))
                            .font(AppTypography.bodySmall.font)
                            .foregroundStyle(s.onSurfaceVariant)
                    }
                    .padding(.vertical, Spacing.sm)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("tag_city_row_\(item.city)")
            }
        }
        .padding(Spacing.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHigh)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

// MARK: - 段 3b：互斥附属卡（mutex_slots）

/// interrupted_card：进程死亡对账（iOS 等价 = DB 存在未完成 session 且当前无活会话）→ 断点续扫。
/// aesthetic_card：iOS 无美学评分执行链（session_control_gap 台账）→ 恒不渲染。
struct TagScanInterruptedCard: View {
    var onResume: () -> Void
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        HStack(spacing: Spacing.md) {
            MatIcon(name: "hourglass", size: IconSize.sm)
                .foregroundStyle(StatusColor.warningAmber)
            Text(L("scan_resume_unfinished"))
                .font(AppTypography.bodyMedium.font)
                .foregroundStyle(s.onSurface)
            Spacer(minLength: Spacing.md)
            Button(action: onResume) {
                Text(L("tag_scan_resume_breakpoint"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(s.onPrimary)
                    .padding(.horizontal, Spacing.md)
                    .padding(.vertical, 6)
                    .background(s.primary)
                    .clipShape(Capsule())
            }
            .accessibilityIdentifier("scan_resume_unfinished_btn")
        }
        .padding(Spacing.md)
        .background(s.surfaceContainer)
        .clipShape(AppShapes.lg)
    }
}

// MARK: - 段 3c：阶段行 ×4（点按 = StageActionSheet 阶段扫描控制；查看职责移交成果格）

enum TagStage: String, Identifiable, CaseIterable {
    case faces, people, content, aesthetic

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .faces: return "tag_pass_title_face"
        case .people: return "tag_pass_title_cluster"
        case .content: return "tag_pass_title_content"
        case .aesthetic: return "tag_pass_title_aesthetic"
        }
    }

    var iconName: String {
        switch self {
        case .faces: return "face.smiling"
        case .people: return "person.2"
        case .content: return "tag"
        case .aesthetic: return "sparkles"
        }
    }

    /// 芯片色板（yaml stages.row_icon：tagPink/tagPurple/tagCyan/tagGreen）。
    var accent: Color {
        switch self {
        case .faces: return AppColors.tagPink
        case .people: return AppColors.tagPurple
        case .content: return AppColors.tagCyan
        case .aesthetic: return AppColors.tagGreen
        }
    }
}

struct TagStageSection: View {
    let stats: ScanDbStats
    /// 点按分派（aesthetic 由调用方弹「后续版本」提示——iOS 无美学评分链）。
    var onStageTap: (TagStage) -> Void
    @Environment(\.colorScheme) private var cs

    var body: some View {
        VStack(spacing: 0) {
            stageRow(.faces)
            Divider()
            stageRow(.people)
            Divider()
            stageRow(.content)
            Divider()
            stageRow(.aesthetic)
        }
        .padding(.vertical, Spacing.xs)
        .background(appScheme(cs).surfaceContainer)
        .clipShape(AppShapes.lg)
    }

    /// 60dp 行：28dp r7 色底芯片（12% 透明度）+ 18dp 图标 + 标题/口径 + 行尾值 + chevron。
    private func stageRow(_ stage: TagStage) -> some View {
        let s = appScheme(cs)
        return Button {
            onStageTap(stage)
        } label: {
            HStack(spacing: Spacing.md) {
                MatIcon(name: stage.iconName, size: SettingsTokens.listIconInnerSize)
                    .foregroundStyle(stage.accent)
                    .frame(width: SettingsTokens.listIconBlockSize, height: SettingsTokens.listIconBlockSize)
                    .background(stage.accent.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: SettingsTokens.listIconBlockRadius))
                VStack(alignment: .leading, spacing: 2) {
                    Text(L(stage.titleKey))
                        .font(AppTypography.bodyMedium.font)
                        .foregroundStyle(s.onSurface)
                    Text(descText(stage))
                        .font(.system(size: 11))
                        .foregroundStyle(s.onSurfaceVariant)
                }
                Spacer(minLength: 0)
                Text(trailingValue(stage))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(s.primary)
                MatIcon(name: "arrow_forward", size: IconSize.sm)
                    .foregroundStyle(s.onSurfaceVariant.opacity(AppAlpha.hint))
            }
            .padding(.horizontal, Spacing.md)
            .frame(minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("stage_\(stage.rawValue)_row")
    }

    /// 行口径 = tagPassProgress().percentRounded()（与环心大数字同源同舍入）；人物行 = 计数非百分比；
    /// 美学行 iOS 恒缺执行链 → 占位「—」（点按弹「后续版本」）。
    private func trailingValue(_ stage: TagStage) -> String {
        switch stage {
        case .faces:
            return "\(tagPassProgress(total: stats.totalMedia, remaining: stats.remainingPass1).percentRounded())%"
        case .people:
            return "\(stats.personCount)"
        case .content:
            return "\(tagPassProgress(total: stats.totalMedia, remaining: stats.remainingPass3).percentRounded())%"
        case .aesthetic:
            return "—"
        }
    }

    private func descText(_ stage: TagStage) -> String {
        switch stage {
        case .faces:
            let p = tagPassProgress(total: stats.totalMedia, remaining: stats.remainingPass1)
            // 空库回落静态键（yaml stages.rows：tag_pass_scope_face（空库回落静态键））
            return p.isEmpty ? L("tag_pass_desc_face")
                : String(format: L("tag_pass_scope_face"), p.processed, p.total)
        case .people:
            return L("tag_pass_desc_cluster")
        case .content:
            let p = tagPassProgress(total: stats.totalMedia, remaining: stats.remainingPass3)
            return p.isEmpty ? L("tag_pass_desc_content")
                : String(format: L("tag_pass_scope_content"), p.processed, p.total)
        case .aesthetic:
            // iOS 无美学评分 → 恒 0 已评分（口径文案保持真实）
            return String(format: L("tag_pass_scope_aesthetic"), 0, stats.totalMedia)
        }
    }
}

// MARK: - 阶段操作弹层（StageActionSheet：增量扫描/全量扫描 点选即执行 + recommended 徽章）

struct StageActionSheet: View {
    let stage: TagStage
    var onRunNew: () -> Void
    var onRunFull: () -> Void
    @Environment(\.colorScheme) private var cs

    enum StageAction { case new, full }

    var body: some View {
        let s = appScheme(cs)
        VStack(alignment: .leading, spacing: Spacing.lg) {
            Text(L(stage.titleKey))
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(s.onSurface)
            optionCard(.new, badge: L("tag_action_recommended"), s)
            optionCard(.full, badge: nil, s)
        }
        .padding(Spacing.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHigh)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private func optionCard(_ action: StageAction, badge: String?, _ s: SchemeColors) -> some View {
        Button {
            if action == .new {
                onRunNew()
            } else {
                onRunFull()
            }
        } label: {
            HStack(alignment: .top, spacing: Spacing.md) {
                RadioCircle(selected: action == .new)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: Spacing.sm) {
                        Text(L(action == .new ? "tag_stage_action_new" : "tag_stage_action_full"))
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(s.onSurface)
                        if let badge {
                            Text(badge)
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(s.primary)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(s.primary.opacity(AppAlpha.primaryTint))
                                .clipShape(Capsule())
                        }
                    }
                    Text(L(action == .new ? "tag_stage_action_new_desc" : "tag_stage_action_full_desc"))
                        .font(AppTypography.bodySmall.font)
                        .foregroundStyle(s.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .padding(Spacing.md)
            .background(s.surfaceContainer)
            .clipShape(AppShapes.card)
            .overlay(
                AppShapes.card.stroke(action == .new ? s.primary : s.outlineVariant,
                                      lineWidth: action == .new ? 1.5 : 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(action == .new ? "tag_stage_option_new" : "tag_stage_option_full")
    }
}

/// 单选圈：20dp 描边圈 + 选中内点（推荐项预选视觉）。
struct RadioCircle: View {
    let selected: Bool
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        ZStack {
            Circle().strokeBorder(selected ? s.primary : s.outlineVariant, lineWidth: 2)
            if selected {
                Circle().fill(s.primary).frame(width: 10, height: 10)
            }
        }
        .frame(width: 20, height: 20)
    }
}

// MARK: - 段 4：bottom_bar（常驻主/次双钮，52dp r12，16sp SemiBold）

/// 主钮 primary 实底；次钮 outlineVariant 1dp 描边幽灵；主钮 disabled 占位（过渡态）。
struct TagScanBottomBar: View {
    let primaryTitle: String
    var primaryDisabled: Bool = false
    let secondaryTitle: String
    var onPrimary: () -> Void
    var onSecondary: () -> Void
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        HStack(spacing: 10) {
            Button(action: onPrimary) {
                Text(primaryTitle)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(s.onPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(s.primary)
                    .clipShape(RoundedRectangle(cornerRadius: AppRadius.panel, style: .continuous))
            }
            .disabled(primaryDisabled)
            .opacity(primaryDisabled ? AppAlpha.secondary : 1)
            .accessibilityIdentifier("tag_scan_primary_btn")
            Button(action: onSecondary) {
                Text(secondaryTitle)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(s.onSurfaceVariant)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: AppRadius.panel, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: AppRadius.panel, style: .continuous)
                            .stroke(s.outlineVariant, lineWidth: 1)
                    )
            }
            .accessibilityIdentifier("tag_scan_secondary_btn")
        }
    }
}
