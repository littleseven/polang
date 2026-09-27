# Chat Parts 消息模型与渲染架构 Spec

> **日期**：2026-09-27
> **状态**：已定稿，待实施
> **决策依据**：ADR-016（Vercel parts 协议 + ChatGPT 渲染 + tokens SSOT；已整合吸收原 ADR-014，编号 014 留空）
> **调研纪要**：本会话联网调研（Vercel AI SDK / OpenAI Responses & ChatGPT / Anthropic / Gemini / LangChain / AG-UI / MCP Apps），要点已沉淀进 ADR-016 §1
> **关联**：`2026-09-26-html-card-two-tier-design.md`（HTML 卡双形态，渲染层原样复用）、`2026-09-25-engineer-task-card-design.md`（任务卡状态机映射进工具状态机）

---

## 1. 背景与目标

当前 chat 消息模型是 13 值 `ChatMessageType` enum + 单类型平铺 payload（`shared/.../domain/chat/ChatMessage.kt`），一次回复的「文本-卡片-文本」交错靠多条消息序列表达。问题：

1. 新内容形态要动消息 schema（加枚举值 + 加 payload 字段），扩展性差
2. 卡片与所属回合、触发它的 tool_call 之间无显式关联（位置靠追加顺序隐式表达）
3. 卡片生命周期（任务卡五态、图表生成中）无统一契约，UI 各自硬编码
4. 正文渲染仍靠 compose-markdown 0.5.4（ADR-016 D2 遗留待办）

目标：按 ADR-016 三方组合选型改造——**数据层 parts 化、流式 chunk 三段式、工具状态机契约化；渲染层 AST→原生受控组件 + Turn 聚合；样式层 tokens SSOT 不动**。

非目标：消息树、JS 桥、文内锚点、全量 HTML 会话（ADR-016 D4）。

## 2. 现状类型 → parts 映射

| 现 `ChatMessageType` | 新 part | 说明 |
|---|---|---|
| `USER_TEXT` / `AGENT_TEXT` | `Text(markdown)` | 正文段；一条回复可有多个 Text part（卡片间交错） |
| `CHART` | `Chart(svg)` | draw_chart 的渲染投影，payload 不变 |
| `HTML_CARD` | `HtmlCard(html, displayMode)` | render_html 的渲染投影；displayMode/测高 metadata 随迁 |
| `TASK_CARD` | `TaskCard(taskId, state)` | 工具调用状态机投影（§5.3） |
| `MEDIA_RESULTS` | `MediaResults(assets)` | data part 性质，不回灌 LLM（现状即如此，沿用） |
| `USER_IMAGE` / `USER_IMAGE_TEXT` | `Image(ref)` + `Text` | user 消息同样 parts 化 |
| `AGENT_IMAGE` / `AGENT_EDIT_RESULT` | `Image(ref)` / `EditResult(...)` | 媒体红线不变：原生 block，不经 LLM 排版 |
| `OPTIMIZE_CANDIDATES` | `OptimizeCandidates(...)` | gacha 候选条 |
| `COMMAND` / `PLAN_PREVIEW` | 消息级 metadata 或专用 part | 实施时定：优先归 metadata，不污染 parts 序列 |

角色概念保留：`role = USER | AGENT`（+ SYSTEM 如需）；一条消息 = `ChatMessage { id, role, parts: List<MessagePart>, metadata }`。

## 3. 数据模型（shared commonMain）

```kotlin
// shared/src/commonMain/.../domain/chat/
@Immutable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: ChatRole,
    val parts: List<MessagePart>,   // 有序即锚点
    val parentId: String? = null,   // D4 消息树扩展点，本期恒 null
    val metadata: MessageMetadata = MessageMetadata(),
    val createdAt: Long,
)

sealed interface MessagePart {
    val partId: String              // 块级 id：流式三段式与UI key的锚

    @Immutable data class Text(override val partId: String, val markdown: String, val state: PartState) : MessagePart
    // M2 起 Chart/HtmlCard 携带工具状态机（占位契约：INPUT_STREAMING/INPUT_AVAILABLE → OUTPUT_AVAILABLE/OUTPUT_ERROR；
    // 持久化卡恒 OUTPUT_AVAILABLE 默认值，M1 存量 partsJson 行解码落默认值，线格式兼容）
    @Immutable data class Chart(override val partId: String, val svg: String, val state: ToolPartState = ToolPartState.OUTPUT_AVAILABLE) : MessagePart
    @Immutable data class HtmlCard(override val partId: String, val html: String, val meta: HtmlCardMeta = HtmlCardMeta(), val state: ToolPartState = ToolPartState.OUTPUT_AVAILABLE) : MessagePart
    @Immutable data class TaskCard(override val partId: String, val toolCallId: String, val state: ToolPartState) : MessagePart
    // Image / EditResult / MediaResults / OptimizeCandidates 同构
}

enum class PartState { STREAMING, DONE }
```

> **实施注记（2026-09-27，M1/M2 落地后校准）**：草图与实现现实的偏差——① `ChatMessage` 未取上述全新形态，实为 legacy 字段 + `parts: List<MessagePart>` **双写共存**（M1 起 Room `partsJson` 双写双读，UI 仍读 legacy 字段，接缝 `data/local/ChatMessageParts.kt`）；`parentId` 未落代码字段（仅 ADR-016 D4 文档层预留）；② `TaskCard` 实际携带完整 `EngineerTaskState` 快照（`task` 字段），非仅 `(toolCallId, state)`。Chart/HtmlCard 的 `meta`/`state` 字段已随 M2 对齐。逐字段事实源：`domain/chat/MessagePart.kt` + `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`。

约束：

- **全部 `@Immutable`**（含 `List` 用不可变拷贝收口），顺车修复当前消息模型 unstable 导致的流式重组放大（2026-09-27 性能梳理 🔴1）
- part 增删只发生在**流式进行中**；DONE 后的 part 不可变（原位更新仅允许 `TaskCard.state` 与 data part 的同 id 覆写——对齐 Vercel `data-*` 原位更新语义）
- commonMain 纯 Kotlin，无平台依赖（ADR-013 纯度守卫覆盖）

## 4. 流式管线（chunk 三段式语义）

对齐的是**事件语义**，传输仍走 Koog agent 循环，不引入 SSE 线协议。

```
TurnStreamEvent
├── TextStart(partId) / TextDelta(partId, delta) / TextEnd(partId)
├── ToolInputStart(toolCallId, toolName) / ToolInputDelta(toolCallId, argsDelta) / ToolInputAvailable(toolCallId, args)
└── ToolOutputAvailable(toolCallId, output) / ToolOutputError(toolCallId, errorText)
```

- Koog 流式回调 → `TurnStreamEvent` → reducer 拼装 parts；`ChatViewModel._streamingMessage` 改为持有「当前 turn 的 parts 快照」，`StreamingPacingController`（50ms 打字机）保留，节拍不变
- 「文本+tool_calls 同帧」沿用 `poLangSingleRunStrategy` 既有修复，reducer 对同帧事件按到达顺序落 part
- 卡片占位契约（对齐 Vercel「未完成时渲染什么」）：`ToolInputStart` 即插入占位 part（骨架/进度文案），`ToolOutputAvailable` 原位填充——任务卡/图表卡/HTML 卡统一此契约，替换现状各自为政的占位逻辑

## 5. 工具状态机契约

### 5.1 状态（对齐 Vercel v5/v6）

```
INPUT_STREAMING → INPUT_AVAILABLE → OUTPUT_AVAILABLE
                                  → OUTPUT_ERROR
                                  → APPROVAL_REQUESTED → APPROVAL_RESPONDED → OUTPUT_AVAILABLE / OUTPUT_DENIED
```

### 5.2 与工程师任务卡五态的映射

| 任务卡现态 | ToolPartState |
|---|---|
| RUNNING | INPUT_AVAILABLE（执行中） |
| AWAITING_CONTINUE / AWAITING_DELIVER | APPROVAL_REQUESTED |
| COMPLETED | OUTPUT_AVAILABLE |
| FAILED | OUTPUT_ERROR |

`EngineerTaskReducer` 状态机与审批语义不变（任务卡 spec §分期不变）；变化的是状态**挂载位置**——从 `_engineerTasks` 旁路 StateFlow 合并进消息 parts，SSE/状态流更新走同 id part 原位覆写（500ms 节流重渲染机制沿用 HTML 卡防抖，two-tier spec §7 不变）。

### 5.3 错误进文档

工具失败以 `OUTPUT_ERROR` part 落库（而非旁路日志），回灌时转为 tool 结果消息让模型可自我修正（对齐 Vercel v5 移除 ToolExecutionError 的理由）。

## 6. 持久化与 LLM 回灌（双层分离）

- **渲染/持久层**：Room `chat_messages` 表加 `partsJson` 列（列名随 Room 属性名，本表列均 camelCase；整包 JSON 序列化，对齐 Vercel「UIMessage JSON 落库」实践）；升级迁移：旧 `type + payload` → parts 文档，迁移失败行降级为 Text part 原文兜底，**不允许丢消息**
- **回灌 LLM**：新增 `ChatMessage.toModelInput()` 显式转换——Text → 文本消息（command/plan_preview 经 Text part 归一后 relabel 为 `agent_text`，原 type 由 legacy 列保留）；TaskCard/Chart/HtmlCard → tool-call + tool-result 对；Image → 英文中性占位文本（`[user sent an image]` / `[assistant generated an image]`，图片本体不进上下文，占位保住回合结构）；EditResult → 回灌其文字说明 description（沿旧路径 `(agent_edit_result, content)` 语义，防多轮编辑上下文断裂）；`MediaResults`/`OptimizeCandidates` 等 data part **默认不进上下文**（对齐 Vercel `convertToModelMessages` 丢弃规则）；替换 `getRecentMessages` 的现行拼接逻辑（`ChatViewModel.kt:3489` 一带）
- displayMode/测高等渲染 metadata 随 part 持久化（two-tier spec「形态不跳变」要求不变）

## 7. 渲染层设计

### 7.1 正文：markdown AST → 原生受控组件

- **候选首选**：`com.mikepenz:multiplatform-markdown-renderer`（KMP、基于 `org.jetbrains.markdown` 官方 AST、支持自定义 node 组件、活跃维护）——「AST→受控组件」正是其架构；KMP 属性为 iOS parity 留路（解析层可入 commonMain，UI 仍各端自绘，符合 ADR-013）
- **Spike 验收硬指标**（承 ADR-016 D2，不过线则退回备选）：① 不完整 markdown 流式增量渲染鲁棒（未闭合代码块/表格不崩不闪）；② 表格；③ 代码高亮；④ 白名单内联 HTML（下划线/高亮/上标级）原生渲染；⑤ 与 `MarkdownSegmenter` 的 TABLE/CODE 分段职责合并或并存方案明确
- **备选**：保留 MarkdownSegmenter 三段式骨架，仅段内渲染器替换（Markdown 段换 AST 渲染器，表格/代码段维持自研组件）
- 正文禁嵌渲染级 HTML 不变（two-tier spec §8：块级 HTML 剥离/降级代码块）

### 7.2 段拍平与列表粒度（性能红线，ADR-016 §4）

- 渲染时 parts **拍平为 LazyColumn 独立 item**：key = `"${messageId}:${partId}"`，**补 `contentType`**（按 part 类型）——顺车修复现状「有 key 无 contentType」的复用错配
- 禁止「一条回复一个巨型 item 内部 Column 排段」（回收粒度/重组隔离/测高防抖全面劣化）
- 卡片渲染组件（`HtmlCard`/`ChartSvgCard`/`EngineerTaskCard`）原样复用；HTML 卡测高 LruCache 的 key 从消息 id 改为 partId

### 7.3 Turn 聚合渲染

- 回合边界：一条 USER 消息开启新 turn；turn = 该 user 消息到下一 user 消息间的 AGENT 消息序列
- 聚合为**纯视觉层**：turn 内相邻 Text part 间取消气泡边界、间距收窄为段落间距；卡片 block 保持全宽独立；turn 间保留回合分隔（头像/时间/间距）
- 列表 item 粒度不变（§7.2），聚合不引入嵌套容器——避免巨型 item
- 视觉规范走 `docs/08-UI-SPECS/screens/chat.yaml` 修订 + tokens，遵守 [PARITY] 三同步

### 7.4 卡片 block 复用清单

| part | 复用 | 改动 |
|---|---|---|
| HtmlCard | `HtmlCard.kt` 全套（双形态/沙箱/防抖/全屏查看器） | 仅数据源从消息 payload 改 part；测高缓存 key 换 partId |
| Chart | `ChartSvgImage` 后台栅格化 | 无 |
| TaskCard | `EngineerTaskCard`（H2 HTML 化路线不变，见 two-tier spec §7） | 状态源改 ToolPartState |
| MediaResults/抽卡 | 现有轮播/候选条 | 无 |

## 8. 性能防护清单（顺车修复，2026-09-27 梳理 🔴 项）

1. 消息/part 模型全 `@Immutable`（§3）
2. LazyColumn 补 contentType（§7.2）
3. `messages.any { TASK_CARD }` 等每帧计算改 `remember(messages)` 派生态；`isScrollInProgress` 读取上移到列表层一次性传入
4. `chatImageIsLive` 的主线程 `File.exists()` 移出组合期（入 ViewModel 或 produceState）
5. 自动滚底 `LaunchedEffect` key 收窄（不含整个 messages 列表）
6. `collectAsState` → `collectAsStateWithLifecycle`

明确不做：WebView 池化（状态污染风险，two-tier spec 已否）、消息分页（独立议题，不在本期）。

## 9. iOS parity

- 本期 Android 先行定稿；iOS 走 ios-follow 管线（[PARITY] 流程），一次跟随覆盖：parts 模型（shared commonMain 直接可得）、AST 渲染（解析层 commonMain，SwiftUI 渲染自绘）、turn 聚合、HTML 卡双形态（现为零实现，gap 最大）
- `chat.yaml` 渲染矩阵在 M4 验收后修订，登记 iOS TODO 项

## 10. 分期实施

| 期 | 内容 | 验收 |
|---|---|---|
| **M1 数据模型** | §3 parts 模型 + Room 迁移 + 回灌转换（§6）；UI 不动，旧渲染经 legacy 字段读取 | 全量历史消息迁移无损（抽查 + 单测）；回灌：文本消息逐条等价，卡片/数据块按 §6 显式转换（command/plan_preview relabel 为 agent_text）（fixture 对比）；编译+ui-driver 冒烟过 |
| **M2 流式管线** | §4 chunk 事件 + reducer + §5 状态机；任务卡状态迁入 parts | 流式交错顺序正确（文本-卡-文本）；任务卡五态渲染等价；OUTPUT_ERROR 落库可回灌 |
| **M3 正文渲染** | §7.1 spike → 替换 compose-markdown | spike 四项硬指标过线；流式长文无闪烁（screenshot-diff + 人工体感）；白名单内联 HTML 渲染 |
| **M4 渲染拍平 + Turn 聚合** | §7.2/§7.3 + §8 性能清单 | 流式期间重组范围实测收窄（Layout Inspector/重组计数）；chat.yaml 修订 + 截图对比基线更新 |
| **M5 iOS 跟随** | ios-follow 管线 | parity gap 报告清零（渲染矩阵口径） |

依赖序：M1 → M2 → M3 ∥ M4（M4 依赖 M1）；M5 在 M4 定稿后启动。每期独立分支、独立可回退（§3.4 工作区隔离）。

## 11. 明确不做

- 消息树（parentId 仅留扩展点）
- JS 桥 / window.openai 式卡内指令通道（零桥红线不动）
- markdown 文内锚点（`{{card:id}}`）
- 全量 HTML 会话、正文嵌渲染级 HTML
- 任务中心列表 HTML 化（two-tier spec §15 已否）
- WebView 池化、消息分页（独立议题）

## 12. 测试与验收

- **JVM 单测**（commonMain 可测）：parts reducer（chunk→文档拼装、同帧顺序）、迁移器（旧类型→parts 全枚举覆盖）、`toModelInput`（丢弃规则、tool 对拆分）、工具状态机迁移表
- **ui-driver**：chat 冒烟（流式回复 + 图卡 + HTML 卡 + 任务卡审批流）
- **screenshot-diff**：M3/M4 视觉回归基线
- **性能基线**：流式重组计数、多 HTML 卡同屏 RSS（ADR-016 §4 内存预算实测项一并执行）、滚帧耗时——数据以时间点快照落 `docs/reviews/`，指标 SSOT 为 `docs/01-PRODUCT/NFR_SPEC.md`

## 13. 风险与回退

| 风险 | 缓解 |
|---|---|
| 流式 partial markdown 把 AST 库打崩 | M3 spike 前置，硬指标不过线退备选方案（保留 Segmenter 骨架） |
| Room 迁移丢消息 | 迁移失败降级 Text 兜底 + 迁移单测全枚举覆盖 |
| Koog 同帧「文本+tool_calls」顺序错乱 | 沿用 poLangSingleRunStrategy；reducer 按到达序落 part + fixture 回归 |
| Turn 聚合视觉回归 | M4 独立分支 + screenshot-diff + chat.yaml 先行修订 |
| 大改造期与 H2（任务卡 HTML 化）冲突 | H2 先行合入（渲染层改造，与 M1/M2 数据层正交）；M4 统一收口卡片 part 化 |
