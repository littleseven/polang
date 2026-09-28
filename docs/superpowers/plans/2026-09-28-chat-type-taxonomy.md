# Chat Type 分类法重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 chat 消息 `type` 字段从 13 个随意值重构为「3 分类 × 8 值」的系统性分类法（`text` / `image` / `tool_*` / `data_*`），并把消息角色从 type 前缀解析升格为独立 `role` 列。

**Architecture:** Room `chat_messages` 表的 `type` 列降级为「索引冗余 + 迁移源」，读面权威是 `partsJson`（parts 模型，ADR-016）；`MessagePart` 各子类新增 `category: PartCategory`（CONTENT/TOOL/DATA）作为代码面分类载体；运行时转换器纯净化只认新 8 值，legacy 13→8 映射知识收编进仅被迁移与备份恢复使用的 `LegacyChatTypeMigration`；`MIGRATION_25_26` 单事务完成加列 + 全量改写（type/role/partsJson 一次写入）。

**Tech Stack:** Kotlin Multiplatform（`:shared` commonMain/commonTest）、Room（`:androidApp`，v25→v26）、kotlinx.serialization、Gradle（`JITPACK=true` 构建）。

**Spec:** `docs/superpowers/specs/2026-09-28-chat-type-taxonomy-design.md`（本计划逐条论证自该 spec，执行时两者同读）

## Global Constraints

以下约束来自 spec，逐字生效，每个任务的验收均隐含包含本节：

- **最终 8 值（spec §1，唯一合法集合）**：`text`、`image`（content 类，无前缀）；`tool_chart`、`tool_html`、`tool_task`、`tool_image_edit`（tool 类，`tool_` 前缀）；`data_media_results`、`data_optimize_candidates`（data 类，`data_` 前缀）。
- **命名六规（spec §1.1）**：①形状 `^{category}_{kind}$`；②`tool_`/`data_` 是仅有的保留前缀，content 类无前缀；③kind 用内容物名词，禁 UI 容器词（card/bubble/strip/viewer）；④kind 与 @Tool 函数名解耦；⑤kind 不得以保留前缀开头；⑥分类由 `MessagePart` 子类型携带，代码里永不解析字符串前缀（前缀只是设计约定 + 测试锁定）。
- **role 列取值**：`"user"` / `"agent"` 两个字符串字面量；Room 默认 `'agent'` 仅为迁移安全网，所有写路径必须显式赋值。
- **无兼容包袱（用户 2026-09-28 拍板）**：运行时转换器只认新 8 值；不做运行时 legacy 双吃；legacy→new 映射只存在于 `LegacyChatTypeMigration`，且只被 `MIGRATION_25_26` 与备份恢复调用。
- **迁移永不丢消息**：行级 `runCatching` 双保险，单行最坏结果 = 原文 Text part 兜底（spec §13 同口径）。
- **单原子提交**：全部代码 + 迁移 + 测试 + 文档一次提交（用户决策，覆盖 skill 的逐任务提交默认）。
- **构建前缀**：所有 Gradle 命令以 `JITPACK=true` 开头；验证以输出含字面量 `BUILD SUCCESSFUL` 为准。
- **不动项**：`ToolPartState`/`PartState` 枚举、浮窗 `AgentMessage` 模型、iOS 侧（零代码，M5 直接按新分类法实现）、`TurnPartsReducer`（键是 Tool 函数名 draw_chart/render_html，与 part serialName 无关）、`toHistoryPair` 的 `tool_call`/`tool_result` 标签。
- **工作区隔离（AGENTS.md §3.4）**：全程在 `.worktrees/chat-type-taxonomy` + 分支 `refactor/chat-type-taxonomy` 中进行。
- **主仓 3 处无关本地改动必须原样保留**：modified `docs/superpowers/plans/2026-08-13-ios-chat-rich-features.md`、modified `docs/superpowers/specs/2026-08-13-ios-chat-rich-features-design.md`、untracked `docs/reviews/2026-09-27-muse-calendar-replication-research.md`——任何 `git add` 只加本任务文件。

---

### Task 0: 隔离工作区

**Files:**
- Create: `.worktrees/chat-type-taxonomy/`（git worktree）
- Create: 分支 `refactor/chat-type-taxonomy`（自 main）

- [ ] **Step 1: 创建 worktree 与分支**

```bash
git worktree add .worktrees/chat-type-taxonomy -b refactor/chat-type-taxonomy main
```

- [ ] **Step 2: 拷入本地构建配置**

```bash
cp local.properties .worktrees/chat-type-taxonomy/local.properties
```

- [ ] **Step 3: 验证 worktree 可用**

Run: `cd .worktrees/chat-type-taxonomy && git status && git log --oneline -1`
Expected: 分支为 `refactor/chat-type-taxonomy`，HEAD 与 main 一致，工作区干净（除 untracked local.properties）。

> 后续 Task 1~8 的所有命令与编辑均在 `.worktrees/chat-type-taxonomy` 内进行。

---

### Task 1: MessagePart serialName 改值 + PartCategory 分类枚举

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePart.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/MessagePartsCodecTest.kt`

**Interfaces:**
- Produces: `enum class PartCategory { CONTENT, TOOL, DATA }`；`MessagePart.category: PartCategory`（抽象只读属性，getter-only override，不进序列化）；6 个新 serialName——Chart=`tool_chart`、HtmlCard=`tool_html`、TaskCard=`tool_task`、MediaResults=`data_media_results`、EditResult=`tool_image_edit`、OptimizeCandidates=`data_optimize_candidates`（Text=`text`、Image=`image` 不变）。后续所有任务的编码断言与本任务锁定的 serialName 一致。

- [ ] **Step 1: 写前缀锁失败测试（先行钉死分类法）**

在 `MessagePartsCodecTest.kt` 追加两个测试（kotlinx 原生 descriptor 反射，不引 kotlin-reflect，KMP commonTest 可用）：

```kotlin
@Test
fun `sealed descriptor locks the 8-value taxonomy`() {
    val descriptor = MessagePart.serializer().descriptor
    assertEquals(8, descriptor.elementsCount)
    val serialNames = (0 until descriptor.elementsCount)
        .map { descriptor.getElementDescriptor(it).serialName }
        .toSet()
    assertEquals(
        setOf(
            "text", "image",
            "tool_chart", "tool_html", "tool_task", "tool_image_edit",
            "data_media_results", "data_optimize_candidates",
        ),
        serialNames,
    )
}

@Test
fun `serial name prefix matches declared category`() {
    val parts: List<MessagePart> = listOf(
        MessagePart.Text("p0", "hi"),
        MessagePart.Image("p0", ref = "u"),
        MessagePart.Chart("p0", svg = "<svg/>"),
        MessagePart.HtmlCard("p0", html = "<html/>"),
        MessagePart.TaskCard("p0", toolCallId = "t", task = EngineerTaskState(taskId = "t", sourceText = "做")),
        MessagePart.EditResult("p0", description = "已提亮"),
        MessagePart.MediaResults("p0", MediaResultsUi(items = emptyList())),
        MessagePart.OptimizeCandidates("p0", OptimizeCandidateGroup(sourceImageUri = "u", scene = "s", recommendedIndex = 0, drawIndex = 1, candidates = emptyList(), usedFingerprints = emptyList())),
    )
    parts.forEach { part ->
        val json = MessagePartsCodec.encode(listOf(part))
        val expectedPrefix = when (part.category) {
            PartCategory.CONTENT -> null
            PartCategory.TOOL -> "\"type\":\"tool_"
            PartCategory.DATA -> "\"type\":\"data_"
        }
        if (expectedPrefix != null) assertTrue(json.contains(expectedPrefix), "$json should carry prefix for $part")
        // content 类反锁：不得带保留前缀
        if (part.category == PartCategory.CONTENT) {
            assertTrue(!json.contains("\"type\":\"tool_") && !json.contains("\"type\":\"data_"))
        }
    }
}
```

（注：MediaResultsUi / OptimizeCandidateGroup 的构造函数实参以 commonMain 现有定义为准——`MessagePartsCodecTest.kt` 已有现成 fixture 可复制。）

- [ ] **Step 2: 同步修正既有 discriminator 断言为失败态**

`MessagePartsCodecTest.kt` 中 `encode uses type discriminator aligned with legacy room types` 测试当前断言 `"type":"chart"` 等旧值——把断言目标改为新值（`"type":"tool_chart"` 等），此时测试失败（实现未改）。

- [ ] **Step 3: 跑测试确认失败**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.domain.chat.MessagePartsCodecTest"`
Expected: FAIL（descriptor 里还是旧 serialName）。

- [ ] **Step 4: 实现 MessagePart 改造**

`MessagePart.kt`：

```kotlin
/** part 三分类（spec §1.2）：内容物 / 工具产物 / 数据载荷。由子类型携带，代码面永不解析字符串前缀。 */
enum class PartCategory { CONTENT, TOOL, DATA }

@Serializable
sealed interface MessagePart {
    val partId: String
    /** 分类：getter-only 覆盖，无后备字段 → kotlinx.serialization 不序列化本属性，线格式零变化。 */
    val category: PartCategory
}
```

8 个子类各自改 `@SerialName` 并加一行覆盖（示例两枚，其余同型）：

```kotlin
@Serializable
@SerialName("text")
data class Text(
    override val partId: String,
    val markdown: String,
    val state: PartState = PartState.DONE,
) : MessagePart {
    override val category: PartCategory get() = PartCategory.CONTENT
}

@Serializable
@SerialName("tool_chart")
data class Chart(
    override val partId: String,
    val svg: String,
    val state: ToolPartState = ToolPartState.OUTPUT_AVAILABLE,
) : MessagePart {
    override val category: PartCategory get() = PartCategory.TOOL
}
```

对应关系：Text/Image→CONTENT；Chart/HtmlCard/TaskCard/EditResult→TOOL；MediaResults/OptimizeCandidates→DATA。

- [ ] **Step 5: 更新 MessagePartsCodec KDoc 的 discriminator 值清单**

`MessagePartsCodec.kt` 头注释里的 discriminator 值列表改为新 8 值（实现代码不动）。

- [ ] **Step 6: 跑测试确认通过**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.domain.chat.MessagePartsCodecTest"`
Expected: PASS。此时其他引用旧 serialName 的测试失败属预期，Task 2/3 收口。

---

### Task 2: 转换器纯净化 + LegacyChatTypeMigration 收编 legacy 映射

**Files:**
- Rename: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/LegacyMessageParts.kt` → `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsConverter.kt`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/LegacyChatTypeMigration.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/LegacyMessagePartsConverterTest.kt` → 重写为 `MessagePartsConverterTest.kt`
- Test: 新建 `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/LegacyChatTypeMigrationTest.kt`

**Interfaces:**
- Consumes: Task 1 的新 serialName 与 `PartCategory`。
- Produces: `MessagePartsConverter.toParts(type: String, content: String, metadata: String?, role: ModelInputRole): List<MessagePart>`（只认新 8 值）；`LegacyChatTypeMigration.NEW_TYPES: Set<String>`、`LegacyChatTypeMigration.MigratedRow(type, role, parts)`、`LegacyChatTypeMigration.map(legacyType, content, metadata): MigratedRow`。Task 4 迁移与 Task 6 备份恢复按此签名调用。

- [ ] **Step 1: 重写转换器测试（新 8 值 + image 角色双分支 + 未知兜底）**

新建 `MessagePartsConverterTest.kt`（替换原 `LegacyMessagePartsConverterTest.kt`），覆盖：

```kotlin
class MessagePartsConverterTest {

    @Test
    fun `text converts to single text part`() {
        assertEquals(
            listOf(MessagePart.Text("p0", "你好", PartState.DONE)),
            MessagePartsConverter.toParts("text", "你好", null, ModelInputRole.USER),
        )
    }

    @Test
    fun `image with user role and imageUri metadata splits into image plus text`() {
        val metadata = """{"imageUri":"/i.jpg"}"""
        assertEquals(
            listOf(
                MessagePart.Image("p0", ref = "/i.jpg"),
                MessagePart.Text("p1", "配文", PartState.DONE),
            ),
            MessagePartsConverter.toParts("image", "配文", metadata, ModelInputRole.USER),
        )
    }

    @Test
    fun `image with user role without metadata uses content as ref`() {
        assertEquals(
            listOf(MessagePart.Image("p0", ref = "/data/img.jpg")),
            MessagePartsConverter.toParts("image", "/data/img.jpg", null, ModelInputRole.USER),
        )
    }

    @Test
    fun `image with agent role reads ref and saved from metadata`() {
        val metadata = """{"imageUri":"/gen.jpg","saved":true}"""
        assertEquals(
            listOf(MessagePart.Image("p0", ref = "/gen.jpg", saved = true)),
            MessagePartsConverter.toParts("image", "说明", metadata, ModelInputRole.ASSISTANT),
        )
    }

    @Test
    fun `tool and data types convert to their parts`() {
        assertIs<MessagePart.Chart>(MessagePartsConverter.toParts("tool_chart", "<svg/>", null, ModelInputRole.ASSISTANT).single())
        assertIs<MessagePart.HtmlCard>(MessagePartsConverter.toParts("tool_html", "<html/>", null, ModelInputRole.ASSISTANT).single())
        val taskMeta = """{"engineer_task":{"taskId":"t-1","sourceText":"做","status":"COMPLETED","startedAtMs":1,"updatedAtMs":2}}"""
        assertIs<MessagePart.TaskCard>(MessagePartsConverter.toParts("tool_task", "做", taskMeta, ModelInputRole.ASSISTANT).single())
        val editMeta = """{"imageUri":"/i.jpg"}"""
        assertIs<MessagePart.EditResult>(MessagePartsConverter.toParts("tool_image_edit", "已提亮", editMeta, ModelInputRole.ASSISTANT).single())
        assertIs<MessagePart.MediaResults>(MessagePartsConverter.toParts("data_media_results", """[{"id":1,"uri":"u","type":"PHOTO","captureDate":1,"fileName":"f"}]""", null, ModelInputRole.ASSISTANT).single())
        val optMeta = """{"sourceImageUri":"u","scene":"s","recommendedIndex":0,"drawIndex":1,"candidates":[],"usedFingerprints":[]}"""
        assertIs<MessagePart.OptimizeCandidates>(MessagePartsConverter.toParts("data_optimize_candidates", "挑一张", optMeta, ModelInputRole.ASSISTANT).single())
    }

    @Test
    fun `unknown type falls back to plain text keeping content`() {
        assertEquals(
            listOf(MessagePart.Text("p0", "原文", PartState.DONE)),
            MessagePartsConverter.toParts("mystery", "原文", null, ModelInputRole.ASSISTANT),
        )
    }
}
```

同时新建 `LegacyChatTypeMigrationTest.kt`，13 个 legacy 值全枚举 + 未知值兜底：

```kotlin
class LegacyChatTypeMigrationTest {

    @Test
    fun `all 13 legacy types map into the new taxonomy with role`() {
        val cases = mapOf(
            "user_text" to ("text" to "user"),
            "agent_text" to ("text" to "agent"),
            "command" to ("text" to "agent"),
            "plan_preview" to ("text" to "agent"),
            "user_image" to ("image" to "user"),
            "user_image_text" to ("image" to "user"),
            "agent_image" to ("image" to "agent"),
            "chart" to ("tool_chart" to "agent"),
            "html_card" to ("tool_html" to "agent"),
            "task_card" to ("tool_task" to "agent"),
            "agent_edit_result" to ("tool_image_edit" to "agent"),
            "media_results" to ("data_media_results" to "agent"),
            "optimize_candidates" to ("data_optimize_candidates" to "agent"),
        )
        cases.forEach { (legacy, expected) ->
            val row = LegacyChatTypeMigration.map(legacy, "c", null)
            assertEquals(expected.first, row.type, "type for $legacy")
            assertEquals(expected.second, row.role, "role for $legacy")
            assertTrue(row.parts.isNotEmpty(), "parts for $legacy")
        }
    }

    @Test
    fun `unknown legacy type falls back to text agent`() {
        val row = LegacyChatTypeMigration.map("mystery", "原文", null)
        assertEquals("text", row.type)
        assertEquals("agent", row.role)
        assertEquals(listOf(MessagePart.Text("p0", "原文", PartState.DONE)), row.parts)
    }

    @Test
    fun `legacy user_image_text keeps image plus text parts`() {
        val row = LegacyChatTypeMigration.map("user_image_text", "配文", """{"imageUri":"/i.jpg"}""")
        assertEquals("image", row.type)
        assertEquals("user", row.role)
        assertEquals(
            listOf(MessagePart.Image("p0", ref = "/i.jpg"), MessagePart.Text("p1", "配文", PartState.DONE)),
            row.parts,
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.domain.chat.*ConverterTest" --tests "com.mamba.picme.domain.chat.LegacyChatTypeMigrationTest"`
Expected: 编译失败（`MessagePartsConverter` / `LegacyChatTypeMigration` 不存在）。

- [ ] **Step 3: 实现纯净化转换器**

`git mv LegacyMessageParts.kt MessagePartsConverter.kt`，改造为（私有解析函数 `parseMetadata/str/bool/int/strList/parseMediaResults/parseHtmlCardMeta/parseTaskCard/parseOptimizeGroup` 与 `fallbackText` 从原文件原样保留）：

```kotlin
/**
 * (type, content, metadata, role) → parts 的运行时转换器（M1 双写接缝的写侧纯函数）。
 *
 * 纯净化口径（spec §4.2）：只认新 8 值分类法；legacy 13→8 映射知识在
 * [LegacyChatTypeMigration]，本转换器不双吃。行级兜底不变：任何异常/空结果 → 原文 Text part。
 */
object MessagePartsConverter {

    fun toParts(type: String, content: String, metadata: String?, role: ModelInputRole): List<MessagePart> =
        runCatching { convert(type, content, metadata, role) }
            .getOrElse { fallbackText(content) }
            .ifEmpty { fallbackText(content) }

    private fun convert(type: String, content: String, metadata: String?, role: ModelInputRole): List<MessagePart> {
        val meta = parseMetadata(metadata)
        return when (type) {
            "text" -> listOf(MessagePart.Text("p0", content, PartState.DONE))
            "image" -> when {
                role == ModelInputRole.USER && meta?.str("imageUri") != null ->
                    listOf(
                        MessagePart.Image("p0", ref = meta.str("imageUri")!!),
                        MessagePart.Text("p1", content, PartState.DONE),
                    )
                role == ModelInputRole.USER -> listOf(MessagePart.Image("p0", ref = content))
                else -> listOf(
                    MessagePart.Image(
                        "p0",
                        ref = meta?.str("imageUri") ?: content,
                        saved = meta?.bool("saved") ?: false,
                    ),
                )
            }
            "tool_chart" -> listOf(MessagePart.Chart("p0", svg = content))
            "tool_html" -> listOf(MessagePart.HtmlCard("p0", html = content, meta = parseHtmlCardMeta(meta)))
            EngineerTaskState.ROOM_TYPE -> listOf(parseTaskCard(meta))
            "tool_image_edit" -> listOf(
                MessagePart.EditResult(
                    "p0",
                    ref = meta?.str("imageUri"),
                    description = content,
                    suggestions = meta?.strList("suggestions") ?: emptyList(),
                    saved = meta?.bool("saved") ?: false,
                ),
            )
            "data_media_results" -> listOf(MessagePart.MediaResults("p0", parseMediaResults(content, meta)))
            OptimizeCandidateGroup.MESSAGE_TYPE -> listOf(MessagePart.OptimizeCandidates("p0", parseOptimizeGroup(metadata)))
            else -> fallbackText(content)
        }
    }

    // ... 原文件的私有解析函数与 fallbackText 原样保留 ...
}
```

- [ ] **Step 4: 新建 LegacyChatTypeMigration**

```kotlin
package com.mamba.picme.domain.chat

/**
 * legacy 13 值 → 新 8 值分类法的唯一映射知识库（spec §4.2）。
 *
 * 只被两处调用：MIGRATION_25_26（Room 全量改写）与备份恢复（旧备份转正）。
 * 运行时不双吃——新写路径产出新值后永不经过这里。
 *
 * 注意：when 分支必须匹配 legacy 字面量（"task_card"/"optimize_candidates"），
 * 禁用 EngineerTaskState.ROOM_TYPE / OptimizeCandidateGroup.MESSAGE_TYPE 常量——
 * 常量在本重构后已是新值，而本映射的输入是 legacy 值。
 */
object LegacyChatTypeMigration {

    /** 新分类法 8 值全集（备份恢复据此判断行是新值还是 legacy）。 */
    val NEW_TYPES: Set<String> = setOf(
        "text", "image",
        "tool_chart", "tool_html", "tool_task", "tool_image_edit",
        "data_media_results", "data_optimize_candidates",
    )

    data class MigratedRow(val type: String, val role: String, val parts: List<MessagePart>)

    fun map(legacyType: String, content: String, metadata: String?): MigratedRow {
        val role = if (legacyType.startsWith("user_")) "user" else "agent"
        val newType = when (legacyType) {
            "user_text", "agent_text", "command", "plan_preview" -> "text"
            "user_image", "user_image_text", "agent_image" -> "image"
            "chart" -> "tool_chart"
            "html_card" -> "tool_html"
            "task_card" -> "tool_task" // legacy 字面量，禁用 ROOM_TYPE 常量
            "agent_edit_result" -> "tool_image_edit"
            "media_results" -> "data_media_results"
            "optimize_candidates" -> "data_optimize_candidates" // legacy 字面量
            else -> "text"
        }
        return MigratedRow(newType, role, MessagePartsConverter.toParts(newType, content, metadata, roleOf(role)))
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.domain.chat.MessagePartsConverterTest" --tests "com.mamba.picme.domain.chat.LegacyChatTypeMigrationTest"`
Expected: PASS。引用旧名 `LegacyMessagePartsConverter` 的文件编译失败属预期，Task 3 收口。

---

### Task 3: role 升格 + ChatMessageType 瘦身 + shared 消费面接线

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt`（+`role: ModelInputRole` 无默认值；`OptimizeCandidateGroup.MESSAGE_TYPE` = `"data_optimize_candidates"`）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessageType.kt`（删 COMMAND、PLAN_PREVIEW → 11 值）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatModelInput.kt`（`roleOf(roomRole)` 切口径；删 `ChatMessage.modelRole`；`toModelInput()` 用 `role`）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatListFlattener.kt`（`modelRole`→`role` 三处；`rendersAsWholeMessage()` 删 COMMAND/PLAN_PREVIEW 分支）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/EngineerTaskState.kt`（`ROOM_TYPE` = `"tool_task"`）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`、`androidApp/src/main/java/com/mamba/picme/features/chat/FloatingChatBubbleService.kt`（`modelRole`→`role`）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/ChatModelInputTest.kt`、`ChatListFlattenerTest.kt`、`TaskCardOverlayTest.kt`（补 role 实参 + 适配）

**Interfaces:**
- Consumes: Task 2 的 `MessagePartsConverter` / `roleOf`。
- Produces: `ChatMessage.role: ModelInputRole`（无默认值，编译器驱动全调用点补齐）；`roleOf(roomRole: String): ModelInputRole`（`"user"`→USER，其余→ASSISTANT）。androidApp 侧 Task 4/6 依赖同一 `roleOf` 口径。

- [ ] **Step 1: 改 ChatMessageType 与常量值**

`ChatMessageType.kt` 删除 `COMMAND`、`PLAN_PREVIEW` 两个枚举值（保留其余 11 个：USER_TEXT/AGENT_TEXT/USER_IMAGE/USER_IMAGE_TEXT/AGENT_IMAGE/AGENT_EDIT_RESULT/MEDIA_RESULTS/CHART/HTML_CARD/TASK_CARD/OPTIMIZE_CANDIDATES——UI 枚举，渲染分支仍需要）。`EngineerTaskState.kt` L36 `ROOM_TYPE = "task_card"` 改为 `"tool_task"`；`ChatMessage.kt` 中 `OptimizeCandidateGroup.MESSAGE_TYPE` 改为 `"data_optimize_candidates"`。

- [ ] **Step 2: ChatMessage 加 role 字段 + ChatModelInput 切换**

`ChatMessage.kt`：data class 增加 `val role: ModelInputRole`（**无默认值**——让编译器列出全部 ~24 个 shared 测试构造点）。

`ChatModelInput.kt`：

```kotlin
/** Room role 列（"user"/"agent"）→ 模型角色（spec §2：role 升格为独立列后的唯一派生点）。 */
fun roleOf(roomRole: String): ModelInputRole =
    if (roomRole == "user") ModelInputRole.USER else ModelInputRole.ASSISTANT
```

删除 `val ChatMessage.modelRole` 扩展；`ChatMessage.toModelInput()` 改为 `parts.toModelInput(role, id)`。

- [ ] **Step 3: ChatListFlattener 适配**

L50、L97、L100 的 `message.modelRole` → `message.role`；`rendersAsWholeMessage()` 删除 `type == ChatMessageType.COMMAND` 与 `type == ChatMessageType.PLAN_PREVIEW` 两个分支（command/plan_preview 已并入 text，走 Text part 常规渲染）。其余（parts.isEmpty()/claudeAgent/AGENT_IMAGE/AGENT_EDIT_RESULT 分支、persisted payload 集合、contentTypeOf/toolStateOrNull/cardContentType）不动。

- [ ] **Step 4: androidApp 两处 modelRole 引用切换**

`ChatScreen.kt`（import + L1492）与 `FloatingChatBubbleService.kt`（import + L688）：`message.modelRole` → `message.role`（`modelRole` import 删除）。

- [ ] **Step 5: 修 shared 测试至全绿（编译器驱动）**

Run: `JITPACK=true ./gradlew :shared:compileTestKotlinJvm`（或 jvmTest 直接暴露全部错误）
按编译错误逐点修：

- `ChatModelInputTest.kt`：`legacyMessage` helper 改为经 `LegacyChatTypeMigration.map(type, content, metadata)` 构造（拿 role+parts 一次到位）；5 处裸 `ChatMessage(` 构造补 `role = ModelInputRole.ASSISTANT`（USER 场景补 USER）；`role derives from legacy type` 测试改写为断言 `roleOf("user")==USER`、`roleOf("agent")==ASSISTANT` 及 legacyMessage 构造出的 `role` 与期望一致。
- `ChatListFlattenerTest.kt`：~17 处 `ChatMessage(` 补 role（agentText helper 内补 ASSISTANT；user 场景补 USER）；L244 的 COMMAND 类型测试改写——COMMAND 枚举已删，改用 `AGENT_TEXT` + 普通 Text part 断言其**不再**走 whole-message 渲染分支（行为变化钉测试：command 并入 text 后按常规文本渲染）。
- `TaskCardOverlayTest.kt`：2 处构造补 `role = ModelInputRole.ASSISTANT`。

- [ ] **Step 6: 跑 shared 全量测试**

Run: `JITPACK=true ./gradlew :shared:jvmTest`
Expected: PASS（输出含 BUILD SUCCESSFUL）。`ChatModelInputTest` 中 images/agent_edit_result 等语义钉测试应原样通过（转换语义不变，变的只是入路由）。

---

### Task 4: Room v26——Entity/DAO/接缝/迁移

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageEntity.kt`（+`role` 列；type KDoc 改写）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageDao.kt`（SQL 字面量换新值 + role 口径）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageParts.kt`（`deriveParts` 接 role；`toModelInputItems` 用 `roleOf(role)`；新增 `migrateChatMessageTypes`）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`（version 25→26；注册 MIGRATION_25_26）
- Test: `androidApp/src/test/java/com/mamba/picme/data/local/ChatMessagePartsTest.kt`、`androidApp/src/test/java/com/mamba/picme/data/local/ChatMessageDaoTest.kt`

**Interfaces:**
- Consumes: Task 2 `LegacyChatTypeMigration.map` / `MessagePartsConverter.toParts(type, content, metadata, role)`；Task 3 `roleOf`。
- Produces: Room v26 schema（chat_messages 含 `role TEXT NOT NULL DEFAULT 'agent'`）；`MIGRATION_25_26`；写路径实体构造必须显式传 `role`（Task 6 全部接线）。

- [ ] **Step 1: Entity 加 role 列 + type KDoc 降级声明**

`ChatMessageEntity.kt` 在 `type` 后加：

```kotlin
    /**
     * 消息角色："user" / "agent"（spec §2 升格列；默认值 'agent' 仅为迁移安全网，
     * 所有写路径必须显式赋值）。
     */
    val role: String = "agent",
```

`type` 的 KDoc 改为：

```kotlin
    /**
     * 新分类法 8 值（spec §1）：text, image, tool_chart, tool_html, tool_task,
     * tool_image_edit, data_media_results, data_optimize_candidates。
     * 降级定位：索引冗余 + 迁移源；读面权威是 partsJson。
     */
```

- [ ] **Step 2: DAO SQL 字面量更新**

`ChatMessageDao.kt`：
- L39 `type = 'task_card'` → `type = 'tool_task'`。
- `getLatestMediaResultsSinceLastUserMessage`：`type = 'media_results'` → `type = 'data_media_results'`；`type LIKE 'user\_%' ESCAPE '\'` → `role = 'user'`（role 列取代前缀解析，正是本重构的动机）。

- [ ] **Step 3: 接缝接 role + 新增迁移函数**

`ChatMessageParts.kt`：

```kotlin
/** 由新 8 值列现算 parts（纯函数，行失败转换器内部已兜底）。 */
fun ChatMessageEntity.deriveParts(): List<MessagePart> =
    MessagePartsConverter.toParts(type, content, metadata, roleOf(role))
```

`toModelInputItems()` 中 `roleOf(type)` → `roleOf(role)`。文件头部 KDoc 的 M1 描述同步（LegacyMessagePartsConverter → MessagePartsConverter；role 列参与现算）。

新增（仿 `backfillChatMessageParts` 结构）：

```kotlin
/**
 * MIGRATION_25_26 第二段：逐行把 legacy (type, content, metadata) 经 [LegacyChatTypeMigration]
 * 转为新 (type, role, partsJson) 一次写入。
 *
 * 必须先映射再写回（而非先改 type 再重编码）：user_image/user_image_text/agent_image
 * 同归 "image"，先改 type 会丢失区分信息。行级 runCatching 双保险：单行最坏结果 =
 * 原文 Text part（text/agent），迁移绝不丢消息。
 *
 * 游标遍历中 UPDATE 同表安全性同 backfillChatMessageParts：扫描只读 id/type/content/metadata，
 * UPDATE 写非索引列且不动 PK，每行恰好访问一次。
 */
internal fun migrateChatMessageTypes(database: SupportSQLiteDatabase) {
    val startedAt = System.currentTimeMillis()
    var rows = 0
    Logger.i(TAG, "MIGRATION_25_26 rewrite chat_messages type/role/partsJson started")
    val update = database.compileStatement("UPDATE `chat_messages` SET `type` = ?, `role` = ?, `partsJson` = ? WHERE `id` = ?")
    update.use {
        val cursor = database.query("SELECT `id`, `type`, `content`, `metadata` FROM `chat_messages`")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val type = it.getString(1)
                val content = it.getString(2)
                val metadata = if (it.isNull(3)) null else it.getString(3)
                val migrated = runCatching { LegacyChatTypeMigration.map(type, content, metadata) }.getOrElse {
                    LegacyChatTypeMigration.MigratedRow(
                        "text", "agent",
                        listOf(MessagePart.Text("p0", content, PartState.DONE)),
                    )
                }
                update.clearBindings()
                update.bindString(1, migrated.type)
                update.bindString(2, migrated.role)
                update.bindString(3, MessagePartsCodec.encode(migrated.parts))
                update.bindString(4, id)
                update.executeUpdateDelete()
                rows++
            }
        }
    }
    Logger.i(TAG, "MIGRATION_25_26 rewrite finished: $rows rows in ${System.currentTimeMillis() - startedAt}ms")
}
```

import 相应更新（`LegacyChatTypeMigration`、`MessagePartsConverter`、`MessagePart`、`PartState`）。

- [ ] **Step 4: AppDatabase v26 注册迁移**

`AppDatabase.kt`：version 25→26；仿 MIGRATION_24_25 注册：

```kotlin
internal val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `role` TEXT NOT NULL DEFAULT 'agent'")
        migrateChatMessageTypes(database)
    }
}
```

两段在同一 `migrate()` 内 = Room 单事务（spec §5）。

- [ ] **Step 5: 修 androidApp 单元测试至全绿**

- `ChatMessagePartsTest.kt`：构造 `ChatMessageEntity` 处补 `role`（或依赖默认值处显式化）；`deriveParts`/`toModelInputItems` 断言由旧 type 前缀口径改为 role 列口径。
- `ChatMessageDaoTest.kt`：SQL 行为测试的 fixture 数据 type 值更新为新 8 值；media_results 相关用例补 `role = "user"` 行验证 `getLatestMediaResultsSinceLastUserMessage` 边界。

Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.data.local.*"`
Expected: PASS。ChatViewModel 等编译错误属预期，Task 6 收口。

---

### Task 5: 迁移 androidTest（真机/模拟器）

**Files:**
- Modify: `androidApp/src/androidTest/java/com/mamba/picme/data/local/ChatMessagePartsMigrationTest.kt`

**Interfaces:**
- Consumes: Task 4 的 `MIGRATION_24_25`、`MIGRATION_25_26`。

- [ ] **Step 1: 旧壳升 v26 双迁移**

现有 v24→v25 迁移测试的 `addMigrations(...)` 追加 `MIGRATION_25_26`，helper 数据库创建版本与断言目标升 v26，验证双迁移串联后 partsJson 仍在且为新值编码。

- [ ] **Step 2: 新增 v25 fixture 全量改写测试**

造 v25 fixture（手工 `execSQL` 建 v25 schema + 插入 13 种 legacy type 各一行 + 一行未知 type + 一行 metadata 损坏 + 一行空 content），跑 v25→v26，逐行断言：

```kotlin
// 伪代码骨架（Room MigrationTestHelper）：
val db = helper.createDatabase(TEST_DB, 25).apply {
    execSQL("INSERT INTO chat_messages (id, sessionId, type, content, timestamp, metadata, partsJson) VALUES ('m1','default','user_text','你好',1,NULL,NULL)")
    execSQL("INSERT INTO chat_messages (id, sessionId, type, content, timestamp, metadata, partsJson) VALUES ('m2','default','user_image_text','配文',1,'{\"imageUri\":\"/i.jpg\"}',NULL)")
    execSQL("INSERT INTO chat_messages (id, sessionId, type, content, timestamp, metadata, partsJson) VALUES ('m3','default','task_card','做',1,'{\"engineer_task\":{\"taskId\":\"t\",\"sourceText\":\"做\",\"status\":\"COMPLETED\",\"startedAtMs\":1,\"updatedAtMs\":2}}',NULL)")
    execSQL("INSERT INTO chat_messages (id, sessionId, type, content, timestamp) VALUES ('m4','default','mystery','原文',1)")
    // ... 13 值全枚举 + 损坏 metadata 行 ...
    close()
}
val migrated = helper.runMigrationsAndValidate(TEST_DB, 26, true, MIGRATION_25_26)
// 断言 m1: type='text', role='user', partsJson 解码 = [Text("p0","你好")]
// 断言 m2: type='image', role='user', partsJson 解码 = [Image(ref=/i.jpg), Text("配文")]
// 断言 m3: type='tool_task', role='agent', partsJson 解码 = [TaskCard(taskId=t)]
// 断言 m4: type='text', role='agent', parts 兜底原文
```

每行断言三件套：`type` 新值、`role` 正确、partsJson 可解码且语义等价（spec §8 矩阵）。

- [ ] **Step 3: 跑 androidTest**

Run: `JITPACK=true ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mamba.picme.data.local.ChatMessagePartsMigrationTest`
Expected: PASS。无设备/模拟器可用时记录为挂起项并在提交信息中声明。

---

### Task 6: androidApp 写路径与消费面全量接线

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（toUiModel 重写 + 全部写路径字面量）
- Modify: `androidApp/src/main/java/com/mamba/picme/ChatTitleGenerator.kt`、`androidApp/src/main/java/com/mamba/picme/core/agent/RemoteCommandDispatcher.kt`、`androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/core/agenttools/ChatHistoryModelInput.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/backup/model/TagDataBackup.kt`（BackupChatMessage +`role: String? = null`）
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/backup/TagDataBackupRepository.kt`（导出带 role；恢复新/旧备份双分支）

**Interfaces:**
- Consumes: 全部前序任务的公共接口（`roleOf`、`NEW_TYPES`、`LegacyChatTypeMigration.map`、Entity.role）。

- [ ] **Step 1: ChatViewModel 写路径字面量替换（13 处锚点）**

按下表逐处替换（行号为重构前锚点，以实际 grep 为准），同时实体构造补 `role = ...`：

| 旧 | 新 type | role |
|---|---|---|
| `"user_text"`（L442/1672/2792） | `"text"` | `"user"` |
| `"agent_text"`（L637/1937/2983/3454） | `"text"` | `"agent"` |
| `user_image_text`/`user_image`（L1672/3250） | `"image"` | `"user"` |
| `"agent_image"`（L3019/3150） | `"image"` | `"agent"` |
| `"chart"`（L2530；`modelUsed="chart"` L2533 不动——它是模型标识非 type） | `"tool_chart"` | `"agent"` |
| `"html_card"`（L2563；L2584 判断同步） | `"tool_html"` | `"agent"` |
| `"media_results"`（L2896） | `"data_media_results"` | `"agent"` |
| `"agent_edit_result"`（L3064） | `"tool_image_edit"` | `"agent"` |

- [ ] **Step 2: toUiModel 重写为 parts 优先**

```kotlin
private fun toUiModel(entity: ChatMessageEntity): ChatMessage {
    val parts = entity.decodePartsOrLegacy()
    val primary = parts.firstOrNull()
    val role = roleOf(entity.role)
    val msgType = when (primary) {
        is MessagePart.Text -> if (role == ModelInputRole.USER) ChatMessageType.USER_TEXT else ChatMessageType.AGENT_TEXT
        is MessagePart.Image -> when {
            role == ModelInputRole.USER && parts.any { it is MessagePart.Text } -> ChatMessageType.USER_IMAGE_TEXT
            role == ModelInputRole.USER -> ChatMessageType.USER_IMAGE
            else -> ChatMessageType.AGENT_IMAGE
        }
        is MessagePart.Chart -> ChatMessageType.CHART
        is MessagePart.HtmlCard -> ChatMessageType.HTML_CARD
        is MessagePart.TaskCard -> ChatMessageType.TASK_CARD
        is MessagePart.EditResult -> ChatMessageType.AGENT_EDIT_RESULT
        is MessagePart.MediaResults -> ChatMessageType.MEDIA_RESULTS
        is MessagePart.OptimizeCandidates -> ChatMessageType.OPTIMIZE_CANDIDATES
        null -> if (role == ModelInputRole.USER) ChatMessageType.USER_TEXT else ChatMessageType.AGENT_TEXT
    }
    // 字段投影：chartSvg/htmlContent/htmlCardMeta/imageUri/imageSaved/mediaResults/
    // optimizeCandidates/engineerTask 一律从 parts 提取（不再按 type 字符串守卫）；
    // performance/claudeAgent 仍从 metadata 解析（现状不变）。
}
```

- [ ] **Step 3: 其余写路径与消费点**

- `ChatTitleGenerator.kt`：when 分支 `"user_image"` → `"image"`（imageTitle）、`"user_text"` → `"text"`（sanitizeTitle）；调用点传入值同步核实。
- `RemoteCommandDispatcher.kt`：L206 user_text→`"text"`+role `"user"`；L223 agent_text→`"text"`+role `"agent"`。
- `PoLangApplication.kt` L644：`"agent_image"` → `"image"` + role `"agent"`。
- `ChatHistoryModelInput.kt`：`toHistoryPair` 标签 `"user_text"`/`"agent_text"` → `"user"`/`"agent"`（`tool_call`/`tool_result` 不变；KDoc 契约段同步）。

- [ ] **Step 4: 备份导出/恢复**

`TagDataBackup.kt` L210-218 `BackupChatMessage` 增加 `val role: String? = null`（nullable：旧备份无此列）。

`TagDataBackupRepository.kt`：
- 导出（L297-307 映射）加 `role = it.role`。
- 恢复（L732-749 构造实体处）：

```kotlin
val row = if (msg.type in LegacyChatTypeMigration.NEW_TYPES) {
    // 新备份：直通（role 缺省补 agent 安全网）
    LegacyChatTypeMigration.MigratedRow(msg.type, msg.role ?: "agent", emptyList())
} else {
    // 旧备份（≤v6）：legacy 值经唯一映射知识库转正
    LegacyChatTypeMigration.map(msg.type, msg.content, msg.metadata)
}
ChatMessageEntity(
    id = msg.id,
    sessionId = msg.sessionId,
    type = row.type,
    role = row.role,
    content = msg.content,
    timestamp = msg.timestamp,
    modelUsed = msg.modelUsed,
    metadata = msg.metadata,
)
// 随后仍走 insertMessagesWithParts：withPartsJson 用纯净化转换器在新 (type, role) 上现算 partsJson，
// 与迁移产物零逻辑重复（spec §6 消费者表「备份恢复」行）。
```

- [ ] **Step 5: 编译 + androidApp 全量单测**

Run: `JITPACK=true ./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest`
Expected: 输出含 `BUILD SUCCESSFUL`（残留编译错误按信息逐点修，多为漏补 role 的实体构造）。

---

### Task 7: 全量验证

- [ ] **Step 1: 全量构建与测试**

Run: `JITPACK=true ./gradlew :shared:jvmTest :shared:assemble :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest`
Expected: 输出含字面量 `BUILD SUCCESSFUL`，零测试失败。

- [ ] **Step 2: 残留旧字面量扫描**

Run: 在 worktree 根执行 `grep -rn '"user_text"\|"agent_text"\|"user_image"\|"agent_image"\|"agent_edit_result"\|"media_results"\|"html_card"\|"task_card"\|"optimize_candidates"\|"plan_preview"' --include='*.kt' shared/src androidApp/src | grep -v LegacyChatTypeMigration | grep -v test`
Expected: 仅剩 `LegacyChatTypeMigration.kt` 内的 legacy 字面量（含注释说明）与显式钉 legacy 行为的测试 fixture；生产代码零残留。`"chart"` 单独人工复核（`modelUsed="chart"` 是模型标识，允许保留）。

---

### Task 8: 文档同步 + 单原子提交

**Files:**
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`（§0/§0.1/§0.2/§2 的 type 值与分类法表）
- Modify: `docs/02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md`（分类法节：8 值 + role 列 + type 降级）
- Modify: `shared/AGENTS.md` §2（转换器改名与新签名）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/common/chat/AGENTS.md` L105（doc 字面量）
- Modify: `docs/08-UI-SPECS/chat.yaml` §3.2 ios_todo（M5 按新分类法实现的注记）

- [ ] **Step 1: 逐文档同步**

按 spec §9 文档同步清单逐处更新：旧 13 值表述 → 新 8 值分类法表（含 `role` 列说明）；`LegacyMessagePartsConverter` 引用 → `MessagePartsConverter` + `LegacyChatTypeMigration` 分工；Room schema 版本 v25→v26。

- [ ] **Step 2: 文档自检**

Run: `grep -rn 'LegacyMessagePartsConverter' docs/ shared/AGENTS.md androidApp/src/main/java/com/mamba/picme/features/common/chat/AGENTS.md`
Expected: 仅出现在历史叙述（明确标注「已改名」）的语境，指引性内容零残留。

- [ ] **Step 3: 单原子提交（worktree 内）**

```bash
cd .worktrees/chat-type-taxonomy
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ \
        shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/ \
        androidApp/src/main/java/com/mamba/picme/data/local/ \
        androidApp/src/main/java/com/mamba/picme/features/chat/ \
        androidApp/src/main/java/com/mamba/picme/core/agent/ \
        androidApp/src/main/java/com/mamba/picme/core/agenttools/ \
        androidApp/src/main/java/com/mamba/picme/domain/backup/ \
        androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt \
        androidApp/src/main/java/com/mamba/picme/ChatTitleGenerator.kt \
        androidApp/src/test/java/com/mamba/picme/data/local/ \
        androidApp/src/androidTest/java/com/mamba/picme/data/local/ \
        docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md \
        docs/02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md \
        shared/AGENTS.md \
        androidApp/src/main/java/com/mamba/picme/features/common/chat/AGENTS.md \
        docs/08-UI-SPECS/chat.yaml
git commit -m "$(cat <<'EOF'
refactor(chat): type 字段分类法重构——3 分类 8 值 + role 升格独立列

- 新分类法：text/image（content）+ tool_chart/tool_html/tool_task/tool_image_edit（tool）+ data_media_results/data_optimize_candidates（data）
- MessagePart 新增 PartCategory 分类载体（getter-only，不进线格式）；6 个 serialName 改值
- 运行时转换器纯净化（MessagePartsConverter 只认新 8 值 + role 入参）；legacy 13→8 映射收编 LegacyChatTypeMigration（仅迁移与备份恢复用）
- Room v26：chat_messages +role 列；MIGRATION_25_26 单事务加列 + 全量改写（行级兜底不丢消息）
- 写路径/查询/回灌/备份恢复全量接线；DAO 用户消息判定由 type 前缀解析改为 role 列
EOF
)"
```

- [ ] **Step 4: 收尾**

提交后报告分支名与 commit hash，进入 finishing-a-development-branch 流程（merge / PR / 保留由用户定）。主仓 3 处无关本地改动全程未触碰（本任务所有 git 操作只发生在 worktree）。
