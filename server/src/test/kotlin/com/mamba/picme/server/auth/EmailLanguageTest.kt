package com.mamba.picme.server.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailLanguageTest {

    // ── resolve：缺省与空值（中英双语兜底）──────────────────

    @Test
    fun `null lang falls back to bilingual ZH_EN for legacy clients`() {
        assertEquals(EmailLanguage.ZH_EN, EmailLanguage.resolve(null))
    }

    @Test
    fun `blank lang falls back to bilingual ZH_EN`() {
        assertEquals(EmailLanguage.ZH_EN, EmailLanguage.resolve(""))
        assertEquals(EmailLanguage.ZH_EN, EmailLanguage.resolve("   "))
    }

    // ── resolve：中文分支 ────────────────────────────────────

    @Test
    fun `simplified chinese variants resolve to ZH_CN`() {
        assertEquals(EmailLanguage.ZH_CN, EmailLanguage.resolve("zh"))
        assertEquals(EmailLanguage.ZH_CN, EmailLanguage.resolve("zh-CN"))
        assertEquals(EmailLanguage.ZH_CN, EmailLanguage.resolve("zh-Hans"))
        assertEquals(EmailLanguage.ZH_CN, EmailLanguage.resolve("zh-Hans-CN"))
        assertEquals(EmailLanguage.ZH_CN, EmailLanguage.resolve("ZH_cn"))
        assertEquals(EmailLanguage.ZH_CN, EmailLanguage.resolve("zh_CN"))
    }

    @Test
    fun `traditional chinese variants resolve to ZH_TW`() {
        assertEquals(EmailLanguage.ZH_TW, EmailLanguage.resolve("zh-TW"))
        assertEquals(EmailLanguage.ZH_TW, EmailLanguage.resolve("zh-HK"))
        assertEquals(EmailLanguage.ZH_TW, EmailLanguage.resolve("zh-MO"))
        assertEquals(EmailLanguage.ZH_TW, EmailLanguage.resolve("zh-Hant"))
        assertEquals(EmailLanguage.ZH_TW, EmailLanguage.resolve("zh-Hant-TW"))
    }

    // ── resolve：其他语言 ────────────────────────────────────

    @Test
    fun `regional variants resolve by language prefix`() {
        assertEquals(EmailLanguage.EN, EmailLanguage.resolve("en"))
        assertEquals(EmailLanguage.EN, EmailLanguage.resolve("en-US"))
        assertEquals(EmailLanguage.EN, EmailLanguage.resolve("en_GB"))
        assertEquals(EmailLanguage.ES, EmailLanguage.resolve("es"))
        assertEquals(EmailLanguage.ES, EmailLanguage.resolve("es-MX"))
        assertEquals(EmailLanguage.ES, EmailLanguage.resolve("es-419"))
        assertEquals(EmailLanguage.FR, EmailLanguage.resolve("fr"))
        assertEquals(EmailLanguage.FR, EmailLanguage.resolve("fr-CA"))
    }

    @Test
    fun `unrecognized lang falls back to bilingual ZH_EN`() {
        assertEquals(EmailLanguage.ZH_EN, EmailLanguage.resolve("ja"))
        assertEquals(EmailLanguage.ZH_EN, EmailLanguage.resolve("de-DE"))
        assertEquals(EmailLanguage.ZH_EN, EmailLanguage.resolve("not-a-locale"))
    }

    // ── subject：五语主题 + 双语缺省 ─────────────────────────

    @Test
    fun `subjects are localized for all five languages`() {
        assertEquals("PoLang verification code", EmailLanguage.EN.subject)
        assertEquals("PoLang 验证码", EmailLanguage.ZH_CN.subject)
        assertEquals("PoLang 驗證碼", EmailLanguage.ZH_TW.subject)
        assertEquals("Código de verificación de PoLang", EmailLanguage.ES.subject)
        assertEquals("Code de vérification PoLang", EmailLanguage.FR.subject)
    }

    @Test
    fun `bilingual default subject contains both chinese and english`() {
        val subject = EmailLanguage.ZH_EN.subject
        assertTrue(subject.contains("验证码"))
        assertTrue(subject.contains("Verification Code"))
    }

    // ── html：正文包含验证码与对应语言文案 ────────────────────

    @Test
    fun `html contains the code for every language`() {
        EmailLanguage.entries.forEach { lang ->
            assertTrue("html of $lang should contain code", lang.html("123456").contains("123456"))
        }
    }

    @Test
    fun `html bodies are localized`() {
        assertTrue(EmailLanguage.EN.html("000000").contains("verification code"))
        assertTrue(EmailLanguage.ZH_CN.html("000000").contains("验证码"))
        assertTrue(EmailLanguage.ZH_TW.html("000000").contains("驗證碼"))
        assertTrue(EmailLanguage.ES.html("000000").contains("código de verificación"))
        assertTrue(EmailLanguage.FR.html("000000").contains("code de vérification"))
    }

    @Test
    fun `bilingual default html contains both chinese and english`() {
        val html = EmailLanguage.ZH_EN.html("000000")
        assertTrue(html.contains("你的 PoLang 验证码是："))
        assertTrue(html.contains("Your PoLang verification code is:"))
        assertTrue(html.contains("验证码 10 分钟内有效。"))
        assertTrue(html.contains("The code is valid for 10 minutes."))
    }
}
