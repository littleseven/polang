import Foundation
import WebKit

// MARK: - HTML 卡预制 JS 库声明式注入层（ios-follow 2026-11-10，对齐 Android LocalWebViewLibs）

/// 契约（CHAT_CARD_CATALOG.md §4.2 + tmp/ios-follow/ios-follow-batch-20261110/contracts.md C2）：
/// 卡 HTML 写 `<meta name="polang-lib" content="echarts">` 声明依赖（meta 过 HtmlCardSanitizer
/// 不剔——只剔 meta refresh）；渲染期 [injectDeclaredLibs] 把声明展开为 `<script src>`，
/// 请求经 [LocalLibSchemeHandler] 从 app bundle 开流（版本随包钉死，离线可用）。
///
/// 三道闸（LLM 无法借声明引入任意脚本）：
/// 1. 声明名 → 资产是**闭集映射**（[libs]），未知库名静默忽略；
/// 2. 直接手写 `<script src=...>` 会被 sanitizer 整段剔除，声明式是唯一通路；
/// 3. 注入只发生在渲染期（[HtmlCardWebView.wrapHtmlDocument]），落库 HTML 保持
///    meta 契约形态，URL 机制演进不影响已存消息。
///
/// 平台差异（台账）：Android 拦截 `https://polang-lib.invalid/...`（shouldInterceptRequest
/// 从 assets 开流）；iOS WKWebView 不可拦截 https → 自定义 scheme `polang-lib://bundle/...`
/// + WKURLSchemeHandler 从 bundle 开流。URL 为渲染期构造，双端落库形态一致（meta 声明）。
enum LocalWebViewLibs {

    /// 自定义 scheme（WKURLSchemeHandler 注册用；WKWebView 保留 scheme 不可用）。
    static let urlScheme = "polang-lib"

    /// scheme URL 的 host 段（路径形态对齐 Android `polang-lib.invalid` 的 urlPath）。
    private static let libHost = "bundle"

    private static let metaName = "polang-lib"

    /// 预制库定义：urlPath = scheme URL 路径段，resource = bundle 内资产名（polang-lib/ 子目录或 bundle 根双路径查找）。
    private struct LibDef {
        let urlPath: String
        let resourceName: String
        let resourceExtension: String
        let resourceSubdirectory: String
        let mimeType: String
        var url: URL? { URL(string: "\(urlScheme)://\(libHost)\(urlPath)") }
    }

    /// 预制库闭集：echarts = 5.6.0 全量 min 构建（约 1MB，双端字节一致）。
    private static let libs: [String: LibDef] = [
        "echarts": LibDef(
            urlPath: "/echarts/echarts.min.js",
            resourceName: "echarts.min",
            resourceExtension: "js",
            resourceSubdirectory: "polang-lib",
            mimeType: "application/javascript"
        ),
    ]

    private static let metaTagRegex = try! NSRegularExpression(pattern: #"<meta\b[^>]*>"#, options: [.caseInsensitive])
    private static let libNameAttrRegex = try! NSRegularExpression(pattern: #"name\s*=\s*["']polang-lib["']"#, options: [.caseInsensitive])
    private static let contentAttrRegex = try! NSRegularExpression(pattern: #"content\s*=\s*["']([^"']*)["']"#, options: [.caseInsensitive])
    private static let headOpenRegex = try! NSRegularExpression(pattern: #"<head[^>]*>"#, options: [.caseInsensitive])
    private static let bodyOpenRegex = try! NSRegularExpression(pattern: #"<body[^>]*>"#, options: [.caseInsensitive])

    /// 渲染期展开声明：`<head>` 开标签后注入（parser-blocking，卡内后续 inline script
    /// 可直接用 echarts 全局）；无 `<head>` 退 `<body>` 前，再退文档最前。
    /// 幂等——URL 已在文档中时不重复注入（同 html 重复处理/多 meta 声明同一库均不叠加）。
    static func injectDeclaredLibs(_ html: String) -> String {
        let scriptTags = declaredLibs(html)
            .compactMap { libs[$0]?.url }
            .filter { !html.contains($0.absoluteString) }
            .map { "<script src=\"\($0.absoluteString)\"></script>" }
            .joined()
        guard !scriptTags.isEmpty else { return html }
        if let match = firstMatch(headOpenRegex, in: html) {
            return inject(html: html, tag: scriptTags, at: match.upperBound)
        }
        if let match = firstMatch(bodyOpenRegex, in: html) {
            return inject(html: html, tag: scriptTags, at: match.lowerBound)
        }
        return scriptTags + html
    }

    /// 解析全部 `<meta name="polang-lib" content="...">` 声明（content 逗号/空格分隔多库），
    /// 闭集过滤 + 去重保序；无声明或全部未知 → 空表。属性顺序（name/content 先后）不敏感。
    static func declaredLibs(_ html: String) -> [String] {
        guard html.contains(metaName) else { return [] }
        var seen = Set<String>()
        var result: [String] = []
        for meta in allMatches(metaTagRegex, in: html) {
            let tag = String(meta)
            guard contains(libNameAttrRegex, in: tag),
                  let content = capturedGroup(contentAttrRegex, in: tag, group: 1) else { continue }
            for name in content.split(whereSeparator: { $0 == "," || $0 == " " }) {
                let key = name.trimmingCharacters(in: .whitespaces).lowercased()
                if libs[key] != nil && seen.insert(key).inserted {
                    result.append(key)
                }
            }
        }
        return result
    }

    /// 库资产 bundle URL（scheme handler 开流 + 测试断言共用口径；双路径查找）。
    static func bundledLibURL(name: String) -> URL? {
        guard let lib = libs[name] else { return nil }
        if let url = Bundle.main.url(
            forResource: lib.resourceName, withExtension: lib.resourceExtension,
            subdirectory: lib.resourceSubdirectory) {
            return url
        }
        return Bundle.main.url(
            forResource: lib.resourceName, withExtension: lib.resourceExtension)
    }

    static func mimeType(name: String) -> String {
        libs[name]?.mimeType ?? "application/octet-stream"
    }

    // MARK: - 内部（NSRegularExpression 包装）

    private static func inject(html: String, tag: String, at index: String.Index) -> String {
        var result = html
        result.insert(contentsOf: tag, at: index)
        return result
    }

    private static func firstMatch(
        _ regex: NSRegularExpression, in text: String
    ) -> (lowerBound: String.Index, upperBound: String.Index)? {
        let ns = text as NSString
        guard let m = regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: ns.length)),
              let range = Range(m.range, in: text) else { return nil }
        return (range.lowerBound, range.upperBound)
    }

    /// 提取首个匹配的指定捕获组（1-based）。
    private static func capturedGroup(
        _ regex: NSRegularExpression, in text: String, group: Int
    ) -> String? {
        let ns = text as NSString
        guard let m = regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: ns.length)),
              m.numberOfRanges > group,
              let range = Range(m.range(at: group), in: text) else { return nil }
        return String(text[range])
    }

    private static func allMatches(_ regex: NSRegularExpression, in text: String) -> [Substring] {
        let ns = text as NSString
        return regex.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length))
            .compactMap { Range($0.range, in: text).map { text[$0] } }
    }

    private static func contains(_ regex: NSRegularExpression, in text: String) -> Bool {
        let ns = text as NSString
        return regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: ns.length)) != nil
    }
}

// MARK: - WKURLSchemeHandler（polang-lib:// → bundle 资产开流）

/// `polang-lib://` 请求 → bundle 内预制库资产。对齐 Android `LocalWebViewLibs.intercept`
/// （shouldInterceptRequest 开流语义）：只服务闭集路径，其余 fail；task 回调至多一次
/// （WKURLSchemeTask 契约：didReceiveResponse → didReceiveData → didFinish，不持 task 引用）。
final class LocalLibSchemeHandler: NSObject, WKURLSchemeHandler {

    static let shared = LocalLibSchemeHandler()

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url,
              url.scheme?.lowercased() == LocalWebViewLibs.urlScheme else {
            urlSchemeTask.didFailWithError(URLError(.unsupportedURL))
            return
        }
        // 路径闭集匹配（host 仅形态占位，不参与语义）：polang-lib://bundle<urlPath>
        guard let lib = LocalWebViewLibs.libsForPath(url.path).first else {
            urlSchemeTask.didFailWithError(URLError(.unsupportedURL))
            return
        }
        guard let assetURL = LocalWebViewLibs.bundledLibURL(name: lib.name),
              let data = try? Data(contentsOf: assetURL) else {
            NSLog("[PoLang:WebViewLibs] bundled lib asset missing: %@", lib.name)
            urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        let response = URLResponse(
            url: url,
            mimeType: LocalWebViewLibs.mimeType(name: lib.name),
            expectedContentLength: data.count,
            textEncodingName: "utf-8")
        urlSchemeTask.didReceive(response)
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        // 无流式状态可停（一次性整段开流；task 由 WKWebView 持有，不重入回调）
    }
}

private extension LocalWebViewLibs {
    /// URL path → 闭集库名（urlPath 精确匹配；path 形如 "/echarts/echarts.min.js"）。
    static func libsForPath(_ path: String) -> [(name: String, def: LibDef)] {
        libs.filter { $0.value.urlPath == path }.map { ($0.key, $0.value) }
    }
}
