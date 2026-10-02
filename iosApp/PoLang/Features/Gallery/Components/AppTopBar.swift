import SwiftUI

/// 自建顶栏（对齐 Android `AppTopBar.kt`，2026-10-02 标题左对齐定稿）：
/// 标题 17sp SemiBold 左对齐（leading，Android 全局弃居中）、
/// 栏内容高 48dp + 状态栏避让由父级 safe area 承担、底 hairline（outlineVariant）。
/// 背景随主题 token：scheme surface（Light #EDEDED / Dark #111111，与页面底同色）。
/// 图标按钮触控框 36dp（字形 22dp）、pitch 44dp（36+8，TopBarTokens）。
/// 不用系统 NavigationBar：双端视觉一致（S5），系统大标题风格不可控。
struct AppTopBar<Actions: View>: View {
    let title: String
    var showsBackButton: Bool = false
    var onBack: (() -> Void)? = nil
    var backLabel: String? = nil
    @ViewBuilder var actions: Actions
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        VStack(spacing: 0) {
            HStack(spacing: TopBarTokens.spacing) {
                if showsBackButton {
                    AppTopBarAction(systemName: "mat_o_arrow_back",
                                    accessibilityID: "topbar_back",
                                    label: backLabel) { onBack?() }
                }
                Text(title)
                    .font(.system(size: TopBarTokens.titleFontSize, weight: TopBarTokens.titleFontWeight))
                    .lineLimit(1)
                Spacer(minLength: 0)
                HStack(spacing: TopBarTokens.spacing) { actions }
            }
            .padding(.horizontal, TopBarTokens.horizontalPadding)
            .frame(height: TopBarTokens.height)
            .frame(maxWidth: .infinity)
            Divider()
                .overlay(s.outlineVariant)  // 底 hairline（导航分隔）
        }
        .background(s.surface)
    }
}

/// 顶栏图标按钮（触控框 36dp、字形 22dp，TopBarTokens）。
/// `isEnabled == false` 时灰置且不可点（功能依赖未落地管线时的降级呈现，不假造交互）；
/// `tint` 缺省走系统语义色，`label` 缺省不覆盖 accessibility 标签。
struct AppTopBarAction: View {
    let systemName: String
    var accessibilityID: String? = nil
    var isEnabled: Bool = true
    var tint: Color? = nil
    var label: String? = nil
    let action: () -> Void

    var body: some View {
        if let label {
            button.accessibilityLabel(label)
        } else {
            button
        }
    }

    private var button: some View {
        Button(action: action) {
            MatIcon(name: systemName, size: TopBarTokens.iconSize)
                .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(tint ?? (isEnabled ? Color.primary : Color.secondary.opacity(0.35)))
        .disabled(!isEnabled)
        .accessibilityIdentifier(accessibilityID ?? "topbar_\(systemName)")
    }
}
