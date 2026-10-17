package com.mamba.picme.agent.core.facade

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import com.mamba.picme.agent.core.inference.local.ImageInferenceEngine
import com.mamba.picme.agent.core.inference.remote.RemoteChatEngine
import com.mamba.picme.agent.core.platform.storage.ChatHistoryCleaner
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.agent.core.platform.thread.DispatcherProvider

/**
 * [AgentOrchestrator] 的全部平台注入项（Phase 4 KMP 抽取，组合根模式）。
 *
 * 由平台组合根（Android = `androidApp` 的 `AndroidAgentComposition`，所有平台实现的
 * 唯一直构点）构建并传入 [AgentOrchestrator.initialize]；facade 自身不再触碰任何
 * 平台类型（Context / WindowManager / DataStore / Koog reflect）。
 *
 * 工具集说明（reflect.ToolSet / asToolsByClass 是 Koog JVM-only API，commonMain 不可引用）：
 * - [chatToolDescriptors] / [cameraToolDescriptors]：组合根经 `asToolsByClass()` 反射展开后
 *   取 `tool.descriptor` 传入，供 [com.mamba.picme.agent.core.inference.remote.tool.ToolInventory]
 *   确定性生成 system prompt「可用工具」段（与 agent 实际持有的 registry 同源，零漂移）。
 * - [chatToolRegistry] / [cameraToolRegistry]：agent 实际持有的工具注册表，同源展开。
 * - [remoteImToolRegistryProvider]：飞书 RPA 工具集（Android 侧 `RemoteControlToolService`，
 *   依赖 WindowManager）按需构建——飞书 agent 懒创建时才取 WindowManager，避免启动期触碰。
 */
data class AgentDependencies(
    /** 平台命名 dispatcher 提供者（编排/模型/DataStore/网络四隔离线程池）。 */
    val dispatcherProvider: DispatcherProvider,

    /** Koog 对话记忆持久化（Android actual = DataStore 的 KoogMessageMemoryStore）。 */
    val chatMemoryStore: ChatMemoryStore,

    /** 旧 langchain4j 键空间（`memory_`）会话历史清理（Android actual = MemoryManager）。 */
    val chatHistoryCleaner: ChatHistoryCleaner,

    /** 端侧 VLM 引擎工厂（Android actual = LocalLlmEngine(MNN JNI)）。调用一次、实例复用。 */
    val imageEngineProvider: () -> ImageInferenceEngine,

    /** chat 场域工具描述元数据（ChatToolService 反射展开），system prompt 清单段用。 */
    val chatToolDescriptors: List<ToolDescriptor>,

    /** chat 场域工具注册表（与 [chatToolDescriptors] 同源展开）。 */
    val chatToolRegistry: ToolRegistry,

    /**
     * chat 场域工具对象清单（与 [chatToolRegistry] 同源，M3 意图级裁剪用）：引擎按路由契约
     * 子集过滤它重建子集 registry + prompt 清单段。null = 平台未注入 → 裁剪不可用，恒全量
     *（行为与 M2 一致）。类型 `ToolBase<*, *>` 是 Koog KMP 类型，commonMain 可引用；
     * 反射展开仍在 JVM 组合根完成（asToolsByClass），iOS 经 ChatToolManifest 手工清单注入。
     */
    val chatTools: List<ToolBase<*, *>>? = null,

    /** 相机场域工具描述元数据（CameraToolService 反射展开），system prompt 清单段用。 */
    val cameraToolDescriptors: List<ToolDescriptor>,

    /** 相机场域工具注册表（与 [cameraToolDescriptors] 同源展开）。 */
    val cameraToolRegistry: ToolRegistry,

    /** 飞书 RPA 工具注册表按需构建（RemoteControlToolService 依赖 WindowManager，懒创建时取用）。 */
    val remoteImToolRegistryProvider: () -> ToolRegistry,

    /**
     * chat system prompt 组装器（Phase 6.2 iOS 注入点）：输入 chat 工具描述元数据，输出完整 prompt。
     * 默认 = [RemoteChatEngine.buildChatSystemPrompt]（Android 现状，零行为变化）；iOS 组合根注入
     * `IosChatPrompt.build`（精简版——iOS v1 无 JS/修图/记忆工具，沿用全量 prompt 会诱使 LLM 幻觉调用）。
     */
    val chatPromptBuilder: (List<ToolDescriptor>) -> String = RemoteChatEngine::buildChatSystemPrompt,
)
