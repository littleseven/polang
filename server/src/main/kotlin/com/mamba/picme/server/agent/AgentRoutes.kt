package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asToolsByClass
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.routes.TokenHashKey
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.response.respondTextWriter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** BYOK 透传头（ADR-018 决策 4：请求级透传，零留存）。 */
const val BYOK_API_KEY_HEADER = "X-User-Api-Key"

private const val DEFAULT_SYSTEM_PROMPT =
    "你是 PoLang 云端 Agent。用中文简洁回答。需要服务器时间时调用 server_time 工具。"

/**
 * 云端 Agent 路由面（ADR-018 决策 3：独立会话域；决策 4：BYOK 透传）。
 *
 * - `POST /agent/sessions`：建会话（channel=gateway|byok；byok 必须带 byok_base_url 与 model）；
 * - `GET /agent/sessions`：我的会话列表（最新在前）；
 * - `GET /agent/sessions/{id}/events?after=&limit=`：事件流分页回放（他人会话 404，不泄露存在性）；
 * - `POST /agent/sessions/{id}/messages`：SSE 流式跑一轮。帧协议：
 *   `data: {"kind":"event","event":{…}}`（每个落流事件一帧，序一致）→ 终结帧
 *   `{"kind":"done"}` 或 `{"kind":"error","message":…}`。HTTP 恒 200（错误经帧传递，
 *   与 SSE 语义一致）；前置失败（鉴权/配额/参数）仍为常规错误码。
 *   M2 起新增 `{"kind":"tool_request","request_id","tool","args","timeout_ms"}` 帧——
 *   设备工具调用经工具桥下发给端侧执行（传输层信号，不落事件流）；与 event/done/error
 *   帧共用同一 writer 天然保序。设备工具集每轮在路由层以当轮 writer 构造并合成进
 *   ToolRegistry（服务端工具 + 设备工具），[AgentRunner] 零改动。
 * - `POST /agent/sessions/{id}/tool-results`：端侧工具结果上行（M2）。body
 *   `{"request_id","ok","result"}` → `200 {"accepted":true}` / `400 request_id_required` /
 *   `404 session_not_found`（他人会话同口径，不泄露存在性）/ `409 tool_request_not_pending`
 *   （requestId 未知或已完成——迟到上行安全）。ok=false 时云端以 `error: <result>` 作为
 *   工具返回值（Observation 照常落流）。连接关闭（正常/异常/端断线）经 `bridge.failAll`
 *   清理该会话挂起项，等待方以 error 文本闭环，防泄漏。
 * - `POST /agent/sessions/{id}/finish`：关闭会话（终态 FINISHED）。
 *
 * BYOK 零留存：X-User-Api-Key 仅在本请求内存中组装 [AgentLlmTarget]，不落库、不进日志、
 * 不消耗网关配额（守卫测试 AgentRoutesTest 钉死）。工具桥链路不接触 target/key。
 *
 * M2.6 起 per-user 跑轮并发=1（[AgentRoundGate]，messages 路由 try/finally 严格配对释放）：
 * 有在跑轮时回 409 `round_in_flight`（占位在配额扣减之前，拒绝不消耗配额）——
 * 原 M1 已知边界（同会话并发跑轮事件交错 + 连坐置 ERROR）由此消除。
 * 云端 Agent 轮次不写 llm_call_log（审计数据源即事件流；用量指标经配额计数）。
 */
fun Route.agentRoute(
    runner: AgentRunner,
    bridge: AgentBridgeRegistry,
    deviceTimeoutMs: Long = DeviceToolService.DEFAULT_TIMEOUT_MS,
    roundGate: AgentRoundGate = AgentRoundGate(),
) {
    route("/agent") {
        post("/sessions") {
            val tokenHash = call.attributes.getOrNull(TokenHashKey)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            val body = call.receive<JsonObject>()
            val model = (body["model"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val channel = (body["channel"] as? JsonPrimitive)?.contentOrNull ?: "gateway"
            val byokBaseUrl = (body["byok_base_url"] as? JsonPrimitive)?.contentOrNull.orEmpty()

            val kind = when (channel) {
                "gateway" -> AgentChannelKind.GATEWAY
                "byok" -> AgentChannelKind.BYOK
                else -> return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "bad_request", "message" to "channel must be gateway|byok"),
                )
            }
            if (kind == AgentChannelKind.BYOK) {
                if (byokBaseUrl.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "byok_base_url_required"),
                    )
                }
                if (model.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "model_required", "message" to "byok channel requires explicit model"),
                    )
                }
            }

            val session = AgentSessionRepository.create(tokenHash, kind, model, byokBaseUrl)
            call.respond(
                buildJsonObject {
                    put("session_id", session.id)
                    put("status", session.status.wire)
                    put("channel", session.channelKind.wire)
                    put("model", session.model)
                },
            )
        }

        get("/sessions") {
            val tokenHash = call.attributes.getOrNull(TokenHashKey)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            val sessions = AgentSessionRepository.listByOwner(tokenHash)
            call.respond(
                buildJsonObject {
                    putJsonArray("sessions") {
                        sessions.forEach { s -> add(sessionJson(s)) }
                    }
                },
            )
        }

        get("/sessions/{id}/events") {
            val tokenHash = call.attributes.getOrNull(TokenHashKey)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            val session = findOwned(call.parameters["id"].orEmpty(), tokenHash)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "session_not_found"))
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 200
            val events = AgentEventRepository.listBySession(session.id, afterId = after, limit = limit)
            call.respond(
                buildJsonObject {
                    putJsonArray("events") {
                        events.forEach { e -> add(eventJson(e)) }
                    }
                },
            )
        }

        post("/sessions/{id}/finish") {
            val tokenHash = call.attributes.getOrNull(TokenHashKey)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            val session = findOwned(call.parameters["id"].orEmpty(), tokenHash)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "session_not_found"))
            if (!session.status.isTerminal) {
                AgentSessionRepository.updateStatus(session.id, AgentSessionStatus.FINISHED)
            }
            call.respond(buildJsonObject { put("status", AgentSessionStatus.FINISHED.wire) })
        }

        post("/sessions/{id}/tool-results") {
            val tokenHash = call.attributes.getOrNull(TokenHashKey)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            val session = findOwned(call.parameters["id"].orEmpty(), tokenHash)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "session_not_found"))
            val body = call.receive<JsonObject>()
            val requestId = (body["request_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "request_id_required"))
            val ok = (body["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val result = (body["result"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            // ok=false：端执行异常，result 为错误描述，云端以 "error: <描述>" 作为工具返回值（spec §3.2）
            // 协议契约：上行 result 可能已带 "error: " 前缀（DeviceToolExecutor 契约），服务端归一化为恰好一个
            val text = if (ok) result else "error: " + result.removePrefix("error: ")
            if (!bridge.complete(requestId, text)) {
                return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "tool_request_not_pending"))
            }
            call.respond(buildJsonObject { put("accepted", true) })
        }

        post("/sessions/{id}/messages") {
            val tokenHash = call.attributes.getOrNull(TokenHashKey)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            val session = findOwned(call.parameters["id"].orEmpty(), tokenHash)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "session_not_found"))
            if (session.status.isTerminal) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "session_terminal", "status" to session.status.wire),
                )
            }
            val body = call.receive<JsonObject>()
            val content = (body["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "content_required"))

            // per-user 跑轮并发闸（M2.6）：须在配额扣减之前占位，busy 拒绝不消耗配额
            if (!roundGate.tryAcquire(tokenHash)) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "round_in_flight", "message" to "previous round still running"),
                )
            }
            try {
                // 通道解析 + 配额（网关扣减；BYOK 零接触）
                var gatewayQuotaCharged = false
                val target = when (session.channelKind) {
                    AgentChannelKind.GATEWAY -> {
                        val channel = ChannelRegistry.active()
                            ?: return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                mapOf("error" to "no_active_channel"),
                            )
                        val t = AgentTargetResolver.fromChannel(channel, session.model)
                            ?: return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                mapOf("error" to "channel_unavailable", "channel" to channel.name),
                            )
                        if (!AccountService.checkAndIncrementQuota(tokenHash)) {
                            return@post call.respond(
                                HttpStatusCode.Forbidden,
                                mapOf("error" to "quota_exceeded", "tier" to "account"),
                            )
                        }
                        gatewayQuotaCharged = true
                        t
                    }
                    AgentChannelKind.BYOK -> {
                        // 零留存：key 只在本请求内存存活，永不进入 Repository/日志调用链
                        val key = call.request.headers[BYOK_API_KEY_HEADER]?.takeIf { it.isNotBlank() }
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "byok_key_required"),
                            )
                        AgentTargetResolver.byok(key, session.byokBaseUrl, session.model)
                    }
                }

                call.respondTextWriter(ContentType.Text.EventStream) {
                    val deviceTools = DeviceToolService(session.id, bridge, deviceTimeoutMs) { frame ->
                        writeSseFrame(frame.toJson())
                    }
                    val toolRegistry = ToolRegistry {
                        tools(ServerToolService().asToolsByClass() + deviceTools.asToolsByClass())
                    }
                    try {
                        runner.runTurn(
                            session = session,
                            userMessage = content,
                            target = target,
                            systemPrompt = DEFAULT_SYSTEM_PROMPT,
                            toolRegistry = toolRegistry,
                            onEvent = { event ->
                                writeSseFrame(buildJsonObject {
                                    put("kind", "event")
                                    put("event", eventJson(event))
                                })
                            },
                        )
                        writeSseFrame(buildJsonObject {
                            put("kind", "done")
                            put("session_status", AgentSessionStatus.RUNNING.wire)
                        })
                    } catch (e: Throwable) {
                        AgentSessionRepository.updateStatus(session.id, AgentSessionStatus.ERROR)
                        if (gatewayQuotaCharged) AccountService.revertQuota(tokenHash)
                        writeSseFrame(buildJsonObject {
                            put("kind", "error")
                            put("message", e.message?.take(200) ?: "internal error")
                        })
                    } finally {
                        // 连接关闭（正常结束/异常/端断线）清理该会话桥挂起项，防泄漏
                        bridge.failAll(session.id, "connection_closed")
                    }
                }
            } finally {
                // 租约严格配对释放（含上方一切 early return）；无 TTL——见 AgentRoundGate 不变式
                roundGate.release(tokenHash)
            }
        }
    }
}

private fun java.io.Writer.writeSseFrame(obj: JsonObject) {
    write("data: ")
    write(obj.toString())
    write("\n\n")
    flush()
}

private suspend fun findOwned(id: String, tokenHash: String): AgentSession? =
    AgentSessionRepository.find(id)?.takeIf { it.tokenHash == tokenHash }

private fun sessionJson(s: AgentSession) = buildJsonObject {
    put("session_id", s.id)
    put("status", s.status.wire)
    put("channel", s.channelKind.wire)
    put("model", s.model)
    put("created_at", s.createdAt)
    put("updated_at", s.updatedAt)
    s.finishedAt?.let { put("finished_at", it) }
}

private fun eventJson(e: AgentEvent) = buildJsonObject {
    put("id", e.id)
    put("type", e.type.wire)
    put("source", e.source.wire)
    put("role", e.role.wire)
    e.parentId?.let { put("parent_id", it) }
    put("payload", AgentEventPayloadCodec.encodeToElement(e.payload) as JsonElement)
    put("created_at", e.createdAt)
}
