package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.ToolParameterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudAgentToolCatalogTest {

    @Test
    fun `all three bridge tools are declared`() {
        listOf(
            CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        ).forEach { name ->
            assertNotNull(CloudAgentToolCatalog.specFor(name), "missing spec for $name")
        }
    }

    @Test
    fun `all bridge tools are LOCAL executor`() {
        listOf(
            CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        ).forEach { name ->
            assertEquals(ToolExecutor.LOCAL, CloudAgentToolCatalog.specFor(name)?.executor)
        }
    }

    @Test
    fun `spec names match contract constants exactly`() {
        assertEquals("get_gallery_summary", CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY)
        assertEquals("search_media", CloudAgentToolContract.TOOL_SEARCH_MEDIA)
        assertEquals("refine_media_search", CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH)
    }

    @Test
    fun `retired CLOUD tools - constants pinned for history replay - no catalog spec`() {
        // render_ui(A2UI)/render_ui_openui/render_html 云端工具已退役（2026-11 GenUI 唯一化）：
        // 常量字面值必须稳定（server 存量 action 帧按工具名回放），目录不再声明——
        // [PRIVACY] 闸审计面收口 LOCAL 工具
        assertEquals("render_ui", CloudAgentToolContract.TOOL_RENDER_UI)
        assertEquals("render_ui_openui", CloudAgentToolContract.TOOL_RENDER_UI_OPENUI)
        assertEquals("render_html", CloudAgentToolContract.TOOL_RENDER_HTML)
        assertNull(CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_RENDER_UI))
        assertNull(CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_RENDER_UI_OPENUI))
        assertNull(CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_RENDER_HTML))
    }

    @Test
    fun `write tools have no spec - unknown to cloud`() {
        assertNull(CloudAgentToolCatalog.specFor("delete_media"))
    }

    @Test
    fun `projection schemas promise no media references`() {
        listOf(
            CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        ).forEach { name ->
            val projection = CloudAgentToolCatalog.specFor(name)!!.projectionSchema
            assertTrue(projection.contains("不含"), "$name projection must state exclusion clause")
        }
    }

    @Test
    fun mediaSearchProjection_declaresHandleToken() {
        for (tool in listOf(
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        )) {
            val spec = CloudAgentToolCatalog.specFor(tool)!!
            assertTrue(spec.projectionSchema.contains("handle:"), "$tool 未声明 handle token")
            assertTrue(spec.projectionSchema.contains("不含"), "$tool 缺排除子句")
        }
    }

    // ── 分层 runtime M2：目录驱动的工具声明源（描述/参数/缺省一处声明） ──

    @Test
    fun `spec descriptions reference contract DESC constants`() {
        assertEquals(
            CloudAgentToolContract.DESC_GET_GALLERY_SUMMARY,
            CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY)!!.description,
        )
        assertEquals(
            CloudAgentToolContract.DESC_SEARCH_MEDIA,
            CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_SEARCH_MEDIA)!!.description,
        )
        assertEquals(
            CloudAgentToolContract.DESC_REFINE_MEDIA_SEARCH,
            CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH)!!.description,
        )
    }

    @Test
    fun `gallery summary declares no params`() {
        val spec = CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY)!!
        assertEquals(emptyList(), spec.params)
    }

    @Test
    fun `search media declares seven params in bridge order`() {
        // 与 server DeviceToolService.searchMedia 桥帧键序逐项等价（M1 真引用化前的
        // @Tool 签名）；全部 required（Koog 反射期即全必填，LLM 可见 schema 零漂移）
        val params = CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_SEARCH_MEDIA)!!.params
        assertEquals(
            listOf("query", "person", "fromMs", "toMs", "hasFace", "display", "mediaType"),
            params.map { it.name },
        )
        assertTrue(params.all { it.required }, "桥工具参数全 required（端侧以空串表达不限）")
        assertEquals(
            listOf(
                CloudAgentToolContract.DESC_PARAM_QUERY,
                CloudAgentToolContract.DESC_PARAM_PERSON,
                CloudAgentToolContract.DESC_PARAM_FROM_MS,
                CloudAgentToolContract.DESC_PARAM_TO_MS,
                CloudAgentToolContract.DESC_PARAM_HAS_FACE,
                CloudAgentToolContract.DESC_PARAM_DISPLAY,
                CloudAgentToolContract.DESC_PARAM_MEDIA_TYPE,
            ),
            params.map { it.description },
        )
    }

    @Test
    fun `refine media search declares three params in bridge order`() {
        val params = CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH)!!.params
        assertEquals(listOf("constraint", "fromMs", "toMs"), params.map { it.name })
        assertTrue(params.all { it.required })
        assertEquals(
            listOf(
                CloudAgentToolContract.DESC_PARAM_CONSTRAINT,
                CloudAgentToolContract.DESC_PARAM_FROM_MS,
                CloudAgentToolContract.DESC_PARAM_TO_MS,
            ),
            params.map { it.description },
        )
    }

    @Test
    fun `device-side defaults declared for display and mediaType`() {
        // default 是端侧 executor 兼容缺省（旧服务端不下发该键时的兜底），非 LLM schema 可选标记
        val tool = CloudAgentToolContract.TOOL_SEARCH_MEDIA
        assertEquals("false", CloudAgentToolCatalog.defaultFor(tool, "display"))
        assertEquals("photo", CloudAgentToolCatalog.defaultFor(tool, "mediaType"))
        assertEquals("", CloudAgentToolCatalog.defaultFor(tool, "query"))
        assertEquals("", CloudAgentToolCatalog.defaultFor("unknown_tool", "query"))
    }

    @Test
    fun `paramNames returns declared order`() {
        assertEquals(
            listOf("query", "person", "fromMs", "toMs", "hasFace", "display", "mediaType"),
            CloudAgentToolCatalog.paramNames(CloudAgentToolContract.TOOL_SEARCH_MEDIA),
        )
        assertEquals(emptyList(), CloudAgentToolCatalog.paramNames("unknown_tool"))
    }

    @Test
    fun `descriptorFor builds koog descriptor with all params required`() {
        val descriptor = CloudAgentToolCatalog.descriptorFor(CloudAgentToolContract.TOOL_SEARCH_MEDIA)
        assertEquals(CloudAgentToolContract.TOOL_SEARCH_MEDIA, descriptor.name)
        assertEquals(CloudAgentToolContract.DESC_SEARCH_MEDIA, descriptor.description)
        assertEquals(
            listOf("query", "person", "fromMs", "toMs", "hasFace", "display", "mediaType"),
            descriptor.requiredParameters.map { it.name },
        )
        assertTrue(descriptor.optionalParameters.isEmpty())
        assertTrue(
            descriptor.requiredParameters.all { it.type == ToolParameterType.String },
            "桥工具参数全 String（端侧空串语义）",
        )
        assertEquals(
            CloudAgentToolContract.DESC_PARAM_DISPLAY,
            descriptor.requiredParameters.single { it.name == "display" }.description,
        )
    }

    @Test
    fun `descriptorFor gallery summary has empty parameter lists`() {
        val descriptor = CloudAgentToolCatalog.descriptorFor(CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY)
        assertEquals(CloudAgentToolContract.DESC_GET_GALLERY_SUMMARY, descriptor.description)
        assertTrue(descriptor.requiredParameters.isEmpty())
        assertTrue(descriptor.optionalParameters.isEmpty())
    }

    @Test
    fun `localTools returns every LOCAL spec`() {
        assertEquals(
            setOf(
                CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
                CloudAgentToolContract.TOOL_SEARCH_MEDIA,
                CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
            ),
            CloudAgentToolCatalog.localTools().map { it.name }.toSet(),
        )
    }
}
