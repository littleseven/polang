package com.mamba.picme.features.cloudagent

import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.data.remote.picme.CloudFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudAgentTurnRunnerTest {

    /** 假客户端：frames 脚本化，记录 postMessage/postToolResult 调用。 */
    private class FakeApi : CloudAgentApi {
        var sentByokKey: String? = null
        var sentGenUiFormat: String? = null
        var failMessage: Throwable? = null
        val toolResults = mutableListOf<Triple<String, Boolean, String>>()
        var framesScript: ((CloudFrame) -> Unit) -> Unit = { onFrame -> onFrame(CloudFrame.Done("running")) }

        override suspend fun createSession(token: String, channel: String, model: String, byokBaseUrl: String) =
            Result.success(JSONObject().put("session_id", "s_1"))
        override suspend fun getEvents(token: String, sessionId: String, after: Long, limit: Int) =
            Result.success(emptyList<JSONObject>())
        override suspend fun finish(token: String, sessionId: String) = Result.success(Unit)
        override suspend fun postMessage(
            token: String,
            sessionId: String,
            content: String,
            byokApiKey: String?,
            genUiFormat: String,
            onFrame: (CloudFrame) -> Unit,
        ): Result<Unit> {
            sentByokKey = byokApiKey
            sentGenUiFormat = genUiFormat
            failMessage?.let { return Result.failure(it) }
            framesScript(onFrame)
            return Result.success(Unit)
        }
        override suspend fun postToolResult(token: String, sessionId: String, requestId: String, ok: Boolean, result: String): Result<Unit> {
            toolResults.add(Triple(requestId, ok, result))
            return Result.success(Unit)
        }
    }

    private fun assistantEvent(text: String) = CloudFrame.Event(
        JSONObject("""{"id":1,"type":"message","source":"agent","role":"assistant","payload":{"type":"message","content":"$text"}}"""),
    )

    private fun newRunner(api: FakeApi, executor: DeviceToolExecutor = DeviceToolExecutor(
        gallerySummary = { "相册 42 张" },
        searchMedia = { _, _, _, _, _, _ -> "找到 0 张" },
        refineSearch = { _, _, _ -> "error: no base" },
    )) = CloudAgentTurnRunner(api, executor, CoroutineScope(UnconfinedTestDispatcher()))

    @Test
    fun `assistant text and done frames are forwarded as events`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(assistantEvent("你有 42 张照片"))
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        val result = newRunner(api).runTurn("token", "s_1", "相册多少张", null) { events.add(it) }
        assertTrue(result.isSuccess)
        assertEquals(
            listOf(CloudTurnEvent.AssistantText("你有 42 张照片"), CloudTurnEvent.Done),
            events,
        )
    }

    @Test
    fun `user echo event is skipped`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":1,"type":"message","source":"user","role":"user","payload":{"type":"message","content":"hi"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.Done), events)
    }

    @Test
    fun `tool request executes on device and posts result back`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(CloudFrame.ToolRequest("tr_1", "get_gallery_summary", JSONObject(), 120_000L))
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "相册多少张", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.ToolCall("get_gallery_summary"), CloudTurnEvent.Done), events)
        assertEquals(listOf(Triple("tr_1", true, "相册 42 张")), api.toolResults)
    }

    @Test
    fun `tool error result is posted with ok false`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(CloudFrame.ToolRequest("tr_2", "refine_media_search", JSONObject(), 120_000L))
            onFrame(CloudFrame.Done("running"))
        }
        newRunner(api).runTurn("token", "s_1", "再看一眼", null) {}
        assertEquals(listOf(Triple("tr_2", false, "error: no base")), api.toolResults)
    }

    @Test
    fun `html card event is forwarded with html and summary`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":2,"type":"html_card","source":"environment","role":"tool","payload":{"type":"html_card","html":"<p>hello</p>","summary":"一张问候卡"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画张卡片", null) { events.add(it) }
        assertEquals(
            listOf(CloudTurnEvent.HtmlCard("<p>hello</p>", "一张问候卡"), CloudTurnEvent.Done),
            events,
        )
    }

    @Test
    fun `html card with blank html is dropped`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":3,"type":"html_card","source":"environment","role":"tool","payload":{"type":"html_card","html":"","summary":"空卡"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画张卡片", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.Done), events)
    }

    @Test
    fun `error frame is forwarded as error event`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame -> onFrame(CloudFrame.Error("quota exhausted")) }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.Error("quota exhausted")), events)
    }

    @Test
    fun `blank byok key is not sent`() = runTest {
        val api = FakeApi()
        newRunner(api).runTurn("token", "s_1", "hi", "  ") {}
        assertNull(api.sentByokKey)
    }

    @Test
    fun `non-blank byok key is passed through`() = runTest {
        val api = FakeApi()
        newRunner(api).runTurn("token", "s_1", "hi", "sk-xyz") {}
        assertEquals("sk-xyz", api.sentByokKey)
    }

    @Test
    fun `postMessage failure propagates as failure result`() = runTest {
        val api = FakeApi()
        api.failMessage = RuntimeException("HTTP 500: boom")
        val result = newRunner(api).runTurn("token", "s_1", "hi", null) {}
        assertTrue(result.isFailure)
        assertEquals("HTTP 500: boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `render_ui action frame emits a2ui card event`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject(
                        """{"id":2,"type":"action","source":"agent","role":"assistant","payload":{"tool":"render_ui","argsJson":"{\"surfaceId\":\"sf-1\",\"messagesJson\":\"[{}]\",\"fallbackSummary\":\"327 张\"}"}}"""
                    ),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画个图", null) { events.add(it) }
        val card = events.filterIsInstance<CloudTurnEvent.A2uiCard>().single()
        assertEquals("sf-1", card.surfaceId)
        assertEquals("[{}]", card.messagesJson)
        assertEquals("327 张", card.fallbackSummary)
    }

    @Test
    fun `action frame of other tool emits StepStarted without card`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":3,"type":"action","source":"agent","role":"assistant","payload":{"tool":"search_media","argsJson":"{}"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.StepStarted(3L, "search_media"), CloudTurnEvent.Done), events)
    }

    @Test
    fun `render_ui_openui action frame emits openui card event`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject(
                        """{"id":4,"type":"action","source":"agent","role":"assistant",""" +
                            """"payload":{"tool":"render_ui_openui","argsJson":"{\"program\":\"root = Card([])\",\"fallbackSummary\":\"327 张\"}"}}"""
                    ),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画个图", null) { events.add(it) }
        val card = events.filterIsInstance<CloudTurnEvent.OpenUiCard>().single()
        assertEquals("root = Card([])", card.program)
        assertEquals("327 张", card.fallbackSummary)
    }

    @Test
    fun `openui delta frame emits card delta event`() = runTest {
        // openui P2 流式预览：openui_delta 帧 → OpenUiCardDelta 上屏事件（不落库）
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(CloudFrame.OpenUiDelta("root = Card(["))
            onFrame(CloudFrame.OpenUiDelta("root = Card([header])"))
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画个图", null) { events.add(it) }
        assertEquals(
            listOf(
                CloudTurnEvent.OpenUiCardDelta("root = Card(["),
                CloudTurnEvent.OpenUiCardDelta("root = Card([header])"),
                CloudTurnEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun `render_ui_openui action frame with malformed argsJson emits StepStarted but no card`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":5,"type":"action","source":"agent","role":"assistant","payload":{"tool":"render_ui_openui","argsJson":"not-json"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.StepStarted(5L, "render_ui_openui"), CloudTurnEvent.Done), events)
    }

    @Test
    fun `action event emits StepStarted and observation emits StepFinished`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":11,"type":"action","source":"agent","role":"assistant","payload":{"type":"action","tool":"search_media","argsJson":"{}"}}"""),
                ),
            )
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":12,"type":"observation","source":"environment","role":"tool","payload":{"type":"observation","actionEventId":11,"content":"找到 3 张"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "搜照片", null) { events.add(it) }
        assertEquals(
            listOf(
                CloudTurnEvent.StepStarted(11L, "search_media"),
                CloudTurnEvent.StepFinished(11L, true),
                CloudTurnEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun `observation with error content emits StepFinished ok=false`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":21,"type":"observation","source":"environment","role":"tool","payload":{"type":"observation","actionEventId":20,"content":"error: timeout"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.StepFinished(20L, false), CloudTurnEvent.Done), events)
    }

    @Test
    fun `orphan observation with actionEventId 0 is skipped`() = runTest {
        // 服务端渲染门控例行产生的孤儿 observation（actionEventId=0）不上屏
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":31,"type":"observation","source":"environment","role":"tool","payload":{"type":"observation","actionEventId":0,"content":"渲染门控"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.Done), events)
    }

    @Test
    fun `render_ui action emits both StepStarted and A2uiCard`() = runTest {
        // 发卡工具同样先发 StepStarted（在卡事件之前），A2uiCard 行为不回归
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject(
                        """{"id":2,"type":"action","source":"agent","role":"assistant","payload":{"tool":"render_ui","argsJson":"{\"surfaceId\":\"sf-1\",\"messagesJson\":\"[{}]\",\"fallbackSummary\":\"327 张\"}"}}"""
                    ),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画个图", null) { events.add(it) }
        assertEquals(
            listOf(
                CloudTurnEvent.StepStarted(2L, "render_ui"),
                CloudTurnEvent.A2uiCard("sf-1", "[{}]", "327 张"),
                CloudTurnEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun `genui format is passed through to postMessage`() = runTest {
        val api = FakeApi()
        newRunner(api).runTurn("token", "s_1", "hi", null, genUiFormat = "openui") {}
        assertEquals("openui", api.sentGenUiFormat)
    }
}
