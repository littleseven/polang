package com.mamba.picme.server.admin

import com.mamba.picme.server.appJson
import com.mamba.picme.server.agent.AgentEventPayload
import com.mamba.picme.server.agent.OpenUiStringScan
import com.mamba.picme.server.agent.SkillCenter
import com.mamba.picme.server.analytics.formatCostCny
import com.mamba.picme.server.auth.AiEngineerWhitelistService
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.issue.IssueReportService
import com.mamba.picme.server.llm.ChannelBalanceService
import com.mamba.picme.server.llm.ChannelRow
import com.mamba.picme.server.llm.renderModelMapLines
import kotlinx.html.FlowContent
import kotlinx.html.FormEncType
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.body
import kotlinx.html.ButtonType
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.hr
import kotlinx.html.html
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.html.stream.createHTML
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * 服务端渲染 HTML 页面（kotlinx.html）。无前端构建、无 CDN，内联 SVG 图表。
 * 每个 page 函数返回完整 HTML 字符串，由 AdminRoutes 用 respondText 输出。
 */
object AdminViews {

    /** stalled 判定阈值：running 会话最后事件距今超过 30min（spec §7 假 running 标注口径）。 */
    private const val STALLED_THRESHOLD_MS = 30L * 60 * 1000

    fun loginPage(failed: Boolean = false): String = createHTML().html {
        adminHead("登录 · PoLang 管理后台")
        body {
            div("wrap") {
                h1 { +"PoLang 管理后台" }
                if (failed) p("err") { +"密码错误" }
                form(action = "/admin/login", method = FormMethod.post) {
                    p { textInput(name = "password", classes = "pw") { placeholder = "ADMIN_TOKEN" } }
                    p { input(type = InputType.submit, classes = "btn") { value = "登录" } }
                }
            }
        }
    }

    fun overviewPage(ov: OverviewRow, range: RangeStats, days: Int, metric: String, browser: BrowserOverview): String = createHTML().html {
        adminHead("概览 · PoLang 管理后台")
        body {
            navBar()
            pageControls(metric, listOf("calls" to "调用", "cost" to "成本", "tokens" to "Token"), days, listOf(7, 14), "/admin")
            h1 { +"概览" }
            h2 { +"今日（UTC+8 自然日，带环比）" }
            div("cards") {
                statCardDelta("今日调用", ov.callsToday.toString(), ov.callsToday.toDouble(), ov.callsYest.toDouble(), DeltaPolarity.GOOD_ON_UP)
                statCardDelta("今日成本 ¥", compactCost(ov.costToday), ov.costToday, ov.costYest, DeltaPolarity.BAD_ON_UP)
                statCardDelta("今日 Token", compactCount(ov.tokensToday.toDouble()), ov.tokensToday.toDouble(), ov.tokensYest.toDouble(), DeltaPolarity.GOOD_ON_UP)
                statCardDelta("今日新增用户", ov.newUsersToday.toString(), ov.newUsersToday.toDouble(), ov.newUsersYest.toDouble(), DeltaPolarity.GOOD_ON_UP)
                statCardDelta("今日新增设备", ov.newDevicesToday.toString(), ov.newDevicesToday.toDouble(), ov.newDevicesYest.toDouble(), DeltaPolarity.GOOD_ON_UP)
                statCardDelta("今日 blocked", ov.blockedToday.toString(), ov.blockedToday.toDouble(), ov.blockedYest.toDouble(), DeltaPolarity.BAD_ON_UP)
                statCardDelta("今日 error", ov.errorsToday.toString(), ov.errorsToday.toDouble(), ov.errorsYest.toDouble(), DeltaPolarity.BAD_ON_UP)
            }
            h2 { +"累计" }
            div("cards") {
                statCard("总用户数", ov.totalUsers.toString())
                statCard("总设备数", ov.totalDevices.toString())
                statCard("累计调用", compactCount(ov.totalCalls.toDouble()))
                statCard("累计 Token", compactCount(ov.totalTokens.toDouble()))
                statCard("累计成本 ¥", compactCost(ov.totalCost))
            }
            p("meta") {
                +"Browser sessions: today ${browser.todayCount} · active ${browser.activeCount} · 7d failure ${"%.1f".format(browser.failureRate7d * 100)}%"
            }
            h2 { +"近 $days 天 · ${metricLabel(metric)}" }
            unsafe { raw(svgBars(range.days.map { dayMetricValue(it, metric) }, range.days.map { it.day }, labelFormatter = metricFormatter(metric))) }
            h2 { +"模型 Top（近 $days 天 成本占比）" }
            div("model-top") {
                shareBars(range.byModel, "cost", dimTotal(range.byModel, "cost"))
            }
        }
    }

    fun usersPage(rows: List<UserRow>, devicesCount: Long): String = createHTML().html {
        adminHead("用户 · PoLang 管理后台")
        body {
            navBar()
            userTabs(rows.size.toLong(), devicesCount, "/admin/users")
            h1 { +"用户（${rows.size}）" }
            table {
                tr {
                    th { +"ID" }
                    th { +"邮箱" }
                    th { +"Device ID" }
                    th { +"平台" }
                    th { +"API Token" }
                    th { +"状态" }
                    th { +"注册时间" }
                    th { +"调用" }
                    th { +"Token 用量" }
                    th { +"成本 ¥" }
                    th { +"最后活跃" }
                    th(classes = "col-actions") { +"操作" }
                }
                rows.forEach { u ->
                    tr {
                        td { +u.id.toString() }
                        td { a("/admin/users/${u.id}") { +u.email } }
                        td { +u.deviceIdMasked }
                        td { +u.lastPlatform }
                        td {
                            if (u.hasToken) {
                                span("tok") { +u.apiTokenMasked }
                                +" "
                                button(type = ButtonType.button, classes = "btn-sm tok-copy") {
                                    attributes["onclick"] = "tokCopy(${u.id}, this)"
                                    +"复制"
                                }
                            } else {
                                +"—"
                            }
                        }
                        td { statusBadge(u.status) }
                        td { +fmtTs(u.createdAt) }
                        td { +u.calls.toString() }
                        td { +u.totalTokens.toString() }
                        td { +fmt(u.cost) }
                        td { +(u.lastActive?.let { fmtTs(it) } ?: "—") }
                        td {
                            div("row-actions") {
                                if (u.status == "active") {
                                    form(action = "/admin/users/${u.id}/revoke", method = FormMethod.post, classes = "inline") {
                                        attributes["onsubmit"] = "return confirm('确定禁用该用户？禁用后 token 立即失效。')"
                                        input(type = InputType.submit, classes = "btn-sm") { value = "禁用" }
                                    }
                                } else if (u.status == "revoked") {
                                    form(action = "/admin/users/${u.id}/unrevoke", method = FormMethod.post, classes = "inline") {
                                        input(type = InputType.submit, classes = "btn-sm btn-go") { value = "恢复" }
                                    }
                                }
                                if (u.status != "deleted") {
                                    form(action = "/admin/users/${u.id}/delete", method = FormMethod.post, classes = "inline") {
                                        attributes["onsubmit"] = "return confirm('确定删除该用户及其全部数据？\\n\\n此操作不可恢复，账号与调用日志将被立即物理删除。')"
                                        input(type = InputType.submit, classes = "btn-sm btn-danger") { value = "删除" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            script {
                unsafe {
                    raw(
                        """function tokCopy(id,btn){fetch('/admin/users/'+id+'/token',{credentials:'same-origin'}).then(function(r){return r.json()}).then(function(d){return navigator.clipboard.writeText(d.token)}).then(function(){var o=btn.textContent;btn.textContent='✓';setTimeout(function(){btn.textContent=o},1200)}).catch(function(){btn.textContent='失败';setTimeout(function(){btn.textContent='复制'},1200)})}""",
                    )
                }
            }
        }
    }

    fun devicesPage(
        rows: List<DeviceRow>,
        usersCount: Long,
        guestLimit: Int,
        platformFilter: String? = null,
    ): String = createHTML().html {
        adminHead("未注册设备 · PoLang 管理后台")
        body {
            navBar()
            userTabs(usersCount, rows.size.toLong(), "/admin/devices")
            h1 { +"未注册设备（${rows.size}）" }
            div("page-controls") {
                div("ctrl-group") {
                    val current = platformFilter ?: "all"
                    listOf("all" to "全部", "android" to "Android", "ios" to "iOS").forEach { (v, label) ->
                        val href = if (v == "all") "/admin/devices" else "/admin/devices?platform=$v"
                        a(href, classes = if (v == current) "ctrl active" else "ctrl") { +label }
                    }
                }
            }
            p("meta") { +"按最后活跃倒序;数据量大时仅展示最近 1000 条" }
            if (rows.isEmpty()) {
                div("card apk-empty") {
                    div("apk-empty-text") { +"暂无未注册设备" }
                }
            } else {
                table {
                    tr {
                        th { +"ID" }
                        th { +"Device ID" }
                        th { +"平台" }
                        th { +"额度（已用 / 上限）" }
                        th { +"首次出现" }
                        th { +"最后活跃" }
                        th(classes = "col-actions") { +"操作" }
                    }
                    rows.forEach { d ->
                        tr {
                            td { +d.id.toString() }
                            td {
                                span("tok") { +d.deviceIdMasked }
                                +" "
                                button(type = ButtonType.button, classes = "btn-sm tok-copy") {
                                    attributes["onclick"] = "devCopy(${d.id}, this)"
                                    +"复制"
                                }
                            }
                            td { +(d.platform ?: "—") }
                            td {
                                val text = "${d.llmCallsUsed} / $guestLimit"
                                if (d.llmCallsUsed >= guestLimit) span("err") { +text } else +text
                            }
                            td { +fmtTs(d.createdAt) }
                            td { +fmtTs(d.lastSeenAt) }
                            td {
                                form(action = "/admin/devices/${d.id}/reset-quota", method = FormMethod.post, classes = "inline") {
                                    attributes["onsubmit"] = "return confirm('重置该设备已用额度？')"
                                    input(type = InputType.submit, classes = "btn-sm") { value = "重置" }
                                }
                                form(action = "/admin/devices/${d.id}/delete", method = FormMethod.post, classes = "inline") {
                                    attributes["onsubmit"] = "return confirm('确定删除该设备记录？\\n\\n将清除其访客用量计数,操作不可恢复。')"
                                    input(type = InputType.submit, classes = "btn-sm btn-danger") { value = "删除" }
                                }
                            }
                        }
                    }
                }
                script {
                    unsafe {
                        raw(
                            """function devCopy(id,btn){fetch('/admin/devices/'+id+'/raw',{credentials:'same-origin'}).then(function(r){return r.json()}).then(function(d){return navigator.clipboard.writeText(d.device_id)}).then(function(){var o=btn.textContent;btn.textContent='✓';setTimeout(function(){btn.textContent=o},1200)}).catch(function(){btn.textContent='失败';setTimeout(function(){btn.textContent='复制'},1200)})}""",
                        )
                    }
                }
            }
        }
    }

    fun userDetailPage(d: UserDetail, calls: List<CallRow>): String = createHTML().html {
        adminHead("用户详情 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"${d.email}（#${d.id}）" }
            p("meta") { +"注册于 ${fmtTs(d.createdAt)}" }
            div("actions-bar") {
                if (d.status == "active") {
                    form(action = "/admin/users/${d.id}/revoke", method = FormMethod.post, classes = "inline") {
                        attributes["onsubmit"] = "return confirm('确定禁用该用户？禁用后 token 立即失效。')"
                        input(type = InputType.submit, classes = "btn") { value = "禁用账户" }
                    }
                } else if (d.status == "revoked") {
                    form(action = "/admin/users/${d.id}/unrevoke", method = FormMethod.post, classes = "inline") {
                        input(type = InputType.submit, classes = "btn btn-go") { value = "恢复账户" }
                    }
                }
                if (d.status != "deleted") {
                    form(action = "/admin/users/${d.id}/reset-quota", method = FormMethod.post, classes = "inline") {
                        attributes["onsubmit"] = "return confirm('重置已用额度？\\n\\n仅清零当前计数器（${d.llmCallsUsed}/${d.llmCallsLimit}），历史调用记录保留。')"
                        input(type = InputType.submit, classes = "btn") { value = "重置已用额度" }
                    }
                }
                if (d.status != "deleted") {
                    form(action = "/admin/users/${d.id}/delete", method = FormMethod.post, classes = "inline") {
                        attributes["onsubmit"] = "return confirm('确定删除该用户及其全部数据？\\n\\n此操作不可恢复，账号与调用日志将被立即物理删除。')"
                        input(type = InputType.submit, classes = "btn btn-danger") { value = "删除账户及数据" }
                    }
                }
            }
            div("cards") {
                statCard("状态", d.status)
                statCard("成功调用", d.calls.toString())
                statCard("Token", d.totalTokens.toString())
                statCard("成本 ¥", fmt(d.cost))
                statCard("blocked", d.blocked.toString())
                statCard("出口字节", d.bytes.toString())
            }
            div("card limit-card") {
                div("card-label") { +"额度上限（当前 ${d.llmCallsUsed} / ${d.llmCallsLimit}）" }
                form(action = "/admin/users/${d.id}/limit", method = FormMethod.post, classes = "inline") {
                    input(type = InputType.number, name = "limit") {
                        value = d.llmCallsLimit.toString()
                        attributes["min"] = "0"
                        style = "width:96px"
                    }
                    +" "
                    input(type = InputType.submit, classes = "btn-sm btn-primary") { value = "改上限" }
                }
            }
            h2 {
                +"最近调用（${calls.size}）"
                +" "
                a("/admin/llm/calls?account_id=${d.id}", classes = "meta-inline") { +"调用流水 →" }
                +" "
                a("/admin/agent/sessions?account_id=${d.id}", classes = "meta-inline") { +"Agent 会话 →" }
            }
            table {
                tr {
                    th { +"时间" }
                    th { +"模型" }
                    th { +"Provider" }
                    th { +"Prompt" }
                    th { +"Completion" }
                    th { +"Total" }
                    th { +"Cached" }
                    th { +"成本 ¥" }
                    th { +"字节" }
                    th { +"状态" }
                    th { +"ms" }
                }
                calls.forEach { c ->
                    tr {
                        td { +fmtTs(c.createdAt) }
                        td { +c.model }
                        td { +c.provider }
                        td { +(c.promptTokens?.toString() ?: "—") }
                        td { +(c.completionTokens?.toString() ?: "—") }
                        td { +(c.totalTokens?.toString() ?: "—") }
                        // null = 上游未上报（不可观测）；0 = 上报但未命中
                        td { +(c.cachedTokens?.toString() ?: "—") }
                        td { +fmt(c.costCny) }
                        td { +c.respBytes.toString() }
                        td { +c.status }
                        td { +(c.latencyMs?.toString() ?: "—") }
                    }
                }
            }
        }
    }

    // ---------- M2 管理后台推理追溯：调用流水 / Agent 会话回放（只读） ----------

    fun llmCallsPage(
        rows: List<LlmCallListRow>,
        status: String?,
        provider: String?,
        model: String?,
        accountId: Int?,
        nextBefore: Long?,
    ): String = createHTML().html {
        adminHead("调用流水 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"调用流水（${rows.size}）" }
            form(action = "/admin/llm/calls", method = FormMethod.get, classes = "trace-filter") {
                select {
                    name = "status"
                    option { value = ""; selected = status == null; +"全部状态" }
                    listOf("ok", "upstream_error", "blocked_quota", "blocked_rate").forEach { s ->
                        option { value = s; selected = status == s; +s }
                    }
                }
                select {
                    name = "provider"
                    option { value = ""; selected = provider == null; +"全部渠道" }
                    listOf("CLOUDFLARE", "TOKENHUB").forEach { p ->
                        option { value = p; selected = provider == p; +p }
                    }
                }
                textInput(name = "model") {
                    placeholder = "model 精确匹配"
                    value = model ?: ""
                }
                if (accountId != null) {
                    input(type = InputType.hidden, name = "account_id") { value = accountId.toString() }
                    span("trace-chip") {
                        +"按用户筛选中 #$accountId "
                        a("/admin/llm/calls${queryString("status" to status, "provider" to provider, "model" to model)}") { +"×清除" }
                    }
                }
                input(type = InputType.submit, classes = "btn-sm btn-primary") { value = "筛选" }
            }
            table {
                tr {
                    th { +"时间" }
                    th { +"用户" }
                    th { +"模型" }
                    th { +"渠道" }
                    th { +"Prompt" }
                    th { +"Completion" }
                    th { +"Cached" }
                    th { +"成本 ¥" }
                    th { +"延迟 ms" }
                    th { +"状态" }
                    th { +"平台" }
                }
                rows.forEach { c ->
                    tr {
                        td { +fmtTs(c.createdAt) }
                        td { a("/admin/users/${c.accountId}") { +(c.emailMasked ?: "已注销") } }
                        td { +c.model }
                        td { +c.provider }
                        td { +(c.promptTokens?.toString() ?: "—") }
                        td { +(c.completionTokens?.toString() ?: "—") }
                        // null = 上游未上报（不可观测）；0 = 上报但未命中
                        td { +(c.cachedTokens?.toString() ?: "—") }
                        td { +fmt(c.costCny) }
                        td { +(c.latencyMs?.toString() ?: "—") }
                        td { span("st-badge ${llmStatusClass(c.status)}") { +c.status } }
                        td { +(c.platform ?: "—") }
                    }
                }
            }
            if (nextBefore != null) {
                p("trace-more") {
                    a(
                        "/admin/llm/calls${queryString(
                            "before" to nextBefore.toString(),
                            "status" to status,
                            "provider" to provider,
                            "model" to model,
                            "account_id" to accountId?.toString(),
                        )}",
                    ) { +"更早 50 条 →" }
                }
            }
        }
    }

    fun agentSessionsPage(
        rows: List<AgentSessionRow>,
        accountId: Int?,
        nextBeforeSeq: Long?,
    ): String = createHTML().html {
        adminHead("Agent 会话 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"Agent 会话（${rows.size}）" }
            if (accountId != null) {
                p("trace-filter") {
                    span("trace-chip") {
                        +"按用户筛选中 #$accountId "
                        a("/admin/agent/sessions") { +"×清除" }
                    }
                }
            }
            table {
                tr {
                    th { +"会话" }
                    th { +"用户" }
                    th { +"状态" }
                    th { +"渠道" }
                    th { +"模型" }
                    th { +"事件数" }
                    th { +"创建时间" }
                    th { +"更新时间" }
                }
                rows.forEach { s ->
                    tr {
                        td { a("/admin/agent/sessions/${s.id}") { +s.id.take(8) } }
                        td { +s.userLabel }
                        td {
                            // 假 running 收口（2026-11-07）：stalled 会话状态列直显 stalled，
                            // running 徽标只保留给真实活跃会话（≤30min 有事件）
                            if (isStalled(s)) {
                                span("st-badge st-muted") { +"stalled" }
                            } else {
                                span("st-badge ${sessionStatusClass(s.status)}") { +s.status }
                            }
                        }
                        td { +s.channelKind }
                        td { +s.model }
                        td { +s.eventCount.toString() }
                        td { +fmtTs(s.createdAt) }
                        td { +fmtTs(s.updatedAt) }
                    }
                }
            }
            if (nextBeforeSeq != null) {
                p("trace-more") {
                    a(
                        "/admin/agent/sessions${queryString(
                            "before_seq" to nextBeforeSeq.toString(),
                            "account_id" to accountId?.toString(),
                        )}",
                    ) { +"更早 50 条 →" }
                }
            }
        }
    }

    fun agentSessionDetailPage(
        session: AgentSessionRow,
        events: List<AgentEventRow>,
        nextAfter: Long?,
    ): String = createHTML().html {
        adminHead("会话回放 ${session.id.take(8)} · PoLang 管理后台")
        body {
            navBar()
            h1 { +"会话回放 ${session.id.take(8)}" }
            div("cards") {
                statCard("用户", session.userLabel)
                statCard("状态", if (isStalled(session)) "stalled" else session.status)
                statCard("渠道", session.channelKind)
                statCard("模型", session.model)
                statCard("事件数", session.eventCount.toString())
            }
            p("meta") { +"创建于 ${fmtTs(session.createdAt)} · 更新于 ${fmtTs(session.updatedAt)}" }
            // 手机扫码预览（0.9.35）：最终程序存在即可扫码——Skill 调试产物 / Agent 直接输出同口径
            val finalProgram = events.lastOrNull { it.payload is AgentEventPayload.Message && it.role != "user" }
            if (finalProgram != null) {
                div("qr-row") {
                    unsafe { raw(QrCodeSvg.svg(openUiPreviewDeepLink(session.id))) }
                    div {
                        p("block-label") { +"手机扫码预览（端上真实渲染器）" }
                        p("meta") { +"PoLang 调试包「OpenUI 调试」页扫码，按会话拉取最终程序渲染。" }
                    }
                }
            }
            // Observation 配对锚点：页内 action 事件 id 集合
            val actionIds = events.filter { it.type == "action" }.map { it.id }.toHashSet()
            div("ev-list") {
                var prevAt: Long? = null
                events.forEach { e ->
                    val delta = prevAt?.let { e.createdAt - it }
                    div("ev ${eventClass(e, actionIds)}") {
                        div("ev-meta") {
                            +fmtTs(e.createdAt)
                            +" · #${e.id} · ${e.type}/${e.source}/${e.role}"
                            delta?.let { +" · +${it}ms" }
                        }
                        eventBody(e, actionIds)
                    }
                    prevAt = e.createdAt
                }
            }
            if (nextAfter != null) {
                p("trace-more") {
                    a("/admin/agent/sessions/${session.id}?after=$nextAfter") { +"后续事件 →" }
                }
            }
        }
    }

    fun trafficPage(range: RangeStats, days: Int, metric: String): String = createHTML().html {
        adminHead("流量 · PoLang 管理后台")
        body {
            navBar()
            pageControls(metric, listOf("calls" to "调用", "tokens" to "Token", "cost" to "成本", "bytes" to "字节"), days, listOf(7, 14, 30, 90), "/admin/traffic")
            h1 { +"流量（近 $days 天，UTC+8）· ${metricLabel(metric)}" }
            h2 { +"每日 ${metricLabel(metric)}" }
            unsafe { raw(svgBars(range.days.map { dayMetricValue(it, metric) }, range.days.map { it.day }, labelFormatter = metricFormatter(metric))) }
            h2 { +"健康" }
            healthRow(range)
            h2 { +"成本构成（输入 vs 输出，估算）" }
            div("model-top") {
                shareBars(
                    listOf(
                        DimStat("输入", 0, 0, range.costSplit.promptCost),
                        DimStat("输出", 0, 0, range.costSplit.completionCost),
                    ),
                    "cost",
                    range.costSplit.promptCost + range.costSplit.completionCost,
                )
            }
            val effMetric = if (metric == "bytes") "cost" else metric
            h2 { +"构成（按${metricLabel(effMetric)}）" }
            div("cards") {
                div("card share-card") {
                    div("card-label") { +"by model" }
                    shareBars(range.byModel, effMetric, dimTotal(range.byModel, effMetric))
                }
                div("card share-card") {
                    div("card-label") { +"by provider" }
                    shareBars(range.byProvider, effMetric, dimTotal(range.byProvider, effMetric))
                }
            }
            h2 { +"每日明细" }
            dailyDetailTable(range)
            h2 { +"异常 Top（近 $days 天）" }
            topLists(range)
        }
    }

    // ── Skill 中心（spike 2026-11-07；v2 同日依 Ardot 设计稿重排：统计行 + 双栏编辑 + 预览页三 Tab）──

    /** markdown → HTML（Skill 中心渲染预览/编辑页实时预览初值；escapeHtml 兜底防注入）。 */
    private val mdParser = org.commonmark.parser.Parser.builder().build()
    private val mdRenderer = org.commonmark.renderer.html.HtmlRenderer.builder()
        .escapeHtml(true)
        .sanitizeUrls(true)
        .build()

    fun renderMarkdown(source: String): String = mdRenderer.render(mdParser.parse(source)).trim()

    private fun FlowContent.skillStatCard(labelText: String, value: String, sub: String) {
        div("skill-stat") {
            div("skill-stat-label") { +labelText }
            div("skill-stat-value") { +value }
            div("skill-stat-sub") { +sub }
        }
    }

    fun skillsPage(
        skills: List<SkillCenter.SkillDetail>,
        promptSection: String,
        message: String? = null,
    ): String = createHTML().html {
        adminHead("技能 · PoLang 管理后台")
        body {
            navBar()
            div("page") {
                h1("page-title") { +"Skill 中心" }
                if (message != null) {
                    div("toast ${if (message.startsWith("已") || message.startsWith("成功")) "toast-ok" else "toast-err"}") { +message }
                }
                div("skill-head") {
                    p("meta") {
                        +"技能 = 按需加载的指令包（SKILL.md），云端 Agent 经 load_skill 加载；保存即热更新（下一轮跑轮生效，无需部署）。"
                    }
                    a("/admin/skills/debug", classes = "btn btn-ghost") { +"调试" }
                    a("/admin/skills/new", classes = "btn") { +"＋ 新增技能" }
                }
                div("skill-stats") {
                    skillStatCard("技能数", "${skills.size}", "frontmatter 合法才进清单")
                    skillStatCard("总字符", "%,d".format(skills.sumOf { it.rawChars }), "单技能上限 ${SkillCenter.MAX_CONTENT_CHARS} 字符")
                    skillStatCard(
                        "最近更新",
                        skills.maxByOrNull { it.updatedAtMs }?.let { fmtTs(it.updatedAtMs) } ?: "—",
                        "磁盘直读 · rsync 即热更新",
                    )
                }
                table {
                    tr {
                        th { +"名称" }
                        th { +"描述（LLM 相关性判断依据）" }
                        th { +"字符" }
                        th { +"更新时间（UTC+8）" }
                        th { +"操作" }
                    }
                    skills.forEach { s ->
                        tr {
                            td("skill-name") { a("/admin/skills/${s.meta.name}") { +s.meta.name } }
                            td { +s.meta.description }
                            td { +s.rawChars.toString() }
                            td { +fmtTs(s.updatedAtMs) }
                            td {
                                div("skill-row-actions") {
                                    a("/admin/skills/${s.meta.name}/preview", classes = "btn-sm") { +"预览" }
                                    a("/admin/skills/${s.meta.name}", classes = "btn-sm") { +"编辑" }
                                    form(action = "/admin/skills/${s.meta.name}/delete", method = FormMethod.post) {
                                        attributes["style"] = "display:inline"
                                        attributes["onsubmit"] =
                                            "return confirm('确认删除技能 ${s.meta.name}？云端下一轮跑轮即不再可见')"
                                        input(type = InputType.submit, classes = "btn-sm btn-danger") { value = "删除" }
                                    }
                                }
                            }
                        }
                    }
                }
                if (skills.isEmpty()) {
                    div("empty-state") { +"暂无技能。技能 = 按需加载的指令包（SKILL.md），云端 Agent 经 load_skill 工具按需加载。" }
                } else {
                    p("meta") { +"共 ${skills.size} 个技能 · frontmatter 非法的目录不进清单不可加载 · 磁盘直读 rsync 即热更新" }
                }
                section("settings-section") {
                    div("section-head") {
                        h2("section-title") { +"LLM 视角 · system prompt 技能清单段（实时）" }
                        p("section-desc") { +"云端 Agent 每轮跑轮注入的内容；空清单时段落整段不出现（prompt 零变化）。" }
                    }
                    pre("mono-block") { +(promptSection.ifBlank { "（空清单——prompt 零变化）" }) }
                }
            }
        }
    }

    /** 编辑页（双栏）：左 SKILL.md 表单，右 LLM 视角实时预览（清单行 + markdown 渲染，纯前端零依赖）。 */
    fun skillEditPage(
        existing: SkillCenter.SkillDetail?,
        error: String? = null,
    ): String = createHTML().html {
        val isNew = existing == null
        val defaultBody = "# 技能标题\n\n## 何时使用\n\n## 步骤\n\n## 约束\n"
        adminHead("${if (isNew) "新增" else "编辑"}技能 · PoLang 管理后台")
        body {
            navBar()
            div("page") {
                h1("page-title") { +buildString { append(if (isNew) "新增" else "编辑"); append("技能"); if (!isNew) append(" · ").append(existing!!.meta.name) } }
                if (error != null) div("toast toast-err") { +error }
                if (!isNew) {
                    div("skill-previewbar") {
                        p("meta") {
                            +"更新于 ${fmtTs(existing!!.updatedAtMs)} · ${existing.rawChars} / ${SkillCenter.MAX_CONTENT_CHARS} 字符 · 保存即热更新（下一轮跑轮生效）"
                        }
                        span("push") {
                            form(action = "/admin/skills/${existing.meta.name}/delete", method = FormMethod.post) {
                                attributes["style"] = "display:inline"
                                attributes["onsubmit"] = "return confirm('确认删除技能 ${existing.meta.name}？云端下一轮跑轮即不再可见')"
                                input(type = InputType.submit, classes = "btn-danger-ghost") { value = "删除技能" }
                            }
                        }
                    }
                }
                div("skills-layout") {
                    // ── 左栏：表单 ──
                    form(
                        action = if (isNew) "/admin/skills" else "/admin/skills/${existing!!.meta.name}",
                        method = FormMethod.post,
                        classes = "settings-section",
                    ) {
                        div("section-head") {
                            h2("section-title") { +"SKILL.md 表单" }
                            p("section-desc") { +"frontmatter（name / description）由表单组装；正文为 markdown 指令。" }
                        }
                        div("skill-field") {
                            label {
                                +"名称（小写字母/数字/连字符；修改即重命名，技能目录随之迁移）"
                            }
                            textInput(name = "name") {
                                value = existing?.meta?.name.orEmpty()
                                placeholder = "html-card"
                                attributes["id"] = "skill-name"
                            }
                        }
                        div("skill-field") {
                            label { +"描述（单行，可折行显示；LLM 判断是否加载本技能的唯一依据，写清「何时该用」）" }
                            textArea(classes = "desc-area") {
                                attributes["name"] = "description"
                                attributes["id"] = "skill-desc"
                                attributes["rows"] = "2"
                                +existing?.meta?.description.orEmpty()
                            }
                        }
                        div("skill-field") {
                            label { +"正文（markdown 指令；load_skill 返回的内容，上限 ${"%,d".format(SkillCenter.MAX_CONTENT_CHARS)} 字符）" }
                            textArea {
                                attributes["name"] = "body"
                                attributes["id"] = "skill-body"
                                attributes["rows"] = "18"
                                +(existing?.body ?: defaultBody)
                            }
                            div("char-counter") { attributes["id"] = "char-counter" }
                        }
                        div("form-actions") {
                            a("/admin/skills", classes = "btn btn-ghost") { +"返回" }
                            input(type = InputType.submit, classes = "btn") { value = if (isNew) "创建" else "保存" }
                        }
                    }
                    // ── 右栏：实时预览（LLM 视角）──
                    section("settings-section") {
                        div("section-head") {
                            h2("section-title") { +"实时预览 · LLM 视角" }
                            p("section-desc") { +"左栏内容在云端跑轮中的实际形态；清单行随描述实时联动，正文按 markdown 渲染。" }
                        }
                        p("block-label") { +"① 清单行 · 披露层（system prompt 注入）" }
                        pre("manifest-block") {
                            attributes["id"] = "manifest-line"
                            +"- ${existing?.meta?.name.orEmpty()}: ${existing?.meta?.description.orEmpty()}"
                        }
                        hr("skill-divider")
                        p("block-label") { +"② load_skill 返回 · 正文层（markdown 渲染）" }
                        div("md-preview") {
                            attributes["id"] = "md-live"
                            unsafe { raw(renderMarkdown(existing?.body ?: defaultBody)) }
                        }
                        hr("skill-divider")
                        p("section-desc") { +"渐进披露：不匹配时禁止加载；正文仅按需进上下文。" }
                    }
                }
            }
            script {
                unsafe { raw(skillEditPageJs(existing?.meta?.name.orEmpty().ifBlank { "skill-name" })) }
            }
        }
    }

    /** 编辑页内联 JS：字符计数 + 清单行联动 + markdown 实时渲染（先转义后变换，无外部依赖）。 */
    private fun skillEditPageJs(fallbackName: String): String = """
        (function () {
            var MAX = ${SkillCenter.MAX_CONTENT_CHARS};
            var nameEl = document.getElementById('skill-name');
            var descEl = document.getElementById('skill-desc');
            var bodyEl = document.getElementById('skill-body');
            var counterEl = document.getElementById('char-counter');
            var manifestEl = document.getElementById('manifest-line');
            var liveEl = document.getElementById('md-live');
            function esc(s) {
                return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
            }
            function inline(s) {
                s = esc(s);
                s = s.replace(/`([^`]+)`/g, '<code>' + '$1' + '</code>');
                s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>' + '$1' + '</strong>');
                return s;
            }
            function renderMd(src) {
                var lines = src.split('\n');
                var out = [], para = [], listType = null, inCode = false, codeBuf = [];
                function flushPara() { if (para.length) { out.push('<p>' + para.map(inline).join('<br>') + '</p>'); para = []; } }
                function flushList() { if (listType) { out.push('</' + listType + '>'); listType = null; } }
                for (var i = 0; i < lines.length; i++) {
                    var l = lines[i];
                    if (/^```/.test(l)) {
                        if (inCode) { out.push('<pre><code>' + codeBuf.join('\n') + '</code></pre>'); codeBuf = []; inCode = false; }
                        else { flushPara(); flushList(); inCode = true; }
                        continue;
                    }
                    if (inCode) { codeBuf.push(esc(l)); continue; }
                    var h = /^(#{1,4})\s+(.*)$/.exec(l);
                    if (h) { flushPara(); flushList(); out.push('<h' + h[1].length + '>' + inline(h[2]) + '</h' + h[1].length + '>'); continue; }
                    if (/^\s*[-*]\s+/.test(l)) { flushPara(); if (listType !== 'ul') { flushList(); out.push('<ul>'); listType = 'ul'; } out.push('<li>' + inline(l.replace(/^\s*[-*]\s+/, '')) + '</li>'); continue; }
                    var ol = /^\s*(\d+)\.\s+(.*)$/.exec(l);
                    if (ol) { flushPara(); if (listType !== 'ol') { flushList(); out.push('<ol>'); listType = 'ol'; } out.push('<li>' + inline(ol[2]) + '</li>'); continue; }
                    if (/^\s*>/.test(l)) { flushPara(); flushList(); out.push('<blockquote>' + inline(l.replace(/^\s*>\s?/, '')) + '</blockquote>'); continue; }
                    if (l.trim() === '') { flushPara(); flushList(); continue; }
                    para.push(l);
                }
                if (inCode && codeBuf.length) { out.push('<pre><code>' + codeBuf.join('\n') + '</code></pre>'); }
                flushPara(); flushList();
                return out.join('\n') || '<p style="color:#999">（空正文）</p>';
            }
            function refresh() {
                // 描述保持单行语义：折行仅是显示层，输入/粘贴的换行即时清洗为空格
                if (/[\r\n]/.test(descEl.value)) { descEl.value = descEl.value.replace(/[\r\n]+/g, ' '); }
                var len = bodyEl.value.length;
                counterEl.textContent = len.toLocaleString() + ' / ' + MAX.toLocaleString() + ' 字符';
                counterEl.className = 'char-counter' + (len > MAX ? ' over' : '');
                manifestEl.textContent = '- ' + (nameEl.value || '$fallbackName') + ': ' + descEl.value;
                liveEl.innerHTML = renderMd(bodyEl.value);
            }
            descEl.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') { e.preventDefault(); } // 单行：回车不落字
            });
            [nameEl, descEl, bodyEl].forEach(function (el) {
                el.addEventListener('input', refresh);
            });
            refresh();
        })();
    """.trimIndent()

    /** 预览页：三 Tab（LLM 视角 / 原文 SKILL.md / 渲染预览），服务端渲染，tab 走 query 参数可分享。 */
    fun skillPreviewPage(
        detail: SkillCenter.SkillDetail,
        tab: String = "llm",
    ): String = createHTML().html {
        val name = detail.meta.name
        val safeTab = if (tab in setOf("llm", "raw", "rendered")) tab else "llm"
        adminHead("预览技能 · $name · PoLang 管理后台")
        body {
            navBar()
            div("page") {
                h1("page-title") { +"预览技能 · $name" }
                div("skill-previewbar") {
                    span("badge badge-active") { +"热更新已生效" }
                    p("meta") { +"更新于 ${fmtTs(detail.updatedAtMs)} · ${detail.rawChars} / ${SkillCenter.MAX_CONTENT_CHARS} 字符" }
                    span("push") {
                        a("/admin/skills/$name", classes = "btn") { +"编辑" }
                        a("/admin/skills", classes = "btn btn-ghost") { +"返回列表" }
                    }
                }
                div("subtabs") {
                    a("/admin/skills/$name/preview?tab=llm", classes = if (safeTab == "llm") "subtab active" else "subtab") { +"LLM 视角" }
                    a("/admin/skills/$name/preview?tab=raw", classes = if (safeTab == "raw") "subtab active" else "subtab") { +"原文 SKILL.md" }
                    a("/admin/skills/$name/preview?tab=rendered", classes = if (safeTab == "rendered") "subtab active" else "subtab") { +"渲染预览" }
                }
                section("settings-section") {
                    when (safeTab) {
                        "raw" -> {
                            div("section-head") {
                                h2("section-title") { +"原文 · SKILL.md" }
                                p("section-desc") { +"磁盘上的完整文件（frontmatter + 正文）；rsync 到 VPS 即热更新。" }
                            }
                            pre("mono-block") {
                                +"---\nname: $name\ndescription: ${detail.meta.description}\n---\n${detail.body}"
                            }
                        }
                        "rendered" -> {
                            div("section-head") {
                                h2("section-title") { +"渲染预览 · load_skill 返回的 markdown" }
                                p("section-desc") { +"正文层按 commonmark 渲染的视觉效果（与编辑页实时预览同源）。" }
                            }
                            div("md-preview") {
                                unsafe { raw(renderMarkdown(detail.body)) }
                            }
                        }
                        else -> {
                            div("section-head") {
                                h2("section-title") { +"LLM 视角" }
                                p("section-desc") { +"云端 Agent 看到的两层：清单行（每轮注入）与 load_skill 返回（按需进上下文）。" }
                            }
                            p("block-label") { +"① system prompt 清单段 · 披露层（本技能呈现为这一行）" }
                            pre("manifest-block") { +"- $name: ${detail.meta.description}" }
                            hr("skill-divider")
                            p("block-label") { +"② load_skill(\"$name\") 返回 · 正文层（Observation 进上下文的 markdown 原文）" }
                            pre("mono-block") { +detail.body.take(SkillCenter.MAX_CONTENT_CHARS) }
                            hr("skill-divider")
                            p("section-desc") { +"渐进披露：不匹配时禁止加载（正文占上下文，按需取用）。" }
                        }
                    }
                }
                p("meta") { +"同名目录磁盘直读 · rsync 即热更新 · frontmatter 非法则整体不进清单" }
            }
        }
    }

    /**
     * 技能调试 playground（2026-11-07）：草稿表单 + 测试消息，提交后服务端建合成会话
     * 异步跑一轮真实云端 Agent（草稿经 OverlaySkillCenter 内存覆盖同名磁盘技能），
     * 立即跳事件回放页。草稿不落盘、不耗配额、不碰生产资产。
     */
    fun skillDebugPage(
        skills: List<SkillCenter.SkillDetail>,
        name: String = "",
        description: String = "",
        body: String = "",
        testMessage: String = "",
        error: String? = null,
        history: List<SkillDebugHistoryRow> = emptyList(),
    ): String = createHTML().html {
        adminHead("技能调试 · PoLang 管理后台")
        body {
            navBar()
            div("page") {
                h1("page-title") { +"技能调试" }
                if (error != null) div("toast toast-err") { +error }
                div("skill-previewbar") {
                    p("meta") {
                        +"草稿只在内存覆盖本轮（同名技能被草稿替换，其余透传磁盘版）；不落盘、不耗配额、不碰生产资产。"
                    }
                    span("push") {
                        a("/admin/skills", classes = "btn btn-ghost") { +"返回列表" }
                    }
                }
                if (skills.isNotEmpty()) {
                    p("meta") {
                        +"从磁盘技能载入草稿： "
                        skills.forEach { s ->
                            a("/admin/skills/debug?name=${s.meta.name}", classes = "btn-sm") { +s.meta.name }
                            +" "
                        }
                    }
                }
                form(action = "/admin/skills/debug/run", method = FormMethod.post, classes = "settings-section") {
                    div("section-head") {
                        h2("section-title") { +"技能草稿 + 测试消息" }
                        p("section-desc") {
                            +"与生产同一 system prompt 与工具清单（设备工具无真机，调用即得 device_offline 降级）；"
                            +"提交后建合成调试会话（model=skill-debug）异步跑一轮，自动跳转事件回放页。"
                        }
                    }
                    div("skill-field") {
                        label { +"名称（同名 = 草稿覆盖磁盘版；新名 = 草稿新增）" }
                        textInput(name = "name") {
                            value = name
                            placeholder = "html-card"
                        }
                    }
                    div("skill-field") {
                        label { +"描述（单行；system prompt 清单段注入）" }
                        textInput(name = "description") {
                            value = description
                            placeholder = "当需要……时，先加载本技能。"
                        }
                    }
                    div("skill-field") {
                        label { +"正文（markdown 指令；load_skill 返回，上限 ${"%,d".format(SkillCenter.MAX_CONTENT_CHARS)} 字符）" }
                        textArea {
                            attributes["name"] = "body"
                            attributes["rows"] = "14"
                            +(body.ifBlank { "# 技能标题\n\n## 何时使用\n\n## 步骤\n\n## 约束\n" })
                        }
                    }
                    div("skill-field") {
                        label { +"测试消息（作为用户消息跑一轮）" }
                        textArea {
                            attributes["name"] = "test_message"
                            attributes["rows"] = "3"
                            attributes["placeholder"] = "帮我生成一个 HTML 投票卡片"
                            +testMessage
                        }
                    }
                    div("form-actions") {
                        a("/admin/skills", classes = "btn btn-ghost") { +"返回" }
                        input(type = InputType.submit, classes = "btn") { value = "跑一轮调试" }
                    }
                }

                // ── 调试历史留底（0.9.32）：每轮合成会话落库即留底，点「结果」直达可视化预览页 ──
                section("settings-section") {
                    div("section-head") {
                        h2("section-title") { +"调试历史（${history.size}）" }
                        p("section-desc") { +"每轮调试会话留底（model=skill-debug，最新 20 条），一行三列卡片：扫码上真机渲染，码下为本轮 case 信息；结果页带手机壳可视化预览。" }
                    }
                    if (history.isEmpty()) {
                        p("meta") { +"（尚无调试记录——跑一轮后此处留底）" }
                    } else {
                        // 一行三列 case 卡（0.9.39）：二维码在上、case 信息在下，一卡一轮。
                        // 二维码门控与 final-program 取数端点同口径（hasFinalProgram），
                        // 无最终程序的会话扫码必 404 → 不出码，占位框明示。
                        div("qr-grid") {
                            history.forEach { h ->
                                div("qr-cell") {
                                    if (h.hasFinalProgram) {
                                        unsafe { raw(QrCodeSvg.svg(openUiPreviewDeepLink(h.session.id))) }
                                    } else {
                                        div("qr-none") { +"无最终程序" }
                                    }
                                    div("qr-info") {
                                        div("qr-line qr-head") {
                                            span("qr-skill") { +(h.skillName ?: "（未触发）") }
                                            when {
                                                h.finishReason == null -> span("badge") { +"运行中" }
                                                h.finishReason == "done" -> span("badge badge-active") { +"完成" }
                                                else -> span("st-badge st-blocked") { +"异常" }
                                            }
                                        }
                                        div("qr-msg") { +((h.testMessage ?: "—").take(60)) }
                                        div("qr-line") {
                                            span { +fmtTs(h.session.createdAt) }
                                            span { +h.session.id.take(8) }
                                        }
                                        div("qr-line") {
                                            span { +"耗时 ${h.elapsedMs?.let { "${it}ms" } ?: "—"}" }
                                            span { +"事件 ${h.session.eventCount}" }
                                        }
                                        div("qr-actions") {
                                            a("/admin/skills/debug/result/${h.session.id}", classes = "btn-sm") { +"结果" }
                                            a("/admin/agent/sessions/${h.session.id}", classes = "btn-sm") { +"回放" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 调试结果页（2026-11-07）：跑轮进行中每 3 秒自动刷新；完成后聚合展示
     * 测试消息 / load_skill 调用与返回 / 其他工具调用 / 最终回复程序 / 终态耗时。
     * 数据源 = 事件流（与完整回放页同查询），此处只做调试视角的聚合呈现。
     */
    fun skillDebugResultPage(
        session: AgentSessionRow,
        events: List<AgentEventRow>,
        preview: OpenUiTreePreview? = null,
    ): String = createHTML().html {
        val finish = events.lastOrNull { it.payload is AgentEventPayload.Finish }
        val running = finish == null
        val userMsg = events.firstOrNull { it.payload is AgentEventPayload.Message && it.role == "user" }
        val skillActions = events.filter { (it.payload as? AgentEventPayload.Action)?.tool == "load_skill" }
        val otherActions = events.filter {
            val a = it.payload as? AgentEventPayload.Action
            a != null && a.tool != "load_skill"
        }
        val finalMsg = events.lastOrNull { it.payload is AgentEventPayload.Message && it.role != "user" }
        fun observationOf(actionId: Long): AgentEventPayload.Observation? =
            events.firstOrNull { (it.payload as? AgentEventPayload.Observation)?.actionEventId == actionId }
                ?.payload as? AgentEventPayload.Observation

        adminHead("调试结果 ${session.id.take(8)} · PoLang 管理后台")
        body {
            navBar()
            div("page") {
                h1("page-title") { +"调试结果" }
                div("skill-previewbar") {
                    if (running) {
                        span("badge") { +"运行中" }
                    } else {
                        val reason = (finish!!.payload as AgentEventPayload.Finish).reason
                        if (reason == "done") span("badge badge-active") { +"完成" }
                        else span("st-badge st-blocked") { +"异常：$reason" }
                    }
                    p("meta") {
                        +"会话 ${session.id.take(8)} · ${session.channelKind}/${session.model} · ${events.size} 事件"
                        if (finish != null && userMsg != null) {
                            +" · 耗时 ${finish.createdAt - userMsg.createdAt}ms"
                        }
                    }
                    span("push") {
                        a("/admin/agent/sessions/${session.id}", classes = "btn btn-ghost") { +"完整回放" }
                        a("/admin/skills/debug", classes = "btn") { +"再跑一轮" }
                    }
                }
                if (running) {
                    p("meta") { +"跑轮进行中，页面每 3 秒自动刷新；设备工具在本环境恒返回 device_offline。" }
                }

                section("settings-section") {
                    div("section-head") {
                        h2("section-title") { +"测试消息" }
                    }
                    pre("mono-block") { +(userMsg?.let { (it.payload as AgentEventPayload.Message).content } ?: "（尚未落流）") }
                }

                section("settings-section") {
                    div("section-head") {
                        h2("section-title") { +"技能加载（load_skill）" }
                        p("section-desc") { +"草稿是否被触发、返回给 LLM 的正文——验证 overlay 是否按预期生效。" }
                    }
                    if (skillActions.isEmpty()) {
                        p("meta") {
                            +(if (running) "（等待中……）" else "本轮未触发 load_skill——若预期触发，检查草稿描述与测试消息的匹配度（描述是 LLM 判断加载的唯一依据）。")
                        }
                    }
                    skillActions.forEach { ev ->
                        val action = ev.payload as AgentEventPayload.Action
                        p("block-label") { +"调用 · ${action.argsJson}" }
                        val obs = observationOf(ev.id)
                        if (obs == null) {
                            p("meta") { +"（Observation 尚未落流）" }
                        } else {
                            pre("mono-block") { +obs.content }
                        }
                    }
                }

                if (otherActions.isNotEmpty()) {
                    section("settings-section") {
                        div("section-head") {
                            h2("section-title") { +"其他工具调用" }
                        }
                        otherActions.forEach { ev ->
                            val action = ev.payload as AgentEventPayload.Action
                            val obs = observationOf(ev.id)
                            p("meta") {
                                b { +action.tool }
                                +" → ${obs?.content?.take(200) ?: "（等待中）"}"
                            }
                        }
                    }
                }

                section("settings-section") {
                    div("section-head") {
                        h2("section-title") { +"最终回复（OpenUI 程序）" }
                        p("section-desc") { +"端上收到的终结程序；组件树经 shared OpenUiSession 求值后整树可视化预览（服务端无宿主工具，Query 恒回 Null 骨架渲染），程序原文在下方。" }
                    }
                    if (finalMsg == null) {
                        p("meta") { +"（尚未产出）" }
                    } else {
                        val program = (finalMsg.payload as AgentEventPayload.Message).content
                        // ── 可视化预览（0.9.37）：组件树整树渲染进手机壳；prepare 失败回落
                        //    Html 组件沙箱 iframe / Markdown 根 commonmark 旧提取链 ──
                        // 手机扫码预览（0.9.35）：调试包「OpenUI 调试」页扫码 →
                        // GET /agent/sessions/{id}/final-program 拉本程序 → 端上真实渲染器。
                        // 二维码与浏览器预览能力解耦（0.9.36）：原生组件树程序浏览器暂不可
                        // 预览时二维码照常展示（finalMsg != null 即渲染），仅提示语条件化。
                        val hasBrowserPreview = preview is OpenUiTreePreview.Rendered ||
                            extractHtmlDocuments(program).isNotEmpty() || extractMarkdownRoot(program) != null
                        div("preview-split") {
                            div("qr-side") {
                                div("qr-row") {
                                    unsafe { raw(QrCodeSvg.svg(openUiPreviewDeepLink(session.id))) }
                                    div {
                                        p("block-label") { +"手机扫码预览（端上真实渲染器）" }
                                        p("meta") {
                                            +(
                                                "PoLang 调试包「OpenUI 调试」页扫码，按会话拉取本程序渲染" +
                                                    if (hasBrowserPreview) "；浏览器内预览见右侧手机壳。" else "。"
                                                )
                                        }
                                        p("meta") { +openUiPreviewDeepLink(session.id) }
                                    }
                                }
                            }
                            when (preview) {
                                is OpenUiTreePreview.Rendered -> {
                                    div("phone-stage") {
                                        div("phone-item") {
                                            p("block-label") { +"可视化预览 · 组件树（shared OpenUiSession 求值）" }
                                            div("phone-frame") {
                                                div("phone-screen") {
                                                    phoneStatusBar()
                                                    div("openui-root") { unsafe { raw(preview.bodyHtml) } }
                                                }
                                            }
                                            if (preview.errors.isNotEmpty()) {
                                                p("meta") {
                                                    +(
                                                        "求值告警 ${preview.errors.size} 条：" +
                                                            preview.errors.take(3).joinToString("；") { err -> "${err.code}${err.statementId?.let { sid -> "($sid)" } ?: ""}" }
                                                        )
                                                }
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    if (preview is OpenUiTreePreview.Failed) {
                                        p("meta") {
                                            +(
                                                "程序求值失败：" +
                                                    preview.errors.take(5).joinToString("；") { err -> "${err.code} ${err.message}" }
                                                )
                                        }
                                    }
                                    legacyPreview(program)
                                }
                            }
                        }
                        p("block-label") { +"程序原文" }
                        pre("mono-block") { +program }
                    }
                }
            }
            if (running) {
                script { unsafe { raw("setTimeout(function(){ location.reload(); }, 3000);") } }
            }
        }
    }

    /** 端侧 OpenUI 调试页 deep link（扫码载荷；App 内扫码解析 session 参数 → final-program 端点拉取）。 */
    internal fun openUiPreviewDeepLink(sessionId: String) = "polang://openui-preview?session=$sessionId"

    /**
     * 旧提取链预览（0.9.30-0.9.36 形态）：Html 组件沙箱 iframe + Markdown 根 commonmark。
     * 0.9.37 起为 prepare 失败（或路由未求值）时的回落路径——程序语法崩了也尽量给出可视化。
     */
    private fun kotlinx.html.FlowContent.legacyPreview(program: String) {
        val htmlDocs = extractHtmlDocuments(program)
        val mdRoot = extractMarkdownRoot(program)
        if (htmlDocs.isEmpty() && mdRoot == null) {
            p("meta") { +"本程序无可视化预览形态。" }
            return
        }
        div("phone-stage") {
            htmlDocs.forEachIndexed { i, doc ->
                div("phone-item") {
                    p("block-label") { +"可视化预览 · Html 组件${if (htmlDocs.size > 1) " #${i + 1}" else ""}（沙箱 iframe，与端上 WebView 同文档）" }
                    div("phone-frame") {
                        div("phone-screen") {
                            phoneStatusBar()
                            // kotlinx.html DSL 无 iframe builder（3.x 未生成）——手写标签，
                            // srcdoc 值经 attrEscape 属性转义（& 须最先）
                            unsafe {
                                raw(
                                    "<iframe sandbox=\"allow-scripts\" loading=\"lazy\" " +
                                        "srcdoc=\"${attrEscape(doc)}\"></iframe>",
                                )
                            }
                        }
                    }
                }
            }
            if (mdRoot != null) {
                div("phone-item") {
                    p("block-label") { +"可视化预览 · Markdown 根（commonmark 渲染）" }
                    div("phone-frame") {
                        div("phone-screen") {
                            phoneStatusBar()
                            div("md-preview") { unsafe { raw(renderMarkdown(mdRoot)) } }
                        }
                    }
                }
            }
        }
    }

    /** 手机壳仿真状态栏（9:41 + 信号/WiFi/电池 SVG），占位 44px 安全区使内容避开刘海。 */
    private fun kotlinx.html.FlowContent.phoneStatusBar() {
        div("phone-statusbar") {
            span { +"9:41" }
            unsafe {
                raw(
                    "<svg width=\"58\" height=\"12\" viewBox=\"0 0 58 12\" fill=\"currentColor\" aria-hidden=\"true\">" +
                        "<rect x=\"0\" y=\"7\" width=\"3\" height=\"5\" rx=\"1\"/>" +
                        "<rect x=\"4.5\" y=\"5.5\" width=\"3\" height=\"6.5\" rx=\"1\"/>" +
                        "<rect x=\"9\" y=\"3.5\" width=\"3\" height=\"8.5\" rx=\"1\"/>" +
                        "<rect x=\"13.5\" y=\"1.5\" width=\"3\" height=\"10.5\" rx=\"1\"/>" +
                        "<path d=\"M22.5 5.4a6.4 6.4 0 0 1 8.6 0l-1.6 1.9a4.2 4.2 0 0 0-5.4 0z\"/>" +
                        "<path d=\"M24.9 8.2a3 3 0 0 1 3.8 0l-1.9 2.2z\"/>" +
                        "<rect x=\"37\" y=\"1\" width=\"17\" height=\"10\" rx=\"3\" fill=\"none\" stroke=\"currentColor\" stroke-opacity=\".4\"/>" +
                        "<rect x=\"38.5\" y=\"2.5\" width=\"11\" height=\"7\" rx=\"1.8\"/>" +
                        "<rect x=\"55.5\" y=\"4\" width=\"2\" height=\"4\" rx=\"1\" fill-opacity=\".4\"/>" +
                        "</svg>",
                )
            }
        }
    }

    // ── 调试结果页可视化预览提取（2026-11-07）──
    // 正则只定位开引号（无嵌套量词，零爆栈面），字面量本体走 OpenUiStringScan 字符级扫描。

    /** Html( 调用开引号定位（位置参数或 html= 命名参数）。 */
    private val HTML_CALL_OPEN_QUOTE = Regex("""\bHtml\s*\(\s*(?:html\s*=\s*)?"""")

    /** root = Markdown( 开引号定位（整程序锚定根节点，防误抓嵌套 Markdown 组件）。 */
    private val MARKDOWN_ROOT_OPEN_QUOTE = Regex("""^\s*root\s*=\s*Markdown\s*\(\s*(?:text\s*=\s*)?"""")

    /** 单页 iframe 预览上限（防异常程序刷屏）。 */
    private const val MAX_HTML_PREVIEW_DOCS = 4

    private fun literalAtOpenQuote(program: String, match: MatchResult): String? {
        val literal = OpenUiStringScan.literalAt(program, match.range.last) ?: return null
        return unescapeDslString(literal.content)
    }

    /** 程序中全部 Html("…") 文档（反转义后可直接进 iframe srcdoc）。 */
    private fun extractHtmlDocuments(program: String): List<String> =
        HTML_CALL_OPEN_QUOTE.findAll(program)
            .mapNotNull { literalAtOpenQuote(program, it) }
            .take(MAX_HTML_PREVIEW_DOCS)
            .toList()

    /** root = Markdown("…") 根节点的 markdown 原文（反转义）；非 Markdown 根返回 null。 */
    private fun extractMarkdownRoot(program: String): String? =
        MARKDOWN_ROOT_OPEN_QUOTE.find(program)?.let { literalAtOpenQuote(program, it) }

    /** HTML 属性值转义（iframe srcdoc 用；& 须最先替换）。 */
    private fun attrEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** DSL 字符串反转义（OpenUiStringScan 只配对不反转义；覆盖 LLM 实写转义集）。 */
    private fun unescapeDslString(raw: String): String {
        if (raw.indexOf('\\') < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    '"' -> { out.append('"'); i += 2 }
                    '\'' -> { out.append('\''); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    'u' -> {
                        val hexEnd = i + 6
                        val code = if (hexEnd <= raw.length) raw.substring(i + 2, hexEnd).toIntOrNull(16) else null
                        if (code != null) { out.append(code.toChar()); i = hexEnd } else { out.append(c); i++ }
                    }
                    else -> { out.append(raw[i + 1]); i += 2 }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    fun settingsPage(
        snap: SettingsService.Snapshot,
        whitelist: List<AiEngineerWhitelistService.Entry>,
        message: String? = null,
    ): String = createHTML().html {
        adminHead("设置 · PoLang 管理后台")
        body {
            navBar()
            div("page") {
                h1("page-title") { +"设置" }
                if (message != null) {
                    div("toast ${if (message.startsWith("已") || message.startsWith("成功")) "toast-ok" else "toast-err"}") { +message }
                }
                section("settings-section") {
                    div("section-head") {
                        h2("section-title") { +"额度默认值（全局）" }
                        p("section-desc") {
                            +"影响：free 用于新注册账号初始上限；guest 用于未注册访客设备上限；browser 用于 Browser 远程会话每日配额。"
                            br(); +"已注册账号的上限按行独立，请在「用户详情」页单独调整。"
                        }
                    }
                    form(action = "/admin/settings", method = FormMethod.post, classes = "quota-form") {
                        div("quota-row") {
                            div("quota-field") {
                                label { +"新注册账号上限"; span("label-hint") { +"free · >0" } }
                                input(type = InputType.number, name = "free_llm_quota") {
                                    value = snap.freeLlmQuota.toString()
                                    attributes["min"] = "1"
                                }
                            }
                            div("quota-field") {
                                label { +"访客设备上限"; span("label-hint") { +"guest · >0" } }
                                input(type = InputType.number, name = "guest_llm_quota") {
                                    value = snap.guestLlmQuota.toString()
                                    attributes["min"] = "1"
                                }
                            }
                            div("quota-field") {
                                label { +"Browser 每日会话配额"; span("label-hint") { +"browser · >0" } }
                                input(type = InputType.number, name = "browser_daily_quota") {
                                    value = snap.browserDailyQuota.toString()
                                    attributes["min"] = "1"
                                }
                            }
                        }
                        div("quota-actions") {
                            input(type = InputType.submit, classes = "btn") { value = "保存" }
                        }
                    }
                }
                whitelistSection(whitelist)
            }
        }
    }

    fun channelsPage(
        channels: List<ChannelRow>,
        usage: Map<String, ChannelUsage>,
        balances: Map<Int, ChannelBalanceService.Cached>,
        error: String? = null,
    ): String = createHTML().html {
        adminHead("渠道 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"渠道" }
            if (error != null) p("err") { +error }
            p {
                a("/admin/channels/new", classes = "btn") { +"新增渠道" }
            }
            table {
                tr {
                    th { +"名称" }
                    th { +"Token" }
                    th { +"默认模型" }
                    th { +"消耗(调用/Token/¥)" }
                    th(classes = "col-balance") { +"余额" }
                    th(classes = "col-toggle") { +"启用" }
                    th(classes = "col-active") { +"当前生效" }
                    th(classes = "col-actions") { +"操作" }
                }
                channels.forEach { ch ->
                    tr {
                        td { +ch.name }
                        td {
                            span("tok") { +ch.apiTokenMasked }
                            if (ch.hasToken) {
                                +" "
                                button(type = ButtonType.button, classes = "btn-sm tok-copy") {
                                    attributes["onclick"] = "tokCopy(${ch.id}, this)"
                                    +"复制"
                                }
                            }
                        }
                        td { +(ch.defaultModel.ifBlank { "严格" }) }
                        td {
                            val u = usage[ch.name]
                            if (u == null) {
                                +"0 / 0 / 0.00"
                            } else {
                                +"${u.calls} / ${compactCount(u.tokens.toDouble())} / ${compactCost(u.cost)}"
                            }
                        }
                        td {
                            val b = balances[ch.id]
                            val display = b?.display ?: "—"
                            if (display != "—") span("active-badge") { +display } else +display
                            b?.checkedAt?.let {
                                br(); span("meta-inline") { +fmtTs(it) }
                            }
                        }
                        td {
                            form(action = "/admin/channels/${ch.id}/toggle", method = FormMethod.post, classes = "inline") {
                                input(type = InputType.submit, classes = "btn-sm ${if (ch.enabled) "" else "btn-go"}") {
                                    value = if (ch.enabled) "停用" else "启用"
                                }
                            }
                        }
                        td {
                            when {
                                ch.isActive -> span("active-badge") { +"● 生效中" }
                                ch.enabled -> form(action = "/admin/channels/${ch.id}/activate", method = FormMethod.post, classes = "inline") {
                                    input(type = InputType.submit, classes = "btn-sm btn-go") { value = "设为生效" }
                                }
                                else -> +"—"
                            }
                        }
                        td {
                            div("row-actions") {
                                if (ch.balanceUrl.isNotBlank()) {
                                    form(action = "/admin/channels/${ch.id}/refresh-balance", method = FormMethod.post, classes = "inline") {
                                        input(type = InputType.submit, classes = "btn-sm") { value = "刷新余额" }
                                    }
                                }
                                a("/admin/channels/${ch.id}/edit", classes = "btn-sm btn-primary") { +"编辑" }
                                if (!ch.isActive) {
                                    form(action = "/admin/channels/${ch.id}/delete", method = FormMethod.post, classes = "inline") {
                                        attributes["onsubmit"] = "return confirm('确定删除该渠道？操作不可恢复。')"
                                        input(type = InputType.submit, classes = "btn-sm btn-danger") { value = "删除" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            script {
                unsafe {
                    raw(
                        """function tokCopy(id,btn){fetch('/admin/channels/'+id+'/token',{credentials:'same-origin'}).then(function(r){return r.json()}).then(function(d){return navigator.clipboard.writeText(d.token)}).then(function(){var o=btn.textContent;btn.textContent='✓';setTimeout(function(){btn.textContent=o},1200)}).catch(function(){btn.textContent='失败';setTimeout(function(){btn.textContent='复制'},1200)})}""",
                    )
                }
            }
        }
    }

    fun channelFormPage(existing: ChannelRow? = null): String = createHTML().html {
        val title = if (existing == null) "新增渠道" else "编辑渠道"
        val action = if (existing == null) "/admin/channels" else "/admin/channels/${existing.id}"
        adminHead("$title · PoLang 管理后台")
        body {
            navBar()
            h1 { +title }
            form(action = action, method = FormMethod.post, classes = "chan-form") {
                p {
                    label { +"名称（≤32）" }
                    br()
                    textInput(name = "name") {
                        value = existing?.name ?: ""
                        placeholder = "如 DeepSeek 直连"
                    }
                }
                p {
                    label { +"类型" }
                    br()
                    select {
                        name = "kind"
                        option {
                            value = "gateway"
                            if (existing?.kind == "gateway") selected = true
                            +"gateway"
                        }
                        option {
                            value = "direct"
                            if (existing == null || existing.kind == "direct") selected = true
                            +"direct"
                        }
                    }
                }
                p {
                    label { +"BaseURL" }
                    br()
                    textInput(name = "base_url") {
                        value = existing?.baseUrl ?: ""
                        placeholder = "https://..."
                    }
                }
                p {
                    label { +"鉴权方式" }
                    br()
                    select {
                        name = "auth_style"
                        option {
                            value = "bearer"
                            if (existing?.authStyle != "cf_aig") selected = true
                            +"bearer (Authorization: Bearer)"
                        }
                        option {
                            value = "cf_aig"
                            if (existing?.authStyle == "cf_aig") selected = true
                            +"cf_aig (cf-aig-authorization)"
                        }
                    }
                }
                p {
                    label { +"API Token（编辑时留空=保持不变）" }
                    br()
                    input(type = InputType.password, name = "api_token") {
                        placeholder = if (existing != null) "••••（留空不变）" else ""
                    }
                }
                p {
                    label { +"模型映射（每行 请求名=上游名）" }
                    br()
                    textArea {
                        name = "model_map"
                        rows = "6"
                        cols = "50"
                        +(existing?.modelMap?.let { renderModelMapLines(it) } ?: "deepseek-chat=glm-5.2")
                    }
                }
                p {
                    label { +"默认模型（留空=严格校验，请求不支持的模型时返回 400）" }
                    br()
                    textInput(name = "default_model") {
                        value = existing?.defaultModel ?: ""
                        placeholder = "如 deepseek-v4-flash"
                    }
                }
                p {
                    label { +"余额 URL（留空=不支持余额查询）" }
                    br()
                    textInput(name = "balance_url") {
                        value = existing?.balanceUrl ?: ""
                        placeholder = "https://api.deepseek.com/user/balance"
                    }
                }
                div("form-actions") {
                    label("cb") {
                        input(type = InputType.checkBox, name = "enabled") {
                            value = "1"
                            if (existing?.enabled ?: true) checked = true
                        }
                        +" 启用此渠道"
                    }
                    span("form-actions-right") {
                        a("/admin/channels", classes = "btn-ghost") { +"取消" }
                        input(type = InputType.submit, classes = "btn") { value = "保存" }
                    }
                }
            }
        }
    }

    data class AndroidTrackInfo(
        val fileExists: Boolean,
        val fileSize: String,
        val lastModified: String,
        val version: String,
        val versionCode: Long,
        val cosUrl: String,
    )

    data class AndroidReleaseData(
        val debug: AndroidTrackInfo,
        val release: AndroidTrackInfo,
        val cosConfigured: Boolean,
        val history: List<AdminQueries.ApkUploadRow> = emptyList(),
    )

    data class IosReleaseData(
        val fileExists: Boolean,
        val fileSize: String,
        val lastModified: String,
        val version: String,
        val cosUrl: String,
        val cosConfigured: Boolean,
        val udidList: List<AdminQueries.IosUdidRow> = emptyList(),
    )

    fun releasePage(
        tab: String,
        message: String? = null,
        android: AndroidReleaseData? = null,
        ios: IosReleaseData? = null,
    ): String = createHTML().html {
        adminHead("发包 · PoLang 管理后台")
        body {
            navBar()
            div("page") {
                h1("page-title") { +"发包管理" }
                p("meta") { +"双端包管理 · COS 直链 · OTA versionCode 比较" }
                releaseTabs(tab)
                if (message != null) {
                    div("toast ${if (message.startsWith("成功")) "toast-ok" else "toast-err"}") { +message }
                }
                val cosConfigured = android?.cosConfigured ?: ios?.cosConfigured ?: true
                if (!cosConfigured) {
                    div("toast toast-err") {
                        +"COS 未配置（COS_SECRET_ID / COS_SECRET_KEY / COS_BUCKET 为空），请在 /etc/picme/server.env 中填写后重启服务"
                    }
                }
                if (tab == "ios" && ios != null) {
                    iosReleasePanel(ios)
                } else if (android != null) {
                    androidReleasePanel(android)
                }
            }
        }
    }

    private fun FlowContent.releaseTabs(tab: String) {
        div("subtabs") {
            a("/admin/release?tab=android", classes = if (tab != "ios") "subtab active" else "subtab") {
                +"Android APK"
            }
            a("/admin/release?tab=ios", classes = if (tab == "ios") "subtab active" else "subtab") {
                +"iOS IPA"
            }
        }
    }

    /** 双轨版本卡（v2 依 Ardot 设计稿）：轨道 Tag + 版本大字 + versionCode + 签名 meta + 直链。 */
    private fun FlowContent.androidTrackCard(track: String, info: AndroidTrackInfo) {
        val isDebug = track == "debug"
        val tagCls = if (isDebug) "tag-debug" else "tag-release"
        val tagText = if (isDebug) "debug · 日常自测轨" else "release · Play 对齐轨"
        div("track-card") {
            div("track-head") {
                span("track-tag $tagCls") { +tagText }
                a(href = info.cosUrl, target = "_blank", classes = "btn btn-sm btn-ghost") { +"COS 直链" }
            }
            if (info.fileExists) {
                div("track-version") {
                    span("track-ver") { +info.version.ifBlank { "未命名版本" } }
                    span("track-code") { +"versionCode ${info.versionCode}" }
                }
                div("track-meta") {
                    +"${info.fileSize} · 更新于 ${info.lastModified} · ${if (isDebug) "debug.keystore" else "picme-release.jks"} 签名"
                }
            } else {
                div("track-empty-text") { +"COS 上暂无 $track 包（上传后此处展示当前版本）" }
            }
        }
    }

    private fun FlowContent.androidReleasePanel(data: AndroidReleaseData) {
        // 双轨当前版本行（v2：轨道身份 + versionCode 显性化）
        div("track-row") {
            androidTrackCard("debug", data.debug)
            androidTrackCard("release", data.release)
        }

        // 上传区域（v2：左表单右拖拽双栏）
        div("card upload-card") {
            div("upload-head") {
                div("section-title") { +"上传新版本" }
                p("section-desc") {
                    +"双轨独立：debug 日常自测 · release 与 Play 对齐；上传即写 COS 并记录对应轨历史"
                }
            }
            div("upload-layout") {
                div("upload-form") {
                    div("form-grid-2") {
                        div("form-row") {
                            label { +"渠道" }
                            select {
                                attributes["id"] = "apk-channel"
                                attributes["class"] = "form-input"
                                option {
                                    value = "debug"
                                    selected = true
                                    +"debug（日常自测）"
                                }
                                option {
                                    value = "release"
                                    +"release（Play 对齐）"
                                }
                            }
                        }
                        div("form-row") {
                            label { +"版本号" }
                            input(type = InputType.text, name = "version", classes = "form-input") {
                                attributes["id"] = "apk-version"
                                placeholder = "如 1.0.11"
                            }
                        }
                    }
                    div("form-row") {
                        label { +"versionCode（OTA 更新判定依据，必须递增）" }
                        input(type = InputType.number, name = "versionCode", classes = "form-input") {
                            attributes["id"] = "apk-version-code"
                            placeholder = "如 10042"
                        }
                    }
                    div("form-row") {
                        label { +"更新说明（App 更新弹窗展示）" }
                        textArea(classes = "form-input") {
                            attributes["id"] = "apk-changelog"
                            attributes["rows"] = "3"
                            attributes["placeholder"] = "可选"
                        }
                    }
                }
                div("upload-side") {
                    // 拖拽上传区（文件选定后 JS 将其隐藏、显示 file-preview）
                    div("drop-zone") {
                        attributes["id"] = "drop-zone"
                        div("drop-zone-inner") {
                            div("drop-zone-icon") { +"☁" }
                            div("drop-zone-text") {
                                +"拖拽 APK 文件到此处，或 "
                                span("drop-zone-link") {
                                    attributes["onclick"] = "document.getElementById('apk-file').click()"
                                    +"点击选择"
                                }
                            }
                            div("drop-zone-hint") { +"支持 .apk 格式，最大 200MB" }
                        }
                        input(type = InputType.file, name = "apkfile") {
                            attributes["id"] = "apk-file"
                            accept = ".apk"
                            style = "display:none"
                        }
                    }

                    // 文件信息预览
                    div("file-preview") {
                        attributes["id"] = "file-preview"
                        style = "display:none"
                        div("file-preview-icon") { +"📱" }
                        div("file-preview-info") {
                            div("file-preview-name") { attributes["id"] = "file-name"; +"" }
                            div("file-preview-size") { attributes["id"] = "file-size"; +"" }
                        }
                        button(type = ButtonType.button, classes = "btn-sm file-preview-remove") {
                            attributes["onclick"] = "clearFile()"
                            attributes["title"] = "移除文件"
                            +"×"
                        }
                    }

                    // 进度条
                    div("progress-wrap") {
                        attributes["id"] = "progress-wrap"
                        style = "display:none"
                        div("progress-track") {
                            div("progress-fill") {
                                attributes["id"] = "progress-fill"
                                +""
                            }
                        }
                        div("progress-meta") {
                            span {
                                attributes["id"] = "progress-pct"
                                +"0%"
                            }
                            span {
                                attributes["id"] = "progress-size"
                                +""
                            }
                        }
                    }
                }
            }

            // 底行：OTA 提示 + 上传按钮
            div("upload-footer") {
                span("hint") { +"OTA 北京镜像约 10 分钟内跟随 · App 端按 versionCode 递增判定更新" }
                button(type = ButtonType.button, classes = "btn btn-primary btn-upload") {
                    attributes["id"] = "upload-btn"
                    attributes["onclick"] = "uploadApk()"
                    +"上传到 COS"
                }
            }
        }

        // 上传历史（v2：按轨分卡——debug / release 各一张，卡即渠道故无渠道列）
        div("history-split") {
            trackHistoryCard("debug", data.history.filter { it.channel == "debug" })
            trackHistoryCard("release", data.history.filter { it.channel != "debug" })
        }

        script {
            unsafe {
                raw(
                    """
                    var selectedFile=null;
                    var dropZone=document.getElementById('drop-zone');
                    var fileInput=document.getElementById('apk-file');
                    var preview=document.getElementById('file-preview');
                    var previewName=document.getElementById('file-name');
                    var previewSize=document.getElementById('file-size');
                    var versionInput=document.getElementById('apk-version');
                    var channelInput=document.getElementById('apk-channel');
                    var versionCodeInput=document.getElementById('apk-version-code');
                    var changelogInput=document.getElementById('apk-changelog');
                    var uploadBtn=document.getElementById('upload-btn');
                    var progressWrap=document.getElementById('progress-wrap');
                    var progressFill=document.getElementById('progress-fill');
                    var progressPct=document.getElementById('progress-pct');
                    var progressSize=document.getElementById('progress-size');

                    function setFile(file){
                      if(!file.name.endsWith('.apk')){alert('请上传 .apk 文件');return;}
                      selectedFile=file;
                      previewName.textContent=file.name;
                      previewSize.textContent=formatBytes(file.size);
                      preview.style.display='flex';
                      dropZone.style.display='none';
                      uploadBtn.disabled=false;
                      uploadBtn.textContent='上传到 COS';
                    }
                    function clearFile(){
                      selectedFile=null;
                      fileInput.value='';
                      preview.style.display='none';
                      dropZone.style.display='block';
                      uploadBtn.disabled=true;
                    }
                    fileInput.addEventListener('change',function(e){
                      if(e.target.files.length) setFile(e.target.files[0]);
                    });
                    // 禁止浏览器对整页拖拽的默认行为：防止把文本/链接拖入页面时浏览器弹框、导航或打开
                    ['dragenter','dragover','dragleave','drop'].forEach(function(evt){
                      document.addEventListener(evt,function(e){e.preventDefault();},false);
                    });
                    dropZone.addEventListener('dragover',function(e){
                      e.preventDefault();
                      dropZone.classList.add('drop-zone-active');
                    });
                    dropZone.addEventListener('dragleave',function(e){
                      e.preventDefault();
                      dropZone.classList.remove('drop-zone-active');
                    });
                    dropZone.addEventListener('drop',function(e){
                      e.preventDefault();
                      dropZone.classList.remove('drop-zone-active');
                      if(e.dataTransfer.files.length) setFile(e.dataTransfer.files[0]);
                    });
                    function uploadApk(){
                      if(!selectedFile){alert('请选择 APK 文件');return;}
                      var form=new FormData();
                      form.append('channel',channelInput.value);
                      form.append('version',versionInput.value.trim()||'');
                      form.append('versionCode',versionCodeInput.value.trim()||'');
                      form.append('changelog',changelogInput.value.trim()||'');
                      form.append('apkfile',selectedFile);
                      var xhr=new XMLHttpRequest();
                      xhr.open('POST','/admin/apk/upload',true);
                      xhr.upload.onprogress=function(e){
                        if(e.lengthComputable){
                          var pct=Math.round(e.loaded/e.total*100);
                          progressFill.style.width=pct+'%';
                          progressPct.textContent=pct+'%';
                          progressSize.textContent=formatBytes(e.loaded)+' / '+formatBytes(e.total);
                        }
                      };
                      xhr.onload=function(){
                        if(xhr.status===200||xhr.status===302){
                          window.location.reload();
                        }else{
                          progressPct.textContent='失败';
                          progressPct.style.color='#e54545';
                          uploadBtn.disabled=false;
                          uploadBtn.textContent='重试上传';
                        }
                      };
                      xhr.onerror=function(){
                        progressPct.textContent='网络错误';
                        progressPct.style.color='#e54545';
                        uploadBtn.disabled=false;
                        uploadBtn.textContent='重试上传';
                      };
                      progressWrap.style.display='block';
                      uploadBtn.disabled=true;
                      uploadBtn.textContent='上传中...';
                      xhr.send(form);
                    }
                    function formatBytes(b){
                      if(b===0)return'0 B';
                      var k=1024,s=['B','KB','MB','GB'];
                      var i=Math.floor(Math.log(b)/Math.log(k));
                      return parseFloat((b/Math.pow(k,i)).toFixed(2))+' '+s[i];
                    }
                    uploadBtn.disabled=true;
                    """.trimIndent(),
                )
            }
        }
    }

    /** 单轨上传历史卡。 */
    private fun FlowContent.trackHistoryCard(track: String, rows: List<AdminQueries.ApkUploadRow>) {
        val isDebug = track == "debug"
        div("track-history") {
            div("th-head") {
                span("track-tag ${if (isDebug) "tag-debug" else "tag-release"}") { +track }
                span("th-title") { +"上传历史（${rows.size}）" }
            }
            if (rows.isEmpty()) {
                div("th-empty") { +"暂无 $track 轨上传记录" }
            } else {
                table {
                    tr {
                        th { +"时间" }
                        th { +"版本" }
                        th { +"文件名" }
                        th { +"大小" }
                        th { +"状态" }
                    }
                    rows.forEach { h ->
                        tr {
                            td { +fmtTs(h.createdAt) }
                            td { +(h.version.ifBlank { "—" }) }
                            td { +(h.fileName.ifBlank { "—" }) }
                            td { +formatBytes(h.fileSize) }
                            td {
                                when (h.status) {
                                    "success" -> span("active-badge") { +"成功" }
                                    else -> span("err") { +(h.message ?: "失败") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.iosReleasePanel(data: IosReleaseData) {
        val fileExists = data.fileExists
        val fileSize = data.fileSize
        val lastModified = data.lastModified
        val version = data.version
        val cosUrl = data.cosUrl
        val udidList = data.udidList
        // 当前 IPA 信息
        if (fileExists) {
            div("card apk-info-card") {
                div("apk-info-header") {
                    div("apk-info-title") {
                        span("apk-badge") { +"当前版本" }
                        +version.ifBlank { "未命名版本" }
                    }
                    div("apk-info-meta") {
                        span { +fileSize }
                        span("apk-meta-sep") { +"·" }
                        span { +lastModified }
                    }
                }
                div("apk-info-actions") {
                    a(href = "https://api.polang.net/download/ios", target = "_blank", classes = "btn btn-sm btn-primary") { +"安装页" }
                    a(href = cosUrl, target = "_blank", classes = "btn btn-sm btn-primary") { +"COS 直链" }
                }
            }
        } else {
            div("card apk-info-card apk-empty") {
                div("apk-empty-icon") { +"🍎" }
                div("apk-empty-text") { +"COS 上暂无 IPA 文件（Phase 5 未启动，这是正常的）" }
            }
        }

        // IPA 上传表单
        h2 { +"上传 IPA" }
        div("card upload-card") {
            form(action = "/admin/ios/upload", method = FormMethod.post, encType = FormEncType.multipartFormData) {
                div("form-row") {
                    label { +"版本号（可选，留空从文件名解析）" }
                    input(type = InputType.text, name = "version", classes = "form-input") {
                        placeholder = "如 1.0.0"
                    }
                }
                div("form-row") {
                    label { +"IPA 文件（.ipa）" }
                    input(type = InputType.file, name = "ipafile") {
                        accept = ".ipa"
                    }
                }
                div("upload-actions") {
                    input(type = InputType.submit, classes = "btn btn-primary") { value = "上传到 COS" }
                }
            }
        }

        // UDID 登记列表
        h2 { +"UDID 登记（${udidList.size}）" }
        if (udidList.isEmpty()) {
            div("card apk-empty") {
                div("apk-empty-text") { +"暂无 UDID 登记" }
            }
        } else {
            table {
                tr {
                    th { +"UDID" }
                    th { +"备注" }
                    th { +"登记时间" }
                    th { +"状态" }
                }
                udidList.forEach { row ->
                    tr {
                        td {
                            span("tok") { +row.udid }
                        }
                        td { +(row.nickname ?: "—") }
                        td { +fmtTs(row.createdAt) }
                        td {
                            when (row.status) {
                                "enrolled" -> span("active-badge") { +"已加入" }
                                else -> span("err") { +"待处理" }
                            }
                        }
                    }
                }
            }

            // 一键导出
            div("card upload-card") {
                div("form-row") {
                    label { +"一键导出（复制全部 UDID，每行一个，贴进 Apple Developer → Devices）" }
                }
                textArea {
                    readonly = true
                    style = "width:100%;font-family:monospace;font-size:13px;min-height:120px;margin-top:8px"
                    +udidList.joinToString(separator = "\n") { row -> row.udid }
                }
                div("upload-actions") {
                    button(type = ButtonType.button, classes = "btn btn-primary") {
                        attributes["onclick"] = "copyUdids(this)"
                        +"复制全部"
                    }
                    +" "
                    a(href = "/admin/ios/udids.txt", classes = "btn btn-sm") { +"下载 .txt" }
                }
                script {
                    unsafe {
                        raw(
                            """function copyUdids(btn){var ta=btn.closest('.upload-card').querySelector('textarea');ta.select();document.execCommand('copy');var o=btn.textContent;btn.textContent='已复制';setTimeout(function(){btn.textContent=o},1200)}""",
                        )
                    }
                }
            }
        }
    }

    private fun relTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}秒"
            s < 3600 -> "${s / 60}分钟"
            s < 86400 -> "${s / 3600}小时"
            else -> "${s / 86400}天"
        }
    }

    // ── 概览/流量 共享组件 ──

    private enum class DeltaPolarity { GOOD_ON_UP, BAD_ON_UP }

    private fun FlowContent.statCardDelta(
        label: String,
        todayStr: String,
        today: Double,
        yesterday: Double,
        polarity: DeltaPolarity,
    ) {
        div("card") {
            div("card-label") { +label }
            div("card-value") { +todayStr }
            deltaChip(today, yesterday, polarity)
        }
    }

    private fun FlowContent.deltaChip(today: Double, yesterday: Double, polarity: DeltaPolarity) {
        when {
            yesterday == 0.0 && today > 0.0 -> span("delta delta-new") { +"新增" }
            today == yesterday -> {}
            else -> {
                val pct = abs(((today - yesterday) / yesterday * 100).toInt())
                val up = today > yesterday
                val cls = if (up) {
                    when (polarity) { DeltaPolarity.GOOD_ON_UP -> "delta delta-up-good"; DeltaPolarity.BAD_ON_UP -> "delta delta-up-bad" }
                } else {
                    "delta delta-down"
                }
                span(cls) { +((if (up) "↑" else "↓") + "$pct%") }
            }
        }
    }

    /** 单行控件栏：左侧分类(指标) Tab + 右侧周期 Tab，共用一条下划线，取代原来的双层 subtabs。 */
    private fun FlowContent.pageControls(
        metric: String,
        metricOptions: List<Pair<String, String>>,
        days: Int,
        daysOptions: List<Int>,
        basePath: String,
    ) {
        div("page-controls") {
            div("ctrl-group") {
                metricOptions.forEach { (v, label) ->
                    a("$basePath?days=$days&metric=$v", classes = if (v == metric) "ctrl active" else "ctrl") { +label }
                }
            }
            div("ctrl-group") {
                daysOptions.forEach { d ->
                    a("$basePath?days=$d&metric=$metric", classes = if (d == days) "ctrl active" else "ctrl") { +"${d}天" }
                }
            }
        }
    }

    private fun metricLabel(metric: String): String = when (metric) {
        "tokens" -> "Token"; "cost" -> "成本 ¥"; "bytes" -> "出口字节"; else -> "调用数"
    }

    private fun dayMetricValue(b: DayBucket, metric: String): Double = when (metric) {
        "tokens" -> b.totalTokens.toDouble(); "cost" -> b.cost; "bytes" -> b.bytes.toDouble(); else -> b.calls.toDouble()
    }

    private fun metricFormatter(metric: String): (Double) -> String = when (metric) {
        "cost" -> ::compactCost
        "bytes" -> { v -> formatBytes(v.toLong()) }
        else -> ::compactCount
    }

    private fun dimStatValue(st: DimStat, metric: String): Double = when (metric) {
        "tokens" -> st.tokens.toDouble(); "cost" -> st.cost; else -> st.calls.toDouble()
    }

    private fun dimTotal(items: List<DimStat>, metric: String): Double = items.sumOf { dimStatValue(it, metric) }

    private fun FlowContent.shareBars(items: List<DimStat>, metric: String, total: Double) {
        val denom = if (total > 0.0) total else 1.0
        div("share-list") {
            items.sortedByDescending { dimStatValue(it, metric) }.take(6).forEach { st ->
                val pct = (dimStatValue(st, metric) / denom * 100).toInt().coerceIn(0, 100)
                div("share-row") {
                    span("share-label") { +st.key }
                    div("share-bar") { div("share-bar-fill") { attributes["style"] = "width:$pct%" } }
                    span("share-pct") { +"$pct%" }
                }
            }
        }
    }

    // ── 流量页专用组件 ──

    private fun FlowContent.healthRow(range: RangeStats) {
        val denom = range.totals.calls + range.totals.blocked + range.totals.errors
        val rate = { n: Long -> if (denom > 0) "${(n.toDouble() / denom * 100).toInt()}%" else "—" }
        div("cards health-row") {
            healthItem("blocked 率", rate(range.totals.blocked))
            healthItem("error 率", rate(range.totals.errors))
            healthItem("延迟 p50", if (range.latency.count == 0) "—" else "${range.latency.p50} ms")
            healthItem("延迟 p95", if (range.latency.count == 0) "—" else "${range.latency.p95} ms")
        }
    }

    private fun FlowContent.healthItem(label: String, value: String) {
        div("card health-item") {
            div("card-label") { +label }
            div("card-value") { +value }
        }
    }

    private fun rateStr(calls: Long, blocked: Long, errors: Long, hit: Long): String {
        val denom = calls + blocked + errors
        return if (denom > 0) "${(hit.toDouble() / denom * 100).toInt()}%" else "—"
    }

    private fun FlowContent.dailyDetailTable(range: RangeStats) {
        val tot = range.totals
        table {
            tr {
                th { +"日期" }; th { +"调用" }; th { +"blocked" }; th { +"error" }; th { +"率" }
                th { +"输入Tok" }; th { +"输出Tok" }; th { +"成本 ¥" }; th { +"出口字节" }
            }
            tr("total-row") {
                td { +"合计" }; td { +tot.calls.toString() }; td { +tot.blocked.toString() }; td { +tot.errors.toString() }
                td { +rateStr(tot.calls, tot.blocked, tot.errors, tot.blocked) }
                td { +compactCount(tot.promptTokens.toDouble()) }; td { +compactCount(tot.completionTokens.toDouble()) }; td { +compactCost(tot.cost) }; td { +formatBytes(tot.bytes) }
            }
            range.days.reversed().forEach { b ->
                tr {
                    td { +b.day }; td { +b.calls.toString() }; td { +b.blocked.toString() }; td { +b.errors.toString() }
                    td { +rateStr(b.calls, b.blocked, b.errors, b.blocked) }
                    td { +compactCount(b.promptTokens.toDouble()) }; td { +compactCount(b.completionTokens.toDouble()) }; td { +fmt(b.cost) }; td { +formatBytes(b.bytes) }
                }
            }
        }
    }

    private fun FlowContent.topLists(range: RangeStats) {
        div("cards") {
            div("card top-card") {
                div("card-label") { +"用户 Top 5（按调用）" }
                topTable(range.topUsers, "/admin/users")
            }
            div("card top-card") {
                div("card-label") { +"设备 Top 5（按调用）" }
                topTable(range.topDevices, null)
            }
        }
    }

    private fun FlowContent.topTable(items: List<TopStat>, linkBase: String?) {
        table("top-list") {
            tr { th { +"标识" }; th { +"调用" }; th { +"成本 ¥" } }
            if (items.isEmpty()) {
                tr { td { +"—" }; td {}; td {} }
            }
            items.forEach { s ->
                tr {
                    td {
                        if (linkBase != null && s.id > 0) a("$linkBase/${s.id}") { +s.label } else +s.label
                    }
                    td { +s.calls.toString() }
                    td { +fmt(s.cost) }
                }
            }
        }
    }

    // ── 公共片段 ──

    private fun HTML.adminHead(title: String) {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +title }
            style {
                unsafe {
                    raw(
                        """
                        :root{
                          --c-primary:#006eff;
                          --c-primary-hover:#005ce6;
                          --c-primary-ring:rgba(0,110,255,.15);
                          --c-success:#0abf5b;
                          --c-success-bg:#e6f9f0;
                          --c-success-border:#b3ebd0;
                          --c-warning:#ff9c00;
                          --c-warning-bg:#fff7e6;
                          --c-warning-border:#ffd699;
                          --c-danger:#e54545;
                          --c-danger-bg:#fff2f0;
                          --c-danger-border:#ffccc7;
                          --c-text:#1f2d3d;
                          --c-text-2:#666;
                          --c-text-3:#999;
                          --c-border:#e5e5e5;
                          --c-bg:#f0f2f5;
                          --c-surface:#fff;
                          --fs-xs:12px;
                          --fs-sm:13px;
                          --fs-md:14px;
                          --fs-lg:16px;
                          --fs-xl:20px;
                          --fs-xxl:24px;
                          /* 内容列自适应宽度：1200 → 1680 封顶（宽屏利用空间，超宽屏防行长失控） */
                          --page-max:1680px;
                          --r-sm:4px;
                          --r-md:8px;
                          --r-lg:12px;
                          --shadow-sm:0 1px 4px rgba(0,0,0,.06);
                          --shadow-md:0 4px 12px rgba(0,0,0,.08);
                        }
                        *{box-sizing:border-box}
                        body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;margin:0;background:var(--c-bg);color:var(--c-text);padding-bottom:40px}
                        body>h1,body>h2,body>.cards,body>table,body>p{max-width:var(--page-max);margin-left:auto;margin-right:auto;padding-left:24px;padding-right:24px}
                        body>h1{font-size:var(--fs-xxl);font-weight:600;margin-top:24px;margin-bottom:16px;color:var(--c-text)}
                        body>h2{font-size:var(--fs-lg);font-weight:600;color:var(--c-text);margin-top:24px;margin-bottom:12px}
                        .meta{font-size:var(--fs-sm);color:var(--c-text-3);margin-top:-8px;margin-bottom:16px}
                        .wrap{max-width:420px;margin:80px auto;padding:32px;background:var(--c-surface);border-radius:var(--r-md);box-shadow:var(--shadow-md)}
                        .cards{display:flex;flex-wrap:wrap;gap:16px;padding:16px 24px}
                        .card{background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);padding:16px 20px;min-width:140px;box-shadow:var(--shadow-sm);transition:transform .2s}
                        .card:hover{transform:translateY(-2px)}
                        .card-label{font-size:var(--fs-sm);color:var(--c-text-2);margin-bottom:4px}
                        .card-value{font-size:var(--fs-xxl);font-weight:600;color:var(--c-primary);font-variant-numeric:tabular-nums}
                        /* 左侧边栏（依 Ardot 设计稿 Sidebar 232：Logo/菜单/弹性位/分隔线/用户区） */
                        .sidebar{position:fixed;left:0;top:0;bottom:0;width:232px;background:var(--c-surface);border-right:1px solid var(--c-border);display:flex;flex-direction:column;z-index:100}
                        .side-logo{height:64px;display:flex;align-items:center;gap:10px;padding:0 20px;flex-shrink:0}
                        .side-logo-mark{width:30px;height:30px;border-radius:6px;background:var(--c-primary);color:var(--c-surface);font-size:15px;font-weight:600;display:flex;align-items:center;justify-content:center;flex-shrink:0}
                        .side-logo-text{font-size:15px;font-weight:600;color:var(--c-text);white-space:nowrap}
                        .side-menu{display:flex;flex-direction:column;gap:4px;padding:12px;overflow-y:auto}
                        .side-item{display:flex;align-items:center;gap:10px;height:38px;padding:0 12px;border-radius:3px;color:var(--c-text);text-decoration:none;font-size:var(--fs-md);white-space:nowrap;transition:all .2s}
                        .side-item:hover{background:#f5f7fa;color:var(--c-primary)}
                        .side-item.active{background:#eaf1fd;color:var(--c-primary);font-weight:500}
                        .side-item .side-ico{display:flex;align-items:center;flex-shrink:0}
                        .side-spacer{flex:1}
                        .side-divider{height:1px;background:var(--c-border);margin:0 12px;flex-shrink:0}
                        .side-user{height:60px;display:flex;align-items:center;gap:10px;padding:0 20px;flex-shrink:0}
                        .side-avatar{width:28px;height:28px;border-radius:50%;background:var(--c-primary);color:var(--c-surface);font-size:12px;display:flex;align-items:center;justify-content:center;flex-shrink:0}
                        .side-user-name{font-size:var(--fs-md);color:var(--c-text);flex:1;white-space:nowrap}
                        .side-logout{font-size:13px;color:var(--c-danger);text-decoration:none}
                        .side-logout:hover{text-decoration:underline}
                        table{border-collapse:collapse;width:100%;background:var(--c-surface);font-size:var(--fs-sm);border:1px solid var(--c-border);border-radius:var(--r-md);overflow:hidden;box-shadow:var(--shadow-sm)}
                        th,td{border-bottom:1px solid #f0f0f0;padding:12px 16px;text-align:left}
                        th{background:#fafafa;font-weight:600;color:var(--c-text-2);font-size:var(--fs-xs);text-transform:uppercase;letter-spacing:.5px}
                        tr:hover{background:#f5f7fa}
                        tr:last-child td{border-bottom:none}
                        td a{color:var(--c-primary);text-decoration:none;font-weight:500}
                        td a:hover{text-decoration:underline}
                        .err{color:var(--c-danger);background:var(--c-danger-bg);padding:8px 12px;border-radius:var(--r-sm);border:1px solid var(--c-danger-border)}
                        .pw{padding:10px 12px;width:100%;border:1px solid #d9d9d9;border-radius:var(--r-sm);font-size:var(--fs-md);transition:border-color .2s}
                        .pw:focus{outline:none;border-color:var(--c-primary);box-shadow:0 0 0 3px var(--c-primary-ring)}
                        .btn{padding:10px 20px;background:var(--c-primary);color:var(--c-surface);border:none;border-radius:var(--r-sm);font-size:var(--fs-md);cursor:pointer;transition:all .2s;font-weight:500;height:40px;line-height:20px}
                        .btn:hover{background:var(--c-primary-hover);transform:translateY(-1px);box-shadow:0 4px 12px rgba(0,110,255,.25)}
                        .chart{display:block;width:100%;max-width:800px;height:auto;margin:16px auto;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);padding:16px;box-shadow:var(--shadow-sm)}
                        .chan-form{max-width:640px;margin:24px auto;padding:0 24px}
                        .chan-form p{margin:12px 0}
                        .chan-form label{display:block;font-size:var(--fs-sm);color:var(--c-text-2);margin-bottom:6px;font-weight:500}
                        .chan-form input[type=text],.chan-form input[type=password],.chan-form select,.chan-form textarea{padding:10px 12px;border:1px solid #d9d9d9;border-radius:var(--r-sm);width:100%;font-size:var(--fs-md);font-family:inherit;transition:border-color .2s}
                        .chan-form input[type=text]:focus,.chan-form select:focus,.chan-form textarea:focus{outline:none;border-color:var(--c-primary);box-shadow:0 0 0 3px var(--c-primary-ring)}
                        .chan-form textarea{font-family:monospace}
                        .inline{display:inline}
                        .btn-sm{padding:4px 12px;background:#f5f5f5;color:var(--c-text-2);border:1px solid #d9d9d9;border-radius:var(--r-sm);font-size:var(--fs-xs);cursor:pointer;min-width:40px;text-align:center;height:28px;line-height:20px;display:inline-block;vertical-align:middle;transition:all .2s}
                        .btn-sm:hover{background:#e8e8e8}
                        .btn-primary{background:var(--c-primary);color:var(--c-surface);border-color:var(--c-primary)}
                        .btn-primary:hover{background:var(--c-primary-hover)}
                        .btn-go{background:var(--c-success);color:var(--c-surface);border-color:var(--c-success)}
                        .btn-go:hover{background:#09a94f}
                        .btn-danger{background:var(--c-danger);color:var(--c-surface);border-color:var(--c-danger)}
                        .btn-danger:hover{background:#d13f3f}
                        .active-badge{color:var(--c-success);font-weight:600}
                        .tok{font-family:monospace;font-size:var(--fs-xs);color:var(--c-text-2);background:#f5f5f5;padding:2px 6px;border-radius:var(--r-sm)}
                        form{margin:0}
                        .row-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
                        .col-toggle{width:80px}
                        .col-active{width:100px}
                        .col-balance{width:120px}
                        .meta-inline{font-size:var(--fs-xs);color:var(--c-text-3)}
                        .col-actions{width:140px}
                        .actions-bar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;max-width:var(--page-max);margin:16px auto;padding:0 24px}
                        .limit-card{max-width:var(--page-max);margin:16px auto;padding:16px 24px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
                        .badge{display:inline-block;padding:2px 10px;border-radius:12px;font-size:var(--fs-xs);font-weight:500}
                        .badge-active{color:var(--c-success);background:var(--c-success-bg);border:1px solid var(--c-success-border)}
                        .badge-revoked{color:var(--c-warning);background:var(--c-warning-bg);border:1px solid var(--c-warning-border)}
                        .badge-deleted{color:var(--c-text-3);background:#f5f5f5;border:1px solid #d9d9d9}
                        .chan-form input[type=checkbox]{width:auto;margin:0}
                        .form-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:20px;padding-top:16px;border-top:1px solid #f0f0f0}
                        .form-actions-right{display:inline-flex;gap:8px;align-items:center}
                        .cb{display:inline-flex;align-items:center;gap:6px;font-size:var(--fs-md);color:var(--c-text);cursor:pointer}
                        .btn-ghost{padding:10px 16px;color:var(--c-text-2);text-decoration:none;font-size:var(--fs-md);border-radius:var(--r-sm);border:1px solid #d9d9d9;background:var(--c-surface);transition:all .2s}
                        .btn-ghost:hover{color:var(--c-text);background:#f5f5f5;border-color:#b0b8c4}
                        /* APK 页面专用样式 */
                        .toast{max-width:var(--page-max);margin:16px auto;padding:12px 20px;border-radius:6px;font-size:var(--fs-md)}
                        .toast-ok{color:var(--c-success);background:var(--c-success-bg);border:1px solid var(--c-success-border)}
                        .toast-err{color:var(--c-danger);background:var(--c-danger-bg);border:1px solid var(--c-danger-border)}
                        .apk-info-card{max-width:var(--page-max);margin:16px auto;padding:20px 24px;display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);box-shadow:var(--shadow-sm)}
                        .apk-empty{text-align:center;padding:40px 24px;justify-content:center;flex-direction:column}
                        .apk-empty-icon{font-size:48px;margin-bottom:12px}
                        .apk-empty-text{color:var(--c-text-3);font-size:var(--fs-md)}
                        .apk-info-header{flex:1;min-width:200px}
                        .apk-info-title{font-size:18px;font-weight:600;color:var(--c-text);display:flex;align-items:center;gap:10px}
                        .apk-badge{background:var(--c-primary);color:var(--c-surface);font-size:var(--fs-xs);padding:2px 10px;border-radius:12px;font-weight:500}
                        .apk-info-meta{color:var(--c-text-3);font-size:var(--fs-sm);margin-top:6px;display:flex;align-items:center;gap:8px}
                        .apk-meta-sep{color:#ccc}
                        .apk-info-actions{display:flex;gap:10px;align-items:center}
                        .upload-card{max-width:var(--page-max);margin:16px auto;padding:24px;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);box-shadow:var(--shadow-sm)}
                        .form-row{margin-bottom:16px}
                        .form-row label{display:block;font-size:var(--fs-sm);color:var(--c-text-2);margin-bottom:6px;font-weight:500}
                        .form-input{padding:10px 12px;border:1px solid #d9d9d9;border-radius:var(--r-sm);width:100%;max-width:300px;font-size:var(--fs-md);transition:border-color .2s}
                        .form-input:focus{outline:none;border-color:var(--c-primary);box-shadow:0 0 0 3px var(--c-primary-ring)}
                        .drop-zone{border:2px dashed #d9d9d9;border-radius:var(--r-md);padding:40px 24px;text-align:center;cursor:pointer;transition:all .2s;background:#fafafa;margin:16px 0}
                        .drop-zone:hover{border-color:var(--c-primary);background:#f0f7ff}
                        .drop-zone-active{border-color:var(--c-primary);background:#e6f2ff;transform:scale(1.01)}
                        .drop-zone-icon{font-size:48px;color:#b0b8c4;margin-bottom:12px;transition:color .2s}
                        .drop-zone:hover .drop-zone-icon{color:var(--c-primary)}
                        .drop-zone-text{font-size:15px;color:var(--c-text);margin-bottom:8px}
                        .drop-zone-link{color:var(--c-primary);cursor:pointer;font-weight:500}
                        .drop-zone-link:hover{text-decoration:underline}
                        .drop-zone-hint{font-size:var(--fs-xs);color:var(--c-text-3)}
                        .file-preview{display:none;align-items:center;gap:16px;padding:16px;background:#f5f7fa;border-radius:var(--r-md);margin:16px 0;border:1px solid var(--c-border)}
                        .file-preview-icon{font-size:32px}
                        .file-preview-info{flex:1;min-width:0}
                        .file-preview-name{font-size:var(--fs-md);font-weight:500;color:var(--c-text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                        .file-preview-size{font-size:var(--fs-xs);color:var(--c-text-3);margin-top:2px}
                        .file-preview-remove{width:32px;height:32px;border-radius:50%;border:1px solid #d9d9d9;background:var(--c-surface);color:var(--c-text-2);font-size:18px;cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .2s;flex-shrink:0;padding:0}
                        .file-preview-remove:hover{background:var(--c-danger-bg);border-color:var(--c-danger);color:var(--c-danger)}
                        .progress-wrap{display:none;margin:16px 0}
                        .progress-track{background:#e5e7eb;border-radius:6px;height:8px;overflow:hidden}
                        .progress-fill{background:var(--c-primary);height:100%;width:0%;transition:width .2s ease;border-radius:6px}
                        .progress-meta{display:flex;justify-content:space-between;margin-top:8px;font-size:var(--fs-xs);color:var(--c-text-2)}
                        .upload-actions{margin-top:20px;text-align:right}
                        .btn-upload{min-width:140px}
                        .btn-upload:disabled{background:#b0b8c4;cursor:not-allowed;transform:none;box-shadow:none}
                        .subtabs{display:flex;gap:4px;max-width:var(--page-max);margin:16px auto 0;padding:0 24px;border-bottom:1px solid var(--c-border)}
                        .subtab{color:var(--c-text-2);text-decoration:none;padding:10px 16px;font-size:var(--fs-md);border-bottom:2px solid transparent;margin-bottom:-1px;transition:all .2s}
                        .subtab:hover{color:var(--c-primary)}
                        .subtab.active{color:var(--c-primary);border-bottom-color:var(--c-primary);font-weight:500}
                        .delta{display:inline-block;margin-left:6px;font-size:var(--fs-xs);font-weight:600;padding:1px 6px;border-radius:8px;vertical-align:middle}
                        .delta-up-good{color:var(--c-success);background:var(--c-success-bg)}
                        .delta-up-bad{color:var(--c-danger);background:var(--c-danger-bg)}
                        .delta-down{color:var(--c-text-3);background:#f5f5f5}
                        .delta-new{color:var(--c-text-3);background:#f5f5f5}
                        .share-list{display:flex;flex-direction:column;gap:8px;margin-top:8px}
                        .share-row{display:flex;align-items:center;gap:10px;font-size:var(--fs-sm)}
                        .share-label{width:160px;color:var(--c-text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                        .share-bar{flex:1;height:10px;background:var(--c-bg);border-radius:6px;overflow:hidden}
                        .share-bar-fill{height:100%;background:var(--c-primary);border-radius:6px}
                        .share-pct{width:44px;text-align:right;color:var(--c-text-2);font-variant-numeric:tabular-nums}
                        .health-item{min-width:120px}
                        .health-item .card-value{font-size:18px}
                        .share-card{flex:1;min-width:280px}
                        .top-card{flex:1;min-width:280px}
                        .top-list{font-size:var(--fs-xs);margin-top:8px}
                        .top-list th,.top-list td{padding:6px 8px}
                        tr.total-row td{font-weight:700;background:#fafafa}
                        .page-controls{display:flex;align-items:center;justify-content:space-between;max-width:var(--page-max);margin:16px auto 0;padding:0 24px;gap:12px;flex-wrap:wrap;border-bottom:1px solid var(--c-border)}
                        .ctrl-group{display:flex;gap:2px}
                        .ctrl{color:var(--c-text-2);text-decoration:none;padding:8px 14px;font-size:var(--fs-md);border-bottom:2px solid transparent;margin-bottom:-1px;transition:all .2s}
                        .ctrl:hover{color:var(--c-primary)}
                        .ctrl.active{color:var(--c-primary);border-bottom-color:var(--c-primary);font-weight:500}
                        .model-top{max-width:var(--page-max);margin:16px auto;padding:16px 24px;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);box-shadow:var(--shadow-sm)}
                        /* 设置页新结构 */
                        .page{max-width:var(--page-max);margin:0 auto;padding:0 24px}
                        .page-title{font-size:var(--fs-xxl);font-weight:600;margin:24px 0 16px}
                        .settings-section{background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-lg);box-shadow:var(--shadow-sm);padding:20px 24px;margin:16px 0}
                        .section-head{margin-bottom:16px}
                        .section-title{font-size:var(--fs-lg);font-weight:600;margin:0 0 6px}
                        .section-desc{font-size:var(--fs-sm);color:var(--c-text-3);margin:0;line-height:1.6}
                        .form-grid{display:flex;gap:20px;flex-wrap:wrap;align-items:flex-end}
                        .field label{display:block;font-size:var(--fs-sm);color:var(--c-text-2);font-weight:500;margin-bottom:6px}
                        .field input,.whitelist-add input{width:160px;padding:10px 12px;border:1px solid #d9d9d9;border-radius:var(--r-sm);font-size:var(--fs-md);transition:border-color .2s}
                        .field input:focus,.whitelist-add input:focus{outline:none;border-color:var(--c-primary);box-shadow:0 0 0 3px var(--c-primary-ring)}
                        .whitelist-add{display:flex;gap:10px;align-items:center;margin-bottom:16px;flex-wrap:wrap}
                        /* 只放宽邮箱框——此前 input 选择器把提交钮也拉到 280px（绿扁钮 bug） */
                        .whitelist-add input[type=email]{width:280px;padding:8px 12px}
                        .whitelist-add input[type=submit]{width:auto}
                        .empty-state{text-align:center;padding:32px 24px;color:var(--c-text-3);font-size:var(--fs-md);border:1px dashed var(--c-border);border-radius:var(--r-md)}
                        .settings-section table{border-radius:var(--r-md)}
                        .settings-section .form-actions{border-top:none;margin-top:0;padding-top:0;justify-content:flex-end}
                        /* 调用流水 / Agent 会话回放页面（M2 推理追溯） */
                        .st-badge{display:inline-block;padding:2px 10px;border-radius:12px;font-size:var(--fs-xs);font-weight:500;white-space:nowrap}
                        .st-ok{color:var(--c-success);background:var(--c-success-bg);border:1px solid var(--c-success-border)}
                        .st-error{color:var(--c-danger);background:var(--c-danger-bg);border:1px solid var(--c-danger-border)}
                        .st-blocked{color:var(--c-warning);background:var(--c-warning-bg);border:1px solid var(--c-warning-border)}
                        .st-muted{color:var(--c-text-3);background:#f5f5f5;border:1px solid #d9d9d9}
                        .trace-filter{display:flex;gap:12px;align-items:center;flex-wrap:wrap;max-width:var(--page-max);margin:8px auto 16px;padding:0 24px;font-size:var(--fs-sm)}
                        .trace-filter select,.trace-filter input[type=text]{padding:6px 10px;border:1px solid #d9d9d9;border-radius:var(--r-sm);font-size:var(--fs-sm);font-family:inherit}
                        .trace-chip{display:inline-flex;align-items:center;gap:6px;padding:4px 10px;background:var(--c-warning-bg);border:1px solid var(--c-warning-border);border-radius:12px;font-size:var(--fs-xs);color:var(--c-text-2)}
                        .trace-chip a{color:var(--c-primary);text-decoration:none;font-weight:500}
                        .trace-more{max-width:var(--page-max);margin:16px auto;padding:0 24px}
                        .trace-more a{color:var(--c-primary);text-decoration:none;font-weight:500}
                        .ev-list{max-width:var(--page-max);margin:16px auto;padding:0 24px}
                        .ev{background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);padding:12px 16px;margin-bottom:8px;box-shadow:var(--shadow-sm);font-size:var(--fs-sm)}
                        .ev-meta{font-size:var(--fs-xs);color:var(--c-text-3);margin-bottom:6px;font-variant-numeric:tabular-nums}
                        .ev-user{border-left:4px solid var(--c-primary)}
                        .ev-assistant{border-left:4px solid var(--c-success)}
                        .ev-action{border-left:4px solid var(--c-warning)}
                        .ev-obs{margin-left:32px;background:#fafafa}
                        .ev-err{color:var(--c-danger)}
                        .ev-warn{background:var(--c-warning-bg);border-color:var(--c-warning-border)}
                        .ev-card{border-left:4px solid var(--c-primary)}
                        .ev-cond{color:var(--c-text-3);background:#fafafa}
                        .ev-finish{text-align:center;font-weight:600}
                        .ev pre{background:#f5f5f5;padding:8px;border-radius:var(--r-sm);overflow-x:auto;font-size:var(--fs-xs);margin:8px 0 0;white-space:pre-wrap;word-break:break-all}
                        .ev details{margin-top:8px}
                        .ev summary{cursor:pointer;color:var(--c-text-3);font-size:var(--fs-xs)}
                        /* Skill 中心（2026-11-07 v2，Ardot 设计稿结构落地） */
                        .skill-head{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:16px}
                        .skill-head .meta{margin:0}
                        .skill-stats{display:flex;gap:16px;flex-wrap:wrap;margin:0 0 16px}
                        .skill-stat{flex:1;min-width:180px;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);padding:14px 20px;box-shadow:var(--shadow-sm)}
                        .skill-stat-label{font-size:var(--fs-sm);color:var(--c-text-2)}
                        .skill-stat-value{font-size:var(--fs-xl);font-weight:600;color:var(--c-primary);margin-top:4px;font-variant-numeric:tabular-nums}
                        .skill-stat-sub{font-size:var(--fs-xs);color:var(--c-text-3);margin-top:4px}
                        td.skill-name{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
                        .skill-row-actions{display:flex;gap:8px;align-items:center;white-space:nowrap}
                        .btn-sm.btn-danger{background:var(--c-danger-bg);color:var(--c-danger);border-color:var(--c-danger-border)}
                        .btn-danger-ghost{padding:8px 16px;background:var(--c-danger-bg);color:var(--c-danger);border:1px solid var(--c-danger-border);border-radius:var(--r-sm);font-size:var(--fs-sm);cursor:pointer;transition:all .2s;height:32px;line-height:16px}
                        .btn-danger-ghost:hover{background:var(--c-danger);color:var(--c-surface)}
                        .skills-layout{display:grid;grid-template-columns:minmax(0,1fr) minmax(480px,48%);gap:16px;margin:16px 0;align-items:start}
                        .skills-layout .settings-section{margin:0}
                        .skill-field{margin-bottom:16px}
                        .skill-field label{display:block;font-size:var(--fs-sm);color:var(--c-text-2);font-weight:500;margin-bottom:6px}
                        .skill-field input[type=text]{width:100%;padding:10px 12px;border:1px solid #d9d9d9;border-radius:var(--r-sm);font-size:var(--fs-md);font-family:inherit;transition:border-color .2s;box-sizing:border-box}
                        .skill-field input[type=text]:focus{outline:none;border-color:var(--c-primary);box-shadow:0 0 0 3px var(--c-primary-ring)}
                        .skill-field input[readonly]{background:#f5f5f5;color:var(--c-text-2)}
                        .skill-field textarea{width:100%;min-height:320px;padding:12px;border:1px solid #d9d9d9;border-radius:var(--r-sm);font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:var(--fs-sm);line-height:1.6;resize:vertical;transition:border-color .2s;box-sizing:border-box}
                        .skill-field textarea:focus{outline:none;border-color:var(--c-primary);box-shadow:0 0 0 3px var(--c-primary-ring)}
                        .skill-field textarea.desc-area{min-height:60px;max-height:120px;font-family:inherit;font-size:var(--fs-md);resize:none}
                        .ro-tag{display:inline-block;padding:1px 8px;border-radius:10px;font-size:var(--fs-xs);color:var(--c-text-3);background:#f5f5f5;border:1px solid #d9d9d9;margin-left:8px;font-weight:400}
                        .char-counter{font-size:var(--fs-xs);color:var(--c-text-3);text-align:right;margin-top:4px;font-variant-numeric:tabular-nums}
                        .char-counter.over{color:var(--c-danger);font-weight:600}
                        .mono-block{background:#f5f5f5;border-radius:var(--r-sm);padding:12px 16px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:var(--fs-sm);color:var(--c-text-2);white-space:pre-wrap;word-break:break-word;margin:0;overflow-x:auto}
                        .manifest-block{background:#e6f2ff;border:1px solid #b8d9ff;border-radius:var(--r-sm);padding:10px 16px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:var(--fs-sm);color:var(--c-text);white-space:pre-wrap;word-break:break-word;margin:0;overflow-x:auto}
                        .block-label{font-size:var(--fs-sm);color:var(--c-text-2);font-weight:500;margin:0 0 6px}
                        .skill-divider{border:none;border-top:1px solid var(--c-border);margin:16px 0}
                        .skill-previewbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:4px}
                        .skill-previewbar .meta{margin:0}
                        .skill-previewbar .push{margin-left:auto;display:flex;gap:8px;align-items:center}
                        .md-preview{line-height:1.7;color:var(--c-text);font-size:var(--fs-md)}
                        .md-preview h1{font-size:20px;margin:16px 0 8px;font-weight:600}
                        .md-preview h2{font-size:17px;margin:14px 0 6px;font-weight:600}
                        .md-preview h3,.md-preview h4{font-size:15px;margin:12px 0 6px;font-weight:600}
                        .md-preview p{margin:8px 0}
                        .md-preview ul,.md-preview ol{margin:8px 0;padding-left:24px}
                        .md-preview li{margin:4px 0}
                        .md-preview code{background:#f5f5f5;padding:1px 6px;border-radius:4px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:13px}
                        .md-preview pre{background:#f5f5f5;padding:12px;border-radius:var(--r-sm);overflow-x:auto}
                        .md-preview pre code{background:none;padding:0}
                        .md-preview blockquote{border-left:3px solid var(--c-primary);margin:8px 0;padding:4px 12px;color:var(--c-text-2);background:#fafafa}
                        .md-preview a{color:var(--c-primary)}
                        /* 调试结果页手机壳预览（0.9.31）：9:19.5 屏幕 + 机身 bezel + Dynamic Island；
                           0.9.33 起屏幕视口 1:1 还原真机逻辑宽度——壳 412px - bezel 11px×2 = 390px 屏幕
                           （iPhone 15/16 逻辑宽 393pt、Android 主流 360~412dp 的代表值； iframe 内
                           H5 的 px 布局/百分比宽/媒体查询断点与端上 WebView 同口径，消除按钮过宽失真） */
                        .phone-stage{display:flex;gap:28px;flex-wrap:wrap;align-items:flex-start;padding:24px;background:#ececf0;border-radius:var(--r-md);margin-bottom:12px}
                        .preview-split{display:flex;gap:28px;align-items:flex-start;flex-wrap:wrap;margin-bottom:12px}
                        .preview-split>.qr-side{flex:0 0 auto;max-width:340px}
                        .preview-split>.qr-side .qr-row{margin-bottom:0;flex-direction:column;align-items:flex-start}
                        .preview-split>.phone-stage{flex:1 1 340px;margin-bottom:0;min-width:0}
                        .phone-screen .openui-root{padding:12px 12px 36px;flex:1;min-height:0;overflow-y:auto;box-sizing:border-box;font-size:14px;color:#1a1a1a;background:#f6f6f8}
                        .phone-screen .openui-root>div{margin-bottom:0}
                        .phone-item{margin:0}
                        .phone-item .block-label{margin-bottom:10px}
                        .phone-frame{width:412px;aspect-ratio:9/19.5;background:#1b1b1f;border-radius:44px;padding:11px;box-sizing:border-box;box-shadow:0 14px 34px rgba(0,0,0,.28),inset 0 0 0 2px #3a3a40;position:relative;flex-shrink:0}
                        .phone-frame::before{content:"";position:absolute;top:11px;left:50%;transform:translateX(-50%);width:86px;height:24px;background:#1b1b1f;border-radius:13px;z-index:2;pointer-events:none}
                        .phone-frame::after{content:"";position:absolute;bottom:5px;left:50%;transform:translateX(-50%);width:96px;height:4px;border-radius:2px;background:rgba(255,255,255,.35);z-index:2;pointer-events:none}
                        .phone-screen{width:100%;height:100%;background:#fff;border-radius:34px;overflow:hidden;display:flex;flex-direction:column}
                        /* 状态栏安全区（0.9.34）：内容自状态栏下缘起排，Dynamic Island 只盖状态栏背景——
                           与真机 WebView 安全区行为一致，H5 顶部不再被刘海遮挡 */
                        .phone-statusbar{height:44px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:0 30px 0 34px;font-size:13px;font-weight:600;color:#1a1a1a;background:#fff}
                        .phone-statusbar svg{display:block}
                        .phone-screen iframe{display:block;width:100%;flex:1;min-height:0;border:0;background:#fff}
                        .phone-screen .md-preview{padding:12px 16px 36px;flex:1;min-height:0;overflow-y:auto;box-sizing:border-box;font-size:13px;line-height:1.6}
                        /* 扫码预览行（0.9.35）：二维码 + 说明 */
                        .qr-row{display:flex;gap:18px;align-items:center;padding:14px 16px;background:#fafafc;border:1px solid var(--c-border);border-radius:var(--r-md);margin-bottom:14px}
                        .qr-row svg{flex-shrink:0;border:1px solid var(--c-border);border-radius:var(--r-sm)}
                        .qr-row .meta{word-break:break-all}
                        /* 调试历史 case 卡网格（0.9.38 起三列；0.9.39 起二维码+信息合一卡） */
                        .qr-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-top:10px}
                        .qr-cell{display:flex;flex-direction:column;align-items:center;gap:12px;padding:14px;background:#fafafc;border:1px solid var(--c-border);border-radius:var(--r-md)}
                        .qr-cell svg{border:1px solid var(--c-border);border-radius:var(--r-sm)}
                        .qr-none{width:168px;height:168px;display:flex;align-items:center;justify-content:center;border:1px dashed var(--c-border);border-radius:var(--r-sm);color:var(--c-text-3);font-size:var(--fs-xs)}
                        .qr-info{width:100%;display:flex;flex-direction:column;gap:4px;font-size:var(--fs-xs);color:var(--c-text-3);line-height:1.6}
                        .qr-info .qr-line{display:flex;justify-content:space-between;align-items:center;gap:8px}
                        .qr-info .qr-head .qr-skill{font-weight:600;color:var(--c-text)}
                        .qr-info .qr-msg{color:var(--c-text-2);word-break:break-all;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
                        .qr-info .qr-actions{display:flex;gap:8px;margin-top:2px}
                        /* 发包管理 v2（依 Ardot 设计稿：双轨版本卡 + 双栏上传 + 分轨历史） */
                        .track-row{display:flex;gap:16px;flex-wrap:wrap;margin:16px 0}
                        .track-card{flex:1;min-width:320px;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);box-shadow:var(--shadow-sm);padding:16px 20px}
                        .track-head{display:flex;align-items:center;gap:8px;margin-bottom:10px}
                        .track-head .btn-ghost{margin-left:auto;height:28px;padding:4px 12px;font-size:var(--fs-xs)}
                        .track-tag{display:inline-block;padding:2px 10px;border-radius:var(--r-sm);font-size:var(--fs-xs);font-weight:500;white-space:nowrap}
                        .tag-debug{color:var(--c-success);background:var(--c-success-bg);border:1px solid var(--c-success-border)}
                        .tag-release{color:var(--c-primary);background:#e6f2ff;border:1px solid #b8d9ff}
                        .track-version{display:flex;align-items:center;gap:8px;margin-bottom:4px;flex-wrap:wrap}
                        .track-ver{font-size:24px;font-weight:600;color:var(--c-text)}
                        .track-code{font-size:var(--fs-sm);color:var(--c-text-2)}
                        .track-meta{font-size:var(--fs-xs);color:var(--c-text-3)}
                        .track-empty-text{color:var(--c-text-3);font-size:var(--fs-md);padding:16px 0;text-align:center}
                        .upload-head{margin-bottom:14px}
                        .upload-layout{display:flex;gap:16px;align-items:stretch;flex-wrap:wrap}
                        .upload-form{flex:1;min-width:320px;display:flex;flex-direction:column;gap:12px}
                        .form-grid-2{display:flex;gap:12px;flex-wrap:wrap}
                        .form-grid-2 .form-row{flex:1;min-width:180px}
                        .upload-side{width:380px;display:flex;flex-direction:column;gap:12px}
                        .upload-side .drop-zone{margin:0;flex:1;min-height:220px;display:flex;flex-direction:column;justify-content:center;align-items:center}
                        .upload-side .file-preview{margin:0;flex:1;align-items:flex-start}
                        .upload-side .progress-wrap{margin:0}
                        .upload-footer{display:flex;align-items:center;gap:12px;margin-top:16px;flex-wrap:wrap}
                        .upload-footer .hint{flex:1;min-width:240px;font-size:var(--fs-xs);color:var(--c-text-3)}
                        .history-split{display:flex;gap:16px;flex-wrap:wrap;align-items:flex-start;margin-bottom:24px}
                        .track-history{flex:1;min-width:340px;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);box-shadow:var(--shadow-sm);overflow:hidden}
                        .th-head{display:flex;align-items:center;gap:8px;padding:12px 16px 8px}
                        .th-title{font-size:var(--fs-md);font-weight:600;color:var(--c-text)}
                        .th-empty{color:var(--c-text-3);font-size:var(--fs-sm);padding:16px;text-align:center;border-top:1px solid #f0f0f0}
                        /* 系统设置 v2（2026-11-07）：额度表单标签上置 + 按钮右对齐带分隔线 */
                        .quota-form{display:flex;flex-direction:column;gap:14px}
                        .quota-row{display:flex;gap:16px;flex-wrap:wrap}
                        .quota-field{flex:1;min-width:220px}
                        .quota-field label{display:block;font-size:var(--fs-sm);color:var(--c-text-2);font-weight:500;margin-bottom:6px}
                        .quota-field .label-hint{color:var(--c-text-3);font-weight:400;margin-left:4px;font-size:var(--fs-xs)}
                        .quota-field input{width:100%;padding:10px 12px;border:1px solid #d9d9d9;border-radius:var(--r-sm);font-size:var(--fs-md);box-sizing:border-box;transition:border-color .2s}
                        .quota-field input:focus{outline:none;border-color:var(--c-primary);box-shadow:0 0 0 3px var(--c-primary-ring)}
                        .quota-actions{display:flex;justify-content:flex-end;padding-top:12px;border-top:1px solid #f0f0f0}
                        @media (max-width:640px){
                        @media (max-width:900px){
                        .sidebar{position:static;width:auto;height:auto;flex-direction:row;align-items:center;border-right:none;border-bottom:1px solid var(--c-border);overflow:visible}
                        .side-logo{height:50px;padding:0 16px}
                        .side-logo-text{font-size:14px}
                        .side-menu{flex-direction:row;overflow-x:auto;padding:6px 8px;gap:2px;flex:1}
                        .side-item{height:34px;padding:0 10px;font-size:13px}
                        .side-item .side-ico{display:none}
                        .side-spacer,.side-divider{display:none}
                        .side-user{height:auto;padding:0 16px;gap:8px}
                        .side-avatar{display:none}
                        }
                        @media (max-width:640px){
                        body>h1{font-size:20px}
                        body>h2{font-size:14px}
                        .cards{padding:12px;gap:12px}
                        .card{min-width:0;flex:1 1 calc(50% - 8px);padding:12px 14px}
                        .card-value{font-size:20px}
                        table{display:block;overflow-x:auto;-webkit-overflow-scrolling:touch;white-space:nowrap}
                        th,td{padding:10px 12px}
                        .wrap{margin:40px auto;padding:20px}
                        .apk-info-card{flex-direction:column;align-items:flex-start}
                        .apk-info-actions{width:100%;justify-content:flex-start}
                        .drop-zone{padding:32px 16px}
                        .upload-actions{text-align:left}
                        .page{padding:0 16px}
                        .settings-section{padding:16px}
                        .form-grid{flex-direction:column;align-items:stretch;gap:12px}
                        .field input,.whitelist-add input{width:100%}
                        .whitelist-add{flex-direction:column;align-items:stretch}
                        .skills-layout{grid-template-columns:1fr}
                        .skill-previewbar .push{margin-left:0;width:100%}
                        .track-card{min-width:100%}
                        .track-history{min-width:100%}
                        .upload-side{width:100%}
                        }
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    private fun FlowContent.statusBadge(status: String) {
        val classes = when (status) {
            "active" -> "badge badge-active"
            "revoked" -> "badge badge-revoked"
            "deleted" -> "badge badge-deleted"
            "open" -> "badge badge-open"
            "investigating" -> "badge badge-investigating"
            "closed" -> "badge badge-closed"
            "ignored" -> "badge badge-ignored"
            else -> "badge"
        }
        div(classes) {
            +when (status) {
                "active" -> "正常"
                "revoked" -> "已禁用"
                "deleted" -> "已删除"
                "open" -> "待处理"
                "investigating" -> "处理中"
                "closed" -> "已关闭"
                "ignored" -> "已忽略"
                else -> status
            }
        }
    }

    fun diagnosisPage(
        issues: List<IssueReportService.IssueRow>,
        message: String? = null,
    ): String = createHTML().html {
        adminHead("问题诊断 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"问题诊断" }
            if (message != null) {
                div("toast ${if (message.startsWith("已") || message.startsWith("成功") || message.startsWith("状态")) "toast-ok" else "toast-err"}") { +message }
            }
            issuesSection(issues)
        }
    }

    private fun FlowContent.whitelistSection(entries: List<AiEngineerWhitelistService.Entry>) {
        section("settings-section") {
            attributes["id"] = "whitelist"
            div("section-head") {
                h2("section-title") { +"AI 工程师白名单" }
                p("section-desc") { +"空表 = 所有用户可诊断，但均不可交付代码；加入邮箱后才开放写链路。" }
            }
            div("whitelist-add") {
                form(action = "/admin/settings/whitelist", method = FormMethod.post, classes = "inline") {
                    input(type = InputType.email, name = "email") {
                        placeholder = "user@example.com"
                        attributes["aria-label"] = "白名单邮箱"
                    }
                    input(type = InputType.submit, classes = "btn-sm btn-primary") { value = "添加" }
                }
            }
            if (entries.isEmpty()) {
                div("empty-state") { +"暂无白名单记录" }
            } else {
                table {
                    tr {
                        th { +"ID" }
                        th { +"邮箱" }
                        th { +"加入时间" }
                        th(classes = "col-actions") { +"操作" }
                    }
                    entries.forEach { e ->
                        tr {
                            td { +e.id.toString() }
                            td { +e.email }
                            td { +fmtTs(e.createdAt) }
                            td {
                                form(
                                    action = "/admin/settings/whitelist/revoke",
                                    method = FormMethod.post,
                                    classes = "inline",
                                ) {
                                    input(type = InputType.hidden, name = "email") { value = e.email }
                                    input(type = InputType.submit, classes = "btn-sm btn-danger") { value = "移除" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.issuesSection(issues: List<IssueReportService.IssueRow>) {
        p("meta") { +"用户从 App 上报的问题，已脱敏处理；可更新状态或手动重试同步 GitHub。" }
        if (issues.isEmpty()) {
            div("card apk-empty") {
                div("apk-empty-text") { +"暂无用户上报问题" }
            }
        } else {
            table {
                tr {
                    th { +"ID" }
                    th { +"类别" }
                    th { +"标题" }
                    th { +"状态" }
                    th { +"GitHub" }
                    th { +"上报时间" }
                    th(classes = "col-actions") { +"操作" }
                }
                issues.forEach { issue ->
                    tr {
                        td { +issue.id.toString() }
                        td { +issue.category }
                        td { +issue.title }
                        td { statusBadge(issue.status) }
                        td {
                            if (issue.githubIssueUrl.isNotBlank()) {
                                a(issue.githubIssueUrl, target = "_blank") { +"#${issue.githubIssueNumber}" }
                            } else {
                                +"未同步"
                            }
                        }
                        td { +fmtTs(issue.createdAt) }
                        td {
                            div("row-actions") {
                                // 状态切换
                                val nextStatus = when (issue.status) {
                                    "open" -> "investigating"
                                    "investigating" -> "closed"
                                    "closed" -> "open"
                                    else -> "open"
                                }
                                form(
                                    action = "/admin/diagnosis/issues/${issue.id}/status",
                                    method = FormMethod.post,
                                    classes = "inline",
                                ) {
                                    input(type = InputType.hidden, name = "status") { value = nextStatus }
                                    input(type = InputType.submit, classes = "btn-sm") {
                                        value = when (nextStatus) {
                                            "investigating" -> "开始处理"
                                            "closed" -> "关闭"
                                            else -> "重新打开"
                                        }
                                    }
                                }
                                if (issue.githubIssueUrl.isBlank()) {
                                    form(
                                        action = "/admin/diagnosis/issues/${issue.id}/sync-github",
                                        method = FormMethod.post,
                                        classes = "inline",
                                    ) {
                                        input(type = InputType.submit, classes = "btn-sm btn-go") { value = "同步 GitHub" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 侧边栏导航图标（16px 线性 stroke，currentColor 跟随文字色/选中态）。 */
    private val sideIcons = mapOf(
        "overview" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="2" y="2" width="5" height="5" rx="1"/><rect x="9" y="2" width="5" height="5" rx="1"/><rect x="2" y="9" width="5" height="5" rx="1"/><rect x="9" y="9" width="5" height="5" rx="1"/></svg>""",
        "users" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4"><circle cx="8" cy="5" r="3"/><path d="M2.5 13.5c1-2.8 3-4 5.5-4s4.5 1.2 5.5 4"/></svg>""",
        "traffic" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M3 13V8M8 13V3M13 13V6"/></svg>""",
        "calls" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M3 4h10M3 8h10M3 12h7"/></svg>""",
        "agent" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="3" y="5" width="10" height="7" rx="2"/><path d="M8 5V3"/><circle cx="6" cy="8.5" r=".9" fill="currentColor" stroke="none"/><circle cx="10" cy="8.5" r=".9" fill="currentColor" stroke="none"/></svg>""",
        "channels" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4"><circle cx="4" cy="4" r="1.8"/><circle cx="12" cy="4" r="1.8"/><circle cx="8" cy="12" r="1.8"/><path d="M5.3 5.2l1.7 5M10.7 5.2l-1.7 5M5.8 4h4.4"/></svg>""",
        "skills" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><path d="M9 1.5 3.5 9H7.5L6.5 14.5 12.5 7H8.5z"/></svg>""",
        "diagnosis" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><path d="M2.5 3.5h11v7h-6l-3 3v-3h-2z"/></svg>""",
        "release" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><path d="M8 2l5.5 3v6L8 14 2.5 11V5z"/><path d="M2.5 5 8 8l5.5-3M8 8v6"/></svg>""",
        "settings" to """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M3 5h10M3 11h10"/><circle cx="6" cy="5" r="1.6" fill="var(--c-surface)"/><circle cx="10.5" cy="11" r="1.6" fill="var(--c-surface)"/></svg>""",
    )

    /** 侧边栏导航项（href, 图标 key, 文案）——顺序对齐 Ardot 设计稿 Sidebar 组件。 */
    private val sideItems = listOf(
        Triple("/admin", "overview", "数据概览"),
        Triple("/admin/users", "users", "用户管理"),
        Triple("/admin/traffic", "traffic", "流量分析"),
        Triple("/admin/llm/calls", "calls", "调用流水"),
        Triple("/admin/agent/sessions", "agent", "Agent 会话"),
        Triple("/admin/channels", "channels", "渠道管理"),
        Triple("/admin/skills", "skills", "技能中心"),
        Triple("/admin/diagnosis", "diagnosis", "问题诊断"),
        Triple("/admin/release", "release", "发包管理"),
        Triple("/admin/settings", "settings", "系统设置"),
    )

    /** 左侧边栏（依 Ardot 设计稿 Sidebar 232：Logo 区/菜单/弹性位/分隔线/用户区）。
     *  固定定位 + 正文左让 232px（随导航内联下发，登录页等无侧栏页零影响）；
     *  选中态走最长前缀匹配（详情页如 /admin/skills/x 也亮「技能中心」）。 */
    private fun FlowContent.navBar() {
        div("sidebar") {
            div("side-logo") {
                div("side-logo-mark") { +"破" }
                div("side-logo-text") { +"PoLang 管理后台" }
            }
            div("side-menu") {
                sideItems.forEach { (href, icon, label) ->
                    a(href, classes = "side-item") {
                        span("side-ico") { unsafe { raw(sideIcons.getValue(icon)) } }
                        +label
                    }
                }
            }
            div("side-spacer") {}
            div("side-divider") {}
            div("side-user") {
                div("side-avatar") { +"A" }
                div("side-user-name") { +"admin" }
                a("/admin/logout", classes = "side-logout") { +"退出" }
            }
        }
        style {
            unsafe {
                raw("body{padding-left:232px}@media(max-width:900px){body{padding-left:0}}")
            }
        }
        script {
            unsafe {
                raw(
                    """document.addEventListener('DOMContentLoaded',function(){var path=window.location.pathname;var best=null,len=-1;document.querySelectorAll('.side-item').forEach(function(link){var href=link.getAttribute('href')||'';if(href==='/admin/logout')return;if(path===href||path.indexOf(href+'/')===0){if(href.length>len){len=href.length;best=link;}}});if(best){best.classList.add('active');}});""",
                )
            }
        }
    }

    private fun FlowContent.userTabs(usersCount: Long, devicesCount: Long, currentPath: String) {
        div("subtabs") {
            a("/admin/users", classes = if (currentPath == "/admin/users") "subtab active" else "subtab") {
                +"注册用户 ($usersCount)"
            }
            a("/admin/devices", classes = if (currentPath == "/admin/devices") "subtab active" else "subtab") {
                +"未注册设备 ($devicesCount)"
            }
        }
    }

    private fun FlowContent.statCard(label: String, value: String) {
        div("card") {
            div("card-label") { +label }
            div("card-value") { +value }
        }
    }

    /** 简易 SVG 柱状图：日期标签旋转 -40° 防重叠，密集时稀疏标注；每柱 <title> 悬浮提示；柱顶显示 compact 数值。 */
    private fun svgBars(
        values: List<Double>,
        labels: List<String>,
        labelFormatter: (Double) -> String = ::fmtVal,
    ): String {
        if (values.isEmpty()) return "<p>无数据</p>"
        val maxV = values.max().coerceAtLeast(1.0)
        val w = 760
        val topPad = 18
        val plotH = 100
        val padBottom = 46
        val h = topPad + plotH + padBottom
        val barW = (w / values.size).coerceAtLeast(2)
        val step = if (values.size > 16) (values.size / 8).coerceAtLeast(1) else 1
        val sb = StringBuilder()
        sb.append("""<svg class="chart" viewBox="0 0 $w $h" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet">""")
        values.forEachIndexed { i, v ->
            val barH = (v / maxV * plotH).toInt().coerceAtLeast(1)
            val x = i * barW
            val y = topPad + plotH - barH
            val cx = x + barW / 2
            val label = esc(labelFormatter(v))
            sb.append("""<rect x="$x" y="$y" width="${(barW - 2).coerceAtLeast(1)}" height="$barH" rx="2" fill="#3b82f6"><title>${esc(labels[i])}: ${fmtVal(v)}</title></rect>""")
            sb.append("""<text x="$cx" y="${y - 4}" font-size="8" fill="#374151" text-anchor="middle">$label</text>""")
            if (i % step == 0) {
                sb.append("""<text x="$cx" y="${topPad + plotH + 10}" font-size="9" fill="#6b7280" text-anchor="end" transform="rotate(-40 $cx ${topPad + plotH + 10})">${esc(labels[i].takeLast(5))}</text>""")
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun fmtVal(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else formatCostCny(v)

    /** 整数类指标（调用数、Token 数）compact 显示：≥1M 用 M，≥1k 用 k，否则原值。 */
    private fun compactCount(v: Double): String = when {
        kotlin.math.abs(v) >= 1_000_000 -> {
            val m = v / 1_000_000.0
            if (m % 1.0 == 0.0) "${m.toLong()}M" else "%.1fM".format(m)
        }
        kotlin.math.abs(v) >= 1_000 -> {
            val k = v / 1_000.0
            if (k % 1.0 == 0.0) "${k.toLong()}k" else "%.1fk".format(k)
        }
        else -> v.toLong().toString()
    }

    /** 成本 compact 显示：≥1k 用 k，≥1M 用 M；低于 1k 保留原精度。 */
    private fun compactCost(v: Double): String = when {
        kotlin.math.abs(v) >= 1_000_000 -> {
            val m = v / 1_000_000.0
            if (m % 1.0 == 0.0) "${m.toLong()}M" else "%.2fM".format(m)
        }
        kotlin.math.abs(v) >= 1_000 -> {
            val k = v / 1_000.0
            if (k % 1.0 == 0.0) "${k.toLong()}k" else "%.2fk".format(k)
        }
        else -> formatCostCny(v)
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------- M2 推理追溯页面辅助 ----------

    /** 组装查询串；空值/空白参数剔除，值 URL 编码。返回 ""（无参数）或 "?a=1&b=2"。 */
    private fun queryString(vararg pairs: Pair<String, String?>): String {
        val q = pairs.filter { !it.second.isNullOrBlank() }
            .joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, Charsets.UTF_8)}" }
        return if (q.isEmpty()) "" else "?$q"
    }

    private fun llmStatusClass(status: String): String = when {
        status == "ok" -> "st-ok"
        status == "upstream_error" -> "st-error"
        status.startsWith("blocked") -> "st-blocked"
        else -> "st-muted"
    }

    private fun sessionStatusClass(status: String): String = when (status) {
        "running" -> "st-ok"
        "error" -> "st-error"
        else -> "st-muted"
    }

    /** 假 running：status=running 且最后事件（缺省回退创建时间，不误标首轮未落库的年轻会话）距今 >30min。 */
    private fun isStalled(s: AgentSessionRow): Boolean =
        s.status == "running" && (s.lastEventAt ?: s.createdAt) < System.currentTimeMillis() - STALLED_THRESHOLD_MS

    private fun isOrphanObservation(p: AgentEventPayload.Observation, actionIds: Set<Long>): Boolean =
        p.actionEventId == 0L || p.actionEventId !in actionIds

    private fun eventClass(e: AgentEventRow, actionIds: Set<Long>): String = when (val p = e.payload) {
        null -> "ev-warn"
        is AgentEventPayload.Message -> if (e.role == "user") "ev-user" else "ev-assistant"
        is AgentEventPayload.Action -> "ev-action"
        is AgentEventPayload.Observation -> buildString {
            append("ev-obs")
            if (isOrphanObservation(p, actionIds)) append(" ev-orphan")
            if (p.content.startsWith("error")) append(" ev-err")
        }
        is AgentEventPayload.HtmlCard -> "ev-card"
        is AgentEventPayload.Finish -> "ev-finish " + when (p.reason) {
            "done" -> "st-ok"
            "error" -> "st-error"
            else -> "st-muted"
        }
        is AgentEventPayload.Condensation -> "ev-cond"
    }

    /**
     * 事件体渲染。转义铁律：一切用户内容经 `+`（kotlinx.html 默认转义）上屏；
     * html_card 只显示 summary + 字符数，永不渲染 HTML 本体。
     */
    private fun FlowContent.eventBody(e: AgentEventRow, actionIds: Set<Long>) {
        when (val p = e.payload) {
            null -> {
                span { +"无法解析的事件载荷" }
                if (!e.payloadRaw.isNullOrEmpty()) {
                    pre { +e.payloadRaw.take(200) }
                }
            }
            is AgentEventPayload.Message -> div("ev-content") { +p.content }
            is AgentEventPayload.Action -> {
                div("ev-content") {
                    b { +p.tool }
                    if (p.reversible) span("meta-inline") { +"（可逆）" }
                }
                details {
                    summary { +"argsJson" }
                    pre { +p.argsJson }
                }
            }
            is AgentEventPayload.Observation -> {
                // 孤儿 observation（门控拦截痕迹）不隐藏，徽标标注后照常成条
                if (isOrphanObservation(p, actionIds)) {
                    span("st-badge st-blocked") { +"门控拦截/未配对" }
                    +" "
                }
                span { +p.content }
            }
            is AgentEventPayload.HtmlCard -> div("ev-content") { +"H5 卡：${p.summary}（${p.html.length} 字符）" }
            is AgentEventPayload.Finish -> span { +"终态：${p.reason}" }
            is AgentEventPayload.Condensation -> div("meta-inline") { +"上下文压缩至 #${p.upToEventId}：${p.summary}" }
        }
    }

    private fun fmt(d: Double): String = formatCostCny(d)

    private fun fmtTs(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.ofHours(8)).toString().take(19).replace("T", " ")

    private fun formatBytes(b: Long): String = when {
        b <= 0 -> "0 B"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        b < 1024 * 1024 * 1024 -> "${String.format("%.2f", b / (1024.0 * 1024.0))} MB"
        else -> "${String.format("%.2f", b / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
