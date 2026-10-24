package com.mamba.picme.features.chat.a2ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseBlueprintMessages] 纯函数契约（debug 蓝图查看器的数据源）：
 * 正常 = 逐条提取 kind/version + prettyJson；畸形 = Malformed 兜底（原文+原因），
 * 调试功能永不因载荷异常崩聊天页。
 */
class A2uiBlueprintDebuggerTest {

    // 载荷形态对齐生产实物（2026-10-22 sf-4603b33a：嵌套中文 + local:// handle）
    private val productionLikeJson =
        """[{"version":"v0.9","createSurface":{"surfaceId":"sf-test1234","catalogId":"https://polang.net/a2ui/catalog/v1.json"}},""" +
            """{"version":"v0.9","updateComponents":{"surfaceId":"sf-test1234","root":"root","components":[{"id":"root","component":"Column","children":["title"]},{"id":"title","component":"Text","text":"2026 年照片月度分布","variant":"h2"}]}},""" +
            """{"version":"v0.9","updateDataModel":{"surfaceId":"sf-test1234","data":{"buckets":"local://scan/32737b0771fb#histogram","selected":""}}}]"""

    @Test
    fun `parses production-shaped three messages with kind and version`() {
        val result = parseBlueprintMessages(productionLikeJson)

        val ok = result as BlueprintParse.Ok
        assertEquals(3, ok.messages.size)
        assertEquals(
            listOf("createSurface", "updateComponents", "updateDataModel"),
            ok.messages.map { it.kind },
        )
        assertTrue(ok.messages.all { it.version == "v0.9" })
        // prettyJson 必须可回读且含原文关键内容（中文不被转义破坏、handle 完整）
        assertTrue(ok.messages[2].prettyJson.contains("local://scan/32737b0771fb#histogram"))
        assertTrue(ok.messages[1].prettyJson.contains("2026 年照片月度分布"))
        // pretty 打印应当多行（区别于原始单行线格式）
        assertTrue(ok.messages[0].prettyJson.lines().size > 1)
    }

    @Test
    fun `malformed json yields Malformed with raw preserved`() {
        val broken = """[{"version":"v0.9","createSurface":{"surfaceId":"sf-x""" // 截断

        val result = parseBlueprintMessages(broken)

        val malformed = result as BlueprintParse.Malformed
        assertEquals(broken, malformed.raw)
        assertTrue(malformed.reason.isNotBlank())
    }

    @Test
    fun `non-array json yields Malformed`() {
        val notArray = """{"version":"v0.9","createSurface":{}}"""

        val result = parseBlueprintMessages(notArray)

        assertTrue(result is BlueprintParse.Malformed)
    }

    @Test
    fun `message without version and unknown kind still parses`() {
        val json = """[{"deleteSurface":{"surfaceId":"sf-old"}}]"""

        val result = parseBlueprintMessages(json)

        val ok = result as BlueprintParse.Ok
        assertEquals(1, ok.messages.size)
        assertEquals("deleteSurface", ok.messages[0].kind)
        assertEquals(null, ok.messages[0].version)
    }
}
