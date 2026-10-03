import Foundation
import SharedKit

// MARK: - 任务中心数据层（UI 侧聚合，task-center.yaml §data_contract）

/// 任务中心双 Tab 数据源（@MainActor ObservableObject，swiftui-expert 单一状态源）：
/// - 工程师 Tab：ChatHistoryStore 全部会话的 task_card 消息（engineerTask 非空者）经
///   shared `TaskCenterPartition.partition` 分区——iOS 无 claude-tunnel SSE 数据链，
///   仅持久化历史（当前必空）+ 空态（task-center.yaml §范围裁定 / §7 台账）；
/// - 后台 Tab：`UserTaskRegistry.tasks` 合并流（Room 元数据 + 内存进度）；
///   活跃计数与 Chat 顶栏角标同口径（registry.activeCount）。
///
/// `engineerList == nil` = 首查未回（spec empty_states.engineer_first_query_pending：
/// 渲染全空白，防空态文案闪现一帧）。
@MainActor
final class TaskCenterStore: ObservableObject {

    /// nil = 首查未回；active/history 已按 spec 排序（审批置顶/倒序/封顶 50）。
    @Published private(set) var engineerList: TaskCenterList? = nil
    @Published private(set) var userTasks: [UserTask] = []
    @Published private(set) var activeUserCount = 0

    private let registry: UserTaskRegistry
    private var watchTasks: Task<Void, Never>?
    private var watchActiveCount: Task<Void, Never>?

    init(registry: UserTaskRegistry = AppContainer.shared.userTaskRegistry) {
        self.registry = registry
    }

    func start() {
        // cover 每次打开重查（工程师历史可能被 chat 侧写盘变更）
        reloadEngineerTasks()
        watchTasks?.cancel()
        watchActiveCount?.cancel()
        // SKIE 直消费（shared/AGENTS §1：新链路一律 SKIE 形态）：StateFlow → AsyncSequence，
        // Task 取消经 SKIE 双向传播回 Kotlin 协程
        watchTasks = Task { @MainActor [weak self, registry] in
            for await tasks in registry.tasks {
                self?.userTasks = tasks
            }
        }
        watchActiveCount = Task { @MainActor [weak self, registry] in
            for await count in registry.activeCount {
                self?.activeUserCount = Int(count.int32Value)
            }
        }
    }

    func stop() {
        watchTasks?.cancel()
        watchTasks = nil
        watchActiveCount?.cancel()
        watchActiveCount = nil
    }

    /// 工程师任务分区重查（ChatHistoryStore 全会话扫描；持久层 JSON 解析是平台边界）。
    /// 扫描+解析下沉后台线程（全会话 JSON 量级未知，且 legacy 兜底有迁移写盘副作用），
    /// 结果回主线程赋值——engineerList nil 首查空白防护正好覆盖异步等待窗口。
    func reloadEngineerTasks() {
        // cover 每次打开重查（工程师历史可能被 chat 侧写盘变更）
        Task.detached { [weak self] in
            let history = await ChatHistoryStore.shared
            var items: [TaskCenterItem] = []
            for thread in history.loadThreads() {
                for message in history.loadMessages(sessionId: thread.sessionId) {
                    guard let task = message.engineerTask else { continue }
                    // 标题口径：消息 content（源指令截断）缺省回退 task.sourceText
                    let title = message.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        ? task.sourceText
                        : message.content
                    items.append(TaskCenterItem(
                        sessionId: thread.sessionId,
                        title: title,
                        sessionTitle: thread.title,
                        task: task))
                }
            }
            // Kotlin object 经 K/N 导出为 .shared 单例（类成员为实例方法）
            let list = TaskCenterPartition.shared.partition(items: items)
            await MainActor.run { self?.engineerList = list }
        }
    }

    /// 声明式动词分发（spec user_task_card.actions_row：失败静默——registry.perform
    /// 全链路兜底不穿透，状态以体系流为准）。
    func perform(taskId: String, action: UserTaskAction) {
        Task { @MainActor in
            try? await registry.perform(taskId: taskId, action: action)
        }
    }

    // MARK: - 后台任务分区（两 Tab 同构：active / history，sections 节）

    /// 活跃判据复用 shared SSOT `UserTaskMapping.isActive`（PENDING/RUNNING/PAUSED，spec data_contract）。
    static func isActive(_ task: UserTask) -> Bool {
        UserTaskMapping.shared.isActive(status: task.status)
    }

    /// 进行中 / 历史双分区（历史按 updatedAt 倒序封顶 50 = UserTaskRegistry.HISTORY_KEEP）。
    var userTaskSections: (active: [UserTask], history: [UserTask]) {
        let active = userTasks.filter { Self.isActive($0) }
        let history = Array(
            userTasks.filter { !Self.isActive($0) }
                .sorted { $0.updatedAt > $1.updatedAt }
                .prefix(Int(UserTaskRegistry.companion.HISTORY_KEEP)))
        return (active, history)
    }
}

// MARK: - Preview 样本（swiftui-expert：空 / 各态覆盖）

#if DEBUG
extension TaskCenterStore {

    /// 预览注入（绕过持久层/注册表直灌 @Published）。
    func loadPreview(engineer: TaskCenterList?, userTasks: [UserTask], activeCount: Int) {
        self.engineerList = engineer
        self.userTasks = userTasks
        self.activeUserCount = activeCount
    }

    /// 样本工厂（nonisolated：纯构造，供 Preview 非隔离上下文调用）。
    nonisolated static func previewEngineerTask(
        id: String,
        status: EngineerTaskStatus,
        resolution: EngineerTaskResolution? = nil
    ) -> EngineerTaskState {
        EngineerTaskState(
            taskId: id,
            sourceText: "Fix login crash on cold start",
            sid: nil,
            status: status,
            stage: status == .running ? "Reproducing in simulator" : nil,
            recentStages: ["Read project structure", "Located LoginActivity"],
            turns: 3,
            costCents: status == .completed ? KotlinInt(int: 42) : nil,
            startedAtMs: 1_760_000_000_000,
            updatedAtMs: 1_760_000_186_000,
            fileChangeCount: status == .awaitingDeliver ? 4 : 0,
            truncatedReason: status == .awaitingContinue ? "max turns reached" : nil,
            errorSummary: status == .failed ? "Build failed: unresolved reference" : nil,
            resultSummary: status == .completed ? "Patched null check and added test" : nil,
            resolution: resolution,
            deliverBranch: status == .awaitingDeliver ? "fix/login-crash" : nil)
    }
}
#endif
