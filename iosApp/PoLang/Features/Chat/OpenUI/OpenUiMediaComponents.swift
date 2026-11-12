import SwiftUI
import Photos
import AVKit
import SharedKit

// MARK: - OpenUI 媒体族组件（photoId → localIdentifier 解析 + ThumbnailLoader 渐进加载）
//
// photoId 解析经 ctx.resolvePhoto（iosMain IosOpenUiMediaSeam：单卡一次全量 fetch 快照）；
// 显示经 ThumbnailLoader（localIdentifier → PHAsset 缩略图）——每图独立异步加载，
// 单张完成即上屏（对齐 Android 2026-11-09 并行渐进行为）；uri 不进 program/payload（[PRIVACY]）。

// MARK: - 单图（Image：photo/src 二缺一；aspect 缺省 4:3，显式 clamp [0.3, 3.0]）

struct OpenUiImageComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        OpenUiAsyncPhoto(photoId: ouNumber(props.raw("photo")).map(Int64.init),
                         remoteSrc: ouText(props.raw("src")),
                         aspect: clampedAspect(ouNumber(props.raw("aspect")), fallback: 4.0 / 3.0),
                         cornerRadius: OpenUiTokens.imageCornerRadius,
                         onTap: tapUri(props))
            .frame(maxWidth: .infinity)
            .clipped()
    }

    private func tapUri(_ props: OpenUiPropsBag) -> (() -> Void)? {
        guard ouNumber(props.raw("photo")) != nil || ouText(props.raw("src")) != nil else { return nil }
        return { [ctx] in
            Task { @MainActor in
                var uri: String?
                if let photoId = ouNumber(props.raw("photo")).map(Int64.init) {
                    uri = try? await ctx.resolvePhoto(photoId)
                } else if let src = ouText(props.raw("src")), let url = URL(string: src) {
                    uri = url.absoluteString
                }
                if let uri { ctx.onPhotoTap(uri) }
            }
        }
    }
}

/// aspect prop 显式值 clamp（Android 同区间 [0.3, 3.0]；缺省回落真实比例/4:3）。
func clampedAspect(_ raw: Double?, fallback: Double) -> Double {
    guard let raw, raw > 0 else { return fallback }
    return min(3.0, max(0.3, raw))
}

// MARK: - 异步照片视图（photoId / https src 双源）

struct OpenUiAsyncPhoto: View {
    let photoId: Int64?
    let remoteSrc: String?
    var aspect: Double = 4.0 / 3.0
    var cornerRadius: CGFloat = OpenUiTokens.imageCornerRadius
    var onTap: (() -> Void)? = nil

    @State private var localId: String? = nil
    @State private var resolved = false

    var body: some View {
        Group {
            if let localId {
                OpenUiLocalPhoto(localIdentifier: localId, aspect: aspect, cornerRadius: cornerRadius, onTap: onTap)
            } else if let remoteSrc, let url = URL(string: remoteSrc) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFill()
                    default:
                        Color(.tertiarySystemBackground)
                    }
                }
                .aspectRatio(aspect, contentMode: .fill)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
                .contentShape(Rectangle())
                .onTapGesture { onTap?() }
            } else if resolved {
                // 解析完成但无源（photo/src 皆无或已删除）：占位灰块
                ZStack {
                    Color(.tertiarySystemBackground)
                    Image(systemName: "photo")
                        .font(.system(size: 20))
                        .foregroundColor(Color(.tertiaryLabel))
                }
                .aspectRatio(aspect, contentMode: .fill)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            } else {
                // 解析中（photoId → localIdentifier 异步）
                Color(.tertiarySystemBackground)
                    .aspectRatio(aspect, contentMode: .fill)
                    .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
                    .opacity(0.6)
            }
        }
        .task(id: photoId) {
            guard let photoId, localId == nil else { return }
            // 解析经上下文媒体缝（宿主注入；流式预览传 nil 时直接落占位）
            if let seam = IosAgentComposition.shared.openUiMediaSeam {
                localId = try? await seam.resolveUri(id: photoId)
            }
            resolved = true
        }
    }
}

/// localIdentifier → PHAsset 缩略图（渐进：单张完成即上屏）。
struct OpenUiLocalPhoto: View {
    let localIdentifier: String
    var aspect: Double = 4.0 / 3.0
    var cornerRadius: CGFloat = OpenUiTokens.imageCornerRadius
    var onTap: (() -> Void)? = nil
    var square: Bool = false

    @State private var image: UIImage? = nil

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Color(.tertiarySystemBackground)
            }
        }
        .aspectRatio(square ? 1 : aspect, contentMode: .fill)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .contentShape(Rectangle())
        .onTapGesture { onTap?() }
        .task(id: localIdentifier) {
            let side: CGFloat = 480
            image = try? await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier, size: CGSize(width: side, height: side))
        }
    }
}

// MARK: - Video（海报帧 + 点击播放；离组释放）

struct OpenUiVideoComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    @State private var playerItem: AVPlayerItem? = nil

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let aspect = clampedAspect(ouNumber(props.raw("aspect")), fallback: 16.0 / 9.0)
        return Group {
            if let playerItem {
                VideoPlayer(player: AVPlayer(playerItem: playerItem))
                    .aspectRatio(aspect, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: OpenUiTokens.imageCornerRadius))
            } else {
                videoPoster(props: props, aspect: aspect)
            }
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder private func videoPoster(props: OpenUiPropsBag, aspect: Double) -> some View {
        ZStack {
            if let src = ouText(props.raw("src")), let url = URL(string: src) {
                AsyncImage(url: url) { phase in
                    if case .success(let image) = phase {
                        image.resizable().scaledToFill()
                    } else {
                        Color(.tertiarySystemBackground)
                    }
                }
            } else if let photoId = ouNumber(props.raw("photo")).map(Int64.init) {
                OpenUiAsyncPhoto(photoId: photoId, remoteSrc: nil, aspect: aspect,
                                 cornerRadius: 0, onTap: nil)
            } else {
                Color(.tertiarySystemBackground)
            }
            Circle()
                .fill(Color.black.opacity(0.45))
                .frame(width: 44, height: 44)
            Image(systemName: "play.fill")
                .font(.system(size: 18))
                .foregroundColor(.white)
        }
        .aspectRatio(aspect, contentMode: .fill)
        .clipShape(RoundedRectangle(cornerRadius: OpenUiTokens.imageCornerRadius))
        .contentShape(Rectangle())
        .onTapGesture { loadPlayer(props: props) }
    }

    private func loadPlayer(props: OpenUiPropsBag) {
        if playerItem != nil { return }
        Task { @MainActor in
            var localIdentifier: String?
            if let photoId = ouNumber(props.raw("photo")).map(Int64.init) {
                localIdentifier = try? await ctx.resolvePhoto(photoId)
            }
            guard let lid = localIdentifier,
                  let asset = PHAsset.fetchAssets(withLocalIdentifiers: [lid], options: nil).firstObject
            else { return }
            PHImageManager.default().requestPlayerItem(forVideo: asset, options: nil) { item, _ in
                Task { @MainActor in
                    if let item { playerItem = item }
                }
            }
        }
    }
}

// MARK: - PhotoGrid（方形 1:1 密缝网格，take(12) 护栏）

struct OpenUiPhotoGridComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let columns = max(2, min(4, Int(ouNumber(props.raw("columns")) ?? 3)))
        let photoIds = ouItems(props.raw("photos")).compactMap { ouNumber($0).map(Int64.init) }
        let cells = Array(photoIds.prefix(12))
        let rows = stride(from: 0, to: cells.count, by: columns).map { Array(cells[$0..<min($0 + columns, cells.count)]) }
        return VStack(spacing: OpenUiTokens.photoGridGap) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: OpenUiTokens.photoGridGap) {
                    ForEach(row, id: \.self) { photoId in
                        OpenUiGridCell(photoId: photoId, ctx: ctx)
                    }
                    // 行尾补位（末行不满时保持等宽）
                    ForEach(0..<max(0, columns - row.count), id: \.self) { _ in
                        Color.clear
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: OpenUiTokens.photoGridCornerRadius))
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct OpenUiGridCell: View {
    let photoId: Int64
    let ctx: OpenUiRenderContext

    @State private var localId: String? = nil
    @State private var missed = false

    var body: some View {
        ZStack {
            if let localId {
                OpenUiLocalPhoto(localIdentifier: localId, aspect: 1, cornerRadius: 0,
                                 onTap: { [ctx] in ctx.onPhotoTap(localId) })
            } else if missed {
                Color(.tertiarySystemBackground)
            } else {
                Color(.tertiarySystemBackground).opacity(0.6)
            }
        }
        .aspectRatio(1, contentMode: .fill)
        .task(id: photoId) {
            if let seam = IosAgentComposition.shared.openUiMediaSeam {
                localId = try? await seam.resolveUri(id: photoId)
                missed = (localId == nil)
            } else {
                missed = true
            }
        }
    }
}

// MARK: - AvatarRow（人物头像横滑条）

struct OpenUiAvatarRowComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let items = ouItems(OpenUiPropsBag(node: node).raw("items"))
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    let obj = ouObject(item)
                    OpenUiAvatarCell(photoId: ouNumber(obj["photo"]).map(Int64.init),
                                     label: ouText(obj["label"]), ctx: ctx)
                }
            }
            .padding(.vertical, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct OpenUiAvatarCell: View {
    let photoId: Int64?
    let label: String?
    let ctx: OpenUiRenderContext

    @State private var localId: String? = nil
    @State private var image: UIImage? = nil

    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Color(.tertiarySystemBackground)
                        .overlay(Image(systemName: "person.fill")
                            .font(.system(size: 20))
                            .foregroundColor(Color(.tertiaryLabel)))
                }
            }
            .frame(width: OpenUiTokens.avatarRowSize, height: OpenUiTokens.avatarRowSize)
            .clipShape(Circle())
            .contentShape(Rectangle())
            .onTapGesture {
                if let localId { ctx.onPhotoTap(localId) }
            }
            if let label {
                Text(label)
                    .font(OpenUiTokens.font(role: "CAPTION"))
                    .foregroundColor(Color(.secondaryLabel))
                    .lineLimit(1)
            }
        }
        .task(id: photoId) {
            guard let photoId, let seam = IosAgentComposition.shared.openUiMediaSeam else { return }
            localId = try? await seam.resolveUri(id: photoId)
            if let localId {
                image = try? await ThumbnailLoader.shared.thumbnail(
                    for: localId, size: CGSize(width: 112, height: 112))
            }
        }
    }
}

// MARK: - CompareRow（前后对比双图同帧）

struct OpenUiCompareRowComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let aspect = clampedAspect(ouNumber(props.raw("aspect")), fallback: 4.0 / 3.0)
        return HStack(spacing: 8) {
            compareSide(photoId: ouNumber(props.raw("beforePhoto")).map(Int64.init),
                        src: ouText(props.raw("beforeSrc")),
                        label: ouText(props.raw("beforeLabel")), aspect: aspect)
            compareSide(photoId: ouNumber(props.raw("afterPhoto")).map(Int64.init),
                        src: ouText(props.raw("afterSrc")),
                        label: ouText(props.raw("afterLabel")), aspect: aspect)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func compareSide(photoId: Int64?, src: String?, label: String?, aspect: Double) -> some View {
        VStack(spacing: 4) {
            OpenUiAsyncPhoto(photoId: photoId, remoteSrc: src, aspect: aspect,
                             cornerRadius: OpenUiTokens.imageCornerRadius,
                             onTap: { [ctx] in
                                 Task { @MainActor in
                                     var uri: String?
                                     if let photoId { uri = try? await ctx.resolvePhoto(photoId) }
                                     else if let src { uri = src }
                                     if let uri { ctx.onPhotoTap(uri) }
                                 }
                             })
            if let label {
                Text(label)
                    .font(OpenUiTokens.font(role: "CAPTION"))
                    .foregroundColor(Color(.secondaryLabel))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Timeline（轴线 + 节点 + 缩略图行）

struct OpenUiTimelineComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let items = ouItems(OpenUiPropsBag(node: node).raw("items"))
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                let obj = ouObject(item)
                HStack(alignment: .top, spacing: 10) {
                    VStack(spacing: 0) {
                        Circle()
                            .fill(Color.accentColor)
                            .frame(width: 8, height: 8)
                            .padding(.top, 6)
                        if index < items.count - 1 {
                            Rectangle()
                                .fill(Color(.label).opacity(0.1))
                                .frame(width: 1.5)
                                .frame(maxHeight: .infinity)
                        }
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ouText(obj["title"]) ?? "")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(Color(.label))
                        if let subtitle = ouText(obj["subtitle"]) {
                            Text(subtitle)
                                .font(.system(size: 12))
                                .foregroundColor(Color(.secondaryLabel))
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if let time = ouText(obj["time"]) {
                            Text(time)
                                .font(OpenUiTokens.font(role: "CAPTION"))
                                .foregroundColor(Color(.tertiaryLabel))
                        }
                    }
                    Spacer(minLength: 8)
                    if let photoId = ouNumber(obj["photo"]).map(Int64.init) {
                        OpenUiTimelineThumb(photoId: photoId, ctx: ctx)
                    }
                }
                .padding(.bottom, index < items.count - 1 ? 12 : 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct OpenUiTimelineThumb: View {
    let photoId: Int64
    let ctx: OpenUiRenderContext

    @State private var localId: String? = nil

    var body: some View {
        ZStack {
            if let localId {
                OpenUiLocalPhoto(localIdentifier: localId, aspect: 1, cornerRadius: 8,
                                 onTap: { [ctx] in ctx.onPhotoTap(localId) })
            } else {
                Color(.tertiarySystemBackground)
            }
        }
        .frame(width: OpenUiTokens.timelineThumbSize, height: OpenUiTokens.timelineThumbSize)
        .task(id: photoId) {
            if let seam = IosAgentComposition.shared.openUiMediaSeam {
                localId = try? await seam.resolveUri(id: photoId)
            }
        }
    }
}

// MARK: - ImageBlock / ImageGallery（https 公网图）

struct OpenUiImageBlockComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        if let src = ouText(props.raw("src")), let url = URL(string: src) {
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().scaledToFit()
                default:
                    Color(.tertiarySystemBackground).aspectRatio(16.0 / 9.0, contentMode: .fill)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: OpenUiTokens.imageCornerRadius))
            .frame(maxWidth: .infinity)
            .accessibilityLabel(ouText(props.raw("alt")) ?? "")
        }
    }
}

struct OpenUiImageGalleryComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let images = ouItems(OpenUiPropsBag(node: node).raw("images"))
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(images.enumerated()), id: \.offset) { _, item in
                    let obj = ouObject(item)
                    if let src = ouText(obj["src"]), let url = URL(string: src) {
                        AsyncImage(url: url) { phase in
                            if case .success(let image) = phase {
                                image.resizable().scaledToFill()
                            } else {
                                Color(.tertiarySystemBackground)
                            }
                        }
                        .frame(width: 200, height: 130)
                        .clipShape(RoundedRectangle(cornerRadius: OpenUiTokens.imageCornerRadius))
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Banner（通栏题图横幅）

struct OpenUiBannerComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let aspect = clampedAspect(ouNumber(props.raw("aspect")), fallback: 16.0 / 9.0)
        return ZStack(alignment: .bottomLeading) {
            if let photoId = ouNumber(props.raw("photo")).map(Int64.init) {
                OpenUiAsyncPhoto(photoId: photoId, remoteSrc: nil, aspect: aspect,
                                 cornerRadius: OpenUiTokens.imageCornerRadius,
                                 onTap: { [ctx] in
                                     Task { @MainActor in
                                         if let uri = try? await ctx.resolvePhoto(photoId) { ctx.onPhotoTap(uri) }
                                     }
                                 })
            } else if let src = ouText(props.raw("src")) {
                OpenUiAsyncPhoto(photoId: nil, remoteSrc: src, aspect: aspect,
                                 cornerRadius: OpenUiTokens.imageCornerRadius, onTap: nil)
            } else {
                Color(.secondarySystemBackground) // 双源皆缺：纯色底
            }
            LinearGradient(colors: [.black.opacity(0.55), .black.opacity(0)],
                           startPoint: .bottom, endPoint: .center)
            VStack(alignment: .leading, spacing: 2) {
                Text(ouText(props.raw("title")) ?? "")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
                    .lineLimit(2)
                if let subtitle = ouText(props.raw("subtitle")) {
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundColor(.white.opacity(0.85))
                        .lineLimit(2)
                }
            }
            .padding(12)
        }
        .aspectRatio(aspect, contentMode: .fill)
        .clipShape(RoundedRectangle(cornerRadius: OpenUiTokens.imageCornerRadius))
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - ImageText / ImageTextLarge（图文组合固化件）

struct OpenUiImageTextComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let imageSize = (ouNumber(props.raw("imageSize")).map { CGFloat($0) }) ?? 48
        return HStack(spacing: 10) {
            OpenUiAsyncPhoto(photoId: ouNumber(props.raw("photo")).map(Int64.init),
                             remoteSrc: ouText(props.raw("src")),
                             aspect: 1, cornerRadius: 8,
                             onTap: { [ctx] in
                                 Task { @MainActor in
                                     if let id = ouNumber(props.raw("photo")).map(Int64.init),
                                        let uri = try? await ctx.resolvePhoto(id) {
                                         ctx.onPhotoTap(uri)
                                     }
                                 }
                             })
                .frame(width: imageSize, height: imageSize)
            VStack(alignment: .leading, spacing: 2) {
                Text(ouText(props.raw("title")) ?? "")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(Color(.label))
                    .lineLimit(1)
                if let subtitle = ouText(props.raw("subtitle")) {
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundColor(Color(.secondaryLabel))
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiImageTextLargeComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        return VStack(alignment: .leading, spacing: 8) {
            OpenUiAsyncPhoto(photoId: ouNumber(props.raw("photo")).map(Int64.init),
                             remoteSrc: ouText(props.raw("src")),
                             aspect: 16.0 / 9.0, cornerRadius: OpenUiTokens.imageCornerRadius,
                             onTap: { [ctx] in
                                 Task { @MainActor in
                                     if let id = ouNumber(props.raw("photo")).map(Int64.init),
                                        let uri = try? await ctx.resolvePhoto(id) {
                                         ctx.onPhotoTap(uri)
                                     }
                                 }
                             })
                .frame(maxWidth: .infinity)
            VStack(alignment: .leading, spacing: 2) {
                Text(ouText(props.raw("title")) ?? "")
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
                if let subtitle = ouText(props.raw("subtitle")) {
                    Text(subtitle)
                        .font(.system(size: 13))
                        .foregroundColor(Color(.secondaryLabel))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
