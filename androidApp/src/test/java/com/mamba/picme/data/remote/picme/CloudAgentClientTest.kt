package com.mamba.picme.data.remote.picme

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
        val result = client.postMessage("tok", "s_1", "hi", byokApiKey = "sk-secret", genUiFormat = "a2ui", onFrame = { frame -> frames.add(frame) })
        assertTrue(result.isSuccess)
        assertEquals(3, frames.size)
        assertTrue(frames[0] is CloudFrame.Event)
        assertTrue(frames[1] is CloudFrame.ToolRequest)
        assertTrue(frames[2] is CloudFrame.Done)
        val recorded = server.takeRequest()
        // BYOK 零留存锚点：key 只出现在这一个 header，不进 body
        assertEquals("sk-secret", recorded.getHeader("X-User-Api-Key"))
        assertEquals("a2ui", recorded.getHeader("X-GenUI-Format"))
        assertTrue(!recorded.body.readUtf8().contains("sk-secret"))
        server.shutdown()
    }

    @Test
    fun `postMessage without byok key omits header`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("data: {\"kind\":\"done\"}\n\n"))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        client.postMessage("tok", "s_1", "hi", byokApiKey = null, genUiFormat = "", onFrame = {})
        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("X-User-Api-Key"))
        // 空 genUiFormat 不发协议头（非 openui 时 server 侧按默认 A2UI 工具集处理）
        assertEquals(null, recorded.getHeader("X-GenUI-Format"))
        server.shutdown()
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
