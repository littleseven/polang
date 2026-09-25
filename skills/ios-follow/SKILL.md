---
name: ios-follow
description: |
  Android 功能定稿后驱动 iOS 对等跟随的编排管线：diff 分析 → 契约固化（spec/tokens/平台差异台账）→ iOS 实现 → 双端验收 → gap 报告，全自动到底、断点续跑。Use when an Android feature is finalized and iOS needs to follow to parity, when catching up iOS 1.0 feature gaps screen by screen, or when the user runs /ios-follow.
version: 1.0.0
created: 2026-08-10
updated: 2026-08-10
maintainer: "[RD] 全栈工程师"
tags:
  - ios
  - parity
  - orchestration
  - spec
  - kmp
---

# /ios-follow — Android 完成后 iOS 一键对等跟随

> **定位**：把「Android 探路 → 契约固化 → iOS 实现 → 双端验收」四环节串成**一条命令**的编排器（spec/token/skill/hook/ios-auto-dev-loop 零件的运转总线）。
> **触发时机**：Android 功能在专用分支提交完成后执行；或 iOS 1.0 追齐期按功能/屏名追平 Android 存量功能。
> **设计 SSOT**：`docs/superpowers/specs/2026-08-10-ios-follow-command-design.md`（D1-D4 决策锁定）；本文是其可执行形态，冲突以设计文档为准。
> **上游**：`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`（五层防线总纲）、`docs/08-UI-SPECS/README.md`（Vibe Coding → 固化 Spec → iOS 翻译流程 SSOT）。

---

## 用法（双模）

| 模式 | 命令 | 适用 |
|------|------|------|
| **A. 分支 diff 模式** | `/ios-follow`（无参数） | 新功能：Android 刚在专用分支完成，自动分析 `main...HEAD` diff |
| **B. 功能追齐模式** | `/ios-follow <功能或屏名>`（如 `/ios-follow tag-scan`、`/ios-follow editor`） | 🔖 **iOS 1.0 追齐期主用**：Android 存量功能已在 main，不依赖分支 diff，从 Android 现状反向提取契约 |

两模式共用 Stage 2-5 管线；差别只在 Stage 1 的输入来源与 Stage 0 的分支要求。

## 核心铁律（不可违反）

1. **不动 Android 已验证的业务/UI 代码**。唯一例外：token 契约固化——改 `design-tokens.json` 后跑 `python3 scripts/gen-design-tokens.py` 重新生成双端镜像（`Spacing.kt`/`AppShapes.kt`/`Color.kt`/`DesignTokens.swift` 均为**生成物，由生成器整体重写，禁止手改**；`--check` 门禁拦截不一致）；Android 既有引用与逻辑仍不动。Android 侧未收敛到接口的平台调用 → 台账登记技术债，iOS 侧按业务语义写 actual，不回头重构 Android。
2. **iOS 实现读 spec 不读 Android 源码**（[PARITY] 红线）。尺寸/颜色必须引用 `DesignTokens.swift` 常量。
3. **shared 接口 iOS 零重写**：commonMain 变更经 XCFramework 重建直接获得；新 Flow/suspend 桥用 SKIE 形态（suspend→`async throws` / sealed→`onEnum` 穷举 / Flow→`for await`），不再新增 FlowWatcher 式手写桥（**REQUIRED BACKGROUND:** `skills/kmp-ios-interop/SKILL.md`）。
4. **模型分工**（根 AGENTS.md §3.5）：Stage 2 契约固化 = K3（决定天花板的事）；Stage 3 UI 翻译可 GLM coder 子 agent、平台 actual/复杂管线必须 `model="primary"`(K3)；**审查与实现模型交叉**——GLM 写的 K3 审，K3 写的 GLM 审。
5. **自动修复重试 ≤2 次**；仍失败 → 停止并附诊断（systematic-debugging 路径），不盲目堆尝试。

---

## Stage 0 — 前置检查（不满足则提示先处理，不硬闯）

| 检查 | 模式 A | 模式 B |
|------|--------|--------|
| 隔离工作区（根 AGENTS.md §3.4） | 当前已在 Android 功能的 worktree 分支上 | 确认或创建 iOS follow 专用 worktree 分支（iOS 改动落此处） |
| Android 编译绿 | `./gradlew :androidApp:assembleDebug` | 同左 |
| 无未提交变更 | `git status` 干净 | 同左 |
| 断点续跑 | 读 `tmp/ios-follow/<branch>/state.json`，从第一个非 done 阶段续跑 | 读 `tmp/ios-follow/<feature>/state.json`，同左 |

## Stage 1 — 输入分析（GLM explore 子 agent，default secondary）

**模式 A（分支 diff）**——`git diff main...HEAD` 解析：

- **涉及屏清单**：`androidApp/src/main/.../features/<x>/` 改动 → 屏名映射
- **shared 契约变更**：`shared/src/commonMain/` 接口/模型/use case 的新增与签名变更
- **平台能力调用点**：权限、相册、相机、文件等 API 关键词扫描

**模式 B（功能追齐）**——不跑 diff，改走 **spec 存在性检查**：

1. `docs/08-UI-SPECS/screens/<screen>.yaml` 存在且覆盖该功能 → 直接采用为契约基线，分析项 = 该屏 + 关联 shared 接口 + 台账差异项
2. spec 缺失 → 标记「Stage 2 需先反向提取」，分析项从 Android 现状（`androidApp` 对应 features 目录 + shared 接口）梳理
3. 同模式 A 输出 shared 契约清单与平台能力调用点（从 Android 现状全量扫，非 diff）

**产物**：`tmp/ios-follow/<branch|feature>/follow-plan.md`（三份清单 + 后续阶段执行项）。

**提前退出**：纯内部重构（无 UI 屏、无 shared 契约变更）→ 跳到 Stage 4 做回归验收，不空跑契约与实现阶段。

## Stage 2 — 契约固化（K3 主 agent，🚫 不下放 GLM）

对 follow-plan 中每一项：

1. **UI Spec**：`docs/08-UI-SPECS/screens/<screen>.yaml` 新建或更新（参照 `camera.yaml`/`gallery-grid.yaml` 格式；质量标准见 `docs/08-UI-SPECS/PARITY_MASTER_PLAN.md` §4.2——自包含/到元素粒度/状态机完整/Back 栈显式/allowed_differences 登记）。
   - 模式 B 且 spec 缺失时：从 Android 定稿代码 + 截图**反向提取**（`docs/08-UI-SPECS/README.md` ②步方式 B：读实现代码 + 定稿截图输出 yaml，新尺寸归一化进 tokens）。
   - 新尺寸/颜色/圆角提取进 `shared/src/commonMain/resources/design-tokens.json` → 跑 `python3 scripts/gen-design-tokens.py` 自动重新生成 Android/iOS 镜像（生成物禁止手改，详见 `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md`）。
   - `adb exec-out screencap` 采集定稿截图到 `tmp/ui-reference/<screen>.png`（设备不在线则复用已有截图并在报告中标记）。
2. **平台差异台账**（spec 内 `platform_differences` 节，本管线的契约层新增）：
   - `permission`：双端权限模型与状态机映射（如 Android 单次授权 vs iOS Full/Limited/AddOnly/Denied 四态）→ shared 语义对齐点
   - `capabilities`：API 能力矩阵（功能 × 端 → 支持 / 替代方案 / 平台独有流程），shared 接口只暴露业务语义
   - `privacy_disclosure`：Android（Manifest + Play Data Safety）与 iOS（purpose string + `PrivacyInfo.xcprivacy` + 隐私标签）披露对照
3. **shared 接口签名登记**：提取 commonMain 签名到 `tmp/ios-follow/<branch|feature>/contracts.md`（只登记，不改 Android 代码）。

**契约冲突停止**：新提取的 spec/台账与现有 iOS 实现或既有 spec 矛盾 → 列出冲突点停，等用户裁决，不替用户猜。

## Stage 3 — iOS 实现

- **shared 变更**：iOS 零重写——`JITPACK=true ./gradlew :shared:assembleSharedKitDebugXCFramework` 重建 + XcodeGen 刷新（`iosApp/scripts/build-shared-kit.sh` 增量路径）。Swift 侧消费 SKIE 形态（铁律 3）。
- **UI**（GLM coder 子 agent）：读 spec + tokens + 定稿截图翻译 SwiftUI；🔴 禁止读 Android 源码翻译布局；尺寸/颜色引用 `DesignTokens.swift`。
- **平台 actual**（K3，`model="primary"`）：读台账 + shared 接口实现；台账登记的平台独有流程（如 iOS Limited 权限管理入口、系统删除确认弹窗）照台账实现。
- 每步过既有 hook（i18n 硬编码 / dp/color 硬编码警告）与 `ios-i18n-validator`。

## Stage 4 — 自动验收

1. `JITPACK=true ./gradlew :shared:jvmTest`（领域逻辑回归）
2. `./scripts/ios-auto-dev-loop.sh --diff`（xcodegen → pods → xcodebuild test → 真机编译安装启动 → 截图黑屏体检 → syslog 崩溃检查 → Android↔iOS SSIM 像素 diff，阈值 0.80）
3. 截图比对**浅色 + 深色双跑**（`docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md` §4.2）
4. 失败处理：自动修复重试 **≤2 次**；仍失败 → 停止并附诊断。真机不在线时支持模拟器降级路径则降级、否则停并报告。

## Stage 5 — 报告

1. **gap analysis**（审查模型与实现模型交叉，铁律 4）：审 iOS 侧 diff → `docs/reviews/<date>-ios-follow-<feature>.md`，🔴/🟡/✅ 分级，🔴 未清零则整体判 FAIL。
2. **验收报告**（`tmp/ios-follow/<branch|feature>/report.md` + 终端摘要）显式分三栏：
   - ✅ **自动通过**：编译 / shared 单测 / 截图比对 / 无崩溃
   - ⚠️ **待真机终验**：手感、观感、性能、真机任务流（命令绿 ≠ 做完，按「功能 > UI > 性能」优先级由用户终验）
   - 📋 **技术债清单**：未提取的 shared 接口、台账新增差异项、跳过的截图采集等

---

## 断点续跑

`tmp/ios-follow/<branch|feature>/state.json` 记录各阶段 `done/pending/failed`：

```json
{
  "feature": "tag-scan",
  "mode": "B",
  "stages": { "0": "done", "1": "done", "2": "done", "3": "failed", "4": "pending", "5": "pending" },
  "updated": "2026-08-10T09:00:00+08:00"
}
```

重跑 `/ios-follow` 从第一个非 done 阶段续跑，不重做已完成阶段；各阶段产物文件（follow-plan.md / contracts.md / report.md）即续跑锚点。

**全自动模式下仅两种情况中途停止**：① 修复重试耗尽的构建/验收失败（附诊断停）；② 契约冲突（列冲突点停，等用户裁决）。

## 验收边界（防「命令绿了 ≠ 做完了」）

| 层 | 命令能判的 | 判不了的（留用户） |
|----|-----------|-------------------|
| 编译/单测 | ✅ 双端编译绿、shared jvmTest 过 | — |
| 结构对齐 | ✅ 截图比对（浅色+深色）、布局结构零容差、尺寸 ±2dp | 观感主观项（如美颜强度观感） |
| 稳定性 | ✅ 黑屏体检、崩溃信号检查 | 长时间使用稳定性 |
| 体验 | — | ⚠️ 手感、动效跟手度、性能体感、真机任务流终验 |

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| diff 分析误判涉及屏（跨屏共享组件改动） | follow-plan 屏清单多/漏 | follow-plan 在报告中回显，用户可对误判项人工补跑单屏（模式 B 指定屏名） |
| 模式 B 当成分支模式跑 | main 上无 diff，Stage 1 空输出 | 追齐期必须带功能/屏名参数走模式 B |
| iOS 翻译时偷看 Android 源码 | spec 形同虚设，漂移照旧 | [PARITY] 红线：只读 spec + tokens + 截图；spec 不全先补 spec |
| 新 Flow 桥又写 FlowWatcher | 与 SKIE 互斥，同一条 Flow 两种消费形态冲突 | 用 SKIE `for await` 直消费，见 `skills/kmp-ios-interop` |
| GLM 写完 GLM 审 | 同模型写审一体 = 没审 | 铁律 4 交叉：GLM 写的 K3 审，K3 写的 GLM 审 |

## 相关文件

- 设计 SSOT：`docs/superpowers/specs/2026-08-10-ios-follow-command-design.md`
- 总纲：`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`；流程 SSOT：`docs/08-UI-SPECS/README.md`；方法论：`docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md`
- spec 示例：`docs/08-UI-SPECS/screens/camera.yaml` / `gallery-grid.yaml`；tokens：`shared/src/commonMain/resources/design-tokens.json`
- [kmp-ios-interop](skills/kmp-ios-interop/SKILL.md) — SKIE 互操作铁律（Stage 3 shared 消费必读）
- [ui-parity-guard](skills/ui-parity-guard/SKILL.md) — 5 步硬规则
- [dev-loop](skills/dev-loop/SKILL.md) / `scripts/ios-auto-dev-loop.sh` — 验收闭环载体
- SKIE 形态实证数据 — 见 [kmp-ios-interop](skills/kmp-ios-interop/SKILL.md) §SKIE（2026-08-10 spike 报告已清理）

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-08-10 | 初始版本：设计文档可执行形态；Stage 1 双模（分支 diff / 功能追齐——追齐期主用后者）；SKIE 形态纳入 Stage 3 铁律 |
