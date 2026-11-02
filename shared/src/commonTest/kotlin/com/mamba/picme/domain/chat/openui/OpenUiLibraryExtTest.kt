package com.mamba.picme.domain.chat.openui

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 任务卡 OpenUI 化词汇扩展（spec《2026-11-02-engineer-taskcard-openui》§3.3）：
 * Progress 新组件 + Tag.tone 实心档 / Icon.container / Text.maxLines 通用能力。
 */
class OpenUiLibraryExtTest {

    private fun singleChild(result: OpenUiResult.Rendered): OpenUiValue.ComponentVal {
        val child = (result.root.props["children"] as? OpenUiValue.Arr)?.v?.single()
        return assertIs<OpenUiValue.ComponentVal>(child)
    }

    @Test
    fun `progress component renders with value`() = runTest {
        val result = OpenUiSession("""root = Card([Progress(120)])""", noOpTools()).prepare()
        val rendered = assertIs<OpenUiResult.Rendered>(result)
        val progress = singleChild(rendered)
        assertEquals("Progress", progress.type)
        assertEquals(OpenUiValue.Num(120.0), progress.props["value"])
        assertEquals(emptyList(), rendered.errors)
    }

    @Test
    fun `tag accepts solid tone enum and stays compatible without tone`() = runTest {
        val toned = OpenUiSession(
            """root = Card([Tag("运行中", tone = "primary")])""",
            noOpTools(),
        ).prepare()
        val rendered = assertIs<OpenUiResult.Rendered>(toned)
        val tag = singleChild(rendered)
        assertEquals(OpenUiValue.Str("primary"), tag.props["tone"])
        assertEquals(emptyList(), rendered.errors)
        // 缺省 tone 不落 props（无 schema 默认值 = 存量 Tag 外观不变）
        val legacy = OpenUiSession("""root = Card([Tag("x")])""", noOpTools()).prepare()
        val legacyTag = singleChild(assertIs<OpenUiResult.Rendered>(legacy))
        assertEquals(null, legacyTag.props["tone"])
    }

    @Test
    fun `icon accepts container enum`() = runTest {
        val result = OpenUiSession(
            """root = Card([Icon("settings", container = "tonal")])""",
            noOpTools(),
        ).prepare()
        val rendered = assertIs<OpenUiResult.Rendered>(result)
        val icon = singleChild(rendered)
        assertEquals(OpenUiValue.Str("tonal"), icon.props["container"])
        assertEquals(emptyList(), rendered.errors)
    }

    @Test
    fun `text accepts maxLines number`() = runTest {
        val result = OpenUiSession(
            """root = Card([Text("长标题", role = "SUBHEAD", maxLines = 1)])""",
            noOpTools(),
        ).prepare()
        val rendered = assertIs<OpenUiResult.Rendered>(result)
        val text = singleChild(rendered)
        assertEquals(OpenUiValue.Num(1.0), text.props["maxLines"])
        assertEquals(emptyList(), rendered.errors)
    }

    @Test
    fun `row weights zero passes schema`() = runTest {
        val result = OpenUiSession(
            """root = Card([Row([Text("✓"), Text("标签")], weights = [0, 1])])""",
            noOpTools(),
        ).prepare()
        val rendered = assertIs<OpenUiResult.Rendered>(result)
        assertEquals(emptyList(), rendered.errors)
    }

    private fun noOpTools(): OpenUiToolResolver = OpenUiToolResolver { name, _ ->
        throw OpenUiToolNotFoundException(name)
    }
}
