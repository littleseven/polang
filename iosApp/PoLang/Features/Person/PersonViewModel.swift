import Foundation
import SharedKit

// MARK: - 关系选项桥接（:shared `PersonRelationSupport` → Swift 友好结构）

/// 当前 App 语言代码（与 LanguageManager 对齐："跟随系统"时取系统首选）。
/// 谓词标签仅有 zh/en/ja 三套，西语/法语回退 en。
enum PersonLanguage {
    static func code() -> String {
        switch LanguageManager.shared.currentLanguage {
        case "english", "spanish", "french": return "en"
        case "chinese_simplified", "chinese_traditional": return "zh"
        default:
            let pref = Locale.preferredLanguages.first ?? "en"
            if pref.hasPrefix("zh") { return "zh" }
            if pref.hasPrefix("ja") { return "ja" }
            return "en"
        }
    }
}

/// 关系谓词选项（Swift 端纯值类型，避免 ForEach 直接持有 K/N 对象）。
/// 谓词词汇的单一事实来源 = :shared `RelationPredicate`；本结构仅做渲染桥接。
struct RelationOptionItem: Identifiable, Hashable {
    let id: String
    let labelZh: String
    let labelEn: String
    let labelJa: String
    let isFamily: Bool

    var label: String {
        switch PersonLanguage.code() {
        case "zh": return labelZh
        case "ja": return labelJa
        default: return labelEn
        }
    }
}

enum RelationOptions {
    /// 全部关系选项（顺序对齐 :shared 枚举顺序）。
    static func all() -> [RelationOptionItem] {
        PersonRelationSupport.shared.allOptions().map { opt in
            RelationOptionItem(
                id: opt.id,
                labelZh: opt.labelZh,
                labelEn: opt.labelEn,
                labelJa: opt.labelJa,
                isFamily: opt.isFamily)
        }
    }

    /// 谓词 id → 当前语言标签（关系 chip 兜底展示）。
    static func label(predicateId: String) -> String {
        guard let opt = PersonRelationSupport.shared.allOptions().first(where: { $0.id == predicateId }) else {
            return predicateId
        }
        switch PersonLanguage.code() {
        case "zh": return opt.labelZh
        case "ja": return opt.labelJa
        default: return opt.labelEn
        }
    }
}

// MARK: - 详情保存被拒类型（对标 Android PersonSaveError）

/// 人物编辑保存被拒的类型化原因（UI 映射为本地化引导文案，不透传裸异常）。
enum PersonSaveError {
    /// 未标记「这是我」本人就声明关系——声明被拒，需引导先打开「这是我」。
    case selfNotDeclared
    /// 目标人物已不存在（如重聚类后被合并）。
    case subjectNotFound
}

// MARK: - 人物列表 ViewModel

/// 人物页（列表）状态。对标 Android `PersonViewModel`：
/// reconcileAndLoad / 行内改名 / 筛选 / 重聚类。读聚类数据（`PersonRepository`）。
@MainActor
final class PersonViewModel: ObservableObject {

    @Published private(set) var items: [PersonDisplayItem] = []
    @Published private(set) var totalCount: Int = 0
    @Published private(set) var showAll: Bool = false
    @Published private(set) var isLoading = false
    @Published private(set) var editingPersonId: Int64?
    /// 瞬时提示（重聚类已启动 / 已隐藏 N 个单人组 / 保存被拒引导）。view 侧消费后置 nil。
    @Published var toast: String?

    private let repo: PersonRepository
    /// reload 代际（见 reload 内守卫注释）。
    private var reloadGeneration = 0

    init(repo: PersonRepository = .shared) {
        self.repo = repo
    }

    /// 进入页：reconcile → load（幂等）。DB 工作后台执行，不阻塞主线程。
    func onAppear() {
        reload(reconcile: true)
    }

    private func reload(reconcile: Bool) {
        isLoading = true
        // 代际守卫：详情保存回联会连发两次 refresh（onBack 立即 + 写入完成后），
        // DB 队列入队顺序不保证——旧快照后发时丢弃，防「改名被回退」假象（🟡 review 修复）
        reloadGeneration += 1
        let generation = reloadGeneration
        let repo = self.repo
        let showAll = self.showAll
        Task.detached(priority: .userInitiated) { [weak self] in
            let snapshot = reconcile
                ? repo.reconcileAndLoad(showAll: showAll)
                : repo.load(showAll: showAll)
            await MainActor.run {
                guard let self, generation == self.reloadGeneration else { return }
                self.items = snapshot.items
                self.totalCount = snapshot.totalCount
                self.isLoading = false
                self.emitHiddenHintIfNeeded()
            }
        }
    }

    func toggleShowAll() {
        showAll.toggle()
        toast = nil
        reload(reconcile: false)
    }

    /// 详情页返回后刷新列表（不做 reconcile）。
    func refresh() { reload(reconcile: false) }

    // MARK: 行内改名

    func startEditing(personId: Int64) { editingPersonId = personId }
    func stopEditing() { editingPersonId = nil }

    /// 行内改名保存：trim 后空名 = 取消命名（存 nil）。保存后停止编辑 + 刷新。
    func updateName(personId: Int64, name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let effective = trimmed.isEmpty ? nil : trimmed
        let repo = self.repo
        let showAll = self.showAll
        stopEditing()
        Task.detached(priority: .userInitiated) { [weak self] in
            repo.rename(personId: personId, name: effective)
            let snapshot = repo.load(showAll: showAll)
            await MainActor.run {
                guard let self else { return }
                self.items = snapshot.items
                self.totalCount = snapshot.totalCount
            }
        }
    }

    // MARK: 重聚类

    /// 触发 Pass2 全量重聚类（fire-and-forget），提示用户完成后返回本页刷新。
    /// ⚠️ iOS Pass2 为全量重扫，会清空 persons（含名字/关系）；与 Android 增量 split/merge 不同。
    func recluster() {
        TagScanOrchestrator.shared.runPass2Clustering()
        toast = L("Re-clustering started (faces only, may take a while). Re-enter this page to refresh when done.")
    }

    /// 默认筛选下隐藏了单人组时，提示用户。
    /// 已有 toast 在展示（如保存被拒引导）时不覆盖——引导被 hidden-hint 顶掉
    /// 等于「被拒无反馈」复活（🔴 review 修复；Android snackbar 为队列，iOS toast 单槽）。
    private func emitHiddenHintIfNeeded() {
        guard toast == nil else { return }
        guard !showAll else { return }
        let hidden = totalCount - items.count
        guard hidden > 0 else { return }
        toast = String(format: L("Hidden %1$d unnamed single-face groups; tap filter to show all"), hidden)
    }

    // MARK: 详情保存引导（对标 Android snackbar 引导；spec §7 error_guidance）

    /// 详情保存被拒 → 本地化引导 toast（列表页胶囊展示）。
    func showSaveGuidance(_ error: PersonSaveError) {
        switch error {
        case .selfNotDeclared:
            toast = L("\"This is me\" is not marked yet: turn on \"This is me\" on your own group first, then set relationships")
        case .subjectNotFound:
            toast = L("This person no longer exists (may have been merged by re-clustering)")
        }
    }
}

// MARK: - 人物详情 ViewModel

/// 人物详情状态：本体 + 对己关系 + 封面候选。编辑由 view 持本地态，保存回调本 VM 持久化 + 刷新。
@MainActor
final class PersonDetailViewModel: ObservableObject {

    @Published private(set) var person: PersonDbRow?
    @Published private(set) var relation: PersonRelationDb?
    @Published private(set) var coverCandidates: [MediaCoverInfo] = []
    @Published private(set) var isLoading = false

    private let repo: PersonRepository
    let personId: Int64

    init(personId: Int64, repo: PersonRepository = .shared) {
        self.personId = personId
        self.repo = repo
    }

    func load() {
        isLoading = true
        let repo = self.repo
        let pid = personId
        Task.detached(priority: .userInitiated) { [weak self] in
            let person = repo.person(pid)
            let relation = repo.relation(personId: pid)
            let covers = repo.coverCandidates(personId: pid)
            await MainActor.run {
                guard let self else { return }
                self.person = person
                self.relation = relation
                self.coverCandidates = covers
                self.isLoading = false
            }
        }
    }

    // MARK: 编辑（详情保存走 applyEdit 原子收口；封面独立保存）

    /// 详情页保存：姓名/关系/自定义称呼/「这是我」**随保存单次提交**（spec §7）。
    ///
    /// name 由编辑页一并提交（不再从快照取旧名——那是「改名被回退」竞态的来源）；
    /// 声明被拒时经 `onComplete` 透传 `PersonSaveError` 由列表页引导用户。
    /// `onComplete` 于写入完成后主线程回调（详情页可能已 dismiss，闭包不持 self 生命周期）。
    func applyEdit(
        name: String?,
        relation: String?,
        customLabel: String?,
        isSelf: Bool,
        onComplete: @escaping @MainActor (PersonSaveError?) -> Void
    ) {
        let repo = self.repo
        let pid = personId
        Task.detached(priority: .userInitiated) { [weak self] in
            let result = repo.applyPersonEdit(
                personId: pid, name: name, relation: relation,
                customLabel: customLabel, isSelf: isSelf)
            let error: PersonSaveError? = {
                switch result {
                case .selfNotDeclared: return .selfNotDeclared
                case .subjectNotFound: return .subjectNotFound
                case .declared, .none: return nil
                }
            }()
            await MainActor.run {
                onComplete(error)
                self?.load()
            }
        }
    }

    func saveCover(_ mediaId: Int64?) {
        let repo = self.repo
        let pid = personId
        Task.detached(priority: .userInitiated) { [weak self] in
            repo.updateCover(personId: pid, mediaId: mediaId)
            await MainActor.run { self?.load() }
        }
    }
}
