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
            descriptor.description.contains("https://polang.net/a2ui/catalog/v1"),
        )
    }

    // fix-a2ui-blueprint-contract：sf-a2u1c0de 事故复盘——LLM 输出 data 批量形式 / Button 用
    // label+secondary / 缺 childId+action，全部越出 alpha01 目录 schema，端上永 loading + Error 占位。
    // 以下契约测试钉住收紧后的线格式约束字面量。
    @Test
    fun `render_ui description pins single path-value updateDataModel form`() {
        assertTrue(
            "description 须钉住 updateDataModel 单 path/value 形式并禁止 data 批量形式",
            descriptor.description.contains("\"path\"") &&
                descriptor.description.contains("\"value\"") &&
                descriptor.description.contains("\"data\""),
        )
    }

    @Test
    fun `render_ui description pins Button contract`() {
        assertTrue(
            "description 须钉住 Button 契约：child 包 Text + action，禁 label，variant 白名单",
            descriptor.description.contains("\"child\"") &&
                descriptor.description.contains("action") &&
                descriptor.description.contains("default/primary/borderless"),
        )
    }

    @Test
    fun `render_ui description pins action event wrap format`() {
        assertTrue(
            "description 须钉住 action 须包 \"event\" 层：action={\"event\":{\"name\":...}}",
            descriptor.description.contains("\"event\"") &&
                descriptor.description.contains("禁止平写"),
        )
    }

    @Test
    fun `render_ui description pins Text variant whitelist`() {
        assertTrue(
            "description 须钉住 Text variant 白名单",
            descriptor.description.contains("h1/h2/h3/h4/h5/caption/body"),
        )
    }

    @Test
    fun `render_ui description pins BarChart selected as path binding`() {
        // 2026-10-24 sf-012a0 事故：LLM 把 selected 写成裸字符串（实测 "selected": ""），
        // 裸值不订阅数据模型 → 端上点选高亮静默失效。钉住绑定形态引导
        // （与 PolangBarChartComponent description 双向核对）。
        assertTrue(
            "description 须钉住 selected 必须写 {\"path\":\"/selected\"} 数据绑定且禁裸字符串",
            descriptor.description.contains("\"selected\"") &&
                descriptor.description.contains("\"/selected\"") &&
                descriptor.description.contains("禁裸字符串"),
        )
    }
}
