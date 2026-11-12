import SwiftUI
import SharedKit

// MARK: - OpenUI 图表族（SwiftUI 原生绘制；色板/细节 token 对齐 Android OpenUiCardTokens）

// MARK: - BarChart（buckets / labels+series 双数据面；variant grouped|stacked；selected 绑定点选）

struct OpenUiBarChartComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let title = ouText(props.raw("title"))
        let buckets = ouItems(props.raw("buckets")).map { ouObject($0) }
        let labels = ouItems(props.raw("labels")).compactMap { ouText($0) }
        let series = ouItems(props.raw("series")).map { ouObject($0) }
        return VStack(alignment: .leading, spacing: 8) {
            if let title {
                Text(title)
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
            }
            if !buckets.isEmpty {
                bucketBars(buckets: buckets, props: props)
            } else if !labels.isEmpty && !series.isEmpty {
                seriesBars(labels: labels, series: series,
                           stacked: ouText(props.raw("variant")) == "stacked")
            } else {
                chartPlaceholder() // 双数据面皆缺：占位不炸卡
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 单系列桶形（selected 绑定点选 → @Set，Android BarChart 统计卡主形态）。
    @ViewBuilder private func bucketBars(buckets: [[String: IosOpenUiVal]], props: OpenUiPropsBag) -> some View {
        let selectedBinding = ouBindingName(props.raw("selected"))
        let selected = ouText(props.raw("selected"), ctx.state)
        let maxCount = buckets.compactMap { ouNumber($0["count"]) }.max() ?? 1
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(buckets.enumerated()), id: \.offset) { _, obj in
                let label = ouText(obj["label"]) ?? ""
                let count = ouNumber(obj["count"]) ?? 0
                let isSelected = selected != nil && selected == label
                Button {
                    guard let selectedBinding, !label.isEmpty else { return }
                    ctx.onAction([ouSetAction(target: selectedBinding, string: label)])
                } label: {
                    HStack(spacing: 8) {
                        Text(label)
                            .font(OpenUiTokens.font(role: "CAPTION"))
                            .foregroundColor(Color(.secondaryLabel))
                            .frame(width: 44, alignment: .leading)
                            .lineLimit(1)
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule()
                                    .fill(Color(.label).opacity(OpenUiTokens.barTrackAlpha))
                                Capsule()
                                    .fill(isSelected ? Color.accentColor : OpenUiTokens.chartSingleSeries)
                                    .frame(width: max(4, geo.size.width * CGFloat(count / max(maxCount, 1))))
                            }
                        }
                        .frame(height: 14)
                        Text("\(Int(count))")
                            .font(OpenUiTokens.font(role: "CAPTION"))
                            .foregroundColor(isSelected ? Color.accentColor : Color(.secondaryLabel))
                            .frame(width: 40, alignment: .trailing)
                            .monospacedDigit()
                    }
                }
                .buttonStyle(.plain)
                .disabled(selectedBinding == nil)
            }
        }
    }

    /// labels+series 多系列（grouped 并排 / stacked 堆叠）。
    @ViewBuilder private func seriesBars(labels: [String], series: [[String: IosOpenUiVal]], stacked: Bool) -> some View {
        let datasets = series.map { obj -> (name: String, values: [Double]) in
            (ouText(obj["name"]) ?? "",
             ouItems(obj["values"]).map { ouNumber($0) ?? 0 })
        }
        let maxValue = datasets.flatMap(\.values).max() ?? 1
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(labels.enumerated()), id: \.offset) { index, label in
                HStack(spacing: 8) {
                    Text(label)
                        .font(OpenUiTokens.font(role: "CAPTION"))
                        .foregroundColor(Color(.secondaryLabel))
                        .frame(width: 44, alignment: .leading)
                        .lineLimit(1)
                    Group {
                        if stacked {
                            GeometryReader { geo in
                                HStack(spacing: 1) {
                                    let total = datasets.reduce(0) { $0 + ($1.values.indices.contains(index) ? $1.values[index] : 0) }
                                    ForEach(Array(datasets.enumerated()), id: \.offset) { setIndex, dataset in
                                        let value = dataset.values.indices.contains(index) ? dataset.values[index] : 0
                                        Rectangle()
                                            .fill(OpenUiTokens.chartPalette[setIndex % OpenUiTokens.chartPalette.count])
                                            .frame(width: total > 0 ? geo.size.width * CGFloat(value / total) : 0)
                                    }
                                }
                            }
                        } else {
                            GeometryReader { geo in
                                HStack(alignment: .bottom, spacing: 3) {
                                    ForEach(Array(datasets.enumerated()), id: \.offset) { setIndex, dataset in
                                        let value = dataset.values.indices.contains(index) ? dataset.values[index] : 0
                                        RoundedRectangle(cornerRadius: OpenUiTokens.barCornerRadius)
                                            .fill(OpenUiTokens.chartPalette[setIndex % OpenUiTokens.chartPalette.count])
                                            .frame(width: max(4, (geo.size.width - CGFloat(datasets.count - 1) * 3) / CGFloat(datasets.count)),
                                                   height: max(4, geo.size.height * CGFloat(value / max(maxValue, 1))))
                                    }
                                }
                            }
                        }
                    }
                    .frame(height: stacked ? 14 : 56)
                }
            }
            chartLegend(names: datasets.map(\.name))
        }
    }
}

// MARK: - PieChart（donut 环形 / pie 实心）

struct OpenUiPieChartComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let slices = ouItems(props.raw("slices")).map { ouObject($0) }
        let entries = slices.compactMap { obj -> (String, Double)? in
            guard let label = ouText(obj["label"]) else { return nil }
            return (label, ouNumber(obj["value"]) ?? 0)
        }
        return VStack(alignment: .leading, spacing: 8) {
            if let title = ouText(props.raw("title")) {
                Text(title)
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
            }
            if entries.isEmpty {
                chartPlaceholder()
            } else {
                let donut = ouText(props.raw("variant")) != "pie"
                let total = entries.map(\.1).reduce(0, +)
                HStack(spacing: 16) {
                    ZStack {
                        PieSlicesView(entries: entries)
                        if donut {
                            // 中心打孔 + 总值（孔底色对齐卡面；donut 语义 Android 同款中心总值）
                            Circle()
                                .fill(Color(.secondarySystemBackground))
                                .frame(width: 120 - OpenUiTokens.pieRingWidth * 2,
                                       height: 120 - OpenUiTokens.pieRingWidth * 2)
                            Text("\(Int(total))")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(Color(.label))
                                .monospacedDigit()
                        }
                    }
                    .frame(width: 120, height: 120)
                    chartLegend(names: entries.map(\.0))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 逐扇着色饼图（0 起点顺时针；末扇区闭圆消缝）。
struct PieSlicesView: View {
    let entries: [(String, Double)]

    var body: some View {
        let total = entries.map(\.1).reduce(0, +)
        ZStack {
            ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
                let start = entries[0..<index].map(\.1).reduce(0, +) / max(total, 1)
                let end = start + entry.1 / max(total, 1)
                PieSliceShape(startFraction: start, endFraction: end)
                    .fill(OpenUiTokens.chartPalette[index % OpenUiTokens.chartPalette.count])
            }
        }
    }
}

struct PieSliceShape: Shape {
    let startFraction: Double
    let endFraction: Double

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        path.move(to: center)
        path.addArc(center: center, radius: radius,
                    startAngle: .degrees(-90 + startFraction * 360),
                    endAngle: .degrees(-90 + endFraction * 360),
                    clockwise: false)
        path.closeSubpath()
        return path
    }
}

// MARK: - LineChart / AreaChart（labels + series[].values）

struct OpenUiLineChartComponent: View {
    let node: IosOpenUiNodeMirror
    var area: Bool

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let labels = ouItems(props.raw("labels")).compactMap { ouText($0) }
        let series = ouItems(props.raw("series")).map { ouObject($0) }
        let step = ouText(props.raw("variant")) == "step"
        return VStack(alignment: .leading, spacing: 8) {
            if let title = ouText(props.raw("title")) {
                Text(title)
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
            }
            if labels.isEmpty || series.isEmpty {
                chartPlaceholder()
            } else {
                let datasets = series.map { obj -> (name: String, values: [Double]) in
                    (ouText(obj["name"]) ?? "",
                     ouItems(obj["values"]).map { ouNumber($0) ?? 0 })
                }
                let maxValue = datasets.flatMap(\.values).max() ?? 1
                VStack(alignment: .leading, spacing: 4) {
                    ChartPlotView(labels: labels, datasets: datasets, maxValue: maxValue, area: area, step: step)
                        .frame(height: 120)
                    chartLegend(names: datasets.map(\.name))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 折线/面积绘制（labels 均分 x 轴；null 断点跳过——values 长度不足补 0）。
struct ChartPlotView: View {
    let labels: [String]
    let datasets: [(name: String, values: [Double])]
    let maxValue: Double
    var area: Bool
    var step: Bool

    var body: some View {
        VStack(spacing: 4) {
            GeometryReader { geo in
                let stepX = labels.count > 1 ? geo.size.width / CGFloat(labels.count - 1) : geo.size.width
                ZStack(alignment: .leading) {
                    // 网格线（3 条）
                    ForEach(1..<4, id: \.self) { line in
                        Rectangle()
                            .fill(Color(.label).opacity(0.06))
                            .frame(height: 0.5)
                            .offset(y: geo.size.height * CGFloat(line) / 4)
                    }
                    ForEach(Array(datasets.enumerated()), id: \.offset) { setIndex, dataset in
                        let color = OpenUiTokens.chartPalette[setIndex % OpenUiTokens.chartPalette.count]
                        linePath(dataset.values, stepX: stepX, height: geo.size.height)
                            .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                        if area {
                            areaPath(dataset.values, stepX: stepX, height: geo.size.height)
                                .fill(color.opacity(0.15))
                        }
                    }
                }
            }
            HStack(spacing: 0) {
                ForEach(Array(labels.enumerated()), id: \.offset) { _, label in
                    Text(label)
                        .font(OpenUiTokens.font(role: "CAPTION"))
                        .foregroundColor(Color(.tertiaryLabel))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private func point(_ index: Int, _ value: Double, stepX: CGFloat, height: CGFloat) -> CGPoint {
        CGPoint(x: CGFloat(index) * stepX,
                y: height - height * CGFloat(min(value / max(maxValue, 1), 1)))
    }

    private func linePath(_ values: [Double], stepX: CGFloat, height: CGFloat) -> Path {
        var path = Path()
        guard !values.isEmpty else { return path }
        var previous: CGPoint? = nil
        for (index, value) in values.enumerated() {
            let current = point(index, value, stepX: stepX, height: height)
            if let previous {
                if step {
                    path.addLine(to: CGPoint(x: current.x, y: previous.y))
                }
                path.addLine(to: current)
            } else {
                path.move(to: current)
            }
            previous = current
        }
        return path
    }

    private func areaPath(_ values: [Double], stepX: CGFloat, height: CGFloat) -> Path {
        var path = linePath(values, stepX: stepX, height: height)
        guard !values.isEmpty else { return path }
        let last = point(values.count - 1, values[values.count - 1], stepX: stepX, height: height)
        let first = point(0, values[0], stepX: stepX, height: height)
        path.addLine(to: CGPoint(x: last.x, y: height))
        path.addLine(to: CGPoint(x: first.x, y: height))
        path.closeSubpath()
        return path
    }
}

// MARK: - HorizontalBarChart（横向条形）

struct OpenUiHorizontalBarChartComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let labels = ouItems(props.raw("labels")).compactMap { ouText($0) }
        let series = ouItems(props.raw("series")).map { ouObject($0) }
        let stacked = ouText(props.raw("variant")) == "stacked"
        return VStack(alignment: .leading, spacing: 8) {
            if let title = ouText(props.raw("title")) {
                Text(title)
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
            }
            if labels.isEmpty || series.isEmpty {
                chartPlaceholder()
            } else {
                let datasets = series.map { obj -> (name: String, values: [Double]) in
                    (ouText(obj["name"]) ?? "", ouItems(obj["values"]).map { ouNumber($0) ?? 0 })
                }
                let maxValue = stacked
                    ? labels.indices.map { index in datasets.reduce(0) { $0 + ($1.values.indices.contains(index) ? $1.values[index] : 0) } }.max() ?? 1
                    : datasets.flatMap(\.values).max() ?? 1
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(labels.indices, id: \.self) { index in
                        horizontalBarRow(labels: labels, index: index,
                                         datasets: datasets, maxValue: maxValue)
                    }
                    chartLegend(names: datasets.map(\.name))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func horizontalBarRow(labels: [String], index: Int,
                                  datasets: [(name: String, values: [Double])],
                                  maxValue: Double) -> some View {
        HStack(spacing: 8) {
            Text(labels[index])
                .font(OpenUiTokens.font(role: "CAPTION"))
                .foregroundColor(Color(.secondaryLabel))
                .frame(width: 56, alignment: .leading)
                .lineLimit(1)
            GeometryReader { geo in
                HStack(spacing: 2) {
                    ForEach(Array(datasets.enumerated()), id: \.offset) { setIndex, dataset in
                        let value = dataset.values.indices.contains(index) ? dataset.values[index] : 0
                        RoundedRectangle(cornerRadius: OpenUiTokens.barCornerRadius)
                            .fill(OpenUiTokens.chartPalette[setIndex % OpenUiTokens.chartPalette.count])
                            .frame(width: max(3, geo.size.width * CGFloat(value / max(maxValue, 1))))
                    }
                }
            }
            .frame(height: 12)
        }
    }
}

// MARK: - RadarChart（雷达多边形）

struct OpenUiRadarChartComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let labels = ouItems(props.raw("labels")).compactMap { ouText($0) }
        let series = ouItems(props.raw("series")).map { ouObject($0) }
        return VStack(alignment: .leading, spacing: 8) {
            if let title = ouText(props.raw("title")) {
                Text(title)
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
            }
            if labels.count < 3 || series.isEmpty {
                chartPlaceholder()
            } else {
                let datasets = series.map { obj -> (name: String, values: [Double]) in
                    (ouText(obj["name"]) ?? "", ouItems(obj["values"]).map { ouNumber($0) ?? 0 })
                }
                let maxValue = datasets.flatMap(\.values).max() ?? 1
                VStack(alignment: .leading, spacing: 4) {
                    ZStack {
                        RadarWebShape(axisCount: labels.count)
                            .stroke(Color(.label).opacity(0.1), lineWidth: 0.5)
                        ForEach(Array(datasets.enumerated()), id: \.offset) { setIndex, dataset in
                            RadarPolygonShape(values: dataset.values, axisCount: labels.count, maxValue: maxValue)
                                .fill(OpenUiTokens.chartPalette[setIndex % OpenUiTokens.chartPalette.count].opacity(0.18))
                            RadarPolygonShape(values: dataset.values, axisCount: labels.count, maxValue: maxValue)
                                .stroke(OpenUiTokens.chartPalette[setIndex % OpenUiTokens.chartPalette.count], lineWidth: 1.8)
                        }
                    }
                    .frame(width: 160, height: 160)
                    .frame(maxWidth: .infinity)
                    chartLegend(names: datasets.map(\.name))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct RadarWebShape: Shape {
    let axisCount: Int

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        for ring in 1...3 {
            let r = radius * CGFloat(ring) / 3
            var ringPath = Path()
            for axis in 0..<max(axisCount, 3) {
                let angle: CGFloat = -CGFloat.pi / 2 + CGFloat(axis) / CGFloat(max(axisCount, 3)) * CGFloat.pi * 2
                let point = CGPoint(x: center.x + cos(angle) * r, y: center.y + sin(angle) * r)
                if axis == 0 { ringPath.move(to: point) } else { ringPath.addLine(to: point) }
            }
            ringPath.closeSubpath()
            path.addPath(ringPath)
        }
        for axis in 0..<max(axisCount, 3) {
            let angle: CGFloat = -CGFloat.pi / 2 + CGFloat(axis) / CGFloat(max(axisCount, 3)) * CGFloat.pi * 2
            path.move(to: center)
            path.addLine(to: CGPoint(x: center.x + cos(angle) * radius, y: center.y + sin(angle) * radius))
        }
        return path
    }
}

struct RadarPolygonShape: Shape {
    let values: [Double]
    let axisCount: Int
    let maxValue: Double

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        for axis in 0..<axisCount {
            let value = values.indices.contains(axis) ? values[axis] : 0
            let angle: CGFloat = -CGFloat.pi / 2 + CGFloat(axis) / CGFloat(axisCount) * CGFloat.pi * 2
            let r = radius * CGFloat(value / max(maxValue, 1))
            let point = CGPoint(x: center.x + cos(angle) * r, y: center.y + sin(angle) * r)
            if axis == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        return path
    }
}

// MARK: - RadialChart（同心进度环）

struct OpenUiRadialChartComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let slices = ouItems(props.raw("slices")).map { ouObject($0) }
        let entries = slices.compactMap { obj -> (String, Double)? in
            guard let category = ouText(obj["category"]) else { return nil }
            return (category, ouNumber(obj["value"]) ?? 0)
        }
        return VStack(alignment: .leading, spacing: 8) {
            if let title = ouText(props.raw("title")) {
                Text(title)
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
            }
            if entries.isEmpty {
                chartPlaceholder()
            } else {
                HStack(spacing: 16) {
                    ZStack {
                        ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
                            let fraction = min(entry.1 / 100.0, 1.0) // value 即百分比口径（0-100）
                            Circle()
                                .trim(from: 0, to: fraction)
                                .stroke(OpenUiTokens.chartPalette[index % OpenUiTokens.chartPalette.count],
                                        style: StrokeStyle(lineWidth: 8, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                                .padding(CGFloat(index) * 12)
                        }
                    }
                    .frame(width: 60 + CGFloat(entries.count - 1) * 12,
                           height: 60 + CGFloat(entries.count - 1) * 12)
                    chartLegend(names: entries.map { "\($0.0) \(Int($0.1))%" })
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - SingleStackedBarChart（单条堆叠占比条）

struct OpenUiSingleStackedBarComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let slices = ouItems(props.raw("slices")).map { ouObject($0) }
        let entries = slices.compactMap { obj -> (String, Double)? in
            guard let category = ouText(obj["category"]) else { return nil }
            return (category, ouNumber(obj["value"]) ?? 0)
        }
        return VStack(alignment: .leading, spacing: 8) {
            if let title = ouText(props.raw("title")) {
                Text(title)
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
            }
            if entries.isEmpty {
                chartPlaceholder()
            } else {
                let total = entries.map(\.1).reduce(0, +)
                GeometryReader { geo in
                    HStack(spacing: 0) {
                        ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
                            Rectangle()
                                .fill(OpenUiTokens.chartPalette[index % OpenUiTokens.chartPalette.count])
                                .frame(width: total > 0 ? geo.size.width * CGFloat(entry.1 / total) : 0)
                        }
                    }
                    .clipShape(Capsule())
                }
                .frame(height: 12)
                chartLegend(names: entries.map { "\($0.0) \(Int($0.1))" })
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - ScatterChart（散点 [[x,y],...]）

struct OpenUiScatterChartComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let datasets = ouItems(props.raw("datasets")).map { ouObject($0) }
        let points: [(Int, [(Double, Double)])] = datasets.enumerated().map { index, obj in
            let pts = ouItems(obj["points"]).compactMap { item -> (Double, Double)? in
                let pair = ouItems(item)
                guard pair.count >= 2,
                      let x = ouNumber(pair[0]), let y = ouNumber(pair[1]) else { return nil }
                return (x, y)
            }
            return (index, pts)
        }
        return VStack(alignment: .leading, spacing: 8) {
            if let title = ouText(props.raw("title")) {
                Text(title)
                    .font(OpenUiTokens.font(role: "SUBHEAD"))
                    .foregroundColor(Color(.label))
            }
            if points.allSatisfy({ $0.1.isEmpty }) {
                chartPlaceholder()
            } else {
                let allPts = points.flatMap(\.1)
                let maxX = allPts.map(\.0).max() ?? 1
                let maxY = allPts.map(\.1).max() ?? 1
                VStack(alignment: .leading, spacing: 4) {
                    GeometryReader { geo in
                        ZStack {
                            Rectangle()
                                .fill(Color(.label).opacity(0.04))
                            ForEach(points, id: \.0) { setIndex, pts in
                                ForEach(Array(pts.enumerated()), id: \.offset) { _, pt in
                                    Circle()
                                        .fill(OpenUiTokens.chartPalette[setIndex % OpenUiTokens.chartPalette.count])
                                        .frame(width: 6, height: 6)
                                        .position(x: geo.size.width * CGFloat(pt.0 / max(maxX, 1)),
                                                  y: geo.size.height * (1 - CGFloat(pt.1 / max(maxY, 1))))
                                }
                            }
                        }
                    }
                    .frame(height: 120)
                    chartLegend(names: datasets.compactMap { ouText($0["name"]) })
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - ChartGrid（双列图表网格；children 限图表族由 server 门禁）

struct OpenUiChartGridComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let children = ouChildren(OpenUiPropsBag(node: node))
        let rows = stride(from: 0, to: children.count, by: 2).map { Array(children[$0..<min($0 + 2, children.count)]) }
        return VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .top, spacing: 8) {
                    ForEach(Array(row.enumerated()), id: \.offset) { _, item in
                        OpenUiChildItem(item: item, ctx: ctx)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 共享件

@ViewBuilder func chartPlaceholder() -> some View {
    HStack {
        Image(systemName: "chart.bar")
            .font(.system(size: 14))
            .foregroundColor(Color(.tertiaryLabel))
        Text(String(localized: "openui.chart_placeholder"))
            .font(.system(size: 12))
            .foregroundColor(Color(.tertiaryLabel))
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(.vertical, 8)
}

@ViewBuilder func chartLegend(names: [String]) -> some View {
    if names.contains(where: { !$0.isEmpty }) {
        FlowLayout(horizontal: 10, vertical: 4) {
            ForEach(Array(names.enumerated()), id: \.offset) { index, name in
                HStack(spacing: 4) {
                    Circle()
                        .fill(OpenUiTokens.chartPalette[index % OpenUiTokens.chartPalette.count])
                        .frame(width: 6, height: 6)
                    Text(name)
                        .font(OpenUiTokens.font(role: "CAPTION"))
                        .foregroundColor(Color(.secondaryLabel))
                        .lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
