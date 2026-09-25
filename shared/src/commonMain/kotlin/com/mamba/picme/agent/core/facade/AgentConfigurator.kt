package com.mamba.picme.agent.core.facade

import ai.koog.agents.core.tools.ToolRegistry
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.inference.local.ImageInferenceEngine
import com.mamba.picme.agent.core.inference.remote.koog.KoogReActAgent
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentCallback
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import com.mamba.picme.agent.core.model.context.RenderEnvironment
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.agent.core.platform.thread.DispatcherProvider
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.policy.PrivacyGuard
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlin.concurrent.Volatile

/**
 * Agent 配置器
 *
 * 负责初始化和配置 Agent 运行时所需的所有核心组件。
 * 作为 [AgentOrchestrator] 的依赖工厂，集中管理组件生命周期。
 *
 * **接口注入化（Phase 4 KMP 抽取）**：构造器不再收 `Context`，平台实现
 * （记忆存储 / VLM 引擎 / 飞书 RPA 工具集）全部由组合根经构造参数注入；
 * 原 `getContext()` 已删除（[imageEngine] / [chatMemoryStore] 只读访问替代）。
 */
class AgentConfigurator(
    /** 平台命名 dispatcher 提供者（构建 KoogReActAgent 时注入）。 */
    val dispatcherProvider: DispatcherProvider,

    /** Koog 对话记忆持久化（组合根注入；供 RemoteChatEngine/飞书 agent 只读访问）。 */
    val chatMemoryStore: ChatMemoryStore,

    imageEngineProvider: () -> ImageInferenceEngine,

    /** 飞书 RPA 工具注册表按需构建（RemoteControlToolService 依赖 WindowManager，懒创建时取用）。 */
    private val remoteImToolRegistryProvider: () -> ToolRegistry,
) {

    private val tag = "AgentConfigurator"

    /** 聊天/飞书 agent 每轮被动注入的记忆快照供给者；由 app 在 onCreate 注入。 */
    @Volatile
    private var memoryContextProvider: MemoryContextProvider? = null

    /** app 层注入记忆快照供给者；须在任一 agent 首次构建前调用。 */
    fun setMemoryContextProvider(provider: MemoryContextProvider) {
        memoryContextProvider = provider
    }

    /** HTML 卡片渲染环境供给者（render_html 排版上下文）；由 app 在 onCreate 注入，iOS 跟随期不注入。 */
    @Volatile
    private var renderEnvironmentProvider: (() -> RenderEnvironment)? = null

    /** app 层注入渲染环境供给者；须在 chat agent 首次构建前调用。 */
    fun setRenderEnvironmentProvider(provider: () -> RenderEnvironment) {
        renderEnvironmentProvider = provider
    }

    /** 渲染环境供给者；供 RemoteChatEngine 只读访问（null = 未注入，prompt 不含渲染环境段）。 */
    fun getRenderEnvironmentProvider(): (() -> RenderEnvironment)? = renderEnvironmentProvider

    // 核心组件
    /** 端侧 VLM 引擎（TAG 打标 / 图像理解专用；文本指令链路已移除）。组合根注入，全进程单实例。 */
    val imageEngine: ImageInferenceEngine = imageEngineProvider()
    val privacyGuard = PrivacyGuard()
    val sceneManager = SceneManager.getInstance()
    val capabilityRegistry = CapabilityRegistry.getInstance()

    /**
     * 模式临时覆盖栈（用于飞书远程控制等场景强制使用特定推理模式）。
     *
     * - [pushModeOverride] 压入覆盖模式
     * - [popModeOverride] 弹出恢复
     * - [getAgentMode] 优先返回栈顶覆盖模式，栈空时返回持久化模式
     *
     * 使用场景：RemoteCommandDispatcher 在处理飞书消息时压入 REMOTE，
     * 处理完成后弹出，不影响用户设置的持久化模式。
     */
    private val modeOverrideStack = ArrayDeque<AiAgentMode>()

    // 配置状态
    private var agentMode: AiAgentMode = AiAgentMode.REMOTE
    private var currentModelId: String = "qwen3_vl_2b"
    private var userRemoteConfig: RemoteModelConfig? = null

    /**
     * 设备级标识（访客试用额度 X-Device-Id）。独立于 [userRemoteConfig] 持有，
     * 避免被多次 configure 覆盖丢失（例如 AiAgentUseCase init 用 fallback 重配 remoteConfig 时，
     * 带 deviceId 的 config 被裸 PICME_SERVER_DEFAULT 覆盖，导致 guest 请求无 X-Device-Id → 401）。
     */
    private var deviceId: String = ""

    fun setDeviceId(id: String) {
        if (id.isNotBlank()) deviceId = id
    }
    private var localUseOpencl: Boolean = false

    /**
     * 配置 Agent 运行参数
     */
    fun configure(
        mode: AiAgentMode,
        modelId: String,
        privacyLevel: AiAgentPrivacyLevel,
        remoteConfig: RemoteModelConfig? = null,
        localUseOpencl: Boolean = false
    ) {
        this.agentMode = mode
        this.currentModelId = modelId
        this.localUseOpencl = localUseOpencl
        if (remoteConfig != null && remoteConfig.baseUrl.isNotBlank() && remoteConfig.modelId.isNotBlank()) {
            this.userRemoteConfig = remoteConfig
        }
        privacyGuard.updateConfig(privacyLevel, mode)
        Logger.i(tag, "Configured: mode=$mode, model=$modelId, privacy=$privacyLevel, " +
            "localUseOpencl=$localUseOpencl, " +
            "remoteModel=${remoteConfig?.modelId ?: "default"}, " +
            "effectiveRemoteModel=${userRemoteConfig?.modelId ?: "fallback"}")
    }

    /**
     * 仅更新远程运行时配置（remoteConfig / privacyLevel），
     * **不触碰持久 agentMode / currentModelId / localUseOpencl**。
     *
     * 用于 chat 发消息、PoLangApplication 同步 remoteConfig 等只想换远程配置的场景——
     * 避免把 [getAgentMode]（可能含临时 modeOverride 栈顶）回写进持久 mode（P0-3 配置污染根因）。
     * privacyGuard 用持久 [agentMode] 同步，杜绝 override 泄漏。
     */
    fun updateRemoteRuntimeConfig(
        remoteConfig: RemoteModelConfig?,
        privacyLevel: AiAgentPrivacyLevel? = null
    ) {
        if (remoteConfig != null && remoteConfig.baseUrl.isNotBlank() && remoteConfig.modelId.isNotBlank()) {
            this.userRemoteConfig = remoteConfig
        }
        if (privacyLevel != null) {
            privacyGuard.updateConfig(privacyLevel, agentMode)
        }
        Logger.i(tag, "updateRemoteRuntimeConfig: remoteModel=${userRemoteConfig?.modelId ?: "fallback"}, " +
            "privacyOverride=${privacyLevel != null}")
    }

    /**
     * 当前 Agent 运行模式
     *
     * 优先返回临时覆盖模式（[modeOverrideStack] 栈顶），
     * 栈空时返回持久化模式（[agentMode]）。
     */
    fun getAgentMode(): AiAgentMode = modeOverrideStack.lastOrNull() ?: agentMode

    /**
     * 压入模式临时覆盖。
     * 此后 [getAgentMode] 将返回 [mode]，直到 [popModeOverride] 被调用。
     *
     * 支持嵌套：多次压入需要对应次数弹出。
     */
    fun pushModeOverride(mode: AiAgentMode) {
        modeOverrideStack.addLast(mode)
        Logger.d(tag, "Mode override pushed: $mode (stack size=${modeOverrideStack.size})")
    }

    /**
     * 弹出模式临时覆盖。
     * 恢复栈为空时返回持久化模式。
     *
     * @throws NoSuchElementException 栈已空时调用
     */
    fun popModeOverride() {
        val popped = modeOverrideStack.removeLastOrNull()
        if (popped != null) {
            Logger.d(tag, "Mode override popped: $popped (stack size=${modeOverrideStack.size})")
        } else {
            Logger.w(tag, "popModeOverride called on empty stack")
        }
    }

    /**
     * 当前模型 ID
     */
    fun getCurrentModelId(): String = currentModelId

    /**
     * 当前本地 LLM 后端是否使用 OpenCL
     */
    fun getLocalUseOpencl(): Boolean = localUseOpencl

    /**
     * 用户远程配置
     */
    fun getUserRemoteConfig(): RemoteModelConfig? = userRemoteConfig

    /** 设备级标识（访客试用 X-Device-Id）；供 RemoteChatEngine/FeishuAgent 只读访问。 */
    fun getDeviceId(): String = deviceId

    /** 记忆快照供给者；供 RemoteChatEngine/FeishuAgent 只读访问。 */
    fun getMemoryContextProvider(): MemoryContextProvider? = memoryContextProvider

    /**
     * 模型是否已加载
     */
    val isModelLoaded: Boolean
        get() = imageEngine.isLoaded

    // ── 飞书 ReAct Agent（懒创建，Koog 驱动，Phase 5）────────────────────────────

    private var cachedFeishuAgent: KoogReActAgent? = null

    /** 缓存的 Feishu Agent 对应的配置，用于检测配置变更 */
    private var cachedFeishuAgentConfig: RemoteModelConfig? = null

    // ── chat ReAct Agent 已抽出到 RemoteChatEngine（决策3 / ADR-010）──

    /**
     * 获取或创建飞书 ReAct Agent。
     * 优先使用用户配置的远程模型，未配置时使用 PoLang Server 默认兜底。
     *
     * 当用户配置发生变更时（cachedFeishuAgentConfig != userRemoteConfig），
     * 自动重建 Agent 以确保使用最新的 API Key / baseUrl / model。
     *
     * **WindowManager 参数已删除（Phase 4 KMP 抽取）**：飞书 RPA 工具集
     * （Android 侧 RemoteControlToolService）由组合根经 [remoteImToolRegistryProvider] 注入。
     */
    fun getFeishuAgent(callback: RemoteReActAgentCallback): KoogReActAgent? {
        val existing = cachedFeishuAgent
        val currentConfig = userRemoteConfig ?: RemoteModelConfig.PICME_SERVER_DEFAULT

        // 配置变更检测：如果用户修改了远程模型配置，重建 Agent
        if (existing != null && cachedFeishuAgentConfig != null) {
            val configChanged = cachedFeishuAgentConfig?.modelId != currentConfig.modelId
                || cachedFeishuAgentConfig?.baseUrl != currentConfig.baseUrl
                || cachedFeishuAgentConfig?.apiKey != currentConfig.apiKey
                || cachedFeishuAgentConfig?.gatewayToken != currentConfig.gatewayToken
                || cachedFeishuAgentConfig?.protocol != currentConfig.protocol
                || cachedFeishuAgentConfig?.providerId != currentConfig.providerId
            if (configChanged) {
                Logger.i("AgentConfigurator", "Remote config changed (model=${currentConfig.modelId}), rebuilding Feishu Agent")
                existing.shutdown()
                cachedFeishuAgent = null
                cachedFeishuAgentConfig = null
            } else {
                return existing
            }
        } else if (existing != null) {
            return existing
        }

        val memProvider = memoryContextProvider
        val cfg = try {
            RemoteReActAgentConfig.Builder()
                .apiKey(currentConfig.apiKey)
                .baseUrl(currentConfig.baseUrl)
                .modelName(currentConfig.modelId)
                .gatewayToken(currentConfig.gatewayToken)
                .deviceId(deviceId)
                .protocol(currentConfig.protocol)
                .providerId(currentConfig.providerId)
                .apply { if (memProvider != null) memoryContextProvider(memProvider) }
                .build()
        } catch (e: Exception) {
            Logger.w("AgentConfigurator", "Failed to build FeishuAgent config", e)
            return null
        }

        // RemoteControlToolService 外部注入（KMP 抽取后 KoogReActAgent 在 commonMain，
        // 不再直构 Android 专有的 RemoteControlToolService(windowManager)）；反射展开
        // ToolSet→ToolRegistry 在组合根（Android）完成。多工具集时 builder 多次 tools(...)。
        val agent = KoogReActAgent(
            config = cfg,
            callback = callback,
            dispatcherProvider = dispatcherProvider,
            memoryStore = chatMemoryStore,
            toolRegistry = remoteImToolRegistryProvider(),
        )
        agent.initialize()
        cachedFeishuAgent = agent
        cachedFeishuAgentConfig = currentConfig
        Logger.i("AgentConfigurator", "Feishu Koog ReAct Agent created: model=${cfg.modelName}, baseUrl=${currentConfig.baseUrl.take(40)}")
        return agent
    }

    /**
     * 清除飞书 ReAct Agent 缓存（用于配置变更后重建）
     */
    fun clearFeishuAgent() {
        cachedFeishuAgent?.shutdown()
        cachedFeishuAgent = null
        cachedFeishuAgentConfig = null
        Logger.i("AgentConfigurator", "Feishu ReAct Agent cleared")
    }
}
