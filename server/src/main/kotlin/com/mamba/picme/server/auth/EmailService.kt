package com.mamba.picme.server.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("picme-email")

class EmailService(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val fromEmail: String = "noreply@polang.net",
) {
    suspend fun sendVerificationCode(email: String, code: String, lang: String? = null): Boolean {
        if (apiKey.isBlank()) {
            logger.warn("RESEND_API_KEY not configured, skipping email to {}", email)
            return false
        }

        val language = EmailLanguage.resolve(lang)
        return try {
            val resp = httpClient.post("https://api.resend.com/emails") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("from", fromEmail)
                    put("to", email)
                    put("subject", language.subject)
                    put("html", language.html(code))
                }.toString())
            }
            logger.info("Verification email sent to {}, lang={}, status={}", email, language.tag, resp.status.value)
            resp.status.value in 200..299
        } catch (e: Exception) {
            logger.error("Failed to send email to {}", email, e)
            false
        }
    }
}
