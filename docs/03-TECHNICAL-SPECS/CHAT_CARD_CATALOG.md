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
  "type": "…", // 新分类法 9 值之一（3 分类，见下）
  "role": "agent", // 消息角色 "user"/"agent"（v26 新列，取代 user_/agent_ type 前缀）
  "content": "…", // 按 type 语义不同，见各卡
  "timestamp": 1790000000000,
  "modelUsed": "deepseek-chat", // 可空
  "metadata": { "…": "…" }, // 卡片专属负载包（实际存储为 JSON 字符串）
  "partsJson": [ "…" ] // parts 数组 JSON（读面权威源，实际存储为 JSON 字符串）
}
```

`type` 全集（2026-09-28 分类法重构，spec `docs/superpowers/specs/2026-09-28-chat-type-taxonomy-design.md`；2026-10-06 扩第 9 值 `tool_browser`，browser-vnc 直播卡 spec §4）——**3 分类 9 值**，命名规则 `^{category}_{kind}$`（`tool_`/`data_` 为保留前缀，content 免前缀，协议值禁 UI 容器词）：

- **content**：`text` · `image`
- **tool**：`tool_chart` · `tool_html` · `tool_task` · `tool_image_edit` · `tool_browser`
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
    "stage": "Edit  TagScanOrchestrator.kt", // 「工具名␣␣细节」，渲染期本地化为「工具名 · 正在修改 细节」
    "recentStages": ["Read  TagScanOrchestrator.kt", "Edit  2 files", "Bash  ./gradlew compile"], // 原始工具串，时间线中文名渲染期映射（stageMaps）
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

**M4 渲染源切 parts + Turn 聚合**（spec §7.2/§7.3/§8）：

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
| 云端 Agent H5 卡 | `tool_html`（主聊天云端回合）/ 调试页内存消息（不落 Room） | `HtmlCard`（复用 §4.2 同族） | WebView | —（同 HTML 卡族） | ✅ Android（2026-10-22）；iOS 挂账（§4.9） |
| 工程师任务卡 | `tool_task` | `EngineerTaskCard` + 原生动作条 | WebView L1 模板 | `taskcard/*` 438:181/208/264/444:31 | ✅（两分区改版待实现迁移） |
| 搜索结果卡 | `data_media_results` | `MediaResultsCarousel` | 原生 | `chat_photo_card` 387:143 | ✅ |
| 抽卡候选条 | `data_optimize_candidates` | `GachaCandidateStrip` | 原生 | —（设计稿已随交付清理） | ✅ |
| Agent 图片结果卡 | `image`（+ role=agent） | `ChatMessageItem` isImage 分支 | 原生 | —（跟随会话帧） | ✅ |
| 编辑结果卡 | `tool_image_edit` | isEditResult 分支 | 原生 | — | ✅ |
| 浏览器直播卡 | `tool_browser` | `BrowserLiveCard` + `BrowserFramePreviewOverlay`（M2 全屏接管） | 原生（帧位图：M2 WS binary 推流 / HTTP base64） | —（视觉契约固化 chat.yaml §18） | ✅（Android 首发，iOS 跟随） |
| A2UI GenUI 卡 | `data_ui` | `A2uiCardHost`（androidx.a2ui 官方渲染器，polang catalog = material + BarChart） | 原生（Compose，云端下发骨架 + 端上 HandleResolver 填槽） | —（GenUI 生成物无固定帧） | ✅（hybrid M2，2026-10-21） |
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
**iOS 渲染组件（2026-10-03 M5 换代：AttributedString 单 Text 方案废弃——列表无标记/无删除线/段落糊团）**：`AgentTextView`（`iosApp/PoLang/Features/Chat/AgentTextView.swift`，同一 shared `MarkdownSegmenter` 分段）→ MARKDOWN 段 `MarkdownText`（ChatView.swift：**MarkdownUI 2.4.1**（SPM `gonzalezreal/swift-markdown-ui`，cmark-gfm 全量方言）AST→原生组件 + `Theme.polangChat`（`DesignSystem/PolangMarkdownTheme.swift`，§5 排版阶梯/色派生全落地：标题 1.30→1.00 全 Bold、inline_code 等宽+底色 α0.12、link primary+下划线、quote italic、divider α0.20、MARKDOWN 段内缩进代码块 scale 0.80+bg α0.08+横滚）；parse gating 同契约 1500 字同步 / 超阈异步+80ms 防抖）+ TABLE 段 `AgentTableView`（CJK 计宽列 + 完整网格线 + 超宽横滚）+ CODE 段 `CodeBlockView`（12 行折叠/复制/横滚）。验证件：`PoLangTests/MarkdownRenderingSmokeTests`（流式逐前缀重解析不崩溃 + GFM 结构覆盖）+ 真机 `-startPage 2 -chatMdDemo`（全语法 torture 种子，仅 DEBUG）。**iOS 差距台账**：① 表格/代码块全屏预览 overlay 未实现（Android 有 TablePreviewOverlay）；② MARKDOWN 段内代码块无语法高亮（MarkdownUI 默认 PlainText highlighter，`CodeSyntaxHighlighter` 协议可后插 Splash）；③ inline HTML 白名单（u/mark/sup/sub）未实现（MarkdownUI 将 InlineHTML 节点文本化，后续以预处理器补）。
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
      "stage": "Edit  TagScanOrchestrator.kt", // 「工具名␣␣细节」（file_change:path 特例），渲染期本地化为「工具名 · 正在修改 细节」
      "recentStages": ["Read  TagScanOrchestrator.kt", "Edit  2 files", "Bash  ./gradlew compile"], // 原始工具串；时间线中文名（读取文件…）由 stageMaps 渲染期映射
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

**渲染组件**：`EngineerTaskCard`（两分区单卡：HTML 信息区 + 卡内原生动作区 `EngineerTaskActionZone`，外层 r16；整卡统一 **cell 底**（Light 纯白 / Dark #1A1A1A）——2026-10-11 Light 连体定稿（design taskcard/light-\*）：上下两区同色、分区仅靠动作区顶 hairline（outlineVariant），白卡从微信灰列表底自然浮起，无描边）+ `EngineerTaskFallbackCard`（`components/EngineerTaskCard.kt`）。机制：`EngineerTaskHtml`（L1 端侧模板 + 状态 JSON 组装完整 HTML，CSS 变量走 tokens）经 `HtmlCard` 双形态渲染；displayMode 粘滞不落库；500ms 合帧节流（`EngineerTaskThrottle`）；状态文本 HTML escape（唯一注入面）；阶段串格式「工具名␣␣细节」/「file_change:path」（reducer `stageLabel` 产出），渲染期经 `parseStage`/`stageMaps` 本地化为「中文阶段名 · 细节」（时间线）与「工具名 · 正在X 细节」（当前阶段行）；停止 = resolve ABANDONED + 取消 SSE。任务中心列表项保持原生紧凑形态（`taskcenter/`）。
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

### 4.8 浏览器直播卡 `BrowserLiveCard`（tool_browser，2026-10-06 新增；2026-10-09 M2 WS 推流 + 全屏接管；2026-10-11 M2.5 接管页三段布局 + 路由捷径 + 租约自愈；2026-10-11 M2.7 接管交互链路修复）

云端浏览器会话的 INLINE 直播卡（browser-vnc 直播卡 spec §4；Android 首发定稿，iOS 走 ios-follow，视觉契约固化 `docs/08-UI-SPECS/screens/chat.yaml` §18）。M2（2026-10-09）：watch 通道升级 WS 推流（screencast binary 帧），点按帧进全屏接管 overlay。M2.5（2026-10-11）：接管页三段布局重构（顶栏返回/状态胶囊四态/暂停/Interact↔Zoom 模式切换 + IME 附件行特殊键 key action + 暂停停流不停会话，详见 chat.yaml §18 full_preview）；意图路由增浏览器捷径 `BROWSE_WEB`（「用浏览器打开 X」零延迟直落全量 agent loop，不再误路由相册搜索，ADR-015）；server 网关 busy 自愈（App 进程死亡租约泄漏不再锁死：open 撞占用经 bridge `/session/{id}/status` 探测，仅 `session_expired` 判死条件释放重试）；bridge 增 `key` action（Enter/Tab/Esc 白名单）与 screencast 30s 节流 touch 保活。M2.6（2026-10-11）：接管页复刻 Muse 观看窗三态（observing 唯一「接管浏览器」CTA / in_control 接管即停 agent + 控制行 / ended 水印 + 返回对话，详见 chat.yaml §18）。M2.7（2026-10-11）接管交互链路修复：①动作通道 WS→HTTP 原文透传（`/v1/browser/action`，WS 下行帧洪泛时同连接 action 排队数十秒不可达；VM 按会话串行保序，session_expired 置 OUTPUT_ERROR）；②server 代理应答 bridge Ping + bridge→app 帧字节闸门（90KB/s、DROP_OLDEST 保新帧，HK↔国内窄带不再积压堵死 pong，详见 server/AGENTS.md）；③状态胶囊六态（新增「页面加载中」：动作后无新帧窗口期）+ 重连判定改 socket 感知（静态页帧恒旧≠断流，`connectedSessions`）；④IME 弹出帧区额外收缩底栏内容高度（控制行/输入行不压帧）+ clickAt 坐标映射修偏（padding 挂容器层）；⑤turn 结束兜底 close 竞态修复（宽限期 90s 调度——turn 刚结束用户进卡接管不再撞在途 close 导致动作全 session_expired；接管/观看豁免，新回合 flush，观看页退出无回合即 close，详见 chat.yaml §18 session_lifecycle）；⑥直播卡断锚修复（running 卡锚点=流式占位消息，turn 结束清除 → 宽限期内卡片消失无法进卡；displayMessages 为无锚 live 会话合成瞬态占位消息，Room 定格卡到达自动让位无双显）。M2.8（2026-10-11）手势统一：in_control 取消 Interact/Zoom 子模式切换——点按/滑动/捏合全部直接作用于帧（点按→clickAt，缩放态经 graphicsLayer 逆变换还原坐标；1x 拖动→scroll；捏合→就地缩放；缩放态拖动→平移视野；双击复位），顶栏模式切换按钮移除。

**协议**：

```jsonc
// ① 入口：LLM tool_call（ChatToolService @Tool browser_* 七原子工具族）
{
  "name": "browser_open",
  "arguments": { "url": "https://example.com" }
}
// 后续动作：browser_navigate / browser_click / browser_type / browser_extract /
// browser_screenshot / browser_close（均带 sessionId；click/type 三模式定位 index>targetText>selector）

// ② 落库：会话结束定格卡——content = BrowserLive part 整颗 JSON（metadata 留空）
{
  "id": "browser_1790000000000_1",
  "type": "tool_browser",
  "role": "agent",
  "content": {
    "type": "tool_browser",
    "partId": "p0",
    "sessionId": "9f3a…",
    "state": "OUTPUT_AVAILABLE",       // 失败 OUTPUT_ERROR
    "currentUrl": "https://example.com/",
    "pageTitle": "Example Domain",
    "frameJpegBase64": "/9j/4AAQ…",     // 最终帧（720p JPEG）
    "actions": [                        // 最近 3 步动作流水，新在尾
      { "description": "点击「价格」", "ok": true }
    ],
    "actionCount": 7,
    "resultSummary": "浏览器会话已结束，共 7 个动作"
  },
  "modelUsed": "browser"
}
```

**UI**：三层结构——头部行（页面标题 + URL + 三态状态字：running 前置 14dp spinner / done「共 N 步操作」/ failed，failed 用 error 色余者 onSurfaceVariant）+ 16:9 帧区（base64 后台解码位图，点按进全屏）+ 动作流水（≤3 行，失败行 error 色）；定格态隐藏 spinner 定格最终帧。会话中 watch 通道（M2，2026-10-09）= WS 推流（`LaunchedEffect(sessionId, running)` 启动 + `DisposableEffect` onDispose 断开；screencast binary 帧 + frame_meta），HTTP 轮询 1s 保留回退。全屏 = `BrowserFramePreviewOverlay`（M1 为纯预览：base64 数据源 + 双指 1x~5x 缩放/拖动 + 关闭按钮 + BackHandler；M2 升级全屏接管——点按→clickAt（坐标经 ContentScale.Fit 映射回帧坐标）、拖动→scroll（方向取反符合触屏惯例）、底部输入框→typeText，坐标动作 M2.7 起经 HTTP `/v1/browser/action` 原文透传下发（WS 仅帧接收）；M2.8 起手势统一免模式切换：点按→clickAt（缩放态经 graphicsLayer 逆变换 invertViewTransform 还原内容坐标）、1x 拖动→scroll、捏合→就地缩放 1x~5x、缩放态拖动→平移视野、双击→复位）。槽位与其他卡片同（LazyColumn 统一 horizontal padding，卡自身不带外边距）；外层 12dp 全圆角无 token 档（四张 chat 卡硬编码现状，chat.yaml §18 注记）。
**渲染组件**：`BrowserLiveCard` / `BrowserFramePreviewOverlay`（`components/BrowserLiveCard.kt`）+ `BrowserActionFormatter`（动作流水文案）；live 态 = `BrowserLiveOverlay.overlayLiveBrowserState`（shared，同 sessionId 原位覆写，形态与 `overlayLiveTaskState` 同构）+ ChatViewModel `_browserLiveSessions` 内存态（500ms sample 节流；M2 起 WS 帧事件原位覆写，非主动断开仅降级记日志不标会话失败）；`data/remote/picme/BrowserWebSocketClient`（M2：OkHttp WS，frameEvents/disconnectEvents 两 SharedFlow；M2.7 起仅帧接收 + `connectedSessions` 连接感知供重连判定，动作下发移出）+ `data/remote/picme/BrowserSessionClient`（HTTP watch 轮询回退 / turn 结束兜底 close 直连；M2.7 增 `rawAction` 原文透传动作通道，VM 按会话 Job 链串行保序）。
**parts 协议（✅ 2026-10-06 已落地）**：`MessagePart.BrowserLive`——

| 字段 | 类型 | 含义 |
|---|---|---|
| `partId` | String | 块级 id（流式占位 = `call-N`；落库定格卡 `p0`） |
| `sessionId` | String | 云端浏览器会话 id（overlay 覆写锚） |
| `state` | ToolPartState | 占位 `INPUT_STREAMING`（瞬态不落库）；定格 `OUTPUT_AVAILABLE` / 失败 `OUTPUT_ERROR` |
| `currentUrl` / `pageTitle` | String | 头部行数据源 |
| `frameJpegBase64` | String? | 最新帧（M2 WS 推流 / HTTP 轮询回退 / 动作响应更新；**帧永不回灌 LLM**，媒体红线同 Image） |
| `actions` | List\<BrowserActionEntry\> | 最近动作流水（至多 3 条，新在尾；`description`+`ok`） |
| `actionCount` / `resultSummary` / `errorReason` | Int / String? / String? | 定格统计与摘要 |

**回灌**（`toModelInput`，toolCallId = `"<messageId>:<partId>"` 命名空间锚）→ `ToolCall(…, "browser_session", argsSummary="")` + `ToolResult(…, resultSummary ?: 失败原因 ?: 会话概览, isError = state==OUTPUT_ERROR)`——只回灌文本摘要，帧与动作流水不进上下文（token 保护；§0.3 硬约束①②④过线）。**端到端**：`browser_open` tool_call → `BrowserSessionCapability` → 网关 `/v1/browser/open`（wantFrame=true）→ xuxing bridge → 占位 part（`INPUT_STREAMING`）经 `BrowserLiveOverlay` 逐动作原位覆写 → `browser_close`/超时定格 → `emitBrowserCardMessage` 落 `tool_browser` 行（partsJson 由 `insertMessageWithParts` 自 content 现算，`MessagePartsConverter` 认 `tool_browser` 直通解码）→ 冷启动从 Room 恢复定格卡；降级不变式：任何一环失败退化为纯文本回答，不白屏。

### 4.9 云端 Agent H5 卡（render_html 云→端，2026-10-22）

云端 Agent（ADR-017/018 会话域）服务端工具 `render_html` 产出的 H5 卡——与 §4.2 HTML 卡同族渲染、协议来源不同：HTML 是**模型在云端生成**的产物，方向为**云→端**（不经端侧 `capability` 回执链路，也不经 `CloudToolProjectionGate`——该闸只管端→云出端投影）。

**协议**：

```jsonc
// ① 入口：云端 LLM tool_call（server 侧 Koog 工具 render_html，shared Catalog 登记 executor=CLOUD）
{
  "name": "render_html",
  "arguments": {
    "html": "<div style='width:100%…'>…仪表盘…</div>",
    "summary": "NVIDIA 2026 Q3 业绩概览"
  }
}

// ② 下行：HTML 本体不进 LLM 上下文——服务端落 agent_event 第六型载荷 + SSE kind=event 帧
// AgentEventPayload.HtmlCard（wire：type=html_card / source=environment / role=tool）
{ "type": "html_card", "source": "environment", "role": "tool",
  "payload": { "html": "<div…>…</div>", "summary": "NVIDIA 2026 Q3 业绩概览" } }

// ③ 落库（主聊天云端回合）：复用 emitHtmlCardMessage，与 §4.2 同形态（content=HTML、metadata.html_card）
{ "type": "tool_html", "role": "agent", "content": "<div…>…</div>", "metadata": { "html_card": { "…": "…" } } }
```

**回灌**：summary-only——LLM 只见 `render_html → <summary>` 回执，HTML 本体不入上下文（token 保护，同 §4.2 口径）；超限 128_000 字符回 `error: html_too_large` 且 sink 不触发（与端侧 `HtmlCardSanitizer` 128KB 口径对齐）。
**渲染**：Android ✅——`CloudAgentTurnRunner` 新增 `CloudTurnEvent.HtmlCard`；调试页（CloudAgentChatScreen）插 `Role.HTML_CARD` 内存消息，复用主聊天 `HtmlCard` 组件 + `HtmlCardSanitizer` 清洗（Rejected 降级纯文本）；主聊天云端回合复用 `emitHtmlCardMessage` 落 Room（tool_html，走 §4.2 双形态全链路）。iOS 挂账 ios-follow（云端页 html_card 解码容错 + WKWebView 卡渲染）。
**pending 重放**：断线重连/重放路径经 `ServerToolDispatcher.withHtmlCardSink` 派生 run 级 sink 副本，卡片重新出现在事件流。
**测试**：server `ServerRenderHtmlToolTest` / `AgentRunnerTest` / `AgentEventPayloadTest`；Android `CloudAgentTurnRunnerTest` / `CloudAgentChatViewModelTest`。

---

## data_ui · A2UI GenUI 卡（hybrid M2）

- **协议**：`ChatMessageEntity.type = "data_ui"`，content 列存整颗 `MessagePart.A2uiCard` part JSON
  （tool_browser 先例）；云端经 render_ui 工具 action 帧下发，端上 TurnRunner 拦截落库。
  **服务端校验闭环**（2026-10-24，sf-012a0/sf-a2u1c0de 事故修复）：render_ui 负载在服务端
  `RenderUiValidator` 过发射点门控（Action 事件先于工具体落流，校验必须挂在
  `AgentRunner.actionGate` 才能拦住坏负载到端）——非法负载不落 Action、不下行、不进 pending
  重放，工具体回 `error: …` 逐条违规清单经 Observation 回灌，LLM 同轮修正重试；工具描述内嵌
  黄金示例 few-shot（`RENDER_UI_EXAMPLE` 常量被测试钉住自身过校验）。
  **组件白名单 19**（2026-10-24 真机穷举后从 6 扩至 17；2026-10-25 补满官方标准全集）：
  catalog 19 组件全量放行 Text/Image/Icon/Video/AudioPlayer/Row/Column/List/Card/Tabs/Divider/
  Button/CheckBox/TextField/ChoicePicker/Slider/DateTimeInput/BarChart——Video/AudioPlayer
  端侧 Media3 真渲染（共用件 `features/chat/media/ChatMediaPlayers.kt`，卡内不自动播、
  离开组合即释放），url 同 Image 口径限 https:// 公网地址；
  Image.url 字面量必须 https://（禁本地路径/相册 uri，[PRIVACY] 红线），Icon.name 限 59 枚
  预定义枚举且禁 svgPath。**交互组件 value 强制数据绑定**（2026-10-25 行程卡置灰事故）：
  TextField/CheckBox/ChoicePicker/Slider/DateTimeInput 的 value 必须写 {"path":...} 且初值经
  updateDataModel 下发——裸字面量没有数据模型回写路径，端侧 bindUpdater 返回 null，
  控件渲染为 disabled 置灰。**纵向 List 端侧封顶**（同日行程 Tab 崩溃修复）：官方 List 纵向
  走 LazyColumn，聊天卡挂在消息流 LazyColumn item 的无限高约束里必崩，端侧
  `BoundedVerticalA2uiList` 覆写 TypedContent 封顶 `listMaxHeight`（320dp）内部自滚。**假发卡守卫**（同日修复，实测 5/13 用例 LLM 不调 render_ui 就声称
  「卡片已下发 ✅」）：`CardClaimGuard` 挂 cloudAgentStrategy 的 correctFakeCardClaim 节点——
  终结消息命中发卡声明模式 + 当轮无卡工具 Action 过门控 → 追加 user 纠正消息就地重发
  LLM 请求（上限 1 次防死循环），LLM 要么真实调用工具、要么改写回复删除发卡表述；
  Koog `onTextMessage{block}` 是分片过滤器而非门控（空串也算命中），门控条件必须写
  `onCondition`（事故记录见 CloudAgentStrategy KDoc）。
- **parts 形态**：`MessagePart.A2uiCard(partId, surfaceId, messagesJson, fallbackSummary, viewState)`，
  category=DATA；持久轨 p0；viewState 同 part id 覆写（US-7）。
- **回灌**：不回灌 LLM（ChatModelInput data part 分支 emptyList）；messagesJson 只含骨架+local://
  绑定，handle id 对云 opaque（D2）。
- **渲染**：ChatScreen TYPE_A2UI_CARD → A2uiCardHost（androidx.a2ui 官方渲染器，
  polang catalog = material 基础目录 + BarChart，catalogId 见 shared A2uiCardContract，
  为可解引用 URL——GET 返回官网静态 catalog JSON schema
  `docs-site/a2ui/catalog/v1.json`，由 `A2uiCatalogDescriptorSyncTest` 从运行态 catalog
  序列化生成并对账（regen 方式见测试类头注释），deploy-docs-site.sh 发布）；
  HandleResolver 填槽，缺失降级占位卡（US-6）。BarChart 柱高公式 = (barAreaHeight −
  barLabelHeight) × count/maxCount（`barChartBarHeight` 纯函数，2026-10-24 修复：
  满高柱顶满定高 Row 会把柱下月份标签挤出裁掉，单桶图退化为无标签纯色块）；
  标签抽稀 `barChartLabelInterval`（同日修复：>12 桶时按 ceil(count/8) 间隔抽稀，
  选中桶恒显示，抽稀位以同高 Box 占位保持柱底对齐——多月桶标签不再挤成数字串）。
- **交互态**：BarChart 点柱 → selectBucket 事件本地消费（不上行）→ 回写数据模型
  `/selected` 高亮 + viewState `selected=<label>` 持久化（US-7 恢复填槽同路径）。
  **防御纵深**（2026-10-24 sf-012a0 事故）：契约要求蓝图 selected 写 `{"path":"/selected"}`
  数据绑定；LLM 偶发写裸字符串字面量（不订阅数据模型 → 高亮静默失效），HandleResolver
  对 updateComponents 内 BarChart 组件归一化改绑（非空字面量播种为初始选中），
  纯函数 `normalizeBarChartSelectedBinding`，同 data 批量展开先例。
- **端到端例子**：「统计我今年的照片」→ search_media（display=false 取数不出图集卡，
  2026-10-24 门控）命中注册 handle → 云端 render_ui 下发
  BarChart 骨架（buckets 绑定 local://scan/<id>#histogram）→ 端上月桶填槽渲染。
- **调试入口（DEBUG 构建）**：Ready 态卡右上角挂 debug 徽标（`A2UI v0.9 · sf-xxxxxxxx`，
  `A2uiDebugBadge`），点击开蓝图查看器（AlertDialog 逐条列 createSurface/updateComponents/
  updateDataModel pretty JSON + 复制全部；messagesJson 非法时降级显示原文+原因，永不崩聊天页）；
  解析纯函数 `parseBlueprintMessages`（`A2uiBlueprintDebugger.kt`），徽标内容为协议标识符无自然语言，
  [I18N] 豁免，release 包 `BuildConfig.DEBUG` 门控零痕迹。
- **测试**：`A2uiComponentAvailabilityTest`（引擎层：19 组件注册清单 + v0.9 必填属性契约 +
  逐组件蓝图落成 active surface + 数据绑定落位 + 负路径降级）/ `A2uiComponentRenderSmokeTest`
  （渲染层：19 组件同框 Compose 实渲冒烟，Robolectric native graphics）/ `HandleResolverTest` /
  `BarChartSelectedBindingNormalizationTest` / `A2uiBlueprintDebuggerTest` / `PolangA2uiCatalogTest` /
  `A2uiCatalogDescriptorSyncTest`（官网 catalog 描述文件对账 + `REGEN_A2UI_CATALOG=true` 再生成）。
  测试夹具注意：surface actor 生命周期挂在收集协程 scope 上，scope 取消即回收数据模型，
  承载 scope 必须活到断言结束。

---

## data_ui_openui · OpenUI GenUI 卡（openui P2，与 data_ui 线并行）

- **开关**：设置页「GenUI 协议」单选（DataStore `genui_format`，默认 a2ui）→ Chat 页云端回合
  请求头 `X-GenUI-Format` → server 侧互斥注册 render_ui（A2UI）/render_ui_openui（OpenUI），
  同一回合只产一种 GenUI 卡。
- **协议**：`ChatMessageEntity.type = "data_ui_openui"`，content 列存整颗 `MessagePart.OpenUiCard`
  part JSON；云端经 render_ui_openui 工具 action 帧下发（OpenUI Lang 1.0 程序），
  端上 TurnRunner 拦截落库。**流式预览**（P2，2026-10-26，spec《2026-10-26-openui-compose-alignment-design》）：
  服务端 Koog EventHandler `onLLMStreamingFrameReceived` 捕获 ToolCallDelta →
  `OpenUiToolCallStreamTracker` 按 id 归组 + `OpenUiProgramStreamExtractor` 解 program
  前缀（JSON 感知键定位 + 转义断帧容错）→ 节流（≥48 字符且 ≥80ms，首发免时间窗）经
  SSE `kind=openui_delta` 瞬态帧旁路下行（不落 agent_event；旧端忽略未知 kind 后向兼容）；
  端侧合成瞬态预览消息（`OPENUI_STREAM_PREVIEW_ID`）挂列表尾，ChatScreen 分流
  `OpenUiStreamingCard`——每帧前缀新建 session 解析（parser autoClose 流末规则 +
  dropped 哨兵剔除未到位引用，卡片随流生长，引擎零改动），空 resolver 不取数
  （Query 组件先渲 defaults 空态），只读不交互；正式 action 帧落库后预览让位。
- **parts 形态**：`MessagePart.OpenUiCard(partId, program, fallbackSummary, viewState)`，
  category=DATA；持久轨 p0；viewState 同 part id 覆写（US-7 对偶）。
- **回灌**：不回灌 LLM（data part 分支 emptyList 同 data_ui）；program 只含骨架 + Query
  local_handle 绑定，handle id 对云 opaque（D2 对偶）。
- **渲染**：ChatScreen TYPE_OPENUI_CARD → OpenUiCardHost（shared OpenUiSession 批量求值，
  宿主侧 P1 响应式状态（2026-10-26，spec《2026-10-26-openui-compose-alignment-design》，
  对齐 androidx bind 粒度订阅语义）：session.state 经宿主边界转 SnapshotStateMap 逐键增量
  同步（`syncWith`），per-key 读追踪使 action 后仅订阅变更键的节点重组；root 树 data class
  相等门控——绑定全保留的卡在纯交互后零树级重组（真机验证：勾选 CheckBox 时焦点中的
  Input 文本与焦点均不丢）；
  polang OpenUI 组件库二十一组件（M2+M3，2026-10-24）：
  Card/Row/Column/Text/Button/BarChart/Image + Icon/Divider/List/Tabs/CheckBox/ChoicePicker/Slider
  + Table/PieChart/LineChart/Callout/Markdown/Accordion/Switch（M3 新增 prop 类型 PRIMITIVE_ARRAY：
  元素限 Str/Num/Bool 或一层嵌套 Arr，两层嵌套/类型混杂 → 必填丢组件；可嵌套进 OBJECT_ARRAY
  objectFields（LineChart.series[].values），Tabs COMPONENT_LIST 递归同机制），
  schema 见 shared OpenUiLibrary）；Query("local_handle") 经 AppContainer `localHandleLookup` 端侧补全
  （scan domain → `#histogram`/省略 = GalleryHistogramBuilder 月桶；`#photos`（2026-10-24）= ScanPhotoProjection 照片清单 [{id,date}] cap 60
  保持搜索相关度原序），handle 失败/prepare Failed 降级占位卡
  （复用 A2uiCardDegraded，US-6 同口径）；样式令牌只读复用 A2uiStyleMapper，BarChart 视觉
  与 PolangBarChartComponent 逐项对齐（A2uiCardTokens + primary 色 + 选中 45% alpha 规则）；
  Image（图文混排）渲染期经 AppContainer `openUiPhotoResolver` 端侧 id→uri 解析
  （[PRIVACY] uri 不出端），Coil AsyncImage + faceAwareVerticalAlignment(faceFocusY)，
  aspect 渲染期缺省 4:3 clamp [0.5,2.5]，解析不到/加载中 = 同形 surfaceVariant 占位；
  M2 渲染细则：Text.text BINDABLE_STRING（Binding 从 state 读，解析不出丢该 Text 不炸卡）；
  Icon name→Material 图标映射 `OpenUiIconMap`（59 枚枚举，schema/A2UI 副本源/server 白名单/描述四处同源，跨 build 残余漂移风险见 `OpenUiLibrary.iconNames` KDoc）；
  List VERTICAL 限高 320dp 纵向滚动（嵌套滚动规避）、HORIZONTAL 横滑行；Tabs 选中页签 =
  渲染期 remember 本地态不进 state；Slider 用 AppSlider（全 app 滑杆红线）。
  M3 渲染细则：Table 表头+数据行（take 50 护栏、等宽列、超宽横滑、缺 cell 补空串）；
  PieChart/LineChart = Canvas 自绘（扇形/折线+数据点，色板 = colorScheme 角色+透明度阶梯
  BarChart 同族，slices take 12 / series take 4 护栏，LineChart 横轴标签抽稀同 BarChart、
  values 缺位 = null 断点）；Callout = StatusColor 色块 + tone→Icon 映射（INFO=info/
  SUCCESS=check/WARNING=warning/ERROR=error）+ 可选加粗 title；Markdown 复用 AgentMarkdown
  （全 app 唯一 Agent 正文入口）；Accordion 展开集 = 渲染期 remember 本地态（Tabs 同口径）；
  Switch = M3 Switch + token 色（AppSlider 红线只管滑杆），checked BINDABLE_BOOLEAN 与
  CheckBox 同构回写。
  M5（2026-10-25）组件库 21→89 项官方全量补全（细则见
  `docs/08-UI-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md` §5 速查表）。
  align 结构治理（2026-10-30，spec 已随交付清理，git 历史可查）：Row/Column/Card
  补 `align` prop，词汇/语义/缺省严格对齐官方 thesysdev/openui Stack（CSS align-items：
  五值 start|center|end|stretch|baseline，缺省 stretch = 子项铺满交叉轴——Row 子栏
  fillMaxHeight、Column/Card 子项 fillMaxWidth；baseline 首行基线对齐，列向兜底 start）；
  Stack 别名 align 缺省同步修正 start→stretch；主按钮（variant=primary）整行宽；
  stat tile 数字栏居中写 `align="center"`（server 黄金示例范式 + lint 软警告）。
  图文组合固化（2026-10-30，spec 已随交付清理，git 历史可查）：
  ImageText/ImageTextLarge 补 `photo` prop（相册投影 id `photos[i].id`，与 src 二选一、
  photo 优先；渲染走 openUiPhotoResolver 端侧 id→uri + 人脸聚焦 + 点击 OpenPhoto 大图，
  Image 组件同径，[PRIVACY] uri 不出端）——相册图文版式被组合组件结构性锁死，替代
  Row 裸拼 Image+Text 反模式（等宽分栏把缩略图拉成半宽栏）；server 工具描述增图文
  排版食谱段（场景→组件选型，文字环绕 float wrap 不支持并声明替代形态）+ 黄金示例
  + lint 软警告（Row 实参区间内 Image+Text 裸拼，括号配对截取防误伤纯多图 Row）；
  单卡图片上限口径补 Image 调用区间外的命名 photo 键计数。
  通用布局原语批次（2026-10-30，spec 已随交付清理，git 历史可查）：
  设计方向转向通用布局组件（业务组合组件降级为便捷糖）——组件库 89→92 新增
  Grid(children*, columns?, spacing?, align?)（通用 N 列网格，columns clamp [1,4] 缺省 2，
  cell 等宽 weight(1f)，末行缺格补空位；四宫格 = columns 2）/ Box(children*, align?)
  （通用叠放容器，children 按数组序从底到顶叠放，align 九值 contentAlignment 缺省
  topStart = Compose 官方缺省）/ Spacer(size?)（通用留白件，size∈xs|s|m|l|xl →
  Spacing token 缺省 m，Row 内占宽 Column/Card 内占高，不参与 Row 分栏）+ Row 增
  `weights` prop（PRIMITIVE_ARRAY，CSS flex-grow 语义按比例分栏，槽位只数组件子项、
  Divider/Spacer 不占，个数不符回退全 1 等宽；左图右文 = weights=[1,2] 配 align）；
  lint 第四条修订（Row 内 Image+Text 裸拼仅无 weights 时警告）；工具描述「通用布局
  食谱」段 + 黄金示例 `GOLDEN_LAYOUT_PROGRAM`（四宫格照片墙 + 1:2 左图右文）。
- **交互态**：BarChart 点柱 → @Set($selected, label) 本地消费（不上行，§3.4 不变式对偶）→
  session 重求值高亮 + viewState `selected=<label>` 持久化（恢复路径经 initialState 种
  `$selected`）；M2 起 CheckBox 勾选/ChoicePicker 点选/Slider 拖值同机制写各自绑定变量
  （渲染期构造 OpenUiAction Set，Binding 缺失的裸字面量只读不写入；持久化仍只针对
  `$selected`，其余交互 state 重开卡回默认值）；@OpenUrl 走系统浏览器，@ToAssistant M1 仅记日志不路由。
- **端到端例子**：「统计我今年的照片」→ search_media 命中注册 handle → 云端 render_ui_openui
  下发 BarChart 程序（buckets = Query("local_handle", {handle: "local://scan/<id>"}））→
  端上求值填槽渲染。

---

## 5. 规划中卡片

### 5.1 审批三要素卡（📋 spec 定稿待实施）

DESTRUCTIVE 批量删除的聚合确认卡：数量 + 珍贵信号（收藏/老照片）+ 可恢复性三要素；双链路（JS 写通路 + Tier B 批量阈值 3）；形态 = 升级确认对话框（不做 chat 内审批卡）。spec：`docs/superpowers/specs/2026-09-26-approval-three-element-card-design.md`。

### 5.2 ADR-016 parts 模型现状（M1-M4 已合 main；真机验收待补）

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
- **ui-driver 冒烟**（DEV_ONLY，DEBUG 构建）：`/html` → `HtmlCardSmokeSamples`（十一卡含双形态/远程/ALL_IN_ONE）；`/task` → `EngineerTaskSmokeSamples`（五态 + 超屏展开）。⏳ M4 真机冒烟待补（设备离线，清单见 `M4_CHAT_FLATTEN_ACCEPTANCE.md` §3：流式/`/html`/`/chart`/`/task`/长列表滚动 + 重组实测 + Turn 聚合截图基线）。
- **设计稿验收**：health-check（`scripts/ardot-health-check.py`）+ Light 双模（`scripts/ardot-light-verify.py`）+ refs 快照（`scripts/export-ardot-snapshot.py`）。

## 8. 文档索引

| 主题 | 文档 |
|---|---|
| 渲染架构宪法 | `docs/02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md`（+ 实施 spec 2026-09-27-chat-parts-rendering-design） |
| 请求/回复全链路协议 | `docs/03-TECHNICAL-SPECS/CHAT_PROTOCOL_TECH_SPEC.md`（路由→组装→流式→落库→历史双轨；本目录管逐卡实例，链路级归该文档） |
| type 分类法（3 分类 8 值 + role 列） | `docs/superpowers/specs/2026-09-28-chat-type-taxonomy-design.md`（2026-09-28 重构：serialName 改值 + `PartCategory` + role 升格 Room v26） |
| 富内容渲染决策（原 ADR-014 D1~D6） | 已并入 ADR-016（D2 渲染 / D3 沙箱 / D4 iOS / D5 样式 / D6 不做；编号 014 永久留空） |
| HTML/图表/脚本实现 SSOT | `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md` §5/§7/§7.1 |
| Turn 聚合 + 拍平渲染矩阵（视觉 SSOT） | `docs/08-UI-SPECS/screens/chat.yaml` §3.1 turn_aggregation / §3.2 parts_rendering（M4 已落地 ✅，iOS TODO 口径） |
| M3/M4 验收 | AST 正文 + immediate 长度门控 + 拍平规则全枚举 13 例已落地；真机冒烟待补 |
| 双形态 + 任务卡 HTML 化 | `docs/superpowers/specs/2026-09-26-html-card-two-tier-design.md` |
| 任务卡产品线 | `docs/superpowers/specs/2026-09-25-engineer-task-card-design.md` · UserTask 协议 2026-09-26 |
| 设计稿 | Ardot polang-ui-spec（fileId 715061534788814）HtmlCard 页 438:2 · ChatComponents 页 438:328；refs `docs/08-UI-SPECS/screens/refs/ardot/` |
| 样式 tokens | `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md` |
