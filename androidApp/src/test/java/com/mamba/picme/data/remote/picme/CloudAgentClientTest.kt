package com.mamba.picme.data.remote.picme

import com.mamba.picme.domain.chat.streaming.AgUiFrame
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAgentClientTest {

    @Test
    fun `parseBlock parses four frame kinds`() {
        val event = CloudSseParser.parseBlock("""data: {"kind":"event","event":{"id":7,"type":"message"}}""")
        assertTrue(event is CloudFrame.Event)
        assertEquals(7, (event as CloudFrame.Event).event.getInt("id"))

        val tr = CloudSseParser.parseBlock("""data: {"kind":"tool_request","request_id":"tr_ab12","tool":"search_media","args":{"query":"猫"},"timeout_ms":120000}""")
        assertTrue(tr is CloudFrame.ToolRequest)
        assertEquals("tr_ab12", (tr as CloudFrame.ToolRequest).requestId)
        assertEquals("search_media", tr.tool)
        assertEquals("猫", tr.args.getString("query"))
        assertEquals(120_000L, tr.timeoutMs)

        assertTrue(CloudSseParser.parseBlock("""data: {"kind":"done","session_status":"running"}""") is CloudFrame.Done)
        assertEquals("boom", (CloudSseParser.parseBlock("""data: {"kind":"error","message":"boom"}""") as CloudFrame.Error).message)
        // 容错：坏 JSON / 空 request_id / 未知 kind 一律 null 跳过
        assertEquals(null, CloudSseParser.parseBlock("data: {bad json"))
        assertEquals(null, CloudSseParser.parseBlock("""data: {"kind":"tool_request","request_id":""}"""))
        assertEquals(null, CloudSseParser.parseBlock("""data: {"kind":"mystery"}"""))
    }

    @Test
    fun `parseBlock parses openui delta frame and tolerates empty program`() {
        // openui P2 流式预览帧（瞬态，不落事件流）
        val delta = CloudSseParser.parseBlock("""data: {"kind":"openui_delta","program":"root = Card(["}""")
        assertTrue(delta is CloudFrame.OpenUiDelta)
        assertEquals("root = Card([", (delta as CloudFrame.OpenUiDelta).program)
        // 空前缀无意义忽略；缺字段忽略
        assertEquals(null, CloudSseParser.parseBlock("""data: {"kind":"openui_delta","program":""}"""))
        assertEquals(null, CloudSseParser.parseBlock("""data: {"kind":"openui_delta"}"""))
    }

    @Test
    fun `parseBlock parses done frame usage fields and tolerates legacy done`() {
        // 2026-11-01 起 done 帧携带回合性能数据（usage/elapsed_ms）；
        // 2026-11-11 起携带 OpenUI 语言协议版本（lang_version，版本协商）
        val done = CloudSseParser.parseBlock(
            """data: {"kind":"done","session_status":"running","elapsed_ms":8432,"usage":{"prompt_tokens":1280,"completion_tokens":96},"lang_version":2}""",
        ) as CloudFrame.Done
        assertEquals("running", done.sessionStatus)
        assertEquals(1280L, done.promptTokens)
        assertEquals(96L, done.completionTokens)
        assertEquals(8432L, done.elapsedMs)
        assertEquals(2, done.langVersion)
        // 旧服务端 done 帧（无 usage / lang_version）：字段全 null 容错，不提示
        val legacy = CloudSseParser.parseBlock("""data: {"kind":"done","session_status":"running"}""") as CloudFrame.Done
        assertEquals(null, legacy.promptTokens)
        assertEquals(null, legacy.completionTokens)
        assertEquals(null, legacy.elapsedMs)
        assertEquals(null, legacy.langVersion)
    }

    @Test
    fun `createSession posts channel fields and returns session json`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session_id":"s_1","status":"running","channel":"byok","model":"kimi-k2.6"}"""))
        server.start()
        // MockWebServer url 带尾 "/"，client 拼路径用 "$baseUrl/agent/..."——去掉尾斜杠防双斜杠
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        val result = client.createSession("tok", "byok", "kimi-k2.6", "https://api.moonshot.cn/v1/")
        assertTrue(result.isSuccess)
        assertEquals("s_1", result.getOrThrow().getString("session_id"))
        val recorded = server.takeRequest()
        assertEquals("tok", recorded.getHeader("X-App-Token"))
        val sent = JSONObject(recorded.body.readUtf8())
        assertEquals("byok", sent.getString("channel"))
        assertEquals("kimi-k2.6", sent.getString("model"))
        assertEquals("https://api.moonshot.cn/v1/", sent.getString("byok_base_url"))
        server.shutdown()
    }

    @Test
    fun `postMessage sends BYOK key header only when provided and streams frames`() = runTest {
        val server = MockWebServer()
        val sse = "data: {\"kind\":\"event\",\"event\":{\"id\":1,\"type\":\"message\",\"source\":\"agent\",\"role\":\"assistant\",\"payload\":{\"type\":\"message\",\"content\":\"你好\"}}}\n\n" +
            "data: {\"kind\":\"tool_request\",\"request_id\":\"tr_1\",\"tool\":\"get_gallery_summary\",\"args\":{},\"timeout_ms\":120000}\n\n" +
            "data: {\"kind\":\"done\",\"session_status\":\"running\"}\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        val frames = mutableListOf<CloudFrame>()
        val result = client.postMessage("tok", "s_1", "hi", byokApiKey = "sk-secret", onFrame = { frame -> frames.add(frame) })
        assertTrue(result.isSuccess)
        assertEquals(3, frames.size)
        assertTrue(frames[0] is CloudFrame.Event)
        assertTrue(frames[1] is CloudFrame.ToolRequest)
        assertTrue(frames[2] is CloudFrame.Done)
        val recorded = server.takeRequest()
        // BYOK 零留存锚点：key 只出现在这一个 header，不进 body
        assertEquals("sk-secret", recorded.getHeader("X-User-Api-Key"))
        // OpenUI 唯一化（2026-11-06）：GenUI 格式分流头已拆除，任何情况下不再发送
        assertTrue(
            "X-GenUI-Format must not be sent after OpenUI sole-protocol",
            recorded.headers.names().none { name -> name.contains("GenUI", ignoreCase = true) },
        )
        assertTrue(!recorded.body.readUtf8().contains("sk-secret"))
        server.shutdown()
    }

    @Test
    fun `postMessage without byok key omits header`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("data: {\"kind\":\"done\"}\n\n"))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        client.postMessage("tok", "s_1", "hi", byokApiKey = null, onFrame = {})
        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("X-User-Api-Key"))
        // GenUI 格式分流头已拆除（server turn-native 恒走响应即程序，无发卡工具）
        assertTrue(
            "X-GenUI-Format must not be sent after OpenUI sole-protocol",
            recorded.headers.names().none { name -> name.contains("GenUI", ignoreCase = true) },
        )
        server.shutdown()
    }

    // 2026-11-22 OpenUI 多语言批次：界面语言经 X-App-Language 上报，null 不带头
    @Test
    fun `postMessage sends app language header only when provided`() = runTest {
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("data: {\"kind\":\"done\"}\n\n"))
        }
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        client.postMessage("tok", "s_1", "hi", byokApiKey = null, replyLanguage = "ENGLISH", onFrame = {})
        client.postMessage("tok", "s_1", "hi", byokApiKey = null, replyLanguage = null, onFrame = {})
        assertEquals("ENGLISH", server.takeRequest().getHeader("X-App-Language"))
        assertEquals(null, server.takeRequest().getHeader("X-App-Language"))
        server.shutdown()
    }

    // ── v2（AG-UI）端点（spec《AG-UI 迁移》M2） ─────────────────────

    @Test
    fun `postMessageV2 posts to v2 url and parses ag-ui frames`() = runTest {
        val server = MockWebServer()
        val sse = "data: {\"type\":\"RUN_STARTED\",\"threadId\":\"s_1\",\"runId\":\"run-abc\"}\n\n" +
            "data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m1\",\"role\":\"assistant\"}\n\n" +
            "data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"你好\"}\n\n" +
            "data: {\"type\":\"TEXT_MESSAGE_END\",\"messageId\":\"m1\"}\n\n" +
            "data: {\"type\":\"RUN_FINISHED\",\"threadId\":\"s_1\",\"runId\":\"run-abc\",\"metadata\":{\"elapsed_ms\":10}}\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        val frames = mutableListOf<AgUiFrame>()
        val result = client.postMessageV2("tok", "s_1", "hi", byokApiKey = null, onFrame = { frame -> frames.add(frame) })
        assertTrue(result.isSuccess)
        assertEquals("/agent/v2/sessions/s_1/messages", server.takeRequest().path)
        assertEquals(5, frames.size)
        assertTrue(frames[0] is AgUiFrame.RunStarted)
        assertTrue(frames[1] is AgUiFrame.TextMessageStart)
        assertEquals("你好", (frames[2] as AgUiFrame.TextMessageContent).delta)
        assertTrue(frames[3] is AgUiFrame.TextMessageEnd)
        assertTrue(frames[4] is AgUiFrame.RunFinished)
        server.shutdown()
    }

    @Test
    fun `postMessageV2 http 404 surfaces failure for route-miss fallback`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        val result = client.postMessageV2("tok", "s_1", "hi", byokApiKey = null, onFrame = {})
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.startsWith("HTTP 404") == true)
        server.shutdown()
    }

    @Test
    fun `ag ui endpoint supported flag flips after mark`() {
        val client = CloudAgentClient("http://localhost")
        assertTrue(client.agUiEndpointSupported)
        client.markAgUiEndpointUnsupported()
        assertTrue(!client.agUiEndpointSupported)
    }

    @Test
    fun `postToolResult posts ok and result`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":true}"""))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        val result = client.postToolResult("tok", "s_1", "tr_1", ok = false, result = "相册为空")
        assertTrue(result.isSuccess)
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("tr_1", sent.getString("request_id"))
        assertEquals(false, sent.getBoolean("ok"))
        assertEquals("相册为空", sent.getString("result"))
        server.shutdown()
    }

    @Test
    fun `postToolResult 409 surfaces as failure`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"tool_request_not_pending"}"""))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        assertTrue(client.postToolResult("tok", "s_1", "tr_x", true, "x").isFailure)
        server.shutdown()
    }
}
