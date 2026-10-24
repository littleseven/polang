package com.mamba.picme.server.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeviceToolServiceTest {

    @Test
    fun `execute writes tool_request frame then returns device result`() = runTest {
        val bridge = AgentBridgeRegistry()
        val frames = mutableListOf<ToolRequestFrame>()
        val service = DeviceToolService("s1", bridge) { frame ->
            frames.add(frame)
            // 模拟端侧上行：写帧后立即补结果
            bridge.complete(frame.requestId, "找到 3 张猫")
        }
        val result = service.searchMedia("猫", "", "", "", "", "false")
        assertEquals("找到 3 张猫", result)
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals("search_media", frame.tool)
        assertEquals("猫", frame.args["query"]!!.jsonPrimitive.content)
        assertEquals("false", frame.args["display"]!!.jsonPrimitive.content)
        assertTrue(frame.requestId.startsWith("tr_"))
        assertEquals(DeviceToolService.DEFAULT_TIMEOUT_MS, frame.timeoutMs)
        // 帧 JSON 线格式钉死（spec §3.1）
        val wire = frame.toJson().toString()
        assertTrue(wire.contains("\"kind\":\"tool_request\""))
        assertTrue(wire.contains("\"request_id\":\"${frame.requestId}\""))
    }

    @Test
    fun `write frame failure returns device_offline without suspending`() = runTest {
        val bridge = AgentBridgeRegistry()
        val service = DeviceToolService("s1", bridge) { throw IOException("broken pipe") }
        assertEquals("error: device_offline", service.getGallerySummary())
        assertEquals(0, bridge.pendingCount())
    }

    @Test
    fun `timeout returns device_tool_timeout`() = runTest {
        val bridge = AgentBridgeRegistry()
        val service = DeviceToolService("s1", bridge, timeoutMs = 50) { }
        assertEquals("error: device_tool_timeout", service.getGallerySummary())
        assertEquals(0, bridge.pendingCount()) // 超时后条目摘除（迟到上行将 409）
    }

    @Test
    fun `refine passes constraint and time bounds`() = runTest {
        val bridge = AgentBridgeRegistry()
        var captured: ToolRequestFrame? = null
        val service = DeviceToolService("s1", bridge) { frame ->
            captured = frame
            bridge.complete(frame.requestId, "ok")
        }
        service.refineMediaSearch("只要夜景", "1700000000000", "")
        assertEquals("refine_media_search", captured!!.tool)
        assertEquals("只要夜景", captured!!.args["constraint"]!!.jsonPrimitive.content)
        assertEquals("1700000000000", captured!!.args["fromMs"]!!.jsonPrimitive.content)
    }
}
