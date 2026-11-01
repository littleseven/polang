package com.mamba.picme.server.agent

/**
 * OpenUI 布局结构门禁（2026-10-31 布局可控性批次，spec §2.2）——只判结构合法性，
 * 不判好坏/最优（语义错配仍归食谱软引导）。违规经工具体回 error 文本作 Observation
 * 回灌，LLM 同轮修正重试（A2UI RenderUiValidator 闭环口径）。
 *
 * 规则数值初值由 spec §2.2 给出，Ardot 范式帧定稿后可调（常量一处改）。
 */
object OpenUiLayoutValidator {

    /** Card 嵌套层数上限（设计语言 §1.2 Surface 层级：L0 卡底 + L1 区块）。 */
    const val MAX_CARD_DEPTH = 2

    /** 容器（Row/Column/Grid/Box/Stack）连续嵌套链上限。 */
    const val MAX_CONTAINER_CHAIN = 4

    /** 单卡渲染组件节点数宽松护栏（防 token/渲染失控，非卡片膨胀治理）。 */
    const val MAX_COMPONENT_NODES = 60

    /** 规则开关（spec §2.3：误伤止血——把规则名（"R1"~"R4"/"NODE_CAP"）加进集合即关，不改代码）。 */
    internal val DISABLED_RULES: Set<String> = emptySet()

    private val CONTAINERS = setOf("Row", "Column", "Grid", "Box", "Stack")
    private val INERT_PIECES = setOf("Spacer", "Divider", "Separator")
    private val SIGNATURE_PIECES = setOf("Banner", "PhotoGrid", "StatRow", "AvatarRow")
    private val CHARTS = setOf(
        "BarChart", "PieChart", "LineChart", "AreaChart", "HorizontalBarChart",
        "RadarChart", "RadialChart", "SingleStackedBarChart", "ScatterChart",
    )

    /** 返回全部违规描述（空 = 合法放行）。扫描失败（残缺程序）→ 空，交协议校验兜底。 */
    fun validate(program: String): List<String> {
        val roots = OpenUiCallTree.parse(program) ?: return emptyList()
        val violations = mutableListOf<String>()
        var componentCount = 0

        fun hit(rule: String, text: String) {
            if (rule !in DISABLED_RULES) violations += text
        }

        fun walk(node: OpenUiCallTree.CallNode, cardAncestors: Int, containerChain: Int) {
            componentCount++
            val comps = OpenUiCallTree.componentChildren(node)
            if (node.name == "Card") {
                if (cardAncestors + 1 > MAX_CARD_DEPTH) {
                    hit(
                        "R1",
                        "Card 嵌套 ${cardAncestors + 1} 层超上限 $MAX_CARD_DEPTH" +
                            "（卡底+区块两层封顶；内层分区用 CardHeader(\"标题\") 分区，别再套 Card）",
                    )
                }
                comps.groupBy { child -> child.name }
                    .filter { (name, group) -> name in SIGNATURE_PIECES && group.size >= 2 }
                    .forEach { (name, group) ->
                        hit(
                            "R2",
                            "同一 Card 直接子级出现 ${group.size} 个 $name" +
                                "（通栏签名件一卡一件；多组内容请拆多卡或用 List 聚合）",
                        )
                    }
            }
            if (node.name == "Row" || node.name == "Grid") {
                val contentComps = comps.filter { child -> child.name !in INERT_PIECES }
                if (contentComps.size >= 2 && contentComps.any { child -> child.name in CHARTS }) {
                    hit(
                        "R3",
                        "图表不可横向分栏（${node.name} 内图表与其它组件并排会被挤压不可读）" +
                            "——双图并排用 ChartGrid([图A, 图B])，多图请竖排",
                    )
                }
                if (node.name == "Grid" && comps.count { child -> child.name == "Image" } >= 2) {
                    hit(
                        "R4",
                        "Grid 内平铺 Image ≥2 张——照片网格唯一形态是 " +
                            "PhotoGrid(photos = [photos[0].id, …], columns = 2|3|4)（方形 1:1 + 人脸聚焦 + 密缝；" +
                            "Grid+Image 帧随各图真实比例会犬牙交错）",
                    )
                }
            }
            if (node.name == "ChartGrid" && comps.any { child -> child.name !in CHARTS }) {
                hit("R3", "ChartGrid 只装图表族组件（BarChart/PieChart/LineChart 等），非图表子项请移出")
            }
            // R1b 容器链：containerChain = 连续容器祖先数，自身再 +1（链含自己）
            if (node.name in CONTAINERS && containerChain + 1 > MAX_CONTAINER_CHAIN) {
                hit(
                    "R1",
                    "容器嵌套链（Row/Column/Grid/Box/Stack）${containerChain + 1} 层超上限 $MAX_CONTAINER_CHAIN" +
                        "（层级过深既难读又破坏节奏——用平铺 + CardHeader(\"分区标题\") 分区替代层层包裹）",
                )
            }
            val childCard = cardAncestors + if (node.name == "Card") 1 else 0
            val childChain = if (node.name in CONTAINERS) containerChain + 1 else 0
            comps.forEach { child -> walk(child, childCard, childChain) }
        }

        roots.filter { root -> root.name in RenderOpenUiValidator.ALLOWED_COMPONENTS }
            .forEach { root -> walk(root, 0, 0) }

        if (componentCount > MAX_COMPONENT_NODES) {
            hit("NODE_CAP", "单卡组件节点 $componentCount 超上限 $MAX_COMPONENT_NODES（内容请拆多卡或精简）")
        }
        return violations
    }

    /** 门禁错误文本（对齐 [RenderOpenUiValidator] errorText 文风：逐条 + 回显开头）。
     *  turnNative=true 走回合原生口径（2026-11-01 T2 审查修复）：响应即程序无工具可回调，
     *  指引「重新输出完整程序」，不得出现 render_ui_openui 工具口径文案。 */
    fun errorText(violations: List<String>, program: String, turnNative: Boolean = false): String = buildString {
        if (turnNative) {
            append("error: 你的回复（整颗 OpenUI 程序）布局门禁未通过。逐条修正后重新输出完整程序：")
        } else {
            append("error: render_ui_openui 布局门禁未通过，本次未下发端侧。逐条修正后重新调用 render_ui_openui：")
        }
        violations.forEach { violation -> append("\n- ").append(violation) }
        append("\n本次收到 program 开头：")
        append(program.replace("\n", "\\n").take(120))
    }
}
