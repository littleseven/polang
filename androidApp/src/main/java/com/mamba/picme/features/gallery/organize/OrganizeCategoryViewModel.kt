package com.mamba.picme.features.gallery.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.repository.OrganizeRepository
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.organize.OrganizeCategorizer
import com.mamba.picme.domain.organize.OrganizeItem
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
        val items: List<OrganizeItem>,
        /** 选中 uri 集合；AI 预选 = 默认全选该类目命中项。 */
        val selected: Set<String>,
        /** 完成态：该类目已清空（区别于「本来就空」的空态）。 */
        val trashed: Boolean = false,
        val trashedCount: Int = 0,
        val trashedBytes: Long = 0,
        /** 最近一批已回收 uri（undo 恢复对象）。 */
        val lastTrashedUris: List<String> = emptyList(),
    ) : OrganizeCategoryUiState
}

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

    /** AI 预选开关（右上角切换）：开 = 进入时全选该类目命中项；关 = 空选由用户手点。 */
    private val _aiPreselectEnabled = MutableStateFlow(true)
    val aiPreselectEnabled: StateFlow<Boolean> = _aiPreselectEnabled.asStateFlow()

    init {
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
                    items.map { item -> item.uri }.toSet()
                } else {
                    emptySet()
                },
            )
        }
    }

    private suspend fun loadCategoryItems(): List<OrganizeItem> = withContext(ioDispatcher) {
        organizeRepository.loadItems()
            .filter { item -> category in OrganizeCategorizer.categoriesOf(item) }
    }

    fun toggle(uri: String) {
        val ready = currentReady() ?: return
        if (ready.trashed) return
        _uiState.value = ready.copy(
            selected = if (uri in ready.selected) ready.selected - uri else ready.selected + uri
        )
    }

    fun selectAll() {
        val ready = currentReady() ?: return
        if (ready.trashed) return
        _uiState.value = ready.copy(selected = ready.items.map { item -> item.uri }.toSet())
    }

    fun deselectAll() {
        val ready = currentReady() ?: return
        if (ready.trashed) return
        _uiState.value = ready.copy(selected = emptySet())
    }

    /** 预选开关：开 = 立即全选；关 = 立即清空（「AI 预选」语义对称，手选项需重新点选）。 */
    fun setAiPreselect(enabled: Boolean) {
        _aiPreselectEnabled.value = enabled
        val ready = currentReady() ?: return
        if (ready.trashed) return
        _uiState.value = ready.copy(
            selected = if (enabled) ready.items.map { item -> item.uri }.toSet() else emptySet()
        )
    }

    fun deleteSelected() {
        val ready = currentReady() ?: return
        if (ready.trashed || ready.selected.isEmpty()) return
        Logger.d(TAG, "request trash: category=$category count=${ready.selected.size}")
        trashController.requestTrash(ready.selected.toList(), tag = category.name)
    }

    /** UI 授权回调：ok=false → Cancelled 不变；ok=true → 剔除实际已回收项，清空则进完成态。 */
    fun onTrashResult(ok: Boolean) {
        val ready = currentReady() ?: return
        when (val outcome = trashController.onTrashResult(ok)) {
            is TrashOutcome.Trashed -> {
                val trashedSet = outcome.trashedUris.toSet()
                val bytes = ready.items
                    .filter { item -> item.uri in trashedSet }
                    .sumOf { item -> item.sizeBytes }
                val remaining = ready.items.filterNot { item -> item.uri in trashedSet }
                _uiState.value = ready.copy(
                    items = remaining,
                    selected = ready.selected - trashedSet,
                    trashed = remaining.isEmpty(),
                    trashedCount = ready.trashedCount + outcome.trashedUris.size,
                    trashedBytes = ready.trashedBytes + bytes,
                    lastTrashedUris = outcome.trashedUris,
                )
                Logger.d(TAG, "trashed ${outcome.trashedUris.size} items, remaining=${remaining.size}")
            }
            // Cancelled / Unsupported / Restored（恢复走 onRestoreResult）均不改选中态
            else -> Unit
        }
    }

    fun undoLastTrash() {
        val ready = currentReady() ?: return
        if (ready.lastTrashedUris.isEmpty()) return
        Logger.d(TAG, "request restore: ${ready.lastTrashedUris.size} uris")
        trashController.requestRestore(ready.lastTrashedUris)
    }

    /** 恢复授权回调：成功后重载类目，回到非完成态（计数清零——项已回到库中，不算已释放）。 */
    fun onRestoreResult(ok: Boolean) {
        val outcome = trashController.onRestoreResult(ok)
        if (outcome is TrashOutcome.Restored) {
            Logger.d(TAG, "restored ${outcome.restoredUris.size} uris, reloading category")
            reload(preselect = true)
        }
    }

    private companion object {
        const val TAG = "PoLang:Organize"
    }
}
