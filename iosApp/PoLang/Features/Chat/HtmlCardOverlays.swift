import SwiftUI
import SharedKit

// MARK: - HTML 卡全屏查看器 + 链接落地页（M5 B4，chat.yaml §13）

/// 查看器载荷（ChatView overlay 态；根 ZStack 叠层呈现，非链式 fullScreenCover）。
struct HtmlFullpagePayload: Identifiable {
    let id: String
    let html: String
    let summary: String?
}

/// FULLPAGE 查看器（chat.yaml §13 fullpage_viewer）：顶栏（✕ + summary 截断 +
/// hairline）+ 竖滚 WebView（可交互、不测高）。卡内 `a` 点击 → [onOpenLink] 开
/// 落地页叠层（叠于本查看器之上）；渲染失败 → 错误占位（顶栏 ✕ 恒可关）。
struct HtmlFullpageViewer: View {
    let payload: HtmlFullpagePayload
    var onClose: () -> Void
    var onOpenLink: (URL) -> Void

    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    @State private var renderFailed = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Divider()
            if renderFailed {
                failurePlaceholder
            } else {
                HtmlCardWebView(
                    html: payload.html,
                    scrollEnabled: true, showIndicators: true, interactive: true,
                    measure: false, allowNavigation: false,
                    onLink: onOpenLink,
                    onFail: { renderFailed = true }
                )
            }
        }
        .background(s.background.ignoresSafeArea())
        .accessibilityIdentifier("chat_html_fullpage")
    }

    /// 顶栏：✕（44pt 热区）+ summary 单行截断（chat.yaml §13 viewer chrome）
    private var topBar: some View {
        HStack(spacing: Spacing.sm) {
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: TopBarTokens.iconSize, weight: .medium))
                    .foregroundColor(Color(.label))
                    .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("chat_html_fullpage_close")

            Text(payload.summary ?? String(localized: "chat.html_card_generated"))
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(Color(.label))
                .lineLimit(1)
                .truncationMode(.tail)

            Spacer(minLength: 0)
        }
        .frame(height: TopBarTokens.height)
        .padding(.horizontal, Spacing.xs)
    }

    /// 渲染失败占位（chat.html_card_preview_failed；顶栏 ✕ 关闭）
    private var failurePlaceholder: some View {
        VStack(spacing: Spacing.sm) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 20))
                .foregroundColor(Color(.secondaryLabel))
            Text(String(localized: "chat.html_card_preview_failed"))
                .font(.system(size: 14))
                .foregroundColor(Color(.secondaryLabel))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// 链接落地页叠层（viewer 之上）：✕ + host 标题 + http/https 页内自由导航。
/// 沙箱口径与卡片一致（nonPersistent store + 零原生 JS 桥）；标题随页内导航更新。
struct HtmlLinkPreviewOverlay: View {
    let url: URL
    var onClose: () -> Void

    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    @State private var currentHost: String?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: Spacing.sm) {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: TopBarTokens.iconSize, weight: .medium))
                        .foregroundColor(Color(.label))
                        .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("chat_html_link_close")

                Text(currentHost ?? url.host ?? url.absoluteString)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(Color(.label))
                    .lineLimit(1)
                    .truncationMode(.tail)

                Spacer(minLength: 0)
            }
            .frame(height: TopBarTokens.height)
            .padding(.horizontal, Spacing.xs)

            Divider()

            HtmlCardWebView(
                html: "",
                loadURL: url,
                scrollEnabled: true, showIndicators: true, interactive: true,
                measure: false, allowNavigation: true,
                onFinish: { finished in
                    if let host = finished?.host { currentHost = host }
                }
            )
        }
        .background(s.background.ignoresSafeArea())
        .accessibilityIdentifier("chat_html_link_overlay")
    }
}
