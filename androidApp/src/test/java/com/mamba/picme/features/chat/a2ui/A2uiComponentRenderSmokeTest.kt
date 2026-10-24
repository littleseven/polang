package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.model.catalog.functions.A2uiUrlOpener
import androidx.a2ui.model.protocol.A2uiDataPath
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A2UI 全组件渲染冒烟（Robolectric native graphics + Compose 实渲）：
 * 官方 basic catalog 18 组件 + 自研 BarChart 同框组合，经 A2uiSurface 真实渲染，
 * 断言无崩溃且关键文本节点存在。引擎层逐组件断言见 [A2uiComponentAvailabilityTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = A2uiTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class A2uiComponentRenderSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private object EmptyLookup : HandleLookup {
        override suspend fun payloadOf(domain: String, handleId: String): String? = null
    }

    @Test
    fun allNineteenComponents_renderTogetherWithoutCrash() {
        val sid = "sf-render-all"
        // 1px 透明 PNG data URI：Image 走 Coil 但不触网
        val pixel =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val messagesJson = """
            [
              {"version":"v0.9","createSurface":{"surfaceId":"$sid","catalogId":"${A2uiCardContract.CATALOG_ID}"}},
              {"version":"v0.9","updateComponents":{"surfaceId":"$sid","components":[
                {"id":"root","component":"Column","children":["txt","img","icn","vid","aud","row","col","lst","crd","tabs","modal","div","btn","tf","cb","cp","sld","dti","chart"]},
                {"id":"txt","component":"Text","text":{"path":"/text"},"variant":"h2"},
                {"id":"img","component":"Image","url":"$pixel","description":"pixel"},
                {"id":"icn","component":"Icon","name":"photo"},
                {"id":"vid","component":"Video","url":"https://example.com/v.mp4"},
                {"id":"aud","component":"AudioPlayer","url":"https://example.com/a.mp3","description":"audio"},
                {"id":"row","component":"Row","children":["row-child"]},
                {"id":"row-child","component":"Text","text":"in-row"},
                {"id":"col","component":"Column","children":["col-child"]},
                {"id":"col-child","component":"Text","text":"in-column"},
                {"id":"lst","component":"List","children":["lst-child"]},
                {"id":"lst-child","component":"Text","text":"in-list"},
                {"id":"crd","component":"Card","child":"crd-child"},
                {"id":"crd-child","component":"Text","text":"in-card"},
                {"id":"tabs","component":"Tabs","tabs":[{"title":"Tab A","child":"tabs-child"}]},
                {"id":"tabs-child","component":"Text","text":"in-tab"},
                {"id":"modal","component":"Modal","trigger":"modal-trigger","content":"modal-content"},
                {"id":"modal-trigger","component":"Button","child":"modal-trigger-label","action":{"event":{"name":"open"}}},
                {"id":"modal-trigger-label","component":"Text","text":"Open Modal"},
                {"id":"modal-content","component":"Text","text":"modal-body"},
                {"id":"div","component":"Divider","axis":"horizontal"},
                {"id":"btn","component":"Button","child":"btn-label","variant":"primary","action":{"event":{"name":"clicked"}}},
                {"id":"btn-label","component":"Text","text":"Click Me"},
                {"id":"tf","component":"TextField","label":"Name","value":{"path":"/tfValue"}},
                {"id":"cb","component":"CheckBox","label":"Agree","value":true},
                {"id":"cp","component":"ChoicePicker","options":[{"label":"Opt A","value":"a"},{"label":"Opt B","value":"b"}],"value":["a"]},
                {"id":"sld","component":"Slider","label":"Level","value":5,"min":0,"max":10},
                {"id":"dti","component":"DateTimeInput","label":"When","value":"","enableDate":true,"enableTime":true},
                {"id":"chart","component":"BarChart","title":"月度统计","buckets":{"path":"/buckets"},"selected":{"path":"/selected"}}
              ]}},
              {"version":"v0.9","updateDataModel":{"surfaceId":"$sid","path":"/text","value":"Hello A2UI"}},
              {"version":"v0.9","updateDataModel":{"surfaceId":"$sid","path":"/tfValue","value":"polang"}},
              {"version":"v0.9","updateDataModel":{"surfaceId":"$sid","path":"/buckets","value":[{"label":"2026-09","count":3},{"label":"2026-10","count":5}]}},
              {"version":"v0.9","updateDataModel":{"surfaceId":"$sid","path":"/selected","value":"2026-10"}},
              {"version":"v0.9","updateDataModel":{"surfaceId":"$sid","path":"/__sentinel","value":"done"}}
            ]
        """.trimIndent()

        val resolver = HandleResolver(EmptyLookup)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val surface = runBlocking {
                val processor = A2uiMessageProcessor(
                    catalogs = listOf(polangA2uiCatalog(A2uiUrlOpener { })),
                )
                scope.launch { processor.collectMessages() }
                val resolution = resolver.resolve(sid, messagesJson)
                assertTrue("全组件蓝图应 Ready（实际 $resolution）", resolution is A2uiResolution.Ready)
                (resolution as A2uiResolution.Ready).messages.forEach { message ->
                    processor.processMessage(message)
                }
                val found = withTimeout(10_000) {
                    processor.activeSurfaces.first { list -> list.any { item -> item.id == sid } }
                }.first { item -> item.id == sid }
                // 哨兵落位 ⇒ 前面全部消息已按序处理完，渲染所见即最终数据态
                val dataModel = (found as androidx.a2ui.engine.model.A2uiCoreSurfaceModel).dataModel
                withTimeout(5_000) {
                    while (dataModel.get(A2uiDataPath("/__sentinel")) != "done") delay(20)
                }
                assertNotNull(found)
                found
            }
            composeRule.setContent {
                MaterialTheme {
                    A2uiSurface(surfaceModel = surface)
                }
            }
            composeRule.waitForIdle()
            composeRule.onRoot().assertExists()
            // 抽样断言关键组件的文本节点真实上屏
            composeRule.onNodeWithText("Hello A2UI").assertExists()
            composeRule.onNodeWithText("in-card").assertExists()
            composeRule.onNodeWithText("Tab A").assertExists()
            composeRule.onNodeWithText("Open Modal").assertExists()
            composeRule.onNodeWithText("Click Me").assertExists()
            composeRule.onNodeWithText("Agree").assertExists()
            composeRule.onNodeWithText("Opt A").assertExists()
            composeRule.onNodeWithText("月度统计").assertExists()
        } finally {
            scope.cancel()
        }
    }
}
