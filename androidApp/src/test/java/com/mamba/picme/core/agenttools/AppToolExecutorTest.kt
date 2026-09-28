package com.mamba.picme.core.agenttools

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppToolExecutorTest {

    private fun executor(
        logs: String = "2026-08-01 I PoLang:Tag: hello",
        crash: String? = null,
        history: List<Pair<String, String>> = listOf("user" to "之前的问题"),
        state: JSONObject = JSONObject().put("appVersion", "1.0"),
        gallery: JSONObject = JSONObject().put("total", 42),
    ) = AppToolExecutor(
        logProvider = { logs },
        crashTraceReader = { crash },
        chatHistoryLoader = { _, limit -> history.take(limit) },
        runtimeStateProvider = RuntimeStateProvider { state },
        gallerySummaryLoader = { gallery },
    )

    @Test
    fun `get_logs returns sanitized lines`() = runTest {
        val out = executor(logs = "mail me a@b.com\nline2").execute(AppTool.GET_LOGS, JSONObject())
        assertFalse(out.getBoolean("empty"))
        val text = out.getString("logs")
        assertTrue(text.contains("<email>"))
        assertTrue(text.contains("line2"))
    }

    @Test
    fun `get_logs respects filter and lines cap`() = runTest {
        val out = executor(logs = "TagA x\nOther y").execute(
            AppTool.GET_LOGS, JSONObject().put("filter", "TagA"),
        )
        assertEquals("TagA x", out.getString("logs"))
        val many = (1..600).joinToString("\n") { "line$it" }
        val capped = executor(logs = many).execute(AppTool.GET_LOGS, JSONObject().put("lines", 10))
        assertEquals(10, capped.getString("logs").split("\n").size)
    }

    @Test
    fun `crash trace empty when none`() = runTest {
        val out = executor(crash = null).execute(AppTool.GET_CRASH_TRACE, JSONObject())
        assertTrue(out.getBoolean("empty"))
        assertEquals("no_crash_trace", out.getString("reason"))
    }

    @Test
    fun `chat history limited to 50`() = runTest {
        val history = (1..60).map { "user" to "msg$it" }
        val out = executor(history = history).execute(
            AppTool.GET_CHAT_HISTORY, JSONObject().put("limit", 100),
        )
        assertEquals(50, out.getJSONArray("messages").length())
    }

    @Test
    fun `runtime state and gallery summary pass through`() = runTest {
        val out = executor().execute(AppTool.GET_RUNTIME_STATE, JSONObject())
        assertEquals("1.0", out.getString("appVersion"))
        val g = executor().execute(AppTool.GET_GALLERY_SUMMARY, JSONObject())
        assertEquals(42, g.getInt("total"))
    }

    @Test
    fun `payload truncated at 32KB with flag`() = runTest {
        val big = "x".repeat(40 * 1024)
        val out = executor(logs = big).execute(AppTool.GET_LOGS, JSONObject())
        assertTrue(out.toString().length <= AppToolExecutor.MAX_PAYLOAD_BYTES + 256)
        assertTrue(out.getBoolean("truncated"))
    }

    @Test
    fun `chat history truncation keeps messages as JSONArray`() = runTest {
        // 50 条超长消息（~100KB）超 32KB 预算：截断必须裁剪条目数，而非把 JSONArray 腐蚀成字符串
        val history = (1..50).map { "agent" to "x".repeat(2_000) }
        val out = executor(history = history).execute(
            AppTool.GET_CHAT_HISTORY, JSONObject().put("limit", 50),
        )
        assertTrue(out.getBoolean("truncated"))
        val messages = out.optJSONArray("messages")
        assertTrue("messages 截断后必须仍是 JSONArray", messages != null)
        assertTrue(messages!!.length() < 50)
        assertTrue(out.toString().length <= AppToolExecutor.MAX_PAYLOAD_BYTES + 256)
    }

    @Test
    fun `logs empty when no match`() = runTest {
        val out = executor(logs = "Other y").execute(
            AppTool.GET_LOGS, JSONObject().put("filter", "Nope"),
        )
        assertTrue(out.getBoolean("empty"))
    }
}
