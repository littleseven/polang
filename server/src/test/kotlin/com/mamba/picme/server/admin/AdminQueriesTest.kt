package com.mamba.picme.server.admin

import com.mamba.picme.server.agent.AgentEventPayload
import com.mamba.picme.server.agent.AgentEventPayloadCodec
import com.mamba.picme.server.agent.AgentEvents
import com.mamba.picme.server.agent.AgentSessions
import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.BrowserSessions
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminQueriesTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L
    private val cn = java.time.ZoneOffset.ofHours(8)
    private val todayStart =
        java.time.Instant.ofEpochMilli(now).atZone(cn).toLocalDate().atStartOfDay(cn).toInstant().toEpochMilli()

    @Test
    fun `overview users detail recent and daily aggregates`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)

        // 两个账户：A 5 天前注册，B 今日注册
        account(1, "a@x.com", todayStart - 5 * day)
        account(2, "b@x.com", todayStart + 10)

        // 今日 A 成功
        logRow(1, "deepseek-chat", "CLOUDFLARE", 100, 50, 150, 1.0, 1024, "ok", todayStart + 1000)
        // 今日 A 超额拦截
        logRow(1, "deepseek-chat", "CLOUDFLARE", null, null, null, 0.0, 0, "blocked_quota", todayStart + 2000)
        // 昨日 A 成功
        logRow(1, "deepseek-chat", "CLOUDFLARE", 200, 100, 300, 2.0, 2048, "ok", todayStart - day + 500)
        // 今日 B 成功
        logRow(2, "kimi-k2.6", "TOKENHUB", 10, 5, 15, 0.5, 100, "ok", todayStart + 3000)

        // overview
        val o = AdminQueries.overview(now)
        assertEquals(2L, o.totalUsers)
        assertEquals(1L, o.newUsersToday) // 仅 B
        assertEquals(2L, o.callsToday) // 今日两个 ok
        assertEquals(165L, o.tokensToday) // 150 + 15
        assertEquals(1.5, o.costToday, 0.000001) // 1.0 + 0.5
        assertEquals(1124L, o.bytesToday) // 1024 + 100
        assertEquals(1L, o.blockedToday)
        // 累计（两账号均 active；3 条 ok 全量；tokens 150+300+15；cost 1.0+2.0+0.5）
        assertEquals(2L, o.totalUsers)
        assertEquals(0L, o.totalDevices)
        assertEquals(3L, o.totalCalls)
        assertEquals(465L, o.totalTokens)
        assertEquals(3.5, o.totalCost, 0.000001)
        // 新增字段：今日 error + 昨日环比 + 今日/昨日新增设备
        assertEquals(0L, o.errorsToday)
        assertEquals(0L, o.newDevicesToday)
        assertEquals(1L, o.callsYest) // 昨日 A 一条 ok
        assertEquals(300L, o.tokensYest)
        assertEquals(2.0, o.costYest, 0.000001)
        assertEquals(0L, o.blockedYest)
        assertEquals(0L, o.errorsYest)
        assertEquals(0L, o.newUsersYest) // A 5 天前注册，不在昨日窗口
        assertEquals(0L, o.newDevicesYest)

        // users（按 createdAt desc：B 在前）
        val users = AdminQueries.usersList()
        assertEquals(2, users.size)
        val bRow = users[0]
        assertEquals("b@x.com", bRow.email)
        assertEquals(1L, bRow.calls)
        assertEquals(15L, bRow.totalTokens)
        assertEquals(0.5, bRow.cost, 0.000001)
        val aRow = users[1]
        assertEquals("a@x.com", aRow.email)
        assertEquals(2L, aRow.calls) // 今日 + 昨日 ok
        assertEquals(450L, aRow.totalTokens) // 150 + 300
        assertEquals(3.0, aRow.cost, 0.000001)
        assertEquals(todayStart + 2000, aRow.lastActive) // 最后一条是今日 blocked

        // detail for A
        val a = AdminQueries.userDetail(1)!!
        assertEquals(2L, a.calls)
        assertEquals(450L, a.totalTokens)
        assertEquals(3.0, a.cost, 0.000001)
        assertEquals(1L, a.blocked)
        assertEquals(3072L, a.bytes) // A 所有 respBytes：1024 + 0(blocked) + 2048 = 3072
        assertEquals(todayStart + 2000, a.lastActive)

        // detail for unknown
        assertNull(AdminQueries.userDetail(999))

        // recent calls for A（按时间 desc）
        val aRecent = AdminQueries.recentCalls(1, 10)
        assertEquals(3, aRecent.size)
        assertEquals("blocked_quota", aRecent[0].status) // 最新在前
        assertEquals("ok", aRecent[2].status)

        // daily series 7 天：升序，今日 + 昨日有数据
        val series = AdminQueries.dailySeries(7, now)
        assertEquals(7, series.size)
        val todayBucket = series.last()
        assertEquals(2L, todayBucket.calls)
        assertEquals(1L, todayBucket.blocked)
        assertEquals(165L, todayBucket.totalTokens)
        assertEquals(1.5, todayBucket.cost, 0.000001)
        assertEquals(1124L, todayBucket.bytes)
        val yBucket = series[5] // 倒数第二 = 昨天
        assertEquals(1L, yBucket.calls)
        assertEquals(300L, yBucket.totalTokens)
        // 中间空日
        assertTrue(series[0].calls == 0L && series[0].totalTokens == 0L)
    }

    @Test
    fun `empty db overview is zeros and no exceptions`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        val o = AdminQueries.overview(now)
        assertEquals(0L, o.totalUsers)
        assertEquals(0L, o.callsToday)
        assertEquals(0L, o.tokensToday)
        assertEquals(0.0, o.costToday, 0.0)
        assertEquals(0L, o.blockedToday)
        assertEquals(0L, o.totalDevices)
        assertEquals(0L, o.totalCalls)
        assertEquals(0L, o.totalTokens)
        assertEquals(0.0, o.totalCost, 0.0)
        val users = AdminQueries.usersList()
        assertTrue(users.isEmpty())
        val series = AdminQueries.dailySeries(14, now)
        assertEquals(14, series.size)
    }

    @Test
    fun `devicesList orders by lastSeenAt desc masks deviceId and limits`() = runBlocking {
        TestDb.init(AnonymousDevices)
        device(1, "abcdef1234567890", 1, 1_000L, 1_000L)
        device(2, "zzzzzz0000001111", 5, 2_000L, 5_000L)
        val rows = AdminQueries.devicesList(100)
        assertEquals(2, rows.size)
        assertEquals(2, rows[0].id) // lastSeenAt 5000 在前
        assertEquals(1, rows[1].id)
        assertEquals("abcdef••••7890", rows.first { it.id == 1 }.deviceIdMasked)
        assertEquals(5, rows[0].llmCallsUsed)
        assertEquals(1_000L, rows[1].createdAt)
        // limit 截断
        assertEquals(1, AdminQueries.devicesList(1).size)
    }

    @Test
    fun `deviceRawId hits and misses`() = runBlocking {
        TestDb.init(AnonymousDevices)
        device(1, "dev-full-id-xyz", 1, 1L, 1L)
        assertEquals("dev-full-id-xyz", AdminQueries.deviceRawId(1))
        assertNull(AdminQueries.deviceRawId(999))
    }

    @Test
    fun `usersList picks latest non-null device_id per user`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        account(1, "a@x.com", todayStart - day)
        logRow(1, "deepseek-chat", "CLOUDFLARE", 10, 5, 15, 0.1, 100, "ok", todayStart - day + 100, deviceId = "device-old-1234567890")
        logRow(1, "deepseek-chat", "CLOUDFLARE", 10, 5, 15, 0.1, 100, "ok", todayStart + 500, deviceId = "device-aaaa-bbbb-1234")
        logRow(1, "deepseek-chat", "CLOUDFLARE", 10, 5, 15, 0.1, 100, "ok", todayStart + 900, deviceId = null)
        val users = AdminQueries.usersList()
        assertEquals(1, users.size)
        assertEquals("device••••1234", users[0].deviceIdMasked)
    }

    @Test
    fun `channelUsage aggregates by provider for ok calls`() = runBlocking {
        TestDb.init(LlmCallLogs)
        logRow(1, "deepseek-chat", "DeepSeek 直连", 100, 50, 150, 1.0, 100, "ok", 1_000L)
        logRow(1, "deepseek-chat", "DeepSeek 直连", 10, 5, 15, 0.1, 50, "ok", 2_000L)
        logRow(1, "deepseek-chat", "DeepSeek 直连", null, null, null, 0.0, 0, "blocked_quota", 3_000L)
        logRow(1, "kimi-k2.6", "Kimi 直连", 10, 5, 15, 0.5, 80, "ok", 4_000L)

        val usage = AdminQueries.channelUsage()
        val ds = usage.getValue("DeepSeek 直连")
        assertEquals(2L, ds.calls) // 不计 blocked
        assertEquals(165L, ds.tokens) // 150 + 15
        assertEquals(1.1, ds.cost, 0.000001)
        assertEquals(1L, usage.getValue("Kimi 直连").calls)
    }

    @Test
    fun `rangeStats aggregates dims tops latency and totals`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        account(1, "alice@x.com", todayStart - 5 * day)
        account(2, "bob@x.com", todayStart + 10)
        logRow(1, "deepseek-chat", "CLOUDFLARE", 100, 50, 150, 1.0, 100, "ok", todayStart + 1000, deviceId = "dev-aaaa-bbbb-1234", latencyMs = 200)
        logRow(1, "deepseek-chat", "CLOUDFLARE", 100, 50, 150, 1.0, 100, "ok", todayStart + 2000, deviceId = "dev-aaaa-bbbb-1234", latencyMs = 400)
        logRow(2, "kimi-k2.6", "TOKENHUB", 10, 5, 15, 0.5, 80, "ok", todayStart + 3000, latencyMs = 100)
        logRow(1, "deepseek-chat", "CLOUDFLARE", null, null, null, 0.0, 0, "blocked_quota", todayStart + 4000)
        logRow(1, "glm-5.2", "TOKENHUB", 40, 10, 50, 0.3, 60, "ok", todayStart - day + 500, latencyMs = 1000)

        val r = AdminQueries.rangeStats(7, now)

        assertEquals(7, r.days.size)
        assertEquals(3, r.byModel.size)
        val dm = r.byModel.first { it.key == "deepseek-chat" }
        assertEquals(2L, dm.calls)
        assertEquals(300L, dm.tokens)
        assertEquals(2.0, dm.cost, 1e-6)
        val cf = r.byProvider.first { it.key == "CLOUDFLARE" }
        assertEquals(2L, cf.calls)
        assertEquals(2.0, cf.cost, 1e-6)
        val th = r.byProvider.first { it.key == "TOKENHUB" }
        assertEquals(0.8, th.cost, 1e-6) // kimi 0.5 + glm 0.3
        assertEquals(1, r.topUsers[0].id)
        assertEquals(3L, r.topUsers[0].calls)
        assertTrue(r.topUsers[0].label.contains("***"))
        assertTrue(r.topUsers[0].label.contains("@x.com"))
        assertEquals(2L, r.topDevices[0].calls)
        assertTrue(r.topDevices[0].label.contains("••••"))
        assertEquals(4, r.latency.count)
        assertEquals(200, r.latency.p50)
        assertEquals(400, r.latency.p95)
        assertEquals(4L, r.totals.calls)
        assertEquals(1L, r.totals.blocked)
        assertEquals(0L, r.totals.errors)
        // 成本构成（按 defaultPrices 估算；glm-5.2 无单价→0）
        assertEquals(0.000632, r.costSplit.promptCost, 1e-6) // deepseek 200×2.96 + kimi 10×4（/1M）
        assertEquals(0.000947, r.costSplit.completionCost, 1e-6) // deepseek 100×8.87 + kimi 5×12（/1M）
    }

    @Test
    fun `browserOverview counts today active and 7d failure rate`() = runBlocking {
        TestDb.init(BrowserSessions)

        // 今日：一条正常关闭 + 一条活跃（未结束）
        browserSession("s1", todayStart + 100, todayStart + 200, "closed")
        browserSession("s2", todayStart + 300, null, null)
        // 昨日：expired（计入 7 日窗口分子分母）
        browserSession("s3", todayStart - day + 500, todayStart - day + 600, "expired")
        // 8 天前：expired（在 7 日窗口外，不计入）
        browserSession("s4", now - 8 * day, now - 8 * day + 100, "expired")

        val b = AdminQueries.browserOverview(now)
        assertEquals(2L, b.todayCount)
        assertEquals(1L, b.activeCount)
        assertEquals(1.0 / 3.0, b.failureRate7d, 1e-9)
    }

    @Test
    fun `browserOverview empty db is zeros`() = runBlocking {
        TestDb.init(BrowserSessions)
        val b = AdminQueries.browserOverview(now)
        assertEquals(0L, b.todayCount)
        assertEquals(0L, b.activeCount)
        assertEquals(0.0, b.failureRate7d, 0.0)
    }

    // ── M2: llmCalls 流水查询 ──────────────────────────────────

    @Test
    fun `llmCalls returns rows desc with masked email`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs)
        account(1, "alice@x.com", todayStart - 5 * day)
        account(2, "bob@x.com", todayStart + 10)
        logRow(1, "deepseek-chat", "CLOUDFLARE", 100, 50, 150, 1.0, 1024, "ok", todayStart + 1000, latencyMs = 200)
        logRow(1, "deepseek-chat", "CLOUDFLARE", null, null, null, 0.0, 0, "blocked_quota", todayStart + 2000)
        logRow(2, "kimi-k2.6", "TOKENHUB", 10, 5, 15, 0.5, 100, "ok", todayStart + 3000)

        val rows = AdminQueries.llmCalls(before = null, limit = 50)
        assertEquals(3, rows.size)
        // id 自增 → id 倒序 = 后插入在前
        assertTrue(rows[0].id > rows[1].id && rows[1].id > rows[2].id)
        val kimi = rows[0]
        assertEquals("kimi-k2.6", kimi.model)
        assertEquals("TOKENHUB", kimi.provider)
        assertEquals(2, kimi.accountId)
        assertEquals("b***@x.com", kimi.emailMasked)
        assertEquals(10, kimi.promptTokens)
        assertEquals(5, kimi.completionTokens)
        assertEquals(0.5, kimi.costCny, 0.000001)
        assertEquals("ok", kimi.status)
        val blocked = rows[1]
        assertEquals("blocked_quota", blocked.status)
        assertEquals("a***@x.com", blocked.emailMasked)
        assertNull(blocked.promptTokens)
        assertEquals(200, rows[2].latencyMs)
    }

    @Test
    fun `llmCalls before cursor paginates without overlap`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs)
        account(1, "a@x.com", todayStart - day)
        repeat(55) { i ->
            logRow(1, "deepseek-chat", "CLOUDFLARE", 1, 1, 2, 0.1, 10, "ok", todayStart + i)
        }

        val page1 = AdminQueries.llmCalls(before = null, limit = 50)
        assertEquals(50, page1.size)
        val page2 = AdminQueries.llmCalls(before = page1.last().id, limit = 50)
        assertEquals(5, page2.size)

        // 不重不漏：两页并集 = 全部 55 条
        val allIds = (page1 + page2).map { it.id }
        assertEquals(55, allIds.distinct().size)
        // before = 严格小于：第二页全部 id < 第一页末行 id
        assertTrue(page2.all { it.id < page1.last().id })
        // 各页内部均 id 倒序
        assertEquals(page1.map { it.id }, page1.map { it.id }.sortedDescending())
        assertEquals(page2.map { it.id }, page2.map { it.id }.sortedDescending())
        // 第三页为空
        assertTrue(AdminQueries.llmCalls(before = page2.last().id, limit = 50).isEmpty())
    }

    @Test
    fun `llmCalls filters by status and provider and accountId`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs)
        account(1, "a@x.com", todayStart - day)
        account(2, "b@x.com", todayStart)
        logRow(1, "deepseek-chat", "CLOUDFLARE", 1, 1, 2, 0.1, 10, "ok", todayStart + 1)
        logRow(1, "deepseek-chat", "CLOUDFLARE", null, null, null, 0.0, 0, "upstream_error", todayStart + 2)
        logRow(1, "kimi-k2.6", "TOKENHUB", 1, 1, 2, 0.1, 10, "ok", todayStart + 3)
        logRow(2, "deepseek-chat", "CLOUDFLARE", 1, 1, 2, 0.1, 10, "ok", todayStart + 4)

        val errors = AdminQueries.llmCalls(before = null, status = "upstream_error")
        assertEquals(1, errors.size)
        assertEquals("upstream_error", errors[0].status)

        val tokenHub = AdminQueries.llmCalls(before = null, provider = "TOKENHUB")
        assertEquals(1, tokenHub.size)
        assertEquals("kimi-k2.6", tokenHub[0].model)

        val deepseek = AdminQueries.llmCalls(before = null, model = "deepseek-chat")
        assertEquals(3, deepseek.size)

        val account2 = AdminQueries.llmCalls(before = null, accountId = 2)
        assertEquals(1, account2.size)
        assertEquals(2, account2[0].accountId)

        // 组合筛选：账号 1 + ok + CLOUDFLARE → 仅第一条
        val combo = AdminQueries.llmCalls(before = null, status = "ok", provider = "CLOUDFLARE", accountId = 1)
        assertEquals(1, combo.size)
        assertEquals("ok", combo[0].status)
        assertEquals("CLOUDFLARE", combo[0].provider)

        // 无命中
        assertTrue(AdminQueries.llmCalls(before = null, status = "blocked_rate").isEmpty())
    }

    @Test
    fun `llmCalls soft-deleted account yields null emailMasked`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs)
        account(1, "alice@x.com", todayStart - day)
        deletedAccount(2, "gone@x.com", todayStart - day)
        logRow(1, "deepseek-chat", "CLOUDFLARE", 1, 1, 2, 0.1, 10, "ok", todayStart + 1)
        logRow(2, "deepseek-chat", "CLOUDFLARE", 1, 1, 2, 0.1, 10, "ok", todayStart + 2)

        val rows = AdminQueries.llmCalls(before = null)
        assertEquals(2, rows.size)
        // join null（账号已注销）→ 查询层给 null，「已注销」由视图层渲染
        assertNull(rows.first { it.accountId == 2 }.emailMasked)
        assertEquals("a***@x.com", rows.first { it.accountId == 1 }.emailMasked)
    }

    // ── M2: Agent 会话/事件回放查询 ──────────────────────────

    @Test
    fun `agentSessions paged desc with eventCount and user label`() = runBlocking {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        account(1, "alice@x.com", todayStart - day)                 // tokenHash = "hash1"
        deletedAccount(2, "gone@x.com", todayStart - day)           // tokenHash = "hash2"
        agentSession("sess-a1", "hash1", status = "finished", createdAt = todayStart, updatedAt = todayStart + 1000)
        agentSession("sess-b2", "abcdef1234567890deadbeef", createdAt = todayStart + 10, updatedAt = todayStart + 20)
        agentSession("sess-c3", "hash2", createdAt = todayStart + 20, updatedAt = todayStart + 30)
        agentEvent("sess-a1", "message", "user", "user", AgentEventPayloadCodec.encode(AgentEventPayload.Message("hi")), todayStart + 100)
        agentEvent("sess-a1", "finish", "agent", "assistant", AgentEventPayloadCodec.encode(AgentEventPayload.Finish("done")), todayStart + 900)
        agentEvent("sess-b2", "message", "user", "user", AgentEventPayloadCodec.encode(AgentEventPayload.Message("yo")), todayStart + 15)

        // seq 自增：a1=1, b2=2, c3=3 → 倒序 c3, b2, a1
        val page1 = AdminQueries.agentSessions(beforeSeq = null, limit = 2)
        assertEquals(2, page1.size)
        // 软删除账号按未关联处理 → 回落 tokenHash 前 8
        assertEquals("sess-c3", page1[0].id)
        assertEquals("hash2".take(8), page1[0].userLabel)
        assertEquals(0L, page1[0].eventCount)
        assertNull(page1[0].lastEventAt)
        // 无关联账号 → tokenHash 前 8
        assertEquals("sess-b2", page1[1].id)
        assertEquals("abcdef12", page1[1].userLabel)
        assertEquals(1L, page1[1].eventCount)
        assertEquals(todayStart + 15, page1[1].lastEventAt)

        // beforeSeq = 严格小于：第二页只剩 a1，不重不漏
        val page2 = AdminQueries.agentSessions(beforeSeq = page1.last().seq, limit = 2)
        assertEquals(1, page2.size)
        val a1 = page2[0]
        assertEquals("sess-a1", a1.id)
        assertEquals("a***@x.com", a1.userLabel) // 关联到账号 → 脱敏邮箱
        assertEquals("finished", a1.status)
        assertEquals("gateway", a1.channelKind)
        assertEquals("kimi-k2.6", a1.model)
        assertEquals(2L, a1.eventCount)
        assertEquals(todayStart + 900, a1.lastEventAt)
        assertEquals(todayStart, a1.createdAt)
        assertEquals(todayStart + 1000, a1.updatedAt)
        // 第三页为空
        assertTrue(AdminQueries.agentSessions(beforeSeq = a1.seq, limit = 2).isEmpty())
    }

    @Test
    fun `agentSessions accountId filter resolves via tokenHash`() = runBlocking {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        account(1, "alice@x.com", todayStart - day)   // tokenHash = "hash1"
        account(3, "c@x.com", todayStart - day)   // tokenHash = "hash3"
        agentSession("sess-1a", "hash1", createdAt = todayStart, updatedAt = todayStart)
        agentSession("sess-1b", "hash1", createdAt = todayStart + 1, updatedAt = todayStart + 1)
        agentSession("sess-3a", "hash3", createdAt = todayStart + 2, updatedAt = todayStart + 2)
        agentSession("sess-x", "orphanhash999", createdAt = todayStart + 3, updatedAt = todayStart + 3)

        val acc1 = AdminQueries.agentSessions(beforeSeq = null, accountId = 1)
        assertEquals(2, acc1.size)
        assertTrue(acc1.all { it.userLabel == "a***@x.com" })
        // seq 倒序：1b 在 1a 前
        assertEquals("sess-1b", acc1[0].id)
        assertEquals("sess-1a", acc1[1].id)

        val acc3 = AdminQueries.agentSessions(beforeSeq = null, accountId = 3)
        assertEquals(1, acc3.size)
        assertEquals("sess-3a", acc3[0].id)

        // 未知 accountId → 空列表
        assertTrue(AdminQueries.agentSessions(beforeSeq = null, accountId = 999).isEmpty())
    }

    @Test
    fun `agentSessionEvents after cursor asc and payload decoded`() = runBlocking {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        agentSession("sess-ev", "hash9", createdAt = todayStart, updatedAt = todayStart)
        agentEvent("sess-ev", "message", "user", "user", AgentEventPayloadCodec.encode(AgentEventPayload.Message("查一下照片")), todayStart)
        agentEvent("sess-ev", "action", "agent", "assistant", AgentEventPayloadCodec.encode(AgentEventPayload.Action("search_media", """{"q":"cat"}""", reversible = false)), todayStart + 10)
        // 孤儿 observation（actionEventId=0，门控拦截痕迹）必须保留
        agentEvent("sess-ev", "observation", "environment", "tool", AgentEventPayloadCodec.encode(AgentEventPayload.Observation(actionEventId = 0, content = "error: rejected by gate")), todayStart + 20)
        // 别的会话事件不混入
        agentEvent("sess-other", "message", "user", "user", AgentEventPayloadCodec.encode(AgentEventPayload.Message("other")), todayStart + 30)

        val events = AdminQueries.agentSessionEvents("sess-ev")
        assertEquals(3, events.size)
        // id 升序
        assertEquals(events.map { it.id }, events.map { it.id }.sorted())
        val action = events[1]
        assertEquals("action", action.type)
        assertEquals("agent", action.source)
        assertEquals("assistant", action.role)
        val actionPayload = action.payload as AgentEventPayload.Action
        assertEquals("search_media", actionPayload.tool)
        assertEquals("""{"q":"cat"}""", actionPayload.argsJson)
        // decode 成功 → payloadRaw 保持 null（不复制大文本）
        assertNull(action.payloadRaw)
        // 孤儿 observation 保留且 decode 为 Observation(actionEventId=0)
        val orphan = events[2].payload as AgentEventPayload.Observation
        assertEquals(0L, orphan.actionEventId)
        assertEquals("error: rejected by gate", orphan.content)

        // after 游标 = 严格大于：跳过第一条
        val afterFirst = AdminQueries.agentSessionEvents("sess-ev", after = events[0].id)
        assertEquals(2, afterFirst.size)
        assertEquals("action", afterFirst[0].type)
        // limit 截断
        assertEquals(1, AdminQueries.agentSessionEvents("sess-ev", limit = 1).size)
    }

    @Test
    fun `agentSessionEvents undecodable payload yields null payload`() = runBlocking {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        agentSession("sess-bad", "hash9", createdAt = todayStart, updatedAt = todayStart)
        agentEvent("sess-bad", "action", "agent", "assistant", "{broken", todayStart)

        val events = AdminQueries.agentSessionEvents("sess-bad") // 不抛
        assertEquals(1, events.size)
        assertEquals("action", events[0].type)
        assertNull(events[0].payload)
        // 降级契约：decode 失败时 payloadRaw 带原文供视图截断展示
        assertEquals("{broken", events[0].payloadRaw)
    }

    @Test
    fun `agentSessionSummary returns null for unknown session`() = runBlocking {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        account(1, "alice@x.com", todayStart - day)
        agentSession("sess-sum", "hash1", status = "error", createdAt = todayStart, updatedAt = todayStart + 50)
        agentEvent("sess-sum", "message", "user", "user", AgentEventPayloadCodec.encode(AgentEventPayload.Message("hi")), todayStart + 40)

        assertNull(AdminQueries.agentSessionSummary("no-such-session"))
        val row = AdminQueries.agentSessionSummary("sess-sum")!!
        assertEquals("sess-sum", row.id)
        assertEquals("a***@x.com", row.userLabel)
        assertEquals("error", row.status)
        assertEquals("gateway", row.channelKind)
        assertEquals("kimi-k2.6", row.model)
        assertEquals(1L, row.eventCount)
        assertEquals(todayStart + 40, row.lastEventAt)
        assertEquals(todayStart, row.createdAt)
        assertEquals(todayStart + 50, row.updatedAt)
    }

    private suspend fun account(id: Int, email: String, createdAt: Long) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            Accounts.insert {
                it[Accounts.id] = id
                it[Accounts.email] = email
                it[Accounts.tokenHash] = "hash$id"
                it[Accounts.status] = "active"
                it[Accounts.llmCallsUsed] = 0
                it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = createdAt
            }
        }
    }

    /** 与 AccountService 软删除同形：status=deleted + email 改写 + deletedAt 落时间。 */
    private suspend fun deletedAccount(id: Int, origEmail: String, createdAt: Long) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            Accounts.insert {
                it[Accounts.id] = id
                it[Accounts.email] = "deleted_${id}__${origEmail}"
                it[Accounts.tokenHash] = "hash$id"
                it[Accounts.status] = "deleted"
                it[Accounts.llmCallsUsed] = 0
                it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = createdAt
                it[Accounts.deletedAt] = createdAt + 1000
            }
        }
    }

    private suspend fun logRow(
        accountId: Int,
        model: String,
        provider: String,
        prompt: Int?,
        completion: Int?,
        total: Int?,
        cost: Double,
        bytes: Int,
        status: String,
        createdAt: Long,
        deviceId: String? = null,
        latencyMs: Int? = null,
    ) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = accountId
                it[LlmCallLogs.model] = model
                it[LlmCallLogs.provider] = provider
                it[LlmCallLogs.promptTokens] = prompt
                it[LlmCallLogs.completionTokens] = completion
                it[LlmCallLogs.totalTokens] = total
                it[LlmCallLogs.costCny] = cost
                it[LlmCallLogs.respBytes] = bytes
                it[LlmCallLogs.status] = status
                it[LlmCallLogs.deviceId] = deviceId
                it[LlmCallLogs.latencyMs] = latencyMs
                it[LlmCallLogs.createdAt] = createdAt
            }
        }
    }

    private suspend fun browserSession(sessionId: String, startedAt: Long, endedAt: Long?, outcome: String?) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            BrowserSessions.insert {
                it[BrowserSessions.sessionId] = sessionId
                it[BrowserSessions.tokenHash] = "hash-$sessionId"
                it[BrowserSessions.startedAt] = startedAt
                it[BrowserSessions.endedAt] = endedAt
                it[BrowserSessions.outcome] = outcome
            }
        }
    }

    private suspend fun agentSession(
        uuid: String,
        tokenHash: String,
        status: String = "running",
        channelKind: String = "gateway",
        model: String = "kimi-k2.6",
        createdAt: Long,
        updatedAt: Long,
    ) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AgentSessions.insert {
                it[id] = uuid
                it[AgentSessions.tokenHash] = tokenHash
                it[AgentSessions.status] = status
                it[AgentSessions.channelKind] = channelKind
                it[AgentSessions.model] = model
                it[AgentSessions.createdAt] = createdAt
                it[AgentSessions.updatedAt] = updatedAt
            }
        }
    }

    private suspend fun agentEvent(
        sessionId: String,
        type: String,
        source: String,
        role: String,
        payloadJson: String,
        createdAt: Long,
        parentId: Long? = null,
    ) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AgentEvents.insert {
                it[AgentEvents.sessionId] = sessionId
                it[AgentEvents.type] = type
                it[eventSource] = source
                it[AgentEvents.role] = role
                it[AgentEvents.parentId] = parentId
                it[AgentEvents.payloadJson] = payloadJson
                it[AgentEvents.createdAt] = createdAt
            }
        }
    }

    private suspend fun device(id: Int, deviceId: String, used: Int, createdAt: Long, lastSeenAt: Long) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.insert {
                it[AnonymousDevices.id] = id
                it[AnonymousDevices.deviceId] = deviceId
                it[AnonymousDevices.llmCallsUsed] = used
                it[AnonymousDevices.createdAt] = createdAt
                it[AnonymousDevices.lastSeenAt] = lastSeenAt
            }
        }
    }
}
