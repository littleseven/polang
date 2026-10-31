# 管理后台推理追溯（server M2）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 管理后台新增两个只读页面——全局 LLM 调用流水（`llm_call_log` 分页/筛选/下钻）与云端 Agent 会话事件流回放（逐步还原一轮推理），并与用户详情页交叉打通。

**Architecture:** 纯查询 + SSR 渲染，零写路径、零 schema 变更。`AdminQueries` 新增四个查询（游标分页），`AdminViews` 新增三个页面（kotlinx.html，沿用现有 navBar/视觉语言），`AdminRoutes` 挂三个 GET 路由（`adminGuard` 鉴权）。Agent 事件回放直接用 `AgentEventPayloadCodec.decode` 解类型化载荷。

**Tech Stack:** Ktor 3 / Exposed / kotlinx.html / SQLite；JUnit（现有 `AdminQueriesTest`/`AdminRoutesTest` 基建）。

**Spec:** `docs/superpowers/specs/2026-11-01-agent-step-timeline-and-admin-trace-design.md`（§4.1 服务端现状、§6 M2 设计、§7 已知缺口、§8 验收）

## Global Constraints

- **只读**：不新增任何 POST/写操作；不改 `agent_event`/`llm_call_log` schema。
- **转义铁律**：argsJson/observation content 等用户内容一律经 kotlinx.html 默认转义上屏，禁用 `unsafe { raw(...) }`（`html_card` 载荷只显示 summary + 字符数，永不渲染 HTML 本体）。
- **脱敏口径**：邮箱用现有 `AdminQueries` 的 `maskEmail`；无关联账号的会话显示 tokenHash 前 8 位；账号已注销（join null）显示「已注销」。
- **2G 盒子**：不做全表 count；分页一律游标（`before`/`after`），单页上限 50（流水/会话）与 200（事件）。
- **[DOC-SYNC]** 交付前更新 `server/AGENTS.md` 路由表与版本号（`server/build.gradle.kts` bump patch）。
- **工作区隔离**：动手前按 `using-git-worktrees` 建隔离 worktree + 专用分支（AGENTS.md §3.4）。
- 部署遵循「双交付铁律」：计划最后一步由人执行 `./server/deploy.sh`（非 agent 自动）。

## Review Focus

1. **XSS**：构造 argsJson 含 `<script>` 的 action 事件，详情页必须转义（Task 4 测试钉住）。
2. **孤儿 observation（`actionEventId=0`/无配对）**：必须单独成条 + 「门控拦截/未配对」徽标，不得隐藏（Task 3/4 测试钉住）。
3. **软删除账号**：流水行 join 不到账号 → 显示「已注销」不崩溃（Task 1 测试钉住）。
4. **游标分页边界**：`before` 为严格小于、事件 `after` 为严格大于，翻页不重不漏（Task 1/2 测试钉住）。
5. **假 running 会话**：`status=running` 但最后事件 >30min → 列表标注「stalled」（视图层判断，Task 3 钉住）。

---

### Task 1: AdminQueries.llmCalls 查询

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`（`CallRow` :91-104 附近加新模型；文件尾部加查询函数）
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt`

**Interfaces:**
- Consumes: 现有 `LlmCallLogs`（`db/Tables.kt`）、`Accounts` 表、私有 `maskEmail`（:539）。
- Produces（Task 3/5 依赖）:
  ```kotlin
  data class LlmCallListRow(
      val id: Long, val accountId: Int, val emailMasked: String?, // null = 账号已注销
      val model: String, val provider: String,
      val promptTokens: Int?, val completionTokens: Int?, val cachedTokens: Int?,
      val costCny: Double, val status: String, val latencyMs: Int?,
      val platform: String?, val createdAt: Long,
  )
  /** id 倒序游标分页：before = 严格小于（传 null 取最新页）；筛选均可空。 */
  suspend fun llmCalls(
      before: Long?, limit: Int = 50,
      status: String? = null, provider: String? = null,
      model: String? = null, accountId: Int? = null,
  ): List<LlmCallListRow>
  ```

- [ ] **Step 1: 写失败测试**（DB 初始化/清表模式照抄本文件既有用例）

```kotlin
@Test fun `llmCalls returns rows desc with masked email`()            // 插 2 账号 3 行，断言倒序 + maskEmail 形态
@Test fun `llmCalls before cursor paginates without overlap`()        // 55 行，两页拉取，断言不重不漏、第二页 before=第一页末行 id
@Test fun `llmCalls filters by status and provider and accountId`()
@Test fun `llmCalls soft-deleted account yields null emailMasked`()   // join null → "已注销" 由视图层处理，查询层给 null
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*AdminQueriesTest*"`
Expected: FAIL（`llmCalls`/`LlmCallListRow` 未定义，编译错误）

- [ ] **Step 3: 实现**

`LlmCallLogs leftJoin Accounts`（on `accountId` eq `Accounts.id`），where 组合：`before?.let { LlmCallLogs.id less it }` + 各筛选 `eq`，orderBy `id DESC`，limit。邮箱列取 `Accounts.email` 可空，`maskEmail` 后置（注意 `maskEmail` 是 private——新查询在同 object 内直接可用）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "*AdminQueriesTest*"`
Expected: PASS（含既有用例）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt
git commit -m "feat(admin): llmCalls paged query with filters for call log stream"
```

---

### Task 2: AdminQueries Agent 会话/事件查询

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt`

**Interfaces:**
- Consumes: `AgentSessions`/`AgentEvents`（`agent/AgentTables.kt`）、`AgentEventPayloadCodec.decode`（`agent/AgentEvent.kt:121-131`）、`Accounts`。
- Produces（Task 3/4/5 依赖）:
  ```kotlin
  data class AgentSessionRow(
      val id: String,                 // 对外 uuid
      val seq: Long,                  // 内部分页游标（Task 4 的 nextBeforeSeq 取当页末行 seq；review 收编）
      val userLabel: String,          // 脱敏邮箱；无关联/已注销账号 = tokenHash 前 8 位
      val status: String, val channelKind: String, val model: String,
      val eventCount: Long, val lastEventAt: Long?,   // stalled 判定用（视图层 >30min）
      val createdAt: Long, val updatedAt: Long,
  )
  data class AgentEventRow(
      val id: Long, val type: String, val source: String, val role: String,
      val parentId: Long?, val payload: AgentEventPayload?,  // decode 失败 = null（视图降级显示截断原文，此时 payloadRaw 带原文）
      val payloadRaw: String? = null, // 仅 decode 失败时填原文（review 收编；成功保持 null 不复制大文本）
      val createdAt: Long,
  )
  /** seq 倒序游标（beforeSeq 严格小于）；accountId 可空 = 经 Accounts.tokenHash 过滤。 */
  suspend fun agentSessions(beforeSeq: Long?, limit: Int = 50, accountId: Int? = null): List<AgentSessionRow>
  /** id 升序，after 严格大于（回放游标），limit ≤ 200。 */
  suspend fun agentSessionEvents(sessionId: String, after: Long = 0, limit: Int = 200): List<AgentEventRow>
  suspend fun agentSessionSummary(sessionId: String): AgentSessionRow?
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `agentSessions paged desc with eventCount and user label`() // 关联到账号的给脱敏邮箱，无关联给 tokenHash 前 8
@Test fun `agentSessions accountId filter resolves via tokenHash`()
@Test fun `agentSessionEvents after cursor asc and payload decoded`()  // action 载荷 decode 为 AgentEventPayload.Action
@Test fun `agentSessionEvents undecodable payload yields null payload`() // 插一行 payload_json 为 "{broken" 断言不抛、payload=null
@Test fun `agentSessionSummary returns null for unknown session`()
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*AdminQueriesTest*"`
Expected: FAIL（编译错误）

- [ ] **Step 3: 实现**

会话查询：`AgentSessions`，`beforeSeq` 游标 + `orderBy(seq DESC)` + limit；`eventCount`/`lastEventAt` 用一次按 sessionId 分组的子查询或逐行查（50 行/页可接受，先正确后优化）；accountId → 先查 `Accounts.tokenHash`，无则返回空列表。事件查询：`AgentEvents.sessionId eq` + `id greater after`，`orderBy(id ASC)`；每行 `runCatching { AgentEventPayloadCodec.decode(payloadJson) }.getOrNull()`。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "*AdminQueriesTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt
git commit -m "feat(admin): agent session and event replay queries"
```

---

### Task 3: AdminViews 三个页面 + 导航

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`（新页面函数；`navBar` :1687-1709 加链接；`userDetailPage` :266 加交叉链接）

**Interfaces:**
- Consumes: Task 1/2 的 `LlmCallListRow`/`AgentSessionRow`/`AgentEventRow`。
- Produces（Task 5 路由依赖）:
  ```kotlin
  fun llmCallsPage(rows: List<LlmCallListRow>, status: String?, provider: String?,
                   model: String?, accountId: Int?, nextBefore: Long?): String
  fun agentSessionsPage(rows: List<AgentSessionRow>, accountId: Int?, nextBeforeSeq: Long?): String
  fun agentSessionDetailPage(session: AgentSessionRow, events: List<AgentEventRow>, nextAfter: Long?): String
  ```

- [ ] **Step 1: llmCallsPage**

`createHTML().html { adminHead("调用流水"); body { navBar(); … } }` 模式照抄 `usersPage`。
- 筛选表单（GET `/admin/llm/calls`）：status 下拉（全部/ok/upstream_error/blocked_quota/blocked_rate）、provider 下拉（全部/CLOUDFLARE/TOKENHUB）、model 文本框、`account_id` hidden（从用户详情带入时显示「按用户筛选中 ×清除」链接）。
- 表格列：时间（`relTime` 或绝对 HH:mm:ss）、用户（emailMasked ?: "已注销"，链 `/admin/users/{accountId}`）、模型、渠道、prompt/completion/cached、成本、延迟 ms、状态色块（ok 绿 / upstream_error 红 / blocked_* 黄）、platform。
- 底部「更早 50 条」链接：`nextBefore` 非空时输出，href 带齐全部筛选参数 + `before=nextBefore`。

- [ ] **Step 2: agentSessionsPage**

列：会话短码（`id.take(8)`… 链详情）、用户（userLabel）、状态（`running` 且 `lastEventAt` 距今 >30min 追加「stalled」灰徽标）、channel、model、事件数、创建/更新时间。`accountId` 筛选态同 Task 3 Step 1 口径。底部「更早」链接带 `before_seq`。

- [ ] **Step 3: agentSessionDetailPage**

头部：会话信息条（userLabel/状态/渠道/模型/时间）。事件流按 `AgentEventRow.type` 渲染，每条显示绝对时间 + 与上一条的 Δms：
- `message`：user / assistant 分色块（角色取 `role` 字段），content 直接 `+`（自动转义）。
- `action`：工具名加粗 + `<details>` 折叠 argsJson（`<pre>`，自动转义）。
- `observation`：缩进一块；`content` 以 "error" 前缀红色；按 `payload.actionEventId` 与页内 action 配对（配对不到或 actionEventId=0 → 「门控拦截/未配对」徽标，**照常成条不隐藏**）。
- `html_card`：一行「H5 卡：{summary}（{html.length} 字符）」，不渲染 HTML。
- `finish`：通栏横幅显示 reason（done 绿 / cancelled 灰 / error 红）。
- `condensation`：灰字摘要行。
- payload = null（decode 失败）：黄底「无法解析的事件载荷」+ 截断 200 字符原文（转义）。
- 底部「更早事件」链接 `after=nextAfter`（注意事件是升序翻页：首屏应拉**最新 200 条之前的**……简化口径：首屏从 0 开始顺序拉，「下一页」`after=末条 id`；长会话尾部看不到——接受，实现者可在头部加「跳到最新」链接，`after` 取 `max(0, 总事件数-200)` 对应 id 由查询层补一个 `agentSessionEventCount(sessionId)` 若需要）。

- [ ] **Step 4: navBar 加链接 + userDetailPage 交叉链接**

`navBar()` 在「流量」后插两个：`a("/admin/llm/calls", classes = "nav-link") { +"调用" }`、`a("/admin/agent/sessions", classes = "nav-link") { +"Agent" }`。
`userDetailPage`「最近调用」区标题旁加：`a("/admin/llm/calls?account_id=${d.id}") { +"调用流水 →" }` 和 `a("/admin/agent/sessions?account_id=${d.id}") { +"Agent 会话 →" }`（UserDetail 的实际字段名以实现时为准）。

- [ ] **Step 5: 编译**

Run: `./gradlew -p server compileKotlin`
Expected: 编译过

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt
git commit -m "feat(admin): call stream and agent session replay pages"
```

---

### Task 4: AdminRoutes 三个路由 + 端到端测试

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`（`get("/traffic")` :242 附近插入）
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt`

**Interfaces:**
- Consumes: Task 3 的视图函数；Task 1/2 的查询；现有 `adminGuard`（:712）。
- Produces: 三个 GET 路由（对外契约）：
  - `GET /admin/llm/calls?before&status&provider&model&account_id`
  - `GET /admin/agent/sessions?before_seq&account_id`
  - `GET /admin/agent/sessions/{id}?after`

- [ ] **Step 1: 写失败测试**（testApplication/建库模式照抄本文件既有用例）

```kotlin
@Test fun `llm calls page requires admin auth`()              // 无 cookie/token 的响应口径同现有 admin 路由测试
@Test fun `llm calls page renders rows and filter links`()    // 造 1 行日志，断言 200 + 模型名在 HTML 中
@Test fun `agent session detail escapes script in argsJson`() // argsJson 含 "<script>alert(1)</script>"，断言响应不含原串、含转义形态
@Test fun `agent session detail shows orphan observation with badge`() // actionEventId=0 的 observation，断言「门控拦截」字样在 HTML 中
@Test fun `agent session detail does not render html_card html`() // 断言响应不含 html 载荷原文的标志性子串
@Test fun `unknown session id returns 404`()
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*AdminRoutesTest*"`
Expected: FAIL（404/编译错误）

- [ ] **Step 3: 实现路由**

三个 `get` 块，每块开头 `if (!call.adminGuard(adminToken)) return@get`；参数解析（`toLongOrNull`/`toIntOrNull`，非法 → 400 纯文本，模式照抄 :172-186）；查不到会话 → 404；`nextBefore`/`nextBeforeSeq`/`nextAfter` = 当页非空时末行游标（满页才给，防死循环）；`respondText(view(...), ContentType.Text.Html)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "*AdminRoutesTest*" && ./gradlew -p server build`
Expected: PASS + build 绿

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt
git commit -m "feat(admin): wire /admin/llm/calls and /admin/agent/sessions routes"
```

---

### Task 5: 文档同步 + 本地验收 + 版本 bump

**Files:**
- Modify: `server/AGENTS.md`（§3 路由表加三行、§2 结构 admin 条目补页面说明、头部「最后更新」）
- Modify: `server/build.gradle.kts`（version bump patch）
- Modify: `docs/superpowers/README.md`（§6 状态推进）

- [ ] **Step 1: server/AGENTS.md 同步**

§3 路由表新增三行（GET `/admin/llm/calls`、`/admin/agent/sessions`、`/admin/agent/sessions/{id}`，P1、✅、ADMIN_TOKEN）；§2 admin 目录条目补「调用流水/Agent 会话回放页面」；版本/日期头更新。

- [ ] **Step 2: 版本 bump + 全量构建**

`server/build.gradle.kts` version patch +1。Run: `./gradlew -p server build` → 绿。

- [ ] **Step 3: 本地起服验收**

`./server/run-local.sh start` 后（ADMIN_TOKEN 取本地 .env）：
```bash
curl -s -H "X-Admin-Token: $ADMIN_TOKEN" "http://127.0.0.1:8080/admin/llm/calls?status=ok" | grep -c "<tr"
curl -s -H "X-Admin-Token: $ADMIN_TOKEN" "http://127.0.0.1:8080/admin/agent/sessions" | head
```
人工过一遍：分页翻页不重不漏、筛选生效、用户详情双向跳转、回放页事件顺序与配对/徽标/转义正确。跑 `./scripts/check_doc_sync.py`（或 ai-gate）确认文档门禁绿。

- [ ] **Step 4: Commit**

```bash
git add server/AGENTS.md server/build.gradle.kts docs/superpowers/README.md
git commit -m "chore(admin): doc sync and version bump for llm trace pages"
```

- [ ] **Step 5: 部署（人工执行，非 agent）**

提示用户：`./server/deploy.sh`（双交付铁律：服务端改动不上线则不生效）。
