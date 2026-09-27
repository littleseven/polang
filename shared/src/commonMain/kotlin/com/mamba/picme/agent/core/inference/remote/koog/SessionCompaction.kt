package com.mamba.picme.agent.core.inference.remote.koog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * M2 滚动摘要（compaction）数据层（US-2.2/2.3/2.5）。
 *
 * 会话内「旧轮」的滚动压缩产物：当 L1 工作记忆（token 预算制组装）装不下时，最旧 M 个对话轮
 * 经 server 网关（S4，纯文本 [PRIVACY] 合规）压缩为四槽位摘要，随会话持久化（[ChatMemoryStore]），
 * 会话恢复时重放——早期语境以摘要形态保留，不再硬遗忘（spec §4 US-2.1~2.6）。
 *
 * - [version] 单调递增（每次 compaction +1），注入时据此判新旧；
 * - [slots] 四槽位（intent/decisions/todos/entities），槽位化抗细节丢失（spec §5 决策 4）；
 * - [compactedUpToTurnId] 已压缩到的实轮序号（增量合并时定位新轮起点）；
 * - [createdAt]  epoch millis（kotlin.time.Clock，编码约定见 shared/AGENTS.md §6）。
 *
 * 与 system prompt 同样**不落盘消息历史**（不变式①语义）——摘要记录本身落盘（这是 L2 情节记忆
 * 的持久化形态），但每轮注入的渲染文本现算（[CompactionSlots.render]）。
 */
@Serializable
public data class SessionCompaction(
    public val version: Int,
    public val slots: CompactionSlots,
    public val compactedUpToTurnId: Int,
    public val createdAt: Long,
) {
    public companion object {
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        public fun encode(value: SessionCompaction): String = json.encodeToString(serializer(), value)

        /** 解码失败返回 null（调用方兜底为空摘要，绝不因解析阻断对话，spec §5 决策 9）。 */
        public fun decode(raw: String): SessionCompaction? = runCatching {
            json.decodeFromString(serializer(), raw)
        }.getOrNull()
    }
}

/**
 * 四槽位摘要（US-2.2）。LLM 输出经 [parseSlots] 解析——合法 JSON 按槽位填，解析失败整体回退
 * 自由文本进 [intent]（降级不阻断，spec §5 决策 9）。
 */
@Serializable
public data class CompactionSlots(
    public val intent: String = "",
    public val decisions: List<String> = emptyList(),
    public val todos: List<String> = emptyList(),
    public val entities: List<String> = emptyList(),
) {

    /**
     * 增量合并（US-2.5）：v(n+1) = old.merge(new)。
     *
     * - 标量槽 [intent]：新非空覆盖旧，新空保留旧；
     * - 列表槽：旧 + 新追加，**去重保序**（新项追加尾部）；
     * - 全空新槽 = 无新增信息，旧摘要原样保留。
     */
    public fun merge(newer: CompactionSlots): CompactionSlots = CompactionSlots(
        intent = newer.intent.ifBlank { intent },
        decisions = (decisions + newer.decisions).distinct(),
        todos = (todos + newer.todos).distinct(),
        entities = (entities + newer.entities).distinct(),
    )

    /**
     * 渲染为注入 system prompt 的文本段（US-2.4）。空槽位跳过；只含非空槽的行。
     * 每轮新鲜调用，产物不落盘。
     */
    public fun render(): String = buildString {
        if (intent.isNotBlank()) appendLine("会话意图：$intent")
        if (decisions.isNotEmpty()) {
            appendLine("已做决定：")
            decisions.forEach { decision -> appendLine("- $decision") }
        }
        if (todos.isNotEmpty()) {
            appendLine("待办：")
            todos.forEach { todo -> appendLine("- $todo") }
        }
        if (entities.isNotEmpty()) {
            appendLine("关键实体：")
            entities.forEach { entity -> appendLine("- $entity") }
        }
    }.trimEnd()

    public companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /**
         * 解析 LLM 摘要输出为槽位（US-2.2）：
         * - 合法四槽位 JSON → 逐槽填充（缺槽补默认，类型错单槽回退默认）；
         * - 任何解析失败 → 原文整体进 [CompactionSlots.intent]（自由文本降级）。
         */
        public fun parseSlots(raw: String): CompactionSlots {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return CompactionSlots()
            return runCatching {
                json.decodeFromString(serializer(), trimmed)
            }.getOrElse {
                CompactionSlots(intent = trimmed)
            }
        }
    }
}
