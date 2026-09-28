import Foundation
import SharedKit

// MARK: - 消息模型（commonMain SSOT，M5 B1）

/// Chat 消息模型（M5 B1）：typealias 切换为 commonMain `ChatMessage`（SharedKit SSOT——
/// ADR-016 parts 模型 + type 分类法 8 值线格式 + role 独立消息级字段）。
///
/// - SKIE 导出为 Swift **class**（commonMain 全 val，引用类型，属性不可变）——流式更新
///   一律 `messages[idx] = messages[idx].with(...)`（doCopy + 数组元素重赋值触发
///   @Published）；便捷构造走 `ChatMessage.make(...)`（Swift 默认参工厂，不依赖
///   SKIE default-arg overloads）。
/// - 持久化：`ChatHistoryStoreCodec`（iosMain，整文件 JSON：8 值分类法 wire type +
///   role 独立列 + parts 嵌套数组/结构化平铺字段双 payload）；legacy JSON 兜底
///   迁移走 `LegacyChatMessage.toShared()`（codec 解不了时一次迁移 + 回写新格式）。
/// - 双显禁止契约（ChatListFlattener）：CHART/HTML_CARD 消息行 `content` 承载
///   svg/html 本体（persisted payloads 集合来自消息行 content），parts 与平铺字段
///   双写一致；写路径 parts 一律持久轨 p0/p1。
/// - 瞬态字段（isStreaming/showCursor/isThinking）不落库；gachaInteractive 为运行时
///   计算值（controller hasPending），同样不落库。
typealias ChatMessage = SharedKit.ChatMessage

// Identifiable（id: String 已满足；不加 @retroactive，保 Swift 5 编译兼容）
extension ChatMessage: Identifiable {}

extension ChatMessage {
    /// 便捷工厂：Swift 默认参包装 22 参 designated init（type/role 必填，其余全默认）。
    static func make(
        id: String = UUID().uuidString,
        type: ChatMessageType,
        content: String = "",
        role: ModelInputRole,
        modelUsed: String? = nil,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        performance: LlmPerformance? = nil,
        mediaResults: MediaResultsUi? = nil,
        imageUri: String? = nil,
        chartSvg: String? = nil,
        htmlContent: String? = nil,
        imageSaved: Bool = false,
        isStreaming: Bool = false,
        showCursor: Bool = false,
        isThinking: Bool = false,
        claudeAgent: ClaudeAgentState? = nil,
        claudeDeliver: ClaudeDeliverUi? = nil,
        optimizeCandidates: OptimizeCandidateGroup? = nil,
        engineerTask: EngineerTaskState? = nil,
        gachaInteractive: Bool = false,
        htmlCardMeta: HtmlCardMeta? = nil,
        parts: [MessagePart] = []
    ) -> ChatMessage {
        ChatMessage(
            id: id, type: type, content: content, role: role,
            modelUsed: modelUsed, timestamp: timestamp, performance: performance,
            mediaResults: mediaResults, imageUri: imageUri, chartSvg: chartSvg,
            htmlContent: htmlContent, imageSaved: imageSaved,
            isStreaming: isStreaming, showCursor: showCursor, isThinking: isThinking,
            claudeAgent: claudeAgent, claudeDeliver: claudeDeliver,
            optimizeCandidates: optimizeCandidates, engineerTask: engineerTask,
            gachaInteractive: gachaInteractive, htmlCardMeta: htmlCardMeta, parts: parts
        )
    }

    /// 原位更新便捷包装（nil = 保持当前值；doCopy 转发——class 引用语义下属性
    /// 不可赋值，调用方须 `messages[idx] = messages[idx].with(...)` 触发发布）。
    /// type/imageUri 变更走 `make` 重建（保留 id/timestamp）。
    func with(
        content: String? = nil,
        isStreaming: Bool? = nil,
        showCursor: Bool? = nil,
        isThinking: Bool? = nil,
        imageSaved: Bool? = nil,
        gachaInteractive: Bool? = nil,
        mediaResults: MediaResultsUi? = nil,
        chartSvg: String? = nil,
        htmlContent: String? = nil,
        optimizeCandidates: OptimizeCandidateGroup? = nil,
        parts: [MessagePart]? = nil
    ) -> ChatMessage {
        doCopy(
            id: id, type: type, content: content ?? self.content, role: role,
            modelUsed: modelUsed, timestamp: timestamp, performance: performance,
            mediaResults: mediaResults ?? self.mediaResults, imageUri: imageUri,
            chartSvg: chartSvg ?? self.chartSvg,
            htmlContent: htmlContent ?? self.htmlContent,
            imageSaved: imageSaved ?? self.imageSaved,
            isStreaming: isStreaming ?? self.isStreaming,
            showCursor: showCursor ?? self.showCursor,
            isThinking: isThinking ?? self.isThinking,
            claudeAgent: claudeAgent, claudeDeliver: claudeDeliver,
            optimizeCandidates: optimizeCandidates ?? self.optimizeCandidates,
            engineerTask: engineerTask,
            gachaInteractive: gachaInteractive ?? self.gachaInteractive,
            htmlCardMeta: htmlCardMeta, parts: parts ?? self.parts
        )
    }
}

// MARK: - Legacy JSON 兜底迁移

/// 老线格式（Swift struct Codable 时代的 Documents/chat_history_<sessionId>.json）。
/// 仅服务 `ChatHistoryStore` 兜底解码 + `toShared()` 一次性迁移（新线格式
/// `ChatHistoryStoreCodec` 解不了时回写），新代码禁止引用。
struct LegacyChatMessage: Codable {
    let id: UUID
    let role: Role
    var text: String
    let timestamp: Date
    /// 消息类型（老 JSON 无此字段 → init(from:) 推断，见下）
    var type: MessageType
    /// 图片引用：userImageText = PHAsset localIdentifier；agentEditResult = Documents/chat_edits 文件路径
    var imageUri: String?
    var isStreaming: Bool
    var isThinking: Bool
    var isToolCalling: Bool
    var mediaIds: [Int64]
    var error: String?
    var mediaQuery: String?
    var mediaTotalCount: Int?

    enum CodingKeys: String, CodingKey {
        case id, role, text, timestamp, type, imageUri
        case isStreaming, isThinking, isToolCalling
        case mediaIds, error, mediaQuery, mediaTotalCount
        case gacha
    }

    enum Role: String, Codable {
        case user, assistant
    }

    enum MessageType: String, Codable {
        case userText, agentText, userImageText, mediaResults, chart, agentEditResult
        case optimizeCandidates
        case agentImage
    }

    /// 单张抽卡候选卡（老 gacha 载荷；迁移丢弃 index/rejectReason——
    /// Candidate（OptimizeCandidateGroup 嵌套类）无此二字段，index==数组位置）。
    struct GachaCandidate: Codable {
        let index: Int
        let direction: String
        let thumbPath: String?
        let nimaScore: Float?
        let rejected: Bool
        var rejectReason: String? = nil
    }

    struct GachaPayload: Codable {
        var sourceImageUri: String
        var scene: String
        var recommendedIndex: Int
        var candidates: [GachaCandidate]
        var usedFingerprints: [String]
        var drawIndex: Int
    }

    init(id: UUID = UUID(), role: Role, text: String, timestamp: Date = Date(),
         type: MessageType? = nil, imageUri: String? = nil,
         isStreaming: Bool = false, isThinking: Bool = false, isToolCalling: Bool = false,
         mediaIds: [Int64] = [], error: String? = nil,
         mediaQuery: String? = nil, mediaTotalCount: Int? = nil,
         gacha: GachaPayload? = nil) {
        self.id = id
        self.role = role
        self.text = text
        self.timestamp = timestamp
        self.type = type ?? (role == .user ? .userText : .agentText)
        self.imageUri = imageUri
        self.isStreaming = isStreaming
        self.isThinking = isThinking
        self.isToolCalling = isToolCalling
        self.mediaIds = mediaIds
        self.error = error
        self.mediaQuery = mediaQuery
        self.mediaTotalCount = mediaTotalCount
    }

    // MARK: - Codable 向前兼容（老 JSON 无 type/imageUri/gacha）

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        role = try c.decode(Role.self, forKey: .role)
        text = try c.decode(String.self, forKey: .text)
        timestamp = try c.decode(Date.self, forKey: .timestamp)
        isStreaming = try c.decode(Bool.self, forKey: .isStreaming)
        isThinking = try c.decode(Bool.self, forKey: .isThinking)
        isToolCalling = try c.decode(Bool.self, forKey: .isToolCalling)
        mediaIds = try c.decode([Int64].self, forKey: .mediaIds)
        error = try c.decodeIfPresent(String.self, forKey: .error)
        mediaQuery = try c.decodeIfPresent(String.self, forKey: .mediaQuery)
        mediaTotalCount = try c.decodeIfPresent(Int.self, forKey: .mediaTotalCount)
        let decodedType = try c.decodeIfPresent(MessageType.self, forKey: .type)
        imageUri = try c.decodeIfPresent(String.self, forKey: .imageUri)
        // 老数据推断：user → userText；assistant + mediaIds → mediaResults；否则 agentText
        type = decodedType ?? (role == .user ? .userText : (!mediaIds.isEmpty ? .mediaResults : .agentText))
        // gacha：结构漂移时静默丢弃（nil）——消息退化为纯文本气泡，不崩
        gacha = (try? c.decodeIfPresent(GachaPayload.self, forKey: .gacha)) ?? nil
    }

    private var gacha: GachaPayload?
}

extension LegacyChatMessage {
    /// legacy → commonMain 一次性迁移（type 8→11 同名映射；media/chart/gacha 补 parts
    /// 双写 p0）。瞬态（isStreaming/isThinking/showCursor/isToolCalling）与
    /// gachaInteractive 不迁移——落库数据本就无流式中间态，交互态运行时查 controller。
    func toShared() -> ChatMessage {
        let sharedRole: ModelInputRole = role == .user ? .user : .assistant
        let sharedType: ChatMessageType
        let content = !text.isEmpty ? text : (error ?? "")
        var mediaResults: MediaResultsUi? = nil
        var optimizeCandidates: OptimizeCandidateGroup? = nil
        var parts: [MessagePart] = []

        switch type {
        case .userText:
            sharedType = ChatMessageType.userText
        case .agentText:
            sharedType = ChatMessageType.agentText
        case .userImageText:
            sharedType = ChatMessageType.userImageText
        case .agentImage:
            sharedType = ChatMessageType.agentImage
        case .agentEditResult:
            sharedType = ChatMessageType.agentEditResult
        case .mediaResults:
            sharedType = ChatMessageType.mediaResults
            // uri 空 stub（老线格式只存 id）；渲染侧按 assets[].id 反查媒体库
            let ui = MediaResultsUi(
                query: mediaQuery ?? "",
                assets: mediaIds.map {
                    MediaAsset(
                        id: $0, uri: "", type: .photo, captureDate: 0, fileName: "",
                        duration: nil, hasFace: false, faceId: nil, source: nil,
                        labels: nil, ocrText: nil, latitude: nil, longitude: nil,
                        locationName: nil, city: nil, indexedAt: nil,
                        faceFocusY: nil, aestheticScore: nil, faceQualityScore: nil
                    )
                },
                totalCount: Int32(mediaTotalCount ?? mediaIds.count),
                isRefinement: false,
                feedbackState: [:]
            )
            mediaResults = ui
            parts = [MessagePartMediaResults(partId: "p0", results: ui)]
        case .chart:
            sharedType = ChatMessageType.chart
            // chartSvg 老线格式不持久化（CodingKeys 排除，LegacyChatMessage 无此存储）——
            // 迁移后 chart 老数据退化为纯文本气泡（parts 空 → flattener 走 legacy_message 桶）。
        case .optimizeCandidates:
            sharedType = ChatMessageType.optimizeCandidates
            if let gacha {
                let group = OptimizeCandidateGroup(
                    sourceImageUri: gacha.sourceImageUri,
                    scene: gacha.scene,
                    recommendedIndex: Int32(gacha.recommendedIndex),
                    candidates: gacha.candidates.map {
                        OptimizeCandidateGroup.Candidate(
                            direction: $0.direction,
                            thumbPath: $0.thumbPath ?? "",
                            nimaScore: $0.nimaScore.map { KotlinFloat(float: $0) },
                            rejected: $0.rejected
                        )
                    },
                    usedFingerprints: gacha.usedFingerprints,
                    drawIndex: Int32(gacha.drawIndex)
                )
                optimizeCandidates = group
                parts = [MessagePartOptimizeCandidates(partId: "p0", group: group)]
            }
        }

        return ChatMessage.make(
            id: id.uuidString,
            type: sharedType,
            content: content,
            role: sharedRole,
            timestamp: Int64(timestamp.timeIntervalSince1970 * 1000),
            mediaResults: mediaResults,
            imageUri: imageUri,
            optimizeCandidates: optimizeCandidates,
            parts: parts
        )
    }
}
