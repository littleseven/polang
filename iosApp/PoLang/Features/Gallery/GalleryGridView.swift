import SwiftUI
import SharedKit

/// 相册网格页（对齐 Android `GalleryScreen.kt` + `MediaGrid.kt`，量化基准 = dump 1200px/360dp）：
/// - 自建 48pt `AppTopBar`（去系统 NavigationStack 大标题），操作组对齐 GalleryTopBar：
///   模型中心→ModelDownloadCenterView（端侧模型下载中心，对齐 Settings 入口）；搜索→激活 SearchTopBar（防抖 300ms，
///   MediaSearchEngine 端侧检索）；扫描→TagScanScreen；分组菜单扁平 6 项（对齐 dump 下拉：全部/日期/人脸/人物/风景/地点），
///   NONE/DATE 实做，其余依赖 Phase 6 索引数据灰置。
/// - 网格：**固定 3 列**（dump 实测 3 列、间距/边距 7px≈2dp；列数固定、格宽 = 屏宽/列数导出）。
/// - 长按进选择模式（对齐 gallery_longpress dump）：顶栏 morph 为 返回/已选 N 项/全选/分享/删除，
///   缩略图右上角勾选圈（未选灰圈/选中蓝底白勾+浅蓝遮罩）。
/// - 冷启动 `SplashPlaceholder`、空相册 `EmptyGalleryMessage`、权限四态 `PermissionMessageView`。
/// [I18N] 所有文案走 String(localized:)，键为英文原文，入 Localizable.xcstrings 三语。
struct GalleryGridView: View {
    @StateObject private var vm: GalleryViewModel
    @StateObject private var permission = GalleryPermissionStore()
    /// 全屏大图页：nil = 关闭，否则为初始素材 localIdentifier
    @State private var pagerInitial: String? = nil
    /// 选择模式（gallery_longpress 对齐）
    @State private var isSelectionMode = false
    @State private var selected: Set<String> = []
    @State private var sharePayload: SharePayload? = nil
    @State private var showSettings = false
    // 拖拽批量选择（相-4，对齐 Android MediaGrid detectDragGestures(AfterLongPress)）
    @State private var cellFrames: [String: CGRect] = [:]
    @State private var dragVisited: Set<String> = []
    @State private var dragTargetSelected = true
    /// 方向守卫：本次手势判为竖向滚动则忽略（防选择模式下滚屏误选竖列）
    @State private var dragIsScrollLike = false
    @State private var showModelCenter = false
    /// TAG 扫描页（SP-B）：相册顶栏扫描图标进入
    @State private var showScanScreen = false
    /// 删除直调 Swift 桥（PHAssetChangeRequest 自带系统确认；成功后观察者驱动网格刷新）
    private let bridge = PhMediaBridge()

    /// Preview 专用：注入权限态与跳过系统权限查询（Preview 环境无授权上下文）
    private let permissionOverride: GalleryAccessState?
    /// 外部注入的待搜索词（chat「查看全部」回相册用）；消费后置 nil
    @Binding var pendingQuery: String?
    /// 选择模式开关回调（main-nav.yaml §0 hide_bar_when：多选态隐藏悬浮底 bar）
    var onSelectionModeChanged: ((Bool) -> Void)? = nil

    /// 固定 3 列（dump：1200px 屏宽 3×391px 列 + 7px 间距/边距 ≈ 2dp）
    private let columns = [GridItem(.flexible(), spacing: 2),
                           GridItem(.flexible(), spacing: 2),
                           GridItem(.flexible(), spacing: 2)]

    init(repository: IosMediaRepository, permissionOverride: GalleryAccessState? = nil,
         pendingQuery: Binding<String?> = .constant(nil),
         onSelectionModeChanged: ((Bool) -> Void)? = nil) {
        _vm = StateObject(wrappedValue: GalleryViewModel(repository: repository))
        self.permissionOverride = permissionOverride
        self._pendingQuery = pendingQuery
        self.onSelectionModeChanged = onSelectionModeChanged
    }

    private var accessState: GalleryAccessState { permissionOverride ?? permission.state }
    private var allItems: [MediaAsset] {
        vm.isSearchActive ? vm.searchResults : vm.groups.flatMap(\.items)
    }

    var body: some View {
        // ⚠️ 刘海屏适配：背景必须用 .background modifier，而非 ZStack 兄弟 Color.ignoresSafeArea()。
        // 兄弟 Color 忽略 safe area 会把 ZStack 布局区扩展到全屏，连带把 VStack 顶栏拉到 y=0，
        // 顶栏被刘海/灵动岛遮挡。改为 .background 后 VStack 保留顶部 safe-area inset，填色仍渗到状态栏。
        VStack(spacing: 0) {
            if vm.isSearchActive {
                SearchTopBar(
                    query: $vm.searchQuery,
                    resultCount: vm.hasSearched ? vm.searchResults.count : nil,
                    onBack: { vm.exitSearch() },
                    onQueryChange: { vm.handleQueryChange($0) }
                )
            } else if isSelectionMode {
                selectionTopBar
            } else {
                normalTopBar
            }
            content
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            // §1.3：状态栏区填 surface 色（勿露黑底）
            Color(.systemBackground).ignoresSafeArea()
        )
        .onChange(of: isSelectionMode) { active in
            onSelectionModeChanged?(active)
        }
        .fullScreenCover(isPresented: Binding(
            get: { pagerInitial != nil },
            set: { if !$0 { pagerInitial = nil } })) {
                MediaPagerView(items: allItems, initial: pagerInitial ?? "")
            }
        .sheet(item: $sharePayload) { payload in
            ActivityView(activityItems: payload.images)
        }
        .fullScreenCover(isPresented: $showSettings) {
            SettingsRoot()
        }
        .fullScreenCover(isPresented: $showModelCenter) {
            NavigationStack {
                ModelDownloadCenterView()  // 端侧模型下载中心（对齐 Settings「Model Center」入口）
            }
        }
        .fullScreenCover(isPresented: $showScanScreen) {
            TagScanScreen(onDismiss: { showScanScreen = false })
        }
        // 删除确认收敛为仅系统 PHAsset 窗（对齐 Android：无 app 层二次确认，相-10）
        .onAppear {
            if permissionOverride == nil { permission.refresh() }
            vm.start()
        }
        .onDisappear { vm.stop() }
        .onChange(of: pendingQuery) { query in
            // chat「查看全部」注入搜索词：激活搜索态 + 填框 + 防抖触发检索，然后置 nil 消费
            guard let query, !query.isEmpty else { return }
            vm.enterSearch()
            vm.searchQuery = query
            vm.handleQueryChange(query)
            pendingQuery = nil
        }
    }

    // MARK: - 顶栏（常态 / 选择态 morph，对齐 gallery_grid / gallery_longpress dump）

    /// 常态顶栏：标题 + 5 操作组（对齐 Android GalleryTopBar）：
    /// 模型中心→ModelDownloadCenterView（端侧模型下载中心）；搜索→激活 SearchTopBar（端侧搜索）；
    /// 分组菜单/设置可用。
    private var normalTopBar: some View {
        AppTopBar(title: String(localized: "Gallery")) {
            AppTopBarAction(systemName: "mat_o_cloud_download",
                            accessibilityID: "topbar_model_center") { showModelCenter = true }
            AppTopBarAction(systemName: "mat_o_play_arrow",
                            accessibilityID: "topbar_scan") {
                showScanScreen = true
            }
            AppTopBarAction(systemName: "mat_o_search",
                            accessibilityID: "topbar_search") {
                vm.enterSearch()
            }
            groupingMenu
            AppTopBarAction(systemName: "mat_o_settings",
                            accessibilityID: "topbar_settings") { showSettings = true }
        }
    }

    /// 选择态顶栏（dump：返回 ← + "已选择 N 项" + 全选/分享/删除，原位 morph）
    private var selectionTopBar: some View {
        AppTopBar(title: String(format: String(localized: "Selected %lld"), selected.count),
                  showsBackButton: true,
                  onBack: { exitSelectionMode() }) {
            AppTopBarAction(systemName: "mat_o_select_all",
                            accessibilityID: "topbar_select_all") { toggleSelectAll() }
            AppTopBarAction(systemName: "mat_o_share",
                            accessibilityID: "topbar_share",
                            isEnabled: !selected.isEmpty) { shareSelected() }
            AppTopBarAction(systemName: "mat_o_delete",
                            accessibilityID: "topbar_delete",
                            isEnabled: !selected.isEmpty) { deleteSelected() }
        }
    }

    /// 分组菜单（对齐 dump gallery_grouping_dropdown：扁平 6 项、当前项带 ✓；
    /// 人脸/人物/风景/地点依赖 Phase 6 人脸/标签/位置索引数据，列出但灰置）
    private var groupingMenu: some View {
        Menu {
            menuItem(String(localized: "All"), mode: .none)
            menuItem(String(localized: "Date"), mode: .date)
            menuItem(String(localized: "Face"), mode: .face)
            menuItem(String(localized: "Person"), mode: .person)
            menuItem(String(localized: "Landscape"), mode: .landscape)
            menuItem(String(localized: "Location"), mode: .location)
        } label: {
            MatIcon(name: "mat_o_sort", size: 22)  // Android Outlined.Sort（iOS mat_o_* 资产）
                .foregroundStyle(Color.primary)  // dump：图标深色（非 accent）
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
        }
        // Menu 内建按钮样式自带横向 padding（会把后续按钮挤出栏外），定死 36 框
        .frame(width: 36, height: 36)
        .menuIndicator(.hidden)
        .accessibilityIdentifier("topbar_grouping")
    }

    private func menuItem(_ title: String, mode: GalleryViewModel.GroupingMode) -> some View {
        Button {
            vm.groupingMode = mode
        } label: {
            if vm.groupingMode == mode {
                Label(title, systemImage: "checkmark")
            } else {
                Text(title)
            }
        }
    }

    // MARK: - 内容区

    @ViewBuilder
    private var content: some View {
        // 搜索态优先（spec states.search_active：grid → search_results）
        if vm.isSearchActive {
            searchContent
        } else {
            switch accessState {
            case .full, .limited:
                if vm.isLoading {
                    SplashPlaceholder()
                } else if vm.groups.isEmpty {
                    // 空相册同冷启动格言占位（spec empty_gallery → reuse splash_placeholder；
                    // Android GalleryScreen 同走 GallerySplashPlaceholder，相-14）
                    SplashPlaceholder()
                } else {
                    // 显式 VStack 容器：防 ScrollView 贪婪占满把 Limited banner 挤出可视区（🟡-6）
                    VStack(spacing: 0) {
                        gridBody
                        if accessState == .limited { limitedBanner }
                    }
                }
            case .notDetermined:
                PermissionMessageView(
                    title: String(localized: "Gallery Access"),
                    description: String(localized: "Allow access to browse and manage your photos and videos"),
                    primaryButton: .init(title: String(localized: "Grant Permissions"),
                                         accessibilityID: "gallery_auth_button") {
                        Task { await permission.requestAccess() }
                    })
            case .addOnly:
                PermissionMessageView(
                    title: String(localized: "Gallery Access"),
                    description: String(localized: "Add-Only Access Hint"),
                    primaryButton: .init(title: String(localized: "Open Settings"),
                                         accessibilityID: "gallery_addonly_settings") {
                        openSystemSettings()
                    })
                .accessibilityIdentifier("gallery_addonly_hint")
            case .denied:
                PermissionMessageView(
                    title: String(localized: "Photo Library Unavailable"),
                    description: String(localized: "Allow access to browse and manage your photos and videos"),
                    primaryButton: .init(title: String(localized: "Open Settings")) {
                        openSystemSettings()
                    })
                .accessibilityIdentifier("gallery_denied")
            }
        }
    }

    // MARK: - 搜索内容区（spec §search_results / §search_no_result / states.search_active）

    @ViewBuilder
    private var searchContent: some View {
        if vm.isSearchLoading {
            // spec §search_results.loading: 首次搜索无旧结果 → 全屏 Loading
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if vm.searchResults.isEmpty {
            // spec §search_no_result: 搜索结果为空 → 居中提示
            EmptyGalleryMessage(
                text: String(format: String(localized: "gallery_search_no_result_with_query"),
                             vm.lastSearchQuery))
        } else {
            // spec §search_results: 网格替换为搜索结果，复用主网格全部能力
            searchGridBody
        }
    }

    private var searchGridBody: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: GridTokens.spacing, pinnedViews: .sectionHeaders) {
                ForEach(vm.searchGroup.map { [$0] } ?? []) { group in
                    Section(header: GroupHeaderView(title: group.id,
                                                    count: group.items.count)) {
                        cells(for: group.items)
                    }
                }
            }
            .padding(GridTokens.spacing)
        }
        .accessibilityIdentifier("search_results_grid")
        // 多选态纯阻断手势（不做拖选：cellFrames 以主网格为坐标系，拖选语义在搜索结果上不可靠）——
        // 仅消费横向拖拽，挡住外层 TabView 翻页把手势（spec §0：相册详情/多选局部禁用外层滑动，
        // 对齐 Android GalleryScreen onHorizontalSwipeEnabledChange 恒生效）；纵向滚动仍归 ScrollView
        .gesture(
            DragGesture(minimumDistance: 8).onChanged { _ in },
            isEnabled: isSelectionMode
        )
    }

    private var gridBody: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 2, pinnedViews: .sectionHeaders) {
                ForEach(vm.groups) { group in
                    if group.id.isEmpty {
                        Section { cells(for: group.items) }
                    } else {
                        Section(header: GroupHeaderView(title: group.id,
                                                        count: group.items.count)) {
                            cells(for: group.items)
                        }
                    }
                }
            }
            .padding(2)  // dump：边距 7px≈2dp
        }
        .coordinateSpace(name: "galleryGrid")
        // 选择模式下直接拖拽扫格（对齐 Android detectDragGestures 分支）。
        // 方向守卫：首段位移竖直主导 → 判为滚动（选择模式下仍需可滚列表），本次手势忽略
        .gesture(
            DragGesture(minimumDistance: 8, coordinateSpace: .named("galleryGrid"))
                .onChanged { value in
                    if !dragIsScrollLike {
                        let t = value.translation
                        if abs(t.height) > abs(t.width) + 4 { dragIsScrollLike = true }
                    }
                    guard !dragIsScrollLike else { return }
                    handleDragSelect(at: value.location)
                }
                .onEnded { _ in
                    dragVisited.removeAll()
                    dragIsScrollLike = false
                },
            isEnabled: isSelectionMode
        )
        .accessibilityIdentifier("gallery_grid")
    }

    private func cells(for items: [MediaAsset]) -> some View {
        ForEach(items, id: \.uri) { asset in
            Button {
                if isSelectionMode {
                    toggleSelection(asset.uri)
                } else {
                    pagerInitial = asset.uri
                }
            } label: {
                // faceFocusY 来自 TagDatabase（PHAsset 不携带），经 vm.faceFocusYMap 注入；
                // 对齐 Android MediaGrid 读 asset.faceFocusY（Room 回填）。nil → 居中裁切。
                ThumbnailView(localIdentifier: asset.uri,
                              faceFocusY: vm.faceFocusYMap[asset.uri],
                              isVideo: asset.type == MediaType.video)
                    .overlay { selectionOverlay(for: asset.uri) }
            }
            .buttonStyle(.plain)
            .background(GeometryReader { geo in
                // 帧上报（galleryGrid 坐标空间；LazyVGrid 复用/首布局时刷新）
                Color.clear.onAppear {
                    cellFrames[asset.uri] = geo.frame(in: .named("galleryGrid"))
                }
            })
            // 仅长按进选择（0.4s）。不挂 sequenced 拖选：与 ScrollView 滚动并行识别时，
            // 滚动起手停顿 ≥0.4s 会被吞成拖选（回归实测）；拖选统一走网格层手势（见 gridBody）
            .simultaneousGesture(LongPressGesture(minimumDuration: 0.4).onEnded { _ in
                if !isSelectionMode {
                    isSelectionMode = true
                    selected = [asset.uri]
                }
            })
            .accessibilityIdentifier("cell_\(asset.uri)")
        }
    }

    /// 勾选覆盖层（对齐 gallery_longpress 截图：选择态下每格右上角 24dp 圈；
    /// 未选=灰圈描边，选中=accentColor 底白勾 + 浅蓝遮罩）
    @ViewBuilder
    private func selectionOverlay(for uri: String) -> some View {
        if isSelectionMode {
            ZStack {
                if selected.contains(uri) {
                    Color.accentColor.opacity(0.25)
                }
                VStack {
                    HStack {
                        Spacer()
                        Image(systemName: selected.contains(uri)
                              ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 24))
                            .foregroundStyle(selected.contains(uri)
                                             ? Color.accentColor : Color.white.opacity(0.9))
                            .shadow(radius: 1)
                            .padding(6)
                    }
                    Spacer()
                }
            }
            .allowsHitTesting(false)  // 手势归 cell Button，覆盖层纯展示
        }
    }

    // MARK: - 选择模式操作

    private func toggleSelection(_ uri: String) {
        if selected.contains(uri) {
            selected.remove(uri)
        } else {
            selected.insert(uri)
        }
    }

    // MARK: - 拖拽批量选择（对齐 Android onDragSelectionStart/Item/End）

    /// 拖选扫格：首格状态定加/减模式（对齐 onDragSelectionStart），后续 visited 去重
    private func handleDragSelect(at point: CGPoint) {
        guard let uri = cellFrames.first(where: { $0.value.contains(point) })?.key else { return }
        if dragVisited.isEmpty {
            dragTargetSelected = !selected.contains(uri)
        }
        guard dragVisited.insert(uri).inserted else { return }
        if dragTargetSelected {
            selected.insert(uri)
        } else {
            selected.remove(uri)
        }
    }

    private func exitSelectionMode() {
        isSelectionMode = false
        selected = []
    }

    private func toggleSelectAll() {
        if selected.count == allItems.count {
            selected = []
        } else {
            selected = Set(allItems.map(\.uri))
        }
    }

    private func shareSelected() {
        let uris = allItems.map(\.uri).filter { selected.contains($0) }  // 保持网格序
        Task {
            var images: [UIImage] = []
            for uri in uris {
                if let image = await ThumbnailLoader.shared.thumbnail(
                    for: uri, size: CGSize(width: 1000, height: 1000), highQuality: true) {
                    images.append(image)
                }
            }
            if !images.isEmpty {
                sharePayload = SharePayload(images: images)
            }
        }
    }

    private func deleteSelected() {
        _ = bridge.deleteMedia(localIdentifiers: Array(selected))
        exitSelectionMode()  // 网格经 PHPhotoLibraryObserver 自动刷新
    }

    // MARK: - Limited / 权限

    private var limitedBanner: some View {
        Button(String(localized: "Manage Accessible Photos")) {
            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let vc = scene.windows.first?.rootViewController {
                permission.presentLimitedLibraryPicker(from: vc)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color(.secondarySystemBackground))
        .accessibilityIdentifier("gallery_limited_manage")
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}

#if DEBUG
/// Preview 桩 bridge：可配数据集与权限态，不走 Photos。
private final class GalleryPreviewBridge: NSObject, IosMediaRepositoryBridge {
    var items: [IosMediaItem] = []
    func currentAccessState() -> AccessState { AccessStateFull.shared }
    func fetchAllMedia() -> [IosMediaItem] { items }
    func requestReadWriteAuthorization() {}
    func addChangeListener(listener: @escaping () -> Void) {}
    func removeChangeListener() {}
    func deleteMedia(localIdentifiers: [String]) -> Bool { true }
    func setFavorite(localIdentifier: String, favorite: Bool) -> Bool { true }
}

private func previewRepository(itemCount: Int) -> IosMediaRepository {
    let bridge = GalleryPreviewBridge()
    let dayMs: Int64 = 86_400_000
    let base: Int64 = 1_786_046_400_000  // 2026-08-07 12:00 UTC
    var items: [IosMediaItem] = []
    items.reserveCapacity(itemCount)
    for i in 0..<itemCount {
        let isVideo = i % 7 == 3
        let duration: KotlinLong? = isVideo ? KotlinLong(longLong: 5_000) : nil
        let item = IosMediaItem(localIdentifier: "P-\(i)",
                                mediaType: isVideo ? "VIDEO" : "PHOTO",
                                captureDateMs: base - Int64(i) * dayMs / 3,
                                durationMs: duration,
                                fileName: String(format: "IMG_%04d.jpg", i))
        items.append(item)
    }
    bridge.items = items
    return IosMediaRepository(bridge: bridge)
}

#Preview("空相册") {
    GalleryGridView(repository: previewRepository(itemCount: 0),
                    permissionOverride: .full)
}

#Preview("1000 图按日分组") {
    GalleryGridView(repository: previewRepository(itemCount: 1000),
                    permissionOverride: .full)
}

#Preview("Limited") {
    GalleryGridView(repository: previewRepository(itemCount: 12),
                    permissionOverride: .limited)
}

#Preview("未授权") {
    GalleryGridView(repository: previewRepository(itemCount: 0),
                    permissionOverride: .notDetermined)
}
#endif
