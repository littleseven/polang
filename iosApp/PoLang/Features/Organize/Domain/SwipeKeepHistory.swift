import Foundation

/// KEEP 决策 30 天抑制（F2 spec）：防止「One more round 原样重喂刚 KEEP 过的整队」。
/// 条目编码 `"uri|epochMs"`；读取时顺带清理过期条目（调用方负责把裁剪结果写回存储）。
/// 纯函数部分直译 Android domain/swipe/SwipeKeepHistory.kt，可 XCTest 直测。
enum SwipeKeepHistory {

    static let ttlMs: Int64 = 30 * 24 * 60 * 60 * 1000
    private static let separator: Character = "|"

    static func encode(uri: String, keptAtMs: Int64) -> String { "\(uri)\(separator)\(keptAtMs)" }

    /// 仍活跃的条目（未过期且时间戳可解析；脏条目一并清除）。
    static func activeEntries(_ entries: Set<String>, nowMs: Int64) -> Set<String> {
        entries.filter { entry in
            guard let keptAtMs = keptAtMs(of: entry) else { return false }
            return nowMs - keptAtMs < ttlMs
        }
    }

    /// 活跃条目对应的 uri 集（建队过滤用）。
    static func activeUris(_ entries: Set<String>) -> Set<String> {
        Set(entries.map { entry in
            // uri 自身可含分隔符：取最后一段为时间戳（对齐 Kotlin substringBeforeLast）
            guard let idx = entry.lastIndex(of: separator) else { return entry }
            return String(entry[..<idx])
        })
    }

    /// 解析尾部时间戳（对齐 Kotlin substringAfterLast(separator, "")：无分隔符 → nil）。
    private static func keptAtMs(of entry: String) -> Int64? {
        guard let idx = entry.lastIndex(of: separator) else { return nil }
        let tail = entry[entry.index(after: idx)...]
        guard !tail.isEmpty else { return nil }
        return Int64(tail)
    }
}

/// KEEP 抑制历史存储（引擎无关接口语义对齐 Android SwipeKeepHistoryStore；
/// iOS 生产实现 = UserDefaults 字符串数组，等价 Android DataStore stringSet）。
/// 读写均为整体集合（条目量级 = 30 天内 KEEP 数，百级内）。
final class SwipeKeepHistoryStore {

    /// UserDefaults 键（对齐 Android DataStore preferences 键名）。
    static let storageKey = "swipe_keep_history"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> Set<String> {
        Set(defaults.stringArray(forKey: Self.storageKey) ?? [])
    }

    func save(_ entries: Set<String>) {
        defaults.set(Array(entries), forKey: Self.storageKey)
    }

    /// 记录一次 KEEP：读取时裁剪过期/脏条目（TTL 语义对齐），追加新条目后整体写回。
    func recordKeep(uri: String, keptAtMs: Int64) {
        var entries = SwipeKeepHistory.activeEntries(load(), nowMs: keptAtMs)
        entries.insert(SwipeKeepHistory.encode(uri: uri, keptAtMs: keptAtMs))
        save(entries)
    }
}
