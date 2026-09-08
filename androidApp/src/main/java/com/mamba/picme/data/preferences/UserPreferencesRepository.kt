package com.mamba.picme.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mamba.picme.BuildConfig
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.core.common.Logger
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.domain.model.CameraAspectRatioMode
import com.mamba.picme.domain.model.CameraGridMode
import com.mamba.picme.domain.model.CameraMemoryState
import com.mamba.picme.domain.model.CameraSceneMode
import com.mamba.picme.domain.model.DetectionModelType
import com.mamba.picme.domain.model.DetectionStage
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.domain.model.InferenceDevicePreference
import com.mamba.picme.domain.model.InferenceEngineType
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.model.StageConfig
import com.mamba.picme.domain.model.ThemeMode
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.tag.TaggerModelSelector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.IOException
import com.mamba.picme.domain.model.LogModuleConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs

// 枚举已迁移至 domain.model.UserPreferences，调用方请从 com.mamba.picme.domain.model 导入

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) : UserSettingsRepository {

    private object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val BEAUTY_STRATEGY = stringPreferencesKey("beauty_strategy")
        val DEVELOPER_OPTIONS_UNLOCKED = booleanPreferencesKey("developer_options_unlocked")
        val DEBUG_UI_ENABLED = booleanPreferencesKey("debug_ui_enabled")
        val SHOW_CAMERA_INFO_IN_PREVIEW = booleanPreferencesKey("show_camera_info_in_preview")
        val SHOW_FACE_DEBUG_OVERLAY = booleanPreferencesKey("show_face_debug_overlay")
        val SHOW_LOG_OVERLAY = booleanPreferencesKey("show_log_overlay")
        val FACE_DETECTION_ENGINE_MODE = stringPreferencesKey("face_detection_engine_mode")
        val FACE_DETECTION_LANDMARK_MODE = booleanPreferencesKey("face_detection_landmark_mode")
        val ADAPTIVE_FACE_DETECTION_INTERVAL = booleanPreferencesKey("adaptive_face_detection_interval")
        val FACE_DETECT_INTERVAL_PROFILE = stringPreferencesKey("face_detect_interval_profile")
        val GL_ENGINE_RECOVERY_AVAILABLE_AT_MS = longPreferencesKey("gl_engine_recovery_available_at_ms")
        val DEBUG_SHADER_MODE = intPreferencesKey("debug_shader_mode")

        // 阶段独立配置（ROI / Landmark）
        val ROI_MODEL_TYPE = stringPreferencesKey("roi_model_type")
        val ROI_ENGINE_TYPE = stringPreferencesKey("roi_engine_type")
        val ROI_DEVICE_PREFERENCE = stringPreferencesKey("roi_device_preference")

        val LANDMARK_MODEL_TYPE = stringPreferencesKey("landmark_model_type")
        val LANDMARK_ENGINE_TYPE = stringPreferencesKey("landmark_engine_type")
        val LANDMARK_DEVICE_PREFERENCE = stringPreferencesKey("landmark_device_preference")

        // AI Agent
        val AI_AGENT_MODE = stringPreferencesKey("ai_agent_mode")
        val AI_AGENT_PRIVACY_LEVEL = stringPreferencesKey("ai_agent_privacy_level")
        val ASSISTANT_PERSONA = stringPreferencesKey("assistant_persona")

        // TAG 生成
        val TAG_GENERATION_USE_OPENCL = booleanPreferencesKey("tag_generation_use_opencl")
        val TAGGER_MODEL_KEY = stringPreferencesKey("tagger_model_key")
        val OPENCL_DEGRADED_DEVICES = stringPreferencesKey("opencl_degraded_devices")
        val AUTO_DOWNLOAD_RECOMMENDED_ON_WIFI = booleanPreferencesKey("auto_download_recommended_on_wifi")

        // 远程模型配置（供应商维度 JSON + 当前选中模型ID）
        val AI_AGENT_REMOTE_MODEL_CONFIGS = stringPreferencesKey("ai_agent_remote_model_configs_v2")
        val AI_AGENT_SELECTED_REMOTE_MODEL = stringPreferencesKey("ai_agent_selected_remote_model")

        // 端侧文本 LLM（qwen3_5_2b）一次性清理标志：删除 filesDir/llm_models/qwen3_5_2b/ 后置位
        val LOCAL_TEXT_LLM_CLEANED = booleanPreferencesKey("local_text_llm_cleaned")

        // 自动执行计划开关
        val AUTO_EXECUTE_PLANS = booleanPreferencesKey("auto_execute_plans")
        val JS_ENGINE_ENABLED = booleanPreferencesKey("js_engine_enabled")
        val AGENT_CAMERA_ACCESS_ENABLED = booleanPreferencesKey("agent_camera_access_enabled")
        val AGENT_GALLERY_ACCESS_ENABLED = booleanPreferencesKey("agent_gallery_access_enabled")

        // Cloudflare AI Gateway Token
        val CLOUDFLARE_GATEWAY_TOKEN = stringPreferencesKey("cloudflare_gateway_token")

        // 语音控制
        val VOICE_COMMAND_MODE = stringPreferencesKey("voice_command_mode")
        val LOCAL_ASR_MODEL = stringPreferencesKey("local_asr_model")
        val LOCAL_KWS_MODEL = stringPreferencesKey("local_kws_model")
        val VOICE_ENTRY_ENABLED = booleanPreferencesKey("voice_entry_enabled")
        // 相机页 AI 对话入口（悬浮 FAB），默认关闭（2026-08-19 语音/AI 悬浮入口全面默认隐藏）
        val AI_CHAT_ENTRY_ENABLED = booleanPreferencesKey("ai_chat_entry_enabled")

        // 「删除不再询问」：持 MANAGE_MEDIA 后回收站删除静默执行（零系统弹框），默认关闭
        val MEDIA_MANAGE_SILENT_TRASH = booleanPreferencesKey("media_manage_silent_trash")

        // 相机参数记忆
        val CAMERA_MEMORY_USE_FRONT_CAMERA = booleanPreferencesKey("camera_memory_use_front_camera")
        val CAMERA_MEMORY_CAPTURE_MODE = stringPreferencesKey("camera_memory_capture_mode")
        val CAMERA_MEMORY_SELECTED_FILTER = stringPreferencesKey("camera_memory_selected_filter")
        val CAMERA_MEMORY_SELECTED_STYLE_FILTER = stringPreferencesKey("camera_memory_selected_style_filter")

        val CAMERA_MEMORY_BEAUTY_ENABLED = booleanPreferencesKey("camera_memory_beauty_enabled")
        val CAMERA_MEMORY_BEAUTY_SMOOTHING = floatPreferencesKey("camera_memory_beauty_smoothing")
        val CAMERA_MEMORY_BEAUTY_WHITENING = floatPreferencesKey("camera_memory_beauty_whitening")
        val CAMERA_MEMORY_BEAUTY_SLIM_FACE = floatPreferencesKey("camera_memory_beauty_slim_face")
        val CAMERA_MEMORY_BEAUTY_BIG_EYES = floatPreferencesKey("camera_memory_beauty_big_eyes")
        val CAMERA_MEMORY_BEAUTY_LIP_COLOR = floatPreferencesKey("camera_memory_beauty_lip_color")
        val CAMERA_MEMORY_BEAUTY_LIP_COLOR_INDEX = intPreferencesKey("camera_memory_beauty_lip_color_index")
        val CAMERA_MEMORY_BEAUTY_BLUSH = floatPreferencesKey("camera_memory_beauty_blush")
        val CAMERA_MEMORY_BEAUTY_BLUSH_COLOR_FAMILY = intPreferencesKey("camera_memory_beauty_blush_color_family")
        val CAMERA_MEMORY_BEAUTY_BODY_ENHANCEMENT = floatPreferencesKey("camera_memory_beauty_body_enhancement")
        val CAMERA_MEMORY_BEAUTY_LEG_EXTENSION = floatPreferencesKey("camera_memory_beauty_leg_extension")
        val CAMERA_MEMORY_BEAUTY_EXPOSURE = floatPreferencesKey("camera_memory_beauty_exposure")
        val CAMERA_MEMORY_BEAUTY_CONTRAST = floatPreferencesKey("camera_memory_beauty_contrast")
        val CAMERA_MEMORY_BEAUTY_SATURATION = floatPreferencesKey("camera_memory_beauty_saturation")
        val CAMERA_MEMORY_BEAUTY_TEMPERATURE = floatPreferencesKey("camera_memory_beauty_temperature")
        val CAMERA_MEMORY_BEAUTY_TINT = floatPreferencesKey("camera_memory_beauty_tint")
        val CAMERA_MEMORY_BEAUTY_BRIGHTNESS = floatPreferencesKey("camera_memory_beauty_brightness")
        val CAMERA_MEMORY_BEAUTY_RED_ADJUSTMENT = floatPreferencesKey("camera_memory_beauty_red_adjustment")
        val CAMERA_MEMORY_BEAUTY_GREEN_ADJUSTMENT = floatPreferencesKey("camera_memory_beauty_green_adjustment")
        val CAMERA_MEMORY_BEAUTY_BLUE_ADJUSTMENT = floatPreferencesKey("camera_memory_beauty_blue_adjustment")

        val CAMERA_MEMORY_ASPECT_RATIO = stringPreferencesKey("camera_memory_aspect_ratio")
        val CAMERA_MEMORY_ZOOM_RATIO = floatPreferencesKey("camera_memory_zoom_ratio")
        val CAMERA_MEMORY_EXPOSURE_COMPENSATION = intPreferencesKey("camera_memory_exposure_compensation")
        val CAMERA_MEMORY_WHITE_BALANCE_MODE = intPreferencesKey("camera_memory_white_balance_mode")
        val CAMERA_MEMORY_SCENE_MODE = stringPreferencesKey("camera_memory_scene_mode")
        val CAMERA_MEMORY_GRID_MODE = stringPreferencesKey("camera_memory_grid_mode")

        // 日志模块配置
        val LOG_MODULE_CONFIG = stringPreferencesKey("log_module_config")

        // Chat 输入模式记忆（文字/语音）
        val CHAT_INPUT_MODE = stringPreferencesKey("chat_input_mode")

        // Chat 当前会话 ID 记忆（恢复上次打开的对话）
        val CHAT_CURRENT_SESSION_ID = stringPreferencesKey("chat_current_session_id")

        // 飞书远程控制
        val FEISHU_APP_ID = stringPreferencesKey("feishu_app_id")
        val FEISHU_APP_SECRET = stringPreferencesKey("feishu_app_secret")

        // 远程通道选择 + Telegram
        val SELECTED_REMOTE_CHANNEL = stringPreferencesKey("selected_remote_channel")
        val TELEGRAM_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        val TELEGRAM_ALLOWED_CHAT_ID = stringPreferencesKey("telegram_allowed_chat_id")

        // 服务端邮箱认证
        val SERVER_AUTH_TOKEN = stringPreferencesKey("server_auth_token")
        val SERVER_AUTH_EMAIL = stringPreferencesKey("server_auth_email")

        // 访客（未注册）聊天消息累计计数，用于渐进式注册引导
        val GUEST_CHAT_MESSAGE_COUNT = intPreferencesKey("guest_chat_message_count")
    }

    private fun parseMediaType(value: String?): MediaType {
        return runCatching { value?.let { MediaType.valueOf(it) } }
            .getOrNull() ?: MediaType.PHOTO
    }

    private fun parseFilterType(value: String?): FilterType {
        return runCatching { value?.let { FilterType.valueOf(it) } }
            .getOrNull() ?: FilterType.NONE
    }

    private fun parseStyleFilter(value: String?): StyleFilter {
        return runCatching { value?.let { StyleFilter.valueOf(it) } }
            .getOrNull() ?: StyleFilter.NONE
    }

    private fun parseCameraAspectRatioMode(value: String?): CameraAspectRatioMode {
        return runCatching { value?.let { CameraAspectRatioMode.valueOf(it) } }
            .getOrNull() ?: CameraAspectRatioMode.FULL
    }

    private fun parseCameraSceneMode(value: String?): CameraSceneMode {
        return runCatching { value?.let { CameraSceneMode.valueOf(it) } }
            .getOrNull() ?: CameraSceneMode.NONE
    }

    private fun parseCameraGridMode(value: String?): CameraGridMode {
        return runCatching { value?.let { CameraGridMode.valueOf(it) } }
            .getOrNull() ?: CameraGridMode.NONE
    }

    override val themeModeFlow: Flow<ThemeMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val themeName = preferences[PreferencesKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name
            ThemeMode.valueOf(themeName)
        }

    override val appLanguageFlow: Flow<AppLanguage> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val langName = preferences[PreferencesKeys.APP_LANGUAGE] ?: AppLanguage.SYSTEM.name
            AppLanguage.valueOf(langName)
        }

    override fun getAppLanguageBlocking(): AppLanguage = runBlocking {
        try {
            val preferences = context.dataStore.data.first()
            val langName = preferences[PreferencesKeys.APP_LANGUAGE] ?: AppLanguage.SYSTEM.name
            AppLanguage.valueOf(langName)
        } catch (_: Exception) {
            AppLanguage.SYSTEM
        }
    }

    override suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    override suspend fun updateAppLanguage(language: AppLanguage) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = language.name
        }
    }

    override val beautyStrategyFlow: Flow<BeautyStrategy> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val strategyName = preferences[PreferencesKeys.BEAUTY_STRATEGY] ?: BeautyStrategy.BIG_BEAUTY.name
            BeautyStrategy.valueOf(strategyName)
        }

    override fun getBeautyStrategyBlocking(): BeautyStrategy = runBlocking {
        try {
            val preferences = context.dataStore.data.first()
            val strategyName = preferences[PreferencesKeys.BEAUTY_STRATEGY] ?: BeautyStrategy.BIG_BEAUTY.name
            BeautyStrategy.valueOf(strategyName)
        } catch (_: Exception) {
            BeautyStrategy.BIG_BEAUTY
        }
    }

    override suspend fun updateBeautyStrategy(strategy: BeautyStrategy) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BEAUTY_STRATEGY] = strategy.name
        }
    }

    override val glEngineRecoveryAvailableAtFlow: Flow<Long> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] ?: 0L
        }

    override suspend fun persistGlEngineFallback(cooldownMs: Long) {
        val nowMs = System.currentTimeMillis()
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = nowMs + cooldownMs
        }
    }

    override suspend fun triggerManualGlEngineRecovery() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BEAUTY_STRATEGY] = BeautyStrategy.BIG_BEAUTY.name
            preferences[PreferencesKeys.GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = 0L
        }
    }

    override suspend fun clearGlEngineRecoveryCooldown() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = 0L
        }
    }

    override val developerOptionsUnlockedFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.DEVELOPER_OPTIONS_UNLOCKED] ?: false
        }

    override suspend fun updateDeveloperOptionsUnlocked(unlocked: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEVELOPER_OPTIONS_UNLOCKED] = unlocked
        }
    }

    override val debugUiEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.DEBUG_UI_ENABLED] ?: false
        }

    override suspend fun updateDebugUiEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEBUG_UI_ENABLED] = enabled
        }
    }

    override val showCameraInfoInPreviewFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SHOW_CAMERA_INFO_IN_PREVIEW] ?: false
        }

    override suspend fun updateShowCameraInfoInPreview(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_CAMERA_INFO_IN_PREVIEW] = show
        }
    }

    override val showFaceDebugOverlayFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SHOW_FACE_DEBUG_OVERLAY] ?: false
        }

    override suspend fun updateShowFaceDebugOverlay(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_FACE_DEBUG_OVERLAY] = show
        }
    }

    override val showLogOverlayFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SHOW_LOG_OVERLAY] ?: false
        }

    override suspend fun updateShowLogOverlay(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_LOG_OVERLAY] = show
        }
    }

    override val faceDetectionEngineModeFlow: Flow<FaceDetectionEngineMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val modeName = preferences[PreferencesKeys.FACE_DETECTION_ENGINE_MODE]
                ?: FaceDetectionEngineMode.MEDIAPIPE.name
            runCatching { FaceDetectionEngineMode.valueOf(modeName) }
                .getOrDefault(FaceDetectionEngineMode.MEDIAPIPE)
        }

    override suspend fun updateFaceDetectionEngineMode(mode: FaceDetectionEngineMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FACE_DETECTION_ENGINE_MODE] = mode.name
        }
    }

    override val debugShaderModeFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.DEBUG_SHADER_MODE] ?: 0
        }

    override suspend fun updateDebugShaderMode(mode: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEBUG_SHADER_MODE] = mode
        }
    }

    override val faceDetectionLandmarkModeFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.FACE_DETECTION_LANDMARK_MODE] ?: true
        }

    override suspend fun updateFaceDetectionLandmarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FACE_DETECTION_LANDMARK_MODE] = enabled
        }
    }

    override val adaptiveFaceDetectionIntervalEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.ADAPTIVE_FACE_DETECTION_INTERVAL] ?: true
        }

    override suspend fun updateAdaptiveFaceDetectionIntervalEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ADAPTIVE_FACE_DETECTION_INTERVAL] = enabled
        }
    }

    override val faceDetectIntervalProfileFlow: Flow<FaceDetectIntervalProfile> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val profileName = preferences[PreferencesKeys.FACE_DETECT_INTERVAL_PROFILE]
                ?: FaceDetectIntervalProfile.BALANCED.name
            FaceDetectIntervalProfile.valueOf(profileName)
        }

    override suspend fun updateFaceDetectIntervalProfile(profile: FaceDetectIntervalProfile) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FACE_DETECT_INTERVAL_PROFILE] = profile.name
        }
    }

    // ── 阶段独立配置（ROI / Landmark）────────────────────────

    private fun parseStageConfig(
        stage: DetectionStage,
        modelTypeName: String?,
        engineTypeName: String?,
        devicePreferenceName: String?
    ): StageConfig {
        val defaultConfig = when (stage) {
            DetectionStage.ROI -> StageConfig.defaultRoi()
            DetectionStage.LANDMARK -> StageConfig.defaultLandmark()
        }

        val modelType = runCatching {
            modelTypeName?.let { DetectionModelType.valueOf(it) }
        }.getOrNull() ?: defaultConfig.modelType

        val engineType = runCatching {
            engineTypeName?.let { InferenceEngineType.valueOf(it) }
        }.getOrNull() ?: defaultConfig.engineType

        val devicePreference = runCatching {
            devicePreferenceName?.let { InferenceDevicePreference.valueOf(it) }
        }.getOrNull() ?: defaultConfig.devicePreference

        return StageConfig(
            stage = stage,
            modelType = modelType,
            engineType = engineType,
            devicePreference = devicePreference
        )
    }

    override val roiStageConfigFlow: Flow<StageConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Logger.e("DataStore", "Failed to read ROI stage config, using default")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            parseStageConfig(
                stage = DetectionStage.ROI,
                modelTypeName = preferences[PreferencesKeys.ROI_MODEL_TYPE],
                engineTypeName = preferences[PreferencesKeys.ROI_ENGINE_TYPE],
                devicePreferenceName = preferences[PreferencesKeys.ROI_DEVICE_PREFERENCE]
            )
        }

    override suspend fun updateRoiStageConfig(config: StageConfig) {
        Logger.d("DataStore", "Updating ROI stage config: $config")
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ROI_MODEL_TYPE] = config.modelType.name
            preferences[PreferencesKeys.ROI_ENGINE_TYPE] = config.engineType.name
            preferences[PreferencesKeys.ROI_DEVICE_PREFERENCE] = config.devicePreference.name
        }
    }

    override val landmarkStageConfigFlow: Flow<StageConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Logger.e("DataStore", "Failed to read Landmark stage config, using default")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            parseStageConfig(
                stage = DetectionStage.LANDMARK,
                modelTypeName = preferences[PreferencesKeys.LANDMARK_MODEL_TYPE],
                engineTypeName = preferences[PreferencesKeys.LANDMARK_ENGINE_TYPE],
                devicePreferenceName = preferences[PreferencesKeys.LANDMARK_DEVICE_PREFERENCE]
            )
        }

    override suspend fun updateLandmarkStageConfig(config: StageConfig) {
        Logger.d("DataStore", "Updating Landmark stage config: $config")
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LANDMARK_MODEL_TYPE] = config.modelType.name
            preferences[PreferencesKeys.LANDMARK_ENGINE_TYPE] = config.engineType.name
            preferences[PreferencesKeys.LANDMARK_DEVICE_PREFERENCE] = config.devicePreference.name
        }
    }

    override val aiAgentModeFlow: Flow<AiAgentMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val modeName = preferences[PreferencesKeys.AI_AGENT_MODE] ?: AiAgentMode.REMOTE.name
            runCatching { AiAgentMode.valueOf(modeName) }
                .getOrDefault(AiAgentMode.REMOTE)
        }

    override suspend fun updateAiAgentMode(mode: AiAgentMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_MODE] = mode.name
        }
    }

    override val assistantPersonaFlow: Flow<AssistantPersona> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val personaName = preferences[PreferencesKeys.ASSISTANT_PERSONA] ?: AssistantPersona.DEFAULT.name
            runCatching { AssistantPersona.valueOf(personaName) }
                .getOrDefault(AssistantPersona.DEFAULT)
        }

    override suspend fun updateAssistantPersona(persona: AssistantPersona) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ASSISTANT_PERSONA] = persona.name
        }
    }

    override val aiAgentPrivacyLevelFlow: Flow<AiAgentPrivacyLevel> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val levelName = preferences[PreferencesKeys.AI_AGENT_PRIVACY_LEVEL]
                ?: AiAgentPrivacyLevel.STRICT.name
            runCatching { AiAgentPrivacyLevel.valueOf(levelName) }
                .getOrDefault(AiAgentPrivacyLevel.STRICT)
        }

    override suspend fun updateAiAgentPrivacyLevel(level: AiAgentPrivacyLevel) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_PRIVACY_LEVEL] = level.name
        }
    }

    override val tagGenerationUseOpencl: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.TAG_GENERATION_USE_OPENCL] ?: true
        }

    override suspend fun updateTagGenerationUseOpencl(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TAG_GENERATION_USE_OPENCL] = enabled
        }
    }

    override val taggerModelKeyFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            // 默认 AUTO（由 TaggerModelSelector 解析为首选 Florence-2）；用户可手动覆盖。
            preferences[PreferencesKeys.TAGGER_MODEL_KEY] ?: TaggerModelSelector.AUTO
        }

    override suspend fun updateTaggerModelKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TAGGER_MODEL_KEY] = key
        }
    }

    override fun getTaggerModelKeyBlocking(): String = runBlocking {
        try {
            context.dataStore.data.first()[PreferencesKeys.TAGGER_MODEL_KEY] ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    override val autoDownloadRecommendedOnWifiFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_DOWNLOAD_RECOMMENDED_ON_WIFI] ?: true
        }

    override suspend fun updateAutoDownloadRecommendedOnWifi(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_DOWNLOAD_RECOMMENDED_ON_WIFI] = enabled
        }
    }

    override val openClDegradedDevices: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.OPENCL_DEGRADED_DEVICES] ?: "[]"
        }

    override suspend fun updateOpenClDegradedDevices(devicesJson: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.OPENCL_DEGRADED_DEVICES] = devicesJson
        }
    }

    // ── 远程模型配置（多模型） ────────────────────────────────

    override val aiAgentRemoteModelConfigsFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_AGENT_REMOTE_MODEL_CONFIGS]
                ?: migrateOldRemoteConfigs(preferences)
                ?: ""
        }

    override suspend fun updateAiAgentRemoteModelConfigs(configsJson: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_REMOTE_MODEL_CONFIGS] = configsJson
        }
    }

    /**
     * 从旧版（v1 key）迁移远程模型配置。
     *
     * v1 key `ai_agent_remote_model_configs` 与 v2 同为 [RemoteModelConfigs] JSON 格式，
     * 这里只做 fromJson→toJson 归一化（丢弃坏数据），不做格式转换。
     * 历史上曾错误地转换为 ProviderConfigs 格式（`provider` 字段），与写入端
     * （设置页写 `providerId` 格式）和解析端都不一致，已随配置链收口移除。
     */
    private fun migrateOldRemoteConfigs(preferences: Preferences): String? {
        val oldJson = preferences[stringPreferencesKey("ai_agent_remote_model_configs")] ?: return null
        return try {
            val oldConfigs = RemoteModelConfigs.fromJson(oldJson)
            if (oldConfigs.configs.isEmpty()) null else RemoteModelConfigs.toJson(oldConfigs)
        } catch (_: Exception) {
            null
        }
    }

    override val aiAgentSelectedRemoteModelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_AGENT_SELECTED_REMOTE_MODEL] ?: "deepseek-v4-flash"
        }

    override suspend fun updateAiAgentSelectedRemoteModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_AGENT_SELECTED_REMOTE_MODEL] = modelId
        }
    }

    override val autoExecutePlansEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_EXECUTE_PLANS] ?: true
        }

    override suspend fun updateAutoExecutePlansEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_EXECUTE_PLANS] = enabled
        }
    }

    override val jsEngineEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.JS_ENGINE_ENABLED] ?: false
        }

    override suspend fun updateJsEngineEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.JS_ENGINE_ENABLED] = enabled
        }
    }

    override val agentCameraAccessEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AGENT_CAMERA_ACCESS_ENABLED] ?: true
        }

    override suspend fun updateAgentCameraAccessEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AGENT_CAMERA_ACCESS_ENABLED] = enabled
        }
    }

    override val agentGalleryAccessEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AGENT_GALLERY_ACCESS_ENABLED] ?: true
        }

    override suspend fun updateAgentGalleryAccessEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AGENT_GALLERY_ACCESS_ENABLED] = enabled
        }
    }

    override val localTextLlmCleanedFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.LOCAL_TEXT_LLM_CLEANED] ?: false
        }

    override suspend fun markLocalTextLlmCleaned() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCAL_TEXT_LLM_CLEANED] = true
        }
    }

    // ── Cloudflare AI Gateway Token ─────────────────────────

    override val cloudflareGatewayTokenFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CLOUDFLARE_GATEWAY_TOKEN] ?: ""
        }

    override suspend fun updateCloudflareGatewayToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CLOUDFLARE_GATEWAY_TOKEN] = token
        }
    }

    override val voiceCommandModeFlow: Flow<VoiceCommandMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val modeName = preferences[PreferencesKeys.VOICE_COMMAND_MODE]
                ?: VoiceCommandMode.DISABLED.name
            runCatching { VoiceCommandMode.valueOf(modeName) }
                .getOrDefault(VoiceCommandMode.DISABLED)
        }

    override suspend fun updateVoiceCommandMode(mode: VoiceCommandMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOICE_COMMAND_MODE] = mode.name
        }
    }

    override val voiceEntryEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.VOICE_ENTRY_ENABLED] ?: false
        }

    override suspend fun updateVoiceEntryEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOICE_ENTRY_ENABLED] = enabled
        }
    }

    override val aiChatEntryEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AI_CHAT_ENTRY_ENABLED] ?: false
        }

    override suspend fun updateAiChatEntryEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_CHAT_ENTRY_ENABLED] = enabled
        }
    }

    override val mediaManageSilentTrashFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MEDIA_MANAGE_SILENT_TRASH] ?: false
        }

    override suspend fun updateMediaManageSilentTrash(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MEDIA_MANAGE_SILENT_TRASH] = enabled
        }
    }

    override val localAsrModelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.LOCAL_ASR_MODEL] ?: ""
        }

    override suspend fun updateLocalAsrModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCAL_ASR_MODEL] = modelId
        }
    }

    override val localKwsModelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.LOCAL_KWS_MODEL] ?: ""
        }

    override suspend fun updateLocalKwsModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCAL_KWS_MODEL] = modelId
        }
    }

    override val cameraMemoryStateFlow: Flow<CameraMemoryState> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val selectedFilter = parseFilterType(preferences[PreferencesKeys.CAMERA_MEMORY_SELECTED_FILTER])
            val selectedStyleFilter = parseStyleFilter(preferences[PreferencesKeys.CAMERA_MEMORY_SELECTED_STYLE_FILTER])
            val beautySettings = BeautySettings(
                enabled = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_ENABLED] ?: false,
                smoothing = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SMOOTHING] ?: 0f,
                whitening = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_WHITENING] ?: 0f,
                slimFace = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SLIM_FACE] ?: 0f,
                bigEyes = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BIG_EYES] ?: 0f,
                lipColor = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LIP_COLOR] ?: BeautySettings.DEFAULT_LIP_COLOR,
                lipColorIndex = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LIP_COLOR_INDEX] ?: 0,
                blush = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUSH] ?: BeautySettings.DEFAULT_BLUSH,
                blushColorFamily = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUSH_COLOR_FAMILY] ?: 0,
                bodyEnhancement = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BODY_ENHANCEMENT] ?: 0f,
                legExtension = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LEG_EXTENSION] ?: 0f,
                exposure = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_EXPOSURE] ?: 0f,
                contrast = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_CONTRAST] ?: 50f,
                saturation = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SATURATION] ?: 100f,
                temperature = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_TEMPERATURE] ?: 5000f,
                tint = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_TINT] ?: 0f,
                brightness = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BRIGHTNESS] ?: 0f,
                redAdjustment = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_RED_ADJUSTMENT] ?: 100f,
                greenAdjustment = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_GREEN_ADJUSTMENT] ?: 100f,
                blueAdjustment = preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUE_ADJUSTMENT] ?: 100f,
                colorFilter = selectedFilter,
                styleFilter = selectedStyleFilter
            )

            CameraMemoryState(
                useFrontCamera = preferences[PreferencesKeys.CAMERA_MEMORY_USE_FRONT_CAMERA] ?: false,
                captureMode = parseMediaType(preferences[PreferencesKeys.CAMERA_MEMORY_CAPTURE_MODE]),
                selectedFilter = selectedFilter,
                selectedStyleFilter = selectedStyleFilter,
                beautySettings = beautySettings,
                aspectRatio = parseCameraAspectRatioMode(preferences[PreferencesKeys.CAMERA_MEMORY_ASPECT_RATIO]),
                zoomRatio = preferences[PreferencesKeys.CAMERA_MEMORY_ZOOM_RATIO] ?: 1f,
                exposureCompensation = preferences[PreferencesKeys.CAMERA_MEMORY_EXPOSURE_COMPENSATION] ?: 0,
                whiteBalanceMode = preferences[PreferencesKeys.CAMERA_MEMORY_WHITE_BALANCE_MODE] ?: 0,
                sceneMode = parseCameraSceneMode(preferences[PreferencesKeys.CAMERA_MEMORY_SCENE_MODE]),
                gridMode = parseCameraGridMode(preferences[PreferencesKeys.CAMERA_MEMORY_GRID_MODE])
            )
        }

    override suspend fun updateCameraMemoryState(state: CameraMemoryState) {
        val resolvedBeautySettings = state.beautySettings.copy(
            colorFilter = state.selectedFilter,
            styleFilter = state.selectedStyleFilter
        )

        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CAMERA_MEMORY_USE_FRONT_CAMERA] = state.useFrontCamera
            preferences[PreferencesKeys.CAMERA_MEMORY_CAPTURE_MODE] = state.captureMode.name
            preferences[PreferencesKeys.CAMERA_MEMORY_SELECTED_FILTER] = state.selectedFilter.name
            preferences[PreferencesKeys.CAMERA_MEMORY_SELECTED_STYLE_FILTER] = state.selectedStyleFilter.name

            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_ENABLED] = resolvedBeautySettings.enabled
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SMOOTHING] = resolvedBeautySettings.smoothing
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_WHITENING] = resolvedBeautySettings.whitening
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SLIM_FACE] = resolvedBeautySettings.slimFace
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BIG_EYES] = resolvedBeautySettings.bigEyes
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LIP_COLOR] = resolvedBeautySettings.lipColor
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LIP_COLOR_INDEX] = resolvedBeautySettings.lipColorIndex
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUSH] = resolvedBeautySettings.blush
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUSH_COLOR_FAMILY] = resolvedBeautySettings.blushColorFamily
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BODY_ENHANCEMENT] = resolvedBeautySettings.bodyEnhancement
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_LEG_EXTENSION] = resolvedBeautySettings.legExtension
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_EXPOSURE] = resolvedBeautySettings.exposure
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_CONTRAST] = resolvedBeautySettings.contrast
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_SATURATION] = resolvedBeautySettings.saturation
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_TEMPERATURE] = resolvedBeautySettings.temperature
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_TINT] = resolvedBeautySettings.tint
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BRIGHTNESS] = resolvedBeautySettings.brightness
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_RED_ADJUSTMENT] = resolvedBeautySettings.redAdjustment
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_GREEN_ADJUSTMENT] = resolvedBeautySettings.greenAdjustment
            preferences[PreferencesKeys.CAMERA_MEMORY_BEAUTY_BLUE_ADJUSTMENT] = resolvedBeautySettings.blueAdjustment

            preferences[PreferencesKeys.CAMERA_MEMORY_ASPECT_RATIO] = state.aspectRatio.name
            preferences[PreferencesKeys.CAMERA_MEMORY_ZOOM_RATIO] = state.zoomRatio
            preferences[PreferencesKeys.CAMERA_MEMORY_EXPOSURE_COMPENSATION] = state.exposureCompensation
            preferences[PreferencesKeys.CAMERA_MEMORY_WHITE_BALANCE_MODE] = state.whiteBalanceMode
            preferences[PreferencesKeys.CAMERA_MEMORY_SCENE_MODE] = state.sceneMode.name
            preferences[PreferencesKeys.CAMERA_MEMORY_GRID_MODE] = state.gridMode.name
        }
    }

    override suspend fun resetCameraMemoryState() {
        updateCameraMemoryState(CameraMemoryState())
    }

    override val logModuleConfigFlow: Flow<LogModuleConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val configJson = preferences[PreferencesKeys.LOG_MODULE_CONFIG]
            if (configJson != null) {
                LogModuleConfig.fromJson(configJson)
            } else {
                LogModuleConfig.default()
            }
        }

    override suspend fun updateLogModuleConfig(config: LogModuleConfig) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOG_MODULE_CONFIG] = config.toJson()
        }
    }

    // ── Chat 输入模式记忆 ────────────────────────────────────
    override val chatInputModeFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CHAT_INPUT_MODE] ?: "text"
        }

    override suspend fun updateChatInputMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CHAT_INPUT_MODE] = mode
        }
    }

    // ── Chat 当前会话记忆 ────────────────────────────────────
    override val chatCurrentSessionIdFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CHAT_CURRENT_SESSION_ID] ?: "default"
        }

    override suspend fun updateChatCurrentSessionId(sessionId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CHAT_CURRENT_SESSION_ID] = sessionId
        }
    }

    // ── 飞书远程控制 ──────────────────────────────────────────
    override val feishuAppIdFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.FEISHU_APP_ID] ?: BuildConfig.FEISHU_APP_ID
        }

    override val feishuAppSecretFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.FEISHU_APP_SECRET] ?: BuildConfig.FEISHU_APP_SECRET
        }

    override suspend fun updateFeishuAppId(appId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FEISHU_APP_ID] = appId
        }
    }

    override suspend fun updateFeishuAppSecret(appSecret: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FEISHU_APP_SECRET] = appSecret
        }
    }

    // ── 远程通道选择 + Telegram ───────────────────────────────
    override val selectedRemoteChannelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SELECTED_REMOTE_CHANNEL] ?: "FEISHU"
        }

    override suspend fun updateSelectedRemoteChannel(type: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_REMOTE_CHANNEL] = type
        }
    }

    override val telegramBotTokenFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.TELEGRAM_BOT_TOKEN] ?: ""
        }

    override val telegramAllowedChatIdFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.TELEGRAM_ALLOWED_CHAT_ID] ?: ""
        }

    override suspend fun updateTelegramConfig(botToken: String, allowedChatId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TELEGRAM_BOT_TOKEN] = botToken
            preferences[PreferencesKeys.TELEGRAM_ALLOWED_CHAT_ID] = allowedChatId
        }
    }

    // ── 服务端邮箱认证 ──────────────────────────────────────────
    override val serverAuthTokenFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SERVER_AUTH_TOKEN] ?: ""
        }

    override val serverAuthEmailFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SERVER_AUTH_EMAIL] ?: ""
        }

    override suspend fun updateServerAuth(token: String, email: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SERVER_AUTH_TOKEN] = token
            preferences[PreferencesKeys.SERVER_AUTH_EMAIL] = email
            // 注册成功即脱离访客模式，清空引导计数
            preferences.remove(PreferencesKeys.GUEST_CHAT_MESSAGE_COUNT)
        }
    }

    override suspend fun clearServerAuth() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.SERVER_AUTH_TOKEN)
            preferences.remove(PreferencesKeys.SERVER_AUTH_EMAIL)
        }
    }

    // ── 访客（未注册）聊天引导 ──────────────────────────────────
    override val guestChatMessageCountFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.GUEST_CHAT_MESSAGE_COUNT] ?: 0
        }

    override suspend fun incrementGuestChatMessageCount(): Int {
        var newCount = 0
        context.dataStore.edit { preferences ->
            newCount = (preferences[PreferencesKeys.GUEST_CHAT_MESSAGE_COUNT] ?: 0) + 1
            preferences[PreferencesKeys.GUEST_CHAT_MESSAGE_COUNT] = newCount
        }
        return newCount
    }

    override suspend fun resetGuestChatMessageCount() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.GUEST_CHAT_MESSAGE_COUNT)
        }
    }
}
