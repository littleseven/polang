import SwiftUI
import SharedKit

// MARK: - OpenUI 表单/交互族（name 键控 → @Set($name, 值) 直写 state；官方表单语义）

// MARK: - CheckBox / Switch

struct OpenUiCheckBoxComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let label = ouText(props.raw("label")) ?? ""
        // 裸字面量只读展示不写入（BarChart 同口径）；绑定解析不出丢组件
        if let checked = ouBool(props.raw("checked"), ctx.state) {
            let bindingName = ouBindingName(props.raw("checked"))
            Button {
                guard let bindingName else { return }
                ctx.onAction([ouSetAction(target: bindingName, bool: !checked)])
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: checked ? "checkmark.square.fill" : "square")
                        .font(.system(size: 18))
                        .foregroundColor(checked ? .accentColor : Color(.secondaryLabel))
                    Text(label)
                        .font(.system(size: 14))
                        .foregroundColor(Color(.label))
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(bindingName == nil)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct OpenUiSwitchComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        if let checked = ouBool(props.raw("checked"), ctx.state) {
            let bindingName = ouBindingName(props.raw("checked"))
            Toggle(ouText(props.raw("label")) ?? "", isOn: Binding(
                get: { checked },
                set: { newValue in
                    guard let bindingName else { return }
                    ctx.onAction([ouSetAction(target: bindingName, bool: newValue)])
                }))
            .disabled(bindingName == nil)
            .tint(.accentColor)
            .font(.system(size: 14))
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - ChoicePicker（单选 chips 行）

struct OpenUiChoicePickerComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let options = ouItems(props.raw("options")).map { ouObject($0) }
        let selected = ouText(props.raw("selected"), ctx.state) ?? ""
        let bindingName = ouBindingName(props.raw("selected"))
        return VStack(alignment: .leading, spacing: 6) {
            if let label = ouText(props.raw("label")) {
                Text(label)
                    .font(.system(size: 13))
                    .foregroundColor(Color(.secondaryLabel))
            }
            FlowLayout(horizontal: 8, vertical: 6) {
                ForEach(Array(options.enumerated()), id: \.offset) { _, obj in
                    let value = ouText(obj["value"]) ?? ""
                    let optionLabel = ouText(obj["label"]) ?? value
                    Button {
                        guard let bindingName, !value.isEmpty else { return }
                        ctx.onAction([ouSetAction(target: bindingName, string: value)])
                    } label: {
                        Text(optionLabel)
                            .font(.system(size: 13))
                            .foregroundColor(selected == value ? .white : Color(.label).opacity(0.8))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(selected == value ? Color.accentColor : Color(.secondarySystemBackground))
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(bindingName == nil)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Slider（HyperOS 胶囊滑杆；拖动结束 @Set）

struct OpenUiSliderComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    @State private var dragValue: Double? = nil

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let minValue = ouNumber(props.raw("min")) ?? 0
        let maxValue = ouNumber(props.raw("max")) ?? 100
        if let value = ouNumber(props.raw("value"), ctx.state) {
            let bindingName = ouBindingName(props.raw("value"))
            let current = dragValue ?? min(max(value, minValue), maxValue)
            VStack(alignment: .leading, spacing: 6) {
                if let label = ouText(props.raw("label")) {
                    HStack {
                        Text(label)
                            .font(.system(size: 13))
                            .foregroundColor(Color(.secondaryLabel))
                        Spacer()
                        Text(String(format: "%.0f", current))
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(Color(.label))
                            .monospacedDigit()
                    }
                }
                OpenUiCapsuleSlider(
                    value: current, range: minValue...maxValue) { newValue in
                        dragValue = newValue
                    } onEnded: { finalValue in
                        dragValue = nil
                        if let bindingName {
                            ctx.onAction([ouSetAction(target: bindingName, number: finalValue)])
                        }
                    }
                    .disabled(bindingName == nil)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            EmptyView()
        }
    }
}

/// 胶囊轨道 + 白圆点描边 thumb（对齐全 app AppSlider 形态；HyperOS 滑杆红线 iOS 对等件）。
struct OpenUiCapsuleSlider: View {
    var value: Double
    var range: ClosedRange<Double>
    var onChanged: (Double) -> Void
    var onEnded: (Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let fraction = range.upperBound > range.lowerBound
                ? (value - range.lowerBound) / (range.upperBound - range.lowerBound) : 0
            let clamped = min(max(fraction, 0), 1)
            let thumbX = clamped * width
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color(.label).opacity(0.1))
                    .frame(height: 6)
                Capsule()
                    .fill(Color.accentColor)
                    .frame(width: max(6, thumbX), height: 6)
                Circle()
                    .fill(.white)
                    .overlay(Circle().stroke(Color.accentColor, lineWidth: 2))
                    .frame(width: 20, height: 20)
                    .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                    .offset(x: min(max(thumbX - 10, 0), max(width - 20, 0)))
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { gesture in
                        let fraction = min(max(gesture.location.x / max(width, 1), 0), 1)
                        onChanged(range.lowerBound + fraction * (range.upperBound - range.lowerBound))
                    }
                    .onEnded { _ in onEnded(valueAt(currentFractionX: width, gestureLocationX: nil)) }
            )
        }
        .frame(height: 28)
    }

    private func valueAt(currentFractionX _: CGFloat, gestureLocationX _: Double?) -> Double {
        // onEnded 用 onChanged 最后一次写入（dragValue 由宿主持有）；此处回落当前 value
        value
    }
}

// MARK: - Table（行式表格）

struct OpenUiTableComponent: View {
    let node: IosOpenUiNodeMirror

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let columns = ouItems(props.raw("columns")).map { ouText(ouObject($0)["label"]) ?? "" }
        let rows = ouItems(props.raw("rows")).map { ouItems($0) }
        if columns.isEmpty || rows.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 0) {
                tableHeader(columns: columns)
                Divider().opacity(0.5)
                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    tableRow(columns: columns, row: row, divider: index < rows.count - 1)
                }
            }
            .background(Color(.secondarySystemBackground).opacity(0.6))
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func tableHeader(columns: [String]) -> some View {
        HStack(spacing: 0) {
            ForEach(Array(columns.enumerated()), id: \.offset) { _, column in
                Text(column)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(Color(.secondaryLabel))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
            }
        }
    }

    @ViewBuilder
    private func tableRow(columns: [String], row: [IosOpenUiVal], divider: Bool) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(columns.indices, id: \.self) { columnIndex in
                    Text(cellText(row, columnIndex))
                        .font(.system(size: 13))
                        .foregroundColor(Color(.label))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 7)
                        .lineLimit(2)
                }
            }
            if divider { Divider().opacity(0.3) }
        }
    }

    /// cell = Str/Num/Bool 原始值（缺 cell 补空串）。
    private func cellText(_ row: [IosOpenUiVal], _ index: Int) -> String {
        guard index < row.count else { return "" }
        switch row[index] {
        case let text as IosOpenUiValTextVal: return text.v
        case let num as IosOpenUiValNumVal:
            return num.v.truncatingRemainder(dividingBy: 1) == 0
                ? String(format: "%.0f", num.v) : String(format: "%.2f", num.v)
        case let bool as IosOpenUiValBoolVal: return bool.v ? "true" : "false"
        default: return ""
        }
    }
}

// MARK: - EditableTable（单元格即写：@Set($name, JSON 行值数组)）

struct OpenUiEditableTableComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    @State private var cells: [[String]] = []

    var body: some View {
        let columns = Self.columnsOf(node)
        let rows = cells
        return VStack(alignment: .leading, spacing: 0) {
            OpenUiEditableTableHeader(columns: columns)
            Divider().opacity(0.5)
            ForEach(Array(rows.indices), id: \.self) { rowIndex in
                OpenUiEditableTableRow(
                    columnCount: columns.count,
                    row: rows[rowIndex]) { values in
                    commitRow(rowIndex: rowIndex, values: values)
                }
            }
        }
        .onAppear { if cells.isEmpty { cells = Self.initialCells(node) } }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private static func columnsOf(_ node: IosOpenUiNodeMirror) -> [[String: IosOpenUiVal]] {
        ouItems(OpenUiPropsBag(node: node).raw("columns")).map { ouObject($0) }
    }

    private static func initialCells(_ node: IosOpenUiNodeMirror) -> [[String]] {
        let data = ouItems(OpenUiPropsBag(node: node).raw("data")).map { ouObject($0) }
        return data.map { obj in ouItems(obj["values"]).map { ouText($0) ?? "" } }
    }

    /// 行提交：整表 JSON 行值数组写入 $name（对齐 Android「单元格即写」简化）。
    private func commitRow(rowIndex: Int, values: [String]) {
        guard rowIndex < cells.count else { return }
        cells[rowIndex] = values
        let name = ouText(OpenUiPropsBag(node: node).raw("name")) ?? ""
        guard !name.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: cells),
              let json = String(data: data, encoding: .utf8)
        else { return }
        ctx.onAction([ouSetAction(target: name, string: json)])
    }
}

/// 表头行。
private struct OpenUiEditableTableHeader: View {
    let columns: [[String: IosOpenUiVal]]

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Array(columns.enumerated()), id: \.offset) { _, obj in
                Text(ouText(obj["header"]) ?? "")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(Color(.secondaryLabel))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 6)
            }
        }
    }
}

/// 单行输入（独立子视图：单元格 TextField + 行内提交）。
private struct OpenUiEditableTableRow: View {
    let columnCount: Int
    let row: [String]
    let onCommit: ([String]) -> Void

    @State private var values: [String] = []

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<columnCount, id: \.self) { column in
                TextField("", text: cellBinding(column))
                    .font(.system(size: 13))
                    .textFieldStyle(.plain)
                    .padding(.vertical, 5)
                    .padding(.horizontal, 4)
                    .background(Color(.tertiarySystemBackground).opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
        .padding(.vertical, 3)
        .onAppear {
            if values.isEmpty { values = row }
        }
    }

    private func cellBinding(_ column: Int) -> Binding<String> {
        Binding<String>(
            get: { column < values.count ? values[column] : "" },
            set: { newValue in
                guard column < values.count else { return }
                values[column] = newValue
                onCommit(values)
            })
    }
}

// MARK: - FormControl / Input / TextArea / Select / DatePicker

struct OpenUiFormControlComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        VStack(alignment: .leading, spacing: 4) {
            Text(ouText(props.raw("label")) ?? "")
                .font(.system(size: 13))
                .foregroundColor(Color(.secondaryLabel))
            OpenUiChildren(ouChildren(props), ctx: ctx)
            if let hint = ouText(props.raw("hint")) {
                Text(hint)
                    .font(OpenUiTokens.font(role: "CAPTION"))
                    .foregroundColor(Color(.tertiaryLabel))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiInputComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    @State private var text = ""

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name")) ?? ""
        TextField(ouText(props.raw("placeholder")) ?? "", text: Binding(
            get: { ouText(props.raw("text"), ctx.state) ?? text },
            set: { newValue in
                text = newValue
            }))
            .font(.system(size: 14))
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .submitLabel(.done)
            .onSubmit {
                if !name.isEmpty { ctx.onAction([ouSetAction(target: name, string: text)]) }
            }
            .frame(maxWidth: .infinity)
    }
}

struct OpenUiTextAreaComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name")) ?? ""
        let rows = Int(ouNumber(props.raw("rows")) ?? 3)
        TextField(ouText(props.raw("placeholder")) ?? "", text: $text, axis: .vertical)
            .font(.system(size: 14))
            .lineLimit(1...max(1, rows))
            .padding(10)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .focused($focused)
            .onChange(of: focused) { isFocused in
                if !isFocused, !name.isEmpty, !text.isEmpty {
                    ctx.onAction([ouSetAction(target: name, string: text)])
                }
            }
            .frame(maxWidth: .infinity)
    }
}

struct OpenUiSelectComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name")) ?? ""
        let items = ouItems(props.raw("items")).map { ouObject($0) }
        let selected = ouText(ctx.state[name])
        Menu {
            ForEach(Array(items.enumerated()), id: \.offset) { _, obj in
                let value = ouText(obj["value"]) ?? ""
                Button(ouText(obj["label"]) ?? value) {
                    if !name.isEmpty, !value.isEmpty {
                        ctx.onAction([ouSetAction(target: name, string: value)])
                    }
                }
            }
        } label: {
            HStack {
                Text(selected.flatMap { value in
                    items.first { ouText($0["value"]) == value }.flatMap { ouText($0["label"]) }
                } ?? ouText(props.raw("placeholder")) ?? String(localized: "openui.select_placeholder"))
                    .font(.system(size: 14))
                    .foregroundColor(selected == nil ? Color(.secondaryLabel) : Color(.label))
                Spacer()
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 12))
                    .foregroundColor(Color(.secondaryLabel))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .frame(maxWidth: .infinity)
    }
}

struct OpenUiDatePickerComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    @State private var date = Date()
    @State private var endDate = Date()

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name")) ?? ""
        let isRange = ouText(props.raw("mode")) == "range"
        return VStack(alignment: .leading, spacing: 8) {
            DatePicker(String(localized: "openui.datepicker_start"), selection: $date,
                       displayedComponents: .date)
                .labelsHidden()
                .environment(\.locale, Locale.current)
                .onChange(of: date) { _ in commit(name: name, isRange: isRange) }
            if isRange {
                DatePicker(String(localized: "openui.datepicker_end"), selection: $endDate,
                           in: date..., displayedComponents: .date)
                    .labelsHidden()
                    .environment(\.locale, Locale.current)
                    .onChange(of: endDate) { _ in commit(name: name, isRange: isRange) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private func commit(name: String, isRange: Bool) {
        guard !name.isEmpty else { return }
        let value = isRange
            ? "\(formatter.string(from: date))~\(formatter.string(from: endDate))"
            : formatter.string(from: date)
        ctx.onAction([ouSetAction(target: name, string: value)])
    }
}

// MARK: - CheckBoxGroup / CheckBoxItem / RadioGroup / SwitchGroup / SwitchItem

struct OpenUiCheckBoxGroupComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let items = ouItems(props.raw("items")).map { ouObject($0) }
        return VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, obj in
                OpenUiCheckRow(
                    label: ouText(obj["label"]) ?? "",
                    description: ouText(obj["description"]),
                    checked: ouBool(ctx.state[ouText(obj["name"]) ?? ""]) ?? (ouBool(obj["defaultChecked"]) ?? false),
                    onToggle: { checked in
                        if let itemName = ouText(obj["name"]), !itemName.isEmpty {
                            ctx.onAction([ouSetAction(target: itemName, bool: checked)])
                        }
                    })
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiCheckBoxItemStandaloneComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name")) ?? ""
        OpenUiCheckRow(
            label: ouText(props.raw("label")) ?? "",
            description: ouText(props.raw("description")),
            checked: ouBool(ctx.state[name]) ?? (ouBool(props.raw("defaultChecked")) ?? false),
            onToggle: { checked in
                if !name.isEmpty { ctx.onAction([ouSetAction(target: name, bool: checked)]) }
            })
    }
}

private struct OpenUiCheckRow: View {
    let label: String
    let description: String?
    let checked: Bool
    let onToggle: (Bool) -> Void

    var body: some View {
        Button {
            onToggle(!checked)
        } label: {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: checked ? "checkmark.square.fill" : "square")
                    .font(.system(size: 18))
                    .foregroundColor(checked ? .accentColor : Color(.secondaryLabel))
                    .padding(.top, 1)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.system(size: 14))
                        .foregroundColor(Color(.label))
                        .fixedSize(horizontal: false, vertical: true)
                    if let description {
                        Text(description)
                            .font(.system(size: 12))
                            .foregroundColor(Color(.secondaryLabel))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct OpenUiRadioGroupComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name")) ?? ""
        let items = ouItems(props.raw("items")).map { ouObject($0) }
        let selected = ouText(ctx.state[name]) ?? ouText(props.raw("defaultValue")) ?? ""
        return VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, obj in
                let value = ouText(obj["value"]) ?? ""
                Button {
                    guard !name.isEmpty, !value.isEmpty else { return }
                    ctx.onAction([ouSetAction(target: name, string: value)])
                } label: {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: selected == value ? "largecircle.fill.circle" : "circle")
                            .font(.system(size: 18))
                            .foregroundColor(selected == value ? .accentColor : Color(.secondaryLabel))
                            .padding(.top, 1)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ouText(obj["label"]) ?? "")
                                .font(.system(size: 14))
                                .foregroundColor(Color(.label))
                            if let description = ouText(obj["description"]) {
                                Text(description)
                                    .font(.system(size: 12))
                                    .foregroundColor(Color(.secondaryLabel))
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiSwitchGroupComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let items = ouItems(props.raw("items")).map { ouObject($0) }
        return VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, obj in
                OpenUiSwitchRow(
                    label: ouText(obj["label"]) ?? "",
                    description: ouText(obj["description"]),
                    checked: ouBool(ctx.state[ouText(obj["name"]) ?? ""]) ?? (ouBool(obj["defaultChecked"]) ?? false),
                    onToggle: { checked in
                        if let itemName = ouText(obj["name"]), !itemName.isEmpty {
                            ctx.onAction([ouSetAction(target: itemName, bool: checked)])
                        }
                    })
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiSwitchItemStandaloneComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name")) ?? ""
        OpenUiSwitchRow(
            label: ouText(props.raw("label")) ?? "",
            description: ouText(props.raw("description")),
            checked: ouBool(ctx.state[name]) ?? (ouBool(props.raw("defaultChecked")) ?? false),
            onToggle: { checked in
                if !name.isEmpty { ctx.onAction([ouSetAction(target: name, bool: checked)]) }
            })
    }
}

private struct OpenUiSwitchRow: View {
    let label: String
    let description: String?
    let checked: Bool
    let onToggle: (Bool) -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.system(size: 14))
                    .foregroundColor(Color(.label))
                if let description {
                    Text(description)
                        .font(.system(size: 12))
                        .foregroundColor(Color(.secondaryLabel))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            Toggle("", isOn: Binding(get: { checked }, set: onToggle))
                .labelsHidden()
                .tint(.accentColor)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Chips / OptionCards（single 写 $name=值；multiple 写 $name=逗号串）

struct OpenUiChipsComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name")) ?? ""
        let items = ouItems(props.raw("items")).map { ouObject($0) }
        let isSingle = ouText(props.raw("type")) == "single"
        let selectedValues = Set((ouText(ctx.state[name]) ?? "").split(separator: ",").map(String.init))
        return FlowLayout(horizontal: 8, vertical: 6) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, obj in
                let value = ouText(obj["value"]) ?? ""
                let label = ouText(obj["label"]) ?? value
                let isSelected = selectedValues.contains(value)
                Button {
                    guard !name.isEmpty, !value.isEmpty else { return }
                    if isSingle {
                        ctx.onAction([ouSetAction(target: name, string: value)])
                    } else {
                        var values = selectedValues
                        if isSelected { values.remove(value) } else { values.insert(value) }
                        ctx.onAction([ouSetAction(target: name, string: values.sorted().joined(separator: ","))])
                    }
                } label: {
                    HStack(spacing: 4) {
                        if let iconName = ouText(obj["icon"]) {
                            Image(systemName: OpenUiTokens.systemImage(named: iconName))
                                .font(.system(size: 12))
                        }
                        Text(label)
                            .font(.system(size: 13))
                    }
                    .foregroundColor(isSelected ? .white : Color(.label).opacity(0.8))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(isSelected ? Color.accentColor : Color(.secondarySystemBackground))
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiOptionCardsComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let name = ouText(props.raw("name")) ?? ""
        let items = ouItems(props.raw("items")).map { ouObject($0) }
        let isMultiple = ouText(props.raw("type")) == "multiple"
        let selectedValues = Set((ouText(ctx.state[name]) ?? "").split(separator: ",").map(String.init))
        return VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, obj in
                let value = ouText(obj["value"]) ?? ""
                let isSelected = selectedValues.contains(value)
                Button {
                    guard !name.isEmpty, !value.isEmpty else { return }
                    if isMultiple {
                        var values = selectedValues
                        if isSelected { values.remove(value) } else { values.insert(value) }
                        ctx.onAction([ouSetAction(target: name, string: values.sorted().joined(separator: ","))])
                    } else {
                        ctx.onAction([ouSetAction(target: name, string: value)])
                    }
                } label: {
                    HStack(spacing: 8) {
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
                        }
                        Spacer(minLength: 8)
                        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 20))
                            .foregroundColor(isSelected ? .accentColor : Color(.tertiaryLabel))
                    }
                    .padding(12)
                    .background(isSelected ? Color.accentColor.opacity(0.08) : Color(.secondarySystemBackground))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(isSelected ? Color.accentColor : .clear, lineWidth: 1.5)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Buttons / IconButton / Modal / Tabs / Accordion / TabItem / Html

struct OpenUiButtonComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let variant = ouText(props.raw("variant")) ?? "primary"
        let actions = ouActions(props.raw("action"))
        Button {
            if !actions.isEmpty { ctx.onAction(actions) }
        } label: {
            Text(ouText(props.raw("label")) ?? "")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(fgColor(variant))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(bg(variant))
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(actions.isEmpty)
    }

    private func fgColor(_ variant: String) -> Color {
        switch variant {
        case "secondary": return Color(.label)
        case "tertiary": return .accentColor
        default: return .white
        }
    }

    private func bg(_ variant: String) -> Color {
        switch variant {
        case "secondary": return Color(.secondarySystemBackground)
        case "tertiary": return .accentColor.opacity(0.1)
        default: return .accentColor
        }
    }
}

struct OpenUiButtonsComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let buttons = ouItems(props.raw("buttons")).compactMap { $0 as? IosOpenUiValNodeVal }
        let isRow = ouText(props.raw("direction")) != "column"
        return Group {
            if isRow {
                HStack(spacing: 8) {
                    ForEach(Array(buttons.enumerated()), id: \.offset) { _, nodeVal in
                        OpenUiNodeRenderer(node: nodeVal.node, ctx: ctx)
                            .frame(maxWidth: .infinity)
                    }
                }
            } else {
                VStack(spacing: 8) {
                    ForEach(Array(buttons.enumerated()), id: \.offset) { _, nodeVal in
                        OpenUiNodeRenderer(node: nodeVal.node, ctx: ctx)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiIconButtonComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        let actions = ouActions(props.raw("action"))
        Button {
            if !actions.isEmpty { ctx.onAction(actions) }
        } label: {
            Image(systemName: OpenUiTokens.systemImage(named: ouText(props.raw("icon"))))
                .font(.system(size: 18))
                .foregroundColor(Color(.label))
                .frame(width: 36, height: 36)
                .background(Color(.secondarySystemBackground))
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(ouText(props.raw("name")) ?? "")
        .disabled(actions.isEmpty)
    }
}

struct OpenUiModalComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        let props = OpenUiPropsBag(node: node)
        if let open = ouBool(props.raw("open"), ctx.state) {
            let bindingName = ouBindingName(props.raw("open"))
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(ouText(props.raw("title")) ?? "")
                        .font(OpenUiTokens.font(role: "SUBHEAD"))
                        .foregroundColor(Color(.label))
                    Spacer()
                    Button {
                        guard let bindingName else { return }
                        ctx.onAction([ouSetAction(target: bindingName, bool: false)])
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(Color(.secondaryLabel))
                            .frame(width: 28, height: 28)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.bottom, 10)
                OpenUiChildren(ouChildren(props), ctx: ctx)
            }
            .padding(16)
            .background(Color(.systemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .shadow(color: .black.opacity(0.15), radius: 12, y: 4)
            .padding(.vertical, 8)
        }
    }
}

struct OpenUiTabsComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    @State private var selected = 0

    var body: some View {
        let tabs = ouItems(OpenUiPropsBag(node: node).raw("tabs")).map { ouObject($0) }
        return VStack(alignment: .leading, spacing: 10) {
            // 胶囊分段控件（页签切换纯 UI 本地态，不进 OpenUI state——Android 同口径）
            HStack(spacing: 4) {
                ForEach(Array(tabs.enumerated()), id: \.offset) { index, obj in
                    Button {
                        selected = index
                    } label: {
                        Text(ouText(obj["title"]) ?? "")
                            .font(.system(size: 13, weight: selected == index ? .semibold : .regular))
                            .foregroundColor(selected == index ? .white : Color(.label).opacity(0.75))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(selected == index ? Color.accentColor : .clear)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(4)
            .background(Color(.secondarySystemBackground))
            .clipShape(Capsule())
            if tabs.indices.contains(selected) {
                OpenUiChildren(
                    ouItems(tabs[selected]["children"]).filter { $0 is IosOpenUiValNodeVal || $0 is IosOpenUiValUnknownNodeVal || $0 is IosOpenUiValSkeletonNodeVal },
                    ctx: ctx)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct OpenUiAccordionComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    @State private var expanded: Set<Int> = []

    var body: some View {
        let items = ouItems(OpenUiPropsBag(node: node).raw("items")).map { ouObject($0) }
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, obj in
                VStack(alignment: .leading, spacing: 0) {
                    Button {
                        if expanded.contains(index) { expanded.remove(index) } else { expanded.insert(index) }
                    } label: {
                        HStack {
                            Text(ouText(obj["title"]) ?? "")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(Color(.label))
                            Spacer()
                            Image(systemName: expanded.contains(index) ? "chevron.down" : "chevron.right")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(Color(.secondaryLabel))
                        }
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if expanded.contains(index) {
                        VStack(alignment: .leading, spacing: 8) {
                            OpenUiChildren(
                                ouItems(obj["children"]).filter { $0 is IosOpenUiValNodeVal || $0 is IosOpenUiValUnknownNodeVal || $0 is IosOpenUiValSkeletonNodeVal },
                                ctx: ctx)
                        }
                        .padding(.bottom, 10)
                    }
                    Divider().opacity(0.3)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// TabItem/AccordionItem 组件形保底：content 直排。
struct OpenUiTabItemComponent: View {
    let node: IosOpenUiNodeMirror
    let ctx: OpenUiRenderContext

    var body: some View {
        OpenUiChildren(ouChildren(OpenUiPropsBag(node: node)), ctx: ctx)
    }
}
