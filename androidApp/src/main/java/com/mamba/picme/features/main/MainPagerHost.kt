package com.mamba.picme.features.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.navOptions
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.features.chat.ChatScreen
import com.mamba.picme.features.chat.ChatTaskAnchor
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.features.gallery.GalleryScreen
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.dedup.DedupViewModel
import com.mamba.picme.features.gallery.memories.MemoriesViewModel
import com.mamba.picme.features.gallery.memories.MemoryScreen
import com.mamba.picme.features.gallery.organize.OrganizeHomeRoute
import com.mamba.picme.features.gallery.organize.OrganizeTab
import com.mamba.picme.features.person.PersonScreen
import com.mamba.picme.features.person.PersonViewModel
import com.mamba.picme.features.settings.SettingsViewModel
import com.mamba.picme.navigation.Screen

/** 主页面 Pager 页索引（线性，无循环回绕） */
const val MAIN_PAGE_GALLERY = 0
const val MAIN_PAGE_DEDUP = 1
const val MAIN_PAGE_CHAT = 2
const val MAIN_PAGE_PEOPLE = 3
const val MAIN_PAGE_MEMORY = 4
const val MAIN_PAGE_COUNT = 5

/**
 * 主页面容器：以 HorizontalPager 承载 相册/整理+扫描/聊天/人物/回忆 5 页。
 *
 * - 拖动跟手：横滑实时跟随手指，松手物理吸附；相册页左滑即达整理+扫描合并页（页 1）
 * - 页面常驻：beyondViewportPageCount = 4，5 页全部常驻组合，相册滚动/搜索状态滑走不丢
 * - 相机不在 Pager：2026-08-26 起相机改为 NavHost 全屏路由（Screen.Camera），
 *   仅头像拍摄与 Agent 指令进入，相机会话按路由生命周期门控
 * - 横滑使能：相册（详情/多选）与聊天（全屏预览）通过回调上报，局部禁用外层滑动
 * - 整理+扫描合并页（2026-09-06）：页 1 为 OrganizeHomeRoute 双 Tab 容器（整理/扫描），
 *   页内 Tab 选择由本宿主持有（rememberSaveable）；外部入口（设置页等 NavHost 路由）
 *   经 [organizeTabRequest] 一次性请求驱动，消费后清零
 */
@Suppress("LongParameterList")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPagerHost(
    pagerState: PagerState,
    chatViewModel: ChatViewModel,
    mediaViewModel: MediaViewModel,
    settingsViewModel: SettingsViewModel,
    personViewModel: PersonViewModel,
    dedupViewModel: DedupViewModel,
    /** Memory 页 / 详情页共用的 Activity 级 VM */
    memoriesViewModel: MemoriesViewModel,
    navController: NavHostController,
    onSwitchPage: (Int) -> Unit,
    gallerySearchRequest: Pair<String, Long>?,
    onGallerySearchRequestConsumed: () -> Unit,
    onRequestGallerySearch: (query: String, personId: Long) -> Unit,
    /** 整理中心 hub「滑动整理」(Swipe to tidy)（F2 已点亮：swipe_review 路由）。 */
    onQuickTidy: () -> Unit = {},
    /** 整理中心 hub 类目卡点击（organize_category/{category} 路由）。 */
    onOpenCategory: (OrganizeCategory) -> Unit = {},
    /** Memory 页大卡点击 → 回忆详情页（memory_detail/{memoryId} 路由）。 */
    onNavigateToMemoryDetail: (String) -> Unit = {},
    /** 整理+扫描合并页扫描 Tab 内「查看标签」出口（tag_viewer 路由）。 */
    onNavigateToTagViewer: () -> Unit = {},
    /** 外部入口（设置页）一次性请求合并页 Tab；null = 无请求。 */
    organizeTabRequest: OrganizeTab? = null,
    onOrganizeTabRequestConsumed: () -> Unit = {},
    /** 任务中心回锚请求（US-15）：切 chat 页 + 切会话 + 滚动锚定任务卡；null = 无请求。 */
    taskAnchor: ChatTaskAnchor? = null,
    onTaskAnchorConsumed: () -> Unit = {},
    /** 后台用户任务活跃数（用户任务协议 §7）：透传 ChatScreen 合并顶栏角标 */
    activeUserTaskCount: Int = 0,
) {
    var gallerySwipeEnabled by remember { mutableStateOf(true) }
    var chatSwipeEnabled by remember { mutableStateOf(true) }
    // 合并页扫描 Tab 的 OpenCL 开关（原 tag_control 路由在 MainActivity 采集，并入后收口此处）
    val tagGenerationUseOpencl by settingsViewModel.tagGenerationUseOpencl.collectAsState()
    // 合并页 Tab 选择：页内手动切换直接写；外部入口经请求一次性驱动
    var organizeTab by rememberSaveable { mutableStateOf(OrganizeTab.ORGANIZE) }
    LaunchedEffect(organizeTabRequest) {
        organizeTabRequest?.let { requested ->
            organizeTab = requested
            onOrganizeTabRequestConsumed()
        }
    }

    // 底 bar 页切换（2026-09-06 导航统一）：目标为整理页时预选 ORGANIZE tab
    // （扫描不再是独立 bar 项，深链仍走 organizeTabRequest）
    val onBarSwitchPage: (Int) -> Unit = { index ->
        if (index == MAIN_PAGE_DEDUP) organizeTab = OrganizeTab.ORGANIZE
        onSwitchPage(index)
    }

    // 场景管理：跟随 Pager 稳定页切换（相册整理与回忆页沿用相册场景，人物页无独立场景沿用进入前的场景）
    LaunchedEffect(pagerState.settledPage) {
        val scene = when (pagerState.settledPage) {
            MAIN_PAGE_GALLERY, MAIN_PAGE_DEDUP, MAIN_PAGE_MEMORY -> SceneManager.Scene.GALLERY
            MAIN_PAGE_CHAT -> SceneManager.Scene.CHAT
            else -> null
        }
        scene?.let { targetScene -> SceneManager.getInstance().transitionTo(targetScene) }
    }

    // 返回键：非相册页回到相册页（对齐原 popUpTo(Gallery) 语义，含相册整理页）。
    // 各 page 内部 BackHandler（预览/多选/顶栏返回）均以 isActivePage 守卫，
    // 仅激活页消费返回键，避免 HorizontalPager 全 page 组合下的跨页 LIFO 抢占。
    BackHandler(enabled = pagerState.currentPage != MAIN_PAGE_GALLERY) {
        onSwitchPage(MAIN_PAGE_GALLERY)
    }

    val userScrollEnabled = when (pagerState.currentPage) {
        MAIN_PAGE_GALLERY -> gallerySwipeEnabled
        MAIN_PAGE_CHAT -> chatSwipeEnabled
        else -> true
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = MAIN_PAGE_COUNT - 1,
        userScrollEnabled = userScrollEnabled,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            MAIN_PAGE_GALLERY -> GalleryScreen(
                navController = navController,
                viewModel = mediaViewModel,
                settingsViewModel = settingsViewModel,
                // 相机已是 NavHost 路由：头像拍摄登记 pending 后全屏进入
                onNavigateToCamera = {
                    navController.navigate(Screen.Camera.route, navOptions { launchSingleTop = true })
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route, navOptions { launchSingleTop = true })
                },
                onNavigateToModelCenter = {
                    navController.navigate(Screen.ModelCenter.createRoute("llm"), navOptions { launchSingleTop = true })
                },
                onNavigateToDebug = {
                    navController.navigate(Screen.Debug.route, navOptions { launchSingleTop = true })
                },
                onSwitchMainPage = onBarSwitchPage,
                searchRequest = gallerySearchRequest,
                onSearchRequestConsumed = onGallerySearchRequestConsumed,
                onHorizontalSwipeEnabledChange = { enabled -> gallerySwipeEnabled = enabled },
                isActivePage = pagerState.currentPage == MAIN_PAGE_GALLERY,
            )

            // 整理+扫描合并页（2026-09-06）：双 Tab 容器承载去重 2.0（四态不变）与 TAG 扫描控制；
            // 返回（顶栏/系统返回键由外层 BackHandler 兜底）切回相册页，不弹栈
            MAIN_PAGE_DEDUP -> OrganizeHomeRoute(
                dedupViewModel = dedupViewModel,
                selectedTab = organizeTab,
                onSelectTab = { tab -> organizeTab = tab },
                useOpencl = tagGenerationUseOpencl,
                onUseOpenclChange = { enabled ->
                    settingsViewModel.setTagGenerationUseOpencl(enabled)
                },
                onLeaveToGallery = { onSwitchPage(MAIN_PAGE_GALLERY) },
                onQuickTidy = onQuickTidy,
                onOpenCategory = onOpenCategory,
                onNavigateToTagViewer = onNavigateToTagViewer,
                onSwitchMainPage = onBarSwitchPage
            )

            MAIN_PAGE_CHAT -> ChatScreen(
                viewModel = chatViewModel,
                settingsViewModel = settingsViewModel,
                onNavigateBack = { onSwitchPage(MAIN_PAGE_GALLERY) },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route, navOptions { launchSingleTop = true })
                },
                onNavigateToGallery = { query -> onRequestGallerySearch(query, 0L) },
                mediaViewModel = mediaViewModel,
                onNavigateToPhotoEditor = { uri, autoOptimize ->
                    navController.navigate(
                        Screen.PhotoEditor.createRoute(sourceUri = uri, autoOptimize = autoOptimize),
                        navOptions { launchSingleTop = true }
                    )
                },
                onNavigateToIDPhoto = { uri ->
                    navController.navigate(
                        Screen.IDPhoto.createRoute(sourceUri = uri),
                        navOptions { launchSingleTop = true }
                    )
                },
                onNavigateToTaskCenter = {
                    navController.navigate(Screen.TaskCenter.route, navOptions { launchSingleTop = true })
                },
                taskAnchor = taskAnchor,
                onTaskAnchorConsumed = onTaskAnchorConsumed,
                onHorizontalSwipeEnabledChange = { enabled -> chatSwipeEnabled = enabled },
                isActivePage = pagerState.currentPage == MAIN_PAGE_CHAT,
                activeUserTaskCount = activeUserTaskCount
            )

            MAIN_PAGE_PEOPLE -> PersonScreen(
                viewModel = personViewModel,
                onNavigateToGallery = { personId -> onRequestGallerySearch("", personId) },
                onNavigateToCamera = {
                    navController.navigate(Screen.Camera.route, navOptions { launchSingleTop = true })
                },
                onSwitchMainPage = onBarSwitchPage
            )

            MAIN_PAGE_MEMORY -> MemoryScreen(
                memoriesViewModel = memoriesViewModel,
                onNavigateToMemoryDetail = onNavigateToMemoryDetail,
                onSwitchMainPage = onBarSwitchPage,
            )
        }
    }
}
