import SwiftUI

/// 悬浮底部 Tab（对标 Android MainFloatingBottomBar，main-nav.yaml §1 bottom_bar）
/// 底部居中悬浮胶囊，纯图标无文字；五项表驱动，页索引 0-4 与主 Pager 页序 1:1：
/// 相册(0) / 整理(1) / 聊天(2) / 人物(3) / 回忆(4)。
/// 选中项高亮（accentColor）且点击空操作；未选中点击瞬时切页（无滑动动画）。
/// 对标：RoundedCornerShape(28dp)（iOS Capsule 等效）、surface 底（iOS ultraThinMaterial，
/// main-nav.yaml §1 allowed_differences 登记的平台差异）、12/8 内边距、24dp 图标、SpaceEvenly。
struct FloatingBottomTab: View {
    @Binding var currentPage: Int

    /// 表驱动项（main-nav.yaml §1 bottom_bar.items；图标资产 mat_o_photo_library /
    /// mat_o_cleaning_services 由资产批落地——缺失时 Image(named:) 空渲染，名字先占位对齐契约）。
    private struct TabItem {
        let icon: String
        let page: Int
        let labelKey: String
        let a11yId: String

        static let all: [TabItem] = [
            TabItem(icon: "mat_o_photo_library", page: 0, labelKey: "tab_gallery", a11yId: "tab_gallery"),
            TabItem(icon: "mat_o_cleaning_services", page: 1, labelKey: "gallery_cleanup", a11yId: "tab_organize"),
            TabItem(icon: "mat_o_chat_bubble", page: 2, labelKey: "chat", a11yId: "tab_chat"),
            TabItem(icon: "mat_o_account_circle", page: 3, labelKey: "gallery_people_entry", a11yId: "tab_person"),
            TabItem(icon: "mat_o_auto_awesome", page: 4, labelKey: "tab_memories", a11yId: "tab_memories"),
        ]
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(TabItem.all, id: \.a11yId) { item in
                tabItem(item)
            }
        }
        .frame(maxWidth: .infinity)  // SpaceEvenly：五项均分胶囊宽度
        .padding(.horizontal, BottomTabTokens.containerPaddingH)
        .padding(.vertical, BottomTabTokens.containerPaddingV)
        .background(
            Capsule()
                .fill(.ultraThinMaterial)
                .shadow(color: .black.opacity(0.15), radius: 6, y: 3)
        )
    }

    private func tabItem(_ item: TabItem) -> some View {
        Button {
            // 选中项点击空操作（赋同值无副作用）；未选中瞬时切页（无动画，main-nav §1）
            currentPage = item.page
        } label: {
            MatIcon(name: item.icon, size: BottomTabTokens.iconSize)
                // 仅 tint 区分选中态，无背景块（main-nav §1 selected_style）
                .foregroundColor(currentPage == item.page ? .accentColor : .primary)
                .padding(.horizontal, BottomTabTokens.itemPaddingH)
                .padding(.vertical, BottomTabTokens.itemPaddingVIconOnly)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        // UI 自动化锚点：tab_gallery / tab_organize / tab_chat / tab_person / tab_memories
        .accessibilityIdentifier(item.a11yId)
        // 纯图标无文字 → a11y 读本地化标题（对齐 Android bottomBarItem contentDescription；
        // TalkBack/VoiceOver 不读资产名 mat_o_*）
        .accessibilityLabel(Text(L(item.labelKey)))
    }
}
