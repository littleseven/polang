import SwiftUI
import SharedKit
import UIKit
import MapKit

/// 按需分析（图像理解 / OCR）状态机：idle→loading→done(text)/failed(reason)。
/// 两条通道独立（同时只一条活跃），共用此状态类型。
private enum AnalysisState: Equatable {
    case idle
    case loading
    case done(text: String)
    case failed(reason: String)
}

/// 文本分享载体（Identifiable 驱动 sheet(item:)）。
private struct ShareText: Identifiable {
    let id = UUID()
    let text: String
}

/// 上滑删除手势状态机（spec gallery-grid.yaml §16b swipe_up_delete）：
/// idle → dragging(offset ≤ 0：跟手上移，向下钳 0 不跟手；|offset| ≥ 页高 25% 即 armed)
/// → flyingOut（220ms 向上飞出屏外 + 淡出）→ 成功收缩列表 / 取消·未超阈值弹回 idle。
private enum SwipeDeletePhase: Equatable {
    case idle
    case dragging(offset: CGFloat)
    case flyingOut
}

/// 大图浏览（对齐 Android `MediaPager.kt`，量化基准 = dump gallery_pager，密度 3.33）：
/// 黑底横滑分页（去系统 page dots，页间距 16）、双指缩放 1–4x + 平移（clamp 公式对齐
/// `ZoomableImage:344-417`）、单击切换顶/底栏显隐（缩放时强制隐藏）。
/// 顶栏（dump：关闭/日期/图片信息/更多 4 项，按钮 48dp、栏内容高 68dp）；
/// 底栏（dump：发送/编辑/证照/删除 4 位 SpaceEvenly）——编辑/证照 iOS 无对应功能（Phase 6），
/// 保持 4 位布局节奏灰置占位，不假造交互；删除走 PHAssetChangeRequest 系统确认窗。
/// 上滑删除（§16b swipe_up_delete）：竖直主导跟手上移、页高 25% 阈值 armed（胶囊转 error 色）、
/// 220ms 飞出淡出后走系统删除——仅确认成功收缩列表/跳相邻张/删空收起，取消弹回停留原图；
/// 缩放态、视频页、OCR/Vision 浮层可见时不响应。
/// 系统栏（§1.3 登记）：状态栏显、黑底 → preferredColorScheme(.dark) 白内容色。
struct MediaPagerView: View {
    let items: [MediaAsset]
    @State private var index: Int
    @State private var barsVisible = true
    /// 当前是否有页处于缩放态：缩放时隐藏顶/底栏（对齐 Android showBarsVisible 语义）
    @State private var isZoomed = false
    @State private var showInfo = false
    @State private var sharePayload: SharePayload? = nil
    /// 编辑器 fullScreenCover 载体（Edit 按钮 → PhotoEditorScreen）
    @State private var editTarget: EditorTarget?
    /// 证照入口 fullScreenCover 载体（ID 按钮 → IDPhotoScreen，对齐 Android 证照路由）
    @State private var idPhotoTarget: EditorTarget?
    /// 按需分析状态：图像理解 / OCR（同时只一条活跃；复用 AnalysisState）。
    /// 图像理解 → Florence-2 caption（端侧，对齐 Android describeImage）；
    /// OCR → Apple Vision（端侧，对齐 Android ML Kit）。
    @State private var visionState: AnalysisState = .idle
    @State private var ocrState: AnalysisState = .idle
    /// 分析结果文本分享载体（Copy/Share 用）
    @State private var shareTextPayload: ShareText?
    /// 「已复制」瞬时 toast
    @State private var showCopiedToast = false
    /// debug 开关门控「人脸关键点」入口（对齐 Android debugUiEnabled）
    @AppStorage("debug_ui_enabled") private var debugEnabled = false
    @State private var showFaceOverlay = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    /// 删除直调 Swift 桥（PHAssetChangeRequest 自带系统确认；成功后观察者驱动网格刷新）
    private let bridge = PhMediaBridge()
    /// 存活列表（§16b list_shrink）：仅删除成功后从这里收缩；删空自动收起预览回网格
    @State private var liveItems: [MediaAsset]
    /// 上滑删除手势阶段（只作用于当前页；详见 SwipeDeletePhase）
    @State private var swipePhase: SwipeDeletePhase = .idle
    /// 分页全屏高（§16b：结算阈值 = 页高 25%、飞出距离基准）
    @State private var pagerHeight: CGFloat = 0

    init(items: [MediaAsset], initial: String) {
        self.items = items
        _liveItems = State(initialValue: items)
        _index = State(initialValue: max(0, items.firstIndex(where: { $0.uri == initial }) ?? 0))
    }

    private var currentAsset: MediaAsset? {
        liveItems.indices.contains(index) ? liveItems[index] : nil
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(liveItems.enumerated()), id: \.element.uri) { i, asset in
                    ZoomablePagerPage(
                        localIdentifier: asset.uri,
                        isActive: i == index,
                        showFaceOverlay: showFaceOverlay,
                        onTap: { withAnimation(.easeInOut(duration: 0.2)) { barsVisible.toggle() } },
                        onLongPress: asset.type == .video ? nil : {
                            // 长按进编辑器 + 触感（对齐 Android MediaPager onLongClick，相-5）
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            editTarget = EditorTarget(localIdentifier: asset.uri)
                        },
                        onZoomChange: { zoomed in
                            if zoomed { isZoomed = true } else if i == index { isZoomed = false }
                        })
                    .padding(.horizontal, 8)  // 对齐 Android pageSpacing 16dp
                    // §16b 上滑删除：跟手位移 / 飞出淡出只作用于当前页
                    .offset(y: i == index ? swipeVisualOffset : 0)
                    .opacity(i == index ? swipeVisualOpacity : 1)
                    .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))  // 去 page dots（Android 无指示器）
            .ignoresSafeArea()
            // 🔴 标识符挂 TabView（页内容叶子侧）：挂根 ZStack 会传播覆盖 pager_back/info/more 等全部子标识符
            .accessibilityIdentifier("media_pager")
            // §16b 上滑删除：与横滑翻页共存（simultaneous），竖直主导才 consume（手势内部判定）
            .simultaneousGesture(swipeUpDeleteGesture)
            // 页全屏几何（挂在 ignoresSafeArea 之后 → 全屏高）；§16b 阈值 = 页高 25%
            .background(
                GeometryReader { geo in
                    Color.clear
                        .onAppear { pagerHeight = geo.size.height }
                        .onChange(of: geo.size.height) { pagerHeight = $0 }
                }
            )

            if barsVisible && !isZoomed {
                VStack(spacing: 0) {
                    topBar
                    Spacer()
                    if currentAsset?.type != MediaType.video { bottomBar }
                }
                .transition(.opacity)
            }
        }
        .preferredColorScheme(.dark)  // §1.3 登记：黑底页状态栏白色内容
        .statusBarHidden(isZoomed || !barsVisible)
        .sheet(isPresented: $showInfo) {
            if let asset = currentAsset { PhotoInfoSheet(asset: asset) }
        }
        .sheet(item: $sharePayload) { payload in
            ActivityView(activityItems: payload.images)
        }
        .sheet(item: $shareTextPayload) { st in
            ActivityView(activityItems: [st.text])
        }
        // 删除确认收敛为仅系统 PHAsset 窗（对齐 Android，相-10）
        .fullScreenCover(item: $editTarget) { target in
            PhotoEditorScreen(localIdentifier: target.id)
        }
        // 证照屏（对齐 Android id_photo/{sourceUri} 路由；spec specs/screens/idphoto.yaml）
        .fullScreenCover(item: $idPhotoTarget) { target in
            IDPhotoScreen(localIdentifier: target.id)
        }
        .overlay {
            // 顶部瞬时 toast：「已复制」
            if showCopiedToast {
                Text(String(localized: "copied.toast"))
                    .font(.system(size: 14))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color.black.opacity(0.8))
                    .clipShape(Capsule())
                    .transition(.opacity)
            }
        }
        .overlay(alignment: .bottom) {
            // 按需分析结果卡（图像理解 / OCR；同时只一条活跃）
            analysisOverlay
        }
        .overlay(alignment: .top) {
            // §16b 上滑删除提示胶囊：拖动中浮现于图片上方；armed（超阈值）转 error 色高亮。
            // 顶边 = 安全区 + 68pt 顶栏（顶栏起始于安全区下沿，胶囊须同基准避让）
            if case .dragging = swipePhase {
                swipeDeleteHint
                    .padding(.top, realSafeTop + 68 + Spacing.md)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: showCopiedToast)
        .onChange(of: showCopiedToast) { onset in
            guard onset else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { showCopiedToast = false }
        }
        // 相邻页缩略图预热（相-13，对齐 Android ±3 页预加载；PHCachingImageManager 窗口取 ±2 页）
        .onAppear { preloadAround(); markCurrentPageViewed() }
        .onChange(of: index) { _ in
            // 手势中断/翻页恢复路径：翻页即复位上滑删除手势（拖拽中横滑翻页不带位移跨页）
            if swipePhase != .idle { swipePhase = .idle }
            preloadAround()
            markCurrentPageViewed()
        }
    }

    /// 整理中心 USER_ENGAGED 信号回写（organize.yaml §1 value_guard）：当前页曝光即记
    /// lastViewedAt；60s 节流在 TagDatabase.updateLastViewedAt 内（60s 内重复翻页零写放大）。
    private func markCurrentPageViewed() {
        guard let uri = currentAsset?.uri else { return }
        OrganizeRepository.shared.markMediaViewed(uri: uri)
    }

    /// 当前页 ±2 页预热 1600×1600 aspectFill 请求（与 ZoomablePagerPage 实际加载参数一致），
    /// 降低翻页首帧白块。
    private func preloadAround() {
        let ids = liveItems.indices.filter { abs($0 - index) <= 2 }.map { liveItems[$0].uri }
        guard !ids.isEmpty else { return }
        ThumbnailLoader.shared.startCaching(identifiers: ids, size: CGSize(width: 1600, height: 1600))
    }

    /// 顶栏（对齐 Android `mediaPagerTopControls`：黑 0.85 底、h16/v10 padding（内容高 68dp）、
    /// 按钮 48dp）：返回 mat_arrow_back 24dp + 日期 14sp/白 0.85（间距 12），
    /// 右侧 mat_info/mat_more_horiz 22dp（间距 4）；更多菜单（图像理解/提取文字/人脸关键点，
    /// 均依赖 Phase 6 VLM/OCR/人脸管线，列出但灰置，菜单项图标 20dp 对齐 Android 下拉项）
    private var topBar: some View {
        HStack(spacing: 0) {
            Button { dismiss() } label: {
                MatIcon(name: "mat_o_arrow_back", size: 24)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("pager_back")
            Text(formattedDate(currentAsset?.captureDate))
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.85))
                .lineLimit(1)
                .padding(.leading, 12)
            Spacer()
            HStack(spacing: 4) {
                Button { showInfo = true } label: {
                    MatIcon(name: "mat_info", size: 22)
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("pager_info")
                Menu {
                    Button { runVision() } label: {
                        Label {
                            Text(String(localized: "Image Analysis"))
                        } icon: {
                            MatIcon(name: "mat_auto_awesome", size: 20)
                        }
                    }
                    .disabled(currentAsset?.type == .video)
                    Button { runOcr() } label: {
                        Label {
                            Text(String(localized: "Extract Text"))
                        } icon: {
                            MatIcon(name: "mat_text_snippet", size: 20)
                        }
                    }
                    .disabled(currentAsset?.type == .video)
                    Button { showFaceOverlay.toggle() } label: {
                        Label {
                            Text(String(localized: "Face Landmarks"))
                        } icon: {
                            MatIcon(name: "mat_face", size: 20)
                        }
                    }
                    .disabled(!debugEnabled || currentAsset?.type == .video)
                } label: {
                    MatIcon(name: "mat_more_horiz", size: 22)
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                // Menu 内建按钮样式自带横向 padding，定死 48 框防挤压顶栏布局
                .frame(width: 48, height: 48)
                .menuIndicator(.hidden)
                .accessibilityIdentifier("pager_more")
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 68)  // = Android v10 padding + 48dp 按钮
        .foregroundStyle(.white)
        .background(Color.black.opacity(0.85))
    }

    /// 底栏（对齐 Android `mediaPagerBottomBar`：4 位 SpaceEvenly、h8/v4 padding、
    /// 按钮 48dp、icon 22dp/白 0.9 + label 10sp/白 0.7）：
    /// 发送/删除实做；编辑→PhotoEditorScreen（fullScreenCover）；
    /// 证照→IDPhotoScreen（fullScreenCover，2026-08-16 对齐 Android id_photo 路由）
    private var bottomBar: some View {
        HStack(spacing: 0) {
            Spacer()
            bottomBarItem(icon: "mat_send",
                          title: String(localized: "Send"),
                          accessibilityID: "pager_share") { shareCurrent() }
            Spacer()
            bottomBarItem(icon: "mat_autofix",
                          title: String(localized: "Edit"),
                          accessibilityID: "pager_edit",
                          isEnabled: true) {
                if let uri = currentAsset?.uri { editTarget = EditorTarget(localIdentifier: uri) }
            }
            Spacer()
            bottomBarItem(icon: "mat_badge",
                          title: String(localized: "ID Photo"),
                          accessibilityID: "pager_id_photo",
                          isEnabled: true) {
                if let uri = currentAsset?.uri { idPhotoTarget = EditorTarget(localIdentifier: uri) }
            }
            Spacer()
            bottomBarItem(icon: "mat_delete",
                          title: String(localized: "Delete"),
                          accessibilityID: "pager_delete") { deleteCurrent() }
            Spacer()
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.black.opacity(0.85))
    }

    private func bottomBarItem(icon: String, title: String,
                               accessibilityID: String,
                               isEnabled: Bool = true,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                MatIcon(name: icon, size: 22)
                    .foregroundStyle(Color.white.opacity(0.9))
                Text(title)
                    .font(.system(size: 10))
                    .foregroundStyle(Color.white.opacity(0.7))
                    .lineLimit(1)
            }
            // EN "ID Photo" 等长文案：宽度不再锁死 48（minWidth 保触控目标），完整显示（2026-08-23）
            .frame(minWidth: 48, minHeight: 48, maxHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .opacity(isEnabled ? 1 : 0.3)
        .disabled(!isEnabled)
        .accessibilityIdentifier(accessibilityID)
    }

    private func formattedDate(_ captureDate: Int64?) -> String {
        guard let captureDate else { return "" }
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        return fmt.string(from: Date(timeIntervalSince1970: TimeInterval(captureDate) / 1000))
    }

    private func shareCurrent() {
        guard let asset = currentAsset else { return }
        Task {
            if let image = await ThumbnailLoader.shared.thumbnail(
                for: asset.uri, size: CGSize(width: 2000, height: 2000), highQuality: true) {
                sharePayload = SharePayload(images: [image])
            }
        }
    }

    private func deleteCurrent() {
        guard let asset = currentAsset else { return }
        _ = bridge.deleteMedia(localIdentifiers: [asset.uri])
        dismiss()  // 删除后退出大图页；网格经 PHPhotoLibraryObserver 自动刷新
    }

    // MARK: - 上滑删除（spec gallery-grid.yaml §16b swipe_up_delete）

    /// §16b available_when / suppressed_when：缩放态（scale > 1.02 → isZoomed）、
    /// 视频页（与底栏删除按钮可见条件一致）、OCR/Vision 浮层可见时均不响应。
    private var swipeUpAvailable: Bool {
        !isZoomed
            && currentAsset?.type != MediaType.video
            && visionState == .idle
            && ocrState == .idle
    }

    /// 松手结算阈值 = 页高 25%（§16b threshold，同 SwipeReview SWIPE_THRESHOLD_FRACTION）
    private var swipeThreshold: CGFloat { max(pagerHeight, 1) * 0.25 }

    /// 真实顶部安全区（刘海/灵动岛）。🔴 不能读 `GeometryProxy.safeAreaInsets`：本页根链
    /// `Color.black.ignoresSafeArea()` 已扩张 ZStack 至全屏、安全区被消费，proxy 恒报 0
    /// （同 CameraPreviewView.realSafeTop 陷阱），只能从 UIKit keyWindow 拿真实值。
    private var realSafeTop: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?.safeAreaInsets.top ?? 0
    }

    /// armed：上移量已达阈值，松手即提交；胶囊转 error 色高亮（§16b armed_style）
    private var isSwipeArmed: Bool {
        if case .dragging(let offset) = swipePhase { return -offset >= swipeThreshold }
        return false
    }

    private var swipeVisualOffset: CGFloat {
        switch swipePhase {
        case .idle: return 0
        case .dragging(let offset): return offset
        case .flyingOut: return -max(pagerHeight, 1) * 1.2  // 向上飞出屏外
        }
    }

    private var swipeVisualOpacity: Double {
        if case .flyingOut = swipePhase { return 0 }
        return 1
    }

    /// 手势本体：竖直主导（abs(dy) > abs(dx)）才 consume，不抢横向翻页；
    /// offset = min(0, dy)——只跟向上，向下钳制不跟手（§16b gesture: swipe_up）。
    private var swipeUpDeleteGesture: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                switch swipePhase {
                case .flyingOut:
                    return  // 提交动画中忽略新手势
                case .dragging:
                    break  // 跟手继续
                case .idle:
                    guard swipeUpAvailable else { return }
                }
                let t = value.translation
                guard abs(t.height) > abs(t.width) else { return }
                swipePhase = .dragging(offset: min(0, t.height))
            }
            .onEnded { _ in
                switch swipePhase {
                case .dragging(let offset) where -offset >= swipeThreshold:
                    commitSwipeUpDelete()
                case .dragging:
                    springBackSwipe()
                case .idle, .flyingOut:
                    break
                }
            }
    }

    /// 提示胶囊（§16b hint_overlay）：delete 图标 + preview_swipe_delete_hint 五语；
    /// 中性态黑底白字，armed 转 error 色高亮（tokens：scheme.error/onError）。
    private var swipeDeleteHint: some View {
        let scheme = appScheme(colorScheme)
        let armed = isSwipeArmed
        return HStack(spacing: Spacing.sm) {
            MatIcon(name: "mat_delete", size: IconSize.sm)
            Text(String(localized: "preview_swipe_delete_hint"))
                .font(AppTypography.titleSmall.font)
                .lineLimit(1)
        }
        .foregroundStyle(armed ? scheme.onError : AppColors.white)
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.sm)
        .background(armed ? scheme.error : AppColors.panelBackground)
        .clipShape(Capsule())
        .animation(.easeInOut(duration: 0.15), value: armed)
    }

    /// 提交动画时长（§16b commit_animation 定值 220ms；DesignTokens 为生成文件勿手改，
    /// Motion 阶无 220 档，就地常量化）
    private static let swipeCommitDuration: TimeInterval = 0.22

    /// 提交：向上飞出屏外 + 淡出 220ms 后回调删除通路（§16b commit_animation: fly_up_and_fade_out）
    private func commitSwipeUpDelete() {
        guard let asset = currentAsset else {
            springBackSwipe()
            return
        }
        withAnimation(.easeOut(duration: Self.swipeCommitDuration)) {
            swipePhase = .flyingOut
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.swipeCommitDuration) {
            performSwipeUpDelete(asset)
        }
    }

    /// 删除通路（§16b ios_note / §26 台账）：PHAssetChangeRequest.deleteAssets 系统强制
    /// 确认框（iOS 无静默通路）；确认成功才收缩列表，取消弹回停留原图。
    private func performSwipeUpDelete(_ asset: MediaAsset) {
        _ = bridge.deleteMediaAwaitingOutcome(localIdentifiers: [asset.uri]) { confirmed in
            if confirmed {
                shrinkAfterSwipeDelete(asset)
            } else {
                springBackSwipe()
            }
        }
    }

    /// 🔴 列表收缩语义（§16b list_shrink: on_trashed_outcome_only）：仅删除成功后收缩；
    /// 跳相邻张（删中间→停下一张，删末张→回退上一张）；删空自动收起预览回网格。
    private func shrinkAfterSwipeDelete(_ asset: MediaAsset) {
        if let i = liveItems.firstIndex(where: { $0.uri == asset.uri }) {
            liveItems.remove(at: i)
            // 删除张在当前页之前 → 下标前移一位保持当前媒体不跳张
            // （原 min 钳制对左删场景会静默跳过一张）
            if i < index { index -= 1 }
        }
        guard !liveItems.isEmpty else {
            swipePhase = .idle
            dismiss()
            return
        }
        index = min(max(0, index), liveItems.count - 1)
        swipePhase = .idle
        preloadAround()
    }

    /// 未超阈值 / 系统确认取消：弹回原位（§16b cancel_animation: spring_back）
    private func springBackSwipe() {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.82)) {
            swipePhase = .idle
        }
    }

    // MARK: - 按需分析（图像理解 / OCR）

    /// 图像理解：端侧 Florence-2 caption（复用 TagScanOrchestrator.describeImage，
    /// 对齐 Android describeImage 的 Florence-2 路径）。模型未下载/低内存/失败 → 失败态文案。
    private func runVision() {
        guard let uri = currentAsset?.uri else { return }
        visionState = .loading
        Task.detached(priority: .userInitiated) {
            let image = await ThumbnailLoader.shared.thumbnail(
                for: uri, size: CGSize(width: 2000, height: 2000), highQuality: true)
            guard let image else {
                await MainActor.run { visionState = .failed(reason: String(localized: "analysis.failed")) }
                return
            }
            let outcome = TagScanOrchestrator.shared.describeImage(image)
            await MainActor.run {
                switch outcome {
                case .success(let caption):
                    visionState = .done(text: caption)
                case .modelsNotDownloaded:
                    visionState = .failed(reason: String(localized: "vision.models.needed"))
                case .lowMemory:
                    visionState = .failed(reason: String(localized: "analysis.low.memory"))
                case .failed:
                    visionState = .failed(reason: String(localized: "analysis.failed"))
                }
            }
        }
    }

    /// 提取文字：端侧 Apple Vision OCR（OcrRecognizer，对齐 Android ML Kit 中文识别）。
    private func runOcr() {
        guard let uri = currentAsset?.uri else { return }
        ocrState = .loading
        Task.detached(priority: .userInitiated) {
            let image = await ThumbnailLoader.shared.thumbnail(
                for: uri, size: CGSize(width: 2200, height: 2200), highQuality: true)
            guard let image else {
                await MainActor.run { ocrState = .failed(reason: String(localized: "analysis.failed")) }
                return
            }
            let outcome = OcrRecognizer.recognize(image)
            await MainActor.run {
                switch outcome {
                case .success(let text):
                    ocrState = .done(text: text)
                case .noText:
                    ocrState = .failed(reason: String(localized: "ocr.no.text"))
                case .failure:
                    ocrState = .failed(reason: String(localized: "analysis.failed"))
                }
            }
        }
    }

    private func copyAnalysis(_ text: String) {
        UIPasteboard.general.string = text
        showCopiedToast = true
    }

    private func shareAnalysis(_ text: String) {
        shareTextPayload = ShareText(text: text)
    }

    /// 分析结果卡（vision 优先于 ocr；同时只一条活跃）。底栏可见时上抬避让。
    @ViewBuilder
    private var analysisOverlay: some View {
        if visionState != .idle || ocrState != .idle {
            Group {
                if visionState != .idle {
                    AnalysisResultCard(
                        title: String(localized: "Image Analysis"),
                        state: visionState, showsCharCount: false,
                        onCopy: { copyAnalysis($0) }, onShare: { shareAnalysis($0) },
                        onClose: { visionState = .idle })
                } else {
                    AnalysisResultCard(
                        title: String(localized: "Extract Text"),
                        state: ocrState, showsCharCount: true,
                        onCopy: { copyAnalysis($0) }, onShare: { shareAnalysis($0) },
                        onClose: { ocrState = .idle })
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, (barsVisible && !isZoomed) ? 88 : 12)
            .transition(.opacity)
        }
    }
}

/// 可缩放单页（对齐 Android `ZoomableImage`）：
/// MagnificationGesture 1–4x clamp；缩放态 DragGesture 平移并按 w*(scale-1)/2 clamp；
/// scale ≤ 1.02 复位并恢复横滑翻页；切页自动复位。
private struct ZoomablePagerPage: View {
    let localIdentifier: String
    let isActive: Bool
    let showFaceOverlay: Bool
    let onTap: () -> Void
    /// 长按进编辑器（视频页外层不传闭包 → 无长按）；触感由外层统一触发
    let onLongPress: (() -> Void)?
    let onZoomChange: (Bool) -> Void

    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    /// 人脸关键点检测状态机：idle→loading→success(画点)/noFace(反馈)。
    /// 对标 Android FaceLandmarkDetectionState；成功态 overlay 留在本 ZStack 跟随缩放。
    private enum FaceState {
        case idle, loading
        case success(StaticFaceDetector.Outcome)
        case noFace
    }
    @State private var faceState: FaceState = .idle

    var body: some View {
        GeometryReader { geo in
            Group {
                if let image {
                    // 🔴 静态检测 overlay 与 Image 同 ZStack、同 frame，跟踪 scaleEffect/offset（缩放时一起动）
                    ZStack {
                        Image(uiImage: image).resizable().scaledToFit()
                        if showFaceOverlay {
                            if case .success(let outcome) = faceState {
                                GalleryFaceOverlay(points: outcome.points, imageSize: outcome.imageSize)
                            } else if case .loading = faceState {
                                GalleryFaceFeedback(phase: .loading)
                            } else if case .noFace = faceState {
                                GalleryFaceFeedback(phase: .noFace)
                            }
                        }
                    }
                } else {
                    ProgressView().tint(.white)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .scaleEffect(scale)
            .offset(offset)
            .contentShape(Rectangle())
            .onTapGesture { onTap() }
            // 长按 → 编辑器（对齐 Android MediaPager onLongClick，相-5；视频页无长按）
            .onLongPressGesture(minimumDuration: 0.4) {
                onLongPress?()
            }
            .gesture(magnify(in: geo.size))
            // 缩放态高优先级平移：压过 TabView 横滑；非缩放态禁用，翻页手势不受影响
            .highPriorityGesture(pan(in: geo.size), isEnabled: scale > 1.02)
        }
        .clipped()
        .onChange(of: isActive) { active in  // iOS 16 部署目标：用单参闭包形式
            if !active { resetZoom() } else { detectIfNeeded() }
        }
        .task(id: showFaceOverlay) {
            // 用 .task(id:) 而非 onChange：父层开关变化在 TabView 页面里 onChange 偶发不触发，
            // .task(id:) 在 id 变化时必然重跑，更可靠地驱动 detectIfNeeded。
            guard showFaceOverlay else {
                faceState = .idle
                return
            }
            detectIfNeeded()
        }
        .task(id: localIdentifier) {
            faceState = .idle  // 切页重置：新图重新走 idle→loading→...
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier,
                size: CGSize(width: 1600, height: 1600),
                highQuality: true)
            detectIfNeeded()  // 图就绪后，若仍「激活+开关开」则触发检测
        }
    }

    /// 满足「开关开 + 当前页 + 图已加载 + idle」时触发一次 MNN 检测；否则空操作。
    private func detectIfNeeded() {
        guard showFaceOverlay, isActive, let image else { return }
        guard case .idle = faceState else { return }  // 已在检测/已有结果则不重复
        faceState = .loading
        let snapshot = image
        Task.detached(priority: .userInitiated) {
            let outcome = StaticFaceDetector.detect(snapshot)
            await MainActor.run {
                guard showFaceOverlay, isActive else { return }  // 仍开且仍是当前页
                if let outcome {
                    faceState = .success(outcome)
                } else {
                    faceState = .noFace
                }
            }
        }
    }

    private func magnify(in size: CGSize) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(4, max(1, lastScale * value))
                offset = clamped(offset, in: size)
                onZoomChange(scale > 1.02)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= 1.02 {
                    resetZoom()
                } else {
                    offset = clamped(offset, in: size)
                    lastOffset = offset
                }
            }
    }

    private func pan(in size: CGSize) -> some Gesture {
        DragGesture()
            .onChanged { value in
                offset = clamped(CGSize(width: lastOffset.width + value.translation.width,
                                        height: lastOffset.height + value.translation.height),
                                 in: size)
            }
            .onEnded { _ in
                lastOffset = offset
            }
    }

    /// 平移边界（对齐 Android：maxX = w*(scale-1)/2，maxY 同理）
    private func clamped(_ value: CGSize, in size: CGSize) -> CGSize {
        let maxX = size.width * (scale - 1) / 2
        let maxY = size.height * (scale - 1) / 2
        return CGSize(width: min(maxX, max(-maxX, value.width)),
                      height: min(maxY, max(-maxY, value.height)))
    }

    private func resetZoom() {
        withAnimation(.easeInOut(duration: 0.2)) {
            scale = 1
            offset = .zero
        }
        lastScale = 1
        lastOffset = .zero
        onZoomChange(false)
    }
}

/// 照片信息浮层（spec photo_info_dialog 全字段，2026-08-16 相-16 扩展：
/// +来源/位置跳地图/美学评分/人脸信息(3行)/标签 FlowRow/OCR 文本；数据源 TagDatabase，未扫描字段隐藏）
private struct PhotoInfoSheet: View {
    let asset: MediaAsset
    @Environment(\.dismiss) private var dismiss
    /// TagDB 信息行（nil=未扫描：只显示基础字段）
    private let info: TagDatabase.MediaInfoRow?

    init(asset: MediaAsset) {
        self.asset = asset
        self.info = TagDatabase.shared.mediaInfoByLocalIdentifier(asset.uri)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    infoRow(String(localized: "File Name"), value: asset.fileName)
                    infoRow(String(localized: "Type"),
                            value: asset.type == MediaType.video
                                ? String(localized: "Video") : String(localized: "Photo"))
                    infoRow(String(localized: "Captured"), value: formattedDateTime)
                    if let duration = asset.duration, duration.int64Value > 0 {
                        infoRow(String(localized: "Duration"), value: formatDuration(duration.int64Value))
                    }
                    if let source = info?.source, !source.isEmpty {
                        infoRow(String(localized: "Source"),
                                value: source.prefix(1).uppercased() + source.dropFirst())
                    }
                    locationRow
                }
                if let info {
                    Section {
                        if let score = info.aestheticScore {
                            infoRow(String(localized: "Aesthetic Score"),
                                    value: String(format: "%.1f / 10", score))
                        }
                        infoRow(String(localized: "Contains Face"),
                                value: info.hasFace
                                    ? String(localized: "Yes") : String(localized: "No"))
                        if info.hasFace, let fid = info.faceId, !fid.isEmpty {
                            infoRow(String(localized: "Person Group"), value: fid)
                        }
                        if let q = info.faceQualityScore, info.hasFace {
                            infoRow(String(localized: "Face Quality"),
                                    value: String(format: "%.2f", q))
                        }
                    }
                    if !tags.isEmpty {
                        Section(String(localized: "Tags")) {
                            tagFlow
                        }
                    }
                    if let ocr = info.ocrText, !ocr.isEmpty {
                        Section(String(localized: "OCR Text")) {
                            Text(ocr).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle(String(localized: "Info"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "Done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - 位置行（可点跳地图，对齐 Android LocationInfoRow → openMapApp）

    @ViewBuilder
    private var locationRow: some View {
        let locName = info?.locationName ?? asset.locationName
        if let locName, !locName.isEmpty {
            Button {
                openInMaps()
            } label: {
                HStack {
                    Text(String(localized: "Location")).foregroundStyle(.secondary)
                    Spacer()
                    HStack(spacing: 4) {
                        Text(locName).multilineTextAlignment(.trailing)
                        Image(systemName: "location.fill")
                            .font(.caption2).foregroundColor(Color.accentColor)
                    }
                }
            }
            .buttonStyle(.plain)
        }
    }

    private func openInMaps() {
        let lat = info?.latitude, lon = info?.longitude
        let item: MKMapItem
        if let lat, let lon {
            item = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon)))
        } else {
            item = MKMapItem()
        }
        item.name = info?.locationName ?? asset.locationName
        item.openInMaps()
    }

    // MARK: - 标签 FlowRow（labels JSON 的 objects+tags+scene 合并）

    private var tags: [String] {
        guard let json = info?.labelsJson,
              let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
        var out: [String] = []
        if let scene = obj["scene"] as? String, !scene.isEmpty { out.append(scene) }
        out.append(contentsOf: (obj["objects"] as? [String]) ?? [])
        out.append(contentsOf: (obj["tags"] as? [String]) ?? [])
        return Array(out.prefix(20))
    }

    private var tagFlow: some View {
        FlowTagRow(tags: tags)
    }

    private func infoRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).multilineTextAlignment(.trailing)
        }
    }

    private var formattedDateTime: String {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd HH:mm"
        return fmt.string(from: Date(timeIntervalSince1970: TimeInterval(asset.captureDate) / 1000))
    }

    private func formatDuration(_ ms: Int64) -> String {
        let totalSeconds = ms / 1000
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

/// 标签流式布局（spec tags: flow_row；iOS 16 用自研 wrap——Layout 协议要 iOS 16+，此处用通用 wrap 实现）
private struct FlowTagRow: View {
    let tags: [String]

    var body: some View {
        var width: CGFloat = 0
        var height: CGFloat = 0
        return GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                ForEach(Array(tags.enumerated()), id: \.offset) { _, tag in
                    tagChip(tag)
                        .padding(.trailing, 6)
                        .padding(.bottom, 6)
                        .alignmentGuide(.leading) { d in
                            if abs(width - d.width) > geo.size.width {
                                width = 0
                                height -= d.height
                            }
                            let result = width
                            if tag == tags.last {
                                width = 0
                            } else {
                                width -= d.width
                            }
                            return result
                        }
                        .alignmentGuide(.top) { _ in
                            let result = height
                            if tag == tags.last { height = 0 }
                            return result
                        }
                }
            }
        }
        .frame(height: CGFloat((tags.count / 3 + 1)) * 32)
    }

    private func tagChip(_ tag: String) -> some View {
        Text(tag)
            .font(.caption)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.secondary.opacity(0.15))
            .clipShape(Capsule())
    }
}

/// 按需分析结果卡（图像理解 caption / OCR 文本，对齐 Android OcrResultOverlay/VisionResultOverlay）。
/// 状态机：loading → ProgressView；done(text) → 文本 + Copy/Share；failed(reason) → 提示文案。
private struct AnalysisResultCard: View {
    let title: String
    let state: AnalysisState
    let showsCharCount: Bool
    let onCopy: (String) -> Void
    let onShare: (String) -> Void
    let onClose: () -> Void

    private var resultText: String? {
        if case .done(let text) = state { return text }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // 标题 + 关闭
            HStack(spacing: 0) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                Spacer()
                Button { onClose() } label: {
                    MatIcon(name: "mat_close", size: 20)
                        .foregroundStyle(.white.opacity(0.85))
                        .frame(width: 36, height: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            // 主体（按状态）
            switch state {
            case .idle:
                EmptyView()
            case .loading:
                HStack(spacing: 8) {
                    ProgressView().tint(.white)
                    Text(String(localized: "analyzing"))
                        .font(.system(size: 14))
                        .foregroundStyle(.white.opacity(0.8))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            case .done(let text):
                ScrollView(.vertical) {
                    Text(text)
                        .font(.system(size: 14))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .frame(maxHeight: 200)
                if showsCharCount {
                    Text("\(text.count) " + String(localized: "chars"))
                        .font(.system(size: 11))
                        .foregroundStyle(.white.opacity(0.6))
                }
            case .failed(let reason):
                Text(reason)
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.7))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            // 操作行（仅 done 态）
            if let resultText {
                HStack(spacing: 10) {
                    actionButton(icon: "mat_content_copy",
                                 label: String(localized: "Copy")) { onCopy(resultText) }
                    actionButton(icon: "mat_share",
                                 label: String(localized: "Share")) { onShare(resultText) }
                    Spacer()
                }
                .padding(.top, 2)
            }
        }
        .padding(16)
        .background(Color.black.opacity(0.9))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func actionButton(icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                MatIcon(name: icon, size: 16)
                    .foregroundStyle(.white)
                Text(label)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Color.white.opacity(0.12))
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    MediaPagerView(items: [], initial: "")
}
