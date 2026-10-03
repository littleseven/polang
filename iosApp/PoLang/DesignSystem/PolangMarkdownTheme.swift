import SwiftUI
import MarkdownUI

// MARK: - PoLang Chat Markdown 主题（spec chat.yaml §5 agent_markdown 契约落地）

/// Agent 正文 Markdown 主题：AST → 原生组件（MarkdownUI / cmark-gfm 全量方言）。
/// 全部派生色以正文色为基准（§5 color_derivation：code_block_bg α0.08 / inline_code_bg α0.12 /
/// divider α0.20 / table_bg α0.02），深/浅宿主自适应，禁硬编码背景色。
/// 排版阶梯以正文 16/24 为基准（§5 typography + ChatBubbleTokens.textSize/textLineHeight）。
extension Theme {

    /// - Parameters:
    ///   - text: 正文色（派生基准；当前唯一宿主为常规表面传 onSurface，深色气泡宿主出现时传白）
    ///   - link: 链接色（token primary）
    static func polangChat(text: Color, link: Color) -> Theme {
        let body = ChatBubbleTokens.textSize          // 16
        // 行距换算说明：SwiftUI lineSpacing 叠在字体自然行高（SF ≈1.19em）之上，与
        // Android Compose lineHeight 总行高语义存在 ~0.19em 系统差；此处沿用 app 既有约定
        // （ChatView lineSpacing(24-16) / CodeBlockView 0.35em），与基线严格一致。
        // §5 正文行高 24/16 = 1.5 → 行距 0.5em
        let bodyLineSpacing: RelativeSize = .em(0.5)
        // §5 标题行高比 1.2 → 行距 0.2em
        let headingLineSpacing: RelativeSize = .em(0.2)

        // §5 typography.headings：h1 1.30 … h6 1.00 全 Bold
        @ViewBuilder
        func headingLabel(_ configuration: BlockConfiguration, scale: Double) -> some View {
            configuration.label
                .relativeLineSpacing(headingLineSpacing)
                .markdownMargin(top: .em(0.75), bottom: .em(0.25))
                .markdownTextStyle {
                    FontWeight(.bold)
                    FontSize(.em(scale))
                }
        }

        return Theme()
            .text {
                ForegroundColor(text)
                FontSize(body)
            }
            // §5 inline_code：等宽同字号 + 底色 text α0.12
            .code {
                FontFamilyVariant(.monospaced)
                BackgroundColor(text.opacity(0.12))
            }
            .strong {
                FontWeight(.bold)
            }
            // §5 link：primary + 下划线
            .link {
                ForegroundColor(link)
                UnderlineStyle(.single)
            }
            .heading1 { c in headingLabel(c, scale: 1.30) }
            .heading2 { c in headingLabel(c, scale: 1.22) }
            .heading3 { c in headingLabel(c, scale: 1.15) }
            .heading4 { c in headingLabel(c, scale: 1.10) }
            .heading5 { c in headingLabel(c, scale: 1.05) }
            .heading6 { c in headingLabel(c, scale: 1.00) }
            .paragraph { configuration in
                configuration.label
                    .fixedSize(horizontal: false, vertical: true)
                    .relativeLineSpacing(bodyLineSpacing)
                    .markdownMargin(top: .em(0), bottom: .em(0.5))
            }
            // §5 quote：italic（仅斜体，无左竖线——与 Android 表现对等）
            .blockquote { configuration in
                configuration.label
                    .markdownTextStyle { FontStyle(.italic) }
                    .relativePadding(.leading, length: .em(0.75))
                    .markdownMargin(top: .em(0.25), bottom: .em(0.5))
            }
            // MARKDOWN 段内缩进代码块（4 空格，分段器只抽围栏段）：与 CodeBlockView 同参数——
            // scale 0.80、行高比 1.35（行距 0.35em）、圆角 8、横向滚动不折行；
            // bg 挂在 ScrollView 上（横向 ScrollView 默认占满父宽）→ 底色全宽，与 CodeBlockView 一致
            .codeBlock { configuration in
                ScrollView(.horizontal, showsIndicators: false) {
                    configuration.label
                        .fixedSize(horizontal: true, vertical: true)
                        .relativeLineSpacing(.em(0.35))
                        .markdownTextStyle {
                            FontFamilyVariant(.monospaced)
                            FontSize(.em(0.80))
                        }
                        .padding(8)
                }
                .background(text.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .markdownMargin(top: .em(0), bottom: .em(0.5))
            }
            .listItem { configuration in
                configuration.label
                    .markdownMargin(top: .em(0.125))
            }
            // MARKDOWN 段内表格（分段器未抽出的边界情形）：描边 text α0.20、bg text α0.02、表头 Bold
            .table { configuration in
                configuration.label
                    .fixedSize(horizontal: false, vertical: true)
                    .markdownTableBorderStyle(.init(color: text.opacity(0.20)))
                    .markdownTableBackgroundStyle(TableBackgroundStyle { _, _ in text.opacity(0.02) })
                    .markdownMargin(top: .em(0), bottom: .em(0.5))
            }
            .tableCell { configuration in
                configuration.label
                    .markdownTextStyle {
                        if configuration.row == 0 {
                            FontWeight(.bold)
                        }
                        BackgroundColor(nil)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(6)
                    .relativeLineSpacing(.em(0.25))
            }
            // §5 divider：text α0.20
            .thematicBreak {
                Divider()
                    .overlay(text.opacity(0.20))
                    .markdownMargin(top: .em(0.5), bottom: .em(0.5))
            }
    }
}
