package com.mamba.picme.server.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenUI prompt 资产守卫（2026-11-05 常量搬家：原工具描述守卫测试改名，
 * 断言改钉 [OpenUiPromptAssets] 新位置；工具行为钉已随 render_ui_openui 工具退役删除）：
 * 钉住 DSL_RULES 关键段字面量（与 RenderOpenUiValidator.lintComposition 阈值同源）+
 * GOLDEN 程序过协议/布局门禁。
 */
class OpenUiPromptAssetsTest {

    @Test
    fun `dsl rules pin display and button composition rules`() {
        // 构图规则描述字面量与 RenderOpenUiValidator.lintComposition 阈值同源
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("一屏 ≤4 个"))
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("一卡 Button ≤3 个"))
    }

    @Test
    fun `dsl rules keep raw string indentation bytes`() {
        // raw string 前导空白是字符串值一部分（曾被脚本 dedent 腐蚀）：
        // 5 空格前缀续行钉住，防 re-indent 回归
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("\n     如 Text(${'$'}selected)"))
    }

    @Test
    fun `dsl rules pin full library expansion sections`() {
        // 2026-10-25 官方全量补全（spec《openui-full-library-design》）：
        // 表单 name 键控写入约定 / 元素形状组件不独立使用 / Image photo·src 二缺一
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("name 键控"))
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("@Set(${'$'}<name>, 值)"))
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("仅作父组件数组元素的对象形状，不独立使用"))
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("photo 与 src 二缺一必给"))
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("选项 value 内禁止逗号"))
        // 新组件代表签名入规则（LLM 唯一可见的组件文档）
        listOf("Stack(", "Form(", "RadarChart(", "SnippetCardBlock(", "EditableTable(")
            .forEach { signature ->
                assertTrue("DSL_RULES 缺 $signature", OpenUiPromptAssets.DSL_RULES.contains(signature))
            }
    }

    @Test
    fun `dsl rules pin Html component signature`() {
        // 2026-11-01 review 🟡2：Html 是 turn-native 线 HTML 唯一通路，签名必须入规则——
        // 裸名单形态模型只能猜必填参数（猜错 missing-required 静默丢组件）
        assertTrue("DSL_RULES 缺 Html(", OpenUiPromptAssets.DSL_RULES.contains("Html(html*)"))
        assertTrue("DSL_RULES 缺 Html 清洗约束", OpenUiPromptAssets.DSL_RULES.contains("禁远程 <script src>"))
        // 规则 1 修订：位置参数按签名序 + 可选参数推荐命名实参（黄金示例同款写法）
        assertTrue("规则 1 应为命名实参口径", OpenUiPromptAssets.DSL_RULES.contains("命名实参"))
    }

    @Test
    fun `dsl rules pin unified spacing scale and rhythm rules`() {
        // 2026-11-01 间距词汇统一批次：spacing/Spacer/Stack gap 同一官方七档刻度，
        // 各档 dp 语义与节奏规则（7.6）是 LLM 唯一可见的间距文档
        assertTrue(
            "签名应为统一七档刻度",
            OpenUiPromptAssets.DSL_RULES.contains("spacing∈none|xs|s|m|l|xl|2xl"),
        )
        assertTrue("DSL_RULES 缺 7.6 间距节奏规则", OpenUiPromptAssets.DSL_RULES.contains("7.6 间距与节奏规则"))
        assertTrue("7.6 缺 dp 语义表", OpenUiPromptAssets.DSL_RULES.contains("m=12（默认组件间距"))
        assertTrue("7.6 缺分区间隔规则", OpenUiPromptAssets.DSL_RULES.contains("分区间隔大于组内间隔"))
        assertTrue("Spacer 应扩 2xl", OpenUiPromptAssets.DSL_RULES.contains("size∈xs|s|m|l|xl|2xl"))
    }

    @Test
    fun `layout batch golden programs pass protocol and layout gates`() {
        listOf(
            OpenUiPromptAssets.GOLDEN_COMPARE_PROGRAM,
            OpenUiPromptAssets.GOLDEN_TIMELINE_PROGRAM,
        ).forEach { golden ->
            assertNull("协议校验应过：$golden", RenderOpenUiValidator.validateToError(golden))
            assertTrue("布局门禁应过：$golden", OpenUiLayoutValidator.validate(golden).isEmpty())
            assertTrue("黄金程序不应被矫正改写", OpenUiLayoutNormalizer.normalize(golden) == golden)
        }
    }
}
