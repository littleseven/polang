import Foundation
import SharedKit
import UIKit

/// 组合根：shared 接口的 iOS actual 在此构造并注入。
/// shared 不知道任何 iOS类型（spec §2.3）。各 feature 的实际注入在对应 Task 追加。
@MainActor
final class AppContainer: ObservableObject {
    static let shared = AppContainer()

    /// 相册数据通路（Task 7：IosMediaRepository + PhMediaBridge）
    let mediaRepository: IosMediaRepository

    /// 相册桥（共享给 IosAgentComposition + GalleryViewModel + Camera）
    let mediaBridge: PhMediaBridge

    /// chat 搜索桥（IosChatGalleryCapability → MediaSearchEngine，契约 §9 Chat 搜索链路）
    let searchBridge: PhSearchBridge

    /// chat 图表渲染桥（IosChartCapability draw_chart → ChartJsEngine 端侧渲染）
    let chartBridge: ChartRendererBridge

    /// chat 脚本执行桥（IosRunScriptCapability run_gallery_script → JsRuntime+JsCoreEngine 端侧沙箱）
    let runScriptBridge: RunScriptBridge

    /// chat AI 一键优化桥（IosAiOptimizeCapability ai_optimize → AiOptimizeService 固定预设
    /// 场景分析路径，产出 observation 给远程 LLM；抽卡由 Chat UI 层另行触发，editor.yaml §17）
    let aiOptimizeBridge: AiOptimizeBridge

    /// chat 导航桥（IosNavigationCapability navigate_to 执行端 → MainTabView 真实切页/弹出，
    /// 2026-09-16 主导航统一，main-nav.yaml §4）
    let navigationBridge: NavigationBridge

    /// 美颜渲染参数（全局共享，BeautyPanelView ↔ BeautyRenderer 双向绑定）
    @Published var beautyParams = BeautyRenderer.Params()

    /// Chat Agent 桥（Phase 6.2 T6：IosAgentComposition.initialize 后可用）
    private(set) var chatBridge: ChatAgentBridge?

    /// 编译期 DEBUG 标记（诊断日志 captureContent 门控用）
    private var isDebugBuild: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    private init() {
        self.mediaBridge = PhMediaBridge()
        self.mediaRepository = IosMediaRepository(bridge: mediaBridge)
        self.searchBridge = PhSearchBridge()
        self.chartBridge = ChartRendererBridge.shared
        self.runScriptBridge = RunScriptBridge.shared
        self.aiOptimizeBridge = AiOptimizeBridge.shared
        self.navigationBridge = NavigationBridge.shared
        setupAgentComposition()
    }

    /// Phase 6.2 T6：初始化 iOS Agent 组合根（访客模式）
    private func setupAgentComposition() {
        let deviceId = DeviceIdStore.shared.getOrCreate()
        IosAgentComposition.shared.initialize(
            bridge: mediaBridge,
            deviceId: deviceId,
            searchBridge: searchBridge,
            chartBridge: chartBridge,
            runScriptBridge: runScriptBridge,
            aiOptimizeBridge: aiOptimizeBridge,
            navigationBridge: navigationBridge,
            debugBuild: isDebugBuild   // 诊断日志 captureContent：DEBUG 记全文 / Release 仅指标
        )
        chatBridge = IosAgentComposition.shared.chatBridge
        // 应用用户保存的模型配置（有自定义模型则覆盖访客默认，无则保持 PICME_SERVER_DEFAULT）
        ModelConfigStore.shared.applyToOrchestrator()
    }
}

/// 设备标识持久化（identifierForVendor + UserDefaults fallback）。
/// 访客模式 X-Device-Id 用，卸载重装会变（plan 风险 8，语义可接受）。
@MainActor
final class DeviceIdStore {
    static let shared = DeviceIdStore()
    private let key = "polang_device_id"

    func getOrCreate() -> String {
        if let saved = UserDefaults.standard.string(forKey: key) {
            return saved
        }
        let id = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        UserDefaults.standard.set(id, forKey: key)
        return id
    }
}
