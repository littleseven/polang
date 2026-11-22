package com.mamba.picme.features.gallery.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.organize.AppSource
import com.mamba.picme.domain.organize.CategoryArbiter
import com.mamba.picme.domain.organize.OrganizeItem
import com.mamba.picme.domain.organize.ProtectReason
import com.mamba.picme.domain.organize.ValueGuard
import com.mamba.picme.domain.organize.appSourceOf
import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.trash.TrashBackend
import com.mamba.picme.domain.trash.TrashGuidanceGate
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

/**
 * 来源详情页单项：媒体 + 价值保护裁定。无类目/置信语义——ValueGuard 红线不因浏览视角
 * 放松：命中老照片/稀缺人物/用户互动的项恒不可选（与类目详情页保护区同口径）。
 */
data class SourceEntry(
    val item: OrganizeItem,
    val protectReasons: Set<ProtectReason>,
) {
    val isProtected: Boolean get() = protectReasons.isNotEmpty()
}

/**
 * 来源详情页两段分组（Task 7.5 裁定：三段简化为两段）：保护项一段（不可选），
 * 其余按拍摄日期降序一段（新→旧；captureDate=0 未知时间戳排段尾）。
 */
data class SourceSections(
    val protectedItems: List<SourceEntry>,
    val recentFirst: List<SourceEntry>,
)

/** 两段分组派生（UI/VM 共享纯函数）：两段互斥且完备。 */
fun List<SourceEntry>.toSourceSections(): SourceSections {
    val (protected, rest) = partition { entry -> entry.isProtected }
    return SourceSections(
        protectedItems = protected.sortedByDescending { entry -> entry.item.captureDate },
        recentFirst = rest.sortedByDescending { entry -> entry.item.captureDate },
    )
}

/**
 * 来源详情页状态（与 OrganizeCategoryUiState 同构）：Loading → Ready（网格 + 选中集）
 * →（批量回收站删除授权）→ Ready.trashed（完成态，可 undo）。
 * AI 预选保守化：selected 默认空集——来源页不做「建议删除」预选，全靠用户手选。
 */
sealed interface OrganizeSourceUiState {
    data object Loading : OrganizeSourceUiState

    data class Ready(
        val items: List<SourceEntry>,
        val selected: Set<String>,
        val trashed: Boolean = false,
        val trashedCount: Int = 0,
        val trashedBytes: Long = 0,
        val lastTrashedUris: List<String> = emptyList(),
    ) : OrganizeSourceUiState
}

/**
 * 来源详情页 VM（按 App 浏览媒体 + 回收站删除）：按 `appSourceOf(relativePath) == source`
 * 过滤全库快照，选中集管理与 TrashSessionController 编排与类目详情页同模式。
 *
 * 保护裁定：CategoryArbiter 定类后走 ValueGuard（仅低质两类受保护；未入任何类目的
 * 媒体无保护信号）。未分类媒体仍入网格（来源页是浏览视角，非清理管线出口）。
 */
class OrganizeSourceViewModel(
    val source: AppSource,
    private val organizeRepository: OrganizeRepository,
    trashBackend: TrashBackend,
    trashGuidanceGate: TrashGuidanceGate? = null,
    /** 测试注入作用域（避开 Dispatchers.Main）；生产为 null → viewModelScope。 */
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    val trashController = TrashSessionController(trashBackend, scope, ioDispatcher, trashGuidanceGate)

    private val _uiState = MutableStateFlow<OrganizeSourceUiState>(OrganizeSourceUiState.Loading)
    val uiState: StateFlow<OrganizeSourceUiState> = _uiState.asStateFlow()

    init {
        // outcome 结算独立于当前 UI state（同 OrganizeCategoryViewModel：复查结果经 outcomes 流异步到达）
        scope.launch { trashController.outcomes.collect { outcome -> onTrashOutcome(outcome) } }
        reload()
    }

    private fun currentReady(): OrganizeSourceUiState.Ready? =
        _uiState.value as? OrganizeSourceUiState.Ready

    private fun reload() {
        scope.launch {
            _uiState.value = OrganizeSourceUiState.Ready(
                items = loadSourceEntries(),
                selected = emptySet(),
            )
        }
    }

    private suspend fun loadSourceEntries(): List<SourceEntry> = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        organizeRepository.loadItems()
            .filter { item -> appSourceOf(item.relativePath) == source }
            .map { item ->
                val category = CategoryArbiter.classify(item)
                SourceEntry(
                    item = item,
                    protectReasons = category
                        ?.let { ValueGuard.assess(item, it, now).reasons }
                        ?: emptySet(),
                )
            }
            // media_assets.uri 非唯一索引，重复行会同段同 key 硬崩 LazyVerticalGrid
            .distinctBy { entry -> entry.item.uri }
    }

    /** 勾选切换；保护项（ValueGuard 命中）红线不可选，静默拒绝。 */
    fun toggle(uri: String) {
        val ready = currentReady() ?: return
        if (ready.trashed) return
        val entry = ready.items.firstOrNull { entry -> entry.item.uri == uri } ?: return
        if (entry.isProtected) return
        _uiState.value = ready.copy(
            selected = if (uri in ready.selected) ready.selected - uri else ready.selected + uri
        )
    }

    fun deleteSelected() {
        val ready = currentReady() ?: return
        if (ready.trashed || ready.selected.isEmpty()) return
        Logger.d(TAG, "request trash: source=$source count=${ready.selected.size}")
        trashController.requestTrash(ready.selected.toList(), tag = source.name)
    }

    /** UI 授权回调：只转发给 controller（清 pending + 触发复查）；状态迁移由 outcomes 流驱动。 */
    fun onTrashResult(ok: Boolean) {
        trashController.onTrashResult(ok)
    }

    fun undoLastTrash() {
        val ready = currentReady() ?: return
        if (ready.lastTrashedUris.isEmpty()) return
        Logger.d(TAG, "request restore: ${ready.lastTrashedUris.size} uris")
        trashController.requestRestore(ready.lastTrashedUris)
    }

    /** 恢复授权回调：只转发；恢复成功后的重载在 outcomes 流的 Restored 分支。 */
    fun onRestoreResult(ok: Boolean) {
        trashController.onRestoreResult(ok)
    }

    private fun onTrashOutcome(outcome: TrashOutcome) {
        when (outcome) {
            is TrashOutcome.Trashed -> {
                val ready = currentReady()
                if (ready == null) {
                    Logger.w(
                        TAG,
                        "trashed outcome in non-ready state, dropped: " +
                            "${outcome.trashedUris.size} uris (tag=${outcome.tag})"
                    )
                    return
                }
                val trashedSet = outcome.trashedUris.toSet()
                val bytes = ready.items
                    .filter { entry -> entry.item.uri in trashedSet }
                    .sumOf { entry -> entry.item.sizeBytes }
                val remaining = ready.items.filterNot { entry -> entry.item.uri in trashedSet }
                _uiState.value = ready.copy(
                    items = remaining,
                    selected = ready.selected - trashedSet,
                    trashed = remaining.isEmpty(),
                    trashedCount = ready.trashedCount + outcome.trashedUris.size,
                    trashedBytes = ready.trashedBytes + bytes,
                    lastTrashedUris = outcome.trashedUris,
                )
                Logger.d(
                    TAG,
                    "trashed ${outcome.trashedUris.size} items (tag=${outcome.tag}), " +
                        "remaining=${remaining.size}"
                )
            }
            is TrashOutcome.Restored -> {
                Logger.d(TAG, "restored ${outcome.restoredUris.size} uris, reloading source")
                // 计数清零——项已回到库中，不算已释放
                reload()
            }
            // Cancelled：留在原地可重试；Unsupported（API<30）：errorEvent 链路已弹 snackbar
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "PoLang:Organize"
    }
}
