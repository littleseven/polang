package com.mamba.picme.server.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 云端 Agent 会话事件模型（ADR-018 决策 1）。
 *
 * 事件流是唯一真相：不可变、类型化、只追加。
 * 三维正交标注：[AgentEventSource]（真实来源）/ [AgentEventRole]（投影给模型的角色）/
 * parentId（分支锚点，M1 仅预留）。
 */

/** 事件类型 wire 值（agent_event.type 列）。 */
enum class AgentEventType(val wire: String) {
    MESSAGE("message"),
    ACTION("action"),
    OBSERVATION("observation"),
    CONDENSATION("condensation"),
    FINISH("finish"),
    HTML_CARD("html_card");

    companion object {
        fun fromWire(wire: String): AgentEventType =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("unknown agent event type: $wire")
    }
}

/** 事件真实来源。 */
enum class AgentEventSource(val wire: String) {
    USER("user"),
    AGENT("agent"),
    ENVIRONMENT("environment");

    companion object {
        fun fromWire(wire: String): AgentEventSource =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("unknown agent event source: $wire")
    }
}

/** 投影给模型时的角色。 */
enum class AgentEventRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromWire(wire: String): AgentEventRole =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("unknown agent event role: $wire")
    }
}

/**
 * 类型化事件载荷（agent_event.payload_json）。
 * Observation 显式携带 actionEventId —— pending action 不变式的匹配锚点：
 * 没有匹配 Observation 的 Action 视为 pending，恢复时优先执行。
 */
@Serializable
sealed class AgentEventPayload {
    abstract fun type(): AgentEventType

    @Serializable
    @SerialName("message")
    data class Message(val content: String) : AgentEventPayload() {
        override fun type() = AgentEventType.MESSAGE
    }

    @Serializable
    @SerialName("action")
    data class Action(
        val tool: String,
        val argsJson: String,
        val reversible: Boolean,
    ) : AgentEventPayload() {
        override fun type() = AgentEventType.ACTION
    }

    @Serializable
    @SerialName("observation")
    data class Observation(
        val actionEventId: Long,
        val content: String,
        /** 决策 1a：逆操作产生的 Observation 标注 revert=true。 */
        val revert: Boolean = false,
    ) : AgentEventPayload() {
        override fun type() = AgentEventType.OBSERVATION
    }

    @Serializable
    @SerialName("condensation")
    data class Condensation(
        val summary: String,
        val upToEventId: Long,
    ) : AgentEventPayload() {
        override fun type() = AgentEventType.CONDENSATION
    }

    @Serializable
    @SerialName("finish")
    data class Finish(val reason: String) : AgentEventPayload() {
        override fun type() = AgentEventType.FINISH
    }

    /**
     * render_html 产出的 H5 卡本体（summary-only 回执语义：HTML 不进 LLM 上下文，
     * 只经事件流下行端上渲染）。投影层只消费 Message，本载荷天然不进模型视图。
     */
    @Serializable
    @SerialName("html_card")
    data class HtmlCard(val html: String, val summary: String) : AgentEventPayload() {
        override fun type() = AgentEventType.HTML_CARD
    }
}

/** payload_json 编解码。判别字段内嵌于 JSON，解码不依赖 type 列。 */
object AgentEventPayloadCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(payload: AgentEventPayload): String =
        json.encodeToString(AgentEventPayload.serializer(), payload)

    /** 路由线协议用：载荷作为内嵌 JSON 元素（非字符串转义）。 */
    fun encodeToElement(payload: AgentEventPayload) =
        json.encodeToJsonElement(AgentEventPayload.serializer(), payload)

    fun decode(raw: String): AgentEventPayload =
        json.decodeFromString(AgentEventPayload.serializer(), raw)
}

/** 从事件流读回的完整事件记录。 */
data class AgentEvent(
    val id: Long,
    val sessionId: String,
    val type: AgentEventType,
    val source: AgentEventSource,
    val role: AgentEventRole,
    val parentId: Long?,
    val payload: AgentEventPayload,
    val createdAt: Long,
)
