package com.mamba.picme.agent.core.facade

import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.context.RenderEnvironment
import com.mamba.picme.agent.core.inference.remote.tool.CameraToolService
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import com.mamba.picme.agent.core.inference.remote.tool.ToolInventory
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentCallback
import com.mamba.picme.agent.core.inference.remote.koog.KoogReActAgent
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.inference.remote.react.AgentExecutionMetrics
import com.mamba.picme.agent.core.inference.remote.RemoteChatEngine
import com.mamba.picme.agent.core.inference.local.LocalModelService
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.runtime.state.SceneManager
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Agent 编排器（统一入口）
 *
 * **线程模型**：
 * 所有专有线程池由 [com.mamba.picme.agent.core.platform.thread.DispatcherProvider]
 * （:shared，组合根注入，Android 为进程级共享实例）集中管理，四线程池完全隔离：
 * - **编排线程**（PoLang-Orchestrator-Thread）：双线程，处理用户输入的整个生命周期
 * - **LLM 推理线程**（PoLang-LLM-Model-Thread）：单线程，模型加载和推理
 * - **DataStore 线程**（PoLang-DataStore-Thread）：单线程，对话历史持久化
 * - **网络线程**（PoLang-Network-Thread）：单线程，远程 HTTP API 调用
 *
 * 各线程池完全隔离，无直接依赖关系。数据持久化为 fire-and-forget 异步操作，
 * 不阻塞推理与编排流程。
 *
 * **接口注入化（Phase 4 KMP 抽取）**：`getInstance(context)` 单例已改为
 * [initialize]（组合根注入 [AgentDependencies]）+ [getInstance]（无参取用）。
 * Android 组合根 = `androidApp` 的 `AndroidAgentComposition`，Application.onCreate 接线。
 */
@OptIn(ExperimentalAtomicApi::class)
class AgentOrchestrator private constructor(
    private val deps: AgentDependencies,
) {

    companion object {
        // commonMain 无 synchronized（kotlin stdlib JVM-only）：AtomicReference CAS 保单次构建，
        // 竞争失败者丢弃新建实例（生产路径仅 Application.onCreate 主线程调用一次，理论兜底）。
        private val instanceRef = AtomicReference<AgentOrchestrator?>(null)

        /**
         * 组合根注入入口：应用启动时（Application.onCreate）调用一次。
         * 重复调用返回既有实例（单例语义，进程内唯一 wiring）。
         */
        fun initialize(deps: AgentDependencies): AgentOrchestrator {
            instanceRef.load()?.let {
                Logger.w("AgentOrchestrator", "initialize called twice, returning existing instance")
                return it
            }
            val created = AgentOrchestrator(deps)
            return if (instanceRef.compareAndSet(null, created)) {
                created
            } else {
                checkNotNull(instanceRef.load())
            }
        }

        /**
         * 取进程内唯一实例。须先于 [initialize] 完成组合根接线，
         * 否则 fail-fast（替代旧 `getInstance(context)` 的隐式直构）。
         */
        fun getInstance(): AgentOrchestrator = checkNotNull(instanceRef.load()) {
            "AgentOrchestrator not initialized: Android 端须先在 Application.onCreate 调用 " +
                "AndroidAgentComposition.initialize(context)"
        }

        /**
         * 相机远程 tool_calls 专属 system prompt 组装（确定性）：「可用工具」段由
         * [ToolInventory] 从 [CameraToolService] 的 @Tool 元数据（组合根反射展开为
         * [ToolDescriptor] 列表注入）生成（同 RemoteChatEngine.buildChatSystemPrompt 模式）。
         * public 供组合根一致性单测复用（无需实例化 orchestrator）。
         */
        fun buildCameraSystemPrompt(toolDescriptors: List<ToolDescriptor>): String =
            """
            你是相机拍摄助手，通过调用工具直接控制相机：拍照、录像、翻转摄像头、调美颜、换滤镜/风格、调变焦/曝光/画幅/场景模式。
            """.trimIndent() +
                "\n" + ToolInventory.build(toolDescriptors) + "\n" +
                """
        【执行规则】
        - 用户指令明确时立即调用对应工具执行，不要反问确认；相机能力之外的请求（如查相册、改设置）如实告知「相机页暂不支持，请到相册/聊天页操作」。
        - 组合指令按顺序调用多个工具；延时拍摄必须先调 delay 再调 capture（如「3秒后拍照」→ delay(delay_ms=3000) → capture()）。
        - 美颜类相对调整（「再白一点」「磨皮高一点」）在当前值基础上小幅增减（±10~20），一次性给足，不要反复微调。
        - 完成后直接用一句话总结执行结果（如「已拍照」「已开启美颜，磨皮 50」），不要调用 finish，不要罗列参数细节。
        - 每次请求工具调用不超过 3 次；工具返回 Error 时如实告知用户原因，不要重试同一失败操作。
        """.trimIndent()
    }

    private val tag = "AgentOrchestrator"

    private val configurator = AgentConfigurator(
        dispatcherProvider = deps.dispatcherProvider,
        chatMemoryStore = deps.chatMemoryStore,
        imageEngineProvider = deps.imageEngineProvider,
        remoteImToolRegistryProvider = deps.remoteImToolRegistryProvider,
    )

    /** 相机 system prompt（由组合根注入的工具描述元数据确定性组装，agent 构建期烘焙）。 */
    private val cameraSystemPrompt = buildCameraSystemPrompt(deps.cameraToolDescriptors)

    /** 远程 chat 推理引擎（决策3 / ADR-010）：chat 远程 ReAct 链路隔离出口。 */
    val remoteChatEngine = RemoteChatEngine(
        configurator = configurator,
        chatToolDescriptors = deps.chatToolDescriptors,
        chatToolRegistry = deps.chatToolRegistry,
        chatPromptBuilder = deps.chatPromptBuilder,
    )

    /** 端侧 VLM 模型加载服务（TAG 打标 Worker / 图像理解专用，经 `getLlmEngine()` 取引擎）。 */
    val localModelService = LocalModelService(configurator)

    private val orchestratorDispatcher = deps.dispatcherProvider.orchestratorDispatcher

    /**
     * 后台作用域：用于 fire-and-forget 异步操作（如对话历史保存）。
     * SupervisorJob 确保单个后台任务失败不影响其他任务。
     */
    private val backgroundScope = CoroutineScope(SupervisorJob())

    // 便捷访问器
    private val chatHistoryCleaner get() = deps.chatHistoryCleaner
    private val sceneManager get() = configurator.sceneManager
    private val _capabilityRegistry get() = configurator.capabilityRegistry

    /**
     * 当前活跃场景（可观察）
     */
    val currentScene = sceneManager.currentScene

    /**
     * 远程 IM 链路（processRemoteImInput）的工具调用监听，app 层注入。
     *
     * 用于精准感知 agent 实际执行的工具——例如 capture 触发时标记远程拍照回传
     * （RemotePhotoTracker.startCapture），替代消息入口的关键词猜测
     * （"连拍三张照片"这类表达匹配不到关键词，导致拍了不回传）。
     */
    @Volatile
    var remoteImToolCallListener: ((toolName: String) -> Unit)? = null

    /**
     * 注册 Capability（应用级，通常由 PoLangApplication 调用）
     */
    fun registerCapability(capability: Capability) {
        _capabilityRegistry.register(capability)
    }

    /**
     * 注销 Capability（页面级 Capability 随页面退出调用，如 CameraCapability）
     */
    fun unregisterCapability(capability: Capability) {
        _capabilityRegistry.unregister(capability)
    }

    /**
     * 注入记忆快照供给者（转发给内部 [AgentConfigurator]）。须在 chat/飞书 agent 首次构建前
     * 调用——app 在 PoLangApplication.onCreate 注入，早于 agent 懒构建。
     */
    fun setMemoryContextProvider(provider: MemoryContextProvider) {
        configurator.setMemoryContextProvider(provider)
    }

    /**
     * 注入 HTML 卡片渲染环境供给者（转发给内部 [AgentConfigurator]）。须在 chat agent 首次
     * 构建前调用——app 在 PoLangApplication.onCreate 注入；iOS 跟随期不注入，prompt 无该段。
     */
    fun setRenderEnvironmentProvider(provider: () -> RenderEnvironment) {
        configurator.setRenderEnvironmentProvider(provider)
    }

    /**
     * 获取 CapabilityRegistry
     */
    fun getCapabilityRegistry(): CapabilityRegistry {
        return _capabilityRegistry
    }

    /**
     * 场景切换
     */
    fun transitionToScene(scene: SceneManager.Scene, saveToHistory: Boolean = true) {
        sceneManager.transitionTo(scene, saveToHistory)
        Logger.i(tag, "Transitioned to scene: $scene")
    }

    /**
     * 返回上一场景
     */
    fun navigateBack(): Boolean {
        return sceneManager.navigateBack()
    }

    /**
     * 初始化配置
     */
    fun configure(
        mode: AiAgentMode,
        modelId: String,
        privacyLevel: AiAgentPrivacyLevel,
        remoteConfig: RemoteModelConfig? = null,
        localUseOpencl: Boolean = false
    ) {
        configurator.configure(mode, modelId, privacyLevel, remoteConfig, localUseOpencl)
    }

    /**
     * 仅更新远程运行时配置，**不触碰持久 mode/modelId**（P0-3 配置污染止血，ADR-010 step 1）。
     * 详见 [AgentConfigurator.updateRemoteRuntimeConfig]。chat 发消息 / remoteConfig 同步等
     * 只想换远程配置的场景应调本方法，勿用 [configure] 回写 [getAgentMode]。
     */
    fun updateRemoteRuntimeConfig(
        remoteConfig: RemoteModelConfig?,
        privacyLevel: AiAgentPrivacyLevel? = null
    ) {
        configurator.updateRemoteRuntimeConfig(remoteConfig, privacyLevel)
    }

    /**
     * 压入模式临时覆盖。
     * 此后所有推理路由将强制使用 [mode]，直到 [popModeOverride] 被调用。
     *
     * 典型场景：飞书远程控制强制使用 REMOTE 模式，无论用户本地设置如何。
     * 支持嵌套：多次压入需要对应次数弹出。
     */
    fun pushModeOverride(mode: AiAgentMode) {
        configurator.pushModeOverride(mode)
    }

    /**
     * 弹出模式临时覆盖。
     * 恢复栈为空时返回持久化模式。
     */
    fun popModeOverride() {
        configurator.popModeOverride()
    }

    /**
     * 获取当前用户远程模型配置（用于模式同步时保留 gatewayToken 等认证信息）
     */
    fun getUserRemoteConfig(): RemoteModelConfig? = configurator.getUserRemoteConfig()

    /** 设置设备级标识（访客试用额度 X-Device-Id），独立于 remoteConfig 持有，不被后续 configure 覆盖。 */
    fun setDeviceId(id: String) = configurator.setDeviceId(id)

    /**
     * 获取当前 Agent 运行模式（含临时覆盖）
     */
    fun getAgentMode(): AiAgentMode = configurator.getAgentMode()

    /**
     * 获取当前模型 ID
     */
    fun getCurrentModelId(): String = configurator.getCurrentModelId()

    /**
     * 清除飞书 ReAct Agent 缓存（配置变更后强制重建）
     */
    fun clearFeishuAgent() {
        configurator.clearFeishuAgent()
    }

    // ── 飞书 ReAct 入口 ─────────────────────────────────────────────

    /**
     * 处理飞书远程控制输入（ReAct 循环）。
     *
     * 使用 [KoogReActAgent]（Koog 驱动，Phase 5 起）执行多轮 Observe→Think→Act→Verify 循环，
     * 通过应用内 UI 自动化工具完成用户请求。
     *
     * **WindowManager 参数已删除（Phase 4 KMP 抽取）**：飞书 RPA 工具集由组合根经
     * [AgentDependencies.remoteImToolRegistryProvider] 注入（构建时自取 WindowManager）。
     *
     * @param input 用户自然语言输入
     * @param timeoutMs 超时时间（毫秒），默认 120 秒
     * @return 任务完成摘要或错误信息
     */
    suspend fun processRemoteImInput(
        input: String,
        timeoutMs: Long = 120_000L
    ): Result<String> = withContext(orchestratorDispatcher) {
        Logger.d(tag, "processRemoteImInput: input='$input', timeout=${timeoutMs}ms")

        val agent = configurator.getFeishuAgent(object : RemoteReActAgentCallback {
            override fun onLoopStart(iteration: Int) {}
            override fun onContent(iteration: Int, content: String) {}
            override fun onToolCall(iteration: Int, toolName: String, args: String) {}
            override fun onToolResult(iteration: Int, toolName: String, result: String) {}
            override fun onComplete(iteration: Int, summary: String, totalTokens: Int, metrics: AgentExecutionMetrics?) {}
            override fun onError(iteration: Int, error: Throwable, totalTokens: Int, metrics: AgentExecutionMetrics?) {}
        }) ?: return@withContext Result.failure(
            IllegalStateException("Feishu ReAct Agent 初始化失败")
        )

        if (agent.isRunning()) {
            return@withContext Result.failure(
                IllegalStateException("Agent 正在执行其他任务")
            )
        }

        return@withContext try {
            val job = coroutineContext[kotlinx.coroutines.Job]

            val result = withTimeout(timeoutMs) {
                suspendCoroutine<String> { continuation ->
                    var executionMetrics: AgentExecutionMetrics? = null
                    val callback = object : RemoteReActAgentCallback {
                        override fun onLoopStart(iteration: Int) {
                            Logger.d(tag, "Feishu ReAct iteration #$iteration")
                        }
                        override fun onContent(iteration: Int, content: String) {
                            Logger.d(tag, "Feishu ReAct content: ${content.take(200)}")
                        }
                        override fun onToolCall(iteration: Int, toolName: String, args: String) {
                            Logger.d(tag, "Feishu ReAct toolCall: $toolName(${args.take(100)})")
                            remoteImToolCallListener?.invoke(toolName)
                        }
                        override fun onToolResult(iteration: Int, toolName: String, result: String) {
                            Logger.d(tag, "Feishu ReAct toolResult: $toolName → ${result.take(80)}")
                        }
                        override fun onComplete(iteration: Int, summary: String, totalTokens: Int, metrics: AgentExecutionMetrics?) {
                            Logger.i(tag, "Feishu ReAct complete: $iteration rounds, $totalTokens tokens")
                            executionMetrics = metrics
                            continuation.resume("✅ $summary")
                        }
                        override fun onError(iteration: Int, error: Throwable, totalTokens: Int, metrics: AgentExecutionMetrics?) {
                            Logger.e(tag, "Feishu ReAct error: ${error.message}")
                            executionMetrics = metrics
                            continuation.resume("❌ ${error.message ?: "未知错误"}")
                        }
                    }

                    // 协程取消时自动取消 Agent
                    job?.invokeOnCompletion { cause ->
                        if (cause != null) {
                            Logger.d(tag, "Feishu ReAct coroutine cancelled: ${cause.message}")
                            agent.cancel()
                        }
                    }

                    agent.executeTask(input, callback)
                    Logger.d(tag, "executeTask submitted, waiting for callback...")
                }
            }
            // 将性能指标附加到返回结果
            val metrics = agent.getLastExecutionMetrics()
            val finalResult = if (metrics != null) {
                val perfInfo = buildString {
                    append("\n\n---\n")
                    val model = metrics.modelName ?: "未知"
                    val latency = "${metrics.latencyMs}ms"
                    // AgentExecutionMetrics 已迁 :shared（跨模块 public val 不可 smart cast），先取局部变量
                    val promptTokens = metrics.promptTokens
                    val completionTokens = metrics.completionTokens
                    val tokens = if (promptTokens != null && completionTokens != null) {
                        "${promptTokens + completionTokens} tokens ($promptTokens in / $completionTokens out)"
                    } else {
                        ""
                    }
                    append("$model | $latency | $tokens")
                }
                result + perfInfo
            } else {
                result
            }
            Logger.d(tag, "processRemoteImInput got result: ${finalResult.take(100)}")
            Result.success(finalResult)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Logger.e(tag, "processRemoteImInput timeout after ${timeoutMs}ms")
            agent.cancel()
            Result.failure(RuntimeException("⏰ 处理超时（${timeoutMs / 1000}秒），请稍后重试"))
        } catch (e: Exception) {
            Logger.e(tag, "processRemoteImInput error", e)
            Result.failure(e)
        }
    }

    // ── 相机远程 tool_calls 入口（端侧文本 LLM 移除后的替代链路）─────────────────

    private var cachedCameraAgent: KoogReActAgent? = null

    /** 缓存的相机 Agent 对应的配置，用于检测配置变更 */
    private var cachedCameraAgentConfig: RemoteModelConfig? = null

    /**
     * 处理相机场景用户输入（远程单轮/少轮 tool_calls）。
     *
     * 端侧文本 LLM 移除后，相机 AI 指令改走远程链路：远程模型输出标准 OpenAI tool_calls
     *（ADR-005 协议分离，与 chat 同一模式），[CameraToolService] 把相机场景 capability
     *（拍照/录像/美颜/滤镜/变焦/曝光/翻转等）暴露为 @Tool，命令经 [CapabilityRegistry]
     * 在 ReAct 循环内直接执行（写操作复用既有 CommandRisk/确认机制）。
     *
     * - OFF 模式直接返回「AI Agent 已关闭」提示，不发起远程调用。
     * - 相机 session（默认 "camera"）对话历史经 Koog 记忆层（[ChatMemoryStore] 注入实现）
     *   fire-and-forget 回写（与原 LocalCameraAgent.saveConversation 同语义）。
     *
     * @param input 用户自然语言输入
     * @param agentContext 相机场景上下文（scene=CAMERA；memorySessionId 默认 "camera"）
     * @param pageContext 页面上下文（可选）
     * @param timeoutMs 超时时间（毫秒），默认 60 秒（相机指令需快速响应）
     * @return [InferenceResult.Chat]：工具已在循环内执行，返回值为模型的文字总结/错误提示
     */
    suspend fun processCameraInput(
        input: String,
        agentContext: AgentContext,
        pageContext: PageContext? = null,
        timeoutMs: Long = 60_000L
    ): InferenceResult = withContext(orchestratorDispatcher) {
        Logger.d(tag, "processCameraInput: input='$input', session=${agentContext.memorySessionId}")

        if (configurator.getAgentMode() == AiAgentMode.OFF) {
            Logger.w(tag, "processCameraInput: Agent is OFF")
            return@withContext InferenceResult.Chat(message = "AI Agent 已关闭")
        }

        val agent = getCameraAgent() ?: return@withContext InferenceResult.Chat(
            message = "Camera Agent 初始化失败，请检查远程模型配置"
        )

        if (agent.isRunning()) {
            return@withContext InferenceResult.Chat(message = "Agent 正在执行其他任务，请稍候")
        }

        agent.setSessionId(agentContext.memorySessionId)

        // 桥接当前相机状态：adjust_beauty 的相对调整（「再白一点」）需要以真实当前值为基线，
        // 未注入时 CameraToolService 只能拿全零默认值。单并发（上方 isRunning 守卫），逐调用注入安全。
        CameraToolService.getInstance().beautySettingsProvider = { agentContext.beautySettings }

        return@withContext try {
            val job = coroutineContext[kotlinx.coroutines.Job]
            val summary = withTimeout(timeoutMs) {
                suspendCoroutine<String> { continuation ->
                    val callback = object : RemoteReActAgentCallback {
                        override fun onLoopStart(iteration: Int) {
                            Logger.d(tag, "Camera ReAct iteration #$iteration")
                        }
                        override fun onContent(iteration: Int, content: String) {
                            Logger.d(tag, "Camera ReAct content: ${content.take(200)}")
                        }
                        override fun onToolCall(iteration: Int, toolName: String, args: String) {
                            Logger.d(tag, "Camera ReAct toolCall: $toolName(${args.take(100)})")
                        }
                        override fun onToolResult(iteration: Int, toolName: String, result: String) {
                            Logger.d(tag, "Camera ReAct toolResult: $toolName → ${result.take(80)}")
                        }
                        override fun onComplete(iteration: Int, summary: String, totalTokens: Int, metrics: AgentExecutionMetrics?) {
                            Logger.i(tag, "Camera ReAct complete: $iteration rounds, $totalTokens tokens")
                            continuation.resume(summary)
                        }
                        override fun onError(iteration: Int, error: Throwable, totalTokens: Int, metrics: AgentExecutionMetrics?) {
                            Logger.e(tag, "Camera ReAct error: ${error.message}")
                            continuation.resume("出错了：${error.message ?: "未知错误"}")
                        }
                    }
                    job?.invokeOnCompletion { cause ->
                        if (cause != null) {
                            Logger.d(tag, "Camera ReAct coroutine cancelled: ${cause.message}")
                            agent.cancel()
                        }
                    }
                    agent.executeTask(input, callback, agentContext.traceId)
                }
            }
            saveCameraConversation(agentContext.memorySessionId, input, summary)
            InferenceResult.Chat(message = summary)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Logger.e(tag, "processCameraInput timeout after ${timeoutMs}ms")
            agent.cancel()
            InferenceResult.Chat(message = "处理超时（${timeoutMs / 1000}秒），请稍后重试")
        } catch (e: Exception) {
            Logger.e(tag, "processCameraInput error", e)
            InferenceResult.Chat(message = "出错了：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 获取或创建相机 ReAct Agent（[CameraToolService]，相机场域能力工具）。
     * 优先使用用户配置的远程模型，未配置时使用 PoLang Server 默认兜底；
     * 配置变更时自动重建。共享配置经 [configurator] 只读访问。
     */
    private fun getCameraAgent(): KoogReActAgent? {
        val existing = cachedCameraAgent
        val currentConfig = configurator.getUserRemoteConfig() ?: RemoteModelConfig.PICME_SERVER_DEFAULT
        if (existing != null && cachedCameraAgentConfig != null) {
            val configChanged = cachedCameraAgentConfig?.modelId != currentConfig.modelId
                || cachedCameraAgentConfig?.baseUrl != currentConfig.baseUrl
                || cachedCameraAgentConfig?.apiKey != currentConfig.apiKey
                || cachedCameraAgentConfig?.gatewayToken != currentConfig.gatewayToken
                || cachedCameraAgentConfig?.protocol != currentConfig.protocol
                || cachedCameraAgentConfig?.providerId != currentConfig.providerId
            if (configChanged) {
                Logger.i(tag, "Remote config changed (model=${currentConfig.modelId}), rebuilding Camera Agent")
                existing.shutdown()
                cachedCameraAgent = null
                cachedCameraAgentConfig = null
            } else {
                return existing
            }
        } else if (existing != null) {
            return existing
        }
        val memProvider = configurator.getMemoryContextProvider()
        val cfg = try {
            RemoteReActAgentConfig.Builder()
                .apiKey(currentConfig.apiKey)
                .baseUrl(currentConfig.baseUrl)
                .modelName(currentConfig.modelId)
                .gatewayToken(currentConfig.gatewayToken)
                .deviceId(configurator.getDeviceId())
                .protocol(currentConfig.protocol)
                .providerId(currentConfig.providerId)
                .systemPrompt(cameraSystemPrompt)
                .apply { if (memProvider != null) memoryContextProvider(memProvider) }
                .build()
        } catch (e: Exception) {
            Logger.w(tag, "Failed to build CameraAgent config", e)
            return null
        }
        val cameraToolService = CameraToolService.getInstance()
        val agent = KoogReActAgent(
            config = cfg,
            callback = object : RemoteReActAgentCallback {
                override fun onLoopStart(iteration: Int) {}
                override fun onContent(iteration: Int, content: String) {}
                override fun onToolCall(iteration: Int, toolName: String, args: String) {}
                override fun onToolResult(iteration: Int, toolName: String, result: String) {}
                override fun onComplete(iteration: Int, summary: String, totalTokens: Int, metrics: AgentExecutionMetrics?) {}
                override fun onError(iteration: Int, error: Throwable, totalTokens: Int, metrics: AgentExecutionMetrics?) {}
            },
            dispatcherProvider = deps.dispatcherProvider,
            memoryStore = koogMemoryStore,
            // reflect.ToolSet / asToolsByClass 是 Koog 1.1.1 JVM-only API；工具集→ToolRegistry
            // 的反射展开在组合根（Android）完成，经 AgentDependencies.cameraToolRegistry 注入。
            toolRegistry = deps.cameraToolRegistry,
            recordSource = KoogReActAgent.RECORD_SOURCE_CAMERA,
        )
        // traceId 注入（原 KoogReActAgent init 内类型判断随迁出）：tool 执行带当轮 traceId。
        cameraToolService.traceIdHolder = agent.traceIdHolder
        agent.initialize()
        cachedCameraAgent = agent
        cachedCameraAgentConfig = currentConfig
        Logger.i(tag, "Camera ReAct Agent created: model=${cfg.modelName}, baseUrl=${currentConfig.baseUrl.take(40)}")
        return agent
    }

    /** Koog 记忆存储（对话回写用；组合根注入，与 agent 运行期记忆同一 koog_memory_ 键空间）。 */
    private val koogMemoryStore: ChatMemoryStore get() = deps.chatMemoryStore

    /** 相机对话历史回写（fire-and-forget，与原 LocalCameraAgent.saveConversation 同语义）。 */
    private fun saveCameraConversation(sessionId: String, userInput: String, assistantResponse: String) {
        backgroundScope.launch {
            appendKoogConversation(sessionId, userInput, assistantResponse)
        }
    }

    // ── 会话记忆操作（替代原 LocalCameraAgent.clearMemory / appendImageChatToMemory）──

    /** 清空指定 session 的对话记忆（如 "camera"）：旧 memory_ 键空间 + Koog koog_memory_ 键空间。 */
    suspend fun clearChatMemory(sessionId: String) {
        chatHistoryCleaner.clearHistory(sessionId)
        koogMemoryStore.clear(sessionId)
    }

    /**
     * 追加一轮对话到指定 session 的记忆（如 chat 页图片分析结果回写，
     * 使后续文本消息能引用图片上下文）。
     */
    suspend fun appendConversation(sessionId: String, userInput: String, assistantResponse: String) {
        appendKoogConversation(sessionId, userInput, assistantResponse)
    }

    /** 经 Koog 记忆层原子回写一轮 user+assistant（load→拼→save，store 内置三不变式裁剪）。 */
    private suspend fun appendKoogConversation(sessionId: String, userInput: String, assistantResponse: String) {
        val history = koogMemoryStore.load(sessionId)
        koogMemoryStore.save(
            sessionId,
            history + listOf(
                Message.User(userInput, RequestMetaInfo.Empty),
                Message.Assistant(assistantResponse, ResponseMetaInfo.Empty)
            )
        )
    }

}
