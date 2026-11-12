import SwiftUI
import SharedKit

// MARK: - OpenUI 卡宿主（openui P2 iOS 对等端，对位 androidApp OpenUiCardHost）
//
// 三态：解析中 → loading；handle 失败/prepare Failed → 降级卡（fallbackSummary，
// US-6 宁缺毋滥同口径）；Ready → 节点渲染器递归渲染。langVersion 高于端侧 → 卡顶细提示行。
// 交互动作经 IosOpenUiCardController.dispatch（引擎动作执行 + 副作用收集），
// viewState 变更指纹去重后经 onViewStateChange 持久化回写 part。

struct OpenUiCardView: View {
    let part: MessagePartOpenUiCard
    var onViewStateChange: (String) -> Void = { _ in }
    var onPhotoTap: (String) -> Void = { _ in }
    var onToAssistant: (String) -> Void = { _ in }
    @Environment(\.openURL) private var openURL

    @StateObject private var host = OpenUiCardHostModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if newerLang {
                OpenUiLangNewerHintRow()
            }
            content
            if host.showActionFailedToast {
                Text(String(localized: "openui.action_failed"))
                    .font(OpenUiTokens.font(role: "CAPTION"))
                    .foregroundColor(Color(.secondaryLabel))
            }
        }
        .task(id: "\(part.partId):\(part.program.hashValue)") {
            await host.prepare(part: part, handlers: .init(
                onViewStateChange: onViewStateChange,
                onPhotoTap: onPhotoTap,
                onToAssistant: onToAssistant,
                openURL: { url in openURL(url) }))
        }
    }

    /// 协议版本协商：卡片携带 langVersion > 端侧 LANG_VERSION → 提示升级（旧 server null 不提示）。
    private var newerLang: Bool {
        let current = Int32(OpenUiContract.shared.LANG_VERSION)
        let carried = part.langVersion?.int32Value ?? current
        return carried > current
    }

    @ViewBuilder private var content: some View {
        switch host.phase {
        case .loading:
            OpenUiCardLoadingView()
        case .degraded:
            OpenUiCardDegradedView(summary: part.fallbackSummary)
        case .ready(let snapshot):
            OpenUiNodeRenderer(
                node: snapshot.root,
                ctx: OpenUiRenderContext(
                    state: OpenUiStateBag(snapshot.state),
                    onAction: { actions in
                        Task { @MainActor in await host.dispatch(actions) }
                    },
                    onPhotoTap: onPhotoTap,
                    resolvePhoto: { photoId in
                        try? await IosAgentComposition.shared.openUiMediaSeam?.resolveUri(id: photoId)
                    }))
        }
    }
}

// MARK: - Host 状态机（controller 生命周期 + 快照发布 + 副作用分流）

/// 宿主动作出口（prepare 时注入；OpenUrl/OpenPhoto/ToAssistant/持久化均由此落地）。
struct OpenUiCardHandlers {
    var onViewStateChange: (String) -> Void = { _ in }
    var onPhotoTap: (String) -> Void = { _ in }
    var onToAssistant: (String) -> Void = { _ in }
    var openURL: (URL) -> Void = { _ in }
}

@MainActor
final class OpenUiCardHostModel: ObservableObject {

    enum Phase {
        case loading
        case degraded
        case ready(IosOpenUiCardSnapshotReady)
    }

    @Published private(set) var phase: Phase = .loading
    /// @Run Mutation 失败 toast（官方语义：中断链 + 宿主 toast 兜底，Android 同款）。
    @Published private(set) var showActionFailedToast = false

    private var controller: IosOpenUiCardController? = nil
    private var preparedKey: String? = nil
    private var lastEmittedViewState: String? = nil
    private var handlers = OpenUiCardHandlers()

    func prepare(part: MessagePartOpenUiCard, handlers newHandlers: OpenUiCardHandlers) async {
        let key = "\(part.partId):\(part.program.hashValue)"
        handlers = newHandlers
        guard preparedKey != key else { return } // viewState 回流不重建 session（Android Y4 同口径）
        preparedKey = key
        phase = .loading
        guard let controller = IosAgentComposition.shared.createOpenUiCardController(
            program: part.program, viewState: part.viewState)
        else {
            phase = .degraded
            return
        }
        self.controller = controller
        do {
            if let ready = try await controller.prepare() as? IosOpenUiCardSnapshotReady {
                phase = .ready(ready)
                lastEmittedViewState = ready.viewState
            } else {
                phase = .degraded
            }
        } catch {
            phase = .degraded
        }
    }

    /// 动作链执行：引擎动作即时（@Set/@Reset/@Run）+ 副作用分流到宿主出口。
    func dispatch(_ actions: [OpenUiAction]) async {
        guard let controller else { return }
        defer { clearToastSoon() }
        do {
            let result = try await controller.dispatch(actions: actions)
            for effect in result.sideEffects {
                handle(effect)
            }
            if let ready = result.snapshot as? IosOpenUiCardSnapshotReady {
                phase = .ready(ready)
                // viewState 指纹去重：controller 已比对，emitted 与上次不同才持久化
                if ready.viewState != lastEmittedViewState {
                    lastEmittedViewState = ready.viewState
                    handlers.onViewStateChange(ready.viewState)
                }
            } else {
                phase = .degraded
            }
        } catch {
            // 信号 6 铁律双保险：控制器内部已兜底异常，此处不再穿透
        }
    }

    private func handle(_ effect: IosOpenUiSideEffect) {
        switch onEnum(of: effect) {
        case .openUrl(let e):
            if let url = URL(string: e.url) { handlers.openURL(url) }
        case .openPhoto(let e):
            if let first = e.uris.first { handlers.onPhotoTap(first) }
        case .toAssistant(let e):
            handlers.onToAssistant(e.text)
        case .runFailed:
            showActionFailedToast = true
        case .custom:
            // Navigate 等 App 内路由 iOS M1 不接线（Android onOpenUiNavigate 对位缺口，
            // 平台差异台账登记）；未知自定义动作告警不穿透
            break
        }
    }

    private func clearToastSoon() {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            showActionFailedToast = false
        }
    }
}

// MARK: - Loading / 降级卡 / 版本提示行 / 性能行

/// 卡解析/装载中占位（16pt 边距居中 spinner，Android OpenUiCardLoading 同指标）。
struct OpenUiCardLoadingView: View {
    var body: some View {
        HStack {
            Spacer()
            ProgressView()
            Spacer()
        }
        .padding(16)
        .frame(maxWidth: .infinity)
    }
}

/// 降级占位卡（US-6 对偶）：handle 缺失/prepare 失败时顶替整张卡。
/// 两条静态 label 五语；fallbackSummary 为云端动态内容（LLM 句子），[I18N] 豁免。
struct OpenUiCardDegradedView: View {
    let summary: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(String(localized: "openui.card_unavailable"))
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(Color(.label))
            Text(degradedBody)
                .font(.system(size: 14))
                .foregroundColor(Color(.secondaryLabel))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var degradedBody: String {
        summary.isEmpty
            ? String(localized: "openui.card_unavailable")
            : String(format: String(localized: "openui.card_data_elsewhere"), summary)
    }
}

/// 「新版内容格式」细提示行（协议版本协商）：语义色弱提示不抢卡内容（Android 同款卡顶行）。
struct OpenUiLangNewerHintRow: View {
    var body: some View {
        Text(String(localized: "openui.lang_newer_hint"))
            .font(OpenUiTokens.font(role: "CAPTION"))
            .foregroundColor(Color(.secondaryLabel))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

/// 性能指标行（对位 Android MessagePerformanceRow：prompt/decode tokens + 回合耗时 + 速度）。
/// 弱化小字（onSurfaceVariant 55% 口径）；仅云端 done 帧有 usage 时装配（全 0 不渲染）。
struct OpenUiPerformanceRow: View {
    let performance: LlmPerformance

    var body: some View {
        HStack(spacing: 10) {
            perfMetric(icon: "textformat.size", value: "\(performance.promptLen)")
            perfMetric(icon: "bubble.right", value: "\(performance.decodeLen)")
            if performance.decodeTimeMs > 0 {
                perfMetric(icon: "bolt", value: "\(performance.decodeTimeMs)ms")
            }
            if performance.decodeSpeed > 0 {
                perfMetric(icon: "speedometer", value: String(format: "%.1f/s", performance.decodeSpeed))
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 4)
    }

    private func perfMetric(icon: String, value: String) -> some View {
        HStack(spacing: 3) {
            Image(systemName: icon)
                .font(.system(size: 10))
            Text(value)
                .font(.system(size: 11))
                .monospacedDigit()
        }
        .foregroundColor(Color(.secondaryLabel).opacity(0.8))
    }
}

// MARK: - 流式预览卡（openui P2；24k 预算对齐 Android PREVIEW_PARSE_BUDGET_CHARS）

/// openui_delta 帧的 program 前缀边收边渲染：预算内每帧重 prepare（空 resolver 不取数，
/// 数据组件 defaults 空态）；超预算转 indeterminate 占位等正式卡接管；只读不写 viewState；
/// 解析失败保留上一帧好预览；正式卡落库后预览消息让位（宿主 ChatViewModel 清流）。
struct OpenUiStreamingCardView: View {
    let program: String

    /// 逐帧重解析客户端字符预算（Android PREVIEW_PARSE_BUDGET_CHARS 同值 24_000：
    /// server 侧发帧节流之外的客户端防线——超此值逐帧全量重解析边际收益为负）。
    static let previewParseBudgetChars = 24_000

    static func shouldContinuePreviewParsing(programLength: Int,
                                             budget: Int = OpenUiStreamingCardView.previewParseBudgetChars) -> Bool {
        programLength <= budget
    }

    @State private var snapshot: IosOpenUiCardSnapshotReady? = nil

    var body: some View {
        Group {
            if let snapshot {
                let ctx = OpenUiRenderContext(
                    state: OpenUiStateBag(snapshot.state),
                    onAction: { _ in }, // 流式预览只读（不写 state 不持久化）
                    onPhotoTap: { _ in },
                    resolvePhoto: { _ in nil }) // 空 resolver：Query 不取数（防按帧刷 DB）
                OpenUiNodeRenderer(node: snapshot.root, ctx: ctx)
            } else {
                // 未渲染成功 / 超预算：indeterminate 占位（等终结卡路径接管）
                OpenUiCardLoadingView()
            }
        }
        .task(id: program) {
            guard Self.shouldContinuePreviewParsing(programLength: program.count) else {
                snapshot = nil // 超预算：停逐帧重解析、清旧帧预览落占位
                return
            }
            // 解析失败（截断前缀未闭合）保留上一帧好预览，永不降级闪错
            if let ready = (try? await IosOpenUiPreviewFactory.shared.preparePreview(program: program))
                as? IosOpenUiCardSnapshotReady {
                snapshot = ready
            }
        }
    }
}
