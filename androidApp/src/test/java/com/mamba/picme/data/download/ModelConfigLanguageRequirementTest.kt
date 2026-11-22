package com.mamba.picme.data.download

import com.mamba.picme.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 必备模型语言条件化（2026-11-22 用户决策）：
 * 非中文用户（系统语言非中文 且 应用语言非中文）免除 en→zh 汉化模型（opus-mt-en-zh，423MB）。
 */
class ModelConfigLanguageRequirementTest {

    @Test
    fun `non-Chinese users are exempt from en-zh translation model`() {
        assertFalse(ModelConfig.isChineseLanguageContext(AppLanguage.ENGLISH, Locale.ENGLISH))
        assertFalse(ModelConfig.isChineseLanguageContext(AppLanguage.SYSTEM, Locale.ENGLISH))
        assertFalse(ModelConfig.isChineseLanguageContext(AppLanguage.SPANISH, Locale.JAPAN))

        val required = ModelConfig.requiredModelIds(needsChineseLabels = false)
        assertFalse("opus-mt-en-zh" in required)
        assertEquals(ModelConfig.REQUIRED_MODEL_IDS_CORE, required)
        // 其余核心模型不受影响
        assertTrue("florence2_base" in required)
        assertTrue("opus-mt-zh-en" in required)
    }

    @Test
    fun `Chinese app or system language keeps en-zh model required`() {
        assertTrue(ModelConfig.isChineseLanguageContext(AppLanguage.CHINESE, Locale.ENGLISH))
        assertTrue(ModelConfig.isChineseLanguageContext(AppLanguage.TRADITIONAL_CHINESE, Locale.ENGLISH))
        assertTrue(ModelConfig.isChineseLanguageContext(AppLanguage.SYSTEM, Locale.SIMPLIFIED_CHINESE))
        assertTrue(ModelConfig.isChineseLanguageContext(AppLanguage.SYSTEM, Locale.TRADITIONAL_CHINESE))
        // 系统中文 + 应用显式英文 → 仍按中文对待（任一为中文即需要）
        assertTrue(ModelConfig.isChineseLanguageContext(AppLanguage.ENGLISH, Locale.SIMPLIFIED_CHINESE))

        assertTrue("opus-mt-en-zh" in ModelConfig.requiredModelIds(needsChineseLabels = true))
    }
}
