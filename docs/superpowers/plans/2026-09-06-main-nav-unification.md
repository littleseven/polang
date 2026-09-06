# 主页面导航统一（五页一面、聊天沉浸）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 底 bar 五项与主页面 Pager 页 1:1（相册/整理/聊天/人物/回忆），四个根页共享一个悬浮 bar 组件；People 升根页；整理双图标收敛；回忆换 AutoAwesome 图标。

**Architecture:** 新建共享组件 `MainFloatingBottomBar`（`features/main/`，表驱动 5 项）；各根页在自身根 Box 内渲染并传入 `onSwitchMainPage`；MainPagerHost 统一包装回调（目标=整理页时预选 ORGANIZE tab）。聊天页零改动。embedded 子页顶栏返回箭头按根页规则移除。

**Tech Stack:** Jetpack Compose（material-icons-extended）、Navigation Compose（路由不变）。

**Spec:** `docs/superpowers/specs/2026-09-06-main-nav-unification-design.md`（已批准 + 3 处校准：返回语义沿用 BackHandler 回相册、整理页 bar 状态门控、组件位置 features/main）。

**测试策略说明:** 全部改动为 Compose UI（本工程无 Compose UI 测试基建，UI 验证走编译 + 既有单测 + 设备截图）；逻辑性红线由 `MemoriesViewModelTest` 等既有测试守护。

---

### Task 0: Worktree 与分支

**Files:** 无（环境准备）

- [ ] **Step 0.1: 创建隔离 worktree**（主树可能有并发会话）

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang
git worktree add .worktrees/feat-main-nav-unification -b feat/main-nav-unification
```

- [ ] **Step 0.2: 进入 worktree 后续所有命令在此目录执行**

基准：main `ca936b502`（含合并 session 的 796b0f342/81cc0a37b）。

---

### Task 1: i18n 字符串（新增 tab_gallery ×5、删除 tag_scan_control ×5）

**Files:**
- Modify: `androidApp/src/main/res/values/strings.xml:502`（tab_memories 附近）
- Modify: `androidApp/src/main/res/values-zh-rCN/strings.xml:486`
- Modify: `androidApp/src/main/res/values-zh-rTW/strings.xml:486`
- Modify: `androidApp/src/main/res/values-es/strings.xml:489`
- Modify: `androidApp/src/main/res/values-fr/strings.xml:489`
- 各文件同时删除 `tag_scan_control` 行（values:1142；kt 引用仅 GalleryScreen:865 / MemoryScreen:164，Task 3/4 一并移除）

- [ ] **Step 1.1: 五语新增 `tab_gallery`（相册 bar 项 contentDescription）**

| 文件 | 行（tab_memories 邻近） | 加入 |
|---|---|---|
| values/strings.xml | 502 | `<string name="tab_gallery">Gallery</string>` |
| values-zh-rCN/strings.xml | 486 | `<string name="tab_gallery">相册</string>` |
| values-zh-rTW/strings.xml | 486 | `<string name="tab_gallery">相簿</string>` |
| values-es/strings.xml | 489 | `<string name="tab_gallery">Galería</string>` |
| values-fr/strings.xml | 489 | `<string name="tab_gallery">Galerie</string>` |

- [ ] **Step 1.2: 五语删除 `tag_scan_control` 行**（bar 打标项移除后零引用；`grep -rn tag_scan_control androidApp/src/main` 应只剩本 Task 将删的 5 行 xml）

- [ ] **Step 1.3: 校验** `grep -rn "tab_gallery\|tag_scan_control" androidApp/src/main/res/ | wc -l` → 5（仅 tab_gallery 五行）

---

### Task 2: 共享组件 MainFloatingBottomBar

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/main/MainFloatingBottomBar.kt`

- [ ] **Step 2.1: 写组件（完整文件）**

```kotlin
package com.mamba.picme.features.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.features.common.components.FloatingBottomTab
import com.mamba.picme.features.common.components.FloatingBottomTabItem

/**
 * 主页面悬浮底 bar（2026-09-06 导航统一）：五项与 Pager 页序 1:1——
 * 相册/整理/聊天/人物/回忆。渲染于四个根页（相册/整理/人物/回忆，各自高亮自身项）；
 * 聊天页沉浸式不渲染。打标图标已移除（TAG 扫描并入整理页 SCAN tab，
 * 外部深链仍走 organizeTabRequest）。
 *
 * - 表驱动：icon + contentDescription + 页索引；选中项高亮且点击空操作
 * - 目标为整理页（[MAIN_PAGE_DEDUP]）时由调用方包装回调预选 ORGANIZE tab（见 MainPagerHost）
 */
@Composable
fun MainFloatingBottomBar(
    selectedMainPage: Int,
    onSwitchPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingBottomTab(
        items = listOf(
            bottomBarItem(
                icon = Icons.Outlined.PhotoLibrary,
                labelRes = R.string.tab_gallery,
                page = MAIN_PAGE_GALLERY,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
            bottomBarItem(
                icon = Icons.Outlined.CleaningServices,
                labelRes = R.string.gallery_cleanup,
                page = MAIN_PAGE_DEDUP,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
            bottomBarItem(
                icon = Icons.Outlined.ChatBubble,
                labelRes = R.string.chat,
                page = MAIN_PAGE_CHAT,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
            bottomBarItem(
                icon = Icons.Outlined.AccountCircle,
                labelRes = R.string.gallery_people_entry,
                page = MAIN_PAGE_PEOPLE,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
            bottomBarItem(
                icon = Icons.Outlined.AutoAwesome,
                labelRes = R.string.tab_memories,
                page = MAIN_PAGE_MEMORY,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
        ),
        modifier = modifier,
    )
}

/** 单项构造：选中项高亮、点击空操作；未选中项点击回调切页。 */
@Composable
private fun bottomBarItem(
    icon: ImageVector,
    labelRes: Int,
    page: Int,
    selectedMainPage: Int,
    onSwitchPage: (Int) -> Unit,
): FloatingBottomTabItem {
    val isSelected = selectedMainPage == page
    return FloatingBottomTabItem(
        icon = icon,
        contentDescription = stringResource(labelRes),
        selected = isSelected,
        onClick = { if (!isSelected) onSwitchPage(page) },
    )
}
```

- [ ] **Step 2.2: 编译验证** `./gradlew :androidApp:compileDebugKotlin` → BUILD SUCCESSFUL

---

### Task 3: MemoryScreen 接共享 bar（回忆换标）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/memories/MemoryScreen.kt`

- [ ] **Step 3.1: 签名替换**（L93-101）——删除 `onNavigateToDedupHome` / `onNavigateToChat` / `onNavigateToTagControl` / `onNavigateToPeople` 四参，新增：

```kotlin
@Composable
fun MemoryScreen(
    memoriesViewModel: MemoriesViewModel,
    onNavigateToMemoryDetail: (String) -> Unit,
    /** 底 bar 页切换出口（相册/整理/聊天/人物；回忆为本页，选中项空操作） */
    onSwitchMainPage: (Int) -> Unit,
) {
```

- [ ] **Step 3.2: bar 块替换**（L149-183 整块 FloatingBottomTab(...5 项...) 换为）：

```kotlin
        // 悬浮底 bar：回忆项 selected 高亮（共享组件，五项与 Pager 页序 1:1）
        MainFloatingBottomBar(
            selectedMainPage = MAIN_PAGE_MEMORY,
            onSwitchPage = onSwitchMainPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        )
```

- [ ] **Step 3.3: KDoc 更新**（L89 「底部悬浮 5 图标底 bar（回忆项 selected 高亮，出口与 GalleryScreen 同源）」→「底部悬浮底 bar（共享 [MainFloatingBottomBar]，回忆项高亮；2026-09-06 导航统一）」）

- [ ] **Step 3.4: import 清理**——删 `outlined.AccountCircle/BurstMode/ChatBubble/Collections/Sell`、`features.common.components.FloatingBottomTab/Item`；加 `com.mamba.picme.features.main.MAIN_PAGE_MEMORY`、`com.mamba.picme.features.main.MainFloatingBottomBar`

- [ ] **Step 3.5: 编译验证**（此步 MainPagerHost 旧调用会失败属预期——仅确认本文件无新错误，最终接线在 Task 7；若想保持绿可先跳过编译，Task 7 后统一验证）

---

### Task 4: GalleryScreen 接共享 bar（相册项新增高亮、打标项移除）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt:120-142,588-601,848-886`

- [ ] **Step 4.1: 签名替换**——删 `onNavigateToChat`（L124）/`onNavigateToTagControl`（L129）/`onNavigateToPeople`（L130）/`onNavigateToDedupHome`（L131-132）/`onNavigateToMemory`（L140-141），新增：

```kotlin
    /** 底 bar 页切换出口（整理/聊天/人物/回忆；相册为本页，选中项空操作；人物过滤态返回也复用） */
    onSwitchMainPage: (Int) -> Unit = {},
```

- [ ] **Step 4.2: L600 人物过滤返回复用**：`if (returnToPeople) onNavigateToPeople()` → `if (returnToPeople) onSwitchMainPage(MAIN_PAGE_PEOPLE)`

- [ ] **Step 4.3: bar 块替换**（L848-886 `if (selectedMediaIndex == null) { val tabItems = ... FloatingBottomTab(...) }` 整块换为）：

```kotlin
            // 悬浮底 bar — 相册/整理/聊天/人物/回忆（纯图标，与 Pager 页序 1:1；详情态隐藏）
            // 2026-09-06 导航统一：新增相册项（本页高亮）；打标图标移除（已并入整理页 SCAN tab）
            if (selectedMediaIndex == null) {
                MainFloatingBottomBar(
                    selectedMainPage = MAIN_PAGE_GALLERY,
                    onSwitchPage = onSwitchMainPage,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .navigationBarsPadding()
                )
            }
```

- [ ] **Step 4.4: import 清理**——删 `outlined.AccountCircle/BurstMode/ChatBubble/Collections/Sell`（各仅 bar 一处使用，已核实）、`features.common.components.FloatingBottomTab/Item`；加 `com.mamba.picme.features.main.MAIN_PAGE_GALLERY`、`MAIN_PAGE_PEOPLE`、`MainFloatingBottomBar`

---

### Task 5: PersonScreen 升根页（去返回箭头 + 挂 bar + 底部避让）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/person/PersonScreen.kt`

- [ ] **Step 5.1: 签名替换**（L60-69）——删 `onNavigateBack`、`isActivePage`，新增：

```kotlin
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    onNavigateToGallery: (Long) -> Unit,
    /** 人物编辑页相机角标：登记 pending 头像拍摄后切到相机页（Pager 页 0） */
    onNavigateToCamera: () -> Unit = {},
    /** 底 bar 页切换出口（相册/整理/聊天/回忆；人物为本页，选中项空操作） */
    onSwitchMainPage: (Int) -> Unit = {},
) {
```

同时更新 KDoc（L56-58）补一句：「2026-09-06 升根页：去顶栏返回箭头、挂悬浮底 bar（人物项高亮），系统返回经 MainPagerHost BackHandler 回相册页」。

- [ ] **Step 5.2: 删除顶栏返回**（L148-150 `navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack, enabled = isActivePage) },` 整段删除；`AppTopBar.navigationIcon` 有默认值 `{}`，省略安全）；删 `AppTopBarNavBack` import

- [ ] **Step 5.3: 根 Box 挂 bar**（外层 `Box(Modifier.fillMaxSize())` 内、`infoTarget?.let` 覆盖层之前插入）：

```kotlin
    // 悬浮底 bar：人物项 selected 高亮（2026-09-06 升根页，对齐相册/整理/回忆）
    MainFloatingBottomBar(
        selectedMainPage = MAIN_PAGE_PEOPLE,
        onSwitchPage = onSwitchMainPage,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
            .navigationBarsPadding(),
    )
```

- [ ] **Step 5.4: 网格底部避让**（L213）：`contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)` → `contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp)`（对齐 Memory 的 96dp 预留）

- [ ] **Step 5.5: import**——加 `androidx.compose.foundation.layout.navigationBarsPadding`、`com.mamba.picme.features.main.MAIN_PAGE_PEOPLE`、`MainFloatingBottomBar`

---

### Task 6: OrganizeHomeRoute 挂 bar（状态门控）+ 子页返回箭头移除

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/organize/OrganizeHomeRoute.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupHomeScreen.kt:260-303`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt:77,281`

- [ ] **Step 6.1: OrganizeHomeRoute 签名**——`onNavigateBack` → `onLeaveToGallery`（语义：仅服务 Dedup 扫描态「后台运行」按钮离页），新增 `onSwitchMainPage`：

```kotlin
fun OrganizeHomeRoute(
    dedupViewModel: DedupViewModel,
    selectedTab: OrganizeTab,
    onSelectTab: (OrganizeTab) -> Unit,
    useOpencl: Boolean,
    onUseOpenclChange: (Boolean) -> Unit,
    /** Dedup 扫描态「后台运行」按钮离页回相册（根页无返回箭头，此回调仅服务该按钮语义） */
    onLeaveToGallery: () -> Unit,
    onQuickTidy: () -> Unit,
    onOpenCategory: (OrganizeCategory) -> Unit,
    onNavigateToTagViewer: () -> Unit,
    /** 底 bar 页切换出口（整理项=本页，高亮且点击空操作） */
    onSwitchMainPage: (Int) -> Unit,
)
```

- [ ] **Step 6.2: 子页传参改**——`DedupHomeRoute(onNavigateBack = onNavigateBack, ...)` → `DedupHomeRoute(onNavigateBack = onLeaveToGallery, ...)`（DedupHomeRoute 参数名保留，仅 OrganizeHomeRoute 层更名）；`TagGenerationControlScreen` 调用删 `onNavigateBack = onNavigateBack,` 行（Step 6.5 删其参数）

- [ ] **Step 6.3: bar 状态门控渲染**（根 Box 内、Column 之后）：

```kotlin
        // 悬浮底 bar：整理项高亮。Dedup 子页 Scanning/Results/Cleaned 态自带底部 CTA，
        // 此时隐藏悬浮 bar 防遮挡（对齐相册页详情态隐藏先例）；hub（Config）与 SCAN tab 显示
        val dedupState by dedupViewModel.uiState.collectAsState()
        val showBottomBar = selectedTab == OrganizeTab.SCAN || dedupState is DedupUiState.Config
        if (showBottomBar) {
            MainFloatingBottomBar(
                selectedMainPage = MAIN_PAGE_DEDUP,
                onSwitchPage = onSwitchMainPage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding(),
            )
        }
```

- [ ] **Step 6.4: 内容底部避让**（子页 Box `Modifier.weight(1f)` →）：

```kotlin
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = if (showBottomBar) 96.dp else 0.dp)
            ) {
```

- [ ] **Step 6.5: TagGenerationControlScreen 去返回**——删参数 `onNavigateBack: () -> Unit,`（L77）与 `navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) },`（L281）、`AppTopBarNavBack` import（L59）

- [ ] **Step 6.6: DedupTopBar 去返回**（DedupHomeScreen L260-303）——删参数 `onNavigateBack: () -> Unit,`（L262）、`navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) } },`（L282-289 整段）；调用点 L146 `onNavigateBack = onNavigateBack,` 删除；删 `automirrored.rounded.ArrowBack` import（L29）。（`DedupHomeRoute.onNavigateBack` 参数保留——仍被 L162 `onRunBackground` 使用，KDoc 注明）

- [ ] **Step 6.7: import**——OrganizeHomeRoute 加 `androidx.compose.runtime.collectAsState/getValue`、`features.gallery.dedup.DedupUiState`、`features.main.MAIN_PAGE_DEDUP/MainFloatingBottomBar`、`navigationBarsPadding`

---

### Task 7: MainPagerHost 接线 + Screen.kt 死代码

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/main/MainPagerHost.kt:127-228`
- Modify: `androidApp/src/main/java/com/mamba/picme/navigation/Screen.kt:44`

- [ ] **Step 7.1: 包装回调**（`organizeTab` 状态声明 L89 之后插入）：

```kotlin
    // 底 bar 页切换：目标为整理页时预选 ORGANIZE tab（扫描不再是独立 bar 项）
    val onBarSwitchPage: (Int) -> Unit = { index ->
        if (index == MAIN_PAGE_DEDUP) organizeTab = OrganizeTab.ORGANIZE
        onSwitchPage(index)
    }
```

- [ ] **Step 7.2: GALLERY 分支**——删 `onNavigateToChat`/`onNavigateToTagControl`/`onNavigateToDedupHome`/`onNavigateToPeople`/`onNavigateToMemory` 五个 lambda 实参，换 `onSwitchMainPage = onBarSwitchPage,`（`onNavigateToCamera/Settings/ModelCenter/Debug`、`searchRequest`、`onHorizontalSwipeEnabledChange`、`isActivePage` 保留）

- [ ] **Step 7.3: DEDUP 分支**——`onNavigateBack = { onSwitchPage(MAIN_PAGE_GALLERY) },` → `onLeaveToGallery = { onSwitchPage(MAIN_PAGE_GALLERY) },`；尾加 `onSwitchMainPage = onBarSwitchPage,`

- [ ] **Step 7.4: PEOPLE 分支**——删 `onNavigateBack = ...` 与 `isActivePage = ...` 实参；加 `onSwitchMainPage = onBarSwitchPage,`

- [ ] **Step 7.5: MEMORY 分支**——删 `onNavigateToDedupHome`/`onNavigateToChat`/`onNavigateToTagControl`/`onNavigateToPeople` 四实参；换 `onSwitchMainPage = onBarSwitchPage,`

- [ ] **Step 7.6: Screen.kt 删死声明**：`data object People : Screen("people")`（L44；全工程零引用已核实）

---

### Task 8: 编译 + 质量门

- [ ] **Step 8.1:** `./gradlew :androidApp:compileDebugKotlin` → BUILD SUCCESSFUL
- [ ] **Step 8.2:** `./gradlew :androidApp:testDebugUnitTest` → 既有测试全绿（重点 `MemoriesViewModelTest`）
- [ ] **Step 8.3:** `./gradlew :androidApp:ktlintCheck :androidApp:detekt` → 零新增（detekt 基线 ≤ 主干 86）

---

### Task 9: 文档同步（同 feature 提交）

**Files:**
- Modify: `PRODUCT.md`（§1.2 L59 当前聚焦段、§2.1 L81 相册首页层 bullet：「相册整理/聊天/打标/人物」→「相册/整理/聊天/人物/回忆五项（与 Pager 1:1，2026-09-06）；打标已并入整理页」）
- Modify: `docs/01-PRODUCT/FEATURES.md`（grep `打标`/`悬浮` 相关行，同步 bar 描述）
- Modify: `androidApp/AGENTS.md`（L39 表 Gallery 行 bar 描述；L42 People 行注根页；L45-47 区块补「2026-09-06 导航统一」条目 + 根页/沉浸页身份规则）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md`（L155 「底 bar 第 5 图标（Collections）」→「（AutoAwesome）」；§2.12 L412 底 bar 段改述共享组件/五项 1:1/相册项新增/打标项移除）

- [ ] **Step 9.1: 上述四处文档同步**（措辞随文内风格，事实以 spec 为准）
- [ ] **Step 9.2: grep 复查** `grep -rn "打标.*悬浮\|悬浮.*打标\|Collections" androidApp/AGENTS.md androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md PRODUCT.md` 无残留旧描述

---

### Task 10: 提交

- [ ] **Step 10.1:**

```bash
git add -A
git commit -m "feat(nav): 主页面导航统一——底 bar 五项与 Pager 1:1、People 升根页、整理双图标收敛

- 新增共享 MainFloatingBottomBar（相册 PhotoLibrary/整理 CleaningServices/聊天/人物/回忆 AutoAwesome），
  四根页各自高亮；聊天页沉浸不挂（spec 2026-09-06-main-nav-unification）
- People 升根页：去顶栏返回箭头 + 挂 bar + 96dp 底部避让；Screen.People 死声明删除
- 整理页挂 bar（Scanning/Results/Cleaned 态门控隐藏防 CTA 遮挡）；打标 bar 项移除，
  organizeTabRequest 深链保留；TagGen/DedupTopBar 返回箭头按根页规则移除
- 回忆 icon Collections→AutoAwesome；tab_gallery 五语新增、tag_scan_control 五语删除
- 文档同步：PRODUCT/FEATURES/androidApp AGENTS/gallery AGENTS"
```

---

### Task 11: 设备验证（有设备则执行，无则留报告）

- [ ] **Step 11.1:** `adb devices` 有设备 → `./scripts/auto-dev-loop.sh`（编译+安装+启动+截图）
- [ ] **Step 11.2:** 截图核对：相册页（bar 五图标、相册高亮、无打标项）/ 整理页 hub（整理高亮）/ 扫描 tab（bar 在）/ Dedup 结果态（bar 隐藏、CTA 完整）/ 人物页（无返回箭头、人物高亮、网格不被遮挡）/ 回忆页（AutoAwesome 星花高亮）
- [ ] **Step 11.3:** 无设备 → 在交付说明中注明「真机验证待补」

---

### 后续（非本计划范围，交付时提醒用户）

1. **Ardot 画布同步**：底 bar 出现的帧（gallery/organize/people/memory 系列 + memory-bottombar.png）icon 需云端改稿后重跑 `export-ardot-snapshot.py`（合并 session 刚做过同类同步，管线现成）
2. **iOS 对等跟随**：Android 验收后走 `/ios-follow`
3. **v2 可选**：People↔Memory 内容级直达（spec §6）

---

## Self-Review 记录

- **Spec 覆盖**：§4.1 图标→Task 2；§4.2 组件→Task 2；§4.3 打标收敛→Task 2/4/7；§5 各页→Task 3-7；§7 i18n/文档→Task 1/9；§8 顺序→Task 0/10；边界（遮挡/门控/no-op）→Task 5/6。✓
- **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码。✓
- **类型一致性**：`onSwitchMainPage: (Int) -> Unit`（页面侧）/`onBarSwitchPage`（宿主侧）/`MainFloatingBottomBar(selectedMainPage, onSwitchPage, modifier)` 全文一致；`onLeaveToGallery` 仅 OrganizeHomeRoute↔MainPagerHost。✓
