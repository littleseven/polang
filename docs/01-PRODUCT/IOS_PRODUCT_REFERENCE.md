# PoLang iOS 产品实现参考

> **校准基线：v1.0.39 (10039) / 2026-09-20 / 事实来源=代码**
>
> **定位**：iOS 端产品实现的单一参考。以 `iosApp/PoLang` 当前 Swift 代码 + `shared/src/iosMain` + `docs/08-UI-SPECS/screens/*.yaml` 契约为事实来源描述**现状**；不再保留历史演进叙述（2026-08-10 前的 Phase 史见 git 历史与 `../reviews/`）。
>
> **数字口径**：行数均为 2026-09-20 实测（`find iosApp/PoLang -name "*.swift"`，不含 Pods/build/测试）。测试代码单独统计：58 文件 / 8494 行（PoLangTests + PoLangUITests）。
>
> **图例**：✅ 已落地 · 🔄 已落地但有登记缺口 · ❌ 缺口/stub · ❄️ 冻结（代码保留不加新功能）· 🚫 平台不对齐（不做）

---

## §1 总览

### 1.1 代码规模实测（2026-09-20）

iOS 主 target（`iosApp/PoLang`）：**185 个 Swift 文件 / 41808 行**；另有 5 个 Metal shader（beauty/lut/smoothing/warp/yuv，相机美颜管线）。

| 模块 | 文件 | 行数 | 状态 |
|---|---:|---:|---|
| Features/Camera（相机+美颜） | 18 | 3995 | ❄️ 冻结（2026-08-16 决策） |
| Features/Settings（设置） | 9 | 3953 | 🔄（备份恢复占位） |
| Features/Editor（编辑器+AI 抽卡） | 18 | 3817 | 🔄（去背景未接线、BEAUTY 渲染 DEFER） |
| Features/Chat（聊天） | 13 | 3687 | 🔄（端侧意图缺口，见 §3.3） |
| Features/Gallery（相册） | 14 | 3048 | ✅ |
| Features/Organize（整理 v2） | 17 | 2973 | 🔄（DUPLICATES 卡降档） |
| Features/IdPhoto（证件照） | 9 | 1836 | ✅ |
| Features/Person（人物） | 4 | 1172 | ✅ |
| Features/Memories（回忆） | 4 | 1050 | ✅ |
| Features/TagScan（扫描控制页） | 3 | 923 | ✅ |
| Features/Main（主 Pager 宿主） | 1 | 241 | ✅ |
| Features/Common | 2 | 189 | — |
| Features/Debug（调试页） | 5 | 1064 | DEBUG 工具 |
| Platform（引擎/桥/库） | 61 | 12678 | 见 §1.2 |
| DesignSystem（token 镜像+组件） | 3 | 912 | codegen 产物，禁手改 |
| App / DI / SharedBridge | 4 | 270 | 入口+组合根+K/N 桥 |

### 1.2 Platform 层拆分（12678 行）

| 子域 | 文件 | 行数 | 内容 |
|---|---:|---:|---|
| Platform/Search | 20 | 4106 | 自然语言搜索全链路（§3.10） |
| Platform/Tag | 10 | 2085 | TAG 3-Pass 扫描（§3.9） |
| Platform/Js | 3 | 349 | JS 沙盒宿主（QuickJS） |
| 根级 | 28 | 6138 | TagDatabase（GRDB，+6 扩展文件）、PhMediaBridge/PhSearchBridge、模型下载（ModelDownloadManager/ParallelFileDownloader/DownloadTaskHub/ModelCatalog）、ORT/MobileCLIP/OCR/FaceAlignment、缩略图、回收站、各 Bridge |

### 1.3 shared iosMain（Kotlin 侧 iOS 桥接）

`shared/src/iosMain`：**28 个 Kotlin 文件 / 2507 行**，编译进 SharedKit XCFramework 供 Swift 消费。清单与职责见 §4。

### 1.4 版本号漂移注记

Android `androidApp/build.gradle.kts` 为 versionName 1.0.39 / versionCode 10039；**iOS `iosApp/project.yml` 仍为 MARKETING_VERSION 0.1.0 / CURRENT_PROJECT_VERSION 1**，双端版本号未同步（登记为任务，见 IOS_TASK_STATUS.md）。

### 1.5 文档前门一致性注记

`IOS_DOC_INDEX.md` §3「真实状态快照」已随本轮校准同步更新（2026-09-20）：聚类 MNN3.5 Apple bug 已于 2026-08-13 经 ONNX Runtime embedder（`ORTFaceEmbedder.swift`）规避并合入 main，i18n 已五语（en/zh-Hans/zh-Hant/es/fr，`Localizable.xcstrings` 767 键）。

---

## §2 导航结构（5 页 Pager）

### 2.1 主 Pager（`Features/Main/MainTabView.swift`）

`TabView(.page)` 原生跟手 pager，全 5 页常驻组合（对标 Android `HorizontalPager` + beyondViewportPageCount=N-1）。页序与 Android `MainPagerHost.kt`（MAIN_PAGE_GALLERY/DEDUP/CHAT/PEOPLE/MEMORY）**1:1**：

| 页 | 内容 | 承载 |
|---|---|---|
| 0 | 相册 | `GalleryGridView`（初始页） |
| 1 | 整理+扫描合并页 | `OrganizeHomeView`（双 Tab：整理/扫描，organize.yaml §0） |
| 2 | 聊天 | `ChatView`（沉浸二级页，不挂悬浮底 bar） |
| 3 | 人物 | `PersonView` |
| 4 | 回忆 | `MemoriesView`（详情页由内部 fullScreenCover 承载） |

- **相机不在 Pager**（2026-09-16 导航统一，`1e5bd5275`）：改 fullScreenCover 路由，仅两条入口——头像拍摄（`AvatarCaptureController` pending）与 Agent `navigate_to(camera)`；dismiss 落回来源页。UI 自动化经 launch arg `-openCamera` 直弹相机。
- **悬浮底 bar**：五项（相册/整理/聊天/人物/回忆），根页显示；聊天页与相册多选态隐藏（main-nav.yaml §0 hide_bar_when）。
- **场景同步**：翻页/cover 开合实时上报 `IosAgentComposition.onMainPageChanged/onCameraRouteChanged`（相册/整理/回忆→GALLERY，聊天→CHAT，相机 cover→CAMERA），chat 工具按场景路由。
- **全屏路由**：相机 / 设置 / 模型中心 / 图片编辑四个 fullScreenCover 挂 MainTabView，同视图互斥（开一个前复位其余）。

### 2.2 Agent navigate_to 路由表（`NavigationBridge` → `MainNavigationRouter`）

别名与 Android `NavigationCapability.parseDestination` 1:1（lowercase + 中文别名）：

| 目的地 | 行为 |
|---|---|
| camera / 相机 / 拍照 / 拍摄 | 弹相机 cover |
| gallery / 相册 / 照片 / 图库 | 切 Pager 页 0 |
| settings / 设置 / 配置 | 弹设置 cover |
| model_center / 模型中心（含 llm/asr_model_manager 等 Android 别名） | 弹模型中心 cover |
| debug 及其余 | 不受理（iOS 无 Debug 页在主路由，平台差异已登记） |

---

## §3 逐模块实现现状

### 3.1 相册 Gallery（14 文件 / 3048 行）✅

- **网格**：`GalleryGridView` + `GalleryViewModel` + `ThumbnailView`/`ThumbnailLoader`；分组模式 FACE/PERSON 已实做，LANDSCAPE（74 词同源筛选）/LOCATION（城市分组+无位置兜底）已实做。
- **大图**：`MediaPagerView` 捏合缩放、上滑删除手势（§16b 逐条对齐 Android，删除弹系统确认窗——平台本质差异）、PhotoInfo 全字段（美学/人脸/标签 FlowRow/OCR/位置跳地图）、长按编辑入口。
- **搜索**：`SearchTopBar` 三层混合检索（见 §3.10），含多选态横滑阻断。
- **选择**：拖拽批量选择（长按拖/选择态拖，加减模式）、多选上报底 bar 隐藏。
- **相册列表**：`AlbumListView`；权限 `GalleryPermissionStore`。
- 人脸关键点交互（debug 门控 + 缩放跟随）：`GalleryFaceDebug.swift`。

### 3.2 整理 Organize v2（17 文件 / 2973 行）🔄

2026-09-15 批次全域移植（`b914f62e3`，契约 organize.yaml）：

- **领域管线**（`Domain/`，纯 Swift 直译 Android，口径逐条一致并有 XCTest 锁定）：`OrganizeCategorizer`/`CategoryArbiter`（互斥裁定）/`BlurAnalyzer`/`ConfidenceGrader`/`ValueGuard`/`SwipeQueueBuilder`/`SwipeKeepHistory`/`OrganizeThresholds`。
- **Hub**：`OrganizeHubView` + `OrganizeHubViewModel`（类目卡 + Hero 字节数）；**DUPLICATES 卡降档**（needsScan 占位，organize.yaml §8 `ios_duplicates_card_downgrade`）——前提 T8 dedup_hash 扫描器未实现。
- **详情/清理**：`OrganizeCategoryScreen`、`OrganizeRepository`（TagDatabase+Organize 扩列存储）、cleaned 页。
- **滑动审片**：`SwipeReviewScreen`（飞出动画 + undo，竞态已修）。
- 信号存储落 `TagDatabase+Organize.swift`（GRDB 扩列）。

### 3.3 聊天 Chat（13 文件 / 3687 行）🔄

Phase 6.2 已实装：SharedKit `ChatAgentBridge` 流式远程推理 + tool_calls。

- **链路**：`ChatViewModel` → SharedKit `ChatAgentBridge.sendMessage`（非 suspend，void 返回）→ Koog 远程引擎 → `watchUiActions/watchText` FlowWatcher 回流；`cancelCurrent` 取消。iOS 专属 prompt（`IosChatPrompt`，只保留与已注册工具匹配的规则段：8 相册工具 + ai_optimize）。
- **多会话**：`ChatThreadSidebarView` + `ChatHistoryStore`（会话/消息 JSON 文件持久化，按 sessionId 分键）；bridge 侧 `setSessionId` 切 Koog memory ID。
- **富交互**：流式节奏器（commonMain `StreamingPacingController`）、Markdown（表格网格/代码块折叠复制）、CHART 图表卡（`ChartSvgCard`+`ChartJsEngine`）、媒体结果卡横滑「查看全部」、👍👎🔄 反馈+模型胶囊、图片消息（上图下文/编辑回链/捏合 1-5x 全屏预览）、工具轮渲染。
- **AI 优化抽卡**：`ChatOptimizeGachaController` + `GachaCandidateStrip`（chat 侧抽卡条，与编辑器共用引擎，见 §3.7）。
- **JS 沙盒**：`run_gallery_script` 12/12 **只读** handler（`Platform/GalleryScriptHandlers.swift`）；写操作（capability.dispatch + 确认弹窗）未实现。
- **登记缺口**：暂存图 UNDERSTAND/FIND_SIMILAR 意图依赖端侧 VLM（stub，见 §3.9），当前提示「端侧能力不可用」（`ChatViewModel.swift:251/450`）；refine_template 未接线；语音输入不做（§6）。
- 助手性格/回复语言注入 bridge（`52c6335aa`/`565270f43`，含粤语解析）。

### 3.4 人物 Person（4 文件 / 1172 行）✅

非占位、完整实现（2026-08-22 Ardot 设计定稿重排 + 2026-09-16 批次补全）：

- **列表**：`PersonView` 2 列网格卡（人脸感知封面+张数角标/行内改名/关系胶囊+Add relation 引导/「这是我」标记），数据源 TAG 人脸聚类（`TagDatabase.persons`）。
- **详情**：`PersonInfoView`（封面选择、关系编辑、头像拍摄入口——`person_cell_<id>` 锚点 + AvatarCapture 链路，cover 上叠 cover）。
- 存储：`PersonRepository` + `TagDatabase+Person.swift`（含 person_relations 表）。
- 待真机项：无人脸聚类数据时 e2e 用例 XCTSkip（`person_avatar_capture`）。

### 3.5 回忆 Memories（4 文件 / 1050 行）✅

2026-09 落地（`cd2e5f102` 独立页 + `1e5bd5275` 入主 Pager，契约 memories.yaml）：

- `MemoriesGenerator` 纯函数生成器（12 例 XCTest）+ `MemoriesViewModel` + `MemoriesView`（分区大卡 feed）+ `MemoryDetailView`。
- 分享：`MemorySharePayload` 文件 URL 导出（PHAssetResourceManager 临时文件，sheet 关闭清理）。
- 数据：`TagDatabase+Memories.swift`；onAppear 即扫库（与 Android 全页常驻语义一致）。

### 3.6 相机 Camera（18 文件 / 3995 行 + 5 metal shader）❄️ 冻结

**2026-08-16 冻结决策（用户拍板）**：双端相机页 UI parity 收敛后冻结——代码保留、不新增功能、不再投入 parity 打磨；G5 功能深化（录像/风格特效/语音入口）取消。相机继续承担：实时渲染引擎试验场 + 编辑流内容采集入口。

现状：AVFoundation 采集（`CaptureSessionController`/`PhotoCaptureController`）+ Metal 美颜渲染（`BeautyRenderer`+5 shader：beauty/lut/smoothing/warp/yuv）+ 人脸关键点（MNN 106pt / MediaPipe 468→106，`FaceEngineRouter` 双引擎路由）+ 滤镜（`FilterColorMatrix`/`FilterSelectorView`）+ 手势（`CameraGesturesView`）+ 记忆水合（镜头/变焦/比例/网格四 UserDefaults 键）+ 头像拍摄模式（前置切换/提示/落库设封面）。路由化后按 cover 生命周期门控会话。

### 3.7 编辑器 Editor（18 文件 / 3817 行）🔄

- **全功能编辑**：`PhotoEditorScreen` + `PhotoEditorViewModel`：CROP / ADJUST / FILTER（9 色+5 风格）/ MARKUP（`MarkupDrawingCanvas`，绘层仅普通编辑态接管）；`EditHistory` 撤销栈；`RecipeApplier`/`RecipeModels` 配方系统。
- **AI 优化抽卡**（`Gacha/` 10 文件）：`OptimizeGachaEngine` + `CandidateSampler`（候选采样）+ `NimaScorer`/`OptimizeScorer`（美学打分）+ `Guardrails` + `OptimizeFeedbackLogger`（稳定 image_key 落库三源反馈）+ `AiOptimizeService`（SharedKit `IosAiOptimizeBridge` 远程）；对比模式 `GachaCandidateBar` 替换底栏。
- **登记缺口**：去背景顶栏按钮置灰（「敬请期待」toast，MattingEngine 已在 IdPhoto 存在但未接进编辑器）；BEAUTY 滑杆可调+参数存档，渲染 DEFER。

### 3.8 证件照 IdPhoto（9 文件 / 1836 行）✅

2026-08-16 ios-follow 验收 PASS（`../reviews/2026-08-16-ios-follow-idphoto.md`，契约 idphoto.yaml）：

- `MattingEngine`：FUSION 固定路由——MediaPipe selfie 分割（256²）+ ORT ModNet（1024²）逐像素 max 融合，100% 端侧；EXIF 方向归一化。
- `IDPhotoScreen`/`IDPhotoViewModel`/`IdPhotoDomain` + Components（尺寸 chip/底色色板/边缘/修复面板）；模型经模型下载中心预解析。

### 3.9 TAG 打标扫描（TagScan 3 文件 / 923 行 + Platform/Tag 10 文件 / 2085 行）🔄

3-Pass 全通（`TagScanOrchestrator` 编排，`TagScanScreen`/`TagScanViewModel` 控制页）：

- **Pass1**：`Pass1Pipeline` + `FaceAlignment`（106→5 点）+ `ORTFaceEmbedder`（ONNX Glint360K R100——规避 MNN3.5 Apple embedding bug）+ `MobileClipEncoder`（语义 embedding）+ GRDB `TagDatabase`。
- **Pass2**：`Pass2Pipeline` + `FaceClusterer`（自适应 k-NN 连通分量 + 精修）+ `FaceClusterMaintenance`。
- **Pass3**：`Florence2Tagger`（Florence-2-base INT8，ORT 4-session，OD+DETAILED_CAPTION 双任务）。
- **端侧 VLM**：❌ **stub**——`shared/iosMain` `IosUnavailableImageInferenceEngine`（isLoaded=false / loadModel 恒失败 / 推理返回空串），修图理解/打标 VLM 工具在 iOS v1 不注册；连带 chat UNDERSTAND/FIND_SIMILAR 不可用。
- **缺口**：MetalGuardian（warmup 超时/Metal→CPU 降级/黑名单）未实现；后台扫描未实现（进后台仅 `pauseForBackground()` 协作暂停，无 BGTaskScheduler）；T8 dedup_hash 扫描器未实现（DUPLICATES 前提）。

### 3.10 自然语言搜索（Platform/Search，20 文件 / 4106 行）✅

已落地（`bb1839de`，非缺口）。三层混合检索：

- `MediaSearchEngine` 主引擎 + `ExplicitFirstSearchPipeline`（显式条件优先）；
- 查询理解：`QueryParser`/`QuerySegmenter`/`ChineseQueryTranslator`/`SearchSynonyms`/`ControlledVocab`/`BilingualVocab`/`KinshipLexicon`（亲属词表）/`PersonQueryResolver`（人名+关系解析）/`RelationPredicate`；
- 语义召回：`SemanticSearchEngine` + `MobileClipTextEncoder`/`MobileClipTokenizer`（文本塔）+ `SemanticEmbeddingCodec`；
- OCR：`OcrRecognizer`（Vision）；反馈：`MediaFeedbackUseCase`；chat 桥：`PhSearchBridge` → SharedKit `IosChatGallerySearch`（refine in-set 语义）。

### 3.11 设置 Settings（9 文件 / 3953 行）🔄

列表式分组主页（`SettingsScreen`）+ 全部二级页（`SettingsSubPages`）：

- 账号（邮箱注册/登录/quota 外显/Hero 卡+头像拍摄角标/清除访客数据 `PoLangAuthClient.clearGuestData`）、AI Memory（`MemoryFactsView` 查看/编辑/删除事实）、人物管理、通道（飞书/Telegram 凭证配置——仅凭证，RPA 不做）、相册设置（统计卡+扫描控制台入口）、远程模型（BYOK 双页面：OpenAI/Anthropic/自定义供应商协议分流，`AddRemoteProviderView`/`ProviderConfigView`/`ModelConfigStore`）、本地模型、模型中心（`ModelCenterView`/`ModelDownloadCenterView`，16 模型下载/进度/删除/完整性校验）、沙盒与权限、数据与隐私、开发者选项（诊断日志查看器 llm/tool/js 三份 JSONL + Log Modules）、主题/语言即时生效。
- 助手性格选择（三 chips，五语）。
- **缺口**：备份与恢复「Coming Soon」占位（`SettingsScreen.swift:537`）；语音模型选择为持久化占位（iOS 无引擎，`SettingsScreen.swift:707`）；App Store 2.5.2 合规结论未出（JS 写操作上线前置）。

### 3.12 Debug（5 文件 / 1064 行）

`DebugScreenView` + `SampleDataGenerator` + Pexels 素材 + `DebugCaptureButton`（DEBUG 构建画面抓取）；`App/DebugOverlay`。

---

## §4 SharedKit 消费方式

### 4.1 集成形态

shared KMP 模块编译为 **SharedKit XCFramework**（`:shared:assembleSharedDebugXCFramework`），Swift `import SharedKit` 消费。互操作纪律（kmp-ios-interop 铁律）：跨边界方法全部非 suspend（void 返回）、Kotlin 侧 try/catch(Throwable) 兜底（未声明 @Throws 的异常逃逸 = signal 6）、Flow 经 `FlowWatcher`（`shared/iosMain/shared/FlowWatchers.kt`）转回调、回调线程任意（Swift 须 `Task { @MainActor in }` 更新 UI）。Swift 侧统一入口 `SharedBridge/KotlinBridge.swift`，组合根 `DI/AppContainer.swift`。

### 4.2 iosMain 清单（28 文件 / 2507 行）

| 文件 | 职责 |
|---|---|
| `agent/IosAgentComposition.kt` | iOS Agent 组合根（手工工具清单替代 JVM 反射；幂等 initialize；场景回调 onMainPageChanged/onCameraRouteChanged） |
| `agent/core/inference/remote/ChatAgentBridge.kt` | Swift↔Kotlin chat 桥：流式远程推理 + tool_calls + 多会话 setSessionId + cancelCurrent |
| `agent/core/inference/remote/IosChatPrompt.kt` + `ChatUiActionDto.kt` | iOS 专属 prompt（匹配已注册工具）/ UI action DTO |
| `agent/core/inference/local/IosUnavailableImageInferenceEngine.kt` | 端侧 VLM stub（§3.9） |
| `agent/core/capability/IosChatGalleryCapability.kt` + `IosChatGallerySearch.kt` | chat 相册能力：search/refine/feedback/more/exclude 五命令 + 写操作四命令（favorite/select/share/delete，delete 走系统确认窗） |
| `agent/core/capability/IosNavigationCapability.kt` | navigate_to 能力（→ `IosNavigationBridge` → Swift `NavigationBridge`） |
| `agent/core/capability/IosAiOptimizeCapability.kt` / `IosChartCapability.kt` / `IosRunScriptCapability.kt` | AI 抽卡 / 图表 / JS 沙盒能力 |
| `agent/core/js/IosJsRuntimeSupport.kt` | JS 运行时 actual（QuickJS） |
| `agent/core/platform/*` | Platform.ios / DispatcherProvider.ios / IosDiagnosticLogStore / IosKoogMessageMemoryStore（NSUserDefaults）/ KoogHttpClientFactoryProvider.ios / AgentIdGenerator.ios |
| `data/Ios*Bridge.kt`（7 个） | Swift 实现接口的 Kotlin 侧声明：MediaRepository/ChatSearch/Navigation/AiOptimize/Chart/RunScript |
| `data/IosMediaRepository.kt` | 相册仓储（经 PhMediaBridge 回调 Swift） |
| `domain/chat/*` | TimeProvider / StreamingPacingControllerFactory actual |
| `shared/FlowWatchers.kt` | Flow→回调 watcher（K/N 返回类型保留） |

### 4.3 数据与隐私口径

[PRIVACY] 红线：chat DTO 不含文件路径/GPS/base64；组合根不注入真实 ImageInferenceEngine，chat 链路无多模态上传。本地数据：GRDB `TagDatabase`（+Scan/Search/Organize/Person/Memories/Cluster/GalleryJs 7 个扩展文件）、会话 JSON、NSUserDefaults（Koog memory/相机记忆/凭证）。

---

## §5 双端能力对照表（Android main v1.0.39 vs iOS 2026-09-20）

| 能力域 | Android | iOS | 备注 |
|---|---|---|---|
| 主导航 5 页 Pager（相册/整理/聊天/人物/回忆） | ✅ | ✅ | 页序 1:1（iOS `1e5bd5275`）；相机双端均为全屏路由 |
| 相册浏览/大图/多选/删除 | ✅ | ✅ | iOS 删除必弹系统确认窗（平台差异） |
| 自然语言搜索 | ✅ | ✅ | iOS `bb1839de` 全链路 |
| 整理 v2（hub/详情/cleaned/swipe） | ✅ | 🔄 | iOS DUPLICATES 卡降档（dedup_hash 未扫） |
| 打标并入整理 SCAN tab | ✅ | ✅ | iOS 合并页为 OrganizeHomeView 页 1 |
| TAG 3-Pass 扫描 | ✅ | 🔄 | iOS 缺 MetalGuardian/后台扫描/dedup_hash |
| 端侧 VLM（图像理解/打标） | ✅ | ❌ stub | `IosUnavailableImageInferenceEngine`；连带 chat UNDERSTAND/FIND_SIMILAR 不可用 |
| Chat 流式对话+工具 | ✅ | ✅ | iOS 8 相册工具+ai_optimize（手工清单） |
| Chat 多会话历史 | ✅ | ✅ | iOS `54799952` |
| Chat JS 沙盒 | ✅（读+写） | 🔄 只读 12/12 | 写操作（capability.dispatch+确认弹窗）未实现；前置 App Store 2.5.2 结论 |
| AI 优化抽卡 | ✅ | ✅ | chat+editor 双入口（`2026-08-16-ios-follow-optimize-gacha.md` PASS） |
| 人物页 | ✅ | ✅ | 含关系/封面/头像拍摄 |
| 回忆页 | ✅ | ✅ | iOS `cd2e5f102`+`1e5bd5275` |
| 编辑器（crop/adjust/filter/markup） | ✅ | 🔄 | iOS 去背景未接线、BEAUTY 渲染 DEFER |
| 证件照 | ✅ | ✅ | iOS MattingEngine FUSION |
| 相机（美颜/滤镜/关键点） | ✅ | ❄️ 冻结 | 双端同步冻结（2026-08-16） |
| 设置/账号/模型中心/BYOK | ✅ | 🔄 | iOS 备份恢复占位；语音模型持久化占位 |
| 语音（ASR/TTS） | 🔄 实验能力（默认关闭） | 🚫 | iOS 无引擎；输入栏 VoiceButton 2026-09-20 从设计资产移除（`b9dec58ba`） |
| 飞书/Telegram 远程控制 RPA | ✅ | 🚫 | iOS 无 AccessibilityService 等价 |
| 悬浮聊天气泡 | ✅ | 🚫 | iOS 无系统悬浮窗等价 |
| launch_app / open_system_settings | ✅ | 🚫 | iOS 沙盒限制 |
| HyperOS 后台冻结检测 | ✅ | 🚫 | iOS 无厂商冻结问题 |
| i18n | ✅ 五语 | ✅ 五语 | 767 键 × en/zh-Hans/zh-Hant/es/fr |
| server 适配 | ✅ | ✅ | `X-Platform: ios` + 设备平台字段 |

---

## §6 契约与验证体系

- **逐屏契约**：`docs/08-UI-SPECS/screens/*.yaml` 15 份（camera/chat/editor/gallery-grid/idphoto/main-nav/memories/model-download-center/organize/person/settings/tag-control/topbar + lang/refs）；iOS 实现读 spec 不读 Android 源码（ui-parity-guard 红线）。
- **Design tokens**：`design-tokens.json` 唯一 SSOT → `scripts/gen-design-tokens.py` 双端镜像 codegen（iOS 落 `DesignSystem/DesignTokens.swift`），`--check` 门禁禁手改；2026-09-20 漂移收敛归零（`7a5cc8db7`）。
- **验收留痕**：`docs/reviews/` 下 ios-follow 批次报告为各模块验收事实来源（2026-08-10 ~ 2026-09-16 共 10 篇 ios-follow 报告 + 差评/一致性审计）。
- **自动化**：PoLangUITests 经 `-startPage <0-4>` / `-openCamera` launch arg 驱动；iOS 单测模拟器受限（MNN.framework arm64-only 无 simulator slice），XCTest/UITest 全量跑批依赖真机。

---

## §7 维护说明

- 功能落地/缺口变化时更新对应模块小节与 §5 对照表；缺口与任务状态以 `IOS_TASK_STATUS.md` 为准。
- 数字（行数/文件数/键数）变更显著时按 §1 口径重测回写。
- 与本文矛盾处以代码为准；发现漂移在 `IOS_TASK_STATUS.md` 登记。
