import SwiftUI

/// 自建顶栏（对齐 Android `AppTopBar.kt` 微信式重造，2026-09-25）：
/// 标题 17sp SemiBold 居中（ZStack 叠加层=屏幕真中心，不受左右图标数量影响）、
/// 栏内容高 48dp + 状态栏避让由父级 safe area 承担、底 hairline（outlineVariant）。
/// 背景随主题 token：scheme surface（Light #EDEDED / Dark #111111，微信式与页面底同色）。
/// 图标按钮触控框 36dp（字形 22dp）、pitch 43dp（36+7）。
/// 不用系统 NavigationBar：双端视觉一致（S5），系统大标题风格不可控。
struct AppTopBar<Actions: View>: View {
    let title: String
    var showsBackButton: Bool = false
    var onBack: (() -> Void)? = nil
    @ViewBuilder var actions: Actions
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        VStack(spacing: 0) {
            ZStack {
                HStack(spacing: 7) {  // dump：按钮 pitch 143px=43dp = 36 框 + 7 间距
                    if showsBackButton {
                        AppTopBarAction(systemName: "mat_o_arrow_back",
                                        accessibilityID: "topbar_back") { onBack?() }
                    }
                    Spacer(minLength: 0)
                    HStack(spacing: 7) { actions }
                }
                .padding(.leading, showsBackButton ? 4 : 16)   // dump：标题 x65px=19.5dp（含字形内边距≈16+4）
                .padding(.trailing, 4)                          // dump：末按钮右缘 5px≈1.5dp
                // 居中标题（叠加层）：微信式屏幕真中心
                Text(title)
                    .font(.system(size: TopBarTokens.titleFontSize, weight: TopBarTokens.titleFontWeight))  // 17sp SemiBold（TopBarTokens）
                    .lineLimit(1)
            }
            .frame(height: TopBarTokens.height)
            .frame(maxWidth: .infinity)
            Divider()
                .overlay(s.outlineVariant)  // 底 hairline（微信式导航分隔）
        }
        .background(s.surface)
    }
}

/// 顶栏图标按钮（dump：触控框 117px=35dp、字形 72px=22dp）。
/// `isEnabled == false` 时灰置且不可点（功能依赖未落地管线时的降级呈现，不假造交互）。
struct AppTopBarAction: View {
    let systemName: String
    var accessibilityID: String? = nil
    var isEnabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MatIcon(name: systemName, size: 22)
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(isEnabled ? Color.primary : Color.secondary.opacity(0.35))
        .disabled(!isEnabled)
        .accessibilityIdentifier(accessibilityID ?? "topbar_\(systemName)")
    }
}
