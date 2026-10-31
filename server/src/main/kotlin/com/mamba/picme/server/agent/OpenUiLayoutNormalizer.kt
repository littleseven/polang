package com.mamba.picme.server.agent

/**
 * OpenUI 布局无声矫正器（2026-10-31 布局可控性批次，spec §2.1）——只做「有唯一
 * 正确解」的机械性改写，不改组件选型与内容（不替 LLM 做语义决策）：
 * - N1 图文 Row 缺 weights：恰好两个组件子项其一为 Image → 按图位补 [1,2]/[2,1]；
 * - N2 节奏归一：连续 Spacer ≥2 合并取最大档；Column/Card 纵向流内 xs/xl 降 s/l；
 * - N3 单子裸容器解包：Row/Column/Grid/Box/Stack 仅一个子项且无命名实参 → 解包；
 * - N4 stat 拼装居中：Row 直接子级 Column 含 DISPLAY 大数字未 align="center" → 补。
 * 自底向上重写（span 锚定原文，非调用区文本原样保留）；幂等。扫描失败/任何意外
 * 原文返回（runCatching 安全网：挂生产发射路径，宁可放过不可崩）。
 *
 * 近似边界（已知保守口径，设计如此）：N1/N4 的键检测扫节点 args 全区间（含子树
 * 原文），子树出现过 weights/align 即跳过追加（漏补无害）；N3 的「裸」同样扫全区间
 * 原文——唯一子自带命名实参的容器不解包（多一层壳无害）；连续 Spacer 之间夹行注释
 * 时，N2 删除吃逗号只回吞空白，注释后的逗号会残留（极端输入风格，不崩，交由既有
 * 协议校验兜底）。
 */
object OpenUiLayoutNormalizer {

    /** 规则开关（spec §2.3：误伤止血——把规则名（"N1"~"N4"）加进集合即关该条，不改代码）。 */
    internal val DISABLED_RULES: Set<String> = emptySet()

    private val UNWRAP_CONTAINERS = setOf("Row", "Column", "Grid", "Box", "Stack")
    private val VERTICAL_FLOW_PARENTS = setOf("Column", "Card")
    private val SPACER_RANK = mapOf("xs" to 0, "s" to 1, "m" to 2, "l" to 3, "xl" to 4)
    private val NAMED_ARG = Regex("""[A-Za-z_][A-Za-z0-9_]*\s*=""")
    private val NAMED_WEIGHTS = Regex("""(?<![A-Za-z0-9_])weights\s*=""")
    private val NAMED_ALIGN = Regex("""(?<![A-Za-z0-9_])align\s*=""")
    private val QUOTED_SIZE = Regex(""""(xs|s|m|l|xl)"""")

    /** 入口：无需矫正/扫描失败/内部异常 → 原文返回。幂等。 */
    fun normalize(program: String): String =
        runCatching { normalizeInternal(program) }.getOrDefault(program)

    private fun normalizeInternal(program: String): String {
        val roots = OpenUiCallTree.parse(program) ?: return program
        var result = program
        roots.sortedByDescending { root -> root.spanStart }.forEach { root ->
            result = result.replaceRange(root.spanStart, root.spanEnd, renderNode(root, program, null))
        }
        return result
    }

    /** 子树最终文本：先递归改子，再决定本节点（N3 解包在父级层面生效）。 */
    private fun renderNode(node: OpenUiCallTree.CallNode, original: String, parentName: String?): String {
        val comps = OpenUiCallTree.componentChildren(node)
        val childText = HashMap<OpenUiCallTree.CallNode, String>()

        // N2 之合并：连续 Spacer 兄弟（组件子项序）≥2 → 留首个、删其余，首个取最大档
        var runStart = -1
        fun closeRun(runEndExclusive: Int) {
            if ("N2" !in DISABLED_RULES && runStart >= 0 && runEndExclusive - runStart >= 2) {
                val run = comps.subList(runStart, runEndExclusive)
                val maxSize = run.maxOf { spacer -> spacerSize(spacer, original) }
                // 合并产出的字面量同样过纵向流降档（xs→s / xl→l）——否则最大档 xl 绕过
                // 降档子规则在纵向流残留，二次 normalize 才修正（幂等反例）
                val size = rankToSize(maxSize)
                val finalSize = if (node.name in VERTICAL_FLOW_PARENTS) {
                    size.replace("xs", "s").replace("xl", "l")
                } else {
                    size
                }
                run.forEachIndexed { i, spacer ->
                    childText[spacer] = if (i == 0) "Spacer(\"$finalSize\")" else ""
                }
            }
            runStart = -1
        }
        comps.forEachIndexed { index, child ->
            if (child.name == "Spacer") {
                if (runStart < 0) runStart = index
            } else {
                closeRun(index)
            }
        }
        closeRun(comps.size)

        // 其余子节点递归；Spacer 单独改写（N2 之降档感知其父容器 = 本节点）
        comps.forEach { child ->
            if (childText.containsKey(child)) return@forEach
            childText[child] = if (child.name == "Spacer") {
                renderSpacer(child, original, node.name)
            } else {
                renderNode(child, original, node.name)
            }
        }

        var args = original.substring(node.argsStart, node.argsEnd)
        // 自后向前替换子节点 span；N2 删除（替换为空串）须连带吃掉前导逗号，避免数组空洞
        node.children.sortedByDescending { child -> child.spanStart }.forEach { child ->
            val replacement = childText[child] ?: original.substring(child.spanStart, child.spanEnd)
            var start = child.spanStart
            if (replacement.isEmpty()) {
                var probe = start - 1
                while (probe >= node.argsStart && original[probe].isWhitespace()) probe--
                if (probe >= node.argsStart && original[probe] == ',') start = probe
            }
            args = args.replaceRange(
                start - node.argsStart,
                child.spanEnd - node.argsStart,
                replacement,
            )
        }
        // N1 / N4：本节点命名实参追加（原文无该键才补）；n4Fired 供 N3 让位判定
        if ("N1" !in DISABLED_RULES && node.name == "Row" && needsWeights(node, comps, original)) {
            val imageFirst = comps.first().name == "Image"
            val weights = if (imageFirst) "[1, 2]" else "[2, 1]"
            args = args.trimEnd() + ", weights = " + weights
        }
        val n4Fired = "N4" !in DISABLED_RULES && node.name == "Column" && parentName == "Row" &&
            containsDisplay(node, original) && !NAMED_ALIGN.containsMatchIn(stripStrings(args))
        if (n4Fired) {
            args = args.trimEnd() + ", align = \"center\""
        }
        // N3：单子裸容器解包。「裸」按容器自身**原文** args 判定——子项改写注入的命名
        // 实参不算数（唯一子 Row 被 N1 补 weights 不遮蔽父容器解包，但 N4 刚给本节点
        // 补的 align 算：stat 列须保壳居中）；唯一子须是组件且非 Spacer：非组件调用
        // （@动作/Query）的回退拼接会剥掉 @ 前缀产出白名单外裸调用，Spacer 单子解包
        // 会让 N2 合并腾空的容器在二次 normalize 再跳一层（幂等反例）——两者保守不解包。
        if ("N3" !in DISABLED_RULES && node.name in UNWRAP_CONTAINERS &&
            node.children.size == 1 && !n4Fired
        ) {
            val only = node.children.single()
            val bareOwnArgs = !NAMED_ARG.containsMatchIn(
                stripStrings(original.substring(node.argsStart, node.argsEnd)),
            )
            if (only.name != "Spacer" && bareOwnArgs) {
                childText[only]?.let { unwrapped -> return unwrapped }
            }
        }
        return original.substring(node.spanStart, node.argsStart) + args + ")"
    }

    /** Spacer 单件改写：纵向流 xs→s / xl→l（保留原 size 形态）。 */
    private fun renderSpacer(node: OpenUiCallTree.CallNode, original: String, parentName: String?): String {
        var text = original.substring(node.spanStart, node.spanEnd)
        if ("N2" !in DISABLED_RULES && parentName in VERTICAL_FLOW_PARENTS) {
            text = text.replace("\"xs\"", "\"s\"").replace("\"xl\"", "\"l\"")
        }
        return text
    }

    private fun needsWeights(
        node: OpenUiCallTree.CallNode,
        comps: List<OpenUiCallTree.CallNode>,
        original: String,
    ): Boolean =
        comps.size == 2 &&
            comps.count { child -> child.name == "Image" } == 1 &&
            comps.none { child -> child.name == "Spacer" || child.name == "Divider" } &&
            !NAMED_WEIGHTS.containsMatchIn(stripStrings(original.substring(node.argsStart, node.argsEnd)))

    private fun containsDisplay(node: OpenUiCallTree.CallNode, original: String): Boolean =
        original.substring(node.spanStart, node.spanEnd).contains("\"DISPLAY\"")

    private fun stripStrings(text: String): String =
        text.replace(Regex(""""([^"\\]|\\.)*""""), "\"\"")

    private fun spacerSize(node: OpenUiCallTree.CallNode, original: String): Int {
        val text = original.substring(node.spanStart, node.spanEnd)
        val quoted = QUOTED_SIZE.find(text)?.value?.removeSurrounding("\"")
        return SPACER_RANK[quoted] ?: 2 // 缺省 m
    }

    private fun rankToSize(rank: Int): String = SPACER_RANK.entries.first { entry -> entry.value == rank }.key
}
