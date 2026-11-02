package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * [OpenUiSession] 引擎测试（OpenUI Lang 1.0 §2 entry/孤儿 + §6.1 Query + §6.3 动作
 * + §8.2/§8.4 组件校验恢复），含 spec §4.3 端到端示例。
 */
class OpenUiEngineTest {

    private class FakeTools(
        private val result: OpenUiValue = OpenUiValue.Null,
        private val error: Throwable? = null,
    ) {
        val calls = mutableListOf<Pair<String, Map<String, OpenUiValue>>>()
        val resolver = OpenUiToolResolver { name, args ->
            calls += name to args
            error?.let { e -> throw e }
            result
        }
    }

    private val noTools = OpenUiToolResolver { name, _ ->
        throw OpenUiToolNotFoundException(name)
    }

    private fun rendered(result: OpenUiResult): OpenUiResult.Rendered {
        assertIs<OpenUiResult.Rendered>(result)
        return result
    }

    private fun childrenOf(component: OpenUiValue.ComponentVal): List<OpenUiValue> =
        (component.props["children"] as OpenUiValue.Arr).v

    // ── entry 规则（§2.2） ──────────────────────────────────────────

    @Test
    fun `root statement is the entry`() = runTest {
        val session = OpenUiSession("root = Card([t])\nt = Text(\"hi\")", noTools)
        val result = rendered(session.prepare())
        assertEquals("Card", result.root.type)
        assertEquals("root", result.root.statementId)
        val child = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(child)
        assertEquals("Text", child.type)
        assertEquals(OpenUiValue.Str("hi"), child.props["text"])
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `first Card value statement is the fallback entry with non fatal no-root`() = runTest {
        val session = OpenUiSession("title = Text(\"x\")\npage = Card([title])", noTools)
        val result = rendered(session.prepare())
        assertEquals("page", result.root.statementId)
        assertTrue(result.errors.any { error -> error.code == "no-root" })
    }

    @Test
    fun `fatal no-root when nothing renders`() = runTest {
        val session = OpenUiSession("t = Text(\"x\")", noTools)
        val result = session.prepare()
        assertIs<OpenUiResult.Failed>(result)
        assertTrue(result.errors.any { error -> error.code == "no-root" })
        assertNull(session.rootOrNull())
    }

    @Test
    fun `root evaluating to non component is fatal no-root without fallback`() = runTest {
        // root 在但值不是组件：致命 no-root，不再回退到其他 Card 语句（§2.2）
        val session = OpenUiSession("root = 5\npage = Card([])", noTools)
        val result = session.prepare()
        assertIs<OpenUiResult.Failed>(result)
        assertTrue(result.errors.any { error -> error.code == "no-root" })
    }

    @Test
    fun `blank program yields no statements and fails`() = runTest {
        val result = OpenUiSession("   ", noTools).prepare()
        assertIs<OpenUiResult.Failed>(result)
    }

    @Test
    fun `text without statements is fatal parse-failed`() = runTest {
        val result = OpenUiSession("just prose here", noTools).prepare()
        assertIs<OpenUiResult.Failed>(result)
        assertTrue(result.errors.any { error -> error.code == "parse-failed" })
    }

    // ── 组件校验恢复（§8.2/§8.4） ────────────────────────────────────

    @Test
    fun `unknown component in value position is dropped and reported`() = runTest {
        val session = OpenUiSession("root = Card([Nope(1), Text(\"ok\")])", noTools)
        val result = rendered(session.prepare())
        assertEquals(1, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "unknown-component" })
    }

    @Test
    fun `missing required prop drops the component`() = runTest {
        val session = OpenUiSession("t = Text()\nroot = Card([t])", noTools)
        val result = rendered(session.prepare())
        assertEquals(0, childrenOf(result.root).size)
        assertTrue(
            result.errors.any { error -> error.code == "missing-required" && error.statementId == "t" },
        )
    }

    @Test
    fun `type mismatch on required prop drops the component`() = runTest {
        val session = OpenUiSession("t = Text(5)\nroot = Card([t])", noTools)
        val result = rendered(session.prepare())
        assertEquals(0, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    @Test
    fun `type mismatch on optional enum falls back to schema default`() = runTest {
        val session = OpenUiSession("t = Text(\"a\", \"BOGUS\")\nroot = Card([t])", noTools)
        val result = rendered(session.prepare())
        val text = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(text)
        assertEquals(OpenUiValue.Str("BODY"), text.props["role"])
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    @Test
    fun `excess args are dropped and component still renders`() = runTest {
        // 2026-11-02 Text +maxLines（第 4 参）后，超额位顺移到第 5 参——意图不变：
        // 位置实参超 schema 位数 → excess-args + 组件照渲染
        val session = OpenUiSession("t = Text(\"a\", \"BODY\", \"ON_SURFACE\", 1, 99)\nroot = Card([t])", noTools)
        val result = rendered(session.prepare())
        assertEquals(1, childrenOf(result.root).size)
        assertTrue(result.errors.any { error -> error.code == "excess-args" })
    }

    @Test
    fun `explicit null skips an optional argument silently`() = runTest {
        val session = OpenUiSession("t = Text(\"a\", null)\nroot = Card([t])", noTools)
        val result = rendered(session.prepare())
        val text = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(text)
        assertEquals(OpenUiValue.Str("BODY"), text.props["role"])
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `invalid bucket elements are left out of the array`() = runTest {
        val session = OpenUiSession(
            """root = Card([BarChart("t", [{label: "a", count: 1}, {label: 2, count: 3}, "junk"])])""",
            noTools,
        )
        val result = rendered(session.prepare())
        val chart = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(chart)
        val buckets = chart.props["buckets"]
        assertIs<OpenUiValue.Arr>(buckets)
        assertEquals(1, buckets.v.size)
        assertTrue(result.errors.any { error -> error.code == "type-mismatch" })
    }

    // ── Query 宿主工具（§6.1 M1 简化） ───────────────────────────────

    @Test
    fun `query result fills the statement value through host tool`() = runTest {
        val tools = FakeTools(
            result = OpenUiValue.Arr(
                listOf(OpenUiValue.Obj(mapOf("label" to OpenUiValue.Str("a"), "count" to OpenUiValue.Num(1.0)))),
            ),
        )
        val program = """
            root = Card([BarChart("t", buckets)])
            buckets = Query("local_handle", { handle: "local://scan/7f3a#histogram" }, [])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, tools.resolver).prepare())
        val chart = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(chart)
        val buckets = chart.props["buckets"]
        assertIs<OpenUiValue.Arr>(buckets)
        assertEquals(1, buckets.v.size)
        assertEquals(
            listOf<Pair<String, Map<String, OpenUiValue>>>(
                "local_handle" to mapOf("handle" to OpenUiValue.Str("local://scan/7f3a#histogram")),
            ),
            tools.calls,
        )
    }

    @Test
    fun `query tool not found keeps defaults and reports tool-not-found`() = runTest {
        val program = """
            root = Card([BarChart("t", buckets)])
            buckets = Query("nope", {}, [{label: "d", count: 1}])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, noTools).prepare())
        val chart = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(chart)
        val buckets = chart.props["buckets"]
        assertIs<OpenUiValue.Arr>(buckets)
        assertEquals(1, buckets.v.size) // defaults
        assertTrue(result.errors.any { error -> error.code == "tool-not-found" && error.statementId == "buckets" })
    }

    @Test
    fun `query tool exception keeps defaults and reports tool-error`() = runTest {
        val tools = FakeTools(error = IllegalStateException("boom"))
        val program = """
            root = Card([BarChart("t", buckets)])
            buckets = Query("local_handle", {}, [])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, tools.resolver).prepare())
        assertTrue(result.errors.any { error -> error.code == "tool-error" })
    }

    @Test
    fun `query tool cancellation propagates instead of degrading`() = runTest {
        // 协程取消必须穿透（review 🟡2）：吞掉会让调用方 cancel 失效
        val tools = FakeTools(error = CancellationException("cancelled"))
        val program = """
            root = Card([BarChart("t", buckets)])
            buckets = Query("local_handle", {}, [])
        """.trimIndent()
        assertFailsWith<CancellationException> {
            OpenUiSession(program, tools.resolver).prepare()
        }
    }

    @Test
    fun `deep nesting does not crash and degrades to parse failure`() = runTest {
        // §8.1 MUST「模型写的任何东西不得让客户端崩溃」（review 🟡3）：
        // 数千层嵌套 → 深度上限/Throwable 兜底 → Failed，不 StackOverflow
        val program = "a = " + "[".repeat(5000) + "1" + "]".repeat(5000)
        val result = OpenUiSession(program, noTools).prepare()
        assertIs<OpenUiResult.Failed>(result)
        assertTrue(
            result.errors.any { error -> error.code == "parse-exception" || error.code == "parse-failed" },
        )
    }

    // ── @Set/@Reset 重求值（§6.3 M1 简化） ───────────────────────────

    @Test
    fun `set action rewrites state and re-evaluates without rerunning queries`() = runTest {
        val tools = FakeTools(
            result = OpenUiValue.Arr(
                listOf(OpenUiValue.Obj(mapOf("label" to OpenUiValue.Str("a"), "count" to OpenUiValue.Num(1.0)))),
            ),
        )
        val program = """
            ${'$'}selected = ""
            root = Card([Text(${'$'}selected), chart])
            chart = BarChart("t", buckets, ${'$'}selected)
            buckets = Query("local_handle", {}, [])
        """.trimIndent()
        val session = OpenUiSession(program, tools.resolver)
        rendered(session.prepare())
        assertEquals(1, tools.calls.size)

        session.applyAction(OpenUiAction("Set", listOf(OpenUiValue.Str("一月")), target = "\$selected"))

        assertEquals(OpenUiValue.Str("一月"), session.state["\$selected"])
        // M2：Text.text 升 BINDABLE_STRING——$selected 原文收 Binding，渲染期从 state 读
        val text = childrenOf(session.rootOrNull()!!).first()
        assertIs<OpenUiValue.ComponentVal>(text)
        assertEquals(OpenUiValue.Binding("\$selected"), text.props["text"])
        // Query 结果缓存：重求值不重跑
        assertEquals(1, tools.calls.size)
        // 绑定属性重建后仍是 Binding
        val chart = childrenOf(session.rootOrNull()!!)[1]
        assertIs<OpenUiValue.ComponentVal>(chart)
        assertEquals(OpenUiValue.Binding("\$selected"), chart.props["selected"])
    }

    @Test
    fun `reset action restores declared defaults`() = runTest {
        val program = """
            ${'$'}selected = ""
            root = Card([Text(${'$'}selected)])
        """.trimIndent()
        val session = OpenUiSession(program, noTools)
        rendered(session.prepare())
        session.applyAction(OpenUiAction("Set", listOf(OpenUiValue.Str("x")), target = "\$selected"))
        assertEquals(OpenUiValue.Str("x"), session.state["\$selected"])
        session.applyAction(
            OpenUiAction("Reset", listOf(OpenUiValue.Str("\$selected")), target = "\$selected"),
        )
        assertEquals(OpenUiValue.Str(""), session.state["\$selected"])
    }

    // ── 动作表达式（§6.3 M1 简化） ───────────────────────────────────

    @Test
    fun `bare action call equals single element Action wrapper`() = runTest {
        val session = OpenUiSession("root = Card([Button(\"Save\", @ToAssistant(\"Save\"))])", noTools)
        val result = rendered(session.prepare())
        val button = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(button)
        val action = button.props["action"]
        assertIs<OpenUiValue.ActionVal>(action)
        assertEquals(
            listOf(OpenUiAction("ToAssistant", listOf(OpenUiValue.Str("Save")))),
            action.actions,
        )
    }

    @Test
    fun `action wrapper flattens several actions in order`() = runTest {
        val program = """
            ${'$'}a = 0
            root = Card([Button("Do", Action([@Set(${'$'}a, 1), @OpenUrl("https://x")]))])
        """.trimIndent()
        val session = OpenUiSession(program, noTools)
        val result = rendered(session.prepare())
        val button = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(button)
        val action = button.props["action"]
        assertIs<OpenUiValue.ActionVal>(action)
        assertEquals(2, action.actions.size)
        assertEquals(OpenUiAction("Set", listOf(OpenUiValue.Num(1.0)), target = "\$a"), action.actions[0])
        assertEquals(OpenUiAction("OpenUrl", listOf(OpenUiValue.Str("https://x"))), action.actions[1])
    }

    // ── 孤儿与可达性（§2.3） ────────────────────────────────────────

    @Test
    fun `unreachable value statements are collected as orphans`() = runTest {
        val program = """
            root = Card([t])
            t = Text("a")
            old = Text("b")
        """.trimIndent()
        val session = OpenUiSession(program, noTools)
        rendered(session.prepare())
        assertEquals(setOf("old"), session.orphans)
    }

    @Test
    fun `statement referenced from query args is never an orphan`() = runTest {
        val tools = FakeTools(result = OpenUiValue.Arr(emptyList()))
        val program = """
            root = Card([Text("a")])
            buckets = Query("local_handle", { h: handleId }, [])
            handleId = "local://x"
        """.trimIndent()
        val session = OpenUiSession(program, tools.resolver)
        rendered(session.prepare())
        assertEquals(emptySet(), session.orphans)
        assertEquals(
            listOf<Pair<String, Map<String, OpenUiValue>>>(
                "local_handle" to mapOf("h" to OpenUiValue.Str("local://x")),
            ),
            tools.calls,
        )
    }

    // ── 初始 state（viewState 恢复） ────────────────────────────────

    @Test
    fun `initial state applies over declared defaults`() = runTest {
        val program = """
            ${'$'}selected = ""
            root = Card([Text(${'$'}selected)])
        """.trimIndent()
        val session = OpenUiSession(
            program,
            noTools,
            initialState = mapOf("\$selected" to OpenUiValue.Str("x")),
        )
        rendered(session.prepare())
        val text = childrenOf(session.rootOrNull()!!).single()
        assertIs<OpenUiValue.ComponentVal>(text)
        // M2：Text($selected) 存 Binding；初始 state 覆盖默认值由渲染期解析体现
        assertEquals(OpenUiValue.Binding("\$selected"), text.props["text"])
        assertEquals(OpenUiValue.Str("x"), session.state["\$selected"])
    }

    // ── 端到端（设计 spec §4.3 示例程序） ────────────────────────────

    @Test
    fun `end to end spec example renders with query filled buckets`() = runTest {
        val tools = FakeTools(
            result = OpenUiValue.Arr(
                listOf(
                    OpenUiValue.Obj(mapOf("label" to OpenUiValue.Str("2026-09"), "count" to OpenUiValue.Num(12.0))),
                    OpenUiValue.Obj(mapOf("label" to OpenUiValue.Str("2026-10"), "count" to OpenUiValue.Num(7.0))),
                ),
            ),
        )
        val program = """
            ${'$'}selected = ""
            root = Card([title, chart])
            title = Text("月度照片分布", "SUBHEAD")
            chart = BarChart("按月份", buckets, ${'$'}selected)
            buckets = Query("local_handle", { handle: "local://scan/7f3a#histogram" }, [])
        """.trimIndent()
        val session = OpenUiSession(program, tools.resolver)
        val result = rendered(session.prepare())

        assertEquals(emptyList(), result.errors)
        assertEquals("Card", result.root.type)
        val children = childrenOf(result.root)
        assertEquals(2, children.size)

        val title = children[0]
        assertIs<OpenUiValue.ComponentVal>(title)
        assertEquals("Text", title.type)
        assertEquals(OpenUiValue.Str("月度照片分布"), title.props["text"])
        assertEquals(OpenUiValue.Str("SUBHEAD"), title.props["role"])
        assertEquals(OpenUiValue.Str("ON_SURFACE"), title.props["colorRole"])

        val chart = children[1]
        assertIs<OpenUiValue.ComponentVal>(chart)
        assertEquals("BarChart", chart.type)
        assertEquals(OpenUiValue.Str("按月份"), chart.props["title"])
        assertEquals(OpenUiValue.Binding("\$selected"), chart.props["selected"])
        val buckets = chart.props["buckets"]
        assertIs<OpenUiValue.Arr>(buckets)
        assertEquals(2, buckets.v.size)

        assertEquals(1, tools.calls.size)
        assertEquals("local_handle", tools.calls[0].first)
    }

    // ── Image 组件（spec《openui-image-component-design》D3） ──────

    @Test
    fun `image renders from photos projection id reference`() = runTest {
        // mock local_handle 返回 #photos 投影形态 [{id, date}]；photo 经 photos[0].id 求值为 Num
        val tools = FakeTools(
            result = OpenUiValue.Arr(
                listOf(
                    OpenUiValue.Obj(
                        mapOf(
                            "id" to OpenUiValue.Num(101.0),
                            "date" to OpenUiValue.Str("2026-10-01"),
                        ),
                    ),
                ),
            ),
        )
        val program = """
            photos = Query("local_handle", { handle: "local://scan/7f3a#photos" }, [])
            root = Card([Text("海边之旅", "HEADLINE"), Image(photos[0].id), Text(photos[0].date, "CAPTION")])
        """.trimIndent()
        val result = rendered(OpenUiSession(program, tools.resolver).prepare())
        assertEquals(emptyList(), result.errors)
        val children = childrenOf(result.root)
        assertEquals(3, children.size)
        val image = children[1]
        assertIs<OpenUiValue.ComponentVal>(image)
        assertEquals("Image", image.type)
        assertEquals(OpenUiValue.Num(101.0), image.props["photo"])
        // aspect 无默认字面量（渲染期缺省 4:3）
        assertEquals(null, image.props["aspect"])
    }

    @Test
    fun `image with explicit aspect keeps the number`() = runTest {
        val program = """root = Card([Image(42, 1.5)])"""
        val result = rendered(OpenUiSession(program, noTools).prepare())
        val image = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(image)
        assertEquals(OpenUiValue.Num(42.0), image.props["photo"])
        assertEquals(OpenUiValue.Num(1.5), image.props["aspect"])
    }

    @Test
    fun `image without photo or src stays for placeholder rendering`() = runTest {
        // 2026-10-25 全量补全：photo 降可选（+src 公网图），皆无 → 渲染层兜底占位不丢组件
        val program = """root = Card([Image(), Text("ok")])"""
        val result = rendered(OpenUiSession(program, noTools).prepare())
        assertEquals(2, childrenOf(result.root).size)
        assertTrue(result.errors.none { error -> error.code == "missing-required" })
    }

    // ── 契约字面量 ──────────────────────────────────────────────────

    @Test
    fun `contract viewState round trips selected label`() {
        assertEquals("selected=一月", OpenUiContract.viewStateOfSelected("一月"))
        assertEquals("一月", OpenUiContract.selectedOfViewState("selected=一月"))
        assertNull(OpenUiContract.selectedOfViewState("other"))
        assertNull(OpenUiContract.selectedOfViewState(null))
        assertEquals("data_ui_openui", OpenUiContract.PART_TYPE)
        assertEquals("local_handle", OpenUiContract.TOOL_LOCAL_HANDLE)
        assertEquals("photos", OpenUiContract.FRAGMENT_PHOTOS)
    }

    // ── 动作名契约（2026-11-01 review Y1/Y7） ──────────────────────────

    @Test
    fun `contract action names and engine action set are pinned`() {
        // 动作名词表字面量钉住：server RenderOpenUiValidator 持副本，改动须双向同步核对
        assertEquals("Set", OpenUiContract.Actions.SET)
        assertEquals("Reset", OpenUiContract.Actions.RESET)
        assertEquals("Run", OpenUiContract.Actions.RUN)
        assertEquals("ToAssistant", OpenUiContract.Actions.TO_ASSISTANT)
        assertEquals("OpenUrl", OpenUiContract.Actions.OPEN_URL)
        assertEquals("OpenPhoto", OpenUiContract.Actions.OPEN_PHOTO)
        assertEquals("Action", OpenUiContract.Actions.WRAPPER)
        // eval 可产出的全集（宿主 dispatch 必须穷尽覆盖，androidApp 侧路由清单测试钉住）
        assertEquals(
            setOf("Set", "Reset", "Run", "ToAssistant", "OpenUrl"),
            OpenUiContract.ENGINE_ACTIONS,
        )
    }

    // ── viewState 键值对泛化（2026-11-01 review Y4/Y7） ───────────────

    @Test
    fun `viewState encodes and decodes key value pairs with escaping`() {
        val entries = mapOf(
            "selected" to "一月",
            "month" to "a=b;c%d",
            "weird=name" to "v",
        )
        val encoded = OpenUiContract.encodeViewState(entries)
        // 转义后分隔符不泄漏（% / = / ; 三字符 percent-编码）
        assertTrue(encoded.contains("a%3Db%3Bc%25d"))
        assertTrue(encoded.contains("weird%3Dname"))
        assertEquals(entries, OpenUiContract.decodeViewState(encoded))
    }

    @Test
    fun `viewState encode strips dollar prefix from state keys`() {
        // store 键含 $ 前缀，线格式 name 不含——恢复时由宿主映射回 $name → Str
        assertEquals("selected=x", OpenUiContract.encodeViewState(mapOf("\$selected" to "x")))
    }

    @Test
    fun `viewState decode keeps legacy subset and tolerates malformed segments`() {
        // 旧格式 "selected=<label>" 是一阶子集，旧数据恢复行为不变
        assertEquals(
            mapOf("selected" to "一月"),
            OpenUiContract.decodeViewState("selected=一月"),
        )
        assertEquals("一月", OpenUiContract.selectedOfViewState("selected=一月"))
        // 畸形段（无 = / 空名 / 空段）丢弃，与旧解析的容错口径一致
        assertEquals(
            mapOf("a" to "1"),
            OpenUiContract.decodeViewState("garbage;a=1;=x;;"),
        )
        assertEquals(emptyMap(), OpenUiContract.decodeViewState(null))
        assertEquals(emptyMap(), OpenUiContract.decodeViewState(""))
    }

    // ── 计数器模式与 @Set 防御分支（review Y7） ────────────────────────

    @Test
    fun `counter pattern set action args refresh after re-evaluation`() = runTest {
        // @Set($n, $n + 1)：渲染期烘焙的 action 参数在 applyAction 重求值后随 store
        // 刷新——连点计数器必须 1→2→3，而不是永远写回渲染初值 1
        val program = """
            ${'$'}n = 0
            root = Card([Button("plus", @Set(${'$'}n, ${'$'}n + 1))])
        """.trimIndent()
        val session = OpenUiSession(program, noTools)
        rendered(session.prepare())
        val firstButton = childrenOf(session.rootOrNull()!!).single()
        assertIs<OpenUiValue.ComponentVal>(firstButton)
        val firstAction = (firstButton.props["action"] as OpenUiValue.ActionVal).actions.single()
        assertEquals(OpenUiAction("Set", listOf(OpenUiValue.Num(1.0)), target = "\$n"), firstAction)

        session.applyAction(firstAction)

        assertEquals(OpenUiValue.Num(1.0), session.state["\$n"])
        val secondButton = childrenOf(session.rootOrNull()!!).single()
        assertIs<OpenUiValue.ComponentVal>(secondButton)
        val secondAction = (secondButton.props["action"] as OpenUiValue.ActionVal).actions.single()
        assertEquals(OpenUiAction("Set", listOf(OpenUiValue.Num(2.0)), target = "\$n"), secondAction)
    }

    @Test
    fun `set action with non state ref target degrades to empty action list`() = runTest {
        // @Set("x", 1)：首参非 $变量引用 → evalSetAction 防御分支产出
        // ActionVal(emptyList)（静默 no-op），不臆造 target 写穿 store
        val program = """root = Card([Button("bad", @Set("x", 1))])"""
        val session = OpenUiSession(program, noTools)
        val result = rendered(session.prepare())
        val button = childrenOf(result.root).single()
        assertIs<OpenUiValue.ComponentVal>(button)
        val action = button.props["action"]
        assertIs<OpenUiValue.ActionVal>(action)
        assertEquals(emptyList<OpenUiAction>(), action.actions)
        assertNull(session.state["x"])
    }
}
