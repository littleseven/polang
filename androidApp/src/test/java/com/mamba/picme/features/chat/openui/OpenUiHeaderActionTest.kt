package com.mamba.picme.features.chat.openui

import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CardHeader「查看全部」缺省动作兜底钉住（2026-10-31 真机回归：LLM 按黄金示例只传
 * actionLabel 不传 action，按钮渲染主色可点态却无动作——点击无效）：
 * actionLabel 在场且 action 缺省时，端侧兜底合成 @ToAssistant("actionLabel：title")
 * 上行一条用户消息；显式 action 永远优先。
 */
class OpenUiHeaderActionTest {

    @Test
    fun `no label means no default action`() {
        assertTrue(headerActionsOrDefault(null, "最近月份", emptyList()).isEmpty())
    }

    @Test
    fun `explicit action wins over default`() {
        val explicit = listOf(OpenUiAction("Set", listOf(OpenUiValue.Str("x")), target = "s"))
        assertEquals(explicit, headerActionsOrDefault("查看全部", "最近月份", explicit))
    }

    @Test
    fun `label without action defaults to to-assistant with title context`() {
        val actions = headerActionsOrDefault("查看全部", "最近月份", emptyList())
        assertEquals(1, actions.size)
        assertEquals("ToAssistant", actions[0].name)
        assertEquals("查看全部：最近月份", (actions[0].args[0] as OpenUiValue.Str).v)
    }

    @Test
    fun `label without title defaults to bare label text`() {
        val actions = headerActionsOrDefault("查看全部", null, emptyList())
        assertEquals("查看全部", (actions[0].args[0] as OpenUiValue.Str).v)
    }
}
