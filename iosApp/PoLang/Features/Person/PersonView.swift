import SwiftUI

/// 人物页（列表）——对标 Android `PersonScreen` + `PersonListItem`（2026-08-22 Ardot 设计定稿重排）。
///
/// 聚类模型：人物来自 TAG 扫描人脸聚类（`TagDatabase.persons`），非手动建人。
/// 2 列网格卡片：人脸感知封面（右下黑底张数角标）→ 名字行（未命名 primary 引导）→
/// 常驻关系行（分组配色胶囊 / 无关系描边「Add relation」引导胶囊 + info 按钮）。
/// 顶栏两行标题：`People` + 可见统计（N people · M photos）。
/// `onBack` 由 MainTabView（page 3）或 Settings（NavigationStack push）注入。
struct PersonView: View {

    var onBack: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    @StateObject private var vm = PersonViewModel()
    @State private var detailRoute: DetailRoute?

    private let columns = [
        GridItem(.flexible(), spacing: PersonTokens.gridSpacing),
        GridItem(.flexible(), spacing: PersonTokens.gridSpacing),
    ]

    var body: some View {
        ZStack {
            s.background.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                content
            }
            if let toast = vm.toast {
                toastView(toast)
            }
        }
        .navigationBarHidden(true)
        // UI 自动化页锚点（对标 gallery_grid / chat_input；人物页无占位符，挂真实页根）
        .accessibilityIdentifier("person_root")
        .task { vm.onAppear() }
        .task(id: vm.toast) {
            guard vm.toast != nil else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            vm.toast = nil
        }
        .fullScreenCover(item: $detailRoute) { route in
            PersonInfoView(personId: route.id) {
                detailRoute = nil
                vm.refresh()
            }
        }
    }

    // MARK: 顶栏（两行标题：People + 可见统计行）

    private var visibleCount: Int { vm.items.count }

    /// 可见人物照片总数（sumOf photoCounts）
    private var visiblePhotoTotal: Int {
        vm.items.reduce(0) { acc, item in acc + item.photoCount }
    }

    /// 统计行：复数形态分流拼装（N people · M photos）
    private var statsLine: String {
        let peopleKey = visibleCount == 1 ? "%1$d person" : "%1$d people"
        let photosKey = visiblePhotoTotal == 1 ? "%1$d photo" : "%1$d photos"
        return String(format: L(peopleKey), visibleCount)
            + " · "
            + String(format: L(photosKey), visiblePhotoTotal)
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Button {
                if let onBack { onBack() } else { dismiss() }
            } label: {
                MatIcon(name: "mat_o_arrow_back", size: 18)
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(L("People"))
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(s.onBackground)
                    .lineLimit(1)
                Text(statsLine)
                    .font(.system(size: 12))
                    .foregroundColor(s.onSurfaceVariant)
                    .lineLimit(1)
            }
            Spacer()
            // 筛选切换
            Button {
                vm.toggleShowAll()
            } label: {
                MatIcon(name: vm.showAll ? "mat_o_filter_list_off" : "mat_o_filter_list", size: 18)
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel(Text(vm.showAll ? L("Hide unnamed single-face groups") : L("Show all people")))
            // 重聚类
            Button {
                vm.recluster()
            } label: {
                MatIcon(name: "mat_o_autorenew", size: 18)
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel(Text(L("Re-cluster faces")))
        }
        .padding(.horizontal, 12)
        .frame(height: TopBarTokens.height)
    }

    // MARK: 内容（对齐 Android：加载/空时不渲染占位）

    @ViewBuilder
    private var content: some View {
        if vm.items.isEmpty {
            s.background
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: PersonTokens.gridSpacing) {
                    ForEach(vm.items) { person in
                        PersonCardView(
                            person: person,
                            isEditing: vm.editingPersonId == person.id,
                            onCoverTap: { openDetail(person.id) },
                            onInfoTap: { openDetail(person.id) },
                            onStartEdit: { vm.startEditing(personId: person.id) },
                            onSaveName: { name in vm.updateName(personId: person.id, name: name) },
                            onCancelEdit: { vm.stopEditing() })
                    }
                }
                .padding(.horizontal, CGFloat(PersonTokens.gridSpacing))
                .padding(.vertical, CGFloat(PersonTokens.gridSpacing))
                .padding(.bottom, 120)  // 避让悬浮 Tab
            }
        }
    }

    private func openDetail(_ id: Int64) {
        detailRoute = DetailRoute(id: id)
    }

    // MARK: toast

    private func toastView(_ message: String) -> some View {
        VStack {
            Spacer()
            Text(message)
                .font(.system(size: 13))
                .foregroundColor(s.onBackground)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Capsule().fill(s.surfaceContainerHigh.opacity(0.95)))
                .padding(.bottom, 140)
        }
        .transition(.opacity)
    }
}

/// 详情路由包装（fullScreenCover item 绑定需 Identifiable）。
private struct DetailRoute: Identifiable {
    let id: Int64
}

// MARK: - 关系分组（列表 chip 配色）

/// 关系 → 配色分组（2026-08-22 定稿）：self=primaryContainer、family=secondaryContainer、
/// social=tertiaryContainer、custom/none=surfaceContainerHighest。
/// 谓词族 SSOT = :shared `PersonRelationSupport.FAMILY_PREDICATES`（18 个亲属谓词）。
enum PersonRelationGroup {
    case selfPerson
    case family
    case social
    case custom
    case noneSet

    /// 亲属谓词族（对齐 :shared FAMILY_PREDICATES；SPOUSE/PARTNER 属亲属组）
    static let familyPredicates: Set<String> = [
        "SPOUSE", "PARTNER",
        "CHILD", "SON", "DAUGHTER",
        "PARENT", "FATHER", "MOTHER",
        "SIBLING", "ELDER_BROTHER", "ELDER_SISTER", "YOUNGER_BROTHER", "YOUNGER_SISTER",
        "GRANDPARENT", "GRANDFATHER", "GRANDMOTHER", "GRANDCHILD", "OTHER_FAMILY",
    ]

    /// 社会谓词族（spec §5 social 组）
    static let socialPredicates: Set<String> = ["FRIEND", "CLASSMATE", "COLLEAGUE", "IDOL"]

    /// 关系行 → 分组：self 优先，customLabel 非空 → custom，谓词落 family/social 集，
    /// 其余（OTHER/未知/无关系）→ none。
    static func of(isSelf: Bool, relation: PersonRelationDb?) -> PersonRelationGroup {
        if isSelf { return .selfPerson }
        guard let relation else { return .noneSet }
        let custom = relation.customLabel ?? ""
        if !custom.isEmpty { return .custom }
        if familyPredicates.contains(relation.predicate) { return .family }
        if socialPredicates.contains(relation.predicate) { return .social }
        return .noneSet
    }
}

// MARK: - 人物卡片（对标 Android PersonListItem）

private struct PersonCardView: View {
    // 随 app 主题（PoLangApp 全局 preferredColorScheme 驱动）
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    let person: PersonDisplayItem
    let isEditing: Bool
    let onCoverTap: () -> Void
    let onInfoTap: () -> Void
    let onStartEdit: () -> Void
    let onSaveName: (String) -> Void
    let onCancelEdit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            cover
            infoColumn
        }
        .background(RoundedRectangle(cornerRadius: PersonTokens.cardRadius, style: .continuous)
            .fill(s.surfaceContainerLow))
        .clipShape(RoundedRectangle(cornerRadius: PersonTokens.cardRadius, style: .continuous))
        .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
    }

    // MARK: 封面（1:1 人脸感知 + 右下张数角标）

    private var cover: some View {
        Button(action: onCoverTap) {
            ZStack(alignment: .bottomTrailing) {
                if let lid = person.coverLocalIdentifier {
                    ThumbnailView(localIdentifier: lid, faceFocusY: person.coverFaceFocusY, cornerRadius: 0)
                } else {
                    // 无封面占位：灰底 + 人脸图标
                    ZStack {
                        s.surfaceVariant
                        MatIcon(name: "face.smiling", size: 48)
                            .foregroundColor(s.onSurfaceVariant)
                    }
                }
                countBadge
            }
        }
        .buttonStyle(.plain)
        .aspectRatio(1, contentMode: .fit)
        .clipShape(UnevenRoundedRectangle(cornerRadii: .init(
            topLeading: PersonTokens.cardRadius, topTrailing: PersonTokens.cardRadius)))
        .accessibilityLabel(Text(person.name ?? String(format: L("Person #%1$d"), Int(person.id))))
    }

    /// 张数角标（黑 60% 全圆角胶囊，白 11 SemiBold 紧凑式计数）
    private var countBadge: some View {
        Text(String(format: L("%1$dP"), person.photoCount))
            .font(.system(size: 11, weight: .semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Capsule().fill(Color.black.opacity(0.6)))
            .padding(8)
    }

    // MARK: 信息列（名字行 + 常驻关系行）

    private var infoColumn: some View {
        VStack(alignment: .leading, spacing: 6) {
            // 行1：名字（未命名以 primary 引导点击命名；编辑态为行内 NameEditor）
            HStack(spacing: 8) {
                nameBlock
                Spacer(minLength: 0)
            }
            // 行2（常驻）：关系胶囊居左（无关系 → 描边「Add relation」引导胶囊），info 按钮固定右端
            HStack {
                if showRelationChip {
                    relationChip
                } else {
                    addRelationChip
                }
                Spacer(minLength: 0)
                Button(action: onInfoTap) {
                    MatIcon(name: "info.circle", size: 20)
                        .foregroundColor(s.onSurfaceVariant)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(L("Edit person")))
            }
        }
        .padding(12)
    }

    @ViewBuilder
    private var nameBlock: some View {
        if isEditing {
            NameEditor(
                initial: person.name ?? "",
                onSave: { name in onSaveName(name) },
                onCancel: onCancelEdit)
        } else {
            Text(person.name?.isEmpty == false ? person.name! : L("Add name"))
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor((person.name?.isEmpty == false) ? s.onSurface : s.primary)
                .lineLimit(1)
                .onTapGesture { onStartEdit() }
        }
    }

    private var showRelationChip: Bool {
        person.isSelf || person.relation != nil
    }

    private var relationChipLabel: String {
        if person.isSelf { return L("This is me") }
        if let rel = person.relation {
            // customLabel 为空字符串(非 nil)时也要 fallback 到 predicate 标签
            let custom = rel.customLabel ?? ""
            return custom.isEmpty ? RelationOptions.label(predicateId: rel.predicate) : custom
        }
        return L("Not set")
    }

    /// 分组配色（self/family/social/custom/none → M3 容器色对）
    private var relationChipColors: (bg: Color, fg: Color) {
        switch PersonRelationGroup.of(isSelf: person.isSelf, relation: person.relation) {
        case .selfPerson: return (s.primaryContainer, s.onPrimaryContainer)
        case .family: return (s.secondaryContainer, s.onSecondaryContainer)
        case .social: return (s.tertiaryContainer, s.onTertiaryContainer)
        case .custom, .noneSet: return (s.surfaceContainerHighest, s.onSurfaceVariant)
        }
    }

    private var relationChip: some View {
        let colors = relationChipColors
        return Text(relationChipLabel)
            .font(.system(size: 12))
            .foregroundColor(colors.fg)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(colors.bg))
            .onTapGesture { onInfoTap() }
    }

    /// 无关系空态：描边胶囊（add 图标 + Add relation），点击进详情引导设置关系
    private var addRelationChip: some View {
        HStack(spacing: 4) {
            MatIcon(name: "plus", size: 12)
            Text(L("Add relation"))
                .font(.system(size: 12))
        }
        .foregroundColor(s.onSurfaceVariant)
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(Capsule().stroke(s.outlineVariant, lineWidth: 1))
        .onTapGesture { onInfoTap() }
    }
}

// MARK: - 行内改名编辑器（对标 Android NameEditor）

private struct NameEditor: View {
    // 随 app 主题（与 PersonCardView 一致）
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    let initial: String
    let onSave: (String) -> Void
    let onCancel: () -> Void

    @State private var text: String = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 4) {
            TextField("", text: $text)
                .font(.system(size: CGFloat(PersonTokens.nameFontSize), weight: .semibold))
                .foregroundColor(s.onSurface)
                .focused($focused)
                .submitLabel(.done)
                .onSubmit { onSave(text) }
            Button { onSave(text) } label: {
                MatIcon(name: "mat_o_check", size: 18)
                    .foregroundColor(s.primary)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
            Button { onCancel() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(s.onSurfaceVariant)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
        }
        .onAppear {
            text = initial
            focused = true
        }
    }
}
