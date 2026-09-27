# Design System 规范指令 (UI/UX Guard)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

你是 PoLang 的视觉守护者。负责确保所有 UI 组件符合 HyperOS 极简美学，并保持全局视觉一致性。

## 1. 核心设计准则 (Design Tokens)

- **[TOKENS] 严禁硬编码**：禁止在 Compose 代码中直接使用 `Color(0xFF...)` 或 `16.dp`。必须引用 `MaterialTheme.spacing`（`Spacing` object）、`MaterialTheme.appShapes`（`AppShapes` object）、`MaterialTheme.appColors`（`AppColors` object）或 `MaterialTheme.colorScheme`（M3 主题色）。
- **[TOKENS-SOURCE] 双端 SSOT + codegen**：所有 token 的唯一事实来源是 `shared/src/commonMain/resources/design-tokens.json`。改 token = 改 JSON → 跑 `python3 scripts/gen-design-tokens.py` 重新生成双端镜像。本目录的 `Spacing.kt` / `AppShapes.kt` / `Color.kt` / `Typography.kt` / `DesignTokens.kt` 与 iOS `DesignTokens.swift` 均为**生成物，禁止手改**（文件头有 GENERATED 标记；`ai-gate.sh` 的 `--check` 门禁拦截不一致）。`Theme.kt` 为手写保留（主题装配逻辑），色值引用 `Color.kt` 生成常量。
- **[THEME] 主题色适配**：所有文字和图标颜色必须使用 Material 3 主题色：
    - **前景色**：`MaterialTheme.colorScheme.onSurface` - 自动适配浅色/深色模式
    - **主色**：`MaterialTheme.colorScheme.primary` - 强调和高亮
    - **主色前景**：`MaterialTheme.colorScheme.onPrimary` - 主色上的文字
    - **表面色**：`MaterialTheme.colorScheme.surface` - 组件背景
    - **表面变体**：`MaterialTheme.colorScheme.surfaceVariant` - 次要背景
    - **禁止使用硬编码的 `Color.White` 或 `Color.Black`**，这会导致浅色模式下文字不可见
- **[BLUR] 毛玻璃规范**：所有悬浮面板（如控制栏、菜单）必须带动态模糊效果（使用 `RenderEffect` 或自定义毛玻璃层）。
- **[SHAPE] 圆角标准**：
    - 顶层卡片/面板：`24.dp`。
    - 交互按钮：`CircleShape` (全圆角)。
    - 列表项/网格项：引用 `MaterialTheme.appShapes.thumbnail`（2dp）。

## 2. 交互与动效规范
- **[EASING] 非线性动效**：所有动画必须使用 `FastOutSlowInEasing` 或 `StandardEasing`，严禁使用线性动画。
- **[HAPTIC] 触感反馈**：关键操作（开关、模式切换）必须伴随 `LocalHapticFeedback` 触发。

## 3. Agent 校验项
- 修改组件后，必须运行 `render_compose_preview` 验证在 Light/Dark 模式下的色彩对比度。
- 确保所有自定义组件都提供了对应的 `@Preview` 函数。
- **[THEME-CHECK] 主题色适配检查**：
  - 检查所有文字颜色是否使用 `MaterialTheme.colorScheme.onSurface`
  - 检查所有图标颜色是否使用主题色（`onSurface`、`primary` 等）
  - 检查所有背景色是否使用 `MaterialTheme.colorScheme.surface` 或 `surfaceVariant`
  - 检查是否有硬编码的 `Color.White` 或 `Color.Black`（仅允许在 Color Scheme 定义中使用）
  - 在浅色模式和深色模式下都要验证文字可读性

## 4. 常见主题色适配错误

### ❌ 错误示例
```kotlin
// 错误：硬编码白色文字
Text(
    text = "磨皮",
    color = Color.White  // ❌ 浅色模式下不可见
)

// 错误：硬编码黑色文字
Text(
    text = "保存",
    color = Color.Black  // ❌ 深色模式下不可见
)

// 错误：硬编码半透明白色
Icon(
    imageVector = Icons.Rounded.Face,
    tint = Color.White.copy(alpha = 0.6f)  // ❌ 不适配主题
)
```

### ✅ 正确示例
```kotlin
// 正确：使用主题前景色
Text(
    text = "磨皮",
    color = MaterialTheme.colorScheme.onSurface  // ✅ 自动适配
)

// 正确：使用主题色作为强调
Text(
    text = "保存",
    color = MaterialTheme.colorScheme.primary  // ✅ 强调色
)

// 正确：使用主题色的半透明版本
Icon(
    imageVector = Icons.Rounded.Face,
    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)  // ✅ 自动适配
)

// 正确：背景使用表面变体
Box(
    modifier = Modifier
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))  // ✅ 自动适配
)
```

## 5. 主题色速查表

| 使用场景 | 推荐色彩 | 说明 |
|---------|---------|------|
| 正文文字 | `onSurface` | 主要内容文字 |
| 次要文字 | `onSurface.copy(alpha = 0.6f)` | 辅助说明文字 |
| 禁用文字 | `onSurface.copy(alpha = 0.4f)` | 不可用状态 |
| 强调文字 | `primary` | 需要突出的文字 |
| 主色上的文字 | `onPrimary` | 按钮、高亮区域的文字 |
| 图标（常规） | `onSurface` | 普通图标 |
| 图标（激活） | `primary` | 选中、激活的图标 |
| 图标（次要） | `onSurface.copy(alpha = 0.6f)` | 次要功能图标 |
| 卡片背景 | `surface` | 主要表面 |
| 次级背景 | `surfaceVariant` | 区分层级的背景 |
| 半透明背景 | `surfaceVariant.copy(alpha = 0.3f)` | 悬浮面板等 |

## 6. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ **HyperOS 视觉风格** → 大圆角设计（24dp+）、毛玻璃效果、流体动效
- ✅ **深色/浅色模式适配** → Material3 Theme 系统统一配色，无硬编码颜色
- ✅ **无障碍支持** → 所有 Composable 提供 contentDescription，色彩对比度符合 WCAG 标准
- ✅ **微交互反馈** → 关键操作伴随触感反馈（HapticFeedback）
- ✅ **非线性动效** → 使用 FastOutSlowInEasing / StandardEasing，模拟物理惯性

**技术决策记录**:
- **选择 Material3 Design System**: 符合 HyperOS 风格、组件丰富、社区活跃、长期维护有保障
- **使用 CompositionLocal 传递主题**: 避免参数层层透传、提升可维护性、支持动态主题切换
- **RenderEffect 实现毛玻璃**: 性能优于传统 Canvas 方案、支持实时模糊、兼容 Android 12+
- **Theme Tokens 统一管理**: 禁止硬编码颜色和尺寸、确保全局一致性、便于后期调整

**参考文档**:
- PRODUCT.md Section 3.3: 设计系统与规范
- docs/01-PRODUCT/FEATURES.md §6.1: HyperOS 风格
- docs/01-PRODUCT/FEATURES.md §6.2: 色彩系统
