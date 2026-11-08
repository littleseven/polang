import Foundation
import SharedKit

/// IosChatSearchBridge 的 Swift 实现：Kotlin iosMain `IosChatGalleryCapability` → `MediaSearchEngine`。
///
/// SharedBridge 铁律（kmp-ios-interop skill）：本类所有方法绝不抛异常跨边界——
/// 失败一律回空集合（Kotlin 异常逃逸到 Swift 会 signal 6 崩溃）。
/// `search` 的 completion **必须**被调用（Kotlin 侧 suspendCancellableCoroutine 等待恢复）。
///
/// 行为契约 SSOT：`tmp/ios-follow/gallery-search/contracts.md` §9（Chat 搜索链路）。
@objc final class PhSearchBridge: NSObject, IosChatSearchBridge {

    /// 全文/结构化搜索（契约 §9.2 onSearchMedia / onRefineMediaSearch）：
    /// - intent 非 nil → SearchIntent → SearchFilter 映射（§9.3 含时间词清洗）后 filter 入口；
    ///   `originalQuery` 恒传 nil——对齐 Android `search(filter = ...)` 的派生 query 语义（§2.3）。
    /// - intent 为 nil → query 全文入口（§2.2 三层管线）。
    /// - limitToDbIds 非 nil → 结果限制在 media_assets 主键集合内（refine in-set，§9.2）。
    func search(
        query: String,
        intent: SearchIntent?,
        limitToDbIds: [KotlinLong]?,
        completion: @escaping ([IosSearchResultItem]) -> Void
    ) {
        let limitIds = limitToDbIds.map { Set($0.map { $0.int64Value }) }
        Task {
            let rows: [SearchMediaRow]
            if let intent {
                let filter = ChatSearchMapping.filter(from: intent)
                rows = await MediaSearchEngine.shared.search(
                    filter: filter, originalQuery: nil, limitToIds: limitIds)
            } else {
                rows = await MediaSearchEngine.shared.search(
                    query: query, lang: Self.currentSearchLang(), limitToIds: limitIds)
            }
            completion(rows.map(ChatSearchMapping.item(from:)))
        }
    }

    /// 搜索语言（与 MediaSearchEngine.shared 的 langProvider 同规则）：
    /// 显式英文 → "en"；其余（含中文两档与跟随系统）→ "zh"。
    static func currentSearchLang() -> String {
        LanguageManager.shared.currentLanguage == "english" ? "en" : "zh"
    }
}
