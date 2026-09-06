package com.mamba.picme.features.gallery

import android.os.Build
import android.provider.MediaStore

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import com.mamba.picme.core.common.Logger

import com.mamba.picme.features.gallery.components.BackgroundScanGuardDialog
import com.mamba.picme.features.gallery.components.EmptyGalleryMessage
import com.mamba.picme.features.gallery.components.GallerySplashPlaceholder
import com.mamba.picme.features.gallery.components.GalleryPermissionMessage
import com.mamba.picme.features.gallery.components.GalleryTopBar
import com.mamba.picme.features.gallery.components.MediaGrid
import com.mamba.picme.features.gallery.components.MediaPager
import com.mamba.picme.features.common.components.FloatingBottomTab
import com.mamba.picme.features.common.components.FloatingBottomTabItem
import com.mamba.picme.features.gallery.components.galleryReadPermissions
import com.mamba.picme.features.gallery.components.hasGalleryPermission
import androidx.core.net.toUri
import com.mamba.picme.features.gallery.components.shareMediaAssets
import com.mamba.picme.features.gallery.components.SearchTopBar
import com.mamba.picme.features.gallery.components.toMediaAsset
import com.mamba.picme.features.common.chat.rememberAgentChatConfig
import com.mamba.picme.features.settings.SettingsViewModel
import android.app.Activity
import com.mamba.picme.features.gallery.capability.GalleryCapability
import com.mamba.picme.features.common.SearchField
import com.mamba.picme.features.common.PersonRelationPicker
import com.mamba.picme.features.common.avatar.AvatarCaptureController
import com.mamba.picme.features.common.avatar.AvatarCaptureOrigin
import com.mamba.picme.features.common.avatar.AvatarCaptureTarget
import com.mamba.picme.features.person.PersonCover
import com.mamba.picme.features.person.components.PersonInfoScreen
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.navOptions
import com.mamba.picme.domain.model.GroupTitleType
import com.mamba.picme.domain.model.GroupedMedia
import com.mamba.picme.domain.model.GroupingMode
import com.mamba.picme.domain.person.PersonEditSnapshot
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.domain.person.RelationSource
import com.mamba.picme.R
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.navigation.Screen
import com.mamba.picme.service.tag.TagGenerationService
import com.mamba.picme.util.permission.BackgroundScanGuard
import com.mamba.picme.PoLangApplication
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Sell

private const val TAG = "Gallery"
private const val TAG_AGENT = "GalleryAgent"
private const val SEARCH_DEBOUNCE_MS = 300L

@OptIn(FlowPreview::class)
@Composable
fun GalleryScreen(
    navController: NavController,
    viewModel: MediaViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateToChat: () -> Unit,
    onNavigateToCamera: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToModelCenter: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToTagControl: () -> Unit = {},
    onNavigateToPeople: () -> Unit = {},
    /** 相册整理（去重 2.0）入口：悬浮底部 Tab 第一项 */
    onNavigateToDedupHome: () -> Unit = {},
    /** 外部搜索/人物过滤请求：(query, personId)，来自 Chat 搜索结果跳转或人物页跳转 */
    searchRequest: Pair<String, Long>? = null,
    onSearchRequestConsumed: () -> Unit = {},
    /** 上报是否允许外层主页面 Pager 横滑（详情/多选时禁用） */
    onHorizontalSwipeEnabledChange: (Boolean) -> Unit = {},
    /** 是否为当前激活的主页面 page（非激活时禁用内部 BackHandler，避免跨页抢占系统返回键） */
    isActivePage: Boolean = true,
    /** Memory 页入口：悬浮底部 Tab 第 5 项（回忆） */
    onNavigateToMemory: () -> Unit = {},
) {
    val groupedMedia by viewModel.groupedMedia.collectAsState()
    val groupingMode by viewModel.groupingMode.collectAsState()
    val debugUiEnabled by settingsViewModel.debugUiEnabled.collectAsState()

    var selectedMediaIndex by remember { mutableStateOf<Int?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }

    // 搜索状态(支持外部带入搜索请求,立即搜索)
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isSearchLoading by remember { mutableStateOf(false) }
    var searchResultMedia by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    // 人物页「相册」模式：直接使用人物关联媒体，不走搜索引擎，避免未命名人物被覆盖成"未匹配"
    var isPersonFilter by remember { mutableStateOf(false) }
    val searchEngine = remember { GalleryCapability.getInstance().searchEngine }

    // 媒体库整体列表：用于在删除/授权完成后自动刷新搜索结果
    val allMedia by viewModel.allMedia.collectAsState()
    LaunchedEffect(Unit) {
        snapshotFlow { allMedia }
            .debounce(300)
            .collect {
                if (isSearchActive && searchQuery.isNotBlank() && searchEngine != null && !isPersonFilter) {
                    Logger.d(TAG, "Media library changed, refreshing search results")
                    isSearchLoading = true
                    searchResultMedia = searchEngine.search(searchQuery).media
                    isSearchLoading = false
                }
            }
    }

    // 搜索输入防抖：300ms 无新输入后才触发实际搜索
    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery }
            .debounce(SEARCH_DEBOUNCE_MS)
            .collectLatest { query ->
                if (query.isBlank()) {
                    searchResultMedia = emptyList()
                    isPersonFilter = false
                    isSearchLoading = false
                } else if (isPersonFilter) {
                    // 人物相册模式：保留已加载的人物媒体，不交给搜索引擎覆盖；
                    // isSearchLoading 由 applyPersonFilter 全权控制，避免防抖先触发导致 loading 提前结束
                } else {
                    val engine = searchEngine
                    if (engine != null) {
                        searchResultMedia = engine.search(query).media
                    }
                    isSearchLoading = false
                }
            }
    }

    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication

    // 从人物页带入 personId：与人物页外显张数共用同一口径（PersonDao.getPersonMediaIds：
    // 人脸聚类 ∪ 三字段标签提及），保证两处数字一致。人名是精确约束，不走搜索引擎——
    // 语义/OCR 泛化召回会混入外显口径之外的照片，导致两处张数对不上。
    suspend fun applyPersonFilter(personId: Long) {
        val db = AppDatabase.getDatabase(context)
        val person = withContext(Dispatchers.IO) {
            db.personDao().getPerson(personId)
        }
        val label = person?.name?.takeIf { it.isNotBlank() } ?: "#$personId"

        val finalMedia = withContext(Dispatchers.IO) {
            val mediaIds = db.personDao().getPersonMediaIds(personId, person?.name.orEmpty())
            if (mediaIds.isEmpty()) {
                emptyList()
            } else {
                db.mediaDao().getMediaByIds(mediaIds)
                    .map { entity -> entity.toMediaAsset() }
                    .sortedByDescending { it.captureDate }
            }
        }

        if (finalMedia.isNotEmpty()) {
            isSearchActive = true
            isPersonFilter = true
            searchResultMedia = finalMedia
        }
        // 无论是否有结果都回填名字，避免空态文案泄漏占位 "#personId"
        searchQuery = label
        isSearchLoading = false
    }

    // 外部搜索/人物过滤请求（Chat 搜索结果跳转、人物页跳转）：驱动搜索状态
    LaunchedEffect(searchRequest) {
        val request = searchRequest ?: return@LaunchedEffect
        val (query, personId) = request
        when {
            personId > 0L -> {
                // 立即进入搜索模式并清空旧结果：
                // applyPersonFilter 为异步 DB 查询，若延迟到完成时才置 isSearchActive，
                // 加载期间 UI 会渲染全部相册/上次搜索残留，造成闪烁
                searchQuery = "#$personId"
                isSearchActive = true
                isPersonFilter = true
                searchResultMedia = emptyList()
                isSearchLoading = true
                applyPersonFilter(personId)
            }
            query.isNotBlank() -> {
                searchQuery = query
                isSearchActive = true
                isPersonFilter = false
                isSearchLoading = true
            }
        }
        onSearchRequestConsumed()
    }

    // 上报外层 Pager 横滑使能：照片详情/多选时禁用，避免与内层 MediaPager 滑动冲突
    LaunchedEffect(selectedMediaIndex, isSelectionMode) {
        onHorizontalSwipeEnabledChange(selectedMediaIndex == null && !isSelectionMode)
    }

    val allFlatMedia by remember { derivedStateOf { groupedMedia.flatMap { group -> group.items } } }
    val mediaById = remember(allFlatMedia) { allFlatMedia.associateBy { it.id } }
    // 预览媒体列表：搜索状态下仅显示搜索结果，非搜索显示全量列表
    val previewMediaList by remember {
        derivedStateOf {
            if (isSearchActive && searchQuery.isNotBlank() && searchResultMedia.isNotEmpty()) {
                searchResultMedia
            } else {
                allFlatMedia
            }
        }
    }

    // ── 后台保活引导(HyperOS 等冻结后台进程,引导加白名单/开通知/自启动) ──
    var guardIssues by remember { mutableStateOf<List<BackgroundScanGuard.Issue>>(emptyList()) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun startScanWithGuard(startAction: () -> Unit) {
        val issues = runCatching { BackgroundScanGuard.diagnose(context.applicationContext) }
            .getOrDefault(emptyList())
        if (issues.isNotEmpty() && BackgroundScanGuard.shouldShowDialog(context.applicationContext)) {
            guardIssues = issues
            pendingStart = startAction
        } else {
            startAction()
        }
    }

    if (guardIssues.isNotEmpty()) {
        BackgroundScanGuardDialog(
            issues = guardIssues,
            onGoSettings = {
                val first = guardIssues.first()
                guardIssues = emptyList()
                pendingStart = null
                first.openFix(context)
            },
            onContinue = {
                val pending = pendingStart
                guardIssues = emptyList()
                pendingStart = null
                pending?.invoke()
            },
            onDontRemind = {
                BackgroundScanGuard.doNotShowAgain(context.applicationContext)
                guardIssues = emptyList()
                pendingStart = null
            }
        )
    }

    val thumbnailCache = remember { app.container.thumbnailCache }
    val deleteAuthRequest by viewModel.deleteAuthRequest.collectAsState()

    // 人物分组名称映射（用于 PERSON 分组模式显示名称）
    val personNameMap = remember { mutableStateMapOf<String, String>() }

    // 人物信息编辑 overlay 状态（点分组名打开 PersonInfoScreen）
    var infoPersonId by remember { mutableStateOf<Long?>(null) }
    var infoSnapshot by remember { mutableStateOf<PersonEditSnapshot?>(null) }

    // 从 DB 重载人物分组名称映射（进入 PERSON 模式 / 编辑保存后刷新分组标题）
    suspend fun refreshPersonNameMap() {
        try {
            val db = AppDatabase.getDatabase(context)
            val persons = db.personDao().getAllPersons()
            personNameMap.clear()
            for (p in persons) {
                val displayName = p.name ?: context.getString(R.string.person_unnamed, p.personId)
                personNameMap[p.personId.toString()] = displayName
            }
        } catch (_: Exception) {}
    }

    // 切到 PERSON 分组模式时加载所有 person 名称
    LaunchedEffect(groupingMode) {
        if (groupingMode == GroupingMode.PERSON) refreshPersonNameMap()
    }

    // 点分组名 → 加载编辑快照；infoPersonId 置空时清 overlay
    LaunchedEffect(infoPersonId) {
        val id = infoPersonId
        if (id != null) {
            infoSnapshot = null
            runCatching { app.container.personRepository.loadPersonEditSnapshot(id) }
                .onSuccess { snapshot -> infoSnapshot = snapshot }
                .onFailure { Logger.e(TAG, "Failed to load person edit snapshot", it) }
        } else {
            infoSnapshot = null
        }
    }

    var hasMediaPermission by remember { mutableStateOf(hasGalleryPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMediaPermission = hasGalleryPermission(context)
    }

    // Android 10 (API 29) 恢复性删除权限请求 launcher
    val api29DeleteLauncher = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Logger.d(TAG, "User granted API 29 delete permission")
                viewModel.executePendingDeletes()
            } else {
                Logger.w(TAG, "User denied API 29 delete permission")
                viewModel.clearPendingRecoverable()
                viewModel.clearPendingDeleteUris()
            }
        }
    } else {
        null
    }

    // Android 11+ 删除权限请求 launcher
    val deletePermissionLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Logger.d(TAG, "User granted delete permission")
                viewModel.executePendingDeletes()
            } else {
                Logger.w(TAG, "User denied delete permission")
                viewModel.clearPendingDeleteUris()
            }
        }
    } else {
        null
    }

    LaunchedEffect(deleteAuthRequest) {
        deleteAuthRequest?.let { request ->
            when (request) {
                is MediaViewModel.DeleteAuthRequest.Api29 -> {
                    api29DeleteLauncher?.launch(
                        IntentSenderRequest.Builder(request.intentSender).build()
                    )
                }
                is MediaViewModel.DeleteAuthRequest.Api30 -> {
                    val intent = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        request.uris
                    )
                    deletePermissionLauncher?.launch(
                        IntentSenderRequest.Builder(intent).build()
                    )
                }
            }
            viewModel.consumeDeleteAuthRequest()
        }
    }

    LaunchedEffect(hasMediaPermission) {
        if (hasMediaPermission) {
            viewModel.refreshMediaLibrary()
        }
    }

    // 进入相册后，仅在蜂窝网络下检查 Tier 1 模型并弹窗提醒
    // WiFi 场景由 MainActivity 启动时静默下载，不打扰用户
    LaunchedEffect(hasMediaPermission) {
        if (hasMediaPermission) {
            settingsViewModel.checkGalleryRequiredModelsOnCellular()
        }
    }

    // AI 图片标签自动扫描 —— 仅在首次安装、夜间或充电时触发
    // 避免高频自动扫描导致耗电发烫，用户可通过顶部按钮手动触发
    // 启动前检查 Tier 1 模型是否已下载，未下载则跳过扫描；蜂窝网络下弹窗提醒
    LaunchedEffect(allFlatMedia.size) {
        if (hasMediaPermission && allFlatMedia.isNotEmpty()
            && !TagGenerationService.isScanning.value) {
            val isFirstLaunch = try {
                val prefs = context.getSharedPreferences("picme_tag_scan", android.content.Context.MODE_PRIVATE)
                val hasScanned = prefs.getBoolean("has_auto_scanned", false)
                if (!hasScanned) {
                    prefs.edit().putBoolean("has_auto_scanned", true).apply()
                    true
                } else false
            } catch (_: Exception) { false }

            val batteryIntent = try {
                context.registerReceiver(
                    null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
            } catch (_: Exception) { null }

            val isCharging = try {
                val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            } catch (_: Exception) { false }

            val isNightTime = try {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                hour in 0..6 || hour >= 23
            } catch (_: Exception) { false }

            if (isFirstLaunch || (isCharging && isNightTime)) {
                val ready = settingsViewModel.areGalleryRequiredModelsDownloaded()
                if (ready) {
                    context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                } else {
                    Logger.i(TAG, "Skipping auto scan: gallery required models not downloaded")
                    settingsViewModel.checkGalleryRequiredModelsOnCellular()
                }
            }
        }
    }

    val thumbnailPositions = remember { mutableStateMapOf<Long, Rect>() }
    var dragSelectionTargetSelected by remember { mutableStateOf(true) }
    val dragSelectionVisitedIds = remember { hashSetOf<Long>() }

    // ===== Agent Chat 配置（使用公共组件）=====
    val agentChatConfig = rememberAgentChatConfig(
        context = context,
        logTag = TAG,
        onCommand = { command ->
            Logger.i(TAG, "Voice command: ${command.javaClass.simpleName}")
        },
        onTranscript = { transcript ->
            Logger.d(TAG, "Voice transcript: $transcript")
        }
    )
    val voiceCoordinator = agentChatConfig.voiceCoordinator
    DisposableEffect(Unit) {
        onDispose {
            // 修复 P0-1：不应该完全释放 voiceCoordinator，因为它在多个 Chat 屏幕间共享
            // 而应该只进行"软释放"（停止监听但保留引擎）
            Logger.i(TAG, "Gallery screen disposed - performing soft release of voice coordinator")
            voiceCoordinator.stopWakeWordListening()
            voiceCoordinator.stopPushToTalk()
            // 注意：不调用 voiceCoordinator.release() 以避免破坏 ASR 引擎状态
        }
    }

    // 绑定 GalleryCapability 的 delegate，确保生命周期绑定
    // 使用 Unit 作为 key，确保只在页面进入/离开时绑定/解绑
    DisposableEffect(Unit) {
        Logger.i(TAG, "Binding GalleryCapability delegate, mediaCount=${allFlatMedia.size}")

        val galleryCapability = GalleryCapability.getInstance()
        galleryCapability.bindDelegate(object : GalleryCapability.Delegate {
            override fun onViewMedia(mediaId: String?) {
                mediaId?.let { id ->
                    val index = allFlatMedia.indexOfFirst { it.id.toString() == id }
                    if (index >= 0) selectedMediaIndex = index
                }
            }
            override fun onDeleteMedia(mediaIds: List<String>) {
                val ids = mediaIds.mapNotNull { it.toLongOrNull() }
                viewModel.deleteMediaByIds(ids)
            }
            override fun onShareMedia(mediaIds: List<String>) {
                val assets = allFlatMedia.filter { it.id.toString() in mediaIds }
                shareMediaAssets(context, assets)
            }
            override fun onSelectMedia(mediaId: String, selected: Boolean) {
                val id = mediaId.toLongOrNull() ?: return
                if (selected) {
                    if (!selectedIds.contains(id)) selectedIds.add(id)
                } else {
                    selectedIds.remove(id)
                }
            }
            override fun onSearch(query: String, results: List<MediaAsset>) {
                Logger.d(TAG_AGENT, "Search query: $query, results=${results.size}")
                searchQuery = query
                isSearchActive = true
                isSearchLoading = false
                searchResultMedia = results
            }
            override fun onSwitchViewMode(mode: GalleryCapability.ViewMode) {
                Logger.d(TAG_AGENT, "Switch to view mode: $mode")
            }
            override fun onFavoriteMedia(mediaId: String, favorite: Boolean) {
                Logger.d(TAG_AGENT, "Favorite $mediaId: $favorite")
            }
        })
        Logger.i(TAG, "GalleryCapability delegate bound")

        onDispose {
            Logger.i(TAG, "Unbinding GalleryCapability delegate")
            galleryCapability.unbindDelegate()
        }
    }

    BackHandler(enabled = isActivePage && (selectedMediaIndex != null || isSelectionMode)) {
        when {
            selectedMediaIndex != null -> selectedMediaIndex = null
            isSelectionMode -> {
                isSelectionMode = false
                selectedIds.clear()
            }
        }
    }

    val currentMedia = selectedMediaIndex?.let { allFlatMedia.getOrNull(it) }
    val selectedItems = selectedIds.mapNotNull { mediaById[it] }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            when {
                selectedMediaIndex == null && isSearchActive && !isSelectionMode -> {
                    // 搜索模式：显示搜索框（预览打开时不显示，避免与预览顶栏重叠）
                    SearchTopBar(
                        searchQuery = searchQuery,
                        onQueryChange = { query ->
                            if (isPersonFilter) {
                                isPersonFilter = false
                            }
                            searchQuery = query
                            if (query.isNotBlank()) {
                                isSearchLoading = true
                            } else {
                                searchResultMedia = emptyList()
                                isSearchLoading = false
                            }
                        },
                        onClose = {
                            // 人物过滤态（由人物页点击封面带入）：返回人物页，而非关闭到普通相册；
                            // 普通搜索态：清空搜索状态，回到普通相册。
                            // isPersonFilter 为 true 当且仅当来自人物页（Chat 跳转恒传 personId=0）。
                            val returnToPeople = isPersonFilter
                            isPersonFilter = false
                            searchQuery = ""
                            searchResultMedia = emptyList()
                            isSearchActive = false
                            isSearchLoading = false
                            if (returnToPeople) onNavigateToPeople()
                        },
                        resultCount = if (searchQuery.isNotBlank()) searchResultMedia.size else null
                    )
                }
                selectedMediaIndex == null -> {
                    GalleryTopBar(
                        isSelectionMode = isSelectionMode,
                        selectedCount = selectedIds.size,
                        groupingMode = groupingMode,
                        onNavigateToSettings = onNavigateToSettings,
                        onNavigateToModelCenter = onNavigateToModelCenter,
                        onToggleSelectionMode = {
                            isSelectionMode = false
                            selectedIds.clear()
                        },
                        onSelectAll = {
                            // 搜索模式下只针对搜索结果进行全选/取消全选
                            val targetItems = if (isSearchActive && searchQuery.isNotBlank()) {
                                searchResultMedia
                            } else {
                                allFlatMedia
                            }
                            if (selectedIds.size == targetItems.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(targetItems.map { it.id })
                            }
                        },
                        onDeleteSelected = {
                            val idsToDelete = selectedIds.toList()
                            viewModel.deleteMediaByIds(idsToDelete)
                            isSelectionMode = false
                            selectedIds.clear()
                        },
                        onShareSelected = {
                            val selectedAssets = selectedIds.mapNotNull { mediaById[it] }
                            shareMediaAssets(context, selectedAssets)
                        },
                        onGroupingModeSelected = { mode -> viewModel.setGroupingMode(mode) },
                        onSearchClick = {
                            isSearchActive = true
                            searchResultMedia = emptyList()
                        },
                        onTagScanClick = {
                            startScanWithGuard {
                                context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                            }
                        },
                        onToggleScan = {
                            if (TagGenerationService.isScanning.value) {
                                context.startForegroundService(TagGenerationService.intentPause(context))
                            } else {
                                startScanWithGuard {
                                    context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // TAG 扫描状态指示器
            if (TagGenerationService.isScanning.collectAsState(false).value) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            when {
                isSearchActive && searchQuery.isNotBlank() -> {
                    if (isSearchLoading && searchResultMedia.isEmpty()) {
                        // 首次搜索加载中，不显示空结果提示以避免闪烁
                        // 已有结果时不显示 loading，保持现有结果展示
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (searchResultMedia.isEmpty()) {
                        EmptyGalleryMessage(message = stringResource(R.string.search_no_matching_photos, searchQuery))
                    } else {
                        val searchGroup = GroupedMedia(
                            titleType = GroupTitleType.SEARCH,
                            titleValue = "\"$searchQuery\"",
                            items = searchResultMedia
                        )
                        MediaGrid(
                            context = context,
                            groupedMedia = listOf(searchGroup),
                            selectedIds = selectedIds,
                            isSelectionMode = isSelectionMode,
                            thumbnailPositions = thumbnailPositions,
                            mediaById = searchResultMedia.associateBy { it.id },
                            thumbnailCache = thumbnailCache,
                            onThumbnailPositioned = { id, rect -> thumbnailPositions[id] = rect },
                            onMediaClick = { asset ->
                                if (isSelectionMode) {
                                    if (selectedIds.contains(asset.id)) {
                                        selectedIds.remove(asset.id)
                                    } else {
                                        selectedIds.add(asset.id)
                                    }
                                } else {
                                    // 搜索结果点击：在搜索结果列表中查找索引
                                    var index = searchResultMedia.indexOfFirst { it.id == asset.id }
                                    // ID 未匹配时按 URI 兜底查找
                                    if (index < 0) {
                                        Logger.w(TAG, "Search result id='${asset.id}' not in searchResultMedia, fallback to URI")
                                        index = searchResultMedia.indexOfFirst { it.uri == asset.uri }
                                    }
                                    if (index >= 0) {
                                        selectedMediaIndex = index
                                    } else {
                                        Logger.e(TAG, "Search result NOT found: id='${asset.id}' uri='${asset.uri}'")
                                    }
                                }
                            },
                            onMediaLongClick = { asset ->
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedIds.add(asset.id)
                                }
                            },
                            onDragSelectionStart = { asset ->
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                }
                                dragSelectionVisitedIds.clear()
                                dragSelectionTargetSelected = !selectedIds.contains(asset.id)
                                if (dragSelectionTargetSelected) {
                                    if (!selectedIds.contains(asset.id)) {
                                        selectedIds.add(asset.id)
                                    }
                                } else {
                                    selectedIds.remove(asset.id)
                                }
                                dragSelectionVisitedIds.add(asset.id)
                            },
                            onDragSelectionItem = { asset ->
                                if (!dragSelectionVisitedIds.add(asset.id)) {
                                    return@MediaGrid
                                }
                                if (dragSelectionTargetSelected) {
                                    if (!selectedIds.contains(asset.id)) {
                                        selectedIds.add(asset.id)
                                    }
                                } else {
                                    selectedIds.remove(asset.id)
                                }
                            },
                            onDragSelectionEnd = {
                                dragSelectionVisitedIds.clear()
                            }
                        )
                    }
                }

                !hasMediaPermission -> {
                    GalleryPermissionMessage(
                        onGrantPermission = {
                            permissionLauncher.launch(galleryReadPermissions())
                        }
                    )
                }

                allFlatMedia.isEmpty() -> {
                    GallerySplashPlaceholder()
                }

                else -> {
                    MediaGrid(
                        context = context,
                        groupedMedia = groupedMedia,
                        selectedIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        thumbnailPositions = thumbnailPositions,
                        mediaById = mediaById,
                        thumbnailCache = thumbnailCache,
                        onThumbnailPositioned = { id, rect -> thumbnailPositions[id] = rect },
                        onMediaClick = { asset ->
                            if (isSelectionMode) {
                                if (selectedIds.contains(asset.id)) {
                                    selectedIds.remove(asset.id)
                                } else {
                                    selectedIds.add(asset.id)
                                }
                            } else {
                                selectedMediaIndex = allFlatMedia.indexOf(asset)
                            }
                        },
                        onMediaLongClick = { asset ->
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedIds.add(asset.id)
                            }
                        },
                        onDragSelectionStart = { asset ->
                            if (!isSelectionMode) {
                                isSelectionMode = true
                            }
                            dragSelectionVisitedIds.clear()
                            dragSelectionTargetSelected = !selectedIds.contains(asset.id)
                            if (dragSelectionTargetSelected) {
                                if (!selectedIds.contains(asset.id)) {
                                    selectedIds.add(asset.id)
                                }
                            } else {
                                selectedIds.remove(asset.id)
                            }
                            dragSelectionVisitedIds.add(asset.id)
                        },
                        onDragSelectionItem = { asset ->
                            if (!dragSelectionVisitedIds.add(asset.id)) {
                                return@MediaGrid
                            }
                            if (dragSelectionTargetSelected) {
                                if (!selectedIds.contains(asset.id)) {
                                    selectedIds.add(asset.id)
                                }
                            } else {
                                selectedIds.remove(asset.id)
                            }
                        },
                        onDragSelectionEnd = {
                            dragSelectionVisitedIds.clear()
                        },
                        onGroupTitleClick = { group ->
                            if (groupingMode == GroupingMode.PERSON) {
                                infoPersonId = group.titleValue.toLongOrNull()
                            }
                        },
                        personNameMap = personNameMap
                    )
                }
            }

            val activeMedia = selectedMediaIndex?.let { previewMediaList.getOrNull(it) }
            val rect by remember { derivedStateOf { activeMedia?.let { thumbnailPositions[it.id] } } }

            // 悬浮底部 Tab — 相册整理 / 聊天 / 打标 / 人物 / 回忆（纯图标）
            // 2026-08-26：相机 tab 移除（相机已路由化，仅头像拍摄进入）；第一项为相册整理——
            // 相册整理是相邻 Pager 页（页 1），相册页左滑即达，手势由外层 HorizontalPager 原生承载
            if (selectedMediaIndex == null) {
                val tabItems = listOf(
                    FloatingBottomTabItem(
                        icon = Icons.Outlined.BurstMode,
                        contentDescription = stringResource(R.string.gallery_cleanup),
                        onClick = onNavigateToDedupHome
                    ),
                    FloatingBottomTabItem(
                        icon = Icons.Outlined.ChatBubble,
                        contentDescription = stringResource(R.string.chat),
                        onClick = onNavigateToChat
                    ),
                    FloatingBottomTabItem(
                        icon = Icons.Outlined.Sell,
                        contentDescription = stringResource(R.string.tag_scan_control),
                        onClick = onNavigateToTagControl
                    ),
                    FloatingBottomTabItem(
                        icon = Icons.Outlined.AccountCircle,
                        contentDescription = stringResource(R.string.gallery_people_entry),
                        onClick = onNavigateToPeople
                    ),
                    FloatingBottomTabItem(
                        icon = Icons.Outlined.Collections,
                        contentDescription = stringResource(R.string.tab_memories),
                        onClick = onNavigateToMemory
                    )
                )
                FloatingBottomTab(
                    items = tabItems,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .navigationBarsPadding()
                )
            }

            AnimatedVisibility(
                visible = selectedMediaIndex != null,
                enter = fadeIn(animationSpec = tween(300)) + scaleIn(
                    initialScale = 0.2f,
                    transformOrigin = rect?.let {
                        TransformOrigin(
                            (it.center.x / 1080f).coerceIn(0f, 1f),
                            (it.center.y / 1920f).coerceIn(0f, 1f)
                        )
                    } ?: TransformOrigin.Center,
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(animationSpec = tween(300)) + scaleOut(
                    targetScale = 0.2f,
                    transformOrigin = rect?.let {
                        TransformOrigin(
                            (it.center.x / 1080f).coerceIn(0f, 1f),
                            (it.center.y / 1920f).coerceIn(0f, 1f)
                        )
                    } ?: TransformOrigin.Center,
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                )
            ) {
                if (selectedMediaIndex != null) {
                    MediaPager(
                        assets = previewMediaList,
                        initialIndex = selectedMediaIndex!!,
                        onTriggerSummary = viewModel::triggerSummaryOnDemand,
                        onClose = { selectedMediaIndex = null },
                        onDelete = { asset ->
                            viewModel.deleteMediaByIds(listOf(asset.id))
                            // 搜索状态下同步更新搜索结果列表
                            if (previewMediaList === searchResultMedia) {
                                searchResultMedia = searchResultMedia.filter { it.id != asset.id }
                            }
                            val newPreview = previewMediaList.filter { it.id != asset.id }
                            if (newPreview.isEmpty()) {
                                selectedMediaIndex = null
                            }
                        },
                        onStartOcr = { uriString ->
                            Logger.d(TAG, "Triggering OCR from Pager")
                            viewModel.recognizeTextFromCurrentImage(context, uriString.toUri())
                        },
                        onDismissOcr = {
                            viewModel.clearOcrResult()
                        },
                        ocrState = viewModel.ocrState,
                        onNavigateToEditor = { asset ->
                            navController.navigate(
                                Screen.PhotoEditor.createRoute(sourceUri = asset.uri),
                                navOptions { launchSingleTop = true }
                            )
                        },
                        onAiOptimize = { asset ->
                            navController.navigate(
                                Screen.PhotoEditor.createRoute(sourceUri = asset.uri, autoOptimize = true),
                                navOptions { launchSingleTop = true }
                            )
                        },
                        onIdPhoto = { asset ->
                            navController.navigate(
                                Screen.IDPhoto.createRoute(sourceUri = asset.uri),
                                navOptions { launchSingleTop = true }
                            )
                        },
                        onReTag = { uri ->
                            val resultJson = app.container.tagGenerationScheduler.processSingleSync(uri.toString())
                            if (resultJson != null) viewModel.refreshLabels()
                            resultJson
                        },
                        onDescribeImage = { uri ->
                            app.container.tagGenerationScheduler.describeImage(uri.toString())
                        },
                        onDetectLandmarks = { uri -> viewModel.detectFaceLandmarks(context, uri) },
                        debugUiEnabled = debugUiEnabled
                    )
                }
            }
        }
    }

    // ── 人物信息编辑 overlay（点分组名打开，与人物页同一 PersonInfoScreen）──
    val infoSnap = infoSnapshot
    if (infoSnap != null) {
        PersonInfoScreen(
            person = infoSnap.person,
            relation = infoSnap.relation,
            cover = infoSnap.coverMedia?.let { media -> PersonCover(media.uri, media.faceFocusY) },
            photos = infoSnap.photos,
            onSave = { relation, customLabel, isSelf ->
                kotlinx.coroutines.MainScope().launch {
                    runCatching {
                        app.container.personRepository.applyPersonEdit(
                            infoSnap.person.personId,
                            infoSnap.person.name.orEmpty(),
                            relation,
                            customLabel,
                            isSelf
                        )
                    }.onSuccess { refreshPersonNameMap() }
                        .onFailure { Logger.e(TAG, "Failed to apply person edit", it) }
                }
            },
            onNavigateBack = { infoPersonId = null },
            // 相机角标：登记 pending 头像拍摄后导航到相机路由
            onCaptureAvatar = {
                AvatarCaptureController.begin(
                    AvatarCaptureTarget.Person(infoSnap.person.personId),
                    AvatarCaptureOrigin.GALLERY_PAGE
                )
                onNavigateToCamera()
            },
            onUpdateCover = { photo ->
                kotlinx.coroutines.MainScope().launch {
                    runCatching {
                        app.container.personRepository.updateCover(infoSnap.person.personId, photo.id)
                    }.onSuccess { refreshPersonNameMap() }
                        .onFailure { Logger.e(TAG, "Failed to update cover", it) }
                }
            },
            onUpdateName = { name ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    kotlinx.coroutines.MainScope().launch {
                        runCatching {
                            app.container.personRepository.renamePerson(infoSnap.person.personId, trimmed)
                        }.onFailure { Logger.e(TAG, "Failed to rename person", it) }
                    }
                }
            },
            onRescore = {
                app.container.aestheticScoreWorker.runOnceForPerson(infoSnap.person.personId)
                runCatching { app.container.personRepository.loadPersonEditSnapshot(infoSnap.person.personId) }
                    .onSuccess { snapshot -> infoSnapshot = snapshot }
                    .onFailure { Logger.e(TAG, "Failed to reload person edit snapshot", it) }
                refreshPersonNameMap()
            }
        )
    }
    } // Box(Scaffold + 人物信息编辑 overlay)

    // ── 相册必须模型下载提示 ────────────────────────────
    val showGalleryRequiredModelsPrompt by settingsViewModel.showGalleryRequiredModelsPrompt.collectAsState()
    val isBatchDownloading by settingsViewModel.isBatchDownloading.collectAsState()
    if (showGalleryRequiredModelsPrompt) {
        AlertDialog(
            onDismissRequest = { if (!isBatchDownloading) settingsViewModel.dismissGalleryRequiredModelsPrompt() },
            title = {
                Text(text = stringResource(R.string.gallery_required_models_download_title))
            },
            text = {
                Text(text = stringResource(R.string.gallery_required_models_download_message))
            },
            confirmButton = {
                Button(
                    onClick = { settingsViewModel.startGalleryRequiredModelsDownload() },
                    enabled = !isBatchDownloading
                ) {
                    Text(
                        text = if (isBatchDownloading) {
                            stringResource(R.string.gallery_required_models_download_progress)
                        } else {
                            stringResource(R.string.gallery_required_models_download_button)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { settingsViewModel.dismissGalleryRequiredModelsPrompt() },
                    enabled = !isBatchDownloading
                ) {
                    Text(text = stringResource(R.string.gallery_required_models_download_later))
                }
            }
        )
    }
}
