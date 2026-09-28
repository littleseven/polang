import SwiftUI
import SharedKit

/// Agent 文本富渲染（M5 B3，spec chat.yaml §5 agent_markdown 契约）：
/// commonMain `MarkdownSegmenter` 分段（MARKDOWN / TABLE / CODE），各段原生 SwiftUI
/// 渲染。代码块/表格底色按 §5 color derivation 以正文色 α 派生（浅深色自适应，
/// 禁硬编码背景）。
struct AgentTextView: View {
    let content: String

    var body: some View {
        let segments = segmentMarkdown(content: content)
        return VStack(alignment: .leading, spacing: 6) {
            ForEach(segments.indices, id: \.self) { i in
                let seg = segments[i]
                switch seg.type {
                case .table:
                    AgentTableView(raw: seg.text)
                case .code:
                    CodeBlockView(raw: seg.text)
                default:
                    MarkdownText(text: seg.text)
                }
            }
        }
    }
}

/// 代码块（§5 code_block）：折叠/展开 + 复制。契约值——scale 0.80（16×0.80=12.8pt）、
/// 行高比 1.35、collapse_lines 12、corner_radius 8、code_block_bg = 正文色 α0.08；
/// 复制提示 ~1.5s 自动消失；折叠态裁切。代码体由 commonMain `extractCodeBody` 提取。
struct CodeBlockView: View {
    let raw: String
    @State private var expanded = false
    @State private var copied = false
    @State private var copyCount = 0

    /// §5 typography：code_block scale 0.80，行高比 1.35
    private static let codeSize: CGFloat = ChatBubbleTokens.textSize * 0.80
    private static let codeLineSpacing: CGFloat = codeSize * 0.35
    /// §5 复制提示存活时长（~1.5s）
    private static let copyHintNs: UInt64 = 1_500_000_000

    var body: some View {
        let code = extractCodeBody(raw: raw)
        let total = Int(codeLineCount(code: code))
        // §5 code_block collapse_lines = 12
        let collapsible = total > 12
        let shown = (expanded || !collapsible) ? code : previewCode(code: code, limit: 12)
        return VStack(alignment: .leading, spacing: 4) {
            Text(shown)
                .font(.system(size: Self.codeSize, design: .monospaced))
                .lineSpacing(Self.codeLineSpacing)
                .foregroundColor(Color(.label))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .background(Color(.label).opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .clipped()
                .accessibilityIdentifier("chat_code_block")
            HStack(spacing: 12) {
                if collapsible {
                    Button(expanded ? String(localized: "Collapse") : "\(total) \(String(localized: "lines"))") {
                        expanded.toggle()
                    }
                }
                Button(copied ? String(localized: "Copied") : String(localized: "Copy")) {
                    UIPasteboard.general.string = code
                    copied = true
                    copyCount += 1
                }
            }
            .font(.system(size: 11))
            .foregroundColor(Color(.secondaryLabel))
            .task(id: copyCount) {
                guard copyCount > 0 else { return }
                try? await Task.sleep(nanoseconds: Self.copyHintNs)
                if !Task.isCancelled { copied = false }
            }
        }
    }
}

/// 表格（§5 table）：commonMain `parseMarkdownTable` → 表头 + 数据行 → SwiftUI 网格。
/// 底色按 §5 派生 table_bg = 正文色 α0.02；描边 divider = 正文色 α0.20；表头加粗。
struct AgentTableView: View {
    let raw: String

    var body: some View {
        let table = parseMarkdownTable(raw: raw)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                ForEach(table.header.indices, id: \.self) { c in
                    Text(table.header[c])
                        .bold()
                        .font(.system(size: 12))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(6)
                }
            }
            .background(Color(.label).opacity(0.02))
            ForEach(table.rows.indices, id: \.self) { r in
                let row = table.rows[r]
                HStack(spacing: 0) {
                    ForEach(row.indices, id: \.self) { c in
                        Text(row[c])
                            .font(.system(size: 12))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(6)
                    }
                }
                .background(Color(.label).opacity(0.02))
            }
        }
        .cornerRadius(6)
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(.label).opacity(0.20), lineWidth: 0.5))
    }
}
