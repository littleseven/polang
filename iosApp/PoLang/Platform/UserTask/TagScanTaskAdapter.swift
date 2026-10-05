import Combine
import Foundation
import SharedKit

/// TAG 扫描适配器（对齐 Android `TagScanTaskAdapter`，spec §6）：整个扫描会话 = 1 个 UserTask
///（id 固定 `tagscan:main`）。订阅 `TagScanStatusCenter` 多播事件流（不占 orchestrator.onEvent
/// 单闭包）；动词直达 `TagScanOrchestrator`，引擎零侵入。
///
/// 口径立法（2026-10-01，对齐 Android）：`progress` 喂**库级 AI 打标完成率**（LibraryCompletion），
/// 任务级 processed/total 仅以 progressText「128/500」数字中性形态辅助呈现，不渲染为进度占比。
///
/// 与 Android 差异（重启对账降级，台账已登记）：启动不做进程死亡对账——残留活动态行原样呈现，
/// 由下次真实扫描事件覆盖纠正，不置 FAILED(PROCESS_TERMINATED)。降级根因：TagScanStatusCenter
/// 为 PassthroughSubject 无首帧回放（模型下载侧 @Published 有回放，对账已实装，勿混淆）。
final class TagScanTaskAdapter: NSObject, UserTaskAdapter {

    /// 任务 ID（口径对齐 Android TagScanTaskAdapter.TASK_ID）。
    static let taskId = "tagscan:main"

    let kind: UserTaskKind = .tagScan

    private let registry: UserTaskRegistry
    private let syncQueue = UserTaskSyncQueue()
    private var cancellables = Set<AnyCancellable>()

    // 库级完成率缓存与节流（≥1s，对齐 Android TagGenerationService lastLibraryRefreshMs 节流口径）。
    // 仅在 syncQueue 串行 op 内读写，天然无并发。
    private var lastLibraryRefreshAt: Date?
    private var cachedLibraryFraction: Float?

    init(registry: UserTaskRegistry) {
        self.registry = registry
        super.init()
    }

    /// 由注册表 registerAdapter 调一次（非幂等：重复调用起双订阅，唯一调用方保证单次）。
    func start() {
        // TagScanStatusCenter 是 @MainActor 单例；本方法经 ObjC 协议非隔离调用，订阅动作跳主线程。
        Task { @MainActor in
            TagScanStatusCenter.shared.events
                .sink { [weak self] event in self?.handle(event) }
                .store(in: &self.cancellables)
        }
    }

    // MARK: - 事件 → 注册表投影

    private func handle(_ event: ScanEvent) {
        switch event {
        case .progress(let progress):
            // runPass2Clustering 的合成事件（sessionId 固定 pass2-manual，total=0）不映射为
            // 用户任务——Android 手动 Pass2 不写入 sessionProgress 流，本过滤对齐其语义边界。
            guard progress.sessionId != Self.pass2ManualSessionId else { return }
            syncQueue.enqueue { [weak self] in
                await self?.sync(progress)
            }
        case .finished:
            // 终态由紧随其前（cancel）或同帧（complete）的 .progress 快照携带，无需二次处理。
            break
        case .modelsNeeded:
            // 模型未就绪 → 会话未成立（orchestrator 置 idle），无任务状态可投影。
            break
        }
    }

    /// 对齐 Android TagScanTaskAdapter.sync：状态投影 + PARTIAL_FAILURES + 库级进度快照。
    private func sync(_ progress: TagScanSessionProgress) async {
        guard let status = Self.project(progress.state) else {
            // IDLE = 无任务：只清快照、不动状态（无进程死亡对账，台账降级）。
            registry.updateProgress(taskId: Self.taskId, snapshot: nil)
            return
        }
        let partialFailures = status == .completed && progress.failed > 0
        try? await registry.upsertStatus(
            id: Self.taskId,
            kind: .tagScan,
            displayName: nil,
            status: status,
            errorCode: partialFailures ? .partialFailures : nil,
            errorDetail: partialFailures ? "\(progress.failed)" : nil)
        // 终态不写进度快照（upsertStatus 内部已清）：避免 PARTIAL_FAILURES 卡带幽灵 ETA
        guard UserTaskMapping.shared.isActive(status: status) else { return }
        registry.updateProgress(
            taskId: Self.taskId,
            snapshot: TaskProgressSnapshot(
                // 库级口径优先；首帧空窗（统计节流窗口内）回退任务级
                progress: libraryFraction().map { KotlinFloat(float: $0) }
                    ?? (progress.total > 0
                        ? KotlinFloat(float: Float(progress.processed) / Float(progress.total))
                        : nil),
                // total==0 时文案同样置 nil，避免误导性的 "0/0"
                progressText: progress.total > 0 ? "\(progress.processed)/\(progress.total)" : nil,
                // iOS 估计器以 0 表「无剩余/不可估」；0 时不上报 ETA（Android 为 null 语义）
                etaMs: progress.estimatedRemainingMs > 0
                    ? KotlinLong(longLong: Int64(progress.estimatedRemainingMs))
                    : nil))
    }

    // MARK: - 统一动词（失败不乐观更新——状态以体系为准，体系流会纠正注册表）

    /// Kotlin suspend 协议方法的 ObjC completion-handler 实现形态（SKIE 导出 `__perform`，
    /// 对齐 GalleryScriptHandlers `__invoke` 先例）。completionHandler 必须被调用，
    /// 否则 Kotlin 侧协程永久挂起；动词链路不产异常，恒回 nil。
    func __perform(taskId: String, action: UserTaskAction, completionHandler: @escaping ((any Error)?) -> Void) {
        Task { @MainActor in
            let orchestrator = TagScanOrchestrator.shared
            switch action {
            case .pause: orchestrator.pause()
            case .resume: orchestrator.resume()
            case .cancel: orchestrator.cancel()
            // RETRY → start：进程死亡对账（FAILED）唯一出口，「点重试重新开始」链（spec §9-2）
            case .retry: orchestrator.start(mode: .incremental)
            default: break
            }
            completionHandler(nil)
        }
    }

    // MARK: - 投影与库级口径

    /// TAG 扫描会话 7 态 → 协议 6 态（对齐 Android UserTaskMapping.fromTagScanState）；idle = 无任务。
    private static func project(_ state: ScanSessionState) -> UserTaskStatus? {
        switch state {
        case .idle: return nil
        case .running, .pausing, .cancelling: return .running
        case .paused: return .paused
        case .completed: return .completed
        case .cancelled: return .cancelled
        }
    }

    /// 库级 AI 打标完成率（节流 ≥1s；DB 查询离主线程——本函数在 syncQueue 后台 op 内调用）。
    private func libraryFraction() -> Float? {
        let now = Date()
        if let last = lastLibraryRefreshAt, now.timeIntervalSince(last) < 1.0 {
            return cachedLibraryFraction
        }
        lastLibraryRefreshAt = now
        let stats = TagDatabase.shared.scanStats()
        let completion = LibraryCompletion(totalMedia: stats.totalMedia, remainingPass3: stats.remainingPass3)
        cachedLibraryFraction = completion.fraction
        return cachedLibraryFraction
    }

    private static let pass2ManualSessionId = "pass2-manual"
}
