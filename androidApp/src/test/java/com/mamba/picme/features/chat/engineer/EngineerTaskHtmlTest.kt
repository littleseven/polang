package com.mamba.picme.features.chat.engineer

import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务卡 L1 模板组装器（[EngineerTaskHtml]）单测，spec《HTML 卡双形态》§7/§11/§14：
 * 转义（唯一注入面）/ 五态 × 折叠展开产物标记 / 字段缺省降级 / 确定性（golden）。
 */
class EngineerTaskHtmlTest {

    private val palette = EngineerTaskPalette(
        cardBg = "#1A1A1A",
        iconBlockBg = "#222222",
        primary = "#07C160",
        onPrimary = "#FFFFFF",
        error = "#FA5151",
        onError = "#FFFFFF",
        onSurface = "#D5D5D5",
        onSurfaceVariant = "#7F7F7F",
        surfaceVariant = "#2C2C2C",
        outlineVariant = "#2C2C2C",
        neutralChipBg = "#222222",
        neutralChipFg = "#7F7F7F",
    )

    private fun texts(
        metaTurns: String? = "12 轮",
        metaElapsed: String? = "耗时 3:42",
        metaCost: String? = "成本 $0.31",
        usedTurns: String? = metaTurns,
        usedCost: String? = metaCost,
        filesChanged: String? = "3 files changed",
        deliverSummary: String? = null,
    ) = EngineerTaskTexts(
        titlePrefix = "工程师任务：",
        chipRunning = "进行中",
        chipAwaiting = "待审批",
        chipCompleted = "已完成",
        chipFailed = "失败",
        chipResolvedContinued = "已继续",
        chipResolvedAbandoned = "已放弃",
        chipResolvedDelivered = "已交付",
        chipResolvedSkipped = "暂不交付",
        expandHint = "点击展开细节 ▾",
        collapseHint = "点击收起 ▴",
        recentEventsCaption = "最近事件",
        metaTurns = metaTurns,
        metaElapsed = metaElapsed,
        metaCost = metaCost,
        reasonPrefix = "截断原因：",
        usedPrefix = "已用：",
        summaryPrefix = "小结：",
        usedTurns = usedTurns,
        usedCost = usedCost,
        filesChanged = filesChanged,
        deliverSummary = deliverSummary,
    )

    private fun runningTask(
        sourceText: String = "修复相册扫描时的崩溃",
        stage: String? = "Edit · 正在修改 TagScanOrchestrator.kt",
        recentStages: List<String> = listOf("Read TagScanOrchestrator.kt", "Edit 2 files", "Bash ./gradlew compile"),
    ) = EngineerTaskState(
        taskId = "task_1",
        sourceText = sourceText,
        status = EngineerTaskStatus.RUNNING,
        stage = stage,
        recentStages = recentStages,
        turns = 12,
        costCents = 31,
        startedAtMs = 1_000L,
        updatedAtMs = 222_000L,
        fileChangeCount = 3,
    )

    // ---- 转义（spec §11：变量插值是唯一注入面）----

    @Test
    fun `escapeHtml covers five entities`() {
        assertEquals("&lt;script&gt;&amp;quot;&#39;", EngineerTaskHtml.escapeHtml("<script>&quot;'"))
        assertEquals("a&amp;b", EngineerTaskHtml.escapeHtml("a&b"))
        assertEquals("plain 汉字 123", EngineerTaskHtml.escapeHtml("plain 汉字 123"))
    }

    @Test
    fun `injection attempts in state text are entity encoded`() {
        val malicious = """<img src=x onerror="alert(1)"><script>fetch('//evil')</script>"""
        val html = EngineerTaskHtml.assemble(
            runningTask(sourceText = malicious, stage = malicious, recentStages = listOf(malicious)),
            expanded = true,
            palette = palette,
            texts = texts(),
        )
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img "))
        // 模板自身的合法标签不受影响（document 骨架仍在）
        assertTrue(html.contains("<html"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    // ---- RUNNING 折叠帧 ----

    @Test
    fun `collapsed running card contains header stage meta progress and expand hint`() {
        val html = EngineerTaskHtml.assemble(runningTask(), expanded = false, palette = palette, texts = texts())
        assertTrue(html.contains("进行中"))
        assertTrue(html.contains("工程师任务：修复相册扫描时的崩溃"))
        assertTrue(html.contains("Edit · 正在修改 TagScanOrchestrator.kt"))
        assertTrue(html.contains("12 轮 ｜ 耗时 3:42 ｜ 成本 $0.31"))
        assertTrue(html.contains("<div class=\"ptrack\""))
        assertTrue(html.contains("点击展开细节 ▾"))
        assertFalse(html.contains("点击收起"))
        // 折叠态不含明细段
        assertFalse(html.contains("最近事件"))
        assertFalse(html.contains("<div class=\"divider\""))
    }

    @Test
    fun `missing fields degrade silently`() {
        val html = EngineerTaskHtml.assemble(
            task = runningTask(stage = null, recentStages = emptyList()),
            expanded = false,
            palette = palette,
            texts = texts(metaElapsed = null),
        )
        assertFalse(html.contains("<div class=\"stage\">"))
        // RUNNING 折叠帧恒有进度条（design taskcard/collapsed），初始 10%
        assertTrue(html.contains("<div class=\"ptrack\""))
        assertTrue(html.contains("width:10%"))
        // 无阶段 → 无展开提示（无内容可展开）
        assertFalse(html.contains("点击展开"))
        assertTrue(html.contains("12 轮 ｜ 成本 $0.31"))
    }

    @Test
    fun `empty meta parts omit the whole line`() {
        val html = EngineerTaskHtml.assemble(
            task = runningTask(recentStages = listOf("Read")),
            expanded = false,
            palette = palette,
            texts = texts(metaTurns = null, metaElapsed = null, metaCost = null),
        )
        assertFalse(html.contains(" ｜ "))
    }

    // ---- 审批帧（AWAITING_CONTINUE 替换 stage/meta/进度）----

    @Test
    fun `awaiting continue shows reason used and hides progress`() {
        val task = runningTask().copy(
            status = EngineerTaskStatus.AWAITING_CONTINUE,
            truncatedReason = "达到最大轮次（50）",
        )
        val html = EngineerTaskHtml.assemble(task, expanded = false, palette = palette, texts = texts())
        assertTrue(html.contains("截断原因：达到最大轮次（50）"))
        assertTrue(html.contains("已用：12 轮 ｜ 成本 $0.31"))
        assertTrue(html.contains("待审批"))
        assertFalse(html.contains("<div class=\"ptrack\""))
        assertFalse(html.contains("Edit · 正在修改"))
        // 截断时无 resultSummary → 小结行缺省
        assertFalse(html.contains("小结："))
    }

    @Test
    fun `summary line renders only when result summary present`() {
        val task = runningTask().copy(
            status = EngineerTaskStatus.AWAITING_CONTINUE,
            truncatedReason = "timeout",
            resultSummary = "已完成根因定位与修复，编译验证中断",
        )
        val html = EngineerTaskHtml.assemble(task, expanded = false, palette = palette, texts = texts())
        assertTrue(html.contains("小结：已完成根因定位与修复，编译验证中断"))
    }

    // ---- 展开帧 ----

    @Test
    fun `expanded card adds divider timeline events and diff row`() {
        val html = EngineerTaskHtml.assemble(
            runningTask(),
            expanded = true,
            palette = palette,
            texts = texts(),
        )
        assertTrue(html.contains("<div class=\"divider\""))
        assertTrue(html.contains("最近事件"))
        assertTrue(html.contains("3 files changed"))
        assertTrue(html.contains("点击收起 ▴"))
        // 时间线：非末条 ✓（&#10003;）、RUNNING 末条 ●（&#9679;）且文字 primary 色
        assertTrue(html.contains("&#10003;"))
        assertTrue(html.contains("&#9679;"))
        assertTrue(html.contains("color:#07C160\">Bash ./gradlew compile"))
        // 无 ○ 待办行（状态无阶段计划数据）
        assertFalse(html.contains("&#9675;"))
    }

    @Test
    fun `timeline caps at eight rows with ellipsis marker`() {
        val stages = (1..12).map { index -> "Stage$index" }
        val html = EngineerTaskHtml.assemble(
            runningTask(stage = "Stage12", recentStages = stages),
            expanded = true,
            palette = palette,
            texts = texts(),
        )
        assertTrue(html.contains("&#8230;"))
        // 时间线 = 8 条可见 + 1 条省略行（events 段全量渲染 Stage1-12 属预期）
        assertEquals(9, Regex("class=\"tlrow\"").findAll(html).count())
        assertTrue(html.contains("Stage5<"))
    }

    @Test
    fun `non running timeline marks all stages as done`() {
        // 暂停/终态无「当前」概念：全 ✓，不出现 ●（对齐 design 展开帧为 RUNNING 态的语义）
        val task = runningTask().copy(status = EngineerTaskStatus.AWAITING_CONTINUE, truncatedReason = "max_turns")
        val html = EngineerTaskHtml.assemble(task, expanded = true, palette = palette, texts = texts())
        assertTrue(html.contains("&#10003;"))
        assertFalse(html.contains("&#9679;"))
    }

    // ---- resolution / 终态 ----

    @Test
    fun `resolution overrides chip with neutral style`() {
        val task = runningTask().copy(
            status = EngineerTaskStatus.COMPLETED,
            resolution = EngineerTaskResolution.ABANDONED,
        )
        val html = EngineerTaskHtml.assemble(task, expanded = false, palette = palette, texts = texts())
        assertTrue(html.contains("已放弃"))
        assertTrue(html.contains("var(--neutral-fg)"))
        assertFalse(html.contains("var(--primary);color:var(--on-primary)"))
    }

    @Test
    fun `failed card shows error block in error color`() {
        val task = runningTask().copy(status = EngineerTaskStatus.FAILED, errorSummary = "connection lost")
        val html = EngineerTaskHtml.assemble(task, expanded = false, palette = palette, texts = texts())
        assertTrue(html.contains("#FA515114"))
        assertTrue(html.contains("connection lost"))
        assertFalse(html.contains("<div class=\"ptrack\""))
    }

    @Test
    fun `completed keeps error block only after resolution`() {
        val base = runningTask().copy(status = EngineerTaskStatus.COMPLETED, resultSummary = "已完成")
        val unresolved = EngineerTaskHtml.assemble(
            base.copy(errorSummary = "boom"), expanded = false, palette = palette, texts = texts())
        assertFalse(unresolved.contains("boom"))
        val resolved = EngineerTaskHtml.assemble(
            base.copy(errorSummary = "boom", resolution = EngineerTaskResolution.CONTINUED),
            expanded = false, palette = palette, texts = texts())
        assertTrue(resolved.contains("boom"))
    }

    // ---- 确定性（golden）与进度启发式 ----

    @Test
    fun `assemble is deterministic for identical inputs`() {
        val task = runningTask()
        val first = EngineerTaskHtml.assemble(task, expanded = true, palette = palette, texts = texts())
        val second = EngineerTaskHtml.assemble(task, expanded = true, palette = palette, texts = texts())
        assertEquals(first, second)
        // 折叠/展开产物不同
        assertNotEquals(first, EngineerTaskHtml.assemble(task, expanded = false, palette = palette, texts = texts()))
    }

    @Test
    fun `progress fraction grows with activity and caps at eighty`() {
        assertEquals(10, EngineerTaskHtml.progressFraction(runningTask(recentStages = emptyList())))
        assertEquals(50, EngineerTaskHtml.progressFraction(runningTask(recentStages = List(4) { "S$it" })))
        assertEquals(80, EngineerTaskHtml.progressFraction(runningTask(recentStages = List(20) { "S$it" })))
    }
}

/** 500ms 合帧判定（spec §7）：状态/裁决跃迁即时，RUNNING 字段微更新合帧。 */
class EngineerTaskThrottleTest {

    @Test
    fun `first appearance is immediate`() {
        assertTrue(EngineerTaskThrottle.isImmediate(null, state(turns = 1)))
    }

    @Test
    fun `status transition is immediate`() {
        val running = state(status = EngineerTaskStatus.RUNNING)
        val truncated = running.copy(status = EngineerTaskStatus.AWAITING_CONTINUE)
        assertTrue(EngineerTaskThrottle.isImmediate(running, truncated))
    }

    @Test
    fun `resolution transition is immediate`() {
        val awaiting = state(status = EngineerTaskStatus.AWAITING_CONTINUE)
        val resolved = awaiting.copy(resolution = EngineerTaskResolution.CONTINUED)
        assertTrue(EngineerTaskThrottle.isImmediate(awaiting, resolved))
    }

    @Test
    fun `running micro updates are coalesced`() {
        val before = state(stage = "Read", turns = 3, recentStages = listOf("Read"))
        val after = before.copy(stage = "Edit", turns = 4, recentStages = listOf("Read", "Edit"), updatedAtMs = 9_999L)
        assertFalse(EngineerTaskThrottle.isImmediate(before, after))
    }

    private fun state(
        status: EngineerTaskStatus = EngineerTaskStatus.RUNNING,
        stage: String? = null,
        turns: Int = 0,
        recentStages: List<String> = emptyList(),
        updatedAtMs: Long = 1_000L,
        resolution: EngineerTaskResolution? = null,
    ) = EngineerTaskState(
        taskId = "task_t",
        sourceText = "s",
        status = status,
        stage = stage,
        recentStages = recentStages,
        turns = turns,
        startedAtMs = 0L,
        updatedAtMs = updatedAtMs,
        resolution = resolution,
    )
}
