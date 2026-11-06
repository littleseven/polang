package com.mamba.picme.server.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("picme-agent")

/**
 * 云端 Agent 运行器（ADR-018：Agent 无状态、Session 有状态；事件流唯一真相）。
 *
 * 一轮（runTurn）的流程：
 * 1. 历史投影在追加新消息**之前**计算（agent.run(input) 会把 input 作为新 user 消息
 *    追加进 prompt，投影含它会重复）；
 * 2. 用户消息落流（source=USER）；
 * 3. **pending action 不变式**：先重放上轮遗留的 pending Action（崩溃恢复语义）——
 *    执行服务端工具补 Observation；未知工具以 error Observation 闭环；
 * 4. Koog loop：工具调用的 Action/Observation 经 EventHandler 边产生边落流；
 * 5. 助手答复与 Finish 落流。LLM 异常 → Finish(reason="error: …") 落流后重抛
 *   （路由层据此置会话 ERROR + 回退配额）。
 *
 * 每个事件落流后回调 [onEvent]（SSE 发射点），顺序与事件序一致。
 *
 * ⚠️ Koog 1.3.0 EventHandler 的 Interceptor 是**同步非 suspend** 接口（javap 核实），
 * 钩子内落库只能 runBlocking 桥到 Dispatchers.IO。Koog 事件在自身执行线程触发、
 * DB 在 IO 池，无同池死锁；M1 规模下可接受。若 Koog 后续开放 suspend 钩子应迁回。
 *
 * GenUI 唯一化（2026-11-06）：/agent 路由面恒走 OpenUI 回合原生（turn-native）——
 * 无发卡工具、无 A2UI 回退线。终结程序守卫 [ProgramGuard] 每轮无条件创建
 * （策略层 correctInvalidProgram 节点纠正，上限 1 次）；终结消息落流前无条件经
 * [OpenUiTurnNative.ensureValidProgram] 兜底变换（「端上永远收到合法树」双保险）。
 * 该线无发卡工具，「声称发卡」话术由程序校验与兜底包装天然管辖。
 *
 * [onOpenUiProgramDelta]：program 前缀流式预览旁路。策略层 LLM 节点经
 * `requestLLMStreaming()` 逐帧收集（内建 nodeLLMRequest 是一次性请求，不产
 * StreamFrame），content TextDelta 无条件经 OpenUiContentStreamTracker 节流为
 * program 前缀推向 SSE（响应即程序），**不落 agent_event**（事件溯源只收最终
 * Action/消息，预览帧是瞬态传输信号，同 tool_request 口径）。正式消息帧仍是
 * 唯一权威负载；端侧新旧版本混跑安全（旧端忽略未知 kind 帧）。
 * 轮边界经 onLLMStreamingStarting 重置 content 前缀（端上覆盖式预览）。
 *
 * [turnUsage]（runTurn 参数）：回合用量累计出参（done 帧 usage/elapsed_ms 数据源）——
 * 流式路径经 onLLMStreamingFrameReceived 的 End 帧 metaInfo 累加（Koog 1.3.0 流式不触发
 * onLLMCallCompleted），非流式回退路径经 onLLMCallCompleted 累加，成功收尾回填 elapsedMs。
 * 缺省独立实例 = 无人观测，零副作用；本类无状态，多回合并发各自传入互不串扰。
 */
class AgentRunner(
    private val executorFactory: AgentExecutorFactory,
    private val dispatcher: ServerToolDispatcher,
    private val reversibleTools: Set<String> = ServerToolService.REVERSIBLE_TOOLS,
    private val maxIterations: Int = 100,
) {

    /**
     * @return 本轮 Finish 事件（reason="done" 或 "error: …"）。
     * @throws Throwable LLM/工具异常（Finish 已落流后重抛）
     */
    suspend fun runTurn(
        session: AgentSession,
        userMessage: String,
        target: AgentLlmTarget,
        systemPrompt: String,
        toolRegistry: ToolRegistry,
        onOpenUiProgramDelta: suspend (programPrefix: String) -> Unit = {},
        /** 回合用量累计出参（done 帧性能数据源）：逐次累加 tokens、成功收尾回填耗时；缺省独立实例 = 无人观测。 */
        turnUsage: TurnUsage = TurnUsage(),
        onEvent: suspend (AgentEvent) -> Unit = {},
    ): AgentEvent {
        // 1. 投影（先于本轮用户消息落流）
        val history = AgentEventRepository.listBySession(session.id, limit = Int.MAX_VALUE)
        val projected = AgentProjection.project(history)

        // 2. 用户消息落流
        emit(
            onEvent,
            AgentEventRepository.append(
                session.id, AgentEventSource.USER, AgentEventRole.USER,
                payload = AgentEventPayload.Message(userMessage),
            ),
        )

        // 3. pending 重放
        replayPending(session.id, onEvent)

        // 4-5. Koog loop + 收尾
        val built = executorFactory.create(target)
        val actionIds = mutableMapOf<String, Long>()
        // 终结程序守卫（每轮新实例）：GenUI 唯一化后无条件启用——终结文本非合法
        // OpenUI 程序 → 策略层 correctInvalidProgram 节点携带错误清单回环纠正
        val programGuard = ProgramGuard()
        // content 流跟踪器（每轮新实例）：TextDelta 无条件透传为 program 前缀预览
        val contentStreamTracker = OpenUiContentStreamTracker()
        val startedAt = System.currentTimeMillis()
        try {
            val agent = AIAgent.builder()
                .graphStrategy(cloudAgentStrategy(programGuard))
                .promptExecutor(built.executor)
                .llmModel(built.model)
                .toolRegistry(toolRegistry)
                .prompt(cloudAgentPrompt("polang-cloud-agent", systemPrompt, projected, built.params))
                .maxIterations(maxIterations)
                .install(EventHandler.Feature) { events ->
                    events.onLLMStreamingStarting {
                        // 新一轮 LLM 流式（工具调用后的下一轮）：前缀从空重新累计
                        // （shared KoogChatAgent.kt:233 同款钩子，覆盖式预览的轮边界）
                        contentStreamTracker.onRoundStart()
                    }
                    events.onLLMStreamingFrameReceived { ctx ->
                        // 流式预览旁路（不落事件流）。同步钩子 → runBlocking 桥
                        val prefix = when (val frame = ctx.streamFrame) {
                            is StreamFrame.TextDelta -> contentStreamTracker.onTextDelta(frame.text)
                            is StreamFrame.End -> {
                                // 回合用量（done 帧性能数据源）：Koog 1.3.0 流式路径只触发
                                // onLLMStreaming* 事件、不触发 onLLMCallCompleted（后者仅非流式
                                // execute 路径发射，javap 核实 ContextualPromptExecutor 是唯一调用点），
                                // 故流式调用的 usage 只能从 End 帧 metaInfo 拿——它由 Koog OpenAI
                                // 客户端从上游 usage chunk 构建（include_usage 由客户端自行携带）。
                                turnUsage.add(
                                    frame.metaInfo?.inputTokensCount?.toLong(),
                                    frame.metaInfo?.outputTokensCount?.toLong(),
                                )
                                null
                            }
                            else -> null
                        }
                        if (prefix != null) {
                            runBlocking { onOpenUiProgramDelta(prefix) }
                        }
                    }
                    events.onToolCallStarting { ctx ->
                        // 同步钩子 → runBlocking 桥（见类 KDoc ⚠️）
                        runBlocking {
                            // Koog 私有 JSON 树 toString 零转义，必须走 kotlinx 映射（见 ToolArgsWireJson.kt）
                            val argsJson = toolArgsWireJson(ctx.toolArgs)
                            val action = AgentEventRepository.append(
                                session.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
                                payload = AgentEventPayload.Action(
                                    tool = ctx.toolName,
                                    argsJson = argsJson,
                                    reversible = ctx.toolName in reversibleTools,
                                ),
                            )
                            ctx.toolCallId?.let { actionIds[it] = action.id }
                            emit(onEvent, action)
                        }
                    }
                    events.onToolCallCompleted { ctx ->
                        runBlocking {
                            val actionId = ctx.toolCallId?.let { actionIds[it] } ?: 0L
                            emit(
                                onEvent,
                                AgentEventRepository.append(
                                    session.id, AgentEventSource.ENVIRONMENT, AgentEventRole.TOOL,
                                    parentId = actionId.takeIf { it != 0L },
                                    payload = AgentEventPayload.Observation(
                                        actionEventId = actionId,
                                        content = ctx.toolResult?.toString() ?: "",
                                    ),
                                ),
                            )
                        }
                    }
                    events.onLLMCallCompleted { ctx ->
                        // 非流式回退路径（requestLLMStreamingOrOnce 流式失败回退 requestLLM）的
                        // 用量累加——Koog 1.3.0 仅非流式 execute 触发本事件，流式路径的用量
                        // 在 onLLMStreamingFrameReceived 的 End 帧分支累加（两路互斥无双计）
                        val meta = ctx.response?.metaInfo
                        turnUsage.add(meta?.inputTokensCount?.toLong(), meta?.outputTokensCount?.toLong())
                    }
                    events.onToolCallFailed { ctx ->
                        runBlocking {
                            val actionId = ctx.toolCallId?.let { actionIds[it] } ?: 0L
                            emit(
                                onEvent,
                                AgentEventRepository.append(
                                    session.id, AgentEventSource.ENVIRONMENT, AgentEventRole.TOOL,
                                    parentId = actionId.takeIf { it != 0L },
                                    payload = AgentEventPayload.Observation(
                                        actionEventId = actionId,
                                        content = "error: ${ctx.message ?: ctx.error?.message ?: "tool failed"}",
                                    ),
                                ),
                            )
                        }
                    }
                }
                .build()
            val result = agent.run(userMessage)
            emit(
                onEvent,
                AgentEventRepository.append(
                    session.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
                    // 终结兜底（GenUI 唯一化）：合法 normalize 放行 / 非法兜底 Markdown 包装
                    payload = AgentEventPayload.Message(OpenUiTurnNative.ensureValidProgram(result)),
                ),
            )
            val finish = AgentEventRepository.append(
                session.id, AgentEventSource.AGENT, AgentEventRole.SYSTEM,
                payload = AgentEventPayload.Finish("done"),
            )
            emit(onEvent, finish)
            turnUsage.finish(System.currentTimeMillis() - startedAt)
            return finish
        } catch (e: CancellationException) {
            emit(
                onEvent,
                AgentEventRepository.append(
                    session.id, AgentEventSource.AGENT, AgentEventRole.SYSTEM,
                    payload = AgentEventPayload.Finish("cancelled"),
                ),
            )
            throw e
        } catch (e: Throwable) {
            logger.warn("Agent turn failed, session={}: {}", session.id, e.message)
            emit(
                onEvent,
                AgentEventRepository.append(
                    session.id, AgentEventSource.AGENT, AgentEventRole.SYSTEM,
                    payload = AgentEventPayload.Finish("error: ${e.message?.take(200) ?: e::class.simpleName}"),
                ),
            )
            throw e
        }
    }

    /** pending action 不变式：恢复时按追加序优先执行 pending，补 Observation 闭环。 */
    private suspend fun replayPending(
        sessionId: String,
        onEvent: suspend (AgentEvent) -> Unit,
    ) {
        // 历史遗留的退役工具（如 render_html）pending：dispatcher 无此 handler，
        // 以 error Observation 闭环（不变式不受退役影响）
        AgentEventRepository.pendingActions(sessionId).forEach { pending ->
            val action = pending.payload as AgentEventPayload.Action
            val content = try {
                dispatcher.dispatch(action.tool, action.argsJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "error: ${e.message ?: e::class.simpleName}"
            }
            emit(
                onEvent,
                AgentEventRepository.append(
                    sessionId, AgentEventSource.ENVIRONMENT, AgentEventRole.TOOL,
                    parentId = pending.id,
                    payload = AgentEventPayload.Observation(actionEventId = pending.id, content = content),
                ),
            )
        }
    }

    private suspend fun emit(onEvent: suspend (AgentEvent) -> Unit, event: AgentEvent) {
        onEvent(event)
    }
}
