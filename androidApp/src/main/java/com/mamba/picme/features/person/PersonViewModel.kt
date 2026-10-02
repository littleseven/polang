package com.mamba.picme.features.person

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.domain.tag.FaceClusterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 人物编辑保存被拒的类型化原因（UI 映射为本地化文案，不透传裸异常）。 */
enum class PersonSaveError {
    /** 未标记"我"本人就声明关系——声明被拒，需引导先打开「这是我」。 */
    SELF_NOT_DECLARED,

    /** 目标人物已不存在（如重聚类后被合并）。 */
    SUBJECT_NOT_FOUND
}

/**
 * 「人物」页 ViewModel：全部人脸聚类列表 + 每个聚类的封面 + 指向"我"的关系。
 *
 * 封面用 [PersonCoverResolver] 纯映射（可单测）；编辑走 [PersonRepository] 收口。
 */
class PersonViewModel(
    private val personRepository: PersonRepository,
    private val db: AppDatabase,
    private val faceClusterEngine: FaceClusterEngine
) : ViewModel() {

    private val _persons = MutableStateFlow<List<PersonEntity>>(emptyList())
    val persons: StateFlow<List<PersonEntity>> = _persons.asStateFlow()

    private val _showAll = MutableStateFlow(false)
    val showAll: StateFlow<Boolean> = _showAll.asStateFlow()

    private val _totalPersonCount = MutableStateFlow(0)
    val totalPersonCount: StateFlow<Int> = _totalPersonCount.asStateFlow()

    private val _covers = MutableStateFlow<Map<Long, PersonCover>>(emptyMap())
    val covers: StateFlow<Map<Long, PersonCover>> = _covers.asStateFlow()

    private val _relations = MutableStateFlow<Map<Long, RelationDisplayItem?>>(emptyMap())
    val relations: StateFlow<Map<Long, RelationDisplayItem?>> = _relations.asStateFlow()

    private val _photoCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val photoCounts: StateFlow<Map<Long, Int>> = _photoCounts.asStateFlow()

    private val _editingPersonId = MutableStateFlow<Long?>(null)
    val editingPersonId: StateFlow<Long?> = _editingPersonId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _saveError = MutableStateFlow<PersonSaveError?>(null)
    val saveError: StateFlow<PersonSaveError?> = _saveError.asStateFlow()

    /** 加载全部人物簇并解析封面与关系。 */
    fun load() {
        viewModelScope.launch {
            val all = personRepository.getAllPersons()
            val ids = all.mapNotNull { person -> person.coverMediaId }.distinct()
            val resolved = withContext(Dispatchers.IO) {
                if (ids.isEmpty()) {
                    emptyMap()
                } else {
                    val media = db.mediaDao().getMediaByIds(ids)
                    PersonCoverResolver.resolve(
                        all,
                        media.associate { entity -> entity.id to entity.uri },
                        media.associate { entity -> entity.id to entity.faceFocusY }
                    )
                }
            }
            val relationMap = withContext(Dispatchers.IO) {
                all.associate { person ->
                    val relation = personRepository.getRelationToSelf(person.personId)
                    person.personId to RelationDisplayItem.from(person, relation)
                }
            }
            val photoCountMap = withContext(Dispatchers.IO) {
                val distinctCounts = db.personDao().getDistinctMediaCounts().associate { it.personId to it.count }
                // 命名人物改用「聚类 ∪ 标签提及」并集计数，与点开后的详情列表
                // （GalleryScreen.applyPersonFilter）共用同一口径（PersonDao.getPersonMediaIds），
                // 保证外显张数与详情张数一致。纯 SQL 轻量统计，不走搜索引擎：
                // 旧实现对每个命名人物并发全量 search()，会并行拖入 MobileCLIP/OPUS-MT
                // 初始化与全库候选加载，在 256MB 堆上引发 OOM 与主线程卡顿（2026-08-01 事故）。
                val namedPersons = all.filter { !it.name.isNullOrBlank() }
                distinctCounts.toMutableMap().apply {
                    namedPersons.forEach { person ->
                        this[person.personId] = db.personDao()
                            .getPersonMediaIds(person.personId, person.name!!)
                            .size
                    }
                }
            }
            _covers.value = resolved
            _relations.value = relationMap
            _photoCounts.value = photoCountMap
            _totalPersonCount.value = all.size

            // 默认隐藏「未命名且只有 1 张人脸」的单人碎片，减少主界面噪音；
            // 用户可一键切换显示全部。
            val coverable = PersonCoverResolver.filterCoverable(all, resolved)
            _persons.value = if (_showAll.value) {
                coverable.sortedForDisplay(relationMap, photoCountMap)
            } else {
                coverable.filter { person ->
                    !person.name.isNullOrBlank() || (photoCountMap[person.personId] ?: person.faceCount) >= 2
                }.sortedForDisplay(relationMap, photoCountMap)
            }
        }
    }

    /** 切换「显示全部 / 隐藏单张未命名单人分组」。切换后自动重新加载。 */
    fun toggleShowAll() {
        _showAll.value = !_showAll.value
        load()
    }

    /**
     * 进入人物页：**先出列表再愈合**（2026-10-02 空白页修复）。
     * 原序「对齐→聚类维护→load」在大碎簇集（694 人物）上维护耗时分钟级，
     * 列表被堵死=整页空白。现改为 load() 先渲染，对齐/拆分/合并转后台，
     * 愈合完成后自动刷新；单飞闸防重复进页叠加跑（维护幂等，中断无害）。
     */
    private val maintenanceInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun reconcileAndLoad() {
        load()
        if (!maintenanceInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                personRepository.reconcilePersons()
                // 聚类维护：拆分（两个不同的人被并成一组）+ 合并（同一人被拆成多组）。
                // 失败不阻断人物页加载。
                withContext(Dispatchers.IO) {
                    runCatching { faceClusterEngine.runClusterMaintenance() }
                        .onFailure { Log.w("PersonViewModel", "runClusterMaintenance failed", it) }
                }
                load()
            } finally {
                maintenanceInFlight.set(false)
            }
        }
    }

    fun startEditing(personId: Long) {
        _editingPersonId.value = personId
    }

    fun stopEditing() {
        _editingPersonId.value = null
    }

    /** 行内改名保存。 */
    fun updateName(personId: Long, name: String) {
        viewModelScope.launch {
            try {
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    personRepository.renamePerson(personId, trimmed)
                }
                stopEditing()
                load()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    /** 更新封面。 */
    fun updateCover(personId: Long, mediaId: Long) {
        viewModelScope.launch {
            try {
                personRepository.updateCover(personId, mediaId)
                load()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    /**
     * 更新人物信息（姓名/关系/自定义称呼/"我"标记）。
     *
     * name 由编辑页随保存一并提交（单次写入 [PersonRepository.applyPersonEdit]），
     * 不再从 [_persons] 取旧名快照——那是"改名被回退"竞态的来源。
     * 声明被拒（未标记"我"）时透传 [PersonSaveError]，由 UI 引导用户。
     */
    fun updatePersonInfo(
        personId: Long,
        name: String,
        relation: RelationPredicate?,
        customLabel: String,
        isSelf: Boolean
    ) {
        viewModelScope.launch {
            try {
                val result = personRepository.applyPersonEdit(personId, name, relation, customLabel, isSelf)
                _saveError.value = when (result) {
                    is PersonRepository.DeclareRelationResult.SelfNotDeclared ->
                        PersonSaveError.SELF_NOT_DECLARED
                    is PersonRepository.DeclareRelationResult.SubjectNotFound ->
                        PersonSaveError.SUBJECT_NOT_FOUND
                    else -> null
                }
                load()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    /** 读取某人物关联的全部媒体（供封面选择 Sheet）。单人照优先，避免合影作封面。 */
    suspend fun loadPhotosByPerson(personId: Long): List<MediaEntity> =
        withContext(Dispatchers.IO) {
            // 防御性去重：同一人可能在同一张照片里有多个人脸 embedding，
            // 即使 DAO 已用 DISTINCT，UI 层再保一次险，避免 LazyVerticalGrid 重复 key 崩溃。
            db.personDao().getMediaByPersonOrderedForCover(personId).distinctBy { it.id }
        }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 人物页排序：
     * 1. 亲密级：我 > 恋人/配偶 > 偶像 > 亲属 > 其他社会关系 > 无关系
     * 2. 同亲密级下：已命名者优先；已命名者按照片数（faceCount）倒序，未命名者按最近更新（新照片）倒序
     * 3. 最后以 updatedAt 作兜底去稳定
     */
    private fun List<PersonEntity>.sortedForDisplay(
        relations: Map<Long, RelationDisplayItem?>,
        photoCounts: Map<Long, Int>
    ): List<PersonEntity> {
        return sortedWith(
            compareByDescending<PersonEntity> { person -> intimacyPriority(person, relations) }
                .thenByDescending { person -> !person.name.isNullOrBlank() }
                .thenByDescending { person ->
                    if (!person.name.isNullOrBlank()) {
                        (photoCounts[person.personId] ?: person.faceCount).toLong()
                    } else {
                        person.updatedAt
                    }
                }
                .thenByDescending { it.updatedAt }
        )
    }

    private fun intimacyPriority(
        person: PersonEntity,
        relations: Map<Long, RelationDisplayItem?>
    ): Int = when {
        person.isSelf -> 5
        relations[person.personId]?.predicate in ROMANTIC_PREDICATES -> 4
        relations[person.personId]?.predicate == RelationPredicate.IDOL -> 3
        relations[person.personId]?.predicate in FAMILY_PREDICATES -> 2
        relations[person.personId]?.predicate != null -> 1
        else -> 0
    }

    companion object {
        private val ROMANTIC_PREDICATES = setOf(
            RelationPredicate.PARTNER,
            RelationPredicate.SPOUSE
        )

        private val FAMILY_PREDICATES = setOf(
            RelationPredicate.CHILD,
            RelationPredicate.SON,
            RelationPredicate.DAUGHTER,
            RelationPredicate.PARENT,
            RelationPredicate.FATHER,
            RelationPredicate.MOTHER,
            RelationPredicate.SIBLING,
            RelationPredicate.ELDER_BROTHER,
            RelationPredicate.ELDER_SISTER,
            RelationPredicate.YOUNGER_BROTHER,
            RelationPredicate.YOUNGER_SISTER,
            RelationPredicate.GRANDPARENT,
            RelationPredicate.GRANDFATHER,
            RelationPredicate.GRANDMOTHER,
            RelationPredicate.GRANDCHILD,
            RelationPredicate.OTHER_FAMILY
        )

        /** ViewModelProvider.Factory：参照 MemoryFactsViewModel.factory 范式。 */
        fun factory(
            personRepository: PersonRepository,
            db: AppDatabase,
            faceClusterEngine: FaceClusterEngine
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(PersonViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return PersonViewModel(personRepository, db, faceClusterEngine) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
