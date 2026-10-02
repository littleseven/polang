import SwiftUI
import SharedKit

/// 远程模型设置页（spec settings.yaml §3 remote_model，2026-10-02 S3b 行式重构）。
///
/// 两区结构（对齐 Android RemoteModelsListSection + 助手性格区）：
/// - 已配置模型行式列表（无组标题）：品牌色徽章 + 模型名/「使用中」胶囊/供应商·已配置双行文本 +
///   ⋯ 动作弹层（设为当前/删除）；点行 = 设为当前模型；组尾「添加模型」行 → AddRemoteProviderView
/// - 助手性格：单选 chips，@AppStorage "assistant_persona" 持久化 shared 枚举名，ChatViewModel 同键消费
struct ModelCenterView: View {
    @EnvironmentObject private var store: ModelConfigStore
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    /// ⋯ 动作弹层目标（标题 = 模型名，副题 = 供应商 displayName）
    @State private var actionTarget: RemoteModelConfig?

    /// 已配置模型（spec §3 data：filter isConfigured）
    private var configuredModels: [RemoteModelConfig] {
        store.configs.filter { $0.isConfigured }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: SettingsTokens.listSectionSpacing) {
                remoteModelsSection
                AssistantPersonaSection()
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Remote Models"))
        .navigationBarTitleDisplayMode(.inline)
        // ⋯ 动作弹层（spec §3 action_sheet；「设为当前」已是当前时禁用）
        .confirmationDialog(
            actionTarget?.modelId ?? "",
            isPresented: .init(get: { actionTarget != nil }, set: { if !$0 { actionTarget = nil } }),
            titleVisibility: .visible,
            presenting: actionTarget
        ) { config in
            Button(L("Set as current")) {
                store.select(modelId: config.modelId)
            }
            .disabled(config.modelId == store.selectedModelId)
            Button(L("Delete"), role: .destructive) {
                store.remove(uniqueKey: config.uniqueKey)
            }
            Button(L("Cancel"), role: .cancel) {}
        } message: { config in
            Text(providerName(for: config))
        }
    }

    // MARK: - Remote Models List（spec §3 remote_models_list，行式无组标题）

    private var remoteModelsSection: some View {
        SettingsListSection {
            if configuredModels.isEmpty {
                emptyHint
            } else {
                ForEach(Array(configuredModels.enumerated()), id: \.element.uniqueKey) { index, config in
                    RemoteModelRow(
                        config: config,
                        providerDisplayName: providerName(for: config),
                        isSelected: config.modelId == store.selectedModelId,
                        onTap: { store.select(modelId: config.modelId) },
                        onMore: { actionTarget = config }
                    )
                    if index < configuredModels.count - 1 {
                        SettingsListDivider()
                    }
                }
                // 添加行上方分隔线（spec §3 add_row：有配置项时）
                SettingsListDivider()
            }
            addRow
        }
    }

    /// 空态提示（无已配置模型时列表区仅此提示 + 添加行；文案沿用既有五语键）
    private var emptyHint: some View {
        VStack(spacing: Spacing.xs) {
            Text(L("Default remote model has time limits"))
                .font(AppTypography.bodyMedium.font)
                .foregroundColor(s.onSurfaceVariant)
            Text(L("Add your own model to remove restrictions"))
                .font(AppTypography.bodySmall.font)
                .foregroundColor(s.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.md)
    }

    /// 组尾「添加模型」行：vibrantGreen 圆形块白 + → add_remote_provider（AddRemoteProviderView）
    private var addRow: some View {
        NavigationLink {
            AddRemoteProviderView()
        } label: {
            SettingsListRow(
                title: L("Add Model"),
                icon: .mat("add"),
                iconBlock: .vibrantGreen,
                showChevron: true
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Helpers

    private func providerName(for config: RemoteModelConfig) -> String {
        RemoteModelConfig.companion.getProvider(providerId: config.providerId)?.displayName
            ?? (config.baseUrl.isEmpty ? "PoLang Server" : config.baseUrl)
    }
}

// MARK: - Remote Model Row（spec §3 remote_model.row）

/// 品牌色圆角方块字母徽章 + 双行文本（行1 = 模型名 + 可选「使用中」胶囊；行2 = 供应商 · 已配置）+
/// ⋯ 动作入口；点行 = 设为当前模型。
private struct RemoteModelRow: View {
    let config: RemoteModelConfig
    let providerDisplayName: String
    let isSelected: Bool
    let onTap: () -> Void
    let onMore: () -> Void
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        HStack(spacing: SettingsTokens.rowElementGap) {
            RemoteModelBadge(providerId: config.providerId, displayName: providerDisplayName)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: Spacing.sm) {
                    Text(config.modelId)
                        .font(.system(size: SettingsTokens.listTitleFontSize, weight: .semibold))
                        .foregroundColor(s.onSurface)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if isSelected {
                        Text(L("In use"))
                            .font(AppTypography.labelSmall.font)
                            .foregroundColor(s.onPrimary)
                            .padding(.horizontal, BadgeTokens.tagPaddingH)
                            .padding(.vertical, BadgeTokens.tagPaddingV)
                            .background(s.primary)
                            .clipShape(Capsule())
                    }
                }
                Text("\(providerDisplayName) · \(L("Configured"))")
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(s.onSurfaceVariant)
            }
            Spacer()
            Button(action: onMore) {
                MatIcon(name: "mat_more_horiz", size: SettingsTokens.rowChevronSize)
                    .foregroundColor(s.onSurfaceVariant.opacity(SettingsTokens.rowChevronAlpha))
            }
            .accessibilityIdentifier("remote_models.row.more.\(config.uniqueKey)")
        }
        .frame(minHeight: SettingsTokens.listRowHeight)
        .padding(.horizontal, SettingsTokens.listRowPaddingH)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .accessibilityIdentifier("remote_models.row.\(config.uniqueKey)")
    }
}

/// spec §3 remote_model.row.badge：listIconBlockSize 圆角方块 + 白色首字母 + 品牌底色
/// （deepseek→vibrantBlue / moonshot|kimi→vibrantPurple(#4F378B) / openai→vibrantGreen /
/// anthropic→#D97757 / 其他→#938F99；色值以 §3 remote_model 为准，与 §3c 添加页色板为 spec 级差异）。
/// 字母规则沿用 §3c ProviderBrandBadge（Kimi=M、TokenHub=T，余取 displayName 首字母），与添加流两页观感一致。
private struct RemoteModelBadge: View {
    let providerId: String
    let displayName: String

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: SettingsTokens.listIconBlockRadius, style: .continuous)
                .fill(brandColor)
            Text(letter)
                .font(.system(size: AppTypography.bodyMedium.size, weight: AppTypography.WeightOverride.semibold))
                .foregroundColor(AppColors.iconOnVibrant)
        }
        .frame(width: SettingsTokens.listIconBlockSize, height: SettingsTokens.listIconBlockSize)
    }

    private var letter: String {
        switch providerId {
        case "kimi-official": return "M"
        case "tencent-tokenhub": return "T"
        default: return String(displayName.prefix(1))
        }
    }

    private var brandColor: Color {
        switch providerId {
        case "deepseek-official": return AppColors.vibrantBlue
        case "kimi-official": return AppColors.vibrantPurple
        case "openai-official": return AppColors.vibrantGreen
        case "anthropic-official": return Color(hex: "FFD97757")
        default: return Color(hex: "FF938F99")
        }
    }
}

// MARK: - Assistant Personality（spec §3 remote_model.assistant_persona）

private struct PersonaOption {
    let value: String
    let label: String
    let desc: String
}

/// 助手性格单选 chips（SettingsM3Section 带组标题）+ 选中项描述脚注。
/// 持久化 @AppStorage("assistant_persona") = shared AssistantPersona 枚举名
/// （DEFAULT/WARM/LIVELY/CONCISE），ChatViewModel 经 UserDefaults 同键消费。
private struct AssistantPersonaSection: View {
    @AppStorage("assistant_persona") private var persona: String = "DEFAULT"
    @Environment(\.colorScheme) private var cs

    private var options: [PersonaOption] {
        [
            PersonaOption(value: "DEFAULT", label: L("Default"), desc: L("Balanced, neutral standard replies")),
            PersonaOption(value: "WARM", label: L("Warm & Caring"), desc: L("Empathizes first, encouraging and supportive")),
            PersonaOption(value: "LIVELY", label: L("Lively & Playful"), desc: L("Relaxed and fun, with light emoji use")),
            PersonaOption(value: "CONCISE", label: L("Crisp & Direct"), desc: L("Straight to conclusions, minimal pleasantries")),
        ]
    }

    var body: some View {
        SettingsM3Section(title: L("Assistant Personality")) {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                FlowLayout(spacing: Spacing.sm) {
                    ForEach(options, id: \.value) { option in
                        SettingsM3Chip(label: option.label, isSelected: persona == option.value) {
                            persona = option.value
                        }
                        .accessibilityIdentifier("assistant_persona.chip.\(option.value)")
                    }
                }
                Text(selectedDesc)
                    .font(AppTypography.bodySmall.font)
                    .foregroundColor(appScheme(cs).onSurfaceVariant)
                    .lineLimit(2)
            }
        }
    }

    private var selectedDesc: String {
        options.first { $0.value == persona }?.desc ?? options[0].desc
    }
}

// MARK: - ARGB hex 色构造（与 DesignTokens.swift/AddRemoteProviderView.swift 同款 file-private 扩展）

private extension Color {
    init(hex: String) {
        let scanner = Scanner(string: hex)
        var hexNumber: UInt64 = 0
        scanner.scanHexInt64(&hexNumber)
        let a = Double((hexNumber & 0xFF00_0000) >> 24) / 255
        let r = Double((hexNumber & 0x00FF_0000) >> 16) / 255
        let g = Double((hexNumber & 0x0000_FF00) >> 8) / 255
        let b = Double(hexNumber & 0x0000_00FF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

#Preview {
    NavigationStack {
        ModelCenterView()
            .environmentObject(ModelConfigStore.shared)
    }
}
