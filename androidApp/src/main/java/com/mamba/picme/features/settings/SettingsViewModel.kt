package com.mamba.picme.features.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceDetector
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.common.NetworkUtils
import com.mamba.picme.data.download.DownloadState
import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.data.download.LlmModelDownloadManager
import com.mamba.picme.data.download.ModelConfig
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.DetectionModelType
import com.mamba.picme.domain.model.DetectionStage
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.domain.model.InferenceDevicePreference
import com.mamba.picme.domain.model.InferenceEngineType
import com.mamba.picme.domain.model.LogModule
import com.mamba.picme.domain.model.LogModuleConfig
import com.mamba.picme.domain.model.ModelCategory
import com.mamba.picme.domain.model.StageConfig
import com.mamba.picme.domain.model.TagTranslations
import com.mamba.picme.domain.model.ThemeMode
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.tag.TaggerModelSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repository: UserSettingsRepository,
    private val modelDownloadManager: LlmModelDownloadManager,
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "Settings"

        /**
         * Tier 1：相册扫描/创建 TAG 必须的模型（最高优先）。
         * 单一事实来源：派生自 [ModelConfig.REQUIRED_MODEL_IDS]。
         * 进入相册且自动扫描任务启动前必须全部已下载，否则弹出下载提醒。
         */
        val GALLERY_REQUIRED_MODEL_IDS: List<String> =
            ModelConfig.REQUIRED_MODEL_IDS.toList()

        /**
         * Tier 2：聊天/语音输入相关模型（次高优先）。
         * 已从相册必须列表中移出，仅在聊天页提醒下载。
         * 端侧文本 LLM（qwen3_5_2b）已移除，聊天改走远程推理；仅保留 ASR 语音输入。
         * KWS 唤醒模型已拆出为可选（语音为非刚需），仅在设置「语音控制」区块按需下载。
         */
        val CHAT_REQUIRED_MODEL_IDS = listOf(
            "sherpa-onnx-zipformer-zh-en" // ASR 语音输入
        )
    }

    val themeMode: StateFlow<ThemeMode> = repository.themeModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM
        )

    val appLanguage: StateFlow<AppLanguage> = repository.appLanguageFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppLanguage.SYSTEM
        )



    val debugUiEnabled: StateFlow<Boolean> = repository.debugUiEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** 开发者选项入口是否已解锁（版本号连点解锁后持久化）。 */
    val developerOptionsUnlocked: StateFlow<Boolean> = repository.developerOptionsUnlockedFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val faceDetectionEngineMode: StateFlow<FaceDetectionEngineMode> = repository.faceDetectionEngineModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FaceDetectionEngineMode.MEDIAPIPE
        )

    val faceDetectionLandmarkModeEnabled: StateFlow<Boolean> = repository.faceDetectionLandmarkModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val adaptiveFaceDetectionIntervalEnabled: StateFlow<Boolean> = repository.adaptiveFaceDetectionIntervalEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val faceDetectIntervalProfile: StateFlow<FaceDetectIntervalProfile> = repository.faceDetectIntervalProfileFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FaceDetectIntervalProfile.BALANCED
        )

    val showCameraInfoInPreview: StateFlow<Boolean> = repository.showCameraInfoInPreviewFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val showFaceDebugOverlay: StateFlow<Boolean> = repository.showFaceDebugOverlayFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val showLogOverlay: StateFlow<Boolean> = repository.showLogOverlayFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Shader debug mode (not persisted, defaults to 0)
    private val _debugShaderMode = MutableStateFlow(0)
    val debugShaderMode: StateFlow<Int> = _debugShaderMode

    // ── 阶段独立配置（ROI / Landmark）────────────────────────
    val roiStageConfig: StateFlow<StageConfig> = repository.roiStageConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StageConfig.defaultRoi()
        )

    val landmarkStageConfig: StateFlow<StageConfig> = repository.landmarkStageConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StageConfig.defaultLandmark()
        )

    val aiAgentRemoteModelConfigs: StateFlow<String> = repository.aiAgentRemoteModelConfigsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val aiAgentSelectedRemoteModel: StateFlow<String> = repository.aiAgentSelectedRemoteModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "deepseek-v4-flash"
        )

    val autoExecutePlansEnabled: StateFlow<Boolean> = repository.autoExecutePlansEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    /** 允许 Agent 执行 JS 沙盒脚本（默认关）。仅持久化，能力层消费待接入。 */
    val jsEngineEnabled: StateFlow<Boolean> = repository.jsEngineEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** 允许 Agent 访问相机能力（默认开）。仅持久化，能力层消费待接入。 */
    val agentCameraAccessEnabled: StateFlow<Boolean> = repository.agentCameraAccessEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    /** 允许 Agent 访问相册能力（默认开）。仅持久化，能力层消费待接入。 */
    val agentGalleryAccessEnabled: StateFlow<Boolean> = repository.agentGalleryAccessEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val aiAgentMode: StateFlow<AiAgentMode> = repository.aiAgentModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiAgentMode.REMOTE
        )

    val assistantPersona: StateFlow<AssistantPersona> = repository.assistantPersonaFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AssistantPersona.DEFAULT
        )

    val aiAgentPrivacyLevel: StateFlow<AiAgentPrivacyLevel> = repository.aiAgentPrivacyLevelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiAgentPrivacyLevel.STRICT
        )

    val tagGenerationUseOpencl: StateFlow<Boolean> = repository.tagGenerationUseOpencl
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** WiFi 下静默预下载推荐模型（默认开启）。 */
    val autoDownloadRecommendedOnWifi: StateFlow<Boolean> = repository.autoDownloadRecommendedOnWifiFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    /** 相册打标模型 key（默认 AUTO → Florence-2 首选；显式 florence2_base / qwen3_vl_2b） */
    val taggerModelKey: StateFlow<String> = repository.taggerModelKeyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TaggerModelSelector.AUTO
        )

    val voiceCommandMode: StateFlow<VoiceCommandMode> = repository.voiceCommandModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VoiceCommandMode.DISABLED
        )

    /** 相机页语音入口（悬浮 FAB）显隐开关，默认关闭 */
    val voiceEntryEnabled: StateFlow<Boolean> = repository.voiceEntryEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** 相机页 AI 对话入口（悬浮 FAB）显隐开关，默认关闭 */
    val aiChatEntryEnabled: StateFlow<Boolean> = repository.aiChatEntryEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** 「删除不再询问」开关（MANAGE_MEDIA 静默回收站），默认关闭 */
    val mediaManageSilentTrashEnabled: StateFlow<Boolean> = repository.mediaManageSilentTrashFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val localAsrModel: StateFlow<String> = repository.localAsrModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val localKwsModel: StateFlow<String> = repository.localKwsModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )


    val logModuleConfig: StateFlow<LogModuleConfig> = repository.logModuleConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LogModuleConfig.default()
        )

    // 模型管理相关 Flow
    private val _allModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val allModels: StateFlow<List<ModelConfig>> = _allModels.asStateFlow()

    private val _downloadedModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val downloadedModels: StateFlow<List<ModelConfig>> = _downloadedModels.asStateFlow()

    private val _groupedModels = MutableStateFlow<Map<ModelCategory, List<ModelConfig>>>(emptyMap())
    val groupedModels: StateFlow<Map<ModelCategory, List<ModelConfig>>> = _groupedModels.asStateFlow()

    private val _tagTranslations = MutableStateFlow<TagTranslations>(emptyMap())
    val tagTranslations: StateFlow<TagTranslations> = _tagTranslations.asStateFlow()

    private val _categories = MutableStateFlow<List<ModelCategory>>(emptyList())
    val categories: StateFlow<List<ModelCategory>> = _categories.asStateFlow()

    private val _currentTab = MutableStateFlow(ModelCategory.ALL)
    val currentTab: StateFlow<ModelCategory> = _currentTab.asStateFlow()

    // ── 模型下载状态（共享给 UI 层实时监听）───────────────────
    val downloadStates: StateFlow<Map<String, DownloadState>> = modelDownloadManager.downloadStates

    // 模型 ID 到 DetectionModelType 的映射
    // Det10G 和 Det500M 都是 ROI 检测模型，共享 DET_500M_MNN 类型
    // isModelDownloaded 需检查所有映射 ID 以兼容两种模型
    private val modelIdToDetectionType = mapOf(
        "face-det-retina10g-mnn" to DetectionModelType.DET_500M_MNN,
        "face-det-retina500m-mnn" to DetectionModelType.DET_500M_MNN,
        "face-landmark-2d106-mnn" to DetectionModelType.FACE_2D106_MNN
    )

    /**
     * 仅在状态从非 COMPLETED 过渡到 COMPLETED 时触发自动切换，
     * 避免下载状态频繁更新导致重复切换（表现为 UI 选项来回跳动）。
     */
    private val lastDownloadStatuses = mutableMapOf<String, DownloadStatus>()

    // ── 必要模型一键下载 ────────────────────────────
    private val _showGalleryRequiredModelsPrompt = MutableStateFlow(false)
    val showGalleryRequiredModelsPrompt: StateFlow<Boolean> = _showGalleryRequiredModelsPrompt.asStateFlow()

    private val _showChatModelsPrompt = MutableStateFlow(false)
    val showChatModelsPrompt: StateFlow<Boolean> = _showChatModelsPrompt.asStateFlow()

    private val _isBatchDownloading = MutableStateFlow(false)
    val isBatchDownloading: StateFlow<Boolean> = _isBatchDownloading.asStateFlow()

    // 静默下载（WiFi 自动下载）正在处理的模型 ID 集合，用于网络切换时精准暂停
    private val _silentDownloadModelIds = MutableStateFlow<Set<String>>(emptySet())

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wasWifi = false

    /**
     * 检查相册扫描必须的 Tier 1 模型是否已全部下载。
     * 纯查询，不修改提示状态；返回 true 表示可以启动自动扫描。
     */
    suspend fun areGalleryRequiredModelsDownloaded(): Boolean {
        return withContext(Dispatchers.IO) {
            GALLERY_REQUIRED_MODEL_IDS.all { id ->
                modelDownloadManager.isModelDownloaded(id)
            }
        }
    }

    /**
     * 在蜂窝网络下检查相册扫描必须的 Tier 1 模型，缺失则弹窗提醒。
     * WiFi 场景由 [startSilentDownloadIfWifi] 静默处理，不弹窗。
     */
    suspend fun checkGalleryRequiredModelsOnCellular() {
        if (!NetworkUtils.isCellularConnected(appContext)) return
        if (_isBatchDownloading.value || _showGalleryRequiredModelsPrompt.value) return
        val missingAny = withContext(Dispatchers.IO) {
            GALLERY_REQUIRED_MODEL_IDS.any { id ->
                !modelDownloadManager.isModelDownloaded(id)
            }
        }
        if (missingAny) {
            Logger.i(TAG, "Gallery required models missing on cellular, showing download prompt")
            _showGalleryRequiredModelsPrompt.value = true
        }
    }

    /**
     * 在蜂窝网络下检查聊天/语音/本地 LLM 相关模型。
     * 仅当本地推理或语音功能已开启/调用且模型缺失时才弹窗。
     */
    suspend fun checkChatModelsOnCellular() {
        if (!NetworkUtils.isCellularConnected(appContext)) return
        if (_isBatchDownloading.value || _showChatModelsPrompt.value) return
        if (!isChatFeatureEnabled()) return
        val missingAny = withContext(Dispatchers.IO) {
            CHAT_REQUIRED_MODEL_IDS.any { id ->
                !modelDownloadManager.isModelDownloaded(id)
            }
        }
        if (missingAny) {
            Logger.i(TAG, "Chat models missing on cellular, showing download prompt")
            _showChatModelsPrompt.value = true
        }
    }

    /**
     * 当用户选择本地模型或启用语音等功能时调用，用于蜂窝网络下的提醒。
     */
    suspend fun checkChatModelsOnFeatureEnabled() {
        checkChatModelsOnCellular()
    }

    /**
     * 判断聊天相关功能（语音输入）是否已开启。
     * 端侧文本 LLM 已移除，聊天为远程推理，无需本地模型；仅语音输入需检查 ASR 模型。
     */
    private suspend fun isChatFeatureEnabled(): Boolean {
        val voiceMode = repository.voiceCommandModeFlow.first()
        return voiceMode != VoiceCommandMode.DISABLED
    }

    /**
     * 在 WiFi 环境下静默下载缺失的 Tier 1 + Tier 2 模型。
     * 进入应用时调用，非 WiFi 不执行。
     */
    fun startSilentDownloadIfWifi() {
        if (!NetworkUtils.isWifiConnected(appContext)) {
            Logger.i(TAG, "Not on WiFi, skipping silent model download")
            return
        }
        if (_isBatchDownloading.value) return
        viewModelScope.launch {
            val allModelIds = GALLERY_REQUIRED_MODEL_IDS + CHAT_REQUIRED_MODEL_IDS
            val missingAny = withContext(Dispatchers.IO) {
                allModelIds.any { id ->
                    !modelDownloadManager.isModelDownloaded(id)
                }
            }
            if (missingAny) {
                Logger.i(TAG, "Starting silent model download on WiFi")
                _silentDownloadModelIds.value = allModelIds.toSet()
                startBatchDownload(allModelIds, "wifi-silent")
            }
        }
    }

    /**
     * 一键下载相册扫描必须的 Tier 1 模型。
     */
    fun startGalleryRequiredModelsDownload() {
        startBatchDownload(GALLERY_REQUIRED_MODEL_IDS, "gallery")
    }

    /**
     * 一键下载聊天/语音/本地 LLM 相关的 Tier 2 模型。
     */
    fun startChatModelsDownload() {
        startBatchDownload(CHAT_REQUIRED_MODEL_IDS, "chat")
    }

    /**
     * 通用批量下载实现：按 [modelIds] 顺序依次下载缺失模型。
     */
    private fun startBatchDownload(modelIds: List<String>, logTag: String) {
        if (_isBatchDownloading.value) return
        _isBatchDownloading.value = true
        _showGalleryRequiredModelsPrompt.value = false
        _showChatModelsPrompt.value = false
        viewModelScope.launch {
            try {
                for (modelId in modelIds) {
                    if (!modelDownloadManager.isModelDownloaded(modelId)) {
                        val config = _allModels.value.find { it.id == modelId }
                        if (config != null) {
                            Logger.i(TAG, "Batch[$logTag]: downloading $modelId")
                            modelDownloadManager.enqueueDownload(modelId, config)
                            // 等待下载完成或失败
                            modelDownloadManager.downloadStates.first { states ->
                                states[modelId]?.status == DownloadStatus.COMPLETED ||
                                    states[modelId]?.status == DownloadStatus.FAILED
                            }
                        }
                    }
                }
                Logger.i(TAG, "Batch[$logTag] download complete")
            } catch (e: Exception) {
                Logger.e(TAG, "Batch[$logTag] download failed", e)
            } finally {
                _isBatchDownloading.value = false
                _silentDownloadModelIds.value = emptySet()
            }
        }
    }

    /**
     * 注册网络状态监听，用于静默下载在 WiFi->蜂窝时自动暂停，
     * 以及回到 WiFi 时自动恢复/补下载。
     */
    private fun registerSilentDownloadNetworkMonitor() {
        try {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            wasWifi = NetworkUtils.isWifiConnected(appContext)

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetworkChanged()
                override fun onLost(network: Network) = onNetworkChanged()
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) = onNetworkChanged()
            }

            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            Logger.i(TAG, "Silent download network monitor registered")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to register silent download network monitor", e)
        }
    }

    /**
     * 网络变化时处理静默下载的暂停/恢复。
     */
    private fun onNetworkChanged() {
        val nowWifi = NetworkUtils.isWifiConnected(appContext)
        val hadWifi = wasWifi
        wasWifi = nowWifi

        if (hadWifi && !nowWifi) {
            Logger.i(TAG, "Network left WiFi, pausing silent downloads")
            pauseSilentDownloads()
        } else if (!hadWifi && nowWifi) {
            Logger.i(TAG, "Network back to WiFi, resuming silent downloads")
            resumeSilentDownloadsIfNeeded()
            startSilentDownloadIfWifi()
        }
    }

    /**
     * 暂停当前静默下载集合中正在下载的模型。
     */
    private fun pauseSilentDownloads() {
        val silentIds = _silentDownloadModelIds.value
        if (silentIds.isEmpty()) return
        for (modelId in silentIds) {
            val state = modelDownloadManager.downloadStates.value[modelId]
            if (state?.status == DownloadStatus.DOWNLOADING) {
                Logger.i(TAG, "Pausing silent download: $modelId")
                modelDownloadManager.pauseDownload(modelId)
            }
        }
    }

    /**
     * 回到 WiFi 时恢复处于 PAUSED 状态的静默下载模型。
     */
    private fun resumeSilentDownloadsIfNeeded() {
        val silentIds = _silentDownloadModelIds.value
        if (silentIds.isEmpty()) return
        for (modelId in silentIds) {
            val state = modelDownloadManager.downloadStates.value[modelId]
            if (state?.status == DownloadStatus.PAUSED) {
                val config = _allModels.value.find { it.id == modelId }
                if (config != null) {
                    Logger.i(TAG, "Resuming silent download: $modelId")
                    modelDownloadManager.enqueueResume(modelId, config)
                }
            }
        }
    }

    /**
     * 关闭相册模型下载提示弹窗
     */
    fun dismissGalleryRequiredModelsPrompt() {
        _showGalleryRequiredModelsPrompt.value = false
    }

    /**
     * 关闭聊天模型下载提示弹窗
     */
    fun dismissChatModelsPrompt() {
        _showChatModelsPrompt.value = false
    }

    init {
        lastDownloadStatuses.putAll(downloadStates.value.mapValues { entry -> entry.value.status })
        loadModels()
        observeDownloadCompletion()
        registerSilentDownloadNetworkMonitor()
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let { callback ->
            try {
                val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager
                connectivityManager?.unregisterNetworkCallback(callback)
                Logger.i(TAG, "Silent download network monitor unregistered")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to unregister silent download network monitor", e)
            }
        }
    }

    /**
     * 监听下载完成事件，自动切换对应阶段的模型
     */
    private fun observeDownloadCompletion() {
        viewModelScope.launch {
            downloadStates.collect { states ->
                lastDownloadStatuses.keys.retainAll(states.keys)

                states.forEach { (modelId, state) ->
                    val previousStatus = lastDownloadStatuses[modelId]
                    val justCompleted = state.status == DownloadStatus.COMPLETED &&
                        previousStatus != DownloadStatus.COMPLETED

                    if (justCompleted) {
                        // 刷新已下载模型列表，确保 UI 计数（如必须模型缺失数）同步更新
                        _downloadedModels.value = modelDownloadManager.getDownloadedModels()

                        val modelType = modelIdToDetectionType[modelId]
                        if (modelType != null) {
                            when {
                                modelType.isRoiModel() && roiStageConfig.value.modelType != modelType -> {
                                    Logger.i("Settings", "Auto-switching ROI model to $modelType after download")
                                    setRoiModelType(modelType)
                                }
                                modelType.isLandmarkModel() && landmarkStageConfig.value.modelType != modelType -> {
                                    Logger.i("Settings", "Auto-switching Landmark model to $modelType after download")
                                    setLandmarkModelType(modelType)
                                }
                            }
                        }
                    }

                    lastDownloadStatuses[modelId] = state.status
                }
            }
        }
    }

    /**
     * 检查模型是否已下载（MediaPipe 视为始终已下载）
     *
     * 一个 DetectionModelType 可能对应多个模型 ID（如 Det10G 和 Det500M 都映射到 DET_500M_MNN），
     * 只要有任意一个模型已下载即为可用。
     */
    fun isModelDownloaded(modelType: DetectionModelType): Boolean {
        if (modelType == DetectionModelType.MEDIAPIPE) return true
        return modelIdToDetectionType
            .filter { it.value == modelType }
            .keys
            .any { modelDownloadManager.isModelDownloaded(it) }
    }

    /**
     * 根据 stage 获取对应的模型 ID
     *
     * 同一 DetectionModelType 可能对应 Det10G 和 Det500M 两种模型，
     * 优先返回已下载的模型；若都未下载，默认返回 500M。
     */
    fun getModelId(modelType: DetectionModelType, stage: DetectionStage): String? {
        return when (stage) {
            DetectionStage.ROI -> when (modelType) {
                DetectionModelType.DET_500M_MNN -> {
                    if (modelDownloadManager.isModelDownloaded("face-det-retina500m-mnn")) {
                        "face-det-retina500m-mnn"
                    } else if (modelDownloadManager.isModelDownloaded("face-det-retina10g-mnn")) {
                        "face-det-retina10g-mnn"
                    } else {
                        "face-det-retina500m-mnn"
                    }
                }
                else -> null
            }
            DetectionStage.LANDMARK -> when (modelType) {
                DetectionModelType.FACE_2D106_MNN -> "face-landmark-2d106-mnn"
                else -> null
            }
        }
    }

    /**
     * 触发模型下载
     */
    fun downloadModel(modelId: String, modelConfig: ModelConfig) {
        modelDownloadManager.enqueueDownload(modelId, modelConfig)
    }

    /**
     * 一键下载所有未下载的必须模型，按顺序加入下载队列
     */
    fun downloadAllRequiredModels() {
        viewModelScope.launch {
            val requiredModels = _allModels.value.filter { it.isRequired }
            val downloadedIds = modelDownloadManager.getDownloadedModels().map { it.id }.toSet()
            val missingModels = requiredModels.filter { it.id !in downloadedIds }
            Logger.i("Settings", "Batch downloading ${missingModels.size} required models")
            missingModels.forEach { model ->
                modelDownloadManager.enqueueDownload(model.id, model)
            }
        }
    }

    fun resumeModelDownload(modelId: String, modelConfig: ModelConfig) {
        modelDownloadManager.enqueueResume(modelId, modelConfig)
    }

    fun pauseModelDownload(modelId: String) {
        modelDownloadManager.pauseDownload(modelId)
    }

    fun cancelModelDownload(modelId: String) {
        modelDownloadManager.cancelDownload(modelId)
    }

    suspend fun deleteDownloadedModel(modelId: String): Boolean {
        return modelDownloadManager.deleteModel(modelId)
    }

    private fun loadModels() {
        viewModelScope.launch {
            try {
                val marketData = modelDownloadManager.loadMarketData()
                _allModels.value = marketData.models
                _tagTranslations.value = marketData.tagTranslations

                val grouped = marketData.groupByCategory()
                _groupedModels.value = grouped
                _categories.value = grouped.keys.toList()

                // 默认选中第一个分类
                if (_currentTab.value == ModelCategory.ALL && grouped.isNotEmpty()) {
                    _currentTab.value = grouped.keys.first()
                }

                val downloaded = modelDownloadManager.getDownloadedModels()
                _downloadedModels.value = downloaded

                Logger.i("Settings", "Loaded ${marketData.models.size} models, " +
                    "categories: ${grouped.keys.map { it.tag }}")
            } catch (e: Exception) {
                Logger.e("Settings", "Failed to load models", e)
            }
        }
    }

    /**
     * 切换 Tab
     */
    fun switchTab(tab: ModelCategory) {
        _currentTab.value = tab
    }

    /**
     * 获取当前 Tab 对应的模型列表
     */
    fun getCurrentTabModels(): List<ModelConfig> {
        return _groupedModels.value[_currentTab.value] ?: emptyList()
    }

    /**
     * 获取所有模型分类标签（用于 TabRow）
     * 返回 Map<分类标签, 中文翻译>
     */
    fun getModelTypeLabels(): Map<ModelCategory, String> {
        val translations = _tagTranslations.value
        return _categories.value.associateWith { category ->
            translations[category.tag] ?: category.tag
        }
    }

    /**
     * 刷新模型列表（强制从网络获取）
     */
    fun refreshModels() {
        viewModelScope.launch {
            try {
                val marketData = modelDownloadManager.refreshMarketData()
                _allModels.value = marketData.models
                _tagTranslations.value = marketData.tagTranslations

                val grouped = marketData.groupByCategory()
                _groupedModels.value = grouped
                _categories.value = grouped.keys.toList()

                if (_currentTab.value !in grouped.keys && grouped.isNotEmpty()) {
                    _currentTab.value = grouped.keys.first()
                }

                val downloaded = modelDownloadManager.getDownloadedModels()
                _downloadedModels.value = downloaded

                Logger.i("Settings", "Refreshed ${marketData.models.size} models")
            } catch (e: Exception) {
                Logger.e("Settings", "Failed to refresh models", e)
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            repository.updateThemeMode(mode)
        }
    }

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            repository.updateAppLanguage(language)
        }
    }



    fun setDebugUiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateDebugUiEnabled(enabled)
            // 关闭总开关时,同时关闭所有子选项
            if (!enabled) {
                repository.updateShowCameraInfoInPreview(false)
                repository.updateShowFaceDebugOverlay(false)
                repository.updateShowLogOverlay(false)
            }
        }
    }

    fun setDeveloperOptionsUnlocked(unlocked: Boolean) {
        viewModelScope.launch {
            repository.updateDeveloperOptionsUnlocked(unlocked)
        }
    }

    fun setFaceDetectionEngineMode(mode: FaceDetectionEngineMode) {
        viewModelScope.launch {
            Logger.d("UX", "Face detection engine mode changed: ${mode.name}")
            repository.updateFaceDetectionEngineMode(mode)

            if (mode != FaceDetectionEngineMode.CUSTOM) {
                val (roiConfig, landmarkConfig) = mode.toStageConfigs()
                repository.updateRoiStageConfig(roiConfig)
                repository.updateLandmarkStageConfig(landmarkConfig)
                Logger.d("UX", "Auto-updated StageConfig for $mode")
            }
        }
    }

    private fun FaceDetectionEngineMode.toStageConfigs(): Pair<StageConfig, StageConfig> = when (this) {
        FaceDetectionEngineMode.MEDIAPIPE -> Pair(
            StageConfig(DetectionStage.ROI, DetectionModelType.MEDIAPIPE, InferenceEngineType.TFLITE, InferenceDevicePreference.AUTO),
            StageConfig(DetectionStage.LANDMARK, DetectionModelType.MEDIAPIPE, InferenceEngineType.TFLITE, InferenceDevicePreference.AUTO)
        )
        FaceDetectionEngineMode.MNN -> Pair(
            StageConfig(DetectionStage.ROI, DetectionModelType.DET_500M_MNN, InferenceEngineType.MNN, InferenceDevicePreference.AUTO),
            StageConfig(DetectionStage.LANDMARK, DetectionModelType.FACE_2D106_MNN, InferenceEngineType.MNN, InferenceDevicePreference.AUTO)
        )
        FaceDetectionEngineMode.CUSTOM -> Pair(
            StageConfig.defaultRoi(),
            StageConfig.defaultLandmark()
        )
    }

    fun setFaceDetectionLandmarkModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateFaceDetectionLandmarkMode(enabled)
        }
    }

    fun setAdaptiveFaceDetectionIntervalEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAdaptiveFaceDetectionIntervalEnabled(enabled)
        }
    }

    fun setFaceDetectIntervalProfile(profile: FaceDetectIntervalProfile) {
        viewModelScope.launch {
            repository.updateFaceDetectIntervalProfile(profile)
        }
    }

    fun setShowCameraInfoInPreview(show: Boolean) {
        viewModelScope.launch {
            repository.updateShowCameraInfoInPreview(show)
        }
    }

    fun setShowFaceDebugOverlay(show: Boolean) {
        viewModelScope.launch {
            repository.updateShowFaceDebugOverlay(show)
        }
    }

    fun setShowLogOverlay(show: Boolean) {
        viewModelScope.launch {
            repository.updateShowLogOverlay(show)
        }
    }

    fun setDebugShaderMode(mode: Int) {
        _debugShaderMode.value = mode
        // 通知 BeautyRenderer 更新 debug mode
        viewModelScope.launch {
            repository.updateDebugShaderMode(mode)
        }
    }

    // ── 阶段独立配置方法 ────────────────────────────────────
    fun setRoiModelType(modelType: DetectionModelType) {
        viewModelScope.launch {
            val current = roiStageConfig.value
            // 模型与引擎绑定，切换模型时自动同步引擎
            val updated = current.copy(
                modelType = modelType,
                engineType = modelType.toEngineType()
            )
            Logger.d("UX", "ROI model type changed: ${modelType.name}, engine auto-synced to ${modelType.toEngineType().name}")
            repository.updateRoiStageConfig(updated)
        }
    }

    fun setRoiDevicePreference(preference: InferenceDevicePreference) {
        viewModelScope.launch {
            val current = roiStageConfig.value
            val updated = current.copy(devicePreference = preference)
            Logger.d("UX", "ROI device preference changed: ${preference.name}")
            repository.updateRoiStageConfig(updated)
        }
    }

    fun setLandmarkModelType(modelType: DetectionModelType) {
        viewModelScope.launch {
            val current = landmarkStageConfig.value
            // 模型与引擎绑定，切换模型时自动同步引擎
            val updated = current.copy(
                modelType = modelType,
                engineType = modelType.toEngineType()
            )
            Logger.d("UX", "Landmark model type changed: ${modelType.name}, engine auto-synced to ${modelType.toEngineType().name}")
            repository.updateLandmarkStageConfig(updated)
        }
    }

    fun setLandmarkDevicePreference(preference: InferenceDevicePreference) {
        viewModelScope.launch {
            val current = landmarkStageConfig.value
            val updated = current.copy(devicePreference = preference)
            Logger.d("UX", "Landmark device preference changed: ${preference.name}")
            repository.updateLandmarkStageConfig(updated)
        }
    }

    fun setAiAgentMode(mode: AiAgentMode) {
        viewModelScope.launch {
            repository.updateAiAgentMode(mode)
        }
    }

    fun setAssistantPersona(persona: AssistantPersona) {
        viewModelScope.launch {
            Logger.d("UX", "Assistant persona changed: ${persona.name}")
            repository.updateAssistantPersona(persona)
        }
    }

    fun setAiAgentPrivacyLevel(level: AiAgentPrivacyLevel) {
        viewModelScope.launch {
            Logger.d("UX", "AI Agent privacy level changed: ${level.name}")
            repository.updateAiAgentPrivacyLevel(level)
        }
    }

    fun setTagGenerationUseOpencl(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "TAG generation OpenCL changed: $enabled")
            repository.updateTagGenerationUseOpencl(enabled)
        }
    }

    fun setAutoDownloadRecommendedOnWifi(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "Auto-download recommended on WiFi changed: $enabled")
            repository.updateAutoDownloadRecommendedOnWifi(enabled)
        }
    }

    fun setTaggerModelKey(key: String) {
        viewModelScope.launch {
            Logger.d("UX", "Tagger model changed: $key")
            repository.updateTaggerModelKey(key)
        }
    }

    fun setAiAgentRemoteModelConfigs(configsJson: String) {
        viewModelScope.launch {
            repository.updateAiAgentRemoteModelConfigs(configsJson)
        }
    }

    fun setAiAgentSelectedRemoteModel(modelId: String) {
        viewModelScope.launch {
            repository.updateAiAgentSelectedRemoteModel(modelId)
        }
    }

    fun setAutoExecutePlansEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "Auto execute plans changed: $enabled")
            repository.updateAutoExecutePlansEnabled(enabled)
        }
    }

    fun setJsEngineEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "JS engine enabled changed: $enabled")
            repository.updateJsEngineEnabled(enabled)
        }
    }

    fun setAgentCameraAccessEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "Agent camera access changed: $enabled")
            repository.updateAgentCameraAccessEnabled(enabled)
        }
    }

    fun setAgentGalleryAccessEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "Agent gallery access changed: $enabled")
            repository.updateAgentGalleryAccessEnabled(enabled)
        }
    }

    fun setVoiceCommandMode(mode: VoiceCommandMode) {
        viewModelScope.launch {
            Logger.d("UX", "Voice command mode changed: ${mode.name}")
            repository.updateVoiceCommandMode(mode)
            // 选择唤醒词模式时联动开启相机页语音入口，避免"已选 WAKE_WORD 但入口隐藏不监听"
            if (mode == VoiceCommandMode.WAKE_WORD) {
                repository.updateVoiceEntryEnabled(true)
            }
        }
    }

    fun setVoiceEntryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "Voice entry enabled changed: $enabled")
            repository.updateVoiceEntryEnabled(enabled)
        }
    }

    fun setAiChatEntryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "AI chat entry enabled changed: $enabled")
            repository.updateAiChatEntryEnabled(enabled)
        }
    }

    fun setMediaManageSilentTrashEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "Media manage silent trash changed: $enabled")
            repository.updateMediaManageSilentTrash(enabled)
        }
    }

    fun setLocalAsrModel(modelId: String) {
        viewModelScope.launch {
            repository.updateLocalAsrModel(modelId)
        }
    }

    fun setLocalKwsModel(modelId: String) {
        viewModelScope.launch {
            repository.updateLocalKwsModel(modelId)
        }
    }

    fun resetCameraMemoryState() {
        viewModelScope.launch {
            repository.resetCameraMemoryState()
        }
    }

    fun setLogModuleConfig(config: LogModuleConfig) {
        // 同步更新内存中的 Logger 配置，使开关立即生效
        Logger.setModuleConfig(config)
        // 同步 C++ 层的人脸检测日志开关（静态全局开关，影响所有 native 实例）
        val faceDetectionEnabled = config.isEnabled(LogModule.FACE_DETECTION)
        MnnFaceDetector.setNativeLogEnabled(faceDetectionEnabled)
        viewModelScope.launch {
            repository.updateLogModuleConfig(config)
        }
    }

}

class SettingsViewModelFactory(
    private val repository: UserSettingsRepository,
    private val modelDownloadManager: LlmModelDownloadManager,
    private val appContext: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository, modelDownloadManager, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
