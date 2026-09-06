package com.mamba.picme.features.gallery.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.organize.ClassifiedItem
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.organize.OrganizeCategorizer
import com.mamba.picme.domain.organize.OrganizeConfidence
import com.mamba.picme.domain.repository.OrganizeRepository
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

/**
 * 整理中心类目详情页状态（Agent First：sealed 枚举全部合法状态）。
 * Loading → Ready（网格 + 选中集）→（批量回收站删除授权）→ Ready.trashed（完成态，可 undo）。
 * 字节格式化在 UI 层，VM 只产出原始数值。
 */
sealed interface OrganizeCategoryUiState {
    data object Loading : OrganizeCategoryUiState

    data class Ready(
        val items: List<ClassifiedItem>,
        /** 选中 uri 集合；AI 预选 = 默认勾选 HIGH 置信且非保护项（修复 v1「预选=全选」）。 */
        val selected: Set<String>,
        /** 完成态：该类目已清空（区别于「本来就空」的空态）。 */
        val trashed: Boolean = false,
        val trashedCount: Int = 0,
        val trashedBytes: Long = 0,
        /** 最近一批已回收 uri（undo 恢复对象）。 */
        val lastTrashedUris: List<String> = emptyList(),
    ) : OrganizeCategoryUiState
}

/** 详情页三段分组（spec §6.3）：建议删除（HIGH 非保护）/ 请确认（MEDIUM+LOW 非保护）/ 珍贵保护。 */
data class CategorySections(
    val suggested: List<ClassifiedItem>,
    val review: List<ClassifiedItem>,
    val protectedItems: List<ClassifiedItem>,
)

/** 三段分组派生（UI/VM 共享纯函数）：三段互斥且完备。 */
fun List<ClassifiedItem>.toSections(): CategorySections = CategorySections(
    suggested = filter { entry -> entry.confidence == OrganizeConfidence.HIGH && !entry.isProtected },
    review = filter { entry -> entry.confidence != OrganizeConfidence.HIGH && !entry.isProtected },
    protectedItems = filter { entry -> entry.isProtected },
)

/**
 * 类目详情页 VM：类目过滤 + 选中集管理 + 回收站删除/恢复编排。
 *
 * [trashController] 在 VM 内构造（scope = viewModelScope），授权 IntentSender 的拉起
 * 与结果回调在 UI 层（StartIntentSenderForResult）；backend 由工厂注入
 * （生产 = DedupTrashBackend 包装 DedupTrashManager）。
 */
class OrganizeCategoryViewModel(
    val category: OrganizeCategory,
    private val organizeRepository: OrganizeRepository,
    trashBackend: TrashBackend,
    /** 测试注入作用域（避开 Dispatchers.Main）；生产为 null → viewModelScope。 */
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    val trashController = TrashSessionController(trashBackend, scope, ioDispatcher)

    private val _uiState = MutableStateFlow<OrganizeCategoryUiState>(OrganizeCategoryUiState.Loading)
    val uiState: StateFlow<OrganizeCategoryUiState> = _uiState.asStateFlow()

    /** AI 预选开关（右上角切换）：开 = 默认勾选 HIGH 置信非保护项；关 = 空选由用户手点。 */
    private val _aiPreselectEnabled = MutableStateFlow(true)
    val aiPreselectEnabled: StateFlow<Boolean> = _aiPreselectEnabled.asStateFlow()

    init {
        // outcome 结算独立于当前 UI state：授权回调只负责清 pending（controller 内同步完成），
        // 复查结果经 outcomes 流异步到达，避免「非 Ready 态提前 return 导致 pendingRequest
        // 永不清空」的陷阱
        scope.launch { trashController.outcomes.collect { outcome -> onTrashOutcome(outcome) } }
        reload(preselect = true)
    }

    private fun currentReady(): OrganizeCategoryUiState.Ready? =
        _uiState.value as? OrganizeCategoryUiState.Ready

    /** 重新拉取类目快照；preselect=true 时按当前预选开关决定初始选中集。 */
    private fun reload(preselect: Boolean) {
        scope.launch {
            val items = loadCategoryItems()
            _uiState.value = OrganizeCategoryUiState.Ready(
                items = items,
                selected = if (preselect && _aiPreselectEnabled.value) {
                    // 与三段分组同源：预选集 = suggested 段（HIGH 置信非保护）
                    items.toSections().suggested.map { entry -> entry.item.uri }.toSet()
                } else {
                    emptySet()
                },
            )
        }
    }

    private suspend fun loadCategoryItems(): List<ClassifiedItem> = withContext(ioDispatcher) {
        OrganizeCategorizer.classifyAll(organizeRepository.loadItems(), now = System.currentTimeMillis())
            .filter { entry -> entry.category == category }
    }

    fun toggle(uri: String) {
        val ready = currentReady() ?: return
        if (ready.trashed) return
        _uiState.value = ready.copy(
            selected = if (uri in ready.selected) ready.selected - uri else ready.selected + uri
        )
    }

    /** 全选（用户显式行为）：仅圈选非保护项——protected「永不预选」含显式全选（spec §6.3）。 */
    fun selectAll() {
        val ready = currentReady() ?: return
        if (ready.trashed) return
        _uiState.value = ready.copy(
            selected = ready.items.filter { entry -> !entry.isProtected }
                .map { entry -> entry.item.uri }.toSet()
        )
    }

    fun deselectAll() {
        val ready = currentReady() ?: return
        if (ready.trashed) return
        _uiState.value = ready.copy(selected = emptySet())
    }

    /** 预选开关：开 = 立即勾选 HIGH 置信非保护项；关 = 立即清空（「AI 预选」语义对称，手选项需重新点选）。 */
    fun setAiPreselect(enabled: Boolean) {
        _aiPreselectEnabled.value = enabled
        val ready = currentReady() ?: return
        if (ready.trashed) return
        _uiState.value = ready.copy(
            selected = if (enabled) {
                // 与三段分组同源：预选集 = suggested 段（HIGH 置信非保护）
                ready.items.toSections().suggested.map { entry -> entry.item.uri }.toSet()
            } else {
                emptySet()
            }
        )
    }

    fun deleteSelected() {
        val ready = currentReady() ?: return
        if (ready.trashed || ready.selected.isEmpty()) return
        Logger.d(TAG, "request trash: category=$category count=${ready.selected.size}")
        trashController.requestTrash(ready.selected.toList(), tag = category.name)
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

    /**
     * outcome 结算（不论到达时 state 是否 Ready 都先结算）：
     * Trashed → 剔除实际已回收项，清空则进完成态；Restored → 重载类目回非完成态；
     * Cancelled / Unsupported → 不动。Loading 态到达的 Trashed 理论不可达
     * （deleteSelected 需 Ready），丢弃并记日志——init 的 reload 会带出最新库快照。
     */
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
                Logger.d(TAG, "restored ${outcome.restoredUris.size} uris, reloading category")
                // 计数清零——项已回到库中，不算已释放
                reload(preselect = true)
            }
            // Cancelled：留在原地可重试；Unsupported（API<30）：errorEvent 链路已弹 snackbar，
            // VM 无在途提交状态需回滚（deleteSelected 不挂前置标记），行为同改动前
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "PoLang:Organize"
    }
}
