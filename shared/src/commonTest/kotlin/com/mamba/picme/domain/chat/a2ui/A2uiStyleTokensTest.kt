package com.mamba.picme.domain.chat.a2ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A2uiStyleTokens 线格式契约测试。
 *
 * 枚举值与 server `RenderUiToolService` @LLMDescription 第 7 条约束、
 * androidApp `A2uiStyleMapper` 解析函数三者必须一致（字面量副本关系）。
 */
class A2uiStyleTokensTest {

    @Test
    fun textRole_wireValues_matchServerDescription() {
        assertEquals("HEADLINE", A2uiTextRole.HEADLINE.name)
        assertEquals("SUBHEAD", A2uiTextRole.SUBHEAD.name)
        assertEquals("BODY", A2uiTextRole.BODY.name)
        assertEquals("CAPTION", A2uiTextRole.CAPTION.name)
    }

    @Test
    fun colorRole_wireValues_matchServerDescription() {
        assertEquals("PRIMARY", A2uiColorRole.PRIMARY.name)
        assertEquals("SURFACE", A2uiColorRole.SURFACE.name)
        assertEquals("ERROR", A2uiColorRole.ERROR.name)
        assertEquals("ON_SURFACE", A2uiColorRole.ON_SURFACE.name)
    }

    @Test
    fun spacingRole_wireValues_matchServerDescription() {
        assertEquals("COMPACT", A2uiSpacingRole.COMPACT.name)
        assertEquals("STANDARD", A2uiSpacingRole.STANDARD.name)
        assertEquals("COMFORTABLE", A2uiSpacingRole.COMFORTABLE.name)
    }

    @Test
    fun parseTextRole_validValues_roundTrip() {
        A2uiTextRole.entries.forEach { role ->
            assertEquals(role, A2uiTextRole.valueOf(role.name))
        }
    }

    @Test
    fun parseTextRole_invalidValue_returnsNull() {
        val result = runCatching { A2uiTextRole.valueOf("HUGE") }.getOrNull()
        assertNull(result)
    }

    @Test
    fun contractKeys_matchWireFormat() {
        assertEquals("role", A2uiCardContract.ROLE_KEY)
        assertEquals("colorRole", A2uiCardContract.COLOR_ROLE_KEY)
        assertEquals("spacing", A2uiCardContract.SPACING_KEY)
    }
}
