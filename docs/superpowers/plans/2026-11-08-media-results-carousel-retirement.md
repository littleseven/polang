# 横滑相册卡（MediaResultsCarousel）退役实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 整体退役 chat 原生横滑相册卡与其插卡机制，「看照片」交付物统一为技能驱动的富媒体卡（云端 OpenUI / 端侧 render_html）。

**Architecture:** 按 spec 删除集逐层拆除：shared 契约面先行（数据模型 → 意图路由 → 工具面/prompt/golden），Android 全链路跟随，iOS 同步（PARITY），server prompt/技能与文档收口殿后。

**Tech Stack:** Kotlin Multiplatform（shared commonMain/iosMain）、Compose（androidApp）、SwiftUI（iosApp）、Ktor（server）、kotlinx.serialization。

**Spec:** `docs/superpowers/specs/2026-11-08-media-results-carousel-retirement-design.md`（同目录，executor 必读）

## Global Constraints

- 工作区：`.worktrees/retire-media-carousel/`，分支 `feat/retire-media-carousel`，基于 main `e019813e5`；只 add 本任务文件。
- [I18N] 五语 strings（values/values-zh-rCN/values-zh-rTW/values-es/values-fr）同步删，禁止留孤儿键。
- [PARITY] iOS 与 Android 同批退役，不允许先合 Android。
- [DOC-SYNC] 文档改动与代码同分支，check_doc_sync.py 必须绿。
- golden 重生统一在 Task 3 做（`POLANG_WRITE_GOLDEN=1`），重生后人工 review diff 才准继续。
- `ignoreUnknownKeys` 不覆盖未知密封类鉴别值——历史行跳过必须显式行级过滤（spec D2），禁止依赖 codec 容错。
- 构建命令：`JITPACK=true ./gradlew :shared:jvmTest` / `:androidApp:compileDebugKotlin`（任务名无 Standard 变体）/ `./gradlew -p server test`（server 是独立构建，无 `:server:` 路径）。
- MediaPager 组件本体保留（GalleryScreen/MemoryDetailScreen 消费）；OpenUI Carousel/PhotoGrid 组件、Memory 页横滑、LlmCallLogScreen pager 不动。
- search_media/refine_media_search 工具本体、lastResultAssets 基数语义、MediaFeedbackUseCase 存储层（MediaSearchEngine 排序消费）保留。

## Review Focus

1. **历史 `data_media_results` 行回放**：老 Room 行若未被显式跳过，会渲染成原始 JSON 气泡（spec D2 机制修正）——Task 1/4 的行级过滤 + Task 6 兼容测试钉住。
2. **VIEW_PHOTOS 回合出不了卡**：契约重指后 allowedTools 若漏 `render_html`，子集闸门内 LLM 物理上无法出卡——Task 2 的契约断言测试钉住。
3. **feedback 三件套删除后的悬空引用**：`dispatchCommand` 的 FeedbackTarget/FeedbackAction 解析、capability 注册表、命令 chip 文案若有残留引用会编译断或工具空转——Task 3/4 编译 + 全仓 grep `Feedback` 扫尾。
4. **iOS legacy 数据推断**：`ChatMessage.swift` 的「assistant + mediaIds → mediaResults」老数据推断若不删，退役后老会话仍试图重建卡数据——Task 8 显式删除并编译验证。
5. **云端 cross-version**：新 server（无 display schema）+ 老端（1.0.86-）直执路径仍在老端本地，行为不受 server 影响；新端 + 老 server 时 LLM 可能传 display='true' 被新端忽略——可接受（spec D3），不阻塞，但 Task 9 的 SKILL.md 必须先把 LLM 引导到 display='false' 范式。

---

### Task 1: shared 数据模型删除 + 历史行跳过契约

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePart.kt`（删 MediaResults 子类，:113-121）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsConverter.kt`（删 :117-118 分支与 :148-174 parseMediaResults）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsCodec.kt:17`（注释枚举更新）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt`（删 mediaResults 字段 :33-35 与 MediaResultsUi 定义 :74-85）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatListFlattener.kt`（删 TYPE_MEDIA_RESULTS 常量 :253 与 contentTypeOf 分支 :207）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatModelInput.kt:17,126`（删 MediaResults 回灌丢弃分支——类型没了自然删）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/LegacyChatTypeMigration.kt:19,33`（删 media_results→data_media_results 映射项，KDoc 注明退役）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/`（MessagePartsCodecTest/ConverterTest/LegacyChatTypeMigrationTest/ChatModelInputTest/ChatListFlattener 相关用例删除或改写）

**Interfaces:**
- Produces: 「退役 type 闭集」约定——`data_media_results` 与 legacy `media_results` 两个 type 字符串为历史残留，双端回放路径遇之跳过整行（Task 4 Android / Task 7 iOS 消费本约定）。建议在 `LegacyChatTypeMigration` companion 加 `val RETIRED_MEDIA_RESULTS_TYPES = setOf("media_results", "data_media_results")` 作为双端过滤的唯一引用点。

- [ ] **Step 1:** 在 `LegacyChatTypeMigration` 加 `RETIRED_MEDIA_RESULTS_TYPES` 闭集常量 + KDoc（退役口径：回放跳过，不渲染不迁移）。
- [ ] **Step 2:** 删 MediaResults part 类与全部转换/拍平/回灌分支；ChatMessage 删 mediaResults 字段与 MediaResultsUi（注意 ChatMessage 的 copy/make 透传参数同步删）。
- [ ] **Step 3:** 更新受影响测试：MessagePartsCodecTest(:95-112,250,273-275) 删 MediaResults 编解码用例与鉴别值枚举断言；ConverterTest(:59,77) 删；LegacyChatTypeMigrationTest(:27,63) 改写为「映射表不含 media_results」；ChatModelInputTest(:162,180) 删 data part 丢弃用例（类型已不存在）。
- [ ] **Step 4:** 运行 `JITPACK=true ./gradlew :shared:jvmTest` —— 全绿（androidApp 此时编译会断，预期内，Task 4 修复；shared 独立编译必须先绿）。
- [ ] **Step 5:** Commit `refactor(chat): 退役 data_media_results 数据模型（spec D2）`。

### Task 2: 意图契约重指 + 直执路由删除

**Files:**
- Modify: `shared/.../agent/core/intent/ChatIntentContract.kt`（UiArtifact 删 MEDIA_RESULTS_CARD :57；VIEW_PHOTOS :87-94 与 REFINE_RESULTS :96-100 重指 uiContract=RICH_HTML_CARD、allowedTools +`render_html`、ruleText 改写为「search_media 取数 → render_html 出图集卡（media://{id} 引用）；可先 load_skill photo-browse 取工作流」；REFINE_RESULTS allowedTools 补 `render_html`；ANALYZE_STATS ruleText 删「否则会触发补卡」尾句）
- Modify: `shared/.../agent/core/intent/ChatRoutingPolicy.kt`（删 DirectSearch/DirectRefine 路由分支与 :20 注释；toolSubsetFor 逻辑保留——子集闸门仍生效）
- Modify: `shared/.../agent/core/intent/IntentRouter.kt:209-211`（ROUTER_SYSTEM_PROMPT 的 VIEW_PHOTOS 交付物描述改「图集卡（render_html）」）
- Modify: `shared/.../agent/core/inference/remote/RemoteChatEngine.kt`（删 routeAndMaybeExecute/executeDirect/:237-330 整段直执链与 :251 MEDIA_RESULTS_CARD 合成；VIEW_PHOTOS/REFINE_RESULTS 一律走 LLM 回合）
- Modify: `shared/.../agent/core/inference/remote/StreamChatResult.kt`（删 directReply/DirectRouteReply :22-36）
- Test: `shared/src/commonTest/.../intent/ChatIntentContractTest.kt`、`ChatRoutingPolicyTest.kt`、`RemoteChatEngineToolSubsetTest.kt`

**Interfaces:**
- Consumes: Task 1 的类型删除（MEDIA_RESULTS_CARD 引用清零是编译前提）。
- Produces: VIEW_PHOTOS/REFINE_RESULTS 的新契约——`uiContract = UiArtifact.RICH_HTML_CARD`，allowedTools 分别为 `listOf("search_media", "refine_media_search", "list_person_relations", "render_html")` 与 `listOf("refine_media_search", "search_media", "render_html")`（load_skill/finish/delay 由 resolveToolSubset 恒 union，不入表）。RemoteChatEngine 不再导出 DirectRouteReply。

- [ ] **Step 1:** ChatIntentContractTest 先改：断言 VIEW_PHOTOS allowedTools 含 render_html、UiArtifact 闭集无 MEDIA_RESULTS_CARD、allowedTools∩forbiddenTools=∅ 仍成立。
- [ ] **Step 2:** 跑测试确认红（契约未改）。
- [ ] **Step 3:** 改 ChatIntentContract/ChatRoutingPolicy/IntentRouter/RemoteChatEngine/StreamChatResult。RemoteChatEngine 直执链删除后检查 processChatReAct 调用点：直执回落为正常 LLM 回合，删除 routeAndMaybeExecute 的前置分流即可。
- [ ] **Step 4:** ChatRoutingPolicyTest(:41) 的 MEDIA_RESULTS_CARD 状态改用 RICH_HTML_CARD；RemoteChatEngineToolSubsetTest(:62-95) 直执断言删、search_media 子集断言保留并补 render_html。
- [ ] **Step 5:** `:shared:jvmTest` 绿 → Commit `refactor(intent): VIEW_PHOTOS/REFINE_RESULTS 契约重指 RICH_HTML_CARD + 直执路由删除（spec D1/D7）`。

### Task 3: 工具面——反馈三件套删除 + 描述改写 + display 下线 + golden 重生

**Files:**
- Modify: `shared/.../remote/tool/ChatToolService.kt`（删 record_feedback :198-208/more_like_this :210-214/exclude_constraint :216-222 三个 @Tool 及 parseFeedbackTarget/parseFeedbackAction :653-670；dispatchCommandDetailed :595-626 删 uiActions.tryEmit 发射段与「已展示在卡片中」回执文案；search_media :131-132/refine :144-145/run_gallery_script :275/render_html :313-315 描述去「横滑卡片/补卡」教学，search_media 改写为「取数 + render_html 出图集卡」范式）
- Modify: `shared/.../remote/tool/ChatToolManifest.kt`（头注释 :15-16 与 search_media :87-88/refine :96-97 目录条目描述同步改写；确认清单是否含反馈三件套条目，有则删）
- Modify: `shared/.../remote/tool/CloudAgentToolContract.kt`（删 DESC_PARAM_DISPLAY :81 与 DESC_SEARCH_MEDIA :71 中 display 段）
- Modify: `shared/.../remote/tool/CloudAgentToolCatalog.kt`（删 search_media 条目的 display 参数 :74-89）
- Modify: `shared/.../remote/prompt/ChatPromptRules.kt`（:41/:85/:136-145/:151 段改写——删补卡教学，多轮窄化段改「上一轮结果卡片」为「上一轮搜索结果」口径）
- Modify: `shared/.../model/context/AgentModels.kt:187-202`（删 AgentAction.MediaResults；先 grep 全仓 `AgentAction.MediaResults` 引用点随删）
- Golden: `shared/src/jvmTest/resources/golden/chat_system_prompt_golden.txt`、`chat_tool_inventory_golden.txt`
- Test: `CloudAgentToolCatalogTest.kt`（:37,113,147-171 display 断言删）、`ChatToolManifestConsistencyTest`/`ChatToolManifestDescriptorTest`（随 golden 校验）、`CommandRiskTest`（search_media 风险分级提及若涉反馈工具同步删）

**Interfaces:**
- Consumes: Task 2 的契约重指（ruleText 语义锚已更新）。
- Produces: 工具面终态——无 record_feedback/more_like_this/exclude_constraint；search_media 参数序无 display；golden 快照为本分支唯一权威。

- [ ] **Step 1:** 删三个反馈 @Tool 与解析函数，grep `record_feedback|more_like_this|exclude_constraint|FeedbackTarget|FeedbackAction|AgentAction.MediaResults` 全仓扫引用随删（capability 注册表/命令映射/Android handler 在 Task 4，此处只保证 shared 编译自洽）。
- [ ] **Step 2:** 改写五处工具描述 + ChatPromptRules 四段 + Manifest 同步；改 CloudAgentToolContract/Catalog 删 display。
- [ ] **Step 3:** `POLANG_WRITE_GOLDEN=1 JITPACK=true ./gradlew :shared:jvmTest --tests "*ToolPromptDeterminismTest*" --tests "*ChatSystemPromptGoldenTest*"` 重生 golden，**人工 review diff**（应只见：三工具消失、display 参数消失、横滑文案消失、render_html 进 VIEW_PHOTOS 相关描述）。
- [ ] **Step 4:** 全量 `:shared:jvmTest` 绿 → Commit `refactor(tools): 反馈三件套退役 + display 参数下线 + 横滑卡 prompt 教学清除（spec D3/D6）`。

### Task 4: Android ChatViewModel 插卡机制拆除 + 回放跳过 + FIND_SIMILAR 改道

**Files:**
- Modify: `androidApp/.../features/chat/ChatViewModel.kt`（删除集见 spec §2.1「插卡机制」全列；lastResultAssets 保留但删补卡读取点；`clearChat`/:1718 deleteSession 清理列表同步删 pendingScriptMediaIds/pendingFeedbackActions，lastResultAssets/sessionSearchSnapshots 保留）
- Modify: `androidApp/.../features/chat/capability/ChatSearchCapability.kt:98-118,167-178`（删 toMediaResults 产出，SearchMedia/RefineMediaSearch 命令仍返回搜索文本结果；MoreLikeThis 命令随反馈三件套删除）
- Modify: `androidApp/.../features/chat/capability/ChatRunScriptCapability.kt:109-117`（删 ids 补卡警告文案）
- Modify: `androidApp/.../features/chat/ChatGallerySearch.kt:180-229`（删 serialize/deserialize）
- Modify: `androidApp/.../data/local/ChatMessageDao.kt:48-61`（删 getLatestMediaResultsSinceLastUserMessage）、`ChatMessageEntity.kt:23-24`（注释更新）
- Modify: `ChatViewModel.kt:4216,4238-4239` 回放映射（遇 `LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES` 跳过整行）
- Modify: `androidApp/.../core/agenttools/CloudAgentGalleryTools.kt`（删 mediaResultsListener/CARD_SHOWN_NOTE/refineDisplay/:166-184/:218-220 透出逻辑；searchMedia/refineMediaSearch 取数与投影保留，display 入参忽略或随 DeviceToolExecutor 同步删）
- Modify: `androidApp/.../core/agenttools/DeviceToolExecutor.kt:32-43`（display 取参删）
- Modify: `androidApp/.../di/AppContainer.kt:1231-1259`（mediaResultsListener 接线删）
- Modify: `ChatViewModel.kt:3795-3826` sendImageWithIntent FIND_SIMILAR（插卡删除 → 命中后把「以图搜图命中 N 张、ids 已入搜索基数」作为上下文触发正常 LLM 回合，由 LLM 经 render_html 出卡；未命中保持原文案提示）

**Interfaces:**
- Consumes: Task 1 `RETIRED_MEDIA_RESULTS_TYPES`；Task 3 的工具面终态（dispatch 不再发射 AgentAction.MediaResults）。
- Produces: ChatViewModel 无 insertMediaResultsMessage/settleScriptMediaIds/onMediaFeedback 等符号；uiActions 收集器（:1447-1468）删除。

- [ ] **Step 1:** 删 uiActions 收集器与 insertMediaResultsMessage 定义及全部 7 处调用点；删 settle/capture/pendingScriptMediaIds 链（onRunScript :3050-3053 的 ids 登记一并删）。
- [ ] **Step 2:** 删反馈链（onMediaFeedback/updateCurrentResultsFeedback/reorderAssetsByFeedback/triggerMoreLikeThis/pendingFeedbackActions/mediaResultsMutex/MAX_CARDS）与 removeMediaResultAsset、误拒补卡（:2042-2059）、searchGalleryDirectly 补卡、runCloudTurn listener 挂接（:2191-2209）。
- [ ] **Step 3:** FIND_SIMILAR 改道；回放跳过过滤；CloudAgentGalleryTools/DeviceToolExecutor/AppContainer 透出拆除。
- [ ] **Step 4:** `JITPACK=true ./gradlew :androidApp:compileDebugKotlin` 绿（残留引用逐一清零）。
- [ ] **Step 5:** Commit `refactor(android): 横滑卡插卡机制整体拆除 + 历史行回放跳过（spec D2/D6）`。

### Task 5: Android 渲染面 + strings + tokens + 配置清理

**Files:**
- Delete: `androidApp/.../features/chat/components/MediaResultsCarousel.kt`
- Modify: `androidApp/.../features/chat/ChatScreen.kt`（:218 import、:727-742 分派、:395-396 previewAssets/previewIndex、:1150-1166 MediaPager 浮层、:506-535 删图联动、:2919 注释——MediaPager import 若无其它消费一并删）
- Modify: `androidApp/.../features/cloudagent/CloudAgentChatScreen.kt:69,265-271` 与 `CloudAgentChatViewModel.kt:50,163-179,250`（Role.MEDIA 消息与 listener 全删）
- Modify: 五语 strings.xml（删 spec §2.1 strings 行所列卡片专属键；chat_cmd_search_media/refine 保留）
- Modify: `androidApp/detekt-baseline.xml:1134`、`androidApp/compose-stability.conf:16,58`
- Modify: `shared/src/commonMain/resources/design-tokens.json`（删 chatCarousel 段 :523-533 与 :221 注释提及）+ `scripts/gen-design-tokens.py:70,81,86,333`（注册表同步）→ 跑 codegen 重生双端镜像（Android `DesignTokens.kt:269-279` ChatCarouselTokens 消失）
- Modify: `androidApp/.../features/common/chat/AgentChatComponents.kt:287-288`（确认 chip 文案键仍被命令面板消费则保留）

- [ ] **Step 1:** 删 renderer 与两个挂载点；compileDebugKotlin 绿。
- [ ] **Step 2:** 五语 strings 删键 → grep 确认零引用（含 layout/代码）。
- [ ] **Step 3:** design-tokens.json 删段 + codegen 注册表 + 重生镜像 + `python3 scripts/gen-design-tokens.py --check` 绿。
- [ ] **Step 4:** `./gradlew :androidApp:detekt`（或 ai-gate 内含）绿。
- [ ] **Step 5:** Commit `refactor(android): MediaResultsCarousel 渲染面与 chatCarousel token 段退役`。

### Task 6: Android 测试更新 + 兼容测试 + 全量门禁

**Files:**
- Test: `androidApp/src/test/.../data/local/ChatMessageDaoTest.kt`（:45-102 八用例随 DAO 查询删除）、`ChatMessagePartsTest.kt:149`、`ChatHistoryModelInputTest.kt:112`、`CloudAgentGalleryToolsTest.kt:158-239`（display 门控组删，取数/投影断言保留）、`ChatViewModelSearchIntentSanitizerTest.kt:43-243`（补卡断言删，意图清洗/基数断言保留）、`CloudAgentTurnRunnerTest.kt`/`CloudAgentChatViewModelTest.kt`/`DeviceToolExecutorTest.kt`/`CloudAgentClientTest.kt`（弱相关，随编译修）
- Test: `androidApp/src/androidTest/.../ChatMessagePartsMigrationTest.kt:106,194`（迁移断言更新——media_results 映射项已删，老值原样保留）
- Test 新增: `ChatViewModel` 回放跳过测试（构造 type=data_media_results 的 entity → 回放列表无该行）

- [ ] **Step 1:** 新增回放跳过兼容测试（红→绿）。
- [ ] **Step 2:** 其余测试随删除集更新。
- [ ] **Step 3:** `./gradlew :androidApp:testDebugUnitTest` 绿 + `:shared:jvmTest` 绿。
- [ ] **Step 4:** `./scripts/ai-gate.sh` 全绿 → Commit `test(android): 横滑卡退役测试收口 + 历史行回放兼容`。

### Task 7: shared iosMain + iosTest

**Files:**
- Modify: `shared/src/iosMain/.../ChatHistoryStoreCodec.kt:70,115,229-233`（删 mediaResults 字段/wire 值/重建分支；遇 data_media_results/media_results 行跳过——引用 Task 1 闭集）
- Modify: `shared/src/iosMain/.../inference/remote/ChatUiActionDto.kt:19-52`（删 media_results kind 映射；若整个 DTO 只服务 media_results 则整文件删）
- Modify: `shared/src/iosMain/.../capability/IosChatGalleryCapability.kt`（删 toMediaResults :241-248 与 AgentAction.MediaResults 产出；搜索执行/lastResultAssets 等价物 :54 保留）
- Modify: `shared/src/iosMain/.../capability/IosCloudAgentToolBridge.kt:60-72,92-111`（display 维度本就不读，注释更新）
- Modify: `shared/src/iosMain/.../inference/remote/IosChatPrompt.kt:30-45`（确认 search/refine 规则文案与新范式一致）
- Test: `shared/src/iosTest/.../IosAgentCompositionTest.kt:84-104`（media_results DTO 用例删）、`IosChatGalleryCapabilityTest.kt:129-152`（产出断言改）、`IosChatPromptTest.kt`（随 prompt 校验）

- [ ] **Step 1:** iosMain 删除集 + iosTest 更新。
- [ ] **Step 2:** `JITPACK=true ./gradlew :shared:assemble`（iOS metadata 编译）+ 若有 iosTest 任务可跑则跑。
- [ ] **Step 3:** Commit `refactor(ios-shared): iosMain media_results 通路退役`。

### Task 8: iosApp SwiftUI 面（PARITY 同步）

**Files:**
- Modify: `iosApp/PoLang/Features/Chat/ChatView.swift`（:802 分派、:848-872 mediaResultsItem、:1053-1114 MediaCardRow、:302,370-375 onMediaTap 预览）
- Modify: `iosApp/PoLang/Features/Chat/ChatViewModel.swift:549-597`（handleUiAction media_results upsert 删；:577-579 FIND_SIMILAR TODO 随删）
- Modify: `iosApp/PoLang/Features/Chat/ChatMessage.swift`（:36,56,76,85,136,199-200,216-250,284 mediaResults 字段/make/copy/老数据推断/legacy 重建全删）
- Modify: `iosApp/PoLang/DesignSystem/DesignTokens.swift:501-512`（ChatCarouselTokens 随 codegen 重生消失——iOS 镜像由 Task 5 的 codegen 一次产出）
- Modify: `iosApp/PoLangUITests/ChatSpecUITests.swift:113-116,325`（chat_media_row/chat_media_card 断言删）

- [ ] **Step 1:** Swift 面删除集。
- [ ] **Step 2:** xcodebuild 编译绿（按 ios-build-debug skill 流程）。
- [ ] **Step 3:** Commit `refactor(ios): MediaCardRow 与 media_results 通路退役（PARITY 同步）`。

### Task 9: server prompt/技能 + 0.9.43

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiPromptAssets.kt:431`（删「而非 search_media display='true' 横滑轮播」对比，保留首选范式引导）
- Modify: `server/skills/photo-browse/SKILL.md`（双形态重写：display 参数已下线；云端 turn-native → OpenUI 程序分支；端侧有 render_html 工具 → HTML 卡 + media://{id} 图集分支；检查清单同步）
- Modify: `server/skills/album-maker/SKILL.md:28`（display 说明同步删除/改写）
- Modify: `server/build.gradle.kts`（version 0.9.42→0.9.43）
- Test: `server/src/test/.../DeviceToolDescriptionTest.kt`/`DeviceToolServiceTest.kt`/`AgentRoutesBridgeTest.kt`（display 相关断言随删）

- [ ] **Step 1:** prompt 资产与 SKILL.md 重写（技能正文是 LLM 契约，措辞照 spec D1 双形态）。
- [ ] **Step 2:** bump 0.9.43 → `./gradlew -p server test` 绿。
- [ ] **Step 3:** Commit `refactor(server): display 范式下线 + 技能双形态（0.9.43）`。
- [ ] **Step 4:** 合并 main 后 `JITPACK=true ./server/deploy.sh` 部署 + healthz 验证 0.9.43（双交付铁律）。

### Task 10: 文档收口 + 合并

**Files:**
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`（§4.4 整节删 + :38,50,140-142,207,298,316 索引行更新）
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_PROTOCOL_TECH_SPEC.md`（:204-205 出卡路径表、:273,297,318,329 时序/透出/回灌行更新）
- Modify: `docs/01-PRODUCT/FEATURES.md:298,372,478`
- Modify: `docs/08-UI-SPECS/screens/chat.yaml`（§9 :533-554 整节删 + :5,139,157-158,178,604,610,726,831-837 更新）、`cloud-agent.yaml:59-60`
- Modify: `docs/02-ARCHITECTURE/ADR/ADR-015-intent-routing-contract.md:13,16` 与 `ADR-016:37,41`（活文档：契约表/parts 子类型表更新，注明横滑卡 2026-11-08 退役）
- Modify: `shared/AGENTS.md:66`、`androidApp/AGENTS.md:231`、`androidApp/.../features/common/chat/AGENTS.md:106,136`、`server/AGENTS.md:39,115,136`
- Modify: `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md:92`、`COMMAND_REFERENCE.md:215`、`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md:393`、`docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md:209`、`docs/01-PRODUCT/IOS_PRODUCT_REFERENCE.md:114`
- Modify: `docs/08-UI-SPECS/screens/refs/ardot/components.json`（chatCarousel 引用清理）
- Delete: 本 spec 与 plan（沉淀管线：结论落位后 git rm；决策 → ADR-015/016 已含；交互 → chat.yaml；实现约束 → 模块 AGENTS.md）

- [ ] **Step 1:** 逐文档更新。
- [ ] **Step 2:** `python3 scripts/check_doc_sync.py` 六项绿 + `./scripts/ai-gate.sh` 绿。
- [ ] **Step 3:** Commit `docs: 横滑卡退役文档收口`。
- [ ] **Step 4:** 最终回归（`:shared:jvmTest` + `:androidApp:compileDebugKotlin` + `./gradlew -p server test` + `:shared:assemble`）→ 合 main（版本 bump 1.0.87 随合并提交）。
