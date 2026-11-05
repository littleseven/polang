# OpenUI 唯一化（A2UI 全量退役）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 拆除 A2UI GenUI 线（依赖/渲染/契约/服务端工具链），OpenUI turn-native 成为唯一 GenUI 协议；存量 `data_ui` 消息降级为可读文本不丢消息。

**Architecture:** 服务端先切 turn-native only（无视 `X-GenUI-Format`），shared 做 part 类型删除 + a2ui 包拆分迁移 + legacy 墓碑兜底，androidApp 删渲染线/设置项/依赖，文档同步收尾。上线顺序：server 部署 → App 发包（双交付铁律）。

**Tech Stack:** Kotlin（KMP commonMain / Ktor server / Android Compose）、kotlinx.serialization、Koog、DataStore。

**Spec:** `docs/superpowers/specs/2026-11-05-openui-sole-genui-protocol-design.md`（拆除清单与决策记录以此为准）

**工作区：** 按根 AGENTS.md §3.4，开工前在 `.worktrees/` 建隔离 worktree + 专用分支（如 `feat/openui-sole-genui`），一切改动在 worktree 内提交。

---

## 关键事实备忘（executor 必读）

- `shared/domain/chat/a2ui/` 是**双用包**，禁止整包删：
  - 删：`A2uiCardContract.kt`（仅 PART_TYPE/CATALOG_ID/PROTOCOL_VERSION/DATA_PATH_*/ROLE_KEY/COLOR_ROLE_KEY/SPACING_KEY 随 A2UI 死；幸存成员见下）、`A2uiStyleTokens.kt` 的 `A2uiColorRole`/`A2uiSpacingRole`。
  - 迁：幸存成员并入 `domain/chat/openui/OpenUiContract.kt`——`HANDLE_SCHEME`、`DOMAIN_SCAN`、`formatHandleToken`、`parseLocalHandle`、`LocalHandle`（`formatLocalHandle` 先 grep，无幸存调用方则一并删）。
  - 迁+改名：`A2uiTextRole` → `domain/chat/openui/OpenUiTextRole.kt` 的 `OpenUiTextRole`（枚举值 HEADLINE/SUBHEAD/BODY/CAPTION 不变）。
  - 整文件迁移（仅改 package 行）：`ScanPhotoProjection.kt`（含 `ScanPhotoEntry`）、`GalleryHistogramBuilder.kt`（含 `HistogramBucket`）→ `domain/chat/openui/`。
- `MessagePart.A2uiCard` 消费点：`ChatListFlattener.contentTypeOf:209`、`ChatScreen.kt:893-917`、`ChatViewModel.kt`（14/2369/2471/3779-3830/4578 行区）、shared iosMain `ChatHistoryStoreCodec.kt:117,172`。
- `ChatMessageType.A2UI_CARD` 定义在 shared commonMain `ChatMessageType.kt`，iosMain codec 有两处分支。
- 服务端 turn-native 接线在 `AgentRoutes.kt:296-341`；`AgentRunner` 的 `forwardContentDeltas`/`finalMessageTransformer`/`actionGate`/`argsTransformer`/`onHtmlCard` 参数化全部是双线期产物，唯一化后内联收编。
- 设置项链路：`SettingsScreen.kt:228,359,443,579-610` + `SettingsViewModel.kt:309-310,1119` + `UserPreferencesRepository.kt:180,1324-1338` + `UserSettingsRepository.kt:218-221` + `CloudAgentClient.kt:93,184,190,196` + 五语 `settings_genui_format_*`。
- 服务端常量搬家源：`RenderOpenUiToolService.kt` companion（`D`、`GOLDEN_*` × 11、`ICON_NAMES_TEXT`、`DSL_RULES`，行 22-169+）。引用方：`OpenUiTurnNative.kt:55`（DSL_RULES）、`OpenUiTurnNativeTest`、`OpenUiLayoutNormalizerTest:174-182`、`AgentRunnerTest:431`。

---

### Task 1: shared — legacy `data_ui` 墓碑兜底（TDD 先行）

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsCodec.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsConverter.kt`（77-78 行 `data_ui` 分支）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/MessagePartsCodecTest.kt`（无则新建）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/MessagePartsConverterTest.kt`（无则新建）

- [ ] **Step 1: 写失败测试（codec 粒度：partsJson 内含 data_ui 的混合数组）**

```kotlin
@Test
fun decode_legacyDataUiPart_tombstonesToText() {
    val partsJson = """
        [
          {"type":"text","partId":"p0","markdown":"前后的文本","state":"DONE"},
          {"type":"data_ui","partId":"p1","surfaceId":"s1","messagesJson":"[]","fallbackSummary":"3 月的照片统计"},
          {"type":"data_ui_openui","partId":"p2","program":"root = Markdown(\"hi\")","fallbackSummary":"卡"}
        ]
    """.trimIndent()
    val parts = MessagePartsCodec.decode(partsJson)
    assertNotNull(parts)
    assertEquals(3, parts!!.size)
    val tombstone = parts[1] as MessagePart.Text
    assertEquals("3 月的照片统计", tombstone.markdown)
    assertIs<MessagePart.OpenUiCard>(parts[2])
}

@Test
fun decode_legacyDataUiWithoutSummary_tombstoneMarker() {
    val partsJson = """[{"type":"data_ui","partId":"p1","surfaceId":"s","messagesJson":"[]","fallbackSummary":""}]"""
    val parts = MessagePartsCodec.decode(partsJson)
    assertEquals(MessagePartsConverter.LEGACY_CARD_TOMBSTONE, (parts!!.single() as MessagePart.Text).markdown)
}
```

converter 侧：

```kotlin
@Test
fun toParts_legacyDataUi_liftsFallbackSummary() {
    val content = """{"type":"data_ui","partId":"p1","surfaceId":"s","messagesJson":"[]","fallbackSummary":"3 月的照片统计"}"""
    val parts = MessagePartsConverter.toParts("data_ui", content, null, ModelInputRole.AGENT)
    assertEquals("3 月的照片统计", (parts.single() as MessagePart.Text).markdown)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*MessagePartsCodecTest*" --tests "*MessagePartsConverterTest*"`
Expected: FAIL（`MessagePart.A2uiCard` 尚在时 codec 测试 2 的反例路径不通 / LEGACY_CARD_TOMBSTONE 未定义编译失败）

- [ ] **Step 3: 实现墓碑逻辑**

`MessagePartsConverter` 加常量与墓碑函数，并把 `"data_ui"` 分支一步到位改为墓碑（本 task 内 converter 即不再引用 `A2uiCard`，Task 2 删类时本文件零改动）：

```kotlin
/** legacy GenUI 卡（A2UI，2026-11-05 退役）墓碑占位符：fallbackSummary 缺失时的技术标记，非文案。 */
const val LEGACY_CARD_TOMBSTONE = "[data_ui]"

/** A2UI 已退役：存量行提升 fallbackSummary 为可读文本，不丢消息（ADR-016 降级口径）。 */
private fun legacyA2uiTombstone(content: String): MessagePart.Text {
    val obj = runCatching { json.parseToJsonElement(content) as? JsonObject }.getOrNull()
    val summary = obj?.get("fallbackSummary")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val partId = obj?.get("partId")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "p0"
    return MessagePart.Text(partId, summary ?: LEGACY_CARD_TOMBSTONE, PartState.DONE)
}
```

`convert` 的 `"data_ui"` 分支改为：

```kotlin
// A2UI 已退役（2026-11-05 spec《openui-sole-genui-protocol》）：存量行提升
// fallbackSummary 为可读文本；损坏行走 toParts 既有 runCatching → 行级 Text 兜底
"data_ui" -> listOf(legacyA2uiTombstone(content))
```

`MessagePartsCodec.decode` 改逐元素解码 + 显式 legacy 拦截（**先判 type 再解码**：Task 2 之前 `A2uiCard` 仍在、data_ui 能正常解码，必须显式拦截而非失败驱动；其余未知类型维持原语义——整列解码失败返回 null 走 legacy 列兜底）：

```kotlin
fun decode(partsJson: String?): List<MessagePart>? {
    if (partsJson.isNullOrBlank()) return null
    return runCatching {
        json.parseToJsonElement(partsJson).jsonArray.map { element ->
            legacyDataUiTombstoneOrNull(element)
                ?: json.decodeFromJsonElement(MessagePart.serializer(), element)
        }
    }.getOrNull()
}

/** legacy data_ui（A2UI 已退役）→ Text 墓碑：fallbackSummary 提升，不丢消息；非 data_ui 返回 null 走正常解码。 */
private fun legacyDataUiTombstoneOrNull(element: JsonElement): MessagePart? {
    val obj = element as? JsonObject ?: return null
    if (obj["type"]?.jsonPrimitive?.contentOrNull != "data_ui") return null
    val summary = obj["fallbackSummary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val partId = obj["partId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "p0"
    return MessagePart.Text(partId, summary ?: MessagePartsConverter.LEGACY_CARD_TOMBSTONE, PartState.DONE)
}
```

 imports 补 `kotlinx.serialization.json.jsonArray/decodeFromJsonElement/JsonElement/JsonObject/contentOrNull/jsonPrimitive`（按现有 import 风格）。

- [ ] **Step 4: 跑测试确认通过**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*MessagePartsCodec*" --tests "*MessagePartsConverter*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsCodec.kt \
        shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsConverter.kt \
        shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/
git commit -m "feat(shared): legacy data_ui 墓碑兜底——A2UI 退役后存量卡降级为可读文本"
```

---

### Task 2: shared — a2ui 包拆分迁移 + MessagePart.A2uiCard 删除

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiContract.kt`（并入 handle 幸存成员 + 去对偶注释）
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiTextRole.kt`
- Move: `a2ui/ScanPhotoProjection.kt`、`a2ui/GalleryHistogramBuilder.kt` → `openui/`（仅改 package 行 + KDoc 去 A2UI 表述）
- Delete: `a2ui/A2uiCardContract.kt`、`a2ui/A2uiStyleTokens.kt`
- Modify: `MessagePart.kt`（删 123-141 行 A2uiCard）、`ChatListFlattener.kt`（删 209 行分支 + 260-261 行 TYPE_A2UI_CARD 常量）、`ChatMessageType.kt`（删 A2UI_CARD 值）
- Modify: `shared/src/iosMain/kotlin/com/mamba/picme/domain/chat/ChatHistoryStoreCodec.kt`（删 117/172 两分支）
- Test 迁移：`commonTest/.../a2ui/ScanPhotoProjectionTest.kt`、`GalleryHistogramBuilderTest.kt` → `openui/`（改 package）；删 `A2uiCardContractTest.kt`、`A2uiStyleTokensTest.kt`（若含 TextRole 断言则把该部分改写为 OpenUiTextRole 测试迁入 openui/）

- [ ] **Step 1: `formatLocalHandle` 幸存判定**

Run: `grep -rn "formatLocalHandle" --include="*.kt" androidApp/src shared/src server/src | grep -v "features/chat/a2ui" | grep -v "domain/chat/a2ui"`
Expected: 无幸存调用方 → 不迁移该函数；有则并入 OpenUiContract。

- [ ] **Step 2: OpenUiContract.kt 并入幸存成员**（顶部注释去「与 A2uiCardContract 对偶」表述，改为「local:// handle 契约唯一出处」）：

```kotlin
const val HANDLE_SCHEME = "local://"

/** 相册扫描结果域（search_media / refine_media_search 命中集）。 */
const val DOMAIN_SCAN = "scan"

/** 上行 opaque token：`handle: scan/<id>`（无 scheme，过投影闸）。 */
fun formatHandleToken(domain: String, id: String): String = "handle: $domain/$id"

/** 解析 `local://<domain>/<id>[#<fragment>]`；非 local:// 或缺 domain/id 返 null。 */
fun parseLocalHandle(value: String): LocalHandle? {
    if (!value.startsWith(HANDLE_SCHEME)) return null
    val rest = value.removePrefix(HANDLE_SCHEME)
    val hashIndex = rest.indexOf('#')
    val pathPart = if (hashIndex >= 0) rest.substring(0, hashIndex) else rest
    val fragment = if (hashIndex >= 0) rest.substring(hashIndex + 1) else null
    val slashIndex = pathPart.indexOf('/')
    if (slashIndex <= 0 || slashIndex == pathPart.length - 1) return null
    return LocalHandle(
        domain = pathPart.substring(0, slashIndex),
        id = pathPart.substring(slashIndex + 1),
        fragment = fragment?.takeIf { frag -> frag.isNotEmpty() },
    )
}

/** local:// handle 的结构化形态。 */
data class LocalHandle(val domain: String, val id: String, val fragment: String? = null)
```

（函数/object 形态对齐 OpenUiContract 现有结构：成员进 object，LocalHandle 为文件级 data class。）

- [ ] **Step 3: 新建 `OpenUiTextRole.kt`**

```kotlin
package com.mamba.picme.domain.chat.openui

/**
 * OpenUI 文本语义层级——LLM 只声明意图层级，端侧映射 Design Token（PARITY）。
 * （自 domain.chat.a2ui.A2uiTextRole 迁入改名，2026-11-05 A2UI 退役。）
 */
enum class OpenUiTextRole {
    /** 卡片主标题（最大字号） */
    HEADLINE,

    /** 区块副标题 */
    SUBHEAD,

    /** 正文内容 */
    BODY,

    /** 辅助说明/脚注 */
    CAPTION,
}
```

- [ ] **Step 4: 迁移/删除文件 + 模型删除**

```bash
cd shared/src/commonMain/kotlin/com/mamba/picme/domain/chat
git mv a2ui/ScanPhotoProjection.kt openui/ScanPhotoProjection.kt
git mv a2ui/GalleryHistogramBuilder.kt openui/GalleryHistogramBuilder.kt
git rm a2ui/A2uiCardContract.kt a2ui/A2uiStyleTokens.kt
```

- 两迁移文件 package 行改 `package com.mamba.picme.domain.chat.openui`，KDoc 中 a2ui/A2UI 表述改 OpenUI。
- `MessagePart.kt` 删 `A2uiCard` 整个 data class（含 KDoc）。
- `ChatListFlattener.kt` 删 `is MessagePart.A2uiCard ->` 分支与 `TYPE_A2UI_CARD` 常量。
- `ChatMessageType.kt` 删 `A2UI_CARD` 枚举值（及 KDoc 提及）。
- `ChatHistoryStoreCodec.kt`（iosMain）删 `ChatMessageType.A2UI_CARD -> "data_ui"` 与 `is MessagePart.A2uiCard -> ...` 两分支。
- commonTest：`git mv` 两个幸存测试到 `commonTest/.../openui/` 并改 package；删 `A2uiCardContractTest.kt`、`A2uiStyleTokensTest.kt`；新建/改写 `OpenUiContractTest.kt`（parseLocalHandle/formatHandleToken 用例自 A2uiCardContractTest 迁移改写）。

- [ ] **Step 5: 编译 + 测试**

Run: `JITPACK=true ./gradlew :shared:assemble :shared:jvmTest`
Expected: shared 自身全绿（androidApp 引用此时必然红，属预期——Task 6 修）

- [ ] **Step 6: Commit**

```bash
git add shared/
git commit -m "refactor(shared): a2ui 包拆分退役——投影/handle/TextRole 迁 openui 包，删 MessagePart.A2uiCard"
```

---

### Task 3: server — OpenUiPromptAssets 常量搬家（TDD：测试改钉新位置）

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiPromptAssets.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiTurnNative.kt:7,55`（DSL_RULES 引用点）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/`（RenderOpenUiToolServiceTest 改名改写 + OpenUiTurnNativeTest/OpenUiLayoutNormalizerTest/AgentRunnerTest 引用点）

- [ ] **Step 1: 新建 `OpenUiPromptAssets.kt`**——把 `RenderOpenUiToolService.kt` companion object（行 22 至 `DSL_RULES` 结束）整体剪切为 `object OpenUiPromptAssets { … }`（`D`、`GOLDEN_*` × 11、`ICON_NAMES_TEXT`、`DSL_RULES` 逐字保留，含 private const val D）。文件头 KDoc：

```kotlin
/**
 * OpenUI prompt 资产（自 RenderOpenUiToolService 迁入，2026-11-05 A2UI 退役/OpenUI 唯一化）：
 * DSL 规则 + Icon 词表 + 黄金示例，[OpenUiTurnNative.SYSTEM_PROMPT] 与校验器/测试同源引用。
 */
```

- [ ] **Step 2: 全局改引用**

Run: `grep -rn "RenderOpenUiToolService\." server/src --include="*.kt" | grep -v "RenderOpenUiToolService.kt"`
把每个 `RenderOpenUiToolService.XXX`（常量引用）改 `OpenUiPromptAssets.XXX`（`OpenUiTurnNative.kt:55`、各测试）；`OpenUiTurnNative.kt` KDoc 的 `[RenderOpenUiToolService.DSL_RULES]` 改 `[OpenUiPromptAssets.DSL_RULES]`。

- [ ] **Step 3: `RenderOpenUiToolServiceTest.kt` 改名改写为 `OpenUiPromptAssetsTest.kt`**——类名/引用全换，断言内容不变（钉住 DSL_RULES 关键段、GOLDEN 程序过校验、ICON_NAMES_TEXT 与 `RenderOpenUiValidator.ICON_NAMES` 同源）。

- [ ] **Step 4: 编译 + 测试**（此时 `RenderOpenUiToolService.kt` 仍在但已无人引用，Task 5 删）

Run: `./gradlew -p server build`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/
git commit -m "refactor(server): OpenUI prompt 资产收口 OpenUiPromptAssets（常量搬家，零行为变化）"
```

---

### Task 4: server — turn-native only 切换（核心接线）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/AgentRoutes.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/AgentRunner.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/CloudAgentStrategy.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/ServerTools.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/AgentRoutesTest.kt`、`AgentRunnerTest.kt`、`CloudAgentStrategyTest.kt`（如有）

- [ ] **Step 1: `AgentRoutes.kt` 简化**

- 删 `GENUI_FORMAT_HEADER`（:39）、`GENUI_FORMAT_OPENUI`（:42）、`DEFAULT_SYSTEM_PROMPT`（含其内「发卡工具」表述段，:60 附近）。
- 删 `htmlCardSink`（:283-295）与 `turnNativeOpenUi`（:296-299）。
- toolRegistry 简化为：

```kotlin
val toolRegistry = ToolRegistry {
    tools(ServerToolService().asToolsByClass() + deviceTools.asToolsByClass())
}
```

- `runTurn` 调用简化为：`systemPrompt = OpenUiTurnNative.SYSTEM_PROMPT`，删 `forwardContentDeltas`/`finalMessageTransformer`/`onHtmlCard` 三参（随 Task 4 Step 2 的签名收缩），保留 `onOpenUiProgramDelta` 与 `onEvent`。

- [ ] **Step 2: `AgentRunner.kt` 收编**

- 删构造参数 `actionGate`/`argsTransformer`/`onHtmlCard`；删 `runTurn` 参数 `forwardContentDeltas`/`finalMessageTransformer`/`onHtmlCard`。
- 行为内联：`val programGuard = ProgramGuard()`（无条件）；contentStreamTracker 无条件发射（删 `if (forwardContentDeltas)` 门）；终结落流 `:240` 改 `payload = AgentEventPayload.Message(OpenUiTurnNative.ensureValidProgram(result))`。
- 删 `CardClaimGuard` 实例化（:112）与 `ctx.toolName in CardClaimGuard.CARD_TOOLS` 分支（:191）、`cloudAgentStrategy(cardGuard, programGuard)` → `cloudAgentStrategy(programGuard)`。
- 删 `replayPending(..., onHtmlCard)` 的 onHtmlCard 参与 `withHtmlCardSink` 调用（:276-279）。
- KDoc 同步（actionGate/argsTransformer/render_html sink 段删除或改写为 turn-native 唯一线表述）。

- [ ] **Step 3: `CloudAgentStrategy.kt` 删 `correctFakeCardClaim` 节点与 cardGuard 参数**（programGuard 恒非 null，`programGuard == null` 门控条件一并删）。

- [ ] **Step 4: `ServerTools.kt` 删 render_html 线**

- 删 `ServerToolService` 的 `onHtmlCard` 构造参、`@Tool(customName = "render_html")` 方法、`MAX_HTML_CHARS`；`ServerToolService(clock, onHtmlCard)` 调用点改无 sink 形态。
- 删 `ServerToolDispatcher.withHtmlCardSink` 与 `RenderHtmlArgs`。
- `AgentEvent.kt` 的 `AgentEventPayload.HtmlCard` **保留**（历史 agent_event 解码），KDoc 加注：「发射路径已随 render_html 退役（2026-11-05），类保留供历史事件回放解码」。
- `REVERSIBLE_TOOLS` 注释提及 render_html 处同步。

- [ ] **Step 5: 修测试**

Run: `grep -rln "RenderUiToolService\|RenderUiValidator\|CardClaimGuard\|GENUI_FORMAT\|X-GenUI-Format\|withHtmlCardSink\|onHtmlCard\|DEFAULT_SYSTEM_PROMPT" server/src/test --include="*.kt"`
逐文件处理：`RenderUiToolServiceTest`/`RenderUiValidatorTest`/`CardClaimGuardTest`（如存在）删除；`AgentRoutesTest` 断言改「无头也走 turn-native」（:671 区既有 openui 断言保留，缺头=A2UI 的断言改为缺头=turn-native）；`AgentRunnerTest:370` 的 RenderUiToolService registry 改 ServerToolService，:431 的 GOLDEN 引用已在 Task 3 改钉 OpenUiPromptAssets。

- [ ] **Step 6: 编译 + 全量测试**

Run: `./gradlew -p server build`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add server/
git commit -m "feat(server): GenUI 唯一化——turn-native only，render_ui/render_html/双线分流全退役"
```

---

### Task 5: server — 死代码清除 + 版本号

**Files:**
- Delete: `server/.../agent/RenderUiToolService.kt`、`RenderUiValidator.kt`、`CardClaimGuard.kt`、`OpenUiToolCallStreamTracker.kt`、`OpenUiProgramStreamExtractor.kt`、`RenderOpenUiToolService.kt`
- Modify: `server/build.gradle.kts`（version bump）

- [ ] **Step 1: 删除文件**

```bash
cd server/src/main/kotlin/com/mamba/picme/server/agent
git rm RenderUiToolService.kt RenderUiValidator.kt CardClaimGuard.kt \
      OpenUiToolCallStreamTracker.kt OpenUiProgramStreamExtractor.kt RenderOpenUiToolService.kt
```

- [ ] **Step 2: 残余引用清扫**

Run: `grep -rn "RenderUiToolService\|RenderUiValidator\|CardClaimGuard\|OpenUiToolCallStreamTracker\|OpenUiProgramStreamExtractor\|RenderOpenUiToolService" server/src --include="*.kt"`
Expected: 零命中（有则按 Task 3/4 口径改完）

- [ ] **Step 3: bump version + 全量验证**

`server/build.gradle.kts` version 自增一个 patch。Run: `./gradlew -p server build`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add server/
git commit -m "chore(server): 删除 A2UI/render_html 死代码六件，bump version"
```

---

### Task 6: androidApp — a2ui 渲染线删除 + 引用修复

**Files:**
- Delete: `androidApp/src/main/java/com/mamba/picme/features/chat/a2ui/`（8 文件）、`androidApp/src/test/java/com/mamba/picme/features/chat/a2ui/`（8 文件）
- Modify: `gradle/libs.versions.toml`（删 59 行版本 + 71-74 行四条库）、`androidApp/build.gradle.kts`（删对应 implementation 行）
- Modify: `ChatScreen.kt`（删 893-917 TYPE_A2UI_CARD 分支 + a2uiHandleResolver/polangA2uiCatalog/A2uiCardDegraded 相关声明与传参）、`ChatViewModel.kt`、`CloudAgentTurnRunner.kt`、`CloudAgentGalleryTools.kt`（import 换 openui 包）
- Modify: `features/chat/openui/` 五件 import（A2uiTextRole→OpenUiTextRole；A2uiCardContract.parseLocalHandle/DOMAIN_SCAN→OpenUiContract）

- [ ] **Step 1: 删文件与依赖**

```bash
git rm -r androidApp/src/main/java/com/mamba/picme/features/chat/a2ui \
          androidApp/src/test/java/com/mamba/picme/features/chat/a2ui
```

`libs.versions.toml` 删 `a2ui = "1.0.0-alpha01"` 与四条 `androidx-a2ui-*`/`androidx-compose-material3-a2ui`；`androidApp/build.gradle.kts` 删对应 implementation 行。

- [ ] **Step 2: `ChatScreen.kt` 删 TYPE_A2UI_CARD 分支**（893-917 整段 when 分支），并删 `a2uiHandleResolver`/`polangA2uiCatalog` 的 remember/传参与 `persistA2uiViewStateAsync` 调用；grep 清理：

Run: `grep -n "a2ui\|A2ui" androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 3: `ChatViewModel.kt` 删 data_ui 链路**

删：`import ...a2ui.A2uiCardContract`（:14）、`CloudTurnCardFlush` 的 genUiFormat 参与 `a2uiFlushes`（:2286-2288）、`insertA2uiCardMessage`（:3779-3830 区）、`is CloudTurnEvent.A2uiCard` 分支（:2471 区）、`is MessagePart.A2uiCard -> ChatMessageType.A2UI_CARD`（:4578）、`persistA2uiViewStateAsync`；`:2332` 的 `genUiFormatFlow.first()` 与 `CloudTurnPrepared.genUiFormat`（:2276,2358）一并删（Task 7 删 repository 成员前先把消费点清零）。

- [ ] **Step 4: `CloudAgentTurnRunner.kt` 删 `CloudTurnEvent.A2uiCard` 数据类（:33）与发射分支（:148 区）**；`CloudTurnCardFlush` 的 `genUiFormat` 构造参删除（构造调用 :2221 改无参）。

- [ ] **Step 5: openui 渲染层 import 切换**（机械替换）

Run: `grep -rln "domain.chat.a2ui" androidApp/src/main --include="*.kt"`
逐文件：`import com.mamba.picme.domain.chat.a2ui.A2uiTextRole` → `import com.mamba.picme.domain.chat.openui.OpenUiTextRole`（枚举值引用 `A2uiTextRole.` → `OpenUiTextRole.`）；`A2uiCardContract.parseLocalHandle` → `OpenUiContract.parseLocalHandle`、`A2uiCardContract.DOMAIN_SCAN` → `OpenUiContract.DOMAIN_SCAN`（`OpenUiCardHost.kt:229,233`、`CloudAgentGalleryTools.kt:99,102`）；`ScanPhotoEntry`/`ScanPhotoProjection`/`GalleryHistogramBuilder`/`HistogramBucket` import 包名改 openui。

- [ ] **Step 6: 编译 + 单测**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest`
Expected: PASS（残余红一律是漏改的 a2ui 引用，按上面口径补）

- [ ] **Step 7: Commit**

```bash
git add androidApp/ gradle/libs.versions.toml
git commit -m "feat(androidApp): 删除 A2UI 渲染线与 androidx.a2ui 依赖，openui 渲染层切换迁移后契约"
```

---

### Task 7: androidApp — GenUI 设置项拆除 + 请求头移除 + i18n

**Files:**
- Modify: `CloudAgentClient.kt`（删 :93,:184 参数、:190 日志段、:196 头）
- Modify: `UserSettingsRepository.kt:218-221`、`UserPreferencesRepository.kt:180,1324-1338`（含 `normalizeGenUiFormat`）
- Modify: `SettingsViewModel.kt:309-310,1119`、`SettingsScreen.kt:228,359,443,579-610`
- Modify: `androidApp/src/main/res/values{,-zh-rCN,-zh-rTW,-es,-fr}/strings.xml`（删 `settings_genui_format_title`/`settings_genui_format_subtitle`）
- Test: `CloudAgentClientTest.kt:100,115`（头断言删除/改写）

- [ ] **Step 1: `CloudAgentClient.kt`**——`postMessage`/`runTurn` 签名删 `genUiFormat: String`（接口与实现两处，:93 与 :184），删 :190 日志中的 `genUiFormat=$genUiFormat` 段与 :196 头设置行及 :195 注释。`ChatViewModel` 调用点（:2437 区）同步去参。

- [ ] **Step 2: 设置链路删除**——`UserSettingsRepository` 接口删 `genUiFormatFlow`/`updateGenUiFormat`；`UserPreferencesRepository` 删 `GENUI_FORMAT` key、`genUiFormatFlow`、`updateGenUiFormat`、`normalizeGenUiFormat`（DataStore 存量值自然失效，不清理）；`SettingsViewModel` 删 `genUiFormat` StateFlow（:309-310）与 `updateGenUiFormat` 转发（:1119 区 Logger 行一并）；`SettingsScreen` 删 :228 收集、:359 传参、:443 参数与默认值、:579-610 GenUI 行整块（含 RadioButton dialog）。

- [ ] **Step 3: i18n 清理**——五份 strings.xml 各删 `settings_genui_format_title` 与 `settings_genui_format_subtitle` 两行。

- [ ] **Step 4: 测试修复**——`CloudAgentClientTest.kt:100`（`assertEquals("a2ui", recorded.getHeader("X-GenUI-Format"))`）与 :115 断言删除/改写为不再发该头；grep 其余 `genUiFormat` 测试引用：

Run: `grep -rn "genUiFormat\|GenUI\|genui" androidApp/src/test --include="*.kt"`

- [ ] **Step 5: 编译 + 单测 + ai-gate**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest && ./scripts/ai-gate.sh`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add androidApp/
git commit -m "feat(androidApp): 拆除 GenUI 协议设置项与 X-GenUI-Format 头（OpenUI 唯一化）"
```

---

### Task 8: 文档同步（[DOC-SYNC]）

**Files:**
- Modify: `docs/02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md`
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`
- Modify: `server/AGENTS.md`、`shared/AGENTS.md`、`androidApp/AGENTS.md`、`androidApp/src/main/java/com/mamba/picme/features/settings/AGENTS.md`
- Delete: `docs-site/a2ui/`
- Check: `PRODUCT.md`、`docs/01-PRODUCT/FEATURES.md`（grep A2UI）

- [ ] **Step 1: ADR-016 修订**——D1 首条 parts 清单删 `HtmlCard`…列举中的 A2UI 提及（`MessagePart` sealed 列举去 A2uiCard）；type 值域「3 分类 11 值」口径改 10 值；文件头修订行下加：

```markdown
> 2026-11-05 修订：A2UI GenUI 线退役（对照组使命完成，OpenUI 已经生产验证；消灭双线校验/提示词执法成本），`data_ui` 值与 `MessagePart.A2uiCard` 删除，存量消息经 codec/converter 墓碑兜底为 Text（不丢消息）；外部 androidx.a2ui 成熟后可重评估。实施 spec/plan：2026-11-05-openui-sole-genui-protocol。
```

- [ ] **Step 2: CHAT_CARD_CATALOG**——`data_ui` 节标题加「（已退役 2026-11-05）」标注 + 首行指向 spec 名，正文保留作历史登记；`data_ui_openui` 节升为唯一 GenUI 线（删与 A2UI 对比/并存表述）；docs-site/a2ui catalog 的引用摘除。

- [ ] **Step 3: 模块 AGENTS.md**——`server/AGENTS.md` §1 云端 Agent 职责串删 render_ui/A2UI/分流表述、§5 决策表加退役行（决策/选择/理由三列）；`shared/AGENTS.md` §2 注记更新（子类型 11→10、a2ui 包拆分迁移记录、iconNames 四处副本改三处）；`androidApp/AGENTS.md` Chat 行删 a2ui 表述；`features/settings/AGENTS.md:137` GenUI 行删除。

- [ ] **Step 4: docs-site 与产品文档**

```bash
git rm -r docs-site/a2ui
grep -rn "A2UI\|a2ui" PRODUCT.md docs/01-PRODUCT/FEATURES.md
```

有命中则同步（无则跳过）。

- [ ] **Step 5: ai-gate + Commit**

Run: `./scripts/ai-gate.sh`
Expected: PASS（含 check_doc_sync 5/6 项）

```bash
git add docs/ docs-site/ server/AGENTS.md shared/AGENTS.md androidApp/AGENTS.md androidApp/src/main/java/com/mamba/picme/features/settings/AGENTS.md PRODUCT.md
git commit -m "docs: A2UI 退役决策记录落位（ADR-016 修订 + 卡片目录退役标注 + 模块规范同步）"
```

---

### Task 9: 终验 + 交付

- [ ] **Step 1: 全量构建矩阵**

```bash
./gradlew -p server build
JITPACK=true ./gradlew :shared:assemble :shared:jvmTest :shared:iosX64Test
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest
./scripts/ai-gate.sh
```

Expected: 全绿

- [ ] **Step 2: 真机冒烟（auto-dev-loop）**

```bash
./scripts/auto-dev-loop.sh
```

验证三点：① 云端 Agent 回合正常出 OpenUI 卡（流式预览 → 成卡）；② 构造/打开含历史 `data_ui` 卡的会话不崩、显示 fallbackSummary 文本；③ 设置页无 GenUI 协议行。

- [ ] **Step 3: 服务端部署（双交付铁律①）**

`./server/deploy.sh`（version 已于 Task 5 bump）→ healthz + 管理后台调用流水确认 turn-native prompt 生效。

- [ ] **Step 4: App 发包（双交付铁律②）**——debug OTA（`./scripts/ota-publish.sh`）+ 视情况 Play internal（`./scripts/play-publish.sh`）。

- [ ] **Step 5: spec/plan 沉淀收尾**——按根 AGENTS.md §4.3 沉淀管线：结论已落位活文档（Task 8），spec/plan 本体 `git rm` 或在 `docs/superpowers/README.md` §6 白名单摘除。

---

## Self-Review 记录

- **Spec 覆盖**：§3.1 server → Task 3/4/5；§3.2 shared → Task 1/2；§3.3 androidApp → Task 6/7；§3.4 文档 → Task 8；§4 兼容 → Task 1（墓碑）+ Task 4（无视头）；§5 上线顺序 → Task 9 Step 3/4；§6 验收 → Task 9；§7 Milestone B → 本计划不实施（spec 已登记方向）。
- **类型一致性**：`OpenUiTextRole`/`OpenUiContract.parseLocalHandle/DOMAIN_SCAN/formatHandleToken`/`OpenUiPromptAssets.DSL_RULES·GOLDEN_*·ICON_NAMES_TEXT`/`LEGACY_CARD_TOMBSTONE` 在定义 task 与消费 task 间一致。
- **已知执行期发现点**（计划允许 grep 确认）：`formatLocalHandle` 幸存判定（Task 2 Step 1）、`A2uiStyleTokensTest` 是否含 TextRole 断言（Task 2 Step 4）、服务端测试引用面（Task 4 Step 5 grep）。
