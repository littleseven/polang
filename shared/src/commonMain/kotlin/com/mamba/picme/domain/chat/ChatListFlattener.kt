package com.mamba.picme.domain.chat

/**
 * Chat 列表拍平器（ADR-016 M4，spec §7.2 段拍平与列表粒度 + §7.3 Turn 聚合元数据）。
 *
 * 把消息列表拍平为 LazyColumn 独立 item 序列（每个 MessagePart = 一个 item），
 * 渲染源自 legacy 消息字段切换为 parts。纯函数，commonMain 可测。
 *
 * 拍平规则：
 * - **key** = `"${messageId}:${partId}"`（块级稳定锚，分轨命名空间见 [MessagePart.partId]）；
 * - **USER 消息整颗单 item**（§5 图文同气泡不拆）；agent 消息按 part 拍平；
 * - **整消息 legacy 渲染**（part = null）：claude 气泡 / COMMAND / PLAN_PREVIEW /
 *   AGENT_IMAGE / AGENT_EDIT_RESULT（message 形渲染器）与 parts 为空的瞬态消息
 *   （流式占位「思考中」）；
 * - **流式消息卡 part 三分流**（spec §4 占位契约 + M2 双轨口径）：已填充
 *   （OUTPUT_AVAILABLE）的 Chart/HtmlCard 在其**产物行已在列表中**时跳过——
 *   产物落库为独立消息行渲染，双显禁止；产物行未达的窗口期（Room invalidation
 *   异步，M4 review 🟡3）占位 part 继续按 OUTPUT_AVAILABLE 负载原位渲染，防卡片
 *   闪失与位置跳变；未完成（INPUT_STREAMING/INPUT_AVAILABLE）渲染占位 item；
 *   失败（OUTPUT_ERROR，瞬态轨不落库）渲染失败 item；
 * - **流式开放文本块**（Text + STREAMING）：内容以消息 content（节奏器 paced 输出）为准
 *   （[textOverride]），光标随 [ChatMessage.showCursor]——50ms 打字机节拍不因拍平丢失；
 * - **Turn 聚合元数据**：USER 消息开启新 turn（[turnIndex]/[isTurnStart]）；同 turn 相邻
 *   agent 文本 item 标 [mergeWithPrevious]（段落间距收窄的视觉依据，纯视觉层不引嵌套容器）。
 *
 * [pendingToolName]：流式 turn 内进行中的**非卡片**工具名（无占位 part 承载——卡片工具
 * 有类型化占位 part），非空时在流式消息 items 尾部合成一个工具状态 item。
 */
fun flattenChatItems(
    messages: List<ChatMessage>,
    pendingToolName: String? = null,
): List<ChatListItem> {
    val items = ArrayList<ChatListItem>(messages.size * 2)
    // 已落库产物行的负载集（🟡3）：chart/html 独立消息行的 content 与 part 负载同源同值
    // （emitChartMessage/emitHtmlCardMessage 同一变量写两边），跳过已填充卡 part
    // 以「产物行已在列表中」为前提
    val persistedChartPayloads = HashSet<String>()
    val persistedHtmlPayloads = HashSet<String>()
    messages.forEach { message ->
        when (message.type) {
            ChatMessageType.CHART -> persistedChartPayloads += message.content
            ChatMessageType.HTML_CARD -> persistedHtmlPayloads += message.content
            else -> Unit
        }
    }
    var nextTurn = 0
    var currentTurn = -1
    messages.forEach { message ->
        // turn 边界：USER 消息开启新 turn；会话首条若为 AGENT 消息独立成 turn（无开启者）
        if (message.modelRole == ModelInputRole.USER || currentTurn < 0) {
            currentTurn = nextTurn++
        }
        val turn = currentTurn
        val turnStart = items.isEmpty() || items.last().turnIndex != turn
        flattenMessage(message, turn, turnStart, persistedChartPayloads, persistedHtmlPayloads, items)
    }
    // 同 turn 相邻 agent 文本合并标记（段落间距收窄）：后一 item 与前一 item 同为 agent
    // 文本且同 turn 即并入连续文本流（覆盖消息内跨轮文本与消息间相邻文本两种形态）
    for (index in 1 until items.size) {
        val previous = items[index - 1]
        val current = items[index]
        if (current.contentType == ChatListItem.TYPE_AGENT_TEXT &&
            previous.contentType == ChatListItem.TYPE_AGENT_TEXT &&
            previous.turnIndex == current.turnIndex
        ) {
            items[index] = current.copy(mergeWithPrevious = true)
        }
    }
    // 非卡片工具进行中的合成状态 item（挂在流式消息所在 turn 尾部）
    if (pendingToolName != null) {
        val streaming = messages.lastOrNull { it.isStreaming }
        if (streaming != null) {
            items += ChatListItem(
                key = "${streaming.id}:tool_status",
                contentType = ChatListItem.TYPE_TOOL_STATUS,
                message = streaming,
                part = null,
                turnIndex = items.lastOrNull()?.turnIndex ?: 0,
                isTurnStart = false,
                isLastPartOfMessage = true,
                pendingToolName = pendingToolName,
            )
        }
    }
    return items
}

private fun flattenMessage(
    message: ChatMessage,
    turn: Int,
    turnStart: Boolean,
    persistedChartPayloads: Set<String>,
    persistedHtmlPayloads: Set<String>,
    out: MutableList<ChatListItem>,
) {
    // USER 消息与 legacy 整消息渲染类型：单 item（part = null，渲染器读 legacy 字段）
    if (message.modelRole == ModelInputRole.USER || message.rendersAsWholeMessage()) {
        out += ChatListItem(
            key = wholeMessageKey(message),
            contentType = if (message.modelRole == ModelInputRole.USER) {
                ChatListItem.TYPE_USER_MESSAGE
            } else {
                ChatListItem.TYPE_LEGACY_MESSAGE
            },
            message = message,
            part = null,
            turnIndex = turn,
            isTurnStart = turnStart,
            isLastPartOfMessage = true,
        )
        return
    }
    // agent 消息按 part 拍平（跳过产物行已渲染的已填充卡 part）
    val visible = message.parts.filterNot { part ->
        part.isPersistedStreamingOutput(message, persistedChartPayloads, persistedHtmlPayloads)
    }
    visible.forEachIndexed { index, part ->
        out += ChatListItem(
            key = "${message.id}:${part.partId}",
            contentType = contentTypeOf(message, part),
            message = message,
            part = part,
            turnIndex = turn,
            isTurnStart = turnStart && index == 0,
            isLastPartOfMessage = index == visible.lastIndex,
            textOverride = streamingTextOverride(message, part),
            showCursor = message.isStreaming && message.showCursor &&
                part is MessagePart.Text && part.state == PartState.STREAMING,
        )
    }
    // 全部 part 被跳过（如 turn 仅产出一张已落库卡）：不留空消息位
}

/** 整消息 legacy 渲染的判定（message 形渲染器 / 瞬态占位无 parts）。 */
private fun ChatMessage.rendersAsWholeMessage(): Boolean =
    parts.isEmpty() ||
        claudeAgent != null ||
        type == ChatMessageType.COMMAND ||
        type == ChatMessageType.PLAN_PREVIEW ||
        type == ChatMessageType.AGENT_IMAGE ||
        type == ChatMessageType.AGENT_EDIT_RESULT

private fun wholeMessageKey(message: ChatMessage): String =
    "${message.id}:${message.parts.firstOrNull()?.partId ?: "whole"}"

/**
 * 流式消息中已填充落库、且**产物行已在列表中**的卡 part（双显禁止：独立
 * CHART/HTML_CARD 消息行在列表中渲染）。
 *
 * M4 review 🟡3：跳过必须以产物行在场为前提——`insertMessageWithParts` 的 Room
 * invalidation 是异步的，`feedToolOutput` 同步填满占位 part 后到产物行到达
 * `_messages` 之间有数十~数百 ms 窗口；仅按 OUTPUT_AVAILABLE 跳过会让卡片窗口期
 * 消失、随后又在列表尾部出现（闪失 + 位置跳变）。匹配按负载等值（emit 时 Room
 * content 与 part 负载同源同值），精确锚定本 turn 的产物行——旧 turn 的历史卡行
 * 负载不同，不会误判提前跳过。
 */
private fun MessagePart.isPersistedStreamingOutput(
    message: ChatMessage,
    persistedChartPayloads: Set<String>,
    persistedHtmlPayloads: Set<String>,
): Boolean {
    if (!message.isStreaming || toolStateOrNull() != ToolPartState.OUTPUT_AVAILABLE) return false
    return when (this) {
        is MessagePart.Chart -> svg in persistedChartPayloads
        is MessagePart.HtmlCard -> html in persistedHtmlPayloads
        else -> false
    }
}

private fun MessagePart.toolStateOrNull(): ToolPartState? = when (this) {
    is MessagePart.Chart -> state
    is MessagePart.HtmlCard -> state
    is MessagePart.TaskCard -> state
    else -> null
}

/** 流式开放文本块以 paced content 渲染（节奏器打字机节拍保留）；其余读 part 自身负载。 */
private fun streamingTextOverride(message: ChatMessage, part: MessagePart): String? =
    if (message.isStreaming && part is MessagePart.Text && part.state == PartState.STREAMING) {
        message.content
    } else {
        null
    }

private fun contentTypeOf(message: ChatMessage, part: MessagePart): String = when (part) {
    is MessagePart.Text -> ChatListItem.TYPE_AGENT_TEXT
    is MessagePart.Chart -> cardContentType(part.state, ChatListItem.TYPE_CHART)
    is MessagePart.HtmlCard -> cardContentType(part.state, ChatListItem.TYPE_HTML_CARD)
    is MessagePart.TaskCard -> ChatListItem.TYPE_TASK_CARD
    is MessagePart.MediaResults -> ChatListItem.TYPE_MEDIA_RESULTS
    is MessagePart.OptimizeCandidates -> ChatListItem.TYPE_OPTIMIZE_CANDIDATES
    // Image/EditResult 按消息类型已整消息路由，到不了这里；防御性落 legacy 整消息
    is MessagePart.Image, is MessagePart.EditResult -> ChatListItem.TYPE_LEGACY_MESSAGE
}

private fun cardContentType(state: ToolPartState, doneType: String): String = when (state) {
    ToolPartState.OUTPUT_AVAILABLE -> doneType
    ToolPartState.OUTPUT_ERROR -> ChatListItem.TYPE_TOOL_ERROR
    else -> ChatListItem.TYPE_TOOL_PLACEHOLDER
}

/**
 * 拍平后的列表 item（[contentType] 即 LazyColumn contentType 复用桶兼种类鉴别）。
 *
 * [part] = null 表示整消息 legacy 渲染（渲染器读 [message] 的 legacy 字段）；
 * 非 null 时渲染器以 part 负载为数据源（spec §7.4：卡片组件原样复用、仅换数据源）。
 */
data class ChatListItem(
    val key: String,
    val contentType: String,
    val message: ChatMessage,
    val part: MessagePart?,
    val turnIndex: Int,
    /** turn 首个 item（turn 间回合分隔的依据，chat.yaml §3.1）。 */
    val isTurnStart: Boolean,
    /** 消息内最后一个可见 item（性能行等消息级尾件的挂载点）。 */
    val isLastPartOfMessage: Boolean,
    /** 同 turn 相邻 agent 文本流合并（段落间距收窄，chat.yaml §3.1 within_turn）。 */
    val mergeWithPrevious: Boolean = false,
    /** 流式开放文本块的 paced 渲染内容（非空时覆盖 part.markdown）。 */
    val textOverride: String? = null,
    val showCursor: Boolean = false,
    /** 仅 [TYPE_TOOL_STATUS] 合成 item：进行中的工具名。 */
    val pendingToolName: String? = null,
) {
    companion object {
        const val TYPE_USER_MESSAGE = "user_message"
        const val TYPE_LEGACY_MESSAGE = "legacy_message"
        const val TYPE_AGENT_TEXT = "agent_text"
        const val TYPE_CHART = "chart"
        const val TYPE_HTML_CARD = "html_card"
        const val TYPE_TASK_CARD = "task_card"
        const val TYPE_MEDIA_RESULTS = "media_results"
        const val TYPE_OPTIMIZE_CANDIDATES = "optimize_candidates"

        /** 卡片工具占位（INPUT_STREAMING/INPUT_AVAILABLE，spec §4 占位契约）。 */
        const val TYPE_TOOL_PLACEHOLDER = "tool_placeholder"

        /** 卡片工具失败（OUTPUT_ERROR 瞬态轨，spec §5.3 错误进文档）。 */
        const val TYPE_TOOL_ERROR = "tool_error"

        /** 非卡片工具进行中的合成状态 item。 */
        const val TYPE_TOOL_STATUS = "tool_status"
    }
}
