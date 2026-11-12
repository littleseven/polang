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
// chat_messages 表结构（Room v28 实体，逐列；parts-first——partsJson 为权威列，标量列为派生投影）
{
  "id": "uuid 或 taskId", // 任务卡特例：id = taskId，REPLACE upsert
  "sessionId": "default",
  "type": "…", // 派生投影列（projectChatRow/primaryPartOf：首个 content/tool/data 类 part 的鉴别值，见下）
  "role": "agent", // 消息角色 "user"/"agent"（消息级独立列）
  "content": "…", // 派生投影列（按 type 语义不同，见各卡；供列表气泡/查询可读性）
  "timestamp": 1790000000000,
  "modelUsed": "deepseek-chat", // 可空
  "metadata": { "…": "…" }, // 派生投影列（卡片专属结构化查询负载；实际存储为 JSON 字符串）
  "partsJson": [ "…" ] // parts 数组 JSON（权威列，非空；实际存储为 JSON 字符串）
}
```

`type` part 鉴别值全集（**2026-11-08 终态，4+1 分类 13 值**；演进编年已出清，git 历史可查；命名规约立法全文——单数 snake_case 语义名/集合负载可复数/禁嵌技术名/前缀即分类/新增值过审查 + codec Tombstone 兜底钉——见 ADR-016 D1 末条）：

| 分类 | 值 | 语义 | 回灌 LLM |
|---|---|---|---|
| content（bare） | `text` | markdown 正文（PartState streaming/done） | ✅ TextMessage |
| content（bare） | `image` | 图片（user 图 ref / agent 产物图 ref+saved；媒体红线：字节不出端） | 占位 `[user sent an image]` |
| reasoning（bare） | `reasoning` | 思考链文本（PartState；可折叠渲染，§4.10） | ❌ 剥离 |
| tool_ | `tool_chart` | 图表卡（SVG） | tool-call/result 对 |
| tool_ | `tool_html` | HTML 卡（双形态） | tool-call/result 对 |
| tool_ | `tool_task` | 工程师任务卡 | tool-call/result 对 |
| tool_ | `tool_image_edit` | 编辑结果卡 | 回灌 description |
| tool_ | `tool_browser` | 浏览器直播定格卡 | tool-call/result 对（摘要投影） |
| data_ | `data_optimize_candidates` | AI 优化抽卡候选组 | ❌ 剥离 |
| data_ | `data_genui_program` | GenUI 卡（OpenUI 程序；自 `data_ui_openui` 语义化改名） | ❌ 剥离 |
| source_ | `source_url` | web_search 引用（url/title/sourceId；§4.10） | ❌ 剥离 |
| source_ | `source_document` | 文档引用（mediaType/title/filename） | ❌ 剥离 |
| tombstone（bare） | `tombstone` | 未知/退役 part 的可读占位（§0.4） | ❌ 剥离 |

**预留（规约就位，不预建）**：content 类 `video`/`audio`/`file`（bare，同免前缀规则）。

多 part 消息的 Room `type` 投影 = `primaryPartOf(parts)` 判型锚——**排除清单语义**：reasoning/source/tombstone 永不作投影源，取首个 content/tool/data 类 part 的鉴别值（data 类参与判型：gacha/GenUI 行的唯一 kind；纯 reasoning+source 消息 → `"text"`）；role 由独立列承载。运行时零 legacy 知识：`LegacyChatTypeMigration`/`MessagePartsConverter`/双端回放过滤已随 Room v28 聊天表重置（`chat_sessions`/`chat_messages` DROP 重建，升级链 DDL 保留）全数删除；v5 备份恢复粗映射只活在备份模块（§0.2 末表）；iOS 会话文件 envelope v6 门禁（version<6 整体忽略）。

### 0.4 前向兼容兜底（2026-11-08 终态，Tombstone part）

协议演进下旧版本读新版本数据的兜底机制，三铁律：**不崩溃、不丢消息、可感知（升级提示）**。

- **`tombstone` 鉴别值**：`MessagePart.Tombstone(partId, originalType, summary?)`（sealed 13 子类型之一；category 归 DATA 属**特例**——仅按 category 过滤挡不住它，判型锚/剥离逻辑须显式类型排除；不回灌 LLM，bare 无前缀）。**读时合成，永不出现于生产写路径**（parts-first 写点不构造、流式瞬态不落库；例外 = 备份 v6 恢复对退役卡行落 Tombstone——粗映射表产物）；原始 payload 保留在 `partsJson` 原文，升级新版本后同一行自动恢复为正式卡片（解码幂等）。
- **part 级细粒度解码**（`MessagePartsCodec.decode`）：逐元素独立解码——未知 part 类型 / 已知类型字段损坏 → 原位合成 `Tombstone(originalType)` 占位（块序保住，同消息其余 part 正常渲染）；payload 携带可读摘要字段（`fallbackSummary`/`summary`/`text`/`markdown`）时提升为 `summary`；仅整体结构损坏（非 JSON 数组）才返回 null。前向兼容钉：伪造未来值 `data_future_x` 编解码往返 → `Tombstone(originalType="data_future_x")`（commonTest + iosTest 双钉）。
- **Room 行级兜底**（`decodeParts`）：partsJson 损坏行降级原文 Text part，不丢消息（v28 重置后无 legacy 标量列回退路径）。
- **iOS 同口径**：`ChatHistoryStoreCodec` parts 数组逐元素 `decodePartOrTombstone`；history 文件 envelope v6 门禁（version < 6 整体忽略）后无 legacy 格式读者。
- **渲染**：`ChatListItem.TYPE_TOMBSTONE` → Android `TombstonePartCard` / iOS `tombstoneItem`（图标 + 「内容暂不支持」标题 + summary 或升级提示，五语 `chat_unsupported_part_*` / `chat.unsupported_part_*`——文案 key 随 2026-11-08 自 UnsupportedPartCard/unsupportedItem 改名沿用）；整消息判型（`toUiModel`/`uiTypeOf`，经 `primaryPartOf`）纯伴随载荷消息防御性落 TEXT。回灌：`toModelInput` 空列表。

**渲染路由（parts-first）**：`ChatViewModel.toUiModel()` 全量 `decodeParts()`（partsJson 直读）填充 `ChatMessage.parts` → shared `domain/chat/ChatListFlattener` 拍平为 item 序列（`ChatScreen.kt` LazyColumn 按 `ChatListItem.contentType` 分发；key/三分流规则见 §1.3）。legacy「按 type 取 content/metadata → if/else 链」为 M1-M3 历史形态，已随 v28 出清。

### 0.1 协议一致性现状与 parts 重构（2026-09-27）

**legacy 13-type 协议本身不统一**——这正是 ADR-016 parts 重构的动机（历史盘点，legacy 值域与迁移机器已随 Room v28 聊天表重置出清；下文各卡「落库协议」示例为**终态形态**——type 为 13 值值域内的投影值 + 独立 `role` 列）：

| 不统一点 | 现状 |
|---|---|
| `content` 列语义 | 派生投影列，按 type 共 5 种：markdown 文本 / SVG 串（`tool_chart`）/ HTML 串（`tool_html`）/ 整颗 part JSON（`tool_browser`/`data_genui_program`）/ 空串占位（`tool_task`/`data_optimize_candidates`——投影立法：卡负载全在 part 字段） |
| `metadata` 挂载风格 | 3 种：① 键包裹单对象（`html_card`/`engineer_task`/`claude_agent_state`/optimize 全量）② 字段直挂顶层（性能 6 字段）③ 平铺小对象（`imageUri`+`saved`、`query`+`totalCount`+`isRefinement`） |
| `id` / `timestamp` 语义特例 | tool_task：id=taskId（REPLACE upsert）、timestamp=startedAtMs（锚定提交位置） |
| serde 位置 | org.json 扩展在 androidApp shim（`ChatModelCommonMainShim.kt`），非 commonMain 纯 Kotlin |
| 僵尸类型 | ✅ 已根治（2026-09-28 分类法重构）：~~`command` / `plan_preview` 主聊天流无专属渲染分支（真正消费者在平行浮动面板体系，§6）~~——两值删除并入 `text` |
| type 值域混杂 | ✅ 已根治（2026-11-08 值域终态）：~~13 值扁平混杂，content/tool/data 不分，role 靠 user_/agent_ 前缀~~——4+1 分类 13 值 + `role` 独立列（见 §0 type 全集） |

**parts 重构（✅ 终态，2026-11-08 值域一步到位）**：按 ADR-016 对齐 OpenAI Responses / Vercel parts 协议——一条消息 = 有序 parts 数组（`partsJson` 权威列，kotlinx JSON 鉴别字段 `type` 13 值 + `PartCategory` 五分类载体 getter，不进线格式；`ignoreUnknownKeys` 前向兼容 + `encodeDefaults=false` 紧凑落库）。**parts-first 持久化（D1a）**：写路径统一 `insertPartsMessage`/`insertPartsMessages`/`replacePartsMessage` 直建 parts，`type`/`content`/`metadata` 标量列由 `projectChatRow` 派生投影；读侧 `decodeParts` 直读（损坏行降级原文 Text 兜底）。legacy 转正机器（`LegacyChatTypeMigration`/`MessagePartsConverter`/双端回放过滤）已全删，v5 备份恢复粗映射只活在备份模块（§0.2 末表）。流式三件套（`domain/chat/streaming/`：TurnStreamEvent / ChatStreamTurnAdapter / TurnPartsReducer + `TaskCardOverlay`）：占位契约（`ToolInputStart` 即插占位 part，`draw_chart`/`render_html` 类型化占位）→ 产物原位填充；错误双轨（持久化错误轨 = TaskCard，Chart/HtmlCard/脚本错误瞬态不落库，详见 §4.3）；reasoning 三段式事件族 + 三分区排序立法（reasoning 恒前 / source 恒尾）已接线（§4.10）。正文 `AgentMarkdown`（§3.2）；渲染源 parts + Turn 聚合（§1.3）。落库线格式实例：

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

M1 期 partsJson 曾为标量列的纯函数双写，2026-11-08 值域终态批次反转为 **parts-first**：partsJson 权威、标量列单向派生（`projectChatRow`），流式以 parts 为权威装配（占位/瞬态不落 Room），渲染源 parts（`ChatListFlattener` 拍平，见 §1.3）。各卡「落库协议」示例为终态投影形态（type 13 值值域内 + `role` 列），parts 形态见 §0.2 与各卡「parts 协议」小节。

### 0.2 目标协议（parts 形态）逐类型示例

字段名与缺省省略行为以 `MessagePartsCodec`（kotlinx JSON，`encodeDefaults=false`）为准，round-trip 有单测锁定（`MessagePartsCodecTest`）。**缺省值不写出**：`state="DONE"`、`saved=false`、空集合、`null` 默认字段一律省略，解码回填。`Chart`/`HtmlCard` 携 `state: ToolPartState`（缺省 `OUTPUT_AVAILABLE`，持久化 JSON 不写出；流式占位 `INPUT_STREAMING` 瞬态不落库）。**partId 分轨定稿**（`MessagePart.partId` KDoc）：瞬态轨（流式 turn，reducer 合成）`txt-N`/`call-N`/`rsn-N` 与持久轨（Room partsJson，persist 点直建）`p0`/`p1`… **不交叉**——流式消息不落 Room、落库消息不经 reducer，各生命周期内 LazyColumn key（`messageId:partId`）恒定；流式→落库边界 messageId 变更属基线行为（一 turn 拆多行的持久模型使然）。

```jsonc
// text（一条回复可多段交错）
{
  "type": "text",
  "partId": "p0",
  "markdown": "已定位崩溃根因：…"          // state 仅 STREAMING 时写出（DONE 为缺省）
}

// reasoning（思考链；三分区排序立法：reasoning 恒排主区 part 前，永不作 type 投影源）
{
  "type": "reasoning",
  "partId": "p0",
  "text": "用户想要统计图，先查库再出图…",   // state 同 text（DONE 缺省不写出）
  "state": "STREAMING"
}

// source_url（web_search 引用；连续 source parts 聚合为单 chips item，恒排 parts 尾区）
{
  "type": "source_url",
  "partId": "p8",
  "sourceId": "https://example.com/article",
  "url": "https://example.com/article",
  "title": "Example — 每月摄影统计指南"      // 可选，缺省不写出
}

// source_document（文档引用；无 url，chips 显 filename/title 不可点）
{
  "type": "source_document",
  "partId": "p9",
  "sourceId": "doc-42",
  "mediaType": "application/pdf",
  "title": "2026 相册年度报告",
  "filename": "report.pdf"
}

// tombstone（读时合成占位，永不出现于生产写路径；§0.4）
{
  "type": "tombstone",
  "partId": "p10",
  "originalType": "data_future_x",           // 未知/退役 part 的原始鉴别值
  "summary": "此内容需新版本 App 查看"        // payload 可读摘要提升，可选
}

// tool_chart
{
  "type": "tool_chart",
  "partId": "p1",
  "svg": "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 360 240'>…柱状图元素…</svg>"
}

// tool_html（meta 四字段与历史 metadata.html_card 键一致）
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

// data_media_results（横滑图集卡，2026-11-08 退役：part 类型与线值已物理删除；
// v5 备份恢复按粗映射落 Tombstone 占位，退役前线格式实例见 git 历史）

// image（媒体红线：原生 block，不经 LLM 排版）
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

// tool_image_edit
{
  "type": "tool_image_edit",
  "partId": "p6",
  "ref": "file:///data/…/edit.webp",     // null 时整个键不写出
  "description": "已完成背景替换：海边 · 保留人物主体",
  "suggestions": ["再亮一点", "换成黄昏"]   // 空数组为缺省不写出
}

// data_optimize_candidates（data part：回灌剥离）
{
  "type": "data_optimize_candidates",
  "partId": "p7",
  "group": { "…": "OptimizeCandidateGroup 全量，见 §4.5" }
}

// data_genui_program（OpenUI GenUI 卡，自 data_ui_openui 改名；回灌剥离；详见 OpenUI GenUI 卡节）
{
  "type": "data_genui_program",
  "partId": "p0",
  "program": "root = Card(…)",
  "fallbackSummary": "每月新增照片统计卡"
}
```

**v5 备份恢复粗映射表**（`BackupChatRestoreMapper`，androidApp 备份模块内一次性映射，**运行时零 legacy 知识**；服务对象 = v5 及以下旧备份包的聊天段，v6+ 备份 partsJson 直通不经本表。role 派生规则：`msg.role` 在场直用，缺失按 legacy type 前缀推断——`user_` 前缀 → `user`，其余 → `agent`）：

| v5 type | 终态 parts 产物 |
|---|---|
| `user_text` / `agent_text` / `command` / `plan_preview` | `Text(markdown=content)`（+ role 推断） |
| `user_image` | `Image(ref=content)`（user） |
| `user_image_text` | `Image(metadata.imageUri ?: content)` + `Text`（图上文下） |
| `agent_image` | `Image(ref, saved)` |
| `chart` / `html_card` / `task_card` / `agent_edit_result` / `optimize_candidates` | 对应 `tool_chart` / `tool_html` / `tool_task` / `tool_image_edit` / `data_optimize_candidates` part 物化（负载自 content/metadata 解析） |
| `tool_browser` / `data_ui_openui`（v5 窗口期值） | 整颗 part JSON 直通解码（`data_ui_openui` 鉴别值重写为 `data_genui_program` 后解码，OpenUiCard 卡保真不降级） |
| `data_ui` / `media_results` / `data_media_results`（退役卡） | `Tombstone(originalType=旧值)`（content 机器 JSON 含可读摘要时提升）；纯文本行直通 `Text` |
| 不可识别值 | 整行跳过（不落库） |

> Room MIGRATION_25_26 时代的 legacy 13→8 转正机器（`LegacyChatTypeMigration`）已随 v28 聊天表重置删除；运行时（含 iOS codec、回放、回灌）零 legacy 分支。

### 0.3 OpenAI 协议兼容对照（目标协议硬约束）

ADR-016 对齐的「主流协议」中，OpenAI 是传输层实际采用的家族（远程推理 = OpenAI 兼容 Chat Completions，经 Koog；Responses API 为概念对齐参照）。parts 模型**命名取 Vercel（partId/markdown），语义须与 OpenAI 无损互转**——本节是重构与新增 part 类型的兼容验收清单。

**① 概念对照**：

| PoLang part | OpenAI Responses item | Chat Completions 概念 |
|---|---|---|
| `Text(markdown)` | `message`（content `output_text`/`input_text`） | `assistant`/`user` 消息 content |
| `Reasoning(text)` | `reasoning` item | 无对应（回灌剥离——对齐 Vercel `convertToModelMessages` 丢弃规则） |
| `Chart` / `HtmlCard` / `TaskCard` | `function_call` + `function_call_output` **对**（靠 `call_id` 关联） | `assistant.tool_calls[]` + `tool` 角色消息**对**（靠 `tool_call_id` 关联） |
| `OptimizeCandidates` / `OpenUiCard` | 无对应（UI data，回灌剥离——对齐 Vercel `convertToModelMessages` 丢弃规则） | 不进上下文 |
| `SourceUrl` / `SourceDocument` | 无对应（UI source 引用，回灌剥离） | 不进上下文 |
| `Tombstone` | 无对应（前向兼容占位，回灌剥离） | 不进上下文 |
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
4. **UI 专有 part 不进上下文**（`data_`/`source_` 前缀 part 与 `reasoning`/`tombstone` 回灌剥离，ADR-016 D1 双层分离）；
5. **user 图不外发**（不映射 `input_image`，媒体红线）；
6. **枚举可映射**：新 part 状态枚举须能落到 ② 的 OpenAI/Vercel 语义行，不造第三套生命周期。

---

## 1. 渲染架构总览

### 1.1 渲染技术四类

| 技术 | 机制 | 卡片 |
|---|---|---|
| **原生 Compose** | 直接组合，Coil 加载图 | 用户气泡、文本流、gacha 条、图片/编辑结果卡、确认弹窗（搜索结果横滑卡已随 2026-11-08 退役拆除） |
| **原生 SVG 栅格化** | androidsvg 渲染 ×2.5 位图 | 图表卡 |
| **WebView · LLM HTML** | 零 JS 桥沙箱 + 清洗器 + 动态测高 | HTML 卡（inline/fullpage 双形态） |
| **WebView · L1 端侧模板** | App 内模板 + 状态 JSON 组装，非 LLM 产物 | 工程师任务卡 |

### 1.2 横切机制（多卡片共用）

- **零 JS 桥沙箱**（ADR-016 D3）：WebView 禁文件/DOM Storage、远程 script 剔除、`HtmlCardSanitizer` 清洗（128KB 上限）；`<a>` 外链一律 `HtmlLinkPreviewOverlay` 落地页。
- **测高防抖三件套**（HTML 卡族）：测高 LruCache（**M4 起 key = 拍平 item key `"messageId:partId"`**——ChatScreen 调用点 `cacheKey = item.key`；任务卡 H3 OpenUI 路径原生布局不需要测高，形态域 key `taskcard:<taskId>:exp|col` 仅 H2 WebView 回滚路径使用）+ 滑动冻结滚停应用 + <2px 抖动忽略；布局前虚高拦截。
- **双形态分流**（`HtmlCardDisplay` 纯函数）：display 声明 × 测高 → INLINE/FULLPAGE，终判随消息 metadata 持久化。
- **样式权威**：design-tokens.json → codegen 双端镜像（ADR-016 D5）；HTML/L1 模板 CSS 变量走 MaterialTheme tokens，Light/Dark 随主题。
- **媒体红线**（ADR-008）：相册图/编辑结果走原生消息（IMAGE（agent 语义经 role 守卫）/EDIT_RESULT 体系），不经 LLM 排版、不上传远程。

### 1.3 ADR-016 parts 模型（✅ 终态；M4 真机验收待补）

一条回复 = 有序 parts 数组（`MessagePart` sealed 13 子类型，§0 终态值域表），本目录卡片即 parts 的独立 block 清单。渲染源已切 parts（M4）+ 持久化已反转为 parts-first（2026-11-08 值域终态批次：partsJson 权威、标量列投影、legacy 机器全删，§0.1）。

**M4 渲染源切 parts + Turn 聚合**（spec §7.2/§7.3/§8）：

- **拍平粒度**：shared `domain/chat/ChatListFlattener` 纯函数——每个 MessagePart = LazyColumn 独立 item，**key = `"${messageId}:${partId}"`** + **contentType 复用桶**（`ChatListItem.TYPE_*`：user_message / legacy_message / agent_text / reasoning / source_chips / chart / html_card / task_card / optimize_candidates / openui_card / browser_live / tombstone / tool_placeholder / tool_error / tool_status；连续 source parts 聚合为单 chips item，多段 run 追加序号防 key 重复）。禁止「一条回复一个巨型 item 内部 Column 排段」（性能红线 ADR-016 §4）。
- **拍平三分流/直通清单**：USER 消息整颗单 item（§5 图文同气泡不拆）；claude 气泡 / agent 结果图（IMAGE + role 守卫）/ EDIT_RESULT 整消息直通渲染；流式卡 part 三分流——已填充（OUTPUT_AVAILABLE）的 Chart/HtmlCard **跳过**（产物已落库为独立行，双显禁止）、未完成（INPUT_*）渲染占位 item、失败（OUTPUT_ERROR 瞬态轨）渲染失败 item；非卡片工具进行中合成 `ToolStatusChip` 状态 item（key `messageId:tool_status`）。
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
| 工程师任务卡 | `tool_task` | `EngineerTaskCard` + 原生动作条 | OpenUI 端侧合成 + 原生渲染（H3；回滚闸可退 WebView L1 模板，iOS 双轨仍走 HTML） | `taskcard/*` 438:181/208/264/444:31 | ✅（两分区改版待实现迁移） |
| ~~搜索结果卡~~（已退役 2026-11-08） | `data_media_results`（已退役） | ~~`MediaResultsCarousel`~~（已拆除） | 原生 | — | ❌ 已退役（2026-11-08 横滑图集卡退役：search_media/refine 回执改 TextReply 计数文本，图集展示走技能出卡；v28 重置后运行时零存量行，v5 备份恢复落 Tombstone，详见 §4.4 墓碑） |
| 抽卡候选条 | `data_optimize_candidates` | `GachaCandidateStrip` | 原生 | —（设计稿已随交付清理） | ✅ |
| Agent 图片结果卡 | `image`（+ role=agent） | `ChatMessageItem` isImage 分支 | 原生 | —（跟随会话帧） | ✅ |
| 编辑结果卡 | `tool_image_edit` | isEditResult 分支 | 原生 | — | ✅ |
| 浏览器直播卡 | `tool_browser` | `BrowserLiveCard` + `BrowserFramePreviewOverlay`（M2 全屏接管） | 原生（帧位图：M2 WS binary 推流 / HTTP base64） | —（视觉契约固化 chat.yaml §18） | ✅（Android 首发，iOS 跟随） |
| ~~A2UI GenUI 卡~~（已退役 2026-11-05） | `data_ui`（已退役） | ~~`A2uiCardHost`~~（androidx.a2ui 官方渲染器，polang catalog = material + BarChart；已随退役拆除） | 原生（Compose，云端下发骨架 + 端上 HandleResolver 填槽） | —（GenUI 生成物无固定帧） | ❌ 已退役（2026-11-05 OpenUI 唯一化；v28 重置后运行时零存量行，v5 备份恢复落 Tombstone，详见 data_ui 节） |
| OpenUI GenUI 卡 | `data_genui_program` | `OpenUiCardHost`（自研 Compose 渲染器，103 件组件库） | 原生（Compose，回合原生：终结回复即 OpenUI 程序，端上 OpenUiSession 求值 + local_handle 填槽 + 布局矫正/门禁） | —（GenUI 生成物无固定帧；版式范式帧 `refs/ardot/pattern-*`） | ✅（2026-10-24 接入；2026-11-01 起回合原生；2026-11-05 起唯一 GenUI 线；2026-11-08 值语义化改名 `data_genui_program`） |
| 思考过程折叠块 | `reasoning`（bare；永不作 type 投影源） | `ReasoningPartBlock`（Android）/ iOS `reasoningItem` | 原生 | —（跟随会话帧） | ✅（2026-11-08；默认折叠，生产开关默认关，§4.10） |
| 引用 chips 行 | `source_url` / `source_document`（连续聚合单 item） | `SourceChipsRow`（Android）/ iOS `sourceChipsItem` | 原生 | —（跟随会话帧） | ✅（2026-11-08；web_search 结果 ≤5 条，§4.10） |
| Claude 步骤附加区 | `text`（+ role=agent）+ metadata `claude_agent_state` | `AgentMessageExtras` / `ClaudeAgentSteps` | 原生 | — | ✅ |
| 流式卡片占位/工具状态/失败 item | （流式瞬态，不落 Room） | 占位骨架 / `ToolStatusChip` / 失败态（i18n `chat_card_generate_failed`） | 原生 | — | ✅（M4 拍平三分流，见 §1.3） |
| 前向兼容兜底卡 | `tombstone`（bare；读时合成**永不写库**，例外 = 备份恢复退役卡行） | `TombstonePartCard`（Android `features/chat/components/`）/ iOS `tombstoneItem` | 原生 | —（兜底卡无固定帧） | ✅（2026-11-08 Unsupported 转正改名，见 §0.4） |
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

（SVG 本体不回灌，省 token；OpenAI 线格式目标形态见 §0.3③。）**端到端**：①②（上）→ `emitChartMessage` 经 `insertPartsMessage` 落 `[{"type":"tool_chart","partId":"p0","svg":"…"}]`（parts-first，标量列投影）→ `summary` 作 observation 回传 LLM → `ChartSvgCard` 栅格化。M2 流式轨：`ToolInputStart(draw_chart)` 即插 `Chart(svg="", INPUT_STREAMING)` 占位（骨架/进度），产物落库后 `ToolOutputAvailable` 原位填充。**渲染源 = part**：item contentType `chart`，组件原样复用仅换数据源（`part.svg`）；流式中已填充（OUTPUT_AVAILABLE）的 Chart part 拍平时**跳过**——产物已落库为独立消息行，双显禁止（§1.3 三分流）。**红线**：无交互位图（交互图表走 render_html）；SVG 只由端侧模板生成（prompt 禁 LLM 手写 SVG/markdown 表格画图）。

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
**调试**：DEBUG `/html` → `HtmlCardSmokeSamples`（十二卡）。
**测试**：`HtmlCardDisplayTest` / `HtmlCardSanitizerTest` / `HtmlSandboxGuardTest` / `LocalWebViewLibsTest`。
**本地预制 JS 库（polang-lib 契约，2026-11-08）**：卡内写 `<meta name="polang-lib" content="echarts">` 声明依赖（meta 过 sanitizer 不剔——只剔 meta refresh），渲染期 `LocalWebViewLibs.injectDeclaredLibs` 把声明展开为白名单域 `polang-lib.invalid` 的 `<script src>`（`HtmlCard` 与 `HtmlFullpageViewer` 两个加载点同链），请求经 `shouldInterceptRequest` 从 assets 开流（ECharts 5.6.0 全量 min ≈1MB，预制 `androidApp/src/main/assets/polang-lib/`，版本随包钉死、离线可用）。三道闸：声明名→资产闭集映射（未知名静默忽略）；LLM 手写 `<script src>` 仍被 sanitizer 整段剔除；注入只在渲染期，落库 HTML 保持 meta 契约形态。零 JS 桥防线不变。LLM 使用契约 = server skill `echarts-card`（与 `draw_chart` 的分流标准见该技能）；冒烟 `/html` → `HtmlCardSmokeSamples.ECHARTS`。iOS ✅（2026-11-10 ios-follow：`LocalWebViewLibs.swift` 同语义注入 + `LocalLibSchemeHandler` WKURLSchemeHandler 从 bundle 开流——平台差异：注入 URL 为 `polang-lib://bundle/...` 自定义 scheme（WKWebView 不可拦 https，落库 HTML 保持 meta 契约形态不受影响）；echarts.min.js 双端字节一致预制）。

**失败模式（2026-11-08 真机事故登记）**：程序文本任何位置出现 `file://` / `content://` / `/storage/` 子串（如 LLM 习惯性写 `file:///android_asset/...` 引脚本）命中服务端 `RenderOpenUiValidator` 的 MEDIA_PATH 整程序扫描（含字符串字面量内部），且该违规在 `ensureValidProgram` 的 Markdown 兜底包装后仍存活 → 整回复硬降级为「回复生成失败，请重试」。观测：`picme-agent` WARN 日志「终结兜底硬降级」（含 wrapError 违规清单 + 原文预览，2026-11-08 起）；回归：`OpenUiTurnNativeTest` 四例（meta 契约放行 / file/content uri 硬降级 / CDN 放行由端侧 sanitizer 剔除）。技能侧已在 `echarts-card` / `html-card` 契约中显式列入 URL 红线。
**真实数据通路（2026-11-08 补录）**：云端回合画统计图前必须经 `run_gallery_script` 工具取真实数据（shared `CloudAgentToolCatalog` 第 4 件 LOCAL 工具）——此前云端目录只有 summary/search/refine 三件，月度明细无取数通路，LLM 只能臆造数值（同日事故：月度数组合计与 search_media 总数对不上）。端侧落点 `core/agenttools/CloudAgentScriptTools`（无头 JsRuntime：handler = `registerGalleryHandlers` 只读 12 件与 chat 链路同源，**不注册** capability.dispatch 写通路；evalMutex 串行，超时 10s），return 值 JSON 化后照旧过投影闸 + 32K 截断；server 零改动（`DeviceToolService` 目录驱动自动生成 Koog 工具），只需重打包重启。步骤时间线标签 `cloud_agent_step_run_script` 五语已同步。iOS ✅（2026-11-10 ios-follow：`CloudAgentScriptRunner.swift` Swift 侧对等——无头 JsCoreEngine runtime + 只读 12 handler + chart bootstrap + 注册制产物信封；已知差异：JSCore 无中断机制，timeoutMs 不生效（协作式脚本不受影响，登记技术债））。

**Chart.echarts 固定模板 + 注册制产物信封（2026-11-09，「本地固定 JS 函数」经济性路线）**：画交互图表的唯一方式是脚本中调用 `Chart.echarts({title?, x, series, unit?, tip?, theme?, style?})`（`assets/js/chart_bootstrap.js` 固定模板：polang-lib 预制 ECharts 声明、confine 防遮挡、读数行联动、主题配色全内置——5 套预置主题 fresh/ocean/sunset/violet/forest + `style` 字段级覆盖 + `series[].color` 最高优先，2026-11-09 主题化；兜底文案五语本地化——读数行默认提示/默认系列名/读数分隔符按应用语言（`AppLanguage`→BCP47）出 CHART_STRINGS 表，端侧每次脚本执行前注入 `__polangLang`、每次发卡解析，切语言即时生效）——LLM 零手写 HTML/ECharts，彻底消灭手写 DSL 嵌套事故的校验打回轮。**注册制产物（2026-11-09 真机事故修复）**：`Chart.echarts` 调用即把 `{html, summary}` 推入 `globalThis.__polangArtifacts`，`CloudAgentScriptTools` 每次执行前清空、执行后统一 drain 包装为信封（`wrapHtmlArtifact`，`__artifact__` 标记 + `cards` 数组，一稿多卡全收），与脚本 return 形状彻底解耦——LLM 把图卡结果包进 `{chart: {...}, total, ...}` 这类信封返回也不再丢卡/不再把完整 HTML 上行（原事故：信封形状识别落空，4 万+ token 的 HTML 整个回传 LLM 且卡片没落库）；原 return 经递归剥除 `html` 键（及值为含 html 对象的 `chart` 键）后作为 `stats` 随信封上行，LLM 拿 summary+stats 写分析。`CloudAgentTurnRunner` 剥离信封后逐卡发 `CloudTurnEvent.HtmlCard` 复用 html_card 落库管线上屏，上行 postToolResult 只带 `{"html_card":"emitted","summary":...,"stats":...}`——HTML 不出端、不进 LLM 上下文。SVG 系 Chart API（bar/line/pie/timeline）云端无渲染通路，返回 error 引导改用 Chart.echarts。配套硬约束（skill + DESC 双写）：图卡已自动上屏，LLM 最终回复不得再含 Html 组件/重复图卡，只写 Markdown 分析（真机事故：双重发卡，手写那张 ECharts 空白）。同日修复 `QueryGalleryMediaUseCase.timeline` 截断缺陷：`maxBuckets` 60→240 且超限保留**最近** N 桶（原升序取最早 N 桶，老相册 2020 年后数据整体被砍——「60 个月合计 896」对全库 8152 的严重低估即此）。token 实证：同需求回合 promptTokens 由约 11 万降至 40117（-63%）。
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

**渲染组件**：`EngineerTaskCard`（两分区单卡：信息区 + 卡内原生动作区 `EngineerTaskActionZone`，外层 r16；整卡统一 **cell 底**（Light 纯白 / Dark #1A1A1A）——2026-10-11 Light 连体定稿（design taskcard/light-\*）：上下两区同色、分区仅靠动作区顶 hairline（outlineVariant），白卡从微信灰列表底自然浮起，无描边）+ `EngineerTaskFallbackCard`（`components/EngineerTaskCard.kt`）。**信息区机制（2026-11-02 H3 OpenUI 化，spec `2026-11-02-engineer-taskcard-openui`）**：`EngineerTaskProgram`（shared 纯函数组装器，段落逻辑与 H2 HTML 模板逐条对偶）把 `EngineerTaskState`+`EngineerTaskTexts` 合成为 OpenUI 程序文本 → `OpenUiSession` 全管线（parse→eval）→ `EngineerTaskOpenUiInfoZone` 解包根 Card 原生渲染 children（无 WebView/无 local_handle/无 viewState，重合成保留旧根零闪烁）；双形态复刻（折叠/展开 = 程序重合成；展开 `onSizeChanged` 原生量高超一屏升格 FULLPAGE 粘滞，内联回落折叠、点击开 `EngineerTaskFullpageDialog` 全屏原生查看器——测高缓存/防抖机制此路径不需要）；`escapeProgramString` 程序字面量转义（唯一注入面）；500ms 合帧节流（`EngineerTaskThrottle`）与阶段串本地化（`parseStage`/`stageMaps`）不变。**回滚闸**：`USE_OPENUI_TASKCARD_RENDER = false` 回退 H2 路径（`EngineerTaskHtml` L1 模板经 `HtmlCard` 渲染——iOS 双轨期 WKWebView 仍消费该模板，两路径并存）；iOS SwiftUI OpenUI 渲染器落地后统一删除。已知偏差（继承 H2 三条 + 新增两条）：进度 % 启发式封顶 80、时间线无 ○ 待办行、diff 行无 +a/−b；事件列暂非等宽字体（Text CAPTION，CodeBlock 皮肤不合）、时间线标记列自然宽（✓/● 字宽差数 px）。停止 = resolve ABANDONED + 取消 SSE。任务中心列表项保持原生紧凑形态（`taskcenter/`）。
**parts 协议（✅ M1/M2 已落地）**：`MessagePart.TaskCard`——

| 字段 | 类型 | 含义 |
|---|---|---|
| `partId` | String | 块级 id |
| `toolCallId` | String | 持久化行恒 = taskId；M2 流式瞬态用 `call-N` 合成 id（不落库） |
| `state` | ToolPartState | 五态投影（`toToolPartState()`）：RUNNING→`INPUT_AVAILABLE`；AWAITING_CONTINUE/AWAITING_DELIVER→`APPROVAL_REQUESTED`；COMPLETED→`OUTPUT_AVAILABLE`；FAILED→`OUTPUT_ERROR` |
| `task` | EngineerTaskState | 完整状态快照（16 字段，键名与 legacy metadata.engineer_task 一致） |

**M2 overlay**：`TaskCardOverlay.overlayLiveTaskState`——SSE live 态对消息内首个 TaskCard part **同 id 原位覆写**（legacy `engineerTask` 渲染字段自 part 投影，同源防漂移；500ms 节流在 displayMessages 管线，首帧直通）。**错误双轨的持久化轨**：FAILED 的 `errorSummary` 随 TaskCard part 落库、回灌 `isError=true`（Chart/HtmlCard/脚本错误为瞬态轨不落库，spec §5.3）。**渲染源 = part**：item contentType `task_card`，`EngineerTaskCard` 组件与 H2 HTML 化路线不变，**状态源 = `ToolPartState`**（part.state 七态投影，五态经 `toToolPartState()` 映射；live 态仍走 overlay 同 id 覆写）——spec §7.4 复用清单落地。

**测试**：`EngineerTaskProgramTest`（程序 golden + prepare 零错误自证 + 转义敌对用例）/ `EngineerTaskHtmlTest`（H2 回滚路径）/ `EngineerTaskReducerTest` / `EngineerTaskStateSerdeTest` / `ChatViewModelEngineerTaskTest`；冒烟 `/task`（`EngineerTaskSmokeSamples`）。

**回灌** → `ToolCall(taskId, "engineer_task", argsSummary = sourceText)` + `ToolResult(taskId, "engineer_task", errorSummary ?: resultSummary ?: "Task status: <status>", isError = state==OUTPUT_ERROR)`；`toHistoryPair` 逐字：

```
("tool_call", "engineer_task(修复相册扫描时的崩溃)")
("tool_result", "engineer_task → 已完成根因定位与修复，编译验证中断")
// FAILED：("tool_result", "engineer_task → SSE connection lost (failed)")——错误进上下文，模型可自我修正
```

**端到端**：工程师模式提交 → `task_<uuid>` 行 upsert（RUNNING）→ SSE 结构性事件经 `EngineerTaskReducer` 五态迁移逐次 upsert（parts 经 `persistEngineerTask` 直建 TaskCard part，标量列投影派生）→ 审批 `resolved()` terminal 化（resolution 粘滞）→ `EngineerTaskCard` 渲染。partsJson 实例见 §0.2 `tool_task`。

### 4.4 ~~相册搜索结果卡 `MediaResultsCarousel`~~（❌ 已退役 2026-11-08）

横滑图集卡已整体退役拆除，本节保留墓碑备查（退役前协议/UI/测试全文见 git 历史）：

- **删除面**：`MessagePart.MediaResults` / `MediaResultsUi` / Room 线值 `data_media_results`（活跃值域剔除）/ `MediaResultsCarousel` 组件 / `feedbackState` 👍/👎 反馈链路（`record_feedback`/`more_like_this`/`exclude_constraint` 三 @Tool + `media_feedback` 表写路径；读路径存量分数排序保留）/ 回合末 `settleScriptMediaIds` 脚本 ids 补卡机制 / 云端回合 `mediaResultsListener` 透出 + `display="true"` 意图门控。
- **替代形态**：`search_media`/`refine_media_search` 回执 = TextReply 纯计数文案（「找到 N 张「q」的照片」），无卡落库；图集浏览展示一律走技能出卡（photo-browse 等，`render_html`/OpenUI 程序）。
- **存量行处置（2026-11-08 值域终态收口）**：`media_results`/`data_media_results` 两值已随值域终态批次彻底出清——Room v28 聊天表重置后运行时零存量行（回放/回灌零退役分支）；v5 备份恢复按粗映射落 `Tombstone(originalType)` 可读占位（§0.2 末表）。

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
**parts 协议（✅ M1 已落地）**：`MessagePart.Image(partId, ref, saved=false)` 与 `MessagePart.EditResult(partId, ref?, description, suggestions?, saved?)`——Image 的 `ref`：user 图 = 内部存储路径（saved 恒缺省）、agent 图 = 结果图 URI（保存后 `"saved": true` 写出）；EditResult 的 `description` = content 列（**回灌唯一通道**），`ref` 为 null 时整键不写出（UI 落 legacy 兜底）。partsJson 实例见 §0.2 `image` / `tool_image_edit`。**回灌**（[PRIVACY] 媒体红线：图片本体不进上下文）——Image → 英文中性占位：user `[user sent an image]` / agent `[assistant generated an image]`（`toHistoryPair` 落 `("user"|"agent", 占位)`——role 升格后标注自消息级 role 列，保多轮回合结构）；EditResult → `TextMessage(ASSISTANT, description)`（只回灌文字说明，防多轮编辑上下文断裂；结果图/ref/suggestions 均不进）。**端到端**（EditResult）：`edit_image` tool_call（`*_delta` 相对调整带步进截断：美颜 ±10/亮度 ±15 等，绝对值不限幅）→ `ChatEditProcessor` Recipe 渲染 → `tool_image_edit` 行（图 + 说明 + suggestions）→ 气泡内结果图 + 建议条；agent Image 三来源：`adjust_image` / AI 优化 fallback / 抽卡「就用这张」行改写（见 §4.5）。**M4**：agent 结果图（IMAGE + role 守卫）/ EDIT_RESULT 拍平走**整消息直通**（contentType `legacy_message`，整颗渲染不拆 part，见 §1.3 直通清单；数据源 = parts，枚举合并后 agent 语义由 `role != USER` 守卫；user 图走 USER 整颗单 item，见 §3.1）。

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

**回灌**（`toModelInput`，toolCallId = `"<messageId>:<partId>"` 命名空间锚）→ `ToolCall(…, "browser_session", argsSummary="")` + `ToolResult(…, resultSummary ?: 失败原因 ?: 会话概览, isError = state==OUTPUT_ERROR)`——只回灌文本摘要，帧与动作流水不进上下文（token 保护；§0.3 硬约束①②④过线）。**端到端**：`browser_open` tool_call → `BrowserSessionCapability` → 网关 `/v1/browser/open`（wantFrame=true）→ xuxing bridge → 占位 part（`INPUT_STREAMING`）经 `BrowserLiveOverlay` 逐动作原位覆写 → `browser_close`/超时定格 → `emitBrowserCardMessage` 经 `insertPartsMessage` 直建 BrowserLive part 落 `tool_browser` 行（content 投影 = 整颗 part JSON 直通先例）→ 冷启动从 Room 恢复定格卡；降级不变式：任何一环失败退化为纯文本回答，不白屏。

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
**渲染**：Android ✅——`CloudAgentTurnRunner` 新增 `CloudTurnEvent.HtmlCard`；调试页（CloudAgentChatScreen）插 `Role.HTML_CARD` 内存消息，复用主聊天 `HtmlCard` 组件 + `HtmlCardSanitizer` 清洗（Rejected 降级纯文本）；主聊天云端回合复用 `emitHtmlCardMessage` 落 Room（tool_html，走 §4.2 双形态全链路）。iOS ✅ 调试页（2026-11-10 ios-follow：`CloudAgentChatStore` html_card 事件解码 + 信封出卡，`CloudAgentChatView` 复用 `HtmlCardView` 双形态 + FULLPAGE/外链浮层；sanitize 走 shared `HtmlCardSanitizer` 同源）。主聊天云端回合（M2.5 runCloudTurn）iOS 未接线——挂账保留，含 OpenUI 卡喂卡路径。
**pending 重放**：断线重连/重放路径经 `ServerToolDispatcher.withHtmlCardSink` 派生 run 级 sink 副本，卡片重新出现在事件流。
**测试**：server `ServerRenderHtmlToolTest` / `AgentRunnerTest` / `AgentEventPayloadTest`；Android `CloudAgentTurnRunnerTest` / `CloudAgentChatViewModelTest`。

### 4.10 思考过程折叠块 + 引用 chips 行（reasoning / source_url · source_document，2026-11-08 值域终态新增）

对齐 OpenAI `reasoning` item / Vercel `reasoning`·`source-url` part 的两个新渲染件，双端对等落地：

**`reasoning`（思考链折叠块）**
- **协议**：`MessagePart.Reasoning(partId, text, state: PartState)`（bare 值；§0.2 线格式示例）。三分区排序立法（`TurnPartsReducer`）：reasoning 恒插主区首 part 前（到达序无关）、source 恒尾、主区内部到达序。
- **流式**：`TurnStreamEvent` reasoning 三段式事件族（`rsn-N` 合成 id，状态机语义同 text 三件套）+ `ChatStreamEvent.ReasoningSnapshot`（本轮累计快照）；Koog 接线——`KoogChatAgent.onLLMStreamingFrameReceived` 消费 `StreamFrame.ReasoningDelta` 逐帧累积发快照（Koog 1.3.0 有 ReasoningDelta/ReasoningComplete 帧，javap 实证）；**生产开关默认关**（RemoteModelFactory thinking=disabled + Thinking capability 不声明，上游启用 extended thinking 后整链自动工作零代码改动）。
- **持久化**：sendMessage 成功路径收尾统一带 `reasoning parts + Text + source parts`（流式瞬态收编持久化，reasoning 落库前置 DONE；取消/错误行不附）；回灌剥离。
- **渲染**：Android `ReasoningPartBlock`（默认折叠 `rememberSaveable`，chevron + `chat_reasoning_title`，展开 13sp onSurfaceVariant + animateContentSize 150ms）/ iOS `reasoningItem`（`@State` 折叠，13pt secondaryLabel）；item contentType `reasoning`。

**`source_url` / `source_document`（引用 chips 行）**
- **协议**：`SourceUrl(partId, sourceId, url, title?)` / `SourceDocument(partId, sourceId, mediaType, title, filename?)`；回灌剥离。
- **生产者**：`web_search` 工具结果由 `WebSearchCapability.extractWebSearchSources` 抽取前 ≤5 条 url 非空结果（只读 title/url，snippet/图链不进 chip）→ `ChatToolService.webSearchSources` 通道 → `TurnStreamEvent.SourcesAvailable` 直喂 reducer；正文 markdown 图链行为不变（图=配图，chip=来源列表，职责分工）。
- **渲染**：连续 source parts 聚合为单 chips item（多段 run 追加序号防 key 重复）——Android `SourceChipsRow`（FlowRow 序号 11sp semibold + 域名 12sp、胶囊 surfaceVariant 16dp 圆角、http/https scheme 白名单、URL 点击 `Intent.ACTION_VIEW` 系统浏览器；SourceDocument 显 filename/title 不可点）/ iOS `sourceChipsItem`（FlowLayout + `openURL`）；item contentType `source_chips`。
- **测试**：`TurnPartsReducerTest`（排序立法/尾区聚合/占位保序）+ `ChatStreamTurnAdapterTest`（reasoning 差分/双闭合）+ `WebSearchCapabilityTest`（≤5 截断/降级）+ `SourceChipsRowTest`（聚合/scheme 白名单/回退）+ `IosChatHistoryStoreCodecTest`（判型锚跳过 reasoning）。

---

## data_ui · A2UI GenUI 卡（hybrid M2）（已退役 2026-11-05）

> **退役标注（2026-11-05，OpenUI 唯一化）**：A2UI 对照线全量退役——server 发卡工具 render_ui 与校验闭环、端侧渲染线 `features/chat/a2ui/`、shared `MessagePart.A2uiCard` 与 `data_ui` type 值、androidx.a2ui 依赖均已删除；存量 `data_ui` 消息经 codec/converter 墓碑兜底为可读 Text（不丢消息）。决策与实施记录见 spec 2026-11-05-openui-sole-genui-protocol；外部 androidx.a2ui 成熟后可重评估。本节正文保留作历史登记。

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
  polang catalog = material 基础目录 + BarChart，catalogId 见 shared A2uiCardContract
  （官网静态 catalog JSON schema 与对账测试已随退役一并删除）；
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
- **测试**（以下均已随 2026-11-05 退役删除，仅作历史登记）：`A2uiComponentAvailabilityTest`（引擎层：19 组件注册清单 + v0.9 必填属性契约 +
  逐组件蓝图落成 active surface + 数据绑定落位 + 负路径降级）/ `A2uiComponentRenderSmokeTest`
  （渲染层：19 组件同框 Compose 实渲冒烟，Robolectric native graphics）/ `HandleResolverTest` /
  `BarChartSelectedBindingNormalizationTest` / `A2uiBlueprintDebuggerTest` / `PolangA2uiCatalogTest` /
  `A2uiCatalogDescriptorSyncTest`（官网 catalog 描述文件对账 + `REGEN_A2UI_CATALOG=true` 再生成——已随退役删除，勿照单 regen）。
  测试夹具注意：surface actor 生命周期挂在收集协程 scope 上，scope 取消即回收数据模型，
  承载 scope 必须活到断言结束。

---

## data_genui_program · OpenUI GenUI 卡（2026-11-01 起为回合原生 turn-native 形态；2026-11-05 起为唯一 GenUI 线；2026-11-08 值语义化改名自 `data_ui_openui`）

- **分流（已退役 2026-11-06，OpenUI 唯一化）**：原设置页「GenUI 协议」单选（DataStore `genui_format`，
  默认 openui）→ Chat 页云端回合请求头 `X-GenUI-Format` → server 侧分流（openui = 回合原生 /
  缺头·未知值 = A2UI 工具发卡回退闸）已整体拆除——server 0.9.20 起 /agent 路由面恒走
  turn-native（旧头被忽略不报错，后向兼容旧客户端），render_ui/render_ui_openui/render_html
  发卡工具全退役（不注册任何 GenUI 发卡工具，LLM 的整个终结回复就是 OpenUI 程序）；端侧设置项
  同步拆除，DataStore 存量 `genui_format` 值自然失效不做迁移。同一回合只走一条线（现恒为
  turn-native）。
- **协议**：`ChatMessageEntity.type = "data_genui_program"`（投影），content 投影 = 整颗 `MessagePart.OpenUiCard`

> 端侧合成消费者（2026-11-02）：工程师任务卡信息区（`tool_task` part）同走 OpenUI 运行时渲染（`EngineerTaskProgram` 端侧确定性合成程序文本 → `OpenUiSession` → 解包根 Card 原生渲染）——openui 渲染器的第二个消费者，程序不出端、非 turn-native 产物；详见「工程师任务卡」节。
  part JSON。**来源（2026-11-01 起）= 云端回合终结 Message 事件**——响应即程序：assistant
  content 直接是 OpenUI Lang 1.0 程序文本，端上 `CloudAgentTurnRunner` message 分支经
  `OpenUiProgramSummary.looksLikeProgram` 嗅探（root 行首声明 / Card 调用，字符串字面量
  剥离后判定）→ 程序形态落 OpenUiCard 事件入库，非程序形态（理论上已被 server 双保险
  兜住）按纯文本 AssistantText 降级上屏；`extractFallbackSummary` 提取降级摘要
  （`// summary:` 首行注释 > 首个 Markdown/Text 字符串字面量，截 80 字）。**流式预览（turn-native）**：服务端 `OpenUiContentStreamTracker` 跟踪 content
  TextDelta 纯文本累计（响应即程序，无需 JSON 转义剥离——2026-10-26 P2 的
  ToolCallDelta 路径已随发卡工具退役物理删除，2026-11-06，server 0.9.20），节流同口径
  （≥48 字符增量且 ≥80ms，首发免时间窗）经 SSE `kind=openui_delta` 瞬态帧旁路下行
  （不落 agent_event；旧端忽略未知 kind 后向兼容）；EventHandler `onLLMStreamingStarting`
  调 `onRoundStart` 轮次重置——回合内多轮 LLM 输出（工具调用后的中间轮 → 终结整树）各自
  从空累计，端上覆盖式替换预览；端侧合成瞬态预览消息（`OPENUI_STREAM_PREVIEW_ID`）挂
  列表尾，ChatScreen 分流 `OpenUiStreamingCard`——每帧前缀新建 session 解析（parser
  autoClose 流末规则 + dropped 哨兵剔除未到位引用，卡片随流生长，引擎零改动），空
  resolver 不取数（Query 组件先渲 defaults 空态），只读不交互；终结 Message 落库后预览让位。
  **服务端双保险**（端上永远收到合法树）：① `ProgramGuard` 策略层守卫——终结文本经
  `RenderOpenUiValidator.validateProgramToError`（结构校验 + 布局门禁，turn-native 口径
  文案「重新输出完整程序」）判定非法 → cloudAgentStrategy `correctInvalidProgram` 节点
  携带逐条错误清单回环重发（上限 MAX_CORRECTIONS=1 防死循环）；② 上限用尽放行后由
  终结落流前内联调用的 `OpenUiTurnNative.ensureValidProgram` 兜底——合法
  normalize 后原样放行 / 非法包成 `root = Markdown("…")` 程序（复验再失败硬编码错误卡）。
  turn-native 线假发卡守卫（CardClaimGuard）已随 2026-11-06 唯一化与发卡工具一并物理删除
  （该线无发卡工具，纠正文案指向不存在的工具会误导——此前经 `programGuard == null &&` 门控旁路）。
- **parts 形态**：`MessagePart.OpenUiCard(partId, program, fallbackSummary, viewState, langVersion)`，
  category=DATA；持久轨 p0；viewState 同 part id 覆写（US-7 对偶）；`langVersion`（2026-11-11）
  = 服务端 OpenUI 语言协议版本（server done 帧 `lang_version` 真引用 shared
  `OpenUiContract.LANG_VERSION`），旧 server 无字段/旧 partsJson 解码均落 null（默认值双向兼容）。
- **协议版本协商 + 未知组件可见降级（2026-11-11）**：服务端先部署新组件而 App 未发包时的
  前向兼容双保险（chat parts 层 Tombstone 口径在 OpenUI 程序层的对偶）——① done 帧
  `lang_version`（server `AgentRoutes` 真引用 shared 常量）→ 端侧 `CloudFrame.Done.langVersion`
  （缺省容忍）→ 落 OpenUiCard part；卡渲染时 `langVersion > OpenUiContract.LANG_VERSION` →
  `OpenUiCardHost` 卡顶部细提示行（五语 `openui_lang_newer_hint`）引导升级；② shared
  `OpenUiEval` 遇未知组件名不再记 `unknown-component` 后静默丢弃，产出
  `OpenUiValue.UnknownComponent(type, statementId)` 占位节点（错误照记）——COMPONENT_LIST
  槽位（含 Tabs.tabs.children 递归校验）同组件口径放行；Android 渲染器（Node/Content/Form/
  CardBlock 四文件的组件槽递归点）渲染可见占位块（五语 `openui_unknown_component_hint`，
  contentDescription 带原始组件名），server `OpenUiHtmlRenderer` 组件树预览落带组件名虚线
  占位框；根本身未知仍走致命 no-root → 可见降级卡（不静默）。bump 规则见
  `OpenUiContract.LANG_VERSION` KDoc（OpenUiLibrary 组件集扩容时 +1）。
- **回灌**：不回灌 LLM（data part 分支 emptyList）；program 只含骨架 + Query
  local_handle 绑定，handle id 对云 opaque（D2 对偶）。
- **渲染**：ChatScreen TYPE_OPENUI_CARD → OpenUiCardHost（shared OpenUiSession 批量求值，
  宿主侧 P1 响应式状态（2026-10-26，spec《2026-10-26-openui-compose-alignment-design》，
  对齐 androidx bind 粒度订阅语义）：session.state 经宿主边界转 SnapshotStateMap 逐键增量
  同步（`syncWith`），per-key 读追踪使 action 后仅订阅变更键的节点重组；root 树 data class
  相等门控——绑定全保留的卡在纯交互后零树级重组（真机验证：勾选 CheckBox 时焦点中的
  Input 文本与焦点均不丢）；
  polang OpenUI 组件库（SSOT = shared `OpenUiLibrary.componentNames`，server 白名单
  真引用同一清单，**现 103 项** = 官方全量适配 + 本仓扩展件；2026-10-24 M2+M3 时为
  二十一组件，早期核心：
  Card/Row/Column/Text/Button/BarChart/Image + Icon/Divider/List/Tabs/CheckBox/ChoicePicker/Slider
  + Table/PieChart/LineChart/Callout/Markdown/Accordion/Switch（M3 新增 prop 类型 PRIMITIVE_ARRAY：
  元素限 Str/Num/Bool 或一层嵌套 Arr，两层嵌套/类型混杂 → 必填丢组件；可嵌套进 OBJECT_ARRAY
  objectFields（LineChart.series[].values），Tabs COMPONENT_LIST 递归同机制），
  schema 见 shared OpenUiLibrary）；Query("local_handle") 经 AppContainer `localHandleLookup` 端侧补全
  （scan domain → `#histogram`/省略 = GalleryHistogramBuilder 月桶；`#photos`（2026-10-24）= ScanPhotoProjection 照片清单 [{id,date}] cap 60
  保持搜索相关度原序；`#videos`（2026-11-04）= ScanPhotoProjection.toVideoRefs 视频清单同形），handle 失败/prepare Failed 降级占位卡
  （`OpenUiCardDegraded`，US-6 同口径）；样式令牌经 `OpenUiStyleMapper`（2026-11-05 自 A2uiStyleMapper
  迁移更名，配色枚举 `OpenUiColorRole` 词表 SSOT 在 shared `OpenUiLibrary.colorRoles`），BarChart 视觉
  与 PolangBarChartComponent 逐项对齐（A2uiCardTokens + primary 色 + 选中 45% alpha 规则——
  `A2uiCardTokens` 为 token 生成物命名债，更名须走 token SSOT + codegen 管线）；
  Image（图文混排）渲染期经 AppContainer `openUiPhotoResolver` 端侧 id→uri 解析
  （[PRIVACY] uri 不出端），Coil AsyncImage + faceAwareVerticalAlignment(faceFocusY)，
  aspect 渲染期缺省 4:3 clamp [0.5,2.5]，解析不到/加载中 = 同形 surfaceVariant 占位；
  M2 渲染细则：Text.text BINDABLE_STRING（Binding 从 state 读，解析不出丢该 Text 不炸卡）；
  Icon name→Material 图标映射 `OpenUiIconMap`（59 枚枚举，shared schema/server 白名单/prompt 清单三处同源，跨 build 残余漂移风险见 `OpenUiLibrary.iconNames` KDoc）；
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
  经典组合版式批次（2026-10-31，spec 已随交付清理，git 历史可查）：
  原语之上固化经典构图（用户定调「布局丑问题依旧没有解决，应该做一些经典的布局
  组件」）——组件库 92→95 新增 StatRow(items*=[{value*, label?}])（经典统计行 =
  stat tile 组件化：等分 + DISPLAY 数字 + CAPTION 弱色标签居中，lint 第三条根治）/
  StatItem（元素形状保底）/ Banner(title*, subtitle?, photo?, src?, aspect?)（通栏题图
  横幅：photo 相册投影优先 / src https，底部渐变蒙层 + 左下白字标题，aspect 缺省
  16:9 clamp [0.5, 2.5]，无图 surfaceVariant 纯色底）；ListItem 实渲染化（经典
  ListTile：前件 photo 48dp 图 > image https 图 > icon 徽章三选一 + 标题/副题 +
  trailing 弱色小字 + actionLabel 胶囊，action 整行可点；`List([ListItem...])` 经典
  列表成形，新增 photo/icon/trailing props 向后兼容）；VisualCardBlock/VisualCardItem
  +bgPhoto（题图卡相册投影，与 bgImageSrc 二选一、bgPhoto 优先）；server 图片计数
  口径扩 bgPhoto 键 + 对象字段冒号形态（`{bgPhoto: …}` 同 `photo =` 计位图）；
  工具描述增经典组合版式食谱五则 + 黄金示例 `GOLDEN_CLASSIC_PROGRAM`（Banner +
  StatRow + ListItem 相册卡骨架，companion const 测试同源钉住）。
  图片帧比例治理批次（2026-10-31，纯渲染层，schema/server 零改动）：
  「布局时图片内容考虑图片比例及缩放」落地——帧宽高比决策链全组件统一：显式 aspect
  prop > 图片真实比例（相册元数据既有 + 网络图 coil intrinsic 新增采集，加载成功单帧
  过渡，宽 sanity 界 [0.3, 3.0]）> 组件缺省框；Image 网络 src 支路告别恒 4:3、
  ImageTextLarge/Banner 告别写死 16:9（竖构图题图不再拦腰裁）、ImageBlock 帧随
  intrinsic、ImageGallery 改定高变宽经典画廊形态（等高不齐宽）；`frameAspectOf`
  兜底比例参数化 + 新增 `intrinsicAspectOf`；定尺寸缩略图（ImageText/ListItem/
  ListBlock 48dp）与 VisualCard 背景保持 Crop 裁切不变。
  相册 APP 组合方式批次（2026-10-31，无 spec，结论直落设计语言文档）：
  学习 Google Photos / Apple 照片 / 小米相册签名形态固化——新增 PhotoGrid（相册主视图：
  方形 1:1 cell + 人脸聚焦 + 2dp 密缝 + 整网格 12dp 圆角，photos* ≤12 张，columns 缺省 3）
  + AvatarRow（人物区：56dp 圆形头像横滑条，[{photo*, label?}]）；CardHeader 扩
  `actionLabel`/`action`（「查看全部 >」分组头）。组件总数 95→97。两组件刻意绕开图片帧
  比例决策链（均匀裁切即设计语义）；server 计数口径扩 photos 数组括号配对扫描 + lint
  第五条（Grid 内 Image≥2 → 引导 PhotoGrid）；黄金示例 `GOLDEN_PHOTO_PROGRAM`，
  GOLDEN_LAYOUT_PROGRAM 照片墙同步改 PhotoGrid。设计语言文档新增 §4.6 相册构图规则。
  布局可控性批次（2026-10-31，spec 已随交付清理，git 历史可查——
  Ardot OpenUILayouts 页八帧 + 对帧校准 13 项）：
  保留 LLM 自由组合，杜绝奇怪组合改走确定性规则层——server 无声矫正
  （OpenUiLayoutNormalizer：图文 Row 自动补 weights/连续 Spacer 合并/单子裸容器
  解包/stat 数字栏自动居中）+ 结构门禁（OpenUiLayoutValidator：Card 嵌套≤2/容器链≤4/
  通栏签名件同层去重/图表禁横向分栏/Grid 照片墙升硬，违规 Observation 回灌同轮重试）；
  矫正经 AgentRunner argsTransformer 改写 Action argsJson，端侧渲染器零改动。
  新增 CompareRow（前后对比统一帧）/Timeline（时间轴）/ChartGrid（双图并排，R3 合法
  出口），组件总数 97→100。lint 三条软警告退役（收编进矫正/门禁）。版式范式八帧
  （统计报告/相册分组/前后对比/时间轴/图文混排双态 + 三新件规格）入
  refs/ardot/pattern-*、comp-*；既有组件对帧校准 13 项（Card 16dp 圆角/Banner
  HEADLINE 题字+蒙层参数/图表标题 CAPTION 弱色/Callout 12dp+IconSize.sm 等）。
  设计语言文档升 v9（§0 原则 4「组合收敛在规则层」+ §4.7 版式范式）。
  回合原生批次（2026-11-01，spec 已随交付清理，git 历史可查）：组件库
  100→101 新增 `Html(html*, display?=INLINE|FULLPAGE)`（L2 沙箱卡入树——完整自包含
  HTML 经既有 `HtmlCardSanitizer` 清洗（含 128KB 尺寸闸）→ 复用 `HtmlCard` WebView
  沙箱渲染（零 JS 桥、禁文件访问不变式继承）；`meta` 钉死
  `HtmlCardMeta(displayMode = INLINE)`——INLINE 跟随内容测高全展开、无限高语义，
  防混合分流把长 HTML 打成 FULLPAGE 0.5 屏预览而死 CTA（onOpenFullpage 未接，
  display prop 的 FULLPAGE 语义留后续期）；sanitize + 降级日志 `remember(rawHtml)`
  记忆化（每 rawHtml 只清洗一次、只打一次 WARN）；清洗 Rejected → 降级 CAPTION
  文本 `chat_openui_card_unavailable` 不炸卡）。shared `OpenUiLibrary` 注册 +
  server `RenderOpenUiValidator` 白名单同步 +Html（101 项字面量副本同源口径不变）。
  复审加固（2026-11-01，fix/openui-review-hardening）：DSL_RULES 补 `Html(html*)`
  签名教学（此前仅尾部裸名单，模型猜必填参数会 missing-required 静默丢组件）+
  规则 1 修订为「位置参数按签名序、可选参数推荐命名实参」（消除与黄金示例的自相
  矛盾）；端侧 Row.weights 值域防御（零/负/NaN/Infinity 槽位回退 1f 等宽，防
  Modifier.weight 抛 IllegalArgumentException 击穿渲染层，`OpenUiRowWeightsTest`
  钉住）；`OpenUiDispatchCoverageTest` 钉住「端侧分发集合 == 注册表 − 元素形状」
  （补齐 server 侧 101 项白名单钉住在端侧的缺口）。
  验收态（2026-11-01）：server/shared/androidApp 测试与门禁全绿；server 0.9.9
  已部署生产；**真机四场景验收 ✅ 全过（ui-driver）**：①统计报告卡
  （StatRow+柱图+PhotoGrid 全套）②相册分组卡（CardHeader+actionLabel 查看全部
  + StatRow + Callout 小结）③CompareRow 前后对比卡 ④硬约束内化——模型主动
  引用「Card 最多嵌套 2 层」自我合规，门禁零触发。（发卡类改动须 App 发包与
  server 部署双交付、多会话部署竞态两条教训已立法 `server/AGENTS.md`
  「发卡类改动双交付铁律」。）
  回复活感批次（2026-11-01，server 0.9.14）：turn-native 上线当天真机实证回归——
  提示词契约与全部黄金示例皆为结构化卡片，模型把「明天穿什么」这类问答也包成
  Card+标题+Callout+Timeline 表单卡，markdown 正文与对话语气消失（真机库 61 张
  openui 卡全为表单形态实证）。修复=提示词新增「回复形态先定位」节（契约段后、DSL 规则
  前）：对话/问答/解释/闲聊类回复 = 裸 `root = Markdown("自然正文")`（可多段/
  加粗/列表，禁表单卡包装）；数据卡 Card 首子级 Markdown 自然导语 + 收尾讲解，
  不做冷报表。新增对话黄金示例 `OpenUiTurnNative.GOLDEN_CHAT_PROGRAM`（测试
  同源钉住且自身须过校验器）。端上配套（1.0.77）：裸 Markdown 程序经
  `looksLikeProgram` 嗅探照常落 data_genui_program、AgentMarkdown 全量渲染无卡壳，
  正文字号对齐对话气泡（OpenUiMarkdown/MarkDownRenderer 14/20sp → 16/24sp，
  与 SegmentedAgentText 同口径——正文不再比提问小一号）。
  本地视频批次（2026-11-04，feat/openui-video-component）：组件库 102→103
  新增 `Video(photo?, aspect?, src?)`——签名对齐 Image：photo 填 `#videos`
  投影 id（禁字面量数字防臆造）/ src 仅 https 公网视频，二缺一必给，aspect
  缺省 16:9。取数通道：`search_media` 增 `mediaType` 参数（'photo' 默认 /
  'video' / 'all'，未知值保守回落 photo；refine_media_search 跟随上一轮基数
  类型），handle 新增 `#videos` fragment（`ScanPhotoProjection.toVideoRefs`
  → [{id,date}] cap 60；`ScanPhotoEntry` 增 `mediaType` 字段区分 PHOTO/VIDEO，
  旧 payload null 视为 PHOTO，`toPhotoRefs` 同步排除 VIDEO 防视频混入照片投影）。
  渲染：`OpenUiVideoPlayer` 复用 `ChatVideoPlayer`（Media3/ExoPlayer，卡内
  不自动播、离开组合即释放），photo 经 `openUiPhotoResolver` 端侧 id→uri
  解析（[PRIVACY] uri 不出端），解析不到 = 同形 surfaceVariant 占位。
  server 校验：`RenderOpenUiValidator` 白名单 +Video（103 项字面量副本同源）
  + 单卡视频上限 MAX_VIDEO_COMPONENTS=4（ExoPlayer 内存护栏，独立于图片
  位图口径，Video 的 photo/src 均不计入位图数）+ 规则 6 src https 校验按
  调用区间分流视频/图片文案 + 规则 8 Video 二缺一与位置参数 src https（与
  Image 同构，位置序同为 photo, aspect, src）；DSL_RULES 增 Video 签名 +
  §4 增 `#videos` 取数写法段。
  性能收口（2026-11-11，fix/openui-photo-parallel-resolve）：①会话照片全量解析改有界并行——
  OpenUiCardHost 会话 Ready 后对 handle 全量 entries（实测 ~300 张）的 photoResolver
  串行解析改 `resolveAllUrisInOrder`（并行 launch + Semaphore(8) 限界，结果顺序对齐
  allIds、失败跳过语义不变），2026-11-09 PhotoGrid 串行灰格（resolveAllProgressively）
  同病灶收口；②流式预览客户端预算——OpenUiStreamingCard 逐帧前缀重解析加
  `PREVIEW_PARSE_BUDGET_CHARS=24k` 字符预算，超限停帧解析、清旧帧落既有 indeterminate
  占位、等终结 Message 落库后正式卡接管（此前仅 server 侧 48 字符/80ms 发帧节流，
  客户端每帧全量重解析无预算，低端机大程序开销不可控）。
- **交互态**：BarChart 点柱 → @Set($selected, label) 本地消费（不上行，§3.4 不变式对偶）→
  session 重求值高亮 + viewState 持久化；M2 起 CheckBox 勾选/ChoicePicker 点选/Slider 拖值
  同机制写各自绑定变量（渲染期构造 OpenUiAction Set，Binding 缺失的裸字面量只读不写入）；
  viewState 线格式 2026-11-01 泛化为 `name=value;…` 键值对（`%`/`=`/`;` percent-编码，旧
  `selected=<label>` 为一阶子集，旧数据可读），持久化范围扩为当前 store 全部 Str 标量
  （选择态与表单文本同径恢复；Bool/Num 不持久化回声明默认值），恢复路径经 initialState
  种 `$name`；宿主 produceState 键收窄为 partId+program，viewState 回流不再重建 session
  （指纹比对防重复写与回流循环）；@OpenUrl 端侧 scheme 闸只放行 http/https（server 对
  字面量首参另有 https 同轮纠错）；@Run 已于 2026-11-01 动作三通道批次实装
  （`OpenUiSession.runToolAction`：MUTATION 语句经宿主 `OpenUiToolResolver` 执行
  白名单写工具、结果入 `mutationResults` 缓存可绑定 UI，QUERY 按当前 store 重取，
  详见 `shared/AGENTS.md` §openui）；
  @ToAssistant 自 2026-10-31 起上行——以用户消息发进会话（CardHeader/ListItem/ListBlock
  的 actionLabel 缺省动作由 labeledActionsOrDefault 兜底合成同文案），M1 及之前仅记日志
  不路由。
- **端到端例子（turn-native 时序，2026-11-01 起）**：「统计我今年的照片」→ 云端回合
  search_media 取数（display=false）命中注册 handle → LLM 终结回复直接输出整颗
  BarChart 程序（buckets = Query("local_handle", {handle: "local://scan/<id>"})；流式
  content 增量经 `openui_delta` 前缀预览随流生长，ProgramGuard 门控 + ensureValidProgram
  兜底保证终结消息恒为合法程序）→ 终结 Message 事件下行 → 端侧 `CloudAgentTurnRunner`
  message 分支嗅探程序形态 → 落 `data_genui_program`（fallbackSummary 取自 `// summary:`
  首行）→ OpenUiCardHost 求值填槽渲染。

---

## 5. 规划中卡片

### 5.1 审批三要素卡（📋 spec 定稿待实施）

DESTRUCTIVE 批量删除的聚合确认卡：数量 + 珍贵信号（收藏/老照片）+ 可恢复性三要素；双链路（JS 写通路 + Tier B 批量阈值 3）；形态 = 升级确认对话框（不做 chat 内审批卡）。spec：`docs/superpowers/specs/2026-09-26-approval-three-element-card-design.md`。

### 5.2 ADR-016 parts 模型现状（✅ 终态；M4 真机验收待补）

一条回复 = parts 文档，卡片 = 独立 block；Turn 聚合渲染；markdown AST 管线换代。卡片 UI/渲染形态**不变**（ADR-016 D2）。里程碑：M1（`MessagePart.kt` sealed + `MessagePartsCodec.kt` partsJson 线格式）、M2（`domain/chat/streaming/` 三件套 + `TaskCardOverlay` + Chart/HtmlCard `state` 字段 + `"<messageId>:<partId>"` 命名空间 id + `RoundStarted` 显式轮边界）、M3（`AgentMarkdown` AST 正文）、M4（渲染源切 parts：`ChatListFlattener` 拍平 + Turn 聚合 + §8 性能收口；验收 `M4_CHAT_FLATTEN_ACCEPTANCE.md`，**真机冒烟/重组实测/截图基线待补**）、**2026-11-08 值域终态批次**（spec `2026-11-08-chat-value-domain-ideal-design.md`：4+1 分类 13 值一步到位——reasoning/source 新分类 + `tombstone` 转正 + `data_genui_program` 改名；parts-first 持久化（D1a，`projectChatRow`/`primaryPartOf` 立法）；`ChatMessageType` 纯 kind 化（D2a，9 值）；Room v28 聊天表重置 + iOS envelope v6 + 备份 v6 粗映射，legacy 机器全删）。线格式实例见 §0.1/§0.2，各卡 parts 协议见 §3/§4 各节，渲染矩阵见 §1.3 + chat.yaml §3.2。数据形态（已按实现校准）：

```kotlin
// shared commonMain（终态，13 子类型 = 4+1 分类）
sealed interface MessagePart { val partId: String }
// content: Text(markdown, state: PartState) | Image(ref, saved)
// reasoning: Reasoning(text, state: PartState)
// tool: Chart(svg, state) | HtmlCard(html, meta, state) | TaskCard(toolCallId, state, task) | EditResult(ref, description, suggestions, saved) | BrowserLive(…)
// data: OptimizeCandidates(group) | OpenUiCard(program, fallbackSummary, viewState?)
// source: SourceUrl(sourceId, url, title?) | SourceDocument(sourceId, mediaType, title, filename?)
// tombstone: Tombstone(originalType, summary?)
```

v5 备份恢复粗映射（唯一残存 legacy 知识，只活在备份模块）见 §0.2 末表；历史 parts 映射表 `2026-09-27-chat-parts-rendering-design.md` §2。

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

- **单测**：见各卡片小节；DAO 层 `ChatMessageDaoTest`；shared `MarkdownSegmenterTest` / `StreamingPacingControllerTest` / `MessagePartsCodecTest`（13 值 round-trip + Tombstone 前向兼容钉）/ `ChatMessageProjectionTest`（type 投影零漂移 + `primaryPartOf`）/ `ChatListFlattenerTest`（拍平规则 + reasoning/source_chips 桶）+ streaming 包（adapter/reducer）；androidApp `ChatMessagePartsProjectionTest`（DAO 级 parts-first 往返）/ `Migration2728Test`（v28 重置钉）/ `BackupChatRestoreTest`（v5 粗映射 + v6 直通）；M3 `InlineHtmlAnnotatorTest` + `MarkdownStreamRobustnessTest`。
- **ui-driver 冒烟**（DEV_ONLY，DEBUG 构建）：`/html` → `HtmlCardSmokeSamples`（十一卡含双形态/远程/ALL_IN_ONE）；`/task` → `EngineerTaskSmokeSamples`（五态 + 超屏展开）。⏳ M4 真机冒烟待补（设备离线，清单见 `M4_CHAT_FLATTEN_ACCEPTANCE.md` §3：流式/`/html`/`/chart`/`/task`/长列表滚动 + 重组实测 + Turn 聚合截图基线）。
- **设计稿验收**：health-check（`scripts/ardot-health-check.py`）+ Light 双模（`scripts/ardot-light-verify.py`）+ refs 快照（`scripts/export-ardot-snapshot.py`）。

## 8. 文档索引

| 主题 | 文档 |
|---|---|
| 渲染架构宪法 | `docs/02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md`（+ 实施 spec 2026-09-27-chat-parts-rendering-design） |
| 请求/回复全链路协议 | `docs/03-TECHNICAL-SPECS/CHAT_PROTOCOL_TECH_SPEC.md`（路由→组装→流式→落库→历史双轨；本目录管逐卡实例，链路级归该文档） |
| type 值域分类法（终态 4+1 分类 13 值） | ADR-016 D1（2026-11-08 值域终态 + 命名规约立法全文；演进史 git 可查，`2026-09-28-chat-type-taxonomy-design.md` 为分类法时点 spec） |
| 富内容渲染决策（原 ADR-014 D1~D6） | 已并入 ADR-016（D2 渲染 / D3 沙箱 / D4 iOS / D5 样式 / D6 不做；编号 014 永久留空） |
| HTML/图表/脚本实现 SSOT | `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md` §5/§7/§7.1 |
| Turn 聚合 + 拍平渲染矩阵（视觉 SSOT） | `docs/08-UI-SPECS/screens/chat.yaml` §3.1 turn_aggregation / §3.2 parts_rendering（M4 已落地 ✅，iOS TODO 口径） |
| M3/M4 验收 | AST 正文 + immediate 长度门控 + 拍平规则全枚举 13 例已落地；真机冒烟待补 |
| 双形态 + 任务卡 HTML 化 | `docs/superpowers/specs/2026-09-26-html-card-two-tier-design.md` |
| 任务卡产品线 | `docs/superpowers/specs/2026-09-25-engineer-task-card-design.md` · UserTask 协议 2026-09-26 |
| 设计稿 | Ardot polang-ui-spec（fileId 715061534788814）HtmlCard 页 438:2 · ChatComponents 页 438:328；refs `docs/08-UI-SPECS/screens/refs/ardot/` |
| 样式 tokens | `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md` |
