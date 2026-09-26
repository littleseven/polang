package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.agent.core.model.context.RenderEnvironment
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptSuffixTest {

    private val today = "2026-08-22"

    @Test
    fun `default persona suffix is date line plus reply language rule`() {
        val suffix = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertEquals(
            "\n\n当前日期：2026-08-22。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。" +
                "\n\nApp 界面语言为简体中文。请始终用简体中文回复用户，无论本提示词、工具描述或工具返回内容使用何种语言。工具返回内容可能是英文或其他语言——请用简体中文总结转述。",
            suffix
        )
    }

    @Test
    fun `persona segment appended after date line`() {
        val suffix = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.WARM, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertEquals(
            "\n\n当前日期：2026-08-22。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。" +
                "\n\n你的语气温暖贴心：先回应用户的情绪，共情之后再给出回答或建议；多使用肯定与鼓励的措辞，让用户感到被理解和支持。" +
                "\n\nApp 界面语言为简体中文。请始终用简体中文回复用户，无论本提示词、工具描述或工具返回内容使用何种语言。工具返回内容可能是英文或其他语言——请用简体中文总结转述。",
            suffix
        )
    }

    @Test
    fun `persona segment follows reply language`() {
        val en = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.CONCISE, ReplyLanguage.ENGLISH, today
        )
        assertTrue(en.contains("crisp and efficient"))
        assertFalse(en.contains("简洁干练"))
    }

    @Test
    fun `render environment segment appended after date line when provided`() {
        val env = RenderEnvironment(
            deviceType = "Android 手机",
            screenWidthDp = 400,
            screenHeightDp = 890,
            screenWidthPx = 1200,
            screenHeightPx = 2670,
            cardContentWidthCssPx = 352,
            cardSuggestedHeightCssPx = 587,
            previewCardHeightCssPx = 445,
            fullpageThresholdCssPx = 890
        )
        val withEnv = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE, today, renderEnvironment = env
        )
        assertTrue(withEnv.contains("【HTML 卡片渲染环境】设备：Android 手机；屏幕 1200x2670 px（400x890 dp）"))
        assertTrue(withEnv.contains("卡片内容区最大宽度约 352 CSS px"))
        assertTrue(withEnv.contains("建议单卡内容高度控制在约 587 CSS px"))
        // 双形态注入：预览卡固定高（0.5 屏）与分流阈值（1.0 屏）
        assertTrue(withEnv.contains("高约 445 CSS px"))
        assertTrue(withEnv.contains("超过约 890 CSS px"))
        // 环境段紧跟日期行、性格/语言段之前
        assertTrue(withEnv.indexOf("【HTML 卡片渲染环境】") > withEnv.indexOf("当前日期"))
        assertTrue(withEnv.indexOf("【HTML 卡片渲染环境】") < withEnv.indexOf("App 界面语言为简体中文"))

        val withoutEnv = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertFalse(withoutEnv.contains("【HTML 卡片渲染环境】"))
    }

    @Test
    fun `reply language rule segment follows reply language`() {
        val en = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.ENGLISH, today
        )
        assertTrue(en.contains("Always reply to the user in English"))

        val simplified = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertTrue(simplified.contains("始终用简体中文回复"))

        val traditional = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.TRADITIONAL_CHINESE, today
        )
        assertTrue(traditional.contains("始終用繁體中文回覆"))
    }
}
