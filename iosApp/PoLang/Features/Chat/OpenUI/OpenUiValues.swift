import Foundation
import SharedKit

// MARK: - OpenUI Swift 值访问层（iosMain 渲染树镜像 → SwiftUI 友好取值）
//
// IosOpenUiVal 镜像树在 Swift 侧的取值/绑定解析/children 提取工具。
// 绑定语义：BindingVal.name 带 "$" 前缀（与 OpenUiSession store 键同径，Android 同口径）；
// 解析不出（无声明/类型不符）由调用方决定丢组件或回落缺省，永不炸卡。

/// state 快照包：`$name` 键 → 值；绑定解析唯一入口。
struct OpenUiStateBag {
    let byName: [String: IosOpenUiVal]

    init(_ mirror: IosOpenUiStateMirror) {
        var map: [String: IosOpenUiVal] = [:]
        for entry in mirror.entries { map[entry.name] = entry.value }
        byName = map
    }

    init(entries: [IosOpenUiPropVal]) {
        var map: [String: IosOpenUiVal] = [:]
        for entry in entries { map[entry.name] = entry.value }
        byName = map
    }

    subscript(name: String) -> IosOpenUiVal? { byName[name] }
}

/// 节点属性包：prop 名 → 镜像值。
struct OpenUiPropsBag {
    let node: IosOpenUiNodeMirror
    private let byName: [String: IosOpenUiVal]

    init(node: IosOpenUiNodeMirror) {
        self.node = node
        var map: [String: IosOpenUiVal] = [:]
        for prop in node.props { map[prop.name] = prop.value }
        byName = map
    }

    func raw(_ key: String) -> IosOpenUiVal? { byName[key] }
}

// MARK: 标量提取（字面量直取；绑定经 state 解析；其余形态 nil——调用方丢组件/落缺省）

func ouText(_ v: IosOpenUiVal?, _ state: OpenUiStateBag? = nil) -> String? {
    switch v {
    case let text as IosOpenUiValTextVal: return text.v
    case let binding as IosOpenUiValBindingVal:
        guard let state else { return nil }
        return ouText(state[binding.name], nil)
    default: return nil
    }
}

func ouNumber(_ v: IosOpenUiVal?, _ state: OpenUiStateBag? = nil) -> Double? {
    switch v {
    case let num as IosOpenUiValNumVal: return num.v
    case let binding as IosOpenUiValBindingVal:
        guard let state else { return nil }
        return ouNumber(state[binding.name], nil)
    default: return nil
    }
}

func ouBool(_ v: IosOpenUiVal?, _ state: OpenUiStateBag? = nil) -> Bool? {
    switch v {
    case let bool as IosOpenUiValBoolVal: return bool.v
    case let binding as IosOpenUiValBindingVal:
        guard let state else { return nil }
        return ouBool(state[binding.name], nil)
    default: return nil
    }
}

/// 数组元素（Arr/children/对象数组/原始数组/嵌套行数组统一形态）。
func ouItems(_ v: IosOpenUiVal?) -> [IosOpenUiVal] {
    (v as? IosOpenUiValListVal)?.items ?? []
}

/// 对象 entries（键值对列表）。
func ouEntries(_ v: IosOpenUiVal?) -> [IosOpenUiPropVal] {
    (v as? IosOpenUiValObjVal)?.entries ?? []
}

/// 对象 → 字典化 entries（按 key 取值便捷形态）。
func ouObject(_ v: IosOpenUiVal?) -> [String: IosOpenUiVal] {
    var map: [String: IosOpenUiVal] = [:]
    for entry in ouEntries(v) { map[entry.name] = entry.value }
    return map
}

/// 动作表达式（裸动作 / Action([...]) 包装皆收）。
func ouActions(_ v: IosOpenUiVal?) -> [OpenUiAction] {
    (v as? IosOpenUiValActionListVal)?.actions ?? []
}

/// children 提取：COMPONENT_LIST 槽（NodeVal / UnknownNodeVal / SkeletonNodeVal 混排，渲染层逐项分流；
/// Skeleton = 流式前向引用骨架占位，瞬态——partial 前缀解析独有，正式卡零产出）。
func ouChildren(_ props: OpenUiPropsBag) -> [IosOpenUiVal] {
    ouItems(props.raw("children")).filter { item in
        item is IosOpenUiValNodeVal || item is IosOpenUiValUnknownNodeVal || item is IosOpenUiValSkeletonNodeVal
    }
}

/// 绑定名提取（可绑定属性；非绑定返回 nil——裸字面量只读形态）。
func ouBindingName(_ v: IosOpenUiVal?) -> String? {
    (v as? IosOpenUiValBindingVal)?.name
}

// MARK: - 动作构造（@Set 回写；对齐 Android 渲染层 onAction 合成形态）

/// @Set(target, value) 单动作。
func ouSetAction(target: String, bool value: Bool) -> OpenUiAction {
    OpenUiAction(name: OpenUiContract.Actions.shared.SET, args: [OpenUiValueBool(v: value)], target: target)
}

func ouSetAction(target: String, string value: String) -> OpenUiAction {
    OpenUiAction(name: OpenUiContract.Actions.shared.SET, args: [OpenUiValueStr(v: value)], target: target)
}

func ouSetAction(target: String, number value: Double) -> OpenUiAction {
    OpenUiAction(name: OpenUiContract.Actions.shared.SET, args: [OpenUiValueNum(v: value)], target: target)
}

// MARK: - 渲染上下文（快照 → 子树渲染的共享载荷）

/// 一次快照下的渲染上下文：state 解析 + 动作/照片回调 + 媒体解析。
/// 值类型传递（快照更新 = 宿主整树重建，Android「相等门控树零重组」的 iOS 等价简化）。
struct OpenUiRenderContext {
    let state: OpenUiStateBag
    let onAction: ([OpenUiAction]) -> Void
    /// 照片点击（看大图）：clicked uri + 会话全量集（已含 clicked 优先重排语义则由宿主处理）。
    let onPhotoTap: (String) -> Void
    /// 媒体解析（photoId → localIdentifier；宿主注入）。
    let resolvePhoto: (Int64) async -> String?
}
