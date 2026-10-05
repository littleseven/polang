package com.mamba.picme.domain.update

import org.junit.Assert.assertEquals
import org.junit.Test

class OtaRecoveryPolicyTest {

    private val pending = OtaPendingDownload(downloadId = 42L, versionCode = 10047L, updatedAt = "2026-10-05 12:00:00")

    @Test
    fun `无 pending 走常规检查`() {
        assertEquals(
            OtaRecoveryAction.ProceedCheck,
            OtaRecoveryPolicy.decide(installedVersionCode = 10046L, installedRemoteKey = null, pending = null, dmStatus = null),
        )
    }

    @Test
    fun `pending 存在但 DM 查询失败走常规检查`() {
        assertEquals(
            OtaRecoveryAction.ProceedCheck,
            OtaRecoveryPolicy.decide(10046L, null, pending, dmStatus = null),
        )
    }

    @Test
    fun `下载中且是新版本则静默`() {
        assertEquals(
            OtaRecoveryAction.StaySilent,
            OtaRecoveryPolicy.decide(10046L, null, pending, OtaDmStatus.Running(0.5f)),
        )
    }

    @Test
    fun `下载完成且是新版本则提示安装`() {
        assertEquals(
            OtaRecoveryAction.OfferInstall(10047L),
            OtaRecoveryPolicy.decide(10046L, null, pending, OtaDmStatus.Successful),
        )
    }

    @Test
    fun `下载失败且是新版本则提示重试`() {
        assertEquals(
            OtaRecoveryAction.OfferRetry("reason=1000"),
            OtaRecoveryPolicy.decide(10046L, null, pending, OtaDmStatus.Failed("reason=1000")),
        )
    }

    @Test
    fun `同版本且同构建已安装才判陈旧`() {
        assertEquals(
            OtaRecoveryAction.DiscardStale,
            OtaRecoveryPolicy.decide(
                installedVersionCode = 10047L,
                installedRemoteKey = "10047@2026-10-05 12:00:00",
                pending = pending,
                dmStatus = OtaDmStatus.Successful,
            ),
        )
    }

    @Test
    fun `同版本但不同构建重发不算陈旧`() {
        assertEquals(
            OtaRecoveryAction.StaySilent,
            OtaRecoveryPolicy.decide(
                installedVersionCode = 10047L,
                installedRemoteKey = "10047@2026-10-06 00:00:00",
                pending = pending,
                dmStatus = OtaDmStatus.Running(0.5f),
            ),
        )
    }

    @Test
    fun `同版本但key为旧构建仍提示安装`() {
        // 装后未记账（通知路径安装/记账中断）：不判陈旧，由 OfferInstall 弹窗兜底，
        // 用户点安装经 installNow 记账收口——不能静默清（否则同 vc 重发被吞）。
        assertEquals(
            OtaRecoveryAction.OfferInstall(10047L),
            OtaRecoveryPolicy.decide(10047L, "10047@2026-10-06 00:00:00", pending, OtaDmStatus.Successful),
        )
    }

    @Test
    fun `DM 查无此下载视为陈旧残留需清理`() {
        assertEquals(
            OtaRecoveryAction.DiscardStale,
            OtaRecoveryPolicy.decide(10046L, null, pending, OtaDmStatus.Missing),
        )
    }

    @Test
    fun `版本严格小于已安装也是陈旧残留`() {
        assertEquals(
            OtaRecoveryAction.DiscardStale,
            OtaRecoveryPolicy.decide(10048L, null, pending, OtaDmStatus.Successful),
        )
    }

    @Test
    fun `Running 但同版本同构建已装仍优先判陈旧`() {
        assertEquals(
            OtaRecoveryAction.DiscardStale,
            OtaRecoveryPolicy.decide(
                installedVersionCode = 10047L,
                installedRemoteKey = "10047@2026-10-05 12:00:00",
                pending = pending,
                dmStatus = OtaDmStatus.Running(0.5f),
            ),
        )
    }
}
