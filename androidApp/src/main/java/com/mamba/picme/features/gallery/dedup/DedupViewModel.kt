package com.mamba.picme.features.gallery.dedup

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanProgress
import com.mamba.picme.domain.dedup.DedupScanSession
import com.mamba.picme.domain.dedup.DedupScanState
import com.mamba.picme.domain.dedup.DedupTrashManager
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.dedup.KeepPolicyEngine
import com.mamba.picme.domain.organize.OrganizeBoard
import com.mamba.picme.domain.organize.OrganizeCategorizer
import com.mamba.picme.domain.organize.StorageStory
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.swipe.SwipeMonth
import com.mamba.picme.domain.swipe.SwipeMonths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 去重 2.0 页面状态机（Agent First：sealed 枚举全部合法状态，无布尔组合）。
 *
 * Config → Scanning（渐进式 GroupFound）→ Results →（系统授权）→ Cleaned →（undo/done）→ Config。
 * 字节格式化在 UI 层，VM 只产出原始数值。
 */
sealed interface DedupUiState {
    data class Config(val config: DedupScanConfig = DedupScanConfig()) : DedupUiState

    data class Scanning(
        val phase: DedupLevel,
        val phaseIndex: Int,
        val phaseCount: Int,
        val scanned: Int,
        val total: Int,
        val paused: Boolean,
        val foundGroups: List<DedupGroup>,
    ) : DedupUiState

    data class Results(
        val groups: List<DedupGroup>,
        val selectedTab: DedupLevel,
        val policy: KeepPolicy,
    ) : DedupUiState

    data class Cleaned(
        val deletedCount: Int,
        val reclaimedBytes: Long,
        val trashedUris: List<String>,
        /** 按类型细分删除后未被清理的组（其他 Tab/未预选组），「继续整理」回 Results 用。 */
        val remainingGroups: List<DedupGroup> = emptyList(),
    ) : DedupUiState
}

/** 待 UI 发起的系统授权请求（回收站删除/恢复同款：uris + IntentSender）。 */
data class PendingTrash(
    val uris: List<String>,
    val intentSender: IntentSender,
    /** 非空 = 组详情「保留所选·删除其余」发起的逐组删除（确认后留在 Results 原地刷新）；null = 结果页底部 CTA 的按 Tab 批量删除（确认后进 Cleaned 完成页）。 */
    val groupId: String? = null,
)

/** board() 单次计算的双产出：hub 看板与存储叙事共享同一次 O(n²) 聚类（Task 7 合并派生）。 */
private data class OrganizeBoardWithStory(
    val board: OrganizeBoard,
    val story: StorageStory?,
)

class DedupViewModel(
    /** 扫描会话（服务所有）：VM 只观察进度并转发控制命令，不再自驱扫描。 */
    private val session: DedupScanSession,
    private val trashManager: DedupTrashManager,
    /** 整理中心 hub（Config 态）类目统计数据源。 */
    private val organizeRepository: OrganizeRepository,
    /** 上次预分析落点（organizePreAnalysisRanAtMsFlow，hub 新鲜度行）。 */
    private val settingsRepository: UserSettingsRepository,
    /** API < 30 无回收站授权接口，由 UI 层注入旧删除流回调兜底。 */
    private val legacyDeleter: ((List<String>) -> Unit)? = null,
    /** 测试注入作用域（避开 Dispatchers.Main）；生产为 null → viewModelScope。 */
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow<DedupUiState>(DedupUiState.Config())
    val uiState: StateFlow<DedupUiState> = _uiState.asStateFlow()

    /** board() 单次计算双流源（hub 看板 + 存储叙事共享一次 O(n²) 聚类，Task 7 合并派生）。 */
    private val organizeBoardStory: StateFlow<OrganizeBoardWithStory> =
        organizeRepository.observeItems()
            .map { items ->
                val board = OrganizeCategorizer.board(items, now = System.currentTimeMillis())
                OrganizeBoardWithStory(
                    board = board,
                    story = StorageStory.of(items, board, now = System.currentTimeMillis()),
                )
            }
            // 媒体库任何写都会触发 observeItems 重算，相同结果不下发（防抖重组）
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                OrganizeBoardWithStory(OrganizeBoard(emptyList(), 0L, 0), null),
            )

    /** 整理中心 hub 看板（Config 态由 UI 订阅）：类目卡聚合 + Hero 口径，管线单一事实来源。 */
    val organizeBoard: StateFlow<OrganizeBoard> = organizeBoardStory
        .map { pair -> pair.board }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), OrganizeBoard(emptyList(), 0L, 0))

    /** 存储空间叙事（Agent 清理 M1）：总账/来源账/过期截图/Agent 面可释放口径；null = 未加载（hub 不渲染叙事区）。 */
    val storageStory: StateFlow<StorageStory?> = organizeBoardStory
        .map { pair -> pair.story }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    /** 上次后台预分析落点（0 = 从未），hub 新鲜度行数据源。 */
    val preAnalysisRanAt: StateFlow<Long> = settingsRepository.organizePreAnalysisRanAtMsFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0L)

    /** 按月回顾（F2+）可用月份列表（新→旧），与建队同口径（非视频）。 */
    val swipeMonths: StateFlow<List<SwipeMonth>> = organizeRepository.observeItems()
        .map { items -> SwipeMonths.availableMonths(items) }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 整理中心 v2：后台分批补算模糊/曝光分（缺分即 LOW 覆盖，hub 引导态承接；补算回写经 Room Flow 自动刷新）
        scope.launch(ioDispatcher) {
            runCatching {
                // 跨批累计永久失败行（解码失败/云端占位符），下一批排除——否则失败行永久占据
                // 批次头部，后续待补算行静默停摆（written=0 即退出的旧逻辑）
                val failedUris = LinkedHashSet<String>()
                var batch = organizeRepository.backfillQualitySignals(excludeUris = failedUris)
                failedUris += batch.failedUris
                var batches = 1
                // 循环条件按 attempted（排除已知失败后仍有待算行）：全败批次不再提前退出；
                // BACKFILL_MAX_BATCHES 兜底防永久失败行反复入批放大为死循环（200/批 × 1000 = 20 万张覆盖）
                while (batch.attempted > 0 && batches < BACKFILL_MAX_BATCHES) {
                    batch = organizeRepository.backfillQualitySignals(excludeUris = failedUris)
                    batches++
                    failedUris += batch.failedUris
                    // 排除集容量封顶：只保留最近 500 个失败 uri（超出则最早逐出，
                    // 被逐出行下轮可能重试一次后再次入集，不会死循环——attempted 终为 0）
                    while (failedUris.size > BACKFILL_FAILED_URIS_CAP) {
                        failedUris.remove(failedUris.first())
                    }
                }
                if (failedUris.isNotEmpty() || batch.attempted > 0) {
                    // 循环结束仍有未补算残留：失败行 blurScore 恒 null 不进 LOW_QUALITY_PHOTOS，
                    // 或命中批次上限仍有积压——如实上报，真机排查看此日志
                    Logger.w(
                        ORGANIZE_TAG,
                        "backfill residual: ${failedUris.size} rows failed decode (skipped, " +
                            "blurScore stays null); lastBatch attempted=${batch.attempted} " +
                            "written=${batch.written}, batches=$batches"
                    )
                }
            }.onFailure { error -> Logger.w(TAG, "backfill quality signals failed", error) }
        }

        // 扫描会话观察：服务端会话进度驱动 uiState 迁移（Scanning/Results/Config）
        scope.launch {
            session.progress.collect { progress -> onSessionProgress(progress) }
        }
    }

    /** VM 级保留策略（Config/Results 共用）：Config 规则行与规则弹层改它；进入 Results 时带入 state.policy。 */
    private val _policy = MutableStateFlow(KeepPolicy.BEST_QUALITY)
    val policy: StateFlow<KeepPolicy> = _policy.asStateFlow()

    private val _pendingTrash = MutableStateFlow<PendingTrash?>(null)
    val pendingTrash: StateFlow<PendingTrash?> = _pendingTrash.asStateFlow()

    private val _pendingRestore = MutableStateFlow<PendingTrash?>(null)
    val pendingRestore: StateFlow<PendingTrash?> = _pendingRestore.asStateFlow()

    /** 一次性 UI 事件槽位（PendingTrash 同款模式）：回收站部分拒绝时置位，UI 弹 snackbar 后调 [consumePartialTrashNotice]。 */
    private val _partialTrashNotice = MutableStateFlow(false)
    val partialTrashNotice: StateFlow<Boolean> = _partialTrashNotice.asStateFlow()

    fun consumePartialTrashNotice() {
        _partialTrashNotice.value = false
    }

    // ---------- 扫描生命周期（转发 + 观察，执行归服务会话） ----------

    fun startScan(config: DedupScanConfig) {
        if (_uiState.value is DedupUiState.Scanning) return // 防重入；会话侧亦有活跃态守卫
        session.startScan(config)
    }

    fun pauseScan() {
        if (_uiState.value !is DedupUiState.Scanning) return
        session.pauseScan()
    }

    fun resumeScan() {
        session.resumeScan()
    }

    fun cancelScan() {
        if (_uiState.value !is DedupUiState.Scanning) return
        session.cancelScan()
    }

    /** 会话进度 → uiState：活跃态映射 Scanning；COMPLETED 落 Results（沿用 Done 分支的排序/选 Tab 逻辑）；CANCELLED/null 退 Config。 */
    private fun onSessionProgress(progress: DedupScanProgress?) {
        when (progress?.state) {
            DedupScanState.RUNNING, DedupScanState.PAUSED -> _uiState.value = DedupUiState.Scanning(
                phase = progress.phase,
                phaseIndex = progress.phaseIndex,
                phaseCount = progress.phaseCount,
                scanned = progress.scanned,
                total = progress.total,
                paused = progress.state == DedupScanState.PAUSED,
                foundGroups = progress.foundGroups,
            )
            DedupScanState.COMPLETED -> {
                // 扫描器按 BEST_QUALITY 建组；完成时按当前保留规则重算默认勾选
                //（resortGroup 天然跳过 userOverride，扫描态不会产生 override）
                val groups = progress.resultGroups.orEmpty().map { group -> resortGroup(group, _policy.value) }
                val firstTab = DedupLevel.entries.firstOrNull { level ->
                    groups.any { group -> group.level == level }
                } ?: DedupLevel.EXACT
                _uiState.value = DedupUiState.Results(
                    groups = groups,
                    selectedTab = firstTab,
                    policy = _policy.value,
                )
            }
            DedupScanState.CANCELLED, null ->
                if (_uiState.value is DedupUiState.Scanning) _uiState.value = DedupUiState.Config()
        }
    }

    // ---------- 结果操作 ----------

    /** 仅 Results 态生效（扫描屏只展示不改选，Scanning 态调用忽略，避免 Done 覆盖丢失 override）。 */
    fun setKeep(groupId: String, uri: String) {
        val state = _uiState.value as? DedupUiState.Results ?: return
        _uiState.value =
            state.copy(groups = state.groups.map { group -> withKeep(group, groupId, uri) })
    }

    private fun withKeep(group: DedupGroup, groupId: String, uri: String): DedupGroup {
        if (group.id != groupId || group.members.none { member -> member.uri == uri }) return group
        return group.copy(keepUri = uri, userOverride = true)
    }

    /** Config/Results 均生效：先更新 VM 级策略；Results 态同时对 !userOverride 组重算默认保留。 */
    fun applyPolicy(policy: KeepPolicy) {
        _policy.value = policy
        val state = _uiState.value as? DedupUiState.Results ?: return
        _uiState.value = state.copy(
            policy = policy,
            groups = state.groups.map { group -> resortGroup(group, policy) },
        )
    }

    /**
     * 仅当前 Tab 内 autoPreselected 的组清 userOverride 并按当前 policy 重算（按类型细分：
     * 全选只作用于 selectedTab；SCENE 组本就不预选，天然不受影响）；未预选组保留原状不勾选。
     */
    fun smartSelectAll() {
        val state = _uiState.value as? DedupUiState.Results ?: return
        _uiState.value = state.copy(
            groups = state.groups.map { group ->
                if (!group.autoPreselected || group.level != state.selectedTab) {
                    group
                } else {
                    resortGroup(group.copy(userOverride = false), state.policy)
                }
            },
        )
    }

    private fun resortGroup(group: DedupGroup, policy: KeepPolicy): DedupGroup {
        if (group.userOverride) return group
        val sorted = KeepPolicyEngine.recommend(policy, group.members)
        return group.copy(members = sorted, keepUri = sorted.first().uri)
    }

    fun selectTab(level: DedupLevel) {
        val state = _uiState.value as? DedupUiState.Results ?: return
        _uiState.value = state.copy(selectedTab = level)
    }

    fun getGroup(id: String): DedupGroup? = when (val state = _uiState.value) {
        is DedupUiState.Results -> state.groups.firstOrNull { group -> group.id == id }
        is DedupUiState.Scanning -> state.foundGroups.firstOrNull { group -> group.id == id }
        else -> null
    }

    // ---------- 删除 / 撤销 ----------

    /**
     * 批量删除候选聚合（spec §4/§10.5 安全约束）：SCENE 相似场景组不参与批量删除，
     * 需逐组人工确认；未预选组（VISUAL 截图/文档）仅在用户改选（userOverride）后参与。
     * 统一口径收口在 [DedupGroup.batchEligible]，VM 删除流与结果页底部 CTA 共用。
     */
    fun batchDeleteUris(groups: List<DedupGroup>): List<String> =
        groups
            .filter { group -> group.batchEligible }
            .flatMap { group -> group.deleteUris }
            .distinct()

    /** [batchDeleteUris] 对应的可释放字节数（同一 uri 跨组只计一次）。 */
    fun batchReclaimBytes(groups: List<DedupGroup>, uris: List<String>): Long =
        groups
            .filter { group -> group.batchEligible }
            .flatMap { group -> group.members }
            .filter { member -> member.uri in uris }
            .distinctBy { member -> member.uri }
            .sumOf { member -> member.sizeBytes }

    /**
     * 当前 Tab 的批量删除候选 uri（按类型细分处理：仅 selectedTab 级别内的 batchEligible
     * 组参与）。结果页底部 CTA 与 [deleteSelected] 删除流共用此口径。
     */
    fun tabBatchUris(state: DedupUiState.Results): List<String> =
        batchDeleteUris(state.groups.filter { group -> group.level == state.selectedTab })

    /**
     * 删除当前 Tab 的批量候选（见 [tabBatchUris]）。API 30+ 发回收站
     * 授权（[pendingTrash]，UI 启动 IntentSender 后回调 [onTrashResult]）；API < 30
     * 走 [legacyDeleter] 旧删除流，状态保持 Results 由旧流自行管理。
     */
    fun deleteSelected() {
        val state = _uiState.value as? DedupUiState.Results ?: return
        val uris = tabBatchUris(state)
        if (uris.isEmpty()) return
        Logger.d(TAG, "deleteSelected: ${uris.size} uris")
        if (trashManager.isSupported) {
            // buildTrashIntent 是 MediaStore IPC：移出主线程并兜底异常，失败保持 Results 态
            scope.launch {
                val sender = withContext(ioDispatcher) {
                    runCatching { trashManager.buildTrashIntent(uris) }
                        .onFailure { error -> Logger.w(TAG, "buildTrashIntent failed", error) }
                        .getOrNull()
                }
                // IPC 窗口内选择集可能已变（setKeep 改选/切 Tab/双击）：复查仍为 Results、
                // 无在途授权且当前 Tab 选择集一致才发授权，避免弹窗覆盖 stale uris
                val current = _uiState.value as? DedupUiState.Results
                if (
                    sender != null &&
                    current != null &&
                    _pendingTrash.value == null &&
                    tabBatchUris(current) == uris
                ) {
                    _pendingTrash.value = PendingTrash(uris, sender)
                }
            }
        } else {
            legacyDeleter?.invoke(uris)
        }
    }

    /**
     * 组详情页「保留所选 · 删除其余 N 张」：逐组立即删除（spec §4 逐组确认语义，L3 场景相似
     * 的唯一删除通路）。与 [deleteSelected] 共用回收站授权流，但 [PendingTrash.groupId]
     * 标记为逐组删除——确认后留在 Results 原地刷新（该组消失/跨级重叠组瘦身），不进完成页。
     * deleteUris 为空（未预选且未改选）时 no-op，按钮仅承担「确认本组选择」。
     */
    fun deleteGroup(groupId: String) {
        val state = _uiState.value as? DedupUiState.Results ?: return
        val group = state.groups.firstOrNull { item -> item.id == groupId } ?: return
        val uris = group.deleteUris
        if (uris.isEmpty()) return
        Logger.d(TAG, "deleteGroup: $groupId, ${uris.size} uris")
        if (trashManager.isSupported) {
            scope.launch {
                val sender = withContext(ioDispatcher) {
                    runCatching { trashManager.buildTrashIntent(uris) }
                        .onFailure { error -> Logger.w(TAG, "buildTrashIntent failed", error) }
                        .getOrNull()
                }
                // IPC 窗口内组可能已改选：复查仍为 Results、无在途授权且该组选择集一致才发授权
                val current = (_uiState.value as? DedupUiState.Results)
                    ?.groups?.firstOrNull { item -> item.id == groupId }
                if (
                    sender != null &&
                    current != null &&
                    _pendingTrash.value == null &&
                    current.deleteUris == uris
                ) {
                    _pendingTrash.value = PendingTrash(uris, sender, groupId)
                }
            }
        } else {
            legacyDeleter?.invoke(uris)
        }
    }

    /**
     * 回收站授权结果。拒绝 → 整批留在 Results 不动；确认 → 复查仍存在的 uri（部分拒绝
     * V1 简化：有残留则整批不动，并置位 [partialTrashNotice] 提示用户）。
     * 批量（groupId=null）→ 全部消失才统计进 Cleaned；逐组（groupId 非空）→ 留在 Results
     * 原地刷新（授权弹窗下详情页已收起，用户期望回到结果页看到该组消失）。
     */
    fun onTrashResult(ok: Boolean) {
        val pending = _pendingTrash.value ?: return
        _pendingTrash.value = null
        if (!ok) return
        val state = _uiState.value as? DedupUiState.Results ?: return
        scope.launch {
            val remaining = withContext(ioDispatcher) { trashManager.queryExisting(pending.uris) }
            if (remaining.isNotEmpty()) {
                Logger.d(TAG, "trash partially confirmed, ${remaining.size} uris remain, keep Results intact")
                _partialTrashNotice.value = true
                return@launch
            }
            val deleted = pending.uris.toSet()
            val remainingGroups = computeRemainingGroups(state, deleted)
            if (pending.groupId != null) {
                // 逐组删除：原地刷新 Results，保留当前 Tab 与保留策略
                _uiState.value = state.copy(groups = remainingGroups)
            } else {
                _uiState.value = DedupUiState.Cleaned(
                    deletedCount = deleted.size,
                    reclaimedBytes = batchReclaimBytes(state.groups, pending.uris),
                    trashedUris = pending.uris,
                    remainingGroups = remainingGroups,
                )
            }
        }
    }

    /**
     * 删除后存活组计算（批量/逐组共用）：本次删干净的组（batchEligible 且 deleteUris 全部
     * 入回收站）移出结果；跨级重叠组先把已删成员从快照剔除：keepUri 被删的按当前 policy
     * 重算保留项（手动改选的对象已消失，override 一并失效），存活成员不足 2 的组不再成组，
     * 杜绝「快照回灌 Results 后把某组最后一张也送进回收站」。
     */
    private fun computeRemainingGroups(
        state: DedupUiState.Results,
        deleted: Set<String>,
    ): List<DedupGroup> =
        state.groups.mapNotNull { group ->
            if (
                group.batchEligible &&
                group.deleteUris.isNotEmpty() &&
                group.deleteUris.all { uri -> uri in deleted }
            ) {
                null
            } else {
                val alive = group.members.filter { member -> member.uri !in deleted }
                when {
                    alive.size < 2 -> null
                    group.keepUri in deleted -> {
                        val sorted = KeepPolicyEngine.recommend(state.policy, alive)
                        group.copy(members = sorted, keepUri = sorted.first().uri, userOverride = false)
                    }
                    else -> if (alive.size == group.members.size) group else group.copy(members = alive)
                }
            }
        }

    /** Cleaned 状态下发起恢复授权（[pendingRestore]，UI 回调 [onRestoreResult]）。 */
    fun undoTrash() {
        val state = _uiState.value as? DedupUiState.Cleaned ?: return
        if (state.trashedUris.isEmpty() || !trashManager.isSupported) return
        _pendingRestore.value = PendingTrash(state.trashedUris, trashManager.buildRestoreIntent(state.trashedUris))
    }

    fun onRestoreResult(ok: Boolean) {
        if (_pendingRestore.value == null) return
        _pendingRestore.value = null
        if (ok) _uiState.value = DedupUiState.Config()
    }

    /** Cleaned → Results（「继续整理」）：带剩余组回去，切到还有组的第一个 Tab。 */
    fun continueWithRemaining() {
        val state = _uiState.value as? DedupUiState.Cleaned ?: return
        if (state.remainingGroups.isEmpty()) return
        val firstTab = DedupLevel.entries.firstOrNull { level ->
            state.remainingGroups.any { group -> group.level == level }
        } ?: DedupLevel.EXACT
        _uiState.value = DedupUiState.Results(
            groups = state.remainingGroups,
            selectedTab = firstTab,
            policy = _policy.value,
        )
    }

    /** Cleaned → Config（「完成」按钮）。 */
    fun resetToConfig() {
        if (_uiState.value is DedupUiState.Cleaned) _uiState.value = DedupUiState.Config()
    }

    // 会话归服务所有：VM 销毁不触碰扫描生命周期（转后台/关页扫描继续，onDestroy 清 sessionProgress 兜底）

    private companion object {
        const val TAG = "Dedup"

        /** 整理中心补算日志标签（与 OrganizeRepositoryImpl 一致，便于真机按 organize 域过滤）。 */
        const val ORGANIZE_TAG = "PoLang:Organize"

        /** init 质量分补算循环批次上限：200/批 × 1000 = 20 万张覆盖，防御永久失败行死循环。 */
        const val BACKFILL_MAX_BATCHES = 1000

        /** 永久失败行排除集容量：只保留最近 500 个失败 uri（防无界增长；逐出行重试一次后仍败再入集）。 */
        const val BACKFILL_FAILED_URIS_CAP = 500
    }
}
