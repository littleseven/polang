package com.mamba.picme.server.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
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
 * render_html 的 HTML 本体不走 Observation（LLM 只见 summary 回执），经 [onHtmlCard]
 * sink 侧路落 HtmlCard 事件——活轮由路由层把它注入 ToolRegistry 的 ServerToolService，
 * pending 重放由本类经 [ServerToolDispatcher.withHtmlCardSink] 绑定当轮 sink。
 *
 * ⚠️ Koog 1.3.0 EventHandler 的 Interceptor 是**同步非 suspend** 接口（javap 核实），
 * 钩子内落库只能 runBlocking 桥到 Dispatchers.IO。Koog 事件在自身执行线程触发、
 * DB 在 IO 池，无同池死锁；M1 规模下可接受。若 Koog 后续开放 suspend 钩子应迁回。
 *
 * [actionGate]：Action 事件发射点门控（2026-10-24 render_ui 校验）。Action 事件在
 * onToolCallStarting（工具体执行**之前**）落流+下行，工具体内的校验拦不住坏负载到端，
 * 故校验必须挂在这里——返回非 null 错误文本时该次调用**不落 Action、不 SSE 下行**
 * （端侧永远见不到坏卡；工具体独立校验同源规则，回错误文本给 LLM 同轮修正）。
 * 被拒调用无 Action 落库，天然不进入 pending 重放。
 */
class AgentRunner(
    private val executorFactory: AgentExecutorFactory,
    private val dispatcher: ServerToolDispatcher,
    private val reversibleTools: Set<String> = ServerToolService.REVERSIBLE_TOOLS,
    private val maxIterations: Int = 20,
    private val actionGate: (toolName: String, argsJson: String) -> String? = { _, _ -> null },
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
        onHtmlCard: suspend (html: String, summary: String) -> Unit = { _, _ -> },
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
        replayPending(session.id, onEvent, onHtmlCard)

        // 4-5. Koog loop + 收尾
        val built = executorFactory.create(target)
        val actionIds = mutableMapOf<String, Long>()
        try {
            val agent = AIAgent.builder()
                .graphStrategy(cloudAgentStrategy())
                .promptExecutor(built.executor)
                .llmModel(built.model)
                .toolRegistry(toolRegistry)
                .prompt(cloudAgentPrompt("polang-cloud-agent", systemPrompt, projected, built.params))
                .maxIterations(maxIterations)
                .install(EventHandler.Feature) { events ->
                    events.onToolCallStarting { ctx ->
                        // 同步钩子 → runBlocking 桥（见类 KDoc ⚠️）
                        runBlocking {
                            // Koog 私有 JSON 树 toString 零转义，必须走 kotlinx 映射（见 ToolArgsWireJson.kt）
                            val argsJson = toolArgsWireJson(ctx.toolArgs)
                            // 发射点门控：被拒负载不落 Action、不下行（见类 KDoc actionGate 段）
                            if (actionGate(ctx.toolName, argsJson) != null) return@runBlocking
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
                    payload = AgentEventPayload.Message(result),
                ),
            )
            val finish = AgentEventRepository.append(
                session.id, AgentEventSource.AGENT, AgentEventRole.SYSTEM,
                payload = AgentEventPayload.Finish("done"),
            )
            emit(onEvent, finish)
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
        onHtmlCard: suspend (html: String, summary: String) -> Unit,
    ) {
        // render_html 重放须绑定当轮 sink：卡片重新落 HtmlCard 事件并随 SSE 下行
        val scopedDispatcher = dispatcher.withHtmlCardSink(onHtmlCard)
        AgentEventRepository.pendingActions(sessionId).forEach { pending ->
            val action = pending.payload as AgentEventPayload.Action
            val content = try {
                scopedDispatcher.dispatch(action.tool, action.argsJson)
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
