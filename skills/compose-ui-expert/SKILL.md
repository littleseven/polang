---
name: compose-ui-expert
description: |
  PoLang Jetpack Compose UI 专家。诊断布局异常、状态管理问题、重组性能瓶颈，确保 HyperOS 视觉风格一致。
version: 1.1.1
created: 2026-05-25
updated: 2026-08-03
maintainer: "[RD] 全栈工程师"
tags:
  - compose
  - ui
  - jetpack
  - layout
  - state
  - performance
  - hyperos
---


# Compose UI 专家 (Compose UI Expert)

> **定位**：诊断 Compose 布局、状态管理、重组性能问题，确保符合 HyperOS 视觉规范。
> **触发时机**：修改 UI 代码、新增 Screen/Component、布局异常、动画卡顿、状态不同步时。

---

## 核心原则

1. **状态优先使用 Sealed Class**：UI 状态必须用 `sealed class` 建模，禁止用多个独立 Boolean。
2. **显式命名 Lambda 参数**：禁止隐式 `it`，必须显式命名。
3. **禁止通配符导入**：所有 import 必须显式列出。
4. **日志标签统一**：UI 状态变更日志遵循全局 `PoLang:[FeatureName]` 格式（如 `PoLang:Gallery`），无独立 UX 标签。

---

## 诊断流程

### Step 1: 布局异常排查

| 症状 | 检查点 | 修复 |
|------|--------|------|
| 元素不显示 | `Modifier` 链中是否有 `size` / `fillMaxSize` | 确认外层容器提供约束 |
| 文字截断 | `Text` 是否有 `overflow = TextOverflow.Ellipsis` | 添加或调整 `softWrap` |
| 点击无响应 | 是否被上层 `Box` 拦截 | 调整 `zIndex` 或 `pointerInput` 层级 |
| 预览与真机不一致 | `@Preview` 参数是否匹配目标设备 | 使用 `device = Devices.PIXEL_4` |

### Step 2: 状态管理排查

**检查清单**：
- [ ] `remember` vs `rememberSaveable` 选择正确（配置变更后需恢复 → `rememberSaveable`）
- [ ] `LaunchedEffect` 依赖项是否完整（遗漏导致不触发）
- [ ] `collect` lambda 中是否读取 `StateFlow.value` 而非捕获的局部变量
- [ ] `derivedStateOf` 是否用于复杂计算（避免重复计算）
- [ ] 状态提升层级是否恰当（不过高导致重组范围过大）

### Step 3: 重组性能排查

```bash
# 启用 Compose 编译器指标
./gradlew :androidApp:assembleDebug -PcomposeCompilerReports=true
# 查看 build/compose_metrics/ 下的报告
```

**红线指标**：
- **可跳过率 < 80%**：说明大量 Composable 被强制重组
- **稳定类比例 < 90%**：检查数据类是否标记 `@Stable` / `@Immutable`

---

## HyperOS 视觉规范检查

| 规范项 | 标准 | 检查方式 |
|--------|------|----------|
| 圆角 token | 走 `AppShapes`（panel=12 顶角/lg=16/card=8/button=10/thumbnail=2），禁止裸 `RoundedCornerShape(n.dp)` | 引用 `core/designsystem/AppShapes`（token SSOT：`shared/src/commonMain/resources/design-tokens.json`，生成物勿手改） |
| 毛玻璃效果 | blur 20dp + alpha 0.5-0.8 | `Modifier.blur(20.dp)` + 半透明背景 |
| 流体动效 | Bezier (0.4, 0.0, 0.2, 1) | `CubicBezierEasing(0.4f, 0f, 0.2f, 1f)` |
| 微交互 | 长按触感 + 点击缩放 0.95x | `HapticFeedback` + `scale = 0.95f` |
| Primary 色 | #00E5FF | `MaterialTheme.colorScheme.primary` |

---

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| **LaunchedEffect 闭包捕获** | 状态值始终为初始值 | 在 lambda 内读取 `StateFlow.value` |
| **remember 跨重组失效** | 配置旋转后状态丢失 | 改用 `rememberSaveable` |
| **Modifier 链顺序错误** | padding/click 效果异常 | `padding` 在 `clickable` 之前 |
| **无限重组** | CPU 占用高、界面闪烁 | 检查 `State` 是否在 `Composable` 内创建 |
| **LazyColumn 键缺失** | 滚动位置跳动、数据错乱 | 提供稳定的 `key` 参数 |

## 相关文件

- [docs/01-PRODUCT/FEATURES.md](docs/01-PRODUCT/FEATURES.md) — 交互规范与设计系统
- [androidApp/src/main/java/com/mamba/picme/core/designsystem/AGENTS.md](androidApp/src/main/java/com/mamba/picme/core/designsystem/AGENTS.md) — 设计系统规范
- [error-healer](/error-healer) — 编译错误修复
- [perf-optimizer](/perf-optimizer) — 重组性能分析
- [i18n-validator](/i18n-validator) — UI 文案多语言验证
- [intent-router](/intent-router) — UI 需求意图路由
- [swiftui-expert](/swiftui-expert) — iOS SwiftUI 对标（双端视觉一致）

---

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-25 | 初始版本 |
| 1.1.0 | 2026-08-09 | 追加 [PARITY] 双端一致性规则段 |
| 1.1.1 | 2026-09-25 | 圆角规范对齐 AppShapes token 实况（28dp+ 大圆角声明不实）；删虚构 `PoLang:UX` 标签；版本号对齐 frontmatter |

---

## [PARITY] 双端 UI 一致性（强制）

> 实现或修改任何屏幕的 UI 时，必须遵循 `ui-parity-guard` skill 的 5 步硬规则。

**核心约束**：
1. **Android vibe coding 不受约束**——自由迭代，硬编码 `dp` 可以。但 UI **定稿后**（iOS 开工前），必须固化 `docs/08-UI-SPECS/screens/<screen>.yaml` + 提取新 token 到 `design-tokens.json` → 跑 `python3 scripts/gen-design-tokens.py` 重新生成双端镜像。
2. **定稿后替换硬编码**：Android 定稿代码中的关键尺寸替换为 `MaterialTheme.spacing.xxx` / `MaterialTheme.appShapes.xxx`，使后续 token 改动生效。
3. **后续修改走三同步**：改 spec → 同步改两端代码 → 同步改 token（如有新值：改 JSON → 跑生成器）。

详见：[`ui-parity-guard`](/ui-parity-guard) · [`docs/08-UI-SPECS/README.md`](/docs/08-UI-SPECS/README.md) · [`DESIGN_TOKENS_SPEC.md`](/docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md) · [`IOS_ANDROID_UI_PARITY.md`](/docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md)
