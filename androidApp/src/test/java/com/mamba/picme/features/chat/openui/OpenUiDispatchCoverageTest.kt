package com.mamba.picme.features.chat.openui

import com.mamba.picme.domain.chat.openui.OpenUiLibrary
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 渲染分发集合完整性钉住（2026-11-01 review 🟡6）：shared [OpenUiLibrary] 注册的
 * 每个非元素形状组件都必须在端侧有渲染分支——主 dispatch 字面量分支 + 四域集合的并集
 * == 组件注册表 − 元素形状。server 侧白名单有 `RenderOpenUiValidatorTest` 101 项钉住，
 * 本测试补齐端侧缺口：新增组件漏写渲染分支时这里变红，而不是线上静默空 Box。
 */
class OpenUiDispatchCoverageTest {

    /**
     * OpenUiNodeRenderer when 主分支字面量清单（与分支一一对应，改分支须同步本清单）；
     * 四域集合直接引用实现侧的 internal val（单一事实源，不副本）。
     */
    private val mainBranchNames = setOf(
        "Card", "Row", "Column", "Grid", "Box", "Spacer", "Text", "Button",
        "BarChart", "Image", "Icon", "Divider", "Html",
        "List", "Tabs", "CheckBox", "ChoicePicker", "Slider", "Table",
        "PieChart", "LineChart", "Callout", "Markdown", "Accordion", "Switch",
    )

    /** 元素形状组件（仅作父组件数组元素，独立出现按设计空占位，无渲染分支）。 */
    private val elementShapeNames = setOf(
        "TabItem", "AccordionItem", "StepsItem", "SelectItem",
        "CheckBoxItem", "RadioItem", "SwitchItem", "ChipItem", "OptionCard",
        "Series", "Slice", "ScatterSeries", "Point",
        "SnippetCardItem", "OverviewCardItem", "ContextCardItem",
        "CompositeCardItem", "VisualCardItem",
    )

    private val renderedNames: Set<String>
        get() = mainBranchNames + openUiContentComponentNames +
            openUiFormComponentNames + openUiChartComponentNames +
            openUiCardBlockComponentNames

    @Test
    fun `dispatch sets cover every registered non-element-shape component`() {
        val expected = OpenUiLibrary.components.keys - elementShapeNames
        val rendered = renderedNames
        val missing = expected - rendered
        val extra = rendered - expected
        assertTrue(
            "已注册但无渲染分支（静默空 Box）：$missing；有分支但未注册（或应为元素形状）：$extra",
            missing.isEmpty() && extra.isEmpty(),
        )
    }

    @Test
    fun `dispatch sets are mutually disjoint`() {
        val sets = listOf(
            "main" to mainBranchNames,
            "content" to openUiContentComponentNames,
            "form" to openUiFormComponentNames,
            "chart" to openUiChartComponentNames,
            "cardBlock" to openUiCardBlockComponentNames,
        )
        val overlaps = sets.flatMapIndexed { index, (nameA, setA) ->
            sets.drop(index + 1).flatMap { (nameB, setB) ->
                (setA intersect setB).map { name -> "$name($nameA/$nameB)" }
            }
        }
        assertTrue("分发集合重叠（后序分支不可达）：$overlaps", overlaps.isEmpty())
    }

    @Test
    fun `element shapes have no dispatch branch`() {
        val leaked = elementShapeNames intersect renderedNames
        assertTrue("元素形状不应有渲染分支（独立出现应空占位）：$leaked", leaked.isEmpty())
    }
}
