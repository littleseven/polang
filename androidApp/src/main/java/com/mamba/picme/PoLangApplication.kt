package com.mamba.picme

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mamba.picme.BuildConfig
import com.mamba.picme.agent.core.inference.remote.tool.RemoteControlToolService
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.RenderEnvironment
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.diag.CrashTraceStore
import com.mamba.picme.data.indexing.geo.LocationIndexer
import com.mamba.picme.core.image.CoilConfig
import com.mamba.picme.core.image.ThumbnailCache
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.llmlog.RoomJsRunRecorder
import com.mamba.picme.data.local.llmlog.RoomLlmCallRecorder
import com.mamba.picme.data.local.llmlog.RoomRoutingAuditRecorder
import com.mamba.picme.data.local.llmlog.RoomToolCallRecorder
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.data.download.RecommendedModelAutoDownloader
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.di.AppContainer
import com.mamba.picme.di.AppContainerImpl
import com.mamba.picme.domain.memory.MemoryContextProviderImpl
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import com.mamba.picme.core.identity.DeviceIdProvider
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.AndroidAgentComposition
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.intent.IntentRouter
import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.agent.core.runtime.capability.CommandExecutor
import com.mamba.picme.agent.core.platform.logging.Logger as AgentCoreLogger
import com.mamba.picme.mnn.MnnResourceManager
import com.mamba.picme.domain.agent.capability.optimize.AiOptimizeCapability
import com.mamba.picme.features.chat.capability.ChatGallerySummaryCapability
import com.mamba.picme.features.chat.capability.ChatMediaWriteCapability
import com.mamba.picme.features.chat.capability.ChatRunScriptCapability
import com.mamba.picme.features.chat.capability.ChatSearchCapability
import com.mamba.picme.features.chat.capability.ChatStartTagScanCapability
import com.mamba.picme.features.chat.CARD_HORIZONTAL_CHROME_DP
import com.mamba.picme.features.chat.CARD_MAX_HEIGHT_FRACTION
import com.mamba.picme.features.chat.HtmlCardDisplay
import com.mamba.picme.features.settings.capability.SettingsCapability
import com.mamba.picme.features.gallery.capability.GalleryCapability
// 其他页面级 Capability 由各 Screen 自行创建
import com.mamba.picme.domain.agent.remote.FeishuChannelHandler
import com.mamba.picme.domain.agent.remote.RemoteChannelManager
import com.mamba.picme.domain.agent.remote.RemoteCommandDispatcher
import com.mamba.picme.domain.agent.remote.RemotePhotoTracker
import com.mamba.picme.domain.agent.remote.TelegramChannelHandler
import com.mamba.picme.domain.model.RemoteChannelType
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.mamba.picme.beauty.log.BeautyLogProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.io.File
import java.util.UUID

class PoLangApplication : Application(), ImageLoaderFactory {

    companion object {
        private const val TAG = "Application"

        /** 端侧 VLM 打标模型（configure 的 modelId 仅作 localModelService 默认模型）。 */
        private const val TAGGER_MODEL_ID = "qwen3_vl_2b"

        /** 远程拍照回传：consume pending token 后等媒体流稳定的时长（连拍落盘异步）。 */
        private const val REMOTE_PHOTO_SETTLE_MS = 4_000L

        /** 远程拍照回传：按 captureDate 收集本轮照片的窗口（排除历史远程照片）。 */
        private const val REMOTE_PHOTO_WINDOW_MS = 120_000L

        /** 远程拍照回传：单次最多回传张数（防异常刷屏）。 */
        private const val MAX_REMOTE_PHOTOS_PER_REPLY = 10

        /**
         * 推荐模型预下载冷启动错峰延迟：避开 Application.onCreate 主线程峰值。
         * 冷启动期立即 startForegroundService 会因主线程被初始化占满 → Service.onCreate
         * 内 startForeground 超时 → ForegroundServiceDidNotStartInTimeException。延迟到主线程空闲后触发。
         */
        private const val RECOMMENDED_AUTO_DOWNLOAD_STARTUP_DELAY_MS = 5_000L
    }

    val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var container: AppContainer
        private set

    /** 双级缩略图缓存：在 ImageLoader 创建前初始化，供 Coil Fetcher 和 Container 共用 */
    lateinit var thumbnailCache: ThumbnailCache
        private set

    val repository: MediaRepository
        get() = container.repository

    val feishuChannelHandler: FeishuChannelHandler by lazy { FeishuChannelHandler(applicationScope) }

    val telegramChannelHandler: TelegramChannelHandler by lazy { TelegramChannelHandler(applicationScope) }

    /** 单通道管理器：按 selectedRemoteChannel 激活飞书或 Telegram。 */
    val remoteChannelManager: RemoteChannelManager by lazy {
        RemoteChannelManager(feishuChannelHandler, telegramChannelHandler)
    }

    /** 推荐模型 WiFi 静默预下载器（依赖 container，故 lazy）。 */
    private val recommendedAutoDownloader: RecommendedModelAutoDownloader by lazy {
        RecommendedModelAutoDownloader(
            context = this,
            settings = container.userPreferencesRepository,
            downloader = container.llmModelDownloadManager
        )
    }

    val remoteCommandDispatcher: RemoteCommandDispatcher by lazy {
        val database = AppDatabase.getDatabase(this)
        RemoteCommandDispatcher(
            remoteChannelManager,
            this,
            database.chatMessageDao(),
            database.chatSessionDao()
        )
    }

    /**
     * 当前活跃的 Activity（用于测试截屏等场景）
     *
     * 通过 ActivityLifecycleCallbacks 自动跟踪，无需手动设置。
     */
    @Volatile
    var currentActivity: Activity? = null
        private set

    /**
     * 当前飞书消息处理 Job，用于新消息到达时取消旧任务
     * 防止多个 LLM 推理同时运行吃满 CPU 导致 ANR
     */
    @Volatile
    private var feishuDispatchJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        // A3：崩溃栈落盘（随下次远程诊断包上报 crashTrace）
        CrashTraceStore.install(this)

        // 显式指定 SLF4J Provider，绕过 SPI 扫描机制。
        // 必须在任何 SLF4J Logger 首次使用前设置，否则不生效。
        System.setProperty("slf4j.provider", "com.mamba.android.slf4j.AndroidSLF4JServiceProvider")

        // 预加载 Native 库（agent-core 模块依赖这些库，但 agent-core 不直接依赖 beauty-engine
        // 的 aar，因此需要在 Application 中统一加载，确保类加载器命名空间可见）
        loadNativeLibraries()

        thumbnailCache = ThumbnailCache(this)
        container = AppContainerImpl(this, thumbnailCache)

        // 用户任务适配器（spec §6）：注册表 + TAG 扫描/模型下载接入任务中心
        container.startUserTaskAdapters()

        // 注入媒体搜索引擎到 GalleryCapability（自然语言图片搜索）
        GalleryCapability.getInstance().searchEngine = container.mediaSearchEngine
        // 注入跨维度查询构建器（LLM 意图 → 多维度 Room 查询）
        GalleryCapability.getInstance().queryBuilder = container.queryBuilder

        // 初始化人脸关键点适配器注册表
        FaceLandmarkAdapterRegistry.initDefaults()

        // 绑定 Beauty Engine 日志代理，使 beauty-engine 模块的日志受 Logger 模块开关控制
        BeautyLogProxy.bindLogger(Logger)

        // 绑定 Agent Core 日志代理，使 agent-core 模块的日志受 Logger 模块开关控制
        AgentCoreLogger.setDelegate(object : AgentCoreLogger {
            override fun d(tag: String, message: String) = Logger.d(tag, message)
            override fun i(tag: String, message: String) = Logger.i(tag, message)
            override fun w(tag: String, message: String) = Logger.w(tag, message)
            override fun w(tag: String, message: String, throwable: Throwable) = Logger.w(tag, message, throwable)
            override fun e(tag: String, message: String, throwable: Throwable?) = Logger.e(tag, message, throwable)
            override fun isLogEnabled(tag: String): Boolean = Logger.isLogEnabled(tag)
        })

        // 从 DataStore 加载日志模块配置并同步到 Logger
        applicationScope.launch {
            val config = container.userPreferencesRepository.logModuleConfigFlow.first()
            Logger.setModuleConfig(config)
        }

        // Android 组合根：构建全部平台实现并注入 AgentOrchestrator（须早于一切 getInstance() 调用）
        AndroidAgentComposition.initialize(this)

        // 注册应用级 Capability（只注册一次，永不注销）
        initializeCapabilities()

        // 轻量位置索引 pass：读 EXIF GPS + 离线逆地理，替代废弃 MediaIndexingWorker 的位置职责。
        // 增量幂等（仅处理 locationName 为空），首启跑完后后续启动 no-op；不跑 OCR，不发热。
        applicationScope.launch {
            try {
                LocationIndexer(this@PoLangApplication).runPass()
            } catch (e: Exception) {
                Logger.w(TAG, "location index pass failed: ${e.message}")
            }
        }

        // 预配置 AgentOrchestrator 默认远程推理配置
        // gatewayToken 异步从 DataStore 加载（syncRemoteModelConfigToOrchestrator）
        // modelId 为端侧 VLM 打标模型（qwen3_vl_2b），仅作 localModelService 默认模型
        AgentOrchestrator.getInstance().configure(
            mode = AiAgentMode.REMOTE,
            modelId = "qwen3_vl_2b",
            privacyLevel = AiAgentPrivacyLevel.STRICT,
            remoteConfig = RemoteModelConfig.PICME_SERVER_DEFAULT
        )
        Logger.i(TAG, "Orchestrator pre-configured with fallback remote config")

        // 安装 LLM 调用 / tool 执行指标记录器（全构建注入）。
        // release 构建仅落纯指标（model/latency/tokens/success/errorMessage 等），
        // 绝不记录消息内容（隐私红线）；DEBUG 构建额外记录 request/response 全文。
        // :shared 的 Koog agent（KoogChatAgent/KoogReActAgent）在每次 LLM 调用完成时经
        // RemoteModelFactory.recorder 录制 LlmCallRecord，
        // CommandExecutor 汇聚全部 tool 执行并上报指标，均落库到独立 DB（polang_llm_log）。
        RemoteModelFactory.captureContent = BuildConfig.DEBUG
        RemoteModelFactory.recorder = RoomLlmCallRecorder(this)
        CommandExecutor.recorder = RoomToolCallRecorder(this)
        // Agent 终端运行感知层·端侧执行层：JS 沙盒运行事件（js_run_log）。
        // 与上面同一约定：release 仅落指标，DEBUG 额外记录脚本文本与结果预览。
        JsRuntime.captureContent = BuildConfig.DEBUG
        JsRuntime.recorder = RoomJsRunRecorder(this)
        // 路由层审计（spec《意图路由契约与意图路由器》§3.7 前半）：每回合路由判定
        //（含门控直通/降级）落 routing_audit_log，仅路由维度指标、不含用户 query 原文。
        IntentRouter.recorder = RoomRoutingAuditRecorder(this)
        Logger.i(TAG, "LLM/tool/js-run metrics recorder installed (captureContent=${BuildConfig.DEBUG})")

        // 注册 Activity 生命周期回调，跟踪当前活跃 Activity
        registerActivityLifecycleCallbacks(ActivityTracker())

        // 绑定消息处理回调：远程消息 → RemoteCommandDispatcher
        // 前一个推理任务未完成时自动取消，防止多个 LLM 线程吃满 CPU
        remoteChannelManager.onMessageReceived = { text, replyToken ->
            feishuDispatchJob?.cancel()
            feishuDispatchJob = applicationScope.launch {
                remoteCommandDispatcher.dispatch(text, replyToken)
            }
        }

        // 统一监听：通道选择 + 飞书凭据 + Telegram 凭据 → manager.activate
        // 首次发射即激活（开机按已存配置连接）；后续变化重新 activate（先断旧再连新）
        applicationScope.launch {
            try {
                val repo = container.userPreferencesRepository
                combine(
                    repo.selectedRemoteChannelFlow.map { RemoteChannelType.fromStored(it) },
                    repo.feishuAppIdFlow,
                    repo.feishuAppSecretFlow,
                    repo.telegramBotTokenFlow,
                    repo.telegramAllowedChatIdFlow
                ) { type, feishuAppId, feishuAppSecret, telegramBotToken, telegramChatId ->
                    ChannelSelection(type, feishuAppId, feishuAppSecret, telegramBotToken, telegramChatId)
                    // DataStore 任一无关 key 写入（如相机持久化预览比例）都会触发重放射；
                    // 值未变时必须跳过，否则 activate() 会无意义断开/重连 WS 通道
                }.distinctUntilChanged().collect { sel ->
                    remoteChannelManager.activate(
                        sel.type, sel.feishuAppId, sel.feishuAppSecret,
                        sel.telegramBotToken, sel.telegramChatId
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "远程通道激活监听失败", e)
            }
        }

        // 注册网络状态变化监听：网络恢复时自动重连飞书
        registerFeishuNetworkMonitor()

        // 同步 AI Agent 模式到 AgentOrchestrator，确保飞书远程控制
        // 的路由遵循用户在设置中选定的推理模式（REMOTE/OFF）
        syncAgentModeToOrchestrator()

        // 端侧文本 LLM（qwen3_5_2b）残留模型目录一次性清理（DataStore 标志位幂等）
        cleanupLegacyLocalTextLlm()

        // 同步远程模型配置到 AgentOrchestrator，确保用户修改 API Token 后即时生效
        syncRemoteModelConfigToOrchestrator()

        // 监听媒体库变化：飞书远程拍照完成后自动发送照片到飞书
        observeRemotePhotoCapture()

        // 推荐模型 WiFi 静默预下载：注册网络监听 + 启动时初始检查
        registerRecommendedAutoDownloadMonitor()
        // 错峰：冷启动立即触发会使 startForegroundService 的 onCreate 排在主线程峰值后 → startForeground 超时崩溃。
        applicationScope.launch {
            delay(RECOMMENDED_AUTO_DOWNLOAD_STARTUP_DELAY_MS)
            recommendedAutoDownloader.triggerIfEligible()
        }

        // 一次性清理已下线的 smolvlm_500m 模型目录（幂等：不存在则空操作）
        purgeSmolVlmIfFirstRun()
    }

    /**
     * 注册 WiFi 网络监听：WiFi 可用时静默预下载推荐模型。
     * 受 autoDownloadRecommendedOnWifi 开关与 downloader 内部 AtomicBoolean 防重入控制。
     */
    private fun registerRecommendedAutoDownloadMonitor() {
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // 网络恢复同样错峰，避免与冷启动峰值叠加。
                        applicationScope.launch {
                            delay(RECOMMENDED_AUTO_DOWNLOAD_STARTUP_DELAY_MS)
                            recommendedAutoDownloader.triggerIfEligible()
                        }
                    }
                }
            )
            Logger.i(TAG, "推荐模型 WiFi 预下载监听已注册")
        } catch (e: Exception) {
            Logger.e(TAG, "注册推荐模型预下载监听失败", e)
        }
    }

    /** 一次性清理已下线的 smolvlm_500m 模型目录（幂等：不存在则空操作）。 */
    private fun purgeSmolVlmIfFirstRun() {
        applicationScope.launch {
            runCatching {
                val dir = ModelPathConfig.getModelDir(this@PoLangApplication, "smolvlm_500m")
                if (dir.exists()) {
                    dir.deleteRecursively()
                    Logger.i(TAG, "已清理下线模型 smolvlm_500m（${dir.absolutePath}）")
                }
            }.onFailure { err -> Logger.w(TAG, "smolvlm 清理失败: ${err.message}") }
        }
    }

    /**
     * 注册网络状态监听，网络恢复时自动重连飞书通道
     */
    private fun registerFeishuNetworkMonitor() {
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Logger.w(TAG, "无法获取 ConnectivityManager")
                return
            }

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                private var wasUnavailable = true

                override fun onAvailable(network: Network) {
                    if (wasUnavailable) {
                        wasUnavailable = false
                        Logger.i(TAG, "网络恢复可用，触发飞书重连")
                        // 延迟 2 秒等待网络稳定后再重连
                        applicationScope.launch {
                            delay(2000)
                            remoteChannelManager.reconnect()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    wasUnavailable = true
                    Logger.w(TAG, "网络连接丢失")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    val hasInternet = capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                    if (hasInternet && wasUnavailable) {
                        wasUnavailable = false
                        Logger.i(TAG, "网络能力恢复，触发飞书重连")
                        applicationScope.launch {
                            delay(2000)
                            remoteChannelManager.reconnect()
                        }
                    }
                }
            }

            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
            Logger.i(TAG, "网络状态监听已注册")
        } catch (e: Exception) {
            Logger.e(TAG, "注册网络状态监听失败", e)
        }
    }

    /**
     * 同步 AI Agent 模式到 AgentOrchestrator
     *
     * **为什么需要这个方法**：
     * - [AgentOrchestrator] 是单例，其内部 [AgentConfigurator.agentMode] 默认值为 [AiAgentMode.REMOTE]
     * - [RemoteCommandDispatcher] 直接使用此单例，但从不调用 [AgentOrchestrator.configure]
     * - 如果用户在设置中切换了推理模式，而飞书路径没有收到通知，
     *   飞书消息会继续走旧的模式（或默认 REMOTE），与用户期望不符
     * - 此方法在启动时读取用户的设置，并在设置变化时自动同步
     *
     * 端侧文本 LLM 移除后，modelId 固定为端侧 VLM 打标模型（qwen3_vl_2b），
     * 仅作 localModelService.ensureModelLoaded 的默认模型；推理路由不再有本地/远程偏好。
     */
    private fun syncAgentModeToOrchestrator() {
        applicationScope.launch {
            try {
                val repository = container.userPreferencesRepository
                combine(
                    repository.aiAgentModeFlow,
                    repository.aiAgentPrivacyLevelFlow
                ) { mode, privacyLevel ->
                    SyncConfig(mode, privacyLevel)
                    // distinctUntilChanged：无关 DataStore 写入会触发重放射，
                    // 值未变时跳过重配，避免打断正在运行的 agent 任务
                }.distinctUntilChanged().collect { (mode, privacyLevel) ->
                    val orchestrator = AgentOrchestrator.getInstance()
                    // 只同步 mode 相关参数，remoteConfig 由 syncRemoteModelConfigToOrchestrator 独立管理
                    // 避免两个 flow 竞态时 gatewayToken 被空值覆盖
                    orchestrator.configure(
                        mode = mode,
                        modelId = TAGGER_MODEL_ID,
                        privacyLevel = privacyLevel,
                        remoteConfig = null
                    )
                    Logger.i(TAG, "Agent orchestrator synced: mode=$mode, model=$TAGGER_MODEL_ID")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Agent mode sync failed", e)
            }
        }
    }

    /**
     * 端侧文本 LLM（qwen3_5_2b）残留模型目录一次性清理。
     *
     * 仅删除 `filesDir/llm_models/qwen3_5_2b/` 子目录（同目录其他模型不动），
     * DataStore 标志位保证只执行一次；任何异常吞掉记日志，不影响启动。
     */
    private fun cleanupLegacyLocalTextLlm() {
        applicationScope.launch {
            try {
                val repository = container.userPreferencesRepository
                val alreadyCleaned = repository.localTextLlmCleanedFlow.first()
                if (alreadyCleaned) return@launch
                val legacyDir = java.io.File(filesDir, "llm_models/qwen3_5_2b")
                if (legacyDir.exists()) {
                    val deleted = legacyDir.deleteRecursively()
                    Logger.i(TAG, "Legacy local text LLM dir removed: ${legacyDir.absolutePath}, deleted=$deleted")
                }
                repository.markLocalTextLlmCleaned()
            } catch (e: Exception) {
                Logger.w(TAG, "Legacy local text LLM cleanup failed (will retry next launch): ${e.message}")
            }
        }
    }

    /**
     * 同步远程模型配置到 AgentOrchestrator
     *
     * 当用户在设置中添加/修改/删除远程模型配置时，
     * 自动解析并同步到 AgentOrchestrator，确保远程推理使用最新配置。
     *
     * **注意**：DataStore `ai_agent_remote_model_configs_v2` 存储的是
     * [RemoteModelConfigs] JSON（字段 `providerId`，由设置页 RemoteModelsListSection 写入）；
     * `ai_agent_selected_remote_model` 存的是选中项的 [RemoteModelConfig.uniqueKey]
     * （"providerId:modelId"）。此处解析格式必须与写入端一致——历史上误用
     * ProviderConfigs.fromJson（找 `provider` 字段）导致 BYOK 配置解析为空、恒落服务端代理。
     */
    private val deviceIdProvider = DeviceIdProvider(this)

    private fun syncRemoteModelConfigToOrchestrator() {
        applicationScope.launch {
            try {
                val repository = container.userPreferencesRepository
                combine(
                    repository.aiAgentRemoteModelConfigsFlow,
                    repository.aiAgentSelectedRemoteModelFlow,
                    repository.serverAuthTokenFlow
                ) { configsJson, selectedModelId, serverToken ->
                    Triple(configsJson, selectedModelId, serverToken)
                    // distinctUntilChanged：无关 DataStore 写入（如相机持久化预览比例）会触发
                    // 重放射；值未变时跳过——此前每次重放射都无条件 clearFeishuAgent()，
                    // 会把正在执行多轮工具调用的飞书 agent 直接 shutdown（"Task cancelled"）
                }.distinctUntilChanged().collect { (configsJson, selectedModelId, serverToken) ->
                    val orchestrator = AgentOrchestrator.getInstance()
                    // deviceId 独立注入 AgentConfigurator，不受后续 remoteConfig 覆盖影响（访客试用 X-Device-Id）
                    orchestrator.setDeviceId(deviceIdProvider.get())

                    val remoteConfigs = RemoteModelConfigs.fromJson(configsJson)
                    // selectedModelId 实为 uniqueKey（"providerId:modelId"），兼容按 modelId 匹配旧值
                    val selectedRemoteConfig = remoteConfigs.getConfig(selectedModelId)
                        ?.takeIf { it.isConfigured }
                        ?: remoteConfigs.getConfigByModelId(selectedModelId)
                            ?.takeIf { it.isConfigured }
                        ?: remoteConfigs.configs.firstOrNull { it.isConfigured }

                    if (selectedRemoteConfig != null) {
                        // BYOK 模式：用户配置了自己的 API Key，直连 provider
                        orchestrator.updateRemoteRuntimeConfig(
                            remoteConfig = selectedRemoteConfig
                        )
                        orchestrator.clearFeishuAgent()
                        Logger.i(TAG, "Remote model config synced: model=${selectedRemoteConfig.modelId}, provider=${selectedRemoteConfig.providerId}")
                    } else {
                        // 存量旧 ProviderConfigs 格式数据（{"provider":...}）经 RemoteModelConfigs
                        // 解析会得到 baseUrl 为空的配置行 → isConfigured=false → 静默落代理。
                        // 打 W 日志区分「没配过」与「配置格式不识别」，便于用户支持定位。
                        if (configsJson.isNotBlank() && remoteConfigs.configs.isNotEmpty()) {
                            Logger.w(TAG, "Stored remote model configs not usable (legacy/unknown format?), falling back to server proxy")
                        }
                        // 服务端代理模式：使用邮箱注册的 token 认证
                        val remoteConfig = RemoteModelConfig.PICME_SERVER_DEFAULT.copy(
                            gatewayToken = serverToken,
                            deviceId = deviceIdProvider.get(),
                        )
                        orchestrator.updateRemoteRuntimeConfig(
                            remoteConfig = remoteConfig
                        )
                        orchestrator.clearFeishuAgent()
                        Logger.i(TAG, "Server proxy config synced: token=${if (serverToken.isNotBlank()) "set" else "empty"}")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Remote model config sync failed", e)
            }
        }
    }

    /** 已回传的远程照片最大 captureDate（观察者去重水位线，防连拍多轮触发重复回传）。 */
    @Volatile
    private var lastRemotePhotoSentCaptureDate = 0L

    /**
     * 监听媒体库变化，远程拍照完成后自动经激活通道发送照片。
     *
     * 当 [RemotePhotoTracker] 标记了 pending capture 且照片保存到媒体库后，
     * 按激活通道的来源标签（feishu_remote / telegram_remote）过滤新照片：
     * 1. 写入对应会话的聊天记录（agent_image 类型）
     * 2. 经 RemoteChannelManager 发送图片到激活通道
     */
    private fun observeRemotePhotoCapture() {
        applicationScope.launch {
            try {
                val chatMessageDao = AppDatabase.getDatabase(this@PoLangApplication).chatMessageDao()
                val chatSessionDao = AppDatabase.getDatabase(this@PoLangApplication).chatSessionDao()

                repository.allMedia.collect { mediaList ->
                    val sourceTag = remoteChannelManager.activeSourceTag
                    if (sourceTag.isBlank()) return@collect

                    val remotePhotos = mediaList.filter { it.source == sourceTag && it.type == MediaType.PHOTO }
                    if (remotePhotos.isEmpty()) return@collect

                    val pendingReplyToken = RemotePhotoTracker.consumePendingReplyToken()
                    if (pendingReplyToken == null) {
                        Logger.d(TAG, "无 pending replyToken，跳过发送（可能已处理或非远程触发）")
                        return@collect
                    }

                    val sessionId = remoteChannelManager.channelId.ifBlank { "remote" }

                    // 连拍回传：capture 与落盘异步，等媒体流稳定后按 captureDate 窗口收集
                    // 本轮全部远程照片（排除历史远程照片），逐张回传。
                    // captureDate > lastRemotePhotoSentCaptureDate 去重：连拍时每次 capture
                    // 工具调用都会重新 arm token，观察者会被多次触发；窗口收集不含已发照片，
                    // 否则同一张照片会重复回传（3 拍收 5 张即此问题）
                    delay(REMOTE_PHOTO_SETTLE_MS)
                    val windowStart = System.currentTimeMillis() - REMOTE_PHOTO_WINDOW_MS
                    val photosToSend = repository.allMedia.first()
                        .filter {
                            it.source == sourceTag && it.type == MediaType.PHOTO &&
                                it.captureDate >= windowStart && it.captureDate > lastRemotePhotoSentCaptureDate
                        }
                        .sortedBy { it.captureDate }
                        .takeLast(MAX_REMOTE_PHOTOS_PER_REPLY)
                    if (photosToSend.isEmpty()) {
                        Logger.w(TAG, "远程拍照回传：窗口内无新照片，跳过（session=$sessionId）")
                        return@collect
                    }
                    Logger.i(TAG, "检测到远程拍照结果: ${photosToSend.size} 张, session=$sessionId")
                    lastRemotePhotoSentCaptureDate = photosToSend.maxOf { it.captureDate }

                    photosToSend.forEach { photo ->
                        // 1. 写入聊天记录（agent_image 类型）
                        try {
                            if (chatSessionDao.getSession(sessionId) == null) {
                                chatSessionDao.insertSession(
                                    ChatSessionEntity(
                                        sessionId = sessionId,
                                        title = if (sessionId == "telegram") "Telegram 远程控制" else "飞书远程控制"
                                    )
                                )
                            }
                            chatMessageDao.insertMessage(
                                ChatMessageEntity(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    type = "agent_image",
                                    content = photo.uri,
                                    modelUsed = sourceTag
                                )
                            )
                            chatSessionDao.touchSession(sessionId)
                        } catch (e: Exception) {
                            Logger.e(TAG, "写入远程聊天记录失败", e)
                        }

                        // 2. 经激活通道发送图片（压缩到 2K）
                        try {
                            val uri = android.net.Uri.parse(photo.uri)
                            val compressedBytes = compressImageForFeishu(uri, 2048, 85)
                            if (compressedBytes != null) {
                                remoteChannelManager.sendImage(compressedBytes, pendingReplyToken)
                                Logger.i(TAG, "远程拍照结果已发送: session=$sessionId, size=${compressedBytes.size / 1024}KB")
                            } else {
                                Logger.w(TAG, "图片压缩失败，尝试发送原图: ${photo.uri}")
                                val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                                if (parcelFileDescriptor != null) {
                                    val imageBytes = java.io.FileInputStream(parcelFileDescriptor.fileDescriptor).use { it.readBytes() }
                                    parcelFileDescriptor.close()
                                    remoteChannelManager.sendImage(imageBytes, pendingReplyToken)
                                }
                            }
                        } catch (e: Exception) {
                            Logger.e(TAG, "发送远程照片失败", e)
                        }
                    }
                    remoteChannelManager.sendMessage(
                        if (photosToSend.size > 1) "✅ ${photosToSend.size} 张照片已发送，请查收" else "✅ 照片已发送，请查收",
                        pendingReplyToken
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "远程拍照监听启动失败", e)
            }
        }
    }

    /**
     * Activity 生命周期跟踪器
     *
     * 用于测试框架获取当前前台 Activity 进行截屏等操作，
     * 同时联动 MnnResourceManager 实现应用级前后台状态感知。
     */
    private inner class ActivityTracker : ActivityLifecycleCallbacks {
        private var activityCount = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {
            if (activityCount == 0) {
                Logger.i(TAG, "App 回到前台")
                MnnResourceManager.getInstance(this@PoLangApplication).onAppForeground()
                // App 回到前台时检查飞书连接，断开则自动重连
                applicationScope.launch {
                    delay(1000) // 等待系统稳定
                    remoteChannelManager.reconnect()
                }
            }
            activityCount++
        }
        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
            RemoteControlToolService.currentActivity = activity
            Logger.d(TAG, "Activity resumed: ${activity.javaClass.simpleName}")
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivity == activity) {
                currentActivity = null
            }
            // 同步清理 RemoteControlToolService 的静态引用，防止已暂停 Activity 泄漏
            if (RemoteControlToolService.currentActivity === activity) {
                RemoteControlToolService.currentActivity = null
            }
        }
        override fun onActivityStopped(activity: Activity) {
            activityCount--
            if (activityCount == 0) {
                MnnResourceManager.getInstance(this@PoLangApplication).onAppBackground()
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            // Activity 销毁时清理引用
            // NavigationCapability 现在由 MainActivity 持有，随 Activity 自动释放
            if (currentActivity == activity) {
                currentActivity = null
            }
            // 同步清理 RemoteControlToolService 的静态引用，防止已销毁 Activity 泄漏
            if (RemoteControlToolService.currentActivity === activity) {
                RemoteControlToolService.currentActivity = null
            }
        }
    }

    /**
     * 初始化应用级 Capability（2026-07-29 单轨收敛：CapabilityRegistry 为唯一注册表，
     * Compose CapabilityHost 已退役）。
     *
     * - 应用级单例 Capability：此处注册一次、永不注销；可用性由 delegate 绑定状态决定
     * - 页面级 Capability（CameraCapability）：随 Screen 进入注册、退出注销（见 CameraScreen）
     * - GalleryCapability 注册为全局是为了支持后台飞书指令直接搜索
     * - SettingsCapability 此前从未注册（死能力，chat 的 change_theme 等工具永远
     *   METHOD_NOT_FOUND），本版本起注册生效
     */
    private fun initializeCapabilities() {
        Logger.i(TAG, "Capability lifecycle: single registry (app-scoped + page-scoped Camera)")
        Logger.i(TAG, "- NavigationCapability: Activity-scoped (MainActivity)")
        Logger.i(TAG, "- CameraCapability: Page-scoped (CameraScreen register/unregister)")
        Logger.i(TAG, "- GalleryCapability: Application-scoped (Feishu background search)")
        Logger.i(TAG, "- AiOptimizeCapability: Application-scoped")

        val orchestrator = AgentOrchestrator.getInstance()
        orchestrator.registerCapability(GalleryCapability.getInstance())
        orchestrator.registerCapability(SettingsCapability.getInstance())
        Logger.i(TAG, "- SettingsCapability: SETTINGS-scoped (change_theme/language 等，补注册)")
        orchestrator.registerCapability(ChatSearchCapability.getInstance())
        Logger.i(TAG, "- ChatSearchCapability: CHAT-scoped gallery search")
        orchestrator.registerCapability(ChatGallerySummaryCapability.getInstance())
        orchestrator.registerCapability(ChatRunScriptCapability.getInstance())
        orchestrator.registerCapability(ChatStartTagScanCapability.getInstance())
        orchestrator.registerCapability(ChatMediaWriteCapability.getInstance())
        Logger.i(TAG, "- Chat summary/script/tag-scan/media-write: CHAT-scoped")
        orchestrator.registerCapability(
            AiOptimizeCapability(
                context = this,
                optimizeUseCase = container.aiOptimizeUseCase
            )
        )
        orchestrator.registerCapability(container.imageEditCapability)
        Logger.i(TAG, "- ImageEditCapability: CHAT-scoped image editing")
        orchestrator.registerCapability(container.personRelationCapability)
        Logger.i(TAG, "- PersonRelationCapability: CHAT-scoped person relation declaration")
        orchestrator.registerCapability(container.memoryCapability)
        Logger.i(TAG, "- MemoryCapability: CHAT-scoped fact memory (chat tool + JS dispatch)")
        val memoryContextProvider = MemoryContextProviderImpl(
            memoryRepository = container.memoryRepository,
            personRepository = container.personRepository,
            scope = applicationScope
        )
        orchestrator.setMemoryContextProvider(memoryContextProvider)
        Logger.i(TAG, "- MemoryContextProvider: injected (chat + feishu passive memory)")
        // render_html 排版上下文：设备/屏幕/卡片宽高 → chat system prompt 动态尾段
        orchestrator.setRenderEnvironmentProvider { buildRenderEnvironment() }
        Logger.i(TAG, "- RenderEnvironmentProvider: injected (html card layout context)")
    }

    /** 构建 HTML 卡片渲染环境（CSS px ≈ dp：渲染层已注入 viewport meta width=device-width）。 */
    private fun buildRenderEnvironment(): RenderEnvironment {
        val dm = resources.displayMetrics
        val widthDp = (dm.widthPixels / dm.density).roundToInt()
        val heightDp = (dm.heightPixels / dm.density).roundToInt()
        return RenderEnvironment(
            deviceType = "Android 手机",
            screenWidthDp = widthDp,
            screenHeightDp = heightDp,
            screenWidthPx = dm.widthPixels,
            screenHeightPx = dm.heightPixels,
            cardContentWidthCssPx = widthDp - CARD_HORIZONTAL_CHROME_DP,
            cardSuggestedHeightCssPx = (heightDp * CARD_MAX_HEIGHT_FRACTION).roundToInt(),
            previewCardHeightCssPx = (heightDp * HtmlCardDisplay.PREVIEW_HEIGHT_FRACTION).roundToInt(),
            fullpageThresholdCssPx = (heightDp * HtmlCardDisplay.FULLPAGE_THRESHOLD_FRACTION).roundToInt()
        )
    }

    override fun newImageLoader(): ImageLoader {
        return CoilConfig.createImageLoader(this, thumbnailCache)
    }

    /**
     * 压缩图片用于飞书发送
     * 将图片缩放到指定最大边长，并以 JPEG 格式压缩
     *
     * @param uri 图片 URI
     * @param maxDimension 最大边长（像素）
     * @param quality JPEG 压缩质量（0-100）
     * @return 压缩后的图片字节数组，失败返回 null
     */
    private fun compressImageForFeishu(uri: android.net.Uri, maxDimension: Int, quality: Int): ByteArray? {
        return try {
            // 1. 解码图片尺寸
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }

            val (width, height) = options.outWidth to options.outHeight
            if (width <= 0 || height <= 0) {
                Logger.w(TAG, "无法获取图片尺寸: $uri")
                return null
            }

            // 2. 计算采样率
            val scaleFactor = if (width > height) {
                width.toFloat() / maxDimension
            } else {
                height.toFloat() / maxDimension
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = if (scaleFactor > 1) {
                    kotlin.math.max(1, scaleFactor.toInt())
                } else {
                    1
                }
            }

            // 3. 解码图片
            val bitmap = contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            // 4. 精确缩放到目标尺寸
            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = kotlin.math.min(
                    maxDimension.toFloat() / bitmap.width,
                    maxDimension.toFloat() / bitmap.height
                )
                val newWidth = (bitmap.width * ratio).toInt()
                val newHeight = (bitmap.height * ratio).toInt()
                android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            // 5. 压缩为 JPEG
            val outputStream = java.io.ByteArrayOutputStream()
            val compressed = scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
            val bytes = outputStream.toByteArray()
            outputStream.close()

            if (!scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }

            if (compressed) {
                Logger.i(TAG, "图片压缩成功: ${width}x${height} -> ${bytes.size / 1024}KB")
                bytes
            } else {
                Logger.w(TAG, "Bitmap.compress 返回 false")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "图片压缩失败", e)
            null
        }
    }

    /**
     * 预加载 Native 共享库
     *
     * sherpa-onnx-jni.so 由 SherpaOnnxAsrEngine / KeywordSpotterEngine 通过
     * System.loadLibrary 加载（Sherpa 语音栈完全基于 ONNX Runtime）。
     * libMNN.so 等 MNN 运行时由 libagent_native（LLM JNI）自动链接加载。
     * 在 Application 中提前加载可确保所有依赖 so 在类加载器命名空间中可见。
     */
    private fun loadNativeLibraries() {
        // 用绝对路径预加载 APK 里的 ICD Loader，让 MNN dlopen 时直接命中跳过搜索
        try {
            val apkLibDir = applicationInfo.nativeLibraryDir
            System.load("$apkLibDir/libOpenCL.so")
            Logger.d(TAG, "OpenCL ICD Loader preloaded")
        } catch (e: UnsatisfiedLinkError) {
            Logger.d(TAG, "OpenCL ICD Loader preload skipped: ${e.message}")
        }
        try {
            System.loadLibrary("sherpa-onnx-jni")
            Logger.d(TAG, "Native library loaded: sherpa-onnx-jni")
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "Failed to load sherpa-onnx-jni", e)
        }
        // 预加载 SentencePiece tokenizer（OPUS-MT 编码解码依赖）
        try {
            System.loadLibrary("sentencepiece_android")
            Logger.d(TAG, "Native library loaded: sentencepiece_android")
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "Failed to load sentencepiece_android", e)
        }
    }

    /**
     * syncAgentModeToOrchestrator 内部使用的同步参数容器
     */
    private data class SyncConfig(
        val mode: AiAgentMode,
        val privacyLevel: AiAgentPrivacyLevel
    )

    /** 通道激活参数容器（combine 5 路流后传给 RemoteChannelManager.activate）。 */
    private data class ChannelSelection(
        val type: RemoteChannelType,
        val feishuAppId: String,
        val feishuAppSecret: String,
        val telegramBotToken: String,
        val telegramChatId: String
    )
}
