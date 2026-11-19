package com.mamba.picme.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class FormatBytesTest {

    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        // `%.1f` 走默认 locale（fr 下输出 1,5），钉 US 保断言确定性
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `bytes below 1KB render as B`() {
        assertEquals("0 B", FormatBytes.format(0))
        assertEquals("1023 B", FormatBytes.format(1023))
    }

    @Test
    fun `KB and MB boundaries render with one decimal`() {
        assertEquals("1.0 KB", FormatBytes.format(1024))
        assertEquals("1.5 MB", FormatBytes.format(1572864))
    }

    @Test
    fun `GB boundary renders with one decimal`() {
        assertEquals("2.3 GB", FormatBytes.format(2469606195L))
    }
}
