# Chat 消息值域理想形态重设计（一步到位版）

> **日期**：2026-11-08
> **状态**：已批准（2026-11-08 用户裁决：**D1=(a) parts-first**——persist 点直接构建 `List<MessagePart>`，partsJson 权威，标量列降级为派生投影；**D2=(a) ChatMessageType 收敛为纯 kind 枚举**——删 role 前缀重复值）
> **前置**：横滑图集卡退役分支（`feat/retire-media-carousel`）合 main 后开工——本设计大量改建其触碰的同一批文件（codec/迁移/值域），必须串行
> **关键约束（用户定）**：主动安装用户仅 ~10 人，**不在乎聊天历史债**——按理想形态一步设计实现，老聊天数据可重置，legacy 兼容机器可全删

---

## 1. 背景与动机

对照 OpenAI Responses API 与 Vercel AI SDK UIMessage parts 模型（[Vercel UIMessage reference](https://ai-sdk.dev/docs/reference/ai-sdk-core/ui-message)、[OpenAI Responses object](https://platform.openai.com/docs/api-reference/responses/object)）review 现值域（退役后 9 值：text/image + tool_chart/tool_html/tool_task/tool_image_edit/tool_browser + data_optimize_candidates/data_ui_openui），发现：

1. **🔴 part 级前向兼容兜底缺失**：`ignoreUnknownKeys` 不覆盖未知 sealed 鉴别值——老 App 读新 part type 整消息抛异常回落（A2UI 已实证腐烂一次）。值域每扩一次，老版本就脆一次。
2. **🔴 reasoning 无一等 part**：思考链只有瞬态 ThinkingIndicator，不可持久化/回放（OpenAI `reasoning` item、Vercel `reasoning` part 均为一等公民）。
3. **🟡 source 引用无结构化载体**：web_search 结果以 markdown 图链嵌正文，不能做引用列表/溯源 UI（Vercel `source-url`/`source-document` 独立 part）。
4. **🟡 `data_ui_openui` 内嵌技术名**：OpenUI 若被下一代 GenUI 协议取代，值即腐烂（A2UI `data_ui` 先例）。kind 应为语义名。
5. **🟡 历史债机器拖尾**：legacy 13 值映射（`LegacyChatTypeMigration`）、RETIRED 退役闭集、双端回放过滤、iOS legacy 兼容——为 ~10 台设备永久背负，违背治理总纲「简洁性 > 兼容性」。

> **builder-check 修正（2026-11-08）**：ToolPartState **已是七态**（四基础态 + APPROVAL_REQUESTED/APPROVAL_RESPONDED/OUTPUT_DENIED，任务卡审批 UI 已在消费，`MessagePart.kt:207-215`）——状态机不缺位，本设计**不动 ToolPartState**（OUTPUT_DENIED 保留：无人分支消费但 CHAT_CARD_CATALOG 登记为人在环终态槽位，审批三要素卡落地时可能需要 deny 终态；删了再加回比留着贵）。原「四态缺审批双态」动机与「六态对齐」条目撤销。

**为什么现在做**：值域刚经历两轮退役（A2UI 2026-11-05、横滑卡 2026-11-08），代码热区全开；用户基数允许破坏性重置聊天表；一次到位比每次扩值补一次兼容便宜。

## 2. 目标 / 非目标 / 反目标

**目标**
- 值域终态对齐主流语义分层（content/reasoning/tool/data/source），Kotlin sealed 闭集形态保持（Agent First 穷尽性红线）
- 命名规约立法完整版（语义名/禁技术名/集合负载规则/前缀即分类）
- legacy 兼容机器全删：LegacyChatTypeMigration、RETIRED 闭集、回放过滤、iOS legacy 推断、A2UI 墓碑分支
- part 级未知值 Tombstone 兜底（前向兼容，防下一次腐烂）
- `reasoning`/`source_url` 两个新 part 类型落地（含渲染与回灌规则）

**非目标**
- 不做 `tool-${name}` 开放命名空间（Kotlin sealed 必须闭集；渲染分发按卡型语义优于按工具名）
- 不做 `custom` provider 逃生舱（闭集红线；实验性需求走 data_ 前缀）
- 不引入 `step_start` 持久 part（Vercel 单消息跨多 step 才需要；PoLang 一回合 = 多消息行，轮边界已由 user 消息行天然表达，落库无消费者）
- 不启用 reasoning 生产开关（extended thinking 涉及成本/延迟产品决策；本设计只落 part 类型 + 渲染 + 可选接线点，默认关）
- 不做 file/video/audio content 扩列（规约预留命名，产品需求驱动再落）
- 不动 server（不读 Room 值域）

**反目标**
- 任何形式的「双吃」：运行时只认新值域，迁移/备份只做一次性单向转换
- 为美学改名动存量数据以外的兼容层
- 保留 TAG/人脸/记忆等非聊天表的任何重置（那些是昂贵数据，不在「历史债」豁免范围）

## 3. 目标值域（终态）

**消息级**：`role`（user/agent）独立列、有序 parts 数组、partId 块级 id——不变。

**part type 值域（4+1 分类 13 值）**：

| 分类 | 值 | 语义 | 回灌 LLM |
|---|---|---|---|
| content（bare） | `text` | markdown 正文（PartState streaming/done） | ✅ TextMessage |
| content（bare） | `image` | 图片（ref/saved；媒体红线：字节不出端） | 占位 `[user sent an image]` |
| reasoning（bare，新） | `reasoning` | 思考链文本（PartState；可折叠渲染） | ❌ 剥离（对齐 Vercel convertToModelMessages 丢弃） |
| tool_ | `tool_chart` | 图表卡（SVG） | tool-call/result 对 |
| tool_ | `tool_html` | HTML 卡（双形态） | tool-call/result 对 |
| tool_ | `tool_task` | 工程师任务卡 | tool-call/result 对 |
| tool_ | `tool_image_edit` | 编辑结果卡 | 回灌 description |
| tool_ | `tool_browser` | 浏览器直播定格卡 | tool-call/result 对（摘要投影） |
| data_ | `data_optimize_candidates` | AI 优化抽卡候选组 | ❌ 剥离 |
| data_ | `data_genui_program` | GenUI 卡（OpenUI 程序；自 `data_ui_openui` 改名） | ❌ 剥离 |
| source_（新分类） | `source_url` | web_search 引用（url/title/sourceId） | ❌ 剥离 |
| source_（新分类） | `source_document` | 文档引用（mediaType/title/filename） | ❌ 剥离 |
| tombstone（bare，新） | `tombstone` | 未知/退役 part 的可读占位（原始 type 名 + 一句话提示） | ❌ 剥离 |

**预留（规约就位，不预建）**：content 类 `video`/`audio`/`file`（bare，同免前缀规则）。

**命名规约（立法全文，修订 2026-11-08 初版）**：
1. kind = 单数 snake_case **语义名**；**集合型负载可用复数**（`optimize_candidates` 自此合规，不再算历史例外）
2. 禁嵌实现技术/厂商/协议版本名（`data_ui_openui` → `data_genui_program` 即本条首次执行；`genui` 为范式名非技术实现名）
3. 禁 UI 容器词（card/bubble/strip/viewer/item/row）
4. 前缀即分类声明：`tool_`/`data_`/`source_` 强制；bare = content/reasoning/tombstone（高频正文与结构占位）；`data_`/`source_` = 回灌剥离机械声明
5. 新增值过本规约审查 + codec Tombstone 兜底测试钉住

**ToolPartState**：**七态保持不动**（builder-check 核实已含审批双态 + OUTPUT_DENIED，对齐 Vercel v6 语义已完成于前序 spec；OUTPUT_DENIED 保留为人在环终态槽位）。审批 UI 随「审批三要素卡」spec 落地时消费既有状态。

## 4. 历史债出清（本设计核心收益）

聊天数据重置 + 兼容机器全删，一次性完成：

1. **Room v27→v28 迁移**（builder-check 修正：当前库已是 **v27**，`MIGRATION_26_27` 已被 `local_handle` 表占用，`AppDatabase.kt:63`）：`chat_sessions` / `chat_messages` 两表 **DROP + 重建**（聊天历史重置，用户已批准）；**其余表（media_assets/tags/face_embeddings/persons/memory_facts/media_feedback/photo_edit_recipes…）一律不动**。`chat_image_cache` 显式裁决：**保留不清**（键独立于聊天表，孤儿缓存无害且按 LRU 自然淘汰；清它反而多一个误删面）。Koog 短期轨 DataStore 键（`koog_memory_*`）同步清空。
2. **删除清单**：
   - `LegacyChatTypeMigration` 全文件（legacy 13 值映射 + RETIRED 闭集）——迁移链 v24→…→v27 已在存量设备跑完，v28 起老聊天行不复存在
   - 双端回放路径的 RETIRED 过滤（Android loadMessages/rebuildChatMemoryIfEmpty、iOS codec decode filter）
   - `MessagePartsConverter` 的 legacy 转换职责整体退役——codec 只认新值域；`data_ui`（A2UI）墓碑分支随删。**注意**（builder-check 核实）：Converter 同时是现行**写路径**（全部 ~37 处 insert 由标量列派生 partsJson）与**读兜底**（decodePartsOrLegacy）——删除它的接缝形态属关键决策 D1（见 §4.1）
   - iOS：Swift `ChatMessage` legacy 字段推断（role+text 老数据分支；mediaIds 分支在退役分支已删）、`MessageType.mediaResults` 枚举位；`chat_history_<sessionId>.json` **无版本字段**（builder-check 核实）——新增 envelope 版本号（或改文件名后缀），v6 以下旧文件**整体忽略**（与 Android 聊天表重置语义对齐；tombstone 兜底只管「支持版本内的未知 part」，不管「格式重置」，两者不冲突）
   - `ChatMessageType`（shared commonMain **11 值**枚举——builder-check 修正原「9 值」误判）是消息级 `type` 列、跨端模型（iOS typealias + `toShared` 映射 + 双端 UI 渲染分支），**不是**仅剩迁移/codec 用途——去留属关键决策 D2（见 §4.1）
3. **备份恢复 v5→v6**：chat 段恢复走备份模块内**一次性粗映射表**（旧 type→新值：text/image/tool_* 直通，data_ui/data_media_results/media_results/legacy 13 值 → 落 `tombstone` 或跳过行；映射表只活在备份模块，不进运行时）。旧 v5 包恢复不崩、聊天卡片降级为可读占位。
4. **TC/文档**：ADR-016 值域史收编为终态叙述；CHAT_CARD_CATALOG §0 重写为终态值域表（不再带退役编年）；命名规约更新到上文全文。

### 4.1 关键决策（builder-check 暴露，呈用户裁决）

- **D1 持久层接缝形态**：现行写路径是「标量列为主、parts 派生」（~37 处 insert 写 type/content/metadata，`MessagePartsConverter` 派生 partsJson；只有 tool_browser/data_ui_openui 把整块 part JSON 走私进 content 列）。删 Converter 后二选一：
  - **(a) parts-first**：persist 点直接构建 `List<MessagePart>`，partsJson 为权威；标量列降级为**投影**（由 parts 派生，供列表气泡/查询用）或随 v28 重建直接删掉
  - **(b) 瘦转换器**：保留一个方向翻转后的 Converter（parts→标量投影），写路径仍走标量
  - 本决策决定 v28 重建的 `chat_messages` schema、全部 insert 点形态、备份 v6 恢复写法、以及 reasoning+text+source 多 part 行如何落库
- **D2 `ChatMessageType` 去留**：role 已升格为 `ChatMessage.role` 独立列，11 值中 USER_*/AGENT_* 前缀对与 role 冗余。选项：**(a) 收敛为纯 kind 枚举**（删 role 前缀重复值，留 TEXT/CHART/HTML_CARD/TASK_CARD/OPTIMIZE_CANDIDATES/OPENUI_CARD 等渲染 kind）；**(b) 整体删除**，消息级 type 由首 part 派生（iOS 桥/codec/双端渲染分支全改，面最大）；**(c) 本批不动**，只删退役相关引用
- **D3 iOS 既有渲染降级缺口**：iOS codec 现把 `OpenUiCard`/`BrowserLive` 降级为 AGENT_TEXT（「iOS 未落地」，`ChatHistoryStoreCodec.kt:171-174`）。改名触及 iOS codec 字面量时，是否顺车补这两卡 iOS 渲染（PARITY 红线）还是维持独立跟踪的既有 gap：**裁决为维持独立跟踪**（本批只改字面量名，渲染缺口不随批膨胀；gap 台账已有登记）

## 5. 前向兼容（防下一次腐烂）

`MessagePartsCodec` 遇未知 part 鉴别值 → 产出 `Tombstone(partId, originalType)` part（渲染为「此内容需新版本 App 查看」占位行），**不再整消息抛异常回落**。消息级 `type` 列遇未知值同理（该行 parts 降级为单 Tombstone part）。commonTest + iosTest 各钉一例（伪造未来值 `data_future_x` 编解码往返）。

## 6. 新 part 落地点

### 6.1 `reasoning`
- 模型：`MessagePart.Reasoning(partId, text, state: PartState)`；Room 消息 type 列取值规则随 D1 决策落地（若 parts-first：type 投影 = 首个 content/tool 类 part 的 kind，reasoning 永不作投影源；排序规则：reasoning 排在 text 前）。**builder-check 修正**：现行代码**没有**「多 part 消息取首 part 值」规则（Android 写路径全走标量列；首 part→type 推导仅存在于 iOS decode `uiTypeOf`）——该规则是本设计要新建的行为，不是既有行为
- 渲染：Android `ChatReasoningPart`（可折叠「思考过程」块，样式参照 Vercel/ChatGPT reasoning UI）；iOS SwiftUI 对等
- 流式：TurnStreamEvent 增 reasoning delta 族；生产者接线点预留（Koog 1.3.0 reasoning 流式支持**实施期验证**——若 Koog 不透出 reasoning_content，则生产者挂账，part 类型/渲染/编解码照常落地）
- 回灌：剥离（不回灌 LLM）

### 6.2 `source_url` / `source_document`
- 模型：`SourceUrl(partId, sourceId, url, title?)` / `SourceDocument(partId, sourceId, mediaType, title, filename?)`
- 生产者：`web_search` 工具结果（DeepSeek Search 代理返回 ≤5 结果）由引擎层抽取为 source parts 挂到当轮 assistant 消息；正文 markdown 图链行为不变
- 渲染：文本气泡下引用 chips 行（序号 + 域名 + 点击开链接落地页）；iOS 对等
- 回灌：剥离

## 7. 影响面清单

| 层 | 文件域 |
|---|---|
| shared commonMain | `domain/chat/`（MessagePart/Codec/Converter 接缝随 D1/Flattener/ChatModelInput/ChatMessageType 随 D2）、`agent/core/streaming`（reasoning 事件族） |
| shared androidMain/iosMain | codec 平台侧、ChatHistoryStoreCodec（iOS legacy 删 + envelope 版本） |
| androidApp | Room v27→v28 迁移（chat 两表 DROP+重建）、备份恢复 v6 粗映射、ChatReasoningPart/SourceChips 渲染、strings 五语 |
| iosApp | ChatMessage.swift legacy 删、history 文件 envelope 版本 bump（旧文件忽略）、SwiftUI 对等渲染、xcstrings 五语 |
| 测试 | commonTest/iosTest/androidTest 全值域 golden 重生 + Tombstone 钉 + 迁移测试 + 备份粗映射测试 |
| 文档 | ADR-016（终态值域 + 规约全文 + 值域史收编）、CHAT_CARD_CATALOG §0 重写、shared/AGENTS.md §2、chat.yaml §3.2 渲染矩阵 |

## 8. 测试策略

- codec 全值域（13 值 + tombstone）编解码往返 golden
- Tombstone 前向兼容（伪造未来鉴别值）commonTest + iosTest 双钉
- Room v27→v28 迁移测试：chat 表重置为空 + media_assets/tags/memory_facts 内容保留
- 备份 v5 旧包 → v6 恢复粗映射测试（老卡行落 tombstone 不崩）
- reasoning/source 渲染：Compose/SwiftUI 快照（若已有截图基线机制则挂 baseline）

## 9. 风险

| 风险 | 缓解 |
|---|---|
| Koog 1.3.0 不透出 reasoning 流 | 生产者挂账，part 类型/渲染/编解码先行（非目标已声明默认关） |
| v28 DROP 聊天表误伤其他表 | 迁移测试显式钉「非聊天表内容保留」；遛狗机先验 |
| source parts 生产者抽取与 markdown 图链双显 | 渲染规则：chips 行与正文图链职责不同（图=配图，chip=来源列表），文案注明 |
| 备份粗映射把用户珍贵聊天降级 | 聊天历史属用户已批准的可重置范围；文本消息直通不受影响，仅卡片行降级 |

## 10. 假设（auto 模式自行裁决，未经用户逐条确认）

1. **串行依赖**：本设计在退役分支合 main 后开工，新 worktree/分支
2. **聊天历史重置可接受**（用户「不在乎历史债」的直接推论）；TAG/人脸/记忆等非聊天表不在豁免范围
3. `data_ui_openui` → `data_genui_program` 改名纳入本设计（聊天表重置后零迁移成本，趁窗口执行）
4. `reasoning` part 落类型/渲染/编解码，生产开关默认关（extended thinking 成本决策不归本设计）
5. `step_start`/`custom`/file 族不引入（论证见非目标）
6. 备份 v6 粗映射表只活在备份模块，运行时零 legacy 知识
7. **ToolPartState 七态不动**（builder-check 核实审批双态已存在；OUTPUT_DENIED 保留为人在环终态槽位）
8. **iOS 旧 history 文件走 envelope 版本忽略**（与 Android 聊天表重置语义对齐；tombstone 只管支持版本内未知 part）
9. **`chat_image_cache` 保留不清**（键独立、孤儿无害、LRU 自然淘汰）
10. **iOS OpenUiCard/BrowserLive 渲染降级缺口维持独立跟踪**，本批只随改名改 codec 字面量（D3）

> **待用户裁决（HARD GATE，批准前不动实现代码）**：D1 持久层接缝形态（§4.1）、D2 ChatMessageType 去留（§4.1）。
