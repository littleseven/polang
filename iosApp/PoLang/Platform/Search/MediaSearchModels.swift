import Foundation

// MARK: - 相册搜索核心模型（双端契约 SSOT: tmp/ios-follow/gallery-search/contracts.md §1）
//
// 与 Android `StructuredFilter` / `SearchIntent` / 反馈命令模型字段一一对应。
// 仅放跨组件共享的核心类型；各引擎私有类型（Segment/ExplicitFilter 等）归各自文件。

/// 时间范围（毫秒时间戳，闭区间）。契约 §1.1 TimeRange。
struct SearchTimeRange: Equatable, Sendable {
    let startMs: Int64
    let endMs: Int64
}

/// 结构化搜索过滤条件（LLM 意图 → 本地检索的中间表示）。契约 §1.1 StructuredFilter。
struct SearchFilter: Equatable, Sendable {
    var timeRange: SearchTimeRange? = nil
    /// 通用关键词（匹配标签、文件名）
    var keywords: [String] = []
    /// OCR 文字关键词
    var ocrKeywords: [String] = []
    /// 地点关键词
    var locationKeywords: [String] = []
    /// 人物名称
    var personName: String? = nil
    /// 是否包含人脸
    var hasFaces: Bool? = nil
    /// 规则引擎无法解析、需要 LLM 兜底
    var needsLlm: Bool = false

    /// 是否完全空（无任何约束）——等价 Android 侧"无约束即不筛"语义
    var isEmpty: Bool {
        timeRange == nil && keywords.isEmpty && ocrKeywords.isEmpty
            && locationKeywords.isEmpty && personName == nil && hasFaces == nil
    }
}

/// 排序后的搜索结果项。契约 §2.4 mergeAndRank 输出元素。
struct ScoredMedia<M>: Sendable where M: Sendable {
    let media: M
    var score: Float
}
