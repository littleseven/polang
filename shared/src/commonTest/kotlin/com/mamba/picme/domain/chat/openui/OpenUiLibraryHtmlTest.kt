package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * OpenUI 组件库注册 Html 组件（2026-11-01，spec《2026-11-01-openui-turn-native-design》§3.6，
 * turn-native T1）：L2 沙箱卡入树——完整自包含 HTML 字符串，端侧 WebView 沙箱渲染
 * （网络默认关死、dispatch 白名单）；display = INLINE（缺省，限高约 320dp）/ FULLPAGE
 * （点击弹全屏查看器）。校验入口沿用既有 OpenUiSession.prepare() 会话口径（M2 测试同形态）。
 */
class OpenUiLibraryHtmlTest {

    private val noTools = OpenUiToolResolver { name, _ ->
        throw OpenUiToolNotFoundException(name)
    }

    private fun rendered(result: OpenUiResult): OpenUiResult.Rendered {
        assertIs<OpenUiResult.Rendered>(result)
        return result
    }

    private fun childrenOf(component: OpenUiValue.ComponentVal): List<OpenUiValue> =
        (component.props["children"] as OpenUiValue.Arr).v

    @Test
    fun htmlComponentRegistered() {
        val html = OpenUiLibrary.components["Html"]
        assertNotNull(html, "Html 组件未注册")
        assertEquals(2, html.props.size)
        assertEquals("html", html.props[0].name)
        assertEquals(OpenUiPropType.STRING, html.props[0].type)
        assertTrue(html.props[0].required)
        assertEquals("display", html.props[1].name)
        assertEquals(OpenUiPropType.ENUM, html.props[1].type)
        assertEquals(OpenUiValue.Str("INLINE"), html.props[1].default)
        assertEquals(listOf("INLINE", "FULLPAGE"), html.props[1].enumValues)
    }

    @Test
    fun `html component validates with required html string`() = runTest {
        // 合法：必填 html 字符串（不报 unknown-component）；display 缺省物化 INLINE
        val result = rendered(
            OpenUiSession("""root = Card([Html("<b>hi</b>")])""", noTools).prepare(),
        )
        assertEquals(emptyList(), result.errors)
        val html = childrenOf(result.root)[0]
        assertIs<OpenUiValue.ComponentVal>(html)
        assertEquals("Html", html.type)
        assertEquals(OpenUiValue.Str("<b>hi</b>"), html.props["html"])
        assertEquals(OpenUiValue.Str("INLINE"), html.props["display"])
    }

    @Test
    fun `html component missing required html is dropped`() = runTest {
        // 非法：缺必填 html → missing-required，丢组件不炸卡（§8.2 既有口径）
        val result = rendered(
            OpenUiSession("""root = Card([Html(display = "FULLPAGE"), Text("ok")])""", noTools).prepare(),
        )
        assertEquals(1, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "missing-required" })
    }
}
