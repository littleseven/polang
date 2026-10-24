package com.mamba.picme.server.agent

import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具参数 wire JSON 转义回归（US-2 根因）：
 * Koog 1.3.0 私有 JSON 树 [ai.koog.serialization.JSONPrimitive.toString] 只做原文包引号、
 * 零 JSON 转义——render_ui 的 messagesJson（嵌套 A2UI JSON 字符串）经 `ctx.toolArgs.toString()`
 * 产非法 JSON，端侧 `JSONObject(argsJson)` 解析失败静默吞帧，data_ui 不落库。
 *
 * 载荷形态对齐生产事故现场（agent_event id=196）：嵌套 A2UI v0.9 消息流 + 中文 +
 * local:// opaque handle。
 */
class ToolArgsWireJsonTest {

    private val messagesJson =
        """[{"version":"v0.9","createSurface":{"surfaceId":"sf-test1234","catalogId":"https://polang.net/a2ui/catalog/v1.json"}},""" +
            """{"updateDataModel":{"surfaceId":"sf-test1234","data":{"buckets":"local://scan/32737b0771fb#histogram","selected":""}}}]"""
    private val fallbackSummary = "今年共 144 张照片，月度分布图数据在原设备"

    private fun renderUiToolArgs(): JSONObject = JSONObject(
        mapOf(
            "surfaceId" to JSONLiteral("sf-test1234", true),
            "messagesJson" to JSONLiteral(messagesJson, true),
            "fallbackSummary" to JSONLiteral(fallbackSummary, true),
        ),
    )

    @Test
    fun `wire json is parseable and nested json string round-trips`() {
        val wire = toolArgsWireJson(renderUiToolArgs())

        // 非法 JSON 在此抛出（修复前：内层引号裸奔，Unterminated object）
        val parsed = Json.parseToJsonElement(wire).jsonObject

        assertEquals("sf-test1234", parsed.getValue("surfaceId").jsonPrimitive.content)
        assertEquals(messagesJson, parsed.getValue("messagesJson").jsonPrimitive.content)
        assertEquals(fallbackSummary, parsed.getValue("fallbackSummary").jsonPrimitive.content)
    }

    @Test
    fun `wire json escapes chinese quotes and newlines in string values`() {
        val args = JSONObject(
            mapOf("text" to JSONLiteral("精选 \"双引号\" '单引号'\n换行", true)),
        )
        val wire = toolArgsWireJson(args)
        val parsed = Json.parseToJsonElement(wire).jsonObject
        assertEquals("精选 \"双引号\" '单引号'\n换行", parsed.getValue("text").jsonPrimitive.content)
    }
}
