# Memory 独立页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Memory 从相册页拆出为独立 Pager 页（index 4）+ 底 bar 第 5 入口，页形态对标小米相册（时光/旅程/人物分区大卡 feed），详情页升级 55% 封面 + 精选/全部开关。

**Architecture:** 数据层仅 `MemoriesGenerator` 补 `allItemUris`（纯函数 TDD）；UI 新增 `MemoryScreen`（Pager 页，分区 feed + 大卡）取代相册 header 槽 carousel（`MemoriesCarousel.kt` 删除）；详情页 `MemoryDetailScreen` 改造；`FloatingBottomTabItem` 增 `selected` 高亮态。

**Tech Stack:** Kotlin / Jetpack Compose / HorizontalPager / Coil / DataStore；spec：`docs/superpowers/specs/2026-09-06-memory-page-design.md`

**工作区**：worktree `/Users/guoshuai/AndroidStudioProjects/polang/.worktrees/feat-organize-suite`（分支 `feat/organize-suite`），所有命令 cwd 均为该目录。

**编码红线**：禁 `it` 隐式参数 / 禁通配符导入 / Coil 一律 `crossfade(false)` / 日志 tag `PoLang:Memories` / 五语 strings 手工同步 / detekt 零新增（主干基线 89，分支当前 86，不超 89）。

---

## 关键现状事实（实现前必读）

- Pager 页序（`MainPagerHost.kt:32-36`）：`MAIN_PAGE_GALLERY=0 / DEDUP=1 / CHAT=2 / PEOPLE=3 / COUNT=4`，`beyondViewportPageCount = MAIN_PAGE_COUNT - 1`，返回键 `BackHandler(enabled = currentPage != GALLERY)` 切回相册页
- 底 bar（`GalleryScreen.kt:869-899`）：`FloatingBottomTab` 4 项纯图标（BurstMode 整理 / ChatBubble 聊天 / Sell 打标 / AccountCircle 人物），仅相册页 `selectedMediaIndex == null` 时显示
- `FloatingBottomTabItem`（`features/common/components/FloatingBottomTab.kt:31-36`）：data class，icon/label/contentDescription/onClick，无 selected 概念，tint 固定 `onSurface`
- `Memory`（`domain/memories/MemoriesModels.kt:28-41`）：id/type/label/hitCount/latestYear/monthDay/coverUri/itemUris（精选 12）；文案经 `features/gallery/memories/MemoryTexts.kt` 的 `memoryTitle/memorySubtitle` 还原
- `MemoriesGenerator.buildMemory`（`domain/memories/MemoriesGenerator.kt:129-153`）：精选排序 = 美学分降序 → 拍摄时间降序，截 `DETAIL_LIMIT=12`
- `MemoriesViewModel`（`features/gallery/memories/MemoriesViewModel.kt`）：Activity 级；`memories`（过滤隐藏）、`observeMemory(id)`、`hideMemory(id)`、`hideError/consumeHideError`
- 详情页 `MemoryDetailScreen(memory, onNavigateBack)`：AppTopBar(返回+分享) → 16:9 封面 → 3 列精选网格 → 底部渐变 Share 按钮；路由 `MainActivity.kt:778-797` `memoryDetailRoute` 经 `observeMemory` collect
- GalleryScreen 签名含 `memoriesViewModel: MemoriesViewModel, onNavigateToMemoryDetail: (String) -> Unit`（`GalleryScreen.kt:122` 附近），header 槽在 `:846-858`
- MainActivity：pagerState 在 `:205-208`，`switchMainPage` 在 `:213` 附近，MainPagerHost 接线在 `:294-330`（含 `onNavigateToMemoryDetail` → `Screen.MemoryDetail.createRoute`）
- `ChatCarouselTokens`（`DesignTokens.kt:268-276`）：cardWidth 120 / cardHeight 150 / r12 / spacing 8——旧小卡规格，新大卡不复用

---

### Task 1: Memory 模型补 allItemUris（TDD）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/memories/MemoriesModels.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/memories/MemoriesGenerator.kt:129-153`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/memories/MemoriesGeneratorTest.kt`

- [ ] **Step 1: 写失败测试**（追加到 MemoriesGeneratorTest，沿用文件内现有 `photo(...)`/`at(...)`/`generate(...)` 夹具）

```kotlin
@Test
fun `memory carries all hits while best shots stay capped`() {
    // 同城 15 张照片：精选截 12，allItemUris 全量 15
    val inputs = (1..15).map { index ->
        photo("city$index", at(2025, 5, 1, index), city = "北京", score = index.toFloat())
    }
    val memory = generate(inputs).single()
    assertEquals(15, memory.hitCount)
    assertEquals(12, memory.itemUris.size)
    assertEquals(15, memory.allItemUris.size)
    // 精选是全量的子集；封面 = 精选首张（美学分最高）
    assertTrue(memory.allItemUris.containsAll(memory.itemUris))
    assertEquals(memory.itemUris.first(), memory.coverUri)
}

@Test
fun `all uris ordered by capture time descending`() {
    val inputs = listOf(
        photo("old", at(2025, 5, 1, 8), city = "北京", score = 1f),
        photo("new", at(2025, 5, 1, 20), city = "北京", score = 2f),
        photo("mid", at(2025, 5, 1, 12), city = "北京", score = 9f),
    ) + (1..5).map { index -> photo("pad$index", at(2025, 4, 1, index), city = "北京") }
    val memory = generate(inputs).single { memory -> memory.type == MemoryType.CITY }
    val all = memory.allItemUris
    assertEquals(listOf("new", "mid", "old"), all.take(3))
}
```

注：若现有夹具 `photo()` 签名不含 city/score 参数，按夹具实际签名适配（该测试文件已有 CITY 类用例可参考）；`MemoryType` 需 import（同包则免）。

- [ ] **Step 2: 跑测试确认红**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*MemoriesGeneratorTest*" --console=plain`
Expected: 编译失败（`allItemUris` 未定义）

- [ ] **Step 3: 实现**

`MemoriesModels.kt` 的 `Memory` 增加字段：

```kotlin
    /** 精选 URI（美学分降序截 [MemoriesGenerator.DETAIL_LIMIT]），封面在首。 */
    val itemUris: List<String>,
    /** 全部命中 URI（拍摄时间降序，不截断；详情页「全部」开关与分享全集用）。 */
    val allItemUris: List<String>,
)
```

`MemoriesGenerator.buildMemory` 末尾改：

```kotlin
        return Memory(
            id = id,
            type = type,
            label = label,
            hitCount = hits.size,
            latestYear = latestYear,
            monthDay = monthDay,
            coverUri = selected.first().uri,
            itemUris = selected.map { input -> input.uri },
            allItemUris = hits
                .sortedByDescending { input -> input.captureDate }
                .map { input -> input.uri },
        )
```

- [ ] **Step 4: 跑测试确认绿**（同 Step 2 命令，全用例 PASS）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/memories/ androidApp/src/test/java/com/mamba/picme/domain/memories/
git commit -m "feat(memories): Memory 补 allItemUris（全部命中，时间降序）支撑详情页精选/全部开关"
```

---

### Task 2: 五语 strings 新增

**Files:**
- Modify: `androidApp/src/main/res/values/strings.xml` 及 `values-zh-rCN` / `values-zh-rTW` / `values-es` / `values-fr` 同位置（现有 `memory_*` 键附近）

- [ ] **Step 1: values/strings.xml（EN）追加**

```xml
    <string name="memory_section_time">Time</string>
    <string name="memory_section_journey">Journeys</string>
    <string name="memory_section_people">People</string>
    <string name="memory_empty_page">Memories will appear as your photo library grows</string>
    <string name="memory_detail_best">Best</string>
    <string name="memory_detail_all">All</string>
    <string name="memory_items_count">%1$d items</string>
    <string name="tab_memories">Memories</string>
```

- [ ] **Step 2: zh-rCN**

```xml
    <string name="memory_section_time">时光</string>
    <string name="memory_section_journey">旅程</string>
    <string name="memory_section_people">人物</string>
    <string name="memory_empty_page">照片积累后会自动生成回忆</string>
    <string name="memory_detail_best">精选</string>
    <string name="memory_detail_all">全部</string>
    <string name="memory_items_count">%1$d 项</string>
    <string name="tab_memories">回忆</string>
```

- [ ] **Step 3: zh-rTW（繁体）**：時光 / 旅程 / 人物 / 照片累積後會自動生成回憶 / 精選 / 全部 / %1$d 項 / 回憶
- [ ] **Step 4: es**：Momentos / Viajes / Personas / Los recuerdos aparecerán a medida que crezca tu fototeca / Destacadas / Todas / %1$d elementos / Recuerdos
- [ ] **Step 5: fr**：Temps / Voyages / Personnes / Les souvenirs apparaîtront au fur et à mesure de votre photothèque / Sélection / Toutes / %1$d éléments / Souvenirs

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/res/
git commit -m "feat(memories): Memory 页五语 strings（分区标题/空态/精选全部开关/底 bar 标签）"
```

---

### Task 3: FloatingBottomTabItem 增 selected 高亮态

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/common/components/FloatingBottomTab.kt`

- [ ] **Step 1: 改 data class 与 Icon tint**

```kotlin
data class FloatingBottomTabItem(
    val icon: ImageVector,
    val label: String? = null,
    val contentDescription: String? = null,
    /** 当前页高亮（图标着色 primary）；默认 false 保持既有 4 项行为不变。 */
    val selected: Boolean = false,
    val onClick: () -> Unit
)
```

`Icon(...)` 的 tint 改：

```kotlin
                    tint = if (item.selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
```

同步更新两处 KDoc（`FloatingBottomTabItem` 头部注释补 selected 说明）。

- [ ] **Step 2: 编译** `./gradlew :androidApp:compileDebugKotlin --console=plain`（可选并入 Task 5 后统一编译）

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/common/components/FloatingBottomTab.kt
git commit -m "feat(designsystem): FloatingBottomTabItem 增 selected 高亮态（默认 false 零回归）"
```

---

### Task 4: MemoryScreen 新 Pager 页（分区大卡 feed）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/gallery/memories/MemoryScreen.kt`
- Delete: `androidApp/src/main/java/com/mamba/picme/features/gallery/memories/MemoriesCarousel.kt`（相册 header carousel 拆除，旧小卡组件无引用后删除；隐藏弹窗逻辑移入新文件）
- Modify: `androidApp/src/main/java/com/mamba/picme/core/designsystem/DesignTokens.kt`（追加 MemoryPageTokens）

- [ ] **Step 1: DesignTokens.kt 追加（`ChatCarouselTokens` 之后）**

```kotlin
/** Memory 独立页（2026-09-06，对标小米相册时光大卡）：竖版 3:4 大卡横滑行。 */
object MemoryPageTokens {
    val cardWidth = 168.dp
    val cardHeight = 224.dp
    val cardCornerRadius = 16.dp
    val cardSpacing = 12.dp
    val sectionHorizontalPadding = 16.dp
    val sectionTitleSpacing = 12.dp
}
```

- [ ] **Step 2: MemoryScreen.kt 完整实现**

职责：顶栏（标题「回忆」+ 副标「端侧生成 · 私密」）→ 三分区（时光 = ON_THIS_DAY + RECENT_HIGHLIGHTS；旅程 = CITY；人物 = PERSON，空分区不占位）→ 整页空态；长按出隐藏确认弹窗；底部悬浮 5 图标底 bar（回忆项 selected）。

```kotlin
package com.mamba.picme.features.gallery.memories

// import 清单：foundation.lazy.LazyRow/items、material3、coil（crossfade(false) 红线）、
// FloatingBottomTab/FloatingBottomTabItem、MemoryPageTokens、AppShapes、
// memoryTitle/memorySubtitle（本包 MemoryTexts.kt）、R、Memory/MemoryType、
// Icons.Outlined.BurstMode/ChatBubble/Sell/AccountCircle/Collections

/** 分区枚举 → 展示模型（内部）：标题资源 + 该分区回忆列表。 */
private data class MemorySection(
    val titleRes: Int,
    val memories: List<Memory>,
)

/** 按类型分区：时光（ON_THIS_DAY + RECENT_HIGHLIGHTS）→ 旅程（CITY）→ 人物（PERSON）；空分区剔除。 */
private fun buildSections(memories: List<Memory>): List<MemorySection> = listOf(
    MemorySection(
        R.string.memory_section_time,
        memories.filter { memory ->
            memory.type == MemoryType.ON_THIS_DAY || memory.type == MemoryType.RECENT_HIGHLIGHTS
        },
    ),
    MemorySection(
        R.string.memory_section_journey,
        memories.filter { memory -> memory.type == MemoryType.CITY },
    ),
    MemorySection(
        R.string.memory_section_people,
        memories.filter { memory -> memory.type == MemoryType.PERSON },
    ),
).filter { section -> section.memories.isNotEmpty() }

@Composable
fun MemoryScreen(
    memoriesViewModel: MemoriesViewModel,
    onNavigateToMemoryDetail: (String) -> Unit,
    // 底 bar 五个出口（与 GalleryScreen 同源）
    onNavigateToDedupHome: () -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToTagControl: () -> Unit,
    onNavigateToPeople: () -> Unit,
    isActivePage: Boolean,
) {
    val memories by memoriesViewModel.memories.collectAsStateWithLifecycle()
    var pendingHide by remember { mutableStateOf<Memory?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：大标题 + 副标（对齐相册页「相册」标题风格）
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.memory_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.memory_privacy_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val sections = buildSections(memories)
            if (sections.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.memory_empty_page),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp), // 底 bar 悬浮遮挡预留
                ) {
                    items(sections, key = { section -> section.titleRes }) { section ->
                        MemorySectionRow(
                            section = section,
                            onMemoryClick = { memory -> onNavigateToMemoryDetail(memory.id) },
                            onMemoryLongClick = { memory -> pendingHide = memory },
                        )
                    }
                }
            }
        }

        // 悬浮底 bar：5 图标，回忆项 selected 高亮
        FloatingBottomTab(
            items = listOf(
                FloatingBottomTabItem(
                    icon = Icons.Outlined.BurstMode,
                    contentDescription = stringResource(R.string.gallery_cleanup),
                    onClick = onNavigateToDedupHome,
                ),
                FloatingBottomTabItem(
                    icon = Icons.Outlined.ChatBubble,
                    contentDescription = stringResource(R.string.chat),
                    onClick = onNavigateToChat,
                ),
                FloatingBottomTabItem(
                    icon = Icons.Outlined.Sell,
                    contentDescription = stringResource(R.string.tag_scan_control),
                    onClick = onNavigateToTagControl,
                ),
                FloatingBottomTabItem(
                    icon = Icons.Outlined.AccountCircle,
                    contentDescription = stringResource(R.string.gallery_people_entry),
                    onClick = onNavigateToPeople,
                ),
                FloatingBottomTabItem(
                    icon = Icons.Outlined.Collections,
                    contentDescription = stringResource(R.string.tab_memories),
                    selected = true,
                    onClick = { /* 当前页，空操作 */ },
                ),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        )
    }

    // 隐藏确认弹窗（从旧 MemoriesCarousel 平移，文案键不变）
    pendingHide?.let { memory ->
        AlertDialog(
            onDismissRequest = { pendingHide = null },
            title = { Text(stringResource(R.string.memory_hide)) },
            text = { Text(stringResource(R.string.memory_hide_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingHide = null
                        memoriesViewModel.hideMemory(memory.id)
                    },
                ) {
                    Text(stringResource(R.string.memory_hide))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHide = null }) {
                    Text(stringResource(R.string.memory_hide_cancel))
                }
            },
        )
    }
}

/** 分区行：标题 + 大卡 LazyRow（横滑，内容两侧 16dp 内边距）。 */
@Composable
private fun MemorySectionRow(
    section: MemorySection,
    onMemoryClick: (Memory) -> Unit,
    onMemoryLongClick: (Memory) -> Unit,
) {
    Column(modifier = Modifier.padding(top = MemoryPageTokens.sectionTitleSpacing)) {
        Text(
            text = stringResource(section.titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = MemoryPageTokens.sectionHorizontalPadding),
        )
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = MemoryPageTokens.sectionHorizontalPadding,
                vertical = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(MemoryPageTokens.cardSpacing),
        ) {
            items(section.memories, key = { memory -> memory.id }) { memory ->
                MemoryBigCard(
                    memory = memory,
                    onClick = { onMemoryClick(memory) },
                    onLongClick = { onMemoryLongClick(memory) },
                )
            }
        }
    }
}

/** 竖版大卡（168×224 r16）：封面 + 底部渐变蒙层 + 左下大标题艺术字（17sp Bold）+ 副行（12sp 80%）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryBigCard(
    memory: Memory,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surface)
    val title = memoryTitle(memory)
    val subtitle = memorySubtitle(memory)
    Box(
        modifier = Modifier
            .width(MemoryPageTokens.cardWidth)
            .height(MemoryPageTokens.cardHeight)
            .clip(RoundedCornerShape(MemoryPageTokens.cardCornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "$title · $subtitle" },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(memory.coverUri)
                .size(512)
                .crossfade(false) // 红线：关闭交叉淡入淡出防 recycled bitmap 崩溃
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

注意：
- `memoriesViewModel.memories.collectAsStateWithLifecycle()` 需 `androidx.lifecycle.compose.collectAsStateWithLifecycle` + `androidx.compose.runtime.getValue` import
- `hideError` 提示：参照 GalleryScreen 现有对 `hideError` 的消费方式（若有 Snackbar 则平移；若无则本期只保留 VM 层防御，UI 不新增提示——按 GalleryScreen 现状为准，不发明新 UI）
- `isActivePage` 参数预留（与其他 Pager 页签名对齐；若页内无 BackHandler 需求可在 KDoc 说明保留原因）

- [ ] **Step 3: 删除 MemoriesCarousel.kt**（确认全仓无引用后删除：`grep -rn "MemoriesCarousel" androidApp/src/main` 应只剩 GalleryScreen 一处，该处在 Task 5 移除后再删文件）

- [ ] **Step 4: Commit**（编译并入 Task 5 统一验证）

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/memories/ androidApp/src/main/java/com/mamba/picme/core/designsystem/DesignTokens.kt
git commit -m "feat(memories): Memory 独立页（分区大卡 feed + 5 图标底 bar），拆除旧小卡 carousel"
```

---

### Task 5: Pager + 底 bar + GalleryScreen 拆接

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/main/MainPagerHost.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`（签名减参、header 槽移除、底 bar 加第 5 项）
- Modify: `androidApp/src/main/java/com/mamba/picme/MainActivity.kt`（MainPagerHost 调用点同步）

- [ ] **Step 1: MainPagerHost.kt**

```kotlin
const val MAIN_PAGE_GALLERY = 0
const val MAIN_PAGE_DEDUP = 1
const val MAIN_PAGE_CHAT = 2
const val MAIN_PAGE_PEOPLE = 3
const val MAIN_PAGE_MEMORY = 4
const val MAIN_PAGE_COUNT = 5
```

- 文件头 KDoc 改「承载 相册/相册整理/聊天/人物/回忆 5 页」
- `LaunchedEffect(pagerState.settledPage)`：`MAIN_PAGE_GALLERY, MAIN_PAGE_DEDUP, MAIN_PAGE_MEMORY -> SceneManager.Scene.GALLERY`
- `HorizontalPager` 的 `when` 追加分支：

```kotlin
            MAIN_PAGE_MEMORY -> MemoryScreen(
                memoriesViewModel = memoriesViewModel,
                onNavigateToMemoryDetail = onNavigateToMemoryDetail,
                onNavigateToDedupHome = { onSwitchPage(MAIN_PAGE_DEDUP) },
                onNavigateToChat = { onSwitchPage(MAIN_PAGE_CHAT) },
                onNavigateToTagControl = {
                    navController.navigate(Screen.TagControl.route, navOptions { launchSingleTop = true })
                },
                onNavigateToPeople = { onSwitchPage(MAIN_PAGE_PEOPLE) },
                isActivePage = pagerState.currentPage == MAIN_PAGE_MEMORY,
            )
```

- `import com.mamba.picme.features.gallery.memories.MemoryScreen`
- MainPagerHost 参数列表 KDoc 更新（`onNavigateToMemoryDetail` 注释从「回忆卡点击」改「Memory 页大卡点击」）

- [ ] **Step 2: GalleryScreen.kt**

- 签名删除 `memoriesViewModel: MemoriesViewModel, onNavigateToMemoryDetail: (String) -> Unit` 两参；删除 `memories` 收集（`collectAsStateWithLifecycle` 处）与 `header = ...` 参数块（`:846-858`）；删除 `MemoriesCarousel` import
- 新增回调参数 `onNavigateToMemory: () -> Unit`（底 bar 第 5 项出口）
- 底 bar `tabItems` 追加第 5 项：

```kotlin
                    FloatingBottomTabItem(
                        icon = Icons.Outlined.Collections,
                        contentDescription = stringResource(R.string.tab_memories),
                        onClick = onNavigateToMemory
                    )
```

- `import androidx.compose.material.icons.outlined.Collections`
- `:866` 处注释更新为「悬浮底部 Tab — 相册整理 / 聊天 / 打标 / 人物 / 回忆（纯图标）」

- [ ] **Step 3: MainActivity.kt**

- MainPagerHost 调用点：GalleryScreen 相关 lambda 不变（MainPagerHost 内部转接）；MainPagerHost 签名无新增参数（MemoryScreen 出口在 MainPagerHost 内部用 navController/onSwitchPage 构造）——仅需确认 `memoriesViewModel` 仍传入（已传）
- GalleryScreen 的实际构造在 MainPagerHost 内（`:104-134`）：删除 `memoriesViewModel = memoriesViewModel, onNavigateToMemoryDetail = onNavigateToMemoryDetail` 两行，新增 `onNavigateToMemory = { onSwitchPage(MAIN_PAGE_MEMORY) },`

- [ ] **Step 4: 编译 + 全量单测**

```bash
./gradlew :androidApp:compileDebugKotlin --console=plain
./gradlew :androidApp:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL；若有 GalleryScreen/MemoryScreen 相关测试引用旧签名，按新签名适配

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/main/MainPagerHost.kt androidApp/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt androidApp/src/main/java/com/mamba/picme/MainActivity.kt
git commit -m "feat(memories): Memory 成 Pager 第 5 页 + 底 bar 第 5 入口，相册页拆 carousel 回归纯网格"
```

---

### Task 6: 详情页升级（55% 封面 + 精选/全部开关）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/memories/MemoryDetailScreen.kt`

- [ ] **Step 1: 改造**

- 状态：`var showAll by remember(memory?.id) { mutableStateOf(false) }`；当前集合 `val displayUris = if (showAll) memory.allItemUris else memory.itemUris`
- 封面：`MemoryCover` 高度从 `aspectRatio(16f/9f)` 改为约屏高 55%：

```kotlin
    val coverHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    Box(modifier = Modifier.fillMaxWidth().height(coverHeight)) { ... }
```

（`import androidx.compose.ui.platform.LocalConfiguration`）
- 封面副行：`memory_best_shots` 改 `memory_items_count`（总数语义）：

```kotlin
            Text(
                text = stringResource(R.string.memory_items_count, memory.hitCount) +
                    " · " + memorySubtitle(memory),
                ...
            )
```

- 网格数据源 `memory.itemUris` → `displayUris`
- 底部渐变 Share 大按钮**删除**，替换为分段开关（居中胶囊，右上分享图标保留且分享集合改 `displayUris`）：

```kotlin
            // 精选/全部分段开关（对标小米「显示优选/全部显示」）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    listOf(false to R.string.memory_detail_best, true to R.string.memory_detail_all)
                        .forEach { pair ->
                            val selected = showAll == pair.first
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else Color.Transparent,
                                    )
                                    .clickable { showAll = pair.first }
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = stringResource(pair.second),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                }
            }
```

- `memoryBrandGradient`（`ChatBubbleTokens` import 随之）若无残留引用则删除；` Icons.Outlined.Share` 保留
- 文件头 KDoc 更新（16:9 → 55% 封面、Share 大按钮 → 分段开关）

- [ ] **Step 2: 编译 + 单测**（同 Task 5 Step 4；本任务纯 UI 无新单测）

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/memories/MemoryDetailScreen.kt
git commit -m "feat(memories): 详情页升级 55% 封面 + 精选/全部分段开关（对标小米），分享集合跟随开关"
```

---

### Task 7: 文档同步 + spec 回填

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md`（§2.12 重写：carousel → 独立页；分区结构；详情页开关；帧清单）
- Modify: `androidApp/AGENTS.md`（Pager 页表/底 bar 描述、`MemoryDetail` 行封面描述校准）
- Modify: `docs/superpowers/specs/2026-09-06-memory-page-design.md`（§10 回填 Ardot 帧 ID + 截图路径；状态改「已落地」）

- [ ] **Step 1: gallery AGENTS §2.12 重写要点**：Memory 独立 Pager 页（index 4）+ 底 bar 第 5 图标（Collections）；三分区（时光=ON_THIS_DAY+RECENT_HIGHLIGHTS / 旅程=CITY / 人物=PERSON）；大卡 168×224 r16（MemoryPageTokens）；详情页 55% 封面 + 精选/全部开关（displayUris）；`Memory.allItemUris`（全部命中时间降序）；`FloatingBottomTabItem.selected`；相册 header 槽移除
- [ ] **Step 2: androidApp AGENTS.md**：路由表 `MemoryDetail` 行更新（55% 封面 + 分段开关）；若有 Pager 页清单，4 页改 5 页
- [ ] **Step 3: spec §10 回填 + 状态行**（Ardot 帧 ID 从画稿 agent 结果取；截图路径 `tmp/ardot-org/shots/`）
- [ ] **Step 4: Commit**

```bash
git add androidApp/AGENTS.md androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md docs/superpowers/specs/2026-09-06-memory-page-design.md
git commit -m "docs(memories): Memory 独立页文档同步 + spec 帧清单回填"
```

---

### Task 8: 全量验证 + 真机闭环

- [ ] **Step 1: 全量构建测试**

```bash
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest --console=plain
./gradlew :androidApp:detekt --console=plain   # 加权 finding ≤ 86，不超主干 89
```

- [ ] **Step 2: 真机验证**（设备 51912a5c 在线）：
  1. `adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk` 装包启动
  2. 相册页：无回忆 carousel、纯网格；底 bar 5 图标
  3. 底 bar 点回忆图标 → Memory 页三分区大卡；从相册页连续左滑 4 次 → 到达 Memory 页
  4. 大卡点击 → 详情页（55% 封面 + 精选/全部开关切换网格）
  5. 长按大卡 → 隐藏弹窗 → 确认后卡片消失
  6. 返回键回相册页
- [ ] **Step 3: 如实报告**（真机结果写入交付报告；任何未验项明说）

---

## Self-Review 记录

- Spec 覆盖：§3 分区 feed→Task 4；§4 详情页→Task 6；§5 数据层→Task 1；§6 导航/底 bar→Task 3/5；§7 I18N→Task 2；§8 验收→Task 8；§9 不做项无任务（正确）
- 类型一致性：`allItemUris`（Task 1 定义 → Task 6 消费）；`onNavigateToMemory`（Task 5 Step 2 定义 → MainPagerHost 接线）；`MemoryPageTokens`（Task 4 Step 1 定义 → Step 2 使用）；`tab_memories`（Task 2 定义 → Task 4/5 使用）
- 删除顺序：MemoriesCarousel.kt 在 Task 4 建、Task 5 摘除 GalleryScreen 引用后删除（Task 4 Step 3 已注明顺序约束）
