import Foundation
import Combine

/// 全局扫描中信号（对齐 Android `TagGenerationService.isScanning`：state ∈ {RUNNING, PAUSING}
/// 为 true，PAUSED 不算）。进程内单例，供 MainTabView 层（整理页落点等）以 SwiftUI 响应式消费；
/// 由 TagScanOrchestrator 在状态迁移后推送。对标 Android 的 companion MutableStateFlow。
@MainActor
final class TagScanStatusCenter: ObservableObject {
    static let shared = TagScanStatusCenter()

    /// 扫描进行中（running / pausing）；暂停（paused）不算。
    @Published private(set) var isScanning: Bool = false

    /// 扫描事件多播中继（orchestrator emit 时推送）。替代 orchestrator.onEvent 单闭包——
    /// onEvent 是单消费者属性，多个 TagScanScreen 实例（scan tab 内嵌 + 相册 fullScreenCover）
    /// 共存时后写覆盖先写、先存活实例静默冻结；经本 subject 任意多实例可共存订阅。
    let events = PassthroughSubject<ScanEvent, Never>()

    private init() {}

    /// 由 orchestrator 在会话状态变化后调用（幂等：值未变不触发发布）。
    func update(isScanning newValue: Bool) {
        guard isScanning != newValue else { return }
        isScanning = newValue
    }

    /// 由 orchestrator emit 时调用（本类型在 MainActor 上，事件即发即达各订阅方）。
    func relay(_ event: ScanEvent) {
        events.send(event)
    }
}
