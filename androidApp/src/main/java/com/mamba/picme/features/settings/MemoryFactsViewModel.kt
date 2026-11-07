package com.mamba.picme.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.entity.MemoryFactEntity
import com.mamba.picme.data.repository.PersonRepository
import com.mamba.picme.data.repository.RelationDisplayItem
import com.mamba.picme.domain.memory.MemoryRepository
import com.mamba.picme.domain.person.RelationPredicate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「AI 记忆」管理页 ViewModel —— 人物关系 + 事实记忆的查看/编辑/删除/清空。
 *
 * 两个列表均由 Room Flow 驱动，DB 变更自动刷新；
 * 写操作全部收口到 [MemoryRepository] / [PersonRepository]（构造注入，显式依赖）。
 */
class MemoryFactsViewModel(
    private val memoryRepository: MemoryRepository,
    private val personRepository: PersonRepository
) : ViewModel() {

    val facts: StateFlow<List<MemoryFactEntity>> =
        memoryRepository.observeAllFacts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val relations: StateFlow<List<RelationDisplayItem>> =
        personRepository.observeRelationsToSelf()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeRelation(relationId: Long) {
        viewModelScope.launch {
            personRepository.removeRelationById(relationId)
        }
    }

    /** 编辑单条关系：只改谓词/自定义称呼，保留 source（见 [PersonRepository.updateRelation]） */
    fun updateRelation(relationId: Long, predicate: RelationPredicate, customLabel: String?) {
        viewModelScope.launch {
            personRepository.updateRelation(
                relationId = relationId,
                predicate = predicate,
                customLabel = customLabel
            )
        }
    }

    fun updateFact(factId: Long, content: String, category: String?) {
        viewModelScope.launch {
            memoryRepository.updateFact(
                factId = factId,
                content = content,
                category = category?.trim()?.ifEmpty { null }
            )
        }
    }

    fun forgetFact(factId: Long) {
        viewModelScope.launch {
            memoryRepository.forgetFact(factId)
        }
    }

    fun clearAllFacts() {
        viewModelScope.launch {
            memoryRepository.clearAllFacts()
        }
    }

    companion object {
        /** 手动 DI 工厂（与 AppContainer 手动注入模式一致） */
        fun factory(
            memoryRepository: MemoryRepository,
            personRepository: PersonRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(MemoryFactsViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return MemoryFactsViewModel(memoryRepository, personRepository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
    }
}
