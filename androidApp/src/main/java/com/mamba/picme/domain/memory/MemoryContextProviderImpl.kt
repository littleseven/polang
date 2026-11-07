package com.mamba.picme.domain.memory

import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.entity.MemoryFactEntity
import com.mamba.picme.data.repository.PersonRepository
import com.mamba.picme.data.repository.RelationDisplayItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [MemoryContextProvider] 的 app 层实现：合并 [MemoryRepository.observeAllFacts] +
 * [PersonRepository.observeRelationsToSelf]，经 [formatMemoryContextFromEntities] 格式化后
 * 缓存到 @Volatile 字段。Flow 收集异常 fail-open（保留上次有效值或 ""）。
 *
 * **线程模型**：收集在 [scope]（app 应用级作用域）跑；[snapshot] 在 langchain4j AiServices
 * 线程读。`@Volatile` 单引用原子替换，无竞态。
 */
class MemoryContextProviderImpl(
    private val memoryRepository: MemoryRepository,
    private val personRepository: PersonRepository,
    scope: CoroutineScope
) : MemoryContextProvider {

    private val tag = "PoLang:MemoryProvider"

    @Volatile
    private var cached: String = ""

    init {
        scope.launch {
            combine(
                memoryRepository.observeAllFacts(),
                personRepository.observeRelationsToSelf()
            ) { facts, relations -> formatMemoryContextFromEntities(facts, relations) }
                .catch { cause -> Logger.w(tag, "snapshot flow failed, keep last cached", cause) }
                .collect { cached = it }
        }
    }

    override fun snapshot(): String = cached

    companion object {
        /**
         * 实体 → 快照文本的纯映射（internal，可纯 JVM 单测）。无 Android/Room 运行时依赖——
         * 入参是普通 data class，测试中直接构造即可。customLabel 非空优先，否则用谓词的中文称谓。
         */
        internal fun formatMemoryContextFromEntities(
            facts: List<MemoryFactEntity>,
            relations: List<RelationDisplayItem>
        ): String = formatMemoryContext(
            relations = relations.map {
                RelationLine(it.subjectName, it.customLabel ?: it.predicate.labelZh)
            },
            facts = facts.map { FactLine(it.content, it.category, it.createdAt) }
        )
    }
}
