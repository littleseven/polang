package com.mamba.picme.features.gallery.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.memories.MemoriesGenerator
import com.mamba.picme.domain.memories.Memory
import com.mamba.picme.domain.memories.MemoryHiddenStore
import com.mamba.picme.domain.memories.MemoryInput
import com.mamba.picme.domain.memories.NamedPerson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页「回忆」carousel VM（F3）：combine 媒体流 + 人物流 + 隐藏集 → [MemoriesGenerator] 纯函数。
 *
 * - 生成链路不含隐藏过滤（[allGenerated] 为未过滤全集）：隐藏只作用于 [memories] 展示层，
 *   [getMemory] 基于全集查询，保证详情页直达/刷新后 id 稳定可复原（含已隐藏条目）。
 * - 生成计算在 [ioDispatcher] 执行；人物映射只取已命名人物（未命名不参与人物回忆）。
 */
class MemoriesViewModel(
    private val mediaDao: MediaDao,
    private val personDao: PersonDao,
    private val hiddenStore: MemoryHiddenStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 测试注入作用域（避开 Dispatchers.Main）；生产为 null → viewModelScope。 */
    coroutineScope: CoroutineScope? = null,
    /** 时间源（测试注入固定时钟，生成器 now 确定性）。 */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    /** 未过滤全集生成结果（隐藏过滤不进 generate，详情页直达可查已隐藏条目）。 */
    private val allGenerated: StateFlow<List<Memory>> =
        combine(mediaDao.getAllMedia(), personDao.observeAll()) { media, persons ->
            MemoriesGenerator.generate(
                inputs = media.mapNotNull { entity -> entity.toMemoryInput() },
                persons = persons.mapNotNull { entity -> entity.toNamedPerson() },
                now = nowMs(),
            )
        }
            .flowOn(ioDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** carousel 展示集：全集中剔除已隐藏 id。 */
    val memories: StateFlow<List<Memory>> =
        combine(allGenerated, hiddenStore.ids) { generated, hidden ->
            generated.filterNot { memory -> memory.id in hidden }
        }
            .flowOn(ioDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 隐藏持久化失败一次性标志（UI 提示用，消费经 [consumeHideError]；仿 TrashSessionController.errorEvent 模式）。 */
    private val _hideError = MutableStateFlow(false)

    val hideError: StateFlow<Boolean> = _hideError

    /** 隐藏一条回忆（幂等持久化；展示层经 [memories] 重发自动消失）。DataStore 写入失败置位 [hideError]，不崩溃。 */
    fun hideMemory(id: String) {
        scope.launch(ioDispatcher) {
            runCatching { hiddenStore.hide(id) }
                .onSuccess { Logger.d(TAG, "memory hidden: $id") }
                .onFailure { error ->
                    _hideError.value = true
                    Logger.e(TAG, "hide memory failed: $id", error)
                }
        }
    }

    /** 消费（清除）隐藏失败标志。 */
    fun consumeHideError() {
        _hideError.value = false
    }

    /** 按 id 查回忆（未过滤全集：已隐藏条目仍可查，供详情页直达复原）。 */
    fun getMemory(id: String): Memory? = allGenerated.value.firstOrNull { memory -> memory.id == id }

    /** 按 id 观察单条回忆（未过滤全集 Flow：详情页随媒体库变化重组复原，列表刷新不 stale、冷恢复不依赖列表 remember 反查）。 */
    fun observeMemory(id: String): Flow<Memory?> =
        allGenerated
            .map { generated -> generated.firstOrNull { memory -> memory.id == id } }
            .distinctUntilChanged()

    /** 仅照片参与回忆；personId = 媒体 faceId（人物聚类归属）。 */
    private fun MediaEntity.toMemoryInput(): MemoryInput? {
        if (type != MediaType.PHOTO) return null
        return MemoryInput(
            uri = uri,
            captureDate = captureDate,
            aestheticScore = aestheticScore,
            city = city,
            personId = faceId,
        )
    }

    /** 只取已命名人物（未命名人物不进回忆）；personId 转 String 与 faceId 对齐。 */
    private fun PersonEntity.toNamedPerson(): NamedPerson? {
        val personName = name?.takeIf { value -> value.isNotBlank() } ?: return null
        return NamedPerson(personId = personId.toString(), name = personName, isSelf = isSelf)
    }

    private companion object {
        const val TAG = "PoLang:Memories"
    }
}
