package com.mamba.picme.features.chat.openui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「查看全部」全量命中集解析钉住：句柄失效/畸形/空集 → null（宿主 toast 降级），
 * 正常 payload 保持搜索相关度原序全量返回（不受投影 60 cap 影响）。
 */
class ScanPhotosViewAllTest {

    @Test
    fun `null payload resolves to null`() {
        assertNull(parseScanPhotoIds(null))
    }

    @Test
    fun `malformed payload resolves to null`() {
        assertNull(parseScanPhotoIds("not-json"))
        assertNull(parseScanPhotoIds("{\"unexpected\":true}"))
    }

    @Test
    fun `empty entries resolve to null`() {
        assertNull(parseScanPhotoIds("[]"))
    }

    @Test
    fun `full hit set keeps original order without projection cap`() {
        val payload = buildString {
            append("[")
            (1L..150L).forEach { id ->
                if (id > 1) append(",")
                append("""{"id":$id,"captureDate":1700000000000,"mediaType":"PHOTO"}""")
            }
            append("]")
        }
        val ids = parseScanPhotoIds(payload)
        assertEquals(150, ids?.size)
        assertEquals(1L, ids?.first())
        assertEquals(150L, ids?.last())
    }

    @Test
    fun `legacy payload without mediaType parses`() {
        val payload = """[{"id":7,"captureDate":1700000000000}]"""
        assertEquals(listOf(7L), parseScanPhotoIds(payload))
    }
}
