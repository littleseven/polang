package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.reflect.asToolsByClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * render_ui 反射展开守卫（hybrid M2 T4 评审 R1 F1 + Minor #5）：
 * 钉住 Koog 反射暴露的 descriptor——工具名 / 参数清单 / catalogId 字面量，
 * 防 @Tool / @LLMDescription 注解漂移（catalogId 与 shared `A2uiCardContract`
 * 为字面量副本关系，双向同步核对）。
 */
class RenderUiToolServiceTest {

    private val descriptor = RenderUiToolService().asToolsByClass().single().descriptor

    @Test
    fun `render_ui tool is exposed by reflection`() {
        assertEquals("render_ui", descriptor.name)
    }

    @Test
    fun `render_ui declares three required parameters`() {
        assertEquals(
            listOf("surfaceId", "messagesJson", "fallbackSummary"),
            descriptor.requiredParameters.map { parameter -> parameter.name },
        )
    }

    @Test
    fun `render_ui description pins catalogId literal`() {
        assertTrue(
            "description 须钉住 catalogId 字面量（与 shared A2uiCardContract 双向同步）",
            descriptor.description.contains("https://polang.mamba.com/a2ui/catalog/v1"),
        )
    }
}
