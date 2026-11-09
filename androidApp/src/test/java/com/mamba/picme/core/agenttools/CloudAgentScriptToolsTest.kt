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
        assertEquals("<meta name=\"polang-lib\" content=\"echarts\"><div id=\"c\"></div>", artifact?.first)
        assertEquals("共12个区间；照片：峰值 2022-03=1531", artifact?.second)
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
        assertEquals(listOf("globalThis.Chart = {};"), evaled)
    }
}
