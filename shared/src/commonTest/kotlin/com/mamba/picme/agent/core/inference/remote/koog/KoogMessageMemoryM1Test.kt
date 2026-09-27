package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1 预算制组装 + 工具瘦身单测（spec《Chat Agent 分层记忆系统》US-1.1~1.4）。
 *
 * - US-1.1 token 预算制：[KoogMessageMemory.trimToTokenBudget] / [KoogMessageMemory.estimateTokens]
 * - US-1.2 tool result 老化：[KoogMessageMemory.ageToolResults]
 * - US-1.3 三不变式保持：装配产物配对完整（sanitize 幂等）
 * - US-1.4 裁剪顺序：旧 tool 块先丢、保护轮最后丢、最新块永不丢
 *
 * token 估算口径（确定性启发式）：CJK 字符 1 token/字，其余字符 4 字符 1 token；
 * 消息固定开销 4 token；Text part 按文本估，Call/Result 按 tool 名 + 参数/内容估。
 */
class KoogMessageMemoryM1Test {

    // ── fixtures（与 KoogMessageMemoryTest 同构）───────────────

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)
    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)
    private fun system(text: String) = Message.System(text, RequestMetaInfo.Empty)

    private fun toolCall(id: String, tool: String = "t", args: String = "{}") =
        Message.Assistant(
            listOf(MessagePart.Tool.Call(id, tool, args)),
            ResponseMetaInfo.Empty,
        )

    private fun toolResult(id: String, tool: String = "t", content: String = "ok") =
        Message.User(
            listOf(MessagePart.Tool.Result(id, tool, content)),
            RequestMetaInfo.Empty,
        )

    private fun contentOf(message: Message): String = message.textContent()

    private fun resultContents(messages: List<Message>): List<String> =
        messages.flatMap { message ->
            (message as? Message.User)?.parts
                ?.filterIsInstance<MessagePart.Tool.Result>()
                ?.map { part -> part.output }
                ?: emptyList()
        }

    private fun allCallIds(messages: List<Message>): List<String?> =
        messages.flatMap { message ->
            (message as? Message.Assistant)?.parts
                ?.filterIsInstance<MessagePart.Tool.Call>()
                ?.map { part -> part.id }
                ?: emptyList()
        }

    /** 400 个 ASCII 字符 → 100 token + 消息开销 4 = 104 token。 */
    private fun bigUser(marker: String) = user(marker + "x".repeat(400 - marker.length))

    // ── US-1.1 token 估算 ─────────────────────────────────────

    @Test
    fun `estimateTokens 空串为0`() {
        assertEquals(0, KoogMessageMemory.estimateTokens(""))
    }

    @Test
    fun `estimateTokens 纯ASCII按四字符一token向上取整`() {
        assertEquals(2, KoogMessageMemory.estimateTokens("abcdefgh"))   // 8/4
        assertEquals(3, KoogMessageMemory.estimateTokens("hello world")) // ceil(11/4)
    }

    @Test
    fun `estimateTokens CJK每字一token`() {
        assertEquals(2, KoogMessageMemory.estimateTokens("你好"))
        assertEquals(3, KoogMessageMemory.estimateTokens("你好abc")) // 2 CJK + ceil(3/4)
    }

    @Test
    fun `estimateMessageTokens 文本消息为开销加文本`() {
        // 4（开销）+ 2（"abcdefgh"）= 6
        assertEquals(6, KoogMessageMemory.estimateMessageTokens(user("abcdefgh")))
    }

    @Test
    fun `estimateMessageTokens tool结果按tool名加内容估`() {
        // 4（开销）+ 1（tool "t"）+ 25（100 ASCII 字符）= 30
        assertEquals(30, KoogMessageMemory.estimateMessageTokens(toolResult("c1", "t", "x".repeat(100))))
    }

    // ── US-1.2 tool result 老化 ───────────────────────────────

    @Test
    fun `ageToolResults 旧轮长result被替换为占位`() {
        val originalOutput = "x".repeat(400) + "TAIL_MARKER"
        val oldResult = toolResult("c1", "gallery_search", originalOutput)
        val messages = listOf(
            user("第一轮问题"),
            toolCall("c1", "gallery_search"),
            oldResult,
            assistant("第一轮回答"),
            user("第二轮问题"),
            assistant("第二轮回答"),
            user("第三轮问题"),
            assistant("第三轮回答"),
        )
        val aged = KoogMessageMemory.ageToolResults(messages, keepRecentTurns = 2)
        val contents = resultContents(aged)
        assertEquals(1, contents.size)
        assertTrue(contents[0].startsWith("[tool:gallery_search"), "占位须带 tool 名：${contents[0]}")
        assertTrue(!contents[0].contains("TAIL_MARKER"), "占位不得保留原文尾部")
        assertTrue(contents[0].length < originalOutput.length, "占位必须短于原文")
    }

    @Test
    fun `ageToolResults 最近两轮result保持原文`() {
        val recentResult = toolResult("c2", "t", "x".repeat(400))
        val messages = listOf(
            user("第一轮问题"),
            toolCall("c2", "t"),
            recentResult,
            assistant("第一轮回答"),
            user("第二轮问题"),
            toolCall("c3", "t"),
            toolResult("c3", "t", "y".repeat(400)),
            assistant("第二轮回答"),
        )
        val aged = KoogMessageMemory.ageToolResults(messages, keepRecentTurns = 2)
        // 最近 2 轮 = 第二轮与…第一轮也算（总实轮数=2）→ 都不老化
        assertEquals(listOf("x".repeat(400), "y".repeat(400)), resultContents(aged))
    }

    @Test
    fun `ageToolResults 短result不替换`() {
        val messages = listOf(
            user("第一轮"),
            toolCall("c1"),
            toolResult("c1", "t", "ok"),
            assistant("答"),
            user("第二轮"),
            assistant("答"),
            user("第三轮"),
            assistant("答"),
        )
        val aged = KoogMessageMemory.ageToolResults(messages, keepRecentTurns = 2)
        assertEquals(listOf("ok"), resultContents(aged))
    }

    @Test
    fun `ageToolResults 实轮数不足keepRecentTurns时不老化`() {
        val messages = listOf(
            user("唯一一轮"),
            toolCall("c1"),
            toolResult("c1", "t", "x".repeat(400)),
            assistant("答"),
        )
        val aged = KoogMessageMemory.ageToolResults(messages, keepRecentTurns = 2)
        assertEquals(listOf("x".repeat(400)), resultContents(aged))
    }

    @Test
    fun `ageToolResults Call与对话消息原样`() {
        val messages = listOf(
            user("第一轮"),
            toolCall("c1", "gallery_search"),
            toolResult("c1", "gallery_search", "x".repeat(400)),
            assistant("答一"),
            user("第二轮"),
            assistant("答二"),
            user("第三轮"),
            assistant("答三"),
        )
        val aged = KoogMessageMemory.ageToolResults(messages, keepRecentTurns = 2)
        assertEquals(8, aged.size)
        assertEquals(listOf("c1"), allCallIds(aged))
        assertEquals("第一轮", contentOf(aged[0]))
        assertEquals("答一", contentOf(aged[3]))
        assertEquals("第三轮", contentOf(aged[6]))
    }

    // ── US-1.1/1.4 预算裁剪 ───────────────────────────────────

    @Test
    fun `trimToTokenBudget 预算内原样返回`() {
        val messages = listOf(user("u1"), assistant("a1"))
        assertEquals(messages, KoogMessageMemory.trimToTokenBudget(messages, budgetTokens = 100))
    }

    @Test
    fun `trimToTokenBudget 超预算从最旧丢`() {
        // 每条 104 token；预算 210 → 只保最近 2 条（208 ≤ 210，3 条 312 > 210）
        val messages = listOf(bigUser("u1"), bigUser("u2"), bigUser("u3"), bigUser("u4"))
        val trimmed = KoogMessageMemory.trimToTokenBudget(messages, budgetTokens = 210)
        assertEquals(listOf("u3", "u4"), trimmed.map { contentOf(it) }.map { it.take(2) })
    }

    @Test
    fun `trimToTokenBudget 旧tool块先于旧对话被丢`() {
        // 旧对话 104 + 旧 tool 块（约 511）+ 新两轮各 104；预算 320：
        // 先丢旧 tool 块（牺牲旧工具输出、保对话）→ 312 ≤ 320
        val messages = listOf(
            bigUser("u1"),
            toolCall("c1", "t"),
            toolResult("c1", "t", "x".repeat(2000)),
            bigUser("u3"),
            bigUser("u4"),
        )
        val trimmed = KoogMessageMemory.trimToTokenBudget(messages, budgetTokens = 320)
        assertTrue(allCallIds(trimmed).isEmpty(), "旧 tool 块应先被丢")
        assertEquals(listOf("u1", "u3", "u4"), trimmed.map { contentOf(it).take(2) })
    }

    @Test
    fun `trimToTokenBudget tool块整块保留不拆散`() {
        // 两个 tool 块 + 1 条对话；预算只够丢最旧 tool 块 → 新 tool 块 Call+Result 相邻共存
        val bigContent = "x".repeat(2000)
        val messages = listOf(
            toolCall("old", "t"),
            toolResult("old", "t", bigContent),
            toolCall("new", "t"),
            toolResult("new", "t", bigContent),
            bigUser("u"),
        )
        val trimmed = KoogMessageMemory.trimToTokenBudget(messages, budgetTokens = 620)
        val callIdx = trimmed.indexOfFirst { m -> (m as? Message.Assistant)?.parts?.any { it is MessagePart.Tool.Call } == true }
        val resultIdx = trimmed.indexOfFirst { m -> (m as? Message.User)?.parts?.any { it is MessagePart.Tool.Result } == true }
        assertTrue(callIdx >= 0 && resultIdx >= 0, "新 tool 块应保留")
        assertEquals(callIdx + 1, resultIdx, "Call 与 Result 必须相邻（同块不拆散）")
        assertEquals("new", allCallIds(trimmed).first())
    }

    @Test
    fun `trimToTokenBudget 保护轮最后才丢且最新块永不丢`() {
        // 5 条实轮各 104；protectedRecentTurns=3 保护 u3/u4/u5；预算 210：
        // passB 丢 u1/u2（312 仍超）→ passC 丢 u3（208 达标）→ 保 [u4, u5]
        val messages = listOf(
            bigUser("u1"), bigUser("u2"), bigUser("u3"), bigUser("u4"), bigUser("u5"),
        )
        val trimmed = KoogMessageMemory.trimToTokenBudget(
            messages, budgetTokens = 210, protectedRecentTurns = 3,
        )
        assertEquals(listOf("u4", "u5"), trimmed.map { contentOf(it).take(2) })
    }

    @Test
    fun `trimToTokenBudget 单块超预算时最新块仍保留`() {
        val messages = listOf(bigUser("u1"), bigUser("u2"))
        val trimmed = KoogMessageMemory.trimToTokenBudget(messages, budgetTokens = 10)
        assertEquals(listOf("u2"), trimmed.map { contentOf(it).take(2) })
    }

    @Test
    fun `trimToTokenBudget System计入预算且永在首位`() {
        val messages = listOf(
            system("SYS"),
            bigUser("u1"), bigUser("u2"), bigUser("u3"),
        )
        val trimmed = KoogMessageMemory.trimToTokenBudget(messages, budgetTokens = 220)
        assertTrue(trimmed.first() is Message.System)
        // SYS(约 4+1=5) + u3(104) = 109 ≤ 220；u2 加入则 213 ≤ 220？ → 再 u1 317 > 220
        assertEquals(listOf("u2", "u3"), trimmed.drop(1).map { contentOf(it).take(2) })
    }

    // ── US-1.3 全管线装配 ─────────────────────────────────────

    @Test
    fun `assembleForPersistence 剔System老化旧result且不超条数上限`() {
        val messages = mutableListOf<Message>(system("SYS"))
        // 3 轮：第 1 轮带大 tool result
        messages += user("第一轮")
        messages += toolCall("c1", "gallery_search")
        messages += toolResult("c1", "gallery_search", "x".repeat(4000))
        messages += assistant("答一")
        repeat(2) { i ->
            messages += user("第${i + 2}轮")
            messages += assistant("答${i + 2}")
        }
        val assembled = KoogMessageMemory.assembleForPersistence(messages)
        assertTrue(assembled.none { it is Message.System })
        assertTrue(assembled.size <= KoogMessageMemory.MAX_MESSAGES)
        val contents = resultContents(assembled)
        assertEquals(1, contents.size)
        assertTrue(contents[0].startsWith("[tool:gallery_search"), "旧 result 应被老化")
    }

    @Test
    fun `assembleForPersistence 产物配对完整`() {
        val messages = listOf(
            user("第一轮"),
            toolCall("c1"),
            toolResult("c1", "t", "x".repeat(400)),
            assistant("答一"),
            user("第二轮"),
            assistant("答二"),
            user("第三轮"),
            assistant("答三"),
        )
        val assembled = KoogMessageMemory.assembleForPersistence(messages)
        // 不变式③幂等：产物上再跑 sanitize 应无变化（无悬空 Call/Result）
        assertEquals(assembled, KoogMessageMemory.sanitizeToolPairing(assembled))
    }
}
