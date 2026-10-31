# 云端 Agent 步骤时间线 UI Spec（Cloud Agent Steps Timeline）

> **状态**：生效中（v1，2026-11-01，PARITY 固化）
> **适用**：云端 Agent 回合的步骤时间线（用户可见进度表达）——主聊天页流式气泡 + 云端调试页 inFlight 区
> **上位约束**：根 `AGENTS.md`（[PARITY]/[I18N] 红线）、`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`；spec `docs/superpowers/specs/2026-11-01-agent-step-timeline-and-admin-trace-design.md`（M1）
> **实现锚点**：`androidApp/.../features/chat/ChatScreen.kt`（`AgentStepsTimeline` composable）、`features/cloudagent/CloudAgentChatScreen.kt`（调试页同款）、`features/cloudagent/AgentStepTimeline.kt`（reducer + `agentStepLabelRes` 工具→文案映射）
> **iOS 跟随（M3）**：iOS 实现以本 spec 为准，**读 spec 不读 Android 源码**；视觉/文案/状态机零容差一致

## 1. 定位

云端回合无 token 级流式，用户等待期间需要「正在做什么」的进度表达。服务端 SSE 的 action/observation 事件被折叠为**步骤时间线**：每个工具调用一步，实时显示 RUNNING → SUCCESS/FAILED 迁移。

**瞬态声明**：步骤时间线是纯内存态，**不落 Room、不进消息历史**。回合开始清空重建；回合结束（正常收尾 / 错误 / 用户中途停止）随流式占位气泡（主聊天页）或 inFlight 区（调试页）一起消失。冷启动恢复会话不重现任何历史步骤。

## 2. 布局

```
Column（步骤列表，verticalArrangement = spacedBy(4.dp)）
 └─ Row（每步一行，verticalAlignment = CenterVertically，horizontalArrangement = spacedBy(6.dp)）
     ├─ 状态指示（12dp 见 §3）
     └─ 步骤标签 Text（13sp，maxLines = 1，overflow = Ellipsis）
```

| 项 | 值 |
|----|----|
| 步骤间垂直间距 | `4.dp` |
| 指示器与标签水平间距 | `6.dp` |
| RUNNING 指示器 | `CircularProgressIndicator`，`Modifier.size(12.dp)`，`strokeWidth = 1.5.dp` |
| 标签字号 | `13.sp`，单行，超长省略 |
| 宿主 | 主聊天页：流式占位气泡内（正文上方/取代 thinking 占位）；调试页：消息列表尾部 inFlight item（无步骤时回退 `cloud_agent_thinking` 占位文案） |

## 3. 状态机与颜色（三态）

| 状态 | 指示器 | 颜色 | 标签 |
|------|--------|------|------|
| RUNNING | 12dp 旋转进度圈（stroke 1.5dp） | `colorScheme.tertiary` | 原标签 |
| SUCCESS | `✓` 文本字形（13sp） | `colorScheme.primary` | 原标签 |
| FAILED | `⚠` 文本字形（13sp） | `colorScheme.error` | 原标签 + FAILED 后缀（见 §4） |

标签正文颜色恒为 `colorScheme.onSurfaceVariant`（三态不变，状态仅靠指示器与后缀区分）。

**状态迁移**：RUNNING → SUCCESS（observation content 非 `error` 前缀）/ RUNNING → FAILED（observation content 以 `error` 前缀开头）。终态不可逆；同 stepId 重复 start 幂等忽略。

## 4. FAILED 后缀规则

FAILED 步的标签 = `"{label} — {failedHint}"`（em dash `—` 分隔），`failedHint` = `R.string.cloud_agent_step_failed_hint` 本地化文案。SUCCESS/RUNNING 不加后缀。

## 5. 工具 → 步骤文案映射（五语全文）

工具名 → 文案资源由 `agentStepLabelRes(tool)` 唯一映射；`render_ui` 与 `render_ui_openui` 共用「生成卡片」文案。

| 工具 | 资源 key | EN | zh-CN | zh-TW | ES | FR |
|------|----------|----|----|----|----|----|
| `get_gallery_summary` | `cloud_agent_step_gallery_summary` | Looking through your gallery… | 正在浏览相册概况… | 正在瀏覽相簿概況… | Revisando tu galería… | Parcours de votre galerie… |
| `search_media` | `cloud_agent_step_search_media` | Searching your photos… | 正在搜索照片… | 正在搜尋照片… | Buscando tus fotos… | Recherche dans vos photos… |
| `refine_media_search` | `cloud_agent_step_refine_search` | Refining the search… | 正在细化搜索… | 正在細化搜尋… | Afinando la búsqueda… | Affinage de la recherche… |
| `render_ui` / `render_ui_openui` | `cloud_agent_step_compose_card` | Composing a card… | 正在生成卡片… | 正在產生卡片… | Creando una tarjeta… | Création d'une carte… |
| `render_html` | `cloud_agent_step_build_page` | Building a page… | 正在生成页面… | 正在產生頁面… | Generando una página… | Génération d'une page… |
| —（FAILED 后缀） | `cloud_agent_step_failed_hint` | didn't work, trying another way… | 未成功，换个方式试试… | 未成功，換個方式試試… | no funcionó, probando otra forma… | échec, essai d'une autre approche… |

[I18N]：新增/修改工具文案必须五语同步（`values` / `values-zh-rCN` / `values-zh-rTW` / `values-es` / `values-fr`），禁止硬编码。

## 6. 空 / 孤儿 / 未知事件行为

| 事件 | 行为 |
|------|------|
| `server_time` 工具 action | 契约级不上时间线（静默跳过，不告警） |
| 未知工具 action（映射表之外） | 跳过该步 + 记一条 warning 日志，不污染进度表达 |
| 孤儿 observation（`actionEventId` 无对应 RUNNING 步，含渲染门控例行 `actionEventId=0`） | reducer 静默忽略（返回 null），仅刷新快照，不产生新步骤 |
| 上回合 pending 重放的 observation | 同上（回合开始已 clear，id 不命中即忽略） |
| 回合内零步骤 | 不渲染时间线；调试页回退 thinking 占位，主聊天页维持 thinking 流式气泡 |

## 7. 生命周期

| 时机 | 行为 |
|------|------|
| 回合开始（send） | `clear()` + 快照置空（上回合残留不跨回合） |
| StepStarted（已知工具） | 追加 RUNNING 步，刷新快照；主聊天页同步退出 isThinking |
| StepFinished | 命中 RUNNING 步 → SUCCESS/FAILED + 回填耗时，刷新快照 |
| 回合结束（Done / Error / 失败兜底 / stop() 取消） | `clear()` + 快照置空；步骤随流式气泡 / inFlight 区一起消失 |

## 8. 验收清单（双端截图比对用）

- [ ] ≥2 步依次出现，首步 RUNNING（旋转圈）→ 前步转 ✓、次步接管 RUNNING
- [ ] 发卡类提问可见「正在生成卡片…」步
- [ ] 工具失败步显示 ⚠ + 「{label} — {failedHint}」
- [ ] 回合结束（含中途停止）步骤整块消失，不留残影
- [ ] zh-TW / ES 抽查步骤文案与 §5 表逐字一致
