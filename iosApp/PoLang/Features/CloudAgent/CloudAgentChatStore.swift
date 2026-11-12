import Foundation
import SharedKit

// MARK: - Cloud Agent Chat Store（ADR-018 M2，spec cloud-agent.yaml §2 状态机）
//
// 云端 Agent 最小调试页状态机（DEBUG 入口实验页；消息纯内存态不落库，镜像属 M3）。
// 生命周期：prepare()（通道解析 + 建会话，幂等）→ chat 态 send()/stop() 循环。
// 一轮只开一条 SSE：tool_request 帧到达即并发执行 + 上行（不阻塞收帧循环）。
//
// 🔴 BYOK 零留存红线：apiKey 自 ModelConfigStore 读出后仅存本类私有属性（内存态），
// 仅随 postMessage 的 X-User-Api-Key 头发送；绝不进 URL/body/日志/UserDefaults 新键。

@MainActor
final class CloudAgentChatStore: ObservableObject {

    enum Role {
        case user, assistant, tool, error
        /// HTML 卡消息（云端图卡信封产物 / render_html 服务端事件；text = summary，html = 清洗后 HTML）
        case htmlCard
    }

    struct Message: Identifiable, Equatable {
        let id: Int
        let role: Role
        let text: String
        /// htmlCard 角色专属：HtmlCardSanitizer 清洗后的 HTML 本体（其余角色恒 nil）
        var html: String? = nil
    }

    /// UiState 四态闭集（穷举渲染）。
    enum State: Equatable {
        case preparing
        case needByokConsent
        case failed(reason: String)
        case chat
    }

    @Published private(set) var state: State = .preparing
    @Published private(set) var sessionId: String = ""
    /// "gateway" | "byok"（仅 chat 态有意义）
    @Published private(set) var channel: String = ""
    @Published private(set) var messages: [Message] = []
    @Published private(set) var inFlight: Bool = false

    private let client: CloudAgentClient
    private var appToken: String = ""
    /// 🔴 BYOK key 仅存此私有属性（零留存红线锚点），仅 postMessage 头透传。
    private var byokApiKey: String = ""
    private var turnTask: Task<Void, Never>?
    private var msgSeq = 0
    private var prepareStarted = false

    init(client: CloudAgentClient = .shared) {
        self.client = client
    }

    // MARK: - 通道解析（spec §4 逐字，双端同规则）

    private enum ChannelChoice {
        case gateway
        case byok(model: String, baseUrl: String, apiKey: String)
        case needConsent
    }

    /// 无配置/无 apiKey → Gateway；有配置+授权 → Byok；未授权 → NeedConsent（不静默回退）。
    private static func resolveChannel(config: RemoteModelConfig?, consent: Bool) -> ChannelChoice {
        guard let config, !config.apiKey.isEmpty else { return .gateway }
        return consent
            ? .byok(model: config.modelId, baseUrl: config.baseUrl, apiKey: config.apiKey)
            : .needConsent
    }

    /// 当前选中远程模型配置（对齐 Android：按 selectedModelId 取首个匹配，不过滤 isConfigured——
    /// apiKey 空即落 Gateway 分支）。
    private static func selectedConfig() -> RemoteModelConfig? {
        let store = ModelConfigStore.shared
        store.load()
        return store.configs.first { $0.modelId == store.selectedModelId }
    }

    // MARK: - 入口

    /// 进入页即调用：读登录态/模型配置/授权开关 → 建会话。幂等（重复触发直接返回，双击守卫）。
    func prepare() {
        if prepareStarted { return }
        prepareStarted = true
        guard state == .preparing else { return }
        Task { await prepareAsync() }
    }

    private func prepareAsync() async {
        let token = UserDefaults.standard.string(forKey: "server_auth_token") ?? ""
        guard !token.isEmpty else {
            state = .failed(reason: "not_logged_in")
            return
        }
        appToken = token
        let consent = UserDefaults.standard.bool(forKey: "cloud_agent_byok_consent")
        switch Self.resolveChannel(config: Self.selectedConfig(), consent: consent) {
        case .gateway:
            await createAndEnter(channel: "gateway", model: "", byokBaseUrl: "", key: "")
        case .byok(let model, let baseUrl, let apiKey):
            await createAndEnter(channel: "byok", model: model, byokBaseUrl: baseUrl, key: apiKey)
        case .needConsent:
            state = .needByokConsent
        }
    }

    /// 授权弹窗 [使用网关]：放弃 BYOK 改走网关通道（本次会话级，不写授权开关）。
    func useGatewayInstead() {
        guard state == .needByokConsent else { return }
        state = .preparing
        Task { await createAndEnter(channel: "gateway", model: "", byokBaseUrl: "", key: "") }
    }

    /// 从设置页返回后重跑通道解析（用户可能已打开授权开关）；仅 needByokConsent 态有效。
    func retryPrepare() {
        guard state == .needByokConsent else { return }
        state = .preparing
        Task { await prepareAsync() }
    }

    private func createAndEnter(channel: String, model: String, byokBaseUrl: String, key: String) async {
        do {
            let resp = try await client.createSession(token: appToken, channel: channel, model: model, byokBaseUrl: byokBaseUrl)
            guard let sessionId = resp["session_id"] as? String, !sessionId.isEmpty else {
                state = .failed(reason: "bad_session_response")
                return
            }
            byokApiKey = key
            self.sessionId = sessionId
            self.channel = channel
            messages = []
            inFlight = false
            state = .chat
        } catch {
            state = .failed(reason: error.localizedDescription)
        }
    }

    // MARK: - Chat 态收发

    func send(_ text: String) {
        guard state == .chat, !inFlight, !text.isEmpty else { return }
        append(Message(id: nextId(), role: .user, text: text))
        inFlight = true
        let key = byokApiKey.isEmpty ? nil : byokApiKey
        let sid = sessionId
        let token = appToken
        turnTask = Task { [weak self] in
            guard let self else { return }
            do {
                // 注：每帧独立 Task 派发 @MainActor，actor 对独立 Task 无严格 FIFO；
                // 服务端 Koog loop 串行发帧使乱序概率极低（审查 🟡3 登记，暂不引 AsyncChannel）。
                try await client.postMessage(token: token, sessionId: sid, content: text, byokApiKey: key) { frame in
                    Task { @MainActor [weak self] in self?.onFrame(frame) }
                }
                // 流干净结束但无 done 帧的兜底（done 已清则为幂等 no-op）
                self.inFlight = false
            } catch is CancellationError {
                // 停止按钮断流：不追加错误
            } catch {
                self.append(Message(id: self.nextId(), role: .error, text: error.localizedDescription))
                self.inFlight = false
            }
        }
    }

    /// 停止当前轮（取消穿透断流）。
    func stop() {
        turnTask?.cancel()
        inFlight = false
    }

    // MARK: - 帧处理（spec §5 sse_frames：仅 event message/assistant 上屏；tool_request 并发执行）

    private func onFrame(_ frame: CloudAgentFrame) {
        guard state == .chat else { return }
        switch frame {
        case .event(let event):
            handleEvent(event)
        case .toolRequest(let requestId, let tool, let args, _):
            append(Message(id: nextId(), role: .tool, text: tool))
            // 端执行不阻塞收帧循环：并发执行，完成即上行
            let sid = sessionId
            let token = appToken
            Task { [weak self] in
                guard let self else { return }
                let result = await executeTool(tool: tool, args: args)
                // 产物拦截（注册制，contracts.md C4）：run_gallery_script 图卡信封 →
                // 本地 HTML 卡逐张上屏，上行只带 summary+stats——HTML 不出端、
                // 不进 LLM 上下文（对齐 Android CloudAgentTurnRunner）。
                let artifact = CloudAgentScriptRunner.parseHtmlArtifact(result)
                if let artifact {
                    for card in artifact.cards {
                        self.appendHtmlCard(html: card.html, summary: card.summary)
                    }
                }
                let postResult: String
                if let artifact {
                    var up: [String: Any] = [
                        "html_card": "emitted",
                        "summary": artifact.cards.map { card in card.summary }
                            .joined(separator: "；"),
                    ]
                    // stats 为 JSON 值串（parseHtmlArtifact 保证），回嵌为原始 JSON 值
                    if let stats = artifact.stats, let data = stats.data(using: .utf8),
                       let value = try? JSONSerialization.jsonObject(with: data) {
                        up["stats"] = value
                    }
                    postResult = Self.jsonString(of: up) ?? "{\"html_card\":\"emitted\"}"
                } else {
                    postResult = result
                }
                do {
                    try await client.postToolResult(
                        token: token, sessionId: sid, requestId: requestId,
                        ok: !result.hasPrefix(Self.errorPrefix), result: postResult
                    )
                } catch {
                    // 上行失败只影响本轮工具回路，降级记日志（不含 key）
                    NSLog("PoLang:CloudAgent postToolResult failed: \(error.localizedDescription)")
                }
            }
        case .openUiDelta:
            break // 主聊天流式预览由 ChatViewModel 消费；调试页 M1 不上屏（帧解析不崩即可）
        case .done:
            inFlight = false
        case .error(let message):
            append(Message(id: nextId(), role: .error, text: message))
            inFlight = false
        }
    }

    private func handleEvent(_ event: [String: Any]) {
        guard let payload = event["payload"] as? [String: Any],
              let type = event["type"] as? String else { return }
        switch type {
        case "message":
            guard let role = event["role"] as? String, role == "assistant",
                  let content = payload["content"] as? String else { return }
            append(Message(id: nextId(), role: .assistant, text: content))
        case "html_card":
            // render_html 服务端工具产物卡（§4.9 挂账清偿；html 为空不上屏空卡）
            if let html = payload["html"] as? String, !html.isEmpty {
                appendHtmlCard(html: html, summary: payload["summary"] as? String ?? "")
            }
        default:
            break // action/observation/finish 不上屏（tool 行由 tool_request 帧驱动）
        }
    }

    /// HTML 卡上屏（云端图卡信封 + render_html 事件共用）：commonMain HtmlCardSanitizer
    /// 清洗——Ok → htmlCard 消息（View 侧复用 HtmlCardView 渲染）；Rejected → 纯文本
    /// 降级（对齐 Android 调试页「Rejected 降级纯文本」口径，§4.9）。
    private func appendHtmlCard(html: String, summary: String) {
        let result = HtmlCardSanitizer.shared.sanitize(html: html)
        if let ok = result as? HtmlCardSanitizerResultOk {
            append(Message(id: nextId(), role: .htmlCard, text: summary, html: ok.html))
        } else if let rejected = result as? HtmlCardSanitizerResultRejected {
            append(Message(id: nextId(), role: .error, text: rejected.reason))
        }
    }

    /// 工具执行（shared iosMain 桥，绕过 CapabilityRegistry 场景门）；异常兜底为 error 前缀。
    /// run_gallery_script 例外：Swift 侧无头 JS 沙盒（注册制图卡产物信封；工具名 SSOT =
    /// shared CloudAgentToolContract.TOOL_RUN_GALLERY_SCRIPT，Swift 字面量副本互链）。
    private func executeTool(tool: String, args: [String: String]) async -> String {
        if tool == "run_gallery_script" {
            return await CloudAgentScriptRunner.shared.runScript(
                code: args[CloudAgentScriptRunner.codeParam] ?? "",
                languageTag: CloudAgentScriptRunner.bcp47LanguageTag()
            )
        }
        guard let bridge = IosAgentComposition.shared.cloudAgentToolBridge else {
            return "\(Self.errorPrefix) tool_bridge_unavailable"
        }
        do {
            return try await bridge.execute(tool: tool, args: args)
        } catch {
            return "\(Self.errorPrefix) \(error.localizedDescription)"
        }
    }

    /// 紧凑 JSON 串（上行载荷构造）。
    private static func jsonString(of object: [String: Any]) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    // MARK: - 内部

    /// 失败前缀（契约 §2：hasPrefix("error:") → ok=false；SSOT = shared commonMain CloudAgentToolContract.ERROR_PREFIX，改动须同步）。
    private static let errorPrefix = "error:"

    private func append(_ message: Message) {
        messages.append(message)
    }

    private func nextId() -> Int {
        msgSeq += 1
        return msgSeq
    }
}
