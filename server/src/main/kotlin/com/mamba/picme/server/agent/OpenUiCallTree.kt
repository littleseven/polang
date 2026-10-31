package com.mamba.picme.server.agent

/**
 * OpenUI program 组件调用树扫描器（2026-10-31 布局可控性批次，spec
 * 《openui-layout-controllability》§1）。
 *
 * 字符级扫描（字符串字面量/行注释感知），不跑完整 parser（server 不依赖 :shared，
 * [RenderOpenUiValidator] 半信任口径先例）：每个 `Name(…)` 调用一个节点，span 锚定
 * **原文**区间（供 [OpenUiLayoutNormalizer] 无损改写）；children = 直接嵌套调用
 * （隔层数组/对象字面量不建节点，嵌套调用归属最近祖先调用）。
 *
 * 括号不配对/字符串未闭合（流式截断、残缺程序）→ 返回 null：调用方按「未扫描」
 * 处理——放行给既有协议校验与端侧兜底，不误杀（对齐 validator「误判优先避免」边界）。
 * 深度守卫对**所有** `(` 计数（裸括号/小写头调用同样入深，不建节点）：残缺程序不能
 * 借无名 `)` 虚假配平弹空调用栈，产出 span 虚胖/错挂顶层的错误树。
 */
object OpenUiCallTree {

    /** 组件调用节点。span = 调用整体原文区间 [spanStart, spanEnd)；args = 实参开区间。 */
    data class CallNode(
        val name: String,
        val spanStart: Int,
        val spanEnd: Int,
        val argsStart: Int,
        val argsEnd: Int,
        val children: List<CallNode>,
    )

    private class OpenCall(val name: String, val spanStart: Int, val argsStart: Int) {
        val children = mutableListOf<CallNode>()
    }

    /** 扫描失败 → null；成功 → 顶层调用列表（含 Query 取数语句等非组件调用）。 */
    fun parse(program: String): List<CallNode>? {
        val stack = ArrayDeque<OpenCall>()
        val roots = mutableListOf<CallNode>()
        var inString = false
        var depth = 0
        var index = 0
        while (index < program.length) {
            val c = program[index]
            when {
                inString -> {
                    if (c == '\\') index++ // 跳过转义字符
                    else if (c == '"') inString = false
                }
                c == '"' -> inString = true
                (c == '/' && index + 1 < program.length && program[index + 1] == '/') || (c == '#') -> {
                    val newline = program.indexOf('\n', index)
                    index = if (newline == -1) program.length else newline
                }
                c == '(' -> {
                    depth++
                    headBefore(program, index)?.let { (head, headStart) ->
                        stack.addLast(OpenCall(head, headStart, index + 1))
                    }
                }
                c == ')' -> {
                    depth--
                    if (depth < 0) return null
                    val open = stack.removeLastOrNull() ?: return null
                    val node = CallNode(open.name, open.spanStart, index + 1, open.argsStart, index, open.children)
                    stack.lastOrNull()?.children?.add(node) ?: roots.add(node)
                }
                else -> Unit
            }
            index++
        }
        if (stack.isNotEmpty() || inString || depth != 0) return null
        return roots
    }

    /** 组件子项 = 名字在组件白名单内的直接子调用（剔除 Query 取数与 @动作调用）。 */
    fun componentChildren(node: CallNode): List<CallNode> =
        node.children.filter { child -> child.name in RenderOpenUiValidator.ALLOWED_COMPONENTS }

    /**
     * `(` 前紧邻的大写标识符 → 名字 + 标识符**起始下标**（名与括号间可空白，span 锚定
     * 须用起始下标而非按长度倒推；`@OpenUrl(` 记名 OpenUrl 由白名单过滤）。
     */
    private fun headBefore(program: String, parenIndex: Int): Pair<String, Int>? {
        var end = parenIndex - 1
        while (end >= 0 && program[end].isWhitespace()) end--
        var start = end
        while (start >= 0 && (program[start].isLetterOrDigit() || program[start] == '_')) start--
        if (start == end || end < 0) return null
        val head = program.substring(start + 1, end + 1)
        return if (head[0].isUpperCase()) head to (start + 1) else null
    }
}
