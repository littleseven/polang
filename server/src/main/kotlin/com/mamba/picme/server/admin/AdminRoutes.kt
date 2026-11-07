package com.mamba.picme.server.admin

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asToolsByClass
import com.mamba.picme.server.analytics.Price
import com.mamba.picme.server.analytics.defaultPrices
import com.mamba.picme.server.agent.AgentBridgeRegistry
import com.mamba.picme.server.agent.AgentChannelKind
import com.mamba.picme.server.agent.AgentRunner
import com.mamba.picme.server.agent.AgentSessionRepository
import com.mamba.picme.server.agent.AgentSessionStatus
import com.mamba.picme.server.agent.AgentTargetResolver
import com.mamba.picme.server.agent.DeviceToolService
import com.mamba.picme.server.agent.LoadSkillToolService
import com.mamba.picme.server.agent.OpenUiTurnNative
import com.mamba.picme.server.agent.OverlaySkillCenter
import com.mamba.picme.server.agent.ServerToolService
import com.mamba.picme.server.agent.SkillCenter
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.AiEngineerWhitelistService
import com.mamba.picme.server.auth.GuestService
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.ApkUploads
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.IosUdidRegistrations
import com.mamba.picme.server.issue.GitHubIssueClient
import com.mamba.picme.server.issue.IssueReportService
import com.mamba.picme.server.llm.ChannelBalanceService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import com.mamba.picme.server.llm.ChannelInput
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.llm.ChannelRepository
import com.mamba.picme.server.llm.parseModelMapLines
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

/** 技能调试 playground：合成会话 owner（64 hex 与真实 token_hash 同形；输入域隔离，零碰撞，绝不落 account 表）。 */
private val SKILL_DEBUG_OWNER_HASH = AccountService.sha256("admin-skill-debug")

/** 技能调试会话的 model 标记：modelMap 未命中回退渠道 defaultModel；回放列表据此辨识调试会话。 */
private const val SKILL_DEBUG_MODEL = "skill-debug"

/** 技能调试单轮超时（与 /agent 路由 ROUND_TIMEOUT_MS 同口径：合法长轮上界 20 迭代 × ~45s）。 */
private const val SKILL_DEBUG_ROUND_TIMEOUT_MS: Long = 15 * 60 * 1000L

/**
 * 管理后台路由：/admin 下全部页面。主 app-token 拦截器（Application.module）对 /admin 前缀放行，
 * 由各受保护页面顶部的 adminGuard 接管认证（ADMIN_TOKEN 为空 → 503 禁用）。
 */
fun Route.adminRoute(
    adminToken: String,
    cosService: CosService,
    balanceService: ChannelBalanceService,
    prices: Map<String, Price> = defaultPrices(),
    issueReportService: IssueReportService = IssueReportService(GitHubIssueClient(HttpClient(CIO), "", "")),
    skillCenter: SkillCenter? = null,
    agentRunner: AgentRunner? = null,
    agentBridge: AgentBridgeRegistry? = null,
) {
    // 技能调试 playground 跑轮协程域：模块级长存（SupervisorJob 单轮失败不连坐）
    val skillDebugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    route("/admin") {
        get("/login") {
            if (adminToken.isBlank()) {
                call.respondText("admin disabled", contentType = ContentType.Text.Plain, status = HttpStatusCode.ServiceUnavailable)
            } else {
                call.respondText(AdminViews.loginPage(), ContentType.Text.Html)
            }
        }

        post("/login") {
            val params = call.receiveParameters()
            val password = params["password"] ?: ""
            if (adminToken.isNotBlank() && password == adminToken) {
                call.response.cookies.append(
                    Cookie(
                        name = AdminAuth.COOKIE_NAME,
                        value = AdminAuth.expectedCookieValue(adminToken),
                        path = "/admin",
                        httpOnly = true,
                        secure = call.isHttps(),
                        // Ktor 3.0.3 的 Cookie 无 sameSite 字段（3.1.0 才加），用 extensions 追加原始属性
                        extensions = mapOf("SameSite" to "Lax"),
                    ),
                )
                call.respondRedirect("/admin")
            } else {
                call.respondText(
                    AdminViews.loginPage(failed = true),
                    ContentType.Text.Html,
                    HttpStatusCode.Unauthorized,
                )
            }
        }

        get("/logout") {
            call.response.cookies.append(
                Cookie(
                    name = AdminAuth.COOKIE_NAME,
                    value = "",
                    path = "/admin",
                    expires = GMTDate(0),
                    httpOnly = true,
                    secure = call.isHttps(),
                    extensions = mapOf("SameSite" to "Lax"),
                ),
            )
            call.respondRedirect("/admin/login")
        }

        get {
            if (!call.adminGuard(adminToken)) return@get
            val now = System.currentTimeMillis()
            val days = parseDays(call.request.queryParameters["days"], listOf(7, 14), 7)
            val metric = parseMetric(call.request.queryParameters["metric"])
            val ov = AdminQueries.overview(now)
            val range = AdminQueries.rangeStats(days, now, prices)
            val browser = AdminQueries.browserOverview(now)
            call.respondText(AdminViews.overviewPage(ov, range, days, metric, browser), ContentType.Text.Html)
        }

        get("/users") {
            if (!call.adminGuard(adminToken)) return@get
            val rows = AdminQueries.usersList()
            call.respondText(
                AdminViews.usersPage(rows, AdminQueries.devicesCount()),
                ContentType.Text.Html,
            )
        }

        get("/devices") {
            if (!call.adminGuard(adminToken)) return@get
            val platformFilter = call.request.queryParameters["platform"]?.takeIf { it.isNotBlank() }
            val rows = AdminQueries.devicesList(platform = platformFilter)
            call.respondText(
                AdminViews.devicesPage(
                    rows,
                    AdminQueries.usersCount(),
                    SettingsService.snapshot().guestLlmQuota,
                    platformFilter,
                ),
                ContentType.Text.Html,
            )
        }

        // 供设备列表「复制」按钮调用:返回完整 device_id(cookie 鉴权;不进 HTML)。
        get("/devices/{id}/raw") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val deviceId = AdminQueries.deviceRawId(id)
            if (deviceId == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val body = buildJsonObject { put("device_id", deviceId) }.toString()
            call.respondText(body, ContentType.Application.Json)
        }

        post("/devices/{id}/delete") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) GuestService.deleteById(id)
            call.respondRedirect("/admin/devices")
        }

        // 重置访客设备已用额度。
        post("/devices/{id}/reset-quota") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) GuestService.resetQuota(id)
            call.respondRedirect("/admin/devices")
        }

        get("/users/{id}") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val detail = AdminQueries.userDetail(id)
            if (detail == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val calls = AdminQueries.recentCalls(id, 50)
            call.respondText(AdminViews.userDetailPage(detail, calls), ContentType.Text.Html)
        }

        // 供用户列表「复制」按钮调用：返回完整 token（cookie 鉴权；不进列表 HTML）。
        get("/users/{id}/token") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            val token = if (id != null) AccountService.rawToken(id) else null
            if (token == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val body = buildJsonObject { put("token", token) }.toString()
            call.respondText(body, ContentType.Application.Json)
        }

        post("/users/{id}/revoke") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) AccountService.setStatus(id, "revoked")
            call.respondRedirect("/admin/users")
        }

        post("/users/{id}/unrevoke") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) AccountService.setStatus(id, "active")
            call.respondRedirect("/admin/users")
        }

        // 重置单账号已用额度（清零计数、保留 llm_call_log 历史）。
        post("/users/{id}/reset-quota") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) AccountService.resetQuota(id)
            call.respondRedirect("/admin/users/$id")
        }

        // 修改单账号调用上限（limit=0 等价禁用但保留 token）。
        post("/users/{id}/limit") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            val limit = call.receiveParameters()["limit"]?.toIntOrNull()
            if (id != null && limit != null && limit >= 0) {
                AccountService.setLimit(id, limit)
            }
            call.respondRedirect("/admin/users/$id")
        }

        // 管理后台「删除账户及数据」：立即物理删除账号 + 调用日志（隐私合规「立即删除」）。
        post("/users/{id}/delete") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) AccountService.purgeAccount(id)
            call.respondRedirect("/admin/users")
        }

        get("/traffic") {
            if (!call.adminGuard(adminToken)) return@get
            val now = System.currentTimeMillis()
            val days = parseDays(call.request.queryParameters["days"], listOf(7, 14, 30, 90), 30)
            val metric = parseMetric(call.request.queryParameters["metric"])
            call.respondText(AdminViews.trafficPage(AdminQueries.rangeStats(days, now, prices), days, metric), ContentType.Text.Html)
        }

        // ── M2 推理追溯：调用流水 / Agent 会话回放（全部只读 GET）──

        // 调用流水：id 倒序游标分页（before），status/provider/model/account_id 筛选。
        get("/llm/calls") {
            if (!call.adminGuard(adminToken)) return@get
            val beforeParam = call.request.queryParameters["before"]
            val before = beforeParam?.toLongOrNull()
            if (beforeParam != null && before == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val accountIdParam = call.request.queryParameters["account_id"]
            val accountId = accountIdParam?.toIntOrNull()
            if (accountIdParam != null && accountId == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }
            val provider = call.request.queryParameters["provider"]?.takeIf { it.isNotBlank() }
            val model = call.request.queryParameters["model"]?.takeIf { it.isNotBlank() }
            val limit = 50
            val rows = AdminQueries.llmCalls(before, limit, status, provider, model, accountId)
            // 游标 = 当页末行 id；满页才给下一页链接，防末页死循环
            val nextBefore = if (rows.size == limit) rows.last().id else null
            call.respondText(
                AdminViews.llmCallsPage(rows, status, provider, model, accountId, nextBefore),
                ContentType.Text.Html,
            )
        }

        // Agent 会话列表：seq 倒序游标分页（before_seq），account_id 筛选。
        get("/agent/sessions") {
            if (!call.adminGuard(adminToken)) return@get
            val beforeSeqParam = call.request.queryParameters["before_seq"]
            val beforeSeq = beforeSeqParam?.toLongOrNull()
            if (beforeSeqParam != null && beforeSeq == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val accountIdParam = call.request.queryParameters["account_id"]
            val accountId = accountIdParam?.toIntOrNull()
            if (accountIdParam != null && accountId == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val limit = 50
            val rows = AdminQueries.agentSessions(beforeSeq, limit, accountId)
            val nextBeforeSeq = if (rows.size == limit) rows.last().seq else null
            call.respondText(
                AdminViews.agentSessionsPage(rows, accountId, nextBeforeSeq),
                ContentType.Text.Html,
            )
        }

        // 会话回放：事件 id 升序（after 游标，单页 200）；未知 sessionId → 404。
        get("/agent/sessions/{id}") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val afterParam = call.request.queryParameters["after"]
            val after = afterParam?.toLongOrNull()
            if (afterParam != null && after == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val session = AdminQueries.agentSessionSummary(id)
            if (session == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val limit = 200
            val events = AdminQueries.agentSessionEvents(id, after ?: 0, limit)
            val nextAfter = if (events.size == limit) events.last().id else null
            call.respondText(
                AdminViews.agentSessionDetailPage(session, events, nextAfter),
                ContentType.Text.Html,
            )
        }

        get("/settings") {
            if (!call.adminGuard(adminToken)) return@get
            val msg = call.request.queryParameters["msg"]
            call.respondText(
                AdminViews.settingsPage(SettingsService.snapshot(), AiEngineerWhitelistService.list(), msg),
                ContentType.Text.Html,
            )
        }

        post("/settings") {
            if (!call.adminGuard(adminToken)) return@post
            val params = call.receiveParameters()
            val free = params["free_llm_quota"]?.toIntOrNull()
            val guest = params["guest_llm_quota"]?.toIntOrNull()
            val browserDaily = params["browser_daily_quota"]?.toIntOrNull()
            if (free == null || guest == null || free <= 0 || guest <= 0 ||
                (browserDaily != null && browserDaily <= 0)
            ) {
                call.respondText(
                    AdminViews.settingsPage(
                        SettingsService.snapshot(),
                        AiEngineerWhitelistService.list(),
                        "参数错误：值必须是正整数",
                    ),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            SettingsService.update(free, guest, browserDailyQuota = browserDaily)
            call.respondRedirect("/admin/settings")
        }

        // ── AI 工程师白名单（设置页）──
        post("/settings/whitelist") {
            if (!call.adminGuard(adminToken)) return@post
            val params = call.receiveParameters()
            val email = (params["email"] ?: "").trim().lowercase()
            val msg = when {
                email.isBlank() || !email.contains("@") -> "请输入有效邮箱"
                AiEngineerWhitelistService.allow(email) -> "已添加 $email"
                else -> "$email 已在白名单中"
            }
            call.respondRedirect("/admin/settings?msg=${java.net.URLEncoder.encode(msg, "UTF-8")}#whitelist")
        }

        post("/settings/whitelist/revoke") {
            if (!call.adminGuard(adminToken)) return@post
            val params = call.receiveParameters()
            val email = (params["email"] ?: "").trim().lowercase()
            val msg = when {
                email.isBlank() -> "请输入有效邮箱"
                AiEngineerWhitelistService.revoke(email) -> "已移除 $email"
                else -> "$email 不在白名单中"
            }
            call.respondRedirect("/admin/settings?msg=${java.net.URLEncoder.encode(msg, "UTF-8")}#whitelist")
        }

        get("/channels") {
            if (!call.adminGuard(adminToken)) return@get
            val channels = ChannelRepository.list()
            val usage = AdminQueries.channelUsage()
            val balances = channels.associate { it.id to balanceService.cached(it.id) }
            call.respondText(AdminViews.channelsPage(channels, usage, balances), ContentType.Text.Html)
        }

        get("/channels/new") {
            if (!call.adminGuard(adminToken)) return@get
            call.respondText(AdminViews.channelFormPage(), ContentType.Text.Html)
        }

        get("/channels/{id}/edit") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            val row = if (id != null) ChannelRepository.get(id) else null
            if (id == null || row == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(AdminViews.channelFormPage(row), ContentType.Text.Html)
        }

        // 供列表「复制」按钮调用：返回完整 token（cookie 鉴权；不进列表 HTML）。
        get("/channels/{id}/token") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            val token = if (id != null) ChannelRepository.rawToken(id) else null
            if (token == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val body = buildJsonObject { put("token", token) }.toString()
            call.respondText(body, ContentType.Application.Json)
        }

        post("/channels") {
            if (!call.adminGuard(adminToken)) return@post
            val input = call.parseChannelInput()
            if (input == null) {
                call.respondText(
                    AdminViews.channelsPage(
                        ChannelRepository.list(), AdminQueries.channelUsage(), emptyMap(),
                        error = "表单参数错误：检查 model_map 格式（每行 请求名=上游名）",
                    ),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            try {
                ChannelRepository.create(input)
            } catch (e: Exception) {
                call.respondText(
                    AdminViews.channelsPage(
                        ChannelRepository.list(), AdminQueries.channelUsage(), emptyMap(),
                        error = "创建失败：名称可能重复",
                    ),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            ChannelRegistry.reload()
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            val input = call.parseChannelInput()
            if (id != null && input != null) {
                try {
                    ChannelRepository.update(id, input)
                } catch (e: Exception) {
                    // 唯一约束冲突等：忽略，回列表
                }
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}/activate") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                ChannelRepository.setActive(id)
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        // 刷新上游余额（缓存+手动刷新策略）。失败不报错，列表显「—」。
        post("/channels/{id}/refresh-balance") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) balanceService.refresh(id)
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}/toggle") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                val current = ChannelRepository.get(id)
                if (current != null) ChannelRepository.setEnabled(id, !current.enabled)
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}/delete") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                ChannelRepository.delete(id)
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        // ── Skill 中心（spike 2026-11-07）：技能资产管理与 LLM 视角预览。
        // 保存/删除即热更新（SkillCenter 磁盘直读、每轮重扫），无需重新部署。
        get("/skills") {
            if (!call.adminGuard(adminToken)) return@get
            val msg = call.request.queryParameters["msg"]
            call.respondText(
                AdminViews.skillsPage(
                    skills = skillCenter?.listDetailed() ?: emptyList(),
                    promptSection = skillCenter?.promptSection() ?: "",
                    message = msg,
                ),
                ContentType.Text.Html,
            )
        }

        get("/skills/new") {
            if (!call.adminGuard(adminToken)) return@get
            call.respondText(AdminViews.skillEditPage(existing = null), ContentType.Text.Html)
        }

        // 预览页（2026-11-07 v2）：三 Tab = LLM 视角 / 原文 SKILL.md / 渲染预览（commonmark 服务端渲染）
        get("/skills/{name}/preview") {
            if (!call.adminGuard(adminToken)) return@get
            val name = call.parameters["name"].orEmpty()
            val tab = call.request.queryParameters["tab"] ?: "llm"
            val detail = skillCenter?.detail(name)
                ?: return@get call.respondText("skill_not_found: $name", ContentType.Text.Html, HttpStatusCode.NotFound)
            call.respondText(AdminViews.skillPreviewPage(detail, tab), ContentType.Text.Html)
        }

        get("/skills/{name}") {
            if (!call.adminGuard(adminToken)) return@get
            val name = call.parameters["name"].orEmpty()
            val detail = skillCenter?.detail(name)
                ?: return@get call.respondText("skill_not_found: $name", ContentType.Text.Html, HttpStatusCode.NotFound)
            call.respondText(AdminViews.skillEditPage(existing = detail), ContentType.Text.Html)
        }

        post("/skills") {
            if (!call.adminGuard(adminToken)) return@post
            val params = call.receiveParameters()
            val name = params["name"].orEmpty()
            val description = params["description"].orEmpty()
            val body = params["body"].orEmpty()
            val error = if (skillCenter == null) "skill center 未启用"
            else skillCenter.upsert(name, description, body)
            if (error != null) {
                // 校验失败原样回填（SkillDetail 临时投影，不落盘）
                val draft = SkillCenter.SkillDetail(
                    meta = SkillCenter.SkillMeta(name.trim(), description.trim()),
                    body = body.trim(), updatedAtMs = 0L, rawChars = body.length,
                )
                call.respondText(
                    AdminViews.skillEditPage(existing = draft, error = error),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            call.respondRedirect("/admin/skills?msg=已创建 ${name.trim()}")
        }

        post("/skills/{name}") {
            if (!call.adminGuard(adminToken)) return@post
            val oldName = call.parameters["name"].orEmpty()
            val params = call.receiveParameters()
            val newName = params["name"].orEmpty().trim()
            val description = params["description"].orEmpty()
            val body = params["body"].orEmpty()
            // 重命名（目录迁移）：表单名 ≠ 路由名时，先防覆盖再写新目录、删旧目录
            val sc = skillCenter ?: return@post call.respondText(
                "skill center 未启用", ContentType.Text.Html, HttpStatusCode.ServiceUnavailable,
            )
            val renaming = newName != oldName
            var error: String? = when {
                newName.isBlank() -> "名称不能为空"
                renaming && sc.detail(newName) != null ->
                    "重命名失败：技能 $newName 已存在（写入会覆盖，已拒绝——先删除或换名）"
                else -> sc.upsert(newName, description, body)
            }
            if (error == null && renaming && !sc.delete(oldName)) {
                // 旧目录删除失败不回滚新写入（少见；提示人工清理，避免静默双技能）
                error = "已写入 $newName，但旧目录 $oldName 删除失败——请在服务器手动清理（当前两技能并存）"
            }
            if (error != null) {
                val draft = SkillCenter.SkillDetail(
                    meta = SkillCenter.SkillMeta(newName, description.trim()),
                    body = body.trim(), updatedAtMs = 0L, rawChars = body.length,
                )
                call.respondText(
                    AdminViews.skillEditPage(existing = draft, error = error),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            call.respondRedirect(
                "/admin/skills?msg=" + if (renaming) "已重命名 $oldName → $newName" else "已保存 $newName",
            )
        }

        post("/skills/{name}/delete") {
            if (!call.adminGuard(adminToken)) return@post
            val name = call.parameters["name"].orEmpty()
            val ok = skillCenter?.delete(name) == true
            call.respondRedirect("/admin/skills?msg=" + (if (ok) "已删除 $name" else "删除失败 $name"))
        }

        // ── 技能调试 playground（2026-11-07）：草稿内存 overlay 跑一轮真实云端 Agent。
        // 草稿不落盘（OverlaySkillCenter 内存覆盖同名磁盘项，其余透传）、不耗配额（admin
        // 限定）、不碰生产资产；设备工具无真机——写帧即抛，立即 error: device_offline
        // 结构化降级（模型看到的工具清单与生产一致）。跑轮异步进行，立即跳事件回放页。
        get("/skills/debug") {
            if (!call.adminGuard(adminToken)) return@get
            val name = call.request.queryParameters["name"].orEmpty()
            val prefill = skillCenter?.detail(name)
            call.respondText(
                AdminViews.skillDebugPage(
                    skills = skillCenter?.listDetailed() ?: emptyList(),
                    name = prefill?.meta?.name ?: name,
                    description = prefill?.meta?.description.orEmpty(),
                    body = prefill?.body.orEmpty(),
                ),
                ContentType.Text.Html,
            )
        }

        post("/skills/debug/run") {
            if (!call.adminGuard(adminToken)) return@post
            val params = call.receiveParameters()
            val name = params["name"].orEmpty().trim()
            val description = params["description"].orEmpty().trim()
            val body = params["body"].orEmpty().trim()
            val testMessage = params["test_message"].orEmpty().trim()
            suspend fun fail(error: String) {
                call.respondText(
                    AdminViews.skillDebugPage(
                        skills = skillCenter?.listDetailed() ?: emptyList(),
                        name = name, description = description, body = body,
                        testMessage = testMessage, error = error,
                    ),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
            }
            // 校验与 upsert 同口径（但不落盘——草稿只活在本轮内存）
            if (!SkillCenter.NAME_REGEX.matches(name)) {
                fail("技能名非法：只允许小写字母/数字/连字符（1~64 位，字母或数字开头）")
                return@post
            }
            if (description.isEmpty()) {
                fail("description 不能为空（LLM 判断相关性的唯一依据）")
                return@post
            }
            if (description.contains('\n') || description.contains('\r')) {
                fail("description 必须单行")
                return@post
            }
            if (body.isEmpty()) {
                fail("正文不能为空")
                return@post
            }
            if (testMessage.isEmpty()) {
                fail("测试消息不能为空")
                return@post
            }
            val center = skillCenter ?: run { fail("skill center 未启用"); return@post }
            val runner = agentRunner ?: run { fail("agent runner 未启用"); return@post }
            val channel = ChannelRegistry.active() ?: run { fail("无活跃 LLM 渠道，请先在「渠道」页配置"); return@post }
            val target = AgentTargetResolver.fromChannel(channel, SKILL_DEBUG_MODEL)
                ?: run { fail("渠道 ${channel.name} 不可用（缺上游 token 或默认模型）"); return@post }

            val draft = SkillCenter.SkillDetail(
                meta = SkillCenter.SkillMeta(name, description),
                body = body, updatedAtMs = 0L, rawChars = body.length,
            )
            val overlay = OverlaySkillCenter(center, draft)
            val session = AgentSessionRepository.create(SKILL_DEBUG_OWNER_HASH, AgentChannelKind.GATEWAY, SKILL_DEBUG_MODEL)
            skillDebugScope.launch {
                val deviceTools = agentBridge?.let { bridge ->
                    DeviceToolService(session.id, bridge, DeviceToolService.DEFAULT_TIMEOUT_MS) {
                        throw IllegalStateException("skill debug: device writer absent")
                    }
                }
                // 与 /agent messages 路由同装配：服务端工具 + load_skill + 设备目录工具
                val toolRegistry = ToolRegistry {
                    tools(
                        ServerToolService().asToolsByClass() +
                            LoadSkillToolService(overlay).asToolsByClass() +
                            (deviceTools?.tools() ?: emptyList()),
                    )
                }
                try {
                    withTimeoutOrNull(SKILL_DEBUG_ROUND_TIMEOUT_MS) {
                        runner.runTurn(
                            session = session,
                            userMessage = testMessage,
                            target = target,
                            // system prompt 与生产同构：OpenUI 回合原生 + 技能清单段（overlay 版）
                            systemPrompt = OpenUiTurnNative.SYSTEM_PROMPT + overlay.promptSection(),
                            toolRegistry = toolRegistry,
                        )
                    }
                } catch (e: Throwable) {
                    // 与 /agent 路由同口径：异常置 ERROR；事件已落库，回放页可见全程
                    if (e !is CancellationException) {
                        AgentSessionRepository.updateStatus(session.id, AgentSessionStatus.ERROR)
                    }
                }
            }
            call.respondRedirect("/admin/skills/debug/result/${session.id}")
        }

        // 调试结果页：跑轮中自动刷新，完成后聚合展示（load_skill/最终程序/终态耗时）
        get("/skills/debug/result/{id}") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"].orEmpty()
            val session = AdminQueries.agentSessionSummary(id)
                ?: return@get call.respondText("session_not_found: $id", ContentType.Text.Html, HttpStatusCode.NotFound)
            val events = AdminQueries.agentSessionEvents(id, after = 0, limit = 200)
            call.respondText(AdminViews.skillDebugResultPage(session, events), ContentType.Text.Html)
        }

        // ── 问题诊断页（用户上报问题；AI 工程师白名单已迁至 /admin/settings）──
        get("/diagnosis") {
            if (!call.adminGuard(adminToken)) return@get
            val msg = call.request.queryParameters["msg"]
            call.respondText(
                AdminViews.diagnosisPage(issues = issueReportService.list(), message = msg),
                ContentType.Text.Html,
            )
        }

        // 旧路径 301 重定向到设置页白名单区块
        get("/ai-engineer-whitelist") {
            call.respondRedirect("/admin/settings#whitelist", permanent = true)
        }

        post("/diagnosis/issues/{id}/status") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            val status = call.receiveParameters()["status"]
            val msg = if (id != null && status != null && status in IssueReportService.ALLOWED_STATUSES) {
                issueReportService.updateStatus(id, status)
                "状态已更新"
            } else {
                "参数错误"
            }
            call.respondRedirect("/admin/diagnosis?msg=${java.net.URLEncoder.encode(msg, "UTF-8")}")
        }

        post("/diagnosis/issues/{id}/sync-github") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            val msg = if (id != null) {
                val issue = issueReportService.list().firstOrNull { it.id == id }
                if (issue != null) {
                    val ok = issueReportService.syncToGithub(issue.id, issue.title, issue.description)
                    if (ok) "已同步到 GitHub" else "同步失败，请检查 GITHUB_TOKEN"
                } else {
                    "问题不存在"
                }
            } else {
                "参数错误"
            }
            call.respondRedirect("/admin/diagnosis?msg=${java.net.URLEncoder.encode(msg, "UTF-8")}")
        }

        // ── 发包：Android APK + iOS IPA 合并页（Tab 切换） ──
        get("/release") {
            if (!call.adminGuard(adminToken)) return@get
            val tab = call.request.queryParameters["tab"]?.takeIf { it == "ios" } ?: "android"
            val msg = call.request.queryParameters["msg"]
            val html = if (tab == "ios") {
                val ipaInfo = cosService.getIpaInfo()
                AdminViews.releasePage(
                    tab = tab,
                    message = msg,
                    ios = AdminViews.IosReleaseData(
                        fileExists = ipaInfo.exists,
                        fileSize = ipaInfo.size,
                        lastModified = ipaInfo.lastModified,
                        version = ipaInfo.version,
                        cosUrl = ipaInfo.publicUrl,
                        cosConfigured = cosService.configured,
                        udidList = AdminQueries.iosUdidList(),
                    ),
                )
            } else {
                val debugInfo = cosService.getApkInfo(CosService.CHANNEL_DEBUG)
                val releaseInfo = cosService.getApkInfo(CosService.CHANNEL_RELEASE)
                AdminViews.releasePage(
                    tab = tab,
                    message = msg,
                    android = AdminViews.AndroidReleaseData(
                        debug = AdminViews.AndroidTrackInfo(
                            fileExists = debugInfo.exists,
                            fileSize = debugInfo.size,
                            lastModified = debugInfo.lastModified,
                            version = debugInfo.version,
                            versionCode = debugInfo.versionCode,
                            cosUrl = debugInfo.publicUrl,
                        ),
                        release = AdminViews.AndroidTrackInfo(
                            fileExists = releaseInfo.exists,
                            fileSize = releaseInfo.size,
                            lastModified = releaseInfo.lastModified,
                            version = releaseInfo.version,
                            versionCode = releaseInfo.versionCode,
                            cosUrl = releaseInfo.publicUrl,
                        ),
                        cosConfigured = cosService.configured,
                        history = AdminQueries.apkUploadHistory(30),
                    ),
                )
            }
            call.respondText(html, ContentType.Text.Html)
        }

        // 旧入口保留兼容：跳转到发包页对应 Tab
        get("/apk") {
            if (!call.adminGuard(adminToken)) return@get
            call.respondRedirect("/admin/release?tab=android")
        }

        post("/apk/upload") {
            if (!call.adminGuard(adminToken)) return@post
            // X-Admin-Token header 鉴权 = CLI 模式：响应 JSON 而非浏览器跳转（ota-publish.sh 消费）
            val cliMode = AdminAuth.isValidHeader(call.request.headers[AdminAuth.TOKEN_HEADER], adminToken)
            // APK 通常 50-150MB，放宽 multipart 限制到 200MB
            val multipart = call.receiveMultipart(200L * 1024 * 1024)
            var uploaded = false
            var errorMsg: String? = null
            var channel = CosService.CHANNEL_RELEASE
            var channelInvalid = false
            var version = ""
            var versionCode = 0L
            var changelog = ""
            var fileName = ""
            var fileSize = 0L
            var fileReceived = false
            val tmpFile = java.io.File.createTempFile("apk-upload-", ".apk")
            try {
                multipart.forEachPart { part ->
                    when {
                        part is PartData.FormItem && part.name == "channel" -> {
                            val raw = part.value.trim()
                            if (CosService.isValidChannel(raw)) channel = raw else channelInvalid = true
                        }
                        part is PartData.FormItem && part.name == "version" -> {
                            version = part.value.trim()
                        }
                        part is PartData.FormItem && part.name == "versionCode" -> {
                            versionCode = part.value.trim().toLongOrNull() ?: 0
                        }
                        part is PartData.FormItem && part.name == "changelog" -> {
                            changelog = part.value.trim()
                        }
                        part is PartData.FileItem && part.name == "apkfile" -> {
                            fileName = part.originalFileName ?: ""
                            if (!fileName.endsWith(".apk", ignoreCase = true)) {
                                errorMsg = "文件格式错误：请上传 .apk 文件"
                            } else {
                                try {
                                    val channel1 = part.provider()
                                    tmpFile.outputStream().use { output ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val read = channel1.readAvailable(buffer)
                                            if (read <= 0) break
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                    fileSize = tmpFile.length()
                                    fileReceived = true
                                } catch (e: Exception) {
                                    errorMsg = "上传失败：${e.message}"
                                }
                            }
                        }
                    }
                    part.dispose()
                }
                // channel 字段可能出现在文件之后，统一直到最后判定：非法渠道不上传、不记历史
                if (!channelInvalid && fileReceived && errorMsg == null) {
                    uploaded = cosService.uploadApk(tmpFile.inputStream(), fileSize, version, versionCode, changelog, channel)
                    if (!uploaded) {
                        errorMsg = "COS 上传失败：检查 COS 配置或凭证"
                    }
                }
            } finally {
                tmpFile.delete()
            }
            val msg = when {
                channelInvalid -> "channel 非法：仅支持 debug | release"
                uploaded -> "成功上传 v$version 到 COS（$channel 渠道）"
                errorMsg != null -> errorMsg
                else -> "未收到文件"
            }
            // 写入上传历史记录（非法渠道属请求错误，不留痕）
            if (!channelInvalid) {
                transaction(Db.instance) {
                    ApkUploads.insert {
                        it[ApkUploads.channel] = channel
                        it[ApkUploads.version] = version
                        it[ApkUploads.fileName] = fileName
                        it[ApkUploads.fileSize] = fileSize
                        it[ApkUploads.status] = if (uploaded) "success" else "failed"
                        it[ApkUploads.message] = if (uploaded) null else errorMsg
                        it[ApkUploads.createdAt] = System.currentTimeMillis()
                    }
                }
            }
            if (cliMode) {
                call.respondText(
                    buildJsonObject {
                        put("ok", uploaded)
                        put("channel", channel)
                        put("message", msg ?: "")
                    }.toString(),
                    ContentType.Application.Json,
                )
            } else {
                call.respondRedirect("/admin/release?tab=android&msg=${java.net.URLEncoder.encode(msg, "UTF-8")}")
            }
        }

        // ── iOS Ad-Hoc 自测分发管理 ──

        get("/ios") {
            if (!call.adminGuard(adminToken)) return@get
            call.respondRedirect("/admin/release?tab=ios")
        }

        post("/ios/upload") {
            if (!call.adminGuard(adminToken)) return@post
            // IPA 通常 20-80MB，放宽 multipart 限制到 200MB
            val multipart = call.receiveMultipart(200L * 1024 * 1024)
            var uploaded = false
            var errorMsg: String? = null
            var version = ""
            var fileName = ""
            var fileSize = 0L
            val tmpFile = java.io.File.createTempFile("ipa-upload-", ".ipa")
            try {
                multipart.forEachPart { part ->
                    when {
                        part is PartData.FormItem && part.name == "version" -> {
                            version = part.value.trim()
                        }
                        part is PartData.FileItem && part.name == "ipafile" -> {
                            fileName = part.originalFileName ?: ""
                            if (!fileName.endsWith(".ipa", ignoreCase = true)) {
                                errorMsg = "文件格式错误：请上传 .ipa 文件"
                            } else {
                                try {
                                    val channel = part.provider()
                                    tmpFile.outputStream().use { output ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val read = channel.readAvailable(buffer)
                                            if (read <= 0) break
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                    fileSize = tmpFile.length()
                                    // 版本号未手填时尝试从文件名解析（如 polang-1.0.0.ipa）
                                    if (version.isBlank()) {
                                        version = fileName.substringBeforeLast(".ipa")
                                            .substringAfterLast("-").ifBlank { "" }
                                    }
                                    uploaded = cosService.uploadIpa(tmpFile.inputStream(), fileSize, version)
                                    if (!uploaded && errorMsg == null) {
                                        errorMsg = "COS 上传失败：检查 COS 配置或凭证"
                                    }
                                } catch (e: Exception) {
                                    errorMsg = "上传失败：${e.message}"
                                }
                            }
                        }
                    }
                    part.dispose()
                }
            } finally {
                tmpFile.delete()
            }
            val msg = when {
                uploaded -> "成功上传 iOS v${version.ifBlank { "?" }} 到 COS"
                errorMsg != null -> errorMsg
                else -> "未收到文件"
            }
            call.respondRedirect("/admin/release?tab=ios&msg=${java.net.URLEncoder.encode(msg, "UTF-8")}")
        }

        // 一键导出 UDID 纯文本（每行一个，方便贴进 Apple Developer → Devices）
        get("/ios/udids.txt") {
            if (!call.adminGuard(adminToken)) return@get
            val udidList = AdminQueries.iosUdidList()
            val text = udidList.joinToString(separator = "\n") { row -> row.udid }
            call.respondText(text, ContentType.Text.Plain)
        }
    }
}

/** 受保护页面统一鉴权：空 token → 503；X-Admin-Token header 直连（CLI）→ 放行；cookie 无效 → 跳登录。返回 false 表示已响应、调用方应 return。 */
private suspend fun ApplicationCall.adminGuard(adminToken: String): Boolean {
    if (adminToken.isBlank()) {
        respondText("admin disabled", contentType = ContentType.Text.Plain, status = HttpStatusCode.ServiceUnavailable)
        return false
    }
    if (AdminAuth.isValidHeader(request.headers[AdminAuth.TOKEN_HEADER], adminToken)) return true
    val cookie = request.cookies[AdminAuth.COOKIE_NAME]
    if (!AdminAuth.isValid(cookie, adminToken)) {
        respondRedirect("/admin/login")
        return false
    }
    return true
}

/** 是否走 HTTPS：nginx 终止 TLS 时按 X-Forwarded-Proto 判断；本地 http dev 返回 false（cookie 不加 Secure）。 */
private fun ApplicationCall.isHttps(): Boolean =
    request.headers["X-Forwarded-Proto"]?.equals("https", ignoreCase = true) == true

/** 解析渠道表单为 ChannelInput；model_map 解析失败或校验不过返回 null。 */
private suspend fun ApplicationCall.parseChannelInput(): ChannelInput? {
    val params = receiveParameters()
    val modelMap = try {
        parseModelMapLines(params["model_map"] ?: "")
    } catch (e: IllegalArgumentException) {
        return null
    }
    val name = (params["name"] ?: "").trim()
    val baseUrl = (params["base_url"] ?: "").trim()
    if (name.isEmpty() || baseUrl.isEmpty()) return null
    if (name.length > 32) return null
    return ChannelInput(
        name = name,
        kind = (params["kind"] ?: "direct").trim(),
        baseUrl = baseUrl,
        authStyle = (params["auth_style"] ?: "bearer").trim(),
        apiToken = (params["api_token"] ?: "").trim(),
        modelMap = modelMap,
        enabled = (params["enabled"] ?: "0") == "1",
        defaultModel = (params["default_model"] ?: "").trim(),
        balanceUrl = (params["balance_url"] ?: "").trim(),
    )
}

/** 概览/流量页时间范围白名单解析：仅允许给定集合，非法或缺省回落 default。 */
private fun parseDays(raw: String?, allowed: List<Int>, default: Int): Int =
    raw?.toIntOrNull()?.let { if (it in allowed) it else default } ?: default

/** 概览/流量页指标白名单解析：仅允许 calls/tokens/cost/bytes，其余回落 calls。 */
private fun parseMetric(raw: String?): String =
    if (raw != null && raw in listOf("calls", "tokens", "cost", "bytes")) raw else "calls"
