package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回合契约变体槽测试（U1-2）：buildSystemPrompt 缺省参数组装的输出与现网 server
 * SYSTEM_PROMPT 逐字节同源——特征断言自 server OpenUiTurnNativeTest 拷贝（server 侧
 * 测试原位钉 server 断言，本件钉 shared 契约）；端侧变体替换身份行、剔工具句。
 */
class OpenUiTurnNativeContractTest {

    @Test
    fun defaultPromptPinsServerContractBytes() {
        val prompt = OpenUiTurnNativeContract.buildSystemPrompt()
        // 身份行槽缺省 = 云端 Agent（首字节起钉）
        assertTrue(prompt.startsWith("你是 PoLang 云端 Agent。"), "缺省身份行应为云端 Agent")
        // 特征断言（拷自 server OpenUiTurnNativeTest）
        assertTrue(prompt.contains("你的整个回复就是一颗 OpenUI Lang 1.0 程序"))
        assertTrue(prompt.contains("// summary:"))
        assertTrue(prompt.contains("root = "))
        assertTrue(prompt.contains(OpenUiPromptAssets.GOLDEN_STATS_PROGRAM))
        assertTrue(prompt.contains(OpenUiTurnNativeContract.GOLDEN_CHAT_PROGRAM))
        // toolNote 槽缺省在场（server_time 工具句）
        assertTrue(prompt.contains("需要服务器时间时调用 server_time 工具；"), "缺省工具句应内嵌")
        assertTrue(prompt.contains("server_time"))
        // 组件计数行：清单真内嵌且计数与白名单同源
        assertTrue(
            prompt.contains("可用组件全量清单（${RenderOpenUiValidator.ALLOWED_COMPONENTS.size} 个"),
            "组件计数行应与 ALLOWED_COMPONENTS.size 同源",
        )
        RenderOpenUiValidator.ALLOWED_COMPONENTS.forEach { name ->
            assertTrue(prompt.contains(name), "系统提示词缺组件 $name")
        }
        // DSL 规则整段同源内嵌（零漂移面最大的一段）
        assertTrue(prompt.contains(OpenUiPromptAssets.DSL_RULES), "DSL_RULES 应整段内嵌")
        // 契约主体字节（黄金示例前后衔接）
        assertTrue(prompt.contains("黄金示例（用户问「明天去故宫穿什么合适」）：\n" + OpenUiTurnNativeContract.GOLDEN_CHAT_PROGRAM))
        assertTrue(prompt.contains("相册数据查询照旧调用搜索工具，\n结果尾部的 handle 按数据禁令绑定进程序。"))
        // 模式覆盖槽缺省 = 空串（server 现网逐字节不变：覆盖段缺席）
        assertFalse(prompt.contains("模式覆盖"), "缺省不应含模式覆盖段")
    }

    @Test
    fun toolModeOverrideSlotInjectsVerbatimBetweenQueryTailAndDslRules() {
        val override = "\n模式覆盖（测试注入）。\n"
        val prompt = OpenUiTurnNativeContract.buildSystemPrompt(toolModeOverride = override)
        // 紧邻缝逐字钉住：取数收尾段 + 覆盖段 + DSL 规则首行（防 trim/漂移）
        assertTrue(
            prompt.contains("绑定进程序。\n\n模式覆盖（测试注入）。\n约束（必须全部满足"),
            "覆盖段应紧邻取数收尾段与 DSL 规则之间，无 trim 无漂移",
        )
        // 缺省参数输出与显式传缺省常量的输出逐字节一致（槽零扰动）
        assertEquals(
            OpenUiTurnNativeContract.buildSystemPrompt(),
            OpenUiTurnNativeContract.buildSystemPrompt(toolModeOverride = OpenUiTurnNativeContract.DEFAULT_TOOL_MODE_OVERRIDE),
        )
    }

    @Test
    fun variantSlotsSwapIdentityLineAndDropToolNote() {
        val prompt = OpenUiTurnNativeContract.buildSystemPrompt(
            identityLine = "你是 PoLang 相册 AI 助手。",
            toolNote = "",
        )
        // 身份行替换（变体槽生效）
        assertTrue(prompt.startsWith("你是 PoLang 相册 AI 助手。"), "变体身份行应替换")
        assertFalse(prompt.contains("你是 PoLang 云端 Agent"), "原云端身份行应被替换")
        // toolNote 置空：server_time 句剔除
        assertFalse(prompt.contains("server_time"), "端侧变体不应含 server_time 工具句")
        // 主体契约不随变体槽变化（逐字节同源部分恒定）
        assertTrue(prompt.contains("你的整个回复就是一颗 OpenUI Lang 1.0 程序"))
        assertTrue(prompt.contains(OpenUiTurnNativeContract.GOLDEN_CHAT_PROGRAM))
        assertTrue(prompt.contains(OpenUiPromptAssets.DSL_RULES))
        assertTrue(prompt.contains("相册数据查询照旧调用搜索工具，\n结果尾部的 handle 按数据禁令绑定进程序。"))
    }
}
