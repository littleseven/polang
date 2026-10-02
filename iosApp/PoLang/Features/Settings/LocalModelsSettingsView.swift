import SwiftUI

// MARK: - 本地模型分类页（2026-10-02 S3c 自 SettingsScreen 抽取）

/// 本地模型分类页（spec settings.yaml §3b local_model，三组行式：检测模型/检测策略/语音）。
/// iOS 平台现实：检测管线为 MNN/MediaPipe 二选一（CPU 单管线）、关键点模型随引擎耦合、
/// 无语音引擎——结构先对齐 Android，能力缺口用灰显 + 注记（spec §3b ios_note / §9 台账）。
struct LocalModelsSettingsView: View {
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    // 组1 检测模型——iOS 实有引擎单选（MNN=RetinaFace+2d106 / MediaPipe），真接消费端 CameraPreviewView
    @AppStorage("camera_use_mnn") private var useMnn: Bool = true
    // 照片打标模型（iOS 仅 Florence-2；auto 与 florence2_base 等价，persistence-only）
    @AppStorage("tagger_model_key") private var taggerModelKey: String = "auto"
    // 组2 检测策略——iOS 检测链路暂无消费端（persistence-only，行下注记）
    @AppStorage("face_landmark_mode") private var landmarkMode: Bool = true
    @AppStorage("adaptive_face_detection_interval") private var adaptiveInterval: Bool = true
    @AppStorage("face_detect_interval_profile") private var intervalProfile: String = "BALANCED"

    @State private var showEngineDialog = false
    @State private var showTaggerDialog = false
    @State private var showProfileDialog = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: SettingsTokens.listSectionSpacing) {
                detectionModelsGroup
                detectionStrategyGroup
                voiceGroup
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Local Models"))
        .navigationBarTitleDisplayMode(.inline)
        // 检测引擎单选（roi_stage_dialog 的 iOS 实有形态：弹层仅 MNN/MediaPipe 两实选项）
        .confirmationDialog(L("ROI Detection"), isPresented: $showEngineDialog, titleVisibility: .visible) {
            Button(L("MNN")) { useMnn = true }
            Button(L("MediaPipe")) { useMnn = false }
            Button(L("Cancel"), role: .cancel) {}
        }
        // 打标模型单选（tagger_choice_dialog；Qwen3-VL iOS 不可用 → 选项裁剪，行下注记）
        .confirmationDialog(L("Photo Tagging"), isPresented: $showTaggerDialog, titleVisibility: .visible) {
            Button(L("Florence-2 preferred")) { taggerModelKey = "auto" }
            Button(L("Florence-2")) { taggerModelKey = "florence2_base" }
            Button(L("Cancel"), role: .cancel) {}
        }
        // 动态间隔档位单选（profile_choice_dialog，persistence-only）
        .confirmationDialog(L("Adaptive interval profile"), isPresented: $showProfileDialog, titleVisibility: .visible) {
            Button(L("Conservative")) { intervalProfile = "CONSERVATIVE" }
            Button(L("Balanced")) { intervalProfile = "BALANCED" }
            Button(L("Aggressive")) { intervalProfile = "AGGRESSIVE" }
            Button(L("Cancel"), role: .cancel) {}
        }
    }

    // MARK: - 组1 检测模型（detection_models）

    private var detectionModelsGroup: some View {
        group(
            title: L("Detection Models"),
            desc: L("Choose face detection models and device; tune landmarks and cadence.")
        ) {
            SettingsListRow(
                title: L("ROI Detection"),
                valueText: useMnn ? L("RetinaFace") : L("MediaPipe"),
                icon: .mat("mat_face"),
                iconBlock: .vibrantBlue,
                action: { showEngineDialog = true }
            )
            SettingsListDivider()
            // 关键点模型 iOS 随引擎耦合（MNN→2d106 / MediaPipe→468pt），无独立单选 → 灰显注记
            SettingsListRow(
                title: L("Landmark Detection"),
                valueText: useMnn ? L("MNN 2d106") : L("MediaPipe 468pt"),
                icon: .sf("scope"),
                iconBlock: .vibrantPink
            )
            .modifier(GrayedOut())
            SettingsListDivider()
            // 打标模型（tagger）：auto=Florence-2 优先 / 固定 Florence-2 单选弹层（Qwen3-VL iOS 不可用已裁剪）
            SettingsListRow(
                title: L("Photo Tagging"),
                valueText: taggerModelKey == "auto" ? L("Florence-2 preferred") : L("Florence-2"),
                icon: .mat("mat_sell"),
                iconBlock: .vibrantOrange,
                action: { showTaggerDialog = true }
            )
            footnote(L("On iOS the landmark model follows the detection engine (MNN 2d106 / MediaPipe 468pt) and cannot be selected separately."))
        }
    }

    // MARK: - 组2 检测策略（detection_strategy，persistence-only）

    private var detectionStrategyGroup: some View {
        group(title: L("Detection Strategy")) {
            listToggleRow(
                title: L("Face landmark mode"),
                icon: .sf("gauge"),
                iconBlock: .vibrantBlue,
                isOn: $landmarkMode
            )
            SettingsListDivider()
            listToggleRow(
                title: L("Adaptive face detect interval"),
                icon: .sf("timer"),
                iconBlock: .vibrantBlue,
                isOn: $adaptiveInterval
            )
            if adaptiveInterval {
                SettingsListDivider()
                SettingsListRow(
                    title: L("Adaptive interval profile"),
                    valueText: profileLabel,
                    icon: .mat("mat_tune"),
                    iconBlock: .vibrantBlue,
                    action: { showProfileDialog = true }
                )
            }
            footnote(L("These switches are saved but not yet consumed by the iOS detection pipeline."))
        }
    }

    // MARK: - 组3 语音（voice，iOS 无引擎 → 整组灰显注「后续版本」）

    private var voiceGroup: some View {
        group(title: L("Voice")) {
            SettingsListRow(
                title: L("Speech Recognition"),
                valueText: L("Coming Soon"),
                icon: .sf("graphic_eq"),
                iconBlock: .vibrantGreen
            )
            .modifier(GrayedOut())
            SettingsListDivider()
            SettingsListRow(
                title: L("Wake Word"),
                valueText: L("Coming Soon"),
                icon: .sf("mic.fill"),
                iconBlock: .vibrantGreen
            )
            .modifier(GrayedOut())
            footnote(L("On-device voice recognition (ASR) and wake word (KWS) are not yet implemented on iOS."))
        }
    }

    // MARK: - 构件

    private var profileLabel: String {
        switch intervalProfile {
        case "CONSERVATIVE": return L("Conservative")
        case "AGGRESSIVE": return L("Aggressive")
        default: return L("Balanced")
        }
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

    /// 开关行（spec 行式：图标块 + 标题 + Switch，对齐 SettingsListRow 布局量规）。
    private func listToggleRow(
        title: String,
        icon: SettingsListIcon,
        iconBlock: SettingsListIconBlock,
        isOn: Binding<Bool>
    ) -> some View {
        HStack(spacing: SettingsTokens.rowElementGap) {
            icon.image(size: SettingsTokens.listIconInnerSize)
                .foregroundColor(iconBlock.colors(s).fg)
                .frame(width: SettingsTokens.listIconBlockSize, height: SettingsTokens.listIconBlockSize)
                .background(Circle().fill(iconBlock.colors(s).bg))
            Text(title)
                .font(.system(size: SettingsTokens.listTitleFontSize))
                .foregroundColor(s.onSurface)
            Spacer()
            Toggle("", isOn: isOn).labelsHidden()
        }
        .frame(minHeight: SettingsTokens.listRowHeight)
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
        LocalModelsSettingsView()
    }
}
