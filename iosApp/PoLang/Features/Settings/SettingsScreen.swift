import SwiftUI
import AVFoundation
import Photos

/// 设置主屏（1:1 对标 Android SettingsScreen.kt）。
/// spec: specs/screens/settings.yaml
/// 截图参考: /tmp/android-ui-reference/02-settings-main.png
///
/// 结构：账号英雄卡片 → 主题模式卡片 → 语言卡片 → 分类网格（2 列×5 行 = 10 卡片）
struct SettingsScreen: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.colorScheme) private var cs
    /// M3 语义色（对标 Android MaterialTheme.colorScheme）
    private var s: SchemeColors { appScheme(cs) }

    // 2026-08-15 用户定：iOS 开发者选项卡片直接显示，无 7 连点解锁门控（与 Android 差异已登记 settings.yaml 台账）
    /// 相册设置（扫描控制台）以 fullScreenCover 呈现（TagScanScreen 既有模式）
    @State private var showGalleryConsole = false
    /// 账号登录态 + hero 额度（对齐 Android AccountHeroCard 外显登录态）
    @AppStorage("server_auth_token") private var authToken = ""
    @AppStorage("server_auth_email") private var authEmail = ""
    @State private var heroQuotaUsed = 0
    @State private var heroQuotaLimit = 0
    @State private var heroQuotaLoaded = false
    /// 「我」的人物封面（hero 头像；main-nav.yaml §3 entries_ui.settings_hero，对齐 Android AccountHeroCard）
    @State private var selfAvatarLid: String?
    @State private var selfAvatarFocusY: Float?
    private var loggedIn: Bool { !authToken.isEmpty }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                accountHeroCard
                themeCard
                languageCard
                categoryGrid
                versionFooter
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Settings"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: {
                    MatIcon(name: "mat_o_arrow_back", size: 20)
                }
            }
        }
        .fullScreenCover(isPresented: $showGalleryConsole) {
            TagScanScreen(onDismiss: { showGalleryConsole = false })
        }
        .task {
            loadSelfAvatar()
            guard loggedIn else { return }
            // hero 额度查询失败静默（详情页会重试）
            if let q = try? await PoLangAuthClient.shared.getQuota(token: authToken) {
                heroQuotaUsed = q.llmCallsUsed; heroQuotaLimit = q.llmCallsLimit; heroQuotaLoaded = true
            }
        }
        // 头像拍摄完成（相机 cover 落回本页）→ 重查「我」的封面刷新头像
        .onReceive(NotificationCenter.default.publisher(for: .avatarCoverUpdated)) { _ in
            loadSelfAvatar()
        }
    }

    // MARK: - Version footer + 开发者选项连点解锁

    private var appVersionText: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        return "PoLang v\(v)"
    }

    private var versionFooter: some View {
        Text(appVersionText)
            .font(.system(size: 12))
            .foregroundColor(.secondary.opacity(0.6))
            .padding(.top, 16)
            .padding(.bottom, 8)
    }

    // MARK: - ① Account Hero Card

    /// 「我」封面解析：selfPersonId → person.coverMediaId → coverInfo。
    /// Task.detached 查库（TagDatabase/PersonRepository 走内部串行队列，任意线程可调）、主线程回填；
    /// 无「我」标记人物或无封面时静默保持静态图标兜底。
    private func loadSelfAvatar() {
        Task.detached(priority: .userInitiated) {
            let cover = TagDatabase.shared.selfPersonId()
                .flatMap { PersonRepository.shared.person($0)?.coverMediaId }
                .flatMap { TagDatabase.shared.coverInfo(mediaId: $0) }
            await MainActor.run {
                selfAvatarLid = cover?.localIdentifier
                selfAvatarFocusY = cover?.faceFocusY
            }
        }
    }

    private var accountHeroCard: some View {
        NavigationLink {
            AccountSettingsView()
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    if let lid = selfAvatarLid {
                        ThumbnailView(localIdentifier: lid, faceFocusY: selfAvatarFocusY, cornerRadius: 0)
                            .frame(width: 48, height: 48)
                            .clipShape(Circle())
                    } else {
                        // 无「我」封面兜底：静态人像图标
                        Circle()
                            .fill(Color.accentColor.opacity(0.15))
                            .frame(width: 48, height: 48)
                        Image(matIcon: "person")
                            .font(.system(size: 24))
                            .foregroundColor(.accentColor)
                    }
                }
                // 相机角标 = 「拍摄头像」入口（PersonInfoView cameraBadge 等比缩小）；
                // 内层 Button 优先命中，不触发整卡 NavigationLink 跳转账号页
                .overlay(alignment: .bottomTrailing) {
                    Button {
                        AvatarCaptureController.shared.begin(target: .selfTarget, origin: .settingsPage)
                    } label: {
                        MatIcon(name: "mat_o_photo_camera", size: 10)
                            .foregroundColor(s.onPrimary)
                            .frame(width: 20, height: 20)
                            .background(Circle().fill(s.primary))
                            .overlay(Circle().stroke(s.background, lineWidth: 1.5))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("settings_avatar_capture")
                    .accessibilityLabel(Text(L("avatar_capture_hint")))
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(loggedIn ? authEmail : L("Account"))
                        .font(.system(size: 16, weight: .medium))
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Text(loggedIn
                         ? (heroQuotaLoaded ? "\(heroQuotaUsed) / \(heroQuotaLimit)" : L("Account"))
                         : L("Sign in for more quota and features"))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                Spacer()
                Image(matIcon: "arrow_forward")
                    .font(.system(size: 16))
                    .foregroundColor(.secondary)
            }
            .padding(16)
            .background(s.surfaceContainerHighest)
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    // MARK: - ② Theme Mode Card

    private var themeCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("Theme"))
                .font(.system(size: 14, weight: .medium))

            HStack(spacing: 8) {
                filterChip("system", label: L("System Default"))
                filterChip("light", label: L("Light"))
                filterChip("dark", label: L("Dark"))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - ③ Language Card

    private var languageCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("Language"))
                .font(.system(size: 14, weight: .medium))

            FlowLayout(spacing: 8) {
                filterChip("english", label: "English", isSelected: settings.appLanguage == "english")
                filterChip("chinese_simplified", label: "中文", isSelected: settings.appLanguage == "chinese_simplified")
                filterChip("chinese_traditional", label: "繁體中文", isSelected: settings.appLanguage == "chinese_traditional")
                filterChip("spanish", label: "Español", isSelected: settings.appLanguage == "spanish")
                filterChip("french", label: "Français", isSelected: settings.appLanguage == "french")
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // 通用 FilterChip（主题用）
    private func filterChip(_ value: String, label: String) -> some View {
        filterChip(value, label: label, isSelected: settings.themeMode == value)
    }

    private func filterChip(_ value: String, label: String, isSelected: Bool) -> some View {
        Button {
            if ["system", "light", "dark"].contains(value) {
                settings.themeMode = value
            } else {
                settings.appLanguage = value
            }
        } label: {
            Text(label)
                .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? .white : .primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(isSelected ? Color.accentColor : s.surfaceContainerHigh)
                .clipShape(Capsule())
        }
    }

    // MARK: - ④ Category Grid (2 columns × 5 rows = 10 cards)

    private var categoryGrid: some View {
        let columns = [
            GridItem(.flexible(), spacing: 10),
            GridItem(.flexible(), spacing: 10),
        ]
        return LazyVGrid(columns: columns, spacing: 10) {
            ForEach(categories, id: \.title) { cat in
                categoryCard(cat)
            }
        }
    }

    @ViewBuilder
    private func categoryCard(_ cat: SettingsCategoryItem) -> some View {
        let cardContent = VStack(alignment: .leading, spacing: 8) {
            Image(matIcon: cat.icon)
                .font(.system(size: 28))
                .foregroundColor(.accentColor)
            Text(cat.title)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.primary)
                .lineLimit(1)
            Text(cat.isPlaceholder ? L("Coming Soon") : cat.desc)
                .font(.system(size: 12))
                .foregroundColor(cat.isPlaceholder ? .secondary.opacity(0.5) : .secondary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
                // 描述恒占 2 行高，保证所有卡片高度一致（短文案卡片不再偏矮）
                .frame(minHeight: 30, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(16)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))

        Group {
            switch cat.target {
            case .modelCenter:
                NavigationLink { ModelDownloadCenterView() } label: { cardContent }
            case .remoteModels:
                NavigationLink { ModelCenterView().environmentObject(ModelConfigStore.shared) } label: { cardContent }
            case .localModels:
                NavigationLink { LocalModelsSettingsView() } label: { cardContent }
            case .sandbox:
                NavigationLink { SandboxSettingsView() } label: { cardContent }
            case .dataPrivacy:
                NavigationLink { DataPrivacyView() } label: { cardContent }
            case .memoryFacts:
                NavigationLink { MemoryFactsView() } label: { cardContent }
            case .channels:
                NavigationLink { CommunicationChannelView() } label: { cardContent }
            case .people:
                NavigationLink {
                    PersonView().environmentObject(AppContainer.shared)
                } label: { cardContent }
            case .developer:
                NavigationLink { DeveloperSettingsView() } label: { cardContent }
            case .gallery:
                Button { showGalleryConsole = true } label: { cardContent }
            }
        }
        .buttonStyle(.plain)
    }

    private var categories: [SettingsCategoryItem] {
        var items: [SettingsCategoryItem] = [
            // Row 1
            .init(icon: "psychology", title: L("AI Memory"), desc: L("View, edit, delete AI remembered facts"), target: .memoryFacts, isPlaceholder: false),
            .init(icon: "account_circle", title: L("People"), desc: L("Manage people and relationships"), target: .people, isPlaceholder: false),
            // Row 2
            .init(icon: "forum", title: L("Channels"), desc: L("Configure Feishu / Telegram remote control"), target: .channels, isPlaceholder: false),
            .init(icon: "photo_library", title: L("Gallery Settings"), desc: L("Tag scanning, people clustering, tags & duplicates"), target: .gallery, isPlaceholder: false),
            // Row 3
            .init(icon: "smart_toy", title: L("Remote Models"), desc: L("Remote model providers, API keys, and the active model."), target: .remoteModels, isPlaceholder: false),
            .init(icon: "storage", title: L("Local Models"), desc: L("Pick on-device face-detection, tagging and voice models."), target: .localModels, isPlaceholder: false),
            // Row 4
            .init(icon: "cloud_download", title: L("Model Center"), desc: L("Download and manage all local models"), target: .modelCenter, isPlaceholder: false),
            .init(icon: "lock", title: L("Sandbox & Permissions"), desc: L("Auto-execute, JS engine, device access."), target: .sandbox, isPlaceholder: false),
            // Row 5
            .init(icon: "privacy_tip", title: L("Data & Privacy"), desc: L("Privacy policy, data retention, deletion"), target: .dataPrivacy, isPlaceholder: false),
        ]
        // 开发者选项直接显示（2026-08-15 用户定：iOS 不做 7 连点门控，与 Android 差异登记台账）
        items.append(.init(icon: "terminal", title: L("Developer Options"), desc: L("Debug overlays and advanced diagnostics."), target: .developer, isPlaceholder: false))
        return items
    }
}

// MARK: - Category Item Model

struct SettingsCategoryItem {
    let icon: String
    let title: String
    let desc: String
    let target: Target
    let isPlaceholder: Bool

    enum Target {
        case memoryFacts, people, channels, gallery
        case modelCenter, remoteModels, localModels, sandbox
        case developer, dataPrivacy
    }
}

// MARK: - Account Settings

// MARK: - PoLang Auth Client（对齐 Android PoLangAuthClient，URLSession 实现）

struct AuthResult { let token: String; let llmCallsUsed: Int; let llmCallsLimit: Int }
struct QuotaInfo { let email: String; let llmCallsUsed: Int; let llmCallsLimit: Int }
struct AuthError: LocalizedError { let code: Int; let message: String; var errorDescription: String? { "HTTP \(code): \(message)" } }

final class PoLangAuthClient {
    static let shared = PoLangAuthClient()
    private let base = "https://api.polang.net"

    private func request(_ path: String, method: String, token: String? = nil, body: [String: Any]? = nil) -> URLRequest {
        var req = URLRequest(url: URL(string: "\(base)\(path)")!)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("ios", forHTTPHeaderField: "X-Platform")
        if let token { req.setValue(token, forHTTPHeaderField: "X-App-Token") }
        if let body { req.httpBody = try? JSONSerialization.data(withJSONObject: body) }
        return req
    }
    private func errMsg(_ data: Data) -> String {
        (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String ?? "unknown_error"
    }
    private func statusCode(_ resp: URLResponse) -> Int { (resp as? HTTPURLResponse)?.statusCode ?? -1 }

    func sendCode(email: String) async throws {
        let (data, resp) = try await URLSession.shared.data(for:request("/auth/email/send", method: "POST", body: ["email": email]))
        if !(200..<300).contains(statusCode(resp)) { throw AuthError(code: statusCode(resp), message: errMsg(data)) }
    }
    func verify(email: String, code: String) async throws -> AuthResult {
        let (data, resp) = try await URLSession.shared.data(for:request("/auth/email/verify", method: "POST", body: ["email": email, "code": code]))
        let sc = statusCode(resp); guard (200..<300).contains(sc) else { throw AuthError(code: sc, message: errMsg(data)) }
        let j = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        return AuthResult(token: j["token"] as? String ?? "",
                          llmCallsUsed: j["llmCallsUsed"] as? Int ?? 0,
                          llmCallsLimit: j["llmCallsLimit"] as? Int ?? 100)
    }
    func getQuota(token: String) async throws -> QuotaInfo {
        let (data, resp) = try await URLSession.shared.data(for:request("/auth/quota", method: "GET", token: token))
        let sc = statusCode(resp); guard (200..<300).contains(sc) else { throw AuthError(code: sc, message: errMsg(data)) }
        let j = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        return QuotaInfo(email: j["email"] as? String ?? "",
                         llmCallsUsed: j["llmCallsUsed"] as? Int ?? 0,
                         llmCallsLimit: j["llmCallsLimit"] as? Int ?? 100)
    }
    func deleteAccount(token: String) async throws {
        let (data, resp) = try await URLSession.shared.data(for:request("/auth/account", method: "DELETE", token: token))
        if !(200..<300).contains(statusCode(resp)) { throw AuthError(code: statusCode(resp), message: errMsg(data)) }
    }
    /// 清除访客数据（对齐 Android PoLangAuthClient.clearGuestData：DELETE /guest/device + X-Device-Id）
    func clearGuestData(deviceId: String) async throws {
        var req = request("/guest/device", method: "DELETE")
        req.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")
        let (data, resp) = try await URLSession.shared.data(for: req)
        if !(200..<300).contains(statusCode(resp)) { throw AuthError(code: statusCode(resp), message: errMsg(data)) }
    }
}

// MARK: - Account Settings（邮箱验证码注册/登录 + 额度，对齐 Android ServerAuthSection）

struct AccountSettingsView: View {
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    @AppStorage("server_auth_token") private var token = ""
    @AppStorage("server_auth_email") private var storedEmail = ""

    @State private var emailInput = ""
    @State private var codeInput = ""
    @State private var codeSent = false
    @State private var sending = false
    @State private var verifying = false
    @State private var errorMsg: String?
    @State private var quota: QuotaInfo?
    @State private var loadingQuota = false
    @State private var showDeleteConfirm = false

    private var loggedIn: Bool { !token.isEmpty }
    private let client = PoLangAuthClient.shared

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if loggedIn { accountDetail } else { registerForm }
            }
            .padding(20)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Account"))
        .navigationBarTitleDisplayMode(.inline)
        .task { if loggedIn { await refreshQuota() } }
        .confirmationDialog(L("Delete account? This cannot be undone."), isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button(L("Delete Account"), role: .destructive) { Task { await deleteAccount() } }
            Button(L("Cancel"), role: .cancel) {}
        }
        .alert(L("Error"), isPresented: Binding(get: { errorMsg != nil }, set: { _ in errorMsg = nil })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMsg ?? "") }
    }

    // MARK: 注册/登录表单
    private var registerForm: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L("Account")).font(AppTypography.titleMedium.font).foregroundColor(s.onSurface)
            Text(L("Sign in for more quota and features")).font(AppTypography.bodySmall.font).foregroundColor(s.onSurfaceVariant)
            TextField(L("Email"), text: $emailInput)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .keyboardType(.emailAddress)
                .padding(12).background(s.surfaceContainerHigh).clipShape(AppShapes.small)
            if codeSent {
                TextField(L("Verification Code"), text: $codeInput)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.numberPad)
                    .padding(12).background(s.surfaceContainerHigh).clipShape(AppShapes.small)
            }
            Button { Task { await sendCode() } } label: {
                Text(sending ? L("Sending…") : L("Send Code")).frame(maxWidth: .infinity)
            }.buttonStyle(.borderedProminent).disabled(emailInput.isEmpty || sending)
            if codeSent {
                Button { Task { await verify() } } label: {
                    Text(verifying ? L("Verifying…") : L("Verify & Sign In")).frame(maxWidth: .infinity)
                }.buttonStyle(.bordered).disabled(codeInput.isEmpty || verifying)
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHighest).clipShape(AppShapes.card)
    }

    // MARK: 已登录详情
    private var accountDetail: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(matIcon: "person").font(.system(size: 24)).foregroundColor(s.primary)
                Text(storedEmail).font(AppTypography.titleSmall.font).foregroundColor(s.onSurface)
            }
            SettingsM3Divider()
            if let quota {
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("LLM Quota")).font(AppTypography.bodySmall.font).foregroundColor(s.onSurfaceVariant)
                    HStack { Spacer(); Text("\(quota.llmCallsUsed) / \(quota.llmCallsLimit)").font(AppTypography.titleSmall.font).foregroundColor(s.primary) }
                    ProgressView(value: Double(quota.llmCallsUsed), total: Double(max(quota.llmCallsLimit, 1)))
                        .tint(quota.llmCallsUsed >= quota.llmCallsLimit ? s.error : s.primary)
                }
            } else if loadingQuota {
                ProgressView()
            }
            Button { Task { await refreshQuota() } } label: { Text(L("Refresh")).frame(maxWidth: .infinity) }.buttonStyle(.bordered)
            Button { logout() } label: { Text(L("Logout")).frame(maxWidth: .infinity) }.buttonStyle(.bordered).foregroundColor(s.error)
            Button { showDeleteConfirm = true } label: { Text(L("Delete Account")).frame(maxWidth: .infinity) }.buttonStyle(.bordered).foregroundColor(s.error)
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHighest).clipShape(AppShapes.card)
    }

    // MARK: Actions
    private func sendCode() async {
        sending = true; defer { sending = false }
        do { try await client.sendCode(email: emailInput); codeSent = true } catch { errorMsg = (error as? AuthError)?.message ?? L("Network error") }
    }
    private func verify() async {
        verifying = true; defer { verifying = false }
        do {
            let r = try await client.verify(email: emailInput, code: codeInput)
            token = r.token; storedEmail = emailInput; quota = QuotaInfo(email: emailInput, llmCallsUsed: r.llmCallsUsed, llmCallsLimit: r.llmCallsLimit)
            codeSent = false; codeInput = ""
            // 访客注册引导计数清零（chat.yaml §4.1 counter.reset_on=register_success；
            // 注册入口不止 chat——此处为 Settings 入口的同步清零挂钩）
            ChatViewModel.resetGuestMessageCount()
        } catch { errorMsg = (error as? AuthError)?.message ?? L("Network error") }
    }
    private func refreshQuota() async {
        loadingQuota = true; defer { loadingQuota = false }
        do { quota = try await client.getQuota(token: token) } catch { errorMsg = (error as? AuthError)?.message ?? L("Network error") }
    }
    private func logout() { token = ""; storedEmail = ""; quota = nil }
    private func deleteAccount() async {
        do {
            try await client.deleteAccount(token: token); logout()
        } catch { errorMsg = (error as? AuthError)?.message ?? L("Network error") }
    }
}

// MARK: - Data & Privacy

struct DataPrivacyView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    /// 清除访客数据（对齐 Android ClearGuestDataButton，DELETE /guest/device）
    @State private var clearingGuest = false
    @State private var guestClearToast: String?

    private let sections: [(title: String, body: String)] = [
        (L("Account Data"), L("Your email is used only for authentication and LLM free trial usage counting (default 100 times). No passwords are collected — login uses email verification codes.")),
        (L("Device Identifier"), L("A device identifier is generated to count free trial usage for unregistered guests. This identifier is sent to api.polang.net and is not used for personal identification or shared with third parties.")),
        (L("Data Retention"), L("After an account is deleted, data will be retained for 90 days (for anti-fraud and recovery purposes) before being permanently deleted, including usage logs.")),
        (L("Delete Your Account"), L("You can delete your account through Settings → Account → Delete Account, or by emailing us.")),
        (L("Local Processing"), L("Photos, beauty filters, facial keypoints, OCR text, media location, and chat memory are all processed locally on your device and are never uploaded to a server.")),
        (L("Remote Inference"), L("After authenticating, remote LLM conversations are proxied through api.polang.net to the LLM provider for the current request only. The server only records call counts and token usage, not conversation content.")),
        (L("Contact Us"), "budao.gs@gmail.com"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // 备份与恢复入口（iOS 尚未实现，占位；Android 已并入此页）
                HStack {
                    Text(L("Backup & Restore"))
                        .font(.system(size: 15, weight: .semibold))
                    Spacer()
                    Text(L("Coming Soon"))
                        .font(.system(size: 12))
                        .foregroundColor(.secondary.opacity(0.6))
                }
                .padding(.vertical, 4)

                // 清除访客数据（对齐 Android ClearGuestDataButton，数据隐私段）
                Button {
                    guard !clearingGuest else { return }
                    clearingGuest = true
                    Task {
                        do {
                            try await PoLangAuthClient.shared.clearGuestData(
                                deviceId: DeviceIdStore.shared.getOrCreate())
                            guestClearToast = L("Guest data cleared")
                        } catch {
                            guestClearToast = L("Failed to clear guest data")
                        }
                        clearingGuest = false
                    }
                } label: {
                    HStack {
                        Image(systemName: "trash.slash")
                            .font(.system(size: 16))
                        Text(L("Clear Guest Data"))
                            .font(.system(size: 15, weight: .semibold))
                        Spacer()
                        if clearingGuest { ProgressView() }
                    }
                }
                .disabled(clearingGuest)
                .padding(.vertical, 4)

                ForEach(sections, id: \.title) { section in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(section.title)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.accentColor)
                        Text(section.body)
                            .font(.system(size: 13))
                            .foregroundColor(.secondary)
                    }
                }

                if let url = URL(string: "https://polang.net/privacy-policy/") {
                    Link(L("View Full Privacy Policy"), destination: url)
                        .font(.system(size: 14, weight: .medium))
                        .padding(.top, 8)
                }
            }
            .padding(20)
        }
        .background(s.background.ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if let toast = guestClearToast {
                Text(toast)
                    .font(.system(size: 14))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color.black.opacity(0.8))
                    .foregroundStyle(.white)
                    .clipShape(Capsule())
                    .padding(.bottom, 24)
                    .transition(.opacity)
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { guestClearToast = nil }
                    }
            }
        }
        .navigationTitle(L("Data & Privacy"))
        .navigationBarTitleDisplayMode(.inline)
        // back 由 NavigationStack 系统提供，无需手动 toolbar
    }
}

// MARK: - About

struct AboutView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    private var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(spacing: 8) {
                    Image(matIcon: "photo_library")
                        .font(.system(size: 48))
                        .foregroundColor(.accentColor)
                    Text("PoLang")
                        .font(.system(size: 20, weight: .bold))
                    Text(L("PoLang · AI Agent Album"))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                .padding(.top, 20)

                VStack(spacing: 0) {
                    infoRow(label: L("Version"), value: appVersion)
                    SettingsM3Divider()
                    if let url = URL(string: "https://github.com/littleseven/polang") {
                        Link(destination: url) {
                            infoRow(label: "GitHub", value: "littleseven/polang", showArrow: true)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .background(s.surfaceContainerHighest)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 16)

                Text(L("Built with Kotlin Multiplatform, Koog, SwiftUI, Metal, MNN"))
                    .font(.system(size: 11))
                    .foregroundColor(.secondary.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)

                Spacer()
            }
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("About"))
        .navigationBarTitleDisplayMode(.inline)
        // back 由 NavigationStack 系统提供，无需手动 toolbar
    }

    private func infoRow(label: String, value: String, showArrow: Bool = false) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 14))
            Spacer()
            Text(value)
                .font(.system(size: 14))
                .foregroundColor(.secondary)
            if showArrow {
                Image(matIcon: "arrow_forward")
                    .font(.system(size: 14))
                    .foregroundColor(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

// MARK: - 本地模型设置（P3：人脸检测/照片打标/语音）

struct LocalModelsSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    // 人脸检测——引擎二选一（真接，对标 Android face detection engine）
    @AppStorage("camera_use_mnn") private var useMnn: Bool = true
    // 人脸细分（iOS 不支持，持久化占位）
    @AppStorage("face_landmark_mode") private var landmarkMode: Bool = true
    @AppStorage("adaptive_face_detection_interval") private var adaptiveInterval: Bool = true
    @AppStorage("face_detect_interval_profile") private var intervalProfile: String = "BALANCED"
    // 照片打标模型（Florence 真接；Qwen iOS 不可用）
    @AppStorage("tagger_model_key") private var taggerModelKey: String = "auto"
    // 语音（iOS 无引擎，持久化占位）
    @AppStorage("voice_command_mode") private var voiceMode: String = "DISABLED"

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                faceDetectionCard
                photoTaggingCard
                voiceCard
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Local Models"))
        .navigationBarTitleDisplayMode(.inline)
        // back 由 NavigationStack 系统提供，无需手动 toolbar
    }

    private func sectionCard<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.system(size: 14, weight: .semibold))
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func chip(_ label: String, isSelected: Bool, isDisabled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? .white : (isDisabled ? .secondary : .primary))
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(isSelected ? Color.accentColor : s.surfaceContainerHigh)
                .clipShape(Capsule())
        }
        .disabled(isDisabled)
    }

    private func iosUnsupportedNote(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11))
            .foregroundColor(.secondary.opacity(0.7))
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: 人脸检测
    private var faceDetectionCard: some View {
        sectionCard(title: L("Face Detection")) {
            Text(L("Choose face detection models and device; tune landmarks and cadence."))
                .font(.system(size: 12)).foregroundColor(.secondary)
            // 引擎二选一（真接 camera_use_mnn）
            Text(L("Detection engine"))
                .font(.system(size: 13, weight: .medium)).foregroundColor(.secondary)
                .padding(.top, 4)
            HStack(spacing: 8) {
                chip("MNN", isSelected: useMnn) { useMnn = true }
                chip("MediaPipe", isSelected: !useMnn) { useMnn = false }
            }
            // 细分项（iOS 不支持，占位灰显）
            SettingsM3Divider().padding(.vertical, 2)
            HStack {
                Text(L("Face landmark mode")).font(.system(size: 14))
                Spacer()
                Toggle("", isOn: $landmarkMode).labelsHidden().disabled(true)
            }.foregroundColor(.secondary)
            HStack {
                Text(L("Adaptive detection interval")).font(.system(size: 14))
                Spacer()
                Toggle("", isOn: $adaptiveInterval).labelsHidden().disabled(true)
            }.foregroundColor(.secondary)
            iosUnsupportedNote(L("iOS uses a single fixed pipeline (CPU only). Per-stage models, GPU and interval profiles are not configurable on iOS."))
        }
    }

    // MARK: 照片打标
    private var photoTaggingCard: some View {
        sectionCard(title: L("Photo Tagging")) {
            Text(L("Auto-detect photo content into tags (scene, objects, people, activity, aesthetic) for search and grouping."))
                .font(.system(size: 12)).foregroundColor(.secondary)
            Text(L("Tagging model"))
                .font(.system(size: 13, weight: .medium)).foregroundColor(.secondary)
                .padding(.top, 4)
            HStack(spacing: 8) {
                chip(L("Auto"), isSelected: taggerModelKey == "auto") { taggerModelKey = "auto" }
                chip("Florence-2", isSelected: taggerModelKey == "florence2_base") { taggerModelKey = "florence2_base" }
                chip("Qwen3-VL", isSelected: taggerModelKey == "qwen3_vl_2b", isDisabled: true) {
                    taggerModelKey = "qwen3_vl_2b"
                }
            }
            iosUnsupportedNote(L("iOS supports Florence-2 only. Qwen3-VL tagging is unavailable on iOS; selecting it falls back to Florence-2."))
        }
    }

    // MARK: 语音
    private var voiceCard: some View {
        sectionCard(title: L("Voice")) {
            iosUnsupportedNote(L("On-device voice recognition (ASR) and wake word (KWS) are not yet implemented on iOS. Settings below are placeholders for future versions."))
            Text(L("Voice interaction mode"))
                .font(.system(size: 13, weight: .medium)).foregroundColor(.secondary)
                .padding(.top, 4)
            HStack(spacing: 8) {
                chip(L("Disabled"), isSelected: voiceMode == "DISABLED", isDisabled: true) { }
                chip(L("Push to talk"), isSelected: voiceMode == "PUSH_TO_TALK", isDisabled: true) { }
                chip(L("Wake word"), isSelected: voiceMode == "WAKE_WORD", isDisabled: true) { }
            }
        }
    }
}

// MARK: - 沙盒与权限（P4：执行 / 设备访问）

struct SandboxSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    // 执行（软开关，两端均 persistence-only，能力层消费待接入）
    @AppStorage("auto_execute_plans") private var autoExecute: Bool = true
    @AppStorage("js_engine_enabled") private var jsEngine: Bool = false
    // 设备访问软开关（persistence-only）
    @AppStorage("agent_camera_access_enabled") private var cameraAccess: Bool = true
    @AppStorage("agent_gallery_access_enabled") private var galleryAccess: Bool = true
    // 语音（iOS 无引擎，占位）
    @AppStorage("voice_command_mode") private var voiceMode: String = "DISABLED"
    @AppStorage("voice_entry_enabled") private var voiceEntry: Bool = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                executionCard
                deviceAccessCard
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Sandbox & Permissions"))
        .navigationBarTitleDisplayMode(.inline)
        // back 由 NavigationStack 系统提供，无需手动 toolbar
    }

    private func sectionCard<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.system(size: 14, weight: .semibold))
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func toggleRow(_ title: String, isOn: Binding<Bool>) -> some View {
        HStack {
            Text(title).font(.system(size: 14))
            Spacer()
            Toggle("", isOn: isOn).labelsHidden()
        }
    }

    private func systemPermissionRow(_ title: String) -> some View {
        Button {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        } label: {
            HStack {
                Text(title).font(.system(size: 14)).foregroundColor(.primary)
                Spacer()
                Image(matIcon: "arrow_forward").font(.system(size: 14)).foregroundColor(.secondary)
            }
        }
        .buttonStyle(.plain)
    }

    private var executionCard: some View {
        sectionCard(title: L("Execution")) {
            Text(L("Control what the Agent can run autonomously."))
                .font(.system(size: 12)).foregroundColor(.secondary)
            toggleRow(L("Auto-execute multi-step plans"), isOn: $autoExecute)
            SettingsM3Divider()
            toggleRow(L("JS engine execution"), isOn: $jsEngine)
            Text(L("Allow the Agent to execute JS sandbox scripts."))
                .font(.system(size: 11)).foregroundColor(.secondary.opacity(0.7))
        }
    }

    private var deviceAccessCard: some View {
        sectionCard(title: L("Device Access")) {
            Text(L("Control which device capabilities the Agent may use."))
                .font(.system(size: 12)).foregroundColor(.secondary)
            toggleRow(L("Camera access"), isOn: $cameraAccess)
            systemPermissionRow(L("Camera permission (system)"))
            SettingsM3Divider()
            toggleRow(L("Gallery access"), isOn: $galleryAccess)
            systemPermissionRow(L("Gallery permission (system)"))
            SettingsM3Divider()
            // 语音（iOS 占位灰显）
            HStack {
                Text(L("Voice interaction mode")).font(.system(size: 14)).foregroundColor(.secondary)
                Spacer()
                Text(L("Disabled")).font(.system(size: 13)).foregroundColor(.secondary)
            }
            Text(L("On-device voice recognition (ASR) and wake word (KWS) are not yet implemented on iOS."))
                .font(.system(size: 11)).foregroundColor(.secondary.opacity(0.7))
        }
    }
}

#Preview {
    NavigationStack {
        SettingsScreen()
    }
}
