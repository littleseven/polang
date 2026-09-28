import SwiftUI
import WebKit
import SharedKit

// MARK: - HTML 卡双形态（M5 B4，chat.yaml §13）

/// HTML_CARD 卡片：沙箱 WKWebView 渲染 + INLINE/FULLPAGE 双形态 + 测高终判。
///
/// 形态路由（chat.yaml §13 routing）：
/// - `display == "fullpage"` 声明 → 直判 FULLPAGE 预览（不测高）；
/// - INLINE 动态测高，首测 > 1.0×屏高 → 转 FULLPAGE 预览；
/// - 测高异常（≤0）→ 降级 INLINE；
/// - 终判（displayMode）持久化后形态不再跳变；判定 INLINE 后交互撑高不回转。
///
/// 终判经 [onDisplayFinalized] 回 `ChatViewModel.updateHtmlCardDisplay` 持久化
/// （`ChatMessage.with()` 无 htmlCardMeta 参 → make 重建保留 id/timestamp）。
struct HtmlCardView: View {
    let messageId: String
    let part: MessagePartHtmlCard
    let meta: HtmlCardMeta
    var onOpenFullpage: (String, String?) -> Void
    var onOpenLink: (URL) -> Void
    var onDisplayFinalized: (HtmlCardDisplayMode, Int32?) -> Void

    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    /// 卡底色（渐隐遮罩终点 = 运行时卡底色；对齐 pendingCardItem 的卡 chrome）
    private var cardBg: Color { Color(.secondarySystemBackground) }

    /// INLINE 最小高（spec：短卡 ≥120dp，dp≈pt）
    private static let minInlineHeight: CGFloat = 120
    /// FULLPAGE 预览固定高（spec：≈0.5 屏）
    private var previewHeight: CGFloat { UIScreen.main.bounds.height * 0.5 }
    /// 转 FULLPAGE 阈值（spec：测高 > 1.0×屏高强制 FULLPAGE；此处取全屏 bounds 高，
    /// 无安全区扣减——较 Android「可用屏高」阈值略偏宽松，差值 ≤ 底部安全区）
    private var fullpageThreshold: CGFloat { UIScreen.main.bounds.height }

    /// "messageId:partId" → 测高缓存（LazyVStack 复建时防跳变；iOS 无 Android 滑动冻结
    /// 直通，以缓存 + 防抖兜底——平台差异，见 M5 gap 报告）。键必须复合 messageId：
    /// 持久轨产物行 partId 恒 "p0"，单以 partId 为键 = 全 App 单槽跨消息互相污染。
    private static var heightCache: [String: CGFloat] = [:]

    /// 测高缓存键：复合 messageId（见 [heightCache] 注释）
    private var heightCacheKey: String { "\(messageId):\(part.partId)" }

    /// 运行时判定形态（init 时按声明初判；测高后升级）
    @State private var judgedMode: CardMode
    @State private var measuredHeight: CGFloat? = nil
    @State private var renderFailed = false

    enum CardMode { case inline, fullpagePreview }

    init(
        messageId: String,
        part: MessagePartHtmlCard,
        meta: HtmlCardMeta,
        onOpenFullpage: @escaping (String, String?) -> Void,
        onOpenLink: @escaping (URL) -> Void,
        onDisplayFinalized: @escaping (HtmlCardDisplayMode, Int32?) -> Void
    ) {
        self.messageId = messageId
        self.part = part
        self.meta = meta
        self.onOpenFullpage = onOpenFullpage
        self.onOpenLink = onOpenLink
        self.onDisplayFinalized = onDisplayFinalized
        // 初判：display 声明 fullpage 直判预览；displayMode 已终判（持久化重渲染）从其值
        let initial: CardMode
        if let finalized = meta.displayMode {
            initial = finalized == .fullpage ? .fullpagePreview : .inline
        } else {
            initial = meta.display?.lowercased() == "fullpage" ? .fullpagePreview : .inline
        }
        _judgedMode = State(initialValue: initial)
    }

    /// 生效形态：终判（持久化）优先，运行时判定兜底
    private var mode: CardMode {
        if let finalized = meta.displayMode {
            return finalized == .fullpage ? .fullpagePreview : .inline
        }
        return judgedMode
    }

    var body: some View {
        Group {
            if mode == .fullpagePreview {
                fullpagePreview
            } else {
                inlineForm
            }
        }
        .background(cardBg)
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.card))
        .frame(maxWidth: ChatBubbleTokens.bubbleMaxWidth, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("chat_html_card")
        .onAppear { finalizeDeclaredFullpageIfNeeded() }
    }

    // MARK: INLINE（短卡：动态测高完全撑开，卡内直接交互仅 a 进落地页）

    private var inlineForm: some View {
        Group {
            if renderFailed {
                // 渲染失败 → 原生封面兜底（summary + 占位说明）
                nativeCover(showHint: false)
            } else {
                htmlWebView(
                    scrollEnabled: false, showIndicators: false, interactive: true,
                    measure: true, onHeight: { handleMeasuredHeight($0) })
                    .frame(height: inlineHeight)
            }
        }
    }

    /// INLINE 动态高：终判测高 → 本次实测 → partId 缓存 → 最小高兜底
    private var inlineHeight: CGFloat {
        // meta.measuredHeightPx 为 KotlinInt?（Kotlin/Native 装箱边界），int32Value 落地
        if let stored = meta.measuredHeightPx {
            return CGFloat(max(stored.int32Value, Int32(Self.minInlineHeight)))
        }
        if let measured = measuredHeight { return measured }
        if let cached = Self.heightCache[heightCacheKey] { return cached }
        return Self.minInlineHeight
    }

    // MARK: FULLPAGE 预览（≈0.5 屏 + WebView 渲染上半部 + 底部渐隐 + 提示条）

    private var fullpagePreview: some View {
        ZStack(alignment: .bottom) {
            if renderFailed {
                // 渲染失败 → 原生封面（summary + 提示条）
                nativeCover(showHint: true)
            } else {
                htmlWebView(
                    scrollEnabled: false, showIndicators: false, interactive: false,
                    measure: false, onHeight: nil)
                    .frame(height: previewHeight)

                // 底部 120pt 渐隐遮罩（向运行时卡底色）
                LinearGradient(
                    colors: [cardBg.opacity(0), cardBg],
                    startPoint: .top, endPoint: .bottom
                )
                .frame(height: 120)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .allowsHitTesting(false)

                // 居中提示条（primary 色）
                VStack {
                    Spacer()
                    Text(String(localized: "chat.html_card_view_full"))
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(s.primary)
                        .padding(.bottom, 44)
                }
                .allowsHitTesting(false)

                // 透明触控层：消费点击不消费竖拖（onTapGesture 不占 pan，外层列表仍可滚）
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture { onOpenFullpage(part.html, meta.summary) }
            }
        }
        .frame(height: previewHeight)
    }

    /// 渲染失败原生封面兜底（summary 文案 + 可选提示条）
    private func nativeCover(showHint: Bool) -> some View {
        VStack(spacing: Spacing.sm) {
            Image(systemName: "doc.richtext")
                .font(.system(size: 18))
                .foregroundColor(Color(.secondaryLabel))
            Text(meta.summary ?? String(localized: "chat.html_card_generated"))
                .font(.system(size: 14))
                .foregroundColor(Color(.label))
                .lineLimit(4)
            if showHint {
                Text(String(localized: "chat.html_card_view_full"))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(s.primary)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: showHint ? previewHeight : Self.minInlineHeight)
        .padding(Spacing.md)
    }

    // MARK: 测高 + 终判

    /// 测高回调（ResizeObserver → JS 2px 防抖 → 本侧 clamp）：
    /// - 布局前虚高拦截：clamp 到 [120, 3×屏高]；
    /// - 首 > 1.0 屏 → 转 FULLPAGE 终判；首有效 ≤1.0 屏 → INLINE 终判（此后撑高不回转）；
    /// - 异常（≤0）忽略（降级保持 INLINE）。
    private func handleMeasuredHeight(_ raw: CGFloat) {
        guard raw > 0 else { return }
        let clamped = min(max(CGFloat(raw), Self.minInlineHeight), UIScreen.main.bounds.height * 3)
        measuredHeight = clamped
        if Self.heightCache.count > 128 { Self.heightCache.removeAll() }
        Self.heightCache[heightCacheKey] = clamped
        finalizeInlineIfNeeded(measured: clamped)
    }

    /// 声明 fullpage 直判终判（displayMode 为空且 display == "fullpage"，onAppear 一次）
    private func finalizeDeclaredFullpageIfNeeded() {
        guard meta.displayMode == nil, judgedMode == .fullpagePreview else { return }
        judgedMode = .fullpagePreview
        onDisplayFinalized(.fullpage, nil)
    }

    /// INLINE 终判（未终判且非声明直判时；首有效测高一次）
    private func finalizeInlineIfNeeded(measured: CGFloat) {
        guard meta.displayMode == nil else { return }
        if measured > fullpageThreshold {
            judgedMode = .fullpagePreview
            onDisplayFinalized(.fullpage, Int32(measured))
        } else {
            // 已在 INLINE：终判 INLINE，此后交互撑高不再转换
            onDisplayFinalized(.inline, Int32(measured))
        }
    }

    // MARK: 沙箱 WebView（INLINE/预览/查看器共用）

    private func htmlWebView(
        scrollEnabled: Bool,
        showIndicators: Bool,
        interactive: Bool,
        measure: Bool,
        onHeight: ((CGFloat) -> Void)?
    ) -> some View {
        HtmlCardWebView(
            html: part.html,
            scrollEnabled: scrollEnabled,
            showIndicators: showIndicators,
            interactive: interactive,
            measure: measure,
            allowNavigation: false,
            onHeight: onHeight,
            onLink: onOpenLink,
            onFail: { renderFailed = true }
        )
    }
}

// MARK: - 沙箱 WebView（HtmlCardView / HtmlFullpageViewer / HtmlLinkPreviewOverlay 共用）

/// WKWebView 沙箱（chat.yaml §13 lockdown + render_template）：
/// - `websiteDataStore = .nonPersistent()`（禁 DOM Storage）、零文件访问（baseURL about:blank）；
/// - 零 JS 桥——`htmlCardHeight` message handler 是唯一 sanctioned 例外（仅 measure = true）；
/// - 导航策略：`allowNavigation = false`（卡片/查看器）只放行首个 main-frame 加载，
///   `a` 点击 http/https → [onLink] 回宿主开落地页、卡片自身永不导航；
///   `allowNavigation = true`（链接落地页）放行 http/https 自由浏览；
/// - 渲染文档经 `wrapHtmlDocument`（viewport meta + 响应式 reset 注入）。
struct HtmlCardWebView: UIViewRepresentable {
    let html: String
    /// 加载远程 URL（链接落地页叠层用）；nil = 加载 html 本体
    var loadURL: URL? = nil
    var scrollEnabled: Bool
    var showIndicators: Bool
    var interactive: Bool
    /// 注入测高 JS + htmlCardHeight handler（INLINE 卡专属）
    var measure: Bool
    /// 放行后续 http/https 导航（链接落地页自由浏览用）
    var allowNavigation: Bool
    var onHeight: ((CGFloat) -> Void)? = nil
    var onLink: ((URL) -> Void)? = nil
    var onFail: (() -> Void)? = nil
    /// main-frame 加载完成（落地页标题随导航更新用）
    var onFinish: ((URL?) -> Void)? = nil

    static let heightHandlerName = "htmlCardHeight"

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        // 禁 DOM Storage / Cookie / Cache（会话级零持久化）
        config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false

        let wv = WKWebView(frame: .zero, configuration: config)
        wv.scrollView.isScrollEnabled = scrollEnabled
        wv.scrollView.showsVerticalScrollIndicator = showIndicators
        wv.scrollView.showsHorizontalScrollIndicator = false
        wv.isOpaque = false
        wv.backgroundColor = .clear
        wv.isUserInteractionEnabled = interactive

        if measure {
            // sanctioned 例外：测高 handler + ResizeObserver（JS 侧 2px 阈值防抖）
            config.userContentController.add(
                context.coordinator.weakProxy, name: Self.heightHandlerName)
            config.userContentController.addUserScript(WKUserScript(
                source: Self.measureJS, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        }

        wv.navigationDelegate = context.coordinator
        context.coordinator.parent = self
        context.coordinator.loadedKey = loadURL?.absoluteString ?? html
        if let url = loadURL {
            wv.load(URLRequest(url: url))
        } else {
            wv.loadHTMLString(Self.wrapHtmlDocument(html), baseURL: URL(string: "about:blank"))
        }
        return wv
    }

    func updateUIView(_ wv: WKWebView, context: Context) {
        context.coordinator.parent = self
        // 同一卡实例 html/url 语义不变（不可变模型），仅防御性重载
        let key = loadURL?.absoluteString ?? html
        if context.coordinator.loadedKey != key {
            context.coordinator.loadedKey = key
            if let url = loadURL {
                wv.load(URLRequest(url: url))
            } else {
                wv.loadHTMLString(Self.wrapHtmlDocument(html), baseURL: URL(string: "about:blank"))
            }
        }
        wv.scrollView.isScrollEnabled = scrollEnabled
        wv.isUserInteractionEnabled = interactive
    }

    static func dismantleUIView(_ wv: WKWebView, coordinator: Coordinator) {
        wv.configuration.userContentController.removeScriptMessageHandler(
            forName: Self.heightHandlerName)
        wv.navigationDelegate = nil
    }

    // MARK: Coordinator（导航策略 + 测高消息）

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var parent: HtmlCardWebView
        /// 首个 main-frame 加载已放行（此后卡片自身不再导航）
        var loadedInitial = false
        var loadedKey: String = ""

        init(_ parent: HtmlCardWebView) { self.parent = parent }

        /// 弱代理包装：userContentController 强持 handler，避免持死 representable
        private weak var weakDelegate: WeakScriptMessageDelegate?
        var weakProxy: WeakScriptMessageDelegate {
            if let weakDelegate { return weakDelegate }
            let proxy = WeakScriptMessageDelegate(self)
            weakDelegate = proxy
            return proxy
        }

        func webView(
            _ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
            preferences: WKWebpagePreferences,
            decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void
        ) {
            let type = navigationAction.navigationType
            if type == .linkActivated {
                // 链接落地页（allowNavigation）：http/https 页内自由导航，其余 scheme 拒
                if parent.allowNavigation {
                    let isWeb = navigationAction.request.url.map(Self.isWebURL) ?? false
                    decisionHandler(isWeb ? .allow : .cancel, preferences)
                    return
                }
                // a 点击（卡片/查看器）：回宿主开落地页，本卡永不导航
                if let url = navigationAction.request.url, Self.isWebURL(url) {
                    parent.onLink?(url)
                }
                decisionHandler(.cancel, preferences)
                return
            }
            // sanitize 已剔 form，防御：表单提交一律拒
            if type == .formSubmitted || type == .formResubmitted {
                decisionHandler(.cancel, preferences)
                return
            }
            if parent.allowNavigation {
                decisionHandler(.allow, preferences)
                return
            }
            // 只放行首个 main-frame 加载（loadHTMLString 本体），其余（重定向/刷新等）取消
            if !loadedInitial, navigationAction.targetFrame?.isMainFrame == true {
                loadedInitial = true
                decisionHandler(.allow, preferences)
                return
            }
            decisionHandler(.cancel, preferences)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            // main-frame 加载失败（首载 navigation==nil 时走 didFailProvisional）
            parent.onFail?()
        }

        func webView(
            _ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!,
            withError error: Error
        ) {
            parent.onFail?()
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.onFinish?(webView.url)
        }

        func userContentController(
            _ userContentController: WKUserContentController, didReceive message: WKScriptMessage
        ) {
            guard message.name == HtmlCardWebView.heightHandlerName,
                  let height = message.body as? NSNumber else { return }
            parent.onHeight?(height.doubleValue)
        }

        static func isWebURL(_ url: URL) -> Bool {
            let scheme = url.scheme?.lowercased() ?? ""
            return scheme == "http" || scheme == "https"
        }
    }
}

/// WKScriptMessageHandler 弱代理（userContentController.add 强持 handler 的标准解法）。
final class WeakScriptMessageDelegate: NSObject, WKScriptMessageHandler {
    private weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(
        _ userContentController: WKUserContentController, didReceive message: WKScriptMessage
    ) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

// MARK: - 文档包装 + 测高 JS

extension HtmlCardWebView {

    /// render_template（chat.yaml §13）：完整文档原样加载（向 head 注 viewport +
    /// 响应式 reset）；片段包完整文档壳。
    static func wrapHtmlDocument(_ raw: String) -> String {
        let inject = """
        <meta name="viewport" content="width=device-width, initial-scale=1"><style>html,body{margin:0;padding:0;max-width:100%;overflow-x:hidden;}img,video,table,pre{max-width:100%;}table{border-collapse:collapse;}td,th{padding:4px 6px;}</style>
        """
        if raw.lowercased().contains("<html") {
            if let range = raw.range(of: "<head>", options: .caseInsensitive) {
                return raw.replacingCharacters(in: range, with: "<head>\(inject)")
            }
            // 无 head：注入到文档前（浏览器容错移入 head）
            return inject + raw
        }
        return "<!DOCTYPE html><html><head>\(inject)</head><body>\(raw)</body></html>"
    }

    /// 测高 JS（ResizeObserver + load/timeout 双触发；JS 侧 2px 阈值防抖，
    /// Swift 侧 clamp + partId 缓存兜底——见 HtmlCardView.handleMeasuredHeight）。
    static let measureJS = """
    (function(){
      var last = -1;
      function report(){
        var h = document.documentElement.scrollHeight;
        if (h > 0 && Math.abs(h - last) >= 2) {
          last = h;
          window.webkit.messageHandlers.htmlCardHeight.postMessage(h);
        }
      }
      if (window.ResizeObserver) { new ResizeObserver(report).observe(document.documentElement); }
      window.addEventListener('load', report);
      setTimeout(report, 50);
    })();
    """
}
