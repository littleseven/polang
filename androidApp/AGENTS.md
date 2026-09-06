# App 模块技术实现规范 (App Module Implementation)

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:androidApp` 主应用模块的实现细节（架构、组件、导航、依赖注入）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 美颜引擎实现细节见 `engines/beauty-engine/AGENTS.md`；Agent Runtime 实现细节见 `shared/AGENTS.md`。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：`:androidApp` 是 PoLang 的主 Android 应用模块，承载 Compose UI、页面导航、依赖注入、数据持久化、网络请求和功能集成。作为最外层模块，`:androidApp` 负责将 `:shared`（Agent 编排层 KMP 模块）、`:engines:beauty-api`、`:engines:beauty-engine`、`:engines:sentencepiece` 四个独立库组装为完整应用（Agent 框架为 Koog 外部依赖，经 `:shared` 透出；VLM JNI `.so` 经 `:engines:agent-native` 传递）。

**主要维护者**：项目开发者

**阅读对象**：项目开发者、AI Agent

---

## 1. 核心架构 (Core Architecture)

### 1.1 分层架构

```
features/                 ← Compose UI + ViewModel（用户可见页面）
    ↓
domain/usecase/           ← 业务逻辑编排（纯 Kotlin，无 Android 依赖）
domain/repository/        ← 仓储接口定义
    ↓
data/                     ← 仓储实现、Room DB、DataStore、Retrofit
    ↓
di/                       ← AppContainer 手动 DI（无 Hilt/Dagger）
```

### 1.2 页面导航（主页面 Pager + NavHost 二级页，Gallery 为默认首页）

**主页面（`Screen.Main` 单 destination，`features/main/MainPagerHost.kt` 以 HorizontalPager 承载 5 页）**：

| 页索引 | 页面 | 定位 |
|--------|------|------|
| 0 | `Gallery` | **默认首页** — 智能相册、媒体浏览、AI 搜索、分类管理；底部悬浮 Tab（共享 `MainFloatingBottomBar`）以纯图标聚合 相册/整理/Chat/People/回忆 五项（与 Pager 页序 1:1，相册项本页高亮）；设置入口在顶部栏最右侧 |
| 1 | `Organize`（整理+扫描合并页） | 整理+扫描双 Tab 容器（`OrganizeHomeRoute`，2026-09-06 合并）— Tab「整理」= 去重 2.0 主页（`DedupHomeRoute` embedded，三级尺度重复/相似照片扫描、保留规则与回收站清理），Tab「扫描」= TAG 生成控制（`TagGenerationControlScreen` embedded，原 `tag_control` 路由并入）；相册页左滑即达，系统返回切回相册页不弹栈；外部入口（设置页）经 `organizeTabRequest` 一次性预选 Tab；根页挂底 bar（整理项高亮，Dedup Scanning/Results/Cleaned 态门控隐藏防 CTA 遮挡） |
| 2 | `Chat` | AI 对话主页，仅远程模型；沉浸式二级身份（无底 bar、顶栏返回相册、横滑可达） |
| 3 | `People` | 人物聚类页；2026-09-06 升根页（去顶栏返回箭头、挂底 bar 人物项高亮、网格 96dp 底部避让） |
| 4 | `Memory`（回忆） | 回忆独立页（2026-09-06，对标小米系统相册）— 三分区大卡 feed（时光/旅程/人物，空分区不占位）+ 回忆详情页 `memory_detail/{memoryId}`；纯端侧规则生成零上传，实现细节见 `features/gallery/AGENTS.md` §2.12 |

> **2026-07 主页面 Pager 化**：主页面由 `HorizontalPager`（`beyondViewportPageCount = MAIN_PAGE_COUNT - 1`，页面全部常驻组合）承载，横滑跟手、线性顺序、无循环回绕；底部 Tab/编程入口经 `switchMainPage` 瞬时切页（无滑动动画）；相册（详情/多选）与聊天（全屏预览）通过 `onHorizontalSwipeEnabledChange` 局部禁用外层滑动；Chat/人物页跳相册搜索经 `searchRequest` 状态驱动（不再走 `gallery?query=` 路由参数）。原 `MainPageSwipeWrapper` 已删除。
>
> **2026-09-06 Memory 独立页**：Pager 追加第 5 页「回忆」（index 4，`MAIN_PAGE_MEMORY`，`beyondViewportPageCount` 随之 = 4），对标小米系统相册分区 feed；实现细节见 `features/gallery/AGENTS.md` §2.12。
>
> **2026-09-06 导航统一（五页一面、聊天沉浸）**：底 bar 五项与 Pager 页序 1:1（相册 PhotoLibrary / 整理 CleaningServices / 聊天 / 人物 / 回忆 AutoAwesome），收敛为共享组件 `MainFloatingBottomBar`（`features/main/`）。**页面身份规则**：根页（相册/整理/人物/回忆）= 无顶栏返回箭头 + 渲染底 bar 高亮自身项 + 系统返回经 MainPagerHost BackHandler 回相册页；沉浸二级页（聊天）= 有返回出路、不渲染 bar。页面自身有底部 UI/全屏态时隐藏 bar（先例：相册详情态、Dedup CTA 三态）。打标 bar 项移除（深链仍走 `organizeTabRequest`）；`Screen.People` 死声明删除。spec：`docs/superpowers/specs/2026-09-06-main-nav-unification-design.md`。
>
> **2026-09-06 整理+扫描合并页**：原 Pager 页 1（去重 2.0 `DedupHomeRoute`）与 NavHost 路由 `tag_control`（TAG 生成控制）合并为页 1 双 Tab 容器 `OrganizeHomeRoute`（顶部胶囊分段开关「整理/扫描」，两子页以 `embedded` 模式嵌入保留各自顶栏 actions）；`tag_control` 路由删除，悬浮底栏两图标与设置入口均切页并预选对应 Tab（页内 Tab 态由 MainPagerHost `rememberSaveable` 持有，外部经 `organizeTabRequest` 驱动）。
>
> **2026-08-26 相机路由化**：相机页从 Pager 移出（原页 0 席位由相册整理接替），改为 NavHost 全屏路由 `Screen.Camera`——唯一用户入口为头像拍摄（`AvatarCaptureController` 登记 pending 后 navigate），Agent `navigate_to(camera)` 由 `NavigationCapability` 直接 navigate 该路由；相机会话门控由 Pager `isActivePage` 改为路由生命周期（`backStackEntry.lifecycle ≥ RESUMED`）驱动，离开即解绑释放。

**NavHost 二级页**：

| Screen | Route | 定位 |
|--------|-------|------|
| `Main` | `main` | **startDestination** — 主页面 Pager 容器（上表 5 页） |
| `Camera` | `camera` | 相机全屏页（2026-08-26 路由化）— 拍照、美颜预览、语音控制；仅头像拍摄与 Agent `navigate_to(camera)` 进入，会话按路由生命周期（≥RESUMED）门控 |
| `PhotoEditor` | `photo_editor/{sourceUri}?recipeUri={recipeUri}&autoOptimize={autoOptimize}` | 图片编辑器 — 从相册 MediaPager 进入；`recipeUri` 重新编辑已保存副本，`autoOptimize` 进入时自动触发 AI 一键优化 |
| `IDPhoto` | `id_photo/{sourceUri}` | 证件照制作 |
| `OrganizeCategory` | `organize_category/{category}` | 整理中心类目详情（F1，2026-09-05）— AI 预选网格 + 批量回收站清理/恢复；路由段为 `domain.organize.OrganizeCategory` 枚举名，非法值弹栈；VM 经 `AppContainer.createOrganizeCategoryViewModelFactory(category)` 构建（TrashSessionController 在 VM 内 new，backend = DedupTrashBackend 包装 dedupTrashManager） |
| `SwipeReview` | `swipe_review` | 手势快速整理全屏页（F2，2026-09-05）— 右滑保留 / 左滑跳过 / 上滑删除（点按=跳过），DELETE 批量提交系统回收站；KEEP 决策写入 30 天抑制历史（DataStore `swipe_keep_history`）不再入队；hub「Quick tidy up」主按钮点亮；VM 经 `AppContainer.createSwipeReviewViewModelFactory()` 构建 |
| `MemoryDetail` | `memory_detail/{memoryId}` | 回忆详情页（F3，2026-09-06 升级对标小米）— 约屏高 55% 封面（左下蒙层白字标题 + hitCount·分类型副行）+ 3 列网格 + 底部「精选/全部」分段开关（`displayUris` = `itemUris`/`allItemUris` 跟随切换，分享集合同步跟随，ACTION_SEND_MULTIPLE）；封面/网格照片点击进全屏 MediaPager 预览（2026-09-06 补齐：`MemoriesViewModel.assetsByUri` uri→MediaAsset 反查，预览内删除/OCR/跳编辑器/证件照经 Activity 级 MediaViewModel，删除授权同 ChatScreen 写法，删空自动收起，返回键优先关预览）；路由段为 Memory.id（`Uri.encode`，Navigation 自动解码一次），数据取自 Activity 级 `MemoriesViewModel.observeMemory(id)` Flow（未过滤全集，已隐藏条目可复原，冷恢复不闪空态），id 失效 → 空态；VM 经 `AppContainer.createMemoriesViewModelFactory()` 构建 |
| `Settings` | `settings` | 设置 — 主菜单（2026-08-26 列表式改版：账号 Hero 卡 + 个性化/功能/AI 与系统/其他四组列表行） |
| `SettingsCategory` | `settings/{category}` | 设置二级分类页 — 路由段为枚举名小写：`account`、`gallery`（dormant）、`camera`、`system`、`remote_model`、`local_model`、`sandbox`、`developer`（2026-08-16 `camera_beauty` 更名 `camera`，承载相机状态记忆与重置） |
| `AddRemoteProvider` | `settings/add_remote_provider` | 添加远程模型 — 供应商列表页（精确路由，优先于 `settings/{category}` 占位匹配；2026-08-21 替代原 AddProviderModelDialog 弹窗） |
| `ProviderConfig` | `settings/provider_config/{providerId}` | 供应商配置页 — API Key + 模型单选 + 自定义模型 ID；`providerId=custom` 为自定义供应商形态（含 Base URL）；保存后确定性弹回远程模型列表 |
| `ModelCenter` | `model_center/{categoryTag}` | 模型中心 — 按服务功能分类管理本地模型 |
| `TagViewer` | `tag_viewer` | 标签查看页 |
| `MemoryFacts` | `memory_facts` | 设置子页 — AI 记忆（人物关系区 + 事实记忆区的查看/编辑/删除/清空），从设置主菜单「功能」组「AI 记忆」列表行进入 |
| `DataPrivacy` | `data_privacy` | 数据隐私说明页 |
| `CommunicationChannel` | `communication_channel` | 通信通道设置页 |
| `Debug` | `debug` | 开发工具 — 日志、截图、样本数据生成 |
| `JsBridge` | `jsbridge` | JS 沙箱调试页 |
| `SearchTest` | `search_test` | 搜索诊断测试页 |
| `SentencePieceTest` | `sentencepiece_test` | SentencePiece 翻译测试页 |
| `LlmLog` | `llm_log` | LLM 调用日志查看页 |

> Chat/Gallery/Organize(整理+扫描)/People/Memory 为 `Main` 内部 Pager 页，不单独注册 destination；相机为 NavHost 全屏路由（`camera`）；完整路由定义以 `navigation/Screen.kt` 为准。

> **2026-06 产品重心转移**：Gallery 为默认首页，Camera/Chat/ModelCenter 作为纯图标入口从 Gallery 底部悬浮 Tab 进入，Settings 从顶部栏进入；设置页已拆分为 6 个二级分类页，主菜单保持一屏可见；Model Center 内置于 Settings 的 AI 助手卡片第一项，分类按服务功能（必须/聊天/相册打标/美颜相机）重排，聊天分类聚合文字与语音模型，并提供必须模型一键下载；重复照片管理已升级为设置主菜单「相册整理」一级入口与主页面 Pager 页 1（2026-08-26，原「Settings 相册功能卡片」入口废弃）；Camera 页已移除设置入口。
>
> **2026-08-26 二次调整（对上段的修正）**：相机自悬浮 Tab 移出、路由化（见上「2026-08-26 相机路由化」），悬浮 Tab 首项改为相册整理；设置主菜单改列表式分组，「AI 助手卡片」移除——Model Center 为「AI 与系统」组列表行，AI 记忆为「功能」组列表行。
> - **16 KB 适配**：MediaPipe tasks-vision 升级至 0.10.26，sherpa-onnx 升级至 1.13.3（内置 ONNX Runtime 1.24.3），`onnxruntime-android` 同步升级至 1.24.3，上述 native lib 均已 16 KB 对齐，满足 Google Play Android 15+ 要求。详见 `PRODUCT.md`。

### 1.3 关键入口文件

| 文件 | 职责 |
|------|------|
| `PoLangApplication.kt` | Application 初始化：DI 容器、Native 库加载、AgentOrchestrator 预配置 |
| `MainActivity.kt` | 单 Activity：Compose NavHost、主题/语言管理、Navigation/System Capability 注册、模型下载弹窗 |
| `navigation/Screen.kt` | sealed class 定义所有路由 |

---

## 2. 子包结构 (Package Structure)

基础包：`com.mamba.picme`

### 2.1 功能层 (`features/`)

| 功能 | 路径 | 核心文件 | 说明 |
|------|------|---------|------|
| **Agent** | `features/agent/` | `GlobalAgentPanel.kt` | 全局悬浮 Agent 面板 |
| **BackupRestore** | `features/backuprestore/` | `BackupRestoreActivity` | 数据备份与恢复入口（本地数据导出/导入，备份模型 v5） |
| **Chat** | `features/chat/` | `ChatScreen`, `ChatViewModel`, `ChatThreadSidebar`, `ChatTitleGenerator` | AI 对话二级页，从相册首页进入，支持多线程；首条消息自动生成会话标题；支持对话式图片编辑（`edit_image`），结果以 `AGENT_EDIT_RESULT` 消息 inline 返回 |
| **Chat JS** | `features/chat/js/` | `QuickJsEngine`, `QuickJsConverter`, `GalleryScriptHandlers`, `GalleryJs`, `ChartJs`, `CapabilityDispatchHandler` | QuickJS 沙箱引擎与 JSBridge 应用层（见下方 JS Engine 说明） |
| **Camera** | `features/camera/` | `CameraScreen`, `CameraPreviewContent`, `CameraAgentCommandHandler` | 相机预览、美颜实时渲染、Agent 命令处理 |
| **Common** | `features/common/chat/` | `AgentChatComponents`, `AgentMessage`, `AiChatScreen` | Chat UI 共享组件库（Camera/Gallery 复用） |
| **Gallery** | `features/gallery/` | `GalleryScreen`, `MediaViewModel` | 智能相册浏览、AI 搜索 |
| **Editor** | `features/editor/` | `ImageEditScreen` | 图片编辑（美颜/滤镜/风格） |
| **IDPhoto** | `features/idphoto/` | `IDPhotoScreen`, `IDPhotoViewModel` | 证件照制作二级页（`id_photo/{sourceUri}`）；底部 4-tab（底色/尺寸/边缘/修补），修补 tab 涂抹手势 + 覆盖层，边缘 tab 三滑块（对比度/收缩扩张/羽化）松手触发预览更新 |
| **Main** | `features/main/` | `MainPagerHost` | 主页面 Pager 容器（Gallery/Dedup/Chat/People/Memory 5 页横滑） |
| **Person** | `features/person/` | `PersonScreen`, `PersonViewModel`, `PersonCoverResolver` | 人物聚类独立页 |
| **Settings** | `features/settings/` | `SettingsScreen`, `SettingsViewModel`, `LlmModelManagerScreen`（含 `ModelCenterScreen` composable）, `MemoryFactsScreen` | 设置与模型管理；`MemoryFactsScreen` 为「AI 记忆」管理二级页（人物关系区查看/编辑/删除 + 事实记忆区查看/编辑/删除/清空） |
| **TagViewer** | `features/tagviewer/` | `TagViewerTestScreen`, `TagAggregator`, `TagJsonParser` | 标签查看页 |
| **Translation** | `features/translation/` | `SentencePieceTestScreen` | SentencePiece 翻译测试页 |
| **Debug** | `features/debug/` | `DebugScreen`, `LogOverlay`, `ScreenshotUtil` | 开发调试工具 |

> **2026-07-25 JS Engine（QuickJS 沙箱，`features/chat/js/`）**：
> - `QuickJsEngine` / `QuickJsConverter`：dokar3 quickjs-kt 1.0.5 引擎适配器（唯一生产引擎实现，QuickJS 依赖仅 `:androidApp` 引入），实现 `:shared` 引擎无关的 `JsEngine` 接口；eval 带超时（默认 5s），bridge 经 `__bridgeCall`/`__bridgeCallAsync` 绑定 + bootstrap JS 注入
> - `GalleryScriptHandlers.registerGalleryHandlers`：gallery/media/face/tag **12 个只读取数 handler 的唯一注册点**（全部 async，JS 侧必须 `await bridge.callAsync`），ChatViewModel 持久 JsRuntime 与 Debug 页 JsBridgeDemo 共用，新增/修改 handler 只改这里
> - `GalleryJs`：JS ↔ 查询模型字段转换（parseQueryFilter / toResultJsValue / toScanStatusJsValue 等），media.meta 白名单不回 uri/GPS/ocrText/embedding（回 city/aestheticScore/faceQualityScore 纯数值字段）
> - `ChartJs` + `assets/js/chart_bootstrap.js`：Chart.bar/line/pie/timeline → SVG，图卡落库为 CHART 消息，summary 回传 LLM
> - `CapabilityDispatchHandler`：`capability.dispatch` 写通路（delete_media/favorite_media/select_media/get_gallery_summary/remember_fact/forget_fact/recall_memory/remember_person_relation/forget_person_relation/query_person_relation 白名单），按 `CommandRisk` 分级，写操作经 `WriteConfirmationController`（`features/chat/`）弹确认框——缩略图预览、120s 超时按拒绝、并发确认互斥串行、脚本结束在途确认失效；落点 `ChatMediaWriteCapability`（CHAT 场景，删除走 MediaStore 授权，favorite/select 会话级无持久化）、`MemoryCapability`（remember/forget_fact 落 `memory_facts`，recall_memory 只读直通）与 `PersonRelationCapability`（人物关系声明/遗忘/查询）
> - 完整规格见 `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`

### 2.2 领域层 (`domain/`)

| 子包 | 内容 | 说明 |
|------|------|------|
| `usecase/` | `AiAgentUseCase`, `GetGroupedMediaUseCase`, `OcrUseCase`, `ChatEditProcessor` | 业务用例：Agent Facade、分组、OCR、对话式图片编辑渲染与保存 |
| `repository/` | `MediaRepository`, `UserPreferencesRepository`, `UserSettingsRepository` 等接口 | 仓储抽象 |
| `dedup/` | `DedupModels`（DedupLevel/DedupGroup/DedupScanConfig）, `DedupScanner`, `DedupScanEvent`, `DedupScanController`, `KeepPolicyEngine`, `DedupTrashManager` | 去重 2.0 领域层：三级尺度流式扫描（dedup_hash 缓存）、保留规则引擎、系统回收站删除/恢复 |
| `model/` | `AiAgentCommand`, `MediaAsset`, `UserPreferences`, `ChatEditRecipeBuilder` 等 | 领域数据模型；`ChatEditRecipeBuilder` 将 LLM 编辑意图转换为 `EditRecipe`（delta 相对调整带单次步进上限保护：美颜 ±10、slim_face ±5、亮度/曝光 ±15、对比度/饱和度 ±15、色温 ±500K、tint ±15；绝对值视为显式数值请求不限幅） |
| `matting/` | `MattingEngine`, `MaskPostProcessor`, `StrokeLayer`, `EdgeParams`, `IDPhotoComposer`, `BackgroundComposer` 等 | 抠图与证件照合成：融合管线不再固定 sharpen（边缘锐化已迁移参数层，`EdgeParams.DEFAULT_CONTRAST=2.5` 复现旧行为，MIN/MAX 常量供 UI 钳制）；`MaskPostProcessor` 参数层 `erode/dilate/adjustEdges`（对比度→收缩扩张→羽化，各环节默认值自然短路）；`StrokeLayer` 矢量描边层（RESTORE/ERASE、undo/redo，撤销=移除尾条重放；`snapshot` + companion `replay` 纯函数保证跨线程安全） |
| `search/` | `MediaSearchEngine`, `ExplicitFirstSearchPipeline`, `QuerySegmenter`, `QueryParser`, `SegmentedQuery`, `ExplicitFilter`, `ContentFilter` | 自然语言图片搜索：显式约束优先分段检索 + 规则/LLM/语义混合排序；Chat 场景由 `SearchIntent` 直接驱动 `MediaSearchEngine.search(filter)` |
| `tag/` | `TagGenerationScheduler`, `TagScanOrchestrator`, `OpenClGuardian`, `TagCategory` | TAG 生成编排、OpenCL 守护、类别定义 |
| `aesthetic/` | `NimaScorer`, `EdiffiqaScorer`, `CoverSelector`, `FaceAligner`, `AestheticScoreWorker` | 美学打分（NIMA + eDifFIQA）：NNAPI 推理、会话跨调用复用、人脸对齐与质量分；非会话制附属打分器（`runUntilDone` 循环排空 + `progress` StateFlow 上报），触发=扫描完成后自动补分 + 打标页手动（Service `ACTION_SCORE_AESTHETIC[_FULL]`），与扫描会话互斥（复用 RetinaFace 无同步保护）；待人脸画质分口径 gate 在 `hasFace=1` |
| `backup/` | `TagDataBackup`（model/）, `TagDataBackupRepository`, `BackupTagDataUseCase`, `RestoreTagDataUseCase` | 标签数据备份/恢复（备份模型 v5：标签、人脸聚类、人物关系、记忆事实、编辑配方等本地数据导出导入） |
| `person/` | `RelationPredicate`, `KinshipLexicon`, `PersonRepository`, `PersonQueryResolver`, `RelationSnapshotRestorer` | 人物关系图谱（两层模型）：谓词封闭枚举（粗谓词机器逻辑，性别/长幼细分 + 中/英/日标签）+ `customLabel` 自定义称呼（用户语言，展示/查询优先）；称谓词表（声明归一具体谓词 + 谓词族查询扩展）；关系与"我"标记收口仓库（`observeSelfAvatar` 同时是设置页账户头像的数据源）；查询串→personId 解析器；重聚快照恢复纯函数 |
| `memory/` | `MemoryRepository` | 通用事实记忆仓库（"帮我记住…"收口，remember/update/forget/唯一匹配删/observeAll） |
| `preview/` | `BeautyPreviewProvider` | 美颜预览提供者接口 |

### 2.3 数据层 (`data/`)

| 子包 | 内容 | 说明 |
|------|------|------|
| `local/` | `AppDatabase`（Room v23）、`MediaDao`、`ChatMessageDao`、`ChatSessionDao`、`PersonRelationDao`、`MemoryFactDao` | Room 数据库 + DAO；v13 新增 `person_relations`（人物关系边，FK→persons CASCADE）与 `memory_facts`（事实记忆），`persons` 加 `is_self` 列；v14 `person_relations` 加 `customLabel` 列（自定义称呼，可空，MIGRATION_13_14）；v15 新增 `chat_image_cache` 表（chat 编辑/优化结果图私有缓存登记，MIGRATION_14_15）；v16 `tag_scan_tasks.pass` 旧值 `QWEN_TAGGING` 改写为 `IMAGE_TAGGING`（枚举重命名，MIGRATION_15_16）；v17 `media_assets` 加 `city` 列（逆地理编码城市，MIGRATION_16_17）；v18 `media_assets` 加 `faceFocusY` 列（人脸纵向聚焦点，MIGRATION_17_18）；v19 `media_assets` 加 `aestheticScore`/`faceQualityScore` 列（美学/人脸质量分，MIGRATION_18_19）；v20 新增 `optimize_feedback` 表（AI 优化抽卡反馈，MIGRATION_19_20）；v21 新增 `dedup_hash` 表（去重 2.0 MD5/pHash 缓存，MIGRATION_20_21）；v22 `media_assets` 加 `blurScore`/`exposureScore`/`lastViewedAt` 列（整理中心 v2 信号，MIGRATION_21_22）；v23 `media_assets` 加 `uri` 非唯一索引（MIGRATION_22_23） |
| `remote/openai/` | OpenAI API 客户端（Retrofit） | 远程 LLM 网络层 |
| `remote/anthropic/` | Anthropic/Claude API 客户端（Retrofit） | 备用远程 LLM |
| `download/` | `LlmModelDownloadManager`、`ModelDownloadForegroundService` | LLM 模型下载管理 + 前台服务 |
| `repository/` | 仓储接口的 Room/DataStore/Network 实现 | 数据源实现 |
| `preferences/` | DataStore Preferences | 用户偏好持久化 |

### 2.4 基础设施 (`core/`、`di/`、`service/`)

| 子包 | 内容 | 说明 |
|------|------|------|
| `di/` | `AppContainer`、`AppContainerImpl` | 手动 DI（无 Hilt/Dagger） |
| `core/common/` | `Logger`、`PerceptualHash` | 共享工具（`PerceptualHash` 为 MD5/pHash 纯算法，供去重 2.0 `DedupScanner` 复用） |
| `core/designsystem/` | `Color`、`Theme`、`Typography` | Compose 设计系统 |
| `core/image/` | `CoilConfig`、`GpuBeautyProcessor`、`ImageProcessor` | 图片加载与处理 |
| `service/chat/` | `FloatingChatBubbleService` | 悬浮聊天气泡 |
| `service/accessibility/` | `PoLangAccessibilityService` | Agent 自动化辅助服务 |
| `testing/` | Agent 自动化测试框架 | 测试基础设施 |

---

## 3. 模块集成 (Module Integration)

### 3.1 依赖关系

```
:androidApp
 ├── :shared                ← Agent 编排层 KMP 模块（commonMain 引擎无关层 + androidMain 平台实现；Agent 框架为 Koog 外部依赖）
 ├── :engines:beauty-api    ← 美颜 API 契约
 ├── :engines:beauty-engine ← 美颜引擎实现
 └── :engines:sentencepiece ← SentencePiece tokenizer
（:engines:agent-native / :engines:mnn-core 经 :shared androidMain 传递，不直接依赖）
```

### 3.1.1 Agent 组合根（Phase 4 KMP 抽取新增）

`agent/AndroidAgentComposition.kt` 是所有 Agent 平台实现的**唯一直构点**：`Application.onCreate` 调 `initialize(context)`，构建 DataStore 存储（`KoogMessageMemoryStore`/`MemoryManager`）、端侧 VLM 引擎（`LocalLlmEngine`）、chat/相机工具集（`asToolsByClass()` 反射展开为 ToolDescriptor 清单 + ToolRegistry 同源派生，保 prompt 与工具零漂移）、飞书 RPA 工具集（`RemoteControlToolService` 懒构建，取 WindowManager），经 `AgentOrchestrator.initialize(AgentDependencies)` 一次性注入。commonMain 侧 `AgentOrchestrator` 只暴露无参 `getInstance()`。

### 3.1.2 Agent 平台组件迁入（Phase 4 Task 13，自 runtime-core）

`agent/core/` 子树承载依赖 Android 平台、无法进 shared commonMain 的 Agent 组件：

| 路径 | 内容 |
|------|------|
| `agent/core/inference/remote/tool/` | `RemoteControlToolService`（飞书 RPA @Tool 集，11 个 suspend 工具方法，实现 JVM-only `reflect.ToolSet`，由组合根懒构建 ToolRegistry） |
| `agent/core/tool/perception/` | `ViewHierarchyExtractor`（WindowManager 视图层级抽取） |
| `agent/core/tool/accessibility/` | `AccessibilityServiceHolder` / `AccessibilityActionPerformer` / `AccessibilityNodeDumper`（无障碍服务桥） |

配套守卫：`androidApp/src/test/.../RemoteInferenceNoMediaUploadGuardTest`（ADR-008 隐私红线防回归，扫描本模块 `inference/remote/` 源码不出现媒体上传符号，token 列表与 shared 侧副本一致）。

### 3.2 关键集成点

| 集成场景 | 入口类 | 说明 |
|----------|--------|------|
| Agent 交互 | `AiAgentUseCase` → `AgentOrchestrator` | Facade 模式，委托给 :shared 的 `AgentOrchestrator` |
| 美颜预览 | `BeautyPreviewProvider` → `BeautyPreviewEngine` | 通过 beauty-api 接口调用 |
| 人脸检测 | `FaceDetector`（beauty-api 接口） | MediaPipe/MNN 双引擎 |
| 远程推理 | `KoogReActAgent`（:shared） | OpenAI Chat Completions / Anthropic Messages 双协议（`RemoteProtocol` 分流）+ tool_calls |
| TAG 生成 | `TagGenerationService` → `TagScanOrchestrator` | 3-Pass 混合管道（FACE_DETECTION/DBSCAN/IMAGE_TAGGING，另有 legacy `MOBILE_CLIP_ENCODING`），OpenCL 超时自动降级 CPU；人脸对齐采用方案 B（2D106 关键点替换 RetinaFace 5 点），ROI/2D106/ArcFace R100 均优先走 MNN OpenCL GPU；ETA 按 Pass 独立统计、取中位数并设冷启动默认值 |
| 自然语言搜索 | `GallerySearchBar` → `MediaSearchEngine`<br>`ChatViewModel` → `ChatSearchCapability` → `MediaSearchEngine` | **Gallery 入口**：Layer 0.5 QuerySegmenter → Layer 1 QueryParser → Layer 2 显式召回 → Layer 2.5 MobileCLIP 语义 → Layer 3 融合排序。<br>**Chat 入口**：远程 LLM 输出 `AgentCommand.SearchMedia(query, intent)`，`ChatViewModel` 将 `SearchIntent` 转为 `StructuredFilter` 后直接调用 `MediaSearchEngine.search(filter)`；多轮细化走 `RefineMediaSearch` 并在上一轮结果集内过滤。`QueryParser` 新增近半年/近 N 个月规则作为兜底。 |
| JS 沙箱脚本 | `ChatRunScriptCapability` → `ChatViewModel.onRunScript/onDrawChart` → `JsRuntime`（QuickJS） | LLM tool_call（run_gallery_script/draw_chart）经 CapabilityRegistry（CHAT 场景）落入持久 JsRuntime；`jsEvalMutex` 串行 eval，超时 5s（含 capability.dispatch 写脚本放宽至 180s）；写操作经 CommandRisk 分级 + 用户确认 → `ChatMediaWriteCapability` |
| Chat 对话式图片编辑 | `ImageEditCapability` → `ChatEditProcessor` | 复用 PhotoEditor 的 Recipe → Bitmap 渲染链路；`ChatEditStateHolder` 维护会话级 Recipe 支持多轮 delta；`AGENT_EDIT_RESULT` 消息 inline 展示结果图与说明 |
| 人物关系图谱 | 编辑人物对话框（GalleryScreen 人物分组标题点击，内嵌 `PersonRelationPicker`）/ 聊天 `remember_person_relation` → `PersonRelationCapability` → `PersonRepository` → `person_relations` | 「X 是我 Y」双通路声明（对话框 RENAME_DIALOG / 聊天 CHAT_DECLARATION），声明幂等覆盖即纠错（customLabel 同步覆盖）。**两层关系模型**：粗谓词（机器逻辑，封闭枚举：SPOUSE/PARTNER/SON/DAUGHTER/CHILD/FATHER/MOTHER/PARENT/ELDER_BROTHER/ELDER_SISTER/YOUNGER_BROTHER/YOUNGER_SISTER/SIBLING/GRANDFATHER/GRANDMOTHER/GRANDPARENT/GRANDCHILD/OTHER_FAMILY/FRIEND/CLASSMATE/COLLEAGUE/OTHER，CHILD/PARENT/SIBLING/GRANDPARENT 为"未指定桶"）+ `customLabel` 自定义称呼（用户语言，展示与查询解析优先于谓词）。"这是我"标记存 `persons.is_self`（全局唯一）；编辑入口共用 `features/common/PersonRelationPicker`（家庭/社会分组 chips + 自定义输入框 + 不设置）；「AI 记忆」页关系可编辑（`updateRelation` 保留 source、刷新 updatedAt）。Pass 2 全量重聚经 `NamedPersonSnapshot` + `RelationSnapshotRestorer` 按名字/isSelf 恢复关系（含 customLabel）。**附修复**：全量重聚 `clearAllPersons` 后复用分支原仅 `updatePersonStats`（对已删行为 no-op，命名/is_self 实际丢失），已改为按原 personId 显式 `insertPerson` 重建人物行，使命名保留真正生效 |
| 多人物共现搜索 | `MediaSearchEngine.collectPersonMediaIds` → `PersonQueryResolver` → `PersonDao.getMediaByPersonsCooccurrence` | 原始 query 按优先级解析人物：① 自定义称呼精确匹配（query contains customLabel，"二儿子""发小"精确命中单簇）→ ② 已命名人物 contains → ③ 亲属称谓（`KinshipLexicon`，已被命中 customLabel 包含的称谓抑制；长短称谓去重如"爸爸"抑制"爸"）→ ④ 合拍 Pattern 的"我"。称谓查询按谓词族扩展：具体称谓含同族未指定桶（女儿→{DAUGHTER, CHILD}），泛化称谓含整族（孩子→{SON, DAUGHTER, CHILD}）。≥2 personId 走共现查询（同框合照），恰好 1 个走单人物查询，0 个回落原人名 LIKE 兜底；chat 与 Gallery 搜索路径自动获得 |
| 事实记忆 | 聊天 `remember_fact`/`recall_memory`/`forget_fact` / JS `capability.dispatch` → `MemoryCapability` → `MemoryRepository` → `memory_facts`；设置页「AI 记忆」（`MemoryFactsScreen`：人物关系区查看/编辑/删除 + 事实区查看/编辑/删除/清空） | LIKE 召回（v1 无 FTS）；遗忘按 factId 或唯一匹配（多候选不删）；JS 写操作走确认门控，chat 直调不弹窗 |
| 工具执行指标（tool_call_log） | `CommandExecutor`（:shared）→ `CommandExecutionRecorder` → `RoomToolCallRecorder` → `polang_llm_log.db` | Capability 业务失败以 `Result.success(AgentAction.Error)` 返回（如引导性错误），记账按 action 语义：`AgentAction.Error` 记 `success=0` + errorCode/errorMessage，其余 action 记 `success=1`；只记纯指标（capability/method/耗时/结果），不含命令参数（隐私红线） |
| 用户问题上报（report-issue） | Chat 顶部「上报问题」入口 → `IssueReportClient`（`data/remote/picme/`）→ `POST /v1/report-issue` | 用户问题描述经服务端脱敏后自动在 `littleseven/polang` 创建 GitHub issue；管理后台「问题诊断」页（`/admin/diagnosis`）承载上报列表 |

---

## 4. 编码规范 (Code Conventions)

### 4.1 全局强制规则

- **包名**：禁止 `com.mamba.picme.*` 完全限定名（使用 import）
- **导入**：禁止通配符导入（`import com.mamba.picme.*`）
- **Lambda**：参数必须显式命名（禁止 `it`）
- **日志标签**：格式 `PoLang:[FeatureName]`（如 `PoLang:Camera`、`PoLang:Chat`）
- **滑杆组件**：全 app 滑杆统一使用 `core/designsystem/components/AppSlider.kt`（HyperOS 风：胶囊轨道 + 白圆点描边 thumb），禁止直接裸用 M3 `Slider` 或自定义配色
- **强制深色页面**：相机/证件照等强制深色背景的页面，内容区统一包 `core/designsystem/Theme.kt` 的 `PoLangForcedDarkTheme`，使 colorScheme 与深色视觉场景对齐（否则 onSurface 派生色在浅色主题下不可见）
- **缩进**：Kotlin 4 空格；XML/JSON/MD 2 空格

### 4.2 I18N（强制）

- 禁止硬编码用户可见字符串
- 所有字符串资源在 `values/strings.xml`（EN）、`values-zh-rCN/strings.xml`（简中）、`values-zh-rTW/strings.xml`（繁中）、`values-es/strings.xml`（西语）、`values-fr/strings.xml`（法语）五语同步

### 4.3 红线（不可突破）

| 红线 | 检查方式 |
|------|----------|
| `[PRIVACY]` 禁止向远程大模型上传图片/视频文件（媒体处理端侧；文本/元数据可远程；自配 IM 通道回传除外，ADR-008） | 网络抓包（远程推理请求体无图片/视频）、权限清单扫描 |
| `[PERF]` 交互 < 100ms，快门 < 50ms | 性能测试 |
| `[I18N]` 五语同步（EN/zh-CN/zh-TW/ES/FR），禁止硬编码 | 资源文件检查 |
| `[AGENT-FIRST]` 遵循 Agent First 原则（显式、枚举、自描述） | CR 审查 |

---

## 5. 已有子模块 AGENTS.md

以下子模块已有独立 AGENTS.md，本文档不重复其内容：

- `features/camera/AGENTS.md` — 相机模块实现规范
- `features/gallery/AGENTS.md` — 相册模块实现规范
- `features/common/chat/AGENTS.md` — Chat UI 共享组件规范（Camera/Gallery 复用）
- `features/editor/AGENTS.md` — 图片编辑模块
- `features/settings/AGENTS.md` — 设置模块
- `features/debug/AGENTS.md` — 调试工具模块
- `core/AGENTS.md` — 核心工具
- `core/designsystem/AGENTS.md` — 设计系统
- `data/AGENTS.md` — 数据层
- `di/AGENTS.md` — 依赖注入
- `domain/agent/capability/AGENTS.md` — Agent Capability 实现

---

## 6. 常见变更检查清单

- [ ] 新增 Feature 页面已注册到 `Screen.kt` 和 `MainActivity.kt` NavHost
- [ ] 新增数据表已更新 `AppDatabase.kt` + DAO + 版本迁移
- [ ] 新增依赖已通过 `libs.versions.toml` 管理
- [ ] UI 字符串已五语同步
- [ ] 日志标签遵循 `PoLang:[FeatureName]` 格式
- [ ] 不跨层引用：features 不直接引用 data 实现类
- [ ] 跨模块调用使用接口（`beauty-api` 等公开 API）

---

> **维护者**：项目开发者
> **最后更新**：2026-09-06
> **状态**：生效中
