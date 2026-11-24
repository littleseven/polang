package com.mamba.picme.server.agent

import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            OpenUiPromptAssets.GOLDEN_FOLLOWUP_PROGRAM,
        ).forEach { golden ->
            assertNull("协议校验应过：$golden", RenderOpenUiValidator.validateToError(golden))
            assertTrue("布局门禁应过：$golden", OpenUiLayoutValidator.validate(golden).isEmpty())
            assertTrue("黄金程序不应被矫正改写", OpenUiLayoutNormalizer.normalize(golden) == golden)
        }
    }

    @Test
    fun `dsl rules pin follow-up options section`() {
        // 2026-11-24 follow-up 批次：后续选项段是 LLM 唯一可见的 follow-up 规则文档
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("后续选项"))
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("回复干净结束"))
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("选项标签 ≤8 字"))
        // 对话形态 root 升级约定（Column([Markdown, Buttons])）必须写进规则
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("Column([Markdown(正文), Buttons([...])])"))
        // 数量口径与 lint 阈值同源（一卡 Button ≤3 个）
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains("合计 ≤3"))
        // 黄金示例内嵌进规则正文（同源钉住）
        assertTrue(OpenUiPromptAssets.DSL_RULES.contains(OpenUiPromptAssets.GOLDEN_FOLLOWUP_PROGRAM))
    }

    @Test
    fun `follow-up golden program evaluates in shared engine`() {
        // 端上真实渲染走 shared OpenUiSession 求值——黄金示例除服务端结构校验外，
        // 必须被引擎解析成 Column([Markdown, Buttons(3×Button)]) 组件树（防 DSL 写法
        // 过得了正则级校验、过不了真解析器的脱节；管理后台组件树预览同路径）
        val result = runBlocking {
            OpenUiSession(
                OpenUiPromptAssets.GOLDEN_FOLLOWUP_PROGRAM,
                tools = { _, _ -> OpenUiValue.Null },
            ).prepare()
        }
        assertTrue("引擎求值应成功：$result", result is OpenUiResult.Rendered)
        val root = (result as OpenUiResult.Rendered).root
        assertEquals("Column", root.type)
        val children = (root.props["children"] as? OpenUiValue.Arr)?.v.orEmpty()
        assertEquals(2, children.size)
        val buttons = children[1] as? OpenUiValue.ComponentVal
        assertEquals("Buttons", buttons?.type)
        val items = (buttons?.props?.get("buttons") as? OpenUiValue.Arr)?.v.orEmpty()
        assertEquals(3, items.size)
        items.forEach { item ->
            val button = item as? OpenUiValue.ComponentVal
            assertEquals("Button", button?.type)
            assertTrue("每个选项必须挂 @ToAssistant 动作", button?.props?.containsKey("action") == true)
        }
    }
}
