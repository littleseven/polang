import SwiftUI

/// 人物详情/编辑页——对标 Android `PersonInfoScreen` + `PersonRelationPicker` + `PersonCoverPickerSheet`
/// （2026-08-22 Ardot 设计定稿五段式重排）。
///
/// 区块：① 120 圆形头像 + 右下相机角标（点击换封面）② 姓名输入（NAME 标签 + 灰底输入框）
/// ③ 居中「This is me」筛选 chip ④ Relationship 分组卡片（Family/Social + 自定义称呼）
/// ⑤ Photos 预览条（最多 4 张缩略图，点击换封面）。标题 `Edit person`；姓名常开编辑态，保存时提交。
/// `onBack` 关闭 fullScreenCover 并通知列表刷新。
struct PersonInfoView: View {

    let personId: Int64
    var onBack: () -> Void = {}
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: PersonDetailViewModel

    // 本地编辑态（对标 Android PersonInfoScreen local state；isEditingName 已移除——姓名常开编辑）
    @State private var nameText: String = ""
    @State private var currentRelation: String?
    @State private var customLabel: String = ""
    @State private var currentIsSelf: Bool = false
    @State private var showCoverPicker = false
    @State private var didSync = false

    init(personId: Int64, onBack: @escaping () -> Void = {}) {
        self.personId = personId
        self.onBack = onBack
        _vm = StateObject(wrappedValue: PersonDetailViewModel(personId: personId))
    }

    private var customActive: Bool { !customLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    // 随 app 主题（PoLangApp 全局 preferredColorScheme 驱动）
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    /// 关系选择器分组（spec §5 显式 chip 列表，顺序即展示顺序；标签经 RelationOptions 从 :shared SSOT 取）
    private static let familyChipIds = [
        "FATHER", "MOTHER", "SON", "DAUGHTER",
        "ELDER_BROTHER", "ELDER_SISTER", "YOUNGER_BROTHER", "YOUNGER_SISTER",
        "GRANDFATHER", "GRANDMOTHER", "SPOUSE", "PARTNER",
    ]
    private static let socialChipIds = ["FRIEND", "CLASSMATE", "COLLEAGUE", "IDOL"]

    private var family: [RelationOptionItem] { Self.options(forIds: Self.familyChipIds) }
    private var social: [RelationOptionItem] { Self.options(forIds: Self.socialChipIds) }

    private static func options(forIds ids: [String]) -> [RelationOptionItem] {
        let all = RelationOptions.all()
        return ids.compactMap { id in all.first { $0.id == id } }
    }

    var body: some View {
        ZStack {
            s.background.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                ScrollView {
                    VStack(spacing: 20) {
                        avatarHeader    // ① 头像
                        nameField      // ② 姓名
                        selfToggleChip // ③ 这是我
                        relationCard   // ④ Relationship
                        photosSection  // ⑤ Photos
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
            }
        }
        .navigationBarHidden(true)
        .task { vm.load() }
        // 头像拍摄完成（相机 cover 落在本页之上，本页不 disappear）→ 重新加载刷新封面
        .onReceive(NotificationCenter.default.publisher(for: .avatarCoverUpdated)) { _ in
            vm.load()
        }
        .onChange(of: vm.isLoading) { loading in
            if !loading, !didSync { syncLocalFromVm() }
        }
        .sheet(isPresented: $showCoverPicker) {
            coverPickerSheet
        }
    }

    private func syncLocalFromVm() {
        didSync = true
        nameText = vm.person?.name ?? ""
        currentIsSelf = vm.person?.isSelf ?? false
        currentRelation = vm.relation?.predicate
        customLabel = vm.relation?.customLabel ?? ""
    }

    private func close() {
        dismiss()
        onBack()
    }

    // MARK: 保存（对标 Android doSave）

    private func doSave() {
        let trimmed = nameText.trimmingCharacters(in: .whitespacesAndNewlines)
        vm.saveName(trimmed.isEmpty ? nil : trimmed)
        vm.saveRelation(predicate: currentRelation, customLabel: customLabel)
        vm.saveSelf(currentIsSelf)
        close()
    }

    private func resetRelation() {
        currentRelation = nil
        customLabel = ""
    }

    // MARK: 顶栏

    private var topBar: some View {
        HStack(spacing: 8) {
            Button { close() } label: {
                MatIcon(name: "mat_o_arrow_back", size: 18)
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            .accessibilityIdentifier("person_info_back")
            Text(L("Edit person"))
                .font(.system(size: CGFloat(TopBarTokens.titleFontSize), weight: .medium))
                .foregroundColor(s.onBackground)
            Spacer()
            Button { resetRelation() } label: {
                MatIcon(name: "mat_o_undo", size: 18)
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel(Text(L("Reset")))
            Button { doSave() } label: {
                MatIcon(name: "mat_o_check", size: 18)
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel(Text(L("Save")))
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
    }

    // MARK: ① 头像（120 圆形人脸感知封面 + 右下相机角标=拍摄头像入口，点图换封面）

    private var avatarHeader: some View {
        ZStack(alignment: .bottomTrailing) {
            Button { showCoverPicker = true } label: {
                Group {
                    if let lid = coverLocalId {
                        ThumbnailView(localIdentifier: lid, faceFocusY: coverFocusY, cornerRadius: 0)
                            .frame(width: 120, height: 120)
                            .clipShape(Circle())
                    } else {
                        // 无封面占位：灰底 + 人脸图标
                        ZStack {
                            Circle().fill(s.surfaceVariant)
                            MatIcon(name: "face.smiling", size: 48)
                                .foregroundColor(s.onSurfaceVariant)
                        }
                        .frame(width: 120, height: 120)
                    }
                }
            }
            .buttonStyle(.plain)
            // 相机角标 = 「拍摄头像」入口（2026-09-16 主导航统一，main-nav.yaml §3）：
            // 登记 pending → MainTabView 观察后弹出相机 fullScreenCover（默认前置+提示文案），
            // 拍照落库自动设为本人物封面并落回本页（avatarCoverUpdated 通知刷新封面显示）。
            Button {
                AvatarCaptureController.shared.begin(target: .person(vm.personId), origin: .peoplePage)
            } label: {
                cameraBadge
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("person_avatar_capture")
        }
        .padding(.top, 16)
    }

    /// 当前封面（cover_media_id）的 localIdentifier / faceFocusY，从簇候选解析。
    private var coverLocalId: String? {
        guard let cid = vm.person?.coverMediaId else { return nil }
        return vm.coverCandidates.first { $0.mediaId == cid }?.localIdentifier
    }
    private var coverFocusY: Float? {
        guard let cid = vm.person?.coverMediaId else { return nil }
        return vm.coverCandidates.first { $0.mediaId == cid }?.faceFocusY
    }

    /// 右下 32 圆形相机角标（primary 底 + background 2dp 描边环）
    private var cameraBadge: some View {
        MatIcon(name: "mat_o_photo_camera", size: 16)
            .foregroundColor(s.onPrimary)
            .frame(width: 32, height: 32)
            .background(Circle().fill(s.primary))
            .overlay(Circle().stroke(s.background, lineWidth: 2))
    }

    // MARK: ② 姓名（常开编辑态，保存时提交；非空时尾随清空按钮）

    private var nameField: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("Name").uppercased())
                .font(.system(size: 11, weight: .semibold))
                .tracking(1)
                .foregroundColor(s.onSurfaceVariant)
            HStack(spacing: 4) {
                TextField(L("Add name"), text: $nameText)
                    .font(.system(size: 16))
                    .foregroundColor(s.onSurface)
                    .submitLabel(.done)
                if !nameText.isEmpty {
                    Button {
                        nameText = ""
                    } label: {
                        MatIcon(name: "mat_o_close", size: 18)
                            .foregroundColor(s.onSurfaceVariant)
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.leading, 14)
            .padding(.trailing, 6)
            .padding(.vertical, 4)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.card, style: .continuous)
                    .fill(s.surfaceContainerLow))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: ③ 「这是我」筛选 chip（居中，选中态带 check）

    private var selfToggleChip: some View {
        Button {
            currentIsSelf.toggle()
        } label: {
            HStack(spacing: 6) {
                if currentIsSelf {
                    MatIcon(name: "mat_o_check", size: 18)
                }
                Text(L("This is me"))
                    .font(.system(size: 14, weight: .medium))
            }
            .foregroundColor(currentIsSelf ? s.onPrimaryContainer : s.onSurfaceVariant)
            .padding(.horizontal, 16)
            .frame(height: ChipTokens.height)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.small, style: .continuous)
                    .fill(currentIsSelf ? s.primaryContainer : s.surfaceVariant.opacity(0.5)))
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }

    // MARK: ④ Relationship 分组卡片（Family/Social chip 组 + 自定义称呼）

    private var relationCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("This person is my…"))
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(s.onSurface)
            chipGroup(title: L("Family"), options: family)
            chipGroup(title: L("Social"), options: social)
            // 自定义称呼
            VStack(alignment: .leading, spacing: 4) {
                Text(L("Custom label"))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(s.onSurfaceVariant)
                TextField(L("e.g. childhood buddy, second son"), text: $customLabel)
                    .font(.system(size: 14))
                    .foregroundColor(s.onSurface)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: AppRadius.button, style: .continuous)
                        .stroke(s.outline))
                Text(L("Custom text takes priority when filled"))
                    .font(.system(size: 11))
                    .foregroundColor(s.onSurfaceVariant)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: PersonTokens.cardRadius, style: .continuous)
                .fill(s.surfaceContainer))
    }

    private func chipGroup(title: String, options: [RelationOptionItem]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(s.onSurfaceVariant)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 6)], alignment: .leading, spacing: 6) {
                ForEach(options) { opt in
                    let selected = !customActive && currentRelation == opt.id
                    Button {
                        currentRelation = opt.id
                        customLabel = ""
                    } label: {
                        Text(opt.label)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(selected ? s.onPrimaryContainer : s.onSurfaceVariant)
                            .padding(.horizontal, 12)
                            .frame(maxWidth: .infinity, minHeight: ChipTokens.height)
                            .background(
                                RoundedRectangle(cornerRadius: AppRadius.small, style: .continuous)
                                    .fill(selected ? s.primaryContainer : s.surfaceVariant.opacity(0.5)))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: ⑤ Photos 预览条（最多 4 张 80pt 缩略图，点击进封面选择器）

    @ViewBuilder
    private var photosSection: some View {
        if !vm.coverCandidates.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(L("Photos"))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(s.onSurface)
                    Spacer()
                    Text(photosCountText)
                        .font(.system(size: 12))
                        .foregroundColor(s.onSurfaceVariant)
                }
                HStack(spacing: 8) {
                    ForEach(Array(vm.coverCandidates.prefix(4)), id: \.mediaId) { cand in
                        Button { showCoverPicker = true } label: {
                            ThumbnailView(
                                localIdentifier: cand.localIdentifier,
                                faceFocusY: cand.faceFocusY,
                                cornerRadius: AppRadius.button)  // radius.small(8)+2=10
                                .frame(width: 80, height: 80)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// 照片计数（复数形态分流）。数据面用封面候选集（= 簇内照片全集排序去重，VM 未另暴露计数）。
    private var photosCountText: String {
        let count = vm.coverCandidates.count
        let key = count == 1 ? "%1$d photo" : "%1$d photos"
        return String(format: L(key), count)
    }

    // MARK: 封面选择器（3 列，簇内候选，按时间倒序）

    private var coverPickerSheet: some View {
        VStack(spacing: 0) {
            HStack {
                Text(L("Select cover"))
                    .font(AppTypography.titleLarge.font)
                    .foregroundColor(.primary)
                Spacer()
                Button(L("Cancel")) { showCoverPicker = false }
                    .font(.system(size: 17))
                    .foregroundColor(.primary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            ScrollView {
                LazyVGrid(columns: [
                    GridItem(.flexible(), spacing: 4),
                    GridItem(.flexible(), spacing: 4),
                    GridItem(.flexible(), spacing: 4),
                ], spacing: 4) {
                    ForEach(vm.coverCandidates, id: \.mediaId) { cand in
                        Button {
                            vm.saveCover(cand.mediaId)
                            showCoverPicker = false
                        } label: {
                            ThumbnailView(localIdentifier: cand.localIdentifier, faceFocusY: cand.faceFocusY, cornerRadius: AppRadius.small)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
        }
        .background(Color(.systemBackground).ignoresSafeArea())
    }
}
