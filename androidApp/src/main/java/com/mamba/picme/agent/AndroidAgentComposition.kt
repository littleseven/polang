package com.mamba.picme.agent

import android.content.Context
import android.view.WindowManager
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asToolsByClass
import com.mamba.picme.agent.core.facade.AgentDependencies
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.inference.remote.tool.CameraToolService
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.inference.remote.tool.RemoteControlToolService
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.KoogMessageMemoryStore
import com.mamba.picme.agent.core.platform.storage.MemoryManager
import com.mamba.picme.agent.core.platform.thread.SharedDispatcherProvider

/**
 * Android 组合根（Phase 4 KMP 抽取）：所有平台实现的**唯一直构点**。
 *
 * commonMain 的 facade（[AgentOrchestrator] / [AgentConfigurator] / LocalModelService /
 * RemoteChatEngine）只依赖注入接口；本 object 负责构建 Android actual 并一次性 wiring：
 * - `KoogMessageMemoryStore` / `MemoryManager`（DataStore，记忆持久化/旧键清理）
 * - `LocalLlmEngine`（MNN JNI 端侧 VLM 引擎，[ImageInferenceEngine] actual）
 * - chat/相机工具集：`asToolsByClass()` 反射展开（Koog JVM-only API，只能在 Android 侧做），
 *   ToolDescriptor 清单（system prompt 用）与 ToolRegistry（agent 持有）同源派生，零漂移
 * - 飞书 RPA 工具集（RemoteControlToolService，依赖 WindowManager）按需构建——
 *   飞书 agent 懒创建时才取 WindowManager
 *
 * 接线：`PoLangApplication.onCreate` 调 [initialize]，早于一切 `AgentOrchestrator.getInstance()`。
 */
object AndroidAgentComposition {

    private const val TAG = "AndroidAgentComposition"

    /**
     * 端侧 VLM 引擎单例（注入 [AgentOrchestrator] 的同一实例）。
     *
     * commonMain 侧只暴露 `ImageInferenceEngine` 接口视图；androidApp 消费者需要
     * Android 专有 API（Bitmap 便捷重载 / trimMemory / lastGenerationMetrics /
     * isLoadedAs）时经此取具体类型（如 TAG 打标流水线、chat 单图理解性能指标）。
     */
    lateinit var localLlmEngine: LocalLlmEngine
        private set

    @Volatile
    private var initialized = false

    /** 应用启动接线入口（幂等：重复调用直接跳过，单例语义与 AgentOrchestrator.initialize 对齐）。 */
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) {
            Logger.w(TAG, "initialize called twice, skipping")
            return
        }
        val appContext = context.applicationContext
        val dispatcherProvider = SharedDispatcherProvider.instance

        val engine = LocalLlmEngine(appContext, dispatcherProvider)
        localLlmEngine = engine

        // chat/相机工具集反射展开（与旧 reflect.ToolSet 扫描同一函数）：descriptor 清单与
        // registry 同源派生，保证 system prompt「可用工具」段与 agent 实际持有工具零漂移。
        val chatTools = ChatToolService.getInstance().asToolsByClass()
        val cameraTools = CameraToolService.getInstance().asToolsByClass()

        AgentOrchestrator.initialize(
            AgentDependencies(
                dispatcherProvider = dispatcherProvider,
                chatMemoryStore = KoogMessageMemoryStore(appContext, dispatcherProvider),
                chatHistoryCleaner = MemoryManager(appContext, dispatcherProvider),
                imageEngineProvider = { engine },
                chatToolDescriptors = chatTools.map { it.descriptor },
                chatToolRegistry = ToolRegistry { tools(chatTools) },
                // M3 意图级工具裁剪：同源工具对象清单注入引擎（子集 registry/prompt 自它过滤派生）
                chatTools = chatTools,
                cameraToolDescriptors = cameraTools.map { it.descriptor },
                cameraToolRegistry = ToolRegistry { tools(cameraTools) },
                // 飞书 RPA 工具集按需构建：飞书 agent 懒创建时才取 WindowManager；
                // RemoteControlToolService 实现 reflect.ToolSet（JVM-only），本模块直构。
                remoteImToolRegistryProvider = {
                    val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    ToolRegistry { tools(RemoteControlToolService(wm)) }
                },
            ),
        )
        initialized = true
        Logger.i(TAG, "Android agent composition initialized")
    }
}
