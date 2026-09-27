package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Koog 记忆三不变式（纯函数，无 Android 依赖，便于 JVM 单测）。
 *
 * 移植自 langchain4j `DataStoreChatMemory.trimToMaxMessages` 与
 * `DataStoreChatMemoryStore.sanitizeMessages` 的语义，适配 Koog 1.1.1 的 **part-based** 消息模型：
 * - 工具调用：[MessagePart.Tool.Call] 作为 part 嵌在 [Message.Assistant.parts]（ResponsePart 列表）。
 * - 工具结果：[MessagePart.Tool.Result] 作为 part 嵌在 [Message.User.parts]（RequestPart 列表）。
 * - 配对键：Call.id ↔ Result.id（双向）。
 *
 * 三不变式（与旧 langchain4j 链路逐条对齐，避免远端 OpenAI 400 "insufficient tool messages
 * following tool_calls message" / "tool_calls without tool results"）：
 * ① SystemMessage 不落盘（[withoutSystemMessages]）。
 * ② tool_call 块原子裁剪（[trimToMaxMessages]）：含 Call 的 Assistant + 紧随其后的所有含 Result
 *    的 User 视为一个不可拆块；裁剪时整块保留/丢弃，绝不拆散（否则半块 Call 无 Result 致 400）。
 * ③ 双向配对剔除悬空（[sanitizeToolPairing]）：无对应 Result 的 Call、无对应 Call 的 Result
 *    一律剔除（重建父消息的 parts 列表，parts 全空则整条丢弃）。
 *
 * 注：1.1.1 **没有**顶层 `Message.Tool` 类型（那是 master 分支，勿参照）；工具信息全部以 part
 * 形式嵌在 Assistant/User 消息内。持久化胶水见 `KoogMessageMemoryStore`。
 */
public object KoogMessageMemory {

    /** 最大历史消息数（与旧 `RemoteReActAgent.maxMemoryMessages = 10` 对齐）。 */
    public const val MAX_MESSAGES: Int = 10

    // ── 不变式 ①：SystemMessage 不落盘 ─────────────────────────

    /**
     * 剔除所有 [Message.System]。system prompt 每轮运行期新鲜组装，持久化会让旧版本 prompt
     * 永久滞留在老会话（见 langchain4j 期 `DataStoreChatMemory` cache 注释的实测教训）。
     */
    public fun withoutSystemMessages(messages: List<Message>): List<Message> =
        messages.filterNot { message -> message is Message.System }

    // ── 不变式 ③：双向配对剔除悬空 tool part ──────────────────

    /**
     * 双向配对剔除悬空 tool part。
     *
     * - 收集所有 Call.id（来自各 Assistant.parts）与 Result.id（来自各 User.parts）。
     * - [validIds] = Call.ids ∩ Result.ids（同时拥有 Call 与 Result 的 id）。
     * - 重建每个含 tool part 的消息：保留 id ∈ [validIds] 的 tool part + 所有非 tool part（Text 等）；
     *   若重建后 parts 为空（即该消息全是悬空 tool part）则整条丢弃。
     * - 无任何 tool part 时原样返回同一列表。
     */
    public fun sanitizeToolPairing(messages: List<Message>): List<Message> {
        // Call/Result 的 id 在 1.1.1 声明为 String?（极端边界可为 null，正常由 LLM 给出 call_xxx）。
        // null id 互通参与交集运算（与 langchain4j 期"null 视作可配对哨兵"语义一致）。
        val callIds = mutableSetOf<String?>()
        val resultIds = mutableSetOf<String?>()
        for (message in messages) {
            when (message) {
                is Message.Assistant -> message.parts.filterIsInstance<MessagePart.Tool.Call>()
                    .forEach { part -> callIds.add(part.id) }
                is Message.User -> message.parts.filterIsInstance<MessagePart.Tool.Result>()
                    .forEach { part -> resultIds.add(part.id) }
                else -> { /* System 等无 tool part */ }
            }
        }
        if (callIds.isEmpty() && resultIds.isEmpty()) return messages

        val validIds = callIds.intersect(resultIds)
        return messages.mapNotNull { message -> sanitizeMessage(message, validIds) }
    }

    private fun sanitizeMessage(message: Message, validIds: Set<String?>): Message? =
        when (message) {
            is Message.Assistant -> {
                if (!message.hasToolCalls()) {
                    message
                } else {
                    val kept = message.parts.filter { part ->
                        part !is MessagePart.Tool.Call || part.id in validIds
                    }
                    if (kept.isEmpty()) null else message.copy(parts = kept)
                }
            }
            is Message.User -> {
                if (!message.hasToolResults()) {
                    message
                } else {
                    val kept = message.parts.filter { part ->
                        part !is MessagePart.Tool.Result || part.id in validIds
                    }
                    if (kept.isEmpty()) null else message.copy(parts = kept)
                }
            }
            else -> message
        }

    // ── 不变式 ②：tool_call 块原子裁剪 ─────────────────────────

    /**
     * 将消息列表裁剪到最多 [maxMessages] 条（**System 计入预算**，与 langchain4j 原版一致）。
     *
     * - 总数（含 System）≤ [maxMessages] 时原样返回。
     * - System 计 1 个预算位、始终保留在结果最前；非 System 可用预算 = [maxMessages] - systemSize。
     * - 非 System 消息按"tool 块"分组：一个含 Call 的 Assistant + 紧随其后所有含 Result 的 User
     *   合为一个不可拆块；其余消息各自成块。
     * - 从最新块向前累加，整块放入预算；单块就超可用预算的块**丢弃整块并继续看更旧的块**
     *  （`continue` 语义，与原版一致），保证 tool_calls/Result 永不成对被拆散。
     */
    public fun trimToMaxMessages(
        messages: List<Message>,
        maxMessages: Int = MAX_MESSAGES,
    ): List<Message> {
        if (messages.size <= maxMessages) return messages.toList()

        val systems = messages.filterIsInstance<Message.System>()
        val nonSystem = messages.filterNot { message -> message is Message.System }

        val blocks = groupIntoBlocks(nonSystem)
        val available = maxMessages - if (systems.isNotEmpty()) 1 else 0

        val keptBlocks = mutableListOf<List<Message>>()
        var keptCount = 0
        for (block in blocks.asReversed()) {
            if (block.size > available) continue // 单块就超可用预算，丢弃整块，继续看更旧的块
            if (keptCount + block.size <= available) {
                keptBlocks.add(0, block)
                keptCount += block.size
            } else {
                break // 剩余预算装不下，停
            }
        }
        return systems + keptBlocks.flatten()
    }

    /** 把非系统消息切成原子块（含 Call 的 Assistant + 紧随其后的所有含 Result 的 User 合并）。 */
    private fun groupIntoBlocks(nonSystem: List<Message>): List<List<Message>> {
        val blocks = mutableListOf<MutableList<Message>>()
        var index = 0
        while (index < nonSystem.size) {
            val current = nonSystem[index]
            val block = mutableListOf(current)
            index++
            if (current is Message.Assistant && current.hasToolCalls()) {
                while (index < nonSystem.size) {
                    val next = nonSystem[index]
                    if (next is Message.User && next.hasToolResults()) {
                        block.add(next)
                        index++
                    } else {
                        break
                    }
                }
            }
            blocks.add(block)
        }
        return blocks
    }

    // ── M1：token 估算（US-1.1，确定性启发式，无 tokenizer 依赖）──

    /** M1 默认 token 预算（约对应主流模型窗口 70% 的一半以下；M2 接真实窗口配置）。 */
    public const val DEFAULT_BUDGET_TOKENS: Int = 8_000

    /** 最近 N 个实轮的 tool result 保留原文，更早的替换为占位（US-1.2）。 */
    public const val KEEP_RECENT_TOOL_TURNS: Int = 2

    /** 近 K 个实轮的对话块最后才丢（US-1.4）。 */
    public const val PROTECTED_RECENT_TURNS: Int = 3

    private const val MESSAGE_OVERHEAD_TOKENS: Int = 4
    private const val UNKNOWN_PART_TOKENS: Int = 32
    private const val RESULT_PLACEHOLDER_HEAD_CHARS: Int = 80
    private const val RESULT_AGING_MIN_LENGTH: Int = 120
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * 粗估 token：CJK 字符 1 token/字，其余 4 字符 1 token（向上取整）。仅用于预算裁剪的
     * 确定性相对比较，不追求与任意分词器精确一致。
     */
    public fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.isCjk()) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    /** 估算单条消息 token：固定开销 + 各 part 之和（Text/Call/Result 精估，未知 part 计常量）。 */
    public fun estimateMessageTokens(message: Message): Int =
        MESSAGE_OVERHEAD_TOKENS + when (message) {
            is Message.User -> message.parts.sumOf { part -> estimatePartTokens(part) }
            is Message.Assistant -> message.parts.sumOf { part -> estimatePartTokens(part) }
            else -> estimateTokens(message.textContent())
        }

    private fun estimatePartTokens(part: MessagePart): Int = when (part) {
        is MessagePart.Text -> estimateTokens(part.text)
        is MessagePart.Tool.Call -> estimateTokens(part.tool) + estimateTokens(part.args)
        is MessagePart.Tool.Result -> estimateTokens(part.tool) + estimateTokens(part.output)
        else -> UNKNOWN_PART_TOKENS
    }

    private fun Char.isCjk(): Boolean =
        this in '\u4E00'..'\u9FFF' || this in '\u3000'..'\u303F' || this in '\uFF00'..'\uFFEF'

    // ── M1：tool result 老化（US-1.2）─────────────────────────

    /**
     * 把「非最近 [keepRecentTurns] 个实轮」的 [MessagePart.Tool.Result] 内容替换为单行占位
     * （`[tool:名 → result omitted: 前80字符]`），保住 Call/Result 配对与消息结构。
     *
     * - 实轮边界 = parts 不含 Result 的 User 消息（真实用户输入；纯 Result 的 User 是工具回灌）。
     * - 短 result（≤120 字符）不替换，避免占位反而更长。
     * - 实轮总数 ≤ [keepRecentTurns] 时无老化。
     */
    public fun ageToolResults(
        messages: List<Message>,
        keepRecentTurns: Int = KEEP_RECENT_TOOL_TURNS,
    ): List<Message> {
        if (messages.isEmpty() || keepRecentTurns <= 0) return messages.toList()
        val boundaries = messages.indices.filter { index -> isRealUserTurn(messages[index]) }
        if (boundaries.size <= keepRecentTurns) return messages.toList()
        val protectFrom = boundaries[boundaries.size - keepRecentTurns]
        return messages.mapIndexed { index, message ->
            if (index >= protectFrom) message else ageMessageToolResults(message)
        }
    }

    private fun isRealUserTurn(message: Message): Boolean =
        message is Message.User && message.parts.none { part -> part is MessagePart.Tool.Result }

    private fun ageMessageToolResults(message: Message): Message =
        if (message !is Message.User || !message.hasToolResults()) {
            message
        } else {
            message.copy(
                parts = message.parts.map { part ->
                    if (part is MessagePart.Tool.Result && part.output.length > RESULT_AGING_MIN_LENGTH) {
                        val head = part.output
                            .replace(WHITESPACE_RUN, " ")
                            .take(RESULT_PLACEHOLDER_HEAD_CHARS)
                        part.copy(parts = listOf(MessagePart.Text("[tool:${part.tool} → result omitted: $head]")))
                    } else {
                        part
                    }
                }
            )
        }

    // ── M1：token 预算裁剪（US-1.1/1.4）──────────────────────

    /**
     * 按 token 预算从旧到新丢块（复用不变式②的原子块分组，tool 块永不拆散）。丢弃优先级：
     *
     * 1. 非保护区的 tool 块（旧工具输出最先牺牲）
     * 2. 保护区的 tool 块（工具输出让位于对话）
     * 3. 非保护区的对话块
     * 4. 保护区的对话块（近 [protectedRecentTurns] 个实轮，最后才丢）
     *
     * - System 计入预算、始终保留最前（与 [trimToMaxMessages] 一致）。
     * - **最新块永不丢**（用户最新输入必须到达模型），即使单块超预算。
     * - 实轮边界定义同 [ageToolResults]。
     */
    public fun trimToTokenBudget(
        messages: List<Message>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        protectedRecentTurns: Int = PROTECTED_RECENT_TURNS,
    ): List<Message> {
        if (messages.isEmpty()) return messages
        val systems = messages.filterIsInstance<Message.System>()
        val nonSystem = messages.filterNot { message -> message is Message.System }
        if (nonSystem.isEmpty()) return systems

        val blocks = groupIntoBlocks(nonSystem)
        val blockStarts = ArrayList<Int>(blocks.size)
        var acc = 0
        for (block in blocks) {
            blockStarts.add(acc)
            acc += block.size
        }
        val boundaries = nonSystem.indices.filter { index -> isRealUserTurn(nonSystem[index]) }
        val protectFrom =
            if (boundaries.size > protectedRecentTurns) boundaries[boundaries.size - protectedRecentTurns] else 0

        val systemTokens = systems.sumOf { system -> estimateMessageTokens(system) }
        val kept = blocks.mapIndexed { index, block -> blockStarts[index] to block }.toMutableList()

        fun isProtectedBlock(start: Int): Boolean = start >= protectFrom
        fun hasToolPart(block: List<Message>): Boolean = block.any { message ->
            (message is Message.Assistant && message.hasToolCalls()) ||
                (message is Message.User && message.hasToolResults())
        }
        fun totalTokens(): Int =
            systemTokens + kept.sumOf { (_, block) -> block.sumOf { estimateMessageTokens(it) } }

        // 逐级牺牲：非保护 tool → 保护 tool → 非保护对话 → 保护对话（最新块除外）
        val passes = listOf(
            { start: Int, block: List<Message> -> !isProtectedBlock(start) && hasToolPart(block) },
            { start: Int, block: List<Message> -> isProtectedBlock(start) && hasToolPart(block) },
            { start: Int, _: List<Message> -> !isProtectedBlock(start) },
            { _: Int, _: List<Message> -> true },
        )
        for (droppable in passes) {
            while (totalTokens() > budgetTokens) {
                val idx = kept.indexOfFirst { (start, block) -> droppable(start, block) }
                if (idx == -1) break
                if (kept.size == 1) break // 最新块永不丢
                kept.removeAt(idx)
            }
        }
        return systems + kept.map { (_, block) -> block }.flatten()
    }

    // ── M1：持久化组装全管线（US-1.3）────────────────────────

    /**
     * 双端 store save 的唯一组装入口：剔 System（①）→ 老化旧 tool result（US-1.2）
     * → token 预算裁剪（US-1.1/1.4）→ 条数硬上限兜底（不变式②原语义，防估算失准）。
     * 产物满足三不变式（tool 块原子、配对完整）。
     */
    public fun assembleForPersistence(
        messages: List<Message>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        keepRecentToolTurns: Int = KEEP_RECENT_TOOL_TURNS,
        protectedRecentTurns: Int = PROTECTED_RECENT_TURNS,
    ): List<Message> = trimToMaxMessages(
        trimToTokenBudget(
            ageToolResults(withoutSystemMessages(messages), keepRecentToolTurns),
            budgetTokens,
            protectedRecentTurns,
        )
    )

    // ── part 探测辅助 ──────────────────────────────────────────


    private fun Message.Assistant.hasToolCalls(): Boolean =
        parts.any { part -> part is MessagePart.Tool.Call }

    private fun Message.User.hasToolResults(): Boolean =
        parts.any { part -> part is MessagePart.Tool.Result }
}
