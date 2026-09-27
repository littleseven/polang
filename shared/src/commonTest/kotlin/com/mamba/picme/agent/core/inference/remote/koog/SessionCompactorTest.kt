package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * M2 压缩执行器单测（US-2.1/2.3/2.5/决策 9，commonTest）。
 *
 * [SessionCompactor] 经 [SummaryGenerator] 接口（函数式）发摘要请求——测试用假 generator
 * 注入固定输出，验证触发/合并/版本/失败跳过全链路，不发真网络请求。
 */
class SessionCompactorTest {

    /** 假摘要生成器：返回预设输出（或抛异常模拟失败）。 */
    private class FakeSummaryGenerator(
        var output: String = "",
        var fail: Boolean = false,
    ) : SummaryGenerator {
        val capturedPrompts = mutableListOf<String>()

        override suspend fun generate(promptText: String): String {
            capturedPrompts.add(promptText)
            if (fail) throw RuntimeException("simulated LLM failure")
            return output
        }
    }

    /** 内存 store（复用契约测试的参考语义）。 */
    private class FakeStore : com.mamba.picme.agent.core.platform.storage.ChatMemoryStore {
        val history = mutableMapOf<String, List<Message>>()
        val summaries = mutableMapOf<String, SessionCompaction>()

        override suspend fun load(sessionId: String) = history[sessionId] ?: emptyList()
        override suspend fun save(sessionId: String, messages: List<Message>) {
            history[sessionId] = messages
        }

        override suspend fun clear(sessionId: String) {
            history.remove(sessionId)
            summaries.remove(sessionId)
        }

        override suspend fun loadSummary(sessionId: String) = summaries[sessionId]
        override suspend fun saveSummary(sessionId: String, summary: SessionCompaction) {
            summaries[sessionId] = summary
        }

        override suspend fun clearSummary(sessionId: String) {
            summaries.remove(sessionId)
        }
    }

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)
    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)

    /** 撑爆预算的长历史（6 实轮，每轮 assistant 长文本）。 */
    private fun longHistory(): List<Message> = mutableListOf<Message>().apply {
        repeat(6) { i ->
            add(user("user-$i"))
            add(assistant("assistant-$i " + "x".repeat(8000)))
        }
    }

    private fun compactor(
        store: FakeStore,
        generator: FakeSummaryGenerator,
        budgetTokens: Int = 4000,
    ) = SessionCompactor(
        store = store,
        generator = generator,
        budgetTokens = budgetTokens,
    )

    // ── US-2.1 触发 ────────────────────────────────────────────

    @Test
    fun `不超预算不触发压缩`() = runTest {
        val store = FakeStore()
        store.history["s1"] = listOf(user("短"), assistant("回复"))
        val generator = FakeSummaryGenerator(output = """{"intent":"x"}""")
        compactor(store, generator).maybeCompact("s1")
        assertTrue(generator.capturedPrompts.isEmpty(), "不超预算不应发摘要请求")
        assertNull(store.loadSummary("s1"))
    }

    @Test
    fun `超预算触发压缩并存摘要`() = runTest {
        val store = FakeStore()
        store.history["s1"] = longHistory()
        val generator = FakeSummaryGenerator(
            output = """{"intent":"整理相册","decisions":["按日期分组"],"todos":[],"entities":["media://a1"]}""",
        )
        compactor(store, generator).maybeCompact("s1")
        val summary = store.loadSummary("s1")
        assertEquals("整理相册", summary?.slots?.intent)
        assertEquals(listOf("按日期分组"), summary?.slots?.decisions)
        assertEquals(listOf("media://a1"), summary?.slots?.entities)
        assertEquals(1, summary?.version)
    }

    @Test
    fun `压缩后历史不被修改`() = runTest {
        // compaction 只产摘要，不改写历史消息（历史仍按 M1 预算组装，由 store.save 承担）
        val store = FakeStore()
        val history = longHistory()
        store.history["s1"] = history
        val generator = FakeSummaryGenerator(output = """{"intent":"x"}""")
        compactor(store, generator).maybeCompact("s1")
        assertEquals(history, store.history["s1"])
    }

    // ── US-2.5 增量合并 ────────────────────────────────────────

    @Test
    fun `二次压缩合并旧摘要且版本递增`() = runTest {
        val store = FakeStore()
        store.history["s1"] = longHistory()
        store.summaries["s1"] = SessionCompaction(
            version = 1,
            slots = CompactionSlots(intent = "旧意图", decisions = listOf("旧决定")),
            compactedUpToTurnId = 2,
            createdAt = 100L,
        )
        val generator = FakeSummaryGenerator(output = """{"intent":"新意图","todos":["新待办"]}""")
        compactor(store, generator).maybeCompact("s1")
        val summary = store.loadSummary("s1")!!
        assertEquals(2, summary.version)
        // 合并：intent 新覆盖、decisions 旧保留、todos 新增
        assertEquals("新意图", summary.slots.intent)
        assertEquals(listOf("旧决定"), summary.slots.decisions)
        assertEquals(listOf("新待办"), summary.slots.todos)
    }

    @Test
    fun `二次压缩 prompt 含旧摘要`() = runTest {
        val store = FakeStore()
        store.history["s1"] = longHistory()
        store.summaries["s1"] = SessionCompaction(
            version = 1,
            slots = CompactionSlots(intent = "旧意图"),
            compactedUpToTurnId = 2,
            createdAt = 100L,
        )
        val generator = FakeSummaryGenerator(output = """{"intent":"x"}""")
        compactor(store, generator).maybeCompact("s1")
        assertTrue(generator.capturedPrompts.single().contains("旧意图"))
    }

    // ── US-2.2 解析失败回退 ────────────────────────────────────

    @Test
    fun `摘要输出非 JSON 回退自由文本仍存`() = runTest {
        val store = FakeStore()
        store.history["s1"] = longHistory()
        val generator = FakeSummaryGenerator(output = "这是一段自由文本摘要。")
        compactor(store, generator).maybeCompact("s1")
        assertEquals("这是一段自由文本摘要。", store.loadSummary("s1")?.slots?.intent)
    }

    // ── 决策 9：失败跳过 ───────────────────────────────────────

    @Test
    fun `摘要请求失败静默跳过不留半态`() = runTest {
        val store = FakeStore()
        store.history["s1"] = longHistory()
        val generator = FakeSummaryGenerator(fail = true)
        compactor(store, generator).maybeCompact("s1")
        assertNull(store.loadSummary("s1"), "失败不应写摘要")
    }

    @Test
    fun `摘要请求失败不抛异常`() = runTest {
        val store = FakeStore()
        store.history["s1"] = longHistory()
        val generator = FakeSummaryGenerator(fail = true)
        // 不抛即通过（决策 9：绝不因记忆系统阻断对话）
        compactor(store, generator).maybeCompact("s1")
    }

    // ── compactedUpToTurnId 追踪 ───────────────────────────────

    @Test
    fun `首次压缩记录压缩到的轮序号`() = runTest {
        val store = FakeStore()
        store.history["s1"] = longHistory() // 6 实轮
        val generator = FakeSummaryGenerator(output = """{"intent":"x"}""")
        compactor(store, generator).maybeCompact("s1")
        // compactedUpToTurnId 应 > 0（有候选被压）
        assertTrue(store.loadSummary("s1")!!.compactedUpToTurnId > 0)
    }
}
