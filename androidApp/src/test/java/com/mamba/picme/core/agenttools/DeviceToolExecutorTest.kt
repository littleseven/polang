package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceToolExecutorTest {

    private fun executor(
        gallery: suspend () -> String = { "相册 1000 张" },
        search: suspend (String, String, String, String, String) -> String = { q, _, _, _, _ -> "搜索[$q] → 3 张" },
        refine: suspend (String, String, String) -> String = { c, _, _ -> "细化[$c] → 1 张" },
    ) = DeviceToolExecutor(gallerySummary = gallery, searchMedia = search, refineSearch = refine)

    @Test
    fun `maps three tool names to handlers`() = runTest {
        val exec = executor()
        assertEquals("相册 1000 张", exec.execute("get_gallery_summary", JSONObject()))
        assertEquals("搜索[猫] → 3 张", exec.execute("search_media", JSONObject().put("query", "猫")))
        assertEquals("细化[夜景] → 1 张", exec.execute("refine_media_search", JSONObject().put("constraint", "夜景")))
    }

    @Test
    fun `unknown tool returns error text`() = runTest {
        assertEquals("error: unknown_tool:delete_media", executor().execute("delete_media", JSONObject()))
    }

    @Test
    fun `handler exception becomes error text`() = runTest {
        val exec = executor(gallery = { throw IllegalStateException("db locked") })
        assertEquals("error: db locked", exec.execute("get_gallery_summary", JSONObject()))
    }

    @Test
    fun `result over limit is truncated`() = runTest {
        val big = "x".repeat(CloudAgentToolContract.MAX_RESULT_CHARS + 500)
        val exec = executor(gallery = { big })
        val out = exec.execute("get_gallery_summary", JSONObject())
        assertTrue(out.length <= CloudAgentToolContract.MAX_RESULT_CHARS + 20)
        assertTrue(out.endsWith("[truncated]"))
    }

    @Test
    fun `search_media passes all five args`() = runTest {
        var captured: List<String>? = null
        val exec = executor(search = { q, p, f, t, h -> captured = listOf(q, p, f, t, h); "ok" })
        exec.execute(
            "search_media",
            JSONObject().put("query", "猫").put("person", "儿子").put("fromMs", "100").put("toMs", "200").put("hasFace", "true"),
        )
        assertEquals(listOf("猫", "儿子", "100", "200", "true"), captured)
    }
}
