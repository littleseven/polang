import SwiftUI
import SharedKit

// MARK: - 回忆详情页（spec memories.yaml §3 detail_page，路由 memory_detail/{memoryId}）

/// 详情页：顶栏（返回/标题/分享）→ 约屏高 55% 封面 → 3 列方图网格 → 底部居中
/// 「精选/全部」胶囊分段开关；封面/网格点击进全屏 MediaPagerView 预览。
/// 单根 ZStack 容器承载 内容列 + 预览覆盖层（多根平铺事故铁律同构）。
/// 数据源 = viewModel 未过滤全集（已隐藏条目可复原、冷恢复不闪空态）；id 失效 → 空态。
struct MemoryDetailView: View {

    let memoryId: String
    /// 共享根页 VM（observeMemory(id) 数据源 + assetsByUri 反查索引）。
    @ObservedObject var viewModel: MemoriesViewModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    @State private var showAll = false
    @State private var preview: MemoryPreview?
    @State private var sharePayload: SharePayload?

    private var memory: Memory? { viewModel.memory(id: memoryId) }

    /// displayUris = showAll ? allItemUris : itemUris（分享集合同步跟随）。
    private var displayUris: [String] {
        guard let memory else { return [] }
        return showAll ? memory.allItemUris : memory.itemUris
    }

    /// 预览集合：displayUris 经 assetsByUri 反查、剔除未解析项。
    private var previewAssets: [MediaAsset] {
        displayUris.compactMap { viewModel.assetsByUri[$0] }
    }

    var body: some View {
        ZStack {
            s.surface.ignoresSafeArea()
            if let memory {
                GeometryReader { geo in
                    VStack(spacing: 0) {
                        topBar
                        ScrollView {
                            cover(memory, screenHeight: geo.size.height)
                            grid
                            // 底部留白：避让悬浮分段开关（开关高 48 + 间距/余量）
                            Color.clear.frame(height: 120)
                        }
                    }
                }
                .overlay(alignment: .bottom) { segmentedToggle }
            } else {
                // id 失效（媒体清空/隐藏后失效）→ 空态不崩溃
                Text(L("memory_detail_empty"))
                    .font(.system(size: AppTypography.bodyMedium.size))
                    .foregroundColor(s.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, Spacing.xl)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .accessibilityIdentifier("memory_detail_root")
        // showAll 按 memory.id 记忆——切回忆时重置回精选
        .onChange(of: memory?.id) { _ in
            showAll = false
        }
        .fullScreenCover(item: $preview, onDismiss: {
            // 预览内删除后 MediaPagerView 自收缩（其内置行为）；收起后刷新生成集
            viewModel.reload()
        }) { target in
            MediaPagerView(items: previewAssets, initial: target.uri)
        }
        .sheet(item: $sharePayload) { payload in
            ActivityView(activityItems: payload.images)
        }
    }

    // MARK: 顶栏（返回 + memory_title + 分享；仅 memory 非 nil 时整体可见）

    private var topBar: some View {
        HStack(spacing: TopBarTokens.spacing) {
            Button {
                dismiss()
            } label: {
                MatIcon(name: "mat_o_arrow_back", size: TopBarTokens.iconSize)
                    .foregroundColor(s.onSurface)
                    .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("memory_detail_back")
            Text(L("memory_title"))
                .font(.system(size: TopBarTokens.titleFontSize, weight: TopBarTokens.titleFontWeight))
                .foregroundColor(s.onSurface)
                .lineLimit(1)
            Spacer(minLength: 0)
            Button {
                share()
            } label: {
                MatIcon(name: "mat_share", size: TopBarTokens.iconSize)
                    .foregroundColor(s.onSurface)
                    .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(L("memory_share")))
        }
        .padding(.leading, TopBarTokens.horizontalPadding)
        .padding(.trailing, TopBarTokens.horizontalPadding)
        .frame(height: TopBarTokens.height)
    }

    // MARK: 封面（约屏高 55%；裁切填满 + 底部 50% 黑渐变 + 左下标题/副行）

    private func cover(_ memory: Memory, screenHeight: CGFloat) -> some View {
        let title = MemoryTexts.title(for: memory)
        let subtitle = String(format: L("memory_items_count"), memory.hitCount) + subtitleSuffix(memory)
        return ZStack(alignment: .bottomLeading) {
            MemoryDetailCover(localIdentifier: memory.coverUri)
            LinearGradient(colors: [.clear, Color.black.opacity(0.6)],
                           startPoint: .top, endPoint: .bottom)
                .frame(height: screenHeight * 0.55 / 2)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .allowsHitTesting(false)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundColor(.white.opacity(AppAlpha.emphasis))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(Spacing.lg)
        }
        .frame(height: screenHeight * 0.55)
        .frame(maxWidth: .infinity)
        .clipped()
        .contentShape(Rectangle())
        .onTapGesture { preview = MemoryPreview(uri: memory.coverUri) }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(title) · \(subtitle)"))
        .accessibilityAddTraits(.isButton)
    }

    /// 副行类型后缀（spec §3 cover.subtitle_suffix）：PERSON 不接（与 hitCount 重复计数）。
    private func subtitleSuffix(_ memory: Memory) -> String {
        switch memory.type {
        case .onThisDay, .recentHighlights:
            return " · " + MemoryTexts.subtitle(for: memory)
        case .city:
            return " · " + MemoryTexts.cityDateRange(memory)
        case .person:
            return ""
        }
    }

    // MARK: 3 列方图网格（1:1 · r8 · 间距 4 · padding 16/8；cd 序号 1 起）

    private var gridColumns: [GridItem] {
        [
            GridItem(.flexible(), spacing: Spacing.xs),
            GridItem(.flexible(), spacing: Spacing.xs),
            GridItem(.flexible(), spacing: Spacing.xs),
        ]
    }

    private var grid: some View {
        LazyVGrid(columns: gridColumns, spacing: Spacing.xs) {
            ForEach(Array(displayUris.enumerated()), id: \.element) { index, uri in
                ThumbnailView(localIdentifier: uri, cornerRadius: AppRadius.small)
                    .aspectRatio(1, contentMode: .fit)
                    .clipped()
                    .contentShape(Rectangle())
                    .onTapGesture { preview = MemoryPreview(uri: uri) }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text(String(format: L("memory_photo_cd"), index + 1)))
                    .accessibilityAddTraits(.isButton)
            }
        }
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.sm)
    }

    // MARK: 底部居中「精选/全部」胶囊分段开关（spec §3 segmented_toggle）

    private var segmentedToggle: some View {
        HStack(spacing: 0) {
            toggleOption(L("memory_detail_best"), value: false)
            toggleOption(L("memory_detail_all"), value: true)
        }
        .frame(height: 48)
        .background(Capsule().fill(s.surfaceVariant))
        .clipShape(Capsule())  // r24 = 高 48 的一半
        .shadow(color: .black.opacity(0.15), radius: AppElevation.floating, y: 3)
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.md)
    }

    private func toggleOption(_ label: String, value: Bool) -> some View {
        let selected = showAll == value
        return Button {
            guard !selected else { return }
            withAnimation(.easeInOut(duration: AppMotion.fastMs / 1000)) {
                showAll = value
            }
            // 开关切换收起预览（索引口径已变）
            preview = nil
        } label: {
            Text(label)
                .font(.system(size: AppTypography.labelLarge.size, weight: .medium))
                .foregroundColor(selected ? s.onPrimary : s.onSurfaceVariant)
                .padding(.horizontal, Spacing.xl)
                .frame(maxHeight: .infinity)
                .background(Capsule().fill(selected ? s.primary : Color.clear))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    // MARK: 分享（UIActivityViewController；集合 = 当前 displayUris 跟随开关）

    private func share() {
        let uris = displayUris
        guard !uris.isEmpty else { return }
        Task { @MainActor in
            // PHAsset 反查 → UIImage（对齐 MediaPagerView 分享通路：2000px 高清档，端侧零网络）
            var images: [UIImage] = []
            for uri in uris {
                if let image = await ThumbnailLoader.shared.thumbnail(
                    for: uri, size: CGSize(width: 2000, height: 2000), highQuality: true) {
                    images.append(image)
                }
            }
            guard !images.isEmpty else { return }
            sharePayload = SharePayload(images: images)
        }
    }
}

/// 预览定位载体（fullScreenCover item 绑定需 Identifiable）。
private struct MemoryPreview: Identifiable {
    let uri: String
    var id: String { uri }
}

/// 详情封面图（裁切填满；大图请求；占位/失败 = surfaceContainer 色块，禁交叉淡入）。
private struct MemoryDetailCover: View {

    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    let localIdentifier: String
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            s.surfaceContainer
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            }
        }
        .task(id: localIdentifier) {
            // 大图请求（spec §3 cover.image：Coil 1080 档 → iOS ≥1080px 高清）
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier, size: CGSize(width: 1080, height: 1080), highQuality: true)
        }
    }
}
