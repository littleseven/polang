package com.mamba.picme.server.admin

import org.junit.Assert.assertTrue
import org.junit.Test

class AdminViewsTest {

    private fun rangeFixture(days: List<DayBucket> = emptyList(), byModel: List<DimStat> = emptyList()): RangeStats =
        RangeStats(days, byModel, emptyList(), emptyList(), emptyList(), LatencyStats(0, 0, 0),
            DayBucket("合计", 0, 0, 0, 0, 0, 0.0, 0, 0))

    @Test
    fun `overview page renders delta cards trend chart and model share bars`() {
        val ov = OverviewRow(2L, 1L, 5L, 1234L, 1.5, 4096L, 1L, 0L, 0L, 0L, 0.0)
        val range = rangeFixture(
            days = listOf(DayBucket("2026-07-12", 5L, 1L, 600L, 634L, 1234L, 1.5, 4096L, 0L)),
            byModel = listOf(DimStat("glm-5.2", 3L, 1000L, 5.0)),
        )
        val html = AdminViews.overviewPage(ov, range, days = 7, metric = "calls", browser = BrowserOverview(3L, 1L, 0.125))
        assertTrue(html.contains("今日调用"))
        assertTrue(html.contains("累计"))
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("模型 Top"))
        assertTrue(html.contains("share-bar-fill"))
        assertTrue(html.contains("Browser sessions: today 3 · active 1 · 7d failure 12.5%"))
    }

    @Test
    fun `users page lists emails, detail links and masked api tokens`() {
        val rows = listOf(
            UserRow(1, "a@x.com", "active", 0L, 3L, 100L, 0.5, null, "picm••••wxyz", true, "device••••1234", "android"),
            UserRow(2, "b@x.com", "active", 0L, 0L, 0L, 0.0, null, "—", false, "—", "—"),
        )
        val html = AdminViews.usersPage(rows, devicesCount = 0L)
        assertTrue(html.contains("a@x.com"))
        assertTrue(html.contains("/admin/users/1"))
        // API Token 列：有明文者显示掩码 + 复制按钮；原用量列改名「Token 用量」消歧
        assertTrue(html.contains("API Token"))
        assertTrue(html.contains("picm••••wxyz"))
        assertTrue(html.contains("tokCopy(1, this)"))
        assertTrue(html.contains("Token 用量"))
        assertTrue(html.contains("Device ID"))
        assertTrue(html.contains("平台"))
        assertTrue(html.contains("device••••1234"))
        assertTrue(html.contains("android"))
        assertTrue(html.contains("未注册设备")) // 二级 Tab 出现
    }

    @Test
    fun `login page has a password form`() {
        val html = AdminViews.loginPage(failed = true)
        assertTrue(html.contains("<form"))
        assertTrue(html.contains("name=\"password\""))
        assertTrue(html.contains("密码错误"))
    }

    @Test
    fun `traffic page renders range tabs detail table with total row and top lists`() {
        val range = RangeStats(
            days = listOf(DayBucket("2026-07-12", 1L, 0L, 10L, 5L, 15L, 0.1, 100L, 0L)),
            byModel = listOf(DimStat("deepseek-chat", 1L, 15L, 0.1)),
            byProvider = listOf(DimStat("CLOUDFLARE", 1L, 15L, 0.1)),
            topUsers = listOf(TopStat(1, "a***@x.com", 1L, 15L, 0.1)),
            topDevices = emptyList(),
            latency = LatencyStats(1, 120, 120),
            totals = DayBucket("合计", 1L, 0L, 10L, 5L, 15L, 0.1, 100L, 0L),
        )
        val html = AdminViews.trafficPage(range, days = 30, metric = "calls")
        assertTrue(html.contains("每日明细"))
        assertTrue(html.contains("合计"))
        assertTrue(html.contains("2026-07-12"))
        assertTrue(html.contains("构成"))
        assertTrue(html.contains("异常 Top"))
        assertTrue(html.contains("blocked 率"))
    }

    @Test
    fun `overview page renders sub-cent cost with precision not zero`() {
        // 今日成本 0.0044 元：%.2f 会显示 "0.00"，计费看起来没生效
        val ov = OverviewRow(1L, 0L, 5L, 7944L, 0.0044, 819L, 0L, 0L, 0L, 0L, 0.0)
        val html = AdminViews.overviewPage(ov, rangeFixture(), days = 7, metric = "calls", browser = BrowserOverview(0L, 0L, 0.0))
        assertTrue("sub-cent cost must not round to 0.00", html.contains("0.0044"))
    }

    @Test
    fun `devices page lists masked ids quota and delete action`() {
        val rows = listOf(
            DeviceRow(1, "abcdef••••7890", 5, 1_700_000_000_000L, 1_700_000_001_000L, "android"),
            DeviceRow(2, "zzzzzz••••1111", 100, 1_700_000_000_000L, 1_700_000_002_000L, "ios"),
        )
        val html = AdminViews.devicesPage(rows, usersCount = 3L, guestLimit = 100)
        assertTrue(html.contains("未注册设备"))
        assertTrue(html.contains("注册用户 (3)")) // 二级 Tab 计数
        assertTrue(html.contains("未注册设备 (2)")) // 二级 Tab 计数
        assertTrue(html.contains("abcdef••••7890"))
        assertTrue(html.contains("devCopy(1, this)"))
        assertTrue(html.contains("/admin/devices/1/delete"))
        assertTrue(html.contains("5 / 100"))
        assertTrue(html.contains("100 / 100")) // 超额行
        assertTrue(html.contains("btn-danger")) // 删除按钮
        assertTrue(html.contains("平台")) // 表头
        assertTrue(html.contains("android"))
        assertTrue(html.contains("ios"))
    }

    @Test
    fun `devices page renders platform filter tabs and highlights active`() {
        val rows = listOf(
            DeviceRow(1, "abcdef••••7890", 5, 1L, 2L, "android"),
        )
        // no filter → "全部" active
        val htmlAll = AdminViews.devicesPage(rows, usersCount = 1L, guestLimit = 100)
        assertTrue(htmlAll.contains("""href="/admin/devices" class="ctrl active">全部"""))
        assertTrue(htmlAll.contains("/admin/devices?platform=android"))
        assertTrue(htmlAll.contains("/admin/devices?platform=ios"))

        // ios filter → "iOS" active
        val htmlIos = AdminViews.devicesPage(rows, usersCount = 1L, guestLimit = 100, platformFilter = "ios")
        assertTrue(htmlIos.contains("""href="/admin/devices?platform=ios" class="ctrl active">iOS"""))
    }

    @Test
    fun `devices page shows dash for null platform`() {
        val rows = listOf(
            DeviceRow(1, "abcdef••••7890", 5, 1L, 2L, null),
        )
        val html = AdminViews.devicesPage(rows, usersCount = 0L, guestLimit = 100)
        assertTrue(html.contains("—"))
    }

}
