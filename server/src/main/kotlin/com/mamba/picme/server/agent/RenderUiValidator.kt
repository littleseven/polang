package com.mamba.picme.server.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * render_ui 负载校验器——把「LLM 写错 A2UI 线格式」从端侧静默降级提前为服务端同轮纠错。
 *
 * 两个消费点（同源规则，零漂移）：
 * 1. [AgentRunner] 发射点门控（`onToolCallStarting`）：非法负载不落 Action 事件、不 SSE 下行，
 *    端侧永远见不到坏卡；
 * 2. [RenderUiToolService.renderUi] 工具体：非法时返回逐条列明违规的 `error: …` 文本，
 *    Koog 将其作为 Observation 回灌，LLM 同轮修正重试。
 *
 * 规则与两处字面量副本对齐（改动须三方同步核对）：
 * - shared `A2uiCardContract`（CATALOG_ID/协议版本/handle 约定）；
 * - 官网 catalog schema `docs-site/a2ui/catalog/v1.json`（组件必填属性，
 *   由 androidApp `A2uiCatalogDescriptorSyncTest` 从运行态 catalog 生成）。
 *
 * 注意组件白名单（19 个）= catalog 全集（19 个）：Video/AudioPlayer 已实装
 * （Media3 真渲染，2026-10-25 起；见 androidApp features/chat/media/ChatMediaPlayers），
 * 两者必须保持这个关系。
 */
object RenderUiValidator {

    const val TOOL_NAME = "render_ui"
    const val CATALOG_ID = "https://polang.net/a2ui/catalog/v1.json"
    const val PROTOCOL_VERSION = "v0.9"

    /** [AgentRunner] 发射点门控入口：按工具名分发——render_ui 走 A2UI 校验，
     *  render_ui_openui 走 OpenUI 结构校验（review 🟡4 对偶），其余一律放行。 */
    fun gate(toolName: String, argsJson: String): String? = when (toolName) {
        TOOL_NAME -> validateArgsJson(argsJson)
        RenderOpenUiValidator.TOOL_NAME -> RenderOpenUiValidator.validateArgsJson(argsJson)
        else -> null
    }

    private val ALLOWED_COMPONENTS = setOf(
        "Text", "Image", "Icon", "Video", "AudioPlayer",
        "Row", "Column", "List", "Card", "Tabs", "Modal", "Divider",
        "Button", "TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput", "BarChart",
    )

    /** 各组件必填属性（对齐 catalog schema 的 required，去掉合成字段 component）。 */
    private val REQUIRED_PROPS = mapOf(
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

    /** 组件间引用键：值（或数组项）必须是同信封内定义的组件 id。 */
    private val CHILD_REF_KEYS = setOf("child", "trigger", "content")

    private val TEXT_VARIANTS = setOf("h1", "h2", "h3", "h4", "h5", "caption", "body")
    private val BUTTON_VARIANTS = setOf("default", "primary", "borderless")
    private val TEXT_ROLES = setOf("HEADLINE", "SUBHEAD", "BODY", "CAPTION")
    private val COLOR_ROLES = setOf("PRIMARY", "SURFACE", "ERROR", "ON_SURFACE")
    private val SPACINGS = setOf("COMPACT", "STANDARD", "COMFORTABLE")
    private val JUSTIFY_VALUES = setOf("start", "center", "end", "spaceBetween", "spaceAround", "spaceEvenly", "stretch")
    private val ALIGN_VALUES = setOf("start", "center", "end", "stretch")
    private val LIST_DIRECTIONS = setOf("vertical", "horizontal")
    private val DIVIDER_AXES = setOf("horizontal", "vertical")
    private val IMAGE_FITS = setOf("contain", "cover", "fill", "none", "scaleDown")
    private val IMAGE_VARIANTS = setOf("icon", "avatar", "smallFeature", "mediumFeature", "largeFeature", "header")
    private val TEXT_FIELD_VARIANTS = setOf("longText", "number", "shortText", "obscured")
    private val CHOICE_PICKER_VARIANTS = setOf("mutuallyExclusive", "multipleSelection")
    private val CHOICE_PICKER_DISPLAY_STYLES = setOf("checkbox", "chips")

    /** catalog v1.json Icon.name 预定义枚举（svgPath 自由形式未开放——LLM 臆造矢量路径必渲染垃圾）。
     *  与 docs-site/a2ui/catalog/v1.json 的对账由 RenderUiValidatorTest 钉住（防手抄漂移）。 */
    internal val ICON_NAMES = setOf(
        "accountCircle", "add", "arrowBack", "arrowForward", "attachFile", "calendarToday",
        "call", "camera", "check", "close", "delete", "download", "edit", "error", "event",
        "fastForward", "favorite", "favoriteOff", "folder", "help", "home", "info",
        "locationOn", "lock", "lockOpen", "mail", "menu", "moreHoriz", "moreVert",
        "notifications", "notificationsOff", "pause", "payment", "person", "phone", "photo",
        "play", "print", "refresh", "rewind", "search", "send", "settings", "share",
        "shoppingCart", "skipNext", "skipPrevious", "star", "starHalf", "starOff", "stop",
        "upload", "visibility", "visibilityOff", "volumeDown", "volumeMute", "volumeOff",
        "volumeUp", "warning",
    )

    /** 禁用具体样式数值字段（样式只走语义令牌）。 */
    private val FORBIDDEN_STYLE_KEYS = setOf("fontSize", "padding", "color", "margin", "width", "height")

    private val ENVELOPE_OPS = setOf("createSurface", "updateComponents", "updateDataModel")

    /**
     * 校验工具调用参数（wire JSON：{"surfaceId","messagesJson","fallbackSummary"}）。
     * @return null = 合法放行；否则为回灌 LLM 的错误文本（逐条违规 + 修正指引）。
     */
    fun validateArgsJson(argsJson: String): String? {
        val args = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return errorText(listOf("工具参数不是合法 JSON 对象"))
        val surfaceId = args["surfaceId"].stringOrNull()
            ?: return errorText(listOf("工具参数缺 surfaceId 字符串"))
        val messagesJson = args["messagesJson"].stringOrNull()
            ?: return errorText(listOf("工具参数缺 messagesJson 字符串"))
        return validateToError(surfaceId, messagesJson)
    }

    /** 校验并直接给回错误文本：null = 合法放行。 */
    fun validateToError(surfaceId: String, messagesJson: String): String? =
        validate(surfaceId, messagesJson)
            .takeIf { violations -> violations.isNotEmpty() }
            ?.let(::errorText)

    /** 校验核心：返回全部违规描述（空 = 合法）。 */
    fun validate(surfaceId: String, messagesJson: String): List<String> {
        val violations = mutableListOf<String>()
        val envelopes = try {
            Json.parseToJsonElement(messagesJson) as? JsonArray
        } catch (e: Exception) {
            null
        }
        if (envelopes == null) {
            violations += "messagesJson 不是合法 JSON 数组"
            return violations
        }
        if (envelopes.isEmpty()) {
            violations += "messagesJson 是空数组，至少要有 createSurface 信封"
            return violations
        }
        if (envelopes.first().objectOrNull()?.containsKey("createSurface") != true) {
            violations += "首个信封必须是 createSurface（端侧须先建 surface 再更新）"
        }
        envelopes.forEachIndexed { index, element ->
            val envelope = element.objectOrNull()
            if (envelope == null) {
                violations += "envelope[$index] 不是 JSON 对象"
                return@forEachIndexed
            }
            validateEnvelope(index, envelope, surfaceId, violations)
        }
        return violations
    }

    private fun validateEnvelope(
        index: Int,
        envelope: JsonObject,
        expectedSurfaceId: String,
        violations: MutableList<String>,
    ) {
        val at = "envelope[$index]"
        val version = envelope["version"].stringOrNull()
        if (version != PROTOCOL_VERSION) {
            violations += "$at: version 必须是 \"$PROTOCOL_VERSION\"（实际 ${version ?: "缺失"}）"
        }
        val ops = ENVELOPE_OPS.filter { op -> envelope.containsKey(op) }
        if (ops.size != 1) {
            violations += "$at: 必须且只能含 createSurface/updateComponents/updateDataModel 之一（实际 ${ops.ifEmpty { listOf("无") }}）"
            return
        }
        when (val op = ops.single()) {
            "createSurface" -> {
                val body = envelope[op].objectOrNull() ?: return
                val catalogId = body["catalogId"].stringOrNull()
                if (catalogId != CATALOG_ID) {
                    violations += "$at: catalogId 必须是 \"$CATALOG_ID\"（实际 ${catalogId ?: "缺失"}）"
                }
                checkSurfaceId(at, op, body, expectedSurfaceId, violations)
            }
            "updateDataModel" -> {
                val body = envelope[op].objectOrNull() ?: return
                checkSurfaceId(at, op, body, expectedSurfaceId, violations)
                if (body.containsKey("data")) {
                    violations += "$at: updateDataModel 禁止 \"data\" 批量形式（端上解析器不识别，数据静默丢弃），须单写 \"path\"+\"value\""
                }
                if (body["path"].stringOrNull() == null) {
                    violations += "$at: updateDataModel 缺 \"path\" 字符串"
                }
                if (!body.containsKey("value")) {
                    violations += "$at: updateDataModel 缺 \"value\""
                } else {
                    checkDataValue(at, body.getValue("value"), violations)
                }
            }
            "updateComponents" -> {
                val body = envelope[op].objectOrNull() ?: return
                checkSurfaceId(at, op, body, expectedSurfaceId, violations)
                val components = (body["components"] as? JsonArray)?.mapNotNull { item -> item.objectOrNull() }
                if (components == null) {
                    violations += "$at: updateComponents.components 必须是组件对象数组"
                    return
                }
                validateComponents(at, components, violations)
            }
        }
    }

    private fun checkSurfaceId(
        at: String,
        op: String,
        body: JsonObject,
        expectedSurfaceId: String,
        violations: MutableList<String>,
    ) {
        val actual = body["surfaceId"].stringOrNull()
        if (actual != expectedSurfaceId) {
            violations += "$at: $op.surfaceId 必须等于工具参数 surfaceId \"$expectedSurfaceId\"（实际 ${actual ?: "缺失"}）"
        }
    }

    /** 数据值体检：local:// handle 格式 + 禁裸媒体路径（隐私投影契约）。 */
    private fun checkDataValue(at: String, value: JsonElement, violations: MutableList<String>) {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        when {
            text.startsWith("local://") -> {
                val rest = text.removePrefix("local://")
                val pathPart = rest.substringBefore('#')
                val domain = pathPart.substringBefore('/', "")
                val id = pathPart.substringAfter('/', "")
                if (domain != "scan" || id.isEmpty()) {
                    violations += "$at: local:// 绑定格式必须是 \"local://scan/<handleId>#<fragment>\"（实际 \"$text\"）；handleId 只能来自 search_media/refine_media_search 结果尾部，禁止臆造"
                }
            }
            text.startsWith("content://") || text.startsWith("file://") || text.contains("/storage/") ->
                violations += "$at: 禁止把媒体路径/uri 写进数据值（隐私投影契约），相册数据一律走 local://scan/<handleId> 绑定"
        }
    }

    private fun validateComponents(at: String, components: List<JsonObject>, violations: MutableList<String>) {
        val ids = components.mapNotNull { component -> component["id"].stringOrNull() }.toSet()
        components.forEachIndexed { i, component ->
            val where = "$at.components[$i]"
            val id = component["id"].stringOrNull()
            if (id == null) violations += "$where: 缺 \"id\" 字符串"
            val name = component["component"].stringOrNull()
            if (name == null) {
                violations += "$where: 缺 \"component\" 类型名"
                return@forEachIndexed
            }
            if (name !in ALLOWED_COMPONENTS) {
                violations += "$where: 组件 \"$name\" 不在白名单（只用 ${ALLOWED_COMPONENTS.sorted().joinToString("/")}）"
                return@forEachIndexed
            }
            REQUIRED_PROPS.getValue(name)
                .filter { key -> !component.containsKey(key) }
                .forEach { key -> violations += "$where: $name 缺必填属性 \"$key\"" }
            validateComponentProps(where, name, component, ids, violations)
        }
    }

    private fun validateComponentProps(
        where: String,
        name: String,
        component: JsonObject,
        ids: Set<String>,
        violations: MutableList<String>,
    ) {
        FORBIDDEN_STYLE_KEYS.filter { key -> component.containsKey(key) }
            .forEach { key -> violations += "$where: 禁止具体样式数值字段 \"$key\"，样式只用 role/colorRole/spacing 语义令牌" }
        checkEnum(where, component, "role", TEXT_ROLES, violations)
        checkEnum(where, component, "titleRole", TEXT_ROLES, violations)
        checkEnum(where, component, "colorRole", COLOR_ROLES, violations)
        checkEnum(where, component, "spacing", SPACINGS, violations)
        when (name) {
            "Text" -> checkEnum(where, component, "variant", TEXT_VARIANTS, violations)
            "Row", "Column" -> {
                checkEnum(where, component, "justify", JUSTIFY_VALUES, violations)
                checkEnum(where, component, "align", ALIGN_VALUES, violations)
            }
            "List" -> {
                checkEnum(where, component, "direction", LIST_DIRECTIONS, violations)
                checkEnum(where, component, "align", ALIGN_VALUES, violations)
            }
            "Divider" -> checkEnum(where, component, "axis", DIVIDER_AXES, violations)
            "Image" -> {
                checkEnum(where, component, "fit", IMAGE_FITS, violations)
                checkEnum(where, component, "variant", IMAGE_VARIANTS, violations)
                val url = component["url"].stringOrNull()
                if (url != null && !url.startsWith("https://")) {
                    violations += "$where: Image.url 只接受 https:// 公网图片地址（实际 \"$url\"）——相册照片永不出设备，禁本地路径/uri"
                }
            }
            "Video", "AudioPlayer" -> {
                val url = component["url"].stringOrNull()
                if (url != null && !url.startsWith("https://")) {
                    violations += "$where: $name.url 只接受 https:// 公网媒体地址（实际 \"$url\"）——相册媒体永不出设备，禁本地路径/uri"
                }
            }
            "Icon" -> {
                val iconName = component["name"]
                val literal = iconName.stringOrNull()
                val binding = iconName.objectOrNull()
                when {
                    literal != null && literal !in ICON_NAMES ->
                        violations += "$where: Icon.name \"$literal\" 不在预定义图标集（如 photo/person/star/check/warning/settings/search 等，禁臆造）"
                    binding?.containsKey("svgPath") == true ->
                        violations += "$where: Icon.name 禁用 svgPath 自由形式（矢量路径臆造必渲染垃圾），只用预定义图标名或数据绑定"
                    literal == null && binding?.containsKey("path") != true ->
                        violations += "$where: Icon.name 必须是预定义图标名字符串或 {\"path\":…} 数据绑定（禁数字/布尔等标量）"
                    else -> Unit
                }
            }
            "Tabs" -> {
                val tabs = component["tabs"] as? JsonArray
                if (tabs == null) {
                    violations += "$where: Tabs.tabs 必须是 [{\"title\",\"child\"}] 数组"
                } else {
                    if (tabs.isEmpty()) violations += "$where: Tabs.tabs 至少一个页签"
                    tabs.forEachIndexed { ti, item ->
                        val tab = item.objectOrNull()
                        if (tab == null) {
                            violations += "$where: tabs[$ti] 不是对象（须含 title 与 child）"
                            return@forEachIndexed
                        }
                        if (tab["title"].stringOrNull() == null) {
                            violations += "$where: tabs[$ti] 缺 \"title\" 页签标题"
                        }
                        val tabChild = tab["child"].stringOrNull()
                        if (tabChild == null) {
                            violations += "$where: tabs[$ti] 缺 \"child\" 组件 id"
                        } else if (tabChild !in ids) {
                            violations += "$where: tabs[$ti].child 引用了未定义的组件 id \"$tabChild\""
                        }
                    }
                }
            }
            "TextField" -> checkEnum(where, component, "variant", TEXT_FIELD_VARIANTS, violations)
            "ChoicePicker" -> {
                checkEnum(where, component, "variant", CHOICE_PICKER_VARIANTS, violations)
                checkEnum(where, component, "displayStyle", CHOICE_PICKER_DISPLAY_STYLES, violations)
            }
            "Button" -> {
                checkEnum(where, component, "variant", BUTTON_VARIANTS, violations)
                if (component.containsKey("label")) {
                    violations += "$where: Button 禁止 \"label\" 属性——按钮文字用 \"child\" 指向一个 Text 组件"
                }
                val action = component["action"].objectOrNull()
                if (action != null && action["event"].objectOrNull()?.get("name").stringOrNull() == null) {
                    violations += "$where: action 格式必须是 {\"event\":{\"name\":\"<事件名>\"}}（\"event\" 层必填，禁止平写 \"name\"）"
                }
            }
            "BarChart" -> {
                if (component["buckets"] != null && component["buckets"].bindingPath() == null) {
                    violations += "$where: BarChart.buckets 必须写数据绑定 {\"path\":\"/buckets\"}（数据经 updateDataModel 下发，禁裸写数据）"
                }
                val selected = component["selected"]
                if (selected != null && selected.bindingPath() == null) {
                    violations += "$where: BarChart.selected 必须写数据绑定 {\"path\":\"/selected\"}——裸字符串不订阅数据模型，点选高亮静默失效"
                }
            }
        }
        // 引用完整性：child/trigger/content 指向同信封已定义组件
        CHILD_REF_KEYS.forEach { key ->
            when (val ref = component[key]) {
                is JsonPrimitive -> if (ref.isString && ref.content !in ids) {
                    violations += "$where: \"$key\" 引用了未定义的组件 id \"${ref.content}\""
                }
                is JsonArray -> ref.forEach { item ->
                    val refId = item.stringOrNull() ?: return@forEach
                    if (refId !in ids) violations += "$where: \"$key\" 引用了未定义的组件 id \"$refId\""
                }
                else -> Unit
            }
        }
        // children 数组项引用完整性
        (component["children"] as? JsonArray)?.forEach { item ->
            val refId = item.stringOrNull() ?: return@forEach
            if (refId !in ids) violations += "$where: \"children\" 引用了未定义的组件 id \"$refId\""
        }
    }

    private fun checkEnum(
        where: String,
        component: JsonObject,
        key: String,
        allowed: Set<String>,
        violations: MutableList<String>,
    ) {
        val value = component[key].stringOrNull() ?: return
        if (value !in allowed) {
            violations += "$where: \"$key\" 非法值 \"$value\"（允许 ${allowed.sorted().joinToString("/")}）"
        }
    }

    private fun errorText(violations: List<String>): String = buildString {
        append("error: render_ui 负载校验未通过，本次未下发端侧。逐条修正后重新调用 render_ui：")
        violations.forEach { violation -> append("\n- ").append(violation) }
    }

    private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 数据绑定形态 {\"path\":\"...\"} 的 path 值；非绑定形态返回 null。 */
    private fun JsonElement?.bindingPath(): String? = this.objectOrNull()?.get("path").stringOrNull()
}
