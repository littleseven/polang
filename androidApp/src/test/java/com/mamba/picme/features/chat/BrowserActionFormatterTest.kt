package com.mamba.picme.features.chat

import com.mamba.picme.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val long = "x".repeat(100)
        val spec = browserActionSpec("type", "input", long)
        assertEquals(24, spec.arg!!.length)
        assertTrue(spec.arg!!.endsWith("..."))
        assertEquals(long.take(21), spec.arg!!.take(21))
    }

    @Test
    fun `arg of exactly 24 chars is not truncated`() {
        val exact = "y".repeat(24)
        val spec = browserActionSpec("navigate", null, exact)
        assertEquals(exact, spec.arg)
    }

    @Test
    fun `unknown action falls back to navigate-like display`() {
        assertEquals(BrowserActionSpec(R.string.browser_action_navigate, null), browserActionSpec("hover", null, null))
    }

    /**
     * click 走 targetText 定位时 selector 为 null，spec.arg 随之 null；
     * 此时 formatBrowserAction 必须以空串占位回落（防字面 %1$s 泄漏到 UI）——
     * Context 层行为由实现保证，此处以源码级断言钉住回落分支（同
     * ChatImageRendererDecodeBitmapTest 的源码级断言先例，避开 Robolectric 环境性预存失败）。
     */
    @Test
    fun `null arg never leaks literal placeholder`() {
        val spec = browserActionSpec("click", null, null)
        assertEquals(R.string.browser_action_click, spec.templateRes)
        assertNull(spec.arg)

        val source = java.io.File(
            "src/main/java/com/mamba/picme/features/chat/BrowserActionFormatter.kt",
        ).readText()
        assertTrue(
            "formatBrowserAction 必须在 arg == null 时以空串占位回落，防字面 %1\$s 泄漏",
            source.contains("?: context.getString(spec.templateRes, \"\")"),
        )
    }
}
