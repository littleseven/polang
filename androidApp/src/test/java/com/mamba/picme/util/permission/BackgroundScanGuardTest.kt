package com.mamba.picme.util.permission

import com.mamba.picme.util.permission.BackgroundScanGuard.IssueType
import com.mamba.picme.util.permission.BackgroundScanGuard.evaluate
import com.mamba.picme.util.permission.BackgroundScanGuard.isRowResolved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundScanGuardTest {

    @Test
    fun `all clear and non-MIUI yields no issues`() {
        assertEquals(emptyList<IssueType>(), evaluate(batteryOk = true, notificationsOk = true, isMiui = false))
    }

    @Test
    fun `battery not whitelisted surfaces battery issue`() {
        assertEquals(
            listOf(IssueType.BATTERY_OPTIMIZATION),
            evaluate(batteryOk = false, notificationsOk = true, isMiui = false)
        )
    }

    @Test
    fun `notifications disabled surfaces notifications issue`() {
        assertEquals(
            listOf(IssueType.NOTIFICATIONS),
            evaluate(batteryOk = true, notificationsOk = false, isMiui = false)
        )
    }

    @Test
    fun `MIUI always surfaces autostart issue even when battery and notifications ok`() {
        // MIUI/HyperOS 不暴露自启动读取 API，只要判定为 MIUI 即列入让用户确认。
        assertEquals(
            listOf(IssueType.MIUI_AUTOSTART),
            evaluate(batteryOk = true, notificationsOk = true, isMiui = true)
        )
    }

    @Test
    fun `all missing plus MIUI yields stable ordered list`() {
        assertEquals(
            listOf(IssueType.BATTERY_OPTIMIZATION, IssueType.NOTIFICATIONS, IssueType.MIUI_AUTOSTART),
            evaluate(batteryOk = false, notificationsOk = false, isMiui = true)
        )
    }

    @Test
    fun `battery ordered before notifications before miui`() {
        // 顺序保证 UI 展示稳定。
        val result = evaluate(batteryOk = false, notificationsOk = false, isMiui = true)
        assertEquals(IssueType.BATTERY_OPTIMIZATION, result[0])
        assertEquals(IssueType.NOTIFICATIONS, result[1])
        assertEquals(IssueType.MIUI_AUTOSTART, result[2])
    }

    // ── 弹窗 checklist 行状态（ON_RESUME 复查打勾口径）──

    @Test
    fun `battery and notification rows resolve by system fact`() {
        assertFalse(isRowResolved(IssueType.BATTERY_OPTIMIZATION, stillMissing = true, visited = false))
        assertTrue(isRowResolved(IssueType.BATTERY_OPTIMIZATION, stillMissing = false, visited = false))
        assertTrue(isRowResolved(IssueType.NOTIFICATIONS, stillMissing = false, visited = false))
    }

    @Test
    fun `notification still missing stays unresolved regardless of visited`() {
        // visited 只对 MIUI 行有意义；通知行仍按系统事实判定
        assertFalse(isRowResolved(IssueType.NOTIFICATIONS, stillMissing = true, visited = true))
    }

    @Test
    fun `miui autostart row resolves only by visiting confirmation page`() {
        // MIUI 无读取 API：点进过确认页即视为已处理，与系统事实（stillMissing 恒 true）无关
        assertFalse(isRowResolved(IssueType.MIUI_AUTOSTART, stillMissing = true, visited = false))
        assertTrue(isRowResolved(IssueType.MIUI_AUTOSTART, stillMissing = true, visited = true))
    }
}
