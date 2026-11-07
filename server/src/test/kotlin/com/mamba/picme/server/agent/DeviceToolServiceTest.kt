package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.Tool
import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 目录驱动工具桥（分层 runtime M2）：工具集自 shared `CloudAgentToolCatalog` 生成，
 * execute = 桥帧下行 + 挂起等上行（行为与 M1 反射期逐项等价，本类钉住）。
 */
class DeviceToolServiceTest {

    private fun DeviceToolService.toolNamed(name: String): Tool<JsonObject, String> =
        tools().single { tool -> tool.descriptor.name == name }

    @Test
    fun `catalog drives the exact three bridge tools`() {
        val service = DeviceToolService("s1", AgentBridgeRegistry()) { }
        assertEquals(
            setOf(
                CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
                CloudAgentToolContract.TOOL_SEARCH_MEDIA,
                CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
            ),
            service.tools().map { tool -> tool.descriptor.name }.toSet(),
        )
    }

    @Test
    fun `execute writes tool_request frame then returns device result`() = runTest {
        val bridge = AgentBridgeRegistry()
        val frames = mutableListOf<ToolRequestFrame>()
        val service = DeviceToolService("s1", bridge) { frame ->
            frames.add(frame)
            // 模拟端侧上行：写帧后立即补结果
            bridge.complete(frame.requestId, "找到 3 张猫")
        }
        val result = service.toolNamed("search_media").execute(
            buildJsonObject {
                put("query", "猫")
                put("person", "")
                put("fromMs", "")
                put("toMs", "")
                put("hasFace", "")
                put("display", "false")
                put("mediaType", "photo")
            },
        )
        assertEquals("找到 3 张猫", result)
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals("search_media", frame.tool)
        assertEquals("猫", frame.args["query"]!!.jsonPrimitive.content)
        assertEquals("false", frame.args["display"]!!.jsonPrimitive.content)
        assertEquals("photo", frame.args["mediaType"]!!.jsonPrimitive.content)
        assertTrue(frame.requestId.startsWith("tr_"))
        assertEquals(DeviceToolService.DEFAULT_TIMEOUT_MS, frame.timeoutMs)
        // 帧 JSON 线格式钉死（spec §3.1）
        val wire = frame.toJson().toString()
        assertTrue(wire.contains("\"kind\":\"tool_request\""))
        assertTrue(wire.contains("\"request_id\":\"${frame.requestId}\""))
    }

    @Test
    fun `execute passes args json verbatim into frame`() = runTest {
        // 目录驱动后桥层零键名知识：LLM 给的 JsonObject 原样入帧（缺省补齐在端侧 executor）
        val bridge = AgentBridgeRegistry()
        var captured: ToolRequestFrame? = null
        val service = DeviceToolService("s1", bridge) { frame ->
            captured = frame
            bridge.complete(frame.requestId, "ok")
        }
        val args = buildJsonObject { put("query", "夜景") }
        service.toolNamed("search_media").execute(args)
        assertEquals(args, captured!!.args)
    }

    @Test
    fun `write frame failure returns device_offline without suspending`() = runTest {
        val bridge = AgentBridgeRegistry()
        val service = DeviceToolService("s1", bridge) { throw IOException("broken pipe") }
        assertEquals(
            "error: device_offline",
            service.toolNamed("get_gallery_summary").execute(buildJsonObject {}),
        )
        assertEquals(0, bridge.pendingCount())
    }

    @Test
    fun `timeout returns device_tool_timeout`() = runTest {
        val bridge = AgentBridgeRegistry()
        val service = DeviceToolService("s1", bridge, timeoutMs = 50) { }
        assertEquals(
            "error: device_tool_timeout",
            service.toolNamed("get_gallery_summary").execute(buildJsonObject {}),
        )
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
        service.toolNamed("refine_media_search").execute(
            buildJsonObject {
                put("constraint", "只要夜景")
                put("fromMs", "1700000000000")
                put("toMs", "")
            },
        )
        assertEquals("refine_media_search", captured!!.tool)
        assertEquals("只要夜景", captured!!.args["constraint"]!!.jsonPrimitive.content)
        assertEquals("1700000000000", captured!!.args["fromMs"]!!.jsonPrimitive.content)
    }
}
