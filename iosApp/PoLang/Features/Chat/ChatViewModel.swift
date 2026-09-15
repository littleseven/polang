import Foundation
import Combine
import SharedKit
import UIKit

/// Chat ViewModel（MV 模式，对齐 Android ChatViewModel 多会话语义，spec chat.yaml §9.1 session_model）。
///
/// 交互模型（spec chat.yaml §9.1）：
/// - 发送流程：user 消息即追加 → thinking 占位（3 点）→ streaming（文本 + 光标）
///   → toolCalling（「正在调用工具…」）→ complete
/// - 发送按钮仅在有内容 && !isProcessing 时显示（Android 无 stop 按钮）
/// - 媒体结果作为独立消息项（不嵌入文本气泡）
/// - 空状态示例 chips 点击直接发送
///
/// 多会话（spec §2.5/§9.1）：
/// - 会话索引 + 每会话消息文件（ChatHistoryStore）；LLM 记忆按 sessionId 分键隔离
/// - 首条用户消息自动生成标题（仅默认标题时覆盖，重命名后不覆盖）
/// - 当前会话 ID 持久化，冷启校验存在否则回退 default；删除当前会话回退 default
@MainActor
final class ChatViewModel: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var isProcessing = false
    @Published private(set) var threads: [ChatThread] = []
    @Published private(set) var currentSessionId: String = ChatHistoryStore.defaultSessionId
    @Published var searchQuery = ""

    // MARK: 上下文附件（B1-feasible）
    /// 选中的图片意图（对齐 Android ChatViewModel.ImageIntent）：理解 / 找相似 / 编辑
    enum ImageIntent: String, CaseIterable {
        case understand, findSimilar, edit
        var label: String {
            switch self {
            case .understand: return String(localized: "Understand")
            case .findSimilar: return String(localized: "Find similar")
            case .edit: return String(localized: "Edit")
            }
        }
    }
    struct StagedImage: Equatable {
        let localIdentifier: String
        var thumbnail: UIImage?
    }
    @Published var stagedImage: StagedImage?
    @Published var stagedIntent: ImageIntent = .understand
    /// 端侧能力不可用的提示（UNDERSTAND/FIND_SIMILAR 引擎本版未实现）
    @Published var unavailableNotice: String? = nil
    /// EDIT 意图回调（ChatView 注入 → PhotoEditorScreen）
    var onEditImage: ((String) -> Void)?

    // MARK: AI 优化抽卡状态（chat.yaml §17）
    /// 抽卡控制器（pending 卡组进程级内存态 + 引擎调用 + 反馈落库）
    private let gachaController = ChatOptimizeGachaController.shared
    /// messageId → 选中卡序号（初值=recommendedIndex；pending 过期后仅驱动只读渲染）
    @Published private(set) var gachaSelections: [UUID: Int] = [:]
    /// 重抽中消息集合（防抖 + 卡条按钮行 spinner）
    @Published private(set) var gachaRerolling: Set<UUID> = []
    /// ai_optimize 抽卡在途（ReAct 同轮重复动作去重）
    private var gachaDrawInFlight = false

    // MARK: 访客渐进注册引导（chat.yaml §4.1 guest_nudge，2026-08-22）
    //
    /// 访客计数存储 key（平台差异台账 contracts.md §3：Android DataStore ↔ iOS UserDefaults）
    static let guestChatMessageCountKey = "guest_chat_message_count"
    /// 主动弹层阈值：恰好第 20 条当次插提示气泡 + 弹注册 sheet（>20 不再主动弹）
    static let guestRegisterNudgeThreshold = 20

    /// 访客判定数据源：UserDefaults `server_auth_token`（SettingsScreen/AccountSettingsView
    /// 注册成功写入的 server 会话 token；空 = 未注册 = 访客——「远程模型且无 server token」的 iOS 等价判定）
    var isGuestMode: Bool {
        (UserDefaults.standard.string(forKey: "server_auth_token") ?? "").isEmpty
    }

    /// 访客消息累计发送数（跨会话累计；注册成功清零）
    @Published private(set) var guestMessageCount: Int =
        UserDefaults.standard.integer(forKey: ChatViewModel.guestChatMessageCountKey)
    /// 注册引导 sheet（guest_link / banner 按钮 / 阈值与 403 兜底触发）
    @Published var showRegistrationSheet = false
    /// banner 会话内关闭标记（仅内存态，重启复现——spec banner.show_when dismissedThisSession）
    @Published private(set) var guestBannerDismissed = false

    /// banner 可见性（chat.yaml §4.1 banner.show_when）
    var showsGuestBanner: Bool {
        isGuestMode && guestMessageCount >= Self.guestRegisterNudgeThreshold && !guestBannerDismissed
    }

    /// 打开注册 sheet（空状态 guest_link / banner 按钮）
    func openRegistrationSheet() { showRegistrationSheet = true }

    /// banner 关闭（仅本会话有效）
    func dismissGuestBanner() { guestBannerDismissed = true }

    /// 注册成功（chat sheet verify OK）：计数清零（counter.reset_on=register_success）+ 关 sheet
    func registrationSuccess() {
        Self.resetGuestMessageCount()
        guestMessageCount = 0
        showRegistrationSheet = false
    }

    /// 计数清零（静态：Settings EmailAuth verify 成功同调——注册入口不止 chat）
    nonisolated static func resetGuestMessageCount() {
        UserDefaults.standard.set(0, forKey: "guest_chat_message_count")
    }

    /// 访客发送计数：==20 恰好跨阈值当次插 agent 提示气泡 + 弹注册 sheet（>20 不再弹）
    private func recordGuestSend() {
        // 重读存储：Settings 侧注册成功清零后，本 VM 立即感知（不依赖自身快照）
        guestMessageCount = UserDefaults.standard.integer(forKey: Self.guestChatMessageCountKey)
        guard isGuestMode else { return }
        guestMessageCount += 1
        UserDefaults.standard.set(guestMessageCount, forKey: Self.guestChatMessageCountKey)
        guard guestMessageCount == Self.guestRegisterNudgeThreshold else { return }
        let notice = String(
            format: L("You've chatted %lld rounds with me 🎉 Register to claim free quota, or configure your own LLM Token — either works to keep chatting!"),
            guestMessageCount
        )
        messages.append(ChatMessage(role: .assistant, text: notice))
        touchThread(preview: notice)
        persist()
        showRegistrationSheet = true
    }

    /// 配额耗尽提示气泡（server 403 兜底）+ 弹 sheet
    private func showGuestQuotaExhaustedNudge() {
        let notice = L("Trial quota used up. Register to get 1000 free calls.")
        messages.append(ChatMessage(role: .assistant, text: notice))
        touchThread(preview: notice)
        persist()
        showRegistrationSheet = true
    }

    func stageImage(_ localIdentifier: String) {
        stagedImage = StagedImage(localIdentifier: localIdentifier)
        let lid = localIdentifier
        Task { @MainActor [weak self] in
            let img = await ThumbnailLoader.shared.thumbnail(for: lid, size: CGSize(width: 240, height: 240))
            guard var s = self?.stagedImage, s.localIdentifier == lid else { return }
            s.thumbnail = img
            self?.stagedImage = s
        }
    }
    func unstageImage() { stagedImage = nil }

    private var bridge: ChatAgentBridge?
    /// 流式吐字节奏器（commonMain StreamingPacingController，经 SharedKit 工厂创建）
    private var pacing: StreamingPacingController?
    private var actionWatcher: FlowWatcher?

    /// 侧栏列表：标题或预览模糊过滤（对齐 Android filteredThreads）
    var filteredThreads: [ChatThread] {
        let query = searchQuery.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return threads }
        return threads.filter {
            $0.title.range(of: query, options: .caseInsensitive) != nil ||
            $0.lastMessagePreview.range(of: query, options: .caseInsensitive) != nil
        }
    }

    /// 幂等：同一 bridge 重复 configure（tab 切换 onAppear）不重载磁盘，
    /// 避免覆盖流式中的内存消息（Android 侧 Room Flow 天然无此问题）。
    func configure(bridge: ChatAgentBridge) {
        if self.bridge === bridge { return }
        self.bridge = bridge
        currentSessionId = ChatHistoryStore.shared.currentSessionId
        bridge.setSessionId(id: currentSessionId)
        messages = ChatHistoryStore.shared.loadMessages(sessionId: currentSessionId)
        restoreGachaSelections()
        threads = ChatHistoryStore.shared.loadThreads()
        actionWatcher?.cancel()
        actionWatcher = bridge.watchUiActions { [weak self] dto in
            Task { @MainActor in self?.handleUiAction(dto) }
        }
        // 图表渲染回调：LLM draw_chart → IosChartCapability → ChartJsEngine 产 SVG，
        // 经 ChartRendererBridge.onChart 回到本 ViewModel 追加 CHART 消息。
        ChartRendererBridge.onChart = { [weak self] svg, summary in
            Task { @MainActor in self?.appendChartMessage(svg: svg, summary: summary) }
        }
        // 编辑结果回链：chat EDIT → PhotoEditorScreen 保存 → 落盘路径回此追加 AGENT_EDIT_RESULT
        ChatEditResultBridge.onEditResult = { [weak self] path in
            Task { @MainActor in self?.appendEditResultMessage(imagePath: path) }
        }
    }

    // MARK: - Send (对齐 Android sendMessage 流程)

    /// 发往远程 LLM 的输入：带暂存图时注入图片标识前缀（对齐 Android ChatViewModel.kt:1191
    /// `"[用户选择了图片：$imageUri，请基于这张图片处理] $text"`），LLM 据此在 ai_optimize 等
    /// 工具调用中回传该标识；注入的是标识字符串非像素（[PRIVACY] 图片文件不上传远程）。
    static func llmInput(text: String, stagedImageUri: String?) -> String {
        guard let uri = stagedImageUri, !uri.isEmpty else { return text }
        return "[用户选择了图片：\(uri)，请基于这张图片处理] \(text)"
    }

    /// App 界面语言 → ReplyLanguage 枚举 name（与 Android commonMain `toReplyLanguage` 同规则）
    private static func currentReplyLanguage() -> String {
        switch AppSettings.shared.appLanguage {
        case "english": return "ENGLISH"
        case "chinese_simplified": return "SIMPLIFIED_CHINESE"
        case "chinese_traditional": return "TRADITIONAL_CHINESE"
        case "spanish": return "SPANISH"
        case "french": return "FRENCH"
        default:
            // 加固：iOS Locale.preferredLanguages 可能产出下划线形式（如 "zh_Hant_TW"），
            // 先统一替换成连字符再做 contains("-hant") 等判断，保证两种形态都正确分流；
            // yue（粤语）按与 zh 相同规则解析：iOS 粤语系统语言下 UI 回退繁中，chat 须一致
            let tag = (Locale.preferredLanguages.first ?? "en")
                .lowercased()
                .replacingOccurrences(of: "_", with: "-")
            if tag == "es" || tag.hasPrefix("es-") { return "SPANISH" }
            if tag == "fr" || tag.hasPrefix("fr-") { return "FRENCH" }
            guard tag == "zh" || tag.hasPrefix("zh-") ||
                    tag == "yue" || tag.hasPrefix("yue-") else { return "ENGLISH" }
            return (tag.contains("-hant") || tag.contains("-tw") ||
                    tag.contains("-hk") || tag.contains("-mo"))
                ? "TRADITIONAL_CHINESE" : "SIMPLIFIED_CHINESE"
        }
    }

    func send(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)

        // CHART 触发链 demo：经 CapabilityRegistry 派发 DrawChart（确定性验证 capability→bridge→渲染）
        if trimmed.lowercased() == "/charttool" {
            emitChartViaToolChain()
            return
        }
        // CHART 渲染 demo：直接调 ChartJsEngine（验证渲染层，不经触发链）
        if trimmed.lowercased().hasPrefix("/chart") {
            emitChartDemo()
            return
        }

        // run_gallery_script 触发链 demo：经 CapabilityRegistry 派发 ExecuteScript（确定性验证
        // IosRunScriptCapability → RunScriptBridge → JsRuntime+JsCoreEngine → gallery handler）
        if trimmed.lowercased() == "/runscript" {
            emitRunScriptDemo()
            return
        }

        // AGENT_EDIT_RESULT 渲染 demo：生成图落盘 → 追加编辑结果消息（确定性验证，/chart 同款）
        if trimmed.lowercased() == "/editdemo" {
            emitEditResultDemo()
            return
        }

        // EDIT 意图：有暂存图 → 跳编辑器（对齐 Android EDIT，不发推理）
        if let staged = stagedImage, stagedIntent == .edit {
            onEditImage?(staged.localIdentifier)
            stagedImage = nil
            return
        }
        // 有暂存图 + 无文本 + 非 EDIT：UNDERSTAND/FIND_SIMILAR 端侧引擎本版不可用
        if trimmed.isEmpty, stagedImage != nil {
            unavailableNotice = String(localized: "On-device image understanding and search-by-image are not available in this version.")
            return
        }

        guard !trimmed.isEmpty, !isProcessing else { return }
        guard let bridge else { return }

        // 发新消息：本会话 pending 卡组过期（§17 expired_semantics；dismiss 反馈落库）
        gachaController.discardPending(sessionId: currentSessionId)

        // 带图发消息：图片标识（PHAsset localIdentifier）注入推理文本，LLM 据此回传
        // ai_optimize(imageUri:) 等工具调用（对齐 Android ChatViewModel.kt:1191 格式；
        // 注入的是标识字符串非像素——[PRIVACY] 图片文件不上传远程）
        let stagedLocalId = stagedImage?.localIdentifier

        // 1. user 消息即追加（带暂存图 → userImageText 上图下文；图引用 localIdentifier，
        //    远程只发文本——图片像素不上传，隐私红线）
        messages.append(ChatMessage(
            role: .user,
            text: trimmed,
            type: stagedImage != nil ? .userImageText : .userText,
            imageUri: stagedImage?.localIdentifier
        ))
        autoTitleIfNeeded(firstUserText: trimmed)
        touchThread(preview: trimmed)
        persist()
        // 访客发送计数 + 阈值弹层（chat.yaml §4.1；==20 当次插提示气泡 + 开注册 sheet）
        recordGuestSend()
        // 带图发文本：远程只收文本（图片像素不上传，隐私红线）；暂存图消费掉
        stagedImage = nil

        // 2. assistant 占位：thinking 态（首 token 前显示 3 点动画）
        let placeholderId = UUID()
        messages.append(ChatMessage(
            id: placeholderId, role: .assistant,
            text: "", isStreaming: true, isThinking: true
        ))
        isProcessing = true

        // 节奏器（豆包风逐字吐）：onPaced 在 main 回调，按 50ms/字推进文本 + 光标可见性
        pacing = createStreamingPacingController(onPaced: { [weak self] text, cursor in
            guard let self else { return }
            guard let idx = self.messages.firstIndex(where: { $0.id == placeholderId }) else { return }
            self.messages[idx].isThinking = false
            self.messages[idx].isToolCalling = false
            self.messages[idx].text = text
            self.messages[idx].showCursor = cursor.boolValue
        })
        pacing?.start()

        // 3. 启动推理（bridge 已指向当前 sessionId，LLM 记忆按会话隔离）
        bridge.sendMessage(
            input: Self.llmInput(text: trimmed, stagedImageUri: stagedLocalId),
            persona: UserDefaults.standard.string(forKey: "assistant_persona") ?? "DEFAULT",
            replyLanguage: Self.currentReplyLanguage(),
            onText: { [weak self] snapshot in
                Task { @MainActor in
                    // 首 token 到达：喂给节奏器（不直接写 UI，节奏器按字符时间轴推进）
                    self?.pacing?.onTextSnapshot(fullText: snapshot)
                }
            },
            onToolCall: { [weak self] in
                Task { @MainActor in
                    // 工具调用：清节奏器缓冲（避免用旧全文覆盖状态文案）
                    self?.pacing?.reset()
                    self?.toolCallingUpdate(id: placeholderId)
                }
            },
            onComplete: { [weak self] summary, errorMessage in
                Task { @MainActor in
                    self?.pacing?.finish()
                    self?.completeMessage(id: placeholderId, summary: summary, errorMessage: errorMessage)
                    self?.isProcessing = false
                }
            }
        )
    }

    // MARK: - 会话管理（对齐 Android switchSession/newSession/renameSession/deleteSession）

    /// 切换会话：换 UI 消息 + 换 bridge memory ID，持久化当前会话
    func switchSession(_ sessionId: String) {
        guard sessionId != currentSessionId else { return }
        persist()
        // 切会话：旧会话 pending 卡组过期（dismiss 反馈落库）
        gachaController.discardPending(sessionId: currentSessionId)
        currentSessionId = sessionId
        ChatHistoryStore.shared.currentSessionId = sessionId
        bridge?.setSessionId(id: sessionId)
        messages = ChatHistoryStore.shared.loadMessages(sessionId: sessionId)
        restoreGachaSelections()
        gachaRerolling = []
    }

    /// 新建会话并切换过去（顶栏 + / 侧栏 +）
    func newSession() {
        let sessionId = UUID().uuidString
        ChatHistoryStore.shared.upsertThread(ChatThread(
            sessionId: sessionId,
            title: String(localized: "New Chat")
        ))
        threads = ChatHistoryStore.shared.loadThreads()
        switchSession(sessionId)
    }

    /// 重命名会话（空白忽略；重命名后不再被自动标题覆盖）
    func renameSession(_ sessionId: String, newTitle: String) {
        let trimmed = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              var thread = threads.first(where: { $0.sessionId == sessionId }) else { return }
        thread.title = trimmed
        ChatHistoryStore.shared.upsertThread(thread)
        threads = ChatHistoryStore.shared.loadThreads()
    }

    /// 删除会话：删消息 + 会话记录 + 该会话 LLM 记忆；删除当前会话回退 default
    func deleteSession(_ sessionId: String) {
        ChatHistoryStore.shared.deleteThread(sessionId: sessionId)
        bridge?.clearHistory(sessionId: sessionId) { }
        // 删会话：该会话 pending 卡组过期（dismiss 反馈落库）
        gachaController.discardPending(sessionId: sessionId)
        if currentSessionId == sessionId {
            let fallback = ChatHistoryStore.defaultSessionId
            currentSessionId = fallback
            ChatHistoryStore.shared.currentSessionId = fallback
            bridge?.setSessionId(id: fallback)
            messages = ChatHistoryStore.shared.loadMessages(sessionId: fallback)
            restoreGachaSelections()
        }
        threads = ChatHistoryStore.shared.loadThreads()
    }

    /// 清空当前会话（顶栏 delete_sweep）：清当前会话消息 + 当前会话 LLM 记忆
    func clearHistory() {
        guard let bridge else { return }
        let sessionId = currentSessionId
        // 清空：本会话 pending 卡组过期（dismiss 反馈落库）
        gachaController.discardPending(sessionId: sessionId)
        // UI 消息与预览同步清（文件操作不依赖异步回调，避免清记忆途中切会话漏清）
        messages = []
        gachaSelections = [:]
        gachaRerolling = []
        ChatHistoryStore.shared.clearMessages(sessionId: sessionId)
        touchThread(preview: "")
        bridge.clearCurrentHistory { }
    }

    deinit {
        actionWatcher?.cancel()
    }

    // MARK: - Streaming State Updates

    // streamingUpdate 已由节奏器 onPaced 内联替代（见 send() 中 pacing 创建）

    /// 工具调用开始：显示状态文案
    private func toolCallingUpdate(id: UUID) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].isThinking = false
        messages[idx].isToolCalling = true
        messages[idx].text = String(localized: "Calling tools…")  // 正在调用工具…
    }

    /// 推理完成
    private func completeMessage(id: UUID, summary: String, errorMessage: String?) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].isStreaming = false
        messages[idx].isThinking = false
        messages[idx].isToolCalling = false
        if let errorMessage, !errorMessage.isEmpty {
            messages[idx].text = errorMessage
            messages[idx].error = errorMessage
            // 访客配额耗尽硬兜底（chat.yaml §4.1 triggers.quota_exceeded）：server 403 body
            // 全程透传到 errorMessage（contracts.md §2 已验证），含 "quota_exceeded" 即插提示 + 弹 sheet
            if isGuestMode, errorMessage.localizedCaseInsensitiveContains("quota_exceeded") {
                showGuestQuotaExhaustedNudge()
            }
        } else {
            messages[idx].text = summary.isEmpty ? String(localized: "(No response)") : summary
        }
        touchThread(preview: messages[idx].text)
        persist()
    }

    // MARK: - UI Actions（媒体结果 = 独立消息）

    private func handleUiAction(_ dto: ChatUiActionDto) {
        switch dto.kind {
        case "media_results":
            // 空结果不出卡片：Android uiActions 收集器以 assets.isNotEmpty() 为门，
            // 空结果由 LLM 在最终回复里说明（避免多轮空搜索刷出多条「未找到」气泡）
            guard dto.totalCount > 0, !dto.mediaIds.isEmpty else { return }
            let ids = dto.mediaIds.map { $0.int64Value }
            let header = String(localized: "Found \(dto.totalCount) results for「\(dto.query)」")
            // 回合内 upsert 去重（chat.yaml §9 per_turn_upsert，c4cea4995 定稿口径）：
            // 同一用户回合（最后一条 user 消息之后）至多一张横滑卡——查本回合上一张卡，
            // 复用其 id/timestamp 原位替换，卡片位置不动（禁 remove+append 把卡移到回合底部）。
            // TODO(ios-follow): FIND_SIMILAR 以图搜图自成新回合（replacePreviousInTurn=false，
            // 追加新卡不覆盖上一回合结果）；iOS 引擎为 stub（send() 直接出不可用提示），
            // 追加分支随引擎落地补齐。
            // 注：LLM 拒绝措辞回退直搜 iOS 无此路径（media_results 仅本入口单一来源），
            // 「本回合已出卡则跳过回退」守卫无从挂载；后续引入回退直搜时须补。
            if let lastUser = messages.lastIndex(where: { $0.role == .user }),
               let prevCard = messages[lastUser...].lastIndex(where: { $0.type == .mediaResults }) {
                let previous = messages[prevCard]
                messages[prevCard] = ChatMessage(
                    id: previous.id,
                    role: .assistant,
                    text: header,
                    timestamp: previous.timestamp,
                    type: .mediaResults,
                    mediaIds: ids,
                    mediaQuery: dto.query,
                    mediaTotalCount: Int(truncatingIfNeeded: dto.totalCount)
                )
            } else {
                messages.append(ChatMessage(
                    role: .assistant,
                    text: header,
                    type: .mediaResults,
                    mediaIds: ids,
                    mediaQuery: dto.query,
                    mediaTotalCount: Int(truncatingIfNeeded: dto.totalCount)
                ))
            }
            touchThread(preview: header)
            persist()
        case "text_reply":
            // 工具文本已作为 observation 回传 LLM（ChatToolService.dispatchCommand），
            // 最终答复经 onComplete 写入同一气泡；不追加独立气泡
            //（对齐 Android uiActions 收集器 TextReply → else{} 忽略，修复回复被切成多条）
            break
        case "error":
            // 对齐 Android：错误以 ❌ 气泡可见（ChatViewModel.kt:1419），不由 LLM 总结掩盖
            let text = "❌ \(dto.message)"
            messages.append(ChatMessage(role: .assistant, text: text))
            touchThread(preview: text)
            persist()
        case "success":
            // 对齐 Android describeCommandResult（ChatViewModel.kt:1409）：✅ 已执行 {command}
            let text = String(format: String(localized: "chat.command_executed"), dto.message)
            messages.append(ChatMessage(role: .assistant, text: text))
            touchThread(preview: text)
            persist()
        case "ai_optimize":
            // AI 一键优化：capability 仅产 observation（AiOptimizeBridge 固定预设路径），
            // 抽卡由 UI 层触发（chat.yaml §17 trigger——gacha 不入 LLM 工具链）
            handleAiOptimizeAction(dto)
        default:
            break
        }
    }

    // MARK: - AI 优化抽卡（chat.yaml §17）

    /// ai_optimize 动作 → 抽卡（draw）→ 出卡条消息（伴随 agent 文本气泡=场景解释句）
    /// 或降级单发（fallback_chain：unavailable / 缩略图全灭 / 无可用源图）。
    private func handleAiOptimizeAction(_ dto: ChatUiActionDto) {
        // ReAct 同轮可能重复派发（流式动作重放）：在途即忽略
        guard !gachaDrawInFlight else { return }
        gachaDrawInFlight = true
        let sessionId = currentSessionId
        let messageId = UUID()
        let imageUri = dto.imageUri
        let fallbackUri = lastUserImageUri()
        Task { @MainActor in
            defer { gachaDrawInFlight = false }
            let outcome = await gachaController.draw(
                messageId: messageId,
                imageUri: imageUri,
                sessionId: sessionId,
                fallbackImageUri: fallbackUri)
            // 抽卡期间切了会话：按过期处理（dismiss 反馈落库），不向新会话插入消息
            guard currentSessionId == sessionId else {
                gachaController.discardPending(sessionId: sessionId)
                return
            }
            switch outcome {
            case .candidates(let payload, let explanation):
                messages.append(ChatMessage(
                    id: messageId, role: .assistant,
                    text: explanation, type: .optimizeCandidates, gacha: payload))
                // 选中态初值 = 推荐卡（KeepOriginal=-1 不预选）
                gachaSelections[messageId] = payload.recommendedIndex
                touchThread(preview: explanation)
                persist()
            case .fallback(let imagePath, let explanation):
                // 降级单发：含图（固定预设全尺寸渲染落盘）或纯文本解释
                // 图消息走 AGENT_IMAGE 契约（FillWidth 240 完整显示，chat.yaml §5 image_content.agent_image）
                if let imagePath {
                    messages.append(ChatMessage(
                        role: .assistant, text: explanation,
                        type: .agentImage, imageUri: imagePath))
                } else {
                    messages.append(ChatMessage(role: .assistant, text: explanation))
                }
                touchThread(preview: explanation)
                persist()
            }
        }
    }

    /// 卡条 interactive 判定（pending 组存在；过期即只读——无按钮行、不改选中）
    func isGachaInteractive(_ messageId: UUID) -> Bool {
        gachaController.hasPending(messageId)
    }

    /// 点卡：改选中（全屏预览由 ChatView onCardTap 打开）
    func selectGachaCard(messageId: UUID, index: Int) {
        gachaSelections[messageId] = index
    }

    /// 换一组：以 usedFingerprints 为 exclude 重抽 → 覆写原消息候选（drawIndex+1）
    func rerollGacha(messageId: UUID) {
        guard !gachaRerolling.contains(messageId),
              messages.contains(where: { $0.id == messageId && $0.type == .optimizeCandidates }) else { return }
        gachaRerolling.insert(messageId)
        Task { @MainActor in
            defer { gachaRerolling.remove(messageId) }
            switch await gachaController.reroll(messageId: messageId) {
            case .replaced(let payload, let explanation):
                // 覆写原消息候选与伴随解释（await 后按 id 重找——列表可能已变动）
                guard let idx = messages.firstIndex(where: { $0.id == messageId }) else { return }
                messages[idx].gacha = payload
                messages[idx].text = explanation
                gachaSelections[messageId] = payload.recommendedIndex
                persist()
            case .unavailable:
                // 引擎不可用/重抽全灭：pending 保持不动，卡条仍可确认既有卡
                unavailableNotice = String(localized: "chat_gacha_reroll_unavailable")
            }
        }
    }

    /// 就用这张：全尺寸渲染 → 落 Documents/chat_edits → 原消息改写为 agentEditResult
    /// （saved=false 语义——文件仅落 App 沙盒，气泡文案不标注「已存相册」；
    /// 失败 pending 已回填，卡条保持可重试 + toast）
    func confirmGacha(messageId: UUID) {
        guard let selection = gachaSelections[messageId], selection >= 0,
              let msg = messages.first(where: { $0.id == messageId && $0.type == .optimizeCandidates }) else { return }
        let sessionId = currentSessionId
        let explanation = msg.text
        Task { @MainActor in
            guard let path = await gachaController.confirm(messageId: messageId, candidateIndex: selection) else {
                unavailableNotice = String(localized: "chat_gacha_confirm_failed")
                return
            }
            // 渲染期间切了会话：反馈已落库、图已写盘，仅放弃 UI 改写（不污染新会话）
            guard currentSessionId == sessionId else { return }
            if let idx = messages.firstIndex(where: { $0.id == messageId }) {
                // 原地改写：气泡正文保留场景解释句，图换为全尺寸渲染结果
                // （AGENT_IMAGE 契约：对齐 Android confirm 改写 type="agent_image"）
                messages[idx].type = .agentImage
                messages[idx].imageUri = path
                messages[idx].gacha = nil
                gachaSelections.removeValue(forKey: messageId)
                touchThread(preview: explanation)
                persist()
            } else {
                messages.append(ChatMessage(
                    role: .assistant, text: explanation,
                    type: .agentImage, imageUri: path))
                persist()
            }
        }
    }

    /// 兜底链：会话最近一张用户图标识（LLM 未传/解析失败时以其为优化目标）
    private func lastUserImageUri() -> String? {
        messages.last(where: { message in message.role == .user && message.imageUri != nil })?.imageUri
    }

    /// 历史载入后恢复卡条选中态（初值=recommendedIndex）。pending 组为进程级内存态，
    /// 冷启后一律只读过期（ChatView interactive=false）；selections 仅驱动渲染。
    private func restoreGachaSelections() {
        var restored: [UUID: Int] = [:]
        for msg in messages where msg.type == .optimizeCandidates {
            if let payload = msg.gacha {
                restored[msg.id] = payload.recommendedIndex
            }
        }
        gachaSelections = restored
    }

    // MARK: - 会话标题与索引

    /// 首条用户消息自动生成标题：仅当标题仍为默认值时覆盖（对齐 Android updateSessionTitleIfDefault）
    private func autoTitleIfNeeded(firstUserText: String) {
        guard var thread = threads.first(where: { $0.sessionId == currentSessionId }) else {
            // default 会话首次发消息时补建会话记录（对齐 Android ensureSessionExists）
            let thread = ChatThread(
                sessionId: currentSessionId,
                title: Self.sanitizeTitle(firstUserText)
            )
            ChatHistoryStore.shared.upsertThread(thread)
            threads = ChatHistoryStore.shared.loadThreads()
            return
        }
        guard Self.isDefaultTitle(thread.title) else { return }
        thread.title = Self.sanitizeTitle(firstUserText)
        ChatHistoryStore.shared.upsertThread(thread)
        threads = ChatHistoryStore.shared.loadThreads()
    }

    /// 更新会话预览与更新时间（对齐 Android touchSession）
    private func touchThread(preview: String) {
        guard var thread = threads.first(where: { $0.sessionId == currentSessionId }) else { return }
        thread.updatedAt = Date()
        thread.lastMessagePreview = String(preview.prefix(50))
        ChatHistoryStore.shared.upsertThread(thread)
        threads = ChatHistoryStore.shared.loadThreads()
    }

    /// 默认标题判定（对齐 Android isDefaultTitle：空 / "New Chat" / "Chat" 及其本地化形式）
    private static func isDefaultTitle(_ title: String) -> Bool {
        if title.isEmpty { return true }
        // 英文常量 fallback（历史数据）+ 当前语言本地化形式，避免非英文下漏判
        let newChatEn = "New Chat"
        let chatEn = "Chat"
        if title == newChatEn || title == chatEn { return true }
        return title == String(localized: "New Chat") || title == String(localized: "Chat")
    }

    /// 标题清洗（对齐 Android ChatTitleGenerator.sanitizeTitle：
    /// 去首尾标点、换行/连续空白折叠、>20 字符截断加 …）
    static func sanitizeTitle(_ content: String) -> String {
        let fallback = String(localized: "New Chat")
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return fallback }

        let trimChars = CharacterSet(charactersIn: ".,!?;:。，！？；：\"'「」『』()（）[]【】{}")
        let collapsed = trimmed
            .components(separatedBy: .newlines).joined(separator: " ")
            .components(separatedBy: .whitespaces).filter { !$0.isEmpty }.joined(separator: " ")
        var cleaned = collapsed.trimmingCharacters(in: trimChars)

        if cleaned.count > 20 {
            cleaned = String(cleaned.prefix(20)).trimmingCharacters(in: trimChars) + "…"
        }
        return cleaned.isEmpty ? fallback : cleaned
    }

    // MARK: - CHART（LLM draw_chart → ChartJsEngine 渲染 + 手动 demo）

    /// 追加一条 CHART 消息（图卡）。LLM draw_chart（经 IosChartCapability → ChartRendererBridge.onChart）
    /// 与 /chart 手动 demo 共用此落点。
    private func appendChartMessage(svg: String, summary: String) {
        var msg = ChatMessage(role: .assistant, text: summary, type: .chart)
        msg.chartSvg = svg
        messages.append(msg)
        touchThread(preview: summary)
        persist()
    }

    /// 手动触发一张示例图，验证 ChartJsEngine(JSCore+chart_bootstrap.js) → ChartSvgCard 端到端。
    private func emitChartDemo() {
        guard let r = ChartJsEngine.render(
            type: "bar", title: "Chart Demo",
            labels: ["A", "B", "C", "D"], values: [3, 7, 2, 5], unit: nil
        ) else { return }
        appendChartMessage(svg: r.svg, summary: r.summary)
    }

    /// /charttool：经 CapabilityRegistry 派发 DrawChart，跑通完整触发链（不依赖 LLM）。
    /// 图卡经 ChartRendererBridge.onChart 追加；此处仅兜底 dispatch 失败。
    private func emitChartViaToolChain() {
        guard let bridge else { return }
        bridge.dispatchDrawChart(
            type: "bar", title: "Chart via draw_chart",
            labels: ["一月", "二月", "三月"], valuesCsv: "10,15,8", unit: "张"
        ) { [weak self] _, errorMessage in
            Task { @MainActor in
                guard let self, let errorMessage, !errorMessage.isEmpty else { return }
                self.messages.append(
                    ChatMessage(role: .assistant,
                                text: String(format: String(localized: "chat.chart_failed"), errorMessage),
                                error: errorMessage)
                )
                self.persist()
            }
        }
    }

    // MARK: - run_gallery_script（LLM run_gallery_script → JsRuntime 端侧沙箱 + 确定性 demo）

    /// /runscript：经 CapabilityRegistry 派发 ExecuteScript，跑通 run_gallery_script 完整触发链（不依赖 LLM）。
    /// 脚本 `await bridge.callAsync('gallery.summary')` 取相册盘点并组合成可读文案，return 后作为 agent 文本消息追加。
    private func emitRunScriptDemo() {
        guard let bridge else { return }
        // JS：${...} 是 JS 模板插值（非 Swift \( )，原样透传给 JsCoreEngine。
        let script = """
        const s = await bridge.callAsync('gallery.summary', {});
        return `相册共 ${s.totalMedia} 个媒体（照片 ${s.totalPhotos}、视频 ${s.totalVideos}）；` +
          `已打标 ${s.labeledCount}，未打标 ${s.unlabeledCount}；人物聚类 ${s.personClusterCount}（已命名 ${s.namedPersonCount}）。`;
        """
        bridge.dispatchRunScript(code: script) { [weak self] result, errorMessage in
            Task { @MainActor in
                guard let self else { return }
                if let errorMessage, !errorMessage.isEmpty {
                    self.messages.append(
                        ChatMessage(role: .assistant,
                                    text: String(format: String(localized: "chat.script_failed"), errorMessage),
                                    error: errorMessage)
                    )
                } else {
                    self.messages.append(ChatMessage(role: .assistant, text: result))
                }
                self.persist()
            }
        }
    }

    // MARK: - AGENT_EDIT_RESULT（chat EDIT → 编辑器 → 结果图回链）

    /// 追加编辑结果消息（图=Documents/chat_edits 文件路径；文案标注已存相册——iOS 编辑器
    /// 保存时已入库，与 Android「chat 内保存按钮」为有意分歧，见 plan 范围裁决）。
    private func appendEditResultMessage(imagePath: String) {
        let caption = String(localized: "Edit complete. Result saved to Photos.")
        messages.append(ChatMessage(role: .assistant, text: caption, type: .agentEditResult, imageUri: imagePath))
        touchThread(preview: caption)
        persist()
    }

    /// /editdemo：生成一张渐变图落盘并追加编辑结果消息（确定性验证渲染链，不经编辑器）。
    private func emitEditResultDemo() {
        let size = CGSize(width: 600, height: 400)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            let colors = [UIColor.systemBlue.cgColor, UIColor.systemTeal.cgColor]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                      colors: colors as CFArray, locations: [0, 1])!
            ctx.cgContext.drawLinearGradient(
                gradient, start: .zero,
                end: CGPoint(x: size.width, y: size.height), options: []
            )
        }
        guard let data = image.jpegData(compressionQuality: 0.9),
              let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let dir = docs.appendingPathComponent("chat_edits", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("demo-\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
            appendEditResultMessage(imagePath: url.path)
        } catch {
            messages.append(ChatMessage(role: .assistant, text: "demo 图片写入失败：\(error.localizedDescription)", error: error.localizedDescription))
            persist()
        }
    }

    // MARK: - Persistence

    private func persist() {
        // 只持久化非流式消息，落当前会话文件
        let persisted = messages.filter { !$0.isStreaming }
        ChatHistoryStore.shared.saveMessages(sessionId: currentSessionId, messages: persisted)
    }
}
