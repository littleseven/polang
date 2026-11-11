import Foundation
import SharedKit

// MARK: - 云端 Agent run_gallery_script 无头 JS 执行器（ios-follow 2026-11-10）

/// 对齐 Android `CloudAgentScriptTools`（contracts.md C3；2026-11-08 云端取数通路 +
/// 2026-11-09 注册制图卡产物）。与 chat 链路持久 runtime（`RunScriptBridge`）的差异
/// （保持云端面只读）：
/// - 独立 JsRuntime（source="cloud_agent"，进程级常驻懒建），handler 集 =
///   `GalleryScriptHandlers.registerAll` 12 只读件（同源 chat 链路），**不注册**
///   capability.dispatch 写通路；
/// - 注入 chart_bootstrap（含 `Chart.echarts` 固定模板 + 5 主题 + 五语兜底）——
///   产物为**注册制**：JS 侧调用即 push `globalThis.__polangArtifacts`，本执行器
///   每次执行前清空、执行后统一回收包装为信封，与脚本 return 形状彻底解耦
///   （LLM 把图卡结果包进别的对象返回也不丢卡；HTML 不出端、不进 LLM 上下文）；
/// - 普通 eval 结果不喂 UI——return 值整体 JSON 化回传云端 LLM（字符串原样）。
///
/// 串行化：[evalLock] 保护整个 eval 序列（预注入→evalAsync→drain），对齐 Android
/// evalMutex——JSCore 单 context 非线程安全，与 RunScriptBridge 同一护栏思路。
///
/// 工具名/错误前缀/截断 SSOT = shared `CloudAgentToolContract`（仅 Swift 侧持字面量
/// 副本，双向注释互链；同 `CloudAgentChatStore.errorPrefix` 先例）。
final class CloudAgentScriptRunner {

    static let shared = CloudAgentScriptRunner()

    /// 失败前缀（契约：hasPrefix("error:") → ok=false；SSOT = shared CloudAgentToolContract.ERROR_PREFIX）。
    private static let errorPrefix = "error:"

    /// run_gallery_script 的 code 参数名（SSOT = shared CloudAgentToolCatalog paramNames 首参）。
    static let codeParam = "code"

    /// 云端聚合脚本求值。注：timeoutMs 仅为 API 形态对齐保留——JSCore evaluateScript
    /// 无中断机制（见 JsCoreEngine 类注释「超时」节），死循环脚本无硬超时（登记技术债，
    /// 与 Android QuickJs interrupt 语义差异）；协作式脚本（bridge 调用 + return）不受影响。
    private static let evalTimeoutMs: Int64 = 10_000

    /// 脚本错误标记（JsCoreEngine evalAsync 异常路径的返回前缀；转 error 契约串上行）。
    private static let scriptErrorPrefix = "[脚本错误] "

    private var runtime: JsRuntime?
    private let buildLock = NSLock()
    private let evalLock = NSLock()

    // MARK: - 入口

    /// 执行云端下发的相册聚合脚本（后台线程求值；evalAsync 同步阻塞见 JsCoreEngine）。
    func runScript(code: String, languageTag: String) async -> String {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: self.runScriptSync(code: code, languageTag: languageTag))
            }
        }
    }

    /// 同步执行体（仅后台线程调用）：分发优先级对齐 Android——
    /// 注册表产物 → 顶层 {html} 兜底 → 顶层 {chart} SVG 拦截引导 → 原样回传。
    private func runScriptSync(code: String, languageTag: String) -> String {
        if code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "\(Self.errorPrefix) empty script"
        }
        let rt = ensureRuntime()
        evalLock.lock()
        defer { evalLock.unlock() }
        do {
            // 每次执行前刷新语言标签 + 清空产物注册表（切语言后新图即新文案；
            // 注册表清空保证 drain 只回收本轮产物）。失败不阻断脚本。
            let tag = Self.jsonQuoted(languageTag)
            _ = rt.eval(script: "globalThis.__polangLang=\(tag);globalThis.__polangArtifacts=[];")
            let result = rt.evalAsync(code: code, timeoutMs: Self.evalTimeoutMs)
            if let str = result as? JsValue.Str {
                // JSCore 异常包装串 → error 契约串（对齐 Android catch → error: <msg>）
                if str.value.hasPrefix(Self.scriptErrorPrefix) {
                    return "\(Self.errorPrefix) " + String(str.value.dropFirst(Self.scriptErrorPrefix.count))
                }
                return str.value
            }
            let cards = Self.drainArtifacts(rt)
            // 分发优先级对齐 Android：注册表产物 → 顶层 {html} 兜底 → {chart} SVG 拦截 → 原样
            if !cards.isEmpty {
                // 注册制产物：调用即发卡，与 return 形状解耦；
                // 原 return 剥除 html 后作为 stats 随信封上行（保住 LLM 要的统计）
                return Self.wrapHtmlArtifact(cards: cards, stats: Self.statsJson(of: result))
            }
            if let obj = result as? JsValue.Obj {
                if let html = obj.entries["html"] as? JsValue.Str {
                    // 兼容兜底：bootstrap 未加载时脚本自组 {html, summary} 仍发卡
                    let summary = (obj.entries["summary"] as? JsValue.Str)?.value ?? ""
                    return Self.wrapHtmlArtifact(cards: [(html: html.value, summary: summary)], stats: nil)
                }
                if obj.entries["chart"] is JsValue.Str {
                    // SVG 图卡云端无渲染通路：拦截省 token 并引导改用 Chart.echarts
                    return "\(Self.errorPrefix) SVG chart 不支持云端渲染，请改用 Chart.echarts(cfg) 返回 {html, summary}"
                }
            }
            return result.toJson()
        }
    }

    // MARK: - Runtime 懒建（chart bootstrap + 只读 handler，对齐 RunScriptBridge 范式）

    private func ensureRuntime() -> JsRuntime {
        buildLock.lock()
        defer { buildLock.unlock() }
        if let runtime = runtime { return runtime }
        let engine = JsCoreEngine()
        // 后台 scope（≠ evalAsync 线程，规避 __bridgeCallAsync 信号量死锁，同 RunScriptBridge）
        let rt = IosJsRuntimeSupportKt.createIosJsRuntime(engine: engine, source: "cloud_agent")
        // 注入 Chart 图表生成器（bar/line/pie/timeline SVG + Chart.echarts HTML 模板）。
        // 失败仅告警，不阻断脚本能力（对齐 Android getOrCreate）。
        if let chartJs = Self.loadChartBootstrap() {
            _ = rt.eval(script: chartJs)
        } else {
            NSLog("[PoLang:CloudAgentScript] chart_bootstrap.js not found in bundle")
        }
        GalleryScriptHandlers.registerAll(into: rt)
        runtime = rt
        NSLog("[PoLang:CloudAgentScript] JsRuntime ready (handlers=%@)", rt.handlerNames().joined(separator: ","))
        return rt
    }

    /// 加载 chart_bootstrap.js（与 ChartJsEngine/RunScriptBridge 同一双路径查找）。
    private static func loadChartBootstrap() -> String? {
        if let url = Bundle.main.url(forResource: "chart_bootstrap", withExtension: "js", subdirectory: "js"),
           let js = try? String(contentsOf: url, encoding: .utf8) {
            return js
        }
        if let url = Bundle.main.url(forResource: "chart_bootstrap", withExtension: "js"),
           let js = try? String(contentsOf: url, encoding: .utf8) {
            return js
        }
        return nil
    }

    // MARK: - 产物信封（对齐 CloudAgentScriptTools companion）

    /// 信封标记键：CloudAgentChatStore 据此把 HTML 卡从工具结果中剥离上屏。
    static let artifactKey = "__artifact__"
    static let artifactHtmlCard = "html_card"

    /// 解析后的产物信封：cards = 本轮全部图卡（html, summary）；stats = 清洗后的统计 JSON。
    struct HtmlArtifactResult {
        let cards: [(html: String, summary: String)]
        let stats: String?
    }

    /// 产物信封（注册制）：cards 数组承载本轮全部图卡（调用即发卡，一稿可多卡）；
    /// 兼容单字段 html/summary（取最后一卡）；stats = 原 return 剥除 html 后的统计小对象。
    static func wrapHtmlArtifact(cards: [(html: String, summary: String)], stats: String?) -> String {
        guard let last = cards.last else { return "{}" }
        var envelope: [String: Any] = [
            artifactKey: artifactHtmlCard,
            "cards": cards.map { ["html": $0.html, "summary": $0.summary] },
            "html": last.html,
            "summary": last.summary,
        ]
        let cleaned = stats?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !cleaned.isEmpty && cleaned != "{}" && cleaned != "null" {
            if let data = cleaned.data(using: .utf8),
               let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                envelope["stats"] = obj
            } else {
                envelope["stats"] = cleaned
            }
        }
        let data = (try? JSONSerialization.data(withJSONObject: envelope)) ?? Data("{}".utf8)
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// 解析产物信封；非信封（普通 JSON/字符串结果）或空卡返回 nil。兼容无 cards 数组的旧信封。
    static func parseHtmlArtifact(_ result: String) -> HtmlArtifactResult? {
        guard let data = result.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              obj[artifactKey] as? String == artifactHtmlCard else { return nil }
        var cards: [(html: String, summary: String)] = []
        if let cardsArray = obj["cards"] as? [[String: Any]] {
            for item in cardsArray {
                guard let html = item["html"] as? String, !html.isEmpty else { continue }
                cards.append((html: html, summary: item["summary"] as? String ?? ""))
            }
        } else if let html = obj["html"] as? String, !html.isEmpty {
            cards.append((html: html, summary: obj["summary"] as? String ?? ""))
        }
        guard !cards.isEmpty else { return nil }
        let stats: String?
        if let raw = obj["stats"] {
            stats = raw is NSNull ? nil : stringifyJson(raw)
        } else {
            stats = nil
        }
        return HtmlArtifactResult(cards: cards, stats: stats)
    }

    // MARK: - 产物回收 + stats 清洗（对齐 drainArtifacts / stripHtml）

    /// 回收本轮注册的图卡产物 {html, summary}；注册表缺失/异常按无产物处理。
    private static func drainArtifacts(_ rt: JsRuntime) -> [(html: String, summary: String)] {
        guard let raw = rt.eval(script: "JSON.stringify(globalThis.__polangArtifacts||[])") as? JsValue.Str,
              let data = raw.value.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return []
        }
        return arr.compactMap { item in
            guard let html = item["html"] as? String, !html.isEmpty else { return nil }
            return (html: html, summary: item["summary"] as? String ?? "")
        }
    }

    /// 原 return 值递归剥除 html 后的 JSON（stats 上行用）：剥掉 `html` 键、及值为
    /// 「含 html 的 Obj」的 `chart` 键（真机事故形状 {chart: {html, summary}, total...}），
    /// 保证完整 HTML 绝不随 stats 上行 LLM。清洗后为空对象则返回 nil。
    private static func statsJson(of result: JsValue) -> String? {
        let cleaned = stripHtml(result)
        if cleaned is JsValue.Null { return nil }
        let json = cleaned.toJson()
        return json == "{}" ? nil : json
    }

    private static func stripHtml(_ value: JsValue) -> JsValue {
        if let obj = value as? JsValue.Obj {
            var kept: [String: JsValue] = [:]
            var stripped = false
            for (key, item) in obj.entries {
                if key == "html" {
                    stripped = true
                    continue
                }
                if key == "chart",
                   let chartObj = item as? JsValue.Obj,
                   chartObj.entries["html"] is JsValue.Str {
                    stripped = true
                    continue
                }
                kept[key] = stripHtml(item)
            }
            return stripped ? JsValue.Obj(entries: kept) : obj
        }
        if let arr = value as? JsValue.Arr {
            var items: [JsValue] = []
            var stripped = false
            for item in arr.items {
                let cleaned = stripHtml(item)
                if cleaned !== item { stripped = true }
                items.append(cleaned)
            }
            return stripped ? JsValue.Arr(items: items) : arr
        }
        return value
    }

    // MARK: - i18n（__polangLang BCP47，contracts.md C3-2）

    /// 当前应用语言 → BCP47 标签（chart_bootstrap CHART_STRINGS 键：zh-CN/zh-TW/en/es/fr）。
    /// system → 按系统首选语言解析（zh-Hant/zh-HK/zh-MO → zh-TW，其余 zh* → zh-CN），
    /// 无法解析落 zh-CN（JS 侧未知标签亦回落 zh-CN，双保险）。
    static func bcp47LanguageTag() -> String {
        switch LanguageManager.shared.currentLanguage {
        case "english": return "en"
        case "chinese_simplified": return "zh-CN"
        case "chinese_traditional": return "zh-TW"
        case "spanish": return "es"
        case "french": return "fr"
        default:
            for preferred in Locale.preferredLanguages {
                let p = preferred.lowercased()
                if p.hasPrefix("zh-hant") || p.hasPrefix("zh-hk") || p.hasPrefix("zh-mo") { return "zh-TW" }
                if p.hasPrefix("zh") { return "zh-CN" }
                if p.hasPrefix("en") { return "en" }
                if p.hasPrefix("es") { return "es" }
                if p.hasPrefix("fr") { return "fr" }
            }
            return "zh-CN"
        }
    }

    // MARK: - 内部

    /// JS 字符串字面量安全引号（标签为白名单形态，防御性转义 \ 与 "）。
    private static func jsonQuoted(_ raw: String) -> String {
        let escaped = raw
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        return "\"\(escaped)\""
    }

    /// JSON 值回串（obj→紧凑 JSON；其他→description 兜底，与 Android opt().toString() 对齐）。
    private static func stringifyJson(_ raw: Any) -> String? {
        if let data = try? JSONSerialization.data(withJSONObject: raw),
           let str = String(data: data, encoding: .utf8) {
            return str
        }
        return "\(raw)"
    }
}
