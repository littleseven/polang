# 相册模块技术实现规范 (Gallery)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 相册自然语言搜索的完整链路以 `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` 为唯一事实来源（SSOT）。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

> **版本**: 1.7  
> **状态**: 生效中  
> **最后更新**: 2026-09-12  
> **维护者**: 项目开发者

**模块定位**: 应用默认首页，提供智能聚类相册浏览、媒体查看器、批量操作功能；支持端侧自然语言搜索；语音 Agent 面板提供自然语言交互入口。二级能力入口：悬浮底部 Tab（共享 `MainFloatingBottomBar`，本页相册项高亮）+ 顶栏（模型中心/设置）+ 相册页左滑（整理）。主页面五项导航与 Pager 页序的归属口径以 `androidApp/AGENTS.md` §1.2 为准。

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
- **性能优化**: 分组计算在 `Dispatchers.Default` 线程执行，避免阻塞 UI；`combine` 结果经 `stateIn(WhileSubscribed(5000))` 转 `StateFlow`，避免重复订阅

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
- **上滑删除（回收站）**: `onSwipeUpDelete: ((MediaAsset) -> Unit)?` 可选参数，为 null 不挂手势；仅「未放大 + 当前页 PHOTO + 竖直位移主导（abs(dy)>abs(dx)）+ OCR/Vision 浮层不可见」时 consume，松手超页高 25% 阈值向上飞出淡出（220ms）后回调，未超弹回；判定纯逻辑收口 `components/SwipeUpDeleteGesture.kt`（JVM 可测）；拖动中浮现提示胶囊（`preview_swipe_delete_hint` 五语，API<30 降级时经 `isTrashSupported` 切中性键 `preview_delete_hint`），与 SwipeReview ↑删除 同一手势语言
- **删除通路（`MediaViewModel.requestTrash(asset, tag)` 返回实际 Route）**: API 30+ → `TrashSessionController`（`MediaStore.createTrashRequest`，30 天可恢复；in-flight 标志防单槽竞态，`cancelPendingRequest(tag)` 供宿主解绑防悬挂）；API < 30 → `PreviewTrashRouting` 降级永久删除 + 系统授权框。域层与跨宿主口径见 `androidApp/AGENTS.md` §2.2 `domain/trash/`
- **静默快路径（MANAGE_MEDIA）**: 设置页「删除不再询问」开 + 持权（API 31+）时 `TrashSessionController.request()` 在 token 构建前经 `TrashBackend.trySilentTrash` 直写 IS_TRASHED 单列静默回收（DATE_EXPIRES 属系统管理列，同写在 HyperOS 致 update 整体失败）；开关关/无权限返回 null 回落系统授权框；直写被 ROM 拒绝时失败子集回落 token 通路（持权时免弹框自动通过），记 `PoLang:DedupTrash` 警告
- **授权 UI 与列表收缩约定**: 共享组件 `components/TrashAuthEffects.kt`（pendingRequest → StartIntentSenderForResult，partialNotice/errorEvent → snackbar/Toast，tag 分桶 + onDispose 清理匹配 pending）；三宿主 Gallery/Chat/MemoryDetail 各挂一份（tag 分别为 `preview_swipe_gallery/chat/memory`），只在 Trashed outcome 后收缩预览列表
- **回收后网格刷新**: VM 侧 Trashed 后先 `repository.removeTrashedFromLocalCache` 乐观移除（缓存剔除 + Room 行删除 + refreshVersion 重发射）再 `refreshMediaLibrary()` 兜底；refresh 链路双端点过滤 trashed（查询侧 `IS_TRASHED=0` + stale 探活读 IS_TRASHED 列），否则 HyperOS/Android 16 已回收照片残留网格
- **预加载策略**: `beyondBoundsPageCount = 3` 预加载前后 3 页
- **沉浸式模式**: 隐藏状态栏与导航栏，滑动边缘时 transient 显示

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
- **搜索模式一致性**: 搜索模式下删除/授权完成后自动重新执行当前 query（实现见 §2.5 结果刷新）

### 2.4 重复照片清理（去重 2.0，Dedup 2.0）

> 旧实现（`DuplicateManager` 页 / `FindDuplicateMediaUseCase` / `DuplicateImageDetector`）已删除；`core/common/PerceptualHash.kt`（MD5/pHash 纯算法，零 Android 依赖、可 JVM 单测）保留，由新扫描器复用。

**技术规范**:
- **入口（整理+扫描合并页）**: 主页面 Pager 页 1 `OrganizeHomeRoute`（`features/gallery/organize/OrganizeHomeRoute.kt`）以顶部胶囊分段开关承载「整理」（本页 embedded）/「扫描」（TagGenerationControlScreen embedded）双 Tab；`DedupViewModel` 为 Activity 级独立 VM，Pager 托管安全；相册页左滑经外层 HorizontalPager 进入，系统返回键切回相册页不弹栈；页序与外部入口（底 bar 整理项、设置 `organizeTabRequest` 预选）归属口径见 `androidApp/AGENTS.md` §1.2
- **三级尺度（`DedupLevel`，`domain/dedup/DedupModels.kt`）**:
  - `EXACT` 精确重复：`(sizeBytes, mime)` 分桶 → 流式 MD5 相同成组
  - `VISUAL` 视觉重复：32×32 降采样 64-bit pHash，汉明距离 ≤ `visualThreshold`(=5) 并查集聚类；与 EXACT 组完全重合（全员同 MD5）的簇跳过
  - `SCENE` 相似场景（连拍）：`sceneThreshold`(=8) 聚类 + `sceneTimeWindowMs`(=10s) 拍摄时间窗切桶，仅保留 ≥2 张的桶
  - `DedupScanConfig.levels` 默认 `{EXACT, VISUAL}`，SCENE 由用户在 Config 页勾选
- **扫描器（`domain/dedup/DedupScanner.kt`）**: cold Flow 流式扫描，逐批流出 `DedupScanEvent`（Progress/PhaseChanged/GroupFound/Done/Cancelled）；哈希分批 500（SQLite IN 参数上限），MD5/pHash/pixelArea 缓存进 Room `dedup_hash` 表（`modifiedAt + sizeBytes` 一致则复用）；暂停/恢复经 `DedupScanController.pauseRequested` 轮询（200ms；批内每 50 张检查点，每张 `ensureActive`），取消经 `onCompletion` 补发 `Cancelled`。媒体字节 100% 端侧处理（[PRIVACY]）
- **保留规则（`KeepPolicyEngine` 四规则）**: `BEST_QUALITY`（像素面积→文件大小→美学分→拍摄日期 降序）/ `ORIGINAL` / `EDITED` / `LATEST`；`classify` 先行标注 `VersionRole`（像素或大小 < 组内最大值 0.5 → `COMPRESSED`；`modifiedAt - captureDate > 6h` → `EDITED`；否则 `ORIGINAL`），keepUri = 排序后首张，UI 可改选（`userOverride`）
- **内容类型差异化（spec §10，`DedupContentType`）**: 取数阶段零额外推理识别 SCREENSHOT（`RELATIVE_PATH` 含 Screenshots，API 29+，低版本 `DATA` 列兜底）/ DOCUMENT（ocrText 文字密度 > 20 字符/MP 或 document/receipt/text 类标签命中，英文整词匹配，`detectContentType` 纯函数）/ PORTRAIT（hasFace 或 faceQualityScore 非空）/ GENERAL，优先级 SCREENSHOT > DOCUMENT > PORTRAIT > GENERAL
- **分桶与预选口径**: VISUAL 聚类按 contentType 分桶（跨桶不成组），截图桶收紧阈值 `screenshotVisualThreshold`(=3)，人像组保留排序前置 faceQualityScore；`DedupGroup.autoPreselected`（EXACT→true、VISUAL 截图/文档→false、SCENE→false）为 false 时不进批量 CTA（口径收口 `DedupGroup.batchEligible`），详情改选（userOverride）后正常参与删除；未预选未改选组 meta 行显示预计可释放量（`potentialReclaimBytes`，「预计」措辞），详情确认按钮为「确认本组选择」中性文案；组卡片有中性描边内容类型 badge（GENERAL 不显示）
- **状态机（`DedupViewModel`，Agent First sealed 枚举）**: `Config` → `Scanning`（渐进 `GroupFound`，含 paused）→ `Results`（按 DedupLevel 分 tab + policy 切换）→（系统授权）→ `Cleaned`（可 undo/done 回 Config）；`_policy` 为 VM 级 StateFlow，Config 与 Results 共用，扫描 `Done` 按当前 policy `resortGroup` 重算默认勾选；**SCENE 组不参与批量删除**（spec §4 安全约束），`batchDeleteUris`/`batchReclaimBytes` 与底部 CTA 统一 `filter batchEligible`
- **回收站（`DedupTrashManager`）**: API 30+ 走 `MediaStore.createTrashRequest`（Cleaned 态可 undo 恢复）；`buildTrashIntent` 经 ioDispatcher + runCatching 保护，失败记日志保持 Results 态；部分拒绝置位 `partialTrashNotice` 一次性事件弹 snackbar；API < 30 由 UI 注入 `legacyDeleter` 回调走 `MediaViewModel.deleteMediaByIds` 旧授权流。**已知限制：API ≤ 29 删除授权链路为降级路径，API 29 上授权弹窗可能无人拉起，待修**
- **残留复查陷阱**: `DedupTrashManager.queryExisting` 判残留必须投影并读取 `IS_TRASHED` 列——部分 ROM（实测 HyperOS/Android 16）对已 trash 行的 item-URI 直查仍返回该行（AOSP 默认查询才过滤），仅判「行可查」会把成功回收误判为部分拒绝（「点了没反应」表象）；行存在且 `IS_TRASHED=0` 才算真残留
- **删除两通路**: ① 结果页底部 CTA `deleteSelected()` 按当前 Tab 批量删除，确认后进 `Cleaned` 完成页（可 undo）；② 组详情「保留所选 · 删除其余 N 张」`deleteGroup(groupId)` 逐组立即删除（L3 场景相似唯一通路），确认后留在 Results 原地刷新；两路共用 `computeRemainingGroups`（删净组移出 / 跨级重叠组剔除幽灵成员 / keepUri 被删按 policy 重算 / 存活 <2 不成组）与 `partialTrashNotice` 语义
- **UI 组件清单（`features/gallery/dedup/`）**: `DedupHomeScreen`（页面 + Route）、`DedupSheets`（`DedupGroupDetailPage` 全屏组详情页——点「保留这张」改选后立即收起回结果列表 + 组内全屏对比预览 + `KeepRulesSheet` 保留规则弹层）、`DedupComponents`（组卡片/缩略图等）、`DedupMediaSource`（扫描输入供数，`MediaType.PHOTO` 元数据 + modifiedAt）
- **整理中心类目详情（F1，`features/gallery/organize/`，类目体系 v2）**: 六类目互斥管线，`domain/organize/` 纯函数四层（`OrganizeCategorizer` 为编排 Facade，对外仅 `classifyAll`（详情页逐媒体裁定）与 `board`（hub 聚合）两入口）：`CategoryArbiter` 按 `OrganizeCategory` 声明序先命中先得（DUPLICATES > SCREEN_CONTENT > DOCUMENTS > LOW_QUALITY_PORTRAITS > LOW_QUALITY_PHOTOS > LARGE_FILES；视频仅参与 DUPLICATES/SCREEN_CONTENT/LARGE_FILES）→ `ValueGuard` 价值保护（仅低质两类：拍摄 ≥5 年前 / 人物聚类 ≤3 张稀缺 / 收藏或查看过 → protected，不预选不计 Hero）→ `ConfidenceGrader`（HIGH 默认勾选 / MEDIUM 近阈值单信号 / LOW 弱信号）；阈值集中 `OrganizeThresholds`（SSOT）
- **F1 信号与 hub**: 新信号 blurScore/exposureScore（`BlurAnalyzer` ≤256px 灰度 Laplacian 方差 + 平均亮度，缺分由 `OrganizeRepositoryImpl.backfillQualitySignals` 后台分批补算回写）与 lastViewedAt（查看器打开回写，60s 节流）；hub（`DedupHomeHub.kt`）board 恒 6 卡，零命中且 READY 不渲染、NEEDS_SCAN 渲染降档引导卡；Hero = 全类目 HIGH 非保护并集字节（DUPLICATES 按精确组扣 1 张 keeper；已知口径差：highCount 徽标含 keeper 而 highBytes 不含）；`DedupCategoryCard`（可展开原去重 Config 三件套）钉首位豁免排序
- **F1 详情页**: `OrganizeCategoryScreen`/`OrganizeCategoryViewModel` 三段分组（建议删除=HIGH 非保护 / 请确认=MEDIUM+LOW / 可能是珍贵照片=protected，Shield 角标）；AI 预选与 selectAll 仅圈 HIGH 非保护项，DUPLICATES 按 `exactDupGroupKey` 每组留 1 张 keeper（captureDate 最新）；顶栏「AI 预选」开关（`org_ai_preselect_on/off`）；段级全选按钮未实装（VM `selectAll`/`deselectAll` 就绪暂无 UI 调用方）；删除/Undo 走 `TrashSessionController` 系统回收站（30 天可恢复）
- **F1 口径与索引**: 重复组聚类输入按库内 uri 收敛（`OrganizeRepositoryImpl.queryDuplicateInfo`，dedup_hash 幽灵行不参与）；`SwipeQueueBuilder` 消费同一 `classifyAll` 产出（口径同源，废片桶只收 HIGH/MEDIUM 非 protected）。设计 spec：`docs/superpowers/specs/2026-09-06-organize-category-redesign-design.md`；UI SSOT：`docs/08-UI-SPECS/screens/organize.yaml`
- **手势快速整理（F2，`features/gallery/swipe/`）**: `SwipeReviewScreen`（NavHost 二级页 `swipe_review`：全屏大图卡——右滑保留 / 左滑跳过 / 上滑删除、点按=跳过，卡片跟手位移 + 超阈值（宽 25%）飞出落决策，预加载后续 3 张；完成态统计卡 + 再来一轮 + 整批恢复）+ `SwipeReviewViewModel`（`SwipeQueueBuilder` 废片优先建队、undo 栈、未提交 DELETE 累计 20 张自动提交一批系统回收站授权，Done 态整批 undoAll）；hub「滑动整理」渐变主按钮经 `onQuickTidy` 点亮路由；入列原因（Screenshot/Blurry/Low-quality portrait/Recent）为卡片角标，视频永不入队
- **F2 KEEP 30 天抑制**: decide(KEEP) 即时写入 `SwipeKeepHistory`（`domain/swipe/SwipeKeepHistory.kt` 纯函数：`uri|epochMs` 编码 + 30 天 TTL 裁剪；`data/preferences/DataStoreSwipeKeepHistoryStore.kt` 持久化到 user_preferences DataStore `swipe_keep_history` stringSet），restart 建队过滤活跃条目并清理过期，undo(KEEP) 只回滚本会话新增条目
- **F2 防死锁与计数**: API<30（`!isSupported`）提交短路不挂在途、`finish` 直接 settleDone（未提交 DELETE 以 skipped 口径进 Done，三桶守恒）；token 构建失败经 errorEvent 回滚；授权被拒（Cancelled）批次回滚可重试，末张决策后显示「待提交 N 张 + 重试/放弃」出口面板；freedBytes 为 Reviewing 存储字段，decide/undo/discard 增量维护（O(1)）
- **回忆 Memories（F3 独立页，`features/gallery/memories/`）**: Memory 独立主页面 Pager 页 4 + 详情页路由 `memory_detail/{memoryId}`；生成规则、隐藏持久化与 v1 不落表说明见 §2.12
- **Pager 页单根铁律**: `DedupHomeRoute` 必须以单个 `Box(fillMaxSize)` 包裹 Scaffold + 详情页 + 预览层——HorizontalPager 会把 page 内容的多个根节点沿主轴顺序平铺（非 Box 式堆叠），多根导致详情页/预览层排到屏外「点击没反应」；页内新增全屏覆盖层一律挂进该 Box，不得作为独立根节点

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

### 2.6 首页导航与底部悬浮 Tab

**首页定位**:
- `GalleryScreen` 由 `Screen.Main`（`MainPagerHost` Pager 容器，NavHost `startDestination`）承载为主页面页 0
- 系统返回键在相册无内部状态（无选择/Pager）时退出应用；主页面其他页按返回键切回相册页（`MainPagerHost` BackHandler）

**底部悬浮 Tab**:
- 使用共享组件 `MainFloatingBottomBar`（`features/main/MainFloatingBottomBar.kt`，底层 `FloatingBottomTab`），五项与 Pager 页序 1:1，本页相册项（PhotoLibrary）高亮；五项组成、图标与切页行为的归属口径以 `androidApp/AGENTS.md` §1.2 为准
- 位置：底部居中，底部 padding 16.dp，悬浮于媒体网格之上；相册详情态（`selectedMediaIndex != null`）隐藏；每项仅图标无文字；点击经 `switchMainPage` 瞬时切页（无滑动动画）

**设置与模型中心入口**:
- 设置：统一放在 `GalleryTopBar` 动作区最右侧，点击跳转 `SettingsScreen`
- 模型中心：`GalleryTopBar` 动作区 CloudDownload 图标直达；设置主菜单「AI 与系统」组「模型中心」列表行也可进入

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

保存成功后经 `navController.popBackStack()` 返回并 `viewModel.refreshMediaLibrary()` 刷新媒体库。

### 2.8 OCR 文字识别集成

**技术规范**:
- **触发入口**: 
  - 工具栏"提取文字"按钮
  - ~~长按图片文字区域（已改为进入图片编辑）~~
- **状态管理**: 使用 `MutableStateFlow<OcrResult?>` 管理识别状态（Loading/Success/Error）
- **资源释放**: ViewModel `onCleared()` 时调用 `ocrUseCase.close()` 释放 ML Kit 资源
- **结果展示**: 原位浮层卡片展示识别结果，支持复制与分享
- **异常处理**: 识别失败时显示友好错误提示，记录日志

### 2.9 TAG 生成精细控制（2026-06 新增）

**入口**: `TagGenerationControlScreen`（已并入整理+扫描合并页 SCAN Tab：主页面 Pager 页 1 `OrganizeHomeRoute`，原 `tag_control` NavHost 路由已删除；入口 = 设置主菜单「AI 与系统」组「TAG 生成控制」列表行（经 `organizeTabRequest` 预选 SCAN Tab）+ 整理页胶囊开关直接切换；embedded 模式下顶栏不内置状态栏避让且无返回箭头（根页规则），由合并页胶囊条统一避让）

**技术规范**:
- **3-Pass 混合管道**（另有 legacy `MOBILE_CLIP_ENCODING` Pass，仅保留用于历史任务兼容及单独重编码场景，不在常规扫描链中）:
  - **Pass 1**: `FACE_DETECTION` — 人脸检测 + 人脸 Embedding + MobileCLIP 语义编码（语义编码已内联合并到本阶段）
  - **Pass 2**: `DBSCAN` — 全局人脸聚类
  - **Pass 3**: `IMAGE_TAGGING` — 端侧多模态标签生成（场景/活动/物体/标签/摘要）；默认打标器 Florence-2（`TaggerModelSelector.defaultKey = "florence2_base"`），Qwen3-VL-2B 为可选打标器
- **类别到 Pass 映射**（`TagCategory.toPasses`）:
  - `FACE` → `FACE_DETECTION` + `DBSCAN`
  - `SCENE / ACTIVITY / OBJECTS / TAGS / SUMMARY` → `IMAGE_TAGGING`
- **队列编排**: `TagScanOrchestrator` 持久化任务队列，支持暂停/恢复/取消/失败重试
- **增量去重**: 默认跳过近期已覆盖所有请求 Pass 的媒体，按 `oldest-first` 排序避免老照片饿死
- **精细控制**: 支持按 `TagCategory`（人脸/场景/活动/物体/标签/摘要）和时间范围（全部/7天/30天/90天）重新生成
- **OpenCL 守护**: `OpenClGuardian` 在 Pass 3 前 warmup，超时后自动降级 CPU 并记录设备黑名单
- **模型加载**: `TagGenerationScheduler.ensureModelLoaded()` 优先 OpenCL（用户开启且未降级），失败/warmup 超时后降级 CPU
- **状态观察**: 通过 `TagGenerationService.sessionProgress` StateFlow 显示进度、预计剩余时间、暂停/恢复按钮

**UI 结构（v2 重设计，英文体验优先）**:
- 页面自上而下四个区块：`Library`（Stats 置顶：图库统计 + 语义索引覆盖）→ `Scan`（空闲时 `ScanActionCard`：状态 chip「Up to date / N pending」+ 「Scan new / Rescan all」；扫描中原进度卡 + 会话控制）→ `Stages` → `Regenerate`
- **Stages 区块**：4 个 `StageRow`（Faces/People/Content tags/Quality scores，右侧百分比或人数 + chevron），点按弹 `StageActionSheet`（ModalBottomSheet）提供「Process new only（Recommended 徽章）/ Reprocess everything」两档
- **全量二次确认**：选 Reprocess everything 先弹 AlertDialog 确认再下发 intent（`intentScanPass1/2/3Full`、`intentScoreAestheticFull`）
- **Regenerate 区块**：类别/时间范围 chips + 「Overwrite existing」开关（关 = 仅补齐缺失）
- **视觉规格对齐 Ardot 稿 `gallery/tag_control` / `gallery/tag_stage_sheet`**（两帧已迁至 `Organize` 页，帧名与 node id 171:273/172:113 未变）：卡片=surfaceContainer r16、瓦片/轨道/Cancel=surfaceVariant、弹层/确认框=surfaceContainerHighest、描边=outlineVariant、强调=primary（#8FD6C6）、渐变按钮/大数字=ChatBubbleTokens 品牌渐变；Stats 瓦片无图标（数值 17sp + 标签 11sp）、覆盖环为实色 primary 弧；弹层选项卡带单选圈（点卡片直接执行）+ 通栏 Cancel；自定义 44×26 TagSwitch 与 h28 chip（选中=primary 14% 底+描边）
- 文案规范：按钮/标题一律短文案（Faces、Scan new、Rescan all、Regenerate），五语（EN/zh-CN/zh-TW/ES/FR）键同步，新增键以 `tag_section_*` / `tag_stage_*` / `tag_scan_*` / `tag_overwrite_*` 前缀

按类别重新生成经 `TagGenerationService.intentRegenerateCategories(categories, startTimeMs, fullMode)` 以前台 Service 下发。

### 2.10 缩略图缓存策略 (LruCache)

**技术规范**:
- **内存缓存**: 使用 Coil 的 `MemoryCache`，占可用内存 25%
- **磁盘缓存**: 占磁盘空间 2%，目录为 `cache/image_cache`
- **加载优先级**: 当前可见项优先加载，预加载相邻项
- **OOM 保护**: 系统内存紧张时自动降低非可见图片质量
- **位置记录**: 使用 `mutableStateMapOf<Long, Rect>` 记录缩略图位置，支持展开动画
- **回收位图保护**: 搜索结果缩略图禁用 Coil crossfade（口径见 §2.5 缩略图安全）

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

### 2.12 回忆 Memories（F3 独立页）

**模块定位**: Memory 独立主页面 Pager 页（index 4，`MAIN_PAGE_MEMORY`）+ 回忆详情页（NavHost 路由 `memory_detail/{memoryId}`）；纯端侧规则生成，零推理零上传（[PRIVACY]），对标小米系统相册分区 feed；Pager 页序与路由归属口径见 `androidApp/AGENTS.md` §1.2。设计 spec：`docs/superpowers/specs/2026-09-06-memory-page-design.md`。

**页面结构（`features/gallery/memories/MemoryScreen.kt`）**:
- 顶栏：`statusBarsPadding()` 状态栏避让（edge-to-edge 防标题被状态栏压住）+ 大标题「回忆」（`memory_title`）+ 副标「端侧生成 · 私密」（`memory_privacy_note`）
- 三分区 feed（`buildSections`）：时光（ON_THIS_DAY + RECENT_HIGHLIGHTS，`memory_section_time`）→ 旅程（CITY，`memory_section_journey`）→ 人物（PERSON，`memory_section_people`）；空分区剔除不占位，三分区全空显示整页空态 `memory_empty_page`；LazyColumn 底部 contentPadding 96.dp 预留底 bar 悬浮遮挡
- 竖版大卡（`MemoryBigCard`）：168×224 dp 圆角 16（`MemoryPageTokens`：cardWidth/cardHeight/cardCornerRadius/cardSpacing/sectionHorizontalPadding/sectionTitleSpacing，经 `design-tokens.json` SSOT codegen 双端同步）；封面 Coil size(512) crossfade(false)（recycled bitmap 红线）；底部渐变蒙层 + 左下 17sp Bold 白字标题 + 12sp 80% 副行；`contentDescription = "title · subtitle"`；点击进详情页、长按弹隐藏确认
- 底 bar：共享 `MainFloatingBottomBar`（五项与 Pager 页序 1:1，口径见 `androidApp/AGENTS.md` §1.2）；本页回忆项（AutoAwesome 图标）selected 高亮（图标着色 primary，点击空操作）；其他页经 `onSwitchMainPage` → `switchMainPage(index)` 瞬时切页（目标为整理页时预选 ORGANIZE tab）

**生成规则（`domain/memories/MemoriesGenerator.kt` 纯函数，now/zoneId 注入确定性，JVM 可测）**:
- 四类回忆：ON_THIS_DAY（与 now 同月同日的往年 ≥4 张，year < now 未来年份排除）、RECENT_HIGHLIGHTS（近 30 天已评分 ≥6 张）、PERSON（已命名非本人人物 ≥6 张，前 3）、CITY（同城 ≥6 张，前 2，仅 CITY 填 `earliestCaptureDate`/`latestCaptureDate` 供旅程日期范围）；总量截 `MAX_CAROUSEL`(10)
- 精选排序：美学分降序（null 排最后）→ 拍摄时间新的在前，截 `DETAIL_LIMIT`(12) 张，封面取首张；`Memory.allItemUris` = 全部命中按拍摄时间降序不截断（详情页「全部」开关与分享全集用）
- 仅 `MediaType.PHOTO` 参与；`MemoryInput.personId` = 媒体 `faceId`（`media_assets.faceId` 为 personId 的 TEXT 形态）
- **结构化文案（I18N 红线）**：`Memory` 只携带结构化字段（type + label（人物/城市名）+ hitCount + latestYear + monthDay（`java.time.MonthDay`）+ 旅程日期范围字段）；UI 层 `features/gallery/memories/MemoryTexts.kt`（`memoryTitle`/`memorySubtitle`）经 stringResource 还原（`memory_on_this_day`/`memory_recent_highlights`/`memory_person_title`/`memory_photo_count` 等键，五语同步），月日按 skeleton "MMMd" 取当前 Locale 最佳 pattern 本地化（不硬编码英文 pattern）
- **旅程卡副行 `cityDateRange`**（`MemoryTexts.kt`，对齐小米）：同年月 → 本地化 yMMMM（skeleton 经 `getBestDateTimePattern` 取当前 Locale 最佳 pattern，en "May 2025"、zh "2025年5月"）；跨月/跨年 → "startYear · endYear"（如「2025 · 2026」）；字段缺失（非 CITY）容错返回空串

**数据链（`MemoriesViewModel`，Activity 级 VM，Memory 页与详情页共用）**:
- `combine(MediaDao.getAllMedia, PersonDao.observeAll, MemoryHiddenStore.ids)` → generate → `flowOn(ioDispatcher).stateIn(WhileSubscribed(5000))`
- **隐藏过滤只在展示层**：`allGenerated`（未过滤全集）为 `observeMemory(id)` 数据源，详情页直达/刷新后 id 稳定可复原（含已隐藏条目）；`memories` 为剔除隐藏后的展示集
- 人物映射只取已命名人物（`PersonEntity.name` 非空），`NamedPerson(personId.toString(), name, isSelf)`

**隐藏持久化**:
- 长按大卡 → 根 Box 内 `AlertDialog` 确认（`memory_hide` / `memory_hide_confirm` / `memory_hide_cancel`）→ `hideMemory(id)`（runCatching 包 DataStore 写入，失败置位 `hideError` 一次性标志不崩溃，`consumeHideError` 消费）
- `MemoryHiddenStore`（domain 接口）/ `DataStoreMemoryHiddenStore`（data/preferences 实现）：user_preferences DataStore `memory_hidden_ids` stringSet，存 Memory.id；展示集经 combine 重发自动消失
- v1 无「重新启用」入口，隐藏确认文案不承诺恢复（`memory_hide_confirm`）

**详情页（`MemoryDetailScreen`，蒙德里安重设计）**:
- 整页一条竖向滚动长列表（LazyColumn，头图/开关均随列表滚动不钉顶不悬浮）：AppTopBar（返回 + Memories + 右上分享图标）→ 16:9 头图（宽撑满、高 = 屏宽×9/16，Coil size(1080) crossfade(false)，底部渐变蒙层 + 左下 20sp Bold 白字标题 + 13sp 副行：`memory_items_count`(hitCount) · 分类型后缀——ON_THIS_DAY/RECENT_HIGHLIGHTS 接 `memorySubtitle`、CITY 接 `cityDateRange`、PERSON 不接）→ 元信息行（左 = 精选/全部胶囊分段开关，右 = `memory_items_count`(displayUris.size) 计数）→ 蒙德里安拼贴网格（五种卡块：2×2 大卡左/右、2×1 横幅左/右、三方卡行；单元 = (屏宽-32-8)/3，间距 4，r8；大卡/横幅 Coil size(720)、方卡 size(360)，`memory_photo_cd` 无障碍序号跨卡块连续）
- **拼贴洗牌（`domain/memories/MemoryMosaic.kt` 纯函数，JVM 可测）**：seed = FNV-1a 32bit(memory.id) → xorshift32 逐块等概率抽取五种卡块，与上一块同向（左/右）重抽至多 3 次仍冲突取方卡行；大卡块耗 3 项、横幅 2 项；尾行剩余 ≤3 一律方卡行补齐；同 id 同 URI 集输出恒定，媒体增删序列自然顺延不承诺稳定
- **图片预览**：封面与网格照片点击打开全屏 `MediaPager` overlay（同 Gallery/Chat 宿主范式，非路由；页面根为单 Box 承载 Column + 预览层）；`Memory` 只存 uri，经 `MemoriesViewModel.assetsByUri`（uri → MediaAsset 全库索引）反查定位初始页，未解析项剔除；预览内删除/OCR/跳编辑器/证件照经 Activity 级 `MediaViewModel` 与导航回调（删除授权写法同 ChatScreen）；删除后预览集合随媒体库流自动收缩、删空自动收起；`BackHandler` 优先关预览再弹栈；精选/全部开关切换收起预览（索引口径已变）
- **精选/全部分段开关**：内联于网格区顶部元信息行（`memory_detail_best` / `memory_detail_all`），`showAll` 按 `remember(memory?.id)` 切回忆时重置回精选；`displayUris = showAll ? allItemUris : itemUris`；分段项带 `role = Button` + `selected` 语义
- **分享集合跟随开关**：分享图标按当前 `displayUris` 发 `ACTION_SEND_MULTIPLE` + `FLAG_GRANT_READ_URI_PERMISSION`（写法同 `GalleryUtils.shareMediaAssets`）
- MainActivity 路由内 collect `observeMemory(id)`（未过滤全集 Flow，列表刷新不 stale、冷恢复随流复原不闪空态）；id 失效（媒体清空等）→ `memory_detail_empty` 空态

**v1 不落表说明**: 回忆不建 Room 表/快照，每次由媒体 + 人物流实时生成，无 DB 迁移成本；隐藏集仅存 DataStore stringSet（id 字符串，个位数量级）。若未来需要跨设备同步或「那年今日回顾历史」，再评估落表。

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
