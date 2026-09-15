import SwiftUI

// MARK: - 手势快速整理屏（F2，spec docs/08-UI-SPECS/screens/organize.yaml §5 swipe_review）
//
// 契约 SSOT：organize.yaml §5 + §1 领域口径（不读 Android 源码翻译 UI）。
// 队列 = SwipeQueueBuilder（同一 OrganizeCategorizer 管线，口径同源）；
// KEEP 决策即时写 SwipeKeepHistoryStore（30 天抑制，restart 建队过滤活跃条目）；
// DELETE 标记不即时删，累计 20 张（batchSize）自动提交一批 IosTrashService.moveToTrash；
// 授权被拒批次整批回滚可重试，末张决策后 Done 态出口面板「待提交 N + 重试/放弃」。
// iOS 平台差异（organize.yaml §8 台账）：系统确认框无静默通路；无「最近删除」程序化
// 恢复 → 已提交批次不可 undo（尾条已提交时顶栏 undo 禁用）、Done 卡「整批恢复」裁剪。

// MARK: - 决策模型

/// 卡片决策（spec §5 gestures：右滑 keep / 左滑+点按 skip / 上滑 delete）。
enum SwipeDecision {
    case keep
    case skip
    case delete
}

/// DELETE 决策的提交归宿（keep/skip 恒 nil）。
enum SwipeDeleteResolution {
    case pending    // 已标记未提交
    case committed  // 已随成功批次进系统回收站（不可 undo）
    case discarded  // 出口面板「放弃」：未提交，Done 统计按 skipped 口径
}

/// 决策记录（undo 栈条目，decisions 尾条 = 最近决策）。
struct SwipeDecisionRecord: Identifiable {
    let id: UUID
    let candidate: SwipeCandidate
    let decision: SwipeDecision
    /// KEEP 决策写入 SwipeKeepHistoryStore 的编码条目（undo 只回滚本会话新增的精确串）。
    let keepHistoryEntry: String?
    var deleteResolution: SwipeDeleteResolution?

    init(id: UUID = UUID(),
         candidate: SwipeCandidate,
         decision: SwipeDecision,
         keepHistoryEntry: String? = nil,
         deleteResolution: SwipeDeleteResolution? = nil) {
        self.id = id
        self.candidate = candidate
        self.decision = decision
        self.keepHistoryEntry = keepHistoryEntry
        self.deleteResolution = deleteResolution
    }
}

/// 屏幕状态机（spec §5 states: loading / reviewing / done；empty = 建队后无卡片）。
enum SwipeReviewPhase {
    case loading
    case reviewing
    case empty
    case done
}

// MARK: - ViewModel

@MainActor
final class SwipeReviewViewModel: ObservableObject {

    /// 单批提交张数（spec §5 up: 「累计 20 张自动提交一批系统回收站授权」）。
    static let batchSize = 20
    /// 飞出阈值 = 卡片宽 25%（spec §5 SWIPE_THRESHOLD_FRACTION=0.25）。
    static let swipeThresholdFraction: CGFloat = 0.25
    /// 预载后续张数（spec §5 preload: 3）。
    static let preloadCount = 3

    @Published private(set) var phase: SwipeReviewPhase = .loading
    @Published private(set) var queue: [SwipeCandidate] = []
    /// 下一张待决策卡下标（= 已决策数 = 顶栏进度 N）。
    @Published private(set) var index = 0
    /// 决策栈（undo 数据源；decisions.last.isCommittedDelete → undo 禁用）。
    @Published private(set) var decisions: [SwipeDecisionRecord] = []
    /// 批次提交中（系统确认框等待）。
    @Published private(set) var isSubmitting = false

    /// 授权被拒后暂停会话内自动提交（防确认框连环弹）；重试出口仅剩 Done 出口面板。
    private var autoSubmitSuspended = false

    private let repository: OrganizeRepository
    private let trash: IosTrashService
    private let keepStore: SwipeKeepHistoryStore

    init(repository: OrganizeRepository = .shared,
         trash: IosTrashService = IosTrashService(),
         keepStore: SwipeKeepHistoryStore = SwipeKeepHistoryStore()) {
        self.repository = repository
        self.trash = trash
        self.keepStore = keepStore
    }

    // MARK: - 统计（Done 卡 / 顶栏副行）

    var keptCount: Int { decisions.filter { $0.decision == .keep }.count }
    /// 已提交进回收站的 DELETE 数（真实删除）。
    var committedDeleteCount: Int {
        decisions.filter { $0.deleteResolution == .committed }.count
    }
    /// 未提交 DELETE 以 skipped 口径进 Done（spec §5 trash_flow）。
    var skippedCount: Int {
        decisions.filter { $0.decision == .skip || $0.deleteResolution == .discarded }.count
    }
    var pendingDeleteCount: Int {
        decisions.filter { $0.deleteResolution == .pending }.count
    }
    /// 本轮真实释放字节（仅成功提交批次；标记未提交不计）。
    var freedBytes: Int64 {
        decisions.filter { $0.deleteResolution == .committed }
            .reduce(0) { $0 + $1.candidate.sizeBytes }
    }

    /// 顶栏 undo 可用性：提交中、无决策或尾条已提交（committed/discarded 均 resolved）时禁用
    /// （spec §5 top_bar「尾条已提交时禁用」；iOS 已删除项无程序化恢复通路）。
    var canUndo: Bool {
        guard !isSubmitting else { return false }
        guard let last = decisions.last else { return false }
        if last.decision == .delete { return last.deleteResolution == .pending }
        return true
    }

    // MARK: - 建队 / 回合

    /// 首次进入加载（.task 触发；重复 appear 不重建，保留进行中状态）。
    func loadIfNeeded() async {
        guard phase == .loading else { return }
        await startNewRound()
    }

    /// 建新回合（Done「再来一轮」同口）：重置决策栈与统计，
    /// KEEP 30 天抑制过滤（spec §5 keep_history「restart 建队过滤活跃条目」）。
    func startNewRound() async {
        guard !isSubmitting else { return }
        phase = .loading
        let items = await repository.loadItems()
        let now = Self.nowMs()
        let suppressed = SwipeKeepHistory.activeUris(
            SwipeKeepHistory.activeEntries(keepStore.load(), nowMs: now))
        let next = await Task.detached(priority: .utility) {
            SwipeQueueBuilder.build(items, now: now).filter { !suppressed.contains($0.uri) }
        }.value
        queue = next
        index = 0
        decisions = []
        autoSubmitSuspended = false
        phase = next.isEmpty ? .empty : .reviewing
    }

    // MARK: - 决策 / undo

    /// 落一条决策（卡片飞出动画完成后由视图调用）。
    func decide(_ decision: SwipeDecision) {
        guard phase == .reviewing, index < queue.count else { return }
        let candidate = queue[index]
        var keepEntry: String?
        if decision == .keep {
            let now = Self.nowMs()
            keepEntry = SwipeKeepHistory.encode(uri: candidate.uri, keptAtMs: now)
            // 即时写历史（spec §5 keep_history「decide(KEEP) 即时写」）
            keepStore.recordKeep(uri: candidate.uri, keptAtMs: now)
        }
        decisions.append(SwipeDecisionRecord(
            candidate: candidate,
            decision: decision,
            keepHistoryEntry: keepEntry,
            deleteResolution: decision == .delete ? .pending : nil))
        index += 1
        if index >= queue.count { phase = .done }
        if decision == .delete { maybeAutoSubmitBatch() }
    }

    /// 回滚最近一条决策：KEEP 撤销本会话写入的历史条目；未提交 DELETE 出 pending；
    /// Done 态撤销则回到 reviewing 重决策末卡。
    func undoLastDecision() {
        guard canUndo, let record = decisions.popLast() else { return }
        if record.decision == .keep, let entry = record.keepHistoryEntry {
            var entries = SwipeKeepHistory.activeEntries(keepStore.load(), nowMs: Self.nowMs())
            entries.remove(entry)
            keepStore.save(entries)
        }
        index -= 1
        if phase == .done { phase = .reviewing }
    }

    // MARK: - 批提交 / 回滚

    /// 20 张边界自动提交：pending 数达 batchSize 整数倍且未被拒绝暂停时触发；
    /// 被拒回滚后不立刻重弹（下次整数倍边界再试），防确认框连环弹。
    private func maybeAutoSubmitBatch() {
        guard !autoSubmitSuspended, !isSubmitting, pendingDeleteCount > 0,
              pendingDeleteCount % Self.batchSize == 0 else { return }
        submitPendingDeletes()
    }

    /// 提交当前全部 pending DELETE（出口面板「重试」同口，一次性整批）。
    /// 失败（用户在系统确认框取消/系统错误）→ 整批回滚进 pending（可重试）。
    func submitPendingDeletes() {
        let pendingIds = Set(decisions
            .filter { $0.deleteResolution == .pending }
            .map(\.id))
        let uris = decisions
            .filter { pendingIds.contains($0.id) }
            .map { $0.candidate.uri }
        guard !uris.isEmpty, !isSubmitting else { return }
        isSubmitting = true
        trash.moveToTrash(uris) { [weak self] result in
            Task { @MainActor in
                self?.handleBatchResult(result, recordIds: pendingIds)
            }
        }
    }

    private func handleBatchResult(_ result: TrashBatchResult, recordIds: Set<UUID>) {
        isSubmitting = false
        guard result.isFullyDeleted else {
            // 授权被拒批次回滚可重试（spec §5 trash_flow）：resolution 保持 .pending
            autoSubmitSuspended = true
            return
        }
        for i in decisions.indices where recordIds.contains(decisions[i].id) {
            decisions[i].deleteResolution = .committed
        }
        // 成功后链式补交（提交飞行期间累计出的整批）
        if phase == .reviewing { maybeAutoSubmitBatch() }
    }

    /// 出口面板「放弃」：pending DELETE 全部落 discarded（Done 按 skipped 口径）。
    func discardPendingDeletes() {
        guard !isSubmitting else { return }
        for i in decisions.indices where decisions[i].deleteResolution == .pending {
            decisions[i].deleteResolution = .discarded
        }
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

// MARK: - 屏幕（对外契约：T4 引用 SwipeReviewScreen()，自包含自建 ViewModel）

struct SwipeReviewScreen: View {
    @StateObject private var vm = SwipeReviewViewModel()
    @Environment(\.colorScheme) private var cs
    @Environment(\.dismiss) private var dismiss

    /// 由父视图注入的返回回调（NavigationStack push 场景）；缺省走 dismiss。
    var onBack: (() -> Void)? = nil

    var body: some View {
        let s = appScheme(cs)
        VStack(spacing: 0) {
            topBar
            switch vm.phase {
            case .loading:
                loadingView
            case .reviewing:
                reviewingContent
            case .empty:
                emptyView
            case .done:
                doneView
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(s.background.ignoresSafeArea())
        .task { await vm.loadIfNeeded() }
    }

    private func handleBack() {
        if let onBack { onBack() } else { dismiss() }
    }

    // MARK: 顶栏：返回 + 居中进度「N / M」+ 副行 freed + 右 undo（尾条已提交时禁用）

    private var topBar: some View {
        let s = appScheme(cs)
        return ZStack {
            HStack(spacing: 0) {
                AppTopBarAction(systemName: "mat_o_arrow_back",
                                accessibilityID: "swipe_back") { handleBack() }
                Spacer(minLength: 0)
                AppTopBarAction(systemName: "mat_o_undo",
                                accessibilityID: "swipe_undo",
                                isEnabled: vm.canUndo) { vm.undoLastDecision() }
                .accessibilityLabel(Text(String(localized: "swipe_undo_cd")))
            }
            .padding(.leading, 4)
            .padding(.trailing, 4)
            VStack(spacing: 0) {
                if vm.phase == .reviewing {
                    Text(String(format: String(localized: "swipe_progress"),
                                Int32(vm.index), Int32(vm.queue.count)))
                        .font(.system(size: TopBarTokens.titleFontSize,
                                      weight: TopBarTokens.titleFontWeight))
                        .foregroundStyle(s.onSurface)
                    if vm.freedBytes > 0 {
                        Text(String(format: String(localized: "swipe_freed"),
                                    SwipeByteFormatter.format(vm.freedBytes)))
                            .font(AppTypography.bodySmall.font)
                            .foregroundStyle(s.onSurfaceVariant)
                    }
                } else {
                    Text(String(localized: "swipe_title"))
                        .font(.system(size: TopBarTokens.titleFontSize,
                                      weight: TopBarTokens.titleFontWeight))
                        .foregroundStyle(s.onSurface)
                }
            }
        }
        .frame(height: vm.phase == .reviewing && vm.freedBytes > 0 ? 58 : TopBarTokens.height)
    }

    // MARK: loading / empty

    private var loadingView: some View {
        ProgressView()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var emptyView: some View {
        let s = appScheme(cs)
        return VStack(spacing: Spacing.md) {
            MatIcon(name: "mat_check_circle", size: IconSize.xl * 2)
                .foregroundStyle(s.onSurfaceVariant)
            Text(String(localized: "swipe_empty"))
                .font(AppTypography.bodyLarge.font)
                .foregroundStyle(s.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
        .padding(Spacing.xl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: reviewing：卡片堆 + 手势提示行

    private var reviewingContent: some View {
        let s = appScheme(cs)
        return VStack(spacing: 0) {
            GeometryReader { geo in
                // 卡片 3:4，横向留 Spacing.lg 边距，纵向同边距内取最小可行尺寸
                let cardW = min(geo.size.width - Spacing.lg * 2,
                                (geo.size.height - Spacing.lg * 2) * 0.75)
                let cardH = cardW / 0.75
                SwipeCardDeck(vm: vm, cardWidth: cardW, cardHeight: cardH)
                    .frame(width: geo.size.width, height: geo.size.height)
            }
            .frame(maxHeight: .infinity)
            hintRow
        }
    }

    private var hintRow: some View {
        let s = appScheme(cs)
        return HStack {
            Text(String(localized: "swipe_hint_skip"))
                .accessibilityIdentifier("swipe_hint_skip")
            Spacer(minLength: 0)
            Text(String(localized: "swipe_hint_delete"))
                .accessibilityIdentifier("swipe_hint_delete")
            Spacer(minLength: 0)
            Text(String(localized: "swipe_hint_keep"))
                .accessibilityIdentifier("swipe_hint_keep")
        }
        .font(AppTypography.bodySmall.font)
        .foregroundStyle(s.onSurfaceVariant)
        .padding(.horizontal, Spacing.xl)
        .padding(.bottom, Spacing.lg)
    }

    // MARK: done：统计卡 + 待提交出口面板 + 再来一轮 / 返回

    private var doneView: some View {
        let s = appScheme(cs)
        return ScrollView {
            VStack(spacing: Spacing.lg) {
                MatIcon(name: "mat_check_circle", size: 64)
                    .foregroundStyle(StatusColor.success)
                Text(String(localized: "swipe_done_title"))
                    .font(AppTypography.headlineSmall.font.weight(AppTypography.WeightOverride.bold))
                    .foregroundStyle(s.onSurface)
                Text(String(localized: "swipe_done_subtitle"))
                    .font(AppTypography.bodyMedium.font)
                    .foregroundStyle(s.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                statRow
                Text(String(format: String(localized: "swipe_done_freed"),
                            SwipeByteFormatter.format(vm.freedBytes)))
                    .font(AppTypography.titleMedium.font.weight(AppTypography.WeightOverride.semibold))
                    .foregroundStyle(tagControlBrandGradient)
                if vm.pendingDeleteCount > 0 { pendingCommitPanel }
                if vm.isSubmitting { ProgressView().padding(Spacing.xs) }
                doneActions
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.top, Spacing.xl)
            .padding(.bottom, Spacing.xl)
        }
    }

    private var statRow: some View {
        let s = appScheme(cs)
        return HStack(spacing: 0) {
            statColumn(count: vm.keptCount, labelKey: "swipe_done_kept",
                       id: "swipe_stat_kept")
            statColumn(count: vm.committedDeleteCount, labelKey: "swipe_done_deleted",
                       id: "swipe_stat_deleted")
            statColumn(count: vm.skippedCount, labelKey: "swipe_done_skipped",
                       id: "swipe_stat_skipped")
        }
        .padding(.vertical, Spacing.md)
        .background(s.surfaceContainer)
        .clipShape(AppShapes.lg)
    }

    private func statColumn(count: Int, labelKey: String, id: String) -> some View {
        let s = appScheme(cs)
        return VStack(spacing: Spacing.xs) {
            Text("\(count)")
                .font(AppTypography.titleLarge.font.weight(AppTypography.WeightOverride.bold))
                .foregroundStyle(s.onSurface)
            Text(NSLocalizedString(labelKey, comment: ""))
                .font(AppTypography.bodySmall.font)
                .foregroundStyle(s.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier(id)
    }

    /// 出口面板：末张决策后仍有未提交 DELETE → 「待提交 N 张 + 重试/放弃」（spec §5 trash_flow）。
    private var pendingCommitPanel: some View {
        let s = appScheme(cs)
        return VStack(spacing: Spacing.md) {
            Text(String(format: String(localized: "swipe_pending_commit"),
                        Int32(vm.pendingDeleteCount)))
                .font(AppTypography.bodyMedium.font)
                .foregroundStyle(s.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: Spacing.md) {
                Button {
                    vm.submitPendingDeletes()
                } label: {
                    Text(String(localized: "swipe_retry"))
                        .font(AppTypography.labelLarge.font)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 40)
                        .background(tagControlBrandGradient)
                        .clipShape(Capsule())
                }
                .disabled(vm.isSubmitting)
                .accessibilityIdentifier("swipe_pending_retry")
                Button {
                    vm.discardPendingDeletes()
                } label: {
                    Text(String(localized: "swipe_discard"))
                        .font(AppTypography.labelLarge.font)
                        .foregroundColor(s.onSurfaceVariant)
                        .frame(maxWidth: .infinity)
                        .frame(height: 40)
                        .background(s.surfaceContainerHigh)
                        .clipShape(Capsule())
                }
                .disabled(vm.isSubmitting)
                .accessibilityIdentifier("swipe_pending_discard")
            }
        }
        .padding(Spacing.lg)
        .background(s.surfaceContainer)
        .clipShape(AppShapes.lg)
    }

    @ViewBuilder private var doneActions: some View {
        Button {
            Task { await vm.startNewRound() }
        } label: {
            Text(String(localized: "swipe_done_more"))
                .font(AppTypography.titleMedium.font.weight(AppTypography.WeightOverride.semibold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(tagControlBrandGradient)
                .clipShape(Capsule())
        }
        .disabled(vm.isSubmitting)
        .accessibilityIdentifier("swipe_done_more")
        Button {
            handleBack()
        } label: {
            let s = appScheme(cs)
            return Text(String(localized: "swipe_done_back"))
                .font(AppTypography.titleMedium.font.weight(AppTypography.WeightOverride.semibold))
                .foregroundColor(s.onSurface)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(s.surfaceContainerHigh)
                .clipShape(Capsule())
        }
        .accessibilityIdentifier("swipe_done_back")
    }
}

// MARK: - 卡片堆（手势状态机 + 预载）

/// 手势状态机：
/// - idle（dragOffset == .zero，isFlying == false）：接受拖拽/点按；
/// - dragging：跟手位移 + 轻微跟手旋转（水平位移 / 20 度，anchor 底部），决策预告章随进度淡入；
/// - released：主位移轴定方向（|ty|>|tx| 且向上 → DELETE；水平主导 tx>0 → KEEP、tx<0 → SKIP），
///   主轴位移超卡片宽 25% → 飞出落决策；未超弹回（spring）；
/// - flying（isFlying == true）：卡片向决策方向飞出（easeIn 200ms），动画结束后 vm.decide 落账、
///   后卡 spring 上位；飞行中忽略新手势，落账后复位 idle。
/// 堆内渲染当前卡 + 后 3 张（spec §5 preload: 3），后卡缩进可视化即预载触发。
private struct SwipeCardDeck: View {
    @ObservedObject var vm: SwipeReviewViewModel
    let cardWidth: CGFloat
    let cardHeight: CGFloat

    @State private var dragOffset: CGSize = .zero
    @State private var isFlying = false

    @Environment(\.colorScheme) private var cs

    private var threshold: CGFloat {
        cardWidth * SwipeReviewViewModel.swipeThresholdFraction
    }

    /// 当前可见卡下标（含当前卡 + 预载 3 张）。
    private var visibleRange: Range<Int> {
        let end = min(vm.index + SwipeReviewViewModel.preloadCount + 1, vm.queue.count)
        return vm.index..<max(end, vm.index)
    }

    var body: some View {
        ZStack {
            ForEach(Array(visibleRange.reversed()), id: \.self) { i in
                let depth = i - vm.index
                SwipeCardView(
                    candidate: vm.queue[i],
                    targetSize: CGSize(width: cardWidth * 2, height: cardHeight * 2))
                    .scaleEffect(1 - CGFloat(depth) * 0.05)
                    .offset(y: depth == 0 ? 0 : CGFloat(depth) * 10)
                    .offset(depth == 0 ? dragOffset : .zero)
                    .rotationEffect(
                        .degrees(depth == 0 ? Double(dragOffset.width / 20) : 0),
                        anchor: .bottom)
                    .overlay(alignment: .top) {
                        if depth == 0 { stampOverlay }
                    }
                    .accessibilityIdentifier(depth == 0 ? "swipe_card_current" : "swipe_card_next_\(depth)")
            }
        }
        .frame(width: cardWidth, height: cardHeight)
        .contentShape(Rectangle())
        // 手势挂载在静止容器上（卡片自身被 dragOffset 平移会引入位移反馈，拖拽不再 1:1 跟手）
        .gesture(drag)
        .onTapGesture {
            guard !isFlying else { return }
            commit(.skip)   // spec §5 gestures: tap = skip
        }
    }

    // MARK: 手势

    private var drag: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard !isFlying else { return }
                dragOffset = value.translation
            }
            .onEnded { value in
                handleDragEnd(value)
            }
    }

    private func handleDragEnd(_ value: DragGesture.Value) {
        guard !isFlying else { return }
        let tx = value.translation.width
        let ty = value.translation.height
        // 主位移轴定方向：垂直向上主导 → DELETE；水平主导 → 右 KEEP / 左 SKIP
        if abs(ty) > abs(tx) {
            if ty < -threshold {
                commit(.delete)
                return
            }
        } else {
            if tx > threshold {
                commit(.keep)
                return
            }
            if tx < -threshold {
                commit(.skip)
                return
            }
        }
        // 未超阈值弹回
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
            dragOffset = .zero
        }
    }

    /// 决策落账前的飞出动画（easeIn 200ms），完成后 vm.decide + 后卡 spring 上位。
    private func commit(_ decision: SwipeDecision) {
        guard !isFlying else { return }
        isFlying = true
        let target = flyTarget(for: decision)
        withAnimation(.easeIn(duration: 0.2)) {
            dragOffset = target
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) {
            withAnimation(.spring(response: 0.25, dampingFraction: 0.9)) {
                vm.decide(decision)
            }
            dragOffset = .zero
            isFlying = false
        }
    }

    private func flyTarget(for decision: SwipeDecision) -> CGSize {
        switch decision {
        case .keep:
            return CGSize(width: cardWidth * 1.6, height: dragOffset.height)
        case .skip:
            return CGSize(width: -cardWidth * 1.6, height: dragOffset.height)
        case .delete:
            return CGSize(width: dragOffset.width, height: -cardHeight * 1.6)
        }
    }

    // MARK: 决策预告章（跟手进度淡入；spec 未规定样式，v1 口径自由实现）

    @ViewBuilder private var stampOverlay: some View {
        if let info = stampInfo {
            MatIcon(name: info.icon, size: IconSize.xl)
                .foregroundColor(info.color)
                .padding(Spacing.sm)
                .background(Circle().fill(Color.white.opacity(AppAlpha.emphasis)))
                .opacity(info.progress)
                .allowsHitTesting(false)
        }
    }

    private var stampInfo: (icon: String, color: Color, progress: Double)? {
        let tx = dragOffset.width
        let ty = dragOffset.height
        let t = Double(threshold)
        guard t > 0 else { return nil }
        if abs(ty) > abs(tx) {
            guard ty < 0 else { return nil }
            return ("mat_delete", StatusColor.error, min(1, -Double(ty) / t))
        }
        if tx > 0 {
            return ("mat_check_circle", StatusColor.success, min(1, Double(tx) / t))
        }
        if tx < 0 {
            return ("mat_close", appScheme(cs).onSurfaceVariant, min(1, -Double(tx) / t))
        }
        return nil
    }
}

// MARK: - 单卡（大图 + 入列原因角标）

private struct SwipeCardView: View {
    let candidate: SwipeCandidate
    var targetSize: CGSize

    @State private var image: UIImage?
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        return ZStack {
            s.surfaceVariant   // 占位底色
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .overlay(alignment: .bottomLeading) {
            // 入列原因角标（spec §5 bucket_order badge_key；badge_scrim = black 40%）
            Text(NSLocalizedString(SwipeReasonBadge.labelKey(candidate.reason), comment: ""))
                .font(AppTypography.labelSmall.font)
                .foregroundColor(.white)
                .padding(.horizontal, Spacing.md)
                .padding(.vertical, Spacing.xs)
                .background(Capsule().fill(Color.black.opacity(0.4)))
                .padding(Spacing.sm)
                .accessibilityIdentifier("swipe_card_reason")
        }
        .task(id: candidate.uri) {
            image = await ThumbnailLoader.shared.thumbnail(
                for: candidate.uri, size: targetSize)
        }
    }
}

/// 入列原因 → i18n 键（spec §5 bucket_order badge_key，键名对齐 Android strings.xml）。
private enum SwipeReasonBadge {
    static func labelKey(_ reason: SwipeReason) -> String {
        switch reason {
        case .screenshot: return "swipe_reason_screenshot"
        case .blurry: return "swipe_reason_blurry"
        case .lowQualityPortrait: return "swipe_reason_portrait"
        case .recent: return "swipe_reason_recent"
        }
    }
}

// MARK: - 字节格式化（1024 进制，口径同 ModelCatalog.formattedSize）

private enum SwipeByteFormatter {
    static func format(_ bytes: Int64) -> String {
        if bytes >= 1024 * 1024 * 1024 {
            return String(format: "%.2f GB", Double(bytes) / (1024 * 1024 * 1024))
        } else if bytes >= 1024 * 1024 {
            return String(format: "%.2f MB", Double(bytes) / (1024 * 1024))
        } else if bytes >= 1024 {
            return String(format: "%.2f KB", Double(bytes) / 1024)
        }
        return "\(bytes) B"
    }
}
