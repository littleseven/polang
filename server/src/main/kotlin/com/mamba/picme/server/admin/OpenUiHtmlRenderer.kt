package com.mamba.picme.server.admin

import com.mamba.picme.domain.chat.openui.OpenUiError
import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiValue

/** 调试结果页组件树预览：prepare 成功 = 渲染后 HTML；失败 = 错误清单（视图层回落旧提取链）。 */
sealed interface OpenUiTreePreview {
    data class Rendered(val bodyHtml: String, val errors: List<OpenUiError>) : OpenUiTreePreview
    data class Failed(val errors: List<OpenUiError>) : OpenUiTreePreview
}

/**
 * OpenUI 组件树 → 手机壳内可视化 HTML（2026-11-07，0.9.37）。
 *
 * 与端上同一求值语义：复用 :shared 的 [OpenUiSession.prepare]（parse → state 初始化 →
 * Query 统一求值 → entry 解析 → 校验恢复，内部全兜底不抛）。服务端无宿主工具，Query 恒
 * 回 [OpenUiValue.Null]（骨架渲染；照片句柄跨设备无意义，与调试页「设备工具恒
 * device_offline」同口径）。渲染对照端侧 OpenUiNodeRenderer 的 prop 键名逐组件近似，
 * BINDABLE 属性按 prepare 后 state 快照物化（同端侧渲染期 `state[binding.name]` 口径）；
 * 未覆盖组件渲染带组件名的虚线占位框（痛点是「未显示」，近似也要显示）。
 */
object OpenUiHtmlRenderer {

    /** 求值并渲染整树；任何程序都不会抛（prepare 内部兜底 + 渲染层全分支防御）。 */
    suspend fun preview(program: String): OpenUiTreePreview {
        val session = OpenUiSession(program, { _, _ -> OpenUiValue.Null })
        val result = try {
            session.prepare()
        } catch (t: Throwable) {
            return OpenUiTreePreview.Failed(
                listOf(OpenUiError("preview-exception", message = t.message ?: t::class.simpleName.orEmpty())),
            )
        }
        return when (result) {
            is OpenUiResult.Rendered ->
                OpenUiTreePreview.Rendered(Renderer(session.state).render(result.root), result.errors)
            is OpenUiResult.Failed -> OpenUiTreePreview.Failed(result.errors)
        }
    }

    /** 单棵树直渲（测试/复用入口；state 为宿主观测快照，缺省空）。 */
    fun renderNode(node: OpenUiValue.ComponentVal, state: Map<String, OpenUiValue> = emptyMap()): String =
        Renderer(state).render(node)

    private class Renderer(private val state: Map<String, OpenUiValue>) {

        // ── 节点渲染 ──────────────────────────────────────────────────────

        fun render(node: OpenUiValue.ComponentVal): String = when (node.type) {
            "Card" -> renderCard(node)
            "Row" -> renderRow(node)
            "Column" -> renderColumn(node)
            "Grid" -> renderGrid(node)
            "Box" -> renderBox(node)
            "Stack" -> renderStack(node)
            "Spacer" -> renderSpacer(node)
            "Text" -> renderText(node)
            "Markdown" -> renderMarkdownNode(node, "text")
            "MarkDownRenderer" -> renderMarkdownNode(node, "textMarkdown")
            "TextContent" -> renderTextContent(node)
            "BoldText" -> strProp(node, "text")?.let { "<div style=\"font-weight:700;font-size:14px\">${esc(it)}</div>" }
            "Label" -> strProp(node, "text")?.let { "<div style=\"font-size:12px;color:#6b7280\">${esc(it)}</div>" }
            "Button" -> renderButton(node)
            "Buttons" -> renderButtons(node)
            "IconButton" -> placeholder("IconButton", strProp(node, "name"))
            "Icon" -> placeholder("Icon", strProp(node, "name"))
            "Divider", "Separator" -> renderDivider(node)
            "Html" -> renderHtml(node)
            "Image" -> renderImage(node)
            "ImageBlock" -> renderImage(node)
            "ImageGallery" -> renderImageGallery(node)
            "Video" -> placeholder("Video", strProp(node, "src"))
            "CardHeader" -> renderCardHeader(node)
            "InlineHeader" -> renderCardHeader(node)
            "IconText" -> renderIconText(node)
            "ImageText", "ImageTextLarge" -> renderImageText(node, large = node.type == "ImageTextLarge")
            "ListItem" -> renderListItem(node)
            "EntityList" -> renderEntityList(node)
            "ListBlock" -> renderListBlock(node)
            "Steps" -> renderSteps(node)
            "Carousel" -> renderCarousel(node)
            "StatRow" -> renderStatRow(node)
            "StatItem" -> renderStatItem(node)
            "Banner" -> renderBanner(node)
            "PhotoGrid" -> renderPhotoGrid(node)
            "AvatarRow" -> renderAvatarRow(node)
            "PhotoCompare" -> renderPhotoCompare(node)
            "Tag" -> strProp(node, "text")?.let { tagPill(it) }
            "TagBlock" -> renderTagBlock(node)
            "Callout" -> renderCallout(node)
            "TextCallout" -> renderTextCallout(node)
            "CodeBlock" -> renderCodeBlock(node)
            "Table" -> renderTable(node)
            "Tabs" -> renderTabs(node)
            "Accordion" -> renderAccordion(node)
            "List" -> renderList(node)
            "BarChart" -> renderBarChart(node)
            "PieChart" -> renderPieChart(node)
            "LineChart", "AreaChart", "HorizontalBarChart", "RadarChart", "RadialChart",
            "SingleStackedBarChart", "ScatterChart",
            -> placeholder(node.type, strProp(node, "title"))
            "CheckBox" -> renderCheckBox(node)
            "Switch" -> renderSwitch(node)
            "Slider" -> renderSlider(node)
            "ChoicePicker" -> renderChoicePicker(node)
            "RadioGroup", "CheckBoxGroup" -> renderOptionGroup(node)
            "Input", "TextArea" -> renderInput(node)
            "Select", "DatePicker" -> renderSelectLike(node)
            "Form", "FormControl", "Modal" -> renderContainerColumn(node)
            else -> placeholder(node.type, null)
        } ?: ""

        // ── 布局容器 ──────────────────────────────────────────────────────

        private fun renderCard(node: OpenUiValue.ComponentVal): String {
            val title = strProp(node, "title")
            val body = StringBuilder()
            if (title != null) {
                body.append("<div style=\"font-size:16px;font-weight:600;margin-bottom:10px\">${esc(title)}</div>")
            }
            body.append(childrenHtml(node, gap = 10))
            return "<div style=\"background:#fff;border-radius:16px;padding:16px;" +
                "box-shadow:0 1px 4px rgba(0,0,0,.08);margin-bottom:2px\">$body</div>"
        }

        private fun renderRow(node: OpenUiValue.ComponentVal): String {
            val children = childrenOf(node)
            val gap = spacingPx(node)
            val align = alignItems(node)
            val weights = (node.props["weights"] as? OpenUiValue.Arr)?.v
                ?.map { (it as? OpenUiValue.Num)?.v }
            val weightableCount = children.count { c -> c is OpenUiValue.ComponentVal && !isSeparator(c) }
            var slot = 0
            val items = children.joinToString("") { child ->
                if (child is OpenUiValue.ComponentVal) {
                    val separator = isSeparator(child)
                    val grow = if (separator) {
                        null
                    } else {
                        val declared = weights?.takeIf { it.size == weightableCount }?.getOrNull(slot)
                        slot++
                        declared ?: 1.0
                    }
                    val style = when {
                        grow == null -> "flex:0 0 auto"
                        grow <= 0.0 -> "flex:0 0 auto"
                        else -> "flex:${trimNum(grow)} 1 0;min-width:0"
                    }
                    "<div style=\"$style\">${render(child)}</div>"
                } else {
                    "<div style=\"flex:1 1 0;min-width:0\"></div>"
                }
            }
            return "<div style=\"display:flex;flex-direction:row;gap:${gap}px;align-items:$align\">$items</div>"
        }

        private fun renderColumn(node: OpenUiValue.ComponentVal): String =
            childrenHtml(node, gap = spacingPx(node), align = alignItems(node))

        private fun renderContainerColumn(node: OpenUiValue.ComponentVal): String {
            val title = strProp(node, "title")
            val head = title?.let { "<div style=\"font-size:15px;font-weight:600;margin-bottom:8px\">${esc(it)}</div>" } ?: ""
            return head + childrenHtml(node, gap = 10)
        }

        private fun renderGrid(node: OpenUiValue.ComponentVal): String {
            val columns = ((node.props["columns"] as? OpenUiValue.Num)?.v ?: 2.0).toInt().coerceIn(1, 4)
            val gap = spacingPx(node)
            val cells = childrenOf(node).joinToString("") { child ->
                "<div style=\"min-width:0\">${(child as? OpenUiValue.ComponentVal)?.let { render(it) } ?: ""}</div>"
            }
            return "<div style=\"display:grid;grid-template-columns:repeat($columns,1fr);gap:${gap}px\">$cells</div>"
        }

        private fun renderBox(node: OpenUiValue.ComponentVal): String {
            val layers = childrenOf(node).joinToString("") { child ->
                "<div style=\"grid-area:1/1;min-width:0\">${(child as? OpenUiValue.ComponentVal)?.let { render(it) } ?: ""}</div>"
            }
            return "<div style=\"display:grid\">$layers</div>"
        }

        private fun renderStack(node: OpenUiValue.ComponentVal): String {
            val row = strProp(node, "direction") == "row"
            val wrap = (node.props["wrap"] as? OpenUiValue.Bool)?.v == true
            val gap = stackGapPx(strProp(node, "gap"))
            val direction = if (row) "row" else "column"
            val wrapStyle = if (wrap) ";flex-wrap:wrap" else ""
            val items = childrenOf(node).joinToString("") { child ->
                "<div style=\"min-width:0\">${(child as? OpenUiValue.ComponentVal)?.let { render(it) } ?: ""}</div>"
            }
            return "<div style=\"display:flex;flex-direction:$direction;gap:${gap}px$wrapStyle\">$items</div>"
        }

        private fun renderSpacer(node: OpenUiValue.ComponentVal): String {
            val size = spacingPxOf(strProp(node, "size"))
            return "<div style=\"height:${size}px;width:${size}px;flex:0 0 auto\"></div>"
        }

        private fun renderList(node: OpenUiValue.ComponentVal): String {
            val gap = spacingPx(node)
            return if (strProp(node, "direction") == "HORIZONTAL") {
                val items = childrenOf(node).joinToString("") { child ->
                    "<div style=\"flex:0 0 auto;max-width:240px\">${(child as? OpenUiValue.ComponentVal)?.let { render(it) } ?: ""}</div>"
                }
                "<div style=\"display:flex;gap:${gap}px;overflow-x:auto\">$items</div>"
            } else {
                childrenHtml(node, gap = gap)
            }
        }

        // ── 文本与 Markdown ───────────────────────────────────────────────

        private fun renderText(node: OpenUiValue.ComponentVal): String? {
            val text = bindableText(node.props["text"]) ?: return null
            val role = strProp(node, "role")
            val color = colorRole(strProp(node, "colorRole"))
            val style = when (role) {
                "DISPLAY" -> "font-size:34px;font-weight:800;line-height:1.2"
                "TITLE" -> "font-size:20px;font-weight:700;line-height:1.3"
                "SUBHEAD" -> "font-size:16px;font-weight:600;line-height:1.4"
                "CAPTION" -> "font-size:12px;font-weight:400;line-height:1.5"
                else -> "font-size:14px;font-weight:400;line-height:1.6"
            }
            val colorCss = color ?: if (role == "CAPTION") "#6b7280" else "#1a1a1a"
            return "<div style=\"$style;color:$colorCss\">${esc(text)}</div>"
        }

        private fun renderTextContent(node: OpenUiValue.ComponentVal): String? {
            val text = bindableText(node.props["text"]) ?: return null
            val size = when (strProp(node, "size")) {
                "large" -> "font-size:18px;font-weight:600"
                "small" -> "font-size:12px;color:#6b7280"
                else -> "font-size:14px"
            }
            return "<div style=\"$size;line-height:1.6\">${esc(text)}</div>"
        }

        private fun renderMarkdownNode(node: OpenUiValue.ComponentVal, prop: String): String? {
            val text = bindableText(node.props[prop]) ?: return null
            return "<div class=\"md-preview\" style=\"padding:0;font-size:14px\">${AdminViews.renderMarkdown(text)}</div>"
        }

        // ── 按钮与图标 ────────────────────────────────────────────────────

        private fun renderButton(node: OpenUiValue.ComponentVal): String? {
            val label = strProp(node, "label") ?: return null
            return when (strProp(node, "variant")) {
                "secondary" ->
                    "<div style=\"display:inline-block;background:#eef2f7;color:#1a1a1a;border-radius:20px;" +
                        "padding:9px 18px;font-size:14px;text-align:center\">${esc(label)}</div>"
                "tertiary" ->
                    "<div style=\"display:inline-block;color:#0a84ff;padding:9px 4px;font-size:14px\">${esc(label)}</div>"
                else ->
                    "<div style=\"background:#0a84ff;color:#fff;border-radius:20px;padding:11px 18px;" +
                        "font-size:14px;font-weight:500;text-align:center\">${esc(label)}</div>"
            }
        }

        private fun renderButtons(node: OpenUiValue.ComponentVal): String {
            val buttons = (node.props["buttons"] as? OpenUiValue.Arr)?.v.orEmpty()
                .mapNotNull { item ->
                    val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
                    val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
                    label to (obj["variant"] as? OpenUiValue.Str)?.v
                }
            if (buttons.isEmpty()) return placeholder("Buttons", null)
            val column = strProp(node, "direction") == "column"
            val items = buttons.joinToString("") { (label, variant) ->
                val style = when (variant) {
                    "secondary" -> "background:#eef2f7;color:#1a1a1a"
                    "tertiary" -> "background:transparent;color:#0a84ff"
                    else -> "background:#0a84ff;color:#fff"
                }
                "<div style=\"$style;border-radius:20px;padding:9px 16px;font-size:13px;text-align:center;" +
                    "flex:${if (column) "0 0 auto" else "1 1 0"}\">${esc(label)}</div>"
            }
            return "<div style=\"display:flex;flex-direction:${if (column) "column" else "row"};gap:8px\">$items</div>"
        }

        // ── 分隔/媒体 ─────────────────────────────────────────────────────

        private fun renderDivider(node: OpenUiValue.ComponentVal): String =
            if (strProp(node, "axis") == "VERTICAL" || strProp(node, "orientation") == "vertical") {
                "<div style=\"width:1px;align-self:stretch;background:rgba(0,0,0,.12)\"></div>"
            } else {
                "<div style=\"height:1px;background:rgba(0,0,0,.12);margin:4px 0\"></div>"
            }

        private fun renderHtml(node: OpenUiValue.ComponentVal): String? {
            val html = strProp(node, "html")?.takeIf { it.isNotBlank() } ?: return null
            return "<iframe sandbox=\"allow-scripts\" loading=\"lazy\" " +
                "style=\"display:block;width:100%;height:420px;border:0;border-radius:12px;background:#fff\" " +
                "srcdoc=\"${attr(html)}\"></iframe>"
        }

        private fun renderImage(node: OpenUiValue.ComponentVal): String {
            val src = strProp(node, "src")?.takeIf { it.startsWith("https://") }
            val aspect = (node.props["aspect"] as? OpenUiValue.Num)?.v?.takeIf { it in 0.3..3.0 } ?: (4.0 / 3.0)
            val alt = strProp(node, "alt") ?: ""
            if (src != null) {
                return "<img src=\"${attr(src)}\" alt=\"${attr(alt)}\" " +
                    "style=\"width:100%;aspect-ratio:${trimNum(aspect)};object-fit:cover;border-radius:12px;background:#f0f0f2\"/>"
            }
            val photoId = resolveNum(node.props["photo"])?.toLong()
            return mediaPlaceholder("照片 ${photoId ?: "·"}", aspect)
        }

        private fun renderImageGallery(node: OpenUiValue.ComponentVal): String {
            val images = (node.props["images"] as? OpenUiValue.Arr)?.v.orEmpty()
            if (images.isEmpty()) return placeholder("ImageGallery", null)
            val cells = images.take(9).joinToString("") { item ->
                val src = when (item) {
                    is OpenUiValue.Str -> item.v
                    is OpenUiValue.Obj -> (item.v["src"] as? OpenUiValue.Str)?.v
                    else -> null
                }?.takeIf { it.startsWith("https://") }
                if (src != null) {
                    "<img src=\"${attr(src)}\" style=\"width:100%;aspect-ratio:1;object-fit:cover;border-radius:8px;background:#f0f0f2\"/>"
                } else {
                    mediaPlaceholder("图", 1.0)
                }
            }
            return "<div style=\"display:grid;grid-template-columns:repeat(3,1fr);gap:6px\">$cells</div>"
        }

        // ── 内容组合 ──────────────────────────────────────────────────────

        private fun renderCardHeader(node: OpenUiValue.ComponentVal): String? {
            val title = strProp(node, "title") ?: return null
            val subtitle = strProp(node, "subtitle") ?: strProp(node, "description")
            val sub = subtitle?.let { "<div style=\"font-size:12px;color:#6b7280;margin-top:2px\">${esc(it)}</div>" } ?: ""
            return "<div style=\"margin-bottom:4px\"><div style=\"font-size:16px;font-weight:600\">${esc(title)}</div>$sub</div>"
        }

        private fun renderIconText(node: OpenUiValue.ComponentVal): String? {
            val text = strProp(node, "text") ?: return null
            val icon = strProp(node, "icon")
            val chip = icon?.let {
                "<span style=\"flex:0 0 auto;font-size:10px;color:#6b7280;border:1px solid #d8d8de;" +
                    "border-radius:6px;padding:2px 6px\">${esc(it)}</span>"
            } ?: ""
            return "<div style=\"display:flex;gap:8px;align-items:center;font-size:14px\">$chip<span>${esc(text)}</span></div>"
        }

        private fun renderImageText(node: OpenUiValue.ComponentVal, large: Boolean): String? {
            val title = strProp(node, "title") ?: return null
            val size = if (large) 72 else (resolveNum(node.props["imageSize"])?.toInt() ?: 48)
            val thumb = thumbHtml(node, size)
            val subtitle = strProp(node, "subtitle")
            val sub = subtitle?.let { "<div style=\"font-size:12px;color:#6b7280;margin-top:2px\">${esc(it)}</div>" } ?: ""
            return "<div style=\"display:flex;gap:12px;align-items:center\">$thumb" +
                "<div style=\"min-width:0\"><div style=\"font-size:14px;font-weight:600\">${esc(title)}</div>$sub</div></div>"
        }

        private fun renderListItem(node: OpenUiValue.ComponentVal): String? {
            val title = strProp(node, "title") ?: return null
            val subtitle = strProp(node, "subtitle")
            val trailing = strProp(node, "trailing") ?: strProp(node, "actionLabel")
            val iconName = strProp(node, "icon")
            val leading = when {
                strProp(node, "image") != null || node.props["photo"] != null -> thumbHtml(node, 40)
                iconName != null ->
                    "<div style=\"flex:0 0 auto;width:32px;height:32px;border-radius:8px;background:#eef2f7;" +
                        "display:flex;align-items:center;justify-content:center;font-size:9px;color:#6b7280\">${esc(iconName.take(4))}</div>"
                else -> ""
            }
            val sub = subtitle?.let { "<div style=\"font-size:12px;color:#6b7280;margin-top:1px\">${esc(it)}</div>" } ?: ""
            val trail = trailing?.let { "<div style=\"flex:0 0 auto;font-size:12px;color:#0a84ff\">${esc(it)}</div>" } ?: ""
            return "<div style=\"display:flex;gap:10px;align-items:center;padding:6px 0\">$leading" +
                "<div style=\"flex:1 1 0;min-width:0\"><div style=\"font-size:14px\">${esc(title)}</div>$sub</div>$trail</div>"
        }

        private fun renderEntityList(node: OpenUiValue.ComponentVal): String {
            val items = objArrProp(node, "items")
            if (items.isEmpty()) return placeholder("EntityList", null)
            return items.take(12).joinToString("") { obj ->
                val title = (obj["title"] as? OpenUiValue.Str)?.v ?: primitiveText(obj["name"]) ?: "·"
                val subtitle = (obj["subtitle"] as? OpenUiValue.Str)?.v
                val sub = subtitle?.let { "<div style=\"font-size:12px;color:#6b7280\">${esc(it)}</div>" } ?: ""
                "<div style=\"padding:7px 0;border-bottom:1px solid #f0f0f2\">" +
                    "<div style=\"font-size:14px\">${esc(title)}</div>$sub</div>"
            }
        }

        private fun renderListBlock(node: OpenUiValue.ComponentVal): String {
            val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty()
            if (items.isEmpty()) return placeholder("ListBlock", null)
            return items.take(12).joinToString("") { item ->
                when (item) {
                    is OpenUiValue.Obj -> {
                        val title = (item.v["title"] as? OpenUiValue.Str)?.v ?: primitiveText(item.v["label"]) ?: "·"
                        "<div style=\"padding:7px 0;border-bottom:1px solid #f0f0f2;font-size:14px\">${esc(title)}</div>"
                    }
                    else -> "<div style=\"padding:7px 0;border-bottom:1px solid #f0f0f2;font-size:14px\">${esc(primitiveText(item) ?: "·")}</div>"
                }
            }
        }

        private fun renderSteps(node: OpenUiValue.ComponentVal): String {
            val items = objArrProp(node, "items")
            if (items.isEmpty()) return placeholder("Steps", null)
            return items.take(10).mapIndexed { index, obj ->
                val title = (obj["title"] as? OpenUiValue.Str)?.v ?: "步骤 ${index + 1}"
                val desc = (obj["description"] as? OpenUiValue.Str)?.v
                val descHtml = desc?.let { "<div style=\"font-size:12px;color:#6b7280;margin-top:2px\">${esc(it)}</div>" } ?: ""
                "<div style=\"display:flex;gap:10px;padding:6px 0\">" +
                    "<div style=\"flex:0 0 auto;width:22px;height:22px;border-radius:11px;background:#0a84ff;color:#fff;" +
                    "font-size:12px;display:flex;align-items:center;justify-content:center\">${index + 1}</div>" +
                    "<div style=\"min-width:0\"><div style=\"font-size:14px;font-weight:500\">${esc(title)}</div>$descHtml</div></div>"
            }.joinToString("")
        }

        private fun renderCarousel(node: OpenUiValue.ComponentVal): String {
            val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty()
            if (items.isEmpty()) return placeholder("Carousel", null)
            val slides = items.take(10).joinToString("") { item ->
                when (item) {
                    is OpenUiValue.ComponentVal -> "<div style=\"flex:0 0 200px;min-width:0\">${render(item)}</div>"
                    is OpenUiValue.Obj -> {
                        val src = (item.v["src"] as? OpenUiValue.Str)?.v?.takeIf { it.startsWith("https://") }
                        if (src != null) {
                            "<img src=\"${attr(src)}\" style=\"flex:0 0 160px;width:160px;aspect-ratio:4/3;object-fit:cover;border-radius:10px;background:#f0f0f2\"/>"
                        } else {
                            "<div style=\"flex:0 0 160px\">${mediaPlaceholder("图", 4.0 / 3.0)}</div>"
                        }
                    }
                    else -> ""
                }
            }
            return "<div style=\"display:flex;gap:10px;overflow-x:auto;padding-bottom:4px\">$slides</div>"
        }

        private fun renderStatRow(node: OpenUiValue.ComponentVal): String {
            val items = objArrProp(node, "items")
            if (items.isEmpty()) return placeholder("StatRow", null)
            val tiles = items.take(4).joinToString("") { obj ->
                val value = primitiveText(obj["value"]) ?: "—"
                val label = (obj["label"] as? OpenUiValue.Str)?.v ?: ""
                "<div style=\"flex:1 1 0;min-width:0;text-align:center\">" +
                    "<div style=\"font-size:24px;font-weight:800\">${esc(value)}</div>" +
                    "<div style=\"font-size:11px;color:#6b7280;margin-top:2px\">${esc(label)}</div></div>"
            }
            return "<div style=\"display:flex;gap:8px\">$tiles</div>"
        }

        private fun renderStatItem(node: OpenUiValue.ComponentVal): String? {
            val value = bindableText(node.props["value"]) ?: return null
            val label = strProp(node, "label")
            val subtext = strProp(node, "subtext")
            val labelHtml = label?.let { "<div style=\"font-size:11px;color:#6b7280;margin-top:2px\">${esc(it)}</div>" } ?: ""
            val subHtml = subtext?.let { "<div style=\"font-size:11px;color:#6b7280\">${esc(it)}</div>" } ?: ""
            return "<div style=\"text-align:center\"><div style=\"font-size:24px;font-weight:800\">${esc(value)}</div>$labelHtml$subHtml</div>"
        }

        private fun renderBanner(node: OpenUiValue.ComponentVal): String? {
            val title = strProp(node, "title") ?: return null
            val subtitle = strProp(node, "subtitle")
            val src = strProp(node, "src")?.takeIf { it.startsWith("https://") }
            val aspect = resolveNum(node.props["aspect"])?.takeIf { it in 0.3..3.0 } ?: (16.0 / 9.0)
            val bg = if (src != null) {
                "background-image:linear-gradient(rgba(0,0,0,.35),rgba(0,0,0,.35)),url('${attr(src)}');background-size:cover;background-position:center"
            } else {
                "background:linear-gradient(135deg,#5b7fff,#0a84ff)"
            }
            val sub = subtitle?.let { "<div style=\"font-size:12px;opacity:.9;margin-top:4px\">${esc(it)}</div>" } ?: ""
            return "<div style=\"$bg;border-radius:14px;aspect-ratio:${trimNum(aspect)};display:flex;" +
                "flex-direction:column;justify-content:flex-end;padding:14px;color:#fff\">" +
                "<div style=\"font-size:16px;font-weight:700\">${esc(title)}</div>$sub</div>"
        }

        private fun renderPhotoGrid(node: OpenUiValue.ComponentVal): String {
            val ids = (node.props["photos"] as? OpenUiValue.Arr)?.v.orEmpty()
                .mapNotNull { resolveNum(it)?.toLong() }
            if (ids.isEmpty()) return placeholder("PhotoGrid", null)
            val columns = (resolveNum(node.props["columns"])?.toInt() ?: 3).coerceIn(2, 4)
            val cells = ids.take(12).joinToString("") { id -> mediaPlaceholder("照片 $id", 1.0) }
            return "<div style=\"display:grid;grid-template-columns:repeat($columns,1fr);gap:6px\">$cells</div>"
        }

        private fun renderAvatarRow(node: OpenUiValue.ComponentVal): String {
            val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty()
            if (items.isEmpty()) return placeholder("AvatarRow", null)
            val avatars = items.take(8).joinToString("") { item ->
                val obj = (item as? OpenUiValue.Obj)?.v
                val src = (obj?.get("src") as? OpenUiValue.Str)?.v?.takeIf { it.startsWith("https://") }
                val name = (obj?.get("name") as? OpenUiValue.Str)?.v ?: primitiveText(item) ?: "?"
                if (src != null) {
                    "<img src=\"${attr(src)}\" style=\"width:32px;height:32px;border-radius:16px;object-fit:cover;" +
                        "border:2px solid #fff;margin-left:-8px\"/>"
                } else {
                    "<div style=\"width:32px;height:32px;border-radius:16px;background:#5b7fff;color:#fff;" +
                        "font-size:13px;display:flex;align-items:center;justify-content:center;border:2px solid #fff;" +
                        "margin-left:-8px\">${esc(name.take(1))}</div>"
                }
            }
            return "<div style=\"display:flex;padding-left:8px\">$avatars</div>"
        }

        private fun renderPhotoCompare(node: OpenUiValue.ComponentVal): String {
            val beforeLabel = strProp(node, "beforeLabel") ?: "Before"
            val afterLabel = strProp(node, "afterLabel") ?: "After"
            val aspect = resolveNum(node.props["aspect"])?.takeIf { it in 0.3..3.0 } ?: (4.0 / 3.0)
            fun side(label: String, srcProp: String, photoProp: String): String {
                val src = strProp(node, srcProp)?.takeIf { it.startsWith("https://") }
                val body = if (src != null) {
                    "<img src=\"${attr(src)}\" style=\"width:100%;aspect-ratio:${trimNum(aspect)};object-fit:cover;border-radius:10px;background:#f0f0f2\"/>"
                } else {
                    val id = resolveNum(node.props[photoProp])?.toLong()
                    mediaPlaceholder("照片 ${id ?: "·"}", aspect)
                }
                return "<div style=\"flex:1 1 0;min-width:0\">$body" +
                    "<div style=\"font-size:11px;color:#6b7280;text-align:center;margin-top:4px\">${esc(label)}</div></div>"
            }
            return "<div style=\"display:flex;gap:8px\">${side(beforeLabel, "beforeSrc", "beforePhoto")}${side(afterLabel, "afterSrc", "afterPhoto")}</div>"
        }

        // ── 标签与提示 ────────────────────────────────────────────────────

        private fun tagPill(text: String): String =
            "<span style=\"display:inline-block;background:#eef2f7;color:#3a3a3e;border-radius:12px;" +
                "padding:3px 10px;font-size:12px;margin:0 4px 4px 0\">${esc(text)}</span>"

        private fun renderTagBlock(node: OpenUiValue.ComponentVal): String {
            val tags = (node.props["tags"] as? OpenUiValue.Arr)?.v.orEmpty()
                .mapNotNull { primitiveText(it) }
            if (tags.isEmpty()) return placeholder("TagBlock", null)
            return "<div>" + tags.take(12).joinToString("") { tagPill(it) } + "</div>"
        }

        private fun renderCallout(node: OpenUiValue.ComponentVal): String? {
            val text = strProp(node, "text") ?: return null
            val title = strProp(node, "title")
            val tone = strProp(node, "tone") ?: "INFO"
            val color = when (tone) {
                "SUCCESS" -> "#16a34a"
                "WARNING" -> "#d97706"
                "ERROR" -> "#dc2626"
                "NEUTRAL" -> "#6b7280"
                else -> "#0a84ff"
            }
            val titleHtml = title?.let { "<div style=\"font-size:14px;font-weight:700;color:$color;margin-bottom:2px\">${esc(it)}</div>" } ?: ""
            return "<div style=\"background:${color}1a;border-radius:12px;padding:12px\">$titleHtml" +
                "<div style=\"font-size:14px;color:#1a1a1a\">${esc(text)}</div></div>"
        }

        private fun renderTextCallout(node: OpenUiValue.ComponentVal): String? {
            val text = strProp(node, "text") ?: return null
            return "<div style=\"background:#f5f6f8;border-radius:12px;padding:12px;font-size:14px\">${esc(text)}</div>"
        }

        private fun renderCodeBlock(node: OpenUiValue.ComponentVal): String? {
            val code = strProp(node, "codeString") ?: return null
            val lang = strProp(node, "language")
            val langHtml = lang?.let { "<div style=\"font-size:10px;color:#9aa0a6;margin-bottom:4px\">${esc(it)}</div>" } ?: ""
            return "<div style=\"background:#f5f5f5;border-radius:8px;padding:10px\">$langHtml" +
                "<pre style=\"margin:0;font-size:12px;font-family:ui-monospace,Menlo,Consolas,monospace;" +
                "white-space:pre-wrap;word-break:break-all\">${esc(code)}</pre></div>"
        }

        // ── 表格与页签 ────────────────────────────────────────────────────

        private fun renderTable(node: OpenUiValue.ComponentVal): String {
            val columns = (node.props["columns"] as? OpenUiValue.Arr)?.v.orEmpty()
                .mapNotNull { item -> ((item as? OpenUiValue.Obj)?.v?.get("label") as? OpenUiValue.Str)?.v }
            if (columns.isEmpty()) return placeholder("Table", null)
            val rows = (node.props["rows"] as? OpenUiValue.Arr)?.v.orEmpty()
                .mapNotNull { (it as? OpenUiValue.Arr)?.v }
                .take(20)
            val head = columns.joinToString("") { label ->
                "<th style=\"text-align:left;font-size:12px;font-weight:700;padding:6px 8px;border-bottom:2px solid #e5e5ea\">${esc(label)}</th>"
            }
            val body = rows.joinToString("") { cells ->
                val tds = columns.indices.joinToString("") { index ->
                    val cell = cells.getOrNull(index)?.let { primitiveText(it) } ?: ""
                    "<td style=\"font-size:12px;padding:6px 8px;border-bottom:1px solid #f0f0f2\">${esc(cell)}</td>"
                }
                "<tr>$tds</tr>"
            }
            return "<div style=\"overflow-x:auto\"><table style=\"width:100%;border-collapse:collapse\">" +
                "<thead><tr>$head</tr></thead><tbody>$body</tbody></table></div>"
        }

        private fun renderTabs(node: OpenUiValue.ComponentVal): String {
            val tabs = titledChildren(node.props["tabs"])
            if (tabs.isEmpty()) return placeholder("Tabs", null)
            val capsules = tabs.take(6).mapIndexed { index, (title, _) ->
                val style = if (index == 0) {
                    "background:#0a84ff;color:#fff"
                } else {
                    "background:transparent;color:#6b7280"
                }
                "<span style=\"$style;border-radius:14px;padding:5px 14px;font-size:12px\">${esc(title)}</span>"
            }.joinToString("")
            val content = tabs.first().second.take(12).joinToString("") { child ->
                "<div style=\"margin-top:8px\">${(child as? OpenUiValue.ComponentVal)?.let { render(it) } ?: ""}</div>"
            }
            return "<div><div style=\"display:flex;gap:4px;background:#eef2f7;border-radius:16px;padding:3px;" +
                "flex-wrap:wrap\">$capsules</div>$content</div>"
        }

        private fun renderAccordion(node: OpenUiValue.ComponentVal): String {
            val items = titledChildren(node.props["items"])
            if (items.isEmpty()) return placeholder("Accordion", null)
            // 预览取「全部展开」静态形态（端上默认全收起；调试场景看内容优先）
            return items.take(12).joinToString("") { (title, children) ->
                val body = children.take(12).joinToString("") { child ->
                    "<div style=\"margin-top:6px\">${(child as? OpenUiValue.ComponentVal)?.let { render(it) } ?: ""}</div>"
                }
                "<div style=\"border-bottom:1px solid #f0f0f2;padding:8px 0\">" +
                    "<div style=\"font-size:14px;font-weight:500\">▾ ${esc(title)}</div>$body</div>"
            }
        }

        // ── 图表 ──────────────────────────────────────────────────────────

        private fun renderBarChart(node: OpenUiValue.ComponentVal): String {
            val title = strProp(node, "title")
            val buckets = (node.props["buckets"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
                val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
                val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
                val count = (obj["count"] as? OpenUiValue.Num)?.v ?: return@mapNotNull null
                label to count.coerceAtLeast(0.0)
            }
            if (buckets.isEmpty()) return placeholder("BarChart", title)
            val max = buckets.maxOf { it.second }.coerceAtLeast(1.0)
            val interval = labelInterval(buckets.size)
            val bars = buckets.mapIndexed { index, (label, count) ->
                val heightPct = (count / max * 100).coerceAtLeast(2.0)
                val labelHtml = if (index % interval == 0) {
                    "<div style=\"font-size:9px;color:#6b7280;margin-top:4px;white-space:nowrap;overflow:hidden;" +
                        "text-overflow:ellipsis;max-width:56px\">${esc(label)}</div>"
                } else {
                    "<div style=\"font-size:9px;margin-top:4px\">&nbsp;</div>"
                }
                "<div style=\"flex:1 1 0;min-width:0;display:flex;flex-direction:column;justify-content:flex-end;" +
                    "align-items:center\">" +
                    "<div style=\"width:70%;height:${trimNum(heightPct * 1.2)}px;background:#5b8def;border-radius:3px 3px 0 0\" " +
                    "title=\"${attr("$label: ${trimNum(count)}")}\"></div>$labelHtml</div>"
            }.joinToString("")
            val titleHtml = title?.let { "<div style=\"font-size:14px;font-weight:600;margin-bottom:8px\">${esc(it)}</div>" } ?: ""
            return "<div>$titleHtml<div style=\"display:flex;align-items:flex-end;gap:2px;height:150px\">$bars</div></div>"
        }

        private fun renderPieChart(node: OpenUiValue.ComponentVal): String {
            val title = strProp(node, "title")
            val slices = (node.props["slices"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
                val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
                val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
                val value = (obj["value"] as? OpenUiValue.Num)?.v ?: return@mapNotNull null
                label to value.coerceAtLeast(0.0)
            }.take(12)
            if (slices.isEmpty()) return placeholder("PieChart", title)
            val total = slices.sumOf { it.second }.coerceAtLeast(0.01)
            val palette = listOf(
                "#5b8def", "#34c759", "#ff9500", "#ff3b30", "#af52de", "#0a84ff",
                "#64d2ff", "#ffd60a", "#ff6482", "#30d5c8", "#ac8e68", "#6b7280",
            )
            var acc = 0.0
            val stops = slices.mapIndexed { index, (_, value) ->
                val from = acc / total * 360
                acc += value
                val to = acc / total * 360
                "${palette[index % palette.size]} ${trimNum(from)}deg ${trimNum(to)}deg"
            }
            val legend = slices.mapIndexed { index, (label, value) ->
                "<div style=\"display:flex;align-items:center;gap:6px;font-size:12px;margin-top:4px\">" +
                    "<span style=\"width:8px;height:8px;border-radius:4px;background:${palette[index % palette.size]};flex:0 0 auto\"></span>" +
                    "<span style=\"flex:1 1 0;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap\">${esc(label)}</span>" +
                    "<span style=\"color:#6b7280\">${esc(trimNum(value))}</span></div>"
            }.joinToString("")
            val titleHtml = title?.let { "<div style=\"font-size:14px;font-weight:600;margin-bottom:8px\">${esc(it)}</div>" } ?: ""
            return "<div>$titleHtml<div style=\"display:flex;gap:16px;align-items:center\">" +
                "<div style=\"flex:0 0 auto;width:110px;height:110px;border-radius:55px;" +
                "background:conic-gradient(${stops.joinToString(",")})\"></div>" +
                "<div style=\"flex:1 1 0;min-width:0\">$legend</div></div></div>"
        }

        // ── 表单近似 ──────────────────────────────────────────────────────

        private fun renderCheckBox(node: OpenUiValue.ComponentVal): String? {
            val label = strProp(node, "label") ?: return null
            val checked = resolveBool(node.props["checked"]) == true
            return optionRow("☑", "☐", checked, label)
        }

        private fun renderSwitch(node: OpenUiValue.ComponentVal): String? {
            val label = strProp(node, "label") ?: return null
            val checked = resolveBool(node.props["checked"]) == true
            val track = if (checked) "background:#34c759;justify-content:flex-end" else "background:#d8d8de;justify-content:flex-start"
            return "<div style=\"display:flex;align-items:center;gap:10px;padding:4px 0\">" +
                "<span style=\"flex:1 1 0;font-size:14px\">${esc(label)}</span>" +
                "<span style=\"width:40px;height:24px;border-radius:12px;$track;display:flex;align-items:center;padding:2px;box-sizing:border-box\">" +
                "<span style=\"width:20px;height:20px;border-radius:10px;background:#fff\"></span></span></div>"
        }

        private fun renderSlider(node: OpenUiValue.ComponentVal): String {
            val min = resolveNum(node.props["min"]) ?: 0.0
            val maxRaw = resolveNum(node.props["max"]) ?: 100.0
            val max = if (maxRaw > min) maxRaw else min + 1.0
            val value = resolveNum(node.props["value"])?.coerceIn(min, max) ?: min
            val pct = (value - min) / (max - min) * 100
            val label = strProp(node, "label")
            val labelHtml = label?.let {
                "<div style=\"display:flex;justify-content:space-between;font-size:13px;margin-bottom:4px\">" +
                    "<span>${esc(it)}</span><span style=\"color:#6b7280\">${esc(trimNum(value))}</span></div>"
            } ?: ""
            return "<div>$labelHtml<div style=\"height:4px;border-radius:2px;background:#e5e5ea;position:relative\">" +
                "<div style=\"position:absolute;left:0;top:0;bottom:0;width:${trimNum(pct)}%;background:#0a84ff;border-radius:2px\"></div>" +
                "<div style=\"position:absolute;top:50%;left:${trimNum(pct)}%;transform:translate(-50%,-50%);" +
                "width:16px;height:16px;border-radius:8px;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.3)\"></div></div></div>"
        }

        private fun renderChoicePicker(node: OpenUiValue.ComponentVal): String {
            val options = optionPairs(node)
            if (options.isEmpty()) return placeholder("ChoicePicker", null)
            val selected = resolveStr(node.props["selected"])
            val chips = options.joinToString("") { (label, value) ->
                val active = selected != null && selected == value
                val style = if (active) "background:#0a84ff;color:#fff" else "background:#eef2f7;color:#3a3a3e"
                "<span style=\"$style;border-radius:14px;padding:5px 14px;font-size:12px;margin:0 4px 4px 0;" +
                    "display:inline-block\">${esc(label)}</span>"
            }
            return "<div>$chips</div>"
        }

        private fun renderOptionGroup(node: OpenUiValue.ComponentVal): String {
            val options = optionPairs(node)
            if (options.isEmpty()) return placeholder(node.type, null)
            val radio = node.type == "RadioGroup"
            val selected = resolveStr(node.props["selected"])
            return options.take(10).joinToString("") { (label, value) ->
                val active = selected != null && selected == value
                if (radio) {
                    optionRow("◉", "○", active, label)
                } else {
                    optionRow("☑", "☐", active, label)
                }
            }
        }

        private fun renderInput(node: OpenUiValue.ComponentVal): String {
            val label = strProp(node, "label")
            val hint = strProp(node, "placeholder") ?: strProp(node, "hint") ?: ""
            val labelHtml = label?.let { "<div style=\"font-size:12px;color:#6b7280;margin-bottom:4px\">${esc(it)}</div>" } ?: ""
            val height = if (node.type == "TextArea") "min-height:64px;" else ""
            return "<div>$labelHtml<div style=\"border:1px solid #d8d8de;border-radius:10px;padding:9px 12px;" +
                "font-size:14px;color:#9aa0a6;${height}\">${esc(hint.ifEmpty { "输入…" })}</div></div>"
        }

        private fun renderSelectLike(node: OpenUiValue.ComponentVal): String {
            val label = strProp(node, "label")
            val labelHtml = label?.let { "<div style=\"font-size:12px;color:#6b7280;margin-bottom:4px\">${esc(it)}</div>" } ?: ""
            val value = resolveStr(node.props["value"]) ?: strProp(node, "placeholder") ?: "选择…"
            return "<div>$labelHtml<div style=\"border:1px solid #d8d8de;border-radius:10px;padding:9px 12px;" +
                "font-size:14px;display:flex;justify-content:space-between\"><span>${esc(value)}</span>" +
                "<span style=\"color:#9aa0a6\">▾</span></div></div>"
        }

        // ── 占位与工具 ────────────────────────────────────────────────────

        /** 未覆盖/无数据组件：虚线占位框带组件名（用户痛点是「未显示」，近似也要显示）。 */
        private fun placeholder(type: String, detail: String?): String {
            val detailHtml = detail?.let { "<div style=\"font-size:11px;color:#9aa0a6;margin-top:2px\">${esc(it)}</div>" } ?: ""
            return "<div style=\"border:1px dashed #c8c8ce;border-radius:10px;padding:14px;text-align:center\">" +
                "<div style=\"font-size:12px;color:#6b7280;font-family:ui-monospace,Menlo,monospace\">${esc(type)}</div>" +
                "$detailHtml</div>"
        }

        private fun mediaPlaceholder(label: String, aspect: Double): String =
            "<div style=\"width:100%;aspect-ratio:${trimNum(aspect)};border-radius:12px;background:#eef0f3;" +
                "display:flex;align-items:center;justify-content:center;font-size:11px;color:#9aa0a6\">${esc(label)}</div>"

        private fun thumbHtml(node: OpenUiValue.ComponentVal, size: Int): String {
            val src = strProp(node, "src")?.takeIf { it.startsWith("https://") }
            return if (src != null) {
                "<img src=\"${attr(src)}\" style=\"flex:0 0 auto;width:${size}px;height:${size}px;" +
                    "border-radius:10px;object-fit:cover;background:#f0f0f2\"/>"
            } else {
                val id = resolveNum(node.props["photo"])?.toLong()
                "<div style=\"flex:0 0 auto;width:${size}px;height:${size}px;border-radius:10px;background:#eef0f3;" +
                    "display:flex;align-items:center;justify-content:center;font-size:9px;color:#9aa0a6\">照片 ${id ?: "·"}</div>"
            }
        }

        private fun optionRow(onGlyph: String, offGlyph: String, active: Boolean, label: String): String {
            val glyph = if (active) "<span style=\"color:#0a84ff\">$onGlyph</span>" else "<span style=\"color:#9aa0a6\">$offGlyph</span>"
            return "<div style=\"display:flex;align-items:center;gap:8px;padding:5px 0;font-size:14px\">$glyph<span>${esc(label)}</span></div>"
        }

        // ── prop 读取工具 ─────────────────────────────────────────────────

        private fun childrenOf(node: OpenUiValue.ComponentVal): List<OpenUiValue> =
            (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty()

        private fun isSeparator(value: OpenUiValue): Boolean =
            value is OpenUiValue.ComponentVal &&
                (value.type == "Divider" || value.type == "Spacer" || value.type == "Separator")

        private fun childrenHtml(
            node: OpenUiValue.ComponentVal,
            gap: Int,
            align: String = "stretch",
        ): String {
            val items = childrenOf(node).joinToString("") { child ->
                "<div style=\"min-width:0\">${(child as? OpenUiValue.ComponentVal)?.let { render(it) } ?: ""}</div>"
            }
            return "<div style=\"display:flex;flex-direction:column;gap:${gap}px;align-items:$align\">$items</div>"
        }

        private fun strProp(node: OpenUiValue.ComponentVal, key: String): String? =
            (node.props[key] as? OpenUiValue.Str)?.v

        private fun objArrProp(node: OpenUiValue.ComponentVal, key: String): List<Map<String, OpenUiValue>> =
            (node.props[key] as? OpenUiValue.Arr)?.v.orEmpty()
                .mapNotNull { (it as? OpenUiValue.Obj)?.v }

        /** Tabs/Accordion 共用的 {title, children} 项解析。 */
        private fun titledChildren(prop: OpenUiValue?): List<Pair<String, List<OpenUiValue>>> =
            (prop as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
                val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
                val title = (obj["title"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
                title to (obj["children"] as? OpenUiValue.Arr)?.v.orEmpty()
            }

        private fun optionPairs(node: OpenUiValue.ComponentVal): List<Pair<String, String>> =
            (node.props["options"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
                val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
                val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
                val value = (obj["value"] as? OpenUiValue.Str)?.v ?: label
                label to value
            }

        /** Str 直取 / Num/Bool 回退文本（端侧 openUiPrimitiveText 口径）。 */
        private fun primitiveText(value: OpenUiValue?): String? = when (value) {
            is OpenUiValue.Str -> value.v
            is OpenUiValue.Num -> trimNum(value.v)
            is OpenUiValue.Bool -> if (value.v) "true" else "false"
            else -> null
        }

        /** BINDABLE_STRING：Binding 按 state 快照物化（端侧渲染期 `state[binding.name]` 口径）。 */
        private fun bindableText(value: OpenUiValue?): String? = when (value) {
            is OpenUiValue.Binding -> primitiveText(state[value.name])
            else -> primitiveText(value)
        }

        private fun resolveNum(value: OpenUiValue?): Double? = when (value) {
            is OpenUiValue.Num -> value.v
            is OpenUiValue.Binding -> (state[value.name] as? OpenUiValue.Num)?.v
            else -> null
        }

        private fun resolveBool(value: OpenUiValue?): Boolean? = when (value) {
            is OpenUiValue.Bool -> value.v
            is OpenUiValue.Binding -> (state[value.name] as? OpenUiValue.Bool)?.v
            else -> null
        }

        private fun resolveStr(value: OpenUiValue?): String? = when (value) {
            is OpenUiValue.Str -> value.v
            is OpenUiValue.Binding -> (state[value.name] as? OpenUiValue.Str)?.v
            else -> null
        }

        // ── 样式常量映射（对照端侧 OpenUiCardTokens/OpenUiStyleMapper）─────

        private fun spacingPx(node: OpenUiValue.ComponentVal): Int = spacingPxOf(strProp(node, "spacing"))

        private fun spacingPxOf(raw: String?): Int = when (raw) {
            "none" -> 0
            "xs", "COMPACT" -> 4
            "s" -> 8
            "l", "COMFORTABLE" -> 16
            "xl" -> 24
            else -> 12
        }

        private fun stackGapPx(raw: String?): Int = when (raw) {
            "none" -> 0
            "xs" -> 4
            "s" -> 8
            "l" -> 16
            "xl" -> 24
            else -> 8
        }

        private fun alignItems(node: OpenUiValue.ComponentVal): String = when (strProp(node, "align")) {
            "start" -> "flex-start"
            "center" -> "center"
            "end" -> "flex-end"
            "baseline" -> "baseline"
            else -> "stretch"
        }

        private fun colorRole(raw: String?): String? = when (raw?.lowercase()) {
            "primary" -> "#0a84ff"
            "secondary" -> "#6b7280"
            "error" -> "#dc2626"
            "success" -> "#16a34a"
            "warning" -> "#d97706"
            else -> null
        }

        /** 端侧 barChartLabelInterval 近似：桶数越多标签越稀。 */
        private fun labelInterval(size: Int): Int = when {
            size <= 8 -> 1
            size <= 16 -> 2
            size <= 32 -> 4
            else -> size / 8
        }

        private fun trimNum(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        private fun esc(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** HTML 属性值转义（& 须最先）。 */
        private fun attr(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
