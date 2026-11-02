package com.mamba.picme.domain.chat.taskcenter

import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiToolNotFoundException
import com.mamba.picme.domain.chat.openui.OpenUiToolResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [EngineerTaskProgram] golden 单测（spec《2026-11-02-engineer-taskcard-openui》§3.1/§5）：
 * 程序文本逐字符 golden + 全管线自证（OpenUiSession.prepare 无错误——词汇覆盖自证）+
 * 转义注入面敌对用例（对齐 EngineerTaskHtmlTest 的 escapeHtml 红线地位）。
 */
class EngineerTaskProgramTest {

    private fun task(
        status: EngineerTaskStatus = EngineerTaskStatus.RUNNING,
        resolution: EngineerTaskResolution? = null,
        stage: String? = "Edit TagScanOrchestrator.kt",
        recentStages: List<String> = listOf("Read MainActivity.kt", "Edit TagScanOrchestrator.kt"),
        errorSummary: String? = null,
        resultSummary: String? = null,
        truncatedReason: String? = null,
    ) = EngineerTaskState(
        taskId = "t1", sourceText = "修复扫描崩溃", status = status, stage = stage,
        recentStages = recentStages, turns = 3, costCents = 42, startedAtMs = 0, updatedAtMs = 65_000,
        fileChangeCount = 2, truncatedReason = truncatedReason, errorSummary = errorSummary,
        resultSummary = resultSummary, resolution = resolution,
    )

    private val texts = EngineerTaskTexts(
        titlePrefix = "工程师任务：", badgeLabel = "TASK · OPENUI",
        chipRunning = "运行中", chipAwaiting = "待审批", chipCompleted = "已完成",
        chipFailed = "失败", chipResolvedContinued = "已继续", chipResolvedAbandoned = "已放弃",
        chipResolvedDelivered = "已交付", chipResolvedSkipped = "暂不交付",
        expandHint = "展开", collapseHint = "收起", recentEventsCaption = "最近事件",
        metaTurns = "轮次 3", metaElapsed = "耗时 1:05", metaCost = "成本 $0.42",
        reasonPrefix = "截断原因：", usedPrefix = "已用：", summaryPrefix = "小结：",
        usedTurns = "轮次 3", usedCost = "成本 $0.42", filesChanged = "2 files changed",
        stageLabels = mapOf("Read" to "读取文件", "Edit" to "编辑代码", "tool" to "工具"),
        stageVerbs = mapOf("Edit" to "正在修改", "tool" to "正在处理"),
    )

    private fun noOpTools(): OpenUiToolResolver = OpenUiToolResolver { name, _ ->
        throw OpenUiToolNotFoundException(name)
    }

    @Test
    fun `collapsed running card golden`() = runTest {
        val program = EngineerTaskProgram.assemble(task(), expanded = false, texts = texts)
        assertEquals(
            """
            root = Card([Column([
              Column([Tag("TASK · OPENUI")], align = "end"),
              Row([Icon("settings", container = "tonal"), Text("工程师任务：修复扫描崩溃", role = "SUBHEAD", maxLines = 1), Tag("运行中", tone = "primary")], weights = [0, 1, 0]),
              Text("Edit · 正在修改 TagScanOrchestrator.kt"),
              Text("轮次 3 ｜ 耗时 1:05 ｜ 成本 $0.42", role = "CAPTION", colorRole = "SURFACE"),
              Progress(30),
              Column([Text("展开", role = "CAPTION", colorRole = "SURFACE")], align = "center")
            ], spacing = "s")])
            """.trimIndent(),
            program,
        )
        // 全管线自证：parse + eval 无错误（词汇覆盖与语法合法性一次钉住）
        val result = OpenUiSession(program, noOpTools()).prepare()
        val rendered = assertIs<OpenUiResult.Rendered>(result)
        assertEquals(emptyList(), rendered.errors)
    }

    @Test
    fun `expanded card adds divider timeline events and files row`() = runTest {
        val program = EngineerTaskProgram.assemble(task(), expanded = true, texts = texts)
        assertTrue("Divider()" in program)
        assertTrue("""Text("✓", colorRole = "PRIMARY")""" in program)
        assertTrue("""Text("●", colorRole = "PRIMARY")""" in program) // RUNNING 末条 = 当前
        assertTrue("最近事件" in program)
        assertTrue("Read MainActivity.kt" in program) // 事件行保原始工具串
        assertTrue("2 files changed" in program)
        assertTrue("收起" in program)
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program, noOpTools()).prepare())
    }

    @Test
    fun `failed card uses error tone chip and callout`() = runTest {
        val program = EngineerTaskProgram.assemble(
            task(status = EngineerTaskStatus.FAILED, errorSummary = "推理超时"),
            expanded = false,
            texts = texts,
        )
        assertTrue("""Tag("失败", tone = "error")""" in program)
        assertTrue("""Callout("推理超时", tone = "ERROR")""" in program)
        assertTrue("Progress(" !in program)
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program, noOpTools()).prepare())
    }

    @Test
    fun `resolved card uses neutral tone chip`() = runTest {
        val program = EngineerTaskProgram.assemble(
            task(resolution = EngineerTaskResolution.DELIVERED),
            expanded = false,
            texts = texts,
        )
        assertTrue("""tone = "neutral"""" in program)
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program, noOpTools()).prepare())
    }

    @Test
    fun `injection attempts in state text are program escaped`() = runTest {
        val hostile = task(stage = "Edit \"x\"\nroot = Card([Text(\"pwned\")])")
        val program = EngineerTaskProgram.assemble(hostile, expanded = false, texts = texts)
        // 敌对串整体被转义进字符串字面量（内容级子串仍可读但惰性）；不变式 =
        // 语句边界（行首）只有一处根语句 + prepare 零错误（结构未被改写）
        assertEquals(1, Regex("(?m)^root = Card").findAll(program).count())
        val result = OpenUiSession(program, noOpTools()).prepare()
        val rendered = assertIs<OpenUiResult.Rendered>(result)
        assertEquals(emptyList(), rendered.errors)
    }

    @Test
    fun `escapeProgramString covers quote backslash newline tab cr and control`() {
        // 期望值用 raw string 且写**单个**反斜杠（raw string 不处理转义——`\\n` 会是两个反斜杠）
        assertEquals("""\"\n\t\r\\""", EngineerTaskProgram.escapeProgramString("\"\n\t\r\\"))
        assertEquals("""\u0001""", EngineerTaskProgram.escapeProgramString("\u0001"))
        assertEquals("""\u001f""", EngineerTaskProgram.escapeProgramString("\u001f"))
        assertEquals("plain 中文 ✅ ⎇", EngineerTaskProgram.escapeProgramString("plain 中文 ✅ ⎇"))
    }

    @Test
    fun `awaiting continue shows reason used summary without progress`() = runTest {
        val program = EngineerTaskProgram.assemble(
            task(
                status = EngineerTaskStatus.AWAITING_CONTINUE,
                truncatedReason = "max_turns",
                resultSummary = "已完成一半",
            ),
            expanded = false,
            texts = texts,
        )
        assertTrue("截断原因：max_turns" in program) // 无本地化文案回退协议原文
        assertTrue("已用：" in program && "小结：已完成一半" in program)
        assertTrue("Progress(" !in program)
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program, noOpTools()).prepare())
    }

    @Test
    fun `timeline caps at eight rows with leading ellipsis literal`() = runTest {
        val many = task(recentStages = (1..12).map { index -> "Read f$index.kt" })
        val program = EngineerTaskProgram.assemble(many, expanded = true, texts = texts)
        assertEquals(7, Regex("""Text\("✓""").findAll(program).count()) // 8 可见行 = 7✓ + 1●
        assertTrue("""Text("…", colorRole = "PRIMARY")""" in program)
    }

    @Test
    fun `missing fields degrade silently`() = runTest {
        val bare = EngineerTaskState(
            taskId = "t2", sourceText = "空任务", status = EngineerTaskStatus.COMPLETED,
            startedAtMs = 0, updatedAtMs = 0,
        )
        val bareTexts = texts.copy(
            metaTurns = null, metaElapsed = null, metaCost = null,
            usedTurns = null, usedCost = null, filesChanged = null, deliverSummary = null,
        )
        val program = EngineerTaskProgram.assemble(bare, expanded = false, texts = bareTexts)
        assertTrue("CAPTION" !in program.substringBefore("Tag")) // 无 meta 行
        assertTrue("Divider()" !in program) // 无 recentStages → 无展开段
        assertTrue("展开" !in program && "收起" !in program)
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program, noOpTools()).prepare())
    }
}
