package com.mamba.picme.features.chat.engineer

import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import org.json.JSONObject

/**
 * 工程师任务卡状态机（spec §5 测试决策首层接缝；纯逻辑，对齐 ClaudeSseParser 先例）。
 * 文本 delta（AssistantText）不驱动卡片——避免每个 token 触发 Room upsert；
 * 结构性事件（isStructural=true）才由调用方持久化。
 */
object EngineerTaskReducer {

    private const val MAX_STAGES = 20
    private const val SUMMARY_MAX = 80

    fun initial(taskId: String, sourceText: String, nowMs: Long): EngineerTaskState =
        EngineerTaskState(taskId = taskId, sourceText = sourceText, startedAtMs = nowMs, updatedAtMs = nowMs)

    fun isStructural(event: ClaudeEvent): Boolean = event !is ClaudeEvent.AssistantText

    fun reduce(
        state: EngineerTaskState,
        event: ClaudeEvent,
        canDeliver: Boolean,
        nowMs: Long,
    ): EngineerTaskState = when (event) {
        is ClaudeEvent.Session -> state.copy(sid = event.sid, updatedAtMs = nowMs)
        is ClaudeEvent.AssistantText -> state
        is ClaudeEvent.ToolUse -> state.pushStage(stageLabel(event.tool, event.input), nowMs)
        is ClaudeEvent.AppToolRequest -> state.pushStage(event.tool, nowMs)
        is ClaudeEvent.ToolResult -> state
        is ClaudeEvent.FileChange ->
            state.copy(fileChangeCount = state.fileChangeCount + 1)
                .pushStage("file_change:${event.path.substringAfterLast('/')}", nowMs)
        is ClaudeEvent.Cost -> state.copy(turns = event.turns, costCents = event.cents, updatedAtMs = nowMs)
        is ClaudeEvent.Error ->
            if (event.truncated) {
                state.copy(
                    status = EngineerTaskStatus.AWAITING_CONTINUE,
                    truncatedReason = event.reason,
                    updatedAtMs = nowMs,
                )
            } else {
                state.copy(status = EngineerTaskStatus.FAILED, errorSummary = event.message, updatedAtMs = nowMs)
            }
        is ClaudeEvent.Done -> {
            val withTurns = if (event.turns > 0) state.copy(turns = event.turns) else state
            when {
                event.truncated -> withTurns.copy(
                    status = EngineerTaskStatus.AWAITING_CONTINUE,
                    truncatedReason = event.reason,
                    updatedAtMs = nowMs,
                )
                withTurns.fileChangeCount > 0 && canDeliver ->
                    withTurns.copy(status = EngineerTaskStatus.AWAITING_DELIVER, updatedAtMs = nowMs)
                else -> withTurns.copy(status = EngineerTaskStatus.COMPLETED, updatedAtMs = nowMs)
            }
        }
    }

    /** 审批动作回填（US-9）：terminal 化 + resolution 粘滞，同一决策点只审批一次。 */
    fun resolved(
        state: EngineerTaskState,
        resolution: EngineerTaskResolution,
        nowMs: Long,
        deliverBranch: String? = null,
    ): EngineerTaskState {
        if (state.resolution != null) return state
        return state.copy(
            status = EngineerTaskStatus.COMPLETED,
            resolution = resolution,
            deliverBranch = deliverBranch,
            updatedAtMs = nowMs,
        )
    }

    /** 完成态结果摘要：末段首个非空行，截断 80 字（US-10 末段回复首行；delta 为全回合累积，末段才是结论）。 */
    fun summarize(text: String): String =
        text.split(Regex("\\n\\s*\\n")).lastOrNull { paragraph -> paragraph.isNotBlank() }
            ?.lines()?.firstOrNull { line -> line.isNotBlank() }?.trim()?.take(SUMMARY_MAX) ?: ""

    private fun EngineerTaskState.pushStage(label: String, nowMs: Long): EngineerTaskState =
        copy(stage = label, recentStages = (recentStages + label).takeLast(MAX_STAGES), updatedAtMs = nowMs)

    /**
     * 阶段标签：工具名 + 关键参数细节（design expanded 最近事件「Read  Xxx.kt」「Bash  ./gradlew …」）。
     * 细节取自工具入参（文件路径取 basename，命令/模式原样截断），与工具名以两空格分隔——
     * 卡片模板 [parseStage] 按此格式解析（工具 token + 细节），勿改分隔约定。
     */
    internal fun stageLabel(tool: String, input: JSONObject): String {
        val detail = when (tool) {
            "Read", "Edit", "Write", "NotebookEdit" ->
                input.optString("file_path").substringAfterLast('/')
            "Bash" -> input.optString("command")
            "Grep", "Glob" -> input.optString("pattern")
            "WebFetch" -> input.optString("url")
            "WebSearch" -> input.optString("query")
            "Task" -> input.optString("description")
            else -> ""
        }.replace(Regex("\\s+"), " ").trim().take(DETAIL_MAX)
        return if (detail.isEmpty()) tool else "$tool  $detail"
    }

    private const val DETAIL_MAX = 80
}
