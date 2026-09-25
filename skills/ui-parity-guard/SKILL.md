---
name: ui-parity-guard
description: |
  双端 UI 一致性守卫。实现/修改任何 UI 屏幕前，强制走 spec → token → 截图闭环，
  替代已证伪的"读对端源码翻译"路线。双端共用（Android Compose + iOS SwiftUI）。
version: 1.1.0
created: 2026-08-09
updated: 2026-08-16
maintainer: "[RD] 全栈工程师"
tags:
  - ui
  - parity
  - design-tokens
  - spec
  - android
  - ios
  - compose
  - swiftui
---

# UI Parity Guard Skill

> **总纲**：`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`（五层防线体系 + 子文档索引）
> **红线**：[PARITY] 已纳入根 `AGENTS.md` §5 全局红线

> **定位**：双端 UI 一致性的强制约束层——让 K3（写 Compose）和 GLM（写 SwiftUI）各自只看共享的 token/spec，不靠"读对端源码脑补"。
> **触发时机**：① iOS 实现/修改任何屏幕的 UI 时自动启用；② Android 新页面 UI 定稿后固化 spec 时启用。
> **研发模式**：Android vibe coding 自由迭代 → 定稿后固化 spec（②）→ iOS 按 spec 实现（③）。详见 `docs/08-UI-SPECS/README.md`。

---

## 🔴 硬规则（RULE）

### Android 端：定稿后固化（Vibe Coding 不受约束）

Android 新页面开发期间无 spec 约束，自由迭代。**UI 定稿后（iOS 开工前）**，必须执行：

1. **固化 spec**：从定稿代码/截图反向提取 `docs/08-UI-SPECS/screens/<screen>.yaml`（可派 AI 提取）。不存在则创建。
2. **提取 token**：定稿中的新尺寸/颜色/圆角加到 `design-tokens.json`，然后跑 `python3 scripts/gen-design-tokens.py` **自动重新生成**双端镜像（Android `Spacing.kt` / `AppShapes.kt` / `Color.kt`、iOS `DesignTokens.swift` 均为生成物，**禁止手改**；`ai-gate.sh` 的 `--check` 门禁会拦截）。
3. **建议替换硬编码**：Android 代码中的关键硬编码值替换为 token 引用（`MaterialTheme.spacing.xxx`），使后续 token 改动生效。
4. **采集定稿截图**：`adb exec-out screencap -p > tmp/ui-reference/<screen>.png`，作为 iOS 视觉参照。

### iOS 端：按 spec 实现（禁止读 Android 源码）

iOS 实现/修改任何屏幕的 UI 时，**必须**：

1. **先读 `docs/08-UI-SPECS/screens/<screen>.yaml`**。不存在则要求 Android 端先固化 spec。
2. **引用 design token 常量**（`Spacing.xxx` / `AppShapes.xxx` / `AppColors.xxx`），禁止硬编码 `.frame(width:)` / `Color(red:)`。
3. **定稿截图作视觉参照**（`tmp/ui-reference/<screen>.png`）——看"长什么样"，但**代码参照是 spec 不是 Android 源码**。

### 后续修改：三同步

UI 定稿后的改动（两端都已实现后），走三同步：
1. 改 `docs/08-UI-SPECS/screens/<screen>.yaml`
2. 同步改 Android（Compose，引用 token）+ iOS（SwiftUI，引用 token）
3. 同步改 `design-tokens.json`（如有新值）→ 跑生成器重新生成双端镜像

**禁止只改一端代码不改 spec。禁止通过读对端源码来"翻译"布局**（此路线已被两轮真机验收证伪）。

---

## 一致性分层（什么必须一致）

| 层 | 对齐要求 | 示例 |
|---|---|---|
| 信息层级 | 🔴 零容差 | 每屏有哪些元素、分组、优先级 |
| 布局结构 | 🔴 零容差（归一化后） | 元素相对位置、锚定关系、尺寸比例 |
| 功能与默认 | 🔴 零容差 | 功能集、默认值、排序、状态机 |
| 文案与状态 | 🔴 零容差 | 空态/加载态/权限态（经 i18n） |
| 字体/图标 | 🟢 平台原生 | Roboto vs SF、Material vs SF Symbols |
| 系统交互 | 🟢 平台原生 | 导航返回方式、权限申请流、picker 形态 |
| 动效曲线/实现 | 🟢 平台原生 | 缓动函数、spring 参数（但触发时机必须一致） |
| 材质细节 | 🟢 平台原生 | 涟漪 vs 高亮、阴影风格、动画曲线 |

> **原则**：用户的心智模型和任务流一致；平台的视觉语言各自原生。凡是允许不同的项，必须登记进 spec 的 `allowed_differences`，不允许悄悄不同。

### 行业标准适配（§4.1–§4.5，详见 IOS_ANDROID_UI_PARITY.md）

实现 UI 时还需检查以下维度：

- **无障碍**：交互元素必须有 accessibilityLabel（Android `contentDescription` / iOS `.accessibilityLabel`）；焦点顺序 = 视觉阅读顺序；触控目标 ≥ 48dp
- **深色模式**：禁止硬编码 `Color.White`/`Color.Black`；用 `colorScheme` 语义色；每屏验收必须浅色+深色双跑
- **动效**：微交互 100–200ms、面板展开 250–350ms；同一操作在两端都触发触觉反馈
- **RTL/本地化**：布局用 `start/end`（Android）/ `.leading/.trailing`（iOS）而非 left/right；用户可见文案必须走资源文件
- **键盘**：弹出时输入框不被遮挡；Android Back 先收键盘不退出页面

---

## 反模式（已实证）

| 反模式 | 后果 |
|--------|------|
| 读源码脑补布局再翻译 | 两轮返工，真机验收「完全对不上」 |
| px 直接当 dp/pt 用 | 高密度机上整体放大 2~3 倍 |
| 硬编码绝对坐标 | 换机型/换刘海形态即错位 |
| 忽略 safe area | 顶栏被刘海吃、底栏被手势条挡 |
| 用平台默认控件拼装 | 信息层级全面缺失 |
| 硬编码 Color.White / Color.Black | 深色模式下文字不可见 |
| 交互元素无 accessibilityLabel | TalkBack/VoiceOver 读不出功能 |
| 动效时长/触发不一致 | 双端体验割裂感 |
| 不更新 spec 就改 UI | 双端持续漂移，gap analysis 永远清不完 |
| 键盘弹出不避让 | 输入框被遮挡 |

---

## 相关文件

- `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md` — Token 工作流 SSOT（codegen + Ardot 预览层）
- `shared/src/commonMain/resources/design-tokens.json` — Token SSOT
- `docs/08-UI-SPECS/screens/*.yaml` — 逐屏规格契约
- `docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md` — 完整方法论
- `docs/reviews/2026-08-10-ios-android-consistency-gap.md` — 现行差距审计（08-08 相机/相册两份为历史快照）
- Android: `androidApp/src/main/java/com/mamba/picme/core/designsystem/`（生成物 + 手写 `Theme.kt`）
- iOS: `DesignSystem/DesignTokens.swift`（生成物）

---

## 版本历史

| 日期 | 变更 |
|------|------|
| 2026-08-16 | token 流程改 codegen（改 JSON → 生成器，镜像禁止手改）；补 Claude Code 镜像 |
| 2026-08-09 | 初版：5 步硬规则 + 分层模型 + 反模式清单 |
