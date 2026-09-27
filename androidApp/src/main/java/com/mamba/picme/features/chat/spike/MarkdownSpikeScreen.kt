package com.mamba.picme.features.chat.spike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.delay
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

/**
 * M3 spike 屏幕（ADR-016 §7.1，验证后整体可删）：mikepenz multiplatform-markdown-renderer 能力摸底。
 *
 * - 流式鲁棒：LaunchedEffect 按 [STREAM_CHUNK_CHARS] 字符步进追加全文，驱动 `Markdown` 重组
 *   （`retainState = true` 防 loading 态闪烁、`immediate = true` 同步解析规避异步空窗）。
 * - 表格/代码高亮：GFM 表格走库内置 table 组件；围栏代码块走 `-code` 模块
 *   [highlightedCodeFence]/[highlightedCodeBlock]（SnipMeDev Highlights）。
 * - 白名单内联 HTML：`<u>`/`<mark>`/`<sup>`/`<sub>` 经 [spikeInlineHtmlAnnotator] 自定义
 *   annotator 渲染（库默认丢弃 HTML_TAG，此处演示自定义成本）。
 */
@Composable
fun MarkdownSpikeScreen(modifier: Modifier = Modifier) {
    var streamedLen by remember { mutableIntStateOf(0) }
    var streaming by remember { mutableStateOf(true) }
    var cycles by remember { mutableIntStateOf(0) }

    LaunchedEffect(streaming) {
        while (streaming) {
            if (streamedLen < SPIKE_MARKDOWN.length) {
                streamedLen = (streamedLen + STREAM_CHUNK_CHARS).coerceAtMost(SPIKE_MARKDOWN.length)
                delay(STREAM_TICK_MS)
            } else {
                cycles++
                delay(STREAM_RESTART_DELAY_MS)
                streamedLen = 0
            }
        }
    }

    val content = SPIKE_MARKDOWN.substring(0, streamedLen)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row {
            Button(onClick = { streaming = !streaming }) {
                Text(if (streaming) "暂停流式" else "继续流式")
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "$streamedLen/${SPIKE_MARKDOWN.length} 字 · 第 ${cycles + 1} 轮",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "spike-progress" },
            )
        }
        Spacer(Modifier.height(8.dp))
        SpikeMarkdown(content = content, modifier = Modifier.fillMaxWidth())
    }
}

/** mikepenz 渲染入口（spike 参数即建议正式参数）：retainState 防闪 + immediate 同步解析 + 高亮组件 + HTML 白名单 annotator。 */
@Composable
fun SpikeMarkdown(content: String, modifier: Modifier = Modifier) {
    val markdownState = rememberMarkdownState(
        content = content,
        retainState = true,
        immediate = true,
    )
    Markdown(
        markdownState = markdownState,
        colors = markdownColor(text = MaterialTheme.colorScheme.onSurface),
        typography = markdownTypography(),
        components = markdownComponents(
            codeFence = highlightedCodeFence,
            codeBlock = highlightedCodeBlock,
        ),
        annotator = spikeInlineHtmlAnnotator(),
        modifier = modifier.semantics { contentDescription = "spike-markdown" },
    )
}

/**
 * 白名单内联 HTML annotator（硬指标④自定义成本演示，~40 行）：
 * 拦截 HTML_TAG 节点，`<u>`/`<mark>`/`<sup>`/`<sub>` 开标签推 SpanStyle、闭标签弹栈；
 * 非白名单标签同样消费丢弃（正文禁嵌渲染级 HTML 不变，与 two-tier spec §8 一致）。
 *
 * 🔴 spike 实测踩坑记录：lambda 首个参数 [content] 才是 AST 所属的文档（树一致），
 * 绝不能用闭包捕获的外部 content——retainState 下旧树与新 annotator 存在竞态，
 * 节点区间超出捕获文本直接 StringIndexOutOfBounds（2026-09-27 真机闪退实证）。
 * 深度计数按 AnnotatedString.Builder 实例复位（跨 build 不残留，未闭合标签样式泄漏到段尾属可接受中间态）。
 */
@Composable
fun spikeInlineHtmlAnnotator() = remember {
    val markColor = Color(0xFFFFF176)
    var openStyles = 0
    var lastBuilder: AnnotatedString.Builder? = null
    markdownAnnotator { content, child ->
        if (lastBuilder !== this) { openStyles = 0; lastBuilder = this }
        if (child.type != MarkdownTokenTypes.HTML_TAG) return@markdownAnnotator false
        when (child.getTextInNode(content).toString().lowercase().trim()) {
            "<u>" -> { pushStyle(SpanStyle(textDecoration = TextDecoration.Underline)); openStyles++ }
            "<mark>" -> { pushStyle(SpanStyle(background = markColor)); openStyles++ }
            "<sup>" -> { pushStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 11.sp)); openStyles++ }
            "<sub>" -> { pushStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 11.sp)); openStyles++ }
            "</u>", "</mark>", "</sup>", "</sub>" -> if (openStyles > 0) { pop(); openStyles-- }
        }
        true
    }
}

private const val STREAM_CHUNK_CHARS = 6
private const val STREAM_TICK_MS = 40L
private const val STREAM_RESTART_DELAY_MS = 1200L

/** 代表性长文：标题/粗斜体/行内代码/链接/列表/引用/GFM 表格/围栏代码/内联 HTML/删除线。 */
internal val SPIKE_MARKDOWN = """
# M3 Spike 验证文

这是**粗体**与*斜体*混合，`inline code`，以及[示例链接](https://example.com/path?q=1)。

## 列表

- 无序项 A：带 **加粗**
- 无序项 B
1. 有序项 1
2. 有序项 2

> 引用块第一行
> 引用块第二行

## GFM 表格

| 指标 | 现状 compose-markdown | mikepenz 候选 |
|:-----|:---------------------|:--------------|
| 表格 | 自研网格组件 | 库内置 GFM table |
| 代码高亮 | 无（等宽纯文本） | -code 模块 Highlights |
| 一个相当长长长长长长的单元格用来验证换行行为是否正常工作 | 短 | 中等长度内容 |

## 代码块

```kotlin
fun main() {
    val scores = listOf(98, 76, 88)
    println("avg = ${'$'}{scores.average()}")
}
```

```json
{"spike": true, "metrics": ["table", "code", "html"]}
```

## 内联 HTML 白名单

<u>下划线文本</u>、<mark>高亮文本</mark>、水的化学式 H<sub>2</sub>O、平方 x<sup>2</sup>。

~~删除线~~与**嵌套*斜体*粗体**收尾。
""".trimIndent()
