package com.mamba.picme.features.chat.openui

import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.OpenUiValue
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `extract handle ids from program text`() {
        val program = """
            photos = Query("local_handle", { handle: "local://scan/5e2de79235c6#photos" }, [])
            videos = Query("local_handle", { handle: "local://scan/a1b2c3d4e5f6#videos" }, [])
            buckets = Query("local_handle", { handle: "local://scan/0000000000ab#histogram" }, [])
        """.trimIndent()
        // 只取 #photos 绑定：#videos/#histogram 不是照片翻页集
        assertEquals(listOf("5e2de79235c6"), extractScanPhotoHandleIds(program))
        assertEquals(emptyList<String>(), extractScanPhotoHandleIds("root = Card([])"))
    }

    @Test
    fun `extract projected photo ids from state values`() {
        val values = listOf(
            OpenUiValue.Arr(
                listOf(
                    OpenUiValue.Obj(mapOf("id" to OpenUiValue.Num(11.0), "date" to OpenUiValue.Str("2026-06"))),
                    OpenUiValue.Obj(mapOf("id" to OpenUiValue.Num(22.0))),
                    OpenUiValue.Str("not-an-object"),
                ),
            ),
            OpenUiValue.Arr(listOf(OpenUiValue.Obj(mapOf("id" to OpenUiValue.Num(11.0))))),
        )
        assertEquals(listOf(11L, 22L), extractProjectedPhotoIds(values))
    }

    @Test
    fun `card full photo uris prefer handle entries over projection and keep order`() = runTest {
        val payload = (1L..139L).joinToString(prefix = "[", postfix = "]") { id ->
            """{"id":$id,"captureDate":1700000000000,"mediaType":"PHOTO"}"""
        }
        val lookup = object : HandleLookup {
            override suspend fun payloadOf(domain: String, handleId: String): String? =
                if (domain == OpenUiContract.DOMAIN_SCAN && handleId == "5e2de79235c6") payload else null
        }
        val resolver = OpenUiPhotoResolver { photoId ->
            OpenUiPhoto(uri = "content://media/$photoId", faceFocusY = null)
        }
        val uris = resolveCardFullPhotoUris(
            handleLookup = lookup,
            photoResolver = resolver,
            programText = """photos = Query("local_handle", { handle: "local://scan/5e2de79235c6#photos" }, [])""",
            projectedIds = listOf(1L, 2L),
        )
        // handle 全量 139 条优先，投影 id 已在其中不产生重复
        assertEquals(139, uris.size)
        assertEquals("content://media/1", uris.first())
        assertEquals("content://media/139", uris.last())
    }

    @Test
    fun `card full photo uris fall back to projection when handle missing`() = runTest {
        val lookup = object : HandleLookup {
            override suspend fun payloadOf(domain: String, handleId: String): String? = null
        }
        val resolver = OpenUiPhotoResolver { photoId ->
            OpenUiPhoto(uri = "content://media/$photoId", faceFocusY = null)
        }
        val uris = resolveCardFullPhotoUris(
            handleLookup = lookup,
            photoResolver = resolver,
            programText = """photos = Query("local_handle", { handle: "local://scan/deadbeef0000#photos" }, [])""",
            projectedIds = listOf(7L, 8L),
        )
        assertEquals(listOf("content://media/7", "content://media/8"), uris)
    }
}
