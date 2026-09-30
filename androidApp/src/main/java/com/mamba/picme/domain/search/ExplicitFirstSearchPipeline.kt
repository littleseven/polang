package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.person.PersonQueryResolver
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.TagTranslator

/**
 * 显式约束优先的搜索管道
 *
 * 规则：
 * 1. 先执行显式约束（时间、地点、人脸）得到候选集；
 * 2. 候选集内再执行内容关键词匹配（带跨语言扩展）；
 * 3. 若无显式约束，直接在全局执行内容关键词搜索。
 *
 * 通过 [TagTranslator] 支持跨语言搜索扩展：
 * 中文查询 → 英文候选词（命中 Qwen/SmolVLM 生成的英文标签）
 */
class ExplicitFirstSearchPipeline(
    private val mediaDao: MediaDao,
    private val personDao: PersonDao? = null,
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty()),
    private val personQueryResolver: PersonQueryResolver? = null
) {

    /**
     * 使用已经分段的查询执行搜索
     */
    suspend fun search(
        segmentedQuery: SegmentedQuery,
        uiLang: AppLanguage = AppLanguage.CHINESE
    ): com.mamba.picme.domain.search.SearchResult {
        val (explicit, content) = QuerySegmenter.toFilters(segmentedQuery)
        return search(explicit, content, uiLang)
    }

    /**
     * 使用显式约束和内容过滤条件执行搜索
     *
     * 人物维度（缺陷③修复）：explicit.personKeywords 经 [PersonQueryResolver] 命中的人物簇
     * 作为显式收窄维度参与候选交集（此前 personKeywords 被静默丢弃，"去年夏天儿子的照片"
     * 退化为 时间∩标签搜索）；已被消费的称谓词不再驱动标签搜索。
     */
    suspend fun search(
        explicit: ExplicitFilter,
        content: ContentFilter,
        uiLang: AppLanguage = AppLanguage.CHINESE
    ): com.mamba.picme.domain.search.SearchResult {
        val personOutcome = resolvePersonOutcome(explicit.personKeywords)
        val effectiveContent = if (personOutcome == null) {
            content
        } else {
            content.copy(
                keywords = content.keywords.filterNot { keyword ->
                    personOutcome.consumedTerms.any { it.isNotBlank() && keyword.contains(it) }
                }
            )
        }
        val candidateIds = resolveCandidateIds(explicit, personOutcome?.mediaIds)
        val mediaList = if (candidateIds == null) {
            searchGlobal(effectiveContent, uiLang)
        } else {
            searchInCandidates(candidateIds, effectiveContent, uiLang)
        }
        return com.mamba.picme.domain.search.SearchResult(
            media = mediaList.map { it.toDomain(uiLang) },
            originalQuery = content.semanticQuery ?: ""
        )
    }

    /**
     * 人物维度预解析结果（簇内媒体 + 已消费称谓词）
     */
    private data class PersonOutcome(
        val mediaIds: Set<Long>,
        val consumedTerms: Set<String>
    )

    /**
     * 解析显式人物关键词：personKeywords 经 [PersonQueryResolver] 命中人物时返回其簇内媒体。
     * 解析器/人物 DAO 未注入、关键词为空、0 命中时返回 null（回落原有关键词链路）。
     */
    private suspend fun resolvePersonOutcome(personKeywords: List<String>): PersonOutcome? {
        val resolver = personQueryResolver ?: return null
        val dao = personDao ?: return null
        if (personKeywords.isEmpty()) return null

        val query = personKeywords.joinToString(" ")
        val resolved = resolver.resolve(query)
        if (resolved.personIds.isEmpty()) return null

        val mediaIds = if (resolved.personIds.size >= 2) {
            val ids = resolved.personIds.toList()
            dao.getMediaByPersonsCooccurrence(ids, ids.size)
                .mapTo(mutableSetOf()) { it.id }
        } else {
            dao.getMediaByPerson(resolved.personIds.first())
                .mapTo(mutableSetOf()) { it.id }
        }
        Logger.d(
            TAG,
            "personOutcome keywords=$personKeywords persons=${resolved.descriptions} " +
                "consumed=${resolved.matchedTerms} media=${mediaIds.size}"
        )
        return PersonOutcome(mediaIds, resolved.matchedTerms)
    }

    /**
     * 根据显式约束解析候选媒体 ID 集合；若没有任何显式约束则返回 null，表示全局搜索
     *
     * @param personMediaIds 人物维度候选集（PersonQueryResolver 命中的人物簇），参与维度间交集
     */
    private suspend fun resolveCandidateIds(
        explicit: ExplicitFilter,
        personMediaIds: Set<Long>? = null
    ): Set<Long>? {
        val candidateSets = mutableListOf<Set<Long>>()

        explicit.timeRange?.let { range ->
            candidateSets.add(
                mediaDao.getMediaIdsByTimeRange(range.startMs, range.endMs).toSet()
            )
        }

        if (explicit.locationKeywords.isNotEmpty()) {
            val locationIds = explicit.locationKeywords
                .flatMap { keyword -> mediaDao.getMediaIdsByLocationKeyword(keyword) }
                .toSet()
            candidateSets.add(locationIds)
        }

        if (explicit.hasFaces == true) {
            candidateSets.add(mediaDao.getMediaIdsByHasFace().toSet())
        }

        personMediaIds?.let { candidateSets.add(it) }

        if (candidateSets.isEmpty()) return null
        return candidateSets.reduce { acc, set -> acc.intersect(set) }
    }

    /**
     * 在候选集中执行内容关键词搜索（带跨语言扩展），返回去重后的媒体列表
     */
    private suspend fun searchInCandidates(
        candidateIds: Set<Long>,
        content: ContentFilter,
        uiLang: AppLanguage
    ): List<MediaEntity> {
        if (candidateIds.isEmpty()) return emptyList()
        if (content.isEmpty()) {
            return mediaDao.getMediaByIds(candidateIds.toList())
                .sortedByDescending { it.captureDate }
        }

        val ids = candidateIds.toList()
        val matchedIds = mutableSetOf<Long>()

        for (keyword in content.keywords) {
            searchPersonByNameCandidate(keyword, candidateIds)?.let { matchedIds.addAll(it) }

            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                matchedIds.addAll(mediaDao.searchLabelsAllFieldsInIds(ids, candidate).map { it.id })
                matchedIds.addAll(mediaDao.searchFileNameInIds(ids, candidate).map { it.id })
            }
        }

        for (keyword in content.ocrKeywords) {
            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                matchedIds.addAll(mediaDao.searchOcrInIds(ids, candidate).map { it.id })
            }
        }

        if (matchedIds.isEmpty()) return emptyList()
        return mediaDao.getMediaByIds(matchedIds.toList())
            .sortedByDescending { it.captureDate }
    }

    /**
     * 全局内容关键词搜索（无显式约束时，带跨语言扩展）
     */
    private suspend fun searchGlobal(
        content: ContentFilter,
        uiLang: AppLanguage
    ): List<MediaEntity> {
        if (content.isEmpty()) return emptyList()

        val matchedIds = mutableSetOf<Long>()
        for (keyword in content.keywords) {
            searchPersonByNameCandidate(keyword, null)?.let { matchedIds.addAll(it) }

            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                matchedIds.addAll(mediaDao.searchByLabelAllFields(candidate).map { it.id })
            }
        }
        for (keyword in content.ocrKeywords) {
            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                matchedIds.addAll(mediaDao.searchByOcrText(candidate).map { it.id })
            }
        }

        if (matchedIds.isEmpty()) return emptyList()
        return mediaDao.getMediaByIds(matchedIds.toList())
            .sortedByDescending { it.captureDate }
    }

    /**
     * 将关键词作为人物分组名称进行匹配。
     *
     * 与 [MediaSearchEngine] 保持一致：支持用户自定义的人物分组名称搜索。
     *
     * @param candidateIds 若不为 null，则返回结果与该候选集取交集
     * @return 命中人物的媒体 ID 集合；未命中或 [personDao] 未注入时返回 null
     */
    private suspend fun searchPersonByNameCandidate(
        keyword: String,
        candidateIds: Set<Long>?
    ): Set<Long>? {
        val dao = personDao ?: return null
        val person = dao.findPersonByName(keyword.trim()) ?: return null
        val media = dao.getMediaByPerson(person.personId)
        Logger.d(TAG, "keyword='$keyword' matched personId=${person.personId}, media=${media.size}")
        val ids = media.map { it.id }.toSet()
        return if (candidateIds != null) ids.intersect(candidateIds) else ids
    }

    companion object {
        private const val TAG = "ExplicitFirstSearchPipeline"
    }
}

/**
 * MediaEntity → MediaAsset 转换（精简版，用于搜索结果）
 */
private fun MediaEntity.toDomain(uiLang: AppLanguage): MediaAsset = MediaAsset(
    id = id,
    uri = uri,
    type = type,
    captureDate = captureDate,
    fileName = fileName,
    duration = duration,
    hasFace = hasFace,
    faceId = faceId,
    faceFocusY = faceFocusY,
    source = source,
    labels = labelsForLanguage(uiLang),
    ocrText = ocrText,
    latitude = latitude,
    longitude = longitude,
    locationName = locationName,
    city = city,
    indexedAt = indexedAt
)
