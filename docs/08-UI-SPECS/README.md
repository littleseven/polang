# Per-Screen UI 研发流程

> **总纲**：`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`（五层防线体系 + 子文档索引）
> **红线**：[PARITY] 已纳入根 `AGENTS.md` §5 全局红线

> **适用**：PoLang 项目所有新页面的 UI 开发（双端：Android Compose + iOS SwiftUI）
> **前置依赖**：`design-tokens.json`（Token SSOT）、`skills/ui-parity-guard/SKILL.md`（硬规则）
> **角色**：个人开发者，Android 为主栈，Vibe Coding 风格，无设计稿

---

## 核心原则

> **Android 探路 → 定稿固化 spec → iOS 按 spec 翻译。**
>
> Spec 不约束 Android 的创作过程，只在 Android 定稿和 iOS 开工之间起桥梁作用。

以前的模式是 Android 代码即"设计稿"，iOS 直接读代码翻译——两轮真机验收证伪。新模式下 Android 仍然自由迭代，但定稿后**固化成 spec**（+ 新 token），iOS 读 spec 不读代码。

---

## 流程（4 步）

```
① Android Vibe Coding（你 + K3，自由迭代，无 spec 约束）
     ↓ UI 定稿，感觉对了
② 固化 Spec（从定稿代码/截图反向提取 docs/08-UI-SPECS/screens/<new>.yaml + 新 token）
     ↓
③ iOS 实现（GLM 或你，读 spec，不读 Android 代码）
     ↓
④ 截图比对 + 真机验收
```

### ① Android Vibe Coding

**和以前完全一样**——你在 Compose 里边写边调，K3 辅助，不需要先写 spec，不需要先定义 token。尺寸可以先用硬编码 `.dp`，布局可以随意迭代。

这一步的产出是：**一个你满意的 Android UI** + 一张定稿截图（`tmp/ui-reference/`）。

> ⚠️ **唯一建议**：vibe coding 期间如果用了新的关键尺寸（如特殊面板高度、独特图标大小），在代码里用命名常量（`private val panelHeight = 0.45f`）而非裸数值，方便②步提取。

### ② 固化 Spec（Android 定稿后，iOS 开工前）

**这是关键一步**——从定稿的 Android 反向提取 spec，把"临场决定"变成"结构化契约"。

两种方式（按效率选）：

**方式 A：你手写 spec（10-15 分钟）**

参照 `docs/08-UI-SPECS/screens/camera.yaml` / `gallery-grid.yaml` 格式，从定稿代码提取：

```yaml
# docs/08-UI-SPECS/screens/<new-page>.yaml
system_bars:          # 状态栏显隐 + 内容色 / Home 指示器处理
back_stack:           # Back 优先级链（面板 > 选择 > 退出）
elements:             # 元素树：anchor + size（引用 token 名）
  root:
    top_bar: { height: topBar.height, ... }
    content: { ... }
states:               # idle / panel_expanded / ...
allowed_differences:  # 允许的平台原生差异
```

**方式 B：派 AI 提取 spec（更省力）**

给 AI（K3）指令：「读 `CameraPreviewContent.kt` 和这张定稿截图，输出 `docs/08-UI-SPECS/screens/camera.yaml`，尺寸引用 `design-tokens.json` 的 token 名。新出现的尺寸加到 tokens 里。」AI 做提取 + 归一化比从零写更快。

**无论哪种方式，②的产出是四件套**：
1. `docs/08-UI-SPECS/screens/<new>.yaml` — 结构化规格
2. `design-tokens.json` 新增 token（如有）→ 跑 `python3 scripts/gen-design-tokens.py` **自动重新生成**双端镜像（Android `Spacing.kt` 等 / iOS `DesignTokens.swift` 均为生成物，禁止手改；详见 `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md`）
3. 定稿截图（如尚未采集）
4. **Android 代码中的硬编码值建议替换为 token 引用**（可选，但强烈推荐——否则后续改 token 时 Android 不生效）

### ③ iOS 实现

**iOS 读 spec + design-tokens.json + DesignTokens.swift + 定稿截图**，写 SwiftUI。**不读 Android 源码**。

spec 已经包含所有需要的信息：元素树（anchor + size）、系统栏状态、Back 栈、状态机。截图提供视觉参照（"长什么样"），spec 提供参数（"多大/多远/什么比例"）。

这步可以交给 GLM 做或你亲自做。交给 GLM 时，prompt 模板：
> 读 `docs/08-UI-SPECS/screens/<screen>.yaml` 和 `tmp/ui-reference/<screenshot>.png`，按 spec 实现该屏 SwiftUI。尺寸引用 `DesignTokens.swift` 常量。

### ④ 验收

- Android 截图 + iOS 截图（`adb screencap` / `xcrun simctl io screenshot`）
- 比对：信息层级对不对、布局结构像不像、尺寸比例是否一致
- 容差：布局结构零容差；尺寸 ±2dp；平台材质项（图标形状/字体/涟漪）免检
- 详见 `IOS_ANDROID_UI_PARITY.md` §5

验收通过后 spec 归档为该屏的**永久 SSOT**。以后改 UI 先改 spec，再改两端代码。

---

## 自动化封装：/ios-follow（一条命令跑完 ②③④）

上述 ②固化 Spec → ③iOS 实现 → ④验收 已编排为单命令 skill：`skills/ios-follow/SKILL.md`（镜像 `.claude/commands/ios-follow.md`，设计 SSOT `docs/superpowers/specs/2026-08-10-ios-follow-command-design.md`）。

- `/ios-follow`（无参数）：新功能模式——分析当前分支 `main...HEAD` diff，自动推断涉及屏 / shared 契约 / 平台调用点
- `/ios-follow <功能或屏名>`：**追齐模式（iOS 1.0 追齐期主用）**——不依赖分支 diff，从 Android 现状反向提取契约（spec 缺失时先补 spec）

契约固化阶段除 spec/tokens 外还会登记**平台差异台账**（spec 内 `platform_differences` 节：权限模型映射 / API 能力矩阵 / 隐私披露对照）。断点续跑锚点：`tmp/ios-follow/<branch|feature>/`。

---

## 后续修改流程

UI 定稿后，后续的改动（加功能、调尺寸）走**三同步**：

```
改 spec（docs/08-UI-SPECS/screens/<screen>.yaml）
     ↓
同步改 Android（Compose 代码，引用 token）
同步改 iOS（SwiftUI 代码，引用 token）
同步改 token（如有新值：改 design-tokens.json → python3 scripts/gen-design-tokens.py 重新生成双端镜像）
```

spec 是"当前事实的记录"。改的时候三处一起改，不允许只改一端代码。

---

## 老页面怎么办

老页面（Android 已存在，iOS 待对齐）的流程和新页面几乎一样，只是 ①已经完成了：

```
① Android 已有（已完成）
     ↓
② 固化 Spec（从现有 Android 代码/截图反向提取）
     ↓
③ iOS 按 spec 实现（重做或修复对齐）
     ↓
④ 截图比对 + 验收
```

现有的 `docs/08-UI-SPECS/screens/camera.yaml` 和 `gallery-grid.yaml` 就是这样从 Android 反向提取的。

---

## 什么时候需要写 spec

| 场景 | 需要 spec？ | 原因 |
|------|------------|------|
| 自定义布局（面板、悬浮控件、自定义网格） | ✅ 必须 | 双端差异风险高 |
| 复杂状态机（多面板 / 选择模式 / 权限态） | ✅ 必须 | 状态不对齐 = 体验断裂 |
| 相机 / 编辑器 / 美颜等核心交互页 | ✅ 必须 | 体验敏感，信息层级零容差 |
| 纯系统控件列表（设置页、关于页） | ❌ 跳过 | 双端用各自原生控件，天然无差异 |
| 纯文本 / Web 内容页 | ❌ 跳过 | 无自定义布局 |

---

## 成本

| 环节 | 耗时 | 说明 |
|------|------|------|
| ① Android vibe coding | 与以前相同 | 不变 |
| ② 固化 spec | 10-15 分钟（手写）/ 5 分钟（AI 提取） | 一次性，定稿后做 |
| ③ iOS 实现 | 与以前相近 | 但省掉了翻译返工 |
| ④ 验收 | 10 分钟 | 截图比对 |

**不写 spec 的后果**：iOS 翻译返工（两轮证伪，2 天+）、gap analysis 审计 + 修复、双端持续漂移。固化 spec 的 15 分钟换掉这些，净赚。

---

## Ardot 设计稿资产（`refs/ardot` 快照管线）

`docs/08-UI-SPECS/screens/refs/ardot/` 承载画布快照（PNG/structure.json）与组件目录，完整编排见 `skills/ardot-design-ops/SKILL.md`：

- 组件目录 SSOT:`refs/ardot/components.json`(新组件入库标准见 `skills/ardot-design-ops/SKILL.md`)
- 设计健康检查:`scripts/ardot-health-check.py`(literal/组件化违规/布尔漏适配/目录漂移,事件驱动必跑)

---

## 相关文件

- `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md` — Token 工作流 SSOT（codegen + Ardot 预览层）
- `shared/src/commonMain/resources/design-tokens.json` — Token SSOT
- `docs/08-UI-SPECS/screens/camera.yaml` / `gallery-grid.yaml` — spec 示例（从 Android 反向提取的）
- `skills/ui-parity-guard/SKILL.md` — 5 步硬规则
- `docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md` — 完整方法论
