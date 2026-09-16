import SwiftUI
import MetalKit
import Photos
import AVFoundation

/// 相机主页面（对标 Android CameraPreviewContent.kt）
/// 🔴 预览全出血 edge-to-edge + 控件锚 safe area
struct CameraPreviewView: View {
    @EnvironmentObject private var container: AppContainer
    /// 相册入口回调（MainTabView 注入：切到相册页）
    var onGalleryTap: () -> Void = {}
    /// 🔴 相机激活门控（对标 Android `isActivePage = currentPage == CAMERA`）：全常驻 pager 下相机页
    /// 不会 disappear，改由 MainTabView 传 `currentPage == 0` 驱动 start/stop——
    /// true→授权/start/resume；false→stop 省电防发热。
    /// 2026-09-16 导航统一后相机为 fullScreenCover 路由，MainTabView 恒传 true。
    var isActive: Bool = false
    /// 头像拍摄完成回调（MainTabView 注入：dismiss 相机 cover 落回来源页，main-nav.yaml §3）
    var onAvatarCaptureDone: () -> Void = {}
    /// 头像拍摄会话（进程内单例观察；pending 非空时本页进入头像拍摄态）
    @ObservedObject private var avatarCapture = AvatarCaptureController.shared
    /// 头像拍摄快门时间戳下界（onSaved 反查新照片用）
    @State private var avatarShutterDate: Date? = nil
    /// 进入头像态时是否从后置切到了前置（退出/完成时恢复）
    @State private var avatarRestoreBack = false
    @State private var authorized = false
    @State private var controller = CaptureSessionController()
    @State private var photoController = PhotoCaptureController()
    @State private var faceRouter = FaceEngineRouter()
    /// 引擎开关镜像（值类型 @State → 触发 toggle 视图重绘；同步到 faceRouter.useMnn）
    /// **来源优先级**：自动化验收启动参数(`-mnnEngine` / `-useMediaPipe`) > 设置页 `camera_use_mnn`(默认 true)。
    /// iOS 端 MediaPipe `face_landmarker.task` 未内置（走模型中心下载，Phase 6 才做）→ MediaPipe 无检测，
    /// 故默认 MNN；MNN 两阶段 RetinaFace det_500m → 2d106 已真机 live 验证可用。
    /// 运行时仍可点顶部 MNN/MediaPipe 胶囊切换，或在 设置→相机与美颜 切换默认引擎。
    /// 瘦脸强度走 设置→相机与美颜（持久化）或 `-slim <Float>`（验收覆盖，range -50..50）。
    @State private var useMnnEngine = Self.resolveUseMnn()
    /// 设置页引擎默认值镜像（@AppStorage 持久化；onChange 实时同步到 faceRouter，设置页改即生效）
    @AppStorage("camera_use_mnn") private var settingsUseMnn = true

    /// 引擎默认值解析：启动参数覆盖 > 设置页持久值 > 兜底 MNN(true)
    private static func resolveUseMnn() -> Bool {
        if parseLaunchFlag("-useMediaPipe") { return false }
        if parseLaunchFlag("-mnnEngine") { return true }
        return (UserDefaults.standard.object(forKey: "camera_use_mnn") as? Bool) ?? true
    }

    /// 关键点/人脸框 overlay：启动参数锁定开；否则跟随设置页持久值。
    private static func resolveShowLandmarks() -> Bool {
        if parseLaunchFlag("-showLandmarks") { return true }
        return (UserDefaults.standard.object(forKey: "camera_show_landmarks") as? Bool) ?? false
    }

    /// 启动参数解析（与 MainTabView -startPage 同模式；仅自动化验收用，不影响产品默认）。
    private static func parseLaunchFlag(_ key: String) -> Bool {
        ProcessInfo.processInfo.arguments.contains(key)
    }
    private static func parseLaunchFloat(_ key: String) -> Float? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: key), args.count > i + 1,
              let v = Float(args[i + 1]) else { return nil }
        return v
    }

    /// 比例启动覆盖（验收/诊断用；-ratio43 / -ratio169，否则 FULL）
    private static func resolveRatio() -> AspectMode {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-ratio43") { return .ratio43 }
        if args.contains("-ratio169") { return .ratio169 }
        return .full
    }

    /// 面板启动覆盖（自动化验收用）：-openPanel beauty|filter|grid|ratio|pro，
    /// 启动即开该面板（XCUITest 逐面板 dump 用，替代面板间点击切换的 flaky 导航）。
    private static func resolveInitialPanel() -> ActivePanel? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-openPanel"), args.count > i + 1 else { return nil }
        switch args[i + 1] {
        case "beauty": return .beauty
        case "filter": return .filter
        case "grid": return .grid
        case "ratio": return .ratio
        case "pro": return .pro
        default: return nil
        }
    }

    /// 🔴 逐点关键点调试 overlay（对标 Android FaceDebugOverlayBigBeauty）。
    /// `-showLandmarks` 开启：把 BeautyRenderer 消费的 106 点 + 9 对瘦脸/2 对大眼控制点画到预览，
    /// 肉眼裁决「形变区域不对/偏转」= 点云错位还是 warp 感知问题。
    @StateObject private var landmarkStore = LandmarkOverlayStore()
    /// 🔴 关键点/人脸框 overlay 开关：启动参数 `-showLandmarks` 锁定开（自动化验收）；
    /// 否则跟随 设置→相机与美颜 的 `camera_show_landmarks` 开关（对标 Android face debug overlay）。
    @State private var showLandmarks = Self.resolveShowLandmarks()
    @AppStorage("camera_show_landmarks") private var settingsShowLandmarks = false
    @State private var activePanel: ActivePanel? = Self.resolveInitialPanel()
    // 🔴 renderer 提到视图层直持：快门链路不再依赖 representable 回调往返（nil 则拍照静默失败）
    @State private var sharedRenderer: BeautyRenderer? = CameraPreviewView.makeRenderer()
    @State private var zoomPreset: CGFloat = 1.0
    @State private var selectedMode: CameraMode = .photo
    @State private var shutterFlash = false
    @State private var lastThumb: UIImage?
    // 构图网格（对标 Android currentGrid）
    @State private var currentGrid: GridType = .off
    @State private var currentRatio: AspectMode = Self.resolveRatio()
    @State private var exposureComp: Double = 0      // EV -2..2（AVCapture setExposureBias）
    // WB 模式持久化（对标 Android CameraMemoryState.whiteBalanceMode；色温值经 camera_color_temperature 持久化）
    @AppStorage("camera_wb_mode") private var whiteBalanceMode = 0  // 0=auto/1=sunny/2=cloudy/3=incandescent/4=fluorescent

    enum ActivePanel: Equatable { case beauty, filter, grid, ratio, pro }
    enum CameraMode: String, CaseIterable {
        case video, photo, document

        var displayName: String {
            switch self {
            case .video:    return String(localized: "Video")
            case .photo:    return String(localized: "Photo")
            case .document: return String(localized: "Document")
            }
        }
    }
    // 构图网格（对标 Android GridType）
    enum GridType: Equatable { case off, thirds, golden }
    // 画面比例（对标 Android CameraAspectRatio：FULL=填充裁剪，4:3/16:9=FIT 留黑边）
    enum AspectMode: Equatable { case full, ratio43, ratio169 }

    /// 视图层直建 renderer（failable init，失败时快门报错而非静默）
    private static func makeRenderer() -> BeautyRenderer? {
        guard let device = MTLCreateSystemDefaultDevice() else { return nil }
        return BeautyRenderer(device: device)
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // 预览层：铺满全屏（全出血）
                // 🔴 camera_preview 标识只挂叶子视图：挂容器会沿子树传播、覆盖子孙自身标识符
                MetalViewRepresentable(controller: controller, renderer: sharedRenderer,
                                       params: container.beautyParams, faceRouter: faceRouter,
                                       landmarkStore: landmarkStore, isActive: isActive)
                .frame(width: geo.size.width, height: geo.size.height)
                .accessibilityIdentifier("camera_preview")

                // 构图网格叠加（对标 Android CompositionGrid：虚线 white 0.5，THIRDS/GOLDEN）
                if currentGrid != .off {
                    compositionGridOverlay(width: geo.size.width, height: geo.size.height)
                }

                // 🔴 逐点关键点 overlay（-showLandmarks）：铺在预览之上、控件之下
                if showLandmarks {
                    LandmarkDebugOverlay(store: landmarkStore)
                        .frame(width: geo.size.width, height: geo.size.height)
                }

                if !authorized {
                    Color.black
                    permissionView
                } else {
                    // 控件层：锚安全区（🔴 根 GeometryReader 忽略了 safe area，
                    // geo.safeAreaInsets 恒为 0，必须用 UIKit 读真实安全区）
                    cameraOverlay(screenHeight: geo.size.height, safeTop: realSafeTop)
                }

                // 快门黑闪反馈（对标 Android CameraScreen.kt:1517-1523 拍照黑场闪屏）
                Color.black
                    .opacity(shutterFlash ? ShutterTokens.flashAlpha : 0)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .animation(.easeOut(duration: ShutterTokens.flashFadeMs / 1000), value: shutterFlash)

                // 头像拍摄态提示胶囊（main-nav.yaml §3：surface 0.85 圆角 16、top 120、labelMedium）
                if avatarCapture.pending != nil {
                    VStack {
                        Text(L("avatar_capture_hint"))
                            .font(.system(size: 12))
                            .foregroundColor(Color(.label))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(
                                RoundedRectangle(cornerRadius: 16)
                                    .fill(Color(.systemBackground).opacity(0.85))
                            )
                            .padding(.top, 120)
                        Spacer()
                    }
                    .allowsHitTesting(false)
                }
            }
        }
        .ignoresSafeArea(.all) // 🔴 全出血：整个 GeometryReader 忽略 safe area
        // 🔴 相机硬件由 isActive 门控（对标 Android `isActivePage`）：全常驻 pager 下相机页不会 disappear，
        // 改由 MainTabView 传 `currentPage == 0` 驱动 start/stop——滑离相机页 stop 省电防发热，滑回 resume。
        // 用 .task(id:) 而非 onChange：首次 view 组合也会跑（onChange 不触发初值），-startPage 0 直进相机页也覆盖。
        .task(id: isActive) {
            if isActive {
                if authorized {
                    controller.resume()   // 已授权已配置：恢复 running（不重配 session）
                } else {
                    // 首次：授权 + 完整配置 session + start
                    authorized = await controller.checkAuthorizationAndStart()
                    DebugOverlayState.shared.set("camera.auth", authorized ? "granted" : "denied")
                    if authorized {
                        // 🔴 串行挂载：走 capture 队列与 session 配置块保序（主线程直挂会和配置竞态）
                        controller.attachOutput(photoController.photoOutput)
                        controller.onFrame = { [faceRouter] pixelBuffer, ts in
                            faceRouter.enqueue(pixelBuffer: pixelBuffer, timestampMs: ts)
                        }
                        controller.faceServiceIsFrontCamera = { [faceRouter] isFront in
                            faceRouter.setFrontCamera(isFront)
                        }
                    }
                }
                // 头像拍摄态激活（cover 弹出时 pending 已登记，需等授权+配置完成后切前置）
                activateAvatarModeIfNeeded()
            } else if authorized {
                controller.stop()         // 滑离相机页：腾出相机给系统
            }
        }
        // 头像拍摄 pending 在相机已激活后登记（防御路径：正常时序是先 pending 后弹 cover）
        .onChange(of: avatarCapture.pending != nil) { hasPending in
            if hasPending { activateAvatarModeIfNeeded() }
        }
        // 离开相机（dismiss/取消）：恢复镜头朝向（完成路径在 handleAvatarCaptureIfNeeded 内恢复）
        .onDisappear { restoreLensAfterAvatarIfNeeded() }
        // 🔴 一次性配置（不随进出相机页重复）：MNN 自检 / Debug 叠加层 / 引擎 / 美颜参数 / 缩略图
        .task {
            // MNN 端侧推理离线自检（-mnnSelfTest 时跑；写 Documents/mnn-verify.txt 供验收拉取）
            MnnSelfTest.runIfRequested()
            // Debug 叠加层：设置页开关控制（默认关闭）
            DebugOverlayState.shared.isEnabled =
                (UserDefaults.standard.object(forKey: "camera_debug_overlay") as? Bool) ?? false
            // 引擎：启动参数 > 设置页；自动化验收用 -mnnEngine / -useMediaPipe 覆盖
            faceRouter.setUseMnn(useMnnEngine)
            // 瘦脸/大眼/形变强度：验收 -slim 覆盖；否则用设置页持久值（beauty_slim_debug 等）
            if let slim = Self.parseLaunchFloat("-slim") {
                container.beautyParams.slimFace = slim
            } else if let savedSlim = UserDefaults.standard.object(forKey: "beauty_slim_debug") as? Float {
                container.beautyParams.slimFace = savedSlim
            }
            if let savedBigEyes = UserDefaults.standard.object(forKey: "beauty_bigeyes_debug") as? Float {
                container.beautyParams.bigEyes = savedBigEyes
            }
            if let savedStrength = UserDefaults.standard.object(forKey: "beauty_warp_strength") as? Float {
                container.beautyParams.warpStrength = savedStrength
            }
            // 验收覆盖：-warpStrength <Float> 强制形变倍率（A/B 排查强度；默认 1.0）
            if let ws = Self.parseLaunchFloat("-warpStrength") {
                container.beautyParams.warpStrength = ws
            }
            // WB 色温记忆恢复（对标 Android 相机状态记忆水合；WB 模式本身由 @AppStorage 直持）
            if let savedTemp = UserDefaults.standard.object(forKey: "camera_color_temperature") as? Float {
                container.beautyParams.temperature = savedTemp
            }
            DebugOverlayState.shared.set("face.engine.active", faceRouter.activeLabel)
            print("[PoLang] launch.args: mnnEngine=\(useMnnEngine) slim=\(Self.parseLaunchFloat("-slim") ?? -1) " +
                  "persistedSlim=\(UserDefaults.standard.object(forKey: "beauty_slim_debug") as? Float ?? -1) " +
                  "warpStrength=\(UserDefaults.standard.object(forKey: "beauty_warp_strength") as? Float ?? 1)")
            refreshLatestThumb()
        }
        .onChange(of: settingsUseMnn) { v in
            // 设置页改了默认引擎：实时同步（启动参数锁定引擎时不覆盖，保留验收确定性）
            guard !Self.parseLaunchFlag("-mnnEngine"), !Self.parseLaunchFlag("-useMediaPipe") else { return }
            useMnnEngine = v
            faceRouter.setUseMnn(v)
            print("[PoLang] face.engine (settings) → \(faceRouter.activeLabel)")
        }
        .onChange(of: settingsShowLandmarks) { v in
            // 关键点 overlay 实时同步（启动参数锁定开时不覆盖）
            guard !Self.parseLaunchFlag("-showLandmarks") else { return }
            showLandmarks = v
        }
        .onChange(of: exposureComp) { ev in
            // ProMode EV → AVCapture 曝光补偿（-2..2）
            controller.setExposureBias(Float(ev))
        }
        .onChange(of: container.beautyParams.temperature) { temp in
            // 色温持久化（WB chip 映射与手动滑杆共用此通路；恢复时写入同值，幂等无环）
            UserDefaults.standard.set(temp, forKey: "camera_color_temperature")
        }
        // 相机 stop 改由 isActive 门控（见 .task(id: isActive)）：全常驻 pager 下相机页不会 disappear，
        // onDisappear 不触发，故移除——滑离相机页由 isActive=false → controller.stop()。
    }

    // MARK: - 权限页

    /// 拉取相册最新一张照片缩略图（48pt@3x），显示在左下相册入口上
    // MARK: - 头像拍摄态（main-nav.yaml §3，对标 Android AvatarCaptureController/Finisher 契约）

    /// 进入头像拍摄态：记录原镜头朝向 → 有前置则切前置（无前置静默保持）→ 标记激活。
    private func activateAvatarModeIfNeeded() {
        guard avatarCapture.pending != nil, isActive else { return }
        if controller.position != .front,
           AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) != nil {
            avatarRestoreBack = true
            controller.switchToPosition(.front)
        }
        avatarCapture.markActivated()
    }

    /// 拍照落库后的头像收尾：设封面 → clear → 恢复镜头 → 通知宿主 dismiss 回来源页。
    private func handleAvatarCaptureIfNeeded() {
        guard let pending = avatarCapture.pending, avatarCapture.activated else { return }
        let shutter = avatarShutterDate ?? Date()
        Task {
            await AvatarCaptureFinisher.finish(target: pending.target, shutterDate: shutter)
            await MainActor.run {
                avatarCapture.clear()
                restoreLensAfterAvatarIfNeeded()
                onAvatarCaptureDone()
            }
        }
    }

    /// 头像态退出恢复后置（仅当本次激活时确实从后置切过前置）。
    private func restoreLensAfterAvatarIfNeeded() {
        guard avatarRestoreBack else { return }
        avatarRestoreBack = false
        controller.switchToPosition(.back)
    }

    private func refreshLatestThumb() {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        opts.fetchLimit = 1
        guard let asset = PHAsset.fetchAssets(with: .image, options: opts).firstObject else { return }
        let req = PHImageRequestOptions()
        req.deliveryMode = .highQualityFormat
        req.isNetworkAccessAllowed = false
        PHImageManager.default().requestImage(
            for: asset, targetSize: CGSize(width: 144, height: 144),
            contentMode: .aspectFill, options: req
        ) { image, _ in
            guard let image else { return }
            DispatchQueue.main.async { self.lastThumb = image }
        }
    }

    /// 构图网格（对标 Android CompositionGrid：white alpha 0.5，1pt，虚线 [10,10]）
    @ViewBuilder
    private func compositionGridOverlay(width: CGFloat, height: CGFloat) -> some View {
        Canvas { ctx, size in
            let isThirds = currentGrid == .thirds
            let xs = isThirds ? [size.width / 3.0, 2.0 * size.width / 3.0]
                              : [size.width * 0.382, size.width * 0.618]
            let ys = isThirds ? [size.height / 3.0, 2.0 * size.height / 3.0]
                              : [size.height * 0.382, size.height * 0.618]
            var path = Path()
            for x in xs {
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x, y: size.height))
            }
            for y in ys {
                path.move(to: CGPoint(x: 0, y: y))
                path.addLine(to: CGPoint(x: size.width, y: y))
            }
            ctx.stroke(path, with: .color(.white.opacity(0.5)),
                       style: StrokeStyle(lineWidth: 1, dash: [10, 10]))
        }
        .frame(width: width, height: height)
        .allowsHitTesting(false)
    }

    private func closePanel() { withAnimation { activePanel = nil } }

    /// 当前比例对应的拍照裁剪 h/w（FULL=nil 不裁；4:3→4/3，16:9→16/9，对标 Android 拍照 aspect crop）
    private var captureCropHPerW: CGFloat? {
        switch currentRatio {
        case .full: return nil
        case .ratio43: return 4.0 / 3.0
        case .ratio169: return 16.0 / 9.0
        }
    }

    private var permissionView: some View {
        VStack(spacing: 12) {
            Text(String(localized: "Camera Permission Required"))
            Button(String(localized: "Open Settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
        }
        .accessibilityIdentifier("camera_denied")
    }

    // MARK: - 控件层（锚安全区，不跟随预览延伸）

    /// 真实顶部安全区高度（刘海/灵动岛）。
    /// 🔴 不能读 `GeometryProxy.safeAreaInsets`：根 GeometryReader 打了 `.ignoresSafeArea(.all)`，
    /// proxy 报告的 insets 恒为 0（SwiftUI 陷阱），只能从 UIKit keyWindow 拿真实值。
    private var realSafeTop: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?.safeAreaInsets.top ?? 0
    }

    /// 真实底部安全区高度（home 指示条）。相机页忽略底部 safe area 后，底栏需自行避让。
    private var realSafeBottom: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?.safeAreaInsets.bottom ?? 0
    }

    /// 顶部内联选项 chip 行（iOS 系统相机风格：居中的胶囊组，无面板壳拉伸）。
    /// 几何全走 CameraTokens（chipSpacing/chipPaddingH/chipHeight），字号 13 对标 Android SelectorChipRow。
    private func selectorChipRow(_ chips: [(String, Bool, () -> Void)]) -> some View {
        HStack(spacing: CameraTokens.chipSpacing) {
            ForEach(chips.indices, id: \.self) { i in
                Button(action: chips[i].2) {
                    Text(LocalizedStringKey(chips[i].0))
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(chips[i].1 ? CameraTokens.cameraAccentOn : .white)
                        .padding(.horizontal, CameraTokens.chipPaddingH)
                        .frame(height: CameraTokens.chipHeight)
                        .background(
                            Capsule().fill(chips[i].1 ? CameraTokens.cameraAccent : Color.white.opacity(0.15))
                        )
                }
                .accessibilityLabel(Text(LocalizedStringKey(chips[i].0)))
            }
        }
    }

    private func cameraOverlay(screenHeight: CGFloat, safeTop: CGFloat) -> some View {
        ZStack {
            // 手势层（任何面板开启时禁用——点按语义变为收起面板）
            CameraGesturesView(controller: controller)
                .allowsHitTesting(activePanel == nil)

            // 空白点击收起层：任何面板开启时，点预览空白收起
            if activePanel != nil {
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture {
                        withAnimation { activePanel = nil }
                    }
            }

            // 顶部控件 + inline 面板（iOS 系统相机 HUD 风格）
            VStack(spacing: 8) {
                topBar(safeTop: safeTop)
                if let panel = activePanel, panel != .beauty {
                    inlinePanel(for: panel)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                // Debug 遥测浮层：受 Debug Overlay 开关控制，默认关闭，顶部居中
                DebugOverlayView()
                    .padding(.top, 4)
                Spacer()
            }

            // 底部控件（底对齐，独立层；面板开启时被覆盖而非顶起）
            VStack(spacing: 0) {
                Spacer()
                bottomControls()
            }

            // Beauty 底部矮抽屉
            if activePanel == .beauty {
                VStack(spacing: 0) {
                    Spacer()
                    ControlPanel(heightRatio: BeautyPanelTokens.heightRatio) {
                        BeautyPanelView(params: $container.beautyParams)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
    }

    // MARK: - 顶部控件（iOS 系统相机风格）

    private func topBar(safeTop: CGFloat) -> some View {
        ZStack(alignment: .top) {
            // 顶部居中横向工具栏
            topToolBar
                .padding(.top, safeTop + CameraTokens.topToolBarPaddingTop)
        }
    }

    /// 顶部横向工具栏：美颜 / 比例 / 辅助线 / 滤镜 / 专业
    private var topToolBar: some View {
        HStack(spacing: CameraTokens.topToolBarSpacing) {
            topToolBarItem(String(localized: "Beauty"), isSelected: activePanel == .beauty) {
                withAnimation { activePanel = activePanel == .beauty ? nil : .beauty }
            }
            topToolBarItem(String(localized: "Ratio"), isSelected: activePanel == .ratio) {
                withAnimation { activePanel = activePanel == .ratio ? nil : .ratio }
            }
            topToolBarItem(String(localized: "Grid"), isSelected: activePanel == .grid) {
                withAnimation { activePanel = activePanel == .grid ? nil : .grid }
            }
            topToolBarItem(String(localized: "Filter"), isSelected: activePanel == .filter) {
                withAnimation { activePanel = activePanel == .filter ? nil : .filter }
            }
            topToolBarItem(String(localized: "Pro"), isSelected: activePanel == .pro) {
                withAnimation { activePanel = activePanel == .pro ? nil : .pro }
            }
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }

    private func topToolBarItem(_ title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: isSelected ? .bold : .medium))
                .foregroundColor(isSelected ? CameraTokens.cameraAccentOn : .white)
                .padding(.horizontal, CameraTokens.topToolBarItemPaddingH)
                .padding(.vertical, CameraTokens.topToolBarItemPaddingV)
                .background(
                    RoundedRectangle(cornerRadius: CameraTokens.topToolBarItemRadius, style: .continuous)
                        .fill(isSelected ? CameraTokens.toolBarSelectedBg : CameraTokens.toolBarUnselectedBg)
                )
        }
        .accessibilityLabel(title)
    }

    // MARK: - 底部三行控件

    private func bottomControls() -> some View {
        return VStack(spacing: 20) {
            // 模式选择器（位置在变焦按钮上方）
            HStack(spacing: CameraTokens.modeSwitcherSpacing) {
                ForEach(CameraMode.allCases, id: \.self) { mode in
                    Text(mode.displayName)
                        .font(.system(size: CameraTokens.modeTabFontSize,
                                      weight: selectedMode == mode ? .bold : .regular))
                        .foregroundColor(selectedMode == mode ? .white : .white.opacity(CameraTokens.modeTabUnselectedAlpha))
                        .onTapGesture { selectedMode = mode }
                }
            }

            // 变焦条（药丸 Capsule）
            HStack(spacing: CameraTokens.zoomBarSpacing) {
                ForEach([(0.6, "0.6x"), (1.0, "1x"), (2.0, "2x"), (3.2, "3.2x")], id: \.0) { val, label in
                    let selected = abs(zoomPreset - val) < 0.01
                    Button {
                        zoomPreset = val
                        controller.setZoom(val)
                    } label: {
                        Text(label)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(selected ? .black : .white)
                            .padding(.horizontal, CameraTokens.zoomCapsulePaddingH)
                            .frame(height: CameraTokens.zoomCapsuleHeight)
                            .background(
                                Capsule().fill(selected ? Color.white : Color.black.opacity(0.5))
                            )
                    }
                }
            }
            .accessibilityIdentifier("camera_zoom_bar")

            // 缩略图 | 快门 | 翻转（回退老版本：图标 + 文字标签，对标 specs/screens/camera.yaml）
            HStack {
                ShutterSideButton(
                    identifier: "camera_gallery_thumb",
                    label: String(localized: "Gallery"),
                    action: { onGalleryTap() }
                ) {
                    Circle()
                        .fill(Color(red: 0.25, green: 0.25, blue: 0.25))
                        .frame(width: 48, height: 48)
                        .overlay(
                            Group {
                                if let lastThumb {
                                    Image(uiImage: lastThumb)
                                        .resizable()
                                        .scaledToFill()
                                } else {
                                    MatIcon(name: "photo.fill", size: 18)
                                        .foregroundColor(.white.opacity(0.5))
                                }
                            }
                        )
                        .clipShape(Circle())
                }

                Spacer()

                ShutterButton {
                    // 白闪反馈（确认点击注册，对标 Android 拍照闪屏）
                    shutterFlash = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { shutterFlash = false }
                    // 头像拍摄态：记录快门时间戳下界（落库反查新照片用）
                    if avatarCapture.pending != nil { avatarShutterDate = Date() }
                    guard let renderer = sharedRenderer else {
                        print("[PoLang] shutter.FAIL: sharedRenderer nil")
                        DebugOverlayState.shared.set("camera.shutter", "error: renderer nil")
                        return
                    }
                    let flow = CaptureFlow(photoController: photoController, renderer: renderer, cropHPerW: captureCropHPerW)
                    flow.onSaved = {
                        refreshLatestThumb()
                        handleAvatarCaptureIfNeeded()
                    }
                    flow.captureAndSave()
                }

                Spacer()

                ShutterSideButton(
                    identifier: "camera_flip",
                    label: String(localized: "Flip"),
                    action: { controller.flipCamera() }
                ) {
                    Circle()
                        .fill(Color.white.opacity(0.2))
                        .frame(width: 48, height: 48)
                        .overlay(MatIcon(name: "camera.rotate", size: 18).foregroundColor(.white))
                }
            }
            .padding(.horizontal, 40)
        }
        // 固定底距：面板开启时覆盖底栏（Z 序在上），不顶起其他 UI
        .padding(.bottom, 33 + realSafeBottom)
    }

    // MARK: - 顶部内联面板（ratio / grid / filter / pro）

    @ViewBuilder
    private func inlinePanel(for panel: ActivePanel) -> some View {
        InlineControlPanel(fillWidth: panel != .ratio && panel != .grid) {
            switch panel {
            case .ratio:
                selectorChipRow([
                    ("4:3", currentRatio == .ratio43, { currentRatio = .ratio43; closePanel() }),
                    ("16:9", currentRatio == .ratio169, { currentRatio = .ratio169; closePanel() }),
                    ("Fullscreen", currentRatio == .full, { currentRatio = .full; closePanel() }),
                ])
            case .grid:
                selectorChipRow([
                    ("Off Grid", currentGrid == .off, { currentGrid = .off; closePanel() }),
                    ("Nine Grid", currentGrid == .thirds, { currentGrid = .thirds; closePanel() }),
                    ("Golden Ratio", currentGrid == .golden, { currentGrid = .golden; closePanel() }),
                ])
            case .filter:
                ScrollView {
                    FilterSelectorView(selectedFilter: $container.beautyParams.colorFilter)
                }
                .frame(maxHeight: CameraTokens.inlineFilterPanelHeight)
            case .pro:
                ProModePanel(
                    exposure: $exposureComp,
                    whiteBalance: $whiteBalanceMode,
                    contrast: Binding(get: { Double(container.beautyParams.contrast) },
                                      set: { container.beautyParams.contrast = Float($0) }),
                    saturation: Binding(get: { Double(container.beautyParams.saturation) },
                                        set: { container.beautyParams.saturation = Float($0) }),
                    temperature: Binding(get: { Double(container.beautyParams.temperature) },
                                         set: { container.beautyParams.temperature = Float($0) })
                )
            case .beauty:
                EmptyView()
            }
        }
    }
}

// MARK: - 底部快门两侧按钮（图标 + 文字标签，对标 specs/screens/camera.yaml）

private struct ShutterSideButton<Icon: View>: View {
    let identifier: String
    let label: String
    let action: () -> Void
    @ViewBuilder let icon: () -> Icon

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                icon()
                Text(label)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(.white)
            }
        }
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - 顶部内联面板外壳（iOS 系统相机 HUD 风格：material 玻璃 + 紧凑圆角 + 轻阴影）
// fillWidth=false 时包裹内容宽度（chip 行面板选项少，撑满 420 显得过宽）；filter/pro 保持满宽。

private struct InlineControlPanel<Content: View>: View {
    var fillWidth: Bool = true
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            content()
                .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .center)
                .padding(.horizontal, Spacing.md)
                .padding(.vertical, 10)
        }
        .frame(maxWidth: fillWidth ? CameraTokens.panelMaxWidth : nil)
        .background(
            RoundedRectangle(cornerRadius: CameraTokens.panelCornerRadius, style: .continuous)
                .fill(.ultraThinMaterial)
        )
        .overlay(
            RoundedRectangle(cornerRadius: CameraTokens.panelCornerRadius, style: .continuous)
                .stroke(Color.white.opacity(0.18), lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.35), radius: 12, x: 0, y: 6)
        .padding(.horizontal, Spacing.md)
    }
}

// MARK: - ControlPanel 容器（对标 Android ControlPanel：半屏 50% + 顶部圆角 24 + 拖拽手柄 + 底部渐变遮罩 + 边框 + 实色 surface）

private struct ControlPanel<Content: View>: View {
    var onDismiss: (() -> Void)? = nil
    // 高度上限:默认半屏 50%;filter 传 CameraTokens.filterSelectorHeight(280,Android 实测≈30%)
    var maxHeight: CGFloat? = nil
    // 高度比例（优先级低于 maxHeight）
    var heightRatio: CGFloat = 0.5
    @ViewBuilder let content: () -> Content

    private var cap: CGFloat { maxHeight ?? UIScreen.main.bounds.height * heightRatio }

    var body: some View {
        ZStack(alignment: .bottom) {
            // 底部渐变遮罩（Transparent→Black0.55→Black0.82），在 surface 之后（对标 Android ControlPanel 外层 Box）
            LinearGradient(colors: [.clear, .black.opacity(0.55), .black.opacity(0.82)],
                           startPoint: .top, endPoint: .bottom)
                .frame(maxWidth: .infinity)
                .frame(height: cap + 24)
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                // 拖拽手柄 36×4（onSurface alpha 0.2）；可点关闭
                Capsule().fill(Color.white.opacity(0.2))
                    .frame(width: 36, height: 4)
                    .padding(.top, 10).padding(.bottom, 4)
                    .onTapGesture { onDismiss?() }
                // 内容自适应高度（maxHeight 上限，非 ScrollView 强制占满；对标 Android heightIn(max)）
                content()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24).padding(.vertical, 12)
            }
            .frame(maxWidth: .infinity)
            .frame(maxHeight: cap, alignment: .top)
            .background(RoundedRectangle(cornerRadius: CameraTokens.panelCornerRadius, style: .continuous)
                .fill(CameraTokens.panelBackground))
            .overlay(RoundedRectangle(cornerRadius: CameraTokens.panelCornerRadius, style: .continuous)
                .stroke(Color.white.opacity(0.25), lineWidth: 0.5))
            .shadow(color: .black.opacity(0.5), radius: 16)
        }
    }
}

// 选项 chip（Pro 面板白平衡用；与顶部 chip 同款风格，几何走 CameraTokens，字号 13）
private struct OptionButton: View {
    let titleKey: LocalizedStringKey
    let isSelected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(titleKey).font(.system(size: 13, weight: .medium))
                .foregroundColor(isSelected ? CameraTokens.cameraAccentOn : .white)
                .padding(.horizontal, CameraTokens.chipPaddingH)
                .frame(height: CameraTokens.chipHeight)
                .background(
                    Capsule().fill(isSelected ? CameraTokens.cameraAccent : Color.white.opacity(0.15))
                )
        }
        .accessibilityLabel(Text(titleKey))
    }
}

// MARK: - ProMode 面板（对标 Android ProModeControls：WB chips + EV/对比度/饱和度/色温）

private struct ProModePanel: View {
    @Binding var exposure: Double
    @Binding var whiteBalance: Int
    @Binding var contrast: Double
    @Binding var saturation: Double
    @Binding var temperature: Double

    private let wbOptions: [(label: String, value: Int)] = [
        ("Auto", 0), ("Sunny", 1), ("Cloudy", 2), ("Incandescent", 3), ("Fluorescent", 4)
    ]

    /// WB 预设 → 色温（K）映射，与 Android `whiteBalanceTemperatureKelvin`（CameraScreenActions.kt）同源，
    /// 契约见 specs/screens/camera.yaml §13；弃用 AVCapture 设备 AWB 锁定，统一走 GL/Metal 色温 tint。
    private func whiteBalanceTemperatureKelvin(_ mode: Int) -> Double {
        switch mode {
        case 1: return 5600   // Sunny
        case 2: return 6200   // Cloudy
        case 3: return 3600   // Incandescent
        case 4: return 4400   // Fluorescent
        default: return 5000  // Auto = 中性
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 8) {
                Text("White Balance").font(.system(size: 12)).foregroundStyle(.white.opacity(0.7))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(wbOptions, id: \.value) { opt in
                            OptionButton(titleKey: LocalizedStringKey(opt.label), isSelected: whiteBalance == opt.value) {
                                whiteBalance = opt.value
                                temperature = whiteBalanceTemperatureKelvin(opt.value)
                            }
                        }
                    }
                }
            }
            sliderRow(label: "Exposure", value: $exposure, range: -2...2, step: 1, display: String(format: "%+.0f", exposure))
            sliderRow(label: "Contrast", value: $contrast, range: 0...200, step: 1, display: "\(Int(contrast))")
            sliderRow(label: "Saturation", value: $saturation, range: 0...200, step: 1, display: "\(Int(saturation))")
            // 手动拖色温退出 WB 预设选中态（对标 Android onTemperatureManualChange：chip 回「自动」）
            // 默认态显示 "--"（对标 Android ProModeControls：偏差 ≤50K 视为中性；spec §13 color_temperature.default_display）
            sliderRow(label: "Color Temperature",
                      value: Binding(get: { temperature }, set: { temperature = $0; whiteBalance = 0 }),
                      range: 2000...8000, step: 50,
                      display: abs(temperature - 5000) > 50 ? "\(Int(temperature))K" : "--")
        }
    }

    private func sliderRow(label: LocalizedStringKey, value: Binding<Double>,
                           range: ClosedRange<Double>, step: Double, display: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(label).font(.system(size: 12)).foregroundStyle(.white.opacity(0.7))
                Spacer()
                Text(display).font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
            }
            // 对标 Android HyperOS 滑杆(与美颜面板同源 AppSlider;step 连续化,视觉优先)
            AppSlider(value: Float(value.wrappedValue),
                      range: Float(range.lowerBound)...Float(range.upperBound),
                      onValueChange: { value.wrappedValue = Double($0) })
        }
    }
}

// MARK: - 圆形图标按钮（右列容器，对标 Android FilledIconButton 48dp）

struct CircleIconButton: View {
    let systemName: String
    var isActive: Bool = false
    var hasIndicator: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                Circle()
                    .fill(isActive ? Color.accentColor : Color.black.opacity(0.5))
                    .frame(width: 48, height: 48)
                    .overlay(
                        MatIcon(name: systemName, size: 18)
                            .foregroundColor(isActive ? .black : .white)
                    )
                if hasIndicator {
                    Circle()
                        .fill(Color.accentColor) // 启用 badge（对标 Android primary 色）
                        .frame(width: 8, height: 8)
                        .overlay(Circle().stroke(Color.black.opacity(0.6), lineWidth: 1))
                        .padding(.top, 4)
                        .padding(.trailing, 4)
                }
            }
        }
    }
}

// MARK: - MetalView bridge

private struct MetalViewRepresentable: UIViewRepresentable {
    let controller: CaptureSessionController
    let renderer: BeautyRenderer?
    let params: BeautyRenderer.Params
    let faceRouter: FaceEngineRouter
    let landmarkStore: LandmarkOverlayStore
    /// 🔴 相机激活门控透传：isActive=false 时暂停 MTKView 自循环，防全常驻 pager 下非活跃相机页 GPU 空转发热
    var isActive: Bool = true

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView()
        view.device = MTLCreateSystemDefaultDevice()
        view.delegate = context.coordinator
        view.enableSetNeedsDisplay = false
        view.isPaused = !isActive   // 🔴 活跃才自循环渲染；非活跃暂停防 GPU 空转发热
        view.colorPixelFormat = .bgra8Unorm
        view.isOpaque = true
        view.backgroundColor = .black
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // renderer 由视图层注入（快门与预览同一实例）；兜底再自建
        if let renderer {
            renderer.params = params
            context.coordinator.renderer = renderer
        } else if let device = view.device, let fallback = BeautyRenderer(device: device) {
            fallback.params = params
            context.coordinator.renderer = fallback
        }
        context.coordinator.controller = controller
        context.coordinator.faceRouter = faceRouter
        context.coordinator.landmarkStore = landmarkStore
        return view
    }

    func updateUIView(_ uiView: MTKView, context: Context) {
        context.coordinator.renderer?.params = params
        uiView.isPaused = !isActive   // 🔴 isActive 变化同步暂停/恢复 MTKView 自循环
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MTKViewDelegate {
        var renderer: BeautyRenderer?
        var controller: CaptureSessionController?
        var faceRouter: FaceEngineRouter?
        var landmarkStore: LandmarkOverlayStore?
        private var frames = 0
        private var lastFpsTick = Date()

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        func draw(in view: MTKView) {
            // 🔴 用 readFrame() 拿 (buffer, 相机PTS毫秒)：检测端 latest.timestampMs 也来自同一 PTS，
            // 两者同域 → latestWithinWindow 的 200ms 窗口 join 才成立（此前误用墙钟 epoch ms，
            // 与相机 PTS 差万亿 ms → 恒 nil → 106 点进不了渲染器 → 瘦脸无效）。
            guard let (pb, tsMs) = controller?.readFrame() else { return }
            if let fs = faceRouter {
                if let points = fs.latestWithinWindow(currentTimestampMs: tsMs) {
                    renderer?.updateFacePoints(points, hasFace: true)
                    // 🔴 转发到逐点调试 overlay（主线程；@Published 合并重绘）
                    if let store = landmarkStore {
                        let snap = points
                        // overlay 的 aspect-fill crop 需与 BeautyRenderer 同 buffer 尺寸（portrait 720×1280）；
                        // 从当前帧实测，避免硬编码假设（裁剪错位会让人脸框/关键点整体偏移）。
                        let bw = CVPixelBufferGetWidth(pb)
                        let bh = CVPixelBufferGetHeight(pb)
                        DispatchQueue.main.async {
                            store.bufferSize = CGSize(width: bw, height: bh)
                            store.points = snap
                        }
                    }
                } else {
                    renderer?.updateFacePoints([], hasFace: false)
                }
            }
            renderer?.draw(pixelBuffer: pb, in: view)
            frames += 1
            if Date().timeIntervalSince(lastFpsTick) >= 1.0 {
                DebugOverlayState.shared.set("camera.fps", "\(frames)")
                frames = 0
                lastFpsTick = Date()
            }
        }
    }
}
