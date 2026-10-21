package com.mamba.picme.server.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DEBUG_QUOTA_BYPASS env 解析：豁免默认关，仅显式 true/1 开启（头可伪造，此开关是总闸）。 */
class AppConfigEnvBoolTest {

    @Test
    fun `null and blank are off`() {
        assertFalse(AppConfig.parseEnvBool(null))
        assertFalse(AppConfig.parseEnvBool(""))
    }

    @Test
    fun `true and 1 are on case-insensitively`() {
        assertTrue(AppConfig.parseEnvBool("true"))
        assertTrue(AppConfig.parseEnvBool("TRUE"))
        assertTrue(AppConfig.parseEnvBool("1"))
    }

    @Test
    fun `other values are off`() {
        assertFalse(AppConfig.parseEnvBool("false"))
        assertFalse(AppConfig.parseEnvBool("0"))
        assertFalse(AppConfig.parseEnvBool("yes"))
    }
}
