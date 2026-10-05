package com.mamba.picme.server.cos

import com.mamba.picme.server.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * APK 下载 URL 的渠道路由：
 * debug 轨可经 APK_DEBUG_PUBLIC_BASE 指向国内镜像（北京轻量裸 IP），
 * release 轨永远走 cos.polang.net（官网正式下载链路不受镜像影响）。
 */
class CosServicePublicUrlTest {

    private fun config(apkDebugPublicBase: String = "") = AppConfig(
        host = "127.0.0.1",
        port = 8080,
        dbPath = "test.db",
        freeLlmQuota = 0,
        guestLlmQuota = 0,
        cloudflareAigUrl = "",
        cloudflareAigToken = "",
        tokenhubUrl = "",
        tokenhubApiToken = "",
        forceProvider = "",
        maxTokensCap = 0,
        rateLimitPerMin = 0,
        resendApiKey = "",
        emailFrom = "",
        cosSecretId = "",
        cosSecretKey = "",
        cosRegion = "",
        cosBucket = "",
        cosPresignTtlMin = 60,
        adminToken = "",
        llmPrices = emptyMap(),
        githubToken = "",
        githubIssueRepo = "",
        apkDebugPublicBase = apkDebugPublicBase,
    )

    @Test
    fun `debug channel url uses mirror base when configured`() {
        val svc = CosService(config(apkDebugPublicBase = "http://82.157.166.5:8100/dl-token"))
        assertEquals(
            "http://82.157.166.5:8100/dl-token/apk/polang-debug.apk",
            svc.apkPublicUrl(CosService.CHANNEL_DEBUG),
        )
    }

    @Test
    fun `release channel url stays on cos domain when mirror base configured`() {
        val svc = CosService(config(apkDebugPublicBase = "http://82.157.166.5:8100/dl-token"))
        assertEquals(
            "https://cos.polang.net/apk/polang-release.apk",
            svc.apkPublicUrl(CosService.CHANNEL_RELEASE),
        )
    }

    @Test
    fun `trailing slash in mirror base is trimmed`() {
        val svc = CosService(config(apkDebugPublicBase = "http://82.157.166.5:8100/dl-token/"))
        assertEquals(
            "http://82.157.166.5:8100/dl-token/apk/polang-debug.apk",
            svc.apkPublicUrl(CosService.CHANNEL_DEBUG),
        )
    }

    @Test
    fun `blank mirror base keeps debug on cos domain`() {
        val svc = CosService(config(apkDebugPublicBase = ""))
        assertEquals(
            "https://cos.polang.net/apk/polang-debug.apk",
            svc.apkPublicUrl(CosService.CHANNEL_DEBUG),
        )
    }

    @Test
    fun `getApkInfo emits mirror url for debug even when cos unconfigured`() {
        // COS 未配置（client=null）时 available=false，但 URL 必须已经是镜像地址——
        // App 端 /api/app/latest 拿到的就是这个 publicUrl。
        val svc = CosService(config(apkDebugPublicBase = "http://82.157.166.5:8100/dl-token"))
        val info = svc.getApkInfo(CosService.CHANNEL_DEBUG)
        assertFalse(info.exists)
        assertEquals(
            "http://82.157.166.5:8100/dl-token/apk/polang-debug.apk",
            info.publicUrl,
        )
    }
}
