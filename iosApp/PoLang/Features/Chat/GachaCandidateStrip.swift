import SwiftUI
import UIKit
import SharedKit

// MARK: - AI 优化抽卡候选卡条（chat.yaml §17 strip_ui）
//
// 独立全宽消息（伴随 agent 文本气泡 = 场景解释句）：
// - 容器：surface 背景 / 圆角 12（AppRadius.card）/ padding 12；
// - 提示行三分支：expired（!interactive）/ keep（recommendedIndex<0）/ pick；
// - 4 卡均分：thumb 84 圆角 6（EditorTokens.gachaCard*），选中 2dp primary 描边，
//   推荐角标（top-start，primary 底白字），护栏淘汰卡 0.4 alpha 不可点，
//   方向名本地化（ai_optimize_direction_* 三语）；
// - 按钮行（仅 interactive）：换一组（文本）+ 就用这张（filled，选中未淘汰才可用）；
//   rerolling 时整行替换为 16pt spinner。
// 交互（§17 interaction_model）：点卡 → 选中 + 全屏预览（复用 ChatImagePreview，无保存按钮）。

/// 方向名 → 本地化文案（editor.yaml §17.3 direction_labels：
/// base/clarity/vivid/warm/cool/brighten/crisp → 原图基准/清晰/鲜艳/暖色/冷色/提亮/锐利；
/// 7 个 ai_optimize_direction_* key 已入 xcstrings 三语，未知方向兜底 base）。
enum GachaDirectionLabel {

    private static let keyByDirection: [String: String] = [
        "base": "ai_optimize_direction_base",
        "clarity": "ai_optimize_direction_clarity",
        "vivid": "ai_optimize_direction_vivid",
        "warm": "ai_optimize_direction_warm",
        "cool": "ai_optimize_direction_cool",
        "brighten": "ai_optimize_direction_brighten",
        "crisp": "ai_optimize_direction_crisp",
    ]

    static func label(for direction: String) -> String {
        let key = keyByDirection[direction] ?? "ai_optimize_direction_base"
        return String(localized: String.LocalizationValue(key))
    }
}

struct GachaCandidateStrip: View {
    /// 候选组（commonMain OptimizeCandidateGroup，M5 B1 切 shared SSOT）。
    /// index==数组位置不变量：candidate 无 index 字段，推荐位/选中位/确认位一律用
    /// 数组位置（enumerated offset）。
    let payload: OptimizeCandidateGroup
    /// 当前选中卡序号（nil=非 interactive 不显示选中态）；初值=recommendedIndex
    let selectedIndex: Int?
    /// pending 组存在（interactive）才出按钮行与选中态；过期转只读（expired 文案）
    let interactive: Bool
    /// 该消息重抽中（防抖 + 按钮行 spinner）
    let rerolling: Bool
    var onSelection: (Int) -> Void = { _ in }
    var onReroll: () -> Void = {}
    var onConfirm: () -> Void = {}
    /// 点卡全屏预览（携 thumbPath；上层加载 UIImage 后打开 ChatImagePreview）
    var onCardTap: (String?) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            hintText
            // 4 卡均分（row_weight1；等宽由各卡 maxWidth .infinity 实现）
            HStack(spacing: Spacing.sm) {
                // index==数组位置：enumerated offset 即卡序号
                ForEach(Array(payload.candidates.enumerated()), id: \.offset) { position, card in
                    GachaCandidateCard(
                        candidate: card,
                        position: position,
                        recommended: position == Int(payload.recommendedIndex),
                        // 选中态仅 interactive 时呈现（过期卡条只读）
                        selected: interactive && position == selectedIndex,
                        onTap: {
                            // 淘汰卡不可点；过期卡仍可预览但不改选中
                            guard !card.rejected else { return }
                            if interactive {
                                onSelection(position)
                            }
                            onCardTap(card.thumbPath.isEmpty ? nil : card.thumbPath)
                        })
                }
            }
            if interactive {
                buttonsRow
            }
        }
        .padding(Spacing.md)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.card))
        .accessibilityIdentifier("chat_gacha_strip")
    }

    // MARK: - 提示行（三分支）

    private var hintText: some View {
        Text(String(localized: String.LocalizationValue(hintKey)))
            .font(.system(size: 12))
            .foregroundColor(Color(.secondaryLabel))
    }

    private var hintKey: String {
        if !interactive { return "chat_gacha_expired" }
        return payload.recommendedIndex < 0 ? "ai_optimize_keep_hint" : "chat_gacha_pick_hint"
    }

    // MARK: - 按钮行（仅 interactive；rerolling 时整行换 spinner）

    private var buttonsRow: some View {
        HStack(spacing: Spacing.md) {
            Spacer()
            if rerolling {
                ProgressView()
                    .frame(width: 16, height: 16)
            } else {
                Button {
                    onReroll()
                } label: {
                    Text(String(localized: "ai_optimize_reroll"))
                        .font(.system(size: 14))
                        .foregroundColor(.accentColor)
                }
                .accessibilityIdentifier("chat_gacha_reroll")

                Button {
                    onConfirm()
                } label: {
                    Text(String(localized: "chat_gacha_use_this"))
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.white)
                        .padding(.horizontal, Spacing.lg)
                        .padding(.vertical, Spacing.xs + 2)
                        .background(
                            RoundedRectangle(cornerRadius: AppRadius.button)
                                .fill(Color.accentColor.opacity(canConfirm ? 1.0 : 0.4))
                        )
                }
                .disabled(!canConfirm)
                .accessibilityIdentifier("chat_gacha_use_this")
            }
        }
    }

    /// enabled_when: selectedIndex >= 0 && !selectedRejected（§17 buttons_row；
    /// index==数组位置不变量 → indices.contains 直接下标）
    private var canConfirm: Bool {
        guard let selectedIndex, payload.candidates.indices.contains(selectedIndex) else {
            return false
        }
        return !payload.candidates[selectedIndex].rejected
    }
}

// MARK: - 单卡（thumb + 推荐角标 + 方向名 + 选中描边）

private struct GachaCandidateCard: View {
    let candidate: OptimizeCandidateGroup.Candidate
    /// 卡序号（== 数组位置，无 index 字段）
    let position: Int
    /// position == recommendedIndex（top-start 角标）
    let recommended: Bool
    /// 选中卡（2dp primary 描边）
    let selected: Bool
    var onTap: () -> Void
    @State private var thumb: UIImage?

    var body: some View {
        VStack(spacing: Spacing.xs) {
            ZStack(alignment: .topLeading) {
                thumbView
                    // row_weight1 4 卡均分：卡格等宽、thumb 1:1 fit（上限 84=token 名义值；
                    // chat 行宽窄于编辑器全宽时格子自适应收缩，不溢出行界）
                    .aspectRatio(1, contentMode: .fit)
                    .frame(maxWidth: .infinity, maxHeight: EditorTokens.gachaCardThumbSize)
                    .clipShape(RoundedRectangle(cornerRadius: EditorTokens.gachaCardThumbCornerRadius))
                // 推荐角标（index == recommendedIndex，top-start，primary 底 labelSmall）
                if recommended {
                    Text(String(localized: "ai_optimize_recommended"))
                        .font(.system(size: 10))
                        .foregroundColor(.white)
                        .padding(.horizontal, Spacing.xs)
                        .padding(.vertical, 2)
                        .background(Color.accentColor)
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                        .padding(3)
                }
            }
            // 方向名（labelSmall_onSurfaceVariant）
            Text(GachaDirectionLabel.label(for: candidate.direction))
                .font(.system(size: 10))
                .foregroundColor(Color(.secondaryLabel))
                .lineLimit(1)
        }
        .padding(Spacing.xs)
        // 护栏淘汰卡：0.4 alpha + 不可点（onTap 内拒）
        .opacity(candidate.rejected ? 0.4 : 1.0)
        .overlay(
            // 选中描边：2dp primary（卡容器圆角 gachaCardCornerRadius）
            RoundedRectangle(cornerRadius: EditorTokens.gachaCardCornerRadius)
                .stroke(selected ? Color.accentColor : Color.clear, lineWidth: 2)
        )
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture {
            guard !candidate.rejected else { return }
            onTap()
        }
        .accessibilityIdentifier("chat_gacha_card_\(position)")
        .task(id: candidate.thumbPath) {
            // thumbPath 空串 → 占位图（落盘失败卡）；reroll 换卡时清残影；
            // 512px JPEG 解码放 .task 避免卡首帧
            thumb = candidate.thumbPath.isEmpty ? nil : UIImage(contentsOfFile: candidate.thumbPath)
        }
    }

    @ViewBuilder private var thumbView: some View {
        if let thumb {
            Image(uiImage: thumb)
                .resizable()
                .scaledToFill()
        } else {
            ZStack {
                Color(.tertiarySystemBackground)
                Image(matIcon: "photo")
                    .font(.system(size: 20))
                    .foregroundColor(Color(.label).opacity(0.25))
            }
        }
    }
}
