package com.mamba.picme.agent

import ai.koog.agents.core.tools.ToolRegistry
import com.mamba.picme.agent.core.capability.IosAiOptimizeCapability
import com.mamba.picme.agent.core.capability.IosChartCapability
import com.mamba.picme.agent.core.capability.IosChatGalleryCapability
import com.mamba.picme.agent.core.capability.IosNavigationCapability
import com.mamba.picme.agent.core.capability.IosRunScriptCapability
import com.mamba.picme.agent.core.facade.AgentDependencies
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.local.IosUnavailableImageInferenceEngine
import com.mamba.picme.agent.core.inference.remote.ChatAgentBridge
import com.mamba.picme.agent.core.inference.remote.IosChatPrompt
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolManifest
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.ChatHistoryCleaner
import com.mamba.picme.agent.core.platform.thread.DispatcherProvider
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.data.IosAiOptimizeBridge
import com.mamba.picme.data.IosChartBridge
import com.mamba.picme.data.IosChatSearchBridge
import com.mamba.picme.data.IosMediaRepository
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.data.IosNavigationBridge
import com.mamba.picme.data.IosRunScriptBridge
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * iOS Agent 组合根（Phase 6.2 T5）—— [AgentOrchestrator] 的 iOS 唯一直构点。
 *
 * 镜像 Android 的 `AndroidAgentComposition`（Application.onCreate 接线），
 * 但用 **手工清单** 替代 JVM 反射（K/N 无 `asToolsByClass()`），并注入 iOS 专属
 * actual 实现（T1-T4 产物）。
 *
 * 接线清单（plan §2 核对表逐项）：
 * - `dispatcherProvider` → iosMain `DispatcherProvider` actual（已有）
 * - `chatMemoryStore` → [IosKoogMessageMemoryStore]（T1，NSUserDefaults）
 * - `chatHistoryCleaner` → no-op lambda（iOS 无旧 `memory_` 键空间需清理）
 * - `imageEngineProvider` → [IosUnavailableImageInferenceEngine]（T1 stub，AgentConfigurator 构造期 eager 调用）
 * - `chatToolDescriptors/Registry` → [ChatToolManifest]（T2，手工 8 工具）
 * - `cameraToolDescriptors/Registry` → 空（相机 AI 指令不在 Chat 范围，plan §1 不进第一版）
 * - `remoteImToolRegistryProvider` → 空 ToolRegistry（飞书 RPA 不在 iOS 范围）
 * - `chatPromptBuilder` → [IosChatPrompt.build]（T3，精简版 prompt）
 *
 * **Capability 注册**：[IosChatGalleryCapability]（T4）在 [AgentOrchestrator.initialize]
 * 完成后注册到 `CapabilityRegistry(scene=CHAT)`，使 ChatToolService.dispatchCommand
 * 能路由到 iOS 相册能力执行端。
 *
 * 调用方式：Swift `AppContainer` 在初始化时经 SharedKit framework 调
 * [initialize]，传入 Swift 实现的 [IosMediaRepositoryBridge]（PhMediaBridge）、
 * `deviceId`（identifierForVendor + UserDefaults 持久化）与 [IosChatSearchBridge]
 * （PhSearchBridge → MediaSearchEngine，chat 搜索链路契约 §9），随后取 [chatBridge] 供
 * `ChatViewModel` 消费。
 *
 * [PRIVACY] 红线：组合根不注入任何 [com.mamba.picme.agent.core.inference.local.ImageInferenceEngine]
 * 的真实实现（VLM stub 的 `imageInference` 返回空串），确保 chat 链路无多模态上传能力。
 */
@OptIn(ExperimentalAtomicApi::class)
object IosAgentComposition {

    private const val TAG = "IosAgentComposition"

    private val initialized = AtomicBoolean(false)

    /**
     * chat 桥（[ChatAgentBridge]），Swift 侧 ChatViewModel 的唯一入口。
     * [initialize] 完成后方可访问。
     */
    var chatBridge: ChatAgentBridge? = null
        private set

    /**
     * iOS Agent 接线入口（幂等：重复调用直接跳过）。
     *
     * @param bridge Swift 侧 Photos framework 桥实现（PhMediaBridge）
     * @param deviceId 设备标识（identifierForVendor + UserDefaults 持久化），作访客 X-Device-Id
     * @param searchBridge Swift 侧搜索引擎桥（PhSearchBridge → MediaSearchEngine）；null 时
     *                     chat 搜索保持文件名匹配降级（防御路径，契约 §9）
     * @param chartBridge Swift 侧图表渲染桥（ChartRendererBridge → ChartJsEngine）；null 时
     *                   draw_chart 命令不可用（IosChartCapability.isAvailable=false）
     * @param runScriptBridge Swift 侧脚本执行桥（RunScriptBridge → JsRuntime+JsCoreEngine）；null 时
     *                        run_gallery_script 命令不可用（IosRunScriptCapability.isAvailable=false）
     * @param aiOptimizeBridge Swift 侧 AI 优化桥（AiOptimizeBridge → AiOptimizeService 固定预设路径）；
     *                         null 时 ai_optimize 命令不可用（IosAiOptimizeCapability.isAvailable=false）
     * @param navigationBridge Swift 侧导航桥（2026-09-16 主导航统一）→ 真实切页/弹出；
     *                         null 时 navigate_to 命令不可用（IosNavigationCapability.isAvailable=false）
     * @param debugBuild Swift `#if DEBUG` 传入；true 时诊断日志记全文（captureContent），
     *                   false 仅纯指标（隐私红线，对标 Android BuildConfig.DEBUG 注入）
     */
    fun initialize(
        bridge: IosMediaRepositoryBridge,
        deviceId: String,
        searchBridge: IosChatSearchBridge? = null,
        chartBridge: IosChartBridge? = null,
        runScriptBridge: IosRunScriptBridge? = null,
        aiOptimizeBridge: IosAiOptimizeBridge? = null,
        navigationBridge: IosNavigationBridge? = null,
        debugBuild: Boolean = false
    ) {
        if (!initialized.compareAndSet(false, true)) {
            Logger.w(TAG, "initialize called twice, skipping")
            return
        }

        // 诊断日志三层 recorder + 模块门控 Logger（对标 Android PoLangApplication 安装段）
        com.mamba.picme.agent.core.platform.logging.IosDiagnosticLogStore.install(debugBuild)

        val dispatcherProvider = DispatcherProvider()
        val mediaRepository = IosMediaRepository(bridge)

        // T2 手工清单（替代 Android 的 asToolsByClass 反射展开）
        val chatTools = ChatToolManifest.tools

        AgentOrchestrator.initialize(
            AgentDependencies(
                dispatcherProvider = dispatcherProvider,
                chatMemoryStore = com.mamba.picme.agent.core.platform.storage.IosKoogMessageMemoryStore(),
                chatHistoryCleaner = ChatHistoryCleaner { }, // no-op：iOS 无旧 memory_ 键空间
                imageEngineProvider = { IosUnavailableImageInferenceEngine() },
                chatToolDescriptors = ChatToolManifest.buildDescriptors(),
                chatToolRegistry = ToolRegistry { tools(chatTools) },
                cameraToolDescriptors = emptyList(), // 相机 AI 指令不在 Chat v1 范围
                cameraToolRegistry = ToolRegistry { },
                remoteImToolRegistryProvider = { ToolRegistry { } }, // 飞书 RPA 不在 iOS 范围
                chatPromptBuilder = IosChatPrompt::build,
            )
        )

        // 设置访客设备标识（访客模式仅 X-Device-Id，无需 X-App-Token）
        val orchestrator = AgentOrchestrator.getInstance()
        orchestrator.setDeviceId(deviceId)

        // 远程推理配置由 Swift 侧 ModelConfigStore 决定（用户自定义 > 访客 PICME_SERVER_DEFAULT）。
        // 初始值先设访客模式兜底，Swift AppContainer.init 完成后 ModelConfigStore.applyToOrchestrator() 会覆盖。
        orchestrator.updateRemoteRuntimeConfig(
            remoteConfig = com.mamba.picme.agent.core.remote.config.RemoteModelConfig.PICME_SERVER_DEFAULT,
            privacyLevel = com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel.PERMISSIVE,
        )

        // 注册 iOS chat 相册能力（T4），使 ChatToolService.dispatchCommand → CapabilityRegistry(CHAT) 路由可达
        val chatGalleryCapability = IosChatGalleryCapability(mediaRepository, bridge, searchBridge)
        orchestrator.registerCapability(chatGalleryCapability)

        // 注册 iOS chat 图表能力（draw_chart 执行端 → ChartJsEngine 端侧渲染）
        orchestrator.registerCapability(IosChartCapability(chartBridge))

        // 注册 iOS chat 脚本能力（run_gallery_script 执行端 → JsRuntime+JsCoreEngine 端侧沙箱）
        orchestrator.registerCapability(IosRunScriptCapability(runScriptBridge))

        // 注册 iOS AI 优化能力（ai_optimize 执行端 → AiOptimizeService 固定预设；gacha 由 Swift UI 层分流）
        orchestrator.registerCapability(IosAiOptimizeCapability(aiOptimizeBridge))

        // 注册 iOS 导航能力（2026-09-16 主导航统一：navigate_to 执行端 → Swift 真实切页/弹出）
        orchestrator.registerCapability(IosNavigationCapability(navigationBridge))

        // 创建 chat 桥（searchBaseProvider：意图路由器紧凑状态 hasSearchBase 的 iOS 数据源）
        chatBridge = ChatAgentBridge(orchestrator, searchBaseProvider = { chatGalleryCapability.hasSearchBase })

        Logger.i(TAG, "iOS agent composition initialized (deviceId=${deviceId.take(8)}…)")
    }

    /**
     * 翻页同步 SceneManager（MainTabView currentPage → scene 切换）。
     *
     * 不同步的后果：CapabilityRegistry 按 currentScene 路由，chat_gallery 只在 CHAT
     * 场景激活；iOS 此前从不切场景（恒 UNKNOWN），所有 chat 工具命令被入队并回复
     * 「正在为您切换到对应页面执行操作...」——真机四链路工具层全废的根因（T7 gap）。
     *
     * 页序契约（2026-09-16 主导航统一，对齐 Android MainPagerHost，相机已移出 Pager）：
     * 0=gallery(GALLERY) / 1=organize(GALLERY 沿用) / 2=chat(CHAT) /
     * 3=person（无独立场景，沿用进入前场景，不 transition）/ 4=memories(GALLERY 沿用)。
     *
     * @param page 主 Pager 页索引（0-4）
     */
    fun onMainPageChanged(page: Long) {
        val orchestrator = AgentOrchestrator.getInstance()
        val scene = when (page.toInt()) {
            0, 1, 4 -> SceneManager.Scene.GALLERY
            2 -> SceneManager.Scene.CHAT
            else -> return // person 页沿用进入前场景（对齐 Android MainPagerHost 不 transition）
        }
        orchestrator.transitionToScene(scene, saveToHistory = false)
    }

    /// 相机 cover 打开前的场景暂存（关闭时恢复；人物页无场景映射，重发页映射恢复不到来源场景）
    private var sceneBeforeCamera: SceneManager.Scene? = null

    /**
     * 相机 fullScreenCover 路由同步 SceneManager（2026-09-17 补齐，main-nav.yaml §2）：
     * cover 打开 → CAMERA 场景（对齐 Android 相机路由 ≥RESUMED 时 Scene.CAMERA；
     * iOS 此前 cover 期间沿用来源页场景，navigate_to(camera) 等相机域命令会被错误入队）。
     * cover 关闭 → 恢复暂存场景；MainTabView 随后重发 [onMainPageChanged] 页映射兜底纠偏
     * （页 0/1/2/4 两者一致；人物页页映射为 no-op，全靠暂存恢复，防场景滞留 CAMERA）。
     *
     * @param active 相机 cover 是否呈现中
     */
    fun onCameraRouteChanged(active: Boolean) {
        val orchestrator = AgentOrchestrator.getInstance()
        if (active) {
            sceneBeforeCamera = orchestrator.currentScene.value
                .takeIf { it != SceneManager.Scene.CAMERA }
            orchestrator.transitionToScene(SceneManager.Scene.CAMERA, saveToHistory = false)
        } else {
            sceneBeforeCamera?.let { orchestrator.transitionToScene(it, saveToHistory = false) }
            sceneBeforeCamera = null
        }
    }
}
