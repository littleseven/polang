# 云端 Agent M2（端侧工具桥 + BYOK 授权 + 最小聊天页）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让云端 Agent 经 SSE 下行 + POST 上行的工具桥调用端侧只读相册工具，BYOK 用户显式授权后走自有 Key 通道，并提供调试入口的最小云端聊天页完成端云遛狗。

**Architecture:** picme-server 侧新增 `AgentBridgeRegistry`（内存挂起表 requestId→Deferred）+ `DeviceToolService`（Koog @Tool 反射三件套，execute = 写 tool_request SSE 帧 → 挂起等上行 → 120s 超时兜底）；路由层在 `respondTextWriter` 内把服务端工具与设备工具合成同一 ToolRegistry（AgentRunner 零改动，事件帧与工具帧共用 writer 保序）。Android 侧新增 `CloudAgentClient`（OkHttp SSE/REST）+ `DeviceToolExecutor`（工具名 → ChatToolService handler，≤32K 字符截断）+ `CloudAgentChatViewModel/Screen`（调试入口实验页）。

**Tech Stack:** server = Ktor 3.0.3 + Koog 1.3.0 + kotlinx.serialization + JUnit4/testApplication/MockEngine；androidApp = Kotlin + OkHttp 5.2.1 + org.json + Compose + DataStore + JUnit4/mockk/MockWebServer。

**Spec:** `docs/superpowers/specs/2026-10-17-cloud-agent-tool-bridge-design.md`（执行者须先读；帧格式、错误矩阵、AC 表以 spec 为准）

## Global Constraints

- 工作区：`/Users/guoshuai/AndroidStudioProjects/polang/.worktrees/cloud-agent-m1`，Task 1 第一步先建分支 `feat/cloud-agent-m2`（基于当前 `feat/cloud-agent-m1` HEAD，M1 未合 main，M2 叠其上）。
- **提交卫生**：用户常预暂存无关改动——每次 commit 前 `git status`，只 `git add` 本任务列出的文件；**禁止 push**（用户未授权）。
- server 构建/测试命令一律 `./gradlew -p server <task>`（独立 Gradle 工程，rootProject.name=picme-server）。
- shared/androidApp 构建一律带 `JITPACK=true`（Koog metadata 阿里云镜像间歇 404）。
- BYOK 零留存：X-User-Api-Key 只在 `postMessage` 请求头出现，不落库、不进日志、不进埋点（守卫测试钉死）。
- [I18N]：所有用户可见文案五语同步（values EN / values-zh-rCN / values-zh-TW / values-es / values-fr）。
- [PRIVACY]：工具桥只回传文本摘要/搜索结果文本，媒体文件字节永不上行。
- Kotlin 编码约定：lambda 参数显式命名（禁止 `it`）；禁止通配符 import；日志 tag `PoLang:CloudAgent`。
- 服务端 @Tool 方法参数全部必填无默认值（Koog 反射派发 R8 稳健性，ChatToolService 先例）。

---

### Task 1: Server — AgentBridgeRegistry 挂起表

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/AgentBridgeRegistry.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/AgentBridgeRegistryTest.kt`

**Interfaces:**
- Consumes: 无（纯 kotlinx.coroutines + JDK）
- Produces（Task 2/3 依赖）：
  - `class AgentBridgeRegistry`
  - `fun register(sessionId: String, requestId: String): CompletableDeferred<String>`
  - `fun complete(requestId: String, result: String): Boolean`（false = 未知或已完成 → 路由回 409）
  - `fun failAll(sessionId: String, reason: String)`（连接断开清理，等待方收 `"error: <reason>"`）
  - `internal fun pendingCount(): Int`（测试探针）

- [ ] **Step 1: 建分支 + 写失败测试**

```bash
git -C /Users/guoshuai/AndroidStudioProjects/polang/.worktrees/cloud-agent-m1 checkout -b feat/cloud-agent-m2
```

```kotlin
package com.mamba.picme.server.agent

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBridgeRegistryTest {

    @Test
    fun `register then complete delivers result and removes entry`() = runTest {
        val registry = AgentBridgeRegistry()
        val deferred = registry.register("s1", "tr_aaa")
        assertEquals(1, registry.pendingCount())
        assertTrue(registry.complete("tr_aaa", "找到 3 张"))
        assertEquals("找到 3 张", deferred.await())
        assertEquals(0, registry.pendingCount()) // invokeOnCompletion 自动摘除
    }

    @Test
    fun `complete unknown requestId returns false`() {
        val registry = AgentBridgeRegistry()
        assertFalse(registry.complete("tr_ghost", "x"))
    }

    @Test
    fun `double complete returns false on second`() = runTest {
        val registry = AgentBridgeRegistry()
        registry.register("s1", "tr_aaa")
        assertTrue(registry.complete("tr_aaa", "first"))
        assertFalse(registry.complete("tr_aaa", "second"))
    }

    @Test
    fun `failAll completes session pendings with error and leaves others`() = runTest {
        val registry = AgentBridgeRegistry()
        val d1 = registry.register("s1", "tr_a")
        val d2 = registry.register("s2", "tr_b")
        registry.failAll("s1", "connection_closed")
        assertEquals("error: connection_closed", d1.await())
        assertFalse(d2.isCompleted)
        assertEquals(1, registry.pendingCount())
    }

    @Test
    fun `cancelled deferred is removed from registry`() = runTest {
        val registry = AgentBridgeRegistry()
        val d = registry.register("s1", "tr_c")
        val job = launch { d.await() }
        job.cancel()
        d.cancel()
        assertEquals(0, registry.pendingCount())
    }
}
```

- [ ] **Step 2: 跑测试确认红（编译失败：类不存在）**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.AgentBridgeRegistryTest"`
Expected: FAIL（compilation error: unresolved reference AgentBridgeRegistry）

- [ ] **Step 3: 实现**

```kotlin
package com.mamba.picme.server.agent

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具桥挂起表（ADR-018 决策 2，spec §3）：requestId → 等待端侧结果的 Deferred。
 * 内存单进程语义——服务重启即清空（spec §3.4 服务重启行：残留 RUNNING 会话由
 * 下轮 pending 重放兜底）。条目随 deferred 完成自动摘除（invokeOnCompletion），防泄漏。
 */
class AgentBridgeRegistry {
    private data class Entry(val sessionId: String, val deferred: CompletableDeferred<String>)

    private val pending = ConcurrentHashMap<String, Entry>()

    fun register(sessionId: String, requestId: String): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()
        pending[requestId] = Entry(sessionId, deferred)
        deferred.invokeOnCompletion { pending.remove(requestId) }
        return deferred
    }

    /** @return false = requestId 未知或已完成（路由层据此回 409 tool_request_not_pending）。 */
    fun complete(requestId: String, result: String): Boolean =
        pending[requestId]?.deferred?.complete(result) ?: false

    /** SSE 连接关闭时清理该会话全部挂起项；等待方以 error 文本闭环（Observation 照常落流）。 */
    fun failAll(sessionId: String, reason: String) {
        pending.values.filter { entry -> entry.sessionId == sessionId }.forEach { entry ->
            entry.deferred.complete("error: $reason")
        }
    }

    /** 测试探针。 */
    internal fun pendingCount(): Int = pending.size
}
```

- [ ] **Step 4: 跑测试确认绿**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.AgentBridgeRegistryTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/AgentBridgeRegistry.kt server/src/test/kotlin/com/mamba/picme/server/agent/AgentBridgeRegistryTest.kt
git commit -m "feat(server): cloud agent M2 工具桥挂起表 AgentBridgeRegistry"
```

---

### Task 2: Server — DeviceToolService（Koog @Tool 设备工具三件套）

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/DeviceToolService.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/DeviceToolServiceTest.kt`

**Interfaces:**
- Consumes: `AgentBridgeRegistry`（Task 1）
- Produces（Task 3 依赖）：
  - `data class ToolRequestFrame(requestId: String, tool: String, args: JsonObject, timeoutMs: Long)` + `fun toJson(): JsonObject`（输出 `{"kind":"tool_request","request_id":…,"tool":…,"args":{…},"timeout_ms":…}`）
  - `class DeviceToolService(sessionId: String, bridge: AgentBridgeRegistry, timeoutMs: Long = DEFAULT_TIMEOUT_MS, onToolRequest: suspend (ToolRequestFrame) -> Unit)`
  - `DeviceToolService.DEFAULT_TIMEOUT_MS = 120_000L`
  - `fun deviceToolsAsList(service: DeviceToolService)` 不需要——Task 3 直接 `service.asToolsByClass()`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.server.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeviceToolServiceTest {

    @Test
    fun `execute writes tool_request frame then returns device result`() = runTest {
        val bridge = AgentBridgeRegistry()
        val frames = mutableListOf<ToolRequestFrame>()
        val service = DeviceToolService("s1", bridge) { frame ->
            frames.add(frame)
            // 模拟端侧上行：写帧后立即补结果
            bridge.complete(frame.requestId, "找到 3 张猫")
        }
        val result = service.searchMedia("猫", "", "", "", "")
        assertEquals("找到 3 张猫", result)
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals("search_media", frame.tool)
        assertEquals("猫", frame.args["query"]!!.jsonPrimitive.content)
        assertTrue(frame.requestId.startsWith("tr_"))
        assertEquals(DeviceToolService.DEFAULT_TIMEOUT_MS, frame.timeoutMs)
        // 帧 JSON 线格式钉死（spec §3.1）
        val wire = frame.toJson().toString()
        assertTrue(wire.contains("\"kind\":\"tool_request\""))
        assertTrue(wire.contains("\"request_id\":\"${frame.requestId}\""))
    }

    @Test
    fun `write frame failure returns device_offline without suspending`() = runTest {
        val bridge = AgentBridgeRegistry()
        val service = DeviceToolService("s1", bridge) { throw IOException("broken pipe") }
        assertEquals("error: device_offline", service.getGallerySummary())
        assertEquals(0, bridge.pendingCount())
    }

    @Test
    fun `timeout returns device_tool_timeout`() = runTest {
        val bridge = AgentBridgeRegistry()
        val service = DeviceToolService("s1", bridge, timeoutMs = 50) { }
        assertEquals("error: device_tool_timeout", service.getGallerySummary())
        assertEquals(0, bridge.pendingCount()) // 超时后条目摘除（迟到上行将 409）
    }

    @Test
    fun `refine passes constraint and time bounds`() = runTest {
        val bridge = AgentBridgeRegistry()
        var captured: ToolRequestFrame? = null
        val service = DeviceToolService("s1", bridge) { frame ->
            captured = frame
            bridge.complete(frame.requestId, "ok")
        }
        service.refineMediaSearch("只要夜景", "1700000000000", "")
        assertEquals("refine_media_search", captured!!.tool)
        assertEquals("只要夜景", captured!!.args["constraint"]!!.jsonPrimitive.content)
        assertEquals("1700000000000", captured!!.args["fromMs"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.DeviceToolServiceTest"`
Expected: FAIL（unresolved reference DeviceToolService）

- [ ] **Step 3: 实现**

```kotlin
package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * SSE 下行工具请求帧（spec §3.1）：传输层信号，**不落事件流**。
 * Action 事件已由 EventHandler.onToolCallStarting 落流，二者无需关联（request_id 只活在桥层）。
 */
data class ToolRequestFrame(
    val requestId: String,
    val tool: String,
    val args: JsonObject,
    val timeoutMs: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", "tool_request")
        put("request_id", requestId)
        put("tool", tool)
        put("args", args)
        put("timeout_ms", timeoutMs)
    }
}

/**
 * 端侧设备工具的云端 Koog 适配器（ADR-018 决策 2 工具桥，spec §3.3）：
 * execute = 注册挂起 → 经 [onToolRequest] 向 SSE 写 tool_request 帧 → withTimeout 等上行结果。
 * 写帧失败 = 端已断线，立即 `error: device_offline` 不挂起；超时 = `error: device_tool_timeout`。
 * 两者都以文本返回（Observation 照常落流，pending 不变式不被桥破坏）。
 *
 * 每轮由路由层以当轮 SSE writer 构造新实例（onToolRequest 捕获 writer），不进单例。
 * @Tool 参数全部必填无默认值（Koog 反射派发约束，对齐端侧 ChatToolService 先例）。
 * M2 只读三件套；写类工具等决策 1a 逆操作执行链（M3）。
 */
class DeviceToolService(
    private val sessionId: String,
    private val bridge: AgentBridgeRegistry,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onToolRequest: suspend (ToolRequestFrame) -> Unit,
) {

    @Tool(customName = "get_gallery_summary")
    @LLMDescription("获取用户手机相册摘要（照片/视频总数、人物聚类数、已打标数等）。工具在用户设备上执行，结果以文本回传。")
    suspend fun getGallerySummary(): String =
        executeOnDevice("get_gallery_summary", buildJsonObject {})

    @Tool(customName = "search_media")
    @LLMDescription("在用户手机相册搜索照片，结果以文本摘要回传（数量+命中说明）。query 自然语言搜索词；person 人物分组名或称谓（无则空串）；fromMs/toMs 毫秒时间串（query 含时间词时必须换算填入，空串=不限）；hasFace 传 'true'=只要含人脸照片（空串=不限）。")
    suspend fun searchMedia(
        @LLMDescription("自然语言搜索词") query: String,
        @LLMDescription("人物分组名或称谓，无则空串") person: String,
        @LLMDescription("时间起点毫秒串，空串=不限") fromMs: String,
        @LLMDescription("时间终点毫秒串，空串=不限") toMs: String,
        @LLMDescription("'true'=只要含人脸；空串=不限") hasFace: String,
    ): String = executeOnDevice(
        "search_media",
        buildJsonObject {
            put("query", query)
            put("person", person)
            put("fromMs", fromMs)
            put("toMs", toMs)
            put("hasFace", hasFace)
        },
    )

    @Tool(customName = "refine_media_search")
    @LLMDescription("在上一轮 search_media 结果内细化过滤（如'只要夜景'）。constraint 细化条件；fromMs/toMs 毫秒串（空串=不限）。上一轮无搜索基数时设备会返回错误，此时改用 search_media。")
    suspend fun refineMediaSearch(
        @LLMDescription("细化条件") constraint: String,
        @LLMDescription("时间起点毫秒串，空串=不限") fromMs: String,
        @LLMDescription("时间终点毫秒串，空串=不限") toMs: String,
    ): String = executeOnDevice(
        "refine_media_search",
        buildJsonObject {
            put("constraint", constraint)
            put("fromMs", fromMs)
            put("toMs", toMs)
        },
    )

    private suspend fun executeOnDevice(tool: String, args: JsonObject): String {
        val requestId = "tr_" + UUID.randomUUID().toString().replace("-", "").take(8)
        val deferred = bridge.register(sessionId, requestId)
        try {
            onToolRequest(ToolRequestFrame(requestId, tool, args, timeoutMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 写帧失败 = 端已断线：不挂起，立即错误返回
            deferred.complete("error: device_offline")
            return "error: device_offline"
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            "error: device_tool_timeout"
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.DeviceToolServiceTest"`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/DeviceToolService.kt server/src/test/kotlin/com/mamba/picme/server/agent/DeviceToolServiceTest.kt
git commit -m "feat(server): cloud agent M2 设备工具 Koog 适配器（tool_request 帧 + 挂起等上行）"
```

---

### Task 3: Server — AgentRoutes 接线（tool-results 端点 + 工具集合成）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/AgentRoutes.kt`（签名扩展 + 新端点 + messages 路由工具集合成 + failAll 清理）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`（构造 bridge 传入路由）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/agent/AgentRoutesTest.kt`（`agentRoute(runner)` 调用点补 bridge 参数）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/AgentRoutesBridgeTest.kt`

**Interfaces:**
- Consumes: `AgentBridgeRegistry`（Task 1）、`DeviceToolService`（Task 2）、M1 `AgentRunner.runTurn(...)`（签名不变）
- Produces:
  - `fun Route.agentRoute(runner: AgentRunner, bridge: AgentBridgeRegistry, deviceTimeoutMs: Long = DeviceToolService.DEFAULT_TIMEOUT_MS)`
  - `POST /agent/sessions/{id}/tool-results`：`{"request_id","ok","result"}` → `200 {"accepted":true}` / `400 request_id_required` / `404 session_not_found` / `409 tool_request_not_pending`
  - `POST /agent/sessions/{id}/messages` SSE 新增 `tool_request` 帧（与 event/done/error 并列）

**关键设计**：`AgentRunner` 零改动——设备工具集在路由层 `respondTextWriter` 内以当轮 writer 构造并合成进 ToolRegistry，tool_request 帧与 event 帧共用 writer 天然保序。

- [ ] **Step 1: 写失败测试（桥集成 + 错误语义）**

```kotlin
package com.mamba.picme.server.agent

import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.llm.AuthStyle
import com.mamba.picme.server.llm.ChannelConfig
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class AgentRoutesBridgeTest {

    @After
    fun tearDown() {
        ChannelRegistry.setActiveForTesting(null)
    }

    /** MockEngine 两轮：第一轮 tool_calls(search_media)，第二轮最终文本。 */
    private fun toolCallThenTextEngine(): MockEngine {
        val callCount = AtomicInteger(0)
        return MockEngine {
            val n = callCount.incrementAndGet()
            val body = if (n == 1) {
                """{"id":"c1","object":"chat.completion","created":0,"model":"glm-5.2","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"search_media","arguments":"{\"query\":\"猫\",\"person\":\"\",\"fromMs\":\"\",\"toMs\":\"\",\"hasFace\":\"\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
            } else {
                """{"id":"c2","object":"chat.completion","created":0,"model":"glm-5.2","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"找到 3 张猫的照片。"}}],"usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
    }

    private fun activateChannel() {
        ChannelRegistry.setActiveForTesting(
            ChannelConfig(
                1, "TestChan", "direct", "http://up.example/chat",
                AuthStyle.BEARER, "tok-abc",
                emptyMap(), "glm-5.2",
            ),
        )
    }

    @Test
    fun `tool-results with unknown requestId returns 409`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val account = runBlocking { AccountService.createOrRefresh("b409@x.com", 100) }
        val session = AgentSessionRepository.create(
            AccountService.sha256(account.token), AgentChannelKind.GATEWAY, "", "",
        )
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { agentRoute(newRunner(toolCallThenTextEngine()), AgentBridgeRegistry()) }
        }
        val resp = client.post("/agent/sessions/${session.id}/tool-results") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"request_id":"tr_ghost","ok":true,"result":"x"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertTrue(resp.bodyAsText().contains("tool_request_not_pending"))
    }

    @Test
    fun `tool-results on other owner's session returns 404`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val owner = runBlocking { AccountService.createOrRefresh("owner@x.com", 100) }
        val stranger = runBlocking { AccountService.createOrRefresh("stranger@x.com", 100) }
        val session = AgentSessionRepository.create(
            AccountService.sha256(owner.token), AgentChannelKind.GATEWAY, "", "",
        )
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { agentRoute(newRunner(toolCallThenTextEngine()), AgentBridgeRegistry()) }
        }
        val resp = client.post("/agent/sessions/${session.id}/tool-results") {
            header(APP_TOKEN_HEADER, stranger.token)
            contentType(ContentType.Application.Json)
            setBody("""{"request_id":"tr_x","ok":true,"result":"x"}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `AC-M2-S1 tool bridge round trip over SSE`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val account = runBlocking { AccountService.createOrRefresh("bridge@x.com", 100) }
        activateChannel()
        val bridge = AgentBridgeRegistry()
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { agentRoute(newRunner(toolCallThenTextEngine()), bridge) }
        }
        val sid = createSession(account.token)
        val frames = Collections.synchronizedList(mutableListOf<String>())
        val sseJob = launch(Dispatchers.IO) {
            client.preparePost("/agent/sessions/$sid/messages") {
                header(APP_TOKEN_HEADER, account.token)
                contentType(ContentType.Application.Json)
                setBody("""{"content":"找猫"}""")
            }.execute { resp ->
                val ch = resp.bodyAsChannel()
                val sb = StringBuilder()
                val buf = ByteArray(4096)
                while (!ch.isClosedForRead) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    if (n > 0) {
                        sb.append(String(buf, 0, n))
                        var idx = sb.indexOf("\n\n")
                        while (idx >= 0) {
                            frames.add(sb.substring(0, idx))
                            sb.delete(0, idx + 2)
                            idx = sb.indexOf("\n\n")
                        }
                    }
                }
            }
        }
        // 等 tool_request 帧到达后回传结果
        val requestId = withTimeout(15_000) {
            while (true) {
                val hit = frames.firstOrNull { f -> f.contains("\"tool_request\"") }
                if (hit != null) {
                    return@withTimeout kotlinx.serialization.json.Json
                        .parseToJsonObject(hit.removePrefix("data:").trim())
                        .jsonObject["request_id"]!!.jsonPrimitive.content
                }
                delay(50)
            }
        }
        val trResp = client.post("/agent/sessions/$sid/tool-results") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"request_id":"$requestId","ok":true,"result":"找到 3 张"}""")
        }
        assertEquals(HttpStatusCode.OK, trResp.status)
        sseJob.join()
        val all = frames.joinToString("\n")
        assertTrue(all.contains("\"observation\""))
        assertTrue(all.contains("找到 3 张"))
        assertTrue(all.contains("找到 3 张猫的照片。"))
        assertTrue(frames.last().contains("\"done\""))
        assertEquals(0, bridge.pendingCount())
    }

    @Test
    fun `AC-M2-S2 device timeout yields error observation and done`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val account = runBlocking { AccountService.createOrRefresh("timeout@x.com", 100) }
        activateChannel()
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            // 缩短设备超时注入（生产默认 120s 不动）
            routing { agentRoute(newRunner(toolCallThenTextEngine()), AgentBridgeRegistry(), deviceTimeoutMs = 300) }
        }
        val sid = createSession(account.token)
        // 不回传 tool-results，让设备工具超时；MockEngine 第二轮照常给最终文本
        val resp = client.post("/agent/sessions/$sid/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"找猫"}""")
        }
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"tool_request\""))
        assertTrue(body.contains("device_tool_timeout"))
        assertTrue(body.contains("\"done\""))
    }

    private fun newRunner(engine: MockEngine): AgentRunner = AgentRunner(
        // 与 M1 AgentRunnerTest.runnerWith 同款的 MockEngine 注入缝
        AgentExecutorFactory(KtorKoogHttpClient.Factory(HttpClient(engine))),
        ServerToolDispatcher.default(),
    )

    private suspend fun createSession(token: String): String {
        // 走 Repository 直建（路由建会话另有 M1 测试覆盖）
        return AgentSessionRepository.create(
            AccountService.sha256(token), AgentChannelKind.GATEWAY, "", "",
        ).id
    }
}
```

（import `ai.koog.http.client.ktor.KtorKoogHttpClient`。）

- [ ] **Step 2: 跑测试确认红**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.AgentRoutesBridgeTest"`
Expected: FAIL（编译错误：agentRoute 单参 / tool-results 不存在 / TokenHashKey import 缺失——见 Step 3 说明）

- [ ] **Step 3: 实现**

3a. `AgentRoutes.kt` 修改：

- 类级 KDoc 补：`POST /agent/sessions/{id}/tool-results` 端点说明与 tool_request 帧说明。
- 签名：`fun Route.agentRoute(runner: AgentRunner, bridge: AgentBridgeRegistry, deviceTimeoutMs: Long = DeviceToolService.DEFAULT_TIMEOUT_MS)`。
- import 补：`com.mamba.picme.server.routes.TokenHashKey`、`ai.koog.agents.core.tools.ToolRegistry`、`ai.koog.agents.core.tools.reflect.asToolsByClass`。
- messages 路由的 `respondTextWriter` 块改为：

```kotlin
call.respondTextWriter(ContentType.Text.EventStream) {
    val deviceTools = DeviceToolService(session.id, bridge, deviceTimeoutMs) { frame ->
        writeSseFrame(frame.toJson())
    }
    val toolRegistry = ToolRegistry {
        tools(ServerToolService().asToolsByClass() + deviceTools.asToolsByClass())
    }
    try {
        runner.runTurn(
            session = session,
            userMessage = content,
            target = target,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            toolRegistry = toolRegistry,
            onEvent = { event ->
                writeSseFrame(buildJsonObject {
                    put("kind", "event")
                    put("event", eventJson(event))
                })
            },
        )
        writeSseFrame(buildJsonObject {
            put("kind", "done")
            put("session_status", AgentSessionStatus.RUNNING.wire)
        })
    } catch (e: Throwable) {
        AgentSessionRepository.updateStatus(session.id, AgentSessionStatus.ERROR)
        if (gatewayQuotaCharged) AccountService.revertQuota(tokenHash)
        writeSseFrame(buildJsonObject {
            put("kind", "error")
            put("message", e.message?.take(200) ?: "internal error")
        })
    } finally {
        // 连接关闭（正常结束/异常/端断线）清理该会话桥挂起项，防泄漏
        bridge.failAll(session.id, "connection_closed")
    }
}
```

3b. `route("/agent")` 内新增端点（放 finish 之后）：

```kotlin
post("/sessions/{id}/tool-results") {
    val tokenHash = call.attributes.getOrNull(TokenHashKey)
        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
    val session = findOwned(call.parameters["id"].orEmpty(), tokenHash)
        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "session_not_found"))
    val body = call.receive<JsonObject>()
    val requestId = (body["request_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "request_id_required"))
    val ok = (body["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
    val result = (body["result"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    // ok=false：端执行异常，result 为错误描述，云端以 "error: <描述>" 作为工具返回值（spec §3.2）
    val text = if (ok) result else "error: $result"
    if (!bridge.complete(requestId, text)) {
        return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "tool_request_not_pending"))
    }
    call.respond(buildJsonObject { put("accepted", true) })
}
```

3c. `Application.kt`：`val agentBridge = AgentBridgeRegistry()`（在 agentRunner 构造旁），路由注册改 `agentRoute(agentRunner, agentBridge)`。

3d. M1 `AgentRoutesTest.kt` 中 `agentRoute(runner)` 调用点全部补第二参 `AgentBridgeRegistry()`（编译驱动，逐处修）。

3e. `TokenHashKey` 若 M1 测试未 import 过：它在 `com.mamba.picme.server.routes`（LlmRouteStreamTest 有先例 import）。

- [ ] **Step 4: 跑测试确认绿 + M1 回归**

Run: `./gradlew -p server test`
Expected: 全绿（M1 299 + 本任务新增 4 + Task1/2 的 9）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/AgentRoutes.kt server/src/main/kotlin/com/mamba/picme/server/Application.kt server/src/test/kotlin/com/mamba/picme/server/agent/AgentRoutesTest.kt server/src/test/kotlin/com/mamba/picme/server/agent/AgentRoutesBridgeTest.kt
git commit -m "feat(server): cloud agent M2 工具桥路由接线——tool-results 上行端点 + SSE 合成注册表"
```

---

### Task 4: Android — CloudAgentClient（SSE/REST 客户端）

**Files:**
- Modify: `gradle/libs.versions.toml`（加 mockwebserver）
- Modify: `androidApp/build.gradle.kts`（testImplementation mockwebserver）
- Create: `androidApp/src/main/java/com/mamba/picme/data/remote/picme/CloudAgentClient.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/data/remote/picme/CloudAgentClientTest.kt`

**Interfaces:**
- Consumes: 无（OkHttp + org.json，ClaudeChatClient 同款形态）
- Produces（Task 7 依赖）：
  - `sealed interface CloudFrame`：`Event(event: JSONObject)` / `ToolRequest(requestId, tool, args: JSONObject, timeoutMs: Long)` / `Done(sessionStatus: String)` / `Error(message: String)`
  - `object CloudSseParser { fun parseBlock(block: String): CloudFrame? }`
  - `interface CloudAgentApi`：`createSession(token, channel, model, byokBaseUrl): Result<JSONObject>` / `getEvents(token, sessionId, after=0L, limit=500): Result<List<JSONObject>>` / `finish(token, sessionId): Result<Unit>` / `postMessage(token, sessionId, content, byokApiKey: String?, onFrame: (CloudFrame) -> Unit): Result<Unit>` / `postToolResult(token, sessionId, requestId, ok, result): Result<Unit>`
  - `class CloudAgentClient(baseUrl: String = "https://api.polang.net") : CloudAgentApi`

- [ ] **Step 1: 加测试依赖 + 写失败测试**

`gradle/libs.versions.toml` libraries 段加（okhttp 已有 `5.2.1` version key）：

```toml
mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
```

`androidApp/build.gradle.kts` dependencies 段 `testImplementation(libs.mockk)` 旁加：

```kotlin
testImplementation(libs.mockwebserver)
```

测试（帧解析纯逻辑 + MockWebServer 线上行为）：

```kotlin
package com.mamba.picme.data.remote.picme

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAgentClientTest {

    @Test
    fun `parseBlock parses four frame kinds`() {
        val event = CloudSseParser.parseBlock("""data: {"kind":"event","event":{"id":7,"type":"message"}}""")
        assertTrue(event is CloudFrame.Event)
        assertEquals(7, (event as CloudFrame.Event).event.getInt("id"))

        val tr = CloudSseParser.parseBlock("""data: {"kind":"tool_request","request_id":"tr_ab12","tool":"search_media","args":{"query":"猫"},"timeout_ms":120000}""")
        assertTrue(tr is CloudFrame.ToolRequest)
        assertEquals("tr_ab12", (tr as CloudFrame.ToolRequest).requestId)
        assertEquals("search_media", tr.tool)
        assertEquals("猫", tr.args.getString("query"))
        assertEquals(120_000L, tr.timeoutMs)

        assertTrue(CloudSseParser.parseBlock("""data: {"kind":"done","session_status":"running"}""") is CloudFrame.Done)
        assertEquals("boom", (CloudSseParser.parseBlock("""data: {"kind":"error","message":"boom"}""") as CloudFrame.Error).message)
        // 容错：坏 JSON / 空 request_id / 未知 kind 一律 null 跳过
        assertEquals(null, CloudSseParser.parseBlock("data: {bad json"))
        assertEquals(null, CloudSseParser.parseBlock("""data: {"kind":"tool_request","request_id":""}"""))
        assertEquals(null, CloudSseParser.parseBlock("""data: {"kind":"mystery"}"""))
    }

    @Test
    fun `createSession posts channel fields and returns session json`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session_id":"s_1","status":"running","channel":"byok","model":"kimi-k2.6"}"""))
        server.start()
        // MockWebServer url 带尾 "/"，client 拼路径用 "$baseUrl/agent/..."——去掉尾斜杠防双斜杠
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        val result = client.createSession("tok", "byok", "kimi-k2.6", "https://api.moonshot.cn/v1/")
        assertTrue(result.isSuccess)
        assertEquals("s_1", result.getOrThrow().getString("session_id"))
        val recorded = server.takeRequest()
        assertEquals("tok", recorded.getHeader("X-App-Token"))
        val sent = JSONObject(recorded.body.readUtf8())
        assertEquals("byok", sent.getString("channel"))
        assertEquals("kimi-k2.6", sent.getString("model"))
        assertEquals("https://api.moonshot.cn/v1/", sent.getString("byok_base_url"))
        server.shutdown()
    }

    @Test
    fun `postMessage sends BYOK key header only when provided and streams frames`() = runTest {
        val server = MockWebServer()
        val sse = "data: {\"kind\":\"event\",\"event\":{\"id\":1,\"type\":\"message\",\"source\":\"agent\",\"role\":\"assistant\",\"payload\":{\"type\":\"message\",\"content\":\"你好\"}}}\n\n" +
            "data: {\"kind\":\"tool_request\",\"request_id\":\"tr_1\",\"tool\":\"get_gallery_summary\",\"args\":{},\"timeout_ms\":120000}\n\n" +
            "data: {\"kind\":\"done\",\"session_status\":\"running\"}\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        val frames = mutableListOf<CloudFrame>()
        val result = client.postMessage("tok", "s_1", "hi", byokApiKey = "sk-secret", onFrame = { frame -> frames.add(frame) })
        assertTrue(result.isSuccess)
        assertEquals(3, frames.size)
        assertTrue(frames[0] is CloudFrame.Event)
        assertTrue(frames[1] is CloudFrame.ToolRequest)
        assertTrue(frames[2] is CloudFrame.Done)
        val recorded = server.takeRequest()
        // BYOK 零留存锚点：key 只出现在这一个 header，不进 body
        assertEquals("sk-secret", recorded.getHeader("X-User-Api-Key"))
        assertTrue(!recorded.body.readUtf8().contains("sk-secret"))
        server.shutdown()
    }

    @Test
    fun `postMessage without byok key omits header`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("data: {\"kind\":\"done\"}\n\n"))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        client.postMessage("tok", "s_1", "hi", byokApiKey = null, onFrame = {})
        assertEquals(null, server.takeRequest().getHeader("X-User-Api-Key"))
        server.shutdown()
    }

    @Test
    fun `postToolResult posts ok and result`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":true}"""))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        val result = client.postToolResult("tok", "s_1", "tr_1", ok = false, result = "相册为空")
        assertTrue(result.isSuccess)
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("tr_1", sent.getString("request_id"))
        assertEquals(false, sent.getBoolean("ok"))
        assertEquals("相册为空", sent.getString("result"))
        server.shutdown()
    }

    @Test
    fun `postToolResult 409 surfaces as failure`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"tool_request_not_pending"}"""))
        server.start()
        val client = CloudAgentClient(server.url("/").toString().removeSuffix("/"))
        assertTrue(client.postToolResult("tok", "s_1", "tr_x", true, "x").isFailure)
        server.shutdown()
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.CloudAgentClientTest"`
Expected: FAIL（unresolved reference CloudAgentClient）

- [ ] **Step 3: 实现**

```kotlin
package com.mamba.picme.data.remote.picme

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 云端 Agent SSE 帧（spec §3：M1 event/done/error + M2 新增 tool_request）。 */
sealed interface CloudFrame {
    /** M1 事件帧：event 为 agent_event 行 JSON（id/type/source/role/payload/created_at）。 */
    data class Event(val event: JSONObject) : CloudFrame

    /** 工具桥下行帧（传输层信号，不落事件流）。 */
    data class ToolRequest(
        val requestId: String,
        val tool: String,
        val args: JSONObject,
        val timeoutMs: Long,
    ) : CloudFrame

    data class Done(val sessionStatus: String) : CloudFrame
    data class Error(val message: String) : CloudFrame
}

/** SSE block → CloudFrame 解析。纯逻辑，无法识别一律 null 容错跳过（ClaudeSseParser 先例）。 */
object CloudSseParser {
    fun parseBlock(block: String): CloudFrame? {
        val data = block.lines().firstOrNull { line -> line.startsWith("data:") }
            ?.removePrefix("data:")?.trim() ?: return null
        val json = try {
            JSONObject(data)
        } catch (_: Throwable) {
            return null
        }
        return when (json.optString("kind")) {
            "event" -> json.optJSONObject("event")?.let { event -> CloudFrame.Event(event) }
            "tool_request" -> json.optString("request_id").takeIf { it.isNotBlank() }?.let { rid ->
                CloudFrame.ToolRequest(
                    requestId = rid,
                    tool = json.optString("tool"),
                    args = json.optJSONObject("args") ?: JSONObject(),
                    timeoutMs = json.optLong("timeout_ms", 120_000L),
                )
            }
            "done" -> CloudFrame.Done(json.optString("session_status"))
            "error" -> CloudFrame.Error(json.optString("message"))
            else -> null
        }
    }
}

/** 云端 Agent 客户端抽象（ViewModel 单测 fake 注入点）。 */
interface CloudAgentApi {
    suspend fun createSession(token: String, channel: String, model: String, byokBaseUrl: String): Result<JSONObject>
    suspend fun getEvents(token: String, sessionId: String, after: Long = 0L, limit: Int = 500): Result<List<JSONObject>>
    suspend fun finish(token: String, sessionId: String): Result<Unit>
    suspend fun postMessage(
        token: String,
        sessionId: String,
        content: String,
        byokApiKey: String?,
        onFrame: (CloudFrame) -> Unit,
    ): Result<Unit>
    suspend fun postToolResult(token: String, sessionId: String, requestId: String, ok: Boolean, result: String): Result<Unit>
}

/**
 * 云端 Agent 客户端（ADR-018 决策 3/4，M2）。OkHttp + X-App-Token + org.json（ClaudeChatClient 同款）。
 * BYOK 零留存锚点：[byokApiKey] 只在 [postMessage] 的 X-User-Api-Key 头出现——
 * 不进 URL、不进 body、不进日志；其余方法连参数都没有。
 */
class CloudAgentClient(private val baseUrl: String = DEFAULT_BASE_URL) : CloudAgentApi {

    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接不超时（服务端 120s 工具超时兜底）
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().addHeader("X-Platform", "android").build())
        }
        .build()
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().addHeader("X-Platform", "android").build())
        }
        .build()
    private val jsonMedia = "application/json".toMediaType()

    override suspend fun createSession(token: String, channel: String, model: String, byokBaseUrl: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("channel", channel)
                    .put("model", model)
                    .put("byok_base_url", byokBaseUrl)
                    .toString()
                val text = post("$baseUrl/agent/sessions", token, body)
                JSONObject(text)
            }
        }

    override suspend fun getEvents(token: String, sessionId: String, after: Long, limit: Int): Result<List<JSONObject>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/agent/sessions/$sessionId/events?after=$after&limit=$limit")
                    .header("X-App-Token", token)
                    .get()
                    .build()
                val text = apiClient.newCall(req).execute().use { resp ->
                    val t = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $t")
                    t
                }
                val arr = JSONObject(text).getJSONArray("events")
                (0 until arr.length()).map { idx -> arr.getJSONObject(idx) }
            }
        }

    override suspend fun finish(token: String, sessionId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                post("$baseUrl/agent/sessions/$sessionId/finish", token, "{}")
                Unit
            }
        }

    override suspend fun postMessage(
        token: String,
        sessionId: String,
        content: String,
        byokApiKey: String?,
        onFrame: (CloudFrame) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // 不用 runCatching：协程取消必须穿透（停止按钮语义，ClaudeChatClient.chat 先例）
        try {
            val body = JSONObject().put("content", content).toString()
            val req = Request.Builder()
                .url("$baseUrl/agent/sessions/$sessionId/messages")
                .header("X-App-Token", token)
                .apply { if (!byokApiKey.isNullOrBlank()) header("X-User-Api-Key", byokApiKey) }
                .post(body.toRequestBody(jsonMedia))
                .build()
            val call = sseClient.newCall(req)
            // readTimeout=0 的 SSE 阻塞读不响应协程取消：协程结束时 cancel 打断 source.read()
            currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
                }
                val source = resp.body?.byteStream() ?: throw RuntimeException("empty body")
                val sb = StringBuilder()
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n == -1) break
                    sb.append(String(buf, 0, n))
                    while (true) {
                        val idx = sb.indexOf("\n\n")
                        if (idx == -1) break
                        val block = sb.substring(0, idx)
                        sb.delete(0, idx + 2)
                        CloudSseParser.parseBlock(block)?.let(onFrame)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun postToolResult(token: String, sessionId: String, requestId: String, ok: Boolean, result: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("request_id", requestId)
                    .put("ok", ok)
                    .put("result", result)
                    .toString()
                post("$baseUrl/agent/sessions/$sessionId/tool-results", token, body)
                Unit
            }
        }

    private fun post(url: String, token: String, jsonBody: String): String {
        val req = Request.Builder()
            .url(url)
            .header("X-App-Token", token)
            .post(jsonBody.toRequestBody(jsonMedia))
            .build()
        return apiClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $text")
            text
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.CloudAgentClientTest"`
Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml androidApp/build.gradle.kts androidApp/src/main/java/com/mamba/picme/data/remote/picme/CloudAgentClient.kt androidApp/src/test/java/com/mamba/picme/data/remote/picme/CloudAgentClientTest.kt
git commit -m "feat(android): cloud agent M2 客户端 CloudAgentClient（SSE 四型帧 + BYOK 头仅随 messages）"
```

---

### Task 5: Android — DeviceToolExecutor（工具名 → ChatToolService 映射）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/core/agenttools/DeviceToolExecutor.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/core/agenttools/DeviceToolExecutorTest.kt`

**Interfaces:**
- Consumes: `ChatToolService.getInstance()` 的 `getGallerySummary()/searchMedia(query,person,fromMs,toMs,hasFace)/refineMediaSearch(constraint,fromMs,toMs)`（shared commonMain，全 suspend 全 String 返回）
- Produces（Task 7 依赖）：
  - `class DeviceToolExecutor(gallerySummary, searchMedia, refineSearch 三个函数注入，默认委托 ChatToolService)`
  - `suspend fun execute(tool: String, args: JSONObject): String`——未知工具 `error: unknown_tool:<tool>`；handler 异常 `error: <message>`；结果超 32_000 字符截断 + `\n…[truncated]`
  - `DeviceToolExecutor.MAX_RESULT_CHARS = 32_000`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.core.agenttools

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceToolExecutorTest {

    private fun executor(
        gallery: suspend () -> String = { "相册 1000 张" },
        search: suspend (String, String, String, String, String) -> String = { q, _, _, _, _ -> "搜索[$q] → 3 张" },
        refine: suspend (String, String, String) -> String = { c, _, _ -> "细化[$c] → 1 张" },
    ) = DeviceToolExecutor(gallerySummary = gallery, searchMedia = search, refineSearch = refine)

    @Test
    fun `maps three tool names to handlers`() = runTest {
        val exec = executor()
        assertEquals("相册 1000 张", exec.execute("get_gallery_summary", JSONObject()))
        assertEquals("搜索[猫] → 3 张", exec.execute("search_media", JSONObject().put("query", "猫")))
        assertEquals("细化[夜景] → 1 张", exec.execute("refine_media_search", JSONObject().put("constraint", "夜景")))
    }

    @Test
    fun `unknown tool returns error text`() = runTest {
        assertEquals("error: unknown_tool:delete_media", executor().execute("delete_media", JSONObject()))
    }

    @Test
    fun `handler exception becomes error text`() = runTest {
        val exec = executor(gallery = { throw IllegalStateException("db locked") })
        assertEquals("error: db locked", exec.execute("get_gallery_summary", JSONObject()))
    }

    @Test
    fun `result over limit is truncated`() = runTest {
        val big = "x".repeat(DeviceToolExecutor.MAX_RESULT_CHARS + 500)
        val exec = executor(gallery = { big })
        val out = exec.execute("get_gallery_summary", JSONObject())
        assertTrue(out.length <= DeviceToolExecutor.MAX_RESULT_CHARS + 20)
        assertTrue(out.endsWith("[truncated]"))
    }

    @Test
    fun `search_media passes all five args`() = runTest {
        var captured: List<String>? = null
        val exec = executor(search = { q, p, f, t, h -> captured = listOf(q, p, f, t, h); "ok" })
        exec.execute(
            "search_media",
            JSONObject().put("query", "猫").put("person", "儿子").put("fromMs", "100").put("toMs", "200").put("hasFace", "true"),
        )
        assertEquals(listOf("猫", "儿子", "100", "200", "true"), captured)
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.core.agenttools.DeviceToolExecutorTest"`
Expected: FAIL（unresolved reference DeviceToolExecutor）

- [ ] **Step 3: 实现**

```kotlin
package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 云端 Agent 工具桥的端侧执行器（spec §5）：tool_request 帧的 tool 名 → ChatToolService 只读 handler。
 * 依赖以函数注入（AppToolExecutor 范式），纯 JVM 可测；生产默认委托 ChatToolService 单例。
 *
 * M2 只读三件套；写类工具（delete/share/select）不在映射表——云端会话里它们即未知工具。
 * 结果截断按字符计（32_000，AppToolExecutor 32KB 语义的文本版——字节截断会切坏 UTF-8）。
 */
class DeviceToolExecutor(
    private val gallerySummary: suspend () -> String =
        { ChatToolService.getInstance().getGallerySummary() },
    private val searchMedia: suspend (String, String, String, String, String) -> String =
        { query, person, fromMs, toMs, hasFace ->
            ChatToolService.getInstance().searchMedia(query, person, fromMs, toMs, hasFace)
        },
    private val refineSearch: suspend (String, String, String) -> String =
        { constraint, fromMs, toMs ->
            ChatToolService.getInstance().refineMediaSearch(constraint, fromMs, toMs)
        },
) {

    suspend fun execute(tool: String, args: JSONObject): String {
        val result = try {
            when (tool) {
                "get_gallery_summary" -> gallerySummary()
                "search_media" -> searchMedia(
                    args.optString("query"),
                    args.optString("person"),
                    args.optString("fromMs"),
                    args.optString("toMs"),
                    args.optString("hasFace"),
                )
                "refine_media_search" -> refineSearch(
                    args.optString("constraint"),
                    args.optString("fromMs"),
                    args.optString("toMs"),
                )
                else -> return "error: unknown_tool:$tool"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return "error: ${e.message ?: e.javaClass.simpleName}"
        }
        return truncate(result)
    }

    private fun truncate(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text
        else text.take(MAX_RESULT_CHARS) + "\n…[truncated]"

    companion object {
        const val MAX_RESULT_CHARS = 32_000
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.core.agenttools.DeviceToolExecutorTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/core/agenttools/DeviceToolExecutor.kt androidApp/src/test/java/com/mamba/picme/core/agenttools/DeviceToolExecutorTest.kt
git commit -m "feat(android): cloud agent M2 端侧工具执行器（只读三件套映射 + 32K 截断）"
```

---

### Task 6: Android — BYOK 授权开关（DataStore + 设置页 + 五语）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt`（接口加两个成员）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt`（实现 + PreferencesKeys）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt`（StateFlow + setter）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`（REMOTE_MODEL 分支加开关行）
- Modify: `androidApp/src/main/res/values/strings.xml` + `values-zh-rCN` + `values-zh-rTW` + `values-es` + `values-fr`
- Test: `androidApp/src/test/java/com/mamba/picme/features/settings/CloudAgentByokConsentTest.kt`（纯逻辑：通道选择规则表）

**Interfaces:**
- Consumes: DataStore `context.dataStore`、`SettingsViewModel` 既有 boolean flow 先例（`debugUiEnabledFlow` / `setDebugUiEnabled`）、`DebugOptionRow`（SettingsBaseComponents.kt:240，internal，同包可用）
- Produces（Task 7 依赖）：
  - `UserSettingsRepository.cloudAgentByokConsentFlow: Flow<Boolean>`（默认 false）
  - `UserSettingsRepository.updateCloudAgentByokConsent(enabled: Boolean)`
  - `CloudAgentChannelResolver`（纯函数对象，本任务新建于 features/cloudagent/）：`fun resolve(config: RemoteModelConfig?, consent: Boolean): CloudAgentChannelChoice`
  - `sealed interface CloudAgentChannelChoice`：`Gateway` / `data class Byok(model, baseUrl, apiKey)` / `NeedConsent`

- [ ] **Step 1: 写失败测试（通道选择规则 = spec §6 表）**

```kotlin
package com.mamba.picme.features.cloudagent

import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAgentByokConsentTest {

    private val byokConfig = RemoteModelConfig(
        modelId = "kimi-k2.6",
        providerId = "kimi-official",
        apiKey = "sk-user-key",
        baseUrl = "https://api.moonshot.cn/v1/",
    )

    @Test
    fun `no user config selects gateway regardless of consent`() {
        assertEquals(CloudAgentChannelChoice.Gateway, CloudAgentChannelResolver.resolve(null, consent = false))
        assertEquals(CloudAgentChannelChoice.Gateway, CloudAgentChannelResolver.resolve(null, consent = true))
    }

    @Test
    fun `config without apiKey selects gateway`() {
        val noKey = byokConfig.copy(apiKey = "")
        assertEquals(CloudAgentChannelChoice.Gateway, CloudAgentChannelResolver.resolve(noKey, consent = false))
    }

    @Test
    fun `byok config with consent selects byok`() {
        val choice = CloudAgentChannelResolver.resolve(byokConfig, consent = true)
        assertTrue(choice is CloudAgentChannelChoice.Byok)
        val byok = choice as CloudAgentChannelChoice.Byok
        assertEquals("kimi-k2.6", byok.model)
        assertEquals("https://api.moonshot.cn/v1/", byok.baseUrl)
        assertEquals("sk-user-key", byok.apiKey)
    }

    @Test
    fun `byok config without consent requires consent dialog`() {
        assertEquals(CloudAgentChannelChoice.NeedConsent, CloudAgentChannelResolver.resolve(byokConfig, consent = false))
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.features.cloudagent.CloudAgentByokConsentTest"`
Expected: FAIL（unresolved reference CloudAgentChannelResolver）

- [ ] **Step 3: 实现**

3a. 新建 `androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentChannelResolver.kt`：

```kotlin
package com.mamba.picme.features.cloudagent

import com.mamba.picme.agent.core.remote.config.RemoteModelConfig

/** 云端会话通道选择（spec §6 表，建会话时一次定型，中途换供应商不影响进行中会话）。 */
sealed interface CloudAgentChannelChoice {
    /** 网关通道（M1 配额语义）。 */
    data object Gateway : CloudAgentChannelChoice

    /** BYOK 透传（model/baseUrl 建会话固化，apiKey 仅随 postMessage 头发送）。 */
    data class Byok(val model: String, val baseUrl: String, val apiKey: String) : CloudAgentChannelChoice

    /** BYOK 供应商但未授权 → 弹窗二选一（去授权 / 改用网关），不静默回退。 */
    data object NeedConsent : CloudAgentChannelChoice
}

object CloudAgentChannelResolver {
    /**
     * @param config 当前选中远程模型配置（null 或无 apiKey = 网关兜底，对齐 AiAgentUseCase 语义）
     * @param consent DataStore `cloud_agent_byok_consent`
     */
    fun resolve(config: RemoteModelConfig?, consent: Boolean): CloudAgentChannelChoice = when {
        config == null || config.apiKey.isBlank() -> CloudAgentChannelChoice.Gateway
        consent -> CloudAgentChannelChoice.Byok(config.modelId, config.baseUrl, config.apiKey)
        else -> CloudAgentChannelChoice.NeedConsent
    }
}
```

3b. `UserSettingsRepository.kt`（飞书配置区下方加）：

```kotlin
// ── 云端 Agent BYOK 授权（ADR-018 决策 4） ──────────────────
/** 允许云端 Agent 使用我的 API Key（默认关；开=key 随请求透传服务器内存处理，零留存）。 */
val cloudAgentByokConsentFlow: Flow<Boolean>
suspend fun updateCloudAgentByokConsent(enabled: Boolean)
```

3c. `UserPreferencesRepository.kt`：PreferencesKeys 对象加 `val CLOUD_AGENT_BYOK_CONSENT = booleanPreferencesKey("cloud_agent_byok_consent")`；实现照抄 `mediaManageSilentTrashFlow` 模式（catch IOException → emptyPreferences → map 取 `?: false` + edit 写入）。

3d. `SettingsViewModel.kt`：

```kotlin
val cloudAgentByokConsent: StateFlow<Boolean> = repository.cloudAgentByokConsentFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

fun setCloudAgentByokConsent(enabled: Boolean) {
    viewModelScope.launch { repository.updateCloudAgentByokConsent(enabled) }
}
```

（stateIn/SharingStarted import 若已有复用既有；无则按同文件其他 flow 先例补。）

3e. `SettingsScreen.kt` REMOTE_MODEL 分支（`RemoteModelsListSection(...)` 调用后、AssistantPersona 区块前）加：

```kotlin
Spacer(modifier = Modifier.height(SettingsTokens.listSectionSpacing))
SettingsListSection {
    DebugOptionRow(
        title = stringResource(R.string.cloud_agent_byok_consent_title),
        subtitle = stringResource(R.string.cloud_agent_byok_consent_subtitle),
        checked = cloudAgentByokConsent,
        onCheckedChange = onCloudAgentByokConsentChange,
        icon = Icons.Rounded.Cloud,
        iconBlockColor = AppColors.vibrantBlue
    )
}
```

配套：外层 composable 链加参数 `cloudAgentByokConsent: Boolean` + `onCloudAgentByokConsentChange: (Boolean) -> Unit`（沿 `debugUiEnabled`/`onDebugUiEnabledChange` 既有传参链逐层补，顶层 collect `viewModel.cloudAgentByokConsent`）。

3f. 五语 strings（每个语言文件各加两条；key 与文案）：

| key | EN | zh-CN | zh-TW | ES | FR |
|-----|----|----|----|----|----|
| cloud_agent_byok_consent_title | Allow cloud agent to use my API key | 允许云端 Agent 使用我的 API Key | 允許雲端 Agent 使用我的 API Key | Permitir que el agente en la nube use mi clave API | Autoriser l'agent cloud à utiliser ma clé API |
| cloud_agent_byok_consent_subtitle | Key is sent per request and processed in server memory only — never stored or logged | Key 仅在会话期间随请求发送至服务器内存处理，服务器不保存、不写日志 | Key 僅在會話期間隨請求傳送至伺服器記憶體處理，伺服器不儲存、不寫日誌 | La clave se envía con cada solicitud y se procesa solo en memoria del servidor: nunca se guarda ni se registra | La clé est envoyée avec chaque requête et traitée uniquement en mémoire serveur — jamais stockée ni journalisée |

- [ ] **Step 4: 跑测试确认绿 + 编译**

Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.features.cloudagent.CloudAgentByokConsentTest"` → 4 PASS
Run: `JITPACK=true ./gradlew :androidApp:compileDebugKotlin` → BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentChannelResolver.kt androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt androidApp/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt androidApp/src/main/res/values/strings.xml androidApp/src/main/res/values-zh-rCN/strings.xml androidApp/src/main/res/values-zh-rTW/strings.xml androidApp/src/main/res/values-es/strings.xml androidApp/src/main/res/values-fr/strings.xml androidApp/src/test/java/com/mamba/picme/features/cloudagent/CloudAgentByokConsentTest.kt
git commit -m "feat(android): cloud agent M2 BYOK 授权开关（DataStore 默认关 + 通道选择规则 + 五语）"
```

---

### Task 7: Android — CloudAgentChatViewModel（状态机 + 桥消费）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentChatViewModel.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/cloudagent/CloudAgentChatViewModelTest.kt`

**Interfaces:**
- Consumes: `CloudAgentApi`/`CloudFrame`（Task 4）、`DeviceToolExecutor`（Task 5）、`CloudAgentChannelResolver`/`CloudAgentChannelChoice`（Task 6）、`UserSettingsRepository`（serverAuthTokenFlow / aiAgentRemoteModelConfigsFlow / aiAgentSelectedRemoteModelFlow / cloudAgentByokConsentFlow）、`RemoteModelConfigs.fromJson`（shared）
- Produces（Task 8 依赖）：
  - `class CloudAgentChatViewModel(api: CloudAgentApi, deviceToolExecutor: DeviceToolExecutor, settingsRepository: UserSettingsRepository) : ViewModel`
  - `data class UiMessage(id: Long, role: Role, text: String)`，`enum class Role { USER, ASSISTANT, TOOL, ERROR }`
  - `sealed interface UiState`：`Preparing` / `NeedByokConsent` / `data class Failed(reason: String)` / `data class Chat(sessionId, channel, messages: List<UiMessage>, inFlight: Boolean)`
  - `val state: StateFlow<UiState>`；`fun prepare()` / `fun useGatewayInstead()` / `fun send(text: String)` / `fun stop()`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.features.cloudagent

import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.data.remote.picme.CloudFrame
import com.mamba.picme.domain.repository.UserSettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudAgentChatViewModelTest {

    private val tokenFlow = MutableStateFlow("pl-token")
    private val configsFlow = MutableStateFlow("")
    private val selectedFlow = MutableStateFlow("")
    private val consentFlow = MutableStateFlow(false)
    private val repository: UserSettingsRepository = mockk {
        every { serverAuthTokenFlow } answers { tokenFlow }
        every { aiAgentRemoteModelConfigsFlow } answers { configsFlow }
        every { aiAgentSelectedRemoteModelFlow } answers { selectedFlow }
        every { cloudAgentByokConsentFlow } answers { consentFlow }
    }

    /** 假客户端：记录调用，frames 脚本化。 */
    private class FakeApi : CloudAgentApi {
        var createdChannel: String? = null
        var createdModel: String? = null
        var createdBaseUrl: String? = null
        var sentByokKey: String? = null
        val toolResults = mutableListOf<Triple<String, Boolean, String>>()
        var framesScript: suspend (CloudAgentChatViewModelTest.FakeApi, (CloudFrame) -> Unit) -> Unit =
            { _, onFrame -> onFrame(CloudFrame.Done("running")) }

        override suspend fun createSession(token: String, channel: String, model: String, byokBaseUrl: String): Result<JSONObject> {
            createdChannel = channel; createdModel = model; createdBaseUrl = byokBaseUrl
            return Result.success(JSONObject().put("session_id", "s_1"))
        }
        override suspend fun getEvents(token: String, sessionId: String, after: Long, limit: Int) = Result.success(emptyList<JSONObject>())
        override suspend fun finish(token: String, sessionId: String) = Result.success(Unit)
        override suspend fun postMessage(token: String, sessionId: String, content: String, byokApiKey: String?, onFrame: (CloudFrame) -> Unit): Result<Unit> {
            sentByokKey = byokApiKey
            framesScript(this, onFrame)
            return Result.success(Unit)
        }
        override suspend fun postToolResult(token: String, sessionId: String, requestId: String, ok: Boolean, result: String): Result<Unit> {
            toolResults.add(Triple(requestId, ok, result))
            return Result.success(Unit)
        }
    }

    @Before
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun newVm(api: FakeApi): CloudAgentChatViewModel =
        CloudAgentChatViewModel(api, DeviceToolExecutor(gallerySummary = { "相册 42 张" }), repository)

    @Test
    fun `prepare without user config enters gateway chat`() = runTest {
        val api = FakeApi()
        val vm = newVm(api)
        vm.prepare()
        val s = vm.state.value
        assertTrue(s is CloudAgentChatViewModel.UiState.Chat)
        assertEquals("gateway", (s as CloudAgentChatViewModel.UiState.Chat).channel)
        assertEquals("gateway", api.createdChannel)
    }

    @Test
    fun `prepare byok without consent stops at NeedByokConsent and sends nothing`() = runTest {
        configsFlow.value = """[{"modelId":"kimi-k2.6","providerId":"kimi-official","protocol":"OPENAI","apiKey":"sk-xyz","baseUrl":"https://api.moonshot.cn/v1/","gatewayToken":""}]"""
        selectedFlow.value = "kimi-official:kimi-k2.6"
        val api = FakeApi()
        val vm = newVm(api)
        vm.prepare()
        assertTrue(vm.state.value is CloudAgentChatViewModel.UiState.NeedByokConsent)
        assertEquals(null, api.createdChannel) // 未授权不建会话
    }

    @Test
    fun `prepare byok with consent enters byok chat`() = runTest {
        configsFlow.value = """[{"modelId":"kimi-k2.6","providerId":"kimi-official","protocol":"OPENAI","apiKey":"sk-xyz","baseUrl":"https://api.moonshot.cn/v1/","gatewayToken":""}]"""
        selectedFlow.value = "kimi-official:kimi-k2.6"
        consentFlow.value = true
        val api = FakeApi()
        val vm = newVm(api)
        vm.prepare()
        val s = vm.state.value as CloudAgentChatViewModel.UiState.Chat
        assertEquals("byok", s.channel)
        assertEquals("byok", api.createdChannel)
        assertEquals("kimi-k2.6", api.createdModel)
        assertEquals("https://api.moonshot.cn/v1/", api.createdBaseUrl)
    }

    @Test
    fun `NeedByokConsent useGatewayInstead enters gateway chat`() = runTest {
        configsFlow.value = """[{"modelId":"kimi-k2.6","providerId":"kimi-official","protocol":"OPENAI","apiKey":"sk-xyz","baseUrl":"https://api.moonshot.cn/v1/","gatewayToken":""}]"""
        selectedFlow.value = "kimi-official:kimi-k2.6"
        val api = FakeApi()
        val vm = newVm(api)
        vm.prepare()
        vm.useGatewayInstead()
        val s = vm.state.value as CloudAgentChatViewModel.UiState.Chat
        assertEquals("gateway", s.channel)
    }

    @Test
    fun `send consumes frames and executes tool request then posts result`() = runTest {
        val api = FakeApi()
        api.framesScript = { _, onFrame ->
            onFrame(CloudFrame.ToolRequest("tr_1", "get_gallery_summary", JSONObject(), 120_000L))
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":3,"type":"message","source":"agent","role":"assistant","payload":{"type":"message","content":"你有 42 张照片"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val vm = newVm(api)
        vm.prepare()
        vm.send("相册多少张")
        val s = vm.state.value as CloudAgentChatViewModel.UiState.Chat
        assertEquals(false, s.inFlight)
        // user + tool 活动行 + assistant
        assertEquals(3, s.messages.size)
        assertEquals(CloudAgentChatViewModel.Role.USER, s.messages[0].role)
        assertEquals(CloudAgentChatViewModel.Role.TOOL, s.messages[1].role)
        assertEquals("你有 42 张照片", s.messages[2].text)
        assertEquals(listOf(Triple("tr_1", true, "相册 42 张")), api.toolResults)
    }

    @Test
    fun `error frame appends error message`() = runTest {
        val api = FakeApi()
        api.framesScript = { _, onFrame -> onFrame(CloudFrame.Error("quota exhausted")) }
        val vm = newVm(api)
        vm.prepare()
        vm.send("hi")
        val s = vm.state.value as CloudAgentChatViewModel.UiState.Chat
        assertEquals(CloudAgentChatViewModel.Role.ERROR, s.messages.last().role)
        assertEquals("quota exhausted", s.messages.last().text)
    }

    @Test
    fun `not logged in fails prepare`() = runTest {
        tokenFlow.value = ""
        val vm = newVm(FakeApi())
        vm.prepare()
        assertTrue(vm.state.value is CloudAgentChatViewModel.UiState.Failed)
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.features.cloudagent.CloudAgentChatViewModelTest"`
Expected: FAIL（unresolved reference CloudAgentChatViewModel）

- [ ] **Step 3: 实现**

```kotlin
package com.mamba.picme.features.cloudagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.data.remote.picme.CloudFrame
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 云端 Agent 最小聊天页状态机（spec §5，调试入口实验页；内存态消息列表，不落 Room——镜像属 M3）。
 *
 * 生命周期：prepare()（通道解析 + 建会话，幂等）→ Chat 态 send()/stop() 循环。
 * 一轮只开一条 SSE：tool_request 帧到达即 launch 端执行 + 上行（不阻塞收帧循环）。
 * BYOK key 仅存本类私有字段（内存态），随 postMessage 头发送；绝不进日志（Logger 调用不含 key）。
 */
class CloudAgentChatViewModel(
    private val api: CloudAgentApi,
    private val deviceToolExecutor: DeviceToolExecutor,
    private val settingsRepository: UserSettingsRepository,
) : ViewModel() {

    data class UiMessage(val id: Long, val role: Role, val text: String)
    enum class Role { USER, ASSISTANT, TOOL, ERROR }

    sealed interface UiState {
        data object Preparing : UiState
        data object NeedByokConsent : UiState
        data class Failed(val reason: String) : UiState
        data class Chat(
            val sessionId: String,
            val channel: String, // "gateway" | "byok"
            val messages: List<UiMessage>,
            val inFlight: Boolean,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Preparing)
    val state: StateFlow<UiState> = _state

    private var appToken: String = ""
    private var byokApiKey: String = ""
    private var turnJob: Job? = null
    private var msgSeq = 0L

    /** 入口：通道解析（spec §6 表）→ 建会话。幂等（非 Preparing 直接返回）。 */
    fun prepare() {
        if (_state.value !is UiState.Preparing) return
        viewModelScope.launch {
            val token = settingsRepository.serverAuthTokenFlow.first()
            if (token.isBlank()) {
                _state.value = UiState.Failed("not_logged_in")
                return@launch
            }
            appToken = token
            val configsJson = settingsRepository.aiAgentRemoteModelConfigsFlow.first()
            val selectedKey = settingsRepository.aiAgentSelectedRemoteModelFlow.first()
            val config = if (configsJson.isBlank()) {
                null
            } else {
                val configs = RemoteModelConfigs.fromJson(configsJson)
                configs.configs.find { c -> c.uniqueKey == selectedKey }
                    ?: configs.getConfigByModelId(selectedKey)
            }
            val consent = settingsRepository.cloudAgentByokConsentFlow.first()
            when (val choice = CloudAgentChannelResolver.resolve(config, consent)) {
                CloudAgentChannelChoice.Gateway -> createAndEnter("gateway", model = "", byokBaseUrl = "", key = "")
                is CloudAgentChannelChoice.Byok ->
                    createAndEnter("byok", choice.model, choice.baseUrl, choice.apiKey)
                CloudAgentChannelChoice.NeedConsent -> _state.value = UiState.NeedByokConsent
            }
        }
    }

    /** 授权弹窗 [使用网关]：放弃 BYOK 改走网关通道。 */
    fun useGatewayInstead() {
        if (_state.value !is UiState.NeedByokConsent) return
        viewModelScope.launch { createAndEnter("gateway", model = "", byokBaseUrl = "", key = "") }
    }

    private suspend fun createAndEnter(channel: String, model: String, byokBaseUrl: String, key: String) {
        api.createSession(appToken, channel, model, byokBaseUrl)
            .onSuccess { json ->
                byokApiKey = key
                _state.value = UiState.Chat(
                    sessionId = json.optString("session_id"),
                    channel = channel,
                    messages = emptyList(),
                    inFlight = false,
                )
            }
            .onFailure { e ->
                _state.value = UiState.Failed(e.message ?: "create_session_failed")
            }
    }

    fun send(text: String) {
        val chat = _state.value as? UiState.Chat ?: return
        if (chat.inFlight || text.isBlank()) return
        turnJob = viewModelScope.launch {
            append(UiMessage(++msgSeq, Role.USER, text))
            setInFlight(true)
            val result = api.postMessage(
                appToken,
                chat.sessionId,
                text,
                byokApiKey.takeIf { it.isNotBlank() },
            ) { frame -> onFrame(frame) }
            result.onFailure { e ->
                append(UiMessage(++msgSeq, Role.ERROR, e.message ?: "network error"))
                setInFlight(false)
            }
        }
    }

    /** 停止当前轮（取消穿透断流，ClaudeChatClient 先例）。 */
    fun stop() {
        turnJob?.cancel()
        setInFlight(false)
    }

    private fun onFrame(frame: CloudFrame) {
        val chat = _state.value as? UiState.Chat ?: return
        when (frame) {
            is CloudFrame.Event -> handleEvent(frame.event)
            is CloudFrame.ToolRequest -> {
                append(UiMessage(++msgSeq, Role.TOOL, frame.tool))
                // 端执行不阻塞收帧循环
                viewModelScope.launch {
                    val result = deviceToolExecutor.execute(frame.tool, frame.args)
                    api.postToolResult(
                        appToken,
                        chat.sessionId,
                        frame.requestId,
                        ok = !result.startsWith("error:"),
                        result = result,
                    ).onFailure { e -> Logger.w(TAG, "postToolResult failed: ${e.message}") }
                }
            }
            is CloudFrame.Done -> setInFlight(false)
            is CloudFrame.Error -> {
                append(UiMessage(++msgSeq, Role.ERROR, frame.message))
                setInFlight(false)
            }
        }
    }

    private fun handleEvent(event: JSONObject) {
        val payload = event.optJSONObject("payload") ?: return
        when (event.optString("type")) {
            // 助手答复（user 回声跳过——本地已先上屏）
            "message" -> if (event.optString("role") == "assistant") {
                append(UiMessage(++msgSeq, Role.ASSISTANT, payload.optString("content")))
            }
            else -> Unit
            else -> Unit // action/observation/finish 不上屏（TOOL 行由 tool_request 帧驱动）
        }
    }

    private fun append(message: UiMessage) {
        val chat = _state.value as? UiState.Chat ?: return
        _state.value = chat.copy(messages = chat.messages + message)
    }

    private fun setInFlight(inFlight: Boolean) {
        val chat = _state.value as? UiState.Chat ?: return
        _state.value = chat.copy(inFlight = inFlight)
    }

    companion object {
        private const val TAG = "CloudAgent"
    }
}
```

（`Logger.w(tag, msg)` 签名以 `core/common/Logger` 实际为准；若模块 tag 约定为自动前缀则传 "CloudAgent" 即可，最终日志形态 `PoLang:CloudAgent`。）

- [ ] **Step 4: 跑测试确认绿**

Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.features.cloudagent.CloudAgentChatViewModelTest"`
Expected: 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentChatViewModel.kt androidApp/src/test/java/com/mamba/picme/features/cloudagent/CloudAgentChatViewModelTest.kt
git commit -m "feat(android): cloud agent M2 聊天页状态机（通道门 + SSE 消费 + 工具桥回传）"
```

---

### Task 8: Android — CloudAgentChatScreen + 路由 + 调试入口

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentChatScreen.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/navigation/Screen.kt`（加 `data object CloudAgentChat : Screen("cloud_agent_chat")`，放 `Debug` 旁）
- Modify: `androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt`（加 `createCloudAgentChatViewModelFactory()`，照 `createTaskCenterViewModelFactory` 先例）
- Modify: `androidApp/src/main/java/com/mamba/picme/MainActivity.kt`（NavHost 注册 + SettingsScreen 回调接线）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`（DEVELOPER 组3 加入口行 + 回调传参链）
- Modify: 五个 strings.xml（入口/页面文案）
- Test: 无新增（UI 层；Task 7 已覆盖状态机。编译 + 真机遛狗验收）

**Interfaces:**
- Consumes: `CloudAgentChatViewModel`（Task 7 全部公开成员）、`AgentMarkdown(content, color, fontSize, lineHeight, modifier)`（features/common/chat）、`SettingsListRow`（SettingsScreen 同文件内私有可用）
- Produces: `Screen.CloudAgentChat.route = "cloud_agent_chat"`；设置 → 开发者选项 → 组3 测试工具（DEBUG 构建）「云端 Agent（实验）」入口

- [ ] **Step 1: 实现 Screen**

```kotlin
package com.mamba.picme.features.cloudagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.features.common.chat.AgentMarkdown

/**
 * 云端 Agent 最小聊天页（spec §5，调试入口实验页——豁免 PARITY 固化，转正时再入 08-UI-SPECS）。
 * 顶栏通道标记（网关/BYOK）+ 消息气泡列表 + 输入框 + 发送/停止。
 */
@Composable
fun CloudAgentChatScreen(
    viewModel: CloudAgentChatViewModel,
    onNavigateBack: () -> Unit,
    onGoRemoteModelSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.prepare() }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：返回 + 标题 + 通道胶囊
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.cloud_agent_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            (state as? CloudAgentChatViewModel.UiState.Chat)?.let { chat ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = stringResource(
                            if (chat.channel == "byok") R.string.cloud_agent_channel_byok
                            else R.string.cloud_agent_channel_gateway,
                        ),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        when (val s = state) {
            CloudAgentChatViewModel.UiState.Preparing ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            CloudAgentChatViewModel.UiState.NeedByokConsent ->
                AlertDialog(
                    onDismissRequest = onNavigateBack,
                    title = { Text(stringResource(R.string.cloud_agent_byok_dialog_title)) },
                    text = { Text(stringResource(R.string.cloud_agent_byok_dialog_message)) },
                    confirmButton = {
                        TextButton(onClick = onGoRemoteModelSettings) {
                            Text(stringResource(R.string.cloud_agent_byok_dialog_go_settings))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.useGatewayInstead() }) {
                            Text(stringResource(R.string.cloud_agent_byok_dialog_use_gateway))
                        }
                    },
                )
            is CloudAgentChatViewModel.UiState.Failed ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (s.reason == "not_logged_in") stringResource(R.string.cloud_agent_not_logged_in)
                        else s.reason,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            is CloudAgentChatViewModel.UiState.Chat -> ChatContent(s, viewModel)
        }
    }
}

@Composable
private fun ChatContent(chat: CloudAgentChatViewModel.UiState.Chat, viewModel: CloudAgentChatViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.size - 1)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chat.messages, key = { message -> message.id }) { message ->
                when (message.role) {
                    CloudAgentChatViewModel.Role.USER -> Bubble(text = message.text, isUser = true)
                    CloudAgentChatViewModel.Role.ASSISTANT -> Bubble(text = message.text, isUser = false)
                    CloudAgentChatViewModel.Role.TOOL -> Text(
                        text = stringResource(R.string.cloud_agent_tool_call, message.text),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CloudAgentChatViewModel.Role.ERROR -> Text(
                        text = message.text,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (chat.inFlight) {
                item {
                    Text(
                        text = stringResource(R.string.cloud_agent_thinking),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        var input by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { value -> input = value },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.cloud_agent_input_hint)) },
                maxLines = 3,
            )
            if (chat.inFlight) {
                IconButton(onClick = { viewModel.stop() }) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.cloud_agent_stop))
                }
            } else {
                IconButton(
                    onClick = {
                        viewModel.send(input.trim())
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.cloud_agent_send))
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, isUser: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (isUser) {
                    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    AgentMarkdown(
                        content = text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: 路由 + DI + 入口接线**

2a. `Screen.kt`（`data object Debug : Screen("debug")` 旁）：

```kotlin
data object CloudAgentChat : Screen("cloud_agent_chat")
```

2b. `AppContainer.kt`（照 `createTaskCenterViewModelFactory` 先例）：

```kotlin
fun createCloudAgentChatViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        CloudAgentChatViewModel(
            api = CloudAgentClient(),
            deviceToolExecutor = DeviceToolExecutor(),
            settingsRepository = userPreferencesRepository,
        )
    }
}
```

（import：`androidx.lifecycle.viewmodel.initializer` / `androidx.lifecycle.viewmodel.viewModelFactory` 若该文件已用同款则复用；`userPreferencesRepository` 为该容器内既有字段名，若实际名不同以 `createTaskCenterViewModelFactory` 内引用为准。）

2c. `MainActivity.kt`（`composable(Screen.Debug.route) { ... }` 旁注册）：

```kotlin
composable(Screen.CloudAgentChat.route) {
    val cloudAgentViewModel: CloudAgentChatViewModel = viewModel(
        factory = app.container.createCloudAgentChatViewModelFactory()
    )
    CloudAgentChatScreen(
        viewModel = cloudAgentViewModel,
        onNavigateBack = { navController.popBackStack() },
        onGoRemoteModelSettings = {
            navController.navigate(Screen.SettingsCategory.createRoute(SettingsCategory.REMOTE_MODEL.name.lowercase()))
        },
    )
}
```

（`SettingsCategory` 已在本文件 import 体系内——设置二级页跳转先例；若无则 `import com.mamba.picme.features.settings.SettingsCategory`，以编译器提示为准。）

2d. `SettingsScreen.kt`：
- 参数链加 `onNavigateToCloudAgentChat: () -> Unit = {}`（沿 `onNavigateToDebug` 先例：顶层签名 line 184 区域 → 中间层 → DEVELOPER 分支内容函数）。
- DEVELOPER 组3 测试工具块（`jsbridge_entry_title` 行后）加：

```kotlin
SettingsListDivider()
SettingsListRow(
    title = stringResource(R.string.cloud_agent_entry_title),
    onClick = onNavigateToCloudAgentChat,
    icon = Icons.Rounded.Cloud,
    iconBlockColor = StatusColor.warningAmber
)
```

- `MainActivity.kt` 中 `SettingsScreen(...)` 调用点（`onNavigateToDebug = { navController.navigate(Screen.Debug.route, ...) }` 旁）补 `onNavigateToCloudAgentChat = { navController.navigate(Screen.CloudAgentChat.route, navOptions { launchSingleTop = true }) }`。

2e. 五语 strings（每个语言文件各加 10 条）：

| key | EN | zh-CN | zh-TW | ES | FR |
|-----|----|----|----|----|----|
| cloud_agent_entry_title | Cloud Agent (experimental) | 云端 Agent（实验） | 雲端 Agent（實驗） | Agente en la nube (experimental) | Agent cloud (expérimental) |
| cloud_agent_title | Cloud Agent | 云端 Agent | 雲端 Agent | Agente en la nube | Agent cloud |
| cloud_agent_channel_gateway | Gateway | 网关通道 | 閘道通道 | Puerta de enlace | Passerelle |
| cloud_agent_channel_byok | BYOK | BYOK 通道 | BYOK 通道 | BYOK | BYOK |
| cloud_agent_byok_dialog_title | Authorization required | 云端 Agent 需要授权 | 雲端 Agent 需要授權 | Autorización requerida | Autorisation requise |
| cloud_agent_byok_dialog_message | Cloud agent needs to pass your API key through the server (never stored), or use the gateway channel instead. | 云端 Agent 需要授权 Key 透传（服务器不保存），或改用网关通道。 | 雲端 Agent 需要授權 Key 透傳（伺服器不儲存），或改用閘道通道。 | El agente necesita transmitir tu clave API por el servidor (nunca se guarda), o usar el canal de puerta de enlace. | L'agent doit transmettre votre clé API via le serveur (jamais stockée), ou utiliser le canal passerelle. |
| cloud_agent_byok_dialog_go_settings | Go to settings | 去设置 | 去設定 | Ir a ajustes | Ouvrir les réglages |
| cloud_agent_byok_dialog_use_gateway | Use gateway | 使用网关 | 使用閘道 | Usar puerta de enlace | Utiliser la passerelle |
| cloud_agent_not_logged_in | Sign in first to use cloud agent | 请先登录后再使用云端 Agent | 請先登入後再使用雲端 Agent | Inicia sesión para usar el agente en la nube | Connectez-vous pour utiliser l'agent cloud |
| cloud_agent_input_hint | Message… | 发送消息… | 傳送訊息… | Mensaje… | Message… |
| cloud_agent_thinking | Thinking… | 思考中… | 思考中… | Pensando… | Réflexion… |
| cloud_agent_tool_call | Calling %1$s… | 正在调用 %1$s… | 正在調用 %1$s… | Llamando a %1$s… | Appel de %1$s… |
| cloud_agent_stop | Stop | 停止 | 停止 | Detener | Arrêter |
| cloud_agent_send | Send | 发送 | 傳送 | Enviar | Envoyer |

- [ ] **Step 3: 编译 + 全量单测**

Run: `JITPACK=true ./gradlew :androidApp:compileDebugKotlin` → BUILD SUCCESSFUL
Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest` → 全绿（含既有回归）

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentChatScreen.kt androidApp/src/main/java/com/mamba/picme/navigation/Screen.kt androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt androidApp/src/main/java/com/mamba/picme/MainActivity.kt androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt androidApp/src/main/res/values/strings.xml androidApp/src/main/res/values-zh-rCN/strings.xml androidApp/src/main/res/values-zh-rTW/strings.xml androidApp/src/main/res/values-es/strings.xml androidApp/src/main/res/values-fr/strings.xml
git commit -m "feat(android): cloud agent M2 最小聊天页 + 调试入口（开发者选项 DEBUG 组）"
```

---

### Task 9: 文档同步 + 全量验证（AC-M2-DOC）

**Files:**
- Modify: `server/AGENTS.md`（§2 agent/ 包加两文件行 + §3 路由表加 tool-results 行 + §1 核心职责 agent 行补工具桥）
- Modify: `androidApp/AGENTS.md`（§2.1 features 表加 CloudAgent 行 + §3.2 集成点表加云端 Agent 行）
- Modify: `docs/02-ARCHITECTURE/ADR/ADR-018-cloud-agent-session-state.md`（§6 状态表 M2 行标 ✅ + 日期）

- [ ] **Step 1: server/AGENTS.md 三处**

- §2 agent/ 包块加：
```
│   │   ├── AgentBridgeRegistry.kt # 工具桥挂起表（requestId→Deferred，断线 failAll 清理）
│   │   ├── DeviceToolService.kt   # 端侧只读三件套 Koog 适配（tool_request 帧 + 120s 挂起）
```
- §3 路由表 `/agent/sessions/{id}/messages` 行下方加：
```
| POST | `/agent/sessions/{id}/tool-results` | P1 | ✅ | X-App-Token | 端侧工具结果上行（409 tool_request_not_pending；ADR-018 工具桥） |
```
- §1 核心职责「云端浏览器网关」行下方加一行：云端 Agent 会话域简述补「M2 工具桥：SSE tool_request 下行 + tool-results 上行双通道」。

- [ ] **Step 2: androidApp/AGENTS.md 两处**

- §2.1 features 表加：
```
| **CloudAgent** | `features/cloudagent/` | `CloudAgentChatScreen`, `CloudAgentChatViewModel`, `CloudAgentChannelResolver` | 云端 Agent 实验页（ADR-018 M2，调试入口，豁免 PARITY） |
```
- §3.2 集成点表加：
```
| 云端 Agent 工具桥 | `CloudAgentClient`（`data/remote/picme/`）→ picme-server `/agent/**`；tool_request 帧 → `DeviceToolExecutor`（`core/agenttools/`）→ ChatToolService 只读三件套 | SSE 下行 + POST 上行双通道；BYOK key 仅随 postMessage 头透传零留存；授权开关 DataStore `cloud_agent_byok_consent` 默认关 |
```

- [ ] **Step 3: ADR-018 §6 状态表 M2 行**

「端侧工具桥 + BYOK 授权 UI」行状态改 `✅ 完成 2026-10-XX`（以实际提交日为准），附注「+ 最小聊天页（调试入口实验页）」。

- [ ] **Step 4: 全量验证**

Run: `./gradlew -p server clean build` → BUILD SUCCESSFUL，全测试绿（M1 299 + M2 新增 9 server）
Run: `JITPACK=true ./gradlew :androidApp:assembleDebug` → BUILD SUCCESSFUL
Run: `JITPACK=true ./gradlew :androidApp:testDebugUnitTest` → 全绿
Run: `./scripts/check_doc_sync.py`（或 ai-gate 等效项）→ 活文档引用合规（本 spec/plan 已在 README §6 白名单内）

- [ ] **Step 5: Commit**

```bash
git add server/AGENTS.md androidApp/AGENTS.md docs/02-ARCHITECTURE/ADR/ADR-018-cloud-agent-session-state.md
git commit -m "docs: cloud agent M2 文档同步（server/androidApp AGENTS + ADR-018 §6 状态）"
```

---

## 验收对照（spec §9）

| AC | 覆盖任务 | 验证物 |
|----|----------|--------|
| AC-M2-S1 工具全链路闭环 | Task 2/3 + 4/5/7/8 | AgentRoutesBridgeTest round trip + 真机遛狗 |
| AC-M2-S2 超时/断线/409 | Task 2/3 | DeviceToolServiceTest + AgentRoutesBridgeTest timeout/409 |
| AC-M2-A1 调试入口完整对话 | Task 4/5/7/8 | 真机遛狗（人工） |
| AC-M2-A2 BYOK 授权门 + 零留存 | Task 6 + 4（header 断言） | CloudAgentByokConsentTest + CloudAgentClientTest（key 不进 body） |
| AC-M2-DOC 文档同步 | Task 9 | check_doc_sync |

## 交付沉淀（合 main 前，spec §10）

- 桥协议三要素 → `docs/03-TECHNICAL-SPECS/`（新 TECH_SPEC 或并入 SERVER_IMPLEMENTATION_PLAN）
- 本 spec + plan `git rm`，README §6 白名单摘除（specs 17→16 / plans 3→2）
- 真机遛狗 AC-M2-A1/A2 通过后由用户决定是否合 main（M1 同分支栈式未合）
