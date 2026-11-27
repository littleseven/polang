import Foundation
import SharedKit

// MARK: - Cloud Agent Client（ADR-018 M2，iOS 对等端；spec cloud-agent.yaml §5）
//
// 全仓首个 SSE 客户端（无 EventSource 先例，平台差异台账登记）：
// URLSession `bytes(for:)` AsyncSequence 行解析，`\n\n` 分块、块内取 `data:` 前缀行。
//
// AG-UI v2（2026-11-28，spec《2026-11-28-ag-ui-migration-design》M3）：
// iOS 无线上存量，直接只实现 v2——`POST /agent/v2/sessions/{id}/messages`，
// 帧解析 = shared commonMain `AgUiSseParser`（单一解码源；未知 type / 非法 JSON
// 服务端演进容错 nil 跳过）。v1 kind 帧模型（CloudAgentFrame/CloudAgentSseParser）已删除。
//
// 🔴 BYOK 零留存红线：apiKey 只出现在 `postMessage` 的 X-User-Api-Key 头——
// 不进 URL、不进 body、不进日志、不进任何持久化；其余方法连参数都没有。

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

    /// POST /agent/v2/sessions/{id}/messages（SSE 长连接，AG-UI 事件流）。
    /// 🔴 BYOK 零留存锚点：[byokApiKey] 只在本方法的 X-User-Api-Key 头出现。
    /// replyLanguage（2026-11-22 OpenUI 多语言批次）：ReplyLanguage 枚举 name 经
    /// X-App-Language 头上报，服务端据此追加语言规则段 + 兜底文案五语化；nil 走简中缺省。
    /// 取消语义：外层 Task 取消 → bytes 迭代中断（停止按钮穿透断流）。
    func postMessage(
        token: String,
        sessionId: String,
        content: String,
        byokApiKey: String?,
        replyLanguage: String? = nil,
        onFrame: @escaping (AgUiFrame) -> Void
    ) async throws {
        var req = URLRequest(url: Self.baseURL.appendingPathComponent("/agent/v2/sessions/\(sessionId)/messages"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(token, forHTTPHeaderField: "X-App-Token")
        req.setValue("ios", forHTTPHeaderField: "X-Platform")
        if let byokApiKey, !byokApiKey.isEmpty {
            req.setValue(byokApiKey, forHTTPHeaderField: "X-User-Api-Key")
        }
        if let replyLanguage, !replyLanguage.isEmpty {
            req.setValue(replyLanguage, forHTTPHeaderField: "X-App-Language")
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

        // SSE 读循环：空行 = 块边界；块聚合后交 shared AgUiSseParser（未知 type 容错 nil）。
        var blockLines: [String] = []
        for try await line in bytes.lines {
            if Task.isCancelled { break }
            if line.isEmpty {
                if !blockLines.isEmpty {
                    if let frame = AgUiSseParser.shared.parseBlock(block: blockLines.joined(separator: "\n")) {
                        onFrame(frame)
                    }
                    blockLines = []
                }
            } else {
                blockLines.append(line)
            }
        }
        // 流尾无空行收尾的兜底块
        if !blockLines.isEmpty,
           let frame = AgUiSseParser.shared.parseBlock(block: blockLines.joined(separator: "\n")) {
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
