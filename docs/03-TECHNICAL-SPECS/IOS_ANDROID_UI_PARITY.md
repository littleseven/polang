# 双端 UI 对齐方法论（Android ↔ iOS）

> **版本**：2.0（2026-08-09）补充行业无障碍/深色模式/动效/RTL/键盘适配规范
> **背景**：Phase 5 iOS 转型中，两轮「读 Compose 源码翻译布局」的对齐方法均被真机验收证伪；确立「视觉+量化地面真值」方法后沉淀本文。v2.0 补充 §4.1–§4.5 行业标准适配规范。
> **适用**：polang iOS 端一切以 Android 为基准的 UI 对齐工作（S5：双端体验一致为最高原则）。

---

## 0. 一致性分层模型（先定义「什么必须一致」）

跨端 UI 一致**不是像素级复制**。社区共识是「结构对齐 + 平台原生质感」（structural parity + platform-native feel）：

| 层 | 内容 | 对齐要求 |
|---|---|---|
| **信息层级** | 每屏有哪些元素、分组、优先级 | 🔴 零容差一致 |
| **布局结构** | 元素的相对位置、锚定关系、尺寸比例 | 🔴 零容差一致（归一化后） |
| **功能与默认** | 功能集、默认值、排序、状态机 | 🔴 零容差一致（S5 既有纪律） |
| **文案与状态** | 文案内容（经 i18n）、空态/加载态/权限态 | 🔴 零容差一致 |
| **无障碍语义** | 交互元素 label/role/state、焦点顺序、触控目标 | 🔴 零容差一致（§4.1） |
| **深色/浅色模式** | 每个页面的主题色适配、对比度达标 | 🔴 零容差一致（§4.2） |
| **触发时机** | 动效/触觉反馈的触发条件一致 | 🔴 零容差一致（§4.3） |
| **字体/图标** | Roboto vs SF、Material Icons vs SF Symbols | 🟢 平台原生，不强求一致 |
| **系统交互** | 导航返回方式、权限申请流、picker 形态 | 🟢 遵从平台 HIG/Material 惯例 |
| **动效曲线/实现** | 缓动函数、spring 参数、涟漪 vs 高亮 | 🟢 平台原生（触发时机一致即可） |
| **材质细节** | 涟漪 vs 高亮、阴影风格 | 🟢 平台原生 |

> 原则：**用户的心智模型和任务流一致；平台的视觉语言各自原生**。凡是「允许不同」的项，必须登记进「已知差异清单」——不允许悄悄不同。

## 1. 度量体系：先解决「不可比的坐标」

### 1.1 dp ≈ pt，数值直接迁移（误差 <2%，可忽略）

- Android `dp` = 1/160 英寸；iOS `pt` ≈ 1/163 英寸。**1dp ≈ 1pt，跨端尺寸数值直接 1:1 迁移**（[Android 官方网格与单位指南](https://developer.android.com/design/ui/mobile/guides/layout-and-content/grids-and-units?hl=zh-cn)）
- 🔴 **禁止跨端比较像素（px）**。一切尺寸先归一化为 dp/pt 再对照。dump 里的 px bounds 必须除以密度换算
- 密度桶对照：Android mdpi(1x)~xxxhdpi(4x)；iOS @1x/@2x/@3x。资源用矢量（Android VectorDrawable / iOS PDF 或 SF Symbols），位图按倍率供多份
- 触控目标下限：Android **48×48dp**、iOS **44×44pt**（[UXPin 双端设计差异](https://www.uxpin.com/studio/blog/ios-vs-andoid-ui-design-for-mobile/)）。以 Android 为基准时 48dp → iOS 用 48pt，天然双达标

### 1.2 屏幕尺寸/形状差异的归一化

- 设计基准宽：Android **360dp** / iOS **375pt**（[smart-interface-design-patterns](https://smart-interface-design-patterns.com/articles/designing-for-mobile-ios-android-guide/)）。两机实际宽度不同时，**宽度方向按比例缩放，高度方向由可伸缩区吸收**
- 🔴 **禁止硬编码绝对坐标**。布局描述只用三件套：
  1. **边缘锚定**——顶栏锚 safe area 上缘、底栏锚 safe area 下缘、侧栏锚左右缘
  2. **固定 dp/pt**——控件尺寸、间距、圆角、字阶（这些跨端不变）
  3. **比例尺寸**——面板高度占屏比（如美颜面板 35%）、预览区占比、缩略图列数（列数固定、格宽 = 屏宽/列数导出）
- **宽高比差异**由「竖直可伸缩区」吸收：相机预览区、相册网格内容区是弹性的；顶栏/底栏/面板给固定或比例高度
- **异形屏（safe area）**：notch / Dynamic Island / 状态栏 / 手势指示条（iOS） vs 挖孔 / 状态栏 / 三大键或手势条（Android）——**对齐的是内容区，不是屏幕玻璃**。参照截图里的 y 坐标必须扣除各自 insets 后再比
- 相机特例：传感器 4:3 与屏幕比例不一致时，裁剪/留边策略双端必须同策（预览显示比例差异会直接导致所有叠加控件视觉错位）

### 1.3 系统栏与 Back 机制（三大系统层差异，显式处理）

#### 系统状态栏（Status Bar）

- **高度不可假设**：Android 常见 24dp + 挖孔增量，iOS 44~59pt（刘海/Dynamic Island 各异）——顶栏一律锚 `WindowInsets.statusBars`（Android）/ `safeAreaInsets.top`（iOS）下缘，🔴 禁止写死「距顶 24/44」
- **显隐与风格逐页对齐**：相机页 Android 通常沉浸式（edge-to-edge、状态栏透明压黑底、浅色图标）——iOS 对应是状态栏内容色 `.lightContent` + 预览延伸至顶；相册页若 Android 显示状态栏，iOS 不得隐藏。**每页登记：状态栏显隐 + 内容色（light/dark）+ 背景处理**，纳入逐页对照表
- 状态栏内容（时间/电量）颜色必须随页面背景可切换，浅色页面上白字状态栏 = 不可读（真机常见翻车点）

#### 虚拟键 / 导航区（Android 三键 vs 手势条 vs iOS Home Indicator）

- **底部 insets 差异更大且同机可变**：Android 三键模式 ~48dp、手势模式 ~16-24dp、沉浸模式可隐藏；iOS Home Indicator ~34pt——底栏锚系统导航区上缘，🔴 禁止写死「距底 48」
- **边缘手势冲突**：iOS 底部上划 = 回桌面、左缘右划 = 返回；Android 左右缘内划 = 系统 Back、底部上划 = 多任务——🔴 双侧底缘/侧缘不放置关键滑动手势（如美颜滑杆、滤镜横滑），预留系统手势热区（≥系统 insets + 8dp 缓冲）；iOS 侧必要时 `preferredScreenEdgesDeferringSystemGestures`（相机页）对应 Android 沉浸模式的边缘滑动二次确认
- 横滑 Pager（相机↔相册）与系统边缘 Back 手势的冲突需实测：Android 上 Pager 需处理 `userScrollEnabled` 与边缘手势区，iOS 上 `TabView(.page)` 满宽滑动同理

#### Back 机制（Android 有系统 Back，iOS 没有）

- **语义映射规则**：Android 每一个「系统 Back 到达的状态」都必须在 iOS 有显式等价物，且**优先级顺序一致**：

  | Android Back 行为（典型栈） | iOS 等价物 |
  |---|---|
  | 面板展开 → Back 先关面板（不退出页） | 面板自带关闭手势（下拖/点空白/×），**不得**用页面返回代替 |
  | 大图页 → Back 回网格 | 顶栏显式返回按钮（位置对齐 Android 顶栏返回位）+ 可选下拖关闭 |
  | 相簿列表 → Back 回网格 | 同上 |
  | Pager 内页 → Back 回首页/退出 | 横滑返回（容器手势），无按钮 |

- Android 端 Back 栈实现（`BackHandler`/`OnBackPressedDispatcher` 的注册顺序）是**对齐基准**，先读清基准端的 Back 优先级链，再在 iOS 导航状态机里复刻同一顺序——🔴 禁止 iOS 出现「Android 按 Back 关面板、iOS 直接退出页面」的语义错位
- iOS 导航栈页面的左缘右划返回是平台惯例，可叠加提供，但不得替代上表的显式控件

## 2. 参照物：视觉 + 量化双地面真值

> ❌ 已证伪的反模式：让 AI 读基准端源码「脑补」布局再翻译——两次迭代均未通过真机验收。源码是实现的地图，不是视觉的地面真值。

### 2.1 实现前：逐屏规格契约（Per-Screen Spec）

> 🔴 **双端 AI 工具实现/修改某屏时，先读 `docs/08-UI-SPECS/screens/<screen>.yaml`，不看对端源码。** 如果 spec 不存在，先创建 spec 再写代码。

每屏一个 YAML 规格（`docs/08-UI-SPECS/screens/`），定义：
- **元素树**——每屏有哪些元素、分组、父子关系（对应 §0 信息层级）
- **每元素 anchor + size**——引用 `design-tokens.json` 的 token 名（如 `topBar.height`），不写裸数值
- **系统栏状态**——状态栏显隐 + 内容色、Home Indicator/导航区处理（对应 §1.3）
- **Back 栈优先级链**——面板 > 选择 > 页面返回（对应 §1.3 Back 机制）
- **状态机**——idle / panel_expanded / searching / selection_mode / permission_denied 等
- **已知差异**——允许的平台原生差异项必须显式登记

现有 spec（持续扩充，见 `docs/08-UI-SPECS/screens/` 目录）：
- `docs/08-UI-SPECS/screens/camera.yaml` — 相机屏
- `docs/08-UI-SPECS/screens/gallery-grid.yaml` — 相册网格屏
- `docs/08-UI-SPECS/screens/chat.yaml` — 聊天屏
- `docs/08-UI-SPECS/screens/settings.yaml` — 设置屏
- `docs/08-UI-SPECS/screens/model-download-center.yaml` — 模型下载中心
- `docs/08-UI-SPECS/screens/editor.yaml` — 图片编辑器
- `docs/08-UI-SPECS/screens/person.yaml` — 人物页

### 2.2 实现后：视觉 + 量化地面真值

正确参照物三件套（本项目流水线，`tmp/ui-reference/`）：

1. **截图**——视觉地面真值。逐状态采集（idle / 面板展开 / 交互中 / 各权限态），AI 直接「看」
2. **视图树 dump**（uiautomator / AccessibilityService）——量化地面真值。每个元素的精确 bounds、text、contentDescription
3. **归一化 INDEX**——从 dump 提炼的换算基准：**占屏比例 + dp 尺寸**，不是 px。每页还必须登记系统栏状态（§1.3）：状态栏显隐 + 内容色、系统导航区实测高度（三键/手势模式不同）、Back 优先级链。例：
   - 「顶部栏高 56dp，锚 safe area 下缘」而非「y=84~252px」
   - 「底部三行各高 44/52/108dp，总占屏底 204dp」
   - 「快门外径 76dp，中心位于屏宽 50%、距底 72dp」
   - 「网格 4 列，间距 2dp，日期头高 40dp」

## 3. 设计令牌（Design Tokens）作为 SSOT

**Token SSOT = `shared/src/commonMain/resources/design-tokens.json`**（v2.0.0）；双端镜像（Android `Spacing`/`AppShapes`/`Color`/`Typography`、iOS `DesignTokens.swift`）由 `scripts/gen-design-tokens.py` 生成，**禁止手改**，`ai-gate.sh --check` 门禁拦截。完整工作流与消费规则见 [`DESIGN_TOKENS_SPEC.md`](DESIGN_TOKENS_SPEC.md)。spec 中尺寸一律引用 token 名，不写裸数值——**对齐收敛为 token 对齐**：

| Token 类 | 示例 |
|---|---|
| 间距阶梯 | 4/8/12/16/24dp |
| 控件尺寸 | 顶栏 56dp、快门 76dp、功能按钮 44dp、对焦框 100dp |
| 圆角 | 面板 24pt（顶部两角）、缩略图 0/8dp |
| 颜色 | 对焦青 #00E5FF、面板背 #CC000000、选中态主色 |
| 字阶 | 标题 17sp/label 12sp（sp≈pt 同迁移；字体各自原生） |
| 面板比例 | 美颜面板 35% 屏高、滤镜网格 5 列 |

Ardot/Figma 画布为 token **可视化预览层**（非 SSOT），由 `sync-ardot-variables.py` 从生成物推送。

## 4. 平台差异的显式处理

- **图标**：Material Icons → SF Symbols 语义映射（语义一致，形状不强求）
- **导航与 Back**：见 §1.3——Android 系统 Back 的每个到达状态都需在 iOS 有显式等价物且优先级一致；Pager 横滑与系统边缘手势冲突需实测
- **权限**：Android 单次授权 vs iOS Limited/AddOnly 四态——状态语义对齐（已落地 `AccessState`），UI 呈现按 Android 对应态布局
- **字体缩放**：iOS Dynamic Type / Android 字体缩放在 MVP 期锁定默认档，后续 Phase 评估
- ** keyboard/刘海/折叠屏/平板**：MVP 期手机竖屏单形态，尺寸类别适配留后续（[Android 自适应尺寸类别](https://developer.android.com/design/ui/mobile/guides/layout-and-content/grids-and-units?hl=zh-cn)）

### 4.1 无障碍（Accessibility）双端对齐

> 无障碍不是可选附加——VoiceOver / TalkBack 能正确读到的 UI，通常在极端机型和网络条件下也更健壮。([QAwerk 无障碍清单](https://qawerk.com/blog/mobile-app-accessibility-testing/))

| 维度 | Android | iOS | 对齐要求 |
|------|---------|-----|---------|
| **屏幕阅读器** | TalkBack | VoiceOver | 🔴 所有交互元素必须暴露 label + role + state；swipe 导航顺序 = 视觉阅读顺序 |
| **语义标注** | `Modifier.semantics { contentDescription = "..." }` / `testTag` | `.accessibilityLabel("...")` / `.accessibilityHint("...")` | 🔴 label 文案双端一致（经 i18n）；纯装饰元素标记为不可聚焦 |
| **焦点管理** | `FocusRequester` + 弹窗 `Modifier.focusGroup()` | `@FocusState` + `.accessibilityAddTraits(.isModal)` | 🔴 弹窗打开时焦点进入弹窗、关闭时返回触发器；隐藏元素不得接收焦点 |
| **触控目标** | ≥ **48×48dp** | ≥ **44×44pt** | 🟢 以 Android 48dp 为基准 → iOS 用 48pt，天然双达标 |
| **色彩对比度** | WCAG 2.1 AA：正文 ≥ **4.5:1**、大字 ≥ **3:1** | 同上 + 系统「增强对比度」开关 | 🔴 双端在浅色/深色模式下均需达标；用 `MaterialTheme.colorScheme` / 语义色自动适配 |
| **动效减弱** | `Settings.Global.ANIMATOR_DURATION_SCALE = 0` | `UIAccessibility.isReduceMotionEnabled` | 🟡 检测系统「减弱动效」开关，降级为淡入淡出或瞬时切换 |

**AI 工具规则**：每屏 spec 的元素树中，交互元素必须标注 `accessibility_label` 字段。spec 验收清单追加一项：TalkBack/VoiceOver 通读无遗漏。

### 4.2 深色/浅色模式一致性

> **语义色 > 硬编码色**：颜色用平台语义色（`MaterialTheme.colorScheme.surface` / `Color(uiColor: .systemBackground)`），让系统自动适配深色/浅色。([Apple Dark Mode 指南](https://developer.apple.com/documentation/uikit/supporting-dark-mode-in-your-interface))

| 规则 | 说明 |
|------|------|
| **禁止硬编码白/黑** | `Color.White` / `Color.Black` 在浅色模式下文字不可见——用 `colorScheme.onSurface` / `colorScheme.surface` 替代 |
| **功能色固定** | 不随主题切换的固定色（对焦青 #00E5FF、面板半透明黑）登记在 `AppColors`，与主题色分离 |
| **半透明分层** | 双端统一用 `surface.copy(alpha = 0.3f)` / `Color.primary.opacity(0.3)` 分层，不用裸 rgba |
| **深色模式验证** | 每屏验收必须在**浅色 + 深色**两种模式下截图比对——浅色通过不代表深色通过 |

### 4.3 动效与触觉反馈映射

> 动效一致性是**时长 + 曲线的语义对齐**，不是逐帧复刻。([200ms 规则](https://www.appypie.com/blog/mobile-app-animation-guide))

| 动效类型 | 推荐时长 | Android（Compose） | iOS（SwiftUI） |
|---------|---------|-------------------|---------------|
| **微交互**（tap/ripple/toggle） | 100–200ms | `tween(150)` + `FastOutSlowInEasing` | `.easeInOut(duration: 0.15)` |
| **面板展开/收起** | 250–350ms | `tween(300)` + `FastOutSlowInEasing` | `.spring(response: 0.3, dampingFraction: 0.8)` |
| **页面转场** | 300–400ms | 平台默认 NavHost 过渡 | 平台默认 NavigationStack push/pop |
| **弹簧/物理** | — | `spring(dampingRatio, stiffness)` | `.spring(response:dampingFraction:)`——参数语义不同，需调试匹配 |

**触觉反馈**（Haptic Feedback）：

| 场景 | Android | iOS |
|------|---------|-----|
| 开关切换 / 模式选择 | `HapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)` | `UIImpactFeedbackGenerator(.light).impactOccurred()` |
| 快门拍照 | `HapticFeedbackType.HandleMoveDown` | `UIImpactFeedbackGenerator(.medium).impactOccurred()` |
| 长按确认 | `HapticFeedbackType.LongPress` | `UIImpactFeedbackGenerator(.heavy).impactOccurred()` |

> 🟡 动效和触觉反馈属"平台原生质感"层——曲线和实现各自原生，但**触发时机一致**（同一操作在两端都触发反馈）。

### 4.4 RTL 与本地化

> 本项目当前五语（en / zh-CN / zh-TW / es / fr），均为 LTR 语言。RTL 预留扩展点，暂不实现。([Bitrise i18n 指南](https://bitrise.com/blog/post/introduction-to-app-localization-in-ios-and-android))

| 维度 | 规则 | 当前状态 |
|------|------|---------|
| **字符串** | 🔴 禁止硬编码用户可见文案——必须走 `strings.xml`（Android）/ `Localizable.strings`（iOS）；五语同步（[I18N] 红线） | ✅ 已落地 |
| **RTL 布局** | 🟡 预留：布局用 `start/end` 而非 `left/right`（Android）/ `.leading/.trailing`（iOS） | 🟡 Compose 已用 `start/end`；iOS 待 Phase 6.x 验证（Phase 5 已发，五语均 LTR，RTL 暂不实现） |
| **日期/数字** | 🟡 双端用平台 locale 格式化器（`DateTimeFormatter` / `java.time.format.DateTimeFormatter`），不手动拼接 | ✅ 已落地 |
| **复数** | 🟡 使用平台复数资源（Android plurals.xml / iOS stringsdict 或 String Catalog） | 🟡 暂未使用，当前五语无复数差异 |
| **字符长度** | 🟡 德语/俄语等翻译后文案可能膨胀 30%+，布局需弹性（不裁断、不溢出） | 🟡 当前五语长度接近，暂无问题 |

### 4.5 软键盘适配

| 维度 | Android | iOS | 对齐要求 |
|------|---------|-----|---------|
| **弹出避让** | `WindowInsets.ime` / `imePadding()` | `.ignoresSafeArea(.keyboard)` + 手动 offset 或 `TextField` 自带行为 | 🔴 键盘弹出时输入框不被遮挡，底栏上移或收起 |
| **返回键收键盘** | `BackHandler` 在键盘弹出时先收键盘 | 点击空白处 / 拖拽下划收键盘 | 🔴 Android Back 先收键盘（不退出页面）对应 iOS 点击空白收键盘 |
| **键盘类型** | `KeyboardType.Number` / `.Email` / `.Password` | `.keyboardType(.numberPad)` / `.emailAddress` / `.secure` | 🔴 同一输入框双端 keyboardType 一致 |

## 5. 验证闭环（对齐必须可度量）

```
基准端采集（截图+dump）→ 归一化 INDEX + tokens → iOS 实现
      → iOS 同状态截图回采 → ui_diff_check 双端比对 + 关键元素 bounds 抽检
      → 差异修复 → 再比对 → 真机人工验收（终态）
```

- **容差定义**：布局结构零容差（元素有无/锚定关系/比例）；尺寸 ±2dp 容差；平台材质项（图标形状/字体/涟漪）免检
- **抽检清单**：每屏至少抽 5 个关键元素做归一化 bounds 对比（顶栏高/底栏高/主按钮尺寸与位置/面板占比/网格列宽）
- 截图比对用 `ui_diff_check`（MCP 工具），量化抽检从 dump/Accessibility 数据算

## 6. 反模式清单（本项目实证）

| 反模式 | 后果 |
|---|---|
| 读源码脑补布局再翻译 | 两轮返工，真机验收「完全对不上」 |
| px 直接当 dp 用 | 高密度机上整体放大 2~3 倍 |
| 硬编码绝对坐标 | 换机型/换刘海形态即错位 |
| 忽略 safe area | 顶栏被刘海吃、底栏被手势条挡 |
| 用平台默认控件拼装（NavigationStack/TabView/List 默认样式） | 信息层级全面缺失（相册差距分析 🔴26 项的根因；注：该分析为 2026-08-08 快照，多数 gap 已于后续重构关闭，见 [`2026-08-10-ios-kmp-doc-drift-audit.md`](../reviews/2026-08-10-ios-kmp-doc-drift-audit.md)） |
| 位图只供单倍率 | 高倍屏模糊 |
| 硬编码 Color.White / Color.Black | 深色模式下文字不可见 |
| 交互元素无 accessibilityLabel | TalkBack/VoiceOver 读不出功能，视障用户完全无法使用 |
| 动效时长不一致 | 双端体验割裂感（一端「跟手」另一端「拖沓」） |
| 键盘弹出不避让 | 输入框被遮挡，用户看不到正在输入什么 |

## 7. 一句话总结

> **参照用眼睛和数字（截图+dump 归一化），尺寸用 dp/pt 三件套（锚定+固定+比例），系统栏与 Back 逐页登记显式映射（§1.3），无障碍/深色/动效/RTL/键盘按 §4 显式处理，一致看结构与功能不看像素与材质，差异必须登记，验收走双端截图比对闭环（浅色+深色双跑）。**

---

### 参考来源
- [Android 官方：网格和单位（密度无关像素、自适应尺寸类别）](https://developer.android.com/design/ui/mobile/guides/layout-and-content/grids-and-units?hl=zh-cn)
- [UXPin：iOS vs Android UI Design 9 Key Differences（44pt vs 48dp 触控目标）](https://www.uxpin.com/studio/blog/ios-vs-andoid-ui-design-for-mobile/)
- [Smart Interface Design Patterns：Designing for Mobile（375pt/360dp 设计基准宽）](https://smart-interface-design-patterns.com/articles/designing-for-mobile-ios-android-guide/)
- [Muzli：Responsive UI、Densities and Asset Scaling](https://medium.muz.li/designing-for-mobile-a-deep-dive-into-responsive-ui-screen-densities-and-asset-scaling-f8766363ab08)
- [Moldstud：跨平台一致 UI（共享 style guide、相对单位、矢量资源、字阶）](https://moldstud.com/articles/p-multi-platform-game-development-ensuring-consistent-ui-design-for-ios-and-android)
- [QAwerk：Mobile App Accessibility Checklist](https://qawerk.com/blog/mobile-app-accessibility-testing/)
- [Requestly：Mobile Accessibility Checklist for Android and iOS](https://requestly.com/blog/mobile-accessibility-checklist/)
- [Apple Developer：Supporting Dark Mode in Your Interface](https://developer.apple.com/documentation/uikit/supporting-dark-mode-in-your-interface)
- [Swift by Sundell：Defining Dynamic Colors in Swift](https://www.swiftbysundell.com/articles/defining-dynamic-colors-in-swift)
- [Appy Pie：200ms Rule for Mobile Animation](https://www.appypie.com/blog/mobile-app-animation-guide)
- [Bitrise：Introduction to App Localization in iOS and Android](https://bitrise.com/blog/post/introduction-to-app-localization-in-ios-and-android)
- 本项目实证（2026-08-08 快照；相机/相册视图层已大幅重构，多数 gap 已关闭，勿用于当前规划，见 [`2026-08-10-ios-kmp-doc-drift-audit.md`](../reviews/2026-08-10-ios-kmp-doc-drift-audit.md)）
