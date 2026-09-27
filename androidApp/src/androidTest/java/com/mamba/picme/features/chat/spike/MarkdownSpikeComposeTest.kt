package com.mamba.picme.features.chat.spike

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * M3 spike 硬指标①（Compose 层，真机/模拟器）：流式增量渲染不崩。
 *
 * [stream_full_document_without_crash] 把 spike 全文按 5 字符步长逐前缀驱动 `SpikeMarkdown`
 * 重组（与 JVM 层前缀序列同源），全部重组完成后断言渲染节点仍在——任何解析/布局异常
 * 都会使测试进程崩溃或断言失败。
 *
 * [full_document_renders_and_screenshot] 渲染完整文档并截图落盘
 * （表格/代码高亮/内联 HTML 白名单的视觉证据，adb pull 后人工核对）。
 */
class MarkdownSpikeComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stream_full_document_without_crash() {
        // -e spikeMaxLen N 可截短文档做二分定位（默认全文）
        val args = InstrumentationRegistry.getArguments()
        val maxLen = args.getString("spikeMaxLen")?.toIntOrNull() ?: SPIKE_MARKDOWN.length
        val doc = SPIKE_MARKDOWN.substring(0, maxLen.coerceAtMost(SPIKE_MARKDOWN.length))
        var streamed by mutableStateOf("")
        composeRule.setContent {
            SpikeMarkdown(content = streamed)
        }
        val startNs = System.nanoTime()
        var count = 0
        var i = 1
        while (i <= doc.length) {
            streamed = doc.substring(0, i)
            // 每 10 步同步一次帧（崩溃在任一同步点都会上浮），降低逐帧 waitForIdle 的同步开销
            if (count % 10 == 9) {
                composeRule.waitForIdle()
                android.util.Log.i("SpikeTest", "STREAM_PROGRESS: $count prefixes, len=$i")
            }
            count++
            i += 5
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription("spike-markdown")).assertExists()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        android.util.Log.i("SpikeTest", "COMPOSE_STREAM_ROBUSTNESS: $count 个前缀重组零崩溃，耗时 ${elapsedMs}ms")
        println("COMPOSE_STREAM_ROBUSTNESS: $count 个前缀重组零崩溃，耗时 ${elapsedMs}ms")
        assertTrue(count > 100 || doc.length < 500)
    }

    @Test
    fun full_document_renders_and_screenshot() {
        composeRule.setContent {
            MarkdownSpikeScreen()
        }
        // 全文约 800 字 / 6 字每 tick / 40ms ≈ 6s，留足余量等首轮流式跑完
        Thread.sleep(9_000)
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription("spike-markdown")).assertExists()
        val bitmap: Bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            ?: error("external files dir unavailable")
        val out = File(dir, "markdown_spike_full.png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SPIKE_SCREENSHOT: ${out.absolutePath}")
        assertTrue(out.length() > 0)
    }
}
