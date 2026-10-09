package com.mamba.picme.server

import com.mamba.picme.server.admin.adminRoute
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.DEVICE_ID_HEADER
import com.mamba.picme.server.auth.EmailService
import com.mamba.picme.server.auth.PLATFORM_HEADER
import com.mamba.picme.server.browser.BrowserBridgeClient
import com.mamba.picme.server.browser.BrowserConcurrencyRegistry
import com.mamba.picme.server.browser.BrowserSessionStats
import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.Migrations
import com.mamba.picme.server.issue.GitHubIssueClient
import com.mamba.picme.server.issue.IssueReportService
import com.mamba.picme.server.llm.LlmProxy
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.llm.ChannelBalanceService
import com.mamba.picme.server.llm.llmRoute
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.routes.DeviceIdKey
import com.mamba.picme.server.routes.EmailKey
import com.mamba.picme.server.routes.PlatformKey
import com.mamba.picme.server.routes.TokenHashKey
import com.mamba.picme.server.routes.accountDeletionRoute
import com.mamba.picme.server.routes.appLatestRoute
import com.mamba.picme.server.routes.guestDeletionRoute
import com.mamba.picme.server.routes.authRoute
import com.mamba.picme.server.routes.browserRoute
import com.mamba.picme.server.routes.claudeChatRoute
import com.mamba.picme.server.routes.claudeDeliverRoute
import com.mamba.picme.server.routes.claudeEngineerAvailabilityRoute
import com.mamba.picme.server.routes.claudeToolResultRoute
import com.mamba.picme.server.routes.issueReportRoute
import com.mamba.picme.server.routes.downloadRoute
import com.mamba.picme.server.routes.healthzRoute
import com.mamba.picme.server.routes.iosDownloadRoute
import com.mamba.picme.server.routes.quotaRoute
import com.mamba.picme.server.routes.recommendRoute
import com.mamba.picme.server.routes.telemetryRoute
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.websocket.WebSockets
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("picme-server")

val appJson = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

fun main() {
    val config = AppConfig.load()
    Db.init(config.dbPath)
    Migrations.run(config)
    runBlocking { ChannelRegistry.reload() }
    runBlocking { SettingsService.load() }
    runBlocking {
        val purged = AccountService.purgeExpiredDeleted(AccountService.RETENTION_MS)
        logger.info("Purged $purged expired deleted accounts (retention=${AccountService.RETENTION_MS}ms)")
    }
    embeddedServer(CIO, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

// Public routes that don't require token auth
private val publicRoutes = setOf(
    "/healthz",
    "/auth/email/send",
    "/auth/email/verify",
    "/api/app/latest",
    "/download",
    "/download/ios",
    "/download/ios/manifest.plist",
    "/download/ios/udid",
    "/guest/device",
)

fun Application.module(config: AppConfig) {
    install(CallLogging) { level = Level.INFO }
    install(DefaultHeaders)
    install(WebSockets)
    install(ContentNegotiation) { json(appJson) }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "malformed request body"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to (cause.message ?: "invalid argument")))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception in request", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (cause.message ?: "internal error")))
        }
    }

    // --- Auth interceptor ---
    intercept(ApplicationCallPipeline.Plugins) {
        val uri = call.request.local.uri.substringBefore("?")
        // /admin/** 由 admin 路由组自己的 cookie 拦截认证，不走 app-token
        if (uri in publicRoutes || uri == "/admin" || uri.startsWith("/admin/")) return@intercept

        val rawToken = call.request.headers[APP_TOKEN_HEADER]
        val platform = call.request.headers[PLATFORM_HEADER]?.takeIf { it.isNotBlank() }
        platform?.let { call.attributes.put(PlatformKey, it) }
        val authResult = rawToken?.let { AccountService.validateToken(it) }
        if (authResult?.valid == true) {
            // 有效账号 token → 存 hash + email 供下游额度校验与白名单判定
            authResult.tokenHash?.let { call.attributes.put(TokenHashKey, it) }
            authResult.email?.let { call.attributes.put(EmailKey, it) }
            // 注册用户请求若带 X-Device-Id,亦存 DeviceIdKey 供后台 device 维度展示(device_id 列)
            call.request.headers[DEVICE_ID_HEADER]?.takeIf { it.isNotBlank() }?.let {
                call.attributes.put(DeviceIdKey, it)
            }
            return@intercept
        }

        // 无有效账号 token → 仅在 LLM 代理路径上允许设备级访客试用
        val isLlmPath = uri == "/chat/completions" || uri == "/v1/chat/completions"
        val deviceId = call.request.headers[DEVICE_ID_HEADER]
        if (isLlmPath && !deviceId.isNullOrBlank()) {
            call.attributes.put(DeviceIdKey, deviceId)
            return@intercept
        }

        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
        finish()
        return@intercept
    }

    // --- HttpClient (shared by LLM proxy + email) ---
    val httpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        engine { requestTimeout = 60_000 }
    }
    // claude-tunnel 反代用：SSE 流式长连接（GLM 推理 + 多轮可能数分钟），不限 requestTimeout，
    // 靠 KimiClaw 网关 CT_PHASE_TIMEOUT（300s）兜底。
    val claudeClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        engine { requestTimeout = 0 }
    }

    val llmProxy = LlmProxy(
        httpClient = httpClient,
        maxTokensCap = config.maxTokensCap,
    )
    val rateLimiter = if (config.rateLimitPerMin > 0) RateLimiter(config.rateLimitPerMin) else null
    val emailService = EmailService(httpClient, config.resendApiKey, config.emailFrom)

    val cosService = CosService(config)
    val balanceService = ChannelBalanceService(httpClient)
    val githubIssueClient = GitHubIssueClient(httpClient, config.githubToken, config.githubIssueRepo)
    val issueReportService = IssueReportService(githubIssueClient)
    // 每账号每天最多 10 条问题上报
    val issueReportRateLimiter = RateLimiter(10, 24 * 60 * 60_000L)

    // Browser 网关（spec §6：网关 30s 超时；每日配额走 SettingsService provider，admin 热改即时生效）
    val browserHttpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        engine { requestTimeout = 30_000 }
    }
    val browserBridge = BrowserBridgeClient(browserHttpClient, config.browserBridgeUrl, config.browserBridgeToken)
    val browserConcurrency = BrowserConcurrencyRegistry()
    val browserDailyLimiter = RateLimiter({ SettingsService.snapshot().browserDailyQuota }, 24 * 60 * 60_000L)
    val browserStats = BrowserSessionStats()

    routing {
        // Public
        downloadRoute(cosService)
        iosDownloadRoute(cosService)
        appLatestRoute(cosService)
        healthzRoute()
        authRoute(emailService)

        // Protected (auth interceptor above enforces token)
        recommendRoute(appJson)
        telemetryRoute()
        quotaRoute()
        accountDeletionRoute()
        guestDeletionRoute()
        llmRoute(llmProxy, rateLimiter, config.llmPrices)
        claudeEngineerAvailabilityRoute()
        claudeChatRoute(claudeClient, rateLimiter)
        claudeDeliverRoute(claudeClient, rateLimiter)
        claudeToolResultRoute(claudeClient, rateLimiter)
        issueReportRoute(issueReportService, issueReportRateLimiter)
        browserRoute(browserBridge, browserDailyLimiter, browserConcurrency, browserStats)
        // 管理后台（/admin/**，独立 cookie 认证）
        adminRoute(config.adminToken, cosService, balanceService, config.llmPrices, issueReportService)
    }
}
