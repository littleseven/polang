package com.mamba.picme.features.chat

import com.mamba.picme.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserActionFormatterTest {

    @Test
    fun `known actions map to their string keys`() {
        assertEquals(BrowserActionSpec(R.string.browser_action_open, "https://a.com"), browserActionSpec("open", null, "https://a.com"))
        assertEquals(BrowserActionSpec(R.string.browser_action_navigate, "https://b.com"), browserActionSpec("navigate", null, "https://b.com"))
        assertEquals(BrowserActionSpec(R.string.browser_action_click, "button.x"), browserActionSpec("click", "button.x", null))
        assertEquals(BrowserActionSpec(R.string.browser_action_type, "hello"), browserActionSpec("type", "input", "hello"))
        assertEquals(BrowserActionSpec(R.string.browser_action_extract, null), browserActionSpec("extract", null, null))
        assertEquals(BrowserActionSpec(R.string.browser_action_screenshot, null), browserActionSpec("screenshot", null, null))
    }

    @Test
    fun `type arg truncates long text`() {
        val spec = browserActionSpec("type", "input", "x".repeat(100))
        assertEquals(24, spec.arg!!.length)
    }

    @Test
    fun `unknown action falls back to navigate-like display`() {
        assertEquals(BrowserActionSpec(R.string.browser_action_navigate, null), browserActionSpec("hover", null, null))
    }
}
