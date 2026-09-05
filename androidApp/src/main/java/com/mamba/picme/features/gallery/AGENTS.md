# 相册模块技术实现规范 (Gallery)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 相册自然语言搜索的完整链路以 `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` 为唯一事实来源（SSOT）。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

> **版本**: 1.4  
> **状态**: 生效中  
> **最后更新**: 2026-08-26  
> **维护者**: 项目开发者

**模块定位**: 应用默认首页，提供智能聚类相册浏览、媒体查看器、批量操作功能；支持端侧自然语言搜索；语音 Agent 面板提供自然语言交互入口。二级能力入口：悬浮底部 Tab（相册整理/聊天/打标/人物，切主页面 Pager 页）+ 顶栏（模型中心/设置）+ 相册页左滑（相册整理）。「相册整理」（去重 2.0）为主页面 Pager 页 1（相册页左滑即达），另有悬浮底部 Tab 第一项 + 设置主菜单一级入口（2026-08-26 二轮升级）；相机已路由化，无常驻入口。

**主要维护者**: 项目开发者

**阅读对象**: RD、QA、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[PERF] 高刷流畅滚动**: 1000+ 张照片滑动保持 60fps，缩略图加载跟手无白屏
- **[LOCAL] 纯本地聚类**: 人脸分组、重复检测全部在设备端完成，严禁上传云端
- **[SEARCH] 自然语言搜索**: 检索与召回纯本地推理，结构化召回优先，语义召回补充，无需联网；但 Chat 场景的搜索意图（`SearchIntent`）由远程 LLM 生成后传入本地搜索（Gallery 入口搜索则完全本地）
- **[I18N] 多语言支持**: 分组标题、操作按钮必须提取到 strings.xml
- **[FEEDBACK] 流体动效**: 图片展开/收起跟随手指轨迹，缩放平移过渡自然
- **[MEMORY] 内存优化**: LruCache 限制上限，OOM 时自动降级清晰度

## 2. 技术实现规范 (Technical Implementation)

### 2.1 智能聚类引擎 (Grouping Engine)

**技术规范**:
- **分组模式**:
  - `NONE`: 按时间倒序平铺
  - `DATE`: 按日期分组（今天、昨天、本周、本月）
  - `PERSON`: 按人物聚类（人脸检测走 beauty-engine `FaceDetectorManager`，MediaPipe 468→106 点主链路 + MNN 备选；人脸 embedding 由 MNN Glint360K R100 生成，TAG Pass 2 经 DBSCAN 聚类）。分组按 `media_assets.faceId` 聚合，该列由 Pass 1 流式增量聚类（`FaceClusterEngine.assignStoredEmbeddings`）与 Pass 2 DBSCAN 共同回写；存量缺失由 `PersonDao.reconcilePersons` 的 `backfillFaceIdsFromEmbeddings` 幂等回填
  - `LANDSCAPE`: 风景照片单独分组，匹配 `labels` 中的风景相关 `scene` 或 `tags`（如「风景」「山脉」「海边」「landscape」等）
  - `SWIMWEAR`: 匹配 `labels` 中的泳装相关标签（如「泳衣」「比基尼」「swimsuit」「bikini」）
  - `SEXY`: 匹配 `labels` 中的风格标签（如「性感」「sexy」）
- **UseCase 层**: 通过 `GetGroupedMediaUseCase` 封装分组逻辑，ViewModel 仅负责状态管理
- **Flow 组合**: 使用 `combine(repository.allMedia, _groupingMode)` 响应式更新分组结果
- **性能优化**: 分组计算在 `Dispatchers.Default` 线程执行，避免阻塞 UI

**代码示例**:
```kotlin
val groupedMedia: StateFlow<List<GroupedMedia>> = combine(
    repository.allMedia,
    _groupingMode
) { allMedia, mode ->
    getGroupedMediaUseCase(allMedia, mode)
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = emptyList()
)
```

### 2.2 媒体查看器 (MediaPager)

**技术规范**:
- **HorizontalPager**: 使用 Compose Foundation 的 `HorizontalPager` 实现左右翻页
- **缩放控制**: 
  - 缩放范围: 1.0x ~ 4.0x
  - 放大态禁用翻页 (`userScrollEnabled = !currentPageZoomed`)
  - 单指平移查看细节，双指捏合缩放
- **手势冲突处理**: 
  - 放大时优先图片平移，禁止触发翻页
  - 回到 1x 后恢复翻页功能
- **预加载策略**: `beyondBoundsPageCount = 3` 预加载前后 3 页
- **沉浸式模式**: 隐藏状态栏与导航栏，滑动边缘时 transient 显示

**代码示例**:
```kotlin
@OptIn(ExperimentalFoundationApi::class)
HorizontalPager(
    state = pagerState,
    beyondBoundsPageCount = 3,
    userScrollEnabled = !currentPageZoomed,
    pageSpacing = 16.dp,
    contentPadding = PaddingValues(horizontal = 16.dp)
) { page ->
    ZoomableImage(
        imageModel = mediaAssets[page].uri,
        onZoomChanged = { scale -> currentPageZoomed = scale > 1f }
    )
}
```

### 2.3 批量选择与操作 (Batch Selection)

**技术规范**:
- **进入方式**: 长按任意缩略图进入选择模式
- **连续批选**: 支持拖拽滑动批量选择/取消，减少逐张点击成本
- **数据结构**: 使用 `mutableStateListOf<Long>` 存储选中 ID
- **全选功能**: 提供"全选"按钮快速选中当前视图所有媒体；搜索模式下仅全选搜索结果
- **批量分享**:
  - 单张: `Intent.ACTION_SEND` + `EXTRA_STREAM`
  - 多张: `Intent.ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` ArrayList
  - MIME 类型: 图片用 `image/*`，视频用 `video/*`，混合用 `*/*`
- **批量删除**: 调用 `viewModel.deleteMediaByIds(ids)` 异步删除；搜索结果与主网格共用同一套删除与授权逻辑
- **Scoped Storage 权限处理**:
  - **Android 6~9 (API 23-28)**: 运行时申请 `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE`，通过 `ContentResolver.delete()` 直接删除
  - **Android 10 (API 29)**: 捕获 `RecoverableSecurityException`，保存 `userAction.actionIntent.intentSender`，通过 `StartIntentSenderForResult` 请求单条授权，授权后重试删除
  - **Android 11+ (API 30+)**: 收集失败的 URI 列表，使用 `MediaStore.createDeleteRequest()` 发起批量系统授权对话框，用户允许后系统自动完成物理删除
- **数据一致性**: 必须遵循"先物理文件、后数据库记录"的顺序。若删除需要用户授权，必须推迟 Room 数据库清理，直到授权成功后再执行，避免用户拒绝后出现"文件还在、记录已消失"的不一致状态
- **搜索模式一致性**: `GalleryScreen` 订阅 `viewModel.allMedia`，媒体库变更（如删除完成）后自动重新执行当前搜索词并刷新结果网格

**代码示例**:
```kotlin
private fun shareMediaAssets(context: Context, assets: List<MediaAsset>) {
    if (assets.isEmpty()) return

    val uris = assets.map { it.uri.toUri() }
    val shareIntent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.first())
            type = if (assets.first().type == MediaType.VIDEO) "video/*" else "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    context.startActivity(Intent.createChooser(shareIntent, null))
}
```

### 2.4 重复照片清理（去重 2.0，Dedup 2.0）

> 旧实现（`DuplicateManager` 页 / `FindDuplicateMediaUseCase` / `DuplicateImageDetector`）已于 2026-08-26 Task 11 整体下线删除；`core/common/PerceptualHash.kt`（MD5/pHash 纯算法，零 Android 依赖、可 JVM 单测）保留，由新扫描器复用。

**技术规范**:
- **入口（2026-08-26 二轮升级）**: 相册整理为**主页面 Pager 页 1**（页序 相册(0)/相册整理(1)/聊天(2)/人物(3)，见 `features/main/MainPagerHost.kt`；`DedupViewModel` 为 Activity 级独立 VM，Pager 托管安全）——相册页左滑经外层 HorizontalPager 原生手势进入，悬浮底部 Tab 第一项（`Icons.Outlined.BurstMode`）与设置主菜单「相册整理」一级入口均经 `switchMainPage(MAIN_PAGE_DEDUP)` 瞬时切页；原 `Screen.DedupHome`（route `dedup_home`）NavHost 路由已删除（避免双宿主）。返回（顶栏返回/系统返回键）切回相册页不弹栈；TagControl 头部「管理重复照片」行已移除
- **三级尺度（`DedupLevel`，`domain/dedup/DedupModels.kt`）**:
  - `EXACT` 精确重复：`(sizeBytes, mime)` 分桶 → 流式 MD5 相同成组
  - `VISUAL` 视觉重复：32×32 降采样 64-bit pHash，汉明距离 ≤ `visualThreshold`(=5) 并查集聚类；与 EXACT 组完全重合（全员同 MD5）的簇跳过
  - `SCENE` 相似场景（连拍）：`sceneThreshold`(=8) 聚类 + `sceneTimeWindowMs`(=10s) 拍摄时间窗切桶，仅保留 ≥2 张的桶
  - `DedupScanConfig.levels` 默认 `{EXACT, VISUAL}`，SCENE 由用户在 Config 页勾选
- **扫描器（`domain/dedup/DedupScanner.kt`）**: cold Flow 流式扫描，逐批/逐组流出 `DedupScanEvent`（Progress/PhaseChanged/GroupFound/Done/Cancelled）；哈希分批 500（SQLite IN 参数上限），MD5/pHash/pixelArea 缓存进 Room `dedup_hash` 表（`modifiedAt + sizeBytes` 一致则复用）；暂停/恢复经 `DedupScanController.pauseRequested` 轮询（200ms；哈希批内每 50 张一个检查点，每张均 `ensureActive` 响应取消），协程取消经 `onCompletion` 补发 `Cancelled`。媒体字节 100% 端侧处理，零上传（[PRIVACY]）
- **保留规则（`KeepPolicyEngine` 四规则）**: `BEST_QUALITY`（像素面积→文件大小→美学分→拍摄日期 降序）/ `ORIGINAL` / `EDITED` / `LATEST`；`classify` 先行标注 `VersionRole`（像素或大小 < 组内最大值 0.5 → `COMPRESSED`；`modifiedAt - captureDate > 6h` → `EDITED`；否则 `ORIGINAL`），keepUri = 排序后首张，UI 可改选（`userOverride`）
- **内容类型差异化（spec §10，`DedupContentType`）**: 取数阶段零额外推理识别 SCREENSHOT（MediaStore `RELATIVE_PATH` 含 Screenshots，API 29+；API 24-28 无该列，以 `DATA` 列路径兜底，截图识别退化基本消除）/ DOCUMENT（ocrText 文字密度 = 字符数/像素面积 > 20 字符/MP，WIDTH/HEIGHT 自 API 16 可用、全版本入 projection，列缺失/脏值退回 >200 字符绝对兜底；或 labels 命中 document/receipt/text 类关键词——英文整词匹配（容忍复数后缀）避免 context/texture 误伤，中文子串，`detectContentType` 纯函数）/ PORTRAIT（hasFace 或 faceQualityScore 非空）/ GENERAL 兜底，优先级 SCREENSHOT > DOCUMENT > PORTRAIT > GENERAL；VISUAL 聚类按 contentType 分桶（跨桶不成组），截图桶用收紧阈值 `screenshotVisualThreshold`(=3)；人像组保留排序前置 faceQualityScore（null 排最后）；`DedupGroup.autoPreselected`（EXACT→true、VISUAL 截图/文档→false、SCENE→false）为 false 时 deleteUris/reclaimBytes 为空、不进批量 CTA（口径收口 `DedupGroup.batchEligible`），详情改选（userOverride）后正常派生参与删除；组卡片有中性描边内容类型 badge（GENERAL 不显示）与类型差异化 footer 文案；未预选未改选组 UI 不显示「0 张/0 B」——meta 行改显示按 keepUri 派生的预计可释放量（`potentialReclaimBytes`，「预计」措辞），详情确认按钮改「确认本组选择」中性文案
- **状态机（`DedupViewModel`，Agent First sealed 枚举）**: `Config` → `Scanning`（渐进 `GroupFound`，含 paused）→ `Results`（按 DedupLevel 分 tab + policy 切换）→（系统授权）→ `Cleaned`（可 undo/done 回 Config）；保留策略 `_policy` 为 VM 级 StateFlow，Config 规则行与 Results 共用；扫描 `Done` 时按当前 policy 对全部组 `resortGroup` 重算默认勾选（扫描器固定按 BEST_QUALITY 建组）。**SCENE 组不参与批量删除**（spec §4 安全约束）：`batchDeleteUris`/`batchReclaimBytes` 派生值统一 `filter batchEligible`（SCENE 组与未预选未改选组均不参与），VM 删除流与结果页底部 CTA 同一口径
- **回收站（`DedupTrashManager`）**: API 30+ 走 `MediaStore.createTrashRequest` 移入系统回收站（Cleaned 态可 undo 恢复）；`buildTrashIntent` 经 ioDispatcher + runCatching 保护，失败记日志保持 Results 态；部分拒绝（授权后仍有残留 uri）置位 `partialTrashNotice` 一次性事件槽位，UI 弹 snackbar 提示；API < 30 无回收站授权接口，由 UI 层注入 `legacyDeleter` 回调兜底走 `MediaViewModel.deleteMediaByIds` 旧删除授权流。**已知限制：API ≤ 29 删除授权链路当前为降级路径（legacyDeleter），API 29 上授权弹窗可能无人拉起，待修**
- **残留复查陷阱（2026-09-05 修复）**: `DedupTrashManager.queryExisting` 判残留必须投影并读取 `IS_TRASHED` 列——部分 ROM（实测 HyperOS/Android 16）对已 trash 行的直接 item-URI 查询仍返回该行（AOSP 默认查询会过滤 trash 行），仅判「行可查」会把成功回收误判为部分拒绝，导致组保留不刷新（「点了没反应」表象）。行存在且 `IS_TRASHED=0` 才算真残留
- **删除两通路（2026-09-04 补齐）**: ① 结果页底部 CTA `deleteSelected()` 按当前 Tab 批量删除，确认后进 `Cleaned` 完成页（可 undo）；② 组详情「保留所选 · 删除其余 N 张」`deleteGroup(groupId)` 逐组立即删除（L3 场景相似唯一删除通路），`PendingTrash.groupId` 标记区分，确认后**留在 Results 原地刷新**不进完成页；两路共用 `computeRemainingGroups`（删净组移出 / 跨级重叠组剔除幽灵成员 / keepUri 被删按 policy 重算 / 存活 <2 不成组）与部分拒绝 `partialTrashNotice` 语义
- **UI 组件清单（`features/gallery/dedup/`）**: `DedupHomeScreen`（页面 + Route）、`DedupSheets`（`DedupGroupDetailPage` 全屏组详情页——点「保留这张」改选后立即收起回结果列表（2026-09-05 交互修正），删除仍走详情 CTA/批量 CTA——+ 组内全屏对比预览 + `KeepRulesSheet` 保留规则底部弹层——组详情 2026-08-27 起由半屏弹层改全屏页）、`DedupComponents`（组卡片/缩略图等）、`DedupMediaSource`（扫描输入供数，`MediaType.PHOTO` 元数据 + modifiedAt）
- **整理中心类目详情（F1，`features/gallery/organize/`，2026-09-05）**: `OrganizeCategoryScreen`（NavHost 二级页 `organize_category/{category}`：副行统计 + 3 列 AI 预选勾选网格 + 底部回收站 CTA；完成态 All clean + Undo）+ `OrganizeCategoryViewModel`（类目过滤 + 选中集 + `TrashSessionController` 删除/恢复编排，测试注入 coroutineScope 范式同 DedupViewModel）；hub 类目卡点击经 `onOpenCategory` 点亮该路由
- **手势快速整理（F2，`features/gallery/swipe/`，2026-09-05）**: `SwipeReviewScreen`（NavHost 二级页 `swipe_review`：全屏大图卡手势决策——右滑保留 / 左滑跳过 / 上滑删除、点按=跳过，卡片跟手位移 + 超阈值（宽 25%）飞出落决策，预加载后续 3 张；完成态统计卡 + 再来一轮 + 整批恢复）+ `SwipeReviewViewModel`（`SwipeQueueBuilder` 废片优先建队、undo 栈、未提交 DELETE 累计 20 张自动提交一批系统回收站授权，Done 态整批 undoAll 恢复）；hub「Quick tidy up」渐变主按钮经 `onQuickTidy` 点亮该路由；队列入列原因（Screenshot/Blurry/Low-quality portrait/Recent）为卡片角标，视频永不入队
- **Pager 页单根铁律（2026-09-04 事故修复）**: `DedupHomeRoute` 必须以单个 `Box(fillMaxSize)` 包裹 Scaffold + 详情页 + 预览层——HorizontalPager 会把 page 内容的多个根节点沿主轴顺序平铺（`MeasuredPage` 按 child 宽度累加 offset，非 Box 式堆叠），多根会导致详情页/预览层被排到屏外，点击「没反应」。Gallery/Person/Chat 页均为单根模式；页内新增全屏覆盖层一律挂进该 Box，不得作为独立根节点

**代码示例**:
```kotlin
// DedupViewModel：收集扫描事件流，渐进式推进状态机
scanner.scan(items, config).flowOn(ioDispatcher).collect { event ->
    when (event) {
        is DedupScanEvent.GroupFound -> /* 追加到 Scanning.foundGroups */
        is DedupScanEvent.Done -> _uiState.value = DedupUiState.Results(event.groups, ...)
        is DedupScanEvent.Cancelled -> _uiState.value = DedupUiState.Config(config)
        // Progress / PhaseChanged → 更新 Scanning 进度
    }
}
```

### 2.5 自然语言搜索 (Natural Language Search)

> 完整实现链路、性能预算与源文件索引见 `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md`。

**入口**: `GalleryTopBar` 搜索按钮 → `SearchTopBar` 输入框，搜索结果显示在 `GalleryScreen` 结果网格。

**技术规范**:
- **本地优先**: 所有查询在设备端完成，禁止上传用户照片或原始 query 到云端
- **搜索触发**: 输入框文本变化后无额外 debounce（由用户输入事件天然节流）；`GalleryScreen` 使用 `rememberCoroutineScope` 直接调用搜索引擎
- **搜索引擎**: `GalleryCapability.searchEngine`（`MediaSearchEngine`）由 `AppContainer` 注入，搜索失败时回退为空结果
- **召回链路**:
  1. `QuerySegmenter` 对中文 query 按语义/时间/实体分段
  2. `ExplicitFirstSearchPipeline` 优先执行结构化 SQL 召回（时间、地点、人脸、OCR、TAG 等显式维度）
  3. `SemanticSearchEngine`（MobileCLIP）在显式召回不足时补充语义召回
  4. `MediaSearchEngine.mergeAndRank()` 融合排序，结构化结果优先
- **结果展示**: 搜索命中结果包装为 `GroupedMedia(titleType = SEARCH)`，使用与主网格相同的 `MediaGrid` 组件，支持长按进入批量选择
- **结果刷新**: `GalleryScreen` 通过 `snapshotFlow { allMedia }.debounce(300)` 监听媒体库变化，搜索激活时自动重新执行当前 query
- **缩略图安全**: 搜索结果缩略图禁用 Coil crossfade，避免 `Canvas: trying to use a recycled bitmap` 崩溃

**代码示例**:
```kotlin
// GalleryScreen.kt
var searchQuery by remember { mutableStateOf("") }
var isSearchActive by remember { mutableStateOf(false) }
var searchResultMedia by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
val searchEngine = remember { GalleryCapability.getInstance().searchEngine }

SearchTopBar(
    searchQuery = searchQuery,
    onQueryChange = { query ->
        searchQuery = query
        if (query.isNotBlank() && searchEngine != null) {
            searchScope.launch {
                searchResultMedia = searchEngine.search(query).media
            }
        } else {
            searchResultMedia = emptyList()
        }
    },
    onClose = {
        searchQuery = ""
        searchResultMedia = emptyList()
        isSearchActive = false
    },
    resultCount = if (searchQuery.isNotBlank()) searchResultMedia.size else null
)
```

### 2.6 首页导航与底部悬浮 Tab

**首页定位**:
- `GalleryScreen` 由 `Screen.Main`（`MainPagerHost` Pager 容器，NavHost `startDestination`）承载为主页面页 0
- 系统返回键在相册无内部状态（无选择/Pager）时退出应用；主页面其他页按返回键切回相册页（`MainPagerHost` BackHandler）

**底部悬浮 Tab**:
- 使用共享组件 `FloatingBottomTab`（`features/common/components/FloatingBottomTab.kt`）
- 位置：底部居中，底部 padding 16.dp，悬浮于媒体网格之上
- 入口项（从左到右）：相册整理（BurstMode，切到 Pager 页 1）、Chat、Tag 打标控制、人物（AccountCircle）
- 每项仅显示图标，无文字标签
- 点击经 `switchMainPage` 瞬时切主页面 Pager 页（无滑动动画）

**设置入口**:
- 统一放在 `GalleryTopBar` 动作区最右侧
- 点击跳转 `SettingsScreen`

**模型中心入口**:
- `GalleryTopBar` 动作区 CloudDownload 图标直接进入
- 设置主菜单「AI 与系统」组「模型中心」列表行也可进入

**语音 Agent 面板**:
- 位置：右下角，底部 padding 84.dp，位于底部 Tab 上方
- 使用 `GalleryAgentPanel` + `AgentChatPanel` 公共组件
- 负责自然语言相册浏览/编辑/管理指令

### 2.7 图片编辑器（Photo Editor，2026-07 Phase 1）

**模块定位**: 从 MediaPager 进入的独立非破坏性图片编辑器，基于配方（Recipe）模型实现裁剪、调节、美颜、滤镜（Phase 2）、标记（Phase 2）五大类编辑。

**技术规范**:
- **入口**:
  - MediaPager 顶部工具栏 ✨ 编辑按钮（`AutoFixHigh` icon），仅 `MediaType.PHOTO` 显示
  - **长按图片区域**：直接进入编辑器（带 `HapticFeedbackType.LongPress` 触感反馈）
  - 两者均通过 `onNavigateToEditor(asset)` 回调导航到 `Screen.PhotoEditor(sourceUri, recipeUri?)`
- **导航路由**: `MainActivity` NavHost 注册 `photo_editor/{sourceUri}?recipeUri={recipeUri}&autoOptimize={autoOptimize}`，可选参数 `recipeUri` 用于重新编辑已保存的副本，`autoOptimize` 用于进入时自动触发 AI 一键优化
- **非破坏性编辑**: 原图始终不动；保存时生成新文件写入 MediaStore（`Pictures/PoLang/EDITED_${timestamp}.jpg`），并将本次完整配方持久化到 `photo_edit_recipes` 表
- **配方数据模型**: `EditRecipe`
  - `crop: CropRecipe` — 裁剪比例、旋转角度、水平翻转
  - `adjustments: AdjustmentRecipe` — 亮度、曝光、对比度、饱和度、色温、色调
  - `beauty: BeautySettings` — 复用相机侧美颜面板与 GPU 处理管线
  - `colorFilter / styleFilter` — 色调/风格滤镜（Phase 2 占位）
  - `markup: List<MarkupAction>` — 涂鸦/马赛克/文字标记（归一化坐标，已实现）
- **状态与历史**: `PhotoEditorViewModel` 管理 `State`（Loading / Ready / Error），内部使用 `EditHistory` 支持撤销/重做；预览通过 `_recipeChanges.debounce(200)` 自动触发
- **处理管线**: `RecipeApplier`
  1. `applyCrop()` — 按 `AspectRatio` 自动居中裁剪，支持 90° 旋转与水平翻转
  2. `applyGpuEffects()` — 调用 `PhotoProcessor.process()` 应用美颜与 GPU 滤镜
  3. `applyMarkup()` — 叠加涂鸦路径/马赛克 Shader/文字（标记在裁剪与 GPU 效果之后绘制）
- **性能与内存**:
  - 预览图按最长边 2048px 降采样解码
  - 所有处理在 `Dispatchers.Default` / `Dispatchers.IO` 执行
  - `sourceBitmap` 在 ViewModel `onCleared()` 时回收
- **I18N**: 编辑器内所有标签、内容描述、错误提示均已提取到 `strings.xml`，同步覆盖英文 / 简体中文 / 繁体中文 / 西班牙语 / 法语

**代码示例**:
```kotlin
// 从 MediaPager 进入编辑器
onNavigateToEditor = { asset ->
    navController.navigate(Screen.PhotoEditor(asset.uri, recipeUri = null))
}

// 保存成功后返回 outputUri，Gallery 自动刷新媒体库
val onEditSaved: (String) -> Unit = { outputUri ->
    navController.popBackStack()
    viewModel.refreshMediaLibrary()
}
```

### 2.8 OCR 文字识别集成

**技术规范**:
- **触发入口**: 
  - 工具栏"提取文字"按钮
  - ~~长按图片文字区域（已改为进入图片编辑）~~
- **状态管理**: 使用 `MutableStateFlow<OcrResult?>` 管理识别状态（Loading/Success/Error）
- **资源释放**: ViewModel `onCleared()` 时调用 `ocrUseCase.close()` 释放 ML Kit 资源
- **结果展示**: 原位浮层卡片展示识别结果，支持复制与分享
- **异常处理**: 识别失败时显示友好错误提示，记录日志

**代码示例**:
```kotlin
fun recognizeTextFromCurrentImage(context: Context, uri: Uri) {
    viewModelScope.launch {
        _ocrState.value = OcrResult.Loading
        try {
            val result = ocrUseCase.recognizeFromUri(context, uri)
            _ocrState.value = if (result != null) {
                OcrResult.Success(result)
            } else {
                OcrResult.Error("未找到文字")
            }
        } catch (e: Exception) {
            _ocrState.value = OcrResult.Error("识别失败：${e.message}")
        }
    }
}

override fun onCleared() {
    super.onCleared()
    ocrUseCase.close() // 释放 ML Kit 资源
}
```

### 2.9 TAG 生成精细控制（2026-06 新增）

**入口**: `TagGenerationControlScreen`（设置 → AI Agent → TAG 生成控制）

**技术规范**:
- **3-Pass 混合管道**（另有 legacy `MOBILE_CLIP_ENCODING` Pass，仅保留用于历史任务兼容及单独重编码场景，不在常规扫描链中）:
  - **Pass 1**: `FACE_DETECTION` — 人脸检测 + 人脸 Embedding + MobileCLIP 语义编码（语义编码已内联合并到本阶段）
  - **Pass 2**: `DBSCAN` — 全局人脸聚类
  - **Pass 3**: `IMAGE_TAGGING` — Qwen3-VL-2B 端侧多模态标签生成（场景/活动/物体/标签/摘要）
- **类别到 Pass 映射**（`TagCategory.toPasses`）:
  - `FACE` → `FACE_DETECTION` + `DBSCAN`
  - `SCENE / ACTIVITY / OBJECTS / TAGS / SUMMARY` → `IMAGE_TAGGING`
- **队列编排**: `TagScanOrchestrator` 持久化任务队列，支持暂停/恢复/取消/失败重试
- **增量去重**: 默认跳过近期已覆盖所有请求 Pass 的媒体，按 `oldest-first` 排序避免老照片饿死
- **精细控制**: 支持按 `TagCategory`（人脸/场景/活动/物体/标签/摘要）和时间范围（全部/7天/30天/90天）重新生成
- **OpenCL 守护**: `OpenClGuardian` 在 Pass 3 前 warmup，超时后自动降级 CPU 并记录设备黑名单
- **模型加载**: `TagGenerationScheduler.ensureModelLoaded()` 优先 OpenCL（用户开启且未降级），失败/warmup 超时后降级 CPU
- **状态观察**: 通过 `TagGenerationService.sessionProgress` StateFlow 显示进度、预计剩余时间、暂停/恢复按钮

**UI 结构（2026-08 v2 重设计，英文体验优先）**:
- 页面自上而下四个区块：`Library`（Stats 置顶：图库统计 + 语义索引覆盖）→ `Scan`（空闲时 `ScanActionCard`：状态 chip「Up to date / N pending」+ 「Scan new / Rescan all」；扫描中原进度卡 + 会话控制）→ `Stages` → `Regenerate`
- **Stages 区块**：4 个 `StageRow`（Faces/People/Content tags/Quality scores，右侧百分比或人数 + chevron），点按弹 `StageActionSheet`（ModalBottomSheet）提供「Process new only（Recommended 徽章）/ Reprocess everything」两档——替代旧行内「增量/全量」双按钮，避免误触
- **全量二次确认**：选 Reprocess everything 先弹 AlertDialog 确认再下发 intent（`intentScanPass1/2/3Full`、`intentScoreAestheticFull`）
- **Regenerate 区块**：类别/时间范围 chips + 「Overwrite existing」开关（关 = 仅补齐缺失），替代旧「模式: X」单选
- 已删除：Pipeline overview 卡、StatsCard 底部阶段进度条、行内增量/全量按钮
- **视觉规格对齐 Ardot 稿 `gallery/tag_control` / `gallery/tag_stage_sheet`**：卡片=surfaceContainer r16、瓦片/轨道/Cancel=surfaceVariant、弹层/确认框=surfaceContainerHighest、描边=outlineVariant、强调=primary（#8FD6C6）、渐变按钮/大数字=ChatBubbleTokens 品牌渐变；Stats 瓦片无图标（数值 17sp + 标签 11sp）、覆盖环为实色 primary 弧；弹层选项卡带单选圈（推荐项选中态，点卡片直接执行）+ 通栏 Cancel；自定义 44×26 TagSwitch 与 h28 chip（选中=primary 14% 底+描边）
- 文案规范：按钮/标题一律短文案（Faces、Scan new、Rescan all、Regenerate），五语（EN/zh-CN/zh-TW/ES/FR）键同步，新增键以 `tag_section_*` / `tag_stage_*` / `tag_scan_*` / `tag_overwrite_*` 前缀

**代码示例**:
```kotlin
// 启动按类别重新生成
context.startForegroundService(
    TagGenerationService.intentRegenerateCategories(
        context = context,
        categories = listOf(TagCategory.SCENE.name, TagCategory.TAGS.name),
        startTimeMs = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L,
        fullMode = false
    )
)
```

### 2.10 缩略图缓存策略 (LruCache)

**技术规范**:
- **内存缓存**: 使用 Coil 的 `MemoryCache`，占可用内存 25%
- **磁盘缓存**: 占磁盘空间 2%，目录为 `cache/image_cache`
- **加载优先级**: 当前可见项优先加载，预加载相邻项
- **OOM 保护**: 系统内存紧张时自动降低非可见图片质量
- **位置记录**: 使用 `mutableStateMapOf<Long, Rect>` 记录缩略图位置，支持展开动画
- **回收位图保护**: 搜索结果缩略图禁用 Coil crossfade，避免列表滚动时复用已回收 Bitmap 导致崩溃

### 2.11 无障碍语义 (Accessibility Semantics)

**目的**: 让 AccessibilityService / UI Automator / ReAct Agent 能够识别网格中的具体媒体项，而不是只能看到空的可点击容器。

**实现位置**: `features/gallery/components/MediaGrid.kt` → `MediaItem()`

**技术规范**:
- **每个缩略图必须提供 `contentDescription`**，格式为 `"<类型>，<文件名>"`
  - 照片: `R.string.media_type_photo` + `asset.fileName`
  - 视频: `R.string.media_type_video` + `asset.fileName`
  - 文档: `R.string.media_type_document` + `asset.fileName`
- **视频叠加图标**单独设置 `contentDescription = R.string.media_type_video`
- **选择模式下**通过 `stateDescription` 暴露选中状态
  - 已选中: `R.string.media_state_selected`
  - 未选中: `R.string.media_state_unselected`
- **多语言**: 类型与状态文案必须提取到 `strings.xml`（已支持 zh / zh-rCN / zh-rTW / en / es / fr）

**验证方式**:
```bash
adb shell settings put secure enabled_accessibility_services com.mamba.picme/.accessibility.PoLangAccessibilityService
adb forward tcp:27183 tcp:27183
python3 scripts/ui_driver.py dump
```

预期在相册网格中能看到类似：
```
[android.view.View] 照片，TEST_PERSON_林依晨_1782859757911.jpg clickable, bounds=(...)
[android.view.View] 视频，share_xxx.mp4 clickable, bounds=(...)
```

## 3. adb 自动化测试命令 (Gallery Test Commands)

> **实现状态（2026-07）**: 旧版 Camera/Gallery 同源广播命令体系已随图片编辑器重构移除。当前仅保留可直接通过 `am broadcast` 触发的导航类命令；编辑器内部参数调节命令后续通过 UI Automator / 新的测试通道覆盖。

**命令列表**:

| 命令 | 参数 | 说明 |
|------|------|------|
| `enter_gallery` | — | 从相机页进入相册（CameraScreen 处理） |
| `open_photo` | `index` (int) | 跳转到指定索引的图片 |
| `long_press_photo` | — | 长按照片，触发进入独立图片编辑器 |
| `start_ocr` | — | 触发 OCR 文字识别 |
| `dismiss_ocr` | — | 关闭 OCR 结果浮层 |
| `toggle_landmark` | — | 切换人脸关键点覆盖层 |
| `toggle_info` | — | 切换信息浮层显示 |
| `delete_photo` | — | 删除当前照片 |
| `share_photo` | — | 分享当前照片 |
| `start_tag_scan_all` | — | 启动 TAG 全量扫描 |
| `start_tag_scan_incremental` | — | 启动 TAG 增量扫描 |
| `pause_tag_scan` | — | 暂停当前 TAG 扫描 |
| `resume_tag_scan` | — | 恢复当前 TAG 扫描 |
| `cancel_tag_scan` | — | 取消当前 TAG 扫描 |

**adb 示例**:
```bash
# 进入相册
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action enter_gallery

# 打开第 3 张图片（索引从 0 开始）
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action open_photo --ei index 2

# 长按照片进入编辑器
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action long_press_photo

# 启动 TAG 全量扫描
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action start_tag_scan_all

# 暂停 / 恢复 / 取消 TAG 扫描
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action pause_tag_scan
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action resume_tag_scan
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action cancel_tag_scan
```

**实现细节**:
- TAG 扫描命令通过 `TagGenerationService` 对应 Intent 触发，无需 UI 处于前台
- 编辑器导航命令由 `MediaPager` 通过 `LaunchedEffect` 订阅并转换为 UI 回调；编辑器内部状态不再通过广播命令控制

## 4. Agent 执行规约 (Execution Rules)

- **图片加载**: 必须使用 Coil 框架，配置内存与磁盘缓存策略
- **线程管理**: 分组计算、OCR 识别、搜索执行必须在后台线程执行
- **手势冲突**: 放大态必须禁用 HorizontalPager 翻页 (`userScrollEnabled = false`)
- **资源释放**: OCR Detector、Bitmap 使用后必须调用 `close()` / `recycle()`
- **I18N**: 所有分组标题、操作按钮、搜索空态文案必须提取到 strings.xml
- **日志规范**: 关键操作（分组切换、删除、OCR、搜索）需记录 `PoLang:Gallery` 日志
- **权限处理**: 读取相册需申请 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` 权限
- **状态持久化**: 分组模式切换后无需持久化，应用重启恢复默认 `NONE`
- **搜索注入**: `GalleryCapability.searchEngine` 必须由 `AppContainer` 在应用启动时注入，禁止直接 `new MediaSearchEngine(...)`

## 5. 常见陷阱检查清单 (Checklist)

- [ ] 是否在 UI 线程中执行了分组计算？(必须使用 Dispatchers.Default)
- [ ] LruCache 是否设置了合理的上限？(避免 OOM)
- [ ] 放大态是否正确禁用了翻页？(userScrollEnabled = false)
- [ ] OCR 引擎是否在 ViewModel 销毁时释放？(onCleared 中 close)
- [ ] 批量分享是否正确授予了 URI 权限？(FLAG_GRANT_READ_URI_PERMISSION)
- [ ] 滚动时是否频繁创建对象？(应在构造函数中初始化)
- [ ] Flow 是否正确使用了 stateIn？(避免重复订阅)
- [ ] 图片加载是否处理了异常？(Coil 的 onError 回调)
- [ ] 长按触发批选时是否有触感反馈？(HapticFeedback)
- [ ] 重复扫描是否在后台线程执行？(避免阻塞 UI)
- [ ] TAG 扫描任务是否正确持久化到 `tag_scan_tasks` 表？(异常恢复)
- [ ] Pass 3 Qwen 推理是否经过 `OpenClGuardian` 超时保护？(防止 OpenCL 挂起)
- [ ] 按类别重新生成时是否正确映射到 Pass 阶段？(人脸→Pass 1+2，其他类别→Pass 3)
- [ ] 沉浸式模式是否在 DisposableEffect 中正确清理？(onDispose 恢复系统栏)
- [ ] 缩略图位置记录是否在重组时丢失？(使用 remember)
- [ ] 搜索结果缩略图是否禁用 Coil crossfade？(避免 recycled bitmap 崩溃)
- [ ] 搜索模式下删除/授权后是否重新执行当前 query？(观察 `allMedia` 变化)
- [ ] 搜索空态、结果数量文案是否已提取到 strings.xml？(I18N)

## 6. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ 高刷流畅滚动 → LazyVerticalGrid + Coil 缓存 + beyondBoundsPageCount
- ✅ 智能聚类 → GetGroupedMediaUseCase 支持 6 种分组模式
- ✅ 流体动效 → HorizontalPager + ZoomableImage 手势联动
- ✅ 批量操作 → mutableStateListOf 支持连续批选与全选；搜索结果支持相同操作
- ✅ 独立图片编辑器 → 非破坏性配方编辑，裁剪/调节/美颜五语本地化，保存为新副本并持久化配方
- ✅ OCR 本地识别 → ML Kit 离线引擎，ViewModel 生命周期管理
- ✅ TAG 生成控制 → 3-Pass 队列 + 类别/时间范围精细控制 + OpenCL 超时降级
- ✅ 自然语言搜索 → `MediaSearchEngine` + `SemanticSearchEngine` 本地召回；结果网格支持长按批量选择

**技术决策记录**:
- 选择 HorizontalPager 而非 ViewPager2：与 Compose 生态无缝集成，手势控制更灵活
- 使用 StateFlow 而非 LiveData：支持冷流、操作符丰富，与 Coroutines 深度整合
- LruCache 上限设为内存 25%：平衡清晰度与内存占用，OOM 风险可控
- 分组计算放在 UseCase 层：遵循 Clean Architecture，便于单元测试与复用
- OCR 资源在 onCleared 释放：避免内存泄漏，符合 ViewModel 生命周期规范
- TAG 扫描使用持久化队列：支持暂停/恢复/取消，避免后台被系统回收后丢失进度
- OpenCL 推理由 `OpenClGuardian` 守护：warmup + 连续失败降级 CPU，降低设备兼容性风险
- 搜索结果缩略图禁用 Coil crossfade：规避 LazyVerticalGrid 复用 item 时 Bitmap 已回收导致的崩溃
- 搜索模式下订阅 `allMedia` 变化：删除/授权完成后自动刷新搜索结果，保证数据一致性
- 图片编辑器使用独立屏幕 + `EditRecipe` 配方模型：支持撤销/重做、非破坏性保存、配方持久化与再次编辑
