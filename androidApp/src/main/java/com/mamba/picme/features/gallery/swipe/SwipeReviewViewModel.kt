package com.mamba.picme.features.gallery.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.swipe.SwipeCandidate
import com.mamba.picme.domain.swipe.SwipeKeepHistory
import com.mamba.picme.domain.swipe.SwipeKeepHistoryStore
import com.mamba.picme.domain.swipe.SwipeQueueBuilder
import com.mamba.picme.domain.trash.TrashBackend
import com.mamba.picme.domain.trash.TrashOutcome
import com.mamba.picme.domain.trash.TrashSessionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 手势决策。DELETE 只标记，批量提交回收站由 VM 编排。 */
enum class SwipeDecision { KEEP, DELETE, SKIP }

/**
 * 手势快速整理（F2）会话状态（Agent First：sealed 枚举全部合法状态）。
 * Loading → Reviewing（逐张决策 + undo 栈）→（回收站授权）→ Done（可整批 undo / 再来一轮）。
 */
sealed interface SwipeUiState {
    data object Loading : SwipeUiState

    data class Reviewing(
        val queue: List<SwipeCandidate>,
        val index: Int,
        /** undo 栈：(uri, 决策) 按决策顺序入栈。 */
        val decisions: List<Pair<String, SwipeDecision>>,
        /**
         * 已标记 DELETE 的字节合计（含已提交批次，会话内单调不减）。
         * 存储字段：decide/undo/discard 时增量维护（O(1)），不做重组期全队列重算。
         */
        val freedBytes: Long,
    ) : SwipeUiState {
        val current: SwipeCandidate? get() = queue.getOrNull(index)
    }

    data class Done(
        val kept: Int,
        val deleted: Int,
        /** SKIP 决策 + 未实际回收的 DELETE（授权被拒/设备不支持），保证三桶守恒。 */
        val skipped: Int,
        val freedBytes: Long,
        /** 本会话全部已回收 uri（整批 undo 用）。 */
        val trashedUris: List<String>,
    ) : SwipeUiState
}

/**
 * 手势整理会话 VM：队列决策 + undo + 批量回收站提交编排 + KEEP 30 天抑制。
 *
 * DELETE 只标记不即时删；未提交 DELETE 累计达 [AUTO_COMMIT_THRESHOLD] 张自动提交一批；
 * 队列走完或手动 [finish] 提交剩余批。已提交批次不可 undo（undo 只覆盖未提交部分）。
 * [trashController] 在 VM 内构造（scope = viewModelScope），授权 IntentSender 的拉起
 * 与结果回调在 UI 层（StartIntentSenderForResult）。
 *
 * 失败路径防死锁（2026-09-05 审查修复）：API<30（!isSupported）时提交短路——不挂
 * commitInFlight/submittedDeletes，[finish] 直接 settleDone（未提交 DELETE 以 skipped 口径
 * 进统计）；token 构建失败无 outcome，由 init 的 errorEvent collect 按 Cancelled 同款回滚。
 *
 * KEEP 抑制：建队时过滤 [SwipeKeepHistory] 30 天活跃条目（读取顺带清理过期并写回），
 * decide(KEEP) 即时持久化，undo(KEEP) 回滚本会话新增条目。
 */
class SwipeReviewViewModel(
    private val organizeRepository: OrganizeRepository,
    trashBackend: TrashBackend,
    private val keepHistoryStore: SwipeKeepHistoryStore,
    /** 测试注入作用域（避开 Dispatchers.Main）；生产为 null → viewModelScope。 */
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 时间源（测试注入 fake 时钟）。 */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    val trashController = TrashSessionController(trashBackend, scope, ioDispatcher)

    private val _uiState = MutableStateFlow<SwipeUiState>(SwipeUiState.Loading)
    val uiState: StateFlow<SwipeUiState> = _uiState.asStateFlow()

    /** 已提交回收站（在途或已回收）的 DELETE uri；undo 对它们不生效。 */
    private val submittedDeletes = mutableSetOf<String>()

    /** 最近一次提交的批次（授权 Cancelled 时回滚为未提交，供重试）。 */
    private var lastSubmittedBatch: List<String> = emptyList()

    /** 本会话已实际回收的 uri（跨批次累计，Done 汇总与整批 undo 的数据源）。 */
    private val sessionTrashedUris = mutableListOf<String>()

    /** finish 触发后在途等待最终批次结算。 */
    private var finishing = false

    /** 有在途提交（token 构建异步，pendingRequest 尚未置位时也要挡住重复提交）。 */
    private var commitInFlight = false

    /** API<30 不支持提示每会话只触发一次（errorEvent 链路）。 */
    private var unsupportedNotified = false

    /** KEEP 抑制历史活跃条目集（restart 加载 + 裁剪后）。 */
    private var keepEntries = mutableSetOf<String>()

    /** 本会话新增 KEEP 条目（uri → 编码条目），undo(KEEP) 回滚用。 */
    private val sessionKeepEntries = mutableMapOf<String, String>()

    init {
        // outcome 结算独立于当前 UI state：授权回调只清 pending（controller 内同步完成），
        // 复查结果经 outcomes 流异步到达（同 OrganizeCategoryViewModel 范式）
        scope.launch { trashController.outcomes.collect { outcome -> onTrashOutcome(outcome) } }
        // token 构建失败路径无 outcome：errorEvent 置位即回滚在途提交状态（防死锁）
        scope.launch {
            trashController.errorEvent.collect { isError -> if (isError) rollbackCommit() }
        }
        restart()
    }

    private fun currentReviewing(): SwipeUiState.Reviewing? = _uiState.value as? SwipeUiState.Reviewing

    /** 顶栏 undo 可用性：尾条决策未提交回收站才可回退。 */
    val canUndo: Boolean
        get() {
            val state = currentReviewing() ?: return false
            val last = state.decisions.lastOrNull() ?: return false
            return last.first !in submittedDeletes
        }

    /** 未提交回收站的 DELETE 数（授权被拒后待提交出口面板的计数）。 */
    fun pendingDeleteCount(): Int = pendingDeleteUris().size

    /** 未提交回收站的 DELETE uri（按决策顺序）。 */
    private fun pendingDeleteUris(): List<String> {
        val state = currentReviewing() ?: return emptyList()
        return state.decisions
            .filter { pair -> pair.second == SwipeDecision.DELETE }
            .map { pair -> pair.first }
            .filter { uri -> uri !in submittedDeletes }
    }

    private fun restart() {
        submittedDeletes.clear()
        lastSubmittedBatch = emptyList()
        sessionTrashedUris.clear()
        sessionKeepEntries.clear()
        finishing = false
        commitInFlight = false
        unsupportedNotified = false
        _uiState.value = SwipeUiState.Loading
        scope.launch {
            val queue = withContext(ioDispatcher) {
                val history = keepHistoryStore.load()
                keepEntries = SwipeKeepHistory.activeEntries(history, nowMs()).toMutableSet()
                if (keepEntries.size != history.size) {
                    // 读取顺带清理过期/脏条目并写回
                    keepHistoryStore.save(keepEntries.toSet())
                }
                val suppressed = SwipeKeepHistory.activeUris(keepEntries)
                SwipeQueueBuilder.build(organizeRepository.loadItems())
                    .filterNot { candidate -> candidate.uri in suppressed }
            }
            _uiState.value = if (queue.isEmpty()) {
                SwipeUiState.Done(
                    kept = 0, deleted = 0, skipped = 0,
                    freedBytes = 0L, trashedUris = emptyList(),
                )
            } else {
                SwipeUiState.Reviewing(queue = queue, index = 0, decisions = emptyList(), freedBytes = 0L)
            }
        }
    }

    fun decide(decision: SwipeDecision) {
        val state = currentReviewing() ?: return
        val candidate = state.current ?: return
        val newIndex = state.index + 1
        _uiState.value = state.copy(
            index = newIndex,
            decisions = state.decisions + (candidate.uri to decision),
            freedBytes = state.freedBytes +
                if (decision == SwipeDecision.DELETE) candidate.sizeBytes else 0L,
        )
        if (decision == SwipeDecision.KEEP) {
            val entry = SwipeKeepHistory.encode(candidate.uri, nowMs())
            sessionKeepEntries[candidate.uri] = entry
            keepEntries += entry
            scope.launch(ioDispatcher) { keepHistoryStore.save(keepEntries.toSet()) }
        }
        if (newIndex >= state.queue.size) {
            finish()
        } else if (decision == SwipeDecision.DELETE &&
            pendingDeleteUris().size >= AUTO_COMMIT_THRESHOLD
        ) {
            requestCommit(pendingDeleteUris())
        }
    }

    /** 回退最后一条决策；已提交回收站的批次不可 undo；index=0 时 no-op。 */
    fun undo() {
        val state = currentReviewing() ?: return
        val last = state.decisions.lastOrNull() ?: return
        if (last.first in submittedDeletes) {
            Logger.d(TAG, "undo ignored: ${last.first} already committed to trash")
            return
        }
        _uiState.value = state.copy(
            index = state.index - 1,
            decisions = state.decisions.dropLast(1),
            freedBytes = state.freedBytes - if (last.second == SwipeDecision.DELETE) {
                state.queue.firstOrNull { candidate -> candidate.uri == last.first }?.sizeBytes ?: 0L
            } else {
                0L
            },
        )
        if (last.second == SwipeDecision.KEEP) {
            // 回滚只移除本会话新增的 KEEP 条目（历史会话条目不动）
            sessionKeepEntries.remove(last.first)?.let { entry ->
                keepEntries -= entry
                scope.launch(ioDispatcher) { keepHistoryStore.save(keepEntries.toSet()) }
            }
        }
    }

    /**
     * 结束会话：有未提交 DELETE → 提交剩余批，授权成功（Trashed）后进 Done，
     * 授权 Cancelled → 留在 Reviewing 原地（未提交批回滚保留，可重试或继续）；
     * 无未提交删除 → 直接 Done；API<30 不可提交 → errorEvent 已置位，直接结算
     * （未提交 DELETE 以 skipped 口径进统计）。
     */
    fun finish() {
        val state = currentReviewing() ?: return
        val pending = pendingDeleteUris()
        if (pending.isEmpty() && !commitInFlight) {
            settleDone()
            return
        }
        finishing = true
        requestCommit(pending)
        if (!trashController.isSupported) {
            settleDone()
        }
    }

    /**
     * 授权被拒后的放弃出口：未提交 DELETE 全部改记 SKIP（已提交批次不动），
     * 随后重新结算（无残留 pending → settleDone 进 Done）。
     */
    fun discardPendingDeletes() {
        val state = currentReviewing() ?: return
        val pending = pendingDeleteUris().toSet()
        if (pending.isEmpty()) return
        Logger.d(TAG, "discard ${pending.size} pending deletes as skip")
        _uiState.value = state.copy(
            decisions = state.decisions.map { pair ->
                if (pair.second == SwipeDecision.DELETE && pair.first in pending) {
                    pair.first to SwipeDecision.SKIP
                } else {
                    pair
                }
            },
            freedBytes = state.freedBytes - pending.sumOf { uri ->
                state.queue.firstOrNull { candidate -> candidate.uri == uri }?.sizeBytes ?: 0L
            },
        )
        finish()
    }

    /** Done 态整批 undo：恢复本会话全部已回收 uri，Restored 后 restart 重新建队。 */
    fun undoAll() {
        val done = _uiState.value as? SwipeUiState.Done ?: return
        if (done.trashedUris.isEmpty()) return
        Logger.d(TAG, "undoAll: restore ${done.trashedUris.size} uris")
        trashController.requestRestore(done.trashedUris)
    }

    /** Done 态再来一轮：重新 loadItems 建队（KEEP 30 天抑制生效）。 */
    fun oneMoreRound() {
        if (_uiState.value !is SwipeUiState.Done) return
        restart()
    }

    private fun requestCommit(uris: List<String>) {
        if (uris.isEmpty() || commitInFlight) return
        if (!trashController.isSupported) {
            // 短路防死锁：不挂 commitInFlight/submittedDeletes；Unsupported outcome 由
            // controller 发射（回滚幂等）；errorEvent 提示每会话一次
            if (!unsupportedNotified) {
                unsupportedNotified = true
                Logger.w(TAG, "trash unsupported (API<30), cannot commit ${uris.size} uris")
                trashController.requestTrash(uris, tag = SWIPE_TAG)
            }
            return
        }
        Logger.d(TAG, "commit trash batch: ${uris.size} uris")
        commitInFlight = true
        lastSubmittedBatch = uris
        submittedDeletes += uris
        trashController.requestTrash(uris, tag = SWIPE_TAG)
    }

    /**
     * 批次回滚为未提交（授权 Cancelled / Unsupported / token 构建失败经 errorEvent）：
     * 保留在 undo 栈外但可被 finish 重试；submittedDeletes 变化无 uiState 迁移，
     * 强制重发 Reviewing 让 canUndo 等派生刷新。
     */
    private fun rollbackCommit() {
        commitInFlight = false
        finishing = false
        submittedDeletes -= lastSubmittedBatch.toSet()
        lastSubmittedBatch = emptyList()
        currentReviewing()?.let { state -> _uiState.value = state.copy() }
    }

    private fun onTrashOutcome(outcome: TrashOutcome) {
        when (outcome) {
            is TrashOutcome.Trashed -> {
                Logger.d(TAG, "trashed ${outcome.trashedUris.size} uris (finishing=$finishing)")
                commitInFlight = false
                lastSubmittedBatch = emptyList()
                sessionTrashedUris += outcome.trashedUris
                if (finishing) {
                    // 在途期间新标记的 DELETE 可能仍未提交，补提交后再结算
                    val remaining = pendingDeleteUris()
                    if (remaining.isEmpty()) settleDone() else requestCommit(remaining)
                }
            }
            is TrashOutcome.Restored -> {
                Logger.d(TAG, "restored ${outcome.restoredUris.size} uris, restarting session")
                restart()
            }
            TrashOutcome.Cancelled -> {
                Logger.d(TAG, "auth cancelled, batch rolled back to uncommitted")
                rollbackCommit()
            }
            TrashOutcome.Unsupported -> {
                Logger.w(TAG, "trash unsupported, batch rolled back to uncommitted")
                rollbackCommit()
            }
        }
    }

    /** 结算进 Done：deleted/freedBytes 以实际已回收 uri 计；未回收 DELETE 按 skipped 计（三桶守恒）。 */
    private fun settleDone() {
        val state = currentReviewing() ?: return
        val sizeByUri = state.queue.associate { candidate -> candidate.uri to candidate.sizeBytes }
        val deleteDecisions = state.decisions.count { pair -> pair.second == SwipeDecision.DELETE }
        _uiState.value = SwipeUiState.Done(
            kept = state.decisions.count { pair -> pair.second == SwipeDecision.KEEP },
            deleted = sessionTrashedUris.size,
            skipped = state.decisions.count { pair -> pair.second == SwipeDecision.SKIP } +
                (deleteDecisions - sessionTrashedUris.size).coerceAtLeast(0),
            freedBytes = sessionTrashedUris.sumOf { uri -> sizeByUri[uri] ?: 0L },
            trashedUris = sessionTrashedUris.toList(),
        )
        finishing = false
    }

    private companion object {
        const val TAG = "PoLang:Swipe"
        const val SWIPE_TAG = "swipe"

        /** 未提交 DELETE 累计达到该值自动提交一批回收站授权。 */
        const val AUTO_COMMIT_THRESHOLD = 20
    }
}
