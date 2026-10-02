import SwiftUI
import AVFoundation
import Photos

extension Notification.Name {
    /// 设置页请求切主页面 Pager 页（object = 页索引；Gallery Cleanup → 整理页 1，对齐 Android
    /// 「切主页面 Pager 页 1」语义）。设置页为 fullScreenCover、无法直触 MainTabView 内持有的
    /// MainNavigationRouter，经通知解耦（MainTabView 侧关设置 cover 并瞬切页）。
    static let settingsRequestMainPage = Notification.Name("settings_request_main_page")
}

/// 设置主屏（对齐 Android SettingsScreen 主菜单 list_sections，spec settings.yaml §2）。
/// 结构：账号英雄卡 → 4 个列表分组（个性化/功能/AI 与系统/其他）→ 版本页脚。
/// 2026-10-02 S3a：原「主题/语言快选卡 + 2 列分类卡片网格」重构为列表式分组，
/// 补 Gallery Cleanup / Camera / Report a problem 三个入口。
struct SettingsScreen: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.colorScheme) private var cs
    /// M3 语义色（对标 Android MaterialTheme.colorScheme）
    private var s: SchemeColors { appScheme(cs) }

    // 2026-08-15 用户定：iOS 开发者选项卡片直接显示，无 7 连点解锁门控（与 Android 差异已登记 settings.yaml 台账）
    /// 相册设置（扫描控制台）以 fullScreenCover 呈现（TagScanScreen 既有模式）
    @State private var showGalleryConsole = false
    /// 主题/语言弹层单选（原快选 chips 卡改行 + 弹层，spec §2 personalization 组）
    @State private var showThemeDialog = false
    @State private var showLanguageDialog = false
    /// 上报问题提交反馈（项目无共享 toast 构件，沿用 DataPrivacyView 同款临时 overlay）
    @State private var issueFeedbackToast: String?
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
            VStack(spacing: SettingsTokens.listSectionSpacing) {
                accountHeroCard
                personalizationSection
                featuresSection
                aiSystemSection
                othersSection
                versionFooter
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
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
        // 主题单选弹层（对齐 Android theme_choice_dialog；单选弹层形态按平台原生差异层取 confirmationDialog）
        .confirmationDialog(L("Theme Mode"), isPresented: $showThemeDialog, titleVisibility: .visible) {
            themeOption("system", label: L("System Default"))
            themeOption("light", label: L("Light"))
            themeOption("dark", label: L("Dark"))
            Button(L("Cancel"), role: .cancel) {}
        }
        // 语言单选弹层（language_choice_dialog；语言名与 Android 一致为专名原文，不随界面语言翻译）
        .confirmationDialog(L("Language"), isPresented: $showLanguageDialog, titleVisibility: .visible) {
            languageOption("system", label: L("System Default"))
            languageOption("english", label: "English")
            languageOption("chinese_simplified", label: "中文")
            languageOption("chinese_traditional", label: "繁體中文")
            languageOption("spanish", label: "Español")
            languageOption("french", label: "Français")
            Button(L("Cancel"), role: .cancel) {}
        }
        .overlay(alignment: .bottom) { issueToast }
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

    // MARK: - 主题/语言弹层选项

    private func themeOption(_ value: String, label: String) -> some View {
        Button(label) { settings.themeMode = value }
    }

    private func languageOption(_ value: String, label: String) -> some View {
        Button(label) { settings.appLanguage = value }
    }

    private var currentThemeLabel: String {
        switch settings.themeMode {
        case "light": return L("Light")
        case "dark": return L("Dark")
        default: return L("System Default")
        }
    }

    private var currentLanguageLabel: String {
        switch settings.appLanguage {
        case "english": return "English"
        case "chinese_simplified": return "中文"
        case "chinese_traditional": return "繁體中文"
        case "spanish": return "Español"
        case "french": return "Français"
        default: return L("System Default")
        }
    }

    // MARK: - ②~⑤ 列表分组（spec §2 list_sections：行 = 圆形彩色图标块 + 标题 + 可选右值 + 可选 chevron）

    @ViewBuilder
    private func navRow<Destination: View>(_ row: SettingsListRow, @ViewBuilder destination: () -> Destination) -> some View {
        NavigationLink { destination() } label: { row }
            .buttonStyle(.plain)
    }

    private var personalizationSection: some View {
        SettingsListSection {
            SettingsListRow(
                title: L("Theme Mode"),
                valueText: currentThemeLabel,
                icon: .sf("moon.fill"),
                iconBlock: .vibrantOrange,
                action: { showThemeDialog = true }
            )
            SettingsListDivider()
            SettingsListRow(
                title: L("Language"),
                valueText: currentLanguageLabel,
                icon: .sf("globe"),
                iconBlock: .vibrantBlue,
                action: { showLanguageDialog = true }
            )
        }
    }

    private var featuresSection: some View {
        SettingsListSection {
            navRow(
                SettingsListRow(title: L("People"), icon: .mat("account_circle"), iconBlock: .vibrantGreen, showChevron: true)
            ) {
                PersonView().environmentObject(AppContainer.shared)
            }
            SettingsListDivider()
            navRow(
                SettingsListRow(title: L("AI Memory"), icon: .mat("psychology"), iconBlock: .primaryContainer, showChevron: true)
            ) {
                MemoryFactsView()
            }
            SettingsListDivider()
            // 相册扫描（原 Gallery 行，gallery_settings 键）：保留 fullScreenCover 扫描控制台既有行为
            SettingsListRow(
                title: L("Gallery Scan"),
                icon: .mat("photo_library"),
                iconBlock: .vibrantOrange,
                showChevron: true
            ) {
                showGalleryConsole = true
            }
            SettingsListDivider()
            // 相册整理（去重 2.0）一级入口：切主页面 Pager 页 1（dedup 设计规范 §11.1 对齐）
            SettingsListRow(
                title: L("Gallery Cleanup"),
                icon: .sf("square.stack.3d.up.fill"),
                iconBlock: .vibrantPurple,
                showChevron: true
            ) {
                NotificationCenter.default.post(name: .settingsRequestMainPage, object: NSNumber(value: 1))
            }
            SettingsListDivider()
            navRow(
                SettingsListRow(title: L("Camera"), icon: .mat("camera_alt"), iconBlock: .vibrantPink, showChevron: true)
            ) {
                CameraSettingsView()
            }
        }
    }

    private var aiSystemSection: some View {
        SettingsListSection {
            navRow(
                SettingsListRow(title: L("Model Center"), icon: .mat("cloud_download"), iconBlock: .vibrantGreen, showChevron: true)
            ) {
                ModelDownloadCenterView()
            }
            SettingsListDivider()
            navRow(
                SettingsListRow(title: L("Remote Models"), icon: .sf("cloud.fill"), iconBlock: .statusInfo, showChevron: true)
            ) {
                ModelCenterView().environmentObject(ModelConfigStore.shared)
            }
            SettingsListDivider()
            navRow(
                SettingsListRow(title: L("Local Models"), icon: .sf("memorychip"), iconBlock: .vibrantPink, showChevron: true)
            ) {
                LocalModelsSettingsView()
            }
            SettingsListDivider()
            navRow(
                SettingsListRow(title: L("Communication Channel"), icon: .mat("forum"), iconBlock: .vibrantOrange, showChevron: true)
            ) {
                CommunicationChannelView()
            }
            SettingsListDivider()
            navRow(
                SettingsListRow(title: L("Sandbox & Permissions"), icon: .sf("checkmark.shield.fill"), iconBlock: .primaryContainer, showChevron: true)
            ) {
                SandboxSettingsView()
            }
        }
    }

    private var othersSection: some View {
        SettingsListSection {
            navRow(
                SettingsListRow(title: L("Data & privacy"), icon: .mat("privacy_tip"), iconBlock: .vibrantBlue, showChevron: true)
            ) {
                DataPrivacyView()
            }
            SettingsListDivider()
            // 上报问题：自包含行 + 对话框 + 提交 client（spec §2 report_issue_dialog）
            ReportIssueEntryView { feedback in
                issueFeedbackToast = feedback
            }
            SettingsListDivider()
            // 开发者选项直接显示（iOS 不做 7 连点门控，2026-08-15 用户定，差异已登记台账）
            navRow(
                SettingsListRow(title: L("Developer Options"), icon: .mat("terminal"), iconBlock: .warningAmber, showChevron: true)
            ) {
                DeveloperSettingsView()
            }
        }
    }

    // MARK: - 上报问题反馈 toast（临时 overlay，DataPrivacyView 同款形态）

    @ViewBuilder
    private var issueToast: some View {
        if let toast = issueFeedbackToast {
            Text(toast)
                .font(.system(size: 14))
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, Spacing.md)
                .background(Color.black.opacity(0.8))
                .foregroundStyle(.white)
                .clipShape(Capsule())
                .padding(.bottom, Spacing.xxl)
                .transition(.opacity)
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { issueFeedbackToast = nil }
                }
        }
    }

    // MARK: - Version footer（iOS 无 7 连点解锁逻辑，页脚纯展示）

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
            .background(s.cell)
            .clipShape(AppShapes.card)
        }
        .buttonStyle(.plain)
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

// MARK: - Account Settings（spec settings.yaml §4 account，2026-10-02 S3d 分层重构）

/// 清除访客数据共享动作（DELETE /guest/device；账号页 Danger Zone 与隐私页操作行共用入口）。
private func performClearGuestData() async -> String {
    do {
        try await PoLangAuthClient.shared.clearGuestData(deviceId: DeviceIdStore.shared.getOrCreate())
        return L("Guest data cleared")
    } catch {
        return L("Failed to clear guest data")
    }
}

/// 访客清除结果 toast（账号页/隐私页同款形态；1.8s 后自动清除）。
private struct GuestClearToast: View {
    @Binding var text: String?

    var body: some View {
        if let text {
            Text(text)
                .font(.system(size: 14))
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color.black.opacity(0.8))
                .foregroundStyle(.white)
                .clipShape(Capsule())
                .padding(.bottom, 24)
                .transition(.opacity)
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { self.text = nil }
                }
        }
    }
}

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
    @State private var clearingGuest = false
    @State private var guestClearToast: String?

    private var loggedIn: Bool { !token.isEmpty }
    private let client = PoLangAuthClient.shared

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.lg) {
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
        .overlay(alignment: .bottom) {
            GuestClearToast(text: $guestClearToast)
        }
    }

    // MARK: 注册/登录表单（spec §4 registration_form；逻辑保持既有 sendCode/verify）

    private var registerForm: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L("Register")).font(AppTypography.titleMedium.font).foregroundColor(s.onSurface)
            Text(L("Create an account for more quota")).font(AppTypography.bodySmall.font).foregroundColor(s.onSurfaceVariant)
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
                    Text(verifying ? L("Verifying…") : L("Register / Login")).frame(maxWidth: .infinity)
                }.buttonStyle(.bordered).disabled(codeInput.isEmpty || verifying)
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(s.cell).clipShape(AppShapes.card)
    }

    // MARK: 已登录（spec §4 logged_in 分层：头部 → Quota 卡 → actions → danger zone）

    private var accountDetail: some View {
        VStack(spacing: Spacing.lg) {
            accountHeader
            quotaCard
            actionButtons
            dangerZone
        }
    }

    /// 头部：person 图标 56（primaryContainer 底）+ email 16 medium + 副题 Server Account。
    private var accountHeader: some View {
        VStack(spacing: Spacing.sm) {
            ZStack {
                Circle().fill(s.primaryContainer).frame(width: 56, height: 56)
                Image(matIcon: "person").font(.system(size: 28)).foregroundColor(s.onPrimaryContainer)
            }
            Text(storedEmail)
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(s.onSurface)
                .lineLimit(1)
                .truncationMode(.tail)
            Text(L("Server Account"))
                .font(AppTypography.bodySmall.font)
                .foregroundColor(s.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, Spacing.sm)
    }

    /// Quota 卡（spec §4 quota_card：surfaceContainerLow 底 16 圆角；值与进度条 ≥90% error 色；8 高进度条）。
    private var quotaCard: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            if let quota {
                Text(L("Quota"))
                    .font(AppTypography.titleSmall.font)
                    .foregroundColor(s.onSurface)
                Text("\(quota.llmCallsUsed) / \(quota.llmCallsLimit)")
                    .font(AppTypography.titleMedium.font)
                    .foregroundColor(quotaHighUsage ? s.error : s.primary)
                QuotaProgressBar(progress: quotaProgress, tint: quotaHighUsage ? s.error : s.primary)
                Text(String(format: L("Remaining: %1$d calls"), max(quota.llmCallsLimit - quota.llmCallsUsed, 0)))
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.onSurfaceVariant)
            } else if loadingQuota {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.lg)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerLow)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var quotaProgress: Double {
        guard let quota, quota.llmCallsLimit > 0 else { return 0 }
        return min(Double(quota.llmCallsUsed) / Double(quota.llmCallsLimit), 1)
    }

    /// ≥90% 高水位 → error 色（spec §4 value_color: primary (or error if ≥90%)）。
    private var quotaHighUsage: Bool {
        guard let quota, quota.llmCallsLimit > 0 else { return false }
        return Double(quota.llmCallsUsed) >= Double(quota.llmCallsLimit) * 0.9
    }

    /// actions（spec §4：Refresh 主按钮 / Logout 次按钮）。
    private var actionButtons: some View {
        HStack(spacing: Spacing.md) {
            Button { Task { await refreshQuota() } } label: {
                HStack(spacing: 6) {
                    MatIcon(name: "mat_refresh", size: 16)
                    Text(L("Refresh"))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            Button { logout() } label: {
                HStack(spacing: 6) {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .font(.system(size: 15))
                    Text(L("Logout"))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    /// danger zone（spec §4：Clear Guest Data / Delete Account 均 error 色；仅删除带确认）。
    private var dangerZone: some View {
        VStack(spacing: Spacing.sm) {
            Button { Task { await clearGuestData() } } label: {
                HStack(spacing: 6) {
                    MatIcon(name: "mat_delete_sweep", size: 16)
                    if clearingGuest { ProgressView().tint(s.error) }
                    Text(L("Clear Guest Data"))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(s.error)
            .disabled(clearingGuest)

            Button { showDeleteConfirm = true } label: {
                HStack(spacing: 6) {
                    MatIcon(name: "mat_delete", size: 16)
                    Text(L("Delete Account"))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(s.error)
        }
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
    private func clearGuestData() async {
        clearingGuest = true; defer { clearingGuest = false }
        guestClearToast = await performClearGuestData()
    }
}

/// 8pt 高线性进度条（spec §4 progress_bar height 8；系统 ProgressView 无法定制轨道高度）。
private struct QuotaProgressBar: View {
    let progress: Double
    let tint: Color

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(tint.opacity(0.15))
                Capsule()
                    .fill(tint)
                    .frame(width: proxy.size.width * CGFloat(min(max(progress, 0), 1)))
            }
        }
        .frame(height: 8)
    }
}

// MARK: - Data & Privacy

struct DataPrivacyView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    /// 清除访客数据（对齐 Android ClearGuestDataButton，DELETE /guest/device；行点击先确认）
    @State private var clearingGuest = false
    @State private var guestClearToast: String?
    @State private var showClearGuestConfirm = false

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

                // 操作行（spec §5 rows：备份恢复 iOS Coming Soon 占位 + 清除访客数据带确认）
                SettingsListSection {
                    SettingsListRow(
                        title: L("Backup & Restore"),
                        valueText: L("Coming Soon"),
                        icon: .sf("externaldrive.badge.timemachine")
                    )
                    .modifier(GrayedOut())
                    SettingsListDivider()
                    SettingsListRow(
                        title: L("Clear Guest Data"),
                        icon: .mat("delete"),
                        action: { showClearGuestConfirm = true }
                    )
                    .disabled(clearingGuest)
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
            GuestClearToast(text: $guestClearToast)
        }
        .navigationTitle(L("Data & Privacy"))
        .navigationBarTitleDisplayMode(.inline)
        // back 由 NavigationStack 系统提供，无需手动 toolbar
        .confirmationDialog(L("Clear Guest Data"), isPresented: $showClearGuestConfirm, titleVisibility: .visible) {
            Button(L("Clear"), role: .destructive) { Task { await clearGuestData() } }
            Button(L("Cancel"), role: .cancel) {}
        }
    }

    /// 与账号页 Danger Zone 共用 performClearGuestData（Settings 入口双挂点）。
    private func clearGuestData() async {
        clearingGuest = true; defer { clearingGuest = false }
        guestClearToast = await performClearGuestData()
    }
}

/// 能力缺口灰显注记（spec §9 允许平台差异层）：禁用交互并压暗整行/整组。
private struct GrayedOut: ViewModifier {
    func body(content: Content) -> some View {
        content
            .environment(\.isEnabled, false)
            .opacity(0.55)
    }
}

#Preview {
    NavigationStack {
        SettingsScreen()
    }
}
