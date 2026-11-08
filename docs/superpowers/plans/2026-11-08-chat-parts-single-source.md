# Chat Parts 单一权威（持久化接缝反转）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 终结 chat_messages 表「标量列 + partsJson」双写：partsJson 升格为唯一内容权威（NOT NULL），`type` 降级为写时投影列，`content` 列退役，`metadata` 白名单瘦身，全部写/读路径翻转，备份格式 v5→v6。

**Architecture:** 对齐 Vercel「UIMessage JSON 整包落库 + 用时投影」与 ChatGPT「单文档消息」主流形态（ADR-016 D1）。写侧：`chatMessageEntity()` / `withUpdatedParts()` 两个纯函数收口全部构造与更新，type 投影与 partsJson 同点派生，漂移在物理上不可能；读侧：`decodeParts()` 直解 partsJson（part 级容错 Unsupported），legacy 回退链删除；查询面（sessionId/type/role/timestamp 四列）零变化。legacy 转换知识（MessagePartsConverter/LegacyChatTypeMigration）退居「MIGRATION_27_28 修复回填 + 旧备份恢复」专用。

**Tech Stack:** Room v28（MIGRATION_27_28 重建表删 content）、kotlinx.serialization（shared commonMain）、Moshi（备份模型）、JUnit/Robolectric（androidApp test）、MigrationTestHelper（androidTest）。

**前置状态：** worktree `.worktrees/chat-parts-single-source`，分支 `feat/chat-parts-single-source`；基线修复已提交（`a808d36b7`：ChatMediaWriteCapabilityTest 补 share_media 测试桩，main f1b80163e 遗留的测试编译断裂，与本特性无关但阻塞整个单测编译面）。

**关键设计决策（执行中不得偏离）：**

1. **Unsupported 原文保留**：凡 partsJson 解码成功（非 null）的行，迁移/恢复时**保留 partsJson 原文**，只重算 type 投影——重编码会把 Unsupported 占位行的未知 payload 固化丢失（codec KDoc 契约：原始 payload 保留，新版本自动恢复）。仅解码失败（null/损坏）行才经 converter 合成新 partsJson。
2. **metadata 白名单**（消息级住户，保留）：`claude_agent_state`、`prompt_len`/`decode_len`/`prefill_time_ms`/`decode_time_ms`/`prefill_speed`/`decode_speed`/`used_sandbox`、`savedAt`。其余键（html_card/engineer_task/imageUri/saved/suggestions/explanation/query/totalCount/isRefinement/抽卡组整组根字段）已迁入 parts，一律剔除。
3. **行为保持**：agent image 消息的 explanation 文本在 M4 拍平渲染下本就不可见（converter 只产单 Image part），翻转后维持 `[Image]` 单 part，不新增 Text part（不改渲染行为）。
4. **投影漂移已知边角**：含 Unsupported 占位的行 type 投影落 "text"，未来版本恢复原类型前 type 列暂不准——可接受（任务中心等 type 查询不针对未知类型），行被重写时自愈。

---

### Task 1: shared `MessagePartsProjection`（投影规则 SSOT）+ commonTest

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsProjection.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/MessagePartsProjectionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessagePartsProjectionTest {

    private fun textPart(id: String = "p0", md: String = "hello") =
        MessagePart.Text(id, md, PartState.DONE)

    @Test
    fun `roomTypeOf maps every part subtype to its wire type`() {
        val task = EngineerTaskState(taskId = "t1", sourceText = "s", status = EngineerTaskStatus.RUNNING)
        val group = OptimizeCandidateGroup("u", "scene", 0, emptyList(), emptyList(), 0)
        val cases: List<Pair<MessagePart, String>> = listOf(
            textPart() to "text",
            MessagePart.Image("p0", "file://a") to "image",
            MessagePart.Chart("p0", "<svg/>") to "tool_chart",
            MessagePart.HtmlCard("p0", "<p/>") to "tool_html",
            MessagePart.TaskCard("p0", "t1", ToolPartState.INPUT_AVAILABLE, task) to "tool_task",
            MessagePart.EditResult("p0", "file://a", "d") to "tool_image_edit",
            MessagePart.BrowserLive("p0", "s1") to "tool_browser",
            MessagePart.MediaResults("p0", MediaResultsUi("q", emptyList(), 0, false)) to "data_media_results",
            MessagePart.OptimizeCandidates("p0", group) to "data_optimize_candidates",
            MessagePart.OpenUiCard("p0", "prog", "sum") to "data_ui_openui",
            MessagePart.Unsupported("p0", "tool_future") to "text",
        )
        cases.forEach { (part, expected) ->
            assertEquals(expected, MessagePartsProjection.roomTypeOf(listOf(part)), "type of ${part::class.simpleName}")
        }
    }

    @Test
    fun `roomTypeOf empty parts falls back to text`() {
        assertEquals("text", MessagePartsProjection.roomTypeOf(emptyList()))
    }

    @Test
    fun `contentPreviewOf joins text parts and skips non-text`() {
        val parts = listOf(
            textPart("p0", "第一段"),
            MessagePart.Chart("p1", "<svg/>"),
            textPart("p2", "第二段"),
        )
        assertEquals("第一段\n\n第二段", MessagePartsProjection.contentPreviewOf(parts))
        assertEquals("", MessagePartsProjection.contentPreviewOf(listOf(MessagePart.Chart("p0", "<svg/>"))))
    }

    @Test
    fun `slimMetadataJson keeps message-level keys and drops part-level keys`() {
        val raw = """
            {"claude_agent_state":{"text":"x"},"prompt_len":10,"decode_len":20,"used_sandbox":false,
             "html_card":{"display":"inline"},"engineer_task":{"taskId":"t"},"imageUri":"file://a",
             "saved":true,"suggestions":["a"],"explanation":"e","query":"q","totalCount":3,
             "isRefinement":false,"sourceImageUri":"u","candidates":[]}
        """.trimIndent()
        val slim = MessagePartsProjection.slimMetadataJson(raw)!!
        listOf("claude_agent_state", "prompt_len", "decode_len", "used_sandbox").forEach {
            assert(it in slim) { "expected key $it in $slim" }
        }
        listOf("html_card", "engineer_task", "imageUri", "saved", "suggestions", "explanation",
            "query", "totalCount", "isRefinement", "sourceImageUri", "candidates").forEach {
            assert(it !in slim) { "unexpected key $it in $slim" }
        }
    }

    @Test
    fun `slimMetadataJson returns null for blank corrupt or fully-stripped input`() {
        assertNull(MessagePartsProjection.slimMetadataJson(null))
        assertNull(MessagePartsProjection.slimMetadataJson(""))
        assertNull(MessagePartsProjection.slimMetadataJson("not json"))
        assertNull(MessagePartsProjection.slimMetadataJson("""{"html_card":{}}"""))
        assertEquals("""{"savedAt":123}""", MessagePartsProjection.slimMetadataJson("""{"savedAt":123,"saved":true}"""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*MessagePartsProjectionTest*"`
Expected: FAIL — `MessagePartsProjection` unresolved

- [ ] **Step 3: Write the implementation**

```kotlin
package com.mamba.picme.domain.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * parts → Room 标量列投影（持久化接缝反转后的写侧纯函数，ADR-016 收口）。
 *
 * 反转后 chat_messages.partsJson 是唯一内容权威；type 列降级为写时投影
 * （DAO 查询/索引面：任务中心 type='tool_task'、回灌窗口 role 子查询），
 * content 列退役。本对象是投影规则的唯一事实源：
 * - [roomTypeOf]：primary part → Room type 值（10 值分类法，[MessagePartsConverter] 的逆映射）；
 * - [contentPreviewOf]：UI content 字段/会话标题的文本投影（Text parts 拼接）；
 * - [slimMetadataJson]：metadata 白名单过滤——消息级住户保留，part 级键剔除（防双写回潮）。
 */
object MessagePartsProjection {

    /**
     * primary part（首个 part）→ Room type 列值。
     * [MessagePart.Unsupported] 为读侧合成物（生产写路径不出现），投影落 "text"
     * （已知边角：恢复原类型前 type 列暂不准，行重写时自愈；type 查询不针对未知类型）。
     */
    fun roomTypeOf(parts: List<MessagePart>): String = when (parts.firstOrNull()) {
        is MessagePart.Text -> "text"
        is MessagePart.Image -> "image"
        is MessagePart.Chart -> "tool_chart"
        is MessagePart.HtmlCard -> "tool_html"
        is MessagePart.TaskCard -> "tool_task"
        is MessagePart.EditResult -> "tool_image_edit"
        is MessagePart.BrowserLive -> "tool_browser"
        is MessagePart.MediaResults -> "data_media_results"
        is MessagePart.OptimizeCandidates -> "data_optimize_candidates"
        is MessagePart.OpenUiCard -> "data_ui_openui"
        is MessagePart.Unsupported, null -> "text"
    }

    /** UI content 字段投影：Text parts 的 markdown 按 "\n\n" 拼接；无 Text part 为空串。 */
    fun contentPreviewOf(parts: List<MessagePart>): String =
        parts.filterIsInstance<MessagePart.Text>().joinToString("\n\n") { it.markdown }

    /** metadata 白名单住户（消息级，不属于任何 part；part 级键已迁入 parts）。 */
    private val SLIM_METADATA_KEYS = setOf(
        "claude_agent_state",
        "prompt_len", "decode_len", "prefill_time_ms", "decode_time_ms",
        "prefill_speed", "decode_speed", "used_sandbox",
        "savedAt",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * metadata 白名单过滤：part 级键（html_card/engineer_task/imageUri/saved/suggestions/
     * explanation/query/totalCount/isRefinement 及抽卡组整组根字段）一律剔除；
     * 白名单全空/整体损坏 → null（该消息无消息级元数据）。
     */
    fun slimMetadataJson(metadata: String?): String? {
        val obj = metadata
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }
            ?: return null
        val kept = obj.filterKeys { key -> key in SLIM_METADATA_KEYS }
        if (kept.isEmpty()) return null
        return JsonObject(kept).toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*MessagePartsProjectionTest*"`
Expected: PASS（5 用例）

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsProjection.kt shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/MessagePartsProjectionTest.kt
git commit -m "feat(chat): MessagePartsProjection——parts→Room 投影规则 SSOT（type/content/metadata 白名单）"
```

---

### Task 2: Entity v28 + 接缝重写（chatMessageEntity / withUpdatedParts / decodeParts）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageEntity.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageParts.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/data/local/ChatMessagePartsTest.kt`（重写）

注意：本任务完成后全工程必然编译失败（content 列删除波及所有构造点），属预期——Task 4~9 逐一收口，**中途不跑全量编译，只跑本任务单测文件编译不了就合并到 Task 4 末尾统一验证**。执行顺序上本任务与 Task 3~9 是「同一编译单元」，全部完成后统一编译。

- [ ] **Step 1: 重写 `ChatMessageEntity.kt`（删 content，partsJson 非空，KDoc 更新）**

```kotlin
package com.mamba.picme.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 数据库实体：聊天消息
 *
 * 对应表：chat_messages（v28 起 parts 单一权威：ADR-016 持久化接缝反转，
 * 对齐 Vercel「UIMessage JSON 整包落库」——content 列已退役）。
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,

    /**
     * 会话 ID，当前仅支持单会话（default），后续可扩展多会话
     */
    val sessionId: String = "default",

    /**
     * 投影列（写时自 parts 经 [com.mamba.picme.domain.chat.MessagePartsProjection.roomTypeOf]
     * 派生，构造/更新接缝同点完成）：服务 DAO 查询/索引面（任务中心 type='tool_task'、
     * 回灌窗口 role 子查询）。读面权威是 partsJson，本列永不作为内容源。
     */
    val type: String,

    /**
     * 消息角色："user" / "agent"
     */
    val role: String = "agent",

    /**
     * 消息时间戳
     */
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * 生成该消息的模型标识：remote_deepseek / cloud / chart 等
     */
    val modelUsed: String? = null,

    /**
     * 消息级元数据 JSON（白名单住户：claude_agent_state / LLM 性能指标 / savedAt）。
     * part 级键（html_card/engineer_task/imageUri/suggestions 等）已迁入 parts，禁止回写。
     */
    val metadata: String? = null,

    /**
     * 消息内容**唯一权威**：有序 parts 数组 JSON（ADR-016 D1 parts 模型；
     * [com.mamba.picme.domain.chat.MessagePartsCodec] 线格式）。v28 起 NOT NULL——
     * MIGRATION_27_28 对存量行一次性转正（损坏行降级 Text 原文兜底，不丢消息）。
     */
    val partsJson: String,
)
```

- [ ] **Step 2: 重写 `ChatMessageParts.kt` 接缝（保留迁移函数，替换运行时接缝）**

文件头部至 `toModelInputItems` 段替换为（迁移函数 `backfillChatMessageParts`/`migrateChatMessageTypes` 原样保留在文件尾部，新增 `migrateChatMessagesToPartsSsot` 见 Task 3）：

```kotlin
package com.mamba.picme.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.chat.MessagePartsConverter
import com.mamba.picme.domain.chat.MessagePartsProjection
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.roleOf
import com.mamba.picme.domain.chat.toModelInput

/**
 * chat_messages 表的 parts 单一权威接缝（持久化接缝反转，ADR-016 收口，v28）。
 *
 * 反转后不变量：partsJson 是唯一内容权威；type 为写时投影列（[MessagePartsProjection]）。
 * 全部写入经 [chatMessageEntity] 构造或 [withUpdatedParts] 更新——投影与 partsJson 同点
 * 派生，物理上不存在漂移面；DAO 裸 insertMessage/insertMessages 仅供本文件两个入口调用。
 *
 * 读侧：[decodeParts] 直解 partsJson（part 级容错：未知/损坏原位 Unsupported，见
 * [MessagePartsCodec]）；legacy 列回退已随 content 列退役删除。
 * legacy 转换知识（[MessagePartsConverter]/[com.mamba.picme.domain.chat.LegacyChatTypeMigration]）
 * 仅存用于 MIGRATION_27_28 修复回填与旧备份（v5-）恢复。
 */

/** 由 parts 构造实体（唯一构造入口：type 投影与 partsJson 同点派生，不可漂移）。 */
fun chatMessageEntity(
    id: String,
    sessionId: String,
    role: String,
    parts: List<MessagePart>,
    timestamp: Long = System.currentTimeMillis(),
    modelUsed: String? = null,
    metadata: String? = null,
): ChatMessageEntity = ChatMessageEntity(
    id = id,
    sessionId = sessionId,
    type = MessagePartsProjection.roomTypeOf(parts),
    role = role,
    timestamp = timestamp,
    modelUsed = modelUsed,
    metadata = metadata,
    partsJson = MessagePartsCodec.encode(parts),
)

/** 读侧：解 partsJson；空/整体损坏（迁移兜底后不应出现）合成 Unsupported 占位，不丢消息位。 */
fun ChatMessageEntity.decodeParts(): List<MessagePart> =
    MessagePartsCodec.decode(partsJson)
        ?: listOf(MessagePart.Unsupported(partId = "p0", originalType = type, summary = null))

/** 更新 parts（转换后投影重算；任务卡/HTML 卡测高/抽卡/OpenUI 交互态等回写统一入口）。 */
fun ChatMessageEntity.withUpdatedParts(transform: (List<MessagePart>) -> List<MessagePart>): ChatMessageEntity {
    val updated = transform(decodeParts())
    return copy(partsJson = MessagePartsCodec.encode(updated), type = MessagePartsProjection.roomTypeOf(updated))
}

/** 任务卡状态读取（任务中心列表/审批回放/交付入口的统一读取口）。 */
fun ChatMessageEntity.taskCardStateOrNull(): EngineerTaskState? =
    decodeParts().filterIsInstance<MessagePart.TaskCard>().firstOrNull()?.task

/**
 * 实体 → LLM 回灌项序列（spec §6：UIMessage→ModelMessage 显式转换）。
 * data part 不进上下文（转换规则见 commonMain `toModelInput`）。
 */
fun ChatMessageEntity.toModelInputItems(): List<ModelInputItem> =
    decodeParts().toModelInput(roleOf(role), id)

/** 插入单条消息（统一入口；实体须经 [chatMessageEntity]/[withUpdatedParts] 产出）。 */
suspend fun ChatMessageDao.insertPartsMessage(message: ChatMessageEntity) =
    insertMessage(message)

/** 批量插入（备份恢复路径）。 */
suspend fun ChatMessageDao.insertPartsMessages(messages: List<ChatMessageEntity>) =
    insertMessages(messages)
```

- [ ] **Step 3: 重写 `ChatMessagePartsTest.kt`**

```kotlin
package com.mamba.picme.data.local

import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.ToolPartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * parts 单一权威接缝单测（v28）：构造投影、parts 更新重投影、读取容错。
 */
class ChatMessagePartsTest {

    private fun textPart(id: String = "p0", md: String = "hi") =
        MessagePart.Text(id, md, PartState.DONE)

    @Test
    fun `chatMessageEntity derives type projection and encodes partsJson at construction`() {
        val entity = chatMessageEntity(
            id = "m1", sessionId = "s1", role = "user",
            parts = listOf(MessagePart.Image("p0", "file://a"), textPart("p1", "看图")),
        )
        assertEquals("image", entity.type)
        assertEquals("user", entity.role)
        assertEquals(
            listOf(MessagePart.Image("p0", "file://a"), textPart("p1", "看图")),
            entity.decodeParts(),
        )
    }

    @Test
    fun `withUpdatedParts re-derives type projection when primary part changes`() {
        val entity = chatMessageEntity(
            id = "m1", sessionId = "s1", role = "agent",
            parts = listOf(MessagePart.OptimizeCandidates("p0", gachaGroup())),
        )
        assertEquals("data_optimize_candidates", entity.type)
        val confirmed = entity.withUpdatedParts {
            listOf(MessagePart.Image("p0", ref = "content://x", saved = false))
        }
        assertEquals("image", confirmed.type)
        assertEquals(listOf(MessagePart.Image("p0", ref = "content://x", saved = false)), confirmed.decodeParts())
    }

    @Test
    fun `withUpdatedParts transforms in place and preserves message fields`() {
        val entity = chatMessageEntity(
            id = "m1", sessionId = "s1", role = "agent", modelUsed = "html_card",
            metadata = """{"claude_agent_state":{}}""",
            parts = listOf(MessagePart.HtmlCard("p0", "<p/>")),
        )
        val updated = entity.withUpdatedParts { parts ->
            parts.map { part ->
                if (part is MessagePart.HtmlCard) {
                    part.copy(meta = part.meta.copy(displayMode = HtmlCardDisplayMode.FULLPAGE, measuredHeightPx = 900))
                } else {
                    part
                }
            }
        }
        assertEquals("tool_html", updated.type)
        assertEquals("html_card", updated.modelUsed)
        assertEquals("""{"claude_agent_state":{}}""", updated.metadata)
        val card = updated.decodeParts().filterIsInstance<MessagePart.HtmlCard>().single()
        assertEquals(HtmlCardDisplayMode.FULLPAGE, card.meta.displayMode)
        assertEquals(900, card.meta.measuredHeightPx)
    }

    @Test
    fun `decodeParts falls back to Unsupported on corrupt partsJson`() {
        val entity = chatMessageEntity(
            id = "m1", sessionId = "s1", role = "agent", parts = listOf(textPart()),
        ).copy(partsJson = "{{{")
        val parts = entity.decodeParts()
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MessagePart.Unsupported)
    }

    @Test
    fun `taskCardStateOrNull reads task from TaskCard part`() {
        val state = EngineerTaskState(taskId = "t1", sourceText = "src", status = EngineerTaskStatus.RUNNING)
        val entity = chatMessageEntity(
            id = "t1", sessionId = "s1", role = "agent",
            parts = listOf(MessagePart.TaskCard("p0", "t1", state.status.toToolPartState(), state)),
        )
        assertEquals(state, entity.taskCardStateOrNull())
        assertEquals("tool_task", entity.type)
        assertNull(chatMessageEntity(id = "m", sessionId = "s1", role = "agent", parts = listOf(textPart())).taskCardStateOrNull())
    }

    private fun gachaGroup() = OptimizeCandidateGroup("u", "scene", 0, emptyList(), emptyList(), 0)
}
```

注：`HtmlCardDisplayMode`/`OptimizeCandidateGroup`/`toToolPartState` 均为 commonMain 类型，import 自 `com.mamba.picme.domain.chat.*`（`toToolPartState` 为顶层函数，直接 import `com.mamba.picme.domain.chat.toToolPartState`）。

- [ ] **Step 4: Commit（编译红属预期，Task 9 收口后转绿）**

```bash
git add androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageEntity.kt androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageParts.kt androidApp/src/test/java/com/mamba/picme/data/local/ChatMessagePartsTest.kt
git commit -m "feat(chat): Entity v28 + parts 单一权威接缝——chatMessageEntity/withUpdatedParts/decodeParts，content 列退役"
```

---

### Task 3: MIGRATION_27_28（重建表 + 逐行转正）+ androidTest

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`（version 27→28、注册迁移、新增 MIGRATION_27_28）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageParts.kt`（尾部追加 `migrateChatMessagesToPartsSsot`）
- Test: `androidApp/src/androidTest/java/com/mamba/picme/data/local/ChatMessagePartsMigrationTest.kt`（追加 27→28 用例）

- [ ] **Step 1: 追加迁移函数到 `ChatMessageParts.kt` 尾部（TAG 常量保留复用）**

```kotlin
/**
 * MIGRATION_27_28：逐行把 (legacy 列, partsJson) 双表示转正为 parts 单一权威行，
 * 写入重建后的 chat_messages_new。
 *
 * - 解码成功行：**partsJson 原文保留**（Unsupported 占位行的未知 payload 不随重编码丢失，
 *   codec 契约），只重算 type 投影 + 瘦身 metadata；
 * - 解码失败行（partsJson null/损坏）：经 [MessagePartsConverter] 自 legacy 列现算合成
 *   （行级兜底链与 24→25/25→26 同款：任何异常 → Text 原文，迁移绝不丢消息）；
 * - type 全量重算为投影（顺带消除双写期漂移行，如抽卡确认留下的 type=image 但
 *   content 滞留旧抽卡文案行——content 随列退役消失，漂移一并清算）。
 *
 * 游标遍历中 INSERT 异表安全：扫描只读旧表，写入新表，无自写并发问题。
 */
internal fun migrateChatMessagesToPartsSsot(database: SupportSQLiteDatabase) {
    val startedAt = System.currentTimeMillis()
    var rows = 0
    Logger.i(TAG, "MIGRATION_27_28 rewrite chat_messages to parts SSOT started")
    val insert = database.compileStatement(
        "INSERT INTO `chat_messages_new` (`id`, `sessionId`, `type`, `role`, `timestamp`, `modelUsed`, `metadata`, `partsJson`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    )
    insert.use {
        val cursor = database.query(
            "SELECT `id`, `sessionId`, `type`, `role`, `timestamp`, `modelUsed`, `metadata`, `content`, `partsJson` FROM `chat_messages`",
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val sessionId = it.getString(1)
                val legacyType = it.getString(2)
                val role = it.getString(3)
                val timestamp = it.getLong(4)
                val modelUsed = if (it.isNull(5)) null else it.getString(5)
                val metadata = if (it.isNull(6)) null else it.getString(6)
                val content = it.getString(7)
                val partsJson = if (it.isNull(8)) null else it.getString(8)
                val decoded = MessagePartsCodec.decode(partsJson)
                val newType: String
                val newPartsJson: String
                if (decoded != null) {
                    newType = MessagePartsProjection.roomTypeOf(decoded)
                    newPartsJson = partsJson!! // 原文保留（decoded != null 蕴含 partsJson 非空）
                } else {
                    val parts = runCatching {
                        MessagePartsConverter.toParts(legacyType, content, metadata, roleOf(role))
                    }.getOrElse {
                        listOf(MessagePart.Text("p0", content, PartState.DONE))
                    }
                    newType = MessagePartsProjection.roomTypeOf(parts)
                    newPartsJson = MessagePartsCodec.encode(parts)
                }
                insert.clearBindings()
                insert.bindString(1, id)
                insert.bindString(2, sessionId)
                insert.bindString(3, newType)
                insert.bindString(4, role)
                insert.bindLong(5, timestamp)
                if (modelUsed == null) insert.bindNull(6) else insert.bindString(6, modelUsed)
                val slim = MessagePartsProjection.slimMetadataJson(metadata)
                if (slim == null) insert.bindNull(7) else insert.bindString(7, slim)
                insert.bindString(8, newPartsJson)
                insert.executeInsert()
                rows++
            }
        }
    }
    Logger.i(TAG, "MIGRATION_27_28 rewrite finished: $rows rows in ${System.currentTimeMillis() - startedAt}ms")
}
```

- [ ] **Step 2: `AppDatabase.kt` 三处修改**

① `version = 27` → `version = 28`；
② builder 迁移列表追加 `MIGRATION_27_28`：
```kotlin
                        MIGRATION_23_24, MIGRATION_24_25,
                        MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28
```
③ `MIGRATION_26_27` 之后新增（SQLite 删列走 create-copy-drop 重建，minSdk 24 不依赖 ALTER DROP COLUMN）：

```kotlin
        /**
         * Migration 27 → 28：chat_messages 持久化接缝反转（ADR-016 收口）——
         * partsJson 升格唯一内容权威（NOT NULL），content 列退役，type 降级投影列，
         * metadata 白名单瘦身（part 级键已迁入 parts）。
         * 重建表删 content：逐行转正收口 [migrateChatMessagesToPartsSsot]（原文保留 +
         * 损坏行 Text 兜底，不丢消息）；单 migrate() = Room 单事务。
         */
        internal val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages_new` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `sessionId` TEXT NOT NULL DEFAULT 'default',
                        `type` TEXT NOT NULL,
                        `role` TEXT NOT NULL DEFAULT 'agent',
                        `timestamp` INTEGER NOT NULL,
                        `modelUsed` TEXT,
                        `metadata` TEXT,
                        `partsJson` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                migrateChatMessagesToPartsSsot(database)
                database.execSQL("DROP TABLE `chat_messages`")
                database.execSQL("ALTER TABLE `chat_messages_new` RENAME TO `chat_messages`")
            }
        }
```

- [ ] **Step 3: androidTest 追加 27→28 用例（沿用本文件既有 MigrationTestHelper 模式）**

```kotlin
    @Test
    fun migrate27To28_dropsContent_slimsMetadata_keepsPartsVerbatim_repairsCorrupt() {
        // v27 库造 4 行：① 正常双写行（partsJson 有效，metadata 混合键）
        // ② 漂移行（type=image 但 content 为滞留抽卡文案，partsJson=[Image]，验投影重算）
        // ③ 损坏行（partsJson 非 JSON，legacy 列齐全，验 converter 兜底）
        // ④ Unsupported 占位行（partsJson 含未知 part 类型，验原文保留）
        // …（SQL 按既有用例的 INSERT 形态直写 v27 八列表）
        helper.runMigrationsAndValidate(TEST_DB, 28, true, AppDatabase.MIGRATION_27_28).apply {
            // content 列消失
            query("SELECT * FROM chat_messages").use { c ->
                assertEquals(-1, c.getColumnIndex("content"))
                assert(c.getColumnIndex("partsJson") >= 0)
            }
            // ①：metadata 仅余白名单键；partsJson 逐字节等于迁移前
            // ②：type 重算为 "image"
            // ③：partsJson 为 converter 产物（Text part 兜底或按 legacy type 映射）
            // ④：partsJson 原文不变（含未知 type 的 part payload 未被重编码）
        }
    }
```

（造数 SQL 与断言细节执行时按本文件既有 24→25/25→26 用例同款 harness 补全；四行覆盖四个验收点，缺一个点补一行。）

- [ ] **Step 4: androidTest 编译验证（设备可选；无设备先保证编译）**

Run: `./gradlew :androidApp:compileDebugAndroidTestKotlin`
Expected: 编译错误仅来自「尚未翻转的业务代码引用 content」（Task 4~9 收口后转绿）；本任务自身文件无错。

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/data/local/AppDatabase.kt androidApp/src/main/java/com/mamba/picme/data/local/ChatMessageParts.kt androidApp/src/androidTest/java/com/mamba/picme/data/local/ChatMessagePartsMigrationTest.kt
git commit -m "feat(chat): MIGRATION_27_28——chat_messages 重建表删 content，逐行转正 parts 单一权威"
```

---

### Task 4: ChatViewModel 构造点翻转（15 处 + generateAutoTitle）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- 附带检查: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`（grep 到 insertMessageWithParts 引用，同口径翻转）

统一 import 替换（全文件）：
- `import com.mamba.picme.data.local.insertMessageWithParts` → `import com.mamba.picme.data.local.chatMessageEntity` + `import com.mamba.picme.data.local.insertPartsMessage`
- `import com.mamba.picme.data.local.decodePartsOrLegacy` → `import com.mamba.picme.data.local.decodeParts`
- 新增 `import com.mamba.picme.data.local.taskCardStateOrNull`（Task 6 用）、`import com.mamba.picme.domain.chat.MessagePartsProjection`（6 用）

逐点替换（行号为翻转前基线 a808d36b7，执行时以代码内容锚定）：

- [ ] **Step 1: L545（sendClaudeMessage 用户文本）**

```kotlin
                chatMessageDao.insertPartsMessage(
                    chatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        role = "user",
                        parts = listOf(MessagePart.Text("p0", text, PartState.DONE)),
                    ),
                )
```

- [ ] **Step 2: L742（claude 气泡落库；metadata 的 claude_agent_state 是白名单消息级键，原样保留）**

```kotlin
        chatMessageDao.insertPartsMessage(
            chatMessageEntity(
                id = msgId,
                sessionId = sessionId,
                role = "agent",
                parts = listOf(MessagePart.Text("p0", state.text, PartState.DONE)),
                modelUsed = currentModelLabel(),
                metadata = metadata,
            ),
        )
```

- [ ] **Step 3: L760（persistEngineerTask；engineer_task 键迁入 TaskCard part，metadata 退役）**

```kotlin
            chatMessageDao.insertPartsMessage(
                chatMessageEntity(
                    id = state.taskId,
                    sessionId = sessionId,
                    role = "agent",
                    parts = listOf(
                        MessagePart.TaskCard(
                            partId = "p0",
                            toolCallId = state.taskId,
                            state = state.status.toToolPartState(),
                            task = state,
                        ),
                    ),
                    timestamp = state.startedAtMs,
                ),
            )
```

（`toToolPartState` import 自 `com.mamba.picme.domain.chat.toToolPartState`；函数头注释「REPLACE 同 id 重插」语义不变。）

- [ ] **Step 4: L1844（sendMessage 用户消息；imageUri 迁入 Image part，对齐 converter 的 [Image, Text] 序）**

```kotlin
                val userMessage = chatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "user",
                    parts = when {
                        imageUri != null && text.isNotBlank() -> listOf(
                            MessagePart.Image("p0", ref = imageUri),
                            MessagePart.Text("p1", text, PartState.DONE),
                        )
                        imageUri != null -> listOf(MessagePart.Image("p0", ref = imageUri))
                        else -> listOf(MessagePart.Text("p0", text, PartState.DONE))
                    },
                )
                chatMessageDao.insertPartsMessage(userMessage)
```

- [ ] **Step 5: L2170（错误消息）**

```kotlin
                val errorMessage = chatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "agent",
                    parts = listOf(MessagePart.Text("p0", errorContent, PartState.DONE)),
                    modelUsed = "error",
                )
                chatMessageDao.insertPartsMessage(errorMessage)
```

- [ ] **Step 6: L3091（emitChartMessage）**

```kotlin
        chatMessageDao.insertPartsMessage(
            chatMessageEntity(
                id = "chart_" + System.currentTimeMillis(),
                sessionId = _currentSessionId.value,
                role = "agent",
                parts = listOf(MessagePart.Chart("p0", svg = svg)),
                modelUsed = "chart",
            ),
        )
```

- [ ] **Step 7: L3124（emitHtmlCardMessage；html_card 键迁入 HtmlCard.meta，metadata 退役）**

```kotlin
        chatMessageDao.insertPartsMessage(
            chatMessageEntity(
                id = "html_" + System.currentTimeMillis() + "_" + htmlMessageSeq.incrementAndGet(),
                sessionId = _currentSessionId.value,
                role = "agent",
                parts = listOf(MessagePart.HtmlCard("p0", html = html, meta = meta)),
                modelUsed = "html_card",
            ),
        )
```

（函数头注释中「metadata `html_card` key 记…」更新为「双形态元数据随 HtmlCard.meta 随迁」。）

- [ ] **Step 8: L3381（直搜用户消息）**

```kotlin
                chatMessageDao.insertPartsMessage(
                    chatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        role = "user",
                        parts = listOf(MessagePart.Text("p0", text, PartState.DONE)),
                    ),
                )
```

- [ ] **Step 9: L3486（insertMediaResultsMessage；query/totalCount/isRefinement 迁入 MediaResults part）**

```kotlin
            chatMessageDao.insertPartsMessage(
                chatMessageEntity(
                    id = previousCard?.id ?: UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "agent",
                    parts = listOf(MessagePart.MediaResults("p0", results = ui)),
                    timestamp = previousCard?.timestamp ?: System.currentTimeMillis(),
                    modelUsed = "gallery_search",
                ),
            )
```

- [ ] **Step 10: L3516（insertOpenUiCardMessage；content 整颗 JSON 形态退役，part 直写；metadata 仅留 performance 白名单）**

```kotlin
        chatMessageDao.insertPartsMessage(
            chatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "agent",
                parts = listOf(part),
                modelUsed = "cloud_agent",
                metadata = performanceMetadataJson(performance),
            ),
        )
```

（函数头注释「走 tool_browser 先例：content 列存整颗 part JSON」整段删除；`cardPartJson` 若因此全文件无引用，连同 Step 12 后一并删除该字段。）

- [ ] **Step 11: L3611（insertAgentMessage）**

```kotlin
        chatMessageDao.insertPartsMessage(
            chatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "agent",
                parts = listOf(MessagePart.Text("p0", content, PartState.DONE)),
                modelUsed = modelUsed,
                metadata = performanceMetadataJson(performance),
            ),
        )
```

- [ ] **Step 12: L3663（insertAgentImageMessage；imageUri/saved 迁入 Image part；explanation 文本 M4 起本不渲染，行为保持不补 Text part）**

```kotlin
        chatMessageDao.insertPartsMessage(
            chatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "agent",
                parts = listOf(MessagePart.Image("p0", ref = imageUri, saved = false)),
                modelUsed = modelUsed,
                metadata = performanceMetadataJson(performance),
            ),
        )
```

（函数头注释补一句「说明文本自 M4 起不进渲染（converter 单 Image part），反转后不保留」。局部 metadata 拼装代码删除，`performanceMetadataJson` 复用。）

- [ ] **Step 13: L3709（insertEditResultMessage；ref/suggestions/saved 迁入 EditResult part）**

```kotlin
        chatMessageDao.insertPartsMessage(
            chatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "agent",
                parts = listOf(
                    MessagePart.EditResult(
                        partId = "p0",
                        ref = imageUri,
                        description = explanation,
                        suggestions = listOf(
                            stringContext().getString(R.string.chat_edit_suggestion_brighter),
                            stringContext().getString(R.string.chat_edit_suggestion_fine_tune),
                        ),
                        saved = false,
                    ),
                ),
                modelUsed = modelUsed,
                metadata = performanceMetadataJson(performance),
            ),
        )
```

- [ ] **Step 14: L3732（insertOptimizeCandidatesMessage；删 content 参数，group 迁入 part）**

签名与实现：
```kotlin
    /** 插入候选卡组消息（type 投影 = data_optimize_candidates），并按推荐卡初始化选中态。 */
    @VisibleForTesting
    internal suspend fun insertOptimizeCandidatesMessage(
        sessionId: String,
        messageId: String,
        group: OptimizeCandidateGroup,
        modelUsed: String,
    ) {
        chatMessageDao.insertPartsMessage(
            chatMessageEntity(
                id = messageId,
                sessionId = sessionId,
                role = "agent",
                parts = listOf(MessagePart.OptimizeCandidates("p0", group = group)),
                modelUsed = modelUsed,
            ),
        )
        chatSessionDao.touchSession(sessionId)
        _gachaSelections.value = _gachaSelections.value + (messageId to group.recommendedIndex)
    }
```

调用点（grep `insertOptimizeCandidatesMessage(`）同步删 content 实参；`content` 此前仅写标量列、converter 与渲染均不消费，删除无行为变化。

- [ ] **Step 15: L3901（用户图片消息）**

```kotlin
                val userMessage = chatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "user",
                    parts = listOf(MessagePart.Image("p0", ref = persistedUri)),
                )
                chatMessageDao.insertPartsMessage(userMessage)
```

- [ ] **Step 16: generateAutoTitle（L3567，content 改文本投影）**

```kotlin
    private fun generateAutoTitle(firstUserMessage: ChatMessageEntity): String {
        return ChatTitleGenerator.generateTitle(
            firstUserMessageType = firstUserMessage.type,
            textContent = MessagePartsProjection.contentPreviewOf(firstUserMessage.decodeParts()),
            imageTitle = stringContext().getString(R.string.chat_title_image_first),
            fallbackTitle = stringContext().getString(R.string.new_chat),
        )
    }
```

- [ ] **Step 17: ChatViewModelDependencies.kt 同口径翻转**（grep `insertMessageWithParts|withPartsJson` 命中的委托方法，逐一换成 `insertPartsMessage`/`chatMessageEntity`）

- [ ] **Step 8 顺带清理**：grep `ChatGallerySearch.serializeContent|serializeMetadata`——Task 5 完成后若无引用，删除这两个函数（converter 解析侧不需要它们）。

- [ ] **Step 18: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/
git commit -m "feat(chat): ChatViewModel 构造点全量翻转 parts 直写——15 处构造 + 标题生成文本投影"
```

---

### Task 5: ChatViewModel 更新点翻转（6 处，统一 withUpdatedParts）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 1: persistHtmlCardDisplayMode（L3147，html_card 键退役 → HtmlCard.meta 原位更新，幂等保持）**

```kotlin
    fun persistHtmlCardDisplayMode(messageId: String, mode: HtmlCardDisplayMode, measuredHeightPx: Int?) {
        viewModelScope.launch(Dispatchers.Default) {
            val entity = chatMessageDao.getMessageById(messageId) ?: return@launch
            if (entity.type != "tool_html") return@launch
            val card = entity.decodeParts().filterIsInstance<MessagePart.HtmlCard>().firstOrNull()
                ?: return@launch
            // 幂等——已持久化同值时跳过写库（重组重复回调不产 DB churn）
            if (card.meta.displayMode == mode) return@launch
            val newMeta = card.meta.copy(
                displayMode = mode,
                measuredHeightPx = measuredHeightPx?.takeIf { it > 0 } ?: card.meta.measuredHeightPx,
            )
            chatMessageDao.insertPartsMessage(
                entity.withUpdatedParts { parts ->
                    parts.map { part -> if (part is MessagePart.HtmlCard) part.copy(meta = newMeta) else part }
                },
            )
        }
    }
```

- [ ] **Step 2: persistOpenUiViewState（L3531，content 直读退役 → parts 内 OpenUiCard 覆写；无卡/no-op 自然短路）**

```kotlin
    /** OpenUI 卡交互态持久化（US-7 对偶，openui P2）：同 part id 覆写，重建时作初始 state。 */
    suspend fun persistOpenUiViewState(messageId: String, viewState: String) {
        val entity = chatMessageDao.getMessageById(messageId) ?: return
        val updated = entity.withUpdatedParts { parts ->
            parts.map { part -> if (part is MessagePart.OpenUiCard) part.copy(viewState = viewState) else part }
        }
        if (updated.partsJson == entity.partsJson) return
        chatMessageDao.insertPartsMessage(updated)
    }
```

- [ ] **Step 3: onOptimizeGachaReroll（L3765，content/metadata 双列退役 → OptimizeCandidates part 覆写）**

```kotlin
                        chatMessageDao.getMessageById(messageId)?.let { entity ->
                            chatMessageDao.insertPartsMessage(
                                entity.copy(metadata = null).withUpdatedParts { parts ->
                                    parts.map { part ->
                                        if (part is MessagePart.OptimizeCandidates) part.copy(group = outcome.group) else part
                                    }
                                },
                            )
                        }
```

（`outcome.explanation` 此前仅写 content 标量列、无消费方，随列退役。）

- [ ] **Step 4: onOptimizeGachaConfirm（L3794，改写为 agent image 结果消息——顺带清算旧实现 content 滞留文案的漂移）**

```kotlin
            chatMessageDao.getMessageById(messageId)?.let { entity ->
                chatMessageDao.insertPartsMessage(
                    entity.copy(metadata = null).withUpdatedParts {
                        listOf(MessagePart.Image("p0", ref = result.imageUri, saved = false))
                    },
                )
            }
```

（type 投影自动落 "image"；旧实现 content 滞留抽卡文案的漂移在迁移与此次重写双通道清算。）

- [ ] **Step 5: removeMediaResultAsset 空结果分支（L4114，改写为文本消息）**

```kotlin
                            chatMessageDao.getMessageById(message.id)?.let { entity ->
                                chatMessageDao.insertPartsMessage(
                                    entity.copy(metadata = null).withUpdatedParts {
                                        listOf(MessagePart.Text("p0", deletedText, PartState.DONE))
                                    },
                                )
                            }
```

- [ ] **Step 6: removeMediaResultAsset 非空分支（L4139，MediaResults part 原位更新）**

```kotlin
                        chatMessageDao.getMessageById(message.id)?.let { entity ->
                            chatMessageDao.insertPartsMessage(
                                entity.withUpdatedParts { parts ->
                                    parts.map { part ->
                                        if (part is MessagePart.MediaResults) {
                                            part.copy(
                                                results = part.results.copy(
                                                    assets = newAssets,
                                                    totalCount = newTotal,
                                                ),
                                            )
                                        } else {
                                            part
                                        }
                                    }
                                },
                            )
                        }
```

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(chat): ChatViewModel 更新点统一 withUpdatedParts——HTML 测高/OpenUI 态/抽卡/媒体结果六处"
```

---

### Task 6: 读点翻转（toUiModel / 任务卡 / 抽卡回放 / 任务中心）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/taskcenter/TaskCenterViewModel.kt`
- Modify（条件删除）: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatModelCommonMainShim.kt` 的 `parseEngineerTaskState`

- [ ] **Step 1: toUiModel（L4226，decodePartsOrLegacy → decodeParts；content 改文本投影；函数头注释更新）**

```kotlin
    @Suppress("CyclomaticComplexMethod") // 待重构：toUiModel 按消息类型映射抽表
    private fun ChatMessageEntity.toUiModel(): ChatMessageUi {
        // ADR-016 持久化接缝反转：parts 为唯一内容权威——primary part 定 [ChatMessageType]，
        // role 列区分 USER_TEXT/AGENT_TEXT 与用户图文；平铺字段（chartSvg/htmlContent/…/
        // engineerTask）一律自 parts 提取；content 为 Text parts 投影；performance/
        // claudeAgent 仍从消息级 metadata（白名单）解析。
        val parts = decodeParts()
        val primary = parts.firstOrNull()
        val role = roleOf(this.role)
        return ChatMessageUi(
            // …（type when 分支与 role 不变）…
            content = MessagePartsProjection.contentPreviewOf(parts),
            // …（其余字段不变：chartSvg/htmlContent/htmlCardMeta/imageUri/imageSaved/
            //     performance/mediaResults/claudeAgent/optimizeCandidates/engineerTask/parts）…
        )
    }
```

（仅替换 `decodePartsOrLegacy()` 为 `decodeParts()`、`content = content` 为 `content = MessagePartsProjection.contentPreviewOf(parts)`、函数头注释，其余分支逐字保留。）

- [ ] **Step 2: 任务卡读取两处（L439 回放、L955 交付入口）**

`parseEngineerTaskState(entity.metadata)` → `entity.taskCardStateOrNull()`（两处同改）。

- [ ] **Step 3: 抽卡选中态回放（L1611）**

```kotlin
                                entity.decodeParts()
                                    .filterIsInstance<MessagePart.OptimizeCandidates>()
                                    .firstOrNull()
                                    ?.let { part -> entity.id to part.group.recommendedIndex }
```

- [ ] **Step 4: TaskCenterViewModel（metadata/content 读退役）**

```kotlin
                val task = entity.taskCardStateOrNull() ?: return@mapNotNull null
                EngineerTaskItem(
                    // …（id/sessionId/其余字段不变）…
                    title = task.sourceText.take(50),
                    // …
                )
```

（`parseEngineerTaskState` import 移除；`entity.content.ifBlank { task.sourceText }` 等价归约为 `task.sourceText.take(50)`——content 列原值就是 sourceText.take(50)。）

- [ ] **Step 5: 死代码清扫（grep 判定）**

- `parseEngineerTaskState`（ChatModelCommonMainShim.kt）：全工程 grep 零引用 → 删除该函数；`OptimizeCandidateGroup.fromJson`（androidApp 扩展）同理判定。
- `MessagePartsConverter`/`LegacyChatTypeMigration` 保留（MIGRATION_27_28 与旧备份恢复专用），KDoc 头部加「restore-only」一句（MessagePartsConverter 头注已含「写侧纯函数」表述，改为「迁移/恢复专用」）。

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/
git commit -m "feat(chat): 读面翻转 parts 直读——toUiModel/任务卡/抽卡回放/任务中心 + 死代码清扫"
```

---

### Task 7: 其余写方 + SaveChatEditResultUseCase

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/agent/remote/RemoteCommandDispatcher.kt`（L203/L221）
- Modify: `androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt`（L686）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/browser/BrowserLiveSessionController.kt`（L236）
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/usecase/SaveChatEditResultUseCase.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/usecase/SaveChatEditResultUseCaseTest.kt`

- [ ] **Step 1: RemoteCommandDispatcher 两处（飞书/Telegram 远程控制消息）**

saveUserMessage/saveAgentMessage 的 `ChatMessageEntity(...)` →：
```kotlin
            chatMessageDao.insertPartsMessage(
                chatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId(),
                    role = "user", // saveAgentMessage 为 "agent"，modelUsed = "feishu_remote"
                    parts = listOf(MessagePart.Text("p0", content, PartState.DONE)),
                    modelUsed = null,
                ),
            )
```

- [ ] **Step 2: PoLangApplication L686（远程回传图片消息）**

```kotlin
                            chatMessageDao.insertPartsMessage(
                                chatMessageEntity(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    role = "agent",
                                    parts = listOf(MessagePart.Image("p0", ref = photo.uri)),
                                    modelUsed = sourceTag,
                                ),
                            )
```

- [ ] **Step 3: BrowserLiveSessionController.emitBrowserCardMessage（content 整颗 JSON 形态退役，part 直写）**

```kotlin
    /**
     * 浏览器会话定格卡**落库**（type 投影 = tool_browser，parts 唯一权威）。
     * 与图表/HTML 卡同理：消息列表由 DB Flow 驱动，卡片必须落库才能跨重载持久。
     * 双显跳过（流式占位卡 vs 产物行）由 ChatScreen 的 persistedBrowserSessionIds
     * 提取式自动生效（自非流式消息 parts 提取 sessionId 集合）。
     */
    private suspend fun emitBrowserCardMessage(finalPart: MessagePart.BrowserLive) {
        chatMessageDao.insertPartsMessage(
            chatMessageEntity(
                id = "browser_" + System.currentTimeMillis() + "_" + messageSeq.incrementAndGet(),
                sessionId = currentSessionId(),
                role = "agent",
                parts = listOf(finalPart.copy(partId = "p0")),
                modelUsed = "browser",
            ),
        )
    }
```

（`partJson`/`cardPartJson` 序列化器若全文件无引用 → 删除字段。）

- [ ] **Step 4: SaveChatEditResultUseCase（saved/imageUri 迁入 part；savedAt 留消息级 metadata）**

```kotlin
package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.data.local.insertPartsMessage
import com.mamba.picme.data.local.withUpdatedParts
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsProjection
import com.mamba.picme.domain.repository.ChatImageStore
import org.json.JSONObject
import java.io.File

/**
 * 把一条 chat 编辑/优化结果消息保存进相册。
 *
 * 顺序保证「文件不先于消息重指向被删」：
 * 1. copyToGallery 得 content:// URI（失败则直接返回，不动文件、不改消息）；
 * 2. 更新消息 part：ref = contentUri、saved = true；消息级 metadata 记 savedAt；
 * 3. markSaved 删私有文件 + 行置 SAVED。
 *
 * 幂等：part.saved=true 时直接返回既有 content:// URI。
 */
class SaveChatEditResultUseCase(
    private val store: ChatImageStore,
    private val chatMessageDao: ChatMessageDao
) {
    suspend fun execute(messageId: String): Result<String> {
        val msg = chatMessageDao.getMessageById(messageId)
            ?: return Result.failure(IllegalStateException("消息不存在"))
        val media = msg.decodeParts().firstNotNullOfOrNull { part ->
            when (part) {
                is MessagePart.Image -> Triple(part.ref, part.saved, true)
                is MessagePart.EditResult -> part.ref?.let { Triple(it, part.saved, false) }
                else -> null
            }
        } ?: return Result.failure(IllegalStateException("消息无图片内容"))
        val (ref, saved, _) = media

        // 幂等：已保存直接返回既有 content:// URI
        if (saved && ref.startsWith("content://")) return Result.success(ref)

        if (!ref.startsWith("file://") || !File(ref.removePrefix("file://")).exists()) {
            return Result.failure(IllegalStateException("图片已过期，无法保存"))
        }
        val filePath = ref.removePrefix("file://")
        val contentUri = store.copyToGallery(filePath)
            ?: return Result.failure(IllegalStateException("保存到相册失败"))

        val newMeta = (
            MessagePartsProjection.slimMetadataJson(msg.metadata)
                ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                ?: JSONObject()
            ).apply { put("savedAt", System.currentTimeMillis()) }
        chatMessageDao.insertPartsMessage(
            msg.copy(metadata = newMeta.toString()).withUpdatedParts { parts ->
                parts.map { part ->
                    when (part) {
                        is MessagePart.Image -> part.copy(ref = contentUri, saved = true)
                        is MessagePart.EditResult -> part.copy(ref = contentUri, saved = true)
                        else -> part
                    }
                }
            },
        )
        store.markSaved(filePath)
        return Result.success(contentUri)
    }
}
```

- [ ] **Step 5: SaveChatEditResultUseCaseTest 同步重写（msg() 工厂改 parts 构造）**

```kotlin
    private fun msg(ref: String?, saved: Boolean) = chatMessageEntity(
        id = "m1", sessionId = "s1", role = "agent",
        parts = listOf(MessagePart.EditResult("p0", ref = ref, description = "d", saved = saved)),
    )
```

（断言面同步：原先读 metadata 的 saved/imageUri 断言改读 `decodeParts()` 的 EditResult.ref/saved + metadata.savedAt。）

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/agent/remote/RemoteCommandDispatcher.kt androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt androidApp/src/main/java/com/mamba/picme/features/chat/browser/BrowserLiveSessionController.kt androidApp/src/main/java/com/mamba/picme/domain/usecase/
git commit -m "feat(chat): 远端/应用层/浏览器卡写方与保存编辑结果用例翻转 parts 直写"
```

---

### Task 8: 备份格式 v6（partsJson 直出直进）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/backup/model/TagDataBackup.kt`（version 5→6；BackupChatMessage +partsJson，content 加默认值）
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/backup/TagDataBackupRepository.kt`（导出/恢复双路径）

- [ ] **Step 1: 模型修改**

```kotlin
// TagDataBackup: val version: Int = 5 → val version: Int = 6

/**
 * v6（2026-11-08，parts 单一权威）：新增 [partsJson]（内容唯一权威直出）；
 * [content] 退役为空串占位（仅为旧版本可读性保留字段位；恢复以 partsJson 为准）。
 * v5- 备份无 partsJson，恢复走 legacy 列经 [com.mamba.picme.domain.chat.LegacyChatTypeMigration] 转正。
 */
@JsonClass(generateAdapter = true)
data class BackupChatMessage(
    val id: String,
    val sessionId: String,
    val type: String,
    val content: String = "",
    val timestamp: Long,
    val modelUsed: String? = null,
    val metadata: String? = null,
    val role: String? = null,
    val partsJson: String? = null,
)
```

- [ ] **Step 2: 导出（BackupChatMessage 构造处，L298 一带）**

```kotlin
        val chatMessages = chatMessageDao.getAllMessages().map {
            BackupChatMessage(
                id = it.id,
                sessionId = it.sessionId,
                type = it.type,
                content = "",
                timestamp = it.timestamp,
                modelUsed = it.modelUsed,
                metadata = it.metadata,   // DB 内已是白名单瘦身形态
                role = it.role,
                partsJson = it.partsJson,
            )
        }
```

- [ ] **Step 3: 恢复（L734 一带，双路径；partsJson 原文保留防 Unsupported payload 重编码丢失）**

```kotlin
            if (backup.chatMessages.isNotEmpty()) {
                backup.chatMessages.chunked(500).forEach { chunk ->
                    // 恢复双路径：v6 备份（partsJson 在场）→ 原文直进 + 投影重算；
                    // v5- 备份（无 partsJson）→ legacy 列经唯一映射知识库
                    // [LegacyChatTypeMigration] 转正后 converter 现算 parts。
                    chatMessageDao.insertPartsMessages(
                        chunk.map { msg ->
                            val decoded = MessagePartsCodec.decode(msg.partsJson)
                            if (decoded != null) {
                                ChatMessageEntity(
                                    id = msg.id,
                                    sessionId = msg.sessionId,
                                    type = MessagePartsProjection.roomTypeOf(decoded),
                                    role = msg.role ?: "agent",
                                    timestamp = msg.timestamp,
                                    modelUsed = msg.modelUsed,
                                    metadata = MessagePartsProjection.slimMetadataJson(msg.metadata),
                                    partsJson = msg.partsJson!!, // 原文保留（Unsupported 占位 payload 不随重编码丢失）
                                )
                            } else {
                                val row = if (msg.type in LegacyChatTypeMigration.NEW_TYPES) {
                                    LegacyChatTypeMigration.MigratedRow(msg.type, msg.role ?: "agent", emptyList())
                                } else {
                                    LegacyChatTypeMigration.map(msg.type, msg.content, msg.metadata)
                                }
                                val parts = MessagePartsConverter.toParts(
                                    row.type, msg.content, msg.metadata, roleOf(row.role),
                                )
                                chatMessageEntity(
                                    id = msg.id,
                                    sessionId = msg.sessionId,
                                    role = row.role,
                                    parts = parts,
                                    timestamp = msg.timestamp,
                                    modelUsed = msg.modelUsed,
                                    metadata = MessagePartsProjection.slimMetadataJson(msg.metadata),
                                )
                            }
                        },
                    )
                }
            }
```

（import 更新：`insertMessagesWithParts` → `insertPartsMessages` + `chatMessageEntity`；新增 `MessagePartsCodec`/`MessagePartsProjection`/`MessagePartsConverter`/`roleOf`/`MessagePart`。）

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/backup/
git commit -m "feat(backup): 备份模型 v6——chat 消息 partsJson 直出直进，v5- 走 legacy 转正恢复"
```

---

### Task 9: 测试编译面收口（构造器更新 + 全量编译转绿）

**Files:**
- Modify: `androidApp/src/androidTest/java/com/mamba/picme/data/local/ChatMessageDaoTest.kt`（helper 构造）
- Modify: `androidApp/src/androidTest/java/com/mamba/picme/data/local/ChatSessionDaoTest.kt`（2 处构造）
- Modify: `androidApp/src/test/java/com/mamba/picme/core/agenttools/ChatHistoryModelInputTest.kt`（构造）
- Modify: `androidApp/src/test/java/com/mamba/picme/features/chat/ChatViewModelGachaTest.kt`（stub 构造 → OptimizeCandidates part）

- [ ] **Step 1: 各测试文件的 `ChatMessageEntity(...)` 直构造全部改 `chatMessageEntity(...)`**

示例（ChatMessageDaoTest helper）：
```kotlin
    ) = chatMessageEntity(
        id = id, sessionId = sessionId, role = role,
        parts = listOf(MessagePart.Text("p0", "x", PartState.DONE)),
        timestamp = timestamp,
    )
```
（type 参数删除——投影自动派生；原 type 形参若被断言消费，改断言 entity.type 投影值。）

- [ ] **Step 2: ChatViewModelGachaTest 的 getMessageById stub**

`ChatMessageEntity(... metadata = group.toJson())` →
```kotlin
chatMessageEntity(
    id = "msg1", sessionId = "s1", role = "agent",
    parts = listOf(MessagePart.OptimizeCandidates("p0", group)),
)
```

- [ ] **Step 3: 全量编译**

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:compileDebugUnitTestKotlin :androidApp:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL（红 → 绿收口点；残留错误逐个修，多为 import 与漏网点）

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test(chat): 测试面构造器收口——chatMessageEntity 统一构造，全量编译转绿"
```

---

### Task 10: 文档同步（[DOC-SYNC] 红线）

**Files:**
- Modify: `docs/02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md`（顶部加 2026-11-08 修订注记）
- Modify: `shared/AGENTS.md`（M1 落地状态段更新为「写面已反转」）
- Modify: `androidApp/AGENTS.md`（§2.3 Room 版本行：v24 → v28 链补 v25/v26/v27/v28 四句）
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_PROTOCOL_TECH_SPEC.md`（双写/partsJson/legacy 列表述更新）
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`（同上）

- [ ] **Step 1: ADR-016 顶部注记（2026-11-08 修订注记格式循 2026-11-05 先例）**

```markdown
> 2026-11-08 修订：持久化接缝反转落地（写面权威移交）——chat_messages v28 起 partsJson 为唯一内容权威（NOT NULL），content 列退役，type 降级写时投影列（`MessagePartsProjection`），metadata 白名单瘦身（消息级住户 claude_agent_state/性能指标/savedAt）；对齐 Vercel「UIMessage 整包落库 + 用时投影」（v5 删 content string 同款收口）。写侧接缝 `chatMessageEntity`/`withUpdatedParts`，legacy 转换知识退居迁移/旧备份恢复专用。备份格式 v5→v6（partsJson 直出直进）。
```

- [ ] **Step 2: shared/AGENTS.md M1 落地状态段**——「`ChatMessage.parts` 双写共存（渲染源 M4 已切 parts）」更新为「持久化接缝已反转（2026-11-08）：Room partsJson 唯一权威，type 投影列，content 退役；`MessagePartsConverter`/`LegacyChatTypeMigration` 仅服务 MIGRATION_27_28 与旧备份恢复」。

- [ ] **Step 3: androidApp/AGENTS.md §2.3 data/local 行**——「`AppDatabase`（Room v24）」→「Room v28」，版本链补：「v25 `chat_messages` 加 `partsJson`（M1 双写过渡，MIGRATION_24_25）；v26 加 `role` + type 新分类法改写（MIGRATION_25_26）；v27 新增 `local_handle`（MIGRATION_26_27）；v28 chat_messages 持久化接缝反转——partsJson 唯一权威（NOT NULL），content 列退役，type 投影化，metadata 白名单瘦身（MIGRATION_27_28）」。

- [ ] **Step 4: 两篇 TECH_SPEC grep 更新**——`content`/`双写`/`legacy 列`/`partsJson` 命中处按新口径改写（落库协议段：消息 = partsJson 权威行 + 投影列；历史双轨一节删除或改写为「v27- 历史形态」）。

- [ ] **Step 5: 门禁**

Run: `./scripts/check_doc_sync.py`（或 `ai-gate.sh` 全量）
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add docs/ shared/AGENTS.md androidApp/AGENTS.md
git commit -m "docs(chat): ADR-016 修订注记 + 模块文档同步持久化接缝反转（v28）"
```

---

### Task 11: 全量验证闭环

- [ ] **Step 1: 编译 + 单测**

```bash
./gradlew :androidApp:compileDebugKotlin
JITPACK=true ./gradlew :shared:jvmTest
./gradlew :androidApp:testDebugUnitTest
```
Expected: 全绿

- [ ] **Step 2: androidTest（有设备/模拟器时）**

```bash
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mamba.picme.data.local.ChatMessagePartsMigrationTest
```
Expected: 24→25 / 25→26 / 27→28 全过

- [ ] **Step 3: ai-gate**

Run: `./scripts/ai-gate.sh`
Expected: PASS（detekt baseline 只减不增 / doc-sync / 隐私守卫）

- [ ] **Step 4: 真机冒烟（auto-dev-loop；验证迁移不丢消息 + 全链路渲染）**

1. 旧包（main 构建）装入测试机，造数据：文本对话 / 图卡 / HTML 卡（测高回填）/ 任务卡 / 抽卡条 / 媒体结果卡 / browser 定格卡（有啥造啥）；
2. 覆盖安装本分支包冷启动：历史消息全部在、卡片形态不跳变（HTML 卡 displayMode/测高保持）、任务中心列表标题正常、抽卡条选中态在；
3. 发新消息全链路：文本问答 → draw_chart 图卡 → render_html HTML 卡 → 搜索出媒体结果卡 → 删除卡片内单图（空卡转文案 / 非空原位更新）→ 保存编辑结果到相册（saved/ref 回写）；
4. 关屏重开 + 杀进程重进：消息/形态/选中态持久。

- [ ] **Step 5: 沉淀管线（根 AGENTS.md §4.3）**

本 plan 交付合 main 前：结论已沉淀进 ADR-016 修订注记 + shared/androidApp AGENTS.md + 两篇 TECH_SPEC（Task 10）；本 plan 文件 `git rm` 删除，git 历史可查。

---

## Self-Review 记录

- **Spec 覆盖**：目标态四要素（partsJson NOT NULL 权威 / type 投影 / content 退役 / metadata 白名单）分别由 Task 2/3 落地，写读翻转 Task 4~7，备份 Task 8，文档 Task 10，验证 Task 11。无双写残留面：DAO 裸 insert 仅本文件入口调用（注释立法 + review 核查）。
- **Unsupported 原文保留**：迁移（Task 3）与备份恢复（Task 8）两处均走「解码成功 → 原文保留」分支，已防重编码丢失。
- **类型一致性**：`chatMessageEntity`/`withUpdatedParts`/`decodeParts`/`insertPartsMessage(s)`/`taskCardStateOrNull` 命名全篇一致；`MessagePartsProjection.roomTypeOf/contentPreviewOf/slimMetadataJson` 签名 Task 1 定义、Task 2~8 消费一致。
- **遗留已知边角**（计划中已声明，非遗漏）：Unsupported 占位行 type 投影暂落 "text"；agent image 说明文本不保留（M4 起本不渲染）；v6 备份降级恢复到旧版本 app 内容为空（无版本门控，旧版读 v6 备份 content=""——已知不兜底，备份方向单向）。
