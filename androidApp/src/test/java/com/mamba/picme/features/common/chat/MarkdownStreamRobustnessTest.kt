package com.mamba.picme.features.common.chat

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式解析鲁棒性守护（M3 review 🟡2，spike MarkdownParserRobustnessSpikeTest 的精简在树版）：
 * 代表性 markdown（标题/粗斜体/行内代码/链接/列表/引用/GFM 表格/围栏代码/内联 HTML/删除线）
 * 按 5 字符步长取全部前缀 + 行级前缀 + 未闭合边缘用例，逐个过 GFM 解析器断言零异常——
 * 覆盖流式期间一切未闭合中间态，防解析库升级或渲染路径变更引入崩溃回归。
 */
class MarkdownStreamRobustnessTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor())

    private val representativeMarkdown = """
        # 标题一

        这是**粗体**与*斜体*的混合，还有 `inline code` 和一个[链接](https://example.com)。

        ## 列表演示

        - 无序项 A
        - 无序项 B 带 **加粗尾巴
        1. 有序项 1

        > 引用块：多行
        > 第二行引用

        | 列一 | 列二 | 列三 |
        |:-----|:----:|-----:|
        | 短 | 中等长度的单元格 | 带`代码`格 |
        | 1 | 2 | 3 |

        ```kotlin
        fun main() {
            val x = listOf(1, 2, 3)
            println("hello ${'$'}x")
        }
        ```

        内联 HTML：<u>下划线</u>、<mark>高亮</mark>、H<sub>2</sub>O、x<sup>2</sup>。

        ~~删除线~~ 与 **嵌套 *斜体* 粗体**，收尾于未闭合围栏：

        ```python
        def unfinished():
            return "stream cut here
    """.trimIndent()

    @Test
    fun `every char-step prefix parses without exception`() {
        val failures = mutableListOf<String>()
        var count = 0
        var i = 1
        while (i <= representativeMarkdown.length) {
            count++
            try {
                parser.buildMarkdownTreeFromString(representativeMarkdown.substring(0, i))
            } catch (t: Throwable) {
                failures += "prefix len=$i: ${t::class.simpleName}: ${t.message}"
            }
            i += 5
        }
        assertTrue(
            "解析失败 ${failures.size}/$count 个前缀：\n" + failures.take(10).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `every line-boundary prefix parses without exception`() {
        val lines = representativeMarkdown.split("\n")
        val failures = mutableListOf<String>()
        for (n in 1..lines.size) {
            val prefix = lines.subList(0, n).joinToString("\n")
            try {
                parser.buildMarkdownTreeFromString(prefix)
            } catch (t: Throwable) {
                failures += "line $n: ${t::class.simpleName}: ${t.message}"
            }
        }
        assertTrue("行级前缀解析失败：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `unclosed fence and broken table delimiter stay parseable`() {
        val cases = mapOf(
            "未闭合代码围栏" to "前文\n```kotlin\nfun a() = 1\n更多代码行",
            "半个围栏" to "前文\n``\n文本",
            "表格只有表头" to "| 列一 | 列二 |",
            "表格分隔行残缺" to "| 列一 | 列二 |\n|:--|",
            "半截行内代码" to "前文 `半截代码",
            "半截链接" to "前文 [链接文本](https://incomplete",
            "半截内联 HTML" to "前文 <u>未闭合下划线",
        )
        val failures = cases.filter { (_, text) ->
            try {
                parser.buildMarkdownTreeFromString(text)
                false
            } catch (t: Throwable) {
                true
            }
        }.keys
        assertTrue("边缘用例解析失败：$failures", failures.isEmpty())
    }
}
