package com.mamba.picme.features.gallery.dedup

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.repository.OrganizeRepository
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanController
import com.mamba.picme.domain.dedup.DedupScanEvent
import com.mamba.picme.domain.dedup.DedupTrashManager
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.dedup.KeepPolicyEngine
import com.mamba.picme.domain.organize.CategoryStat
import com.mamba.picme.domain.organize.OrganizeCategorizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class DedupViewModel(
    private val mediaSource: DedupMediaSource,
    private val scanner: DedupScanController,
    private val trashManager: DedupTrashManager,
    /** 整理中心 hub（Config 态）类目统计数据源。 */
    private val organizeRepository: OrganizeRepository,
    /** API < 30 无回收站授权接口，由 UI 层注入旧删除流回调兜底。 */
    private val legacyDeleter: ((List<String>) -> Unit)? = null,
    /** 测试注入作用域（避开 Dispatchers.Main）；生产为 null → viewModelScope。 */
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow<DedupUiState>(DedupUiState.Config())
    val uiState: StateFlow<DedupUiState> = _uiState.asStateFlow()

    /** 整理中心 hub 类目统计（Config 态展示；DUPLICATES 不在其中，hub 卡用 dedup 摘要）。 */
    val categoryStats: StateFlow<List<CategoryStat>> =
        organizeRepository.observeItems()
            .map { items -> OrganizeCategorizer.stats(items) }
            .flowOn(ioDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    private var scanJob: Job? = null

    // ---------- 扫描生命周期 ----------

    fun startScan(config: DedupScanConfig) {
        if (_uiState.value is DedupUiState.Scanning) return // 防重入
        val phases = config.levels.sorted()
        _uiState.value = DedupUiState.Scanning(
            phase = phases.firstOrNull() ?: DedupLevel.EXACT,
            phaseIndex = 1,
            phaseCount = phases.size.coerceAtLeast(1),
            scanned = 0,
            total = 0,
            paused = false,
            foundGroups = emptyList(),
        )
        scanJob = scope.launch {
            val items = mediaSource.photoScanItems()
            scanner.scan(items, config)
                .flowOn(ioDispatcher)
                .collect { event -> onScanEvent(event) }
        }
    }

    fun pauseScan() {
        if (_uiState.value !is DedupUiState.Scanning) return
        scanner.pauseRequested = true
        updateScanning { state -> state.copy(paused = true) }
    }

    fun resumeScan() {
        scanner.resume()
        updateScanning { state -> state.copy(paused = false) }
    }

    fun cancelScan() {
        scanner.resume() // 防卡死：暂停态取消时扫描循环需退出 awaitIfPaused
        scanJob?.cancel()
        scanJob = null
        _uiState.value = DedupUiState.Config()
    }

    private fun onScanEvent(event: DedupScanEvent) {
        when (event) {
            is DedupScanEvent.Progress -> updateScanning { state ->
                state.copy(phase = event.phase, scanned = event.scanned, total = event.total)
            }
            is DedupScanEvent.GroupFound -> updateScanning { state ->
                state.copy(foundGroups = listOf(event.group) + state.foundGroups) // 最新发现排前
            }
            is DedupScanEvent.PhaseChanged -> updateScanning { state ->
                state.copy(phase = event.phase, phaseIndex = event.phaseIndex, phaseCount = event.phaseCount)
            }
            is DedupScanEvent.Done -> {
                // 扫描器按 BEST_QUALITY 建组；Done 时按当前保留规则重算默认勾选
                //（resortGroup 天然跳过 userOverride，扫描态不会产生 override）
                val groups = event.groups.map { group -> resortGroup(group, _policy.value) }
                val firstTab = DedupLevel.entries.firstOrNull { level ->
                    groups.any { group -> group.level == level }
                } ?: DedupLevel.EXACT
                _uiState.value = DedupUiState.Results(
                    groups = groups,
                    selectedTab = firstTab,
                    policy = _policy.value,
                )
            }
            DedupScanEvent.Cancelled -> _uiState.value = DedupUiState.Config()
        }
    }

    private inline fun updateScanning(transform: (DedupUiState.Scanning) -> DedupUiState.Scanning) {
        (_uiState.value as? DedupUiState.Scanning)?.let { state -> _uiState.value = transform(state) }
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

    override fun onCleared() {
        scanner.resume()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Dedup"
    }
}
