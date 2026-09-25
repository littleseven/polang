package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.streaming.StreamFrame
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecord
import com.mamba.picme.agent.core.inference.remote.log.TraceIdAware
import com.mamba.picme.agent.core.inference.remote.log.TraceIdHolder
import com.mamba.picme.agent.core.inference.remote.react.AgentExecutionMetrics
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentCallback
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import com.mamba.picme.agent.core.platform.currentPlatform
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.agent.core.platform.thread.DispatcherProvider
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 相机 + 飞书（远程控制 RPA）链路的 Koog Agent（
 * 替代 langchain4j 期的 RemoteReActAgent + StreamingSyncChatModel + AiServices）。
 *
 * 与旧 langchain4j 路径的关键差异：
 * - **纯协程**：[executeTask] 在内部 [CoroutineScope]（SupervisorJob + 注入的
 *   [DispatcherProvider.orchestratorDispatcher]，语义对齐旧 Dispatchers.IO 承载网络/工具等待）
 *   launch 一个 Job，替代旧的单线程 executor；[cancel] = 取消当前 Job + cancelled 标志
 *   （Koog 1.1.1 已正确响应协程取消，agent.run 抛 CancellationException → 走
 *   onComplete(0, "Task cancelled", ...) 分支，语义对齐旧实现）。
 * - **记忆**：Koog ChatMemory feature（**不**设 windowSize）+ [KoogSessionHistoryProvider]（包
 *   注入的 [ChatMemoryStore]；Android actual = DataStore 的 KoogMessageMemoryStore），三不变式
 *   （System 不落盘 / tool_call 块原子裁剪 / 双向配对剔除）由 store 强制；sessionId 语义对齐旧行为
 *   （飞书默认 "feishu_p2p"，相机由 [setSessionId] 切 "camera" 等；历史不迁移，新键前缀
 *   `koog_memory_` 自然隔离）。
 * - **工具集解耦（KMP 抽取）**：本类在 commonMain，不再直构 Android 专有的
 *   `RemoteControlToolService(windowManager)`——飞书 RPA 等 Android 工具集经
 *   [additionalToolSets] 由组合根注入；相机主工具集仍经 [toolService] 传入。
 *   LlmCallRecord 来源标签不再靠 `is CameraToolService` 类型判断，改由调用方经
 *   [recordSource] 显式声明（[RECORD_SOURCE_CAMERA] / [RECORD_SOURCE_FEISHU]）。
 * - **网关 header**：经 [RemoteModelFactory.createKoogExecutor] 的 extraHeaders 注入
 *   （X-App-Token / X-Device-Id / X-Platform，照 [KoogChatAgent] 的 buildGatewayHeaders 模式）。
 * - **流式**：onLLMStreamingFrameReceived 把 TextDelta 累积成本轮快照 → cb.onPartialText（累计全文非
 *   delta，与旧 StreamingSyncChatModel 旁路语义一致）；onLLMStreamingStarting 重置（新一轮从空累计）。
 * - **指标**：onLLMCallCompleted 累加 token + 录制 [LlmCallRecord]（DEBUG 全文 / release 纯指标，
 *   双模式隐私）；RECORD_SOURCE 区分 "camera-koog" / "feishu-koog"。
 *
 * **不可变契约**：公开 API（[executeTask]/[cancel]/[shutdown]/[isRunning]/[setSessionId]/[resetSession]/
 * [initialize]/[getLastExecutionMetrics]）与旧 RemoteReActAgent 逐方法对齐，调用方
 * （AgentOrchestrator.processCameraInput / processRemoteImInput、AgentConfigurator.getFeishuAgent）
 * 只需改构造点；[RemoteReActAgentCallback] 接口不变。
 *
 * **回调闭包**：EventHandler lambda 在 build 期捕获，统一读 per-run 持有字段 [currentCallback]
 * （executeTask 的 launch 开头赋值、finally 清空），避免捕获过期回调；runs 串行（running 互斥守卫）。
 */
@OptIn(ExperimentalAtomicApi::class)
class KoogReActAgent(
    private val config: RemoteReActAgentConfig,
    private val callback: RemoteReActAgentCallback,
    dispatcherProvider: DispatcherProvider,
    private val memoryStore: ChatMemoryStore,
    toolRegistry: ToolRegistry,
    private val recordSource: String = RECORD_SOURCE_FEISHU,
) {
    private val tag = "KoogReActAgent"

    // 工具集注入（KMP 化后的 additionalToolSets 语义）：原计划签名 `additionalToolSets:
    // List<reflect.ToolSet>` 不可行——reflect.ToolSet 与 ToolRegistryBuilder.tools(ToolSet) 是
    // Koog 1.1.1 jvmCommonMain API，commonMain 不可引用。改用 KMP 类型 ToolRegistry：
    // - 相机路径：组合根传 ToolRegistry { tools(CameraToolService.getInstance()) }
    //   （recordSource=RECORD_SOURCE_CAMERA）。
    // - 飞书路径：组合根传 ToolRegistry { tools(RemoteControlToolService(windowManager)) }
    //   ——RemoteControlToolService 从本类直构（windowManager!!）改为外部注入。
    // 多工具集组合由组合根在构建 registry 时完成（builder 多次 tools(...) 或 ToolRegistry +）。
    private val effectiveToolRegistry: ToolRegistry = toolRegistry

    private val executorBundle = RemoteModelFactory.createKoogExecutor(
        config = RemoteModelConfig(
            modelId = config.modelName,
            providerId = config.providerId,
            protocol = config.protocol,
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            gatewayToken = config.gatewayToken ?: "",
            deviceId = config.deviceId,
        ),
        extraHeaders = buildGatewayHeaders(),
    )

    /** 持久化对话记忆（注入式；Android actual 键前缀 koog_memory_，与旧 langchain4j 路径的 memory_ 隔离，历史不迁移）。 */
    private val historyProvider by lazy { KoogSessionHistoryProvider(memoryStore) }

    /**
     * 当轮 traceId 持有器：[executeTask] 的 Job 开始时写入，onLLMCallCompleted 录制 LlmCallRecord 时
     * 读取。组合根在构建本 agent 后把它注入实现 [TraceIdAware] 的工具集（CameraToolService 的
     * dispatchCommand 读它注入 AgentContext，使远程 ReAct 下的 tool 执行也带 traceId）；
     * 飞书路径（RemoteControlToolService）不实现 TraceIdAware，无需注入（语义对齐旧 when 分支，
     * 原 init 内类型判断随 KMP 抽取移除——reflect.ToolSet 是 Koog 1.1.1 JVM-only API）。
     */
    val traceIdHolder = TraceIdHolder()

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** 任务执行作用域：SupervisorJob 隔离单任务失败；orchestratorDispatcher 承载网络/工具等待。 */
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.orchestratorDispatcher)

    @Volatile
    private var currentJob: Job? = null

    /** 记录每次执行的性能指标 */
    private var lastExecutionMetrics: AgentExecutionMetrics? = null

    // 单 run 累计状态（runs 串行：running 互斥守卫）。StringBuilder/Atomic 线程安全，
    // 因 EventHandler 可能在 Koog 内部调度线程触发（非调用 executeTask 的线程）。
    private val snapshotBuffer = StringBuilder()
    private val promptTokens = AtomicInt(0)
    private val completionTokens = AtomicInt(0)

    // per-run 回调持有（EventHandler lambda 读这个字段，而非捕获 executeTask 的入参，避免重建/跨 run 失效）
    @Volatile
    private var currentCallback: RemoteReActAgentCallback? = null

    /** 当前会话的 memory ID。飞书默认 "feishu_p2p"；相机经 [setSessionId] 切换（如 "camera"）。 */
    private var sessionId: String = "feishu_p2p"

    // 记忆快照驱动的懒重建（快照变更才重建 AIAgent；executor/store/provider 复用，历史经 DataStore 跨重建留存）
    private var builtSnapshot: String? = null
    private var builtAgent: AIAgent<String, String>? = null

    /** 切换会话：换 memory ID。历史按 sessionId 分键持久化，无需重建 agent。 */
    fun setSessionId(id: String) {
        if (id.isNotBlank()) sessionId = id
    }

    fun initialize() {
        Logger.i(tag, "Koog ReAct Agent initialized: model=${config.modelName}, source=$recordSource")
    }

    /** 获取最近一次执行的性能指标 */
    fun getLastExecutionMetrics(): AgentExecutionMetrics? = lastExecutionMetrics

    fun isRunning(): Boolean = running.load()

    /**
     * 执行一次 ReAct 任务（回调式，签名与旧 RemoteReActAgent 对齐）。
     * 已在运行时立即回调 onError（IllegalStateException）并返回；否则 launch 协程异步执行，
     * 结果经 [RemoteReActAgentCallback] 回调。
     */
    fun executeTask(userPrompt: String, taskCallback: RemoteReActAgentCallback? = null, traceId: String? = null) {
        if (running.load()) {
            (taskCallback ?: callback).onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.store(true)
        cancelled.store(false)
        promptTokens.store(0)
        completionTokens.store(0)
        snapshotBuffer.setLength(0)

        currentJob = scope.launch {
            runTask(userPrompt, taskCallback ?: callback, traceId)
        }
    }

    /** 取消当前任务：置 cancelled 标志并取消当前 Job（Koog agent.run 响应协程取消）。 */
    fun cancel() {
        cancelled.store(true)
        currentJob?.cancel()
    }

    fun shutdown() {
        cancel()
        scope.cancel()
    }

    /**
     * 重置当前会话（清除持久化记忆并废弃缓存的 agent，下次按新快照重建）。
     * 用于开始新的对话或重置状态。
     */
    fun resetSession() {
        builtAgent = null
        builtSnapshot = null
        val id = sessionId
        scope.launch {
            memoryStore.clear(id)
        }
        Logger.d(tag, "Session reset: $id")
    }

    // ==================== Koog ReAct 执行（替代 AiServices 代理循环）====================

    private suspend fun runTask(userPrompt: String, cb: RemoteReActAgentCallback, traceId: String?) {
        Logger.d(tag, "runTask start: userPrompt='$userPrompt', session='$sessionId'")
        cb.onLoopStart(1)

        val startTime = Clock.System.now().toEpochMilliseconds()
        traceIdHolder.value = traceId
        currentCallback = cb
        try {
            // Koog agent.run 自动驱动 ReAct 循环（工具调用 → 结果回填 → 继续），
            // maxIterations 上限对齐旧 AiServices 行为。
            val summary = agent().run(userPrompt, sessionId)

            val metrics = buildMetrics(startTime)
            lastExecutionMetrics = metrics
            Logger.i(tag, "Task complete: result='$summary', latency=${metrics.latencyMs}ms, " +
                "prompt=${promptTokens.load()}, completion=${completionTokens.load()}")
            cb.onComplete(1, summary, totalTokens(), metrics)
        } catch (e: CancellationException) {
            // 取消（cancel() 或调用方协程取消级联）：语义对齐旧实现的 cancelled 分支
            val metrics = buildMetrics(startTime)
            lastExecutionMetrics = metrics
            Logger.d(tag, "Task cancelled")
            cb.onComplete(0, "Task cancelled", totalTokens(), metrics)
        } catch (e: Exception) {
            val metrics = buildMetrics(startTime)
            lastExecutionMetrics = metrics
            if (cancelled.load()) {
                Logger.d(tag, "Task cancelled")
                cb.onComplete(0, "Task cancelled", totalTokens(), metrics)
            } else {
                Logger.e(tag, "Agent execution failed", e)
                val friendlyError = RuntimeException(buildFriendlyErrorMessage(e), e)
                cb.onError(0, friendlyError, totalTokens(), metrics)
            }
        } finally {
            traceIdHolder.value = null
            currentCallback = null
            running.store(false)
        }
        Logger.d(tag, "runTask end")
    }

    private fun buildMetrics(startTime: Long): AgentExecutionMetrics = AgentExecutionMetrics(
        latencyMs = Clock.System.now().toEpochMilliseconds() - startTime,
        promptTokens = promptTokens.load().takeIf { it > 0 },
        completionTokens = completionTokens.load().takeIf { it > 0 },
        modelName = config.modelName.ifBlank { null },
    )

    private fun totalTokens(): Int = promptTokens.load() + completionTokens.load()

    /** 取或按记忆快照新鲜度重建 AIAgent（快照烘焙进 system prompt，变更才重建）。 */
    private fun agent(): AIAgent<String, String> {
        val snapshot = config.memoryContextProvider?.snapshot()?.trim()?.ifEmpty { null }
        val cached = builtAgent
        if (cached != null && snapshot == builtSnapshot) return cached
        val agent = buildAgent(composeSystemPrompt(config.systemPrompt, config.memoryContextProvider))
        builtAgent = agent
        builtSnapshot = snapshot
        Logger.i(tag, "Built Koog AIAgent: model=${config.modelName}, snapshotLen=${snapshot?.length ?: 0}")
        return agent
    }

    private fun buildAgent(systemPrompt: String): AIAgent<String, String> =
        // 自定义策略（graphStrategy 的 lambda 忽略入参 builder，直接返回预建策略）：
        // 修复 Koog 1.1.1 内建 singleRunStrategy 在 nodeSendToolResult 出边先匹配 onTextMessage
        // 导致「文本+tool_calls 同帧」响应丢工具调用的缺陷，详见 poLangSingleRunStrategy KDoc。
        //（2026-09-25 核实：1.3.0 仍未修，本策略继续必要。）
        AIAgent.builder()
            // 直接传策略实例（命名 lambda 重载 graphStrategy(name){...} 是 Koog 1.1.1 JVM-only
            // 便捷 API；策略内部已命名 "polang_single_run"，语义等价）。
            .graphStrategy(poLangSingleRunStrategy())
            .promptExecutor(executorBundle.executor)
            .llmModel(executorBundle.model)
            .toolRegistry(effectiveToolRegistry)
            // baseParams（temperature 钳制 + DeepSeek thinking=disabled + maxTokens）必须经 polangSystemPrompt
            // 烘焙进 Prompt；不能用 .systemPrompt(String)——它从空 Prompt.Empty 扩展，丢弃全部 params。
            .prompt(polangSystemPrompt(id = "polang-react", systemPrompt = systemPrompt, params = executorBundle.baseParams))
            // Koog maxIterations 数的是**子图节点执行次数**（一轮工具调用 ≈ nodeLLMRequest +
            // nodeExecuteTool ≈ 2-3 步），而旧 AiServices maxIterations 数的是 LLM 轮次。
            // 真机实测（2026-08-07）：直接传 10 时约 5 轮工具调用就抛
            // AIAgentMaxNumberOfIterationsReachedException（飞书 navigate_to 循环撞顶）。
            // ×3 换算对齐旧「10 轮 LLM」语义上限（仅是上限，正常 1-3 轮即返回，无副作用）。
            //（2026-09-25 核实：1.3.0 仍按 plan step 计数——AIAgentPlanner 每 step ++iterations，
            // 语义未变，换算仍必要。）
            .maxIterations((config.maxIterations * KOOG_STEPS_PER_LLM_ROUND).coerceAtLeast(KOOG_STEPS_PER_LLM_ROUND))
            .install(ChatMemory.Feature) { cm ->
                cm.chatHistoryProvider(historyProvider)
            }
            .install(EventHandler.Feature) { events ->
                events.onLLMStreamingStarting { ctx ->
                    // 新一轮 LLM 流式开始（如工具调用后的下一轮）：本轮快照从空重新累计
                    Logger.d(tag, "streaming round start: model=${ctx.model?.id}")
                    snapshotBuffer.setLength(0)
                }
                events.onLLMStreamingFrameReceived { ctx ->
                    val frame = ctx.streamFrame
                    if (frame is StreamFrame.TextDelta) {
                        snapshotBuffer.append(frame.text)
                        currentCallback?.onPartialText(snapshotBuffer.toString())
                    }
                }
                events.onToolCallStarting { ctx ->
                    Logger.d(tag, "tool call: ${ctx.toolName}(${ctx.toolArgs.toString().take(100)})")
                    currentCallback?.onToolCall(1, ctx.toolName, ctx.toolArgs.toString())
                }
                events.onLLMCallCompleted { ctx ->
                    val meta = ctx.response?.metaInfo
                    meta?.inputTokensCount?.let { prompt -> promptTokens.addAndFetch(prompt) }
                    meta?.outputTokensCount?.let { comp -> completionTokens.addAndFetch(comp) }
                    val rec = RemoteModelFactory.recorder
                    if (rec != null) {
                        rec.record(
                            LlmCallRecord(
                                createdAt = Clock.System.now().toEpochMilliseconds(),
                                source = recordSource,
                                model = ctx.model?.id,
                                success = true,
                                latencyMs = null,
                                promptTokens = meta?.inputTokensCount,
                                completionTokens = meta?.outputTokensCount,
                                totalTokens = meta?.totalTokensCount,
                                // Koog EventHandler 不暴露原始请求 JSON；DEBUG 全文 body 需 Ktor Logging（后续）。
                                requestJson = "",
                                responseJson = if (RemoteModelFactory.captureContent) {
                                    LlmCallRecord.cap(ctx.response?.textContent())
                                } else {
                                    null
                                },
                                errorMessage = null,
                                traceId = traceIdHolder.value,
                            )
                        )
                    }
                }
            }
            .build()

    /**
     * 网关鉴权 header（与旧 RemoteReActAgent 经 MambaAgentFactory.customHeader 注入的等价）：
     * X-App-Token=gatewayToken（注册/访客均带）、X-Device-Id=deviceId（非空才带）、X-Platform=currentPlatform。
     */
    private fun buildGatewayHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        config.gatewayToken?.takeIf { it.isNotBlank() }?.let { token -> headers["X-App-Token"] = token }
        if (config.deviceId.isNotBlank()) headers["X-Device-Id"] = config.deviceId
        headers["X-Platform"] = currentPlatform
        return headers
    }

    companion object {
        /** LlmCallRecord 来源标签：相机链路（chat 链路是 KoogChatAgent 的 "chat-koog"）。 */
        const val RECORD_SOURCE_CAMERA = "camera-koog"

        /** LlmCallRecord 来源标签：飞书（远程控制 RPA）链路。 */
        const val RECORD_SOURCE_FEISHU = "feishu-koog"

        /** Koog 一轮工具调用消耗的步数估计（nodeLLMRequest + nodeExecuteTool 等），见 buildAgent 注释。 */
        private const val KOOG_STEPS_PER_LLM_ROUND = 3
    }
}

/**
 * 把基础 system prompt 与记忆快照拼成最终 system message 文本。快照为空（无 provider / provider
 * 返回空白）时原样返回 [base]，零开销。（自旧 RemoteReActAgent.kt 随迁，供 JVM 单测。）
 */
internal fun composeSystemPrompt(base: String, provider: MemoryContextProvider?): String {
    val snapshot = provider?.snapshot()?.trim()?.ifEmpty { null } ?: return base
    return "$base\n\n$snapshot"
}

/**
 * 把底层异常转换为飞书/相机用户友好的错误描述（逻辑自旧 RemoteReActAgent 原样随迁：
 * upstream_error → 服务不可用文案；tool_calls 顺序异常 → 会话重置提示）。
 */
internal fun buildFriendlyErrorMessage(original: Throwable): String {
    val causeChain = generateSequence<Throwable>(original) { it.cause }
    val hasUpstream = causeChain.any { it.message?.contains("upstream_error", ignoreCase = true) == true }
    val hasToolSequence = original.message?.contains("tool_calls", ignoreCase = true) == true

    return when {
        hasUpstream -> {
            "远程模型服务暂时不可用（upstream error），请稍后重试，或到设置切换其他模型供应商。"
        }
        hasToolSequence -> {
            "对话历史中的工具调用消息顺序异常，已自动重置会话，请重新发送指令。"
        }
        else -> {
            "远程模型调用失败：${original.message ?: "未知错误"}"
        }
    }
}
