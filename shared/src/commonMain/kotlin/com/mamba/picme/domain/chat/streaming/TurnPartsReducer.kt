package com.mamba.picme.domain.chat.streaming

import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.ToolPartState

/**
 * Turn parts 快照装配器（ADR-016 M2，spec §4）：把 [TurnStreamEvent] 事件流拼装为
 * 当前 turn 的有序 parts 快照。
 *
 * 语义要点：
 * - **同帧顺序 = 到达顺序**：「文本+tool_calls 同帧」按事件到达序落 part（沿用
 *   poLangSingleRunStrategy 修复后的事件序），reducer 只做 append / 同 id 原位覆写；
 * - **三分区排序立法（值域终态 spec §6.1/§6.2）**：reasoning 恒插主区（text/tool/
 *   data）首 part 之前、source 恒在尾部 source 区——无论到达序如何，终态 parts
 *   恒为 `[reasoning…][主区…][source…]`（reasoning 折叠块在正文上方、chips 在正文
 *   下方）；主区内部保持到达序；
 * - **占位契约**：[TurnStreamEvent.ToolInputStart] 即插入占位 part（[TOOL_DRAW_CHART] /
 *   [TOOL_RENDER_HTML] 有类型化占位，其余工具不产卡片只占位登记），
 *   [TurnStreamEvent.ToolOutputAvailable] 原位填充（按 partId == toolCallId 锚定——
 *   三分区插入会使 Int 下标漂移，partId 锚不受位移影响），不挪动块序；
 * - **错误进文档**（spec §5.3）：[TurnStreamEvent.ToolOutputError] 把占位 part 标
 *   [ToolPartState.OUTPUT_ERROR]，errorText 入 [toolErrors]（瞬态；持久化错误以
 *   TaskCard.errorSummary 为准，经 M1 双写落 Room）；
 * - **DONE 不可变**（spec §3）：[PartState.DONE] 的 Text/Reasoning 块不再接受 Delta
 *   （忽略并视为适配层 bug 信号）；占位 part 的原位覆写是 spec 显式豁免。
 *
 * 纯 Kotlin 状态机，**单线程契约**：全部 [apply]/[reset]/[oldestPendingToolCallId] 访问
 * 由调用方漏斗到同一线程（androidApp ChatViewModel 收口在 Main.immediate——onEvent 实际
 * 运行在 orchestrator 调度器、能力执行点在 Dispatchers.Default，均先切 Main 再触本类；
 * 线程约束注释与 [StreamingPacingController] 一致，但实现方式是「调用方收口」而非本类自检）。
 */
class TurnPartsReducer {

    /** 当前 turn 的 parts 快照（每次 [apply] 后整体替换，List 不可变拷贝收口）。 */
    var parts: List<MessagePart> = emptyList()
        private set

    /** toolCallId → 错误文案（OUTPUT_ERROR 瞬态记录，供状态文案/调试）。 */
    val toolErrors: Map<String, String>
        get() = _toolErrors.toMap()

    private val _toolErrors = mutableMapOf<String, String>()

    /** toolCallId → toolName（全部 in-flight 工具登记，含无占位卡片的工具）。 */
    private val toolNames = mutableMapOf<String, String>()

    /** toolCallId → args 累积（ToolInputDelta/Available；M2 不渲染，存档供 M3+）。 */
    private val toolArgs = mutableMapOf<String, String>()

    /** 已完结（output/error 已到）的 toolCallId。 */
    private val completedToolCalls = mutableSetOf<String>()

    /** source part 序号（partId `src-N` 统一重编，跨多次 web_search 调用唯一）。 */
    private var sourcePartSeq = 0

    /** 新 turn 开始前清空（turn 边界由调用方判定：一次 sendMessage = 一个 turn）。 */
    fun reset() {
        parts = emptyList()
        _toolErrors.clear()
        toolNames.clear()
        toolArgs.clear()
        completedToolCalls.clear()
        sourcePartSeq = 0
    }

    /** 消费一个事件并返回最新 parts 快照。 */
    fun apply(event: TurnStreamEvent): List<MessagePart> {
        when (event) {
            // 轮边界不消费块状态：未闭合文本块的闭合已由 adapter 翻译为 TextEnd（M4 显式
            // 信号，spec §10）；round 序号仅作事件流自描述/审计，reducer 无轮级语义
            is TurnStreamEvent.RoundStarted -> Unit
            is TurnStreamEvent.TextStart -> textStart(event.partId)
            is TurnStreamEvent.TextDelta -> textDelta(event.partId, event.delta)
            is TurnStreamEvent.TextEnd -> textEnd(event.partId)
            is TurnStreamEvent.ReasoningStart -> reasoningStart(event.partId)
            is TurnStreamEvent.ReasoningDelta -> reasoningDelta(event.partId, event.delta)
            is TurnStreamEvent.ReasoningEnd -> reasoningEnd(event.partId)
            is TurnStreamEvent.SourcesAvailable -> sourcesAvailable(event.sources)
            is TurnStreamEvent.ToolInputStart -> toolInputStart(event.toolCallId, event.toolName)
            is TurnStreamEvent.ToolInputDelta ->
                toolArgs[event.toolCallId] = (toolArgs[event.toolCallId] ?: "") + event.argsDelta
            is TurnStreamEvent.ToolInputAvailable -> toolInputAvailable(event.toolCallId, event.args)
            is TurnStreamEvent.ToolOutputAvailable -> toolOutput(event.toolCallId, event.output)
            is TurnStreamEvent.ToolOutputError -> toolError(event.toolCallId, event.errorText)
        }
        return parts
    }

    /** 最老的未完成 toolCallId（[toolName] 非空时按名过滤）；无则 null。供产物回填关联。 */
    fun oldestPendingToolCallId(toolName: String? = null): String? =
        toolNames.entries.firstOrNull { (id, name) ->
            id !in completedToolCalls && (toolName == null || name == toolName)
        }?.key

    // ── 文本块 ────────────────────────────────────────────────

    private fun textStart(partId: String) {
        if (parts.any { it.partId == partId }) return // 幂等：重复 Start 不复制块
        // 主区插入位（source 区恒尾部）：尾部 source run 之前——source 先到、text 后到的
        // 异常序自愈为「正文在 chips 上方」（spec §6.2）；无 source（现行全部路径）= 尾部
        insertAt(mainZoneInsertIndex, MessagePart.Text(partId, markdown = "", state = PartState.STREAMING))
    }

    private fun textDelta(partId: String, delta: String) {
        val index = parts.indexOfFirst { it.partId == partId }
        val existing = parts.getOrNull(index) as? MessagePart.Text
        if (existing == null) {
            // 鲁棒性：Delta 先于 Start 到达（适配层简化路径）自动补 Start
            textStart(partId)
            textDelta(partId, delta)
            return
        }
        if (existing.state == PartState.DONE) return // DONE 不可变（spec §3）
        replaceAt(index, existing.copy(markdown = existing.markdown + delta))
    }

    private fun textEnd(partId: String) {
        val index = parts.indexOfFirst { it.partId == partId }
        val existing = parts.getOrNull(index) as? MessagePart.Text ?: return
        if (existing.state == PartState.DONE) return
        replaceAt(index, existing.copy(state = PartState.DONE))
    }

    // ── 思考链块（值域终态 spec §6.1；状态机语义与文本块同构） ──

    private fun reasoningStart(partId: String) {
        if (parts.any { it.partId == partId }) return // 幂等
        // reasoning 区插入位：主区首 part 前——无论到达序如何恒排在 text 前（spec §6.1
        // 排序立法）；纯 reasoning/source（无主区 part）落 source 区之前
        insertAt(reasoningZoneInsertIndex, MessagePart.Reasoning(partId, text = "", state = PartState.STREAMING))
    }

    private fun reasoningDelta(partId: String, delta: String) {
        val index = parts.indexOfFirst { it.partId == partId }
        val existing = parts.getOrNull(index) as? MessagePart.Reasoning
        if (existing == null) {
            reasoningStart(partId)
            reasoningDelta(partId, delta)
            return
        }
        if (existing.state == PartState.DONE) return // DONE 不可变（spec §3）
        replaceAt(index, existing.copy(text = existing.text + delta))
    }

    private fun reasoningEnd(partId: String) {
        val index = parts.indexOfFirst { it.partId == partId }
        val existing = parts.getOrNull(index) as? MessagePart.Reasoning ?: return
        if (existing.state == PartState.DONE) return
        replaceAt(index, existing.copy(state = PartState.DONE))
    }

    // ── source 区（值域终态 spec §6.2：chips 恒在正文下方） ──

    private fun sourcesAvailable(sources: List<MessagePart.SourceUrl>) {
        if (sources.isEmpty()) return
        // partId 统一重编（发射方不保证唯一，LazyColumn key = messageId:partId 需全局唯一）
        parts = parts + sources.map { it.copy(partId = "src-${sourcePartSeq++}") }
    }

    // ── 工具块 ────────────────────────────────────────────────

    private fun toolInputStart(toolCallId: String, toolName: String) {
        if (toolNames.containsKey(toolCallId)) return // 幂等
        toolNames[toolCallId] = toolName
        val placeholder = placeholderPart(toolCallId, toolName) ?: return // 无卡片工具只占位登记
        insertAt(mainZoneInsertIndex, placeholder)
    }

    private fun toolInputAvailable(toolCallId: String, args: String) {
        toolArgs[toolCallId] = args
        updateToolPlaceholder(toolCallId) { part -> part.withToolState(ToolPartState.INPUT_AVAILABLE) }
    }

    private fun toolOutput(toolCallId: String, output: MessagePart) {
        completedToolCalls += toolCallId
        val index = parts.indexOfFirst { it.partId == toolCallId }
        if (index >= 0) {
            // 原位填充：占位 partId == toolCallId 锚定（三分区插入会使 Int 下标漂移，
            // partId 锚不受影响），产物 partId 以调用方契约为准
            replaceAt(index, output)
        } else {
            // 无位置锚：非卡片工具不产占位（产物经调用方 oldestPendingToolCallId 降级关联到
            // 该 toolCallId，如 run_gallery_script 直出图卡）——按到达顺序落主区尾部
            //（source 区之前）
            insertAt(mainZoneInsertIndex, output)
        }
    }

    private fun toolError(toolCallId: String, errorText: String) {
        completedToolCalls += toolCallId
        _toolErrors[toolCallId] = errorText
        updateToolPlaceholder(toolCallId) { part -> part.withToolState(ToolPartState.OUTPUT_ERROR) }
    }

    /** 占位 part 工厂：卡片类工具（图卡/HTML 卡）有类型化占位；其余工具不产 part。 */
    private fun placeholderPart(toolCallId: String, toolName: String): MessagePart? = when (toolName) {
        TOOL_DRAW_CHART ->
            MessagePart.Chart(partId = toolCallId, svg = "", state = ToolPartState.INPUT_STREAMING)
        TOOL_RENDER_HTML ->
            MessagePart.HtmlCard(partId = toolCallId, html = "", state = ToolPartState.INPUT_STREAMING)
        TOOL_BROWSER_OPEN ->
            MessagePart.BrowserLive(partId = toolCallId, sessionId = "", state = ToolPartState.INPUT_STREAMING)
        else -> null
    }

    private inline fun updateToolPlaceholder(toolCallId: String, transform: (MessagePart) -> MessagePart) {
        val index = parts.indexOfFirst { it.partId == toolCallId }
        if (index < 0) return
        replaceAt(index, transform(parts[index]))
    }

    private fun MessagePart.withToolState(state: ToolPartState): MessagePart = when (this) {
        is MessagePart.Chart -> copy(state = state)
        is MessagePart.HtmlCard -> copy(state = state)
        is MessagePart.TaskCard -> copy(state = state)
        is MessagePart.BrowserLive -> copy(state = state)
        else -> this // Text / Reasoning / source / data part 无工具状态机，不动
    }

    // ── 三分区插入位（值域终态排序立法） ──────────────────────

    private fun MessagePart.isSourcePart(): Boolean =
        this is MessagePart.SourceUrl || this is MessagePart.SourceDocument

    /** 主区（text/tool/data）插入位：尾部连续 source run 之前（source 区恒尾部）。 */
    private val mainZoneInsertIndex: Int
        get() = parts.indexOfLast { !it.isSourcePart() } + 1

    /** reasoning 区插入位：首个主区 part 之前；无主区 part（纯 reasoning/source）→ 主区插入位。 */
    private val reasoningZoneInsertIndex: Int
        get() = parts.indexOfFirst { !(it is MessagePart.Reasoning || it.isSourcePart()) }
            .takeIf { it >= 0 } ?: mainZoneInsertIndex

    private fun insertAt(index: Int, part: MessagePart) {
        parts = parts.toMutableList().also { it.add(index, part) }
    }

    private fun replaceAt(index: Int, part: MessagePart) {
        parts = parts.toMutableList().also { it[index] = part }
    }

    companion object {
        const val TOOL_DRAW_CHART = "draw_chart"
        const val TOOL_RENDER_HTML = "render_html"

        /** 云端浏览器会话开启工具（有类型化占位：BrowserLive 直播卡）；其余 browser_* 动作工具不落占位。 */
        const val TOOL_BROWSER_OPEN = "browser_open"

        /** 脚本沙箱工具（无类型化占位，只占位登记；直出图卡/HTML 卡经降级关联 append）。 */
        const val TOOL_RUN_GALLERY_SCRIPT = "run_gallery_script"
    }
}
