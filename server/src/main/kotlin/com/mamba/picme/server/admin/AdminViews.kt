package com.mamba.picme.server.admin

import com.mamba.picme.server.appJson
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
import kotlinx.html.body
import kotlinx.html.ButtonType
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
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

    fun overviewPage(ov: OverviewRow, range: RangeStats, days: Int, metric: String): String = createHTML().html {
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
            h2 { +"最近调用（${calls.size}）" }
            table {
                tr {
                    th { +"时间" }
                    th { +"模型" }
                    th { +"Provider" }
                    th { +"Prompt" }
                    th { +"Completion" }
                    th { +"Total" }
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
                        td { +fmt(c.costCny) }
                        td { +c.respBytes.toString() }
                        td { +c.status }
                        td { +(c.latencyMs?.toString() ?: "—") }
                    }
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
                    form(action = "/admin/settings", method = FormMethod.post, classes = "form-grid") {
                        div("field") {
                            label { +"新注册账号上限（free，>0）" }
                            input(type = InputType.number, name = "free_llm_quota") {
                                value = snap.freeLlmQuota.toString()
                                attributes["min"] = "1"
                            }
                        }
                        div("field") {
                            label { +"访客设备上限（guest，>0）" }
                            input(type = InputType.number, name = "guest_llm_quota") {
                                value = snap.guestLlmQuota.toString()
                                attributes["min"] = "1"
                            }
                        }
                        div("field") {
                            label { +"Browser 每日会话配额（browser，>0）" }
                            input(type = InputType.number, name = "browser_daily_quota") {
                                value = snap.browserDailyQuota.toString()
                                attributes["min"] = "1"
                            }
                        }
                        div("form-actions") {
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
            h1 { +"发包" }
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

    private fun FlowContent.androidTrackCard(channelLabel: String, info: AndroidTrackInfo) {
        if (info.fileExists) {
            div("card apk-info-card") {
                div("apk-info-header") {
                    div("apk-info-title") {
                        span("apk-badge") { +"当前 $channelLabel" }
                        +"${info.version.ifBlank { "未命名版本" }}（versionCode ${info.versionCode}）"
                    }
                    div("apk-info-meta") {
                        span { +info.fileSize }
                        span("apk-meta-sep") { +"·" }
                        span { +info.lastModified }
                    }
                }
                div("apk-info-actions") {
                    a(href = info.cosUrl, target = "_blank", classes = "btn btn-sm btn-primary") { +"COS 直链" }
                }
            }
        } else {
            div("card apk-info-card apk-empty") {
                div("apk-empty-icon") { +"📦" }
                div("apk-empty-text") { +"COS 上暂无 $channelLabel 包" }
            }
        }
    }

    private fun FlowContent.androidReleasePanel(data: AndroidReleaseData) {
        val history = data.history
        // 双轨当前版本信息卡片
        androidTrackCard("debug", data.debug)
        androidTrackCard("release", data.release)

        // 上传区域
        h2 { +"上传新版本" }
        div("card upload-card") {
            // 渠道选择（默认 debug：唯一开发者日常遛狗主轨）
            div("form-row") {
                label { +"渠道" }
                select {
                    attributes["id"] = "apk-channel"
                    attributes["class"] = "form-input"
                    option {
                        value = "debug"
                        selected = true
                        +"debug（debug.keystore，日常自测）"
                    }
                    option {
                        value = "release"
                        +"release（picme-release.jks，Play 对齐）"
                    }
                }
            }
            // 版本号输入
            div("form-row") {
                label { +"版本号" }
                input(type = InputType.text, name = "version", classes = "form-input") {
                    attributes["id"] = "apk-version"
                    placeholder = "如 1.0.11"
                }
            }
            // versionCode 输入（OTA 更新比较依据）
            div("form-row") {
                label { +"versionCode" }
                input(type = InputType.number, name = "versionCode", classes = "form-input") {
                    attributes["id"] = "apk-version-code"
                    placeholder = "如 10042（OTA 更新判定依据）"
                }
            }
            // 更新说明
            div("form-row") {
                label { +"更新说明" }
                textArea(classes = "form-input") {
                    attributes["id"] = "apk-changelog"
                    attributes["rows"] = "3"
                    attributes["placeholder"] = "可选，App 更新弹窗中展示"
                }
            }

            // 拖拽上传区
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

            // 上传按钮
            div("upload-actions") {
                button(type = ButtonType.button, classes = "btn btn-primary btn-upload") {
                    attributes["id"] = "upload-btn"
                    attributes["onclick"] = "uploadApk()"
                    +"上传"
                }
            }
        }

        // 上传历史
        h2 { +"上传历史（最近 ${history.size} 条）" }
        if (history.isEmpty()) {
            div("card apk-empty") {
                div("apk-empty-text") { +"暂无上传记录" }
            }
        } else {
            table {
                tr {
                    th { +"时间" }
                    th { +"渠道" }
                    th { +"版本号" }
                    th { +"文件名" }
                    th { +"大小" }
                    th { +"状态" }
                }
                history.forEach { h ->
                    tr {
                        td { +fmtTs(h.createdAt) }
                        td { +(h.channel.ifBlank { "release" }) }
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
                          --r-sm:4px;
                          --r-md:8px;
                          --r-lg:12px;
                          --shadow-sm:0 1px 4px rgba(0,0,0,.06);
                          --shadow-md:0 4px 12px rgba(0,0,0,.08);
                        }
                        *{box-sizing:border-box}
                        body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;margin:0;background:var(--c-bg);color:var(--c-text);padding-bottom:40px}
                        body>h1,body>h2,body>.cards,body>table,body>p{max-width:1200px;margin-left:auto;margin-right:auto;padding-left:24px;padding-right:24px}
                        body>h1{font-size:var(--fs-xxl);font-weight:600;margin-top:24px;margin-bottom:16px;color:var(--c-text)}
                        body>h2{font-size:var(--fs-lg);font-weight:600;color:var(--c-text);margin-top:24px;margin-bottom:12px}
                        .meta{font-size:var(--fs-sm);color:var(--c-text-3);margin-top:-8px;margin-bottom:16px}
                        .wrap{max-width:420px;margin:80px auto;padding:32px;background:var(--c-surface);border-radius:var(--r-md);box-shadow:var(--shadow-md)}
                        .cards{display:flex;flex-wrap:wrap;gap:16px;padding:16px 24px}
                        .card{background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);padding:16px 20px;min-width:140px;box-shadow:var(--shadow-sm);transition:transform .2s}
                        .card:hover{transform:translateY(-2px)}
                        .card-label{font-size:var(--fs-sm);color:var(--c-text-2);margin-bottom:4px}
                        .card-value{font-size:var(--fs-xxl);font-weight:600;color:var(--c-primary);font-variant-numeric:tabular-nums}
                        .nav{background:var(--c-text);color:var(--c-surface);padding:0 24px;display:flex;align-items:center;gap:4px;height:56px;box-shadow:var(--shadow-sm);position:sticky;top:0;z-index:100}
                        .nav-brand{font-weight:600;font-size:var(--fs-lg);margin-right:32px;white-space:nowrap;color:var(--c-surface)}
                        .nav-links{display:flex;gap:4px}
                        .nav-link{color:#b0b8c4;text-decoration:none;padding:8px 16px;border-radius:var(--r-sm);font-size:var(--fs-md);transition:all .2s}
                        .nav-link:hover{background:rgba(255,255,255,.1);color:var(--c-surface)}
                        .nav-link.active{background:var(--c-primary);color:var(--c-surface)}
                        .nav-spacer{flex:1}
                        .nav-logout{color:var(--c-warning)}
                        .nav-logout:hover{background:rgba(255,156,0,.1);color:var(--c-warning)}
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
                        .actions-bar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;max-width:1200px;margin:16px auto;padding:0 24px}
                        .limit-card{max-width:1200px;margin:16px auto;padding:16px 24px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
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
                        .toast{max-width:1200px;margin:16px auto;padding:12px 20px;border-radius:6px;font-size:var(--fs-md)}
                        .toast-ok{color:var(--c-success);background:var(--c-success-bg);border:1px solid var(--c-success-border)}
                        .toast-err{color:var(--c-danger);background:var(--c-danger-bg);border:1px solid var(--c-danger-border)}
                        .apk-info-card{max-width:1200px;margin:16px auto;padding:20px 24px;display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);box-shadow:var(--shadow-sm)}
                        .apk-empty{text-align:center;padding:40px 24px;justify-content:center;flex-direction:column}
                        .apk-empty-icon{font-size:48px;margin-bottom:12px}
                        .apk-empty-text{color:var(--c-text-3);font-size:var(--fs-md)}
                        .apk-info-header{flex:1;min-width:200px}
                        .apk-info-title{font-size:18px;font-weight:600;color:var(--c-text);display:flex;align-items:center;gap:10px}
                        .apk-badge{background:var(--c-primary);color:var(--c-surface);font-size:var(--fs-xs);padding:2px 10px;border-radius:12px;font-weight:500}
                        .apk-info-meta{color:var(--c-text-3);font-size:var(--fs-sm);margin-top:6px;display:flex;align-items:center;gap:8px}
                        .apk-meta-sep{color:#ccc}
                        .apk-info-actions{display:flex;gap:10px;align-items:center}
                        .upload-card{max-width:1200px;margin:16px auto;padding:24px;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);box-shadow:var(--shadow-sm)}
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
                        .subtabs{display:flex;gap:4px;max-width:1200px;margin:16px auto 0;padding:0 24px;border-bottom:1px solid var(--c-border)}
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
                        .page-controls{display:flex;align-items:center;justify-content:space-between;max-width:1200px;margin:16px auto 0;padding:0 24px;gap:12px;flex-wrap:wrap;border-bottom:1px solid var(--c-border)}
                        .ctrl-group{display:flex;gap:2px}
                        .ctrl{color:var(--c-text-2);text-decoration:none;padding:8px 14px;font-size:var(--fs-md);border-bottom:2px solid transparent;margin-bottom:-1px;transition:all .2s}
                        .ctrl:hover{color:var(--c-primary)}
                        .ctrl.active{color:var(--c-primary);border-bottom-color:var(--c-primary);font-weight:500}
                        .model-top{max-width:1200px;margin:16px auto;padding:16px 24px;background:var(--c-surface);border:1px solid var(--c-border);border-radius:var(--r-md);box-shadow:var(--shadow-sm)}
                        /* 设置页新结构 */
                        .page{max-width:1200px;margin:0 auto;padding:0 24px}
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
                        .whitelist-add input{width:280px}
                        .empty-state{text-align:center;padding:32px 24px;color:var(--c-text-3);font-size:var(--fs-md);border:1px dashed var(--c-border);border-radius:var(--r-md)}
                        .settings-section table{border-radius:var(--r-md)}
                        .settings-section .form-actions{border-top:none;margin-top:0;padding-top:0;justify-content:flex-end}
                        @media (max-width:640px){
                        body>h1{font-size:20px}
                        body>h2{font-size:14px}
                        .nav{padding:0 16px;height:50px}
                        .nav-brand{font-size:14px;margin-right:16px}
                        .nav-link{padding:6px 10px;font-size:13px;white-space:nowrap}
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
                    }
                    +" "
                    input(type = InputType.submit, classes = "btn-sm btn-go") { value = "添加" }
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

    private fun FlowContent.navBar() {
        div("nav") {
            div("nav-brand") { +"PoLang 管理后台" }
            div("nav-links") {
                a("/admin", classes = "nav-link") { +"概览" }
                a("/admin/users", classes = "nav-link") { +"用户" }
                a("/admin/traffic", classes = "nav-link") { +"流量" }
                a("/admin/channels", classes = "nav-link") { +"渠道" }
                a("/admin/settings", classes = "nav-link") { +"设置" }
                a("/admin/diagnosis", classes = "nav-link") { +"问题诊断" }
                a("/admin/release", classes = "nav-link") { +"发包" }
            }
            div("nav-spacer") {}
            a("/admin/logout", classes = "nav-link nav-logout") { +"退出" }
        }
        script {
            unsafe {
                raw(
                    """document.addEventListener('DOMContentLoaded',function(){var path=window.location.pathname;var links=document.querySelectorAll('.nav-link');links.forEach(function(link){if(link.getAttribute('href')===path){link.classList.add('active');}});});""",
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
