package com.mamba.picme.server.agent

import org.slf4j.LoggerFactory

/**
 * OpenUI 布局无声矫正器（2026-10-31 布局可控性批次，spec §2.1）——只做「有唯一
 * 正确解」的机械性改写，不改组件选型与内容（不替 LLM 做语义决策）：
 * - N1 图文 Row 缺 weights：恰好两个组件子项其一为 Image → 按图位补 [1,2]/[2,1]；
 * - N2 节奏归一：连续 Spacer ≥2 合并取最大档；Column/Card 纵向流内 xs 降 s、xl/2xl 降 l；
 * - N3 单子裸容器解包：Row/Column/Grid/Box/Stack 仅一个子项且无命名实参 → 解包；
 * - N4 stat 拼装居中：Row 直接子级 Column 含 DISPLAY 大数字未 align="center" → 补。
 * - N5 DSML 尾标剥离（2026-11-01 故宫卡事故）：模型（deepseek-v4-flash）偶发把其
 *   DSML 工具调用收尾标记（`</｜｜DSML｜｜ parameter>` 等，全角｜）吐在程序尾部——
 *   turn-native「响应即程序」语境下无工具可收、标记纯属污染——尾部标记行整体剥除。
 * 自底向上重写（span 锚定原文，非调用区文本原样保留）；幂等。扫描失败/任何意外
 * 原文返回（runCatching 安全网：挂生产发射路径，宁可放过不可崩）。
 *
 * 近似边界（已知保守口径，设计如此）：N1/N4 的键检测扫节点 args 全区间（含子树
 * 原文），子树出现过 weights/align 即跳过追加（漏补无害）；N3 的「裸」同样扫全区间
 * 原文——唯一子自带命名实参的容器不解包（多一层壳无害），且裸判定只认命名实参，
 * 位置实参（如 spacing 枚举）随解包丢弃属规格内行为；行注释属死输入域——客户端
 * lexer 不支持注释，注释程序本就整卡致命降级、与矫正器无关（可忽略），其活变体
 * args 尾随逗号已由分离符归一吸收（见 appendNamedArg）。
 */
object OpenUiLayoutNormalizer {

    /** 规则开关（spec §2.3：误伤止血——把规则名（"N1"~"N5"）加进集合即关该条，不改代码）。 */
    internal val DISABLED_RULES: Set<String> = emptySet()

    private val logger = LoggerFactory.getLogger("picme-agent")

    private val UNWRAP_CONTAINERS = setOf("Row", "Column", "Grid", "Box", "Stack")
    private val VERTICAL_FLOW_PARENTS = setOf("Column", "Card")
    private val SPACER_RANK = mapOf("xs" to 0, "s" to 1, "m" to 2, "l" to 3, "xl" to 4, "2xl" to 5)

    /** 纵向流降档表（2026-11-01 间距词汇统一批次改为显式映射）：xs→s、xl/2xl→l。 */
    private val DOWNGRADE_VERTICAL = mapOf("xs" to "s", "xl" to "l", "2xl" to "l")
    private val NAMED_ARG = Regex("""[A-Za-z_][A-Za-z0-9_]*\s*=""")
    private val NAMED_WEIGHTS = Regex("""(?<![A-Za-z0-9_])weights\s*=""")
    private val NAMED_ALIGN = Regex("""(?<![A-Za-z0-9_])align\s*=""")
    private val QUOTED_SIZE = Regex(""""(2xl|xs|s|m|l|xl)"""")
    private val STRING_LITERAL = Regex(""""([^"\\]|\\.)*"""")

    /**
     * N5：模型响应尾部的 DSML 工具调用边界标记块（`</｜｜DSML｜｜ parameter>` /
     * `</｜｜DSML｜｜ invoke>` / `</｜｜DSML｜｜ calls>` 等，全角｜，兼容 ASCII | 变体）。
     * 尾锚定 + 标签体内不含换行——程序正文里的 DSML 字样（极端文案引用）不受影响。
     */
    private val DSML_TAIL_TAGS = Regex("""(?:\s*</?[｜|]{2}DSML[｜|]{2}[^>\n]*>)+\s*$""")

    /** 入口：无需矫正/扫描失败/内部异常 → 原文返回。幂等。 */
    fun normalize(program: String): String {
        // N5 前置剥离（try 之前）：DSML 尾标会让 parse/矫正器在垃圾文本上空转
        val stripped = if ("N5" in DISABLED_RULES) program else program.replace(DSML_TAIL_TAGS, "")
        return runCatching { normalizeInternal(stripped) }
            // warn 而非 debug：runCatching 只兜矫正器自身 bug（残缺程序走 parse null 不到此）——
            // 稀有 + 高信号；生产 INFO 级下 debug 会让安全网成黑洞（Task 12+13 质量 I-1）
            .onFailure { e ->
                logger.warn("normalize 静默放行：{} | program 开头：{}", e.toString(), program.take(120))
            }
            .getOrDefault(stripped)
    }

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
                // 合并产出的字面量同样过纵向流降档（xs→s / xl、2xl→l）——否则最大档 xl
                // 绕过降档子规则在纵向流残留，二次 normalize 才修正（幂等反例）
                val size = rankToSize(maxSize)
                val finalSize = if (node.name in VERTICAL_FLOW_PARENTS) {
                    DOWNGRADE_VERTICAL[size] ?: size
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
            args = appendNamedArg(args, "weights", weights)
        }
        val n4Fired = "N4" !in DISABLED_RULES && node.name == "Column" && parentName == "Row" &&
            containsDisplay(node, original) && !NAMED_ALIGN.containsMatchIn(stripStrings(args))
        if (n4Fired) {
            args = appendNamedArg(args, "align", "\"center\"")
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

    /** Spacer 单件改写：纵向流降档 xs→s / xl、2xl→l（保留原 size 形态）。 */
    private fun renderSpacer(node: OpenUiCallTree.CallNode, original: String, parentName: String?): String {
        var text = original.substring(node.spanStart, node.spanEnd)
        if ("N2" !in DISABLED_RULES && parentName in VERTICAL_FLOW_PARENTS) {
            val quoted = QUOTED_SIZE.find(text)?.value?.removeSurrounding("\"")
            val downgraded = quoted?.let { size -> DOWNGRADE_VERTICAL[size] }
            if (quoted != null && downgraded != null) {
                text = text.replace("\"$quoted\"", "\"$downgraded\"")
            }
        }
        return text
    }

    /**
     * 追加命名实参——分离符归一：args 呈尾随逗号形态（`...],)` / `..., )`）时复用既有
     * 逗号（评审 I-1：双逗号会让客户端 parsePrefix 撞 COMMA 抛异常，整卡致命降级）。
     */
    private fun appendNamedArg(args: String, key: String, rendered: String): String {
        val base = args.trimEnd()
        val sep = if (base.endsWith(",")) " " else ", "
        return base + sep + key + " = " + rendered
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
        text.replace(STRING_LITERAL, "\"\"")

    private fun spacerSize(node: OpenUiCallTree.CallNode, original: String): Int {
        val text = original.substring(node.spanStart, node.spanEnd)
        val quoted = QUOTED_SIZE.find(text)?.value?.removeSurrounding("\"")
        return SPACER_RANK[quoted] ?: 2 // 缺省 m
    }

    private fun rankToSize(rank: Int): String = SPACER_RANK.entries.first { entry -> entry.value == rank }.key
}
