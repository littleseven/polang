import Foundation
import SharedKit

// MARK: - 帧序管道（SSE reader 串行产帧 → MainActor 消费串行回灌，保序 + 排空语义）

/// 单消费者帧管道：postMessage onFrame（任意线程）→ [send]；MainActor 消费循环 [next]
/// 逐帧取出；流结束/断流 [finish] 后缓冲排空、next 返 nil（消费任务收尾）。
final class CloudFramePipe: @unchecked Sendable {

    private let lock = NSLock()
    private var buffer: [CloudAgentFrame] = []
    private var finished = false
    private var waiter: CheckedContinuation<CloudAgentFrame?, Never>? = nil

    func send(_ frame: CloudAgentFrame) {
        lock.lock()
        let waiting = waiter
        if waiting != nil { waiter = nil }
        if waiting == nil { buffer.append(frame) }
        lock.unlock()
        waiting?.resume(returning: frame)
    }

    func finish() {
        lock.lock()
        finished = true
        let waiting = waiter
        waiter = nil
        lock.unlock()
        waiting?.resume(returning: nil)
    }

    func next() async -> CloudAgentFrame? {
        lock.lock()
        if !buffer.isEmpty {
            let frame = buffer.removeFirst()
            lock.unlock()
            return frame
        }
        if finished {
            lock.unlock()
            return nil
        }
        lock.unlock()
        return await withCheckedContinuation { continuation in
            lock.lock()
            if !buffer.isEmpty {
                let frame = buffer.removeFirst()
                lock.unlock()
                continuation.resume(returning: frame)
            } else if finished {
                lock.unlock()
                continuation.resume(returning: nil)
            } else {
                waiter = continuation
                lock.unlock()
            }
        }
    }
}

//
// MARK: - 云端回合事件映射（对位 Android CloudAgentTurnRunner + ChatViewModel runCloudTurnOnce 的事件面）
//
// CloudAgentFrame → CloudTurnEvent 纯逻辑映射（可单测）：
// - event(message/assistant)：程序嗅探（shared OpenUiProgramSummary）→ OpenUiCard 事件
//   （fallbackSummary 提取）或 AssistantText 降级——门判纯按内容嗅探，防 LLM 把程序当
//   纯文本输出糊屏（Android 2026-11-02 跨线污染修复同口径）；
// - event(html_card)：云端 render_html 产物卡；
// - event(action) render_ui_openui：参数帧发卡（坏帧容错忽略）；
// - openui_delta：流式预览；tool_request：端执行；done：性能 + langVersion。
// event(action/observation) 步骤时间线事件 iOS M1 不上屏（Android AgentStepTimeline
// 对位缺口，主聊天云端回合先保证 message/卡/工具回路）。

/// 云端回合事件（上屏/收尾信号；ChatViewModel 消费）。
enum CloudTurnEvent {
    /// 助手答复整条文本（SSE 事件粒度为整条消息，非 token 增量）。
    case assistantText(String)
    /// 云端 render_html 产物（H5 卡）：完整自包含 HTML + 一句话摘要。
    case htmlCard(html: String, summary: String)
    /// render_ui_openui 云端发卡（OpenUI Lang 1.0 程序 + 投影级降级摘要）。
    case openUiCard(program: String, fallbackSummary: String)
    /// openui 流式预览（已到达的 program 前缀）。
    case openUiCardDelta(program: String)
    /// 服务端错误帧正文。
    case error(String)
    /// done 帧性能数据 + 语言协议版本（旧服务端 nil）。
    case done(promptTokens: Int64?, completionTokens: Int64?, elapsedMs: Int64?, langVersion: Int?)
}

/// 帧事件映射器（纯逻辑）。
enum CloudTurnEventMapper {

    /// tool_request 帧占位事件口径外（工具执行由宿主直接驱动，不经本映射器）。
    static func map(frame: CloudAgentFrame) -> [CloudTurnEvent] {
        switch frame {
        case .event(let event):
            return mapEvent(event)
        case .openUiDelta(let program):
            return [.openUiCardDelta(program: program)]
        case .toolRequest:
            return [] // 宿主端执行通道直驱（不经事件流）
        case .done(let sessionStatus, let promptTokens, let completionTokens, let elapsedMs, let langVersion):
            // done 收尾恒发事件（sessionStatus 现无消费方，保留载荷转发）
            _ = sessionStatus
            return [.done(promptTokens: promptTokens, completionTokens: completionTokens,
                          elapsedMs: elapsedMs, langVersion: langVersion)]
        case .error(let message):
            return [.error(message)]
        }
    }

    /// event 帧（agent_event 行 JSON）分派。
    static func mapEvent(_ event: [String: Any]) -> [CloudTurnEvent] {
        guard let payload = event["payload"] as? [String: Any],
              let type = event["type"] as? String else { return [] }
        switch type {
        case "message":
            // user 回声跳过（本地已先上屏）；assistant content 嗅探分派
            guard event["role"] as? String == "assistant",
                  let content = payload["content"] as? String else { return [] }
            if OpenUiProgramSummary.shared.looksLikeProgram(content: content) {
                return [.openUiCard(
                    program: content,
                    fallbackSummary: OpenUiProgramSummary.shared.extractFallbackSummary(program: content))]
            }
            return [.assistantText(content)]
        case "html_card":
            guard let html = payload["html"] as? String, !html.isEmpty else { return [] }
            return [.htmlCard(html: html, summary: payload["summary"] as? String ?? "")]
        case "action":
            // render_ui_openui 参数帧发卡；坏帧容错忽略不拖垮整回合
            guard let tool = payload["tool"] as? String,
                  tool == "render_ui_openui",
                  let argsJson = payload["argsJson"] as? String,
                  let data = argsJson.data(using: .utf8),
                  let args = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let program = args["program"] as? String
            else { return [] }
            return [.openUiCard(program: program,
                                fallbackSummary: args["fallbackSummary"] as? String ?? "")]
        default:
            return [] // action（其它工具）/observation/finish 不上屏
        }
    }

    /// done 帧性能数据 → LlmPerformance（映射同 Android toLlmPerformanceOrNull：
    /// prefill=0 隐藏、decode=回合耗时、speed=completion/耗时；tokens 双 0 返 nil 不渲染全 0 行）。
    static func performance(promptTokens: Int64?, completionTokens: Int64?,
                            elapsedMs: Int64?) -> LlmPerformance? {
        let prompt = promptTokens ?? 0
        let completion = completionTokens ?? 0
        guard prompt > 0 || completion > 0 else { return nil }
        let elapsed = elapsedMs ?? 0
        return LlmPerformance(
            promptLen: prompt,
            decodeLen: completion,
            prefillTimeMs: 0,
            decodeTimeMs: elapsed,
            prefillSpeed: 0,
            decodeSpeed: elapsed > 0 && completion > 0
                ? Float(completion) / Float(elapsed) * 1000 : 0,
            usedSandbox: false)
    }
}
