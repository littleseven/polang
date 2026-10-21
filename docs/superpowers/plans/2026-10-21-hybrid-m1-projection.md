# 混合架构 M1：投影通路 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为云端 Agent 端侧工具建立「locality 静态声明 + 集中投影闸」隐私闸门：一切出端结果经过 shared commonMain 单点校验，违规则 fail-closed，云端 LLM 可见描述只承诺投影面。

**Architecture:** shared commonMain 新增两个纯组件——`CloudAgentToolCatalog`（每工具 executor/resultSchema/projectionSchema 三声明，SSOT）与 `CloudToolProjectionGate`（出端禁出模式校验，fail-closed `error: projection_blocked:<tool>`）；Android `DeviceToolExecutor` 与 iOS `IosCloudAgentToolBridge` 的唯一出端点各接一行闸调用；server `DeviceToolService` 的 @LLMDescription 对齐 projectionSchema（字面量副本模式不变）。无协议改动、无行为变化（现有结果文本本就投影安全），纯结构性立闸。

**Tech Stack:** Kotlin Multiplatform（shared commonMain，kotlin.test）；androidApp JUnit4 + org.json；server Ktor + Koog 1.3.0 反射（`asToolsByClass().map { it.descriptor }`）。

**Spec:** `docs/superpowers/specs/2026-10-21-cloud-local-hybrid-a2ui-genui-design.md`（§3.1 Locality 声明、§3.4 强制不变式、§7 测试决策接缝 1、§9 M1 任务 [agent-task:hybrid-m1-projection]，Acceptance = US-1/US-3）

## Global Constraints

- **D1**：parts 协议零改动，不新增 part type；本 milestone 不动任何消息模型。
- **[PRIVACY]**：出端只许投影白名单（摘要/计数/文本元数据）；闸必须 fail-closed——违规内容绝不放行，替换为 `error: projection_blocked:<tool>`。
- **三端契约同步**：`CloudAgentToolContract`/`CloudAgentToolCatalog` 常量改动须同步核对 server `DeviceToolService` 与 Swift `CloudAgentChatStore` 字面量副本（既有双向注释模式）。
- **commonMain 纯度**：禁 `import android.*`/`java.*`；禁裸 `@Volatile`；禁通配符 import；lambda 参数显式命名（禁裸 `it`）。
- **构建验证**：`JITPACK=true ./gradlew :shared:jvmTest`；整体门槛 `JITPACK=true ./gradlew :shared:assemble`；server 独立 `./gradlew -p server build`。
- **已知挂账（不阻塞）**：`:shared:iosX64Test` 的 `IosChatPromptTest` 8/9 工具漂移为既有失败（根因 9aacc1fa4），非本计划引入。
- **[I18N]**：M1 无用户可见文案（闸拦截文本走 `error:` 协议前缀，非 UI 字符串），不触 strings.xml。
- 不 push 远端；提交前 `git status` 确认只 add 本任务文件。

---

### Task 1: CloudAgentToolCatalog — locality 静态声明层

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudAgentToolCatalog.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudAgentToolCatalogTest.kt`

**Interfaces:**
- Consumes: `CloudAgentToolContract.TOOL_*` 三常量（同包，已存在）。
- Produces: `enum class ToolExecutor { CLOUD, LOCAL }`；`data class CloudToolSpec(name: String, executor: ToolExecutor, resultSchema: String, projectionSchema: String)`；`object CloudAgentToolCatalog` 暴露 `fun specFor(tool: String): CloudToolSpec?`。Task 5 的 server 描述对齐以各 `projectionSchema` 文本为准。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.inference.remote.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudAgentToolCatalogTest {

    @Test
    fun `all three bridge tools are declared`() {
        listOf(
            CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        ).forEach { name ->
            assertNotNull(CloudAgentToolCatalog.specFor(name), "missing spec for $name")
        }
    }

    @Test
    fun `all bridge tools are LOCAL executor`() {
        listOf(
            CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        ).forEach { name ->
            assertEquals(ToolExecutor.LOCAL, CloudAgentToolCatalog.specFor(name)?.executor)
        }
    }

    @Test
    fun `spec names match contract constants exactly`() {
        assertEquals("get_gallery_summary", CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY)
        assertEquals("search_media", CloudAgentToolContract.TOOL_SEARCH_MEDIA)
        assertEquals("refine_media_search", CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH)
    }

    @Test
    fun `write tools have no spec (unknown to cloud)`() {
        assertNull(CloudAgentToolCatalog.specFor("delete_media"))
    }

    @Test
    fun `projection schemas promise no media references`() {
        listOf(
            CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        ).forEach { name ->
            val projection = CloudAgentToolCatalog.specFor(name)!!.projectionSchema
            assertTrue(projection.contains("不含"), "$name projection must state exclusion clause")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolCatalogTest"`
Expected: FAIL — 编译错误 `Unresolved reference: CloudAgentToolCatalog`

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.mamba.picme.agent.core.inference.remote.tool

/**
 * 云端 Agent 工具注册表（spec §3.1 Locality 静态声明）——每工具 executor + resultSchema +
 * projectionSchema 三声明，代码自有、非运行时判断。
 *
 * projectionSchema 是出端白名单的人类可读描述；机器可校验的禁出校验由
 * [CloudToolProjectionGate] 承载（同一文件族）。云端 LLM 可见描述只许承诺 projectionSchema
 * 这个面——server `DeviceToolService` 的 @LLMDescription 与之逐字对齐（字面量副本模式，
 * 改动须双向同步核对）。
 *
 * 完整结果（resultSchema 描述的结构，如 search_media 的 List<MediaAsset>）永不离开设备；
 * M2 起经 local:// handle 端上补全引用。本表是 [PRIVACY] 闸的审计入口。
 */

/** 工具执行位。 */
enum class ToolExecutor { CLOUD, LOCAL }

/** 云端 Agent 工具注册条目。 */
data class CloudToolSpec(
    val name: String,
    val executor: ToolExecutor,
    /** 完整结果结构描述（端上保留，不上云）。 */
    val resultSchema: String,
    /** 出端白名单描述（摘要/计数/文本元数据）；必须含「不含」排除子句声明禁出项。 */
    val projectionSchema: String,
)

object CloudAgentToolCatalog {

    private val specs: Map<String, CloudToolSpec> = listOf(
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            executor = ToolExecutor.LOCAL,
            resultSchema = "GallerySummary 结构（照片/视频总数、人物聚类数、已打标数等）",
            projectionSchema = "纯文本摘要：仅计数与文本元数据，不含文件路径/uri/媒体清单",
        ),
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            executor = ToolExecutor.LOCAL,
            resultSchema = "List<MediaAsset> 完整资产列表（含 uri）+ 总数",
            projectionSchema = "计数文本「找到 N 张『query』的照片」：仅数量+命中说明，不含文件路径/uri/媒体清单",
        ),
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
            executor = ToolExecutor.LOCAL,
            resultSchema = "List<MediaAsset> 细化后完整资产列表（含 uri）+ 基数/命中数",
            projectionSchema = "计数文本「在上一轮结果内找到 N 张」：仅数量，不含文件路径/uri/媒体清单",
        ),
    ).associateBy { spec -> spec.name }

    fun specFor(tool: String): CloudToolSpec? = specs[tool]
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolCatalogTest"`
Expected: PASS（5 用例）

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudAgentToolCatalog.kt shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudAgentToolCatalogTest.kt
git commit -m "feat(agent): 混合架构 M1——CloudAgentToolCatalog locality 三声明（executor/resultSchema/projectionSchema）"
```

---

### Task 2: CloudToolProjectionGate — 集中投影闸（fail-closed）

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudToolProjectionGate.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudToolProjectionGateTest.kt`

**Interfaces:**
- Consumes: 无（纯函数，仅 stdlib Regex）。
- Produces: `object CloudToolProjectionGate`：`const val BLOCKED_PREFIX = "error: projection_blocked:"`、`fun gate(tool: String, outbound: String): String`。Task 3/4 的两端执行器在唯一出端点调用 `gate`（先 gate 后 truncate）。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.inference.remote.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudToolProjectionGateTest {

    @Test
    fun `clean projection passes through unchanged`() {
        val text = "找到 327 张「猫」的照片"
        assertEquals(text, CloudToolProjectionGate.gate("search_media", text))
    }

    @Test
    fun `error text without forbidden pattern passes through`() {
        val text = "error: 没有上一轮搜索结果可细化"
        assertEquals(text, CloudToolProjectionGate.gate("refine_media_search", text))
    }

    @Test
    fun `content uri is blocked`() {
        val out = CloudToolProjectionGate.gate("search_media", "找到 1 张 content://media/external/images/42")
        assertEquals("error: projection_blocked:search_media", out)
    }

    @Test
    fun `file uri is blocked`() {
        assertEquals(
            "error: projection_blocked:search_media",
            CloudToolProjectionGate.gate("search_media", "file:///storage/emulated/0/DCIM/a.jpg"),
        )
    }

    @Test
    fun `local handle is blocked`() {
        assertEquals(
            "error: projection_blocked:search_media",
            CloudToolProjectionGate.gate("search_media", "详见 local://scan/7f3a"),
        )
    }

    @Test
    fun `absolute storage path is blocked`() {
        assertEquals(
            "error: projection_blocked:get_gallery_summary",
            CloudToolProjectionGate.gate("get_gallery_summary", "最新: /storage/emulated/0/DCIM/x.jpg"),
        )
        assertEquals(
            "error: projection_blocked:get_gallery_summary",
            CloudToolProjectionGate.gate("get_gallery_summary", "/sdcard/DCIM/x.jpg"),
        )
    }

    @Test
    fun `app private path is blocked`() {
        assertEquals(
            "error: projection_blocked:search_media",
            CloudToolProjectionGate.gate("search_media", "/data/user/0/com.mamba.picme/files/x"),
        )
        assertEquals(
            "error: projection_blocked:search_media",
            CloudToolProjectionGate.gate("search_media", "/data/media/0/x"),
        )
    }

    @Test
    fun `error message leaking path is also blocked`() {
        val out = CloudToolProjectionGate.gate("search_media", "error: /storage/emulated/0/db locked")
        assertEquals("error: projection_blocked:search_media", out)
    }

    @Test
    fun `blocked output itself passes the gate (idempotent safe)`() {
        val blocked = CloudToolProjectionGate.gate("search_media", "content://x")
        assertEquals(blocked, CloudToolProjectionGate.gate("search_media", blocked))
        assertTrue(blocked.startsWith(CloudToolProjectionGate.BLOCKED_PREFIX))
    }

    @Test
    fun `empty string passes through`() {
        assertEquals("", CloudToolProjectionGate.gate("search_media", ""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.agent.core.inference.remote.tool.CloudToolProjectionGateTest"`
Expected: FAIL — 编译错误 `Unresolved reference: CloudToolProjectionGate`

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.mamba.picme.agent.core.inference.remote.tool

/**
 * 出端投影闸（spec §3.1 集中投影函数 + §3.4 强制不变式）——一切上云的端侧工具结果
 * 必经此闸，不散落在各工具里。
 *
 * fail-closed：命中禁出模式时原内容**不出端**，替换为 `error: projection_blocked:<tool>`
 *（契约 `error:` 前缀，server/端侧/TurnRunner 同口径识别为失败；该文本本身即是
 * 可观测信号——违反投影不变式时表现为功能失败而非静默泄漏）。
 *
 * 禁出模式 = [PRIVACY] 红线的机器可校验面：媒体引用（content://、file://、local://）
 * 与全量路径（/storage/、/sdcard/、/data/user/、/data/media/）。文本元数据/计数/摘要放行。
 */
object CloudToolProjectionGate {

    const val BLOCKED_PREFIX = "error: projection_blocked:"

    private val FORBIDDEN: List<Regex> = listOf(
        Regex("content://"),
        Regex("file://"),
        Regex("local://"),
        Regex("/storage/"),
        Regex("/sdcard/"),
        Regex("/data/user/"),
        Regex("/data/media/"),
    )

    /** 校验出端文本；安全则原样返回，违规则 fail-closed 替换。 */
    fun gate(tool: String, outbound: String): String {
        val hit = FORBIDDEN.firstOrNull { pattern -> pattern.containsMatchIn(outbound) }
        return if (hit == null) outbound else "$BLOCKED_PREFIX$tool"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.agent.core.inference.remote.tool.CloudToolProjectionGateTest"`
Expected: PASS（10 用例）

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudToolProjectionGate.kt shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudToolProjectionGateTest.kt
git commit -m "feat(agent): 混合架构 M1——CloudToolProjectionGate 出端投影闸（fail-closed 禁出模式）"
```

---

### Task 3: Android 接线 — DeviceToolExecutor 出端过闸

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/core/agenttools/DeviceToolExecutor.kt`（唯一出端 return + 类注释）
- Test: `androidApp/src/test/java/com/mamba/picme/core/agenttools/DeviceToolExecutorTest.kt`（追加 2 用例）

**Interfaces:**
- Consumes: Task 2 的 `CloudToolProjectionGate.gate(tool, outbound)`。
- Produces: 不变（`execute(tool, args): String` 签名不动；上游 CloudAgentClient/SSE 链零感知）。

- [ ] **Step 1: Write the failing test**

追加到 `DeviceToolExecutorTest`：

```kotlin
    @Test
    fun `handler result containing content uri is blocked by projection gate`() = runTest {
        val exec = executor(search = { _, _, _, _, _ -> "找到 2 张 content://media/external/images/1" })
        assertEquals(
            "error: projection_blocked:search_media",
            exec.execute("search_media", JSONObject().put("query", "x")),
        )
    }

    @Test
    fun `handler error leaking storage path is blocked by projection gate`() = runTest {
        val exec = executor(gallery = { throw IllegalStateException("/storage/emulated/0/db locked") })
        assertEquals(
            "error: projection_blocked:get_gallery_summary",
            exec.execute("get_gallery_summary", JSONObject()),
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.core.agenttools.DeviceToolExecutorTest"`
Expected: FAIL — 两个新用例实际返回原文（未被拦）

- [ ] **Step 3: Write minimal implementation**

`DeviceToolExecutor.kt`：import 追加 `import com.mamba.picme.agent.core.inference.remote.tool.CloudToolProjectionGate`；最终 return 由

```kotlin
        return CloudAgentToolContract.truncate(result)
```

改为（先闸后截断——闸校验完整文本）：

```kotlin
        return CloudAgentToolContract.truncate(CloudToolProjectionGate.gate(tool, result))
```

类注释「工具名/错误前缀/截断契约收口…」行尾追加一句：`出端结果一律过 shared [CloudToolProjectionGate] 投影闸（混合架构 M1，fail-closed）。`

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.core.agenttools.DeviceToolExecutorTest"`
Expected: PASS（原 5 + 新 2 = 7 用例）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/core/agenttools/DeviceToolExecutor.kt androidApp/src/test/java/com/mamba/picme/core/agenttools/DeviceToolExecutorTest.kt
git commit -m "feat(agent): M1 Android 接线——DeviceToolExecutor 出端过 CloudToolProjectionGate"
```

---

### Task 4: iOS 接线 — IosCloudAgentToolBridge 出端过闸

**Files:**
- Modify: `shared/src/iosMain/kotlin/com/mamba/picme/agent/core/capability/IosCloudAgentToolBridge.kt`（execute 唯一出端 return + 类注释）

**Interfaces:**
- Consumes: Task 2 的 `CloudToolProjectionGate.gate`（commonMain，iosMain 直接可调）。
- Produces: 不变（`execute(tool, args): String` 签名不动，Swift 侧零改动）。
- 测试策略：闸本体由 Task 2 commonTest 覆盖（commonTest 在 iosX64Test 同样运行）；本任务为单行接线，由既有 `IosCloudAgentToolBridgeTest` 回归 + 编译验证兜底。

- [ ] **Step 1: 修改 execute 出端点**

import 追加 `import com.mamba.picme.agent.core.inference.remote.tool.CloudToolProjectionGate`；`execute` 末尾由

```kotlin
        return CloudAgentToolContract.truncate(result)
```

改为：

```kotlin
        return CloudAgentToolContract.truncate(CloudToolProjectionGate.gate(tool, result))
```

类注释「工具名/错误前缀/截断常量的 SSOT…」段尾追加一句：`出端结果一律过 [CloudToolProjectionGate] 投影闸（混合架构 M1，fail-closed），与 Android DeviceToolExecutor 同闸。`

- [ ] **Step 2: 编译 + 回归验证**

Run: `JITPACK=true ./gradlew :shared:compileKotlinIosX64 && JITPACK=true ./gradlew :shared:iosX64Test --tests "com.mamba.picme.agent.core.capability.IosCloudAgentToolBridgeTest"`
Expected: 编译成功；IosCloudAgentToolBridgeTest 全绿（`IosChatPromptTest` 8/9 漂移为既有失败，不在本任务范围）

- [ ] **Step 3: Commit**

```bash
git add shared/src/iosMain/kotlin/com/mamba/picme/agent/core/capability/IosCloudAgentToolBridge.kt
git commit -m "feat(agent): M1 iOS 接线——IosCloudAgentToolBridge 出端过 CloudToolProjectionGate"
```

---

### Task 5: server 描述对齐 — @LLMDescription 只承诺投影面

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/DeviceToolService.kt`（三处 @LLMDescription + 类注释）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/DeviceToolDescriptionTest.kt`（新建）

**Interfaces:**
- Consumes: Task 1 各工具 `projectionSchema` 文本（对齐基准；server 持字面量副本）。
- Produces: 不变（Koog ToolDescriptor 的 name/args 不动，仅 description 文案收窄承诺面）。

- [ ] **Step 1: Write the failing test**

新建 `server/src/test/kotlin/com/mamba/picme/server/agent/DeviceToolDescriptionTest.kt`：

```kotlin
package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.reflect.asToolsByClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LLM 可见描述只许承诺 projectionSchema 面（混合架构 M1，spec §3.1③）：
 * 三设备工具描述必须显式声明「不含文件路径/媒体清单」，防止描述漂移暗示全量数据可得。
 */
class DeviceToolDescriptionTest {

    private val descriptors = DeviceToolService("s", AgentBridgeRegistry()) { _ -> }
        .asToolsByClass()
        .associateBy { tool -> tool.descriptor.name }

    @Test
    fun `three device tools present`() {
        assertEquals(
            setOf("get_gallery_summary", "search_media", "refine_media_search"),
            descriptors.keys,
        )
    }

    @Test
    fun `every description promises projection-only surface`() {
        descriptors.forEach { (name, tool) ->
            val description = tool.descriptor.description
            assertTrue(description.contains("投影"), "$name description must state projection")
            assertTrue(description.contains("不含文件路径"), "$name description must state exclusion")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.DeviceToolDescriptionTest"`
Expected: FAIL — `every description promises projection-only surface` 断言失败（现描述无「投影」字样）

- [ ] **Step 3: Write minimal implementation**

`DeviceToolService.kt` 三处 @LLMDescription 改写（参数描述不动）：

- `getGallerySummary`：
  `获取用户手机相册摘要（照片/视频总数、人物聚类数、已打标数等）。工具在用户设备上执行，结果以文本摘要回传（投影：仅计数与文本元数据，不含文件路径与媒体清单）。`
- `searchMedia`：首句改为
  `在用户手机相册搜索照片，结果以文本摘要回传（投影：仅数量+命中说明，不含文件路径与媒体清单）。`
  后半句参数说明原文保留。
- `refineMediaSearch`：首句改为
  `在上一轮 search_media 结果内细化过滤（如'只要夜景'），结果以文本摘要回传（投影：仅数量，不含文件路径与媒体清单）。`
  后半句原文保留。

类注释「工具名/`error:` 前缀/32_000 字符截断的 SSOT = …」段尾追加一句：
`@LLMDescription 的投影承诺面与 shared CloudAgentToolCatalog.projectionSchema 逐字对齐（混合架构 M1），改动须双向同步核对。`

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.DeviceToolDescriptionTest"`
Expected: PASS（2 用例）

- [ ] **Step 5: server 全量回归**

Run: `./gradlew -p server build`
Expected: BUILD SUCCESSFUL（全部既有测试绿）

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/DeviceToolService.kt server/src/test/kotlin/com/mamba/picme/server/agent/DeviceToolDescriptionTest.kt
git commit -m "feat(server): M1 描述对齐——DeviceToolService @LLMDescription 只承诺投影面"
```

---

### Task 6: 文档同步 + 全量验证 + 合 main

**Files:**
- Modify: `shared/AGENTS.md`（§2 `inference/remote/` 行）
- Modify: `androidApp/AGENTS.md`（§3.2「云端 Agent 工具桥」行）
- Modify: `server/AGENTS.md`（§2 结构 `DeviceToolService.kt` 行 + §1 云端 Agent 会话域 bullet）
- Modify: `docs/superpowers/README.md`（§6 plans 索引补录本计划——若 plan 入库提交未含）

- [ ] **Step 1: shared/AGENTS.md**

§2 `inference/remote/` 行中 `CloudAgentToolContract`（同包，M3）描述句尾追加：`；M1（混合架构投影通路）新增同包 `CloudAgentToolCatalog`（工具 locality 三声明：executor/resultSchema/projectionSchema，[PRIVACY] 闸审计入口）+ `CloudToolProjectionGate`（出端集中投影闸，fail-closed `error: projection_blocked:<tool>`，Android DeviceToolExecutor 与 iOS IosCloudAgentToolBridge 唯一出端点同闸）`。

- [ ] **Step 2: androidApp/AGENTS.md**

§3.2「云端 Agent 工具桥（ADR-018 M2）」行尾追加：`；出端结果统一过 shared `CloudToolProjectionGate` 投影闸（混合架构 M1，2026-10-21）——命中媒体引用/全量路径禁出模式即 fail-closed 回 `error: projection_blocked:<tool>`，原内容不出端`。

- [ ] **Step 3: server/AGENTS.md**

§2 结构树 `DeviceToolService.kt` 行改为：`DeviceToolService.kt  # 端侧只读三件套 Koog 适配（tool_request 帧 + 120s 挂起；M1 起 @LLMDescription 只承诺投影面，对齐 shared CloudAgentToolCatalog.projectionSchema）`；§1 云端 Agent 会话域 bullet 尾追加：`；M1（2026-10-21，混合架构投影通路）端侧结果出端过 shared CloudToolProjectionGate（fail-closed），LLM 可见描述只承诺投影（计数/摘要，不含路径/媒体清单）`。

- [ ] **Step 4: 全量验证**

```bash
JITPACK=true ./gradlew :shared:jvmTest
JITPACK=true ./gradlew :shared:assemble
./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.core.agenttools.*"
./gradlew -p server build
./scripts/ai-gate.sh
```

Expected: 全绿（iosX64Test 不在本轮；known failure 见 Global Constraints）

- [ ] **Step 5: Commit + 合 main + 清 worktree**

```bash
git add shared/AGENTS.md androidApp/AGENTS.md server/AGENTS.md docs/superpowers/README.md
git commit -m "docs(agent): 混合架构 M1 投影通路——模块 AGENTS 同步"
# 回 main 快进合并，删 worktree 与分支
```

---

## Self-Review 记录

- **Spec 覆盖**：M1 Expected Change ① → Task 1（注册表三声明）；② → Task 2（集中闸）+ Task 3/4（双端接线）+ 各任务单测；③ → Task 5（描述对齐 + 反射测试钉住）。Acceptance US-1（抓包验证）属真机遛狗人工 AC，记入交付挂账；US-3（云端拿 projection 续推）为既有行为，本计划不改语义。
- **M2/M3 不在本计划**（spec §9 分期；各自独立 plan）。
- **placeholder 扫描**：无 TBD/TODO；所有代码步骤含完整代码。
- **类型一致性**：`CloudToolProjectionGate.gate(tool, outbound)` / `CloudAgentToolCatalog.specFor(tool)` / `BLOCKED_PREFIX` 跨任务引用一致；Task 3/4 均「先 gate 后 truncate」。
