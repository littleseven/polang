import SwiftUI
import UIKit
import SharedKit

/// 主导航路由状态（2026-09-16 主导航统一，main-nav.yaml）：
/// currentPage/showCamera/showSettings 集中一处，供 NavigationBridge（Agent navigate_to 执行端）
/// 与 AvatarCaptureController（头像拍摄入口）从视图层外驱动。
@MainActor
final class MainNavigationRouter: ObservableObject {
    /// 主 Pager 页索引：0=相册/1=整理/2=聊天/3=人物/4=回忆（与 Android MainPagerHost 1:1）
    @Published var currentPage: Int
    /// 相机 fullScreenCover（路由化：仅头像拍摄与 Agent navigate_to(camera) 进入）
    @Published var showCamera = false
    /// 设置 fullScreenCover（Agent navigate_to(settings)）
    @Published var showSettings = false
    /// 模型中心 fullScreenCover（Agent navigate_to(model_center)，对齐 Android model_center 路由）
    @Published var showModelCenter = false

    init() {
        // 初始页 = 相册(0)（对标 Android）；UI 自动化可用 launch arg `-startPage <0-4>` 指定起始页
        let args = ProcessInfo.processInfo.arguments
        if let idx = args.firstIndex(of: "-startPage"),
           args.count > idx + 1,
           let page = Int(args[idx + 1]),
           (0...4).contains(page) {
            currentPage = page
        } else {
            currentPage = 0
        }
        // UI 自动化直进相机通路（2026-09-16 相机路由化后 Pager 无相机入口）：
        // launch arg `-openCamera` → 启动即弹相机 fullScreenCover（供 UI 测试进相机）
        if args.contains("-openCamera") {
            showCamera = true
        }
    }
}

/// iOS 主页面 5 页 Pager：相册(0)/整理+扫描(1)/聊天(2)/人物(3)/回忆(4)，相册为初始页。
/// 页序与 Android MainPagerHost 1:1 对位（2026-09-16 导航统一）：
/// - 相机不在 Pager：fullScreenCover 路由，仅头像拍摄（AvatarCaptureController pending）
///   与 Agent navigate_to(camera) 进入，dismiss 落回来源页
/// - 页面身份：根页（相册/整理/人物/回忆）挂悬浮底 bar；聊天沉浸二级页不挂（返回键可出）
/// - 场景同步：相册/整理/回忆→GALLERY，聊天→CHAT，人物沿用进入前场景（shared 侧不 transition）
struct MainTabView: View {
    @StateObject private var router = MainNavigationRouter()
    @EnvironmentObject private var container: AppContainer
    @Environment(\.scenePhase) private var scenePhase
    /// chat「查看全部」回相册时带入的搜索词（消费后清 nil）
    @State private var pendingGalleryQuery: String? = nil
    /// chat EDIT 意图：跳 PhotoEditorScreen 的目标 localIdentifier
    @State private var editingImage: String? = nil
    /// 相册多选态（main-nav.yaml §0 hide_bar_when：页面自身底部 UI 激活时隐藏悬浮底 bar）
    @State private var gallerySelecting = false

    var body: some View {
        ZStack {
            // 🔴 主页面容器：TabView(.page) 原生跟手 pager（对标 Android HorizontalPager）——
            // 手指拖动 offset 实时跟随、松手物理吸附。全 5 页常驻组合（对标 beyondViewportPageCount=N-1）。
            TabView(selection: $router.currentPage) {
                GalleryGridView(
                    repository: container.mediaRepository,
                    pendingQuery: $pendingGalleryQuery,
                    onSelectionModeChanged: { gallerySelecting = $0 }
                )
                    .environmentObject(container)
                    .tag(0)
                // 整理+扫描合并页（spec organize.yaml §0 container: organize_home_route），
                // 相册页左滑即达（对标 Android gallery_left_swipe 入口）
                OrganizeHomeView()
                    .environmentObject(container)
                    .tag(1)
                ChatView(
                    onBack: { switchPage(0) },
                    onNavigateToGallery: { query in
                        pendingGalleryQuery = query
                        switchPage(0)
                    },
                    onEditImage: { lid in editingImage = lid }
                )
                .environmentObject(container)
                .tag(2)
                PersonView(onBack: { switchPage(0) })
                    .environmentObject(container)
                    .tag(3)
                // 回忆页（spec memories.yaml §2；详情页由其内部 fullScreenCover 承载）
                MemoriesView()
                    .environmentObject(container)
                    .tag(4)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))  // 去页码点（Android 无指示器）
            // 仅忽略顶部容器（状态栏全出血）；底部 home 指示条 + 键盘安全区须尊重——
            // 否则 chat 输入框既不避开键盘也不避开 home 指示条。
            // （相机已移出 Pager 改 fullScreenCover，原 tag0 底部全出血例外随之删除。）
            .ignoresSafeArea(.container, edges: .top)
        }
        // 翻页同步 SceneManager（chat 工具按场景路由，不同步会被入队不执行）。
        // 页序契约与 shared IosAgentComposition.onMainPageChanged 1:1（2026-09-16 统一后直传，不再翻译）。
        .onAppear {
            IosAgentComposition.shared.onMainPageChanged(page: Int64(router.currentPage))
            // -openCamera 启动即弹相机 cover 的场景同步（onChange 不触发初值，需在 onAppear 补）
            if router.showCamera {
                IosAgentComposition.shared.onCameraRouteChanged(active: true)
            }
            bindNavigationBridge()
        }
        .onChange(of: router.currentPage) { page in
            IosAgentComposition.shared.onMainPageChanged(page: Int64(page))
        }
        // 头像拍摄：pending 登记 → 弹相机 cover（任意来源页均可，含 PersonInfoView cover 之上）
        .onReceive(AvatarCaptureController.shared.$pending) { pending in
            if pending != nil { router.showCamera = true }
        }
        // 相机 cover 场景同步 + 取消语义（main-nav.yaml §2/§3）
        .onChange(of: router.showCamera) { shown in
            if shown {
                // cover 打开 → Scene.CAMERA（对齐 Android 相机路由 ≥RESUMED）
                IosAgentComposition.shared.onCameraRouteChanged(active: true)
            } else {
                IosAgentComposition.shared.onCameraRouteChanged(active: false)
                // 关闭 → 重发页映射恢复来源场景
                IosAgentComposition.shared.onMainPageChanged(page: Int64(router.currentPage))
                // 相机 cover 关闭时 pending 仍未消费（用户下滑/点相册缩略图取消）→ 清 pending（取消语义）
                if AvatarCaptureController.shared.pending != nil {
                    AvatarCaptureController.shared.clear()
                }
            }
        }
        .onChange(of: scenePhase) { phase in
            // 前台优先：进后台协作暂停扫描（SP-B）；回前台不自动续，由用户在扫描页点恢复
            if phase == .background { TagScanOrchestrator.shared.pauseForBackground() }
        }
        // 悬浮 Tab：根页（相册/整理/人物/回忆）显示；聊天页（沉浸式，避免遮挡输入栏）隐藏
        .overlay(alignment: .bottom) {
            if router.currentPage != 2 && !gallerySelecting {
                FloatingBottomTab(currentPage: $router.currentPage)
                    .padding(.bottom, 16)
            }
        }
        #if DEBUG
        .overlay(alignment: .topTrailing) {
            // 调试旁路：点此抓当前画面到 Documents，宿主 devicectl copy 拉取（仅 DEBUG）
            DebugCaptureButton()
                .padding(.top, 60)
                .padding(.trailing, 8)
        }
        #endif
        // 相机全屏路由（main-nav.yaml §0 camera_route）：会话按 cover 生命周期门控（isActive 恒 true，
        // dismiss 即 disappear → stop 释放）；onGalleryTap（左下相册缩略图）= dismiss 落回来源页
        .fullScreenCover(isPresented: $router.showCamera) {
            CameraPreviewView(
                onGalleryTap: { router.showCamera = false },
                isActive: true,
                onAvatarCaptureDone: { router.showCamera = false }
            )
            .environmentObject(container)
        }
        // 设置全屏路由（Agent navigate_to(settings)；页内顶栏入口仍由各页自持）
        .fullScreenCover(isPresented: $router.showSettings) {
            SettingsRoot()
        }
        // 模型中心全屏路由（Agent navigate_to(model_center)；设置页内 NavigationLink 入口不变）。
        // 必须包 NavigationStack：视图的唯一关闭手段是 toolbar 返回键，离开导航容器不渲染，
        // fullScreenCover 又不支持下滑关闭（与 GalleryGridView 顶栏入口同构）
        .fullScreenCover(isPresented: $router.showModelCenter) {
            NavigationStack { ModelDownloadCenterView() }
        }
        // chat EDIT 意图：跳 PhotoEditorScreen
        .fullScreenCover(isPresented: Binding(
            get: { editingImage != nil },
            set: { if !$0 { editingImage = nil } }
        )) {
            if let lid = editingImage {
                PhotoEditorScreen(
                    localIdentifier: lid,
                    onEditResult: { path in
                        ChatEditResultBridge.onEditResult?(path)
                    }
                )
            }
        }
    }

    /// 瞬时切页（对标 Android switchMainPage 无滑动动画；底 bar/返回出路共用）。
    private func switchPage(_ page: Int) {
        var transaction = Transaction()
        transaction.animation = nil
        withTransaction(transaction) { router.currentPage = page }
    }

    /// Agent navigate_to 执行端绑定（main-nav.yaml §4）：
    /// camera→相机 cover / gallery→切 Pager 页 0 / settings→设置 cover /
    /// model_center（含 Android 别名 llm/asr_model_manager 与中文别名）→模型中心 cover；
    /// 其余（含 debug——iOS 无 Debug 页，平台差异已登记）不受理。
    private func bindNavigationBridge() {
        let router = router
        let editing = $editingImage
        NavigationBridge.shared.handler = { destination in
            // 同视图四 cover（camera/settings/modelCenter/editing）互斥：开一个前复位其余，
            // 防 Agent 单轮连发 navigate_to 并发呈现（SwiftUI 同视图并发 cover 行为未定义）。
            // 别名与 Android NavigationCapability.parseDestination 1:1（lowercase + 中文别名）
            switch destination.lowercased() {
            case "camera", "相机", "拍照", "拍摄":
                router.showSettings = false
                router.showModelCenter = false
                editing.wrappedValue = nil
                router.showCamera = true
                return true
            case "gallery", "相册", "照片", "图库":
                router.showCamera = false
                router.showSettings = false
                router.showModelCenter = false
                editing.wrappedValue = nil
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) { router.currentPage = 0 }
                return true
            case "settings", "设置", "配置":
                router.showCamera = false
                router.showModelCenter = false
                editing.wrappedValue = nil
                router.showSettings = true
                return true
            case "model_center", "模型中心", "模型管理",
                 "llm_model_manager", "llm模型管理", "大模型管理",
                 "asr_model_manager", "asr模型管理", "语音模型管理":
                router.showCamera = false
                router.showSettings = false
                editing.wrappedValue = nil
                router.showModelCenter = true
                return true
            default:
                return false
            }
        }
    }
}

#Preview {
    MainTabView()
        .environmentObject(AppContainer.shared)
}
