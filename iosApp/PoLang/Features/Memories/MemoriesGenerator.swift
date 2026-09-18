import Foundation

// MARK: - 回忆领域模型（spec memories.yaml §1 domain）
//
// 纯函数确定性生成器：零平台依赖（无 UI / Photos / DB），now/timeZone 注入，
// 单测可固定时钟。所有输入视为照片——视频由上游数据源过滤，生成器不判定类型。

/// 回忆类型（spec §1 output_model.type）。
enum MemoryType: Equatable {
    case onThisDay
    case recentHighlights
    case person
    case city
}

/// 单条媒体最小投影（spec §1 input_model）。personId = 媒体 faceId（persons.person_id 字符串）。
struct MemoryInput {
    let uri: String
    let captureDate: Int64
    let aestheticScore: Float?
    let city: String?
    let personId: String?
}

/// 仅已命名人物参与（name 非空白）；isSelf 本人不进人物回忆。
struct NamedPerson: Equatable {
    let personId: String
    let name: String
    let isSelf: Bool
}

/// 月日（java MonthDay 的最小替代；语义 = 与 now 同月同日，UI 按 Locale 本地化展示）。
struct MemoryMonthDay: Equatable {
    let month: Int
    let day: Int
}

/// 一条回忆：稳定 id + 结构化展示参数（不含文案串，[I18N] 红线——文案由 UI 层按类型还原）。
struct Memory: Identifiable, Equatable {
    let id: String
    let type: MemoryType
    /// 人物名（PERSON）/ 城市名（CITY）；其余 nil。
    let label: String?
    /// 命中总张数（精选截断后 itemUris 可能小于它）。
    let hitCount: Int
    /// 仅 ON_THIS_DAY：命中照片最大年份。
    let latestYear: Int?
    /// 仅 ON_THIS_DAY：与 now 同月同日。
    let monthDay: MemoryMonthDay?
    /// 精选首张。
    let coverUri: String
    /// 精选 URI：美学分降序（nil 排最后）→ 同分拍摄时间新在前，截 DETAIL_LIMIT。
    let itemUris: [String]
    /// 全部命中 URI，拍摄时间降序不截断（详情页「全部」开关与分享全集用）。
    let allItemUris: [String]
    /// 仅 CITY：命中最早拍摄 epochMs（旅程日期范围副行）。
    let earliestCaptureDate: Int64?
    /// 仅 CITY：命中最晚拍摄 epochMs。
    let latestCaptureDate: Int64?
}

// MARK: - 生成常量（spec §1 constants）

enum MemoryConstants {
    static let maxCarousel = 10
    static let detailLimit = 12
    static let minOnThisDay = 4
    static let minGroup = 6
    static let maxPerson = 3
    static let maxCity = 2
    /// 近 30 天窗口（ms）。
    static let recentWindowMs: Int64 = 2_592_000_000
}

// MARK: - 生成器

/// 回忆生成规则（spec §1 rules，纯函数）：
/// - on_this_day：与 now 同月同日的往年照片（year < now，未来年份排除）跨年聚合，≥4 张生成，至多 1 条
/// - recent_highlights：captureDate ∈ (now-30d, now] 且 aestheticScore 非 nil，≥6 张生成，至多 1 条
/// - person：已命名非本人人物命中 ≥6 张，按命中数降序取前 3
/// - city：city 非空白同城聚合 ≥6 张，按张数降序取前 2
/// - 输出顺序 ON_THIS_DAY > RECENT_HIGHLIGHTS > PERSON(媒体数降序) > CITY(张数降序)，总量截 maxCarousel
enum MemoriesGenerator {

    static func generate(
        inputs: [MemoryInput],
        persons: [NamedPerson],
        now: Date,
        timeZone: TimeZone,
        maxCarousel: Int = MemoryConstants.maxCarousel
    ) -> [Memory] {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let nowComps = cal.dateComponents([.year, .month, .day], from: now)
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)

        var out: [Memory] = []
        out.append(contentsOf: onThisDay(
            inputs, nowComps: nowComps, calendar: cal))
        out.append(contentsOf: recentHighlights(
            inputs, nowMs: nowMs, nowComps: nowComps, calendar: cal))
        out.append(contentsOf: personMemories(inputs, persons: persons))
        out.append(contentsOf: cityMemories(inputs))
        return Array(out.prefix(maxCarousel))
    }

    // MARK: ON_THIS_DAY

    private static func onThisDay(
        _ inputs: [MemoryInput],
        nowComps: DateComponents,
        calendar: Calendar
    ) -> [Memory] {
        var hits: [MemoryInput] = []
        var latestYear = Int.min
        let nowYear = nowComps.year ?? .max
        for input in inputs {
            let comps = calendar.dateComponents([.year, .month, .day], from: Date(ms: input.captureDate))
            guard comps.month == nowComps.month, comps.day == nowComps.day,
                  let year = comps.year, year < nowYear else { continue }
            hits.append(input)
            latestYear = max(latestYear, year)
        }
        guard hits.count >= MemoryConstants.minOnThisDay else { return [] }
        let monthDay = MemoryMonthDay(month: nowComps.month ?? 0, day: nowComps.day ?? 0)
        let id = String(format: "on_this_day:%02d-%02d", monthDay.month, monthDay.day)
        return [makeMemory(id: id, type: .onThisDay, label: nil, hits: hits,
                           latestYear: latestYear, monthDay: monthDay,
                           earliest: nil, latest: nil)]
    }

    // MARK: RECENT_HIGHLIGHTS

    private static func recentHighlights(
        _ inputs: [MemoryInput],
        nowMs: Int64,
        nowComps: DateComponents,
        calendar: Calendar
    ) -> [Memory] {
        let windowStart = nowMs - MemoryConstants.recentWindowMs
        let hits = inputs.filter {
            $0.captureDate > windowStart && $0.captureDate <= nowMs && $0.aestheticScore != nil
        }
        guard hits.count >= MemoryConstants.minGroup else { return [] }
        let id = String(format: "recent:%04d-%02d", nowComps.year ?? 0, nowComps.month ?? 0)
        return [makeMemory(id: id, type: .recentHighlights, label: nil, hits: hits,
                           latestYear: nil, monthDay: nil,
                           earliest: nil, latest: nil)]
    }

    // MARK: PERSON

    private static func personMemories(
        _ inputs: [MemoryInput],
        persons: [NamedPerson]
    ) -> [Memory] {
        let named = persons.filter { !$0.isSelf && !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        var byPerson: [String: [MemoryInput]] = [:]
        for namedPerson in named {
            byPerson[namedPerson.personId] = inputs.filter { $0.personId == namedPerson.personId }
        }
        return named
            .compactMap { person -> (person: NamedPerson, hits: [MemoryInput])? in
                guard let hits = byPerson[person.personId], hits.count >= MemoryConstants.minGroup else { return nil }
                return (person, hits)
            }
            .sorted {
                if $0.hits.count != $1.hits.count { return $0.hits.count > $1.hits.count }
                return $0.person.personId < $1.person.personId
            }
            .prefix(MemoryConstants.maxPerson)
            .map { pair in
                makeMemory(id: "person:\(pair.person.personId)", type: .person, label: pair.person.name,
                           hits: pair.hits, latestYear: nil, monthDay: nil, earliest: nil, latest: nil)
            }
    }

    // MARK: CITY

    private static func cityMemories(_ inputs: [MemoryInput]) -> [Memory] {
        var byCity: [String: [MemoryInput]] = [:]
        for input in inputs {
            guard let raw = input.city else { continue }
            let city = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !city.isEmpty else { continue }
            byCity[city, default: []].append(input)
        }
        return byCity
            .filter { $0.value.count >= MemoryConstants.minGroup }
            .sorted {
                if $0.value.count != $1.value.count { return $0.value.count > $1.value.count }
                return $0.key < $1.key
            }
            .prefix(MemoryConstants.maxCity)
            .map { city, hits in
                makeMemory(id: "city:\(city)", type: .city, label: city, hits: hits,
                           latestYear: nil, monthDay: nil,
                           earliest: hits.map(\.captureDate).min(),
                           latest: hits.map(\.captureDate).max())
            }
    }

    // MARK: 共用装配

    /// 精选排序：美学分降序（nil 最后）→ 同分拍摄时间新在前 → 平局按 uri 稳定（Swift sort 不稳定）。
    private static func selectedUris(_ hits: [MemoryInput]) -> [String] {
        hits.sorted {
            switch ($0.aestheticScore, $1.aestheticScore) {
            case let (lhs?, rhs?) where lhs != rhs:
                return lhs > rhs
            case (nil, _?):
                return false  // nil 美学分排在任何有分项之后
            case (_?, nil):
                return true
            default:
                break  // 同分（或双 nil）→ 拍摄时间新在前
            }
            if $0.captureDate != $1.captureDate { return $0.captureDate > $1.captureDate }
            return $0.uri < $1.uri
        }
        .prefix(MemoryConstants.detailLimit)
        .map(\.uri)
    }

    /// 全量排序：拍摄时间降序不截断；平局按 uri 稳定。
    private static func allUris(_ hits: [MemoryInput]) -> [String] {
        hits.sorted {
            if $0.captureDate != $1.captureDate { return $0.captureDate > $1.captureDate }
            return $0.uri < $1.uri
        }
        .map(\.uri)
    }

    private static func makeMemory(
        id: String,
        type: MemoryType,
        label: String?,
        hits: [MemoryInput],
        latestYear: Int?,
        monthDay: MemoryMonthDay?,
        earliest: Int64?,
        latest: Int64?
    ) -> Memory {
        let itemUris = selectedUris(hits)
        return Memory(
            id: id,
            type: type,
            label: label,
            hitCount: hits.count,
            latestYear: latestYear,
            monthDay: monthDay,
            coverUri: itemUris.first ?? "",
            itemUris: itemUris,
            allItemUris: allUris(hits),
            earliestCaptureDate: earliest,
            latestCaptureDate: latest
        )
    }
}

// MARK: - Date(ms:) 工具

private extension Date {
    init(ms: Int64) {
        self.init(timeIntervalSince1970: TimeInterval(ms) / 1000)
    }
}
