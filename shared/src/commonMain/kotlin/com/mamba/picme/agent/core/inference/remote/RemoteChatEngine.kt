package com.mamba.picme.agent.core.inference.remote

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
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
import com.mamba.picme.agent.core.model.context.AgentAction
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val chatTools: List<ToolBase<*, *>>? = null,
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

        /**
         * 子集 prompt 追加的工具范围约束（M3 review 修订）：规则段 [ChatPromptRules] 恒全量渲染
         *（分节与工具族对应但暂不按子集过滤，保留前缀结构），其中引用了可能不在子集里的工具——
         * 显式声明本轮可用范围，防模型幻觉调用未提供 schema 的工具（报错浪费一轮）。
         */
        private const val SUBSET_TOOL_RANGE_NOTE =
            "\n\n【本轮工具范围】本轮仅提供上方清单所列工具；规则中提及但清单未列的工具本轮不可用，禁止调用。"

        /**
         * 意图工具子集的纯计算部分（M3，可单测）：契约子集 ∩ 平台可用工具名。
         *
         * 返回 null = 保守回退全量工具面，两种触发：
         * - 交集为空（契约全数腐烂的极端兜底，宁可全量不可无工具）；
         * - 不可用项超过半数（missing.size * 2 > toolNames.size，review 修订 2026-10-17）——
         *   平台工具面远窄于契约（如 iOS 手工清单无 finish/delay）时子集严重缩水，
         *   裁出的残缺的子集比全量更易困住模型，不如全量。
         *
         * 返回非 null 时 [ToolSubsetResolution.missing] 记录被丢弃的契约引用（正常应为空——
         * Android 由 ChatIntentContractToolNamesTest 编译期守卫，运行期出现即契约漂移信号）。
         */
        internal fun resolveToolSubset(
            available: Set<String>,
            toolNames: Set<String>,
        ): ToolSubsetResolution? {
            val hits = toolNames.intersect(available)
            if (hits.isEmpty()) return null
            val missing = toolNames - available
            if (missing.size * 2 > toolNames.size) return null
            return ToolSubsetResolution(
                names = hits,
                key = hits.sorted().joinToString(","),
                missing = missing,
            )
        }

        /** [resolveToolSubset] 命中结果：[key] 为排序后工具名串（子集身份，prompt 缓存与 AIAgent 重建键共用）。 */
        internal data class ToolSubsetResolution(
            val names: Set<String>,
            val key: String,
            val missing: Set<String>,
        )
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
     * 意图子集 base prompt 缓存（key = 排序后工具名串）：同子集逐字节复用，保 prompt 前缀稳定。
     * orchestratorDispatcher 是线程池（Android 固定 2 线程 / iOS Dispatchers.Default），重叠回合
     * 可并发进入（runChat 的 running 互斥是 check-then-act），普通 HashMap 并发写有结构损坏
     * 风险——get/put 经 [toolSetPromptCacheLock] 串行化（review 修订 2026-10-17）。
     */
    private val toolSetPromptCache = mutableMapOf<String, String>()
    private val toolSetPromptCacheLock = Mutex()

    /**
     * 路由闸门结果（M3 意图级工具裁剪）：直执已处理 / 回落 agent loop（携带契约工具子集，
     * null = 完整工具面——降级路径、直执失败回退、secondary 双产出物等判定不可靠场景保守全量）。
     */
    private sealed interface RoutingGate {
        class Handled(val result: Result<StreamChatResult>) : RoutingGate
        class FallThrough(val toolNames: Set<String>?) : RoutingGate
    }

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
        // 其余回落 chat 远程 ReAct（ADR-005 协议分离）——M3 起回落携带契约工具子集，
        // 引擎按子集裁剪 tool schema 与 prompt 清单段（成本治理：不再每轮全量 43 个 schema）。
        when (val gate = routeAndMaybeExecute(input, agentContext, onEvent)) {
            is RoutingGate.Handled -> return gate.result
            is RoutingGate.FallThrough -> {
                Logger.i(tag, "streamChat routing to Chat ReAct (toolSet=${gate.toolNames?.size ?: "full"})")
                return streamChatReAct(input, agentContext, onEvent, gate.toolNames)
            }
        }
    }

    /**
     * 意图路由入口：门控/pattern/LLM 闭集分类 → [ChatRoutingPolicy] 查表 → 直执或回落。
     *
     * 返回 [RoutingGate.FallThrough] = 回落完整 agent loop（携带决策给出的工具子集，null = 全量）；
     * [RoutingGate.Handled] = 已直执（DirectSearch/DirectRefine）。
     * 直执语义（review 决议）：
     * - 复用 [ChatToolService.dispatchCommandDetailed]（uiActions 发射 + observation + 5s 超时
     *   与 LLM tool_calls 路径同源）；dispatch 失败/业务错误（非 MediaResults）→ FallThrough(null)
     *   回落完整 agent loop 且**保守用全量工具面**（偏离剧本的恢复场景给模型完整自由度）。
     * - 成功时不产 LLM 总结：结果以 [StreamChatResult.directReply] 结构化回执返回，
     *   用户气泡由平台层本地化渲染（observation 硬编码中文模板只进记忆/审计，I18N 红线）；
     *   该轮 user+observation 补写 Koog 会话记忆（直执跳过 agent loop，否则多轮指代断裂）。
     * - 整体在 orchestratorDispatcher 执行（与 processChatReAct 同一线程上下文，
     *   避免 lastResultAssets 等非线程安全状态的写入线程扩散）。
     *
     * 路由器 executor 与 chat agent 同源（[getChatAgent] 缓存复用，含网关 header/协议分流）；
     * agent 构建失败（远程未配置等）时路由器内部降级为直通，不影响主链路。
     */
    private suspend fun routeAndMaybeExecute(
        input: String,
        agentContext: AgentContext,
        onEvent: (ChatStreamEvent) -> Unit
    ): RoutingGate = withContext(configurator.dispatcherProvider.orchestratorDispatcher) {
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
        when (val decision = ChatRoutingPolicy.decide(routing, state, input)) {
            is ChatRoutingPolicy.RouteDecision.FullAgentLoop -> {
                Logger.d(tag, "router fallback to agent loop: ${decision.reason} (toolSet=${decision.toolNames?.size ?: "full"})")
                RoutingGate.FallThrough(decision.toolNames)
            }
            is ChatRoutingPolicy.RouteDecision.DirectSearch -> executeDirect(
                command = AgentCommand.SearchMedia(query = decision.query, intent = decision.intent),
                kind = DirectRouteReply.Kind.SEARCH,
                input = input,
                agentContext = agentContext,
                onEvent = onEvent,
            ).toGate()
            is ChatRoutingPolicy.RouteDecision.DirectRefine -> executeDirect(
                command = AgentCommand.RefineMediaSearch(constraint = decision.constraint, intent = decision.intent),
                kind = DirectRouteReply.Kind.REFINE,
                input = input,
                agentContext = agentContext,
                onEvent = onEvent,
            ).toGate()
        }
    }

    /** 直执结果 → 闸门：成功 Handled；失败 FallThrough(null)——回退场景保守全量工具面。 */
    private fun Result<StreamChatResult>?.toGate(): RoutingGate =
        this?.let { RoutingGate.Handled(it) } ?: RoutingGate.FallThrough(null)

    /**
     * 直执一条搜索/细化命令：成功 → [DirectRouteReply] 结构化回执 + 补写会话记忆；
     * 失败（dispatch 超时/业务错误/非 MediaResults action）→ null（调用方回落 agent loop）。
     */
    private suspend fun executeDirect(
        command: AgentCommand,
        kind: DirectRouteReply.Kind,
        input: String,
        agentContext: AgentContext,
        onEvent: (ChatStreamEvent) -> Unit,
    ): Result<StreamChatResult>? {
        // 占位文案切换到「搜集中」语义（与 tool_calls 路径的 ToolCallStarted 一致）；
        // M2 起携带工具名（直执命令 → 对应 @Tool customName）供 turn 装配器登记。
        val toolName = when (command) {
            is AgentCommand.RefineMediaSearch -> "refine_media_search"
            else -> "search_media"
        }
        onEvent(ChatStreamEvent.ToolCallStarted(toolName = toolName))
        val detailed = ChatToolService.getInstance().dispatchCommandDetailed(command, agentContext.traceId)
        val results = detailed.action as? AgentAction.MediaResults
        if (results == null) {
            Logger.w(tag, "direct dispatch failed (${detailed.observation.take(80)}), fallback to agent loop")
            return null
        }
        // 补写 Koog 会话记忆（直执跳过 agent loop，ChatMemory 不会记录本轮）：
        // user 原文 + 模型侧 observation，使下一轮追问（"他多大了"类 OPEN_QA）能看到上下文。
        runCatching {
            val store = configurator.chatMemoryStore
            val sessionId = agentContext.memorySessionId
            store.save(
                sessionId,
                store.load(sessionId) + listOf(
                    Message.User(input, RequestMetaInfo.Empty),
                    Message.Assistant(detailed.observation, ResponseMetaInfo.Empty),
                )
            )
        }.onFailure { Logger.w(tag, "direct turn memory write failed: ${it.message}") }
        return Result.success(
            StreamChatResult(
                fullResponse = "",
                directReply = DirectRouteReply(
                    kind = kind,
                    query = results.query,
                    totalCount = results.totalCount,
                ),
            )
        )
    }

    /** chat 远程 ReAct：调 [processChatReAct] 拿 summary，包成 TextReply 命令回 chat。 */
    private suspend fun streamChatReAct(
        input: String,
        agentContext: AgentContext,
        onEvent: (ChatStreamEvent) -> Unit,
        toolNames: Set<String>? = null,
    ): Result<StreamChatResult> {
        val startTime = Clock.System.now().toEpochMilliseconds()
        return try {
            processChatReAct(
                input,
                agentContext.memorySessionId,
                traceId = agentContext.traceId,
                persona = agentContext.persona,
                replyLanguage = agentContext.replyLanguage,
                onEvent = onEvent,
                toolNames = toolNames,
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
        } catch (e: CancellationException) {
            // 用户主动停止：穿透给调用方静默收场（同 processChatReAct 的取消语义）
            throw e
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
        onEvent: ((ChatStreamEvent) -> Unit)? = null,
        toolNames: Set<String>? = null,
    ): Result<Pair<String, AgentExecutionMetrics?>> = withContext(configurator.dispatcherProvider.orchestratorDispatcher) {
        Logger.d(tag, "processChatReAct: input='$input', sessionId='$sessionId', timeout=${timeoutMs}ms")

        val agent = getChatAgent(persona, replyLanguage) ?: return@withContext Result.failure(
            IllegalStateException("Chat ReAct Agent 初始化失败")
        )

        if (agent.isRunning()) {
            return@withContext Result.failure(IllegalStateException("Agent 正在执行其他任务"))
        }

        agent.setSessionId(sessionId)
        // M3 意图级工具裁剪：路由契约子集 → 覆写 registry + prompt 清单段（同源过滤，零漂移）；
        // 未注入 chatTools（裁剪不可用的平台）或判定不可靠（null）时恒为全量默认。
        val toolSetOverride = resolveToolSetOverride(toolNames, persona, replyLanguage)

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
                        onEvent?.invoke(ChatStreamEvent.ToolCallStarted(toolName = toolName, args = args))
                    },
                    onRoundStarted = {
                        // M4：显式轮边界（spec §10 收口），turn 装配器替代快照差分猜测
                        onEvent?.invoke(ChatStreamEvent.RoundStarted)
                    },
                    toolSetOverride = toolSetOverride,
                )
            }
            Result.success(summary to metrics)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Logger.e(tag, "processChatReAct timeout after ${timeoutMs}ms")
            Result.failure(RuntimeException("处理超时（${timeoutMs / 1000}秒），请稍后重试"))
        } catch (e: CancellationException) {
            // 用户主动停止（外层协程取消）：取消必须穿透（Koog/Ktor 级联断流），不得折叠成错误结果
            throw e
        } catch (e: Exception) {
            Logger.e(tag, "processChatReAct error", e)
            Result.failure(e)
        }
    }

    /**
     * 路由契约工具子集 → 本轮覆写（M3，2026-10-17）。
     *
     * 同源不变式不变（spec §4 设计不变式 8）：子集 registry 与 prompt 清单段都自同一份
     * [chatTools] 过滤派生，禁手写漂移；子集判定（交集 / 空交集与过半缺失回退）收口在纯函数
     * [resolveToolSubset]（commonTest 直接覆盖）。
     *
     * 返回 null 的场景（一律落全量默认）：未请求裁剪（[toolNames] = null）/ 平台未注入
     * [chatTools] / [resolveToolSubset] 判定保守全量（空交集或缺失过半，如 iOS 窄工具面）。
     *
     * 新鲜度语义：子集 prompt 的动态尾段冻结在「该 toolSetKey 对应 AIAgent 上次构建时」
     *（全量路径冻结在 KoogChatAgent 构建时）——同 key 内逐字节稳定（前缀缓存目标），
     * 跨午夜/语言旋转后不同 key 的日期行可不同，属可接受微差。
     */
    private suspend fun resolveToolSetOverride(
        toolNames: Set<String>?,
        persona: AssistantPersona,
        replyLanguage: ReplyLanguage,
    ): KoogChatAgent.ChatToolSetOverride? {
        if (toolNames == null) return null
        val all = chatTools ?: return null
        val available = all.mapTo(mutableSetOf()) { it.descriptor.name }
        val resolution = resolveToolSubset(available, toolNames)
        if (resolution == null) {
            Logger.w(
                tag,
                "tool subset fallback to full registry (requested=$toolNames, unavailable=${toolNames - available})",
            )
            return null
        }
        if (resolution.missing.isNotEmpty()) {
            // 契约漂移信号（Android 有编译期守卫，正常为空；iOS 窄工具面由过半阈值拦截，
            // 走到这的零星缺失按 key 低频出现，debug 级即可）
            Logger.d(tag, "tool subset dropped unavailable tools: ${resolution.missing}")
        }
        val filtered = all.filter { it.descriptor.name in resolution.names }
        val basePrompt = toolSetPromptCacheLock.withLock {
            toolSetPromptCache.getOrPut(resolution.key) {
                chatPromptBuilder(filtered.map { it.descriptor }) + SUBSET_TOOL_RANGE_NOTE
            }
        }
        return KoogChatAgent.ChatToolSetOverride(
            key = resolution.key,
            registry = ToolRegistry { tools(filtered) },
            systemPrompt = basePrompt + buildPromptSuffix(
                persona,
                replyLanguage,
                today(),
                renderEnvironment = configurator.getRenderEnvironmentProvider()?.invoke(),
            ),
        )
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
