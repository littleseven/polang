# Chat 卡片目录（数据 · UI 样式 · 渲染技术 SSOT）

> **定位**：聊天消息流中一切卡片/气泡的登记目录——每张卡片给出**协议实例**（入口 tool_call JSON + Room 落库 JSON）、**UI 样式**（Ardot 设计稿帧 + refs 图）、**渲染技术**（渲染组件与机制）。
> **日期**：2026-09-27 建立（覆盖 app v1.0.39 卡片现状 + 已定稿待实施项）；同日登记 ADR-016 M1-M4 parts 改造落地态（**M4 真机验收待补**，见 §5.2/§7）
> **上游**：ADR-016（chat 消息内容模型与渲染架构，宪法级——已整合原 ADR-014）、`JS_ENGINE_TECH_SPEC.md` §7/§7.1（图表/HTML 卡实现 SSOT）、`2026-09-26-html-card-two-tier-design.md`（双形态 + 任务卡 HTML 化）
> **维护规则**：① 新增卡片 = 同一原子提交内登记本目录（协议/UI/渲染三列齐）；② 卡片视觉改版 = 先改 Ardot 画布 → 快照 refs → 回写本目录 UI 列与状态标注；③ 本目录只做登记与索引，实现细节以各上游文档为准，禁止反向漂移；④ 协议演进（parts/回灌）须过 §0.3 OpenAI 兼容对照六条硬约束。

---

## 0. 协议总纲（读例前必读）

**两级协议形态**：

1. **入口协议**（LLM → App）：OpenAI 兼容 tool_calls，`name` + `arguments`（JSON 字符串，定义在 `shared/.../inference/remote/tool/ChatToolService.kt` `@Tool`）。
2. **落库协议**（App → Room → 渲染）：`chat_messages` 表一行（`androidApp/.../data/local/ChatMessageEntity.kt`），卡片差异化负载全部收在 `content` + `metadata`（JSON 字符串）两列。

> 📌 **读例约定**：下文所有 `metadata` / `tool_call.arguments` / `content` 内嵌 JSON 一律**展开为对象/数组示意**（带注释）；实际 Room 存储为其序列化字符串。

```jsonc
// chat_messages 表结构（Room v26 实体，逐列）
{
  "id": "uuid 或 taskId", // 任务卡特例：id = taskId，REPLACE upsert
  "sessionId": "default",
  "type": "…", // 新分类法 8 值之一（3 分类，见下）
  "role": "agent", // 消息角色 "user"/"agent"（v26 新列，取代 user_/agent_ type 前缀）
  "content": "…", // 按 type 语义不同，见各卡
  "timestamp": 1790000000000,
  "modelUsed": "deepseek-chat", // 可空
  "metadata": { "…": "…" }, // 卡片专属负载包（实际存储为 JSON 字符串）
  "partsJson": [ "…" ] // parts 数组 JSON（读面权威源，实际存储为 JSON 字符串）
}
```

`type` 全集（2026-09-28 分类法重构，spec `docs/superpowers/specs/2026-09-28-chat-type-taxonomy-design.md`）——**3 分类 8 值**，命名规则 `^{category}_{kind}$`（`tool_`/`data_` 为保留前缀，content 免前缀，协议值禁 UI 容器词）：

- **content**：`text` · `image`
- **tool**：`tool_chart` · `tool_html` · `tool_task` · `tool_image_edit`
- **data**：`data_media_results` · `data_optimize_candidates`

多 part 消息的 type 取首 part 值；role 由独立列承载。legacy 13 值（`user_text`/`agent_text`/`user_image`/`user_image_text`/`agent_image`/`command`/`plan_preview`/`media_results`/`chart`/`html_card`/`agent_edit_result`/`optimize_candidates`/`task_card`）仅存于迁移源，经 `LegacyChatTypeMigration` 一次性转正（映射见 §0.2 末表）。

**渲染路由（M4 已切 parts）**：`ChatViewModel.toUiModel()` 全量 `decodePartsOrLegacy` 填充 `ChatMessage.parts`（内存快路径 parts 同源双写）→ shared `domain/chat/ChatListFlattener` 拍平为 item 序列（`ChatScreen.kt` LazyColumn 按 `ChatListItem.contentType` 分发；key/三分流规则见 §1.3）。legacy「按 type 取 content/metadata → if/else 链」为 M1-M3 形态，仅余整消息 legacy 渲染类型沿用（清单见 §1.3）。

### 0.1 协议一致性现状与 parts 重构（2026-09-27）

**legacy 13-type 协议本身不统一**——这正是 ADR-016 parts 重构的动机，盘点如下（其中「僵尸类型」「type 值域混杂」两项已随 2026-09-28 分类法重构根治；下文各卡「落库协议」示例均为**新分类法形态**——type 为 8 值之一 + 独立 `role` 列，legacy 13 值仅存于迁移源）：

| 不统一点 | 现状 |
|---|---|
| `content` 列语义 | 按 type 共 6 种：markdown 文本 / SVG 串（`tool_chart`）/ HTML 串（`tool_html`）/ JSON 数组（`data_media_results`）/ sourceText 前 50 字（`tool_task`）/ 空串占位（`data_optimize_candidates`） |
| `metadata` 挂载风格 | 3 种：① 键包裹单对象（`html_card`/`engineer_task`/`claude_agent_state`/optimize 全量）② 字段直挂顶层（性能 6 字段）③ 平铺小对象（`imageUri`+`saved`、`query`+`totalCount`+`isRefinement`） |
| `id` / `timestamp` 语义特例 | tool_task：id=taskId（REPLACE upsert）、timestamp=startedAtMs（锚定提交位置） |
| serde 位置 | org.json 扩展在 androidApp shim（`ChatModelCommonMainShim.kt`），非 commonMain 纯 Kotlin |
| 僵尸类型 | ✅ 已根治（2026-09-28 分类法重构）：~~`command` / `plan_preview` 主聊天流无专属渲染分支（真正消费者在平行浮动面板体系，§6）~~——两值删除并入 `text`，存量行经 MIGRATION_25_26 转正 |
| type 值域混杂 | ✅ 已根治（2026-09-28 分类法重构）：~~13 值扁平混杂，content/tool/data 不分，role 靠 user_/agent_ 前缀~~——3 分类 8 值 + `role` 升格独立列（见 §0 type 全集） |

**parts 重构（✅ M1-M4 已落地：M1-M3 已合 main `46a6e91fb`；M4 在 `feat/chat-parts-m4` 待合）**：按 ADR-016 对齐 OpenAI Responses / Vercel parts 协议——Room 增 `partsJson` 列（v24→v25 迁移全量回填），一条消息 = 有序 parts 数组，kotlinx JSON 鉴别字段 `type`（2026-09-28 分类法重构后 = 新 3 分类 8 值：6 个 serialName 改值 + `PartCategory` 分类载体 getter，不进线格式），`ignoreUnknownKeys` 前向兼容 + `encodeDefaults=false` 紧凑落库；legacy 三列经转换器全枚举迁移，**行级 Text 兜底不允许丢消息**。转换器现为 `MessagePartsConverter`（原 `LegacyMessagePartsConverter` 已改名——仅认新 8 值 + `role` 入参）；legacy 13→8 映射知识单点收编 `LegacyChatTypeMigration`（仅供 Room MIGRATION_25_26 与备份恢复，运行时不双吃）。M2 流式三件套（`domain/chat/streaming/`：TurnStreamEvent / ChatStreamTurnAdapter / TurnPartsReducer + `TaskCardOverlay`）已落地：占位契约（`ToolInputStart` 即插占位 part，`draw_chart`/`render_html` 类型化占位）→ 产物原位填充；错误双轨（持久化错误轨 = TaskCard，Chart/HtmlCard/脚本错误瞬态不落库，详见 §4.3）。M3 正文换 `AgentMarkdown`（§3.2）；M4 渲染源切 parts + Turn 聚合（§1.3）。落库线格式实例：

```jsonc
// partsJson 列（M1/M2 已落地；encodeDefaults=false → state="DONE"/saved=false 等缺省字段不写出）
[
  { "type": "text", "partId": "p0", "markdown": "已定位崩溃根因：…" },
  {
    "type": "tool_task",
    "partId": "p1",
    "toolCallId": "task-01JD2Z…",
    "state": "APPROVAL_REQUESTED",        // ToolPartState 七态（Vercel 对齐）；M2 流式已驱动（占位→原位填充/OUTPUT_ERROR）
    "task": { "…": "EngineerTaskState 全量，同 §4.3" }
  },
  {
    "type": "tool_html",
    "partId": "p2",
    "html": "<div style='width:100%…'>…</div>",
    "meta": { "display": "fullpage", "displayMode": "FULLPAGE", "summary": "NVIDIA 2026 Q3 业绩概览" }
    // M2：Chart/HtmlCard 另有 state: ToolPartState（缺省 OUTPUT_AVAILABLE 不写出），见 §0.2
  }
]
```

M1 期 partsJson 为 legacy 三列的纯函数双写（写接缝 `data/local/ChatMessageParts.kt`）；M2 起流式以 parts 为权威装配（占位/瞬态不落 Room），legacy 三列仍为持久化写面；**M4 起渲染源已切 parts**（`ChatListFlattener` 拍平，UI 不再读 legacy 字段选卡——见 §1.3）。各卡「落库协议」示例为新分类法形态（type 8 值 + `role` 列；标量列持久化写面不变），parts 形态见 §0.2 与各卡「parts 协议」小节。

### 0.2 目标协议（parts 形态）逐类型示例

字段名与缺省省略行为以 `MessagePartsCodec`（kotlinx JSON，`encodeDefaults=false`）为准，round-trip 有单测锁定（`MessagePartsCodecTest`）。**缺省值不写出**：`state="DONE"`、`saved=false`、空集合、`null` 默认字段一律省略，解码回填。**M2 增量**：`Chart`/`HtmlCard` 新增 `state: ToolPartState`（缺省 `OUTPUT_AVAILABLE`，持久化 JSON 不写出；流式占位 `INPUT_STREAMING` 瞬态不落库）——M1 存量 partsJson 行解码落默认值，线格式兼容。**M4 partId 分轨定稿**（`MessagePart.partId` KDoc / spec §3）：瞬态轨（流式 turn，reducer 合成）`txt-N`/`call-N` 与持久轨（Room partsJson，迁移器合成）`p0`/`p1`… **不交叉**——流式消息不落 Room、落库消息不经 reducer，各生命周期内 LazyColumn key（`messageId:partId`）恒定；流式→落库边界 messageId 变更属基线行为（一 turn 拆多行的持久模型使然）。

```jsonc
// text ← user_text / agent_text / command / plan_preview（一条回复可多段交错）
{
  "type": "text",
  "partId": "p0",
  "markdown": "已定位崩溃根因：…"          // state 仅 STREAMING 时写出（DONE 为缺省）
}

// tool_chart ← chart（M2 起 model 另有 state: ToolPartState，缺省 OUTPUT_AVAILABLE 不写出）
{
  "type": "tool_chart",
  "partId": "p1",
  "svg": "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 360 240'>…柱状图元素…</svg>"
}

// tool_html ← html_card（meta 四字段与 legacy metadata.html_card 键一致，随迁）
{
  "type": "tool_html",
  "partId": "p2",
  "html": "<div style='width:100%…'>…</div>",
  "meta": {
    "display": "fullpage",
    "displayMode": "FULLPAGE",
    "measuredHeightPx": null,
    "summary": "NVIDIA 2026 Q3 业绩概览"
  }
}

// tool_task ← task_card（state = ToolPartState 七态投影；task 内字段与 legacy engineer_task 键同名）
{
  "type": "tool_task",
  "partId": "p3",
  "toolCallId": "task-01JD2Z…",           // 持久化行恒 = taskId；M2 流式瞬态用 call-N 合成 id（不落库，见 §4.3）
  "state": "APPROVAL_REQUESTED",
  "task": {
    "taskId": "task-01JD2Z…",
    "sourceText": "修复相册扫描时的崩溃",
    "sid": "claude-9a…",
    "status": "AWAITING_CONTINUE",
    "stage": "Edit · TagScanOrchestrator.kt",
    "recentStages": ["读取文件", "分析堆栈", "编辑代码"],
    "turns": 50,
    "costCents": 128,
    "startedAtMs": 1790000000000,
    "updatedAtMs": 1790000217000,
    "fileChangeCount": 3,
    "truncatedReason": "达到最大轮次（50）",
    "errorSummary": null,
    "resultSummary": "已完成根因定位与修复，编译验证中断",
    "resolution": null,
    "deliverBranch": null
  }
}

// data_media_results ← media_results（data part：默认不回灌 LLM）
{
  "type": "data_media_results",
  "partId": "p4",
  "results": {
    "query": "去年夏天的海边",
    "assets": [
      {
        "id": 1024,
        "uri": "content://media/external/images/media/1024",
        "type": "PHOTO",
        "captureDate": 1757000000000,
        "fileName": "IMG_20260805.jpg",
        "faceFocusY": 0.42
      }
    ],
    "totalCount": 87,
    "isRefinement": false,
    "feedbackState": { "1024": "LIKE" }   // 空 Map 为缺省不写出
  }
}

// image ← user_image / agent_image / user_image_text 的图（媒体红线：原生 block，不经 LLM 排版）
{
  "type": "image",
  "partId": "p5",
  "ref": "content://media/external/images/media/1024"   // user 图：saved=false 缺省不写出
}

{
  "type": "image",
  "partId": "p5",
  "ref": "file:///data/…/result.webp",
  "saved": true                                          // agent 产物图已保存到相册
}

// tool_image_edit ← agent_edit_result
{
  "type": "tool_image_edit",
  "partId": "p6",
  "ref": "file:///data/…/edit.webp",     // null（legacy metadata 缺失）时整个键不写出，UI 落 legacy 兜底
  "description": "已完成背景替换：海边 · 保留人物主体",
  "suggestions": ["再亮一点", "换成黄昏"]   // 空数组为缺省不写出
}

// data_optimize_candidates ← optimize_candidates（data part：默认不回灌 LLM）
{
  "type": "data_optimize_candidates",
  "partId": "p7",
  "group": { "…": "OptimizeCandidateGroup 全量，字段与 legacy metadata 键同名，见 §4.5" }
}
```

**legacy type → 新 type（+ role）迁移映射**（`LegacyChatTypeMigration` 实况，2026-09-28 分类法重构；仅供 Room MIGRATION_25_26 与备份恢复，运行时不双吃。role 派生规则：legacy `user_` 前缀 → `user`，其余 → `agent`）：

| legacy type | 新 type（+ role） | parts 转换产物 |
|---|---|---|
| `user_text` | `text`（user） | `Text(markdown=content)` |
| `agent_text` | `text`（agent） | `Text(markdown=content)` |
| `command` / `plan_preview` | `text`（agent） | `Text(markdown=content)`（僵尸类型就此转正；claude_agent_state 步骤流不进 parts，随后续里程碑定表达） |
| `user_image` | `image`（user） | `Image(ref=content)`（content 列即图片路径） |
| `user_image_text` | `image`（user） | 有 `metadata.imageUri` → `Image(imageUri)` + `Text`（图上文下，顺序即展示顺序）；无则 `Image(ref=content)` 单 part |
| `agent_image` | `image`（agent） | `Image(ref, saved)` |
| `chart` | `tool_chart` | `Chart(svg=content)` |
| `html_card` | `tool_html` | `HtmlCard(html=content, meta)` |
| `task_card` | `tool_task` | `TaskCard`（taskId 兼任 toolCallId） |
| `agent_edit_result` | `tool_image_edit` | `EditResult(ref, description=content, suggestions, saved)` |
| `media_results` | `data_media_results` | `MediaResults`（content 数组 + metadata 三字段合一） |
| `optimize_candidates` | `data_optimize_candidates` | `OptimizeCandidates` |
| 任意行转换失败/未知类型 | `text`（agent） | 行级兜底 `Text(content)`，不允许丢消息 |

### 0.3 OpenAI 协议兼容对照（目标协议硬约束）

ADR-016 对齐的「主流协议」中，OpenAI 是传输层实际采用的家族（远程推理 = OpenAI 兼容 Chat Completions，经 Koog；Responses API 为概念对齐参照）。parts 模型**命名取 Vercel（partId/markdown），语义须与 OpenAI 无损互转**——本节是重构与新增 part 类型的兼容验收清单。

**① 概念对照**：

| PoLang part | OpenAI Responses item | Chat Completions 概念 |
|---|---|---|
| `Text(markdown)` | `message`（content `output_text`/`input_text`） | `assistant`/`user` 消息 content |
| `Chart` / `HtmlCard` / `TaskCard` | `function_call` + `function_call_output` **对**（靠 `call_id` 关联） | `assistant.tool_calls[]` + `tool` 角色消息**对**（靠 `tool_call_id` 关联） |
| `MediaResults` / `OptimizeCandidates` | 无对应（UI data，回灌剥离——对齐 Vercel `convertToModelMessages` 丢弃规则） | 不进上下文 |
| `Image`(user 图) | 不映射 `input_image`——**媒体红线：本地 uri 不外发**（ADR-008） | 同左 |
| `partId` | item `id`（本地 UI key） | —（消息级即可） |
| `toolCallId` | **`call_id`**（须可原样回显） | **`tool_call_id` / tool_calls[].id** |

**② 生命周期对照**（`ToolPartState` ↔ OpenAI item `status` + 流式事件；三段式 chunk = start/delta/end 的事件语义对齐，非 SSE 线协议）：

| ToolPartState | OpenAI 语义 | 流式事件锚 |
|---|---|---|
| `INPUT_STREAMING` | arguments 增量中（`status:"in_progress"`） | `response.function_call_arguments.delta` |
| `INPUT_AVAILABLE` | arguments 完整、调用就绪 | `response.function_call_arguments.done` |
| `OUTPUT_AVAILABLE` | `function_call_output` 已回（`status:"completed"`） | `response.output_item.done` |
| `OUTPUT_ERROR` | `status:"failed"` | 同上（错误态） |
| `APPROVAL_REQUESTED` / `APPROVAL_RESPONDED` / `OUTPUT_DENIED` | OpenAI 核心无对应——Vercel v6 / Agents SDK human-in-the-loop 扩展位 | — |

（块级 start ↔ `response.output_item.added`；工程师任务卡五态经 `toToolPartState()` 投影到本表。）

**③ 回灌线格式示例**（`ChatMessage.toModelInput()` 的目标形态，spec §6；Chat Completions 为实际线协议）：

```jsonc
// parts 文档（§0.2 示例）→ 回灌 messages 数组：Text 进 content，工具 part 拆 tool-call/tool-result 对
[
  { "role": "user", "content": "修复相册扫描时的崩溃" },
  {
    "role": "assistant",
    "content": "已定位崩溃根因：…",                       // Text part
    "tool_calls": [
      {
        "id": "call-h9…",                                // = part.toolCallId，须与下行 tool_call_id 一致
        "type": "function",
        "function": {
          "name": "render_html",                         // Chart→draw_chart / HtmlCard→render_html
          "arguments": "{\"html\":\"…\",\"summary\":\"NVIDIA 2026 Q3 业绩概览\",\"display\":\"fullpage\"}"
        }                                                // arguments 恒为 JSON 字符串（OpenAI 线格式）
      }
    ]
  },
  { "role": "tool", "tool_call_id": "call-h9…", "content": "{\"summary\":\"NVIDIA 2026 Q3 业绩概览\"}" }
]
```

**④ 兼容性硬约束**（新增/改 part 类型必须逐条过）：

1. **配对完整**：回灌时每个工具 part 必须产出 tool-call + tool-result 两条且 `call_id` 两端一致——OpenAI 严格校验（孤儿 `tool` 消息或缺响应均 400）；
2. **`arguments` 恒为 JSON 字符串**（非嵌套对象）——与 OpenAI 线格式一致，part 内嵌对象字段（`task`/`meta`）是 UI 投影，序列化进 arguments 时须 stringify；
3. **入参原文保真**：faithful 回灌需要 part 保留（或可重建）工具原始入参——**M1 已知缺口**（`Chart` 只有 svg、`HtmlCard` 只有 html，原始 arguments 不可逆；`TaskCard.sourceText` 部分覆盖）——M2 流式改造时补 `input` 字段或旁路保留；
4. **UI 专有 part 不进上下文**（`MediaResults`/`OptimizeCandidates` 回灌剥离）；
5. **user 图不外发**（不映射 `input_image`，媒体红线）；
6. **枚举可映射**：新 part 状态枚举须能落到 ② 的 OpenAI/Vercel 语义行，不造第三套生命周期。

---

## 1. 渲染架构总览

### 1.1 渲染技术四类

| 技术 | 机制 | 卡片 |
|---|---|---|
| **原生 Compose** | 直接组合，Coil 加载图 | 用户气泡、文本流、搜索结果卡、gacha 条、图片/编辑结果卡、确认弹窗 |
| **原生 SVG 栅格化** | androidsvg 渲染 ×2.5 位图 | 图表卡 |
| **WebView · LLM HTML** | 零 JS 桥沙箱 + 清洗器 + 动态测高 | HTML 卡（inline/fullpage 双形态） |
| **WebView · L1 端侧模板** | App 内模板 + 状态 JSON 组装，非 LLM 产物 | 工程师任务卡 |

### 1.2 横切机制（多卡片共用）

- **零 JS 桥沙箱**（ADR-016 D3）：WebView 禁文件/DOM Storage、远程 script 剔除、`HtmlCardSanitizer` 清洗（128KB 上限）；`<a>` 外链一律 `HtmlLinkPreviewOverlay` 落地页。
- **测高防抖三件套**（HTML 卡族）：测高 LruCache（**M4 起 key = 拍平 item key `"messageId:partId"`**——ChatScreen 调用点 `cacheKey = item.key`；任务卡保留形态域 key `taskcard:<taskId>:exp|col`，状态重生成不失效缓存）+ 滑动冻结滚停应用 + <2px 抖动忽略；布局前虚高拦截。
- **双形态分流**（`HtmlCardDisplay` 纯函数）：display 声明 × 测高 → INLINE/FULLPAGE，终判随消息 metadata 持久化。
- **样式权威**：design-tokens.json → codegen 双端镜像（ADR-016 D5）；HTML/L1 模板 CSS 变量走 MaterialTheme tokens，Light/Dark 随主题。
- **媒体红线**（ADR-008）：相册图/编辑结果走原生消息（MEDIA_RESULTS/AGENT_IMAGE），不经 LLM 排版、不上传远程。

### 1.3 ADR-016 parts 模型（✅ M1-M4 已落地：M1-M3 合 main `46a6e91fb`；M4 在 `feat/chat-parts-m4` 待合，真机验收待补）

一条回复 = 有序 parts 数组（`MessagePart` sealed：Text/Chart/HtmlCard/TaskCard/MediaResults/Image/EditResult/OptimizeCandidates），本目录卡片即 parts 的独立 block 清单；映射表见 `2026-09-27-chat-parts-rendering-design.md` §2，type 值域（3 分类 8 值 + role 列）见 `2026-09-28-chat-type-taxonomy-design.md`。M1 = Room `partsJson` 双写 + v24→v25 迁移回填 + 回灌转换；M2 = 流式三件套（类型化占位/原位填充/OUTPUT_ERROR 双轨）+ 任务卡 overlay 迁入 parts；M3 = 正文 `AgentMarkdown`（§3.2）。

**M4 渲染源切 parts + Turn 聚合**（spec §7.2/§7.3/§8；验收 `docs/06-QA/M4_CHAT_FLATTEN_ACCEPTANCE.md`）：

- **拍平粒度**：shared `domain/chat/ChatListFlattener` 纯函数——每个 MessagePart = LazyColumn 独立 item，**key = `"${messageId}:${partId}"`** + **contentType 复用桶**（`ChatListItem.TYPE_*`：user_message / legacy_message / agent_text / chart / html_card / task_card / media_results / optimize_candidates / tool_placeholder / tool_error / tool_status）。禁止「一条回复一个巨型 item 内部 Column 排段」（性能红线 ADR-016 §4）。
- **拍平三分流/直通清单**：USER 消息整颗单 item（§5 图文同气泡不拆）；claude 气泡 / AGENT_IMAGE / AGENT_EDIT_RESULT 整消息 legacy 渲染（part=null，渲染器读 legacy 字段；原 COMMAND / PLAN_PREVIEW 已并入 `text` 随分类法重构转正，2026-09-28）；流式卡 part 三分流——已填充（OUTPUT_AVAILABLE）的 Chart/HtmlCard **跳过**（产物已落库为独立行，双显禁止）、未完成（INPUT_*）渲染占位 item、失败（OUTPUT_ERROR 瞬态轨）渲染失败 item；非卡片工具进行中合成 `ToolStatusChip` 状态 item（key `messageId:tool_status`）。
- **Turn 聚合（纯视觉层）**：USER 消息开启新 turn（首条 AGENT 独立成 turn）；逐 item 顶距阶梯（无 spacedBy/嵌套容器）——turn 间回合分隔 `Spacing.lg`（16）· 同 turn 相邻 agent 文本 `Spacing.xs`（4，`mergeWithPrevious` 标记）· 卡片与相邻 part `Spacing.sm`（8）；token 阶梯取最近档，design-tokens.json 零变更。本屏无头像/无时间戳（2026-08-18 去头像定稿），回合分隔以间距承担。视觉规范 SSOT：`docs/08-UI-SPECS/screens/chat.yaml` §3.1/§3.2（渲染矩阵口径一致）。
- **§8 性能收口**：`compose-stability.conf` 稳定性白名单（domain.chat 整包，shared 无 compose-runtime 依赖）、滚底/回锚改 flatItems item 粒度 + effect key 收窄、`rememberChatImageIsLive`（File.exists 移出组合期）、`collectAsStateWithLifecycle` 全量、isScrollInProgress 上移列表层。
- **⏳ 真机验收待补**（M4 验收记录 §3：设备离线）：装包冒烟（流式/`/html`/`/chart`/`/task`/长列表滚动）、重组范围实测（Layout Inspector）、Turn 聚合间距阶梯截图基线更新。

---

## 2. 卡片总表

| 卡片 | type（Room v26 列值） | 渲染组件 | 渲染技术 | 设计稿（Ardot） | 状态 |
|---|---|---|---|---|---|
| 用户气泡（文/图/图文） | `text` / `image`（+ role=user） | `ChatMessageItem` isUser 分支 | 原生 | `chat_user_bubble` 438:332 | ✅ |
| Agent 文本流（markdown） | `text`（+ role=agent） | `ChatAgentTextPart`（M4 拍平 item）→ `AgentMarkdown`（M3）+ AgentTable/CodeBlock | 原生（AST 管线） | chat-conversation 帧 | ✅ |
| 图表卡 | `tool_chart` | `ChartSvgCard` / `ChartSvgImage` | SVG 栅格化 | —（生成物无固定帧） | ✅ |
| HTML 卡（双形态） | `tool_html` | `HtmlCard` / `HtmlFullpageViewer` | WebView | `htmlcard/inline` 438:3 · `preview` 438:60 · `fullpage` 438:98 | ✅ |
| 工程师任务卡 | `tool_task` | `EngineerTaskCard` + 原生动作条 | WebView L1 模板 | `taskcard/*` 438:181/208/264/444:31 | ✅（两分区改版待实现迁移） |
| 搜索结果卡 | `data_media_results` | `MediaResultsCarousel` | 原生 | `chat_photo_card` 387:143 | ✅ |
| 抽卡候选条 | `data_optimize_candidates` | `GachaCandidateStrip` | 原生 | —（设计稿已随交付清理） | ✅ |
| Agent 图片结果卡 | `image`（+ role=agent） | `ChatMessageItem` isImage 分支 | 原生 | —（跟随会话帧） | ✅ |
| 编辑结果卡 | `tool_image_edit` | isEditResult 分支 | 原生 | — | ✅ |
| Claude 步骤附加区 | `text`（+ role=agent）+ metadata `claude_agent_state` | `AgentMessageExtras` / `ClaudeAgentSteps` | 原生 | — | ✅ |
| 流式卡片占位/工具状态/失败 item | （流式瞬态，不落 Room） | 占位骨架 / `ToolStatusChip` / 失败态（i18n `chat_card_generate_failed`） | 原生 | — | ✅（M4 拍平三分流，见 §1.3） |
| 日期分隔 | （列表装饰，非消息） | `ChatDateChip` | 原生 | `chat_date_chip` 438:335 | ✅ |
| 游客引导卡 | （sheet，非消息） | `ChatRegistrationSheet` | 原生 | `chat_nudge_card` 438:357 | ✅ |
| 清理确认/完成卡 | （流程 UI，非消息） | 写确认 AlertDialog 管线 | 原生 | `chat_cleanup_card` 438:397 · `done` 438:409 | ✅（三要素升级待实施） |
| 审批三要素卡 | — | — | 原生（规划） | 待画 | 📋 spec 定稿待实施 |
| 任务中心列表项 | （页面卡） | `UserTaskCard` / EngineerTask 紧凑项 | 原生 | `chat-task_center` 帧 | ✅ |
| 浮动 AI 面板体系 | 独立 sealed `AgentMessage` | `ChatBubble` 系（AiChatScreen） | 原生 | — | ✅（平行体系，§6） |

---

## 3. 基础消息形态

### 3.1 用户气泡

**协议**（落库；无 tool_call 入口——用户输入直落）：

```jsonc
// 纯文本（← user_text）
{
  "id": "8f3a…",
  "type": "text",
  "role": "user",
  "content": "修复相册扫描时的崩溃",
  "metadata": null
}

// 图文（← user_image / user_image_text，metadata 携带图片 uri）
{
  "id": "8f3b…",
  "type": "image",
  "role": "user",
  "content": "把这张图的背景换成海边",
  "metadata": {
    "imageUri": "content://media/external/images/media/1024"
  }
}
```

**UI**：绿底深字（`chatBubble/userBubbleBg` #95EC69 双模恒值 / `userBubbleOn` #181818），hug 尺寸 pad 18h/14v，右对齐。
![chat_user_bubble](../assets/chat-cards/chat_user_bubble-dark.png?v=20260927-4)
**渲染组件**：`ChatMessageItem`（ChatScreen.kt）isUser 分支，原生 Compose；图片走 Coil。`ChatBubbleTokens`（`core/designsystem/DesignTokens.kt`）。
**parts 协议（✅ M1 已落地 / M4 渲染源已切）**：§0.2 `text` / `image`（role=user 分支）——有 `metadata.imageUri` → `[Image(p0, ref=metadata.imageUri), Text(p1, content)]` 图上文下（顺序即展示顺序）；无 imageUri（← `user_image`）→ `Image(p0, ref=content 列)` 单 part。回灌：Image → 占位 `[user sent an image]`、Text → 原文，两腿相邻保回合结构（逐字示例见 §4.6）。**M4 拍平**：USER 消息**整颗单 item**（contentType `user_message`，part=null 整消息渲染）——图文两 part 不拆 item，§5「上图下文同气泡」视觉单元保持。

### 3.2 Agent 文本流（markdown）

**协议**（落库）：

```jsonc
// 纯文本回复（性能指标直挂 metadata 顶层；← agent_text）
{
  "id": "a1…",
  "type": "text",
  "role": "agent",
  "content": "已定位崩溃根因：\n\n**TagScanOrchestrator** 空指针…",
  "modelUsed": "deepseek-chat",
  "metadata": {
    "prompt_len": 3210,
    "decode_len": 890,
    "prefill_time_ms": 412,
    "decode_time_ms": 1830,
    "prefill_speed": 7.8,
    "decode_speed": 48.6
  }
}

// AI 工程师回合（metadata 换挂 claude_agent_state；性能字段缺省 → 不渲染性能行）
{
  "id": "a2…",
  "type": "text",
  "role": "agent",
  "content": "",
  "metadata": {
    "claude_agent_state": {
      "text": "正在修复…",
      "steps": [
        { "tool": "Edit",  "status": "RUNNING", "detail": "TagScanOrchestrator.kt" },
        { "tool": "Bash",  "status": "SUCCESS", "detail": "compileDebugKotlin" },
        { "tool": "Read",  "status": "FAILED",  "detail": "build.gradle.kts" }
      ],
      "hasFileChange": true,
      "truncatedReason": null
    }
  }
}
```

**UI**：通栏无气泡皮；流式态 `TypingIndicator`（thinking）/`BlinkCursor`；表格点击进 `TablePreviewOverlay`；代码块 >12 行折叠 + 复制。
**渲染组件**：`ChatAgentTextPart`（M4 拍平正文 item，ChatScreen.kt）→ `AgentMarkdown`（mikepenz multiplatform-markdown-renderer 0.41.0，ADR-016 M3 已替换 jeziellago compose-markdown；`features/common/chat/AgentMarkdown.kt`：retainState+immediate 流式参数（`IMMEDIATE_PARSE_MAX_CHARS=1500` 按内容长度门控）、白名单内联 HTML annotator（u/mark/sup/sub）、高亮+折叠 codeFence）+ `AgentTable`（纯 Compose 网格，TABLE 段自研保留）+ `CodeBlock`（CODE 段自研保留）；分段器 shared `MarkdownSegmenter`（MARKDOWN/TABLE/CODE）。附加区：`ClaudeAgentSteps`（步骤 ⏳/✓/✗ + 截断继续条 + 交付按钮）、`MessagePerformanceRow`。Turn 聚合：同 turn 相邻 agent 文本 item `mergeWithPrevious` → 段落间距 `Spacing.xs`（4），连续文档流（§1.3）。
**parts 协议（✅ M1/M2 已落地 / M4 渲染源已切）**：§0.2 `text`——一条回复多段交错（M2 流式按 `txt-N` 块级三段式装配，M4 拍平后打字机节拍经 `textOverride`（消息 content 节奏器 paced 输出）+ `showCursor` 保留，contentType `agent_text`）；`command`/`plan_preview` 已并入 `text`（2026-09-28 分类法重构，存量迁移转正，role=agent），回灌 Text part 按 role 标注 `agent`；claude 气泡整消息 legacy 渲染（part=null，claude_agent_state 步骤流不进 parts）。

### 3.3 日期分隔

列表装饰非消息。`ChatDateChip`（surfaceContainerHigh 底 r8 胶囊）。
![chat_date_chip](../assets/chat-cards/chat_date_chip-dark.png?v=20260927-4)

---

## 4. 富内容卡片

### 4.1 图表卡 `ChartSvgCard`

**协议**：

```jsonc
// ① 入口：LLM tool_call（ChatToolService @Tool draw_chart）
{
  "name": "draw_chart",
  "arguments": {
    "type": "bar",
    "title": "每月新增照片",
    "labels": "6月,7月,8月",
    "values": "128,96,214",
    "unit": "张"
  }
}

// ② 落库：content = 端侧 QuickJS 生成的 SVG 串
{
  "id": "c7…",
  "type": "tool_chart",
  "content": "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 360 240'>…柱状图元素…</svg>",
  "metadata": null
}
```

**UI**：无固定设计帧（端侧生成物）；点击进 `ChartPreviewOverlay` 全屏。
**渲染组件**：`ChartSvgCard` / `ChartSvgImage`（`ChartSvgImage.kt`）——androidsvg 解析 → ×2.5 栅格化位图；渲染中占位文案。生成链路：`assets/js/chart_bootstrap.js`（`Chart.bar/line/pie/timeline`，QuickJS `ChartJs.kt` 加载，返回 `{chart, summary}`，summary 作 observation 回传 LLM）。prompt 契约：图表唯一通路，禁 markdown 表格/ASCII。
**测试**：`GalleryJsTest` / `ChatRunScriptCapabilityTest`。
**parts 协议（✅ M1/M2 已落地）**：`MessagePart.Chart`——

| 字段 | 类型 | 含义 |
|---|---|---|
| `partId` | String | 块级 id（M1 迁移 `p0`…；M2 流式产物 = `call-N` 合成 id） |
| `svg` | String | 端侧 JS 生成的自包含 SVG（= content 列） |
| `state` | ToolPartState | M2 增；持久化恒缺省 `OUTPUT_AVAILABLE`（JSON 不写出），流式占位 `INPUT_STREAMING` 瞬态不落库 |

partsJson 实例见 §0.2 `tool_chart`。**回灌**（`toModelInput`，M2 起 toolCallId = `"<messageId>:<partId>"` 命名空间锚防跨消息碰撞）→ `ToolCall(…, "draw_chart", argsSummary="")` + `ToolResult(…, "Chart card (SVG, ${svg.length} chars)", isError = state==OUTPUT_ERROR)`；经 `toHistoryPair` 进 GET_CHAT_HISTORY 的现行线格式逐字：

```
("tool_call", "draw_chart")
("tool_result", "draw_chart → Chart card (SVG, 2831 chars)")
```

（SVG 本体不回灌，省 token；OpenAI 线格式目标形态见 §0.3③。）**端到端**：①②（上）→ `emitChartMessage` 落库行经 `withPartsJson` 双写 `[{"type":"tool_chart","partId":"p0","svg":"…"}]` → `summary` 作 observation 回传 LLM → `ChartSvgCard` 栅格化。M2 流式轨：`ToolInputStart(draw_chart)` 即插 `Chart(svg="", INPUT_STREAMING)` 占位（骨架/进度），产物落库后 `ToolOutputAvailable` 原位填充。**M4 渲染源已切 part**：item contentType `chart`，组件原样复用仅换数据源（`part.svg`）；流式中已填充（OUTPUT_AVAILABLE）的 Chart part 拍平时**跳过**——产物已落库为独立消息行，双显禁止（§1.3 三分流）。**红线**：无交互位图（交互图表走 render_html）；SVG 只由端侧模板生成（prompt 禁 LLM 手写 SVG/markdown 表格画图）。

### 4.2 HTML 卡 `HtmlCard`（双形态）+ 全屏查看器

**协议**：

```jsonc
// ① 入口：LLM tool_call（render_html；display 声明形态）
{
  "name": "render_html",
  "arguments": {
    "html": "<div style='width:100%;font-family:system-ui'>…仪表盘…</div>",
    "summary": "NVIDIA 2026 Q3 业绩概览",
    "display": "fullpage"
  }
}

// ② 落库：content = 清洗后 HTML；metadata.html_card = 双形态终判
{
  "id": "h9…",
  "type": "tool_html",
  "content": "<div style='width:100%…'>…</div>",
  "metadata": {
    "html_card": {
      "display": "fullpage", // LLM 声明原值；null = 未声明（按 inline）
      "displayMode": "FULLPAGE", // 端侧终判；null = 尚未判定（待测高）
      "measuredHeightPx": null, // 终判测高（CSS px ≈ dp）；fullpage 直判时可为 null
      "summary": "NVIDIA 2026 Q3 业绩概览"
    }
  }
}
```

**UI**（Ardot HtmlCard 页）：

| 形态 | 帧 | 视觉 |
|---|---|---|
| Inline 短卡 | `htmlcard/inline` 438:3 | 动态测高完全撑开，卡内直接交互 |
| Fullpage 预览 | `htmlcard/preview` 438:60 | 0.5 屏固定高 + 底部渐隐遮罩 + 「点击查看完整内容」提示条 |
| 全屏查看器 | `htmlcard/fullpage` 438:98 | ✕ 顶栏（标题=summary）+ 竖滚 + 滚动条 |

![inline](../assets/chat-cards/htmlcard-inline.png?v=20260927-4) ![preview](../assets/chat-cards/htmlcard-preview.png?v=20260927-4) ![fullpage](../assets/chat-cards/htmlcard-fullpage.png?v=20260927-4)

**渲染组件**：`HtmlCard` / `HtmlWebView` / `HtmlPreviewHintBar` / `HtmlPreviewFallbackCover`（`HtmlCard.kt`）+ `HtmlFullpageViewer`（同目录）+ `HtmlLinkPreviewOverlay`（`<a>` 落地页浮层）。机制：`HtmlCardSanitizer` 清洗（128KB/剔远程 script/iframe/form）→ `wrapHtmlDocument`（viewport + 响应式 reset）→ `HtmlCardDisplay` 纯函数分流（display==fullpage 直判；否则测高 >1.0 屏强制 FULLPAGE；终判落 metadata 防跳变）；Inline = console 出站测高 + ResizeObserver 跟随 + 防抖三件套；渲染失败 → 原生封面兜底。
**调试**：DEBUG `/html` → `HtmlCardSmokeSamples`（十一卡）。
**测试**：`HtmlCardDisplayTest` / `HtmlCardSanitizerTest` / `HtmlSandboxGuardTest`。
**parts 协议（✅ M1/M2 已落地）**：`MessagePart.HtmlCard`——

| 字段 | 类型 | 含义 |
|---|---|---|
| `partId` | String | 块级 id（M2 流式 = `call-N` 合成 id） |
| `html` | String | 清洗后 HTML（= content 列） |
| `meta` | HtmlCardMeta | `display`/`displayMode`/`measuredHeightPx`/`summary` 四字段（键名与 legacy metadata.html_card 一致，随迁；全空时整体缺省不写出） |
| `state` | ToolPartState | M2 增；语义同 Chart.state——清洗 Rejected 时补喂 `feedToolError` → 瞬态 OUTPUT_ERROR（不落库，reason 回传 LLM 引导重生成） |

partsJson 实例见 §0.2 `tool_html`。**回灌**（toolCallId 同为 `"<messageId>:<partId>"` 命名空间锚）→ `ToolCall(…, "render_html", argsSummary = "display=<display>"（未声明则空）)` + `ToolResult(…, meta.summary ?: "HTML card (${html.length} chars)", isError)`；`toHistoryPair` 逐字：

```
("tool_call", "render_html(display=fullpage)")
("tool_result", "render_html → NVIDIA 2026 Q3 业绩概览")
```

（HTML 本体不回灌，只有 summary/长度。）**端到端**：① tool_call → `HtmlCardSanitizer`（空白/超 128KB Rejected：不落库 + reason 回传 + M2 瞬态标错）→ ② 落库 + metadata.html_card → 测高后 `persistHtmlCardDisplayMode` 幂等回写终判 → `withPartsJson` 重算 parts → 双形态渲染；M2 流式轨同 Chart（`INPUT_STREAMING` 占位 → 原位填充）。**M4 渲染源已切 part**：item contentType `html_card`，`HtmlCard` 全套组件原样复用，数据源消息 payload → part（`part.html`/`part.meta`）；**测高 LruCache key 由消息 id 改拍平 item key `"messageId:partId"`**（调用点 `cacheKey = item.key`，§1.2）；流式 OUTPUT_AVAILABLE 跳过防双显同 Chart（§1.3）。**红线**：零 JS 桥；128KB 硬限（@Tool 软引导 100KB，两级口径）；正文禁嵌渲染级 HTML。

### 4.3 工程师任务卡（tool_task）

**协议**（无 tool_call 入口——工程师模式任务创建经 `persistEngineerTask` upsert；SSE 事件流驱动 `EngineerTaskReducer` 状态机，结构性事件才落库）：

```jsonc
// 落库：content = sourceText 前 50 字；metadata.engineer_task = 全量状态
{
  "id": "task-01JD2Z…", // = taskId（REPLACE 同 id 重插）
  "type": "tool_task",
  "content": "修复相册扫描时的崩溃",
  "timestamp": 1790000000000, // = startedAtMs（卡片锚定提交位置）
  "metadata": {
    "engineer_task": {
      "taskId": "task-01JD2Z…",
      "sourceText": "修复相册扫描时的崩溃",
      "sid": "claude-9a…",
      "status": "AWAITING_CONTINUE", // RUNNING | AWAITING_CONTINUE | AWAITING_DELIVER | COMPLETED | FAILED
      "stage": "Edit · TagScanOrchestrator.kt",
      "recentStages": ["读取文件", "分析堆栈", "编辑代码"],
      "turns": 50,
      "costCents": 128,
      "startedAtMs": 1790000000000,
      "updatedAtMs": 1790000217000,
      "fileChangeCount": 3,
      "truncatedReason": "达到最大轮次（50）",
      "errorSummary": null,
      "resultSummary": "已完成根因定位与修复，编译验证中断",
      "resolution": null, // CONTINUED | ABANDONED | DELIVERED | DELIVER_SKIPPED（审批动作回填）
      "deliverBranch": null
    }
  }
}
```

**UI**（2026-09-27 两分区改版，Muse 风格——上半 InfoZone 任务信息 / 下半 ActionZone 选项或按钮，**画布已定稿、App 实现待迁移**）：

| 态 | 帧 | 下半部动作区 |
|---|---|---|
| running 折叠 | `taskcard/collapsed` 438:181 | 整宽胶囊 [停止] |
| running 展开 | `taskcard/expanded` 438:208 | 同上（信息区含时间线/事件/diff） |
| 待审批 | `taskcard/approval` 438:264 | radio 选项列表（继续执行/到此为止） |
| 完成 | `taskcard/done` 444:31 | [暂不｜推送交付] 双按钮 |

![collapsed](../assets/chat-cards/taskcard-collapsed.png?v=20260927-4) ![expanded](../assets/chat-cards/taskcard-expanded.png?v=20260927-4) ![approval](../assets/chat-cards/taskcard-approval.png?v=20260927-4) ![done](../assets/chat-cards/taskcard-done.png?v=20260927-4)

**渲染组件**：`EngineerTaskCard` + `EngineerTaskNativeActionBar`（现实现动作条外置卡底）+ `EngineerTaskFallbackCard`（`components/EngineerTaskCard.kt`）。机制：`EngineerTaskHtml`（L1 端侧模板 + 状态 JSON 组装完整 HTML，CSS 变量走 tokens）经 `HtmlCard` 双形态渲染；displayMode 粘滞不落库；500ms 合帧节流（`EngineerTaskThrottle`）；状态文本 HTML escape（唯一注入面）；停止 = resolve ABANDONED + 取消 SSE。任务中心列表项保持原生紧凑形态（`taskcenter/`）。
**parts 协议（✅ M1/M2 已落地）**：`MessagePart.TaskCard`——

| 字段 | 类型 | 含义 |
|---|---|---|
| `partId` | String | 块级 id |
| `toolCallId` | String | 持久化行恒 = taskId；M2 流式瞬态用 `call-N` 合成 id（不落库） |
| `state` | ToolPartState | 五态投影（`toToolPartState()`）：RUNNING→`INPUT_AVAILABLE`；AWAITING_CONTINUE/AWAITING_DELIVER→`APPROVAL_REQUESTED`；COMPLETED→`OUTPUT_AVAILABLE`；FAILED→`OUTPUT_ERROR` |
| `task` | EngineerTaskState | 完整状态快照（16 字段，键名与 legacy metadata.engineer_task 一致） |

**M2 overlay**：`TaskCardOverlay.overlayLiveTaskState`——SSE live 态对消息内首个 TaskCard part **同 id 原位覆写**，legacy `engineerTask` 渲染字段自 part 投影（同源防漂移；500ms 节流在 displayMessages 管线，首帧直通）；`toUiModel` 对 TASK_CARD 已恢复 parts 双读填充。**错误双轨的持久化轨**：FAILED 的 `errorSummary` 经 M1 双写落库、回灌 `isError=true`（Chart/HtmlCard/脚本错误为瞬态轨不落库，spec §5.3）。**M4 渲染源已切 part**：item contentType `task_card`，`EngineerTaskCard` 组件与 H2 HTML 化路线不变，**状态源改 `ToolPartState`**（part.state 七态投影，五态经 `toToolPartState()` 映射；live 态仍走 overlay 同 id 覆写）——spec §7.4 复用清单落地。

**测试**：`EngineerTaskHtmlTest` / `EngineerTaskReducerTest` / `EngineerTaskStateSerdeTest` / `ChatViewModelEngineerTaskTest`；冒烟 `/task`（`EngineerTaskSmokeSamples`）。

**回灌** → `ToolCall(taskId, "engineer_task", argsSummary = sourceText)` + `ToolResult(taskId, "engineer_task", errorSummary ?: resultSummary ?: "Task status: <status>", isError = state==OUTPUT_ERROR)`；`toHistoryPair` 逐字：

```
("tool_call", "engineer_task(修复相册扫描时的崩溃)")
("tool_result", "engineer_task → 已完成根因定位与修复，编译验证中断")
// FAILED：("tool_result", "engineer_task → SSE connection lost (failed)")——错误进上下文，模型可自我修正
```

**端到端**：工程师模式提交 → `task_<uuid>` 行 upsert（RUNNING）→ SSE 结构性事件经 `EngineerTaskReducer` 五态迁移逐次 upsert（parts 随 `withPartsJson` 重算）→ 审批 `resolved()` terminal 化（resolution 粘滞）→ `EngineerTaskCard` 渲染。partsJson 实例见 §0.2 `tool_task`。

### 4.4 相册搜索结果卡 `MediaResultsCarousel`

**协议**（content=资产数组、metadata=查询上下文，两列分担）：

```jsonc
// 落库（来源：search_media 工具 / 脚本 return ids 自动补卡）
{
  "id": "m5…",
  "type": "data_media_results",
  "content": [
    {
      "id": 1024,
      "uri": "content://media/external/images/media/1024",
      "type": "PHOTO",
      "captureDate": 1757000000000,
      "fileName": "IMG_20260805.jpg",
      "faceFocusY": 0.42
    }
  ], // 已截到展示上限
  "metadata": {
    "query": "去年夏天的海边",
    "totalCount": 87, // 未截断真实命中数
    "isRefinement": false
  }
}
```

**UI**：`chat_photo_card` 组件（120×150 照片卡 + 👍/👎/🔁 反馈钮列 + DateChip 角标）横滑轮播。
![chat_photo_card](../assets/chat-cards/chat_photo_card-dark.png?v=20260927-4)
**渲染组件**：`MediaResultsCarousel`（`components/MediaResultsCarousel.kt`）——原生 Compose（LazyRow + Coil）；媒体被删后同步收缩。
**测试**：`ChatGallerySearchTest` / `SearchSnapshotBuilderTest`。
**parts 协议（✅ M1 已落地）**：`MessagePart.MediaResults(partId, results: MediaResultsUi)`——`results` 合一 legacy 两列：`query`/`totalCount`/`isRefinement`（← metadata 三字段）+ `assets`（← content 数组，含 `faceFocusY`）；`feedbackState` 会话内 👍/👎 反馈态（空 Map 缺省不写出）。partsJson 实例见 §0.2 `data_media_results`。**回灌：丢弃**（data part 不进上下文，对齐 Vercel 丢弃规则；LLM 对结果的感知来自 `search_media` 即时 observation，非历史回灌）。**端到端**：`search_media` tool_call → `SearchIntent`→`StructuredFilter`→`MediaSearchEngine` → **同一用户回合至多一张卡**（`getLatestMediaResultsSinceLastUserMessage` 定位行 id 做 REPLACE upsert；以图搜图等自成新回合追加新卡）→ 落库行双写 parts → `MediaResultsCarousel`。**M4**：item contentType `media_results`，组件无改动（spec §7.4 复用清单）。**红线**：资产字段白名单（重建最小 MediaAsset，不含 GPS/OCR/标签明细）。

### 4.5 AI 优化抽卡候选条 `GachaCandidateStrip`

**协议**（落库；metadata 全量负载）：

```jsonc
{
  "id": "g8…",
  "type": "data_optimize_candidates",
  "content": "",
  "metadata": {
    "sourceImageUri": "file://…/edit_tmp.jpg",
    "scene": "portait_enhance",
    "recommendedIndex": 2, // NIMA 最优卡；-1 = KeepOriginal 不预选
    "drawIndex": 1, // 第几组（换一组 +1）
    "candidates": [
      {
        "direction": "自然",
        "thumbPath": "/data/…/c0.webp", // 空串 = 落盘失败（UI 占位）
        "nimaScore": 5.82, // null = 未评分
        "rejected": false
      }
    ],
    "usedFingerprints": ["a3f1…"] // 「换一组」回传 exclude 的去重指纹
  }
}
```

**UI**：设计稿已随交付清理（git 历史可查）；选中高亮 / 换一组 loading / 护栏 rejected / 进程重建只读态；确认后消息改写为 `image`（+role=agent）。
**渲染组件**：`GachaCandidateStrip`（`components/GachaCandidateStrip.kt`）+ `ChatOptimizeGachaController`（内存态 `hasPending(id)` 驱动 `gachaInteractive`）。
**测试**：`ChatViewModelGachaTest` / `OptimizeCandidateGroupTest` / `ChatOptimizeGachaControllerTest`。
**parts 协议（✅ M1 已落地）**：`MessagePart.OptimizeCandidates(partId, group: OptimizeCandidateGroup)`——group 六字段全量入 part（键名与 legacy metadata 一致；kotlinx 与 org.json 双 serde 线格式互不干扰）；单条消息 parts 只有此一块（content 列的 explanation **不成** Text part，仅行级兜底时出现）。partsJson 实例见 §0.2 `data_optimize_candidates`。**回灌：丢弃**（data part）。**端到端**：AI 一键优化 → `ChatOptimizeGachaController.draw` 端侧并行出 N 候选 → `data_optimize_candidates` 行（metadata = group JSON）→「换一组」同 id 覆写（drawIndex+1、usedFingerprints 排重）/「就用这张」行改写为 `image`（+role=agent，parts 随之重算为 image part）→ `GachaCandidateStrip`。**M4**：item contentType `optimize_candidates`，组件无改动（spec §7.4 复用清单）。**红线**：交互态（controller `hasPending`）是进程内存，进程重建后卡条降级只读。

### 4.6 Agent 图片结果卡 / 编辑结果卡

**协议**（落库）：

```jsonc
// image + role=agent（生成/优化结果图；← agent_image）
{
  "id": "r2…",
  "type": "image",
  "role": "agent",
  "content": "已生成修复后的照片",
  "metadata": {
    "imageUri": "file:///data/…/result.webp",
    "saved": true
  }
}

// tool_image_edit（对话式编辑结果：图 + 说明；← agent_edit_result）
{
  "id": "r3…",
  "type": "tool_image_edit",
  "content": "已完成背景替换：海边 · 保留人物主体",
  "metadata": {
    "imageUri": "file:///data/…/edit.webp",
    "saved": false
  }
}
```

**UI**：通栏结果图；LRU 清理后 → `ExpiredImagePlaceholder` 灰框过期占位；点击进 `ChatImagePreviewOverlay`（保存/删除/OCR）。
**渲染组件**：`ChatMessageItem` isImage / isEditResult 分支（原生 Compose，Coil；`ChatImageLive` 判定存活）。媒体红线：不经 LLM 排版。
**测试**：`ChatImageLiveTest` / `ChatImageRenderer*Test` / `ChatViewModelEditResultTest`。
**parts 协议（✅ M1 已落地）**：`MessagePart.Image(partId, ref, saved=false)` 与 `MessagePart.EditResult(partId, ref?, description, suggestions?, saved?)`——Image 的 `ref`：user 图 = 内部存储路径（saved 恒缺省）、agent 图 = 结果图 URI（保存后 `"saved": true` 写出）；EditResult 的 `description` = content 列（**回灌唯一通道**），`ref` 为 null 时整键不写出（UI 落 legacy 兜底）。partsJson 实例见 §0.2 `image` / `tool_image_edit`。**回灌**（[PRIVACY] 媒体红线：图片本体不进上下文）——Image → 英文中性占位：user `[user sent an image]` / agent `[assistant generated an image]`（`toHistoryPair` 落 `("user"|"agent", 占位)`——role 升格后标注自消息级 role 列，保多轮回合结构）；EditResult → `TextMessage(ASSISTANT, description)`（只回灌文字说明，防多轮编辑上下文断裂；结果图/ref/suggestions 均不进）。**端到端**（EditResult）：`edit_image` tool_call（`*_delta` 相对调整带步进截断：美颜 ±10/亮度 ±15 等，绝对值不限幅）→ `ChatEditProcessor` Recipe 渲染 → `tool_image_edit` 行（图 + 说明 + suggestions）→ 气泡内结果图 + 建议条；agent Image 三来源：`adjust_image` / AI 优化 fallback / 抽卡「就用这张」行改写（见 §4.5）。**M4**：AGENT_IMAGE / AGENT_EDIT_RESULT 拍平走**整消息 legacy 直通**（contentType `legacy_message`，part=null——渲染器读 legacy 字段，见 §1.3 直通清单；user 图走 USER 整颗单 item，见 §3.1）。

### 4.7 流程卡：清理确认/完成 · 游客引导 · 写操作确认

| 卡 | 组件（Ardot） | 数据/触发 | 代码 |
|---|---|---|---|
| 清理确认卡 | `chat_cleanup_card` 438:397（9 宫格缩略 + 宽主钮 + 双次钮） | 清理流程确认 | 设计正典；代码侧写确认 AlertDialog 简化形态 |
| 清理完成卡 | `chat_cleanup_done_card` 438:409（✓ + 标题 + caption） | 清理完成回显 | 同上 |
| 游客引导卡 | `chat_nudge_card` 438:357（r28 sheet + 注册主钮） | 游客态注册引导 | `ChatRegistrationSheet` + `GuestNudgeBanner` |
| 写操作确认 | —（同确认卡皮） | JS `capability.dispatch` → `PendingWriteConfirmation`（120s 超时/串行互斥/source JS\|TOOL_CALL） | `WriteConfirmationController` + ChatScreen AlertDialog |

```jsonc
// 写确认触发（脚本内 bridge 调用 → 端侧弹原生确认；用户拒绝/超时 → Promise reject 回传脚本）
{
  "method": "delete_media",
  "params": {
    "ids": [1024, 1025, 1026]
  }
}
```

![cleanup](../assets/chat-cards/chat_cleanup_card-dark.png?v=20260927-4) ![cleanup_done](../assets/chat-cards/chat_cleanup_done_card-dark.png?v=20260927-4) ![nudge](../assets/chat-cards/chat_nudge_card-dark.png?v=20260927-4)

---

## 5. 规划中卡片

### 5.1 审批三要素卡（📋 spec 定稿待实施）

DESTRUCTIVE 批量删除的聚合确认卡：数量 + 珍贵信号（收藏/老照片）+ 可恢复性三要素；双链路（JS 写通路 + Tier B 批量阈值 3）；形态 = 升级确认对话框（不做 chat 内审批卡）。spec：`docs/superpowers/specs/2026-09-26-approval-three-element-card-design.md`。

### 5.2 ADR-016 parts 模型迁移（✅ M1-M4 已落地：M1-M3 合 main `46a6e91fb`；M4 在 `feat/chat-parts-m4` 待合——真机验收待补）

一条回复 = parts 文档，卡片 = 独立 block；Turn 聚合渲染；markdown AST 管线换代。卡片 UI/渲染形态**不变**（ADR-016 D2）。已落地：M1（`MessagePart.kt` sealed 八类 + `MessagePartsCodec.kt` partsJson 线格式 + `LegacyMessagePartsConverter.kt`（已改名 `MessagePartsConverter.kt`，仅认新 8 值；legacy 映射收编 `LegacyChatTypeMigration.kt`——2026-09-28 分类法重构）全枚举迁移/行级兜底 + Room v24→v25 回填 + 回灌转换）、M2（`domain/chat/streaming/` 三件套 + `TaskCardOverlay` 任务卡迁入 parts + Chart/HtmlCard `state` 字段 + 回灌 `"<messageId>:<partId>"` 命名空间 id + `RoundStarted` 显式轮边界）、M3（`AgentMarkdown` AST 正文，jeziellago 移除）、M4（渲染源切 parts：`ChatListFlattener` 拍平 + Turn 聚合视觉层 + §8 性能收口；验收 `M4_CHAT_FLATTEN_ACCEPTANCE.md`，**真机冒烟/重组实测/截图基线待补**）。M5 = iOS ios-follow 跟随。**2026-09-28 分类法重构**（spec `2026-09-28-chat-type-taxonomy-design.md`）：6 个 part serialName 改值为 3 分类 8 值 + `PartCategory` 分类载体（getter-only，不进线格式）；`role` 升格消息级独立列（Room v25→v26，MIGRATION_25_26 单事务加列 + 全量改写）；运行时转换器纯净化（legacy 13→8 映射仅存 `LegacyChatTypeMigration`，服务迁移与备份恢复）。线格式实例见 §0.1/§0.2，各卡 parts 协议见 §3/§4 各节，渲染矩阵见 §1.3 + chat.yaml §3.2。数据形态（spec §3，已按实现校准）：

```kotlin
// shared commonMain（M1-M3 已合 main；M4 ChatListFlattener 在 feat/chat-parts-m4）
sealed interface MessagePart { val partId: String }
// Text(markdown, state: PartState) | Chart(svg, state: ToolPartState) | HtmlCard(html, meta: HtmlCardMeta, state: ToolPartState)
// TaskCard(toolCallId, state, task: EngineerTaskState) | MediaResults(results: MediaResultsUi) | Image(ref, saved) | EditResult(…) | OptimizeCandidates(…)
```

legacy 13 值 → 新 type（+role）迁移映射见 §0.2 末表（`LegacyChatTypeMigration` 实况）；历史 parts 映射表 `2026-09-27-chat-parts-rendering-design.md` §2。

---

## 6. 平行渲染体系（非主聊天流，登记备查）

| 体系 | 模型 | 说明 |
|---|---|---|
| 浮动 AI 面板（`features/common/chat/`） | sealed `AgentMessage`（UserText/AgentText/CommandExecution/PlanPreview/PlanProgress/PlanResult） | 相机/相册页内嵌面板，sealed 体系自带 Command/Plan 形态（平行模型非 Room type；原 Room `command`/`plan_preview` 已随 2026-09-28 分类法重构并入 `text`） |
| 悬浮聊天气泡（`service/chat/FloatingChatBubbleService`） | 复用主 `ChatViewModel.displayMessages` | 仅 Text/markdownText 简化气泡，无富卡 |
| 任务中心（`features/chat/taskcenter/`） | `UserTask` 域模型 + EngineerTask 分区 | `UserTaskCard` 原生列表项（PENDING/RUNNING/PAUSED/FAILED/终态 + supportedActions 按钮） |
| iOS 对等（`iosApp/PoLang/Features/Chat/`） | Swift 原生 `ChatMessage`（未消费 shared 模型） | ChartSvgCard.swift/GachaCandidateStrip.swift 等对等实现，走 /ios-follow 对齐 |

---

## 7. 测试与冒烟入口汇总

- **单测**：见各卡片小节；DAO 层 `ChatMessageDaoTest`；shared `MarkdownSegmenterTest` / `StreamingPacingControllerTest` / `MessagePartsCodecTest` / 分类法 `MessagePartsConverterTest` + `LegacyChatTypeMigrationTest`（新 8 值 + 13→8 全枚举，2026-09-28）；M4 `ChatListFlattenerTest`（拍平规则全枚举 13 例）+ streaming 包（adapter/reducer）；M3 `InlineHtmlAnnotatorTest`（白名单 4 例）+ `MarkdownStreamRobustnessTest`（流式解析鲁棒 3 例）。
- **ui-driver 冒烟**（DEV_ONLY，DEBUG 构建）：`/html` → `HtmlCardSmokeSamples`（十一卡含双形态/远程/ALL_IN_ONE）；`/task` → `EngineerTaskSmokeSamples`（五态 + 超屏展开）。⏳ M4 真机冒烟待补（设备离线，清单见 `docs/06-QA/M4_CHAT_FLATTEN_ACCEPTANCE.md` §3：流式/`/html`/`/chart`/`/task`/长列表滚动 + 重组实测 + Turn 聚合截图基线）。
- **设计稿验收**：health-check（`scripts/ardot-health-check.py`）+ Light 双模（`scripts/ardot-light-verify.py`）+ refs 快照（`scripts/export-ardot-snapshot.py`）。

## 8. 文档索引

| 主题 | 文档 |
|---|---|
| 渲染架构宪法 | `docs/02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md`（+ 实施 spec 2026-09-27-chat-parts-rendering-design） |
| type 分类法（3 分类 8 值 + role 列） | `docs/superpowers/specs/2026-09-28-chat-type-taxonomy-design.md`（2026-09-28 重构：serialName 改值 + `PartCategory` + role 升格 Room v26） |
| 富内容渲染决策（原 ADR-014 D1~D6） | 已并入 ADR-016（D2 渲染 / D3 沙箱 / D4 iOS / D5 样式 / D6 不做；编号 014 永久留空） |
| HTML/图表/脚本实现 SSOT | `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md` §5/§7/§7.1 |
| Turn 聚合 + 拍平渲染矩阵（视觉 SSOT） | `docs/08-UI-SPECS/screens/chat.yaml` §3.1 turn_aggregation / §3.2 parts_rendering（M4 已落地 ✅，iOS TODO 口径） |
| M3/M4 验收记录 | `docs/06-QA/M3_CHAT_MARKDOWN_ACCEPTANCE.md`（AST 正文 + immediate 定案）/ `docs/06-QA/M4_CHAT_FLATTEN_ACCEPTANCE.md`（四块证据链，真机待补） |
| 双形态 + 任务卡 HTML 化 | `docs/superpowers/specs/2026-09-26-html-card-two-tier-design.md` |
| 任务卡产品线 | `docs/superpowers/specs/2026-09-25-engineer-task-card-design.md` · UserTask 协议 2026-09-26 |
| 设计稿 | Ardot polang-ui-spec（fileId 715061534788814）HtmlCard 页 438:2 · ChatComponents 页 438:328；refs `docs/08-UI-SPECS/screens/refs/ardot/` |
| 样式 tokens | `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md` |
