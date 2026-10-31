package com.mamba.picme.features.chat.openui

import com.mamba.picme.domain.chat.openui.OpenUiContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dispatch 路由清单钉住（2026-11-01 review Y1/Y2/Y5/Y7）：
 * eval 产出的动作全集 [OpenUiContract.ENGINE_ACTIONS] 必须全部被宿主路由覆盖
 * （不得落 UNKNOWN 静默吞）；@Run 显式识别为「未实现」路由（告警留痕）；
 * @OpenUrl scheme 闸只放行 http/https。
 */
class OpenUiActionDispatchTest {

    @Test
    fun `every engine action has a dedicated route`() {
        OpenUiContract.ENGINE_ACTIONS.forEach { name ->
            assertNotEquals(
                "$name 不得落 UNKNOWN",
                OpenUiActionRoute.UNKNOWN,
                openUiActionRouteOf(name),
            )
        }
    }

    @Test
    fun `route mapping matches action semantics`() {
        assertEquals(OpenUiActionRoute.STATE_WRITE, openUiActionRouteOf(OpenUiContract.Actions.SET))
        assertEquals(OpenUiActionRoute.STATE_WRITE, openUiActionRouteOf(OpenUiContract.Actions.RESET))
        assertEquals(OpenUiActionRoute.OPEN_URL, openUiActionRouteOf(OpenUiContract.Actions.OPEN_URL))
        assertEquals(OpenUiActionRoute.OPEN_PHOTO, openUiActionRouteOf(OpenUiContract.Actions.OPEN_PHOTO))
        assertEquals(OpenUiActionRoute.TO_ASSISTANT, openUiActionRouteOf(OpenUiContract.Actions.TO_ASSISTANT))
        assertEquals(OpenUiActionRoute.RUN_UNSUPPORTED, openUiActionRouteOf(OpenUiContract.Actions.RUN))
    }

    @Test
    fun `wrapper and unknown names fall to unknown route`() {
        // Action([...]) 是语法包装，eval 层已拍平，dispatch 不可达——落 UNKNOWN 告警即可
        assertEquals(OpenUiActionRoute.UNKNOWN, openUiActionRouteOf(OpenUiContract.Actions.WRAPPER))
        assertEquals(OpenUiActionRoute.UNKNOWN, openUiActionRouteOf("Explode"))
        assertEquals(OpenUiActionRoute.UNKNOWN, openUiActionRouteOf(""))
    }

    @Test
    fun `open url scheme gate allows http and https only`() {
        assertTrue(isOpenUrlAllowed("https://polang.net/x"))
        assertTrue(isOpenUrlAllowed("http://example.com"))
        // scheme 大小写不敏感（URI 规范）、容忍首参带空白
        assertTrue(isOpenUrlAllowed("HTTPS://polang.net/x"))
        assertTrue(isOpenUrlAllowed(" https://polang.net/x "))
        assertFalse(isOpenUrlAllowed("file:///etc/passwd"))
        assertFalse(isOpenUrlAllowed("intent://scan/#Intent;end"))
        assertFalse(isOpenUrlAllowed("market://details?id=x"))
        assertFalse(isOpenUrlAllowed("content://media/external/1"))
        assertFalse(isOpenUrlAllowed("javascript:alert(1)"))
        assertFalse(isOpenUrlAllowed(""))
    }
}
