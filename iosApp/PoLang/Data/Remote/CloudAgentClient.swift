import Foundation

// MARK: - Cloud Agent Client（ADR-018 M2，iOS 对等端；spec cloud-agent.yaml §5）
//
// 全仓首个 SSE 客户端（无 EventSource 先例，平台差异台账登记）：
// URLSession `bytes(for:)` AsyncSequence 行解析，`\n\n` 分块、块内取 `data:` 前缀行、
// JSON 按 kind 四型分发，未知 kind / 解析失败一律忽略（容错 null）。
//
// 🔴 BYOK 零留存红线：apiKey 只出现在 `postMessage` 的 X-User-Api-Key 头——
// 不进 URL、不进 body、不进日志、不进任何持久化；其余方法连参数都没有。

/// SSE 下行帧（kind 四型闭集；未知 kind 容错忽略不产生帧）。
enum CloudAgentFrame {
    /// event 帧：agent_event 行 JSON（id/type/source/role/payload/created_at）。
    case event([String: Any])
    /// 工具桥下行帧（传输层信号，不落事件流）。
    case toolRequest(requestId: String, tool: String, args: [String: String], timeoutMs: Int)
    case done(sessionStatus: String)
    case error(message: String)
}

enum CloudAgentClientError: LocalizedError {
    case http(Int, String)
    case badResponse(String)
    case connectTimeout

    var errorDescription: String? {
        switch self {
        case .http(let code, let body): return "HTTP \(code): \(body)"
        case .badResponse(let detail): return detail
        case .connectTimeout: return "connect timeout"
        }
    }
}

/// SSE 块解析（纯逻辑）：取 `data:` 前缀行 JSON，按 kind 分发；无法识别一律 nil 容错跳过。
enum CloudAgentSseParser {
    static let defaultToolTimeoutMs = 120_000

    /// lines = 一个 `\n\n` 块内的全部行（空行已剔除）。
    static func parseBlock(lines: [String]) -> CloudAgentFrame? {
        let dataLines = lines.compactMap { line -> String? in
            guard line.hasPrefix("data:") else { return nil }
            return String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
        }
        guard !dataLines.isEmpty,
              let data = dataLines.joined(separator: "\n").data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        switch json["kind"] as? String {
        case "event":
            guard let event = json["event"] as? [String: Any] else { return nil }
            return .event(event)
        case "tool_request":
            guard let requestId = json["request_id"] as? String, !requestId.isEmpty else { return nil }
            let rawArgs = json["args"] as? [String: Any] ?? [:]
            var args: [String: String] = [:]
            for (key, value) in rawArgs {
                args[key] = value as? String ?? String(describing: value)
            }
            let timeoutMs = (json["timeout_ms"] as? NSNumber)?.intValue ?? defaultToolTimeoutMs
            return .toolRequest(requestId: requestId,
                                tool: json["tool"] as? String ?? "",
                                args: args,
                                timeoutMs: timeoutMs)
        case "done":
            return .done(sessionStatus: json["session_status"] as? String ?? "")
        case "error":
            return .error(message: json["message"] as? String ?? "")
        default:
            return nil
        }
    }
}

/// 云端 Agent 客户端（URLSession async/await + SSE AsyncSequence）。
/// 超时契约：连接 15s（postMessage 建连 + 首响门 race 实现）；SSE 读无限（服务端 120s 工具超时兜底）；
/// 普通 API 读 30s。
struct CloudAgentClient {
    static let shared = CloudAgentClient()
    static let baseURL = URL(string: "https://api.polang.net")!

    /// 普通 API 会话（读 30s）。
    private let apiSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()

    /// SSE 会话（读无限：timeoutIntervalForResource=0 不限总时长；请求级 idle 超时关闭，
    /// 长连接事件间隔不设上限——服务端心跳/120s 工具超时兜底）。
    private let sseSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = .infinity
        config.timeoutIntervalForResource = 0
        return URLSession(configuration: config)
    }()

    // MARK: - 端点（spec §5 network）

    /// POST /agent/sessions → {session_id, status, channel, model}
    func createSession(token: String, channel: String, model: String, byokBaseUrl: String) async throws -> [String: Any] {
        let body: [String: Any] = ["channel": channel, "model": model, "byok_base_url": byokBaseUrl]
        let data = try await post(path: "/agent/sessions", token: token, body: body)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CloudAgentClientError.badResponse("bad_session_response")
        }
        return json
    }

    /// POST /agent/sessions/{id}/messages（SSE 长连接）。
    /// 🔴 BYOK 零留存锚点：[byokApiKey] 只在本方法的 X-User-Api-Key 头出现。
    /// 取消语义：外层 Task 取消 → bytes 迭代中断（停止按钮穿透断流）。
    func postMessage(
        token: String,
        sessionId: String,
        content: String,
        byokApiKey: String?,
        onFrame: @escaping (CloudAgentFrame) -> Void
    ) async throws {
        var req = URLRequest(url: Self.baseURL.appendingPathComponent("/agent/sessions/\(sessionId)/messages"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(token, forHTTPHeaderField: "X-App-Token")
        req.setValue("ios", forHTTPHeaderField: "X-Platform")
        if let byokApiKey, !byokApiKey.isEmpty {
            req.setValue(byokApiKey, forHTTPHeaderField: "X-User-Api-Key")
        }
        req.httpBody = try JSONSerialization.data(withJSONObject: ["content": content])

        // 连接 15s 门：race bytes(for:) 建连+首响 vs 15s 定时；超时取消底层请求。
        let (bytes, response) = try await withConnectTimeout(seconds: 15) {
            try await self.sseSession.bytes(for: req)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard (200..<300).contains(status) else {
            var bodyText = ""
            for try await line in bytes.lines { bodyText += line; if bodyText.count > 2000 { break } }
            throw CloudAgentClientError.http(status, bodyText)
        }

        // SSE 读循环：空行 = 块边界；块内 data: 行聚合解析。
        var blockLines: [String] = []
        for try await line in bytes.lines {
            if Task.isCancelled { break }
            if line.isEmpty {
                if !blockLines.isEmpty {
                    if let frame = CloudAgentSseParser.parseBlock(lines: blockLines) {
                        onFrame(frame)
                    }
                    blockLines = []
                }
            } else {
                blockLines.append(line)
            }
        }
        // 流尾无空行收尾的兜底块
        if !blockLines.isEmpty, let frame = CloudAgentSseParser.parseBlock(lines: blockLines) {
            onFrame(frame)
        }
    }

    /// POST /agent/sessions/{id}/tool-results → 200 {accepted:true} / 409 tool_request_not_pending
    func postToolResult(token: String, sessionId: String, requestId: String, ok: Bool, result: String) async throws {
        let body: [String: Any] = ["request_id": requestId, "ok": ok, "result": result]
        _ = try await post(path: "/agent/sessions/\(sessionId)/tool-results", token: token, body: body)
    }

    /// POST /agent/sessions/{id}/finish → 200
    func finishSession(token: String, sessionId: String) async throws {
        _ = try await post(path: "/agent/sessions/\(sessionId)/finish", token: token, body: [:])
    }

    // MARK: - 内部

    private func post(path: String, token: String, body: [String: Any]) async throws -> Data {
        var req = URLRequest(url: Self.baseURL.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(token, forHTTPHeaderField: "X-App-Token")
        req.setValue("ios", forHTTPHeaderField: "X-Platform")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await apiSession.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? -1
        guard (200..<300).contains(status) else {
            throw CloudAgentClientError.http(status, String(data: data, encoding: .utf8) ?? "")
        }
        return data
    }

    /// 建连超时门（race 实现；超时即取消请求任务）。
    private func withConnectTimeout<T>(
        seconds: UInt64,
        operation: @escaping () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(nanoseconds: seconds * 1_000_000_000)
                throw CloudAgentClientError.connectTimeout
            }
            guard let first = try await group.next() else {
                throw CloudAgentClientError.connectTimeout
            }
            group.cancelAll()
            return first
        }
    }
}
