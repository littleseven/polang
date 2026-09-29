package com.mamba.picme.domain.person

import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.dao.PersonRelationDao
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.PersonRelationEntity
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 人物领域仓库 —— 人物命名、"我"标记、人物关系图谱的唯一收口
 *
 * 命名对话框与聊天声明工具都必须走这里，不直调 DAO。
 * 关系声明幂等：同一对人物重复声明即覆盖（纠错通路 = 重新声明）。
 */
class PersonRepository(
    private val personDao: PersonDao,
    private val relationDao: PersonRelationDao,
    private val mediaDao: MediaDao
) {

    /** 关系声明结果（枚举所有分支，调用方穷举处理） */
    sealed interface DeclareRelationResult {
        /** 声明成功（含覆盖旧关系），relation 为落库后的实体 */
        data class Declared(val relation: PersonRelationEntity) : DeclareRelationResult

        /** 尚未标记"我"本人，无法建立指向我的关系 */
        data object SelfNotDeclared : DeclareRelationResult

        /** subject 人物不存在 */
        data object SubjectNotFound : DeclareRelationResult
    }

    suspend fun renamePerson(personId: Long, name: String) {
        personDao.updatePersonName(personId, name)
    }

    /** 标记某人物为"我"（全局唯一，自动清除旧标记） */
    suspend fun setSelf(personId: Long) {
        personDao.clearSelfFlags()
        personDao.setSelf(personId, true)
    }

    suspend fun clearSelf() {
        personDao.clearSelfFlags()
    }

    suspend fun getSelfPerson(): PersonEntity? = personDao.getSelfPerson()

    /**
     * 设置页账户头像数据源：观察"我"本人，解析封面媒体 uri + 人脸纵向聚焦点。
     *
     * 未标记"我"、无封面或封面媒体已删时发 null（调用方回退默认图标）。
     * persons 表变更（标记/取消、封面更新、重聚恢复）自动重发。
     */
    fun observeSelfAvatar(): Flow<SelfAvatar?> =
        personDao.observeSelfPerson()
            // persons 表级失效会随无关人物变更（改名/聚类统计）重发；
            // self 行内容未变时跳过 map，避免对 media_assets 的重复点查
            .distinctUntilChanged()
            .map { person ->
                val coverId = person?.coverMediaId ?: return@map null
                val media = mediaDao.getMediaById(coverId) ?: return@map null
                SelfAvatar(coverUri = media.uri, faceFocusY = media.faceFocusY)
            }

    /**
     * 人物编辑收口（相册重命名对话框与人物页共用）：
     * 1) name 非空 → 改名；
     * 2) isSelf → 设为"我"，否则若当前是"我"则清除；
     * 3) relation != null → 声明（覆盖）；relation == null → 清除该人物所有关系。
     * 自定义称呼非空时 predicate 应为 [RelationPredicate.OTHER]（由调用方决定）。
     *
     * 返回声明结果（relation == null 走清除路径时返回 null）；
     * 调用方必须处理 [DeclareRelationResult.SelfNotDeclared]——未标记"我"时声明会被拒绝，
     * 吞掉结果就是"修改关系不生效"静默 bug。
     */
    suspend fun applyPersonEdit(
        personId: Long,
        name: String,
        relation: RelationPredicate?,
        customLabel: String,
        isSelf: Boolean
    ): DeclareRelationResult? {
        if (name.isNotBlank()) {
            renamePerson(personId, name)
        }
        if (isSelf) {
            setSelf(personId)
        } else if (getSelfPerson()?.personId == personId) {
            clearSelf()
        }
        return if (relation != null) {
            declareRelation(
                subjectPersonId = personId,
                predicate = relation,
                source = RelationSource.RENAME_DIALOG,
                customLabel = customLabel.ifEmpty { null }
            )
        } else {
            removeAllRelationsOf(personId)
            null
        }
    }

    /** 更新人物封面（收口，供 UI 层调用） */
    suspend fun updateCover(personId: Long, mediaId: Long) {
        personDao.updateCoverMedia(personId, mediaId)
    }

    /**
     * 声明"subject 是我的 predicate"（如：小宝 是我的 女儿）。
     *
     * 幂等覆盖：同一对人物已存在任意旧关系时先删除再写入新关系（customLabel 同步覆盖）。
     *
     * @param customLabel 用户自由输入的称呼（如"发小""二儿子"），空白归一为 null
     */
    suspend fun declareRelation(
        subjectPersonId: Long,
        predicate: RelationPredicate,
        source: RelationSource,
        customLabel: String? = null
    ): DeclareRelationResult {
        val subject = personDao.getPerson(subjectPersonId)
            ?: return DeclareRelationResult.SubjectNotFound
        val self = personDao.getSelfPerson()
            ?: return DeclareRelationResult.SelfNotDeclared

        relationDao.deleteByPair(subjectPersonId = subject.personId, objectPersonId = self.personId)
        val relation = PersonRelationEntity(
            subjectPersonId = subject.personId,
            objectPersonId = self.personId,
            predicate = predicate.name,
            source = source.name,
            customLabel = customLabel?.trim()?.ifEmpty { null }
        )
        val relationId = relationDao.upsert(relation)
        return DeclareRelationResult.Declared(relation.copy(relationId = relationId))
    }

    /**
     * 遗忘"subject 是我的 predicate"关系；返回删除条数（0 = 幂等无操作）。
     */
    suspend fun removeRelation(subjectPersonId: Long, predicate: RelationPredicate): Int {
        val self = personDao.getSelfPerson() ?: return 0
        return relationDao.deleteByPairAndPredicate(
            subjectPersonId = subjectPersonId,
            objectPersonId = self.personId,
            predicate = predicate.name
        )
    }

    /** 遗忘某人物与"我"之间的全部关系；返回删除条数 */
    suspend fun removeAllRelationsOf(subjectPersonId: Long): Int {
        val self = personDao.getSelfPerson() ?: return 0
        return relationDao.deleteByPair(subjectPersonId = subjectPersonId, objectPersonId = self.personId)
    }

    /** 按名字模糊解析人物（"小宝" → 人脸簇） */
    suspend fun resolveByName(name: String): PersonEntity? = personDao.findPersonByName(name)

    /** 全部已命名人物（供 PersonQueryResolver 扫描查询串中的人名命中） */
    suspend fun getNamedPersons(): List<PersonEntity> =
        personDao.getAllPersons().filter { person -> !person.name.isNullOrBlank() }

    /** 全部人物簇（含未命名），按更新时间倒序，供人物页展示。 */
    suspend fun getAllPersons(): List<PersonEntity> =
        personDao.getAllPersons().sortedByDescending { person -> person.updatedAt }

    /**
     * 对齐 persons 表与 face_embeddings / media_assets：清孤儿、重算 faceCount、修悬空封面。
     *
     * 进入人物页前、聚类完成后、媒体删除后调用，幂等。详见 [PersonDao.reconcilePersons]。
     */
    suspend fun reconcilePersons() {
        personDao.reconcilePersons(System.currentTimeMillis())
    }

    /**
     * 列出指向"我"的关系（chat 主动读通路的同步版，实时查 DB，不依赖 Flow 快照）。
     *
     * 规避 [observeRelationsToSelf] 的 Flow invalidation 延迟——声明关系后 snapshot 可能数分钟才更新，
     * 用户在此期间查询会读到空。本方法每次现查 DB，供 chat 主动读工具直接调用。
     *
     * [name] 非空时只返回该名字人物与"我"的关系（声明幂等，至多 1 条）；null/空返回全部。
     */
    suspend fun listRelationsToSelf(name: String? = null): List<RelationDisplayItem> {
        val self = personDao.getSelfPerson() ?: return emptyList()
        val personsById = personDao.getAllPersons().associateBy { person -> person.personId }
        val nameFilter = name?.trim()?.ifEmpty { null }
        return relationDao.getAll()
            .asSequence()
            .filter { relation -> relation.objectPersonId == self.personId }
            .filter { relation ->
                nameFilter == null || personsById[relation.subjectPersonId]?.name == nameFilter
            }
            .mapNotNull { relation ->
                val subject = personsById[relation.subjectPersonId] ?: return@mapNotNull null
                val predicate = RelationPredicate.fromStored(relation.predicate) ?: return@mapNotNull null
                RelationDisplayItem(
                    relationId = relation.relationId,
                    subjectPersonId = subject.personId,
                    subjectName = subject.name ?: "#${subject.personId}",
                    predicate = predicate,
                    customLabel = relation.customLabel?.trim()?.ifEmpty { null }
                )
            }
            .toList()
    }

    /** 查询某人物当前与"我"的关系（对话框回显用） */
    suspend fun getRelationToSelf(subjectPersonId: Long): PersonRelationEntity? {
        val self = personDao.getSelfPerson() ?: return null
        return relationDao.getBySubject(subjectPersonId)
            .firstOrNull { relation -> relation.objectPersonId == self.personId }
    }

    /**
     * 加载某人物信息编辑页所需快照：实体 + 与"我"的关系 + 封面媒体 + 封面候选照片。
     * 人物不存在返回 null。封面从封面候选照片里按 coverMediaId 解析（coverMediaId
     * 失效/媒体已删时为 null，由调用方兜底渲染）。
     */
    suspend fun loadPersonEditSnapshot(personId: Long): PersonEditSnapshot? {
        val person = personDao.getPerson(personId) ?: return null
        val relation = RelationDisplayItem.from(person, getRelationToSelf(personId))
        val photos = personDao.getMediaByPersonOrderedForCover(personId).distinctBy { it.id }
        val coverMedia = person.coverMediaId?.let { cid -> photos.firstOrNull { it.id == cid } }
        return PersonEditSnapshot(person, relation, coverMedia, photos)
    }

    /**
     * 按亲属称谓解析人物集合（"我女儿" → 谓词族 {DAUGHTER, CHILD} 指向我的人物）。
     * 查询按谓词族扩展：具体称谓含同族未指定桶，泛化称谓含整族；
     * 一个称谓可能命中多条关系（多个孩子），返回并集由调用方决定是否歧义。
     */
    suspend fun resolveByKinship(term: String): List<PersonEntity> {
        val predicates = KinshipLexicon.queryPredicatesFor(term) ?: return emptyList()
        val self = personDao.getSelfPerson() ?: return emptyList()
        val relations = relationDao.getByObjectAndPredicates(
            objectPersonId = self.personId,
            predicates = predicates.map { predicate -> predicate.name }
        )
        return relations.mapNotNull { relation -> personDao.getPerson(relation.subjectPersonId) }
    }

    /**
     * 自定义称呼匹配（查询解析最高优先级）：query 包含某条指向"我"的关系的 customLabel
     * （如"二儿子""发小"）→ 精确命中对应人物。按称呼长度降序返回（优先长匹配）。
     */
    suspend fun resolveByCustomLabels(query: String): List<CustomLabelHit> {
        val self = personDao.getSelfPerson() ?: return emptyList()
        return relationDao.getByObjectWithCustomLabel(self.personId)
            .mapNotNull { relation ->
                val label = relation.customLabel?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                if (!query.contains(label)) return@mapNotNull null
                val person = personDao.getPerson(relation.subjectPersonId) ?: return@mapNotNull null
                CustomLabelHit(label = label, person = person)
            }
            .sortedByDescending { hit -> hit.label.length }
    }

    /**
     * 单条更新关系（「AI 记忆」页编辑入口）：只改谓词与自定义称呼，
     * 保留 source / createdAt，刷新 updatedAt。返回是否命中。
     */
    suspend fun updateRelation(
        relationId: Long,
        predicate: RelationPredicate,
        customLabel: String?
    ): Boolean {
        return relationDao.updateRelation(
            relationId = relationId,
            predicate = predicate.name,
            customLabel = customLabel?.trim()?.ifEmpty { null },
            updatedAt = System.currentTimeMillis()
        ) > 0
    }

    /** 快照导出（Pass 2 重聚前调用，按 personId 原样导出，由调度器翻译成名字） */
    suspend fun exportAllRelations(): List<PersonRelationEntity> = relationDao.getAll()

    /** 快照恢复（重聚后按新 personId 批量写回） */
    suspend fun restoreRelations(relations: List<PersonRelationEntity>) {
        if (relations.isNotEmpty()) {
            relationDao.upsertAll(relations)
        }
    }

    /** 按 relationId 遗忘一条关系（管理界面单条删除）；返回是否命中 */
    suspend fun removeRelationById(relationId: Long): Boolean {
        return relationDao.deleteById(relationId) > 0
    }

    /**
     * 「AI 记忆」页人物关系列表驱动源：指向"我"的关系，联 persons 取名字。
     * 人物/关系表变更时重算（预取 persons map 内存 join，v1 关系量极小）。
     */
    fun observeRelationsToSelf(): Flow<List<RelationDisplayItem>> =
        relationDao.observeAll().map { relations ->
            if (relations.isEmpty()) return@map emptyList()
            val personsById = personDao.getAllPersons().associateBy { person -> person.personId }
            relations.mapNotNull { relation ->
                val subject = personsById[relation.subjectPersonId] ?: return@mapNotNull null
                val obj = personsById[relation.objectPersonId] ?: return@mapNotNull null
                if (!obj.isSelf) return@mapNotNull null
                val predicate = RelationPredicate.fromStored(relation.predicate)
                    ?: return@mapNotNull null
                RelationDisplayItem(
                    relationId = relation.relationId,
                    subjectPersonId = subject.personId,
                    subjectName = subject.name ?: "#${subject.personId}",
                    predicate = predicate,
                    customLabel = relation.customLabel?.trim()?.ifEmpty { null }
                )
            }
        }
}

/**
 * 自定义称呼命中项：[resolveByCustomLabels][PersonRepository.resolveByCustomLabels] 的返回元素
 */
data class CustomLabelHit(
    val label: String,
    val person: PersonEntity
)

/** "我"的账户头像：人物封面 uri + 人脸纵向聚焦点（供 faceAwareVerticalAlignment）。 */
data class SelfAvatar(
    val coverUri: String,
    val faceFocusY: Float?
)

/**
 * 人物关系展示项（「AI 记忆」页用）："X 是我的 Y"
 *
 * @property subjectName 人名（subject 端，未命名退化为 "#personId"）
 * @property predicate 关系谓词（本地化标签由 UI 层映射）
 * @property customLabel 自定义称呼；非空时 UI 优先展示它而非谓词标签
 */
data class RelationDisplayItem(
    val relationId: Long,
    val subjectPersonId: Long,
    val subjectName: String,
    val predicate: RelationPredicate,
    val customLabel: String? = null
) {
    companion object {
        /**
         * PersonRelationEntity → RelationDisplayItem。
         * 未知谓词或空关系返回 null；customLabel 空白归一为 null、非空则 trim。
         */
        fun from(person: PersonEntity, relation: PersonRelationEntity?): RelationDisplayItem? {
            if (relation == null) return null
            val predicate = RelationPredicate.fromStored(relation.predicate) ?: return null
            return RelationDisplayItem(
                relationId = relation.relationId,
                subjectPersonId = person.personId,
                subjectName = person.name ?: "#${person.personId}",
                predicate = predicate,
                customLabel = relation.customLabel?.trim()?.ifEmpty { null }
            )
        }
    }
}

/**
 * 人物信息编辑页所需数据快照（相册分组与人物页共用加载入口）。
 *
 * [coverMedia] 从 [photos] 中按 person.coverMediaId 解析；coverMediaId 失效时为 null。
 */
data class PersonEditSnapshot(
    val person: PersonEntity,
    val relation: RelationDisplayItem?,
    val coverMedia: MediaEntity?,
    val photos: List<MediaEntity>
)
