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
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.platform.currentPlatform
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

/**
 * chat 链路的 Koog Agent（Phase 4：替代 RemoteReActAgent + StreamingSyncChatModel 的 chat 专用路径）。
 *
 * 与旧 langchain4j AiServices 路径的关键差异：
 * - **纯 suspend**：`agent.run(input, sessionId)` 即协程挂起，删除 CountDownLatch / suspendCoroutine 桥。
 *   取消经调用方 `withTimeout` 的协程 cancel 级联（Koog 0.5.3+ 已修 cancellation 包裹）。
 * - **记忆**：Koog ChatMemory feature + [KoogSessionHistoryProvider]（包 [ChatMemoryStore]），
 *   三不变式（System 不落盘 / tool_call 块原子裁剪 / 双向配对剔除）由 store 强制；feature **不**设 windowSize
 *   （避免朴素计数裁剪拆散 tool_call 块致远端 400）。
 * - **网关 header**：经 [RemoteModelFactory.createKoogExecutor] 的 extraHeaders 注入（X-App-Token / X-Device-Id），
 *   auth 仍走 apiKey 标准路径。
 * - **流式**：onLLMStreamingFrameReceived 把 TextDelta 累积成本轮快照（[com.mamba.picme.agent.core.inference.remote.ChatStreamEvent.TextSnapshot]
 *   语义=累计全文非 delta），onLLMStreamingStarting 重置（新一轮从空累计）；onToolCallStarted 发 ToolCallStarted。
 * - **指标**：onLLMCallCompleted 累加 token + 录制 [LlmCallRecord]（DEBUG 全文 / release 纯指标，双模式隐私）。
 *
 * **不可变契约**：[runChat] 返回 `(summary, AgentExecutionMetrics)`，由 RemoteChatEngine 包成冻结的
 * StreamChatResult / ChatStreamEvent；onPartialText / onToolCall 经 processChatReAct 透传到 UI。
 *
 * **记忆快照新鲜度**：system prompt（含【关于用户】记忆快照）在 build 期烘焙；记忆快照变更时重建 AIAgent
 * （executor / memoryStore / historyProvider 复用，历史经 DataStore 跨重建留存）。
 *
 * **回调闭包**：EventHandler lambda 在 build 期捕获，引用 per-run 持有字段 [currentPartialText] / [currentToolCall]
 *（runChat 开头赋值、finally 清空），避免捕获过期回调；runs 串行（processChatReAct 的 isRunning 互斥）。
 */
@OptIn(ExperimentalAtomicApi::class)
class KoogChatAgent(
    private val config: RemoteReActAgentConfig,
    private val toolRegistry: ToolRegistry,
    memoryStore: ChatMemoryStore,
) {
    private val tag = "KoogChatAgent"

    // internal（非 private）：commonTest 契约测试需断言 protocol/providerId 透传到工厂产物
    internal val executorBundle = RemoteModelFactory.createKoogExecutor(
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

    private val historyProvider = KoogSessionHistoryProvider(memoryStore)

    /**
     * 当轮 traceId 持有器（runChat 开头写入，onLLMCallCompleted 录制时读取）。
     * 组合根在构建本 agent 后把它注入实现 [TraceIdAware] 的工具集（ChatToolService），
     * 使 Koog 链路下 tool 执行也带 traceId（原 init 内 `as? ChatToolService` 类型判断
     * 随 KMP 抽取移除——本类在 commonMain，reflect.ToolSet 是 Koog 1.1.1 JVM-only API）。
     */
    val traceIdHolder = TraceIdHolder()

    // 单 run 累计状态（runs 串行：processChatReAct 的 isRunning 互斥）。StringBuilder/Atomic 线程安全，
    // 因 EventHandler 可能在 Koog 内部调度线程触发（非调用 runChat 的线程）。
    private val snapshotBuffer = StringBuilder()
    private val promptTokens = AtomicInt(0)
    private val completionTokens = AtomicInt(0)

    // per-run 回调持有（EventHandler lambda 读这两个字段，而非捕获 runChat 的入参，避免重建/跨 run 失效）
    @Volatile private var currentPartialText: ((snapshot: String) -> Unit)? = null
    @Volatile private var currentToolCall: ((toolName: String, args: String) -> Unit)? = null

    @Volatile private var running = false
    private var currentSessionId: String = "default"

    // 记忆快照驱动的懒重建（快照变更才重建 AIAgent；executor/store/provider 复用）
    private var builtSnapshot: String? = null
    private var builtAgent: AIAgent<String, String>? = null

    fun setSessionId(sessionId: String) {
        currentSessionId = sessionId
    }

    fun isRunning(): Boolean = running

    /**
     * 执行一次 chat ReAct（suspend）。返回 (summary, metrics)；异常向上抛（由 processChatReAct 的
     * withTimeout/catch 处理；取消经 withTimeout 级联）。
     */
    suspend fun runChat(
        input: String,
        traceId: String?,
        onPartialText: (snapshot: String) -> Unit,
        onToolCall: (toolName: String, args: String) -> Unit,
    ): Pair<String, AgentExecutionMetrics> {
        running = true
        traceIdHolder.value = traceId
        snapshotBuffer.setLength(0)
        promptTokens.store(0)
        completionTokens.store(0)
        currentPartialText = onPartialText
        currentToolCall = onToolCall
        val started = Clock.System.now().toEpochMilliseconds()
        return try {
            val summary = agent().run(input, currentSessionId)
            val latencyMs = Clock.System.now().toEpochMilliseconds() - started
            summary to AgentExecutionMetrics(
                latencyMs = latencyMs,
                promptTokens = promptTokens.load().takeIf { it > 0 },
                completionTokens = completionTokens.load().takeIf { it > 0 },
                modelName = config.modelName.ifBlank { null },
            )
        } finally {
            running = false
            traceIdHolder.value = null
            currentPartialText = null
            currentToolCall = null
        }
    }

    /** 取或按记忆快照新鲜度重建 AIAgent。 */
    private fun agent(): AIAgent<String, String> {
        val snapshot = config.memoryContextProvider?.snapshot()?.trim()?.ifEmpty { null }
        val cached = builtAgent
        if (cached != null && snapshot == builtSnapshot) return cached
        val agent = buildAgent(composeSystemPrompt(snapshot))
        builtAgent = agent
        builtSnapshot = snapshot
        Logger.i(tag, "Built Koog AIAgent: model=${config.modelName}, snapshotLen=${snapshot?.length ?: 0}")
        return agent
    }

    private fun buildAgent(systemPrompt: String): AIAgent<String, String> =
        // 自定义策略：修复 Koog 1.1.1 内建 singleRunStrategy 丢「文本+tool_calls 同帧」
        // 工具调用的缺陷（chat 多轮工具任务同样受影响），详见 poLangSingleRunStrategy KDoc。
        //（2026-09-25 核实：1.3.0 仍未修，本策略继续必要。）
        AIAgent.builder()
            // 直接传策略实例（命名 lambda 重载 graphStrategy(name){...} 是 Koog 1.1.1 JVM-only
            // 便捷 API；策略内部已命名 "polang_single_run"，语义等价）。
            .graphStrategy(poLangSingleRunStrategy())
            .promptExecutor(executorBundle.executor)
            .llmModel(executorBundle.model)
            .toolRegistry(toolRegistry)
            // baseParams（temperature 钳制 + DeepSeek thinking=disabled + maxTokens）必须经 polangSystemPrompt
            // 烘焙进 Prompt；不能用 .systemPrompt(String)——它从空 Prompt.Empty 扩展，丢弃全部 params。
            .prompt(polangSystemPrompt(id = "polang-chat", systemPrompt = systemPrompt, params = executorBundle.baseParams))
            // Koog maxIterations 数的是子图节点执行次数（一轮工具调用 ≈ 2-3 步），旧 AiServices
            // 数的是 LLM 轮次；×3 对齐旧语义上限（详见 KoogReActAgent 同款注释，飞书真机撞顶实测）。
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
                        currentPartialText?.invoke(snapshotBuffer.toString())
                    }
                }
                events.onToolCallStarting { ctx ->
                    Logger.d(tag, "tool call: ${ctx.toolName}(${ctx.toolArgs.toString().take(100)})")
                    currentToolCall?.invoke(ctx.toolName, ctx.toolArgs.toString())
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
                                source = RECORD_SOURCE,
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

    /** base system prompt + 【关于用户】记忆快照（快照空则原样返回，零开销）。 */
    private fun composeSystemPrompt(snapshot: String?): String {
        val base = config.systemPrompt
        return if (snapshot.isNullOrBlank()) base else "$base\n\n$snapshot"
    }

    /**
     * 网关鉴权 header（与 RemoteReActAgent 经 MambaAgentFactory.customHeader 注入的等价）：
     * X-App-Token=gatewayToken（注册/访客均带）、X-Device-Id=deviceId、X-Platform=currentPlatform。
     */
    private fun buildGatewayHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        config.gatewayToken?.takeIf { it.isNotBlank() }?.let { token -> headers["X-App-Token"] = token }
        if (config.deviceId.isNotBlank()) headers["X-Device-Id"] = config.deviceId
        headers["X-Platform"] = currentPlatform
        return headers
    }

    private companion object {
        const val RECORD_SOURCE = "chat-koog"

        /** Koog 一轮工具调用消耗的步数估计（nodeLLMRequest + nodeExecuteTool 等）。 */
        const val KOOG_STEPS_PER_LLM_ROUND = 3
    }
}
