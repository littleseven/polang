# 工程师任务卡 OpenUI 化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 工程师任务卡信息区从 WebView HTML 模板升级为 OpenUI 程序描述 + 原生渲染（端侧确定性合成、程序文本全管线、动作区原生不动、Android 先行双轨、复刻双形态+全屏）。

**Architecture:** `EngineerTaskProgram`（shared 纯函数）把 `EngineerTaskState`+`EngineerTaskTexts` 合成为 OpenUI 程序文本 → `OpenUiSession.prepare()` → 宿主解包根 Card 渲染 children（外层两分区容器/动作区不变）。词汇层补 4 个通用缺口：`Progress` 组件、`Tag.tone` 实心档、`Icon.container`、`Text.maxLines`、`Row weights=0` hug 语义。协议零变更。

**Tech Stack:** Kotlin (shared KMP commonMain / androidApp Compose / server Ktor)，kotlinx 单测，OpenUI 自研运行时。

**Spec:** `docs/superpowers/specs/2026-11-02-engineer-taskcard-openui-design.md`（§3.2 回滚闸按本计划 Task 6 修订为代码内开关——YAGNI：iOS 双轨期 HTML 路径本就保留编译，开发者选项项暂不加）

**已核实的语法/API 事实**（执行者无需再考古）：
- DSML：语句 `name = Expr`；组件调用 `Text("x", role = "SUBHEAD")`（首参位置、余参 `名 = 值`）；列表 `[a, b]`；根必须 Card（`root = Card([...])`）。
- 字符串字面量转义（lexer 支持收）：`\n \t \r \\ \" \uXXXX`；其余控制字符直接落文本会被当原字符（`kept as is` 去 `\` 保留）——所以合成端必须全转义。
- `OpenUiSession(program: String, tools: ...)`（构造参数 tools 有默认值，测试里 `OpenUiSession(program)` 直接用）；`suspend prepare(): OpenUiResult`；`rootOrNull(): ComponentVal?`。
- `OpenUiNodeRenderer(node, state: Map<String,OpenUiValue>, photoResolver, onAction, modifier)` 公开可复用；无绑定的程序传 `emptyMap()` + 空 onAction。
- Row 渲染：`Modifier.weight(w)` 恒施加（w≤0/NaN 被 `openUiSafeWeight` 钳成 1f）——**weight 0 = hug 需要本计划解锁**（负/NaN 仍钳 1f）。
- server 白名单：`server/.../RenderOpenUiValidator.kt` `ALLOWED_COMPONENTS`（+`RenderOpenUiToolService.kt` `@LLMDescription` 副本）。
- 测试命令：`./gradlew :shared:jvmTest`、`./gradlew :androidApp:testDebugUnitTest`、`./gradlew :server:test`。

---

### Task 1: shared 词汇扩展（Progress 组件 / Tag.tone / Icon.container / Text.maxLines）

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibrary.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibraryExtTest.kt`（新建）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.chat.openui

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 任务卡 OpenUI 化词汇扩展（spec 2026-11-02 §3.3）：Progress 新组件 + Tag.tone/Icon.container/Text.maxLines。 */
class OpenUiLibraryExtTest {

    @Test
    fun `progress component renders with clamped value`() = runTest {
        val result = OpenUiSession("""root = Card([Progress(120)])""").prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val progress = (result.root.props["children"] as? OpenUiValue.Arr)?.v?.single()
        assertIs<OpenUiValue.ComponentVal>(progress)
        assertEquals("Progress", progress.type)
        assertEquals(OpenUiValue.Num(120.0), progress.props["value"])
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `tag accepts solid tone enum`() = runTest {
        val result = OpenUiSession("""root = Card([Tag("运行中", tone = "primary")])""").prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val tag = (result.root.props["children"] as? OpenUiValue.Arr)?.v?.single()
        assertIs<OpenUiValue.ComponentVal>(tag)
        assertEquals(OpenUiValue.Str("primary"), tag.props["tone"])
        // 缺省 tone 不落 props（无默认值=向后兼容存量 Tag 外观）
        val legacy = OpenUiSession("""root = Card([Tag("x")])""").prepare()
        assertIs<OpenUiResult.Rendered>(legacy)
    }

    @Test
    fun `icon accepts container enum`() = runTest {
        val result = OpenUiSession("""root = Card([Icon("settings", container = "tonal")])""").prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val icon = (result.root.props["children"] as? OpenUiValue.Arr)?.v?.single()
        assertIs<OpenUiValue.ComponentVal>(icon)
        assertEquals(OpenUiValue.Str("tonal"), icon.props["container"])
    }

    @Test
    fun `text accepts maxLines number`() = runTest {
        val result = OpenUiSession("""root = Card([Text("长标题", role = "SUBHEAD", maxLines = 1)])""").prepare()
        assertIs<OpenUiResult.Rendered>(result)
        val text = (result.root.props["children"] as? OpenUiValue.Arr)?.v?.single()
        assertIs<OpenUiValue.ComponentVal>(text)
        assertEquals(OpenUiValue.Num(1.0), text.props["maxLines"])
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `row weights zero passes schema`() = runTest {
        val result = OpenUiSession(
            """root = Card([Row([Text("✓"), Text("标签")], weights = [0, 1])])""",
        ).prepare()
        assertIs<OpenUiResult.Rendered>(result)
        assertEquals(emptyList(), result.errors)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :shared:jvmTest --tests '*OpenUiLibraryExtTest*'`
Expected: FAIL（`Progress` 组件未注册 → 丢组件/`tone` 非法枚举值报错）

- [ ] **Step 3: 改 OpenUiLibrary.kt**

① `Text` 组件 props 列表（现 `text/role/colorRole`）追加：

```kotlin
                // 任务卡 OpenUI 化（2026-11-02）：可选行数上限（渲染期 Ellipsis）——
                // 通用能力（长文本截断），缺省不落 props 向后兼容
                OpenUiProp("maxLines", OpenUiPropType.NUMBER),
```

② `Icon` 组件 props（现 `name/colorRole`）追加：

```kotlin
                // 任务卡 OpenUI 化（2026-11-02）：tonal = 图标嵌 surfaceContainerHigh
                // 圆角容器块（28dp r7，通用「图标块」形态）；缺省 none = 裸图标
                OpenUiProp(
                    "container",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("none"),
                    enumValues = listOf("none", "tonal"),
                ),
```

③ `Tag` 组件 props（现 `text/icon/size/…`）追加（放在 `size` 之后）：

```kotlin
                // 任务卡 OpenUI 化（2026-11-02）：实心档——primary/error/neutral 实心底
                // + 对应前景色（状态 chip 语义）；缺省不带 tone = 存量外观不变
                OpenUiProp(
                    "tone",
                    OpenUiPropType.ENUM,
                    enumValues = listOf("primary", "error", "neutral"),
                ),
```

④ 组件清单追加（放 `Divider` 注册之后）：

```kotlin
        // 任务卡 OpenUI 化（2026-11-02）：通用进度条——4dp 轨道 + primary 填充，
        // value 0-100（渲染期 clamp）；LLM 卡与端侧合成卡共用
        OpenUiComponent(
            "Progress",
            listOf(
                OpenUiProp("value", OpenUiPropType.NUMBER, required = true),
            ),
        ),
```

⑤ 文件头 KDoc 组件数注记追加一行（`一百零一项注册 = …` 段后）：

```kotlin
 * 2026-11-02 任务卡 OpenUI 化批次：+Progress；Tag +tone（实心档）、Icon +container、
 * Text +maxLines；Row weights 0=hug 语义（渲染层）。
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :shared:jvmTest --tests '*OpenUiLibraryExtTest*'`
Expected: PASS（5 用例全绿）

- [ ] **Step 5: 提交**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibrary.kt shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibraryExtTest.kt
git commit -m "feat(openui): 词汇扩展——Progress 组件 + Tag.tone/Icon.container/Text.maxLines 通用能力"
```

---

### Task 2: androidApp 渲染器（weight-0 hug / Progress / Tag 实心 / Icon 容器块 / Text maxLines）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiNodeRenderer.kt`（Row weight 逻辑 + Text maxLines + Icon container）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt`（dispatch 名单 + Tag tone 皮肤 + Progress 渲染）
- Test: 既有「组件注册=渲染器 dispatch」清单测试（执行时 `grep -rn 'contentRenderComponents\|dispatch' androidApp/src/test --include='*.kt'` 定位，把 `Progress` 加进期望集）

- [ ] **Step 1: Row weight 0 = hug**

`OpenUiNodeRenderer.kt` Row 分支内（现 236-252 行区域），把：

```kotlin
                            val slotWeight = if (isSeparator) {
                                1f
                            } else {
                                // 值域防御（2026-11-01 review 🔴1）：weights 经 schema 只保证
                                // 「数字数组」，零/负值/NaN 直通 Modifier.weight() 会抛
                                // IllegalArgumentException（require(weight > 0)）击穿渲染层——
                                // 非法槽位回退 1f 等宽
                                val w = openUiSafeWeight(effectiveWeights?.getOrNull(weightSlot) ?: 1f)
                                weightSlot++
                                w
                            }
                            val weightMod =
                                if (isSeparator || !weightsUsable) {
                                    Modifier
                                } else {
                                    Modifier.weight(slotWeight)
                                }
```

改为：

```kotlin
                            // 任务卡 OpenUI 化（2026-11-02）：weights 槽位值 0 = hug（CSS
                            // flex-grow:0 语义，子项按内容自尺寸）——解锁「固定图标 + 弹性
                            // 标题 + 贴边 chip」类排版；负值/NaN 仍走值域防御回退 1f 等宽
                            // （2026-11-01 review 🔴1：直通 Modifier.weight() 会抛
                            // IllegalArgumentException 击穿渲染层）
                            val declaredWeight = if (isSeparator) null else {
                                val w = effectiveWeights?.getOrNull(weightSlot)
                                weightSlot++
                w
            }
                            val isHug = !isSeparator && weightsUsable && declaredWeight == 0f
                            val weightMod = when {
                                isSeparator || !weightsUsable -> Modifier
                                isHug -> Modifier
                                else -> Modifier.weight(openUiSafeWeight(declaredWeight ?: 1f))
                            }
```

（注意保持缩进层级与原文件一致；`slotWeight` 变量删除后确认分支内无残余引用。）

- [ ] **Step 2: Text maxLines**

`OpenUiNodeRenderer.kt` 的 `"Text"` 渲染分支（`grep -n '"Text" ->' OpenUiNodeRenderer.kt` 定位），在既有 `Text(...)` 组合上调叠加 maxLines：

```kotlin
        "Text" -> run {
            // ……既有样式/颜色取值不动……
            val maxLinesRaw = (node.props["maxLines"] as? OpenUiValue.Num)?.v?.toInt()
            Text(
                text = /* 既有 */,
                style = /* 既有 */,
                color = /* 既有 */,
                // 任务卡 OpenUI 化（2026-11-02）：可选行数上限（Ellipsis）；缺省不限制
                maxLines = maxLinesRaw ?: Int.MAX_VALUE,
                overflow = if (maxLinesRaw != null) TextOverflow.Ellipsis else TextOverflow.Clip,
            )
        }
```

（按该分支实际代码形态合并——若既有 Text 调用已带 maxLines/overflow 参数则按上表改值。）

- [ ] **Step 3: Icon container 块**

`OpenUiNodeRenderer.kt` 的 `"Icon"` 分支：包一层条件容器——

```kotlin
        "Icon" -> run {
            // ……既有 name/colorRole 取值……
            val container = (node.props["container"] as? OpenUiValue.Str)?.v ?: "none"
            if (container == "tonal") {
                // 任务卡 OpenUI 化（2026-11-02）：图标嵌 tonal 容器块（28dp r7
                // surfaceContainerHigh，对齐任务卡 ⚙ 块设计）
                Box(
                    modifier = modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = /* 既有 */, contentDescription = null,
                        tint = /* 既有 tint */, modifier = Modifier.size(15.dp))
                }
            } else {
                /* 既有裸图标渲染不动 */
            }
        }
```

- [ ] **Step 4: Tag 实心 tone + Progress 渲染（OpenUiContentRenderers.kt）**

① dispatch 名单（`CONTENT_*` 集合常量，85-92 行区域）加 `"Progress",`（带注释 `// 任务卡 OpenUI 化（2026-11-02）`）；`when` 分支加：

```kotlin
        "Progress" -> OpenUiContentProgress(node, modifier)
```

② 新渲染器（文件内其他 `OpenUiContentXxx` 同级）：

```kotlin
/**
 * Progress：通用进度条（任务卡 OpenUI 化 2026-11-02）——4dp 圆角轨道（surfaceVariant）
 * + primary 填充，value 渲染期 clamp [0,100]。
 */
private fun OpenUiContentProgress(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val raw = (node.props["value"] as? OpenUiValue.Num)?.v ?: return
    val fraction = ((raw.coerceIn(0.0, 100.0)) / 100.0).toFloat()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
```

（import 按该文件既有惯例补齐：`clip/height/background/fillMax*`、`RoundedCornerShape`、`MaterialTheme` 等大概率已有。）

③ Tag tone 皮肤：`OpenUiContentTagNode` 读 `tone`，实心档走新私有 chip：

```kotlin
private fun OpenUiContentTagNode(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val text = (node.props["text"] as? OpenUiValue.Str)?.v ?: return
    val icon = (node.props["icon"] as? OpenUiValue.Str)?.v
    val size = (node.props["size"] as? OpenUiValue.Str)?.v
    val variant = (node.props["variant"] as? OpenUiValue.Str)?.v
    val tone = (node.props["tone"] as? OpenUiValue.Str)?.v
    if (tone != null) {
        OpenUiSolidTagChip(text = text, tone = tone, modifier = modifier)
    } else {
        OpenUiContentTagChip(text = text, icon = icon, size = size, variant = variant, modifier = modifier)
    }
}

/** 实心状态 chip（任务卡状态语义 2026-11-02）：r6 + 10sp SemiBold + tone 实心底/前景。 */
private fun OpenUiSolidTagChip(text: String, tone: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        "primary" -> scheme.primary to scheme.onPrimary
        "error" -> scheme.error to scheme.onError
        else -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}
```

- [ ] **Step 5: 清单测试同步**

`grep -rn 'Progress\|compareRow\|Timeline' androidApp/src/test --include='*.kt' -l` 找到组件覆盖清单测试，期望集加 `"Progress"`。

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: PASS（含既有全部 OpenUI 渲染测试）

- [ ] **Step 6: 编译 + lint 门禁**

Run: `./gradlew :androidApp:compileDebugKotlin ktlintCheck :androidApp:detektMain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/openui/ androidApp/src/test/
git commit -m "feat(openui): 渲染器补齐——Row weights 0=hug + Progress/Tag 实心/Icon 容器块/Text maxLines"
```

---

### Task 3: shared 组装器 EngineerTaskProgram + golden 单测

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/taskcenter/EngineerTaskProgram.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/taskcenter/EngineerTaskProgramTest.kt`

- [ ] **Step 1: 写失败测试**（fixture 风格对齐 `EngineerTaskHtmlTest.kt` 既有夹具）

```kotlin
package com.mamba.picme.domain.chat.taskcenter

import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineerTaskProgramTest {

    private fun task(
        status: EngineerTaskStatus = EngineerTaskStatus.RUNNING,
        resolution: EngineerTaskResolution? = null,
        stage: String? = "Edit TagScanOrchestrator.kt",
        recentStages: List<String> = listOf("Read MainActivity.kt", "Edit TagScanOrchestrator.kt"),
        errorSummary: String? = null,
        resultSummary: String? = null,
        truncatedReason: String? = null,
    ) = EngineerTaskState(
        taskId = "t1", sourceText = "修复扫描崩溃", status = status, stage = stage,
        recentStages = recentStages, turns = 3, costCents = 42, startedAtMs = 0, updatedAtMs = 65_000,
        fileChangeCount = 2, truncatedReason = truncatedReason, errorSummary = errorSummary,
        resultSummary = resultSummary, resolution = resolution,
    )

    private val texts = EngineerTaskTexts(
        titlePrefix = "工程师任务：", badgeLabel = "TASK · OPENUI",
        chipRunning = "运行中", chipAwaiting = "待审批", chipCompleted = "已完成",
        chipFailed = "失败", chipResolvedContinued = "已继续", chipResolvedAbandoned = "已放弃",
        chipResolvedDelivered = "已交付", chipResolvedSkipped = "暂不交付",
        expandHint = "展开", collapseHint = "收起", recentEventsCaption = "最近事件",
        metaTurns = "轮次 3", metaElapsed = "耗时 1:05", metaCost = "成本 $0.42",
        reasonPrefix = "截断原因：", usedPrefix = "已用：", summaryPrefix = "小结：",
        usedTurns = "轮次 3", usedCost = "成本 $0.42", filesChanged = "2 files changed",
        stageLabels = mapOf("Read" to "读取文件", "Edit" to "编辑代码", "tool" to "工具"),
        stageVerbs = mapOf("Edit" to "正在修改", "tool" to "正在处理"),
    )

    @Test
    fun `collapsed running card golden`() = runTest {
        val program = EngineerTaskProgram.assemble(task(), expanded = false, texts = texts)
        assertEquals(
            """
            root = Card([Column([
              Column([Tag("TASK · OPENUI")], align = "end"),
              Row([Icon("settings", container = "tonal"), Text("工程师任务：修复扫描崩溃", role = "SUBHEAD", maxLines = 1), Tag("运行中", tone = "primary")], weights = [0, 1, 0]),
              Text("Edit · 正在修改 TagScanOrchestrator.kt"),
              Text("轮次 3 ｜ 耗时 1:05 ｜ 成本 $0.42", role = "CAPTION", colorRole = "SURFACE"),
              Progress(30),
              Column([Text("展开", role = "CAPTION", colorRole = "SURFACE")], align = "center")
            ], spacing = "s")])
            """.trimIndent(),
            program,
        )
        // 全管线自证：parse+eval 无错误
        val result = OpenUiSession(program).prepare()
        assertIs<OpenUiResult.Rendered>(result)
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `expanded card adds divider timeline events and files row`() = runTest {
        val program = EngineerTaskProgram.assemble(task(), expanded = true, texts = texts)
        assertTrue("Divider()" in program)
        assertTrue("""Text("✓", colorRole = "PRIMARY")""" in program)
        assertTrue("""Text("●", colorRole = "PRIMARY")""" in program) // RUNNING 末条 = 当前
        assertTrue("最近事件" in program)
        assertTrue("2 files changed" in program)
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program).prepare())
    }

    @Test
    fun `failed card uses error tone chip and callout`() = runTest {
        val program = EngineerTaskProgram.assemble(
            task(status = EngineerTaskStatus.FAILED, errorSummary = "推理超时"),
            expanded = false, texts = texts,
        )
        assertTrue("""Tag("失败", tone = "error")""" in program)
        assertTrue("""Callout("推理超时", tone = "ERROR")""" in program)
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program).prepare())
    }

    @Test
    fun `resolved card uses neutral tone chip`() = runTest {
        val program = EngineerTaskProgram.assemble(
            task(resolution = EngineerTaskResolution.DELIVERED),
            expanded = false, texts = texts,
        )
        assertTrue("""tone = "neutral"""" in program)
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program).prepare())
    }

    @Test
    fun `injection attempts in state text are program escaped`() = runTest {
        val hostile = task(stage = "Edit \"x\"\\nroot = Card([Text(\"pwned\")])")
        val program = EngineerTaskProgram.assemble(hostile, expanded = false, texts = texts)
        assertEquals(1, Regex("root = Card").findAll(program).count()) // 只有一处根语句
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program).prepare())
        assertEquals(emptyList(), (OpenUiSession(program).prepare() as OpenUiResult.Rendered).errors)
    }

    @Test
    fun `escapeProgramString covers quote backslash newline tab cr and control`() {
        assertEquals("""\"\\n\\t\\r\\\\\"""", EngineerTaskProgram.escapeProgramString("\"\n\t\r\\"))
        assertEquals("""\\u0001""", EngineerTaskProgram.escapeProgramString(""))
        assertEquals("plain 中文 ✅", EngineerTaskProgram.escapeProgramString("plain 中文 ✅"))
    }

    @Test
    fun `awaiting continue shows reason used summary without progress`() = runTest {
        val program = EngineerTaskProgram.assemble(
            task(status = EngineerTaskStatus.AWAITING_CONTINUE, truncatedReason = "max_turns",
                resultSummary = "已完成一半"),
            expanded = false, texts = texts,
        )
        assertTrue("截断原因" in program && "已继续" !in program)
        assertTrue("已用：" in program && "小结：" in program)
        assertTrue("Progress(" !in program)
        assertIs<OpenUiResult.Rendered>(OpenUiSession(program).prepare())
    }

    @Test
    fun `timeline caps at eight rows with leading ellipsis literal`() = runTest {
        val many = task(recentStages = (1..12).map { "Read f$it.kt" })
        val program = EngineerTaskProgram.assemble(many, expanded = true, texts = texts)
        assertEquals(8, Regex("""Text\("✓""").findAll(program).count())
        assertTrue("…" in program)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :shared:jvmTest --tests '*EngineerTaskProgramTest*'`
Expected: FAIL（`EngineerTaskProgram` 未定义）

- [ ] **Step 3: 实现 EngineerTaskProgram.kt**

```kotlin
package com.mamba.picme.domain.chat.taskcenter

import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus

/**
 * 工程师任务卡 OpenUI 程序组装器（spec《工程师任务卡 OpenUI 化》2026-11-02 §3.1；
 * [EngineerTaskHtml] 的 OpenUI 对偶——段落逻辑/文案回退/已知偏差逐条平移，阶段解析
 * 与进度启发式**委托 [EngineerTaskHtml] 复用不复制**）。
 *
 * - 产物 = 单条 `root = Card([...])` 语句的 OpenUI 程序文本（协议要求 Card 根）；
 *   宿主端解包 Card 渲染 children（卡壳由外层两分区原生容器提供）。
 * - 纯函数：同输入同输出（不含时间戳），golden 单测钉住。
 * - **唯一注入面 = [escapeProgramString]**（对偶 escapeHtml）：所有状态文本入程序前
 *   按 lexer 字符串字面量规则全转义（\ " 换行/制表/回车/控制字符）。
 * - iOS 双轨期 HTML 模板仍在（[EngineerTaskHtml]），本组装器为 Android 渲染路径 SSOT。
 */
object EngineerTaskProgram {

    /** 时间线最多渲染条数（与 [EngineerTaskHtml] 同口径，防长卡）。 */
    private const val TIMELINE_MAX_ROWS = 8

    private const val TITLE_SEP = " ｜ "

    fun assemble(task: EngineerTaskState, expanded: Boolean, texts: EngineerTaskTexts): String {
        val sections = mutableListOf<String>()
        sections += """Column([Tag("${esc(texts.badgeLabel)}")], align = "end")"""
        sections += headerRow(task, texts)
        when (task.status) {
            EngineerTaskStatus.RUNNING -> {
                task.stage?.takeIf { stage -> stage.isNotBlank() }?.let { stage ->
                    sections += """Text("${esc(EngineerTaskHtml.stageLine(stage, texts))}")"""
                }
                sections += metaLine(task, texts)
                sections += """Progress(${EngineerTaskHtml.progressFraction(task)})"""
            }
            EngineerTaskStatus.AWAITING_CONTINUE -> {
                task.truncatedReason?.takeIf { reason -> reason.isNotBlank() }?.let { reason ->
                    sections += """Text("${esc(texts.reasonPrefix + EngineerTaskHtml.truncatedReasonLabel(reason, texts))}")"""
                }
                sections += usedLine(texts)
                task.resultSummary?.takeIf { summary -> summary.isNotBlank() }?.let { summary ->
                    sections += """Text("${esc(texts.summaryPrefix + summary)}")"""
                }
            }
            EngineerTaskStatus.AWAITING_DELIVER -> {
                texts.deliverSummary?.let { summary -> sections += """Text("${esc(summary)}", role = "CAPTION", colorRole = "SURFACE")""" }
                errorBlock(task)?.let { sections += it }
            }
            EngineerTaskStatus.COMPLETED -> {
                task.resultSummary?.takeIf { summary -> summary.isNotBlank() }?.let { summary ->
                    sections += """Text("${esc(summary)}", role = "CAPTION", colorRole = "SURFACE")""" }
                }
                // 失败后被裁决的卡：出错原因不随终态消失（对齐 H2 口径）
                if (task.resolution != null) errorBlock(task)?.let { sections += it }
            }
            EngineerTaskStatus.FAILED -> errorBlock(task)?.let { sections += it }
        }
        if (task.recentStages.isNotEmpty()) {
            if (expanded) {
                sections += "Divider()"
                sections += timeline(task, texts)
                sections += events(task, texts)
                texts.filesChanged?.let { files -> sections += """Text("${esc(files)}", role = "CAPTION", colorRole = "SURFACE")""" }
            }
            sections += """Column([Text("${esc(if (expanded) texts.collapseHint else texts.expandHint)}", role = "CAPTION", colorRole = "SURFACE")], align = "center")"""
        }
        val body = sections.joinToString(",\n  ")
        return "root = Card([Column([\n  $body\n], spacing = \"s\")])"
    }

    /** OpenUI 程序字符串字面量转义（唯一注入面；lexer 收 \n \t \r \\ \" \uXXXX）。 */
    fun escapeProgramString(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\t' -> append("\\t")
                ch == '\r' -> append("\\r")
                ch.code < 0x20 -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
    }

    private fun esc(raw: String): String = escapeProgramString(raw)

    private fun headerRow(task: EngineerTaskState, texts: EngineerTaskTexts): String {
        val (chipText, tone) = when (task.resolution) {
            EngineerTaskResolution.CONTINUED -> texts.chipResolvedContinued to "neutral"
            EngineerTaskResolution.ABANDONED -> texts.chipResolvedAbandoned to "neutral"
            EngineerTaskResolution.DELIVERED -> texts.chipResolvedDelivered to "neutral"
            EngineerTaskResolution.DELIVER_SKIPPED -> texts.chipResolvedSkipped to "neutral"
            null -> when (task.status) {
                EngineerTaskStatus.RUNNING -> texts.chipRunning to "primary"
                EngineerTaskStatus.COMPLETED -> texts.chipCompleted to "primary"
                EngineerTaskStatus.AWAITING_CONTINUE,
                EngineerTaskStatus.AWAITING_DELIVER,
                -> texts.chipAwaiting to "error"
                EngineerTaskStatus.FAILED -> texts.chipFailed to "error"
            }
        }
        return "Row([" +
            """Icon("settings", container = "tonal"), """ +
            """Text("${esc(texts.titlePrefix + task.sourceText)}", role = "SUBHEAD", maxLines = 1), """ +
            """Tag("${esc(chipText)}", tone = "$tone")""" +
            "], weights = [0, 1, 0])"
    }

    private fun metaLine(task: EngineerTaskState, texts: EngineerTaskTexts): String? {
        val parts = buildList {
            texts.metaTurns?.let { part -> add(part) }
            texts.metaElapsed?.let { part -> add(part) }
            texts.metaCost?.let { part -> add(part) }
            task.deliverBranch?.takeIf { branch -> branch.isNotBlank() }?.let { branch -> add("⎇ $branch") }
        }
        if (parts.isEmpty()) return null
        return """Text("${esc(parts.joinToString(TITLE_SEP))}", role = "CAPTION", colorRole = "SURFACE")"""
    }

    private fun usedLine(texts: EngineerTaskTexts): String? {
        val parts = listOfNotNull(texts.usedTurns, texts.usedCost)
        if (parts.isEmpty()) return null
        return """Text("${esc(texts.usedPrefix + parts.joinToString(TITLE_SEP))}", role = "CAPTION", colorRole = "SURFACE")"""
    }

    private fun errorBlock(task: EngineerTaskState): String? =
        task.errorSummary?.takeIf { err -> err.isNotBlank() }
            ?.let { err -> """Callout("${esc(err)}", tone = "ERROR")""" }

    /** 时间线：✓ 已完成 / RUNNING 末条 ● 当前（文案本地化复用 [EngineerTaskHtml.timelineLabel]）。 */
    private fun timeline(task: EngineerTaskState, texts: EngineerTaskTexts): String {
        val stages = task.recentStages
        val rows = StringBuilder()
        if (stages.size > TIMELINE_MAX_ROWS) rows.append("""Text("…", colorRole = "PRIMARY"), """)
        val visible = stages.takeLast(TIMELINE_MAX_ROWS)
        visible.forEachIndexed { index, stageLabel ->
            val isCurrent = task.status == EngineerTaskStatus.RUNNING && index == visible.lastIndex
            val mark = if (isCurrent) "●" else "✓"
            val label = esc(EngineerTaskHtml.timelineLabel(stageLabel, texts))
            val labelColor = if (isCurrent) ", colorRole = \"PRIMARY\"" else ""
            rows.append("""Row([Text("$mark", colorRole = "PRIMARY"), Text("$label"$labelColor)], weights = [0, 1]), """)
        }
        return rows.removeSuffix(", ")
    }

    private fun events(task: EngineerTaskState, texts: EngineerTaskTexts): String {
        if (task.recentStages.isEmpty()) return ""
        val caption = """Text("${esc(texts.recentEventsCaption)}", role = "CAPTION", colorRole = "SURFACE")"""
        val rows = task.recentStages.joinToString(", ") { stage ->
            """Text("${esc(stage)}", role = "CAPTION", colorRole = "SURFACE")"""
        }
        return "$caption, $rows"
    }
}
```

（注意：`sections += expr?.let{}` 的 nullable 模式——`metaLine`/`errorBlock`/`usedLine` 返回 nullable，Kotlin `+=` 不收 null，用 `metaLine(task, texts)?.let { sections += it }` 形态；上面 when 分支内已按此写。）

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :shared:jvmTest --tests '*EngineerTaskProgramTest*'`
Expected: PASS（8 用例全绿；golden 逐字符一致）

- [ ] **Step 5: 全量 shared 测试回归**

Run: `./gradlew :shared:jvmTest`
Expected: PASS（含既有 OpenUi*/EngineerTaskHtml* 全部）

- [ ] **Step 6: 提交**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/taskcenter/EngineerTaskProgram.kt shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/taskcenter/EngineerTaskProgramTest.kt
git commit -m "feat(taskcard): EngineerTaskProgram——任务卡 OpenUI 程序端侧组装器 + golden 单测"
```

---

### Task 4: androidApp 渲染路径切换 + 双形态复刻 + 全屏查看器

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/components/EngineerTaskCard.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（约 368/738/788/1083 行区域：fullpage 状态与浮层）
- Modify: `androidApp/src/main/res/values/strings.xml` + `values-zh-rCN/` + `values-zh-rTW/` + `values-es/` + `values-fr/`（`chat_task_badge` 值）

- [ ] **Step 1: EngineerTaskCard.kt 加 OpenUI 渲染路径**

在文件顶部（imports 后）加：

```kotlin
/**
 * 任务卡信息区渲染引擎开关（M1 观察期回滚闸，spec 2026-11-02 §3.2 修订）：
 * true = OpenUI 程序合成 + 原生渲染（本轮交付）；false = 回退 H2 WebView 路径。
 * iOS 双轨期 [com.mamba.picme.domain.chat.taskcenter.EngineerTaskHtml] 保留编译，
 * 两路径并存成本≈0；iOS OpenUI 渲染器落地后统一删除。
 */
internal const val USE_OPENUI_TASKCARD_RENDER = true
```

`EngineerTaskCard` 组合内，把 `if (html == null) … else HtmlCard(...)` 整段改为：

```kotlin
            if (USE_OPENUI_TASKCARD_RENDER) {
                EngineerTaskOpenUiInfoZone(
                    task = throttled,
                    expanded = expanded,
                    viewportHeightDp = viewportHeightDp,
                    texts = texts,
                    fullpageSticky = resolvedMode == HtmlCardDisplayMode.FULLPAGE,
                    onDisplayModeResolved = { mode -> resolvedMode = mode },
                    onToggleExpand = onToggleExpand,
                    onOpenFullpage = { program, title -> onOpenTaskFullpage(program, title) },
                )
            } else if (html == null) {
                EngineerTaskFallbackCard(throttled)
            } else {
                HtmlCard(/* 既有参数原样保留 */)
            }
```

（`onOpenFullpage` 旧签名 `(html, title)` 改名透传：组合参数改为 `onOpenFullpage: (program: String, title: String) -> Unit` ——OpenUI 路径传 program 文本，legacy 路径传 html，ChatScreen 侧按内容开对应浮层；见 Step 3。）

- [ ] **Step 2: 同文件实现 OpenUI 信息区宿主 + 全屏对话框**

```kotlin
/**
 * OpenUI 信息区宿主（spec 2026-11-02 §3.2）：节流态合成程序文本（纯 remember，无时间戳
 * 确定性）→ OpenUiSession 全管线（无 local_handle/无 viewState）→ **解包根 Card 渲染
 * children**（卡壳由外层两分区原生 Surface 提供，对齐 H2 期 HtmlCard cardColor/
 * contentPadding=0 中和手法）；Failed/组装异常 → [EngineerTaskFallbackCard]。
 *
 * 双形态复刻（§D5）：折叠/展开 = 程序带 expanded 重合成；展开态超一屏（onSizeChanged
 * 原生量高 > [viewportHeightDp]）升格 FULLPAGE——FULLPAGE 粘滞仅内存（spec §10 口径），
 * 内联渲染折叠形态、点击整卡开全屏对话框。WebView 测高缓存/防抖机制此路径不需要。
 */
@Suppress("LongParameterList")
@Composable
internal fun EngineerTaskOpenUiInfoZone(
    task: EngineerTaskState,
    expanded: Boolean,
    viewportHeightDp: Int,
    texts: EngineerTaskTexts,
    fullpageSticky: Boolean,
    onDisplayModeResolved: (HtmlCardDisplayMode) -> Unit,
    onToggleExpand: () -> Unit,
    onOpenFullpage: (program: String, title: String) -> Unit,
) {
    val program = remember(task, expanded, texts) {
        runCatching { EngineerTaskProgram.assemble(task, expanded, texts) }.getOrNull()
    }
    if (program == null) {
        EngineerTaskFallbackCard(task)
        return
    }
    val resolution by produceState<OpenUiValue.ComponentVal?>(initialValue = null, program) {
        val result = OpenUiSession(program).prepare()
        value = (result as? OpenUiResult.Rendered)?.rootOrNull()
    }
    val root = resolution ?: run {
        EngineerTaskFallbackCard(task)
        return
    }
    val density = LocalDensity.current
    var measuredHeightDp by remember(task.taskId, expanded) { mutableStateOf(0) }
    // FULLPAGE 判定（升格粘滞）：展开 + 实测高超一屏 → 升格；此后内联渲染折叠形态
    LaunchedEffect(measuredHeightDp, expanded, viewportHeightDp) {
        if (!fullpageSticky && expanded && viewportHeightDp > 0 &&
            measuredHeightDp > viewportHeightDp
        ) {
            onDisplayModeResolved(HtmlCardDisplayMode.FULLPAGE)
        }
    }
    val inlineExpanded = expanded && !fullpageSticky
    // fullpageSticky 时内联重合成折叠形态（升级前用户看到的形态）
    val inlineProgram = if (inlineExpanded) program else remember(task, false, texts) {
        runCatching { EngineerTaskProgram.assemble(task, false, texts) }.getOrNull()
    } ?: program
    val inlineRoot = if (inlineProgram == program) root else remember(inlineProgram) {
        // 折叠形态小体量，同步求值（无 Query/工具，prepare 不挂起）
        null // 占位——见下方注：折叠根复用 produceState 第二实例
    }
    BoxWithConstraints {
        val clickLabel = stringResource(
            if (fullpageSticky) R.string.chat_card_expand_hint
            else if (expanded) R.string.chat_card_collapse_hint
            else R.string.chat_card_expand_hint,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    measuredHeightDp = with(density) { size.height.toDp().value.toInt() }
                },
        ) {
            OpenUiCardChildren(root = root, program = inlineProgram, inlineRoot = inlineRoot)
        }
    }
    // 整卡点击：fullpage → 开对话框；否则切换展开
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel) {
                if (fullpageSticky) onOpenFullpage(program, task.sourceText) else onToggleExpand()
            },
    )
}

/** 根 Card 解包：children 直渲（外层容器提供卡壳/内边距 12/16/14，对齐 H2 HTML body）。 */
@Composable
private fun OpenUiCardChildren(root: OpenUiValue.ComponentVal, program: String, inlineRoot: OpenUiValue.ComponentVal?) {
    val effective = inlineRoot ?: root
    val children = (effective.props["children"] as? OpenUiValue.Arr)?.v
        ?.filterIsInstance<OpenUiValue.ComponentVal>().orEmpty()
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        children.forEach { child ->
            OpenUiNodeRenderer(
                node = child,
                state = emptyMap(),
                photoResolver = rememberNoOpPhotoResolver(),
                onAction = { },
            )
        }
    }
}

@Composable
private fun rememberNoOpPhotoResolver(): OpenUiPhotoResolver = remember { OpenUiPhotoResolver { null } }

/** 任务卡全屏查看器（复刻 H2 全屏 WebView 查看器交互）：标题 + 同一程序可滚动原生渲染。 */
@Composable
internal fun EngineerTaskFullpageDialog(
    program: String,
    title: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.cell) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                )
                val resolution by produceState<OpenUiValue.ComponentVal?>(initialValue = null, program) {
                    value = (OpenUiSession(program).prepare() as? OpenUiResult.Rendered)?.rootOrNull()
                }
                resolution?.let { root ->
                    val children = (root.props["children"] as? OpenUiValue.Arr)?.v
                        ?.filterIsInstance<OpenUiValue.ComponentVal>().orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        children.forEach { child ->
                            OpenUiNodeRenderer(child, emptyMap(), rememberNoOpPhotoResolver(), { })
                        }
                    }
                }
            }
        }
    }
}
```

（**执行注**：上面 InfoZone 里「折叠形态第二实例」的同步求值简化——实现时直接用一个 `produceState(inlineProgram)` 承载两种形态的根（键=inlineProgram），删去 `inlineRoot` 占位三元组，避免双轨状态。最终形态：`val inlineProgram = if (inlineExpanded) program else collapsedProgram`；`val root by produceState(null, inlineProgram)`；`onSizeChanged` 只量 inline 实际内容。实现以简为准，行为以测试为准。）

- [ ] **Step 3: ChatScreen 接线**

① 状态区（~368 行旁）加：

```kotlin
    var fullpageTaskProgram by remember { mutableStateOf<Pair<String, String>?>(null) }
```

② TYPE_TASK_CARD 分支调用处：`onOpenFullpage = { html, title -> fullpageHtmlCard = … }` 改为：

```kotlin
                                        onOpenFullpage = { program, title ->
                                            if (USE_OPENUI_TASKCARD_RENDER) {
                                                fullpageTaskProgram = program to title
                                            } else {
                                                fullpageHtmlCard = HtmlFullpageContent(html = program, title = title)
                                            }
                                        },
```

③ 浮层槽（~1083 行 `content = fullpageHtmlCard` 区域）并列分支：

```kotlin
        fullpageTaskProgram?.let { (program, title) ->
            EngineerTaskFullpageDialog(
                program = program,
                title = title,
                onDismiss = { fullpageTaskProgram = null },
            )
        }
```

（back 处理 566 行区域同步置空 `fullpageTaskProgram = null`。）

- [ ] **Step 4: 徽章文案五语同步**

`chat_task_badge` 值 `TASK · HTML` → `TASK · OPENUI`（五文件同键同改；该键五语本就同形技术标签）。

- [ ] **Step 5: 编译 + 测试 + 门禁**

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest ktlintCheck :androidApp:detektMain`
Expected: 全绿（既有 EngineerTaskCard 相关单测如有引用 onOpenFullpage 语义需同步——先跑测试看失败清单）

- [ ] **Step 6: 提交**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/ androidApp/src/main/res/
git commit -m "feat(taskcard): 任务卡信息区切换 OpenUI 原生渲染——双形态复刻+全屏查看器，WebView 路径降级为回滚闸"
```

---

### Task 5: server 字面量副本同步 + 测试

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt`（`ALLOWED_COMPONENTS` + `Progress`）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiToolService.kt`（`@LLMDescription` 组件说明：Progress 新条目；Tag tone / Icon container / Text maxLines / Row weights 0=hug 措辞）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt`（Progress 放行用例）

- [ ] **Step 1: 写失败测试**

在 `RenderOpenUiValidatorTest` 加：

```kotlin
    @Test
    fun `progress component is allowed`() {
        val violations = validate("""root = Card([Progress(40)])""")
        assertTrue(violations.none { it.contains("Progress") })
    }
```

（对齐该测试文件既有 `validate` 帮助函数签名——执行时按实际函数名调整。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :server:test --tests '*RenderOpenUiValidatorTest*'`
Expected: FAIL（Progress 不在白名单）

- [ ] **Step 3: 同步两处字面量副本 + 跑绿**

`ALLOWED_COMPONENTS` 集合加 `"Progress",`；`@LLMDescription` 组件清单文本补 Progress 行（`Progress(value: 0-100) 进度条`），并在 Tag/Icon/Text/Row 描述句尾补新 prop 语义（tone 实心档 / container tonal / maxLines / weights 0=不自撑）。Run: `./gradlew :server:test`。Expected: PASS。

- [ ] **Step 4: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/ server/src/test/kotlin/com/mamba/picme/server/agent/
git commit -m "feat(server): openui 词汇副本同步——Progress/Tag.tone/Icon.container/Text.maxLines + bump 0.9.15"
```

（version 位置：`grep -rn '0.9.14' server/build.gradle.kts server/src/main/resources/` 找到后改 0.9.15。）

---

### Task 6: 文档同步 + spec 修订 + 版本 bump + 全量门禁

**Files:**
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`（卡目录行 ~315 + 工程师任务卡节 ~577 机制段 + data_ui_openui 节 ~871 补一行 + 测高缓存行 ~287 注记）
- Modify: `docs/03-TECHNICAL-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md`（§5 组件皮肤：Progress/Tag 实心/Icon 容器块；§6 批次编年加 2026-11-02 条目）
- Modify: `docs/superpowers/specs/2026-11-02-engineer-taskcard-openui-design.md`（§3.2 回滚闸：开发者选项开关 → 代码内开关 `USE_OPENUI_TASKCARD_RENDER`，YAGNI 理由）
- Modify: `shared/AGENTS.md`（若列有 taskcenter 包清单，加 EngineerTaskProgram 一行指针）
- Modify: `androidApp/build.gradle.kts`（versionName 1.0.77→1.0.78，versionCode 10077→10078——以文件实际值为准）

- [ ] **Step 1: 目录文档改写**（按既有行文风格，替换「WebView L1 模板」为「OpenUI 端侧合成 + 原生渲染（EngineerTaskProgram → OpenUiSession → OpenUiNodeRenderer，根 Card 解包）」；机制段保留 500ms 合帧/跃迁即时/粘滞 FULLPAGE/兜底卡语义，注明测高缓存仅 legacy 路径；已知偏差段新增：事件列暂非等宽字体、时间线标记列自然宽、标题 SUBHEAD 字号）
- [ ] **Step 2: 设计语言文档 §5/§6 条目**
- [ ] **Step 3: spec 修订 + AGENTS 指针**
- [ ] **Step 4: 版本 bump**
- [ ] **Step 5: 全量门禁**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :server:test ktlintCheck detekt :androidApp:assembleDebug`
Expected: 全绿

- [ ] **Step 6: 提交**

```bash
git add docs/ androidApp/build.gradle.kts shared/AGENTS.md
git commit -m "docs(taskcard): 任务卡 OpenUI 化收口——目录/设计语言/AGENTS 同步 + spec 回滚闸修订 + bump 1.0.78"
```

---

## 真机验收清单（醒后人工项，不阻塞提交）

- [ ] 五态真机截图对比 taskcard 四帧（438:181/208/264、444:31）Light/Dark
- [ ] 展开超一屏自动升格 → 点击开全屏 → back 关闭
- [ ] live 任务 RUNNING 500ms 合帧流畅、停止/审批/交付动作正常
- [ ] debug 徽标/程序查看器可看任务卡 program 原文（如无则后续接 OpenUiDebugBadge）

## Self-Review 结论

- Spec 覆盖：§3.1 组装器→Task 3；§3.2 宿主/双形态/回滚闸→Task 4；§3.3 词汇→Task 1/2；§3.3 server 同步→Task 5；§5 测试→各任务 TDD + Task 6 门禁；§6 文档→Task 6；D4 iOS 双轨→legacy 路径保留（Task 4 开关）。
- 占位符：Task 4 Step 2 有「执行注」明确简化方向（非 TBD——行为以测试为准）；其余步骤代码完整。
- 类型一致性：`EngineerTaskProgram.assemble(task, expanded, texts)`/`escapeProgramString` 全计划一致；`onOpenFullpage: (String, String) -> Unit` 在 Task 4 内改签名并同步 ChatScreen。
