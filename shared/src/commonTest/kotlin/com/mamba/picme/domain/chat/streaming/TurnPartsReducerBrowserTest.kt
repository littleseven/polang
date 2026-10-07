package com.mamba.picme.domain.chat.streaming

import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ToolPartState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * browser_open 占位管线（browser-vnc 直播卡 Task 13）钉桩：
 * browser_open 产类型化 BrowserLive 占位；其余 6 个 browser 动作工具不落占位；
 * 产物原位填充 / 失败标 OUTPUT_ERROR。
 */
class TurnPartsReducerBrowserTest {

    @Test
    fun `browser_open produces typed placeholder`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ToolInputStart(toolCallId = "call-1", toolName = TurnPartsReducer.TOOL_BROWSER_OPEN))
        val part = reducer.parts.single()
        assertIs<MessagePart.BrowserLive>(part)
        assertEquals(ToolPartState.INPUT_STREAMING, part.state)
        assertEquals("call-1", part.partId)
    }

    @Test
    fun `browser action tools produce no placeholder`() {
        val reducer = TurnPartsReducer()
        for (name in listOf("browser_navigate", "browser_click", "browser_type", "browser_extract", "browser_screenshot", "browser_close")) {
            reducer.apply(TurnStreamEvent.ToolInputStart(toolCallId = "c-$name", toolName = name))
        }
        assertTrue(reducer.parts.isEmpty())
    }

    @Test
    fun `placeholder fills in place and error marks OUTPUT_ERROR`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ToolInputStart(toolCallId = "call-1", toolName = TurnPartsReducer.TOOL_BROWSER_OPEN))
        val filled = MessagePart.BrowserLive(partId = "call-1", sessionId = "s-1", state = ToolPartState.INPUT_AVAILABLE, pageTitle = "A")
        reducer.apply(TurnStreamEvent.ToolOutputAvailable(toolCallId = "call-1", output = filled))
        assertEquals(filled, reducer.parts.single())

        reducer.reset()
        reducer.apply(TurnStreamEvent.ToolInputStart(toolCallId = "call-2", toolName = TurnPartsReducer.TOOL_BROWSER_OPEN))
        reducer.apply(TurnStreamEvent.ToolOutputError(toolCallId = "call-2", errorText = "boom"))
        assertEquals(ToolPartState.OUTPUT_ERROR, (reducer.parts.single() as MessagePart.BrowserLive).state)
    }
}
