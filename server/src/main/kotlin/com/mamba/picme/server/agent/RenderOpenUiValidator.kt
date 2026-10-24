package com.mamba.picme.server.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * render_ui_openui 负载校验器（openui P2 review 🟡4，与 [RenderUiValidator] 对偶）——
 * 把「LLM 写错 OpenUI 线格式」从端侧静默降级提前为服务端同轮纠错。
 *
 * 两个消费点（同源规则，零漂移，同 render_ui 闭环口径）：
 * 1. [AgentRunner] 发射点门控（[RenderUiValidator.gate] 按 toolName 分发到本校验器）：
 *    非法负载不落 Action 事件、不 SSE 下行；
 * 2. [RenderOpenUiToolService.renderUiOpenUi] 工具体：非法时回逐条违规的 `error: …` 文本，
 *    Koog 作为 Observation 回灌，LLM 同轮修正重试（对比实验口径对齐：两条 GenUI 线的
 *    LLM 书写成功率都有同轮修正机会）。
 *
 * 结构级轻量校验（server 不依赖 :shared，DeviceToolService 字面量副本先例）：
 * 不跑完整 parser，只做正则级五项——root 入口存在性 / 组件名白名单 /
 * Query local_handle 形态 / Query 语句位（禁内联，§2.1）/ program 长度上限
 * （对齐 render_html 128_000 先例）+ 隐私投影禁令（媒体路径禁入 program）。规则与 shared `OpenUiContract`/`OpenUiLibrary`
 * 字面量副本对齐，改动须双向同步核对。
 *
 * 近似边界：扫描前剥离字符串字面量与行注释防文案误伤；剥离后仍非完整语法分析，
 * 漏判（放行坏负载）由端侧 engine 降级兜底，误判（错杀好负载）优先避免。
 */
object RenderOpenUiValidator {

    const val TOOL_NAME = "render_ui_openui"

    /** program 长度上限（对齐 render_html 128_000 字符先例）。 */
    const val MAX_PROGRAM_LENGTH = 128_000

    /** 组件白名单（生成引导面，与 shared OpenUiLibrary 六组件对齐）。 */
    private val ALLOWED_COMPONENTS = setOf("Text", "Row", "Column", "Card", "Button", "BarChart")

    /** 组件外唯一允许的大写调用：Query（宿主取数语句）。 */
    private val ALLOWED_CALLS = ALLOWED_COMPONENTS + "Query"

    private val STRING_LITERAL = Regex(""""([^"\\]|\\.)*"""")
    private val LINE_COMMENT = Regex("""//[^\n]*|#[^\n]*""")
    private val CALL_NAME = Regex("""(?<![@${'$'}A-Za-z0-9_])([A-Z][A-Za-z0-9]*)\s*\(""")
    private val ROOT_STATEMENT = Regex("""(?m)^\s*root\s*=""")
    private val CARD_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Card\s*\(""")
    private val QUERY_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Query\s*\(\s*"((?:[^"\\]|\\.)*)"""")
    private val QUERY_ANY = Regex("""(?<![@${'$'}A-Za-z0-9_])Query\s*\(""")
    private val STMT_ASSIGNMENT_PREFIX = Regex("""\s*${'$'}?\w+\s*=\s*""")
    private val LOCAL_HANDLE_REF = Regex("""local://[^\s"}\],)]*""")
    private val LOCAL_HANDLE_FORM = Regex("""local://scan/[0-9A-Za-z]+(#[^\s"}\],)]+)?""")
    private val MEDIA_PATH = Regex("""content://|file://|/storage/""")

    /** 校验工具调用参数（wire JSON：{"program","fallbackSummary"}）。null = 合法放行。 */
    fun validateArgsJson(argsJson: String): String? {
        val args = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return errorText(listOf("工具参数不是合法 JSON 对象"))
        val program = (args["program"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return errorText(listOf("工具参数缺 program 字符串"))
        return validateToError(program)
    }

    /** 校验并直接给回错误文本：null = 合法放行。 */
    fun validateToError(program: String): String? =
        validate(program).takeIf { violations -> violations.isNotEmpty() }?.let(::errorText)

    /** 校验核心：返回全部违规描述（空 = 合法）。 */
    fun validate(program: String): List<String> {
        val violations = mutableListOf<String>()
        if (program.isBlank()) {
            violations += "program 为空"
            return violations
        }
        if (program.length > MAX_PROGRAM_LENGTH) {
            violations += "program 超长（${program.length} > $MAX_PROGRAM_LENGTH 字符上限，对齐 render_html 先例）"
        }
        if (MEDIA_PATH.containsMatchIn(program)) {
            violations += "禁止把媒体路径/uri 写进 program（隐私投影契约），相册数据一律走 Query(\"local_handle\", …) 绑定"
        }
        // 剥离字符串字面量与行注释后再扫结构（防文案内容误伤）。
        // 顺序必须是先字符串后注释：handle 里的 local:// 含 //，先剥注释会把
        // 字符串内部截断留下奇数引号，后续字符串全局配对跨行吞掉 root 语句
        // （2026-10-24 真机事故：合法两步写法被误报「缺 root」死循环）
        val stripped = LINE_COMMENT.replace(STRING_LITERAL.replace(program, "\"\""), "")

        // 1. root 入口：root = 语句存在，或存在 Card 调用兜底 entry（spec §2.2）
        if (!ROOT_STATEMENT.containsMatchIn(stripped) && !CARD_CALL.containsMatchIn(stripped)) {
            violations += "缺 root = 语句（root 是组件树入口；无 root 时兜底的首个 Card 语句也缺失）"
        }

        // 2. 组件名白名单：大写开头调用只允许六组件 + Query
        CALL_NAME.findAll(stripped)
            .map { match -> match.groupValues[1] }
            .toSet()
            .filter { name -> name !in ALLOWED_CALLS }
            .forEach { name ->
                violations += "组件/调用 \"$name\" 不在白名单（只用 ${ALLOWED_COMPONENTS.sorted().joinToString("/")} 组件 + Query 取数）"
            }

        // 3. Query 形态：唯一宿主工具 local_handle；handle 必须是 local://scan/<id> 形态
        var hasLocalHandleQuery = false
        QUERY_CALL.findAll(program).forEach { match ->
            val tool = match.groupValues[1]
            if (tool != "local_handle") {
                violations += "Query 唯一允许的宿主工具是 \"local_handle\"（实际 \"$tool\"）"
            } else {
                hasLocalHandleQuery = true
            }
        }
        LOCAL_HANDLE_REF.findAll(program).forEach { match ->
            if (!LOCAL_HANDLE_FORM.matches(match.value)) {
                violations += "handle \"${match.value}\" 必须是 local://scan/<handleId> 形态；handleId 只许来自 search_media/refine_media_search 结果尾部，禁止臆造"
            }
        }
        if (hasLocalHandleQuery && !program.contains("local://scan/")) {
            violations += "Query(\"local_handle\", …) 缺 handle 参数：args 对象须含 { handle: \"local://scan/<id>#histogram\" }"
        }

        // 4. Query 禁止内联（OpenUI Lang §2.1：Query 只允许独占语句右侧；内联在组件参数
        //    会判 inline-reserved、组件静默丢弃——2026-10-24 真机柱图消失事故）
        QUERY_ANY.findAll(stripped).forEach { match ->
            val lineStart = stripped.lastIndexOf('\n', match.range.first)
                .let { index -> if (index < 0) 0 else index + 1 }
            val linePrefix = stripped.substring(lineStart, match.range.first)
            if (!STMT_ASSIGNMENT_PREFIX.matches(linePrefix)) {
                violations += "Query 必须独占整条语句右侧（先 `buckets = Query(\"local_handle\", …, [])` 单独成行，组件参数里引用 buckets），禁止内联进组件参数（内联触发 inline-reserved，组件会被静默丢弃）"
            }
        }
        return violations
    }

    private fun errorText(violations: List<String>): String = buildString {
        append("error: render_ui_openui 负载校验未通过，本次未下发端侧。逐条修正后重新调用 render_ui_openui：")
        violations.forEach { violation -> append("\n- ").append(violation) }
    }
}
