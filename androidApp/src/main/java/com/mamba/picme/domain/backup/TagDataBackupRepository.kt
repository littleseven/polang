package com.mamba.picme.domain.backup

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.MediaFeedbackDao
import com.mamba.picme.data.local.dao.MemoryFactDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.dao.PersonRelationDao
import com.mamba.picme.data.local.dao.PhotoEditRecipeDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.data.local.dao.TagScanTaskDao
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.LocationHierarchyEntity
import com.mamba.picme.data.local.entity.MediaFeedbackEntity
import com.mamba.picme.data.local.entity.MediaLocationEntity
import com.mamba.picme.data.local.entity.MediaTagCrossRef
import com.mamba.picme.data.local.entity.MemoryFactEntity
import com.mamba.picme.data.local.entity.OcrWordEntity
import com.mamba.picme.data.local.entity.OcrWordOccurrence
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.PersonRelationEntity
import com.mamba.picme.data.local.entity.PhotoEditRecipeEntity
import com.mamba.picme.data.local.entity.TagEntity
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.data.local.entity.TagScanTaskEntity
import com.mamba.picme.data.local.entity.TagScanTaskStatus
import com.mamba.picme.domain.backup.model.BackupChatMessage
import com.mamba.picme.domain.backup.model.BackupChatSession
import com.mamba.picme.domain.backup.model.BackupFaceEmbedding
import com.mamba.picme.domain.backup.model.BackupLocationHierarchy
import com.mamba.picme.domain.backup.model.BackupMediaFeedback
import com.mamba.picme.domain.backup.model.BackupMediaLocation
import com.mamba.picme.domain.backup.model.BackupMediaTagCrossRef
import com.mamba.picme.domain.backup.model.BackupMediaTagMetadata
import com.mamba.picme.domain.backup.model.BackupMemoryFact
import com.mamba.picme.domain.backup.model.BackupOcrWord
import com.mamba.picme.domain.backup.model.BackupOcrWordOccurrence
import com.mamba.picme.domain.backup.model.BackupPerson
import com.mamba.picme.domain.backup.model.BackupPersonRelation
import com.mamba.picme.domain.backup.model.BackupPhotoEditRecipe
import com.mamba.picme.domain.backup.model.BackupPreferenceEntry
import com.mamba.picme.domain.backup.model.BackupPreferences
import com.mamba.picme.domain.backup.model.BackupTag
import com.mamba.picme.domain.backup.model.BackupTagScanTask
import com.mamba.picme.domain.backup.model.TagDataBackup
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException

/**
 * TAG 数据库备份/还原仓库。
 *
 * 设计要点：
 * - 以 URI 为跨安装匹配键，不依赖本地自增 mediaId。
 * - 标签以 name 为唯一键重建，避免 tagId 变化导致关联失效。
 * - 人脸 Embedding / 人物聚类 / OCR 倒排索引 / 地理位置关系一并备份，
 *   避免重装后重新执行最耗时的 Pass 1~2 与 OCR。
 * - 还原时先建立 (uri → mediaId)、(oldPersonId → newPersonId)、
 *   (normalizedWord → newWordId)、(lat/lon → newLocationId) 映射，再批量写入。
 * - 扫描任务统一重置为 PENDING，由调度器根据 lastTagScanAt 去重。
 */
@Suppress("LongParameterList") // 待重构：依赖容器，考虑分组
class TagDataBackupRepository(
    private val database: AppDatabase,
    private val mediaDao: MediaDao,
    private val tagDao: TagDao,
    private val tagScanTaskDao: TagScanTaskDao,
    private val personDao: PersonDao,
    private val ocrWordDao: OcrWordDao,
    private val locationDao: LocationDao,
    private val mediaFeedbackDao: MediaFeedbackDao,
    private val chatMessageDao: ChatMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val personRelationDao: PersonRelationDao,
    private val memoryFactDao: MemoryFactDao,
    private val photoEditRecipeDao: PhotoEditRecipeDao,
    private val dataStore: DataStore<Preferences>,
    moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) {

    private val backupAdapter = moshi.adapter(TagDataBackup::class.java)

    data class ExportResult(
        val file: File,
        val tagCount: Int,
        val mediaCount: Int,
        val crossRefCount: Int,
        val scanTaskCount: Int,
        val personCount: Int,
        val faceEmbeddingCount: Int,
        val ocrWordCount: Int,
        val ocrWordOccurrenceCount: Int,
        val locationCount: Int,
        val mediaLocationCount: Int,
        val mediaFeedbackCount: Int,
        val preferenceCount: Int,
        val chatSessionCount: Int,
        val chatMessageCount: Int,
        val personRelationCount: Int,
        val memoryFactCount: Int,
        val photoEditRecipeCount: Int
    )

    data class RestoreResult(
        val matchedMediaCount: Int,
        val unmatchedUris: List<String>,
        val restoredTagCount: Int,
        val restoredCrossRefCount: Int,
        val restoredScanTaskCount: Int,
        val restoredMetadataCount: Int,
        val restoredPersonCount: Int,
        val restoredFaceEmbeddingCount: Int,
        val restoredOcrWordCount: Int,
        val restoredOcrWordOccurrenceCount: Int,
        val restoredLocationCount: Int,
        val restoredMediaLocationCount: Int,
        val restoredMediaFeedbackCount: Int,
        val restoredPreferenceCount: Int,
        val restoredChatSessionCount: Int,
        val restoredChatMessageCount: Int,
        val restoredPersonRelationCount: Int,
        val restoredMemoryFactCount: Int,
        val restoredPhotoEditRecipeCount: Int
    )

    /**
     * 导出 TAG 相关数据到 JSON 文件。
     *
     * @param file 目标文件，父目录需已存在或可创建。
     */
    suspend fun exportToFile(file: File): ExportResult = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()

        val tags = tagDao.getAllTags().map {
            BackupTag(tagId = it.tagId, name = it.name, category = it.category)
        }

        val allMedia = mediaDao.getAllMediaNow()
        val mediaIdToUri = allMedia.associate { it.id to it.uri }

        val metadataList = allMedia.mapNotNull { media ->
            if (media.isTagRelatedEmpty()) return@mapNotNull null
            BackupMediaTagMetadata(
                uri = media.uri,
                labels = media.labels,
                labelsEn = media.labelsEn,
                labelsZh = media.labelsZh,
                mlKitLabels = media.mlKitLabels,
                mlKitLabelsZh = media.mlKitLabelsZh,
                ocrText = media.ocrText,
                latitude = media.latitude,
                longitude = media.longitude,
                locationName = media.locationName,
                faceRoiResult = media.faceRoiResult,
                semanticEmbedding = media.semanticEmbedding,
                lastTagScanAt = media.lastTagScanAt,
                lastTagScanPasses = media.lastTagScanPasses,
                hasFace = media.hasFace,
                faceId = media.faceId,
                city = media.city,
                faceFocusY = media.faceFocusY,
                aestheticScore = media.aestheticScore,
                faceQualityScore = media.faceQualityScore,
                blurScore = media.blurScore,
                exposureScore = media.exposureScore,
                lastViewedAt = media.lastViewedAt
            )
        }

        val tagNameToId = tags.associate { it.tagId to it.name }
        val crossRefs = allMedia.flatMap { media ->
            tagDao.getTagsForMedia(media.id).mapNotNull { tag ->
                val tagName = tagNameToId[tag.tagId] ?: return@mapNotNull null
                BackupMediaTagCrossRef(
                    uri = media.uri,
                    tagName = tagName,
                    confidence = null
                )
            }
        }

        val scanTasks = tagScanTaskDao.getAllTasks()
            .map { it.toBackupModel(mediaIdToUri[it.mediaId] ?: "") }

        val persons = personDao.getAllPersons().map {
            BackupPerson(
                personId = it.personId,
                name = it.name,
                coverMediaUri = it.coverMediaId?.let { id -> mediaIdToUri[id] },
                faceCount = it.faceCount,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt
            )
        }

        val faceEmbeddings = personDao.getAllEmbeddings().map {
            BackupFaceEmbedding(
                mediaUri = mediaIdToUri[it.mediaId] ?: "",
                personId = it.personId,
                embedding = Base64.encodeToString(it.embedding, Base64.NO_WRAP),
                createdAt = it.createdAt
            )
        }

        val ocrWords = ocrWordDao.getAllWords().map {
            BackupOcrWord(
                wordId = it.wordId,
                word = it.word,
                normalizedWord = it.normalizedWord
            )
        }
        val wordIdToNormalized = ocrWords.associate { it.wordId to it.normalizedWord }
        val ocrOccurrences = ocrWordDao.getAllOccurrences().mapNotNull {
            val normalized = wordIdToNormalized[it.wordId] ?: return@mapNotNull null
            BackupOcrWordOccurrence(
                normalizedWord = normalized,
                mediaUri = mediaIdToUri[it.mediaId] ?: "",
                confidence = it.confidence,
                boundingBox = it.boundingBox
            )
        }

        val locations = locationDao.getAllLocations().map {
            BackupLocationHierarchy(
                locationId = it.locationId,
                country = it.country,
                province = it.province,
                city = it.city,
                district = it.district,
                poi = it.poi,
                latitude = it.latitude,
                longitude = it.longitude
            )
        }
        val locationIdToCoordinate = locations.associate {
            it.locationId to coordinateKey(it.latitude, it.longitude)
        }
        val mediaLocations = locationDao.getAllMediaLocations().mapNotNull {
            val coordinate = locationIdToCoordinate[it.locationId] ?: return@mapNotNull null
            BackupMediaLocation(
                mediaUri = mediaIdToUri[it.mediaId] ?: "",
                latitude = coordinateLatitude(coordinate),
                longitude = coordinateLongitude(coordinate),
                accuracy = it.accuracy
            )
        }

        val mediaFeedback = mediaFeedbackDao.getAll().mapNotNull { fb ->
            // media_feedback.media_id 存的是 MediaEntity.id(Long) 的字符串 → 转 uri 作跨安装键
            val uri = fb.mediaId.toLongOrNull()?.let { mediaIdToUri[it] } ?: return@mapNotNull null
            BackupMediaFeedback(
                mediaUri = uri,
                feedbackType = fb.feedbackType,
                queryText = fb.queryText,
                sessionId = fb.sessionId,
                createdAt = fb.createdAt
            )
        }

        val preferences = exportPreferences()

        // v5 新增：聊天、人物关系、事实记忆、编辑配方（无 mediaId 依赖，直接全量导出）
        val chatSessions = chatSessionDao.getAllSessionsNow().map {
            BackupChatSession(
                sessionId = it.sessionId,
                title = it.title,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt
            )
        }

        val chatMessages = chatMessageDao.getAllMessages().map {
            BackupChatMessage(
                id = it.id,
                sessionId = it.sessionId,
                type = it.type,
                content = it.content,
                timestamp = it.timestamp,
                modelUsed = it.modelUsed,
                metadata = it.metadata
            )
        }

        val personRelations = personRelationDao.getAll().map {
            BackupPersonRelation(
                subjectPersonId = it.subjectPersonId,
                objectPersonId = it.objectPersonId,
                predicate = it.predicate,
                source = it.source,
                customLabel = it.customLabel,
                confidence = it.confidence,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt
            )
        }

        val memoryFacts = memoryFactDao.getAll().map {
            BackupMemoryFact(
                content = it.content,
                category = it.category,
                source = it.source,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt
            )
        }

        val photoEditRecipes = photoEditRecipeDao.getAll().map {
            BackupPhotoEditRecipe(
                outputUri = it.outputUri,
                sourceUri = it.sourceUri,
                recipeJson = it.recipeJson,
                updatedAt = it.updatedAt
            )
        }

        val backup = TagDataBackup(
            exportedAt = System.currentTimeMillis(),
            tags = tags,
            mediaTagMetadata = metadataList,
            crossRefs = crossRefs,
            scanTasks = scanTasks,
            persons = persons,
            faceEmbeddings = faceEmbeddings,
            ocrWords = ocrWords,
            ocrWordOccurrences = ocrOccurrences,
            locationHierarchy = locations,
            mediaLocations = mediaLocations,
            mediaFeedback = mediaFeedback,
            preferences = preferences,
            chatSessions = chatSessions,
            chatMessages = chatMessages,
            personRelations = personRelations,
            memoryFacts = memoryFacts,
            photoEditRecipes = photoEditRecipes
        )

        // 使用流式 JSON 写入，避免大备份序列化时产生超大字符串导致 OOM
        file.outputStream().sink().buffer().use { sink ->
            backupAdapter.toJson(sink, backup)
        }

        ExportResult(
            file = file,
            tagCount = tags.size,
            mediaCount = metadataList.size,
            crossRefCount = crossRefs.size,
            scanTaskCount = scanTasks.size,
            personCount = persons.size,
            faceEmbeddingCount = faceEmbeddings.size,
            ocrWordCount = ocrWords.size,
            ocrWordOccurrenceCount = ocrOccurrences.size,
            locationCount = locations.size,
            mediaLocationCount = mediaLocations.size,
            mediaFeedbackCount = mediaFeedback.size,
            preferenceCount = preferences.entries.size,
            chatSessionCount = chatSessions.size,
            chatMessageCount = chatMessages.size,
            personRelationCount = personRelations.size,
            memoryFactCount = memoryFacts.size,
            photoEditRecipeCount = photoEditRecipes.size
        )
    }

    /**
     * 导出 DataStore 用户偏好为可序列化结构。
     */
    private suspend fun exportPreferences(): BackupPreferences {
        val prefs = dataStore.data.first()
        val entries = prefs.asMap().map { (key, value) ->
            when (value) {
                is String -> BackupPreferenceEntry(key.name, "STRING", value)
                is Boolean -> BackupPreferenceEntry(key.name, "BOOLEAN", value.toString())
                is Int -> BackupPreferenceEntry(key.name, "INT", value.toString())
                is Long -> BackupPreferenceEntry(key.name, "LONG", value.toString())
                is Float -> BackupPreferenceEntry(key.name, "FLOAT", value.toString())
                is Double -> BackupPreferenceEntry(key.name, "DOUBLE", value.toString())
                is Set<*> -> BackupPreferenceEntry(
                    key.name,
                    "STRING_SET",
                    "",
                    stringSetValues = value.filterIsInstance<String>()
                )
                else -> BackupPreferenceEntry(key.name, "STRING", value.toString())
            }
        }
        return BackupPreferences(entries)
    }

    /**
     * 从 JSON 文件还原 TAG 数据。
     *
     * @param file 备份文件。
     * @param dryRun 为 true 时只计算匹配情况，不实际写入数据库。
     */
    suspend fun importFromFile(
        file: File,
        dryRun: Boolean = false
    ): RestoreResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) {
            throw IOException("Backup file not readable: ${file.absolutePath}")
        }

        // 使用流式 JSON 读取，避免一次性把整个备份文件加载到内存
        val backup = file.source().buffer().use { source ->
            backupAdapter.fromJson(source)
        } ?: throw IOException("Failed to parse backup file: ${file.absolutePath}")

        // 1. 建立 URI -> 本地 mediaId 映射（一次性加载全部媒体，避免 N 次单条查询）
        val currentMedia = mediaDao.getAllMediaNow()
        val uriToMediaId = currentMedia.associate { it.uri to it.id }

        val allBackupUris = buildSet {
            backup.mediaTagMetadata.mapTo(this) { it.uri }
            backup.crossRefs.mapTo(this) { it.uri }
            backup.scanTasks.mapTo(this) { it.uri }
            backup.faceEmbeddings.mapTo(this) { it.mediaUri }
            backup.ocrWordOccurrences.mapTo(this) { it.mediaUri }
            backup.mediaLocations.mapTo(this) { it.mediaUri }
            backup.mediaFeedback.mapTo(this) { it.mediaUri }
        }

        val unmatchedUris = allBackupUris.filter { it !in uriToMediaId }
        val matchedMediaCount = allBackupUris.size - unmatchedUris.size

        if (dryRun) {
            return@withContext RestoreResult(
                matchedMediaCount = matchedMediaCount,
                unmatchedUris = unmatchedUris,
                restoredTagCount = 0,
                restoredCrossRefCount = 0,
                restoredScanTaskCount = 0,
                restoredMetadataCount = 0,
                restoredPersonCount = 0,
                restoredFaceEmbeddingCount = 0,
                restoredOcrWordCount = 0,
                restoredOcrWordOccurrenceCount = 0,
                restoredLocationCount = 0,
                restoredMediaLocationCount = 0,
                restoredMediaFeedbackCount = 0,
                restoredPreferenceCount = 0,
                restoredChatSessionCount = 0,
                restoredChatMessageCount = 0,
                restoredPersonRelationCount = 0,
                restoredMemoryFactCount = 0,
                restoredPhotoEditRecipeCount = 0
            )
        }

        // 2~9 在单个事务中执行，大幅提升写入速度并保证原子性
        var result: RestoreResult? = null
        database.beginTransaction()
        try {
            // 2. 重建标签并建立 name -> 新 tagId 映射
            val existingTags = tagDao.getAllTags().associateBy { it.name }
            val tagsToInsert = backup.tags
                .map { TagEntity(tagId = 0, name = it.name, category = it.category) }
                .filter { it.name !in existingTags }

            val restoredTagCount: Int
            val nameToTagId: Map<String, Long>
            if (tagsToInsert.isEmpty()) {
                restoredTagCount = 0
                nameToTagId = existingTags.mapValues { it.value.tagId }
            } else {
                val insertedIds = tagDao.insertTags(tagsToInsert)
                restoredTagCount = insertedIds.count { it > 0 }
                nameToTagId = existingTags.mapValues { it.value.tagId } +
                    tagsToInsert.zip(insertedIds).associate { it.first.name to it.second }
            }

            // 3. 恢复媒体-标签关联
            val crossRefsToInsert = backup.crossRefs.mapNotNull { ref ->
                val mediaId = uriToMediaId[ref.uri] ?: return@mapNotNull null
                val tagId = nameToTagId[ref.tagName] ?: return@mapNotNull null
                MediaTagCrossRef(mediaId = mediaId, tagId = tagId, confidence = ref.confidence)
            }
            if (crossRefsToInsert.isNotEmpty()) {
                crossRefsToInsert.forEach { tagDao.insertMediaTag(it) }
            }

            // 4. 恢复 TAG 扫描任务（重置为 PENDING，避免 RUNNING 状态悬挂）
            val scanTasksToInsert = backup.scanTasks.mapNotNull { task ->
                val mediaId = uriToMediaId[task.uri] ?: return@mapNotNull null
                TagScanTaskEntity(
                    id = 0,
                    sessionId = task.sessionId,
                    mediaId = mediaId,
                    pass = parsePass(task.pass),
                    tagCategories = task.tagCategories,
                    status = TagScanTaskStatus.PENDING,
                    priority = task.priority,
                    attemptCount = 0,
                    createdAt = task.createdAt,
                    scheduledAt = null,
                    startedAt = null,
                    completedAt = null,
                    errorMessage = null
                )
            }
            scanTasksToInsert.chunked(1000).forEach { chunk ->
                tagScanTaskDao.insertAll(chunk)
            }

            // 5. 恢复媒体 TAG 元数据字段
            var restoredMetadataCount = 0
            for (meta in backup.mediaTagMetadata) {
                val mediaId = uriToMediaId[meta.uri] ?: continue
                mediaDao.updateTagMetadataFromBackup(
                    mediaId = mediaId,
                    labels = meta.labels,
                    labelsEn = meta.labelsEn,
                    labelsZh = meta.labelsZh,
                    mlKitLabels = meta.mlKitLabels,
                    mlKitLabelsZh = meta.mlKitLabelsZh,
                    ocrText = meta.ocrText,
                    latitude = meta.latitude,
                    longitude = meta.longitude,
                    locationName = meta.locationName,
                    faceRoiResult = meta.faceRoiResult,
                    semanticEmbedding = meta.semanticEmbedding,
                    lastTagScanAt = meta.lastTagScanAt,
                    lastTagScanPasses = meta.lastTagScanPasses,
                    hasFace = meta.hasFace,
                    faceId = meta.faceId,
                    city = meta.city,
                    faceFocusY = meta.faceFocusY,
                    aestheticScore = meta.aestheticScore,
                    faceQualityScore = meta.faceQualityScore,
                    blurScore = meta.blurScore,
                    exposureScore = meta.exposureScore,
                    lastViewedAt = meta.lastViewedAt
                )
                restoredMetadataCount++
            }

            // 6. 恢复人物聚类（Pass 2 产出）
            val oldToNewPersonId = mutableMapOf<Long, Long>()
            var restoredPersonCount = 0
            for (person in backup.persons) {
                val coverMediaId = person.coverMediaUri?.let { uriToMediaId[it] }
                val newId = personDao.insertPerson(
                    PersonEntity(
                        personId = 0,
                        name = person.name,
                        coverMediaId = coverMediaId,
                        faceCount = person.faceCount,
                        createdAt = person.createdAt,
                        updatedAt = person.updatedAt
                    )
                )
                if (newId > 0) {
                    oldToNewPersonId[person.personId] = newId
                    restoredPersonCount++
                }
            }

            // 6.5 恢复人物关系图谱（依赖 oldToNewPersonId 重定位两端人物，须在人物恢复之后）
            val relationsToInsert = backup.personRelations.mapNotNull { rel ->
                val newSubjectId = oldToNewPersonId[rel.subjectPersonId] ?: return@mapNotNull null
                val newObjectId = oldToNewPersonId[rel.objectPersonId] ?: return@mapNotNull null
                PersonRelationEntity(
                    relationId = 0,
                    subjectPersonId = newSubjectId,
                    objectPersonId = newObjectId,
                    predicate = rel.predicate,
                    source = rel.source,
                    customLabel = rel.customLabel,
                    confidence = rel.confidence,
                    createdAt = rel.createdAt,
                    updatedAt = rel.updatedAt
                )
            }
            if (relationsToInsert.isNotEmpty()) {
                personRelationDao.upsertAll(relationsToInsert)
            }
            val restoredPersonRelationCount = relationsToInsert.size

            // 7. 恢复人脸 Embedding（Pass 1 产出）
            val embeddingsToInsert = backup.faceEmbeddings.mapNotNull { emb ->
                val mediaId = uriToMediaId[emb.mediaUri] ?: return@mapNotNull null
                val newPersonId = emb.personId?.let { oldToNewPersonId[it] }
                FaceEmbeddingEntity(
                    embeddingId = 0,
                    mediaId = mediaId,
                    personId = newPersonId,
                    embedding = Base64.decode(emb.embedding, Base64.NO_WRAP),
                    createdAt = emb.createdAt
                )
            }
            if (embeddingsToInsert.isNotEmpty()) {
                personDao.insertEmbeddings(embeddingsToInsert)
            }
            val restoredFaceEmbeddingCount = embeddingsToInsert.size

            // 8. 恢复 OCR 倒排索引
            val normalizedToNewWordId = mutableMapOf<String, Long>()
            var restoredOcrWordCount = 0
            for (word in backup.ocrWords) {
                val existing = ocrWordDao.getWordByNormalized(word.normalizedWord)
                val wordId = if (existing != null) {
                    existing.wordId
                } else {
                    val inserted = ocrWordDao.insertWord(
                        OcrWordEntity(wordId = 0, word = word.word, normalizedWord = word.normalizedWord)
                    )
                    if (inserted > 0) inserted else null
                }
                if (wordId != null) {
                    normalizedToNewWordId[word.normalizedWord] = wordId
                    if (existing == null) restoredOcrWordCount++
                }
            }

            val occurrencesToInsert = backup.ocrWordOccurrences.mapNotNull { occ ->
                val mediaId = uriToMediaId[occ.mediaUri] ?: return@mapNotNull null
                val wordId = normalizedToNewWordId[occ.normalizedWord] ?: return@mapNotNull null
                OcrWordOccurrence(
                    wordId = wordId,
                    mediaId = mediaId,
                    confidence = occ.confidence,
                    boundingBox = occ.boundingBox
                )
            }
            if (occurrencesToInsert.isNotEmpty()) {
                ocrWordDao.insertOccurrences(occurrencesToInsert)
            }
            val restoredOcrWordOccurrenceCount = occurrencesToInsert.size

            // 9. 恢复地理位置关系
            val coordinateToNewLocationId = mutableMapOf<String, Long>()
            var restoredLocationCount = 0
            for (location in backup.locationHierarchy) {
                val key = coordinateKey(location.latitude, location.longitude)
                if (key.isBlank()) continue
                val existing = locationDao.findByCoordinate(
                    location.latitude ?: 0.0,
                    location.longitude ?: 0.0
                )
                val locationId = if (existing != null) {
                    existing.locationId
                } else {
                    val inserted = locationDao.insertLocation(
                        LocationHierarchyEntity(
                            locationId = 0,
                            country = location.country,
                            province = location.province,
                            city = location.city,
                            district = location.district,
                            poi = location.poi,
                            latitude = location.latitude,
                            longitude = location.longitude
                        )
                    )
                    if (inserted > 0) inserted else null
                }
                if (locationId != null) {
                    coordinateToNewLocationId[key] = locationId
                    if (existing == null) restoredLocationCount++
                }
            }

            val mediaLocationsToInsert = backup.mediaLocations.mapNotNull { ml ->
                val mediaId = uriToMediaId[ml.mediaUri] ?: return@mapNotNull null
                val key = coordinateKey(ml.latitude, ml.longitude)
                val locationId = coordinateToNewLocationId[key] ?: return@mapNotNull null
                MediaLocationEntity(
                    mediaId = mediaId,
                    locationId = locationId,
                    accuracy = ml.accuracy
                )
            }
            if (mediaLocationsToInsert.isNotEmpty()) {
                mediaLocationsToInsert.chunked(500).forEach { chunk ->
                    locationDao.insertMediaLocations(chunk)
                }
            }
            val restoredMediaLocationCount = mediaLocationsToInsert.size

            // 10. 恢复媒体反馈（media_id 重定位：uri → 新 mediaId 字符串）
            val feedbackToInsert = backup.mediaFeedback.mapNotNull { fb ->
                val mediaId = uriToMediaId[fb.mediaUri] ?: return@mapNotNull null
                MediaFeedbackEntity(
                    id = 0,
                    mediaId = mediaId.toString(),
                    feedbackType = fb.feedbackType,
                    queryText = fb.queryText,
                    sessionId = fb.sessionId,
                    createdAt = fb.createdAt
                )
            }
            if (feedbackToInsert.isNotEmpty()) {
                mediaFeedbackDao.insertAll(feedbackToInsert)
            }
            val restoredMediaFeedbackCount = feedbackToInsert.size

            // 11. 恢复聊天会话与消息（REPLACE 幂等，主键为 String 无需重定位）
            backup.chatSessions.forEach { session ->
                chatSessionDao.insertSession(
                    ChatSessionEntity(
                        sessionId = session.sessionId,
                        title = session.title,
                        createdAt = session.createdAt,
                        updatedAt = session.updatedAt
                    )
                )
            }
            if (backup.chatMessages.isNotEmpty()) {
                backup.chatMessages.chunked(500).forEach { chunk ->
                    chatMessageDao.insertMessages(
                        chunk.map { msg ->
                            ChatMessageEntity(
                                id = msg.id,
                                sessionId = msg.sessionId,
                                type = msg.type,
                                content = msg.content,
                                timestamp = msg.timestamp,
                                modelUsed = msg.modelUsed,
                                metadata = msg.metadata
                            )
                        }
                    )
                }
            }

            // 12. 恢复事实记忆（无外部引用，直接插入）
            var restoredMemoryFactCount = 0
            for (fact in backup.memoryFacts) {
                val newId = memoryFactDao.insert(
                    MemoryFactEntity(
                        factId = 0,
                        content = fact.content,
                        category = fact.category,
                        source = fact.source,
                        createdAt = fact.createdAt,
                        updatedAt = fact.updatedAt
                    )
                )
                if (newId > 0) restoredMemoryFactCount++
            }

            // 13. 恢复编辑配方（主键 outputUri，REPLACE 幂等）
            backup.photoEditRecipes.forEach { recipe ->
                photoEditRecipeDao.insert(
                    PhotoEditRecipeEntity(
                        outputUri = recipe.outputUri,
                        sourceUri = recipe.sourceUri,
                        recipeJson = recipe.recipeJson,
                        updatedAt = recipe.updatedAt
                    )
                )
            }

            result = RestoreResult(
                matchedMediaCount = matchedMediaCount,
                unmatchedUris = unmatchedUris,
                restoredTagCount = restoredTagCount,
                restoredCrossRefCount = crossRefsToInsert.size,
                restoredScanTaskCount = scanTasksToInsert.size,
                restoredMetadataCount = restoredMetadataCount,
                restoredPersonCount = restoredPersonCount,
                restoredFaceEmbeddingCount = restoredFaceEmbeddingCount,
                restoredOcrWordCount = restoredOcrWordCount,
                restoredOcrWordOccurrenceCount = restoredOcrWordOccurrenceCount,
                restoredLocationCount = restoredLocationCount,
                restoredMediaLocationCount = restoredMediaLocationCount,
                restoredMediaFeedbackCount = restoredMediaFeedbackCount,
                restoredPreferenceCount = 0, // 在 SQLite 事务外恢复
                restoredChatSessionCount = backup.chatSessions.size,
                restoredChatMessageCount = backup.chatMessages.size,
                restoredPersonRelationCount = restoredPersonRelationCount,
                restoredMemoryFactCount = restoredMemoryFactCount,
                restoredPhotoEditRecipeCount = backup.photoEditRecipes.size
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        result ?: throw IOException("Restore transaction failed to produce result")

        // 10. 恢复 DataStore 用户偏好（在 SQLite 事务外执行）
        val restoredPreferenceCount = importPreferences(backup.preferences)

        result.copy(restoredPreferenceCount = restoredPreferenceCount)
    }

    /**
     * 恢复 DataStore 用户偏好。
     *
     * @return 实际恢复的键数量。
     */
    private suspend fun importPreferences(preferences: BackupPreferences): Int {
        if (preferences.entries.isEmpty()) return 0
        dataStore.edit { prefs ->
            preferences.entries.forEach { entry ->
                try {
                    when (entry.type) {
                        "STRING" -> prefs[stringPreferencesKey(entry.key)] = entry.value
                        "BOOLEAN" -> prefs[booleanPreferencesKey(entry.key)] = entry.value.toBooleanStrict()
                        "INT" -> prefs[intPreferencesKey(entry.key)] = entry.value.toInt()
                        "LONG" -> prefs[longPreferencesKey(entry.key)] = entry.value.toLong()
                        "FLOAT" -> prefs[floatPreferencesKey(entry.key)] = entry.value.toFloat()
                        "DOUBLE" -> prefs[doublePreferencesKey(entry.key)] = entry.value.toDouble()
                        "STRING_SET" -> prefs[stringSetPreferencesKey(entry.key)] = entry.stringSetValues.toSet()
                    }
                } catch (_: Exception) {
                    // 跳过解析失败的条目，避免单个坏值导致整个恢复失败
                }
            }
        }
        return preferences.entries.size
    }

    private fun parsePass(name: String): TagScanPass = when (name) {
        // 兼容枚举重命名前的旧备份：QWEN_TAGGING → IMAGE_TAGGING
        "QWEN_TAGGING" -> TagScanPass.IMAGE_TAGGING
        else -> runCatching { TagScanPass.valueOf(name) }.getOrDefault(TagScanPass.IMAGE_TAGGING)
    }

    private fun TagScanTaskEntity.toBackupModel(uri: String): BackupTagScanTask = BackupTagScanTask(
        sessionId = sessionId,
        uri = uri,
        pass = pass.name,
        tagCategories = tagCategories,
        status = status.name,
        priority = priority,
        attemptCount = attemptCount,
        createdAt = createdAt,
        scheduledAt = scheduledAt,
        startedAt = startedAt,
        completedAt = completedAt,
        errorMessage = errorMessage
    )

    private fun coordinateKey(lat: Double?, lon: Double?): String {
        return if (lat != null && lon != null) {
            "${"%.4f".format(lat)},${"%.4f".format(lon)}"
        } else {
            ""
        }
    }

    private fun coordinateLatitude(key: String): Double? =
        key.takeIf { it.isNotBlank() }?.substringBefore(",")?.toDoubleOrNull()

    private fun coordinateLongitude(key: String): Double? =
        key.takeIf { it.isNotBlank() }?.substringAfter(",")?.toDoubleOrNull()

    private fun com.mamba.picme.data.model.MediaEntity.isTagRelatedEmpty(): Boolean =
        labels.isNullOrBlank() &&
            mlKitLabels.isNullOrBlank() &&
            mlKitLabelsZh.isNullOrBlank() &&
            ocrText.isNullOrBlank() &&
            locationName.isNullOrBlank() &&
            faceRoiResult.isNullOrBlank() &&
            semanticEmbedding.isNullOrBlank() &&
            lastTagScanAt == null &&
            lastTagScanPasses.isNullOrBlank() &&
            !hasFace &&
            faceId.isNullOrBlank() &&
            city.isNullOrBlank() &&
            faceFocusY == null &&
            aestheticScore == null &&
            faceQualityScore == null &&
            blurScore == null &&
            exposureScore == null &&
            lastViewedAt == null
}
