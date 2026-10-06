# Chat 消息类型分类法重构设计（type taxonomy）

> **日期**：2026-09-28
> **状态**：已定稿待实施
> **上位约束**：ADR-016（chat 消息内容模型宪法）；本文是 ADR-016 框架内的**鉴别字段分类学**专项，不改变 parts 模型本身
> **关联文档**：`docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md` §0/§0.1/§0.2/§0.3（协议总纲与 OpenAI 兼容硬约束）、`docs/superpowers/specs/2026-09-27-chat-parts-rendering-design.md`（M1~M5 主线 spec）
>
> 2026-09-28 修订（用户指示「无需考虑兼容问题，按理想态实现」）：运行时转换器纯净化——不再双吃 legacy 13，legacy 映射知识收敛到迁移专用 `LegacyChatTypeMigration`（§4.2/§5/§6/§8/§11 联动）。
>
> 2026-10-06 增补：tool 类扩第 9 值 `tool_browser`（browser-vnc 云端浏览器直播卡，spec `2026-10-06-browser-vnc-live-card-design.md` §4）——纯新增无 legacy 迁移源，命名遵循 §1.1（tool 类、kind=产物名词、非工具函数名绑定）。

---

## 0. 背景与动机

legacy 13 值（`user_text`/`agent_text`/`user_image`/`user_image_text`/`agent_image`/`agent_edit_result`/`command`/`plan_preview`/`media_results`/`chart`/`html_card`/`task_card`/`optimize_candidates`）是三个分类维度混杂的产物：

- **角色前缀**：`user_` / `agent_` 把消息角色编码进类型值；
- **内容种类**：`text` / `image` / `chart` / `html_card`；
- **工具产物**：`task_card` / `edit_result`；
- **UI 容器词**：`html_card` / `task_card` 的 `_card` 后缀是渲染形态概念，不属于协议语义；且后缀使用不一致（`chart` 无 `_card`，`html_card` 有）；
- **僵尸类型**：`command` / `plan_preview` 在主聊天流无专属渲染分支（真正消费者是平行浮动面板体系的 sealed `AgentMessage`），M1 起已映射为 Text。

ADR-016 parts 重构（M1~M4 已全合 main）把鉴别字段原样继承了这套随意命名（`MessagePart` 的 `@SerialName` 值与 legacy 列对齐）。**现在是改名的历史最低成本窗口**：M5（iOS 跟随）未动工、Swift 侧零存量；partsJson 落库仅一周、存量行少；M4 已把渲染拍平到 parts，legacy 列的读点所剩无几。

## 1. 目标分类法（协议线）

**三分类 9 值**（8 值 + 2026-10-06 扩 `tool_browser`），对齐主流协议（OpenAI Responses / Vercel AI SDK）的 content / tool / data 三族：

| 类目 | 新值 | 迁移源（legacy type 列值） | 语义 |
|---|---|---|---|
| content | `text` | `user_text` / `agent_text` / `command` / `plan_preview` | 正文段（用户/模型原生内容） |
| content | `image` | `user_image` / `user_image_text` / `agent_image` | 图片块（媒体红线：原生 block） |
| tool | `tool_chart` | `chart` | draw_chart 产物（SVG） |
| tool | `tool_html` | `html_card` | render_html 产物（HTML 文档） |
| tool | `tool_task` | `task_card` | 工程师任务状态机投影 |
| tool | `tool_image_edit` | `agent_edit_result` | 对话式图像编辑产物 |
| tool | `tool_browser` | —（2026-10-06 新增，无 legacy 源） | 云端浏览器会话直播卡 |
| data | `data_media_results` | `media_results` | 相册搜索结果集 |
| data | `data_optimize_candidates` | `optimize_candidates` | AI 优化抽卡候选组 |

### 1.1 命名规则（新增 part 类型的登记硬约束）

1. **词法**：`^{category}_{kind}$`，纯下划线 snake_case，一个值内不得混用中划线与下划线；
2. **类目**：`tool_` / `data_` 为仅有的两个保留类目前缀；content 类**免前缀**（主流惯例，`text`/`image`）；
3. **kind = 产物名词**：取能唯一标识的最短名词；**禁 UI 容器词**（card / bubble / strip / viewer 等渲染形态概念不进协议值——"卡片"是 `CHAT_CARD_CATALOG.md` 的展示层登记概念）；
4. **解耦工具函数名**：tool 类 kind 对齐**产物**而非 `@Tool` 函数名（draw_chart→`tool_chart` 是产物恰好同名，非绑定），工具重命名不传染线格式；
5. **防撞车**：kind 不得以保留前缀开头（如 content 类禁 `tool_*` 命名）；
6. **解析规则**：类别判定 = 前缀匹配（`tool_`→TOOL、`data_`→DATA、其余→CONTENT），代码侧由 `MessagePart` 子类型直接承载（见 §4.1），不做字符串解析。

### 1.2 类目的协议语义

| 类目 | 回灌 LLM | 状态机 | toolCallId |
|---|---|---|---|
| content | 进上下文（image 例外：媒体红线，占位 `[user sent an image]`） | Text 有 `PartState`（STREAMING/DONE）；Image 无 | 无 |
| tool | tool-call / tool-result 配对回灌（§0.3 硬约束①②） | 恒有 `ToolPartState` 七态 | 恒有 |
| data | **剥离**（对齐 Vercel `convertToModelMessages` 丢弃规则） | 无 | 无 |

**回灌剥离规则升级**：从硬编码类型清单（`MediaResults`/`OptimizeCandidates`）变为 **`data_` 前缀即剥离声明**——新增 data part 零回灌适配成本。实现上仍走 `when` 子类型分派（sealed 穷尽性优先），前缀规则作为**设计约定 + 测试锁定**（§8），代码里不做字符串前缀判断。

## 2. role 上提为消息级字段

`user_text`/`agent_text` 归一为 `text` 后，角色判据必须有新载体：

- **Room `chat_messages` 加 `role` 列**：`TEXT NOT NULL`，值域 `"user"` / `"agent"`；v25→v26 迁移期由 legacy type 前缀推导（`user_*`→user；其余含 `command`/`plan_preview`→agent）；
- **partsJson 不改信封**：role 是消息级属性，不进 part（维持裸数组线格式）；
- **UI 判据切换**：`ChatViewModel.toUiModel()` 的 isUser 判据从 type 前缀切换到 role 列；shared `ChatMessage`（双端 SSOT）加 `role` 字段（或 `isUser` 派生），iOS M5 直接按新模型实现；
- **值域演进预留**：未来 `system` / `tool` 角色（OpenAI 语义）直接扩展列值，不再污染 part 分类法。

## 3. legacy `type` 列终态

- **type 列保留但降级**：值重写为新分类法的主 part 值（多 part 消息——legacy 仅 `user_image_text` 双 part——取其首 part 值 `image`），仅作**索引冗余 + 迁移源**；
- **读面收敛**：反序列化路由（`toUiModel`）与回灌（`toModelInput`）全部以 partsJson 为唯一权威读面（M4 已完成渲染侧切换，本重构清剩余读点）；`decodePartsOrLegacy` 的 legacy 现算回退路径**保留**（防御旧行 partsJson 缺失），但回退输出即新分类法 parts；
- **僵尸类型删除**：`command` / `plan_preview` 从 `ChatMessageType` 枚举与转换器中删除，存量行迁为 `text` + role=agent；浮动面板体系消费的 sealed `AgentMessage` 是平行模型，不受影响；
- **已知 SQL 直读点**：`ChatMessageDao.getTaskCardMessages()`（任务中心跨会话查询，`type='task_card'` 字面量）改为 `type='tool_task'`——这是 type 列仅存的索引价值场景，保留列即为此类查询服务。

## 4. 代码结构变更

### 4.1 `MessagePart`（shared `domain/chat/MessagePart.kt`）

- 8 个子类型 `@SerialName` 改为新值：`text` / `image` / `tool_chart` / `tool_html` / `tool_task` / `tool_image_edit` / `data_media_results` / `data_optimize_candidates`（2026-10-06 增第 9 子类型 `BrowserLive` → `tool_browser`，无迁移源，纯新增登记）；
- 新增 **`PartCategory` 枚举**（`CONTENT` / `TOOL` / `DATA`）与 `MessagePart.category` 抽象属性（各子类型覆写返回常量）——类目的代码层权威承载，`when` 分派与文档登记共用；
- 类注释更新：分类法与命名规则（§1.1）写入 KDoc，作为新增 part 的登记指引。

### 4.2 转换与编解码

- **运行时转换器纯净化**：`LegacyMessagePartsConverter` 更名 `MessagePartsConverter`（文件 `LegacyMessageParts.kt` → `MessagePartsConverter.kt`），**仅映射新 8 值**——不再双吃 legacy 13（2026-10-06 起另认 `tool_browser`：无 legacy 源，content 列存 `BrowserLive` 整颗 JSON 直通解码，见 browser-vnc 直播卡 spec §4）；未知值 → 行级 Text 兜底原则不变。签名补 role 入参（`toParts(type, content, metadata, role)`）：`image` 映射按角色分流——user 且 metadata.imageUri 在场 → [Image, Text] 图文双 part，否则 [Image(ref=content)]；agent → [Image(ref=metadata.imageUri ?: content, saved)]；
- **legacy 映射收敛迁移专用**：新增 `LegacyChatTypeMigration`（shared `domain/chat/`）：`map(legacyType, content, metadata) → MigratedRow(type, role, parts)`——type 重写表 + role 推导（`user_` 前缀 → `"user"`，其余 → `"agent"`）单点收口，parts 委托 `MessagePartsConverter`（零解析逻辑重复）。**仅供 `MIGRATION_25_26` 与备份恢复两个存量数据入口使用**，运行时路径不引用；
- `MessagePartsCodec`：线格式随 `@SerialName` 自动更新；`ignoreUnknownKeys` 前向兼容语义不变；round-trip 测试全量改写（§8）。

### 4.3 实体与迁移

- `ChatMessageEntity`：`type` 列 KDoc 改为新 8 值 + 降级定位说明；新增 `role` 列（`String`，default `"agent"` 仅作迁移安全网，写路径恒显式赋值）；
- `ChatMessageDao`：`getTaskCardMessages()` 等 SQL 字面量改新值；写入路径（`insertMessageWithParts`）补 role 赋值；
- `ChatMessage`（shared 双端 SSOT）：加 `role` 字段；`toUiModel` 路由判据切换。

### 4.4 回灌（`toModelInput`）

- tool 类配对回灌维持现有形态（`draw_chart`/`render_html` 等工具函数名与 ToolCall/ToolResult 结构不变），仅 part 来源鉴别值更新；**part type 值与工具函数名的映射表集中一处**（单点维护，呼应 §1.1 第 4 条的解耦原则）；
- data 类剥离逻辑不变（子类型分派），注释更新为前缀规则语义；
- `toHistoryPair` 的 GET_CHAT_HISTORY 线格式同步更新（`("tool_call", "draw_chart")` 等工具名不变，part 来源值变）。

### 4.5 流式（M2 三件套）

- `TurnPartsReducer` 的类型化占位判断（`draw_chart`/`render_html`→Chart/HtmlCard 占位）按工具函数名驱动，**不受 part type 改名影响**；占位 part 的序列化值自然为新值；
- `TaskCardOverlay` / `ChatListFlattener` 的子类型 `when` 分派不受影响（sealed 类名不变，仅鉴别值变）。

## 5. Room v25→v26 迁移（`MIGRATION_25_26`）

两步，单事务：

1. `ALTER TABLE chat_messages ADD COLUMN role TEXT NOT NULL DEFAULT 'agent'`（default 仅迁移安全网，写路径恒显式赋值）；
2. **逐行转换**（compileStatement 单语句复用，实现形态同 v24→v25 `backfillChatMessageParts`）：读出 `(id, type, content, metadata)` → `LegacyChatTypeMigration.map` → **一次 UPDATE 同写** `type`（§1 新值）+ `role` + `partsJson`（新鉴别值重编码）。行级 runCatching 双保险：失败行 `type='text'` + `role='agent'` + Text 原文 part，**不丢消息**。

> 先重写 type 再重编码会丢信息（`user_image`/`user_image_text`/`agent_image` 同归 `image` 后无法区分 parts 形态），故单行内原子完成三字段转换。
> 测试壳复用 `ChatMessagePartsMigrationTest` 的 fixture 框架（§8）。

## 6. 消费点改造清单（grep 锚点）

| 位置 | 改动 |
|---|---|
| `shared/.../domain/chat/MessagePart.kt` | §4.1（鉴别值 + PartCategory） |
| `shared/.../domain/chat/MessagePartsConverter.kt`（原 LegacyMessageParts.kt） | §4.2 运行时转换器纯净化 |
| `shared/.../domain/chat/LegacyChatTypeMigration.kt`（新建） | §4.2 迁移专用 mapper（迁移 + 备份恢复共用） |
| `shared/.../domain/chat/MessagePartsCodec.kt` | 注释/测试 |
| `shared/.../domain/chat/ChatMessage.kt` | +role；`toModelInput` 映射表单点化（§4.4） |
| `androidApp/.../data/local/ChatMessageEntity.kt` | +role 列、KDoc（§4.3） |
| `androidApp/.../data/local/AppDatabase.kt` | version 26 + `MIGRATION_25_26`（§5） |
| `androidApp/.../data/local/ChatMessageDao.kt` | SQL 字面量（`'task_card'`→`'tool_task'`、`'media_results'`→`'data_media_results'`、`type LIKE 'user\_%'`→`role = 'user'`）+ 写入路径（§4.3） |
| `androidApp/.../features/chat/ChatViewModel.kt` | `toUiModel` parts+role 判据 + 各 emit 写路径 type 值 + role 显式赋值（§2/§4.3） |
| `androidApp/.../domain/backup/TagDataBackupRepository.kt` | 备份 DTO +role；恢复路径旧备份经 `LegacyChatTypeMigration` 一次性转正，新备份直通 |
| 任务中心（`TaskCenterScreen` 数据源链路） | 随 DAO 查询自动生效，冒烟确认 |
| iOS（M5 未动工） | `docs/08-UI-SPECS/screens/chat.yaml` §3.2 ios_todo 登记新值，零迁移 |

## 7. OpenAI 兼容硬约束回归（catalog §0.3 六条）

| 约束 | 本重构的影响与保障 |
|---|---|
| ① 配对完整（call_id 两端一致） | 不受影响（toolCallId 语义不变），回灌测试回归 |
| ② arguments 恒 JSON 字符串 | 不受影响 |
| ③ 入参原文保真 | 不改善不恶化（M2 已知缺口照旧登记） |
| ④ UI 专有 part 不进上下文 | **强化**：data_ 前缀规则 + 测试锁定 |
| ⑤ user 图不外发 | 不受影响（image part 占位回灌不变） |
| ⑥ 枚举可映射 | **强化**：三分类与 OpenAI Responses item 族（message / function_call+output / 无对应→剥离）一一对应，§1.2 表即映射表 |

## 8. 测试计划

- `MessagePartsCodecTest`（commonTest）：8 值 round-trip 全量 + 缺省省略（`encodeDefaults=false`）+ `category` 属性正确性 + **前缀规则锁定测试**（所有 `@SerialName` 值经反射/KClass 枚举，断言 `data_` 前缀 ⟺ category==DATA、`tool_` ⟺ TOOL、无前缀 ⟺ CONTENT——命名规则违反即测试红）；
- `MessagePartsConverterTest`（原 LegacyMessagePartsConverterTest 改写）：新 8 值映射全枚举 + `image` 的 role 双分支 + 未知值 Text 兜底；
- `LegacyChatTypeMigrationTest`（新建）：13 legacy 值 → `MigratedRow(type, role, parts)` 三元组全枚举（含 `command`/`plan_preview`→text+agent、`user_image_text`→image+user+双 part、未知值兜底）；
- `ChatMessagePartsMigrationTest`（androidTest）：v25 fixture 行（13 值各一 + 边界行：未知类型、损坏 metadata、空 metadata）→ v26 断言 role 推导、type 重写、partsJson 重编码、兜底行不丢；
- 回灌测试：`toModelInput` 的 tool 配对完整 + data 剥离 + image 占位，六条硬约束回归；
- `toUiModel`：role 判据单测（user/agent 气泡分派）。

## 9. 文档同步清单（同原子提交，[DOC-SYNC]）

- `CHAT_CARD_CATALOG.md`：§0 协议总纲（type 值表 + role 列）、§0.1（不统一盘点标记"已根治"）、§0.2（逐类型示例新值）、§2 总表 type 列、各卡「落库协议」示例值；
- `ADR-016`：补一节分类法决策（三分类 + 命名规则 + role 上提），或在本文被引用处加注；
- `shared/AGENTS.md` §2 `domain/chat/` 上位约束段：M1 落地状态描述的 8 值更新；
- `docs/08-UI-SPECS/screens/chat.yaml` §3.2 ios_todo：M5 按新值实现的登记；
- 本 spec 归档即索引（`docs/superpowers/README.md` 无需改，按日期自然索引）。

## 10. 落地形态

- 按 AGENTS.md §3.4：`.worktrees/chat-type-taxonomy` + `refactor/chat-type-taxonomy` 分支（自 main）；
- 单原子提交：代码 + 迁移 + 测试 + 文档一次性合入（线格式变更不可半半拉拉）；
- 闭环验证：`JITPACK=true ./gradlew :shared:jvmTest :shared:assemble :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest` + androidTest 迁移测试（可用设备/模拟器时）；
- 真机冒烟顺车挂 M4 遗留六项（合 main 后同批执行）；重点加验：存量会话打开（迁移路径）、任务中心列表（`tool_task` 查询）、回灌多轮对话（tool 配对）。

## 11. 非目标（明确不做）

- 不改 partsJson 信封格式（裸数组维持；role 不落 part 级）；
- 不动 `ToolPartState` 七态与 `PartState`（状态机分类学已系统，无改名诉求）；
- 不动浮动面板体系的 sealed `AgentMessage`（平行模型，不在 chat_messages 协议面）；
- 不做 M2 已知缺口（工具原始入参保真，`input` 字段）——属另一专项；
- **不做运行时 legacy 双吃**（用户 2026-09-28 指示：无兼容包袱、按理想态实现）——旧存量数据（v25 库 / 旧备份）经 `LegacyChatTypeMigration` 一次性转正，运行时转换器不保留 legacy 分支；
- 不做闲聊/命令的 type 级区分——type 是内容产物分类，对话意图属 ADR-015 路由层，需要时挂 metadata/modelUsed，不进 type 值域；
- iOS 侧零代码（M5 开工时直接按新分类法实现）。
