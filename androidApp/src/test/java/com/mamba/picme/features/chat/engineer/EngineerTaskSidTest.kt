package com.mamba.picme.features.chat.engineer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [EngineerTaskSid.chooseActionSid] 分支矩阵（GLM 审查 #1/#3/#4 回归钉死）：
 * 卡片 sid 优先 / VM sid 归属守卫 / 跨会话拒绝。
 */
class EngineerTaskSidTest {

    private val gatewaySid = "aaaa1111bbbb"
    private val uuidSid = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `compliant card sid wins even when vm sid belongs to another session`() {
        assertEquals(
            gatewaySid,
            EngineerTaskSid.chooseActionSid(
                taskSid = gatewaySid,
                vmSid = "cccc2222dddd",
                vmSidOwner = "session-A",
                targetSessionId = "session-B",
            ),
        )
    }

    @Test
    fun `uuid-overwritten card sid falls back to vm sid when it owns the target session`() {
        assertEquals(
            gatewaySid,
            EngineerTaskSid.chooseActionSid(
                taskSid = uuidSid,
                vmSid = gatewaySid,
                vmSidOwner = "session-A",
                targetSessionId = "session-A",
            ),
        )
    }

    @Test
    fun `cross-session action rejects vm sid from another session`() {
        assertNull(
            EngineerTaskSid.chooseActionSid(
                taskSid = uuidSid,
                vmSid = gatewaySid,
                vmSidOwner = "session-A",
                targetSessionId = "session-B",
            ),
        )
    }

    @Test
    fun `null card sid and null vm sid yields null`() {
        assertNull(
            EngineerTaskSid.chooseActionSid(
                taskSid = null,
                vmSid = null,
                vmSidOwner = null,
                targetSessionId = "session-A",
            ),
        )
    }

    @Test
    fun `gateway pattern - 12 hex only`() {
        assert(EngineerTaskSid.isGatewaySid("0123456789ab"))
        assert(!EngineerTaskSid.isGatewaySid(uuidSid))
        assert(!EngineerTaskSid.isGatewaySid("0123456789abc"))
        assert(!EngineerTaskSid.isGatewaySid("ZZZZ456789ab"))
    }
}
