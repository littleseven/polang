package com.mamba.picme.server.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * render_ui 负载校验器测试：每个违规类一例 + 黄金示例/合法夹具全过。
 *
 * 示例保真不变式：[RENDER_UI_EXAMPLE]（@LLMDescription 内嵌 few-shot）必须过校验——
 * 示例若教错形态，LLM 学到的是错误结构，校验器再把 LLM 卡住，死循环。
 */
class RenderUiValidatorTest {

    private val sid = "sf-a1b2c3d4"

    private fun createSurface(surfaceId: String = sid, catalogId: String = RenderUiValidator.CATALOG_ID) =
        """{"version":"v0.9","createSurface":{"surfaceId":"$surfaceId","catalogId":"$catalogId"}}"""

    private fun updateComponents(components: String, surfaceId: String = sid) =
        """{"version":"v0.9","updateComponents":{"surfaceId":"$surfaceId","components":[$components]}}"""

    private fun updateData(path: String, valueJson: String, surfaceId: String = sid) =
        """{"version":"v0.9","updateDataModel":{"surfaceId":"$surfaceId","path":"$path","value":$valueJson}}"""

    private fun envelopeArray(vararg envelopes: String) = envelopes.joinToString(",", "[", "]")

    /** 最小合法卡：Column + Text，无数据绑定。 */
    private val validMinimal = envelopeArray(
        createSurface(),
        updateComponents(
            """
            {"id":"root","component":"Column","children":["title"]},
            {"id":"title","component":"Text","text":"你好","variant":"h2"}
            """,
        ),
    )

    // ── 合法路径 ────────────────────────────────────────────────────────

    @Test
    fun `minimal valid card passes`() {
        assertEquals(emptyList<String>(), RenderUiValidator.validate(sid, validMinimal))
    }

    @Test
    fun `golden example in tool description passes validation`() {
        assertEquals(
            "工具描述内嵌示例必须自身过校验（示例与校验器零脱节）",
            emptyList<String>(),
            RenderUiValidator.validate("sf-a1b2c3d4", RENDER_UI_EXAMPLE.trim()),
        )
    }

    @Test
    fun `full bar chart card with bindings passes`() {
        val json = envelopeArray(
            createSurface(),
            updateComponents(
                """
                {"id":"root","component":"Column","children":["title","chart","btn"]},
                {"id":"title","component":"Text","text":"统计","variant":"h3","role":"HEADLINE","colorRole":"PRIMARY","spacing":"COMPACT"},
                {"id":"chart","component":"BarChart","title":"月度","buckets":{"path":"/buckets"},"selected":{"path":"/selected"},"titleRole":"SUBHEAD"},
                {"id":"btn","component":"Button","variant":"primary","child":"btn-label","action":{"event":{"name":"open"}}},
                {"id":"btn-label","component":"Text","text":"查看"}
                """,
            ),
            updateData("/buckets", "\"local://scan/ab12cd34#histogram\""),
            updateData("/selected", "\"2026-10\""),
        )
        assertEquals(emptyList<String>(), RenderUiValidator.validate(sid, json))
    }

    @Test
    fun `validateArgsJson reads surfaceId and messagesJson from wire args`() {
        val args = """{"surfaceId":"$sid","messagesJson":${jsonString(validMinimal)},"fallbackSummary":"摘要"}"""
        assertNull(RenderUiValidator.validateArgsJson(args))
        val badArgs = """{"surfaceId":"other","messagesJson":${jsonString(validMinimal)},"fallbackSummary":"摘要"}"""
        assertNotNull(RenderUiValidator.validateArgsJson(badArgs))
    }

    @Test
    fun `gate only intercepts render_ui`() {
        assertNull(RenderUiValidator.gate("server_time", "not json at all"))
        assertNotNull(RenderUiValidator.gate("render_ui", "not json at all"))
    }

    // ── 信封级违规 ──────────────────────────────────────────────────────

    @Test
    fun `messagesJson not json`() {
        assertHasViolation("{oops", "不是合法 JSON 数组")
    }

    @Test
    fun `messagesJson not array`() {
        assertHasViolation("""{"version":"v0.9"}""", "不是合法 JSON 数组")
    }

    @Test
    fun `empty array`() {
        assertHasViolation("[]", "空数组")
    }

    @Test
    fun `first envelope must be createSurface`() {
        assertHasViolation(
            envelopeArray(updateComponents("""{"id":"root","component":"Text","text":"x"}""")),
            "首个信封必须是 createSurface",
        )
    }

    @Test
    fun `missing version`() {
        assertHasViolation(
            envelopeArray("""{"createSurface":{"surfaceId":"$sid","catalogId":"${RenderUiValidator.CATALOG_ID}"}}"""),
            "version",
        )
    }

    @Test
    fun `wrong version`() {
        assertHasViolation(
            envelopeArray("""{"version":"v0.8","createSurface":{"surfaceId":"$sid","catalogId":"${RenderUiValidator.CATALOG_ID}"}}"""),
            "v0.9",
        )
    }

    @Test
    fun `multiple operation keys in one envelope`() {
        assertHasViolation(
            envelopeArray(
                createSurface(),
                """{"version":"v0.9","createSurface":{"surfaceId":"$sid","catalogId":"${RenderUiValidator.CATALOG_ID}"},"updateDataModel":{"surfaceId":"$sid","path":"/x","value":1}}""",
            ),
            "之一",
        )
    }

    @Test
    fun `wrong catalogId`() {
        assertHasViolation(
            envelopeArray(createSurface(catalogId = "https://example.com/other")),
            "catalogId",
        )
    }

    @Test
    fun `surfaceId mismatch with tool arg`() {
        assertHasViolation(
            envelopeArray(createSurface(surfaceId = "sf-different")),
            "surfaceId",
        )
    }

    // ── updateDataModel 违规（sf-a2u1c0de 事故类） ──────────────────────

    @Test
    fun `data batch form rejected`() {
        assertHasViolation(
            envelopeArray(createSurface(), """{"version":"v0.9","updateDataModel":{"surfaceId":"$sid","data":{"/buckets":[]}}}"""),
            "data",
        )
    }

    @Test
    fun `missing path or value`() {
        assertHasViolation(
            envelopeArray(createSurface(), """{"version":"v0.9","updateDataModel":{"surfaceId":"$sid","path":"/buckets"}}"""),
            "value",
        )
    }

    @Test
    fun `malformed local handle`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateData("/buckets", "\"local://scan/\"")),
            "local://scan/<handleId>",
        )
    }

    @Test
    fun `raw media path rejected`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateData("/buckets", "\"content://media/external/123\"")),
            "禁止把媒体路径",
        )
    }

    // ── 组件级违规 ──────────────────────────────────────────────────────

    @Test
    fun `component outside allowlist`() {
        // Video/AudioPlayer 端侧是空盒占位渲染（PolangA2uiCatalog），未实装即不开放
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Video","url":"https://x.co/a.mp4"}""")),
            "不在白名单",
        )
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"AudioPlayer","url":"https://x.co/a.mp3"}""")),
            "不在白名单",
        )
    }

    @Test
    fun `full seventeen component card passes`() {
        val json = envelopeArray(
            createSurface(),
            updateComponents(
                """
                {"id":"root","component":"Column","children":["t","img","ic","row","list","card","tabs","modal","div","btn","tf","cb","cp","sl","dt","chart"],"justify":"spaceBetween","align":"center"},
                {"id":"t","component":"Text","text":"标题","variant":"h3"},
                {"id":"img","component":"Image","url":"https://x.co/a.png","fit":"cover","variant":"header"},
                {"id":"ic","component":"Icon","name":"photo"},
                {"id":"row","component":"Row","children":["t2"],"justify":"start","align":"stretch"},
                {"id":"t2","component":"Text","text":"行内"},
                {"id":"list","component":"List","children":["t3"],"direction":"horizontal","align":"start"},
                {"id":"t3","component":"Text","text":"列表项"},
                {"id":"card","component":"Card","child":"t4"},
                {"id":"t4","component":"Text","text":"卡内"},
                {"id":"tabs","component":"Tabs","tabs":[{"title":"页一","child":"t5"}]},
                {"id":"t5","component":"Text","text":"页签内容"},
                {"id":"modal","component":"Modal","trigger":"btn","content":"t6"},
                {"id":"t6","component":"Text","text":"弹层"},
                {"id":"div","component":"Divider","axis":"vertical"},
                {"id":"btn","component":"Button","child":"btn-label","action":{"event":{"name":"go"}},"variant":"borderless"},
                {"id":"btn-label","component":"Text","text":"点我"},
                {"id":"tf","component":"TextField","label":"名字","variant":"shortText"},
                {"id":"cb","component":"CheckBox","label":"同意","value":true},
                {"id":"cp","component":"ChoicePicker","options":[{"label":"A","value":"a"}],"value":["a"],"variant":"multipleSelection","displayStyle":"chips"},
                {"id":"sl","component":"Slider","label":"强度","value":5,"min":0,"max":10},
                {"id":"dt","component":"DateTimeInput","label":"时间","value":"2026-10-24","enableDate":true},
                {"id":"chart","component":"BarChart","buckets":{"path":"/buckets"},"selected":{"path":"/selected"}}
                """,
            ),
            updateData("/buckets", "\"local://scan/ab12cd34#histogram\""),
            updateData("/selected", "\"2026-10\""),
        )
        assertEquals(emptyList<String>(), RenderUiValidator.validate(sid, json))
    }

    @Test
    fun `image url must be https public address`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Image","url":"content://media/1"}""")),
            "https://",
        )
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Image","url":"file:///storage/emulated/0/a.jpg"}""")),
            "https://",
        )
    }

    @Test
    fun `icon name outside predefined set rejected`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Icon","name":"beautify"}""")),
            "不在预定义图标集",
        )
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Icon","name":{"svgPath":"M0 0h24"}}""")),
            "svgPath",
        )
        // 非字符串非绑定标量（数字/布尔）不得落空放行
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Icon","name":123}""")),
            "数据绑定",
        )
    }

    /** ICON_NAMES 手抄副本防漂移：与官网 catalog 描述文件逐项对账（server 独立检出无该文件时跳过）。 */
    @Test
    fun `icon names stay in sync with published catalog descriptor`() {
        val catalogFile = File("../docs-site/a2ui/catalog/v1.json")
        assumeTrue("server 独立检出无官网目录，跳过对账", catalogFile.isFile)
        val enum = Json.parseToJsonElement(catalogFile.readText())
            .jsonObject.getValue("components").jsonObject
            .getValue("Icon").jsonObject
            .getValue("properties").jsonObject
            .getValue("name").jsonObject
            .getValue("oneOf").jsonArray[0].jsonObject
            .getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            "RenderUiValidator.ICON_NAMES 与 docs-site catalog v1.json Icon 枚举漂移",
            enum,
            RenderUiValidator.ICON_NAMES,
        )
    }

    @Test
    fun `tabs items must reference defined children`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Tabs","tabs":[{"title":"一","child":"ghost"}]}""")),
            "未定义的组件 id \"ghost\"",
        )
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Tabs","tabs":[]}""")),
            "至少一个页签",
        )
        assertHasViolation(
            envelopeArray(
                createSurface(),
                updateComponents(
                    """{"id":"root","component":"Tabs","tabs":[{"child":"t"}]},""" +
                        """{"id":"t","component":"Text","text":"x"}""",
                ),
            ),
            "缺 \"title\"",
        )
    }

    @Test
    fun `layout enums validated`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Row","children":[],"justify":"diagonal"}""")),
            "非法值 \"diagonal\"",
        )
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"List","children":[],"direction":"diagonal"}""")),
            "非法值 \"diagonal\"",
        )
    }

    @Test
    fun `missing required property`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Text"}""")),
            "缺必填属性 \"text\"",
        )
    }

    @Test
    fun `bar chart bare buckets rejected`() {
        assertHasViolation(
            envelopeArray(
                createSurface(),
                updateComponents("""{"id":"root","component":"BarChart","buckets":[{"label":"a","count":1}]}"""),
            ),
            "buckets 必须写数据绑定",
        )
    }

    @Test
    fun `bar chart bare selected rejected - sf-012a0 incident class`() {
        assertHasViolation(
            envelopeArray(
                createSurface(),
                updateComponents(
                    """{"id":"root","component":"BarChart","buckets":{"path":"/buckets"},"selected":"2026-10"}""",
                ),
            ),
            "selected 必须写数据绑定",
        )
    }

    @Test
    fun `button label instead of child`() {
        assertHasViolation(
            envelopeArray(
                createSurface(),
                updateComponents("""{"id":"root","component":"Button","label":"点我","action":{"event":{"name":"go"}}}"""),
            ),
            "label",
        )
    }

    @Test
    fun `button action missing event layer`() {
        assertHasViolation(
            envelopeArray(
                createSurface(),
                updateComponents(
                    """
                    {"id":"root","component":"Button","child":"lbl","action":{"name":"go"}},
                    {"id":"lbl","component":"Text","text":"点我"}
                    """,
                ),
            ),
            "event",
        )
    }

    @Test
    fun `illegal enum value`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Text","text":"x","variant":"giant"}""")),
            "非法值 \"giant\"",
        )
    }

    @Test
    fun `forbidden style numeric field`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Text","text":"x","fontSize":20}""")),
            "fontSize",
        )
    }

    @Test
    fun `dangling child reference`() {
        assertHasViolation(
            envelopeArray(createSurface(), updateComponents("""{"id":"root","component":"Column","children":["ghost"]}""")),
            "未定义的组件 id \"ghost\"",
        )
    }

    @Test
    fun `multiple violations aggregated in one error text`() {
        val error = RenderUiValidator.validateToError(
            "sf-different",
            envelopeArray("""{"version":"v0.8","createSurface":{"surfaceId":"sf-different","catalogId":"https://x"}}"""),
        )
        assertNotNull(error)
        assertTrue(error!!.startsWith("error: "))
        assertTrue(error.contains("v0.9"))
        assertTrue(error.contains("catalogId"))
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private fun assertHasViolation(messagesJson: String, expectedFragment: String) {
        val violations = RenderUiValidator.validate(sid, messagesJson)
        assertTrue(
            "应报含「$expectedFragment」的违规，实际：$violations",
            violations.any { violation -> violation.contains(expectedFragment) },
        )
    }

    private fun jsonString(raw: String): String =
        buildString {
            append('"')
            raw.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    else -> append(char)
                }
            }
            append('"')
        }
}
