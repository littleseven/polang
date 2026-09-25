package com.mamba.picme.domain.usecase

import android.content.Context
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.AiAgentCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Agent 核心用例（Facade）
 *
 * 向后兼容的入口类。内部委托给 [AgentOrchestrator]，保留原有接口不变。
 *
 * **端侧文本 LLM 移除（2026-08）**：
 * - 相机 AI 指令统一走远程 tool_calls 链路（[AgentOrchestrator.processCameraInput]）
 * - 端侧仅保留 VLM 打标（qwen3_vl_2b，经 `localModelService`），与本用例无关
 * - 远程控制能力参考 apkClaw（/Users/guoshuai/code/ApkClaw）的 DefaultAgentService
 *   使用 langchain4j tool_calls 机制实现 IM 远程控制
 *
 * @param context Application Context
 * @param agentMode Agent 运行模式，默认 REMOTE
 * @param privacyLevel 隐私级别，默认 STRICT
 * @param remoteConfig 用户自定义远程模型配置（完整配置，包含 modelId/apiKey/baseUrl/gatewayToken）
 * @param gatewayToken 邮箱注册获取的服务端认证 token
 */
class AiAgentUseCase(
    context: Context,
    agentMode: AiAgentMode = AiAgentMode.REMOTE,
    privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    remoteConfig: RemoteModelConfig? = null,
    gatewayToken: String? = null
) {

    private val tag = "AiAgent"

    /**
     * Agent Runtime 编排器（单例）
     */
    private val orchestrator = AgentOrchestrator.getInstance()

    /**
     * 用户自定义远程模型配置（高优先级）
     * 只要 remoteConfig 有 baseUrl + modelId 就使用，apiKey 由调用链路自行处理
     */
    private val userRemoteConfig: RemoteModelConfig? =
        remoteConfig?.takeIf { it.baseUrl.isNotBlank() && it.modelId.isNotBlank() }

    /**
     * 兜底远程模型配置（PoLang Server 代理，无需用户配置）
     * gatewayToken 由 DataStore 异步注入（邮箱注册后的动态 token）
     */
    private val fallbackRemoteConfig: RemoteModelConfig =
        RemoteModelConfig.PICME_SERVER_DEFAULT.copy(
            gatewayToken = gatewayToken?.takeIf { it.isNotBlank() } ?: ""
        )

    /**
     * 当前实际使用的远程模型配置
     */
    private val effectiveRemoteConfig: RemoteModelConfig
        get() = userRemoteConfig ?: fallbackRemoteConfig

    /**
     * 当前 Agent 模式
     */
    val currentMode: AiAgentMode = agentMode

    init {
        Logger.i(tag, "AiAgentUseCase init: remoteConfig=${remoteConfig?.modelId ?: "null"}, " +
            "baseUrl=${remoteConfig?.baseUrl?.take(40) ?: "null"}, " +
            "apiKey=${if (remoteConfig?.apiKey.isNullOrBlank()) "empty" else "set"}, " +
            "gatewayToken=${if (remoteConfig?.gatewayToken.isNullOrBlank()) "empty" else "set"}, " +
            "effectiveBaseUrl=${effectiveRemoteConfig.baseUrl.take(40)}, " +
            "isUsingFallbackGateway=${userRemoteConfig == null}")
        orchestrator.configure(
            mode = agentMode,
            modelId = TAGGER_MODEL_ID,
            privacyLevel = privacyLevel,
            remoteConfig = effectiveRemoteConfig
        )
    }

    /**
     * 发送用户指令到 Agent，返回解析后的命令
     *
     * 端侧文本 LLM 移除后统一走远程 tool_calls 链路：
     * [AgentOrchestrator.processCameraInput] 内部执行远程 ReAct + CameraToolService，
     * 工具命令在循环内直接执行，返回 [InferenceResult.Chat] 文本总结。
     *
     * @param userInput 用户自然语言输入
     * @param currentState 当前相机状态快照，用于上下文感知
     */
    suspend fun processInput(
        userInput: String,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> = withContext(Dispatchers.IO) {
        // 构建 AgentContext
        val agentContext = AgentContext(
            scene = AgentScene.CAMERA,
            beautySettings = currentState.beautySettings,
            filterType = currentState.filterType,
            styleFilter = currentState.styleFilter,
            zoomRatio = currentState.zoomRatio,
            exposureCompensation = currentState.exposureCompensation,
            captureMode = currentState.captureMode,
            isRecording = currentState.isRecording,
            memorySessionId = "camera"
        )

        Logger.i(tag, "[UseCase] ${currentMode.name} mode, calling processCameraInput for input='$userInput'")
        val inferenceResult = orchestrator.processCameraInput(
            input = userInput,
            agentContext = agentContext
        )
        Logger.i(tag, "[UseCase] processCameraInput returned: ${inferenceResult::class.simpleName}")
        return@withContext handleInferenceResult(inferenceResult)
    }

    /**
     * 处理 InferenceResult 并转换为 AiAgentCommand。
     * 远程相机链路只产生 [InferenceResult.Chat] 分支，其余分支防御性保留。
     */
    private fun handleInferenceResult(inferenceResult: InferenceResult): Result<AiAgentCommand> {
        return when (inferenceResult) {
            is InferenceResult.Local -> {
                val command = inferenceResult.command
                Logger.d(tag, "Local result: ${command::class.simpleName}")
                Result.success(mapAgentCommandToLegacy(command))
            }
            is InferenceResult.Batch -> {
                Logger.d(tag, "Batch result: ${inferenceResult.commands.size} commands")
                if (inferenceResult.commands.isEmpty()) {
                    Result.success(AiAgentCommand.TextReply("未识别到有效命令"))
                } else {
                    val commands = inferenceResult.commands.map { mapAgentCommandToLegacy(it) }
                    Result.success(
                        if (commands.size == 1) commands.first() else AiAgentCommand.BatchExecute(commands)
                    )
                }
            }
            is InferenceResult.Plan -> {
                Logger.d(tag, "Plan result: ${inferenceResult.plan.steps.size} steps")
                val commands = inferenceResult.plan.steps.mapNotNull { step ->
                    // PlanStep 的 action 已经是 AgentCommand，直接映射
                    mapAgentCommandToLegacy(step.action)
                }
                if (commands.isEmpty()) {
                    Result.success(AiAgentCommand.TextReply("未识别到有效命令"))
                } else {
                    Result.success(
                        if (commands.size == 1) commands.first() else AiAgentCommand.BatchExecute(commands)
                    )
                }
            }
            is InferenceResult.Chat -> {
                Logger.d(tag, "Chat result: ${inferenceResult.message}")
                Result.success(AiAgentCommand.TextReply(inferenceResult.message))
            }
        }
    }

    /**
     * 将 AgentCommand 映射为 AiAgentCommand（向后兼容）
     */
    private fun mapAgentCommandToLegacy(command: AgentCommand): AiAgentCommand {
        return when (command) {
            is AgentCommand.AdjustBeauty -> AiAgentCommand.AdjustBeauty(command.settings)
            is AgentCommand.SwitchFilter -> AiAgentCommand.SwitchFilter(command.filterType)
            is AgentCommand.SwitchStyle -> AiAgentCommand.SwitchStyle(command.styleFilter)
            is AgentCommand.SwitchScene -> AiAgentCommand.SwitchScene(command.sceneName)
            is AgentCommand.SwitchRatio -> AiAgentCommand.SwitchRatio(command.ratio)
            is AgentCommand.AdjustExposure -> AiAgentCommand.AdjustExposure(command.exposure)
            is AgentCommand.AdjustZoom -> AiAgentCommand.AdjustZoom(command.zoomRatio)
            is AgentCommand.FlipCamera -> AiAgentCommand.FlipCamera
            is AgentCommand.CapturePhoto -> AiAgentCommand.CapturePhoto
            is AgentCommand.ToggleRecording -> AiAgentCommand.ToggleRecording
            is AgentCommand.SwitchMode -> AiAgentCommand.SwitchMode(command.mode)
            is AgentCommand.Delay -> AiAgentCommand.Delay(command.delayMs)
            is AgentCommand.TextReply -> AiAgentCommand.TextReply(command.message)
            is AgentCommand.BatchExecute -> AiAgentCommand.BatchExecute(
                command.commands.map { mapAgentCommandToLegacy(it) }
            )
            is AgentCommand.NavigateTo -> AiAgentCommand.NavigateTo(command.destination)
            is AgentCommand.GoBack -> AiAgentCommand.GoBack
            is AgentCommand.ExecutePlan -> AiAgentCommand.TextReply("执行计划: ${command.plan.description}")
            // Gallery 命令
            is AgentCommand.ViewMedia -> AiAgentCommand.NavigateTo("gallery")
            is AgentCommand.DeleteMedia -> AiAgentCommand.TextReply("请在相册中删除照片")
            is AgentCommand.ShareMedia -> AiAgentCommand.TextReply("请在相册中分享照片")
            is AgentCommand.SelectMedia -> AiAgentCommand.TextReply("请在相册中选择照片")
            is AgentCommand.SearchMedia -> AiAgentCommand.SearchMedia(command.query)
            is AgentCommand.RefineMediaSearch -> AiAgentCommand.SearchMedia(command.constraint)
            is AgentCommand.SwitchViewMode -> AiAgentCommand.TextReply("切换相册视图")
            is AgentCommand.FavoriteMedia -> AiAgentCommand.TextReply("收藏照片")
            is AgentCommand.GetGallerySummary -> AiAgentCommand.TextReply("相册摘要")
            is AgentCommand.StartTagScan -> AiAgentCommand.TextReply("TAG 扫描控制: ${command.action}")
            // 设置命令
            is AgentCommand.ChangeTheme -> AiAgentCommand.TextReply("切换主题: ${command.theme}")
            is AgentCommand.ChangeLanguage -> AiAgentCommand.TextReply("切换语言: ${command.language}")
            is AgentCommand.DownloadModel -> AiAgentCommand.TextReply("下载模型: ${command.modelId}")
            is AgentCommand.SwitchFaceEngine -> AiAgentCommand.TextReply("切换人脸引擎: ${command.engine}")
            is AgentCommand.ToggleSetting -> AiAgentCommand.TextReply("切换设置: ${command.settingKey}")
            // AI 一键优化
            is AgentCommand.AiOptimize -> {
                command.resultRecipe?.let { AiAgentCommand.ApplyEditRecipe(it) }
                    ?: AiAgentCommand.TextReply("AI 优化图片: ${command.imageUri}")
            }
            // 对话式图片编辑（Chat 路径在 ChatViewModel 处理，legacy 映射先兜底为文本）
            is AgentCommand.EditImage -> AiAgentCommand.TextReply(command.explanation ?: "对话式编辑图片")
            // 系统/外部 App 命令
            is AgentCommand.LaunchApp -> AiAgentCommand.TextReply("打开应用: ${command.appName ?: command.packageName}")
            is AgentCommand.OpenSystemSettings -> AiAgentCommand.TextReply("打开设置: ${command.setting}")
            // 错误/未知命令 —— 明确报告，不允许掩盖
            is AgentCommand.Error -> AiAgentCommand.TextReply("命令错误: ${command.reason}")
            is AgentCommand.Unknown -> AiAgentCommand.TextReply("未知命令: ${command.raw}")
            // Feedback 命令无 legacy 对应，统一转文本提示
            is AgentCommand.RecordMediaFeedback -> AiAgentCommand.TextReply("记录媒体反馈")
            is AgentCommand.MoreLikeThis -> AiAgentCommand.TextReply("查找更多相似")
            is AgentCommand.ExcludeConstraint -> AiAgentCommand.TextReply("排除约束")
            is AgentCommand.ExecuteScript -> AiAgentCommand.TextReply("执行脚本")
            is AgentCommand.DrawChart -> AiAgentCommand.TextReply("画图表")
            is AgentCommand.RenderHtml -> AiAgentCommand.TextReply("渲染 HTML 卡片")
            // 记忆命令无 legacy 对应，统一转文本提示
            is AgentCommand.RememberPersonRelation -> AiAgentCommand.TextReply("记住人物关系")
            is AgentCommand.ForgetPersonRelation -> AiAgentCommand.TextReply("遗忘人物关系")
            is AgentCommand.QueryPersonRelation -> AiAgentCommand.TextReply("查询人物关系")
            is AgentCommand.RememberFact -> AiAgentCommand.TextReply("记住事实")
            is AgentCommand.ForgetFact -> AiAgentCommand.TextReply("遗忘事实")
            is AgentCommand.RecallMemory -> AiAgentCommand.TextReply("检索记忆")
        }
    }

    /**
     * 清空相机 session 对话记忆
     */
    suspend fun clearMemory() {
        orchestrator.clearChatMemory("camera")
    }

    /**
     * 当前相机状态快照
     */
    data class CameraStateSnapshot(
        val beautySettings: BeautySettings = BeautySettings(),
        val filterType: FilterType = FilterType.NONE,
        val styleFilter: StyleFilter = StyleFilter.NONE,
        val zoomRatio: Float = 1f,
        val exposureCompensation: Int = 0,
        val captureMode: MediaType = MediaType.PHOTO,
        val isRecording: Boolean = false
    )

    private companion object {
        /** 端侧 VLM 打标模型（configure 的 modelId 仅作 localModelService 默认模型，相机链路不再使用）。 */
        const val TAGGER_MODEL_ID = "qwen3_vl_2b"
    }
}
