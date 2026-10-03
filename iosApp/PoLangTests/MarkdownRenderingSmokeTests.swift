#if DEBUG
import XCTest
import MarkdownUI
@testable import PoLang

/// Markdown 渲染解析冒烟（chat.yaml §5 agent_markdown：streaming_redline / dialect GFM 全量）：
/// 1. 流式场景逐前缀全量重解析不崩溃（未闭合 ** / ``` / 表格半行均须容错）；
/// 2. 全语法 torture 文档经 GFM 解析产出全部关键元素（以 renderHTML 结构标签断言）。
@MainActor
final class MarkdownRenderingSmokeTests: XCTestCase {

    /// 流式红线：每个 8 字前缀（覆盖未闭合语法中间态）重解析 + 渲染均不得崩溃。
    func testStreamingPrefixReparseNeverCrashes() {
        let full = ChatViewModel.tortureMarkdown
        for end in stride(from: 1, through: full.count, by: 8) {
            let content = MarkdownContent(String(full.prefix(end)))
            _ = content.renderPlainText()
            _ = content.renderHTML()
        }
    }

    /// 契约语法面：torture 文档必须解析出全部关键结构（标题/表格/删除线/任务列表/代码块/引用/分割线）。
    func testTortureDocumentGfmCoverage() {
        let html = MarkdownContent(ChatViewModel.tortureMarkdown).renderHTML()
        XCTAssertTrue(html.contains("<h1"), "缺 h1")
        XCTAssertTrue(html.contains("<h3"), "缺 h3")
        XCTAssertTrue(html.contains("<strong>"), "缺加粗")
        XCTAssertTrue(html.contains("<em>"), "缺斜体")
        XCTAssertTrue(html.contains("<del>"), "缺删除线")
        XCTAssertTrue(html.contains("<code>"), "缺行内代码")
        XCTAssertTrue(html.contains("<table"), "缺表格")
        XCTAssertTrue(html.contains("<blockquote"), "缺引用")
        XCTAssertTrue(html.contains("<hr"), "缺分割线")
        XCTAssertTrue(html.contains("<pre"), "缺代码块")
        XCTAssertTrue(html.contains("<ol"), "缺有序列表")
        XCTAssertTrue(html.contains("checkbox"), "缺任务列表")
    }
}
#endif
