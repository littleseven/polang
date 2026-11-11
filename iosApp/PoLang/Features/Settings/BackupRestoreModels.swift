import Foundation

// MARK: - 备份容器 DTO（对齐 Android TagDataBackup v6）
//
// 字段名与 androidApp `domain/backup/model/TagDataBackup.kt` 逐字段同构
// （JSON 键 = Kotlin 属性名，跨平台互备兼容）。导入侧版本门禁：仅接受
// version == 6——Android 兼容 v5 粗映射，iOS v1 不做（contracts C7 决策）。

/// 备份容器顶层（对应 Kotlin `TagDataBackup`，version=6 + 17 段）。
struct TagDataBackupContainer {
    static let currentVersion = 6

    var version: Int = currentVersion
    var exportedAt: Int64 = 0
    var tags: [BackupTag] = []
    var mediaTagMetadata: [BackupMediaTagMetadata] = []
    var crossRefs: [BackupMediaTagCrossRef] = []
    var scanTasks: [BackupTagScanTask] = []
    var persons: [BackupPerson] = []
    var faceEmbeddings: [BackupFaceEmbedding] = []
    var ocrWords: [BackupOcrWord] = []
    var ocrWordOccurrences: [BackupOcrWordOccurrence] = []
    var locationHierarchy: [BackupLocationHierarchy] = []
    var mediaLocations: [BackupMediaLocation] = []
    var mediaFeedback: [BackupMediaFeedback] = []
    var preferences = BackupPreferences()
    var chatSessions: [BackupChatSession] = []
    var chatMessages: [BackupChatMessage] = []
    var personRelations: [BackupPersonRelation] = []
    var memoryFacts: [BackupMemoryFact] = []
    var photoEditRecipes: [BackupPhotoEditRecipe] = []
}

// MARK: - 段内 DTO（字段名照抄 Android；可空性对齐 Kotlin nullable）

/// DataStore 用户偏好（iOS v1 恒空数组：Android DataStore key 与 iOS UserDefaults key 集不同源，C7 决策）。
struct BackupPreferences: Codable {
    var entries: [BackupPreferenceEntry] = []
}

struct BackupPreferenceEntry: Codable {
    var key: String = ""
    var type: String = ""          // STRING, BOOLEAN, INT, LONG, FLOAT, STRING_SET
    var value: String = ""
    var stringSetValues: [String] = []
}

struct BackupTag: Codable {
    var tagId: Int64 = 0
    var name: String = ""
    var category: String = "scene"
}

struct BackupMediaTagMetadata: Codable {
    var uri: String = ""
    var labels: String? = nil
    var labelsEn: String? = nil
    var labelsZh: String? = nil
    var mlKitLabels: String? = nil
    var mlKitLabelsZh: String? = nil
    var ocrText: String? = nil
    var latitude: Double? = nil
    var longitude: Double? = nil
    var locationName: String? = nil
    var faceRoiResult: String? = nil
    var semanticEmbedding: String? = nil
    var lastTagScanAt: Int64? = nil
    var lastTagScanPasses: String? = nil
    var hasFace: Bool = false
    var faceId: String? = nil
    var city: String? = nil
    var faceFocusY: Float? = nil
    var aestheticScore: Float? = nil
    var faceQualityScore: Float? = nil
    var blurScore: Float? = nil
    var exposureScore: Float? = nil
    var lastViewedAt: Int64? = nil
}

struct BackupMediaTagCrossRef: Codable {
    var uri: String = ""
    var tagName: String = ""
    var confidence: Float? = nil
}

struct BackupTagScanTask: Codable {
    var sessionId: String = ""
    var uri: String = ""
    var pass: String = ""
    var tagCategories: String? = nil
    var status: String = ""
    var priority: Int = 0
    var attemptCount: Int = 0
    var createdAt: Int64 = 0
    var scheduledAt: Int64? = nil
    var startedAt: Int64? = nil
    var completedAt: Int64? = nil
    var errorMessage: String? = nil
}

struct BackupPerson: Codable {
    var personId: Int64 = 0
    var name: String? = nil
    var coverMediaUri: String? = nil
    var faceCount: Int = 0
    var createdAt: Int64 = 0
    var updatedAt: Int64 = 0
}

struct BackupFaceEmbedding: Codable {
    var mediaUri: String = ""
    var personId: Int64? = nil
    var embedding: String = ""     // BLOB 的 Base64（NO_WRAP）
    var createdAt: Int64 = 0
}

struct BackupOcrWord: Codable {
    var wordId: Int64 = 0
    var word: String = ""
    var normalizedWord: String = ""
}

struct BackupOcrWordOccurrence: Codable {
    var normalizedWord: String = ""
    var mediaUri: String = ""
    var confidence: Float? = nil
    var boundingBox: String? = nil
}

struct BackupLocationHierarchy: Codable {
    var locationId: Int64 = 0
    var country: String? = nil
    var province: String? = nil
    var city: String? = nil
    var district: String? = nil
    var poi: String? = nil
    var latitude: Double? = nil
    var longitude: Double? = nil
}

struct BackupMediaLocation: Codable {
    var mediaUri: String = ""
    var latitude: Double? = nil
    var longitude: Double? = nil
    var accuracy: Float? = nil
}

struct BackupMediaFeedback: Codable {
    var mediaUri: String = ""
    var feedbackType: String = ""
    var queryText: String = ""
    var sessionId: String = ""
    var createdAt: Int64 = 0
}

struct BackupChatSession: Codable {
    var sessionId: String = ""
    var title: String = ""
    var createdAt: Int64 = 0
    var updatedAt: Int64 = 0
}

/// 聊天消息（partsJson 为权威负载——envelope v6 里消息的 parts 数组 JSON 原样直通；
/// iOS 侧无 metadata blob，恒 nil）。role 缺失时按 type 前缀推断（对齐 Android 恢复侧口径）。
struct BackupChatMessage: Codable {
    var id: String = ""
    var sessionId: String = ""
    var type: String = ""
    var content: String = ""
    var timestamp: Int64 = 0
    var modelUsed: String? = nil
    var metadata: String? = nil
    var role: String? = nil
    var partsJson: String? = nil
}

/// 人物关系边。iOS 表无时间戳列，导出以导出时刻填充；恢复接受端点映射成功的边（语义子集，C7）。
struct BackupPersonRelation: Codable {
    var subjectPersonId: Int64 = 0
    var objectPersonId: Int64 = 0
    var predicate: String = ""
    var source: String = ""
    var customLabel: String? = nil
    var confidence: Float = 1.0
    var createdAt: Int64 = 0
    var updatedAt: Int64 = 0
}

struct BackupMemoryFact: Codable {
    var content: String = ""
    var category: String? = nil
    var source: String = ""
    var createdAt: Int64 = 0
    var updatedAt: Int64 = 0
}

struct BackupPhotoEditRecipe: Codable {
    var outputUri: String = ""
    var sourceUri: String = ""
    var recipeJson: String = ""
    var updatedAt: Int64 = 0
}

// MARK: - 容器 Codable（宽进：段缺失回退空数组，version 缺失回 0 走版本门禁报错）

extension TagDataBackupContainer: Codable {
    enum CodingKeys: String, CodingKey {
        case version, exportedAt, tags, mediaTagMetadata, crossRefs, scanTasks, persons
        case faceEmbeddings, ocrWords, ocrWordOccurrences, locationHierarchy, mediaLocations
        case mediaFeedback, preferences, chatSessions, chatMessages, personRelations
        case memoryFacts, photoEditRecipes
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 0
        exportedAt = try c.decodeIfPresent(Int64.self, forKey: .exportedAt) ?? 0
        tags = try c.decodeIfPresent([BackupTag].self, forKey: .tags) ?? []
        mediaTagMetadata = try c.decodeIfPresent([BackupMediaTagMetadata].self, forKey: .mediaTagMetadata) ?? []
        crossRefs = try c.decodeIfPresent([BackupMediaTagCrossRef].self, forKey: .crossRefs) ?? []
        scanTasks = try c.decodeIfPresent([BackupTagScanTask].self, forKey: .scanTasks) ?? []
        persons = try c.decodeIfPresent([BackupPerson].self, forKey: .persons) ?? []
        faceEmbeddings = try c.decodeIfPresent([BackupFaceEmbedding].self, forKey: .faceEmbeddings) ?? []
        ocrWords = try c.decodeIfPresent([BackupOcrWord].self, forKey: .ocrWords) ?? []
        ocrWordOccurrences = try c.decodeIfPresent([BackupOcrWordOccurrence].self, forKey: .ocrWordOccurrences) ?? []
        locationHierarchy = try c.decodeIfPresent([BackupLocationHierarchy].self, forKey: .locationHierarchy) ?? []
        mediaLocations = try c.decodeIfPresent([BackupMediaLocation].self, forKey: .mediaLocations) ?? []
        mediaFeedback = try c.decodeIfPresent([BackupMediaFeedback].self, forKey: .mediaFeedback) ?? []
        preferences = try c.decodeIfPresent(BackupPreferences.self, forKey: .preferences) ?? BackupPreferences()
        chatSessions = try c.decodeIfPresent([BackupChatSession].self, forKey: .chatSessions) ?? []
        chatMessages = try c.decodeIfPresent([BackupChatMessage].self, forKey: .chatMessages) ?? []
        personRelations = try c.decodeIfPresent([BackupPersonRelation].self, forKey: .personRelations) ?? []
        memoryFacts = try c.decodeIfPresent([BackupMemoryFact].self, forKey: .memoryFacts) ?? []
        photoEditRecipes = try c.decodeIfPresent([BackupPhotoEditRecipe].self, forKey: .photoEditRecipes) ?? []
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(version, forKey: .version)
        try c.encode(exportedAt, forKey: .exportedAt)
        try c.encode(tags, forKey: .tags)
        try c.encode(mediaTagMetadata, forKey: .mediaTagMetadata)
        try c.encode(crossRefs, forKey: .crossRefs)
        try c.encode(scanTasks, forKey: .scanTasks)
        try c.encode(persons, forKey: .persons)
        try c.encode(faceEmbeddings, forKey: .faceEmbeddings)
        try c.encode(ocrWords, forKey: .ocrWords)
        try c.encode(ocrWordOccurrences, forKey: .ocrWordOccurrences)
        try c.encode(locationHierarchy, forKey: .locationHierarchy)
        try c.encode(mediaLocations, forKey: .mediaLocations)
        try c.encode(mediaFeedback, forKey: .mediaFeedback)
        try c.encode(preferences, forKey: .preferences)
        try c.encode(chatSessions, forKey: .chatSessions)
        try c.encode(chatMessages, forKey: .chatMessages)
        try c.encode(personRelations, forKey: .personRelations)
        try c.encode(memoryFacts, forKey: .memoryFacts)
        try c.encode(photoEditRecipes, forKey: .photoEditRecipes)
    }
}

// MARK: - 结果计数

/// 导出计数（对齐 Android ExportResult 子集；UI 展示 Tags/Media/Persons/Sessions/Messages）。
struct BackupExportSummary {
    var tagCount = 0
    var mediaCount = 0
    var crossRefCount = 0
    var scanTaskCount = 0
    var personCount = 0
    var faceEmbeddingCount = 0
    var ocrWordCount = 0
    var ocrWordOccurrenceCount = 0
    var locationCount = 0
    var mediaLocationCount = 0
    var mediaFeedbackCount = 0
    var personRelationCount = 0
    var chatSessionCount = 0
    var chatMessageCount = 0
}

/// 恢复计数（对齐 Android RestoreResult + iOS 聊天段；matched/unmatched 为跨安装 uri 匹配汇总）。
struct BackupRestoreSummary {
    var matchedMediaCount = 0
    var unmatchedUriCount = 0
    var restoredTagCount = 0
    var restoredCrossRefCount = 0
    var restoredScanTaskCount = 0
    var restoredMetadataCount = 0
    var restoredPersonCount = 0
    var restoredFaceEmbeddingCount = 0
    var restoredOcrWordCount = 0
    var restoredOcrWordOccurrenceCount = 0
    var restoredLocationCount = 0
    var restoredMediaLocationCount = 0
    var restoredMediaFeedbackCount = 0
    var restoredPersonRelationCount = 0
    var restoredChatSessionCount = 0
    var restoredChatMessageCount = 0
}

// MARK: - 错误（状态文本呈现，不 crash）

enum BackupRestoreError: LocalizedError {
    case fileNotReadable(String)
    case parseFailed(String)
    case unsupportedVersion(Int)
    case sqlFailed(String)
    case embeddingDecodeFailed(String)
    case chatSessionDecodeFailed(String)

    var errorDescription: String? {
        switch self {
        case .fileNotReadable(let path):
            return String(format: L("Backup file not readable: %1$@"), path)
        case .parseFailed(let detail):
            return String(format: L("Failed to parse backup file: %1$@"), detail)
        case .unsupportedVersion(let found):
            return String(format: L("Unsupported backup version (found %1$d, expected 6)"), found)
        case .sqlFailed(let detail):
            return String(format: L("Database operation failed: %1$@"), detail)
        case .embeddingDecodeFailed(let uri):
            return String(format: L("Corrupt face embedding data for media: %1$@"), uri)
        case .chatSessionDecodeFailed(let sessionId):
            return String(format: L("Failed to rebuild chat history for session: %1$@"), sessionId)
        }
    }
}
