package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.reflect.asToolsByClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `description pins full library expansion sections`() {
        // 2026-10-25 官方全量补全（spec《openui-full-library-design》）：
        // 表单 name 键控写入约定 / 元素形状组件不独立使用 / Image photo·src 二缺一
        assertTrue(descriptor.description.contains("name 键控"))
        assertTrue(descriptor.description.contains("@Set(${'$'}<name>, 值)"))
        assertTrue(descriptor.description.contains("仅作父组件数组元素的对象形状，不独立使用"))
        assertTrue(descriptor.description.contains("photo 与 src 二缺一必给"))
        assertTrue(descriptor.description.contains("选项 value 内禁止逗号"))
        // 新组件代表签名入描述（LLM 唯一可见的组件文档）
        listOf("Stack(", "Form(", "RadarChart(", "SnippetCardBlock(", "EditableTable(")
            .forEach { signature ->
                assertTrue("描述缺 $signature", descriptor.description.contains(signature))
            }
    }

    @Test
    fun `description pins Html component signature`() {
        // 2026-11-01 review 🟡2：Html 是 turn-native 线 HTML 唯一通路，签名必须入描述——
        // 裸名单形态模型只能猜必填参数（猜错 missing-required 静默丢组件）
        assertTrue("描述缺 Html(", descriptor.description.contains("Html(html*)"))
        assertTrue("描述缺 Html 清洗约束", descriptor.description.contains("禁远程 <script src>"))
        // 规则 1 修订：位置参数按签名序 + 可选参数推荐命名实参（黄金示例同款写法）
        assertTrue("规则 1 应为命名实参口径", descriptor.description.contains("命名实参"))
    }

    @Test
    fun `description pins unified spacing scale and rhythm rules`() {
        // 2026-11-01 间距词汇统一批次：spacing/Spacer/Stack gap 同一官方七档刻度，
        // 各档 dp 语义与节奏规则（7.6）是 LLM 唯一可见的间距文档
        assertTrue(
            "签名应为统一七档刻度",
            descriptor.description.contains("spacing∈none|xs|s|m|l|xl|2xl"),
        )
        assertTrue("描述缺 7.6 间距节奏规则", descriptor.description.contains("7.6 间距与节奏规则"))
        assertTrue("7.6 缺 dp 语义表", descriptor.description.contains("m=12（默认组件间距"))
        assertTrue("7.6 缺分区间隔规则", descriptor.description.contains("分区间隔大于组内间隔"))
        assertTrue("Spacer 应扩 2xl", descriptor.description.contains("size∈xs|s|m|l|xl|2xl"))
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

    @Test
    fun `tool body rejects layout violation and confirms normalized program`() = runBlocking {
        val layoutBad = service.renderUiOpenUi(
            "root = Card([Text(\"a\"), Card([Text(\"b\"), Card([Text(\"c\")])])])",
            "摘要",
        )
        assertTrue("实际：$layoutBad", layoutBad.startsWith("error: render_ui_openui 布局门禁未通过"))

        val normalizable = service.renderUiOpenUi(
            "root = Card([Row([Image(photos[0].id), Text(\"说明\")])])",
            "摘要",
        )
        assertTrue("实际：$normalizable", normalizable.startsWith("UI 已下发端侧渲染"))
    }

    @Test
    fun `layout batch golden programs pass protocol and layout gates`() {
        listOf(
            RenderOpenUiToolService.GOLDEN_COMPARE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_TIMELINE_PROGRAM,
        ).forEach { golden ->
            assertNull("协议校验应过：$golden", RenderOpenUiValidator.validateToError(golden))
            assertTrue("布局门禁应过：$golden", OpenUiLayoutValidator.validate(golden).isEmpty())
            assertTrue("黄金程序不应被矫正改写", OpenUiLayoutNormalizer.normalize(golden) == golden)
        }
    }
}
