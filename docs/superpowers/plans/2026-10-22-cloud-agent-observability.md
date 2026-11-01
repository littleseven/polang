# 云端 Agent 可观测性补齐实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为云端 Agent 建立完整的可观测性体系——LLM 调用明细落库、Admin 后台可查、端侧 Debug 页可看。

**Architecture:** 在 AgentRunner 的 Koog loop 中拦截每次 LLM 调用，写入 `llm_call_log`（source="cloud-agent" + session_id 关联）；Admin 后台新增 `/admin/agent` 页面（会话列表 → 事件流回放 → LLM 调用明细）；端侧 Debug 页增加「云端」Tab 拉取当前设备的云端 Agent 日志。

**Tech Stack:** Ktor 3.0.3 + Exposed 0.55.0 + SQLite（服务端）；kotlinx.html SSR（Admin 后台）；Retrofit + Room（端侧）

**Spec:** 设计决策已在本对话中确认（复用 llm_call_log 表 + Admin 后台 + 端侧 Debug 页双端呈现）

## Global Constraints

- 服务端构建：`./gradlew -p server build`
- 服务端测试：`./gradlew -p server test`
- Android 编译：`./gradlew :androidApp:compileDebugKotlin`
- 日志标签格式：`PoLang:[FeatureName]`
- [PRIVACY]：release 构建不落 LLM request/response 内容（与端侧现有策略一致）
- llm_call_log 表迁移：新增 `session_id` 列（VARCHAR(64) NULL）+ 索引
- Koog 1.3.0 EventHandler 钩子是**同步非 suspend** 接口，钩子内落库只能 `runBlocking` 桥到 Dispatchers.IO

---

### Task 1: llm_call_log 表加 session_id 列 + 服务端迁移

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt`（LlmCallLogs 表定义，~line 99-123）
- Create: `server/migrations/014_llm_call_log_session_id.sql`

**Interfaces:**
- Consumes: 现有 `LlmCallLogs` Exposed Table 定义
- Produces: `LlmCallLogs.sessionId` 列（`varchar("session_id", 64).nullable()`），后续 Task 2 写入时使用

- [ ] **Step 1: 创建迁移 SQL 文件**

```sql
-- 014_llm_call_log_session_id.sql
-- 云端 Agent LLM 调用关联会话（2026-10-22 可观测性补齐）

ALTER TABLE llm_call_log ADD COLUMN session_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_llm_call_log_session_id ON llm_call_log(session_id, created_at);
```

- [ ] **Step 2: 修改 Tables.kt 加列**

在 `LlmCallLogs` 对象中，`platform` 列之后加：

```kotlin
val sessionId = varchar("session_id", 64).nullable()
```

在 `init` 块中加索引：

```kotlin
init {
    index(isUnique = false, accountId, createdAt)
    index(isUnique = false, createdAt)
    index(isUnique = false, sessionId, createdAt)  // 新增：按会话查 LLM 调用
}
```

- [ ] **Step 3: 检查 Migrations.kt 是否需要注册新迁移**

读取 `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt`，确认迁移加载机制。如果它扫描 `migrations/` 目录自动执行，则无需修改；如果是硬编码列表，需要添加 `014_llm_call_log_session_id.sql`。

- [ ] **Step 4: 编译验证**

```bash
./gradlew -p server compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt server/migrations/014_llm_call_log_session_id.sql
git commit -m "feat(server): llm_call_log 加 session_id 列——云端 Agent 调用关联会话"
```

---

### Task 2: UsageRecorder 支持 session_id + cloud-agent source

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/analytics/UsageRecorder.kt`

**Interfaces:**
- Consumes: Task 1 的 `LlmCallLogs.sessionId` 列
- Produces: `UsageRecorder.log()` 新增可选参数 `sessionId: String? = null`，Task 3 调用时传入

- [ ] **Step 1: 修改 UsageRecorder.log() 签名**

在现有参数列表末尾（`platform` 之后）加：

```kotlin
suspend fun log(
    accountId: Int,
    model: String,
    provider: String,
    usage: TokenUsage?,
    respBytes: Int,
    status: String,
    latencyMs: Int?,
    prices: Map<String, Price>,
    deviceId: String? = null,
    platform: String? = null,
    sessionId: String? = null,  // 新增：云端 Agent 会话关联
    now: Long = Instant.now().toEpochMilli(),
)
```

- [ ] **Step 2: 在 insert 块中写入 sessionId**

在 `LlmCallLogs.insert { }` 块中，`platform` 赋值之后加：

```kotlin
it[LlmCallLogs.sessionId] = sessionId
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew -p server compileKotlin
```

Expected: BUILD SUCCESSFUL（现有调用方不需要改——sessionId 有默认值 null）

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/analytics/UsageRecorder.kt
git commit -m "feat(server): UsageRecorder 支持 session_id——云端 Agent LLM 调用可关联会话"
```

---

### Task 3: AgentRunner 拦截 LLM 调用写入 llm_call_log

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/AgentRunner.kt`

**Interfaces:**
- Consumes: Task 2 的 `UsageRecorder.log(sessionId = ...)`；`AgentLlmTarget` 的 model/provider 信息
- Produces: 云端 Agent 每轮 LLM 调用自动写入 `llm_call_log`（source 字段区分：accountId 用现有账号 ID，model/provider 从 AgentLlmTarget 取）

**关键设计说明：**

Koog 的 `promptExecutor` 是 LLM 调用的实际执行者。要拦截 LLM 调用，最干净的方式是**包装 executor**——在 `AgentExecutorFactory.create()` 返回的 executor 外面包一层，在 `execute()` 前后记录 latency、从返回的 `LLMResponse` 中提取 token usage，然后调 `UsageRecorder.log()`。

需要读取 `AgentExecutorFactory.kt` 和 `AgentLlmTarget.kt` 确认 executor 接口类型和 target 中的 model/provider 字段。

- [ ] **Step 1: 读取 AgentExecutorFactory 和 AgentLlmTarget 确认接口**

读取 `server/src/main/kotlin/com/mamba/picme/server/agent/AgentExecutorFactory.kt` 和 `server/src/main/kotlin/com/mamba/picme/server/agent/AgentLlmTarget.kt`，确认：
- `create()` 返回类型（应该是包含 `executor` 和 `model` 的 data class）
- executor 的接口类型（Koog 的 `PromptExecutor` 或类似）
- `AgentLlmTarget` 中 model 和 provider 的字段名

- [ ] **Step 2: 在 AgentRunner 中添加 LLM 调用拦截**

在 `AgentRunner` 类中添加一个内部包装函数。在 `runTurn` 方法中，将 `built.executor` 替换为包装后的版本。

具体实现模式（需要根据 Step 1 读取的实际接口调整）：

```kotlin
// 在 AgentRunner 中添加私有方法
private suspend fun <T> wrapExecutorWithLogging(
    executor: PromptExecutor,  // 类型以 AgentExecutorFactory 实际返回为准
    target: AgentLlmTarget,
    session: AgentSession,
    accountId: Int,
    deviceId: String?,
    platform: String?,
    prices: Map<String, Price>,
): PromptExecutor {
    // 返回一个包装过的 executor，在 execute 前后记录
    // 参考 Koog PromptExecutor 接口：suspend fun execute(prompt: ModelInput): LLMResponse
}
```

如果 Koog 的 `PromptExecutor` 是接口/函数类型，用装饰器模式包装；如果是具体类，考虑用代理。

**替代方案（如果包装 executor 不可行）：** 在 `AgentExecutorFactory.create()` 中直接返回已包装的 executor，把 `UsageRecorder` 注入 factory。这样 AgentRunner 不需要改。

- [ ] **Step 3: 写测试验证 LLM 调用被记录**

在 `server/src/test/kotlin/` 创建测试文件，验证：
- 跑一轮云端 Agent 后，`llm_call_log` 表中有 `session_id = <sessionId>` 的记录
- `model` 字段与 `AgentLlmTarget.model` 一致
- `status` 为 "ok"（成功时）
- `latencyMs` 非空

```kotlin
class CloudAgentLlmCallLogTest {
    @Test
    fun `cloud agent turn writes llm_call_log with session_id`() = runTest {
        // 创建会话 → 跑一轮（mock LLM 响应）→ 查 llm_call_log 验证
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
./gradlew -p server test --tests "CloudAgentLlmCallLogTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/AgentRunner.kt server/src/test/kotlin/CloudAgentLlmCallLogTest.kt
git commit -m "feat(server): 云端 Agent LLM 调用写 llm_call_log——可观测性补齐核心"
```

---

### Task 4: Admin 后台新增 /admin/agent 页面

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`（新增路由）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`（新增查询）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`（新增页面渲染）

**Interfaces:**
- Consumes: `AgentSessionRepository.listByOwner()` / `AgentEventRepository.listBySession()`；`LlmCallLogs` 表（Task 1 的 sessionId 列）
- Produces: `/admin/agent` 页面（会话列表）、`/admin/agent/{id}` 页面（事件流回放 + LLM 调用明细）

- [ ] **Step 1: AdminQueries 新增云端 Agent 查询函数**

在 `AdminQueries.kt` 中添加：

```kotlin
// 云端 Agent 会话列表（Admin 全量，不限 owner）
suspend fun agentSessions(limit: Int = 100): List<AgentSessionRow> =
    newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AgentSessions.selectAll()
            .orderBy(AgentSessions.seq to SortOrder.DESC)
            .limit(limit)
            .map { it.toAgentSessionRow() }
    }

// 单个会话的 LLM 调用记录
suspend fun agentSessionLlmCalls(sessionId: String, limit: Int = 50): List<CallRow> =
    newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        LlmCallLogs.selectAll()
            .where { LlmCallLogs.sessionId eq sessionId }
            .orderBy(LlmCallLogs.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toCallRow() }
    }
```

需要定义 `AgentSessionRow` DTO（id, status, channelKind, model, createdAt, updatedAt, finishedAt, eventCount）。

- [ ] **Step 2: AdminViews 新增页面渲染函数**

在 `AdminViews.kt` 的 `object AdminViews` 中添加两个函数：

```kotlin
fun agentSessionsPage(sessions: List<AgentSessionRow>): String = createHTML().html {
    adminHead("云端 Agent · PoLang 管理后台")
    body {
        navBar()
        h1 { +"云端 Agent 会话" }
        table("tbl") {
            thead {
                tr {
                    th { +"ID" }; th { +"模型" }; th { +"通道" }; th { +"状态" }
                    th { +"事件数" }; th { +"创建时间" }; th { +"操作" }
                }
            }
            tbody {
                sessions.forEach { s ->
                    tr {
                        td { code { +s.id.take(8) } }
                        td { +s.model }
                        td { +s.channelKind }
                        td { +s.status }
                        td { +s.eventCount.toString() }
                        td { +fmtTime(s.createdAt) }
                        td { a("/admin/agent/${s.id}") { +"查看" } }
                    }
                }
            }
        }
    }
}

fun agentSessionDetailPage(
    session: AgentSessionRow,
    events: List<AgentEvent>,
    llmCalls: List<CallRow>,
): String = createHTML().html {
    adminHead("会话详情 · PoLang 管理后台")
    body {
        navBar()
        h1 { +"会话 ${session.id.take(8)}" }
        // 会话元信息卡片
        div("cards") {
            statCard("模型", session.model)
            statCard("通道", session.channelKind)
            statCard("状态", session.status)
            statCard("事件数", session.eventCount.toString())
        }
        // LLM 调用明细表
        h2 { +"LLM 调用（${llmCalls.size} 条）" }
        table("tbl") {
            thead {
                tr {
                    th { +"时间" }; th { +"模型" }; th { +"Status" }
                    th { +"Tokens" }; th { +"延迟" }; th { +"成本" }
                }
            }
            tbody {
                llmCalls.forEach { c ->
                    tr {
                        td { +fmtTime(c.createdAt) }
                        td { +c.model }
                        td { +c.status }
                        td { +"${c.promptTokens ?: "-"} / ${c.completionTokens ?: "-"}" }
                        td { +"${c.latencyMs ?: "-"} ms" }
                        td { +"¥${c.costCny}" }
                    }
                }
            }
        }
        // 事件流时间线
        h2 { +"事件流（${events.size} 条）" }
        div("events") {
            events.forEach { e ->
                div("event-item") {
                    attributes["data-type"] = e.type.name.lowercase()
                    span("event-id") { +"#${e.id}" }
                    span("event-type") { +e.type.name }
                    span("event-source") { +"${e.source}/${e.role}" }
                    span("event-time") { +fmtTime(e.createdAt) }
                    div("event-payload") {
                        +e.payload.toDisplayString().take(500)
                    }
                }
            }
        }
    }
}
```

需要在 `AdminViews` 中添加 `AgentEvent.toDisplayString()` 辅助函数（将 payload 转为可读文本）。

- [ ] **Step 3: AdminRoutes 新增路由**

在 `AdminRoutes.kt` 的 `adminRoute` 函数中，在 `get("/release")` 之后添加：

```kotlin
get("/agent") {
    if (!call.adminGuard(adminToken)) return@get
    val sessions = AdminQueries.agentSessions()
    call.respondText(AdminViews.agentSessionsPage(sessions), ContentType.Text.Html)
}
get("/agent/{id}") {
    if (!call.adminGuard(adminToken)) return@get
    val sessionId = call.parameters["id"] ?: return@get call.respondText("missing id", status = HttpStatusCode.BadRequest)
    val session = AdminQueries.agentSessionDetail(sessionId) ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
    val events = AgentEventRepository.listBySession(sessionId, limit = Int.MAX_VALUE)
    val llmCalls = AdminQueries.agentSessionLlmCalls(sessionId)
    call.respondText(AdminViews.agentSessionDetailPage(session, events, llmCalls), ContentType.Text.Html)
}
```

需要在 `AdminQueries` 中补 `agentSessionDetail(sessionId)` 函数。

- [ ] **Step 4: navBar 加链接**

在 `AdminViews.kt` 的 `navBar()` 函数中，在现有导航链接之后加：

```kotlin
a("/admin/agent", classes = "nav-link") { +"云端Agent" }
```

- [ ] **Step 5: 编译验证**

```bash
./gradlew -p server build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/
git commit -m "feat(server): Admin 后台新增 /admin/agent 页面——云端 Agent 会话列表+事件流回放+LLM调用明细"
```

---

### Task 5: 端侧 CloudAgentClient 新增拉取云端日志 API

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/remote/picme/CloudAgentClient.kt`

**Interfaces:**
- Consumes: 服务端 `GET /agent/sessions`（已有）和新增的 `GET /agent/logs` 接口
- Produces: `CloudAgentClient.fetchCloudLogs(): Result<CloudAgentLogs>` 方法，Task 6 的 ViewModel 调用

**前提：** 服务端需要新增一个 `GET /agent/logs` 接口（X-App-Token 鉴权，返回当前设备关联的云端 Agent LLM 调用记录 + 会话事件）。这个接口在 Task 5 之前需要先实现。

**重新排序：** 这个 Task 应该在 Task 4 之后做，因为需要先实现服务端 `/agent/logs` 接口。

**实际上，更好的方案是复用现有接口：**
- `GET /agent/sessions` 已有（返回当前用户的会话列表）
- `GET /agent/sessions/{id}/events` 已有（返回事件流）
- 新增 `GET /agent/sessions/{id}/llm-calls` 返回该会话的 LLM 调用记录

这样端侧可以逐个会话拉取，不需要新接口。但端侧需要知道哪些会话是当前设备的——现有 `GET /agent/sessions` 按 tokenHash 过滤，已经满足（当前设备登录的账号）。

**简化方案：** 端侧 Debug 页直接调现有接口：
1. `GET /agent/sessions` → 会话列表
2. 对每个会话 `GET /agent/sessions/{id}/events` → 事件流
3. 新增 `GET /agent/sessions/{id}/llm-calls` → LLM 调用记录（需要在 AgentRoutes.kt 中加）

- [ ] **Step 1: 服务端 AgentRoutes 新增 llm-calls 端点**

在 `AgentRoutes.kt` 中，`get("/sessions/{id}/events")` 之后加：

```kotlin
get("/sessions/{id}/llm-calls") {
    val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
    val session = AgentSessionRepository.find(sessionId) ?: return@get call.respond(HttpStatusCode.NotFound)
    // 鉴权：只能看自己的会话（tokenHash 匹配）
    val tokenHash = call.attributes[TokenHashKey]
    if (session.tokenHash != tokenHash) return@get call.respond(HttpStatusCode.Forbidden)
    val calls = AgentSessionLlmCalls.query(sessionId)  // 新增查询
    call.respond(CallsResponse(calls))
}
```

需要定义 `CallsResponse` 序列化 DTO 和 `AgentSessionLlmCalls` 查询对象。

- [ ] **Step 2: 端侧 CloudAgentClient 新增 fetch 方法**

在 `CloudAgentClient` 中添加：

```kotlin
suspend fun getSessions(token: String): Result<List<CloudAgentSession>> {
    // GET /agent/sessions
}

suspend fun getEvents(token: String, sessionId: String, after: Long, limit: Int): Result<List<JSONObject>> {
    // GET /agent/sessions/{id}/events?after=&limit=  （已有，可能只需复用）
}

suspend fun getLlmCalls(token: String, sessionId: String): Result<List<CloudLlmCall>> {
    // GET /agent/sessions/{id}/llm-calls
}
```

- [ ] **Step 3: 定义 CloudLlmCall DTO**

```kotlin
@Serializable
data class CloudLlmCall(
    val id: Long,
    val model: String,
    val status: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val latencyMs: Int?,
    val createdAt: Long,
)
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew -p server compileKotlin
./gradlew :androidApp:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/agent/AgentRoutes.kt
git add androidApp/src/main/java/com/mamba/picme/data/remote/picme/CloudAgentClient.kt
git commit -m "feat: 端云双端新增云端 Agent LLM 调用拉取接口"
```

---

### Task 6: 端侧 LlmCallLogScreen 增加「云端」Tab

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/debug/LlmCallLogScreen.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/debug/LlmCallLogViewModel.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/debug/TurnRecordModels.kt`（如存在）

**Interfaces:**
- Consumes: Task 5 的 `CloudAgentClient.getSessions()` / `getEvents()` / `getLlmCalls()`
- Produces: `LlmCallLogScreen` 增加第四个 Tab「云端」，显示当前账号的云端 Agent 会话列表 → 点击进入事件流 + LLM 调用明细

- [ ] **Step 1: ViewModel 增加云端数据加载**

在 `LlmCallLogViewModel` 中添加：

```kotlin
// 云端 Agent 数据
private val _cloudSessions = MutableStateFlow<List<CloudAgentSession>>(emptyList())
val cloudSessions = _cloudSessions.asStateFlow()

private val _cloudEvents = MutableStateFlow<List<CloudAgentEvent>>(emptyList())
val cloudEvents = _cloudEvents.asStateFlow()

private val _cloudLlmCalls = MutableStateFlow<List<CloudLlmCall>>(emptyList())
val cloudLlmCalls = _cloudLlmCalls.asStateFlow()

fun loadCloudSessions() {
    viewModelScope.launch {
        val result = cloudAgentClient.getSessions(token)
        _cloudSessions.value = result.getOrDefault(emptyList())
    }
}

fun loadCloudSessionDetail(sessionId: String) {
    viewModelScope.launch {
        val eventsResult = cloudAgentClient.getEvents(token, sessionId, after = 0, limit = Int.MAX_VALUE)
        val callsResult = cloudAgentClient.getLlmCalls(token, sessionId)
        _cloudEvents.value = eventsResult.getOrDefault(emptyList())
        _cloudLlmCalls.value = callsResult.getOrDefault(emptyList())
    }
}
```

需要注入 `CloudAgentClient` 和 token（通过 Factory 传入）。

- [ ] **Step 2: Screen 增加「云端」Tab**

在 `LogTab` 枚举中加 `CLOUD`：

```kotlin
private enum class LogTab { LLM, TOOL, JS, CLOUD }
```

Tab 标题从 3 个变 4 个（需要加字符串资源）。

在 `CLOUD` Tab 中：
- 列表态：显示云端会话列表（模型/状态/创建时间/事件数）
- 点击会话 → 加载该会话的事件流 + LLM 调用
- 详情态：上半部分显示 LLM 调用记录表，下半部分显示事件流时间线

- [ ] **Step 3: 添加字符串资源**

在 `values/strings.xml` 中添加：
```xml
<string name="log_tab_cloud">云端</string>
<string name="cloud_agent_session_list">云端 Agent 会话</string>
<string name="cloud_agent_llm_calls">LLM 调用</string>
<string name="cloud_agent_events">事件流</string>
```

五语同步：zh-rCN, zh-rTW, es, fr。

- [ ] **Step 4: 编译验证**

```bash
./gradlew :androidApp:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/debug/
git add androidApp/src/main/res/values*/strings.xml
git commit -m "feat(app): Debug 页 LLM 日志增加云端 Tab——真机排查云端 Agent"
```

---

### Task 7: 端到端验证

**Files:**
- 无新文件，验证测试

- [ ] **Step 1: 服务端全量测试**

```bash
./gradlew -p server build
```

Expected: BUILD SUCCESSFUL + all tests pass

- [ ] **Step 2: Android 全量编译**

```bash
./gradlew :androidApp:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 真机/模拟器验证**

1. 安装 debug 包到设备
2. 开启云端 Agent（设置 → 远程模型 → cloud_agent_enabled）
3. 发一条消息触发云端 Agent
4. 打开 Debug 页 → LLM 日志 → 云端 Tab
5. 验证：能看到云端会话列表，点击进入能看到事件流和 LLM 调用记录

- [ ] **Step 4: Admin 后台验证**

1. 浏览器打开 `https://api.polang.net/admin/agent`
2. 验证：能看到云端 Agent 会话列表
3. 点击某个会话 → 验证：能看到事件流时间线和 LLM 调用明细表

- [ ] **Step 5: Commit（如有修复）**

```bash
git add -A
git commit -m "fix: 云端 Agent 可观测性端到端验证修复"
```

---

## Self-Review

### 1. Spec coverage

| 设计决策 | 覆盖 Task |
|---------|----------|
| LLM 调用写 llm_call_log（source=cloud-agent + session_id） | Task 1, 2, 3 |
| Admin 后台 /admin/agent 页面 | Task 4 |
| 端侧 Debug 页云端 Tab | Task 5, 6 |
| 端到端验证 | Task 7 |

### 2. Placeholder scan

- Task 3 Step 2 中 PromptExecutor 的确切类型需要根据 Koog API 确认——这是必要的探索步骤，不是 placeholder
- Task 5 Step 1 中 `AgentSessionLlmCalls` 查询对象需要在实现时创建——接口已明确（输入 sessionId，输出 LLM 调用列表）

### 3. Type consistency

- `sessionId` 在 Task 1（列定义）→ Task 2（UsageRecorder 参数）→ Task 3（AgentRunner 调用）→ Task 4（Admin 查询）→ Task 5（API 响应）中保持一致
- `CloudLlmCall` DTO 在 Task 5 定义，Task 6 消费——字段一致
- `AgentSessionRow` 在 Task 4 定义和使用——字段一致

---

## 执行方式选择

**Plan complete and saved to `docs/superpowers/plans/2026-10-22-cloud-agent-observability.md`.**

**Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
