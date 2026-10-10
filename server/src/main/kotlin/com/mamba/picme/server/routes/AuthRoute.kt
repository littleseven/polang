package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.DEVICE_ID_HEADER
import com.mamba.picme.server.auth.EmailService
import com.mamba.picme.server.auth.GuestService
import com.mamba.picme.server.config.SettingsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable

val TokenHashKey = AttributeKey<String>("tokenHash")

val EmailKey = AttributeKey<String>("email")

val DeviceIdKey = AttributeKey<String>("deviceId")

val PlatformKey = AttributeKey<String>("platform")

@Serializable
data class EmailSendRequest(val email: String, val lang: String? = null)

@Serializable
data class EmailVerifyRequest(val email: String, val code: String)

@Serializable
data class TokenResponse(val token: String, val llmCallsUsed: Int, val llmCallsLimit: Int)

@Serializable
data class QuotaResponse(val email: String, val llmCallsUsed: Int, val llmCallsLimit: Int)

fun Route.authRoute(
    emailService: EmailService,
) {
    post("/auth/email/send") {
        val req = call.receive<EmailSendRequest>()
        val code = AccountService.createVerification(req.email)
        val sent = emailService.sendVerificationCode(req.email, code, req.lang)
        if (sent) {
            call.respond(mapOf("sent" to true))
        } else {
            // Dev mode without RESEND_API_KEY — code is in server logs
            call.respond(mapOf("sent" to true, "dev" to true))
        }
    }

    post("/auth/email/verify") {
        val req = call.receive<EmailVerifyRequest>()
        val valid = AccountService.verifyCode(req.email, req.code)
        if (!valid) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_code"))
            return@post
        }
        val account = AccountService.createOrRefresh(req.email, SettingsService.snapshot().freeLlmQuota)
        call.respond(
            TokenResponse(
                token = account.token,
                llmCallsUsed = account.llmCallsUsed,
                llmCallsLimit = account.llmCallsLimit,
            )
        )
    }
}

fun Route.quotaRoute() {
    get("/auth/quota") {
        val tokenHash = call.attributes[TokenHashKey]
        val quota = AccountService.getQuota(tokenHash)
        if (quota == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token"))
        } else {
            call.respond(
                QuotaResponse(
                    email = quota.email,
                    llmCallsUsed = quota.llmCallsUsed,
                    llmCallsLimit = quota.llmCallsLimit,
                )
            )
        }
    }
}

fun Route.accountDeletionRoute() {
    delete("/auth/account") {
        val tokenHash = call.attributes[TokenHashKey]   // 由 auth interceptor 注入
        val ok = AccountService.softDelete(tokenHash)
        call.response.status(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }
}

fun Route.guestDeletionRoute() {
    delete("/guest/device") {
        val deviceId = call.request.headers[DEVICE_ID_HEADER]
        val ok = !deviceId.isNullOrBlank()
        if (ok) {
            GuestService.deleteByDeviceId(deviceId!!)
        }
        call.response.status(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
    }
}
