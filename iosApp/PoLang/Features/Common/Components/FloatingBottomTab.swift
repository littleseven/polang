import SwiftUI

/// 悬浮底部 Tab（对标 Android FloatingBottomTab.kt）
/// 底部居中悬浮胶囊，纯图标无文字
/// 对标：RoundedCornerShape(28dp)、surface 底色、12dp/8dp 内边距、24dp 图标、SpaceEvenly
struct FloatingBottomTab: View {
    @Binding var currentPage: Int

    var body: some View {
        HStack(spacing: 0) {
            tabItem(icon: "mat_o_photo_camera", page: 0, labelKey: "Camera") // 字形切换 camera_alt→photo_camera（对齐 Android 2278d6f7a）
            // 整理+扫描页（spec organize.yaml §0 floating_bottom_bar_organize，Android 图标 CleaningServices）
            // ⚠️ 资产缺口登记：Assets.xcassets 暂无 mat_cleaning_services 系资产——
            // 过渡用语义最近既有资产 mat_o_delete_sweep（扫除/清扫同义，且与本栏 mat_o_* 描边族一致）；
            // 资产落地后把此名换成 mat_o_cleaning_services 即可。
            tabItem(icon: "mat_o_delete_sweep", page: 2, labelKey: "org_title")
            tabItem(icon: "mat_o_chat_bubble", page: 3, labelKey: "Chat") // Chat 已落地（Phase 6.2）；页索引位移 2→3
            tabItem(icon: "mat_o_sell", page: -1, labelKey: "Scan", isPlaceholder: true) // 打标无独立页
            tabItem(icon: "mat_o_account_circle", page: 4, labelKey: "People") // 页索引位移 3→4
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            Capsule()
                .fill(.ultraThinMaterial)
                .shadow(color: .black.opacity(0.15), radius: 6, y: 3)
        )
    }

    private func tabItem(icon: String, page: Int, labelKey: String, isPlaceholder: Bool = false) -> some View {
        Button {
            if isPlaceholder {
                // 占位页 — push 到占位 View（由父处理）；传稳定语义 key（非资产名），
                // 父级 MainTabView 按 "tag" 路由 TagScanScreen，勿回传 mat_o_* 资产名
                onPlaceholderTap?(tabId(for: icon))
            } else {
                withAnimation { currentPage = page }
            }
        } label: {
            MatIcon(name: icon, size: 24)
                .foregroundColor(currentPage == page ? .accentColor : .primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
        }
        // UI 自动化锚点：tab_camera / tab_chat / tab_tag / tab_person
        .accessibilityIdentifier("tab_\(tabId(for: icon))")
        // 纯图标无文字 → a11y 读本地化标题（对齐 Android bottomBarItem contentDescription；
        // TalkBack/VoiceOver 不读资产名 mat_o_*）
        .accessibilityLabel(Text(String(localized: String.LocalizationValue(labelKey))))
    }

    private func tabId(for icon: String) -> String {
        switch icon {
        case "mat_o_photo_camera": return "camera"
        case "mat_o_delete_sweep": return "organize"
        case "mat_o_chat_bubble": return "chat"
        case "mat_o_sell": return "tag"
        case "mat_o_account_circle": return "person"
        default: return icon
        }
    }

    var onPlaceholderTap: ((String) -> Void)?
}
