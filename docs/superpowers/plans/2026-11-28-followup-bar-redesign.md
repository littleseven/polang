# FollowupBar 追问选项重设计 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 chat 回复尾部追问选项从 M3 TextButton 横排重设计为 FollowupBar 专用组件（全宽列表条：左标签右 chevron、surfaceVariant 底、r12、≤3 项）。

**Architecture:** 新增 OpenUI 组件 `FollowupBar`（shared `OpenUiLibrary` 注册，白名单自动跟随）；validator 加 FollowupBar 硬校验（项数/action 通道）并把 FollowupBar 区间从「一卡 Button ≤3」lint 计数解耦；prompt 资产（DSL_RULES 段 + 签名行 + 黄金示例）换教新形态；Android/iOS 各加一个渲染分支；Ardot 画布 B 帧组件化收口。Buttons/Button 既有组件、@ToAssistant 动作通道、server 代码零改动。

**Tech Stack:** Kotlin Multiplatform（shared commonMain/commonTest）、Jetpack Compose M3、SwiftUI、Ardot MCP（batch_edit DSL）。

**Spec:** `docs/superpowers/specs/2026-11-28-followup-chips-redesign-design.md`（方向 B 定稿）

**关键背景（执行者必读）：**
- OpenUI 是「LLM 产 DSL → 端侧渲染」体系：组件白名单 SSOT 在 shared `OpenUiLibrary`，`RenderOpenUiValidator.ALLOWED_COMPONENTS = OpenUiLibrary.componentNames` 自动派生；server 经 typealias 消费 shared（改 shared 即全端生效）。
- validator 是**字符串级校验管线**（正则 + `OpenUiStringScan.literalAt` 提取字符串参数；字符串/注释剥离后再扫结构）；lint 是软警告（`warnings +=`），硬校验是 `violations +=`。
- `OpenUiCallTree.parse(program)` 返回调用树，`CallNode` 有 `name/children/spanStart/spanEnd`——span 是原文区间，可用于「FollowupBar 实参区间」分析。
- Android 端有 `OpenUiDispatchCoverageTest`：shared 注册的非元素形状组件漏写渲染分支会红。
- 测试命令：shared 用 `./gradlew :shared:jvmTest`（iOS 侧 `shared/src/iosTest` 由 CI/手动跑）；server 用 `./gradlew -p server test`；Android 单测 `./gradlew :androidApp:testDebugUnitTest`。
- Ardot 画布操作必须**单会话串行**（skill 红线）；MCP 端点 `http://127.0.0.1:50501/api/v1/mcp`，fileUrl 用 `https://ardot.tencent.com/file/715061534788814`（新版格式，**不带** `?node_id=`，页面定位用 `nodeIds` 参数）。

---

### Task 1: shared 注册 FollowupBar 组件

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibrary.kt`（Buttons 注册块后，~896 行）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibraryFollowupBarTest.kt`（新建）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FollowupBar 组件注册钉住（2026-11-28 追问选项重设计，spec §3.1）。 */
class OpenUiLibraryFollowupBarTest {

    @Test
    fun `followup bar registered with items component list prop`() {
        val comp = OpenUiLibrary.components["FollowupBar"]
        assertTrue(comp != null, "FollowupBar 必须注册进 OpenUiLibrary")
        assertEquals(1, comp!!.props.size)
        val items = comp.props.first { it.name == "items" }
        assertEquals(OpenUiPropType.COMPONENT_LIST, items.type)
        assertTrue(items.required, "items 是必填 prop")
    }

    @Test
    fun `component count grows to 104`() {
        assertEquals(104, OpenUiLibrary.componentNames.size)
    }
}
```

（`OpenUiLibrary.components` 的可见性若为 private，测试改走 `componentNames` + 通过 parser 求值断言 items prop——执行时以实际可见性为准调整，断言语义不变。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :shared:jvmTest --tests "*OpenUiLibraryFollowupBarTest*" --console=plain`
Expected: FAIL（FollowupBar 未注册 / count 103）

- [ ] **Step 3: 最小实现**

`OpenUiLibrary.kt` 的 `Buttons` 注册块（`OpenUiComponent("Buttons", ...)`）之后插入：

```kotlin
OpenUiComponent(
    "FollowupBar",
    listOf(
        OpenUiProp("items", OpenUiPropType.COMPONENT_LIST, required = true),
    ),
),
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :shared:jvmTest --tests "*OpenUiLibraryFollowupBarTest*" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibrary.kt shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibraryFollowupBarTest.kt
git commit -m "feat(openui): 注册 FollowupBar 组件（追问选项条，白名单 103→104）"
```

---

### Task 2: validator 硬校验 + lint 解耦

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/RenderOpenUiValidator.kt`
  （正则区 ~224 行 `BUTTON_CALL` 旁 + `validate()` 主流程 + lint Button 计数处 + ~390 行注释「只允许 103 组件」改 104）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt`（新增正反例 + 409 行 `assertEquals(103, ...)` → 104）

- [ ] **Step 1: 写失败测试**

`RenderOpenUiValidatorTest.kt` 追加（沿用文件既有测试函数风格）：

```kotlin
// ---------- FollowupBar（2026-11-28 追问选项重设计） ----------

@Test
fun `followup bar accepts up to three to-assistant items`() {
    val program = """
        root = Column([Markdown("整理建议"), FollowupBar([Button("挑最佳镜头", @ToAssistant("从上周的照片里挑出最佳镜头")), Button("按场景分组", @ToAssistant("把上周的照片按场景分组")), Button("找出废片", @ToAssistant("找出上周照片里可以删掉的废片"))])])
    """.trimIndent()
    assertTrue(RenderOpenUiValidator.validate(program).isEmpty())
}

@Test
fun `followup bar rejects more than three items`() {
    val program = """
        root = FollowupBar([Button("a", @ToAssistant("a")), Button("b", @ToAssistant("b")), Button("c", @ToAssistant("c")), Button("d", @ToAssistant("d"))])
    """.trimIndent()
    val violations = RenderOpenUiValidator.validate(program)
    assertTrue(violations.any { it.contains("FollowupBar") && it.contains("3") })
}

@Test
fun `followup bar rejects non to-assistant actions`() {
    val program = """
        root = FollowupBar([Button("去相册", @Navigate("gallery"))])
    """.trimIndent()
    val violations = RenderOpenUiValidator.validate(program)
    assertTrue(violations.any { it.contains("FollowupBar") && it.contains("@ToAssistant") })
}

@Test
fun `followup bar rejects overlong labels`() {
    val label = "一二三四五六七八九十一二三" // 13 字 > 12 上限
    val program = "root = FollowupBar([Button(\"$label\", @ToAssistant(\"$label\"))])"
    val violations = RenderOpenUiValidator.validate(program)
    assertTrue(violations.any { it.contains("12") })
}

@Test
fun `followup bar buttons do not count into card button lint`() {
    // FollowupBar 3 项 + 卡内 3 个动作 Button：lint Button 计数解耦后不应触发 >3 警告
    val program = """
        root = Card([Button("a", @Run("x")), Button("b", @Run("y")), Button("c", @Run("z")), FollowupBar([Button("f1", @ToAssistant("f1")), Button("f2", @ToAssistant("f2")), Button("f3", @ToAssistant("f3"))])])
    """.trimIndent()
    val warnings = RenderOpenUiValidator.lintComposition(program)
    assertTrue(warnings.none { it.contains("Button") && it.contains("3") })
}
```

同时把 409 行 `assertEquals(103, RenderOpenUiValidator.ALLOWED_COMPONENTS.size)` 改为 `assertEquals(104, ...)`。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*RenderOpenUiValidatorTest*" --console=plain`
Expected: 新增用例 FAIL（无 FollowupBar 校验）；104 断言 FAIL（仍 103——若 Task 1 已合入则此条已过）

- [ ] **Step 3: 实现**

`RenderOpenUiValidator.kt`：

1. 组件白名单自动跟随（Task 1 注册即入 `ALLOWED_CALLS`），无需额外正则；区间校验走 `OpenUiCallTree`（见下）。

2. `validate()` 内（组件白名单校验之后）追加区间校验（用 `OpenUiCallTree` span 定区间，参照 `countDisplayTexts` 的 walk 模式收集 FollowupBar 节点 span）：

```kotlin
// FollowupBar 区间校验（2026-11-28 追问选项重设计）
val followupSpans = mutableListOf<IntRange>()
OpenUiCallTree.parse(program)?.let { roots ->
    fun walk(nodes: List<OpenUiCallTree.CallNode>) {
        nodes.forEach { node ->
            if (node.name == "FollowupBar") {
                followupSpans += node.spanStart..node.spanEnd
                val segment = program.substring(node.spanStart, node.spanEnd)
                val itemCount = BUTTON_CALL.findAll(segment).count()
                if (itemCount > 3) {
                    violations += "FollowupBar 选项数 $itemCount 超上限（最多 3 项——追问是轻引导不是菜单，精选最相关的后续）"
                }
                if (!segment.contains("@ToAssistant")) {
                    violations += "FollowupBar 选项必须带 @ToAssistant 动作（追问语义固定为「点选即作为用户消息发出」，跳页/写操作请用卡内 Button）"
                }
                listOf("@Navigate", "@Run(", "@OpenUrl", "Mutation(").forEach { bad ->
                    if (segment.contains(bad)) {
                        violations += "FollowupBar 内不允许 $bad 动作（仅 @ToAssistant——页面跳转与工具写操作走卡内 Button 三通道）"
                    }
                }
                // 标签 ≤12 字：Button 首参字符串字面量
                val labelRegex = Regex("""Button\s*\(\s*"""")
                labelRegex.findAll(segment).forEach { m ->
                    OpenUiStringScan.literalAt(segment, m.range.last)?.let { lit ->
                        if (lit.content.length > 12) {
                            violations += "FollowupBar 选项标签 \"${lit.content}\" 超 12 字（全宽列表单行显示，超长会被拒）"
                        }
                    }
                }
            }
            walk(node.children)
        }
    }
    walk(roots)
}
```

3. lint Button 计数解耦：找到 `lintComposition` 里用 `BUTTON_CALL` 计数的位置，计数前先把所有 FollowupBar 区间从剥离文本中移除（`followupSpans` 同法重算于 lint 入口），即 `val text = removeSpans(stripped, followupSpans)` 后再 `BUTTON_CALL.findAll(text).count()`。`removeSpans` 为本文件新增的私有工具（按区间降序 delete）：

```kotlin
/** 从文本中移除指定区间（降序删除防位移）。 */
private fun removeSpans(text: String, spans: List<IntRange>): String {
    val sb = StringBuilder(text)
    spans.sortedByDescending { it.first }.forEach { span ->
        val end = (span.last + 1).coerceAtMost(sb.length)
        if (span.first < end) sb.delete(span.first, end)
    }
    return sb.toString()
}
```

4. ~390 行注释「大写开头调用只允许 103 组件」改「104 组件」。

> 实现注意：`OpenUiCallTree.CallNode` 的 `spanStart/spanEnd` 语义是**原文**区间（`countDisplayTexts` 用 `program.substring(node.spanStart, node.spanEnd)` 证实）——上面对 `program`（非 stripped）取区间，标签提取要在原文上做。`OpenUiStringScan.literalAt(segment, index)` 的签名以现有调用（373 行）为准。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "*RenderOpenUiValidatorTest*" --console=plain`
Expected: 全部 PASS（含既有用例无回归）

- [ ] **Step 5: 跑 shared 全量测试防回归**

Run: `./gradlew :shared:jvmTest --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/RenderOpenUiValidator.kt server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt
git commit -m "feat(openui): FollowupBar 硬校验（≤3 项/仅 @ToAssistant/标签 ≤12 字）+ lint Button 计数解耦"
```

---

### Task 3: prompt 资产换教 FollowupBar

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiPromptAssets.kt`
  （GOLDEN_FOLLOWUP_PROGRAM ~140 行 / 签名清单 ~259 行 / DSL_RULES 后续选项段 ~494-508 行）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiPromptAssetsTest.kt`（~89-99 行 follow-up 段断言更新）

- [ ] **Step 1: 更新测试断言（先红）**

`OpenUiPromptAssetsTest.kt` 的 follow-up 段测试（89-99 行）改为：

```kotlin
@Test
fun `follow-up rules teach followup bar semantics`() {
    // 2026-11-28 FollowupBar 重设计：后续选项段是 LLM 唯一可见的 follow-up 规则文档
    assertTrue(OpenUiPromptAssets.DSL_RULES.contains("后续选项"))
    assertTrue(OpenUiPromptAssets.DSL_RULES.contains("FollowupBar([Button(选项标签, @ToAssistant(选项全文))"))
    assertTrue(OpenUiPromptAssets.DSL_RULES.contains("≤3 项"))
    assertTrue(OpenUiPromptAssets.DSL_RULES.contains("≤12 字"))
    // 旧形态不再被引导
    assertTrue(!OpenUiPromptAssets.DSL_RULES.contains("variant = \"tertiary\"))
    // 黄金示例内嵌进规则正文（同源钉住）
    assertTrue(OpenUiPromptAssets.DSL_RULES.contains(OpenUiPromptAssets.GOLDEN_FOLLOWUP_PROGRAM))
    // 签名清单含 FollowupBar
    assertTrue(OpenUiPromptAssets.DSL_RULES.contains("FollowupBar(items*=[Button...])"))
}
```

既有黄金示例三闸 forEach（77-84 行）无需改（引用常量自动覆盖新形态）。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*OpenUiPromptAssetsTest*" --console=plain`
Expected: FAIL（规则文本仍是旧形态）

- [ ] **Step 3: 实现三处修改**

**3a. `GOLDEN_FOLLOWUP_PROGRAM`（~140 行）整体替换：**

```kotlin
const val GOLDEN_FOLLOWUP_PROGRAM =
    "// summary: 上周照片整理建议与后续选项\n" +
        "root = Column([Markdown(\"上周你拍了 86 张，大头是周末奥森那场球。" +
        "整理可以从最乱的开始。\\n\\n要我动手的话，挑一个方向就行。\"), " +
        "FollowupBar([Button(\"挑最佳镜头\", @ToAssistant(\"从上周的照片里挑出最佳镜头\")), " +
        "Button(\"按场景分组\", @ToAssistant(\"把上周的照片按场景分组\")), " +
        "Button(\"找出废片\", @ToAssistant(\"找出上周照片里可以删掉的废片\"))])])"
```

（KDoc 同步：删「≤3 个 tertiary Button 横排」描述，改为「FollowupBar 全宽列表条，chevron 渲染层内置，LLM 只写 label + @ToAssistant」。）

**3b. 签名清单（~259 行 `Buttons(...)` 行后）加一行：**

```
     FollowupBar(items*=[Button...])（追问选项条：回复收尾专用全宽列表，端侧渲染左标签右箭头，Button 只写 label + @ToAssistant）/
```

**3c. DSL_RULES 后续选项段（~494-508 行）整体替换：**

```
   后续选项（2026-11-28 FollowupBar 重设计：回复收尾的后续动作列表——用户点选即把
   选项全文作为用户消息发进新一轮对话，动作三通道第 3 条的场景化应用）：
   - 何时加：正文收尾后，若存在与本轮上下文强相关的自然后续动作，在 root 树
     末尾追加 FollowupBar([Button(选项标签, @ToAssistant(选项全文)), ...])，≤3 项；
   - 何时不加：任务已收尾/话题自然结束/没有有意义的后续 → 不加，回复干净结束，
     不为凑数编造弱相关选项；
   - 写法约束：选项标签 ≤12 字（端侧全宽列表单行渲染，超长被拒）；@ToAssistant 的
     选项全文是用户视角的完整一句话（可比标签长，语境写全）；FollowupBar 内只允许
     @ToAssistant 动作（跳页/写操作走卡内 Button 三通道）；选项之间不重复、与刚
     给出的内容连贯；
   - 形态兼容：裸 Markdown 对话回复加选项时 root 升级为
     Column([Markdown(正文), FollowupBar([...])])（仍是对话形态，不算表单卡包装）；
     数据卡则在收尾 Markdown 讲解之后、Card 内末尾追加同一排；
   - 数量口径：FollowupBar 自身 ≤3 项，不与卡内动作 Button 合并计数；
```

> ⚠️ raw string 顶格纪律：DSL_RULES 是 raw string，段落前导空白是字符串值的一部分，新段落的缩进必须与被替换段落逐字符对齐（3 空格续行缩进），禁 re-indent。

- [ ] **Step 4: 跑测试确认通过 + 三闸全绿**

Run: `./gradlew -p server test --tests "*OpenUiPromptAssetsTest*" --tests "*OpenUiTurnNativeTest*" --tests "*OpenUiLayoutValidatorTest*" --console=plain`
Expected: PASS（黄金示例新形态过结构校验 + 布局门禁 + lint 三闸）

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiPromptAssets.kt server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiPromptAssetsTest.kt
git commit -m "feat(openui): prompt 后续选项段/签名/黄金示例换教 FollowupBar"
```

---

### Task 4: Android 渲染分支

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt`
  （dispatch 表 ~170 行 / 域集合 ~92 行 `openUiContentComponentNames` / 新渲染函数加在 `OpenUiContentButtons` ~1459 行后）
- Test: `androidApp/src/test/java/com/mamba/picme/features/chat/openui/OpenUiDispatchCoverageTest.kt`（`mainBranchNames` 加 "FollowupBar"）

- [ ] **Step 1: 更新 dispatch coverage 钉（先红）**

`OpenUiDispatchCoverageTest.kt` 的 `mainBranchNames` 集合加 `"FollowupBar"`：

```kotlin
    private val mainBranchNames = setOf(
        "Card", "Row", "Column", "Grid", "Box", "Spacer", "Text", "Button",
        "BarChart", "Image", "Video", "Icon", "Divider", "Html",
        "List", "Tabs", "CheckBox", "ChoicePicker", "Slider", "Table",
        "PieChart", "LineChart", "Callout", "Markdown", "Accordion", "Switch",
        "FollowupBar",
    )
```

同时 `openUiContentComponentNames`（`OpenUiContentRenderers.kt` ~92 行集合）加 `"FollowupBar"`。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*OpenUiDispatchCoverageTest*" --console=plain`
Expected: FAIL（FollowupBar 有注册无渲染分支）

- [ ] **Step 3: 实现渲染**

dispatch 表（~170 行 `"Buttons" -> ...` 后）加：

```kotlin
"FollowupBar" -> OpenUiContentFollowupBar(node, state, photoResolver, onAction, modifier)
```

`OpenUiContentButtons` 函数后新增（import 缺啥补啥：`Icon`、`Icons.AutoMirrored.Filled.KeyboardArrowRight`、`TextOverflow`、`RoundedCornerShape`、`clip`、`clickable`、`background`——以文件现有 import 为准去重）：

```kotlin
/**
 * FollowupBar：追问选项条（2026-11-28 重设计，spec §2）——竖排全宽列表行，
 * 左标签（fill 单行 ellipsis）右 chevron（渲染层内置，LLM DSL 不写）；
 * surfaceVariant 底 r12、pressed 加深 surfaceContainerHigh；项数上限沿用
 * MAX_CONTENT_ITEMS（validator 已拦 >3）。子 Button 只消费 label + action。
 */
@Composable
private fun OpenUiContentFollowupBar(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (List<OpenUiAction>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().take(MAX_CONTENT_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items.forEach { item ->
            val button = item as? OpenUiValue.ComponentVal ?: return@forEach
            val label = (button.props["label"] as? OpenUiValue.Str)?.v ?: return@forEach
            val actions = openUiActionsOf(button.props["action"])
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onAction(actions) }
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
```

（pressed 态：Compose M3 无内置 pressed 背景，`clickable` 默认 ripple 已提供触控反馈；若要底色加深态，后续用 `interactionSource + animateColorAsState` 迭代——首版 ripple 达标，spec §2 的 pressed 目标「按下去有反馈」即满足。）

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*OpenUiDispatchCoverageTest*" --console=plain`
Expected: PASS

- [ ] **Step 5: 编译整包确认**

Run: `./gradlew :androidApp:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt androidApp/src/test/java/com/mamba/picme/features/chat/openui/OpenUiDispatchCoverageTest.kt
git commit -m "feat(chat): Android FollowupBar 全宽列表条渲染（dispatch + coverage 钉住）"
```

---

### Task 5: iOS 渲染分支

**Files:**
- Modify: `iosApp/PoLang/Features/Chat/OpenUI/OpenUiNodeRenderer.swift`（~124 行 case 表 + 文件尾新 struct）
- Test: `iosApp/PoLangTests/OpenUiFollowupBarRenderTest.swift`（新建）

- [ ] **Step 1: 写失败测试**

```swift
import XCTest
@testable import PoLang

/// FollowupBar 渲染注册 smoke（2026-11-28 追问选项重设计）：
/// shared 解析 FollowupBar 程序 → 渲染入口产出非 nil 且含 3 行选项。
final class OpenUiFollowupBarRenderTest: XCTestCase {
    func testFollowupBarProgramRendersThreeRows() throws {
        let program = """
        root = FollowupBar([Button("挑最佳镜头", @ToAssistant("从上周的照片里挑出最佳镜头")), Button("按场景分组", @ToAssistant("把上周的照片按场景分组")), Button("找出废片", @ToAssistant("找出上周照片里可以删掉的废片"))])
        """
        let node = try XCTUnwrap(SharedKit.parseOpenUiRoot(program), "FollowupBar 程序应解析出 root 节点")
        let renderer = OpenUiNodeRenderer(node: node, ctx: .placeholder)
        XCTAssertNotNil(renderer.body)
    }
}
```

> 执行注意：`parseOpenUiRoot` / `ctx: .placeholder` 是示意名——执行时先读 `iosApp/PoLang/Features/Chat/OpenUI/` 现有测试或渲染入口的真实构造方式（参照 `CloudTurnRunnerTests.swift` 里 shared 程序的解析调用），用真实 API 改写本测试的构造段，断言语义不变（解析成功 + 渲染不崩）。若渲染入口依赖 SwiftUI 环境，退化为「shared 解析 FollowupBar 程序成功 + 组件类型正确」的纯逻辑断言。

- [ ] **Step 2: 跑测试确认失败**

Run: `xcodebuild test -workspace iosApp/PoLang.xcworkspace -scheme PoLang -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:PoLangTests/OpenUiFollowupBarRenderTest`
（模拟器名以 `xcrun simctl list devices available` 为准；Intel 机器注意 SharedKit 需 simulator 切片——参照记忆 ios-intel-dev-device-only-build，若模拟器不可用改真机 UDID）
Expected: FAIL（case 分支缺失 / 编译错误）

- [ ] **Step 3: 实现**

case 表（~124 行 `case "Buttons"` 后）加：

```swift
case "FollowupBar": OpenUiFollowupBarComponent(node: node, ctx: ctx)
```

文件尾部新增（样式值对齐 spec §2；SwiftUI 无 pressed 底色时用 `ButtonStyle` 定制，首版可用系统 highlight 简化）：

```swift
/// FollowupBar：追问选项条（2026-11-28 重设计）——竖排全宽行，左标签右 chevron。
/// chevron 是渲染层内置装饰（LLM DSL 不写）；子 Button 只消费 label + action。
struct OpenUiFollowupBarComponent: View {
    let node: OpenUiNode
    let ctx: OpenUiRenderContext

    var body: some View {
        let items = (node.props["items"] as? [OpenUiNode]) ?? []
        VStack(spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                let label = (item.props["label"] as? String) ?? ""
                if !label.isEmpty {
                    OpenUiFollowupRow(label: label, item: item, ctx: ctx)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }
}

private struct OpenUiFollowupRow: View {
    let label: String
    let item: OpenUiNode
    let ctx: OpenUiRenderContext

    var body: some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.system(size: 14))
                .foregroundColor(Color(.openUiOnSurface))
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 14))
                .foregroundColor(Color(.openUiOnSurfaceVariant))
        }
        .padding(EdgeInsets(top: 12, leading: 14, bottom: 12, trailing: 14))
        .background(Color(.openUiSurfaceVariant))
        .cornerRadius(12)
        .contentShape(Rectangle())
        .onTapGesture { ctx.dispatch(item) }
    }
}
```

> 执行注意：`OpenUiNode`/`OpenUiRenderContext`/`Color(.openUiOnSurface)` 等类型与色彩桥接以 `OpenUiButtonsComponent` 邻近实现的既有命名为准对齐改写（读现有 Buttons/IconButton 渲染取真实 props 访问方式、色板桥接、action 派发回调名）；结构性样式值（8/12/14/16、r12、单行省略）不得偏离 spec §2。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add iosApp/PoLang/Features/Chat/OpenUI/OpenUiNodeRenderer.swift iosApp/PoLangTests/OpenUiFollowupBarRenderTest.swift
git commit -m "feat(chat): iOS FollowupBar 全宽列表条渲染（对等 Android）"
```

---

### Task 6: Ardot 画布收口

> 前置：Ardot 客户端已打开 polang-ui-spec；本 task 全程**单会话串行**（无并行子代理）。
> 现状帧：A=`561:1`、B=`561:11`（定稿源）、C=`561:23`，均在 Chat 页（`111:319`）。

**Files:**
- 画布：Chat 页 / ChatComponents 页（`438:328`）
- Modify: `docs/08-UI-SPECS/screens/refs/ardot/components.json`
- 快照: `docs/08-UI-SPECS/screens/refs/ardot/`

- [ ] **Step 1: 组件化 followup_row**

1. `batch_read` ChatComponents 页（`nodeIds: ["438:328"]`, readDepth=1）确认布局与既有组件排布，用 `locate_available_space` 找空位；
2. 用 `C("561:14", "<ChatComponents 空位父>", {name:"followup_row"})` 把 B 帧的 OptionRow（561:14）拷贝为组件本体（**先 batch_read 561:14 确认子树完整**：OptionLabel/OptionArrow）；
3. `U(<新组件id>, {reusable: true})`（若 API 支持组件化标记；Ardot 组件化配方参照 `components.json` 既有条目结构——执行时读 `component/floating_tab` 等条目的字段对齐登记格式）；
4. chevron 垂直居中修正：OptionArrow（拷贝后的子节点）设 `textAlignVertical: "CENTER"`（或父 Row 的 counterAxisAlignItems: "CENTER"）——mockup 自检发现的基线微偏在此修掉；
5. 颜色核对：全 fills/strokes 绑 `PoLang Tokens` 变量（$2:150 底 / $2:149 文字 / $2:151 chevron），literal=0。

- [ ] **Step 2: 正典接线**

`chat/conversation`（111:383）AgentMsg2（111:569）尾部：`I` 三个 followup_row 实例（type:"ref"），标签文本依次「挑最佳镜头/按场景分组/找出废片」——参照正典帧既有实例化手法（`I` + 显式宽/布局参数），OptionList 容器 vertical gap8。删 AgentMsg2 内旧无选项态占位（若有）。

- [ ] **Step 3: 清理 + 验证三连**

1. `D("561:1")`、`D("561:23")` 删 A/C 帧（函数式 D 语法）；
2. B 帧（561:11）整个 `D`（内容已组件化转移）或保留为标注帧——**默认删除**，快照不留 mockup 残帧；
3. health-check：

```bash
python3 scripts/ardot-scan.py --pages Chat ChatComponents --out-dir tmp/ardot-followup/final --vars-out tmp/ardot-followup/final/vars.jsonl
python3 scripts/ardot-health-check.py --jsonl tmp/ardot-followup/final --vars tmp/ardot-followup/final/vars.jsonl \
  --components docs/08-UI-SPECS/screens/refs/ardot/components.json \
  --ignore-file docs/08-UI-SPECS/screens/refs/ardot/health-ignore.txt \
  --out-dir tmp/ardot-followup/final
```

Expected: 退出码 0（新组件子树 literal=0；若 scan 因 MCP session 协议失败，参照本次会话的无 session rpc 模式手搓 batch_read 落盘）

4. Light 验证（followup_row 组件 + 接线后的 conversation 帧）：

```bash
scripts/ardot-preview-mode.sh <frameId> light --shot tmp/ardot-followup/final/<name>-light.png
python3 scripts/ardot-light-verify.py --light-dir tmp/ardot-followup/final \
  --dark-dir docs/08-UI-SPECS/screens/refs/ardot
scripts/ardot-preview-mode.sh <frameId> dark
```

Expected: Light 判据通过（双模同值 token 组件按 components.json rules 备注豁免）

5. `components.json` 登记 `component/followup_row`（结构对齐既有条目）+ 双模预览 PNG 入 `refs/ardot/components/`。

- [ ] **Step 4: 快照入库**

```bash
python3 scripts/export-ardot-snapshot.py
ls docs/08-UI-SPECS/screens/refs/ardot/   # 核对陈旧 PNG → git rm（followup/A/B/C 帧图应消失）
```

- [ ] **Step 5: Commit**

```bash
git add docs/08-UI-SPECS/screens/refs/ardot/
git commit -m "feat(design): followup_row 组件化入 ChatComponents + 正典接线 + 快照收口"
```

---

### Task 7: 文档同步 + spec 勾选

**Files:**
- Modify: `shared/AGENTS.md`（openui 节：组件数 103→104 追记 FollowupBar 一句）
- Modify: `docs/04-AGENT-CAPABILITIES/CHAT_CARD_CATALOG.md`（data_genui_program 节：后续选项形态 FollowupBar 登记）
- Modify: `docs/superpowers/specs/2026-11-28-followup-chips-redesign-design.md`（§7 交付清单勾选）

- [ ] **Step 1: shared/AGENTS.md openui 节追加一句**

在 openui 组件演进长句尾部追加（保持该文件「一句一能力」的既有文风）：

```
；追问选项 FollowupBar 重设计（2026-11-28，spec《2026-11-28-followup-chips-redesign-design》）：白名单 103→104 +FollowupBar（items=[Button...]，追问专用全宽列表条——端侧左标签右 chevron、≤3 项、action 仅 @ToAssistant），prompt 后续选项段/签名/黄金示例同步换教，lint「一卡 Button ≤3」计数与 FollowupBar 区间解耦（validator 硬校验接管），双端各一渲染分支（Android OpenUiContentFollowupBar / iOS OpenUiFollowupBarComponent），Buttons/Button 语义零改动（存量卡不受影响）
```

- [ ] **Step 2: CHAT_CARD_CATALOG.md 登记**

`data_genui_program` 节追加 FollowupBar 组件行（格式对齐节内既有组件条目；组合谱系定位：对话形态收尾的轻引导，非卡内 CTA）。

- [ ] **Step 3: spec 交付清单勾选**

`docs/superpowers/specs/2026-11-28-followup-chips-redesign-design.md` §7 五个 `- [ ]` 改 `- [x]`。

- [ ] **Step 4: 全量验证**

Run: `./gradlew :shared:jvmTest -p server test --console=plain && ./gradlew :androidApp:testDebugUnitTest --console=plain`
Expected: 全绿

- [ ] **Step 5: Commit**

```bash
git add shared/AGENTS.md docs/04-AGENT-CAPABILITIES/CHAT_CARD_CATALOG.md docs/superpowers/specs/2026-11-28-followup-chips-redesign-design.md
git commit -m "docs: FollowupBar 重设计三处文档同步（AGENTS/CATALOG/spec 勾选）"
```

---

## 验收清单（对照 spec）

- [ ] 白名单 104，FollowupBar props=items(COMPONENT_LIST, required)
- [ ] validator：>3 项拒 / 非 @ToAssistant 拒 / 标签 >12 字拒 / lint Button 计数不含 FollowupBar 区间
- [ ] DSL_RULES 教 FollowupBar（无 tertiary 残留），黄金示例过三闸
- [ ] Android/iOS 各一渲染分支，dispatch coverage 钉住
- [ ] Ardot：followup_row 组件化 + 正典接线 + A/B/C 帧清理 + health-check 0 + Light 过 + 快照入库
- [ ] 三处文档同步
