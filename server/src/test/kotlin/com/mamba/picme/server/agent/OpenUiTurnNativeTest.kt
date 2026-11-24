package com.mamba.picme.server.agent

import com.mamba.picme.agent.core.model.context.ReplyLanguage
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

    // 2026-11-01 故宫卡事故：模型响应尾部混入 DSML 工具调用边界标记，validate 宽容
    // 放行致标记载体落流到端上——终结消息兜底必须先剥掉再放行
    @Test
    fun `ensureValidProgram strips dsml tool-call tail from valid program`() {
        val program = "// summary: 行程\nroot = Card([Text(\"行程\", \"HEADLINE\")])" +
            "\n</｜｜DSML｜｜ parameter>\n</｜｜DSML｜｜ invoke>\n</｜｜DSML｜｜ calls>"
        assertEquals(
            "// summary: 行程\nroot = Card([Text(\"行程\", \"HEADLINE\")])",
            OpenUiTurnNative.ensureValidProgram(program),
        )
    }

    @Test
    fun `system prompt contains response-is-program contract and golden examples`() {
        val prompt = OpenUiTurnNative.SYSTEM_PROMPT
        assertTrue(prompt.contains("你的整个回复就是一颗 OpenUI Lang 1.0 程序"))
        assertTrue(prompt.contains("// summary:"))
        assertTrue(prompt.contains("root = "))
        // 黄金示例同源（改示例测试必红，防 prompt 与校验器脱节）
        assertTrue(prompt.contains(OpenUiPromptAssets.GOLDEN_STATS_PROGRAM))
    }

    // 2026-11-01 回复活感批次：真机实证模型被卡片示例带偏，一切回复皆表单卡
    // （「明天穿什么」也回 Card+标题+Callout+Timeline）——提示词须先教「选形态」
    @Test
    fun `system prompt pins reply-shape guidance and chat golden example`() {
        val prompt = OpenUiTurnNative.SYSTEM_PROMPT
        assertTrue(prompt.contains("回复形态先定位"))
        // 对话类回复 = 裸 Markdown 正文，禁表单卡包装
        assertTrue(prompt.contains("禁止包装成 Card + 标题 + Callout + Timeline"))
        // 数据卡不做冷报表：首子级 Markdown 导语 + 收尾讲解
        assertTrue(prompt.contains("Markdown 自然导语"))
        // 对话黄金示例同源内嵌（改示例测试必红），且自身是合法程序
        assertTrue(prompt.contains(OpenUiTurnNative.GOLDEN_CHAT_PROGRAM))
        assertNull(RenderOpenUiValidator.validateProgramToError(OpenUiTurnNative.GOLDEN_CHAT_PROGRAM))
    }

    // 2026-11-24 follow-up 批次：形态定位段放宽——对话回复有自然后续时 root 可
    // 升级为 Column([Markdown, Buttons]) 补选项排；没有后续保持裸 Markdown
    @Test
    fun `system prompt pins follow-up options guidance`() {
        val prompt = OpenUiTurnNative.SYSTEM_PROMPT
        assertTrue(prompt.contains("Column([Markdown(正文), Buttons([...])])"))
        assertTrue(prompt.contains("没有自然后续就保持裸 Markdown 干净收尾"))
        // 后续选项黄金示例经 DSL_RULES 同源内嵌进系统提示词，且自身过终结校验
        assertTrue(prompt.contains(OpenUiPromptAssets.GOLDEN_FOLLOWUP_PROGRAM))
        assertNull(RenderOpenUiValidator.validateProgramToError(OpenUiPromptAssets.GOLDEN_FOLLOWUP_PROGRAM))
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

    // 2026-11-08 真机事故：云端回合 LLM 产出 ECharts HTML 卡后，终结落流变成硬编码
    // 「回复生成失败，请重试」——整程序扫描（含字符串字面量内部）的违规模式在 Markdown
    // 兜底包装后仍然存活，wrap 复验失败即硬降级。以下四例钉住三态分布与典型踩坑面。

    private val echartsBody =
        "<meta name='polang-lib' content='echarts'>" +
            "<div id='c' style='width:100%;height:260px'></div>" +
            "<script>(function(){var el=document.getElementById('c');" +
            "if(typeof echarts==='undefined'){el.innerHTML='图表库未加载';return;}" +
            "var chart=echarts.init(el);chart.setOption({tooltip:{trigger:'axis'}," +
            "xAxis:{type:'category',data:['6月','7月','8月']},yAxis:{type:'value'}," +
            "series:[{name:'照片',type:'bar',data:[320,452,601]}]});})();</script>"

    @Test
    fun `ensureValidProgram passes echarts polang-lib meta program through`() {
        // echarts-card 技能契约正确形态：meta 声明 + 内联脚本，整程序过校验原样放行
        val program = "// summary: 相册月度趋势\n" +
            "root = Card([Text(\"相册月度趋势\", \"HEADLINE\"), " +
            "Text(\"点柱子看每月数值\", \"CAPTION\"), " +
            "Html(html = \"$echartsBody\")])"
        assertEquals(program, OpenUiTurnNative.ensureValidProgram(program))
    }

    @Test
    fun `ensureValidProgram hard-fails on file uri script src`() {
        // LLM 习惯性写 file:///android_asset 引脚本：MEDIA_PATH 是整程序扫描，
        // Markdown 包装后违规仍存活 → 硬编码保底（观测走 picme-agent WARN 日志）
        val bad = echartsBody.replace(
            "<meta name='polang-lib' content='echarts'>",
            "<script src='file:///android_asset/polang-lib/echarts.min.js'></script>",
        )
        val out = OpenUiTurnNative.ensureValidProgram(
            "// summary: 相册月度趋势\nroot = Card([Html(html = \"$bad\")])",
        )
        assertEquals("// summary: 错误\nroot = Markdown(\"回复生成失败，请重试\")", out)
    }

    @Test
    fun `ensureValidProgram hard-fails on content uri in html`() {
        val bad = echartsBody + "<img src='content://media/external/images/12345'>"
        val out = OpenUiTurnNative.ensureValidProgram(
            "// summary: 相册月度趋势\nroot = Card([Html(html = \"$bad\")])",
        )
        assertEquals("// summary: 错误\nroot = Markdown(\"回复生成失败，请重试\")", out)
    }

    // 2026-11-22 OpenUI 多语言批次：终结兜底文案随 X-App-Language 五语化（shared SSOT）
    @Test
    fun `ensureValidProgram hard fallback follows reply language`() {
        val bad = echartsBody + "<img src='content://media/external/images/12345'>"
        val program = "// summary: 相册月度趋势\nroot = Card([Html(html = \"$bad\")])"
        assertEquals(
            "// summary: Error\nroot = Markdown(\"Failed to generate the reply, please try again\")",
            OpenUiTurnNative.ensureValidProgram(program, ReplyLanguage.ENGLISH),
        )
        assertEquals(
            "// summary: Erreur\nroot = Markdown(\"Échec de la génération de la réponse, veuillez réessayer\")",
            OpenUiTurnNative.ensureValidProgram(program, ReplyLanguage.FRENCH),
        )
    }

    @Test
    fun `ensureValidProgram markdown wrap summary label follows reply language`() {
        val wrapped = OpenUiTurnNative.ensureValidProgram("plain chat text", ReplyLanguage.SPANISH)
        assertTrue(wrapped.startsWith("// summary: Respuesta de texto\n"))
        assertNull(RenderOpenUiValidator.validateProgramToError(wrapped))
    }

    @Test
    fun `ensureValidProgram passes cdn script src through for client sanitizer`() {
        // CDN 远程脚本不是服务端违规面（无媒体路径）：程序放行，由端侧 sanitizer
        // 剔除远程 script、卡内兜底文案外显——软失败语义钉住，防两端职责漂移
        val cdn = echartsBody.replace(
            "<meta name='polang-lib' content='echarts'>",
            "<script src='https://cdn.jsdelivr.net/npm/echarts@5.6.0/dist/echarts.min.js'></script>",
        )
        val program = "// summary: 相册月度趋势\nroot = Card([Html(html = \"$cdn\")])"
        assertEquals(program, OpenUiTurnNative.ensureValidProgram(program))
    }

    @Test
    fun `ensureValidProgram survives huge Html literal`() {
        // 2026-11-04 SOE 回归：终结兜底变换（normalize + validate + 兜底包装复验）
        // 对整页 HTML 大字面量不爆栈，合法程序原样放行
        val body = buildString {
            append("<html><body>")
            var i = 0
            while (length < 60_000) {
                append("<div class=\\\"r\\\" data-i=\\\"").append(i % 10).append("\\\">x</div>")
                i++
            }
            append("</body></html>")
        }
        val program = "// summary: 演示\nroot = Html(\"$body\")"
        assertEquals(program, OpenUiTurnNative.ensureValidProgram(program))
    }
}
