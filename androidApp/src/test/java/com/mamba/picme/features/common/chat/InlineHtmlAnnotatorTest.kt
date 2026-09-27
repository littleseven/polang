package com.mamba.picme.features.common.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单内联 HTML annotator（[createInlineHtmlAnnotator]）行为契约测试：
 * 驱动方式——用 GFM 解析器产出真实 AST，深度优先走查节点，annotator 返回 true 即消费（模拟
 * mikepenz 渲染器语义），否则叶子节点原文追加，最终断言 AnnotatedString 的文本与样式区间。
 *
 * 覆盖 M3 review 🟡2：四白名单标签样式 / 非白名单丢弃 / 未闭合泄漏不崩且 Builder 复位 /
 * 带空格与属性的标签变体按现状丢弃。
 */
class InlineHtmlAnnotatorTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor())
    private val markColor = Color(0xFFFFB020).copy(alpha = 0.35f)

    /** 模拟渲染器消费 AST：annotator 消费的节点不入文本，其余叶子原文追加。 */
    private fun render(
        markdown: String,
        annotator: com.mikepenz.markdown.model.MarkdownAnnotator = createInlineHtmlAnnotator(markColor),
        builder: AnnotatedString.Builder = AnnotatedString.Builder(),
    ): AnnotatedString {
        val tree = parser.buildMarkdownTreeFromString(markdown)

        fun walk(node: ASTNode) {
            val consumed = annotator.annotate?.invoke(builder, markdown, node) == true
            if (consumed) return
            if (node.children.isEmpty()) {
                if (node.type != MarkdownTokenTypes.EOL) {
                    builder.append(node.getTextInNode(markdown).toString())
                }
            } else {
                node.children.forEach { child -> walk(child) }
            }
        }
        walk(tree)
        return builder.toAnnotatedString()
    }

    @Test
    fun `whitelist tags push expected span styles`() {
        val result = render("A<u>下划线</u>B<mark>高亮</mark>C<sup>2</sup>D<sub>2</sub>E")
        assertEquals("A下划线B高亮C2D2E", result.text)

        fun rangeOn(sub: String): SpanStyle {
            val start = result.text.indexOf(sub)
            assertTrue("未找到片段 $sub", start >= 0)
            val ranges = result.spanStyles.filter { range -> range.start == start && range.end == start + sub.length }
            assertEquals("片段 $sub 应有唯一样式区间：$ranges", 1, ranges.size)
            return ranges.single().item
        }

        assertEquals(TextDecoration.Underline, rangeOn("下划线").textDecoration)
        assertEquals(markColor, rangeOn("高亮").background)
        val sup = rangeOn("2")
        // C<sup>2</sup> 与 D<sub>2</sub> 文本相同，分别按出现位置断言
        val supStart = result.text.indexOf("2")
        val subStart = result.text.indexOf("2", supStart + 1)
        val supRange = result.spanStyles.single { range -> range.start == supStart }.item
        val subRange = result.spanStyles.single { range -> range.start == subStart }.item
        assertEquals(BaselineShift.Superscript, supRange.baselineShift)
        assertEquals(0.75.em, supRange.fontSize)
        assertEquals(BaselineShift.Subscript, subRange.baselineShift)
        assertEquals(0.75.em, subRange.fontSize)
        assertEquals(BaselineShift.Superscript, sup.baselineShift) // 复用首个命中校验
    }

    @Test
    fun `non-whitelist tags are consumed without styles`() {
        val result = render("A<b>粗</b>C<i>斜</i>D<br>E")
        assertEquals("A粗C斜DE", result.text)
        assertTrue("非白名单标签不应产生样式：${result.spanStyles}", result.spanStyles.isEmpty())
    }

    @Test
    fun `unclosed tag leaks to end without crash and builder reset isolates next render`() {
        val annotator = createInlineHtmlAnnotator(markColor)
        val first = render("<u>泄漏到段尾", annotator = annotator)
        assertEquals("泄漏到段尾", first.text)
        assertEquals(1, first.spanStyles.size)
        assertEquals(0, first.spanStyles.single().start)
        assertEquals(first.text.length, first.spanStyles.single().end)

        // 同一 annotator + 新 Builder：深度计数必须复位，不受上一场未闭合污染
        val second = render("干净文本", annotator = annotator, builder = AnnotatedString.Builder())
        assertEquals("干净文本", second.text)
        assertTrue("新 Builder 不应残留样式：${second.spanStyles}", second.spanStyles.isEmpty())
    }

    @Test
    fun `tag variants with space or attributes fall back to drop behavior`() {
        val result = render("""A<u >甲</u>B<u class="c">乙</u>C""")
        assertEquals("A甲B乙C", result.text)
        assertTrue("变体标签按非白名单丢弃不应产生样式：${result.spanStyles}", result.spanStyles.isEmpty())
    }
}
