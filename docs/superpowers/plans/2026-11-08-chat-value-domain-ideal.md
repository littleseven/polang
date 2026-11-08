# Chat 消息值域理想形态重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 chat 消息值域一步到位置换为 4+1 分类 13 值终态（content/reasoning/tool/data/source + tombstone 兜底），parts-first 持久化（D1a），ChatMessageType 纯 kind 化（D2a），legacy 兼容机器全删。

**Architecture:** spec `docs/superpowers/specs/2026-11-08-chat-value-domain-ideal-design.md`（已批准，D1a/D2a 裁决写入头部）是唯一设计权威；本 plan 只做任务切分与执行顺序。持久层反转：persist 点直接构建 `List<MessagePart>`，`partsJson` 为权威列；`type`/`content`/`metadata` 降级为派生投影（保留供列表气泡/查询/备份可读性）。Room v27→v28 DROP+重建 `chat_sessions`/`chat_messages` 两表（聊天历史重置，用户已批准），其余表不动；`chat_image_cache` 保留不清。

**Tech Stack:** Kotlin Multiplatform（shared commonMain/androidMain/iosMain）、Room、kotlinx.serialization、Jetpack Compose、SwiftUI、Koog 1.3.0。

**关键裁决备忘（执行中不得翻案）**
- ToolPartState 七态不动（spec §3 末条）
- tombstone = 现有 `MessagePart.Unsupported` 的改名转正：`@SerialName("unsupported")` → `"tombstone"`，类名 `Unsupported` → `Tombstone`。该 part 读时投影永不写库，改名零数据成本
- `data_ui_openui` → `data_genui_program`：`OpenUiContract.PART_TYPE` 常量 + codec + iOS 字面量同步改；server 不动（spec 非目标）
- reasoning 生产开关默认关：part 类型/渲染/编解码落地，Koog 不透出 reasoning 流则生产者挂账（spec §6.1）
- MIGRATION_24_25 / 25_26 的聊天表**数据**工作（backfill/rewrite）随 LegacyChatTypeMigration 一并删除——v28 DROP 后这些数据工作全部白做；**schema DDL（ADD COLUMN）必须保留**保证升级链不断
- iOS `chat_history_<sessionId>.json` 加 envelope 版本号（v6），旧文件整体忽略
- `chat.yaml §3.2`、ADR-016、CHAT_CARD_CATALOG §0 文档终态化是交付 DoD 的一部分

**通用验证命令（worktree 根目录）**
```bash
JITPACK=true ./gradlew :shared:jvmTest          # commonMain 改动
JITPACK=true ./gradlew :shared:iosX64Test       # iosMain 改动
JITPACK=true ./gradlew :androidApp:testDebugUnitTest  # Room/UI 改动
python3 scripts/gen-design-tokens.py --check && python3 scripts/check_doc_sync.py
# iOS 编译（必须与 gradle 串行——Build SharedKit 内嵌 gradlew）：
cd iosApp && pod install && xcodebuild -workspace PoLang.xcworkspace -scheme PoLang \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

---

### Task 1: shared 模型层终态（13 值 + Tombstone 转正 + 新 part 类型）

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePart.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsCodec.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatModelInput.kt`（回灌规则）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiContract.kt`（PART_TYPE 常量）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/MessagePartsCodecTest.kt`、`ChatModelInputTest.kt`

- [ ] **Step 1: MessagePart 终态**
  - `PartCategory` 扩 5 值：CONTENT / REASONING / TOOL / DATA / SOURCE（wireName 不变机制）
  - `Unsupported` → `Tombstone`（serial `"tombstone"`），category 归 DATA 不变，KDoc 改写为「前向兼容 + 退役占位」语义
  - `OpenUiCard` serial `"data_ui_openui"` → `"data_genui_program"`（KDoc 注明改名史）
  - 新增三个子类型（模式照抄现有 data class）：
    ```kotlin
    @Serializable @SerialName("reasoning")
    data class Reasoning(
        override val partId: String,
        val text: String,
        val state: PartState = PartState.DONE,
    ) : MessagePart { override val category get() = PartCategory.REASONING }

    @Serializable @SerialName("source_url")
    data class SourceUrl(
        override val partId: String,
        val sourceId: String,
        val url: String,
        val title: String? = null,
    ) : MessagePart { override val category get() = PartCategory.SOURCE }

    @Serializable @SerialName("source_document")
    data class SourceDocument(
        override val partId: String,
        val sourceId: String,
        val mediaType: String,
        val title: String,
        val filename: String? = null,
    ) : MessagePart { override val category get() = PartCategory.SOURCE }
    ```
  - 类 KDoc 头部值域叙述改为「13 值终态分类法」（spec §3 表）

- [ ] **Step 2: Codec 收口**
  - 删 `data_ui` 墓碑显式拦截分支（`decodePartOrUnsupported` 里 `type == "data_ui"` 提前返回）——A2UI legacy 机器出清，未知值统一走 Tombstone
  - `MessagePartsConverter.legacyCardTombstone` 调用点解除（converter 侧 Task 5 处理）
  - KDoc 更新：鉴别值域 13 值 + tombstone 语义

- [ ] **Step 3: 回灌规则**（`ChatModelInput.kt` when 分支）
  - `Reasoning` / `SourceUrl` / `SourceDocument` / `Tombstone` → `emptyList()`（剥离，对齐 Vercel convertToModelMessages 丢弃）

- [ ] **Step 4: 测试**
  - codec 全值域 13 值编解码往返 golden 表（serialNames 集合断言改为 13 值）
  - Tombstone 前向兼容钉：伪造 `data_future_x` part 编解码往返 → Tombstone(originalType="data_future_x")
  - `data_ui` 旧值现在落 Tombstone（不再提升 fallbackSummary 为 Text）——改既有断言
  - 回灌剥离断言

- [ ] **Step 5: 验证 + 提交**
  Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*MessageParts*" --tests "*ChatModelInput*"`
  Expected: PASS。`git commit -m "feat(chat): 值域终态 13 值——reasoning/source 新 part + Tombstone 转正 + data_genui_program 改名"`

### Task 2: Flattener / 渲染桶 / ChatMessageType 纯 kind 化（D2a）

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessageType.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatListFlattener.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt`（type 字段语义注释）
- 消费点全量跟随（explore 盘点报告 §3 清单）：androidApp typealias、iOS `toShared` 映射、双端渲染 when 分支
- Test: `ChatListFlattenerTest.kt`

- [ ] **Step 1: ChatMessageType 收敛纯 kind**（11 值 → 9 值，role 由消息级 `role` 列表达）
  ```kotlin
  enum class ChatMessageType {
      TEXT,               // 原 USER_TEXT/AGENT_TEXT
      IMAGE,              // 原 USER_IMAGE/AGENT_IMAGE
      IMAGE_TEXT,         // 原 USER_IMAGE_TEXT（图文混排；role 恒 user，保留独立 kind 供气泡布局）
      EDIT_RESULT,        // 原 AGENT_EDIT_RESULT
      CHART, HTML_CARD, TASK_CARD, OPTIMIZE_CANDIDATES, OPENUI_CARD,
  }
  ```
  Room type 列线值同步收敛（投影规则见 Task 3）：`text`/`image`/`tool_chart`/`tool_html`/`tool_task`/`tool_image_edit`/`data_optimize_candidates`/`data_genui_program`/`tool_browser`——**Room type 列字符串值不变**（已是 part 鉴别值口径），变的只是 Kotlin 枚举名。

- [ ] **Step 2: 消费点批量跟随**：`when (type)` 分支里 `USER_TEXT, AGENT_TEXT ->` 合并为 `TEXT ->` 并用 `role` 分流。影响面实测集中在三处（inventory §3）：Android `ChatViewModel.toUiModel()`（:3682-3711，11 值全枚举 when）+ iOS `ChatHistoryStoreCodec.wireTypeOf`/`uiTypeOf`（:128-137/:170-195）+ `ChatMessage.swift:220-240` toShared 映射 + MessageBubble 少量分支（ChatView.swift:640/647/653/738）；Room type 列是 wire 字符串不经枚举（不动）。

- [ ] **Step 3: Flattener 新桶**：`TYPE_REASONING` / `TYPE_SOURCE_CHIPS`（一条消息的连续 source parts 聚合一个 chips item）/ `TYPE_UNSUPPORTED` → `TYPE_TOMBSTONE`；`partToContentType` when 补三新 part。

- [ ] **Step 4: 测试 + 提交**：FlattenerTest 三新桶用例；`./gradlew :shared:jvmTest` 绿；commit `feat(chat): ChatMessageType 纯 kind 化（D2a）+ Flattener reasoning/source/tombstone 桶`

### Task 3: parts-first 持久层接缝（D1a 核心）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageEntity.kt`（v28 schema）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageParts.kt`（接缝反转）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageDao.kt`
- Modify: 全部 insert 调用点（**实测 23 处主源**，精确清单见 `.superpowers/sdd/2026-11-08-chat-value-domain-ideal/inventory.md` §1：ChatViewModel 17 处（:534/:731/:749/:1814/:2100/:2781/:2814/:2850/:3057/:3079/:3152/:3204/:3250/:3273/:3307/:3341/:3447）+ 6 处外围（SaveChatEditResultUseCase:45、TagDataBackupRepository:741、RemoteCommandDispatcher:202/:220、PoLangApplication:685、BrowserLiveSessionController:238））
- Test: `androidApp/src/test/.../ChatMessagePartsProjectionTest.kt`（新建）

- [ ] **Step 1: 投影纯函数**（shared commonMain 新建 `domain/chat/ChatMessageProjection.kt`）
  ```kotlin
  /** parts → Room 标量列投影（parts-first 写路径唯一派生点）。 */
  data class ChatRowProjection(val type: String, val content: String, val metadata: String?)
  fun projectChatRow(parts: List<MessagePart>, role: ModelInputRole): ChatRowProjection
  ```
  投影规则（终态，写进 KDoc）：
  - `type` = 首个 **content 或 tool 类** part 的鉴别值（reasoning/source/tombstone 永不作投影源）；全消息无 content/tool part（纯 reasoning+source）→ `"text"`
  - `content` = 首个 Text 的 markdown / Image 的 ref / 卡类的整颗 part JSON（沿 tool_browser 先例）/ EditResult description；列表气泡可读性优先
  - `metadata` = 需要结构化查询的负载（html_card meta / engineer_task / optimize group）——沿用现 converter 解析路径的反向（各 part → meta JSON）；无则 null

- [ ] **Step 2: Entity v28**：`partsJson` 改非空（`val partsJson: String`）；`type`/`content`/`metadata` KDoc 改「派生投影列（权威源 = partsJson）」。

- [ ] **Step 3: DAO 新入口**：
  ```kotlin
  suspend fun ChatMessageDao.insertPartsMessage(
      sessionId: String, role: ModelInputRole, parts: List<MessagePart>,
      modelUsed: String? = null, timestamp: Long = nowEpochMillis(),
  ): String  // 返回 messageId
  ```
  旧 `insertMessageWithParts`/`insertMessagesWithParts`（标量主写）删除；`decodePartsOrLegacy` 改名 `decodeParts()`（partsJson 非空后 legacy 回退路径删除）；`withPartsJson`/`deriveParts` 删除。

- [ ] **Step 4: 写路径翻转**（explore §1 清单逐点）：调用点从「拼 type/content/metadata 标量」改为「构建 List<MessagePart>」。形态示例（用户文本）：
  ```kotlin
  // Before: insertMessageWithParts(ChatMessageEntity(id, type="text", role="user", content=text))
  // After:  insertPartsMessage(sessionId, ModelInputRole.USER, listOf(MessagePart.Text("p0", text)))
  ```

- [ ] **Step 5: 测试**：投影纯函数全值域用例（含「reasoning 不作投影源」「排序：reasoning 在 text 前时 type 仍取 text」）；DAO 级 Robolectric 测试 insert→read 往返。`./gradlew :androidApp:testDebugUnitTest` 绿；commit `feat(chat): parts-first 持久层接缝（D1a）——标量列降级为派生投影`

### Task 4: Room v27→v28 + legacy 迁移机器出清

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`（version 28 + MIGRATION_27_28 + 24_25/25_26 瘦身）
- Delete: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/LegacyChatTypeMigration.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsConverter.kt`（退役为纯投影辅助或整删，视 Task 3 落地形态）
- Modify: 回放过滤点（explore §4 清单：loadMessages/rebuildChatMemoryIfEmpty/iOS codec filter）
- Test: `androidApp/src/test/.../Migration2728Test.kt`（新建）

- [ ] **Step 1: MIGRATION_27_28**：DROP `chat_sessions` + `chat_messages`，按 v28 entity 重建；`chat_image_cache` 不动。同迁移内清 Koog 短期轨 DataStore 键（`koog_memory_*`）——Room Migration 拿不到 DataStore，改在 Application onCreate 版本哨兵（SharedPreferences `chat_value_domain_v28_done`）处清，一次性。
- [ ] **Step 2: 24_25/25_26 瘦身**：删 `backfillChatMessageParts`/`migrateChatMessageTypes` 数据工作（保留 ADD COLUMN DDL）；`ChatMessageParts.kt` 相应函数删。
- [ ] **Step 3: LegacyChatTypeMigration 全文件删** + 全仓引用清零（grep 验证）。
- [ ] **Step 4: 迁移测试**：v27 造旧库（chat 两表有数据 + media_assets/memory_facts 有数据）→ 升 v28 → chat 两表空、非聊天表内容保留、chat_image_cache 保留。
- [ ] **Step 5: 验证 + 提交**：`./gradlew :androidApp:testDebugUnitTest --tests "*Migration*"` 绿；commit `feat(chat): Room v28 聊天表重置 + legacy 迁移机器出清`

### Task 5: iOS 对等（codec 字面量 / envelope v6 / legacy 推断删 / 渲染）

**Files:**
- Modify: `shared/src/iosMain/kotlin/com/mamba/picme/domain/chat/ChatHistoryStoreCodec.kt`
- Modify: `iosApp/PoLang/Features/Chat/ChatMessage.swift`、`ChatHistoryStore.swift`、`ChatView.swift`
- Test: `shared/src/iosTest/kotlin/com/mamba/picme/domain/chat/IosChatHistoryStoreCodecTest.kt`

- [ ] **Step 1**: codec 字面量 `data_ui_openui`→`data_genui_program`、`unsupported`→`tombstone`；删 legacy 过滤分支（RETIRED mediaResults 行级滤除——v28 后不存在）
- [ ] **Step 2**: history 文件 envelope：`{"version":6,"messages":[...]}`；读侧 version<6 整体忽略（重置语义对齐 Android）
- [ ] **Step 3**: Swift `ChatMessage` legacy role+text 推断分支删；MessageType 同步纯 kind
- [ ] **Step 4**: SwiftUI 渲染：reasoning 可折叠块（「思考过程」）+ source chips 行 + tombstone 占位卡（复用 UnsupportedPartCard 改名对等）
- [ ] **Step 5**: xcstrings 五语同步（iOS 侧文案）；`JITPACK=true ./gradlew :shared:iosX64Test` + xcodebuild build 绿；commit `feat(chat): iOS 值域终态对等——envelope v6 + legacy 删 + 三新卡渲染`

### Task 6: Android 渲染（reasoning 折叠块 + source chips + tombstone 卡）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（contentType 分发）
- Rename: `components/UnsupportedPartCard.kt` → `TombstonePartCard.kt`（文案不变：「此内容需新版本 App 查看」）
- Create: `components/ReasoningPartBlock.kt`（可折叠「思考过程」，默认折叠，样式参照 ChatGPT reasoning UI：浅灰斜体前导 + 展开动画）
- Create: `components/SourceChipsRow.kt`（序号 + 域名 + 点击 `CustomTab`/浏览器打开）
- Modify: `androidApp/src/main/res/values*/strings.xml` 五语
- Test: Flattener 桶已由 Task 2 覆盖；渲染走既有截图基线机制（若有）

- [ ] **Step 1-4**: 渲染件 + 分发 + 五语 + i18n 校验（`skills/i18n-validator` 口径：禁硬编码）
- [ ] **Step 5**: `./gradlew :androidApp:testDebugUnitTest` + ai-gate 绿；commit `feat(chat): Android reasoning/source/tombstone 渲染三件套`

### Task 7: 流式 reasoning 事件族 + web_search source 抽取挂点

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/streaming/TurnStreamEvent.kt`（+ReasoningStart/Delta/End）
- Modify: `streaming/TurnPartsReducer.kt`（reasoning part 装配，排在 text 前）
- Modify: `shared/.../inference/remote/`（KoogChatAgent/RemoteChatEngine）：Koog 1.3.0 reasoning_content 透出探针——**有则接线，无则留 `// reasoning 生产者挂账（Koog 不透出）` 注释 + 事件族空置**
- Modify: web_search 结果链（explore §9 挂点）：引擎层从 DeepSeek Search 代理结果抽 ≤5 条 → SourceUrl parts 挂当轮 assistant 消息（正文 markdown 图链行为不变）
- Test: `TurnPartsReducerTest` reasoning 装配 + source 抽取用例

- [ ] **Step 1-3** 实现 + 测试 + commit `feat(chat): reasoning 流式事件族 + web_search source parts 抽取`

### Task 8: 备份恢复 v6 粗映射

**Files:**
- Modify: 备份模块（explore §6 定位，版本号 v5→v6）
- Test: v5 旧包 → v6 恢复粗映射测试

- [ ] **Step 1**: 备份模块内一次性粗映射表（旧 type→新值：text/image/tool_* 直通；data_ui/data_media_results/legacy 13 值 → tombstone 行或跳过）；运行时零 legacy 知识（映射表只活在备份模块）
- [ ] **Step 2**: 测试 + commit `feat(chat): 备份恢复 v6——聊天段一次性粗映射`

### Task 9: 文档终态化 + golden 重生 + 全量验证

**Files:**
- Modify: `docs/02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md`（值域史收编为终态叙述 + 命名规约全文修订版）
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md` §0 重写终态值域表（13 值 + tombstone，去退役编年）
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_PROTOCOL_TECH_SPEC.md`、`shared/AGENTS.md` §2、`docs/08-UI-SPECS/chat.yaml §3.2` 渲染矩阵
- golden 重生：`POLANG_WRITE_GOLDEN=1`（若 prompt 面受影响）+ 逐行核对

- [ ] **Step 1-3**: 文档 + `python3 scripts/check_doc_sync.py` 绿 + 全量终验（jvmTest/iosX64Test/androidApp test/ai-gate/xcodebuild）
- [ ] **Step 4**: commit `docs(chat): 值域终态化文档收编`

---

## Self-Review 记录

- **Spec 覆盖**：§3 值域 → Task 1/2；§4.1 D1 → Task 3；D2 → Task 2；§4 历史债出清 → Task 4/5/8；§5 前向兼容 → Task 1（Unsupported 转正即此机制，测试钉住）；§6.1 reasoning → Task 1+6+7；§6.2 source → Task 1+6+7；§7 影响面 → Task 1-9 全覆盖；§8 测试策略 → 各 Task 测试步
- **已知简化（对 skill 规范的偏离说明）**：步骤粒度为任务级而非 2-5 分钟微步——执行者为本会话 K3 coder 子 agent，spec 已承载完整设计细节（DRY，plan 不复制 spec 全文）
- **类型一致性**：`Tombstone`/`Reasoning`/`SourceUrl`/`SourceDocument`/`projectChatRow`/`insertPartsMessage`/`ChatRowProjection` 命名在 Task 间一致
