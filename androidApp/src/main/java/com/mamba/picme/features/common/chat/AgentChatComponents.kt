package com.mamba.picme.features.common.chat

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import com.mamba.picme.agent.core.platform.voice.SherpaOnnxAsrEngine
import com.mamba.picme.core.common.Logger
import com.mamba.picme.R
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.domain.usecase.AiAgentUseCase
import com.mamba.picme.features.camera.voice.SystemAsrEngine
import com.mamba.picme.features.camera.voice.VoiceCommandCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// 1. ASR 引擎初始化（公共逻辑）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 根据用户设置初始化 ASR 引擎，支持三级降级策略：
 * Sherpa-MNN ASR → MNN ASR → System ASR
 *
 * @param context Android Context
 * @param localAsrModel 本地 ASR 模型 ID（从 UserPreferencesRepository 读取）
 * @param logTag 日志标签前缀
 */
fun createAsrEngine(
    context: Context,
    localAsrModel: String,
    logTag: String
): AsrEngine {
    if (localAsrModel.isBlank()) {
        Logger.d(logTag, "No local ASR model configured, using system ASR")
        return SystemAsrEngine(context)
    }

    val modelDir = context.filesDir.resolve("llm_models/$localAsrModel")
    val modelDirPath = modelDir.absolutePath

    val isModelReady = if (localAsrModel.contains("zipformer", ignoreCase = true)) {
        // 修复 P0-3：检查 .onnx 文件而非 .mnn（已迁移到 Sherpa-ONNX）
        modelDir.exists() && modelDir.isDirectory &&
            modelDir.walkTopDown().any { it.name.endsWith(".onnx") } &&
            File(modelDir, "tokens.txt").exists()
    } else {
        modelDir.exists() && modelDir.isDirectory
    }

    if (!isModelReady) {
        Logger.w(logTag, "ASR model not ready: $localAsrModel")
        return SystemAsrEngine(context)
    }

    return if (localAsrModel.contains("zipformer", ignoreCase = true)) {
        val sherpaOnnxAsr = SherpaOnnxAsrEngine(context, modelDirPath)
        if (sherpaOnnxAsr.isAvailable()) {
            Logger.i(logTag, "Using Sherpa-ONNX ASR engine")
            sherpaOnnxAsr
        } else {
            Logger.w(logTag, "Sherpa-ONNX ASR init failed, fallback to system ASR")
            SystemAsrEngine(context)
        }
    } else {
        Logger.w(logTag, "Unsupported ASR model: $localAsrModel, fallback to system ASR")
        SystemAsrEngine(context)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. VoiceCommandCoordinator 初始化（公共逻辑）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 创建并配置 VoiceCommandCoordinator
 *
 * @param asrEngine ASR 引擎实例
 * @param aiAgentUseCase AI Agent 用例
 * @param scope 协程作用域
 * @param onCommand 命令回调
 * @param onTranscript 识别文本回调（可选）
 * @param onAgentResponse Agent 响应回调（可选）
 */
fun createVoiceCommandCoordinator(
    asrEngine: AsrEngine,
    aiAgentUseCase: AiAgentUseCase,
    scope: CoroutineScope,
    onCommand: (AiAgentCommand) -> Unit,
    onTranscript: ((String) -> Unit)? = null,
    onAgentResponse: ((Result<AiAgentCommand>) -> Unit)? = null,
    context: Context? = null
): VoiceCommandCoordinator {
    return VoiceCommandCoordinator(
        asrEngine = asrEngine,
        aiAgentUseCase = aiAgentUseCase,
        onCommand = onCommand,
        scope = scope,
        onTranscript = onTranscript,
        onAgentResponse = onAgentResponse,
        context = context
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Agent Chat Panel（浮动按钮 + AiChatScreen + AgentOrchestrator）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一的 Agent Chat Panel 组件
 *
 * 封装以下重复逻辑：
 * - 右下角浮动按钮（KeyboardVoice 图标）
 * - AiChatScreen 对话框
 * - AgentOrchestrator 消息处理
 * - 消息状态管理
 *
 * @param pageContext 页面上下文
 * @param agentScene Agent 场景枚举
 * @param memorySessionId 记忆会话 ID
 * @param voiceCoordinator 语音协调器（可选）
 * @param modifier 修饰符（通常用于定位，如 Modifier.align(Alignment.BottomEnd)）
 */
private fun mapAgentActionToAiAgentCommand(action: AgentAction.Success): AiAgentCommand? {
    return when (val cmd = action.command) {
        is AgentCommand.AdjustBeauty ->
            AiAgentCommand.AdjustBeauty(cmd.settings)
        is AgentCommand.SwitchFilter ->
            AiAgentCommand.SwitchFilter(cmd.filterType)
        is AgentCommand.SwitchStyle ->
            AiAgentCommand.SwitchStyle(cmd.styleFilter)
        is AgentCommand.SwitchScene ->
            AiAgentCommand.SwitchScene(cmd.sceneName)
        is AgentCommand.SwitchRatio ->
            AiAgentCommand.SwitchRatio(cmd.ratio)
        is AgentCommand.AdjustExposure ->
            AiAgentCommand.AdjustExposure(cmd.exposure)
        is AgentCommand.AdjustZoom ->
            AiAgentCommand.AdjustZoom(cmd.zoomRatio)
        is AgentCommand.FlipCamera ->
            AiAgentCommand.FlipCamera
        is AgentCommand.CapturePhoto ->
            AiAgentCommand.CapturePhoto
        is AgentCommand.ToggleRecording ->
            AiAgentCommand.ToggleRecording
        is AgentCommand.SwitchMode ->
            AiAgentCommand.SwitchMode(cmd.mode)
        is AgentCommand.Delay ->
            AiAgentCommand.Delay(cmd.delayMs)
        is AgentCommand.NavigateTo ->
            AiAgentCommand.NavigateTo(cmd.destination)
        is AgentCommand.GoBack ->
            AiAgentCommand.GoBack
        is AgentCommand.AiOptimize ->
            cmd.resultRecipe?.let { AiAgentCommand.ApplyEditRecipe(it) }
        else -> null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AgentAction → CommandExecution 消息转换
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 将 AgentAction.Success 转换为 CommandExecution 消息列表
 *
 * 支持 BatchExecute 展开为多条消息，单命令转换为单条消息。
 */
private fun agentActionToExecutionMessages(context: Context, action: AgentAction.Success): List<AgentMessage> {
    val cmd = action.command
    return when (cmd) {
        is AgentCommand.BatchExecute -> {
            val total = cmd.commands.size
            cmd.commands.mapIndexed { index, subCmd ->
                AgentMessage.CommandExecution(
                    commandName = getAgentCommandDisplayName(context, subCmd),
                    commandIcon = resolveCommandIcon(subCmd),
                    status = AgentMessage.CommandExecution.Status.SUCCESS,
                    detail = getAgentCommandDetail(context, subCmd),
                    index = index + 1,
                    total = total
                )
            }
        }
        else -> listOf(
            AgentMessage.CommandExecution(
                commandName = getAgentCommandDisplayName(context, cmd),
                commandIcon = resolveCommandIcon(cmd),
                status = AgentMessage.CommandExecution.Status.SUCCESS,
                detail = getAgentCommandDetail(context, cmd),
                index = 0,
                total = 1
            )
        )
    }
}

private fun getAgentCommandDisplayName(context: Context, command: AgentCommand): String =
    when (command) {
        is AgentCommand.AdjustBeauty -> context.getString(R.string.chat_cmd_adjust_beauty)
        is AgentCommand.SwitchFilter -> context.getString(R.string.chat_cmd_switch_filter)
        is AgentCommand.SwitchStyle -> context.getString(R.string.chat_cmd_switch_style)
        is AgentCommand.SwitchScene -> context.getString(R.string.chat_cmd_switch_scene)
        is AgentCommand.SwitchRatio -> context.getString(R.string.chat_cmd_switch_ratio)
        is AgentCommand.AdjustExposure -> context.getString(R.string.chat_cmd_adjust_exposure)
        is AgentCommand.AdjustZoom -> context.getString(R.string.chat_cmd_adjust_zoom)
        is AgentCommand.FlipCamera -> context.getString(R.string.chat_cmd_flip_camera)
        is AgentCommand.CapturePhoto -> context.getString(R.string.chat_cmd_capture_photo)
        is AgentCommand.Delay -> context.getString(R.string.chat_cmd_delay)
        is AgentCommand.ToggleRecording -> context.getString(R.string.chat_cmd_toggle_recording)
        is AgentCommand.SwitchMode -> context.getString(R.string.chat_cmd_switch_mode)
        is AgentCommand.NavigateTo -> context.getString(R.string.chat_cmd_navigate_to)
        is AgentCommand.GoBack -> context.getString(R.string.chat_cmd_go_back)
        is AgentCommand.BatchExecute -> context.getString(R.string.chat_cmd_batch_execute)
        is AgentCommand.TextReply -> context.getString(R.string.chat_cmd_text_reply)
        is AgentCommand.ExecutePlan -> context.getString(R.string.chat_cmd_execute_plan)
        is AgentCommand.ChangeTheme -> context.getString(R.string.chat_cmd_change_theme)
        is AgentCommand.ChangeLanguage -> context.getString(R.string.chat_cmd_change_language)
        is AgentCommand.DownloadModel -> context.getString(R.string.chat_cmd_download_model)
        is AgentCommand.SwitchFaceEngine -> context.getString(R.string.chat_cmd_switch_face_engine)
        is AgentCommand.ToggleSetting -> context.getString(R.string.chat_cmd_toggle_setting)
        is AgentCommand.ViewMedia -> context.getString(R.string.chat_cmd_view_media)
        is AgentCommand.DeleteMedia -> context.getString(R.string.chat_cmd_delete_media)
        is AgentCommand.ShareMedia -> context.getString(R.string.chat_cmd_share_media)
        is AgentCommand.SelectMedia -> context.getString(R.string.chat_cmd_select_media)
        is AgentCommand.SearchMedia -> context.getString(R.string.chat_cmd_search_media)
        is AgentCommand.RefineMediaSearch -> context.getString(R.string.chat_cmd_refine_media_search)
        is AgentCommand.SwitchViewMode -> context.getString(R.string.chat_cmd_switch_view_mode)
        is AgentCommand.FavoriteMedia -> context.getString(R.string.chat_cmd_favorite_media)
        is AgentCommand.GetGallerySummary -> context.getString(R.string.chat_cmd_gallery_summary)
        is AgentCommand.StartTagScan -> context.getString(R.string.chat_cmd_start_tag_scan)
        is AgentCommand.AiOptimize -> context.getString(R.string.chat_cmd_ai_optimize)
        is AgentCommand.EditImage -> context.getString(R.string.chat_cmd_edit_image)
        is AgentCommand.LaunchApp -> context.getString(R.string.chat_cmd_launch_app)
        is AgentCommand.OpenSystemSettings -> context.getString(R.string.chat_cmd_open_system_settings)
        is AgentCommand.Unknown -> context.getString(R.string.chat_cmd_unknown_command)
        is AgentCommand.Error -> context.getString(R.string.chat_cmd_execution_error)
        is AgentCommand.RecordMediaFeedback -> context.getString(R.string.chat_cmd_media_feedback)
        is AgentCommand.MoreLikeThis -> context.getString(R.string.chat_cmd_more_like_this)
        is AgentCommand.ExcludeConstraint -> context.getString(R.string.chat_cmd_exclude_constraint)
        is AgentCommand.ExecuteScript -> context.getString(R.string.chat_cmd_execute_script)
        is AgentCommand.DrawChart -> context.getString(R.string.chat_cmd_draw_chart)
        is AgentCommand.RenderHtml -> context.getString(R.string.chat_cmd_render_html)
        is AgentCommand.RememberPersonRelation -> context.getString(R.string.chat_cmd_remember_person_relation)
        is AgentCommand.ForgetPersonRelation -> context.getString(R.string.chat_cmd_forget_person_relation)
        is AgentCommand.QueryPersonRelation -> context.getString(R.string.chat_cmd_query_person_relation)
        is AgentCommand.RememberFact -> context.getString(R.string.chat_cmd_remember_fact)
        is AgentCommand.ForgetFact -> context.getString(R.string.chat_cmd_forget_fact)
        is AgentCommand.RecallMemory -> context.getString(R.string.chat_cmd_recall_memory)
    }

/**
 * 将 AgentCommand 映射为可视化图标，减少 chat 中 command 文字标识的冗余。
 */
private fun resolveCommandIcon(command: AgentCommand): ImageVector = when (command) {
    is AgentCommand.AdjustBeauty -> Icons.Rounded.Face
    is AgentCommand.SwitchFilter -> Icons.Rounded.PhotoFilter
    is AgentCommand.SwitchStyle -> Icons.Rounded.Style
    is AgentCommand.SwitchScene -> Icons.Rounded.Videocam
    is AgentCommand.SwitchRatio -> Icons.Rounded.AspectRatio
    is AgentCommand.AdjustExposure -> Icons.Rounded.Exposure
    is AgentCommand.AdjustZoom -> Icons.Rounded.ZoomIn
    is AgentCommand.FlipCamera -> Icons.Rounded.FlipCameraAndroid
    is AgentCommand.CapturePhoto -> Icons.Rounded.CameraAlt
    is AgentCommand.Delay -> Icons.Rounded.HourglassEmpty
    is AgentCommand.ToggleRecording -> Icons.Rounded.Videocam
    is AgentCommand.SwitchMode -> Icons.Rounded.Settings
    is AgentCommand.NavigateTo -> Icons.AutoMirrored.Rounded.OpenInNew
    is AgentCommand.GoBack -> Icons.AutoMirrored.Rounded.ArrowBack
    is AgentCommand.BatchExecute -> Icons.AutoMirrored.Rounded.FactCheck
    is AgentCommand.TextReply -> Icons.AutoMirrored.Rounded.ShortText
    is AgentCommand.ExecutePlan -> Icons.AutoMirrored.Rounded.PlaylistAddCheck
    is AgentCommand.ChangeTheme -> Icons.Rounded.DarkMode
    is AgentCommand.ChangeLanguage -> Icons.Rounded.Language
    is AgentCommand.DownloadModel -> Icons.Rounded.Download
    is AgentCommand.SwitchFaceEngine -> Icons.Rounded.Memory
    is AgentCommand.ToggleSetting -> Icons.Rounded.ToggleOn
    is AgentCommand.ViewMedia -> Icons.Rounded.Visibility
    is AgentCommand.DeleteMedia -> Icons.Rounded.Delete
    is AgentCommand.ShareMedia -> Icons.Rounded.Share
    is AgentCommand.SelectMedia -> Icons.Rounded.CheckCircle
    is AgentCommand.SearchMedia -> Icons.Rounded.Search
    is AgentCommand.RefineMediaSearch -> Icons.Rounded.Search
    is AgentCommand.SwitchViewMode -> Icons.Rounded.GridView
    is AgentCommand.FavoriteMedia -> Icons.Rounded.Favorite
    is AgentCommand.GetGallerySummary -> Icons.Rounded.PhotoLibrary
    is AgentCommand.StartTagScan -> Icons.Rounded.Sync
    is AgentCommand.AiOptimize -> Icons.Rounded.AutoFixHigh
    is AgentCommand.EditImage -> Icons.Rounded.Edit
    is AgentCommand.LaunchApp -> Icons.AutoMirrored.Rounded.Launch
    is AgentCommand.OpenSystemSettings -> Icons.Rounded.Settings
    is AgentCommand.Unknown -> Icons.AutoMirrored.Rounded.Help
    is AgentCommand.Error -> Icons.Rounded.Error
    is AgentCommand.RecordMediaFeedback -> Icons.Rounded.Favorite
    is AgentCommand.MoreLikeThis -> Icons.Rounded.Search
    is AgentCommand.ExcludeConstraint -> Icons.Rounded.Delete
    is AgentCommand.ExecuteScript -> Icons.Rounded.Code
    is AgentCommand.DrawChart -> Icons.Rounded.Code
    is AgentCommand.RenderHtml -> Icons.Rounded.Web
    is AgentCommand.RememberPersonRelation -> Icons.Rounded.Face
    is AgentCommand.ForgetPersonRelation -> Icons.Rounded.Face
    is AgentCommand.QueryPersonRelation -> Icons.Rounded.Face
    is AgentCommand.RememberFact -> Icons.Rounded.Memory
    is AgentCommand.ForgetFact -> Icons.Rounded.Memory
    is AgentCommand.RecallMemory -> Icons.Rounded.Memory
}

private fun getAgentCommandDetail(context: Context, command: AgentCommand): String =
    when (command) {
        is AgentCommand.AdjustBeauty -> buildString {
            val s = command.settings
            val parts = mutableListOf<String>()
            if (s.smoothing > 0) parts.add(context.getString(R.string.chat_cmd_detail_smoothing, s.smoothing.toInt()))
            if (s.whitening > 0) parts.add(context.getString(R.string.chat_cmd_detail_whitening, s.whitening.toInt()))
            if (s.slimFace != 0f) parts.add(context.getString(R.string.chat_cmd_detail_slim_face, s.slimFace.toInt()))
            if (s.bigEyes > 0) parts.add(context.getString(R.string.chat_cmd_detail_big_eyes, s.bigEyes.toInt()))
            if (parts.isEmpty()) append(context.getString(R.string.chat_cmd_detail_default_params)) else append(parts.joinToString(", "))
        }
        is AgentCommand.SwitchFilter -> context.getString(R.string.chat_cmd_detail_filter, command.filterType.name)
        is AgentCommand.SwitchStyle -> context.getString(R.string.chat_cmd_detail_style, command.styleFilter.name)
        is AgentCommand.SwitchScene -> context.getString(R.string.chat_cmd_detail_scene, command.sceneName)
        is AgentCommand.SwitchRatio -> context.getString(R.string.chat_cmd_detail_ratio, command.ratio)
        is AgentCommand.AdjustExposure -> context.getString(R.string.chat_cmd_detail_exposure, command.exposure)
        is AgentCommand.AdjustZoom -> context.getString(R.string.chat_cmd_detail_zoom, command.zoomRatio)
        is AgentCommand.NavigateTo -> context.getString(R.string.chat_cmd_detail_target, command.destination)
        is AgentCommand.ChangeTheme -> context.getString(R.string.chat_cmd_detail_theme, command.theme)
        is AgentCommand.ChangeLanguage -> context.getString(R.string.chat_cmd_detail_language, command.language)
        is AgentCommand.DownloadModel -> context.getString(R.string.chat_cmd_detail_model, command.modelId)
        is AgentCommand.SwitchFaceEngine -> context.getString(R.string.chat_cmd_detail_engine, command.engine)
        is AgentCommand.ToggleSetting -> context.getString(
            R.string.chat_cmd_detail_setting,
            command.settingKey,
            context.getString(if (command.enabled) R.string.chat_cmd_detail_switch_on else R.string.chat_cmd_detail_switch_off)
        )
        is AgentCommand.SearchMedia -> context.getString(R.string.chat_cmd_detail_keyword, command.query)
        is AgentCommand.ExecutePlan -> context.getString(R.string.chat_cmd_detail_plan, command.plan.description)
        is AgentCommand.Delay -> context.getString(R.string.chat_cmd_detail_delay, command.delayMs)
        is AgentCommand.LaunchApp -> command.appName ?: command.packageName ?: ""
        is AgentCommand.OpenSystemSettings -> command.setting
        else -> ""
    }

// ─────────────────────────────────────────────────────────────────────────────
// 4. 页面级初始化辅助（ASR + VoiceCoordinator 一站式配置）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 页面级 Agent Chat 初始化数据类
 *
 * 包含 ASR 引擎、VoiceCommandCoordinator 和 AiAgentUseCase 的完整配置
 */
data class AgentChatConfig(
    val asrEngine: AsrEngine,
    val aiAgentUseCase: AiAgentUseCase,
    val voiceCoordinator: VoiceCommandCoordinator
)

/**
 * 记住页面级 Agent Chat 配置（ASR + VoiceCoordinator + AiAgentUseCase）
 *
 * 使用示例：
 * ```
 * val config = rememberAgentChatConfig(
 *     context = context,
 *     logTag = "PoLang:Gallery",
 *     onCommand = { command -> /* 处理命令 */ },
 *     onTranscript = { transcript -> /* 处理识别文本 */ },
 *     onAgentResponse = { result -> /* 处理 Agent 响应 */ }
 * )
 * DisposableEffect(Unit) {
 *     onDispose {
 *         // 修复 P0-1：不应该调用 release()，而是进行"软释放"
 *         config.voiceCoordinator.stopWakeWordListening()
 *         config.voiceCoordinator.stopPushToTalk()
 *     }
 * }
 * ```
 *
 * 为什么不能调用 release()？
 * - voiceCoordinator 在多个屏幕（Camera、Gallery、Settings）间共享使用
 * - 如果在某个页面调用 release()，会完全释放 ASR 引擎
 * - 切换到其他屏幕后，ASR 引擎已被销毁，无法再使用语音功能
 * - 正确的做法：每个页面只进行"软释放"（停止监听但保留引擎状态）
 */
@Composable
fun rememberAgentChatConfig(
    context: Context,
    logTag: String,
    onCommand: (AiAgentCommand) -> Unit,
    onTranscript: ((String) -> Unit)? = null,
    onAgentResponse: ((Result<AiAgentCommand>) -> Unit)? = null
): AgentChatConfig {
    val scope = rememberCoroutineScope()

    // 读取用户 ASR 模型设置
    val settingsRepository = remember { UserPreferencesRepository(context) }
    val localAsrModel by settingsRepository.localAsrModelFlow.collectAsState(initial = "")

    // ASR 引擎 - 异步初始化避免主线程阻塞（先降级为系统ASR，后台加载本地模型）
    var asrEngine by remember(context, localAsrModel) {
        mutableStateOf<AsrEngine>(SystemAsrEngine(context))
    }
    LaunchedEffect(context, localAsrModel) {
        val engine = withContext(Dispatchers.IO) {
            createAsrEngine(context, localAsrModel, logTag)
        }
        asrEngine = engine
    }

    // 读取 Agent 模式与远程配置（端侧文本 LLM 已移除，统一远程链路）
    val aiAgentMode by settingsRepository.aiAgentModeFlow.collectAsState(initial = AiAgentMode.REMOTE)
    val aiAgentRemoteModelConfigs by settingsRepository.aiAgentRemoteModelConfigsFlow.collectAsState(initial = "")
    val aiAgentSelectedRemoteModel by settingsRepository.aiAgentSelectedRemoteModelFlow.collectAsState(initial = "deepseek-v4-flash")

    // 解析远程模型配置
    // 注意：aiAgentSelectedRemoteModel 保存的是 uniqueKey（providerId:modelId），
    // 优先按 uniqueKey 查找；找不到再按 modelId 查找。
    // 用户未配置时返回 null，让 AiAgentUseCase 使用 SCF 兜底配置。
    val remoteConfig = remember(aiAgentRemoteModelConfigs, aiAgentSelectedRemoteModel) {
        val configs = if (aiAgentRemoteModelConfigs.isNotBlank()) {
            RemoteModelConfigs.fromJson(aiAgentRemoteModelConfigs)
        } else {
            RemoteModelConfigs()
        }
        configs.getConfig(aiAgentSelectedRemoteModel)
            ?: configs.getConfigByModelId(aiAgentSelectedRemoteModel)
    }

    // 读取服务端认证 token（邮箱注册）
    val serverAuthToken by settingsRepository.serverAuthTokenFlow.collectAsState(initial = "")

    // AiAgentUseCase：根据设置动态配置 mode 与远程配置
    val aiAgentUseCase = remember(
        aiAgentMode,
        remoteConfig,
        serverAuthToken
    ) {
        AiAgentUseCase(
            context = context,
            agentMode = aiAgentMode,
            remoteConfig = remoteConfig,
            gatewayToken = serverAuthToken.takeIf { it.isNotBlank() }
        )
    }

    // VoiceCommandCoordinator - asrEngine 变化时自动重建
    val voiceCoordinator = remember(asrEngine, aiAgentUseCase) {
        Logger.d("AgentChatConfig", "Creating voiceCoordinator: asrEngine=$asrEngine, aiAgentUseCase=$aiAgentUseCase")
        val coordinator = createVoiceCommandCoordinator(
            asrEngine = asrEngine,
            aiAgentUseCase = aiAgentUseCase,
            scope = scope,
            onCommand = onCommand,
            onTranscript = onTranscript,
            onAgentResponse = onAgentResponse,
            context = context
        )
        Logger.d("AgentChatConfig", "VoiceCoordinator created: $coordinator")
        coordinator
    }
    // 修复 P0-1：不应该在 voiceCoordinator 变化时调用 release()
    // 原因：voiceCoordinator 在多个 Chat 屏幕（Camera、Gallery、Settings）间共享使用
    // release() 会完全释放 ASR 引擎，导致后续屏幕无法再使用语音功能
    //
    // 正确做法：每个使用 voiceCoordinator 的页面在自己的 onDispose 时，只进行"软释放"
    // （调用 stopWakeWordListening() + stopPushToTalk()，不调用 release()）
    //
    // ASR 引擎的完全释放应该由 Application 生命周期或单独的释放策略管理
    DisposableEffect(voiceCoordinator) {
        onDispose {
            // 旧代码注释：voiceCoordinator.release() // ❌ 不再调用
            Logger.d("AgentChatConfig", "rememberAgentChatConfig disposed - soft release only (not calling release())")
            voiceCoordinator.stopWakeWordListening()
            voiceCoordinator.stopPushToTalk()
        }
    }

    return AgentChatConfig(
        asrEngine = asrEngine,
        aiAgentUseCase = aiAgentUseCase,
        voiceCoordinator = voiceCoordinator
    )
}
