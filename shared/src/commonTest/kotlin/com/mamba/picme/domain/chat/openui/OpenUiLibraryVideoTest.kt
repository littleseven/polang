package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

/**
 * OpenUI 组件库注册 Video 组件（2026-11-04 视频组件批次）：本地/公网视频播放——
 * photo = #videos 投影 id 与 src = https 公网视频二缺一（签名对齐 Image，全部可选、
 * 来源二缺一的引导在 server 校验层，端侧 schema 不拦）；渲染复用 ChatVideoPlayer
 * （卡片语境不自动播、离组即释放）。校验入口沿用 OpenUiSession.prepare() 会话口径
 * （OpenUiLibraryHtmlTest 同形态）。
 */
class OpenUiLibraryVideoTest {

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
    fun videoComponentRegistered() {
        val video = OpenUiLibrary.components["Video"]
        assertNotNull(video, "Video 组件未注册")
        assertEquals(3, video.props.size)
        assertEquals("photo", video.props[0].name)
        assertEquals(OpenUiPropType.NUMBER, video.props[0].type)
        assertEquals("aspect", video.props[1].name)
        assertEquals(OpenUiPropType.NUMBER, video.props[1].type)
        assertEquals("src", video.props[2].name)
        assertEquals(OpenUiPropType.STRING, video.props[2].type)
        video.props.forEach { prop -> assertFalse(prop.required, "${prop.name} 不应必填") }
    }

    @Test
    fun `video component validates with photo id`() = runTest {
        val result = rendered(
            OpenUiSession("""root = Card([Video(photo = 42)])""", noTools).prepare(),
        )
        assertEquals(emptyList(), result.errors)
        val video = childrenOf(result.root)[0]
        assertIs<OpenUiValue.ComponentVal>(video)
        assertEquals("Video", video.type)
        assertEquals(OpenUiValue.Num(42.0), video.props["photo"])
    }

    @Test
    fun `video component validates with src and aspect`() = runTest {
        val result = rendered(
            OpenUiSession(
                """root = Card([Video(src = "https://cos.polang.net/a.mp4", aspect = 1.5)])""",
                noTools,
            ).prepare(),
        )
        assertEquals(emptyList(), result.errors)
        val video = childrenOf(result.root)[0]
        assertIs<OpenUiValue.ComponentVal>(video)
        assertEquals(OpenUiValue.Str("https://cos.polang.net/a.mp4"), video.props["src"])
    }
}
