import XCTest
@testable import PoLang

/// 回忆生成器规则测试（spec memories.yaml §1 domain）：
/// 四类型阈值边界 / 窗口与年份排除 / 人物与城市截断 / 精选排序与封面 /
/// 输出顺序 / maxCarousel 截断 / 空输入。固定 now 与 UTC 时区保证确定性。
final class MemoriesGeneratorTests: XCTestCase {

    private let tz = TimeZone(identifier: "UTC")!
    /// 固定时钟：2026-09-15 12:00:00 UTC。
    private var now: Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        return cal.date(from: DateComponents(year: 2026, month: 9, day: 15, hour: 12))!
    }
    private var nowMs: Int64 { Int64(now.timeIntervalSince1970 * 1000) }

    /// UTC 时区下的年月日 → epoch 毫秒（默认正午，避开日界边缘）。
    private func ms(_ year: Int, _ month: Int, _ day: Int, hour: Int = 12) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        return Int64(cal.date(from: DateComponents(year: year, month: month, day: day, hour: hour))!
            .timeIntervalSince1970 * 1000)
    }

    private func input(
        _ uri: String,
        date: Int64,
        score: Float? = nil,
        city: String? = nil,
        personId: String? = nil
    ) -> MemoryInput {
        MemoryInput(uri: uri, captureDate: date, aestheticScore: score, city: city, personId: personId)
    }

    private func generate(
        _ inputs: [MemoryInput],
        persons: [NamedPerson] = [],
        maxCarousel: Int = MemoryConstants.maxCarousel
    ) -> [Memory] {
        MemoriesGenerator.generate(inputs: inputs, persons: persons, now: now, timeZone: tz, maxCarousel: maxCarousel)
    }

    // MARK: - 空输入

    func testEmptyInputsYieldNothing() {
        XCTAssertTrue(generate([]).isEmpty)
    }

    // MARK: - ON_THIS_DAY

    func testOnThisDayBelowThresholdNotGenerated() {
        let inputs = (0..<3).map { input("otd\($0)", date: ms(2020 + $0, 9, 15)) }
        XCTAssertTrue(generate(inputs).isEmpty)
    }

    func testOnThisDayAggregatesPastYearsOnly() {
        let inputs = [
            input("a", date: ms(2019, 9, 15)),
            input("b", date: ms(2020, 9, 15)),
            input("c", date: ms(2023, 9, 15)),
            input("d", date: ms(2025, 9, 15)),
        ]
        let out = generate(inputs)
        XCTAssertEqual(1, out.count)
        let m = out[0]
        XCTAssertEqual(.onThisDay, m.type)
        XCTAssertEqual("on_this_day:09-15", m.id)
        XCTAssertEqual(4, m.hitCount)
        XCTAssertEqual(2025, m.latestYear)
        XCTAssertEqual(MemoryMonthDay(month: 9, day: 15), m.monthDay)
        XCTAssertNil(m.label)
        XCTAssertNil(m.earliestCaptureDate)
        XCTAssertNil(m.latestCaptureDate)
    }

    func testOnThisDayExcludesFutureYearCurrentYearAndOtherDates() {
        let inputs = [
            // 未来年份（2027）同月同日：排除
            input("future1", date: ms(2027, 9, 15)),
            input("future2", date: ms(2028, 9, 15)),
            // 当前年（2026）同月同日：排除（要求 year < now）
            input("current1", date: ms(2026, 9, 15)),
            input("current2", date: ms(2026, 9, 15)),
            // 往年但非同月同日：排除
            input("otherdate1", date: ms(2020, 9, 14)),
            input("otherdate2", date: ms(2020, 8, 15)),
        ]
        XCTAssertTrue(generate(inputs).isEmpty)
    }

    // MARK: - RECENT_HIGHLIGHTS

    func testRecentHighlightsThresholdAndScoreRequirement() {
        let inWindow = (0..<6).map { input("r\($0)", date: nowMs - 1_000_000, score: 5.0) }
        // 6 张窗口内但 1 张无美学分 → 5 张有效，不足 ≥6
        var noScore = inWindow
        noScore[5] = input("r5", date: nowMs - 1_000_000)
        XCTAssertTrue(generate(noScore).isEmpty)
        // 6 张全部有分 → 生成
        let out = generate(inWindow)
        XCTAssertEqual(1, out.count)
        XCTAssertEqual(.recentHighlights, out[0].type)
        XCTAssertEqual("recent:2026-09", out[0].id)
        XCTAssertEqual(6, out[0].hitCount)
    }

    func testRecentHighlightsWindowBoundaries() {
        let inputs = [
            // 恰在 now（闭区间右端）→ 计入
            input("edgeNow", date: nowMs, score: 5.0),
            // 恰在 now-30d（开区间左端）→ 排除
            input("edgeStart", date: nowMs - MemoryConstants.recentWindowMs, score: 5.0),
            // 窗口外（更早）→ 排除
            input("tooOld", date: nowMs - MemoryConstants.recentWindowMs - 1, score: 5.0),
            // 未来时间戳（> now）→ 排除
            input("future", date: nowMs + 1, score: 5.0),
            // 窗口内 4 张
            input("f1", date: nowMs - 100, score: 5.0),
            input("f2", date: nowMs - 200, score: 5.0),
            input("f3", date: nowMs - 300, score: 5.0),
            input("f4", date: nowMs - 400, score: 5.0),
        ]
        // 有效 = edgeNow + f1..f4 = 5 张 < 6（边界项全部排除后不足阈值）
        XCTAssertTrue(generate(inputs).isEmpty)
    }

    // MARK: - PERSON

    func testPersonRequiresNamedNonSelfWithMinHits() {
        let alicePhotos = (0..<6).map { input("a\($0)", date: ms(2021, 1, 10 + $0), personId: "1") }
        var inputs = alicePhotos
        // 未命名人物 6 张（NamedPerson 名字空白）→ 排除
        inputs += (0..<6).map { input("u\($0)", date: ms(2021, 2, 10 + $0), personId: "2") }
        // 本人 6 张 → 排除
        inputs += (0..<6).map { input("s\($0)", date: ms(2021, 3, 10 + $0), personId: "3") }
        // 已命名但仅 3 张 → 不足 ≥6
        inputs += (0..<3).map { input("x\($0)", date: ms(2021, 4, 10 + $0), personId: "4") }
        // 无人脸归属（personId nil）→ 排除
        inputs += (0..<6).map { input("n\($0)", date: ms(2021, 5, 10 + $0)) }

        let persons = [
            NamedPerson(personId: "1", name: "Alice", isSelf: false),
            NamedPerson(personId: "2", name: "  ", isSelf: false),  // 空白名
            NamedPerson(personId: "3", name: "Me", isSelf: true),
            NamedPerson(personId: "4", name: "X", isSelf: false),
        ]
        let out = generate(inputs, persons: persons)
        XCTAssertEqual(1, out.count)
        XCTAssertEqual(.person, out[0].type)
        XCTAssertEqual("person:1", out[0].id)
        XCTAssertEqual("Alice", out[0].label)
        XCTAssertEqual(6, out[0].hitCount)
    }

    func testPersonTop3ByHitCount() {
        var inputs: [MemoryInput] = []
        for (pid, count) in [("1", 9), ("2", 8), ("3", 7), ("4", 6)] {
            inputs += (0..<count).map { input("p\(pid)_\($0)", date: ms(2021, 6, 1 + $0 % 20), personId: pid) }
        }
        let persons = (1...4).map { NamedPerson(personId: "\($0)", name: "P\($0)", isSelf: false) }
        let out = generate(inputs, persons: persons)
        XCTAssertEqual(3, out.count)
        XCTAssertEqual(["person:1", "person:2", "person:3"], out.map(\.id))
        XCTAssertEqual([9, 8, 7], out.map(\.hitCount))
    }

    // MARK: - CITY

    func testCityRequiresNonBlankWithMinHitsAndSetsDateRange() {
        var inputs = (0..<6).map { input("paris\($0)", date: ms(2022, 5, 1 + $0), city: "Paris") }
        // 空白 city → 排除
        inputs += (0..<6).map { input("blank\($0)", date: ms(2022, 6, 1 + $0), city: "   ") }
        // nil city → 排除
        inputs += (0..<6).map { input("nil\($0)", date: ms(2022, 7, 1 + $0)) }
        // 5 张 → 不足
        inputs += (0..<5).map { input("lyon\($0)", date: ms(2022, 8, 1 + $0), city: "Lyon") }

        let out = generate(inputs)
        XCTAssertEqual(1, out.count)
        let m = out[0]
        XCTAssertEqual(.city, m.type)
        XCTAssertEqual("city:Paris", m.id)
        XCTAssertEqual("Paris", m.label)
        XCTAssertEqual(6, m.hitCount)
        XCTAssertEqual(ms(2022, 5, 1), m.earliestCaptureDate)
        XCTAssertEqual(ms(2022, 5, 6), m.latestCaptureDate)
    }

    func testCityTop2ByCount() {
        var inputs: [MemoryInput] = []
        for (city, count) in [("A", 10), ("B", 9), ("C", 8)] {
            inputs += (0..<count).map { input("\(city)\($0)", date: ms(2023, 1, 1 + $0 % 20), city: city) }
        }
        let out = generate(inputs)
        XCTAssertEqual(["city:A", "city:B"], out.map(\.id))
        XCTAssertEqual([10, 9], out.map(\.hitCount))
    }

    // MARK: - 精选排序 / 封面 / 全量

    func testSelectionOrderTruncationAndCover() {
        // 15 张同人物（personId "1"）照片：4 张 9.5 分（同分拍摄时间新在前）、1 张 6.0 分、
        // 10 张无分（排最后，按拍摄时间降序）。
        var inputs: [MemoryInput] = []
        inputs.append(input("top1", date: ms(2021, 1, 1), score: 9.5, personId: "1"))
        inputs.append(input("top2", date: ms(2021, 1, 2), score: 9.5, personId: "1"))
        inputs.append(input("top3", date: ms(2021, 1, 3), score: 9.5, personId: "1"))
        inputs.append(input("top4", date: ms(2020, 12, 31), score: 9.5, personId: "1"))
        inputs.append(input("mid", date: ms(2021, 2, 1), score: 6.0, personId: "1"))
        inputs.append(input("nil1", date: ms(2021, 3, 1), personId: "1"))
        inputs.append(input("nil2", date: ms(2021, 3, 2), personId: "1"))
        // 8 张无分补足（4 月，拍摄时间最新——验证无分区内按时间降序）
        for i in 0..<8 {
            inputs.append(input("fill\(i)", date: ms(2021, 4, 1 + i), personId: "1"))
        }
        XCTAssertEqual(15, inputs.count)

        let out = generate(inputs, persons: [NamedPerson(personId: "1", name: "A", isSelf: false)])
        XCTAssertEqual(1, out.count)
        let m = out[0]
        XCTAssertEqual(15, m.hitCount)
        XCTAssertEqual(12, m.itemUris.count)  // DETAIL_LIMIT 截断
        XCTAssertEqual("top3", m.coverUri)    // 9.5 分组内最新
        XCTAssertEqual(["top3", "top2", "top1", "top4", "mid",
                        "fill7", "fill6", "fill5", "fill4", "fill3", "fill2", "fill1"],
                       m.itemUris)
        // allItemUris：拍摄时间降序、不截断（4 月组 → 3 月无分组 → 2 月 → 1 月 → 2020-12-31）
        XCTAssertEqual(15, m.allItemUris.count)
        XCTAssertEqual("fill7", m.allItemUris.first)
        XCTAssertEqual("top4", m.allItemUris.last)
    }

    // MARK: - 输出顺序与 maxCarousel

    func testOutputOrderAndMaxCarouselTruncation() {
        var inputs: [MemoryInput] = []
        // OTD：4 张往年今日
        inputs += (0..<4).map { input("otd\($0)", date: ms(2020 + $0, 9, 15)) }
        // RECENT：6 张窗口内有分（日期避开 9/15 防 OTD 计入）
        inputs += (0..<6).map { input("rec\($0)", date: nowMs - 1_000_000 - Int64($0) * 86_400_000 / 2, score: 5.0) }
        // PERSON ×3（各 6..8 张，日期避开今日与窗口）
        for (pid, count) in [("9", 8), ("8", 7), ("7", 6)] {
            inputs += (0..<count).map { input("p\(pid)_\($0)", date: ms(2022, 2, 1 + $0 % 20), personId: pid) }
        }
        // CITY ×2（各 ≥6 张）
        for (city, count) in [("A", 6), ("B", 6)] {
            inputs += (0..<count).map { input("c\(city)\($0)", date: ms(2023, 3, 1 + $0), city: city) }
        }
        let persons = [NamedPerson(personId: "9", name: "P9", isSelf: false),
                       NamedPerson(personId: "8", name: "P8", isSelf: false),
                       NamedPerson(personId: "7", name: "P7", isSelf: false)]

        let out = generate(inputs, persons: persons)
        XCTAssertEqual(7, out.count)
        XCTAssertEqual(["on_this_day:09-15", "recent:2026-09", "person:9", "person:8", "person:7", "city:A", "city:B"],
                       out.map(\.id))

        // maxCarousel 截断：总量 > maxCarousel 时保序截断
        let truncated = generate(inputs, persons: persons, maxCarousel: 3)
        XCTAssertEqual(["on_this_day:09-15", "recent:2026-09", "person:9"], truncated.map(\.id))
    }
}
