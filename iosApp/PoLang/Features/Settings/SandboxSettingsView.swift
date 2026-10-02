import SwiftUI
import AVFoundation
import Photos

// MARK: - 沙盒与权限分类页（2026-10-02 S3c 自 SettingsScreen 抽取）

/// 沙盒与权限分类页（spec settings.yaml §3e sandbox，三组行式：智能体执行/设备访问/语音）。
/// iOS 平台现实：执行/访问开关 persistence-only（键名沿用既有实现）、silent_trash 整行省略
/// （spec ios: not_applicable）、无语音引擎 → 语音组整组灰显注「后续版本」（spec §3e ios_note）。
struct SandboxSettingsView: View {
    @Environment(\.colorScheme) private var cs
    @Environment(\.scenePhase) private var scenePhase
    private var s: SchemeColors { appScheme(cs) }

    // 组1 智能体执行（软开关，能力层消费待接入——persistence-only）
    @AppStorage("auto_execute_plans") private var autoExecute: Bool = true
    @AppStorage("js_engine_enabled") private var jsEngine: Bool = true
    // 组2 设备访问软开关（persistence-only，键名沿用既有实现）
    @AppStorage("agent_camera_access_enabled") private var cameraAccess: Bool = true
    @AppStorage("agent_gallery_access_enabled") private var galleryAccess: Bool = true
    // 组3 语音（iOS 无引擎，灰显占位；键保留与 Android 对齐的持久化）
    @AppStorage("voice_entry_enabled") private var voiceEntry: Bool = false
    @AppStorage("ai_chat_entry_enabled") private var aiChatEntry: Bool = false

    // 组2 系统权限态（AVFoundation/Photos 实时读取，onAppear/回前台刷新；仅作状态点不占文案键）
    @State private var cameraStatus = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var galleryStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: SettingsTokens.listSectionSpacing) {
                executionGroup
                deviceAccessGroup
                voiceGroup
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Sandbox & Permissions"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: refreshPermissions)
        // 自系统设置返回（inactive → active）时刷新权限状态点
        .onChange(of: scenePhase) { phase in
            if phase == .active { refreshPermissions() }
        }
    }

    // MARK: - 组1 智能体执行（agent_execution）

    private var executionGroup: some View {
        group(
            title: L("Execution"),
            desc: L("Control what the Agent can run autonomously.")
        ) {
            listToggleRow(
                title: L("Auto-Execute Plans"),
                subtitle: L("When disabled, Agent requires confirmation before executing multi-step plans."),
                icon: .mat("psychology"),
                iconBlock: .vibrantPurple,
                isOn: $autoExecute
            )
            SettingsListDivider()
            listToggleRow(
                title: L("JS Engine"),
                subtitle: L("Allow the Agent to execute JS sandbox scripts."),
                icon: .sf("chevron.left.forwardslash.chevron.right"),
                iconBlock: .vibrantPurple,
                isOn: $jsEngine
            )
        }
    }

    // MARK: - 组2 设备访问（device_access；silent_trash iOS not_applicable 整行省略）

    private var deviceAccessGroup: some View {
        group(
            title: L("Device Access"),
            desc: L("Control which device capabilities the Agent may use.")
        ) {
            listToggleRow(
                title: L("Camera Access"),
                icon: .mat("camera_alt"),
                iconBlock: .vibrantBlue,
                isOn: $cameraAccess
            )
            SettingsListDivider()
            systemPermissionRow(
                title: L("Camera Permission (System)"),
                status: SystemPermissionStatus(camera: cameraStatus)
            )
            SettingsListDivider()
            listToggleRow(
                title: L("Gallery Access"),
                icon: .mat("photo_library"),
                iconBlock: .vibrantBlue,
                isOn: $galleryAccess
            )
            SettingsListDivider()
            systemPermissionRow(
                title: L("Gallery Permission (System)"),
                status: SystemPermissionStatus(gallery: galleryStatus)
            )
        }
    }

    // MARK: - 组3 语音（voice，iOS 无引擎 → 整组灰显注「后续版本」，voice_mode 行不弹层）

    private var voiceGroup: some View {
        group(title: L("Voice")) {
            SettingsListRow(
                title: L("Voice Control"),
                valueText: L("Coming Soon"),
                icon: .sf("mic.fill"),
                iconBlock: .vibrantGreen
            )
            .modifier(GrayedOut())
            SettingsListDivider()
            listToggleRow(
                title: L("Voice Control Entry"),
                icon: .mat("keyboard_voice"),
                iconBlock: .vibrantGreen,
                isOn: $voiceEntry
            )
            .modifier(GrayedOut())
            SettingsListDivider()
            listToggleRow(
                title: L("Camera AI Chat Entry"),
                icon: .mat("smart_toy"),
                iconBlock: .vibrantGreen,
                isOn: $aiChatEntry
            )
            .modifier(GrayedOut())
            footnote(L("On-device voice recognition (ASR) and wake word (KWS) are not yet implemented on iOS."))
        }
    }

    // MARK: - 构件

    /// 系统权限行：标题 + 权限状态点（副题行）+ 右值「系统设置」，点击跳系统设置 App 页。
    private func systemPermissionRow(title: String, status: SystemPermissionStatus) -> some View {
        Button {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        } label: {
            HStack(spacing: SettingsTokens.rowElementGap) {
                SettingsListIcon.mat("lock").image(size: SettingsTokens.listIconInnerSize)
                    .foregroundColor(SettingsListIconBlock.vibrantBlue.colors(s).fg)
                    .frame(width: SettingsTokens.listIconBlockSize, height: SettingsTokens.listIconBlockSize)
                    .background(Circle().fill(SettingsListIconBlock.vibrantBlue.colors(s).bg))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: SettingsTokens.listTitleFontSize))
                        .foregroundColor(s.onSurface)
                    HStack(spacing: 6) {
                        Circle()
                            .fill(status.dotColor)
                            .frame(width: 7, height: 7)
                        Text(L("Open system app settings to manage permissions."))
                            .font(AppTypography.bodySmall.font)
                            .foregroundColor(s.onSurfaceVariant)
                    }
                }
                Spacer()
                Text(L("System Settings"))
                    .font(.system(size: SettingsTokens.listValueFontSize))
                    .foregroundColor(s.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: SettingsTokens.listValueMaxWidth, alignment: .trailing)
            }
            .frame(minHeight: SettingsTokens.rowHeightWithSubtitle)
            .padding(.horizontal, SettingsTokens.listRowPaddingH)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// 组 = 组标题（+可选组描述）+ SettingsListSection 卡片（组内行间分隔线由调用方插入）。
    private func group<Content: View>(
        title: String,
        desc: String? = nil,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text(title)
                .font(AppTypography.titleSmall.font)
                .foregroundColor(s.onSurface)
            if let desc {
                Text(desc)
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.onSurfaceVariant)
            }
            SettingsListSection { content() }
        }
    }

    /// 开关行（spec 行式：图标块 + 标题(+副题) + Switch，对齐 SettingsListRow 布局量规）。
    private func listToggleRow(
        title: String,
        subtitle: String? = nil,
        icon: SettingsListIcon,
        iconBlock: SettingsListIconBlock,
        isOn: Binding<Bool>
    ) -> some View {
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
            Toggle("", isOn: isOn).labelsHidden()
        }
        .frame(minHeight: subtitle == nil ? SettingsTokens.listRowHeight : SettingsTokens.rowHeightWithSubtitle)
        .padding(.horizontal, SettingsTokens.listRowPaddingH)
        .contentShape(Rectangle())
    }

    private func footnote(_ text: String) -> some View {
        Text(text)
            .font(AppTypography.bodySmall.font)
            .foregroundColor(s.onSurfaceVariant.opacity(0.7))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, SettingsTokens.listRowPaddingH)
            .padding(.vertical, Spacing.sm)
    }

    private func refreshPermissions() {
        cameraStatus = AVCaptureDevice.authorizationStatus(for: .video)
        galleryStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    }
}

/// 系统权限状态点（绿=已授权 / 琥珀=受限 / 红=拒绝 / 灰=未决定）。
private enum SystemPermissionStatus {
    case granted, limited, denied, notDetermined

    init(camera: AVAuthorizationStatus) {
        switch camera {
        case .authorized: self = .granted
        case .notDetermined: self = .notDetermined
        default: self = .denied
        }
    }

    init(gallery: PHAuthorizationStatus) {
        switch gallery {
        case .authorized: self = .granted
        case .limited: self = .limited
        case .notDetermined: self = .notDetermined
        default: self = .denied
        }
    }

    var dotColor: Color {
        switch self {
        case .granted: return StatusColor.success
        case .limited: return StatusColor.warningAmber
        case .denied: return StatusColor.error
        case .notDetermined: return Color(UIColor.systemGray3)
        }
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
        SandboxSettingsView()
    }
}
