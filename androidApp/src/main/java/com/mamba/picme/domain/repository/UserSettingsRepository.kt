package com.mamba.picme.domain.repository

import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.domain.model.CameraMemoryState
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.domain.model.StageConfig
import com.mamba.picme.domain.model.ThemeMode
import com.mamba.picme.domain.model.VoiceCommandMode
import kotlinx.coroutines.flow.Flow
import com.mamba.picme.domain.model.LogModuleConfig

/**
 * 用户偏好设置仓储接口（Domain 层契约）
 *
 * Features 层应依赖此接口，而非直接依赖 data 层的 UserPreferencesRepository。
 * 实现类：data/preferences/UserPreferencesRepository（通过 DI 注入）。
 */
interface UserSettingsRepository {

    // ── 主题 ──────────────────────────────────────────────
    val themeModeFlow: Flow<ThemeMode>
    suspend fun updateThemeMode(mode: ThemeMode)

    // ── 语言 ──────────────────────────────────────────────
    val appLanguageFlow: Flow<AppLanguage>
    fun getAppLanguageBlocking(): AppLanguage
    suspend fun updateAppLanguage(language: AppLanguage)

    // ── 美颜引擎策略 ───────────────────────────────────────
    val beautyStrategyFlow: Flow<BeautyStrategy>
    fun getBeautyStrategyBlocking(): BeautyStrategy
    suspend fun updateBeautyStrategy(strategy: BeautyStrategy)

    // ── GL 引擎回退与恢复 ──────────────────────────────────
    val glEngineRecoveryAvailableAtFlow: Flow<Long>
    suspend fun persistGlEngineFallback(cooldownMs: Long)
    suspend fun triggerManualGlEngineRecovery()
    suspend fun clearGlEngineRecoveryCooldown()

    // ── 开发者选项入口解锁（版本号连点解锁后持久化）──────────
    val developerOptionsUnlockedFlow: Flow<Boolean>
    suspend fun updateDeveloperOptionsUnlocked(unlocked: Boolean)

    // ── 调试开关 ───────────────────────────────────────────
    val debugUiEnabledFlow: Flow<Boolean>
    suspend fun updateDebugUiEnabled(enabled: Boolean)

    val showCameraInfoInPreviewFlow: Flow<Boolean>
    suspend fun updateShowCameraInfoInPreview(show: Boolean)

    val showFaceDebugOverlayFlow: Flow<Boolean>
    suspend fun updateShowFaceDebugOverlay(show: Boolean)

    val showLogOverlayFlow: Flow<Boolean>
    suspend fun updateShowLogOverlay(show: Boolean)

    // ── Shader 调试模式 ────────────────────────────────────
    val debugShaderModeFlow: Flow<Int>
    suspend fun updateDebugShaderMode(mode: Int)

    // ── 人脸检测 ───────────────────────────────────────────
    val faceDetectionEngineModeFlow: Flow<FaceDetectionEngineMode>
    suspend fun updateFaceDetectionEngineMode(mode: FaceDetectionEngineMode)

    val faceDetectionLandmarkModeFlow: Flow<Boolean>
    suspend fun updateFaceDetectionLandmarkMode(enabled: Boolean)

    val adaptiveFaceDetectionIntervalEnabledFlow: Flow<Boolean>
    suspend fun updateAdaptiveFaceDetectionIntervalEnabled(enabled: Boolean)

    val faceDetectIntervalProfileFlow: Flow<FaceDetectIntervalProfile>
    suspend fun updateFaceDetectIntervalProfile(profile: FaceDetectIntervalProfile)

    // ── 阶段独立配置（ROI / Landmark）────────────────────────
    val roiStageConfigFlow: Flow<StageConfig>
    suspend fun updateRoiStageConfig(config: StageConfig)

    val landmarkStageConfigFlow: Flow<StageConfig>
    suspend fun updateLandmarkStageConfig(config: StageConfig)

    // ── AI Agent ────────────────────────────────────────────
    val aiAgentModeFlow: Flow<AiAgentMode>
    suspend fun updateAiAgentMode(mode: AiAgentMode)

    val assistantPersonaFlow: Flow<AssistantPersona>
    suspend fun updateAssistantPersona(persona: AssistantPersona)

    val aiAgentPrivacyLevelFlow: Flow<AiAgentPrivacyLevel>
    suspend fun updateAiAgentPrivacyLevel(level: AiAgentPrivacyLevel)

    // ── TAG 生成 ────────────────────────────────────────────
    val tagGenerationUseOpencl: Flow<Boolean>
    suspend fun updateTagGenerationUseOpencl(enabled: Boolean)

    /** 相册打标模型 key（AUTO / florence2_base / qwen3_vl_2b；默认 AUTO，由 TaggerModelSelector 解析为首选 Florence-2） */
    val taggerModelKeyFlow: Flow<String>
    suspend fun updateTaggerModelKey(key: String)
    fun getTaggerModelKeyBlocking(): String

    val openClDegradedDevices: Flow<String>
    suspend fun updateOpenClDegradedDevices(devicesJson: String)

    // ── 模型预下载 ──────────────────────────────────────────
    /** WiFi 下静默预下载推荐模型（默认开启）。 */
    val autoDownloadRecommendedOnWifiFlow: Flow<Boolean>
    suspend fun updateAutoDownloadRecommendedOnWifi(enabled: Boolean)

    // ── 远程模型配置（供应商维度） ────────────────────────────────
    val aiAgentRemoteModelConfigsFlow: Flow<String>
    suspend fun updateAiAgentRemoteModelConfigs(configsJson: String)

    val aiAgentSelectedRemoteModelFlow: Flow<String>
    suspend fun updateAiAgentSelectedRemoteModel(modelId: String)

    // ── 自动执行计划开关（仅持久化，chat 层暂未消费） ────────
    val autoExecutePlansEnabledFlow: Flow<Boolean>
    suspend fun updateAutoExecutePlansEnabled(enabled: Boolean)

    // ── 沙盒与权限开关（仅持久化，Agent/能力层消费待接入） ──────
    /** 允许 Agent 执行 JS 沙盒脚本（默认关）。 */
    val jsEngineEnabledFlow: Flow<Boolean>
    suspend fun updateJsEngineEnabled(enabled: Boolean)

    /** 允许 Agent 访问相机能力（默认开）。 */
    val agentCameraAccessEnabledFlow: Flow<Boolean>
    suspend fun updateAgentCameraAccessEnabled(enabled: Boolean)

    /** 允许 Agent 访问相册能力（默认开）。 */
    val agentGalleryAccessEnabledFlow: Flow<Boolean>
    suspend fun updateAgentGalleryAccessEnabled(enabled: Boolean)

    // ── 端侧文本 LLM 残留清理（一次性迁移标志） ─────────────
    /** qwen3_5_2b 模型目录是否已一次性清理（true 后不再重复执行） */
    val localTextLlmCleanedFlow: Flow<Boolean>
    suspend fun markLocalTextLlmCleaned()

    // ── Cloudflare AI Gateway Token ─────────────────────────
    val cloudflareGatewayTokenFlow: Flow<String>
    suspend fun updateCloudflareGatewayToken(token: String)

    // ── 语音控制 ────────────────────────────────────────────
    val voiceCommandModeFlow: Flow<VoiceCommandMode>
    suspend fun updateVoiceCommandMode(mode: VoiceCommandMode)

    /** 相机页语音入口（悬浮 FAB）是否显示，默认 false（语音能力为非刚需，默认收敛） */
    val voiceEntryEnabledFlow: Flow<Boolean>
    suspend fun updateVoiceEntryEnabled(enabled: Boolean)

    /** 相机页 AI 对话入口（悬浮 FAB）是否显示，默认 false（2026-08-19 语音/AI 悬浮入口全面默认隐藏） */
    val aiChatEntryEnabledFlow: Flow<Boolean>
    suspend fun updateAiChatEntryEnabled(enabled: Boolean)

    /**
     * 「删除不再询问」开关，默认 false。开关开 + 持 MANAGE_MEDIA（API 31+）时
     * 回收站删除直写 IS_TRASHED 静默执行（零系统弹框）；否则维持 createTrashRequest 系统授权框。
     */
    val mediaManageSilentTrashFlow: Flow<Boolean>
    suspend fun updateMediaManageSilentTrash(enabled: Boolean)

    val localAsrModelFlow: Flow<String>
    suspend fun updateLocalAsrModel(modelId: String)

    val localKwsModelFlow: Flow<String>
    suspend fun updateLocalKwsModel(modelId: String)

    // ── 相机参数记忆 ──────────────────────────────────────────
    val cameraMemoryStateFlow: Flow<CameraMemoryState>
    suspend fun updateCameraMemoryState(state: CameraMemoryState)
    suspend fun resetCameraMemoryState()

    // ── 日志模块配置 ──────────────────────────────────────────
    val logModuleConfigFlow: Flow<LogModuleConfig>
    suspend fun updateLogModuleConfig(config: LogModuleConfig)

    // ── Chat 输入模式记忆 ────────────────────────────────────
    val chatInputModeFlow: Flow<String>
    suspend fun updateChatInputMode(mode: String)

    // ── Chat 当前会话记忆 ────────────────────────────────────
    val chatCurrentSessionIdFlow: Flow<String>
    suspend fun updateChatCurrentSessionId(sessionId: String)

    // ── 飞书远程控制 ──────────────────────────────────────────
    val feishuAppIdFlow: Flow<String>
    val feishuAppSecretFlow: Flow<String>
    suspend fun updateFeishuAppId(appId: String)
    suspend fun updateFeishuAppSecret(appSecret: String)

    // ── 远程通道选择 + Telegram ───────────────────────────────
    val selectedRemoteChannelFlow: Flow<String>
    suspend fun updateSelectedRemoteChannel(type: String)
    val telegramBotTokenFlow: Flow<String>
    val telegramAllowedChatIdFlow: Flow<String>
    suspend fun updateTelegramConfig(botToken: String, allowedChatId: String)

    // ── 服务端邮箱认证 ──────────────────────────────────────────
    val serverAuthTokenFlow: Flow<String>
    val serverAuthEmailFlow: Flow<String>
    suspend fun updateServerAuth(token: String, email: String)
    suspend fun clearServerAuth()

    // ── 访客（未注册）聊天引导 ──────────────────────────────────
    /** 访客模式下用户消息累计发送数（跨会话），注册成功后清零。 */
    val guestChatMessageCountFlow: Flow<Int>
    /** 自增并返回自增后的累计值。 */
    suspend fun incrementGuestChatMessageCount(): Int
    suspend fun resetGuestChatMessageCount()
}


