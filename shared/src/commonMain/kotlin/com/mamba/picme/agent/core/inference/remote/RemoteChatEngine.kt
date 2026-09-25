package com.mamba.picme.agent.core.inference.remote

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import com.mamba.picme.agent.core.facade.AgentConfigurator
import com.mamba.picme.agent.core.inference.remote.koog.KoogChatAgent
import com.mamba.picme.agent.core.inference.remote.prompt.ChatPromptRules
import com.mamba.picme.agent.core.inference.remote.react.AgentExecutionMetrics
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.inference.remote.tool.ToolInventory
import com.mamba.picme.agent.core.intent.ChatRoutingPolicy
import com.mamba.picme.agent.core.intent.CompactChatState
import com.mamba.picme.agent.core.intent.IntentRouter
import com.mamba.picme.agent.core.intent.UiArtifact
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.agent.core.model.config.personaPromptSegment
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import com.mamba.picme.agent.core.model.context.RenderEnvironment
import com.mamba.picme.agent.core.model.context.replyLanguageRuleSegment
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 远程 chat 推理引擎（决策3 / ADR-010 链路隔离 step 2）。
 *
 * 从 AgentOrchestrator/AgentConfigurator 抽出的 chat 远程 ReAct 链路：owns chat-agent 生命周期
 *（[getChatAgent] + chat system prompt + 缓存）与 chat 推理（[streamChat]/streamChatReAct/[processChatReAct]）。
 * 共享配置（userRemoteConfig / deviceId / memoryContextProvider / chatMemoryStore）经 [AgentConfigurator] 只读访问，
 * 与相机链路（CameraToolService / AgentOrchestrator.processCameraInput）严格隔离、无交叉。
 *
 * **Koog 驱动**：chat 链路由 [KoogChatAgent] 驱动（Koog AIAgent + ChatMemory
 * feature），取代旧 langchain4j RemoteReActAgent + StreamingSyncChatModel 路径。[processChatReAct] 直接 suspend
 * 调 [KoogChatAgent.runChat]（删 suspendCoroutine/CountDownLatch 桥）；多轮记忆由 Koog ChatMemory feature +
 * 组合根注入的 ChatMemoryStore（ADR-012 三不变式由 store 强制）承担；媒体处理留端侧、远程只发文本（ADR-008）。
 *
 * **接口注入化（Phase 4 KMP 抽取）**：工具集→ToolRegistry 的反射展开（`asToolsByClass`，
 * Koog JVM-only API）移到组合根（Android）完成，[chatToolRegistry] 与 prompt 清单段用的
 * [ToolDescriptor] 列表同源注入。
 */
class RemoteChatEngine internal constructor(
    private val configurator: AgentConfigurator,
    chatToolDescriptors: List<ToolDescriptor>,
    private val chatToolRegistry: ToolRegistry,
    private val chatPromptBuilder: (List<ToolDescriptor>) -> String = ::buildChatSystemPrompt,
) {

    private val tag = "RemoteChatEngine"

    companion object {
        /**
         * chat ReAct 专属 system prompt 组装（确定性）：强调用工具调度相册能力，含 run_gallery_script 用法。
         * 「可用工具」段由 [ToolInventory] 从 [ChatToolService] 的 @Tool 元数据（组合根反射展开为
         * [ToolDescriptor] 列表注入）确定性生成；行为规则段由 [ChatPromptRules] 按节拼装
         *（原一整块手写 raw string，已拆分为带稳定 id 的有序规则节，改动按节审查）。
         * public 供组合根一致性单测复用（无需实例化 engine）。
         */
        fun buildChatSystemPrompt(toolDescriptors: List<ToolDescriptor>): String =
            """
            你是 PoLang 相册 AI 助手，通过调用工具帮助用户管理、搜索、分析本地相册。
            """.trimIndent() +
                "\n" + ToolInventory.build(toolDescriptors) + "\n\n" +
                ChatPromptRules.render()

        /**
         * chat system prompt 的动态尾段：当前日期行 + 渲染环境段（[renderEnvironment] 非空时，
         * render_html 排版上下文）+ 性格段（按 persona + 回复语言选段）+ 语言规则段（按回复语言选段）。
         * 在 agent 构建期拼接（非 buildChatSystemPrompt 内），DEFAULT 不注入性格段——
         * 保证 `buildChatSystemPrompt` 输出与 golden 逐字节不变。
         * 语言规则段与性格无关、恒注入（含 DEFAULT）：显式对抗全中文 base prompt 与
         * 中文工具输出的引力，保证回复语言跟随界面语言。
         */
        fun buildPromptSuffix(
            persona: AssistantPersona,
            replyLanguage: ReplyLanguage,
            today: String,
            renderEnvironment: RenderEnvironment? = null
        ): String =
            "\n\n当前日期：$today。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。" +
                (renderEnvironment?.let { "\n\n${it.toPromptSegment()}" } ?: "") +
                (personaPromptSegment(persona, replyLanguage)?.let { "\n\n$it" } ?: "") +
                "\n\n${replyLanguageRuleSegment(replyLanguage)}"
    }

    // ── chat ReAct Agent（懒创建）────────────────────────────────────

    /** chat agent 缓存条目（四元组原子替换，避免 agent 与重建条件错配）。 */
    private class ChatAgentCache(
        val agent: KoogChatAgent,
        val config: RemoteModelConfig,
        val persona: AssistantPersona,
        val replyLanguage: ReplyLanguage
    )

    private var cachedChatAgent: ChatAgentCache? = null

    /** chat system prompt（由组合根注入的工具描述元数据经注入的 prompt 组装器确定性组装，agent 构建期拼接当前日期）。 */
    private val chatSystemPrompt = chatPromptBuilder(chatToolDescriptors)

    /**
     * 流式自由聊天（chat 远程 ReAct）。流式期间经 [onEvent] 实时上报：
     * 模型逐 token 增量以 [ChatStreamEvent.TextSnapshot]（本轮累计全文）下发，
     * 进入工具调用轮时下发 [ChatStreamEvent.ToolCallStarted]；多轮记忆由 DataStoreChatMemory 承担。
     */
    suspend fun streamChat(
        input: String,
        agentContext: AgentContext,
        onEvent: (ChatStreamEvent) -> Unit
    ): Result<StreamChatResult> {
        Logger.d(tag, "streamChat: input='$input'")
        // 意图路由（spec《意图路由契约与意图路由器》M2）：LLM 管意图、代码管策略。
        // 命中最热意图（看照片/细化结果）时直执确定性命令、跳过全量 agent loop；
        // 其余（含门控直通与一切降级）原路走 chat 远程 ReAct（ADR-005 协议分离），行为不变。
        routeAndMaybeExecute(input, agentContext, onEvent)?.let { return it }
        Logger.i(tag, "streamChat routing to Chat ReAct")
        return streamChatReAct(input, agentContext, onEvent)
    }

    /**
     * 意图路由入口：门控/pattern/LLM 闭集分类 → [ChatRoutingPolicy] 查表 → 直执或回落。
     *
     * 返回 null = 回落完整 agent loop；非 null = 已直执（DirectSearch/DirectRefine），
     * 直执复用 [ChatToolService.dispatchCommandWithTrace]（uiActions 发射 + observation +
     * 5s 超时与 LLM tool_calls 路径同源），结果包成 TextReply 命令回 chat。
     *
     * 路由器 executor 与 chat agent 同源（[getChatAgent] 缓存复用，含网关 header/协议分流）；
     * agent 构建失败（远程未配置等）时路由器内部降级为直通，不影响主链路。
     */
    private suspend fun routeAndMaybeExecute(
        input: String,
        agentContext: AgentContext,
        onEvent: (ChatStreamEvent) -> Unit
    ): Result<StreamChatResult>? {
        val persona = agentContext.persona
        val replyLanguage = agentContext.replyLanguage
        // 路由器实例无状态（审计口在 companion），按回合构造、provider 闭包捕获本轮配置
        val router = IntentRouter {
            getChatAgent(persona, replyLanguage)?.executorBundle
        }
        // 紧凑对话状态（spec §3.2 初值）：搜索基数存在性 + 上轮 artifact
        val hasSearchBase = agentContext.recentSearchResults.isNotEmpty()
        val state = CompactChatState(
            lastArtifact = if (hasSearchBase) UiArtifact.MEDIA_RESULTS_CARD else null,
            hasSearchBase = hasSearchBase,
        )
        val routing = router.route(
            query = input,
            state = state,
            today = today(),
            traceId = agentContext.traceId,
        )
        return when (val decision = ChatRoutingPolicy.decide(routing, state, input)) {
            is ChatRoutingPolicy.RouteDecision.FullAgentLoop -> {
                Logger.d(tag, "router fallback to agent loop: ${decision.reason}")
                null
            }
            is ChatRoutingPolicy.RouteDecision.DirectSearch -> {
                // 占位文案切换到「搜集中」语义（与 tool_calls 路径的 ToolCallStarted 一致）
                onEvent(ChatStreamEvent.ToolCallStarted)
                val observation = ChatToolService.getInstance().dispatchCommandWithTrace(
                    AgentCommand.SearchMedia(query = decision.query, intent = decision.intent),
                    agentContext.traceId,
                )
                Result.success(
                    StreamChatResult(
                        fullResponse = observation,
                        commands = listOf(AgentCommand.TextReply(message = observation)),
                    )
                )
            }
            is ChatRoutingPolicy.RouteDecision.DirectRefine -> {
                onEvent(ChatStreamEvent.ToolCallStarted)
                val observation = ChatToolService.getInstance().dispatchCommandWithTrace(
                    AgentCommand.RefineMediaSearch(constraint = decision.constraint, intent = decision.intent),
                    agentContext.traceId,
                )
                Result.success(
                    StreamChatResult(
                        fullResponse = observation,
                        commands = listOf(AgentCommand.TextReply(message = observation)),
                    )
                )
            }
        }
    }

    /** chat 远程 ReAct：调 [processChatReAct] 拿 summary，包成 TextReply 命令回 chat。 */
    private suspend fun streamChatReAct(
        input: String,
        agentContext: AgentContext,
        onEvent: (ChatStreamEvent) -> Unit
    ): Result<StreamChatResult> {
        val startTime = Clock.System.now().toEpochMilliseconds()
        return try {
            processChatReAct(
                input,
                agentContext.memorySessionId,
                traceId = agentContext.traceId,
                persona = agentContext.persona,
                replyLanguage = agentContext.replyLanguage,
                onEvent = onEvent
            ).fold(
                onSuccess = { (summary, metrics) ->
                    val latencyMs = Clock.System.now().toEpochMilliseconds() - startTime
                    val commands = if (summary.isNotBlank()) {
                        listOf(AgentCommand.TextReply(message = summary))
                    } else {
                        emptyList()
                    }
                    val base = StreamChatResult(
                        fullResponse = summary,
                        metrics = StreamMetrics(
                            latencyMs = latencyMs,
                            promptTokens = metrics?.promptTokens?.toLong(),
                            completionTokens = metrics?.completionTokens?.toLong()
                        )
                    )
                    Result.success(base.copy(commands = commands))
                },
                onFailure = { Result.failure(it) },
            )
        } catch (e: Exception) {
            Logger.e(tag, "streamChatReAct error", e)
            Result.failure(e)
        }
    }

    /**
     * chat 远程推理（Koog ReAct tool_calls 循环）。用 [getChatAgent]（ChatToolService，chat 场域能力工具）
     * 驱动 [KoogChatAgent.runChat] 执行多轮 tool 调用，完成后返回自然语言 summary。
     *
     * [onEvent] 非空时透传流式事件：模型逐 token 增量 → [ChatStreamEvent.TextSnapshot]，
     * 工具调用轮开始 → [ChatStreamEvent.ToolCallStarted]（飞书等不传 onEvent 的调用方行为不变）。
     *
     * Koog `agent.run()` 本身 suspend，故直接 `withTimeout { runChat(...) }`，删除旧 langchain4j 期的
     * suspendCoroutine/CountDownLatch 回调桥。取消经 withTimeout 的协程 cancel 级联（Koog 1.1.1 已正确响应取消）。
     */
    internal suspend fun processChatReAct(
        input: String,
        sessionId: String,
        timeoutMs: Long = 120_000L,
        traceId: String? = null,
        persona: AssistantPersona = AssistantPersona.DEFAULT,
        replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE,
        onEvent: ((ChatStreamEvent) -> Unit)? = null
    ): Result<Pair<String, AgentExecutionMetrics?>> = withContext(configurator.dispatcherProvider.orchestratorDispatcher) {
        Logger.d(tag, "processChatReAct: input='$input', sessionId='$sessionId', timeout=${timeoutMs}ms")

        val agent = getChatAgent(persona, replyLanguage) ?: return@withContext Result.failure(
            IllegalStateException("Chat ReAct Agent 初始化失败")
        )

        if (agent.isRunning()) {
            return@withContext Result.failure(IllegalStateException("Agent 正在执行其他任务"))
        }

        agent.setSessionId(sessionId)

        return@withContext try {
            val (summary, metrics) = withTimeout(timeoutMs) {
                agent.runChat(
                    input = input,
                    traceId = traceId,
                    onPartialText = { snapshot ->
                        // 本轮累计全文快照 → UI 直接替换气泡内容
                        onEvent?.invoke(ChatStreamEvent.TextSnapshot(snapshot))
                    },
                    onToolCall = { toolName, args ->
                        Logger.d(tag, "Chat ReAct toolCall: $toolName(${args.take(100)})")
                        onEvent?.invoke(ChatStreamEvent.ToolCallStarted)
                    },
                )
            }
            Result.success(summary to metrics)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Logger.e(tag, "processChatReAct timeout after ${timeoutMs}ms")
            Result.failure(RuntimeException("处理超时（${timeoutMs / 1000}秒），请稍后重试"))
        } catch (e: Exception) {
            Logger.e(tag, "processChatReAct error", e)
            Result.failure(e)
        }
    }

    /**
     * 获取或创建 chat Koog Agent（ChatToolService，chat 场域能力工具，不含 UI/相机）。
     * 配置或 persona/回复语言变更时自动重建。共享配置经 [configurator] 只读访问。
     *
     * KoogChatAgent 无 langchain4j 期的 initialize/shutdown 生命周期（AIAgent 在 [KoogChatAgent.agent]
     * 内按记忆快照新鲜度懒建/重建），故重建仅替换缓存条目；executor/memoryStore/historyProvider 复用，
     * 历史经 DataStore（chat_memory）跨重建留存。
     */
    private fun getChatAgent(persona: AssistantPersona, replyLanguage: ReplyLanguage): KoogChatAgent? {
        val cache = cachedChatAgent
        val currentConfig = configurator.getUserRemoteConfig() ?: RemoteModelConfig.PICME_SERVER_DEFAULT
        if (cache != null) {
            val configChanged = cache.config.modelId != currentConfig.modelId
                || cache.config.baseUrl != currentConfig.baseUrl
                || cache.config.apiKey != currentConfig.apiKey
                || cache.config.gatewayToken != currentConfig.gatewayToken
                || cache.config.protocol != currentConfig.protocol
                || cache.config.providerId != currentConfig.providerId
                || cache.persona != persona
                || cache.replyLanguage != replyLanguage
            if (configChanged) {
                Logger.i(tag, "Remote config or persona/language changed (model=${currentConfig.modelId}, persona=$persona, language=$replyLanguage), rebuilding Chat Agent")
            } else {
                return cache.agent
            }
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
                .systemPrompt(
                    chatSystemPrompt + buildPromptSuffix(
                        persona,
                        replyLanguage,
                        today(),
                        // render_html 排版上下文；未注入（iOS 跟随期）时无该段。
                        // 环境变化（如旋转）不触发重建——agent 缓存键不含此项，下次重建自然刷新。
                        renderEnvironment = configurator.getRenderEnvironmentProvider()?.invoke()
                    )
                )
                .apply { if (memProvider != null) memoryContextProvider(memProvider) }
                .build()
        } catch (e: Exception) {
            Logger.w(tag, "Failed to build ChatAgent config", e)
            return null
        }
        val chatToolService = ChatToolService.getInstance()
        val agent = KoogChatAgent(
            config = cfg,
            // reflect.ToolSet / asToolsByClass 是 Koog 1.1.1 JVM-only API；工具集→registry 的
            // 反射展开在组合根（Android）完成，与 prompt 清单段同源注入（逐字节等价）。
            toolRegistry = chatToolRegistry,
            memoryStore = configurator.chatMemoryStore,
        )
        // traceId 注入（原 KoogChatAgent init 内类型判断随迁出）：tool 执行带当轮 traceId。
        chatToolService.traceIdHolder = agent.traceIdHolder
        cachedChatAgent = ChatAgentCache(
            agent = agent,
            config = currentConfig,
            persona = persona,
            replyLanguage = replyLanguage
        )
        Logger.i(tag, "Chat Koog Agent created: model=${cfg.modelName}, baseUrl=${currentConfig.baseUrl.take(40)}")
        return agent
    }
}

/** 当前日期（ISO yyyy-MM-dd，与旧 `java.time.LocalDate.now()` 输出格式一致）。 */
private fun today(): String =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
