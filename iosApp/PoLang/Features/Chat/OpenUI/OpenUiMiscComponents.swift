import SwiftUI
import WebKit
import SharedKit

// MARK: - OpenUI 杂项组件（Progress/Tag/ListBlock/ListItem/Carousel/卡块族/Html 沙箱叶）

struct OpenUiProgressComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let value = min(max(ouNumber(OpenUiPropsBag(node: node).raw("value")) ?? 0, 0), 100)
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color(.label).opacity(0.08))
                Capsule()
                    .fill(Color.accentColor)
                    .frame(width: max(4, geo.size.width * CGFloat(value / 100)))
            }
        }
        .frame(height: 4)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Tag / TagBlock

struct OpenUiTagComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let colors = tagColors(props)
        let size = ouText(props.raw("size")) ?? "md"
        let fontSize: CGFloat = size == "sm" ? 11 : (size == "lg" ? 14 : 12)
        let padH: CGFloat = size == "sm" ? 8 : (size == "lg" ? 14 : 10)
        let padV: CGFloat = size == "sm" ? 3 : (size == "lg" ? 7 : 4)
        HStack(spacing: 4) {
            if let iconName = ouText(props.raw("icon")) {
                Image(systemName: OpenUiTokens.systemImage(named: iconName))
                    .font(.system(size: fontSize - 1))
            }
            Text(ouText(props.raw("text")) ?? "")
                .font(.system(size: fontSize))
                .lineLimit(1)
        }
        .foregroundColor(colors.fg)
        .padding(.horizontal, padH)
        .padding(.vertical, padV)
        .background(colors.bg)
        .clipShape(Capsule())
    }

    /// tone 实心档优先（状态 chip 语义）；缺省走 variant 容器档。
    private func tagColors(_ props: OpenUiPropsBag) -> (fg: Color, bg: Color) {
        if let tone = ouText(props.raw("tone")) {
            switch tone {
            case "primary": return (.white, .accentColor)
            case "error": return (.white, .red)
            default: return (Color(.label), Color(.secondarySystemBackground))
            }
        }
        let toneColors = OpenUiTokens.tone(ouText(props.raw("variant")))
        return (toneColors.fg, toneColors.bg)
    }
}

struct OpenUiTagBlockComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let tags = ouItems(OpenUiPropsBag(node: node).raw("tags")).compactMap { ouText($0) }
        FlowLayout(horizontal: 6, vertical: 6) {
            ForEach(Array(tags.enumerated()), id: \.offset) { _, tag in
                Text(tag)
                    .font(.system(size: 12))
                    .foregroundColor(Color(.label).opacity(0.8))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(Capsule())
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - ListBlock / ListItem（经典列表行；前件三选一 photo/image/icon）

struct OpenUiListBlockComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let items = ouItems(props.raw("items")).map { ouObject($0) }
        let numberVariant = ouText(props.raw("variant")) != "image"
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, obj in
                if index > 0 { Divider().opacity(0.3) }
                OpenUiRowEntry(
                    title: ouText(obj["title"]) ?? "",
                    subtitle: ouText(obj["subtitle"]),
                    photoId: nil,
                    remoteImage: numberVariant ? nil : ouText(obj["image"]),
                    iconName: nil,
                    trailing: ouText(obj["actionLabel"]),
                    trailingAction: ouActions(obj["action"]),
                    ordinal: numberVariant ? index + 1 : nil,
                    ctx: ctx)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiListItemComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        OpenUiRowEntry(
            title: ouText(props.raw("title")) ?? "",
            subtitle: ouText(props.raw("subtitle")),
            photoId: ouNumber(props.raw("photo")).map(Int64.init),
            remoteImage: ouText(props.raw("image")),
            iconName: ouText(props.raw("icon")),
            trailing: ouText(props.raw("trailing")) ?? ouText(props.raw("actionLabel")),
            trailingAction: ouActions(props.raw("action")),
            ordinal: nil,
            ctx: ctx)
    }
}

/// 列表行通用件：前件（photo > image > icon 徽章）+ 标题副题 + 尾部弱色小字/动作。
struct OpenUiRowEntry: View {
    let title: String
    let subtitle: String?
    var photoId: Int64?
    var remoteImage: String?
    var iconName: String?
    var trailing: String?
    var trailingAction: [OpenUiAction]
    var ordinal: Int?
    let ctx: OpenUiRenderContext

    @State private var localId: String? = nil

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            leadingView
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14))
                    .foregroundColor(Color(.label))
                    .lineLimit(1)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundColor(Color(.secondaryLabel))
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            if let trailing, !trailing.isEmpty {
                if trailingAction.isEmpty {
                    Text(trailing)
                        .font(.system(size: 12))
                        .foregroundColor(Color(.tertiaryLabel))
                        .lineLimit(1)
                } else {
                    Button {
                        ctx.onAction(trailingAction)
                    } label: {
                        HStack(spacing: 2) {
                            Text(trailing)
                                .font(.system(size: 12))
                            Image(systemName: "chevron.right")
                                .font(.system(size: 10, weight: .semibold))
                        }
                        .foregroundColor(.accentColor)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.vertical, 8)
        .task(id: photoId) {
            if let photoId, let seam = IosAgentComposition.shared.openUiMediaSeam {
                localId = try? await seam.resolveUri(id: photoId)
            }
        }
    }

    @ViewBuilder private var leadingView: some View {
        if let localId {
            OpenUiLocalPhoto(localIdentifier: localId, aspect: 1, cornerRadius: 8,
                             onTap: { [ctx] in ctx.onPhotoTap(localId) })
                .frame(width: OpenUiTokens.listLeadingImageSize, height: OpenUiTokens.listLeadingImageSize)
        } else if let photoId, localId == nil {
            Color(.tertiarySystemBackground)
                .frame(width: OpenUiTokens.listLeadingImageSize, height: OpenUiTokens.listLeadingImageSize)
                .clipShape(RoundedRectangle(cornerRadius: 8))
        } else if let remoteImage, let url = URL(string: remoteImage) {
            AsyncImage(url: url) { phase in
                if case .success(let image) = phase {
                    image.resizable().scaledToFill()
                } else {
                    Color(.tertiarySystemBackground)
                }
            }
            .frame(width: OpenUiTokens.listLeadingImageSize, height: OpenUiTokens.listLeadingImageSize)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        } else if let iconName {
            Image(systemName: OpenUiTokens.systemImage(named: iconName))
                .font(.system(size: 16))
                .foregroundColor(Color(.secondaryLabel))
                .frame(width: OpenUiTokens.listLeadingImageSize, height: OpenUiTokens.listLeadingImageSize)
                .background(Color(.secondarySystemBackground))
                .clipShape(Circle())
        } else if let ordinal {
            Text("\(ordinal)")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(Color(.secondaryLabel))
                .frame(width: 24, alignment: .leading)
        }
    }
}

// MARK: - Carousel（横滑卡片条；variant card/sunk）

struct OpenUiCarouselComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let children = ouChildren(props)
        let sunk = ouText(props.raw("variant")) == "sunk"
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 10) {
                OpenUiChildren(children, ctx: ctx)
                    .padding(10)
                    .background(sunk ? Color(.tertiarySystemBackground) : Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 卡块族（Snippet/Overview/Context/Composite/Visual + Block/Item 双形态 + grid/carousel 布局）

enum OpenUiCardBlockKind {
    case snippet, overview, context, composite, visual
}

struct OpenUiCardBlockComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext
    let kind: OpenUiCardBlockKind

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let items = ouItems(props.raw("items"))
        let isCarousel = ouText(props.raw("layout")) == "carousel"
        let blockAction = ouActions(props.raw("action"))
        let itemViews = items.map { OpenUiCardBlockItemView(item: $0, kind: kind, ctx: ctx) }
        return Group {
            if isCarousel {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 10) { ForEach(Array(itemViews.enumerated()), id: \.offset) { _, v in v } }
                }
            } else {
                VStack(alignment: .leading, spacing: 10) { ForEach(Array(itemViews.enumerated()), id: \.offset) { _, v in v } }
            }
        }
        .onTapGesture {
            if !blockAction.isEmpty { ctx.onAction(blockAction) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiCardBlockItemComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext
    let kind: OpenUiCardBlockKind

    private static let itemKeys = ["lhs", "rhs", "top", "bottom", "title", "body",
                                   "header", "footer", "tag", "bgImageSrc", "bgPhoto"]

    var body: some View {
        // Item 形保底：本组件 props 重组为对象条目交共享渲染件
        let entries = node.props.filter { Self.itemKeys.contains($0.name) }
        OpenUiCardBlockItemView(item: IosOpenUiValObjVal(entries: entries), kind: kind, ctx: ctx)
    }
}

/// 单卡条渲染（按 kind 分流字段形态）。
struct OpenUiCardBlockItemView: View {
    let item: IosOpenUiVal
    let kind: OpenUiCardBlockKind
    let ctx: OpenUiRenderContext

    private var obj: [String: IosOpenUiVal] { ouObject(item) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            switch kind {
            case .snippet:
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    childrenOf("lhs")
                    Spacer(minLength: 4)
                    childrenOf("rhs")
                }
            case .overview:
                VStack(alignment: .leading, spacing: 6) {
                    childrenOf("top")
                    childrenOf("bottom")
                }
            case .context:
                VStack(alignment: .leading, spacing: 4) {
                    Text(ouText(obj["title"]) ?? "")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(Color(.label))
                    if let body = ouText(obj["body"]) {
                        Text(body)
                            .font(.system(size: 13))
                            .foregroundColor(Color(.secondaryLabel))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            case .composite:
                VStack(alignment: .leading, spacing: 6) {
                    childrenOf("header")
                    childrenOf("body")
                    childrenOf("footer")
                }
            case .visual:
                OpenUiVisualCardItem(obj: obj, ctx: ctx)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    @ViewBuilder private func childrenOf(_ key: String) -> some View {
        let items = ouItems(obj[key]).filter { $0 is IosOpenUiValNodeVal || $0 is IosOpenUiValUnknownNodeVal }
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                OpenUiChildren(items, ctx: ctx)
            }
        }
    }
}

/// 题图卡条（VisualCardItem）：bgPhoto/bgImageSrc 题图 + tag 徽标 + body 白字。
private struct OpenUiVisualCardItem: View {
    let obj: [String: IosOpenUiVal]
    let ctx: OpenUiRenderContext

    @State private var localId: String? = nil

    var body: some View {
        let bgPhoto = ouNumber(obj["bgPhoto"]).map(Int64.init)
        ZStack(alignment: .bottomLeading) {
            if let bgPhoto {
                Group {
                    if let localId {
                        OpenUiLocalPhoto(localIdentifier: localId, aspect: 16.0 / 9.0,
                                         cornerRadius: 0, onTap: { [ctx] in ctx.onPhotoTap(localId) })
                    } else {
                        Color(.tertiarySystemBackground)
                    }
                }
                .task(id: bgPhoto) {
                    if let seam = IosAgentComposition.shared.openUiMediaSeam {
                        localId = try? await seam.resolveUri(id: bgPhoto)
                    }
                }
            } else if let src = ouText(obj["bgImageSrc"]), let url = URL(string: src) {
                AsyncImage(url: url) { phase in
                    if case .success(let image) = phase {
                        image.resizable().scaledToFill()
                    } else {
                        Color(.tertiarySystemBackground)
                    }
                }
            } else {
                Color(.tertiarySystemBackground)
            }
            LinearGradient(colors: [.black.opacity(0.6), .black.opacity(0)], startPoint: .bottom, endPoint: .center)
            VStack(alignment: .leading, spacing: 4) {
                if let tag = ouText(obj["tag"]) {
                    Text(tag)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.black.opacity(0.35))
                        .clipShape(Capsule())
                }
                Text(ouText(obj["body"]) ?? "")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.white)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(10)
        }
        .aspectRatio(16.0 / 9.0, contentMode: .fill)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - Html（L2 沙箱叶：WKWebView 内联渲染，外链导航拦截）

struct OpenUiHtmlComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let html = ouText(OpenUiPropsBag(node: node).raw("html")) ?? ""
        OpenUiHtmlLeaf(html: html)
            .frame(minHeight: 80)
            .frame(maxWidth: .infinity)
    }
}

/// WKWebView 沙箱：自包含 HTML 内联渲染；除 about:blank 外一切导航拦截（网络资源放行，
/// 外链跳转收口——Android 全屏查看器回调 M1 未接同口径）。
struct OpenUiHtmlLeaf: UIViewRepresentable {
    let html: String

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.scrollView.isScrollEnabled = false
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.navigationDelegate = context.coordinator
        webView.loadHTMLString(html, baseURL: nil)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // 仅当内容变化时重载（视图复用）
        if context.coordinator.loadedHtml != html {
            context.coordinator.loadedHtml = html
            webView.loadHTMLString(html, baseURL: nil)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var loadedHtml: String? = nil

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            // 放行首次 loadHTMLString（about:blank 目标）；拦截其余导航（a 外链点击不跳出）
            if navigationAction.navigationType == .other {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }
    }
}
