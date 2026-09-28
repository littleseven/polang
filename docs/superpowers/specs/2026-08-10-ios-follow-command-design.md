# /ios-follow 命令设计（Android 完成后 iOS 一键对等跟随）

> **日期**：2026-08-10
> **状态**：待用户审批
> **上游**：`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`（五层防线总纲）、`docs/08-UI-SPECS/README.md`（Vibe Coding → 固化 Spec → iOS 翻译）、`docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md`（对齐方法论）
> **背景**：双端开发工作流中，「Android 探路 → 契约固化 → iOS 实现 → 双端验收」四个环节已各自有零件（spec/token/skill/hook/ios-auto-dev-loop），缺一个把它们串成**一条命令**的编排器。本设计补这一环，同时补 `platform_differences` 台账层（底层平台差异的契约化缺口）。

---

## 0. 决策锁定（brainstorming 已确认）

| # | 决策 | 结论 |
|---|------|------|
| D1 | 输入粒度 | **按需求/分支 diff**——命令自动分析当前分支与 main 的 diff，推断涉及屏、shared 接口、平台改动 |
| D2 | 自治程度 | **全自动到底**——一条命令跑完分析→契约→实现→验收→报告，仅失败/契约冲突时停 |
| D3 | follow 范围 | **全管线**——UI 屏 + shared 接口/模型 + 平台 actual 都 follow；**不动 Android 已验证的业务/UI 代码**（未收敛到接口的平台调用登记技术债，不重构 Android）。唯一例外：token 常量文件（`Spacing.kt` 等）**纯新增**常量属契约固化的一部分，允许；禁止改既有引用与逻辑 |
| D4 | 命令形态 | **编排型 Skill**（方案 A）+ 断点续跑（吸收方案 C）；不写新编排骨架脚本 |

---

## 1. 命令形态与前置

- **载体**：`skills/ios-follow/SKILL.md` + `.claude/commands/ios-follow.md` 镜像（与现有 30 个 skill 同构，双工具可用）
- **用法**：Android 功能在专用分支提交完成后执行 `/ios-follow`，无参数
- **前置检查（Stage 0）**，不满足则提示先处理、不硬闯：
  1. 当前在 worktree 隔离分支上（根 AGENTS.md §3.4 工作区隔离规范）
  2. Android 编译绿（`./gradlew :androidApp:assembleDebug`）
  3. 改动已全部提交（工作区无未提交变更）

---

## 2. 六阶段管线

每阶段落地产物文件 = 断点续跑锚点。产物根目录：`tmp/ios-follow/<branch>/`。

### Stage 1 — diff 分析（GLM explore 子 agent）

- `git diff main...HEAD` 解析：
  - **涉及屏清单**（`androidApp/src/main/.../features/<x>/` 改动 → 屏名映射）
  - **shared 契约变更**（`shared/src/commonMain/` 接口/模型/use case 的新增与签名变更）
  - **平台能力调用点**（权限、相册、相机、文件等 API 关键词扫描）
- 产物：`follow-plan.md`（三份清单 + 后续阶段执行项）
- **提前退出**：纯内部重构（无 UI 屏、无 shared 契约变更）→ 跳到 Stage 4 做回归验收，不空跑契约与实现阶段

### Stage 2 — 契约固化（K3）

对 follow-plan 中每一项：

1. **UI Spec**：`docs/08-UI-SPECS/screens/<screen>.yaml` 新建或更新（参照 `camera.yaml` / `gallery-grid.yaml` 格式）；新尺寸/颜色/圆角提取进 `design-tokens.json` → 跑 `python3 scripts/gen-design-tokens.py` 重新生成双端镜像（Android `Spacing.kt` 等 / iOS `DesignTokens.swift` 均为生成物，禁止手改，见 `DESIGN_TOKENS_SPEC.md`）；`adb exec-out screencap` 采集定稿截图到 `tmp/ui-reference/<screen>.png`（设备不在线则复用已有截图并在报告中标记）
2. **平台差异台账**（本设计新增的契约层）：spec 内新增 `platform_differences` 节，登记：
   - `permission`：双端权限模型与状态机映射（如 Android 单次授权 vs iOS Full/Limited/AddOnly/Denied 四态）→ shared 语义对齐点
   - `capabilities`：API 能力矩阵（功能 × 端 → 支持 / 替代方案 / 平台独有流程），shared 接口只暴露业务语义
   - `privacy_disclosure`：Android（Manifest + Play Data Safety）与 iOS（purpose string + `PrivacyInfo.xcprivacy` + 隐私标签）披露对照
3. **shared 接口签名登记**：从 diff 提取 commonMain 签名到 `contracts.md`（只登记，不改 Android 代码）

产物：spec yaml 变更 + tokens 变更 + `contracts.md`

### Stage 3 — iOS 实现（模型分工：UI 翻译 → GLM；平台 actual / 复杂管线 → K3）

- **shared 变更**：iOS 零重写，`./gradlew :shared:assembleSharedDebugXCFramework` 重建 + XcodeGen 刷新（`iosApp/scripts/build-shared-kit.sh` 增量路径）
- **UI**：读 spec + tokens + 定稿截图翻译 SwiftUI（GLM coder 子 agent）；🔴 禁止读 Android 源码翻译布局；尺寸/颜色引用 `DesignTokens.swift`
- **平台 actual**：读台账 + shared 接口实现（K3）；台账登记的平台独有流程（如 iOS Limited 权限管理入口、系统删除确认弹窗）照台账实现
- **铁律**：
  - 不动 Android 业务/UI 代码（token 常量纯新增除外，见 D3）
  - Android 侧未收敛到接口的平台调用 → 台账登记技术债，iOS 侧按业务语义写 actual，不回头重构 Android
- 每步过既有 hook（i18n 硬编码 / dp/color 硬编码警告）与 `ios-i18n-validator`

### Stage 4 — 自动验收

1. `./gradlew :shared:jvmTest`（领域逻辑回归）
2. `./scripts/ios-auto-dev-loop.sh --diff`（xcodegen → pods → xcodebuild test → 真机编译安装启动 → 截图黑屏体检 → syslog 崩溃检查 → Android↔iOS SSIM 像素 diff，阈值 0.80）
3. 截图比对**浅色 + 深色双跑**（[PARITY] 方法论 §4.2）
4. 失败处理：自动修复重试 **≤2 次**（根 AGENTS.md 闭环验证习惯）；仍失败 → 停止并附诊断（systematic-debugging 路径），不盲目堆尝试

### Stage 5 — 报告

1. **gap analysis**：GLM review 子 agent 审 iOS 侧 diff（K3/GLM 交叉审查原则）→ `docs/06-QA/<date>-ios-follow-<feature>.md`（过程验收，随线清理），🔴/🟡/✅ 分级，🔴 未清零则报告整体判 FAIL
2. **验收报告**（`tmp/ios-follow/<branch>/report.md` + 终端摘要）显式分三栏：
   - ✅ **自动通过**：编译 / shared 单测 / 截图比对 / 无崩溃
   - ⚠️ **待真机终验**：手感、观感、性能、真机任务流（命令绿 ≠ 做完，按「功能 > UI > 性能」优先级由用户终验）
   - 📋 **技术债清单**：未提取的 shared 接口、台账新增差异项、跳过的截图采集等

---

## 3. 断点续跑与失败策略

- `tmp/ios-follow/<branch>/state.json` 记录各阶段 `done/pending/failed`；重跑 `/ios-follow` 从第一个非 done 阶段续跑，不重做已完成阶段
- 全自动模式下仅两种情况中途停止：
  1. **修复重试耗尽**的构建/验收失败 → 附诊断报告停
  2. **契约冲突**（新提取的 spec/台账与现有 iOS 实现或既有 spec 矛盾）→ 列出冲突点停，等用户裁决，不替用户猜

---

## 4. 验收边界（防「命令绿了 ≠ 做完了」）

| 层 | 命令能判的 | 判不了的（留用户） |
|----|-----------|-------------------|
| 编译/单测 | ✅ 双端编译绿、shared jvmTest 过 | — |
| 结构对齐 | ✅ 截图比对（浅色+深色）、布局结构零容差、尺寸 ±2dp | 观感主观项（如美颜强度观感） |
| 稳定性 | ✅ 黑屏体检、崩溃信号检查 | 长时间使用稳定性 |
| 体验 | — | ⚠️ 手感、动效跟手度、性能体感、真机任务流终验 |

---

## 5. 文档同步（[DOC-SYNC] 红线）

本设计落地时同步：

1. `docs/08-UI-SPECS/README.md` — 流程补 `/ios-follow` 入口段（一条命令 = ②③④ 的自动化封装）
2. `docs/08-UI-SPECS/PARITY_MASTER_PLAN.md` — L3 规格层登记 `platform_differences` 台账节；§7 子文档索引加本文
3. 根 `AGENTS.md` §7 — 文档索引加本设计
4. `AI_TOOLS.md` — skill/command 清单加 `ios-follow`（如该文件维护清单）

---

## 6. 风险登记册

| # | 风险 | 等级 | 缓解 |
|---|------|------|------|
| R1 | 全自动模式契约无人把关，spec 提取错则 iOS 跟着错 | 🟡 | Stage 2 由 K3 执行；Stage 5 gap analysis 交叉审查兜底；报告 ⚠️ 栏强制用户终验 |
| R2 | 长管线中途上下文膨胀/模型限流 | 🟡 | 阶段产物文件化 + 断点续跑；子 agent 隔离上下文；GLM 限流降并发回退 |
| R3 | 真机不在线（Android 截图/iOS dev-loop 依赖设备） | 🟡 | 截图缺失时复用已有并标记；iOS 验收支持模拟器降级路径时降级、否则停并报告 |
| R4 | diff 分析误判涉及屏（如跨屏共享组件改动） | 🟡 | follow-plan.md 在报告中回显，用户可对误判项人工补跑单屏 spec 流程 |

---

## 7. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-10 | 初版：brainstorming 三节确认（D1 diff 粒度 / D2 全自动 / D3 全管线不动 Android），方案 A 编排型 Skill + 断点续跑 |
