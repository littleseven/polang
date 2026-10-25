package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.reflect.asToolsByClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * render_ui_openui 反射展开 + 回执守卫（2026-10-25 构图软警告配套，
 * 照 [RenderUiToolServiceTest] 先例）：钉住工具名 / 参数清单 / 构图规则
 * 描述字面量 + 软警告回执协议（超限附带「无需重发」建议行、干净 program 裸回执）。
 */
class RenderOpenUiToolServiceTest {

    private val service = RenderOpenUiToolService()
    private val descriptor = service.asToolsByClass().single().descriptor

    @Test
    fun `render_ui_openui tool is exposed by reflection`() {
        assertEquals("render_ui_openui", descriptor.name)
    }

    @Test
    fun `render_ui_openui declares two required parameters`() {
        assertEquals(
            listOf("program", "fallbackSummary"),
            descriptor.requiredParameters.map { parameter -> parameter.name },
        )
    }

    @Test
    fun `description pins display and button composition rules`() {
        // 构图规则描述字面量与 RenderOpenUiValidator.lintComposition 阈值同源
        assertTrue(descriptor.description.contains("一屏 ≤4 个"))
        assertTrue(descriptor.description.contains("一卡 Button ≤3 个"))
    }

    @Test
    fun `receipt for clean program is bare confirmation`() = runBlocking {
        val receipt = service.renderUiOpenUi("""root = Card([Text("ok")])""", "摘要")
        assertEquals("UI 已下发端侧渲染（OpenUI）", receipt)
    }

    @Test
    fun `receipt for lint-overflow program appends advisory without rejecting`() = runBlocking {
        val tiles = (1..5).joinToString(", ") { i -> "Text(\"$i\", \"DISPLAY\")" }
        val receipt = service.renderUiOpenUi("root = Card([$tiles])", "摘要")
        assertTrue(receipt.startsWith("UI 已下发端侧渲染（OpenUI）"))
        assertTrue(receipt.contains("无需重发"))
        assertTrue(receipt.contains("DISPLAY"))
    }
}
