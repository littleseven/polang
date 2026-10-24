package com.mamba.picme.features.chat.a2ui

import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BarChart selected 裸值归一化守卫（2026-10-24 sf-012a0 事故复盘）：
 * 云端 LLM 偶发把 selected 写成字符串字面量（蓝图实测 "selected": ""），而非契约要求的
 * {"path":"/selected"} 数据绑定——裸值不订阅数据模型，点柱后拦截器回写 /selected
 * 无人消费，高亮静默失效。HandleResolver 在 JSON 边界对越约蓝图归一化改绑，
 * 与 data 批量形式展开（fix-a2ui-blueprint-contract）同属防御纵深。
 */
@RunWith(RobolectricTestRunner::class)
class BarChartSelectedBindingNormalizationTest {

    private fun barChartEnvelope(selectedPayload: Any?): JSONObject {
        val chart = JSONObject()
            .put("id", "chart")
            .put("component", "BarChart")
            .put("title", "每月照片数")
        if (selectedPayload != null) chart.put("selected", selectedPayload)
        return JSONObject().put(
            "updateComponents",
            JSONObject()
                .put("surfaceId", "sf-test")
                .put("components", org.json.JSONArray().put(chart)),
        )
    }

    @Test
    fun `empty string literal is rebound to selected path`() {
        // sf-012a0 实际事故形态
        val envelope = barChartEnvelope("")
        val seed = normalizeBarChartSelectedBinding(envelope.getJSONObject("updateComponents"))
        val chart = envelope.getJSONObject("updateComponents")
            .getJSONArray("components").getJSONObject(0)
        assertEquals(
            A2uiCardContract.DATA_PATH_SELECTED,
            chart.getJSONObject("selected").getString("path"),
        )
        assertNull("空串字面量无预选语义，不播种", seed)
    }

    @Test
    fun `non-empty literal is rebound and returned as seed`() {
        val envelope = barChartEnvelope("2026-08")
        val seed = normalizeBarChartSelectedBinding(envelope.getJSONObject("updateComponents"))
        val chart = envelope.getJSONObject("updateComponents")
            .getJSONArray("components").getJSONObject(0)
        assertEquals(
            A2uiCardContract.DATA_PATH_SELECTED,
            chart.getJSONObject("selected").getString("path"),
        )
        assertEquals("非空裸字面量=LLM 意图预选，须播种回数据模型", "2026-08", seed)
    }

    @Test
    fun `missing selected key gets binding injected`() {
        val envelope = barChartEnvelope(null)
        val seed = normalizeBarChartSelectedBinding(envelope.getJSONObject("updateComponents"))
        val chart = envelope.getJSONObject("updateComponents")
            .getJSONArray("components").getJSONObject(0)
        assertTrue("缺键也须补绑定，否则点选高亮同样失效", chart.has("selected"))
        assertEquals(
            A2uiCardContract.DATA_PATH_SELECTED,
            chart.getJSONObject("selected").getString("path"),
        )
        assertNull(seed)
    }

    @Test
    fun `existing path binding is left untouched`() {
        val envelope = barChartEnvelope(JSONObject().put("path", "/selected"))
        val seed = normalizeBarChartSelectedBinding(envelope.getJSONObject("updateComponents"))
        val chart = envelope.getJSONObject("updateComponents")
            .getJSONArray("components").getJSONObject(0)
        assertEquals("/selected", chart.getJSONObject("selected").getString("path"))
        assertNull(seed)
    }

    @Test
    fun `non-BarChart components are not touched`() {
        val envelope = JSONObject().put(
            "updateComponents",
            JSONObject().put(
                "components",
                org.json.JSONArray().put(
                    JSONObject().put("id", "t").put("component", "Text").put("text", "hi")
                ),
            ),
        )
        val seed = normalizeBarChartSelectedBinding(envelope.getJSONObject("updateComponents"))
        assertNull(seed)
        assertTrue(
            "Text 组件不得被加 selected",
            !envelope.getJSONObject("updateComponents")
                .getJSONArray("components").getJSONObject(0).has("selected"),
        )
    }
}
