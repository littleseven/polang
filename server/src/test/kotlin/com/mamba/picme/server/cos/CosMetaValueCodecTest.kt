package com.mamba.picme.server.cos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosMetaValueCodecTest {

    @Test
    fun `encode produces ASCII-only header-safe value for Chinese text`() {
        val encoded = CosService.encodeMetaValue("OTA 自更新基线包")
        assertTrue(encoded.all { ch -> ch.code < 128 })
    }

    @Test
    fun `decode after encode roundtrips Chinese text`() {
        val original = "修复打标崩溃 + 新增双轨"
        assertEquals(original, CosService.decodeMetaValue(CosService.encodeMetaValue(original)))
    }

    @Test
    fun `encode empty string stays empty`() {
        assertEquals("", CosService.encodeMetaValue(""))
    }

    @Test
    fun `decode legacy plain ASCII value returns as-is`() {
        // 老数据（未编码的纯 ASCII changelog）必须原样可读
        assertEquals("bugfix release", CosService.decodeMetaValue("bugfix release"))
    }
}
