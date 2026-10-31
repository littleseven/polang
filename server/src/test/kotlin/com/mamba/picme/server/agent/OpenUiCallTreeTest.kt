package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiCallTreeTest {

    private val program = """
        photos = Query("local_handle", { handle: "local://scan/f9d33db29ed6#photos" }, [])
        root = Card([Text("标题", "HEADLINE"),
            Row([Image(photos[0].id), Column([Text("海边日落"), Text("2026-07-18", "CAPTION")])],
                weights = [1, 2], align = "center")])
    """.trimIndent()

    @Test
    fun `parses nested component calls with original-text spans`() {
        val roots = OpenUiCallTree.parse(program)!!
        val names = roots.map { root -> root.name }
        assertEquals(listOf("Query", "Card"), names)

        val card = roots.last()
        assertEquals("Card", card.name)
        // Card 直接子组件：Text + Row
        assertEquals(listOf("Text", "Row"), componentChildren(card).map { node -> node.name })
        // span 锚定原文：Card 调用整体区间首字符是 'C'
        assertEquals('C', program[card.spanStart])
        assertEquals(')', program[card.spanEnd - 1])
        // args 开区间 [argsStart, argsEnd)：前字符 '('、后字符 ')' 均在区间外
        assertEquals('(', program[card.argsStart - 1])
        assertEquals(')', program[card.argsEnd])
        // Row 子组件：Image + Column；Query/@动作调用不属组件子项
        val row = componentChildren(card).last()
        assertEquals(listOf("Image", "Column"), componentChildren(row).map { node -> node.name })
    }

    @Test
    fun `filters action and query calls out of component children`() {
        val actionProgram = "root = Card([Button(\"查看\", @OpenUrl(\"https://x.com\")), Text(\"hi\")])"
        val card = OpenUiCallTree.parse(actionProgram)!!.last()
        assertEquals(listOf("Button", "Text"), componentChildren(card).map { node -> node.name })
    }

    @Test
    fun `returns null on unbalanced program`() {
        assertNull(OpenUiCallTree.parse("root = Card([Text(\"截断\""))
        // 夹具：闭引号被转义（程序内含 \"），字符串吞到 EOF 仍未闭合 → null
        assertNull(OpenUiCallTree.parse("root = Card([Text(\"未闭串\\\")])"))
        // I-2：小写头调用的 ( 同样计深——截断程序不能借 foo 的 ) 虚假配平出错误树
        assertNull(OpenUiCallTree.parse("root = Card([foo(1), Text(\"a\")"))
        // 程序开头多余 ) → 深度为负/空栈弹空 → null
        assertNull(OpenUiCallTree.parse(") root = Card([Text(\"a\")])"))
    }

    @Test
    fun `ignores parens inside line comments`() {
        val commentProgram = """
            root = Card([Text("a"), // 行注释里的 ) 不入深度
                Text("b") # 井号注释里的 ) 同理
                ])
        """.trimIndent()
        val card = OpenUiCallTree.parse(commentProgram)!!.last()
        assertEquals(listOf("Text", "Text"), componentChildren(card).map { node -> node.name })
    }

    @Test
    fun `anchors span at identifier when whitespace precedes paren`() {
        // I-1：validator CALL_NAME 容忍名与括号间空白——span 须锚定标识符起始，不能按长度倒推
        val spaced = "root = Card ([Text(\"a\")])"
        val card = OpenUiCallTree.parse(spaced)!!.last()
        assertEquals('C', spaced[card.spanStart])
        assertEquals('T', spaced[componentChildren(card).first().spanStart])
        assertEquals(')', spaced[card.spanEnd - 1])
    }

    @Test
    fun `all golden programs parse`() {
        listOf(
            RenderOpenUiToolService.GOLDEN_STATS_PROGRAM,
            RenderOpenUiToolService.GOLDEN_MIXED_PROGRAM,
            RenderOpenUiToolService.GOLDEN_INTERACTIVE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_REPORT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_STAT_TILE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_PHOTO_TEXT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_LAYOUT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_CLASSIC_PROGRAM,
            RenderOpenUiToolService.GOLDEN_PHOTO_PROGRAM,
        ).forEach { golden ->
            assertTrue(golden, OpenUiCallTree.parse(golden) != null)
        }
    }
}

/** 组件子项 = 名字在组件白名单内的直接子调用（剔除 Query 取数与 @动作调用）。 */
private fun componentChildren(node: OpenUiCallTree.CallNode): List<OpenUiCallTree.CallNode> =
    OpenUiCallTree.componentChildren(node)
