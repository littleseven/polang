# OpenUI 布局可控性批次实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留 LLM 自由组合的前提下，用「server 无声矫正 + 结构门禁退回重试」杜绝 OpenUI 奇怪组合；Ardot 设计四场景版式范式 + 落地三新件（CompareRow/Timeline/ChartGrid，97→100）。

**Architecture:** 三层防御——① `OpenUiLayoutNormalizer`（无声矫正 N1~N4，机械性几何问题直接改写 program）→ ② `OpenUiLayoutValidator`（结构门禁 R1~R4，违规清单经 Observation 回灌同轮重试）→ ③ 端上渲染干净 program（渲染器零机制改动）。规则引擎全部落 **server**（spec §1 决策 1 的核实结论：`server/` 是独立 Gradle 构建、不依赖 `:shared`，且规则的唯一消费方是 server——遵循 `RenderOpenUiValidator` 字面量副本先例，**不做 shared 副本**，端侧零改动即无同步负担）。矫正经 `AgentRunner` 新增 `argsTransformer` 钩子改写 Action 事件 argsJson；门禁扩展进既有 `RenderOpenUiValidator.validateArgsJson`（actionGate 路径）与工具体。

**Tech Stack:** Kotlin（server Ktor 独立构建 `./gradlew -p server`；shared KMP；androidApp Compose）、Ardot MCP（设计稿）、JUnit。

**上游 spec:** `docs/superpowers/specs/2026-10-31-openui-layout-controllability-design.md`

**关键背景（执行前必读）：**

1. **发卡类改动双交付铁律**（server/AGENTS.md）：工具描述/校验器改动 LLM 读的是**线上 jar**——只发包不部署 = 线上 LLM 从未见过新约束（2026-10-30 三轮布局治理曾因此失效，这是「布局丑依旧」的直接原因之一）。本批次交付 = App 发包 + `./server/deploy.sh` 部署，缺一不可。
2. server 不纳入 Android `settings.gradle.kts`：`./gradlew -p server test` 独立跑测试。
3. OpenUI program 是文本 DSL（`名 = 表达式` 逐行语句，组件调用大写开头）。server 现有校验是**正则级**（`RenderOpenUiValidator`），本批新增结构级分析但**不跑完整 parser**（字符级括号扫描，见 Task 1）。
4. 组件注册「四处同源」：shared `OpenUiLibrary`（schema）↔ androidApp 渲染器集合 ↔ server `ALLOWED_COMPONENTS` ↔ 工具描述文本；另有测试钉住（`RenderOpenUiValidatorTest` 白名单断言）。新件四处都要改。
5. 测试命令基线：server = `./gradlew -p server test --tests "<FQCN>"`；shared = `./gradlew :shared:jvmTest`；androidApp 单测 = `./gradlew :androidApp:testDebugUnitTest`。

---

## Phase M1 — 规则引擎（server，TDD）

### Task 1: OpenUiCallTree 调用树扫描器

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiCallTree.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiCallTreeTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.server.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenUiCallTreeTest {

    private val program = """
        photos = Query("local_handle", { handle: "local://scan/f9d33db29ed6#photos" }, [])
        root = Card([Text("标题", "HEADLINE"),
            Row([Image(photos[0].id), Column([Text("海边日落"), Text("2026-07-18", "CAPTION")])],
                weights = [1, 2], align = "center")])
    """.trimIndent()

    @Test
    fun `parses nested component calls with original-text spans`() {
        val roots = OpenUiCallTree.parse(program)!!
        val names = roots.map { root -> root.name }
        assertEquals(listOf("Query", "Card"), names)

        val card = roots.last()
        assertEquals("Card", card.name)
        // Card 直接子组件：Text + Row
        assertEquals(listOf("Text", "Row"), componentChildren(card).map { node -> node.name })
        // span 锚定原文：Card 调用整体区间首字符是 'C'
        assertEquals('C', program[card.spanStart])
        assertEquals(')', program[card.spanEnd - 1])
        // Row 子组件：Image + Column；Query/@动作调用不属组件子项
        val row = componentChildren(card).last()
        assertEquals(listOf("Image", "Column"), componentChildren(row).map { node -> node.name })
    }

    @Test
    fun `filters action and query calls out of component children`() {
        val actionProgram = "root = Card([Button(\"查看\", @OpenUrl(\"https://x.com\")), Text(\"hi\")])"
        val card = OpenUiCallTree.parse(actionProgram)!!.last()
        assertEquals(listOf("Button", "Text"), componentChildren(card).map { node -> node.name })
    }

    @Test
    fun `returns null on unbalanced program`() {
        assertNull(OpenUiCallTree.parse("root = Card([Text(\"截断\""))
        assertNull(OpenUiCallTree.parse("root = Card([Text(\"未闭串\")])"))
    }

    @Test
    fun `all golden programs parse`() {
        listOf(
            RenderOpenUiToolService.GOLDEN_STATS_PROGRAM,
            RenderOpenUiToolService.GOLDEN_MIXED_PROGRAM,
            RenderOpenUiToolService.GOLDEN_LAYOUT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_CLASSIC_PROGRAM,
            RenderOpenUiToolService.GOLDEN_PHOTO_PROGRAM,
        ).forEach { golden ->
            assertTrue(OpenUiCallTree.parse(golden) != null, golden)
        }
    }
}
```

同文件底部放测试辅助（production 代码也要用同一口径，抽到 `OpenUiCallTree` 伴生物内，测试引用之）：

```kotlin
/** 组件子项 = 名字在组件白名单内的直接子调用（剔除 Query 取数与 @动作调用）。 */
private fun componentChildren(node: OpenUiCallTree.CallNode): List<OpenUiCallTree.CallNode> =
    OpenUiCallTree.componentChildren(node)
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.OpenUiCallTreeTest"`
Expected: 编译失败 `unresolved reference: OpenUiCallTree`

- [ ] **Step 3: 实现 OpenUiCallTree**

```kotlin
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
        var index = 0
        while (index < program.length) {
            val c = program[index]
            when {
                inString -> {
                    if (c == '\\') index++ // 跳过转义字符
                    else if (c == '"') inString = false
                }
                c == '"' -> inString = true
                c == '/' && index + 1 < program.length && program[index + 1] == '/' || c == '#' -> {
                    val newline = program.indexOf('\n', index)
                    index = if (newline == -1) program.length else newline
                }
                c == '(' -> headBefore(program, index)?.let { head ->
                    stack.addLast(OpenCall(head, index - head.length, index + 1))
                }
                c == ')' -> {
                    val open = stack.removeLastOrNull() ?: return null
                    val node = CallNode(open.name, open.spanStart, index + 1, open.argsStart, index, open.children)
                    stack.lastOrNull()?.children?.add(node) ?: roots.add(node)
                }
                else -> Unit
            }
            index++
        }
        if (stack.isNotEmpty() || inString) return null
        return roots
    }

    /** 组件子项 = 名字在组件白名单内的直接子调用（剔除 Query 取数与 @动作调用）。 */
    fun componentChildren(node: CallNode): List<CallNode> =
        node.children.filter { child -> child.name in RenderOpenUiValidator.ALLOWED_COMPONENTS }

    /** `(` 前紧邻的大写标识符（名与括号间可空白；`@OpenUrl(` 记名 OpenUrl 由白名单过滤）。 */
    private fun headBefore(program: String, parenIndex: Int): String? {
        var end = parenIndex - 1
        while (end >= 0 && program[end].isWhitespace()) end--
        var start = end
        while (start >= 0 && (program[start].isLetterOrDigit() || program[start] == '_')) start--
        if (start == end || end < 0) return null
        val head = program.substring(start + 1, end + 1)
        return if (head[0].isUpperCase()) head else null
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.OpenUiCallTreeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiCallTree.kt \
  server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiCallTreeTest.kt
git commit -m "feat(server): OpenUiCallTree 调用树扫描器——布局规则的结构分析基座

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 2: OpenUiLayoutNormalizer（N1~N4 无声矫正）

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiLayoutNormalizer.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiLayoutNormalizerTest.kt`

- [ ] **Step 1: 写失败测试（逐规则用例 + 黄金程序不动点 + 幂等）**

```kotlin
package com.mamba.picme.server.agent

import org.junit.Test
import kotlin.test.assertEquals

class OpenUiLayoutNormalizerTest {

    // N1：图文 Row 缺 weights → 按图位补 [1,2] / [2,1]
    @Test
    fun `n1 adds weights to bare image-text row`() {
        val program = "root = Card([Row([Image(photos[0].id), Text(\"说明\")])])"
        val normalized = OpenUiLayoutNormalizer.normalize(program)
        assertEquals(
            "root = Card([Row([Image(photos[0].id), Text(\"说明\")], weights = [1, 2])])",
            normalized,
        )
    }

    @Test
    fun `n1 reversed order gets flipped weights`() {
        val program = "root = Card([Row([Text(\"说明\"), Image(photos[0].id)])])"
        assertEquals(
            "root = Card([Row([Text(\"说明\"), Image(photos[0].id)], weights = [2, 1])])",
            OpenUiLayoutNormalizer.normalize(program),
        )
    }

    @Test
    fun `n1 skips row with weights or pure image row`() {
        val hasWeights = "root = Card([Row([Image(photos[0].id), Text(\"a\")], weights = [1, 3])])"
        assertEquals(hasWeights, OpenUiLayoutNormalizer.normalize(hasWeights))
        val pureImages = "root = Card([Row([Image(photos[0].id), Image(photos[1].id)])])"
        assertEquals(pureImages, OpenUiLayoutNormalizer.normalize(pureImages))
    }

    // N3：单子裸容器解包（有命名实参不解包）
    @Test
    fun `n3 unwraps single-child bare container`() {
        val program = "root = Card([Column([Text(\"hi\")])])"
        assertEquals("root = Card([Text(\"hi\")])", OpenUiLayoutNormalizer.normalize(program))
    }

    @Test
    fun `n3 keeps container with named args`() {
        val program = "root = Card([Column([Text(\"hi\")], align = \"center\")])"
        assertEquals(program, OpenUiLayoutNormalizer.normalize(program))
    }

    // N4：Row 直接子级 Column 含 DISPLAY 未居中 → 补 align
    @Test
    fun `n4 centers stat column`() {
        val program = "root = Card([Row([Column([Text(\"7,120\", \"DISPLAY\")]), Column([Text(\"771\", \"DISPLAY\")], align = \"center\")])])"
        assertEquals(
            "root = Card([Row([Column([Text(\"7,120\", \"DISPLAY\")], align = \"center\"), " +
                "Column([Text(\"771\", \"DISPLAY\")], align = \"center\")])])",
            OpenUiLayoutNormalizer.normalize(program),
        )
    }

    // N2：连续 Spacer 合并取最大档；纵向流 xs/xl 降档
    @Test
    fun `n2 merges consecutive spacers keeping max size`() {
        val program = "root = Card([Text(\"a\"), Spacer(\"s\"), Spacer(\"l\"), Text(\"b\")])"
        assertEquals(
            "root = Card([Text(\"a\"), Spacer(\"l\"), Text(\"b\")])",
            OpenUiLayoutNormalizer.normalize(program),
        )
    }

    @Test
    fun `n2 downgrades xs and xl in vertical flow`() {
        val program = "root = Card([Text(\"a\"), Spacer(\"xs\"), Text(\"b\")])"
        assertEquals(
            "root = Card([Text(\"a\"), Spacer(\"s\"), Text(\"b\")])",
            OpenUiLayoutNormalizer.normalize(program),
        )
        val xl = "root = Card([Text(\"a\"), Spacer(\"xl\"), Text(\"b\")])"
        assertEquals(
            "root = Card([Text(\"a\"), Spacer(\"l\"), Text(\"b\")])",
            OpenUiLayoutNormalizer.normalize(xl),
        )
    }

    // 不动点 + 幂等
    @Test
    fun `golden programs pass through unchanged`() {
        listOf(
            RenderOpenUiToolService.GOLDEN_STATS_PROGRAM,
            RenderOpenUiToolService.GOLDEN_LAYOUT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_STAT_TILE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_CLASSIC_PROGRAM,
        ).forEach { golden ->
            assertEquals(golden, OpenUiLayoutNormalizer.normalize(golden), golden)
        }
    }

    @Test
    fun `normalize is idempotent and safe on garbage`() {
        val program = "root = Card([Row([Image(photos[0].id), Text(\"x\")]), Column([Text(\"y\")])])"
        val once = OpenUiLayoutNormalizer.normalize(program)
        assertEquals(once, OpenUiLayoutNormalizer.normalize(once))
        assertEquals("截断", OpenUiLayoutNormalizer.normalize("截断"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.OpenUiLayoutNormalizerTest"`
Expected: 编译失败 `unresolved reference: OpenUiLayoutNormalizer`

- [ ] **Step 3: 实现 OpenUiLayoutNormalizer**

```kotlin
package com.mamba.picme.server.agent

/**
 * OpenUI 布局无声矫正器（2026-10-31 布局可控性批次，spec §2.1）——只做「有唯一
 * 正确解」的机械性改写，不改组件选型与内容（不替 LLM 做语义决策）：
 *
 * - N1 图文 Row 缺 weights：恰好两个组件子项其一为 Image → 按图位补 [1,2]/[2,1]
 *   （等宽分栏把缩略图拉成半宽栏的反模式根治）；
 * - N2 节奏归一：连续 Spacer ≥2 合并取最大档；Column/Card 纵向流内 xs/xl 降 s/l；
 * - N3 单子裸容器解包：Row/Column/Grid/Box/Stack 仅一个子项且无命名实参 → 解包
 *   （纯包裹层既加深嵌套又破坏节奏；带 align/spacing 等命名实参 = 有语义，保留）；
 * - N4 stat 拼装居中：Row 直接子级 Column 含 DISPLAY 大数字未 align="center" → 补。
 *
 * 自底向上重写（span 锚定原文，非调用区文本原样保留）；幂等：normalize(normalize(p))
 * == normalize(p)。扫描失败（残缺程序）原文返回，交协议校验/端侧兜底。
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

    /** 入口：无需矫正/扫描失败 → 原文返回。 */
    fun normalize(program: String): String {
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
                run.forEachIndexed { i, spacer ->
                    childText[spacer] = if (i == 0) "Spacer(\"${rankToSize(maxSize)}\")" else ""
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

        // N3：单子裸容器解包（子项总数含 @/Query 调用恰为 1，且无命名实参）
        if ("N3" !in DISABLED_RULES && node.name in UNWRAP_CONTAINERS &&
            node.children.size == 1 && noNamedArgs(node, original)
        ) {
            return childText.values.single()
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
        // N1 / N4：本节点命名实参追加（原文无该键才补）
        if ("N1" !in DISABLED_RULES && node.name == "Row" && needsWeights(node, comps, original)) {
            val imageFirst = comps.first().name == "Image"
            val weights = if (imageFirst) "[1, 2]" else "[2, 1]"
            args = args.trimEnd() + ", weights = " + weights
        }
        if ("N4" !in DISABLED_RULES && node.name == "Column" && parentName == "Row" &&
            containsDisplay(node, original) && !NAMED_ALIGN.containsMatchIn(stripStrings(args))
        ) {
            args = args.trimEnd() + ", align = \"center\""
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

    private fun noNamedArgs(node: OpenUiCallTree.CallNode, original: String): Boolean =
        !NAMED_ARG.containsMatchIn(stripStrings(original.substring(node.argsStart, node.argsEnd)))

    private fun stripStrings(text: String): String =
        text.replace(Regex(""""([^"\\]|\\.)*""""), "\"\"")

    private fun spacerSize(node: OpenUiCallTree.CallNode, original: String): Int {
        val text = original.substring(node.spanStart, node.spanEnd)
        val quoted = QUOTED_SIZE.find(text)?.value?.removeSurrounding("\"")
        return SPACER_RANK[quoted] ?: 2 // 缺省 m
    }

    private fun rankToSize(rank: Int): String = SPACER_RANK.entries.first { entry -> entry.value == rank }.key
}
```

> ⚠️ 实现注意：`renderNode` 里 N1 那行 `if (!args.trimEnd().endsWith("]") || true)` 是占位笔误——直接写 `args = args.trimEnd() + ", weights = " + weights`。测试若揭示追加逗号后多空格等问题，以测试期望为准修实现（测试是契约）。

> 注：N2 合并删除子节点时连带吃掉前导逗号（`renderNode` 内 replacement 为空串的分支）——否则数组里留空洞 `[, ]` 解析失败；测试 `n2 merges consecutive spacers keeping max size` 钉住该行为。

- [ ] **Step 4: 跑测试确认通过（红→绿循环内允许按测试微调实现）**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.OpenUiLayoutNormalizerTest"`
Expected: PASS（N1 期望串精确匹配；如空格差异按实际输出修断言或实现，保持「追加 `, weights = [1, 2]`」语义）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiLayoutNormalizer.kt \
  server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiLayoutNormalizerTest.kt
git commit -m "feat(server): OpenUiLayoutNormalizer 无声矫正——weights/Spacer/解包/居中四规则

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 3: OpenUiLayoutValidator（R1~R4 结构门禁 + 节点数护栏）

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiLayoutValidator.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt`（`ALLOWED_COMPONENTS` +3 名：`CompareRow`/`Timeline`/`ChartGrid`，批次注释「2026-10-31 布局可控性批次 97→100」——校验器依赖白名单识别新件子树，须与本任务同步）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt`（白名单钉住断言的 expected 集合 +3 名）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiLayoutValidatorTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.server.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenUiLayoutValidatorTest {

    @Test
    fun `all golden programs pass layout gate`() {
        listOf(
            RenderOpenUiToolService.GOLDEN_STATS_PROGRAM,
            RenderOpenUiToolService.GOLDEN_MIXED_PROGRAM,
            RenderOpenUiToolService.GOLDEN_INTERACTIVE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_REPORT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_STAT_TILE_PROGRAM,
            RenderOpenUiToolService.GOLDEN_LAYOUT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_PHOTO_TEXT_PROGRAM,
            RenderOpenUiToolService.GOLDEN_CLASSIC_PROGRAM,
            RenderOpenUiToolService.GOLDEN_PHOTO_PROGRAM,
        ).forEach { golden ->
            assertEquals(emptyList(), OpenUiLayoutValidator.validate(golden), golden)
        }
    }

    @Test
    fun `r1 rejects triple nested card`() {
        val program = "root = Card([Text(\"a\"), Card([Text(\"b\"), Card([Text(\"c\")])])])"
        val violations = OpenUiLayoutValidator.validate(program)
        assertTrue(violations.any { v -> v.contains("Card 嵌套") }, violations.toString())
    }

    @Test
    fun `r1 allows two-level card but rejects deep container chain`() {
        val twoLevel = "root = Card([Card([Text(\"ok\")])])"
        assertEquals(emptyList(), OpenUiLayoutValidator.validate(twoLevel))

        // 4 层容器链合法（≤MAX_CONTAINER_CHAIN），5 层违规
        val fourChain = "root = Card([Row([Column([Row([Column([Text(\"合法\")])])])])])"
        assertEquals(emptyList(), OpenUiLayoutValidator.validate(fourChain))

        val fiveChain = "root = Card([Row([Column([Row([Column([Row([Text(\"深\")])])])])])])"
        assertTrue(
            OpenUiLayoutValidator.validate(fiveChain).any { v -> v.contains("容器嵌套链") },
        )
    }

    @Test
    fun `r2 rejects duplicated signature pieces in one card`() {
        val program = "root = Card([Banner(\"a\"), Banner(\"b\")])"
        assertTrue(OpenUiLayoutValidator.validate(program).any { v -> v.contains("Banner") })
    }

    @Test
    fun `r3 rejects chart squeezed beside sibling`() {
        val program = "root = Card([Row([BarChart(\"t\", []), Text(\"侧栏\")])])"
        assertTrue(
            OpenUiLayoutValidator.validate(program).any { v -> v.contains("图表不可横向分栏") },
        )
    }

    @Test
    fun `r4 rejects grid of images`() {
        val program = "root = Card([Grid([Image(photos[0].id), Image(photos[1].id)], columns = 2)])"
        assertTrue(
            OpenUiLayoutValidator.validate(program).any { v -> v.contains("PhotoGrid") },
        )
    }

    @Test
    fun `chartgrid accepts charts only`() {
        val ok = "root = Card([ChartGrid([BarChart(\"a\", []), PieChart(\"b\", [])])])"
        assertEquals(emptyList(), OpenUiLayoutValidator.validate(ok))
        val bad = "root = Card([ChartGrid([BarChart(\"a\", []), Text(\"混入\")])])"
        assertTrue(OpenUiLayoutValidator.validate(bad).any { v -> v.contains("ChartGrid 只装图表") })
    }

    @Test
    fun `node count guard`() {
        val parts = (1..61).joinToString(", ") { i -> "Text(\"$i\")" }
        val program = "root = Card([$parts])"
        assertTrue(
            OpenUiLayoutValidator.validate(program).any { v -> v.contains("组件节点") },
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.OpenUiLayoutValidatorTest"`
Expected: 编译失败 `unresolved reference: OpenUiLayoutValidator`（ChartGrid 用例另需 Step 3 的白名单 +3）

- [ ] **Step 3: 实现校验器 + 白名单扩名**

`OpenUiLayoutValidator.kt`：

```kotlin
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
                    "容器（Row/Column/Grid/Box/Stack）嵌套链 ${containerChain + 1} 层超上限 $MAX_CONTAINER_CHAIN" +
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

    /** 门禁错误文本（对齐 [RenderOpenUiValidator] errorText 文风：逐条 + 回显开头）。 */
    fun errorText(violations: List<String>, program: String): String = buildString {
        append("error: render_ui_openui 布局门禁未通过，本次未下发端侧。逐条修正后重新调用 render_ui_openui：")
        violations.forEach { violation -> append("\n- ").append(violation) }
        append("\n本次收到 program 开头：")
        append(program.replace("\n", "\\n").take(120))
    }
}
```

`RenderOpenUiValidator.ALLOWED_COMPONENTS` 增补（放在 PhotoGrid/AvatarRow 批次注释块之后）：

```kotlin
        // 布局可控性批次（2026-10-31）：前后对比/时间轴固化件 + 双图并排图表网格
        // （R3 门禁的合法出口；渲染器与工具描述在 App/M4 批次落地）
        "CompareRow", "Timeline", "ChartGrid",
```

同步更新 `RenderOpenUiValidatorTest` 白名单钉住断言的 expected 集合（文件内 `assertEquals(expected, RenderOpenUiValidator.ALLOWED_COMPONENTS)` 处）加同名三项。

- [ ] **Step 4: 跑本任务测试 + 白名单钉住测试**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.OpenUiLayoutValidatorTest" --tests "com.mamba.picme.server.agent.RenderOpenUiValidatorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiLayoutValidator.kt \
  server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt \
  server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiLayoutValidatorTest.kt \
  server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt
git commit -m "feat(server): OpenUiLayoutValidator 结构门禁 R1-R4 + 白名单 97→100

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 4: lintComposition 收编（退役被矫正层吸收的软警告）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt`（`lintComposition`）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt`（相关 lint 断言删除）

- [ ] **Step 1: 改测试先行（删除三条退役 lint 的断言，保留 DISPLAY≤4/Button≤3 断言）**

`lintComposition` 保留规则：`DISPLAY 大数字 ≤4` 与 `Button ≤3` 两条（未进本批硬层）。删除以下三段软警告代码及其测试断言：

1. `stat tile 数字栏未居中`（`TEXT_DISPLAY_CALL + ROW_CALL && !ALIGN_CENTER` 分支）→ 已由矫正 N4 无声根治；
2. `Row 内等宽裸拼 Image + Text`（`callArgSpans(stripped, ROW_CALL)` 内 IMAGE_ANY/TEXT_ANY/!NAMED_WEIGHTS 分支）→ 已由矫正 N1 吸收；
3. `Grid 内平铺 Image ≥2 张`（`callArgSpans(stripped, GRID_CALL)` 分支）→ 已升硬为门禁 R4。

`lintComposition` 收编后的 KDoc 注释补一行：

```kotlin
 * （2026-10-31 布局可控性批次收编：stat 居中/图文 Row weights/Grid 照片墙三条软警告
 * 退役——前两条由 OpenUiLayoutNormalizer 无声矫正吸收，第三条升硬为
 * OpenUiLayoutValidator R4 门禁；本函数只保留 DISPLAY≤4 与 Button≤3 两条软建议。）
```

测试侧：`RenderOpenUiValidatorTest` 中对上述三段警告的断言（如 `lintComposition(GOLDEN_STAT_TILE_PROGRAM)` 相关、构造违规 program 断言警告文本的用例）整段删除；DISPLAY/Button 两条的断言保留不动。

- [ ] **Step 2: 删除实现三段分支**

按 Step 1 清单删 `lintComposition` 内三个 `warnings +=` 分支（连同不再使用的私有正则 `ROW_CALL`/`ALIGN_CENTER`/`IMAGE_ANY_CALL`/`TEXT_ANY_CALL`/`NAMED_WEIGHTS`/`GRID_CALL`——先 grep 确认无其他引用再删；`callArgSpans` 仍被保留分支使用则留）。

- [ ] **Step 3: 跑测试**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.RenderOpenUiValidatorTest"`
Expected: PASS（黄金程序 lint 断言仅剩 DISPLAY/Button 两条口径）

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt \
  server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt
git commit -m "refactor(server): lint 收编——stat 居中/图文 weights 转 N1/N4 矫正，Grid 照片墙升硬 R4

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase M2 — Ardot 设计批次（四场景版式范式 + 三新件）

> ⚠️ **本阶段是交互检查点任务**：画布创作经 ardot MCP 工具进行，帧定稿需**用户在画布上评审**（画布轮播选型惯例）。执行者每完成一帧即请用户过目，别攒到最后。规范依据 `/ardot-design-ops` skill（token 绑定/快照导出/语言台账规程）。

### Task 5: OpenUILayouts 画布页——4 范式帧 + 3 新件帧

**Deliverables（Ardot 文件 = polang-ui-spec，fileId `715061534788814`）：**
- 新建画布页 `OpenUILayouts`
- 7 个帧：`pattern-stats-report` / `pattern-album-groups` / `pattern-compare` / `pattern-timeline` / `comp-comparerow` / `comp-timeline` / `comp-chartgrid`

- [ ] **Step 1: 建页与帧骨架**

经 ardot MCP `create_new_page` 建 `OpenUILayouts` 页；每帧宽 360（聊天卡内容宽）、高随内容 hug。帧内元素全部绑定既有 token 变量（色/字阶/间距走 variables，禁裸值——token 门禁会查）。

- [ ] **Step 2: 画 4 个场景范式帧（正例整卡 = 场景唯一正确版式）**

| 帧 | 区块顺序（自上而下） | 关键规范 |
|----|---------------------|----------|
| `pattern-stats-report` | Banner 题图 → StatRow（3 项）→ ChartGrid（BarChart+PieChart 双图）→ Callout 结论 | 统计报告签名骨架；区块间距 Spacing.md/lg 阶梯；HEADLINE 一卡一个 |
| `pattern-album-groups` | CardHeader(标题+副题+「查看全部」actionLabel) → PhotoGrid(3 列) → CardHeader → AvatarRow | 分组节奏：组间 lg、组内 md；AvatarRow 56dp 圆头像+名字居下 |
| `pattern-compare` | Text(HEADLINE) → CompareRow（左右等宽统一帧双图+前后标签）→ Text(CAPTION 说明) | 双图同帧（同宽同比 例，等比缩放裁剪语义）；图下标签 CAPTION 弱色居中 |
| `pattern-timeline` | Text(HEADLINE) → Timeline（轴线+圆点+内容行×3：48dp 缩略图+标题+副题+时间） | 轴线 outlineVariant 2dp、圆点 primary 8dp；行距 Spacing.sm |

内容示例数据用相册场景中文文案（年度报告/2026年7月分组/背景替换前后/海边之旅）。

- [ ] **Step 3: 画 3 个新件帧（组件规格帧，供渲染实现对照）**

| 帧 | 内容 |
|----|------|
| `comp-comparerow` | CompareRow 单件两态：有标签态（双图+图下「替换前/替换后」）/ 无标签态；标注统一帧比例决策（aspect > 图真实比例 > 4:3）|
| `comp-timeline` | Timeline 单件：3 节点（含 photo 前件/无 photo 纯文字两形态），末节点无连接线 |
| `comp-chartgrid` | ChartGrid 单件：双图并排（BarChart+LineChart）+ 单图形态（末格空位） |

- [ ] **Step 4: 用户画布评审（检查点）**

请用户在 Ardot 画布逐帧过目定稿；改动意见现场迭代。R1 深度等规则数值如需随帧调整，回改 `OpenUiLayoutValidator` 常量并同步 spec §2.2 标注。

- [ ] **Step 5: Light 模式切换验证**

按全域可切体系逐帧切 Light 模式检查（apply_variables 静默变白坑在册——变量绑定必须走绑定而非写死色值）。

### Task 6: 快照入库 + 设计语言文档 v9 + 目录登记

**Files:**
- Modify: `docs/08-UI-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md`
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`（data_ui_openui 节追加批次行）
- 快照 refs: `docs/08-UI-SPECS/screens/refs/ardot/`

- [ ] **Step 1: 导出快照**

Run: `python3 scripts/export-ardot-snapshot.py`（导 OpenUILayouts 页七帧；Dark 出图）
然后核对残留：`ls docs/08-UI-SPECS/screens/refs/ardot/ | grep openui`（快照不清理旧帧——改名/删除的旧 PNG 要 `git rm`）。

- [ ] **Step 2: 设计语言文档升 v9**

`OPENUI_CARD_DESIGN_LANGUAGE.md` 三处：

① §0 三条架构原则后补第四条：

```markdown
4. **组合收敛在规则层**（2026-10-31）：LLM 自由组合，组合合法性由 server 确定性规则保证
   （无声矫正 OpenUiLayoutNormalizer + 结构门禁 OpenUiLayoutValidator，spec
   《2026-10-31-openui-layout-controllability》）；「不奇怪」是规则保证的下限，
   「最优」仍靠版式范式菜单软引导。
```

② §4 新增 §4.7 版式范式（引用七帧 refs 图 + 四场景骨架表——内容同 Task 5 Step 2 表格，帧图路径 `../assets/chat-cards/openui-pattern-*.png` 按 Step 1 实际导出名）。

③ §5 速查表补三行（PhotoGrid 行后）：

```markdown
| CompareRow | 前后对比固化件：左右等宽双图统一帧（显式 aspect > 图真实比例 > 4:3，双侧同帧）+ 等比缩放 Crop + 人脸聚焦 + 图下标签；点击 OpenPhoto |
| Timeline | 时间轴固化件：轴线 + 节点圆点 + 内容行（photo 48dp Crop 前件 + 标题/副题/时间 CAPTION） |
| ChartGrid | 双图并排图表网格（columns 恒 2）：图表禁横向分栏门禁的合法出口 |
```

④ §6 变更记录补 v9 条目（概述：布局可控性批次——矫正+门禁混合约束、三新件 97→100、四场景版式范式、Ardot OpenUILayouts 页建立）。

- [ ] **Step 3: CHAT_CARD_CATALOG 登记**

`CHAT_CARD_CATALOG.md` data_ui_openui 节（相册 APP 组合方式批次段之后）追加：

```markdown
  布局可控性批次（2026-10-31，spec《2026-10-31-openui-layout-controllability》，
  Ardot OpenUILayouts 页四范式帧 + 三新件帧）：
  保留 LLM 自由组合，杜绝奇怪组合改走确定性规则层——server 无声矫正
  （OpenUiLayoutNormalizer：图文 Row 自动补 weights/连续 Spacer 合并/单子裸容器
  解包/stat 数字栏自动居中）+ 结构门禁（OpenUiLayoutValidator：Card 嵌套≤2/容器链≤4/
  通栏签名件同层去重/图表禁横向分栏/Grid 照片墙升硬，违规 Observation 回灌同轮重试
  ×1）；矫正经 AgentRunner argsTransformer 改写 Action argsJson，端侧渲染器零改动。
  新增 CompareRow（前后对比统一帧）/Timeline（时间轴）/ChartGrid（双图并排，R3 合法
  出口），组件总数 97→100。lint 三条软警告退役（收编进矫正/门禁）。
```

- [ ] **Step 4: Commit**

```bash
git add docs/08-UI-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md \
  docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md \
  docs/08-UI-SPECS/screens/refs/ardot/
git commit -m "docs(ui): OpenUI 布局可控性批次设计登记——OpenUILayouts 七帧 + 设计语言 v9 + 目录同步

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase M3 — 三新件落地（shared schema + Android 渲染器）

### Task 7: shared OpenUiLibrary schema +3（测试先行）

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibrary.kt`（AvatarRow 条目之后、`.associateBy` 之前）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibraryLayoutBatchTest.kt`

- [ ] **Step 1: 基线核对**

Run: `grep -c "OpenUiComponent(" shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibrary.kt`
Expected: 与文档口径 97 一致（如有出入，以实际数为 N，测试断言总数写 N+3）。

- [ ] **Step 2: 写失败测试**

```kotlin
package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenUiLibraryLayoutBatchTest {

    @Test
    fun `layout batch components registered with contracts`() {
        val registry = OpenUiLibrary.components

        val compare = registry.getValue("CompareRow")
        assertEquals(
            listOf("beforePhoto", "afterPhoto", "beforeLabel", "afterLabel", "beforeSrc", "afterSrc", "aspect"),
            compare.props.map { prop -> prop.name },
        )
        assertTrue(compare.props.first { prop -> prop.name == "beforePhoto" }.required)
        assertTrue(compare.props.first { prop -> prop.name == "afterPhoto" }.required)

        val timeline = registry.getValue("Timeline")
        val items = timeline.props.single()
        assertTrue(items.required)
        assertEquals(OpenUiPropType.OBJECT_ARRAY, items.type)
        assertEquals(
            listOf("title", "subtitle", "time", "photo"),
            items.objectFields?.map { field -> field.name },
        )

        val chartGrid = registry.getValue("ChartGrid")
        val children = chartGrid.props.single()
        assertTrue(children.required)
        assertEquals(OpenUiPropType.COMPONENT_LIST, children.type)
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./gradlew :shared:jvmTest --tests "com.mamba.picme.domain.chat.openui.OpenUiLibraryLayoutBatchTest"`
Expected: 编译失败或 `getValue` 抛 NoSuchElementException

- [ ] **Step 4: 加 schema 三条**

```kotlin
        // ── 布局可控性批次（2026-10-31，spec《2026-10-31-openui-layout-controllability》）──
        // CompareRow：前后对比固化件——左右等宽双图统一帧（显式 aspect > 图真实比例 > 4:3，
        // 双侧同帧：异比例各自成帧会高低错位破坏对比语义）+ 等比缩放 Crop + 人脸聚焦 +
        // 图下标签；photo 相册投影优先 / src https 兜底（Banner 双源同款约定）
        OpenUiComponent(
            "CompareRow",
            listOf(
                OpenUiProp("beforePhoto", OpenUiPropType.NUMBER, required = true),
                OpenUiProp("afterPhoto", OpenUiPropType.NUMBER, required = true),
                OpenUiProp("beforeLabel", OpenUiPropType.STRING),
                OpenUiProp("afterLabel", OpenUiPropType.STRING),
                OpenUiProp("beforeSrc", OpenUiPropType.STRING),
                OpenUiProp("afterSrc", OpenUiPropType.STRING),
                OpenUiProp("aspect", OpenUiPropType.NUMBER),
            ),
        ),
        // Timeline：时间轴固化件——轴线 + 节点圆点 + 内容行（photo 48dp 定尺寸缩略图
        // Crop，ListItem 同径）；items [{title*, subtitle?, time?, photo?}]
        OpenUiComponent(
            "Timeline",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("title", OpenUiPropType.STRING, required = true),
                        OpenUiProp("subtitle", OpenUiPropType.STRING),
                        OpenUiProp("time", OpenUiPropType.STRING),
                        OpenUiProp("photo", OpenUiPropType.NUMBER),
                    ),
                ),
            ),
        ),
        // ChartGrid：双图并排图表网格（columns 恒 2）——图表禁横向分栏门禁（R3）的
        // 合法出口；children 限图表族（server 门禁同条拦截）
        OpenUiComponent(
            "ChartGrid",
            listOf(
                OpenUiProp(
                    "children",
                    OpenUiPropType.COMPONENT_LIST,
                    required = true,
                ),
            ),
        ),
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :shared:jvmTest --tests "com.mamba.picme.domain.chat.openui.OpenUiLibraryLayoutBatchTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibrary.kt \
  shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibraryLayoutBatchTest.kt
git commit -m "feat(shared): OpenUI 布局可控性批次 schema——CompareRow/Timeline/ChartGrid

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

> **渲染器任务说明**：Compose 渲染无单测基建（OpenUI 线惯例：真机/画廊冒烟验证 + 纯函数单测），TDD 不适用于本三任务的 UI 部分；视觉正确性经 Task 11 调试画廊（`/openui` DEBUG 入口）目检。import 缺失按编译提示补齐（`TextAlign`/`BoxWithConstraints`/`Dp`/`height`/`IntrinsicSize` 等，同文件既有惯例）。三件渲染全部照 `OpenUiContentPhotoGrid`/`OpenUiContentAvatarRow` 现行模式写（props 解析 → photoResolver 批量解析 → 占位兜底 → OpenPhoto 点击）。

### Task 8: CompareRow 渲染器（前后对比）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt`

- [ ] **Step 1: 注册组件名与 dispatch**

`openUiContentComponentNames` 集合 `"PhotoGrid", "AvatarRow",` 行后加：

```kotlin
    // 布局可控性批次（2026-10-31）：CompareRow/Timeline/ChartGrid 新增
    "CompareRow", "Timeline", "ChartGrid",
```

`OpenUiContentNode` when 里 `"AvatarRow" -> …` 分支后加：

```kotlin
        "CompareRow" -> OpenUiContentCompareRow(node, photoResolver, onAction, modifier)
        "Timeline" -> OpenUiContentTimeline(node, photoResolver, onAction, modifier)
        "ChartGrid" -> OpenUiContentChartGrid(node, state, photoResolver, onAction, modifier)
```

- [ ] **Step 2: 实现（文件末尾 PhotoGrid/AvatarRow 实现之后追加）**

```kotlin
// ── 布局可控性批次（2026-10-31，spec《2026-10-31-openui-layout-controllability》）──
// CompareRow = 前后对比固化件（修图/优化效果）；Timeline = 时间轴固化件（回忆/事件流）；
// ChartGrid = 双图并排图表网格（图表禁横向分栏门禁 R3 的合法出口）。

/** CompareRow 统一帧缺省 4:3（设计语言 §4.5：均匀裁切即对比语义，同 PhotoGrid 例外口径）。 */
private const val COMPARE_ROW_FALLBACK_ASPECT = 4.0 / 3.0

/**
 * CompareRow：左右等宽双图统一帧（显式 aspect > before 图真实比例 > after 图真实比例
 * > 4:3——双侧同帧，异比例各自成帧会高低错位破坏对比语义）+ 等比缩放填满 + Crop 裁溢出
 * + 人脸聚焦 + 图下标签；photo 投影优先 / src https 兜底（Banner 双源同款）；
 * 点击 OpenPhoto 大图（Image 同口径）；解析中/失败侧落同形占位，不炸整卡。
 */
@Composable
private fun OpenUiContentCompareRow(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val beforeId = (node.props["beforePhoto"] as? OpenUiValue.Num)?.v?.toLong()
    val afterId = (node.props["afterPhoto"] as? OpenUiValue.Num)?.v?.toLong()
    val ids = listOfNotNull(beforeId, afterId)
    val resolved by produceState<Map<Long, OpenUiPhoto>>(emptyMap(), ids) {
        value = ids.mapNotNull { id -> photoResolver.resolve(id)?.let { photo -> id to photo } }.toMap()
    }
    val frameAspect = frameAspectOf(
        (node.props["aspect"] as? OpenUiValue.Num)?.v,
        resolved[beforeId]?.aspectRatio ?: resolved[afterId]?.aspectRatio,
        COMPARE_ROW_FALLBACK_ASPECT,
    ).toFloat()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OpenUiCompareSide(
            photo = beforeId?.let { id -> resolved[id] },
            src = (node.props["beforeSrc"] as? OpenUiValue.Str)?.v,
            label = (node.props["beforeLabel"] as? OpenUiValue.Str)?.v,
            frameAspect = frameAspect,
            onAction = onAction,
            modifier = Modifier.weight(1f),
        )
        OpenUiCompareSide(
            photo = afterId?.let { id -> resolved[id] },
            src = (node.props["afterSrc"] as? OpenUiValue.Str)?.v,
            label = (node.props["afterLabel"] as? OpenUiValue.Str)?.v,
            frameAspect = frameAspect,
            onAction = onAction,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OpenUiCompareSide(
    photo: OpenUiPhoto?,
    src: String?,
    label: String?,
    frameAspect: Float,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val frame = Modifier
            .fillMaxWidth()
            .aspectRatio(frameAspect)
            .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
        when {
            photo != null -> AsyncImage(
                model = photo.uri,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                alignment = faceAwareVerticalAlignment(photo.faceFocusY),
                modifier = frame.clickable(
                    onClickLabel = stringResource(R.string.cd_image_preview),
                    role = Role.Image,
                ) {
                    onAction(OpenUiAction("OpenPhoto", listOf(OpenUiValue.Str(photo.uri))))
                },
            )
            src != null && src.startsWith("https://") -> AsyncImage(
                model = src,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = frame,
            )
            else -> Box(modifier = frame)
        }
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
            )
        }
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（import 缺失按报错补：`TextAlign`、`RoundedCornerShape`、`clip`、`background` 等按同文件既有 import 检查）

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt
git commit -m "feat(openui): CompareRow 前后对比渲染器——双图统一帧 + 等比缩放裁剪

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 9: Timeline 渲染器（时间轴）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt`

- [ ] **Step 1: 实现（CompareSide 之后追加；dispatch 已在 Task 8 注册）**

```kotlin
/** Timeline 轴列：节点圆点 8dp（primary）+ 连接线 2dp（outlineVariant）。 */
private val TIMELINE_DOT_SIZE = 8.dp
private val TIMELINE_AXIS_WIDTH = 2.dp

/**
 * Timeline：时间轴固化件——轴线 + 节点圆点 + 内容行（可选 photo 48dp 定尺寸缩略图
 * Crop，ListItem 同径 + 标题/副题/时间 CAPTION 弱色）；items 端侧解析
 * （[PRIVACY] uri 不出端），title 缺失丢行不炸卡；末节点不画连接线。
 */
@Composable
private fun OpenUiContentTimeline(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    data class TimelineEntry(
        val title: String,
        val subtitle: String?,
        val time: String?,
        val photoId: Long?,
    )

    val entries = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val title = (obj["title"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        TimelineEntry(
            title = title,
            subtitle = (obj["subtitle"] as? OpenUiValue.Str)?.v,
            time = (obj["time"] as? OpenUiValue.Str)?.v,
            photoId = (obj["photo"] as? OpenUiValue.Num)?.v?.toLong(),
        )
    }.take(MAX_CONTENT_ITEMS)
    if (entries.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val ids = entries.mapNotNull { entry -> entry.photoId }
    val resolved by produceState<Map<Long, OpenUiPhoto>>(emptyMap(), ids) {
        value = ids.mapNotNull { id -> photoResolver.resolve(id)?.let { photo -> id to photo } }.toMap()
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        entries.forEachIndexed { index, entry ->
            // IntrinsicSize.Min 让轴列连接线随内容行等高拉伸
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(TIMELINE_DOT_SIZE),
                ) {
                    Box(
                        modifier = Modifier
                            .size(TIMELINE_DOT_SIZE)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    if (index < entries.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(TIMELINE_AXIS_WIDTH)
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                entry.photoId?.let { id ->
                    val photo = resolved[id]
                    val thumb = Modifier
                        .size(CONTENT_LIST_IMAGE_SIZE)
                        .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                    if (photo == null) {
                        Box(modifier = thumb)
                    } else {
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = entry.title,
                            contentScale = ContentScale.Crop,
                            alignment = faceAwareVerticalAlignment(photo.faceFocusY),
                            modifier = thumb.clickable(
                                onClickLabel = stringResource(R.string.cd_image_preview),
                                role = Role.Image,
                            ) {
                                onAction(OpenUiAction("OpenPhoto", listOf(OpenUiValue.Str(photo.uri))))
                            },
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = entry.title, style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY))
                    if (!entry.subtitle.isNullOrBlank()) {
                        Text(
                            text = entry.subtitle,
                            style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!entry.time.isNullOrBlank()) {
                        Text(
                            text = entry.time,
                            style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`height`/`IntrinsicSize` import 按需补）

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt
git commit -m "feat(openui): Timeline 时间轴渲染器——轴线节点 + 48dp 缩略图前件

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 10: ChartGrid 渲染器（双图并排）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt`

- [ ] **Step 1: 实现（Timeline 之后追加）**

```kotlin
/** ChartGrid 子项护栏（每格一张图，2×2 封顶——双图并排为主形态）。 */
private const val CHART_GRID_MAX_CHILDREN = 4

/**
 * ChartGrid：双列图表网格（columns 恒 2）——Row/Grid 内图表横向并排被 R3 门禁禁止，
 * 本件是唯一合法出口；子项经 [OpenUiNodeRenderer] 全量 dispatch 递归渲染（图表族），
 * 末行缺格补空位保持列对齐（Grid 同口径）；无界宽上下文按内容自尺寸（Grid 同径）。
 */
@Composable
private fun OpenUiContentChartGrid(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty()
        .filterIsInstance<OpenUiValue.ComponentVal>()
        .take(CHART_GRID_MAX_CHILDREN)
    if (children.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val weightsUsable = maxWidth != Dp.Infinity
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            children.chunked(2).forEach { rowChildren ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    rowChildren.forEach { child ->
                        val cellModifier = if (weightsUsable) Modifier.weight(1f) else Modifier
                        OpenUiNodeRenderer(child, state, photoResolver, onAction, cellModifier)
                    }
                    if (rowChildren.size == 1 && weightsUsable) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
```

> 注：`OpenUiNodeRenderer` 是全量 dispatch 入口（`OpenUiNodeRenderer.kt` 顶层 @Composable，Tabs/Grid 递归同径）；跨文件 internal 可见性已满足（同为 `features.chat.openui` 包）。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`BoxWithConstraints`/`Dp` import 按需补）

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt
git commit -m "feat(openui): ChartGrid 双图并排图表网格——R3 门禁合法出口

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 11: 调试画廊 demo 三条 + 画廊目检

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/debug/openui/OpenUiComponentGallery.kt`（`demoGroups`）

- [ ] **Step 1: 加三条 demo（结构照抄 demoGroups 内 PhotoGrid 条目——同构 `OpenUiComponentDemo`；program 文本如下）**

```kotlin
// 布局可控性批次（2026-10-31）
OpenUiComponentDemo(
    name = "CompareRow",
    program = "root = Card([CompareRow(beforePhoto = 101, afterPhoto = 102, " +
        "beforeLabel = \"替换前\", afterLabel = \"替换后\")])",
),
OpenUiComponentDemo(
    name = "Timeline",
    program = "root = Card([Timeline([{title: \"三亚日落\", subtitle: \"海边散步\", time: \"2026-07-18\"}, " +
        "{title: \"山顶云海\", time: \"2026-07-19\"}, {title: \"返程\"}])])",
),
OpenUiComponentDemo(
    name = "ChartGrid",
    program = "root = Card([ChartGrid([BarChart(\"月新增\", [{label: \"7月\", count: 12}]), " +
        "PieChart(\"占比\", [{label: \"人像\", value: 4}])])])",
),
```

（demo 的 photo id 沿用画廊既有占位形态——画廊 photoResolver 恒 null，渲染占位框即预期形态。若 `OpenUiComponentDemo` 构造签名不同——如带分组/描述参数——以 PhotoGrid 条目实参形态为准照抄改写。）

- [ ] **Step 2: 构建安装 + 画廊目检**

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk
```

设备进 DEBUG 画廊页（`/openui` 入口）目检三新卡：CompareRow 双格同帧、Timeline 轴线贯通、ChartGrid 双图等宽；卡片上方无 ✗/⚠ 标注。

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/debug/openui/OpenUiComponentGallery.kt
git commit -m "feat(openui): 调试画廊补 CompareRow/Timeline/ChartGrid 三条 demo

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase M4 — server 接线（矫正落流 + 门禁闭环 + prompt 重组）

### Task 12: AgentRunner argsTransformer 钩子（矫正改写 Action argsJson）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/AgentRunner.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`（约 :210 `actionGate = RenderUiValidator::gate,` 处）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/AgentRunnerTest.kt`

- [ ] **Step 1: 写失败测试（AgentRunnerTest 末尾追加，脚手架照 `valid render_ui payload passes gate and emits action event` 用例同款）**

```kotlin
    /** 发射点矫正：render_ui_openui 的反模式 program 经 argsTransformer 归一化后落 Action。 */
    @Test
    fun `argsTransformer rewrites action argsJson for render_ui_openui`() = runBlocking {
        val session = newSession()
        val args = buildJsonObject {
            put("program", "root = Card([Row([Image(photos[0].id), Text(\"说明\")])])")
            put("fallbackSummary", "摘要")
        }.toString()
        val registry = ToolRegistry { tools(RenderOpenUiToolService().asToolsByClass()) }

        runnerWith(
            HttpStatusCode.OK to toolCallResponse("call_1", "render_ui_openui", jsonArgsEscape(args)),
            HttpStatusCode.OK to chatResponse("卡已发"),
            actionGate = RenderUiValidator::gate,
            argsTransformer = RenderOpenUiValidator::transformArgs,
        ).runTurn(session, "发张卡", gatewayTarget, "sys", registry)

        val events = AgentEventRepository.listBySession(session.id)
        val action = events.single { event -> event.type == AgentEventType.ACTION }
        val actionArgs = (action.payload as AgentEventPayload.Action).argsJson
        assertTrue("Action 应携带矫正后 program，实际：$actionArgs", actionArgs.contains("weights = [1, 2]"))
    }
```

同步扩展 `runnerWith` 测试辅助：增可选参 `argsTransformer: (String, String) -> String = { _, argsJson -> argsJson }` 透传给 `AgentRunner` 构造（照该辅助内 `actionGate` 可选参既有写法）。

- [ ] **Step 2: 实现钩子**

`AgentRunner.kt` 构造参数区（`actionGate` 参数下一行）加：

```kotlin
    /** 发射点矫正（2026-10-31 布局可控性批次）：Action 落流前的 argsJson 改写钩子
     *  （render_ui_openui 的 program 无声归一化）；缺省 identity。改写发生在
     *  [actionGate] 校验之前——门禁见到的是矫正后的 program。 */
    private val argsTransformer: (toolName: String, argsJson: String) -> String = { _, argsJson -> argsJson },
```

`onToolCallStarting` 内（`val argsJson = toolArgsWireJson(ctx.toolArgs)` 与门控行之间）：

```kotlin
                            val rawArgsJson = toolArgsWireJson(ctx.toolArgs)
                            // 发射点矫正：render_ui_openui program 先归一化再校验/落流
                            val argsJson = argsTransformer(ctx.toolName, rawArgsJson)
                            // 发射点门控：被拒负载不落 Action、不下行（见类 KDoc actionGate 段）
                            if (actionGate(ctx.toolName, argsJson) != null) return@runBlocking
```

（后续 `AgentEventPayload.Action(tool = ctx.toolName, argsJson = argsJson, …)` 引用名不变，落流即矫正后值。）

`Application.kt` `actionGate = RenderUiValidator::gate,` 下一行加：

```kotlin
        argsTransformer = RenderOpenUiValidator::transformArgs,
```

> `transformArgs` 本体在 Task 13 实现——Task 12/13 顺序执行（12 先写钩子与测试骨架会编译失败，可与 13 合并为一个红→绿循环；两任务合并 commit 亦可）。

- [ ] **Step 3: Commit（与 Task 13 合并 commit 见下）**

### Task 13: RenderOpenUiValidator 接线（transformArgs + 布局门禁进双消费点）+ 工具体改造

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiToolService.kt`（`renderUiOpenUi` 工具体）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt` + `RenderOpenUiToolServiceTest.kt`

- [ ] **Step 1: 写失败测试**

`RenderOpenUiValidatorTest` 追加：

```kotlin
@Test
fun `transformArgs normalizes program in argsJson`() {
    val argsJson = """{"program":"root = Card([Row([Image(photos[0].id), Text(\"说明\")])])","fallbackSummary":"摘要"}"""
    val transformed = RenderOpenUiValidator.transformArgs(RenderOpenUiValidator.TOOL_NAME, argsJson)
    assertTrue(transformed.contains("weights = [1, 2]"), transformed)
    // 幂等：再过一遍无变化
    assertEquals(transformed, RenderOpenUiValidator.transformArgs(RenderOpenUiValidator.TOOL_NAME, transformed))
}

@Test
fun `transformArgs passthrough for other tools and malformed json`() {
    assertEquals("x", RenderOpenUiValidator.transformArgs("other_tool", "x"))
    assertEquals("not json", RenderOpenUiValidator.transformArgs(RenderOpenUiValidator.TOOL_NAME, "not json"))
}

@Test
fun `validateArgsJson rejects layout violations`() {
    val argsJson = """{"program":"root = Card([Text(\"a\"), Card([Text(\"b\"), Card([Text(\"c\")])])])","fallbackSummary":"s"}"""
    val error = RenderOpenUiValidator.validateArgsJson(argsJson)
    assertNotNull(error)
    assertTrue(error!!.startsWith("error: render_ui_openui 布局门禁未通过"), error)
}
```

`RenderOpenUiToolServiceTest` 追加：

```kotlin
@Test
fun `tool body rejects layout violation and confirms normalized program`() {
    val service = RenderOpenUiToolService()
    val layoutBad = service.renderUiOpenUi(
        "root = Card([Text(\"a\"), Card([Text(\"b\"), Card([Text(\"c\")])])])",
        "摘要",
    )
    assertTrue(layoutBad.startsWith("error: render_ui_openui 布局门禁未通过"), layoutBad)

    val normalizable = service.renderUiOpenUi(
        "root = Card([Row([Image(photos[0].id), Text(\"说明\")])])",
        "摘要",
    )
    assertTrue(normalizable.startsWith("UI 已下发端侧渲染"), normalizable)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.RenderOpenUiValidatorTest" --tests "com.mamba.picme.server.agent.RenderOpenUiToolServiceTest" --tests "com.mamba.picme.server.agent.AgentRunnerTest"`
Expected: 编译失败（`transformArgs` 未定义）/ 断言失败

- [ ] **Step 3: 实现**

`RenderOpenUiValidator.kt` 新增两段：

```kotlin
    /**
     * 发射点矫正入口（[AgentRunner.argsTransformer] 消费，2026-10-31 布局可控性批次）：
     * render_ui_openui 的 program 经 [OpenUiLayoutNormalizer] 无声归一化后重组 argsJson；
     * 其余工具 / argsJson 非 JSON / 缺 program → 原样返回。幂等（gate 内二次调用无变化）。
     */
    fun transformArgs(toolName: String, argsJson: String): String {
        if (toolName != TOOL_NAME) return argsJson
        val args = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull() as? JsonObject
            ?: return argsJson
        val program = (args["program"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return argsJson
        val normalized = OpenUiLayoutNormalizer.normalize(program)
        if (normalized == program) return argsJson
        val rewritten = JsonObject(args.toMutableMap().apply { put("program", JsonPrimitive(normalized)) })
        return rewritten.toString()
    }
```

`validateArgsJson` 尾部（`return validateToError(program)` 改为）：

```kotlin
        validate(program).takeIf { violations -> violations.isNotEmpty() }
            ?.let { violations -> return errorText(violations, program) }
        // 布局门禁（2026-10-31）：对矫正后程序做结构校验（transformer 已归一化则此处幂等）
        val layoutViolations = OpenUiLayoutValidator.validate(OpenUiLayoutNormalizer.normalize(program))
        if (layoutViolations.isNotEmpty()) return OpenUiLayoutValidator.errorText(layoutViolations, program)
        return null
    }
```

`RenderOpenUiToolService.renderUiOpenUi` 工具体改为：

```kotlin
    ): String {
        // 与发射点门控同源校验（review 🟡4 对偶）：非法回 error 文本作 Observation
        // 回灌，LLM 同轮修正重试（同 RenderUiToolService 闭环口径）
        RenderOpenUiValidator.validateToError(program)?.let { error -> return error }
        // 布局门禁（2026-10-31 布局可控性批次）：矫正后程序过结构校验，违规同轮退回；
        // 机械性问题已被 normalize 无声修复，此处见到的都是结构反模式
        val normalized = OpenUiLayoutNormalizer.normalize(program)
        val layoutViolations = OpenUiLayoutValidator.validate(normalized)
        if (layoutViolations.isNotEmpty()) {
            return OpenUiLayoutValidator.errorText(layoutViolations, program)
        }
        // 构图软警告（DISPLAY≤4 / Button≤3）：卡照发，建议附在回执里供下次发卡改进
        val warnings = RenderOpenUiValidator.lintComposition(normalized)
        return buildString {
            append("UI 已下发端侧渲染（OpenUI）")
            if (warnings.isNotEmpty()) {
                append("。构图建议（本卡已下发、无需重发，下次发卡请改进）：")
                warnings.forEach { warning -> append("\n- ").append(warning) }
            }
        }
    }
```

> **重试上限语义勘误（spec §6 对齐）**：spec 所写「重试上限 1 次」在 A2UI 线的实际实现形态是 Koog agent loop 的 `maxIterations` 迭代预算（工具回 error → LLM 看 Observation 自行重调，无独立计数器；「上限 1 次」专指 CardClaimGuard 的纠正回环）。本批次沿用同款实际形态（AgentRunner 既有 `maxIterations` 兜底），**不新增独立重试计数器**——行为与 A2UI 线完全对齐，spec 表述按此口径理解。

- [ ] **Step 4: 跑三个测试类确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.RenderOpenUiValidatorTest" --tests "com.mamba.picme.server.agent.RenderOpenUiToolServiceTest" --tests "com.mamba.picme.server.agent.AgentRunnerTest"`
Expected: PASS

- [ ] **Step 5: Commit（含 Task 12 的钩子与接线）**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/AgentRunner.kt \
  server/src/main/kotlin/com/mamba/picme/server/Application.kt \
  server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt \
  server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiToolService.kt \
  server/src/test/kotlin/com/mamba/picme/server/agent/
git commit -m "feat(server): 布局矫正+门禁接线——argsTransformer 改写 Action、双消费点过 OpenUiLayoutValidator

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 14: 工具描述重组（版式菜单 + 新组件签名 + 新黄金）+ 计数同步

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiToolService.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt`（计数文案）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiToolServiceTest.kt`

- [ ] **Step 1: companion 增两个黄金示例常量（`GOLDEN_PHOTO_PROGRAM` 之后）**

```kotlin
        /** 前后对比黄金示例（2026-10-31 布局可控性批次）：CompareRow 统一双图帧。 */
        const val GOLDEN_COMPARE_PROGRAM =
            "photos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#photos\" }, [])\n" +
                "root = Card([Text(\"背景替换效果\", \"HEADLINE\"), " +
                "CompareRow(beforePhoto = photos[0].id, afterPhoto = photos[1].id, " +
                "beforeLabel = \"替换前\", afterLabel = \"替换后\"), " +
                "Text(\"已保留人物主体，背景换成海边黄昏\", \"CAPTION\")])"

        /** 时间轴黄金示例（2026-10-31 布局可控性批次）：回忆/事件流。 */
        const val GOLDEN_TIMELINE_PROGRAM =
            "photos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#photos\" }, [])\n" +
                "root = Card([Text(\"海边之旅\", \"HEADLINE\"), " +
                "Timeline([{title: \"三亚日落\", subtitle: \"海边散步两小时\", time: \"2026-07-18\", photo: photos[0].id}, " +
                "{title: \"山顶云海\", subtitle: \"清晨登山\", time: \"2026-07-19\", photo: photos[1].id}, " +
                "{title: \"返程\", time: \"2026-07-20\"}])])"
```

- [ ] **Step 2: @LLMDescription 三处插入**

① 组件签名段：相册组合件块（`CardHeader("2026年7月", subtitle = "128 张", actionLabel = "查看全部")）；` 行）之后插：

```kotlin
   - 布局可控性批次（2026-10-31）：CompareRow(beforePhoto*, afterPhoto*, beforeLabel?,
     afterLabel?, beforeSrc?, afterSrc?, aspect?)（前后对比：左右等宽双图统一帧 + 等比
     缩放裁剪 + 图下标签——修图/优化效果对比首选；photo 写 photos[0].id 投影、src 是
     https 公网图兜底，二选一 photo 优先）/ Timeline(items*=[{title*, subtitle?, time?,
     photo?}])（时间轴：轴线 + 节点 + 内容行，photo 48dp 缩略图——回忆/事件流首选）/
     ChartGrid(children*=[图表组件...])（双列图表网格——图表禁横向并排，双图并排唯一
     合法形态）；
```

② 约束 7（`7. fallbackSummary 写投影级摘要…降级展示；`）之后插约束行：

```kotlin
   7.5 布局结构硬约束（服务端校验，违规退回重写）：Card 嵌套 ≤2 层；容器
     （Row/Column/Grid/Box/Stack）嵌套链 ≤4；Banner/PhotoGrid/StatRow/AvatarRow 同一
     Card 直接子级各一件；图表禁止与其它组件横向并排（双图用 ChartGrid）；Grid 内 ≥2 张
     Image 必须换 PhotoGrid。机械性问题（图文 Row 缺 weights、stat 数字未居中、单子容器
     包裹、连续 Spacer）服务端会自动修正，无需手写修正；
```

③ 描述尾部（`""" + GOLDEN_PHOTO_PROGRAM` 改接续）：

```kotlin
""" + GOLDEN_PHOTO_PROGRAM + """
   版式范式菜单（2026-10-31 布局可控性批次；四场景标准骨架——结构照此组合）：
   - 统计报告（月度/年度回顾）→ Banner 题图 + StatRow 关键数字 + 图表（单图直排 / 双图
     ChartGrid）+ Callout 结论；
   - 相册分组浏览 → CardHeader(标题, subtitle = "128 张", actionLabel = "查看全部") +
     PhotoGrid，多分组拆多卡；
   - 前后对比（修图/优化效果）→ CompareRow(beforePhoto, afterPhoto, 前后标签) + 说明文字；
   - 时间轴（回忆/事件流）→ Timeline([{title, subtitle, time, photo}, ...])；
   - 叙事分工（说明/解释/知识类内容）→ Markdown 正文承载叙事（标题/列表/加粗自由写，
     排版节奏端侧定死——别堆多个 Text 组件写长文），关键数据/图表用组件嵌入：
     Card([Markdown("## 标题\n导语…"), StatRow([...]), Markdown("**结论**：…")])。
   前后对比黄金示例：
""" + GOLDEN_COMPARE_PROGRAM + """
   时间轴黄金示例：
""" + GOLDEN_TIMELINE_PROGRAM
```

- [ ] **Step 3: 计数文案同步**

`RenderOpenUiValidator`：`validate()` 白名单违规文案 `（95 组件全量清单见 render_ui_openui 工具描述…` 改 `100 组件`；`ALLOWED_COMPONENTS` KDoc 批次链末尾补 `2026-10-31 布局可控性批次 97→100（+CompareRow/Timeline/ChartGrid）。`

- [ ] **Step 4: 测试钉住新黄金**

`RenderOpenUiToolServiceTest` 追加：

```kotlin
@Test
fun `layout batch golden programs pass protocol and layout gates`() {
    listOf(
        RenderOpenUiToolService.GOLDEN_COMPARE_PROGRAM,
        RenderOpenUiToolService.GOLDEN_TIMELINE_PROGRAM,
    ).forEach { golden ->
        assertNull(RenderOpenUiValidator.validateToError(golden), golden)
        assertTrue(OpenUiLayoutValidator.validate(golden).isEmpty(), golden)
    }
}
```

- [ ] **Step 5: 跑测试 + Commit**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.RenderOpenUiToolServiceTest" --tests "com.mamba.picme.server.agent.RenderOpenUiValidatorTest"`
Expected: PASS

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiToolService.kt \
  server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt \
  server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiToolServiceTest.kt
git commit -m "feat(server): render_ui_openui 版式范式菜单 + CompareRow/Timeline/ChartGrid 签名与黄金示例

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase M5 — 验收与交付

### Task 15: 全量回归 + App 版本 bump

- [ ] **Step 1: 全量测试**

```bash
./gradlew -p server build          # server 编译 + 全部测试
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
./gradlew ktlintCheck detekt
```

Expected: 全绿（detekt 若报新文件阈值类问题按仓库既有豁免方式处理——注释或 config，不改规则）。

- [ ] **Step 2: App 版本 bump**

`androidApp/build.gradle.kts`：`versionCode = 10068` → `10069`；`versionName = "1.0.68"` → `"1.0.69"`。

- [ ] **Step 3: 构建安装**

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk
```

- [ ] **Step 4: Commit**

```bash
git add androidApp/build.gradle.kts
git commit -m "chore(app): bump 1.0.69（10069）——OpenUI 布局可控性批次

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 16: server 部署（用户确认）+ 真机四场景验收

> ⚠️ **deploy 是外部动作**：`./server/deploy.sh` 部署生产 HK——执行前**必须征得用户确认**。双交付铁律：只发包不部署 = 线上 LLM 永远见不到新约束（2026-10-30 事故）。

- [ ] **Step 1: server 版本 bump + 构建**

`server/build.gradle.kts`：`version = "0.9.6"` → `"0.9.7"`。
Run: `./gradlew -p server build`（含全部测试）

- [ ] **Step 2: 用户确认后部署**

```bash
./server/deploy.sh
```

- [ ] **Step 3: 真机四场景验收（与用户一起）**

chat 页实测四类 prompt，验收点：

| 场景 | 示例 prompt | 验收点 |
|------|------------|--------|
| 统计报告 | 「统计我今年的照片」 | 命中 Banner+StatRow+图表骨架；无双图横挤（或正确走 ChartGrid） |
| 相册分组 | 「看看 2026 年 7 月的照片」 | CardHeader+PhotoGrid 签名形态；无 Grid 照片墙 |
| 前后对比 | 修图/优化流程后追问对比 | CompareRow 双图同帧（视当轮是否有对比意图） |
| 门禁重试 | 构造诱导违规 prompt（如「把每张照片分别放在很深的嵌套卡片里」） | 观察日志：Action 被门禁拒 → LLM 同轮修正重发；流式预览最终让位正式帧 |

同时核对 `/openui` 画廊三新件 + 历史会话卡片不回归（旧 program 渲染不受影响）。

- [ ] **Step 4: 验收结论回写**

`CHAT_CARD_CATALOG.md` data_ui_openui 布局可控性批次段补验收态（真机四场景 ✅ + 日期）；spec 状态改「已实施」。

```bash
git add server/build.gradle.kts docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md \
  docs/superpowers/specs/2026-10-31-openui-layout-controllability-design.md
git commit -m "chore(server): bump 0.9.7 + 布局可控性批次验收登记

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 执行顺序与并行性

- **server 线**（Task 1→4 → 12→14）与 **设计/端侧线**（Task 5→11）无相互依赖，可并行；汇合点在 Task 15/16。
- Task 3 提前把三新件名加进 server 白名单（校验器依赖），shared schema/渲染器/描述文本在后续任务补齐——期间白名单「多三名」无副作用（LLM 在描述更新前不会写出这些名字，即便写出端侧空占位兜底）。
- Task 5（Ardot 画布）是**交互检查点**：需用户逐帧评审后才能进 Task 6 快照入库。
- iOS（三新件渲染跟随）不在本计划内，验收后走 `/ios-follow` 另行批次。
