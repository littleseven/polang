import Foundation
import UIKit
import ImageIO
import CoreImage
import SharedKit

// MARK: - Chat AI 优化抽卡控制器（chat.yaml §17 interaction_model 执行端）
//
// 职责：
// - pending 卡组进程级内存态（pendingGroups：messageId → 组）；
//   切会话/发新消息/清空/删会话/进程回收即过期（expired_semantics），
//   过期后卡条转只读（ChatView interactive=false → expired 文案、无按钮行）。
// - draw：ChatUiActionDto(kind=ai_optimize) → 解析目标图 → AiOptimizeService.optimizeWithGacha
//   （savedTo=Documents/chat_edit_cache 落 512px 候选缩略图）→ selected/keepOriginal 建组，
//   unavailable / 缩略图全灭 → fallback 单发（固定预设全尺寸渲染落盘成 agent 图消息，或纯文本）。
// - reroll：以 usedFingerprints 为 exclude 重抽 → 覆写 pending + drawIndex+1。
// - confirm：**先摘除 pending 再渲染**（防 dismiss/user 双落库）→ toEditRecipe 全尺寸渲染
//   （RecipeApplier 直渲，长边 2048 上限）→ Documents/chat_edits/<uuid>.jpg → 反馈落库
//   source=user；失败回填 pending 保持可重试。
// - discardPending：落库 source=dismiss 后移除。
//
// 引擎与数值契约：specs/screens/editor.yaml §17（chat 复用同一引擎，零改动）。
// [PRIVACY] 全链路端侧（场景分析/渲染/NIMA 评分），无媒体上传。

/// pending 卡组（进程级内存态）。messageId = commonMain ChatMessage.id（String）。
struct GachaPendingGroup {
    let messageId: String
    let sessionId: String
    /// LLM 传入的目标图标识（payload 持久化用，reroll 覆写时保持不变）。
    let sourceImageUri: String
    /// 解析后的本地源图文件（draw/reroll/confirm 共用）。
    let sourceImageFile: URL
    /// 反馈落库稳定图标识（LLM 传的 PHAsset id / 原始路径；临时导出文件路径含 UUID 不落库）。
    let sourceImageKey: String
    var scene: OptimizeScene
    /// 候选卡（已剥离 thumbnail UIImage，只留 thumbPath 落盘路径，控内存；preset 保留供 confirm）。
    var scored: [ScoredCandidate]
    /// 本次已出现的参数指纹（reroll 时回传 exclude 去重）。
    var usedFingerprints: Set<String>
    /// 第几抽（首抽 1 对齐 Android OptimizeCandidateGroup；换一组 +1）。
    var drawIndex: Int
}

/// imageUri → 本地文件解析工具（ChatOptimizeGachaController 与 AiOptimizeBridge 共用）。
///
/// 支持三种形态（对齐 ChatUiActionDto.imageUri 注释：PHAsset id / file:// 路径 / 媒体 URI）：
/// - file:// URL / 已存在的裸路径 → 直接用；
/// - PHAsset localIdentifier → fullResolution 导出临时文件（场景分析与渲染均需文件输入）；
/// - 其余 → nil（调用方走会话最近用户图/纯文本兜底链）。
/// [PRIVACY] isNetworkAccessAllowed=false（ThumbnailLoader 内建），100% 端侧。
enum ChatImageUriResolver {

    private static let tag = "[PoLang:ChatGacha]"

    /// 候选缩略图/源图导出目录（Documents/chat_edit_cache，与 AiOptimizeService.savedTo 契约一致）。
    static var thumbnailCacheDirectory: URL? {
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        return docs.appendingPathComponent("chat_edit_cache", isDirectory: true)
    }

    static func resolve(_ imageUri: String) async -> URL? {
        let trimmed = imageUri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        // file:// URL 或已存在的裸路径
        if trimmed.hasPrefix("file://"), let url = URL(string: trimmed), url.isFileURL {
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            return url
        }
        if FileManager.default.fileExists(atPath: trimmed) {
            return URL(fileURLWithPath: trimmed)
        }

        // PHAsset localIdentifier：导出原图到 chat_edit_cache 临时文件
        guard let image = await ThumbnailLoader.shared.fullResolution(for: trimmed),
              let data = image.jpegData(compressionQuality: 0.95),
              let dir = thumbnailCacheDirectory else {
            NSLog("%@ resolve: not a file path or PHAsset id: %@", tag, trimmed)
            return nil
        }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("src-\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
            return url
        } catch {
            NSLog("%@ resolve: export write failed: %@", tag, error.localizedDescription)
            return nil
        }
    }
}

@MainActor
final class ChatOptimizeGachaController {

    static let shared = ChatOptimizeGachaController()

    private static let tag = "[PoLang:ChatGacha]"

    /// 反馈落库（auto=每组抽完已由 AiOptimizeService 落；此处只落 user/dismiss）。
    private let feedbackLogger = OptimizeFeedbackLogger()

    /// messageId → pending 组（进程级内存态，spec §17 expired_semantics；key=ChatMessage.id String）。
    private(set) var pendingGroups: [String: GachaPendingGroup] = [:]

    // MARK: - draw（首次抽卡）

    enum DrawOutcome {
        /// selected / keepOriginal → 建组出卡条（payload=SharedKit OptimizeCandidateGroup
        /// 供消息持久化与 parts；explanation=场景解释句）。
        case candidates(payload: OptimizeCandidateGroup, explanation: String)
        /// unavailable / 缩略图全灭 / 无可用源图 → 单发降级（imagePath=nil 纯文本解释）。
        case fallback(imagePath: String?, explanation: String)
    }

    enum RerollOutcome {
        case replaced(payload: OptimizeCandidateGroup, explanation: String)
        case unavailable
    }

    /// 首抽（chat.yaml §17 trigger：capability 仅产 observation，gacha 由 UI 层触发）。
    ///
    /// - Parameters:
    ///   - messageId: 待插入的 optimizeCandidates 消息 id（由 ViewModel 预生成）
    ///   - imageUri: LLM 传的目标图标识；解析失败回退 fallbackImageUri（会话最近用户图）
    ///   - sessionId: 归属会话（discardPending 按会话过期）
    ///   - fallbackImageUri: 会话最近一张用户图标识（兜底链）
    func draw(messageId: String, imageUri: String, sessionId: String, fallbackImageUri: String?) async -> DrawOutcome {
        // 1. 目标图解析（file:// / 裸路径 / PHAsset id → 失败回退会话最近用户图）
        var resolvedUri = imageUri
        var sourceFile = await ChatImageUriResolver.resolve(imageUri)
        if sourceFile == nil, let fallbackImageUri, !fallbackImageUri.isEmpty {
            sourceFile = await ChatImageUriResolver.resolve(fallbackImageUri)
            resolvedUri = fallbackImageUri
        }
        guard let sourceFile else {
            NSLog("%@ draw: no resolvable image (uri=%@)", Self.tag, imageUri)
            return .fallback(imagePath: nil, explanation: String(localized: "ai_optimize_not_available"))
        }

        // 2. 抽卡（savedTo=chat_edit_cache 落 512px 候选缩略图，C-G4）
        let imageKey = resolvedUri.isEmpty ? sourceFile.path : resolvedUri
        let outcome = await AiOptimizeService.shared.optimizeWithGacha(
            imageFile: sourceFile,
            savedTo: ChatImageUriResolver.thumbnailCacheDirectory,
            imageKey: imageKey)
        let explanation = String(localized: String.LocalizationValue(outcome.explanationKey))

        let recommendedIndex: Int
        let cards: [ScoredCandidate]
        switch outcome.result {
        case .selected(let best, let all, _):
            // ★ index==position 不变量（payload 候选无 index 字段，位置即序号）：
            // 推荐位 = best 在卡组中的数组位置
            recommendedIndex = all.firstIndex { $0.candidate.index == best.candidate.index } ?? 0
            cards = all
        case .keepOriginal(let all, _):
            recommendedIndex = -1
            cards = all
        case .unavailable:
            // fallback_chain 第 1 条：NIMA 未下载/初始化失败 → 固定预设单发优化
            let path = await Self.renderAndWrite(recipe: outcome.editRecipe, source: sourceFile)
            return .fallback(imagePath: path, explanation: explanation)
        }

        // fallback_chain 第 2 条：缩略图全部落盘失败 → Fallback 单发（含图或纯文本解释）
        if cards.allSatisfy({ card in card.thumbPath == nil }) {
            let path = await Self.renderAndWrite(recipe: outcome.editRecipe, source: sourceFile)
            return .fallback(imagePath: path, explanation: explanation)
        }

        // 3. 建组（selected 与 keepOriginal 都出卡条；recommendedIndex=-1 即 KeepOriginal 不预选）
        let scored = Self.stripThumbnails(cards)
        let group = GachaPendingGroup(
            messageId: messageId,
            sessionId: sessionId,
            sourceImageUri: imageUri.isEmpty ? sourceFile.path : imageUri,
            sourceImageFile: sourceFile,
            sourceImageKey: imageKey,
            scene: outcome.scene,
            scored: scored,
            usedFingerprints: outcome.usedFingerprints,
            drawIndex: 1)
        pendingGroups[messageId] = group

        let payload = Self.makePayload(group: group, recommendedIndex: recommendedIndex)
        NSLog("%@ draw: group ready (message=%@, scene=%@, cards=%d)",
              Self.tag, messageId, outcome.scene.rawValue, scored.count)
        return .candidates(payload: payload, explanation: explanation)
    }

    // MARK: - reroll（换一组）

    /// 以 pending.usedFingerprints 为 exclude 重抽 → 覆写 pending（drawIndex+1）。
    /// 引擎 unavailable / 新组缩略图全灭 → .unavailable（调用方 toast，pending 保持不动）。
    func reroll(messageId: String) async -> RerollOutcome {
        guard var group = pendingGroups[messageId] else {
            NSLog("%@ reroll: no pending group (message=%@)", Self.tag, messageId)
            return .unavailable
        }

        let outcome = await AiOptimizeService.shared.optimizeWithGacha(
            imageFile: group.sourceImageFile,
            savedTo: ChatImageUriResolver.thumbnailCacheDirectory,
            exclude: group.usedFingerprints,
            imageKey: group.sourceImageKey)

        let recommendedIndex: Int
        let cards: [ScoredCandidate]
        switch outcome.result {
        case .selected(let best, let all, _):
            // ★ index==position 不变量：推荐位 = best 在新卡组中的数组位置
            recommendedIndex = all.firstIndex { $0.candidate.index == best.candidate.index } ?? 0
            cards = all
        case .keepOriginal(let all, _):
            recommendedIndex = -1
            cards = all
        case .unavailable:
            NSLog("%@ reroll: engine unavailable (message=%@)", Self.tag, messageId)
            return .unavailable
        }
        if cards.allSatisfy({ card in card.thumbPath == nil }) {
            return .unavailable
        }

        // 覆写 pending（drawIndex+1；指纹并集已由 Service 返回）
        group.scored = Self.stripThumbnails(cards)
        group.usedFingerprints = outcome.usedFingerprints
        group.scene = outcome.scene
        group.drawIndex += 1
        pendingGroups[messageId] = group

        let explanation = String(localized: String.LocalizationValue(outcome.explanationKey))
        return .replaced(payload: Self.makePayload(group: group, recommendedIndex: recommendedIndex),
                         explanation: explanation)
    }

    // MARK: - confirm（就用这张）

    /// 确认应用候选卡：**先摘除 pending 再渲染**（渲染期间收到 discard 也不会双落库）；
    /// 成功返回 Documents/chat_edits/<uuid>.jpg 路径；失败回填 pending 保持可重试并返回 nil。
    func confirm(messageId: String, candidateIndex: Int) async -> String? {
        guard let group = pendingGroups[messageId] else {
            NSLog("%@ confirm: no pending group (message=%@)", Self.tag, messageId)
            return nil
        }
        // ★ index==position 不变量：candidateIndex 是卡组数组位置（payload 候选无 index 字段，
        // UI 选中/推荐位均按位置传递）
        guard group.scored.indices.contains(candidateIndex),
              !group.scored[candidateIndex].rejected else {
            NSLog("%@ confirm: invalid card index=%d (message=%@)",
                  Self.tag, candidateIndex, messageId)
            return nil
        }
        let card = group.scored[candidateIndex]

        pendingGroups.removeValue(forKey: messageId)

        let recipe = OptimizeRecipeMapper.toEditRecipe(
            preset: card.candidate.preset,
            sourceUri: group.sourceImageFile.path)
        guard let path = await Self.renderAndWrite(recipe: recipe, source: group.sourceImageFile) else {
            pendingGroups[messageId] = group   // 回填，保持卡条可重试（toast 由调用方出）
            return nil
        }

        feedbackLogger.log(imageUri: group.sourceImageKey,
                           scene: group.scene,
                           all: group.scored,
                           selectedIndex: candidateIndex,
                           source: OptimizeFeedbackLogger.sourceUser)
        NSLog("%@ confirm: applied card=%d -> %@ (message=%@)",
              Self.tag, candidateIndex, path, messageId)
        return path
    }

    // MARK: - discard（过期）

    /// 废弃会话内 pending 组（切会话/发新消息/清空/删会话）：落库 source=dismiss 后移除。
    /// exceptMessageId：confirm 成功路径此时已摘除自身，一般传 nil。
    func discardPending(sessionId: String, exceptMessageId: String? = nil) {
        let targets = pendingGroups.filter { entry in
            entry.value.sessionId == sessionId && entry.key != exceptMessageId
        }
        for (id, group) in targets {
            feedbackLogger.log(imageUri: group.sourceImageKey,
                               scene: group.scene,
                               all: group.scored,
                               selectedIndex: -1,
                               source: OptimizeFeedbackLogger.sourceDismiss)
            pendingGroups.removeValue(forKey: id)
        }
        if !targets.isEmpty {
            NSLog("%@ discardPending: session=%@, dismissed=%d", Self.tag, sessionId, targets.count)
        }
    }

    /// 卡条 interactive 判定（pending 存在；过期即只读，spec §17 expired 文案/无按钮行）。
    func hasPending(_ messageId: String) -> Bool {
        pendingGroups[messageId] != nil
    }

    // MARK: - 内部工具

    /// 剥离 thumbnail UIImage（只留 thumbPath 落盘路径），preset/评分保留供 confirm 与落库。
    private static func stripThumbnails(_ cards: [ScoredCandidate]) -> [ScoredCandidate] {
        cards.map { card -> ScoredCandidate in
            var out = card
            out.thumbnail = nil
            return out
        }
    }

    /// pending 组 → 消息 payload（SharedKit OptimizeCandidateGroup，结构照 chat.yaml §17
    /// message_model.payload；★ index==position 不变量——candidates 顺序即卡组顺序，
    /// recommendedIndex 为数组位置，候选本体无 index 字段）。
    private static func makePayload(group: GachaPendingGroup, recommendedIndex: Int) -> OptimizeCandidateGroup {
        OptimizeCandidateGroup(
            sourceImageUri: group.sourceImageUri,
            scene: group.scene.rawValue,
            recommendedIndex: Int32(recommendedIndex),
            candidates: group.scored.map { card in
                OptimizeCandidateGroup.Candidate(
                    direction: card.candidate.direction,
                    thumbPath: card.thumbPath ?? "",
                    nimaScore: card.nimaScore.map { KotlinFloat(float: $0) },
                    rejected: card.rejected)
            },
            usedFingerprints: group.usedFingerprints.sorted(),
            drawIndex: Int32(group.drawIndex))
    }

    /// EditRecipe 全尺寸渲染并写 Documents/chat_edits/<uuid>.jpg（长边 2048 上限，jpeg 0.92）；
    /// recipe=nil（KeepOriginal 降级无配方）或任一步失败 → nil（调用方走纯文本）。
    static func renderAndWrite(recipe: EditRecipe?, source: URL) async -> String? {
        guard let recipe else { return nil }
        guard let image = await renderRecipe(recipe, source: source),
              let data = image.jpegData(compressionQuality: 0.92) else { return nil }
        return writeChatEditFile(data)
    }

    /// 全尺寸渲染（RecipeApplier 直渲；解码长边 2048 上限防内存峰值，
    /// 模式对齐 PhotoEditorViewModel.render 的 Task.detached 后台渲染）。
    static func renderRecipe(_ recipe: EditRecipe, source: URL) async -> UIImage? {
        let sourceUrl = source
        return await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let src = CGImageSourceCreateWithURL(sourceUrl as CFURL, nil) else { return nil }
            let options: [CFString: Any] = [
                kCGImageSourceThumbnailMaxPixelSize: 2048,
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
            ]
            guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) else { return nil }
            let input = CIImage(cgImage: cg)
            let output = RecipeApplier.apply(recipe, to: input)
            guard let outCg = RecipeApplier.context.createCGImage(output, from: output.extent) else { return nil }
            return UIImage(cgImage: outCg)
        }.value
    }

    /// 编辑结果落盘 Documents/chat_edits/<uuid>.jpg（目录懒创建）；失败返回 nil。
    /// 模式对齐 PhotoEditorViewModel.writeChatEditFile（该处 private，编辑器文件由另一任务
    /// 负责、本任务不可改，故此处独立实现；后续收尾可抽公共工具去重）。
    static func writeChatEditFile(_ data: Data) -> String? {
        let fm = FileManager.default
        guard let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let dir = docs.appendingPathComponent("chat_edits", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
            return url.path
        } catch {
            NSLog("%@ writeChatEditFile failed: %@", Self.tag, error.localizedDescription)
            return nil
        }
    }
}
