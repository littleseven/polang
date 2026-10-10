package com.mamba.picme.server.auth

/**
 * 注册验证码邮件语言，与 App [I18N] 红线五语同步（EN/zh-CN/zh-TW/ES/FR）。
 *
 * 另设 [ZH_EN] 中英双语缺省模板：客户端未携带 lang（旧客户端）或语言无法识别时，
 * 用中文+英文并列发出，保证两类用户都能读懂。
 */
enum class EmailLanguage(val tag: String) {
    EN("en"),
    ZH_CN("zh-CN"),
    ZH_TW("zh-TW"),
    ES("es"),
    FR("fr"),
    ZH_EN("zh-CN+en");

    val subject: String
        get() = when (this) {
            EN -> "PoLang verification code"
            ZH_CN -> "PoLang 验证码"
            ZH_TW -> "PoLang 驗證碼"
            ES -> "Código de verificación de PoLang"
            FR -> "Code de vérification PoLang"
            ZH_EN -> "PoLang 验证码 / Verification Code"
        }

    fun html(code: String): String {
        val (intro, validity) = when (this) {
            EN -> "Your PoLang verification code is:" to "The code is valid for 10 minutes."
            ZH_CN -> "你的 PoLang 验证码是：" to "验证码 10 分钟内有效。"
            ZH_TW -> "你的 PoLang 驗證碼是：" to "驗證碼 10 分鐘內有效。"
            ES -> "Tu código de verificación de PoLang es:" to "El código es válido durante 10 minutos."
            FR -> "Votre code de vérification PoLang est :" to "Le code est valable 10 minutes."
            ZH_EN ->
                "你的 PoLang 验证码是：<br>Your PoLang verification code is:" to
                    "验证码 10 分钟内有效。<br>The code is valid for 10 minutes."
        }
        return """
            <p>$intro</p>
            <h2 style="font-size:32px;letter-spacing:4px;">$code</h2>
            <p>$validity</p>
        """.trimIndent()
    }

    companion object {
        /**
         * 解析客户端上报的 BCP-47 语言标签（如 `zh-CN`/`zh-Hant`/`es-MX`）。
         * null/blank（向后兼容未携带语言的旧客户端）或无法识别 → ZH_EN 中英双语缺省模板。
         */
        fun resolve(lang: String?): EmailLanguage {
            if (lang.isNullOrBlank()) return ZH_EN
            val tag = lang.trim().lowercase().replace('_', '-')
            return when {
                tag.startsWith("zh") ->
                    if (tag.contains("tw") || tag.contains("hk") || tag.contains("mo") || tag.contains("hant")) {
                        ZH_TW
                    } else {
                        ZH_CN
                    }
                tag.startsWith("en") -> EN
                tag.startsWith("es") -> ES
                tag.startsWith("fr") -> FR
                else -> ZH_EN
            }
        }
    }
}
