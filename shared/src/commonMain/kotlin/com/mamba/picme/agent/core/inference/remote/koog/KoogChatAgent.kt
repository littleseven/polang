package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecord
import com.mamba.picme.agent.core.inference.remote.log.TraceIdAware
import com.mamba.picme.agent.core.inference.remote.log.TraceIdHolder
import com.mamba.picme.agent.core.inference.remote.react.AgentExecutionMetrics
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import com.mamba.picme.agent.core.model.context.openUiReplyLanguageRuleSegment
import com.mamba.picme.agent.core.platform.currentPlatform
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import com.mamba.picme.domain.chat.openui.OpenUiProgramGuard
import com.mamba.picme.domain.chat.openui.OpenUiTurnNativeContract
import kotlin.concurrent.Volatile
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
 *   语义=累计全文非 delta），onLLMStreamingStarting 重置（新一轮从空累计）并发射显式轮边界
 *   [com.mamba.picme.agent.core.inference.remote.ChatStreamEvent.RoundStarted]（M4，spec §10）；onToolCallStarted 发 ToolCallStarted；
 *   ReasoningDelta 同构累积为 [com.mamba.picme.agent.core.inference.remote.ChatStreamEvent.ReasoningSnapshot]
 *   （值域终态 §6.1，生产开关默认关——thinking=disabled）。
 * - **指标**：[TurnUsageAccumulator] 统一流式/非流式两路 usage（流式轮只随
 *   `StreamFrame.End` 抵达、非流式轮经 onLLMCallCompleted——Koog 1.3.0 事件语义互斥，
 *   server 0.9.17 实证），每回合 reset；onLLMCallCompleted 另录制 [LlmCallRecord]
 *   （DEBUG 全文 / release 纯指标，双模式隐私，录制直读 metaInfo 不经 accumulator）。
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
class KoogChatAgent(
    private val config: RemoteReActAgentConfig,
    private val toolRegistry: ToolRegistry,
    memoryStore: ChatMemoryStore,
    summaryGenerator: SummaryGenerator? = null,
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
    private val memoryStoreRef = memoryStore

    /**
     * M2 滚动压缩执行器（US-2.1~2.5）。runChat 前 maybeCompact 判定并压缩；
     * 摘要注入 system prompt（[composeSystemPrompt]）。generator 默认经 executorBundle 单发
     * （[SummaryGeneratorViaExecutor]），可注入假实现测试。压缩失败静默跳过（决策 9）。
     */
    private val compactor = SessionCompactor(
        store = memoryStore,
        generator = summaryGenerator ?: SummaryGeneratorViaExecutor(executorBundle),
    )

    /**
     * 当轮 traceId 持有器（runChat 开头写入，onLLMCallCompleted 录制时读取）。
     * 组合根在构建本 agent 后把它注入实现 [TraceIdAware] 的工具集（ChatToolService），
     * 使 Koog 链路下 tool 执行也带 traceId（原 init 内 `as? ChatToolService` 类型判断
     * 随 KMP 抽取移除——本类在 commonMain，reflect.ToolSet 是 Koog 1.1.1 JVM-only API）。
     */
    val traceIdHolder = TraceIdHolder()

    // 单 run 累计状态（runs 串行：processChatReAct 的 isRunning 互斥）。StringBuilder 与
    // 原子计数线程安全，因 EventHandler 可能在 Koog 内部调度线程触发（非调用 runChat 的线程）。
    private val snapshotBuffer = StringBuilder()

    /**
     * 思考链累计缓冲（值域终态 spec §6.1）：Koog `StreamFrame.ReasoningDelta` 逐帧累积为
     * 本轮累计快照（与 [snapshotBuffer] 同构语义）。**生产开关默认关**——模型配置层
     * thinking=disabled（RemoteModelFactory）且 Koog Thinking capability 未声明，缓冲
     * 恒空零开销；上游启用 extended thinking 后自动透出。
     */
    private val reasoningBuffer = StringBuilder()

    /**
     * 回合 token 用量累加器（U2-2）：流式轮 usage 只随 `StreamFrame.End` 抵达、
     * 非流式轮经 onLLMCallCompleted（Koog 1.3.0 两路互斥，server 0.9.17 实证），
     * 本累加器统一两路计数，终值在 runChat 收尾读出（替代原两 AtomicInt 直读直加）。
     * 每回合与 [toolLoopGuard] 同点位 reset。
     */
    private val usageAccumulator = TurnUsageAccumulator()

    // per-run 回调持有（EventHandler lambda 读这两个字段，而非捕获 runChat 的入参，避免重建/跨 run 失效）
    @Volatile private var currentPartialText: ((snapshot: String) -> Unit)? = null
    @Volatile private var currentToolCall: ((toolName: String, args: String) -> Unit)? = null

    /** M4：显式轮边界回调（[ChatStreamEvent.RoundStarted] 的发射源）。 */
    @Volatile private var currentRoundStarted: (() -> Unit)? = null

    /** 思考链累计快照回调（[ChatStreamEvent.ReasoningSnapshot] 的发射源；生产开关默认关）。 */
    @Volatile private var currentReasoning: ((snapshot: String) -> Unit)? = null

    @Volatile private var running = false
    private var currentSessionId: String = "default"

    /**
     * 工具循环熔断（2026-11-08，真机事故：模型在空/错工具结果上打转 15 轮撞 30 步硬顶）。
     * per-agent 单实例：runs 串行（isRunning 互斥），每回合 [ToolLoopGuard.reset]；
     * 熔断命中时策略携哨兵落终点，runChat 识别后抛 [AgentLoopStuckException]。
     */
    private val toolLoopGuard = ToolLoopGuard()

    /**
     * OpenUI 终结程序守卫（U2-2 turn-native）：flag on 时经 [asCorrector] 挂进策略
     * 纠正节点（非法终结程序带错误清单回环重发，上限 [OpenUiProgramGuard.MAX_CORRECTIONS]）。
     * per-agent 单实例跨回合复用，runChat 开头与 [toolLoopGuard] 同点位 reset——
     * 纠正计数不得跨回合累积（server 每轮新实例天然归零，本地缓存 agent 靠显式 reset）。
     */
    private val openUiGuard = OpenUiProgramGuard()

    /**
     * 策略配置（U2-2）：flag 驱动选择——见 [chatReActStrategyConfig]（纯函数，
     * commonTest 直测）。config 不可变故配置恒稳定，构建期一次求值。
     */
    private val strategyConfig = chatReActStrategyConfig(config.openUiTurnNative, openUiGuard, toolLoopGuard)

    /**
     * OpenUI 回合原生契约段（U2-2，仅 flag on；空串 = flag off）：契约 prompt
     * （端侧身份行、无 server_time 工具句）+ 语言规则段，拼在技能清单段之后（prompt 最末）。
     * 段文本比对入 AIAgent 重建键（与 skillSection 同槽）；config 不可变故段恒稳定，
     * 入键为后续动态化（如逐轮语言）留位。
     */
    private val openUiContractSection: String =
        if (config.openUiTurnNative) {
            OpenUiTurnNativeContract.buildSystemPrompt(
                identityLine = OPENUI_IDENTITY_LINE,
                toolNote = "",
                toolModeOverride = OPENUI_TOOL_MODE_OVERRIDE,
            ) + "\n\n" + openUiReplyLanguageRuleSegment(config.replyLanguage)
        } else {
            ""
        }

    // 记忆快照 + 摘要版本 + 工具子集 + 技能清单段 + OpenUI 契约段驱动的懒重建（五者任一变更才
    // 重建 AIAgent；executor/store/provider 复用）。
    // 摘要每轮新鲜渲染（US-2.4），但其变化经 version 反映——压缩后 version 变 → 重建 → 新摘要烘焙。
    // 技能清单段（2026-11-08）：内容随 SkillRepository 同步变化，段文本比对入键——
    // 技能新增/删除/描述变更即重建烘焙新清单，正文更新（version 变、name/description 不变）
    // 不触发重建（清单段无变化，load_skill 拉的是最新缓存正文，无需重建）。
    // OpenUI 契约段（U2-2）：与技能清单段同槽文本比对入键；flag off 时空串入键零扰动。
    private var builtSnapshot: String? = null
    private var builtSummaryVersion: Int = -1
    private var builtToolSetKey: String? = null
    private var builtSkillSection: String? = null
    private var builtOpenUiSection: String = ""
    private var builtAgent: AIAgent<String, String>? = null

    fun setSessionId(sessionId: String) {
        currentSessionId = sessionId
    }

    fun isRunning(): Boolean = running

    /**
     * 意图级工具子集覆写（M3 分支化，2026-10-17）：引擎层按路由契约把 tool schema 裁到
     * 意图白名单时，单次 runChat 携带此覆写——[registry] 与 [systemPrompt]（子集清单段
     * 同源生成 + 动态尾段）替换构造期的全量默认值；[key] 进 AIAgent 重建键，子集切换
     * 与记忆快照/摘要变更同级触发重建（executor/memoryStore/historyProvider 仍单份复用，
     * 不为每个子集各建 executor）。
     */
    class ChatToolSetOverride(
        val key: String,
        val registry: ToolRegistry,
        val systemPrompt: String,
    )

    /**
     * 执行一次 chat ReAct（suspend）。返回 (summary, metrics)；异常向上抛（由 processChatReAct 的
     * withTimeout/catch 处理；取消经 withTimeout 级联）。
     * [toolSetOverride] 非空时本轮按意图子集裁剪工具面与 prompt 清单段（见 [ChatToolSetOverride]）。
     */
    suspend fun runChat(
        input: String,
        traceId: String?,
        onPartialText: (snapshot: String) -> Unit,
        onToolCall: (toolName: String, args: String) -> Unit,
        onRoundStarted: () -> Unit = {},
        toolSetOverride: ChatToolSetOverride? = null,
        onReasoning: (snapshot: String) -> Unit = {},
    ): Pair<String, AgentExecutionMetrics> {
        running = true
        traceIdHolder.value = traceId
        snapshotBuffer.setLength(0)
        reasoningBuffer.setLength(0)
        currentPartialText = onPartialText
        currentToolCall = onToolCall
        currentRoundStarted = onRoundStarted
        currentReasoning = onReasoning
        val started = Clock.System.now().toEpochMilliseconds()
        return try {
            // M2：跑 agent 前判定并执行滚动压缩（超预算才触发；失败静默跳过，决策 9）。
            // 与 agent run 串行（runs 互斥），压缩完成后续正常推理。
            runCatching { compactor.maybeCompact(currentSessionId) }
                .onFailure { Logger.w(tag, "compaction pre-pass failed: ${it.message}") }
            // M2：加载会话摘要（US-2.4），作为 system 段注入 + 版本纳入 agent 重建键。
            val summary = runCatching { memoryStoreRef.loadSummary(currentSessionId) }.getOrNull()
            val summaryText = summary?.slots?.render()?.ifBlank { null }
            toolLoopGuard.reset()
            // per-agent 跨回合复用的回合级状态同点位复位（U2-2）：程序守卫纠正计数、
            // token 用量累加器——不得跨回合残留。
            openUiGuard.reset()
            usageAccumulator.reset()
            val summaryResult = try {
                agent(summaryText, summary?.version ?: 0, toolSetOverride).run(input, currentSessionId)
            } catch (e: AIAgentMaxNumberOfIterationsReachedException) {
                // Koog 步数硬顶（30 步）统一折叠为熔断语义：UI 按类型映射本地化文案，
                // 不再把 Koog 英文开发者文案（"consider increasing maxAgentIterations"）原样上屏。
                throw AgentLoopStuckException("max iterations reached: ${e.message}")
            }
            if (summaryResult == ToolLoopGuard.TRIPPED_SENTINEL) {
                throw AgentLoopStuckException("tool loop guard tripped (session=$currentSessionId)")
            }
            val latencyMs = Clock.System.now().toEpochMilliseconds() - started
            summaryResult to AgentExecutionMetrics(
                latencyMs = latencyMs,
                promptTokens = usageAccumulator.promptTokens.takeIf { it > 0 },
                completionTokens = usageAccumulator.completionTokens.takeIf { it > 0 },
                modelName = config.modelName.ifBlank { null },
            )
        } finally {
            running = false
            traceIdHolder.value = null
            currentPartialText = null
            currentToolCall = null
            currentRoundStarted = null
            currentReasoning = null
        }
    }

    /**
     * 取或按「记忆快照 + 摘要版本 + 工具子集 + 技能清单段 + OpenUI 契约段」新鲜度重建
     * AIAgent（M2：摘要变更同样触发重建，使新压缩的摘要即时烘焙进 system prompt；
     * M3：意图子集切换同级触发，覆写 registry/prompt 即时生效；U2-2：契约段文本
     * 变更同级触发——config 不可变故当前恒稳定，为动态化留位）。
     */
    private fun agent(summaryText: String?, summaryVersion: Int, toolSet: ChatToolSetOverride?): AIAgent<String, String> {
        val snapshot = config.memoryContextProvider?.snapshot()?.trim()?.ifEmpty { null }
        val skillSection = config.skillPromptProvider?.invoke()?.trim()?.ifEmpty { null }
        val cached = builtAgent
        if (cached != null && snapshot == builtSnapshot && summaryVersion == builtSummaryVersion &&
            toolSet?.key == builtToolSetKey && skillSection == builtSkillSection &&
            openUiContractSection == builtOpenUiSection
        ) {
            return cached
        }
        val agent = buildAgent(
            systemPrompt = composeSystemPrompt(snapshot, summaryText, toolSet, skillSection),
            registry = toolSet?.registry ?: toolRegistry,
        )
        builtAgent = agent
        builtSnapshot = snapshot
        builtSummaryVersion = summaryVersion
        builtToolSetKey = toolSet?.key
        builtSkillSection = skillSection
        builtOpenUiSection = openUiContractSection
        Logger.i(
            tag,
            "Built Koog AIAgent: model=${config.modelName}, snapshotLen=${snapshot?.length ?: 0}, " +
                "summaryV=$summaryVersion, toolSet=${toolSet?.key ?: "full"}, skills=${skillSection != null}",
        )
        return agent
    }

    private fun buildAgent(systemPrompt: String, registry: ToolRegistry): AIAgent<String, String> =
        // 自定义策略：修复 Koog 1.1.1 内建 singleRunStrategy 丢「文本+tool_calls 同帧」
        // 工具调用的缺陷（chat 多轮工具任务同样受影响），详见 poLangSingleRunStrategy KDoc。
        //（2026-09-25 核实：1.3.0 仍未修，本策略继续必要。）
        // U2-2：策略配置经 [strategyConfig] flag 驱动——off = 现行内建节点路径
        // （streaming=false 无纠正），on = OpenUI turn-native（流式 + 终结程序守卫纠正）。
        AIAgent.builder()
            // 直接传策略实例（命名 lambda 重载 graphStrategy(name){...} 是 Koog 1.1.1 JVM-only
            // 便捷 API；策略内部已命名 "polang_single_run"，语义等价）。挂工具循环熔断边。
            .graphStrategy(poLangSingleRunStrategy(strategyConfig))
            .promptExecutor(executorBundle.executor)
            .llmModel(executorBundle.model)
            .toolRegistry(registry)
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
                    reasoningBuffer.setLength(0)
                    // M4：显式轮边界信号（spec §10 收口），turn 装配器据此闭合上一轮文本块
                    currentRoundStarted?.invoke()
                }
                events.onLLMStreamingFrameReceived { ctx ->
                    val frame = ctx.streamFrame
                    when (frame) {
                        is StreamFrame.TextDelta -> {
                            snapshotBuffer.append(frame.text)
                            currentPartialText?.invoke(snapshotBuffer.toString())
                        }
                        // reasoning 生产者接线（值域终态 spec §6.1；探针实证 2026-11-08：Koog
                        // 1.3.0 StreamFrame 透出 ReasoningDelta/ReasoningComplete）。逐帧累积
                        // 为本轮累计快照（与 TextDelta 同构）；ReasoningComplete 不单独消费——
                        // 块闭合由轮边界信号统一承担（与 TextComplete 同口径）。生产开关默认关
                        //（thinking=disabled + Thinking capability 未声明），帧不产出零开销。
                        is StreamFrame.ReasoningDelta -> {
                            reasoningBuffer.append(frame.text)
                            currentReasoning?.invoke(reasoningBuffer.toString())
                        }
                        // 回合用量（U2-2 流式 metrics 修复）：Koog 1.3.0 流式轮只发
                        // onLLMStreaming* 事件、不触发 onLLMCallCompleted（后者仅非流式
                        // execute 路径发射，server 0.9.17 实证），流式 usage 只能从 End 帧
                        // metaInfo 拿（OpenAI 客户端自上游 usage chunk 构建）。
                        // Task 13：同点位补录 llm_call_log（流式回合调用流水审计，
                        // 口径与非流式录制对齐，见 recordStreamingLlmCall）。
                        is StreamFrame.End -> {
                            usageAccumulator.onStreamingEnd(
                                frame.metaInfo?.inputTokensCount,
                                frame.metaInfo?.outputTokensCount,
                            )
                            recordStreamingLlmCall(
                                model = ctx.model,
                                meta = frame.metaInfo,
                                roundText = snapshotBuffer.toString(),
                                traceId = traceIdHolder.value,
                            )
                        }
                        else -> Unit
                    }
                }
                events.onToolCallStarting { ctx ->
                    Logger.d(tag, "tool call: ${ctx.toolName}(${ctx.toolArgs.toString().take(100)})")
                    currentToolCall?.invoke(ctx.toolName, ctx.toolArgs.toString())
                }
                events.onLLMCallCompleted { ctx ->
                    val meta = ctx.response?.metaInfo
                    // 非流式轮 usage 入回合累加器（与流式 End 帧两路互斥，见 usageAccumulator）。
                    // LlmCallRecord 录制直读 metaInfo 不经累加器（录制语义 = 单次调用快照）。
                    usageAccumulator.onCallCompleted(meta?.inputTokensCount, meta?.outputTokensCount)
                    // wire tap（2026-11-12 缺口清偿）：请求体（DEBUG）与 SSE usage 帧
                    // cached_tokens（纯指标）自 HttpClient 装饰层旁路捕获——Koog EventHandler
                    // 不暴露原始请求，metaInfo 亦丢弃 cachedTokens（1.3.0 createMetaInfo）。
                    val tap = RemoteModelFactory.wireTap
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
                                requestJson = RemoteModelFactory.capturedRequestBody(tap),
                                // 正文 + 工具调用清单双记（原仅 textContent，纯工具轮记成 null 的观测盲区）
                                responseJson = if (RemoteModelFactory.captureContent) {
                                    LlmCallRecord.cap(summarizeResponseForLog(ctx.response))
                                } else {
                                    null
                                },
                                errorMessage = null,
                                traceId = traceIdHolder.value,
                                cachedTokens = tap?.latestCachedTokens(),
                            )
                        )
                    }
                }
            }
            .build()

    /**
     * base system prompt + 【关于用户】记忆快照 + 【会话摘要】段（M2，US-2.4）+ 技能清单段
     * + OpenUI 契约段（U2-2，flag on 才追加，恒最末）。
     * 摘要段拼在快照后；空则跳过（零开销）。每轮新鲜渲染（[CompactionSlots.render]），
     * 与 system prompt 同样不落盘——摘要记录落盘，渲染文本现算（不变式①语义）。
     * M3：意图子集覆写时 base 取子集 prompt（清单段与覆写 registry 同源），否则取 config 全量。
     * 技能清单段（[skillSection]，2026-11-08）拼在摘要后（全量/子集 base 共用）——
     * 技能是跨意图的元能力，子集裁剪不裁技能披露面。
     * OpenUI 契约段（[openUiContractSection]）追加在技能清单段之后、语言规则段收尾——
     * flag off 时空串零追加，产物与现行逐字节一致。
     * internal（非 private）：commonTest 契约测试钉 flag on/off 组装行为。
     */
    internal fun composeSystemPrompt(
        snapshot: String?,
        summaryText: String?,
        toolSet: ChatToolSetOverride?,
        skillSection: String?,
    ): String {
        val base = composeChatSystemPrompt(toolSet?.systemPrompt ?: config.systemPrompt, snapshot, summaryText, skillSection)
        if (openUiContractSection.isEmpty()) return base
        return base + "\n\n" + openUiContractSection
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
        /** Koog 一轮工具调用消耗的步数估计（nodeLLMRequest + nodeExecuteTool 等）。 */
        const val KOOG_STEPS_PER_LLM_ROUND = 3

        /** 端侧身份行（与 RemoteChatEngine.buildChatSystemPrompt 首句同源措辞；句式对齐契约身份行）。 */
        const val OPENUI_IDENTITY_LINE = "你是 PoLang 相册 AI 助手。"

        /**
         * 端侧模式覆盖段（2026-11-26 照片图集引导修复）：基础规则段（ChatPromptRules
         * 收敛规则）与工具描述里的「看照片 → render_html 出图集卡收尾」指引在
         * turn-native 模式下与「响应即程序」直接冲突（1.0.96 真机实证模型服从
         * 收敛规则，搜索照片出 HTML 卡而非 PhotoGrid 程序）——本段在契约段内、
         * DSL 规则前显式作废该指引。工具描述已中性化（双模共用不能写死路径），
         * 收敛规则段是 flag-off 文本气泡路径的活指引不能改，故覆盖只能落在这里。
         */
        const val OPENUI_TOOL_MODE_OVERRIDE =
            "\n模式覆盖（优先级高于上文工具描述与收敛规则中的渲染指引）：数据类工具\n" +
                "（search_media / refine_media_search / get_gallery_summary / run_gallery_script）\n" +
                "结果到手后不再调用 render_html 等渲染工具收尾——终结程序即交付物：看照片/图集 →\n" +
                "按下方数据禁令用结果尾部句柄绑定出 PhotoGrid；统计/趋势 → 程序内 Chart 组件；\n" +
                "render_html 仅在用户明确要求独立 HTML 沙箱卡（内容不含相册媒体）时才调用。\n"
    }
}

/** llm_call_log 的 chat 调用来源标签（非流式/流式两路录制共用）。 */
private const val RECORD_SOURCE = "chat-koog"

/**
 * 流式轮 llm_call_log 补录（Task 13，U2 跟进）：Koog 1.3.0 流式路径
 * （`ContextualPromptExecutor.executeStreaming`）只发 onLLMStreaming* 事件、不触发
 * onLLMCallCompleted（后者仅 `execute` 非流式路径发射，字节码实证）——T5 切流式后
 * 原录制点在流式回合整段缺失（llm_call_log 零记录）。本函数以 End 帧 metaInfo 补录，
 * 字段口径与非流式录制（onLLMCallCompleted handler）对齐；responseJson 记本轮累计
 * 快照正文（流式无完整 Message 可摘要，正文口径对齐 summarizeResponseForLog 的
 * text 部分）。两路互斥由 Koog 事件语义保证（流式失败回退一次性请求时 End 帧不达、
 * 仅 onLLMCallCompleted 录制），无双重记录。
 *
 * internal（非 private）：commonTest 钉字段口径与 release 纯指标模式。
 */
internal fun recordStreamingLlmCall(
    model: LLModel?,
    meta: ResponseMetaInfo?,
    roundText: String,
    traceId: String?,
) {
    val rec = RemoteModelFactory.recorder ?: return
    val tap = RemoteModelFactory.wireTap
    rec.record(
        LlmCallRecord(
            createdAt = Clock.System.now().toEpochMilliseconds(),
            source = RECORD_SOURCE,
            model = model?.id,
            success = true,
            latencyMs = null,
            promptTokens = meta?.inputTokensCount,
            completionTokens = meta?.outputTokensCount,
            totalTokens = meta?.totalTokensCount,
            requestJson = RemoteModelFactory.capturedRequestBody(tap),
            responseJson = if (RemoteModelFactory.captureContent) {
                LlmCallRecord.cap(roundText.takeIf { it.isNotBlank() })
            } else {
                null
            },
            errorMessage = null,
            traceId = traceId,
            cachedTokens = tap?.latestCachedTokens(),
        )
    )
}

/**
 * chat 链路策略配置选择（U2-2 OpenUI turn-native）：flag 驱动——
 * - off：现行配置逐字节不变（只传 [toolLoopGuard]，策略走内建节点非流式路径）；
 * - on：流式节点 + 终结程序守卫纠正（[openUiGuard.asCorrector]）+ 流式失败告警出口
 *   （流式失败回退一次性请求由策略层内建，轮次可用性优先，与 server 同语义）。
 * 纯函数便于 commonTest 直测（KoogChatAgentOpenUiTurnNativeTest 钉 on/off 两形态）。
 */
internal fun chatReActStrategyConfig(
    openUiTurnNative: Boolean,
    openUiGuard: OpenUiProgramGuard,
    toolLoopGuard: ToolLoopGuard,
): PoLangReActStrategyConfig =
    if (openUiTurnNative) {
        PoLangReActStrategyConfig(
            streaming = true,
            finalOutputCorrector = openUiGuard.asCorrector(),
            onStreamingFallback = { error ->
                Logger.w(
                    STRATEGY_LOG_TAG,
                    "OpenUI turn-native streaming request failed, fallback to one-shot: ${error.message}",
                )
            },
            toolLoopGuard = toolLoopGuard,
        )
    } else {
        PoLangReActStrategyConfig(toolLoopGuard = toolLoopGuard)
    }

private const val STRATEGY_LOG_TAG = "KoogChatAgent"

/**
 * chat system prompt 四段组装（base + 记忆快照 + 会话摘要 + 技能清单段），纯函数便于
 * commonTest 直测（US-2.4）。任一段空则跳过；摘要恒在快照后（快照是用户级长期事实，
 * 摘要是会话级近期情节）；技能清单段恒在最末（跨意图元能力，随技能同步变化触发重建）。
 * OpenUI 契约段不在此函数——由 KoogChatAgent.composeSystemPrompt 追加在技能清单段后
 * （flag off 零追加，本函数输出与现行逐字节一致）。
 */
internal fun composeChatSystemPrompt(
    base: String,
    snapshot: String?,
    summaryText: String?,
    skillSection: String? = null,
): String {
    val withSnapshot = if (snapshot.isNullOrBlank()) base else "$base\n\n$snapshot"
    val withSummary = if (summaryText.isNullOrBlank()) withSnapshot else "$withSnapshot\n\n【会话摘要】\n$summaryText"
    return if (skillSection.isNullOrBlank()) withSummary else "$withSummary\n$skillSection"
}
