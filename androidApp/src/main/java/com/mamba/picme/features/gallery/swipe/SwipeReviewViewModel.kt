package com.mamba.picme.features.gallery.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.swipe.SwipeCandidate
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
    ) : SwipeUiState {
        val current: SwipeCandidate? get() = queue.getOrNull(index)

        /** 已标记 DELETE 的字节合计（含已提交批次，会话内单调不减）。 */
        val freedBytes: Long
            get() {
                val sizeByUri = queue.associate { candidate -> candidate.uri to candidate.sizeBytes }
                return decisions
                    .filter { pair -> pair.second == SwipeDecision.DELETE }
                    .sumOf { pair -> sizeByUri[pair.first] ?: 0L }
            }
    }

    data class Done(
        val kept: Int,
        val deleted: Int,
        val skipped: Int,
        val freedBytes: Long,
        /** 本会话全部已回收 uri（整批 undo 用）。 */
        val trashedUris: List<String>,
    ) : SwipeUiState
}

/**
 * 手势整理会话 VM：队列决策 + undo + 批量回收站提交编排。
 *
 * DELETE 只标记不即时删；未提交 DELETE 累计达 [AUTO_COMMIT_THRESHOLD] 张自动提交一批；
 * 队列走完或手动 [finish] 提交剩余批。已提交批次不可 undo（undo 只覆盖未提交部分）。
 * [trashController] 在 VM 内构造（scope = viewModelScope），授权 IntentSender 的拉起
 * 与结果回调在 UI 层（StartIntentSenderForResult）。
 */
class SwipeReviewViewModel(
    private val organizeRepository: OrganizeRepository,
    trashBackend: TrashBackend,
    /** 测试注入作用域（避开 Dispatchers.Main）；生产为 null → viewModelScope。 */
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    init {
        // outcome 结算独立于当前 UI state：授权回调只清 pending（controller 内同步完成），
        // 复查结果经 outcomes 流异步到达（同 OrganizeCategoryViewModel 范式）
        scope.launch { trashController.outcomes.collect { outcome -> onTrashOutcome(outcome) } }
        restart()
    }

    private fun currentReviewing(): SwipeUiState.Reviewing? = _uiState.value as? SwipeUiState.Reviewing

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
        finishing = false
        commitInFlight = false
        _uiState.value = SwipeUiState.Loading
        scope.launch {
            val queue = withContext(ioDispatcher) {
                SwipeQueueBuilder.build(organizeRepository.loadItems())
            }
            _uiState.value = if (queue.isEmpty()) {
                SwipeUiState.Done(
                    kept = 0, deleted = 0, skipped = 0,
                    freedBytes = 0L, trashedUris = emptyList(),
                )
            } else {
                SwipeUiState.Reviewing(queue = queue, index = 0, decisions = emptyList())
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
        )
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
        _uiState.value = state.copy(index = state.index - 1, decisions = state.decisions.dropLast(1))
    }

    /**
     * 结束会话：有未提交 DELETE → 提交剩余批，授权成功（Trashed）后进 Done，
     * 授权 Cancelled → 留在 Reviewing 原地（未提交批回滚保留，可重试或继续）；
     * 无未提交删除 → 直接 Done。
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
    }

    /** Done 态整批 undo：恢复本会话全部已回收 uri，Restored 后 restart 重新建队。 */
    fun undoAll() {
        val done = _uiState.value as? SwipeUiState.Done ?: return
        if (done.trashedUris.isEmpty()) return
        Logger.d(TAG, "undoAll: restore ${done.trashedUris.size} uris")
        trashController.requestRestore(done.trashedUris)
    }

    /** Done 态再来一轮：重新 loadItems 建队。 */
    fun oneMoreRound() {
        if (_uiState.value !is SwipeUiState.Done) return
        restart()
    }

    /** UI 授权回调：只转发给 controller（清 pending + 触发复查）；状态迁移由 outcomes 流驱动。 */
    fun onTrashResult(ok: Boolean) {
        trashController.onTrashResult(ok)
    }

    fun onRestoreResult(ok: Boolean) {
        trashController.onRestoreResult(ok)
    }

    private fun requestCommit(uris: List<String>) {
        if (uris.isEmpty() || commitInFlight) return
        Logger.d(TAG, "commit trash batch: ${uris.size} uris")
        commitInFlight = true
        lastSubmittedBatch = uris
        submittedDeletes += uris
        trashController.requestTrash(uris, tag = SWIPE_TAG)
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
                commitInFlight = false
                finishing = false
                // 批次回滚为未提交：留在 undo 栈外但可被 finish 重试
                submittedDeletes -= lastSubmittedBatch.toSet()
                lastSubmittedBatch = emptyList()
            }
            TrashOutcome.Unsupported -> {
                commitInFlight = false
                finishing = false
            }
        }
    }

    /** 结算进 Done：deleted/freedBytes 以实际已回收 uri 计（部分拒绝不计入）。 */
    private fun settleDone() {
        val state = currentReviewing() ?: return
        val sizeByUri = state.queue.associate { candidate -> candidate.uri to candidate.sizeBytes }
        _uiState.value = SwipeUiState.Done(
            kept = state.decisions.count { pair -> pair.second == SwipeDecision.KEEP },
            deleted = sessionTrashedUris.size,
            skipped = state.decisions.count { pair -> pair.second == SwipeDecision.SKIP },
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
