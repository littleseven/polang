package com.mamba.picme.server.admin

import com.mamba.picme.server.agent.AgentEventPayload
import com.mamba.picme.server.agent.SkillCenter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminViewsTest {

    private fun rangeFixture(days: List<DayBucket> = emptyList(), byModel: List<DimStat> = emptyList()): RangeStats =
        RangeStats(days, byModel, emptyList(), emptyList(), emptyList(), LatencyStats(0, 0, 0),
            DayBucket("合计", 0, 0, 0, 0, 0, 0.0, 0, 0))

    @Test
    fun `overview page renders delta cards trend chart and model share bars`() {
        val ov = OverviewRow(2L, 1L, 5L, 1234L, 1.5, 4096L, 1L, 0L, 0L, 0L, 0.0)
        val range = rangeFixture(
            days = listOf(DayBucket("2026-07-12", 5L, 1L, 600L, 634L, 1234L, 1.5, 4096L, 0L)),
            byModel = listOf(DimStat("glm-5.2", 3L, 1000L, 5.0)),
        )
        val html = AdminViews.overviewPage(ov, range, days = 7, metric = "calls", browser = BrowserOverview(3L, 1L, 0.125))
        assertTrue(html.contains("今日调用"))
        assertTrue(html.contains("累计"))
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("模型 Top"))
        assertTrue(html.contains("share-bar-fill"))
        assertTrue(html.contains("Browser sessions: today 3 · active 1 · 7d failure 12.5%"))
    }

    @Test
    fun `users page lists emails, detail links and masked api tokens`() {
        val rows = listOf(
            UserRow(1, "a@x.com", "active", 0L, 3L, 100L, 0.5, null, "picm••••wxyz", true, "device••••1234", "android"),
            UserRow(2, "b@x.com", "active", 0L, 0L, 0L, 0.0, null, "—", false, "—", "—"),
        )
        val html = AdminViews.usersPage(rows, devicesCount = 0L)
        assertTrue(html.contains("a@x.com"))
        assertTrue(html.contains("/admin/users/1"))
        // API Token 列：有明文者显示掩码 + 复制按钮；原用量列改名「Token 用量」消歧
        assertTrue(html.contains("API Token"))
        assertTrue(html.contains("picm••••wxyz"))
        assertTrue(html.contains("tokCopy(1, this)"))
        assertTrue(html.contains("Token 用量"))
        assertTrue(html.contains("Device ID"))
        assertTrue(html.contains("平台"))
        assertTrue(html.contains("device••••1234"))
        assertTrue(html.contains("android"))
        assertTrue(html.contains("未注册设备")) // 二级 Tab 出现
    }

    @Test
    fun `login page has a password form`() {
        val html = AdminViews.loginPage(failed = true)
        assertTrue(html.contains("<form"))
        assertTrue(html.contains("name=\"password\""))
        assertTrue(html.contains("密码错误"))
    }

    @Test
    fun `traffic page renders range tabs detail table with total row and top lists`() {
        val range = RangeStats(
            days = listOf(DayBucket("2026-07-12", 1L, 0L, 10L, 5L, 15L, 0.1, 100L, 0L)),
            byModel = listOf(DimStat("deepseek-chat", 1L, 15L, 0.1)),
            byProvider = listOf(DimStat("CLOUDFLARE", 1L, 15L, 0.1)),
            topUsers = listOf(TopStat(1, "a***@x.com", 1L, 15L, 0.1)),
            topDevices = emptyList(),
            latency = LatencyStats(1, 120, 120),
            totals = DayBucket("合计", 1L, 0L, 10L, 5L, 15L, 0.1, 100L, 0L),
        )
        val html = AdminViews.trafficPage(range, days = 30, metric = "calls")
        assertTrue(html.contains("每日明细"))
        assertTrue(html.contains("合计"))
        assertTrue(html.contains("2026-07-12"))
        assertTrue(html.contains("构成"))
        assertTrue(html.contains("异常 Top"))
        assertTrue(html.contains("blocked 率"))
    }

    @Test
    fun `overview page renders sub-cent cost with precision not zero`() {
        // 今日成本 0.0044 元：%.2f 会显示 "0.00"，计费看起来没生效
        val ov = OverviewRow(1L, 0L, 5L, 7944L, 0.0044, 819L, 0L, 0L, 0L, 0L, 0.0)
        val html = AdminViews.overviewPage(ov, rangeFixture(), days = 7, metric = "calls", browser = BrowserOverview(0L, 0L, 0.0))
        assertTrue("sub-cent cost must not round to 0.00", html.contains("0.0044"))
    }

    @Test
    fun `devices page lists masked ids quota and delete action`() {
        val rows = listOf(
            DeviceRow(1, "abcdef••••7890", 5, 1_700_000_000_000L, 1_700_000_001_000L, "android"),
            DeviceRow(2, "zzzzzz••••1111", 100, 1_700_000_000_000L, 1_700_000_002_000L, "ios"),
        )
        val html = AdminViews.devicesPage(rows, usersCount = 3L, guestLimit = 100)
        assertTrue(html.contains("未注册设备"))
        assertTrue(html.contains("注册用户 (3)")) // 二级 Tab 计数
        assertTrue(html.contains("未注册设备 (2)")) // 二级 Tab 计数
        assertTrue(html.contains("abcdef••••7890"))
        assertTrue(html.contains("devCopy(1, this)"))
        assertTrue(html.contains("/admin/devices/1/delete"))
        assertTrue(html.contains("5 / 100"))
        assertTrue(html.contains("100 / 100")) // 超额行
        assertTrue(html.contains("btn-danger")) // 删除按钮
        assertTrue(html.contains("平台")) // 表头
        assertTrue(html.contains("android"))
        assertTrue(html.contains("ios"))
    }

    @Test
    fun `devices page renders platform filter tabs and highlights active`() {
        val rows = listOf(
            DeviceRow(1, "abcdef••••7890", 5, 1L, 2L, "android"),
        )
        // no filter → "全部" active
        val htmlAll = AdminViews.devicesPage(rows, usersCount = 1L, guestLimit = 100)
        assertTrue(htmlAll.contains("""href="/admin/devices" class="ctrl active">全部"""))
        assertTrue(htmlAll.contains("/admin/devices?platform=android"))
        assertTrue(htmlAll.contains("/admin/devices?platform=ios"))

        // ios filter → "iOS" active
        val htmlIos = AdminViews.devicesPage(rows, usersCount = 1L, guestLimit = 100, platformFilter = "ios")
        assertTrue(htmlIos.contains("""href="/admin/devices?platform=ios" class="ctrl active">iOS"""))
    }

    @Test
    fun `devices page shows dash for null platform`() {
        val rows = listOf(
            DeviceRow(1, "abcdef••••7890", 5, 1L, 2L, null),
        )
        val html = AdminViews.devicesPage(rows, usersCount = 0L, guestLimit = 100)
        assertTrue(html.contains("—"))
    }

    // ---------- M2 管理后台推理追溯：调用流水 / Agent 会话回放 ----------

    private fun llmRow(
        id: Long,
        emailMasked: String? = "a***@x.com",
        model: String = "glm-5.2",
        provider: String = "CLOUDFLARE",
        status: String = "ok",
    ) = LlmCallListRow(
        id = id, accountId = 7, emailMasked = emailMasked, model = model, provider = provider,
        promptTokens = 100, completionTokens = 50, cachedTokens = 10, costCny = 0.0044,
        status = status, latencyMs = 1234, platform = "android", createdAt = 1_700_000_000_000L + id,
    )

    private fun sessionRow(
        id: String = "abcdef1234567890",
        status: String = "running",
        lastEventAt: Long? = System.currentTimeMillis() - 31 * 60_000L,
        createdAt: Long = 1_700_000_000_000L,
        seq: Long = 99,
    ) = AgentSessionRow(
        id = id, userLabel = "a***@x.com", status = status, channelKind = "gateway",
        model = "glm-5.2", eventCount = 12, lastEventAt = lastEventAt,
        createdAt = createdAt, updatedAt = 1_700_000_100_000L, seq = seq,
    )

    private fun eventRow(
        id: Long,
        type: String,
        payload: AgentEventPayload?,
        role: String = "assistant",
        payloadRaw: String? = null,
        createdAt: Long = 1_700_000_000_000L + id * 1000,
    ) = AgentEventRow(
        id = id, type = type, source = "agent", role = role, parentId = null,
        payload = payload, payloadRaw = payloadRaw, createdAt = createdAt,
    )

    @Test
    fun `llm calls page renders rows status colors user links and pagination`() {
        val rows = listOf(
            llmRow(id = 101),
            llmRow(id = 100, emailMasked = null, status = "upstream_error"),
            llmRow(id = 99, status = "blocked_quota"),
        )
        val html = AdminViews.llmCallsPage(rows, status = null, provider = null, model = null, accountId = null, nextBefore = 99)
        assertTrue(html.contains("调用流水"))
        assertTrue(html.contains("a***@x.com"))
        assertTrue(html.contains("href=\"/admin/users/7\""))
        assertTrue(html.contains("已注销")) // emailMasked=null → 已注销不崩溃
        assertTrue(html.contains("glm-5.2"))
        assertTrue(html.contains("CLOUDFLARE"))
        assertTrue(html.contains("st-ok"))
        assertTrue(html.contains("st-error"))
        assertTrue(html.contains("st-blocked"))
        assertTrue(html.contains("0.0044"))
        assertTrue(html.contains("1234"))
        assertTrue(html.contains("android"))
        // 分页链接带齐筛选参数（此处全空，至少带 before）
        assertTrue(html.contains("href=\"/admin/llm/calls?before=99"))
        assertTrue(html.contains("更早"))
    }

    @Test
    fun `llm calls page escapes user controlled model name`() {
        val rows = listOf(llmRow(id = 1, model = "<script>alert(1)</script>"))
        val html = AdminViews.llmCallsPage(rows, status = null, provider = null, model = null, accountId = null, nextBefore = null)
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
    }

    @Test
    fun `llm calls page keeps filters in pagination link and shows account filter chip`() {
        val html = AdminViews.llmCallsPage(
            listOf(llmRow(id = 50)), status = "ok", provider = "TOKENHUB", model = "deep seek",
            accountId = 7, nextBefore = 50,
        )
        assertTrue(html.contains("按用户筛选中"))
        // 清除链接回到不带 account_id 的列表
        assertTrue(html.contains("href=\"/admin/llm/calls?status=ok&amp;provider=TOKENHUB&amp;model=deep+seek\""))
        // 分页链接带齐全部筛选参数（model 需 URL 编码）
        assertTrue(
            html.contains("before=50") && html.contains("status=ok") &&
                html.contains("provider=TOKENHUB") && html.contains("model=deep+seek") && html.contains("account_id=7"),
        )
        // 筛选表单回显当前值
        assertTrue(html.contains("value=\"deep seek\""))
        assertTrue(html.contains("selected"))
    }

    @Test
    fun `agent sessions page renders rows stalled badge and before_seq pagination`() {
        val now = System.currentTimeMillis()
        val rows = listOf(
            sessionRow(id = "abcdef1234567890", status = "running", lastEventAt = now - 31 * 60_000L, seq = 99),
            sessionRow(id = "bbbbbbbb99999999", status = "running", lastEventAt = now - 60_000L, seq = 98),
            sessionRow(id = "cccccccc88888888", status = "finished", lastEventAt = null, seq = 97),
        )
        val html = AdminViews.agentSessionsPage(rows, accountId = null, nextBeforeSeq = 97)
        assertTrue(html.contains("href=\"/admin/agent/sessions/abcdef1234567890\""))
        assertTrue(html.contains("abcdef12")) // 短码
        assertTrue(html.contains("a***@x.com"))
        assertTrue(html.contains("gateway"))
        assertTrue(html.contains("glm-5.2"))
        // stalled 仅出现在假 running（>30min 无事件）那一行
        assertTrue("stalled badge expected exactly once", html.split("stalled").size - 1 == 1)
        // 有效状态直显（2026-11-07）：stalled 行状态列直显 stalled、不再显示 running 徽标文本；
        // running 文本仅属真实活跃行（≤30min 有事件）
        assertTrue("stalled replaces running in status cell", html.split(">stalled<").size - 1 == 1)
        assertTrue("running badge only for the active row", html.split(">running<").size - 1 == 1)
        assertTrue(html.contains("href=\"/admin/agent/sessions?before_seq=97\""))
    }

    @Test
    fun `agent sessions page does not stall-badge young running session without events`() {
        val now = System.currentTimeMillis()
        val rows = listOf(
            // 刚建会话、首轮事件未落库（lastEventAt=null，createdAt 在 30min 内）→ 不标 stalled
            sessionRow(id = "ddddddddd0000001", status = "running", lastEventAt = null, createdAt = now - 5 * 60_000L, seq = 100),
            // 废弃 running（lastEventAt=null，createdAt 超 30min）→ 仍标 stalled
            sessionRow(id = "eeeeeeeee0000002", status = "running", lastEventAt = null, createdAt = now - 45 * 60_000L, seq = 99),
        )
        val html = AdminViews.agentSessionsPage(rows, accountId = null, nextBeforeSeq = null)
        assertTrue("stalled badge expected exactly once (abandoned only)", html.split("stalled").size - 1 == 1)
    }

    @Test
    fun `agent session detail shows stalled as effective status`() {
        val now = System.currentTimeMillis()
        val stalled = AdminViews.agentSessionDetailPage(
            sessionRow(status = "running", lastEventAt = now - 31 * 60_000L), emptyList(), nextAfter = null,
        )
        assertTrue(stalled.contains(">stalled<"))
        assertFalse(stalled.contains(">running<"))
        val active = AdminViews.agentSessionDetailPage(
            sessionRow(status = "running", lastEventAt = now - 60_000L), emptyList(), nextAfter = null,
        )
        assertTrue(active.contains(">running<"))
        assertFalse(active.contains(">stalled<"))
    }

    @Test
    fun `agent session detail renders messages actions observations with delta ms and escaping`() {
        val t0 = 1_700_000_000_000L
        val events = listOf(
            eventRow(1, "message", AgentEventPayload.Message("你好 <b>世界</b>"), role = "user", createdAt = t0),
            eventRow(2, "action", AgentEventPayload.Action("search_media", "{\"q\":\"<script>alert(1)</script>\"}", true), createdAt = t0 + 1000),
            eventRow(3, "observation", AgentEventPayload.Observation(2, "ok 3 条"), role = "tool", createdAt = t0 + 1500),
            eventRow(4, "observation", AgentEventPayload.Observation(0, "error: gated by validator"), role = "tool", createdAt = t0 + 2500),
            eventRow(5, "observation", AgentEventPayload.Observation(999, "no parent on this page"), role = "tool", createdAt = t0 + 3000),
        )
        val html = AdminViews.agentSessionDetailPage(sessionRow(), events, nextAfter = 5)
        // 角色分色块
        assertTrue(html.contains("ev-user"))
        assertTrue(html.contains("ev-assistant") || html.contains("ev-action"))
        // 转义铁律：消息与 argsJson 均不出现原始尖括号载荷
        assertFalse(html.contains("<b>世界</b>"))
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;b&gt;世界&lt;/b&gt;"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        // action 工具名 + 折叠详情
        assertTrue(html.contains("search_media"))
        assertTrue(html.contains("<details>"))
        // Δms：相邻事件差值
        assertTrue(html.contains("+1000ms"))
        assertTrue(html.contains("+500ms"))
        // 孤儿 observation（actionEventId=0 / 页内无配对）徽标，照常成条
        assertTrue(html.contains("error: gated by validator"))
        assertTrue(html.contains("no parent on this page"))
        assertTrue("orphan badge expected twice", html.split("门控拦截").size - 1 == 2)
        // error 前缀标红
        assertTrue(html.contains("ev-err"))
        // 底部翻页（事件 id 升序，after 指向更新的事件）
        assertTrue(html.contains("href=\"/admin/agent/sessions/abcdef1234567890?after=5\""))
        assertTrue(html.contains("后续事件"))
        assertFalse(html.contains("更早事件"))
    }

    @Test
    fun `agent session detail renders html card summary only and finish banner and condensation`() {
        val t0 = 1_700_000_000_000L
        val events = listOf(
            eventRow(1, "html_card", AgentEventPayload.HtmlCard("<div>secret-html-body</div>", "照片墙卡片"), createdAt = t0),
            eventRow(2, "condensation", AgentEventPayload.Condensation("前文压缩摘要", 1), createdAt = t0 + 100),
            eventRow(3, "finish", AgentEventPayload.Finish("done"), createdAt = t0 + 200),
        )
        val html = AdminViews.agentSessionDetailPage(sessionRow(), events, nextAfter = null)
        // H5 卡只显示 summary + 字符数，绝不渲染 HTML 本体
        assertTrue(html.contains("照片墙卡片"))
        assertTrue(html.contains("字符"))
        assertFalse(html.contains("secret-html-body"))
        // condensation 摘要行
        assertTrue(html.contains("前文压缩摘要"))
        // finish 通栏横幅
        assertTrue(html.contains("ev-finish"))
        assertTrue(html.contains("done"))
    }

    @Test
    fun `agent session detail degrades undecodable payload with truncated escaped raw`() {
        val raw = "x".repeat(500)
        val events = listOf(
            eventRow(1, "action", payload = null, payloadRaw = raw),
        )
        val html = AdminViews.agentSessionDetailPage(sessionRow(), events, nextAfter = null)
        assertTrue(html.contains("无法解析的事件载荷"))
        assertTrue(html.contains("ev-warn"))
        // 截断 200 字符：不出现完整 500 x
        assertTrue(html.contains("x".repeat(200)))
        assertFalse(html.contains("x".repeat(201)))
    }

    @Test
    fun `nav bar links to llm calls and agent sessions`() {
        val html = AdminViews.usersPage(emptyList(), devicesCount = 0L)
        assertTrue(html.contains("href=\"/admin/llm/calls\""))
        assertTrue(html.contains("href=\"/admin/agent/sessions\""))
    }

    @Test
    fun `user detail page links to llm calls and agent sessions filtered by account`() {
        val d = UserDetail(
            id = 42, email = "a@x.com", status = "active", createdAt = 1_700_000_000_000L,
            llmCallsUsed = 3, llmCallsLimit = 100, calls = 3L, totalTokens = 1000L,
            cost = 0.5, blocked = 0L, bytes = 2048L, lastActive = null,
        )
        val html = AdminViews.userDetailPage(d, emptyList())
        assertTrue(html.contains("href=\"/admin/llm/calls?account_id=42\""))
        assertTrue(html.contains("href=\"/admin/agent/sessions?account_id=42\""))
    }

    // ── Skill 中心（2026-11-07 v2：统计行 / 双栏编辑 / 预览页三 Tab + markdown 渲染）──

    private fun skillFixture(
        name: String = "html-card",
        description: String = "当需要生成任务卡/结果卡 HTML 时，先加载本技能",
        body: String = "# html-card 任务卡生成\n\n## 何时使用\n用户需要生成任务卡 HTML 时。\n\n## 步骤\n1. 取 UserTask JSON\n2. 套模板\n",
        updatedAtMs: Long = 1_760_000_000_000L,
        rawChars: Int = 1204,
    ) = SkillCenter.SkillDetail(
        meta = SkillCenter.SkillMeta(name, description),
        body = body, updatedAtMs = updatedAtMs, rawChars = rawChars,
    )

    @Test
    fun `skills list page renders stats row table with preview link and prompt section`() {
        val html = AdminViews.skillsPage(
            skills = listOf(skillFixture(), skillFixture("tag-report", rawChars = 862)),
            promptSection = "可用技能（SKILL）清单……\n- html-card: 当需要生成任务卡…",
        )
        // 统计行
        assertTrue(html.contains("技能数"))
        assertTrue(html.contains("2"))
        assertTrue(html.contains("总字符"))
        assertTrue(html.contains("2,066")) // 1204 + 862 千分位
        assertTrue(html.contains("最近更新"))
        // 表格 + 预览入口
        assertTrue(html.contains("html-card"))
        assertTrue(html.contains("href=\"/admin/skills/html-card/preview\""))
        assertTrue(html.contains("预览"))
        assertTrue(html.contains("共 2 个技能"))
        // LLM 视角清单段
        assertTrue(html.contains("system prompt 技能清单段"))
        assertTrue(html.contains("- html-card: 当需要生成任务卡…"))
    }

    @Test
    fun `skills edit page renders two-column layout with live preview hooks and delete button`() {
        val html = AdminViews.skillEditPage(existing = skillFixture())
        // 双栏 + 左表单
        assertTrue(html.contains("skills-layout"))
        assertTrue(html.contains("SKILL.md 表单"))
        assertTrue(html.contains("修改即重命名")) // 名称可改（目录迁移），不再锁定
        assertTrue(!html.contains("readonly=\"readonly\""))
        // 右栏实时预览：清单行 + markdown 渲染初值（commonmark 服务端渲染）
        assertTrue(html.contains("实时预览 · LLM 视角"))
        assertTrue(html.contains("id=\"manifest-line\""))
        assertTrue(html.contains("id=\"md-live\""))
        assertTrue(html.contains("<h1>")) // body 的 # 标题已渲染
        assertTrue(html.contains("<h2>"))
        // JS：计数 + 联动
        assertTrue(html.contains("id=\"char-counter\""))
        assertTrue(html.contains("32,000")) // MAX_CONTENT_CHARS 注入
        // 编辑态删除按钮
        assertTrue(html.contains("删除技能"))
        assertTrue(html.contains("/admin/skills/html-card/delete"))
    }

    @Test
    fun `skills edit page for new skill has no delete and renders default body preview`() {
        val html = AdminViews.skillEditPage(existing = null)
        assertTrue(html.contains("新增技能"))
        assertTrue(!html.contains("删除技能"))
        assertTrue(html.contains("# 技能标题")) // 默认正文模板进 textarea
    }

    @Test
    fun `skills preview page llm tab shows both layers`() {
        val html = AdminViews.skillPreviewPage(skillFixture(), tab = "llm")
        assertTrue(html.contains("预览技能 · html-card"))
        assertTrue(html.contains("热更新已生效"))
        assertTrue(html.contains("① system prompt 清单段"))
        assertTrue(html.contains("- html-card: 当需要生成任务卡"))
        assertTrue(html.contains("② load_skill"))
        assertTrue(html.contains("取 UserTask JSON"))
        // 三 Tab 链接
        assertTrue(html.contains("?tab=llm"))
        assertTrue(html.contains("?tab=raw"))
        assertTrue(html.contains("?tab=rendered"))
    }

    @Test
    fun `skills preview page raw tab reconstructs skill md source`() {
        val html = AdminViews.skillPreviewPage(skillFixture(), tab = "raw")
        assertTrue(html.contains("原文 · SKILL.md"))
        assertTrue(html.contains("---"))
        assertTrue(html.contains("name: html-card"))
        assertTrue(html.contains("description: 当需要生成任务卡"))
    }

    @Test
    fun `skills preview page rendered tab renders markdown to html`() {
        val html = AdminViews.skillPreviewPage(skillFixture(), tab = "rendered")
        assertTrue(html.contains("渲染预览"))
        assertTrue(html.contains("<h1>html-card 任务卡生成</h1>"))
        assertTrue(html.contains("<ol>")) // 有序列表 1. 2.
    }

    @Test
    fun `renderMarkdown escapes raw html in skill body`() {
        val rendered = AdminViews.renderMarkdown("# t\n\n<script>alert(1)</script>")
        assertTrue(!rendered.contains("<script>"))
        assertTrue(rendered.contains("&lt;script&gt;"))
    }

    @Test
    fun `skills preview page falls back to llm tab on unknown tab`() {
        val html = AdminViews.skillPreviewPage(skillFixture(), tab = "evil")
        // 属性序不保证，拆两条断言：llm 高亮 + 无 evil 透传
        assertTrue(html.contains("subtab active"))
        assertTrue(html.contains("\"/admin/skills/html-card/preview?tab=llm\""))
        assertTrue(!html.contains("?tab=evil"))
    }

}
