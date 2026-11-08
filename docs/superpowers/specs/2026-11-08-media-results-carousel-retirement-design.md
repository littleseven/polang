# 原生横滑相册卡（MediaResultsCarousel）退役设计

> **状态**：设计定稿待实施
> **日期**：2026-11-08
> **触发**：用户指令「原生的横滑相册卡片可以退役了，插入动作也是没有必要的了」
> **范围**：Android + iOS + shared + server prompt/技能 + design tokens + 文档

## 1. 背景与意图

chat 内「看照片」的交付物经历了三代：

1. **原生横滑卡（本次退役对象）**：`MediaResultsCarousel` Compose 原生渲染，`data_media_results` data part 落库，`search_media`/`refine_media_search` 工具直出卡片，端侧还有一整套插卡机制（uiActions 通道、脚本 ids 补卡、REPLACE upsert「一回合至多一卡」、意图误拒补卡、以图搜图插卡、云端 display='true' 透出）。
2. **OpenUI 卡**：云端 turn-native 范式（2026-11-06 唯一化），PhotoGrid/ImageText 等组件排版，photo-browse / album-maker 技能教 LLM 工作流。
3. **端云同构 skill（2026-11-08 落地）**：端侧 `load_skill` 执行器上线，端侧 LLM 也能拉取 photo-browse / album-maker 技能，按同样工作流出 OpenUI/HTML 卡。

第 2+3 代齐备后，第 1 代的存在只剩负资产：双渲染管线并存（原生卡 vs OpenUI 卡形态不一致）、插卡机制是 ChatViewModel 里最复杂的状态机之一（lastResultAssets 12 处读写、补卡护栏 a/c、反馈防抖、删图联动）、prompt 面大量篇幅教「什么时候不要补卡」。server prompt 已在引导弃用（`OpenUiPromptAssets`：「首选此范式，而非 search_media display='true' 横滑轮播」）。

**意图**：整体退役原生横滑卡与其插卡机制，「看照片」交付物统一为 LLM 产出的 OpenUI/HTML 卡（技能驱动）。

## 2. 删除集 / 保留集

### 2.1 删除（核心集）

| 层 | 删除内容 |
|---|---|
| **Android renderer** | `features/chat/components/MediaResultsCarousel.kt` 整文件；`ChatScreen.kt` 挂载分支（TYPE_MEDIA_RESULTS 分派、previewAssets/previewIndex、MediaPager 浮层、删图联动 506-535）——**MediaPager 组件本体保留**（GalleryScreen/MemoryDetailScreen 共享消费，builder-check 实证）；`CloudAgentChatScreen.kt`/`CloudAgentChatViewModel.kt` Role.MEDIA 挂载与 listener 挂接 |
| **插卡机制** | ChatViewModel：`insertMediaResultsMessage`、`settleScriptMediaIds`/`captureScriptMediaIds`/`pendingScriptMediaIds`、意图误拒补卡（2042-2059）、`handleAgentAction` MediaResults 分支、`triggerMoreLikeThis`、`onMediaFeedback`/`updateCurrentResultsFeedback`/`reorderAssetsByFeedback`/`pendingFeedbackActions`、`removeMediaResultAsset`、`mediaResultsMutex`、MAX_CARDS、`searchGalleryDirectly` 补卡；`runCloudTurn` 的 mediaResultsListener 挂接 |
| **uiActions 通道** | `AgentAction.MediaResults`（AgentModels.kt:187-202）；`ChatToolService.dispatchCommandDetailed` 的 uiActions 发射（595-626 相关段）；`ChatSearchCapability` 的 toMediaResults 产出；`ChatUiActionDto` media_results 映射（iosMain） |
| **直执路由** | `RemoteChatEngine.routeAndMaybeExecute`/`executeDirect`（DirectSearch/DirectRefine）、`StreamChatResult.directReply`/`DirectRouteReply`、`ChatRoutingPolicy` DirectSearch/DirectRefine；VIEW_PHOTOS/REFINE_RESULTS 回落为普通 LLM 回合 |
| **display 参数** | `CloudAgentToolContract.DESC_PARAM_DISPLAY` + DESC_SEARCH_MEDIA 中 display 段；`CloudAgentToolCatalog` search_media 的 display 参数；`DeviceToolExecutor` display 取参；`CloudAgentGalleryTools` 的 mediaResultsListener/CARD_SHOWN_NOTE/refineDisplay 透出逻辑（searchMedia/refineMediaSearch 取数本体保留） |
| **数据模型** | `MessagePart.MediaResults`、`MessagePartsConverter.parseMediaResults` 分支、`ChatListFlattener` TYPE_MEDIA_RESULTS 常量与分派、`ChatMessage.mediaResults` 字段、`MediaResultsUi`、`ChatGallerySearch` 序列化/反序列化、`ChatMessageDao.getLatestMediaResultsSinceLastUserMessage`、entity 注释；`LegacyChatTypeMigration` 的 media_results 映射项；iosMain `ChatHistoryStoreCodec` 对应分支 |
| **iOS** | `ChatView.swift` mediaResultsItem/MediaCardRow/TYPE_MEDIA_RESULTS 分派/onMediaTap 预览；`ChatViewModel.swift` handleUiAction media_results upsert；`ChatMessage.swift` mediaResults 字段与 legacy 重建；`IosChatGalleryCapability` toMediaResults 产出（搜索执行本体保留） |
| **tokens** | design-tokens.json `chatCarousel` 段 + codegen 注册表 + 双端生成镜像（ChatCarouselTokens） |
| **strings** | 卡片专属五语文案：chat_results_found/refined/empty_hint/view_all/photo_deleted、feedback_like/dislike/more_like_this/no_more_results、chat_direct_results_shown、chat_more_like_this*（命令 chip 文案 chat_cmd_search_media/refine 保留——工具保留） |
| **反馈三件套** | `record_feedback` / `more_like_this` / `exclude_constraint` 三个 @Tool 及 dispatch 段、ChatViewModel 对应 handler（D6） |
| **prompt 文案** | 所有「横滑卡片」教学：ChatToolService search_media/refine/run_gallery_script/render_html/反馈三件套描述、ChatToolManifest 头注释与目录条目、ChatPromptRules 41/85/136-145/151 段、IntentRouter ROUTER_SYSTEM_PROMPT 交付物描述、ChatIntentContract MEDIA_RESULTS_CARD 枚举与契约条目（D7 重指）、server OpenUiPromptAssets:431 |
| **测试** | 随上述各点同步删/改（见 §6） |
| **配置文件** | detekt-baseline.xml MediaResultsCarousel 豁免、compose-stability.conf MediaResultsUi 登记 |
| **server 技能** | photo-browse SKILL.md display 工作流重写（display 参数下线，统一「取数 + 自出卡」）；album-maker SKILL.md :28 的 display 说明同步 |

### 2.2 保留（勿误删）

- `search_media` / `refine_media_search` **工具本体与取数能力**——云端 OpenUI 卡（PhotoGrid 取数）与端侧 LLM 的取数底座；`lastResultAssets` 作为 refine 搜索基数的语义保留（但不再用于补卡，读写点随之收缩）
- `CloudAgentGalleryTools.searchMedia/refineMediaSearch` 取数与投影（`handle: scan/<id>`）通路
- `MediaFeedbackUseCase`——先确认搜索排序是否消费反馈分数：有则保留 UseCase 删 UI 入口，无则整体删
- OpenUI `Carousel` 组件、`PhotoGrid`、`grid/carousel` Block 布局（卡内横滑是 OpenUI 组件能力，与本卡无关）
- Memory 页横滑、LlmCallLogScreen 详情 pager、LlmModelManagerScreen 横滑（独立功能）
- `MediaPager` 组件本体若是相册域共享组件则保留（仅删 ChatScreen 消费点）；确认无其它消费方再定去留
- ADR-015/016、docs/reviews、docs/superpowers 历史 spec/plan、CHANGELOG（历史存档不改）

## 3. 关键决策（我的拍板，需用户确认的标 ★）

### D1 ★：VIEW_PHOTOS 的交付物 = LLM + 技能 + 富媒体卡（端云双形态）

直执路由（DirectSearch/DirectRefine）删除后，VIEW_PHOTOS/REFINE_RESULTS 不再是「绕过 LLM 直出原生卡」，而是正常 LLM 回合。**builder-check 修正：端侧 LLM 没有 OpenUI 程序发射通道**（OpenUiCard part 只产生于云端回合与调试注入；端侧工具面只有 `render_html` + `draw_chart`），因此交付形态是「技能驱动、端云双渲染器」而非单一 OpenUI：

- **云端**：turn-native OpenUI 程序（photo-browse/album-maker 技能现行范式，不变）。
- **端侧（Android/iOS）**：`render_html` HTML 卡（`media://{id}` 引用照片，端侧渲染器已支持）。技能改写为双形态：SKILL.md 按环境分支——「有 render_html 工具 → HTML 卡分支；回合原生 OpenUI 契约 → OpenUI 程序分支」，LLM 按自身工具面/system prompt 自取。
- **iOS**：render_html 已实装（IosRenderHtmlCapability），HTML 卡通路可用；SkillRepository 尚未接线（独立里程碑），过渡期内 iOS 看照片 = LLM + search_media 取数 + render_html 出卡（无技能引导，靠 prompt 规则兜底）。

配套：VIEW_PHOTOS/REFINE_RESULTS 的意图契约 allowedTools 补 `render_html`（否则子集闸门内 LLM 物理上出不了卡）。

代价：看照片从「零 LLM 调用直出卡」变为「至少一轮 LLM」。收益：交付形态技能化可迭代（改 SKILL.md 即改行为，不发版）、插卡机制整体拆除。

### D2（机制经 builder-check 修正）：历史数据 = 显式行级过滤跳过

`MessagePart.MediaResults` 类物理删除。**注意 `ignoreUnknownKeys` 不覆盖未知密封类鉴别值**——`MessagePartsCodec.decode` 遇 `data_media_results` 鉴别值整体抛异常 → runCatching 返 null → converter else 分支会把原始 JSON 资产数组渲染成可见 Text 气泡（A2UI 退役当年就是踩的这个坑才加的显式拦截）。因此必须在**双端回放路径显式过滤** entity `type ∈ {data_media_results, media_results}` 的行（跳过不渲染），而不是依赖 codec 行为。`LegacyChatTypeMigration` 删除 media_results 映射项。不写 Room 迁移清历史行。行内容是机器 JSON 非用户文本，跳过（而非 A2UI 式墓碑文案）是刻意取舍。补双端兼容测试钉住。

### D3：display 参数下线 = 契约减法，交叉版本安全

- 新 server + 老端：schema 无 display → LLM 不传 → 老端执行器缺省不透出 → 与新范式行为一致。
- 老 server + 新端：老 schema 有 display='true' 选项，LLM 若传 true，新端执行器已删该参数直接忽略、不透出；LLM 看不到卡片会按技能工作流自出 OpenUI 卡兜底（photo-browse 技能本就教 display='false' 取数范式）。退化可接受。
- 端云同步发布（App 1.0.87 + server 0.9.43），交叉窗口仅存在于部署间隙。

### D4：tokens 段随卡删

`chatCarousel` 段从 design-tokens.json 删除，codegen 注册表同步，双端镜像重生。Android 侧该 token 本就零消费（死 token）；iOS 侧唯一消费方 MediaCardRow 随卡删。Ardot 设计稿 components.json 引用同步清理。

### D5：server prompt/技能同步改 + 0.9.43 部署

OpenUiPromptAssets:431 的对比文案删除；photo-browse SKILL.md 重写 display 工作流（统一「display 参数已下线，取数 + 自出卡」）；album-maker 的 display 说明同步。按双交付铁律：server bump 0.9.43 并 deploy.sh 部署。

### D6：卡面反馈三件套（record_feedback / more_like_this / exclude_constraint）随卡整体退役

这三个 LLM 工具是横滑卡反馈环的配套（dispatch 进 triggerMoreLikeThis/onMediaFeedback/reapplyFiltersToCurrentResults，均在删除集）。卡没了，工具的 UI 锚点也没了——**工具本体、dispatch 段、prompt/manifest 描述、golden 同步删**。`MediaFeedbackUseCase` 存储层保留：`MediaSearchEngine` 排序仍消费既有反馈分数（builder-check 实证 MediaSearchEngine.kt:49），分数变只读存量可接受。五语 feedback_* 文案与 ChatRunScriptCapability 的 ids 补卡警告同步清理。

### D7：意图契约重指 + ADR-015/016 是活文档需同步

- `UiArtifact.MEDIA_RESULTS_CARD` 枚举删除；VIEW_PHOTOS/REFINE_RESULTS 的 uiContract 重指到 HTML 卡形态枚举（实现时以 ChatIntentContract 实际枚举为准）；`RemoteChatEngine.kt:251` hasSearchBase 合成处同步改。
- 工具子集闸门（`resolveToolSubset`/`ChatRoutingPolicy.toolSubsetFor`）对这两个意图继续生效，allowedTools 补 `render_html`（D1 配套）。
- ADR-015/016 是活文档（§4.3），其意图→UI 契约表与 parts 子类型表随本次改动更新，不归「历史存档不改」。

### D8：合并序

三个并行 worktree（chat-tool-surface-cleanup / chat-loop-guard 仅未提交 WIP；fix-carousel-timing 分支领先 main 但改的是本次要删的 display 回执）均不阻塞——基于当前 main 动工，golden 以本分支为准一次重生；那几条线若后续要合，冲突由主方按「卡片已退役」裁决。

## 4. 分期

| 期 | 内容 | 验证 |
|---|---|---|
| **M1** | shared 数据模型 + prompt 面 + 契约 display 减法 + Android 全链路（renderer/插卡/直执/反馈）+ tokens + 五语 + 测试 + golden 重生 | `:shared:jvmTest`、`:androidApp:compileDebugKotlin`、detekt、i18n 检查 |
| **M2** | iOS 同步退役（PARITY）+ iosMain/iosTest + UI 测试断言清理 | `shared:assemble`（iOS metadata）、xcodebuild 编译 |
| **M3** | server prompt 资产 + SKILL.md 双形态重写 + 0.9.43 bump/deploy + 文档同步（CHAT_CARD_CATALOG §4.4、CHAT_PROTOCOL_TECH_SPEC、FEATURES.md、chat.yaml、**ADR-015/016 活文档**、AGENTS.md×4、components.json） | `server test`、check_doc_sync.py、线上 healthz |

M1/M2 可并行（共享 shared 契约面需先定），M3 依赖 M1 契约定稿。

## 5. 风险与红线

- **[PARITY]**：M2 必须同批交付，禁止 Android 先上 iOS 后补（chat.yaml §9 整节删除，iOS 不再读该节）。
- **[I18N]**：五语 strings 同步删，ai-gate i18n 检查兜底。
- **[DOC-SYNC]**：§4 M3 文档清单全走完，check_doc_sync.py 绿。
- **prompt golden**：system prompt / 工具清单 golden 需 POLANG_WRITE_GOLDEN=1 重生并人工 review diff。
- **行为回归**：「看看我家猫的照片」回合从直出原生卡变为 LLM 出 OpenUI 卡——端侧 LLM 出卡质量依赖 photo-browse 技能与端侧渲染器，遛狗包实测是验收关键。

## 6. 测试处置原则

- 随功能删：ChatMessageDaoTest 8 用例、CloudAgentGalleryToolsTest display 门控组、ChatViewModelSearchIntentSanitizerTest（补卡断言部分）、MessagePartsCodecTest/ConverterTest 的 MediaResults 用例、ChatIntentContractTest MEDIA_RESULTS_CARD 断言、iOS ChatSpecUITests 的 chat_media_row/card 断言。
- 改写保留：RemoteChatEngineToolSubsetTest（search_media 子集断言保留，直执断言删）、CloudAgentToolCatalogTest（display 参数断言删）、IosChatGalleryCapabilityTest（搜索产出断言保留，toMediaResults 删）。
- 新增：回放跳过 data_media_results 行的兼容测试（D2）。
