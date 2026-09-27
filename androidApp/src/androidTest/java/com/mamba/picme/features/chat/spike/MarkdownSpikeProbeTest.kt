package com.mamba.picme.features.chat.spike

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

/**
 * M3 spike 环境探针：区分「测试环境主线程/looper 问题」与「mikepenz 组件问题」。
 * probe_trivial_compose 挂 → 环境侧；probe_markdown_static 挂而前者过 → 组件侧。
 */
class MarkdownSpikeProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun probe_trivial_compose() {
        Log.i("SpikeProbe", "PROBE1: before setContent")
        composeRule.setContent { Text("hi") }
        Log.i("SpikeProbe", "PROBE1: after setContent")
        composeRule.waitForIdle()
        Log.i("SpikeProbe", "PROBE1: after waitForIdle — PASS")
    }

    @Test
    fun probe_markdown_static() {
        Log.i("SpikeProbe", "PROBE2: before setContent")
        composeRule.setContent { SpikeMarkdown(content = SPIKE_MARKDOWN) }
        Log.i("SpikeProbe", "PROBE2: after setContent")
        composeRule.waitForIdle()
        Log.i("SpikeProbe", "PROBE2: after waitForIdle")
        composeRule.onNode(hasContentDescription("spike-markdown")).assertExists()
        Log.i("SpikeProbe", "PROBE2: assertExists — PASS")
    }
}
