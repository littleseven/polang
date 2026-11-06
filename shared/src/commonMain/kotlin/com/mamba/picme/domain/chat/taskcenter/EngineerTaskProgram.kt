package com.mamba.picme.domain.chat.taskcenter

import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus

/**
 * 工程师任务卡 OpenUI 程序组装器（spec《2026-11-02-engineer-taskcard-openui》§3.1；
 * [EngineerTaskHtml] 的 OpenUI 对偶——段落逻辑/文案回退/已知偏差逐条平移，阶段解析
 * 与进度启发式**委托 [EngineerTaskHtml] 复用不复制**）。
 *
 * - 产物 = 单条 `root = Card([...])` 语句的 OpenUI 程序文本（协议要求 Card 根）；
 *   Android 宿主端解包 Card 渲染 children（卡壳由外层两分区原生容器提供，
 *   对齐 H2 期 HtmlCard cardColor/contentPadding=0 的中和手法）。
 * - 纯函数：同输入同输出（不含任何时间戳），golden 单测逐字符钉住。
 * - **唯一注入面 = [escapeProgramString]**（对齐 [EngineerTaskHtml.escapeHtml] 的红线
 *   地位）：所有状态文本入程序前按 OpenUI lexer 字符串字面量规则全转义——
 *   `\` `"` 换行/制表/回车及控制字符；`$` 在双引号字面量内为普通字符，无需转义。
 * - iOS 双轨期 HTML 模板仍在（[EngineerTaskHtml] 服务 WKWebView），本组装器为
 *   Android OpenUI 渲染路径 SSOT；已平移的已知偏差（继承 H2 口径）：进度 % 启发式
 *   封顶 80、时间线无 ○ 待办行、diff 行无 +a/−b 行数。
 */
object EngineerTaskProgram {

    /** 时间线最多渲染条数（与 [EngineerTaskHtml] 同口径，防长卡）。 */
    private const val TIMELINE_MAX_ROWS = 8

    private const val TITLE_SEP = " ｜ "

    /** 组装任务卡 OpenUI 程序文本；折叠/展开两形态共用骨架，[expanded] 控制明细段。 */
    fun assemble(task: EngineerTaskState, expanded: Boolean, texts: EngineerTaskTexts): String {
        val sections = mutableListOf<String>()
        sections += """Column([Tag("${esc(texts.badgeLabel)}")], align = "end")"""
        sections += headerRow(task, texts)
        when (task.status) {
            EngineerTaskStatus.RUNNING -> {
                task.stage?.takeIf { stage -> stage.isNotBlank() }?.let { stage ->
                    sections += """Text("${esc(EngineerTaskHtml.stageLine(stage, texts))}")"""
                }
                metaLine(task, texts)?.let { line -> sections += line }
                sections += """Progress(${EngineerTaskHtml.progressFraction(task)})"""
            }
            EngineerTaskStatus.AWAITING_CONTINUE -> {
                task.truncatedReason?.takeIf { reason -> reason.isNotBlank() }?.let { reason ->
                    sections += """Text("${esc(texts.reasonPrefix + EngineerTaskHtml.truncatedReasonLabel(reason, texts))}")"""
                }
                usedLine(texts)?.let { line -> sections += line }
                task.resultSummary?.takeIf { summary -> summary.isNotBlank() }?.let { summary ->
                    sections += """Text("${esc(texts.summaryPrefix + summary)}")"""
                }
            }
            EngineerTaskStatus.AWAITING_DELIVER -> {
                texts.deliverSummary?.let { summary ->
                    sections += """Text("${esc(summary)}", role = "CAPTION", colorRole = "SURFACE")"""
                }
                errorBlock(task)?.let { block -> sections += block }
            }
            EngineerTaskStatus.COMPLETED -> {
                task.resultSummary?.takeIf { summary -> summary.isNotBlank() }?.let { summary ->
                    sections += """Text("${esc(summary)}", role = "CAPTION", colorRole = "SURFACE")"""
                }
                // 失败后被裁决（放弃/重试后终态化）的卡：出错原因仍是卡片的一部分，不随终态消失
                if (task.resolution != null) {
                    errorBlock(task)?.let { block -> sections += block }
                }
            }
            EngineerTaskStatus.FAILED -> errorBlock(task)?.let { block -> sections += block }
        }
        if (task.recentStages.isNotEmpty()) {
            if (expanded) {
                sections += "Divider()"
                sections += timeline(task, texts)
                sections += events(task, texts)
                texts.filesChanged?.let { files ->
                    sections += """Text("${esc(files)}", role = "CAPTION", colorRole = "SURFACE")"""
                }
            }
            val hint = esc(if (expanded) texts.collapseHint else texts.expandHint)
            sections += "Column([Text(\"$hint\", role = \"CAPTION\", colorRole = \"SURFACE\")], " +
                "align = \"center\")"
        }
        val body = sections.joinToString(",\n  ")
        return "root = Card([Column([\n  $body\n], spacing = \"s\")])"
    }

    /**
     * OpenUI 程序字符串字面量转义（唯一注入面；lexer 收 `\\ \" \n \t \r \uXXXX`，
     * 其余转义按原字符保留——所以合成端必须全转义，敌对输入不得改变程序结构）。
     */
    fun escapeProgramString(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\t' -> append("\\t")
                ch == '\r' -> append("\\r")
                ch.code < 0x20 -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
    }

    private fun esc(raw: String): String = escapeProgramString(raw)

    /** 头部行：⚙ 容器块 + 单行省略标题 + 状态 chip（tone 映射对齐 H2 三色口径）。 */
    private fun headerRow(task: EngineerTaskState, texts: EngineerTaskTexts): String {
        val (chipText, tone) = when (task.resolution) {
            EngineerTaskResolution.CONTINUED -> texts.chipResolvedContinued to "neutral"
            EngineerTaskResolution.ABANDONED -> texts.chipResolvedAbandoned to "neutral"
            EngineerTaskResolution.DELIVERED -> texts.chipResolvedDelivered to "neutral"
            EngineerTaskResolution.DELIVER_SKIPPED -> texts.chipResolvedSkipped to "neutral"
            null -> when (task.status) {
                EngineerTaskStatus.RUNNING -> texts.chipRunning to "primary"
                EngineerTaskStatus.COMPLETED -> texts.chipCompleted to "primary"
                EngineerTaskStatus.AWAITING_CONTINUE,
                EngineerTaskStatus.AWAITING_DELIVER,
                -> texts.chipAwaiting to "error"
                EngineerTaskStatus.FAILED -> texts.chipFailed to "error"
            }
        }
        return "Row([" +
            """Icon("settings", container = "tonal"), """ +
            """Text("${esc(texts.titlePrefix + task.sourceText)}", role = "SUBHEAD", maxLines = 1), """ +
            """Tag("${esc(chipText)}", tone = "$tone")""" +
            "], weights = [0, 1, 0])"
    }

    private fun metaLine(task: EngineerTaskState, texts: EngineerTaskTexts): String? {
        val parts = buildList {
            texts.metaTurns?.let { part -> add(part) }
            texts.metaElapsed?.let { part -> add(part) }
            texts.metaCost?.let { part -> add(part) }
            task.deliverBranch?.takeIf { branch -> branch.isNotBlank() }?.let { branch ->
                add("⎇ $branch")
            }
        }
        if (parts.isEmpty()) return null
        return """Text("${esc(parts.joinToString(TITLE_SEP))}", role = "CAPTION", colorRole = "SURFACE")"""
    }

    private fun usedLine(texts: EngineerTaskTexts): String? {
        val parts = listOfNotNull(texts.usedTurns, texts.usedCost)
        if (parts.isEmpty()) return null
        return """Text("${esc(texts.usedPrefix + parts.joinToString(TITLE_SEP))}", role = "CAPTION", colorRole = "SURFACE")"""
    }

    /** 错误块：Callout(ERROR)（对齐原生 EngineerTaskErrorBlock 视觉语义）。 */
    private fun errorBlock(task: EngineerTaskState): String? =
        task.errorSummary?.takeIf { err -> err.isNotBlank() }
            ?.let { err -> """Callout("${esc(err)}", tone = "ERROR")""" }

    /**
     * 时间线：✓ 已完成（primary 标记）/ RUNNING 末条 ● 当前（标记与文字均 primary，
     * 对齐 design taskcard/expanded 帧）；文案本地化复用 [EngineerTaskHtml.timelineLabel]。
     * 超 8 行前导 … 行（标记列 primary）。
     */
    private fun timeline(task: EngineerTaskState, texts: EngineerTaskTexts): String {
        val stages = task.recentStages
        val rows = StringBuilder()
        if (stages.size > TIMELINE_MAX_ROWS) {
            rows.append("""Text("…", colorRole = "PRIMARY"), """)
        }
        val visible = stages.takeLast(TIMELINE_MAX_ROWS)
        visible.forEachIndexed { index, stageLabel ->
            val isCurrent = task.status == EngineerTaskStatus.RUNNING && index == visible.lastIndex
            val mark = if (isCurrent) "●" else "✓"
            val label = esc(EngineerTaskHtml.timelineLabel(stageLabel, texts))
            val labelPart = if (isCurrent) {
                """Text("$label", colorRole = "PRIMARY")"""
            } else {
                """Text("$label")"""
            }
            rows.append("""Row([Text("$mark", colorRole = "PRIMARY"), $labelPart], weights = [0, 1]), """)
        }
        return rows.toString().removeSuffix(", ")
    }

    /** 最近事件段：标题行 + 原始工具串逐行（H2 口径：不本地化、非等宽为已知偏差）。 */
    private fun events(task: EngineerTaskState, texts: EngineerTaskTexts): String {
        if (task.recentStages.isEmpty()) return ""
        val caption = """Text("${esc(texts.recentEventsCaption)}", role = "CAPTION", colorRole = "SURFACE")"""
        val rows = task.recentStages.joinToString(", ") { stage ->
            """Text("${esc(stage)}", role = "CAPTION", colorRole = "SURFACE")"""
        }
        return "$caption, $rows"
    }
}
