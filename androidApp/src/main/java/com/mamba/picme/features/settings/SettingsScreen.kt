package com.mamba.picme.features.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.mamba.picme.BuildConfig
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.agent.core.tool.accessibility.AccessibilityServiceHolder
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.designsystem.AppColors
import com.mamba.picme.core.designsystem.PoLangTheme
import com.mamba.picme.core.designsystem.SettingsTokens
import com.mamba.picme.core.designsystem.StatusColor
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.data.download.DownloadState
import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.data.download.ModelConfig
import com.mamba.picme.data.download.LlmModelDownloadManager
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.DetectionModelType
import com.mamba.picme.domain.model.DetectionStage
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.domain.model.InferenceDevicePreference
import com.mamba.picme.domain.model.LogModule
import com.mamba.picme.domain.model.LogModuleConfig
import com.mamba.picme.domain.model.StageConfig
import com.mamba.picme.domain.model.ThemeMode
import com.mamba.picme.domain.tag.TaggerModelSelector
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.features.common.chat.rememberAgentChatConfig
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.features.settings.capability.SettingsCapability
import com.mamba.picme.service.chat.FloatingChatBubbleService
import com.mamba.picme.util.permission.BatteryOptimizationUtils
import com.mamba.picme.util.permission.MiuiPermissionUtils
import kotlinx.coroutines.delay

/**
 * 设置页分类，用于主菜单与二级页切换
 */
enum class SettingsCategory {
    MAIN,           // 设置主菜单
    ACCOUNT,        // 账号
    CAMERA,         // 相机（状态记忆与重置）
    SYSTEM,         // 系统与权限
    REMOTE_MODEL,   // 远程模型（用户侧一级入口）
    LOCAL_MODEL,    // 本地模型（用户侧一级入口）
    SANDBOX,        // 沙盒与权限（用户侧一级入口）
    DEVELOPER       // 开发者选项
}

private const val TAG = "Settings"

/** Shader 调试模式（硬编码技术名，不做 i18n）。 */
private val SHADER_DEBUG_MODES = listOf(
    0 to "Normal",
    1 to "Skin Mask",
    2 to "Warp Offset",
    3 to "BigEye Radius",
    4 to "ThinFace Radius",
    5 to "All Warp"
)

@Suppress("LongMethod", "LongParameterList") // 待重构：SettingsScreen 抽 SettingsNav holder
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    category: SettingsCategory = SettingsCategory.MAIN,
    onNavigateBack: () -> Unit,
    onNavigateToModelCenter: (String) -> Unit = {},
    onNavigateToTagControl: () -> Unit = {},
    onNavigateToTagViewer: () -> Unit = {},
    onNavigateToDedupHome: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToJsBridge: () -> Unit = {},
    onNavigateToSearchTest: () -> Unit = {},
    onNavigateToLlmLog: () -> Unit = {},
    onNavigateToCategory: (SettingsCategory) -> Unit = {},
    onNavigateToDataPrivacy: () -> Unit = {},
    onNavigateToMemoryFacts: () -> Unit = {},
    onNavigateToCommunicationChannel: () -> Unit = {},
    onNavigateToPeople: () -> Unit = {},
    onNavigateToAddProvider: () -> Unit = {},
    /** 账号 Hero 卡相机角标：进相机拍摄「我」的头像 */
    onCaptureSelfAvatar: () -> Unit = {}
) {
    val context = LocalContext.current

    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val developerOptionsUnlocked by viewModel.developerOptionsUnlocked.collectAsState()
    val debugUiEnabled by viewModel.debugUiEnabled.collectAsState()
    val showCameraInfoInPreview by viewModel.showCameraInfoInPreview.collectAsState()
    val showFaceDebugOverlay by viewModel.showFaceDebugOverlay.collectAsState()
    val showLogOverlay by viewModel.showLogOverlay.collectAsState()
    val faceDetectionLandmarkModeEnabled by viewModel.faceDetectionLandmarkModeEnabled.collectAsState()
    val adaptiveFaceDetectionIntervalEnabled by viewModel.adaptiveFaceDetectionIntervalEnabled.collectAsState()
    val faceDetectIntervalProfile by viewModel.faceDetectIntervalProfile.collectAsState()
    val debugShaderMode by viewModel.debugShaderMode.collectAsState()
    val roiStageConfig by viewModel.roiStageConfig.collectAsState()
    val landmarkStageConfig by viewModel.landmarkStageConfig.collectAsState()
    val aiAgentRemoteModelConfigs by viewModel.aiAgentRemoteModelConfigs.collectAsState()
    val aiAgentSelectedRemoteModel by viewModel.aiAgentSelectedRemoteModel.collectAsState()
    val autoExecutePlans by viewModel.autoExecutePlansEnabled.collectAsState()
    val jsEngineEnabled by viewModel.jsEngineEnabled.collectAsState()
    val agentCameraAccessEnabled by viewModel.agentCameraAccessEnabled.collectAsState()
    val agentGalleryAccessEnabled by viewModel.agentGalleryAccessEnabled.collectAsState()
    val taggerModelKey by viewModel.taggerModelKey.collectAsState()
    val voiceCommandMode by viewModel.voiceCommandMode.collectAsState()
    val voiceEntryEnabled by viewModel.voiceEntryEnabled.collectAsState()
    val aiChatEntryEnabled by viewModel.aiChatEntryEnabled.collectAsState()
    val mediaManageSilentTrashEnabled by viewModel.mediaManageSilentTrashEnabled.collectAsState()
    val localAsrModel by viewModel.localAsrModel.collectAsState()
    val localKwsModel by viewModel.localKwsModel.collectAsState()
    val logModuleConfig by viewModel.logModuleConfig.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val allModels by viewModel.allModels.collectAsState()
    val assistantPersona by viewModel.assistantPersona.collectAsState()

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
            Logger.i(TAG, "Settings screen disposed - performing soft release of voice coordinator")
            voiceCoordinator.stopWakeWordListening()
            voiceCoordinator.stopPushToTalk()
            // 注意：不调用 voiceCoordinator.release() 以避免破坏 ASR 引擎状态
        }
    }

    DisposableEffect(Unit) {
        Logger.i(TAG, "Binding SettingsCapability delegate")
        val settingsCapability = SettingsCapability.getInstance()
        settingsCapability.bindDelegate(object : SettingsCapability.Delegate {
            override fun onChangeTheme(theme: ThemeMode) {
                viewModel.setThemeMode(theme)
            }
            override fun onChangeLanguage(language: AppLanguage) {
                viewModel.setAppLanguage(language)
            }
            override fun onDownloadModel(modelId: String) {
                onNavigateToModelCenter("")
            }
            override fun onSwitchFaceEngine(engine: FaceDetectionEngineMode) {
                viewModel.setFaceDetectionEngineMode(engine)
            }
            override fun onToggleSetting(key: String, enabled: Boolean) {
                when (key) {
                    "debug_ui" -> viewModel.setDebugUiEnabled(enabled)
                    "camera_info" -> viewModel.setShowCameraInfoInPreview(enabled)
                    "voice_command" -> viewModel.setVoiceCommandMode(
                        if (enabled) VoiceCommandMode.WAKE_WORD else VoiceCommandMode.DISABLED
                    )
                    "agent_mode" -> viewModel.setAiAgentMode(
                        if (enabled) AiAgentMode.REMOTE else AiAgentMode.OFF
                    )
                    "tag_generation_opencl" -> viewModel.setTagGenerationUseOpencl(enabled)
                    else -> Logger.w(TAG, "Unknown setting key: $key")
                }
            }
        })
        Logger.i(TAG, "SettingsCapability delegate bound")

        onDispose {
            Logger.i(TAG, "Unbinding SettingsCapability delegate")
            settingsCapability.unbindDelegate()
        }
    }


    Box(modifier = Modifier.fillMaxSize()) {
        SettingsContent(
            category = category,
            themeMode = themeMode,
            appLanguage = appLanguage,
            developerOptionsUnlocked = developerOptionsUnlocked,
            onUnlockDeveloperOptions = { viewModel.setDeveloperOptionsUnlocked(true) },
            debugUiEnabled = debugUiEnabled,
            showCameraInfoInPreview = showCameraInfoInPreview,
            showFaceDebugOverlay = showFaceDebugOverlay,
            showLogOverlay = showLogOverlay,
            faceDetectionLandmarkModeEnabled = faceDetectionLandmarkModeEnabled,
            adaptiveFaceDetectionIntervalEnabled = adaptiveFaceDetectionIntervalEnabled,
            faceDetectIntervalProfile = faceDetectIntervalProfile,
            debugShaderMode = debugShaderMode,
            roiStageConfig = roiStageConfig,
            landmarkStageConfig = landmarkStageConfig,
            onThemeModeSelected = { viewModel.setThemeMode(it) },
            onAppLanguageSelected = { viewModel.setAppLanguage(it) },
            onDebugUiEnabledChange = { viewModel.setDebugUiEnabled(it) },
            onShowCameraInfoInPreviewChange = { viewModel.setShowCameraInfoInPreview(it) },
            onShowFaceDebugOverlayChange = { viewModel.setShowFaceDebugOverlay(it) },
            onShowLogOverlayChange = { viewModel.setShowLogOverlay(it) },
            onFaceDetectionLandmarkModeEnabledChange = { viewModel.setFaceDetectionLandmarkModeEnabled(it) },
            onAdaptiveFaceDetectionIntervalEnabledChange = { viewModel.setAdaptiveFaceDetectionIntervalEnabled(it) },
            onFaceDetectIntervalProfileSelected = { viewModel.setFaceDetectIntervalProfile(it) },
            onDebugShaderModeSelected = { viewModel.setDebugShaderMode(it) },
            onRoiModelTypeSelected = { viewModel.setRoiModelType(it) },
            onRoiDevicePreferenceSelected = { viewModel.setRoiDevicePreference(it) },
            onLandmarkModelTypeSelected = { viewModel.setLandmarkModelType(it) },
            onLandmarkDevicePreferenceSelected = { viewModel.setLandmarkDevicePreference(it) },
            aiAgentRemoteModelConfigs = aiAgentRemoteModelConfigs,
            onAiAgentRemoteModelConfigsChange = { viewModel.setAiAgentRemoteModelConfigs(it) },
            aiAgentSelectedRemoteModel = aiAgentSelectedRemoteModel,
            onAiAgentSelectedRemoteModelChange = { viewModel.setAiAgentSelectedRemoteModel(it) },
            assistantPersona = assistantPersona,
            onAssistantPersonaSelected = { persona -> viewModel.setAssistantPersona(persona) },
            autoExecutePlans = autoExecutePlans,
            onAutoExecutePlansChange = { viewModel.setAutoExecutePlansEnabled(it) },
            jsEngineEnabled = jsEngineEnabled,
            onJsEngineEnabledChange = { viewModel.setJsEngineEnabled(it) },
            agentCameraAccessEnabled = agentCameraAccessEnabled,
            onAgentCameraAccessChange = { viewModel.setAgentCameraAccessEnabled(it) },
            agentGalleryAccessEnabled = agentGalleryAccessEnabled,
            onAgentGalleryAccessChange = { viewModel.setAgentGalleryAccessEnabled(it) },
            taggerModelKey = taggerModelKey,
            onTaggerModelKeyChange = { viewModel.setTaggerModelKey(it) },
            voiceCommandMode = voiceCommandMode,
            onVoiceCommandModeChange = { viewModel.setVoiceCommandMode(it) },
            voiceEntryEnabled = voiceEntryEnabled,
            onVoiceEntryEnabledChange = { viewModel.setVoiceEntryEnabled(it) },
            aiChatEntryEnabled = aiChatEntryEnabled,
            onAiChatEntryEnabledChange = { viewModel.setAiChatEntryEnabled(it) },
            mediaManageSilentTrashEnabled = mediaManageSilentTrashEnabled,
            onMediaManageSilentTrashEnabledChange = { viewModel.setMediaManageSilentTrashEnabled(it) },
            localAsrModel = localAsrModel,
            onLocalAsrModelChange = { viewModel.setLocalAsrModel(it) },
            localKwsModel = localKwsModel,
            onLocalKwsModelChange = { viewModel.setLocalKwsModel(it) },
            onNavigateToModelCenter = onNavigateToModelCenter,
            onNavigateToCategory = onNavigateToCategory,
            isModelDownloaded = viewModel::isModelDownloaded,
            getModelId = viewModel::getModelId,
            downloadModel = viewModel::downloadModel,
            downloadStates = downloadStates,
            allModels = allModels,
            logModuleConfig = logModuleConfig,
            onLogModuleConfigChange = viewModel::setLogModuleConfig,
            onNavigateBack = onNavigateBack,
            onNavigateToTagControl = onNavigateToTagControl,
            onNavigateToTagViewer = onNavigateToTagViewer,
            onNavigateToDedupHome = onNavigateToDedupHome,
            onNavigateToDebug = onNavigateToDebug,
            onNavigateToJsBridge = onNavigateToJsBridge,
            onNavigateToSearchTest = onNavigateToSearchTest,
            onNavigateToLlmLog = onNavigateToLlmLog,
            onNavigateToDataPrivacy = onNavigateToDataPrivacy,
            onNavigateToMemoryFacts = onNavigateToMemoryFacts,
            onNavigateToCommunicationChannel = onNavigateToCommunicationChannel,
            onNavigateToPeople = onNavigateToPeople,
            onNavigateToAddProvider = onNavigateToAddProvider,
            onResetCameraMemoryState = { viewModel.resetCameraMemoryState() },
            onCaptureSelfAvatar = onCaptureSelfAvatar
        )
    }
}

@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // 待重构：SettingsContent 按分类拆子屏
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    category: SettingsCategory,
    themeMode: ThemeMode,
    appLanguage: AppLanguage,
    developerOptionsUnlocked: Boolean,
    onUnlockDeveloperOptions: () -> Unit,
    debugUiEnabled: Boolean,
    showCameraInfoInPreview: Boolean,
    showFaceDebugOverlay: Boolean,
    showLogOverlay: Boolean,
    faceDetectionLandmarkModeEnabled: Boolean,
    adaptiveFaceDetectionIntervalEnabled: Boolean,
    faceDetectIntervalProfile: FaceDetectIntervalProfile,
    debugShaderMode: Int,
    roiStageConfig: StageConfig,
    landmarkStageConfig: StageConfig,
    aiAgentRemoteModelConfigs: String,
    onAiAgentRemoteModelConfigsChange: (String) -> Unit,
    aiAgentSelectedRemoteModel: String,
    onAiAgentSelectedRemoteModelChange: (String) -> Unit,
    assistantPersona: AssistantPersona,
    onAssistantPersonaSelected: (AssistantPersona) -> Unit,
    autoExecutePlans: Boolean,
    onAutoExecutePlansChange: (Boolean) -> Unit,
    jsEngineEnabled: Boolean,
    onJsEngineEnabledChange: (Boolean) -> Unit,
    agentCameraAccessEnabled: Boolean,
    onAgentCameraAccessChange: (Boolean) -> Unit,
    agentGalleryAccessEnabled: Boolean,
    onAgentGalleryAccessChange: (Boolean) -> Unit,
    taggerModelKey: String,
    onTaggerModelKeyChange: (String) -> Unit,
    voiceCommandMode: VoiceCommandMode,
    onVoiceCommandModeChange: (VoiceCommandMode) -> Unit,
    voiceEntryEnabled: Boolean,
    onVoiceEntryEnabledChange: (Boolean) -> Unit,
    aiChatEntryEnabled: Boolean,
    onAiChatEntryEnabledChange: (Boolean) -> Unit,
    mediaManageSilentTrashEnabled: Boolean,
    onMediaManageSilentTrashEnabledChange: (Boolean) -> Unit,
    localAsrModel: String,
    onLocalAsrModelChange: (String) -> Unit,
    localKwsModel: String,
    onLocalKwsModelChange: (String) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    onDebugUiEnabledChange: (Boolean) -> Unit,
    onShowCameraInfoInPreviewChange: (Boolean) -> Unit,
    onShowFaceDebugOverlayChange: (Boolean) -> Unit,
    onShowLogOverlayChange: (Boolean) -> Unit,
    onFaceDetectionLandmarkModeEnabledChange: (Boolean) -> Unit,
    onAdaptiveFaceDetectionIntervalEnabledChange: (Boolean) -> Unit,
    onFaceDetectIntervalProfileSelected: (FaceDetectIntervalProfile) -> Unit,
    onDebugShaderModeSelected: (Int) -> Unit,
    onRoiModelTypeSelected: (DetectionModelType) -> Unit,
    onRoiDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onLandmarkModelTypeSelected: (DetectionModelType) -> Unit,
    onLandmarkDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onNavigateToModelCenter: (String) -> Unit,
    onNavigateToCategory: (SettingsCategory) -> Unit = {},
    isModelDownloaded: (DetectionModelType) -> Boolean,
    getModelId: (DetectionModelType, DetectionStage) -> String?,
    downloadModel: (String, ModelConfig) -> Unit,
    downloadStates: Map<String, DownloadState>,
    allModels: List<ModelConfig>,
    logModuleConfig: LogModuleConfig,
    onLogModuleConfigChange: (LogModuleConfig) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToTagControl: () -> Unit = {},
    onNavigateToTagViewer: () -> Unit = {},
    onNavigateToDedupHome: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToJsBridge: () -> Unit = {},
    onNavigateToSearchTest: () -> Unit = {},
    onNavigateToLlmLog: () -> Unit = {},
    onNavigateToDataPrivacy: () -> Unit = {},
    onNavigateToMemoryFacts: () -> Unit = {},
    onNavigateToCommunicationChannel: () -> Unit = {},
    onNavigateToPeople: () -> Unit = {},
    onNavigateToAddProvider: () -> Unit = {},
    onResetCameraMemoryState: () -> Unit = {},
    /** 账号 Hero 卡相机角标：进相机拍摄「我」的头像 */
    onCaptureSelfAvatar: () -> Unit = {}
) {
    val titleRes = when (category) {
        SettingsCategory.MAIN -> R.string.settings
        SettingsCategory.ACCOUNT -> R.string.account
        SettingsCategory.CAMERA -> R.string.camera_settings
        SettingsCategory.SYSTEM -> R.string.system_and_permissions
        SettingsCategory.REMOTE_MODEL -> R.string.remote_models
        SettingsCategory.LOCAL_MODEL -> R.string.local_models
        SettingsCategory.SANDBOX -> R.string.sandbox_settings
        SettingsCategory.DEVELOPER -> R.string.developer_options
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = SettingsTokens.listSectionPaddingH,
                    vertical = 8.dp
                )
        ) {
            if (category == SettingsCategory.MAIN) {
                SettingsMainMenu(
                    themeMode = themeMode,
                    onThemeModeSelected = onThemeModeSelected,
                    appLanguage = appLanguage,
                    onAppLanguageSelected = onAppLanguageSelected,
                    developerOptionsUnlocked = developerOptionsUnlocked,
                    onUnlockDeveloperOptions = onUnlockDeveloperOptions,
                    onNavigateToCategory = onNavigateToCategory,
                    onNavigateToModelCenter = { onNavigateToModelCenter("") },
                    onNavigateToDataPrivacy = onNavigateToDataPrivacy,
                    onNavigateToCommunicationChannel = onNavigateToCommunicationChannel,
                    onNavigateToMemoryFacts = onNavigateToMemoryFacts,
                    onNavigateToPeople = onNavigateToPeople,
                    onNavigateToTagControl = onNavigateToTagControl,
                    onNavigateToDedupHome = onNavigateToDedupHome,
                    onCaptureSelfAvatar = onCaptureSelfAvatar
                )
                return@Column
            }

            // ── 0. 账号 ────────────────────────────────────────────
            if (category == SettingsCategory.ACCOUNT) {
                SettingsSection(
                    title = stringResource(R.string.account),
                    description = stringResource(R.string.account_desc)
                ) {
                    ServerAuthSection()
                }
            }

            // ── 1. 个性化（主题与语言已迁移至设置页主菜单顶部）───

            // ── 2. 远程模型（用户侧一级入口，2026-08-17 行式重设计）────────────────
            if (category == SettingsCategory.REMOTE_MODEL) {
                RemoteModelsListSection(
                    configsJson = aiAgentRemoteModelConfigs,
                    onConfigsChange = onAiAgentRemoteModelConfigsChange,
                    selectedModelId = aiAgentSelectedRemoteModel,
                    onSelectedModelChange = onAiAgentSelectedRemoteModelChange,
                    onAddProvider = onNavigateToAddProvider
                )
                Spacer(modifier = Modifier.height(SettingsTokens.listSectionSpacing))
                SettingsSection(title = stringResource(R.string.assistant_persona)) {
                    AssistantPersonaSelection(
                        currentPersona = assistantPersona,
                        onPersonaSelected = onAssistantPersonaSelected
                    )
                }
                // 自动执行多步骤计划已迁入「沙盒与权限」一级入口
            }

            // ── 3. 本地模型（用户侧一级入口，2026-08-17 拍平为行式，与设置主页同构）──
            if (category == SettingsCategory.LOCAL_MODEL) {
                var showRoiDialog by remember { mutableStateOf(false) }
                var showLandmarkDialog by remember { mutableStateOf(false) }
                var showProfileDialog by remember { mutableStateOf(false) }
                var showTaggerDialog by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SettingsTokens.listSectionSpacing)
                ) {
                // ── 组1 检测模型：ROI / 关键点 / 照片打标（模型中心入口已在主菜单+语音行可达）──
                SettingsListSection {
                    SettingsListRow(
                        title = stringResource(R.string.stage_roi_title),
                        onClick = { showRoiDialog = true },
                        icon = Icons.Rounded.Face,
                        iconBlockColor = AppColors.vibrantBlue,
                        valueText = stageModelLabel(roiStageConfig.modelType)
                    )
                    SettingsListDivider()
                    SettingsListRow(
                        title = stringResource(R.string.stage_landmark_title),
                        onClick = { showLandmarkDialog = true },
                        icon = Icons.Rounded.CenterFocusStrong,
                        iconBlockColor = AppColors.vibrantPink,
                        valueText = stageModelLabel(landmarkStageConfig.modelType)
                    )
                    SettingsListDivider()
                    SettingsListRow(
                        title = stringResource(R.string.tagging_model_label),
                        onClick = { showTaggerDialog = true },
                        icon = Icons.Rounded.Sell,
                        iconBlockColor = AppColors.vibrantOrange,
                        valueText = taggerLabel(taggerModelKey)
                    )
                }

                // ── 组2 检测策略：关键点模式 / 动态间隔 / 档位 ──
                SettingsListSection {
                    DebugOptionRow(
                        title = stringResource(R.string.face_landmark_mode),
                        checked = faceDetectionLandmarkModeEnabled,
                        onCheckedChange = onFaceDetectionLandmarkModeEnabledChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.Speed,
                        iconBlockColor = AppColors.vibrantBlue
                    )
                    SettingsListDivider()
                    DebugOptionRow(
                        title = stringResource(R.string.adaptive_face_detect_interval),
                        checked = adaptiveFaceDetectionIntervalEnabled,
                        onCheckedChange = onAdaptiveFaceDetectionIntervalEnabledChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.Timer,
                        iconBlockColor = AppColors.vibrantBlue
                    )
                    if (adaptiveFaceDetectionIntervalEnabled) {
                        SettingsListDivider()
                        SettingsListRow(
                            title = stringResource(R.string.face_detect_profile_title),
                            onClick = { showProfileDialog = true },
                            icon = Icons.Rounded.Tune,
                            iconBlockColor = AppColors.vibrantBlue,
                            valueText = profileLabel(faceDetectIntervalProfile)
                        )
                    }
                }

                // ── 组3 语音：语音识别 / 唤醒词 ──
                SettingsListSection {
                    VoiceModelRow(
                        title = stringResource(R.string.local_asr_model),
                        tag = "asr",
                        currentModel = localAsrModel,
                        onModelSelected = onLocalAsrModelChange,
                        onNavigateToModelCenter = { onNavigateToModelCenter("Audio") },
                        icon = Icons.Rounded.GraphicEq,
                        iconBlockColor = AppColors.vibrantGreen
                    )
                    SettingsListDivider()
                    VoiceModelRow(
                        title = stringResource(R.string.local_kws_model),
                        tag = "kws",
                        currentModel = localKwsModel,
                        onModelSelected = onLocalKwsModelChange,
                        onNavigateToModelCenter = { onNavigateToModelCenter("Audio") },
                        icon = Icons.Rounded.Mic,
                        iconBlockColor = AppColors.vibrantGreen
                    )
                }
                }

                if (showRoiDialog) {
                    StageConfigDialog(
                        title = stringResource(R.string.stage_roi_title),
                        stage = DetectionStage.ROI,
                        config = roiStageConfig,
                        onModelTypeSelected = onRoiModelTypeSelected,
                        onDevicePreferenceSelected = onRoiDevicePreferenceSelected,
                        isModelDownloaded = isModelDownloaded,
                        getModelId = getModelId,
                        downloadModel = downloadModel,
                        downloadStates = downloadStates,
                        allModels = allModels,
                        onDismiss = { showRoiDialog = false }
                    )
                }
                if (showLandmarkDialog) {
                    StageConfigDialog(
                        title = stringResource(R.string.stage_landmark_title),
                        stage = DetectionStage.LANDMARK,
                        config = landmarkStageConfig,
                        onModelTypeSelected = onLandmarkModelTypeSelected,
                        onDevicePreferenceSelected = onLandmarkDevicePreferenceSelected,
                        isModelDownloaded = isModelDownloaded,
                        getModelId = getModelId,
                        downloadModel = downloadModel,
                        downloadStates = downloadStates,
                        allModels = allModels,
                        onDismiss = { showLandmarkDialog = false }
                    )
                }
                if (showProfileDialog) {
                    SettingsSingleChoiceDialog(
                        title = stringResource(R.string.face_detect_profile_title),
                        options = listOf(
                            FaceDetectIntervalProfile.CONSERVATIVE to stringResource(R.string.face_detect_profile_conservative),
                            FaceDetectIntervalProfile.BALANCED to stringResource(R.string.face_detect_profile_balanced),
                            FaceDetectIntervalProfile.AGGRESSIVE to stringResource(R.string.face_detect_profile_aggressive)
                        ),
                        isSelected = { it == faceDetectIntervalProfile },
                        onSelected = { profile -> onFaceDetectIntervalProfileSelected(profile as FaceDetectIntervalProfile) },
                        onDismiss = { showProfileDialog = false }
                    )
                }
                if (showTaggerDialog) {
                    SettingsSingleChoiceDialog(
                        title = stringResource(R.string.tagging_model_label),
                        options = listOf(
                            TaggerModelSelector.AUTO to stringResource(R.string.tag_model_auto),
                            "florence2_base" to "Florence-2",
                            "qwen3_vl_2b" to "Qwen3-VL"
                        ),
                        isSelected = { it == taggerModelKey },
                        onSelected = { key -> onTaggerModelKeyChange(key as String) },
                        onDismiss = { showTaggerDialog = false }
                    )
                }
            }

            // ── 4. 语音控制（用户侧一级入口）────────────────────────
            // 语音控制已并入「沙盒与权限」页的设备访问模块（麦克风访问）

            // ── 5. 沙盒与权限（用户侧一级入口，2026-08-17 行式重设计）────────────
            if (category == SettingsCategory.SANDBOX) {
                val context = LocalContext.current
                val openAppSystemSettings: () -> Unit = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                }
                var showVoiceModeDialog by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SettingsTokens.listSectionSpacing)
                ) {
                // ── 组1 智能体执行 ──
                SettingsListSection {
                    DebugOptionRow(
                        title = stringResource(R.string.ai_agent_auto_execute_plans),
                        checked = autoExecutePlans,
                        onCheckedChange = onAutoExecutePlansChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.Psychology,
                        iconBlockColor = AppColors.vibrantPurple
                    )
                    SettingsListDivider()
                    DebugOptionRow(
                        title = stringResource(R.string.js_engine_execution),
                        checked = jsEngineEnabled,
                        onCheckedChange = onJsEngineEnabledChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.Code,
                        iconBlockColor = AppColors.vibrantPurple
                    )
                }

                // ── 组2 设备访问 ──
                SettingsListSection {
                    DebugOptionRow(
                        title = stringResource(R.string.agent_camera_access),
                        checked = agentCameraAccessEnabled,
                        onCheckedChange = onAgentCameraAccessChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.CameraAlt,
                        iconBlockColor = AppColors.vibrantBlue
                    )
                    SettingsListDivider()
                    SettingsListRow(
                        title = stringResource(R.string.camera_permission_system),
                        onClick = openAppSystemSettings,
                        icon = Icons.Rounded.Lock,
                        iconBlockColor = AppColors.vibrantBlue,
                        valueText = stringResource(R.string.permission_system_value)
                    )
                    SettingsListDivider()
                    DebugOptionRow(
                        title = stringResource(R.string.agent_gallery_access),
                        checked = agentGalleryAccessEnabled,
                        onCheckedChange = onAgentGalleryAccessChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.PhotoLibrary,
                        iconBlockColor = AppColors.vibrantBlue
                    )
                    SettingsListDivider()
                    SettingsListRow(
                        title = stringResource(R.string.gallery_permission_system),
                        onClick = openAppSystemSettings,
                        icon = Icons.Rounded.Lock,
                        iconBlockColor = AppColors.vibrantBlue,
                        valueText = stringResource(R.string.permission_system_value)
                    )
                    // 「删除不再询问」（MANAGE_MEDIA 静默回收站）：canManageMedia 检查口为 API 31+ 新增，低版本不显示
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val lifecycleOwner = LocalLifecycleOwner.current
                        // 权限状态跟随 ON_RESUME 刷新（从系统授权页返回即更新 summary），不做一次性 remember 缓存
                        val canManageMedia by produceState(
                            initialValue = MediaStore.canManageMedia(context),
                            lifecycleOwner
                        ) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    value = MediaStore.canManageMedia(context)
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            awaitDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        SettingsListDivider()
                        DebugOptionRow(
                            title = stringResource(R.string.settings_silent_trash_title),
                            subtitle = stringResource(
                                if (canManageMedia) R.string.settings_silent_trash_summary_granted
                                else R.string.settings_silent_trash_summary_denied
                            ),
                            checked = mediaManageSilentTrashEnabled,
                            onCheckedChange = { enabled ->
                                onMediaManageSilentTrashEnabledChange(enabled)
                                // 开关状态照存；未授权时引导跳系统媒体管理授权页，权限到位后自然生效
                                if (enabled && !canManageMedia) {
                                    val intent = Intent(
                                        Settings.ACTION_REQUEST_MANAGE_MEDIA,
                                        Uri.parse("package:${context.packageName}")
                                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    runCatching { context.startActivity(intent) }
                                }
                            },
                            horizontalPadding = SettingsTokens.listRowPaddingH,
                            rowHeight = SettingsTokens.listRowHeight,
                            icon = Icons.Rounded.DeleteSweep,
                            iconBlockColor = AppColors.vibrantBlue
                        )
                    }
                }

                // ── 组3 语音 ──
                SettingsListSection {
                    SettingsListRow(
                        title = stringResource(R.string.voice_control),
                        onClick = { showVoiceModeDialog = true },
                        icon = Icons.Rounded.Mic,
                        iconBlockColor = AppColors.vibrantGreen,
                        valueText = stringResource(voiceModeLabelRes(voiceCommandMode))
                    )
                    SettingsListDivider()
                    DebugOptionRow(
                        title = stringResource(R.string.voice_entry_enabled),
                        checked = voiceEntryEnabled,
                        onCheckedChange = onVoiceEntryEnabledChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.KeyboardVoice,
                        iconBlockColor = AppColors.vibrantGreen
                    )
                    SettingsListDivider()
                    DebugOptionRow(
                        title = stringResource(R.string.ai_chat_entry_enabled),
                        checked = aiChatEntryEnabled,
                        onCheckedChange = onAiChatEntryEnabledChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.SmartToy,
                        iconBlockColor = AppColors.vibrantGreen
                    )
                }
                }

                if (showVoiceModeDialog) {
                    SettingsSingleChoiceDialog(
                        title = stringResource(R.string.voice_control),
                        options = listOf(
                            VoiceCommandMode.DISABLED to stringResource(R.string.voice_command_mode_disabled),
                            VoiceCommandMode.PUSH_TO_TALK to stringResource(R.string.voice_command_mode_push_to_talk),
                            VoiceCommandMode.WAKE_WORD to stringResource(R.string.voice_command_mode_wake_word)
                        ),
                        isSelected = { it == voiceCommandMode },
                        onSelected = { mode -> onVoiceCommandModeChange(mode as VoiceCommandMode) },
                        onDismiss = { showVoiceModeDialog = false }
                    )
                }
            }

            // ── 3. 相册功能 ─────────────────────────────────────
            // 休眠 GALLERY 分类已于 2026-09-05 整块删除（死代码清理）：
            // TAG 控制/标签查看/重复图入口由主菜单与扫描页承担，
            // TAG 生成 GPU 加速收口至 TagGenerationControlScreen 页尾单行卡。

            // ── 相机（状态记忆与重置；2026-08-16 相机页改版后重置入口迁入此处）──
            if (category == SettingsCategory.CAMERA) {
                SettingsSection(
                    title = stringResource(R.string.camera_memory),
                    description = stringResource(R.string.camera_memory_desc)
                ) {
                    var showResetConfirm by remember { mutableStateOf(false) }
                    SettingsClickableRow(
                        title = stringResource(R.string.reset_camera_to_default),
                        subtitle = stringResource(R.string.reset_camera_to_default_desc),
                        leadingIcon = Icons.Rounded.RestartAlt,
                        onClick = { showResetConfirm = true }
                    )
                    if (showResetConfirm) {
                        AlertDialog(
                            onDismissRequest = { showResetConfirm = false },
                            title = { Text(stringResource(R.string.reset_camera_confirm_title)) },
                            text = { Text(stringResource(R.string.reset_camera_confirm_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showResetConfirm = false
                                    onResetCameraMemoryState()
                                }) {
                                    Text(stringResource(R.string.ok))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showResetConfirm = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }
                }
            }

            // 人脸检测引擎配置收口至「本地模型」/「开发者选项」，
            // 语音入口开关迁入「沙盒与权限」一级入口（语音入口属语音行为）。

            // ── 5. 系统与权限 ─────────────────────────────────────
            if (category == SettingsCategory.SYSTEM) {
                val context = LocalContext.current
                var isFloatingChatRunning by remember {
                    mutableStateOf(FloatingChatBubbleService.isRunning(context))
                }
                var hasOverlayPermission by remember {
                    mutableStateOf(FloatingChatBubbleService.canDrawOverlays(context))
                }
                val overlayPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    hasOverlayPermission = FloatingChatBubbleService.canDrawOverlays(context)
                    if (hasOverlayPermission && !isFloatingChatRunning) {
                        FloatingChatBubbleService.start(context)
                        isFloatingChatRunning = true
                    }
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        isFloatingChatRunning = FloatingChatBubbleService.isRunning(context)
                        hasOverlayPermission = FloatingChatBubbleService.canDrawOverlays(context)
                        delay(1000)
                    }
                }

                SettingsSection(
                    title = stringResource(R.string.floating_chat_title),
                    description = stringResource(R.string.floating_chat_summary)
                ) {
                    SettingsClickableRow(
                        title = stringResource(R.string.floating_chat_title),
                        subtitle = stringResource(R.string.floating_chat_summary),
                        valueText = stringResource(
                            if (isFloatingChatRunning) R.string.floating_chat_enabled else R.string.floating_chat_disabled
                        ),
                        onClick = {
                            when {
                                !hasOverlayPermission -> {
                                    overlayPermissionLauncher.launch(
                                        FloatingChatBubbleService.openOverlayPermissionSettingsIntent(context)
                                    )
                                }
                                isFloatingChatRunning -> {
                                    FloatingChatBubbleService.stop(context)
                                    isFloatingChatRunning = false
                                }
                                else -> {
                                    FloatingChatBubbleService.start(context)
                                    isFloatingChatRunning = true
                                }
                            }
                        }
                    )
                }

                var isIgnoringBatteryOptimizations by remember {
                    mutableStateOf(BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context))
                }
                val isMiui = remember { MiuiPermissionUtils.isMiui() }

                LaunchedEffect(Unit) {
                    while (true) {
                        isIgnoringBatteryOptimizations =
                            BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context)
                        delay(1000)
                    }
                }

                SettingsSection(
                    title = stringResource(R.string.settings_background_permission_title),
                    description = stringResource(R.string.settings_background_permission_summary)
                ) {
                    SettingsClickableRow(
                        title = stringResource(R.string.settings_battery_optimization_title),
                        subtitle = stringResource(R.string.settings_battery_optimization_summary),
                        valueText = stringResource(
                            if (isIgnoringBatteryOptimizations) {
                                R.string.settings_battery_optimization_enabled
                            } else {
                                R.string.settings_battery_optimization_disabled
                            }
                        ),
                        onClick = {
                            if (!isIgnoringBatteryOptimizations) {
                                BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(context)
                            }
                        }
                    )

                    if (isMiui) {
                        SettingsClickableRow(
                            title = stringResource(R.string.settings_miui_auto_start_title),
                            subtitle = stringResource(R.string.settings_miui_auto_start_summary),
                            valueText = stringResource(R.string.settings_miui_action_open),
                            onClick = { MiuiPermissionUtils.openMiuiAutoStart(context) }
                        )

                        SettingsClickableRow(
                            title = stringResource(R.string.settings_miui_permission_editor_title),
                            subtitle = stringResource(R.string.settings_miui_permission_editor_summary),
                            valueText = stringResource(R.string.settings_miui_action_open),
                            onClick = { MiuiPermissionUtils.openMiuiPermissionEditor(context) }
                        )
                    }
                }
            }

            // ── 6. 开发者选项 ─────────────────────────────────────
            if (category == SettingsCategory.DEVELOPER) {
                var showShaderDialog by remember { mutableStateOf(false) }
                var showLogModulesDialog by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SettingsTokens.listSectionSpacing)
                ) {
                // ── 组1 预览调试（设计稿 111:129：五行常显，不随调试开关折叠）──
                SettingsListSection {
                    DebugOptionRow(
                        title = stringResource(R.string.debug),
                        checked = debugUiEnabled,
                        onCheckedChange = onDebugUiEnabledChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.BugReport,
                        iconBlockColor = StatusColor.warningAmber
                    )
                    SettingsListDivider()
                    DebugOptionRow(
                        title = stringResource(R.string.show_camera_info),
                        checked = showCameraInfoInPreview,
                        onCheckedChange = onShowCameraInfoInPreviewChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.Language,
                        iconBlockColor = StatusColor.warningAmber
                    )
                    SettingsListDivider()
                    DebugOptionRow(
                        title = stringResource(R.string.show_face_debug),
                        checked = showFaceDebugOverlay,
                        onCheckedChange = onShowFaceDebugOverlayChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.Face,
                        iconBlockColor = StatusColor.warningAmber
                    )
                    SettingsListDivider()
                    DebugOptionRow(
                        title = stringResource(R.string.show_log_overlay),
                        checked = showLogOverlay,
                        onCheckedChange = onShowLogOverlayChange,
                        horizontalPadding = SettingsTokens.listRowPaddingH,
                        rowHeight = SettingsTokens.listRowHeight,
                        icon = Icons.Rounded.Description,
                        iconBlockColor = StatusColor.warningAmber
                    )
                    SettingsListDivider()
                    SettingsListRow(
                        title = stringResource(R.string.shader_debug_mode),
                        onClick = { showShaderDialog = true },
                        icon = Icons.Rounded.Gradient,
                        iconBlockColor = StatusColor.warningAmber,
                        valueText = shaderModeLabel(debugShaderMode)
                    )
                }

                // 引擎与模型（人脸检测阶段配置 + 打标模型）已移至「本地模型」一级入口

                // ── 组2 诊断日志 ──
                SettingsListSection {
                    SettingsListRow(
                        title = stringResource(R.string.llm_call_log),
                        onClick = onNavigateToLlmLog,
                        icon = Icons.Rounded.Terminal,
                        iconBlockColor = StatusColor.warningAmber
                    )
                    SettingsListDivider()
                    SettingsListRow(
                        title = stringResource(R.string.log_management),
                        onClick = { showLogModulesDialog = true },
                        icon = Icons.Rounded.ListAlt,
                        iconBlockColor = StatusColor.warningAmber,
                        valueText = stringResource(R.string.log_modules_all)
                    )
                }

                // ── 组3 测试工具（仅 DEBUG 构建） ──
                if (BuildConfig.DEBUG) {
                    val context = LocalContext.current

                    SettingsListSection {
                        SettingsListRow(
                            title = stringResource(R.string.debug_image_download),
                            onClick = onNavigateToDebug,
                            icon = Icons.Rounded.CloudDownload,
                            iconBlockColor = StatusColor.warningAmber
                        )
                        SettingsListDivider()
                        SettingsListRow(
                            title = stringResource(R.string.search_test_entry_title),
                            onClick = onNavigateToSearchTest,
                            icon = Icons.Rounded.Search,
                            iconBlockColor = StatusColor.warningAmber
                        )
                        SettingsListDivider()
                        SettingsListRow(
                            title = stringResource(R.string.jsbridge_entry_title),
                            onClick = onNavigateToJsBridge,
                            icon = Icons.Rounded.Code,
                            iconBlockColor = StatusColor.warningAmber
                        )
                        SettingsListDivider()

                        // AI 远程控制无障碍服务
                        var isAccessibilityEnabled by remember {
                            mutableStateOf(AccessibilityServiceHolder.isActive())
                        }

                        LaunchedEffect(Unit) {
                            while (true) {
                                isAccessibilityEnabled = AccessibilityServiceHolder.isActive()
                                delay(1000)
                            }
                        }

                        SettingsListRow(
                            title = stringResource(R.string.settings_accessibility_service_title),
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            icon = Icons.Rounded.Accessibility,
                            iconBlockColor = StatusColor.warningAmber,
                            valueText = stringResource(
                                if (isAccessibilityEnabled) {
                                    R.string.settings_accessibility_service_enabled
                                } else {
                                    R.string.settings_accessibility_service_disabled
                                }
                            )
                        )
                    }
                }
                }

                if (showShaderDialog) {
                    SettingsSingleChoiceDialog(
                        title = stringResource(R.string.shader_debug_mode),
                        options = SHADER_DEBUG_MODES,
                        isSelected = { it == debugShaderMode },
                        onSelected = { mode -> onDebugShaderModeSelected(mode as Int) },
                        onDismiss = { showShaderDialog = false }
                    )
                }
                if (showLogModulesDialog) {
                    SettingsOptionSheetShell(
                        title = stringResource(R.string.log_management),
                        subtitle = stringResource(R.string.log_modules_dialog_subtitle),
                        onDismiss = { showLogModulesDialog = false }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LogModule.entries.forEach { module ->
                                SettingsOptionRow(
                                    label = module.displayName,
                                    selected = logModuleConfig.isEnabled(module),
                                    onClick = {
                                        onLogModuleConfigChange(
                                            logModuleConfig.toggle(module, !logModuleConfig.isEnabled(module))
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongParameterList") // 待重构：SettingsScreen 抽 SettingsNav holder
@Composable
private fun SettingsMainMenu(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    developerOptionsUnlocked: Boolean,
    onUnlockDeveloperOptions: () -> Unit,
    onNavigateToCategory: (SettingsCategory) -> Unit,
    onNavigateToModelCenter: () -> Unit,
    onNavigateToDataPrivacy: () -> Unit,
    onNavigateToCommunicationChannel: () -> Unit,
    onNavigateToMemoryFacts: () -> Unit,
    onNavigateToPeople: () -> Unit,
    onNavigateToTagControl: () -> Unit,
    onNavigateToDedupHome: () -> Unit,
    onCaptureSelfAvatar: () -> Unit
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsTokens.listSectionSpacing)
    ) {
        // ── 账号（置顶行，反映登录态）──
        SettingsAccountHeroCard(
            onClick = { onNavigateToCategory(SettingsCategory.ACCOUNT) },
            onCaptureAvatarClick = onCaptureSelfAvatar
        )

        // ── 个性化：主题 / 语言（右值=当前选中，点击弹窗切换）──
        SettingsListSection {
            SettingsListRow(
                title = stringResource(R.string.theme_mode),
                icon = Icons.Rounded.DarkMode,
                iconBlockColor = AppColors.vibrantOrange,
                valueText = stringResource(themeModeLabelRes(themeMode)),
                onClick = { showThemeDialog = true }
            )
            SettingsListDivider()
            SettingsListRow(
                title = stringResource(R.string.language),
                icon = Icons.Rounded.Language,
                iconBlockColor = AppColors.vibrantBlue,
                valueText = languageLabel(appLanguage),
                onClick = { showLanguageDialog = true }
            )
        }

        // ── 功能：人物 / AI 记忆 / 相册 / 相机 ──
        SettingsListSection {
            SettingsListRow(
                title = stringResource(R.string.people_entry),
                icon = Icons.Rounded.AccountCircle,
                iconBlockColor = AppColors.vibrantGreen,
                onClick = onNavigateToPeople
            )
            SettingsListDivider()
            SettingsListRow(
                title = stringResource(R.string.settings_ai_memory),
                icon = Icons.Rounded.Psychology,
                iconBlockColor = MaterialTheme.colorScheme.primaryContainer,
                onClick = onNavigateToMemoryFacts
            )
            SettingsListDivider()
            SettingsListRow(
                title = stringResource(R.string.gallery_settings),
                icon = Icons.Rounded.PhotoLibrary,
                iconBlockColor = AppColors.vibrantOrange,
                onClick = onNavigateToTagControl
            )
            SettingsListDivider()
            // 相册整理（去重 2.0）一级入口：原「管理重复照片」自 TagControl 头部升级为独立入口
            SettingsListRow(
                title = stringResource(R.string.gallery_cleanup),
                icon = Icons.Rounded.BurstMode,
                iconBlockColor = AppColors.vibrantPurple,
                onClick = onNavigateToDedupHome
            )
            SettingsListDivider()
            SettingsListRow(
                title = stringResource(R.string.camera_settings),
                icon = Icons.Rounded.CameraAlt,
                iconBlockColor = AppColors.vibrantPink,
                onClick = { onNavigateToCategory(SettingsCategory.CAMERA) }
            )
        }

        // ── AI 与系统：模型中心 / 远程模型 / 本地模型 / 通信通道 / 沙盒与权限 ──
        SettingsListSection {
            SettingsListRow(
                title = stringResource(R.string.model_center),
                icon = Icons.Rounded.CloudDownload,
                iconBlockColor = AppColors.vibrantGreen,
                onClick = onNavigateToModelCenter
            )
            SettingsListDivider()
            SettingsListRow(
                title = stringResource(R.string.remote_models),
                icon = Icons.Rounded.Cloud,
                iconBlockColor = StatusColor.info,
                onClick = { onNavigateToCategory(SettingsCategory.REMOTE_MODEL) }
            )
            SettingsListDivider()
            SettingsListRow(
                title = stringResource(R.string.local_models),
                icon = Icons.Rounded.Memory,
                iconBlockColor = AppColors.vibrantPink,
                onClick = { onNavigateToCategory(SettingsCategory.LOCAL_MODEL) }
            )
            SettingsListDivider()
            SettingsListRow(
                title = stringResource(R.string.communication_channel),
                icon = Icons.Rounded.Forum,
                iconBlockColor = AppColors.vibrantOrange,
                onClick = onNavigateToCommunicationChannel
            )
            SettingsListDivider()
            SettingsListRow(
                title = stringResource(R.string.sandbox_settings),
                icon = Icons.Rounded.VerifiedUser,
                iconBlockColor = MaterialTheme.colorScheme.primaryContainer,
                onClick = { onNavigateToCategory(SettingsCategory.SANDBOX) }
            )
        }

        // ── 其他：数据与隐私 / 开发者选项（解锁后显示）──
        SettingsListSection {
            SettingsListRow(
                title = stringResource(R.string.data_privacy_entry),
                icon = Icons.Rounded.PrivacyTip,
                iconBlockColor = AppColors.vibrantBlue,
                onClick = onNavigateToDataPrivacy
            )
            if (developerOptionsUnlocked) {
                SettingsListDivider()
                SettingsListRow(
                    title = stringResource(R.string.developer_options),
                    icon = Icons.Rounded.Terminal,
                    iconBlockColor = StatusColor.warningAmber,
                    onClick = { onNavigateToCategory(SettingsCategory.DEVELOPER) }
                )
            }
        }

        // ── 版本页脚（连点 7 次解锁开发者选项）──
        SettingsVersionFooter(onUnlock = onUnlockDeveloperOptions)
    }

    if (showThemeDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.theme_mode),
            subtitle = stringResource(R.string.theme_mode_dialog_subtitle),
            options = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.system_default),
                ThemeMode.LIGHT to stringResource(R.string.light),
                ThemeMode.DARK to stringResource(R.string.dark)
            ),
            isSelected = { it == themeMode },
            onSelected = { mode -> onThemeModeSelected(mode as ThemeMode) },
            onDismiss = { showThemeDialog = false }
        )
    }
    if (showLanguageDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.language),
            subtitle = stringResource(R.string.language_dialog_subtitle),
            options = listOf(
                AppLanguage.SYSTEM to stringResource(R.string.system_default),
                AppLanguage.ENGLISH to "English",
                AppLanguage.CHINESE to "中文",
                AppLanguage.TRADITIONAL_CHINESE to "繁體中文",
                AppLanguage.SPANISH to "Español",
                AppLanguage.FRENCH to "Français"
            ),
            isSelected = { it == appLanguage },
            onSelected = { language -> onAppLanguageSelected(language as AppLanguage) },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

private fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.system_default
    ThemeMode.LIGHT -> R.string.light
    ThemeMode.DARK -> R.string.dark
}

private fun voiceModeLabelRes(mode: VoiceCommandMode): Int = when (mode) {
    VoiceCommandMode.DISABLED -> R.string.voice_command_mode_disabled
    VoiceCommandMode.PUSH_TO_TALK -> R.string.voice_command_mode_push_to_talk
    VoiceCommandMode.WAKE_WORD -> R.string.voice_command_mode_wake_word
}

private fun shaderModeLabel(mode: Int): String = SHADER_DEBUG_MODES.firstOrNull { it.first == mode }?.second ?: "Normal"

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.system_default)
    AppLanguage.ENGLISH -> "English"
    AppLanguage.CHINESE -> "中文"
    AppLanguage.TRADITIONAL_CHINESE -> "繁體中文"
    AppLanguage.SPANISH -> "Español"
    AppLanguage.FRENCH -> "Français"
}

/** 列表行值列的弹层选择器（主题/语言/档位/打标/语音共用）：底部弹层，单选即生效。
 * trailing 可选：主题弹窗传预览块等自定义尾部内容。 */
@Composable
private fun <T> SettingsSingleChoiceDialog(
    title: String,
    subtitle: String? = null,
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    trailing: @Composable (T) -> Unit = {}
) {
    SettingsOptionSheetShell(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (value, label) ->
                SettingsOptionRow(
                    label = label,
                    selected = isSelected(value),
                    onClick = {
                        onSelected(value)
                        onDismiss()
                    },
                    trailing = { trailing(value) }
                )
            }
        }
    }
}

/**
 * 账号置顶全宽卡：已登录显示邮箱与「服务端账户」，未登录显示「账号」+ 注册引导。
 * 头像跟随人物页的"我"标记：已标记且有封面时显示本人人脸（face-aware 裁剪），否则回退默认图标。
 */
@Composable
private fun SettingsAccountHeroCard(
    onClick: () -> Unit,
    /** 头像右下角相机角标：进相机拍摄「我」的头像 */
    onCaptureAvatarClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val repo = app.container.userPreferencesRepository
    val serverToken by repo.serverAuthTokenFlow.collectAsState(initial = "")
    val serverEmail by repo.serverAuthEmailFlow.collectAsState(initial = "")
    val loggedIn = serverToken.isNotBlank()

    val personRepository = app.container.personRepository
    val selfAvatar by remember(personRepository) {
        personRepository.observeSelfAvatar()
    }.collectAsState(initial = null)

    val authClient = app.container.picMeAuthClient
    var quotaUsed by remember { mutableStateOf(0) }
    var quotaLimit by remember { mutableStateOf(0) }
    var quotaLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(serverToken) {
        if (loggedIn) {
            authClient.getQuota(serverToken)
                .onSuccess {
                    quotaUsed = it.llmCallsUsed
                    quotaLimit = it.llmCallsLimit
                    quotaLoaded = true
                }
                .onFailure { Logger.w(TAG, "Hero card quota load failed: ${it.message}") }
        } else {
            quotaLoaded = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SettingsTokens.listHeroRowHeight)
                .padding(horizontal = SettingsTokens.listRowPaddingH),
            horizontalArrangement = Arrangement.spacedBy(SettingsTokens.rowElementGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(SettingsTokens.heroAvatarSize)
                ) {
                    val avatar = selfAvatar
                    if (avatar != null) {
                        AsyncImage(
                            model = avatar.coverUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = faceAwareVerticalAlignment(avatar.faceFocusY)
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
                // 相机角标：拍「我」的头像（视觉对齐人物编辑页 AvatarHeader）
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable(onClick = onCaptureAvatarClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoCamera,
                        contentDescription = stringResource(R.string.avatar_capture_hint),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = if (loggedIn) serverEmail else stringResource(R.string.account),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        !loggedIn -> stringResource(R.string.account_desc)
                        quotaLoaded -> stringResource(
                            R.string.auth_quota_summary,
                            quotaUsed,
                            quotaLimit
                        )
                        else -> stringResource(R.string.auth_account_title)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SettingsTokens.rowChevronAlpha),
                modifier = Modifier.size(SettingsTokens.rowChevronSize)
            )
        }
    }
}

/**
 * 设置主页底部版本页脚：连点 [DeveloperOptionsUnlockCounter.REQUIRED_TAPS] 次解锁开发者选项。
 * 解锁前对普通用户仅显示版本号（良性信息），不构成开发项泄漏。
 */
@Composable
private fun SettingsVersionFooter(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val counter = remember { DeveloperOptionsUnlockCounter() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(
                R.string.app_version_footer,
                stringResource(R.string.app_name),
                BuildConfig.VERSION_NAME
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.clickable {
                when (val result = counter.tap()) {
                    is UnlockTapResult.Countdown -> Toast.makeText(
                        context,
                        context.getString(R.string.dev_options_unlock_countdown, result.remaining),
                        Toast.LENGTH_SHORT
                    ).show()
                    UnlockTapResult.Unlocked -> {
                        onUnlock()
                        Toast.makeText(
                            context,
                            context.getString(R.string.dev_options_unlocked_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
}

/**
 * 检测阶段配置弹窗（ROI/关键点行点击）：模型单选 + 设备偏好单选两组。
 * 未下载模型项点击触发下载（下载中禁用），选项即时生效不关弹窗。
 */
@Composable
private fun StageConfigDialog(
    title: String,
    stage: DetectionStage,
    config: StageConfig,
    onModelTypeSelected: (DetectionModelType) -> Unit,
    onDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    isModelDownloaded: (DetectionModelType) -> Boolean,
    getModelId: (DetectionModelType, DetectionStage) -> String?,
    downloadModel: (String, ModelConfig) -> Unit,
    downloadStates: Map<String, DownloadState>,
    allModels: List<ModelConfig>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val modelTypes = if (stage == DetectionStage.ROI) {
        listOf(
            DetectionModelType.MEDIAPIPE to R.string.model_mediapipe,
            DetectionModelType.DET_500M_MNN to R.string.model_det10g_mnn
        )
    } else {
        listOf(
            DetectionModelType.MEDIAPIPE to R.string.model_mediapipe,
            DetectionModelType.FACE_2D106_MNN to R.string.model_2d106_mnn
        )
    }

    fun selectModel(type: DetectionModelType) {
        val isMediaPipe = type == DetectionModelType.MEDIAPIPE
        if (isMediaPipe || isModelDownloaded(type)) {
            onModelTypeSelected(type)
        } else {
            val modelId = getModelId(type, stage)
            val modelConfig = modelId?.let { id -> allModels.find { it.id == id } }
            if (modelConfig != null && modelId != null) {
                downloadModel(modelId, modelConfig)
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_model_download_started, modelConfig.name),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_model_config_not_found),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    SettingsOptionSheetShell(
        title = title,
        subtitle = null,
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsOptionGroupLabel(stringResource(R.string.model_type))
            modelTypes.forEach { (type, labelRes) ->
                val isMediaPipe = type == DetectionModelType.MEDIAPIPE
                val downloaded = isModelDownloaded(type) || isMediaPipe
                val modelId = getModelId(type, stage)
                val downloadState = modelId?.let { downloadStates[it] }
                val downloading = downloadState?.status == DownloadStatus.DOWNLOADING
                val progress = if (downloading && downloadState.totalBytes > 0) {
                    (downloadState.downloadedBytes.toFloat() / downloadState.totalBytes * 100).toInt()
                } else {
                    0
                }
                SettingsOptionRow(
                    label = when {
                        downloading -> "${stringResource(labelRes)} · $progress%"
                        !downloaded -> "${stringResource(labelRes)} · ${stringResource(R.string.model_pending_download)}"
                        else -> stringResource(labelRes)
                    },
                    selected = type == config.modelType,
                    onClick = { selectModel(type) }
                )
            }
            SettingsOptionGroupLabel(stringResource(R.string.inference_device_preference))
            listOf(
                InferenceDevicePreference.AUTO to R.string.device_preference_auto,
                InferenceDevicePreference.FORCE_CPU to R.string.device_preference_force_cpu,
                InferenceDevicePreference.FORCE_GPU to R.string.device_preference_force_gpu
            ).forEach { (preference, labelRes) ->
                SettingsOptionRow(
                    label = stringResource(labelRes),
                    selected = preference == config.devicePreference,
                    onClick = { onDevicePreferenceSelected(preference) }
                )
            }
        }
    }
}

@Composable
private fun stageModelLabel(type: DetectionModelType): String = stringResource(
    when (type) {
        DetectionModelType.MEDIAPIPE -> R.string.model_mediapipe
        DetectionModelType.DET_500M_MNN -> R.string.model_det10g_mnn
        DetectionModelType.FACE_2D106_MNN -> R.string.model_2d106_mnn
    }
)

@Composable
private fun profileLabel(profile: FaceDetectIntervalProfile): String = stringResource(
    when (profile) {
        FaceDetectIntervalProfile.CONSERVATIVE -> R.string.face_detect_profile_conservative
        FaceDetectIntervalProfile.BALANCED -> R.string.face_detect_profile_balanced
        FaceDetectIntervalProfile.AGGRESSIVE -> R.string.face_detect_profile_aggressive
    }
)

/** 语音模型行：右值=当前模型名（无则「待下载」），已下载弹窗选择、未下载跳模型中心。 */
@Composable
private fun VoiceModelRow(
    title: String,
    tag: String,
    currentModel: String,
    onModelSelected: (String) -> Unit,
    onNavigateToModelCenter: () -> Unit,
    icon: ImageVector,
    iconBlockColor: Color
) {
    val context = LocalContext.current
    val downloadManager = remember { LlmModelDownloadManager(context) }
    var downloadedModels by remember { mutableStateOf<List<ModelConfig>>(emptyList()) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        downloadedModels = downloadManager.getDownloadedModels().filter { model ->
            model.tags.any { it.equals(tag, ignoreCase = true) } ||
                model.id.contains(tag, ignoreCase = true)
        }
    }

    val currentName = downloadedModels.find { it.id == currentModel }?.name
        ?: currentModel.ifBlank { null }
    SettingsListRow(
        title = title,
        onClick = {
            if (downloadedModels.isEmpty()) {
                onNavigateToModelCenter()
            } else {
                showDialog = true
            }
        },
        icon = icon,
        iconBlockColor = iconBlockColor,
        valueText = currentName ?: stringResource(R.string.model_pending_download)
    )
    if (showDialog) {
        SettingsSingleChoiceDialog(
            title = title,
            subtitle = stringResource(R.string.voice_model_dialog_subtitle),
            options = downloadedModels.map { it.id to it.name },
            isSelected = { it == currentModel },
            onSelected = { value -> onModelSelected(value as String) },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun taggerLabel(key: String): String = when (key) {
    "florence2_base" -> "Florence-2"
    "qwen3_vl_2b" -> "Qwen3-VL"
    else -> stringResource(R.string.tag_model_auto)
}


@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    PoLangTheme {
        SettingsContent(
            category = SettingsCategory.MAIN,
            themeMode = ThemeMode.SYSTEM,
            appLanguage = AppLanguage.ENGLISH,
            developerOptionsUnlocked = false,
            onUnlockDeveloperOptions = {},
            debugUiEnabled = true,
            showCameraInfoInPreview = false,
            showFaceDebugOverlay = false,
            showLogOverlay = false,
            faceDetectionLandmarkModeEnabled = true,
            adaptiveFaceDetectionIntervalEnabled = true,
            faceDetectIntervalProfile = FaceDetectIntervalProfile.BALANCED,
            debugShaderMode = 0,
            roiStageConfig = StageConfig.defaultRoi(),
            landmarkStageConfig = StageConfig.defaultLandmark(),
            onThemeModeSelected = {},
            onAppLanguageSelected = {},
            onDebugUiEnabledChange = {},
            onShowCameraInfoInPreviewChange = {},
            onShowFaceDebugOverlayChange = {},
            onShowLogOverlayChange = {},
            onFaceDetectionLandmarkModeEnabledChange = {},
            onAdaptiveFaceDetectionIntervalEnabledChange = {},
            onFaceDetectIntervalProfileSelected = {},
            onDebugShaderModeSelected = {},
            onRoiModelTypeSelected = {},
            onRoiDevicePreferenceSelected = {},
            onLandmarkModelTypeSelected = {},
            onLandmarkDevicePreferenceSelected = {},
            aiAgentRemoteModelConfigs = "",
            onAiAgentRemoteModelConfigsChange = {},
            aiAgentSelectedRemoteModel = "deepseek-v4-flash",
            onAiAgentSelectedRemoteModelChange = {},
            assistantPersona = AssistantPersona.DEFAULT,
            onAssistantPersonaSelected = {},
            autoExecutePlans = true,
            onAutoExecutePlansChange = {},
            jsEngineEnabled = false,
            onJsEngineEnabledChange = {},
            agentCameraAccessEnabled = true,
            onAgentCameraAccessChange = {},
            agentGalleryAccessEnabled = true,
            onAgentGalleryAccessChange = {},
            taggerModelKey = TaggerModelSelector.AUTO,
            onTaggerModelKeyChange = {},
            voiceCommandMode = VoiceCommandMode.DISABLED,
            onVoiceCommandModeChange = {},
            voiceEntryEnabled = false,
            onVoiceEntryEnabledChange = {},
            aiChatEntryEnabled = false,
            onAiChatEntryEnabledChange = {},
            mediaManageSilentTrashEnabled = false,
            onMediaManageSilentTrashEnabledChange = {},
            localAsrModel = "",
            onLocalAsrModelChange = {},
            localKwsModel = "",
            onLocalKwsModelChange = {},
            onNavigateToModelCenter = {},
            isModelDownloaded = { true },
            getModelId = { _, _ -> null },
            downloadModel = { _, _ -> },
            downloadStates = emptyMap(),
            allModels = emptyList(),
            logModuleConfig = LogModuleConfig.default(),
            onLogModuleConfigChange = {},
            onNavigateBack = {},
            onNavigateToDebug = {},
            onNavigateToJsBridge = {},
            onNavigateToSearchTest = {}
        )
    }
}


/**
 * 远程模型页（2026-08-17 v2 重设计，spec=specs/screens/refs/ardot settings/remote_models）：
 * 单组模型列表——供应商字母徽章（品牌色）+ 双行模型行（模型名+使用中胶囊 / 供应商·已配置）；
 * 选中行 primaryTint 高亮；点行=设为当前，行尾「⋯」弹动作（设为当前/删除）；组尾添加行。
 * 2026-08-21：添加行改为导航至「添加远程模型」供应商列表页（Screen.AddRemoteProvider），
 * 原 AddProviderModelDialog 弹窗流程下线（对话框本身仍被 SettingsAiAgent 遗留区块引用，暂保留）。
 */
@Composable
private fun RemoteModelsListSection(
    configsJson: String,
    onConfigsChange: (String) -> Unit,
    selectedModelId: String,
    onSelectedModelChange: (String) -> Unit,
    onAddProvider: () -> Unit
) {
    val configs = remember(configsJson) {
        if (configsJson.isNotBlank()) {
            RemoteModelConfigs.fromJson(configsJson)
        } else {
            RemoteModelConfigs()
        }
    }
    val configuredConfigs = configs.configs.filter { it.isConfigured }
    var actionModel by remember { mutableStateOf<RemoteModelConfig?>(null) }

    SettingsListSection {
        configuredConfigs.forEachIndexed { index, config ->
            if (index > 0) {
                SettingsListDivider()
            }
            RemoteModelRow(
                config = config,
                isSelected = config.uniqueKey == selectedModelId,
                onSelect = { onSelectedModelChange(config.uniqueKey) },
                onMore = { actionModel = config }
            )
        }
        if (configuredConfigs.isNotEmpty()) {
            SettingsListDivider()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SettingsTokens.listRowHeight)
                .clickable(onClick = onAddProvider)
                .padding(horizontal = SettingsTokens.listRowPaddingH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsTokens.rowElementGap)
        ) {
            Box(
                modifier = Modifier
                    .size(SettingsTokens.listIconBlockSize)
                    .clip(RoundedCornerShape(SettingsTokens.listIconBlockSize / 2))
                    .background(AppColors.vibrantGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(SettingsTokens.listIconInnerSize)
                )
            }
            Text(
                text = stringResource(R.string.add_model),
                fontSize = SettingsTokens.listTitleFontSize.value.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }

    actionModel?.let { model ->
        SettingsOptionSheetShell(
            title = model.modelId,
            subtitle = RemoteModelConfig.getProvider(model.providerId)?.displayName ?: model.providerId,
            onDismiss = { actionModel = null }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsOptionRow(
                    label = stringResource(R.string.remote_model_set_current),
                    selected = model.uniqueKey == selectedModelId,
                    onClick = {
                        onSelectedModelChange(model.uniqueKey)
                        actionModel = null
                    }
                )
                SettingsOptionRow(
                    label = stringResource(R.string.delete),
                    selected = false,
                    onClick = {
                        val updated = configs.removeConfig(model.uniqueKey)
                        onConfigsChange(RemoteModelConfigs.toJson(updated))
                        if (model.uniqueKey == selectedModelId) {
                            updated.configs.find { it.isConfigured }?.let { next ->
                                onSelectedModelChange(next.uniqueKey)
                            }
                        }
                        actionModel = null
                    }
                )
            }
        }
    }
}

/** 供应商字母徽章品牌色（与设计稿映射一致：DeepSeek 蓝 / Moonshot 紫 / OpenAI 绿 / Anthropic 橙 / 其他灰）。 */
private fun providerBadgeColor(providerId: String): Color {
    val id = providerId.lowercase()
    return when {
        id.startsWith("deepseek") -> AppColors.vibrantBlue
        id.startsWith("moonshot") || id.startsWith("kimi") -> Color(0xFF4F378B)
        id.startsWith("openai") -> AppColors.vibrantGreen
        id.startsWith("anthropic") -> Color(0xFFD97757)
        else -> Color(0xFF938F99)
    }
}

/** 远程模型行：字母徽章 + 双行文本（模型名+可选「使用中」胶囊 / 供应商·已配置）+ 行尾 ⋯。 */
@Composable
private fun RemoteModelRow(
    config: RemoteModelConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onMore: () -> Unit
) {
    val providerName = RemoteModelConfig.getProvider(config.providerId)?.displayName
        ?: stringResource(R.string.provider_custom)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingsTokens.rowHeightWithSubtitle)
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onSelect)
            .padding(start = SettingsTokens.listRowPaddingH, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsTokens.rowElementGap)
    ) {
        Box(
            modifier = Modifier
                .size(SettingsTokens.listIconBlockSize)
                .clip(RoundedCornerShape(SettingsTokens.listIconBlockSize / 2))
                .background(providerBadgeColor(config.providerId)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = providerName.first().uppercase(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = config.modelId,
                    fontSize = SettingsTokens.listTitleFontSize.value.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.model_in_use),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            Text(
                text = "$providerName · ${stringResource(R.string.remote_model_configured)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SettingsTokens.rowChevronAlpha),
            modifier = Modifier
                .size(SettingsTokens.rowChevronSize)
                .clickable(onClick = onMore)
        )
    }
}
