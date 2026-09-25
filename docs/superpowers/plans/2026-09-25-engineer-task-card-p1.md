# 工程师任务卡 P1（在场态）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 工程师模式每次 `sendClaudeMessage` 提交即登记一张 TASK_CARD 消息卡（原生 Compose），SSE 事件驱动状态机，覆盖进度态（US-1~3）与审批态/终态（US-7~11），审批收口为卡片唯一入口。

**Spec:** `docs/superpowers/specs/2026-09-25-engineer-task-card-design.md`（§8 P1 在场态：US-1~3、US-7~11）。回联（US-4~6）与任务中心（US-12~16）属 P3，网关改造属 P2，均不在本计划。

**Architecture:** 状态模型 `EngineerTaskState` 为 shared commonMain 纯数据（双端 SSOT 形状，spec D1）；状态机 `EngineerTaskReducer` 在 androidApp 纯 Kotlin 层（消费 `ClaudeEvent`，对齐 `ClaudeSseParser` 纯逻辑先例）；卡片即 `TASK_CARD` 类型 Room 消息，提交即插入（state=RUNNING）、结构性事件 REPLACE upsert（对齐 gacha `optimize_candidates` metadata 先例）；UI 为全宽原生 Compose 卡片（对齐 `HtmlCard`/`GachaCandidateStrip` 分发先例）。

**Tech Stack:** Kotlin / Jetpack Compose / Room（picme_database v23，type 为 TEXT 列，新增类型无迁移）/ JUnit4 + org.json（单测既有依赖）。

**计划决策（spec 内未细定，本计划裁定）：**
- **US-3 简化**：卡片自身展开/收起明细（recentStages 列表）；不做「锚定气泡 + 气泡折叠为单行摘要」的联动（气泡行为不变即 US-2 本意）。
- **交付动作只有 push**（`ClaudeChatClient.deliver` 默认 `mode="push"`，现有注释「gateway MVP 仅 push（pr/auto 二期）」）。
- **气泡按钮抑制**：会话内存在 TASK_CARD 时，`AgentMessageExtras` 的「继续」「交付」气泡按钮整体隐藏（`suppressClaudeActions`）；无卡 legacy 会话行为不变。
- **D6 在场并发**：`_isProcessing` 已天然门控（处理中输入禁用），App 侧不另做；网关 409 属 P2。
- **costCents = null 时不显示成本行**（网关补发前的降级形态，spec §7）。
- **已耗时无秒级 ticker**：随事件刷新（`updatedAtMs - startedAtMs`）。

---

### Task 1: 共享状态模型 + Room metadata serde

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/EngineerTaskState.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatModelCommonMainShim.kt`（追加 serde 扩展）
- Test: `androidApp/src/test/java/com/mamba/picme/features/chat/EngineerTaskStateSerdeTest.kt`

- [ ] **Step 1: 写失败的 serde roundtrip 测试**

创建 `androidApp/src/test/java/com/mamba/picme/features/chat/EngineerTaskStateSerdeTest.kt`：

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineerTaskStateSerdeTest {

    private fun fullState() = EngineerTaskState(
        taskId = "task_abc",
        sourceText = "帮我修复登录崩溃",
        sid = "a1b2c3d4e5f6",
        status = EngineerTaskStatus.AWAITING_DELIVER,
        stage = "Bash",
        recentStages = listOf("Read", "Edit", "Bash"),
        turns = 7,
        costCents = 42,
        startedAtMs = 1000L,
        updatedAtMs = 2000L,
        fileChangeCount = 3,
        truncatedReason = null,
        errorSummary = null,
        resultSummary = "已修复登录崩溃",
        resolution = null,
        deliverBranch = null,
    )

    @Test
    fun `roundtrip preserves all fields`() {
        val metadata = JSONObject().put("engineer_task", fullState().toJson()).toString()
        assertEquals(fullState(), parseEngineerTaskState(metadata))
    }

    @Test
    fun `roundtrip preserves terminal resolution`() {
        val resolved = fullState().copy(
            status = EngineerTaskStatus.COMPLETED,
            resolution = EngineerTaskResolution.DELIVERED,
            deliverBranch = "fix/login",
        )
        val metadata = JSONObject().put("engineer_task", resolved.toJson()).toString()
        assertEquals(resolved, parseEngineerTaskState(metadata))
    }

    @Test
    fun `null and garbage metadata return null`() {
        assertNull(parseEngineerTaskState(null))
        assertNull(parseEngineerTaskState(""))
        assertNull(parseEngineerTaskState("not json"))
        assertNull(parseEngineerTaskState("""{"other":1}"""))
    }

    @Test
    fun `unknown status and resolution fall back safely`() {
        val json = fullState().toJson()
            .put("status", "NOT_A_STATUS")
            .put("resolution", "NOT_A_RESOLUTION")
        val parsed = parseEngineerTaskState(JSONObject().put("engineer_task", json).toString())
        assertEquals(EngineerTaskStatus.RUNNING, parsed?.status)
        assertNull(parsed?.resolution)
    }

    @Test
    fun `missing costCents stays null`() {
        val metadata = JSONObject().put("engineer_task", fullState().copy(costCents = null).toJson()).toString()
        assertNull(parseEngineerTaskState(metadata)?.costCents)
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*EngineerTaskStateSerdeTest*"`
Expected: FAIL — `EngineerTaskState` / `toJson` / `parseEngineerTaskState` unresolved

- [ ] **Step 3: 创建 shared 状态模型**

创建 `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/EngineerTaskState.kt`：

```kotlin
package com.mamba.picme.domain.chat

/**
 * 工程师模式任务卡状态（spec 2026-09-25-engineer-task-card-design D1/D2）。
 * 纯数据形状，双端 SSOT；状态迁移在 androidApp `EngineerTaskReducer`（消费 ClaudeEvent）。
 * org.json 序列化为平台边界，由 androidApp ChatModelCommonMainShim 提供（对齐 ClaudeAgentState 先例）。
 */
enum class EngineerTaskStatus { RUNNING, AWAITING_CONTINUE, AWAITING_DELIVER, COMPLETED, FAILED }

/** 审批动作回填（US-9）：已继续 / 已放弃 / 已交付 / 暂不交付。 */
enum class EngineerTaskResolution { CONTINUED, ABANDONED, DELIVERED, DELIVER_SKIPPED }

data class EngineerTaskState(
    val taskId: String,
    val sourceText: String,
    val sid: String? = null,
    val status: EngineerTaskStatus = EngineerTaskStatus.RUNNING,
    val stage: String? = null,
    val recentStages: List<String> = emptyList(),
    val turns: Int = 0,
    val costCents: Int? = null,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val fileChangeCount: Int = 0,
    val truncatedReason: String? = null,
    val errorSummary: String? = null,
    val resultSummary: String? = null,
    val resolution: EngineerTaskResolution? = null,
    val deliverBranch: String? = null,
) {
    companion object {
        /** Room chat_messages.type 列值（TEXT 列，新增类型无需迁移）。 */
        const val ROOM_TYPE = "task_card"
    }
}
```

- [ ] **Step 4: 在 shim 追加 serde 扩展**

在 `androidApp/src/main/java/com/mamba/picme/features/chat/ChatModelCommonMainShim.kt` 文件末尾追加（文件已有 `ClaudeAgentState.toJson/fromJson`、`OptimizeCandidateGroup.toJson/fromJson` 先例；顶部 import 补 `com.mamba.picme.domain.chat.EngineerTaskResolution`、`EngineerTaskState`、`EngineerTaskStatus`、`org.json.JSONArray`）：

```kotlin
// ---- 工程师任务卡（TASK_CARD）Room metadata serde ----

fun EngineerTaskState.toJson(): JSONObject = JSONObject().apply {
    put("taskId", taskId)
    put("sourceText", sourceText)
    put("sid", sid)
    put("status", status.name)
    put("stage", stage)
    put("recentStages", JSONArray(recentStages))
    put("turns", turns)
    if (costCents != null) put("costCents", costCents)
    put("startedAtMs", startedAtMs)
    put("updatedAtMs", updatedAtMs)
    put("fileChangeCount", fileChangeCount)
    put("truncatedReason", truncatedReason)
    put("errorSummary", errorSummary)
    put("resultSummary", resultSummary)
    put("resolution", resolution?.name)
    put("deliverBranch", deliverBranch)
}

/** 从 metadata.engineer_task 还原任务卡状态；任何损坏/缺字段都返回 null 不崩（对齐 OptimizeCandidateGroup.fromJson 先例）。 */
fun parseEngineerTaskState(metadata: String?): EngineerTaskState? {
    if (metadata.isNullOrBlank()) return null
    return runCatching {
        val root = JSONObject(metadata).optJSONObject("engineer_task") ?: return null
        EngineerTaskState(
            taskId = root.getString("taskId"),
            sourceText = root.optString("sourceText"),
            sid = root.optString("sid").ifBlank { null },
            status = runCatching { EngineerTaskStatus.valueOf(root.optString("status")) }
                .getOrDefault(EngineerTaskStatus.RUNNING),
            stage = root.optString("stage").ifBlank { null },
            recentStages = root.optJSONArray("recentStages")?.let { arr ->
                (0 until arr.length()).map { idx -> arr.getString(idx) }
            } ?: emptyList(),
            turns = root.optInt("turns"),
            costCents = if (root.has("costCents")) root.getInt("costCents") else null,
            startedAtMs = root.optLong("startedAtMs"),
            updatedAtMs = root.optLong("updatedAtMs"),
            fileChangeCount = root.optInt("fileChangeCount"),
            truncatedReason = root.optString("truncatedReason").ifBlank { null },
            errorSummary = root.optString("errorSummary").ifBlank { null },
            resultSummary = root.optString("resultSummary").ifBlank { null },
            resolution = root.optString("resolution").ifBlank { null }
                ?.let { name -> runCatching { EngineerTaskResolution.valueOf(name) }.getOrNull() },
            deliverBranch = root.optString("deliverBranch").ifBlank { null },
        )
    }.getOrNull()
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*EngineerTaskStateSerdeTest*"`
Expected: PASS（5 tests）

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/EngineerTaskState.kt \
        androidApp/src/main/java/com/mamba/picme/features/chat/ChatModelCommonMainShim.kt \
        androidApp/src/test/java/com/mamba/picme/features/chat/EngineerTaskStateSerdeTest.kt
git commit -m "feat(chat): 任务卡状态模型 EngineerTaskState + Room metadata serde（spec D1/D2）"
```

---

### Task 2: EngineerTaskReducer 状态机（全迁移矩阵 TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/chat/engineer/EngineerTaskReducer.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/chat/engineer/EngineerTaskReducerTest.kt`

- [ ] **Step 1: 写失败的状态机测试**

创建 `androidApp/src/test/java/com/mamba/picme/features/chat/engineer/EngineerTaskReducerTest.kt`：

```kotlin
package com.mamba.picme.features.chat.engineer

import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineerTaskReducerTest {

    private fun running() = EngineerTaskReducer.initial("task_1", "修 bug", nowMs = 1000L)

    @Test
    fun `initial is RUNNING with empty progress`() {
        val state = running()
        assertEquals(EngineerTaskStatus.RUNNING, state.status)
        assertEquals(0, state.turns)
        assertNull(state.sid)
    }

    @Test
    fun `session event records sid`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Session("a1b2c3d4e5f6"), canDeliver = false, nowMs = 1100L)
        assertEquals("a1b2c3d4e5f6", next.sid)
    }

    @Test
    fun `tool use updates stage and history with cap`() {
        var state = running()
        repeat(25) { idx ->
            state = EngineerTaskReducer.reduce(state, ClaudeEvent.ToolUse("Tool$idx", JSONObject()), canDeliver = false, nowMs = 1100L + idx)
        }
        assertEquals("Tool24", state.stage)
        assertEquals(20, state.recentStages.size)
        assertEquals("Tool5", state.recentStages.first())
    }

    @Test
    fun `assistant text and tool result do not change card`() {
        val state = running()
        assertEquals(state, EngineerTaskReducer.reduce(state, ClaudeEvent.AssistantText("hello"), canDeliver = false, nowMs = 1100L))
        assertEquals(state, EngineerTaskReducer.reduce(state, ClaudeEvent.ToolResult(true, "ok"), canDeliver = false, nowMs = 1100L))
    }

    @Test
    fun `file change increments counter`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.FileChange("A.kt", "edit"), canDeliver = false, nowMs = 1100L)
        assertEquals(1, next.fileChangeCount)
    }

    @Test
    fun `cost fills turns and cents`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Cost(turns = 5, cents = 37), canDeliver = false, nowMs = 1100L)
        assertEquals(5, next.turns)
        assertEquals(37, next.costCents)
    }

    @Test
    fun `done truncated awaits continue`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Done(turns = 9, truncated = true, reason = "max_turns"), canDeliver = true, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.AWAITING_CONTINUE, next.status)
        assertEquals("max_turns", next.truncatedReason)
        assertEquals(9, next.turns)
    }

    @Test
    fun `error truncated awaits continue`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Error("timeout", truncated = true, reason = "phase_timeout"), canDeliver = false, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.AWAITING_CONTINUE, next.status)
        assertEquals("phase_timeout", next.truncatedReason)
    }

    @Test
    fun `error plain fails with summary`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Error("boom"), canDeliver = false, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.FAILED, next.status)
        assertEquals("boom", next.errorSummary)
    }

    @Test
    fun `done with file change and canDeliver awaits deliver`() {
        val withFile = EngineerTaskReducer.reduce(running(), ClaudeEvent.FileChange("A.kt", "edit"), canDeliver = true, nowMs = 1050L)
        val next = EngineerTaskReducer.reduce(withFile, ClaudeEvent.Done(turns = 3), canDeliver = true, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.AWAITING_DELIVER, next.status)
    }

    @Test
    fun `done with file change but no permission completes`() {
        val withFile = EngineerTaskReducer.reduce(running(), ClaudeEvent.FileChange("A.kt", "edit"), canDeliver = false, nowMs = 1050L)
        val next = EngineerTaskReducer.reduce(withFile, ClaudeEvent.Done(turns = 3), canDeliver = false, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.COMPLETED, next.status)
    }

    @Test
    fun `done plain completes`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Done(turns = 2), canDeliver = true, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.COMPLETED, next.status)
    }

    @Test
    fun `resolution is terminal and idempotent`() {
        val awaiting = EngineerTaskReducer.reduce(running(), ClaudeEvent.Done(turns = 1, truncated = true, reason = "max_turns"), canDeliver = true, nowMs = 1100L)
        val resolved = EngineerTaskReducer.resolved(awaiting, EngineerTaskResolution.CONTINUED, nowMs = 1200L)
        assertEquals(EngineerTaskStatus.COMPLETED, resolved.status)
        assertEquals(EngineerTaskResolution.CONTINUED, resolved.resolution)
        val again = EngineerTaskReducer.resolved(resolved, EngineerTaskResolution.ABANDONED, nowMs = 1300L)
        assertEquals(EngineerTaskResolution.CONTINUED, again.resolution)
    }

    @Test
    fun `summarize takes first non blank line capped`() {
        assertEquals("结果", EngineerTaskReducer.summarize("\n\n结果\n第二行"))
        assertEquals(80, EngineerTaskReducer.summarize("x".repeat(200)).length)
        assertEquals("", EngineerTaskReducer.summarize(""))
    }

    @Test
    fun `only assistant text is non structural`() {
        assertTrue(!EngineerTaskReducer.isStructural(ClaudeEvent.AssistantText("x")))
        assertTrue(EngineerTaskReducer.isStructural(ClaudeEvent.Done()))
        assertTrue(EngineerTaskReducer.isStructural(ClaudeEvent.Session("a1b2c3d4e5f6")))
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*EngineerTaskReducerTest*"`
Expected: FAIL — `EngineerTaskReducer` unresolved

- [ ] **Step 3: 实现状态机**

创建 `androidApp/src/main/java/com/mamba/picme/features/chat/engineer/EngineerTaskReducer.kt`：

```kotlin
package com.mamba.picme.features.chat.engineer

import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus

/**
 * 工程师任务卡状态机（spec §5 测试决策首层接缝；纯逻辑，对齐 ClaudeSseParser 先例）。
 * 文本 delta（AssistantText）不驱动卡片——避免每个 token 触发 Room upsert；
 * 结构性事件（isStructural=true）才由调用方持久化。
 */
object EngineerTaskReducer {

    private const val MAX_STAGES = 20
    private const val SUMMARY_MAX = 80

    fun initial(taskId: String, sourceText: String, nowMs: Long): EngineerTaskState =
        EngineerTaskState(taskId = taskId, sourceText = sourceText, startedAtMs = nowMs, updatedAtMs = nowMs)

    fun isStructural(event: ClaudeEvent): Boolean = event !is ClaudeEvent.AssistantText

    fun reduce(
        state: EngineerTaskState,
        event: ClaudeEvent,
        canDeliver: Boolean,
        nowMs: Long,
    ): EngineerTaskState = when (event) {
        is ClaudeEvent.Session -> state.copy(sid = event.sid, updatedAtMs = nowMs)
        is ClaudeEvent.AssistantText -> state
        is ClaudeEvent.ToolUse -> state.pushStage(event.tool, nowMs)
        is ClaudeEvent.AppToolRequest -> state.pushStage(event.tool, nowMs)
        is ClaudeEvent.ToolResult -> state
        is ClaudeEvent.FileChange ->
            state.copy(fileChangeCount = state.fileChangeCount + 1).pushStage("file_change:${event.path}", nowMs)
        is ClaudeEvent.Cost -> state.copy(turns = event.turns, costCents = event.cents, updatedAtMs = nowMs)
        is ClaudeEvent.Error ->
            if (event.truncated) {
                state.copy(
                    status = EngineerTaskStatus.AWAITING_CONTINUE,
                    truncatedReason = event.reason,
                    updatedAtMs = nowMs,
                )
            } else {
                state.copy(status = EngineerTaskStatus.FAILED, errorSummary = event.message, updatedAtMs = nowMs)
            }
        is ClaudeEvent.Done -> {
            val withTurns = if (event.turns > 0) state.copy(turns = event.turns) else state
            when {
                event.truncated -> withTurns.copy(
                    status = EngineerTaskStatus.AWAITING_CONTINUE,
                    truncatedReason = event.reason,
                    updatedAtMs = nowMs,
                )
                withTurns.fileChangeCount > 0 && canDeliver ->
                    withTurns.copy(status = EngineerTaskStatus.AWAITING_DELIVER, updatedAtMs = nowMs)
                else -> withTurns.copy(status = EngineerTaskStatus.COMPLETED, updatedAtMs = nowMs)
            }
        }
    }

    /** 审批动作回填（US-9）：terminal 化 + resolution 粘滞，同一决策点只审批一次。 */
    fun resolved(
        state: EngineerTaskState,
        resolution: EngineerTaskResolution,
        nowMs: Long,
        deliverBranch: String? = null,
    ): EngineerTaskState {
        if (state.resolution != null) return state
        return state.copy(
            status = EngineerTaskStatus.COMPLETED,
            resolution = resolution,
            deliverBranch = deliverBranch,
            updatedAtMs = nowMs,
        )
    }

    /** 完成态结果摘要：首个非空行，截断 80 字（US-10 末段文本首行）。 */
    fun summarize(text: String): String =
        text.lines().firstOrNull { line -> line.isNotBlank() }?.trim()?.take(SUMMARY_MAX) ?: ""

    private fun EngineerTaskState.pushStage(label: String, nowMs: Long): EngineerTaskState =
        copy(stage = label, recentStages = (recentStages + label).takeLast(MAX_STAGES), updatedAtMs = nowMs)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*EngineerTaskReducerTest*"`
Expected: PASS（15 tests）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/engineer/EngineerTaskReducer.kt \
        androidApp/src/test/java/com/mamba/picme/features/chat/engineer/EngineerTaskReducerTest.kt
git commit -m "feat(chat): EngineerTaskReducer 任务卡状态机——SSE 事件→五态迁移 + 审批回填幂等"
```

---

### Task 3: TASK_CARD 消息类型接入（enum + 域字段 + 映射 + 列表 overlay）

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessageType.kt`（+TASK_CARD）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt`（+engineerTask 字段）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（toUiModel 映射、_engineerTasks、displayMessages overlay、loadMessages 回填）

- [ ] **Step 1: shared 侧加类型与载荷字段**

`ChatMessageType.kt` 在 `OPTIMIZE_CANDIDATES` 前（保持 HTML_CARD 之后）加一行：

```kotlin
    TASK_CARD,
```

`ChatMessage.kt` 在 `optimizeCandidates` 字段后加：

```kotlin
    val engineerTask: EngineerTaskState? = null,   // TASK_CARD 载荷（状态存 Room metadata）
```

- [ ] **Step 2: ViewModel 加任务状态表与列表 overlay**

`ChatViewModel.kt` 在 `claudeSid` 字段（:310-311）附近加：

```kotlin
    /** 工程师任务卡内存态：taskId → 最新状态（Room 为持久层，此处为流式期间的 live 覆盖）。 */
    private val _engineerTasks = MutableStateFlow<Map<String, EngineerTaskState>>(emptyMap())

    /** 当前回合活动任务（sendClaudeMessage 提交时置位，回合结束清空）。 */
    @Volatile
    private var activeEngineerTaskId: String? = null
```

`displayMessages`（:701-703）改为三流 combine：

```kotlin
    val displayMessages: StateFlow<List<ChatMessageUi>> =
        combine(_messages, _streamingMessage, _engineerTasks) { messages, streaming, tasks ->
            val base = if (streaming != null) messages + streaming else messages
            if (tasks.isEmpty()) {
                base
            } else {
                base.map { msg ->
                    val live = msg.engineerTask?.let { task -> tasks[task.taskId] }
                    if (live != null && live != msg.engineerTask) msg.copy(engineerTask = live) else msg
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

- [ ] **Step 3: toUiModel 映射 + loadMessages 回填**

`toUiModel()`（:2904-2918 的 when）加分支：

```kotlin
                EngineerTaskState.ROOM_TYPE -> ChatMessageType.TASK_CARD
```

同函数返回体内 `optimizeCandidates = ...` 之后加：

```kotlin
            engineerTask = if (type == EngineerTaskState.ROOM_TYPE) parseEngineerTaskState(metadata) else null,
```

`loadMessages()`（:993-1023，Room flow collect 内 map toUiModel 之后、`claudeDeliverOverrides` 回填附近）加：

```kotlin
                    _engineerTasks.value = uiMessages
                        .mapNotNull { msg -> msg.engineerTask?.let { task -> task.taskId to task } }
                        .toMap()
```

（`uiMessages` 用该处实际的局部变量名。）

- [ ] **Step 4: 编译 + 既有测试不回归**

Run: `./gradlew :androidApp:compileDebugKotlin && ./gradlew :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，既有测试全绿

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessageType.kt \
        shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt \
        androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(chat): TASK_CARD 消息类型接入——enum + 域载荷 + Room 映射 + 列表 live overlay"
```

---

### Task 4: 提交插卡 + SSE 事件驱动 + Room 增量 upsert

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（sendClaudeMessage、persistEngineerTask、onClaudeEventForTask、失败路径）

- [ ] **Step 1: persistEngineerTask + 事件驱动 helper**

`ChatViewModel.kt` 在 `persistClaudeBubble`（:512-543）后加：

```kotlin
    /** 任务卡 upsert（REPLACE 同 id 重插，对齐 gacha metadata 覆写先例）；timestamp 恒为 startedAtMs，卡片锚定提交位置。 */
    private suspend fun persistEngineerTask(sessionId: String, state: EngineerTaskState) {
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = state.taskId,
                sessionId = sessionId,
                type = EngineerTaskState.ROOM_TYPE,
                content = state.sourceText.take(50),
                timestamp = state.startedAtMs,
                metadata = JSONObject().put("engineer_task", state.toJson()).toString(),
            )
        )
    }

    /** SSE 事件 → 任务卡状态机；结构性事件才落库（文本 delta 不触发 Room churn）。 */
    private fun onClaudeEventForTask(sessionId: String, event: ClaudeEvent, finalText: String) {
        val taskId = activeEngineerTaskId ?: return
        val current = _engineerTasks.value[taskId] ?: return
        var next = EngineerTaskReducer.reduce(current, event, canDeliverClaude.value, System.currentTimeMillis())
        if (event is ClaudeEvent.Done && !event.truncated) {
            next = next.copy(resultSummary = EngineerTaskReducer.summarize(finalText))
        }
        if (next == current) return
        _engineerTasks.update { tasks -> tasks + (taskId to next) }
        if (EngineerTaskReducer.isStructural(event)) {
            viewModelScope.launch { persistEngineerTask(sessionId, next) }
        }
    }

    /** SSE 断连/失败：任务卡 terminal 化（P1 在场态 = FAILED；P3 回联时改「后台运行中」）。 */
    private fun markActiveEngineerTaskFailed(sessionId: String, message: String?) {
        val taskId = activeEngineerTaskId ?: return
        val current = _engineerTasks.value[taskId] ?: return
        val next = EngineerTaskReducer.reduce(
            current,
            ClaudeEvent.Error(message ?: "connection lost"),
            canDeliver = false,
            nowMs = System.currentTimeMillis(),
        )
        _engineerTasks.update { tasks -> tasks + (taskId to next) }
        viewModelScope.launch { persistEngineerTask(sessionId, next) }
    }
```

（顶部 import 补 `com.mamba.picme.domain.chat.EngineerTaskState`、`com.mamba.picme.features.chat.engineer.EngineerTaskReducer`、`com.mamba.picme.data.remote.picme.ClaudeEvent`——若未引入。）

- [ ] **Step 2: sendClaudeMessage 提交插卡 + 事件接线**

`sendClaudeMessage`（:354-459）三处改动：

① 在 renderer 创建（:379-389）之前、token 校验之后，插入：

```kotlin
                // 提交即登记任务卡（spec：每次 claude-chat 提交 = 一张任务卡）
                val taskId = "task_" + UUID.randomUUID().toString()
                val initialTask = EngineerTaskReducer.initial(taskId, text, System.currentTimeMillis())
                activeEngineerTaskId = taskId
                _engineerTasks.update { tasks -> tasks + (taskId to initialTask) }
                persistEngineerTask(sessionId, initialTask)
```

② SSE 事件回调（:407-440 的 `{ event -> ... }`  lambda 开头）加一行：

```kotlin
                    onClaudeEventForTask(sessionId, event, renderer.state.text)
```

③ 回合收尾：`result.fold` 的 `onFailure` 分支（:444-450）在 `insertAgentMessage(...)` 前加：

```kotlin
                        markActiveEngineerTaskFailed(sessionId, e.message)
```

`result.fold` 之后（两分支都会经过处）与 `catch` 块内 `_streamingMessage.value = null` 旁，各加：

```kotlin
                activeEngineerTaskId = null
```

- [ ] **Step 3: 编译 + 全量单测**

Run: `./gradlew :androidApp:compileDebugKotlin && ./gradlew :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全绿

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(chat): 提交即登记任务卡——SSE 事件驱动状态机 + 结构性事件 Room upsert + 失败 terminal 化"
```

---

### Task 5: 审批动作四件套 + 气泡按钮抑制

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（resolve/deliver/retry + persistClaudeBubble 去 override）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（AgentMessageExtras/ChatMessageItem 加 suppressClaudeActions）

- [ ] **Step 1: ViewModel 审批动作**

`ChatViewModel.kt` 在 `continueClaude`（:549-551）后加：

```kotlin
    /** 审批动作回填 + 落库（US-9：同一决策点只审批一次，reducer 内幂等）。 */
    private fun resolveEngineerTask(
        taskId: String,
        resolution: EngineerTaskResolution,
        deliverBranch: String? = null,
    ) {
        val current = _engineerTasks.value[taskId] ?: return
        val next = EngineerTaskReducer.resolved(current, resolution, System.currentTimeMillis(), deliverBranch)
        if (next == current) return
        _engineerTasks.update { tasks -> tasks + (taskId to next) }
        viewModelScope.launch { persistEngineerTask(_currentSessionId.value, next) }
    }

    /** 任务卡「继续」：旧卡回填已继续 + 同 sid 续跑（新回合新卡，语义同气泡按钮）。 */
    fun continueEngineerTask(taskId: String) {
        resolveEngineerTask(taskId, EngineerTaskResolution.CONTINUED)
        sendClaudeMessage(stringContext().getString(R.string.chat_claude_continue))
    }

    /** 任务卡「到此为止」。 */
    fun abandonEngineerTask(taskId: String) = resolveEngineerTask(taskId, EngineerTaskResolution.ABANDONED)

    /** 任务卡「暂不」交付。 */
    fun skipEngineerDeliver(taskId: String) = resolveEngineerTask(taskId, EngineerTaskResolution.DELIVER_SKIPPED)

    /** 任务卡「重试」：重发原消息（新任务卡，不覆盖旧卡，US-11）。 */
    fun retryEngineerTask(taskId: String) {
        val source = _engineerTasks.value[taskId]?.sourceText ?: return
        sendClaudeMessage(source)
    }

    /** 任务卡「交付 push」：复用 ClaudeChatClient.deliver；失败保持 AWAITING_DELIVER 可重试。 */
    fun deliverEngineerTask(taskId: String) {
        val sid = _engineerTasks.value[taskId]?.sid ?: return
        viewModelScope.launch {
            val token = _serverAuthToken.value
            if (token.isBlank()) return@launch
            claudeChatClient.deliver(token, sid, "push").fold(
                onSuccess = { json ->
                    val branch = json.optString("branch")
                    if (json.optBoolean("ok", false) && branch.isNotBlank()) {
                        resolveEngineerTask(taskId, EngineerTaskResolution.DELIVERED, branch)
                    } else {
                        markEngineerTaskDeliverError(taskId, json.optString("error"))
                    }
                },
                onFailure = { error -> markEngineerTaskDeliverError(taskId, error.message) },
            )
        }
    }

    /** 交付失败：保持待审批态，错误摘要上卡（可重试，对齐 confirmClaudeDeliver pending 恢复语义）。 */
    private fun markEngineerTaskDeliverError(taskId: String, message: String?) {
        val current = _engineerTasks.value[taskId] ?: return
        val next = current.copy(
            errorSummary = stringContext().getString(R.string.claude_deliver_failed, message ?: ""),
            updatedAtMs = System.currentTimeMillis(),
        )
        _engineerTasks.update { tasks -> tasks + (taskId to next) }
        viewModelScope.launch { persistEngineerTask(_currentSessionId.value, next) }
    }
```

（import 补 `com.mamba.picme.domain.chat.EngineerTaskResolution`。）

- [ ] **Step 2: persistClaudeBubble 去掉交付 override 登记**

`persistClaudeBubble`（:522-527）删除「sid 非空 && hasFileChange → 写 `claudeDeliverOverrides`」代码块，替换为注释：

```kotlin
        // 任务卡时代（2026-09-25 起）：交付审批收口到 TASK_CARD（US-2 审批唯一入口），
        // 新气泡不再登记 claudeDeliverOverrides；confirmClaudeDeliver 仅供 legacy 内存 override 使用。
```

（气泡落库其余逻辑不动；legacy 会话的历史按钮由 Step 3 的 suppress 参数自然保留。）

- [ ] **Step 3: 气泡按钮抑制参数**

`ChatScreen.kt`：

① `AgentMessageExtras`（:1574-1580）签名加参数并把两段动作包进条件：

```kotlin
private fun AgentMessageExtras(
    message: ChatMessageUi,
    onClaudeDeliver: (String, String) -> Unit,
    onClaudeContinue: () -> Unit,
    canDeliverClaude: Boolean,
    suppressClaudeActions: Boolean,
) {
```

`:1589` 的 `message.claudeAgent?.truncatedReason?.let` 块与 `:1606` 的 `message.claudeDeliver?.let` 块，分别改为：

```kotlin
    if (!suppressClaudeActions) {
        message.claudeAgent?.truncatedReason?.let { reason ->
            // …原有内容不变…
        }
        message.claudeDeliver?.let { cd ->
            // …原有内容不变…
        }
    }
```

② `ChatMessageItem`（:1389 起）签名加 `suppressClaudeActions: Boolean = false`，并在调 `AgentMessageExtras`（:1445-1450）处透传：

```kotlin
                AgentMessageExtras(
                    message = message,
                    onClaudeDeliver = onClaudeDeliver,
                    onClaudeContinue = onClaudeContinue,
                    canDeliverClaude = canDeliverClaude,
                    suppressClaudeActions = suppressClaudeActions,
                )
```

③ 消息列表处（:556 items 之前）计算并传入：

```kotlin
                        val hasTaskCards = messages.any { msg -> msg.type == ChatMessageType.TASK_CARD }
```

`:623` 的 `ChatMessageItem(...)` 调用加实参 `suppressClaudeActions = hasTaskCards,`。

- [ ] **Step 4: 编译 + 全量单测**

Run: `./gradlew :androidApp:compileDebugKotlin && ./gradlew :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全绿

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt \
        androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): 任务卡审批四动作（继续/放弃/交付/重试）+ 气泡审批按钮收口卡片唯一入口"
```

---

### Task 6: EngineerTaskCard UI + ChatScreen 分发 + i18n 五语

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/chat/components/EngineerTaskCard.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（分发分支）
- Modify: `androidApp/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`、`values-es/strings.xml`、`values-fr/strings.xml`

- [ ] **Step 1: i18n 五语字符串**

在五个 strings.xml 各加 19 条（放在既有 `claude_*` 区块后）：

| key | EN (values) | zh-CN | zh-TW | ES | FR |
|---|---|---|---|---|---|
| chat_task_status_running | Running | 进行中 | 進行中 | En curso | En cours |
| chat_task_status_awaiting | Needs approval | 待审批 | 待審批 | Pendiente | En attente |
| chat_task_status_completed | Completed | 已完成 | 已完成 | Completado | Terminé |
| chat_task_status_failed | Failed | 失败 | 失敗 | Fallido | Échec |
| chat_task_meta_turns | %1$d turns | %1$d 轮 | %1$d 輪 | %1$d turnos | %1$d tours |
| chat_task_meta_cost | Cost $%1$.2f | 成本 $%1$.2f | 成本 $%1$.2f | Coste $%1$.2f | Coût $%1$.2f |
| chat_task_meta_files | %1$d files changed | 改 %1$d 个文件 | 改 %1$d 個檔案 | %1$d archivos | %1$d fichiers |
| chat_task_stage | Stage: %1$s | 当前阶段：%1$s | 目前階段：%1$s | Fase: %1$s | Étape : %1$s |
| chat_task_abandon | Stop here | 到此为止 | 到此為止 | Terminar | Arrêter |
| chat_task_skip_deliver | Not now | 暂不 | 暫不 | Ahora no | Plus tard |
| chat_task_retry | Retry | 重试 | 重試 | Reintentar | Réessayer |
| chat_task_resolved_continued | Continued | 已继续 | 已繼續 | Continuado | Repris |
| chat_task_resolved_delivered | Delivered | 已交付 | 已交付 | Entregado | Livré |
| chat_task_resolved_abandoned | Abandoned | 已放弃 | 已放棄 | Abandonado | Abandonné |
| chat_task_resolved_skipped | Delivery skipped | 已跳过交付 | 已跳過交付 | Entrega omitida | Livraison ignorée |
| chat_task_expand | Show details | 展开明细 | 展開明細 | Ver detalles | Voir le détail |
| chat_task_collapse | Hide details | 收起明细 | 收起明細 | Ocultar | Réduire |
| chat_task_deliver_summary | %1$d files changed, ready to deliver | 改动 %1$d 个文件，可交付 | 改動 %1$d 個檔案，可交付 | %1$d archivos, listo para entregar | %1$d fichiers, prêt à livrer |
| cd_engineer_task_card | Engineer task card | 工程师任务卡 | 工程師任務卡 | Tarjeta de tarea | Carte de tâche |

- [ ] **Step 2: EngineerTaskCard 组件**

创建 `androidApp/src/main/java/com/mamba/picme/features/chat/components/EngineerTaskCard.kt`：

```kotlin
package com.mamba.picme.features.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus

/**
 * 工程师任务卡（spec US-1~3/7~11）：状态 chip + 标题 + 阶段 + meta 行 + 状态动作区 + 展开明细。
 * 纯渲染无状态；审批动作回调由 ChatScreen 接到 ChatViewModel。
 */
@Composable
fun EngineerTaskCard(
    task: EngineerTaskState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onContinue: () -> Unit,
    onAbandon: () -> Unit,
    onDeliver: () -> Unit,
    onSkipDeliver: () -> Unit,
    onRetry: () -> Unit,
) {
    val cd = stringResource(R.string.cd_engineer_task_card)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = cd },
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineerTaskStatusChip(task)
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatElapsed(task.updatedAtMs - task.startedAtMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(text = task.sourceText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val stage = task.stage
            if (task.status == EngineerTaskStatus.RUNNING && stage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chat_task_stage, stage),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val meta = taskMetaText(task)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(text = meta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (task.status) {
                EngineerTaskStatus.AWAITING_CONTINUE -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.claude_truncated),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onContinue) { Text(stringResource(R.string.claude_continue), fontSize = 13.sp) }
                        TextButton(onClick = onAbandon) { Text(stringResource(R.string.chat_task_abandon), fontSize = 13.sp) }
                    }
                }
                EngineerTaskStatus.AWAITING_DELIVER -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.chat_task_deliver_summary, task.fileChangeCount),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    task.errorSummary?.let { deliverError ->
                        Text(text = deliverError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDeliver) { Text(stringResource(R.string.claude_deliver_mode_push), fontSize = 13.sp) }
                        TextButton(onClick = onSkipDeliver) { Text(stringResource(R.string.chat_task_skip_deliver), fontSize = 13.sp) }
                    }
                }
                EngineerTaskStatus.COMPLETED -> {
                    task.resultSummary?.takeIf { summary -> summary.isNotBlank() }?.let { summary ->
                        Spacer(Modifier.height(4.dp))
                        Text(text = summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                EngineerTaskStatus.FAILED -> {
                    Spacer(Modifier.height(6.dp))
                    task.errorSummary?.let { errorText ->
                        Text(text = errorText, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_task_retry), fontSize = 13.sp) }
                }
                EngineerTaskStatus.RUNNING -> Unit
            }
            if (task.recentStages.isNotEmpty()) {
                TextButton(onClick = onToggleExpand) {
                    Text(
                        text = stringResource(if (expanded) R.string.chat_task_collapse else R.string.chat_task_expand),
                        fontSize = 12.sp,
                    )
                }
                if (expanded) {
                    task.recentStages.asReversed().forEach { stageLabel ->
                        Text(
                            text = "· $stageLabel",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineerTaskStatusChip(task: EngineerTaskState) {
    val labelRes = when (task.resolution) {
        EngineerTaskResolution.CONTINUED -> R.string.chat_task_resolved_continued
        EngineerTaskResolution.ABANDONED -> R.string.chat_task_resolved_abandoned
        EngineerTaskResolution.DELIVERED -> R.string.chat_task_resolved_delivered
        EngineerTaskResolution.DELIVER_SKIPPED -> R.string.chat_task_resolved_skipped
        null -> when (task.status) {
            EngineerTaskStatus.RUNNING -> R.string.chat_task_status_running
            EngineerTaskStatus.AWAITING_CONTINUE, EngineerTaskStatus.AWAITING_DELIVER -> R.string.chat_task_status_awaiting
            EngineerTaskStatus.COMPLETED -> R.string.chat_task_status_completed
            EngineerTaskStatus.FAILED -> R.string.chat_task_status_failed
        }
    }
    val color = when {
        task.resolution != null || task.status == EngineerTaskStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
        task.status == EngineerTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = color,
        )
    }
}

@Composable
private fun taskMetaText(task: EngineerTaskState): String {
    val parts = mutableListOf<String>()
    if (task.turns > 0) parts += stringResource(R.string.chat_task_meta_turns, task.turns)
    task.costCents?.let { cents -> parts += stringResource(R.string.chat_task_meta_cost, cents / 100.0) }
    if (task.fileChangeCount > 0) parts += stringResource(R.string.chat_task_meta_files, task.fileChangeCount)
    return parts.joinToString(" · ")
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
```

- [ ] **Step 3: ChatScreen 分发分支**

`ChatScreen.kt` items 链（:584 OPTIMIZE_CANDIDATES 分支前）插入：

```kotlin
                            } else if (message.type == ChatMessageType.TASK_CARD && message.engineerTask != null) {
                                val task = message.engineerTask!!
                                var taskExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
                                EngineerTaskCard(
                                    task = task,
                                    expanded = taskExpanded,
                                    onToggleExpand = { taskExpanded = !taskExpanded },
                                    onContinue = { viewModel.continueEngineerTask(task.taskId) },
                                    onAbandon = { viewModel.abandonEngineerTask(task.taskId) },
                                    onDeliver = { viewModel.deliverEngineerTask(task.taskId) },
                                    onSkipDeliver = { viewModel.skipEngineerDeliver(task.taskId) },
                                    onRetry = { viewModel.retryEngineerTask(task.taskId) },
                                )
                            } else if (message.type == ChatMessageType.OPTIMIZE_CANDIDATES && message.optimizeCandidates != null) {
```

（import 补 `com.mamba.picme.features.chat.components.EngineerTaskCard`、`androidx.compose.runtime.saveable.rememberSaveable`——若无。）

- [ ] **Step 4: 编译 + aapt 资源校验 + 全量单测**

Run: `./gradlew :androidApp:assembleDebug && ./gradlew :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（资源五语无缺失），全绿

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/components/EngineerTaskCard.kt \
        androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt \
        androidApp/src/main/res/values/strings.xml androidApp/src/main/res/values-zh-rCN/strings.xml \
        androidApp/src/main/res/values-zh-rTW/strings.xml androidApp/src/main/res/values-es/strings.xml \
        androidApp/src/main/res/values-fr/strings.xml
git commit -m "feat(chat): EngineerTaskCard 原生 Compose 任务卡——五态渲染 + 展开明细 + i18n 五语"
```

---

### Task 7: /task 冒烟入口 + 设备闭环验证

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/chat/engineer/EngineerTaskSmokeSamples.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（/task 调试指令）

- [ ] **Step 1: 冒烟样本 + /task 指令**

创建 `EngineerTaskSmokeSamples.kt`：

```kotlin
package com.mamba.picme.features.chat.engineer

import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus

/** [DEV_ONLY] /task 冒烟样本：五态任务卡各一，供 ui-driver 截图与视觉走查（对齐 HtmlCardSmokeSamples 先例）。 */
object EngineerTaskSmokeSamples {

    fun all(nowMs: Long): List<EngineerTaskState> = listOf(
        EngineerTaskReducer.initial("task_smoke_running", "帮我整理相册模块的包结构", nowMs - 30_000)
            .copy(sid = "a1b2c3d4e5f6", stage = "Edit", recentStages = listOf("Read", "Grep", "Edit"), turns = 3),
        EngineerTaskReducer.initial("task_smoke_truncated", "重构 ChatViewModel", nowMs - 90_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.AWAITING_CONTINUE, truncatedReason = "max_turns", turns = 10),
        EngineerTaskReducer.initial("task_smoke_deliver", "修复登录页崩溃", nowMs - 150_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.AWAITING_DELIVER, turns = 6, fileChangeCount = 3,
                recentStages = listOf("Read", "Edit", "Bash", "file_change:Login.kt")),
        EngineerTaskReducer.initial("task_smoke_done", "写一个日期工具函数", nowMs - 300_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.COMPLETED, turns = 2, resultSummary = "已创建 DateUtils.formatRelative()"),
        EngineerTaskReducer.initial("task_smoke_failed", "升级 Koog 到 9.9", nowMs - 400_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.FAILED, errorSummary = "connection lost"),
    )
}
```

`ChatViewModel.kt` 在 /html 调试块（:1184-1191）后加：

```kotlin
        // [DEV_ONLY] 调试指令：/task 注入任务卡五态冒烟集，不走 LLM
        if (BuildConfig.DEBUG && text.trim() == "/task") {
            viewModelScope.launch {
                val sessionId = _currentSessionId.value
                ensureSessionExists(sessionId)
                EngineerTaskSmokeSamples.all(System.currentTimeMillis()).forEach { sample ->
                    _engineerTasks.update { tasks -> tasks + (sample.taskId to sample) }
                    persistEngineerTask(sessionId, sample)
                }
            }
            return
        }
```

（import 补 `com.mamba.picme.features.chat.engineer.EngineerTaskSmokeSamples`。）

- [ ] **Step 2: 全量编译 + 单测**

Run: `./gradlew :androidApp:assembleDebug && ./gradlew :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全绿（含新增 20 个任务卡测试）

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/engineer/EngineerTaskSmokeSamples.kt \
        androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "test(chat): /task 冒烟入口——任务卡五态样本注入（DEBUG only）"
```

- [ ] **Step 4: 设备闭环（装真机 → /task → 截屏验证五态 + 审批点击）**

```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

按 `skills/ui-driver` 与 `skills/adb-bot` 流程：进入 Chat → 开 AI Engineer → 输入 `/task` → 验证五张卡渲染（进行中/待审批截断/待审批交付/已完成/失败）；点「展开明细」看 recentStages；点「暂不」验证卡片变「已跳过交付」；真实发一条工程师消息验证提交即出 RUNNING 卡 + 阶段刷新 + 终态迁移。

Expected: 五态卡渲染正确、审批动作回填落库（杀进程重进卡片状态仍在）

---

### Task 8: 文档同步（[DOC-SYNC] 红线）

**Files:**
- Modify: `docs/08-UI-SPECS/screens/chat.yaml`（§13 登记 task_card，ios_status TODO）
- Modify: `androidApp/AGENTS.md`（§3.2 集成点表加任务卡行 / §2.1 Chat 行补充）
- Modify: `docs/01-PRODUCT/FEATURES.md`（§2.7 AI 工程师模式补任务卡交互）
- Modify: `docs/superpowers/specs/2026-09-25-engineer-task-card-design.md`（D2 注记实证更正）

- [ ] **Step 1: chat.yaml 登记**

对齐 `html_card_result`（chat.yaml:643-650）先例，登记 `task_card` 消息类型：类型来源（engineer 模式提交即产）、渲染（原生 EngineerTaskCard）、`ios_status: "TODO(ios-follow)"`。

- [ ] **Step 2: androidApp/AGENTS.md**

§3.2 集成点表加一行：工程师任务卡 = `sendClaudeMessage` 提交即插 TASK_CARD 消息 → `EngineerTaskReducer`（SSE 事件 → 五态）→ `EngineerTaskCard`（审批唯一入口，气泡按钮 suppress）；状态存 Room metadata `engineer_task`（REPLACE upsert）。

- [ ] **Step 3: FEATURES.md §2.7**

补任务卡交互段：提交即出卡（进度/审批/终态）、审批收口卡片、气泡明细保留。

- [ ] **Step 4: spec D2 实证更正**

`2026-09-25-engineer-task-card-design.md` D2 末尾补一句：「实证更正（2026-09-25 实现期）：iOS chat 用独立 Swift `MessageType` 枚举、不消费 shared `ChatMessageType`（`iosApp/PoLang/Features/Chat/ChatMessage.swift:50-55`），新增 TASK_CARD 无枚举面泄漏；iOS 接入时点以 chat.yaml 登记为准。」

- [ ] **Step 5: Commit**

```bash
git add docs/08-UI-SPECS/screens/chat.yaml androidApp/AGENTS.md docs/01-PRODUCT/FEATURES.md \
        docs/superpowers/specs/2026-09-25-engineer-task-card-design.md
git commit -m "docs: 任务卡 P1 落地同步——chat.yaml 登记 + AGENTS/FEATURES + spec D2 实证更正"
```

---

## Self-Review 结论（计划写完已对 spec 逐项核对）

- **US-1** → Task 4（提交插卡 + Cost/ToolUse 驱动 meta）+ Task 6（渲染）；成本降级形态已覆盖（costCents null 不显示）。
- **US-2** → Task 5 Step 3（气泡按钮 suppress）+ 气泡明细不动。
- **US-3** → Task 6（展开/收起 recentStages）；「锚定气泡」简化为卡片自身展开，已在头部决策注明。
- **US-7/8/9** → Task 2（AWAITING_CONTINUE/AWAITING_DELIVER 迁移 + resolved 幂等）+ Task 5（四动作）。
- **US-10/11** → Task 2（COMPLETED/FAILED）+ Task 4（resultSummary）+ Task 5（retry 新卡）。
- **测试决策（spec §5）**：状态机单测（Task 2）+ serde（Task 1）✓；SSE 解析/服务端路由/网关 Python 层属 P2/P3 不在 P1；UI 验证走 Task 7 冒烟 + ui-driver。
- **D1~D7**：D1（通用形状 shared）✓、D2（原生 TASK_CARD）✓、D4/P2 依赖已隔离（P1 不碰服务端）、D6 在场并发由 `_isProcessing` 覆盖、D7 任务中心属 P3。
- 类型一致性：`EngineerTaskState`/`Status`/`Resolution`/`ROOM_TYPE`/`toJson`/`parseEngineerTaskState`/`EngineerTaskReducer.{initial,reduce,resolved,summarize,isStructural}` 各 Task 引用一致。
