package com.mamba.picme.features.debug.openui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiToolNotFoundException
import com.mamba.picme.domain.chat.openui.OpenUiToolResolver
import com.mamba.picme.features.chat.openui.OpenUiCardHost
import com.mamba.picme.features.chat.openui.OpenUiPhotoResolver

/**
 * OpenUI 全组件画廊（debug 专用，参照 AGenUI demo 的 load-all 页）：枚举
 * `OpenUiLibrary` 全部可独立渲染组件，每个组件一条独立 OpenUI Lang 程序、
 * 经真实发卡路径（[OpenUiCardHost]：prepare → 渲染 → 交互回写）渲染。
 *
 * 单组件单卡隔离：任一组件 prepare 失败/静默丢弃不影响其他卡；每条卡上方标注
 * 组件协议名 + prepare 错误计数（✗=Failed 降级 / ⚠=Rendered 带非致命错误），
 * 错误明细（code@stmtId: message）直接内联展示，免翻 logcat。
 *
 * 元素形状组件（TabItem/AccordionItem/ListItem/StepsItem/SelectItem/CheckBoxItem/
 * RadioItem/SwitchItem/ChipItem/OptionCard/Series/Slice/ScatterSeries/Point/
 * SnippetCardItem/OverviewCardItem/ContextCardItem/CompositeCardItem/VisualCardItem）
 * 仅在父组件槽内有语义、独立出现渲染空占位（引擎半信任输入防御既有口径），不单独演示。
 *
 * [I18N] 豁免：组件名/错误明细/分组名全为协议标识符（OpenUiBlueprintDebugger 同口径），
 * 卡内中文文案是演示 payload 数据（SampleDataGenerator 测试数据同口径），非应用 UI 文案。
 */

/** 单个组件演示：name = 组件协议名，program = 独立 OpenUI Lang 1.0 程序（含 root 语句）。 */
data class OpenUiComponentDemo(val name: String, val program: String)

private data class OpenUiDemoGroup(val title: String, val demos: List<OpenUiComponentDemo>)

private val D = '$'

private val demoGroups: List<OpenUiDemoGroup> = listOf(
    OpenUiDemoGroup(
        "Layout 布局容器",
        listOf(
            OpenUiComponentDemo(
                "Card",
                """root = Card([Text("卡片容器", "HEADLINE"), Text("Card 支持 title 头部与 children 数组", "CAPTION")], "STANDARD", "Card 标题")""",
            ),
            OpenUiComponentDemo(
                "Row",
                """root = Card([Row([Column([Text("7,120", "DISPLAY"), Text("照片总数", "CAPTION")]), Column([Text("771", "DISPLAY"), Text("视频总数", "CAPTION")]), Column([Text("12", "DISPLAY"), Text("覆盖月份", "CAPTION")])])])""",
            ),
            OpenUiComponentDemo(
                "Column",
                """root = Card([Column([Text("第一行"), Text("第二行 · COMPACT 间距"), Text("第三行")], "COMPACT")])""",
            ),
            OpenUiComponentDemo(
                "Stack",
                """root = Card([Stack([Tag("人像"), Tag("风景"), Tag("美食"), Tag("宠物"), Tag("旅行"), Tag("文档"), Tag("截图"), Tag("全景")], "row", "s", "start", "start", true)])""",
            ),
            OpenUiComponentDemo(
                "List",
                """root = Card([List([IconText("照片", "photo", "7,120 张"), IconText("视频", "play", "771 个"), IconText("相册", "folder", "86 本")])])""",
            ),
            OpenUiComponentDemo(
                "Tabs",
                """root = Card([Tabs([{title: "照片", children: [Text("照片页内容")]}, {title: "相册", children: [Text("相册页内容")]}, {title: "统计", children: [Text("统计页内容")]}])])""",
            ),
            OpenUiComponentDemo(
                "Accordion",
                """root = Card([Accordion([{title: "什么是 OpenUI", children: [Text("协议驱动的生成式 UI")]}, {title: "数据从哪来", children: [Text("Query 绑定端侧补全")]}])])""",
            ),
            OpenUiComponentDemo(
                "Carousel",
                """root = Card([Carousel([Card([Text("卡片一", "SUBHEAD")]), Card([Text("卡片二", "SUBHEAD")]), Card([Text("卡片三", "SUBHEAD")])])])""",
            ),
            OpenUiComponentDemo(
                "Buttons",
                """root = Card([Buttons([Button(label="主要"), Button(label="次要", variant="secondary"), Button(label="文字", variant="tertiary")])])""",
            ),
            OpenUiComponentDemo(
                "Modal",
                "${D}modalOpen = false\n" +
                    "root = Card([Button(\"打开弹窗\", @Set(${D}modalOpen, true)), " +
                    "Modal(\"示例弹窗\", ${D}modalOpen, [Text(\"这是 Modal 内容区\"), " +
                    "Button(\"关闭\", @Set(${D}modalOpen, false), \"secondary\")])])",
            ),
        ),
    ),
    OpenUiDemoGroup(
        "Text 文案",
        listOf(
            OpenUiComponentDemo(
                "Text",
                """root = Card([Text("DISPLAY 大数字 32sp", "DISPLAY"), Text("HEADLINE 标题", "HEADLINE"), Text("SUBHEAD 副标题", "SUBHEAD"), Text("BODY 正文（默认）", "BODY"), Text("CAPTION 弱注", "CAPTION"), Text("PRIMARY 主色文字", "BODY", "PRIMARY"), Text("ERROR 错误文字", "BODY", "ERROR")])""",
            ),
            OpenUiComponentDemo(
                "Markdown",
                """root = Card([Markdown("**加粗** 与 *斜体* 混排\n- 列表项一\n- 列表项二\n\n## 小节标题\n正文收尾")])""",
            ),
            OpenUiComponentDemo(
                "MarkDownRenderer",
                """root = Card([MarkDownRenderer("**MarkDownRenderer** 别名形态（同 Markdown 渲染）")])""",
            ),
            OpenUiComponentDemo(
                "CardHeader",
                """root = Card([CardHeader("卡片头部标题", "副标题说明文字")])""",
            ),
            OpenUiComponentDemo(
                "InlineHeader",
                """root = Card([InlineHeader("轻量标题", "描述文字")])""",
            ),
            OpenUiComponentDemo(
                "TextContent",
                """root = Card([TextContent("small 字阶", "small"), TextContent("default 字阶"), TextContent("large-heavy 字阶", "large-heavy")])""",
            ),
            OpenUiComponentDemo(
                "Label",
                """root = Card([Label("弱色小标签")])""",
            ),
            OpenUiComponentDemo(
                "BoldText",
                """root = Card([Row([BoldText("1,024", "本周新增", "lg"), BoldText("86", "相册", "lg")])])""",
            ),
            OpenUiComponentDemo(
                "TextCallout",
                """root = Card([TextCallout("同步完成", "所有照片已备份", "success"), TextCallout("存储告急", "剩余不足 1 GB", "danger")])""",
            ),
            OpenUiComponentDemo(
                "Callout",
                """root = Card([Callout("本月新增 328 张，存储占用健康", "结论", "SUCCESS"), Callout("有 12 张照片待整理", "提醒", "WARNING"), Callout("中立提示信息", "说明", "NEUTRAL")])""",
            ),
            OpenUiComponentDemo(
                "CodeBlock",
                """root = Card([CodeBlock("val photos = scan(2026)", "kotlin")])""",
            ),
            OpenUiComponentDemo(
                "Tag",
                """root = Card([Row([Tag("人像", "person", "md", "info"), Tag("风景", "photo", "md", "success"), Tag("警告", "warning", "md", "danger")])])""",
            ),
            OpenUiComponentDemo(
                "TagBlock",
                """root = Card([TagBlock(["人像", "风景", "宠物", "美食", "旅行"])])""",
            ),
        ),
    ),
    OpenUiDemoGroup(
        "Icon 图标与分隔",
        listOf(
            OpenUiComponentDemo(
                "Divider",
                """root = Card([Text("上方内容"), Divider(), Text("下方内容")])""",
            ),
            OpenUiComponentDemo(
                "Separator",
                """root = Card([Text("上方内容"), Separator(), Text("下方内容（同义别名）")])""",
            ),
            OpenUiComponentDemo(
                "Icon",
                """root = Card([Row([Icon("favorite", "ERROR"), Icon("photo"), Icon("star"), Icon("check", "PRIMARY")])])""",
            ),
            OpenUiComponentDemo(
                "IconText",
                """root = Card([IconText("拍摄时间", "calendarToday", "2026-10-26"), IconText("收藏夹", "favorite", "加粗项", true)])""",
            ),
            OpenUiComponentDemo(
                "IconButton",
                """root = Card([Row([IconButton("刷新", "refresh", @ToAssistant("刷新")), IconButton("分享", "share", @ToAssistant("分享")), IconButton("收藏", "favorite", @ToAssistant("收藏"))])])""",
            ),
        ),
    ),
    OpenUiDemoGroup(
        "Media 媒体",
        listOf(
            OpenUiComponentDemo(
                "Image",
                """root = Card([Image(src="https://picsum.photos/seed/polang-demo1/600/400", alt="示例图")])""",
            ),
            OpenUiComponentDemo(
                "ImageBlock",
                """root = Card([ImageBlock("https://picsum.photos/seed/polang-demo2/600/360", "示例图")])""",
            ),
            OpenUiComponentDemo(
                "ImageGallery",
                """root = Card([ImageGallery([{src: "https://picsum.photos/seed/polang-ig1/400/300", alt: "图一", details: "第一张"}, {src: "https://picsum.photos/seed/polang-ig2/400/300", alt: "图二"}, {src: "https://picsum.photos/seed/polang-ig3/400/300", alt: "图三"}])])""",
            ),
            OpenUiComponentDemo(
                "ImageText",
                """root = Card([ImageText("海边之旅", "https://picsum.photos/seed/polang-it/240/240", "2026 夏 · 128 张")])""",
            ),
            OpenUiComponentDemo(
                "ImageTextLarge",
                """root = Card([ImageTextLarge("城市印象", "https://picsum.photos/seed/polang-itl/600/400", "摄于 2026 年秋")])""",
            ),
        ),
    ),
    OpenUiDemoGroup(
        "List 列表",
        listOf(
            OpenUiComponentDemo(
                "EntityList",
                """root = Card([EntityList([{left: "照片总数", right: "7,120"}, {left: "视频总数", right: "771"}, {left: "存储占用", right: "12.4 GB"}], "相册统计", "2026", "数据来源", "本机")])""",
            ),
            OpenUiComponentDemo(
                "ListBlock",
                """root = Card([ListBlock([{title: "导入照片", subtitle: "从相机或文件导入"}, {title: "清理缓存", subtitle: "可释放 2.1 GB", actionLabel: "执行", action: @ToAssistant("清理缓存")}, {title: "备份设置", subtitle: "WebDAV 自动备份"}])])""",
            ),
            OpenUiComponentDemo(
                "Steps",
                """root = Card([Steps([{title: "扫描相册", details: "建立媒体索引"}, {title: "AI 打标", details: "端侧 VLM 识别"}, {title: "生成报告", details: "输出统计结论"}])])""",
            ),
        ),
    ),
    OpenUiDemoGroup(
        "Form 表单（name 键控回写 state）",
        listOf(
            OpenUiComponentDemo(
                "Form",
                "${D}nickname = \"\"\n" +
                    "root = Card([Form(\"profile\", [FormControl(\"昵称\", [Input(\"nickname\", \"输入昵称\")], \"2-20 个字符\")]), Text(${D}nickname, \"CAPTION\")])",
            ),
            OpenUiComponentDemo(
                "Input",
                "${D}kw = \"\"\n" +
                    "root = Card([Input(\"kw\", \"搜索关键词\", \"text\"), Text(${D}kw, \"CAPTION\")])",
            ),
            OpenUiComponentDemo(
                "TextArea",
                "${D}note = \"\"\n" +
                    "root = Card([TextArea(\"note\", \"多行备注…\", 3)])",
            ),
            OpenUiComponentDemo(
                "Select",
                """root = Card([Select("sort", [{value: "new", label: "最新优先"}, {value: "old", label: "最旧优先"}], "选择排序方式")])""",
            ),
            OpenUiComponentDemo(
                "DatePicker",
                """root = Card([DatePicker("trip", "range")])""",
            ),
            OpenUiComponentDemo(
                "CheckBox",
                "${D}ck = false\n" +
                    "root = Card([CheckBox(\"只看精选\", ${D}ck), Text(${D}ck, \"CAPTION\")])",
            ),
            OpenUiComponentDemo(
                "Switch",
                "${D}sw = true\n" +
                    "root = Card([Switch(\"自动备份\", ${D}sw), Text(${D}sw, \"CAPTION\")])",
            ),
            OpenUiComponentDemo(
                "CheckBoxGroup",
                """root = Card([CheckBoxGroup("tags", [{label: "人像", name: "t_person", defaultChecked: true}, {label: "风景", name: "t_view"}, {label: "美食", name: "t_food"}])])""",
            ),
            OpenUiComponentDemo(
                "SwitchGroup",
                """root = Card([SwitchGroup([{label: "自动备份", name: "s_backup", defaultChecked: true}, {label: "省流量模式", name: "s_saver"}], "card")])""",
            ),
            OpenUiComponentDemo(
                "RadioGroup",
                """root = Card([RadioGroup("quality", [{label: "标准", value: "sd"}, {label: "高清", value: "hd"}], "sd")])""",
            ),
            OpenUiComponentDemo(
                "ChoicePicker",
                "${D}cp = \"new\"\n" +
                    "root = Card([ChoicePicker(\"排序方式\", [{label: \"最新\", value: \"new\"}, {label: \"最旧\", value: \"old\"}, {label: \"最多\", value: \"most\"}], ${D}cp), Text(${D}cp, \"CAPTION\")])",
            ),
            OpenUiComponentDemo(
                "Slider",
                "${D}lv = 50\n" +
                    "root = Card([Slider(\"美化强度\", ${D}lv, 0, 100), Text(${D}lv, \"CAPTION\")])",
            ),
            OpenUiComponentDemo(
                "Chips",
                """root = Card([Chips("flt", [{value: "p", label: "人像", icon: "person"}, {value: "v", label: "风景", icon: "photo"}, {value: "f", label: "美食"}], "multiple")])""",
            ),
            OpenUiComponentDemo(
                "OptionCards",
                """root = Card([OptionCards("mode", [{value: "fast", title: "快速模式", subtitle: "低功耗处理"}, {value: "pro", title: "专业模式", subtitle: "最高质量"}], "single")])""",
            ),
            OpenUiComponentDemo(
                "EditableTable",
                """root = Card([EditableTable("t1", [{type: "text", key: "name", header: "名称"}, {type: "number", key: "count", header: "数量"}], [{id: "r1", values: ["照片", 128]}, {id: "r2", values: ["视频", 32]}])])""",
            ),
        ),
    ),
    OpenUiDemoGroup(
        "Chart 图表",
        listOf(
            OpenUiComponentDemo(
                "BarChart",
                "${D}sel = \"\"\n" +
                    "root = Card([BarChart(\"月度照片（点选联动）\", [{label: \"1月\", count: 120}, {label: \"2月\", count: 200}, {label: \"3月\", count: 160}], ${D}sel), Text(${D}sel, \"CAPTION\")])",
            ),
            OpenUiComponentDemo(
                "PieChart",
                """root = Card([PieChart("类目占比", [{label: "人像", value: 142}, {label: "风景", value: 98}, {label: "美食", value: 45}])])""",
            ),
            OpenUiComponentDemo(
                "LineChart",
                """root = Card([LineChart("月度趋势", ["1月", "2月", "3月", "4月"], [{name: "照片", values: [120, 200, 160, 240]}, {name: "视频", values: [30, 45, 28, 60]}])])""",
            ),
            OpenUiComponentDemo(
                "AreaChart",
                """root = Card([AreaChart("增长面积", ["1月", "2月", "3月", "4月"], [{name: "照片", values: [120, 200, 160, 240]}])])""",
            ),
            OpenUiComponentDemo(
                "HorizontalBarChart",
                """root = Card([HorizontalBarChart("类目横向对比", ["人像", "风景", "美食"], [{name: "数量", values: [142, 98, 45]}])])""",
            ),
            OpenUiComponentDemo(
                "RadarChart",
                """root = Card([RadarChart("能力雷达", ["人像", "风景", "美食", "宠物", "文档"], [{name: "占比", values: [80, 60, 45, 30, 55]}])])""",
            ),
            OpenUiComponentDemo(
                "RadialChart",
                """root = Card([RadialChart("存储占比", [{category: "照片", value: 62}, {category: "视频", value: 28}, {category: "其他", value: 10}])])""",
            ),
            OpenUiComponentDemo(
                "SingleStackedBarChart",
                """root = Card([SingleStackedBarChart("年度构成", [{category: "照片", value: 62}, {category: "视频", value: 28}, {category: "其他", value: 10}])])""",
            ),
            OpenUiComponentDemo(
                "ScatterChart",
                """root = Card([ScatterChart("尺寸分布", [{name: "照片", points: [[1.0, 2.0], [2.0, 3.5], [3.0, 2.8], [4.0, 4.2]]}], "宽度(MP)", "高度(MP)")])""",
            ),
        ),
    ),
    OpenUiDemoGroup(
        "CardBlock 卡块",
        listOf(
            OpenUiComponentDemo(
                "MetricIndicatorInline",
                """root = Card([Row([MetricIndicatorInline("7,120", "照片总数", "up", 12.0), MetricIndicatorInline("12.4 GB", "存储占用", "down", 3.0)])])""",
            ),
            OpenUiComponentDemo(
                "MetricIndicatorWithStrikethrough",
                """root = Card([Row([MetricIndicatorWithStrikethrough("328", "本月新增", "298", "up", 10.0), MetricIndicatorInline("86", "相册数")])])""",
            ),
            OpenUiComponentDemo(
                "SnippetCardBlock",
                """root = Card([SnippetCardBlock([{lhs: [Text("待办事项", "SUBHEAD")], rhs: [Icon("check")]}, {lhs: [Text("整理重复照片")], rhs: [Tag(text="新", size="sm", variant="info")]}])])""",
            ),
            OpenUiComponentDemo(
                "OverviewCardBlock",
                """root = Card([OverviewCardBlock([{top: [Text("本月摘要", "SUBHEAD")], bottom: [Text("新增 328 张", "CAPTION")]}, {top: [Text("存储")], bottom: [Text("12.4 GB", "CAPTION")]}])])""",
            ),
            OpenUiComponentDemo(
                "ContextCardBlock",
                """root = Card([ContextCardBlock([{title: "备份提醒", body: "上次备份在 3 天前"}, {title: "存储预警", body: "剩余空间不足 10%"}])])""",
            ),
            OpenUiComponentDemo(
                "CompositeCardBlock",
                """root = Card([CompositeCardBlock([{header: [Text("周报", "SUBHEAD")], body: [Text("本周新增 128 张照片")], footer: [Text("2026-W43", "CAPTION")]}])])""",
            ),
            OpenUiComponentDemo(
                "VisualCardBlock",
                """root = Card([VisualCardBlock([{body: "探索你的相册", bgImageSrc: "https://picsum.photos/seed/polang-vb/600/400", tag: "推荐"}])])""",
            ),
        ),
    ),
    OpenUiDemoGroup(
        "Button 按钮",
        listOf(
            OpenUiComponentDemo(
                "Button",
                """root = Card([Row([Button("主要", @OpenUrl("https://a2ui.org")), Button("次要", @OpenUrl("https://a2ui.org"), "secondary"), Button("文字", @OpenUrl("https://a2ui.org"), "tertiary")])])""",
            ),
        ),
    ),
)

private val elementShapeNote =
    "元素形状组件（仅在父组件槽内有语义，独立渲染为空占位，不单独演示）：TabItem / AccordionItem / " +
        "ListItem / StepsItem / SelectItem / CheckBoxItem / RadioItem / SwitchItem / ChipItem / OptionCard / " +
        "Series / Slice / ScatterSeries / Point / SnippetCardItem / OverviewCardItem / ContextCardItem / " +
        "CompositeCardItem / VisualCardItem"

/** 画廊不取数：Query 一律未注册异常（演示程序本就不含 Query，防御性兜底）。 */
private val galleryTools = OpenUiToolResolver { name, _ ->
    throw OpenUiToolNotFoundException(name)
}

private val galleryHandleLookup = object : HandleLookup {
    override suspend fun payloadOf(domain: String, handleId: String): String? = null
}

/** 照片解析 stub：演示程序不用 photo 通道（Image 全走 https src），恒 null 落占位。 */
private val galleryPhotoResolver = OpenUiPhotoResolver { null }

@Composable
fun OpenUiComponentGallery(modifier: Modifier = Modifier) {
    val total = demoGroups.sumOf { it.demos.size }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Text(
                text = "OpenUI Load All · $total components · ${demoGroups.size} groups",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        demoGroups.forEach { group ->
            item(key = "group-${group.title}") {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            group.demos.forEach { demo ->
                item(key = "demo-${demo.name}") {
                    OpenUiDemoItem(demo)
                }
            }
        }
        item {
            Text(
                text = elementShapeNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单组件卡：组件名 + prepare 错误标记（✗ 降级 / ⚠ 非致命错误数+明细）+ 真实路径渲染。 */
@Composable
private fun OpenUiDemoItem(demo: OpenUiComponentDemo) {
    // 并行 prepare 一次专取错误明细（画廊程序无 Query，纯解析开销可忽略）；
    // 渲染仍走 OpenUiCardHost 真实路径（它内部再建 session）
    val prep by produceState<OpenUiResult?>(initialValue = null, demo.program) {
        value = OpenUiSession(program = demo.program, tools = galleryTools).prepare()
    }
    val errors = when (val result = prep) {
        is OpenUiResult.Rendered -> result.errors
        is OpenUiResult.Failed -> result.errors
        null -> emptyList()
    }
    val failed = prep is OpenUiResult.Failed

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = demo.name,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            when {
                failed -> Text(
                    text = "✗ FAILED",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                )
                errors.isNotEmpty() -> Text(
                    text = "⚠ ${errors.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        errors.forEach { error ->
            Text(
                text = "${error.code}@${error.statementId}: ${error.message}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OpenUiCardHost(
            part = MessagePart.OpenUiCard(
                partId = "gallery-${demo.name}",
                program = demo.program,
                fallbackSummary = "${demo.name} 演示卡降级",
            ),
            handleLookup = galleryHandleLookup,
            photoResolver = galleryPhotoResolver,
            onViewStateChange = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
