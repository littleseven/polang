import Foundation

/// 整理中心 hub ViewModel（spec organize.yaml §2 board_contract）。
/// board == nil = stateIn 初值未加载 → Hero 与类目卡均不渲染（防 0B 假数据）。
@MainActor
final class OrganizeHubViewModel: ObservableObject {
    @Published private(set) var board: OrganizeBoard?
    @Published private(set) var isLoading = false

    private let repository: OrganizeRepository
    /// onAppear 驱动的首载任务（board → 回填 → board 刷新），防重复触发。
    private var startTask: Task<Void, Never>?

    init(repository: OrganizeRepository = .shared) {
        self.repository = repository
    }

    /// hub 首次出现：构建看板 → 空闲时回填质量信号 → 回填后重建看板
    /// （blur/exposure 覆盖度可能翻牌 NEEDS_SCAN→READY，LOW_QUALITY_PHOTOS 卡随之显形）。
    /// 错峰由 repository 内建（活跃 tag 扫描会话跳过/让路），此处直调。
    /// VM 由 OrganizeHomeView 容器级持有：回填循环全局只跑一条（防 hub 视图重建并发双循环）。
    func start() {
        guard startTask == nil else { return }
        startTask = Task { [weak self] in
            guard let self else { return }
            await self.reload()
            await self.repository.runQualitySignalBackfill()
            await self.reload()
        }
    }

    /// hub 视图每次出现（.task）：首载走 start() 全链；重入只重建看板
    /// （切扫描 Tab 回来 / 二级页删除返回后，信号/数据可能已更新）。
    func appear() async {
        if startTask == nil {
            start()
        } else {
            await reload()
        }
    }

    /// 重建看板（全量管线在 repository 侧 Task.detached(.utility) 离主线程跑）。
    func reload() async {
        isLoading = true
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        board = await repository.board(now: now)
        isLoading = false
    }
}
