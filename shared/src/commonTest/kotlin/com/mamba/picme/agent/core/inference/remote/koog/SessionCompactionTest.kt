package com.mamba.picme.agent.core.inference.remote.koog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2 滚动摘要（compaction）数据层单测（US-2.2/2.3/2.5）。
 *
 * 被测对象 [SessionCompaction] / [CompactionSlots] / [CompactionPrompt] 在 commonMain（纯函数 +
 * kotlinx-serializable，无平台依赖）。槽位 JSON 解析失败必须回退自由文本（绝不因解析阻断对话，
 * 对齐 spec §5 决策 9）。
 */
class SessionCompactionTest {

    // ── fixtures ────────────────────────────────────────────────

    private fun slots(
        intent: String = "",
        decisions: List<String> = emptyList(),
        todos: List<String> = emptyList(),
        entities: List<String> = emptyList(),
    ) = CompactionSlots(intent = intent, decisions = decisions, todos = todos, entities = entities)

    private fun compaction(
        version: Int = 1,
        slots: CompactionSlots = slots(),
        compactedUpToTurnId: Int = 0,
        createdAt: Long = 1_000L,
    ) = SessionCompaction(
        version = version,
        slots = slots,
        compactedUpToTurnId = compactedUpToTurnId,
        createdAt = createdAt,
    )

    // ── US-2.2：四槽位 JSON 解析 ───────────────────────────────

    @Test
    fun `parseSlots 合法四槽位 JSON 全字段解析`() {
        val json = """
            {"intent":"整理海边照片","decisions":["按日期分组"],"todos":["删除重复"],"entities":["media://abc123","2025-07"]}
        """.trimIndent()
        val parsed = CompactionSlots.parseSlots(json)
        assertEquals("整理海边照片", parsed.intent)
        assertEquals(listOf("按日期分组"), parsed.decisions)
        assertEquals(listOf("删除重复"), parsed.todos)
        assertEquals(listOf("media://abc123", "2025-07"), parsed.entities)
    }

    @Test
    fun `parseSlots 缺字段补默认值`() {
        val parsed = CompactionSlots.parseSlots("""{"intent":"只说了意图"}""")
        assertEquals("只说了意图", parsed.intent)
        assertTrue(parsed.decisions.isEmpty())
        assertTrue(parsed.todos.isEmpty())
        assertTrue(parsed.entities.isEmpty())
    }

    @Test
    fun `parseSlots 非 JSON 回退自由文本进 intent 槽`() {
        val parsed = CompactionSlots.parseSlots("这只是一段普通摘要文字，没有 JSON 结构。")
        assertEquals("这只是一段普通摘要文字，没有 JSON 结构。", parsed.intent)
        assertTrue(parsed.decisions.isEmpty())
        assertTrue(parsed.todos.isEmpty())
        assertTrue(parsed.entities.isEmpty())
    }

    @Test
    fun `parseSlots 槽位类型错误整体回退自由文本`() {
        // decisions 给了字符串而非数组——decode 整体失败，保守回退原文进 intent（单槽部分
        // 解析风险更高，整体回退更符合「绝不因解析阻断对话」语义）
        val json = """{"intent":"x","decisions":"oops","todos":["t1"]}"""
        val parsed = CompactionSlots.parseSlots(json)
        assertEquals(json, parsed.intent)
        assertTrue(parsed.decisions.isEmpty())
        assertTrue(parsed.todos.isEmpty())
    }

    @Test
    fun `parseSlots 空字符串回退空槽`() {
        val parsed = CompactionSlots.parseSlots("")
        assertEquals("", parsed.intent)
        assertTrue(parsed.decisions.isEmpty())
    }

    // ── US-2.2：槽位渲染（注入 system prompt 用）──────────────

    @Test
    fun `render 空槽位渲染为空串`() {
        assertEquals("", slots().render())
    }

    @Test
    fun `render 只渲染非空槽`() {
        val rendered = slots(
            intent = "整理相册",
            decisions = listOf("按日期分组"),
            entities = listOf("media://abc"),
        ).render()
        assertTrue(rendered.contains("整理相册"))
        assertTrue(rendered.contains("按日期分组"))
        assertTrue(rendered.contains("media://abc"))
        // todos 空 → 无待办行
        assertTrue(!rendered.contains("待办"))
    }

    @Test
    fun `render 多值槽每项一行`() {
        val rendered = slots(todos = listOf("任务A", "任务B")).render()
        assertTrue(rendered.lines().any { it.contains("任务A") })
        assertTrue(rendered.lines().any { it.contains("任务B") })
    }

    // ── US-2.5：增量合并 v(n+1) = old + new ───────────────────

    @Test
    fun `merge 新槽位覆盖同键旧值`() {
        val old = slots(intent = "旧意图", decisions = listOf("旧决定"))
        val new = slots(intent = "新意图", todos = listOf("新待办"))
        val merged = old.merge(new)
        assertEquals("新意图", merged.intent)       // 新覆盖旧
        assertEquals(listOf("旧决定"), merged.decisions) // 旧保留
        assertEquals(listOf("新待办"), merged.todos)     // 新增
    }

    @Test
    fun `merge 列表槽追加去重保序`() {
        val old = slots(decisions = listOf("A", "B"), entities = listOf("e1"))
        val new = slots(decisions = listOf("B", "C"), entities = listOf("e1", "e2"))
        val merged = old.merge(new)
        assertEquals(listOf("A", "B", "C"), merged.decisions)
        assertEquals(listOf("e1", "e2"), merged.entities)
    }

    @Test
    fun `merge 新空槽不覆盖旧非空`() {
        val old = slots(intent = "有意义的旧意图", decisions = listOf("D"))
        val merged = old.merge(slots()) // 新摘要全空
        assertEquals("有意义的旧意图", merged.intent)
        assertEquals(listOf("D"), merged.decisions)
    }

    // ── US-2.3：序列化往返 ─────────────────────────────────────

    @Test
    fun `序列化往返保持全字段`() {
        val original = compaction(
            version = 3,
            slots = slots("i", listOf("d"), listOf("t"), listOf("e")),
            compactedUpToTurnId = 7,
            createdAt = 99L,
        )
        val restored = SessionCompaction.decode(SessionCompaction.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun `decode 坏字符串返回 null`() {
        assertNull(SessionCompaction.decode("not-json"))
        assertNull(SessionCompaction.decode(""))
    }

    // ── US-2.1：压缩候选选择 ───────────────────────────────────

    @Test
    fun `selectCompactionCandidates 不超预算返回空`() {
        val messages = listOf(
            TestMessages.user("u1"),
            TestMessages.assistant("a1"),
        )
        val candidates = KoogMessageMemory.selectCompactionCandidates(messages, budgetTokens = 8000)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `selectCompactionCandidates 超预算选最旧对话轮`() {
        // 构造 6 个实轮（u/a 交替），每个 assistant 带长文本撑爆预算
        //（ASCII 4 字符 1 token，8000 字符 ≈ 2000 token/条 × 6 = 12000 > 预算 4000）
        val messages = mutableListOf<ai.koog.prompt.message.Message>()
        repeat(6) { i ->
            messages.add(TestMessages.user("user-$i"))
            messages.add(TestMessages.assistant("assistant-$i " + "x".repeat(8000)))
        }
        val candidates = KoogMessageMemory.selectCompactionCandidates(messages, budgetTokens = 4000)
        // 应选中若干最旧轮，且从第 0 条开始连续
        assertTrue(candidates.isNotEmpty())
        assertEquals(messages.first(), candidates.first())
    }

    @Test
    fun `selectCompactionCandidates 保护区不选`() {
        // 近 PROTECTED_RECENT_TURNS 轮即使超预算也不压
        val messages = mutableListOf<ai.koog.prompt.message.Message>()
        repeat(4) { i ->
            messages.add(TestMessages.user("user-$i"))
            messages.add(TestMessages.assistant("assistant-$i " + "x".repeat(3000)))
        }
        val candidates = KoogMessageMemory.selectCompactionCandidates(
            messages,
            budgetTokens = 2000,
            protectedRecentTurns = 2,
        )
        // 最新 2 轮（user-2/a-2, user-3/a-3）不应在候选中
        val candidateTexts = candidates.map { it.textContent() }
        assertTrue(candidateTexts.none { it.contains("user-3") })
        assertTrue(candidateTexts.none { it.contains("user-2") })
    }

    // ── US-2.6：摘要 prompt 构造 ───────────────────────────────

    @Test
    fun `buildPrompt 含旧摘要槽位与待压轮`() {
        val old = compaction(slots = slots(intent = "旧意图", decisions = listOf("旧决定")))
        val turns = listOf(TestMessages.user("新用户输入"), TestMessages.assistant("新助手回复"))
        val prompt = CompactionPrompt.build(old, turns)
        assertTrue(prompt.contains("旧意图"))
        assertTrue(prompt.contains("旧决定"))
        assertTrue(prompt.contains("新用户输入"))
        assertTrue(prompt.contains("新助手回复"))
    }

    @Test
    fun `buildPrompt 含四槽位 schema 说明`() {
        val prompt = CompactionPrompt.build(null, listOf(TestMessages.user("x")))
        assertTrue(prompt.contains("intent"))
        assertTrue(prompt.contains("decisions"))
        assertTrue(prompt.contains("todos"))
        assertTrue(prompt.contains("entities"))
    }

    @Test
    fun `buildPrompt 语义保护指令在场`() {
        val prompt = CompactionPrompt.build(null, listOf(TestMessages.user("x")))
        // US-2.6：media id / 日期 / 数字 / 否定语义
        assertTrue(prompt.contains("media://"))
        assertTrue(prompt.contains("日期") || prompt.contains("date", ignoreCase = true))
        assertTrue(prompt.contains("数字") || prompt.contains("number", ignoreCase = true))
        assertTrue(prompt.contains("不要") || prompt.contains("否定") || prompt.contains("negation", ignoreCase = true))
    }

    @Test
    fun `buildPrompt 无旧摘要时不含历史段`() {
        val prompt = CompactionPrompt.build(null, listOf(TestMessages.user("only")))
        assertTrue(!prompt.contains("上一版摘要"))
    }

    @Test
    fun `buildPrompt 有旧摘要时含历史段`() {
        val old = compaction(slots = slots(intent = "prev"))
        val prompt = CompactionPrompt.build(old, listOf(TestMessages.user("x")))
        assertTrue(prompt.contains("prev"))
    }
}

/** 测试内联的消息构造（避免跨文件依赖 M1 测试的 private fixture）。 */
private object TestMessages {
    fun user(text: String) = ai.koog.prompt.message.Message.User(
        text,
        ai.koog.prompt.message.RequestMetaInfo.Empty,
    )

    fun assistant(text: String) = ai.koog.prompt.message.Message.Assistant(
        text,
        ai.koog.prompt.message.ResponseMetaInfo.Empty,
    )
}
