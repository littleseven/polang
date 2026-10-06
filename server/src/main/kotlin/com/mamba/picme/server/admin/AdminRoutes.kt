package com.mamba.picme.server.admin

import com.mamba.picme.server.analytics.Price
import com.mamba.picme.server.analytics.defaultPrices
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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

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
) {
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
