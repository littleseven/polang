import Foundation
import SharedKit

// MARK: - 消息模型（commonMain SSOT，M5 B1）

/// Chat 消息模型（M5 B1）：typealias 切换为 commonMain `ChatMessage`（SharedKit SSOT——
/// ADR-016 parts 模型 + 9 值 kind 枚举线格式 + role 独立消息级字段）。
///
/// - SKIE 导出为 Swift **class**（commonMain 全 val，引用类型，属性不可变）——流式更新
///   一律 `messages[idx] = messages[idx].with(...)`（doCopy + 数组元素重赋值触发
///   @Published）；便捷构造走 `ChatMessage.make(...)`（Swift 默认参工厂，不依赖
///   SKIE default-arg overloads）。
/// - 持久化：`ChatHistoryStoreCodec`（iosMain，整文件 JSON：envelope v6 版本信封 +
///   9 值 kind→wire type + role 独立列 + parts 嵌套数组/结构化平铺字段双 payload）；
///   envelope 前的旧格式文件整体忽略（2026-11-08 值域终态——与 Android 聊天表重置
///   语义对齐），无 legacy 迁移路径。
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
        imageUri: String? = nil,
        chartSvg: String? = nil,
        htmlContent: String? = nil,
        imageSaved: Bool = false,
        isStreaming: Bool = false,
        showCursor: Bool = false,
        isThinking: Bool = false,
        agentSteps: [AgentStepUi] = [],
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
            imageUri: imageUri, chartSvg: chartSvg,
            htmlContent: htmlContent, imageSaved: imageSaved,
            isStreaming: isStreaming, showCursor: showCursor, isThinking: isThinking,
            agentSteps: agentSteps,
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
        chartSvg: String? = nil,
        htmlContent: String? = nil,
        optimizeCandidates: OptimizeCandidateGroup? = nil,
        parts: [MessagePart]? = nil
    ) -> ChatMessage {
        doCopy(
            id: id, type: type, content: content ?? self.content, role: role,
            modelUsed: modelUsed, timestamp: timestamp, performance: performance,
            imageUri: imageUri,
            chartSvg: chartSvg ?? self.chartSvg,
            htmlContent: htmlContent ?? self.htmlContent,
            imageSaved: imageSaved ?? self.imageSaved,
            isStreaming: isStreaming ?? self.isStreaming,
            showCursor: showCursor ?? self.showCursor,
            isThinking: isThinking ?? self.isThinking,
            agentSteps: agentSteps,
            claudeAgent: claudeAgent, claudeDeliver: claudeDeliver,
            optimizeCandidates: optimizeCandidates ?? self.optimizeCandidates,
            engineerTask: engineerTask,
            gachaInteractive: gachaInteractive ?? self.gachaInteractive,
            htmlCardMeta: htmlCardMeta, parts: parts ?? self.parts
        )
    }
}
