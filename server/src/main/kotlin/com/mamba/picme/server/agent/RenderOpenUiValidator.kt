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
 * 不跑完整 parser，只做正则级——root 入口存在性 / 组件名白名单 /
 * Query local_handle 形态 / program 长度上限
 * （对齐 render_html 128_000 先例）+ 隐私投影禁令（媒体路径禁入 program）+
 * Image 图片来源校验（photo/src 二缺一）+ 图片地址键 https 限（src/bgImageSrc/image，
 * [PRIVACY] 同 A2UI Image.url 口径）。规则与 shared
 * `OpenUiContract`/`OpenUiLibrary` 字面量副本对齐，改动须双向同步核对。
 * （2026-10-24 起不再禁内联 Query：端侧引擎已支持内联提升（fix/a2ui 0e5856b10），
 * 原「内联判 inline-reserved 静默丢弃」根因已根治，服务端不再误杀合法负载。）
 *
 * 近似边界：扫描前剥离字符串字面量与行注释防文案误伤；剥离后仍非完整语法分析，
 * 漏判（放行坏负载）由端侧 engine 降级兜底，误判（错杀好负载）优先避免。
 */
object RenderOpenUiValidator {

    const val TOOL_NAME = "render_ui_openui"

    /** program 长度上限（对齐 render_html 128_000 字符先例）。 */
    const val MAX_PROGRAM_LENGTH = 128_000

    /**
     * 组件白名单（生成引导面，与 shared `OpenUiLibrary.components` 九十五项字面量
     * 副本对齐：M2（2026-10-24）+Icon/Divider/List/Tabs/CheckBox/ChoicePicker/Slider；
     * M3（2026-10-24）+Table/PieChart/LineChart/Callout/Markdown/Accordion/Switch；
     * 2026-10-25 官方组件库全量补全（spec《openui-full-library-design》）21→89——
     * 官方 82 组件适配 81（Col 列式 Table 刻意不收，行式为唯一形态）+ 本仓既有 8；
     * 2026-10-30 通用布局原语批次 89→92（+Grid/Box/Spacer）；
     * 2026-10-31 经典组合版式批次 92→95（+StatRow/StatItem/Banner）；
     * 2026-10-31 相册 APP 组合方式批次 95→97（+PhotoGrid/AvatarRow）；
     * 元素形状组件（TabItem/Series/Slice/… 仅作父组件数组元素的对象形状）同收白名单，
     * 独立出现的位置约束由端侧 schema/渲染层兜底（半信任口径：白名单 + 明显结构错误，
     * 不做 per-component prop schema 校验；ListItem 2026-10-31 起例外——经典 ListTile
     * 实渲染化，独立/列表内皆可）。
     * internal 供测试钉住全清单（字面量副本同源核对）。
     */
    internal val ALLOWED_COMPONENTS = setOf(
        // 本仓既有 + M2/M3 扩（原二十一组件）
        "Text", "Row", "Column", "Card", "Button", "BarChart", "Image",
        "Icon", "Divider", "List", "Tabs", "CheckBox", "ChoicePicker", "Slider",
        "Table", "PieChart", "LineChart", "Callout", "Markdown", "Accordion", "Switch",
        // 官方别名（schema 注册官方名，渲染复用既有实现）
        "Stack", "Separator", "MarkDownRenderer",
        // 通用布局原语批次（2026-10-30 本仓扩展：四宫格/比例分栏/叠放/留白经典版式）
        "Grid", "Box", "Spacer",
        // 经典组合版式批次（2026-10-31 本仓扩展：统计行/题图横幅固化构图）
        "StatRow", "StatItem", "Banner",
        // 相册 APP 组合方式批次（2026-10-31 本仓扩展：方形照片网格/人物头像条签名形态）
        "PhotoGrid", "AvatarRow",
        // 官方内容/布局
        "CardHeader", "InlineHeader", "TextContent", "TextCallout", "CodeBlock",
        "ImageBlock", "ImageGallery", "Tag", "TagBlock", "Label",
        "BoldText", "IconText", "ImageText", "ImageTextLarge", "EntityList",
        "ListBlock", "Steps", "Carousel", "Buttons", "IconButton", "Modal",
        // 官方表单（name 键控 → 交互即 @Set($<name>, 值) 直写 state）
        "Form", "FormControl", "Input", "TextArea", "Select", "DatePicker",
        "CheckBoxGroup", "RadioGroup", "SwitchGroup", "Chips", "OptionCards", "EditableTable",
        // 官方图表
        "AreaChart", "HorizontalBarChart", "RadarChart", "RadialChart",
        "SingleStackedBarChart", "ScatterChart",
        // 官方卡块（items* + layout?=grid|carousel + action?）
        "MetricIndicatorInline", "MetricIndicatorWithStrikethrough",
        "SnippetCardBlock", "OverviewCardBlock", "ContextCardBlock",
        "CompositeCardBlock", "VisualCardBlock",
        // 元素形状（仅作父组件数组元素，独立出现渲染空占位）
        "TabItem", "AccordionItem", "ListItem", "StepsItem", "SelectItem",
        "CheckBoxItem", "RadioItem", "SwitchItem", "ChipItem", "OptionCard",
        "Series", "Slice", "ScatterSeries", "Point",
        "SnippetCardItem", "OverviewCardItem", "ContextCardItem",
        "CompositeCardItem", "VisualCardItem",
    )

    /**
     * Icon.name 59 枚预定义图标名白名单（M2，禁 svgPath 同口径）——字面量副本三方同步：
     * [RenderUiValidator.ICON_NAMES]（副本源，A2UI catalog v1）↔ 本清单 ↔ shared
     * `OpenUiLibrary.iconNames` ENUM 域 ↔ `RenderOpenUiToolService.ICON_NAMES_TEXT`
     * 描述内嵌清单，改动须四处核对（测试钉住同源）。
     */
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

    /** 组件外唯一允许的大写调用：Query（宿主取数语句）。 */
    private val ALLOWED_CALLS = ALLOWED_COMPONENTS + "Query"

    private val STRING_LITERAL = Regex(""""([^"\\]|\\.)*"""")
    private val LINE_COMMENT = Regex("""//[^\n]*|#[^\n]*""")
    private val CALL_NAME = Regex("""(?<![@${'$'}A-Za-z0-9_])([A-Z][A-Za-z0-9]*)\s*\(""")
    private val ROOT_STATEMENT = Regex("""(?m)^\s*root\s*=""")
    private val CARD_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Card\s*\(""")
    private val QUERY_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Query\s*\(\s*"((?:[^"\\]|\\.)*)"""")

    /** Image 调用实参抽取（2026-10-25 官方对齐：photo 降可选 + src https 公网图）。
     *  实参全是标量无嵌套调用，`[^()]*` 足够；剥离字符串会丢 src 判定信息，扫原文
     *  （同 QUERY_CALL/ICON_CALL 口径——Markdown 文案内写 Image(…) 字样会被一并分析，
     *  属已记录的近似边界，漏判/误判优先保放行好负载）。 */
    private val IMAGE_CALL_ARGS = Regex("""(?<![@${'$'}A-Za-z0-9_])Image\s*\(([^()]*)\)""")

    /** 图片地址键字符串字面量抽取（命名 `src = "…"` 与对象字段 `{src: "…"}` 两种形态；
     *  全组件生效——键名闭集 src / bgImageSrc（VisualCardBlock）/ image（ListBlock 缩略图）
     *  三种，词边界防误伤 images/imageSize 等近名键）。分组 1 = 键名，分组 2 = 地址。 */
    private val SRC_STRING =
        Regex("""(?<![A-Za-z0-9_])(bgImageSrc|src|image)\s*[:=]\s*"((?:[^"\\]|\\.)*)"""")

    private val NAMED_ARG = Regex("""^[A-Za-z_][A-Za-z0-9_]*\s*=""")
    /** photo 类键（2026-10-31 扩 bgPhoto + 对象字段冒号形态：VisualCardBlock items 的
     *  `{bgPhoto: …}` 对象字面量字段同样产生位图，计入单卡图片上限；命名实参 `photo =`
     *  与对象字段 `photo:`/`bgPhoto:` 两种写法同规）。 */
    private val NAMED_PHOTO = Regex("""(?<![A-Za-z0-9_])(bgPhoto|photo)\s*[:=]""")
    private val NAMED_SRC = Regex("""(?<![A-Za-z0-9_])src\s*=""")

    /** photos 数组起始（2026-10-31 PhotoGrid 位图计数）：`photos = […]` / `{photos: […]}`。
     *  元素含嵌套下标括号（photos[0].id），正则 [^\]]* 会在首个 ] 截断，故只定位起始、
     *  元素计数走 [countPhotosArrayBitmaps] 括号配对扫描。`photos = Query(…)` 取数语句
     *  等号后是 Query 非 [，天然不匹配。 */
    private val PHOTOS_ARRAY_START = Regex("""(?<![A-Za-z0-9_])photos\s*[:=]\s*\[""")

    /** PhotoGrid photos 数组元素计数（每个元素 = 一张位图，计入单卡图片上限）：
     *  括号配对扫描数组区间，按顶层逗号分隔数元素（空数组 = 0）。 */
    private fun countPhotosArrayBitmaps(program: String): Int {
        var total = 0
        PHOTOS_ARRAY_START.findAll(program).forEach { match ->
            var depth = 1
            var index = match.range.last + 1
            var elements = 0
            var cellHasContent = false
            while (index < program.length && depth > 0) {
                when (program[index]) {
                    '[', '(' -> depth++
                    ']', ')' -> depth--
                    ',' -> if (depth == 1) {
                        if (cellHasContent) elements++
                        cellHasContent = false
                    }
                    else -> if (depth == 1 && !program[index].isWhitespace()) cellHasContent = true
                }
                index++
            }
            if (cellHasContent) elements++
            total += elements
        }
        return total
    }

    /** Image 实参分析时字符串字面量的占位符（与剥离替换同形，便于按占位符定位位置参数）。 */
    private const val STR_PLACEHOLDER = "\"\""

    /** Icon 首参字符串字面量抽取（M2 Icon name 白名单校验；须在字符串剥离前扫原文，
     *  同 QUERY_CALL 口径）。非字符串首参（如 $绑定）不匹配，由端侧 schema 兜底。 */
    private val ICON_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Icon\s*\(\s*"([^"\\]*)"""")
    private val LOCAL_HANDLE_REF = Regex("""local://[^\s"}\],)]*""")

    /** fragment 闭集白名单（2026-10-24 review 🟡4）：typo（如 #photo）在服务端同轮纠错，
     *  不放行到端侧整卡降级。新增 fragment 须同步 shared OpenUiContract + OpenUiCardHost。 */
    private val LOCAL_HANDLE_FORM = Regex("""local://scan/[0-9A-Za-z]+(#(histogram|photos))?""")
    private val MEDIA_PATH = Regex("""content://|file://|/storage/""")

    /** 动作表达式被包成字符串（2026-10-24 真机事故：LLM 写 "@OpenUrl(...)" 致端侧
     *  ACTION 校验 type-mismatch、按钮静默惰性）——须在字符串剥离前扫原文。 */
    private val QUOTED_ACTION = Regex(""""@(OpenUrl|Set|Reset|ToAssistant)\s*\(""")

    /** 单卡图片总数上限（2026-10-24 review 🟡3 OOM 护栏；2026-10-25 口径从 Image 调用数
     *  扩为整卡位图总数——Image 调用 + ImageGallery images / ImageBlock / ImageText /
     *  VisualCardBlock bgImageSrc / ListBlock image 等一切 src 类图片地址合计）：全宽图
     *  每张约 3.5MB 位图，整卡同屏组合存活，60 张投影上限是 token 保护而非内存保护——
     *  超过即同轮纠错。 */
    const val MAX_IMAGE_COMPONENTS = 12

    /** 构图软警告阈值（2026-10-25，设计规范 §1.1/§4.1）：与工具描述规则同源。 */
    const val MAX_DISPLAY_TEXTS = 4
    const val MAX_BUTTONS = 3

    /** DISPLAY role Text 计数（扫原文——role 在字符串字面量里，剥离后不可见）。
     *  括号只允许出现在字符串字面量内（stat 增量写法 `Text("1,234 (+56)", "DISPLAY")`
     *  不误判）；残留漏判：首参含括号表达式（Text(String($x), …)）与跨行调用。
     *  误伤面（lint 级可接受）：`Text("DISPLAY")` 首参恰为该词的极端写法、注释内
     *  死代码（raw 扫描不剥注释——先剥注释会踩 2026-10-24 local:// 截断事故）。 */
    private val TEXT_DISPLAY_CALL =
        Regex("""(?<![@${'$'}A-Za-z0-9_])Text\s*\((?:[^()"\\]|"(?:[^"\\]|\\.)*")*"DISPLAY"""")

    private val BUTTON_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Button\s*\(""")

    /** stat tile 未居中 lint（2026-10-30 align 治理配套）：DISPLAY + Row 分栏但全文无
     *  align = "center" → 数字贴栏首错落。扫原文近似判定（lint 级可接受误伤面：
     *  DISPLAY 图卡混排无数字栏场景会误报，建议口径不影响发卡）。 */
    private val ROW_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Row\s*\(""")
    private val ALIGN_CENTER = Regex("""align\s*=\s*"center"""")

    /** Row 裸拼图文 lint（2026-10-30 图文组合固化配套）：Row 实参区间内同时出现
     *  Image( 与 Text( → 图文项反模式（等宽分栏把缩略图拉成半宽栏）。纯多图并排
     *  Row([Image, Image]) 是食谱合法形态不误报；实参区间用括号配对扫描精确截取
     *  （正则窗口法会把 Row 之后的 Text 错算进来，误伤 GOLDEN_MIXED 黄金示例）。
     *  词边界防误伤 ImageText(/ImageGallery(/TextContent(/TextCallout( 等近名组件。 */
    private val IMAGE_ANY_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Image\s*\(""")
    private val TEXT_ANY_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Text\s*\(""")

    /** Row weights 键检测（lint 豁免依据：比例分栏是认可写法，无 weights 的等宽裸拼才警告）。 */
    private val NAMED_WEIGHTS = Regex("""(?<![A-Za-z0-9_])weights\s*=""")

    /** Grid 调用（lint：Grid 内平铺 Image ≥2 张 → 引导 PhotoGrid 固化件）。 */
    private val GRID_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Grid\s*\(""")

    /** CardHeader 调用（lint：actionLabel 无 action 配对 → 引导补 @ToAssistant，2026-10-31 真机回归）。 */
    private val CARD_HEADER_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])CardHeader\s*\(""")

    /** actionLabel 命名键检测（「查看全部」类尾部动作位在场的依据）。 */
    private val NAMED_ACTION_LABEL = Regex("""(?<![A-Za-z0-9_])actionLabel\s*=""")

    /** action 命名键检测（不含 actionLabel——`action\s*=` 在 actionLabel 处因后跟 L 不命中）。 */
    private val NAMED_ACTION = Regex("""(?<![A-Za-z0-9_])action\s*=""")

    /**
     * 构图软警告（2026-10-25，设计规范第一刀配套）：不拒卡、不落违规，工具体
     * Observation 附带「本卡已下发、无需重发，下次发卡改进」提示——硬校验管合法性，
     * lint 管美观下限。规则来源：
     * - DISPLAY ≤4（规范 §4.1 stat tile：一屏大数字过多 = 没有重点）；
     * - Button ≤3（规范 §1.1 行为色面积：Button 是 primary 大色块面积的主要代理
     *   分量——Tabs 选中胶囊/Slider/Switch/图表选中高亮不在 lint 面内）。
     */
    fun lintComposition(program: String): List<String> {
        val warnings = mutableListOf<String>()
        val displayCount = TEXT_DISPLAY_CALL.findAll(program).count()
        if (displayCount > MAX_DISPLAY_TEXTS) {
            warnings += "DISPLAY 大数字 $displayCount 个超建议上限（一屏 ≤$MAX_DISPLAY_TEXTS 个，stat tile 只放最关键指标，其余改 BODY/CAPTION 或收进 Table）"
        }
        val stripped = LINE_COMMENT.replace(STRING_LITERAL.replace(program, "\"\""), "")
        val buttonCount = BUTTON_CALL.findAll(stripped).count()
        if (buttonCount > MAX_BUTTONS) {
            warnings += "Button $buttonCount 个超建议上限（一卡 primary 大色块 ≤$MAX_BUTTONS 处，行为色面积收敛；次要动作并入一个主 Button 或改 Markdown 文本链接）"
        }
        if (TEXT_DISPLAY_CALL.containsMatchIn(program) && ROW_CALL.containsMatchIn(program) &&
            !ALIGN_CENTER.containsMatchIn(program)
        ) {
            warnings += "stat tile 数字栏未居中（检测到 DISPLAY 大数字 + Row 分栏但无 align = \"center\"，数字贴栏首错落；每个数字栏 Column 补 align = \"center\"，或直接换 StatRow([{value, label}, ...]) 组件一行固化构图）"
        }
        callArgSpans(stripped, ROW_CALL).forEach { args ->
            // 2026-10-30 通用布局原语批次修订：weights 比例分栏是认可的经典版式写法，
            // 仅无 weights 的等宽裸拼才警告（等宽分栏把缩略图拉成半宽栏）
            if (IMAGE_ANY_CALL.containsMatchIn(args) && TEXT_ANY_CALL.containsMatchIn(args) &&
                !NAMED_WEIGHTS.containsMatchIn(args)
            ) {
                warnings += "Row 内等宽裸拼 Image + Text（缩略图会被拉成半宽栏、图文对齐散落）——按比例左图右文给 Row 传 weights（如 weights = [1, 2] 图窄文宽，上下对齐配 align）；一行写法便捷糖可用 ImageText（小图左+文字右）/ ImageTextLarge（通栏图+标题副题）"
            }
        }
        callArgSpans(stripped, GRID_CALL).forEach { args ->
            // 2026-10-31 相册 APP 组合方式批次：照片网格有固化件 PhotoGrid（方形 1:1 +
            // 人脸聚焦 + 2dp 密缝）；Grid+Image 拼装里 Image 帧随各图真实比例（2026-10-31
            // v7 起），网格犬牙交错——≥2 张即引导换固化件
            if (IMAGE_ANY_CALL.findAll(args).count() >= 2) {
                warnings += "Grid 内平铺 Image ≥2 张——照片网格请换 PhotoGrid(photos = [photos[0].id, photos[1].id, ...], columns = 2|3|4) 固化件（方形 1:1 + 人脸聚焦 + 2dp 密缝，相册 APP 主视图签名形态）；Grid+Image 拼装的帧随各图真实比例会犬牙交错"
            }
        }
        callArgSpans(stripped, CARD_HEADER_CALL).forEach { args ->
            // 2026-10-31 真机回归「查看全部点击无效」：只传 actionLabel 不传 action 时端侧
            // 虽有同文案 @ToAssistant 兜底，但显式 action 文案语境更准（兜底只有标题上下文）
            if (NAMED_ACTION_LABEL.containsMatchIn(args) && !NAMED_ACTION.containsMatchIn(args)) {
                warnings += "CardHeader 有 actionLabel 但缺 action——尾部「查看全部」类动作位请显式配 action = @ToAssistant(\"查看全部 <分组语境>\")（以用户消息上行回话；缺省端侧仅兜底发同文案，语境不准）"
            }
        }
        return warnings
    }

    /** 大写调用的实参区间扫描：从 call 正则命中处起括号配对到闭合（嵌套调用/数组
     *  一并含入）。调用方须传剥离字符串后的文本（引号内括号已抹除，配对不受干扰）。 */
    private fun callArgSpans(stripped: String, call: Regex): List<String> {
        val spans = mutableListOf<String>()
        call.findAll(stripped).forEach { match ->
            var depth = 0
            var end = -1
            for (index in match.range.last until stripped.length) {
                when (stripped[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
            }
            if (end > match.range.last) {
                spans += stripped.substring(match.range.last + 1, end)
            }
        }
        return spans
    }

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
        validate(program).takeIf { violations -> violations.isNotEmpty() }
            ?.let { violations -> errorText(violations, program) }

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
        QUOTED_ACTION.findAll(program).forEach { match ->
            violations += "动作表达式被包成了字符串（${match.value}…）：Button 的 action 是裸动作表达式，去掉最外层那对引号——写 Button(\"查看\", @OpenUrl(\"https://…\"))，不要 Button(\"查看\", \"@OpenUrl(…)\")"
        }
        // 剥离字符串字面量与行注释后再扫结构（防文案内容误伤）。
        // 顺序必须是先字符串后注释：handle 里的 local:// 含 //，先剥注释会把
        // 字符串内部截断留下奇数引号，后续字符串全局配对跨行吞掉 root 语句
        // （2026-10-24 真机事故：合法两步写法被误报「缺 root」死循环烧光 20 迭代）
        val stripped = LINE_COMMENT.replace(STRING_LITERAL.replace(program, "\"\""), "")

        // 1. root 入口：root = 语句存在，或存在 Card 调用兜底 entry（spec §2.2）
        if (!ROOT_STATEMENT.containsMatchIn(stripped) && !CARD_CALL.containsMatchIn(stripped)) {
            violations += "缺 root = 语句（root 是组件树入口；无 root 时兜底的首个 Card 语句也缺失）"
        }

        // 2. 组件名白名单：大写开头调用只允许八十九组件 + Query
        CALL_NAME.findAll(stripped)
            .map { match -> match.groupValues[1] }
            .toSet()
            .filter { name -> name !in ALLOWED_CALLS }
            .forEach { name ->
                violations += "组件/调用 \"$name\" 不在白名单（95 组件全量清单见 render_ui_openui 工具描述，另有 Query 取数；官方 Col 列式组件刻意不收，表格用行式 Table）"
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
                violations += "handle \"${match.value}\" 必须是 local://scan/<handleId> 形态（fragment 只许 #histogram/#photos 或省略）；handleId 只许来自 search_media/refine_media_search 结果尾部，禁止臆造"
            }
        }
        if (hasLocalHandleQuery && !program.contains("local://scan/")) {
            violations += "Query(\"local_handle\", …) 缺 handle 参数：args 对象须含 { handle: \"local://scan/<id>#histogram\" }（取照片清单用 #photos）"
        }

        // 4. 图片数量上限（OOM 护栏，见 MAX_IMAGE_COMPONENTS KDoc；2026-10-25 口径扩为
        //    整卡同屏位图总数）：Image(…) 调用数 + 全部图片地址键（src/bgImageSrc/image）
        //    字面量数 + Image 调用区间外的命名 photo 键数（2026-10-30 图文组合固化：
        //    ImageText/ImageTextLarge 的 photo = 投影同样产生位图）——Image(src=…) 的
        //    src 与 Image(photo=…) 的 photo 落在 Image 调用区间内，只计一次不双计
        val imageCalls = IMAGE_CALL_ARGS.findAll(program).toList()
        val imageCallRanges = imageCalls.map { match -> match.range }
        val srcOutsideImage = SRC_STRING.findAll(program)
            .count { match -> imageCallRanges.none { range -> match.range.first in range } }
        val photoOutsideImage = NAMED_PHOTO.findAll(program)
            .count { match -> imageCallRanges.none { range -> match.range.first in range } }
        val imageCount = imageCalls.size + srcOutsideImage + photoOutsideImage +
            countPhotosArrayBitmaps(program)
        if (imageCount > MAX_IMAGE_COMPONENTS) {
            violations += "图片 $imageCount 张超上限（单卡最多 $MAX_IMAGE_COMPONENTS 张——Image 组件与 ImageGallery/ImageBlock/ImageText 等 src 类图片地址、ImageText/ImageTextLarge 的 photo 投影及 PhotoGrid photos 数组元素合计，防整卡同屏位图 OOM；精简到最关键的几张）"
        }

        // 5. Icon name 白名单（M2，正则级）：禁臆造图标名在服务端同轮纠错
        ICON_CALL.findAll(program).forEach { match ->
            val iconName = match.groupValues[1]
            if (iconName !in ICON_NAMES) {
                violations += "Icon name \"$iconName\" 不在预定义图标集（59 枚，如 photo/person/star/check/warning/settings/search 等，禁臆造，全量清单见工具描述）"
            }
        }

        // 6. 图片地址 https 硬校验（2026-10-25 官方对齐，[PRIVACY] 同 A2UI Image.url
        //    口径）：任意组件的 src/bgImageSrc/image 键（`键 = "…"` 或 `{键: "…"}` 形态）
        //    字符串字面量都必须 https 公网地址
        SRC_STRING.findAll(program).forEach { match ->
            val key = match.groupValues[1]
            val url = match.groupValues[2]
            if (!url.startsWith("https://")) {
                violations += "$key 只接受 https:// 公网图片地址（实际 \"$url\"）——相册照片走 Image(photo) 投影 id，禁本地路径/uri"
            }
        }

        // 7. Image 图片来源（photo/src 二缺一）：渲染层虽兜底丢占位，prompt 层引导
        //    LLM 必给其一——皆无在服务端同轮纠错；位置参数形态 src（第三个位置参数是
        //    字符串字面量）同样限 https
        IMAGE_CALL_ARGS.findAll(program).forEach { match ->
            val args = match.groupValues[1]
            val tokenized = STRING_LITERAL.replace(args, STR_PLACEHOLDER)
            val parts = tokenized.split(',')
                .map { part -> part.trim() }
                .filter { part -> part.isNotEmpty() }
            val positional = parts.filter { part -> !NAMED_ARG.containsMatchIn(part) }
            val hasPhoto = NAMED_PHOTO.containsMatchIn(args) ||
                (positional.isNotEmpty() && positional[0] != STR_PLACEHOLDER)
            val hasSrc = NAMED_SRC.containsMatchIn(args) ||
                positional.getOrNull(2) == STR_PLACEHOLDER
            if (!hasPhoto && !hasSrc) {
                violations += "Image 缺图片来源：photo（相册投影 id，如 photos[0].id）与 src（\"https://…\" 公网图）必须给其一"
            }
            if (positional.getOrNull(2) == STR_PLACEHOLDER) {
                // 位置序 photo, aspect, src, alt——按占位符次序定位第三个位置参数
                // 对应的字符串字面量
                var literalOrdinal = 0
                var positionalIndex = 0
                var srcLiteralOrdinal = -1
                parts.forEach { part ->
                    val isNamed = NAMED_ARG.containsMatchIn(part)
                    if (!isNamed && part == STR_PLACEHOLDER && positionalIndex == 2 && srcLiteralOrdinal < 0) {
                        srcLiteralOrdinal = literalOrdinal
                    }
                    literalOrdinal += part.split(STR_PLACEHOLDER).size - 1
                    if (!isNamed) positionalIndex++
                }
                // STRING_LITERAL 的分组只覆盖最后一个重复单元（量词在分组外），
                // 取完整字面量须用 match.value 去首尾引号
                val srcLiteral = STRING_LITERAL.findAll(args)
                    .map { literal -> literal.value.removeSurrounding("\"") }
                    .toList()
                    .getOrNull(srcLiteralOrdinal)
                if (srcLiteral != null && !srcLiteral.startsWith("https://")) {
                    violations += "Image.src 只接受 https:// 公网图片地址（实际 \"$srcLiteral\"）——相册照片走 photo 投影 id，禁本地路径/uri"
                }
            }
        }

        return violations
    }

    private fun errorText(violations: List<String>, program: String = ""): String = buildString {
        append("error: render_ui_openui 负载校验未通过，本次未下发端侧。逐条修正后重新调用 render_ui_openui：")
        violations.forEach { violation -> append("\n- ").append(violation) }
        // 回显收到内容开头（2026-10-24 all_years 事故：LLM 连续 4 次「缺 root」却无法从错误文本
        // 自纠——回显可定位 markdown 围栏/单行程序/root 非行首类偏差，program 是 LLM 生成的
        // 骨架（数据禁令保证无用户数据），回显无隐私面）
        val preview = program.replace("\n", "\\n").take(120)
        append("\n本次收到 program 开头：").append(preview)
        if (violations.any { it.startsWith("缺 root") } && program.contains("root")) {
            append("（注意：你的 program 里出现了 root 但不在行首/被包裹——root = 必须独占一行行首，且 program 不要包 markdown 代码围栏）")
        }
    }
}
