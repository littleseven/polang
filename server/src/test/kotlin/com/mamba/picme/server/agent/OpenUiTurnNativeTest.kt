package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合原生（turn-native）系统提示词契约 + 终结消息兜底变换测试（T7）：
 * 钉住「响应即程序」契约字面量 / 兜底包装可解析 / 黄金示例同源 /
 * 组件全量清单内嵌（T2 白名单违规文案指向系统提示词，故清单必须真在提示词里）。
 */
class OpenUiTurnNativeTest {

    @Test
    fun `ensureValidProgram normalizes and passes valid program through`() {
        val program = "root = Card([Text(\"hi\")])"
        assertEquals(program, OpenUiTurnNative.ensureValidProgram(program))
    }

    @Test
    fun `ensureValidProgram wraps invalid text into markdown fallback`() {
        val wrapped = OpenUiTurnNative.ensureValidProgram("就是一句 \"闲聊\"\n第二行")
        assertTrue(wrapped.startsWith("// summary:"))
        assertTrue(wrapped.contains("root = Markdown(\""))
        // 引号与换行已转义，程序可被引擎解析（结构级校验通过）
        assertNull(RenderOpenUiValidator.validateProgramToError(wrapped))
    }

    @Test
    fun `system prompt contains response-is-program contract and golden examples`() {
        val prompt = OpenUiTurnNative.SYSTEM_PROMPT
        assertTrue(prompt.contains("你的整个回复就是一颗 OpenUI Lang 1.0 程序"))
        assertTrue(prompt.contains("// summary:"))
        assertTrue(prompt.contains("root = "))
        // 黄金示例同源（改示例测试必红，防 prompt 与校验器脱节）
        assertTrue(prompt.contains(RenderOpenUiToolService.GOLDEN_STATS_PROGRAM))
    }

    @Test
    fun `system prompt embeds full component allowlist`() {
        val prompt = OpenUiTurnNative.SYSTEM_PROMPT
        // 代表性条目钉住（T2 裁决：白名单违规文案指向系统提示词，清单必须真实内嵌）
        listOf("Html", "ChartGrid", "PhotoGrid").forEach { name ->
            assertTrue("系统提示词缺组件 $name", prompt.contains(name))
        }
        // 与校验器白名单同源：全量组件无一遗漏
        RenderOpenUiValidator.ALLOWED_COMPONENTS.forEach { name ->
            assertTrue("系统提示词缺组件 $name", prompt.contains(name))
        }
    }
}
