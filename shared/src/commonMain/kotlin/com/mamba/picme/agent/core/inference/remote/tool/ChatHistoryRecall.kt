package com.mamba.picme.agent.core.inference.remote.tool

/**
 * recall_chat_history 工具的纯逻辑 SSOT（参数钳制 + observation 组装，2026-11-12）。
 *
 * 检索语义：当前会话 Room 长期轨（chat_messages）`type='text'` 投影行的 content LIKE
 * 匹配（无 query 按时间倒序取最近 N 条），返回**硬编码中文 observation**——只进模型
 * 不进用户气泡（I18N 不变量 3 既有口径，与其它工具的「找到 N 张…」模板同族）。
 * 不产任何 UI 卡。
 *
 * 数据取数在 androidApp `ChatHistoryRecallCapability`（ChatMessageDao 查询），本对象
 * 只做无副作用纯函数，供 Capability 与 commonTest 同源消费。
 */
object ChatHistoryRecall {

    /** @Tool 参数建议值（LLM 描述引导；实际值仍经 [clampLimit] 钳制）。 */
    const val DEFAULT_LIMIT = 20

    /** 返回条数硬上限。 */
    const val MAX_LIMIT = 50

    /** 单条历史行截断字符数（防超长消息撑爆 observation）。 */
    const val PER_LINE_MAX_CHARS = 200

    /** observation 总量截断字符数（防 prompt 膨胀）。 */
    const val TOTAL_MAX_CHARS = 4_000

    /**
     * 召回的历史文本行（Capability 自 DAO 行映射：role 取 Room `role` 列
     * （"user"/"agent"），content 取 `type='text'` 投影列）。
     */
    data class HistoryLine(val role: String, val content: String, val timestamp: Long)

    /** limit 钳制：1..[MAX_LIMIT]（非正数/超界收敛到边界，LLM 传参不可信）。 */
    fun clampLimit(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

    /**
     * 组装 observation。入参 [lines] 须已是**时间正序**（DAO 取 DESC 后由调用方反转）。
     *
     * 格式：`[历史召回] 共 N 条（按时间正序）：` + 每行 `role: content`；单行超
     * [PER_LINE_MAX_CHARS] 截断加省略号；总量超 [TOTAL_MAX_CHARS] 时**从最旧一侧整行
     * 丢弃**（保最近段）并加截断标记。空结果返回「共 0 条」引导语（模型据此如实告知）。
     */
    fun formatObservation(lines: List<HistoryLine>): String {
        if (lines.isEmpty()) {
            return "[历史召回] 共 0 条——当前会话没有匹配的文本历史"
        }
        val rendered = lines.map { line ->
            val text = line.content.trim()
                .replace('\n', ' ')
                .let { if (it.length > PER_LINE_MAX_CHARS) it.take(PER_LINE_MAX_CHARS) + "…" else it }
            "${line.role}: $text"
        }
        // 总量预算：从最旧一侧整行丢弃（O(n) 前缀累减），保最近段
        var begin = 0
        var total = rendered.sumOf { it.length + 1 }
        while (begin < rendered.lastIndex && total > TOTAL_MAX_CHARS) {
            total -= rendered[begin].length + 1
            begin++
        }
        val truncPrefix = if (begin > 0) "…<较早 $begin 条已截断>\n" else ""
        return "[历史召回] 共 ${lines.size} 条（按时间正序）：\n" +
            truncPrefix +
            rendered.subList(begin, rendered.size).joinToString("\n")
    }
}
