import SwiftUI
import SharedKit

// MARK: - OpenUI 节点渲染器（openui P2 iOS 对等端）
//
// 组件覆盖 = OpenUiLibrary.componentNames 103 项全集（shared SSOT；本 switch 逐名分发，
// 未命中落 UnknownComponent 占位块——与引擎 UnknownComponent 占位节点同口径的前向兼容）。
// 视觉走 OpenUiTokens（Android 定稿镜像）；动作经 ctx.onAction 整链交宿主（iosMain 控制器执行）。

struct OpenUiNodeRenderer: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        content
            .accessibilityIdentifier("openui_node_\(node.type)")
    }

    @ViewBuilder private var content: some View {
        switch node.type {
        // ── 布局原语 ──
        case "Text": OpenUiTextComponent(node: node, ctx: ctx)
        case "Row": OpenUiRowComponent(node: node, ctx: ctx)
        case "Column": OpenUiColumnComponent(node: node, ctx: ctx)
        case "Card": OpenUiCardComponent(node: node, ctx: ctx)
        case "Grid": OpenUiGridComponent(node: node, ctx: ctx)
        case "Box": OpenUiBoxComponent(node: node, ctx: ctx)
        case "Spacer":
            let size = OpenUiTokens.spacing(ouText(OpenUiPropsBag(node: node).raw("size")))
            Color.clear.frame(width: size, height: size)
        case "Button": OpenUiButtonComponent(node: node, ctx: ctx)
        case "Icon": OpenUiIconComponent(node: node)
        case "Divider": OpenUiDividerComponent(node: node)
        case "Progress": OpenUiProgressComponent(node: node)
        case "List": OpenUiListComponent(node: node, ctx: ctx)
        case "Stack": OpenUiStackComponent(node: node, ctx: ctx)
        case "Separator": OpenUiSeparatorComponent(node: node)

        // ── 文本族 ──
        case "Markdown", "MarkDownRenderer": OpenUiMarkdownComponent(node: node, ctx: ctx)
        case "BoldText": OpenUiBoldTextComponent(node: node, ctx: ctx)
        case "Label": OpenUiLabelComponent(node: node)
        case "CodeBlock": OpenUiCodeBlockComponent(node: node)
        case "TextContent": OpenUiTextContentComponent(node: node, ctx: ctx)
        case "TextCallout": OpenUiTextCalloutComponent(node: node)
        case "Callout": OpenUiCalloutComponent(node: node)
        case "CardHeader": OpenUiCardHeaderComponent(node: node, ctx: ctx)
        case "InlineHeader": OpenUiInlineHeaderComponent(node: node)
        case "IconText": OpenUiIconTextComponent(node: node)
        case "ImageText": OpenUiImageTextComponent(node: node, ctx: ctx)
        case "ImageTextLarge": OpenUiImageTextLargeComponent(node: node, ctx: ctx)
        case "EntityList": OpenUiEntityListComponent(node: node)
        case "ListBlock": OpenUiListBlockComponent(node: node, ctx: ctx)
        case "ListItem": OpenUiListItemComponent(node: node, ctx: ctx)
        case "Steps": OpenUiStepsComponent(node: node)
        case "StepsItem": OpenUiStepsItemComponent(node: node, index: 1, isLast: true)
        case "Carousel": OpenUiCarouselComponent(node: node, ctx: ctx)
        case "Tag": OpenUiTagComponent(node: node)
        case "TagBlock": OpenUiTagBlockComponent(node: node)
        case "StatRow": OpenUiStatRowComponent(node: node)
        case "StatItem": OpenUiStatItemComponent(node: node)
        case "Banner": OpenUiBannerComponent(node: node, ctx: ctx)
        case "MetricIndicatorInline": OpenUiMetricInlineComponent(node: node)
        case "MetricIndicatorWithStrikethrough": OpenUiMetricStrikethroughComponent(node: node)
        case "SnippetCardBlock": OpenUiCardBlockComponent(node: node, ctx: ctx, kind: .snippet)
        case "SnippetCardItem": OpenUiCardBlockItemComponent(node: node, ctx: ctx, kind: .snippet)
        case "OverviewCardBlock": OpenUiCardBlockComponent(node: node, ctx: ctx, kind: .overview)
        case "OverviewCardItem": OpenUiCardBlockItemComponent(node: node, ctx: ctx, kind: .overview)
        case "ContextCardBlock": OpenUiCardBlockComponent(node: node, ctx: ctx, kind: .context)
        case "ContextCardItem": OpenUiCardBlockItemComponent(node: node, ctx: ctx, kind: .context)
        case "CompositeCardBlock": OpenUiCardBlockComponent(node: node, ctx: ctx, kind: .composite)
        case "CompositeCardItem": OpenUiCardBlockItemComponent(node: node, ctx: ctx, kind: .composite)
        case "VisualCardBlock": OpenUiCardBlockComponent(node: node, ctx: ctx, kind: .visual)
        case "VisualCardItem": OpenUiCardBlockItemComponent(node: node, ctx: ctx, kind: .visual)

        // ── 媒体族（OpenUiMediaComponents.swift） ──
        case "Image": OpenUiImageComponent(node: node, ctx: ctx)
        case "Video": OpenUiVideoComponent(node: node, ctx: ctx)
        case "PhotoGrid": OpenUiPhotoGridComponent(node: node, ctx: ctx)
        case "AvatarRow": OpenUiAvatarRowComponent(node: node, ctx: ctx)
        case "CompareRow": OpenUiCompareRowComponent(node: node, ctx: ctx)
        case "Timeline": OpenUiTimelineComponent(node: node, ctx: ctx)
        case "ImageBlock": OpenUiImageBlockComponent(node: node)
        case "ImageGallery": OpenUiImageGalleryComponent(node: node)

        // ── 图表族（OpenUiCharts.swift） ──
        case "BarChart": OpenUiBarChartComponent(node: node, ctx: ctx)
        case "PieChart": OpenUiPieChartComponent(node: node)
        case "LineChart": OpenUiLineChartComponent(node: node, area: false)
        case "AreaChart": OpenUiLineChartComponent(node: node, area: true)
        case "HorizontalBarChart": OpenUiHorizontalBarChartComponent(node: node)
        case "RadarChart": OpenUiRadarChartComponent(node: node)
        case "RadialChart": OpenUiRadialChartComponent(node: node)
        case "SingleStackedBarChart": OpenUiSingleStackedBarComponent(node: node)
        case "ScatterChart": OpenUiScatterChartComponent(node: node)
        case "ChartGrid": OpenUiChartGridComponent(node: node, ctx: ctx)
        case "Series", "Slice", "ScatterSeries", "Point":
            EmptyView() // 数据形状组件：仅父槽内有语义，独立出现空占位（Android 同口径）

        // ── 表单/交互族（OpenUiFormComponents.swift） ──
        case "CheckBox": OpenUiCheckBoxComponent(node: node, ctx: ctx)
        case "Switch": OpenUiSwitchComponent(node: node, ctx: ctx)
        case "ChoicePicker": OpenUiChoicePickerComponent(node: node, ctx: ctx)
        case "Slider": OpenUiSliderComponent(node: node, ctx: ctx)
        case "Table": OpenUiTableComponent(node: node)
        case "EditableTable": OpenUiEditableTableComponent(node: node, ctx: ctx)
        case "Form": OpenUiColumnComponent(node: node, ctx: ctx) // 表单壳 = 纵排容器
        case "FormControl": OpenUiFormControlComponent(node: node, ctx: ctx)
        case "Input": OpenUiInputComponent(node: node, ctx: ctx)
        case "TextArea": OpenUiTextAreaComponent(node: node, ctx: ctx)
        case "Select": OpenUiSelectComponent(node: node, ctx: ctx)
        case "SelectItem": EmptyView() // 数据形状
        case "DatePicker": OpenUiDatePickerComponent(node: node, ctx: ctx)
        case "CheckBoxGroup": OpenUiCheckBoxGroupComponent(node: node, ctx: ctx)
        case "CheckBoxItem": OpenUiCheckBoxItemStandaloneComponent(node: node, ctx: ctx)
        case "RadioGroup": OpenUiRadioGroupComponent(node: node, ctx: ctx)
        case "RadioItem": EmptyView() // 数据形状
        case "SwitchGroup": OpenUiSwitchGroupComponent(node: node, ctx: ctx)
        case "SwitchItem": OpenUiSwitchItemStandaloneComponent(node: node, ctx: ctx)
        case "Chips": OpenUiChipsComponent(node: node, ctx: ctx)
        case "ChipItem": EmptyView() // 数据形状
        case "OptionCards": OpenUiOptionCardsComponent(node: node, ctx: ctx)
        case "OptionCard": EmptyView() // 数据形状
        case "Buttons": OpenUiButtonsComponent(node: node, ctx: ctx)
        case "IconButton": OpenUiIconButtonComponent(node: node, ctx: ctx)
        case "Modal": OpenUiModalComponent(node: node, ctx: ctx)
        case "Tabs": OpenUiTabsComponent(node: node, ctx: ctx)
        case "Accordion": OpenUiAccordionComponent(node: node, ctx: ctx)
        case "TabItem", "AccordionItem": OpenUiTabItemComponent(node: node, ctx: ctx)
        case "Html": OpenUiHtmlComponent(node: node)

        default:
            OpenUiUnknownComponentPlaceholder(typeName: node.type)
        }
    }
}

// MARK: - 未知组件占位（前向兼容可见降级；langVersion 协商的对偶消费端）

struct OpenUiUnknownComponentPlaceholder: View {
    let typeName: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "puzzlepiece.extension")
                .font(.system(size: 14))
                .foregroundColor(Color(.secondaryLabel))
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "openui.unknown_component_hint"))
                    .font(.system(size: 12))
                    .foregroundColor(Color(.secondaryLabel))
                Text(typeName)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(Color(.tertiaryLabel))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemFill))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - children 渲染便捷

@ViewBuilder
func OpenUiChildren(_ children: [IosOpenUiVal], ctx: OpenUiRenderContext) -> some View {
    ForEach(Array(children.enumerated()), id: \.offset) { _, item in
        OpenUiChildItem(item: item, ctx: ctx)
    }
}

/// 单 child 分流：组件节点 → 渲染器；未知组件 → 占位；其余（数据形态混入）跳过。
struct OpenUiChildItem: View {
    let item: IosOpenUiVal
    let ctx: OpenUiRenderContext

    var body: some View {
        switch item {
        case let nodeVal as IosOpenUiValNodeVal:
            OpenUiNodeRenderer(node: nodeVal.node, ctx: ctx)
        case let unknown as IosOpenUiValUnknownNodeVal:
            OpenUiUnknownComponentPlaceholder(typeName: unknown.type)
        default:
            EmptyView()
        }
    }
}

// MARK: - 布局原语

private struct OpenUiTextComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        if let text = ouText(props.raw("text"), ctx.state) {
            let role = ouText(props.raw("role")) ?? "BODY"
            Text(text)
                .font(OpenUiTokens.font(role: role))
                .lineSpacing(OpenUiTokens.lineHeight(role: role) - fontSize(of: role))
                .foregroundColor(OpenUiTokens.colorRole(ouText(props.raw("colorRole"))))
                .fixedSize(horizontal: false, vertical: true)
                .lineLimit(ouNumber(props.raw("maxLines")).map(Int.init))
        } else {
            EmptyView() // 绑定解析不出丢组件不炸卡（Android 同口径）
        }
    }

    private func fontSize(of role: String) -> CGFloat {
        switch role {
        case "HEADLINE": return 20
        case "SUBHEAD": return 15
        case "CAPTION": return 12
        default: return 14
        }
    }
}

/// align 五值 → Row 纵向对齐（stretch = 子项撑满行高；baseline 首行基线）。
private enum OpenUiVAlign {
    case top, center, bottom, stretch, baseline

    init(_ raw: String?) {
        switch raw {
        case "center": self = .center
        case "end": self = .bottom
        case "stretch": self = .stretch
        case "baseline": self = .baseline
        default: self = .top // start / 缺省
        }
    }

    var hStackAlignment: VerticalAlignment {
        switch self {
        case .center: return .center
        case .bottom: return .bottom
        case .baseline: return .firstTextBaseline
        default: return .top
        }
    }
}

private enum OpenUiHAlign {
    case leading, center, trailing, stretch

    init(_ raw: String?) {
        switch raw {
        case "center": self = .center
        case "end": self = .trailing
        case "stretch": self = .stretch
        default: self = .leading
        }
    }

    var vStackAlignment: HorizontalAlignment {
        switch self {
        case .center: return .center
        case .trailing: return .trailing
        default: return .leading
        }
    }
}

/// Row weights 布局（CSS flex-grow 语义；Divider/Spacer 不占槽位，缺省/个数不符回退全 1）。
private struct OpenUiRowLayout: Layout {
    var spacing: CGFloat
    var weights: [Double]
    var align: OpenUiVAlign

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var weighted: [Double] = []
        var fixedWidth: CGFloat = 0
        var height: CGFloat = 0
        for (index, sub) in subviews.enumerated() {
            let size = sub.sizeThatFits(.unspecified)
            height = max(height, size.height)
            if index < weights.count && weights[index] > 0 {
                weighted.append(weights[index])
            } else {
                fixedWidth += size.width
            }
        }
        let totalWeight = weighted.reduce(0, +)
        let flexible = max(0, (width.isFinite ? width : 0) - fixedWidth - spacing * CGFloat(max(0, subviews.count - 1)))
        var finalHeight = height
        for (index, sub) in subviews.enumerated() {
            if index < weights.count && weights[index] > 0, totalWeight > 0 {
                let w = flexible * CGFloat(weights[index] / totalWeight)
                let subSize = sub.sizeThatFits(ProposedViewSize(width: w, height: nil))
                if align == .stretch {
                    finalHeight = max(finalHeight, subSize.height)
                } else {
                    finalHeight = max(finalHeight, subSize.height)
                }
            }
        }
        return CGSize(width: width.isFinite ? width : fixedWidth, height: finalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        // 先测固定子项占宽，剩余宽按权重分摊
        var weighted: [(Int, Double)] = []
        var x = bounds.minX
        var fixedTotal: CGFloat = 0
        for (index, sub) in subviews.enumerated() {
            let size = sub.sizeThatFittingWidth(.infinity, align: align, boundsHeight: bounds.height)
            if index < weights.count && weights[index] > 0 {
                weighted.append((index, weights[index]))
            } else {
                fixedTotal += size.width
            }
        }
        let totalWeight = weighted.map(\.1).reduce(0, +)
        let available = max(0, bounds.width - fixedTotal - spacing * CGFloat(max(0, subviews.count - 1)))
        for (index, sub) in subviews.enumerated() {
            let isWeighted = index < weights.count && weights[index] > 0 && totalWeight > 0
            let subWidth: CGFloat
            if isWeighted, let weight = weighted.first(where: { $0.0 == index })?.1 {
                subWidth = available * CGFloat(weight / totalWeight)
            } else {
                subWidth = sub.sizeThatFittingWidth(.infinity, align: align, boundsHeight: bounds.height).width
            }
            let subProposal = ProposedViewSize(width: subWidth, height: align == .stretch ? bounds.height : nil)
            let size = sub.sizeThatFits(subProposal)
            let y: CGFloat
            switch align {
            case .center: y = bounds.midY - size.height / 2
            case .bottom: y = bounds.maxY - size.height
            case .baseline, .stretch, .top: y = bounds.minY
            }
            sub.place(at: CGPoint(x: x, y: y), proposal: subProposal)
            x += subWidth + spacing
        }
    }
}

private extension LayoutSubview {
    /// 宽度不限时的理想尺寸（对齐 Android wrap-content）。
    func sizeThatFittingWidth(_ width: CGFloat, align: OpenUiVAlign, boundsHeight: CGFloat) -> CGSize {
        sizeThatFits(ProposedViewSize(width: width, height: align == .stretch ? boundsHeight : nil))
    }
}

struct OpenUiRowComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let children = ouChildren(props)
        let rawWeights = ouItems(props.raw("weights")).compactMap { ouNumber($0) }
        // 权重槽位与「非 Spacer/Separator/Divider」子项一一对应；个数不符回退全等宽
        let flexibleCount = children.filter { !isRigid($0) }.count
        let weights: [Double] = (rawWeights.count == flexibleCount && flexibleCount > 0)
            ? alignWeights(rawWeights, to: children)
            : []
        return OpenUiRowLayout(spacing: OpenUiTokens.spacing(ouText(props.raw("spacing"))),
                               weights: weights,
                               align: OpenUiVAlign(ouText(props.raw("align")))) {
            OpenUiChildren(children, ctx: ctx)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func isRigid(_ item: IosOpenUiVal) -> Bool {
        guard let nodeVal = item as? IosOpenUiValNodeVal else { return false }
        return nodeVal.node.type == "Spacer" || nodeVal.node.type == "Divider" || nodeVal.node.type == "Separator"
    }

    /// 把「灵活子项序」的权重展开为「全子项序」（刚性子项占 0 槽）。
    private func alignWeights(_ raw: [Double], to children: [IosOpenUiVal]) -> [Double] {
        var result: [Double] = []
        var cursor = 0
        for child in children {
            if isRigid(child) {
                result.append(0)
            } else if cursor < raw.count {
                result.append(raw[cursor])
                cursor += 1
            } else {
                result.append(1)
            }
        }
        return result
    }
}

struct OpenUiColumnComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let align = OpenUiHAlign(ouText(props.raw("align")))
        return VStack(alignment: align.vStackAlignment,
                      spacing: OpenUiTokens.spacing(ouText(props.raw("spacing")))) {
            OpenUiChildren(ouChildren(props), ctx: ctx)
        }
        .frame(maxWidth: .infinity, alignment: align == .stretch ? .leading : align.frameAlignment())
    }
}

private extension OpenUiHAlign {
    func frameAlignment() -> Alignment {
        switch self {
        case .center: return .center
        case .trailing: return .trailing
        default: return .leading
        }
    }
}

struct OpenUiCardComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let align = OpenUiHAlign(ouText(props.raw("align")))
        return VStack(alignment: align.vStackAlignment,
                      spacing: OpenUiTokens.spacing(ouText(props.raw("spacing")))) {
            if let title = ouText(props.raw("title")) {
                Text(title)
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            OpenUiChildren(ouChildren(props), ctx: ctx)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct OpenUiGridComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let columns = max(1, min(4, Int(ouNumber(props.raw("columns")) ?? 2)))
        let spacing = OpenUiTokens.spacing(ouText(props.raw("spacing")))
        let rows = chunk(ouChildren(props), into: columns)
        return VStack(alignment: .leading, spacing: spacing) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .top, spacing: spacing) {
                    ForEach(Array(row.enumerated()), id: \.offset) { _, item in
                        OpenUiChildItem(item: item, ctx: ctx)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chunk<T>(_ items: [T], into size: Int) -> [[T]] {
        guard size > 0 else { return [items] }
        return stride(from: 0, to: items.count, by: size).map { Array(items[$0..<min($0 + size, items.count)]) }
    }
}

struct OpenUiBoxComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        ZStack(alignment: boxAlignment(ouText(props.raw("align")))) {
            OpenUiChildren(ouChildren(props), ctx: ctx)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func boxAlignment(_ raw: String?) -> Alignment {
        switch raw {
        case "topCenter": return .top
        case "topEnd": return .topTrailing
        case "centerStart": return .leading
        case "center": return .center
        case "centerEnd": return .trailing
        case "bottomStart": return .bottomLeading
        case "bottomCenter": return .bottom
        case "bottomEnd": return .bottomTrailing
        default: return .topLeading
        }
    }
}

struct OpenUiStackComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let children = ouChildren(props)
        let gap = OpenUiTokens.spacing(ouText(props.raw("gap")))
        let wrap = ouBool(props.raw("wrap")) ?? false
        let isRow = ouText(props.raw("direction")) == "row"
        let align = OpenUiHAlign(ouText(props.raw("align")))
        return Group {
            if wrap {
                FlowLayout(horizontal: isRow ? gap : OpenUiTokens.spacing(nil),
                           vertical: isRow ? OpenUiTokens.spacing(nil) : gap) {
                    OpenUiChildren(children, ctx: ctx)
                }
            } else if isRow {
                HStack(alignment: OpenUiVAlign(ouText(props.raw("align")) == "stretch" ? "start" : ouText(props.raw("align"))).hStackAlignment,
                       spacing: gap) {
                    OpenUiChildren(children, ctx: ctx)
                }
                .frame(maxWidth: .infinity, alignment: justifyAlignment(props))
            } else {
                VStack(alignment: align.vStackAlignment, spacing: gap) {
                    OpenUiChildren(children, ctx: ctx)
                }
                .frame(maxWidth: .infinity, alignment: align == .stretch ? .leading : align.frameAlignment())
            }
        }
    }

    /// justify（主轴分布；Column 恒 wrap 高无意义，仅 Row 消费）。
    private func justifyAlignment(_ props: OpenUiPropsBag) -> Alignment {
        switch ouText(props.raw("justify")) {
        case "center": return .center
        case "end": return .trailing
        default: return .leading
        }
    }
}

struct OpenUiListComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let children = ouChildren(props)
        let spacing = OpenUiTokens.spacing(ouText(props.raw("spacing")))
        if ouText(props.raw("direction")) == "HORIZONTAL" {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: spacing) {
                    OpenUiChildren(children, ctx: ctx)
                }
            }
        } else {
            // VERTICAAL 限高 ~320 纵滚（对齐 Android maxHeight 护栏，防嵌套滚动事故）
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: spacing) {
                    OpenUiChildren(children, ctx: ctx)
                }
            }
            .frame(maxHeight: 320)
        }
    }
}

struct OpenUiDividerComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let horizontal = (ouText(OpenUiPropsBag(node: node).raw("axis")) ?? "HORIZONTAL") == "HORIZONTAL"
        Rectangle()
            .fill(Color(.label).opacity(OpenUiTokens.dividerAlpha))
            .frame(height: horizontal ? 0.5 : nil)
            .frame(maxWidth: horizontal ? .infinity : 0.5)
            .frame(maxHeight: horizontal ? 0.5 : .infinity)
    }
}

struct OpenUiSeparatorComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let horizontal = (ouText(OpenUiPropsBag(node: node).raw("orientation")) ?? "horizontal") == "horizontal"
        Rectangle()
            .fill(Color(.label).opacity(OpenUiTokens.dividerAlpha))
            .frame(height: horizontal ? 0.5 : nil)
            .frame(maxWidth: horizontal ? .infinity : 0.5)
            .frame(maxHeight: horizontal ? 0.5 : .infinity)
    }
}

// MARK: - 文本族组件

struct OpenUiMarkdownComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let text = ouText(props.raw(node.type == "Markdown" ? "text" : "textMarkdown"), ctx.state) ?? ""
        return MarkdownText(text: text)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiBoldTextComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let size = ouText(props.raw("size")) ?? "sm"
        let font: Font = {
            switch size {
            case "xs": return .system(size: 13, weight: .bold)
            case "md": return .system(size: 16, weight: .bold)
            case "lg": return .system(size: 18, weight: .bold)
            default: return .system(size: 14, weight: .bold) // sm
            }
        }()
        return HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(ouText(props.raw("value"), ctx.state) ?? "")
                .font(font)
                .foregroundColor(Color(.label))
            if let subtext = ouText(props.raw("subtext")) {
                Text(subtext)
                    .font(.system(size: 12))
                    .foregroundColor(Color(.secondaryLabel))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiLabelComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        Text(ouText(OpenUiPropsBag(node: node).raw("text")) ?? "")
            .font(.system(size: 13))
            .foregroundColor(Color(.secondaryLabel))
            .lineLimit(1)
    }
}

struct OpenUiCodeBlockComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        ScrollView(.horizontal, showsIndicators: false) {
            Text(ouText(props.raw("codeString")) ?? "")
                .font(.system(size: 13, design: .monospaced))
                .foregroundColor(Color(.label))
                .textSelection(.enabled)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.tertiarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

struct OpenUiTextContentComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let role: String = {
            switch ouText(props.raw("size")) ?? "default" {
            case "small": return "CAPTION"
            case "large": return "SUBHEAD"
            case "small-heavy": return "SUBHEAD"
            case "large-heavy": return "HEADLINE"
            default: return "BODY"
            }
        }()
        return Text(ouText(props.raw("text"), ctx.state) ?? "")
            .font(OpenUiTokens.font(role: role))
            .foregroundColor(Color(.label))
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiTextCalloutComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let tone = OpenUiTokens.tone(ouText(props.raw("variant")))
        return VStack(alignment: .leading, spacing: 4) {
            Text(ouText(props.raw("title")) ?? "")
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(tone.fg)
            if let description = ouText(props.raw("description")) {
                Text(description)
                    .font(.system(size: 13))
                    .foregroundColor(tone.fg.opacity(0.85))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tone.bg)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct OpenUiCalloutComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let tone = OpenUiTokens.tone(ouText(props.raw("tone")))
        return VStack(alignment: .leading, spacing: 4) {
            if let title = ouText(props.raw("title")) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(tone.fg)
            }
            Text(ouText(props.raw("text")) ?? "")
                .font(.system(size: 14))
                .foregroundColor(tone.fg.opacity(0.9))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tone.bg)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct OpenUiCardHeaderComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        return HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(ouText(props.raw("title")) ?? "")
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
                if let subtitle = ouText(props.raw("subtitle")) {
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundColor(Color(.secondaryLabel))
                }
            }
            Spacer(minLength: 8)
            if let actionLabel = ouText(props.raw("actionLabel")) {
                let actions = ouActions(props.raw("action"))
                Button {
                    if !actions.isEmpty { ctx.onAction(actions) }
                } label: {
                    HStack(spacing: 2) {
                        Text(actionLabel)
                            .font(.system(size: 13))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundColor(.accentColor)
                }
                .buttonStyle(.plain)
                .disabled(actions.isEmpty) // 无 action 仅展示文案
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiInlineHeaderComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        return VStack(alignment: .leading, spacing: 2) {
            Text(ouText(props.raw("title")) ?? "")
                .font(OpenUiTokens.font(role: "SUBHEAD"))
                .foregroundColor(Color(.label))
            if let description = ouText(props.raw("description")) {
                Text(description)
                    .font(.system(size: 13))
                    .foregroundColor(Color(.secondaryLabel))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiIconComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name"))
        let color = OpenUiTokens.colorRole(ouText(props.raw("colorRole")))
        let icon = Image(systemName: OpenUiTokens.systemImage(named: name))
            .font(.system(size: 18))
            .foregroundColor(color)
        if ouText(props.raw("container")) == "tonal" {
            icon
                .frame(width: 28, height: 28)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 7))
        } else {
            icon
        }
    }
}

struct OpenUiIconTextComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let title = ouText(props.raw("title")) ?? ""
        let bold = ouBool(props.raw("bold")) ?? false
        let titleView = Text(title)
            .font(.system(size: 14, weight: bold ? .semibold : .regular))
            .foregroundColor(Color(.label))
        let subtitleView = Group {
            if let subtitle = ouText(props.raw("subtitle")) {
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundColor(Color(.secondaryLabel))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        return Group {
            if ouText(props.raw("layout")) == "vertical" {
                VStack(alignment: .leading, spacing: 6) {
                    OpenUiIconComponent(node: node)
                    titleView
                    subtitleView
                }
            } else {
                HStack(spacing: 10) {
                    OpenUiIconComponent(node: node)
                    VStack(alignment: .leading, spacing: 2) {
                        titleView
                        subtitleView
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiStepsComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let items = ouItems(OpenUiPropsBag(node: node).raw("items"))
        return VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                OpenUiStepsRow(
                    title: ouText(ouObject(item)["title"]) ?? "",
                    details: ouText(ouObject(item)["details"]),
                    index: index + 1, isLast: index == items.count - 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiStepsItemComponent: View {
    let node: IosOpenUiNodeMirror
    let index: Int
    let isLast: Bool

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        OpenUiStepsRow(title: ouText(props.raw("title")) ?? "",
                       details: ouText(props.raw("details")),
                       index: index, isLast: isLast)
    }
}

private struct OpenUiStepsRow: View {
    let title: String
    let details: String?
    let index: Int
    let isLast: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(spacing: 0) {
                Text("\(index)")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.accentColor)
                    .frame(width: 20, height: 20)
                    .background(Circle().fill(Color.accentColor.opacity(0.12)))
                if !isLast {
                    Rectangle()
                        .fill(Color(.label).opacity(0.1))
                        .frame(width: 1.5)
                        .frame(maxHeight: .infinity)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14))
                    .foregroundColor(Color(.label))
                if let details, !details.isEmpty {
                    Text(details)
                        .font(.system(size: 12))
                        .foregroundColor(Color(.secondaryLabel))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.bottom, isLast ? 0 : 6)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiEntityListComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let rows = ouItems(props.raw("rows"))
        return VStack(alignment: .leading, spacing: 0) {
            if let headerLeft = ouText(props.raw("headerLeft")) {
                entityRow(left: headerLeft, right: ouText(props.raw("headerRight")), isHeader: true)
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                let obj = ouObject(row)
                entityRow(left: ouText(obj["left"]) ?? "", right: ouText(obj["right"]) ?? "",
                          isHeader: false, divider: index > 0)
            }
            if let footerLeft = ouText(props.raw("footerLeft")) {
                entityRow(left: footerLeft, right: ouText(props.raw("footerRight")), isFooter: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func entityRow(left: String, right: String?, isHeader: Bool = false, isFooter: Bool = false, divider: Bool = true) -> some View {
        VStack(spacing: 0) {
            if divider && !isHeader { Divider().opacity(0.5) }
            HStack {
                Text(left)
                    .font(.system(size: 13, weight: isHeader ? .semibold : .regular))
                    .foregroundColor(isHeader ? Color(.label) : Color(.label).opacity(0.85))
                Spacer(minLength: 8)
                if let right {
                    Text(right)
                        .font(.system(size: 13))
                        .foregroundColor(Color(.secondaryLabel))
                }
            }
            .padding(.vertical, 8)
        }
    }
}

struct OpenUiStatRowComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let items = ouItems(OpenUiPropsBag(node: node).raw("items")).prefix(6)
        return HStack(alignment: .top, spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                let obj = ouObject(item)
                OpenUiStatTile(value: ouText(obj["value"]) ?? "", label: ouText(obj["label"]))
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiStatItemComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        OpenUiStatTile(value: ouText(props.raw("value")) ?? "", label: ouText(props.raw("label")))
            .frame(maxWidth: .infinity)
    }
}

struct OpenUiStatTile: View {
    let value: String
    let label: String?

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(OpenUiTokens.font(role: "DISPLAY"))
                .foregroundColor(Color(.label))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            if let label, !label.isEmpty {
                Text(label)
                    .font(OpenUiTokens.font(role: "CAPTION"))
                    .foregroundColor(Color(.secondaryLabel))
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

struct OpenUiMetricInlineComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        metricBody(node: node, strikethrough: nil)
    }
}

struct OpenUiMetricStrikethroughComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        metricBody(node: node, strikethrough: ouText(OpenUiPropsBag(node: node).raw("previousValue")))
    }
}

@ViewBuilder private func metricBody(node: IosOpenUiNodeMirror, strikethrough: String?) -> some View {
    let props = OpenUiPropsBag(node: node)
    let direction = ouText(props.raw("trendDirection"))
    let trend = ouNumber(props.raw("trendValue"))
    HStack(spacing: 6) {
        if let strikethrough {
            Text(strikethrough)
                .font(.system(size: 13))
                .strikethrough()
                .foregroundColor(Color(.tertiaryLabel))
        }
        Text(ouText(props.raw("value")) ?? "")
            .font(.system(size: 16, weight: .semibold))
            .foregroundColor(Color(.label))
        if let direction {
            HStack(spacing: 2) {
                Image(systemName: direction == "down" ? "arrow.down.right" : "arrow.up.right")
                    .font(.system(size: 11, weight: .semibold))
                if let trend {
                    Text(formatTrend(trend))
                        .font(.system(size: 11, weight: .semibold))
                }
            }
            .foregroundColor(direction == "down" ? .green : .red)
        }
        if let subtext = ouText(props.raw("subtext")) {
            Text(subtext)
                .font(.system(size: 12))
                .foregroundColor(Color(.secondaryLabel))
        }
        Spacer(minLength: 0)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
}

private func formatTrend(_ value: Double) -> String {
    value.truncatingRemainder(dividingBy: 1) == 0
        ? String(format: "%.0f", value)
        : String(format: "%.1f", value)
}
