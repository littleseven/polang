# OpenUI 回合原生（turn-native）M1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenUI 线从「工具发卡」改为「响应即程序」——LLM 回复内容本身即 OpenUI Lang 程序，content delta 流式直驱端上前缀渲染，ProgramGuard 校验回环 + 兜底 Markdown 包装保证端上永远收到合法树；A2UI 线零改动保留为回退闸。

**Architecture:** server（prompt 契约 + ProgramGuard + content 帧透传 + 兜底包装）→ SSE（`openui_delta` 帧语义升级 + Message 事件权威负载）→ androidApp（TurnRunner message 解析 + 既有 P2 预览/落库/渲染管线 + Html 组件叶子）。spec：`docs/superpowers/specs/2026-11-01-openui-turn-native-design.md`。

**Tech Stack:** Kotlin / Koog 1.3.0（AIAgent + EventHandler + StreamFrame）/ Ktor SSE / kotlinx.serialization / Jetpack Compose / shared KMP commonMain（纯 kotlin.\*/kotlinx.\*）。

**工作区**：worktree `.worktrees/openui-turn-native`，分支 `feat/openui-turn-native`（已建）。所有命令在该目录执行。

**已验证的关键事实（实现者不必重查）：**
- `StreamFrame.TextDelta`（属性 `.text`）在 Koog 1.3.0 存在，shared `KoogChatAgent.kt:242` 已在用；`events.onLLMStreamingStarting` 轮次开始钩子（`KoogChatAgent.kt:233`）同样存在，server EventHandler 同版本可用。
- server 策略层所有 LLM 节点已走 `requestLLMStreamingOrOnce()`（`CloudAgentStrategy.kt:27`），content 帧已在流内，只需透传。
- 纠正回环节点先例：`correctFakeCardClaim`（`CloudAgentStrategy.kt:80`），`CardClaimGuard.MAX_CORRECTIONS=1`。
- 校验链：`RenderOpenUiValidator.validate(program): List<String>` / `validateToError(program): String?` + `OpenUiLayoutNormalizer.normalize(program): String` + `OpenUiLayoutValidator.validate(normalized): List<…>` + `OpenUiLayoutValidator.errorText(violations, program): String`。
- `ServerToolService` 在 `server/.../agent/ServerTools.kt:28`，含 `server_time` 与 `render_html` 两个 @Tool。
- 端上 `openui_delta` 帧解析已存在（`CloudAgentClient.kt:61`），TurnRunner 已有 `genUiFormat` 参数（`CloudAgentTurnRunner.kt:70`）。
- `HtmlCard` composable：`androidApp/.../features/chat/HtmlCard.kt:155`，参数 `html: String, modifier, meta=null, …` 全部有缺省。

---

### Task 1: shared — OpenUiLibrary 注册 Html 组件

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibrary.kt`（`components` 清单，约 :1462 AvatarRow 声明之后）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibraryM2Test.kt`（或新增 `OpenUiLibraryHtmlTest.kt`，取其一，跟随既有测试文件组织）

- [ ] **Step 1: 写失败测试**

在 `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/openui/` 新增 `OpenUiLibraryHtmlTest.kt`：

```kotlin
package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenUiLibraryHtmlTest {

    @Test
    fun htmlComponentRegistered() {
        val html = OpenUiLibrary.components["Html"]
        assertNotNull(html, "Html 组件未注册")
        assertEquals(2, html.props.size)
        assertEquals("html", html.props[0].name)
        assertEquals(OpenUiPropType.STRING, html.props[0].type)
        assertTrue(html.props[0].required)
        assertEquals("display", html.props[1].name)
        assertEquals(OpenUiPropType.ENUM, html.props[1].type)
        assertEquals(OpenUiValue.Str("INLINE"), html.props[1].default)
    }

    @Test
    fun htmlComponentValidates() {
        // 合法：必填 html 字符串
        val ok = OpenUiLibrary.validateProps("Html", listOf(OpenUiValue.Str("<b>hi</b>")))
        assertTrue(ok.isEmpty(), "合法 Html 调用被误杀: $ok")
        // 非法：缺必填
        val missing = OpenUiLibrary.validateProps("Html", emptyList())
        assertTrue(missing.isNotEmpty(), "缺必填 html 未被拦截")
    }
}
```

> 注：`OpenUiLibrary.validateProps` 的确切函数名以 `OpenUiLibrary.kt` 中既有校验入口为准（M2 测试文件里有现成调用形态，照抄其调用方式替换组件名/参数即可）。若既有测试用的是别的入口名，用它。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd .worktrees/openui-turn-native && JITPACK=true ./gradlew :shared:jvmTest --tests "*OpenUiLibraryHtmlTest*"`
Expected: 编译失败或断言失败（Html 未注册）

- [ ] **Step 3: 注册 Html 组件**

在 `OpenUiLibrary.kt` 的 `components` 清单中（AvatarRow 声明块之后、布局可控性批次注释之前），照 Divider/PhotoGrid 的声明先例插入：

```kotlin
        // Html：L2 沙箱卡入树（turn-native，spec《2026-11-01-openui-turn-native-design》§3.6）——
        // 完整自包含 HTML 字符串，端侧 WebView 沙箱渲染（网络默认关死、dispatch 白名单）；
        // display=INLINE（缺省，限高约 320dp）/ FULLPAGE（点击弹全屏查看器）
        OpenUiComponent(
            "Html",
            listOf(
                OpenUiProp("html", OpenUiPropType.STRING, required = true),
                OpenUiProp(
                    "display",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("INLINE"),
                    enumValues = listOf("INLINE", "FULLPAGE"),
                ),
            ),
        ),
```

> 注：`OpenUiProp` 的 enum 参数名（`enumValues`/`values`）以 Divider `axis` prop 的既有声明逐字对齐——先 grep `OpenUiPropType.ENUM` 看一个真实声明再写。

- [ ] **Step 4: 跑测试确认通过 + commonMain 纯度**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*OpenUiLibraryHtmlTest*" && JITPACK=true ./gradlew :shared:assemble`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibrary.kt shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/openui/OpenUiLibraryHtmlTest.kt
git commit -m "feat(shared): OpenUI 目录注册 Html 组件（L2 沙箱入树，turn-native T1）"
```

---

### Task 2: server — RenderOpenUiValidator 白名单 +Html 与回合原生校验入口

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt`

- [ ] **Step 1: 写失败测试**

在 `RenderOpenUiValidatorTest.kt` 追加：

```kotlin
    @Test
    fun `Html component allowed in whitelist`() {
        val program = "root = Card([Html(\"<p>hi</p>\")])"
        assertNull(RenderOpenUiValidator.validateToError(program))
    }

    @Test
    fun `validateProgramToError returns violations for invalid program`() {
        val error = RenderOpenUiValidator.validateProgramToError("这是一段没有 root 的纯文本")
        assertNotNull(error)
        assertTrue(error.contains("缺 root"))
        // 回合原生文案：不再指引 LLM 调用工具
        assertTrue(error.contains("重新输出完整程序"), "错误文案仍是工具口径: $error")
    }

    @Test
    fun `validateProgramToError passes valid program and applies layout gate`() {
        val valid = "root = Card([Text(\"标题\", \"HEADLINE\")])"
        assertNull(RenderOpenUiValidator.validateProgramToError(valid))
        // 布局门禁违规（Grid 内 ≥2 Image，R4）同样被回合原生入口拦截
        val layoutBad = """
            root = Card([Grid([Image(photos[0].id), Image(photos[1].id)], columns = 2)])
        """.trimIndent()
        assertNotNull(RenderOpenUiValidator.validateProgramToError(layoutBad))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*RenderOpenUiValidatorTest*"`
Expected: 编译失败（`validateProgramToError` 未定义）

- [ ] **Step 3: 实现**

`RenderOpenUiValidator.kt` 两处修改：

① `ALLOWED_COMPONENTS` 集合中（"ChartGrid" 之后）加 `"Html"`，并在 KDoc 批次记录后追加一句：`2026-11-01 turn-native：+Html（L2 沙箱入树，spec§3.6）。`

② 错误文本参数化 + 新入口。把私有 `errorText` 改为带重试指引参数（保持既有默认，零行为变化），并新增 `validateProgramToError`：

```kotlin
    /**
     * 回合原生（turn-native）校验入口（spec§3.5 ProgramGuard 消费）：对完整响应程序
     * 做结构校验 + 布局门禁（先 normalize 再 validate，幂等同 validateArgsJson 链路）。
     * 错误文案指引「重新输出完整程序」（响应即程序，无工具可回调）。
     */
    fun validateProgramToError(program: String): String? {
        validateToError(program, turnNative = true)?.let { return it }
        val layoutViolations = OpenUiLayoutValidator.validate(OpenUiLayoutNormalizer.normalize(program))
        if (layoutViolations.isNotEmpty()) return OpenUiLayoutValidator.errorText(layoutViolations, program)
        return null
    }
```

`validateToError` 与 `errorText` 改造：

```kotlin
    /** 校验并直接给回错误文本：null = 合法放行。 */
    fun validateToError(program: String, turnNative: Boolean = false): String? =
        validate(program).takeIf { violations -> violations.isNotEmpty() }
            ?.let { violations -> errorText(violations, program, turnNative) }

    private fun errorText(
        violations: List<String>,
        program: String = "",
        turnNative: Boolean = false,
    ): String = buildString {
        if (turnNative) {
            append("error: 你的回复（整颗 OpenUI 程序）校验未通过。逐条修正后重新输出完整程序：")
        } else {
            append("error: render_ui_openui 负载校验未通过，本次未下发端侧。逐条修正后重新调用 render_ui_openui：")
        }
        violations.forEach { violation -> append("\n- ").append(violation) }
        val preview = program.replace("\n", "\\n").take(120)
        append("\n本次收到 program 开头：").append(preview)
        if (violations.any { it.startsWith("缺 root") } && program.contains("root")) {
            append("（注意：你的 program 里出现了 root 但不在行首/被包裹——root = 必须独占一行行首，且 program 不要包 markdown 代码围栏）")
        }
    }
```

同时把 whitelist 违规文案里的「100 组件全量清单见 render_ui_openui 工具描述」改为「组件全量清单见系统提示词」（该文案在 turn-native 下才可见，A2UI 线不用本校验器，直接改安全）。

- [ ] **Step 4: 跑测试确认通过（含既有用例不回归）**

Run: `./gradlew -p server test --tests "*RenderOpenUiValidatorTest*"`
Expected: PASS。若既有同源钉住测试（shared 组件清单副本比对类）失败，按 Task 1 已加 Html 的事实更新被钉住的字面量副本预期值。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidator.kt server/src/test/kotlin/com/mamba/picme/server/agent/RenderOpenUiValidatorTest.kt
git commit -m "feat(server): OpenUI 校验器 +Html 白名单与回合原生校验入口（T2）"
```

---

### Task 3: server — OpenUiContentStreamTracker（content 帧 → program 前缀节流发射）

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiContentStreamTracker.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiContentStreamTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.server.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenUiContentStreamTrackerTest {

    private var now = 0L
    private fun tracker() = OpenUiContentStreamTracker(
        minEmitChars = 10,
        minEmitIntervalMs = 100,
        now = { now },
    )

    @Test
    fun `accumulates deltas and emits throttled prefix`() {
        val t = tracker()
        assertNull(t.onTextDelta("root = "))           // 7 < 10 不发射
        val emitted = t.onTextDelta("Card([Text(\"hi\")])")
        assertEquals("root = Card([Text(\"hi\")])", emitted) // 累计 ≥10 发射全量前缀
    }

    @Test
    fun `round start resets buffer`() {
        val t = tracker()
        t.onTextDelta("root = Card([Text(\"hi\")])")
        t.onRoundStart()
        assertNull(t.onTextDelta("short"))             // 重置后重新累计
        assertEquals("short_long_enough", t.onTextDelta("_long_enough"))
    }

    @Test
    fun `interval throttling suppresses rapid emits`() {
        val t = tracker()
        t.onTextDelta("0123456789")                    // 首发
        now = 50                                        // 距首发 < 100ms
        assertNull(t.onTextDelta("abcdefghij"))         // 间隔抑制
        now = 200
        assertEquals("0123456789abcdefghij", t.onTextDelta(""))
    }

    @Test
    fun `no re-emit when prefix not advanced`() {
        val t = tracker()
        t.onTextDelta("0123456789")
        now = 200
        assertNull(t.onTextDelta(""))                   // 前缀未推进不重复发
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*OpenUiContentStreamTrackerTest*"`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

```kotlin
package com.mamba.picme.server.agent

/**
 * 回合原生（turn-native）content 流 → 响应 program 前缀的节流发射器（spec§3.2）。
 *
 * 语义对齐 [OpenUiToolCallStreamTracker]（≥[minEmitChars] 增量且距上次 ≥[minEmitIntervalMs]
 * 才发射；前缀未推进不重复发），但跟踪对象从「工具 args JSON 增量」换成「content
 * TextDelta 纯文本累计」——响应即程序，无需 JSON 转义剥离。每轮 LLM 流式开始
 * （EventHandler onLLMStreamingStarting）调 [onRoundStart] 重置：回合内多轮 LLM 输出
 * （中间迷你程序 → 终结整树）各自从空累计，端上覆盖式替换预览。
 */
internal class OpenUiContentStreamTracker(
    private val minEmitChars: Int = 48,
    private val minEmitIntervalMs: Long = 80,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val buffer = StringBuilder()
    private var lastEmittedLen = 0
    private var lastEmitAt = 0L
    private var hasEmitted = false

    /** 新一轮 LLM 流式开始：本轮前缀从空重新累计（端上预览覆盖式替换）。 */
    fun onRoundStart() {
        buffer.setLength(0)
        lastEmittedLen = 0
        lastEmitAt = 0L
        hasEmitted = false
    }

    /** @return 应发射的 program 前缀；本帧不触发发射 → null。 */
    fun onTextDelta(text: String): String? {
        buffer.append(text)
        val prefix = buffer.toString()
        return if (shouldEmit(prefix.length)) emit(prefix) else null
    }

    private fun shouldEmit(prefixLen: Int): Boolean =
        prefixLen - lastEmittedLen >= minEmitChars &&
            (!hasEmitted || now() - lastEmitAt >= minEmitIntervalMs)

    private fun emit(prefix: String): String {
        lastEmittedLen = prefix.length
        lastEmitAt = now()
        hasEmitted = true
        return prefix
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "*OpenUiContentStreamTrackerTest*"`
Expected: PASS（4 例）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiContentStreamTracker.kt server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiContentStreamTrackerTest.kt
git commit -m "feat(server): OpenUI 回合原生 content 流节流发射器（T3）"
```

---

### Task 4: server — AgentRunner content 帧透传 + 终结消息变换

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/AgentRunner.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/AgentRunnerTest.kt`

- [ ] **Step 1: 写失败测试**

在 `AgentRunnerTest.kt` 追加（fake SSE/MockLlmSse 基建既有，参照文件内 openui 预览用例的接线方式）：

```kotlin
    @Test
    fun `turn-native forwards content deltas as program prefix`() = runTest {
        // 假 LLM 流式产出 content（无工具调用），断言 onOpenUiProgramDelta 收到
        // 递增前缀且终结消息经 finalMessageTransformer 变换
        val deltas = mutableListOf<String>()
        val events = runTurnCollecting(
            sseBody = MockLlmSse.streamingText("root = Card([Text(\"hi\")])"),
            forwardContentDeltas = true,
            finalMessageTransformer = { "TRANSFORMED:$it" },
            onDelta = { deltas += it },
        )
        assertTrue(deltas.isNotEmpty(), "content delta 未透传")
        assertTrue(deltas.last().endsWith("Card([Text(\"hi\")])"))
        val finalMessage = events.filterIsInstance<AgentEvent>()
            .map { it.payload }.filterIsInstance<AgentEventPayload.Message>().last()
        assertTrue(finalMessage.content.startsWith("TRANSFORMED:"))
    }

    @Test
    fun `default mode does not forward content deltas`() = runTest {
        val deltas = mutableListOf<String>()
        runTurnCollecting(
            sseBody = MockLlmSse.streamingText("plain answer"),
            forwardContentDeltas = false,
            onDelta = { deltas += it },
        )
        assertTrue(deltas.isEmpty(), "默认模式不应透传 content delta")
    }
```

> 注：`runTurnCollecting`/`MockLlmSse.streamingText` 是计划级示意名——按 `AgentRunnerTest.kt` / `MockLlmSse.kt` 既有的 fake 组织方式落地（文件里已有 ToolCallDelta 流式用例，content 流式照其 SSE chunk 形态把 `tool_calls` 换成 `content` 字段增量）。若 MockLlmSse 尚无 content 流式 helper，在 MockLlmSse 里加一个（OpenAI SSE `delta: {"content": "…"}` 分片）。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*AgentRunnerTest*"`
Expected: 编译失败（runTurn 无新参数）

- [ ] **Step 3: 实现 AgentRunner 改造**

`AgentRunner.kt`：

① `runTurn` 签名加两个参数（放在 `onOpenUiProgramDelta` 之前，保持调用点可读）：

```kotlin
    suspend fun runTurn(
        session: AgentSession,
        userMessage: String,
        target: AgentLlmTarget,
        systemPrompt: String,
        toolRegistry: ToolRegistry,
        /** 回合原生（openui turn-native）：透传 content TextDelta 为 program 前缀预览。 */
        forwardContentDeltas: Boolean = false,
        /** 终结消息落流前变换（openui turn-native：normalize + 兜底 Markdown 包装）。 */
        finalMessageTransformer: (String) -> String = { it },
        onHtmlCard: suspend (html: String, summary: String) -> Unit = { _, _ -> },
        onOpenUiProgramDelta: suspend (programPrefix: String) -> Unit = {},
        onEvent: suspend (AgentEvent) -> Unit = {},
    ): AgentEvent {
```

② runTurn 内（`openUiStreamTracker` 创建处旁边）新建 content tracker：

```kotlin
        // 回合原生 content 流跟踪器（每轮新实例；仅 forwardContentDeltas 时发射）
        val contentStreamTracker = OpenUiContentStreamTracker()
```

③ EventHandler install 块内，`onLLMStreamingFrameReceived` 的 `when` 前加轮次重置钩子，`when` 加 TextDelta 分支：

```kotlin
                    events.onLLMStreamingStarting {
                        // 新一轮 LLM 流式（工具调用后的下一轮）：前缀从空重新累计
                        // （shared KoogChatAgent.kt:233 同款钩子，覆盖式预览的轮边界）
                        contentStreamTracker.onRoundStart()
                    }
                    events.onLLMStreamingFrameReceived { ctx ->
                        val prefix = when (val frame = ctx.streamFrame) {
                            is StreamFrame.ToolCallDelta -> openUiStreamTracker.onDelta(
                                frame.id, frame.name, frame.content, frame.index,
                            )
                            is StreamFrame.ToolCallComplete -> openUiStreamTracker.onComplete(
                                frame.id, frame.index,
                            )
                            is StreamFrame.TextDelta ->
                                if (forwardContentDeltas) contentStreamTracker.onTextDelta(frame.text) else null
                            else -> null
                        }
                        if (prefix != null) {
                            runBlocking { onOpenUiProgramDelta(prefix) }
                        }
                    }
```

④ 终结消息落流处（`AgentEventPayload.Message(result)`）改为：

```kotlin
                payload = AgentEventPayload.Message(finalMessageTransformer(result)),
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "*AgentRunnerTest*"`
Expected: PASS（新旧用例全绿——旧用例走默认参数零行为变化）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/AgentRunner.kt server/src/test/kotlin/com/mamba/picme/server/agent/AgentRunnerTest.kt server/src/test/kotlin/com/mamba/picme/server/agent/MockLlmSse.kt
git commit -m "feat(server): AgentRunner content 帧透传与终结消息变换（turn-native T4）"
```

---

### Task 5: server — ProgramGuard（终结程序校验回环守卫）

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/ProgramGuard.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/ProgramGuardTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.server.agent

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramGuardTest {

    @Test
    fun `valid program passes without correction`() {
        val guard = ProgramGuard()
        assertNull(guard.invalidProgramError("root = Card([Text(\"hi\")])"))
    }

    @Test
    fun `invalid program triggers correction with error details`() {
        val guard = ProgramGuard()
        val error = guard.invalidProgramError("没有 root 的文字")
        assertNotNull(error)
        assertTrue(error.contains("缺 root"))
        assertTrue(ProgramGuard.correctionPrompt(error).contains("重新输出完整程序"))
    }

    @Test
    fun `corrections exhausted after MAX_CORRECTIONS`() {
        val guard = ProgramGuard()
        repeat(ProgramGuard.MAX_CORRECTIONS) {
            assertNotNull(guard.invalidProgramError("bad"))
            guard.markCorrected()
        }
        // 上限用尽：放行（由终结消息变换兜底包装），不再纠正
        assertNull(guard.invalidProgramError("bad"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*ProgramGuardTest*"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package com.mamba.picme.server.agent

import java.util.concurrent.atomic.AtomicInteger

/**
 * 终结程序守卫（turn-native，spec§3.5——[CardClaimGuard] 的回合原生泛化）。
 *
 * OpenUI 回合原生线下，LLM 的整个终结回复必须是一颗合法 OpenUI 程序。策略层
 * onTextMessage 出边经 [invalidProgramError] 门控：非法 → correctInvalidProgram
 * 节点携带具体错误清单回环重发（上限 [MAX_CORRECTIONS] 次防死循环）；上限用尽
 * 放行，由 AgentRunner.finalMessageTransformer 兜底 Markdown 包装——
 * 「端上永远收到合法树」的双保险。
 *
 * 每轮一个新实例（[AgentRunner.runTurn] 内创建），无跨轮状态。
 */
internal class ProgramGuard {

    private val corrections = AtomicInteger(0)

    /** 终结文本是否需要纠正回环：非合法程序 + 纠正次数未达上限。null = 放行。 */
    fun invalidProgramError(assistantText: String): String? {
        if (corrections.get() >= MAX_CORRECTIONS) return null
        return RenderOpenUiValidator.validateProgramToError(assistantText)
    }

    fun markCorrected() {
        corrections.incrementAndGet()
    }

    companion object {
        /** 单轮纠正上限（对齐 CardClaimGuard 口径，防 LLM 坚持己见死循环）。 */
        const val MAX_CORRECTIONS = 1

        fun correctionPrompt(error: String): String =
            "系统校验：你的整个回复就是用户看到的界面，必须是一颗合法的 OpenUI Lang 1.0 程序" +
                "（不允许程序之外的任何文字，也不要包 markdown 代码围栏；纯文本回复写 root = Markdown(\"…\")）。" +
                "你上次的输出未通过校验，逐条修正后重新输出完整程序：\n$error"
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "*ProgramGuardTest*"`
Expected: PASS（3 例）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/ProgramGuard.kt server/src/test/kotlin/com/mamba/picme/server/agent/ProgramGuardTest.kt
git commit -m "feat(server): ProgramGuard 终结程序校验守卫（turn-native T5）"
```

---

### Task 6: server — CloudAgentStrategy 接线 ProgramGuard 纠正节点

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/CloudAgentStrategy.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/AgentRunnerTest.kt`（策略行为经 Runner 集成断言）

- [ ] **Step 1: 写失败测试（集成层）**

在 `AgentRunnerTest.kt` 追加：

```kotlin
    @Test
    fun `invalid final program triggers correction round then finish`() = runTest {
        // 假 LLM 第一轮输出非法文本、纠正后第二轮输出合法程序（MockLlmSse 序列响应基建
        // 参照既有 correctFakeCardClaim 用例的两段式响应组织）
        val events = runTurnCollecting(
            sseBody = MockLlmSse.sequence(
                MockLlmSse.streamingText("这不是程序"),
                MockLlmSse.streamingText("root = Card([Text(\"修正后\")])"),
            ),
            turnNative = true, // 计划级示意：等 Task 8 接线后等价于 header=openui 路径
        )
        val messages = events.map { it.payload }.filterIsInstance<AgentEventPayload.Message>()
        assertEquals("root = Card([Text(\"修正后\")])", messages.last().content)
        // 纠正消息落 prompt 不落事件流（纠正 user 消息只存在于 Koog prompt 内，同
        // correctFakeCardClaim 既有语义）——断言事件流无第二 Message
        assertEquals(1, messages.count { it.content != "这不是程序" })
    }

    @Test
    fun `persistently invalid program falls back to markdown wrap`() = runTest {
        val events = runTurnCollecting(
            sseBody = MockLlmSse.sequence(
                MockLlmSse.streamingText("坏程序"),
                MockLlmSse.streamingText("还是坏程序"),
            ),
            turnNative = true,
        )
        val final = events.map { it.payload }.filterIsInstance<AgentEventPayload.Message>().last()
        assertTrue(final.content.startsWith("// summary:"), "兜底包装缺失: ${final.content.take(80)}")
        assertTrue(final.content.contains("root = Markdown("))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*AgentRunnerTest*"`
Expected: FAIL（无纠正回环，非法文本直接落流）

- [ ] **Step 3: 实现策略层改造**

`CloudAgentStrategy.kt`：

① 签名与节点：

```kotlin
internal fun cloudAgentStrategy(
    cardGuard: CardClaimGuard,
    programGuard: ProgramGuard? = null,
): AIAgentGraphStrategy<String, String> =
    strategy("polang_cloud_single_run") {
        // ……既有 nodeCallLLM / nodeExecuteTool / nodeSendToolResult / nodeCorrectFakeCard 不动……

        // turn-native（spec§3.5）：终结程序非法 → 带错误清单回环纠正
        val nodeCorrectInvalidProgram by node<String, Message.Assistant>("correctInvalidProgram") { text ->
            programGuard!!.markCorrected()
            val error = RenderOpenUiValidator.validateProgramToError(text)
                ?: "未知校验错误（请检查 root 语句、组件白名单与 Query 形态）"
            llm.writeSession {
                appendPrompt {
                    user(ProgramGuard.correctionPrompt(error))
                }
                requestLLMStreamingOrOnce()
            }
        }
```

② 出边改造——`nodeCallLLM` 与 `nodeSendToolResult` 的文本出边按「程序校验优先、假发卡其次」顺序声明（programGuard 为 null 时新边条件恒 false，A2UI 线零变化）：

```kotlin
        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            nodeCallLLM forwardTo nodeCorrectInvalidProgram
                onTextMessage { true }
                onCondition { text -> programGuard?.invalidProgramError(text) != null },
        )
        edge(
            nodeCallLLM forwardTo nodeCorrectFakeCard
                onTextMessage { true }
                onCondition { cardGuard.needsCorrection(it) },
        )
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            nodeSendToolResult forwardTo nodeCorrectInvalidProgram
                onTextMessage { true }
                onCondition { text -> programGuard?.invalidProgramError(text) != null },
        )
        edge(
            nodeSendToolResult forwardTo nodeCorrectFakeCard
                onTextMessage { true }
                onCondition { cardGuard.needsCorrection(it) },
        )
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
        edge(nodeCorrectFakeCard forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCorrectFakeCard forwardTo nodeFinish onTextMessage { true })
        edge(nodeCorrectInvalidProgram forwardTo nodeExecuteTool onToolCalls { true })
        // 纠正后再输出仍非法：放行落流（finalMessageTransformer 兜底包装）
        edge(nodeCorrectInvalidProgram forwardTo nodeFinish onTextMessage { true })
    }
```

③ `AgentRunner.runTurn` 内策略构建点（`cloudAgentStrategy(cardGuard)`）改为按模式注入：

```kotlin
        // turn-native：ProgramGuard 仅 forwardContentDeltas 模式启用（两闸同头驱动）
        val programGuard = if (forwardContentDeltas) ProgramGuard() else null
        // ……
                .graphStrategy(cloudAgentStrategy(cardGuard, programGuard))
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test`
Expected: PASS（server 全量，含 A2UI 线假发卡既有用例零回归）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/CloudAgentStrategy.kt server/src/main/kotlin/com/mamba/picme/server/agent/AgentRunner.kt server/src/test/kotlin/com/mamba/picme/server/agent/AgentRunnerTest.kt
git commit -m "feat(server): 策略层 ProgramGuard 纠正回环（turn-native T6）"
```

---

### Task 7: server — 回合原生系统提示词 + 终结消息兜底变换

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiTurnNative.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiToolService.kt`（抽取 DSL 规则为可复用 const）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiTurnNativeTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.server.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenUiTurnNativeTest {

    @Test
    fun `ensureValidProgram normalizes and passes valid program through`() {
        val program = "root = Card([Text(\"hi\")])"
        assertEquals(program, OpenUiTurnNative.ensureValidProgram(program))
    }

    @Test
    fun `ensureValidProgram wraps invalid text into markdown fallback`() {
        val wrapped = OpenUiTurnNative.ensureValidProgram("就是一句 \"闲聊\"\n第二行")
        assertTrue(wrapped.startsWith("// summary:"))
        assertTrue(wrapped.contains("root = Markdown(\""))
        // 引号与换行已转义，程序可被引擎解析（结构级校验通过）
        assertNull(RenderOpenUiValidator.validateProgramToError(wrapped))
    }

    @Test
    fun `system prompt contains response-is-program contract and golden examples`() {
        val prompt = OpenUiTurnNative.SYSTEM_PROMPT
        assertTrue(prompt.contains("你的整个回复就是一颗 OpenUI Lang 1.0 程序"))
        assertTrue(prompt.contains("// summary:"))
        assertTrue(prompt.contains("root = "))
        // 黄金示例同源（改示例测试必红，防 prompt 与校验器脱节）
        assertTrue(prompt.contains(RenderOpenUiToolService.GOLDEN_STATS_PROGRAM))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*OpenUiTurnNativeTest*"`
Expected: 编译失败

- [ ] **Step 3: 实现**

① `RenderOpenUiToolService.kt` 抽取规则主体为 const（annotation 可拼接编译期常量）：把 @LLMDescription 中「约束（必须全部满足……）」到「时间轴黄金示例」的**规则正文**抽成 companion 内 `const val DSL_RULES`（逐字剪切），annotation 改为 `"""向用户下发一张 OpenUI 原生渲染卡……\n""" + DSL_RULES`。annotation 行为零变化（拼接结果逐字节相同——抽完先跑 `RenderOpenUiToolServiceTest` 验证）。

② 新建 `OpenUiTurnNative.kt`：

```kotlin
package com.mamba.picme.server.agent

/**
 * OpenUI 回合原生（turn-native，spec《2026-11-01-openui-turn-native-design》）：
 * 系统提示词契约 + 终结消息兜底变换。
 *
 * [SYSTEM_PROMPT]：响应即程序契约——DSL 规则与 [RenderOpenUiToolService.DSL_RULES]
 * 同源复用（零漂移），工具语境改写为响应语境（无工具可回调、// summary 首行、
 * 渲染顺序约定 §3.4、纯文本也必须是程序）。
 */
object OpenUiTurnNative {

    private const val D = "$"

    val SYSTEM_PROMPT: String =
        """你是 PoLang 云端 Agent。你的整个回复就是用户看到的界面——必须是一颗合法的
OpenUI Lang 1.0 程序：不允许程序之外的任何文字，不要包 markdown 代码围栏；
纯文本回复也必须写成程序（root = Markdown("…")）；闲聊一句话同样如此。
程序结构约定（边生成边渲染，骨架必须先立）：
1. 首行写 `// summary: <投影级一句话摘要>`（端上降级与历史折叠的数据源，
   摘要只含计数/结论，不含 handle id 与用户隐私细节）；
2. 第二行起 `root = ...` 声明独占一行行首（root 是组件树入口）；
3. 布局骨架组件（Column/Row/Card/Grid）先于数据组件书写；
4. Query 取数语句殿后（程序末尾），组件参数引用其变量名。
需要服务器时间时调用 server_time 工具；相册数据查询照旧调用搜索工具，
结果尾部的 handle 按数据禁令绑定进程序。
""" + RenderOpenUiToolService.DSL_RULES

    /**
     * 终结消息兜底变换（AgentRunner.finalMessageTransformer 消费，spec§3.2 双保险）：
     * 合法 → normalize 后原样放行；非法（ProgramGuard 纠正用尽）→ 兜底包成
     * Markdown 程序，端上永远收到合法树。
     */
    fun ensureValidProgram(text: String): String {
        val normalized = OpenUiLayoutNormalizer.normalize(text)
        if (RenderOpenUiValidator.validate(normalized).isEmpty() &&
            OpenUiLayoutValidator.validate(normalized).isEmpty()
        ) {
            return normalized
        }
        return "// summary: 文本回复\nroot = Markdown(\"${escapeOpenUiString(text)}\")"
    }

    /** OpenUI 双引号字符串转义（JSON 转义子集，对齐 OpenUiLexer 双引规则）。 */
    internal fun escapeOpenUiString(raw: String): String = buildString(raw.length + 8) {
        for (c in raw) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
```

> 注：兜底包装后的程序过 `validateProgramToError` 自检（`ensureValidProgram` 内部可断言式复验，失败则逐字硬编码 `"// summary: 错误\nroot = Markdown(\"回复生成失败，请重试\")"`——实现时加这个防御分支即可，测试已覆盖主路径）。

- [ ] **Step 4: 跑测试确认通过（含 ToolService 既有测试零回归）**

Run: `./gradlew -p server test --tests "*OpenUiTurnNativeTest*" && ./gradlew -p server test --tests "*RenderOpenUiToolServiceTest*"`
Expected: PASS（DSL_RULES 抽取后 annotation 拼接结果不变）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiTurnNative.kt server/src/main/kotlin/com/mamba/picme/server/agent/RenderOpenUiToolService.kt server/src/test/kotlin/com/mamba/picme/server/agent/OpenUiTurnNativeTest.kt
git commit -m "feat(server): 回合原生系统提示词契约与终结兜底变换（T7）"
```

---

### Task 8: server — AgentRoutes 接线（openui 头 = 回合原生）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/AgentRoutes.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/AgentRoutesTest.kt`

- [ ] **Step 1: 写失败测试**

在 `AgentRoutesTest.kt` 追加（参照文件内既有 X-GenUI-Format 用例）：

```kotlin
    @Test
    fun `openui header registers no genui tools and uses turn-native prompt`() = withAgentApp { client ->
        // 断言一：POST messages（X-GenUI-Format: openui）后，假 LLM 收到的请求体
        // tools 数组不含 render_ui_openui / render_html（经 RawSseStubServer 请求捕获），
        // system prompt 含「你的整个回复就是一颗 OpenUI Lang 1.0 程序」
        // 断言二：缺头（a2ui）路径 tools 含 render_ui、prompt 为 DEFAULT_SYSTEM_PROMPT（零回归）
    }
```

> 注：按 `AgentRoutesTest` / `RawSseStubServer` 既有捕获形态落地断言（请求体 JSON 读取 tools[].function.name 与 messages[0]）。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*AgentRoutesTest*"`
Expected: FAIL（openui 头仍注册 render_ui_openui）

- [ ] **Step 3: 实现**

`AgentRoutes.kt` `post("/sessions/{id}/messages")` 内：

① 头部解析（`GENUI_FORMAT_HEADER` 读取处上方）：

```kotlin
                    // 回合原生（turn-native，spec§3.1）：openui 头语义升级——
                    // 不注册 GenUI 发卡工具，响应即程序；缺头/未知值 = A2UI 工具发卡（回退闸）
                    val turnNativeOpenUi =
                        call.request.headers[GENUI_FORMAT_HEADER] == GENUI_FORMAT_OPENUI
```

② ToolRegistry 注册点改造：

```kotlin
                    val toolRegistry = ToolRegistry {
                        tools(
                            ServerToolService(onHtmlCard = htmlCardSink).asToolsByClass()
                                // turn-native：render_html 退役（Html 组件入树），其余服务端工具保留
                                .filter { !turnNativeOpenUi || it.descriptor.name != "render_html" } +
                                deviceTools.asToolsByClass() +
                                // GenUI 线格式分流：turn-native 线无发卡工具；A2UI 线照旧
                                if (turnNativeOpenUi) {
                                    emptyList()
                                } else {
                                    RenderUiToolService().asToolsByClass()
                                },
                        )
                    }
```

> 注：若 Koog `Tool` 暴露的名称属性不是 `descriptor.name`（1.3.0 反射 API 以编译器为准），用 `it.name` 或在 ServerTools 里加常量比对——改对名称来源即可，过滤语义不变；测试（断言一）会钉住正确性。

③ runTurn 调用点：

```kotlin
                            runner.runTurn(
                                session = session,
                                userMessage = content,
                                target = target,
                                systemPrompt = if (turnNativeOpenUi) {
                                    OpenUiTurnNative.SYSTEM_PROMPT
                                } else {
                                    DEFAULT_SYSTEM_PROMPT
                                },
                                toolRegistry = toolRegistry,
                                forwardContentDeltas = turnNativeOpenUi,
                                finalMessageTransformer = if (turnNativeOpenUi) {
                                    OpenUiTurnNative::ensureValidProgram
                                } else {
                                    { it }
                                },
                                onHtmlCard = htmlCardSink,
                                onOpenUiProgramDelta = { prefix -> ……不变…… },
                                onEvent = { event -> ……不变…… },
                            )
```

④ KDoc 更新：路由面注释里 `X-GenUI-Format` 段落（:35-39）改为「`openui` → 回合原生（响应即程序，无发卡工具）；其余 → A2UI 工具发卡（默认/回退闸）」。

- [ ] **Step 4: 跑测试确认通过（server 全量）**

Run: `./gradlew -p server build`
Expected: BUILD SUCCESSFUL（含全部既有用例——BYOK/配额/409/A2UI 注册零回归）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/AgentRoutes.kt server/src/test/kotlin/com/mamba/picme/server/agent/AgentRoutesTest.kt
git commit -m "feat(server): AgentRoutes 接线回合原生（openui 头语义升级，T8）"
```

---

### Task 9: androidApp — OpenUiProgramSummary（// summary 提取 + 程序嗅探）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiProgramSummary.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/chat/openui/OpenUiProgramSummaryTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.features.chat.openui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiProgramSummaryTest {

    @Test
    fun `looksLikeProgram detects root statement`() {
        assertTrue(OpenUiProgramSummary.looksLikeProgram("// summary: x\nroot = Card([Text(\"hi\")])"))
        assertTrue(OpenUiProgramSummary.looksLikeProgram("card = Card([Text(\"hi\")])")) // Card 兜底 entry
        assertFalse(OpenUiProgramSummary.looksLikeProgram("今天天气不错"))
        assertFalse(OpenUiProgramSummary.looksLikeProgram("root 这个词出现在句子里但不是声明"))
    }

    @Test
    fun `extractFallbackSummary prefers summary comment`() {
        val program = "// summary: 上周共 327 张照片\nroot = Card([Text(\"其它\")])"
        assertEquals("上周共 327 张照片", OpenUiProgramSummary.extractFallbackSummary(program))
    }

    @Test
    fun `extractFallbackSummary falls back to first text literal`() {
        val program = "root = Card([Markdown(\"# 相册报告\n正文内容\")])"
        assertEquals("# 相册报告 正文内容", OpenUiProgramSummary.extractFallbackSummary(program))
    }

    @Test
    fun `extractFallbackSummary empty when nothing extractable`() {
        assertEquals("", OpenUiProgramSummary.extractFallbackSummary("root = Card([Divider()])"))
    }

    @Test
    fun `summary truncated to 80 chars`() {
        val long = "长".repeat(100)
        val program = "// summary: $long\nroot = Card([Text(\"x\")])"
        assertEquals(80, OpenUiProgramSummary.extractFallbackSummary(program).length)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*OpenUiProgramSummaryTest*"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package com.mamba.picme.features.chat.openui

/**
 * 回合原生（turn-native，spec§3.4/§3.7）：响应程序的程序嗅探与 fallbackSummary 提取。
 *
 * 规则与 server `RenderOpenUiValidator` 结构校验是**字面量副本关系**（root 行首声明 /
 * Card 兜底 entry），改动须双向同步核对；本文件只做嗅探与提取，不做完整校验
 * （端上永远收到合法树由 server 双保险保证，这里是消息分派启发式）。
 */
object OpenUiProgramSummary {

    private val SUMMARY_COMMENT = Regex("""(?m)^\s*//\s*summary\s*[:：]\s*(.+)$""")
    private val STRING_LITERAL = Regex(""""([^"\\]|\\.)*"""")
    private val ROOT_STATEMENT = Regex("""(?m)^\s*root\s*=""")
    private val CARD_CALL = Regex("""(?<![@${'$'}A-Za-z0-9_])Card\s*\(""")
    private val TEXT_CALL =
        Regex("""(?<![@${'$'}A-Za-z0-9_])(?:Markdown|Text)\s*\(\s*"((?:[^"\\]|\\.)*)"""")

    private const val MAX_SUMMARY_LEN = 80

    /** assistant content 是否按程序解析（false → 按纯文本 AssistantText 降级上屏）。 */
    fun looksLikeProgram(content: String): Boolean {
        val stripped = STRING_LITERAL.replace(content, "\"\"")
        return ROOT_STATEMENT.containsMatchIn(stripped) || CARD_CALL.containsMatchIn(stripped)
    }

    /** 降级摘要：// summary 首行注释 > 首个 Markdown/Text 字符串字面量（截 80 字）> 空串。 */
    fun extractFallbackSummary(program: String): String {
        SUMMARY_COMMENT.find(program)?.let { match ->
            return match.groupValues[1].trim().take(MAX_SUMMARY_LEN)
        }
        TEXT_CALL.find(program)?.let { match ->
            return match.groupValues[1]
                .replace("\\n", " ")
                .trim()
                .take(MAX_SUMMARY_LEN)
        }
        return ""
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*OpenUiProgramSummaryTest*"`
Expected: PASS（5 例）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiProgramSummary.kt androidApp/src/test/java/com/mamba/picme/features/chat/openui/OpenUiProgramSummaryTest.kt
git commit -m "feat(android): OpenUI 程序嗅探与降级摘要提取（turn-native T9）"
```

---

### Task 10: androidApp — CloudAgentTurnRunner message 分支程序解析

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunner.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunnerTest.kt`

- [ ] **Step 1: 写失败测试**

在 `CloudAgentTurnRunnerTest.kt` 追加（fake CloudAgentApi 基建既有，参照 openui action 帧用例）：

```kotlin
    @Test
    fun `openui mode parses assistant message as OpenUiCard`() = runTest {
        val events = mutableListOf<CloudTurnEvent>()
        val program = "// summary: 统计\nroot = Card([Text(\"hi\")])"
        runner(genUiFormat = "openui", sseFrames = listOf(assistantMessageFrame(program), doneFrame()))
            .runTurn("token", "session", "text", null, "openui") { events += it }
        val card = events.filterIsInstance<CloudTurnEvent.OpenUiCard>().single()
        assertEquals(program, card.program)
        assertEquals("统计", card.fallbackSummary)
        assertTrue(events.filterIsInstance<CloudTurnEvent.AssistantText>().isEmpty())
    }

    @Test
    fun `openui mode falls back to AssistantText for non-program message`() = runTest {
        val events = mutableListOf<CloudTurnEvent>()
        runner(genUiFormat = "openui", sseFrames = listOf(assistantMessageFrame("兜底文本"), doneFrame()))
            .runTurn("token", "session", "text", null, "openui") { events += it }
        assertEquals("兜底文本", events.filterIsInstance<CloudTurnEvent.AssistantText>().single().text)
    }

    @Test
    fun `a2ui mode keeps assistant message as text`() = runTest {
        val events = mutableListOf<CloudTurnEvent>()
        val text = "root = Card([Text(\"hi\")])" // a2ui 线下即使是程序形态也不解析
        runner(genUiFormat = "a2ui", sseFrames = listOf(assistantMessageFrame(text), doneFrame()))
            .runTurn("token", "session", "text", null, "a2ui") { events += it }
        assertEquals(text, events.filterIsInstance<CloudTurnEvent.AssistantText>().single().text)
    }
```

> 注：`runner(...)`/`assistantMessageFrame(...)`/`doneFrame()` 是计划级示意名——按 `CloudAgentTurnRunnerTest.kt` 既有 fake 组织落地（文件里已有 action/html_card 帧用例，照其帧构造形态造 `type=message, role=assistant` 的 event 帧）。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*CloudAgentTurnRunnerTest*"`
Expected: FAIL（message 仍按 AssistantText 上屏）

- [ ] **Step 3: 实现**

`CloudAgentTurnRunner.kt`：

① `runTurn` 的 frame 分派把 `genUiFormat` 传入 `handleEvent`：

```kotlin
            is CloudFrame.Event -> handleEvent(frame.event, onEvent, genUiFormat)
```

② `handleEvent` 签名加 `genUiFormat: String`，message 分支改造：

```kotlin
    private fun handleEvent(event: JSONObject, onEvent: (CloudTurnEvent) -> Unit, genUiFormat: String) {
        val payload = event.optJSONObject("payload") ?: return
        when (event.optString("type")) {
            // 助手答复（user 回声跳过——本地已先上屏）；
            // turn-native（openui）：assistant content = OpenUI 程序 → OpenUiCard 事件
            // （spec§3.7）；非程序形态（兜底包装理论上已被 server 兜住）按文本降级
            "message" -> if (event.optString("role") == "assistant") {
                val content = payload.optString("content")
                if (genUiFormat == "openui" && OpenUiProgramSummary.looksLikeProgram(content)) {
                    onEvent(
                        CloudTurnEvent.OpenUiCard(
                            program = content,
                            fallbackSummary = OpenUiProgramSummary.extractFallbackSummary(content),
                        ),
                    )
                } else {
                    onEvent(CloudTurnEvent.AssistantText(content))
                }
            }
            // ……其余分支不变……
        }
    }
```

③ import：`import com.mamba.picme.features.chat.openui.OpenUiProgramSummary`

> ChatViewModel **零改动**：`OpenUiCard`/`OpenUiCardDelta`/`AssistantText` 三种事件的消费管线（`runCloudTurnOnce` :2426-2472 + `flushCloudTurnCards` :2355）原样覆盖新来源；覆盖式预览由 delta 帧天然实现（每颗新程序的前缀覆写 `_openUiStreamProgram`）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*CloudAgentTurnRunnerTest*"`
Expected: PASS（新旧用例全绿）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunner.kt androidApp/src/test/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunnerTest.kt
git commit -m "feat(android): TurnRunner message 分支回合原生程序解析（T10）"
```

---

### Task 11: androidApp — Html 组件渲染叶子

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiNodeRenderer.kt`
- Modify（如需）: `androidApp/src/main/java/com/mamba/picme/features/chat/openui/OpenUiContentRenderers.kt`（若内容类组件集中在此文件，跟随其组织）
- Test: 编译验证 + 既有 openui 测试不回归（Html 渲染属 WebView 集成，单测覆盖有限，真机遛狗在 Task 13）

- [ ] **Step 1: 读 HtmlCard 调用先例**

读 `ChatScreen.kt` 中 `HtmlCard(` 的调用点（搜索 `HtmlCard(html`），确认宿主传入的典型参数（meta/cacheKey/display 回调在卡内组件场景的取舍：组件内嵌一律 Inline、不落 displayMode 持久化——`meta = null`、`onDisplayModeResolved` 用默认 no-op）。

- [ ] **Step 2: 实现渲染分支**

在 `OpenUiNodeRenderer` 的 `when (node.type)` 中（`"Divider"` 分支附近，按字母/语义就近）加：

```kotlin
        // Html（turn-native，spec§3.6）：L2 沙箱入树——完整自包含 HTML 经既有
        // HtmlCardSanitizer 清洗后复用 HtmlCard WebView 沙箱渲染（网络默认关死、
        // dispatch 白名单不变式继承）；清洗拒绝 → 降级 CAPTION 文本不炸卡
        "Html" -> run {
            val rawHtml = (node.props["html"] as? OpenUiValue.Str)?.v.orEmpty()
            when (val sanitized = HtmlCardSanitizer.sanitize(rawHtml)) {
                is HtmlCardSanitizer.Result.Ok -> HtmlCard(
                    html = sanitized.html,
                    modifier = modifier.fillMaxWidth(),
                )
                is HtmlCardSanitizer.Result.Rejected -> {
                    Logger.w("OpenUiNodeRenderer", "Html component rejected: ${sanitized.reason}")
                    Text(
                        text = stringResource(R.string.chat_a2ui_card_unavailable),
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = modifier,
                    )
                }
            }
        }
```

> 注：① `HtmlCardSanitizer` 的确切包名/object 形态以 `ChatViewModel.kt:2443` 的调用为准；② `chat_a2ui_card_unavailable` 字符串既有（降级卡复用口径，spec§4.2 [I18N]）；③ 若 `stringResource` 在该文件未引入，照文件内其它 Text 分支的资源获取方式对齐；④ 编辑器提示 `Html` 分支不可达时，确认 Task 1 的 shared 改动已同步到本 worktree 的 `:shared` 依赖。

- [ ] **Step 3: 编译 + 既有测试**

Run: `./gradlew :androidApp:compileDebugKotlin && ./gradlew :androidApp:testDebugUnitTest --tests "*OpenUi*"`
Expected: BUILD SUCCESSFUL + 既有 openui 测试全绿

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/openui/
git commit -m "feat(android): Html 组件 WebView 沙箱渲染叶子（turn-native T11）"
```

---

### Task 12: androidApp — 设置页 GenUI 开关文案五语更新

**Files:**
- Modify: `androidApp/src/main/res/values/strings.xml:565`
- Modify: `androidApp/src/main/res/values-zh-rCN/strings.xml:553`
- Modify: `androidApp/src/main/res/values-zh-rTW/strings.xml:553`
- Modify: `androidApp/src/main/res/values-es/strings.xml:550`
- Modify: `androidApp/src/main/res/values-fr/strings.xml:550`

- [ ] **Step 1: 更新 `settings_genui_format_subtitle`（title 不变）**

EN (`values/strings.xml`)：
```xml
    <string name="settings_genui_format_subtitle">OpenUI renders every reply as a native interactive UI (recommended); A2UI is the legacy fallback wire format</string>
```

zh-CN (`values-zh-rCN/strings.xml`)：
```xml
    <string name="settings_genui_format_subtitle">OpenUI 把每条回复渲染为原生交互界面（推荐）；A2UI 为兼容回退线格式</string>
```

zh-TW (`values-zh-rTW/strings.xml`)：
```xml
    <string name="settings_genui_format_subtitle">OpenUI 將每則回覆渲染為原生互動介面（推薦）；A2UI 為相容回退線格式</string>
```

ES (`values-es/strings.xml`)：
```xml
    <string name="settings_genui_format_subtitle">OpenUI renderiza cada respuesta como interfaz interactiva nativa (recomendado); A2UI es el formato heredado de reserva</string>
```

FR (`values-fr/strings.xml`)：
```xml
    <string name="settings_genui_format_subtitle">OpenUI rend chaque réponse comme une interface interactive native (recommandé) ; A2UI est le format de repli hérité</string>
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/res/values/strings.xml androidApp/src/main/res/values-zh-rCN/strings.xml androidApp/src/main/res/values-zh-rTW/strings.xml androidApp/src/main/res/values-es/strings.xml androidApp/src/main/res/values-fr/strings.xml
git commit -m "feat(android): GenUI 开关文案语义升级五语同步（turn-native T12，[I18N]）"
```

---

### Task 13: 文档沉淀 + 门禁 + 全量验证

**Files:**
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`（登记 Html 组件 + 回合原生协议变更）
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_PROTOCOL_TECH_SPEC.md`（请求/回复协议实况：openui 头语义升级、openui_delta 帧语义、无发卡工具、兜底包装）
- Modify: `server/AGENTS.md`（工具清单：OpenUI 线无 render_ui_openui/render_html；策略层 ProgramGuard 节点；AgentRunner 两新参数）
- Modify: `shared/AGENTS.md`（§2 openui 包段落补 Html 组件一句）
- Modify: `docs/superpowers/README.md`（§6 白名单登记在途 spec/plan——若该清单存在且管理在途项）

- [ ] **Step 1: 文档更新**

- `CHAT_CARD_CATALOG.md`：`data_ui_openui` 卡条目——来源从「render_ui_openui 工具 action 帧」改为「终结 Message 事件（回合原生）」；新增 Html 组件行（协议/parts 形态/回灌/渲染三要素）；端到端例子更新为 §4 时序。
- `CHAT_PROTOCOL_TECH_SPEC.md`：云端 Agent 段——`X-GenUI-Format: openui` 语义升级段落（无发卡工具、content delta → openui_delta、终结 Message = 程序、ProgramGuard + 兜底包装、A2UI 回退闸）。
- `server/AGENTS.md`：agent 域工具与策略说明同步（ServerToolService 在 openui 线过滤 render_html；cloudAgentStrategy 新增 correctInvalidProgram 节点；AgentRunner forwardContentDeltas/finalMessageTransformer）。
- `shared/AGENTS.md` §2 openui 包段落末补：`2026-11-01 turn-native：+Html 组件（L2 沙箱入树，spec《2026-11-01-openui-turn-native-design》），组件清单 100→101。`

> 注：shared 组件计数以 Task 1 落地后的真实清单数为准（白名单 KDoc/AGENTS.md 中的「100」计数同步改）。

- [ ] **Step 2: 全量验证**

```bash
cd .worktrees/openui-turn-native
JITPACK=true ./gradlew :shared:jvmTest
JITPACK=true ./gradlew :shared:assemble
./gradlew :androidApp:compileDebugKotlin
./gradlew :androidApp:testDebugUnitTest
./gradlew -p server build
./scripts/ai-gate.sh
```
Expected: 全部 PASS（ai-gate 含 check_doc_sync）

- [ ] **Step 3: 真机遛狗清单（人工/半自动，spec §7 门槛）**

构建安装 debug 包，云 Agent（openui 开关位）跑四场景：
1. 统计卡：「看看我每个月拍了多少照片」→ BarChart/StatRow 卡流式长成，数据正确；
2. 照片网格：「找几张海边的照片」→ PhotoGrid 卡，照片渲染无破图；
3. 图文混排：「总结一下我上周的照片」→ ImageText/Banner 混排卡；
4. 纯文本闲聊：「你好」→ 纯文本形态（Markdown 组件树），无降级卡。
另验证：⑤ Html 场景「做个可交互的小工具」→ Html 组件卡沙箱渲染；⑥ 开关拨回 a2ui → 旧行为回归正常。

- [ ] **Step 4: Commit**

```bash
git add docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md docs/03-TECHNICAL-SPECS/CHAT_PROTOCOL_TECH_SPEC.md server/AGENTS.md shared/AGENTS.md docs/superpowers/README.md
git commit -m "docs: OpenUI 回合原生交付沉淀（目录/协议/模块文档同步，T13）"
```

---

## Self-Review 记录（计划撰写后）

- **Spec 覆盖**：§3.1（T8）/ §3.2（T3+T4）/ §3.3（T4 轮次重置 + T10 注释，端上零改动验证在 T13）/ §3.4（T7 prompt + T4 transformer + T9）/ §3.5（T2+T5+T6）/ §3.6（T1+T2 白名单+T11）/ §3.7（T9+T10）/ §3.8（T8④+T12）/ §7 测试矩阵（各任务 Step + T13）/ §10 文档沉淀（T13）。US-1~7 → T13 Step 3 遛狗清单 + 各单测。
- **类型一致性**：`validateProgramToError`（T2 定义，T5/T7 消费）；`ensureValidProgram`（T7 定义，T8 接线）；`OpenUiContentStreamTracker.onRoundStart/onTextDelta`（T3 定义，T4 消费）；`ProgramGuard.invalidProgramError/markCorrected/correctionPrompt`（T5 定义，T6 消费）；`OpenUiProgramSummary.looksLikeProgram/extractFallbackSummary`（T9 定义，T10 消费）；`forwardContentDeltas`/`finalMessageTransformer`（T4 定义，T6/T8 消费）。已核对一致。
- **已知计划级不确定性（实现时以编译器/既有测试为准，均有退路注明）**：Koog `Tool` 名称属性形态（T8②）；`OpenUiProp` enum 参数名（T1③）；`OpenUiLibrary` 校验入口函数名（T1 测试）；MockLlmSse/CloudAgentTurnRunnerTest 的 fake 组织细节（T4/T6/T10）。
