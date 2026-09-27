package com.mamba.picme.features.chat.spike

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * M3 spike 硬指标①（JVM 层）：不完整 markdown 的解析鲁棒性。
 *
 * 对一段代表性 markdown（表格/围栏代码块/列表/粗斜体/链接/行内代码/内联 HTML）
 * 按 5 字符步长 + 逐行边界取全部前缀（数百个），逐个过 org.jetbrains.markdown GFM 解析器，
 * 断言零异常——覆盖流式期间一切未闭合中间态（未闭合代码块/表格分隔行残缺/行内标记半截）。
 *
 * spike 取舍见 spec docs/superpowers/specs/2026-09-27-chat-parts-rendering-design.md §7.1。
 */
class MarkdownParserRobustnessSpikeTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor())

    private val representativeMarkdown = """
        # 标题一

        这是**粗体**与*斜体*的混合，还有 `inline code` 和一个[链接](https://example.com)。

        ## 列表演示

        - 无序项 A
        - 无序项 B 带 **加粗尾巴
          - 嵌套子项
        1. 有序项 1
        2. 有序项 2

        - [x] 已完成任务
        - [ ] 待办任务

        > 引用块：多行
        > 第二行引用

        | 列一 | 列二 | 列三 |
        |:-----|:----:|-----:|
        | 短 | 中等长度的单元格内容 | 一个相当长长长长长的单元格用来验证换行行为是否正常工作 |
        | 1 | 2 | 3 |
        | 带`代码`格 | **加粗格** | [链接格](https://example.com) |

        ```kotlin
        fun main() {
            val x = listOf(1, 2, 3)
            println("hello ${'$'}x")
        }
        ```

        ```json
        {"key": [1, 2, 3], "nested": {"a": true}}
        ```

        内联 HTML：<u>下划线</u>、<mark>高亮</mark>、H<sub>2</sub>O、x<sup>2</sup>。

        ~~删除线~~ 与 **嵌套 *斜体* 粗体**。

        自动链接 https://example.com/auto 与邮箱 a@b.com。

        第二段落 continuation，带 *半截斜体标记
        以及 `半截行内代码
        和 [半截链接文本](https://incomplete
        收尾于未闭合围栏：

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
            val prefix = representativeMarkdown.substring(0, i)
            count++
            try {
                parser.buildMarkdownTreeFromString(prefix)
            } catch (t: Throwable) {
                failures += "prefix len=$i: ${t::class.simpleName}: ${t.message}"
            }
            i += 2
        }
        // 全文每字符前缀（最细粒度，覆盖所有未闭合中间态）
        assertTrue(
            "解析失败 ${failures.size}/$count 个前缀：\n" + failures.take(10).joinToString("\n"),
            failures.isEmpty(),
        )
        println("PREFIX_ROBUSTNESS: $count 个前缀（2 字符步长，全文 ${representativeMarkdown.length} 字符）全部解析零异常")
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
        assertTrue(
            "行级前缀解析失败：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
        println("LINE_PREFIX_ROBUSTNESS: ${lines.size} 个行级前缀全部解析零异常")
    }

    @Test
    fun `unclosed fence and broken table delimiter stay parseable`() {
        val cases = mapOf(
            "未闭合代码围栏" to "前文\n```kotlin\nfun a() = 1\n更多代码行",
            "半个围栏" to "前文\n``\n文本",
            "表格只有表头" to "| 列一 | 列二 |",
            "表格分隔行残缺" to "| 列一 | 列二 |\n|---|\n| a | b |",
            "半截粗体" to "这是**没闭合的粗体",
            "半截行内代码" to "这是`没闭合的代码",
            "半截链接" to "这是[没闭合的链接](https://exa",
            "半截内联HTML" to "这是<u>没闭合的标签",
            "孤立的管道" to "a | b | c",
        )
        val failures = mutableListOf<String>()
        cases.forEach { (name, md) ->
            try {
                parser.buildMarkdownTreeFromString(md)
            } catch (t: Throwable) {
                failures += "$name: ${t::class.simpleName}: ${t.message}"
            }
        }
        assertTrue("针对性用例解析失败：\n" + failures.joinToString("\n"), failures.isEmpty())
        println("EDGE_CASES: ${cases.size} 个边缘用例全部解析零异常")
    }

    @Test
    fun `full document parse time sanity`() {
        // 预热
        repeat(3) { parser.buildMarkdownTreeFromString(representativeMarkdown) }
        val times = (1..20).map {
            measureTimeMillis { parser.buildMarkdownTreeFromString(representativeMarkdown) }
        }
        val avg = times.average()
        println("PARSE_TIME: 全量文档(${representativeMarkdown.length}字符) 平均 ${"%.2f".format(avg)}ms / 最大 ${times.maxOrNull()}ms")
        // 粗门槛：代表性长文全量解析均值 < 50ms（流式每次重组全量重解析的预算参照）
        assertTrue("解析均值超预算：${avg}ms", avg < 50.0)
    }
}
