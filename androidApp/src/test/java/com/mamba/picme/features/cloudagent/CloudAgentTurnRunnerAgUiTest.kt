package com.mamba.picme.features.cloudagent

import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.data.remote.picme.CloudFrame
import com.mamba.picme.domain.chat.streaming.AgUiFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2（AG-UI）帧映射 + v1 回落（spec《2026-11-28-ag-ui-migration-design》M2）。
 * v1 kind 帧路径见 [CloudAgentTurnRunnerTest]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudAgentTurnRunnerAgUiTest {

    /** 假客户端：v2 脚本化；v2FramesScript 为 null 时模拟老服务端路由缺失（404 无 error 体 → 回落 v1）。 */
    private class FakeApi : CloudAgentApi {
        val toolResults = mutableListOf<Triple<String, Boolean, String>>()
        var framesScript: ((CloudFrame) -> Unit) -> Unit = { onFrame -> onFrame(CloudFrame.Done("running")) }
        var v1CallCount = 0
        var v2FramesScript: (((AgUiFrame) -> Unit) -> Unit)? = null
        var v2Failure: Throwable = RuntimeException("HTTP 404: Not Found")
        var v2CallCount = 0
        var agUiSupported = true
        var markedUnsupported = false

        override val agUiEndpointSupported: Boolean get() = agUiSupported

        override fun markAgUiEndpointUnsupported() {
            markedUnsupported = true
            agUiSupported = false
        }

        override suspend fun createSession(token: String, channel: String, model: String, byokBaseUrl: String) =
            Result.success(JSONObject().put("session_id", "s_1"))
        override suspend fun getEvents(token: String, sessionId: String, after: Long, limit: Int) =
            Result.success(emptyList<JSONObject>())
        override suspend fun getFinalProgram(token: String, sessionId: String) = Result.success(JSONObject())
        override suspend fun finish(token: String, sessionId: String) = Result.success(Unit)
        override suspend fun postMessage(
            token: String,
            sessionId: String,
            content: String,
            byokApiKey: String?,
            replyLanguage: String?,
            onFrame: (CloudFrame) -> Unit,
        ): Result<Unit> {
            v1CallCount++
            framesScript(onFrame)
            return Result.success(Unit)
        }
        override suspend fun postMessageV2(
            token: String,
            sessionId: String,
            content: String,
            byokApiKey: String?,
            replyLanguage: String?,
            onFrame: (AgUiFrame) -> Unit,
        ): Result<Unit> {
            v2CallCount++
            val script = v2FramesScript ?: return Result.failure(v2Failure)
            script(onFrame)
            return Result.success(Unit)
        }
        override suspend fun postToolResult(token: String, sessionId: String, requestId: String, ok: Boolean, result: String): Result<Unit> {
            toolResults.add(Triple(requestId, ok, result))
            return Result.success(Unit)
        }
    }

    private fun newRunner(api: FakeApi) = CloudAgentTurnRunner(
        api,
        DeviceToolExecutor(
            gallerySummary = { "相册 42 张" },
            searchMedia = { _, _, _, _, _, _ -> "找到 0 张" },
            refineSearch = { _, _, _ -> "error: no base" },
            runGalleryScript = { _ -> "{}" },
        ),
        CoroutineScope(UnconfinedTestDispatcher()),
    )

    private fun v2TextBlock(messageId: String, role: String, vararg chunks: String): List<AgUiFrame> =
        buildList {
            add(AgUiFrame.TextMessageStart(messageId, role))
            chunks.forEach { add(AgUiFrame.TextMessageContent(messageId, it)) }
            add(AgUiFrame.TextMessageEnd(messageId))
        }

    private fun v2RunFinished() = AgUiFrame.RunFinished("s_1", "run-abc", metadata = null)

    @Test
    fun `v2 assistant text block aggregates to AssistantText and v1 is not called`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            onFrame(AgUiFrame.RunStarted("s_1", "run-abc"))
            v2TextBlock("m1", "assistant", "你有 42 张照片").forEach(onFrame)
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        val result = newRunner(api).runTurn("token", "s_1", "相册多少张", null) { events.add(it) }
        assertTrue(result.isSuccess)
        assertEquals(listOf(CloudTurnEvent.AssistantText("你有 42 张照片"), CloudTurnEvent.Done()), events)
        assertEquals(1, api.v2CallCount)
        assertEquals(0, api.v1CallCount)
    }

    @Test
    fun `v2 multi-block assistant texts are joined in order`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            v2TextBlock("m1", "assistant", "第一段").forEach(onFrame)
            v2TextBlock("m2", "assistant", "第二段").forEach(onFrame)
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(
            listOf(CloudTurnEvent.AssistantText("第一段"), CloudTurnEvent.AssistantText("第二段"), CloudTurnEvent.Done()),
            events,
        )
    }

    @Test
    fun `v2 user role block is skipped`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            v2TextBlock("u1", "user", "hi").forEach(onFrame)
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.Done()), events)
    }

    @Test
    fun `v2 streamed program chunks emit delta then OpenUiCard`() = runTest {
        val api = FakeApi()
        val program = "// summary: 统计\nroot = Card([Text(\"hi\")])"
        api.v2FramesScript = { onFrame ->
            v2TextBlock("stream-1", "assistant", "// summary: 统计\nroot = Card([", "Text(\"hi\")])").forEach(onFrame)
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画个图", null) { events.add(it) }
        assertEquals(
            listOf(
                CloudTurnEvent.OpenUiCardDelta(program),
                CloudTurnEvent.OpenUiCard(program, "统计"),
                CloudTurnEvent.Done(),
            ),
            events,
        )
    }

    @Test
    fun `v2 single chunk program emits OpenUiCard without delta`() = runTest {
        val api = FakeApi()
        val program = "// summary: 统计\nroot = Card([Text(\"hi\")])"
        api.v2FramesScript = { onFrame ->
            v2TextBlock("m1", "assistant", program).forEach(onFrame)
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画个图", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.OpenUiCard(program, "统计"), CloudTurnEvent.Done()), events)
    }

    @Test
    fun `v2 multi-chunk plain text does not emit program delta`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            v2TextBlock("m1", "assistant", "你好，", "这是回答。").forEach(onFrame)
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.AssistantText("你好，这是回答。"), CloudTurnEvent.Done()), events)
    }

    @Test
    fun `v2 tool call triad emits StepStarted and StepFinished`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            onFrame(AgUiFrame.ToolCallStart("act-11", "search_media"))
            onFrame(AgUiFrame.ToolCallArgs("act-11", "{\"query\":\"猫\"}"))
            onFrame(AgUiFrame.ToolCallEnd("act-11"))
            onFrame(AgUiFrame.ToolCallResult("act-11-result", "act-11", "找到 3 张"))
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "搜照片", null) { events.add(it) }
        assertEquals(
            listOf(
                CloudTurnEvent.StepStarted(11L, "search_media"),
                CloudTurnEvent.StepFinished(11L, true),
                CloudTurnEvent.Done(),
            ),
            events,
        )
    }

    @Test
    fun `v2 orphan tool result act-0 is skipped`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            onFrame(AgUiFrame.ToolCallResult("act-0-result", "act-0", "渲染门控"))
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.Done()), events)
    }

    @Test
    fun `v2 render_ui_openui tool args emit OpenUiCard after StepStarted`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            onFrame(AgUiFrame.ToolCallStart("act-4", "render_ui_openui"))
            onFrame(AgUiFrame.ToolCallArgs("act-4", "{\"program\":\"root = Card([])\",\"fallbackSummary\":\"327 张\"}"))
            onFrame(AgUiFrame.ToolCallEnd("act-4"))
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画个图", null) { events.add(it) }
        assertEquals(
            listOf(
                CloudTurnEvent.StepStarted(4L, "render_ui_openui"),
                CloudTurnEvent.OpenUiCard("root = Card([])", "327 张"),
                CloudTurnEvent.Done(),
            ),
            events,
        )
    }

    @Test
    fun `v2 device tool request custom frame executes and posts result`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            onFrame(
                AgUiFrame.Custom(
                    "device_tool_request",
                    buildJsonObject {
                        put("request_id", "tr_1")
                        put("tool", "get_gallery_summary")
                        put("args", buildJsonObject {})
                        put("timeout_ms", 120000)
                    },
                ),
            )
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "相册多少张", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.ToolCall("get_gallery_summary"), CloudTurnEvent.Done()), events)
        assertEquals(listOf(Triple("tr_1", true, "相册 42 张")), api.toolResults)
    }

    @Test
    fun `v2 run finished metadata maps to done usage`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            onFrame(
                AgUiFrame.RunFinished(
                    "s_1",
                    "run-abc",
                    buildJsonObject {
                        put("prompt_tokens", 1280)
                        put("completion_tokens", 96)
                        put("elapsed_ms", 8432)
                        put("openui_lang_version", 2)
                    },
                ),
            )
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(
            listOf(CloudTurnEvent.Done(promptTokens = 1280, completionTokens = 96, elapsedMs = 8432, langVersion = 2)),
            events,
        )
    }

    @Test
    fun `v2 run error maps to error event`() = runTest {
        val api = FakeApi()
        api.v2FramesScript = { onFrame -> onFrame(AgUiFrame.RunError("quota exhausted", code = null)) }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.Error("quota exhausted")), events)
    }

    @Test
    fun `v2 rewritten stream block is replaced by evt final block single card`() = runTest {
        // 改写场景（ensureValidProgram normalize 改写过）：stream 预览块 END 暂存候选，
        // evt 终结块 START 丢弃候选 —— 只有 evt 权威全文出一张卡
        val api = FakeApi()
        val streamed = "// summary: 统计\nroot = Card([Text(\"hi\")])"
        val normalized = "// summary: 统计\nroot = Card([Text(\"hi\"), Text(\"v2\")])"
        api.v2FramesScript = { onFrame ->
            onFrame(AgUiFrame.RunStarted("s_1", "run-abc"))
            v2TextBlock("stream-1", "assistant", "// summary: 统计\nroot = Card([", "Text(\"hi\")])").forEach(onFrame)
            v2TextBlock("evt-7", "assistant", normalized).forEach(onFrame)
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画个图", null) { events.add(it) }
        assertEquals(
            listOf(
                CloudTurnEvent.OpenUiCardDelta(streamed),
                CloudTurnEvent.OpenUiCard(normalized, "统计"),
                CloudTurnEvent.Done(),
            ),
            events,
        )
    }

    @Test
    fun `v2 equal stream block candidate flushes at run finished single card`() = runTest {
        // 等值场景（未改写，无 evt 续帧）：stream 候选在 RUN_FINISHED 前 flush 转正，恒单卡
        val api = FakeApi()
        val program = "// summary: 统计\nroot = Card([Text(\"hi\")])"
        api.v2FramesScript = { onFrame ->
            v2TextBlock("stream-1", "assistant", "// summary: 统计\nroot = Card([", "Text(\"hi\")])").forEach(onFrame)
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "画个图", null) { events.add(it) }
        assertEquals(
            listOf(
                CloudTurnEvent.OpenUiCardDelta(program),
                CloudTurnEvent.OpenUiCard(program, "统计"),
                CloudTurnEvent.Done(),
            ),
            events,
        )
    }

    @Test
    fun `v2 stream text candidate flushes before tool call frames`() = runTest {
        // 工具交错：stream 文本块 END 后接 TOOL_CALL_START —— 候选先 flush（叙述文本不丢、顺序不乱）
        val api = FakeApi()
        api.v2FramesScript = { onFrame ->
            v2TextBlock("stream-1", "assistant", "我先搜一下").forEach(onFrame)
            onFrame(AgUiFrame.ToolCallStart("act-11", "search_media"))
            onFrame(AgUiFrame.ToolCallArgs("act-11", "{\"query\":\"猫\"}"))
            onFrame(AgUiFrame.ToolCallEnd("act-11"))
            onFrame(AgUiFrame.ToolCallResult("act-11-result", "act-11", "找到 3 张"))
            onFrame(v2RunFinished())
        }
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "搜照片", null) { events.add(it) }
        assertEquals(
            listOf(
                CloudTurnEvent.AssistantText("我先搜一下"),
                CloudTurnEvent.StepStarted(11L, "search_media"),
                CloudTurnEvent.StepFinished(11L, true),
                CloudTurnEvent.Done(),
            ),
            events,
        )
    }

    @Test
    fun `v2 route missing falls back to v1 and marks endpoint unsupported`() = runTest {
        val api = FakeApi()
        api.framesScript = { onFrame ->
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":1,"type":"message","source":"agent","role":"assistant","payload":{"type":"message","content":"你有 42 张照片"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val events = mutableListOf<CloudTurnEvent>()
        val result = newRunner(api).runTurn("token", "s_1", "相册多少张", null) { events.add(it) }
        assertTrue(result.isSuccess)
        assertEquals(listOf(CloudTurnEvent.AssistantText("你有 42 张照片"), CloudTurnEvent.Done()), events)
        assertEquals(1, api.v2CallCount)
        assertEquals(1, api.v1CallCount)
        assertTrue(api.markedUnsupported)
    }

    @Test
    fun `v2 business 404 with error body does not fall back`() = runTest {
        val api = FakeApi()
        api.v2Failure = RuntimeException("""HTTP 404: {"error":"session_not_found"}""")
        val result = newRunner(api).runTurn("token", "s_1", "hi", null) {}
        assertTrue(result.isFailure)
        assertEquals(0, api.v1CallCount)
        assertTrue(!api.markedUnsupported)
    }

    @Test
    fun `ag ui gate off goes straight to v1`() = runTest {
        val api = FakeApi()
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null, agUiEnabled = false) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.Done()), events)
        assertEquals(0, api.v2CallCount)
        assertEquals(1, api.v1CallCount)
    }

    @Test
    fun `sticky unsupported endpoint skips v2 probe`() = runTest {
        val api = FakeApi()
        api.agUiSupported = false
        val events = mutableListOf<CloudTurnEvent>()
        newRunner(api).runTurn("token", "s_1", "hi", null) { events.add(it) }
        assertEquals(listOf(CloudTurnEvent.Done()), events)
        assertEquals(0, api.v2CallCount)
        assertEquals(1, api.v1CallCount)
    }
}
