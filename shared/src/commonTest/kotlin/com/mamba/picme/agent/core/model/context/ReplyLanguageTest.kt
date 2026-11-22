package com.mamba.picme.agent.core.model.context

import com.mamba.picme.domain.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplyLanguageTest {

    @Test
    fun `explicit app languages map directly`() {
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.ENGLISH.toReplyLanguage("zh-CN"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.CHINESE.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.TRADITIONAL_CHINESE.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.SPANISH, AppLanguage.SPANISH.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.FRENCH, AppLanguage.FRENCH.toReplyLanguage("zh-CN"))
    }

    @Test
    fun `system resolves simplified chinese locales`() {
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-CN"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hans"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-SG"))
    }

    @Test
    fun `system resolves traditional chinese locales`() {
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-TW"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hant-HK"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-MO"))
    }

    @Test
    fun `system resolves spanish and french locales`() {
        assertEquals(ReplyLanguage.SPANISH, AppLanguage.SYSTEM.toReplyLanguage("es-ES"))
        assertEquals(ReplyLanguage.SPANISH, AppLanguage.SYSTEM.toReplyLanguage("es-MX"))
        assertEquals(ReplyLanguage.SPANISH, AppLanguage.SYSTEM.toReplyLanguage("es"))
        assertEquals(ReplyLanguage.FRENCH, AppLanguage.SYSTEM.toReplyLanguage("fr-FR"))
        assertEquals(ReplyLanguage.FRENCH, AppLanguage.SYSTEM.toReplyLanguage("fr-CA"))
        assertEquals(ReplyLanguage.FRENCH, AppLanguage.SYSTEM.toReplyLanguage("fr"))
    }

    @Test
    fun `system falls back to english for non chinese locales`() {
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("ja-JP"))
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("de-DE"))
        // "es*"/"fr*" 但非 "es-"/"fr-" 的畸形 tag → 英文（与 "zhcn" 对称，锁定子标签边界）
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("esES"))
    }

    @Test
    fun `system locale tag edge cases`() {
        // 裸 "zh"（无子标签）→ 简体
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh"))
        // 显式 Hans 脚本 + 地区
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hans-CN"))
        // 大写 tag 经 lowercase 归一化
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("ZH-HANT-TW"))
        // 空串 → 英文兜底
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage(""))
        // "zh*" 但非 "zh-" 的畸形 tag → 英文（锁定收紧后的子标签边界）
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("zhcn"))
        // 裸脚本 tag（iOS 真实形态）→ 繁体
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hant"))
    }

    @Test
    fun `system resolves cantonese and underscore locales`() {
        // yue（粤语）按中文同规则解析：iOS 粤语系统语言下 UI 回退繁中，chat 回复语言须与之一致
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("yue-Hant-HK"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("yue-CN"))
        // 裸 "yue"（无子标签）→ 简体（与裸 "zh" 对称）
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("yue"))
        // "yue*" 但非 "yue-" 的畸形 tag → 英文（与 "zhcn" 对称，锁定子标签边界）
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("yuecn"))
        // 下划线分隔形式（iOS Locale.identifier）先归一为 BCP-47 再判定
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh_TW"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh_Hant"))
    }

    // ── 2026-11-22 OpenUI 多语言批次：X-App-Language 头解析 + OpenUI 语言规则段 + 兜底文案 ──

    @Test
    fun `replyLanguageFromName resolves enum names and falls back to simplified chinese`() {
        assertEquals(ReplyLanguage.ENGLISH, replyLanguageFromName("ENGLISH"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, replyLanguageFromName("TRADITIONAL_CHINESE"))
        assertEquals(ReplyLanguage.SPANISH, replyLanguageFromName("SPANISH"))
        assertEquals(ReplyLanguage.FRENCH, replyLanguageFromName("FRENCH"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, replyLanguageFromName("SIMPLIFIED_CHINESE"))
        // 缺失/非法/大小写不符一律回落简中（向后兼容旧端不带头）
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, replyLanguageFromName(null))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, replyLanguageFromName(""))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, replyLanguageFromName("english"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, replyLanguageFromName("zh-CN"))
    }

    @Test
    fun `openUiReplyLanguageRuleSegment covers program text and pins dsl vocabulary`() {
        ReplyLanguage.entries.forEach { language ->
            val segment = openUiReplyLanguageRuleSegment(language)
            // 每段都必须同时钉住两个面：程序内用户可见文案翻译 + DSL 词汇/handle id 不翻译
            assertTrue(segment.contains("// summary"))
            assertTrue(segment.contains("handle id"))
        }
        assertTrue(openUiReplyLanguageRuleSegment(ReplyLanguage.ENGLISH).contains("must always be written in English"))
        assertTrue(openUiReplyLanguageRuleSegment(ReplyLanguage.SIMPLIFIED_CHINESE).contains("简体中文"))
        assertTrue(openUiReplyLanguageRuleSegment(ReplyLanguage.TRADITIONAL_CHINESE).contains("繁體中文"))
        assertTrue(openUiReplyLanguageRuleSegment(ReplyLanguage.SPANISH).contains("español"))
        assertTrue(openUiReplyLanguageRuleSegment(ReplyLanguage.FRENCH).contains("français"))
    }

    @Test
    fun `openUi fallback texts follow reply language`() {
        assertEquals("文本回复", openUiWrappedSummaryLabel(ReplyLanguage.SIMPLIFIED_CHINESE))
        assertEquals("Text reply", openUiWrappedSummaryLabel(ReplyLanguage.ENGLISH))
        assertEquals("回复生成失败，请重试", openUiHardFallbackMessage(ReplyLanguage.SIMPLIFIED_CHINESE))
        assertEquals("Failed to generate the reply, please try again", openUiHardFallbackMessage(ReplyLanguage.ENGLISH))
        assertEquals("錯誤", openUiHardFallbackSummaryLabel(ReplyLanguage.TRADITIONAL_CHINESE))
        assertEquals("Error", openUiHardFallbackSummaryLabel(ReplyLanguage.SPANISH))
        assertEquals("Erreur", openUiHardFallbackSummaryLabel(ReplyLanguage.FRENCH))
    }
}
