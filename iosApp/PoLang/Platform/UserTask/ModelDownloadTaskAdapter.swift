import Combine
import Foundation
import SharedKit

/// 模型下载适配器（对齐 Android `ModelDownloadTaskAdapter`，spec §6）：每个 modelId = 1 个
/// UserTask（并发多任务，id = `download:<modelId>`）。订阅 `ModelDownloadManager.downloadStates`
/// 投影注册表；动词直达 manager（pause/resume/cancel，RETRY 映射 download 重下）。
///
/// 附带收益（对齐 Android）：状态迁移落注册表，下载体系获得进程重启可见性 + 启动对账
///（`$downloadStates` @Published 首帧回放当前值 = 对账数据源，与 Android reconcile 读取
/// downloadStates.value 等价——对账语义已实装，非降级）。
final class ModelDownloadTaskAdapter: NSObject, UserTaskAdapter {

    /// 任务 ID 方案唯一入口（对齐 Android ModelDownloadTaskAdapter.taskIdFor，禁止各处硬编码前缀）。
    static func taskIdFor(_ modelId: String) -> String { "download:\(modelId)" }
    static func modelId(of taskId: String) -> String {
        taskId.hasPrefix("download:") ? String(taskId.dropFirst("download:".count)) : taskId
    }

    let kind: UserTaskKind = .modelDownload

    private let registry: UserTaskRegistry
    private let syncQueue = UserTaskSyncQueue()
    private var cancellables = Set<AnyCancellable>()

    // 以下状态仅在 syncQueue 串行 op 内读写，天然无并发：

    /// 上一轮 sync 见过的活动任务 id（对齐 Android lastActiveIds）。
    private var lastActiveIds: Set<String> = []
    /// 模型展示名缓存（spec §4 卡片标题用展示名不用机器 ID）；null 结果不缓存——清单可能后至，下次再查。
    private var displayNameCache: [String: String] = [:]

    init(registry: UserTaskRegistry) {
        self.registry = registry
        super.init()
    }

    /// 由注册表 registerAdapter 调一次（非幂等：重复调用起双订阅，唯一调用方保证单次）。
    func start() {
        // ModelDownloadManager 是 @MainActor 单例；本方法经 ObjC 协议非隔离调用，订阅动作跳主线程。
        // @Published 订阅首发当前值：既完成「活体下载补登记」，也触发一次性启动对账（reconcile）。
        Task { @MainActor in
            var didReconcile = false
            ModelDownloadManager.shared.$downloadStates
                .sink { [weak self] states in
                    guard let self else { return }
                    let reconcileOnStart = !didReconcile
                    didReconcile = true
                    self.syncQueue.enqueue { [weak self] in
                        guard let self else { return }
                        if reconcileOnStart {
                            await self.reconcile(states)
                        }
                        await self.sync(states)
                    }
                }
                .store(in: &self.cancellables)
        }
    }

    // MARK: - 启动对账（对齐 Android ModelDownloadTaskAdapter.reconcile）

    /// 首帧一次性对账（spec §5）：注册表活动态行但无活体下载 → FAILED(PROCESS_TERMINATED)
    /// 可 RETRY；误判自愈——紧随其后的 sync 重读活体状态覆盖回正确状态。
    private func reconcile(_ states: [String: DownloadState]) async {
        let liveIds = Set(states.keys.map(Self.taskIdFor))
        guard let activeIds = try? await registry.activeIdsOfKind(kind: .modelDownload) else { return }
        for rowId in activeIds where !liveIds.contains(rowId) {
            try? await registry.upsertStatus(
                id: rowId,
                kind: .modelDownload,
                displayName: displayName(of: Self.modelId(of: rowId)),
                status: .failed,
                errorCode: .processTerminated,
                errorDetail: nil)
            registry.updateProgress(taskId: rowId, snapshot: nil)
        }
    }

    // MARK: - 状态投影（对齐 Android ModelDownloadTaskAdapter.sync）

    private func sync(_ states: [String: DownloadState]) async {
        var currentActiveIds = Set<String>()
        for (id, state) in states {
            let taskId = Self.taskIdFor(id)
            let status = Self.project(state.status)
            try? await registry.upsertStatus(
                id: taskId,
                kind: .modelDownload,
                displayName: displayName(of: id),
                status: status,
                errorCode: nil,
                errorDetail: nil)
            // 终态不写进度快照（upsertStatus 内部已清）：避免幽灵进度
            if UserTaskMapping.shared.isActive(status: status) {
                currentActiveIds.insert(taskId)
                registry.updateProgress(
                    taskId: taskId,
                    snapshot: TaskProgressSnapshot(
                        // coerceIn 兜底对齐 Android：防预存双计致 downloadedBytes > totalBytes
                        progress: state.totalBytes > 0
                            ? KotlinFloat(float: min(max(
                                Float(state.downloadedBytes) / Float(state.totalBytes), 0), 1))
                            : nil,
                        // totalBytes==0 时文案置 nil，避免误导性的 "0 B / 0 B"
                        progressText: state.totalBytes > 0
                            ? "\(Self.formatBytes(state.downloadedBytes)) / \(Self.formatBytes(state.totalBytes))"
                            : nil,
                        etaMs: nil))
            } else {
                registry.updateProgress(taskId: taskId, snapshot: nil)
            }
        }
        // 本轮 map 中消失的活动任务 → CANCELLED 终态（无 errorCode：map 收缩唯一来源是
        // cancel/delete 用户操作，非进程死亡；CANCELLED 即终态不留 RETRY）
        let presentIds = Set(states.keys.map(Self.taskIdFor))
        for missingId in lastActiveIds.subtracting(presentIds) {
            try? await registry.upsertStatus(
                id: missingId,
                kind: .modelDownload,
                displayName: displayName(of: Self.modelId(of: missingId)),
                status: .cancelled,
                errorCode: nil,
                errorDetail: nil)
        }
        lastActiveIds = currentActiveIds
    }

    // MARK: - 统一动词（失败不乐观更新——状态以体系为准，体系流会纠正注册表）

    /// Kotlin suspend 协议方法的 ObjC completion-handler 实现形态（SKIE 导出 `__perform`，
    /// 对齐 GalleryScriptHandlers `__invoke` 先例）。completionHandler 必须被调用，
    /// 否则 Kotlin 侧协程永久挂起；动词链路不产异常，恒回 nil。
    func __perform(taskId: String, action: UserTaskAction, completionHandler: @escaping ((any Error)?) -> Void) {
        let modelId = Self.modelId(of: taskId)
        Task { @MainActor in
            let manager = ModelDownloadManager.shared
            switch action {
            case .pause: manager.pause(modelId)
            case .resume: manager.resume(modelId)
            case .cancel: manager.cancel(modelId)
            // iOS manager 无独立 retry 入口：download 自带「非 downloading 即可重起」语义
            case .retry: manager.download(modelId)
            default: break
            }
            completionHandler(nil)
        }
    }

    // MARK: - 投影与展示名

    /// 下载六态 → 协议六态（对齐 Android UserTaskMapping.fromDownloadStatus）。
    private static func project(_ status: DownloadStatus) -> UserTaskStatus {
        switch status {
        case .pending: return .pending
        case .downloading: return .running
        case .paused: return .paused
        case .completed: return .completed
        case .failed: return .failed
        case .cancelled: return .cancelled
        }
    }

    /// 模型展示名（ModelCatalog.name）；查不到回退 modelId（对齐 Android displayNameOf 语义）。
    private func displayName(of modelId: String) -> String {
        if let cached = displayNameCache[modelId] { return cached }
        if let name = ModelCatalog.shared.model(byId: modelId)?.name {
            displayNameCache[modelId] = name
            return name
        }
        return modelId
    }

    /// 字节格式化（对齐 Android ModelDownloadTaskAdapter.formatBytes：%.1f 一级单位，Float 除法口径；
    /// locale 固定 en_US_POSIX 对齐 Android Locale.US——fr/es 设备十进制逗号不渗入进度文案）。
    private static let byteFormatLocale = Locale(identifier: "en_US_POSIX")

    private static func formatBytes(_ bytes: Int64) -> String {
        let kb: Int64 = 1 << 10
        let mb: Int64 = 1 << 20
        let gb: Int64 = 1 << 30
        if bytes >= gb { return String(format: "%.1f GB", locale: byteFormatLocale, Float(bytes) / Float(gb)) }
        if bytes >= mb { return String(format: "%.1f MB", locale: byteFormatLocale, Float(bytes) / Float(mb)) }
        if bytes >= kb { return String(format: "%.1f KB", locale: byteFormatLocale, Float(bytes) / Float(kb)) }
        return "\(bytes) B"
    }
}
