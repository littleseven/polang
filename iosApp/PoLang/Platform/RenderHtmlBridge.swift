import Foundation
import SharedKit

/// `IosRenderHtmlBridge` 的 Swift 实现：Kotlin `IosRenderHtmlCapability`（render_html 命令）
/// → `ChatViewModel` 落 HtmlCard 消息（M5 B4，chat.yaml §13）。
///
/// 契约对齐 Android `ChatViewModel.onRenderHtml`：本桥先经 commonMain
/// `HtmlCardSanitizer`（纯函数，线程安全）清洗——
/// - **Ok**：清洗后 html 经 [onRenderHtml] 通道交 `ChatViewModel`（主线程）落
///   HTML_CARD 消息 + 双形态渲染；observation = summary（回传远程 LLM 做文字总结）；
/// - **Rejected**（超限/blank）：不落卡，通道交 ViewModel 闭环 turn 占位
///   （feedToolError 瞬态 OUTPUT_ERROR），observation = 拒绝原因（引导 LLM 重新生成）。
///
/// SharedBridge 铁律（同 `ChartRendererBridge`）：方法绝不抛异常跨边界；
/// `onResult` **必须**被调用（Kotlin 侧 suspendCancellableCoroutine 等待恢复），
/// 与主线程通道派发无先后依赖（resume 线程安全）。
@objc final class RenderHtmlBridge: NSObject, IosRenderHtmlBridge {

    static let shared = RenderHtmlBridge()

    /// Swift → ChatViewModel 通道的载荷（Swift 内部 enum，不跨 K/N）。
    enum Outcome {
        /// sanitize Ok：(清洗后 html, summary, display 声明)。
        case ok(html: String, summary: String?, display: String?)
        /// sanitize Rejected：不落卡，ViewModel 侧 feedToolError 闭环 turn 占位。
        case rejected(reason: String)
    }

    /// Swift → ChatViewModel 通道：`ChatViewModel.configure` 时注入；
    /// nil 时仅回传 observation（卡片不显示，防御路径）。
    static var onRenderHtml: ((Outcome) -> Void)?

    func renderHtml(
        html: String,
        summary: String?,
        display: String?,
        onResult: @escaping (String) -> Void
    ) {
        // sanitize：commonMain 纯函数直调（调用线程 = Kotlin 能力 dispatcher，安全）。
        // sealed interface 消费用项目既定 `as?` 下转形态（同 ChatViewModel 的
        // `turnEvent as? TurnStreamEventToolInputStart`），不走 SKIE enum 变换。
        let outcome: Outcome
        let observation: String
        let result = HtmlCardSanitizer.shared.sanitize(html: html)
        if let ok = result as? HtmlCardSanitizerResultOk {
            outcome = .ok(html: ok.html, summary: summary, display: display)
            // Kotlin isBlank ≈ Swift 空白判定（whitespacesAndNewlines 全空白/空）
            let summaryText = (summary ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            observation = summaryText.isEmpty
                ? String(localized: "chat.html_card_generated")
                : summary!
        } else if let rejected = result as? HtmlCardSanitizerResultRejected {
            NSLog("[PoLang:RenderHtmlBridge] rejected: %@", rejected.reason)
            outcome = .rejected(reason: rejected.reason)
            observation = rejected.reason
        } else {
            // sealed interface 两子类穷尽，防御分支（不可达）
            NSLog("[PoLang:RenderHtmlBridge] unexpected sanitize result")
            outcome = .rejected(reason: "sanitize failed")
            observation = "sanitize failed"
        }
        if let onRenderHtml = RenderHtmlBridge.onRenderHtml {
            DispatchQueue.main.async { onRenderHtml(outcome) }
        }
        onResult(observation)
    }
}
