package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.model.catalog.functions.A2uiUrlOpener
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientToServerMessage
import androidx.a2ui.model.protocol.A2uiDataPath
import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 裸 Application：绕开 PoLangApplication.onCreate 的 native 库加载（agent_native 在 JVM 不可用）。 */
class A2uiTestApplication : android.app.Application()

/**
 * A2UI 全组件可用性验证（引擎层）：catalog 注册清单 + 必填属性契约 +
 * 每组件蓝图经 HandleResolver → A2uiMessageProcessor 全链路落成 active surface。
 *
 * 组件/属性契约以官方 v0.9 basic catalog（specification/v0_9/catalogs/basic/catalog.json）
 * 为基准，外加自研 BarChart；蓝图线格式与 google/A2UI 官方 examples 对齐。
 * 渲染层（Compose 实渲）冒烟见 [A2uiComponentRenderSmokeTest]。
 *
 * 注意：surface actor 生命周期挂在收集协程的 scope 上，scope 取消即回收数据模型
 * ——承载 surface 的 scope 必须活到断言结束（见 [newHarnessScope] 的调用点）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = A2uiTestApplication::class)
class A2uiComponentAvailabilityTest {

    private val catalog: A2uiCatalog = polangA2uiCatalog(A2uiUrlOpener { })

    private val resolver = HandleResolver(EmptyLookup)

    private object EmptyLookup : HandleLookup {
        override suspend fun payloadOf(domain: String, handleId: String): String? = null
    }

    /** 一个组件的验证用例：组件 JSON 片段（含辅助子组件）+ 需预置的数据模型绑定。 */
    private data class ComponentCase(
        val name: String,
        val components: String,
        val data: List<Pair<String, String>> = emptyList(),
    )

    private val cases = listOf(
        ComponentCase(
            name = "Text",
            components = """{"id":"target","component":"Text","text":{"path":"/text"},"variant":"h2"}""",
            data = listOf("/text" to "\"Hello A2UI\""),
        ),
        ComponentCase(
            name = "Image",
            components = """{"id":"target","component":"Image","url":"https://example.com/pic.png","description":"pic","fit":"cover","variant":"smallFeature"}""",
        ),
        ComponentCase(
            name = "Icon",
            components = """{"id":"target","component":"Icon","name":"photo"}""",
        ),
        ComponentCase(
            name = "Video",
            components = """{"id":"target","component":"Video","url":"https://example.com/v.mp4"}""",
        ),
        ComponentCase(
            name = "AudioPlayer",
            components = """{"id":"target","component":"AudioPlayer","url":"https://example.com/a.mp3","description":"audio"}""",
        ),
        ComponentCase(
            name = "Row",
            components = """
                {"id":"target","component":"Row","children":["target-child"],"justify":"center","align":"center"},
                {"id":"target-child","component":"Text","text":"in-row"}
            """,
        ),
        ComponentCase(
            name = "Column",
            components = """
                {"id":"target","component":"Column","children":["target-child"],"justify":"start","align":"stretch"},
                {"id":"target-child","component":"Text","text":"in-column"}
            """,
        ),
        ComponentCase(
            name = "List",
            components = """
                {"id":"target","component":"List","children":["target-child"],"direction":"vertical"},
                {"id":"target-child","component":"Text","text":"in-list"}
            """,
        ),
        ComponentCase(
            name = "Card",
            components = """
                {"id":"target","component":"Card","child":"target-child"},
                {"id":"target-child","component":"Text","text":"in-card"}
            """,
        ),
        ComponentCase(
            name = "Tabs",
            components = """
                {"id":"target","component":"Tabs","tabs":[{"title":"Tab A","child":"target-child"}]},
                {"id":"target-child","component":"Text","text":"in-tab"}
            """,
        ),
        ComponentCase(
            name = "Modal",
            components = """
                {"id":"target","component":"Modal","trigger":"target-trigger","content":"target-content"},
                {"id":"target-trigger","component":"Button","child":"target-trigger-label","action":{"event":{"name":"open"}}},
                {"id":"target-trigger-label","component":"Text","text":"Open"},
                {"id":"target-content","component":"Text","text":"modal-body"}
            """,
        ),
        ComponentCase(
            name = "Divider",
            components = """{"id":"target","component":"Divider","axis":"horizontal"}""",
        ),
        ComponentCase(
            name = "Button",
            components = """
                {"id":"target","component":"Button","child":"target-child","variant":"primary","action":{"event":{"name":"clicked","context":{"k":"v"}}}},
                {"id":"target-child","component":"Text","text":"Click Me"}
            """,
        ),
        ComponentCase(
            name = "TextField",
            components = """{"id":"target","component":"TextField","label":"Name","value":{"path":"/tfValue"},"variant":"shortText"}""",
            data = listOf("/tfValue" to "\"polang\""),
        ),
        ComponentCase(
            name = "CheckBox",
            components = """{"id":"target","component":"CheckBox","label":"Agree","value":true}""",
        ),
        ComponentCase(
            name = "ChoicePicker",
            components = """{"id":"target","component":"ChoicePicker","options":[{"label":"Opt A","value":"a"},{"label":"Opt B","value":"b"}],"value":["a"],"variant":"mutuallyExclusive"}""",
        ),
        ComponentCase(
            name = "Slider",
            components = """{"id":"target","component":"Slider","label":"Level","value":5,"min":0,"max":10}""",
        ),
        ComponentCase(
            name = "DateTimeInput",
            components = """{"id":"target","component":"DateTimeInput","label":"When","value":"","enableDate":true,"enableTime":true}""",
        ),
        ComponentCase(
            name = "BarChart",
            components = """{"id":"target","component":"BarChart","title":"月度统计","buckets":{"path":"/buckets"},"selected":{"path":"/selected"}}""",
            data = listOf(
                "/buckets" to """[{"label":"2026-09","count":3},{"label":"2026-10","count":5}]""",
                "/selected" to "\"2026-10\"",
            ),
        ),
    )

    @Test
    fun catalog_registersExactlyExpectedComponents() {
        val names = catalog.components.map { component -> component.name }
        assertEquals("catalog 组件重名", names.size, names.toSet().size)
        assertEquals(
            "polang catalog = material 基础目录 18 组件 + 自研 BarChart",
            setOf(
                "Text", "Image", "Icon", "Video", "AudioPlayer",
                "Row", "Column", "List", "Card", "Tabs", "Modal", "Divider",
                "Button", "TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput",
                PolangBarChartComponent.NAME,
            ),
            names.toSet(),
        )
    }

    @Test
    fun catalog_registersBasicCatalogFunctions() {
        val names = catalog.functions.map { function -> function.definition.name }.toSet()
        val expected = setOf(
            "required", "regex", "length", "numeric", "email",
            "formatString", "formatNumber", "formatCurrency", "formatDate", "pluralize",
            "openUrl", "and", "or", "not",
        )
        assertTrue("缺少函数: ${expected - names}", names.containsAll(expected))
    }

    @Test
    fun requiredProperties_matchV09Contract() {
        // 官方 v0.9 basic catalog 各组件 required 属性（BarChart 为自研契约：buckets 必填）
        val expected = mapOf(
            "Text" to setOf("text"),
            "Image" to setOf("url"),
            "Icon" to setOf("name"),
            "Video" to setOf("url"),
            "AudioPlayer" to setOf("url"),
            "Row" to setOf("children"),
            "Column" to setOf("children"),
            "List" to setOf("children"),
            "Card" to setOf("child"),
            "Tabs" to setOf("tabs"),
            "Modal" to setOf("trigger", "content"),
            "Divider" to emptySet(),
            "Button" to setOf("child", "action"),
            "TextField" to setOf("label"),
            "CheckBox" to setOf("label", "value"),
            "ChoicePicker" to setOf("options", "value"),
            "Slider" to setOf("value", "max"),
            "DateTimeInput" to setOf("value"),
            "BarChart" to setOf("buckets"),
        )
        for ((name, requiredKeys) in expected) {
            val component = catalog.components[name]
            assertNotNull("catalog 缺少组件 $name", component)
            val actual = component!!.properties
                .filter { property -> property.isRequired }
                .map { property -> property.key }
                .toSet()
            assertEquals("组件 $name 必填属性契约漂移", requiredKeys, actual)
        }
    }

    @Test
    fun everyComponent_processesToActiveSurface() = runBlocking {
        val scope = newHarnessScope()
        try {
            for (case in cases) {
                val sid = "sf-test-${case.name.lowercase()}"
                val surface = processToSurface(scope, case, sid)
                assertNotNull("组件 ${case.name} 未落成 active surface", surface)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun dataModelBindings_landInSurfaceModel() = runBlocking {
        val scope = newHarnessScope()
        try {
            val textCase = cases.single { case -> case.name == "Text" }
            val textSurface = processToSurface(scope, textCase, "sf-bind-text") as A2uiCoreSurfaceModel
            assertEquals("Hello A2UI", readInSnapshot { textSurface.dataModel.get(A2uiDataPath("/text")) })

            val chartCase = cases.single { case -> case.name == "BarChart" }
            val chartSurface = processToSurface(scope, chartCase, "sf-bind-chart") as A2uiCoreSurfaceModel
            val buckets = readInSnapshot { chartSurface.dataModel.get(A2uiDataPath("/buckets")) }
            assertTrue("buckets 应为 List，实际 $buckets", buckets is List<*> && (buckets as List<*>).size == 2)
            assertEquals("2026-10", readInSnapshot { chartSurface.dataModel.get(A2uiDataPath("/selected")) })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unknownComponent_reportsClientError() = runBlocking {
        val sid = "sf-neg-unknown"
        val messagesJson = envelopeArray(
            createSurface(sid),
            updateComponents(
                sid,
                """
                {"id":"root","component":"Column","children":["target"]},
                {"id":"target","component":"NoSuchWidget","text":"boom"}
                """,
            ),
        )
        val error = awaitClientError(sid, messagesJson)
        assertNotNull("未知组件应上报 client error", error)
    }

    @Test
    fun wrongCatalogId_reportsClientError() = runBlocking {
        val sid = "sf-neg-catalog"
        val messagesJson = envelopeArray(
            """{"version":"v0.9","createSurface":{"surfaceId":"$sid","catalogId":"https://example.com/other"}}""",
            updateComponents(sid, """{"id":"root","component":"Text","text":"hi"}"""),
        )
        val error = awaitClientError(sid, messagesJson)
        assertNotNull("未注册 catalogId 应上报 client error", error)
    }

    @Test
    fun envelopeMissingVersion_degradesCleanly() = runBlocking {
        // 缺 version 的信封：A2uiJsonMessageParser 拒收 → HandleResolver 降级（不炸聊天页）
        val result = resolver.resolve("sf-neg-version", """[{"createSurface":{"surfaceId":"sf-neg-version","catalogId":"x"}}]""")
        assertTrue(result is A2uiResolution.Degraded)
        assertEquals("envelope_invalid", (result as A2uiResolution.Degraded).reason)
    }

    // ── 驱动辅助 ────────────────────────────────────────────────────────

    /** 承载 surface actor 的 scope：必须活到断言结束，取消即回收数据模型。 */
    private fun newHarnessScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 蓝图经 HandleResolver（生产解析路径）→ processor，等待 surface 激活。 */
    private suspend fun processToSurface(
        scope: CoroutineScope,
        case: ComponentCase,
        sid: String,
    ): androidx.a2ui.model.processor.A2uiSurfaceModel {
        val processor = A2uiMessageProcessor(catalogs = listOf(catalog))
        scope.launch { processor.collectMessages() }
        // 哨兵消息收尾：surface actor 按序处理，哨兵落位 ⇒ 前面全部消息已处理完
        val messagesJson = envelopeArray(
            createSurface(sid),
            updateComponents(
                sid,
                """{"id":"root","component":"Column","children":["target"]},${case.components}""",
            ),
            *case.data.map { (path, value) -> updateData(sid, path, value) }.toTypedArray(),
            updateData(sid, "/__sentinel", "\"done\""),
        )
        val resolution = resolver.resolve(sid, messagesJson)
        assertTrue(
            "组件 ${case.name} 蓝图应 Ready（实际 $resolution）",
            resolution is A2uiResolution.Ready,
        )
        (resolution as A2uiResolution.Ready).messages.forEach { message ->
            processor.processMessage(message)
        }
        val surface = withTimeout(10_000) {
            processor.activeSurfaces.first { list -> list.any { item -> item.id == sid } }
        }.first { item -> item.id == sid }
        // 数据模型底层是 Compose SnapshotStateMap：跨线程读取须开新快照才看得到已落账的写。
        // 每轮轮询开新快照，等价于「读到全局最新」。
        val dataModel = (surface as A2uiCoreSurfaceModel).dataModel
        withTimeout(10_000) {
            while (readInSnapshot { dataModel.get(A2uiDataPath("/__sentinel")) } != "done") delay(20)
        }
        return surface
    }

    /**
     * 喂入蓝图并等待首个 client error（负路径断言用）。
     * 确定性等待（而非定长 delay）：满载全量回归时 actor 调度延迟不定。
     */
    private suspend fun awaitClientError(
        sid: String,
        messagesJson: String,
    ): A2uiClientToServerMessage {
        val scope = newHarnessScope()
        try {
            val processor = A2uiMessageProcessor(catalogs = listOf(catalog))
            scope.launch { processor.collectMessages() }
            // outboundEvents 是无 replay 的 SharedFlow：必须先挂收集再喂消息，否则先发的错误被错过
            val errorDeferred = scope.async {
                processor.outboundEvents.first { message -> message is A2uiClientErrorMessage }
            }
            val resolution = resolver.resolve(sid, messagesJson)
            assertTrue(resolution is A2uiResolution.Ready)
            (resolution as A2uiResolution.Ready).messages.forEach { message ->
                processor.processMessage(message)
            }
            return withTimeout(10_000) { errorDeferred.await() }
        } finally {
            scope.cancel()
        }
    }

    /** Compose 快照态读包装：测试线程（无帧驱动）必须显式开新快照才能读到 actor 线程已落账的写。 */
    private fun <T> readInSnapshot(block: () -> T): T =
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot(block)

    private fun envelopeArray(vararg envelopes: String): String =
        envelopes.joinToString(",", "[", "]")

    private fun createSurface(sid: String): String =
        """{"version":"v0.9","createSurface":{"surfaceId":"$sid","catalogId":"${A2uiCardContract.CATALOG_ID}"}}"""

    private fun updateComponents(sid: String, components: String): String =
        """{"version":"v0.9","updateComponents":{"surfaceId":"$sid","components":[$components]}}"""

    private fun updateData(sid: String, path: String, valueJson: String): String =
        """{"version":"v0.9","updateDataModel":{"surfaceId":"$sid","path":"$path","value":$valueJson}}"""
}
