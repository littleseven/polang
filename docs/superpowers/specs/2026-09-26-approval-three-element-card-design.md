# 审批三要素卡——DESTRUCTIVE 批量删除聚合确认（Spec）

> **日期**: 2026-09-26
> **来源**: Muse 调研 P0 借鉴项 #2（`docs/reviews/2026-09-25-meta-muse-feature-research.md` §3.2）、PRODUCT.md §6.6 任务范式线 P0（「审批三要素卡：无前置，性价比最高」）
> **上游**: `CommandRisk.kt`（写操作确认两层策略 SSOT）、`WriteConfirmationController`（Tier A 确认状态机）、`TrashSessionController`（回收站编排）、`ValueGuard`（珍贵信号语义先例）
> **关键决策**（用户 2026-09-26 逐项确认）: 双链路覆盖（Tier B 批量阈值 3）/ 形态 = 升级确认对话框（不做 chat 内审批卡）
> **状态**: 已定稿，待实施

---

## 1. 问题陈述

Muse 审批三要素卡（订单/收货/支付）的本质：**把「将要发生什么」的要素聚合到一张卡上再要授权**。PoLang 的 DESTRUCTIVE 批量删除现状有两个差距（Muse 调研 §3.1 #2）：

1. **无要素聚合展示**：Tier A（JS `capability.dispatch`）确认框是散点文本——「删除 N 张」一行 + 缩略图行 + AI 来源说明（`chat_write_confirm_delete` / `PendingWriteConfirmation`），用户看不到收藏占比与可恢复性；
2. **对话直调路径确认弱**：Tier B（`ChatToolService.delete_media` @Tool 直调）无任何应用内确认，仅靠系统 MediaStore 授权框——「删掉所有截图」式批量删除走此链路时风险敞口最大。

现有底座完备：`CommandRisk` 三级分级 SSOT、`WriteConfirmationController` 状态机（120s 超时、串行互斥、「脚本已死确认不再生效」孤儿防护）、`TrashSessionController` 回收站编排、`ValueGuard` 珍贵信号语义、`OrganizeRepositoryImpl` 的 `IS_FAVORITE` Q+ 列守卫查询先例。本 spec 只做「确认信息从散点文本升级为结构化卡片 + Tier B 批量补应用内确认」。

## 2. 方案概述

### 2.1 触发与链路（双链路统一一张卡）

- **Tier A（JS 写通路）**：链路结构不变（`CapabilityDispatchHandler` 分级 → 注入的 `requestConfirmation` lambda → `WriteConfirmationController`）；仅确认请求 payload 升级为结构化要素。`CapabilityDispatchHandler` 本体**零改动**（source 标注在 ChatViewModel 的 lambda 实现内完成）。
- **Tier B（LLM 直调）**：`ChatViewModel.onDeleteMedia`（ChatMediaWriteCapability.Delegate，`ChatViewModel.kt:2532`）在执行删除前拦截——**`ids.size ≥ BATCH_CONFIRM_THRESHOLD`（常量 = 3）时先经同一 controller 弹卡**；用户拒绝/超时 → 不执行删除，向 LLM 返回「用户拒绝了本次删除」文本（新 string resource，ReAct 自然消化，不抛异常）；< 3 张维持现状（ReAct 透明 + 系统授权框）。
- **`WriteConfirmationController` 泛化双来源**：`request(..., source: WriteConfirmationSource)`，`enum { JS, TOOL_CALL }`——
  - JS 来源完整保留现有不变式：`scriptRunning` gate（脚本未运行立即拒绝不弹窗）+ `onScriptEnded` 拒绝在途确认；
  - TOOL_CALL 来源不受脚本生命周期约束，gate = chat 链路存活（delegate 绑定语义，与现有 WeakReference 一致）；
  - **互斥下沉 controller**：controller 内部 `Mutex` 串行所有来源的确认（现状 JS 侧互斥在 `CapabilityDispatchHandler.confirmationMutex`，保留不动；controller 级互斥兜住 JS × TOOL_CALL 并发——单一 pending slot 不被覆盖）；
  - 120s 超时对两来源统一（JS 侧现有 `withTimeoutOrNull` 保留；TOOL_CALL 调用点同样包裹）。

### 2.2 卡片内容（三要素 + 保留项）

| 要素 | 内容 | 数据源 | 降级 |
|---|---|---|---|
| ① 数量 | 「将删除 N 张照片/视频」 | ids 长度（现有 `targetCount`） | — |
| ② 珍贵信号 | 「含 X 张收藏 · Y 张拍摄于 5 年前」；无信号整行隐藏 | `MediaStore.IS_FAVORITE`（Q+）+ `DATE_TAKEN` 距今 5 年，语义对齐 `ValueGuard`（收藏/老照片两信号，轻量版） | API < Q 无收藏列：只显老照片信号；查询失败：整行隐藏 |
| ③ 可恢复性 | API 30+：「移入系统回收站，30 天内可恢复」；API <30：「永久删除，不可恢复」（红色警示强化） | `TrashSessionController.isSupported`（`MediaViewModel.isTrashSupported` 同源；ChatViewModel 侧注入等价判定） | — |

保留项：缩略图带（核实目标）、AI 来源说明（JS 来源）/「AI 助手请求」说明（TOOL_CALL 来源）、DESTRUCTIVE 警示色（`CommandRisk` 文档既有要求「UI 用警示色」，现状有 `chat_write_confirm_risk_destructive` 行）。

**REVERSIBLE_WRITE（收藏/选中/记忆写）维持现有简单形态**——同一对话框按 risk 分支渲染，不三要素化。

### 2.3 数据流

确认请求前对目标 ids 做一次批量 MediaStore 轻量查询（projection：`IS_FAVORITE` + `DATE_TAKEN`，Q+ 列守卫参照 `OrganizeRepositoryImpl.kt:173-215` 先例），聚合为 `WriteConfirmElements(favoriteCount, oldPhotoCount, trashSupported)`；`PendingWriteConfirmation` 扩展该字段。查询在 IO dispatcher、失败静默降级（要素缺失不阻塞确认）。五语 i18n（EN/zh-CN/zh-TW/ES/FR）。

## 3. 已定决策

- **D1 双链路，Tier B 批量阈值**：Tier B 仅 `ids.size ≥ 3` 弹应用内卡（用户确认）；单张保持「系统授权框」现状。阈值 = 常量 3，不做配置化。
- **D2 形态 = 升级确认对话框**：外置模态（与 Muse Sentinel「审批在 App UI 不在对话流」同构）；JS await 同步语义天然适配；`WriteConfirmationController` 状态机全复用。明确否决 chat 内审批卡（同步 await + 120s 超时语义不适配对话流卡片）。
- **D3 CapabilityDispatchHandler 零改动**：source 标注与要素聚合都在 ChatViewModel 侧完成，JS 链路 diff 最小化。
- **D4 Tier B 拒绝不抛异常**：拒绝/超时返回描述性文本给 LLM（对齐 `onDeleteMedia` 现有「返回结果描述」契约），ReAct 自行决定后续话术。
- **D5 珍贵信号 = 收藏 + 老照片两信号轻量版**：不复刻 `ValueGuard` 全量（查看过/稀缺人物信号不进卡），保持查询轻量。
- **D6 要素缺失不阻塞**：MediaStore 查询失败/列不可用 → 对应要素行隐藏，确认框仍可用（数量 + 可恢复性常在）。
- **D7 CommandRisk 两层策略注记正式修订**：Tier B 新增「批量 ≥ 阈值需应用内三要素卡」例外，注记决策日期与依据（Muse #2 / PRODUCT.md §6.6）——这是对「两层策略是刻意设计」文档化决策的显式修订，非静默偏离。
- **D8 同一决策点只审批一次**：一次删除请求一张卡；拒绝后 LLM 重试删除 = 新请求新卡（不缓存授权）。

## 4. 测试决策

- **纯函数 JVM 单测**：ids × MediaStore 元数据 → `WriteConfirmElements` 聚合（Q+ / API<Q 降级 / 查询异常 / 空信号 / 全珍贵分支矩阵）。
- **`WriteConfirmationController` 双来源单测扩展**（现有 `WriteConfirmationControllerTest` 增补）：TOOL_CALL 不受 `scriptRunning` gate；`onScriptEnded` 只拒绝 JS 来源在途确认；JS × TOOL_CALL 并发经 controller 互斥串行（单 slot 不覆盖）；TOOL_CALL 120s 超时拒绝。
- **Tier B 拦截单测**：`ids.size` 2/3/4 边界（2 不弹卡直删、3/4 弹卡）；拒绝路径返回拒绝文本且不调 `deleteMediaByIds`（fake repository 验证零调用）。
- **UI 验证**：ui-driver 截图三态（含收藏信号 / API<30 永久删除警示 / 无珍贵信号）+ screenshot-diff 基线。
- **回归**：JS 写通路既有测试全绿（`CapabilityDispatchHandlerTest` 不动应零回归）。

## 5. 文档同步（与实现同一原子提交）

| 文档 | 改动 |
|---|---|
| `shared/.../CommandRisk.kt` | 两层策略注记修订（D7）：Tier B 批量阈值例外 |
| `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` | 路由策略节同步两层策略修订 |
| `androidApp/AGENTS.md` | `WriteConfirmationController` 行更新（双来源 + 三要素） |
| `docs/01-PRODUCT/FEATURES.md` | Agent 交互章节（§3.2 对话模式或 §2.4 附近）补三要素卡交互规范 |
| `PRODUCT.md` §6.6 | 审批三要素卡行 📋 → ✅ |

## 6. 明确不做

- `share_media` 聚合卡（同为 DESTRUCTIVE，但系统分享面板本身即确认环节）；
- REVERSIBLE_WRITE 三要素化（收藏/选中/记忆写维持简单确认）；
- 阈值可配置化 / 设置项（常量 3）；
- chat 内审批卡形态（D2 已否决）；
- `ValueGuard` 全量珍贵信号（稀缺人物/查看过）进卡；
- iOS 实现（Android 定稿后走 /ios-follow 排期，记入 parity 台账）。
