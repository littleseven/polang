package com.mamba.picme.server.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终结程序守卫测试（turn-native T5，原假发卡守卫的回合原生泛化）：
 * 非法终结程序触发纠正回环（携带错误清单），上限 [ProgramGuard.MAX_CORRECTIONS]
 * 用尽后放行（由终结消息变换兜底包装）。
 */
class ProgramGuardTest {

    @Test
    fun `valid program passes without correction`() {
        val guard = ProgramGuard()
        assertNull(guard.invalidProgramError("root = Card([Text(\"hi\")])"))
    }

    @Test
    fun `invalid program triggers correction with error details`() {
        val guard = ProgramGuard()
        val error = guard.invalidProgramError("没有 root 的文字")
        assertNotNull(error)
        assertTrue(error!!.contains("缺 root"))
        assertTrue(ProgramGuard.correctionPrompt(error).contains("重新输出完整程序"))
    }

    @Test
    fun `corrections exhausted after MAX_CORRECTIONS`() {
        val guard = ProgramGuard()
        repeat(ProgramGuard.MAX_CORRECTIONS) {
            assertNotNull(guard.invalidProgramError("bad"))
            guard.markCorrected()
        }
        // 上限用尽：放行（由终结消息变换兜底包装），不再纠正
        assertNull(guard.invalidProgramError("bad"))
    }
}
