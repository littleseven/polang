package com.mamba.picme.domain.backup.model

import com.squareup.moshi.JsonClass

/**
 * TAG 相关数据库的备份容器。
 *
 * 以媒体 [uri] 作为跨安装稳定键（content://media/external/...），
 * 重装后通过 URI 在本地 media_assets 表中重新定位 mediaId，再恢复标签、
 * 关联、扫描任务及 TAG 元数据字段。
 *
 * 版本演进：
 * - v1：标签、媒体 TAG 元数据、关联、扫描任务
 * - v2：新增人脸 Embedding、人物聚类、OCR 倒排索引、地理位置关系
 * - v3：新增 DataStore 用户偏好（账号、Token、设置等）
 * - v4：新增媒体反馈（media_feedback，用户 like/dislike 训练数据）
 * - v5：新增聊天会话/消息、人物关系、事实记忆、编辑配方，
 *   媒体 TAG 元数据补 city/faceFocusY/aestheticScore/faceQualityScore
 */
@JsonClass(generateAdapter = true)
data class TagDataBackup(
    val version: Int = 5,
    val exportedAt: Long,
    val tags: List<BackupTag>,
    val mediaTagMetadata: List<BackupMediaTagMetadata>,
    val crossRefs: List<BackupMediaTagCrossRef>,
    val scanTasks: List<BackupTagScanTask>,
    val persons: List<BackupPerson> = emptyList(),
    val faceEmbeddings: List<BackupFaceEmbedding> = emptyList(),
    val ocrWords: List<BackupOcrWord> = emptyList(),
    val ocrWordOccurrences: List<BackupOcrWordOccurrence> = emptyList(),
    val locationHierarchy: List<BackupLocationHierarchy> = emptyList(),
    val mediaLocations: List<BackupMediaLocation> = emptyList(),
    val mediaFeedback: List<BackupMediaFeedback> = emptyList(),
    val preferences: BackupPreferences = BackupPreferences(),
    val chatSessions: List<BackupChatSession> = emptyList(),
    val chatMessages: List<BackupChatMessage> = emptyList(),
    val personRelations: List<BackupPersonRelation> = emptyList(),
    val memoryFacts: List<BackupMemoryFact> = emptyList(),
    val photoEditRecipes: List<BackupPhotoEditRecipe> = emptyList()
)

/**
 * DataStore 用户偏好备份。
 *
 * 以 key-value 形式保存，支持 string / boolean / int / long / float / stringSet。
 * 包含账号、Token、模型配置、相机记忆、主题语言等所有用户设置。
 */
@JsonClass(generateAdapter = true)
data class BackupPreferences(
    val entries: List<BackupPreferenceEntry> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BackupPreferenceEntry(
    val key: String,
    val type: String, // STRING, BOOLEAN, INT, LONG, FLOAT, STRING_SET
    val value: String,
    val stringSetValues: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BackupTag(
    val tagId: Long,
    val name: String,
    val category: String = "scene"
)

@JsonClass(generateAdapter = true)
data class BackupMediaTagMetadata(
    val uri: String,
    val labels: String? = null,
    val labelsEn: String? = null,
    val labelsZh: String? = null,
    val mlKitLabels: String? = null,
    val mlKitLabelsZh: String? = null,
    val ocrText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val faceRoiResult: String? = null,
    val semanticEmbedding: String? = null,
    val lastTagScanAt: Long? = null,
    val lastTagScanPasses: String? = null,
    val hasFace: Boolean = false,
    val faceId: String? = null,
    val city: String? = null,
    val faceFocusY: Float? = null,
    val aestheticScore: Float? = null,
    val faceQualityScore: Float? = null,
    // 整理中心 v2 信号（Task 18a 补入；旧备份无此三字段，Moshi 缺省回退 null 默认值）
    val blurScore: Float? = null,
    val exposureScore: Float? = null,
    val lastViewedAt: Long? = null
)

@JsonClass(generateAdapter = true)
data class BackupMediaTagCrossRef(
    val uri: String,
    val tagName: String,
    val confidence: Float? = null
)

@JsonClass(generateAdapter = true)
data class BackupTagScanTask(
    val sessionId: String,
    val uri: String,
    val pass: String,
    val tagCategories: String? = null,
    val status: String,
    val priority: Int = 0,
    val attemptCount: Int = 0,
    val createdAt: Long,
    val scheduledAt: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null
)

/** 人物聚类结果（Pass 2 产出） */
@JsonClass(generateAdapter = true)
data class BackupPerson(
    val personId: Long,
    val name: String? = null,
    val coverMediaUri: String? = null,
    val faceCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

/** 人脸 Embedding（Pass 1 产出） */
@JsonClass(generateAdapter = true)
data class BackupFaceEmbedding(
    val mediaUri: String,
    val personId: Long? = null,
    val embedding: String, // ByteArray 的 Base64
    val createdAt: Long
)

/** OCR 词条 */
@JsonClass(generateAdapter = true)
data class BackupOcrWord(
    val wordId: Long,
    val word: String,
    val normalizedWord: String
)

/** OCR 词条出现记录 */
@JsonClass(generateAdapter = true)
data class BackupOcrWordOccurrence(
    val normalizedWord: String,
    val mediaUri: String,
    val confidence: Float? = null,
    val boundingBox: String? = null
)

/** 地理层级信息 */
@JsonClass(generateAdapter = true)
data class BackupLocationHierarchy(
    val locationId: Long,
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val poi: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

/** 媒体-地理位置关联 */
@JsonClass(generateAdapter = true)
data class BackupMediaLocation(
    val mediaUri: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null
)

/**
 * 媒体反馈（用户 like/dislike，影响搜索排序）。
 *
 * media_feedback 表的 media_id 存的是 MediaEntity.id（Long）的字符串形式，
 * 故此处以 mediaUri 作跨安装稳定键，restore 时重定位回新 mediaId。
 */
@JsonClass(generateAdapter = true)
data class BackupMediaFeedback(
    val mediaUri: String,
    val feedbackType: String,
    val queryText: String,
    val sessionId: String,
    val createdAt: Long
)

/** 聊天会话元数据 */
@JsonClass(generateAdapter = true)
data class BackupChatSession(
    val sessionId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 聊天消息。
 *
 * 已知限制：图片消息的 [content] 为旧安装的本地文件路径，
 * 跨安装恢复后图片文件可能不存在，仅保留消息记录本身。
 */
@JsonClass(generateAdapter = true)
data class BackupChatMessage(
    val id: String,
    val sessionId: String,
    val type: String,
    val content: String,
    val timestamp: Long,
    val modelUsed: String? = null,
    val metadata: String? = null
)

/**
 * 人物关系图谱边。subject/objectPersonId 为旧安装的本地自增 personId，
 * 恢复时通过 oldPersonId → newPersonId 映射重定位（与人物聚类同一映射）。
 */
@JsonClass(generateAdapter = true)
data class BackupPersonRelation(
    val subjectPersonId: Long,
    val objectPersonId: Long,
    val predicate: String,
    val source: String,
    val customLabel: String? = null,
    val confidence: Float = 1.0f,
    val createdAt: Long,
    val updatedAt: Long
)

/** 通用事实记忆（"帮我记住…"），无外部引用，恢复时直接插入 */
@JsonClass(generateAdapter = true)
data class BackupMemoryFact(
    val content: String,
    val category: String? = null,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 非破坏性编辑配方。outputUri 即主键，直接写入；
 * 已知限制：跨安装恢复后 outputUri 指向的媒体可能已不存在。
 */
@JsonClass(generateAdapter = true)
data class BackupPhotoEditRecipe(
    val outputUri: String,
    val sourceUri: String,
    val recipeJson: String,
    val updatedAt: Long
)
