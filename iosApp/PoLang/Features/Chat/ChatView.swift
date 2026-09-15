import SwiftUI
import Photos
import PhotosUI
import SharedKit

/// Chat 主视图（1:1 对标 Android ChatScreen.kt）。
/// spec: specs/screens/chat.yaml
struct ChatView: View {
    /// 返回动作（pager 场景 = 回相册页）；nil 时返回键占位
    var onBack: (() -> Void)? = nil
    /// 媒体结果「查看全部」回相册并带入搜索词（MainTabView 接线：切 tab 1 + 注入 query）
    var onNavigateToGallery: ((String) -> Void)? = nil
    /// EDIT 意图：跳 PhotoEditorScreen(localIdentifier:)（MainTabView 接线）
    var onEditImage: ((String) -> Void)? = nil

    @StateObject private var viewModel = ChatViewModel()
    /// 全屏图片预览（chat 图/媒体卡点击打开）
    @State private var previewImage: UIImage?
    @EnvironmentObject private var container: AppContainer
    @State private var inputText = ""
    @FocusState private var inputFocused: Bool
    @State private var showClearConfirm = false
    @State private var isSidebarOpen = false
    @State private var showPhotoPicker = false
    /// 诚实占位：功能未实现时的说明（spec §11 允许差异外的项后续补齐）
    @State private var comingSoonFeature: String? = nil
    /// 「自己的 Token」入口：全屏打开设置·远程模型页（chat.yaml §4.1 secondary_cta）
    @State private var showTokenConfig = false

    var body: some View {
        ZStack(alignment: .leading) {
            VStack(spacing: 0) {
                chatTopBar

                if viewModel.messages.isEmpty {
                    // 包 ScrollView：系统键盘避让对 ScrollView 生效（greedy VStack 不生效→输入栏被遮）。
                    // GeometryReader 给 ChatEmptyState 明确全高，保内部居中布局不被 ScrollView 破坏；
                    // v3 内容超高可滚（chat.yaml §4 scrollable: true）。
                    GeometryReader { geo in
                        ScrollView {
                            ChatEmptyState(
                                isGuestMode: viewModel.isGuestMode,
                                onExampleTap: { prompt in
                                    viewModel.send(prompt)  // 直接发送，不填充输入框
                                },
                                onGuestLinkTap: { viewModel.openRegistrationSheet() }
                            )
                            .frame(width: geo.size.width)
                            .frame(minHeight: geo.size.height)
                        }
                        .scrollDismissesKeyboard(.interactively)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { inputFocused = false }  // 点空白收键盘
                } else {
                    messageList  // 内含 .scrollDismissesKeyboard：下滑收键盘
                }

                inputBar
            }

            // 会话历史侧栏（spec §2.5）：常驻渲染 + offset 抽屉（条件插入在真机上偶发不展开）
            Color.black.opacity(isSidebarOpen ? 0.3 : 0)
                .ignoresSafeArea()
                .allowsHitTesting(isSidebarOpen)
                .onTapGesture { withAnimation { isSidebarOpen = false } }

            ChatThreadSidebarView(
                threads: viewModel.filteredThreads,
                currentSessionId: viewModel.currentSessionId,
                searchQuery: $viewModel.searchQuery,
                onThreadSelected: { sessionId in
                    viewModel.switchSession(sessionId)
                    withAnimation { isSidebarOpen = false }
                },
                onNewChat: {
                    viewModel.newSession()
                    withAnimation { isSidebarOpen = false }
                },
                onRename: { sessionId, newTitle in
                    viewModel.renameSession(sessionId, newTitle: newTitle)
                },
                onDelete: { sessionId in
                    viewModel.deleteSession(sessionId)
                },
                onDismiss: { withAnimation { isSidebarOpen = false } }
            )
            .offset(x: isSidebarOpen ? 0 : -300)
            .allowsHitTesting(isSidebarOpen)
            .accessibilityHidden(!isSidebarOpen)
        }
        .background(Color(.systemBackground).ignoresSafeArea())
        .onAppear {
            if let bridge = container.chatBridge {
                viewModel.configure(bridge: bridge)
            }
            viewModel.onEditImage = { lid in self.onEditImage?(lid) }
        }
        .confirmationDialog(
            String(localized: "Clear conversation?"),
            isPresented: $showClearConfirm,
            titleVisibility: .visible
        ) {
            Button(String(localized: "Clear"), role: .destructive) {
                viewModel.clearHistory()
            }
            Button(String(localized: "Cancel"), role: .cancel) {}
        }
        .alert(
            String(localized: "Coming Soon"),
            isPresented: Binding(
                get: { comingSoonFeature != nil },
                set: { if !$0 { comingSoonFeature = nil } }
            )
        ) {
            Button(String(localized: "OK"), role: .cancel) {}
        } message: {
            Text(comingSoonFeature ?? "")
        }
        .alert(
            String(localized: "Unavailable"),
            isPresented: Binding(
                get: { viewModel.unavailableNotice != nil },
                set: { if !$0 { viewModel.unavailableNotice = nil } }
            )
        ) {
            Button(String(localized: "OK"), role: .cancel) {}
        } message: {
            Text(viewModel.unavailableNotice ?? "")
        }
        .sheet(isPresented: $showPhotoPicker) {
            // 相册单选 picker（PHPicker，取 assetIdentifier = PHAsset localIdentifier）
            ChatPhotoPicker { localIdentifier in
                viewModel.stageImage(localIdentifier)
                showPhotoPicker = false
            }
            .ignoresSafeArea()
        }
        // 访客注册引导 sheet（chat.yaml §4.1 sheet：guest_link / banner 按钮 / 阈值与 403 触发）
        .sheet(isPresented: $viewModel.showRegistrationSheet) {
            ChatRegistrationSheet(
                usedCount: viewModel.guestMessageCount,
                onRegisterSuccess: { viewModel.registrationSuccess() },
                onUseOwnToken: {
                    viewModel.showRegistrationSheet = false
                    showTokenConfig = true
                }
            )
            .presentationDetents([.medium, .large])
        }
        // 「自己的 Token」：全屏打开设置·远程模型页（等价 Android 跳 Settings provider 配置页；
        // 复用 ModelCenterView（只引用不改动），关闭按钮由本包装层提供）
        .fullScreenCover(isPresented: $showTokenConfig) {
            NavigationStack {
                ModelCenterView()
                    .environmentObject(ModelConfigStore.shared)
                    .toolbar {
                        ToolbarItem(placement: .navigationBarLeading) {
                            Button { showTokenConfig = false } label: {
                                MatIcon(name: "mat_o_close", size: 20)
                                    .foregroundColor(Color(.label))
                            }
                            .accessibilityIdentifier("chat_token_config_close")
                        }
                    }
            }
        }
    }

    // MARK: - Top Bar (48dp, 无标题，spec §2)

    private var chatTopBar: some View {
        HStack(spacing: TopBarTokens.spacing) {
            // 返回（pager 场景回相册页）
            Button { onBack?() } label: {
                MatIcon(name: "mat_o_arrow_back", size: TopBarTokens.iconSize)
                    .foregroundColor(Color(.label))
            }
            .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            .accessibilityIdentifier("chat_back")

            // 菜单（打开会话历史侧栏，spec §2.5）
            Button { withAnimation { isSidebarOpen = true } } label: {
                MatIcon(name: "mat_o_menu", size: TopBarTokens.iconSize)
                    .foregroundColor(Color(.label))
            }
            .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            .accessibilityIdentifier("chat_menu")

            Spacer()

            // 上报问题（Android 走 /v1/report-issue 建 GitHub issue，iOS 通道未接）
            Button { comingSoonFeature = String(localized: "Issue reporting is not available in this version.") } label: {
                MatIcon(name: "mat_o_bug_report", size: TopBarTokens.iconSize)
                    .foregroundColor(Color(.label))
            }
            .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            .accessibilityIdentifier("chat_report")

            // 新对话（= 新建会话并切换，对齐 Android onNewChat；非清空当前会话）
            Button { viewModel.newSession() } label: {
                MatIcon(name: "mat_o_add_comment", size: TopBarTokens.iconSize)
                    .foregroundColor(Color(.label))
            }
            .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            .accessibilityIdentifier("chat_new")

            // 清空对话（仅有消息时显示）
            if !viewModel.messages.isEmpty {
                Button { showClearConfirm = true } label: {
                    MatIcon(name: "mat_o_delete_sweep", size: TopBarTokens.iconSize)
                        .foregroundColor(Color(.label))
                }
                .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
                .accessibilityIdentifier("chat_clear")
            }
        }
        .padding(.horizontal, TopBarTokens.horizontalPadding)
        .frame(height: TopBarTokens.height)
        .background(Color(.systemBackground))
    }

    // MARK: - Message List

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(viewModel.messages) { msg in
                        MessageBubble(
                            message: msg,
                            onNavigateToGallery: onNavigateToGallery,
                            onImageTap: { img in
                                if let img { previewImage = img }
                            },
                            onMediaTap: { lid in openPreview(localIdentifier: lid) },
                            gachaInteractive: viewModel.isGachaInteractive(msg.id),
                            gachaSelectedIndex: viewModel.gachaSelections[msg.id],
                            gachaRerolling: viewModel.gachaRerolling.contains(msg.id),
                            onGachaSelection: { index in
                                viewModel.selectGachaCard(messageId: msg.id, index: index)
                            },
                            onGachaReroll: {
                                viewModel.rerollGacha(messageId: msg.id)
                            },
                            onGachaConfirm: {
                                viewModel.confirmGacha(messageId: msg.id)
                            },
                            onGachaCardTap: { thumbPath in
                                openGachaPreview(thumbPath: thumbPath)
                            }
                        )
                        .id(msg.id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.top, 12)
                .padding(.bottom, 8)
            }
            .scrollDismissesKeyboard(.interactively)  // 下滑消息列表收起键盘
            .onTapGesture { inputFocused = false }  // 点消息/空白收键盘（tap 与 scroll 手势不冲突）
            .fullScreenCover(isPresented: Binding(
                get: { previewImage != nil },
                set: { if !$0 { previewImage = nil } }
            )) {
                if let previewImage {
                    ChatImagePreview(image: previewImage)
                }
            }
            .onChange(of: viewModel.messages.count) { _ in scrollToBottom(proxy) }
            .onChange(of: viewModel.messages.last?.text) { _ in scrollToBottom(proxy) }
        }
    }

    /// 媒体卡全屏：localIdentifier → 原图 async 载入后打开预览。
    private func openPreview(localIdentifier: String) {
        Task {
            let image = await ThumbnailLoader.shared.fullResolution(for: localIdentifier)
            await MainActor.run { if let image { previewImage = image } }
        }
    }

    /// 抽卡候选卡全屏预览（chat.yaml §17：512px 候选缩略图落盘路径直接解码；
    /// 复用 ChatImagePreview——本入口无保存按钮，rejected 卡不可点已由卡条拒）。
    private func openGachaPreview(thumbPath: String?) {
        guard let thumbPath, let image = UIImage(contentsOfFile: thumbPath) else { return }
        previewImage = image
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        if let lastId = viewModel.messages.last?.id {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(lastId, anchor: .bottom)
            }
        }
    }

    // MARK: - Input Bar（spec §7：24dp 大圆角卡片，行1 文本 + 行2 按钮栏）

    private var inputBar: some View {
        VStack(spacing: 0) {
            // 访客注册引导 banner（chat.yaml §4.1 banner：isGuestMode && count>=20 && !dismissedThisSession）
            if viewModel.showsGuestBanner {
                guestNudgeBanner
            }
            VStack(spacing: TopBarTokens.spacing) {
                // 暂存图（选图后）：72dp 缩略图 + ✕ 移除 + 3 意图 chip（理解/找相似/编辑）
                if let staged = viewModel.stagedImage {
                    stagingRow(staged)
                }
                // 行 1：文本输入（通栏；处理中仍可编辑）
                TextField(String(localized: "Ask AI Agent..."), text: $inputText, axis: .vertical)
                    .font(.system(size: 16))
                    .lineSpacing(8)
                    .foregroundColor(Color(.label))
                    .focused($inputFocused)
                    .lineLimit(1...5)
                    // 多行输入：Return=换行（发送走发送按钮）；不配 submitLabel(.send)/onSubmit（多行下不触发，反直觉）
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("chat_input")
                    .toolbar {
                        // 键盘上方「完成」按钮收起键盘（多行 TextField 的 Return=换行，无法收起）
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button(String(localized: "Done")) { inputFocused = false }
                        }
                    }

                // 行 2：按钮栏（SpaceBetween）
                HStack(spacing: TopBarTokens.spacing) {
                    // 相册胶囊（spec gallery_capsule）：打开图片选择器（B1）
                    Button {
                        showPhotoPicker = true
                    } label: {
                        HStack(spacing: 6) {
                            MatIcon(name: "mat_photo_library", size: 16)
                            Text(String(localized: "Gallery"))
                                .font(.system(size: 12))
                        }
                        .foregroundColor(Color(.label).opacity(0.7))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color(.secondarySystemBackground).opacity(ChatBubbleTokens.capsuleInactiveAlpha))
                        .clipShape(RoundedRectangle(cornerRadius: ChatBubbleTokens.capsuleCornerRadius))
                    }
                    .disabled(viewModel.isProcessing)
                    .accessibilityIdentifier("chat_gallery_capsule")

                    // 模型胶囊已移除（2026-08-22 Android 同步决策，chat.yaml §9.1
                    // model_capsule: removed_2026_08_22）——BYOK 切模型走设置远程模型页。

                    Spacer()

                    // 2026-08-19：语音降级为默认关闭的实验能力，iOS 无语音开关，
                    // 占位语音按钮（chat_voice）按 spec settings.yaml/chat.yaml 平台差异登记移除。

                    // 发送按钮（品牌渐变实底圆钮+白 icon，对齐 Android brandGradient 形态；
                    // 仅有内容 && 非处理中时显示）
                    if canSend {
                        Button(action: send) {
                            MatIcon(name: "mat_send", size: ChatBubbleTokens.circularButtonIconSize)
                                .foregroundColor(ChatBubbleTokens.userBubbleOn)
                        }
                        .frame(width: ChatBubbleTokens.circularButtonSize, height: ChatBubbleTokens.circularButtonSize)
                        .background(
                            Circle().fill(
                                LinearGradient(
                                    colors: [ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                        )
                        .accessibilityIdentifier("chat_send")
                    }
                }
            }
            .padding(.horizontal, ChatBubbleTokens.paddingH)
            .padding(.vertical, ChatBubbleTokens.paddingV)
            .background(Color(.systemBackground))
            .clipShape(RoundedRectangle(cornerRadius: ChatBubbleTokens.inputCornerRadius))
            .shadow(color: .black.opacity(0.08), radius: ChatBubbleTokens.inputShadowElevation, y: 2)
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    // MARK: - 访客注册引导 banner（spec §4.1 banner：r16 surfaceContainerHigh 卡 + 双文本按钮 + 关闭）

    private var guestNudgeBanner: some View {
        HStack(spacing: 8) {
            Text(String(format: L("Guest trial: %lld+ rounds used"), viewModel.guestMessageCount))
                .font(.system(size: 13))
                .foregroundColor(Color(.label).opacity(0.8))
                .lineLimit(1)
            Spacer(minLength: 4)
            Button { viewModel.openRegistrationSheet() } label: {
                Text(L("Get free quota"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(ChatBubbleTokens.brandGradientStart)
            }
            .accessibilityIdentifier("chat_guest_banner_register")
            Button { showTokenConfig = true } label: {
                Text(L("My own Token"))
                    .font(.system(size: 13))
                    .foregroundColor(Color(.secondaryLabel))
            }
            .accessibilityIdentifier("chat_guest_banner_token")
            // 关闭（28dp 热区；仅本会话有效，重启复现）
            Button { viewModel.dismissGuestBanner() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13))
                    .foregroundColor(Color(.secondaryLabel))
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("chat_guest_banner_dismiss")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .accessibilityIdentifier("chat_guest_banner")
    }

    /// 对齐 Android：发送按钮仅在 text 非空 && !isProcessing 时出现；
    /// EDIT 意图 + 有暂存图时也可发（点=跳编辑器）。
    private var canSend: Bool {
        if viewModel.stagedImage != nil, viewModel.stagedIntent == .edit { return !viewModel.isProcessing }
        return !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !viewModel.isProcessing
    }

    private func send() {
        guard canSend else { return }
        let text = inputText
        inputText = ""
        viewModel.send(text)
    }

    // MARK: - 上下文附件暂存区（B1）：72dp 缩略图 + ✕ + 3 意图 chip

    private func stagingRow(_ staged: ChatViewModel.StagedImage) -> some View {
        HStack(spacing: ChatContextTokens.intentChipSpacing) {
            ZStack(alignment: .topTrailing) {
                if let img = staged.thumbnail {
                    Image(uiImage: img).resizable().scaledToFill()
                } else {
                    Color(.tertiarySystemBackground).overlay(ProgressView())
                }
                Button { viewModel.unstageImage() } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 18))
                        .foregroundColor(.white)
                        .background(Circle().fill(Color.black.opacity(0.45)))
                }
                .padding(2)
            }
            .frame(width: ChatContextTokens.thumbSize, height: ChatContextTokens.thumbSize)
            .clipShape(RoundedRectangle(cornerRadius: ChatContextTokens.thumbCornerRadius))

            HStack(spacing: 6) {
                ForEach(ChatViewModel.ImageIntent.allCases, id: \.self) { intent in
                    intentChip(intent)
                }
            }
            Spacer(minLength: 0)
        }
    }

    private func intentChip(_ intent: ChatViewModel.ImageIntent) -> some View {
        let selected = viewModel.stagedIntent == intent
        return Button { viewModel.stagedIntent = intent } label: {
            Text(intent.label)
                .font(.system(size: 12))
                .foregroundColor(selected ? .white : Color(.label).opacity(0.7))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(selected ? Color.accentColor : Color(.secondarySystemBackground).opacity(ChatBubbleTokens.capsuleInactiveAlpha))
                .clipShape(RoundedRectangle(cornerRadius: ChatBubbleTokens.capsuleCornerRadius))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("chat_intent_\(intent.rawValue)")
    }
}

// MARK: - Markdown 文本（spec §5 agent.text: markdown=true；§11 allowed: iOS AttributedString）
// 用 Apple AttributedString(markdown:) 解析 CommonMark（粗体/斜体/链接/行内代码/标题/列表/引用）。
// 代码块折叠·复制、表格渲染属 §11 允许差异，本版不做。用户气泡保持纯文本（对齐 Android）。

struct MarkdownText: View {
    let text: String

    var body: some View {
        Text(parsed)
    }

    private var parsed: AttributedString {
        // 流式期间文本可能不完整（未闭合 ** / ``），用 returnPartiallyParsedIfPossible 容错；
        // 整体解析失败回退纯文本。
        if let attr = try? AttributedString(markdown: text, options: Self.opts) {
            return attr
        }
        return AttributedString(text)
    }

    private static let opts = AttributedString.MarkdownParsingOptions(
        interpretedSyntax: .full,
        failurePolicy: .returnPartiallyParsedIfPossible
    )
}

// MARK: - Message Bubble

private struct MessageBubble: View {
    let message: ChatMessage
    var onNavigateToGallery: ((String) -> Void)? = nil
    var onImageTap: ((UIImage?) -> Void)? = nil
    var onMediaTap: ((String) -> Void)? = nil
    // AI 优化抽卡（chat.yaml §17）：卡条状态与回调（仅 optimizeCandidates 消息消费）
    var gachaInteractive: Bool = false
    var gachaSelectedIndex: Int? = nil
    var gachaRerolling: Bool = false
    var onGachaSelection: ((Int) -> Void)? = nil
    var onGachaReroll: (() -> Void)? = nil
    var onGachaConfirm: (() -> Void)? = nil
    var onGachaCardTap: ((String?) -> Void)? = nil
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                if message.role == .user { Spacer(minLength: 40) }

                VStack(alignment: .leading, spacing: 0) {
                    if message.isThinking {
                        // 思考态：3 点动画（首 token 前）
                        ThinkingIndicator()
                    } else if message.isToolCalling {
                        // 工具调用态：状态文案
                        Text(message.text)
                            .font(.system(size: ChatBubbleTokens.textSize))
                            .foregroundColor(Color(.secondaryLabel))
                    } else if !message.text.isEmpty || !message.mediaIds.isEmpty || message.imageUri != nil {
                        // USER_IMAGE_TEXT：上图下文（图在文本上方，对齐 Android）
                        if message.type == .userImageText, let uri = message.imageUri {
                            UserImageAttachment(localIdentifier: uri, onTap: { onImageTap?($0) })
                                .padding(.bottom, 6)
                        }

                        // AGENT_IMAGE：agent 单发结果图（gacha 确认/降级；FillWidth 240 完整显示，
                        // chat.yaml §5 image_content.agent_image）
                        if message.type == .agentImage, let path = message.imageUri {
                            AgentImageAttachment(imagePath: path, onTap: { onImageTap?($0) })
                                .padding(.bottom, 6)
                        }

                        // AGENT_EDIT_RESULT：编辑结果图卡（文件路径 + 失效占位），说明文字走下方文本渲染
                        if message.type == .agentEditResult, let path = message.imageUri {
                            ChatEditImageCard(imagePath: path, onTap: { onImageTap?($0) })
                                .padding(.bottom, 6)
                        }

                        // 正常文本（agent → Markdown 渲染；user → 纯文本；流式光标内联右侧，对齐 Android）
                        if !message.text.isEmpty {
                            HStack(alignment: .bottom, spacing: 2) {
                                Group {
                                    if message.role == .user {
                                        Text(message.text)
                                    } else {
                                        AgentTextView(content: message.text)
                                    }
                                }
                                .font(.system(size: ChatBubbleTokens.textSize))
                                .lineSpacing(ChatBubbleTokens.textLineHeight - ChatBubbleTokens.textSize)
                                .foregroundColor(textColor)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier(message.role == .user ? "chat_user_bubble" : "chat_ai_bubble")

                                // 流式光标（内联右侧，由节奏器 showCursor 驱动：吐字中可见 / 完成隐藏）
                                if message.showCursor {
                                    BlinkCursor()
                                        .padding(.bottom, 3)
                                }
                            }
                        }

                        // 媒体卡片（独立消息项）
                        if !message.mediaIds.isEmpty {
                            MediaCardRow(
                                mediaIds: message.mediaIds,
                                totalCount: message.mediaTotalCount ?? message.mediaIds.count,
                                onViewAll: { onNavigateToGallery?(message.mediaQuery ?? "") },
                                onMediaTap: onMediaTap
                            )
                            .padding(.top, 6)
                        }
                    }
                }
                // 宽度上限（对齐 Android Column.widthIn）：内容收缩包裹，超 cap 才换行。
                // 图+文气泡 240，其余 360（对齐 Android isImage/isImageText 分支）。
                // 图类气泡（用户图+文 / agent 单发图）：240 上限 + padding 6/6
                // （Android isImage||isImageText 分支）；文本/编辑结果 360 上限 + 16/12。
                // 常量未入 DesignTokens（生成物，门禁修复前禁手改——技术债：待 tokens JSON 统一）
                .widthCap((message.type == .userImageText || message.type == .agentImage)
                    ? ChatBubbleTokens.imageMaxWidth
                    : ChatBubbleTokens.bubbleMaxWidth)
                .padding(.horizontal,
                         (message.type == .userImageText || message.type == .agentImage) ? 6 : ChatBubbleTokens.paddingH)
                .padding(.vertical,
                         (message.type == .userImageText || message.type == .agentImage) ? 6 : ChatBubbleTokens.paddingV)
                .background(bubbleBackground)
                .clipShape(bubbleShape)
                .contentShape(Rectangle())
                .onLongPressGesture {
                    // 长按复制（对齐 Android）
                    UIPasteboard.general.string = message.text
                }

                // CHART 图卡（draw_chart 端侧 JS 生成的 SVG，ChartJsEngine 渲染）
                if let svg = message.chartSvg {
                    ChartSvgCard(svg: svg)
                        .padding(.top, 6)
                        .frame(maxWidth: ChatBubbleTokens.bubbleMaxWidth)
                }

                if message.role == .assistant { Spacer(minLength: 40) }
            }

            // OPTIMIZE_CANDIDATES 候选卡条（chat.yaml §17 strip_ui）：独立全宽块，
            // 伴随上方 agent 文本气泡（场景解释句）；pending 过期 → interactive=false
            // 只读（expired 文案、无按钮行、无选中态）
            if message.type == .optimizeCandidates, let payload = message.gacha {
                GachaCandidateStrip(
                    payload: payload,
                    selectedIndex: gachaSelectedIndex,
                    interactive: gachaInteractive,
                    rerolling: gachaRerolling,
                    onSelection: { index in onGachaSelection?(index) },
                    onReroll: { onGachaReroll?() },
                    onConfirm: { onGachaConfirm?() },
                    onCardTap: { thumbPath in onGachaCardTap?(thumbPath) })
            }
        }
    }

    // §5 text：用户正文 userBubbleOn（v2.2.3 对比度契约 946a36db4：亮绿底白字 ~1.9:1 → 深字 ~8:1）；
    // 图文说明（USER_IMAGE_TEXT text_below）走 onSurface（图类气泡底是 surfaceVariant 0.4，非能量绿）
    private var textColor: Color {
        if message.role == .user {
            return message.type == .userImageText ? s.onSurface : ChatBubbleTokens.userBubbleOn
        }
        return Color(.label)
    }

    private var bubbleShape: UnevenRoundedRectangle {
        let r = ChatBubbleTokens.cornerRadius, sharp = ChatBubbleTokens.tailCornerRadius
        if message.role == .user {
            return UnevenRoundedRectangle(
                topLeadingRadius: r, bottomLeadingRadius: r,
                bottomTrailingRadius: sharp, topTrailingRadius: r
            )
        } else {
            return UnevenRoundedRectangle(
                topLeadingRadius: r, bottomLeadingRadius: sharp,
                bottomTrailingRadius: r, topTrailingRadius: r
            )
        }
    }

    private var bubbleBackground: Color {
        // 图类气泡（用户图+文 / agent 单发图）→ surfaceVariant 0.4（chat.yaml §5 background.image，
        // 用户图+文配 onSurface 图文说明）；用户文本 → userBubbleBg 实色（§5 background.user_text，
        // v2.2.3：去 0.9 alpha，双模式一致）
        if message.type == .agentImage || message.type == .userImageText {
            return s.surfaceVariant.opacity(0.4)
        }
        if message.role == .user {
            return ChatBubbleTokens.userBubbleBg
        } else {
            return Color(.secondarySystemBackground).opacity(0.85)
        }
    }
}

// MARK: - WidthCap Layout（对齐 Android Modifier.widthIn(max=)）

/// `widthIn(max:)` 等价布局：内容宽度自适应（短文本收缩包裹），超过 maxWidth 才收缩换行。
/// 不能用 `.frame(maxWidth:)` 替代——flexible frame 的尺寸是「父提案 clamp 到 max」，
/// 与内容无关，短文本也会撑满 max（即气泡恒定 360 固定宽的根因）。
private struct WidthCapLayout: Layout {
    var maxWidth: CGFloat

    private func capped(_ proposal: ProposedViewSize) -> ProposedViewSize {
        let cap = min(proposal.width ?? maxWidth, maxWidth)
        return ProposedViewSize(width: cap, height: proposal.height)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let subview = subviews.first else { return .zero }
        return subview.sizeThatFits(capped(proposal))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let subview = subviews.first else { return }
        subview.place(at: bounds.origin, proposal: capped(proposal))
    }
}

private extension View {
    /// `WidthCapLayout` 的 modifier 形式（Layout 协议只有 callAsFunction 用法，无 .layout()）。
    func widthCap(_ maxWidth: CGFloat) -> some View {
        WidthCapLayout(maxWidth: maxWidth) { self }
    }
}

// MARK: - Thinking Indicator (6dp dots, 5dp spacing, 160ms stagger)

private struct ThinkingIndicator: View {
    @State private var animate = false

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3) { i in
                Circle()
                    .fill(Color(.label).opacity(animate ? 1.0 : 0.3))
                    .frame(width: 6, height: 6)
                    .animation(
                        .easeInOut(duration: 0.4)
                        .repeatForever(autoreverses: true)
                        .delay(Double(i) * 0.16),
                        value: animate
                    )
            }
        }
        .padding(.vertical, 4)
        .onAppear { animate = true }
    }
}

// MARK: - Blink Cursor (> 字符闪烁)

private struct BlinkCursor: View {
    @State private var visible = true

    var body: some View {
        Text(">")
            .font(.system(size: 14, weight: .bold))
            .foregroundColor(Color(.label))
            .opacity(visible ? 1.0 : 0.3)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.5).repeatForever(autoreverses: true)) {
                    visible = false
                }
            }
    }
}

// MARK: - Media Card Row (spec §9：120×150 卡片 + 日期标签)

private struct MediaCardRow: View {
    let mediaIds: [Int64]
    var totalCount: Int = 0
    var onViewAll: () -> Void = {}
    var onMediaTap: ((String) -> Void)? = nil
    @State private var idToIdentifier: [Int64: String] = [:]
    @State private var idToDate: [Int64: Date] = [:]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: ChatCarouselTokens.cardSpacing) {
                ForEach(mediaIds, id: \.self) { id in
                    MediaThumbnail(
                        localIdentifier: idToIdentifier[id],
                        date: idToDate[id],
                        onTap: { if let lid = idToIdentifier[id] { onMediaTap?(lid) } }
                    )
                    .frame(width: ChatCarouselTokens.cardWidth, height: ChatCarouselTokens.cardHeight)
                    .clipShape(RoundedRectangle(cornerRadius: ChatCarouselTokens.cardCornerRadius))
                }
                // 「查看全部」尾卡：全量命中数 > 显示数时附在末尾（对齐 Android ViewAllCard）
                if totalCount > mediaIds.count {
                    ViewAllCard(onTap: onViewAll)
                        .frame(width: ChatCarouselTokens.cardWidth, height: ChatCarouselTokens.cardHeight)
                        .clipShape(RoundedRectangle(cornerRadius: ChatCarouselTokens.viewAllCornerRadius))
                }
            }
            .padding(.vertical, 4)
        }
        .accessibilityIdentifier("chat_media_card")
        .task(id: mediaIds) {
            // mediaIds = localIdentifier.hashCode().toLong()（Kotlin String.hashCode，见
            // IosChatGalleryCapability.toDomain id 口径）。反查须用同一 32-bit Java hash。
            guard !mediaIds.isEmpty else { return }
            var idMap: [Int64: String] = [:]
            var dateMap: [Int64: Date] = [:]
            PHAsset.fetchAssets(with: nil).enumerateObjects { asset, _, _ in
                let key = Self.javaHashCode(asset.localIdentifier)
                idMap[key] = asset.localIdentifier
                dateMap[key] = asset.creationDate
            }
            let resolved = mediaIds.filter { idMap[$0] != nil }.count
            #if DEBUG
            DebugBypass.log("Chat", "carousel mediaIds=[\(mediaIds.map(String.init).joined(separator: ","))] resolved=\(resolved)/\(mediaIds.count)")
            #endif
            idToIdentifier = idMap
            idToDate = dateMap
        }
    }

    /// Kotlin `String.hashCode().toLong()` 等价：**Int32** 溢出运算（31 &* / &+ 在 Int32 上 wrap，
    /// 对齐 Kotlin Int 的 32-bit 回绕），再符号扩展到 Int64。早期用 Int64 累加 → wrap 模 2^63 ≠
    /// Kotlin 的模 2^32 → 永远匹配不上 → 卡片空白。
    static func javaHashCode(_ s: String) -> Int64 {
        var h: Int32 = 0
        for u in s.utf16 {
            h = 31 &* h &+ Int32(u)
        }
        return Int64(h)
    }
}

/// 「查看全部」尾卡（对齐 Android ViewAllCard）：与媒体卡同尺寸，tap → onViewAll（跳相册带搜索词）。
private struct ViewAllCard: View {
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 6) {
                MatIcon(name: "mat_photo_library", size: 22)
                    .foregroundColor(.accentColor)
                Text(String(localized: "View All"))
                    .font(.system(size: 12))
                    .foregroundColor(Color(.label).opacity(0.6))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.secondarySystemBackground))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("chat_media_view_all")
    }
}

private struct MediaThumbnail: View {
    let localIdentifier: String?
    let date: Date?
    var onTap: () -> Void = {}
    @State private var image: UIImage?
    /// 媒体反馈（对齐 Android FeedbackIconButton 👍👎🔄；本批本地态选中，上报/持久留后续）
    enum FeedbackType: Hashable { case thumbUp, thumbDown, refresh }
    @State private var feedback: FeedbackType?

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color(.tertiarySystemBackground)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                Image(matIcon: "photo")
                    .font(.system(size: 20))
                    .foregroundColor(.secondary.opacity(0.3))
            }
            // 日期标签（对齐 spec §9 card_date_label：yyyy-MM-dd，底部渐变 scrim 上白字）
            if let date, let label = Self.dateFormatter.string(for: date) {
                Text(label)
                    .font(.system(size: 11))
                    .foregroundColor(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        LinearGradient(
                            colors: [.clear, .black.opacity(0.5)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )
            }
        }
        .overlay(alignment: .topTrailing) { mediaFeedbackButtons }
        .contentShape(Rectangle())
        .onTapGesture { onTap() }
        .task(id: localIdentifier) {
            guard let localIdentifier, image == nil else { return }
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier, size: CGSize(width: 360, height: 450))
        }
    }

    private var mediaFeedbackButtons: some View {
        HStack(spacing: 6) {
            ForEach([FeedbackType.thumbUp, .thumbDown, .refresh], id: \.self) { t in
                Button { feedback = (feedback == t ? nil : t) } label: {
                    Image(systemName: Self.iconName(t))
                        .font(.system(size: 11))
                        .foregroundColor(feedback == t ? .accentColor : .white)
                }
                .accessibilityIdentifier("chat_media_feedback_\(t == .thumbUp ? "up" : t == .thumbDown ? "down" : "refresh")")
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .background(Capsule().fill(Color.black.opacity(0.45)))
        .padding(4)
    }

    private static func iconName(_ t: FeedbackType) -> String {
        switch t {
        case .thumbUp: return "hand.thumbsup.fill"
        case .thumbDown: return "hand.thumbsdown.fill"
        case .refresh: return "arrow.clockwise"
        }
    }
}

// MARK: - Empty State v3（chat.yaml §4，2026-08-22 定稿；设计稿 refs/ardot/chat-empty-v2-guest）

/// 空状态 v3：48pt 品牌渐变 Logo 块 + 渐变标题 + 访客小链接 + 两组示例 chips（各 3 条，带彩色图标）。
/// EN 示例词为 key（L() 查表），显示与点击发送均用本地化串（对齐 Android stringArray 行为）。
struct ChatEmptyState: View {
    /// 访客模式（无 server token）才显示注册小链接（show_when isGuestMode）
    let isGuestMode: Bool
    let onExampleTap: (String) -> Void
    var onGuestLinkTap: (() -> Void)? = nil

    /// 示例 chip：EN 取词 key + 彩色图标（图标色为设计稿固定色，非主题色）
    private struct ExampleChip {
        let prompt: String
        let iconSystemName: String
        let iconColor: Color
    }
    private struct ExampleGroup {
        let label: String
        let chips: [ExampleChip]
    }

    /// 两组示例词（spec §4 example_groups；文案=chat_example_prompts_search/ask 三语数组，
    /// 与 Android strings.xml 逐字一致；chips 图标按组内语义固定）
    private let groups: [ExampleGroup] = [
        ExampleGroup(label: "Find photos", chips: [
            ExampleChip(prompt: "Find photos from last summer",
                        iconSystemName: "calendar", iconColor: Color(red: 0x6B / 255.0, green: 0xA6 / 255.0, blue: 0xFF / 255.0)),
            ExampleChip(prompt: "Find photos with people",
                        iconSystemName: "person.fill", iconColor: Color(red: 0xFF / 255.0, green: 0x7E / 255.0, blue: 0xB0 / 255.0)),
            ExampleChip(prompt: "Search for night scene photos",
                        iconSystemName: "moon.stars.fill", iconColor: Color(red: 0x9B / 255.0, green: 0x8C / 255.0, blue: 0xFF / 255.0)),
        ]),
        ExampleGroup(label: "Ask about your gallery", chips: [
            ExampleChip(prompt: "Give me a gallery health report",
                        iconSystemName: "chart.bar.fill", iconColor: Color(red: 0x4A / 255.0, green: 0xDE / 255.0, blue: 0x80 / 255.0)),
            ExampleChip(prompt: "Find duplicate photos",
                        iconSystemName: "photo.on.rectangle.angled", iconColor: Color(red: 0x22 / 255.0, green: 0xD3 / 255.0, blue: 0xEE / 255.0)),
            ExampleChip(prompt: "How many selfies did I take?",
                        iconSystemName: "person.2.fill", iconColor: Color(red: 0xFF / 255.0, green: 0x7E / 255.0, blue: 0xB0 / 255.0)),
        ]),
    ]

    /// 品牌渐变（chat.yaml §10：#0F766E→#5EA88F 135°；token 见 ChatBubbleTokens.brandGradient*）
    private var brandGradient: LinearGradient {
        LinearGradient(
            colors: [ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd],
            startPoint: .topLeading, endPoint: .bottomTrailing
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            Spacer().frame(height: 28)  // top_spacer（v3）

            // Logo：48pt 品牌渐变底块 r16；内嵌图 1.75 倍（84pt）放大居中裁切——
            // 透明边裁出框外、插画填满 LogoBox（spec inner_image_zoom；iOS 无
            // app_launcher_foreground 资源，暂以 chat_logo 位图等比代用，见交付报告）
            ZStack {
                RoundedRectangle(cornerRadius: 16)
                    .fill(brandGradient)
                Image("chat_logo")
                    .resizable()
                    .scaledToFill()
                    .frame(width: 48 * 1.75, height: 48 * 1.75)
            }
            .frame(width: 48, height: 48)
            .clipShape(RoundedRectangle(cornerRadius: 16))

            Spacer().frame(height: 12)  // title_spacer

            Text(L("Hi, I'm Xiaolang"))
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(brandGradient)  // 渐变着色文字

            Spacer().frame(height: 6)  // subtitle_spacer

            Text(L("Search, edit, beautify — ask me anything!"))
                .font(.system(size: 14))
                .foregroundColor(Color(.secondaryLabel))

            // 访客轻量入口（show_when isGuestMode；纯文本点击态，padding 12/6）
            if isGuestMode {
                Button { onGuestLinkTap?() } label: {
                    Text(L("Not registered? Sign up or use your Token →"))
                        .font(.system(size: 13))
                        .foregroundColor(ChatBubbleTokens.brandGradientStart)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                }
                .accessibilityIdentifier("chat_guest_link")
            }

            Spacer().frame(height: 16)  // groups_spacer

            // 两组示例：居中小标题 13 onSurfaceVariant + flow chips（组间 12，组内标题→chips 8）
            ForEach(Array(groups.enumerated()), id: \.offset) { index, group in
                if index > 0 { Spacer().frame(height: 12) }  // group_gap
                groupView(group)
            }

            Spacer().frame(height: 10)  // 末组距输入栏
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityIdentifier("chat_empty_state")
    }

    private func groupView(_ group: ExampleGroup) -> some View {
        VStack(spacing: 8) {
            Text(L(group.label))
                .font(.system(size: 13))
                .foregroundColor(Color(.secondaryLabel))
            // chips flow（行距 8 / 列距 14，整体居中）
            FlowLayout(horizontal: 14, vertical: 8, centered: true) {
                ForEach(group.chips, id: \.prompt) { chip in
                    exampleChip(chip)
                }
            }
        }
    }

    /// 单条示例 chip：r22 surfaceContainerHigh 底 + 16pt 彩色图标 + 14sp 单行文本
    /// （padding start 12 / end 14 / vertical 8；文本强制单行溢出省略）
    private func exampleChip(_ chip: ExampleChip) -> some View {
        Button {
            onExampleTap(L(chip.prompt))
        } label: {
            HStack(spacing: 8) {
                Image(systemName: chip.iconSystemName)
                    .font(.system(size: 16))
                    .foregroundColor(chip.iconColor)
                Text(L(chip.prompt))
                    .font(.system(size: 14))
                    .foregroundColor(Color(.label))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(.leading, 12)
            .padding(.trailing, 14)
            .padding(.vertical, 8)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 22))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("chat_example_chip")
    }
}

// MARK: - FlowLayout

struct FlowLayout: Layout {
    let hSpacing: CGFloat
    let vSpacing: CGFloat
    /// 每行内容整体居中（chat 空状态 v3 two_groups_flow_wrap_centered；默认 false=左对齐，
    /// Settings/TagScan 既有调用不受影响）
    var centered: Bool = false
    /// 等距便捷初始化（旧行为兼容：Settings/TagScan 既有调用）
    init(spacing: CGFloat = 8) {
        self.hSpacing = spacing
        self.vSpacing = spacing
    }
    /// 横纵独立间距（chat 空状态 v3：列距 14 / 行距 8；默认左对齐）
    init(horizontal: CGFloat, vertical: CGFloat) {
        self.hSpacing = horizontal
        self.vSpacing = vertical
    }
    /// 横纵独立间距 + 行居中
    init(horizontal: CGFloat, vertical: CGFloat, centered: Bool) {
        self.hSpacing = horizontal
        self.vSpacing = vertical
        self.centered = centered
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var totalHeight: CGFloat = 0, x: CGFloat = 0, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth && x > 0 {
                totalHeight += rowHeight + vSpacing; x = 0; rowHeight = 0
            }
            x += size.width + hSpacing; rowHeight = max(rowHeight, size.height)
        }
        totalHeight += rowHeight
        return CGSize(width: maxWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        // 先按宽度分行，再逐行摆放（centered=true 时行内容整体居中）
        var rows: [[(size: CGSize, subview: LayoutSubview)]] = []
        var row: [(size: CGSize, subview: LayoutSubview)] = []
        var x: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.width && !row.isEmpty {
                rows.append(row); row = []; x = 0
            }
            row.append((size, subview))
            x += size.width + hSpacing
        }
        if !row.isEmpty { rows.append(row) }

        var y = bounds.minY
        for row in rows {
            let rowHeight = row.map(\.size.height).max() ?? 0
            let rowWidth = row.map(\.size.width).reduce(0, +) + hSpacing * CGFloat(row.count - 1)
            var px = centered
                ? bounds.minX + max(0, (bounds.width - rowWidth) / 2)
                : bounds.minX
            for item in row {
                item.subview.place(at: CGPoint(x: px, y: y), proposal: .init(item.size))
                px += item.size.width + hSpacing
            }
            y += rowHeight + vSpacing
        }
    }
}

// MARK: - Photo Picker（PHPicker 单选；config 带 photoLibrary → assetIdentifier = PHAsset.localIdentifier）

struct ChatPhotoPicker: UIViewControllerRepresentable {
    let onPicked: (String) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(photoLibrary: PHPhotoLibrary.shared())
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ vc: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPicked: (String) -> Void
        init(onPicked: @escaping (String) -> Void) { self.onPicked = onPicked }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            if let lid = results.first?.assetIdentifier { onPicked(lid) }
        }
    }
}

#Preview {
    ChatView()
        .environmentObject(AppContainer.shared)
}
