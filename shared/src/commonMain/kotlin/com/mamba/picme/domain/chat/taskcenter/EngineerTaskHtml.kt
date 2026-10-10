package com.mamba.picme.domain.chat.taskcenter

import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus

/**
 * 工程师任务卡 L1 模板组装器（spec《HTML 卡双形态》2026-09-26 §7；ADR-016 D5 L1 轨首个兑现场景）。
 *
 * - HTML 来源 = 端侧模板 + [EngineerTaskState]（App 渲染，非 LLM 产物）——样式权威在 App，
 *   模板本身可信，**变量插值是唯一注入面**：所有状态文本字段（阶段/事件行/原因/摘要）入模板前
 *   一律经 [escapeHtml] 纯文本转义（spec §11），不经 HtmlCardSanitizer（那是 LLM 产物的清洗轨）。
 * - CSS 变量由 [EngineerTaskPalette] 注入（UI 层从主题运行时取色 → hex），
 *   Light/Dark 随主题切换（design-tokens 单源，与 Ardot taskcard 四帧逐值对齐）。
 * - 文案由 [EngineerTaskTexts] 预解析传入（各端本地化预解析），组装器只做拼接与转义——纯函数可测。
 * - 产物为完整文档（含 `<html`），宿主管线原样放行；
 *   viewport width=device-width → 1 CSS px ≈ 1 dp/pt（测高/排版基准与 HtmlCard 管线一致）。
 * - 卡片为上下两分区（2026-09-27 Muse 修订）：本模板只渲染上半信息区（InfoZone）；
 *   下半动作区（停止/radio 选项/双按钮/重试）由原生层在卡容器内组合（零 JS 桥接红线不动）。
 *
 * 与设计稿（taskcard/collapsed、expanded、approval、done，Ardot 页 438:2，帧 438:181/438:208/438:264/444:31）
 * 的既知偏差（状态无对应数据，设计值为示意）：进度 % 为活动量启发式（[progressFraction]，封顶 80%）；
 * 时间线无 ○ 待办行（无阶段计划数据）；diff 行无 +a/−b 行数（状态仅 fileChangeCount，整行 onSurfaceVariant 呈现）。
 */

/** 任务卡调色板：hex 字符串（"#1A1A1A" 形态），双模值由 UI 层运行时取色转换。 */
data class EngineerTaskPalette(
    /** 卡底（scheme/surfaceContainer：Dark #1A1A1A / Light #F7F7F7）。 */
    val cardBg: String,
    /** ⚙ 图标块底（surfaceContainerHigh：#222222 / #EDEDED）。 */
    val iconBlockBg: String,
    val primary: String,
    val onPrimary: String,
    val error: String,
    val onError: String,
    val onSurface: String,
    val onSurfaceVariant: String,
    /** 进度条轨道底（surfaceVariant：#2C2C2C / #F2F2F2）。 */
    val surfaceVariant: String,
    /** 分隔线（outlineVariant：#2C2C2C / #E5E5E5）。 */
    val outlineVariant: String,
    /** 中性 chip（COMPLETED/resolved 态）：底 surfaceContainerHigh、字 onSurfaceVariant。 */
    val neutralChipBg: String,
    val neutralChipFg: String,
)

/**
 * 模板文案（UI 层本地化预解析，含数值格式化）；null = 数据缺省时整行/整段隐藏。
 */
data class EngineerTaskTexts(
    /** 标题前缀（「工程师任务：」）。 */
    val titlePrefix: String,
    /** 右上来源徽章（「TASK · HTML」）。默认值为五语同形的固定技术标签，Android 经 stringResource 显式传入。 */
    val badgeLabel: String = "TASK · HTML",
    val chipRunning: String,
    val chipAwaiting: String,
    val chipCompleted: String,
    val chipFailed: String,
    val chipResolvedContinued: String,
    val chipResolvedAbandoned: String,
    val chipResolvedDelivered: String,
    val chipResolvedSkipped: String,
    val expandHint: String,
    val collapseHint: String,
    val recentEventsCaption: String,
    /** 「轮次 N」（已格式化，turns>0 时传入）。 */
    val metaTurns: String? = null,
    /** 「耗时 M:SS」（已格式化）。 */
    val metaElapsed: String? = null,
    /** 「成本 $X.XX」（已格式化，costCents 非空时传入）。 */
    val metaCost: String? = null,
    /** 「截断原因：」。 */
    val reasonPrefix: String,
    /** 「已用：」。 */
    val usedPrefix: String,
    /** 「小结：」。 */
    val summaryPrefix: String,
    /** 审批帧「已用」段（已格式化，可空段拼「 ｜ 」）。 */
    val usedTurns: String? = null,
    val usedCost: String? = null,
    /** 「N files changed」（fileChangeCount>0 时传入）。 */
    val filesChanged: String? = null,
    /** AWAITING_DELIVER 摘要行（「已修改 N 个文件…」）。 */
    val deliverSummary: String? = null,
)

@Suppress("TooManyFunctions") // 模板组装器按段落拆私有构件（header/meta/timeline/events…），职责单一
object EngineerTaskHtml {

    /** 重渲染合帧窗口（spec §7：SSE 状态变化 → 500ms 合帧重渲染）。 */
    const val FRAME_MS = 500L

    /** 时间线最多渲染条数（recentStages 已封顶 20，时间线再收窄防长卡）。 */
    private const val TIMELINE_MAX_ROWS = 8

    /** 进度启发式步长/封顶（百分比）：每观察到一个阶段 +10%，封顶 80（设计 50% 系示意）。 */
    private const val PROGRESS_STEP_PERCENT = 10
    private const val PROGRESS_CAP_PERCENT = 80

    /**
     * 组装任务卡 HTML（完整文档）。折叠/展开两形态共用骨架，[expanded] 控制明细段与提示文案。
     * 纯函数：同输入同输出（不含任何时间戳），便于 golden 单测与 WebView 全文判等去重。
     */
    fun assemble(
        task: EngineerTaskState,
        expanded: Boolean,
        palette: EngineerTaskPalette,
        texts: EngineerTaskTexts,
    ): String {
        val body = StringBuilder()
        body.append("""<div class="badge-row"><span class="badge">${escapeHtml(texts.badgeLabel)}</span></div>""")
        body.append(headerRow(task, texts))
        when (task.status) {
            EngineerTaskStatus.RUNNING -> {
                task.stage?.takeIf { stage -> stage.isNotBlank() }?.let { stage ->
                    body.append(line(escapeHtml(stage), CSS_STAGE))
                }
                body.append(metaLine(task, texts))
                body.append(progressTrack(task, palette))
            }
            EngineerTaskStatus.AWAITING_CONTINUE -> {
                task.truncatedReason?.takeIf { reason -> reason.isNotBlank() }?.let { reason ->
                    body.append(line(texts.reasonPrefix + escapeHtml(reason), CSS_BODY))
                }
                body.append(usedLine(texts))
                task.resultSummary?.takeIf { summary -> summary.isNotBlank() }?.let { summary ->
                    body.append(line(texts.summaryPrefix + escapeHtml(summary), CSS_BODY))
                }
            }
            EngineerTaskStatus.AWAITING_DELIVER -> {
                texts.deliverSummary?.let { summary ->
                    body.append(line(escapeHtml(summary), CSS_SMALL))
                }
                body.append(errorBlock(task.errorSummary, palette))
            }
            EngineerTaskStatus.COMPLETED -> {
                task.resultSummary?.takeIf { summary -> summary.isNotBlank() }?.let { summary ->
                    body.append(line(escapeHtml(summary), CSS_SMALL))
                }
                // 失败后被裁决（放弃/重试后终态化）的卡：出错原因仍是卡片的一部分，不随终态消失
                if (task.resolution != null) {
                    body.append(errorBlock(task.errorSummary, palette))
                }
            }
            EngineerTaskStatus.FAILED -> {
                body.append(errorBlock(task.errorSummary, palette))
            }
        }
        if (task.recentStages.isNotEmpty()) {
            if (expanded) {
                body.append(divider(palette))
                body.append(timeline(task, palette))
                body.append(events(task, texts))
                texts.filesChanged?.let { files ->
                    body.append(line(escapeHtml(files), CSS_SMALL))
                }
            }
            body.append(line(if (expanded) texts.collapseHint else texts.expandHint, CSS_HINT))
        }
        return document(palette, body.toString())
    }

    /** HTML 实体转义（spec §11：变量插值是唯一注入面，五字符全覆盖）。 */
    fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /**
     * 进度启发式（RUNNING 态展示）：观察到的活动量（阶段数）驱动、封顶 80%——
     * 状态无真实进度字段，设计稿 50% 系示意；确定性纯函数。
     */
    fun progressFraction(task: EngineerTaskState): Int =
        ((task.recentStages.size.coerceAtMost(TIMELINE_MAX_ROWS) + 1) * PROGRESS_STEP_PERCENT)
            .coerceAtMost(PROGRESS_CAP_PERCENT)

    // ---- 段落构件 ---------------------------------------------------------------

    private fun headerRow(task: EngineerTaskState, texts: EngineerTaskTexts): String {
        // chipBg/chipFg 为 :root CSS 变量名（kebab-case，与 document() 定义严格一致）
        val (chipText, chipBg, chipFg) = when (task.resolution) {
            EngineerTaskResolution.CONTINUED -> Triple(texts.chipResolvedContinued, "neutral", "neutral-fg")
            EngineerTaskResolution.ABANDONED -> Triple(texts.chipResolvedAbandoned, "neutral", "neutral-fg")
            EngineerTaskResolution.DELIVERED -> Triple(texts.chipResolvedDelivered, "neutral", "neutral-fg")
            EngineerTaskResolution.DELIVER_SKIPPED -> Triple(texts.chipResolvedSkipped, "neutral", "neutral-fg")
            null -> when (task.status) {
                EngineerTaskStatus.RUNNING -> Triple(texts.chipRunning, "primary", "on-primary")
                EngineerTaskStatus.COMPLETED -> Triple(texts.chipCompleted, "primary", "on-primary")
                EngineerTaskStatus.AWAITING_CONTINUE,
                EngineerTaskStatus.AWAITING_DELIVER,
                -> Triple(texts.chipAwaiting, "error", "on-error")
                EngineerTaskStatus.FAILED -> Triple(texts.chipFailed, "error", "on-error")
            }
        }
        val chipStyle = "background:var(--$chipBg);color:var(--$chipFg)"
        return """<div class="hdr"><div class="ico">&#9881;</div>""" +
            """<div class="ttl">${escapeHtml(texts.titlePrefix + task.sourceText)}</div>""" +
            """<span class="chip" style="$chipStyle">${escapeHtml(chipText)}</span></div>"""
    }

    private fun metaLine(task: EngineerTaskState, texts: EngineerTaskTexts): String {
        val parts = buildList {
            texts.metaTurns?.let { part -> add(part) }
            texts.metaElapsed?.let { part -> add(part) }
            texts.metaCost?.let { part -> add(part) }
            task.deliverBranch?.takeIf { branch -> branch.isNotBlank() }?.let { branch ->
                add("⎇ " + escapeHtml(branch))
            }
        }
        if (parts.isEmpty()) return ""
        return line(parts.joinToString(TITLE_SEP), CSS_SMALL)
    }

    private fun usedLine(texts: EngineerTaskTexts): String {
        val parts = listOfNotNull(texts.usedTurns, texts.usedCost)
        if (parts.isEmpty()) return ""
        return line(texts.usedPrefix + parts.joinToString(TITLE_SEP), CSS_SMALL)
    }

    private fun progressTrack(task: EngineerTaskState, palette: EngineerTaskPalette): String {
        if (task.status != EngineerTaskStatus.RUNNING) return ""
        return """<div class="ptrack" style="background:${palette.surfaceVariant}">""" +
            """<div class="pfill" style="background:${palette.primary};width:${progressFraction(task)}%"></div></div>"""
    }

    /** 错误块：error 色 8% 透明底容器 + error 色摘要（对齐原生 EngineerTaskErrorBlock 视觉）。 */
    private fun errorBlock(errorText: String?, palette: EngineerTaskPalette): String {
        val text = errorText?.takeIf { err -> err.isNotBlank() } ?: return ""
        return """<div class="eblock" style="background:${palette.error}14">""" +
            """<span style="color:${palette.error}">${escapeHtml(text)}</span></div>"""
    }

    private fun divider(palette: EngineerTaskPalette): String =
        """<div class="divider" style="background:${palette.outlineVariant}"></div>"""

    /**
     * 时间线：✓ 已完成（primary）→ RUNNING 态末条 ● 当前（primary + 文字 primary，
     * 对齐 design taskcard/expanded 帧）；非 RUNNING（暂停/终态）无「当前」概念，全 ✓。
     * 无 ○ 待办行（状态无阶段计划数据，设计稿 ○ 行系示意）。
     */
    private fun timeline(task: EngineerTaskState, palette: EngineerTaskPalette): String {
        val stages = task.recentStages
        val rows = StringBuilder()
        if (stages.size > TIMELINE_MAX_ROWS) {
            rows.append("""<div class="tlrow"><span class="tlmark">&#8230;</span><span>&nbsp;</span></div>""")
        }
        val visible = stages.takeLast(TIMELINE_MAX_ROWS)
        visible.forEachIndexed { index, stageLabel ->
            val isCurrent = task.status == EngineerTaskStatus.RUNNING && index == visible.lastIndex
            val mark = if (isCurrent) "&#9679;" else "&#10003;"
            val labelColor = if (isCurrent) palette.primary else palette.onSurface
            rows.append(
                """<div class="tlrow"><span class="tlmark" style="color:${palette.primary}">$mark</span>""" +
                    """<span style="color:$labelColor">${escapeHtml(stageLabel)}</span></div>"""
            )
        }
        return rows.toString()
    }

    private fun events(task: EngineerTaskState, texts: EngineerTaskTexts): String {
        if (task.recentStages.isEmpty()) return ""
        val rows = task.recentStages.joinToString(separator = "") { stageLabel ->
            """<div class="ev">${escapeHtml(stageLabel)}</div>"""
        }
        return """<div class="evcap">${escapeHtml(texts.recentEventsCaption)}</div>$rows"""
    }

    private fun line(content: String, cssClass: String): String =
        """<div class="$cssClass">$content</div>"""

    private fun document(palette: EngineerTaskPalette, body: String): String = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
:root{--card-bg:${palette.cardBg};--icon-bg:${palette.iconBlockBg};--primary:${palette.primary};
--on-primary:${palette.onPrimary};--error:${palette.error};--on-error:${palette.onError};
--on-surface:${palette.onSurface};--on-surface-variant:${palette.onSurfaceVariant};
--surface-variant:${palette.surfaceVariant};--outline-variant:${palette.outlineVariant};
--neutral:${palette.neutralChipBg};--neutral-fg:${palette.neutralChipFg}}
html,body{margin:0;padding:0}
body{background:var(--card-bg);display:flow-root;overflow-wrap:break-word;
padding:12px 16px 14px;font-family:system-ui,-apple-system,'PingFang SC','Segoe UI',Roboto,sans-serif;
font-size:12.5px;color:var(--on-surface)}
.badge-row{display:flex;justify-content:flex-end}
.badge{border-radius:8px;padding:2px 8px;font-size:10px;font-weight:600;
background:var(--surface-variant);color:var(--on-surface-variant)}
.hdr{display:flex;align-items:center;gap:8px;margin-top:8px}
.ico{width:28px;height:28px;border-radius:7px;background:var(--icon-bg);color:var(--primary);
display:flex;align-items:center;justify-content:center;font-size:15px;flex:none}
.ttl{flex:1;min-width:0;font-size:14px;font-weight:600;line-height:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.chip{flex:none;border-radius:6px;padding:3px 8px;font-size:10px;font-weight:600}
.stage{margin-top:8px;font-size:12px}
.small{margin-top:8px;font-size:10px;color:var(--on-surface-variant)}
.body-line{margin-top:8px;font-size:12px}
.hint{margin-top:8px;font-size:10px;color:var(--on-surface-variant);text-align:center}
.ptrack{margin-top:8px;height:4px;border-radius:2px;overflow:hidden}
.pfill{height:100%;border-radius:2px}
.eblock{margin-top:8px;border-radius:8px;padding:8px 10px;font-size:12px;line-height:1.5}
.divider{margin-top:8px;height:1px}
.tlrow{display:flex;gap:8px;margin-top:8px;font-size:12px;line-height:16px;align-items:baseline}
.tlmark{width:14px;flex:none;font-size:12px;text-align:center}
.evcap{margin-top:8px;font-size:10px;font-weight:600;color:var(--on-surface-variant)}
.ev{margin-top:4px;font-size:10px;color:var(--on-surface-variant);
font-family:ui-monospace,Menlo,'Courier New',monospace}
</style></head><body>$body</body></html>"""

    private const val TITLE_SEP = " ｜ "
    private const val CSS_STAGE = "stage"
    private const val CSS_SMALL = "small"
    private const val CSS_BODY = "body-line"
    private const val CSS_HINT = "hint"
}

/**
 * 500ms 合帧判定（spec §7）：状态/裁决**跃迁**即时渲染（用户可感知的形态变化不等帧），
 * RUNNING 态字段微更新（stage/turns/cost/阶段列表）走合帧窗口；纯函数，单测覆盖。
 */
object EngineerTaskThrottle {
    fun isImmediate(previous: EngineerTaskState?, next: EngineerTaskState): Boolean {
        if (previous == null) return true
        return previous.status != next.status || previous.resolution != next.resolution
    }
}
