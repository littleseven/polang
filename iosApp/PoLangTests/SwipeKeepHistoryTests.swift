import XCTest
@testable import PoLang

/// KEEP 30 天抑制测试（语义移植 Android SwipeKeepHistoryTest + UserDefaults 存储回路）。
final class SwipeKeepHistoryTests: XCTestCase {

    private let now: Int64 = 10_000_000_000_000

    func testEncodeDecodeRoundtrip() {
        let entry = SwipeKeepHistory.encode(uri: "content://media/1", keptAtMs: now)
        XCTAssertEqual(["content://media/1"], SwipeKeepHistory.activeUris([entry]))
        XCTAssertEqual([entry], SwipeKeepHistory.activeEntries([entry], nowMs: now))
    }

    func testEntriesWithinTtlStayActive() {
        let entry = SwipeKeepHistory.encode(uri: "a", keptAtMs: now - SwipeKeepHistory.ttlMs + 1)
        XCTAssertEqual([entry], SwipeKeepHistory.activeEntries([entry], nowMs: now))
    }

    func testEntriesAtTtlBoundaryExpire() {
        let entry = SwipeKeepHistory.encode(uri: "a", keptAtMs: now - SwipeKeepHistory.ttlMs)
        XCTAssertTrue(SwipeKeepHistory.activeEntries([entry], nowMs: now).isEmpty)
    }

    func testDirtyEntriesWithoutTimestampAreDropped() {
        XCTAssertTrue(SwipeKeepHistory.activeEntries(["no-separator", "tail|"], nowMs: now).isEmpty)
        XCTAssertTrue(SwipeKeepHistory.activeEntries(["a|not-a-number"], nowMs: now).isEmpty)
    }

    func testActiveUrisStripsTimestampSuffix() {
        let entries: Set<String> = [
            SwipeKeepHistory.encode(uri: "a", keptAtMs: now),
            SwipeKeepHistory.encode(uri: "b|c", keptAtMs: now), // uri 自身含分隔符：取最后一段为时间戳
        ]
        XCTAssertEqual(["a", "b|c"], SwipeKeepHistory.activeUris(entries))
        XCTAssertEqual(entries, SwipeKeepHistory.activeEntries(entries, nowMs: now))
    }

    func testUserDefaultsStoreRoundtripAndPruneOnRecord() {
        let suite = "SwipeKeepHistoryTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = SwipeKeepHistoryStore(defaults: defaults)

        // 写读回路
        store.save([SwipeKeepHistory.encode(uri: "a", keptAtMs: now)])
        XCTAssertEqual(["a"], SwipeKeepHistory.activeUris(store.load()))

        // recordKeep：过期条目裁剪后追加新条目
        defaults.set([
            SwipeKeepHistory.encode(uri: "expired", keptAtMs: now - SwipeKeepHistory.ttlMs),
            "dirty-entry",
        ], forKey: SwipeKeepHistoryStore.storageKey)
        store.recordKeep(uri: "b", keptAtMs: now)
        XCTAssertEqual([SwipeKeepHistory.encode(uri: "b", keptAtMs: now)], store.load())
    }
}
