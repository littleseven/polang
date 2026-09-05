@file:Suppress("OPT_IN_USAGE_ERROR")

package com.mamba.picme

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navOptions
import com.mamba.picme.core.designsystem.PoLangTheme
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.ThemeMode
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.features.common.avatar.AvatarCaptureController
import com.mamba.picme.features.common.avatar.AvatarCaptureOrigin
import com.mamba.picme.features.common.avatar.AvatarCaptureTarget
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.features.debug.DebugScreen
import com.mamba.picme.features.debug.JsBridgeScreen
import com.mamba.picme.features.debug.LlmCallLogScreen
import com.mamba.picme.features.editor.PhotoEditorScreen
import com.mamba.picme.features.editor.PhotoEditorViewModel
import com.mamba.picme.features.idphoto.IDPhotoScreen
import com.mamba.picme.features.idphoto.IDPhotoViewModel
import com.mamba.picme.features.gallery.organize.OrganizeCategoryScreen
import com.mamba.picme.features.gallery.organize.OrganizeCategoryViewModel
import com.mamba.picme.features.gallery.swipe.SwipeReviewScreen
import com.mamba.picme.features.gallery.swipe.SwipeReviewViewModel
import com.mamba.picme.features.search.SearchTestScreen
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.components.TagGenerationControlScreen
import com.mamba.picme.features.camera.CameraScreen
import com.mamba.picme.features.gallery.dedup.DedupViewModel
import com.mamba.picme.features.translation.SentencePieceTestScreen
import com.mamba.picme.features.tagviewer.TagViewerTestScreen
import com.mamba.picme.features.settings.DataPrivacyScreen
import com.mamba.picme.features.settings.AddRemoteProviderScreen
import com.mamba.picme.features.settings.ProviderConfigScreen
import com.mamba.picme.features.settings.MemoryFactsScreen
import com.mamba.picme.features.main.MAIN_PAGE_COUNT
import com.mamba.picme.features.main.MAIN_PAGE_DEDUP
import com.mamba.picme.features.main.MAIN_PAGE_GALLERY
import com.mamba.picme.features.main.MAIN_PAGE_PEOPLE
import com.mamba.picme.features.main.MainPagerHost
import com.mamba.picme.features.person.PersonViewModel
import com.mamba.picme.features.settings.MemoryFactsViewModel
import com.mamba.picme.features.settings.CommunicationChannelScreen
import com.mamba.picme.features.settings.CommunicationChannelViewModel
import com.mamba.picme.domain.model.RemoteChannelType
import com.mamba.picme.features.settings.ModelCenterScreen
import com.mamba.picme.features.settings.SettingsCategory
import com.mamba.picme.features.settings.SettingsScreen
import com.mamba.picme.features.settings.SettingsViewModel
import com.mamba.picme.features.settings.SettingsViewModelFactory
import com.mamba.picme.features.debug.LogOverlay
import com.mamba.picme.navigation.Screen
import com.mamba.picme.core.common.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.domain.agent.capability.NavigationCapability
import com.mamba.picme.domain.agent.capability.SystemCapability
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.ui.platform.LocalConfiguration

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var currentLanguage: AppLanguage? = null

    override fun attachBaseContext(newBase: Context) {
        val repository = UserPreferencesRepository(newBase)
        val language = repository.getAppLanguageBlocking()

        val locale = getLocaleFromLanguage(language)
        val context = updateLocale(newBase, locale)
        super.attachBaseContext(context)
    }

    @ExperimentalGetImage
    @Suppress("OPT_IN_USAGE_ERROR")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = application as PoLangApplication
            val context = LocalContext.current
            val chatViewModel: ChatViewModel = viewModel(
                factory = app.container.createChatViewModelFactory()
            )
            val mediaViewModel: MediaViewModel = viewModel(
                factory = app.container.createMediaViewModelFactory()
            )
            // 去重 2.0：Activity 级作用域（与 mediaViewModel 同款），转后台扫描不被取消；
            // API < 30 无回收站授权接口，经旧删除通道兜底（uris → ids → deleteMediaByIds）。
            val dedupViewModel: DedupViewModel = viewModel(
                factory = app.container.createDedupViewModelFactory { uris ->
                    val ids = mediaViewModel.allMedia.value
                        .filter { asset -> asset.uri in uris }
                        .map { asset -> asset.id }
                    if (ids.isNotEmpty()) mediaViewModel.deleteMediaByIds(ids)
                }
            )
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    app.container.userPreferencesRepository,
                    app.container.llmModelDownloadManager,
                    context
                )
            )

            val themeMode by settingsViewModel.themeMode.collectAsState()
            val appLanguage by settingsViewModel.appLanguage.collectAsState()

            // 系统栏（状态栏 + 底部虚拟导航键）跟随**应用内**主题而非系统主题：
            // onCreate 里的无参 enableEdgeToEdge() 只认系统暗色——「应用强制深色 + 系统浅色」
            // 时虚拟键保持深色图标，叠在深色底栏上几乎不可见（浅色反向同理）。scrims 沿用
            // androidx 默认值（<29 半透明深色兜底），仅改暗色判定来源。
            val appDarkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(appDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.argb(0x66, 0x1b, 0x1b, 0x1b),
                    ) { appDarkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.argb(0x66, 0x1b, 0x1b, 0x1b),
                    ) { appDarkTheme },
                )
            }

            LaunchedEffect(appLanguage) {
                if (currentLanguage != null && currentLanguage != appLanguage) {
                    recreate()
                }
                currentLanguage = appLanguage
            }

            // 进入应用且处于 WiFi 时，后台静默下载缺失的 Tier 1 + Tier 2 模型
            LaunchedEffect(Unit) {
                settingsViewModel.startSilentDownloadIfWifi()
            }

            CompositionLocalProvider(
                LocalConfiguration provides Configuration(context.resources.configuration).apply {
                    setLocale(getLocaleFromLanguage(appLanguage))
                }
            ) {
                // dynamicColor 显式钉 false：2026-08-19 签核全局钉青玉，防 Theme.kt 默认值未来回摆
                PoLangTheme(themeMode = themeMode, dynamicColor = false) {
                    val navController = rememberNavController()
                    // 主页面 Pager 状态：提升到此层，保证导航到二级页再返回后页位与滚动状态保留
                    val pagerState = rememberPagerState(
                        initialPage = MAIN_PAGE_GALLERY,
                        pageCount = { MAIN_PAGE_COUNT }
                    )
                    val scope = rememberCoroutineScope()
                    var gallerySearchRequest by remember { mutableStateOf<Pair<String, Long>?>(null) }

                    // 主页面切换（底部 Tab / 编程入口）：瞬时跳转，无横滑动画；手指拖动由 Pager 跟手处理
                    val switchMainPage: (Int) -> Unit = { index ->
                        if (navController.currentDestination?.route != Screen.Main.route) {
                            navController.popBackStack(Screen.Main.route, inclusive = false)
                        }
                        scope.launch { pagerState.scrollToPage(index) }
                    }

                    val personViewModel: PersonViewModel = viewModel(
                        factory = PersonViewModel.factory(
                            app.container.personRepository,
                            app.container.database,
                            app.container.faceClusterEngine
                        )
                    )

                    // Navigation/System Capability：依赖 NavController/Context，在 Activity 期创建并
                    // 注册到全局 CapabilityRegistry（唯一注册表，2026-07-29 单轨收敛——Compose
                    // CapabilityHost 已退役，不再有第二注册容器）。
                    // 主页面（相册等）已收敛进 HorizontalPager，经 mainPageSwitcher 切页；
                    // 相机为 NavHost 路由，由 Capability 内部直接 navigate
                    val navigationCapability = remember {
                        NavigationCapability(navController) { destination ->
                            when (destination) {
                                NavigationCapability.Destination.GALLERY -> switchMainPage(MAIN_PAGE_GALLERY)
                                else -> Unit
                            }
                        }
                    }
                    val systemCapability = remember { SystemCapability(applicationContext) }
                    val orchestrator = remember { AgentOrchestrator.getInstance() }
                    DisposableEffect(navigationCapability, systemCapability) {
                        orchestrator.registerCapability(navigationCapability)
                        orchestrator.registerCapability(systemCapability)
                        Logger.i(TAG, "NavigationCapability and SystemCapability registered globally")
                        onDispose {
                            // Activity recreate（切语言/配置变更）时注销旧实例，新 composition 的
                            // 新实例随后注册替换；旧实例不注销会让注册表持有捕获死 scope 的
                            // capability，agent 导航静默失效（navigate_to 假成功、切页不发生）
                            orchestrator.unregisterCapability(navigationCapability)
                            orchestrator.unregisterCapability(systemCapability)
                        }
                    }

                    LaunchedEffect(navController) {
                        Logger.i(TAG, "NavigationCapability initialized with NavController")
                    }

                    run {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets(0, 0, 0, 0)
                        ) { innerPadding ->
                            NavHost(
                                navController = navController,
                                startDestination = Screen.Main.route,
                                modifier = Modifier.padding(innerPadding),
                                enterTransition = {
                                    fadeIn(tween(400)) + slideIntoContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Start,
                                        tween(400)
                                    )
                                },
                                exitTransition = {
                                    fadeOut(tween(400)) + slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Start,
                                        tween(400)
                                    )
                                },
                                popEnterTransition = {
                                    fadeIn(tween(400)) + slideIntoContainer(
                                        AnimatedContentTransitionScope.SlideDirection.End,
                                        tween(400)
                                    )
                                },
                                popExitTransition = {
                                    fadeOut(tween(400)) + slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.End,
                                        tween(400)
                                    )
                                }
                            ) {
                            // 主页面：相机/相册/聊天/人物 由 HorizontalPager 承载，横滑跟手切换
                            composable(Screen.Main.route) {
                                MainPagerHost(
                                    pagerState = pagerState,
                                    chatViewModel = chatViewModel,
                                    mediaViewModel = mediaViewModel,
                                    settingsViewModel = settingsViewModel,
                                    personViewModel = personViewModel,
                                    dedupViewModel = dedupViewModel,
                                    navController = navController,
                                    onSwitchPage = switchMainPage,
                                    gallerySearchRequest = gallerySearchRequest,
                                    onGallerySearchRequestConsumed = { gallerySearchRequest = null },
                                    onRequestGallerySearch = { query, personId ->
                                        gallerySearchRequest = query to personId
                                        switchMainPage(MAIN_PAGE_GALLERY)
                                    },
                                    // 整理中心 hub 出口：F2 Quick tidy → 手势整理页；类目卡 → F1 类目详情路由
                                    onQuickTidy = {
                                        navController.navigate(Screen.SwipeReview.route) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onOpenCategory = { category ->
                                        navController.navigate(
                                            Screen.OrganizeCategory.createRoute(category.name)
                                        ) {
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }
                            // 相机：2026-08-26 起为 NavHost 全屏路由（原 Pager 页 0 席位由相册整理接替），
                            // 入口为头像拍摄（AvatarCaptureController 登记 pending 后 navigate）与 Agent navigate_to(camera)
                            composable(Screen.Camera.route) { backStackEntry ->
                                // 按路由生命周期门控相机会话（替代原 Pager isActivePage）：
                                // 进入 RESUMED 绑定相机，弹栈/退后台即释放
                                val routeLifecycleState by backStackEntry.lifecycle.currentStateFlow.collectAsState()
                                CameraScreen(
                                    onNavigateToGallery = { switchMainPage(MAIN_PAGE_GALLERY) },
                                    onNavigateBack = { navController.popBackStack() },
                                    viewModel = mediaViewModel,
                                    settingsViewModel = settingsViewModel,
                                    isActivePage = routeLifecycleState.isAtLeast(Lifecycle.State.RESUMED)
                                )
                            }
                            composable(
                                route = Screen.PhotoEditor.route,
                                arguments = listOf(
                                    navArgument("sourceUri") { type = NavType.StringType },
                                    navArgument("recipeUri") {
                                        type = NavType.StringType
                                        defaultValue = ""
                                        nullable = true
                                    },
                                    navArgument("autoOptimize") {
                                        type = NavType.BoolType
                                        defaultValue = false
                                    }
                                )
                            ) { backStackEntry ->
                                val encodedSource = backStackEntry.arguments?.getString("sourceUri") ?: ""
                                val encodedRecipe = backStackEntry.arguments?.getString("recipeUri").orEmpty()
                                val sourceUri = java.net.URLDecoder.decode(encodedSource, "UTF-8")
                                val recipeUri = encodedRecipe.takeIf { it.isNotBlank() }
                                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                                val autoOptimize = backStackEntry.arguments?.getBoolean("autoOptimize") ?: false

                                val factory = app.container.createPhotoEditorViewModelFactory()
                                val viewModel: PhotoEditorViewModel = viewModel(factory = factory)

                                PhotoEditorScreen(
                                    sourceUri = sourceUri,
                                    recipeUri = recipeUri,
                                    autoOptimize = autoOptimize,
                                    viewModel = viewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onEditSaved = { outputUri ->
                                        navController.previousBackStackEntry
                                            ?.savedStateHandle
                                            ?.set("photo_editor_output_uri", outputUri)
                                        navController.popBackStack()
                                    }
                                )
                            }
                            composable(
                                route = Screen.IDPhoto.route,
                                arguments = listOf(
                                    navArgument("sourceUri") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val encodedSource = backStackEntry.arguments?.getString("sourceUri") ?: ""
                                val sourceUri = java.net.URLDecoder.decode(encodedSource, "UTF-8")
                                val factory = app.container.createIDPhotoViewModelFactory()
                                val viewModel: IDPhotoViewModel = viewModel(factory = factory)
                                IDPhotoScreen(
                                    sourceUri = sourceUri,
                                    viewModel = viewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onSaved = { navController.popBackStack() }
                                )
                            }
                            composable(
                                route = Screen.OrganizeCategory.route,
                                arguments = listOf(
                                    navArgument("category") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val categoryName = backStackEntry.arguments?.getString("category").orEmpty()
                                val category = runCatching {
                                    OrganizeCategory.valueOf(categoryName)
                                }.getOrNull()
                                if (category == null) {
                                    // 非法类目参数（旧版本路由/手写 deep link）：直接弹栈不崩溃
                                    LaunchedEffect(Unit) { navController.popBackStack() }
                                } else {
                                    val viewModel: OrganizeCategoryViewModel = viewModel(
                                        factory = app.container.createOrganizeCategoryViewModelFactory(category)
                                    )
                                    OrganizeCategoryScreen(
                                        viewModel = viewModel,
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                }
                            }
                            composable(Screen.SwipeReview.route) {
                                val viewModel: SwipeReviewViewModel = viewModel(
                                    factory = app.container.createSwipeReviewViewModelFactory()
                                )
                                SwipeReviewScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.TagControl.route) {
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.GALLERY)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.GALLERY)
                                    }
                                }
                                val useOpencl by settingsViewModel.tagGenerationUseOpencl.collectAsState()
                                TagGenerationControlScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToTagViewer = {
                                        navController.navigate(
                                            Screen.TagViewer.route,
                                            navOptions { launchSingleTop = true }
                                        )
                                    },
                                    useOpencl = useOpencl,
                                    onUseOpenclChange = { enabled ->
                                        settingsViewModel.setTagGenerationUseOpencl(enabled)
                                    }
                                )
                            }
                            composable(Screen.TagViewer.route) {
                                TagViewerTestScreen(onNavigateBack = { navController.popBackStack() })
                            }
                            composable(Screen.Settings.route) {
                                // 场景管理：进入 Settings 主页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.SETTINGS)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.SETTINGS)
                                    }
                                }
                                SettingsScreen(
                                    viewModel = settingsViewModel,
                                    category = SettingsCategory.MAIN,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToModelCenter = { categoryTag ->
                                        navController.navigate(Screen.ModelCenter.createRoute(categoryTag), navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToTagControl = {
                                        navController.navigate(Screen.TagControl.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToTagViewer = {
                                        navController.navigate(Screen.TagViewer.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToDebug = {
                                        navController.navigate(Screen.Debug.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToJsBridge = {
                                        navController.navigate(Screen.JsBridge.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToSearchTest = {
                                        navController.navigate(Screen.SearchTest.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToLlmLog = {
                                        navController.navigate(Screen.LlmLog.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToCategory = { target ->
                                        navController.navigate(Screen.SettingsCategory.createRoute(target.name.lowercase()), navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToDataPrivacy = {
                                        navController.navigate(Screen.DataPrivacy.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToMemoryFacts = {
                                        navController.navigate(Screen.MemoryFacts.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToCommunicationChannel = {
                                        navController.navigate(Screen.CommunicationChannel.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToPeople = {
                                        // 人物页已是主页面 Pager 一页：切页并弹回 Main
                                        switchMainPage(MAIN_PAGE_PEOPLE)
                                    },
                                    onNavigateToDedupHome = {
                                        // 相册整理已是主页面 Pager 页 1：切页并弹回 Main
                                        switchMainPage(MAIN_PAGE_DEDUP)
                                    },
                                    onNavigateToAddProvider = {
                                        navController.navigate(Screen.AddRemoteProvider.route, navOptions { launchSingleTop = true })
                                    },
                                    onCaptureSelfAvatar = {
                                        // 账号 Hero 卡相机角标：登记 pending 后导航到相机路由拍「我」的头像
                                        AvatarCaptureController.begin(
                                            AvatarCaptureTarget.Self,
                                            AvatarCaptureOrigin.SETTINGS_PAGE
                                        )
                                        navController.navigate(Screen.Camera.route, navOptions { launchSingleTop = true })
                                    }
                                )
                            }
                            composable(
                                route = Screen.SettingsCategory.route,
                                arguments = listOf(
                                    navArgument("category") {
                                        type = NavType.StringType
                                        defaultValue = ""
                                    }
                                )
                            ) { backStackEntry ->
                                // 场景管理：进入 Settings 二级分类页
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.SETTINGS)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.SETTINGS)
                                    }
                                }
                                val categoryArg = backStackEntry.arguments?.getString("category") ?: ""
                                val category = try {
                                    SettingsCategory.valueOf(categoryArg.uppercase())
                                } catch (_: IllegalArgumentException) {
                                    SettingsCategory.MAIN
                                }
                                SettingsScreen(
                                    viewModel = settingsViewModel,
                                    category = category,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToModelCenter = { categoryTag ->
                                        navController.navigate(Screen.ModelCenter.createRoute(categoryTag), navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToTagControl = {
                                        navController.navigate(Screen.TagControl.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToTagViewer = {
                                        navController.navigate(Screen.TagViewer.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToDebug = {
                                        navController.navigate(Screen.Debug.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToJsBridge = {
                                        navController.navigate(Screen.JsBridge.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToSearchTest = {
                                        navController.navigate(Screen.SearchTest.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToLlmLog = {
                                        navController.navigate(Screen.LlmLog.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToCategory = { target ->
                                        navController.navigate(Screen.SettingsCategory.createRoute(target.name.lowercase()), navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToDataPrivacy = {
                                        navController.navigate(Screen.DataPrivacy.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToMemoryFacts = {
                                        navController.navigate(Screen.MemoryFacts.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToCommunicationChannel = {
                                        navController.navigate(Screen.CommunicationChannel.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToPeople = {
                                        // 人物页已是主页面 Pager 一页：切页并弹回 Main
                                        switchMainPage(MAIN_PAGE_PEOPLE)
                                    },
                                    onNavigateToDedupHome = {
                                        // 相册整理已是主页面 Pager 页 1：切页并弹回 Main
                                        switchMainPage(MAIN_PAGE_DEDUP)
                                    },
                                    onNavigateToAddProvider = {
                                        navController.navigate(Screen.AddRemoteProvider.route, navOptions { launchSingleTop = true })
                                    },
                                    onCaptureSelfAvatar = {
                                        // 账号 Hero 卡相机角标：登记 pending 后导航到相机路由拍「我」的头像
                                        AvatarCaptureController.begin(
                                            AvatarCaptureTarget.Self,
                                            AvatarCaptureOrigin.SETTINGS_PAGE
                                        )
                                        navController.navigate(Screen.Camera.route, navOptions { launchSingleTop = true })
                                    }
                                )
                            }
                            // 添加远程模型：供应商列表页（精确路由，优先于 settings/{category} 占位）
                            composable(Screen.AddRemoteProvider.route) {
                                val remoteModelConfigs by settingsViewModel.aiAgentRemoteModelConfigs.collectAsState()
                                AddRemoteProviderScreen(
                                    configsJson = remoteModelConfigs,
                                    onNavigateBack = { navController.popBackStack() },
                                    onProviderSelected = { providerId ->
                                        navController.navigate(
                                            Screen.ProviderConfig.createRoute(providerId),
                                            navOptions { launchSingleTop = true }
                                        )
                                    }
                                )
                            }
                            // 供应商配置页：保存后确定性弹掉 config + add 两页，落回远程模型列表
                            composable(
                                route = Screen.ProviderConfig.route,
                                arguments = listOf(
                                    navArgument("providerId") {
                                        type = NavType.StringType
                                        defaultValue = Screen.ProviderConfig.CUSTOM_PROVIDER_ID
                                    }
                                )
                            ) { backStackEntry ->
                                val providerId = backStackEntry.arguments?.getString("providerId")
                                    ?: Screen.ProviderConfig.CUSTOM_PROVIDER_ID
                                val remoteModelConfigs by settingsViewModel.aiAgentRemoteModelConfigs.collectAsState()
                                ProviderConfigScreen(
                                    providerId = providerId,
                                    configsJson = remoteModelConfigs,
                                    onConfigsChange = { settingsViewModel.setAiAgentRemoteModelConfigs(it) },
                                    onSaved = {
                                        navController.popBackStack(Screen.AddRemoteProvider.route, inclusive = true)
                                    },
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(
                                route = Screen.ModelCenter.route,
                                arguments = listOf(
                                    navArgument("categoryTag") {
                                        type = NavType.StringType
                                        defaultValue = ""
                                    }
                                )
                            ) { backStackEntry ->
                                // 场景管理：进入 Settings 子页面（复用 SETTINGS 场景）
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.SETTINGS)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.SETTINGS)
                                    }
                                }
                                val categoryTag = backStackEntry.arguments?.getString("categoryTag") ?: ""
                                ModelCenterScreen(
                                    viewModel = settingsViewModel,
                                    initialCategoryTag = categoryTag,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.DataPrivacy.route) {
                                DataPrivacyScreen(onNavigateBack = { navController.popBackStack() })
                            }
                            composable(Screen.CommunicationChannel.route) {
                                val communicationChannelViewModel: CommunicationChannelViewModel = viewModel(
                                    factory = CommunicationChannelViewModel.factory(app.container.userPreferencesRepository)
                                )
                                val selectedChannel by communicationChannelViewModel.selectedChannel.collectAsState()
                                val feishuAppId by communicationChannelViewModel.feishuAppId.collectAsState()
                                val feishuAppSecret by communicationChannelViewModel.feishuAppSecret.collectAsState()
                                val telegramBotToken by communicationChannelViewModel.telegramBotToken.collectAsState()
                                val isConfigured = when (selectedChannel) {
                                    RemoteChannelType.FEISHU -> feishuAppId.isNotBlank() && feishuAppSecret.isNotBlank()
                                    RemoteChannelType.TELEGRAM -> telegramBotToken.isNotBlank()
                                    RemoteChannelType.NONE -> false
                                }
                                CommunicationChannelScreen(
                                    viewModel = communicationChannelViewModel,
                                    isConnected = app.remoteChannelManager.isConnected,
                                    isConfigured = isConfigured,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.MemoryFacts.route) {
                                val memoryFactsViewModel: MemoryFactsViewModel = viewModel(
                                    factory = MemoryFactsViewModel.factory(
                                        app.container.memoryRepository,
                                        app.container.personRepository
                                    )
                                )
                                MemoryFactsScreen(
                                    viewModel = memoryFactsViewModel,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.Debug.route) {
                                // 场景管理：进入 Debug 页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.DEBUG)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.DEBUG)
                                    }
                                }
                                DebugScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    mediaViewModel = mediaViewModel
                                )
                            }
                            if (BuildConfig.DEBUG) {
                                composable(Screen.JsBridge.route) {
                                    JsBridgeScreen(
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                }
                                composable(Screen.SearchTest.route) {
                                    SearchTestScreen(
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                }
                                composable(Screen.SentencePieceTest.route) {
                                    SentencePieceTestScreen(
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                }
                            }
                            // 诊断页全构建可用：release 下仅展示纯指标，不含消息内容
                            composable(Screen.LlmLog.route) {
                                LlmCallLogScreen(
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            }
                        }
                    }

                    // 全局日志浮层：跨越页面生命周期
                    val showLogOverlay by settingsViewModel.showLogOverlay.collectAsState()
                    if (showLogOverlay) {
                        LogOverlay(onDismiss = { settingsViewModel.setShowLogOverlay(false) })
                    }
                }
            }
        }
    }

    private fun getLocaleFromLanguage(language: AppLanguage): Locale {
        return when (language) {
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
            AppLanguage.SPANISH -> Locale("es")
            AppLanguage.FRENCH -> Locale.FRENCH
            AppLanguage.SYSTEM -> Locale.getDefault()
        }
    }

    private fun updateLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        // Update resources for old context
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        return context.createConfigurationContext(config)
    }
}
