package com.mamba.picme.features.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.navOptions
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.features.chat.ChatScreen
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.features.gallery.GalleryScreen
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.dedup.DedupHomeRoute
import com.mamba.picme.features.gallery.dedup.DedupViewModel
import com.mamba.picme.features.gallery.memories.MemoriesViewModel
import com.mamba.picme.features.gallery.memories.MemoryScreen
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
 * 主页面容器：以 HorizontalPager 承载 相册/相册整理/聊天/人物/回忆 5 页。
 *
 * - 拖动跟手：横滑实时跟随手指，松手物理吸附；相册页左滑即达相册整理（页 1）
 * - 页面常驻：beyondViewportPageCount = 4，5 页全部常驻组合，相册滚动/搜索状态滑走不丢
 * - 相机不在 Pager：2026-08-26 起相机改为 NavHost 全屏路由（Screen.Camera），
 *   仅头像拍摄与 Agent 指令进入，相机会话按路由生命周期门控
 * - 横滑使能：相册（详情/多选）与聊天（全屏预览）通过回调上报，局部禁用外层滑动
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
    /** 整理中心 hub「Quick tidy up」（F2 路由未点亮，先空占位）。 */
    onQuickTidy: () -> Unit = {},
    /** 整理中心 hub 类目卡点击（Task 5 点亮，先空占位）。 */
    onOpenCategory: (OrganizeCategory) -> Unit = {},
    /** Memory 页大卡点击 → 回忆详情页（memory_detail/{memoryId} 路由）。 */
    onNavigateToMemoryDetail: (String) -> Unit = {},
) {
    var gallerySwipeEnabled by remember { mutableStateOf(true) }
    var chatSwipeEnabled by remember { mutableStateOf(true) }

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
                onNavigateToChat = { onSwitchPage(MAIN_PAGE_CHAT) },
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
                onNavigateToTagControl = {
                    navController.navigate(Screen.TagControl.route, navOptions { launchSingleTop = true })
                },
                // 相册整理是相邻 Pager 页：切页而非导航（与底部 Tab 瞬时切页风格一致）
                onNavigateToDedupHome = { onSwitchPage(MAIN_PAGE_DEDUP) },
                onNavigateToPeople = { onSwitchPage(MAIN_PAGE_PEOPLE) },
                searchRequest = gallerySearchRequest,
                onSearchRequestConsumed = onGallerySearchRequestConsumed,
                onHorizontalSwipeEnabledChange = { enabled -> gallerySwipeEnabled = enabled },
                isActivePage = pagerState.currentPage == MAIN_PAGE_GALLERY,
                onNavigateToMemory = { onSwitchPage(MAIN_PAGE_MEMORY) }
            )

            // 相册整理（去重 2.0）Pager 托管：内部 Config→Scanning→Results→Cleaned 四态不变；
            // 返回（顶栏/系统返回键由外层 BackHandler 兜底）切回相册页，不弹栈
            MAIN_PAGE_DEDUP -> DedupHomeRoute(
                viewModel = dedupViewModel,
                onNavigateBack = { onSwitchPage(MAIN_PAGE_GALLERY) },
                onQuickTidy = onQuickTidy,
                onOpenCategory = onOpenCategory
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
                onHorizontalSwipeEnabledChange = { enabled -> chatSwipeEnabled = enabled },
                isActivePage = pagerState.currentPage == MAIN_PAGE_CHAT
            )

            MAIN_PAGE_PEOPLE -> PersonScreen(
                viewModel = personViewModel,
                onNavigateBack = { onSwitchPage(MAIN_PAGE_GALLERY) },
                onNavigateToGallery = { personId -> onRequestGallerySearch("", personId) },
                onNavigateToCamera = {
                    navController.navigate(Screen.Camera.route, navOptions { launchSingleTop = true })
                },
                isActivePage = pagerState.currentPage == MAIN_PAGE_PEOPLE
            )

            MAIN_PAGE_MEMORY -> MemoryScreen(
                memoriesViewModel = memoriesViewModel,
                onNavigateToMemoryDetail = onNavigateToMemoryDetail,
                onNavigateToDedupHome = { onSwitchPage(MAIN_PAGE_DEDUP) },
                onNavigateToChat = { onSwitchPage(MAIN_PAGE_CHAT) },
                onNavigateToTagControl = {
                    navController.navigate(Screen.TagControl.route, navOptions { launchSingleTop = true })
                },
                onNavigateToPeople = { onSwitchPage(MAIN_PAGE_PEOPLE) },
            )
        }
    }
}
