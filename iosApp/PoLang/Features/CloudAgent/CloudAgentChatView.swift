import SwiftUI

// MARK: - Cloud Agent Chat View（ADR-018 M2，spec cloud-agent.yaml §2/§3）
//
// 开发者选项 DEBUG 构建入口的实验页（豁免 PARITY 零容差比对，气泡配色/圆角引用语义色）。
// 四态闭集渲染：preparing（居中 ProgressView）/ needByokConsent（alert 二选一，
// 不静默回退）/ failed（居中错误文本）/ chat（消息列表 + 输入栏 + 通道胶囊）。

struct CloudAgentChatView: View {
    @StateObject private var store = CloudAgentChatStore()
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    @State private var input: String = ""
    @State private var showRemoteModelSettings = false
    @State private var showConsentAlert = false

    var body: some View {
        content
            .background(Color(.systemBackground).ignoresSafeArea())
            .navigationTitle(L("Cloud Agent"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarCapsule }
            // need_byok_consent 弹窗（spec §2：标题/正文/二选一动作，🔴 不静默回退）
            .alert(L("Authorization required"), isPresented: $showConsentAlert) {
                Button(L("Go to settings")) { showRemoteModelSettings = true }
                Button(L("Use gateway")) { store.useGatewayInstead() }
            } message: {
                Text(L("Cloud agent needs to pass your API key through the server (never stored), or use the gateway channel instead."))
            }
            // 「去设置」→ 远程模型页（授权开关所在页）；返回时若仍待授权则重跑通道解析
            .navigationDestination(isPresented: $showRemoteModelSettings) {
                ModelCenterView()
                    .environmentObject(ModelConfigStore.shared)
            }
            .onAppear { store.prepare() }
            .onChange(of: store.state) { newState in
                if newState == .needByokConsent { showConsentAlert = true }
            }
            .onChange(of: showRemoteModelSettings) { presented in
                if !presented && store.state == .needByokConsent { store.retryPrepare() }
            }
    }

    // MARK: - 四态渲染（穷举）

    @ViewBuilder
    private var content: some View {
        switch store.state {
        case .preparing:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .needByokConsent:
            // alert 承载交互；底层保持空页
            Color.clear
        case .failed(let reason):
            // reason==not_logged_in → 文案键；否则原文展示（spec §2 mapping）
            Text(reason == "not_logged_in" ? L("Sign in first to use cloud agent") : reason)
                .font(AppTypography.bodyMedium.font)
                .foregroundColor(s.error)
                .multilineTextAlignment(.center)
                .padding(Spacing.lg)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .chat:
            chatContent
        }
    }

    // MARK: - Chat 态（§3：消息列表 + 输入栏）

    private var chatContent: some View {
        VStack(spacing: 0) {
            messageList
            inputBar
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(store.messages) { message in
                        bubble(for: message)
                            .id(message.id)
                    }
                    if store.inFlight {
                        Text(L("Thinking…"))
                            .font(AppTypography.bodySmall.font)
                            .foregroundColor(s.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id("thinking")
                    }
                }
                .padding(12)
            }
            .onChange(of: store.messages.count) { _ in scrollToBottom(proxy) }
            .onChange(of: store.inFlight) { _ in scrollToBottom(proxy) }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        if store.inFlight {
            withAnimation { proxy.scrollTo("thinking", anchor: .bottom) }
        } else if let last = store.messages.last {
            withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
        }
    }

    // MARK: 气泡四角色（§3 bubbles）

    @ViewBuilder
    private func bubble(for message: CloudAgentChatStore.Message) -> some View {
        switch message.role {
        case .user:
            HStack {
                Spacer(minLength: 0)
                Text(message.text)
                    .font(AppTypography.bodyMedium.font) // 14pt
                    .foregroundColor(s.onPrimaryContainer)
                    .padding(.horizontal, Spacing.md)
                    .padding(.vertical, Spacing.sm)
                    .background(s.primaryContainer)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .frame(maxWidth: 300, alignment: .trailing)
            }
        case .assistant:
            HStack {
                AgentTextView(content: message.text) // markdown AST 渲染，与 chat 正文同源
                    .padding(.horizontal, Spacing.md)
                    .padding(.vertical, Spacing.sm)
                    .background(s.surfaceVariant)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .frame(maxWidth: 300, alignment: .leading)
                Spacer(minLength: 0)
            }
        case .tool:
            Text(String(format: L("Calling %@…"), message.text))
                .font(AppTypography.bodySmall.font) // 12pt
                .foregroundColor(s.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .error:
            Text(message.text)
                .font(.system(size: 13))
                .foregroundColor(s.error)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: 输入栏（§3 input_bar）

    private var inputBar: some View {
        HStack(spacing: Spacing.sm) {
            TextField(L("Message…"), text: $input, axis: .vertical)
                .lineLimit(1...3)
                .font(AppTypography.bodyMedium.font)
                .padding(.horizontal, Spacing.md)
                .padding(.vertical, Spacing.sm)
                .background(s.surfaceContainerHigh)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            if store.inFlight {
                Button(L("Stop")) { store.stop() }
                    .font(AppTypography.bodyMedium.font)
                    .foregroundColor(s.error)
            } else if !input.isEmpty {
                Button {
                    let text = input
                    input = ""
                    store.send(text)
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(s.onPrimary)
                        .frame(width: 32, height: 32)
                        .background(Circle().fill(s.primary))
                }
                .accessibilityLabel(L("Send"))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, Spacing.sm)
    }

    // MARK: 通道胶囊（§2 chat.channel_capsule：仅 chat 态；圆角 10、secondaryContainer 底、11pt）
    // principal 会替换默认标题视图，故标题+胶囊同组 HStack——spec 明示「顶栏标题旁」。

    @ToolbarContentBuilder
    private var toolbarCapsule: some ToolbarContent {
        if store.state == .chat {
            ToolbarItem(placement: .principal) {
                HStack(spacing: Spacing.sm) {
                    Text(L("Cloud Agent")).font(.headline)
                    Text(store.channel == "byok" ? L("BYOK") : L("Gateway"))
                        .font(.system(size: 11))
                        .foregroundColor(s.onSecondaryContainer)
                        .padding(.horizontal, Spacing.sm)
                        .padding(.vertical, 2)
                        .background(s.secondaryContainer)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        CloudAgentChatView()
    }
}
