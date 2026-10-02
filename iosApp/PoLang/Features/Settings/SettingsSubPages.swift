import SwiftUI

// MARK: - Communication Channel Settings

/// 通信通道分类页（spec settings.yaml §3f channels，2026-10-02 S3d 对齐）。
/// 三组：通道选择（chips 单选 + 状态行）/ 飞书 / Telegram——后两组双常驻（不随选中通道显隐）。
struct CommunicationChannelView: View {
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    @AppStorage("channel_type") private var channelType: String = "none"
    @AppStorage("feishu_app_id") private var feishuAppId = ""
    @AppStorage("feishu_app_secret") private var feishuAppSecret = ""
    @AppStorage("telegram_bot_token") private var telegramBotToken = ""
    @AppStorage("telegram_chat_id") private var telegramChatId = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: SettingsTokens.listSectionSpacing) {
                selectionGroup
                feishuGroup
                telegramGroup
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Communication Channel"))
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: 组1 通道选择（channel_selection：chips 单选 + 状态行）

    private var selectionGroup: some View {
        SettingsListGroup(title: L("Active Channel")) {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                FlowLayout(spacing: Spacing.sm) {
                    SettingsM3Chip(label: L("Feishu"), isSelected: channelType == "feishu") { channelType = "feishu" }
                    SettingsM3Chip(label: L("Telegram"), isSelected: channelType == "telegram") { channelType = "telegram" }
                    SettingsM3Chip(label: L("None"), isSelected: channelType == "none") { channelType = "none" }
                }
                Text(statusText)
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.onSurfaceVariant)
            }
            .padding(.horizontal, SettingsTokens.listRowPaddingH)
            .padding(.vertical, Spacing.sm)
        }
    }

    /// isConfigured = 当前选中通道凭据齐全（飞书 appId+secret / TG token+chatId；none 视为不齐）。
    /// iOS 无激活连接驱动（无 IM 长连接生命周期可观察，isConnected 快照无来源）→
    /// Android 三态在此二态退化：凭据不齐 → not_configured；凭据齐全 → disconnected
    /// （connected 分支不可达，已登记平台差异台账）。
    private var isConfigured: Bool {
        switch channelType {
        case "feishu": return !feishuAppId.isEmpty && !feishuAppSecret.isEmpty
        case "telegram": return !telegramBotToken.isEmpty && !telegramChatId.isEmpty
        default: return false
        }
    }

    private var statusText: String {
        isConfigured ? L("Disconnected") : L("Not configured")
    }

    // MARK: 组2 飞书（双常驻，不随选中通道显隐）

    private var feishuGroup: some View {
        SettingsListGroup(
            title: L("Feishu"),
            desc: L("Connect to Feishu (Lark) to receive remote commands via IM messages.")
        ) {
            VStack(spacing: Spacing.sm) {
                CredentialField(title: L("App ID"), text: $feishuAppId, placeholder: L("Feishu App ID"))
                CredentialField(title: L("App Secret"), text: $feishuAppSecret, placeholder: L("Feishu App Secret"), isPassword: true)
            }
            .padding(.horizontal, SettingsTokens.listRowPaddingH)
            .padding(.vertical, Spacing.sm)
        }
    }

    // MARK: 组3 Telegram（双常驻，不随选中通道显隐）

    private var telegramGroup: some View {
        SettingsListGroup(
            title: L("Telegram"),
            desc: L("Connect via Telegram Bot long polling (no public IP needed).")
        ) {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                CredentialField(title: L("Bot Token"), text: $telegramBotToken, placeholder: L("123456:ABC-DEF..."), isPassword: true)
                CredentialField(title: L("Allowed Chat ID"), text: $telegramChatId, placeholder: L("e.g. 123456789"))
                Text(L("Create a bot via @BotFather and paste its token."))
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.onSurfaceVariant.opacity(0.7))
                Text(L("Only this chat can send commands (security whitelist)."))
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.onSurfaceVariant.opacity(0.7))
                Text(L("Without an Allowed Chat ID, the bot rejects all messages for safety."))
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.error)
            }
            .padding(.horizontal, SettingsTokens.listRowPaddingH)
            .padding(.vertical, Spacing.sm)
        }
    }
}

// MARK: - Memory Facts View

struct MemoryFactsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var facts: [String] = []
    @State private var showClearConfirm = false

    var body: some View {
        Group {
            if facts.isEmpty {
                VStack(spacing: 12) {
                    Image(matIcon: "psychology").font(.system(size: 48)).foregroundColor(.secondary.opacity(0.3))
                    Text(String(localized: "No memories yet. Tell Xiaolang \"remember...\" in chat to add."))
                        .font(.system(size: 14)).foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(facts, id: \.self) { fact in
                        Text(fact)
                    }
                    .onDelete { _ in }
                }
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("AI Memory"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !facts.isEmpty {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L("Clear All")) { showClearConfirm = true }
                        .foregroundColor(.red)
                }
            }
        }
        .confirmationDialog(L("Clear all memories?"), isPresented: $showClearConfirm, titleVisibility: .visible) {
            Button(L("Clear"), role: .destructive) { facts = [] }
            Button(L("Cancel"), role: .cancel) {}
        }
    }
}

// MARK: - Developer Settings View

/// 开发者选项分类页（spec settings.yaml §6.5 developer，2026-10-02 S3d 行式重构）。
/// 组1 相机预览调试五行常显（warningAmber 行式图标块，不随 debug 总开关折叠；
/// shader_debug_mode = 行+右值 → 单选弹层）；TAG Generation Engine 组（tag_gen_use_opencl）
/// iOS 无 OpenCL 概念整组省略（spec §6.5 已登记台账，iOS 推理走 MNN CPU/ORT）；
/// 组2 诊断与日志（LLM 日志导航 + log_modules 行+右值 → 底部多选 sheet）；
/// 组3 开发测试工具仅 DEBUG 构建。
struct DeveloperSettingsView: View {
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    @AppStorage("debug_ui_enabled") private var debugEnabled = false
    @AppStorage("show_camera_info_in_preview") private var showCameraInfo = true
    @AppStorage("show_face_debug_overlay") private var showFaceDebug = true
    @AppStorage("show_log_overlay") private var showLogOverlay = true
    @AppStorage("debug_shader_mode") private var debugShaderMode = 0

    /// Log Modules 多选（spec §6.5 log_modules）：UserDefaults `log_module_config`
    /// JSON `{"enabledModules":[...]}`，与 Android/`IosModuleGatedLogger` 同构消费。
    /// 成员为枚举名大写下划线；key 缺失时默认 AGENT/ORCHESTRATOR/DOWNLOAD/SETTINGS/CHAT/SEMANTIC。
    struct LogModule: Identifiable {
        let rawValue: String   // 枚举名（持久化值）
        let displayName: String
        var id: String { rawValue }
    }

    static let logModules: [LogModule] = [
        .init(rawValue: "FACE_DETECTION", displayName: "Face Detection"),
        .init(rawValue: "RENDERING", displayName: "Rendering"),
        .init(rawValue: "BEAUTY", displayName: "Beauty"),
        .init(rawValue: "AGENT", displayName: "Agent"),
        .init(rawValue: "CAMERA", displayName: "Camera"),
        .init(rawValue: "DOWNLOAD", displayName: "Download"),
        .init(rawValue: "SETTINGS", displayName: "Settings"),
        .init(rawValue: "ORCHESTRATOR", displayName: "Orchestrator"),
        .init(rawValue: "CHAT", displayName: "Chat"),
        .init(rawValue: "SEMANTIC", displayName: "Semantic Search"),
    ]

    static let defaultEnabledModules: Set<String> = [
        "AGENT", "ORCHESTRATOR", "DOWNLOAD", "SETTINGS", "CHAT", "SEMANTIC",
    ]

    @State private var enabledModules: Set<String> = Self.defaultEnabledModules
    @State private var showShaderDialog = false
    @State private var showLogModulesSheet = false

    /// Shader Debug Mode 选项（spec §6.5：Normal/Skin Mask/Warp Offset/BigEye Radius/
    /// ThinFace Radius/All Warp 对应 Int 0-5；双端预览均不消费，persistence-only）。
    private static let shaderModes: [String] = [
        "Normal", "Skin Mask", "Warp Offset", "BigEye Radius", "ThinFace Radius", "All Warp"
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: SettingsTokens.listSectionSpacing) {
                cameraPreviewDebugGroup
                diagnosticsGroup
                #if DEBUG
                developerToolsGroup
                #endif
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Developer Options"))
        .navigationBarTitleDisplayMode(.inline)
        // shader_debug_mode 单选弹层（spec §6.5 row_value_dialog）
        .confirmationDialog(L("Shader Debug Mode"), isPresented: $showShaderDialog, titleVisibility: .visible) {
            ForEach(0..<Self.shaderModes.count, id: \.self) { mode in
                Button(L(Self.shaderModes[mode])) { debugShaderMode = mode }
            }
            Button(L("Cancel"), role: .cancel) {}
        }
        // log_modules 底部多选弹层（spec §6.5 row_value_sheet）
        .sheet(isPresented: $showLogModulesSheet) {
            LogModulesSheet(enabledModules: $enabledModules, onToggle: toggleModule)
        }
        .onAppear { enabledModules = Self.loadEnabledModules() }
    }

    // MARK: 组1 相机预览调试（五行常显，行式图标块 warningAmber）

    private var cameraPreviewDebugGroup: some View {
        SettingsListGroup(
            title: L("Camera Preview Debug"),
            desc: L("Recommended for debugging only.")
        ) {
            SettingsIconToggleRow(
                title: L("Debug"),
                icon: .mat("bug_report"),
                iconBlock: .warningAmber,
                isOn: $debugEnabled
            )
            SettingsListDivider()
            SettingsIconToggleRow(
                title: L("Camera Info"),
                icon: .mat("camera_alt"),
                iconBlock: .warningAmber,
                isOn: $showCameraInfo
            )
            SettingsListDivider()
            SettingsIconToggleRow(
                title: L("Face Debug Overlay"),
                icon: .mat("mat_face"),
                iconBlock: .warningAmber,
                isOn: $showFaceDebug
            )
            SettingsListDivider()
            SettingsIconToggleRow(
                title: L("Log Overlay"),
                icon: .mat("mat_text_snippet"),
                iconBlock: .warningAmber,
                isOn: $showLogOverlay
            )
            SettingsListDivider()
            SettingsListRow(
                title: L("Shader Debug Mode"),
                valueText: L(Self.shaderModes[min(max(debugShaderMode, 0), Self.shaderModes.count - 1)]),
                icon: .mat("mat_tune"),
                iconBlock: .warningAmber,
                action: { showShaderDialog = true }
            )
            SettingsListFootnote(text: L("Stored only; preview rendering does not consume this setting yet."))
        }
    }

    // MARK: 组2 诊断与日志

    private var diagnosticsGroup: some View {
        SettingsListGroup(title: L("Diagnostics & Logs")) {
            NavigationLink {
                DiagnosticLogView()
            } label: {
                SettingsListRow(
                    title: L("LLM Call Log"),
                    subtitle: L("View LLM inference, tool call and JS run logs"),
                    showChevron: true
                )
            }
            .buttonStyle(.plain)
            SettingsListDivider()
            SettingsListRow(
                title: L("Log Modules"),
                valueText: logModulesValue,
                action: { showLogModulesSheet = true }
            )
            SettingsListFootnote(text: L("Only gates Agent (Kotlin-side) log output."))
        }
    }

    /// 全部开启 → log_modules_all 文案；否则 "N/10"（spec §6.5 log_modules 右值）。
    private var logModulesValue: String {
        enabledModules.count == Self.logModules.count
            ? L("All")
            : "\(enabledModules.count)/\(Self.logModules.count)"
    }

    // MARK: 组3 开发测试工具（仅 DEBUG）

    #if DEBUG
    private var developerToolsGroup: some View {
        SettingsListGroup(title: L("Developer Tools")) {
            NavigationLink {
                DebugScreenView()
            } label: {
                SettingsListRow(title: L("Image Download"), showChevron: true)
            }
            .buttonStyle(.plain)
            SettingsListDivider()
            androidOnlyRow(L("Search Test"))
            SettingsListDivider()
            androidOnlyRow(L("JSBridge"))
            SettingsListDivider()
            androidOnlyRow(L("Accessibility Service"))
        }
    }

    /// Android 专属开发工具行：灰显 + 右值 "Android only"（spec §6.5 ios_state: disabled_android_only）。
    private func androidOnlyRow(_ title: String) -> some View {
        SettingsListRow(title: title, valueText: L("Android only"))
            .modifier(GrayedOut())
    }
    #endif

    // MARK: log_module_config 持久化（JSON 结构与 Android 同构，键不变）

    /// 读取 UserDefaults `log_module_config`；key 缺失/解析失败 → 默认启用集。
    static func loadEnabledModules() -> Set<String> {
        guard let raw = UserDefaults.standard.string(forKey: "log_module_config"),
              let data = raw.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(ModuleConfig.self, from: data) else {
            return defaultEnabledModules
        }
        return Set(decoded.enabledModules)
    }

    /// 与 Android `toJson()` 同构：`{"enabledModules":[...]}`（保持插入序稳定）。
    static func persistEnabledModules(_ enabled: Set<String>) {
        let ordered = logModules.map(\.rawValue).filter { enabled.contains($0) }
        let payload = ["enabledModules": ordered]
        if let data = try? JSONSerialization.data(withJSONObject: payload),
           let json = String(data: data, encoding: .utf8) {
            UserDefaults.standard.set(json, forKey: "log_module_config")
        }
    }

    private func toggleModule(_ rawValue: String) {
        if enabledModules.contains(rawValue) {
            enabledModules.remove(rawValue)
        } else {
            enabledModules.insert(rawValue)
        }
        Self.persistEnabledModules(enabledModules)
    }

    struct ModuleConfig: Codable {
        let enabledModules: [String]
    }

    /// Log Modules 底部多选弹层（spec §6.5 sheet：标题 log_management + 副题
    /// log_modules_dialog_subtitle + 10 模块多选行；切换即时生效并持久化）。
    private struct LogModulesSheet: View {
        @Binding var enabledModules: Set<String>
        let onToggle: (String) -> Void
        @Environment(\.dismiss) private var dismiss
        @Environment(\.colorScheme) private var cs
        private var s: SchemeColors { appScheme(cs) }

        var body: some View {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("Module Logs"))
                        .font(AppTypography.titleMedium.font)
                        .foregroundColor(s.onSurface)
                    Text(L("Toggle per-module logging"))
                        .font(AppTypography.bodySmall.font)
                        .foregroundColor(s.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Spacing.lg)
                .padding(.top, Spacing.lg)
                .padding(.bottom, Spacing.sm)

                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(DeveloperSettingsView.logModules.enumerated()), id: \.element.id) { index, module in
                            moduleRow(module)
                            if index < DeveloperSettingsView.logModules.count - 1 {
                                SettingsM3Divider()
                            }
                        }
                    }
                    .padding(.horizontal, Spacing.lg)
                    .padding(.bottom, Spacing.lg)
                }
            }
            .background(s.surfaceContainerLow)
            .presentationDetents([.medium, .large])
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Done")) { dismiss() }
                }
            }
        }

        private func moduleRow(_ module: DeveloperSettingsView.LogModule) -> some View {
            let selected = enabledModules.contains(module.rawValue)
            return Button {
                onToggle(module.rawValue)
            } label: {
                HStack {
                    Text(L(module.displayName))
                        .font(AppTypography.bodyMedium.font)
                        .foregroundColor(s.onSurface)
                    Spacer()
                    if selected {
                        Image(matIcon: "check")
                            .font(.system(size: 18))
                            .foregroundColor(s.primary)
                    }
                }
                .frame(minHeight: SettingsTokens.dialogOptionRowHeight)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - M3 设置原语（对齐 Android SettingsBaseComponents，消费 DesignTokens）

/// Android SettingsSection：surfaceContainerHighest 卡片 + titleSmall/bodySmall + 尾部分隔线。
struct SettingsM3Section<Content: View>: View {
    let title: String
    var desc: String? = nil
    @Environment(\.colorScheme) private var cs
    @ViewBuilder let content: Content

    var body: some View {
        let s = appScheme(cs)
        VStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(AppTypography.titleSmall.font)
                    .foregroundColor(s.onSurface)
                if let desc {
                    Text(desc)
                        .font(AppTypography.bodySmall.font)
                        .foregroundColor(s.onSurfaceVariant)
                }
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, SettingsTokens.sectionPaddingH)
            .padding(.vertical, SettingsTokens.sectionPaddingV)
            .background(s.surfaceContainerHighest)
            .clipShape(AppShapes.card)
            SettingsM3Divider()
        }
    }
}

/// Android SettingsClickableRow：高 56/64 + leading icon 24 + 标题/副标题 + value + chevron。
struct SettingsM3Row: View {
    let title: String
    var subtitle: String? = nil
    var valueText: String? = nil
    var leadingIcon: String? = nil
    var action: () -> Void
    @Environment(\.colorScheme) private var cs
    var body: some View {
        let s = appScheme(cs)
        Button(action: action) {
            HStack(spacing: SettingsTokens.rowElementGap) {
                if let leadingIcon {
                    Image(matIcon: leadingIcon)
                        .font(.system(size: SettingsTokens.rowLeadingIconSize))
                        .foregroundColor(s.primary)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(AppTypography.bodyMedium.font).foregroundColor(s.onSurface)
                    if let subtitle {
                        Text(subtitle).font(AppTypography.bodySmall.font).foregroundColor(s.onSurfaceVariant)
                    }
                }
                Spacer()
                if let valueText {
                    Text(valueText).font(AppTypography.bodySmall.font).foregroundColor(s.primary)
                }
                Image(matIcon: "arrow_forward")
                    .font(.system(size: SettingsTokens.rowChevronSize))
                    .foregroundColor(s.onSurfaceVariant.opacity(SettingsTokens.rowChevronAlpha))
            }
            .frame(minHeight: subtitle == nil ? SettingsTokens.rowHeightNoSubtitle : SettingsTokens.rowHeightWithSubtitle)
            .padding(.horizontal, SettingsTokens.rowPaddingH)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Android HorizontalDivider（outlineVariant 绑定，双 mode；alpha 分档 0.3/0.5/0.6，默认 0.6）。
/// 渲染注意：勿用 Divider 加 background 修饰——色垫在发丝线后、separator 材质仍在上层（实际显色=两者叠加，
/// 双 mode 色值不纯）；此处 Rectangle 显式绘制，outlineVariant（浅 #CDC7BC / 深 #46413A）直接生效。
struct SettingsM3Divider: View {
    @Environment(\.colorScheme) private var cs
    var alpha: Double = SettingsTokens.rowChevronAlpha

    var body: some View {
        Rectangle()
            .fill(appScheme(cs).outlineVariant.opacity(alpha))
            .frame(height: 0.5)
    }
}

/// Android FilterChip（selected = primary/onPrimary）。
struct SettingsM3Chip: View {
    let label: String
    let isSelected: Bool
    var isDisabled: Bool = false
    var action: () -> Void
    @Environment(\.colorScheme) private var cs
    var body: some View {
        let s = appScheme(cs)
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? s.onPrimary : (isDisabled ? s.onSurfaceVariant.opacity(0.5) : s.onSurface))
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(isSelected ? s.primary : s.surfaceContainerHigh)
                .clipShape(Capsule())
        }
        .disabled(isDisabled)
    }
}

// MARK: - 主菜单列表原语（spec settings.yaml §2 list_sections，对齐 Android SettingsListSection/Row/Divider）

/// 行图标块配色（vibrant* 为固定功能色，容器色/状态色走语义通道；前景一律反色）。
enum SettingsListIconBlock {
    case vibrantGreen, vibrantBlue, vibrantOrange, vibrantPink, vibrantPurple
    case primaryContainer
    case statusInfo
    case warningAmber

    func colors(_ s: SchemeColors) -> (bg: Color, fg: Color) {
        switch self {
        case .vibrantGreen: return (AppColors.vibrantGreen, AppColors.iconOnVibrant)
        case .vibrantBlue: return (AppColors.vibrantBlue, AppColors.iconOnVibrant)
        case .vibrantOrange: return (AppColors.vibrantOrange, AppColors.iconOnVibrant)
        case .vibrantPink: return (AppColors.vibrantPink, AppColors.iconOnVibrant)
        case .vibrantPurple: return (AppColors.vibrantPurple, AppColors.iconOnVibrant)
        case .primaryContainer: return (s.primaryContainer, s.onPrimaryContainer)
        case .statusInfo: return (StatusColor.info, AppColors.iconOnVibrant)
        case .warningAmber: return (StatusColor.warningAmber, AppColors.iconOnVibrant)
        }
    }
}

/// 行图标：Material 资产（mat_*）优先；资产缺失时 SF Symbol 兜底（图标字形属平台原生差异层）。
enum SettingsListIcon {
    case mat(String)
    case sf(String)

    @ViewBuilder
    func image(size: CGFloat) -> some View {
        switch self {
        case .mat(let name):
            MatIcon(name: name, size: size)
        case .sf(let name):
            Image(systemName: name)
                .font(.system(size: size, weight: .medium))
        }
    }
}

/// Android SettingsListRow：高 listRowHeight（带副标题 rowHeightWithSubtitle）+
/// 圆形彩色图标块 + 标题 + 可选右值（当前选中）+ 可选 chevron。
/// action 为 nil 时渲染纯内容（供 NavigationLink 包裹）。
struct SettingsListRow: View {
    let title: String
    var subtitle: String? = nil
    var valueText: String? = nil
    var icon: SettingsListIcon? = nil
    var iconBlock: SettingsListIconBlock? = nil
    var showChevron: Bool = false
    var action: (() -> Void)? = nil
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        let content = HStack(spacing: SettingsTokens.rowElementGap) {
            leadingIcon(s)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: SettingsTokens.listTitleFontSize))
                    .foregroundColor(s.onSurface)
                if let subtitle {
                    Text(subtitle)
                        .font(AppTypography.bodySmall.font)
                        .foregroundColor(s.onSurfaceVariant)
                }
            }
            Spacer()
            if let valueText {
                Text(valueText)
                    .font(.system(size: SettingsTokens.listValueFontSize))
                    .foregroundColor(s.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: SettingsTokens.listValueMaxWidth, alignment: .trailing)
            }
            if showChevron {
                Image(matIcon: "arrow_forward")
                    .font(.system(size: SettingsTokens.rowChevronSize))
                    .foregroundColor(s.onSurfaceVariant.opacity(SettingsTokens.rowChevronAlpha))
            }
        }
        .frame(minHeight: subtitle == nil ? SettingsTokens.listRowHeight : SettingsTokens.rowHeightWithSubtitle)
        .padding(.horizontal, SettingsTokens.listRowPaddingH)
        .contentShape(Rectangle())

        if let action {
            Button(action: action) { content }.buttonStyle(.plain)
        } else {
            content
        }
    }

    /// 有图标块 → 块内 inner 尺寸反色图标；无块 → 主色裸图标（SettingsClickableRow 形态）。
    @ViewBuilder
    private func leadingIcon(_ s: SchemeColors) -> some View {
        if let icon, let iconBlock {
            let c = iconBlock.colors(s)
            icon.image(size: SettingsTokens.listIconInnerSize)
                .foregroundColor(c.fg)
                .frame(width: SettingsTokens.listIconBlockSize, height: SettingsTokens.listIconBlockSize)
                .background(Circle().fill(c.bg))
        } else if let icon {
            icon.image(size: SettingsTokens.rowLeadingIconSize)
                .foregroundColor(s.primary)
        }
    }
}

/// Android SettingsListSection：surfaceContainerHighest 卡片（组标题按 Android 惯例缺省），
/// 组内行间分隔线由调用方插入 SettingsListDivider。
struct SettingsListSection<Content: View>: View {
    @ViewBuilder let content: Content
    @Environment(\.colorScheme) private var cs

    var body: some View {
        VStack(spacing: 0) { content }
            .background(appScheme(cs).surfaceContainerHighest)
            .clipShape(AppShapes.panel)
    }
}

/// Android SettingsListDivider：组内行间分隔线，起始缩进与图标块列对齐。
struct SettingsListDivider: View {
    var body: some View {
        SettingsM3Divider()
            .padding(.leading, SettingsTokens.listDividerInsetStart)
    }
}

/// 凭据输入行（spec §3f channels 字段）：label bodySmall + 内凹浅色底输入框（surfaceContainerHigh、
/// 圆角 small、无指示线）；密码字段 SecureField 掩码。
private struct CredentialField: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    var isPassword: Bool = false
    @Environment(\.colorScheme) private var cs

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(AppTypography.bodySmall.font)
                .foregroundColor(appScheme(cs).onSurfaceVariant)
            Group {
                if isPassword {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text)
                }
            }
            .font(AppTypography.bodyMedium.font)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(10)
            .background(appScheme(cs).surfaceContainerHigh)
            .clipShape(AppShapes.small)
        }
    }
}

// MARK: - 行式分组构件（S3d：开发者/通道页组内共用，对齐 S3c group/footnote 模式）

/// 组 = 组标题（+可选组描述）+ SettingsListSection 卡片（组内行间分隔线由调用方插入）。
private struct SettingsListGroup<Content: View>: View {
    let title: String
    var desc: String? = nil
    @ViewBuilder let content: Content
    @Environment(\.colorScheme) private var cs

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text(title)
                .font(AppTypography.titleSmall.font)
                .foregroundColor(appScheme(cs).onSurface)
            if let desc {
                Text(desc)
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(appScheme(cs).onSurfaceVariant)
            }
            SettingsListSection { content }
        }
    }
}

/// 行式开关行：图标块 + 标题(+副题) + Switch，量规对齐 SettingsListRow。
private struct SettingsIconToggleRow: View {
    let title: String
    var subtitle: String? = nil
    let icon: SettingsListIcon
    let iconBlock: SettingsListIconBlock
    @Binding var isOn: Bool
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        HStack(spacing: SettingsTokens.rowElementGap) {
            icon.image(size: SettingsTokens.listIconInnerSize)
                .foregroundColor(iconBlock.colors(s).fg)
                .frame(width: SettingsTokens.listIconBlockSize, height: SettingsTokens.listIconBlockSize)
                .background(Circle().fill(iconBlock.colors(s).bg))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: SettingsTokens.listTitleFontSize))
                    .foregroundColor(s.onSurface)
                if let subtitle {
                    Text(subtitle)
                        .font(AppTypography.bodySmall.font)
                        .foregroundColor(s.onSurfaceVariant)
                }
            }
            Spacer()
            Toggle("", isOn: $isOn).labelsHidden()
        }
        .frame(minHeight: subtitle == nil ? SettingsTokens.listRowHeight : SettingsTokens.rowHeightWithSubtitle)
        .padding(.horizontal, SettingsTokens.listRowPaddingH)
        .contentShape(Rectangle())
    }
}

/// 组内脚注：bodySmall onSurfaceVariant 0.7。
private struct SettingsListFootnote: View {
    let text: String
    @Environment(\.colorScheme) private var cs

    var body: some View {
        Text(text)
            .font(AppTypography.bodySmall.font)
            .foregroundColor(appScheme(cs).onSurfaceVariant.opacity(0.7))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, SettingsTokens.listRowPaddingH)
            .padding(.vertical, Spacing.sm)
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
