import SwiftUI
import Combine

/// TAG 扫描页 v4.1 ViewModel：会话状态 / 统计快照 / 会话控制中转（spec tag-control.yaml）。
///
/// 进度信号双通道分工：本页 UI 订阅 `TagScanStatusCenter.shared.events`（多播中继，
/// 不占 orchestrator.onEvent 单闭包——scan tab 内嵌实例与相册 fullScreenCover 实例可共存，
/// 互不覆盖）→ `progress`（本轮会话 x/y）；全局「扫描中」信号一律消费
/// `TagScanStatusCenter.shared`（orchestrator emit 时推送），本层不再自推导 isScanning
/// （消双源）。环心大数字走 `LibraryCompletion`（库级口径立法）。
@MainActor
final class TagScanViewModel: ObservableObject {
    @Published private(set) var progress: TagScanSessionProgress?
    @Published private(set) var stats: ScanDbStats = .init(
        totalMedia: 0, withFace: 0, withLabels: 0, withSemantic: 0,
        personCount: 0, namedPersonCount: 0, faceEmbeddingCount: 0,
        remainingPass1: 0, remainingPass3: 0)
    @Published private(set) var hasUnfinishedSession: Bool = false
    /// 模型未就绪（glintr100/mobileclip 未下载）→ 扫描页弹提示去 Model Center。
    @Published var showModelsNeeded = false

    private let orchestrator = TagScanOrchestrator.shared
    private var cancellables = Set<AnyCancellable>()

    init() {
        TagScanStatusCenter.shared.events
            .sink { [weak self] ev in
                guard let self else { return }
                switch ev {
                case .progress(let p):
                    self.progress = p
                case .finished:
                    self.refreshStats()
                case .modelsNeeded:
                    self.showModelsNeeded = true
                    self.refreshStats()
                }
            }
            .store(in: &cancellables)
        refreshStats()
    }

    // MARK: - 库级口径（环心大数字 / 弧 / hint；2026-10-01 口径立法唯一对外百分比）

    var libraryCompletion: LibraryCompletion {
        LibraryCompletion(totalMedia: stats.totalMedia, remainingPass3: stats.remainingPass3)
    }

    // MARK: - 会话控制（v4 bottom_bar 四态状态机入口）

    func startIncremental() { orchestrator.start(mode: .incremental); refreshStats() }
    func startFull() { orchestrator.start(mode: .full); refreshStats() }
    func pause() { orchestrator.pause() }
    func resume() { orchestrator.resume() }
    func cancel() { orchestrator.cancel() }
    func retryFailed() { orchestrator.retryFailed() }

    /// paused 态次钮「重新开始」：cancel → 800ms → 增量重起（yaml bottom_bar.states.paused.secondary）。
    func restart() {
        cancel()
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 800_000_000)
            startIncremental()
        }
    }

    // MARK: - 分阶段独立控制（StageActionSheet intents）

    func runPass2() { orchestrator.runPass2Clustering() }
    func startPass3Incremental() { orchestrator.startPass3(mode: .incremental); refreshStats() }
    func startPass3Full() { orchestrator.startPass3(mode: .full); refreshStats() }

    // MARK: - 数据源

    /// 刷新统计。对齐 Android `TagGenerationControlScreen.refreshStats`（Room 查询走 IO 线程）：
    /// SQLite 统计查询离主线程，避免扫描高峰写库时主线程 `queue.sync` 等待串行队列造成卡顿；
    /// 相等性门控——`@Published` 逐次赋值即触发刷新，1s 轮询场景下值未变不重渲染。
    func refreshStats() {
        Task.detached(priority: .utility) { [weak self] in
            let newStats = TagDatabase.shared.scanStats()
            let unfinished = TagScanOrchestrator.shared.hasUnfinishedSession
            let current = TagScanOrchestrator.shared.currentProgress()
            await MainActor.run {
                guard let self else { return }
                if self.stats != newStats { self.stats = newStats }
                if self.hasUnfinishedSession != unfinished { self.hasUnfinishedSession = unfinished }
                if self.progress == nil, let p = current { self.progress = p }
            }
        }
    }

    /// 恢复上次未完成 session（进程死亡对账 → interrupted_card「从中断处继续」）。
    func resumeUnfinished() {
        orchestrator.resumeUnfinishedSession()
        refreshStats()
    }

    /// 城市分组（成果格城市弹层数据源）。
    /// ⚠️ iOS 无写 media_assets.city 的链路 → 恒空（tag-control.yaml platform_differences.stats_source
    /// 已登记平台缺口：城市回填落地前足迹城市格恒 0，弹层仍可开）。
    func cityGroups() -> [(city: String, count: Int)] {
        var counts: [String: Int] = [:]
        for (_, city) in TagDatabase.shared.cityByLocalIdentifier() {
            counts[city, default: 0] += 1
        }
        let entries = counts.map { (city: $0.key, count: $0.value) }
        return entries.sorted { lhs, rhs in
            if lhs.count != rhs.count { return lhs.count > rhs.count }
            return lhs.city < rhs.city
        }
    }
}
