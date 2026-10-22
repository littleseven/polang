package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage
import com.mamba.picme.data.local.HandleLookup
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric：parseEnvelope 走 android.util.JsonReader（AndroidA2uiJsonReader），纯 JVM stub 下静默返默认值。
@RunWith(RobolectricTestRunner::class)
class HandleResolverTest {

    private class FakeLookup(
        private val payloads: Map<Pair<String, String>, String>,
    ) : HandleLookup {
        override suspend fun payloadOf(domain: String, handleId: String): String? =
            payloads[domain to handleId]
    }

    private fun envelopeWithHandle(handle: String): String =
        """[{"version":"v0.9","updateDataModel":{"surfaceId":"sf-1","path":"/buckets","value":"$handle"}}]"""

    @Test
    fun resolve_scanHandle_fillsMonthlyBuckets() = runTest {
        val payload = """[{"id":1,"captureDate":1782000000000},{"id":2,"captureDate":1782000100000}]"""
        val resolver = HandleResolver(
            FakeLookup(mapOf("scan" to "abc123000000" to payload)),
            timeZone = TimeZone.UTC,
        )
        val result = resolver.resolve("sf-1", envelopeWithHandle("local://scan/abc123000000#histogram"))
        assertTrue(result is A2uiResolution.Ready)
        result as A2uiResolution.Ready
        // 填槽后 messages 内不再含 local:// 字面量；具体桶值经 A2uiUpdateDataModelMessage.value 断言
        val rendered = result.messages.toString()
        assertTrue(!rendered.contains("local://"))
        // 两笔同月（UTC）→ 单桶 count == 2
        val value = result.messages.filterIsInstance<A2uiUpdateDataModelMessage>().single().value
        assertTrue(value is List<*>)
        val firstBucket = (value as List<*>)[0] as Map<*, *>
        assertEquals(2, firstBucket["count"] as Int)
    }

    @Test
    fun resolve_missingHandle_degrades() = runTest {
        val resolver = HandleResolver(FakeLookup(emptyMap()))
        val result = resolver.resolve("sf-1", envelopeWithHandle("local://scan/deadbeef0000#histogram"))
        assertTrue(result is A2uiResolution.Degraded)
    }

    @Test
    fun resolve_nonObjectElement_degradesWithoutThrow() = runTest {
        // 终审 M1：顶层数组内含非对象元素，optJSONObject 防护 → Degraded 而非 JSONException 逃逸
        val resolver = HandleResolver(FakeLookup(emptyMap()))
        val result = resolver.resolve("sf-1", """["oops"]""")
        assertTrue(result is A2uiResolution.Degraded)
        assertEquals("envelope_invalid", (result as A2uiResolution.Degraded).reason)
    }

    @Test
    fun resolve_noHandle_passesThrough() = runTest {
        val resolver = HandleResolver(FakeLookup(emptyMap()))
        val json = """[{"version":"v0.9","createSurface":{"surfaceId":"sf-1","catalogId":"x"}}]"""
        val result = resolver.resolve("sf-1", json)
        assertTrue(result is A2uiResolution.Ready)
        assertEquals(1, (result as A2uiResolution.Ready).messages.size)
    }

    // fix-a2ui-blueprint-contract：LLM 可能输出 data 批量形式（服务端描述已收紧禁止，端侧防御纵深容错展开）。
    // 蓝图原文（sf-a2u1c0de ③）："updateDataModel":{"surfaceId":"...","data":{"buckets":"local://...","selected":0}}
    @Test
    fun resolve_dataBatchForm_expandsAndFillsHandle() = runTest {
        val payload = """[{"id":1,"captureDate":1782000000000},{"id":2,"captureDate":1782000100000}]"""
        val resolver = HandleResolver(
            FakeLookup(mapOf("scan" to "abc123000000" to payload)),
            timeZone = TimeZone.UTC,
        )
        val json = """[{"version":"v0.9","updateDataModel":{"surfaceId":"sf-1",""" +
            """"data":{"buckets":"local://scan/abc123000000#histogram","selected":0}}}]"""
        val result = resolver.resolve("sf-1", json)
        assertTrue(result is A2uiResolution.Ready)
        result as A2uiResolution.Ready
        assertTrue(!result.messages.toString().contains("local://"))
        // data 两个 key 展开为两条 updateDataModel：/buckets 填槽 + /selected 原样透传
        val updates = result.messages.filterIsInstance<A2uiUpdateDataModelMessage>()
        assertEquals(2, updates.size)
        val buckets = updates.single { it.path == "/buckets" }.value
        assertTrue(buckets is List<*>)
        assertEquals(2, ((buckets as List<*>)[0] as Map<*, *>)["count"] as Int)
        val selected = updates.single { it.path == "/selected" }.value
        assertEquals(0, selected)
    }

    @Test
    fun resolve_dataBatchForm_withoutHandle_passesThrough() = runTest {
        val resolver = HandleResolver(FakeLookup(emptyMap()))
        val json = """[{"version":"v0.9","updateDataModel":{"surfaceId":"sf-1",""" +
            """"data":{"title":"概览","count":3}}}]"""
        val result = resolver.resolve("sf-1", json)
        assertTrue(result is A2uiResolution.Ready)
        result as A2uiResolution.Ready
        val updates = result.messages.filterIsInstance<A2uiUpdateDataModelMessage>()
        assertEquals(2, updates.size)
        assertEquals("概览", updates.single { it.path == "/title" }.value)
        assertEquals(3, updates.single { it.path == "/count" }.value)
    }

    @Test
    fun resolve_dataBatchForm_missingHandle_degrades() = runTest {
        val resolver = HandleResolver(FakeLookup(emptyMap()))
        val json = """[{"version":"v0.9","updateDataModel":{"surfaceId":"sf-1",""" +
            """"data":{"buckets":"local://scan/deadbeef0000#histogram"}}}]"""
        val result = resolver.resolve("sf-1", json)
        assertTrue(result is A2uiResolution.Degraded)
    }
}
