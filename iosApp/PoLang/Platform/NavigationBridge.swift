import Foundation
import SharedKit

/// Swift 侧导航桥实现（2026-09-16 主导航统一，main-nav.yaml §4）：
/// SharedKit `IosNavigationCapability`（navigate_to 执行端）经本桥落到 UI 层真实切页/弹出。
///
/// SharedBridge 铁律：
/// - 绝不抛异常跨 Kotlin 边界（逃逸会 signal 6）；
/// - handler 必须在主线程执行（capability 在 Kotlin 协程线程调入）；
/// - 返回值 = 「目的地受支持且已受理」的同步判定，UI 切换异步生效。
@objc final class NavigationBridge: NSObject, IosNavigationBridge {
    static let shared = NavigationBridge()

    /// MainTabView 在 onAppear 绑定（主线程）：camera→相机 cover / gallery→切 Pager 页 0 /
    /// settings→设置 cover / model_center→模型中心 cover；别名与 Android
    /// NavigationCapability.parseDestination 1:1（lowercase + 中文别名）。
    /// 返回 false = 目的地不支持（含 debug——iOS 无 Debug 页，平台差异已登记）。nil → 一律不受理。
    var handler: ((String) -> Bool)?

    private override init() {}

    func navigateTo(destination: String) -> Bool {
        if Thread.isMainThread { return handler?(destination) ?? false }
        return DispatchQueue.main.sync { handler?(destination) ?? false }
    }
}
