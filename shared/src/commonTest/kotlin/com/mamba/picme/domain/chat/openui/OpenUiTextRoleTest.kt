package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * OpenUiTextRole 线格式契约测试（自 a2ui/A2uiStyleTokensTest 的 TextRole 部分迁移改写，
 * 2026-11-05 A2UI 退役；ColorRole/SpacingRole 随 A2UI 线一并删除）。
 *
 * 枚举值与 server `OpenUiPromptAssets` 描述文本、androidApp openui 渲染层
 * 解析三者必须一致（字面量副本关系）。
 */
class OpenUiTextRoleTest {

    @Test
    fun textRole_wireValues_matchServerDescription() {
        assertEquals("HEADLINE", OpenUiTextRole.HEADLINE.name)
        assertEquals("SUBHEAD", OpenUiTextRole.SUBHEAD.name)
        assertEquals("BODY", OpenUiTextRole.BODY.name)
        assertEquals("CAPTION", OpenUiTextRole.CAPTION.name)
    }

    @Test
    fun parseTextRole_validValues_roundTrip() {
        OpenUiTextRole.entries.forEach { role ->
            assertEquals(role, OpenUiTextRole.valueOf(role.name))
        }
    }

    @Test
    fun parseTextRole_invalidValue_returnsNull() {
        val result = runCatching { OpenUiTextRole.valueOf("HUGE") }.getOrNull()
        assertNull(result)
    }
}
