# 双端 UI 一致性体系总纲（Cross-Platform UI Parity Master Plan）

> **版本**：2.0（2026-08-16 重整：L2 数据层升级 codegen、各层状态刷新、消除与子文档的重复维护）
> **创建**：2026-08-09 · **状态**：生效中 · **维护者**：项目开发者
> **定位**：双端 UI 一致性的**顶层架构文件**，负责编排各层零件与追踪状态。**流程细节以 `docs/08-UI-SPECS/README.md` 为 SSOT，token 细节以 `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md` 为 SSOT**——本文不重复维护，只给指针与状态。

---

## 0. 演进史（历史注记，不再逐条维护）

2026-08-09 建纲时诊断出七个断裂点（红线缺失 / skill 不自动触发 / token 未被消费 / spec 只覆盖 2 屏 / 验证无自动化 / hook 只查 i18n / 顶层无双端章节）。此后陆续闭合：

- **[PARITY] 红线**入根 `AGENTS.md` §5；双端总纲/方法论入 §7 文档索引（2026-08-10 复核确认）。
- **spec 扩到 7 屏**（camera / gallery-grid / chat / settings / model-download-center / editor / person）。
- **hook 硬编码拦截**上线：`.kimi-code/hooks/lib/parity-hardcode.sh`（观察级警告，编辑 `.kt` 时触发）。
- **token 层升级 codegen**（2026-08-15，`c3651beab`）：`design-tokens.json` 唯一 SSOT，双端镜像由 `scripts/gen-design-tokens.py` 生成、`ai-gate.sh --check` 门禁拦截手改。
- **「Figma/Ardot 画布作 SSOT」实验废止**（2026-08-15）：非设计师维护者不适合以设计画布作 SSOT，画布降级为**可视化预览层**（历史决策以 `AI_TOOLS.md` 变更记录 2026-08-15/16 条目为准；原 spec 已清理，细节查 git 历史）。

原「零件齐全但缺总纲」的根因已消——当前体系即本文 §1 五层防线。

---

## 1. 体系架构：五层防线

```
┌─────────────────────────────────────────────────────────┐
│ L5: 红线层 — 根 AGENTS.md [PARITY] 红线                   │
│     触发：每次代码改动（AI 工具开机即加载）                 │
├─────────────────────────────────────────────────────────┤
│ L4: 流程层 — Vibe Coding → 固化 Spec → iOS 翻译           │
│     载体：docs/08-UI-SPECS/README.md + ui-parity-guard skill        │
├─────────────────────────────────────────────────────────┤
│ L3: 规格层 — Per-Screen Spec（docs/08-UI-SPECS/screens/*.yaml）      │
│     载体：camera / gallery-grid / chat / settings / ...  │
├─────────────────────────────────────────────────────────┤
│ L2: 数据层 — Design Tokens codegen（SSOT→生成双端镜像）   │
│     载体：design-tokens.json + gen-design-tokens.py      │
├─────────────────────────────────────────────────────────┤
│ L1: 验证层 — Hook 拦截 + --check 门禁 + 截图比对闭环      │
│     载体：parity-hardcode.sh / ai-gate.sh / reviews/     │
└─────────────────────────────────────────────────────────┘
```

---

## 2. L5 红线层：[PARITY]

定义见根 `AGENTS.md` §5 全局红线表：**双端 UI 一致性——信息层级/布局结构/功能默认/文案状态/无障碍语义零容差一致。新页面 Android 定稿后必须固化 spec；iOS 实现必须读 spec 不读 Android 源码；后续修改走三同步（spec + 双端代码 + token）**。验证方式：spec 完整性检查、截图比对、gap analysis。

## 3. L4 流程层：Vibe Coding 研发模式

**SSOT = `docs/08-UI-SPECS/README.md`**（新页面 4 步 / 老页面改造 / 后续修改三同步 / 何时需要 spec / 成本账），本文不重复。一句话概括：**Android 自由探路 → 定稿反向固化 spec（+ token）→ iOS 读 spec 不读源码 → 截图比对 + 真机验收（浅色+深色双跑）**。②③④已编排为单命令 `/ios-follow`（六阶段管线 + 断点续跑）。

## 4. L3 规格层：Per-Screen Spec

**载体**：`docs/08-UI-SPECS/screens/*.yaml`。

### 4.1 当前覆盖（2026-09-18 行数核验）

| Spec | 行数 | 状态 |
|------|------|------|
| `camera.yaml` | 1170 | ✅ 完整（gap analysis 全部 P0/P1/P2；2026-08-26 相机路由化/头像拍摄链路已同步） |
| `gallery-grid.yaml` | 1026 | ✅ 完整（相册整理一级入口/悬浮 Tab 已同步） |
| `chat.yaml` | 778 | ✅ 已建 |
| `editor.yaml` | 646 | ✅ 已建 |
| `settings.yaml` | 507 | ✅ 已建（2026-08-26 列表式主菜单改版已同步） |
| `model-download-center.yaml` | 390 | ✅ 已建 |
| `person.yaml` | 386 | ✅ 已建 |
| `idphoto.yaml` | 418 | ✅ 已建（2026-08-16 /ios-follow idphoto 反向提取；含 FUSION 抠图管线/构图数学/修补契约全量） |
| `tag-control.yaml` | 181 | ✅ 已建 |
| `organize.yaml` | 471 | ✅ 已建（2026-09-07 整理中心类目体系 v2 落地固化：hub/类目详情/cleaned/swipe 口径 + 已知残留口径差台账） |
| `topbar.yaml` | 106 | ✅ 已建 |
| `tag(photo-info)` / `memory` | — | ❌ 按需待建（对应屏启动 iOS 对齐时先补 spec） |

### 4.2 Spec 质量标准

1. **自包含**——iOS 开发者读 spec + tokens + 定稿截图就能写代码，不需要读 Android 源码
2. **到元素粒度**——每个交互元素：id / type / anchor / size（引用 token 名）/ 图标 / 颜色 / 行为
3. **状态机完整**——列出所有页面状态（idle / panel_expanded / searching / selection_mode ...）
4. **Back 栈显式**——面板 > 选择 > 页面返回的优先级链
5. **允许差异登记**——平台原生差异显式登记到 `allowed_differences`
6. **红线规则前置**——如"禁止系统默认 NavigationStack/TabView"等硬约束

### 4.3 platform_differences 台账

底层平台差异（权限模型映射 / API 能力矩阵 / 隐私披露对照）登记为 spec 内 `platform_differences` 节，由 `/ios-follow` Stage 2 契约固化时随 spec 一并产出/更新。设计见 `docs/superpowers/specs/2026-08-10-ios-follow-command-design.md` §2。

## 5. L2 数据层：Design Tokens codegen

**SSOT = `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md`**（工作流 / 双端生成物清单 / iOS 应用指南 / 内容色板附录 / 消费漂移记录），本文不重复。要点：

- **唯一 SSOT**：`shared/src/commonMain/resources/design-tokens.json`（v2.0.0）。
- **双端镜像全部由 `scripts/gen-design-tokens.py` 生成，禁止手改**（Android `Spacing`/`AppShapes`/`Color`/`Typography`/`DesignTokens.kt`；iOS `DesignTokens.swift`；仅 Android `Theme.kt` 手写保留）。`ai-gate.sh` 跑 `--check` 门禁，生成物与 JSON 不一致即 fail。
- **Ardot = token 活体预览层**（非 SSOT）：`sync-ardot-variables.py` 把生成器输出的 `ardot-variables.json` 推入 Ardot 画布（`PoLang Tokens` 变量集）。
- **存量硬编码替换**：Android 仍有大量内联 `.dp`/色值绕过 token（漂移清单见 DESIGN_TOKENS_SPEC §4）。策略不变——**不一次性全替，改到哪替到哪**：新代码必须引用 token（hook 观察级提醒）；改老文件时顺手替换；高频文件（CameraPreviewContent / MediaPager / BeautyPanel）优先。

## 6. L1 验证层：自动化拦截 + 人工验收

### 6.1 Hook 拦截（✅ 已上线）

`.kimi-code/hooks/post-edit-check.sh` 在编辑 `.kt` 时调用 `lib/parity-hardcode.sh`：designsystem/ 目录之外出现 `\d+\.dp` / `Color(0x........)` 即打印 ⚠️（观察级 exit 0，不阻断）。i18n 硬编码检测同链路（`i18n-hardcode.sh`）。

### 6.2 CI 门禁（✅ 已上线）

`scripts/ai-gate.sh` 内置 Design Tokens Sync Check：`python3 scripts/gen-design-tokens.py --check`——生成物与 JSON 不一致即 fail，杜绝手改镜像。

### 6.3 截图比对（半自动，验收时触发）

```
Android 截图（adb screencap）+ iOS 截图（XCUITest / App 内捕获）
     ↓ ui_diff_check（MCP 工具）双端比对 / screenshot-diff.py
差异 > 阈值 → 修复 → 再比对 → 差异 ≤ 阈值 → 人工真机验收（终态）
```

容差：布局结构零容差；尺寸 ±2dp；平台材质项免检。每屏验收必须**浅色 + 深色双跑**。

### 6.4 Gap Analysis 闭环（审计驱动）

**现行审计 SSOT = `docs/reviews/2026-08-10-ios-android-consistency-gap.md`**（逐屏差异清单，含 2026-08-12 内联解决批次标注；后续解决项继续行内标 ✅）。更早的 `2026-08-08-ios-camera/gallery-ui-gap-analysis.md` 为被取代的历史快照，勿用于当前规划。

流程：iOS 实现完成 → 逐元素对照审计（🔴/🟡/✅）→ gap 反馈回 spec（spec 漏了的补上）→ 修复 → 再审，直到 🔴 = 0。

### 6.5 完整性闸门（可选验收工具）

`scripts/completeness-check.sh <screen> <state>`：iOS a11y 树 dump（`docs/08-UI-SPECS/screens/refs/ios/`）vs 设计帧 node 树（`docs/08-UI-SPECS/screens/refs/figma/`）逐元素匹配，判「该有的元素在不在」。Figma/Ardot 先导期产物；画布已降级预览层后，此闸门作为**可选**逐屏验收工具保留（帧参照 Ardot 云端画布，快照 `docs/08-UI-SPECS/screens/refs/ardot/`；Android 真值截图 `docs/08-UI-SPECS/screens/refs/android/`）。

---

## 7. 子文档索引（消除碎片化）

| 层 | 文档/载体 | 定位 |
|----|------|------|
| **总纲** | 本文（`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`） | 顶层编排 + 状态追踪 |
| **红线** | 根 `AGENTS.md` §5 | [PARITY] 红线定义 |
| **流程** | `docs/08-UI-SPECS/README.md` | Vibe Coding 研发模式 SSOT（新页面/老页面/三同步） |
| **方法论** | `docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md` | 度量体系/系统栏/Back/无障碍/深色/动效/RTL/键盘 |
| **Token SSOT** | `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md` | token 工作流 + iOS 应用指南 + 内容色板 + 漂移记录 |
| **Spec** | `docs/08-UI-SPECS/screens/*.yaml`（11 份）+ `refs/`（双端地面真值） | 逐屏完整规格 |
| **Skill** | `skills/ui-parity-guard/SKILL.md`（镜像 `.claude/commands/ui-parity-guard.md`） | UI 任务硬规则约束层 |
| **Skill** | `skills/compose-ui-expert/SKILL.md` [PARITY] 段 / `skills/swiftui-expert/SKILL.md` [PARITY] 段 | 双端各自约束 |
| **编排** | `skills/ios-follow/SKILL.md`（镜像 `.claude/commands/ios-follow.md`） | /ios-follow 六阶段管线；设计 SSOT `docs/superpowers/specs/2026-08-10-ios-follow-command-design.md` |
| **Ardot 工具** | `.kimi-code/ARDOT_MCP.md` | Ardot MCP 用法速查（prompt/工具链/排障）+ token 预览同步操作细节 |
| **Hook** | `.kimi-code/hooks/post-edit-check.sh`（`lib/parity-hardcode.sh`） | 硬编码拦截 |
| **CI 门禁** | `scripts/ai-gate.sh`（含 token `--check`） | 质量门禁 |
| **闸门** | `scripts/completeness-check.sh` + `scripts/completeness/` | 完整性逐元素核对（可选） |
| **Gap 审计** | `docs/reviews/2026-08-10-ios-android-consistency-gap.md` | 现行差异清单 SSOT（08-08 两份为历史快照） |
| **历史决策** | `AI_TOOLS.md` 变更记录 2026-08-15/16 条目 | Figma/Ardot SSOT 实验（已废止；spec/plan 已清理，细节查 git 历史） |

---

## 8. 待执行行动项（2026-08-16 刷新）

| # | 行动 | 优先级 | 状态 |
|---|------|--------|------|
| A1 | 根 AGENTS.md §5 [PARITY] 红线 | — | ✅ 完成 |
| A2 | post-edit hook 追加 dp/color 硬编码检测 | — | ✅ 完成（`parity-hardcode.sh`） |
| A3/A4/A5 | docs/08-UI-SPECS/README、ui-parity-guard、AGENTS.md §7 引用总纲 | — | ✅ 完成 |
| A6 | 存量 token 替换：`CameraPreviewContent.kt`（~68 处） | 🟡 P1 | ❌ 开放（改到哪替到哪） |
| A7 | 存量 token 替换：`MediaPager.kt`（~80 处） | 🟡 P1 | ❌ 开放 |
| A8 | 存量 token 替换：`BeautyPanel.kt`（~15 处） | 🟢 P2 | ❌ 开放 |
| A9 | chat 屏 spec 产出 | — | ✅ 完成（另 editor/person 亦已建） |
| A10 | 自动截图 diff CI（双端截图 → 比对） | 🟢 P2 | ❌ 开放（依赖 iOS 侧截图自动化稳定） |
| A11 | `docs/08-UI-SPECS/screens/refs/android/`（27MB 真值截图）入库策略 | 🟢 P2 | 🔶 半决策：`polang-ui-spec.fig` 已删除（2026-08-16，Ardot 仅云端，git 形态改走 `docs/08-UI-SPECS/screens/refs/ardot/` 快照）；android 真值目录仍 untracked 待决策 |

---

## 9. 一句话总结

> **[PARITY] 是红线，spec 是契约，`design-tokens.json` 是数据源（镜像 codegen 生成 + 门禁守卫），skill 是约束层，hook/CI 是拦截层，gap analysis 是审计层，Ardot/Figma 只做预览。各层各司其职、SSOT 各归其位，由本文总纲编排运转——不靠单一零件保证一致性，靠体系闭环。**
