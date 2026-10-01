# 扫描页 v3 实现计划（单锚点主卡 + 全页可点导航 + GPU 迁设置）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development 或 superpowers:executing-plans 逐任务执行。
> 设计 SSOT：`docs/08-UI-SPECS/screens/tag-control.yaml`（v3，2026-10-01）+ Ardot 帧 `gallery/tag_control`(478:10)/`gallery/tag_control_running`(478:89)。本计划只写实现映射，不重复设计。

**Goal:** 按 v3 设计稿重写 `TagGenerationControlScreen`（单锚点 HeroCard、四行+统计区可点跳转、重新生成收二级、GPU 迁设置·开发者选项、平铺绿换肤）。

**Tech Stack:** Kotlin + Compose Material3（:androidApp）；Room MediaDao 两条新查询；五语 strings。

**已核实事实（实现时直接用）：**
- `MediaAsset.hasFace: Boolean` 已存在（gallery 列表内即可过滤）。
- Room 表 `media_assets` 有 `labels`/`aestheticScore` 列；`MediaDao`（`data/local/MediaDao.kt`）已有 `getUnlabeledMediaIds()`（谓词 `labels IS NULL OR labels=''`）与 `getAestheticScoredCount()`（`aestheticScore IS NOT NULL AND type='PHOTO'`）。
- `MediaViewModel.applyPersonFilter(personId)`（GalleryScreen.kt:197）= mediaIds→过滤基表的人物过滤范式，直接泛化复用。
- 外部跳转通道：`MainPagerHost.gallerySearchRequest: (query, personId)?` + `onRequestGallerySearch`（MainActivity:336-340 持有 state）。照此加平行的 view-filter 通道。
- People 页 = `MAIN_PAGE_PEOPLE`（MainPagerHost:41）；`OrganizeHomeRoute.onSwitchMainPage` 已有。
- TagViewer 路由已接（`onNavigateToTagViewer`）。
- 设置页 DEVELOPER 类目已存在（SettingsScreen.kt:1023 起，含 SettingsListSection/DebugOptionRow 组件）；`SettingsViewModel.tagGenerationUseOpencl` + `setTagGenerationUseOpencl` 已暴露（SettingsViewModel.kt:239）。
- 运行态卡逻辑：domain `scanCardUiModel(sessionProgress)` 纯函数驱动（v2 已有，v3 只换皮）。

---

### Task 1: 五语 strings 增删

**Files:** `androidApp/src/main/res/values/strings.xml` + values-zh-rCN / -zh-rTW / -es / -fr

- [ ] 新增键（五语）：
  - `tag_stats_coverage_label`: EN `AI-tagged coverage` / zh `AI 打标覆盖` / tw `AI 打標覆蓋` / es `Cobertura de etiquetas IA` / fr `Couverture des tags IA`
  - `tag_stats_remaining_line`（参数 %1$d）: EN `photos · %1$,d remaining` / zh `张照片 · 剩余 %1$,d 张待处理` / tw `張照片 · 剩餘 %1$,d 張待處理` / es `fotos · quedan %1$,d` / fr `photos · %1$,d restantes`
  - `tag_stages_hint_view`: EN `Tap a stage to view` / zh `点按阶段查看内容` / tw `點按階段查看內容` / es `Toca una etapa para ver` / fr `Touchez une étape pour voir`
  - `tag_regen_entry`: EN `Regenerate by stage or filter` / zh `按阶段或条件重新生成` / tw `按階段或條件重新生成` / es `Regenerar por etapa o filtro` / fr `Régénérer par étape ou filtre`
  - `gallery_view_tagged`: EN `AI-tagged` / zh `AI 已打标` / tw `AI 已打標` / es `Etiquetadas por IA` / fr `Étiquetées par IA`
  - `gallery_view_faces`: EN `With faces` / zh `含人脸` / tw `含人臉` / es `Con caras` / fr `Avec visages`
  - `gallery_view_best`: EN `Best photos` / zh `最佳照片` / tw `最佳照片` / es `Mejores fotos` / fr `Meilleures photos`
- [ ] Task 3-5 完成后 grep 确认以下键零引用再删（五语同步删）：`stats_hero_caption`、`tag_scan_status`、`tag_scan_up_to_date`、`tag_scan_chip_pending`、`tag_scan_idle_caption`、`tag_stages_hint`、`tag_stats_with_face`、`tag_stats_embeddings`、`tag_stats_people`、`tag_stats_named`（`tag_gen_use_opencl_*` 不删——迁设置继续用）
- [ ] 提交

### Task 2: DAO 查询 + MediaViewModel 视图过滤通道

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/MediaDao.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`（MediaViewModel 在此文件内）

- [ ] MediaDao 新增（对齐既有注释风格，IO 安全仅取 id）：
```kotlin
/** 已生成 AI 标签的媒体 ID（v3 扫描页「已打标照片」视图） */
@Query("SELECT id FROM media_assets WHERE labels IS NOT NULL AND labels != '' ORDER BY captureDate DESC")
suspend fun getLabeledMediaIds(): List<Long>

/** 美学分降序的照片 ID（v3 扫描页「最佳照片」视图；仅照片，与打分口径一致） */
@Query("SELECT id FROM media_assets WHERE aestheticScore IS NOT NULL AND type = 'PHOTO' ORDER BY aestheticScore DESC")
suspend fun getBestQualityMediaIds(): List<Long>
```
- [ ] MediaViewModel：
  - `enum class GalleryViewFilter { TAGGED, FACES, BEST }`
  - `suspend fun applyGalleryViewFilter(filter, label: String)`：TAGGED/BEST → DAO ids→过滤 `allFlatMedia`（照 applyPersonFilter 范式，保序）；FACES → `allFlatMedia.filter { it.hasFace }.sortedByDescending { it.captureDate }`；统一置 searchQuery=label + 过滤态 UI（与人物过滤同一套显示路径）
  - Room schema 未变（只加 @Query），无需迁移
- [ ] 提交

### Task 3: TagGenerationControlScreen v3 重写

**Files:** `androidApp/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt`

- [ ] 签名：删 `useOpencl/onUseOpenclChange`；增 `onOpenTagged/onOpenFaces/onOpenBest/onOpenPeople: () -> Unit`（默认 `{}`）
- [ ] HeroCard（新私有组件，替换 StatsCard+ScanActionCard+ScanProgressCard 三卡）：
  - 空闲：coverageLabel(12sp onSurfaceVariant) + bigNum(34sp SemiBold **primary 纯色**, `%,d`) + remainingLine(12sp) + StatsProgressRing(沿用) + 轨道(6dp r3, fill=primary) + 双钮(48dp r12: 主=primary/onPrimary 15sp「扫描新增」icon PlayArrow；次=surfaceContainerHigh/onSurface 15sp「全量重扫」icon Refresh)
  - 运行：scanCardUiModel 同 v2 语义（标题优先级/叙述行/库级轨道/按钮集/容器色不变），按钮换 r12 恒高 48（暂停=surfaceContainerHigh 描边无、取消=ghost TextButton error 色？——设计=幽灵钮 onSurfaceVariant，但取消语义保留 error 色文字可读性优先，取 error 色 ghost）
  - 统计区（标题+数字+剩余+圆环）`clickable(onOpenTagged)` + 尾随 chevron 20dp
  - `tagBrandGradient`/GradientPillButton/OutlinedPillButton 从本文件删除
- [ ] 互斥槽保留：AestheticProgressCard、InterruptedScanCard 原样（独立卡，不在 HeroCard 内）
- [ ] 阶段行：onClick → FACE=onOpenFaces / PEOPLE=onOpenPeople / CONTENT=onNavigateToTagViewer / QUALITY=onOpenBest；删除行上 stageSheet 触发与 `controlsLocked` 行锁定（alpha 恢复 1）
- [ ] RegenEntry 单行卡（r16，14sp + chevron）→ 打开 RegenSheet（ModalBottomSheet）：① 四阶段行（点行 → 既有 StageActionSheet 流程，stageSheetTarget 保留）② 精细控制区（类别 chips/时间 chips/覆盖开关/提交钮=primary r12 h48）原样移入；扫描会话活跃时 RegenEntry 整行隐藏（visible = cardModel == null）
- [ ] 删除：StatsCard/StatsMetricTile/TagAccelCard/TagAccelSegmented/TagAccelSegmentedOption
- [ ] 提交

### Task 4: 宿主导线（OrganizeHomeRoute → MainPagerHost → MainActivity）

**Files:** 上述三文件

- [ ] OrganizeHomeRoute：删 useOpencl 透传；`onOpenPeople = { onSwitchMainPage(MAIN_PAGE_PEOPLE) }`；其余四个回调上抛
- [ ] MainPagerHost：新增 `onRequestGalleryView: (GalleryViewFilter) -> Unit` 与 `galleryViewRequest: GalleryViewFilter?` + consumed 回调（照 gallerySearchRequest 通道样式）；透传给 OrganizeHomeRoute→TagGenerationControlScreen 的 onOpenTagged/onOpenFaces/onOpenBest
- [ ] MainActivity：持有 `galleryViewRequest` state；`onRequestGalleryView = { galleryViewRequest = it; switchMainPage(MAIN_PAGE_GALLERY) }`
- [ ] GalleryScreen：消费 galleryViewRequest → `viewModel.applyGalleryViewFilter(filter, label)`（label=三键 stringResource）
- [ ] 提交

### Task 5: 设置·开发者选项加 GPU 行

**Files:** `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`

- [ ] DEVELOPER 类目合适分组（推理/模型组，落在 face engine / tag model 行附近）加 `DebugOptionRow`：title=`tag_gen_use_opencl_title`、checked=`tagGenerationUseOpencl`、onCheckedChange=`setTagGenerationUseOpencl`；若 DebugOptionRow 无副标题则不加副标题（键 `tag_gen_use_opencl_subtitle` 若无处引用则一并删除——先 grep）
- [ ] 提交

### Task 6: 构建验证 + 真机走查

- [ ] `./gradlew :androidApp:assembleDebug` 绿
- [ ] `./gradlew :androidApp:testDebugUnitTest` 绿（存量基线外无新增失败）
- [ ] detekt 增量与基线对比（不动存量）
- [ ] `adb install -r` + ui_driver（ANDROID_SERIAL=51912a5c）走查：空闲 hero（数字绿色/双钮 r12）→ 点统计区落「AI 已打标」过滤相册 → 四行分别落 含人脸/人物页/TagViewer/最佳照片 → 重新生成入口弹层（阶段 sheet + 精细控制）→ 设置·开发者选项见 GPU 行 → 开扫验证运行态卡
- [ ] 死键清理核验 + 提交收尾
