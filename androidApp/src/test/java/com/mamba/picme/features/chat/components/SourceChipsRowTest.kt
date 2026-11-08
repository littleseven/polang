package com.mamba.picme.features.chat.components

import com.mamba.picme.domain.chat.MessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [sourceChipsOf] 单元测试（SOURCE_CHIPS item 的数据装配，值域终态 §6.2）。
 *
 * 聚合口径与 ChatListFlattener/iOS sourceChips 对齐：自 run 首个 source part 锚
 * 取**连续** SourceUrl/SourceDocument parts，非 source part 即 break（多段 run 各自
 * 成 item，只取本段）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SourceChipsRowTest {

    @Test
    fun `consecutive source parts from anchor build chips with ordinals and hosts`() {
        val url1 = MessagePart.SourceUrl(partId = "s1", sourceId = "a", url = "https://example.com/article", title = "Example")
        val doc = MessagePart.SourceDocument(partId = "s2", sourceId = "b", mediaType = "text/html", title = "Report", filename = "report.pdf")
        val url2 = MessagePart.SourceUrl(partId = "s3", sourceId = "c", url = "http://docs.site.org/guide")

        val chips = sourceChipsOf(anchor = url1, parts = listOf(url1, doc, url2))

        assertEquals(
            listOf(
                SourceChipUi(id = "s1", ordinal = 1, label = "example.com", url = "https://example.com/article"),
                SourceChipUi(id = "s2", ordinal = 2, label = "report.pdf", url = null),
                SourceChipUi(id = "s3", ordinal = 3, label = "docs.site.org", url = "http://docs.site.org/guide"),
            ),
            chips,
        )
    }

    @Test
    fun `run stops at first non-source part - later run not included`() {
        val url1 = MessagePart.SourceUrl(partId = "s1", sourceId = "a", url = "https://example.com/x")
        val text = MessagePart.Text(partId = "t1", markdown = "正文")
        val url2 = MessagePart.SourceUrl(partId = "s2", sourceId = "b", url = "https://other.com/y")

        val chips = sourceChipsOf(anchor = url1, parts = listOf(url1, text, url2))

        assertEquals(1, chips.size)
        assertEquals("s1", chips.single().id)
    }

    @Test
    fun `document chip falls back to title when filename absent`() {
        val doc = MessagePart.SourceDocument(partId = "d1", sourceId = "a", mediaType = "application/pdf", title = "Annual Report", filename = null)

        val chips = sourceChipsOf(anchor = doc, parts = listOf(doc))

        val chip = chips.single()
        assertEquals("Annual Report", chip.label)
        assertNull(chip.url)
    }

    @Test
    fun `non-web scheme url is not clickable but label keeps host`() {
        val url = MessagePart.SourceUrl(partId = "s1", sourceId = "a", url = "ftp://files.example.com/data")

        val chips = sourceChipsOf(anchor = url, parts = listOf(url))

        val chip = chips.single()
        assertEquals("files.example.com", chip.label)
        assertNull(chip.url)
    }

    @Test
    fun `unparsable url falls back to raw string label`() {
        val url = MessagePart.SourceUrl(partId = "s1", sourceId = "a", url = "not a url")

        val chips = sourceChipsOf(anchor = url, parts = listOf(url))

        assertEquals("not a url", chips.single().label)
        assertNull(chips.single().url)
    }

    @Test
    fun `anchor not present in parts returns empty`() {
        val anchor = MessagePart.SourceUrl(partId = "s1", sourceId = "a", url = "https://example.com/x")

        val chips = sourceChipsOf(anchor = anchor, parts = listOf(MessagePart.Text(partId = "t1", markdown = "正文")))

        assertEquals(emptyList<SourceChipUi>(), chips)
    }
}
