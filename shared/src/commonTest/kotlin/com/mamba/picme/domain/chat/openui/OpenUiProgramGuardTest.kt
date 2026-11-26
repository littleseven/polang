package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 终结程序守卫测试（U1-3，自 server ProgramGuardTest 同口径迁移 + reset 语义）：
 * 门控/上限/跨回合复位/correctionPrompt 文案。
 */
class OpenUiProgramGuardTest {

    private val invalidProgram = "就是一句闲聊，不是程序"

    @Test
    fun invalidProgramYieldsErrorThenExhaustsAtLimit() {
        val guard = OpenUiProgramGuard()
        assertNotNull(guard.invalidProgramError(invalidProgram), "非法程序首轮应返回错误清单")
        repeat(OpenUiProgramGuard.MAX_CORRECTIONS) {
            guard.markCorrected()
        }
        assertNull(guard.invalidProgramError(invalidProgram), "纠正上限用尽后应放行（null）")
    }

    @Test
    fun resetRestoresGateForCrossTurnReuse() {
        val guard = OpenUiProgramGuard()
        guard.markCorrected()
        assertNull(guard.invalidProgramError(invalidProgram), "上限用尽后放行")
        guard.reset()
        assertNotNull(guard.invalidProgramError(invalidProgram), "reset 后应恢复门控")
        guard.markCorrected()
        assertNull(guard.invalidProgramError(invalidProgram), "reset 后计数重新从零计满")
    }

    @Test
    fun validProgramAlwaysPassesGate() {
        val guard = OpenUiProgramGuard()
        assertNull(guard.invalidProgramError("root = Card([Text(\"hi\")])"), "合法程序应放行")
        guard.markCorrected()
        assertNull(guard.invalidProgramError("root = Card([Text(\"hi\")])"), "合法程序与计数无关恒放行")
    }

    @Test
    fun correctionPromptCarriesErrorAndRewriteDirective() {
        val error = "缺 root = 语句"
        val prompt = OpenUiProgramGuard.correctionPrompt(error)
        assertTrue(prompt.contains("逐条修正后重新输出完整程序"), "纠正 prompt 应含重写指令")
        assertTrue(prompt.contains(error), "纠正 prompt 应携带错误正文")
        assertEquals(1, OpenUiProgramGuard.MAX_CORRECTIONS)
    }
}
