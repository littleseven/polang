package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.js.JsBridge
import com.mamba.picme.agent.core.js.JsEngine
import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.agent.core.js.JsValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAgentScriptToolsTest {

    /** 假引擎：记录 evalAsync 入参并返回预置结果/抛预置异常。 */
    private class FakeEngine(
        private val responder: (String) -> JsValue,
    ) : JsEngine {
        var lastCode: String? = null
        var lastTimeoutMs: Long? = null

        override fun eval(script: String): JsValue = responder(script)

        override fun evalAsync(code: String, timeoutMs: Long): JsValue {
            lastCode = code
            lastTimeoutMs = timeoutMs
            return responder(code)
        }

        override fun callFunction(name: String, vararg args: JsValue): JsValue = JsValue.Null
        override fun installBridge(bridge: JsBridge) {}
    }

    private fun tools(
        engine: JsEngine,
        registered: MutableList<JsRuntime>? = null,
    ) = CloudAgentScriptTools(
        engineFactory = { engine },
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        registerHandlers = { rt -> registered?.add(rt) },
    )

    @Test
    fun `string result is returned raw, object result is json`() = runTest {
        val strTools = tools(FakeEngine { JsValue.Str("月度趋势见下") })
        assertEquals("月度趋势见下", strTools.runScript("return '月度趋势见下'"))

        val objTools = tools(FakeEngine { JsValue.Obj(linkedMapOf("total" to JsValue.Num(8146.0))) })
        assertEquals("{\"total\":8146.0}", objTools.runScript("return {total: 8146}"))
    }

    @Test
    fun `blank code returns error contract`() = runTest {
        val t = tools(FakeEngine { JsValue.Null })
        assertEquals("error: empty script", t.runScript("   "))
    }

    @Test
    fun `eval exception becomes error contract`() = runTest {
        val t = tools(FakeEngine { throw IllegalStateException("SyntaxError: unexpected token") })
        assertEquals("error: SyntaxError: unexpected token", t.runScript("return {"))
    }

    @Test
    fun `handlers registered once and runtime reused`() = runTest {
        val registered = mutableListOf<JsRuntime>()
        val engine = FakeEngine { JsValue.Num(1.0) }
        val t = tools(engine, registered)
        t.runScript("return 1")
        t.runScript("return 2")
        assertEquals(1, registered.size)
        assertEquals("return 2", engine.lastCode)
        assertEquals(CloudAgentScriptTools.DEFAULT_EVAL_TIMEOUT_MS, engine.lastTimeoutMs)
    }

    @Test
    fun `runtime created lazily on first script`() = runTest {
        val registered = mutableListOf<JsRuntime>()
        val t = tools(FakeEngine { JsValue.Null }, registered)
        assertTrue(registered.isEmpty())
        t.runScript("return null")
        assertEquals(1, registered.size)
    }

    @Test
    fun `html artifact becomes envelope, parseable back`() = runTest {
        val t = tools(
            FakeEngine {
                JsValue.Obj(
                    linkedMapOf(
                        "html" to JsValue.Str("<meta name=\"polang-lib\" content=\"echarts\"><div id=\"c\"></div>"),
                        "summary" to JsValue.Str("共12个区间；照片：峰值 2022-03=1531"),
                    ),
                )
            },
        )
        val out = t.runScript("return Chart.echarts({...})")
        val artifact = CloudAgentScriptTools.parseHtmlArtifact(out)
        assertEquals(1, artifact?.cards?.size)
        assertEquals(
            "<meta name=\"polang-lib\" content=\"echarts\"><div id=\"c\"></div>",
            artifact?.cards?.first()?.first,
        )
        assertEquals("共12个区间；照片：峰值 2022-03=1531", artifact?.cards?.first()?.second)
        assertEquals(null, artifact?.stats)
    }

    @Test
    fun `registered artifact wraps any return shape, stats strip html`() = runTest {
        // 2026-11-09 真机事故形状：LLM 把 Chart.echarts 结果包进 {chart: {...}, total, months} 返回。
        // 注册制后：drain 回收注册表发卡（卡不丢），原 return 剥除 html 后作为 stats 上行。
        val t = tools(
            FakeEngine { script ->
                if (script.contains("JSON.stringify(globalThis.__polangArtifacts")) {
                    JsValue.Str("[{\"html\":\"<div>card</div>\",\"summary\":\"照片：峰值 2022-03=1531\"}]")
                } else {
                    JsValue.Obj(
                        linkedMapOf(
                            "chart" to JsValue.Obj(
                                linkedMapOf(
                                    "html" to JsValue.Str("<div>card</div>"),
                                    "summary" to JsValue.Str("照片：峰值 2022-03=1531"),
                                ),
                            ),
                            "total" to JsValue.Num(8146.0),
                            "months" to JsValue.Num(12.0),
                        ),
                    )
                }
            },
        )
        val out = t.runScript("var c = Chart.echarts({...}); return {chart: c, total: 8146, months: 12}")
        val artifact = CloudAgentScriptTools.parseHtmlArtifact(out)
        assertEquals(listOf("<div>card</div>" to "照片：峰值 2022-03=1531"), artifact?.cards)
        // 注：org.json 往返把整值 Double 规范成整数形态（8146.0→8146），值不变
        assertEquals("{\"total\":8146,\"months\":12}", artifact?.stats)
        assertTrue(out.contains("\"stats\""))
        // 完整 HTML 绝不随 stats 上行（只出现在 cards 卡槽里）
        val statsJson = artifact?.stats ?: ""
        assertTrue(!statsJson.contains("<div"))
    }

    @Test
    fun `multiple registered cards all land in envelope, last card fills compat fields`() = runTest {
        val t = tools(
            FakeEngine { script ->
                if (script.contains("JSON.stringify(globalThis.__polangArtifacts")) {
                    JsValue.Str(
                        "[{\"html\":\"<div>c1</div>\",\"summary\":\"s1\"}," +
                            "{\"html\":\"<div>c2</div>\",\"summary\":\"s2\"}]",
                    )
                } else {
                    JsValue.Obj(linkedMapOf("total" to JsValue.Num(1.0)))
                }
            },
        )
        val out = t.runScript("Chart.echarts(a); Chart.echarts(b); return {total: 1}")
        val artifact = CloudAgentScriptTools.parseHtmlArtifact(out)
        assertEquals(listOf("<div>c1</div>" to "s1", "<div>c2</div>" to "s2"), artifact?.cards)
        assertEquals("{\"total\":1}", artifact?.stats)
        // 兼容单字段取最后一卡
        val env = org.json.JSONObject(out)
        assertEquals("<div>c2</div>", env.getString("html"))
        assertEquals("s2", env.getString("summary"))
    }

    @Test
    fun `svg chart is intercepted with guidance, not returned`() = runTest {
        val t = tools(
            FakeEngine {
                JsValue.Obj(
                    linkedMapOf(
                        "chart" to JsValue.Str("<svg width=\"640\"></svg>"),
                        "summary" to JsValue.Str("..."),
                    ),
                )
            },
        )
        val out = t.runScript("return Chart.bar({...})")
        assertTrue(out.startsWith("error:"))
        assertTrue(out.contains("Chart.echarts"))
        assertTrue(!out.contains("<svg"))
    }

    @Test
    fun `plain object is not mistaken for artifact`() {
        assertEquals(null, CloudAgentScriptTools.parseHtmlArtifact("{\"total\":8146.0}"))
        assertEquals(null, CloudAgentScriptTools.parseHtmlArtifact("月度趋势见下"))
        assertEquals(null, CloudAgentScriptTools.parseHtmlArtifact("{\"__artifact__\":\"html_card\",\"html\":\"\"}"))
    }

    @Test
    fun `chart bootstrap is evaled on runtime creation`() = runTest {
        val evaled = mutableListOf<String>()
        val engine = object : JsEngine {
            override fun eval(script: String): JsValue {
                evaled.add(script)
                return JsValue.Null
            }
            override fun evalAsync(code: String, timeoutMs: Long): JsValue = JsValue.Null
            override fun callFunction(name: String, vararg args: JsValue): JsValue = JsValue.Null
            override fun installBridge(bridge: JsBridge) {}
        }
        val t = CloudAgentScriptTools(
            engineFactory = { engine },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            registerHandlers = {},
            chartBootstrapJs = { "globalThis.Chart = {};" },
        )
        t.runScript("return null")
        assertEquals(
            listOf(
                "globalThis.Chart = {};",
                "globalThis.__polangLang=\"zh-CN\";globalThis.__polangArtifacts=[];",
                "JSON.stringify(globalThis.__polangArtifacts||[])",
            ),
            evaled,
        )
    }

    @Test
    fun `app language tag is injected as js global before each script eval`() = runTest {
        val evaled = mutableListOf<String>()
        val engine = object : JsEngine {
            override fun eval(script: String): JsValue {
                evaled.add(script)
                return JsValue.Null
            }
            override fun evalAsync(code: String, timeoutMs: Long): JsValue = JsValue.Null
            override fun callFunction(name: String, vararg args: JsValue): JsValue = JsValue.Null
            override fun installBridge(bridge: JsBridge) {}
        }
        var tag = "zh-CN"
        val t = CloudAgentScriptTools(
            engineFactory = { engine },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            registerHandlers = {},
            appLanguageTag = { tag },
        )
        t.runScript("return 1")
        t.runScript("return 2")
        // 每次执行 = 合并注入（语言标签 + 清空产物注册表）+ drain 回收，共两次 eval
        val injects = evaled.filter { it.startsWith("globalThis.__polangLang") }
        assertEquals(
            listOf(
                "globalThis.__polangLang=\"zh-CN\";globalThis.__polangArtifacts=[];",
                "globalThis.__polangLang=\"zh-CN\";globalThis.__polangArtifacts=[];",
            ),
            injects,
        )
        // 应用内切换语言后下一次脚本即生效（进程级常驻 runtime 无需重建）
        tag = "fr"
        t.runScript("return 3")
        assertEquals(
            "globalThis.__polangLang=\"fr\";globalThis.__polangArtifacts=[];",
            evaled.filter { it.startsWith("globalThis.__polangLang") }.last(),
        )
    }
}
