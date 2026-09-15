import SwiftUI
import UIKit
import SharedKit

/// 对标 Android MainPagerHost：5 页 Pager（相机/相册/整理+扫描/聊天/人物）
/// 相册(1)为初始页，悬浮 Tab 切换页
struct MainTabView: View {
    // 初始页 = 相册（对标 Android）；UI 自动化可用 launch arg `-startPage <0-4>` 指定起始页
    @State private var currentPage: Int = {
        guard let idx = ProcessInfo.processInfo.arguments.firstIndex(of: "-startPage"),
              ProcessInfo.processInfo.arguments.count > idx + 1,
              let page = Int(ProcessInfo.processInfo.arguments[idx + 1]),
              (0...4).contains(page) else { return 1 }
        return page
    }()
    @EnvironmentObject private var container: AppContainer
    @Environment(\.scenePhase) private var scenePhase
    @State private var showPlaceholder: String?
    /// chat「查看全部」回相册时带入的搜索词（消费后清 nil）
    @State private var pendingGalleryQuery: String? = nil
    /// chat EDIT 意图：跳 PhotoEditorScreen 的目标 localIdentifier
    @State private var editingImage: String? = nil

    var body: some View {
        ZStack {
            // 🔴 主页面容器：TabView(.page) 原生跟手 pager（对标 Android HorizontalPager）——
            // 手指拖动 offset 实时跟随、松手物理吸附。替换原「ZStack 条件渲染 + 仅 onEnded 手势」
            // （拖动期零位移、松手才跳 → 不跟手）。全 5 页常驻组合（对标 beyondViewportPageCount=N-1）。
            TabView(selection: $currentPage) {
                // onGalleryTap：相机页左下相册入口（camera_gallery_thumb）→ 切回相册页。
                // 🔴 不可省——缺省落 CameraPreviewView 默认空闭包，点击无响应（e85823015 重写时丢过一次）。
                CameraPreviewView(
                    onGalleryTap: { withAnimation(.easeInOut(duration: 0.25)) { currentPage = 1 } },
                    isActive: currentPage == 0
                )
                    .environmentObject(container)
                    .tag(0)
                GalleryGridView(repository: container.mediaRepository, pendingQuery: $pendingGalleryQuery)
                    .environmentObject(container)
                    .tag(1)
                // 整理+扫描合并页（spec organize.yaml §0 container: organize_home_route），
                // 插在 Gallery 之后：相册页左滑即达（对标 Android gallery_left_swipe 入口）
                OrganizeHomeView()
                    .environmentObject(container)
                    .tag(2)
                ChatView(
                    onBack: { currentPage = 1 },
                    onNavigateToGallery: { query in
                        pendingGalleryQuery = query
                        currentPage = 1
                    },
                    onEditImage: { lid in editingImage = lid }
                )
                .environmentObject(container)
                .tag(3)
                PersonView(onBack: { currentPage = 1 })
                    .environmentObject(container)
                    .tag(4)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))  // 去页码点（Android 无指示器）
            // 仅忽略顶部容器（状态栏全出血，保持现状）；底部 home 指示条 + 键盘安全区须尊重——
            // 否则 chat 输入框既不避开键盘也不避开 home 指示条（原 .ignoresSafeArea() 默认 .all 含 keyboard）。
            // 🔴 相机页例外：全出血到底（修复底部白条 bug），底栏避让由 CameraPreviewView 自行加 safeBottom padding。
            .ignoresSafeArea(.container, edges: currentPage == 0 ? [.top, .bottom] : .top)

            // 打标页 push（覆盖在 pager 之上）：TAG tab → TagScanScreen（SP-B）；其余占位 Coming Soon
            if let ph = showPlaceholder {
                if ph == "tag" {
                    TagScanScreen(onDismiss: { showPlaceholder = nil })
                        .transition(.opacity)
                        .zIndex(10)
                } else {
                    PlaceholderPage(title: String(localized: "Coming Soon"))
                        .transition(.opacity)
                }
            }
        }
        // 翻页同步 SceneManager（chat 工具按场景路由，不同步会被入队不执行）。
        // ⚠️ 页索引位移适配：shared IosAgentComposition.onMainPageChanged 仍按旧 4 页契约
        // （0=camera, 1=gallery, 2=chat, else=UNKNOWN）映射场景，本侧先翻译再上报——
        // 整理+扫描(2) 无专用 Scene，落 GALLERY（媒体域最近场景，保 chat_gallery 类能力可用）；
        // 人物(4) 沿用旧 else=UNKNOWN 行为。
        .onAppear {
            IosAgentComposition.shared.onMainPageChanged(page: Int64(sharedScenePage(currentPage)))
        }
        .onChange(of: currentPage) { page in
            showPlaceholder = nil
            IosAgentComposition.shared.onMainPageChanged(page: Int64(sharedScenePage(page)))
        }
        .onChange(of: scenePhase) { phase in
            // 前台优先：进后台协作暂停扫描（SP-B）；回前台不自动续，由用户在扫描页点恢复
            if phase == .background { TagScanOrchestrator.shared.pauseForBackground() }
        }
        // 悬浮 Tab：相册/整理+扫描/人物页显示；相机页（沉浸式）与聊天页（避免遮挡输入栏，返回键可出）隐藏
        // ⚠️ 页索引位移：聊天页 2→3
        .overlay(alignment: .bottom) {
            if currentPage != 0 && currentPage != 3 {
                FloatingBottomTab(currentPage: $currentPage, onPlaceholderTap: { icon in
                    showPlaceholder = icon
                })
                .padding(.bottom, 16)
            }
        }
        // 🔴 左右滑切页改由 TabView(.page) 原生跟手处理（见上方 TabView），不再用 onEnded 手势。
        #if DEBUG
        .overlay(alignment: .topTrailing) {
            // 调试旁路：点此抓当前画面到 Documents，宿主 devicectl copy 拉取（仅 DEBUG）
            DebugCaptureButton()
                .padding(.top, 60)
                .padding(.trailing, 8)
        }
        #endif
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

    /// iOS 5 页序（0=相机/1=相册/2=整理+扫描/3=聊天/4=人物）→ shared 旧 4 页场景契约的翻译。
    /// 整理+扫描无专用 Scene（SceneManager 枚举只有 CHAT/CAMERA/GALLERY/SETTINGS/DEBUG/UNKNOWN），
    /// 落 GALLERY 保媒体域能力激活；shared 侧若后续增页契约，此处是唯一适配点。
    private func sharedScenePage(_ page: Int) -> Int {
        switch page {
        case 0: return 0        // camera → CAMERA
        case 1: return 1        // gallery → GALLERY
        case 2: return 1        // 整理+扫描 → GALLERY（最近媒体域场景）
        case 3: return 2        // chat → CHAT
        default: return 3       // person → shared else 分支（UNKNOWN，沿用位移前行为）
        }
    }
}

/// 占位页（诚实占位，不造假功能）
struct PlaceholderPage: View {
    let title: String

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 12) {
                MatIcon(name: "hourglass", size: 36)
                    .foregroundColor(.white.opacity(0.3))
                Text(title)
                    .font(.system(size: 16))
                    .foregroundColor(.white.opacity(0.5))
            }
        }
    }
}

#Preview {
    MainTabView()
        .environmentObject(AppContainer.shared)
}
