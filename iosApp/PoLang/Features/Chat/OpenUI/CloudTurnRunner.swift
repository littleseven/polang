import Foundation
import SharedKit

// MARK: - 帧序管道（SSE reader 串行产帧 → MainActor 消费串行回灌，保序 + 排空语义）

/// 单消费者帧管道：postMessage onFrame（任意线程）→ [send]；MainActor 消费循环 [next]
/// 逐帧取出；流结束/断流 [finish] 后缓冲排空、next 返 nil（消费任务收尾）。
/// 帧型 = shared `AgUiFrame`（AG-UI v2，2026-11-28 M3；v1 CloudAgentFrame 已退役）。
final class CloudFramePipe: @unchecked Sendable {

    private let lock = NSLock()
    private var buffer: [AgUiFrame] = []
    private var finished = false
    private var waiter: CheckedContinuation<AgUiFrame?, Never>? = nil

    func send(_ frame: AgUiFrame) {
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

    func next() async -> AgUiFrame? {
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
// MARK: - 云端回合事件映射（对位 Android AgUiCloudTurnTranslator + ChatViewModel runCloudTurnOnce 的事件面）
//
// shared AgUiFrame（AG-UI v2）→ CloudTurnEvent 翻译（可单测；Android AgUiCloudTurnTranslator 的
// iOS 子集——iOS 无步骤时间线，TOOL_CALL_RESULT 不上屏）：
// - TEXT_MESSAGE_* 聚合整条后同一 looksLikeProgram 嗅探 → OpenUiCard 或 AssistantText；
//   多 chunk 流式块经 looksLikeProgramPrefix 门判出 OpenUiCardDelta（单 chunk=非流式整条不出预览）；
//   role=user 块整段跳过（本地已先上屏）；
// - stream/evt 双块去重（服务端 TurnFrameSink 契约）：stream-* 块 END 暂存候选，evt-*（assistant）
//   START 到达丢弃候选（改写场景 evt 权威全文），其余帧到达先 flush 候选（等值场景）——恒单卡；
// - TOOL_CALL_END 全量 args 解析 render_ui_openui → OpenUiCard（坏帧容错忽略不拖垮整回合）；
// - CUSTOM(device_tool_request) → onToolRequest 回调（端执行通道直驱，不经事件流）；
// - RUN_FINISHED metadata → done；RUN_ERROR → error；RUN_STARTED 仅回合边界信号。

/// 云端回合事件（上屏/收尾信号；ChatViewModel 消费）。
enum CloudTurnEvent: Equatable {
    /// 助手答复整条文本（AG-UI 块聚合粒度为整条消息，非 token 增量）。
    case assistantText(String)
    /// 保留通道（v1 html_card 事件产物卡；v2 不下行 html_card，图卡全走端侧工具桥产物拦截）。
    case htmlCard(html: String, summary: String)
    /// render_ui_openui 云端发卡 / 流式程序终结卡（OpenUI Lang 1.0 程序 + 投影级降级摘要）。
    case openUiCard(program: String, fallbackSummary: String)
    /// openui 流式预览（已到达的 program 前缀）。
    case openUiCardDelta(program: String)
    /// 服务端错误帧正文。
    case error(String)
    /// RUN_FINISHED metadata 性能数据 + 语言协议版本（缺省 nil）。
    case done(promptTokens: Int64?, completionTokens: Int64?, elapsedMs: Int64?, langVersion: Int?)
}

/// AG-UI v2 帧 → CloudTurnEvent 翻译器（单回合状态：文本块聚合 / 工具参数累计）。
/// 每回合新建；仅 MainActor 消费循环调用，无需锁。
final class AgUiCloudTurnTranslator {

    /// 进行中文本块（messageId → 角色/累计/帧计数）。
    private var textBlocks: [String: (role: String, buf: String, chunks: Int)] = [:]
    private var toolNames: [String: String] = [:]
    private var toolArgs: [String: String] = [:]

    /// 终结候选（服务端 stream/evt 去重契约，TurnFrameSink §双形态，对位 Android pendingFinal）：
    /// 流式预览块（stream-*）END 后暂存事件不上屏——随后 evt-*（assistant）START 到达
    /// = ensureValidProgram 改写场景，evt 携带权威全文取代候选（防双卡）；
    /// 任何其它帧到达 = 等值场景（无 evt 续帧），候选 flush 转正上屏。
    private var pendingFinal: CloudTurnEvent?

    /// 翻译一帧；事件经 onEvent、设备工具桥请求经 onToolRequest 分流（同 Android 口径）。
    func onFrame(
        _ frame: AgUiFrame,
        onEvent: (CloudTurnEvent) -> Void,
        onToolRequest: (_ requestId: String, _ tool: String, _ args: [String: String]) -> Void
    ) {
        if let start = frame as? AgUiFrameTextMessageStart,
           start.messageId.hasPrefix(Self.finalMessagePrefix), start.role == "assistant" {
            // 改写场景：evt 块到达，丢弃 stream 候选（权威全文在 evt 块内）
            pendingFinal = nil
        } else {
            // 等值场景/工具交错：候选转正（evt assistant START 之外的一切帧都先 flush）
            flushPendingFinal(onEvent)
        }
        switch frame {
        case is AgUiFrameRunStarted:
            break // 回合边界信号（v1 无对应物），不上屏
        case let f as AgUiFrameTextMessageStart:
            textBlocks[f.messageId] = (f.role, "", 0)
        case let f as AgUiFrameTextMessageContent:
            guard var block = textBlocks[f.messageId] else { break }
            block.buf += f.delta
            block.chunks += 1
            textBlocks[f.messageId] = block
            // 流式程序预览：第二 chunk 起（单 chunk = 非流式整条消息）且前缀像程序
            if block.role == "assistant", block.chunks >= 2,
               OpenUiProgramSummary.shared.looksLikeProgramPrefix(text: block.buf) {
                onEvent(.openUiCardDelta(program: block.buf))
            }
        case let f as AgUiFrameTextMessageEnd:
            guard let block = textBlocks.removeValue(forKey: f.messageId),
                  block.role == "assistant" else { break }
            let content = block.buf
            let event: CloudTurnEvent
            if OpenUiProgramSummary.shared.looksLikeProgram(content: content) {
                event = .openUiCard(
                    program: content,
                    fallbackSummary: OpenUiProgramSummary.shared.extractFallbackSummary(program: content))
            } else {
                event = .assistantText(content)
            }
            // stream-* = 流式预览块：暂存候选等 evt 续帧裁决；evt-*（及旧服务端整条块）直上屏
            if f.messageId.hasPrefix(Self.streamMessagePrefix) {
                pendingFinal = event
            } else {
                onEvent(event)
            }
        case let f as AgUiFrameToolCallStart:
            toolNames[f.toolCallId] = f.toolCallName
            toolArgs[f.toolCallId] = ""
        case let f as AgUiFrameToolCallArgs:
            toolArgs[f.toolCallId, default: ""] += f.delta
        case let f as AgUiFrameToolCallEnd:
            let args = toolArgs.removeValue(forKey: f.toolCallId) ?? ""
            guard toolNames.removeValue(forKey: f.toolCallId) == Self.renderUiOpenUiTool,
                  let event = Self.openUiCardEvent(argsJson: args) else { break }
            onEvent(event)
        case is AgUiFrameToolCallResult:
            break // iOS 无步骤时间线（Android AgentStepTimeline 对位缺口，M1 登记）
        case let f as AgUiFrameCustom:
            guard f.name == Self.customDeviceToolRequest,
                  let (requestId, tool, args) = Self.parseDeviceToolRequest(valueJson: f.valueAsJsonString())
            else { break }
            onToolRequest(requestId, tool, args)
        case let f as AgUiFrameRunFinished:
            onEvent(Self.doneEvent(metadataJson: f.metadataAsJsonString()))
        case let f as AgUiFrameRunError:
            onEvent(.error(f.message))
        default:
            break // 未知子类容错（shared 新增事件族旧端零感知）
        }
    }

    // MARK: - 纯逻辑桥接（kotlinx JsonObject 经 toString → JSONSerialization；可单测）

    /// render_ui_openui 全量 args → OpenUiCard 事件（坏 JSON 容错 nil）。
    static func openUiCardEvent(argsJson: String) -> CloudTurnEvent? {
        guard let data = argsJson.data(using: .utf8),
              let args = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let program = args["program"] as? String
        else { return nil }
        return .openUiCard(program: program, fallbackSummary: args["fallbackSummary"] as? String ?? "")
    }

    /// CUSTOM(device_tool_request) value → 桥请求（args 值字符串化，v1 tool_request 同口径）。
    static func parseDeviceToolRequest(valueJson: String?) -> (String, String, [String: String])? {
        guard let valueJson,
              let data = valueJson.data(using: .utf8),
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let requestId = value["request_id"] as? String, !requestId.isEmpty,
              let tool = value["tool"] as? String, !tool.isEmpty
        else { return nil }
        var args: [String: String] = [:]
        for (key, v) in value["args"] as? [String: Any] ?? [:] {
            args[key] = v as? String ?? String(describing: v)
        }
        return (requestId, tool, args)
    }

    /// RUN_FINISHED metadata → done 事件（键缺省 nil 容错）。
    static func doneEvent(metadataJson: String?) -> CloudTurnEvent {
        var metadata: [String: Any] = [:]
        if let metadataJson, let data = metadataJson.data(using: .utf8) {
            metadata = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        }
        return .done(
            promptTokens: (metadata["prompt_tokens"] as? NSNumber)?.int64Value,
            completionTokens: (metadata["completion_tokens"] as? NSNumber)?.int64Value,
            elapsedMs: (metadata["elapsed_ms"] as? NSNumber)?.int64Value,
            langVersion: (metadata["openui_lang_version"] as? NSNumber)?.intValue
        )
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

    /// 暂存的终结候选转正上屏（等值场景/回合收尾/工具交错前调用，对位 Android flushPendingFinal）。
    private func flushPendingFinal(_ onEvent: (CloudTurnEvent) -> Void) {
        if let event = pendingFinal { onEvent(event) }
        pendingFinal = nil
    }

    private static let renderUiOpenUiTool = "render_ui_openui"
    private static let customDeviceToolRequest = "device_tool_request"
    /// 服务端流式预览块 messageId 前缀（TurnFrameSink `stream-{N}`）。
    private static let streamMessagePrefix = "stream-"
    /// 服务端终结块 messageId 前缀（TurnFrameSink `evt-{eventId}`）。
    private static let finalMessagePrefix = "evt-"
}
